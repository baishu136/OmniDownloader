import Foundation
import SwiftUI
import WebKit

enum CapturedTarget { case video(VideoMetadata);case images(ImagePost) }

@MainActor final class PlatformBrowser: NSObject, ObservableObject, WKNavigationDelegate {
    @Published private(set) var web: WKWebView?
    @Published var visible = false
    @Published var notice = ""
    private(set) var target = ""
    private(set) var site = ""
    private var scope = ""
    private var epoch = UUID()
    private var login = false
    func prepare(_ url: String, login: Bool = false) async throws {
        close();self.login=login;target=url;site=OmniPlatform.detect(url);scope=PlatformSession.shared.generation(site)
        guard PlatformSession.shared.enabled(site) else{throw OmniPlatform.error("COOKIE_DISABLED","本站 Cookie 已禁用，浏览器辅助需要先开启本站 Cookie")}
        guard let address=URL(string:url),NativeMediaParser.safeURL(url) else{throw OmniPlatform.error("NO_MEDIA","需要有效的内容网址")}
        let config=WKWebViewConfiguration();config.websiteDataStore = .nonPersistent()
        config.allowsInlineMediaPlayback=true;config.mediaTypesRequiringUserActionForPlayback = .all
        config.defaultWebpagePreferences.preferredContentMode = .desktop
        if #available(iOS 17.0,*){config.websiteDataStore.proxyConfigurations=try OmniNetwork.configuration().proxyConfigurations}
        else{_ = try OmniNetwork.configuration()}
        if !login {
            let id=OmniPlatform.identifier(url)
            guard !id.isEmpty,let path=Bundle.main.url(forResource:"target-capture",withExtension:"js"),let script=try? String(contentsOf:path,encoding:.utf8) else{throw OmniPlatform.error("BROWSER_ASSIST_REQUIRED","需要可定位的目标作品链接")}
            let options:[String:Any]=["id":id,"site":site,"quiet":true]
            let data=try JSONSerialization.data(withJSONObject:options)
            let prefix="window.__omniTarget="+String(decoding:data,as:UTF8.self)+";\n"
            config.userContentController.addUserScript(WKUserScript(source:prefix+script,injectionTime:.atDocumentStart,forMainFrameOnly:true))
        }
        let page=WKWebView(frame:.zero,configuration:config);page.navigationDelegate=self;page.customUserAgent=OmniNetwork.userAgent
        page.isOpaque=false;page.backgroundColor = .clear
        let generation=epoch
        for cookie in try PlatformSession.shared.cookies(site){
            await withCheckedContinuation{(continuation:CheckedContinuation<Void,Never>)in config.websiteDataStore.httpCookieStore.setCookie(cookie){continuation.resume()}}
            try Task.checkCancellation()
            guard generation==epoch,scope==PlatformSession.shared.generation(site) else{throw CancellationError()}
        }
        web=page;page.load(URLRequest(url:address))
    }
    func capture(_ url:String,images:Bool=false,reuse:Bool=false,budget:TimeInterval=15)async throws->CapturedTarget{
        if !reuse || web==nil || target != url{try await prepare(url)}
        guard let page=web else{throw OmniPlatform.error("BROWSER_ASSIST_REQUIRED","页面尚未准备好")}
        let generation=epoch,started=ProcessInfo.processInfo.systemUptime
        var best:CapturedTarget?,fingerprint="",changed=started
        defer{if !visible{page.stopLoading();if best != nil{close()}}}
        while ProcessInfo.processInfo.systemUptime-started<budget{
            try Task.checkCancellation()
            guard generation==epoch,scope==PlatformSession.shared.generation(site) else{throw CancellationError()}
            if let result=try? await page.evaluateJavaScript("JSON.stringify(window.__omniReadTarget ? window.__omniReadTarget() : {})") as? String,
               result.utf8.count<=8_388_608,let data=result.data(using:.utf8),let value=try? JSONSerialization.jsonObject(with:data) as? [String:Any]{
                let status=value["status"] as? String ?? ""
                if status=="challenge" || status=="auth"{
                    notice="平台要求\(status=="challenge" ? "验证":"登录")，请在内置浏览器完成。"
                    if best==nil{throw OmniPlatform.error(status=="challenge" ? "CHALLENGE_REQUIRED":"AUTH_REQUIRED",notice)}
                    break
                }
                if let parsed=try? NativeMediaParser.parse(value,url:url){
                    if images,let post=parsed.1{best = .images(post)}
                    else if !images,let video=parsed.0{best = .video(video)}
                    else if !images,let post=parsed.1{best = .images(post)}
                    if best != nil{
                        let signature=String(result.hashValue)
                        if signature != fingerprint{fingerprint=signature;changed=ProcessInfo.processInfo.systemUptime}
                        let now=ProcessInfo.processInfo.systemUptime
                        var minimum:TimeInterval=2.5
                        if let current=best,case .video(let video)=current,let format=video.availableVideoFormats.first{
                            if min(format.width,format.height)<=720{minimum=7}
                            if (video.referenceWidth ?? 0)*(video.referenceHeight ?? 0)>format.width*format.height{minimum=budget}
                        }
                        if now-started>=minimum && now-changed>=1.5{break}
                    }
                }
            }
            try await Task.sleep(nanoseconds:400_000_000)
        }
        guard let captured=best else{throw OmniPlatform.error("BROWSER_ASSIST_REQUIRED",images ? "未取得目标原图，可打开内置浏览器完成验证后继续":"自动提取未取得目标视频，可打开内置浏览器完成验证后继续")}
        if visible{try? await saveSession()}
        switch captured{
        case .video(var video):
            video.sourceScope=scope
            if let best=video.availableVideoFormats.first,min(best.width,best.height)<=720{video.qualityWarning="已自动检查当前可访问源；更高画质尚未确认，本次结果不代表上传原片。"}
            MediaSources.shared.remember(video);return .video(video)
        case .images(var post):post.sourceScope=scope;MediaSources.shared.remember(post);return .images(post)
        }
    }
    func saveSession()async throws{
        guard let page=web,scope==PlatformSession.shared.generation(site) else{throw OmniPlatform.error("SOURCE_EXPIRED","会话已变化，请重新打开登录页")}
        let cookies=await withCheckedContinuation{(continuation:CheckedContinuation<[HTTPCookie],Never>)in page.configuration.websiteDataStore.httpCookieStore.getAllCookies{continuation.resume(returning:$0)}}
        try PlatformSession.shared.save(cookies,site:site)
        if site=="哔哩哔哩"{CookieStore.shared.bilibiliCookie=PlatformSession.shared.header(site,url:URL(string:"https://www.bilibili.com/")!)}
        scope=PlatformSession.shared.generation(site)
    }
    func present(){visible=true;web?.evaluateJavaScript("if(window.__omniTarget)window.__omniTarget.quiet=false",completionHandler:nil)}
    func close(){epoch=UUID();web?.stopLoading();web?.navigationDelegate=nil;web?.configuration.userContentController.removeAllUserScripts();web=nil}
    func webView(_ webView:WKWebView,decidePolicyFor navigationAction:WKNavigationAction,decisionHandler:@escaping(WKNavigationActionPolicy)->Void){
        guard let url=navigationAction.request.url,["http","https"].contains(url.scheme ?? ""),url.user==nil,url.password==nil else{decisionHandler(.cancel);return}
        if !login,OmniPlatform.detect(url.absoluteString)==site{
            let actual=OmniPlatform.identifier(url.absoluteString),expected=OmniPlatform.identifier(target)
            if !actual.isEmpty && actual != expected{notice="请保持在目标作品页面";decisionHandler(.cancel);return}
        }
        decisionHandler(.allow)
    }
}

