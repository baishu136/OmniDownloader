package com.omni.downloader.ui.localization

import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Locale

/**
 * 界面文案。字段数量已超过 JVM 方法参数上限，因此不再用逐个参数的构造函数，
 * 而是由 Builder 写入数组、对外只暴露只读 getter（调用方式仍是 strings.xxx）。
 */
class AppStrings internal constructor(private val values: Array<String>) {
    val navHome: String get() = values[0]
    val navTasks: String get() = values[1]
    val navSettings: String get() = values[2]
    val appSubtitle: String get() = values[3]
    val inputPlaceholder: String get() = values[4]
    val clear: String get() = values[5]
    val paste: String get() = values[6]
    val pasteClipboard: String get() = values[7]
    val startAnalyze: String get() = values[8]
    val analyzing: String get() = values[9]
    val activeTasksTitle: String get() = values[10]
    val viewAll: String get() = values[11]
    val analyzeFailedTitle: String get() = values[12]
    val proxyTip: String get() = values[13]
    val copyErrorLog: String get() = values[14]
    val errorLogCopied: String get() = values[15]
    val setProxy: String get() = values[16]
    val tasksTitle: String get() = values[17]
    val tabAll: String get() = values[18]
    val tabDownloading: String get() = values[19]
    val tabCompleted: String get() = values[20]
    val emptyAll: String get() = values[21]
    val emptyDownloading: String get() = values[22]
    val emptyCompleted: String get() = values[23]
    val clearCompleted: String get() = values[24]
    val clearTasksDialogTitle: String get() = values[25]
    val clearTasksDialogMessage: String get() = values[26]
    val clearDownloadingTitle: String get() = values[27]
    val clearDownloadingMessage: String get() = values[28]
    val confirmStopAndClean: String get() = values[29]
    val confirmClear: String get() = values[30]
    val openFolder: String get() = values[31]
    val play: String get() = values[32]
    val retry: String get() = values[33]
    val delete: String get() = values[34]
    val cancel: String get() = values[35]
    val settingsAppearanceTitle: String get() = values[36]
    val settingsAppearanceSummary: String get() = values[37]
    val settingsDownloadsTitle: String get() = values[38]
    val settingsDownloadsSummary: String get() = values[39]
    val settingsNetworkTitle: String get() = values[40]
    val settingsNetworkSummary: String get() = values[41]
    val settingsRelayTitle: String get() = values[42]
    val settingsRelaySummary: String get() = values[43]
    val settingsAccountsTitle: String get() = values[44]
    val settingsAccountsSummary: String get() = values[45]
    val settingsEngineTitle: String get() = values[46]
    val settingsEngineSummary: String get() = values[47]
    val settingsAboutTitle: String get() = values[48]
    val settingsAboutSummary: String get() = values[49]
    val navigateBack: String get() = values[50]
    val settingsTitle: String get() = values[51]
    val languageSection: String get() = values[52]
    val appearanceSection: String get() = values[53]
    val appearanceSystem: String get() = values[54]
    val appearanceLight: String get() = values[55]
    val appearanceDark: String get() = values[56]
    val languageZhCn: String get() = values[57]
    val languageZhTw: String get() = values[58]
    val languageEn: String get() = values[59]
    val languageJa: String get() = values[60]
    val proxySection: String get() = values[61]
    val proxyHint: String get() = values[62]
    val proxyPlaceholder: String get() = values[63]
    val applyProxy: String get() = values[64]
    val clearProxy: String get() = values[65]
    val proxySaved: String get() = values[66]
    val proxyCleared: String get() = values[67]
    val bilibiliCookieSection: String get() = values[68]
    val bilibiliCookieHint: String get() = values[69]
    val bilibiliCookieActive: String get() = values[70]
    val bilibiliCookiePlaceholder: String get() = values[71]
    val bilibiliCookieSaved: String get() = values[72]
    val bilibiliCookieCleared: String get() = values[73]
    val clearBilibiliCookie: String get() = values[74]
    val applyBilibiliCookie: String get() = values[75]
    val bilibiliCookieHelp: String get() = values[76]
    val bilibiliGuideTitle: String get() = values[77]
    val bilibiliGuideMessage: String get() = values[78]
    val bilibiliGuideLogin: String get() = values[79]
    val bilibiliGuideLater: String get() = values[80]
    val bilibiliAutoLoginBtn: String get() = values[81]
    val bilibiliLoginDialogTitle: String get() = values[82]
    val bilibiliLoginAutoDetecting: String get() = values[83]
    val bilibiliLoginSuccess: String get() = values[84]
    val bilibiliCloseDialog: String get() = values[85]
    val storageSection: String get() = values[86]
    val customPathHint: String get() = values[87]
    val defaultPath: String get() = values[88]
    val pathPlaceholder: String get() = values[89]
    val chooseFolder: String get() = values[90]
    val resetDefault: String get() = values[91]
    val pathSaved: String get() = values[92]
    val pathReset: String get() = values[93]
    val engineSection: String get() = values[94]
    val updateEngine: String get() = values[95]
    val updating: String get() = values[96]
    val engineHint: String get() = values[97]
    val appInfoSection: String get() = values[98]
    val appVersion: String get() = values[99]
    val formatSheetTitle: String get() = values[100]
    val mediaSelection: String get() = values[101]
    val downloadType: String get() = values[102]
    val typeVideo: String get() = values[103]
    val typeAudio: String get() = values[104]
    val resolution: String get() = values[105]
    val audioFormat: String get() = values[106]
    val startDownload: String get() = values[107]
    val downloadAll: String get() = values[108]
    val gifHint: String get() = values[109]
    val typeCover: String get() = values[110]
    val saveCover: String get() = values[111]
    val saveCoverSuccess: String get() = values[112]
    val coverDescription: String get() = values[113]
    val downloadSingleMode: String get() = values[114]
    val downloadCollectionMode: String get() = values[115]
    val selectAll: String get() = values[116]
    val deselectAll: String get() = values[117]
    val invertSelection: String get() = values[118]
    val selectedEpisodesCount: String get() = values[119]
    val downloadSelected: String get() = values[120]
    val pleaseSelectEpisode: String get() = values[121]
    val tabVideo: String get() = values[122]
    val tabVideoSub: String get() = values[123]
    val tabMute: String get() = values[124]
    val tabMuteSub: String get() = values[125]
    val tabAudio: String get() = values[126]
    val tabAudioSub: String get() = values[127]
    val tabGif: String get() = values[128]
    val tabGifSub: String get() = values[129]
    val attachedCoverTitle: String get() = values[130]
    val attachedCoverDesc: String get() = values[131]
    val saveCoverAction: String get() = values[132]
    val selectResolutionWithAudio: String get() = values[133]
    val selectResolutionMute: String get() = values[134]
    val selectAudioFormat: String get() = values[135]
    val gifEngineTitle: String get() = values[136]
    val gifEngineDesc: String get() = values[137]
    val switchMultiVideoPrompt: String get() = values[138]
    val switchBilibiliPartPrompt: String get() = values[139]
    val videoPartLabel: String get() = values[140]
    val batchDownloadAudio: String get() = values[141]
    val batchDownloadGif: String get() = values[142]
    val batchDownloadVideos: String get() = values[143]
    val downloadAllCount: String get() = values[144]
    val switchToCollectionMode: String get() = values[145]
    val engineStatusTitle: String get() = values[146]
    val engineStatusRunning: String get() = values[147]
    val engineStatusNotReady: String get() = values[148]
    val storageCleanTitle: String get() = values[149]
    val cleanCacheAction: String get() = values[150]
    val cleanCacheSuccess: String get() = values[151]
    val customBadge: String get() = values[152]
    val changeFolderDialogTitle: String get() = values[153]
    val changeFolderDialogHint: String get() = values[154]
    val selectViaFilePicker: String get() = values[155]
    val commonFolders: String get() = values[156]
    val folderDefault: String get() = values[157]
    val folderMovies: String get() = values[158]
    val folderMusic: String get() = values[159]
    val pathInputLabel: String get() = values[160]
    val saveSettings: String get() = values[161]
    val statusPending: String get() = values[162]
    val statusDownloading: String get() = values[163]
    val statusProcessing: String get() = values[164]
    val statusCompleted: String get() = values[165]
    val statusFailed: String get() = values[166]
    val statusCancelled: String get() = values[167]
    val badgeVideo: String get() = values[168]
    val badgeMute: String get() = values[169]
    val badgeAudio: String get() = values[170]
    val badgeGif: String get() = values[171]
    val badgeCover: String get() = values[172]
    val collectionBadge: String get() = values[173]
    val collectionEpisodesCount: String get() = values[174]
    val collectionTotalProgress: String get() = values[175]
    val collectionCurrentSubTask: String get() = values[176]
    val collectionAllCompleted: String get() = values[177]
    val collectionCancelAll: String get() = values[178]
    val collectionDelete: String get() = values[179]
    val collectionRetryFailed: String get() = values[180]
    val collectionPlayFirst: String get() = values[181]
    val collectionExpandDetails: String get() = values[182]
    val collectionCollapseDetails: String get() = values[183]
    val collectionDeleteConfirmTitle: String get() = values[184]
    val collectionDeleteConfirmMessage: String get() = values[185]
    val collectionCancelConfirmTitle: String get() = values[186]
    val collectionCancelConfirmMessage: String get() = values[187]
    val relaySitesSection: String get() = values[188]
    val relaySitesDesc: String get() = values[189]
    val addRelaySiteBtn: String get() = values[190]
    val relaySitesEmptySettings: String get() = values[191]
    val relaySitesEmptyHome: String get() = values[192]
    val goToSettings: String get() = values[193]
    val fetchingTitle: String get() = values[194]
    val fetchTitleFailed: String get() = values[195]
    val deleteRelaySiteConfirmTitle: String get() = values[196]
    val deleteRelaySiteConfirmMessage: String get() = values[197]
    val sessionSection: String get() = values[198]
    val sessionDesc: String get() = values[199]
    val sessionPlatformLabel: String get() = values[200]
    val sessionImport: String get() = values[201]
    val sessionClear: String get() = values[202]
    val sessionMissing: String get() = values[203]
    val sessionImported: String get() = values[204]
    val sessionNeedsAuth: String get() = values[205]
    val sessionImportSuccess: String get() = values[206]
    val sessionImportFailed: String get() = values[207]
    val sessionCleared: String get() = values[208]
    val sessionManualClearHint: String get() = values[209]
    val sessionManageGoogle: String get() = values[210]
    val sessionManageGoogleHint: String get() = values[211]
    val requestWarningTitle: String get() = values[212]
    val requestWarningBody: String get() = values[213]
    val requestWarningWait: String get() = values[214]
    val requestWarningConfirm: String get() = values[215]
    val requestWarningAccount: String get() = values[216]
    val sessionReverify: String get() = values[217]
    val googleAccountsTitle: String get() = values[218]
    val googleAccountsHint: String get() = values[219]
    val googleAddAccount: String get() = values[220]
    val googleAccountName: String get() = values[221]
    val googleCurrentAccount: String get() = values[222]
    val googleUseAccount: String get() = values[223]
    val googleOpenAccount: String get() = values[224]
    val googleSignedOut: String get() = values[225]
    val googleNoCredentials: String get() = values[226]
    val googleCredentials: String get() = values[227]
    val googleLogoutCurrent: String get() = values[228]
    val googleLogoutSelected: String get() = values[229]
    val googleSelectAll: String get() = values[230]
    val googleClearSelection: String get() = values[231]
    val googleLogoutTitle: String get() = values[232]
    val googleLogoutHint: String get() = values[233]
    val googleUnsupported: String get() = values[234]
    val googlePending: String get() = values[235]
    val googleOpenGoogle: String get() = values[236]
    val googleOpenPlatform: String get() = values[237]
    val googleSavePlatform: String get() = values[238]
    val googleSaved: String get() = values[239]
    val googleCleanupFinished: String get() = values[240]
    val googleNoAccounts: String get() = values[241]
    val googleLegacyAccount: String get() = values[242]
    val engineDiagnosticsTitle: String get() = values[243]
    val engineDiagnosticsUnavailable: String get() = values[244]
    val sessionLogin: String get() = values[245]
    val sessionLoginHint: String get() = values[246]
    val sessionLoginDone: String get() = values[247]
    val browserCaptureAction: String get() = values[248]
    val browserCaptureTitle: String get() = values[249]
    val browserCaptureHint: String get() = values[250]
    val navBatch: String get() = values[251]
    val manageXCookie: String get() = values[252]
    val importYoutubeCookie: String get() = values[253]
    val accountsAndSessions: String get() = values[254]
    val scrollToTop: String get() = values[255]
    val expandPlatforms: String get() = values[256]
    val collapsePlatforms: String get() = values[257]
    val platformLabelBilibili: String get() = values[258]
    val platformLabelDouyin: String get() = values[259]
    val platformLabelKuaishou: String get() = values[260]
    val platformLabelXiaohongshu: String get() = values[261]
    val platformLabelHeybox: String get() = values[262]
    val platformLabelPixiv: String get() = values[263]
    val platformLabelX: String get() = values[264]
    val platformLabelYoutube: String get() = values[265]
    val close: String get() = values[266]
    val open: String get() = values[267]
    val share: String get() = values[268]
    val save: String get() = values[269]
    val viewAction: String get() = values[270]
    val deleteContentDesc: String get() = values[271]
    val menuPaneTitle: String get() = values[272]
    val settingsGifSummary: String get() = values[273]
    val folderPermissionDenied: String get() = values[274]
    val engineFeedback: String get() = values[275]
    val proxyTestFailed: String get() = values[276]
    val proxyTesting: String get() = values[277]
    val proxyTestAction: String get() = values[278]
    val disableCookie: String get() = values[279]
    val disableCookieHint: String get() = values[280]
    val cookieDisabledRetained: String get() = values[281]
    val googleLoginHint: String get() = values[282]
    val imageDownloadPath: String get() = values[283]
    val followDefaultDownloadPath: String get() = values[284]
    val followDefaultPathAction: String get() = values[285]
    val chooseImageFolder: String get() = values[286]
    val relaySiteAdded: String get() = values[287]
    val relaySiteDeleted: String get() = values[288]
    val verifyImagesAndExtract: String get() = values[289]
    val statusAnalyzingShort: String get() = values[290]
    val statusCompletedShort: String get() = values[291]
    val statusIncompleteShort: String get() = values[292]
    val artistProgress: String get() = values[293]
    val stopAnalyzing: String get() = values[294]
    val continueAnalyzing: String get() = values[295]
    val xProfileReading: String get() = values[296]
    val xProfileComplete: String get() = values[297]
    val xProfileIncomplete: String get() = values[298]
    val selectedCount: String get() = values[299]
    val zipByPost: String get() = values[300]
    val zipByArtwork: String get() = values[301]
    val zipArchive: String get() = values[302]
    val ugoiraCoverHint: String get() = values[303]
    val downloadAnimation: String get() = values[304]
    val saveThisImage: String get() = values[305]
    val animationFormat: String get() = values[306]
    val originalFramesZip: String get() = values[307]
    val clearSelection: String get() = values[308]
    val savingEllipsis: String get() = values[309]
    val selectAnimationDesc: String get() = values[310]
    val selectImageDesc: String get() = values[311]
    val previewImageDesc: String get() = values[312]
    val imageDesc: String get() = values[313]
    val ugoiraIndexLabel: String get() = values[314]
    val imageIndexLabel: String get() = values[315]
    val batchTitle: String get() = values[316]
    val batchSmartMode: String get() = values[317]
    val batchManualMode: String get() = values[318]
    val batchInputManual: String get() = values[319]
    val batchInputSmart: String get() = values[320]
    val batchAddLink: String get() = values[321]
    val batchRecognizeAdd: String get() = values[322]
    val batchAllEpisodes: String get() = values[323]
    val batchStart: String get() = values[324]
    val batchStopAdding: String get() = values[325]
    val batchRetryPending: String get() = values[326]
    val batchProgressSummary: String get() = values[327]
    val batchViewTasks: String get() = values[328]
    val batchFilledCount: String get() = values[329]
    val batchClearInput: String get() = values[330]
    val batchAddedToQueue: String get() = values[331]
    val batchRemove: String get() = values[332]
    val batchHistoryTitle: String get() = values[333]
    val batchClearHistory: String get() = values[334]
    val batchWaiting: String get() = values[335]
    val batchSingleAnalyze: String get() = values[336]
    val batchClearLinksTitle: String get() = values[337]
    val batchClearLinksMessage: String get() = values[338]
    val batchClearHistoryTitle: String get() = values[339]
    val batchClearHistoryMessage: String get() = values[340]
    val clipboardEmpty: String get() = values[341]
    val batchTextTooLong: String get() = values[342]
    val clipboardReadFailed: String get() = values[343]
    val gifConvertLocal: String get() = values[344]
    val gifCannotReadVideo: String get() = values[345]
    val gifShareChooser: String get() = values[346]
    val gifNoApp: String get() = values[347]
    val gifChooseVideo: String get() = values[348]
    val gifBackgroundHint: String get() = values[349]
    val gifEmpty: String get() = values[350]
    val gifDeleteTitle: String get() = values[351]
    val gifDeleteMessage: String get() = values[352]
    val gifDeleteFailed: String get() = values[353]
    val gifCannotDeleteFile: String get() = values[354]
    val statusWaitingAuth: String get() = values[355]
    val imageOriginal: String get() = values[356]
    val imageZip: String get() = values[357]
    val coverOriginal: String get() = values[358]
    val retryExport: String get() = values[359]
    val coverContentDesc: String get() = values[360]
    val errorPrefix: String get() = values[361]
    val viewDetails: String get() = values[362]
    val failureLogTitle: String get() = values[363]
    val copyLog: String get() = values[364]
    val mergingTracks: String get() = values[365]
    val remainingTime: String get() = values[366]
    val fileMissing: String get() = values[367]
    val noPlayer: String get() = values[368]
    val shareVideo: String get() = values[369]
    val shareFailed: String get() = values[370]
    val deleteLocalFiles: String get() = values[371]
    val failedEpisodes: String get() = values[372]
    val preparing: String get() = values[373]
    val relayAddHint: String get() = values[374]
    val relayUrlLabel: String get() = values[375]
    val relayRefetchName: String get() = values[376]
    val relayClearUrl: String get() = values[377]
    val relayNameLabel: String get() = values[378]
    val relayNamePlaceholder: String get() = values[379]
    val relayClearName: String get() = values[380]
    val relayFillRequired: String get() = values[381]
    val confirmAdd: String get() = values[382]
    val relayCompatTitle: String get() = values[383]
    val relayCompatS1Title: String get() = values[384]
    val relayCompatS1Desc: String get() = values[385]
    val relayCompatS2Title: String get() = values[386]
    val relayCompatS2Desc: String get() = values[387]
    val relayCompatS3Title: String get() = values[388]
    val relayCompatS3Desc: String get() = values[389]
    val relayCompatAccept: String get() = values[390]
    val relayConnecting: String get() = values[391]
    val relayTimeout: String get() = values[392]
    val relayPageFailed: String get() = values[393]
    val relayPasteFirst: String get() = values[394]
    val relayExpandAll: String get() = values[395]
    val relayCollapseAll: String get() = values[396]
    val relaySuccess: String get() = values[397]
    val relayFallbackTitle: String get() = values[398]
    val relayDirect: String get() = values[399]
    val relayWindowDebug: String get() = values[400]
    val relayResolvingDefault: String get() = values[401]
    val relayBack: String get() = values[402]
    val relayFillLink: String get() = values[403]
    val relayRefresh: String get() = values[404]
    val relayOpenExternal: String get() = values[405]
    val relayExternalFailed: String get() = values[406]
    val douyinVideo: String get() = values[407]
    val douyinImages: String get() = values[408]
    val douyinCaptureNotice: String get() = values[409]
    val douyinVerifyNotice: String get() = values[410]
    val douyinBrowserTitle: String get() = values[411]
    val douyinCookieDisabled: String get() = values[412]
    val enableCookieContinue: String get() = values[413]
    val douyinNoId: String get() = values[414]
    val douyinNotTarget: String get() = values[415]
    val douyinPageLeft: String get() = values[416]
    val douyinExtractTimeout: String get() = values[417]
    val douyinStillNoTarget: String get() = values[418]
    val douyinIdMismatch: String get() = values[419]
    val douyinReading: String get() = values[420]
    val douyinExtract: String get() = values[421]
    val pixivLoginHint: String get() = values[422]
    val sessionNotCaptured: String get() = values[423]
    val desktopSite: String get() = values[424]
    val mobileSite: String get() = values[425]
    val switchToMobile: String get() = values[426]
    val switchToDesktop: String get() = values[427]
    val reloadLogin: String get() = values[428]
    val iHaveLoggedIn: String get() = values[429]
    val loginNotice: String get() = values[430]
    val cookieDisabledTitle: String get() = values[431]
    val cookieDisabledBody: String get() = values[432]
    val desktopSwitchLabel: String get() = values[433]
    val mobileSwitchLabel: String get() = values[434]
    val downloadCaptured: String get() = values[435]
    val imageInputLabel: String get() = values[436]
    val savePathDesc: String get() = values[437]
    val resetDefaultFolder: String get() = values[438]
    val analyzingEllipsis: String get() = values[439]
    val analyzeImages: String get() = values[440]
    val resolutionPending: String get() = values[441]
    val formatPending: String get() = values[442]
    val sizePending: String get() = values[443]
    val currentAccountAccess: String get() = values[444]
    val unverified: String get() = values[445]
    val browserMoreQuality: String get() = values[446]
    val gifBadge: String get() = values[447]
    val imagePreviewUnavailable: String get() = values[448]
    val previousImage: String get() = values[449]
    val nextImage: String get() = values[450]
    val zoomHint: String get() = values[451]
    val webProxyChanged: String get() = values[452]
    val webProxyUnsupported: String get() = values[453]
    val notifQueued: String get() = values[454]
    val notifPreparing: String get() = values[455]
    val notifRemaining: String get() = values[456]
    val notifDownloadError: String get() = values[457]
    val notifTaskError: String get() = values[458]
    val notifAnalyzingTitle: String get() = values[459]
    val notifAnalyzingText: String get() = values[460]
    val notifChannelName: String get() = values[461]
    val notifChannelDesc: String get() = values[462]
    val notifProcessing: String get() = values[463]
    val notifDownloading: String get() = values[464]
    val notifCompletedTitle: String get() = values[465]
    val audioLabelMp3: String get() = values[466]
    val audioLabelM4a: String get() = values[467]
    val audioLabelFlac: String get() = values[468]
    val audioLabelOpus: String get() = values[469]
    val vmClipboardNoLink: String get() = values[470]
    val vmClipboardUnreadable: String get() = values[471]
    val vmEnterLink: String get() = values[472]
    val vmArtistProgress: String get() = values[473]
    val vmImagesFound: String get() = values[474]
    val vmNoMediaResources: String get() = values[475]
    val vmAnalysisCancelled: String get() = values[476]
    val vmAnalysisFailedDetail: String get() = values[477]
    val vmSaveInProgress: String get() = values[478]
    val vmImageTaskLimit: String get() = values[479]
    val vmImageArchiveTitle: String get() = values[480]
    val vmImageSaveStartFailed: String get() = values[481]
    val vmAnalysisStopped: String get() = values[482]
    val vmPastedAnalyzing: String get() = values[483]
    val vmPastedVideoLink: String get() = values[484]
    val vmEnterVideoUrl: String get() = values[485]
    val vmRelayAddressHint: String get() = values[486]
    val vmRelayAddressDetail: String get() = values[487]
    val vmCollectionDetected: String get() = values[488]
    val vmCoverOriginal: String get() = values[489]
    val vmDefaultQuality: String get() = values[490]
    val vmCoverSuffix: String get() = values[491]
    val vmAddedQueueWithCover: String get() = values[492]
    val vmAddedQueue: String get() = values[493]
    val vmRelayVideoTitle: String get() = values[494]
    val vmRelayAuthor: String get() = values[495]
    val vmRelayDirectLink: String get() = values[496]
    val vmRelayCaptured: String get() = values[497]
    val vmNoCoverUrl: String get() = values[498]
    val vmCoverStarted: String get() = values[499]
    val vmSelectVideosFirst: String get() = values[500]
    val vmBestQuality: String get() = values[501]
    val vmCollectionTitle: String get() = values[502]
    val vmCollectionQueued: String get() = values[503]
    val vmDownloadStarted: String get() = values[504]
    val vmTaskCancelled: String get() = values[505]
    val vmDeletedWithFile: String get() = values[506]
    val vmRemovedFromList: String get() = values[507]
    val vmCollectionCancelled: String get() = values[508]
    val vmCollectionDeletedWithFile: String get() = values[509]
    val vmCollectionRemoved: String get() = values[510]
    val vmCollectionRetried: String get() = values[511]
    val vmClearedCount: String get() = values[512]
    val vmNothingToClear: String get() = values[513]
    val vmStoppedTasks: String get() = values[514]
    val vmEngineUpdated: String get() = values[515]
    val vmEngineUpdateFailed: String get() = values[516]
    val androidUpdateTitle: String get() = values[517]
    val androidUpdateBody: String get() = values[518]
    val androidUpdateRelease: String get() = values[519]
    val browserUnavailable: String get() = values[520]
    val platformLabelWeibo: String get() = values[521]
    val ytDlpPlatformsTitle: String get() = values[522]
    val ytDlpPlatformsDescription: String get() = values[523]
    val ytDlpPlatformsSearch: String get() = values[524]
    val ytDlpPlatformsNoResults: String get() = values[525]
    val ytDlpPlatformsUnavailable: String get() = values[526]
    val platformLabelXigua: String get() = values[527]
    val platformLabelYouku: String get() = values[528]
    val platformLabelIqiyi: String get() = values[529]
    val platformLabelHuya: String get() = values[530]
    val analyzeClipboard: String get() = values[531]
}

