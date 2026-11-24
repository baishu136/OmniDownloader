import SwiftUI

struct PlatformCatalogView:View {
    @Environment(\.dismiss) private var dismiss
    @State private var query=""
    private let extractors:[String] = {
        guard let url=Bundle.main.url(forResource:"ytdlp-extractors",withExtension:"txt"),let text=try? String(contentsOf:url,encoding:.utf8) else{return []}
        return text.components(separatedBy:.newlines).filter{!$0.isEmpty}
    }()
    var body:some View {
        NavigationView {
            List {
                Section {
                    Text(OmniLocalization.text("这是随版本附带的 yt-dlp 解析器参考名录。iOS 使用原生与 WebKit 引擎，未内置 yt-dlp；名录中的站点不代表本机已验证支持。受登录、DRM 或签名限制的媒体可能无法下载。")).font(.caption).foregroundColor(.secondary)
                    Text("参考版本 2026.10.26 · \(extractors.count) 个解析器")
                }
                ForEach(extractors.filter{query.isEmpty || $0.localizedCaseInsensitiveContains(query)},id:\.self){Text($0).font(.subheadline)}
            }.searchable(text:$query,prompt:"搜索平台或解析器").navigationTitle(OmniLocalization.text("yt-dlp 平台名录"))
                .toolbar{ToolbarItem(placement:.confirmationAction){Button(OmniLocalization.text("完成")){dismiss()}}}
        }.navigationViewStyle(.stack)
    }
}
