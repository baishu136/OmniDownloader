import Foundation
import CryptoKit

struct ImageResource: Identifiable, Codable, Equatable {
    let id: String
    let url: String
    var width: Int = 0
    var height: Int = 0
    var original: Bool = true
    var animated: Bool = false
    var label: String { width>0 && height>0 ? "\(width)×\(height)" : "尺寸未知" }
}
struct ImagePost: Identifiable, Codable {
    var id: String { platform + ":" + contentId }
    let url: String
    let platform: String
    let contentId: String
    var title: String
    var author: String = ""
    var resources: [ImageResource]
    var frameArchive: String? = nil
    var frameDelays: [Int]? = nil
    var frameNames: [String]? = nil
    var sourceScope: String? = nil
}

/// Signed URLs remain in memory and are keyed by both post and actual media ID.
final class MediaSources {
    static let shared = MediaSources()
    private let lock=NSLock()
    private struct VideoEntry { let metadata:VideoMetadata;let at:Date;let scope:String }
    private struct ImageEntry { let post:ImagePost;let at:Date;let scope:String }
    private var video:[String:VideoEntry]=[:]
    private var images:[String:ImageEntry]=[:]
    private func key(_ url:String,_ media:String)->String { url + "|" + media }
    func remember(_ metadata:VideoMetadata) {
        lock.lock();defer{lock.unlock()}
        if video.count>=64{video.removeAll()}
        let rows=metadata.multiMediaList.isEmpty ? [metadata]:metadata.multiMediaList
        for row in rows { let scope=row.sourceScope ?? metadata.sourceScope ?? PlatformSession.shared.generation(row.siteName);if scope==PlatformSession.shared.generation(row.siteName){video[key(row.url,row.mediaId ?? "")]=VideoEntry(metadata:row,at:Date(),scope:scope)} }
    }
    func video(_ task:DownloadTask)throws->VideoMetadata {
        lock.lock();defer{lock.unlock()}
        guard let entry=video[key(task.url,task.mediaId ?? "")],Date().timeIntervalSince(entry.at)<300,
              entry.scope==PlatformSession.shared.generation(entry.metadata.siteName) else{throw OmniPlatform.error("SOURCE_EXPIRED","媒体源已过期或账号/网络已变化，请重新解析原链接")}
        return entry.metadata
    }
    func remember(_ post:ImagePost){lock.lock();defer{lock.unlock()};let scope=post.sourceScope ?? PlatformSession.shared.generation(post.platform);guard scope==PlatformSession.shared.generation(post.platform) else{return};if images.count>=32{images.removeAll()};images[post.url]=ImageEntry(post:post,at:Date(),scope:scope)}
    func images(_ task:DownloadTask)throws->ImagePost{
        lock.lock();defer{lock.unlock()}
        guard let entry=images[task.url],Date().timeIntervalSince(entry.at)<300,entry.scope==PlatformSession.shared.generation(entry.post.platform) else{throw OmniPlatform.error("SOURCE_EXPIRED","图片源已过期，请重新解析原图文")};return entry.post
    }
    func invalidate(_ site:String){lock.lock();defer{lock.unlock()};video=video.filter{$0.value.metadata.siteName != site};images=images.filter{$0.value.post.platform != site}}
}

enum MediaQuality {
    static func label(_ width:Int,_ height:Int)->String{guard width>0,height>0 else{return "源视频（尺寸未知）"};let edge=min(width,height);return "\(edge==2160 ? "4K":edge==4320 ? "8K":"\(edge)p") · \(width)×\(height)"}
    static func sorted(_ rows:[FormatOption])->[FormatOption]{
        var seen=Set<String>()
        let unique=rows.filter{seen.insert($0.formatId).inserted}
        func family(_ f:FormatOption)->String{let c=(f.vcodec ?? "").lowercased();if c.hasPrefix("avc") || c=="h264"{return "avc"};if c.hasPrefix("hev") || c.hasPrefix("hvc") || c=="h265"{return "hevc"};return c}
        let families=unique.map(family).reduce(into:[String]()){if !$0.contains($1){$0.append($1)}}
        return unique.sorted{a,b in
            if a.width*a.height != b.width*b.height{return a.width*a.height>b.width*b.height}
            if a.fps != b.fps{return a.fps>b.fps}
            let ah = !(a.dynamicRange ?? "").isEmpty && a.dynamicRange != "SDR"
            let bh = !(b.dynamicRange ?? "").isEmpty && b.dynamicRange != "SDR"
            if ah != bh{return ah}
            if family(a) != family(b){return (families.firstIndex(of:family(a)) ?? 0)<(families.firstIndex(of:family(b)) ?? 0)}
            return (a.bitrateKbps ?? 0)>(b.bitrateKbps ?? 0)
        }
    }
}

