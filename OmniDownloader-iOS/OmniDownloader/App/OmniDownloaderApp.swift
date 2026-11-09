import SwiftUI

/// OmniDownloader iOS 原生应用程序入口
@main
@MainActor
struct OmniDownloaderApp: App {
    @UIApplicationDelegateAdaptor(OmniAppDelegate.self) private var appDelegate
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var downloadManager = DownloadManager.shared
    @AppStorage("com.omni.downloader.appearance") private var appearance = "system"

    private var preferredColorScheme: ColorScheme? {
        switch appearance {
        case "light": return .light
        case "dark": return .dark
        default: return nil
        }
    }
    
    var body: some Scene {
        WindowGroup {
            MainTabView()
                .preferredColorScheme(preferredColorScheme)
                .environmentObject(downloadManager)
                .onChange(of: scenePhase) { value in if value == .active { downloadManager.resumeForegroundProcessing() } }
                .onAppear {
                    OmniPalette.configureNativeAppearance()
                    // 初始化相册权限准备
                    _ = MediaSaver.shared
                }
        }
    }
}
