import SwiftUI
import UniformTypeIdentifiers

@MainActor struct BatchDownloadView:View {
    @EnvironmentObject private var manager:DownloadManager
    @Environment(\.scenePhase) private var phase
    @StateObject private var batch=BatchDownloads.shared
    @StateObject private var browser=PlatformBrowser()
    @State private var importing=false
    var body:some View {
        ZStack {
            OmniPalette.background.ignoresSafeArea()
            if browser.web != nil && !browser.visible{PlatformWebContent(browser:browser).frame(maxWidth:390,maxHeight:780).opacity(0).allowsHitTesting(false).accessibilityHidden(true)}
            ScrollView {
                VStack(alignment:.leading,spacing:18) {
                    OmniPageHeader(title:"批量下载")
                    Text("粘贴多条链接或导入 TXT，按顺序解析后加入现有下载队列。单次最多 100 条。").font(.caption).foregroundColor(OmniPalette.secondary)
                    VStack(spacing:12) {
                        TextEditor(text:$batch.draft).frame(minHeight:130).padding(8).accessibilityIdentifier("omni.batch.input").accessibilityLabel("批量链接").disabled(batch.running)
                        HStack {Button("粘贴剪贴板"){batch.draft=UIPasteboard.general.string ?? batch.draft};Spacer();Button("导入 TXT"){importing=true};Button("清空"){batch.clear()}}.disabled(batch.running)
                        Picker("下载类型",selection:$batch.mode){Text("自动识别").tag(DownloadType.auto);Text("视频").tag(DownloadType.videoWithAudio);Text("仅视频").tag(DownloadType.videoOnly);Text("音频").tag(DownloadType.audioOnly);Text("GIF").tag(DownloadType.gif);Text("封面").tag(DownloadType.cover);Text("图文 ZIP").tag(DownloadType.imageZip)}
                        Picker("画质上限",selection:$batch.maxHeight){Text("最高可用").tag(0);ForEach([2160,1440,1080,720,480],id:\.self){Text("\($0)p").tag($0)}}
                        if batch.mode == .audioOnly{Picker("音频格式",selection:$batch.audioFormat){ForEach(AudioFormat.allCases,id:\.self){Text($0.rawValue.uppercased()).tag($0)}}}
                        Toggle("下载全部分 P / 多媒体",isOn:$batch.allEpisodes)
                        Toggle("同时保存封面",isOn:$batch.saveCover)
                    }.padding(16).omniCard().disabled(batch.running)
                    HStack {
                        OmniAction(title:batch.running ? "停止添加":"开始批量下载",icon:batch.running ? "stop":"arrow.down.to.line",prominent:true){if batch.running{batch.cancel();browser.close()}else{start()}}
                        if batch.rows.contains(where:{$0.state=="失败"}){Button("重试失败项"){start(retry:true)}.disabled(batch.running)}
                    }
                    if !batch.notice.isEmpty{Text(batch.notice).font(.subheadline).foregroundColor(OmniPalette.secondary)}
                    if batch.running{HStack{ProgressView();Text("正在解析，已加入的任务会继续下载").font(.caption)}}
                    ForEach(batch.rows){row in
                        VStack(alignment:.leading,spacing:8) {
                            HStack{Text(row.state).font(.headline);Spacer();if row.count>0{Text("\(row.count) 个任务").font(.caption)}}
                            Text(row.url).font(.caption).lineLimit(2)
                            if !row.message.isEmpty{Text(row.message).font(.caption).foregroundColor(OmniPalette.error);Button("在首页验证此链接"){NotificationCenter.default.post(name:.omniAnalyzeURL,object:row.url)}}
                        }.padding(16).frame(maxWidth:.infinity,alignment:.leading).omniCard()
                    }
                }.padding(20)
            }
        }.tint(OmniPalette.primary)
        .fileImporter(isPresented:$importing,allowedContentTypes:[.plainText]){result in
            do {
                let url=try result.get(),access=url.startAccessingSecurityScopedResource();defer{if access{url.stopAccessingSecurityScopedResource()}}
                let size=try url.resourceValues(forKeys:[.fileSizeKey]).fileSize ?? 0
                guard size<=1_048_576 else{throw OmniPlatform.error("BATCH_LIMIT","TXT 最大 1 MiB")}
                let data=try Data(contentsOf:url)
                guard data.count<=1_048_576,let text=String(data:data,encoding:.utf8) ?? String(data:data,encoding:.utf16) else{throw OmniPlatform.error("INVALID_INPUT","请使用 UTF-8 或 UTF-16 文本")}
                batch.draft=text;batch.prepare()
            }catch{batch.notice=error.localizedDescription}
        }
        .onChange(of:batch.draft){_ in batch.save()}
        .onChange(of:phase){if $0 == .background{batch.cancel();browser.close()}}
        .onDisappear{batch.cancel();browser.close()}
    }
    private func start(retry:Bool=false) {
        batch.start(manager:manager,capture:{url in
            do{return .video(try await MediaResolver.analyze(url))}
            catch MediaRouting.images(let post){return .images(post)}
            catch is CancellationError{throw CancellationError()}
            catch {
                let message=error.localizedDescription
                if ["MEDIA_ID_MISMATCH","NO_MEDIA","RATE_LIMITED","ACCESS_DENIED","DRM","PREVIEW_ONLY"].contains(where:message.contains){throw error}
                if OmniPlatform.detect(url)=="YouTube"{throw OmniPlatform.error("BROWSER_ASSIST_REQUIRED","YouTube 需要在首页验证播放器；不会把未知媒体加入下载队列")}
                return try await browser.capture(url)
            }
        },retry:retry)
    }
}
