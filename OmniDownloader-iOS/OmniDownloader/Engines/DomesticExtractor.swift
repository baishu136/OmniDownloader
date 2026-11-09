import Foundation

/// Target-bound HTML extraction. A content page is never returned as a video URL.
public final class DomesticExtractor{
    public static let shared=DomesticExtractor()
    public func isSupported(_ url:String)->Bool{["抖音","小红书","快手"].contains(OmniPlatform.detect(url))}
    static func embeddedJSON(_ html:String,marker:String)->Any?{
        guard let range=html.range(of:marker),let start=html[range.upperBound...].firstIndex(of:"{") else{return nil}
        let tail=html[start...];guard tail.utf8.count<=16_777_216 else{return nil}
        var depth=0,quote:Character?,escape=false
        for index in tail.indices{
            let c=tail[index]
            if let current=quote{if escape{escape=false}else if c=="\\"{escape=true}else if c==current{quote=nil};continue}
            if c=="\"" || c=="'"{quote=c;continue}
            if c=="{" || c=="["{depth+=1;if depth>64{return nil}}
            if c=="}" || c=="]"{depth-=1;if depth==0{let text=String(tail[...index]);guard text.utf8.count<=4_194_304,let data=text.data(using:.utf8) else{return nil};return try? JSONSerialization.jsonObject(with:data)}}
        };return nil
    }
    static func targetItems(_ root:Any,id:String,depth:Int=0)->[[String:Any]]{
        guard depth<25 else{return []}
        if let object=root as? [String:Any]{
            var results:[[String:Any]]=[]
            if (object["aweme_id"] as? String ?? object["awemeId"] as? String)==id,object["video"] != nil || object["images"] != nil{results.append(object)}
            for value in object.values{results+=targetItems(value,id:id,depth:depth+1);if results.count>=16{break}}
            return Array(results.prefix(16))
        }
        if let array=root as? [Any]{return Array(array.prefix(1000).flatMap{targetItems($0,id:id,depth:depth+1)}.prefix(16))};return []
    }
    static func merge(_ base:VideoMetadata,_ extra:VideoMetadata)throws->VideoMetadata{
        guard base.mediaId==extra.mediaId,base.siteName==extra.siteName else{throw OmniPlatform.error("MEDIA_ID_MISMATCH","页面返回了其他作品")}
        var value=base
        value.availableVideoFormats=MediaQuality.sorted(extra.availableVideoFormats+base.availableVideoFormats)
        if (extra.referenceWidth ?? 0)*(extra.referenceHeight ?? 0)>(base.referenceWidth ?? 0)*(base.referenceHeight ?? 0){value.referenceWidth=extra.referenceWidth;value.referenceHeight=extra.referenceHeight}
        return value
    }
    public func extract(rawUrl:String)async throws->VideoMetadata{
        let url=await UrlSniffer.resolveRedirects(for:rawUrl),site=OmniPlatform.detect(url),id=OmniPlatform.identifier(url)
        guard site=="抖音",!id.isEmpty else{throw OmniPlatform.error("BROWSER_ASSIST_REQUIRED","需要自动加载目标平台页面")}
        let scope=PlatformSession.shared.generation(site)
        let address=URL(string:"https://www.iesdouyin.com/share/video/\(id)/")!
        let(data,response)=try await OmniNetwork.data(address,site:site,headers:["User-Agent":"Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"],timeout:7)
        guard let resolved=response.url,OmniPlatform.detect(resolved.absoluteString)==site,OmniPlatform.identifier(resolved.absoluteString)==id else{throw OmniPlatform.error("MEDIA_ID_MISMATCH","抖音分享页没有停留在目标作品")}
        let html=String(decoding:data,as:UTF8.self)
        var roots:[Any]=[]
        if let root=Self.embeddedJSON(html,marker:"_ROUTER_DATA"){roots.append(root)}
        if let expression=try? NSRegularExpression(pattern:#"<script[^>]+id=["']RENDER_DATA["'][^>]*>(.*?)</script>"#,options:.dotMatchesLineSeparators),let match=expression.firstMatch(in:html,range:NSRange(html.startIndex...,in:html)),let json=(html as NSString).substring(with:match.range(at:1)).removingPercentEncoding?.data(using:.utf8),let root=try? JSONSerialization.jsonObject(with:json){roots.append(root)}
        var value:VideoMetadata?
        for item in roots.flatMap({Self.targetItems($0,id:id)}){
            let images=item["images"] as? [[String:Any]] ?? (item["image_post_info"] as? [String:Any])?["images"] as? [[String:Any]] ?? []
            if !images.isEmpty{
                let resources=images.enumerated().compactMap{index,image->PostImageResource? in
                    let source=(image["origin_image"] as? [String:Any])?["url_list"] as? [String] ?? image["url_list"] as? [String] ?? []
                    guard let url=source.first(where:NativeMediaParser.safeURL) else{return nil};return PostImageResource(id:id+"-p\(index)",url:url,width:NativeMediaParser.number(image["width"]),height:NativeMediaParser.number(image["height"]))
                }
                guard scope==PlatformSession.shared.generation(site) else{throw CancellationError()}
                var post=ImagePost(url:url,platform:site,contentId:id,title:item["desc"] as? String ?? id,author:(item["author"] as? [String:Any])?["nickname"] as? String ?? "",resources:resources);post.sourceScope=scope;throw MediaRouting.images(post)
            }
            if let parsed=try? NativeMediaParser.douyinItem(item,url:url){value=try value.map{try Self.merge($0,parsed)} ?? parsed}
        }
        guard var result=value else{throw OmniPlatform.error("BROWSER_ASSIST_REQUIRED","分享页没有提供目标视频详情，需要自动加载原页面")}
        guard scope==PlatformSession.shared.generation(site) else{throw CancellationError()}
        result.sourceScope=scope;MediaSources.shared.remember(result);return result
    }
}
