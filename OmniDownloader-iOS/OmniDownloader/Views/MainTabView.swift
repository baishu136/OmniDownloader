import SwiftUI
import UIKit

@MainActor struct MainTabView:View{
    @EnvironmentObject private var manager:DownloadManager
    @AppStorage("omni.language") private var language="system"
    @State private var selection=0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    private let tabs=[("首页","house"),("图文","photo.on.rectangle"),("任务","arrow.down.to.line"),("设置","gearshape")]
    private var active:Int{manager.tasks.filter{[.pending,.downloading,.processing].contains($0.status)}.count}
    var body:some View{
        ZStack{
            HomeView().opacity(selection==0 ? 1:0).allowsHitTesting(selection==0).accessibilityHidden(selection != 0).zIndex(selection==0 ? 1:0)
            ImagesView().opacity(selection==1 ? 1:0).allowsHitTesting(selection==1).accessibilityHidden(selection != 1).zIndex(selection==1 ? 1:0)
            TasksView().opacity(selection==2 ? 1:0).allowsHitTesting(selection==2).accessibilityHidden(selection != 2).zIndex(selection==2 ? 1:0)
            SettingsView().opacity(selection==3 ? 1:0).allowsHitTesting(selection==3).accessibilityHidden(selection != 3).zIndex(selection==3 ? 1:0)
        }
        .animation(reduceMotion ? nil:.easeInOut(duration:0.16),value:selection)
        .safeAreaInset(edge:.bottom,spacing:0){
            HStack(spacing:0){ForEach(0..<tabs.count,id:\.self){index in
                Button{selection=index}label:{
                    VStack(spacing:5){
                        OmniIcon(name:tabs[index].1,size:24).frame(width:64,height:32).background(selection==index ? OmniPalette.primaryContainer:Color.clear,in:Capsule())
                            .overlay(alignment:.topTrailing){if index==2 && active>0{Text(active>99 ? "99+":"\(active)").font(.caption2.bold()).foregroundColor(OmniPalette.onPrimary).padding(.horizontal,4).background(OmniPalette.primary,in:Capsule()).offset(x:3,y:-3)}}
                        Text(OmniLocalization.text(tabs[index].0,language:language)).font(.caption.weight(selection==index ? .semibold:.regular))
                    }.foregroundColor(selection==index ? OmniPalette.onSurface:OmniPalette.secondary).frame(maxWidth:.infinity,minHeight:58).contentShape(Rectangle())
                }.buttonStyle(OmniPressStyle()).accessibilityIdentifier("omni.nav.\(index)").accessibilityLabel(OmniLocalization.text(tabs[index].0,language:language)).accessibilityAddTraits(selection==index ? .isSelected:[])
            }}.padding(.horizontal,8).padding(.top,8).padding(.bottom,4).background(OmniPalette.background)
        }
        .onChange(of:selection){_ in UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder),to:nil,from:nil,for:nil)}
        .onReceive(NotificationCenter.default.publisher(for:.omniAnalyzeURL)){_ in selection=0}
        .onReceive(NotificationCenter.default.publisher(for:.omniAnalyzeImages)){_ in selection=1}
        .onReceive(NotificationCenter.default.publisher(for:.omniShowImages)){_ in selection=1}
        .onOpenURL{url in
            let value:URL?
            if url.scheme=="omnidownloader",let raw=URLComponents(url:url,resolvingAgainstBaseURL:false)?.queryItems?.first(where:{$0.name=="url"})?.value{value=URL(string:raw)}else{value=url}
            guard let value=value,["https","http"].contains(value.scheme ?? "") else{return}
            selection=0;NotificationCenter.default.post(name:.omniAnalyzeURL,object:value.absoluteString)
        }
    }
}
