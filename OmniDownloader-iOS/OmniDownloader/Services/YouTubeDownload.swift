import Foundation
import AVFoundation

enum YouTubeDownload {
    private final class ProgressDelegate: NSObject, URLSessionDownloadDelegate {
        let progress: (Float) -> Void
        init(_ progress: @escaping (Float) -> Void) { self.progress = progress }
        func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {}
        func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
            if totalBytesExpectedToWrite > 0 { progress(Float(totalBytesWritten) / Float(totalBytesExpectedToWrite)) }
        }
    }
    static func download(_ task: DownloadTask, progress: @escaping (Float) -> Void) async throws -> URL {
        guard let identifier = YouTubeSources.videoID(task.url) else { throw YouTubeCookies.failure("[MEDIA_ID_MISMATCH] YouTube 视频 ID 无效") }
        let snapshot = try YouTubeSources.snapshot(identifier)
        let sorted = snapshot.sources.values.sorted { $0.width * $0.height > $1.width * $1.height }
        let audio = snapshot.sources.values.filter { $0.mime.hasPrefix("audio/mp4") }.max { $0.bitrate < $1.bitrate }
        let itag = task.selectedVideoFormatId?.replacingOccurrences(of: "yt:", with: "")
        let source = itag.flatMap { snapshot.sources[$0] } ?? (itag == nil ? sorted.first(where: { $0.mime.hasPrefix("video/mp4") }) : nil)
        guard let source = source else { throw YouTubeCookies.failure("[QUALITY_UNAVAILABLE] 指定格式未取得完整资源，请重新播放并解析") }
        if ![DownloadType.videoWithAudio, .videoOnly, .audioOnly].contains(task.downloadType) { throw YouTubeCookies.failure("iOS YouTube 当前支持 MP4、仅画面和 M4A 音频") }
        if task.downloadType == .audioOnly && task.audioFormat != .m4a { throw YouTubeCookies.failure("iOS YouTube 音频请选择 M4A") }
        let root = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("youtube-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let config = URLSessionConfiguration.ephemeral
        config.httpCookieStorage = nil; config.httpShouldSetCookies = false
        config.timeoutIntervalForRequest = 45; config.timeoutIntervalForResource = 1800
        let session = URLSession(configuration: config)
        defer { session.invalidateAndCancel() }
        func fetch(_ item: YouTubeSource, _ name: String, _ offset: Float, _ scale: Float) async throws -> URL {
            try Task.checkCancellation()
            let request = URLRequest(url: item.url)
            let delegate = ProgressDelegate { progress(offset + $0 * scale) }
            let (temporary, response) = try await session.download(for: request, delegate: delegate)
            defer { try? FileManager.default.removeItem(at: temporary) }
            guard let http = response as? HTTPURLResponse, [200, 206].contains(http.statusCode) else {
                throw YouTubeCookies.failure("[SOURCE_EXPIRED] 资源请求被拒绝，请重新在原页面解析")
            }
            let size = try temporary.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
            guard Int64(size) == item.bytes else { throw YouTubeCookies.failure("[POSTPROCESS_FAILED] 响应长度不符，拒绝保存片段或错误页") }
            let handle = try FileHandle(forReadingFrom: temporary)
            defer { try? handle.close() }
            let prefix = try handle.read(upToCount: 64) ?? Data()
            guard prefix.count > 12, String(data: prefix.subdata(in: 4..<8), encoding: .ascii) == "ftyp" else {
                throw YouTubeCookies.failure("[POSTPROCESS_FAILED] 响应不是完整 MP4/AAC 容器")
            }
            let target = root.appendingPathComponent(name)
            try FileManager.default.copyItem(at: temporary, to: target)
            return target
        }
        let output = root.appendingPathComponent(task.downloadType == .audioOnly ? "result.m4a" : "result.mp4")
        if task.downloadType == .audioOnly {
            guard let selectedAudio = audio else { throw YouTubeCookies.failure("[NO_AUDIO] 页面未取得完整 AAC 音轨") }
            let file = try await fetch(selectedAudio, "audio.m4a", 0, 0.9)
            try FileManager.default.copyItem(at: file, to: output)
        } else {
            let video = try await fetch(source, "video.mp4", 0, source.hasAudio || task.downloadType == .videoOnly ? 0.9 : 0.7)
            if task.downloadType == .videoOnly {
                let asset = AVURLAsset(url: video), composition = AVMutableComposition()
                guard let track = try await asset.loadTracks(withMediaType: .video).first,
                      let result = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid) else { throw YouTubeCookies.failure("没有有效视频轨") }
                let duration = try await asset.load(.duration)
                try result.insertTimeRange(CMTimeRange(start: .zero, duration: duration), of: track, at: .zero)
                result.preferredTransform = try await track.load(.preferredTransform)
                guard let exporter = AVAssetExportSession(asset: composition, presetName: AVAssetExportPresetPassthrough) else { throw YouTubeCookies.failure("系统无法导出当前编码") }
                exporter.outputURL = output; exporter.outputFileType = .mp4
                await exporter.export()
                guard exporter.status == .completed else { throw YouTubeCookies.failure("[POSTPROCESS_FAILED] 系统未能完成仅画面导出") }
            } else if source.hasAudio { try FileManager.default.copyItem(at: video, to: output) }
            else {
                guard let audio = audio else { throw YouTubeCookies.failure("[NO_AUDIO] 请在网页播放并取得 AAC 音轨，或选择仅画面") }
                let sound = try await fetch(audio, "audio.m4a", 0.7, 0.2)
                try await AVMuxer.mergeVideoAndAudio(videoUrl: video, audioUrl: sound, outputUrl: output)
            }
        }
        try Task.checkCancellation()
        let asset = AVURLAsset(url: output)
        let duration = (try await asset.load(.duration)).seconds
        guard duration.isFinite, duration > 0, snapshot.duration <= 0 || abs(duration - snapshot.duration) < max(2, snapshot.duration * 0.01) else { throw YouTubeCookies.failure("[POSTPROCESS_FAILED] 成品时长与目标视频不符") }
        let audioTracks = try await asset.loadTracks(withMediaType: .audio)
        if task.downloadType == .videoWithAudio && audioTracks.isEmpty { throw YouTubeCookies.failure("[POSTPROCESS_FAILED] 成品缺少音轨") }
        if task.downloadType == .videoOnly && !audioTracks.isEmpty { throw YouTubeCookies.failure("[POSTPROCESS_FAILED] 仅画面成品仍含音轨") }
        if task.downloadType != .audioOnly {
            guard let track = try await asset.loadTracks(withMediaType: .video).first else { throw YouTubeCookies.failure("[POSTPROCESS_FAILED] 成品缺少视频轨") }
            let size = try await track.load(.naturalSize)
            guard Double(abs(size.width * size.height)) >= Double(source.width * source.height) * 0.98 else { throw YouTubeCookies.failure("[QUALITY_UNAVAILABLE] 成品低于所选源画质") }
        }
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let destination = docs.appendingPathComponent("YouTube-\(identifier)-\(UUID().uuidString).\(output.pathExtension)")
        try FileManager.default.copyItem(at: output, to: destination)
        progress(1)
        return destination
    }
}
