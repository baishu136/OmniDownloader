import Foundation
import Combine
import Security
import CryptoKit
import Network
import WebKit

enum OmniPlatform {
    static let all = ["哔哩哔哩", "抖音", "快手", "小红书", "YouTube", "TikTok", "X (Twitter)", "Instagram", "Facebook", "Pinterest", "Pixiv", "小黑盒"]
    static let domains: [String: [String]] = [
        "哔哩哔哩": ["bilibili.com", "b23.tv"], "抖音": ["douyin.com", "iesdouyin.com"],
        "快手": ["kuaishou.com", "kwai.com", "gifshow.com", "chenzhongtech.com"],
        "小红书": ["xiaohongshu.com", "xhslink.com", "xhslink.cn", "rednote.com"],
        "YouTube": ["youtube.com", "youtu.be"], "TikTok": ["tiktok.com"], "X (Twitter)": ["x.com", "twitter.com", "t.co"],
        "Instagram": ["instagram.com"], "Facebook": ["facebook.com", "fb.watch", "fb.me"],
        "Pinterest": ["pinterest.com", "pin.it"], "Pixiv": ["pixiv.net"], "小黑盒": ["xiaoheihe.cn", "heybox.cn"]]
    static let homes: [String: String] = ["哔哩哔哩":"https://www.bilibili.com", "抖音":"https://www.douyin.com", "快手":"https://www.kuaishou.com", "小红书":"https://www.xiaohongshu.com", "YouTube":"https://www.youtube.com", "TikTok":"https://www.tiktok.com", "X (Twitter)":"https://x.com", "Instagram":"https://www.instagram.com", "Facebook":"https://www.facebook.com", "Pinterest":"https://www.pinterest.com", "Pixiv":"https://www.pixiv.net", "小黑盒":"https://www.xiaoheihe.cn"]
    static func owns(_ host: String, _ domain: String) -> Bool { host == domain || host.hasSuffix("." + domain) }
    static func detect(_ text: String) -> String {
        let host = URL(string: text)?.host?.lowercased() ?? ""
        return all.first { site in (domains[site] ?? []).contains { owns(host, $0) } } ?? "通用媒体"
    }
    static func identifier(_ text: String) -> String {
        let site = detect(text)
        if let parts=URLComponents(string:text){
            if site=="小黑盒",let id=parts.queryItems?.first(where:{$0.name=="link_id"})?.value,id.allSatisfy(\.isNumber){return id}
            if site=="Facebook",let id=parts.queryItems?.first(where:{$0.name=="v"})?.value,id.allSatisfy(\.isNumber){return id}
            if site=="哔哩哔哩",parts.host=="t.bilibili.com",let id=parts.path.split(separator:"/").first,id.allSatisfy(\.isNumber){return String(id)}
        }
        let patterns: [String: String] = ["抖音":#"/(?:video|note|share/video|share/note)/(\d+)"#,
            "X (Twitter)":#"/status/(\d+)"#, "小红书":#"/(?:explore|discovery/item)/([a-zA-Z0-9]+)"#,
            "快手":#"/(?:short-video|photo)/([a-zA-Z0-9]+)"#, "Pixiv":#"/artworks/(\d+)"#,
            "哔哩哔哩":#"/(?:video|opus)/(BV[a-zA-Z0-9]+|\d+)"#, "Instagram":#"/(?:p|reel)/([a-zA-Z0-9_-]+)"#,
            "TikTok":#"/video/(\d+)"#, "Pinterest":#"/pin/(\d+)"#, "Facebook":#"/(?:videos|reel)/(\d+)"#, "小黑盒":#"/(?:app/bbs/link|bbs/link)/(\d+)"#]
        if site == "YouTube", let parts = URLComponents(string: text) {
            return parts.queryItems?.first(where: {$0.name == "v"})?.value ?? URL(string:text)?.lastPathComponent ?? ""
        }
        guard let pattern = patterns[site], let regex = try? NSRegularExpression(pattern: pattern),
              let match = regex.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)),
              let range = Range(match.range(at: 1), in: text) else { return "" }
        return String(text[range])
    }
    static func error(_ code: String, _ message: String) -> NSError { NSError(domain:"OmniDownloader",code:1,userInfo:[NSLocalizedDescriptionKey:"[\(code)] \(message)"]) }
}

