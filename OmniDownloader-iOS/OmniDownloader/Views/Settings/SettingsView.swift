import SwiftUI
import Photos
import UniformTypeIdentifiers
import ffmpegkit

@MainActor struct SettingsView:View{
    @EnvironmentObject private var manager:DownloadManager
    @AppStorage("com.omni.downloader.appearance") private var appearance="system"
    @AppStorage("omni.language") private var language="system"
    @AppStorage("omni.concurrent") private var concurrent=3
    @ObservedObject private var preferences=CookieStore.shared
    @ObservedObject private var sessions=PlatformSession.shared
    @StateObject private var browser=PlatformBrowser()
    @State private var page:Page?
    @State private var selectedSite="YouTube"
    @State private var count=0
    @State private var importing=false
    @State private var choosingFolder=false
    @State private var folder=""
    @State private var proxy=UserDefaults.standard.string(forKey:"omni.proxy") ?? ""
    @State private var message=""
    @State private var cacheSize=""
    @State private var addingRelay=false
    @State private var clearingAccount=false
    @State private var clearingHistory=false
    @State private var engineInfo=""
    @State private var loginTask:Task<Void,Never>?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    private enum Page:String,CaseIterable,Identifiable{
        case appearance,downloads,proxy,relay,accounts,engine,about
        var id:String{rawValue}
        var title:String{switch self{case .appearance:return "外观与语言";case .downloads:return "下载与存储";case .proxy:return "网络代理";case .relay:return "中转站";case .accounts:return "账号";case .engine:return "下载引擎";case .about:return "关于"}}
        var summary:String{switch self{case .appearance:return "主题模式、界面语言";case .downloads:return "保存目录、缓存与数据清理";case .proxy:return "HTTP 与 SOCKS 代理连接";case .relay:return "添加、管理和删除解析站点";case .accounts:return "Cookie 与登录管理";case .engine:return "运行状态与组件更新";case .about:return "应用版本与支持平台"}}
        var icon:String{switch self{case .appearance:return "paintpalette";case .downloads:return "folder";case .proxy:return "globe";case .relay:return "server.rack";case .accounts:return "person.crop.circle";case .engine:return "icloud.and.arrow.down";case .about:return "info.circle"}}
    }
    private func L(_ text:String)->String{OmniLocalization.text(text,language:language)}
    private var active:Bool{manager.tasks.contains{[.pending,.downloading,.processing].contains($0.status)}}
    var body:some View{
        ScrollView{
            VStack(alignment:.leading,spacing:18){
                OmniPageHeader(title:L(page?.title ?? "偏好设置"),back:page==nil ? nil:{withAnimation(reduceMotion ? nil:.easeInOut(duration:0.2)){page=nil;message=""}})
                if let page=page{
                    VStack(alignment:.leading,spacing:20){child(page)}.frame(maxWidth:.infinity,alignment:.leading).padding(20).omniCard(radius:24).transition(.opacity)
                }else{
                    VStack(spacing:4){ForEach(Page.allCases){item in
                        Button{withAnimation(reduceMotion ? nil:.easeInOut(duration:0.2)){page=item;message=""};if item == .downloads{loadStorage()};if item == .accounts{reloadSession()};if item == .engine{inspectEngine()}}label:{
                            HStack(spacing:22){OmniIcon(name:item.icon,size:24).foregroundColor(OmniPalette.primary).frame(width:30);VStack(alignment:.leading,spacing:5){Text(L(item.title)).font(.system(size:18));Text(L(item.summary)).font(.subheadline).foregroundColor(OmniPalette.secondary)};Spacer();OmniIcon(name:"chevron.right",size:20).foregroundColor(OmniPalette.secondary)}.padding(.vertical,21).contentShape(Rectangle())
                        }.buttonStyle(OmniPressStyle()).accessibilityIdentifier("omni.settings."+item.rawValue)
                    }}
                }
                if !message.isEmpty{Text(message).font(.subheadline).foregroundColor(OmniPalette.secondary).padding(.vertical,8)}
            }.padding(.horizontal,24).padding(.top,8).padding(.bottom,24)
        }.background(OmniPalette.background).foregroundColor(OmniPalette.onSurface).tint(OmniPalette.primary)
        .onChange(of:selectedSite){_ in reloadSession()}.onChange(of:sessions.revision){_ in reloadSession()}
        .fileImporter(isPresented:$importing,allowedContentTypes:[.plainText,.text,.data]){result in do{try sessions.importFile(result.get(),site:selectedSite);reloadSession();message=L("会话已保存，请重新解析原链接")}catch{message=error.localizedDescription}}
        .fileImporter(isPresented:$choosingFolder,allowedContentTypes:[.folder]){result in
            do{let url=try result.get(),access=url.startAccessingSecurityScopedResource();defer{if access{url.stopAccessingSecurityScopedResource()}};guard access else{throw OmniPlatform.error("EXPORT_FAILED","无法访问保存目录")};let bookmark=try url.bookmarkData(options:[],includingResourceValuesForKeys:nil,relativeTo:nil);UserDefaults.standard.set(bookmark,forKey:"omni.folderBookmark");folder=url.lastPathComponent;message=L("保存目录已更新")}
            catch{message=error.localizedDescription}
        }
        .sheet(isPresented:$browser.visible,onDismiss:{loginTask?.cancel();browser.close();reloadSession()}){PlatformBrowserSheet(browser:browser,login:true,onDone:{browser.visible=false})}
        .sheet(isPresented:$addingRelay){AddRelaySiteSheet()}
        .confirmationDialog(L("清除所选平台的登录状态？"),isPresented:$clearingAccount,titleVisibility:.visible){Button(L("清除会话"),role:.destructive){sessions.clear(selectedSite);reloadSession()};Button(L("取消"),role:.cancel){}}
        .confirmationDialog(L("清空记录会保留已下载的文件"),isPresented:$clearingHistory,titleVisibility:.visible){Button(L("清空已完成记录"),role:.destructive){manager.clearCompletedTasks()};Button(L("清空所有记录"),role:.destructive){manager.clearHistory()};Button(L("取消"),role:.cancel){}}
    }
    @ViewBuilder private func child(_ item:Page)->some View{
        switch item{
        case .appearance:
            Text(L("主题模式")).font(.headline)
            Picker(L("主题模式"),selection:$appearance){Text(L("跟随系统")).tag("system");Text(L("浅色")).tag("light");Text(L("深色")).tag("dark")}.pickerStyle(.segmented)
            Text(L("界面语言")).font(.headline)
            Picker(L("界面语言"),selection:$language){Text(L("跟随系统")).tag("system");Text("简体中文").tag("zh-CN");Text("繁體中文").tag("zh-TW");Text("English").tag("en");Text("日本語").tag("ja")}.pickerStyle(.menu)
        case .downloads:
            Text(L("保存目录")).font(.headline);Text(folder.isEmpty ? "文件 → OmniDownloader → Downloads":folder).font(.subheadline).foregroundColor(OmniPalette.secondary)
            HStack{Button(L("选择保存目录")){choosingFolder=true};Spacer();Button(L("恢复默认")){UserDefaults.standard.removeObject(forKey:"omni.folderBookmark");folder=""}}
            Divider();Toggle(L("自动保存到相册"),isOn:$preferences.autoSaveToAlbum)
            Text(L("文件先保存到目录；相册权限不足时仍保留文件。")).font(.caption).foregroundColor(OmniPalette.secondary)
            Picker(L("最大同时下载任务数"),selection:$concurrent){ForEach([1,2,3,5],id:\.self){Text("\($0)").tag($0)}}
            Divider();HStack{Text(L("缓存"));Spacer();Text(cacheSize).foregroundColor(OmniPalette.secondary)}
            Button(L("清理缓存")){clearCache()}.disabled(active)
            Button(L("清空下载记录"),role:.destructive){clearingHistory=true}.disabled(active)
        case .proxy:
            TextField("http://127.0.0.1:7890",text:$proxy).textInputAutocapitalization(.never).disableAutocorrection(true).keyboardType(.URL).padding(14).background(OmniPalette.surfaceVariant.opacity(0.35),in:RoundedRectangle(cornerRadius:15))
            Text(L("HTTP、HTTPS 与 SOCKS5；应用内代理需要 iOS 17 及以上。留空使用系统网络设置。")).font(.caption).foregroundColor(OmniPalette.secondary)
            OmniAction(title:L("保存并应用"),icon:"checkmark",prominent:true){
                let old=UserDefaults.standard.string(forKey:"omni.proxy") ?? "";UserDefaults.standard.set(proxy.trimmingCharacters(in:.whitespacesAndNewlines),forKey:"omni.proxy")
                do{_ = try OmniNetwork.configuration();message=L("代理设置已保存，请重新解析原链接")}catch{UserDefaults.standard.set(old,forKey:"omni.proxy");message=error.localizedDescription}
            }
        case .relay:
            if preferences.relaySites.isEmpty{Text(L("暂未配置中转站")).foregroundColor(OmniPalette.secondary)}
            ForEach(preferences.relaySites){site in HStack{VStack(alignment:.leading,spacing:4){Text(site.name).font(.headline);Text(site.url).font(.caption).foregroundColor(OmniPalette.secondary).lineLimit(1)};Spacer();Button(role:.destructive){preferences.removeRelaySite(id:site.id)}label:{Image(systemName:"trash").frame(width:44,height:44)}.accessibilityLabel(L("删除")+site.name)}}
            OmniAction(title:L("添加中转网站"),icon:"plus",prominent:true){addingRelay=true}
        case .accounts:
            Text(L("Cookie 与登录管理")).font(.headline)
            Picker(L("平台"),selection:$selectedSite){ForEach(OmniPlatform.all,id:\.self){Text($0).tag($0)}}.pickerStyle(.menu)
            Toggle(L("禁用 Cookie"),isOn:Binding(get:{!sessions.enabled(selectedSite)},set:{sessions.setEnabled(selectedSite,!$0)}))
            Text(count>0 ? L("已保存")+" \(count) "+L("项 Cookie"):L("尚未导入会话")).font(.subheadline).foregroundColor(OmniPalette.secondary)
            Text(L("禁用后新请求不使用会话，已保存会话继续保留。登录状态保存在本机钥匙串。")).font(.caption).foregroundColor(OmniPalette.secondary)
            OmniAction(title:L("网页登录"),icon:"person.crop.circle",prominent:true,disabled:!sessions.enabled(selectedSite)){
                guard let home=OmniPlatform.homes[selectedSite] else{return};browser.visible=true;loginTask=Task{do{try await browser.prepare(home,login:true);browser.present()}catch{browser.notice=error.localizedDescription}}
            }
            HStack{Button(L("导入 Cookies 文件")){importing=true};Spacer();Button(L("清除会话"),role:.destructive){clearingAccount=true}}
        case .engine:
            Text("FFmpeg · FFprobe · WebKit · URLSession").font(.headline)
            Text(engineInfo.isEmpty ? L("正在检查组件…"):engineInfo).font(.caption.monospaced()).textSelection(.enabled)
            Button(L("重新检查")){inspectEngine()}
            Text(L("媒体组件随应用版本更新；不会下载并执行外部程序。")).font(.caption).foregroundColor(OmniPalette.secondary)
            Link(L("检查应用更新"),destination:URL(string:"https://github.com/baishu136/OmniDownloader/releases")!)
        case .about:
            HStack{Text("OmniDownloader").font(.title2.bold());Spacer();Text("1.7.20").foregroundColor(OmniPalette.primary)}
            Text(L("支持平台")).font(.headline);OmniPlatformChips()
            Text("SwiftUI · WebKit · URLSession · FFmpegKit").font(.caption).foregroundColor(OmniPalette.secondary)
            Link("GitHub",destination:URL(string:"https://github.com/baishu136/OmniDownloader")!)
            Link(L("第三方组件与许可证"),destination:URL(string:"https://github.com/jaywcjlove/ffmpeg-kit/tree/0bd85df8f9693061f6f979414662baaf876a3e20")!)
        }
    }
    private func reloadSession(){count=sessions.count(selectedSite)}
    private func loadStorage(){
        if let bookmark=UserDefaults.standard.data(forKey:"omni.folderBookmark"){var stale=false;folder=(try? URL(resolvingBookmarkData:bookmark,options:[],relativeTo:nil,bookmarkDataIsStale:&stale).lastPathComponent) ?? ""}
        let directory=FileManager.default.urls(for:.cachesDirectory,in:.userDomainMask)[0].appendingPathComponent("omni-transfers")
        Task.detached{let files=FileManager.default.enumerator(at:directory,includingPropertiesForKeys:[.fileSizeKey]);var size:Int64=0
            while let file=files?.nextObject() as? URL{size += Int64((try? file.resourceValues(forKeys:[.fileSizeKey]).fileSize) ?? 0)}
            let label=ByteCountFormatter.string(fromByteCount:size,countStyle:.file);await MainActor.run{cacheSize=label}
        }
    }
    private func clearCache(){guard !active else{return};let directory=FileManager.default.urls(for:.cachesDirectory,in:.userDomainMask)[0].appendingPathComponent("omni-transfers");try? FileManager.default.removeItem(at:directory);loadStorage();message=L("缓存已清理")}
    private func inspectEngine(){Task.detached{let result=FFmpegKit.executeWithArguments(["-version"]);let line=result.getOutput().components(separatedBy:.newlines).first ?? "FFmpeg";await MainActor.run{engineInfo=line}}}
}


