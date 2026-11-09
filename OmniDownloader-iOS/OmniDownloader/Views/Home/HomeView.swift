import SwiftUI
import WebKit

struct SniffedMediaItem:Identifiable,Equatable{
    let id=UUID().uuidString
    let url:String
    let title:String
    let format:String
    let host:String
    let timestamp=Date()
}

@MainActor struct HomeView:View{
    @EnvironmentObject private var downloadManager:DownloadManager
    @ObservedObject private var preferences=CookieStore.shared
    @StateObject private var browser=PlatformBrowser()
    @Environment(\.scenePhase) private var scenePhase
    @AppStorage("omni.language") private var language="system"
    @State private var input=""
    @State private var analyzing=false
    @State private var stage=""
    @State private var error=""
    @State private var metadata:VideoMetadata?
    @State private var showFormats=false
    @State private var analysis:Task<Void,Never>?
    @State private var generation=UUID()
    @State private var activeRelay:RelaySite?
    @State private var youtubeURL=""
    @State private var showYouTube=false
    @State private var pendingYouTube:VideoMetadata?
    @State private var pendingBrowser=false
    @State private var pendingFormats=false
    @State private var bilibiliLogin=false
    @State private var retryAfterLogin=false
    private func L(_ text:String)->String{OmniLocalization.text(text,language:language)}
    var body:some View{
        ZStack{
            OmniPalette.background.ignoresSafeArea()
            if browser.web != nil && !browser.visible{PlatformWebContent(browser:browser).frame(maxWidth:390,maxHeight:780).opacity(0).allowsHitTesting(false).accessibilityHidden(true)}
            ScrollView{
                VStack(alignment:.leading,spacing:18){
                    HStack(spacing:12){OmniBrand();Text("OmniDownloader").font(.system(size:24,weight:.heavy)).minimumScaleFactor(0.7).lineLimit(1);Text("v1.7.20").font(.caption.bold()).foregroundColor(OmniPalette.primary).padding(.horizontal,7).padding(.vertical,4).background(OmniPalette.primaryContainer,in:RoundedRectangle(cornerRadius:8));Spacer(minLength:0)}.padding(.vertical,6)
                    OmniPlatformChips()
                    VStack(spacing:12){
                        HStack(spacing:10){OmniIcon(name:"link",size:20).foregroundColor(OmniPalette.primary);TextField(L("粘贴视频链接或分享文本…"),text:$input).textInputAutocapitalization(.never).disableAutocorrection(true).keyboardType(.URL).submitLabel(.go).onSubmit{start(input)}.accessibilityIdentifier("omni.video.url");if !input.isEmpty{Button{input=""}label:{OmniIcon(name:"xmark",size:20).foregroundColor(OmniPalette.secondary).frame(width:44,height:44)}.accessibilityLabel(L("清空"))};Button(action:paste){OmniIcon(name:"doc.on.clipboard",size:20).foregroundColor(OmniPalette.secondary).frame(width:44,height:44)}.accessibilityLabel(L("粘贴剪贴板"))}.padding(.horizontal,16).padding(.vertical,8).background(OmniPalette.surfaceVariant.opacity(0.35),in:RoundedRectangle(cornerRadius:22))
                        HStack(spacing:12){OmniAction(title:L("粘贴剪贴板"),icon:"doc.on.clipboard",disabled:analyzing,action:paste);OmniAction(title:L(analyzing ? "取消解析":"立即解析"),icon:analyzing ? "xmark":"bolt.fill",prominent:true,disabled:!analyzing && input.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty){if analyzing{cancel()}else{start(input)}}.accessibilityIdentifier("omni.action.analyze")}
                    }.padding(16).omniCard(radius:26)
                    if analyzing{HStack(spacing:10){ProgressView();Text(stage).font(.subheadline).foregroundColor(OmniPalette.secondary)}.accessibilityIdentifier("omni.analysis.stage")}
                    if !error.isEmpty{
                        VStack(alignment:.leading,spacing:10){Text(error).font(.subheadline).foregroundColor(OmniPalette.error);if error.contains("AUTH_REQUIRED") || error.contains("CHALLENGE_REQUIRED") || error.contains("BROWSER_ASSIST_REQUIRED"){Button(L("打开内置浏览器验证")){validate()}.buttonStyle(OmniPressStyle())}}
                            .padding(16).frame(maxWidth:.infinity,alignment:.leading).omniCard()
                    }
                    ForEach(preferences.relaySites){site in
                        HStack(spacing:12){Text(String(site.name.prefix(1)).uppercased()).font(.title3.bold()).foregroundColor(OmniPalette.primary).frame(width:46,height:46).background(OmniPalette.primaryContainer,in:RoundedRectangle(cornerRadius:15));VStack(alignment:.leading,spacing:3){Text(site.name).font(.headline);Text(URL(string:site.url)?.host ?? site.url).font(.caption).foregroundColor(OmniPalette.secondary).lineLimit(1)};Spacer();Button{activeRelay=site}label:{HStack(spacing:5){OmniIcon(name:"bolt.fill",size:18);Text(L("直接解析")).font(.subheadline.bold())}.padding(12).background(OmniPalette.primaryContainer,in:Capsule())}.buttonStyle(OmniPressStyle())}.padding(14).omniCard(radius:24)
                    }
                    let active=downloadManager.tasks.filter{[.pending,.downloading,.processing].contains($0.status)}
                    if !active.isEmpty{HStack{Text(L("进行中")+" (\(active.count))").font(.headline);Spacer()};ForEach(active.prefix(2)){task in TaskCard(task:task)}}
                    Spacer(minLength:24)
                }.padding(.horizontal,20).padding(.top,8)
            }
        }
        .sheet(isPresented:$showFormats,onDismiss:{if pendingBrowser{pendingBrowser=false;validate()}}){if let metadata=metadata{FormatSelectorSheet(metadata:metadata,onMoreQuality:{pendingBrowser=true;showFormats=false})}}
        .sheet(isPresented:$browser.visible,onDismiss:{
            analysis?.cancel();analyzing=false;browser.close()
            if pendingFormats{pendingFormats=false;showFormats=true}
            else if retryAfterLogin{retryAfterLogin=false;start(input)}
        }){PlatformBrowserSheet(browser:browser,login:bilibiliLogin,onDone:{browser.visible=false},onSaved:{if bilibiliLogin{retryAfterLogin=true}})}
        .sheet(isPresented:$showYouTube,onDismiss:{if let value=pendingYouTube{pendingYouTube=nil;Task{await accept(.video(value))}}}){YouTubeBrowserSheet(url:youtubeURL){pendingYouTube=$0}}
        .sheet(item:$activeRelay){RelayBrowserSheet(site:$0,initialVideoUrl:input)}
        .onReceive(NotificationCenter.default.publisher(for:.omniAnalyzeURL)){if let url=$0.object as? String{input=url;start(url)}}
        .onChange(of:scenePhase){if $0 == .background{cancel()}}
        .onDisappear{if !browser.visible{cancel()}}
    }
    private func paste(){if let text=UIPasteboard.general.string{input=text}}
    private func cancel(){generation=UUID();analysis?.cancel();analysis=nil;analyzing=false;stage="";browser.close()}
    private func start(_ raw:String){
        cancel();error="";let epoch=generation
        let clean=raw.trimmingCharacters(in:.whitespacesAndNewlines)
        let value=clean.range(of:#"^BV[a-zA-Z0-9]{10}$"#,options:.regularExpression) != nil ? "https://www.bilibili.com/video/"+clean:(UrlSniffer.extractFirstUrl(from:clean) ?? clean)
        guard NativeMediaParser.safeURL(value) else{error=L("请输入有效链接");return}
        input=value;analyzing=true;stage=L("正在读取视频信息…")
        analysis=Task{
            defer{if generation==epoch{analyzing=false;stage=""}}
            do{
                let url=await UrlSniffer.resolveRedirects(for:value);try Task.checkCancellation();guard generation==epoch else{return};input=url
                if OmniPlatform.detect(url)=="YouTube"{youtubeURL=url;showYouTube=true;return}
                let result:CapturedTarget
                do{
                    var native=try await MediaResolver.analyze(url)
                    if native.siteName=="抖音",DouyinQuality.needsPage(native){
                        stage=L("正在自动补全画质…")
                        do{if case .video(let page)=try await browser.capture(url){native=try DouyinQuality.merge(native,page)}}
                        catch is CancellationError{throw CancellationError()}
                        catch{native.qualityWarning="已保留当前可用源；更高画质尚未确认，可在浏览器验证后重试。"}
                    }
                    result = .video(native)
                }
                catch MediaRouting.images(let post){result = .images(post)}
                catch is CancellationError{throw CancellationError()}
                catch{
                    let message=error.localizedDescription
                    if ["NO_MEDIA","MEDIA_ID_MISMATCH","RATE_LIMITED","ACCESS_DENIED","ACCESS_UNCONFIRMED","DRM","PREVIEW_ONLY"].contains(where:message.contains) || OmniPlatform.detect(url)=="哔哩哔哩"{throw error}
                    stage=L("正在自动补全画质…");result=try await browser.capture(url)
                }
                guard generation==epoch,!Task.isCancelled else{return};await accept(result)
            }catch is CancellationError{}
            catch{if generation==epoch{self.error=error.localizedDescription}}
        }
    }
    private func accept(_ result:CapturedTarget)async{
        switch result{
        case .video(let original):
            stage=L("正在核对视频尺寸…")
            let value:VideoMetadata
            do{value=try await NativeMP4Probe.confirm(original)}catch{return}
            guard !Task.isCancelled else{return}
            metadata=value;MediaSources.shared.remember(value)
            let present = !downloadManager.acceptRefreshed(value)
            if browser.visible{pendingFormats=present;browser.visible=false}
            else if present{showFormats=true}
        case .images(let post):
            if browser.visible{browser.visible=false}
            NotificationCenter.default.post(name:.omniShowImages,object:post)
        }
    }
    private func validate(){
        if OmniPlatform.detect(input)=="哔哩哔哩"{
            analysis?.cancel();bilibiliLogin=true;retryAfterLogin=false
            analysis=Task{do{try await browser.prepare(input,login:true);browser.present()}catch{self.error=error.localizedDescription}}
            return
        }
        bilibiliLogin=false
        analysis?.cancel();error="";browser.present();analyzing=true;stage=L("等待平台验证…")
        analysis=Task{
            defer{analyzing=false;stage=""}
            do{let value=try await browser.capture(input,reuse:true,budget:120);await accept(value)}
            catch is CancellationError{}
            catch{browser.notice=error.localizedDescription;self.error=error.localizedDescription}
        }
    }
}


struct RelayBrowserSheet: View {
    let site: RelaySite
    let initialVideoUrl: String
    @Environment(\.presentationMode) private var presentationMode
    @EnvironmentObject var downloadManager: DownloadManager