/// Credentials stay in the device-only Keychain. Browser stores are separate and ephemeral.
final class PlatformSession: ObservableObject {
    static let shared = PlatformSession()
    @Published private(set) var revision = 0
    private let service = "com.omni.downloader.platform-cookies.v2"
    private let defaults = UserDefaults.standard
    private let lock = NSRecursiveLock()
    private func query(_ site: String) -> [String: Any] { [kSecClass as String:kSecClassGenericPassword, kSecAttrService as String:service, kSecAttrAccount as String:site] }
    func enabled(_ site: String) -> Bool { !defaults.bool(forKey:"com.omni.downloader.cookieDisabled." + site) }
    func generation(_ site: String) -> String { "\(defaults.integer(forKey: "omni.cookie.revision."+site))|\(enabled(site))|\(UserDefaults.standard.string(forKey:"omni.proxy") ?? "")" }
    func setEnabled(_ site: String, _ enabled: Bool) { defaults.set(!enabled,forKey:"com.omni.downloader.cookieDisabled."+site); changed(site) }
    private func changed(_ site: String) {
        defaults.set(defaults.integer(forKey:"omni.cookie.revision."+site)+1,forKey:"omni.cookie.revision."+site)
        DispatchQueue.main.async { self.revision += 1 }
        MediaSources.shared.invalidate(site)
    }
    func raw(_ site: String) throws -> Data {
        if site == "YouTube" { return try YouTubeCookies.shared.rawData() }
        var request=query(site);request[kSecReturnData as String]=true;request[kSecMatchLimit as String]=kSecMatchLimitOne
        var item: CFTypeRef?;let status=SecItemCopyMatching(request as CFDictionary,&item)
        if status == errSecItemNotFound { return Data() }
        guard status == errSecSuccess, let data=item as? Data else { throw OmniPlatform.error("COOKIE_INVALID","无法读取本机会话") }
        return data
    }
    func count(_ site: String) -> Int { (try? Self.parse(raw(site),site:site).count) ?? 0 }
    func cookies(_ site: String) throws -> [HTTPCookie] { enabled(site) ? try Self.parse(raw(site),site:site) : [] }
    static func parse(_ data: Data, site: String) throws -> [HTTPCookie] {
        guard data.count <= 1_048_576, let text=String(data:data,encoding:.utf8) else { throw OmniPlatform.error("COOKIE_INVALID","需要 1MB 以内的 UTF-8 Netscape Cookie 文件") }
        var result: [HTTPCookie]=[]
        for raw in text.components(separatedBy:.newlines) {
            if raw.isEmpty || (raw.hasPrefix("#") && !raw.hasPrefix("#HttpOnly_")) { continue }
            let line=raw.hasPrefix("#HttpOnly_") ? String(raw.dropFirst(10)) : raw
            let fields=line.components(separatedBy:"\t")
            guard fields.count == 7 else { throw OmniPlatform.error("COOKIE_INVALID","Cookie 文件需为 Netscape 七列格式") }
            let host=fields[0].lowercased().trimmingCharacters(in:CharacterSet(charactersIn:"."))
            guard (OmniPlatform.domains[site] ?? []).contains(where:{OmniPlatform.owns(host,$0)}) else { continue }
            guard ["TRUE","FALSE"].contains(fields[1]),["TRUE","FALSE"].contains(fields[3]), let expiry=Double(fields[4]),expiry>=0,fields[2].hasPrefix("/"),!fields[5].isEmpty,!fields[5].contains(";"),!fields[6].contains("\r") else { throw OmniPlatform.error("COOKIE_INVALID","Cookie 属性不合法") }
            if expiry>0 && expiry<=Date().timeIntervalSince1970 { continue }
            var properties: [HTTPCookiePropertyKey:Any]=[.domain:fields[1]=="TRUE" ? "."+host:host,.path:fields[2],.name:fields[5],.value:fields[6],.secure:fields[3]]
            if expiry>0 { properties[.expires]=Date(timeIntervalSince1970:expiry) }
            if raw.hasPrefix("#HttpOnly_") { properties[HTTPCookiePropertyKey(rawValue:"HttpOnly")]="TRUE" }
            if let cookie=HTTPCookie(properties:properties) { result.append(cookie) }
        }
        return result
    }
    func importFile(_ file: URL, site: String) throws {
        let access=file.startAccessingSecurityScopedResource();defer{if access{file.stopAccessingSecurityScopedResource()}}
        if site == "YouTube" { try YouTubeCookies.shared.importFile(file);changed(site);return }
        let size=try file.resourceValues(forKeys:[.fileSizeKey]).fileSize ?? 0
        guard size<=1_048_576 else{throw OmniPlatform.error("COOKIE_INVALID","Cookie 文件不能超过 1MB")}
        try save(try Self.parse(Data(contentsOf:file),site:site),site:site)
    }
    func importHeader(_ header: String, site: String) throws {
        guard let domain=OmniPlatform.domains[site]?.first else{throw OmniPlatform.error("COOKIE_INVALID","未知平台")}
        let pairs=header.split(separator:";").compactMap { part -> HTTPCookie? in
            let pair=part.trimmingCharacters(in:.whitespaces).split(separator:"=",maxSplits:1,omittingEmptySubsequences:false)
            guard pair.count==2,!pair[0].isEmpty else{return nil}
            return HTTPCookie(properties:[.domain:"."+domain,.path:"/",.name:String(pair[0]),.value:String(pair[1]),.secure:"TRUE"])
        }
        try save(pairs,site:site)
    }
    func save(_ cookies: [HTTPCookie], site: String) throws {
        lock.lock();defer{lock.unlock()}
        let allowed=cookies.filter { cookie in let host=cookie.domain.trimmingCharacters(in:CharacterSet(charactersIn:"."));return (OmniPlatform.domains[site] ?? []).contains { OmniPlatform.owns(host,$0) } }
        guard !allowed.isEmpty else { throw OmniPlatform.error("COOKIE_INVALID","没有该平台的有效 Cookie") }
        let lines=allowed.map { c in "\(c.isHTTPOnly ? "#HttpOnly_":"")\(c.domain)\t\(c.domain.hasPrefix(".") ? "TRUE":"FALSE")\t\(c.path)\t\(c.isSecure ? "TRUE":"FALSE")\t\(Int(c.expiresDate?.timeIntervalSince1970 ?? 0))\t\(c.name)\t\(c.value)" }
        let data=Data(("# Netscape HTTP Cookie File\n"+lines.joined(separator:"\n")+"\n").utf8)
        if site == "YouTube" { try YouTubeCookies.shared.saveImportedData(data);changed(site);return }
        let status=SecItemUpdate(query(site) as CFDictionary,[kSecValueData as String:data] as CFDictionary)
        if status == errSecItemNotFound { var item=query(site);item[kSecValueData as String]=data;item[kSecAttrAccessible as String]=kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly;guard SecItemAdd(item as CFDictionary,nil)==errSecSuccess else{throw OmniPlatform.error("COOKIE_INVALID","无法安全保存会话")} }
        else if status != errSecSuccess { throw OmniPlatform.error("COOKIE_INVALID","无法更新会话") }
        changed(site)
    }
    func clear(_ site: String) { if site=="YouTube"{YouTubeCookies.shared.clear()}else{SecItemDelete(query(site) as CFDictionary)};if site=="哔哩哔哩"{defaults.removeObject(forKey:"com.omni.downloader.bilibiliCookie")};changed(site) }
    func header(_ site: String, url: URL) -> String {
        guard url.scheme=="https",let host=url.host,(OmniPlatform.domains[site] ?? []).contains(where:{OmniPlatform.owns(host,$0)}) else{return ""}
        return ((try? cookies(site)) ?? []).filter { c in
            let domain=c.domain.trimmingCharacters(in:CharacterSet(charactersIn:"."))
            return (c.domain.hasPrefix(".") ? OmniPlatform.owns(host,domain):host==domain) && url.path.hasPrefix(c.path) && (c.expiresDate.map{$0>Date()} ?? true)
        }.map{"\($0.name)=\($0.value)"}.joined(separator:"; ")
    }
}

enum OmniNetwork {
    static let userAgent="Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15"
    static func configuration() throws -> URLSessionConfiguration {
        let config=URLSessionConfiguration.ephemeral;config.timeoutIntervalForRequest=15;config.timeoutIntervalForResource=45
        config.httpCookieStorage=nil;config.httpShouldSetCookies=false
        let proxy=UserDefaults.standard.string(forKey:"omni.proxy")?.trimmingCharacters(in:.whitespacesAndNewlines) ?? ""
        if !proxy.isEmpty {
            guard let parts=URLComponents(string:proxy),let host=parts.host,let port=parts.port,let p=NWEndpoint.Port(rawValue:UInt16(clamping:port)),port>0,port<=65535,parts.user==nil,parts.password==nil,["http","https","socks5","socks5h"].contains(parts.scheme ?? "") else{throw OmniPlatform.error("NETWORK_ERROR","代理需为带端口的 HTTP、HTTPS 或 SOCKS5 地址")}
            if #available(iOS 17.0,*) {
                let endpoint=NWEndpoint.hostPort(host:NWEndpoint.Host(host),port:p)
                if parts.scheme?.hasPrefix("socks")==true { config.proxyConfigurations=[ProxyConfiguration(socksv5Proxy:endpoint)] }
                else { config.proxyConfigurations=[ProxyConfiguration(httpCONNECTProxy:endpoint,tlsOptions:parts.scheme=="https" ? NWProtocolTLS.Options():nil)] }
            }else{throw OmniPlatform.error("NETWORK_ERROR","应用内代理需要 iOS 17 或更新版本；当前系统可使用系统代理或 VPN")}
        }
        return config
    }
    static func request(_ url: URL, site: String, headers: [String:String]=[:]) -> URLRequest {
        var request=URLRequest(url:url);request.setValue(userAgent,forHTTPHeaderField:"User-Agent")
        if let home=OmniPlatform.homes[site]{request.setValue(home+"/",forHTTPHeaderField:"Referer")}
        for(key,value)in headers where !["cookie","authorization","proxy-authorization"].contains(key.lowercased()){request.setValue(value,forHTTPHeaderField:key)}
        let cookie=PlatformSession.shared.header(site,url:url);if !cookie.isEmpty{request.setValue(cookie,forHTTPHeaderField:"Cookie")}
        return request
    }
    static func data(_ url: URL, site: String,headers:[String:String]=[:],timeout:TimeInterval=12) async throws -> (Data,HTTPURLResponse) {
        let config=try configuration();config.timeoutIntervalForRequest=timeout;config.timeoutIntervalForResource=timeout
        let session=URLSession(configuration:config,delegate:RedirectGuard(site),delegateQueue:nil);defer{session.finishTasksAndInvalidate()}
        let(data,response)=try await session.data(for:request(url,site:site,headers:headers))
        guard let http=response as? HTTPURLResponse else{throw OmniPlatform.error("NETWORK_ERROR","没有有效的网络响应")}
        guard (200...299).contains(http.statusCode) else{throw OmniPlatform.error(http.statusCode==429 ? "RATE_LIMITED":http.statusCode==401 ? "AUTH_REQUIRED":"NETWORK_ERROR","HTTP \(http.statusCode)")}
        guard data.count<=16_777_216 else{throw OmniPlatform.error("EXTRACTOR_CHANGED","平台数据过大")};return(data,http)
    }
    final class RedirectGuard:NSObject,URLSessionTaskDelegate {
        let site:String;init(_ site:String){self.site=site}
        func urlSession(_ session:URLSession,task:URLSessionTask,willPerformHTTPRedirection response:HTTPURLResponse,newRequest request:URLRequest,completionHandler:@escaping(URLRequest?)->Void){
            guard let url=request.url,["https","http"].contains(url.scheme ?? ""),url.user==nil,url.password==nil else{completionHandler(nil);return}
            var next=request;next.setValue(nil,forHTTPHeaderField:"Cookie");next.setValue(nil,forHTTPHeaderField:"Authorization")
            let cookie=PlatformSession.shared.header(site,url:url);if !cookie.isEmpty{next.setValue(cookie,forHTTPHeaderField:"Cookie")};completionHandler(next)
        }
    }
}
