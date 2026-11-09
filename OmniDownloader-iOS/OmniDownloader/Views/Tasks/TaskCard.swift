import SwiftUI
import QuickLook

@MainActor struct TaskCard:View{
    let task:DownloadTask
    @EnvironmentObject private var manager:DownloadManager
    @AppStorage("omni.language") private var language="system"
    @State private var deleting=false
    @State private var preview=false
    @State private var share=false
    private func L(_ text:String)->String{OmniLocalization.text(text,language:language)}
    private var active:Bool{[.pending,.analyzing,.downloading,.processing].contains(task.status)}
    private var status:String{
        switch task.status{case .pending:return "等待中";case .analyzing:return "解析中";case .downloading:return "下载中";case .processing:return "处理中";case .completed:return "已完成";case .failed:return "失败";case .paused:return "已暂停";case .cancelled:return "已取消";case .waitingForAuth:return "等待验证"}
    }
    private var type:String{switch task.downloadType{case .videoWithAudio,.auto:return "音画合流";case .videoOnly:return "纯画面";case .audioOnly:return task.audioFormat.rawValue.uppercased();case .gif:return "GIF";case .cover:return "封面";case .image:return "原图";case .imageZip:return "ZIP"}}
    var body:some View{
        VStack(alignment:.leading,spacing:12){
            HStack(alignment:.top,spacing:12){OmniRemoteImage(url:task.thumbnailUrl).frame(width:76,height:55).clipShape(RoundedRectangle(cornerRadius:10));VStack(alignment:.leading,spacing:5){Text(task.title).font(.subheadline.weight(.semibold)).lineLimit(2);HStack{Text(L(type)).font(.caption2.bold()).padding(.horizontal,7).padding(.vertical,3).background(OmniPalette.primaryContainer,in:RoundedRectangle(cornerRadius:6));Text(task.selectedResolution).font(.caption2).foregroundColor(OmniPalette.secondary).lineLimit(1)}};Spacer(minLength:0)}
            if active{ProgressView(value:Double(max(0,min(100,task.progress))),total:100).tint(OmniPalette.primary);HStack{Text(task.eta.isEmpty ? L(status):task.eta);Spacer();Text("\(Int(task.progress))%")}.font(.caption).foregroundColor(OmniPalette.secondary)}
            else{Text(L(status)).font(.caption).foregroundColor(task.status == .failed || task.status == .waitingForAuth ? OmniPalette.error:OmniPalette.secondary)}
            if let error=task.errorMessage,!error.isEmpty{Text(error).font(.caption).foregroundColor(task.status == .completed ? OmniPalette.secondary:OmniPalette.error).textSelection(.enabled)}
            HStack(spacing:14){
                if active{Button(L("暂停")){manager.pauseTask(id:task.id)};Button(L("取消")){manager.cancelTask(id:task.id)}}
                else if task.status != .completed{Button(L(task.status == .waitingForAuth ? "验证并重试":task.status == .paused ? "继续":"重试")){manager.retryTask(id:task.id)}}
                if task.status == .completed,task.localFilePath != nil{Button(L("打开")){preview=true};Button(L("分享")){share=true}}
                Spacer();Button{deleting=true}label:{Image(systemName:"trash").frame(width:36,height:36)}.accessibilityLabel(L("删除任务"))
            }.font(.subheadline.weight(.medium)).buttonStyle(OmniPressStyle())
        }.padding(15).omniCard(radius:23)
        .confirmationDialog(L("删除任务"),isPresented:$deleting,titleVisibility:.visible){Button(L("只删除记录"),role:.destructive){manager.deleteTask(id:task.id,deleteFile:false)};Button(L("同时删除文件"),role:.destructive){manager.deleteTask(id:task.id,deleteFile:true)};Button(L("取消"),role:.cancel){}}
        .sheet(isPresented:$preview){if let path=task.localFilePath{FilePreviewSheet(url:URL(fileURLWithPath:path))}}
        .sheet(isPresented:$share){if let path=task.localFilePath{MediaShareSheet(items:[URL(fileURLWithPath:path)])}}
    }
}

struct FilePreview:UIViewControllerRepresentable{
    let url:URL
    func makeCoordinator()->Coordinator{Coordinator(url)}
    func makeUIViewController(context:Context)->QLPreviewController{let view=QLPreviewController();view.dataSource=context.coordinator;return view}
    func updateUIViewController(_ view:QLPreviewController,context:Context){}
    static func dismantleUIViewController(_ controller:QLPreviewController,coordinator:Coordinator){coordinator.folder?.stopAccessingSecurityScopedResource()}
    final class Coordinator:NSObject,QLPreviewControllerDataSource{
        let url:URL;var folder:URL?
        init(_ url:URL){self.url=url;super.init();if let data=UserDefaults.standard.data(forKey:"omni.folderBookmark"){var stale=false;if let root=try? URL(resolvingBookmarkData:data,options:[],relativeTo:nil,bookmarkDataIsStale:&stale),root.startAccessingSecurityScopedResource(){folder=root}}}
        func numberOfPreviewItems(in controller:QLPreviewController)->Int{1}
        func previewController(_ controller:QLPreviewController,previewItemAt index:Int)->QLPreviewItem{url as NSURL}
    }
}
struct FilePreviewSheet:View{
    let url:URL
    @Environment(\.presentationMode) private var presentation
    var body:some View{FilePreview(url:url).overlay(alignment:.topTrailing){Button{presentation.wrappedValue.dismiss()}label:{Image(systemName:"xmark.circle.fill").font(.title).padding(12).background(OmniPalette.surface.opacity(0.9),in:Circle())}.padding(10).accessibilityLabel("关闭")}}
}
