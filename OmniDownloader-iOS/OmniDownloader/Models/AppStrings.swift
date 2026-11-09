import Foundation

/// 国际化文案常列表 (全中文支持)
public struct AppStrings {
    public static var current = AppStrings()
    
    // MARK: - 通用
    public var appName: String { OmniLocalization.text("OmniDownloader") }
    public var appearanceSection: String { OmniLocalization.text("外观") }
    public var appearanceSystem: String { OmniLocalization.text("跟随系统") }
    public var appearanceLight: String { OmniLocalization.text("浅色") }
    public var appearanceDark: String { OmniLocalization.text("深色") }
    public var confirm: String { OmniLocalization.text("确定") }
    public var cancel: String { OmniLocalization.text("取消") }
    public var delete: String { OmniLocalization.text("删除") }
    public var retry: String { OmniLocalization.text("重试") }
    public var play: String { OmniLocalization.text("播放") }
    public var share: String { OmniLocalization.text("分享") }
    public var save: String { OmniLocalization.text("保存") }
    public var copy: String { OmniLocalization.text("复制") }
    public var success: String { OmniLocalization.text("成功") }
    public var failed: String { OmniLocalization.text("失败") }
    public var errorTitle: String { OmniLocalization.text("提示") }
    public var unknownError: String { OmniLocalization.text("发生未知错误") }
    
    // MARK: - Tab 标签栏
    public var tabHome: String { OmniLocalization.text("解析") }
    public var tabTasks: String { OmniLocalization.text("下载") }
    public var tabSettings: String { OmniLocalization.text("设置") }
    
    // MARK: - 首页 Home
    public var homeTitle: String { OmniLocalization.text("OmniDownloader") }
    public var homeSubtitle: String { OmniLocalization.text("全网高清原画 · 原生直连极速下载") }
    public var inputPrompt: String { OmniLocalization.text("音视频链接") }
    public var inputPlaceholder: String { OmniLocalization.text("粘贴或输入视频/合集链接 (支持 B站/抖音/快手/小红书等)...") }
    public var clearAction: String { OmniLocalization.text("清空") }
    public var pasteAction: String { OmniLocalization.text("粘贴") }
    public var parseAction: String { OmniLocalization.text("立即解析") }
    public var parsing: String { OmniLocalization.text("正在解析中...") }
    public var analyzingPrompt: String { OmniLocalization.text("正在深度探测音视频流信息...") }
    public var clipboardDetectedTitle: String { OmniLocalization.text("检测到剪贴板链接") }
    public var quickDownload: String { OmniLocalization.text("直接下载") }
    public var pasteAndParse: String { OmniLocalization.text("粘贴解析") }
    public var supportedSites: String { OmniLocalization.text("支持的媒体平台") }
    public var tipsTitle: String { OmniLocalization.text("温馨小贴士") }
    public var tipsContent: String { OmniLocalization.text("1. B站支持 4K、1080P60 帧、DASH 分离流以及分P/UGC合集一键聚合下载。\n2. 抖音、快手、小红书均支持无水印原生高清原画直连提取。\n3. 下载并合成后的视频可自动存入系统《照片》相册中的专属相簿，方便随时查看与分享。") }
    
    // MARK: - 弹窗与格式选择
    public var downloadSingleMode: String { OmniLocalization.text("下载单集") }
    public var downloadCollectionMode: String { OmniLocalization.text("下载合集") }
    public var switchBilibiliPartPrompt: String { OmniLocalization.text("切换分P：") }
    public var switchMultiVideoPrompt: String { OmniLocalization.text("切换子视频：") }
    public var videoPartLabel: String { OmniLocalization.text("第%d集") }
    public var selectAll: String { OmniLocalization.text("全选") }
    public var invertSelection: String { OmniLocalization.text("反选") }
    public var deselectAll: String { OmniLocalization.text("取消全选") }
    public var selectedEpisodesCount: String { OmniLocalization.text("已选 %d / %d 集") }
    public var pleaseSelectEpisode: String { OmniLocalization.text("请勾选至少一个分集") }
    public var downloadType: String { OmniLocalization.text("下载模式") }
    public var saveCover: String { OmniLocalization.text("保存封面") }
    public var badgeCover: String { OmniLocalization.text("封面") }
    public var tabVideo: String { OmniLocalization.text("音画合流") }
    public var tabMute: String { OmniLocalization.text("纯画面") }
    public var tabAudio: String { OmniLocalization.text("提取音频") }
    public var tabGif: String { OmniLocalization.text("动态图") }
    public var selectResolutionWithAudio: String { OmniLocalization.text("选择视频分辨率 (自动合成最佳声音)") }
    public var selectResolutionMute: String { OmniLocalization.text("选择视频画面分辨率 (无声音)") }
    public var selectAudioFormat: String { OmniLocalization.text("选择音频格式 (无损原轨或通用格式)") }
    public var startDownload: String { OmniLocalization.text("开始下载") }
    public var batchDownloadVideos: String { OmniLocalization.text("批量下载 %d 个视频 (%@)") }
    public var batchDownloadAudio: String { OmniLocalization.text("批量下载 %d 个音频 (%@)") }
    public var batchDownloadGif: String { OmniLocalization.text("批量下载 %d 个动图") }
    public var downloadAllCount: String { OmniLocalization.text("下载全部 (%d个视频)") }
    public var switchToCollectionMode: String { OmniLocalization.text("批量下载全部分集 (%d集)") }
    
