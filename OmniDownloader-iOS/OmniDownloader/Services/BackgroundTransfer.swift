import Foundation
import CryptoKit

/// URLSession owns network transfers while the app is suspended. Conversion resumes in foreground.
final class BackgroundTransfer:NSObject,URLSessionDownloadDelegate,@unchecked Sendable{
    static let shared=BackgroundTransfer()
    private final class Record{
        let continuation:CheckedContinuation<URL,Error>;let progress:(Float)->Void;let site:String;let scope:String;let resumeFile:URL
        var staged:URL?
        let range:String?
        init(_ continuation:CheckedContinuation<URL,Error>,progress:@escaping(Float)->Void,site:String,resumeFile:URL,range:String?){self.continuation=continuation;self.progress=progress;self.site=site;self.scope=PlatformSession.shared.generation(site);self.resumeFile=resumeFile;self.range=range}
    }
    private final class CancellationSlot:@unchecked Sendable{
        let lock=NSLock();var task:URLSessionDownloadTask?;var cancelled=false;let resumeFile:URL
        init(_ file:URL){resumeFile=file}
        func install(_ value:URLSessionDownloadTask){lock.lock();task=value;let stop=cancelled;lock.unlock();if stop{cancel()}else{value.resume()}}
        func cancel(){lock.lock();cancelled=true;let value=task;lock.unlock();value?.cancel{data in if let data=data{try? data.write(to:self.resumeFile,options:[.atomic,.completeFileProtectionUntilFirstUserAuthentication])}}}
    }
    private let lock=NSRecursiveLock()
    private var records:[String:Record]=[:]
    private var sessions:[String:URLSession]=[:]
    private var completions:[String:()->Void]=[:]
    private var root:URL{
        let url=FileManager.default.urls(for:.cachesDirectory,in:.userDomainMask)[0].appendingPathComponent("omni-transfers",isDirectory:true)
        try? FileManager.default.createDirectory(at:url,withIntermediateDirectories:true,attributes:[.protectionKey:FileProtectionType.completeUntilFirstUserAuthentication]);return url
    }
    private func key(_ session:URLSession,_ task:URLSessionTask)->String{(session.configuration.identifier ?? "")+"|\(task.taskIdentifier)"}
    private func connection()throws->URLSession{
        lock.lock();defer{lock.unlock()}
        let proxy=UserDefaults.standard.string(forKey:"omni.proxy") ?? ""
        let fingerprint=SHA256.hash(data:Data(proxy.utf8)).prefix(8).map{String(format:"%02x",$0)}.joined()
        let identifier="com.omni.downloader.transfer."+fingerprint
        if let existing=sessions[identifier]{return existing}
        let route=try OmniNetwork.configuration(),config=URLSessionConfiguration.background(withIdentifier:identifier)
        config.httpCookieStorage=nil;config.httpShouldSetCookies=false;config.isDiscretionary=false;config.sessionSendsLaunchEvents=true
        config.timeoutIntervalForRequest=45;config.timeoutIntervalForResource=3600
        if #available(iOS 17.0,*){config.proxyConfigurations=route.proxyConfigurations}
        let session=URLSession(configuration:config,delegate:self,delegateQueue:nil);sessions[identifier]=session
        session.getAllTasks{tasks in for task in tasks{self.lock.lock();let known=self.records[self.key(session,task)] != nil;self.lock.unlock();if !known{task.cancel()}}}
        return session
    }
    func restore(_ identifier:String,completion:@escaping()->Void){
        guard identifier.hasPrefix("com.omni.downloader.transfer.") else{completion();return}
        lock.lock();defer{lock.unlock()};completions[identifier]=completion
        if sessions[identifier]==nil{
            let config=URLSessionConfiguration.background(withIdentifier:identifier);config.httpCookieStorage=nil
            let session=URLSession(configuration:config,delegate:self,delegateQueue:nil);sessions[identifier]=session
            session.getAllTasks{$0.forEach{$0.cancel()}} // No business continuation survives process death.
        }
    }
    func download(_ url:URL,site:String,taskId:String,headers:[String:String]=[:],progress:@escaping(Float)->Void)async throws->URL{
        try Task.checkCancellation()
        let session=try connection()
        let intent=url.absoluteString+"|"+PlatformSession.shared.generation(site)+"|"+(headers["Range"] ?? "")
        let digest=SHA256.hash(data:Data(intent.utf8)).prefix(16).map{String(format:"%02x",$0)}.joined()
        let resume=root.appendingPathComponent(digest+".resume"),slot=CancellationSlot(resume)
        return try await withTaskCancellationHandler(operation:{
            try await withCheckedThrowingContinuation{continuation in
                let task:URLSessionDownloadTask
                if let data=try? Data(contentsOf:resume){task=session.downloadTask(withResumeData:data)}
                else{task=session.downloadTask(with:OmniNetwork.request(url,site:site,headers:headers))}
                task.taskDescription=taskId
                lock.lock();records[key(session,task)]=Record(continuation,progress:progress,site:site,resumeFile:resume,range:headers["Range"]);lock.unlock()
                slot.install(task)
            }
        },onCancel:{slot.cancel()})
    }
    func urlSession(_ session:URLSession,downloadTask:URLSessionDownloadTask,didWriteData bytesWritten:Int64,totalBytesWritten:Int64,totalBytesExpectedToWrite:Int64){
        lock.lock();let record=records[key(session,downloadTask)];lock.unlock()
        guard let record=record else{return}
        if let range=record.range,let response=downloadTask.response as? HTTPURLResponse,!Self.acceptsRange(response,range){downloadTask.cancel();return}
        if record.scope != PlatformSession.shared.generation(record.site){downloadTask.cancel();return}
        if totalBytesExpectedToWrite>0{record.progress(Float(totalBytesWritten)/Float(totalBytesExpectedToWrite))}
    }
    func urlSession(_ session:URLSession,downloadTask:URLSessionDownloadTask,didFinishDownloadingTo location:URL){
        lock.lock();defer{lock.unlock()}
        guard let record=records[key(session,downloadTask)] else{return}
        let destination=root.appendingPathComponent(UUID().uuidString+".part")
        do{try FileManager.default.moveItem(at:location,to:destination);record.staged=destination}catch{record.staged=nil}
    }
    func urlSession(_ session:URLSession,task:URLSessionTask,didCompleteWithError error:Error?){
        lock.lock();let record=records.removeValue(forKey:key(session,task));lock.unlock()
        guard let record=record else{return}
        if record.scope != PlatformSession.shared.generation(record.site){if let file=record.staged{try? FileManager.default.removeItem(at:file)};try? FileManager.default.removeItem(at:record.resumeFile);record.continuation.resume(throwing:OmniPlatform.error("SOURCE_EXPIRED","账号或网络已改变，请重新解析"));return}
        if let range=record.range,let response=task.response as? HTTPURLResponse,!Self.acceptsRange(response,range){if let file=record.staged{try? FileManager.default.removeItem(at:file)};try? FileManager.default.removeItem(at:record.resumeFile);record.continuation.resume(throwing:OmniPlatform.error("NETWORK_ERROR","服务器未正确返回所需的媒体区间"));return}
        if let error=error{if let file=record.staged{try? FileManager.default.removeItem(at:file)};record.continuation.resume(throwing:error);return}
        guard record.scope==PlatformSession.shared.generation(record.site),let response=task.response as? HTTPURLResponse,(200...299).contains(response.statusCode),let file=record.staged else{
            if let file=record.staged{try? FileManager.default.removeItem(at:file)}
            try? FileManager.default.removeItem(at:record.resumeFile)
            record.continuation.resume(throwing:OmniPlatform.error("SOURCE_EXPIRED","媒体源已失效或会话已改变，请重新解析"));return
        }
        let length=(try? file.resourceValues(forKeys:[.fileSizeKey]).fileSize) ?? 0
        if let range=record.range{let parts=range.dropFirst(6).split(separator:"-").compactMap{Int($0)};if parts.count != 2 || parts[1]<parts[0] || length != parts[1]-parts[0]+1{try? FileManager.default.removeItem(at:file);record.continuation.resume(throwing:OmniPlatform.error("NETWORK_ERROR","媒体区间不完整"));return}}
        guard length>0,response.expectedContentLength<=0 || response.expectedContentLength==Int64(length) else{
            try? FileManager.default.removeItem(at:file);record.continuation.resume(throwing:OmniPlatform.error("NETWORK_ERROR","媒体下载不完整，请重试"));return
        }
        try? FileManager.default.removeItem(at:record.resumeFile);record.continuation.resume(returning:file)
    }
    private static func acceptsRange(_ response:HTTPURLResponse,_ range:String)->Bool{response.statusCode==206 && range.hasPrefix("bytes=") && (response.value(forHTTPHeaderField:"Content-Range") ?? "").lowercased().hasPrefix("bytes "+range.dropFirst(6)+"/")}
    func urlSession(_ session:URLSession,task:URLSessionTask,willPerformHTTPRedirection response:HTTPURLResponse,newRequest request:URLRequest,completionHandler:@escaping(URLRequest?)->Void){
        lock.lock();let record=records[key(session,task)];lock.unlock()
        guard let record=record,let url=request.url,NativeMediaParser.safeURL(url.absoluteString) else{completionHandler(nil);return}
        var next=request;next.setValue(nil,forHTTPHeaderField:"Cookie");next.setValue(nil,forHTTPHeaderField:"Authorization")
        let header=PlatformSession.shared.header(record.site,url:url);if !header.isEmpty{next.setValue(header,forHTTPHeaderField:"Cookie")};completionHandler(next)
    }
    func urlSessionDidFinishEvents(forBackgroundURLSession session:URLSession){
        lock.lock();let done=completions.removeValue(forKey:session.configuration.identifier ?? "");lock.unlock();if let done=done{DispatchQueue.main.async{done()}}
    }
}
