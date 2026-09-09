import Foundation
import Combine

/// 全局下载任务调度管理中心 (基于 URLSession 后台断点续传通道)
public class DownloadManager: NSObject, ObservableObject, URLSessionDownloadDelegate {
    
    public static let shared = DownloadManager()
    
    @Published public var tasks: [DownloadTask] = []
    
    private var session: URLSession!
    private var downloadTaskMap: [URLSessionDownloadTask: String] = [:] // taskIdentifier -> Omni taskId
    private var activeStreamsMap: [String: (tempVideo: URL?, tempAudio: URL?, isAudioPending: Bool)] = [:]
    
    private let tasksSaveUrl: URL
    
    public override init() {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        self.tasksSaveUrl = docs.appendingPathComponent("omni_tasks.json")
        super.init()
        
        let config = URLSessionConfiguration.background(withIdentifier: "com.omni.downloader.background")
        config.isDiscretionary = false
        config.sessionSendsLaunchEvents = true
        config.timeoutIntervalForRequest = 60
        config.timeoutIntervalForResource = 3600
        self.session = URLSession(configuration: config, delegate: self, delegateQueue: OperationQueue.main)
        
        loadTasks()
    }
    
    // MARK: - 任务持久化
    
    private func saveTasks() {
        do {
            let data = try JSONEncoder().encode(tasks)
            try data.write(to: tasksSaveUrl, options: .atomic)
        } catch {
            print("保存任务失败: \(error)")
        }
    }
    
    private func loadTasks() {
        guard let data = try? Data(contentsOf: tasksSaveUrl),
              let loaded = try? JSONDecoder().decode([DownloadTask].self, from: data) else { return }
        self.tasks = loaded
    }
    
    // MARK: - 聚合分组视图数据
    
    public var displayItems: [TaskDisplayItem] {
        var result: [TaskDisplayItem] = []
        var processedCollectionIds = Set<String>()
        
        for task in tasks {
            if let colId = task.collectionId, !colId.isEmpty {
                if !processedCollectionIds.contains(colId) {
                    processedCollectionIds.insert(colId)
                    let colTasks = tasks.filter { $0.collectionId == colId }.sorted { $0.episodeIndex < $1.episodeIndex }
                    let colTitle = task.collectionTitle ?? "合集视频"
                    result.append(.collection(collectionId: colId, collectionTitle: colTitle, tasks: colTasks))
                }
            } else {
                result.append(.single(task: task))
            }
        }
        return result
    }
    
    // MARK: - 任务提交与控制
    
    /// 添加并启动下载任务
    public func addTask(_ task: DownloadTask) {
        var newTask = task
        newTask.status = .downloading
        tasks.insert(newTask, at: 0)
        saveTasks()
        
        Task {
            await startDownloadProcess(for: newTask)
        }
    }
    
    /// 批量添加合集分集任务
    public func addTasks(_ newTasks: [DownloadTask]) {
        for t in newTasks {
            var updated = t
            updated.status = .downloading
            tasks.insert(updated, at: 0)
        }
        saveTasks()
        
        for t in newTasks {
            Task {
                await startDownloadProcess(for: t)
            }
        }
    }
    
