import SwiftUI
import Photos

/// 设置视图：B站账号安全登录授权、自动存入系统相册配置、缓存清理与关于说明
struct SettingsView: View {
    @State private var isLoggedIn: Bool = false
    @State private var sessdataMasked: String = ""
    @State private var autoSaveToPhotos: Bool = true
    @State private var showLoginSheet: Bool = false
    @State private var showClearCacheSuccess: Bool = false
    @State private var cacheSizeText: String = "0.0 MB"
    @State private var photoAuthStatusText: String = "已授权"
    
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
            .alert(isPresented: $showClearCacheSuccess) {
                Alert(
                    title: Text(strings.appName),
                    message: Text(strings.clearCacheSuccess),
                    dismissButton: .default(Text(strings.confirm))
                )
            }
            .onAppear {
                reloadLoginState()
                checkPhotoPermission()
                calculateCacheSize()
            }
        }
        .navigationViewStyle(StackNavigationViewStyle())
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
