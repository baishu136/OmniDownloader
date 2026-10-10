import SwiftUI

/// 首页视图：负责链接输入、剪贴板自动嗅探识别、平台导航展示与唤起格式选择
struct HomeView: View {
    @EnvironmentObject var downloadManager: DownloadManager
    @ObservedObject private var cookieStore = CookieStore.shared
    @State private var inputUrl: String = ""
    @State private var isParsing: Bool = false
    @State private var detectedClipboardUrl: String? = nil
    @State private var parsedMetadata: VideoMetadata? = nil
    @State private var showFormatSheet: Bool = false
    @State private var errorMessage: String? = nil
    @State private var showErrorAlert: Bool = false
    @State private var activeBrowserSite: RelaySite? = nil

    private let strings = AppStrings.current

    // 支持的平台胶囊列表
    private let supportedPlatforms = [
        ("哔哩哔哩", "play.rectangle.fill", Color.blue),
        ("抖音", "music.note", Color.black),
        ("快手", "video.fill", Color.orange),
        ("小红书", "bookmark.fill", Color.red)
    ]

    var body: some View {
        NavigationView {
            ZStack {
                Color(UIColor.systemGroupedBackground)
                    .ignoresSafeArea()

                ScrollView {
                    VStack(spacing: 20) {
                        // 顶部剪贴板快速粘贴条
                        if let detected = detectedClipboardUrl {
                            clipboardNotificationBanner(url: detected)
                                .transition(.move(edge: .top).combined(with: .opacity))
                        }

                        // 主输入框卡片
                        inputSectionCard

                        // 备用中转解析站卡片 (仅在配置了中转站时展示)
                        if !cookieStore.relaySites.isEmpty {
                            relaySitesSectionCard
                        }

                        // 支持平台展示区
                        supportedPlatformsCard

                        // 使用小贴士卡片
                        tipsCard

                        Spacer(minLength: 40)
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 12)
                }
            }
            .navigationTitle(strings.appName)
            .navigationBarTitleDisplayMode(.large)
            .sheet(isPresented: $showFormatSheet) {
                if let meta = parsedMetadata {
                    FormatSelectorSheet(metadata: meta)
                }
            }
            .sheet(item: $activeBrowserSite) { site in
                RelayBrowserSheet(site: site, initialVideoUrl: inputUrl)
            }
            .alert(isPresented: $showErrorAlert) {
                Alert(
                    title: Text(strings.errorTitle),
                    message: Text(errorMessage ?? strings.unknownError),
                    dismissButton: .default(Text(strings.confirm))
                )
            }
            .onAppear {
                checkClipboard()
            }
        }
        .navigationViewStyle(StackNavigationViewStyle())
    }
    
    // MARK: - 剪贴板嗅探提示浮条
    @ViewBuilder
    private func clipboardNotificationBanner(url: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: "doc.on.clipboard.fill")
                .foregroundColor(.accentColor)
                .font(.system(size: 18))
            
            VStack(alignment: .leading, spacing: 2) {
                Text(strings.clipboardDetectedTitle)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(.primary)
                Text(url)
                    .font(.system(size: 12))
                    .foregroundColor(.secondary)
                    .lineLimit(1)
            }
            
            Spacer()
            
            Button(action: {
                inputUrl = url
                detectedClipboardUrl = nil
                startExtract(url: url)
            }) {
                Text(strings.parseAction)
                    .font(.system(size: 13, weight: .bold))
                    .foregroundColor(.white)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(Color.accentColor)
                    .cornerRadius(14)
            }
            