    /// 启动单个任务的解析与流下载流程
    private func startDownloadProcess(for task: DownloadTask) async {
        let site = UrlSniffer.detectSite(from: task.url)
        
        do {
            if site == "哔哩哔哩" {
                let streamUrls = try await BilibiliExtractor.shared.fetchStreamUrls(for: task)
                updateTask(id: task.id) { t in
                    t.title = streamUrls.realTitle
                }
                
                // 处理视频流与音频流下载
                if !streamUrls.videoUrl.isEmpty, let vUrl = URL(string: streamUrls.videoUrl) {
                    var req = URLRequest(url: vUrl)
                    req.setValue("https://www.bilibili.com", forHTTPHeaderField: "Referer")
                    req.setValue("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36", forHTTPHeaderField: "User-Agent")
                    let dlTask = session.downloadTask(with: req)
                    dlTask.taskDescription = "\(task.id)|video"
                    dlTask.resume()
                }
                
                if task.downloadType == .videoWithAudio, !streamUrls.audioUrl.isEmpty, let aUrl = URL(string: streamUrls.audioUrl) {
                    var req = URLRequest(url: aUrl)
                    req.setValue("https://www.bilibili.com", forHTTPHeaderField: "Referer")
                    req.setValue("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36", forHTTPHeaderField: "User-Agent")
                    let dlTask = session.downloadTask(with: req)
                    dlTask.taskDescription = "\(task.id)|audio"
                    dlTask.resume()
                }
            } else if DomesticExtractor.shared.isSupported(task.url) {
                let meta = try await DomesticExtractor.shared.extract(rawUrl: task.url)
                if let directUrl = URL(string: meta.url) {
                    let dlTask = session.downloadTask(with: directUrl)
                    dlTask.taskDescription = "\(task.id)|direct"
                    dlTask.resume()
                }
            } else {
                if let u = URL(string: task.url) {
                    let dlTask = session.downloadTask(with: u)
                    dlTask.taskDescription = "\(task.id)|direct"
                    dlTask.resume()
                }
            }
        } catch {
            updateTask(id: task.id) { t in
                t.status = .failed
                t.errorMessage = error.localizedDescription
            }
        }
    }
    
    public func cancelTask(id: String) {
        updateTask(id: id) { t in
            t.status = .cancelled
        }
    }
    
    public func deleteTask(id: String, deleteFile: Bool = true) {
        if let idx = tasks.firstIndex(where: { $0.id == id }) {
            let task = tasks[idx]
            if deleteFile, let path = task.localFilePath {
                try? FileManager.default.removeItem(atPath: path)
            }
            tasks.remove(at: idx)
            saveTasks()
        }
    }
    
    public func cancelCollection(collectionId: String) {
        for task in tasks where task.collectionId == collectionId && (task.status == .downloading || task.status == .pending) {
            cancelTask(id: task.id)
        }
    }
    
    public func deleteCollection(collectionId: String, deleteFiles: Bool = true) {
        let toRemove = tasks.filter { $0.collectionId == collectionId }
        for t in toRemove {
            deleteTask(id: t.id, deleteFile: deleteFiles)
        }
    }
    
    public func retryTask(id: String) {
        guard let task = tasks.first(where: { $0.id == id }) else { return }
        updateTask(id: id) { t in
            t.status = .downloading
            t.progress = 0
            t.errorMessage = nil
        }
        Task {
            await startDownloadProcess(for: task)
        }
    }
    
    public func retryCollection(collectionId: String) {
        for task in tasks where task.collectionId == collectionId && (task.status == .failed || task.status == .cancelled) {
            retryTask(id: task.id)
        }
    }
    
    private func updateTask(id: String, block: (inout DownloadTask) -> Void) {
        if let idx = tasks.firstIndex(where: { $0.id == id }) {
            block(&tasks[idx])
            saveTasks()
        }
    }
    
    // MARK: - URLSessionDownloadDelegate 回调
    