private class AppStringsBuilder {
    lateinit var navHome: String
    lateinit var navTasks: String
    lateinit var navSettings: String
    lateinit var appSubtitle: String
    lateinit var inputPlaceholder: String
    lateinit var clear: String
    lateinit var paste: String
    lateinit var pasteClipboard: String
    lateinit var startAnalyze: String
    lateinit var analyzing: String
    lateinit var activeTasksTitle: String
    lateinit var viewAll: String
    lateinit var analyzeFailedTitle: String
    lateinit var proxyTip: String
    lateinit var copyErrorLog: String
    lateinit var errorLogCopied: String
    lateinit var setProxy: String
    lateinit var tasksTitle: String
    lateinit var tabAll: String
    lateinit var tabDownloading: String
    lateinit var tabCompleted: String
    lateinit var emptyAll: String
    lateinit var emptyDownloading: String
    lateinit var emptyCompleted: String
    lateinit var clearCompleted: String
    lateinit var clearTasksDialogTitle: String
    lateinit var clearTasksDialogMessage: String
    lateinit var clearDownloadingTitle: String
    lateinit var clearDownloadingMessage: String
    lateinit var confirmStopAndClean: String
    lateinit var confirmClear: String
    lateinit var openFolder: String
    lateinit var play: String
    lateinit var retry: String
    lateinit var delete: String
    lateinit var cancel: String
    lateinit var settingsAppearanceTitle: String
    lateinit var settingsAppearanceSummary: String
    lateinit var settingsDownloadsTitle: String
    lateinit var settingsDownloadsSummary: String
    lateinit var settingsNetworkTitle: String
    lateinit var settingsNetworkSummary: String
    lateinit var settingsRelayTitle: String
    lateinit var settingsRelaySummary: String
    lateinit var settingsAccountsTitle: String
    lateinit var settingsAccountsSummary: String
    lateinit var settingsEngineTitle: String
    lateinit var settingsEngineSummary: String
    lateinit var settingsAboutTitle: String
    lateinit var settingsAboutSummary: String
    lateinit var navigateBack: String
    lateinit var settingsTitle: String
    lateinit var languageSection: String
    lateinit var appearanceSection: String
    lateinit var appearanceSystem: String
    lateinit var appearanceLight: String
    lateinit var appearanceDark: String
    lateinit var languageZhCn: String
    lateinit var languageZhTw: String
    lateinit var languageEn: String
    lateinit var languageJa: String
    lateinit var proxySection: String
    lateinit var proxyHint: String
    lateinit var proxyPlaceholder: String
    lateinit var applyProxy: String
    lateinit var clearProxy: String
    lateinit var proxySaved: String
    lateinit var proxyCleared: String
    lateinit var bilibiliCookieSection: String
    lateinit var bilibiliCookieHint: String
    lateinit var bilibiliCookieActive: String
    lateinit var bilibiliCookiePlaceholder: String
    lateinit var bilibiliCookieSaved: String
    lateinit var bilibiliCookieCleared: String
    lateinit var clearBilibiliCookie: String
    lateinit var applyBilibiliCookie: String
    lateinit var bilibiliCookieHelp: String
    lateinit var bilibiliGuideTitle: String
    lateinit var bilibiliGuideMessage: String
    lateinit var bilibiliGuideLogin: String
    lateinit var bilibiliGuideLater: String
    lateinit var bilibiliAutoLoginBtn: String
    lateinit var bilibiliLoginDialogTitle: String
    lateinit var bilibiliLoginAutoDetecting: String
    lateinit var bilibiliLoginSuccess: String
    lateinit var bilibiliCloseDialog: String
    lateinit var storageSection: String
    lateinit var customPathHint: String
    lateinit var defaultPath: String
    lateinit var pathPlaceholder: String
    lateinit var chooseFolder: String
    lateinit var resetDefault: String
    lateinit var pathSaved: String
    lateinit var pathReset: String
    lateinit var engineSection: String
    lateinit var updateEngine: String
    lateinit var updating: String
    lateinit var engineHint: String
    lateinit var appInfoSection: String
    lateinit var appVersion: String
    lateinit var formatSheetTitle: String
    lateinit var mediaSelection: String
    lateinit var downloadType: String
    lateinit var typeVideo: String
    lateinit var typeAudio: String
    lateinit var resolution: String
    lateinit var audioFormat: String
    lateinit var startDownload: String
    lateinit var downloadAll: String
    lateinit var gifHint: String
    lateinit var typeCover: String
    lateinit var saveCover: String
    lateinit var saveCoverSuccess: String
    lateinit var coverDescription: String
    lateinit var downloadSingleMode: String
    lateinit var downloadCollectionMode: String
    lateinit var selectAll: String
    lateinit var deselectAll: String
    lateinit var invertSelection: String
    lateinit var selectedEpisodesCount: String
    lateinit var downloadSelected: String
    lateinit var pleaseSelectEpisode: String
    lateinit var tabVideo: String
    lateinit var tabVideoSub: String
    lateinit var tabMute: String
    lateinit var tabMuteSub: String
    lateinit var tabAudio: String
    lateinit var tabAudioSub: String
    lateinit var tabGif: String
    lateinit var tabGifSub: String
    lateinit var attachedCoverTitle: String
    lateinit var attachedCoverDesc: String
    lateinit var saveCoverAction: String
    lateinit var selectResolutionWithAudio: String
    lateinit var selectResolutionMute: String
    lateinit var selectAudioFormat: String
    lateinit var gifEngineTitle: String
    lateinit var gifEngineDesc: String
    lateinit var switchMultiVideoPrompt: String
    lateinit var switchBilibiliPartPrompt: String
    lateinit var videoPartLabel: String
    lateinit var batchDownloadAudio: String
    lateinit var batchDownloadGif: String
    lateinit var batchDownloadVideos: String
    lateinit var downloadAllCount: String
    lateinit var switchToCollectionMode: String
    lateinit var engineStatusTitle: String
    lateinit var engineStatusRunning: String
    lateinit var engineStatusNotReady: String
    lateinit var storageCleanTitle: String
    lateinit var cleanCacheAction: String
    lateinit var cleanCacheSuccess: String
    lateinit var customBadge: String
    lateinit var changeFolderDialogTitle: String
    lateinit var changeFolderDialogHint: String
    lateinit var selectViaFilePicker: String
    lateinit var commonFolders: String
    lateinit var folderDefault: String
    lateinit var folderMovies: String
    lateinit var folderMusic: String
    lateinit var pathInputLabel: String
    lateinit var saveSettings: String
    lateinit var statusPending: String
    lateinit var statusDownloading: String
    lateinit var statusProcessing: String
    lateinit var statusCompleted: String
    lateinit var statusFailed: String
    lateinit var statusCancelled: String
    lateinit var badgeVideo: String
    lateinit var badgeMute: String
    lateinit var badgeAudio: String
    lateinit var badgeGif: String
    lateinit var badgeCover: String
    lateinit var collectionBadge: String
    lateinit var collectionEpisodesCount: String
    lateinit var collectionTotalProgress: String
    lateinit var collectionCurrentSubTask: String
    lateinit var collectionAllCompleted: String
    lateinit var collectionCancelAll: String
    lateinit var collectionDelete: String
    lateinit var collectionRetryFailed: String
    lateinit var collectionPlayFirst: String
    lateinit var collectionExpandDetails: String
    lateinit var collectionCollapseDetails: String
    lateinit var collectionDeleteConfirmTitle: String
    lateinit var collectionDeleteConfirmMessage: String
    lateinit var collectionCancelConfirmTitle: String
    lateinit var collectionCancelConfirmMessage: String
    lateinit var relaySitesSection: String
    lateinit var relaySitesDesc: String
    lateinit var addRelaySiteBtn: String
    lateinit var relaySitesEmptySettings: String
    lateinit var relaySitesEmptyHome: String
    lateinit var goToSettings: String
    lateinit var fetchingTitle: String
    lateinit var fetchTitleFailed: String
    lateinit var deleteRelaySiteConfirmTitle: String
    lateinit var deleteRelaySiteConfirmMessage: String
    lateinit var sessionSection: String
    lateinit var sessionDesc: String
    lateinit var sessionPlatformLabel: String
    lateinit var sessionImport: String
    lateinit var sessionClear: String
    lateinit var sessionMissing: String
    lateinit var sessionImported: String
    lateinit var sessionNeedsAuth: String
    lateinit var sessionImportSuccess: String
    lateinit var sessionImportFailed: String
    lateinit var sessionCleared: String
    lateinit var sessionManualClearHint: String
    lateinit var sessionManageGoogle: String
    lateinit var sessionManageGoogleHint: String
    lateinit var requestWarningTitle: String
    lateinit var requestWarningBody: String
    lateinit var requestWarningWait: String
    lateinit var requestWarningConfirm: String
    lateinit var requestWarningAccount: String
    lateinit var sessionReverify: String
    lateinit var googleAccountsTitle: String
    lateinit var googleAccountsHint: String
    lateinit var googleAddAccount: String
    lateinit var googleAccountName: String
    lateinit var googleCurrentAccount: String
    lateinit var googleUseAccount: String
    lateinit var googleOpenAccount: String
    lateinit var googleSignedOut: String
    lateinit var googleNoCredentials: String
    lateinit var googleCredentials: String
    lateinit var googleLogoutCurrent: String
    lateinit var googleLogoutSelected: String
    lateinit var googleSelectAll: String
    lateinit var googleClearSelection: String
    lateinit var googleLogoutTitle: String
    lateinit var googleLogoutHint: String
    lateinit var googleUnsupported: String
    lateinit var googlePending: String
    lateinit var googleOpenGoogle: String
    lateinit var googleOpenPlatform: String
    lateinit var googleSavePlatform: String
    lateinit var googleSaved: String
    lateinit var googleCleanupFinished: String
    lateinit var googleNoAccounts: String
    lateinit var googleLegacyAccount: String
    lateinit var engineDiagnosticsTitle: String
    lateinit var engineDiagnosticsUnavailable: String
    lateinit var sessionLogin: String
    lateinit var sessionLoginHint: String
    lateinit var sessionLoginDone: String
    lateinit var browserCaptureAction: String
    lateinit var browserCaptureTitle: String
    lateinit var browserCaptureHint: String
    lateinit var navBatch: String
    lateinit var manageXCookie: String
    lateinit var importYoutubeCookie: String
    lateinit var accountsAndSessions: String
    lateinit var scrollToTop: String
    lateinit var expandPlatforms: String
    lateinit var collapsePlatforms: String
    lateinit var platformLabelBilibili: String
    lateinit var platformLabelDouyin: String
    lateinit var platformLabelKuaishou: String
    lateinit var platformLabelXiaohongshu: String
    lateinit var platformLabelHeybox: String
    lateinit var platformLabelPixiv: String
    lateinit var platformLabelX: String
    lateinit var platformLabelYoutube: String
    lateinit var close: String
    lateinit var open: String
    lateinit var share: String
    lateinit var save: String
    lateinit var viewAction: String
    lateinit var deleteContentDesc: String
    lateinit var menuPaneTitle: String
    lateinit var settingsGifSummary: String
    lateinit var folderPermissionDenied: String
    lateinit var engineFeedback: String
    lateinit var proxyTestFailed: String
    lateinit var proxyTesting: String
    lateinit var proxyTestAction: String
    lateinit var disableCookie: String
    lateinit var disableCookieHint: String
    lateinit var cookieDisabledRetained: String
    lateinit var googleLoginHint: String
    lateinit var imageDownloadPath: String
    lateinit var followDefaultDownloadPath: String
    lateinit var followDefaultPathAction: String
    lateinit var chooseImageFolder: String
    lateinit var relaySiteAdded: String
    lateinit var relaySiteDeleted: String
    lateinit var verifyImagesAndExtract: String
    lateinit var statusAnalyzingShort: String
    lateinit var statusCompletedShort: String
    lateinit var statusIncompleteShort: String
    lateinit var artistProgress: String
    lateinit var stopAnalyzing: String
    lateinit var continueAnalyzing: String
    lateinit var xProfileReading: String
    lateinit var xProfileComplete: String
    lateinit var xProfileIncomplete: String
    lateinit var selectedCount: String
    lateinit var zipByPost: String
    lateinit var zipByArtwork: String
    lateinit var zipArchive: String
    lateinit var ugoiraCoverHint: String
    lateinit var downloadAnimation: String
    lateinit var saveThisImage: String
    lateinit var animationFormat: String
    lateinit var originalFramesZip: String
    lateinit var clearSelection: String
    lateinit var savingEllipsis: String
    lateinit var selectAnimationDesc: String
    lateinit var selectImageDesc: String
    lateinit var previewImageDesc: String
    lateinit var imageDesc: String
    lateinit var ugoiraIndexLabel: String
    lateinit var imageIndexLabel: String
    lateinit var batchTitle: String
    lateinit var batchSmartMode: String
    lateinit var batchManualMode: String
    lateinit var batchInputManual: String
    lateinit var batchInputSmart: String
    lateinit var batchAddLink: String
    lateinit var batchRecognizeAdd: String
    lateinit var batchAllEpisodes: String
    lateinit var batchStart: String
    lateinit var batchStopAdding: String
    lateinit var batchRetryPending: String
    lateinit var batchProgressSummary: String
    lateinit var batchViewTasks: String
    lateinit var batchFilledCount: String
    lateinit var batchClearInput: String
    lateinit var batchAddedToQueue: String
    lateinit var batchRemove: String
    lateinit var batchHistoryTitle: String
    lateinit var batchClearHistory: String
    lateinit var batchWaiting: String
    lateinit var batchSingleAnalyze: String
    lateinit var batchClearLinksTitle: String
    lateinit var batchClearLinksMessage: String
    lateinit var batchClearHistoryTitle: String
    lateinit var batchClearHistoryMessage: String
    lateinit var clipboardEmpty: String
    lateinit var batchTextTooLong: String
    lateinit var clipboardReadFailed: String
    lateinit var gifConvertLocal: String
    lateinit var gifCannotReadVideo: String
    lateinit var gifShareChooser: String
    lateinit var gifNoApp: String
    lateinit var gifChooseVideo: String
    lateinit var gifBackgroundHint: String
    lateinit var gifEmpty: String
    lateinit var gifDeleteTitle: String
    lateinit var gifDeleteMessage: String
    lateinit var gifDeleteFailed: String
    lateinit var gifCannotDeleteFile: String
    lateinit var statusWaitingAuth: String
    lateinit var imageOriginal: String
    lateinit var imageZip: String
    lateinit var coverOriginal: String
    lateinit var retryExport: String
    lateinit var coverContentDesc: String
    lateinit var errorPrefix: String
    lateinit var viewDetails: String
    lateinit var failureLogTitle: String
    lateinit var copyLog: String
    lateinit var mergingTracks: String
    lateinit var remainingTime: String
    lateinit var fileMissing: String
    lateinit var noPlayer: String
    lateinit var shareVideo: String
    lateinit var shareFailed: String
    lateinit var deleteLocalFiles: String
    lateinit var failedEpisodes: String
    lateinit var preparing: String
    lateinit var relayAddHint: String
    lateinit var relayUrlLabel: String
    lateinit var relayRefetchName: String
    lateinit var relayClearUrl: String
    lateinit var relayNameLabel: String
    lateinit var relayNamePlaceholder: String
    lateinit var relayClearName: String
    lateinit var relayFillRequired: String
    lateinit var confirmAdd: String
    lateinit var relayCompatTitle: String
    lateinit var relayCompatS1Title: String
    lateinit var relayCompatS1Desc: String
    lateinit var relayCompatS2Title: String
    lateinit var relayCompatS2Desc: String
    lateinit var relayCompatS3Title: String
    lateinit var relayCompatS3Desc: String
    lateinit var relayCompatAccept: String
    lateinit var relayConnecting: String
    lateinit var relayTimeout: String
    lateinit var relayPageFailed: String
    lateinit var relayPasteFirst: String
    lateinit var relayExpandAll: String
    lateinit var relayCollapseAll: String
    lateinit var relaySuccess: String
    lateinit var relayFallbackTitle: String
    lateinit var relayDirect: String
    lateinit var relayWindowDebug: String
    lateinit var relayResolvingDefault: String
    lateinit var relayBack: String
    lateinit var relayFillLink: String
    lateinit var relayRefresh: String
    lateinit var relayOpenExternal: String
    lateinit var relayExternalFailed: String
    lateinit var douyinVideo: String
    lateinit var douyinImages: String
    lateinit var douyinCaptureNotice: String
    lateinit var douyinVerifyNotice: String
    lateinit var douyinBrowserTitle: String
    lateinit var douyinCookieDisabled: String
    lateinit var enableCookieContinue: String
    lateinit var douyinNoId: String
    lateinit var douyinNotTarget: String
    lateinit var douyinPageLeft: String
    lateinit var douyinExtractTimeout: String
    lateinit var douyinStillNoTarget: String
    lateinit var douyinIdMismatch: String
    lateinit var douyinReading: String
    lateinit var douyinExtract: String
    lateinit var pixivLoginHint: String
    lateinit var sessionNotCaptured: String
    lateinit var desktopSite: String
    lateinit var mobileSite: String
    lateinit var switchToMobile: String
    lateinit var switchToDesktop: String
    lateinit var reloadLogin: String
    lateinit var iHaveLoggedIn: String
    lateinit var loginNotice: String
    lateinit var cookieDisabledTitle: String
    lateinit var cookieDisabledBody: String
    lateinit var desktopSwitchLabel: String
    lateinit var mobileSwitchLabel: String
    lateinit var downloadCaptured: String
    lateinit var imageInputLabel: String
    lateinit var savePathDesc: String
    lateinit var resetDefaultFolder: String
    lateinit var analyzingEllipsis: String
    lateinit var analyzeImages: String
    lateinit var resolutionPending: String
    lateinit var formatPending: String
    lateinit var sizePending: String
    lateinit var currentAccountAccess: String
    lateinit var unverified: String
    lateinit var browserMoreQuality: String
    lateinit var gifBadge: String
    lateinit var imagePreviewUnavailable: String
    lateinit var previousImage: String
    lateinit var nextImage: String
    lateinit var zoomHint: String
    lateinit var webProxyChanged: String
    lateinit var webProxyUnsupported: String
    lateinit var notifQueued: String
    lateinit var notifPreparing: String
    lateinit var notifRemaining: String
    lateinit var notifDownloadError: String
    lateinit var notifTaskError: String
    lateinit var notifAnalyzingTitle: String
    lateinit var notifAnalyzingText: String
    lateinit var notifChannelName: String
    lateinit var notifChannelDesc: String
    lateinit var notifProcessing: String
    lateinit var notifDownloading: String
    lateinit var notifCompletedTitle: String
    lateinit var audioLabelMp3: String
    lateinit var audioLabelM4a: String
    lateinit var audioLabelFlac: String
    lateinit var audioLabelOpus: String
    lateinit var vmClipboardNoLink: String
    lateinit var vmClipboardUnreadable: String
    lateinit var vmEnterLink: String
    lateinit var vmArtistProgress: String
    lateinit var vmImagesFound: String
    lateinit var vmNoMediaResources: String
    lateinit var vmAnalysisCancelled: String
    lateinit var vmAnalysisFailedDetail: String
    lateinit var vmSaveInProgress: String
    lateinit var vmImageTaskLimit: String
    lateinit var vmImageArchiveTitle: String
    lateinit var vmImageSaveStartFailed: String
    lateinit var vmAnalysisStopped: String
    lateinit var vmPastedAnalyzing: String
    lateinit var vmPastedVideoLink: String
    lateinit var vmEnterVideoUrl: String
    lateinit var vmRelayAddressHint: String
    lateinit var vmRelayAddressDetail: String
    lateinit var vmCollectionDetected: String
    lateinit var vmCoverOriginal: String
    lateinit var vmDefaultQuality: String
    lateinit var vmCoverSuffix: String
    lateinit var vmAddedQueueWithCover: String
    lateinit var vmAddedQueue: String
    lateinit var vmRelayVideoTitle: String
    lateinit var vmRelayAuthor: String
    lateinit var vmRelayDirectLink: String
    lateinit var vmRelayCaptured: String
    lateinit var vmNoCoverUrl: String
    lateinit var vmCoverStarted: String
    lateinit var vmSelectVideosFirst: String
    lateinit var vmBestQuality: String
    lateinit var vmCollectionTitle: String
    lateinit var vmCollectionQueued: String
    lateinit var vmDownloadStarted: String
    lateinit var vmTaskCancelled: String
    lateinit var vmDeletedWithFile: String
    lateinit var vmRemovedFromList: String
    lateinit var vmCollectionCancelled: String
    lateinit var vmCollectionDeletedWithFile: String
    lateinit var vmCollectionRemoved: String
    lateinit var vmCollectionRetried: String
    lateinit var vmClearedCount: String
    lateinit var vmNothingToClear: String
    lateinit var vmStoppedTasks: String
    lateinit var vmEngineUpdated: String
    lateinit var vmEngineUpdateFailed: String
    lateinit var androidUpdateTitle: String
    lateinit var androidUpdateBody: String
    lateinit var androidUpdateRelease: String
    lateinit var browserUnavailable: String
    lateinit var platformLabelWeibo: String
    lateinit var ytDlpPlatformsTitle: String
    lateinit var ytDlpPlatformsDescription: String
    lateinit var ytDlpPlatformsSearch: String
    lateinit var ytDlpPlatformsNoResults: String
    lateinit var ytDlpPlatformsUnavailable: String
    lateinit var platformLabelXigua: String
    lateinit var platformLabelYouku: String
    lateinit var platformLabelIqiyi: String
    lateinit var platformLabelHuya: String
    lateinit var analyzeClipboard: String