            Button(action: {
                withAnimation {
                    detectedClipboardUrl = nil
                }
            }) {
                Image(systemName: "xmark.circle.fill")
                    .foregroundColor(.secondary)
            }
        }
        .padding(12)
        .background(Color(UIColor.secondarySystemGroupedBackground))
        .cornerRadius(12)
        .shadow(color: Color.black.opacity(0.04), radius: 5, x: 0, y: 2)
    }
    
    // MARK: - 输入区域卡片
    private var inputSectionCard: some View {
        VStack(spacing: 16) {
            HStack {
                Text(strings.inputPrompt)
                    .font(.system(size: 15, weight: .medium))
                    .foregroundColor(.secondary)
                Spacer()
                
                if !inputUrl.isEmpty {
                    Button(action: {
                        inputUrl = ""
                    }) {
                        HStack(spacing: 4) {
                            Image(systemName: "trash")
                            Text(strings.clearAction)
                        }
                        .font(.system(size: 13))
                        .foregroundColor(.secondary)
                    }
                }
            }
            
            // 多行输入区
            ZStack(alignment: .topLeading) {
                if inputUrl.isEmpty {
                    Text(strings.inputPlaceholder)
                        .font(.system(size: 15))
                        .foregroundColor(Color(UIColor.placeholderText))
                        .padding(.top, 8)
                        .padding(.leading, 4)
                }
                
                TextEditor(text: $inputUrl)
                    .frame(minHeight: 90)
                    .font(.system(size: 15))
                    .scrollContentBackground(.hidden)
                    .background(Color.clear)
            }
            .padding(8)
            .background(Color(UIColor.tertiarySystemGroupedBackground))
            .cornerRadius(10)
            
            // 操作按钮行：读取剪贴板 + 立即解析
            HStack(spacing: 12) {
                Button(action: pasteFromClipboard) {
                    HStack(spacing: 6) {
                        Image(systemName: "doc.on.clipboard")
                        Text(strings.pasteAction)
                    }
                    .font(.system(size: 15, weight: .medium))
                    .foregroundColor(.primary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(Color(UIColor.secondarySystemGroupedBackground))
                    .cornerRadius(12)
                    .overlay(
                        RoundedRectangle(cornerRadius: 12)
                            .stroke(Color.gray.opacity(0.2), lineWidth: 1)
                    )
                }
                
                Button(action: {
                    startExtract(url: inputUrl)
                }) {
                    HStack(spacing: 8) {
                        if isParsing {
                            ProgressView()
                                .progressViewStyle(CircularProgressViewStyle(tint: .white))
                            Text(strings.parsing)
                        } else {
                            Image(systemName: "arrow.down.circle.fill")
                            Text(strings.parseAction)
                        }
                    }
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(inputUrl.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || isParsing ? Color.gray.opacity(0.5) : Color.accentColor)
                    .cornerRadius(12)
                }
                .disabled(inputUrl.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || isParsing)
            }
        }
        .padding(16)
        .background(Color(UIColor.secondarySystemGroupedBackground))
        .cornerRadius(16)
        .shadow(color: Color.black.opacity(0.04), radius: 6, x: 0, y: 2)
    }
    
    // MARK: - 支持平台展示卡片
    private var supportedPlatformsCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Image(systemName: "checkmark.seal.fill")
                    .foregroundColor(.blue)
                Text(strings.supportedSites)
                    .font(.system(size: 16, weight: .semibold))
                Spacer()
            }
            
            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
                ForEach(supportedPlatforms, id: \.0) { item in
                    HStack(spacing: 10) {
                        Image(systemName: item.1)
                            .font(.system(size: 18))
                            .foregroundColor(item.2)
                        Text(item.0)
                            .font(.system(size: 14, weight: .medium))
                            .foregroundColor(.primary)
                        Spacer()
                    }
                    .padding(12)
                    .background(Color(UIColor.tertiarySystemGroupedBackground))
                    .cornerRadius(10)
                }
            }
        }
        .padding(16)
        .background(Color(UIColor.secondarySystemGroupedBackground))
        .cornerRadius(16)
        .shadow(color: Color.black.opacity(0.04), radius: 6, x: 0, y: 2)
    }
    
    // MARK: - 小贴士卡片
    private var tipsCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Image(systemName: "lightbulb.fill")
                    .foregroundColor(.orange)
                Text(strings.tipsTitle)
                    .font(.system(size: 15, weight: .semibold))
                Spacer()
            }
            
            Text(strings.tipsContent)
                .font(.system(size: 13))
                .foregroundColor(.secondary)
                .lineSpacing(4)
        }
        .padding(16)
        .background(Color(UIColor.secondarySystemGroupedBackground))
        .cornerRadius(16)
        .shadow(color: Color.black.opacity(0.04), radius: 6, x: 0, y: 2)
    }
    
    // MARK: - 剪贴板检查
    private func checkClipboard() {
        if let string = UIPasteboard.general.string, !string.isEmpty {
            if let validUrl = UrlSniffer.extractFirstUrl(from: string) {
                if validUrl != inputUrl {
                    withAnimation {
                        detectedClipboardUrl = validUrl
                    }
                }
            }
        }
    }
    
    // 粘贴操作
    private func pasteFromClipboard() {
        if let string = UIPasteboard.general.string, !string.isEmpty {
            if let extracted = UrlSniffer.extractFirstUrl(from: string) {
                inputUrl = extracted
            } else {
                inputUrl = string
            }
        }
    }
    
    // MARK: - 执行提取解析
    private func startExtract(url: String) {
        let cleanText = url.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let extractedUrl = UrlSniffer.extractFirstUrl(from: cleanText) ?? (cleanText.hasPrefix("http") ? cleanText : nil) else {
            errorMessage = "未在输入内容中检测到有效的链接地址"
            showErrorAlert = true
            return
        }
        
        isParsing = true
        errorMessage = nil
        
        Task {
            do {
                let resolved = await UrlSniffer.resolveRedirects(for: extractedUrl)
                let site = UrlSniffer.detectSite(from: resolved)
                
                let metadata: VideoMetadata
                if site == "哔哩哔哩" {
                    metadata = try await BilibiliExtractor.shared.extract(rawUrl: resolved)
                } else if DomesticExtractor.shared.isSupported(resolved) {
                    metadata = try await DomesticExtractor.shared.extract(rawUrl: resolved)
                } else {
                    // 通用降级方案
                    metadata = VideoMetadata(
                        url: resolved,
                        title: "视频下载",
                        siteName: site,
                        availableVideoFormats: [
                            FormatOption(formatId: "direct", resolutionLabel: "直接提取原视频", height: 1080)
                        ]
                    )
                }
                
                await MainActor.run {
                    self.parsedMetadata = metadata
                    self.isParsing = false
                    self.showFormatSheet = true
                }
            } catch {
                await MainActor.run {
                    self.isParsing = false
                    self.errorMessage = error.localizedDescription
                    self.showErrorAlert = true
                }
            }
        }
    }

    // MARK: - 备用中转站卡片 (仅在配置了中转站时展示)
    private var relaySitesSectionCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Image(systemName: "globe")
                    .foregroundColor(.blue)
                Text("备用中转解析站")
                    .font(.system(size: 16, weight: .semibold))
                Spacer()
            }

            VStack(spacing: 8) {
                ForEach(cookieStore.relaySites) { site in
                    HStack(spacing: 12) {
                        ZStack {
                            RoundedRectangle(cornerRadius: 8)
                                .fill(Color.blue.opacity(0.12))
                                .frame(width: 34, height: 34)
                            Text(String(site.name.prefix(1)).uppercased())
                                .font(.system(size: 15, weight: .bold))
                                .foregroundColor(.blue)
                        }

                        VStack(alignment: .leading, spacing: 2) {
                            Text(site.name)
                                .font(.system(size: 14, weight: .bold))
                                .lineLimit(1)
                            Text(site.url)
                                .font(.system(size: 11))
                                .foregroundColor(.secondary)
                                .lineLimit(1)
                        }

                        Spacer()

                        Button(action: {
                            activeBrowserSite = site
                        }) {
                            HStack(spacing: 4) {
                                Image(systemName: "bolt.fill")
                                    .font(.system(size: 11))
                                Text("嗅探解析")
                                    .font(.system(size: 12, weight: .semibold))
                            }
                            .foregroundColor(.white)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 6)
                            .background(Color.blue)
                            .cornerRadius(8)
                        }
                    }
                    .padding(10)
                    .background(Color(UIColor.tertiarySystemGroupedBackground))
                    .cornerRadius(12)
                }
            }
        }
        .padding(16)
        .background(Color(UIColor.secondarySystemGroupedBackground))
        .cornerRadius(16)
        .shadow(color: Color.black.opacity(0.04), radius: 6, x: 0, y: 2)
    }
}

