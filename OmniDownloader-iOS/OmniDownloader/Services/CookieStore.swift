import Foundation

/// B 站与各平台凭证存储服务 (基于 UserDefaults 与 Keychain 思想)
public class CookieStore: ObservableObject {
    public static let shared = CookieStore()
    
    private let kBilibiliCookie = "com.omni.downloader.bilibiliCookie"
    private let kAutoSaveToAlbum = "com.omni.downloader.autoSaveToAlbum"
    private let kRelaySites = "com.omni.downloader.relaySites"
    private let kHasShownRelayCompatTip = "com.omni.downloader.hasShownRelayCompatTip"

    @Published public var bilibiliCookie: String {
        didSet {
            UserDefaults.standard.set(bilibiliCookie, forKey: kBilibiliCookie)
        }
    }

    @Published public var autoSaveToAlbum: Bool {
        didSet {
            UserDefaults.standard.set(autoSaveToAlbum, forKey: kAutoSaveToAlbum)
        }
    }

    @Published public var relaySites: [RelaySite] {
        didSet {
            if let data = try? JSONEncoder().encode(relaySites) {
                UserDefaults.standard.set(data, forKey: kRelaySites)
            }
        }
    }

    @Published public var hasShownRelayCompatTip: Bool {
        didSet {
            UserDefaults.standard.set(hasShownRelayCompatTip, forKey: kHasShownRelayCompatTip)
        }
    }

    public var isBilibiliLoggedIn: Bool {
        let cookie = bilibiliCookie.trimmingCharacters(in: .whitespacesAndNewlines)
        return !cookie.isEmpty && (cookie.contains("SESSDATA") || !cookie.contains("="))
    }

    private init() {
        self.bilibiliCookie = UserDefaults.standard.string(forKey: kBilibiliCookie) ?? ""
        self.autoSaveToAlbum = UserDefaults.standard.object(forKey: kAutoSaveToAlbum) as? Bool ?? true

        if let data = UserDefaults.standard.data(forKey: kRelaySites),
           let saved = try? JSONDecoder().decode([RelaySite].self, from: data) {
            self.relaySites = saved
        } else {
            self.relaySites = []
        }

        self.hasShownRelayCompatTip = UserDefaults.standard.bool(forKey: kHasShownRelayCompatTip)
    }
    
    /// 获取规范格式的 B 站 Cookie 请求头字符串
    public func getBilibiliCookieHeader() -> String {
        let raw = bilibiliCookie.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !raw.isEmpty else { return "" }
        if !raw.contains("=") && !raw.contains(";") {
            return "SESSDATA=\(raw)"
        }
        return raw
    }
    
    /// 更新并保存 B 站 Cookie
    public func updateBilibiliCookie(_ cookie: String) {
        self.bilibiliCookie = cookie.trimmingCharacters(in: .whitespacesAndNewlines)
    }
    
    /// 清除 B 站登录凭证
    public func clearBilibiliCookie() {
        self.bilibiliCookie = ""
    }

    public func isLoggedIn() -> Bool {
        return isBilibiliLoggedIn
    }

    public func getBilibiliSessData() -> String? {
        let raw = bilibiliCookie.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !raw.isEmpty else { return nil }
        if raw.contains("SESSDATA=") {
            let parts = raw.components(separatedBy: ";")
            for part in parts {
                let trimmed = part.trimmingCharacters(in: .whitespaces)
                if trimmed.hasPrefix("SESSDATA=") {
                    return String(trimmed.dropFirst("SESSDATA=".count))
                }
            }
        }
        return raw
    }

    public func getAutoSaveToPhotos() -> Bool {
        return autoSaveToAlbum
    }

    public func setAutoSaveToPhotos(_ enabled: Bool) {
        self.autoSaveToAlbum = enabled
    }

    public func clearBilibiliCookies() {
        clearBilibiliCookie()
    }

    /// 添加备用中转解析网站
    @discardableResult
    public func addRelaySite(name: String, url: String) -> RelaySite {
        var cleanUrl = url.trimmingCharacters(in: .whitespacesAndNewlines)
        if !cleanUrl.lowercased().hasPrefix("http://") && !cleanUrl.lowercased().hasPrefix("https://") {
            cleanUrl = "https://" + cleanUrl
        }
        let cleanName = name.trimmingCharacters(in: .whitespacesAndNewlines)

        var current = relaySites
        current.removeAll { $0.url.lowercased() == cleanUrl.lowercased() }
        let newSite = RelaySite(name: cleanName, url: cleanUrl)
        current.append(newSite)
        self.relaySites = current
        return newSite
    }

    /// 移除指定的备用中转解析网站
    public func removeRelaySite(id: String) {
        self.relaySites.removeAll { $0.id == id }
    }

    /// 标记已知晓第三方网页免责声明
    public func confirmRelayCompatTip() {
        self.hasShownRelayCompatTip = true
    }
}