    @State private var sniffedMediaList: [SniffedMediaItem] = []
    @State private var showSnifferSheet: Bool = false
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

                // 底部浮动高亮胶囊：提示已捕获数量，支持查看列表与下载最新资源
                if !sniffedMediaList.isEmpty {
                    VStack(spacing: 8) {
                        HStack(spacing: 10) {
                            ZStack {
                                Circle()
                                    .fill(OmniPalette.primary)
                                    .frame(width: 32, height: 32)
                                Image(systemName: "bolt.fill")
                                    .foregroundColor(OmniPalette.onPrimary)
                                    .font(.system(size: 15))
                            }

                            VStack(alignment: .leading, spacing: 2) {
                                Text("🐱 嗅探猫已捕获 \(sniffedMediaList.count) 个媒体")
                                    .font(.system(size: 13, weight: .bold))
                                    .foregroundColor(.primary)
                                if let first = sniffedMediaList.first {
                                    Text("最新: [\(first.format)] \(first.host.isEmpty ? "直链 CDN" : first.host)")
                                        .font(.system(size: 11))
                                        .foregroundColor(.secondary)
                                        .lineLimit(1)
                                }
                            }

                            Spacer()

                            Button(action: { showSnifferSheet = true }) {
                                Text("列表")
                                    .font(.system(size: 12, weight: .semibold))
                                    .foregroundColor(OmniPalette.primary)
                                    .padding(.horizontal, 10)
                                    .padding(.vertical, 6)
                                    .background(OmniPalette.primary.opacity(0.12))
                                    .cornerRadius(8)
                            }

                            Button(action: startDownloadLatest) {
                                Text("下载最新")
                                    .font(.system(size: 12, weight: .bold))
                                    .foregroundColor(OmniPalette.onPrimary)
                                    .padding(.horizontal, 10)
                                    .padding(.vertical, 6)
                                    .background(OmniPalette.primary)
                                    .cornerRadius(8)
                            }
                        }
                        .padding(12)
                        .background(OmniPalette.surface)
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
                    HStack(spacing: 8) {
                        Button(action: { showSnifferSheet = true }) {
                            HStack(spacing: 4) {
                                Text("🐱 \(sniffedMediaList.count)")
                                    .font(.system(size: 12, weight: .bold))
                            }
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .background(sniffedMediaList.isEmpty ? Color.gray.opacity(0.15) : OmniPalette.primary.opacity(0.15))
                            .foregroundColor(sniffedMediaList.isEmpty ? .secondary : .blue)
                            .cornerRadius(12)
                        }

                        Button(action: { webView?.reload() }) {
                            Image(systemName: "arrow.clockwise")
                        }
                    }
                }
            }
            .sheet(isPresented: $showSnifferSheet) {
                SnifferManagerSheet(
                    items: $sniffedMediaList,
                    onDownloadItem: { item in
                        startDownload(item: item)
                    },
                    onTriggerDeepSearch: {
                        triggerDeepSearch()
                    }
                )
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

    private func detectMediaFormat(url: String) -> String {
        let lower = url.lowercased()
        if lower.contains(".m3u8") { return "M3U8" }
        if lower.contains(".mp4") { return "MP4" }
        if lower.contains(".m4a") || lower.contains(".mp3") || lower.contains(".aac") || lower.contains(".flac") { return "音频" }
        if lower.contains(".webm") { return "WebM" }
        if lower.contains(".flv") { return "FLV" }
        return "视频"
    }

    private func handleSniffedMedia(url: String, title: String) {
        let (unpackedUrl, unpackedTitle) = UrlSniffer.unpackDirectMediaUrl(rawUrl: url, defaultTitle: title.isEmpty ? "\(site.name) 视频" : title)
        guard UrlSniffer.isDirectMediaUrl(urlString: unpackedUrl) else { return }

        let host = URL(string: unpackedUrl)?.host ?? ""
        let format = detectMediaFormat(url: unpackedUrl)

        DispatchQueue.main.async {
            if !self.sniffedMediaList.contains(where: { $0.url.lowercased() == unpackedUrl.lowercased() }) {
                let newItem = SniffedMediaItem(
                    url: unpackedUrl,
                    title: unpackedTitle,
                    format: format,
                    host: host
                )
                withAnimation {
                    self.sniffedMediaList.insert(newItem, at: 0)
                }
            }
        }
    }

    private func startDownloadLatest() {
        guard let first = sniffedMediaList.first else { return }
        startDownload(item: first)
    }

    private func startDownload(item: SniffedMediaItem) {
        var origin=URLComponents(string:site.url)
        origin?.path="/";origin?.query=nil;origin?.fragment=nil;origin?.user=nil;origin?.password=nil
        let title=item.title.isEmpty ? "\(site.name) 视频":item.title
        let format=FormatOption(formatId:item.url,resolutionLabel:"浏览器确认源（尺寸未知）",ext:item.format.lowercased(),sourceUrl:item.url,sourceHeaders:["Referer":origin?.string ?? ""])
        var metadata=VideoMetadata(url:item.url,title:title,siteName:OmniPlatform.detect(item.url),availableVideoFormats:[format])
        metadata.sourceScope=PlatformSession.shared.generation(metadata.siteName)
        metadata.qualityEvidence="用户确认的浏览器媒体源，下载后核对实际尺寸。"
        MediaSources.shared.remember(metadata)
        let task = DownloadTask(
            url: item.url,
            title: title,
            downloadType: item.format=="音频" ? .audioOnly:.videoWithAudio,
            selectedResolution: format.resolutionLabel,
            selectedVideoFormatId: format.formatId,
            sourceReferer: origin?.string
        )
        downloadManager.addTask(task)
        showSnifferSheet = false
        presentationMode.wrappedValue.dismiss()
    }

    private func triggerDeepSearch() {
        webView?.evaluateJavaScript("if (window.__omniDeepSearch) { window.__omniDeepSearch(); } else { 0; }", completionHandler: nil)
    }
}

/// 🐱 嗅探猫 · 媒体资源管理器弹窗面板
struct SnifferManagerSheet: View {
    @Binding var items: [SniffedMediaItem]
    let onDownloadItem: (SniffedMediaItem) -> Void
    let onTriggerDeepSearch: () -> Void
    @Environment(\.presentationMode) private var presentationMode
    @State private var copiedNotice: Bool = false

    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                // 顶部工具控制栏
                HStack(spacing: 12) {
                    Button(action: {
                        onTriggerDeepSearch()
                    }) {
                        HStack(spacing: 4) {
                            Image(systemName: "magnifyingglass")
                            Text("深度搜索")
                        }
                        .font(.system(size: 13, weight: .medium))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(OmniPalette.primary.opacity(0.12))
                        .foregroundColor(OmniPalette.primary)
                        .cornerRadius(8)
                    }

                    Button(action: copyAllUrls) {
                        HStack(spacing: 4) {
                            Image(systemName: "doc.on.doc")
                            Text("复制全部")
                        }
                        .font(.system(size: 13, weight: .medium))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(Color.gray.opacity(0.12))
                        .foregroundColor(.primary)
                        .cornerRadius(8)
                    }

                    Spacer()

                    Button(action: { items.removeAll() }) {
                        Image(systemName: "trash")
                            .font(.system(size: 14))
                            .foregroundColor(OmniPalette.error)
                    }
                }
                .padding(14)
                .background(OmniPalette.surface)

