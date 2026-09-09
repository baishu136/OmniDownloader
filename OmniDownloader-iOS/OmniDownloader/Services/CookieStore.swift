import Foundation

/// B 站与各平台凭证存储服务 (基于 UserDefaults 与 Keychain 思想)
public class CookieStore: ObservableObject {
    public static let shared = CookieStore()
    
    private let kBilibiliCookie = "com.omni.downloader.bilibiliCookie"
    private let kAutoSaveToAlbum = "com.omni.downloader.autoSaveToAlbum"
    
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
    
    public var isBilibiliLoggedIn: Bool {
        let cookie = bilibiliCookie.trimmingCharacters(in: .whitespacesAndNewlines)
        return !cookie.isEmpty && (cookie.contains("SESSDATA") || !cookie.contains("="))
    }
    
    private init() {
        self.bilibiliCookie = UserDefaults.standard.string(forKey: kBilibiliCookie) ?? ""
        self.autoSaveToAlbum = UserDefaults.standard.object(forKey: kAutoSaveToAlbum) as? Bool ?? true
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
}