struct AddRelaySiteSheet: View {
    @Environment(\.presentationMode) private var presentationMode
    @State private var siteUrl: String = ""
    @State private var siteName: String = ""
    @State private var isFetchingTitle: Bool = false
    @State private var isNameManuallyEdited: Bool = false
    @State private var lastFetchedTitle: String = ""
    @State private var fetchWorkItem: DispatchWorkItem?

    var body: some View {
        NavigationView {
            Form {
                Section(
                    header: Text("输入网址后将自动检索网站名称，也可手动修改："),
                    footer: Text("添加后可在首页便捷调用备用中转服务提取直链。")
                ) {
                    // 1. 网址输入框 (置顶)
                    VStack(alignment: .leading, spacing: 6) {
                        HStack {
                            Text("网站完整网址")
                                .font(.system(size: 13, weight: .bold))
                                .foregroundColor(.primary)
                            Spacer()
                            if isFetchingTitle {
                                HStack(spacing: 4) {
                                    ProgressView()
                                        .scaleEffect(0.7)
                                    Text("正在检索名称...")
                                        .font(.system(size: 11))
                                        .foregroundColor(OmniPalette.primary)
                                }
                            }
                        }

                        HStack {
                            Image(systemName: "link")
                                .foregroundColor(.secondary)
                            TextField("https://...", text: $siteUrl)
                                .autocapitalization(.none)
                                .disableAutocorrection(true)
                                .keyboardType(.URL)
                                .onChange(of: siteUrl) { newValue in
                                    handleUrlChanged(newValue)
                                }

                            if !siteUrl.isEmpty {
                                Button(action: triggerManualFetch) {
                                    Image(systemName: "arrow.clockwise")
                                        .foregroundColor(OmniPalette.primary)
                                        .font(.system(size: 13))
                                }
                                .buttonStyle(BorderlessButtonStyle())

                                Button(action: clearUrl) {
                                    Image(systemName: "xmark.circle.fill")
                                        .foregroundColor(.secondary)
                                        .font(.system(size: 14))
                                }
                                .buttonStyle(BorderlessButtonStyle())
                            }
                        }
                    }
                    .padding(.vertical, 4)

                    // 2. 网站名称输入框 (第二项)
                    VStack(alignment: .leading, spacing: 6) {
                        Text("网站名称")
                            .font(.system(size: 13, weight: .bold))
                            .foregroundColor(.primary)

                        HStack {
                            Image(systemName: "globe")
                                .foregroundColor(.secondary)
                            TextField("例如：备用解析工具", text: $siteName)
                                .onChange(of: siteName) { newValue in
                                    if !newValue.isEmpty && newValue != lastFetchedTitle {
                                        isNameManuallyEdited = true
                                    }
                                }
                        }
                    }
                    .padding(.vertical, 4)
                }
            }
            .navigationTitle("添加备用中转网站")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") {
                        presentationMode.wrappedValue.dismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("确认添加") {
                        submitAdd()
                    }
                    .disabled(siteUrl.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || siteName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }

    private func handleUrlChanged(_ text: String) {
        fetchWorkItem?.cancel()
        let clean = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard clean.count >= 5 && clean.contains(".") else { return }

        let workItem = DispatchWorkItem {
            Task {
                await executeFetchTitle(url: clean)
            }
        }
        self.fetchWorkItem = workItem
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.7, execute: workItem)
    }

    private func triggerManualFetch() {
        let clean = siteUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return }
        Task {
            await executeFetchTitle(url: clean)
        }
    }

    private func clearUrl() {
        siteUrl = ""
        if !isNameManuallyEdited {
            siteName = ""
        }
        fetchWorkItem?.cancel()
        isFetchingTitle = false
    }

    @MainActor
    private func executeFetchTitle(url: String) async {
        isFetchingTitle = true
        let title = await UrlSniffer.fetchWebTitle(from: url)
        isFetchingTitle = false

        if let title = title, !title.isEmpty {
            if !isNameManuallyEdited || siteName.isEmpty || siteName == lastFetchedTitle {
                self.siteName = title
                self.lastFetchedTitle = title
                self.isNameManuallyEdited = false
            }
        }
    }

    private func submitAdd() {
        let name = siteName.trimmingCharacters(in: .whitespacesAndNewlines)
        let url = siteUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty && !url.isEmpty else { return }

        CookieStore.shared.addRelaySite(name: name, url: url)
        presentationMode.wrappedValue.dismiss()
    }
}
