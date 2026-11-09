import SwiftUI

@MainActor struct TasksView:View{
    @EnvironmentObject private var manager:DownloadManager
    @AppStorage("omni.language") private var language="system"
    @State private var selected=0
    @State private var clearing=false
    private func L(_ text:String)->String{OmniLocalization.text(text,language:language)}
    var body:some View{
        VStack(spacing:14){
            HStack{OmniPageHeader(title:L("下载任务"));Menu{Button(L("全部暂停")){manager.pauseAll()};Button(L("继续未完成任务")){manager.resumeAll()};Button(L("清空已完成记录"),role:.destructive){clearing=true}}label:{Image(systemName:"ellipsis").font(.title3).frame(width:44,height:44)}.accessibilityLabel(L("任务操作"))}.padding(.horizontal,20)
            OmniTaskTabs(selection:$selected,titles:[L("进行中"),L("已完成")]).padding(.horizontal,20)
            let items=manager.getDisplayItems(completed:selected==1)
            if items.isEmpty{Spacer();Image(systemName:"tray").font(.system(size:42,weight:.light)).foregroundColor(OmniPalette.secondary);Text(L(selected==1 ? "暂无已完成任务":"暂无下载任务")).foregroundColor(OmniPalette.secondary);Spacer()}
            else{ScrollView{LazyVStack(spacing:12){ForEach(items){item in switch item{case .single(let task):TaskCard(task:task);case .collection(let id,let title,let tasks):CollectionTaskCard(collectionId:id,collectionTitle:title,tasks:tasks)}}}.padding(.horizontal,20).padding(.bottom,20)}}
        }.background(OmniPalette.background).foregroundColor(OmniPalette.onSurface).tint(OmniPalette.primary)
        .confirmationDialog(L("清空已完成记录会保留已下载的文件"),isPresented:$clearing,titleVisibility:.visible){Button(L("清空记录"),role:.destructive){manager.clearCompletedTasks()};Button(L("取消"),role:.cancel){}}
    }
}
