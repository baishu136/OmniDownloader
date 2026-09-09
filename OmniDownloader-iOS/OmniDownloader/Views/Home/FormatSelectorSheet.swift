import SwiftUI

/// 格式与合集下载弹窗 (采用可滚动主体 + 吸底常驻操作栏 Sticky Footer)
public struct FormatSelectorSheet: View {
    @Environment(\.presentationMode) var presentationMode
    @EnvironmentObject var downloadManager: DownloadManager
    
    let metadata: VideoMetadata
    var onDismiss: (() -> Void)? = nil
    var onStartDownload: ((DownloadTask) -> Void)? = nil
    var onStartBatchDownload: (([DownloadTask]) -> Void)? = nil
    var onOpenBilibiliLogin: (() -> Void)? = nil
    
    @State private var isCollectionMode: Bool
    @State private var selectedIndex: Int = 0
    @State private var selectedEpisodes: Set<Int> = []
    @State private var selectedType: DownloadType = .videoWithAudio
    @State private var selectedFormat: FormatOption?
    @State private var selectedAudioFormat: AudioFormat = .mp3
    @State private var saveCoverWithDownload: Bool = false
    @State private var showLoginSheetInternal: Bool = false
    
    private let strings = AppStrings.current
    
    public init(
        metadata: VideoMetadata,
        onDismiss: (() -> Void)? = nil,
        onStartDownload: ((DownloadTask) -> Void)? = nil,
        onStartBatchDownload: (([DownloadTask]) -> Void)? = nil,
        onOpenBilibiliLogin: (() -> Void)? = nil
    ) {
        self.metadata = metadata
        self.onDismiss = onDismiss
        self.onStartDownload = onStartDownload
        self.onStartBatchDownload = onStartBatchDownload
        self.onOpenBilibiliLogin = onOpenBilibiliLogin
        
        let hasMulti = metadata.multiMediaList.count > 1
        _isCollectionMode = State(initialValue: hasMulti)
        _selectedEpisodes = State(initialValue: Set(0..<metadata.multiMediaList.count))
        let initialFormats = metadata.multiMediaList.first?.availableVideoFormats ?? metadata.availableVideoFormats
        _selectedFormat = State(initialValue: initialFormats.first)
    }
    
    private var currentMedia: VideoMetadata {
        if metadata.multiMediaList.indices.contains(selectedIndex) {
            return metadata.multiMediaList[selectedIndex]
        }
        return metadata
    }
    
    public var body: some View {
        VStack(spacing: 0) {
            // 1. 可滚动主体区域
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    // 视频概览头
                    headerView
                    
                    // 单集 / 合集切换器
                    if metadata.multiMediaList.count > 1 {
                        modePickerView
                    }
                    
                    // 单集分P横栏 或 合集勾选列表
                    if metadata.multiMediaList.count > 1 {
                        if !isCollectionMode {
                            singlePartPickerView
                        } else {
                            collectionChecklistView
                        }
                    }
                    
                    Divider()
                    
                    // 未登录 B 站提示横幅 (若仅返回 <= 480P)
                    if metadata.siteName == "哔哩哔哩" && !CookieStore.shared.isBilibiliLoggedIn {
                        bilibiliGuestPromptView
                    }
                    
                    // 下载模式选择与保存封面开关
                    downloadTypeSection
                    
                    // 清晰度格式列表
                    formatOptionsSection
                    
                    Spacer().frame(height: 16)
                }
                .padding(.horizontal, 20)
                .padding(.top, 16)
            }
            
