package com.omni.downloader.ui.localization

import com.omni.downloader.engine.MediaErrorCode

private val errorCodeCopies = mapOf(
    "AUTH_REQUIRED" to listOf("需要先登入此平台，或載入有效的帳號工作階段（Cookie）後重試", "Sign in to this platform or import valid account cookies, then retry", "このプラットフォームにログインするか、有効なCookieを読み込んでから再試行してください"),
    "CHALLENGE_REQUIRED" to listOf("需要人機驗證，請在內建瀏覽器或對應帳號中完成驗證後重試", "Human verification is required; complete verification in the built-in browser and retry", "人間確認が必要です。内蔵ブラウザで認証を完了してから再試行してください"),
    "BROWSER_ASSIST_REQUIRED" to listOf("需要內建瀏覽器輔助載入目標頁面以完成解析", "Use the built-in browser to load this page and finish extracting media", "このページの抽出を完了するには内蔵ブラウザでの読み込みが必要です"),
    "COOKIE_DISABLED" to listOf("本站 Cookie 已停用，請先在「設定 → 帳號與工作階段」啟用後重試", "Cookies for this platform are disabled; enable them in Settings -> Accounts & Sessions and retry", "このサイトのCookieは無効です。「設定 → アカウントとセッション」で有効にして再試行してください"),
    "COOKIE_INVALID" to listOf("帳號工作階段或 Cookie 格式無效，請重新登入或匯入", "Account session or Cookie format is invalid; sign in again or re-import cookies", "アカウントセッションまたはCookieの形式が無効です。再ログインまたは再読み込みしてください"),
    "ACCESS_DENIED" to listOf("無權存取此內容，請確認帳號權限或購買狀態", "Access denied; verify your account permissions for this content", "アクセスが拒否されました。アカウント権限を確認してください"),
    "ACCESS_UNCONFIRMED" to listOf("此內容需要對應帳號權益，請登入並確認權限後重試", "Account entitlement is unconfirmed; sign in and verify access", "アカウント権限が未確認です。ログインして権限を確認してください"),
    "DRM_UNSUPPORTED" to listOf("目標內容受 DRM 版權保護，不支援匯出下載", "This content is DRM-protected and cannot be exported", "このコンテンツはDRMで保護されているため書き出せません"),
    "RATE_LIMITED" to listOf("平台請求過於頻繁，請稍後再試", "Rate-limited by the platform; please try again later", "プラットフォームからの制限がかかりました。しばらくしてから再試行してください"),
    "QUALITY_UNAVAILABLE" to listOf("所選畫質或格式目前無法取得，請切換其他畫質或重新解析", "Requested quality or format is unavailable; choose another option or re-analyze", "選択した画質または形式は利用できません。別の画質を選ぶか再解析してください"),
    "ENGINE_UNAVAILABLE" to listOf("本機解析與轉碼引擎尚未就緒，請重新檢查引擎或重啟應用程式", "Download/transcoding engine is unavailable; check engine status or restart the app", "エンジンが利用できません。状態を確認するかアプリを再起動してください"),
    "EXTRACTOR_CHANGED" to listOf("平台頁面結構可能已調整，無法擷取目標資料，可嘗試內建瀏覽器輔助", "Platform page structure changed and extraction failed; try built-in browser assist", "プラットフォームの仕様変更により抽出できませんでした。内蔵ブラウザをお試しください"),
    "NO_MEDIA" to listOf("未在連結中找到可下載的目標媒體", "No downloadable target media was found at this link", "このリンクからダウンロード可能なメディアが見つかりませんでした"),
    "MEDIA_UNCONFIRMED" to listOf("尚未能確認目標作品資料，請重試或使用內建瀏覽器開啟該作品頁", "Could not confirm the target post yet; retry or open it via built-in browser", "対象の作品を特定できませんでした。再試行するか内蔵ブラウザで開いてください"),
    "MEDIA_ID_MISMATCH" to listOf("頁面傳回的作品與目標連結不一致，請保持在原作品頁後重試", "Returned post does not match the requested ID; return to the target post and retry", "返された作品が要求したIDと一致しません。元の投稿ページに戻って再試行してください"),
    "CONTENT_UNAVAILABLE" to listOf("此內容已被刪除、設為私密或目前地區無法瀏覽", "Content is unavailable, deleted, private, or restricted in your region", "コンテンツは削除済み、非公開、または地域制限により利用できません"),
    "NETWORK_ERROR" to listOf("網路連線或代理逾時，請檢查網路或代理設定後重試", "Network or proxy timed out; check your connection or proxy settings and retry", "ネットワークまたはプロキシがタイムアウトしました。接続やプロキシを確認してください"),
    "SOURCE_EXPIRED" to listOf("媒體來源連結已過期或帳號已變更，請回到首頁重新解析", "Media source URL expired or session changed; re-analyze from Home", "メディアURLの有効期限切れかセッション変更が発生しました。再解析してください"),
    "POSTPROCESS_FAILED" to listOf("影音合併或轉碼處理失敗，請確認儲存空間後重試", "Video/audio merging or transcoding failed; check free storage and retry", "結合または変換処理に失敗しました。空き容量を確認して再試行してください"),
    "EXPORT_FAILED" to listOf("目標檔案或下載資料夾無法寫入，請檢查權限與儲存空間", "Unable to export to the download folder; check permissions and storage space", "保存先フォルダへの書き出しに失敗しました。権限と空き容量を確認してください"),
    "UNKNOWN" to listOf("解析或下載未能成功，請稍後重試", "Operation failed; please try again later", "処理に失敗しました。しばらくして再試行してください"),
)

