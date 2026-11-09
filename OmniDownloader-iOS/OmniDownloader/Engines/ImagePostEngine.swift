import Foundation
import JavaScriptCore

enum ImagePostEngine {
    static func json(_ url:String,site:String)async throws->[String:Any]{
        guard let address=URL(string:url) else{throw OmniPlatform.error("NO_MEDIA","链接不合法")}
        let(data,_)=try await OmniNetwork.data(address,site:site)
        guard let object=try JSONSerialization.jsonObject(with:data) as? [String:Any] else{throw OmniPlatform.error("EXTRACTOR_CHANGED","平台响应不是有效数据")};return object
    }
    static func analyze(_ raw:String)async throws->ImagePost{
        let original=UrlSniffer.extractFirstUrl(from:raw) ?? raw,url=await UrlSniffer.resolveRedirects(for:original)
        let site=OmniPlatform.detect(url),id=OmniPlatform.identifier(url)
        let scope=PlatformSession.shared.generation(site)
        guard !id.isEmpty else{throw OmniPlatform.error("BROWSER_ASSIST_REQUIRED","尚未确认目标图文 ID")}
        var post:ImagePost
        if site=="Pixiv"{
            let response=try await json("https://www.pixiv.net/ajax/illust/\(id)",site:site)
            guard response["error"] as? Bool != true,let body=response["body"] as? [String:Any],String(describing:body["id"] ?? "")==id else{throw OmniPlatform.error("AUTH_REQUIRED","没有取得该 Pixiv 作品，请检查登录状态和访问权限")}
            if NativeMediaParser.number(body["illustType"])==2{
                let info=try await json("https://www.pixiv.net/ajax/illust/\(id)/ugoira_meta",site:site)
                guard let metadata=info["body"] as? [String:Any],let zip=metadata["originalSrc"] as? String ?? metadata["src"] as? String,NativeMediaParser.safeURL(zip),let frames=metadata["frames"] as? [[String:Any]],!frames.isEmpty,frames.count<=2000 else{throw OmniPlatform.error("NO_MEDIA","未返回可用 Ugoira 帧")}
                let source=(body["urls"] as? [String:Any])?["original"] as? String ?? ""
                let resources=source.isEmpty ? []:[ImageResource(id:id+"-preview",url:source,width:NativeMediaParser.number(body["width"]),height:NativeMediaParser.number(body["height"]))]
                post=ImagePost(url:url,platform:site,contentId:id,title:body["title"] as? String ?? id,author:body["userName"] as? String ?? "",resources:resources,frameArchive:zip,frameDelays:frames.map{NativeMediaParser.number($0["delay"])},frameNames:frames.compactMap{$0["file"] as? String})
            }else{
                let pages=try await json("https://www.pixiv.net/ajax/illust/\(id)/pages",site:site)
                let resources=(pages["body"] as? [[String:Any]] ?? []).enumerated().compactMap{index,item->ImageResource? in
                    guard let address=(item["urls"] as? [String:Any])?["original"] as? String,NativeMediaParser.safeURL(address) else{return nil}
                    return ImageResource(id:id+"-p\(index)",url:address,width:NativeMediaParser.number(item["width"]),height:NativeMediaParser.number(item["height"]))
                }
                post=ImagePost(url:url,platform:site,contentId:id,title:body["title"] as? String ?? id,author:body["userName"] as? String ?? "",resources:resources)
            }
        }else if site=="哔哩哔哩"{
            let response=try await json("https://api.bilibili.com/x/polymer/web-dynamic/v1/detail?id=\(id)",site:site)
            guard NativeMediaParser.number(response["code"])==0,let item=(response["data"] as? [String:Any])?["item"] as? [String:Any],item["id_str"] as? String==id else{throw OmniPlatform.error("AUTH_REQUIRED","未返回目标动态，请检查登录状态")}
            guard item["type"] as? String != "DYNAMIC_TYPE_FORWARD" else{throw OmniPlatform.error("NO_MEDIA","转发动态请使用原始动态链接")}
            let modules=item["modules"] as? [String:Any] ?? [:],dynamic=modules["module_dynamic"] as? [String:Any] ?? [:],major=dynamic["major"] as? [String:Any] ?? [:]
            let draw=major["draw"] as? [String:Any] ?? [:],opus=major["opus"] as? [String:Any] ?? [:]
            let rows=draw["items"] as? [[String:Any]] ?? opus["pics"] as? [[String:Any]] ?? []
            let resources=rows.enumerated().compactMap{index,row->ImageResource? in
                let source=row["src"] as? String ?? row["url"] as? String ?? "";guard NativeMediaParser.safeURL(source) else{return nil}
                return ImageResource(id:id+"-p\(index)",url:source,width:NativeMediaParser.number(row["width"]),height:NativeMediaParser.number(row["height"]))
            }
            post=ImagePost(url:url,platform:site,contentId:id,title:(dynamic["desc"] as? [String:Any])?["text"] as? String ?? (opus["summary"] as? [String:Any])?["text"] as? String ?? "B站动态 \(id)",author:(modules["module_author"] as? [String:Any])?["name"] as? String ?? "",resources:resources)
        }else if site=="X (Twitter)"{
            let result=try await xPost(url)
            guard let images=try NativeMediaParser.parse(result,url:url).1 else{throw OmniPlatform.error("NO_MEDIA","该帖子没有可导出的原图")};post=images
        }else{throw OmniPlatform.error("BROWSER_ASSIST_REQUIRED","需要自动加载目标图文页")}
        guard !post.resources.isEmpty || post.frameArchive != nil else{throw OmniPlatform.error("NO_MEDIA","目标作品没有可访问的图片")}
        guard scope==PlatformSession.shared.generation(site) else{throw CancellationError()};post.sourceScope=scope;MediaSources.shared.remember(post);return post
    }
    static func xPost(_ url:String)async throws->[String:Any]{
        let id=OmniPlatform.identifier(url)
        guard id.range(of:#"^\d{8,24}$"#,options:.regularExpression) != nil else{throw OmniPlatform.error("MEDIA_ID_MISMATCH","需要目标 X 帖子")}
        let token=JSContext()?.evaluateScript("((Number('\(id)')/1e15)*Math.PI).toString(36).replace(/(0+|\\.)/g,'')").toString() ?? ""
        let item=try await json("https://cdn.syndication.twimg.com/tweet-result?id=\(id)&lang=en&token=\(token)",site:"X (Twitter)")
        guard item["id_str"] as? String==id else{throw OmniPlatform.error("MEDIA_ID_MISMATCH","X 未返回目标帖子")}
        let details=item["mediaDetails"] as? [[String:Any]] ?? []
        var media:[[String:Any]]=[],images:[[String:Any]]=[]
        for entry in details{
            if entry["type"] as? String=="photo"{
                var source=URLComponents(string:entry["media_url_https"] as? String ?? "")
                var query=source?.queryItems ?? [];query.removeAll{$0.name=="name"};query.append(URLQueryItem(name:"name",value:"orig"));source?.queryItems=query
                let size=entry["original_info"] as? [String:Any] ?? [:]
                if let address=source?.url?.absoluteString{images.append(["url":address,"width":NativeMediaParser.number(size["width"]),"height":NativeMediaParser.number(size["height"])])}
            }else{
                let info=entry["video_info"] as? [String:Any] ?? [:]
                let variants=(info["variants"] as? [[String:Any]] ?? []).filter{["video/mp4","application/x-mpegURL","application/vnd.apple.mpegurl"].contains($0["content_type"] as? String ?? "")}.map{row->[String:Any] in
                    let address=row["url"] as? String ?? "";var width=0,height=0
                    if let regex=try? NSRegularExpression(pattern:#"/(\d+)x(\d+)/"#),let match=regex.firstMatch(in:address,range:NSRange(address.startIndex...,in:address)){width=Int((address as NSString).substring(with:match.range(at:1))) ?? 0;height=Int((address as NSString).substring(with:match.range(at:2))) ?? 0}
                    return ["url":address,"width":width,"height":height,"bitrateKbps":Double(NativeMediaParser.number(row["bitrate"]))/1000]
                }
                let source=variants.first?["url"] as? String ?? ""
                let parts=source.components(separatedBy:"/")
                let marker=parts.firstIndex(where:{["ext_tw_video","amplify_video"].contains($0)})
                let fallback=marker.flatMap{parts.indices.contains($0+1) ? parts[$0+1]:nil} ?? ""
                let identity=entry["id_str"] as? String ?? entry["media_key"] as? String ?? fallback
                if !identity.isEmpty{media.append(["mediaId":identity,"formats":variants,"durationMs":NativeMediaParser.number(info["duration_millis"]),"isGif":entry["type"] as? String=="animated_gif","thumbnailUrl":entry["media_url_https"] as? String ?? ""])}
            }
        }
        return ["contentId":id,"title":item["text"] as? String ?? "X_\(id)","author":(item["user"] as? [String:Any])?["screen_name"] as? String ?? "","images":images,"media":media]
    }
}
