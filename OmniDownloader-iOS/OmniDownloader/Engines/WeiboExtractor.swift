import Foundation

/// Uses the status endpoint and playback fields also used by upstream yt-dlp's Weibo extractor.
enum WeiboExtractor {
    static func analyze(_ url:String)async throws->VideoMetadata {
        let scope=PlatformSession.shared.generation("微博")
        let json=try await fetch(url)
        guard scope==PlatformSession.shared.generation("微博") else{throw CancellationError()}
        let result=try NativeMediaParser.parse(json,url:url)
        if var video=result.0{video.sourceScope=scope;MediaSources.shared.remember(video);return video}
        if var post=result.1{post.sourceScope=scope;throw MediaRouting.images(post)}
        throw OmniPlatform.error("BROWSER_ASSIST_REQUIRED","微博未提供可用媒体，请验证登录或重试")
    }
    static func fetch(_ url:String)async throws->[String:Any] {
        let id=OmniPlatform.identifier(url)
        guard !id.isEmpty else{throw OmniPlatform.error("BROWSER_ASSIST_REQUIRED","微博短链或视频页需要浏览器验证")}
        var parts=URLComponents(string:"https://weibo.com/ajax/statuses/show")!
        parts.queryItems=[URLQueryItem(name:"id",value:id)]
        let (data,_)=try await OmniNetwork.data(parts.url!,site:"微博")
        guard let item=try JSONSerialization.jsonObject(with:data) as? [String:Any] else{throw OmniPlatform.error("EXTRACTOR_CHANGED","微博未返回有效数据")}
        return try parse(item,url:url)
    }
    static func parse(_ item:[String:Any],url:String)throws->[String:Any] {
        let id=OmniPlatform.identifier(url)
        let identities=["id","idstr","id_str","mid","bid","mblogid"].compactMap{item[$0]}.map{String(describing:$0)}
        guard !identities.isEmpty else{throw OmniPlatform.error("MEDIA_UNCONFIRMED","微博暂未确认目标作品，请验证登录后重试")}
        guard !id.isEmpty,identities.contains(id) else{throw OmniPlatform.error("MEDIA_ID_MISMATCH","微博没有返回目标作品")}
        let title=(item["text_raw"] as? String ?? item["text"] as? String ?? "\(OmniLocalization.text("微博"))_\(id)").replacingOccurrences(of:#"<[^>]+>"#,with:"",options:.regularExpression)
        let author=(item["user"] as? [String:Any])?["screen_name"] as? String ?? ""
        func entry(_ page:[String:Any],identity:String)->[String:Any]? {
            let info=page["media_info"] as? [String:Any] ?? [:]
            var formats:[[String:Any]]=[]
            for row in info["playback_list"] as? [[String:Any]] ?? [] {
                guard let play=row["play_info"] as? [String:Any],let source=play["url"] as? String,NativeMediaParser.safeURL(source) else{continue}
                formats.append(["url":source,"width":NativeMediaParser.number(play["width"]),"height":NativeMediaParser.number(play["height"]),"fps":NativeMediaParser.number(play["fps"]),"vcodec":play["video_codecs"] as? String ?? "","bitrateKbps":Double(NativeMediaParser.number(play["bitrate"]))/1000])
            }
            if formats.isEmpty {
                for key in ["stream_url_hd","stream_url","mp4_720p_mp4","mp4_hd_url","mp4_sd_url"] {
                    if let source=info[key] as? String,NativeMediaParser.safeURL(source){formats.append(["url":source])}
                }
            }
            guard !formats.isEmpty else{return nil}
            let thumb=(page["page_pic"] as? String) ?? (page["page_pic"] as? [String:Any])?["url"] as? String ?? ""
            return ["mediaId":identity,"formats":formats,"durationMs":NativeMediaParser.number(info["duration"])*1000,"thumbnailUrl":thumb]
        }
        var media:[[String:Any]]=[]
        let mixed=(item["mix_media_info"] as? [String:Any])?["items"] as? [[String:Any]] ?? []
        if mixed.isEmpty,let page=item["page_info"] as? [String:Any],let value=entry(page,identity:id){media.append(value)}
        for (index,row) in mixed.enumerated() where row["type"] as? String != "pic" {
            if let data=row["data"] as? [String:Any],let value=entry(["media_info":data["media_info"] ?? [:]],identity:id+"-\(index)"){media.append(value)}
        }
        let images=(item["pics"] as? [[String:Any]] ?? []).compactMap{ row -> [String:Any]? in
            let large=row["large"] as? [String:Any] ?? row
            guard let source=large["url"] as? String,NativeMediaParser.safeURL(source) else{return nil}
            return ["url":source,"width":NativeMediaParser.number(large["width"]),"height":NativeMediaParser.number(large["height"])]
        }
        return ["contentId":id,"title":title,"author":author,"media":media,"images":images]
    }
}