    fun build(): AppStrings = AppStrings(arrayOf(
        navHome,
        navTasks,
        navSettings,
        appSubtitle,
        inputPlaceholder,
        clear,
        paste,
        pasteClipboard,
        startAnalyze,
        analyzing,
        activeTasksTitle,
        viewAll,
        analyzeFailedTitle,
        proxyTip,
        copyErrorLog,
        errorLogCopied,
        setProxy,
        tasksTitle,
        tabAll,
        tabDownloading,
        tabCompleted,
        emptyAll,
        emptyDownloading,
        emptyCompleted,
        clearCompleted,
        clearTasksDialogTitle,
        clearTasksDialogMessage,
        clearDownloadingTitle,
        clearDownloadingMessage,
        confirmStopAndClean,
        confirmClear,
        openFolder,
        play,
        retry,
        delete,
        cancel,
        settingsAppearanceTitle,
        settingsAppearanceSummary,
        settingsDownloadsTitle,
        settingsDownloadsSummary,
        settingsNetworkTitle,
        settingsNetworkSummary,
        settingsRelayTitle,
        settingsRelaySummary,
        settingsAccountsTitle,
        settingsAccountsSummary,
        settingsEngineTitle,
        settingsEngineSummary,
        settingsAboutTitle,
        settingsAboutSummary,
        navigateBack,
        settingsTitle,
        languageSection,
        appearanceSection,
        appearanceSystem,
        appearanceLight,
        appearanceDark,
        languageZhCn,
        languageZhTw,
        languageEn,
        languageJa,
        proxySection,
        proxyHint,
        proxyPlaceholder,
        applyProxy,
        clearProxy,
        proxySaved,
        proxyCleared,
        bilibiliCookieSection,
        bilibiliCookieHint,
        bilibiliCookieActive,
        bilibiliCookiePlaceholder,
        bilibiliCookieSaved,
        bilibiliCookieCleared,
        clearBilibiliCookie,
        applyBilibiliCookie,
        bilibiliCookieHelp,
        bilibiliGuideTitle,
        bilibiliGuideMessage,
        bilibiliGuideLogin,
        bilibiliGuideLater,
        bilibiliAutoLoginBtn,
        bilibiliLoginDialogTitle,
        bilibiliLoginAutoDetecting,
        bilibiliLoginSuccess,
        bilibiliCloseDialog,
        storageSection,
        customPathHint,
        defaultPath,
        pathPlaceholder,
        chooseFolder,
        resetDefault,
        pathSaved,
        pathReset,
        engineSection,
        updateEngine,
        updating,
        engineHint,
        appInfoSection,
        appVersion,
        formatSheetTitle,
        mediaSelection,
        downloadType,
        typeVideo,
        typeAudio,
        resolution,
        audioFormat,
        startDownload,
        downloadAll,
        gifHint,
        typeCover,
        saveCover,
        saveCoverSuccess,
        coverDescription,
        downloadSingleMode,
        downloadCollectionMode,
        selectAll,
        deselectAll,
        invertSelection,
        selectedEpisodesCount,
        downloadSelected,
        pleaseSelectEpisode,
        tabVideo,
        tabVideoSub,
        tabMute,
        tabMuteSub,
        tabAudio,
        tabAudioSub,
        tabGif,
        tabGifSub,
        attachedCoverTitle,
        attachedCoverDesc,
        saveCoverAction,
        selectResolutionWithAudio,
        selectResolutionMute,
        selectAudioFormat,
        gifEngineTitle,
        gifEngineDesc,
        switchMultiVideoPrompt,
        switchBilibiliPartPrompt,
        videoPartLabel,
        batchDownloadAudio,
        batchDownloadGif,
        batchDownloadVideos,
        downloadAllCount,
        switchToCollectionMode,
        engineStatusTitle,
        engineStatusRunning,
        engineStatusNotReady,
        storageCleanTitle,
        cleanCacheAction,
        cleanCacheSuccess,
        customBadge,
        changeFolderDialogTitle,
        changeFolderDialogHint,
        selectViaFilePicker,
        commonFolders,
        folderDefault,
        folderMovies,
        folderMusic,
        pathInputLabel,
        saveSettings,
        statusPending,
        statusDownloading,
        statusProcessing,
        statusCompleted,
        statusFailed,
        statusCancelled,
        badgeVideo,
        badgeMute,
        badgeAudio,
        badgeGif,
        badgeCover,
        collectionBadge,
        collectionEpisodesCount,
        collectionTotalProgress,
        collectionCurrentSubTask,
        collectionAllCompleted,
        collectionCancelAll,
        collectionDelete,
        collectionRetryFailed,
        collectionPlayFirst,
        collectionExpandDetails,
        collectionCollapseDetails,
        collectionDeleteConfirmTitle,
        collectionDeleteConfirmMessage,
        collectionCancelConfirmTitle,
        collectionCancelConfirmMessage,
        relaySitesSection,
        relaySitesDesc,
        addRelaySiteBtn,
        relaySitesEmptySettings,
        relaySitesEmptyHome,
        goToSettings,
        fetchingTitle,
        fetchTitleFailed,
        deleteRelaySiteConfirmTitle,
        deleteRelaySiteConfirmMessage,
        sessionSection,
        sessionDesc,
        sessionPlatformLabel,
        sessionImport,
        sessionClear,
        sessionMissing,
        sessionImported,
        sessionNeedsAuth,
        sessionImportSuccess,
        sessionImportFailed,
        sessionCleared,
        sessionManualClearHint,
        sessionManageGoogle,
        sessionManageGoogleHint,
        requestWarningTitle,
        requestWarningBody,
        requestWarningWait,
        requestWarningConfirm,
        requestWarningAccount,
        sessionReverify,
        googleAccountsTitle,
        googleAccountsHint,
        googleAddAccount,
        googleAccountName,
        googleCurrentAccount,
        googleUseAccount,
        googleOpenAccount,
        googleSignedOut,
        googleNoCredentials,
        googleCredentials,
        googleLogoutCurrent,
        googleLogoutSelected,
        googleSelectAll,
        googleClearSelection,
        googleLogoutTitle,
        googleLogoutHint,
        googleUnsupported,
        googlePending,
        googleOpenGoogle,
        googleOpenPlatform,
        googleSavePlatform,
        googleSaved,
        googleCleanupFinished,
        googleNoAccounts,
        googleLegacyAccount,
        engineDiagnosticsTitle,
        engineDiagnosticsUnavailable,
        sessionLogin,
        sessionLoginHint,
        sessionLoginDone,
        browserCaptureAction,
        browserCaptureTitle,
        browserCaptureHint,
        navBatch,
        manageXCookie,
        importYoutubeCookie,
        accountsAndSessions,
        scrollToTop,
        expandPlatforms,
        collapsePlatforms,
        platformLabelBilibili,
        platformLabelDouyin,
        platformLabelKuaishou,
        platformLabelXiaohongshu,
        platformLabelHeybox,
        platformLabelPixiv,
        platformLabelX,
        platformLabelYoutube,
        close,
        open,
        share,
        save,
        viewAction,
        deleteContentDesc,
        menuPaneTitle,
        settingsGifSummary,
        folderPermissionDenied,
        engineFeedback,
        proxyTestFailed,
        proxyTesting,
        proxyTestAction,
        disableCookie,
        disableCookieHint,
        cookieDisabledRetained,
        googleLoginHint,
        imageDownloadPath,
        followDefaultDownloadPath,
        followDefaultPathAction,
        chooseImageFolder,
        relaySiteAdded,
        relaySiteDeleted,
        verifyImagesAndExtract,
        statusAnalyzingShort,
        statusCompletedShort,
        statusIncompleteShort,
        artistProgress,
        stopAnalyzing,
        continueAnalyzing,
        xProfileReading,
        xProfileComplete,
        xProfileIncomplete,
        selectedCount,
        zipByPost,
        zipByArtwork,
        zipArchive,
        ugoiraCoverHint,
        downloadAnimation,
        saveThisImage,
        animationFormat,
        originalFramesZip,
        clearSelection,
        savingEllipsis,
        selectAnimationDesc,
        selectImageDesc,
        previewImageDesc,
        imageDesc,
        ugoiraIndexLabel,
        imageIndexLabel,
        batchTitle,
        batchSmartMode,
        batchManualMode,
        batchInputManual,
        batchInputSmart,
        batchAddLink,
        batchRecognizeAdd,
        batchAllEpisodes,
        batchStart,
        batchStopAdding,
        batchRetryPending,
        batchProgressSummary,
        batchViewTasks,
        batchFilledCount,
        batchClearInput,
        batchAddedToQueue,
        batchRemove,
        batchHistoryTitle,
        batchClearHistory,
        batchWaiting,
        batchSingleAnalyze,
        batchClearLinksTitle,
        batchClearLinksMessage,
        batchClearHistoryTitle,
        batchClearHistoryMessage,
        clipboardEmpty,
        batchTextTooLong,
        clipboardReadFailed,
        gifConvertLocal,
        gifCannotReadVideo,
        gifShareChooser,
        gifNoApp,
        gifChooseVideo,
        gifBackgroundHint,
        gifEmpty,
        gifDeleteTitle,
        gifDeleteMessage,
        gifDeleteFailed,
        gifCannotDeleteFile,
        statusWaitingAuth,
        imageOriginal,
        imageZip,
        coverOriginal,
        retryExport,
        coverContentDesc,
        errorPrefix,
        viewDetails,
        failureLogTitle,
        copyLog,
        mergingTracks,
        remainingTime,
        fileMissing,
        noPlayer,
        shareVideo,
        shareFailed,
        deleteLocalFiles,
        failedEpisodes,
        preparing,
        relayAddHint,
        relayUrlLabel,
        relayRefetchName,
        relayClearUrl,
        relayNameLabel,
        relayNamePlaceholder,
        relayClearName,
        relayFillRequired,
        confirmAdd,
        relayCompatTitle,
        relayCompatS1Title,
        relayCompatS1Desc,
        relayCompatS2Title,
        relayCompatS2Desc,
        relayCompatS3Title,
        relayCompatS3Desc,
        relayCompatAccept,
        relayConnecting,
        relayTimeout,
        relayPageFailed,
        relayPasteFirst,
        relayExpandAll,
        relayCollapseAll,
        relaySuccess,
        relayFallbackTitle,
        relayDirect,
        relayWindowDebug,
        relayResolvingDefault,
        relayBack,
        relayFillLink,
        relayRefresh,
        relayOpenExternal,
        relayExternalFailed,
        douyinVideo,
        douyinImages,
        douyinCaptureNotice,
        douyinVerifyNotice,
        douyinBrowserTitle,
        douyinCookieDisabled,
        enableCookieContinue,
        douyinNoId,
        douyinNotTarget,
        douyinPageLeft,
        douyinExtractTimeout,
        douyinStillNoTarget,
        douyinIdMismatch,
        douyinReading,
        douyinExtract,
        pixivLoginHint,
        sessionNotCaptured,
        desktopSite,
        mobileSite,
        switchToMobile,
        switchToDesktop,
        reloadLogin,
        iHaveLoggedIn,
        loginNotice,
        cookieDisabledTitle,
        cookieDisabledBody,
        desktopSwitchLabel,
        mobileSwitchLabel,
        downloadCaptured,
        imageInputLabel,
        savePathDesc,
        resetDefaultFolder,
        analyzingEllipsis,
        analyzeImages,
        resolutionPending,
        formatPending,
        sizePending,
        currentAccountAccess,
        unverified,
        browserMoreQuality,
        gifBadge,
        imagePreviewUnavailable,
        previousImage,
        nextImage,
        zoomHint,
        webProxyChanged,
        webProxyUnsupported,
        notifQueued,
        notifPreparing,
        notifRemaining,
        notifDownloadError,
        notifTaskError,
        notifAnalyzingTitle,
        notifAnalyzingText,
        notifChannelName,
        notifChannelDesc,
        notifProcessing,
        notifDownloading,
        notifCompletedTitle,
        audioLabelMp3,
        audioLabelM4a,
        audioLabelFlac,
        audioLabelOpus,
        vmClipboardNoLink,
        vmClipboardUnreadable,
        vmEnterLink,
        vmArtistProgress,
        vmImagesFound,
        vmNoMediaResources,
        vmAnalysisCancelled,
        vmAnalysisFailedDetail,
        vmSaveInProgress,
        vmImageTaskLimit,
        vmImageArchiveTitle,
        vmImageSaveStartFailed,
        vmAnalysisStopped,
        vmPastedAnalyzing,
        vmPastedVideoLink,
        vmEnterVideoUrl,
        vmRelayAddressHint,
        vmRelayAddressDetail,
        vmCollectionDetected,
        vmCoverOriginal,
        vmDefaultQuality,
        vmCoverSuffix,
        vmAddedQueueWithCover,
        vmAddedQueue,
        vmRelayVideoTitle,
        vmRelayAuthor,
        vmRelayDirectLink,
        vmRelayCaptured,
        vmNoCoverUrl,
        vmCoverStarted,
        vmSelectVideosFirst,
        vmBestQuality,
        vmCollectionTitle,
        vmCollectionQueued,
        vmDownloadStarted,
        vmTaskCancelled,
        vmDeletedWithFile,
        vmRemovedFromList,
        vmCollectionCancelled,
        vmCollectionDeletedWithFile,
        vmCollectionRemoved,
        vmCollectionRetried,
        vmClearedCount,
        vmNothingToClear,
        vmStoppedTasks,
        vmEngineUpdated,
        vmEngineUpdateFailed,
        androidUpdateTitle,
        androidUpdateBody,
        androidUpdateRelease,
        browserUnavailable,
        platformLabelWeibo,
        ytDlpPlatformsTitle,
        ytDlpPlatformsDescription,
        ytDlpPlatformsSearch,
        ytDlpPlatformsNoResults,
        ytDlpPlatformsUnavailable,
        platformLabelXigua,
        platformLabelYouku,
        platformLabelIqiyi,
        platformLabelHuya,
        analyzeClipboard
    ))
}

private fun appStrings(block: AppStringsBuilder.() -> Unit): AppStrings =
    AppStringsBuilder().apply(block).build()

