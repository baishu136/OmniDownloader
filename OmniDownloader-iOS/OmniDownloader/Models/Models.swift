import Foundation

/// 下载任务类型
public enum DownloadType: String, Codable, CaseIterable {
    case videoWithAudio = "VIDEO_WITH_AUDIO"
    case videoOnly = "VIDEO_ONLY"
    case audioOnly = "AUDIO_ONLY"
    case gif = "GIF"
    case cover = "COVER"
    case auto = "AUTO"
}

/// 音频输出格式
public enum AudioFormat: String, Codable, CaseIterable {
    case mp3 = "mp3"
    case m4a = "m4a"
    case flac = "flac"
    case wav = "wav"
    case aac = "aac"
    
    public var label: String {
        switch self {
        case .mp3: return "MP3 通用音频 (标准码率)"
        case .m4a: return "M4A 极速直出 (无损原轨)"
        case .flac: return "FLAC 无损音频 (高解析度)"
        case .wav: return "WAV 无损音频 (广播级)"
        case .aac: return "AAC 高压缩比"
        }
    }
}

/// 下载任务生命周期状态
public enum TaskStatus: String, Codable {
    case pending = "PENDING"
    case analyzing = "ANALYZING"
    case downloading = "DOWNLOADING"
    case processing = "PROCESSING"
    case completed = "COMPLETED"
    case failed = "FAILED"
    case paused = "PAUSED"
    case cancelled = "CANCELLED"
}

/// 清晰度格式选项模型
public struct FormatOption: Identifiable, Codable, Equatable {
    public var id: String { formatId }
    public let formatId: String
    public let resolutionLabel: String
    public let ext: String
    public let width: Int
    public let height: Int
    public let fps: Int
    public let approximateSize: String
    public let note: String
    public let isAudioOnly: Bool
    
    public init(
        formatId: String,
        resolutionLabel: String,
        ext: String = "mp4",
        width: Int = 0,
        height: Int = 0,
        fps: Int = 30,
        approximateSize: String = "",
        note: String = "",
        isAudioOnly: Bool = false
    ) {
        self.formatId = formatId
        self.resolutionLabel = resolutionLabel
        self.ext = ext
        self.width = width
        self.height = height
        self.fps = fps
        self.approximateSize = approximateSize
        self.note = note
        self.isAudioOnly = isAudioOnly
    }
}

/// 视频/合集元数据模型
public struct VideoMetadata: Identifiable, Codable {
    public var id: String { url }
    public let url: String
    public let title: String
    public let author: String
    public let durationText: String
    public let thumbnailUrl: String
    public let siteName: String
    public let isGif: Bool
    public var availableVideoFormats: [FormatOption]
    public var availableAudioFormats: [FormatOption]
    public var multiMediaList: [VideoMetadata]
    
    public init(
        url: String,
        title: String,
        author: String = "",
        durationText: String = "",
        thumbnailUrl: String = "",
        siteName: String = "",
        isGif: Bool = false,
        availableVideoFormats: [FormatOption] = [],
        availableAudioFormats: [FormatOption] = [],
        multiMediaList: [VideoMetadata] = []
    ) {
        self.url = url
        self.title = title
        self.author = author
        self.durationText = durationText
        self.thumbnailUrl = thumbnailUrl
        self.siteName = siteName
        self.isGif = isGif
        self.availableVideoFormats = availableVideoFormats
        self.availableAudioFormats = availableAudioFormats
        self.multiMediaList = multiMediaList
    }
}

/// 下载任务模型 (支持单任务与合集任务归属)
public struct DownloadTask: Identifiable, Codable, Equatable {
    public let id: String
    public let url: String
    public var title: String
    public var author: String
    public var thumbnailUrl: String
    public var downloadType: DownloadType
    public var selectedResolution: String
    public var audioFormat: AudioFormat
    public var status: TaskStatus
    public var progress: Float
    public var speed: String
    public var eta: String
    public var localFilePath: String?
    public var errorMessage: String?
    public var createdAt: Date
    
    // 合集下载关联字段
    public var collectionId: String?
    public var collectionTitle: String?
    public var episodeIndex: Int
    public var episodeTotal: Int
    
    public init(
        id: String = UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(12).lowercased(),
        url: String,
        title: String,
        author: String = "",
        thumbnailUrl: String = "",
        downloadType: DownloadType = .videoWithAudio,
        selectedResolution: String = "自适应最高画质 (推荐)",
        audioFormat: AudioFormat = .mp3,
        status: TaskStatus = .pending,
        progress: Float = 0,
        speed: String = "",
        eta: String = "",
        localFilePath: String? = nil,
        errorMessage: String? = nil,
        createdAt: Date = Date(),
        collectionId: String? = nil,
        collectionTitle: String? = nil,
        episodeIndex: Int = 0,
        episodeTotal: Int = 0
    ) {
        self.id = id
        self.url = url
        self.title = title
        self.author = author
        self.thumbnailUrl = thumbnailUrl
        self.downloadType = downloadType
        self.selectedResolution = selectedResolution
        self.audioFormat = audioFormat
        self.status = status
        self.progress = progress
        self.speed = speed
        self.eta = eta
        self.localFilePath = localFilePath
        self.errorMessage = errorMessage
        self.createdAt = createdAt
        self.collectionId = collectionId
        self.collectionTitle = collectionTitle
        self.episodeIndex = episodeIndex
        self.episodeTotal = episodeTotal
    }
}

/// 任务展示层包装模型 (支持单任务与合集聚合卡片展示)
public enum TaskDisplayItem: Identifiable {
    case single(task: DownloadTask)
    case collection(collectionId: String, collectionTitle: String, tasks: [DownloadTask])
    
    public var id: String {
        switch self {
        case .single(let task):
            return "single_\(task.id)"
        case .collection(let collectionId, _, _):
            return "col_\(collectionId)"
        }
    }
}

/// 第三方备用中转解析网站模型 (对齐 Android / Windows 端)
public struct RelaySite: Identifiable, Codable, Equatable {
    public let id: String
    public var name: String
    public var url: String
    public var iconUrl: String

    public init(
        id: String = String(UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(8)).lowercased(),
        name: String,
        url: String,
        iconUrl: String = ""
    ) {
        self.id = id
        self.name = name
        self.url = url
        self.iconUrl = iconUrl
    }
}
