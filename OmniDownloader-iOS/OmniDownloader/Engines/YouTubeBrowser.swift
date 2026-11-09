import Foundation
import WebKit
import SwiftUI

struct YouTubeSource {
    let itag: String, url: URL, mime: String
    let width: Int, height: Int, fps: Int, bytes: Int64, bitrate: Int
    let hasAudio: Bool
}

/// Signed resources remain in memory. Tasks retain the post and format identity only.
enum YouTubeSources {
    struct Snapshot { let created: Date, fingerprint: String, title: String, author: String, duration: Double, sources: [String: YouTubeSource] }
    private static let lock = NSLock()
    private static var snapshots: [String: Snapshot] = [:]
    static func videoID(_ value: String) -> String? {
        guard let url = URL(string: value), let host = url.host?.lowercased() else { return nil }
        let identifier: String?
        if host == "youtu.be" { identifier = url.path.split(separator: "/").first.map(String.init) }
        else if host == "youtube.com" || host.hasSuffix(".youtube.com") {
            identifier = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first(where: { $0.name == "v" })?.value
                ?? url.path.split(separator: "/").last.map(String.init)
        } else { return nil }
        guard let result = identifier, result.range(of: "^[A-Za-z0-9_-]{11}$", options: .regularExpression) != nil else { return nil }
        return result
    }
    static func clear() { lock.lock(); defer { lock.unlock() }; snapshots.removeAll() }
    static func save(_ identifier: String, _ snapshot: Snapshot) { lock.lock(); defer { lock.unlock() }; if snapshots.count >= 16 { snapshots.removeAll() }; snapshots[identifier] = snapshot }
    static func snapshot(_ identifier: String) throws -> Snapshot {
        lock.lock(); let value = snapshots[identifier]; lock.unlock()
        guard let value = value, Date().timeIntervalSince(value.created) < 600,
              value.fingerprint == YouTubeCookies.shared.fingerprint else {
            throw YouTubeCookies.failure("[SOURCE_EXPIRED] 会话或媒体地址已变化，请重新在 YouTube 页面解析原视频")
        }
        return value
    }
}

struct YouTubeBrowserSheet: View {
    let url: String
    let onResolved: (VideoMetadata) -> Void
    @Environment(\.presentationMode) private var presentation
    @State private var metadata: VideoMetadata?
    @State private var detail = "正在载入 YouTube 会话。请在页面中播放目标视频，选择所需清晰度。"
    var body: some View {
        NavigationView {
            VStack(spacing: 10) {
                Text(detail).font(.caption).padding(.horizontal)
                YouTubeWebView(url: url, onMetadata: { value in metadata = value; detail = "已确认 \(value.availableVideoFormats.count) 个可下载的 MP4 格式。" }, onError: { metadata = nil; detail = $0 })
                Text("本机解析需要页面实际取得完整 MP4/AAC 地址；直播、DRM、分段流及未取得的加密地址不作为下载成功。更高清画质可先在播放器中切换。")
                    .font(.caption2).foregroundStyle(OmniPalette.secondary).padding(.horizontal)
                Button("使用已解析资源") { if let value = metadata { onResolved(value); presentation.wrappedValue.dismiss() } }
                    .disabled(metadata == nil).buttonStyle(.borderedProminent).padding(.bottom, 8)
            }
            .background(OmniPalette.background).tint(OmniPalette.primary)
            .navigationTitle("YouTube").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { presentation.wrappedValue.dismiss() } } }
        }
        .navigationViewStyle(.stack)
    }
}

