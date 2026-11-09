import Foundation

public final class BilibiliExtractor{
    public static let shared=BilibiliExtractor()
    private struct Item{
        let url:String;let bvid:String;let cid:Int64;let episode:Int?;let title:String;let author:String;let thumbnail:String;let duration:Int64;let exclusive:Bool
        var mediaId:String{episode.map{"ep\($0)"} ?? "\(bvid).\(cid)"}
    }
    private func api(_ endpoint:String)async throws->[String:Any]{
        let json=try await ImagePostEngine.json("https://api.bilibili.com/"+endpoint,site:"哔哩哔哩")
        let code=NativeMediaParser.number(json["code"])
        guard code==0 else{throw OmniPlatform.error([-101,-400].contains(code) ? "AUTH_REQUIRED":code == -10403 ? "ACCESS_DENIED":"EXTRACTOR_CHANGED",json["message"] as? String ?? "B站接口未返回可用内容")}
        guard let data=json["data"] as? [String:Any] ?? json["result"] as? [String:Any] else{throw OmniPlatform.error("EXTRACTOR_CHANGED","B站数据不完整")};return data
    }
    public func extractBvid(from url:String)->String?{
        guard let regex=try? NSRegularExpression(pattern:#"(BV[a-zA-Z0-9]{10}|av\d+)"#,options:.caseInsensitive),let match=regex.firstMatch(in:url,range:NSRange(url.startIndex...,in:url)) else{return nil};return (url as NSString).substring(with:match.range(at:1))
    }
    private func item(_ raw:String,media:String?=nil)async throws->(Item,[Item]){
        let url=await UrlSniffer.resolveRedirects(for:raw)
        if let regex=try? NSRegularExpression(pattern:#"/bangumi/play/(ep|ss)(\d+)"#),let match=regex.firstMatch(in:url,range:NSRange(url.startIndex...,in:url)){
            let text=url as NSString,kind=text.substring(with:match.range(at:1)),id=text.substring(with:match.range(at:2))
            let season=try await api("pgc/view/web/season?\(kind=="ep" ? "ep_id":"season_id")=\(id)")
            let title=season["title"] as? String ?? "番剧",episodes=season["episodes"] as? [[String:Any]] ?? []
            let rows=episodes.compactMap{entry->Item? in
                let episode=NativeMediaParser.number(entry["id"]);guard episode>0 else{return nil}
                return Item(url:"https://www.bilibili.com/bangumi/play/ep\(episode)",bvid:entry["bvid"] as? String ?? "",cid:(entry["cid"] as? NSNumber)?.int64Value ?? 0,episode:episode,title:title+" · "+(entry["long_title"] as? String ?? entry["title"] as? String ?? ""),author:"",thumbnail:entry["cover"] as? String ?? season["cover"] as? String ?? "",duration:(entry["duration"] as? NSNumber)?.int64Value ?? 0,exclusive:false)
            }
            guard let chosen=rows.first(where:{$0.episode.map{String($0)}==id}) ?? (kind=="ss" ? rows.first:nil) else{throw OmniPlatform.error("NO_MEDIA","番剧列表没有目标分集")};return(chosen,rows)
        }
        guard let identifier=extractBvid(from:url) else{throw OmniPlatform.error("NO_MEDIA","需要 B站 BV、av、ep 或 ss 内容链接")}
        let data=try await api(identifier.lowercased().hasPrefix("av") ? "x/web-interface/view?aid="+String(identifier.dropFirst(2)):"x/web-interface/view?bvid="+identifier)
        guard let bvid=data["bvid"] as? String,!bvid.isEmpty else{throw OmniPlatform.error("MEDIA_ID_MISMATCH","B站没有返回目标视频 ID")}
        let title=data["title"] as? String ?? bvid,author=(data["owner"] as? [String:Any])?["name"] as? String ?? ""
        let thumbnail=(data["pic"] as? String ?? "").replacingOccurrences(of:"http://",with:"https://")
        let pages=data["pages"] as? [[String:Any]] ?? [],requested=URLComponents(string:url)?.queryItems?.first{$0.name=="p"}?.value.flatMap(Int.init) ?? 1
        let exclusive=(data["is_upower_exclusive"] as? Bool)==true || (data["is_upower_play"] as? Bool)==true
        var rows=pages.enumerated().compactMap{index,page->Item? in
            let cid=(page["cid"] as? NSNumber)?.int64Value ?? 0;guard cid>0 else{return nil}
            return Item(url:"https://www.bilibili.com/video/\(bvid)?p=\(index+1)",bvid:bvid,cid:cid,episode:nil,title:pages.count>1 ? title+" · P\(index+1) "+(page["part"] as? String ?? ""):title,author:author,thumbnail:page["first_frame"] as? String ?? thumbnail,duration:Int64(NativeMediaParser.number(page["duration"]))*1000,exclusive:exclusive)
        }
        if rows.isEmpty,let cid=(data["cid"] as? NSNumber)?.int64Value,cid>0{rows=[Item(url:"https://www.bilibili.com/video/"+bvid,bvid:bvid,cid:cid,episode:nil,title:title,author:author,thumbnail:thumbnail,duration:Int64(NativeMediaParser.number(data["duration"]))*1000,exclusive:exclusive)]}
        let selected=rows.first(where:{$0.mediaId==media}) ?? (rows.indices.contains(requested-1) ? rows[requested-1]:rows.first)
        guard let chosen=selected else{throw OmniPlatform.error("NO_MEDIA","目标视频没有可定位分集")}
        if pages.count<=1,let season=data["ugc_season"] as? [String:Any],let sections=season["sections"] as? [[String:Any]]{
            let group=sections.flatMap{$0["episodes"] as? [[String:Any]] ?? []}.compactMap{episode->Item? in
                guard let bv=episode["bvid"] as? String,let cid=(episode["cid"] as? NSNumber)?.int64Value,cid>0 else{return nil}
                let arc=episode["arc"] as? [String:Any] ?? [:]
                return Item(url:"https://www.bilibili.com/video/"+bv,bvid:bv,cid:cid,episode:nil,title:episode["title"] as? String ?? bv,author:author,thumbnail:arc["pic"] as? String ?? thumbnail,duration:Int64(NativeMediaParser.number(arc["duration"]))*1000,exclusive:exclusive)
            }
            if group.count>1{rows=group}
        }
        return(chosen,rows)
    }
    static func validate(_ play:[String:Any],expected:Int64,exclusive:Bool,accountVerified:Bool)throws{
        let returned=(play["timelength"] as? NSNumber)?.int64Value ?? 0
        if NativeMediaParser.number(play["is_preview"]) != 0 || (expected>0 && returned>0 && returned+2000<expected){throw OmniPlatform.error("ACCESS_DENIED","平台仅返回试看内容，未取得完整视频")}
        if NativeMediaParser.number(play["is_drm"]) != 0{throw OmniPlatform.error("DRM_UNSUPPORTED","该内容受保护，不能导出")}
        if exclusive && !accountVerified{throw OmniPlatform.error("ACCESS_UNCONFIRMED","充电内容需要验证对应账号权益")}
    }
    private func play(_ item:Item)async throws->[String:Any]{
        let path=item.episode.map{"pgc/player/web/playurl?ep_id=\($0)&qn=127&fnval=4048&fourk=1"} ?? "x/player/playurl?bvid=\(item.bvid)&cid=\(item.cid)&qn=127&fnval=4048&fourk=1"
        let raw=try await api(path),data=raw["video_info"] as? [String:Any] ?? raw
        var verified=false
        if item.exclusive,let account=try? await api("x/web-interface/nav"){verified=account["isLogin"] as? Bool==true && NativeMediaParser.number(account["mid"])>0}
        try Self.validate(data,expected:item.duration,exclusive:item.exclusive,accountVerified:verified)
        return data
    }
    private func formats(_ play:[String:Any])->([FormatOption],[FormatOption]){
        let dash=play["dash"] as? [String:Any] ?? [:],videos=dash["video"] as? [[String:Any]] ?? [],audio=dash["audio"] as? [[String:Any]] ?? []
        func address(_ row:[String:Any])->String{row["baseUrl"] as? String ?? row["base_url"] as? String ?? ""}
        let rows=videos.compactMap{row->FormatOption? in
            let source=address(row);guard NativeMediaParser.safeURL(source) else{return nil}
            let w=NativeMediaParser.number(row["width"]),h=NativeMediaParser.number(row["height"]),qn=NativeMediaParser.number(row["id"]),codec=NativeMediaParser.number(row["codecid"])
            let rate=(row["frame_rate"] as? String ?? row["frameRate"] as? String ?? "0").split(separator:"/").compactMap{Double($0)}
            let fps=Int(((rate.first ?? 0)/(rate.count>1 ? max(1,rate[1]):1)).rounded())
            let bitrate=(row["bandwidth"] as? NSNumber)?.doubleValue ?? 0
            return FormatOption(formatId:"bili:\(qn):\(codec):\(w)x\(h):\(fps):\(Int(bitrate))",resolutionLabel:MediaQuality.label(w,h),width:w,height:h,fps:fps,sourceUrl:source,vcodec:row["codecs"] as? String ?? "",dynamicRange:qn==125 ? "HDR10":qn==126 ? "Dolby Vision":"SDR",bitrateKbps:bitrate/1000)
        }
        let audios=audio.compactMap{row->FormatOption? in let source=address(row);guard NativeMediaParser.safeURL(source) else{return nil};return FormatOption(formatId:"bili_audio_\(NativeMediaParser.number(row["id"]))",resolutionLabel:"音频",ext:"m4a",isAudioOnly:true,sourceUrl:source,bitrateKbps:((row["bandwidth"] as? NSNumber)?.doubleValue ?? 0)/1000)}.sorted{($0.bitrateKbps ?? 0)>($1.bitrateKbps ?? 0)}
        if !rows.isEmpty{return(MediaQuality.sorted(rows),audios)}
        let durl=play["durl"] as? [[String:Any]] ?? []
        if durl.count==1,let source=durl[0]["url"] as? String,NativeMediaParser.safeURL(source){return([FormatOption(formatId:"bili_durl",resolutionLabel:"源视频（尺寸未知）",sourceUrl:source)],[])}
        return([],[])
    }
    private func metadata(_ item:Item,video:[FormatOption]=[],audio:[FormatOption]=[])->VideoMetadata{
        VideoMetadata(url:item.url,title:item.title,author:item.author,durationText:String(format:"%02d:%02d",Int(item.duration/60000),Int(item.duration/1000%60)),thumbnailUrl:item.thumbnail,siteName:"哔哩哔哩",availableVideoFormats:video,availableAudioFormats:audio,mediaId:item.mediaId,expectedDurationMs:item.duration)
    }
    public func extract(rawUrl:String)async throws->VideoMetadata{
        let scope=PlatformSession.shared.generation("哔哩哔哩"),pair=try await item(rawUrl),data=try await play(pair.0),streams=formats(data)
        guard !streams.0.isEmpty else{throw OmniPlatform.error("AUTH_REQUIRED","没有取得真实视频流，请检查登录状态与观看权限")}
        guard scope==PlatformSession.shared.generation("哔哩哔哩") else{throw CancellationError()}
        var result=metadata(pair.0,video:streams.0,audio:streams.1)
        result.sourceScope=scope
        if pair.1.count>1{result.multiMediaList=pair.1.prefix(200).map{metadata($0)}}
        MediaSources.shared.remember(result);return result
    }
    public func extractHeightFromResolution(_ label:String)->Int{
        let text=label.lowercased();if text.contains("8k"){return 4320};if text.contains("4k"){return 2160};if text.contains("2k"){return 1440}
        guard let regex=try? NSRegularExpression(pattern:#"(\d{3,5})p"#),let match=regex.firstMatch(in:text,range:NSRange(text.startIndex...,in:text)) else{return 0};return Int((text as NSString).substring(with:match.range(at:1))) ?? 0
    }
    public func fetchStreamUrls(for task:DownloadTask)async throws->(videoUrl:String,audioUrl:String,realTitle:String){
        let scope=PlatformSession.shared.generation("哔哩哔哩"),pair=try await item(task.url,media:task.mediaId),payload=try await play(pair.0),streams=formats(payload)
        if let id=task.mediaId,id != pair.0.mediaId{throw OmniPlatform.error("MEDIA_ID_MISMATCH","原分集已不可定位，请重新解析")}
        guard scope==PlatformSession.shared.generation("哔哩哔哩") else{throw CancellationError()}
        let requested=task.selectedVideoFormatId ?? "",height=extractHeightFromResolution(task.selectedResolution)
        let eligible=streams.0.filter{MediaQuality.meets($0,task)}
        let exact=eligible.first{$0.formatId==requested} ?? eligible.first{!requested.isEmpty && $0.formatId.split(separator:":").dropLast().joined(separator:":")==requested.split(separator:":").dropLast().joined(separator:":")}
        let chosen=exact ?? eligible.first{height>0 && min($0.width,$0.height)==height} ?? eligible.first{height==0 || min($0.width,$0.height)>=height}
        guard let chosen=chosen,MediaQuality.meets(chosen,task) else{throw OmniPlatform.error("QUALITY_UNAVAILABLE","当前账号没有满足所选尺寸、帧率、HDR 与编码要求的真实视频流")}
        if task.downloadType == .audioOnly && streams.1.isEmpty{
            return(chosen.sourceUrl ?? "",chosen.sourceUrl ?? "",pair.0.title)
        }
        return(chosen.sourceUrl ?? "",streams.1.first?.sourceUrl ?? "",pair.0.title)
    }
}
