import Foundation
import Combine

enum ReleaseVersion {
    static func newer(_ candidate: String, than installed: String) -> Bool {
        func parts(_ value: String) -> [Int]? {
            let clean = value.hasPrefix("v") ? String(value.dropFirst()) : value
            let values = clean.split(separator: ".", omittingEmptySubsequences: false)
            guard (2...4).contains(values.count), values.allSatisfy({ !$0.isEmpty && $0.allSatisfy(\.isNumber) }) else { return nil }
            return values.compactMap { Int($0) }
        }
        guard let left = parts(candidate), let right = parts(installed) else { return false }
        for i in 0..<max(left.count, right.count) {
            let a = i < left.count ? left[i] : 0, b = i < right.count ? right[i] : 0
            if a != b { return a > b }
        }
        return false
    }
}

@MainActor final class AppUpdates: ObservableObject {
    static let shared = AppUpdates()
    @Published private(set) var checking = false
    @Published private(set) var message = ""
    @Published private(set) var releaseURL: URL?
    @Published private(set) var version = ""
    private var lastCheck: Date { Date(timeIntervalSince1970: UserDefaults.standard.double(forKey: "omni.update.lastCheck")) }
    func check(force: Bool = false) async {
        guard !checking else { return }
        guard force || (UserDefaults.standard.object(forKey: "omni.update.automatic") as? Bool ?? true) && Date().timeIntervalSince(lastCheck) > 21600 else { return }
        checking = true; defer { checking = false }
        do {
            var request = URLRequest(url: URL(string: "https://api.github.com/repos/baishu136/OmniDownloader/releases?per_page=20")!)
            request.timeoutInterval = 15; request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let response = response as? HTTPURLResponse, response.statusCode == 200 else { throw URLError(.badServerResponse) }
            guard let releases = try JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { throw URLError(.cannotParseResponse) }
            let current = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.7.61"
            var found: (String, URL)?
            for item in releases where item["draft"] as? Bool != true && item["prerelease"] as? Bool != true {
                guard let tag = item["tag_name"] as? String, ReleaseVersion.newer(tag, than: current),
                      let url = URL(string: item["html_url"] as? String ?? ""), url.scheme == "https", url.host == "github.com",
                      url.path.hasPrefix("/baishu136/OmniDownloader/releases/tag/"),
                      let assets = item["assets"] as? [[String:Any]], assets.contains(where: { ($0["name"] as? String ?? "").lowercased().hasSuffix(".ipa") }) else { continue }
                if found == nil || ReleaseVersion.newer(tag, than: found!.0) { found = (tag, url) }
            }
            UserDefaults.standard.set(Date().timeIntervalSince1970, forKey:"omni.update.lastCheck")
            version = found?.0 ?? ""; releaseURL = found?.1
            message = found == nil ? "当前没有更新的 iOS 安装包" : "发现 \(version)。IPA 需按发布说明签名安装。"
        } catch is CancellationError {} catch { message = "更新检查失败，请检查网络后重试" }
    }
}
