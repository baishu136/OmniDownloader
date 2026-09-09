import SwiftUI

/// 合集/多视频聚合下载卡片 (支持总进度条、当前子集进度、折叠/展开分集抽屉)
struct CollectionTaskCard: View {
    @EnvironmentObject var downloadManager: DownloadManager
    let collectionId: String
    let collectionTitle: String
    let tasks: [DownloadTask]
    
    @State private var isExpanded: Bool = false
    private let strings = AppStrings.current
    
    // 统计计算
    private var totalCount: Int { tasks.count }
    private var completedCount: Int { tasks.filter { $0.status == .completed }.count }
    private var currentActiveTask: DownloadTask? {
        tasks.first { $0.status == .downloading || $0.status == .muxing || $0.status == .saving } ?? tasks.first { $0.status == .pending }
    }
    
    // 加权总进度计算
    private var totalProgress: Float {
        guard totalCount > 0 else { return 0 }
        let sum = tasks.reduce(Float(0)) { acc, t in
            if t.status == .completed {
                return acc + 1.0
            } else if t.status == .downloading || t.status == .muxing || t.status == .saving {
                return acc + min(max(t.progress, 0), 0.99)
            }
            return acc
        }
        return min(sum / Float(totalCount), 1.0)
    }
    
    private var isAllCompleted: Bool {
        completedCount == totalCount && totalCount > 0
    }
    
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            // MARK: - 头部：合集标题与集数统计
            HStack(alignment: .top, spacing: 10) {
                ZStack {
                    RoundedRectangle(cornerRadius: 8)
                        .fill(Color.accentColor.opacity(0.12))
                        .frame(width: 44, height: 44)
                    Image(systemName: "square.stack.3d.down.right.fill")
                        .font(.system(size: 20))
                        .foregroundColor(.accentColor)
                }
                
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(collectionTitle)
                            .font(.system(size: 16, weight: .bold))
                            .foregroundColor(.primary)
                            .lineLimit(1)
                        
                        Spacer()
                        
                        // 集合操作菜单
                        Menu {
                            if !isAllCompleted {
                                Button(action: pauseAllCollection) {
                                    Label("暂停合集全部任务", systemImage: "pause")
                                }
                                Button(action: resumeAllCollection) {
                                    Label("继续合集全部任务", systemImage: "play")
                                }
                            }
                            Button(role: .destructive, action: deleteAllCollection) {
                                Label("删除合集", systemImage: "trash")
                            }
                        } label: {
                            Image(systemName: "ellipsis")
                                .foregroundColor(.secondary)
                                .padding(6)
                        }
                    }
                    
