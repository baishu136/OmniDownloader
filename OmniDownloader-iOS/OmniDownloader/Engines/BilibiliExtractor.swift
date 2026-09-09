import Foundation

/// 哔哩哔哩 DASH 视频流候选对象
public struct DashVideoCandidate {
    public let qnId: Int
    public let height: Int
    public let bandwidth: Int64
    public let isAvc: Bool
    public let url: String
    public let backupUrls: [String]
}

/// 哔哩哔哩原生直连解析引擎 (支持 4K/1080P60/DASH双流与合集批量识别)
public class BilibiliExtractor {
    
    public static let shared = BilibiliExtractor()
    private let pcUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    
    private let bvRegex = try! NSRegularExpression(pattern: #"(BV[a-zA-Z0-9]{10})|(av\d+)"#, options: .caseInsensitive)
    
    public func extractBvid(from url: String) -> String? {
        let nsString = url as NSString
        let matches = bvRegex.matches(in: url, options: [], range: NSRange(location: 0, length: nsString.length))
        guard let first = matches.first else { return nil }
        return nsString.substring(with: first.range)
    }
    
    /// 解析视频/合集元数据
    public func extract(rawUrl: String) async throws -> VideoMetadata {
        let resolvedUrl = await UrlSniffer.resolveRedirects(for: rawUrl)
        guard let bvid = extractBvid(from: resolvedUrl) else {
            throw NSError(domain: "BilibiliExtractor", code: 400, userInfo: [NSLocalizedDescriptionKey: "未能识别到有效的 B 站视频 ID (BV/av)"])
        }
        
        // 1. 获取基本信息 (x/web-interface/view)
        let viewApiUrl = bvid.uppercased().hasPrefix("BV")
            ? "https://api.bilibili.com/x/web-interface/view?bvid=\(bvid)"
            : "https://api.bilibili.com/x/web-interface/view?aid=\(bvid.dropFirst(2))"
        
        guard let viewUrl = URL(string: viewApiUrl) else {
            throw NSError(domain: "BilibiliExtractor", code: 400, userInfo: [NSLocalizedDescriptionKey: "构建接口地址失败"])
        }
        
        var request = URLRequest(url: viewUrl)
        request.setValue(pcUserAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("https://www.bilibili.com", forHTTPHeaderField: "Referer")
        let cookieHeader = CookieStore.shared.getBilibiliCookieHeader()
        if !cookieHeader.isEmpty {
            request.setValue(cookieHeader, forHTTPHeaderField: "Cookie")
        }
        
        let (viewData, _) = try await URLSession.shared.data(for: request)
        guard let viewJson = try JSONSerialization.jsonObject(with: viewData) as? [String: Any],
              let data = viewJson["data"] as? [String: Any] else {
            let msg = (try? JSONSerialization.jsonObject(with: viewData) as? [String: Any])?["message"] as? String ?? "解析失败"
            throw NSError(domain: "BilibiliExtractor", code: 404, userInfo: [NSLocalizedDescriptionKey: "B站接口返回: \(msg)"])
        }
        
        let title = data["title"] as? String ?? "B站视频"
        let pic = (data["pic"] as? String ?? "").replacingOccurrences(of: "http://", with: "https://")
        let duration = data["duration"] as? Int ?? 0
        let owner = (data["owner"] as? [String: Any])?["name"] as? String ?? "B站UP主"
        let cid = data["cid"] as? Int64 ?? 0
        
        // 2. 获取 DASH 清晰度流 (qn=120, fnval=4048 支持 4K/1080P/HDR 等全量流)
        let playApiUrl = "https://api.bilibili.com/x/player/playurl?bvid=\(bvid)&cid=\(cid)&qn=120&fnval=4048&fnver=0&fourk=1"
        guard let playUrl = URL(string: playApiUrl) else {
            throw NSError(domain: "BilibiliExtractor", code: 400, userInfo: [NSLocalizedDescriptionKey: "流接口错误"])
        }
        
        var playReq = URLRequest(url: playUrl)
        playReq.setValue(pcUserAgent, forHTTPHeaderField: "User-Agent")
        playReq.setValue("https://www.bilibili.com/video/\(bvid)", forHTTPHeaderField: "Referer")
        if !cookieHeader.isEmpty {
            playReq.setValue(cookieHeader, forHTTPHeaderField: "Cookie")
        }
        
        let (playDataBytes, _) = try await URLSession.shared.data(for: playReq)
        var videoFormats: [FormatOption] = []
        var audioFormats: [FormatOption] = []
        
        if let playJson = try? JSONSerialization.jsonObject(with: playDataBytes) as? [String: Any],
           let playData = playJson["data"] as? [String: Any],
           let dash = playData["dash"] as? [String: Any] {
            
            if let videos = dash["video"] as? [[String: Any]] {
                var seenHeights = Set<Int>()
                for v in videos {
                    guard let h = v["height"] as? Int, h > 0, !seenHeights.contains(h) else { continue }
                    seenHeights.insert(h)
                    let id = v["id"] as? Int ?? 0
                    let codecs = v["codecs"] as? String ?? ""
                    let bandwidth = v["bandwidth"] as? Int64 ?? 0
                    
                    let label: String
                    if h >= 2160 { label = "4K (2160p)" }
                    else if h >= 1440 { label = "2K (1440p)" }
                    else if h >= 1080 { label = "1080p 全高清" }
                    else if h >= 720 { label = "720p 高清" }
                    else if h >= 480 { label = "480p 标清" }
                    else { label = "\(h)p" }
                    
                    let codecNote: String
                    if codecs.lowercased().hasPrefix("avc") { codecNote = "AVC" }
                    else if codecs.lowercased().hasPrefix("hev") { codecNote = "HEVC" }
                    else if codecs.lowercased().hasPrefix("av01") { codecNote = "AV1" }
                    else { codecNote = codecs }
                    
                    let estSize = (duration > 0 && bandwidth > 0) ? formatBytes((bandwidth * Int64(duration)) / 8) : ""
                    
                    videoFormats.append(FormatOption(
                        formatId: "bili_\(id)",
                        resolutionLabel: label,
                        width: v["width"] as? Int ?? 0,
                        height: h,
                        fps: v["frameRate"] as? Int ?? 30,
                        approximateSize: estSize,
                        note: codecNote
                    ))
                }
            }
            
            if let audios = dash["audio"] as? [[String: Any]], !audios.isEmpty {
                audioFormats.append(FormatOption(
                    formatId: "bili_audio_best",
                    resolutionLabel: "320 kbps 极高音质",
                    ext: "m4a",
                    isAudioOnly: true
                ))
            }
        }
        
        if videoFormats.isEmpty {
            videoFormats = [
                FormatOption(formatId: "1080", resolutionLabel: "1080p 全高清", height: 1080),
                FormatOption(formatId: "720", resolutionLabel: "720p 高清", height: 720),
                FormatOption(formatId: "480", resolutionLabel: "480p 标清", height: 480)
            ]
        }
        videoFormats.sort { $0.height > $1.height }
        
        // 3. 提取分P与 UGC 合集列表
        var multiMediaList: [VideoMetadata] = []
        if let pages = data["pages"] as? [[String: Any]], pages.count > 1 {
            for (idx, p) in pages.enumerated() {
                let pNum = p["page"] as? Int ?? (idx + 1)
                let partTitle = p["part"] as? String ?? "第\(pNum)P"
                let pDur = p["duration"] as? Int ?? 0
                let pagePic = (p["first_frame"] as? String ?? "").isEmpty ? pic : (p["first_frame"] as? String ?? "")
                
                multiMediaList.append(VideoMetadata(
                    url: "https://www.bilibili.com/video/\(bvid)?p=\(pNum)",
                    title: "\(title) - P\(pNum) \(partTitle)",
                    author: owner,
                    durationText: formatDuration(pDur),
                    thumbnailUrl: pagePic,
                    siteName: "哔哩哔哩"
                ))
            }
        } else if let ugcSeason = data["ugc_season"] as? [String: Any] {
            let seasonTitle = ugcSeason["title"] as? String ?? "合集"
            if let sections = ugcSeason["sections"] as? [[String: Any]] {
                for sec in sections {
                    guard let episodes = sec["episodes"] as? [[String: Any]] else { continue }
                    for ep in episodes {
                        let epBvid = ep["bvid"] as? String ?? bvid
                        let epTitle = ep["title"] as? String ?? "分集"
                        let arc = ep["arc"] as? [String: Any]
                        let epDuration = arc?["duration"] as? Int ?? (ep["page"] as? [String: Any])?["duration"] as? Int ?? 0
                        let epPic = arc?["pic"] as? String ?? pic
                        
                        multiMediaList.append(VideoMetadata(
                            url: "https://www.bilibili.com/video/\(epBvid)",
                            title: "[\(seasonTitle)] \(epTitle)",
                            author: owner,
                            durationText: formatDuration(epDuration),
                            thumbnailUrl: epPic,
                            siteName: "哔哩哔哩"
                        ))
                    }
                }
            }
        }
        
        // 当存在多个分集时，在首位注入“自适应最高画质 (推荐)”
        var finalVideoFormats = videoFormats
        if !multiMediaList.isEmpty {
            finalVideoFormats.insert(FormatOption(
                formatId: "bili_auto_best",
                resolutionLabel: "自适应最高画质 (推荐)",
                height: 99999,
                note: "各集自动匹配最高清晰度"
            ), at: 0)
            
            // 同步赋予各分集
            for i in 0..<multiMediaList.count {
                multiMediaList[i].availableVideoFormats = finalVideoFormats
                multiMediaList[i].availableAudioFormats = audioFormats
            }
        }
        
        let finalTitle = multiMediaList.count > 1 ? "\(title) (共\(multiMediaList.count)集)" : title
        return VideoMetadata(
            url: "https://www.bilibili.com/video/\(bvid)",
            title: finalTitle,
            author: owner,
            durationText: formatDuration(duration),
            thumbnailUrl: pic,
            siteName: "哔哩哔哩",
            availableVideoFormats: finalVideoFormats,
            availableAudioFormats: audioFormats,
            multiMediaList: multiMediaList
        )
    }
    
    /// 解析指定清晰度的高度数值 (0 代表自适应最高)
    public func extractHeightFromResolution(_ label: String) -> Int {
        let lower = label.lowercased().trimmingCharacters(in: .whitespacesAndNewlines)
        if lower.contains("自适应") || lower.contains("最佳") || lower.contains("最高") ||
            lower.contains("auto") || lower.contains("best") || lower.contains("默认") {
            return 0
        }
        if lower.contains("4k") || lower.contains("2160") { return 2160 }
        if lower.contains("2k") || lower.contains("1440") { return 1440 }
        
        let regex = try! NSRegularExpression(pattern: #"(\d{3,4})"#, options: [])
        let nsString = label as NSString
        if let match = regex.matches(in: label, options: [], range: NSRange(location: 0, length: nsString.length)).first {
            let numStr = nsString.substring(with: match.range(at: 1))
            if let parsed = Int(numStr), parsed > 0 { return parsed }
        }
        if lower.contains("1080") { return 1080 }
        if lower.contains("720") { return 720 }
        if lower.contains("480") { return 480 }
        if lower.contains("360") { return 360 }
        return 0
    }
    
    /// 挑选最佳 DASH 视频画面流
    public func selectBestDashVideo(from videos: [[String: Any]], reqHeight: Int) -> DashVideoCandidate? {
        var candidates: [DashVideoCandidate] = []
        for v in videos {
            guard let url = v["baseUrl"] as? String ?? v["base_url"] as? String, !url.isEmpty else { continue }
            let h = v["height"] as? Int ?? 0
            let qnId = v["id"] as? Int ?? 0
            let bw = v["bandwidth"] as? Int64 ?? 0
            let codecs = v["codecs"] as? String ?? ""
            let isAvc = codecs.lowercased().hasPrefix("avc")
            let backups = (v["backupUrl"] as? [String] ?? v["backup_url"] as? [String]) ?? []
            candidates.append(DashVideoCandidate(qnId: qnId, height: h, bandwidth: bw, isAvc: isAvc, url: url, backupUrls: backups))
        }
        guard !candidates.isEmpty else { return nil }
        
        if reqHeight <= 0 {
            // 自适应最高：高度优先 -> qn优先 -> AVC兼容优先 -> 码率优先
            return candidates.max { a, b in
                if a.height != b.height { return a.height < b.height }
                if a.qnId != b.qnId { return a.qnId < b.qnId }
                if a.isAvc != b.isAvc { return !a.isAvc && b.isAvc }
                return a.bandwidth < b.bandwidth
            }
        } else {
            // 指定清晰度：寻找差值最小 -> qn降序 -> AVC兼容优先 -> 码率降序
            return candidates.min { a, b in
                let diffA = abs(a.height - reqHeight)
                let diffB = abs(b.height - reqHeight)
                if diffA != diffB { return diffA < diffB }
                if a.qnId != b.qnId { return a.qnId > b.qnId }
                if a.isAvc != b.isAvc { return a.isAvc && !b.isAvc }
                return a.bandwidth > b.bandwidth
            }
        }
    }
    
    /// 下载前获取该任务的音画流下载地址
    public func fetchStreamUrls(for task: DownloadTask) async throws -> (videoUrl: String, audioUrl: String, realTitle: String) {
        let resolvedUrl = await UrlSniffer.resolveRedirects(for: task.url)
        guard let bvid = extractBvid(from: resolvedUrl) else {
            throw NSError(domain: "BilibiliExtractor", code: 400, userInfo: [NSLocalizedDescriptionKey: "无法提取视频 ID"])
        }
        
        // 1. 获取 cid
        let viewApiUrl = bvid.uppercased().hasPrefix("BV")
            ? "https://api.bilibili.com/x/web-interface/view?bvid=\(bvid)"
            : "https://api.bilibili.com/x/web-interface/view?aid=\(bvid.dropFirst(2))"
        
        var viewReq = URLRequest(url: URL(string: viewApiUrl)!)
        viewReq.setValue(pcUserAgent, forHTTPHeaderField: "User-Agent")
        viewReq.setValue("https://www.bilibili.com", forHTTPHeaderField: "Referer")
        let cookieHeader = CookieStore.shared.getBilibiliCookieHeader()
        if !cookieHeader.isEmpty { viewReq.setValue(cookieHeader, forHTTPHeaderField: "Cookie") }
        
        let (viewBytes, _) = try await URLSession.shared.data(for: viewReq)
        guard let viewJson = try JSONSerialization.jsonObject(with: viewBytes) as? [String: Any],
              let data = viewJson["data"] as? [String: Any] else {
            throw NSError(domain: "BilibiliExtractor", code: 404, userInfo: [NSLocalizedDescriptionKey: "获取视频详情失败"])
        }
        
        var cid = data["cid"] as? Int64 ?? 0
        var realTitle = data["title"] as? String ?? task.title
        
        // 匹配分P序号
        if let pMatch = task.url.range(of: #"[?&]p=(\d+)"#, options: .regularExpression) {
            let sub = String(task.url[pMatch])
            let pDigits = sub.filter { "0123456789".contains($0) }
            if let pNum = Int(pDigits), let pages = data["pages"] as? [[String: Any]] {
                for p in pages {
                    if (p["page"] as? Int) == pNum {
                        cid = p["cid"] as? Int64 ?? cid
                        let part = p["part"] as? String ?? ""
                        realTitle = part.isEmpty ? "\(realTitle) - P\(pNum)" : "\(realTitle) - P\(pNum) \(part)"
                        break
                    }
                }
            }
        }
        
        // 2. 请求全画质 DASH (qn=120)
        let playApiUrl = "https://api.bilibili.com/x/player/playurl?bvid=\(bvid)&cid=\(cid)&qn=120&fnval=4048&fnver=0&fourk=1"
        var playReq = URLRequest(url: URL(string: playApiUrl)!)
        playReq.setValue(pcUserAgent, forHTTPHeaderField: "User-Agent")
        playReq.setValue("https://www.bilibili.com/video/\(bvid)", forHTTPHeaderField: "Referer")
        if !cookieHeader.isEmpty { playReq.setValue(cookieHeader, forHTTPHeaderField: "Cookie") }
        
        let (playBytes, _) = try await URLSession.shared.data(for: playReq)
        guard let playJson = try JSONSerialization.jsonObject(with: playBytes) as? [String: Any],
              let playData = playJson["data"] as? [String: Any],
              let dash = playData["dash"] as? [String: Any] else {
            throw NSError(domain: "BilibiliExtractor", code: 404, userInfo: [NSLocalizedDescriptionKey: "获取视频流失败"])
        }
        
        let reqHeight = extractHeightFromResolution(task.selectedResolution)
        var bestVideoUrl = ""
        if let videos = dash["video"] as? [[String: Any]],
           let best = selectBestDashVideo(from: videos, reqHeight: reqHeight) {
            bestVideoUrl = best.url
        }
        
        var bestAudioUrl = ""
        if let audios = dash["audio"] as? [[String: Any]] {
            let bestAudio = audios.max { ($0["bandwidth"] as? Int64 ?? 0) < ($1["bandwidth"] as? Int64 ?? 0) }
            bestAudioUrl = (bestAudio?["baseUrl"] as? String ?? bestAudio?["base_url"] as? String) ?? ""
        }
        
        return (bestVideoUrl, bestAudioUrl, realTitle)
    }
    
    private func formatBytes(_ bytes: Int64) -> String {
        guard bytes > 0 else { return "0 B" }
        let units = ["B", "KB", "MB", "GB"]
        let digitGroups = Int(log10(Double(bytes)) / log10(1024.0))
        let clamped = min(max(0, digitGroups), units.count - 1)
        return String(format: "%.1f %@", Double(bytes) / pow(1024.0, Double(clamped)), units[clamped])
    }
    
    private func formatDuration(_ seconds: Int) -> String {
        guard seconds > 0 else { return "" }
        let h = seconds / 3600
        let m = (seconds % 3600) / 60
        let s = seconds % 60
        return h > 0 ? String(format: "%02d:%02d:%02d", h, m, s) : String(format: "%02d:%02d", m, s)
    }
}
