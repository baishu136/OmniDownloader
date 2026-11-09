import SwiftUI
import UIKit
import WebKit
import ImageIO
import UniformTypeIdentifiers

struct OmniPressStyle:ButtonStyle{
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    func makeBody(configuration:Configuration)->some View{configuration.label.opacity(configuration.isPressed ? 0.85:1).scaleEffect(configuration.isPressed && !reduceMotion ? 0.97:1).animation(reduceMotion ? nil:.easeOut(duration:0.15),value:configuration.isPressed)}
}
struct OmniAction:View{
    let title:String;let icon:String;var prominent:Bool=false;var disabled:Bool=false;var action:()->Void
    var body:some View{
        Button(action:action){Label(title,systemImage:icon).font(.system(size:16,weight:.semibold)).frame(maxWidth:.infinity,minHeight:52)
            .foregroundColor(prominent ? OmniPalette.onPrimaryContainer:OmniPalette.onSurface)
            .background(prominent ? OmniPalette.primaryContainer:OmniPalette.surfaceVariant.opacity(0.45),in:Capsule())}
            .buttonStyle(OmniPressStyle()).disabled(disabled).opacity(disabled ? 0.5:1)
    }
}
struct OmniPlatformChips:View{
    var sites:[String]=OmniPlatform.all
    @State private var expanded=false
    @State private var width:CGFloat=300
    @Environment(\.sizeCategory) private var sizeCategory
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    private let colors:[Color]=[.pink,.gray,.orange,.red,.red,.cyan,.gray,.pink,.blue,.red,.cyan,.yellow]
    private func rows(_ count:Int,toggle:Bool)->[[Int]]{
        let font=UIFont.preferredFont(forTextStyle:.caption1)
        var result:[[Int]]=[[]],used:CGFloat=0
        let items=Array(0..<count)+(toggle ? [-1]:[])
        for index in items{
            let natural=index == -1 ? CGFloat(36):(sites[index] as NSString).size(withAttributes:[.font:font]).width+32
            let next=min(width,natural)
            if used>0 && used+7+next>width{result.append([]);used=0}
            result[result.count-1].append(index);used += (used>0 ? 7:0)+next
        };return result
    }
    private var displayed:[[Int]]{
        if expanded || rows(sites.count,toggle:false).count<=2{return rows(sites.count,toggle:expanded)}
        let count=(0..<sites.count).reversed().first{rows($0,toggle:true).count<=2} ?? 0
        return rows(count,toggle:true)
    }
    var body:some View{
        GeometryReader{geometry in
            VStack(alignment:.leading,spacing:7){ForEach(Array(displayed.enumerated()),id:\.offset){_,row in
                HStack(spacing:7){ForEach(row,id:\.self){index in
                    if index == -1{Button{withAnimation(reduceMotion ? nil:.easeInOut(duration:0.2)){expanded.toggle()}}label:{Image(systemName:expanded ? "chevron.up":"chevron.down").font(.caption.bold()).frame(width:36,height:30).background(OmniPalette.surfaceVariant.opacity(0.4),in:Capsule())}.buttonStyle(OmniPressStyle()).accessibilityLabel(expanded ? "收起平台":"展开平台")}
                    else{HStack(spacing:6){Circle().fill(colors[(OmniPlatform.all.firstIndex(of:sites[index]) ?? index)%colors.count]).frame(width:7,height:7);Text(sites[index]).font(.caption).lineLimit(1)}.padding(.horizontal,10).padding(.vertical,7).foregroundColor(OmniPalette.secondary).background(OmniPalette.surfaceVariant.opacity(0.35),in:Capsule())}
                }}
            }}.onAppear{width=max(1,geometry.size.width)}.onChange(of:geometry.size.width){width=max(1,$0)}
        }.frame(height:CGFloat(displayed.count)*(UIFont.preferredFont(forTextStyle:.caption1).lineHeight+14)+CGFloat(max(0,displayed.count-1))*7)
    }
}
struct OmniPageHeader:View{
    let title:String
    var back:(()->Void)?=nil
    var body:some View{HStack(spacing:14){if let back=back{Button(action:back){Image(systemName:"arrow.left").font(.title3).frame(width:44,height:44)}.buttonStyle(OmniPressStyle()).accessibilityLabel("返回")};Text(title).font(.system(size:25,weight:.semibold));Spacer()}.foregroundColor(OmniPalette.onSurface).padding(.vertical,8)}
}
struct OmniRemoteImage:View{
    let url:String
    var site:String=""
    @State private var image:UIImage?
    var body:some View{
        ZStack{OmniPalette.surfaceVariant;if let image=image{Image(uiImage:image).resizable().scaledToFill()}else{Image(systemName:"photo").foregroundColor(OmniPalette.secondary)}}.clipped()
            .task(id:url){
                image=nil;guard let address=URL(string:url),NativeMediaParser.safeURL(url) else{return}
                let platform=site.isEmpty && (address.host ?? "").hasSuffix("pximg.net") ? "Pixiv":site
                guard let pair=try? await OmniNetwork.data(address,site:platform,timeout:8),!Task.isCancelled,let decoded=UIImage(data:pair.0) else{return}
                image=decoded
            }
    }
}
struct MediaShareSheet:UIViewControllerRepresentable{
    let items:[Any]
    func makeUIViewController(context:Context)->UIActivityViewController{UIActivityViewController(activityItems:items,applicationActivities:nil)}
    func updateUIViewController(_ controller:UIActivityViewController,context:Context){}
}