                    HStack(spacing: 8) {
                        Text(isAllCompleted ? "已全部下载 (\(completedCount)/\(totalCount)集)" : "正在下载合集 (\(completedCount)/\(totalCount)集)")
                            .font(.system(size: 12, weight: .medium))
                            .foregroundColor(isAllCompleted ? .green : .accentColor)
                        
                        Text("· \(Int(totalProgress * 100))%")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundColor(.secondary)
                    }
                }
            }
            
            // MARK: - 加权总进度条
            VStack(alignment: .leading, spacing: 6) {
                ProgressView(value: totalProgress, total: 1.0)
                    .progressViewStyle(LinearProgressViewStyle(tint: isAllCompleted ? .green : .accentColor))
                    .scaleEffect(x: 1, y: 1.5, anchor: .center)
                    .clipShape(RoundedRectangle(cornerRadius: 3))
                    .animation(.easeInOut(duration: 0.25), value: totalProgress)
            }
            
            // MARK: - 当前正在下载的分集子进度 (未全部完成时呈现)
            if !isAllCompleted, let active = currentActiveTask {
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Image(systemName: "arrow.triangle.2.circlepath")
                            .font(.system(size: 11))
                            .foregroundColor(.accentColor)
                        
                        Text("正在处理第 \(active.episodeIndex)/\(totalCount) 集：\(active.title)")
                            .font(.system(size: 13, weight: .medium))
                            .foregroundColor(.primary)
                            .lineLimit(1)
                        
                        Spacer()
                        
                        Text("\(Int(active.progress * 100))%")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundColor(.secondary)
                    }
                    
                    ProgressView(value: active.progress, total: 1.0)
                        .progressViewStyle(LinearProgressViewStyle(tint: .blue))
                        .animation(.easeInOut(duration: 0.2), value: active.progress)
                    
                    if !active.speed.isEmpty {
                        HStack {
                            Spacer()
                            Text("\(active.speed) \(active.eta.isEmpty ? "" : "· 剩余 " + active.eta)")
                                .font(.system(size: 11))
                                .foregroundColor(.secondary)
                        }
                    }
                }
                .padding(10)
                .background(Color(UIColor.tertiarySystemGroupedBackground))
                .cornerRadius(10)
            }
            
            // MARK: - 展开 / 折叠 分集抽屉控制条
            Button(action: {
                withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) {
                    isExpanded.toggle()
                }
            }) {
                HStack {
                    Text(isExpanded ? "收起分集详情" : "展开分集明细 (\(totalCount) 集)")
                        .font(.system(size: 13, weight: .medium))
                        .foregroundColor(.accentColor)
                    
                    Spacer()
                    
                    Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundColor(.accentColor)
                }
                .padding(.vertical, 4)
            }
            
            // MARK: - 抽屉展开内容：分集子任务列表
            if isExpanded {
                VStack(spacing: 8) {
                    Divider()
                        .padding(.vertical, 2)
                    
                    ForEach(tasks) { task in
                        HStack(spacing: 10) {
                            Text("P\(task.episodeIndex)")
                                .font(.system(size: 12, weight: .bold))
                                .foregroundColor(.secondary)
                                .frame(width: 32, alignment: .leading)
                            
                            VStack(alignment: .leading, spacing: 2) {
                                Text(task.title)
                                    .font(.system(size: 13))
                                    .foregroundColor(.primary)
                                    .lineLimit(1)
                                
                                Text(subTaskStatusText(task))
                                    .font(.system(size: 11))
                                    .foregroundColor(subTaskStatusColor(task))
                            }
                            
                            Spacer()
                            
                            if task.status == .completed {
                                Image(systemName: "checkmark.circle.fill")
                                    .foregroundColor(.green)
                                    .font(.system(size: 14))
                            } else if task.status == .failed {
                                Button(action: {
                                    downloadManager.retryTask(id: task.id)
                                }) {
                                    Image(systemName: "arrow.clockwise")
                                        .font(.system(size: 12))
                                        .foregroundColor(.red)
                                }
                            } else if task.status == .downloading {
                                ProgressView()
                                    .scaleEffect(0.7)
                            }
                        }
                        .padding(.vertical, 4)
                    }
                }
                .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
        .padding(14)
        .background(Color(UIColor.secondarySystemGroupedBackground))
        .cornerRadius(14)
        .shadow(color: Color.black.opacity(0.04), radius: 5, x: 0, y: 2)
    }
    
    // 状态文案
    private func subTaskStatusText(_ task: DownloadTask) -> String {
        switch task.status {
        case .pending:
            return "等待中"
        case .downloading:
            return "下载中 \(Int(task.progress * 100))%"
        case .muxing:
            return "合成中..."
        case .saving:
            return "存入相册..."
        case .completed:
            return "已保存至相册"
        case .paused:
            return "已暂停"
        case .failed:
            return "下载失败"
        }
    }
    
    private func subTaskStatusColor(_ task: DownloadTask) -> Color {
        switch task.status {
        case .completed:
            return .green
        case .failed:
            return .red
        case .downloading, .muxing, .saving:
            return .accentColor
        default:
            return .secondary
        }
    }
    
    // MARK: - 快捷操作
    private func pauseAllCollection() {
        for t in tasks where t.status == .downloading {
            downloadManager.pauseTask(id: t.id)
        }
    }
    
    private func resumeAllCollection() {
        for t in tasks where t.status == .paused {
            downloadManager.resumeTask(id: t.id)
        }
    }
    
    private func deleteAllCollection() {
        for t in tasks {
            downloadManager.deleteTask(id: t.id)
        }
    }
}
