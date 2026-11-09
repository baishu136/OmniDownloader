import SwiftUI

extension Notification.Name{
    static let omniAnalyzeImages=Notification.Name("omniAnalyzeImages")
    static let omniShowImages=Notification.Name("omniShowImages")
}

@MainActor struct ImagesView:View{
    @EnvironmentObject private var manager:DownloadManager
    @Environment(\.scenePhase) private var phase
    @AppStorage("omni.language") private var language="system"
    @StateObject private var browser=PlatformBrowser()
    @State private var input=""
    @State private var analyzing=false
    @State private var stage=""
    @State private var error=""
    @State private var post:ImagePost?
    @State private var selected=Set<String>()
    @State private var operation:Task<Void,Never>?
    @State private var generation=UUID()
    @State private var preview:Preview?
    private struct Preview:Identifiable{let id:Int}
    private func L(_ text:String)->String{OmniLocalization.text(text,language:language)}
    var body:some View{
        ZStack{
            OmniPalette.background.ignoresSafeArea()
            if browser.web != nil && !browser.visible{PlatformWebContent(browser:browser).frame(maxWidth:390,maxHeight:780).opacity(0).allowsHitTesting(false).accessibilityHidden(true)}
            VStack(spacing:0){
                ScrollView{
                    VStack(alignment:.leading,spacing:18){
                        OmniPageHeader(title:L("图文下载"))
                        OmniPlatformChips(sites:["抖音","快手","哔哩哔哩","Pixiv","小红书","X (Twitter)","Pinterest"])
                        VStack(spacing:12){
                            HStack{Image(systemName:"link").foregroundColor(OmniPalette.primary);TextField(L("粘贴图文链接或分享文本…"),text:$input).textInputAutocapitalization(.never).disableAutocorrection(true).keyboardType(.URL).onSubmit{start(input)}.accessibilityIdentifier("omni.images.url");Button{input=UIPasteboard.general.string ?? input}label:{Image(systemName:"doc.on.clipboard").frame(width:36,height:40)}.accessibilityLabel(L("粘贴剪贴板"))}.padding(.horizontal,14).padding(.vertical,8).background(OmniPalette.surfaceVariant.opacity(0.35),in:RoundedRectangle(cornerRadius:20))
                            HStack{OmniAction(title:L("粘贴剪贴板"),icon:"doc.on.clipboard",disabled:analyzing){input=UIPasteboard.general.string ?? input};OmniAction(title:L(analyzing ? "取消解析":"立即解析"),icon:analyzing ? "xmark":"bolt.fill",prominent:true,disabled:!analyzing && input.isEmpty){if analyzing{cancel()}else{start(input)}}}
                        }.padding(15).omniCard(radius:25)
                        if analyzing{HStack{ProgressView();Text(stage).font(.subheadline).foregroundColor(OmniPalette.secondary)}}
                        if !error.isEmpty{VStack(alignment:.leading,spacing:12){Text(error).font(.subheadline).foregroundColor(OmniPalette.error);if error.contains("AUTH_REQUIRED") || error.contains("CHALLENGE_REQUIRED") || error.contains("BROWSER_ASSIST_REQUIRED"){Button(L("打开内置浏览器验证")){validate()}}}.padding(16).omniCard()}
                        if let post=post{
                            VStack(alignment:.leading,spacing:6){Text(post.title).font(.title3.bold()).lineLimit(3);Text(post.platform+" · "+post.author+" · \(post.resources.count) "+L("张图片")).font(.caption).foregroundColor(OmniPalette.secondary)}
                            if post.frameArchive != nil{
                                HStack{Button("GIF"){animation(.gif)};Button("MP4"){animation(.videoOnly)};Text("Ugoira · \(post.frameNames?.count ?? 0) "+L("帧")).font(.caption).foregroundColor(OmniPalette.secondary)}.buttonStyle(.bordered)
                            }
                            HStack{Text(L("已选择")+" \(selected.count)/\(post.resources.count)").font(.subheadline);Spacer();Button(L("全选")){selected=Set(post.resources.map(\.id))};Button(L("反选")){selected=Set(post.resources.map(\.id)).subtracting(selected)}}.font(.subheadline)
                            LazyVGrid(columns:[GridItem(.adaptive(minimum:145),spacing:12)],spacing:12){
                                ForEach(Array(post.resources.enumerated()),id:\.element.id){index,image in
                                    VStack(alignment:.leading,spacing:6){
                                        ZStack(alignment:.topTrailing){Button{preview=Preview(id:index)}label:{OmniRemoteImage(url:image.url).frame(height:170).frame(maxWidth:.infinity).clipShape(RoundedRectangle(cornerRadius:16))}.buttonStyle(OmniPressStyle()).accessibilityLabel(L("查看图片")+" \(index+1)")
                                            Button{if !selected.insert(image.id).inserted{selected.remove(image.id)}}label:{Image(systemName:selected.contains(image.id) ? "checkmark.circle.fill":"circle").font(.title2).foregroundColor(selected.contains(image.id) ? OmniPalette.primary:.white).padding(10).background(Color.black.opacity(0.2),in:Circle())}.buttonStyle(.plain).accessibilityLabel(L("选择图片")+" \(index+1)").accessibilityAddTraits(selected.contains(image.id) ? .isSelected:[])
                                        }
                                        HStack{Text(image.original ? L("原图"):L("平台图片"));if image.animated{Text("GIF").foregroundColor(.purple)};Spacer();Text(image.label)}.font(.caption2).foregroundColor(OmniPalette.secondary)
                                    }
                                }
                            }
                        }
                    }.padding(.horizontal,20).padding(.bottom,24)
                }
                if let post=post{
                    VStack(spacing:0){Divider();HStack(spacing:10){OmniAction(title:L("下载选中图片"),icon:"arrow.down.to.line",disabled:selected.isEmpty){download(zip:false)};OmniAction(title:post.frameArchive == nil ? L("导出 ZIP"):L("帧 ZIP"),icon:"archivebox",prominent:true,disabled:selected.isEmpty && post.frameArchive==nil){download(zip:true)}}.padding(14)}.background(OmniPalette.background)
                }
            }
        }
        .sheet(isPresented:$browser.visible,onDismiss:{operation?.cancel();analyzing=false;browser.close()}){PlatformBrowserSheet(browser:browser,onDone:{browser.visible=false})}
        .sheet(item:$preview){value in if let post=post{ImageGallery(resources:post.resources,site:post.platform,start:value.id)}}
        .onReceive(NotificationCenter.default.publisher(for:.omniShowImages)){if let post=$0.object as? ImagePost{accept(post)}}
        .onReceive(NotificationCenter.default.publisher(for:.omniAnalyzeImages)){if let url=$0.object as? String{input=url;start(url)}}
        .onChange(of:phase){if $0 == .background{cancel()}}
    }
    private func cancel(){generation=UUID();operation?.cancel();operation=nil;analyzing=false;browser.close()}
    private func start(_ raw:String){
        cancel();error="";let epoch=generation,value=UrlSniffer.extractFirstUrl(from:raw) ?? raw
        guard NativeMediaParser.safeURL(value) else{error=L("请输入有效链接");return};input=value;analyzing=true;stage=L("正在读取原图…")
        operation=Task{
            defer{if generation==epoch{analyzing=false;stage=""}}
            do{
                let url=await UrlSniffer.resolveRedirects(for:value);guard generation==epoch,!Task.isCancelled else{return};input=url
                let result:ImagePost
                do{result=try await ImagePostEngine.analyze(url)}catch{
                    if error.localizedDescription.contains("NO_MEDIA"){throw error}
                    stage=L("正在自动加载目标图文…")
                    guard case .images(let post)=try await browser.capture(url,images:true) else{throw OmniPlatform.error("NO_MEDIA","目标不是可导出的图文")};result=post
                }
                guard generation==epoch,!Task.isCancelled else{return};accept(result)
            }catch is CancellationError{}catch{if generation==epoch{self.error=error.localizedDescription}}
        }
    }
    private func accept(_ value:ImagePost){post=value;input=value.url;error="";selected=Set(value.resources.map(\.id));MediaSources.shared.remember(value);if browser.visible{browser.visible=false};_ = manager.acceptRefreshedImages(value)}
    private func validate(){
        operation?.cancel();browser.present();analyzing=true;stage=L("等待平台验证…")
        operation=Task{defer{analyzing=false};do{guard case .images(let value)=try await browser.capture(input,images:true,reuse:true,budget:120) else{return};accept(value)}catch is CancellationError{}catch{browser.notice=error.localizedDescription}}
    }
    private func download(zip:Bool){
        guard let post=post else{return}
        if zip{manager.addTask(DownloadTask(url:post.url,title:post.title,downloadType:.imageZip,mediaId:post.contentId,imagePlatform:post.platform,selectedImageIds:Array(selected)));return}
        let rows=post.resources.filter{selected.contains($0.id)},group=rows.count>1 ? UUID().uuidString:nil
        manager.addTasks(rows.enumerated().map{index,row in DownloadTask(url:post.url,title:post.title+"_\(index+1)",author:post.author,thumbnailUrl:row.url,downloadType:.image,collectionId:group,collectionTitle:group==nil ? nil:post.title,episodeIndex:index+1,episodeTotal:rows.count,mediaId:post.contentId,imagePlatform:post.platform,selectedImageIds:[row.id])})
    }
    private func animation(_ type:DownloadType){guard let post=post else{return};manager.addTask(DownloadTask(url:post.url,title:post.title,thumbnailUrl:post.resources.first?.url ?? "",downloadType:type,mediaId:post.contentId,expectedDurationMs:Int64(post.frameDelays?.reduce(0,+) ?? 0),imagePlatform:post.platform))}
}
