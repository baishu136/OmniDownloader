import SwiftUI

/// OmniDownloader iOS 原生应用程序入口
@main
struct OmniDownloaderApp: App {
    @StateObject private var downloadManager = DownloadManager.shared
    
    var body: some Scene {
        WindowGroup {
            MainTabView()
                .environmentObject(downloadManager)
                .onAppear {
                    // 初始化相册权限准备
                    _ = MediaSaver.shared
                }
        }
    }
}
