import SwiftUI

/// OmniDownloader iOS 原生应用程序入口
@main
@MainActor
struct OmniDownloaderApp: App {
    @UIApplicationDelegateAdaptor(OmniAppDelegate.self) private var appDelegate
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var downloadManager = DownloadManager.shared
    @AppStorage("com.omni.downloader.appearance") private var appearance = "system"
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var launched=false
    @State private var launchProgress:CGFloat=0

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
                .modifier(LaunchContent(progress:launchProgress))
                .overlay { if !launched { LaunchOverlay(progress:launchProgress) } }
                .task {
                    do {
                        try await Task.sleep(nanoseconds:250_000_000)
                        withAnimation(reduceMotion ? nil:.linear(duration:0.24)){launchProgress=1}
                        if !reduceMotion { try await Task.sleep(nanoseconds:240_000_000) }
                        launched=true
                        await AppUpdates.shared.check()
                    } catch { launchProgress=1;launched=true }
                }
                .onChange(of: scenePhase) { value in if value == .active { downloadManager.resumeForegroundProcessing() } }
                .onAppear {
                    OmniPalette.configureNativeAppearance()
                    // 初始化相册权限准备
                    _ = MediaSaver.shared
                }
        }
    }
}