struct PlatformWebContent:UIViewRepresentable{
    @ObservedObject var browser:PlatformBrowser
    func makeUIView(context:Context)->UIView{UIView()}
    func updateUIView(_ container:UIView,context:Context){
        guard let page=browser.web else{container.subviews.forEach{$0.removeFromSuperview()};return}
        if page.superview !== container{container.subviews.forEach{$0.removeFromSuperview()};page.removeFromSuperview();page.frame=container.bounds;page.autoresizingMask=[.flexibleWidth,.flexibleHeight];container.addSubview(page)}
    }
}

struct PlatformBrowserSheet:View{
    @ObservedObject var browser:PlatformBrowser
    var login:Bool=false
    var onDone:()->Void
    var onSaved:(()->Void)?=nil
    @State private var saving=false
    var body:some View{
        NavigationView{
            VStack(spacing:8){
                Text(browser.notice.isEmpty ? (login ? "在官方网站登录后点击保存登录状态。":"请完成平台验证；目标作品就绪后自动继续提取。") : browser.notice)
                    .font(.caption).foregroundColor(OmniPalette.secondary).padding(.horizontal)
                PlatformWebContent(browser:browser)
            }
            .background(OmniPalette.background)
            .navigationTitle(login ? "\(browser.site) 登录":"\(browser.site) 浏览器")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar{
                ToolbarItem(placement:.cancellationAction){Button("关闭"){onDone()}}
                ToolbarItem(placement:.confirmationAction){if login{Button(saving ? "保存中…":"保存登录状态"){
                    saving=true;Task{do{try await browser.saveSession();onSaved?();onDone()}catch{browser.notice=error.localizedDescription};saving=false}
                }.disabled(saving)}}
            }
        }.navigationViewStyle(.stack)
    }
}