val ZhHansStrings = appStrings {
    navHome = "首页"
    navTasks = "任务"
    navSettings = "设置"
    appSubtitle = "支持 哔哩哔哩、抖音、快手、小红书、YouTube、TikTok、X 等 10 大主流平台"
    inputPlaceholder = "粘贴视频链接或分享文本..."
    clear = "清除"
    paste = "粘贴"
    pasteClipboard = "粘贴剪贴板"
    startAnalyze = "立即解析"
    analyzing = "解析中..."
    activeTasksTitle = "正在下载的任务"
    viewAll = "查看全部"
    analyzeFailedTitle = "解析未能成功"
    proxyTip = "提示: 解析 YouTube / X 需配合科学上网；如已开启代理，可在设置中配置代理端口。"
    copyErrorLog = "复制错误日志"
    errorLogCopied = "已复制错误日志"
    setProxy = "设置代理"
    tasksTitle = "下载管理"
    tabAll = "全部"
    tabDownloading = "下载中"
    tabCompleted = "已完成"
    emptyAll = "暂无任何下载任务"
    emptyDownloading = "暂无正在进行的下载任务"
    emptyCompleted = "暂无已完成的下载记录"
    clearCompleted = "清空记录"
    clearTasksDialogTitle = "清除下载记录"
    clearTasksDialogMessage = "确定要清除所有已结束的任务记录吗？\n（包含已完成、下载失败与已取消任务，本地已保存的视频文件不会被删除）"
    clearDownloadingTitle = "终止未完成任务"
    clearDownloadingMessage = "确定要终止所有未完成的下载任务并删除残留文件吗？此操作将立即停止下载并清理未完成的临时文件。"
    confirmStopAndClean = "终止并清理"
    confirmClear = "立即清除"
    openFolder = "打开文件夹"
    play = "播放"
    retry = "重试"
    delete = "删除"
    cancel = "取消"
    settingsAppearanceTitle = "外观与语言"
    settingsAppearanceSummary = "主题模式、界面语言"
    settingsDownloadsTitle = "下载与存储"
    settingsDownloadsSummary = "保存目录、缓存与数据清理"
    settingsNetworkTitle = "网络代理"
    settingsNetworkSummary = "HTTP 与 SOCKS 代理连接"
    settingsRelayTitle = "中转站"
    settingsRelaySummary = "添加、管理和删除解析站点"
    settingsAccountsTitle = "账号"
    settingsAccountsSummary = "Cookie 与登录管理"
    settingsEngineTitle = "下载引擎"
    settingsEngineSummary = "运行状态与组件更新"
    settingsAboutTitle = "关于"
    settingsAboutSummary = "应用版本与支持平台"
    navigateBack = "返回"
    settingsTitle = "偏好设置"
    languageSection = "语言 (Language)"
    appearanceSection = "外观"
    appearanceSystem = "跟随系统"
    appearanceLight = "浅色"
    appearanceDark = "深色"
    languageZhCn = "简体中文"
    languageZhTw = "繁體中文"
    languageEn = "English"
    languageJa = "日本語"
    proxySection = "网络代理设置 (Proxy)"
    proxyHint = "支持 HTTP / HTTPS / SOCKS5 代理；留空时测试直连网络"
    proxyPlaceholder = ""
    applyProxy = "保存并应用"
    clearProxy = "清空代理"
    proxySaved = "代理已更新"
    proxyCleared = "代理已清空"
    bilibiliCookieSection = "登录哔哩哔哩 (Cookie / SESSDATA)"
    bilibiliCookieHint = "解锁 1080P / 4K 高清画质"
    bilibiliCookieActive = "已配置 (1080P/4K 已解锁)"
    bilibiliCookiePlaceholder = "粘贴 SESSDATA=xxx 或完整 Cookie (留空使用游客身份)"
    bilibiliCookieSaved = "B站凭证已保存并生效"
    bilibiliCookieCleared = "B站凭证已清空"
    clearBilibiliCookie = "清空凭证"
    applyBilibiliCookie = "保存并应用"
    bilibiliCookieHelp = "如何获取？在电脑浏览器登录 B站 (bilibili.com) 后按 F12，在 Application -> Cookies 中复制 SESSDATA 的值粘贴到此处即可；或直接点击上方按钮通过内置网页一键登录获取。"
    bilibiliGuideTitle = "解锁 1080P / 4K 高清画质"
    bilibiliGuideMessage = "因 B站 官方仅对未登录用户开放最高 480P 画质，若需下载高清 B站 视频请在下方获取登录凭证"
    bilibiliGuideLogin = "一键登录"
    bilibiliGuideLater = "稍后在设置中配置"
    bilibiliAutoLoginBtn = "一键网页登录"
    bilibiliLoginDialogTitle = "B站官方安全登录"
    bilibiliLoginAutoDetecting = "登录成功后将自动识别凭证并关闭..."
    bilibiliLoginSuccess = "🎉 B站凭证已自动获取并生效！已解锁 1080P/4K 高清"
    bilibiliCloseDialog = "关闭"
    storageSection = "存储目录设置"
    customPathHint = "自定义下载路径："
    defaultPath = "默认 (公共 Download/OmniDownloader)"
    pathPlaceholder = "留空恢复默认公共存储目录"
    chooseFolder = "选择文件夹"
    resetDefault = "恢复默认"
    pathSaved = "保存路径已更新"
    pathReset = "已恢复默认保存路径"
    engineSection = "核心引擎组件"
    updateEngine = "在线更新核心解析库"
    updating = "更新中..."
    engineHint = "拉取最新 yt-dlp 解析规则"
    appInfoSection = "应用信息"
    appVersion = "版本"
    formatSheetTitle = "下载配置"
    mediaSelection = "选择多媒体片段"
    downloadType = "下载模式"
    typeVideo = "视频"
    typeAudio = "音频"
    resolution = "视频画质"
    audioFormat = "音频编码格式"
    startDownload = "开始下载"
    downloadAll = "全部下载"
    gifHint = "已智能识别为动态图/短动效，可作为高画质视频下载"
    typeCover = "保存封面"
    saveCover = "保存封面"
    saveCoverSuccess = "封面已保存至相册"
    coverDescription = "原图高清封面，直接保存至系统相册"
    downloadSingleMode = "下载单集"
    downloadCollectionMode = "下载合集"
    selectAll = "全选"
    deselectAll = "取消全选"
    invertSelection = "反选"
    selectedEpisodesCount = "已选 %d / %d 集"
    downloadSelected = "下载所选"
    pleaseSelectEpisode = "请至少勾选一个视频"
    tabVideo = "视频"
    tabVideoSub = "高清音画"
    tabMute = "纯画面"
    tabMuteSub = "无声素材"
    tabAudio = "音频"
    tabAudioSub = "MP3 / M4A"
    tabGif = "GIF"
    tabGifSub = "循环动效"
    attachedCoverTitle = "附加选项：视频原画封面"
    attachedCoverDesc = "提取发布者高清原图，直接存入系统相册"
    saveCoverAction = "存入相册"
    selectResolutionWithAudio = "选择视频分辨率 (自动合成最佳声音)"
    selectResolutionMute = "选择纯视频分辨率 (完全去除声音轨，静音素材)"
    selectAudioFormat = "选择导出的音频格式"
    gifEngineTitle = "高保真 GIF 动图转换"
    gifEngineDesc = "• 内置 FFmpeg 双 Pass 调色板引擎，还原细腻生动色彩与紧凑体积\n• 导出为标准 .gif 格式，微信、相册、QQ 无需播放器即可直接开播与转发\n• 如需保存原画视频文件，可随时切换回「视频」下载为 MP4"
    switchMultiVideoPrompt = "点击切换当前预览与独立下载的视频:"
    switchBilibiliPartPrompt = "点击切换当前预览与下载的分P/分集:"
    videoPartLabel = "视频 %d"
    batchDownloadAudio = "下载所选音频 (%d 首 · %s)"
    batchDownloadGif = "下载所选动图 (%d 个)"
    batchDownloadVideos = "下载所选合集 (%d 集 · %s)"
    downloadAllCount = "全部下载 (%d 集)"
    switchToCollectionMode = "切换为合集多选下载 (%d 集)"
    engineStatusTitle = "核心引擎状态"
    engineStatusRunning = "正常运行"
    engineStatusNotReady = "未就绪"
    storageCleanTitle = "存储瘦身与数据清理"
    cleanCacheAction = "一键清理历史运行缓存"
    cleanCacheSuccess = "历史缓存已清理完毕，存储空间已释放"
    customBadge = "自定义"
    changeFolderDialogTitle = "更改下载保存目录"
    changeFolderDialogHint = "选择快捷预设或使用系统文件选择器："
    selectViaFilePicker = "系统文件管理器中选取文件夹"
    commonFolders = "快捷常用目录:"
    folderDefault = "默认下载"
    folderMovies = "影视库"
    folderMusic = "音乐库"
    pathInputLabel = "存储绝对路径"
    saveSettings = "保存设置"
    statusPending = "等待中"
    statusDownloading = "正在下载"
    statusProcessing = "正在合并"
    statusCompleted = "已完成"
    statusFailed = "失败"
    statusCancelled = "已取消"
    badgeVideo = "视频"
    badgeMute = "纯画面"
    badgeAudio = "音频"
    badgeGif = "GIF"
    badgeCover = "封面"
    collectionBadge = "合集"
    collectionEpisodesCount = "%d 集"
    collectionTotalProgress = "总进度: 已完成 %d/%d 集 (%d%%)"
    collectionCurrentSubTask = "当前分集 (P%d/%d): %s"
    collectionAllCompleted = "合集已全部下载完成 (%d/%d 集)"
    collectionCancelAll = "取消合集"
    collectionDelete = "删除合集"
    collectionRetryFailed = "重试失败项"
    collectionPlayFirst = "播放首集"
    collectionExpandDetails = "分集明细 (共 %d 集 · 已完成 %d)"
    collectionCollapseDetails = "收起分集"
    collectionDeleteConfirmTitle = "删除合集任务"
    collectionDeleteConfirmMessage = "确定要删除该合集下的全部 %d 个分集任务记录吗？"
    collectionCancelConfirmTitle = "取消合集下载"
    collectionCancelConfirmMessage = "确定要终止该合集下所有正在下载与等待中的任务并清除缓存碎片吗？"
    relaySitesSection = "第三方中转网站"
    relaySitesDesc = "自定义添加备用解析站点，在首页中转解析时直接调用"
    addRelaySiteBtn = "添加网址"
    relaySitesEmptySettings = "暂无配置任何中转网站，点击右上角添加"
    relaySitesEmptyHome = "暂无第三方中转网址，可在「设置」中添加"
    goToSettings = "前往设置"
    fetchingTitle = "正在检索网页名称..."
    fetchTitleFailed = "未能自动获取名称，请手动输入"
    deleteRelaySiteConfirmTitle = "删除中转网站"
    deleteRelaySiteConfirmMessage = "确定要删除中转网站「%s」吗？"
    sessionSection = "平台会话"
    sessionDesc = "导入 Netscape Cookies 文件，解析与下载共用同一会话；Cookie 仅保存在本机加密存储中。"
    sessionPlatformLabel = "平台"
    sessionImport = "导入 Cookies 文件"
    sessionClear = "清除会话"
    sessionMissing = "未导入"
    sessionImported = "已导入"
    sessionNeedsAuth = "需重新验证"
    sessionImportSuccess = "会话已导入"
    sessionImportFailed = "Cookies 文件无效或无法读取"
    sessionCleared = "会话已清除"
    sessionManualClearHint = "已清除本机保存的下载凭据。内置浏览器可能仍保留网页登录状态；若通过 Google 等第三方账号登录，请手动退出 Google 和对应平台账号，避免再次自动登录原账号。"
    sessionManageGoogle = "管理 Google 登录"
    sessionManageGoogleHint = "请在 Google 页面中打开账号菜单，手动选择退出登录。此页面不会保存或导入会话；完成后返回对应平台手动退出账号或重新登录。"
    requestWarningTitle = "%s 请求警告"
    requestWarningBody = "平台返回限流、登录失效或验证要求，已停止当前请求。频繁重试可能使账号受到限制。请先检查账号或完成验证，了解后再手动重试；不会自动重启任务。"
    requestWarningWait = "平台指定可重试时间：%s。确认警告不会取消此等待时间。"
    requestWarningConfirm = "我已了解"
    requestWarningAccount = "账号管理"
    sessionReverify = "重新验证登录"
    googleAccountsTitle = "Google 登录档案"
    googleAccountsHint = "每个档案仅登录一个 Google 账号，名称仅作本机备注。可单独或批量退出；旧共享登录请使用手动管理。"
    googleAddAccount = "添加账号"
    googleAccountName = "档案名称或邮箱（本机备注）"
    googleCurrentAccount = "当前档案"
    googleUseAccount = "设为当前"
    googleOpenAccount = "打开"
    googleSignedOut = "已退出"
    googleNoCredentials = "未关联下载凭据"
    googleCredentials = "关联平台：%s"
    googleLogoutCurrent = "退出当前"
    googleLogoutSelected = "退出所选（%d）"
    googleSelectAll = "全选"
    googleClearSelection = "取消选择"
    googleLogoutTitle = "退出并清理所选档案？"
    googleLogoutHint = "将清理所选档案内的网页登录状态，以及仍由这些档案保存的下载凭据。其他档案保留；不会移除手机系统账号或撤销服务器授权。"
    googleUnsupported = "请更新 Android System WebView 后使用独立登录档案。"
    googlePending = "清理未完成，可再次选择重试"
    googleOpenGoogle = "Google 账号页"
    googleOpenPlatform = "平台登录页"
    googleSavePlatform = "保存 %s 下载凭据"
    googleSaved = "下载凭据已关联到此档案"
    googleCleanupFinished = "所选档案已退出并清理本机凭据"
    googleNoAccounts = "尚未添加独立登录档案"
    googleLegacyAccount = "共享网页登录（手动管理）"
    engineDiagnosticsTitle = "组件版本"
    engineDiagnosticsUnavailable = "无法读取"
    sessionLogin = "登录获取会话"
    sessionLoginHint = "在内置浏览器完成登录后，点击“完成”"
    sessionLoginDone = "完成"
    browserCaptureAction = "用内置浏览器捕获"
    browserCaptureTitle = "浏览器媒体捕获"
    browserCaptureHint = "在页面中点击播放，捕获到视频地址后会自动加入下载"
    navBatch = "批量"
    manageXCookie = "管理 X Cookie"
    importYoutubeCookie = "导入 YouTube Cookie"
    accountsAndSessions = "账户与会话"
    scrollToTop = "回到顶部"
    expandPlatforms = "展开支持的平台"
    collapsePlatforms = "收起支持的平台"
    platformLabelBilibili = "哔哩哔哩"
    platformLabelDouyin = "抖音"
    platformLabelKuaishou = "快手"
    platformLabelXiaohongshu = "小红书"
    platformLabelHeybox = "小黑盒"
    platformLabelPixiv = "Pixiv"
    platformLabelX = "X (Twitter)"
    platformLabelYoutube = "YouTube"
    close = "关闭"
    open = "打开"
    share = "分享"
    save = "保存"
    viewAction = "查看"
    deleteContentDesc = "删除"
    menuPaneTitle = "选项菜单"
    settingsGifSummary = "管理 GIF · 本地视频转 GIF"
    folderPermissionDenied = "未获得文件夹权限，请重新选择"
    engineFeedback = "反馈: %s"
    proxyTestFailed = "连通性测试失败，请检查网络后重试"
    proxyTesting = "正在测试…"
    proxyTestAction = "连通性测试"
    disableCookie = "禁用 Cookie"
    disableCookieHint = "仅影响 %s 的后续解析和新下载；已保存会话继续加密保留。"
    cookieDisabledRetained = "已禁用 · 会话保留"
    googleLoginHint = "Google 可能拒绝内置浏览器登录；遇到此情况请使用上方 cookies.txt 文件导入。"
    imageDownloadPath = "图文下载路径"
    followDefaultDownloadPath = "跟随默认下载路径"
    followDefaultPathAction = "跟随默认路径"
    chooseImageFolder = "选择图文文件夹"
    relaySiteAdded = "已成功添加中转网站：%s"
    relaySiteDeleted = "已删除：%s"
    verifyImagesAndExtract = "验证目标图文并提取"
    statusAnalyzingShort = "正在解析"
    statusCompletedShort = "已完成"
    statusIncompleteShort = "尚未完成"
    artistProgress = "%1\$s · %2\$d/%3\$d 个作品 · %4\$d 张原图 · %5\$d 个动图"
    stopAnalyzing = "停止解析"
    continueAnalyzing = "继续解析"
    xProfileReading = "正在读取主页 · %1\$d 个图片帖子 · %2\$d 张图片"
    xProfileComplete = "平台已返回末页 · %1\$d 个图片帖子 · %2\$d 张图片"
    xProfileIncomplete = "尚未解析完成 · 已保留 %d 个图片帖子"
    selectedCount = "已选 %1\$d / %2\$d"
    zipByPost = "按帖打包 ZIP"
    zipByArtwork = "图片按作品 ZIP"
    zipArchive = "打包 ZIP"
    ugoiraCoverHint = "动图封面 · 下载后播放"
    downloadAnimation = "下载动图"
    saveThisImage = "保存此图"
    animationFormat = "动图：%s ▾"
    originalFramesZip = "原始帧 ZIP"
    clearSelection = "清空选择"
    savingEllipsis = "保存中…"
    selectAnimationDesc = "选择第 %d 个动图"
    selectImageDesc = "选择第 %d 张图片"
    previewImageDesc = "预览图片"
    imageDesc = "第 %d 张图片"
    ugoiraIndexLabel = "%1\$d · 动图（封面）"
    imageIndexLabel = "%1\$d · %2\$d×%3\$d"
    batchTitle = "批量下载"
    batchSmartMode = "智能识别"
    batchManualMode = "逐条添加"
    batchInputManual = "填入一条链接或单条分享文本"
    batchInputSmart = "粘贴多条链接或完整分享文本"
    batchAddLink = "添加链接"
    batchRecognizeAdd = "识别并添加"
    batchAllEpisodes = "下载合集 / 多视频的全部分集"
    batchStart = "解析并加入下载队列"
    batchStopAdding = "停止添加（已入队任务继续下载）"
    batchRetryPending = "继续 / 重试未入队条目"
    batchProgressSummary = "已入队 %1\$d / %2\$d 条链接 · %3\$d 个任务"
    batchViewTasks = "查看下载任务"
    batchFilledCount = "已填链接 %d / 100"
    batchClearInput = "清空输入"
    batchAddedToQueue = "已加入下载队列"
    batchRemove = "移除"
    batchHistoryTitle = "批次处理记录"
    batchClearHistory = "清空记录"
    batchWaiting = "等待处理"
    batchSingleAnalyze = "单独解析 / 验证"
    batchClearLinksTitle = "清空已填链接？"
    batchClearLinksMessage = "对应解析结果会清除，已入队的下载任务会继续。"
    batchClearHistoryTitle = "清空批次处理记录？"
    batchClearHistoryMessage = "已填链接和下载任务会保留，正在下载的任务不受影响。"
    clipboardEmpty = "剪贴板为空"
    batchTextTooLong = "文本过长，请分批添加"
    clipboardReadFailed = "无法读取剪贴板"
    gifConvertLocal = "本地视频转 GIF"
    gifCannotReadVideo = "无法读取所选视频"
    gifShareChooser = "分享 GIF"
    gifNoApp = "文件不可用或没有可打开 GIF 的应用"
    gifChooseVideo = "选择本地视频转 GIF"
    gifBackgroundHint = "转换任务在后台执行，可在任务页查看进度、取消或重试。"
    gifEmpty = "暂无 GIF"
    gifDeleteTitle = "删除 GIF？"
    gifDeleteMessage = "将删除此 GIF 文件及任务记录，本地原视频保留。"
    gifDeleteFailed = "删除失败，请检查文件权限"
    gifCannotDeleteFile = "无法删除文件"
    statusWaitingAuth = "等待登录/验证"
    imageOriginal = "图文：原始图片"
    imageZip = "图文：ZIP 归档"
    coverOriginal = "%s: 原图"
    retryExport = "重试导出"
    coverContentDesc = "封面"
    errorPrefix = "错误: %s"
    viewDetails = "查看详情"
    failureLogTitle = "下载失败详细日志"
    copyLog = "复制日志"
    mergingTracks = "正在合并音视频轨..."
    remainingTime = "剩余 %s"
    fileMissing = "文件不存在或已被移动"
    noPlayer = "未找到可打开此文件的播放器或相册"
    shareVideo = "分享视频"
    shareFailed = "分享失败: %s"
    deleteLocalFiles = "同时删除已下载的本地视频文件"
    failedEpisodes = "失败 %d 集"
    preparing = "准备中"
    relayAddHint = "输入网址后将自动检索网站名称，也可手动修改："
    relayUrlLabel = "网站完整网址"
    relayRefetchName = "重新检索名称"
    relayClearUrl = "清除网址"
    relayNameLabel = "网站名称"
    relayNamePlaceholder = "例如：备用解析工具"
    relayClearName = "清空名称"
    relayFillRequired = "请完整填写网站名称与网址"
    confirmAdd = "确认添加"
    relayCompatTitle = "第三方网页使用须知与兼容性提示"
    relayCompatS1Title = "1. 服务性质声明"
    relayCompatS1Desc = "备用中转功能仅提供第三方网页的加载容器与通用直链嗅探辅助，本应用未内置亦未与任何第三方中转解析站点达成合作，不保证任何第三方网站的长期可用性。"
    relayCompatS2Title = "2. 兼容性与网页行为"
    relayCompatS2Desc = "各站点的页面交互、反爬机制及解析链路存在较大差异。使用过程中若遇人机验证、广告跳转、临时失效或防盗链拦截均属于网页端行为，建议优先使用主界面的原生直连解析。"
    relayCompatS3Title = "3. 安全与隐私保护"
    relayCompatS3Desc = "请确保您添加并访问的网址合法合规。在加载第三方网页时，请勿输入个人账号、密码或敏感隐私信息，防范钓鱼与安全风险。"
    relayCompatAccept = "我已知晓并继续"
    relayConnecting = "正在连接中转站…"
    relayTimeout = "中转站响应超时，可点击「窗口排查」完成验证或手动解析"
    relayPageFailed = "中转站页面加载失败，可使用窗口排查"
    relayPasteFirst = "请先在上方输入框粘贴欲解析的视频链接"
    relayExpandAll = "展开全部 %d 个中转网站"
    relayCollapseAll = "收起部分中转网站"
    relaySuccess = "中转解析成功，已自动加入本地下载队列！"
    relayFallbackTitle = "%s 视频"
    relayDirect = "直接解析"
    relayWindowDebug = "窗口排查"
    relayResolvingDefault = "正在后台自动提取视频直链并加入下载..."
    relayBack = "返回"
    relayFillLink = "填入链接"
    relayRefresh = "刷新"
    relayOpenExternal = "在外部浏览器打开"
    relayExternalFailed = "无法打开外部浏览器"
    douyinVideo = "视频"
    douyinImages = "图文"
    douyinCaptureNotice = "请完成登录或平台验证，打开目标%s后点击提取。"
    douyinVerifyNotice = "请完成平台验证；目标视频就绪后会自动继续提取。"
    douyinBrowserTitle = "抖音%s浏览器"
    douyinCookieDisabled = "抖音 Cookie 已禁用。浏览器辅助需要使用网页登录状态。"
    enableCookieContinue = "开启 Cookie 并继续"
    douyinNoId = "尚未确认目标作品ID，请返回重新解析分享链接。"
    douyinNotTarget = "当前页面不是目标作品，请返回目标%s页后提取。"
    douyinPageLeft = "页面已离开目标作品，请返回后重试"
    douyinExtractTimeout = "提取超时，请确认目标%s已正常加载"
    douyinStillNoTarget = "网页仍未提供目标%s，请完成验证并播放目标作品后重试"
    douyinIdMismatch = "目标作品ID不匹配"
    douyinReading = "正在读取目标%s…"
    douyinExtract = "提取此作品%s"
    pixivLoginHint = "请使用能查看目标作品的 Pixiv 账号登录，完成后点击下方保存会话，再重新解析作品。"
    sessionNotCaptured = "尚未取得会话，请先完成网页登录后再保存"
    desktopSite = " · 桌面网站"
    mobileSite = " · 手机版"
    switchToMobile = "切换手机版"
    switchToDesktop = "切换桌面版"
    reloadLogin = "重新加载登录页"
    iHaveLoggedIn = "我已登录，保存会话"
    loginNotice = "请在网页内选择扫码或手机号登录，应用唤起链接不会离开当前登录页"
    cookieDisabledTitle = "%s 的 Cookie 已禁用"
    cookieDisabledBody = "浏览器辅助会使用网页登录状态。可开启该平台 Cookie 后继续，或关闭窗口保持匿名解析。"
    desktopSwitchLabel = "桌面网站（切换手机版）"
    mobileSwitchLabel = "手机版（切换桌面网站）"
    downloadCaptured = "下载当前已捕获媒体（请确认来自目标内容）"
    imageInputLabel = "粘贴单个作品链接或分享文字"
    savePathDesc = "保存路径：%s"
    resetDefaultFolder = "恢复默认目录"
    analyzingEllipsis = "正在解析…"
    analyzeImages = "解析图文"
    resolutionPending = "分辨率待确认"
    formatPending = "格式待确认"
    sizePending = "大小待确认"
    currentAccountAccess = "当前账号：%1\$s · 访问：%2\$s"
    unverified = "未验证"
    browserMoreQuality = "浏览器读取更多画质"
    gifBadge = "GIF 动图"
    imagePreviewUnavailable = "图片暂不可预览，可关闭后重试或保存原图"
    previousImage = "上一张"
    nextImage = "下一张"
    zoomHint = "双指缩放 · 双击还原/放大"
    webProxyChanged = "浏览器网络已改变，请关闭旧页面后重试"
    webProxyUnsupported = "系统 WebView 暂不支持指定代理"
    notifQueued = "任务已加入队列"
    notifPreparing = "准备下载..."
    notifRemaining = "· 剩余 %s"
    notifDownloadError = "下载过程中遇到错误"
    notifTaskError = "任务执行异常"
    notifAnalyzingTitle = "正在解析媒体"
    notifAnalyzingText = "可返回应用查看解析结果"
    notifChannelName = "下载任务"
    notifChannelDesc = "展示当前视频/音频的实时下载与合并进度"
    notifProcessing = "正在处理"
    notifDownloading = "正在下载"
    notifCompletedTitle = "下载完成"
    audioLabelMp3 = "MP3 格式 (高兼容性)"
    audioLabelM4a = "M4A 格式 (高音质 AAC)"
    audioLabelFlac = "FLAC 格式 (无损音频)"
    audioLabelOpus = "OPUS 格式 (超高压缩)"
    vmClipboardNoLink = "剪贴板中未发现有效链接"
    vmClipboardUnreadable = "无法读取剪贴板"
    vmEnterLink = "请输入或粘贴作品链接"
    vmArtistProgress = "已读取 %1\$d/%2\$d 个作品…"
    vmImagesFound = "已找到 %d 张图片…"
    vmNoMediaResources = "该作品没有可确认的图片资源"
    vmAnalysisCancelled = "解析会话已改变"
    vmAnalysisFailedDetail = "解析失败，点击弹窗查看详情"
    vmSaveInProgress = "图片正在保存，请稍后重试"
    vmImageTaskLimit = "每次最多添加 500 个图片任务，请减少选择或按帖打包"
    vmImageArchiveTitle = "原图归档"
    vmImageSaveStartFailed = "无法启动图片保存，请重试"
    vmAnalysisStopped = "已停止解析，已取得的图片仍可保存或继续解析"
    vmPastedAnalyzing = "已读取剪贴板，正在解析..."
    vmPastedVideoLink = "已从剪贴板粘贴视频链接"
    vmEnterVideoUrl = "请输入或粘贴视频网址"
    vmRelayAddressHint = "提示：此为中转网站地址，请粘贴具体视频链接"
    vmRelayAddressDetail = "检测到您输入的是中转解析站地址 (%s)。\n\n请在输入框粘贴欲下载的真实视频链接（如 Bilibili、YouTube、TikTok、X 等平台的分享链接），或在下方中转站卡片内输入视频链接进行解析。"
    vmCollectionDetected = "检测到合集内容(共%d个视频)，请选择下载选项"
    vmCoverOriginal = "原图封面"
    vmDefaultQuality = "默认画质"
    vmCoverSuffix = "%s (封面)"
    vmAddedQueueWithCover = "已加入下载队列 (含封面): %s"
    vmAddedQueue = "已加入下载队列: %s"
    vmRelayVideoTitle = "中转下载视频"
    vmRelayAuthor = "第三方中转"
    vmRelayDirectLink = "中转直链"
    vmRelayCaptured = "已从备用站捕获并加入下载队列: %s"
    vmNoCoverUrl = "未发现封面图地址"
    vmCoverStarted = "已开始下载封面: %s"
    vmSelectVideosFirst = "请先勾选需要下载的视频"
    vmBestQuality = "最佳画质"
    vmCollectionTitle = "合集视频"
    vmCollectionQueued = "已加入合集下载队列 (共 %d 集)"
    vmDownloadStarted = "已开始下载: %s"
    vmTaskCancelled = "任务已取消"
    vmDeletedWithFile = "已删除记录与本地文件"
    vmRemovedFromList = "已从列表移除"
    vmCollectionCancelled = "合集下载已取消"
    vmCollectionDeletedWithFile = "已删除合集记录与本地文件"
    vmCollectionRemoved = "已从列表移除合集"
    vmCollectionRetried = "已重新开始下载合集中的未完成项 (%d 集)"
    vmClearedCount = "已清除 %d 条任务记录"
    vmNothingToClear = "暂无需要清除的历史记录"
    vmStoppedTasks = "已终止 %d 个任务并清理残留文件"
    vmEngineUpdated = "核心引擎更新成功: %s"
    vmEngineUpdateFailed = "更新引擎失败:\n%s"
    androidUpdateTitle = "发现 Android 新版本 %s"
    androidUpdateBody = "前往 GitHub 发行页查看更新内容并下载 APK。"
    androidUpdateRelease = "查看发行版"
    browserUnavailable = "找不到可以打开 GitHub 发行页的浏览器。"
    platformLabelWeibo = "微博"
    ytDlpPlatformsTitle = "yt-dlp 支持的平台"
    ytDlpPlatformsDescription = "以下解析器来自当前引擎。请在首页粘贴视频链接来检查可用格式。部分平台需要登录或额外的引擎组件。"
    ytDlpPlatformsSearch = "搜索平台或解析器"
    ytDlpPlatformsNoResults = "未找到匹配的平台"
    ytDlpPlatformsUnavailable = "暂时无法读取 yt-dlp 平台列表"
    platformLabelXigua = "西瓜视频"
    platformLabelYouku = "优酷"
    platformLabelIqiyi = "爱奇艺"
    platformLabelHuya = "虎牙"
    analyzeClipboard = "解析剪切板"
}

