import Foundation
import AVFoundation

/// 基于 iOS 硬件加速 AVFoundation 的毫秒级音画无损合成器 (免去第三方 FFmpeg 库，纯原生 100% 原始画质直出)
public class AVMuxer {
    
    /// 将独立下载的 DASH 视频流 (.m4s / .mp4) 与 音频轨 (.m4s / .m4a) 原生无损合成单个 MP4
    public static func mergeVideoAndAudio(
        videoUrl: URL,
        audioUrl: URL,
        outputUrl: URL
    ) async throws {
        // 清理已有目标文件
        if FileManager.default.fileExists(atPath: outputUrl.path) {
            try? FileManager.default.removeItem(at: outputUrl)
        }
        
        let videoAsset = AVURLAsset(url: videoUrl)
        let audioAsset = AVURLAsset(url: audioUrl)
        
        let composition = AVMutableComposition()
        
        // 1. 抽取并拼合视频轨
        if let videoTrack = composition.addMutableTrack(
            withMediaType: .video,
            preferredTrackID: kCMPersistentTrackID_Invalid
        ) {
            let videoAssetTracks = try await videoAsset.loadTracks(withMediaType: .video)
            if let assetTrack = videoAssetTracks.first {
                let duration = try await videoAsset.load(.duration)
                let timeRange = CMTimeRange(start: .zero, duration: duration)
                try videoTrack.insertTimeRange(timeRange, of: assetTrack, at: .zero)
                
                // 保持原画面旋转与矩阵方向
                let transform = try await assetTrack.load(.preferredTransform)
                videoTrack.preferredTransform = transform
            }
        }
        
        // 2. 抽取并拼合音频轨
        if let audioTrack = composition.addMutableTrack(
            withMediaType: .audio,
            preferredTrackID: kCMPersistentTrackID_Invalid
        ) {
            let audioAssetTracks = try await audioAsset.loadTracks(withMediaType: .audio)
            if let assetTrack = audioAssetTracks.first {
                let duration = try await audioAsset.load(.duration)
                let timeRange = CMTimeRange(start: .zero, duration: duration)
                try audioTrack.insertTimeRange(timeRange, of: assetTrack, at: .zero)
            }
        }
        
        // 3. 原生 Passthrough 无损极速导出 (1-2 秒完成，不重新编码)
        guard let exportSession = AVAssetExportSession(
            asset: composition,
            presetName: AVAssetExportPresetPassthrough
        ) else {
            throw NSError(domain: "AVMuxer", code: 500, userInfo: [NSLocalizedDescriptionKey: "无法初始化系统导出合成会话"])
        }
        
        exportSession.outputURL = outputUrl
        exportSession.outputFileType = .mp4
        exportSession.shouldOptimizeForNetworkUse = true
        
        await exportSession.export()
        
        if exportSession.status == .completed {
            return
        } else if let error = exportSession.error {
            throw error
        } else {
            throw NSError(domain: "AVMuxer", code: 501, userInfo: [NSLocalizedDescriptionKey: "音画合成未完成 (状态: \(exportSession.status.rawValue))"])
        }
    }
    
    /// 从视频中提取无损音频为 M4A
    public static func extractAudio(from videoUrl: URL, outputUrl: URL) async throws {
        if FileManager.default.fileExists(atPath: outputUrl.path) {
            try? FileManager.default.removeItem(at: outputUrl)
        }
        
        let asset = AVURLAsset(url: videoUrl)
        guard let exportSession = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetAppleM4A) else {
            throw NSError(domain: "AVMuxer", code: 500, userInfo: [NSLocalizedDescriptionKey: "初始化音频导出失败"])
        }
        
        exportSession.outputURL = outputUrl
        exportSession.outputFileType = .m4a
        await exportSession.export()
        
        if exportSession.status != .completed {
            throw exportSession.error ?? NSError(domain: "AVMuxer", code: 502, userInfo: [NSLocalizedDescriptionKey: "音频导出未完成"])
        }
    }
}
