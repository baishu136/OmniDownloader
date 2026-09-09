import Foundation

/// 国内短视频去水印直连提取引擎 (抖音 / 快手 / 小红书)
public class DomesticExtractor {
    public static let shared = DomesticExtractor()
    
    private let mobileUserAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
    
    public func isSupported(_ url: String) -> Bool {
        let lower = url.lowercased()
        return lower.contains("douyin.com") || lower.contains("iesdouyin.com") ||
               lower.contains("kuaishou.com") || lower.contains("gifshow.com") || lower.contains("kwai.com") ||
               lower.contains("xiaohongshu.com") || lower.contains("xhslink.com")
    }
    
    /// 解析国内短视频元数据
    public func extract(rawUrl: String) async throws -> VideoMetadata {
        let cleanUrl = await UrlSniffer.resolveRedirects(for: rawUrl)
        let site = UrlSniffer.detectSite(from: cleanUrl)
        
        var request = URLRequest(url: URL(string: cleanUrl)!)
        request.setValue(mobileUserAgent, forHTTPHeaderField: "User-Agent")
        if site == "抖音" {
            request.setValue("https://www.douyin.com/", forHTTPHeaderField: "Referer")
        } else if site == "小红书" {
            request.setValue("https://www.xiaohongshu.com/", forHTTPHeaderField: "Referer")
        } else if site == "快手" {
            request.setValue("https://www.kuaishou.com/", forHTTPHeaderField: "Referer")
        }
        
        let (htmlData, _) = try await URLSession.shared.data(for: request)
        let html = String(data: htmlData, encoding: .utf8) ?? ""
        
        // 1. 尝试从页面 HTML 提取视频直链或元数据
        var videoUrl = ""
        var coverUrl = ""
        var title = "\(site)视频"
        
        if site == "抖音" {
            // 匹配 playAddr 或 play_addr
            let pattern = #""playAddr":\s*\[\s*\{\s*"src":\s*"([^"]+)""#
            if let regex = try? NSRegularExpression(pattern: pattern),
               let match = regex.matches(in: html, range: NSRange(location: 0, length: (html as NSString).length)).first {
                let rawPlay = (html as NSString).substring(with: match.range(at: 1))
                videoUrl = rawPlay.replacingOccurrences(of: "\\u0026", with: "&").replacingOccurrences(of: "playwm", with: "play")
            }
            if let titleRegex = try? NSRegularExpression(pattern: #"<title>([^<]+)</title>"#),
               let match = titleRegex.matches(in: html, range: NSRange(location: 0, length: (html as NSString).length)).first {
                title = (html as NSString).substring(with: match.range(at: 1)).replacingOccurrences(of: " - 抖音", with: "").trimmingCharacters(in: .whitespacesAndNewlines)
            }
        } else if site == "快手" {
            let pattern = #"srcNoMark":"([^"]+)""#
            if let regex = try? NSRegularExpression(pattern: pattern),
               let match = regex.matches(in: html, range: NSRange(location: 0, length: (html as NSString).length)).first {
                videoUrl = (html as NSString).substring(with: match.range(at: 1)).replacingOccurrences(of: "\\u002F", with: "/")
            }
        } else if site == "小红书" {
            let pattern = #"\"originVideoKey\":\"([^\"]+)\""#
            if let regex = try? NSRegularExpression(pattern: pattern),
               let match = regex.matches(in: html, range: NSRange(location: 0, length: (html as NSString).length)).first {
                let key = (html as NSString).substring(with: match.range(at: 1))
                videoUrl = "https://sns-video-bd.xhscdn.com/\(key)"
            }
        }
        
        // 若未能提取到具体地址，兜底返回原链接
        let finalVideoUrl = videoUrl.isEmpty ? cleanUrl : videoUrl
        let formats = [
            FormatOption(formatId: "original", resolutionLabel: "无水印高清原画", height: 1080, note: "极速直连")
        ]
        
        return VideoMetadata(
            url: finalVideoUrl,
            title: title.isEmpty ? "\(site)热门视频" : title,
            author: site,
            durationText: "",
            thumbnailUrl: coverUrl,
            siteName: site,
            availableVideoFormats: formats,
            availableAudioFormats: [FormatOption(formatId: "audio_mp3", resolutionLabel: "MP3 音频原轨", ext: "mp3", isAudioOnly: true)]
        )
    }
}