val ZhHantStrings = appStrings {
    navHome = "首頁"
    navTasks = "任務"
    navSettings = "設定"
    appSubtitle = "支援 嗶哩嗶哩、抖音、快手、小紅書、YouTube、TikTok、X 等 10 大主流平台"
    inputPlaceholder = "貼上影片連結或分享文字..."
    clear = "清除"
    paste = "貼上"
    pasteClipboard = "貼上剪貼簿"
    startAnalyze = "立即解析"
    analyzing = "解析中..."
    activeTasksTitle = "正在下載的任務"
    viewAll = "查看全部"
    analyzeFailedTitle = "解析未能成功"
    proxyTip = "提示: 解析 YouTube / X 需配合代理連線；如已開啟代理，可在設定中配置代理埠。"
    copyErrorLog = "複製錯誤記錄"
    errorLogCopied = "已複製錯誤記錄"
    setProxy = "設定代理"
    tasksTitle = "下載管理"
    tabAll = "全部"
    tabDownloading = "下載中"
    tabCompleted = "已完成"
    emptyAll = "暫無任何下載任務"
    emptyDownloading = "暫無進行中的下載任務"
    emptyCompleted = "暫無已完成的下載記錄"
    clearCompleted = "清空記錄"
    clearTasksDialogTitle = "清除下載記錄"
    clearTasksDialogMessage = "確定要清除所有已結束的任務記錄嗎？\n（包含已完成、下載失敗與已取消任務，本機已儲存的影片檔案不會被刪除）"
    clearDownloadingTitle = "終止未完成任務"
    clearDownloadingMessage = "確定要終止所有未完成的下載任務並刪除殘留檔案嗎？此操作將立即停止下載並清理未完成的暫存檔案。"
    confirmStopAndClean = "終止並清理"
    confirmClear = "立即清除"
    openFolder = "開啟資料夾"
    play = "播放"
    retry = "重試"
    delete = "刪除"
    cancel = "取消"
    settingsAppearanceTitle = "外觀與語言"
    settingsAppearanceSummary = "主題模式、介面語言"
    settingsDownloadsTitle = "下載與儲存"
    settingsDownloadsSummary = "儲存目錄、快取與資料清理"
    settingsNetworkTitle = "網路代理"
    settingsNetworkSummary = "HTTP 與 SOCKS 代理連線"
    settingsRelayTitle = "中轉站"
    settingsRelaySummary = "新增、管理與刪除解析站點"
    settingsAccountsTitle = "帳號"
    settingsAccountsSummary = "Cookie 與登入管理"
    settingsEngineTitle = "下載引擎"
    settingsEngineSummary = "執行狀態與元件更新"
    settingsAboutTitle = "關於"
    settingsAboutSummary = "應用程式版本與支援平台"
    navigateBack = "返回"
    settingsTitle = "偏好設定"
    languageSection = "語言 (Language)"
    appearanceSection = "外觀"
    appearanceSystem = "跟隨系統"
    appearanceLight = "淺色"
    appearanceDark = "深色"
    languageZhCn = "简体中文"
    languageZhTw = "繁體中文"
    languageEn = "English"
    languageJa = "日本語"
    proxySection = "網路代理設定 (Proxy)"
    proxyHint = "支援 HTTP / HTTPS / SOCKS5 代理；留空時測試直接連線"
    proxyPlaceholder = ""
    applyProxy = "儲存並套用"
    clearProxy = "清空代理"
    proxySaved = "代理已更新"
    proxyCleared = "代理已清空"
    bilibiliCookieSection = "登入嗶哩嗶哩 (Cookie / SESSDATA)"
    bilibiliCookieHint = "解鎖 1080P / 4K 高畫質"
    bilibiliCookieActive = "已配置 (1080P/4K 已解鎖)"
    bilibiliCookiePlaceholder = "貼上 SESSDATA=xxx 或完整 Cookie (留空使用訪客身份)"
    bilibiliCookieSaved = "B站憑證已儲存並生效"
    bilibiliCookieCleared = "B站憑證已清空"
    clearBilibiliCookie = "清空憑證"
    applyBilibiliCookie = "儲存並套用"
    bilibiliCookieHelp = "如何獲取？在電腦瀏覽器登入 B站 (bilibili.com) 後按 F12，在 Application -> Cookies 中複製 SESSDATA 的值貼至此處即可；或直接點擊上方按鈕透過內建網頁一鍵登入取得。"
    bilibiliGuideTitle = "解鎖 1080P / 4K 高畫質"
    bilibiliGuideMessage = "因 B站 官方僅對未登入使用者開放最高 480P 畫質，若需下載高畫質 B站 影片請於下方取得登入憑證"
    bilibiliGuideLogin = "一鍵登入"
    bilibiliGuideLater = "稍後於設定中配置"
    bilibiliAutoLoginBtn = "一鍵網頁登入"
    bilibiliLoginDialogTitle = "B站官方安全登入"
    bilibiliLoginAutoDetecting = "登入成功後將自動識別憑證並關閉..."
    bilibiliLoginSuccess = "🎉 B站憑證已自動取得並生效！已解鎖 1080P/4K 高畫質"
    bilibiliCloseDialog = "關閉"
    storageSection = "儲存目錄設定"
    customPathHint = "自訂下載路徑："
    defaultPath = "預設 (公共 Download/OmniDownloader)"
    pathPlaceholder = "留空恢復預設儲存目錄"
    chooseFolder = "選取資料夾"
    resetDefault = "恢復預設"
    pathSaved = "儲存路徑已更新"
    pathReset = "已恢復預設儲存路徑"
    engineSection = "核心引擎組件"
    updateEngine = "線上更新核心解析庫"
    updating = "更新中..."
    engineHint = "獲取最新 yt-dlp 解析規則"
    appInfoSection = "應用程式資訊"
    appVersion = "版本"
    formatSheetTitle = "下載設定"
    mediaSelection = "選取多媒體項目"
    downloadType = "下載模式"
    typeVideo = "視頻"
    typeAudio = "音訊"
    resolution = "影片畫質"
    audioFormat = "音訊編碼格式"
    startDownload = "開始下載"
    downloadAll = "全部下載"
    gifHint = "已智慧辨識為動態圖/短動效，可作為高畫質影片下載"
    typeCover = "儲存封面"
    saveCover = "儲存封面"
    saveCoverSuccess = "封面已儲存至相簿"
    coverDescription = "原圖高清封面，直接儲存至系統相簿"
    downloadSingleMode = "下載單集"
    downloadCollectionMode = "下載合集"
    selectAll = "全選"
    deselectAll = "取消全選"
    invertSelection = "反選"
    selectedEpisodesCount = "已選 %d / %d 集"
    downloadSelected = "下載所選"
    pleaseSelectEpisode = "請至少勾選一個影片"
    tabVideo = "視頻"
    tabVideoSub = "高畫質音畫"
    tabMute = "純畫面"
    tabMuteSub = "無聲素材"
    tabAudio = "音訊"
    tabAudioSub = "MP3 / M4A"
    tabGif = "GIF"
    tabGifSub = "循環動效"
    attachedCoverTitle = "附加選項：影片原圖封面"
    attachedCoverDesc = "擷取發布者高畫質原圖，直接儲存至系統相簿"
    saveCoverAction = "存入相簿"
    selectResolutionWithAudio = "選取影片解析度 (自動合併最佳音訊)"
    selectResolutionMute = "選取純影片解析度 (完全去除聲音軌，靜音素材)"
    selectAudioFormat = "選取導出的音訊格式"
    gifEngineTitle = "高保真 GIF 動圖轉換"
    gifEngineDesc = "• 內建 FFmpeg 雙 Pass 調色盤引擎，還原細膩生動色彩與緊湊體積\n• 匯出為標準 .gif 格式，通訊軟體與相簿可直接播放\n• 如需儲存原畫影片，可隨時切換回「視頻」下載為 MP4"
    switchMultiVideoPrompt = "點擊切換當前預覽與獨立下載的影片:"
    switchBilibiliPartPrompt = "點擊切換當前預覽與下載的分P/分集:"
    videoPartLabel = "影片 %d"
    batchDownloadAudio = "下載所選音訊 (%d 首 · %s)"
    batchDownloadGif = "下載所選動圖 (%d 個)"
    batchDownloadVideos = "下載所選合集 (%d 集 · %s)"
    downloadAllCount = "全部下載 (%d 集)"
    switchToCollectionMode = "切換為合集多選下載 (%d 集)"
    engineStatusTitle = "核心引擎狀態"
    engineStatusRunning = "正常運作"
    engineStatusNotReady = "未就緒"
    storageCleanTitle = "儲存瘦身與資料清理"
    cleanCacheAction = "一鍵清理歷史快取"
    cleanCacheSuccess = "歷史快取已清理完畢，儲存空間已釋放"
    customBadge = "自訂"
    changeFolderDialogTitle = "更改下載儲存目錄"
    changeFolderDialogHint = "選取快捷預設或使用系統檔案選擇器："
    selectViaFilePicker = "系統檔案管理器中選取資料夾"
    commonFolders = "快捷常用目錄:"
    folderDefault = "預設下載"
    folderMovies = "影視庫"
    folderMusic = "音樂庫"
    pathInputLabel = "儲存絕對路徑"
    saveSettings = "儲存設定"
    statusPending = "等待中"
    statusDownloading = "正在下載"
    statusProcessing = "正在合併"
    statusCompleted = "已完成"
    statusFailed = "失敗"
    statusCancelled = "已取消"
    badgeVideo = "視頻"
    badgeMute = "純畫面"
    badgeAudio = "音訊"
    badgeGif = "GIF"
    badgeCover = "封面"
    collectionBadge = "合集"
    collectionEpisodesCount = "%d 集"
    collectionTotalProgress = "總進度: 已完成 %d/%d 集 (%d%%)"
    collectionCurrentSubTask = "目前分集 (P%d/%d): %s"
    collectionAllCompleted = "合集已全部下載完成 (%d/%d 集)"
    collectionCancelAll = "取消合集"
    collectionDelete = "刪除合集"
    collectionRetryFailed = "重試失敗項"
    collectionPlayFirst = "播放首集"
    collectionExpandDetails = "分集明細 (共 %d 集 · 已完成 %d)"
    collectionCollapseDetails = "收起分集"
    collectionDeleteConfirmTitle = "刪除合集任務"
    collectionDeleteConfirmMessage = "確定要刪除該合集下的全部 %d 個分集任務記錄嗎？"
    collectionCancelConfirmTitle = "取消合集下載"
    collectionCancelConfirmMessage = "確定要終止該合集下所有正在下載與等待中的任務並清除快取碎片嗎？"
    relaySitesSection = "第三方轉發網站"
    relaySitesDesc = "自訂新增備用解析網站，在首頁轉發解析時直接調用"
    addRelaySiteBtn = "新增網址"
    relaySitesEmptySettings = "暫無配置任何轉發網站，點擊右上角新增"
    relaySitesEmptyHome = "暫無第三方轉發網址，可在「設定」中新增"
    goToSettings = "前往設定"
    fetchingTitle = "正在檢索網頁名稱..."
    fetchTitleFailed = "未能自動獲取名稱，請手動輸入"
    deleteRelaySiteConfirmTitle = "刪除轉發網站"
    deleteRelaySiteConfirmMessage = "確定要刪除轉發網站「%s」嗎？"
    sessionSection = "平台工作階段"
    sessionDesc = "匯入 Netscape Cookies 檔案，解析與下載共用同一工作階段；Cookie 僅儲存在本機加密儲存中。"
    sessionPlatformLabel = "平台"
    sessionImport = "匯入 Cookies 檔案"
    sessionClear = "清除工作階段"
    sessionMissing = "未匯入"
    sessionImported = "已匯入"
    sessionNeedsAuth = "需重新驗證"
    sessionImportSuccess = "工作階段已匯入"
    sessionImportFailed = "Cookies 檔案無效或無法讀取"
    sessionCleared = "工作階段已清除"
    sessionManualClearHint = "已清除本機儲存的下載憑證。內建瀏覽器可能仍保留網頁登入狀態；若透過 Google 等第三方帳號登入，請手動登出 Google 和對應平台帳號，避免再次自動登入原帳號。"
    sessionManageGoogle = "管理 Google 登入"
    sessionManageGoogleHint = "請在 Google 頁面中開啟帳號選單，手動選擇登出。此頁面不會儲存或匯入工作階段；完成後返回對應平台手動登出帳號或重新登入。"
    requestWarningTitle = "%s 請求警告"
    requestWarningBody = "平台回傳限流、登入失效或驗證要求，已停止目前請求。頻繁重試可能使帳號受到限制。請先檢查帳號或完成驗證，了解後再手動重試；不會自動重啟工作。"
    requestWarningWait = "平台指定可重試時間：%s。確認警告不會取消此等待時間。"
    requestWarningConfirm = "我已了解"
    requestWarningAccount = "帳號管理"
    sessionReverify = "重新驗證登入"
    googleAccountsTitle = "Google 登入檔案"
    googleAccountsHint = "每個檔案僅登入一個 Google 帳號，名稱僅作本機備註。可單獨或批次登出；舊共用登入請使用手動管理。"
    googleAddAccount = "新增帳號"
    googleAccountName = "檔案名稱或信箱（本機備註）"
    googleCurrentAccount = "目前檔案"
    googleUseAccount = "設為目前"
    googleOpenAccount = "開啟"
    googleSignedOut = "已登出"
    googleNoCredentials = "未關聯下載憑證"
    googleCredentials = "關聯平台：%s"
    googleLogoutCurrent = "登出目前"
    googleLogoutSelected = "登出所選（%d）"
    googleSelectAll = "全選"
    googleClearSelection = "取消選取"
    googleLogoutTitle = "登出並清理所選檔案？"
    googleLogoutHint = "將清理所選檔案內的網頁登入狀態，以及仍由這些檔案儲存的下載憑證。其他檔案保留；不會移除手機系統帳號或撤銷伺服器授權。"
    googleUnsupported = "請更新 Android System WebView 後使用獨立登入檔案。"
    googlePending = "清理未完成，可再次選取重試"
    googleOpenGoogle = "Google 帳號頁"
    googleOpenPlatform = "平台登入頁"
    googleSavePlatform = "儲存 %s 下載憑證"
    googleSaved = "下載憑證已關聯到此檔案"
    googleCleanupFinished = "所選檔案已登出並清理本機憑證"
    googleNoAccounts = "尚未新增獨立登入檔案"
    googleLegacyAccount = "共用網頁登入（手動管理）"
    engineDiagnosticsTitle = "組件版本"
    engineDiagnosticsUnavailable = "無法讀取"
    sessionLogin = "登入取得工作階段"
    sessionLoginHint = "在內建瀏覽器完成登入後，點擊「完成」"
    sessionLoginDone = "完成"
    browserCaptureAction = "用內建瀏覽器擷取"
    browserCaptureTitle = "瀏覽器媒體擷取"
    browserCaptureHint = "在頁面中點擊播放，擷取到影片位址後會自動加入下載"
    navBatch = "批次"
    manageXCookie = "管理 X Cookie"
    importYoutubeCookie = "匯入 YouTube Cookie"
    accountsAndSessions = "帳號與工作階段"
    scrollToTop = "回到頂端"
    expandPlatforms = "展開支援的平台"
    collapsePlatforms = "收合支援的平台"
    platformLabelBilibili = "嗶哩嗶哩"
    platformLabelDouyin = "抖音"
    platformLabelKuaishou = "快手"
    platformLabelXiaohongshu = "小紅書"
    platformLabelHeybox = "小黑盒"
    platformLabelPixiv = "Pixiv"
    platformLabelX = "X (Twitter)"
    platformLabelYoutube = "YouTube"
    close = "關閉"
    open = "開啟"
    share = "分享"
    save = "儲存"
    viewAction = "檢視"
    deleteContentDesc = "刪除"
    menuPaneTitle = "選項選單"
    settingsGifSummary = "管理 GIF · 本機影片轉 GIF"
    folderPermissionDenied = "未取得資料夾權限，請重新選擇"
    engineFeedback = "回饋: %s"
    proxyTestFailed = "連線測試失敗，請檢查網路後重試"
    proxyTesting = "測試中…"
    proxyTestAction = "連線測試"
    disableCookie = "停用 Cookie"
    disableCookieHint = "僅影響 %s 的後續解析與新下載；已儲存的工作階段仍會加密保留。"
    cookieDisabledRetained = "已停用 · 工作階段保留"
    googleLoginHint = "Google 可能拒絕內建瀏覽器登入；遇到此情況請使用上方的 cookies.txt 檔案匯入。"
    imageDownloadPath = "圖文下載路徑"
    followDefaultDownloadPath = "跟隨預設下載路徑"
    followDefaultPathAction = "跟隨預設路徑"
    chooseImageFolder = "選擇圖文資料夾"
    relaySiteAdded = "已成功新增中轉網站：%s"
    relaySiteDeleted = "已刪除：%s"
    verifyImagesAndExtract = "驗證目標圖文並擷取"
    statusAnalyzingShort = "正在解析"
    statusCompletedShort = "已完成"
    statusIncompleteShort = "尚未完成"
    artistProgress = "%1\$s · %2\$d/%3\$d 個作品 · %4\$d 張原圖 · %5\$d 個動圖"
    stopAnalyzing = "停止解析"
    continueAnalyzing = "繼續解析"
    xProfileReading = "正在讀取主頁 · %1\$d 個圖片貼文 · %2\$d 張圖片"
    xProfileComplete = "平台已回傳末頁 · %1\$d 個圖片貼文 · %2\$d 張圖片"
    xProfileIncomplete = "尚未解析完成 · 已保留 %d 個圖片貼文"
    selectedCount = "已選 %1\$d / %2\$d"
    zipByPost = "依貼文打包 ZIP"
    zipByArtwork = "圖片依作品 ZIP"
    zipArchive = "打包 ZIP"
    ugoiraCoverHint = "動圖封面 · 下載後播放"
    downloadAnimation = "下載動圖"
    saveThisImage = "儲存此圖"
    animationFormat = "動圖：%s ▾"
    originalFramesZip = "原始影格 ZIP"
    clearSelection = "清除選取"
    savingEllipsis = "儲存中…"
    selectAnimationDesc = "選取第 %d 個動圖"
    selectImageDesc = "選取第 %d 張圖片"
    previewImageDesc = "預覽圖片"
    imageDesc = "第 %d 張圖片"
    ugoiraIndexLabel = "%1\$d · 動圖（封面）"
    imageIndexLabel = "%1\$d · %2\$d×%3\$d"
    batchTitle = "批次下載"
    batchSmartMode = "智慧辨識"
    batchManualMode = "逐條新增"
    batchInputManual = "填入一條連結或單則分享文字"
    batchInputSmart = "貼上多條連結或完整分享文字"
    batchAddLink = "新增連結"
    batchRecognizeAdd = "辨識並新增"
    batchAllEpisodes = "下載合集 / 多影片的全部分集"
    batchStart = "解析並加入下載佇列"
    batchStopAdding = "停止新增（已入列任務繼續下載）"
    batchRetryPending = "繼續 / 重試未入列項目"
    batchProgressSummary = "已入列 %1\$d / %2\$d 條連結 · %3\$d 個任務"
    batchViewTasks = "檢視下載任務"
    batchFilledCount = "已填連結 %d / 100"
    batchClearInput = "清除輸入"
    batchAddedToQueue = "已加入下載佇列"
    batchRemove = "移除"
    batchHistoryTitle = "批次處理記錄"
    batchClearHistory = "清除記錄"
    batchWaiting = "等待處理"
    batchSingleAnalyze = "單獨解析 / 驗證"
    batchClearLinksTitle = "清除已填連結？"
    batchClearLinksMessage = "對應的解析結果會清除，已入列的下載任務會繼續。"
    batchClearHistoryTitle = "清除批次處理記錄？"
    batchClearHistoryMessage = "已填連結與下載任務會保留，正在下載的任務不受影響。"
    clipboardEmpty = "剪貼簿為空"
    batchTextTooLong = "文字過長，請分批新增"
    clipboardReadFailed = "無法讀取剪貼簿"
    gifConvertLocal = "本機影片轉 GIF"
    gifCannotReadVideo = "無法讀取所選影片"
    gifShareChooser = "分享 GIF"
    gifNoApp = "檔案無法使用，或沒有可開啟 GIF 的應用程式"
    gifChooseVideo = "選擇本機影片轉 GIF"
    gifBackgroundHint = "轉換任務會在背景執行，可在任務頁檢視進度、取消或重試。"
    gifEmpty = "尚無 GIF"
    gifDeleteTitle = "刪除 GIF？"
    gifDeleteMessage = "將刪除此 GIF 檔案與任務記錄，本機原始影片會保留。"
    gifDeleteFailed = "刪除失敗，請檢查檔案權限"
    gifCannotDeleteFile = "無法刪除檔案"
    statusWaitingAuth = "等待登入/驗證"
    imageOriginal = "圖文：原始圖片"
    imageZip = "圖文：ZIP 封存"
    coverOriginal = "%s: 原圖"
    retryExport = "重試匯出"
    coverContentDesc = "封面"
    errorPrefix = "錯誤: %s"
    viewDetails = "檢視詳情"
    failureLogTitle = "下載失敗詳細記錄"
    copyLog = "複製記錄"
    mergingTracks = "正在合併音視訊軌..."
    remainingTime = "剩餘 %s"
    fileMissing = "檔案不存在或已被移動"
    noPlayer = "找不到可開啟此檔案的播放器或相簿"
    shareVideo = "分享影片"
    shareFailed = "分享失敗: %s"
    deleteLocalFiles = "同時刪除已下載的本機影片檔案"
    failedEpisodes = "失敗 %d 集"
    preparing = "準備中"
    relayAddHint = "輸入網址後會自動擷取網站名稱，也可手動修改："
    relayUrlLabel = "網站完整網址"
    relayRefetchName = "重新擷取名稱"
    relayClearUrl = "清除網址"
    relayNameLabel = "網站名稱"
    relayNamePlaceholder = "例如：備用解析工具"
    relayClearName = "清除名稱"
    relayFillRequired = "請完整填寫網站名稱與網址"
    confirmAdd = "確認新增"
    relayCompatTitle = "第三方網頁使用須知與相容性提示"
    relayCompatS1Title = "1. 服務性質聲明"
    relayCompatS1Desc = "備用中轉功能僅提供第三方網頁的載入容器與通用直鏈嗅探輔助，本應用程式未內建、亦未與任何第三方中轉解析網站合作，不保證任何第三方網站的長期可用性。"
    relayCompatS2Title = "2. 相容性與網頁行為"
    relayCompatS2Desc = "各網站的頁面互動、反爬機制與解析流程差異甚大。使用過程中若遇人機驗證、廣告跳轉、暫時失效或防盜鏈攔截，均屬網頁端行為，建議優先使用主畫面的原生直連解析。"
    relayCompatS3Title = "3. 安全與隱私保護"
    relayCompatS3Desc = "請確認您新增並存取的網址合法合規。載入第三方網頁時，請勿輸入個人帳號、密碼或敏感隱私資訊，以防釣魚與安全風險。"
    relayCompatAccept = "我已知悉並繼續"
    relayConnecting = "正在連線中轉站…"
    relayTimeout = "中轉站回應逾時，可點擊「視窗排查」完成驗證或手動解析"
    relayPageFailed = "中轉站頁面載入失敗，可使用視窗排查"
    relayPasteFirst = "請先在上方輸入框貼上要解析的影片連結"
    relayExpandAll = "展開全部 %d 個中轉網站"
    relayCollapseAll = "收合部分中轉網站"
    relaySuccess = "中轉解析成功，已自動加入本機下載佇列！"
    relayFallbackTitle = "%s 影片"
    relayDirect = "直接解析"
    relayWindowDebug = "視窗排查"
    relayResolvingDefault = "正在背景自動擷取影片直鏈並加入下載..."
    relayBack = "返回"
    relayFillLink = "填入連結"
    relayRefresh = "重新整理"
    relayOpenExternal = "在外部瀏覽器開啟"
    relayExternalFailed = "無法開啟外部瀏覽器"
    douyinVideo = "影片"
    douyinImages = "圖文"
    douyinCaptureNotice = "請完成登入或平台驗證，開啟目標%s後點擊擷取。"
    douyinVerifyNotice = "請完成平台驗證；目標影片就緒後會自動繼續擷取。"
    douyinBrowserTitle = "抖音%s瀏覽器"
    douyinCookieDisabled = "抖音 Cookie 已停用。瀏覽器輔助需要使用網頁登入狀態。"
    enableCookieContinue = "開啟 Cookie 並繼續"
    douyinNoId = "尚未確認目標作品 ID，請返回重新解析分享連結。"
    douyinNotTarget = "目前頁面不是目標作品，請返回目標%s頁後擷取。"
    douyinPageLeft = "頁面已離開目標作品，請返回後重試"
    douyinExtractTimeout = "擷取逾時，請確認目標%s已正常載入"
    douyinStillNoTarget = "網頁仍未提供目標%s，請完成驗證並播放目標作品後重試"
    douyinIdMismatch = "目標作品 ID 不符"
    douyinReading = "正在讀取目標%s…"
    douyinExtract = "擷取此作品%s"
    pixivLoginHint = "請使用能檢視目標作品的 Pixiv 帳號登入，完成後點擊下方儲存工作階段，再重新解析作品。"
    sessionNotCaptured = "尚未取得工作階段，請先完成網頁登入後再儲存"
    desktopSite = " · 桌面網站"
    mobileSite = " · 手機版"
    switchToMobile = "切換手機版"
    switchToDesktop = "切換桌面版"
    reloadLogin = "重新載入登入頁"
    iHaveLoggedIn = "我已登入，儲存工作階段"
    loginNotice = "請在網頁內選擇掃碼或手機號登入，應用程式喚起連結不會離開目前登入頁"
    cookieDisabledTitle = "%s 的 Cookie 已停用"
    cookieDisabledBody = "瀏覽器輔助會使用網頁登入狀態。可開啟該平台 Cookie 後繼續，或關閉視窗保持匿名解析。"
    desktopSwitchLabel = "桌面網站（切換手機版）"
    mobileSwitchLabel = "手機版（切換桌面網站）"
    downloadCaptured = "下載目前擷取的媒體（請確認來自目標內容）"
    imageInputLabel = "貼上單一作品連結或分享文字"
    savePathDesc = "儲存路徑：%s"
    resetDefaultFolder = "恢復預設目錄"
    analyzingEllipsis = "正在解析…"
    analyzeImages = "解析圖文"
    resolutionPending = "解析度待確認"
    formatPending = "格式待確認"
    sizePending = "大小待確認"
    currentAccountAccess = "目前帳號：%1\$s · 存取：%2\$s"
    unverified = "未驗證"
    browserMoreQuality = "以瀏覽器讀取更多畫質"
    gifBadge = "GIF 動圖"
    imagePreviewUnavailable = "圖片暫時無法預覽，可關閉後重試或儲存原圖"
    previousImage = "上一張"
    nextImage = "下一張"
    zoomHint = "雙指縮放 · 雙擊還原/放大"
    webProxyChanged = "瀏覽器網路已變更，請關閉舊頁面後重試"
    webProxyUnsupported = "系統 WebView 暫不支援指定代理"
    notifQueued = "任務已加入佇列"
    notifPreparing = "準備下載..."
    notifRemaining = "· 剩餘 %s"
    notifDownloadError = "下載過程中發生錯誤"
    notifTaskError = "任務執行異常"
    notifAnalyzingTitle = "正在解析媒體"
    notifAnalyzingText = "可返回應用程式檢視解析結果"
    notifChannelName = "下載任務"
    notifChannelDesc = "顯示目前影片/音訊的即時下載與合併進度"
    notifProcessing = "正在處理"
    notifDownloading = "正在下載"
    notifCompletedTitle = "下載完成"
    audioLabelMp3 = "MP3 格式 (高相容性)"
    audioLabelM4a = "M4A 格式 (高音質 AAC)"
    audioLabelFlac = "FLAC 格式 (無損音訊)"
    audioLabelOpus = "OPUS 格式 (超高壓縮)"
    vmClipboardNoLink = "剪貼簿中未發現有效連結"
    vmClipboardUnreadable = "無法讀取剪貼簿"
    vmEnterLink = "請輸入或貼上作品連結"
    vmArtistProgress = "已讀取 %1\$d/%2\$d 個作品…"
    vmImagesFound = "已找到 %d 張圖片…"
    vmNoMediaResources = "此作品沒有可確認的圖片資源"
    vmAnalysisCancelled = "解析工作階段已變更"
    vmAnalysisFailedDetail = "解析失敗，點擊對話框檢視詳情"
    vmSaveInProgress = "圖片正在儲存，請稍後重試"
    vmImageTaskLimit = "每次最多新增 500 個圖片任務，請減少選取或依貼文打包"
    vmImageArchiveTitle = "原圖封存"
    vmImageSaveStartFailed = "無法啟動圖片儲存，請重試"
    vmAnalysisStopped = "已停止解析，已取得的圖片仍可儲存或繼續解析"
    vmPastedAnalyzing = "已讀取剪貼簿，正在解析..."
    vmPastedVideoLink = "已從剪貼簿貼上影片連結"
    vmEnterVideoUrl = "請輸入或貼上影片網址"
    vmRelayAddressHint = "提示：此為中轉網站網址，請貼上具體影片連結"
    vmRelayAddressDetail = "偵測到您輸入的是中轉解析站網址 (%s)。\n\n請在輸入框貼上要下載的真實影片連結（如 Bilibili、YouTube、TikTok、X 等平台的分享連結），或在下方中轉站卡片內輸入影片連結進行解析。"
    vmCollectionDetected = "偵測到合集內容(共%d部影片)，請選擇下載選項"
    vmCoverOriginal = "原圖封面"
    vmDefaultQuality = "預設畫質"
    vmCoverSuffix = "%s (封面)"
    vmAddedQueueWithCover = "已加入下載佇列 (含封面): %s"
    vmAddedQueue = "已加入下載佇列: %s"
    vmRelayVideoTitle = "中轉下載影片"
    vmRelayAuthor = "第三方中轉"
    vmRelayDirectLink = "中轉直鏈"
    vmRelayCaptured = "已從備用站擷取並加入下載佇列: %s"
    vmNoCoverUrl = "未發現封面圖網址"
    vmCoverStarted = "已開始下載封面: %s"
    vmSelectVideosFirst = "請先勾選要下載的影片"
    vmBestQuality = "最佳畫質"
    vmCollectionTitle = "合集影片"
    vmCollectionQueued = "已加入合集下載佇列 (共 %d 集)"
    vmDownloadStarted = "已開始下載: %s"
    vmTaskCancelled = "任務已取消"
    vmDeletedWithFile = "已刪除記錄與本機檔案"
    vmRemovedFromList = "已從清單移除"
    vmCollectionCancelled = "合集下載已取消"
    vmCollectionDeletedWithFile = "已刪除合集記錄與本機檔案"
    vmCollectionRemoved = "已從清單移除合集"
    vmCollectionRetried = "已重新開始下載合集中未完成的項目 (%d 集)"
    vmClearedCount = "已清除 %d 筆任務記錄"
    vmNothingToClear = "目前沒有需要清除的歷史記錄"
    vmStoppedTasks = "已終止 %d 個任務並清理殘留檔案"
    vmEngineUpdated = "核心引擎更新成功: %s"
    vmEngineUpdateFailed = "更新引擎失敗:\n%s"
    androidUpdateTitle = "發現 Android 新版本 %s"
    androidUpdateBody = "前往 GitHub 發行頁查看更新內容並下載 APK。"
    androidUpdateRelease = "查看發行版本"
    browserUnavailable = "找不到可開啟 GitHub 發行頁的瀏覽器。"
    platformLabelWeibo = "微博"
    ytDlpPlatformsTitle = "yt-dlp 支援的平台"
    ytDlpPlatformsDescription = "以下解析器來自目前的引擎。請在首頁貼上影片連結以檢查可用格式。部分平台需要登入或額外的引擎元件。"
    ytDlpPlatformsSearch = "搜尋平台或解析器"
    ytDlpPlatformsNoResults = "找不到符合的平台"
    ytDlpPlatformsUnavailable = "暫時無法讀取 yt-dlp 平台清單"
    platformLabelXigua = "西瓜影片"
    platformLabelYouku = "優酷"
    platformLabelIqiyi = "愛奇藝"
    platformLabelHuya = "虎牙"
    analyzeClipboard = "解析剪貼簿"
}