                Divider()

                if items.isEmpty {
                    VStack(spacing: 12) {
                        Spacer()
                        Image(systemName: "waveform.badge.magnifyingglass")
                            .font(.system(size: 40))
                            .foregroundColor(.secondary)
                        Text("暂未嗅探到媒体流")
                            .font(.system(size: 15, weight: .medium))
                            .foregroundColor(.primary)
                        Text("播放视频或点击上方「深度搜索」主动挖掘隐藏媒体")
                            .font(.system(size: 12))
                            .foregroundColor(.secondary)
                        Spacer()
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    List {
                        ForEach(items) { item in
                            HStack(spacing: 10) {
                                // 格式彩标
                                Text(item.format)
                                    .font(.system(size: 11, weight: .bold))
                                    .foregroundColor(OmniPalette.onPrimary)
                                    .padding(.horizontal, 6)
                                    .padding(.vertical, 3)
                                    .background(badgeColor(for: item.format))
                                    .cornerRadius(6)

                                VStack(alignment: .leading, spacing: 2) {
                                    Text(item.title)
                                        .font(.system(size: 13, weight: .semibold))
                                        .lineLimit(1)
                                    Text("来源: \(item.host.isEmpty ? "直链 CDN" : item.host)")
                                        .font(.system(size: 11))
                                        .foregroundColor(.secondary)
                                    Text(item.url)
                                        .font(.system(size: 10))
                                        .foregroundColor(.blue.opacity(0.8))
                                        .lineLimit(1)
                                }

                                Spacer()

                                Button(action: {
                                    UIPasteboard.general.string = item.url
                                }) {
                                    Image(systemName: "doc.on.doc")
                                        .font(.system(size: 13))
                                        .foregroundColor(.secondary)
                                        .padding(6)
                                }
                                .buttonStyle(BorderlessButtonStyle())

                                Button(action: {
                                    onDownloadItem(item)
                                }) {
                                    Text("下载")
                                        .font(.system(size: 12, weight: .bold))
                                        .foregroundColor(OmniPalette.onPrimary)
                                        .padding(.horizontal, 10)
                                        .padding(.vertical, 5)
                                        .background(OmniPalette.primary)
                                        .cornerRadius(6)
                                }
                                .buttonStyle(BorderlessButtonStyle())
                            }
                            .padding(.vertical, 2)
                        }
                    }
                    .listStyle(PlainListStyle())
                }
            }
            .navigationTitle("🐱 嗅探猫 (\(items.count))")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") {
                        presentationMode.wrappedValue.dismiss()
                    }
                }
            }
        }
    }

    private func copyAllUrls() {
        guard !items.isEmpty else { return }
        UIPasteboard.general.string = items.map { $0.url }.joined(separator: "\n")
    }

    private func badgeColor(for format: String) -> Color {
        switch format {
        case "M3U8": return .orange
        case "MP4": return .blue
        case "音频": return .green
        case "WebM": return .purple
        default: return .gray
        }
    }
}