enum NativeMediaParser {
    static func safeURL(_ text:String)->Bool{guard let u=URL(string:text),["https","http"].contains(u.scheme ?? ""),u.host != nil,u.user==nil,u.password==nil else{return false};return true}
    static func number(_ value:Any?)->Int{(value as? NSNumber)?.intValue ?? Int(value as? String ?? "") ?? 0}
    static func text(_ value:Any?)->String{value as? String ?? ""}
    static func parse(_ json:[String:Any],url:String)throws->(VideoMetadata?,ImagePost?){
        let site=OmniPlatform.detect(url),expected=OmniPlatform.identifier(url)
        guard !expected.isEmpty,let actual=json["contentId"] as? String,actual==expected else{throw OmniPlatform.error("MEDIA_ID_MISMATCH","网页数据与目标作品不一致")}
        let title=text(json["title"]).isEmpty ? site+"_"+actual:text(json["title"])
        let author=text(json["author"]),cover=text(json["thumbnailUrl"])
        let resources=(json["images"] as? [[String:Any]] ?? []).enumerated().compactMap { index,row -> ImageResource? in
            let source=text(row["url"]);guard safeURL(source) else{return nil}
            var parts=URLComponents(string:source);parts?.query=nil;parts?.fragment=nil
            let id=SHA256.hash(data:Data((actual+"|"+(parts?.string ?? source)).utf8)).prefix(10).map{String(format:"%02x",$0)}.joined()
            return ImageResource(id:id,url:source,width:number(row["width"]),height:number(row["height"]),original:row["original"] as? Bool ?? true,animated:source.lowercased().contains(".gif"))
        }
        let post=resources.isEmpty ? nil:ImagePost(url:url,platform:site,contentId:actual,title:title,author:author,resources:resources)
        let mediaRows=json["media"] as? [[String:Any]] ?? []
        var videos:[VideoMetadata]=[]
        for (index,media) in mediaRows.prefix(200).enumerated(){
            let mediaId=text(media["mediaId"]).isEmpty ? actual:text(media["mediaId"])
            let formats=(media["formats"] as? [[String:Any]] ?? []).compactMap{row->FormatOption? in
                let source=text(row["url"]);guard safeURL(source) else{return nil}
                let w=max(0,number(row["width"])),h=max(0,number(row["height"]))
                return FormatOption(formatId:source,resolutionLabel:MediaQuality.label(w,h),ext:source.contains(".m3u8") ? "m3u8":"mp4",width:w,height:h,fps:number(row["fps"]),note:text(row["note"]),sourceUrl:source,vcodec:text(row["vcodec"]),dynamicRange:text(row["dynamicRange"]),bitrateKbps:(row["bitrateKbps"] as? NSNumber)?.doubleValue)
            }
            if formats.isEmpty{continue}
            var metadata=VideoMetadata(url:url,title:mediaRows.count>1 ? title+" · \(index+1)":title,author:author,thumbnailUrl:text(media["thumbnailUrl"]).isEmpty ? cover:text(media["thumbnailUrl"]),siteName:site,isGif:media["isGif"] as? Bool ?? false,availableVideoFormats:MediaQuality.sorted(formats),mediaId:mediaId,expectedDurationMs:Int64(number(media["durationMs"])))
            metadata.referenceWidth=number(media["referenceWidth"]);metadata.referenceHeight=number(media["referenceHeight"])
            metadata.qualityEvidence="本次最高规格：\(metadata.availableVideoFormats[0].resolutionLabel) · 平台标注，下载后核对"
            videos.append(metadata)
        }
        if var first=videos.first{if videos.count>1{first.multiMediaList=videos};return(first,post)}
        return(nil,post)
    }
    static func douyinItem(_ item:[String:Any],url:String)throws->VideoMetadata{
        let id=OmniPlatform.identifier(url)
        guard text(item["aweme_id"]).isEmpty ? text(item["awemeId"])==id:text(item["aweme_id"])==id else{throw OmniPlatform.error("MEDIA_ID_MISMATCH","抖音返回了其他作品")}
        if !(item["images"] as? [Any] ?? []).isEmpty{throw OmniPlatform.error("NO_MEDIA","这是图文作品，请使用图文页")}
        guard let video=item["video"] as? [String:Any] else{throw OmniPlatform.error("BROWSER_ASSIST_REQUIRED","页面没有目标视频详情")}
        var formats:[[String:Any]]=[]
        func add(_ object:[String:Any],rate:[String:Any]=[:]){
            for(key,value)in object where key.hasPrefix("play_addr") || key.hasPrefix("playAddr"){
                guard let address=value as? [String:Any] else{continue}
                for source in address["url_list"] as? [String] ?? address["urlList"] as? [String] ?? []{
                    formats.append(["url":source,"width":number(address["width"]),"height":number(address["height"]),"fps":number(rate["fps"]),"vcodec":text(address["codec_type"] ?? rate["codec_type"]),"dynamicRange":text(address["dynamic_range"] ?? rate["dynamic_range"]),"bitrateKbps":Double(number(rate["bit_rate"] ?? rate["bitRate"]))/1000])
                }
            }
        }
        for rate in video["bit_rate"] as? [[String:Any]] ?? video["bitRateList"] as? [[String:Any]] ?? []{add(rate,rate:rate)};add(video)
        let json:[String:Any]=["contentId":id,"title":text(item["desc"]),"author":text((item["author"] as? [String:Any])?["nickname"]),"media":[["mediaId":id,"formats":formats,"durationMs":number(video["duration"]),"referenceWidth":number(video["width"]),"referenceHeight":number(video["height"])]]]
        guard let result=try parse(json,url:url).0 else{throw OmniPlatform.error("BROWSER_ASSIST_REQUIRED","目标作品没有可用播放源")};return result
    }
}