val EnStrings = appStrings {
    navHome = "Home"
    navTasks = "Tasks"
    navSettings = "Settings"
    appSubtitle = "Fast downloads for Bilibili, TikTok, Douyin, X, YouTube & 10+ platforms"
    inputPlaceholder = "Paste video link or shared text..."
    clear = "Clear"
    paste = "Paste"
    pasteClipboard = "Paste Clipboard"
    startAnalyze = "Analyze"
    analyzing = "Analyzing..."
    activeTasksTitle = "Active Downloads"
    viewAll = "View All"
    analyzeFailedTitle = "Analysis Failed"
    proxyTip = "Tip: Accessing YouTube/X may require a proxy. You can configure proxy port in Settings."
    copyErrorLog = "Copy Error Log"
    errorLogCopied = "Error log copied to clipboard"
    setProxy = "Set Proxy"
    tasksTitle = "Downloads"
    tabAll = "All"
    tabDownloading = "Downloading"
    tabCompleted = "Completed"
    emptyAll = "No download tasks yet"
    emptyDownloading = "No active download tasks"
    emptyCompleted = "No completed downloads yet"
    clearCompleted = "Clear History"
    clearTasksDialogTitle = "Clear Download Records"
    clearTasksDialogMessage = "Are you sure you want to clear finished and failed tasks?\n(Downloaded media files will remain intact)"
    clearDownloadingTitle = "Cancel Incomplete Tasks"
    clearDownloadingMessage = "Are you sure you want to cancel all incomplete tasks and delete temporary files? This will stop all downloads immediately."
    confirmStopAndClean = "Stop & Clean"
    confirmClear = "Clear Now"
    openFolder = "Open Folder"
    play = "Play"
    retry = "Retry"
    delete = "Delete"
    cancel = "Cancel"
    settingsAppearanceTitle = "Appearance & language"
    settingsAppearanceSummary = "Theme mode and interface language"
    settingsDownloadsTitle = "Downloads & storage"
    settingsDownloadsSummary = "Save location and cache cleanup"
    settingsNetworkTitle = "Network proxy"
    settingsNetworkSummary = "HTTP and SOCKS proxy connections"
    settingsRelayTitle = "Relay sites"
    settingsRelaySummary = "Add and manage parsing sites"
    settingsAccountsTitle = "Accounts"
    settingsAccountsSummary = "Cookies and sign-in management"
    settingsEngineTitle = "Download engine"
    settingsEngineSummary = "Runtime status and component updates"
    settingsAboutTitle = "About"
    settingsAboutSummary = "App version and supported platforms"
    navigateBack = "Back"
    settingsTitle = "Settings"
    languageSection = "Language"
    appearanceSection = "Appearance"
    appearanceSystem = "System"
    appearanceLight = "Light"
    appearanceDark = "Dark"
    languageZhCn = "简体中文"
    languageZhTw = "繁體中文"
    languageEn = "English"
    languageJa = "日本語"
    proxySection = "Network Proxy"
    proxyHint = "HTTP / HTTPS / SOCKS5 proxies; leave blank to test a direct connection"
    proxyPlaceholder = ""
    applyProxy = "Save & Apply"
    clearProxy = "Clear Proxy"
    proxySaved = "Proxy updated"
    proxyCleared = "Proxy cleared"
    bilibiliCookieSection = "Login Bilibili (Cookie / SESSDATA)"
    bilibiliCookieHint = "Unlock 1080P / 4K HD Quality"
    bilibiliCookieActive = "Configured (1080P/4K Unlocked)"
    bilibiliCookiePlaceholder = "Paste SESSDATA=xxx or full Cookie (empty for guest)"
    bilibiliCookieSaved = "Bilibili credentials saved"
    bilibiliCookieCleared = "Bilibili credentials cleared"
    clearBilibiliCookie = "Clear"
    applyBilibiliCookie = "Save & Apply"
    bilibiliCookieHelp = "How to get? Log in to bilibili.com on your PC browser, press F12, find SESSDATA under Application -> Cookies, and paste it here; or click the button above to log in via built-in web browser to auto-extract."
    bilibiliGuideTitle = "Unlock 1080P / 4K HD Quality"
    bilibiliGuideMessage = "Bilibili limits unauthenticated users to a maximum of 480P. To download HD Bilibili videos, please acquire login credentials below."
    bilibiliGuideLogin = "Quick Login"
    bilibiliGuideLater = "Later in Settings"
    bilibiliAutoLoginBtn = "Quick Web Login"
    bilibiliLoginDialogTitle = "Bilibili Official Secure Login"
    bilibiliLoginAutoDetecting = "Credentials will be automatically captured once logged in..."
    bilibiliLoginSuccess = "🎉 Bilibili credentials captured! 1080P/4K unlocked"
    bilibiliCloseDialog = "Close"
    storageSection = "Storage Location"
    customPathHint = "Custom Download Path:"
    defaultPath = "Default (Download/OmniDownloader)"
    pathPlaceholder = "Leave empty for default folder"
    chooseFolder = "Choose Folder"
    resetDefault = "Reset to Default"
    pathSaved = "Download path updated"
    pathReset = "Restored to default download path"
    engineSection = "Core Engine"
    updateEngine = "Update Core Extractor"
    updating = "Updating..."
    engineHint = "Fetch latest yt-dlp extraction rules"
    appInfoSection = "App Info"
    appVersion = "Version"
    formatSheetTitle = "Download Options"
    mediaSelection = "Select Item"
    downloadType = "Format Type"
    typeVideo = "Video"
    typeAudio = "Audio"
    resolution = "Resolution"
    audioFormat = "Audio Format"
    startDownload = "Download"
    downloadAll = "Download All"
    gifHint = "Recognized as animated GIF / clip, can be downloaded as MP4"
    typeCover = "Save Cover"
    saveCover = "Save Cover"
    saveCoverSuccess = "Cover saved to gallery"
    coverDescription = "HD original cover art, saved to gallery directly"
    downloadSingleMode = "Single"
    downloadCollectionMode = "Collection"
    selectAll = "Select All"
    deselectAll = "Deselect"
    invertSelection = "Invert"
    selectedEpisodesCount = "Selected %d / %d"
    downloadSelected = "Download Selected"
    pleaseSelectEpisode = "Please select at least one video"
    tabVideo = "Video"
    tabVideoSub = "HD + Audio"
    tabMute = "Video only"
    tabMuteSub = "Video only"
    tabAudio = "Audio"
    tabAudioSub = "MP3 / M4A"
    tabGif = "GIF"
    tabGifSub = "Looping"
    attachedCoverTitle = "Add-on: HD Cover Image"
    attachedCoverDesc = "Save original high-resolution cover directly to gallery"
    saveCoverAction = "Save Cover"
    selectResolutionWithAudio = "Select video resolution (auto-merge best audio)"
    selectResolutionMute = "Select muted video resolution (no audio stream)"
    selectAudioFormat = "Select audio export format"
    gifEngineTitle = "High-Fidelity GIF Conversion"
    gifEngineDesc = "• Built-in FFmpeg 2-Pass palette engine for vivid colors and compact file size\n• Standard .gif format compatible with photo galleries and messaging apps\n• Switch back to 'Video' anytime to download as MP4"
    switchMultiVideoPrompt = "Tap to switch preview and download video:"
    switchBilibiliPartPrompt = "Tap to switch Bilibili episode / part:"
    videoPartLabel = "Video %d"
    batchDownloadAudio = "Download Selected Audio (%d tracks · %s)"
    batchDownloadGif = "Download Selected GIFs (%d items)"
    batchDownloadVideos = "Download Selected Episodes (%d items · %s)"
    downloadAllCount = "Download All (%d items)"
    switchToCollectionMode = "Switch to Collection Download (%d items)"
    engineStatusTitle = "Core Engine Status"
    engineStatusRunning = "Running"
    engineStatusNotReady = "Not Ready"
    storageCleanTitle = "Storage & Cache Cleanup"
    cleanCacheAction = "Clear Cache & Temporary Files"
    cleanCacheSuccess = "Cache cleared and storage freed"
    customBadge = "Custom"
    changeFolderDialogTitle = "Change Download Directory"
    changeFolderDialogHint = "Select preset or open system file picker:"
    selectViaFilePicker = "Pick Folder in File Manager"
    commonFolders = "Presets:"
    folderDefault = "Default"
    folderMovies = "Movies"
    folderMusic = "Music"
    pathInputLabel = "Absolute Storage Path"
    saveSettings = "Save Settings"
    statusPending = "Pending"
    statusDownloading = "Downloading"
    statusProcessing = "Processing"
    statusCompleted = "Completed"
    statusFailed = "Failed"
    statusCancelled = "Cancelled"
    badgeVideo = "Video"
    badgeMute = "Video only"
    badgeAudio = "Audio"
    badgeGif = "GIF"
    badgeCover = "Cover"
    collectionBadge = "Collection"
    collectionEpisodesCount = "%d eps"
    collectionTotalProgress = "Total: %d/%d completed (%d%%)"
    collectionCurrentSubTask = "Current (P%d/%d): %s"
    collectionAllCompleted = "All %d/%d episodes completed"
    collectionCancelAll = "Cancel All"
    collectionDelete = "Delete Collection"
    collectionRetryFailed = "Retry Failed"
    collectionPlayFirst = "Play Episode 1"
    collectionExpandDetails = "Episodes (%d total · %d done)"
    collectionCollapseDetails = "Collapse Details"
    collectionDeleteConfirmTitle = "Delete Collection"
    collectionDeleteConfirmMessage = "Are you sure you want to delete all %d episode tasks in this collection?"
    collectionCancelConfirmTitle = "Cancel Collection Download"
    collectionCancelConfirmMessage = "Are you sure you want to cancel all running and queued tasks in this collection?"
    relaySitesSection = "Third-Party Relay Sites"
    relaySitesDesc = "Add custom relay parsing websites to use on the home page"
    addRelaySiteBtn = "Add Website"
    relaySitesEmptySettings = "No relay sites configured yet. Tap above to add."
    relaySitesEmptyHome = "No relay sites yet. You can add them in Settings."
    goToSettings = "Go to Settings"
    fetchingTitle = "Fetching website title..."
    fetchTitleFailed = "Failed to fetch title, please enter manually"
    deleteRelaySiteConfirmTitle = "Delete Relay Site"
    deleteRelaySiteConfirmMessage = "Are you sure you want to delete \"%s\"?"
    sessionSection = "Platform sessions"
    sessionDesc = "Import a Netscape cookies file so parsing and downloads share one session. Cookies are stored encrypted on this device only."
    sessionPlatformLabel = "Platform"
    sessionImport = "Import cookies file"
    sessionClear = "Clear session"
    sessionMissing = "Not imported"
    sessionImported = "Imported"
    sessionNeedsAuth = "Needs re-authentication"
    sessionImportSuccess = "Session imported"
    sessionImportFailed = "Invalid or unreadable cookies file"
    sessionCleared = "Session cleared"
    sessionManualClearHint = "Saved download credentials have been cleared. The built-in browser may still be signed in. If you used Google or another sign-in provider, manually sign out of both the provider and the platform to prevent automatic sign-in to the previous account."
    sessionManageGoogle = "Manage Google sign-in"
    sessionManageGoogleHint = "Open the account menu on Google's page and manually choose Sign out. This page does not save or import sessions. Then return to the platform to sign out or sign in again."
    requestWarningTitle = "%s request warning"
    requestWarningBody = "The platform limited requests or requires sign-in or verification. The current request stopped. Frequent retries may restrict your account. Check your account or complete verification before retrying manually. Tasks will not restart automatically."
    requestWarningWait = "The platform allows retrying after %s. Acknowledging does not cancel this waiting period."
    requestWarningConfirm = "Understood"
    requestWarningAccount = "Manage accounts"
    sessionReverify = "Verify sign-in again"
    googleAccountsTitle = "Google sign-in profiles"
    googleAccountsHint = "Use one Google account per profile. Names are local notes. Sign out of profiles individually or together; manage old shared sign-ins manually."
    googleAddAccount = "Add account"
    googleAccountName = "Profile name or email (local note)"
    googleCurrentAccount = "Current profile"
    googleUseAccount = "Use profile"
    googleOpenAccount = "Open"
    googleSignedOut = "Signed out"
    googleNoCredentials = "No linked download credentials"
    googleCredentials = "Linked platforms: %s"
    googleLogoutCurrent = "Sign out current"
    googleLogoutSelected = "Sign out selected (%d)"
    googleSelectAll = "Select all"
    googleClearSelection = "Clear selection"
    googleLogoutTitle = "Sign out and clear selected profiles?"
    googleLogoutHint = "Clear browser sign-ins in the selected profiles and download credentials still owned by them. Other profiles stay intact. This does not remove system accounts or revoke server authorizations."
    googleUnsupported = "Update Android System WebView to use separate sign-in profiles."
    googlePending = "Cleanup pending; select again to retry"
    googleOpenGoogle = "Google account page"
    googleOpenPlatform = "Platform sign-in page"
    googleSavePlatform = "Save %s download credentials"
    googleSaved = "Download credentials linked to this profile"
    googleCleanupFinished = "Selected profiles signed out and local credentials cleared"
    googleNoAccounts = "No separate profiles yet"
    googleLegacyAccount = "Shared browser sign-in (manual)"
    engineDiagnosticsTitle = "Component versions"
    engineDiagnosticsUnavailable = "Unavailable"
    sessionLogin = "Sign in to capture session"
    sessionLoginHint = "Complete sign-in in the built-in browser, then tap Done"
    sessionLoginDone = "Done"
    browserCaptureAction = "Capture in browser"
    browserCaptureTitle = "Browser media capture"
    browserCaptureHint = "Tap play on the page; the captured video address is added to downloads automatically"
    navBatch = "Batch"
    manageXCookie = "Manage X cookies"
    importYoutubeCookie = "Import YouTube cookies"
    accountsAndSessions = "Accounts & sessions"
    scrollToTop = "Back to top"
    expandPlatforms = "Show supported platforms"
    collapsePlatforms = "Hide supported platforms"
    platformLabelBilibili = "Bilibili"
    platformLabelDouyin = "Douyin"
    platformLabelKuaishou = "Kuaishou"
    platformLabelXiaohongshu = "Xiaohongshu"
    platformLabelHeybox = "Heybox"
    platformLabelPixiv = "Pixiv"
    platformLabelX = "X (Twitter)"
    platformLabelYoutube = "YouTube"
    close = "Close"
    open = "Open"
    share = "Share"
    save = "Save"
    viewAction = "View"
    deleteContentDesc = "Delete"
    menuPaneTitle = "Options menu"
    settingsGifSummary = "Manage GIFs and convert local videos"
    folderPermissionDenied = "Folder permission denied. Please choose again"
    engineFeedback = "Details: %s"
    proxyTestFailed = "Connectivity test failed. Check your network and try again"
    proxyTesting = "Testing…"
    proxyTestAction = "Test connection"
    disableCookie = "Disable cookies"
    disableCookieHint = "Only affects future parsing and new downloads for %s; saved sessions stay encrypted."
    cookieDisabledRetained = "Disabled · session kept"
    googleLoginHint = "Google may refuse sign-in in the built-in browser. If that happens, import cookies.txt above."
    imageDownloadPath = "Image download path"
    followDefaultDownloadPath = "Follow the default download path"
    followDefaultPathAction = "Use default path"
    chooseImageFolder = "Choose image folder"
    relaySiteAdded = "Relay site added: %s"
    relaySiteDeleted = "Deleted: %s"
    verifyImagesAndExtract = "Verify and extract the target post"
    statusAnalyzingShort = "Parsing"
    statusCompletedShort = "Completed"
    statusIncompleteShort = "Incomplete"
    artistProgress = "%1\$s · %2\$d/%3\$d works · %4\$d images · %5\$d animations"
    stopAnalyzing = "Stop parsing"
    continueAnalyzing = "Continue parsing"
    xProfileReading = "Reading profile · %1\$d image posts · %2\$d images"
    xProfileComplete = "Platform returned the last page · %1\$d image posts · %2\$d images"
    xProfileIncomplete = "Parsing incomplete · kept %d image posts"
    selectedCount = "Selected %1\$d / %2\$d"
    zipByPost = "ZIP by post"
    zipByArtwork = "ZIP by artwork"
    zipArchive = "Create ZIP"
    ugoiraCoverHint = "Animation cover · plays after download"
    downloadAnimation = "Download animation"
    saveThisImage = "Save this image"
    animationFormat = "Animation: %s ▾"
    originalFramesZip = "Original frames ZIP"
    clearSelection = "Clear selection"
    savingEllipsis = "Saving…"
    selectAnimationDesc = "Select animation %d"
    selectImageDesc = "Select image %d"
    previewImageDesc = "Preview image"
    imageDesc = "Image %d"
    ugoiraIndexLabel = "%1\$d · Animation (cover)"
    imageIndexLabel = "%1\$d · %2\$d×%3\$d"
    batchTitle = "Batch download"
    batchSmartMode = "Smart detect"
    batchManualMode = "Add one by one"
    batchInputManual = "Enter one link or a single share text"
    batchInputSmart = "Paste multiple links or the full share text"
    batchAddLink = "Add link"
    batchRecognizeAdd = "Detect and add"
    batchAllEpisodes = "Download every episode of collections / multi-video posts"
    batchStart = "Parse and add to queue"
    batchStopAdding = "Stop adding (queued tasks keep downloading)"
    batchRetryPending = "Continue / retry pending links"
    batchProgressSummary = "Queued %1\$d / %2\$d links · %3\$d tasks"
    batchViewTasks = "View download tasks"
    batchFilledCount = "Links entered %d / 100"
    batchClearInput = "Clear input"
    batchAddedToQueue = "Added to the download queue"
    batchRemove = "Remove"
    batchHistoryTitle = "Batch history"
    batchClearHistory = "Clear history"
    batchWaiting = "Waiting"
    batchSingleAnalyze = "Parse / verify separately"
    batchClearLinksTitle = "Clear entered links?"
    batchClearLinksMessage = "Their parse results are cleared. Queued downloads continue."
    batchClearHistoryTitle = "Clear batch history?"
    batchClearHistoryMessage = "Entered links and download tasks stay; running downloads are unaffected."
    clipboardEmpty = "Clipboard is empty"
    batchTextTooLong = "Text is too long. Add it in smaller batches"
    clipboardReadFailed = "Could not read the clipboard"
    gifConvertLocal = "Convert local video to GIF"
    gifCannotReadVideo = "Could not read the selected video"
    gifShareChooser = "Share GIF"
    gifNoApp = "The file is unavailable or no app can open GIFs"
    gifChooseVideo = "Choose a local video to convert"
    gifBackgroundHint = "Conversion runs in the background. Track, cancel or retry it on the Tasks page."
    gifEmpty = "No GIFs yet"
    gifDeleteTitle = "Delete GIF?"
    gifDeleteMessage = "This removes the GIF file and its task record. The original local video is kept."
    gifDeleteFailed = "Delete failed. Check file permissions"
    gifCannotDeleteFile = "Could not delete the file"
    statusWaitingAuth = "Waiting for sign-in"
    imageOriginal = "Images: original files"
    imageZip = "Images: ZIP archive"
    coverOriginal = "%s: original"
    retryExport = "Retry export"
    coverContentDesc = "Cover"
    errorPrefix = "Error: %s"
    viewDetails = "View details"
    failureLogTitle = "Download failure log"
    copyLog = "Copy log"
    mergingTracks = "Merging audio and video tracks..."
    remainingTime = "%s left"
    fileMissing = "The file is missing or was moved"
    noPlayer = "No player or gallery app can open this file"
    shareVideo = "Share video"
    shareFailed = "Share failed: %s"
    deleteLocalFiles = "Also delete downloaded local video files"
    failedEpisodes = "%d failed"
    preparing = "Preparing"
    relayAddHint = "The site name is detected after entering a URL; you can edit it:"
    relayUrlLabel = "Full site URL"
    relayRefetchName = "Detect name again"
    relayClearUrl = "Clear URL"
    relayNameLabel = "Site name"
    relayNamePlaceholder = "e.g. Backup parser"
    relayClearName = "Clear name"
    relayFillRequired = "Enter both a site name and a URL"
    confirmAdd = "Add"
    relayCompatTitle = "Third-party pages: notice and compatibility"
    relayCompatS1Title = "1. What this feature is"
    relayCompatS1Desc = "The relay feature only hosts third-party pages and provides generic direct-link sniffing. The app bundles no parser and has no partnership with any relay site; availability of third-party sites is not guaranteed."
    relayCompatS2Title = "2. Compatibility and page behavior"
    relayCompatS2Desc = "Page interactions, anti-bot measures and parsing paths differ widely between sites. CAPTCHAs, ad redirects, temporary outages or hotlink protection come from the webpage itself; prefer built-in direct parsing when possible."
    relayCompatS3Title = "3. Safety and privacy"
    relayCompatS3Desc = "Only add and visit lawful sites. Never enter personal accounts, passwords or sensitive information on third-party pages; beware of phishing and other risks."
    relayCompatAccept = "I understand, continue"
    relayConnecting = "Connecting to the relay site…"
    relayTimeout = "The relay site timed out. Use Window check to finish verification or parse manually"
    relayPageFailed = "The relay page failed to load. Try Window check"
    relayPasteFirst = "Paste the video link into the box above first"
    relayExpandAll = "Show all %d relay sites"
    relayCollapseAll = "Collapse relay sites"
    relaySuccess = "Relay parsing succeeded and the download was queued!"
    relayFallbackTitle = "%s video"
    relayDirect = "Parse directly"
    relayWindowDebug = "Window check"
    relayResolvingDefault = "Extracting the video direct link in the background..."
    relayBack = "Back"
    relayFillLink = "Fill link"
    relayRefresh = "Reload"
    relayOpenExternal = "Open in external browser"
    relayExternalFailed = "Could not open an external browser"
    douyinVideo = "video"
    douyinImages = "image post"
    douyinCaptureNotice = "Sign in or complete platform verification, open the target %s, then tap Extract."
    douyinVerifyNotice = "Complete platform verification; extraction continues automatically once the video is ready."
    douyinBrowserTitle = "Douyin %s browser"
    douyinCookieDisabled = "Douyin cookies are disabled. The browser helper needs a web sign-in."
    enableCookieContinue = "Enable cookies and continue"
    douyinNoId = "Target post ID not confirmed. Go back and parse the share link again."
    douyinNotTarget = "This is not the target post. Return to the target %s page and extract."
    douyinPageLeft = "The page left the target post. Return and try again"
    douyinExtractTimeout = "Extraction timed out. Make sure the target %s has loaded"
    douyinStillNoTarget = "The page still has no target %s. Verify, play the target, then retry"
    douyinIdMismatch = "Target post ID mismatch"
    douyinReading = "Reading the target %s…"
    douyinExtract = "Extract this %s"
    pixivLoginHint = "Sign in with a Pixiv account that can view the target work, save the session below, then parse again."
    sessionNotCaptured = "No session captured yet. Finish signing in on the page, then save"
    desktopSite = " · Desktop site"
    mobileSite = " · Mobile site"
    switchToMobile = "Switch to mobile"
    switchToDesktop = "Switch to desktop"
    reloadLogin = "Reload sign-in page"
    iHaveLoggedIn = "I have signed in, save session"
    loginNotice = "Use QR or phone sign-in on the page; app deep links will not leave this sign-in page"
    cookieDisabledTitle = "Cookies for %s are disabled"
    cookieDisabledBody = "The browser helper uses the web sign-in state. Enable cookies for this platform, or close the window to keep parsing anonymously."
    desktopSwitchLabel = "Desktop site (switch to mobile)"
    mobileSwitchLabel = "Mobile site (switch to desktop)"
    downloadCaptured = "Download the captured media (confirm it matches the target)"
    imageInputLabel = "Paste a single work link or share text"
    savePathDesc = "Save path: %s"
    resetDefaultFolder = "Reset to default folder"
    analyzingEllipsis = "Parsing…"
    analyzeImages = "Parse image post"
    resolutionPending = "Resolution pending"
    formatPending = "Format pending"
    sizePending = "Size pending"
    currentAccountAccess = "Account: %1\$s · Access: %2\$s"
    unverified = "Not verified"
    browserMoreQuality = "Load more qualities in browser"
    gifBadge = "GIF"
    imagePreviewUnavailable = "Preview unavailable. Close and retry, or save the original"
    previousImage = "Previous"
    nextImage = "Next"
    zoomHint = "Pinch to zoom · double-tap to reset or enlarge"
    webProxyChanged = "Browser network changed. Close the old page and try again"
    webProxyUnsupported = "System WebView does not support a custom proxy yet"
    notifQueued = "Task queued"
    notifPreparing = "Preparing download..."
    notifRemaining = "· %s left"
    notifDownloadError = "An error occurred during download"
    notifTaskError = "Task failed unexpectedly"
    notifAnalyzingTitle = "Parsing media"
    notifAnalyzingText = "Return to the app to see the result"
    notifChannelName = "Downloads"
    notifChannelDesc = "Live download and merge progress for the current video or audio"
    notifProcessing = "Processing"
    notifDownloading = "Downloading"
    notifCompletedTitle = "Download complete"
    audioLabelMp3 = "MP3 (widely compatible)"
    audioLabelM4a = "M4A (high-quality AAC)"
    audioLabelFlac = "FLAC (lossless audio)"
    audioLabelOpus = "OPUS (high compression)"
    vmClipboardNoLink = "No valid link found in the clipboard"
    vmClipboardUnreadable = "Could not read the clipboard"
    vmEnterLink = "Enter or paste a work link"
    vmArtistProgress = "Read %1\$d/%2\$d works…"
    vmImagesFound = "Found %d images…"
    vmNoMediaResources = "This work has no confirmable image resources"
    vmAnalysisCancelled = "The parsing session changed"
    vmAnalysisFailedDetail = "Parsing failed. Tap the dialog for details"
    vmSaveInProgress = "Images are being saved. Try again shortly"
    vmImageTaskLimit = "Up to 500 image tasks per batch; select fewer or archive by post"
    vmImageArchiveTitle = "Original archive"
    vmImageSaveStartFailed = "Could not start saving images. Try again"
    vmAnalysisStopped = "Parsing stopped; images already found can be saved or resumed"
    vmPastedAnalyzing = "Clipboard read, parsing..."
    vmPastedVideoLink = "Video link pasted from the clipboard"
    vmEnterVideoUrl = "Enter or paste a video URL"
    vmRelayAddressHint = "This is a relay site address. Paste the actual video link"
    vmRelayAddressDetail = "The input is a relay parser address (%s).\n\nPaste the real video link you want to download (share links from Bilibili, YouTube, TikTok, X, etc.), or enter the video link in the relay card below."
    vmCollectionDetected = "Collection detected (%d videos). Choose download options"
    vmCoverOriginal = "Original cover"
    vmDefaultQuality = "Default quality"
    vmCoverSuffix = "%s (cover)"
    vmAddedQueueWithCover = "Added to queue (with cover): %s"
    vmAddedQueue = "Added to queue: %s"
    vmRelayVideoTitle = "Relay video"
    vmRelayAuthor = "Third-party relay"
    vmRelayDirectLink = "Relay direct link"
    vmRelayCaptured = "Captured from the backup site and queued: %s"
    vmNoCoverUrl = "No cover image URL found"
    vmCoverStarted = "Cover download started: %s"
    vmSelectVideosFirst = "Select the videos to download first"
    vmBestQuality = "Best quality"
    vmCollectionTitle = "Collection"
    vmCollectionQueued = "Collection queued (%d episodes)"
    vmDownloadStarted = "Download started: %s"
    vmTaskCancelled = "Task cancelled"
    vmDeletedWithFile = "Record and local file deleted"
    vmRemovedFromList = "Removed from the list"
    vmCollectionCancelled = "Collection download cancelled"
    vmCollectionDeletedWithFile = "Collection record and local files deleted"
    vmCollectionRemoved = "Collection removed from the list"
    vmCollectionRetried = "Restarted unfinished episodes (%d)"
    vmClearedCount = "Cleared %d task records"
    vmNothingToClear = "No history to clear"
    vmStoppedTasks = "Stopped %d tasks and cleaned partial files"
    vmEngineUpdated = "Core engine updated: %s"
    vmEngineUpdateFailed = "Engine update failed:\n%s"
    androidUpdateTitle = "Android update %s available"
    androidUpdateBody = "Open the GitHub release to read the changes and download the APK."
    androidUpdateRelease = "View release"
    browserUnavailable = "No browser is available to open the GitHub release."
    platformLabelWeibo = "Weibo"
    ytDlpPlatformsTitle = "yt-dlp platforms"
    ytDlpPlatformsDescription = "Paste a video link on the home screen to check available formats. Some platforms require sign-in or additional engine components."
    ytDlpPlatformsSearch = "Search platforms or extractors"
    ytDlpPlatformsNoResults = "No matching platforms"
    ytDlpPlatformsUnavailable = "Could not load the yt-dlp list"
    platformLabelXigua = "Xigua Video"
    platformLabelYouku = "Youku"
    platformLabelIqiyi = "iQIYI"
    platformLabelHuya = "Huya"
    analyzeClipboard = "Analyze Clipboard"
}

