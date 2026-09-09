import SwiftUI

/// 应用主 Tab 导航容器
struct MainTabView: View {
    @EnvironmentObject var downloadManager: DownloadManager
    @State private var selectedTab: Int = 0
    
    private let strings = AppStrings.current
    
    private var activeDownloadCount: Int {
        downloadManager.tasks.filter { $0.status == .downloading || $0.status == .pending || $0.status == .muxing || $0.status == .saving }.count
    }
    
    var body: some View {
        TabView(selection: $selectedTab) {
            HomeView()
                .tabItem {
                    Label(strings.tabHome, systemImage: "link.circle.fill")
                }
                .tag(0)
            
            TasksView()
                .tabItem {
                    Label(strings.tabTasks, systemImage: "arrow.down.circle.fill")
                }
                .badge(activeDownloadCount > 0 ? activeDownloadCount : 0)
                .tag(1)
            
            SettingsView()
                .tabItem {
                    Label(strings.tabSettings, systemImage: "gearshape.fill")
                }
                .tag(2)
        }
        .accentColor(.blue)
    }
}
