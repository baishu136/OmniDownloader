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
                .overlay{
                    if !launched{
                        ZStack{OmniPalette.background.ignoresSafeArea();OmniBrand(size:76)}.accessibilityHidden(true).transition(.opacity)
                    }
                }
                .task{try? await Task.sleep(nanoseconds:150_000_000);withAnimation(reduceMotion ? nil:.easeOut(duration:0.18)){launched=true}}
                .onChange(of: scenePhase) { value in if value == .active { downloadManager.resumeForegroundProcessing() } }
                .onAppear {
                    OmniPalette.configureNativeAppearance()
                    // 初始化相册权限准备
                    _ = MediaSaver.shared
                }
        }
    }
}
