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
}