    public func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didWriteData bytesWritten: Int64,
        totalBytesWritten: Int64,
        totalBytesExpectedToWrite: Int64
    ) {
        guard let desc = downloadTask.taskDescription else { return }
        let parts = desc.split(separator: "|")
        guard let taskId = parts.first.map(String.init) else { return }
        
        let fraction = totalBytesExpectedToWrite > 0 ? Float(totalBytesWritten) / Float(totalBytesExpectedToWrite) : 0.5
        let progressPercent = fraction * 100
        
        updateTask(id: taskId) { t in
            t.progress = progressPercent
            t.status = .downloading
        }
    }
    
    public func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didFinishDownloadingTo location: URL
    ) {
        guard let desc = downloadTask.taskDescription else { return }
        let parts = desc.split(separator: "|")
        guard parts.count == 2, let taskId = parts.first.map(String.init), let streamType = parts.last.map(String.init) else { return }
        
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        let tempFile = docs.appendingPathComponent("tmp_\(taskId)_\(streamType).tmp")
        try? FileManager.default.removeItem(at: tempFile)
        try? FileManager.default.copyItem(at: location, to: tempFile)
        
        if streamType == "direct" {
            // 一体化单流下载完成
            let cleanTitle = sanitizeFileName(tasks.first(where: { $0.id == taskId })?.title ?? "video")
            let finalUrl = docs.appendingPathComponent("\(cleanTitle).mp4")
            try? FileManager.default.removeItem(at: finalUrl)
            try? FileManager.default.moveItem(at: tempFile, to: finalUrl)
            
            updateTask(id: taskId) { t in
                t.status = .completed
                t.progress = 100
                t.localFilePath = finalUrl.path
            }
            
            if CookieStore.shared.autoSaveToAlbum {
                Task { try? await MediaSaver.shared.saveVideoToAlbum(fileUrl: finalUrl) }
            }
        } else if streamType == "video" || streamType == "audio" {
            var streamInfo = activeStreamsMap[taskId] ?? (nil, nil, false)
            if streamType == "video" { streamInfo.tempVideo = tempFile }
            if streamType == "audio" { streamInfo.tempAudio = tempFile }
            activeStreamsMap[taskId] = streamInfo
            
            // 若为 videoOnly 或 音画均已就绪，执行合成
            let currentTask = tasks.first(where: { $0.id == taskId })
            let isMute = currentTask?.downloadType == .videoOnly
            
            if isMute, let v = streamInfo.tempVideo {
                let cleanTitle = sanitizeFileName(currentTask?.title ?? "video")
                let finalUrl = docs.appendingPathComponent("\(cleanTitle).mp4")
                try? FileManager.default.removeItem(at: finalUrl)
                try? FileManager.default.moveItem(at: v, to: finalUrl)
                activeStreamsMap.removeValue(forKey: taskId)
                
                updateTask(id: taskId) { t in
                    t.status = .completed
                    t.progress = 100
                    t.localFilePath = finalUrl.path
                }
                if CookieStore.shared.autoSaveToAlbum {
                    Task { try? await MediaSaver.shared.saveVideoToAlbum(fileUrl: finalUrl) }
                }
            } else if let v = streamInfo.tempVideo, let a = streamInfo.tempAudio {
                // DASH 双流均已就绪，执行 AVFoundation 毫秒合成
                updateTask(id: taskId) { t in
                    t.status = .processing
                    t.eta = "音画合成中..."
                }
                
                let cleanTitle = sanitizeFileName(currentTask?.title ?? "video")
                let finalUrl = docs.appendingPathComponent("\(cleanTitle).mp4")
                activeStreamsMap.removeValue(forKey: taskId)
                
                Task {
                    do {
                        try await AVMuxer.mergeVideoAndAudio(videoUrl: v, audioUrl: a, outputUrl: finalUrl)
                        try? FileManager.default.removeItem(at: v)
                        try? FileManager.default.removeItem(at: a)
                        
                        await MainActor.run {
                            self.updateTask(id: taskId) { t in
                                t.status = .completed
                                t.progress = 100
                                t.localFilePath = finalUrl.path
                            }
                        }
                        if CookieStore.shared.autoSaveToAlbum {
                            try? await MediaSaver.shared.saveVideoToAlbum(fileUrl: finalUrl)
                        }
                    } catch {
                        await MainActor.run {
                            self.updateTask(id: taskId) { t in
                                t.status = .failed
                                t.errorMessage = "合成失败: \(error.localizedDescription)"
                            }
                        }
                    }
                }
            }
        }
    }
    
    public func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let error = error, let desc = task.taskDescription else { return }
        let taskId = desc.split(separator: "|").first.map(String.init) ?? ""
        updateTask(id: taskId) { t in
            t.status = .failed
            t.errorMessage = error.localizedDescription
        }
    }
    
    private func sanitizeFileName(_ name: String) -> String {
        let invalid = CharacterSet(charactersIn: "\\/:*?\"<>|\r\n")
        let clean = name.components(separatedBy: invalid).joined(separator: "_")
        let trimmed = clean.trimmingCharacters(in: .whitespacesAndNewlines)
        return String(trimmed.prefix(60)).isEmpty ? "video_\(Int(Date().timeIntervalSince1970))" : String(trimmed.prefix(60))
    }
}
