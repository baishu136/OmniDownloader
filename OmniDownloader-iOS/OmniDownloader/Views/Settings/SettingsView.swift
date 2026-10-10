import SwiftUI
import Photos

/// 设置视图：B站账号安全登录授权、自动存入系统相册配置、缓存清理与关于说明
struct SettingsView: View {
    @ObservedObject private var cookieStore = CookieStore.shared
    @State private var isLoggedIn: Bool = false
    @State private var sessdataMasked: String = ""
    @State private var autoSaveToPhotos: Bool = true
    @State private var showLoginSheet: Bool = false
    @State private var showClearCacheSuccess: Bool = false
    @State private var cacheSizeText: String = "0.0 MB"
    @State private var photoAuthStatusText: String = "已授权"
    @State private var showAddRelaySheet: Bool = false
    @State private var showRelayCompatAlert: Bool = false
    
    private let strings = AppStrings.current
    
    var body: some View {
        NavigationView {
            Form {
                // MARK: - 哔哩哔哩账号登录
                Section(header: Text(strings.bilibiliSection), footer: Text(strings.bilibiliLoginDesc)) {
                    if isLoggedIn {
                        HStack {
                            Image(systemName: "checkmark.seal.fill")
                                .foregroundColor(.green)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(strings.bilibiliLoggedIn)
                                    .font(.system(size: 15, weight: .semibold))
                                    .foregroundColor(.primary)
                                if !sessdataMasked.isEmpty {
                                    Text("凭证: \(sessdataMasked)")
                                        .font(.system(size: 12))
                                        .foregroundColor(.secondary)
                                }
                            }
                            
                            Spacer()
                            
                            Button(role: .destructive, action: logoutBilibili) {
                                Text(strings.bilibiliLogout)
                                    .font(.system(size: 14))
                            }
                        }
                    } else {
                        VStack(alignment: .leading, spacing: 10) {
                            HStack {
                                Image(systemName: "person.crop.circle.badge.exclamationmark")
                                    .foregroundColor(.orange)
                                    .font(.system(size: 20))
                                Text(strings.bilibiliNotLoggedIn)
                                    .font(.system(size: 14))
                                    .foregroundColor(.orange)
                            }
                            
                            Button(action: {
                                showLoginSheet = true
                            }) {
                                HStack {
                                    Spacer()
                                    Image(systemName: "lock.shield.fill")
                                    Text(strings.bilibiliAutoLoginBtn)
                                    Spacer()
                                }
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundColor(.white)
                                .padding(.vertical, 10)
                                .background(Color.orange)
                                .cornerRadius(8)
                            }
                        }
                        .padding(.vertical, 4)
                    }
                }
                
                // MARK: - 下载与相册设置
                Section(header: Text(strings.downloadSettingsSection), footer: Text(strings.autoSaveToAlbumDesc)) {
                    Toggle(isOn: $autoSaveToPhotos) {
                        HStack(spacing: 12) {
                            Image(systemName: "photo.on.rectangle.angled")
                                .foregroundColor(.blue)
                            Text(strings.autoSaveToAlbum)
                                .font(.system(size: 15))
                        }
                    }
                    .onChange(of: autoSaveToPhotos) { newValue in
                        CookieStore.shared.setAutoSaveToPhotos(newValue)
                    }
                    
                    HStack {
                        Text("系统相册写入权限")
                            .font(.system(size: 15))
                        Spacer()
                        Text(photoAuthStatusText)
                            .font(.system(size: 14))
                            .foregroundColor(.secondary)
                    }
                    
                    HStack {
                        Button(action: clearDiskCache) {
                            HStack(spacing: 12) {
                                Image(systemName: "trash")
                                    .foregroundColor(.red)
                                Text(strings.clearCache)
                                    .foregroundColor(.red)
                                    .font(.system(size: 15))
                            }
                        }
                        Spacer()
                        Text(cacheSizeText)
                            .font(.system(size: 14))
                            .foregroundColor(.secondary)
                    }
                }

                // MARK: - 第三方中转解析网站 (Relay Sites)
                Section(header: HStack {
                    Text("第三方中转解析网站")
                    Spacer()
                    Text("\(cookieStore.relaySites.count) 个")
                        .font(.system(size: 12))
                        .foregroundColor(.secondary)
                }, footer: Text("自定义添加备用解析站点，在直连受限或失效时使用。")) {
                    if cookieStore.relaySites.isEmpty {
                        Text("暂未配置任何备用中转网站")
                            .font(.system(size: 14))
                            .foregroundColor(.secondary)
                    } else {
                        ForEach(cookieStore.relaySites) { site in
                            HStack(spacing: 12) {
                                ZStack {
                                    RoundedRectangle(cornerRadius: 6)
                                        .fill(Color.blue.opacity(0.12))
                                        .frame(width: 28, height: 28)
                                    Text(String(site.name.prefix(1)).uppercased())
                                        .font(.system(size: 13, weight: .bold))
                                        .foregroundColor(.blue)
                                }
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(site.name)
                                        .font(.system(size: 14, weight: .semibold))
                                    Text(site.url)
                                        .font(.system(size: 11))
                                        .foregroundColor(.secondary)
                                        .lineLimit(1)
                                }
                                Spacer()
                            }
                        }
                        .onDelete { indexSet in
                            for index in indexSet {
                                let site = cookieStore.relaySites[index]
                                cookieStore.removeRelaySite(id: site.id)
                            }
                        }
                    }

                    Button(action: handleOpenAddRelaySite) {
                        HStack {
                            Image(systemName: "plus.circle.fill")
                                .foregroundColor(.blue)
                            Text("添加中转网站")
                                .foregroundColor(.blue)
                                .font(.system(size: 15, weight: .medium))
                        }
                    }
                }

                // MARK: - 关于与版本
                Section(header: Text(strings.about)) {
                    HStack {
                        Text("当前版本")
                        Spacer()
                        Text(strings.version)
                            .foregroundColor(.secondary)
                    }
                    
                    VStack(alignment: .leading, spacing: 6) {
                        Text("技术架构")
                            .font(.system(size: 15, weight: .medium))
                        Text(strings.openSourceInfo)
                            .font(.system(size: 13))
                            .foregroundColor(.secondary)
                            .lineSpacing(4)
                    }
                    .padding(.vertical, 4)
                }
            }
            .navigationTitle(strings.settingsTitle)
            .navigationBarTitleDisplayMode(.inline)
            .sheet(isPresented: $showLoginSheet) {
                BilibiliLoginSheet {
                    reloadLoginState()
                }
            }
            .sheet(isPresented: $showAddRelaySheet) {
                AddRelaySiteSheet()
            }
            .alert(isPresented: $showClearCacheSuccess) {
                Alert(
                    title: Text(strings.appName),
                    message: Text(strings.clearCacheSuccess),
                    dismissButton: .default(Text(strings.confirm))
                )
            }
            .alert("第三方网页使用须知与兼容性提示", isPresented: $showRelayCompatAlert) {
                Button("取消", role: .cancel) { }
                Button("我已知晓并继续") {
                    cookieStore.confirmRelayCompatTip()
                    showAddRelaySheet = true
                }
            } message: {
                Text("备用中转功能仅提供第三方网页快捷导航与直链下载支持，各站点交互、反爬机制及解析链路存在差异。若遇人机验证属于网页端行为。请勿输入个人隐私信息。")
            }
            .onAppear {
                reloadLoginState()
                checkPhotoPermission()
                calculateCacheSize()
            }
        }
        .navigationViewStyle(StackNavigationViewStyle())
    }

    private func handleOpenAddRelaySite() {
        if !cookieStore.hasShownRelayCompatTip {
            showRelayCompatAlert = true
        } else {
            showAddRelaySheet = true
        }
    }

    // MARK: - 刷新登录状态
    private func reloadLoginState() {
        self.isLoggedIn = CookieStore.shared.isLoggedIn()
        if let sess = CookieStore.shared.getBilibiliSessData(), !sess.isEmpty {
            if sess.count > 8 {
                self.sessdataMasked = "\(sess.prefix(4))****\(sess.suffix(4))"
            } else {
                self.sessdataMasked = "****"
            }
        } else {
            self.sessdataMasked = ""
        }
        self.autoSaveToPhotos = CookieStore.shared.getAutoSaveToPhotos()
    }
    
    // MARK: - 退出登录
    private func logoutBilibili() {
        CookieStore.shared.clearBilibiliCookies()
        reloadLoginState()
    }
    
    // MARK: - 相册权限检查
    private func checkPhotoPermission() {
        let status = PHPhotoLibrary.authorizationStatus(for: .addOnly)
        switch status {
        case .authorized, .limited:
            photoAuthStatusText = "已授权"
        case .denied, .restricted:
            photoAuthStatusText = "已拒绝 (请在系统设置中开启)"
        case .notDetermined:
            photoAuthStatusText = "首次保存时请求"
        @unknown default:
            photoAuthStatusText = "未知"
        }
    }
    
    // MARK: - 计算与清理缓存
    private func calculateCacheSize() {
        DispatchQueue.global(qos: .background).async {
            let tmpDir = NSTemporaryDirectory()
            let fileManager = FileManager.default
            var totalBytes: Int64 = 0
            if let files = try? fileManager.contentsOfDirectory(atPath: tmpDir) {
                for file in files {
                    let path = (tmpDir as NSString).appendingPathComponent(file)
                    if let attrs = try? fileManager.attributesOfItem(atPath: path),
                       let size = attrs[.size] as? Int64 {
                        totalBytes += size
                    }
                }
            }
            let mb = Double(totalBytes) / (1024.0 * 1024.0)
            DispatchQueue.main.async {
                self.cacheSizeText = String(format: "%.1f MB", mb)
            }
        }
    }
    
    private func clearDiskCache() {
        let tmpDir = NSTemporaryDirectory()
        let fileManager = FileManager.default
        if let files = try? fileManager.contentsOfDirectory(atPath: tmpDir) {
            for file in files {
                let path = (tmpDir as NSString).appendingPathComponent(file)
                try? fileManager.removeItem(atPath: path)
            }
        }
        calculateCacheSize()
        showClearCacheSuccess = true
    }
}

/// 添加备用中转网站模态弹窗 (支持 URL 置顶与 700ms 防抖自动抓取网页标题)
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
                                        .foregroundColor(.blue)
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
                                        .foregroundColor(.blue)
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
