import Foundation

/// Reads at most eight 64KiB ranges. A CDN ignoring Range never causes a full pre-download.
enum NativeMP4Probe {
    struct Info{let width:Int;let height:Int;let durationMs:Int64}
    private final class RangeDelegate:NSObject,URLSessionDataDelegate,@unchecked Sendable{
        let offset:Int64;var bytes=Data();var total:Int64?;var done:CheckedContinuation<(Data,Int64?)?,Never>?
        var session:URLSession?;var task:URLSessionDataTask?
        init(offset:Int64){self.offset=offset}
        func finish(_ value:(Data,Int64?)?){guard let done=done else{return};self.done=nil;done.resume(returning:value);task?.cancel();session?.invalidateAndCancel();session=nil;task=nil}
        func urlSession(_ session:URLSession,dataTask:URLSessionDataTask,didReceive response:URLResponse,completionHandler:@escaping(URLSession.ResponseDisposition)->Void){
            guard let http=response as? HTTPURLResponse else{completionHandler(.cancel);finish(nil);return}
            if http.statusCode==206{
                let header=http.value(forHTTPHeaderField:"Content-Range") ?? ""
                guard let range=header.range(of:#"^bytes (\d+)-(\d+)/(\d+|\*)$"#,options:.regularExpression) else{completionHandler(.cancel);finish(nil);return}
                let values=header[range].dropFirst(6).split(separator:"/")
                guard Int64(values[0].split(separator:"-")[0])==offset else{completionHandler(.cancel);finish(nil);return};total=Int64(values[1])
            }else if http.statusCode==200 && offset==0{if response.expectedContentLength>0{total=response.expectedContentLength}}
            else{completionHandler(.cancel);finish(nil);return}
            completionHandler(.allow)
        }
        func urlSession(_ session:URLSession,dataTask:URLSessionDataTask,didReceive data:Data){
            let count=min(65536-bytes.count,data.count);if count>0{bytes.append(data.prefix(count))}
            if bytes.count>=65536{finish((bytes,total))}
        }
        func urlSession(_ session:URLSession,task:URLSessionTask,didCompleteWithError error:Error?){finish(error==nil && !bytes.isEmpty ? (bytes,total):nil)}
    }
    private final class Reader{
        let url:URL;let site:String;let deadline:TimeInterval
        var blocks:[(Int64,Data)]=[];var total:Int64?;var requests=0
        init(_ url:URL,site:String,budget:TimeInterval){self.url=url;self.site=site;deadline=ProcessInfo.processInfo.systemUptime+budget}
        func read(_ at:Int64,_ count:Int)async->Data?{
            guard at>=0,count>0,count<=16,at<Int64.max-Int64(count),!Task.isCancelled else{return nil}
            if let block=blocks.first(where:{at >= $0.0 && at+Int64(count)<=$0.0+Int64($0.1.count)}){return block.1.subdata(in:Int(at-block.0)..<Int(at-block.0)+count)}
            guard requests<8,ProcessInfo.processInfo.systemUptime<deadline,total.map({at<$0}) ?? true,at<Int64.max-65536 else{return nil}
            requests+=1
            guard let config=try? OmniNetwork.configuration() else{return nil}
            config.timeoutIntervalForRequest=max(0.1,deadline-ProcessInfo.processInfo.systemUptime);config.timeoutIntervalForResource=config.timeoutIntervalForRequest
            let delegate=RangeDelegate(offset:at)
            let value=await withTaskCancellationHandler(operation:{await withCheckedContinuation{continuation in
                delegate.done=continuation;let session=URLSession(configuration:config,delegate:delegate,delegateQueue:nil);delegate.session=session
                let request=OmniNetwork.request(url,site:site,headers:["Range":"bytes=\(at)-\(at+65535)","Accept-Encoding":"identity"])
                let task=session.dataTask(with:request);delegate.task=task;task.resume()
            }},onCancel:{delegate.task?.cancel()})
            guard let value=value,value.0.count>=count else{return nil}
            if let size=value.1{if let old=total,old != size{return nil};total=size}
            blocks.append((at,value.0));return value.0.prefix(count)
        }
        func uint(_ at:Int64,_ size:Int)async->UInt64?{
            guard let data=await read(at,size) else{return nil};return data.reduce(UInt64(0)){$0<<8|UInt64($1)}
        }
        struct Atom{let at:Int64;let end:Int64;let body:Int64;let kind:String}
        func atom(_ at:Int64,limit:Int64)async->Atom?{
            guard at>=0,at<=limit-8,let header=await read(at,8),let name=String(data:header[4..<8],encoding:.ascii) else{return nil}
            var size=header.prefix(4).reduce(UInt64(0)){$0<<8|UInt64($1)};var body=at+8
            if size==1{guard let large=await uint(at+8,8) else{return nil};size=large;body+=8}
            guard size>=UInt64(body-at),size<=UInt64(limit-at) else{return nil}
            return Atom(at:at,end:at+Int64(size),body:body,kind:name)
        }
        func children(_ parent:Atom,depth:Int=0)async->Info?{
            guard depth<5 else{return nil};var at=parent.body,duration:Int64=0,dimensions:(Int,Int)?
            for _ in 0..<64{
                guard let child=await atom(at,limit:parent.end) else{break}
                if child.kind=="mvhd",let version=await uint(child.body,1){
                    let offset:Int64=version==1 ? 20:12
                    if let scale=await uint(child.body+offset,4),scale>0,let ticks=await uint(child.body+offset+4,version==1 ? 8:4),ticks<UInt64(Int64.max/1000){duration=Int64(ticks*1000/scale)}
                }else if child.kind=="tkhd",child.end-child.body>=84,
                         let w=await uint(child.end-8,4),let h=await uint(child.end-4,4),w>>16>0,h>>16>0,w>>16<=32768,h>>16<=32768{dimensions=(Int(w>>16),Int(h>>16))}
                else if ["trak","moov"].contains(child.kind),let nested=await children(child,depth:depth+1){dimensions=(nested.width,nested.height);if duration==0{duration=nested.durationMs}}
                at=child.end;if at>=parent.end{break}
            }
            if let size=dimensions{return Info(width:size.0,height:size.1,durationMs:duration)};return nil
        }
        func probe()async->Info?{
            var at:Int64=0
            for _ in 0..<64{
                guard let box=await atom(at,limit:total ?? Int64.max) else{return nil}
                if box.kind=="moov"{return await children(box)}
                at=box.end;if let total=total,at>=total{break}
            };return nil
        }
    }
    static func confirm(_ input:VideoMetadata)async throws->VideoMetadata{
        let scope=PlatformSession.shared.generation(input.siteName)
        guard input.sourceScope==nil || input.sourceScope==scope else{throw CancellationError()}
        var metadata=input,formats=input.availableVideoFormats
        for index in formats.indices.prefix(2){
            let source=formats[index].sourceUrl ?? formats[index].formatId
            guard let url=URL(string:source),NativeMediaParser.safeURL(source),!source.contains(".m3u8") else{continue}
            if let result=await Reader(url,site:input.siteName,budget:3).probe(){
                let old=formats[index]
                var format=FormatOption(formatId:old.formatId,resolutionLabel:MediaQuality.label(result.width,result.height),ext:old.ext,width:result.width,height:result.height,fps:old.fps,approximateSize:old.approximateSize,note:old.note,isAudioOnly:old.isAudioOnly,sourceUrl:old.sourceUrl,sourceHeaders:old.sourceHeaders,vcodec:old.vcodec,dynamicRange:old.dynamicRange,bitrateKbps:old.bitrateKbps)
                format.dimensionsVerified=true;format.sourceDurationMs=result.durationMs>0 ? result.durationMs:nil;format.audioSourceUrl=old.audioSourceUrl;formats[index]=format
            }
            try Task.checkCancellation()
        }
        guard scope==PlatformSession.shared.generation(input.siteName) else{throw CancellationError()}
        metadata.availableVideoFormats=MediaQuality.sorted(formats)
        if let best=metadata.availableVideoFormats.first{metadata.qualityEvidence="本次最高规格：\(best.resolutionLabel) · \(best.dimensionsVerified==true ? "尺寸已实测":"平台标注，下载后核对")"}
        MediaSources.shared.remember(metadata);return metadata
    }
}