/// WebKit renders GIFs without flattening their animation. Content is sandboxed and script-free.
struct ImagePreviewPage:UIViewRepresentable{
    let resource:ImageResource;let site:String
    func makeUIView(context:Context)->WKWebView{
        let configuration=WKWebViewConfiguration();configuration.websiteDataStore = .nonPersistent();configuration.defaultWebpagePreferences.allowsContentJavaScript=false
        let web=WKWebView(frame:.zero,configuration:configuration);web.isOpaque=false;web.backgroundColor = .black;web.scrollView.backgroundColor = .black
        return web
    }
    func updateUIView(_ web:WKWebView,context:Context){
        guard context.coordinator.source != resource.url else{return};context.coordinator.source=resource.url
        context.coordinator.task?.cancel()
        if let url=URL(string:resource.url){
            context.coordinator.task=Task{@MainActor in
                do{
                    let(data,_)=try await OmniNetwork.data(url,site:site,timeout:20)
                    try Task.checkCancellation()
                    guard let source=CGImageSourceCreateWithData(data as CFData,nil),let type=CGImageSourceGetType(source),let mime=UTType(type as String)?.preferredMIMEType else{throw OmniPlatform.error("NO_MEDIA","图片无法解码")}
                    web.load(data,mimeType:mime,characterEncodingName:"UTF-8",baseURL:URL(string:"https://localhost.invalid/")!)
                }catch is CancellationError{}catch{web.loadHTMLString("<html><body style='color:white;background:black;font:17px sans-serif'>图片暂时无法加载，请重试或下载原图。</body></html>",baseURL:nil)}
            }
        }
    }
    func makeCoordinator()->Coordinator{Coordinator()}
    final class Coordinator{var source="";var task:Task<Void,Never>?}
    static func dismantleUIView(_ web:WKWebView,coordinator:Coordinator){coordinator.task?.cancel();web.stopLoading()}
}
struct ImageGallery:View{
    let resources:[ImageResource];let site:String;let start:Int
    @Environment(\.presentationMode) private var presentation
    @State private var index=0
    var body:some View{
        NavigationView{TabView(selection:$index){ForEach(Array(resources.enumerated()),id:\.element.id){i,resource in ImagePreviewPage(resource:resource,site:site).tag(i)}}.tabViewStyle(.page(indexDisplayMode:.never)).background(Color.black).navigationTitle("\(index+1) / \(resources.count)").navigationBarTitleDisplayMode(.inline).toolbar{ToolbarItem(placement:.cancellationAction){Button("关闭"){presentation.wrappedValue.dismiss()}}}.onAppear{index=start}}
        .navigationViewStyle(.stack)
    }
}
