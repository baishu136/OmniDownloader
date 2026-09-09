import SwiftUI

/// 首页视图：负责链接输入、剪贴板自动嗅探识别、平台导航展示与唤起格式选择
struct HomeView: View {
    @EnvironmentObject var downloadManager: DownloadManager
    @State private var inputUrl: String = ""
    @State private var isParsing: Bool = false
    @State private var detectedClipboardUrl: String? = nil
    @State private var parsedMetadata: VideoMetadata? = nil
    @State private var showFormatSheet: Bool = false
    @State private var errorMessage: String? = nil
    @State private var showErrorAlert: Bool = false
    
    private let strings = AppStrings.current
    
    // 支持的平台胶囊列表
    private let supportedPlatforms = [
        ("哔哩哔哩", "play.rectangle.fill", Color.blue),
        ("抖音", "music.note", Color.black),
        ("快手", "video.fill", Color.orange),
        ("小红书", "bookmark.fill", Color.red)
    ]
    
    var body: some View {
        NavigationView {
            ZStack {
                Color(UIColor.systemGroupedBackground)
                    .ignoresSafeArea()
                
                ScrollView {
                    VStack(spacing: 20) {
                        // 顶部剪贴板快速粘贴条
                        if let detected = detectedClipboardUrl {
                            clipboardNotificationBanner(url: detected)
                                .transition(.move(edge: .top).combined(with: .opacity))
                        }
                        
                        // 主输入框卡片
                        inputSectionCard
                        
                        // 支持平台展示区
                        supportedPlatformsCard
                        
                        // 使用小贴士卡片
                        tipsCard
                        
                        Spacer(minLength: 40)
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 12)
                }
            }
            .navigationTitle(strings.appName)
            .navigationBarTitleDisplayMode(.large)
            .sheet(isPresented: $showFormatSheet) {
                if let meta = parsedMetadata {
                    FormatSelectorSheet(metadata: meta)
                }
            }
            .alert(isPresented: $showErrorAlert) {
                Alert(
                    title: Text(strings.errorTitle),
                    message: Text(errorMessage ?? strings.unknownError),
                    dismissButton: .default(Text(strings.confirm))
                )
            }
            .onAppear {
                checkClipboard()
            }
        }
        .navigationViewStyle(StackNavigationViewStyle())
    }
    
    // MARK: - 剪贴板嗅探提示浮条
    @ViewBuilder
    private func clipboardNotificationBanner(url: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: "doc.on.clipboard.fill")
                .foregroundColor(.accentColor)
                .font(.system(size: 18))
            
            VStack(alignment: .leading, spacing: 2) {
                Text(strings.clipboardDetectedTitle)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(.primary)
                Text(url)
                    .font(.system(size: 12))
                    .foregroundColor(.secondary)
                    .lineLimit(1)
            }
            
            Spacer()
            
            Button(action: {
                inputUrl = url
                detectedClipboardUrl = nil
                startExtract(url: url)
            }) {
                Text(strings.parseAction)
                    .font(.system(size: 13, weight: .bold))
                    .foregroundColor(.white)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(Color.accentColor)
                    .cornerRadius(14)
            }
            
