import Foundation

/// 链接嗅探与重定向解析工具
public class UrlSniffer {
    
    private static let urlRegex = try! NSRegularExpression(
        pattern: #"https?://[a-zA-Z0-9\.\-_]+[a-zA-Z0-9/?:@&=+$,#~%!\(\)]*"#,
        options: .caseInsensitive
    )
    
    /// 从用户粘贴的包含文本、中文或表情的杂乱内容中精确提取出有效的 HTTP(S) 链接
    public static func extractFirstUrl(from text: String) -> String? {
        let nsString = text as NSString
        let matches = urlRegex.matches(in: text, options: [], range: NSRange(location: 0, length: nsString.length))
        guard let first = matches.first else { return nil }
        return nsString.substring(with: first.range)
    }
    
    /// 解析短链（如 b23.tv, v.douyin.com, ksh.com 等）并获取最终重定向的目标真实地址
    public static func resolveRedirects(for urlString: String) async -> String {
        guard let url = URL(string: urlString.trimmingCharacters(in: .whitespacesAndNewlines)) else {
            return urlString
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "HEAD"
        request.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1", forHTTPHeaderField: "User-Agent")
        
        // 允许自动跟随重定向
        let session = URLSession(configuration: .default)
        do {
            let (_, response) = try await session.data(for: request)
            if let httpResp = response as? HTTPURLResponse, let finalUrl = httpResp.url {
                return finalUrl.absoluteString
            }
        } catch {
            // 若 HEAD 请求受阻，尝试常规 GET 请求获取 URL
            do {
                var getReq = URLRequest(url: url)
                getReq.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1", forHTTPHeaderField: "User-Agent")
                let (_, response) = try await session.data(for: getReq)
                if let httpResp = response as? HTTPURLResponse, let finalUrl = httpResp.url {
                    return finalUrl.absoluteString
                }
            } catch {
                return urlString
            }
        }
        return urlString
    }
    
    /// 检测所属媒体站点
    public static func detectSite(from urlString: String) -> String {
        let lower = urlString.lowercased()
        if lower.contains("bilibili.com") || lower.contains("b23.tv") {
            return "哔哩哔哩"
        } else if lower.contains("douyin.com") || lower.contains("iesdouyin.com") {
            return "抖音"
        } else if lower.contains("kuaishou.com") || lower.contains("gifshow.com") || lower.contains("kwai.com") {
            return "快手"
        } else if lower.contains("xiaohongshu.com") || lower.contains("xhslink.com") {
            return "小红书"
        } else if lower.contains("youtube.com") || lower.contains("youtu.be") {
            return "YouTube"
        } else if lower.contains("tiktok.com") {
            return "TikTok"
        } else if lower.contains("twitter.com") || lower.contains("x.com") {
            return "X (Twitter)"
        }
        return "通用媒体"
    }

    /// 异步拉取网页并提取 title 标签 (用于添加中转站时自动回填标题)
    public static func fetchWebTitle(from urlString: String) async -> String? {
        var cleanUrl = urlString.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanUrl.isEmpty else { return nil }
        if !cleanUrl.lowercased().hasPrefix("http://") && !cleanUrl.lowercased().hasPrefix("https://") {
            cleanUrl = "https://" + cleanUrl
        }
        guard let url = URL(string: cleanUrl) else { return nil }

        func fallbackHost() -> String {
            if let host = url.host?.lowercased() {
                let cleanHost = host.hasPrefix("www.") ? String(host.dropFirst(4)) : host
                let parts = cleanHost.split(separator: ".")
                if let first = parts.first {
                    return first.prefix(1).uppercased() + first.dropFirst()
                }
            }
            return "备用网站"
        }

        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 6.0)
        request.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1", forHTTPHeaderField: "User-Agent")
        request.setValue("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8", forHTTPHeaderField: "Accept")
        request.setValue("zh-CN,zh;q=0.9,en;q=0.8", forHTTPHeaderField: "Accept-Language")

        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let httpResp = response as? HTTPURLResponse, httpResp.statusCode == 200 else {
                return fallbackHost()
            }
            let maxBytes = min(data.count, 64 * 1024)
            let subData = data.prefix(maxBytes)

            let htmlString = String(data: subData, encoding: .utf8) ?? String(data: subData, encoding: .isoLatin1) ?? ""
            let pattern = #"<title[^>]*>(.*?)</title>"#
            if let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive, .dotMatchesLineSeparators]) {
                let nsHtml = htmlString as NSString
                if let match = regex.firstMatch(in: htmlString, options: [], range: NSRange(location: 0, length: nsHtml.length)) {
                    let rawTitle = nsHtml.substring(with: match.range(at: 1)).trimmingCharacters(in: .whitespacesAndNewlines)
                    let decodedTitle = rawTitle
                        .replacingOccurrences(of: "&amp;", with: "&")
                        .replacingOccurrences(of: "&quot;", with: "\"")
                        .replacingOccurrences(of: "&#39;", with: "'")
                        .replacingOccurrences(of: "&lt;", with: "<")
                        .replacingOccurrences(of: "&gt;", with: ">")
                        .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
                        .trimmingCharacters(in: .whitespacesAndNewlines)

                    let lower = decodedTitle.lowercased()
                    if !decodedTitle.isEmpty &&
                        !lower.contains("404 not found") &&
                        !lower.contains("just a moment") &&
                        !lower.contains("attention required") &&
                        !lower.contains("security check") {
                        return String(decodedTitle.prefix(50))
                    }
                }
            }
            return fallbackHost()
        } catch {
            return fallbackHost()
        }
    }

    /// 解析中转站提取的直链或携带 JWT Payload (如 SnapCDN/X2Twitter/TwitterSaver) 的长链接
    public static func unpackDirectMediaUrl(rawUrl: String, defaultTitle: String = "中转下载视频") -> (url: String, title: String) {
        let trimmed = rawUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        if let tokenRange = trimmed.range(of: "token=") {
            var tokenVal = String(trimmed[tokenRange.upperBound...])
            if let ampIdx = tokenVal.firstIndex(of: "&") {
                tokenVal = String(tokenVal[..<ampIdx])
            }
            if tokenVal.hasPrefix("eyJ") && tokenVal.contains(".") {
                let parts = tokenVal.split(separator: ".")
                if parts.count >= 2 {
                    var payload = String(parts[1])
                    // Base64 padding
                    let padding = payload.count % 4
                    if padding > 0 {
                        payload += String(repeating: "=", count: 4 - padding)
                    }
                    payload = payload.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
                    if let data = Data(base64Encoded: payload),
                       let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                        let realUrl = json["url"] as? String ?? ""
                        let filename = json["filename"] as? String ?? ""
                        let title = filename.isEmpty ? defaultTitle : (filename as NSString).deletingPathExtension
                        if !realUrl.isEmpty && realUrl.lowercased().hasPrefix("http") {
                            if isM3u8Url(urlString: realUrl) && (trimmed.contains("snapcdn") || trimmed.contains("get?token")) {
                                return (trimmed, title)
                            }
                            return (realUrl, title)
                        }
                    }
                }
            }
        }
        return (trimmed, defaultTitle)
    }

    /// 判断是否属于 M3U8 流
    public static func isM3u8Url(urlString: String) -> Bool {
        let lower = urlString.lowercased()
        return lower.hasSuffix(".m3u8") || lower.contains(".m3u8?") ||
               lower.contains("/hls/") || lower.contains("format=m3u8") ||
               lower.contains(".m3u8/")
    }

    /// 判断是否属于有效网络媒体直链 (严格排除 API 接口)
    public static func isDirectMediaUrl(urlString: String) -> Bool {
        let lower = urlString.lowercased()
        let isApiRoute = lower.contains("/api/") || lower.contains("/ajax/") ||
                         lower.contains("/extract/") || lower.contains("/video-tool") ||
                         lower.contains("cnsimpleextract") || lower.contains("dodownload") ||
                         lower.contains("getdownloadinfo")
        let isExplicitMediaExt = lower.hasSuffix(".mp4") || lower.contains(".mp4?") ||
                                 lower.hasSuffix(".m4a") || lower.contains(".m4a?") ||
                                 lower.hasSuffix(".mp3") || lower.contains(".mp3?") ||
                                 lower.hasSuffix(".webm") || lower.contains(".webm?") ||
                                 lower.hasSuffix(".flv") || lower.contains(".flv?") ||
                                 lower.hasSuffix(".m3u8") || lower.contains(".m3u8?")

        if isApiRoute && !isExplicitMediaExt {
            return false
        }

        return isExplicitMediaExt || [
            "dl.snapcdn.app", "video.twimg.com", "snapany.com/api/download",
            "googlevideo.com/videoplayback", "byteoversea.com", "ibytedtos.com",
            "tiktokcdn.com", "douyinvod.com", "snssdk.com", "yximgs.com",
            "xhscdn.com", "fbcdn.net", "twcdn.net"
        ].contains { lower.contains($0) }
    }
}
