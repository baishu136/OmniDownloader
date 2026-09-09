import SwiftUI
import WebKit

/// 哔哩哔哩网页登录弹窗：利用系统 WebKit 安全加载官方登录页面，拦截获取 SESSDATA 并持久化
struct BilibiliLoginSheet: View {
    @Environment(\.presentationMode) var presentationMode
    @State private var isLoading = true
    @State private var webView: WKWebView? = nil
    
    var onLoginSuccess: (() -> Void)? = nil
    
    var body: some View {
        NavigationView {
            ZStack {
                WebViewRepresentable(isLoading: $isLoading, webView: $webView) { cookies in
                    handleCapturedCookies(cookies)
                }
                
                if isLoading {
                    ProgressView("加载登录页面中...")
                        .padding()
                        .background(Color(UIColor.secondarySystemBackground))
                        .cornerRadius(10)
                        .shadow(radius: 5)
                }
            }
            .navigationTitle("B站账号安全登录")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("取消") {
                        presentationMode.wrappedValue.dismiss()
                    }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(action: {
                        webView?.reload()
                    }) {
                        Image(systemName: "arrow.clockwise")
                    }
                }
            }
        }
    }
    
    private func handleCapturedCookies(_ cookies: [HTTPCookie]) {
        var sessdata: String?
        var biliJct: String?
        var dedeUserId: String?
        var cookieStrings: [String] = []
        
        for cookie in cookies {
            if cookie.domain.contains("bilibili.com") {
                cookieStrings.append("\(cookie.name)=\(cookie.value)")
                if cookie.name == "SESSDATA" {
                    sessdata = cookie.value
                } else if cookie.name == "bili_jct" {
                    biliJct = cookie.value
                } else if cookie.name == "DedeUserID" {
                    dedeUserId = cookie.value
                }
            }
        }
        
        // 当捕获到有效的 SESSDATA 时即认为登录成功
        if let sess = sessdata, !sess.isEmpty {
            let fullCookie = cookieStrings.joined(separator: "; ")
            CookieStore.shared.saveBilibiliCookies(fullCookie: fullCookie, sessdata: sess)
            DispatchQueue.main.async {
                self.onLoginSuccess?()
                self.presentationMode.wrappedValue.dismiss()
            }
        }
    }
}

/// WKWebView 封装
struct WebViewRepresentable: UIViewRepresentable {
    @Binding var isLoading: Bool
    @Binding var webView: WKWebView?
    let onCookieChange: ([HTTPCookie]) -> Void
    
    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }
    
    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        let wv = WKWebView(frame: .zero, configuration: config)
        wv.navigationDelegate = context.coordinator
        
        // 监听 Cookie 变动
        wv.configuration.websiteDataStore.httpCookieStore.add(context.coordinator)
        
        DispatchQueue.main.async {
            self.webView = wv
        }
        
        if let url = URL(string: "https://passport.bilibili.com/login") {
            let req = URLRequest(url: url)
            wv.load(req)
        }
        
        return wv
    }
    
    func updateUIView(_ uiView: WKWebView, context: Context) {}
    
    class Coordinator: NSObject, WKNavigationDelegate, WKHTTPCookieStoreObserver {
        var parent: WebViewRepresentable
        
        init(_ parent: WebViewRepresentable) {
            self.parent = parent
        }
        
        func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
            DispatchQueue.main.async {
                self.parent.isLoading = true
            }
        }
        
        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            DispatchQueue.main.async {
                self.parent.isLoading = false
            }
            checkCookies(webView)
        }
        
        func cookiesDidChange(in cookieStore: WKHTTPCookieStore) {
            cookieStore.getAllCookies { cookies in
                self.parent.onCookieChange(cookies)
            }
        }
        
        private func checkCookies(_ webView: WKWebView) {
            webView.configuration.websiteDataStore.httpCookieStore.getAllCookies { cookies in
                self.parent.onCookieChange(cookies)
            }
        }
    }
}
