import Foundation
import Combine
import Security
import WebKit
import CryptoKit
import SwiftUI
import UniformTypeIdentifiers

/// Only youtube.com cookies; secrets stay in this device's Keychain, never UserDefaults.
final class YouTubeCookies: ObservableObject {
    static let shared = YouTubeCookies()
    @Published private(set) var count = 0
    private let service = "com.omni.downloader.youtube-cookies.v1"
    private var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
         kSecAttrAccount as String: "YouTube"]
    }
    private init() { count = (try? cookies().count) ?? 0 }
    func rawData() throws -> Data {
        var request = query
        request[kSecReturnData as String] = true
        request[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(request as CFDictionary, &result)
        if status == errSecItemNotFound { return Data() }
        guard status == errSecSuccess, let data = result as? Data else {
            throw Self.failure("无法读取本机 YouTube 会话")
        }
        return data
    }
    var fingerprint: String { ((try? SHA256.hash(data: rawData()).map { String(format: "%02x", $0) }.joined()) ?? "missing") + PlatformSession.shared.generation("YouTube") }
    func cookies() throws -> [HTTPCookie] {
        if UserDefaults.standard.bool(forKey: "com.omni.downloader.cookieDisabled.YouTube") { return [] }
        return try Self.parse(rawData()).cookies
    }
    static func parse(_ data: Data) throws -> (cookies: [HTTPCookie], lines: [String]) {
        guard data.count <= 1_048_576, let text = String(data: data, encoding: .utf8) else { throw failure("需要不超过 1MB 的 UTF-8 Netscape cookies.txt") }
        var cookies: [HTTPCookie] = [], lines: [String] = []
        for original in text.components(separatedBy: .newlines) {
            let line = original.trimmingCharacters(in: .whitespacesAndNewlines)
            if line.isEmpty || (line.hasPrefix("#") && !line.hasPrefix("#HttpOnly_")) { continue }
            let stripped = line.hasPrefix("#HttpOnly_") ? String(line.dropFirst(10)) : line
            let fields = stripped.components(separatedBy: "\t")
            guard fields.count == 7 else { throw failure("Cookie 行格式不正确，需要 Netscape 七列格式") }
            let domain = fields[0].lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "."))
            guard domain == "youtube.com" || domain.hasSuffix(".youtube.com") else { continue }
            guard let expiry = Double(fields[4]), expiry >= 0, fields[2].hasPrefix("/"),
                  ["TRUE", "FALSE"].contains(fields[1]), ["TRUE", "FALSE"].contains(fields[3]),
                  !fields[5].isEmpty, !fields[5].contains(";"), !fields[6].contains("\r") else { throw failure("Cookie 属性不合法") }
            if expiry > 0 && expiry <= Date().timeIntervalSince1970 { continue }
            var properties: [HTTPCookiePropertyKey: Any] = [.domain: fields[1] == "TRUE" ? "." + domain : domain,
                .path: fields[2], .name: fields[5], .value: fields[6], .secure: fields[3] == "TRUE" ? "TRUE" : "FALSE"]
            if expiry > 0 { properties[.expires] = Date(timeIntervalSince1970: expiry) }
            if original.hasPrefix("#HttpOnly_") { properties[HTTPCookiePropertyKey(rawValue: "HttpOnly")] = "TRUE" }
            if let cookie = HTTPCookie(properties: properties) { cookies.append(cookie); lines.append(original) }
        }
        return (cookies, lines)
    }
    func importFile(_ url: URL) throws {
        let granted = url.startAccessingSecurityScopedResource()
        defer { if granted { url.stopAccessingSecurityScopedResource() } }
        let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        guard size <= 1_048_576 else { throw Self.failure("Cookie 文件不得超过 1MB") }
        let parsed = try Self.parse(Data(contentsOf: url))
        guard !parsed.cookies.isEmpty else { throw Self.failure("文件中没有 YouTube 的未过期 Cookie") }
        let data = Data(("# Netscape HTTP Cookie File\n" + parsed.lines.joined(separator: "\n") + "\n").utf8)
        try saveImportedData(data)
    }
    func saveImportedData(_ data: Data) throws {
        let parsed = try Self.parse(data)
        guard !parsed.cookies.isEmpty else { throw Self.failure("文件中没有有效 YouTube Cookie") }
        let status = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            var item = query
            item[kSecValueData as String] = data
            item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            guard SecItemAdd(item as CFDictionary, nil) == errSecSuccess else { throw Self.failure("无法安全保存 YouTube Cookie") }
        } else if status != errSecSuccess { throw Self.failure("无法更新 YouTube Cookie") }
        count = parsed.cookies.count
    }
    func clear() {
        SecItemDelete(query as CFDictionary)
        count = 0
        YouTubeSources.clear()
    }
    static func failure(_ message: String) -> NSError { NSError(domain: "YouTube", code: 1, userInfo: [NSLocalizedDescriptionKey: message]) }
}

struct YouTubeCookieSection: View {
    @ObservedObject private var store = YouTubeCookies.shared
    @State private var choosing = false
    @State private var message = ""
    var body: some View {
        Section(header: Text("YouTube Cookie"), footer: Text("从已登录 YouTube 的浏览器导出 Netscape cookies.txt。仅导入 YouTube 域的有效会话，保存在本机钥匙串；平台仍可能要求人工验证。")) {
            Text(store.count > 0 ? "已导入 \(store.count) 项 Cookie" : "尚未导入")
            Button(store.count > 0 ? "更新 cookies.txt" : "导入 cookies.txt") { choosing = true }
            if store.count > 0 { Button("移除 YouTube Cookie", role: .destructive) { store.clear(); message = "会话已移除" } }
            Link("官方 Cookie 导出说明", destination: URL(string: "https://github.com/yt-dlp/yt-dlp/wiki/Extractors#exporting-youtube-cookies")!)
            if !message.isEmpty { Text(message).font(.caption).foregroundStyle(OmniPalette.secondary) }
        }
        .fileImporter(isPresented: $choosing, allowedContentTypes: [.plainText, .text, .data]) { result in
            do { try store.importFile(result.get()); message = "已加密保存。请重新解析原 YouTube 链接。" }
            catch { message = error.localizedDescription }
        }
    }
}