val JaStrings = appStrings {
    navHome = "ホーム"
    navTasks = "タスク"
    navSettings = "設定"
    appSubtitle = "Bilibili、TikTok、X、YouTubeなど主要10大プラットフォーム対応"
    inputPlaceholder = "リンクまたは共有テキストを貼り付け..."
    clear = "クリア"
    paste = "貼り付け"
    pasteClipboard = "クリップボード貼付"
    startAnalyze = "解析開始"
    analyzing = "解析中..."
    activeTasksTitle = "ダウンロード中のタスク"
    viewAll = "すべて見る"
    analyzeFailedTitle = "解析に失敗しました"
    proxyTip = "ヒント: YouTubeやXなどの解析にはプロキシが必要な場合があります。設定画面で構成可能です。"
    copyErrorLog = "エラーログをコピー"
    errorLogCopied = "エラーログをコピーしました"
    setProxy = "プロキシ設定"
    tasksTitle = "ダウンロード管理"
    tabAll = "すべて"
    tabDownloading = "ダウンロード中"
    tabCompleted = "完了"
    emptyAll = "ダウンロードタスクはありません"
    emptyDownloading = "進行中のダウンロードはありません"
    emptyCompleted = "完了したダウンロードはありません"
    clearCompleted = "履歴を消去"
    clearTasksDialogTitle = "ダウンロード履歴の消去"
    clearTasksDialogMessage = "完了および失敗したタスクを消去しますか？\n（ダウンロード済みのファイルは削除されません）"
    clearDownloadingTitle = "未完了タスクの中止"
    clearDownloadingMessage = "未完了のダウンロードタスクをすべて中止し、一時ファイルを削除してもよろしいですか？"
    confirmStopAndClean = "中止して削除"
    confirmClear = "今すぐ消去"
    openFolder = "フォルダを開く"
    play = "再生"
    retry = "再試行"
    delete = "削除"
    cancel = "キャンセル"
    settingsAppearanceTitle = "外観と言語"
    settingsAppearanceSummary = "テーマと表示言語"
    settingsDownloadsTitle = "ダウンロードと保存"
    settingsDownloadsSummary = "保存先とキャッシュの整理"
    settingsNetworkTitle = "ネットワーク"
    settingsNetworkSummary = "HTTP・SOCKS プロキシ接続"
    settingsRelayTitle = "中継サイト"
    settingsRelaySummary = "解析サイトの追加と管理"
    settingsAccountsTitle = "アカウント"
    settingsAccountsSummary = "Cookie とログイン管理"
    settingsEngineTitle = "ダウンロードエンジン"
    settingsEngineSummary = "動作状況とコンポーネントの更新"
    settingsAboutTitle = "このアプリについて"
    settingsAboutSummary = "バージョンと対応サイト"
    navigateBack = "戻る"
    settingsTitle = "環境設定"
    languageSection = "言語 (Language)"
    appearanceSection = "外観"
    appearanceSystem = "システムに従う"
    appearanceLight = "ライト"
    appearanceDark = "ダーク"
    languageZhCn = "简体中文"
    languageZhTw = "繁體中文"
    languageEn = "English"
    languageJa = "日本語"
    proxySection = "ネットワークプロキシ"
    proxyHint = "HTTP / HTTPS / SOCKS5 対応。空欄では直接接続をテスト"
    proxyPlaceholder = ""
    applyProxy = "保存して適用"
    clearProxy = "プロキシをクリア"
    proxySaved = "プロキシを更新しました"
    proxyCleared = "プロキシをクリアしました"
    bilibiliCookieSection = "ビリビリにログイン (Cookie / SESSDATA)"
    bilibiliCookieHint = "1080P / 4K 高画質をアンロック"
    bilibiliCookieActive = "設定済み (1080P/4K 解除済み)"
    bilibiliCookiePlaceholder = "SESSDATA=xxx または完全な Cookie を貼付 (空白でゲスト)"
    bilibiliCookieSaved = "Bilibili 認証情報を保存しました"
    bilibiliCookieCleared = "Bilibili 認証情報を消去しました"
    clearBilibiliCookie = "消去"
    applyBilibiliCookie = "保存して適用"
    bilibiliCookieHelp = "取得方法: PC ブラウザで bilibili.com にログイン後、F12 キーを押し、Application -> Cookies から SESSDATA の値をコピーして貼り付けます。または上のボタンから内蔵ブラウザでログインして自動取得します。"
    bilibiliGuideTitle = "1080P / 4K 高画質をアンロック"
    bilibiliGuideMessage = "Bilibili 公式の制限により、未ログインユーザーは最大 480P 画質に制限されています。高画質動画をダウンロードする場合は、以下からログイン認証情報を取得してください。"
    bilibiliGuideLogin = "ログイン"
    bilibiliGuideLater = "後で設定画面で設定"
    bilibiliAutoLoginBtn = "Web ログイン"
    bilibiliLoginDialogTitle = "Bilibili 公式安全ログイン"
    bilibiliLoginAutoDetecting = "ログイン完了後、自動的に認証情報を取得して閉じます..."
    bilibiliLoginSuccess = "🎉 Bilibili 認証情報を自動取得しました！1080P/4K 解除完了"
    bilibiliCloseDialog = "閉じる"
    storageSection = "保存先フォルダ"
    customPathHint = "カスタム保存先:"
    defaultPath = "デフォルト (Download/OmniDownloader)"
    pathPlaceholder = "空白でデフォルトフォルダに戻す"
    chooseFolder = "フォルダを選択"
    resetDefault = "デフォルトに戻す"
    pathSaved = "保存先を更新しました"
    pathReset = "デフォルト保存先に戻しました"
    engineSection = "コアエンジン"
    updateEngine = "オンラインでコア更新"
    updating = "更新中..."
    engineHint = "最新の yt-dlp 解析ルールを取得"
    appInfoSection = "アプリ情報"
    appVersion = "バージョン"
    formatSheetTitle = "ダウンロード構成"
    mediaSelection = "メディアを選択"
    downloadType = "ダウンロード形式"
    typeVideo = "動画"
    typeAudio = "音声"
    resolution = "動画解像度"
    audioFormat = "音声形式"
    startDownload = "ダウンロード"
    downloadAll = "すべてダウンロード"
    gifHint = "GIFアニメーションとして認識されました。高画質動画として保存できます"
    typeCover = "カバー保存"
    saveCover = "カバー保存"
    saveCoverSuccess = "カバー画像をアルバムに保存しました"
    coverDescription = "高画質な元画像カバーを直接アルバムに保存"
    downloadSingleMode = "単一動画"
    downloadCollectionMode = "一括保存"
    selectAll = "すべて選択"
    deselectAll = "選択解除"
    invertSelection = "反転"
    selectedEpisodesCount = "選択中 %d / %d"
    downloadSelected = "選択分を保存"
    pleaseSelectEpisode = "動画を1つ以上選択してください"
    tabVideo = "動画"
    tabVideoSub = "高画質・音声"
    tabMute = "映像のみ"
    tabMuteSub = "映像のみ"
    tabAudio = "音声"
    tabAudioSub = "MP3 / M4A"
    tabGif = "GIF"
    tabGifSub = "アニメ"
    attachedCoverTitle = "追加オプション: カバー画像"
    attachedCoverDesc = "高画質な元画像カバーを直接写真アルバムに保存"
    saveCoverAction = "保存する"
    selectResolutionWithAudio = "動画解像度を選択 (音声を自動合成)"
    selectResolutionMute = "映像解像度を選択 (音声なし素材)"
    selectAudioFormat = "出力音声形式を選択"
    gifEngineTitle = "高画質 GIF アニメ変換"
    gifEngineDesc = "• 高度なFFmpegパレットエンジンにより鮮明な色合いと軽量サイズを実現\n• 標準.gif形式で保存され、写真アプリ等で再生可能\n• MP4動画として保存したい場合はいつでも「動画」に切り替え可能"
    switchMultiVideoPrompt = "プレビュー・保存する動画を選択:"
    switchBilibiliPartPrompt = "プレビュー・保存するパートを選択:"
    videoPartLabel = "動画 %d"
    batchDownloadAudio = "選択した音声を保存 (%d曲 · %s)"
    batchDownloadGif = "選択したGIFを保存 (%d件)"
    batchDownloadVideos = "選択した合集を保存 (%d本 · %s)"
    downloadAllCount = "すべてダウンロード (%d本)"
    switchToCollectionMode = "合集一括ダウンロードに切り替え (%d本)"
    engineStatusTitle = "コアエンジン状態"
    engineStatusRunning = "稼働中"
    engineStatusNotReady = "未準備"
    storageCleanTitle = "ストレージとキャッシュのクリーンアップ"
    cleanCacheAction = "キャッシュと一時ファイルを消去"
    cleanCacheSuccess = "キャッシュを消去し空き容量を確保しました"
    customBadge = "カスタム"
    changeFolderDialogTitle = "ダウンロード保存先を変更"
    changeFolderDialogHint = "プリセットまたはファイル選択機能を使用:"
    selectViaFilePicker = "ファイル管理アプリでフォルダを選択"
    commonFolders = "プリセット:"
    folderDefault = "デフォルト"
    folderMovies = "動画フォルダ"
    folderMusic = "音楽フォルダ"
    pathInputLabel = "絶対パス"
    saveSettings = "設定を保存"
    statusPending = "待機中"
    statusDownloading = "ダウンロード中"
    statusProcessing = "処理中"
    statusCompleted = "完了"
    statusFailed = "失敗"
    statusCancelled = "キャンセル済"
    badgeVideo = "動画"
    badgeMute = "映像のみ"
    badgeAudio = "音声"
    badgeGif = "GIF"
    badgeCover = "カバー"
    collectionBadge = "まとめ"
    collectionEpisodesCount = "%d 話"
    collectionTotalProgress = "全体進捗: %d/%d 話完了 (%d%%)"
    collectionCurrentSubTask = "現在ダウンロード中 (P%d/%d): %s"
    collectionAllCompleted = "全 %d/%d 話のダウンロードが完了しました"
    collectionCancelAll = "合集をキャンセル"
    collectionDelete = "合集を削除"
    collectionRetryFailed = "失敗した項目を再試行"
    collectionPlayFirst = "第1話を再生"
    collectionExpandDetails = "エピソード詳細 (全 %d 話 · %d 完了)"
    collectionCollapseDetails = "折りたたむ"
    collectionDeleteConfirmTitle = "合集タスクを削除"
    collectionDeleteConfirmMessage = "この合集の全 %d 件のエピソードタスクを削除しますか？"
    collectionCancelConfirmTitle = "合集ダウンロードをキャンセル"
    collectionCancelConfirmMessage = "この合集で進行中および待機中のタスクをすべて中止しますか？"
    relaySitesSection = "サードパーティ中継サイト"
    relaySitesDesc = "予備の解析サイトを追加し、ホーム画面で直接呼び出し可能"
    addRelaySiteBtn = "サイトを追加"
    relaySitesEmptySettings = "中継サイトがありません。上部から追加してください"
    relaySitesEmptyHome = "中継サイトがありません。「設定」から追加できます"
    goToSettings = "設定へ"
    fetchingTitle = "タイトルを取得中..."
    fetchTitleFailed = "タイトルを取得できませんでした。手動で入力してください"
    deleteRelaySiteConfirmTitle = "中継サイトを削除"
    deleteRelaySiteConfirmMessage = "中継サイト「%s」を削除しますか？"
    sessionSection = "プラットフォームセッション"
    sessionDesc = "Netscape形式のCookiesファイルを読み込み、解析とダウンロードで同じセッションを使用します。Cookieは端末内の暗号化ストレージにのみ保存されます。"
    sessionPlatformLabel = "プラットフォーム"
    sessionImport = "Cookiesファイルを読み込む"
    sessionClear = "セッションを削除"
    sessionMissing = "未読み込み"
    sessionImported = "読み込み済み"
    sessionNeedsAuth = "再認証が必要"
    sessionImportSuccess = "セッションを読み込みました"
    sessionImportFailed = "Cookiesファイルが無効か読み取れません"
    sessionCleared = "セッションを削除しました"
    sessionManualClearHint = "保存済みのダウンロード認証情報を削除しました。内蔵ブラウザのログイン状態は残る場合があります。Googleなどでログインした場合は、以前のアカウントで自動ログインしないよう、Googleと対象サービスの両方から手動でログアウトしてください。"
    sessionManageGoogle = "Googleログインを管理"
    sessionManageGoogleHint = "Googleのページでアカウントメニューを開き、手動でログアウトしてください。この画面ではセッションを保存・読み込みません。その後、対象サービスに戻ってログアウトまたは再ログインしてください。"
    requestWarningTitle = "%s リクエスト警告"
    requestWarningBody = "プラットフォームから制限、ログイン失効、または認証要求が返されたため、現在のリクエストを停止しました。頻繁な再試行はアカウント制限につながる可能性があります。認証を確認した後、手動で再試行してください。タスクは自動再開しません。"
    requestWarningWait = "再試行可能日時：%s。確認してもサーバー指定の待機時間は解除されません。"
    requestWarningConfirm = "確認しました"
    requestWarningAccount = "アカウント管理"
    sessionReverify = "ログインを再確認"
    googleAccountsTitle = "Googleログインプロファイル"
    googleAccountsHint = "各プロファイルでGoogleアカウントを1つだけ使用してください。名前は端末内のメモです。個別・一括ログアウトに対応し、従来の共有ログインは手動で管理します。"
    googleAddAccount = "アカウントを追加"
    googleAccountName = "名前またはメール（端末内のメモ）"
    googleCurrentAccount = "現在のプロファイル"
    googleUseAccount = "現在に設定"
    googleOpenAccount = "開く"
    googleSignedOut = "ログアウト済み"
    googleNoCredentials = "ダウンロード認証情報なし"
    googleCredentials = "関連サービス：%s"
    googleLogoutCurrent = "現在からログアウト"
    googleLogoutSelected = "選択分からログアウト（%d）"
    googleSelectAll = "すべて選択"
    googleClearSelection = "選択を解除"
    googleLogoutTitle = "選択したプロファイルからログアウトしますか？"
    googleLogoutHint = "選択したプロファイルのブラウザログインと、そのプロファイルが保存したままのダウンロード認証情報を削除します。他のプロファイル、端末のシステムアカウント、サーバーの認可は変更しません。"
    googleUnsupported = "個別プロファイルを使うにはAndroid System WebViewを更新してください。"
    googlePending = "削除未完了。再選択して再試行してください"
    googleOpenGoogle = "Googleアカウント"
    googleOpenPlatform = "サービスのログイン"
    googleSavePlatform = "%sのダウンロード認証情報を保存"
    googleSaved = "認証情報をこのプロファイルに関連付けました"
    googleCleanupFinished = "選択したプロファイルをログアウトし認証情報を削除しました"
    googleNoAccounts = "個別プロファイルはまだありません"
    googleLegacyAccount = "共有ブラウザログイン（手動）"
    engineDiagnosticsTitle = "コンポーネントのバージョン"
    engineDiagnosticsUnavailable = "取得できません"
    sessionLogin = "ログインしてセッションを取得"
    sessionLoginHint = "内蔵ブラウザでログインを完了してから「完了」を押してください"
    sessionLoginDone = "完了"
    browserCaptureAction = "内蔵ブラウザで取得"
    browserCaptureTitle = "ブラウザメディア取得"
    browserCaptureHint = "ページで再生をタップすると、取得した動画URLが自動的にダウンロードに追加されます"
    navBatch = "一括"
    manageXCookie = "X Cookieを管理"
    importYoutubeCookie = "YouTube Cookieを読み込む"
    accountsAndSessions = "アカウントとセッション"
    scrollToTop = "先頭へ戻る"
    expandPlatforms = "対応プラットフォームを表示"
    collapsePlatforms = "対応プラットフォームを閉じる"
    platformLabelBilibili = "ビリビリ"
    platformLabelDouyin = "抖音"
    platformLabelKuaishou = "快手"
    platformLabelXiaohongshu = "小紅書"
    platformLabelHeybox = "小黑盒"
    platformLabelPixiv = "Pixiv"
    platformLabelX = "X (Twitter)"
    platformLabelYoutube = "YouTube"
    close = "閉じる"
    open = "開く"
    share = "共有"
    save = "保存"
    viewAction = "表示"
    deleteContentDesc = "削除"
    menuPaneTitle = "オプションメニュー"
    settingsGifSummary = "GIFの管理とローカル動画の変換"
    folderPermissionDenied = "フォルダへのアクセスが許可されていません。選択し直してください"
    engineFeedback = "詳細: %s"
    proxyTestFailed = "接続テストに失敗しました。ネットワークを確認して再試行してください"
    proxyTesting = "テスト中…"
    proxyTestAction = "接続テスト"
    disableCookie = "Cookieを無効化"
    disableCookieHint = "%s の今後の解析と新規ダウンロードにのみ影響します。保存済みセッションは暗号化されたまま保持されます。"
    cookieDisabledRetained = "無効 · セッション保持"
    googleLoginHint = "Googleは内蔵ブラウザでのログインを拒否する場合があります。その場合は上のcookies.txtを読み込んでください。"
    imageDownloadPath = "画像のダウンロード先"
    followDefaultDownloadPath = "既定のダウンロード先に従う"
    followDefaultPathAction = "既定の保存先"
    chooseImageFolder = "画像フォルダを選択"
    relaySiteAdded = "中継サイトを追加しました: %s"
    relaySiteDeleted = "削除しました: %s"
    verifyImagesAndExtract = "対象の投稿を確認して取得"
    statusAnalyzingShort = "解析中"
    statusCompletedShort = "完了"
    statusIncompleteShort = "未完了"
    artistProgress = "%1\$s · %2\$d/%3\$d 作品 · %4\$d 枚の画像 · %5\$d 個のアニメ"
    stopAnalyzing = "解析を停止"
    continueAnalyzing = "解析を続行"
    xProfileReading = "プロフィール読み込み中 · %1\$d 件の画像投稿 · %2\$d 枚の画像"
    xProfileComplete = "プラットフォームが最終ページを返しました · %1\$d 件の画像投稿 · %2\$d 枚の画像"
    xProfileIncomplete = "解析未完了 · %d 件の画像投稿を保持"
    selectedCount = "選択 %1\$d / %2\$d"
    zipByPost = "投稿ごとにZIP"
    zipByArtwork = "作品ごとにZIP"
    zipArchive = "ZIPを作成"
    ugoiraCoverHint = "アニメのカバー · ダウンロード後に再生"
    downloadAnimation = "アニメをダウンロード"
    saveThisImage = "この画像を保存"
    animationFormat = "アニメ: %s ▾"
    originalFramesZip = "元フレームZIP"
    clearSelection = "選択を解除"
    savingEllipsis = "保存中…"
    selectAnimationDesc = "%d 番目のアニメを選択"
    selectImageDesc = "%d 枚目の画像を選択"
    previewImageDesc = "画像をプレビュー"
    imageDesc = "%d 枚目の画像"
    ugoiraIndexLabel = "%1\$d · アニメ（カバー）"
    imageIndexLabel = "%1\$d · %2\$d×%3\$d"
    batchTitle = "一括ダウンロード"
    batchSmartMode = "自動判別"
    batchManualMode = "1件ずつ追加"
    batchInputManual = "リンク1件または共有テキストを入力"
    batchInputSmart = "複数のリンクまたは共有テキスト全体を貼り付け"
    batchAddLink = "リンクを追加"
    batchRecognizeAdd = "判別して追加"
    batchAllEpisodes = "コレクション / 複数動画の全エピソードをダウンロード"
    batchStart = "解析してキューに追加"
    batchStopAdding = "追加を停止（登録済みのタスクは継続）"
    batchRetryPending = "続行 / 未登録項目を再試行"
    batchProgressSummary = "%1\$d / %2\$d 件のリンクを登録 · %3\$d タスク"
    batchViewTasks = "ダウンロードタスクを表示"
    batchFilledCount = "入力済みリンク %d / 100"
    batchClearInput = "入力をクリア"
    batchAddedToQueue = "ダウンロードキューに追加しました"
    batchRemove = "削除"
    batchHistoryTitle = "一括処理履歴"
    batchClearHistory = "履歴を消去"
    batchWaiting = "待機中"
    batchSingleAnalyze = "単独で解析 / 確認"
    batchClearLinksTitle = "入力済みリンクを消去しますか？"
    batchClearLinksMessage = "対応する解析結果は消去されます。登録済みのダウンロードは継続します。"
    batchClearHistoryTitle = "一括処理履歴を消去しますか？"
    batchClearHistoryMessage = "入力済みリンクとダウンロードタスクは保持されます。進行中のダウンロードには影響しません。"
    clipboardEmpty = "クリップボードが空です"
    batchTextTooLong = "テキストが長すぎます。分割して追加してください"
    clipboardReadFailed = "クリップボードを読み取れませんでした"
    gifConvertLocal = "ローカル動画をGIFに変換"
    gifCannotReadVideo = "選択した動画を読み取れませんでした"
    gifShareChooser = "GIFを共有"
    gifNoApp = "ファイルを利用できないか、GIFを開けるアプリがありません"
    gifChooseVideo = "ローカル動画を選んでGIFに変換"
    gifBackgroundHint = "変換はバックグラウンドで実行されます。タスク画面で進捗の確認、中止、再試行ができます。"
    gifEmpty = "GIFはまだありません"
    gifDeleteTitle = "GIFを削除しますか？"
    gifDeleteMessage = "このGIFファイルとタスク記録を削除します。元のローカル動画は保持されます。"
    gifDeleteFailed = "削除に失敗しました。ファイルの権限を確認してください"
    gifCannotDeleteFile = "ファイルを削除できません"
    statusWaitingAuth = "ログイン待ち"
    imageOriginal = "画像: 元ファイル"
    imageZip = "画像: ZIPアーカイブ"
    coverOriginal = "%s: オリジナル"
    retryExport = "エクスポートを再試行"
    coverContentDesc = "カバー"
    errorPrefix = "エラー: %s"
    viewDetails = "詳細を表示"
    failureLogTitle = "ダウンロード失敗の詳細ログ"
    copyLog = "ログをコピー"
    mergingTracks = "音声と映像を結合中..."
    remainingTime = "残り %s"
    fileMissing = "ファイルが存在しないか移動されています"
    noPlayer = "このファイルを開けるプレイヤーやギャラリーがありません"
    shareVideo = "動画を共有"
    shareFailed = "共有に失敗しました: %s"
    deleteLocalFiles = "ダウンロード済みのローカル動画も削除"
    failedEpisodes = "%d 件失敗"
    preparing = "準備中"
    relayAddHint = "URLを入力するとサイト名を自動取得します。手動で変更もできます："
    relayUrlLabel = "サイトの完全なURL"
    relayRefetchName = "名前を再取得"
    relayClearUrl = "URLをクリア"
    relayNameLabel = "サイト名"
    relayNamePlaceholder = "例：予備の解析ツール"
    relayClearName = "名前をクリア"
    relayFillRequired = "サイト名とURLを両方入力してください"
    confirmAdd = "追加する"
    relayCompatTitle = "サードパーティページの注意事項と互換性"
    relayCompatS1Title = "1. 機能の性質"
    relayCompatS1Desc = "中継機能はサードパーティページの表示と汎用的な直リンク検出のみを提供します。本アプリは解析サイトを内蔵せず、いかなる中継サイトとも提携していません。第三者のサイトの可用性は保証されません。"
    relayCompatS2Title = "2. 互換性とページの挙動"
    relayCompatS2Desc = "ページの操作、ボット対策、解析経路はサイトごとに大きく異なります。認証、広告リダイレクト、一時的な停止、直リンク保護はウェブページ側の挙動です。可能な限り本体の直接解析をご利用ください。"
    relayCompatS3Title = "3. 安全とプライバシー"
    relayCompatS3Desc = "追加・閲覧するURLが法令に適合することを確認してください。サードパーティページでは個人のアカウント、パスワード、機密情報を入力しないでください。フィッシングなどのリスクにご注意ください。"
    relayCompatAccept = "理解しました、続行"
    relayConnecting = "中継サイトに接続中…"
    relayTimeout = "中継サイトがタイムアウトしました。「ウィンドウ確認」で認証を完了するか手動で解析してください"
    relayPageFailed = "中継ページの読み込みに失敗しました。「ウィンドウ確認」をお試しください"
    relayPasteFirst = "先に上の入力欄へ解析する動画リンクを貼り付けてください"
    relayExpandAll = "中継サイト %d 件をすべて表示"
    relayCollapseAll = "中継サイトを折りたたむ"
    relaySuccess = "中継解析に成功し、ダウンロードキューに追加しました！"
    relayFallbackTitle = "%s の動画"
    relayDirect = "直接解析"
    relayWindowDebug = "ウィンドウ確認"
    relayResolvingDefault = "バックグラウンドで動画の直リンクを取得しています..."
    relayBack = "戻る"
    relayFillLink = "リンクを入力"
    relayRefresh = "再読み込み"
    relayOpenExternal = "外部ブラウザで開く"
    relayExternalFailed = "外部ブラウザを開けませんでした"
    douyinVideo = "動画"
    douyinImages = "画像投稿"
    douyinCaptureNotice = "ログインまたはプラットフォーム認証を完了し、対象の%sを開いて取得をタップしてください。"
    douyinVerifyNotice = "プラットフォーム認証を完了してください。対象の動画が準備できると自動で取得を続けます。"
    douyinBrowserTitle = "抖音%sブラウザ"
    douyinCookieDisabled = "抖音のCookieが無効です。ブラウザ補助にはウェブのログイン状態が必要です。"
    enableCookieContinue = "Cookieを有効にして続行"
    douyinNoId = "対象投稿のIDを確認できません。戻って共有リンクを再解析してください。"
    douyinNotTarget = "現在のページは対象の投稿ではありません。対象の%sページに戻って取得してください。"
    douyinPageLeft = "ページが対象投稿から離れました。戻って再試行してください"
    douyinExtractTimeout = "取得がタイムアウトしました。対象の%sが読み込まれたか確認してください"
    douyinStillNoTarget = "ページに対象の%sがまだありません。認証して対象を再生してから再試行してください"
    douyinIdMismatch = "対象投稿のIDが一致しません"
    douyinReading = "対象の%sを読み込み中…"
    douyinExtract = "この%sを取得"
    pixivLoginHint = "対象作品を閲覧できるPixivアカウントでログインし、下のセッション保存を押してから再解析してください。"
    sessionNotCaptured = "セッションを取得できていません。ウェブでログインを完了してから保存してください"
    desktopSite = " · デスクトップ版"
    mobileSite = " · モバイル版"
    switchToMobile = "モバイル版に切替"
    switchToDesktop = "デスクトップ版に切替"
    reloadLogin = "ログインページを再読み込み"
    iHaveLoggedIn = "ログイン済み、セッションを保存"
    loginNotice = "ページ内でQRコードまたは電話番号でログインしてください。アプリのリンク起動ではこのログインページから離れません"
    cookieDisabledTitle = "%s のCookieが無効です"
    cookieDisabledBody = "ブラウザ補助はウェブのログイン状態を使用します。このプラットフォームのCookieを有効にして続行するか、ウィンドウを閉じて匿名解析を続けてください。"
    desktopSwitchLabel = "デスクトップ版（モバイル版に切替）"
    mobileSwitchLabel = "モバイル版（デスクトップ版に切替）"
    downloadCaptured = "取得済みメディアをダウンロード（対象の内容か確認してください）"
    imageInputLabel = "作品リンク1件または共有テキストを貼り付け"
    savePathDesc = "保存先: %s"
    resetDefaultFolder = "既定のフォルダに戻す"
    analyzingEllipsis = "解析中…"
    analyzeImages = "画像投稿を解析"
    resolutionPending = "解像度未確定"
    formatPending = "形式未確定"
    sizePending = "サイズ未確定"
    currentAccountAccess = "現在のアカウント: %1\$s · アクセス: %2\$s"
    unverified = "未確認"
    browserMoreQuality = "ブラウザでより多くの画質を読み込む"
    gifBadge = "GIFアニメ"
    imagePreviewUnavailable = "プレビューを表示できません。閉じて再試行するか元画像を保存してください"
    previousImage = "前へ"
    nextImage = "次へ"
    zoomHint = "ピンチで拡大 · ダブルタップでリセット/拡大"
    webProxyChanged = "ブラウザのネットワークが変更されました。古いページを閉じて再試行してください"
    webProxyUnsupported = "システム WebView は現在カスタムプロキシに対応していません"
    notifQueued = "タスクをキューに追加しました"
    notifPreparing = "ダウンロードを準備中..."
    notifRemaining = "· 残り %s"
    notifDownloadError = "ダウンロード中にエラーが発生しました"
    notifTaskError = "タスクの実行に失敗しました"
    notifAnalyzingTitle = "メディアを解析中"
    notifAnalyzingText = "アプリに戻って解析結果を確認できます"
    notifChannelName = "ダウンロード"
    notifChannelDesc = "現在の動画/音声のリアルタイムなダウンロードと結合の進捗"
    notifProcessing = "処理中"
    notifDownloading = "ダウンロード中"
    notifCompletedTitle = "ダウンロード完了"
    audioLabelMp3 = "MP3（互換性重視）"
    audioLabelM4a = "M4A（高音質AAC）"
    audioLabelFlac = "FLAC（可逆圧縮）"
    audioLabelOpus = "OPUS（高圧縮）"
    vmClipboardNoLink = "クリップボードに有効なリンクがありません"
    vmClipboardUnreadable = "クリップボードを読み取れませんでした"
    vmEnterLink = "作品リンクを入力または貼り付けてください"
    vmArtistProgress = "%1\$d/%2\$d 作品を読み取りました…"
    vmImagesFound = "%d 枚の画像が見つかりました…"
    vmNoMediaResources = "この作品には確認できる画像リソースがありません"
    vmAnalysisCancelled = "解析セッションが変更されました"
    vmAnalysisFailedDetail = "解析に失敗しました。ダイアログをタップして詳細を確認してください"
    vmSaveInProgress = "画像を保存中です。しばらくしてから再試行してください"
    vmImageTaskLimit = "1回につき最大500件の画像タスクです。選択を減らすか投稿単位でまとめてください"
    vmImageArchiveTitle = "元画像アーカイブ"
    vmImageSaveStartFailed = "画像の保存を開始できませんでした。再試行してください"
    vmAnalysisStopped = "解析を停止しました。取得済みの画像は保存または解析の続行ができます"
    vmPastedAnalyzing = "クリップボードを読み取り、解析中..."
    vmPastedVideoLink = "クリップボードから動画リンクを貼り付けました"
    vmEnterVideoUrl = "動画のURLを入力または貼り付けてください"
    vmRelayAddressHint = "これは中継サイトのアドレスです。実際の動画リンクを貼り付けてください"
    vmRelayAddressDetail = "入力されたのは中継解析サイトのアドレスです (%s)。\n\nダウンロードしたい実際の動画リンク（Bilibili、YouTube、TikTok、X などの共有リンク）を入力欄に貼り付けるか、下の中継サイトカードで動画リンクを入力してください。"
    vmCollectionDetected = "コレクションを検出しました（全%d本）。ダウンロード設定を選択してください"
    vmCoverOriginal = "オリジナルカバー"
    vmDefaultQuality = "既定の画質"
    vmCoverSuffix = "%s (カバー)"
    vmAddedQueueWithCover = "キューに追加しました（カバー付き）: %s"
    vmAddedQueue = "キューに追加しました: %s"
    vmRelayVideoTitle = "中継ダウンロード動画"
    vmRelayAuthor = "サードパーティ中継"
    vmRelayDirectLink = "中継直リンク"
    vmRelayCaptured = "予備サイトから取得してキューに追加しました: %s"
    vmNoCoverUrl = "カバー画像のURLが見つかりません"
    vmCoverStarted = "カバーのダウンロードを開始しました: %s"
    vmSelectVideosFirst = "ダウンロードする動画を選択してください"
    vmBestQuality = "最高画質"
    vmCollectionTitle = "コレクション動画"
    vmCollectionQueued = "コレクションをキューに追加しました（全 %d 話）"
    vmDownloadStarted = "ダウンロードを開始しました: %s"
    vmTaskCancelled = "タスクをキャンセルしました"
    vmDeletedWithFile = "記録とローカルファイルを削除しました"
    vmRemovedFromList = "リストから削除しました"
    vmCollectionCancelled = "コレクションのダウンロードをキャンセルしました"
    vmCollectionDeletedWithFile = "コレクションの記録とローカルファイルを削除しました"
    vmCollectionRemoved = "コレクションをリストから削除しました"
    vmCollectionRetried = "コレクションの未完了項目を再開しました（%d 話）"
    vmClearedCount = "%d 件のタスク記録を消去しました"
    vmNothingToClear = "消去する履歴はありません"
    vmStoppedTasks = "%d 件のタスクを停止し、残存ファイルを整理しました"
    vmEngineUpdated = "コアエンジンを更新しました: %s"
    vmEngineUpdateFailed = "エンジンの更新に失敗しました:\n%s"
    androidUpdateTitle = "Android 版 %s が利用可能です"
    androidUpdateBody = "GitHub のリリースページで変更内容を確認し、APK をダウンロードできます。"
    androidUpdateRelease = "リリースを表示"
    browserUnavailable = "GitHub のリリースページを開けるブラウザーがありません。"
    platformLabelWeibo = "Weibo"
    ytDlpPlatformsTitle = "yt-dlp 対応サイト"
    ytDlpPlatformsDescription = "ホーム画面に動画のリンクを貼り付けると、利用可能な形式を確認できます。一部のサイトではログインや追加のエンジン部品が必要です。"
    ytDlpPlatformsSearch = "サイト名や抽出器を検索"
    ytDlpPlatformsNoResults = "該当するサイトがありません"
    ytDlpPlatformsUnavailable = "yt-dlp の一覧を読み込めませんでした"
    platformLabelXigua = "西瓜動画"
    platformLabelYouku = "Youku"
    platformLabelIqiyi = "iQIYI"
    platformLabelHuya = "虎牙"
    analyzeClipboard = "クリップボードを解析"
}
fun resolveAppStrings(languagePreference: String): AppStrings {
    return when (languagePreference) {
        "zh-CN" -> ZhHansStrings
        "zh-TW" -> ZhHantStrings
        "en" -> EnStrings
        "ja" -> JaStrings
        else -> {
            // "system"
            val defaultLocale = Locale.getDefault()
            val lang = defaultLocale.language.lowercase()
            val country = defaultLocale.country.uppercase()
            val script = defaultLocale.script.lowercase()

            when {
                lang == "zh" && (country == "TW" || country == "HK" || country == "MO" || script.contains("hant")) -> ZhHantStrings
                lang == "zh" -> ZhHansStrings
                lang == "ja" -> JaStrings
                lang == "en" -> EnStrings
                else -> ZhHansStrings
            }
        }
    }
}

val LocalAppStrings = staticCompositionLocalOf { ZhHansStrings }