import WebKit

/// iOS 端原生媒体嗅探浏览器弹窗 (对应 Android RelayBrowserDialog)
struct RelayBrowserSheet: View {
    let site: RelaySite
    let initialVideoUrl: String
    @Environment(\.presentationMode) private var presentationMode
    @EnvironmentObject var downloadManager: DownloadManager

    @State private var sniffedUrl: String = ""
    @State private var sniffedTitle: String = ""
    @State private var webTitle: String = ""
    @State private var canGoBack: Bool = false
    @State private var webView: WKWebView? = nil

    var body: some View {
        NavigationView {
            ZStack(alignment: .bottom) {
                RelayWebViewRepresentable(
                    url: targetInitialUrl,
                    videoUrlToInject: initialVideoUrl,
                    onTitleChanged: { self.webTitle = $0 },
                    onMediaSniffed: { url, title in
                        handleSniffedMedia(url: url, title: title)
                    },
                    webViewRef: { self.webView = $0 }
                )
                .ignoresSafeArea(edges: .bottom)

                // 底部浮动高亮一键下载胶囊
                if !sniffedUrl.isEmpty {
                    VStack(spacing: 8) {
                        HStack(spacing: 10) {
                            ZStack {
                                Circle()
                                    .fill(Color.blue)
                                    .frame(width: 32, height: 32)
                                Image(systemName: "bolt.fill")
                                    .foregroundColor(.white)
                                    .font(.system(size: 15))
                            }

                            VStack(alignment: .leading, spacing: 2) {
                                Text("已嗅探到视频资源！")
                                    .font(.system(size: 13, weight: .bold))
                                    .foregroundColor(.primary)
                                Text(sniffedUrl)
                                    .font(.system(size: 11))
                                    .foregroundColor(.secondary)
                                    .lineLimit(1)
                            }

                            Spacer()

                            Button(action: startDownloadSniffed) {
                                Text("立即下载")
                                    .font(.system(size: 13, weight: .bold))
                                    .foregroundColor(.white)
                                    .padding(.horizontal, 12)
                                    .padding(.vertical, 7)
                                    .background(Color.blue)
                                    .cornerRadius(8)
                            }

                            Button(action: { sniffedUrl = "" }) {
                                Image(systemName: "xmark")
                                    .font(.system(size: 12))
                                    .foregroundColor(.secondary)
                            }
                        }
                        .padding(12)
                        .background(Color(UIColor.secondarySystemGroupedBackground))
                        .cornerRadius(14)
                        .shadow(color: Color.black.opacity(0.12), radius: 8, x: 0, y: 3)
                    }
                    .padding(.horizontal, 16)
                    .padding(.bottom, 12)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                }
            }
            .navigationTitle(webTitle.isEmpty ? site.name : webTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") {
                        presentationMode.wrappedValue.dismiss()
                    }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(action: { webView?.reload() }) {
                        Image(systemName: "arrow.clockwise")
                    }
                }
            }
        }
    }

    private var targetInitialUrl: URL {
        var clean = site.url
        if clean.lowercased().contains("greenvideo.cc") && !initialVideoUrl.isEmpty {
            let sep = clean.contains("?") ? "&" : "?"
            clean += "\(sep)url=\(initialVideoUrl.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? "")"
        }
        return URL(string: clean) ?? URL(string: site.url)!
    }

    private func handleSniffedMedia(url: String, title: String) {
        let (unpackedUrl, unpackedTitle) = UrlSniffer.unpackDirectMediaUrl(rawUrl: url, defaultTitle: title.isEmpty ? "\(site.name) 视频" : title)
        guard UrlSniffer.isDirectMediaUrl(urlString: unpackedUrl) else { return }
        withAnimation {
            self.sniffedUrl = unpackedUrl
            self.sniffedTitle = unpackedTitle
        }
    }

    private func startDownloadSniffed() {
        guard !sniffedUrl.isEmpty else { return }
        let task = DownloadTask(
            url: sniffedUrl,
            title: sniffedTitle.isEmpty ? "\(site.name) 视频" : sniffedTitle,
            downloadType: .videoWithAudio,
            selectedResolution: "中转提取原画"
        )
        downloadManager.addTask(task)
        presentationMode.wrappedValue.dismiss()
    }
}