/// 基于 WKWebView 的媒体嗅探桥接 (全面注入嗅探猫 Fetch/XHR/MediaElement 钩子脚本)
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
            injectCatCatchSniffScript(webView)
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

        private func injectCatCatchSniffScript(_ webView: WKWebView) {
            let js = #"""
            (function() {
                if (window.__omniCatCatchInjected) return;
                window.__omniCatCatchInjected = true;

                function report(url, title) {
                    if (url && typeof url === 'string' && url.startsWith('http')) {
                        var lower = url.toLowerCase();
                        var isMedia = lower.includes('.mp4') || lower.includes('.m3u8') ||
                                      lower.includes('.m4a') || lower.includes('.mp3') ||
                                      lower.includes('.webm') || lower.includes('.flv') ||
                                      lower.includes('dl.snapcdn.app') || lower.includes('video.twimg.com') ||
                                      lower.includes('googlevideo.com/videoplayback') || lower.includes('byteoversea.com') ||
                                      lower.includes('ibytedtos.com') || lower.includes('tiktokcdn.com') ||
                                      lower.includes('douyinvod.com') || lower.includes('snssdk.com') ||
                                      lower.includes('yximgs.com') || lower.includes('xhscdn.com');
                        var isApi = lower.includes('/api/') || lower.includes('/ajax/') || lower.includes('/extract/') || lower.includes('cnsimpleextract');
                        if (isMedia && !isApi) {
                            window.webkit.messageHandlers.OmniSniffer.postMessage({ url: url, title: title || document.title || '' });
                        }
                    }
                }

                // 1. Hook Fetch
                try {
                    var origFetch = window.fetch;
                    if (origFetch) {
                        window.fetch = function() {
                            try {
                                var reqUrl = typeof arguments[0] === 'string' ? arguments[0] : (arguments[0] && arguments[0].url ? arguments[0].url : '');
                                if (reqUrl) report(reqUrl);
                            } catch(e) {}
                            return origFetch.apply(this, arguments);
                        };
                    }
                } catch(e) {}

                // 2. Hook XMLHttpRequest
                try {
                    var origOpen = XMLHttpRequest.prototype.open;
                    XMLHttpRequest.prototype.open = function(method, url) {
                        try {
                            if (url && typeof url === 'string') report(url);
                        } catch(e) {}
                        return origOpen.apply(this, arguments);
                    };
                } catch(e) {}

                // 3. Hook HTMLMediaElement
                try {
                    var origPlay = HTMLMediaElement.prototype.play;
                    HTMLMediaElement.prototype.play = function() {
                        try {
                            var src = this.currentSrc || this.src;
                            if (src) report(src);
                        } catch(e) {}
                        return origPlay.apply(this, arguments);
                    };
                } catch(e) {}

                // 4. 定时扫描 DOM
                function scanDOM() {
                    try {
                        var videos = document.querySelectorAll('video, audio, source');
                        for (var i = 0; i < videos.length; i++) {
                            var src = videos[i].src || videos[i].getAttribute('src') || videos[i].getAttribute('data-src') || '';
                            if (src) report(src);
                        }
                    } catch(e) {}
                }
                scanDOM();
                setInterval(scanDOM, 1500);

                // 5. 挂载深度搜索函数
                window.__omniDeepSearch = function() {
                    scanDOM();
                    var count = 0;
                    try {
                        var html = document.documentElement.innerHTML;
                        var regex = /(https?:\/\/[^"'\s\\]+?\.(mp4|m3u8|m4a|mp3|flv|webm)(\?[^"'\s\\]*)?)/gi;
                        var match;
                        while ((match = regex.exec(html)) !== null) {
                            report(match[1]);
                            count++;
                        }
                    } catch(e) {}
                    return count;
                };
            })();
            """#
            webView.evaluateJavaScript(js, completionHandler: nil)
        }
    }
}