    // MARK: - 任务管理 Tasks
    public var title: String { OmniLocalization.text("下载管理") }
    public var tasksTitle: String { OmniLocalization.text("下载管理") }
    public var tabAll: String { OmniLocalization.text("全部") }
    public var tabDownloading: String { OmniLocalization.text("正在下载") }
    public var tabCompleted: String { OmniLocalization.text("已完成") }
    public var emptyDownloading: String { OmniLocalization.text("暂无正在下载的任务") }
    public var emptyCompleted: String { OmniLocalization.text("暂无已完成的任务") }
    public var emptyTasks: String { OmniLocalization.text("暂无下载任务") }
    public var emptyTasksSub: String { OmniLocalization.text("在首页解析视频后即可开始下载") }
    public var pauseAll: String { OmniLocalization.text("全部暂停") }
    public var resumeAll: String { OmniLocalization.text("全部继续") }
    public var clearAll: String { OmniLocalization.text("清空已完成") }
    public var clearAllConfirmTitle: String { OmniLocalization.text("清空确认") }
    public var clearAllConfirmMessage: String { OmniLocalization.text("确定要清空所有已下载完成的历史记录吗？（相册中的视频不会被删除）") }
    public var actionDelete: String { OmniLocalization.text("删除任务") }
    public var actionRetry: String { OmniLocalization.text("重试任务") }
    
    // 任务状态文案
    public var statusPending: String { OmniLocalization.text("等待下载") }
    public var statusDownloading: String { OmniLocalization.text("正在下载") }
    public var statusPaused: String { OmniLocalization.text("已暂停") }
    public var statusMuxing: String { OmniLocalization.text("正在无损合并音视频...") }
    public var statusSavingToAlbum: String { OmniLocalization.text("正在存入系统相册...") }
    public var statusCompleted: String { OmniLocalization.text("下载完成") }
    public var statusFailed: String { OmniLocalization.text("下载失败") }
    public var statusSavedToAlbum: String { OmniLocalization.text("已保存至系统《照片》相册") }
    
    // 合集文案
    public var collectionBadge: String { OmniLocalization.text("合集 · %d 集") }
    public var collectionOverallProgress: String { OmniLocalization.text("总进度：已完成 %d/%d 集 (%d%%)") }
    public var currentSubTaskDownloading: String { OmniLocalization.text("正在下载 (第%d/%d集)：%@") }
    public var viewEpisodeDetails: String { OmniLocalization.text("查看分集详情 (已完成 %d/%d)") }
    public var hideEpisodeDetails: String { OmniLocalization.text("收起分集详情") }
    public var playFirstEpisode: String { OmniLocalization.text("播放首集") }
    public var saveToAlbum: String { OmniLocalization.text("存入相册") }
    public var savedToAlbumSuccess: String { OmniLocalization.text("已成功存入系统《照片》相册") }
    public var openInFiles: String { OmniLocalization.text("在文件App中查看") }
    
    // MARK: - 设置 Settings
    public var settingsTitle: String { OmniLocalization.text("设置") }
    public var bilibiliSection: String { OmniLocalization.text("哔哩哔哩账号登录 (Cookie / SESSDATA)") }
    public var bilibiliLoginDesc: String { OmniLocalization.text("登录后免费解锁 B 站 1080P 高清、1080P60 帧及 4K 超清画质。未登录状态官方最高限制 480P。") }
    public var bilibiliLoggedIn: String { OmniLocalization.text("✓ 已登录 B 站账户") }
    public var bilibiliNotLoggedIn: String { OmniLocalization.text("当前为未登录游客状态 (最高限制 480P)") }
    public var bilibiliAutoLoginBtn: String { OmniLocalization.text("一键安全网页登录") }
    public var bilibiliLogout: String { OmniLocalization.text("退出登录") }
    public var downloadSettingsSection: String { OmniLocalization.text("下载与存储") }
    public var autoSaveToAlbum: String { OmniLocalization.text("自动存入系统《照片》相册") }
    public var autoSaveToAlbumDesc: String { OmniLocalization.text("下载并合成完成的视频或提取的音频封面将自动存入系统相册专属的“OmniDownloader”相簿。") }
    public var clearCache: String { OmniLocalization.text("清理临时缓存文件") }
    public var clearCacheSuccess: String { OmniLocalization.text("临时缓存已成功清理") }
    public var about: String { OmniLocalization.text("关于 OmniDownloader") }
    public var version: String { "1.7.19" }
    public var openSourceInfo: String { OmniLocalization.text("基于 SwiftUI + AVFoundation 纯原生构建，原生支持 Bilibili DASH 硬件极速混流。") }
}
