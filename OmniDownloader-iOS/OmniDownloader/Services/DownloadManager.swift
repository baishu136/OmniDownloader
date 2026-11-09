import Foundation
import Combine

extension Notification.Name { static let omniAnalyzeURL=Notification.Name("omniAnalyzeURL") }

@MainActor public final class DownloadManager:ObservableObject{
    public static let shared=DownloadManager()
    @Published public private(set) var tasks:[DownloadTask]=[]
    @Published var pendingRetry:String?
    private var operations:[String:Task<Void,Never>]=[:]
    private var generations:[String:UUID]=[:]
    private var progressTimes:[String:TimeInterval]=[:]
    private var foregroundPaused=Set<String>()
    private let persistence=DispatchQueue(label:"com.omni.downloader.persistence",qos:.utility)
    private var saveWork:DispatchWorkItem?
    private let storage:URL
    public init(){
        let root=FileManager.default.urls(for:.applicationSupportDirectory,in:.userDomainMask)[0]
        try? FileManager.default.createDirectory(at:root,withIntermediateDirectories:true)
        storage=root.appendingPathComponent("omni_tasks.json")
        let legacy=FileManager.default.urls(for:.documentDirectory,in:.userDomainMask)[0].appendingPathComponent("omni_tasks.json")
        let source=FileManager.default.fileExists(atPath:storage.path) ? storage:legacy
        if let data=try? Data(contentsOf:source),let saved=try? JSONDecoder().decode([DownloadTask].self,from:data){
            tasks=saved.map{task in var item=task;if [.pending,.analyzing,.downloading,.processing].contains(item.status){item.status = .paused;item.errorMessage="应用退出中断了任务，可继续或重新解析"};return item}
            if source==legacy{saveTasks(force:true)}
        }
    }
    public func saveTasks(force:Bool=false){
        saveWork?.cancel()
        let snapshot=tasks,file=storage
        let item=DispatchWorkItem{guard let data=try? JSONEncoder().encode(snapshot) else{return};try? data.write(to:file,options:[.atomic,.completeUntilFirstUserAuthentication])}
        saveWork=item
        if force{persistence.async(execute:item)}else{persistence.asyncAfter(deadline:.now()+1,execute:item)}
    }
    public func addTask(_ source:DownloadTask){
        guard !tasks.contains(where:{$0.id==source.id}) else{return}
        var task=source;task.status = .pending;task.progress=0;task.errorMessage=nil
        tasks.insert(task,at:0);saveTasks(force:true);pump()
    }
    public func addTasks(_ rows:[DownloadTask]){for var task in rows.reversed() where !tasks.contains(where:{$0.id==task.id}){task.status = .pending;tasks.insert(task,at:0)};saveTasks(force:true);pump()}
    public func addBatchTasks(_ rows:[DownloadTask]){addTasks(rows)}
    private func pump(){
        let limit=max(1,min(5,UserDefaults.standard.object(forKey:"omni.concurrent") as? Int ?? 3))
        for task in tasks.reversed() where task.status == .pending && operations[task.id]==nil{
            if operations.count>=limit{break};start(task)
        }
    }
    private func start(_ task:DownloadTask){
        let generation=UUID();generations[task.id]=generation
        update(task.id){$0.status = .downloading;$0.errorMessage=nil}
        operations[task.id]=Task{[weak self]in
            guard let self=self else{return}
            defer{if self.generations[task.id]==generation{self.operations.removeValue(forKey:task.id);self.generations.removeValue(forKey:task.id);self.progressTimes.removeValue(forKey:task.id);self.saveTasks(force:true);self.pump()}}
            do{
                let output=try await NativeMediaPipeline.execute(task,progress:{[weak self]value,message in Task{@MainActor in
                    guard let self=self,self.generations[task.id]==generation else{return}
                    let now=ProcessInfo.processInfo.systemUptime
                    if value<98,now-(self.progressTimes[task.id] ?? 0)<0.25{return};self.progressTimes[task.id]=now
                    self.update(task.id){item in guard [.downloading,.processing].contains(item.status) else{return};item.progress=value;item.eta=message;item.status=value>=85 ? .processing:.downloading}
                }})
                guard !Task.isCancelled,self.generations[task.id]==generation else{throw CancellationError()}
                self.update(task.id){$0.localFilePath=output.path;$0.status = .completed;$0.progress=100;$0.eta=""}
                if CookieStore.shared.autoSaveToAlbum,![.audioOnly,.imageZip].contains(task.downloadType){
                    do{
                        if [.image,.gif,.cover].contains(task.downloadType){try await MediaSaver.shared.saveImageToAlbum(fileUrl:output)}
                        else{try await MediaSaver.shared.saveVideoToAlbum(fileUrl:output)}
                    }catch{self.update(task.id){$0.errorMessage="文件已保存，相册写入未完成：\(error.localizedDescription)"}}
                }
                if task.saveCover==true,!task.thumbnailUrl.isEmpty{
                    guard !Task.isCancelled,self.generations[task.id]==generation else{return}
                    let cover=DownloadTask(url:task.url,title:task.title+"_封面",thumbnailUrl:task.thumbnailUrl,downloadType:.cover,mediaId:task.mediaId)
                    self.addTask(cover)
                }
            }catch{
                guard self.generations[task.id]==generation else{return}
                if Task.isCancelled || error is CancellationError{return}
                let message=error.localizedDescription
                if message.contains("FOREGROUND_REQUIRED"){foregroundPaused.insert(task.id);self.update(task.id){$0.status = .paused;$0.errorMessage=message};return}
                let waiting=["AUTH_REQUIRED","CHALLENGE_REQUIRED","SOURCE_EXPIRED","BROWSER_ASSIST_REQUIRED","COOKIE_INVALID"].contains{message.contains($0)}
                self.update(task.id){$0.status=waiting ? .waitingForAuth:.failed;$0.errorMessage=message;$0.eta=""}
            }
        }
    }
    private func update(_ id:String,_ body:(inout DownloadTask)->Void){guard let index=tasks.firstIndex(where:{$0.id==id}) else{return};body(&tasks[index]);saveTasks()}
    private func stop(_ id:String,status:TaskStatus){
        generations.removeValue(forKey:id);operations.removeValue(forKey:id)?.cancel();update(id){$0.status=status;$0.speed="";$0.eta=""};saveTasks(force:true);pump()
    }
    public func pauseTask(id:String){stop(id,status:.paused)}
    public func cancelTask(id:String){stop(id,status:.cancelled)}
    public func resumeTask(id:String){retryTask(id:id)}
    public func retryTask(id:String){
        guard let task=tasks.first(where:{$0.id==id}),![.downloading,.processing,.pending].contains(task.status) else{return}
        if task.status == .waitingForAuth{
            pendingRetry=id;NotificationCenter.default.post(name:[.image,.imageZip].contains(task.downloadType) ? .omniAnalyzeImages:.omniAnalyzeURL,object:task.url);return
        }
        update(id){$0.status = .pending;$0.errorMessage=nil};pump()
    }
    func acceptRefreshed(_ metadata:VideoMetadata)->Bool{
        guard let id=pendingRetry,let index=tasks.firstIndex(where:{$0.id==id}) else{return false}
        let old=tasks[index],rows=metadata.multiMediaList.isEmpty ? [metadata]:metadata.multiMediaList
        guard let row=rows.first(where:{old.mediaId==nil || $0.mediaId==old.mediaId}),!row.availableVideoFormats.isEmpty else{return false}
        let area=(old.selectedWidth ?? 0)*(old.selectedHeight ?? 0)
        guard let format=row.availableVideoFormats.first(where:{$0.width * $0.height >= area}) else{return false}
        var replacement=DownloadTask(id:old.id,url:row.url,title:old.title,author:row.author,thumbnailUrl:row.thumbnailUrl,downloadType:old.downloadType,selectedResolution:format.resolutionLabel,audioFormat:old.audioFormat,collectionId:old.collectionId,collectionTitle:old.collectionTitle,episodeIndex:old.episodeIndex,episodeTotal:old.episodeTotal,selectedVideoFormatId:format.formatId,mediaId:row.mediaId,expectedDurationMs:format.sourceDurationMs ?? row.expectedDurationMs,selectedWidth:format.width,selectedHeight:format.height,saveCover:old.saveCover ?? false)
        replacement.createdAt=old.createdAt;tasks[index]=replacement;pendingRetry=nil;saveTasks(force:true);pump();return true
    }
    public func deleteTask(id:String,deleteFile:Bool=true){
        guard let task=tasks.first(where:{$0.id==id}) else{return}
        generations.removeValue(forKey:id);operations.removeValue(forKey:id)?.cancel()
        if deleteFile,let path=task.localFilePath{try? FileManager.default.removeItem(at:URL(fileURLWithPath:path))}
        tasks.removeAll{$0.id==id};saveTasks(force:true);pump()
    }
    public func clearCompletedTasks(){tasks.removeAll{$0.status == .completed};saveTasks(force:true)}
    func resumeForegroundProcessing(){let ids=foregroundPaused;foregroundPaused.removeAll();for id in ids{if tasks.first(where:{$0.id==id})?.status == .paused{retryTask(id:id)}}}
    public func clearHistory(){guard !tasks.contains(where:{[.pending,.downloading,.processing].contains($0.status)}) else{return};operations.values.forEach{$0.cancel()};operations.removeAll();generations.removeAll();tasks.removeAll();saveTasks(force:true)}
    func acceptRefreshedImages(_ post:ImagePost)->Bool{
        guard let id=pendingRetry,let index=tasks.firstIndex(where:{$0.id==id}),[.image,.imageZip].contains(tasks[index].downloadType) else{return false}
        let old=tasks[index],ids=Set(old.selectedImageIds ?? []),available=Set(post.resources.map(\.id))
        guard ids.isSubset(of:available),!ids.isEmpty else{return false}
        var replacement=DownloadTask(id:old.id,url:post.url,title:old.title,author:post.author,thumbnailUrl:old.thumbnailUrl,downloadType:old.downloadType,collectionId:old.collectionId,collectionTitle:old.collectionTitle,episodeIndex:old.episodeIndex,episodeTotal:old.episodeTotal,mediaId:post.contentId,imagePlatform:post.platform,selectedImageIds:Array(ids))
        replacement.createdAt=old.createdAt;tasks[index]=replacement;pendingRetry=nil;saveTasks(force:true);pump();return true
    }
    public func pauseAll(){for id in tasks.filter({[.pending,.downloading,.processing].contains($0.status)}).map(\.id){stop(id,status:.paused)}}
    public func resumeAll(){for id in tasks.filter({[.paused,.failed,.cancelled].contains($0.status)}).map(\.id){retryTask(id:id)}}
    public func cancelCollection(collectionId:String){for id in tasks.filter({$0.collectionId==collectionId}).map(\.id){cancelTask(id:id)}}
    public func deleteCollection(collectionId:String,deleteFiles:Bool=true){for id in tasks.filter({$0.collectionId==collectionId}).map(\.id){deleteTask(id:id,deleteFile:deleteFiles)}}
    public func pauseCollection(collectionId:String){for id in tasks.filter({$0.collectionId==collectionId && [.pending,.downloading,.processing].contains($0.status)}).map(\.id){pauseTask(id:id)}}
    public func resumeCollection(collectionId:String){for id in tasks.filter({$0.collectionId==collectionId && [.paused,.failed,.cancelled,.waitingForAuth].contains($0.status)}).map(\.id){resumeTask(id:id)}}
    public func retryCollection(collectionId:String){resumeCollection(collectionId:collectionId)}
    public func getDisplayItems(completed:Bool)->[TaskDisplayItem]{
        let filtered=tasks.filter{completed ? $0.status == .completed:$0.status != .completed}
        var groups:[String:[DownloadTask]]=[:],emitted=Set<String>(),result:[TaskDisplayItem]=[]
        for task in filtered{if let id=task.collectionId,!id.isEmpty{groups[id,default:[]].append(task)}}
        for task in filtered{
            if let id=task.collectionId,!id.isEmpty{if emitted.insert(id).inserted{result.append(.collection(collectionId:id,collectionTitle:task.collectionTitle ?? task.title,tasks:(groups[id] ?? []).sorted{$0.episodeIndex<$1.episodeIndex}))}}
            else{result.append(.single(task:task))}
        };return result
    }
    public var displayItems:[TaskDisplayItem]{getDisplayItems(completed:false)+getDisplayItems(completed:true)}
}