            // 2. 吸底常驻操作栏 (Sticky Footer)
            stickyFooterBar
        }
        .background(Color(uiColor: .systemBackground))
        .sheet(isPresented: $showLoginSheetInternal) {
            BilibiliLoginSheet()
        }
    }
    
    // MARK: - 子视图拆分
    
    private var headerView: some View {
        HStack(spacing: 12) {
            if let imgUrl = URL(string: currentMedia.thumbnailUrl) {
                AsyncImage(url: imgUrl) { phase in
                    switch phase {
                    case .success(let img):
                        img.resizable().aspectRatio(contentMode: .fill)
                    default:
                        Color.gray.opacity(0.2)
                    }
                }
                .frame(width: 100, height: 62)
                .clipShape(RoundedRectangle(cornerRadius: 8))
            }
            
            VStack(alignment: .leading, spacing: 4) {
                Text(currentMedia.title)
                    .font(.headline)
                    .lineLimit(2)
                HStack(spacing: 8) {
                    Text(metadata.siteName)
                        .font(.caption2)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(Color.blue.opacity(0.12))
                        .foregroundColor(.blue)
                        .clipShape(Capsule())
                    if !currentMedia.durationText.isEmpty {
                        Text(currentMedia.durationText)
                            .font(.caption2)
                            .foregroundColor(.secondary)
                    }
                    Text(currentMedia.author)
                        .font(.caption2)
                        .foregroundColor(.secondary)
                        .lineLimit(1)
                }
            }
        }
    }
    
    private var modePickerView: some View {
        HStack(spacing: 0) {
            Button(action: { isCollectionMode = false }) {
                HStack {
                    Image(systemName: "play.rectangle")
                    Text(strings.downloadSingleMode)
                }
                .font(.subheadline.bold())
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
                .background(!isCollectionMode ? Color(uiColor: .systemBackground) : Color.clear)
                .foregroundColor(!isCollectionMode ? .blue : .secondary)
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .shadow(color: !isCollectionMode ? Color.black.opacity(0.08) : .clear, radius: 2)
            }
            
            Button(action: { isCollectionMode = true }) {
                HStack {
                    Image(systemName: "square.stack.3d.down.right")
                    Text("\(strings.downloadCollectionMode) (\(metadata.multiMediaList.count))")
                }
                .font(.subheadline.bold())
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
                .background(isCollectionMode ? Color(uiColor: .systemBackground) : Color.clear)
                .foregroundColor(isCollectionMode ? .blue : .secondary)
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .shadow(color: isCollectionMode ? Color.black.opacity(0.08) : .clear, radius: 2)
            }
        }
        .padding(3)
        .background(Color(uiColor: .secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 10))
    }
    
    private var singlePartPickerView: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(strings.switchBilibiliPartPrompt)
                .font(.caption.bold())
                .foregroundColor(.blue)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(Array(metadata.multiMediaList.enumerated()), id: \.offset) { index, item in
                        let isSelected = index == selectedIndex
                        Button(action: { selectedIndex = index }) {
                            Text("P\(index + 1)")
                                .font(.caption.bold())
                                .padding(.horizontal, 12)
                                .padding(.vertical, 6)
                                .background(isSelected ? Color.blue : Color(uiColor: .secondarySystemBackground))
                                .foregroundColor(isSelected ? .white : .primary)
                                .clipShape(RoundedRectangle(cornerRadius: 6))
                        }
                    }
                }
            }
        }
    }
    
    private var collectionChecklistView: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(String(format: strings.selectedEpisodesCount, selectedEpisodes.count, metadata.multiMediaList.count))
                    .font(.caption.bold())
                    .foregroundColor(.blue)
                Spacer()
                HStack(spacing: 6) {
                    Button(strings.selectAll) { selectedEpisodes = Set(0..<metadata.multiMediaList.count) }
                        .font(.caption2.bold())
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(Color.blue.opacity(0.12)).foregroundColor(.blue).clipShape(RoundedRectangle(cornerRadius: 4))
                    Button(strings.invertSelection) { selectedEpisodes = Set((0..<metadata.multiMediaList.count).filter { !selectedEpisodes.contains($0) }) }
                        .font(.caption2.bold())
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(Color.blue.opacity(0.12)).foregroundColor(.blue).clipShape(RoundedRectangle(cornerRadius: 4))
                    Button(strings.deselectAll) { selectedEpisodes = [] }
                        .font(.caption2.bold())
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(Color.gray.opacity(0.12)).foregroundColor(.secondary).clipShape(RoundedRectangle(cornerRadius: 4))
                }
            }
            
            // 紧凑分集勾选框
            ScrollView {
                VStack(spacing: 4) {
                    ForEach(Array(metadata.multiMediaList.enumerated()), id: \.offset) { idx, ep in
                        let checked = selectedEpisodes.contains(idx)
                        Button(action: {
                            if checked { selectedEpisodes.remove(idx) } else { selectedEpisodes.insert(idx) }
                        }) {
                            HStack {
                                Image(systemName: checked ? "checkmark.square.fill" : "square")
                                    .foregroundColor(checked ? .blue : .secondary)
                                Text("P\(idx + 1)")
                                    .font(.caption.bold())
                                    .foregroundColor(.blue)
                                Text(ep.title)
                                    .font(.caption)
                                    .lineLimit(1)
                                    .foregroundColor(.primary)
                                Spacer()
                                Text(ep.durationText)
                                    .font(.caption2)
                                    .foregroundColor(.secondary)
                            }
                            .padding(.vertical, 4)
                        }
                    }
                }
                .padding(.horizontal, 8)
            }
            .frame(height: 130)
            .background(Color(uiColor: .secondarySystemBackground).opacity(0.6))
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.gray.opacity(0.2), lineWidth: 1))
        }
    }
    
    private var bilibiliGuestPromptView: some View {
        HStack(spacing: 8) {
            Image(systemName: "exclamationmark.circle.fill")
                .foregroundColor(.orange)
            Text("当前为游客模式(最高480P)，登录即可免费解锁 1080P/4K")
                .font(.caption)
                .foregroundColor(.primary)
            Spacer()
            Button("一键登录") {
                if let custom = onOpenBilibiliLogin {
                    custom()
                } else {
                    showLoginSheetInternal = true
                }
            }
            .font(.caption.bold())
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(Color.orange)
            .foregroundColor(.white)
            .clipShape(Capsule())
        }
        .padding(10)
        .background(Color.orange.opacity(0.12))
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }
    
    private var downloadTypeSection: some View {
        VStack(spacing: 10) {
            HStack {
                Text(strings.downloadType).font(.subheadline.bold())
                Spacer()
                Toggle(strings.saveCover, isOn: $saveCoverWithDownload)
                    .labelsHidden()
                Text(strings.saveCover).font(.caption).foregroundColor(.secondary)
            }
            
            HStack(spacing: 6) {
                modeChip(title: strings.tabVideo, icon: "video.fill", type: .videoWithAudio)
                modeChip(title: strings.tabMute, icon: "speaker.slash.fill", type: .videoOnly)
                modeChip(title: strings.tabAudio, icon: "music.note", type: .audioOnly)
            }
        }
    }
    
    private func modeChip(title: String, icon: String, type: DownloadType) -> some View {
        let isSelected = selectedType == type
        return Button(action: { selectedType = type }) {
            HStack(spacing: 4) {
                Image(systemName: icon)
                Text(title)
            }
            .font(.caption.bold())
            .frame(maxWidth: .infinity)
            .padding(.vertical, 8)
            .background(isSelected ? Color.blue : Color(uiColor: .secondarySystemBackground))
            .foregroundColor(isSelected ? .white : .primary)
            .clipShape(RoundedRectangle(cornerRadius: 8))
        }
    }
    
    private var formatOptionsSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            if selectedType == .videoWithAudio || selectedType == .videoOnly {
                Text(selectedType == .videoWithAudio ? strings.selectResolutionWithAudio : strings.selectResolutionMute)
                    .font(.caption).foregroundColor(.secondary)
                
                VStack(spacing: 8) {
                    ForEach(currentMedia.availableVideoFormats) { format in
                        let isSelected = selectedFormat?.formatId == format.formatId
                        Button(action: { selectedFormat = format }) {
                            HStack {
                                Image(systemName: isSelected ? "largecircle.fill.circle" : "circle")
                                    .foregroundColor(isSelected ? .blue : .secondary)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(format.resolutionLabel).font(.subheadline.bold()).foregroundColor(.primary)
                                    if !format.note.isEmpty {
                                        Text(format.note).font(.caption2).foregroundColor(.secondary)
                                    }
                                }
                                Spacer()
                                if !format.approximateSize.isEmpty {
                                    Text(format.approximateSize).font(.caption.bold()).foregroundColor(.blue)
                                }
                            }
                            .padding(12)
                            .background(isSelected ? Color.blue.opacity(0.08) : Color(uiColor: .secondarySystemBackground).opacity(0.5))
                            .clipShape(RoundedRectangle(cornerRadius: 10))
                            .overlay(RoundedRectangle(cornerRadius: 10).stroke(isSelected ? Color.blue : Color.clear, lineWidth: 1.5))
                        }
                    }
                }
            } else if selectedType == .audioOnly {
                Text(strings.selectAudioFormat).font(.caption).foregroundColor(.secondary)
                VStack(spacing: 8) {
                    ForEach(AudioFormat.allCases, id: \.self) { audio in
                        let isSelected = selectedAudioFormat == audio
                        Button(action: { selectedAudioFormat = audio }) {
                            HStack {
                                Image(systemName: isSelected ? "largecircle.fill.circle" : "circle")
                                    .foregroundColor(isSelected ? .blue : .secondary)
                                Text(audio.label).font(.subheadline.bold()).foregroundColor(.primary)
                                Spacer()
                            }
                            .padding(12)
                            .background(isSelected ? Color.blue.opacity(0.08) : Color(uiColor: .secondarySystemBackground).opacity(0.5))
                            .clipShape(RoundedRectangle(cornerRadius: 10))
                            .overlay(RoundedRectangle(cornerRadius: 10).stroke(isSelected ? Color.blue : Color.clear, lineWidth: 1.5))
                        }
                    }
                }
            }
        }
    }
    
    // MARK: - 吸底常驻操作栏 (Sticky Footer)
    
    private var stickyFooterBar: some View {
        VStack(spacing: 8) {
            if isCollectionMode {
                let count = selectedEpisodes.count
                let res = selectedFormat?.resolutionLabel ?? "最佳画质"
                let mainBtnText = selectedType == .audioOnly
                    ? String(format: strings.batchDownloadAudio, count, selectedAudioFormat.rawValue.uppercased())
                    : String(format: strings.batchDownloadVideos, count, res)
                
                Button(action: {
                    guard !selectedEpisodes.isEmpty else { return }
                    var batchTasks: [DownloadTask] = []
                    let colId = "col_\(UUID().uuidString.prefix(8))"
                    let sortedIdxs = selectedEpisodes.sorted()
                    
                    for (i, idx) in sortedIdxs.enumerated() {
                        let sub = metadata.multiMediaList[idx]
                        batchTasks.append(DownloadTask(
                            url: sub.url,
                            title: sub.title,
                            author: sub.author,
                            thumbnailUrl: sub.thumbnailUrl,
                            downloadType: selectedType,
                            selectedResolution: res,
                            audioFormat: selectedAudioFormat,
                            collectionId: colId,
                            collectionTitle: metadata.title,
                            episodeIndex: i + 1,
                            episodeTotal: sortedIdxs.count
                        ))
                    }
                    if let custom = onStartBatchDownload {
                        custom(batchTasks)
                    } else {
                        downloadManager.addBatchTasks(batchTasks)
                    }
                    if let dismiss = onDismiss {
                        dismiss()
                    } else {
                        presentationMode.wrappedValue.dismiss()
                    }
                }) {
                    HStack {
                        Image(systemName: "arrow.down.circle.fill")
                        Text(count > 0 ? mainBtnText : strings.pleaseSelectEpisode)
                    }
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
                    .background(count > 0 ? Color.blue : Color.gray)
                    .foregroundColor(.white)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                }
                .disabled(count == 0)
                
                Button(action: {
                    var batchTasks: [DownloadTask] = []
                    let colId = "col_\(UUID().uuidString.prefix(8))"
                    let all = metadata.multiMediaList
                    for (i, sub) in all.enumerated() {
                        batchTasks.append(DownloadTask(
                            url: sub.url,
                            title: sub.title,
                            author: sub.author,
                            thumbnailUrl: sub.thumbnailUrl,
                            downloadType: selectedType,
                            selectedResolution: res,
                            audioFormat: selectedAudioFormat,
                            collectionId: colId,
                            collectionTitle: metadata.title,
                            episodeIndex: i + 1,
                            episodeTotal: all.count
                        ))
                    }
                    if let custom = onStartBatchDownload {
                        custom(batchTasks)
                    } else {
                        downloadManager.addBatchTasks(batchTasks)
                    }
                    if let dismiss = onDismiss {
                        dismiss()
                    } else {
                        presentationMode.wrappedValue.dismiss()
                    }
                }) {
                    HStack {
                        Image(systemName: "square.and.arrow.down")
                        Text(String(format: strings.downloadAllCount, metadata.multiMediaList.count))
                    }
                    .font(.subheadline.bold())
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
                    .background(Color(uiColor: .secondarySystemBackground))
                    .foregroundColor(.blue)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                }
            } else {
                // 单集下载主按钮
                let res = selectedFormat?.resolutionLabel ?? "默认画质"
                Button(action: {
                    let task = DownloadTask(
                        url: currentMedia.url,
                        title: currentMedia.title,
                        author: currentMedia.author,
                        thumbnailUrl: currentMedia.thumbnailUrl,
                        downloadType: selectedType,
                        selectedResolution: res,
                        audioFormat: selectedAudioFormat
                    )
                    if let custom = onStartDownload {
                        custom(task)
                    } else {
                        downloadManager.addTask(task)
                    }
                    if let dismiss = onDismiss {
                        dismiss()
                    } else {
                        presentationMode.wrappedValue.dismiss()
                    }
                }) {
                    HStack {
                        Image(systemName: "arrow.down.circle.fill")
                        Text("\(strings.startDownload) (\(res))")
                    }
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
                    .background(Color.blue)
                    .foregroundColor(.white)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                }
                
                if metadata.multiMediaList.count > 1 {
                    Button(action: { isCollectionMode = true }) {
                        HStack {
                            Image(systemName: "square.stack.3d.down.right")
                            Text(String(format: strings.switchToCollectionMode, metadata.multiMediaList.count))
                        }
                        .font(.subheadline.bold())
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .background(Color(uiColor: .secondarySystemBackground))
                        .foregroundColor(.blue)
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                    }
                }
            }
        }
        .padding(.horizontal, 20)
        .padding(.top, 10)
        .padding(.bottom, 20)
        .background(
            Color(uiColor: .systemBackground)
                .shadow(color: Color.black.opacity(0.08), radius: 8, x: 0, y: -4)
        )
    }
}