private struct YouTubeWebView: UIViewRepresentable {
    let url: String
    let onMetadata: (VideoMetadata) -> Void
    let onError: (String) -> Void
    func makeCoordinator() -> Coordinator { Coordinator(parent: self) }
    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        config.allowsInlineMediaPlayback = true
        config.userContentController.add(context.coordinator, name: "omniYouTube")
        config.userContentController.addUserScript(WKUserScript(source: Self.script, injectionTime: .atDocumentStart, forMainFrameOnly: true))
        let web = WKWebView(frame: .zero, configuration: config)
        web.navigationDelegate = context.coordinator
        context.coordinator.web = web
        guard let identifier = YouTubeSources.videoID(url), let target = URL(string: "https://m.youtube.com/watch?v=\(identifier)") else { onError("需要单个 YouTube 视频链接"); return web }
        Task { @MainActor in
            do {
                for cookie in try YouTubeCookies.shared.cookies() {
                    await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                        config.websiteDataStore.httpCookieStore.setCookie(cookie) { continuation.resume() }
                    }
                }
                web.load(URLRequest(url: target))
            } catch { onError(error.localizedDescription) }
        }
        return web
    }
    func updateUIView(_ uiView: WKWebView, context: Context) { context.coordinator.parent = self }
    static func dismantleUIView(_ uiView: WKWebView, coordinator: Coordinator) {
        uiView.stopLoading(); uiView.configuration.userContentController.removeScriptMessageHandler(forName: "omniYouTube")
        coordinator.web = nil
    }
    final class Coordinator: NSObject, WKNavigationDelegate, WKScriptMessageHandler {
        var parent: YouTubeWebView
        weak var web: WKWebView?
        private var sources: [String: YouTubeSource] = [:]
        init(parent: YouTubeWebView) { self.parent = parent }
        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) { parent.onError("网络加载失败，请检查 iPhone 网络或系统 VPN") }
        func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            if action.targetFrame?.isMainFrame != false, let target = action.request.url, let host = target.host?.lowercased(),
               !(host == "youtube.com" || host.hasSuffix(".youtube.com") || host == "accounts.google.com") {
                parent.onError("请保持在目标 YouTube 页面中解析")
                decisionHandler(.cancel); return
            }
            decisionHandler(.allow)
        }
        func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
            guard message.frameInfo.isMainFrame, let host = message.frameInfo.request.url?.host?.lowercased(),
                  host == "youtube.com" || host.hasSuffix(".youtube.com"),
                  let expected = YouTubeSources.videoID(parent.url), let payload = message.body as? [String: Any] else { return }
            guard payload["videoId"] as? String == expected else {
                sources.removeAll()
                parent.onError("页面已切换，请返回最初请求的视频后重新解析")
                return
            }
            if let error = payload["error"] as? String { parent.onError(error == "LOGIN_REQUIRED" ? "YouTube 要求验证登录，请更新 Cookie 或在页面中完成人工验证" : "YouTube 当前未允许播放目标视频"); return }
            guard let rows = payload["formats"] as? [[String: Any]], rows.count <= 100 else { return }
            for row in rows {
                guard let itag = row["itag"] as? String, itag.range(of: "^\\d{1,5}$", options: .regularExpression) != nil,
                      let candidate = row["url"] as? String, candidate.count <= 16384, let url = URL(string: candidate), url.scheme == "https", url.user == nil, url.password == nil,
                      let mediaHost = url.host?.lowercased(), mediaHost.hasSuffix(".googlevideo.com"),
                      var parts = URLComponents(url: url, resolvingAgainstBaseURL: false),
                      let mime = row["mime"] as? String, mime.hasPrefix("video/mp4") || mime.hasPrefix("audio/mp4"),
                      let byteString = row["bytes"] as? String, let bytes = Int64(byteString), bytes > 0, bytes <= 20_000_000_000 else { continue }
                let query = Dictionary((parts.queryItems ?? []).map { ($0.name, $0.value ?? "") }, uniquingKeysWith: { first, _ in first })
                guard query["sq"] == nil, query["otf"] != "1", query["itag"] == itag,
                      Int64(query["clen"] ?? "") == bytes, let reference = row["reference"] as? String,
                      let referenceParts = URLComponents(string: reference),
                      let referenceID = referenceParts.queryItems?.first(where: { $0.name == "id" })?.value,
                      !referenceID.isEmpty, query["id"] == referenceID else { continue }
                if let expiry = Double(query["expire"] ?? ""), expiry <= Date().timeIntervalSince1970 + 30 { continue }
                parts.queryItems = parts.queryItems?.filter { !["range", "rn", "rbuf"].contains($0.name) }
                guard let fullURL = parts.url else { continue }
                let width = row["width"] as? Int ?? 0, height = row["height"] as? Int ?? 0
                guard (0...32768).contains(width), (0...32768).contains(height) else { continue }
                sources[itag] = YouTubeSource(itag: itag, url: fullURL, mime: mime,
                    width: width, height: height, fps: row["fps"] as? Int ?? 0,
                    bytes: bytes, bitrate: row["bitrate"] as? Int ?? 0, hasAudio: row["hasAudio"] as? Bool ?? false)
            }
            let title = payload["title"] as? String ?? expected, author = payload["author"] as? String ?? ""
            let duration = Double(payload["duration"] as? String ?? "") ?? 0
            guard duration.isFinite, duration >= 0, duration <= 604800 else { return }
            let videos = sources.values.filter { $0.mime.hasPrefix("video/") }.sorted { $0.width * $0.height > $1.width * $1.height }
            guard !videos.isEmpty else { return }
            YouTubeSources.save(expected, .init(created: Date(), fingerprint: YouTubeCookies.shared.fingerprint, title: title, author: author, duration: duration, sources: sources))
            let options = videos.map { source in FormatOption(formatId: "yt:\(source.itag)", resolutionLabel: "\(source.height)p · \(source.width)×\(source.height)",
                width: source.width, height: source.height, fps: source.fps, approximateSize: "\(source.bytes / 1_048_576) MB", note: source.hasAudio ? "音画合流" : "独立视频轨") }
            let audio = sources.values.filter { $0.mime.hasPrefix("audio/") }.map { FormatOption(formatId: "yt:\($0.itag)", resolutionLabel: "AAC 音轨", ext: "m4a", isAudioOnly: true) }
            parent.onMetadata(VideoMetadata(url: "https://www.youtube.com/watch?v=\(expected)", title: title, author: author,
                durationText: "\(Int(duration))秒", siteName: "YouTube", availableVideoFormats: options, availableAudioFormats: audio))
        }
    }
    private static let script = #"""
    (() => {
      const seen = new Map();
      function inspect(candidate) {
        try {
          const p = window.ytInitialPlayerResponse || document.getElementById('movie_player')?.getPlayerResponse?.();
          if (!p?.videoDetails?.videoId) return;
          if (p.playabilityStatus?.status !== 'OK') {
            window.webkit.messageHandlers.omniYouTube.postMessage({videoId:p.videoDetails.videoId,error:p.playabilityStatus?.status || 'UNAVAILABLE'}); return;
          }
          if (p.videoDetails.isLiveContent || p.streamingData?.drmFamilies?.length || document.querySelector('.ad-showing')) return;
          if (candidate) { const u = new URL(candidate, location.href); if (u.hostname.endsWith('.googlevideo.com') && u.pathname.includes('videoplayback')) seen.set(u.searchParams.get('itag'),u.href); }
          if (seen.size > 100) seen.clear();
          const formats=[];
          for (const [group,hasAudio] of [[p.streamingData?.formats || [],true],[p.streamingData?.adaptiveFormats || [],false]]) {
            for (const f of group) {
              if (f.drmFamilies?.length) continue;
              const ref = f.url || new URLSearchParams(f.signatureCipher || f.cipher || '').get('url');
              const source = f.url || seen.get(String(f.itag));
              if (!ref || !source) continue;
              formats.push({itag:String(f.itag),url:source,reference:ref,mime:f.mimeType || '',bytes:String(f.contentLength || new URL(ref).searchParams.get('clen') || ''),width:f.width||0,height:f.height||0,fps:f.fps||0,bitrate:f.bitrate||0,hasAudio});
            }
          }
          window.webkit.messageHandlers.omniYouTube.postMessage({videoId:p.videoDetails.videoId,title:p.videoDetails.title,author:p.videoDetails.author,duration:p.videoDetails.lengthSeconds,formats});
        } catch (_) {}
      }
      const originalFetch=window.fetch;
      window.fetch=function(input,...args) { inspect(typeof input==='string'?input:input?.url);return originalFetch.call(this,input,...args); };
      const open=XMLHttpRequest.prototype.open;
      XMLHttpRequest.prototype.open=function(method,url,...args) {inspect(url);return open.call(this,method,url,...args);};
      try {new PerformanceObserver(list => list.getEntries().forEach(entry => inspect(entry.name))).observe({entryTypes:['resource']});} catch (_) {}
      setInterval(()=>inspect(),2000);
    })();
    """#
}