private val exactErrorCopies = mapOf(
    "任务因应用退出已中止" to listOf("任務因應用程式退出已中止", "Task stopped because the app closed", "アプリ終了によりタスクが中断されました"),
    "抖音合集链接交由通用引擎展开" to listOf("抖音合輯連結交由通用引擎展開", "Delegating Douyin collection expansion to general engine", "抖音コレクションリンクを汎用エンジンで展開中"),
    "未能识别小黑盒内容 ID" to listOf("未能識別小黑盒內容 ID", "Could not identify Heybox content ID", "小黒盒のコンテンツIDを識別できません"),
    "未能取得小黑盒视频地址" to listOf("未能取得小黑盒視頻位址", "Could not obtain Heybox video URL", "小黒盒の動画URLを取得できません"),
    "未能识别 Pixiv 作品 ID" to listOf("未能識別 Pixiv 作品 ID", "Could not identify Pixiv artwork ID", "Pixiv作品IDを識別できません"),
    "Pixiv 作品信息与请求 ID 不一致" to listOf("Pixiv 作品資訊與請求 ID 不一致", "Pixiv artwork info mismatch", "Pixiv作品情報が要求IDと一致しません"),
    "任务缺少 Pixiv 作品 ID" to listOf("任務缺少 Pixiv 作品 ID", "Task is missing Pixiv artwork ID", "タスクにPixiv作品IDがありません"),
    "Ugoira 没有返回帧列表" to listOf("Ugoira 沒有傳回影格清單", "Ugoira returned no frame list", "Ugoiraのフレーム一覧が空です"),
    "Ugoira 导出失败" to listOf("Ugoira 匯出失敗", "Ugoira export failed", "Ugoiraの書き出しに失敗しました"),
)

private val taggedErrorRegex = Regex("^\\[([A-Z0-9_]+)\\]\\s*(.*)$")

/**
 * 将错误码标识和已知中文提示转换为当前界面语言，保留 [CODE] 诊断标签及技术明细。
 */
fun AppStrings.localizeError(value: String): String {
    if (value.isBlank()) return value
    val index = when (this) { ZhHantStrings -> 0; EnStrings -> 1; JaStrings -> 2; else -> return value }
    exactErrorCopies[value]?.get(index)?.let { return it }
    val progressMapped = progressText(value)
    if (progressMapped != value) return progressMapped
    val match = taggedErrorRegex.matchEntire(value.trim()) ?: return value
    val tag = match.groupValues[1]
    val rest = match.groupValues[2].trim()
    val localizedHint = errorCodeCopies[tag]?.get(index) ?: return value
    val entry = MediaErrorCode.entries.firstOrNull { it.code == tag }
    val suffix = when {
        rest.isEmpty() -> ""
        entry != null && rest.startsWith(entry.userHint) -> rest.removePrefix(entry.userHint).trimStart()
        rest.startsWith("(") -> rest
        else -> " ($rest)"
    }
    val cleanSuffix = if (suffix.startsWith("(") && suffix.endsWith(")")) {
        val inner = suffix.substring(1, suffix.length - 1).trim()
        val mapped = exactErrorCopies[inner]?.get(index) ?: progressText(inner).takeIf { it != inner }
        if (mapped != null) " ($mapped)" else if (suffix.isEmpty()) "" else " $suffix"
    } else if (suffix.isEmpty()) "" else " $suffix"
    return "[$tag] $localizedHint$cleanSuffix"
}