enum MediaResolver {
    static func analyze(_ raw:String)async throws->VideoMetadata{
        let original=UrlSniffer.extractFirstUrl(from:raw) ?? raw
        let url=await UrlSniffer.resolveRedirects(for:original),site=OmniPlatform.detect(url)
        if site=="哔哩哔哩"{
            if URL(string:url)?.host=="t.bilibili.com" || url.contains("/opus/"){throw MediaRouting.images(try await ImagePostEngine.analyze(url))}
            let value=try await BilibiliExtractor.shared.extract(rawUrl:url);MediaSources.shared.remember(value);return value
        }
        if site=="抖音"{return try await DomesticExtractor.shared.extract(rawUrl:url)}
        if site=="X (Twitter)"{
            let scope=PlatformSession.shared.generation(site),json=try await ImagePostEngine.xPost(url),parsed=try NativeMediaParser.parse(json,url:url)
            guard scope==PlatformSession.shared.generation(site) else{throw CancellationError()}
            if var value=parsed.0{
                value.sourceScope=scope
                let children=value.multiMediaList.isEmpty ? [value]:value.multiMediaList
                var enhanced:[VideoMetadata]=[]
                for var child in children{
                    child.sourceScope=scope;var formats=child.availableVideoFormats
                    for format in child.availableVideoFormats.filter({$0.ext=="m3u8"}).prefix(1){if let address=URL(string:format.sourceUrl ?? format.formatId),let variants=try? await NativeHLS.inspect(address,site:site){formats+=variants}}
                    child.availableVideoFormats=MediaQuality.sorted(formats);enhanced.append(child)
                }
                guard scope==PlatformSession.shared.generation(site) else{throw CancellationError()}
                value.availableVideoFormats=enhanced[0].availableVideoFormats;if enhanced.count>1{value.multiMediaList=enhanced};MediaSources.shared.remember(value);return value
            }
            if var post=parsed.1{post.sourceScope=scope;throw MediaRouting.images(post)}
            throw OmniPlatform.error("NO_MEDIA","目标 X 帖子没有可导出的媒体")
        }
        if site=="Pixiv"{
            let post=try await ImagePostEngine.analyze(url)
            if post.frameArchive==nil{throw MediaRouting.images(post)}
            let first=post.resources.first
            var value=VideoMetadata(url:url,title:post.title,author:post.author,thumbnailUrl:first?.url ?? "",siteName:site,isGif:true,availableVideoFormats:[FormatOption(formatId:"ugoira",resolutionLabel:"原始帧动画",width:first?.width ?? 0,height:first?.height ?? 0)],mediaId:post.contentId,expectedDurationMs:Int64(post.frameDelays?.reduce(0,+) ?? 0))
            value.sourceScope=post.sourceScope;MediaSources.shared.remember(value);return value
        }
        if let source=URL(string:url),["mp4","webm","m4v","mov","m3u8"].contains(source.pathExtension.lowercased()){
            let format=FormatOption(formatId:url,resolutionLabel:"源视频（尺寸未知）",sourceUrl:url)
            let value=VideoMetadata(url:url,title:source.deletingPathExtension().lastPathComponent,siteName:site,availableVideoFormats:[format],mediaId:source.path)
            MediaSources.shared.remember(value);return value
        }
        throw OmniPlatform.error("BROWSER_ASSIST_REQUIRED","平台需要执行网页脚本，正在准备本机页面提取")
    }
}

enum MediaRouting:Error{case images(ImagePost)}