            Button(action: {
                withAnimation {
                    detectedClipboardUrl = nil
                }
            }) {
                Image(systemName: "xmark.circle.fill")
                    .foregroundColor(.secondary)
            }
        }
        .padding(12)
        .background(Color(UIColor.secondarySystemGroupedBackground))
        .cornerRadius(12)
        .shadow(color: Color.black.opacity(0.04), radius: 5, x: 0, y: 2)
    }
    
    // MARK: - 输入区域卡片
    private var inputSectionCard: some View {
        VStack(spacing: 16) {
            HStack {
                Text(strings.inputPrompt)
                    .font(.system(size: 15, weight: .medium))
                    .foregroundColor(.secondary)
                Spacer()
                
                if !inputUrl.isEmpty {
                    Button(action: {
                        inputUrl = ""
                    }) {
                        HStack(spacing: 4) {
                            Image(systemName: "trash")
                            Text(strings.clearAction)
                        }
                        .font(.system(size: 13))
                        .foregroundColor(.secondary)
                    }
                }
            }
            
            // 多行输入区
            ZStack(alignment: .topLeading) {
                if inputUrl.isEmpty {
                    Text(strings.inputPlaceholder)
                        .font(.system(size: 15))
                        .foregroundColor(Color(UIColor.placeholderText))
                        .padding(.top, 8)
                        .padding(.leading, 4)
                }
                
                TextEditor(text: $inputUrl)
                    .frame(minHeight: 90)
                    .font(.system(size: 15))
                    .scrollContentBackground(.hidden)
                    .background(Color.clear)
            }
            .padding(8)
            .background(Color(UIColor.tertiarySystemGroupedBackground))
            .cornerRadius(10)
            
            // 操作按钮行：读取剪贴板 + 立即解析
            HStack(spacing: 12) {
                Button(action: pasteFromClipboard) {
                    HStack(spacing: 6) {
                        Image(systemName: "doc.on.clipboard")
                        Text(strings.pasteAction)
                    }
                    .font(.system(size: 15, weight: .medium))
                    .foregroundColor(.primary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(Color(UIColor.secondarySystemGroupedBackground))
                    .cornerRadius(12)
                    .overlay(
                        RoundedRectangle(cornerRadius: 12)
                            .stroke(Color.gray.opacity(0.2), lineWidth: 1)
                    )
                }
                
                Button(action: {
                    startExtract(url: inputUrl)
                }) {
                    HStack(spacing: 8) {
                        if isParsing {
                            ProgressView()
                                .progressViewStyle(CircularProgressViewStyle(tint: .white))
                            Text(strings.parsing)
                        } else {
                            Image(systemName: "arrow.down.circle.fill")
                            Text(strings.parseAction)
                        }
                    }
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(inputUrl.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || isParsing ? Color.gray.opacity(0.5) : Color.accentColor)
                    .cornerRadius(12)
                }
                .disabled(inputUrl.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || isParsing)
            }
        }
        .padding(16)
        .background(Color(UIColor.secondarySystemGroupedBackground))
        .cornerRadius(16)
        .shadow(color: Color.black.opacity(0.04), radius: 6, x: 0, y: 2)
    }
    
    // MARK: - 支持平台展示卡片
    private var supportedPlatformsCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Image(systemName: "checkmark.seal.fill")
                    .foregroundColor(.blue)
                Text(strings.supportedSites)
                    .font(.system(size: 16, weight: .semibold))
                Spacer()
            }
            
            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
                ForEach(supportedPlatforms, id: \.0) { item in
                    HStack(spacing: 10) {
                        Image(systemName: item.1)
                            .font(.system(size: 18))
                            .foregroundColor(item.2)
                        Text(item.0)
                            .font(.system(size: 14, weight: .medium))
                            .foregroundColor(.primary)
                        Spacer()
                    }
                    .padding(12)
                    .background(Color(UIColor.tertiarySystemGroupedBackground))
                    .cornerRadius(10)
                }
            }
        }
        .padding(16)
        .background(Color(UIColor.secondarySystemGroupedBackground))
        .cornerRadius(16)
        .shadow(color: Color.black.opacity(0.04), radius: 6, x: 0, y: 2)
    }
    
    // MARK: - 小贴士卡片
    private var tipsCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Image(systemName: "lightbulb.fill")
                    .foregroundColor(.orange)
                Text(strings.tipsTitle)
                    .font(.system(size: 15, weight: .semibold))
                Spacer()
            }
            
            Text(strings.tipsContent)
                .font(.system(size: 13))
                .foregroundColor(.secondary)
                .lineSpacing(4)
        }
        .padding(16)
        .background(Color(UIColor.secondarySystemGroupedBackground))
        .cornerRadius(16)
        .shadow(color: Color.black.opacity(0.04), radius: 6, x: 0, y: 2)
    }
    
    // MARK: - 剪贴板检查
    private func checkClipboard() {
        if let string = UIPasteboard.general.string, !string.isEmpty {
            if let validUrl = UrlSniffer.extractFirstUrl(from: string) {
                if validUrl != inputUrl {
                    withAnimation {
                        detectedClipboardUrl = validUrl
                    }
                }
            }
        }
    }
    
    // 粘贴操作
    private func pasteFromClipboard() {
        if let string = UIPasteboard.general.string, !string.isEmpty {
            if let extracted = UrlSniffer.extractFirstUrl(from: string) {
                inputUrl = extracted
            } else {
                inputUrl = string
            }
        }
    }
    
    // MARK: - 执行提取解析
    private func startExtract(url: String) {
        let cleanText = url.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let extractedUrl = UrlSniffer.extractFirstUrl(from: cleanText) ?? (cleanText.hasPrefix("http") ? cleanText : nil) else {
            errorMessage = "未在输入内容中检测到有效的链接地址"
            showErrorAlert = true
            return
        }
        
        isParsing = true
        errorMessage = nil
        
        Task {
            do {
                let resolved = await UrlSniffer.resolveRedirects(for: extractedUrl)
                let site = UrlSniffer.detectSite(from: resolved)
                
                let metadata: VideoMetadata
                if site == "哔哩哔哩" {
                    metadata = try await BilibiliExtractor.shared.extract(rawUrl: resolved)
                } else if DomesticExtractor.shared.isSupported(resolved) {
                    metadata = try await DomesticExtractor.shared.extract(rawUrl: resolved)
                } else {
                    // 通用降级方案
                    metadata = VideoMetadata(
                        url: resolved,
                        title: "视频下载",
                        siteName: site,
                        availableVideoFormats: [
                            FormatOption(formatId: "direct", resolutionLabel: "直接提取原视频", height: 1080)
                        ]
                    )
                }
                
                await MainActor.run {
                    self.parsedMetadata = metadata
                    self.isParsing = false
                    self.showFormatSheet = true
                }
            } catch {
                await MainActor.run {
                    self.isParsing = false
                    self.errorMessage = error.localizedDescription
                    self.showErrorAlert = true
                }
            }
        }
    }
}
