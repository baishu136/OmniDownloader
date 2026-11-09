import SwiftUI

@MainActor struct CollectionTaskCard:View{
    let collectionId:String
    let collectionTitle:String
    let tasks:[DownloadTask]
    @EnvironmentObject private var manager:DownloadManager
    @AppStorage("omni.language") private var language="system"
    @State private var expanded=false
    @State private var deleting=false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    private func L(_ text:String)->String{OmniLocalization.text(text,language:language)}
    var body:some View{
        VStack(alignment:.leading,spacing:14){
            Button{withAnimation(reduceMotion ? nil:.easeInOut(duration:0.2)){expanded.toggle()}}label:{HStack{Image(systemName:"square.stack").foregroundColor(OmniPalette.primary);Text(collectionTitle).font(.headline).lineLimit(2);Spacer();Image(systemName:expanded ? "chevron.up":"chevron.down")}.contentShape(Rectangle())}.buttonStyle(OmniPressStyle())
            let done=tasks.filter{$0.status == .completed}.count
            HStack{Text(L("合集")+" · \(done)/\(tasks.count)");Spacer();Text("\(Int(tasks.reduce(Float(0)){$0+$1.progress}/Float(max(1,tasks.count))))%")}.font(.caption).foregroundColor(OmniPalette.secondary)
            ProgressView(value:Double(tasks.reduce(Float(0)){$0+$1.progress}),total:Double(max(1,tasks.count)*100)).tint(OmniPalette.primary)
            if let current=tasks.first(where:{[.downloading,.processing].contains($0.status)}){Text(current.title).font(.caption).foregroundColor(OmniPalette.secondary).lineLimit(1)}
            HStack{Button(L("暂停")){manager.pauseCollection(collectionId:collectionId)};Button(L("继续")){manager.resumeCollection(collectionId:collectionId)};Spacer();Button{deleting=true}label:{Image(systemName:"trash").frame(width:36,height:36)}.accessibilityLabel(L("删除合集"))}.font(.subheadline).buttonStyle(OmniPressStyle())
            if expanded{LazyVStack(spacing:10){ForEach(tasks){TaskCard(task:$0)}}.transition(.opacity)}
        }.padding(16).omniCard(radius:23)
        .confirmationDialog(L("删除合集"),isPresented:$deleting,titleVisibility:.visible){Button(L("只删除记录"),role:.destructive){manager.deleteCollection(collectionId:collectionId,deleteFiles:false)};Button(L("同时删除文件"),role:.destructive){manager.deleteCollection(collectionId:collectionId,deleteFiles:true)};Button(L("取消"),role:.cancel){}}
    }
}
