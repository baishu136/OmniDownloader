import SwiftUI

/// 下载任务管理视图：分类展示进行中与已完成任务，聚合合集进度，支持全局启停与平滑翻页动画
struct TasksView: View {
    @EnvironmentObject var downloadManager: DownloadManager
    @State private var selectedTab: Int = 0 // 0: 进行中, 1: 已完成
    @State private var showClearConfirmAlert: Bool = false
    
    private let strings = AppStrings.current
    
    // 过滤进行中的展示项
    private var downloadingItems: [TaskDisplayItem] {
        downloadManager.getDisplayItems(completed: false)
    }
    
    // 过滤已完成的展示项
    private var completedItems: [TaskDisplayItem] {
        downloadManager.getDisplayItems(completed: true)
    }
    
    var body: some View {
        NavigationView {
            ZStack {
                Color(UIColor.systemGroupedBackground)
                    .ignoresSafeArea()
                
                VStack(spacing: 0) {
                    // 顶部分页分段切换条 (带平滑视觉缓冲)
                    pickerHeader
                        .padding(.horizontal, 16)
                        .padding(.vertical, 8)
                        .background(Color(UIColor.systemBackground))
                    
                    Divider()
                    
                    // 任务列表滚动区
                    ScrollView {
                        LazyVStack(spacing: 14) {
                            if selectedTab == 0 {
                                if downloadingItems.isEmpty {
                                    emptyStateView(
                                        icon: "arrow.down.circle",
                                        title: strings.emptyDownloading,
                                        subtitle: "粘贴视频链接即可极速下载"
                                    )
                                    .transition(.opacity)
                                } else {
                                    ForEach(downloadingItems) { item in
                                        renderDisplayItem(item)
                                    }
                                    .transition(.opacity.combined(with: .move(edge: .leading)))
                                }
                            } else {
                                if completedItems.isEmpty {
                                    emptyStateView(
                                        icon: "checkmark.circle",
                                        title: strings.emptyCompleted,
                                        subtitle: "已下载完成的音视频文件将保存在这里"
                                    )
                                    .transition(.opacity)
                                } else {
                                    ForEach(completedItems) { item in
                                        renderDisplayItem(item)
                                    }
                                    .transition(.opacity.combined(with: .move(edge: .trailing)))
                                }
                            }
                        }
                        .padding(16)
                        .animation(.easeInOut(duration: 0.25), value: selectedTab)
                    }
                }
            }
            .navigationTitle(strings.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    trailingToolbarMenu
                }
            }
            .alert(isPresented: $showClearConfirmAlert) {
                Alert(
                    title: Text(strings.clearAllConfirmTitle),
                    message: Text(strings.clearAllConfirmMessage),
                    primaryButton: .destructive(Text(strings.actionDelete)) {
                        withAnimation {
                            downloadManager.clearCompletedTasks()
                        }
                    },
                    secondaryButton: .cancel(Text(strings.cancel))
                )
            }
        }
        .navigationViewStyle(StackNavigationViewStyle())
    }
    
    // MARK: - 分页控制器
    private var pickerHeader: some View {
        Picker("任务分类", selection: $selectedTab) {
            Text("\(strings.tabDownloading) (\(downloadingCount))").tag(0)
            Text("\(strings.tabCompleted) (\(completedCount))").tag(1)
        }
        .pickerStyle(SegmentedPickerStyle())
    }
    
    private var downloadingCount: Int {
        downloadManager.tasks.filter { $0.status != .completed }.count
    }
    
    private var completedCount: Int {
        downloadManager.tasks.filter { $0.status == .completed }.count
    }
    
    // MARK: - 渲染展示项
    @ViewBuilder
    private func renderDisplayItem(_ item: TaskDisplayItem) -> some View {
        switch item {
        case .single(let task):
            TaskCard(task: task)
        case .collection(let collectionId, let collectionTitle, let tasks):
            CollectionTaskCard(collectionId: collectionId, collectionTitle: collectionTitle, tasks: tasks)
        }
    }
    
    // MARK: - 导航栏菜单
    @ViewBuilder
    private var trailingToolbarMenu: some View {
        if selectedTab == 0 {
            Menu {
                Button(action: {
                    downloadManager.resumeAll()
                }) {
                    Label(strings.resumeAll, systemImage: "play.fill")
                }
                Button(action: {
                    downloadManager.pauseAll()
                }) {
                    Label(strings.pauseAll, systemImage: "pause.fill")
                }
            } label: {
                Image(systemName: "ellipsis.circle")
                    .font(.system(size: 16))
            }
        } else {
            if !completedItems.isEmpty {
                Button(action: {
                    showClearConfirmAlert = true
                }) {
                    Text(strings.clearAll)
                        .font(.system(size: 14))
                        .foregroundColor(.red)
                }
            }
        }
    }
    
    // MARK: - 空状态提示
    private func emptyStateView(icon: String, title: String, subtitle: String) -> some View {
        VStack(spacing: 12) {
            Spacer(minLength: 80)
            Image(systemName: icon)
                .font(.system(size: 50))
                .foregroundColor(Color(UIColor.tertiaryLabel))
            Text(title)
                .font(.system(size: 16, weight: .medium))
                .foregroundColor(.secondary)
            Text(subtitle)
                .font(.system(size: 13))
                .foregroundColor(Color(UIColor.placeholderText))
            Spacer(minLength: 80)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 40)
    }
}