/// 基于 WKWebView 的媒体嗅探桥接
struct RelayWebViewRepresentable: UIViewRepresentable {
    let url: URL
    let videoUrlToInject: String
    let onTitleChanged: (String) -> Void
    let onMediaSniffed: (String, String) -> Void
    let webViewRef: (WKWebView) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.allowsInlineMediaPlayback = true
        let contentController = WKUserContentController()
        contentController.add(context.coordinator, name: "OmniSniffer")
        config.userContentController = contentController

        let wv = WKWebView(frame: .zero, configuration: config)
        wv.navigationDelegate = context.coordinator
        wv.uiDelegate = context.coordinator
        wv.customUserAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
        webViewRef(wv)
        wv.load(URLRequest(url: url))
        return wv
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}

    class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler {
        let parent: RelayWebViewRepresentable

        init(_ parent: RelayWebViewRepresentable) {
            self.parent = parent
        }

        func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
            if message.name == "OmniSniffer", let dict = message.body as? [String: String], let mediaUrl = dict["url"] {
                DispatchQueue.main.async {
                    self.parent.onMediaSniffed(mediaUrl, dict["title"] ?? "")
                }
            }
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            if let title = webView.title {
                parent.onTitleChanged(title)
            }
            injectSniffScript(webView)
        }

        func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            if let targetUrl = navigationAction.request.url?.absoluteString {
                if UrlSniffer.isDirectMediaUrl(urlString: targetUrl) {
                    DispatchQueue.main.async {
                        self.parent.onMediaSniffed(targetUrl, webView.title ?? "")
                    }
                }
            }
            decisionHandler(.allow)
        }

        private func injectSniffScript(_ webView: WKWebView) {
            let js = """
            (function() {
                function report(url) {
                    if (url && url.startsWith('http')) {
                        window.webkit.messageHandlers.OmniSniffer.postMessage({ url: url, title: document.title || '' });
                    }
                }
                var videos = document.querySelectorAll('video, source');
                for (var i = 0; i < videos.length; i++) {
                    if (videos[i].src) report(videos[i].src);
                }
            })();
            """
            webView.evaluateJavaScript(js, completionHandler: nil)
        }
    }
}
