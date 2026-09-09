import SwiftUI

/// 单个下载任务卡片
struct TaskCard: View {
    @EnvironmentObject var downloadManager: DownloadManager
    let task: DownloadTask
    @State private var showShareSheet = false
    
    private let strings = AppStrings.current
    
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 12) {
                // 缩略图 / 占位图标
                thumbnailView
                    .frame(width: 80, height: 60)
                    .cornerRadius(8)
                    .clipped()
                
                // 标题与信息
                VStack(alignment: .leading, spacing: 4) {
                    Text(task.title)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundColor(.primary)
                        .lineLimit(2)
                    
                    HStack(spacing: 6) {
                        // 格式 Badge
                        Text(formatBadgeText)
                            .font(.system(size: 10, weight: .bold))
                            .foregroundColor(.white)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(task.downloadType == .audioOnly ? Color.purple : Color.blue)
                            .cornerRadius(4)
                        
                        if !task.author.isEmpty {
                            Text(task.author)
                                .font(.system(size: 12))
                                .foregroundColor(.secondary)
                                .lineLimit(1)
                        }
                    }
                }
                
                Spacer()
                
                // 右上角操作按钮 (暂停/恢复/重试/更多)
                trailingActionButton
            }
            
            // 进度条与状态信息 (针对未完成任务)
            if task.status != .completed {
                VStack(alignment: .leading, spacing: 6) {
                    ProgressView(value: task.progress, total: 1.0)
                        .progressViewStyle(LinearProgressViewStyle(tint: progressColor))
                        .animation(.easeInOut(duration: 0.2), value: task.progress)
                    
                    HStack {
                        // 状态文案
                        Text(statusDescriptionText)
                            .font(.system(size: 12))
                            .foregroundColor(statusColor)
                        
                        Spacer()
                        
                        // 速度与耗时
                        if task.status == .downloading && !task.speed.isEmpty {
                            Text("\(task.speed) \(task.eta.isEmpty ? "" : "· 剩余 " + task.eta)")
                                .font(.system(size: 11))
                                .foregroundColor(.secondary)
                        }
                    }
                }
            } else {
                // 已完成状态栏：显示存入相册成功标签与分享入口
                HStack {
                    HStack(spacing: 4) {
                        Image(systemName: "checkmark.circle.fill")
                            .foregroundColor(.green)
                            .font(.system(size: 13))
                        Text(strings.statusSavedToAlbum)
                            .font(.system(size: 12, weight: .medium))
                            .foregroundColor(.green)
                    }
                    
                    Spacer()
                    
                    // 分享按钮
                    if let path = task.localFilePath, FileManager.default.fileExists(atPath: path) {
                        Button(action: {
                            showShareSheet = true
                        }) {
                            HStack(spacing: 4) {
                                Image(systemName: "square.and.arrow.up")
                                Text("分享")
                            }
                            .font(.system(size: 12))
                            .foregroundColor(.accentColor)
                        }
                    }
                }
                .padding(.top, 2)
            }
        }
        .padding(14)
        .background(Color(UIColor.secondarySystemGroupedBackground))
        .cornerRadius(14)
        .shadow(color: Color.black.opacity(0.04), radius: 5, x: 0, y: 2)
        .sheet(isPresented: $showShareSheet) {
            if let path = task.localFilePath {
                ShareActivityView(activityItems: [URL(fileURLWithPath: path)])
            }
        }
        .contextMenu {
            if task.status == .completed {
                Button(action: {
                    showShareSheet = true
                }) {
                    Label("分享文件", systemImage: "square.and.arrow.up")
                }
            }
            
            Button(role: .destructive, action: {
                withAnimation {
                    downloadManager.deleteTask(id: task.id)
                }
            }) {
                Label(strings.actionDelete, systemImage: "trash")
            }
        }
    }
    
    // MARK: - 缩略图视图
    @ViewBuilder
    private var thumbnailView: some View {
        if let url = URL(string: task.thumbnailUrl), !task.thumbnailUrl.isEmpty {
            AsyncImage(url: url) { phase in
                switch phase {
                case .empty:
                    Color.gray.opacity(0.2)
                        .overlay(ProgressView())
                case .success(let image):
                    image
                        .resizable()
                        .scaledToFill()
                case .failure:
                    placeholderThumbnail
                @unknown default:
                    placeholderThumbnail
                }
            }
        } else {
            placeholderThumbnail
        }
    }
    
    private var placeholderThumbnail: some View {
        ZStack {
            Color(UIColor.tertiarySystemGroupedBackground)
            Image(systemName: task.downloadType == .audioOnly ? "music.note" : "play.rectangle.fill")
                .font(.system(size: 24))
                .foregroundColor(.secondary)
        }
    }
    
    // MARK: - 标签文字
    private var formatBadgeText: String {
        switch task.downloadType {
        case .videoWithAudio:
            return task.selectedResolution
        case .videoOnly:
            return "\(task.selectedResolution) (无声)"
        case .audioOnly:
            return task.audioFormat.rawValue.uppercased()
        }
    }
    
    // MARK: - 进度条颜色
    private var progressColor: Color {
        switch task.status {
        case .failed:
            return .red
        case .paused:
            return .orange
        case .muxing, .saving:
            return .purple
        default:
            return .accentColor
        }
    }
    
    // MARK: - 状态描述文字
    private var statusDescriptionText: String {
        switch task.status {
        case .pending:
            return strings.statusPending
        case .downloading:
            let pct = Int(task.progress * 100)
            return "\(strings.statusDownloading) \(pct)%"
        case .paused:
            return strings.statusPaused
        case .muxing:
            return strings.statusMuxing
        case .saving:
            return strings.statusSavingToAlbum
        case .completed:
            return strings.statusCompleted
        case .failed:
            return "\(strings.statusFailed): \(task.errorMessage ?? "未知错误")"
        }
    }
    
    private var statusColor: Color {
        switch task.status {
        case .failed:
            return .red
        case .paused:
            return .orange
        case .muxing, .saving:
            return .purple
        case .completed:
            return .green
        default:
            return .secondary
        }
    }
    
    // MARK: - 右侧快捷操作按钮
    @ViewBuilder
    private var trailingActionButton: some View {
        switch task.status {
        case .downloading:
            Button(action: {
                downloadManager.pauseTask(id: task.id)
            }) {
                Image(systemName: "pause.fill")
                    .font(.system(size: 14))
                    .foregroundColor(.orange)
                    .padding(8)
                    .background(Color.orange.opacity(0.12))
                    .clipShape(Circle())
            }
        case .paused:
            Button(action: {
                downloadManager.resumeTask(id: task.id)
            }) {
                Image(systemName: "play.fill")
                    .font(.system(size: 14))
                    .foregroundColor(.accentColor)
                    .padding(8)
                    .background(Color.accentColor.opacity(0.12))
                    .clipShape(Circle())
            }
        case .failed:
            Button(action: {
                downloadManager.retryTask(id: task.id)
            }) {
                Image(systemName: "arrow.clockwise")
                    .font(.system(size: 14))
                    .foregroundColor(.red)
                    .padding(8)
                    .background(Color.red.opacity(0.12))
                    .clipShape(Circle())
            }
        default:
            Menu {
                Button(role: .destructive, action: {
                    downloadManager.deleteTask(id: task.id)
                }) {
                    Label(strings.actionDelete, systemImage: "trash")
                }
            } label: {
                Image(systemName: "ellipsis")
                    .font(.system(size: 14))
                    .foregroundColor(.secondary)
                    .padding(8)
            }
        }
    }
}

/// 系统分享面板封装
struct ShareActivityView: UIViewControllerRepresentable {
    let activityItems: [Any]
    let applicationActivities: [UIActivity]? = nil
    
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: activityItems, applicationActivities: applicationActivities)
    }
    
    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
