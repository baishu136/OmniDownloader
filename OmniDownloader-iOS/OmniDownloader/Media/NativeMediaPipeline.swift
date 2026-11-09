import Foundation
import ImageIO
import UniformTypeIdentifiers
import CryptoKit

enum NativeMediaPipeline {
    private struct Ready:Codable{let signature:String;let name:String}
    static var workRoot:URL{
        FileManager.default.urls(for:.applicationSupportDirectory,in:.userDomainMask)[0].appendingPathComponent("media_tasks",isDirectory:true)
    }
    static func execute(_ task:DownloadTask,progress:@escaping(Float,String)->Void)async throws->URL{
        guard task.id.range(of:#"^[a-zA-Z0-9_-]{1,64}$"#,options:.regularExpression) != nil else{throw OmniPlatform.error("POSTPROCESS_FAILED","任务 ID 不合法")}
        let work=workRoot.appendingPathComponent(task.id,isDirectory:true),ready=work.appendingPathComponent("ready.json")
        try FileManager.default.createDirectory(at:work,withIntermediateDirectories:true,attributes:[.protectionKey:FileProtectionType.completeUntilFirstUserAuthentication])
        let signature=SHA256.hash(data:Data("\(task.url)|\(task.mediaId ?? "")|\(task.selectedVideoFormatId ?? "")|\(task.downloadType.rawValue)|\(task.audioFormat.rawValue)|\(task.selectedImageIds ?? [])".utf8)).map{String(format:"%02x",$0)}.joined()
        var staged:URL?
        if let data=try? Data(contentsOf:ready),let record=try? JSONDecoder().decode(Ready.self,from:data),record.signature==signature,!record.name.contains("/"),!record.name.contains(".."){
            let file=work.appendingPathComponent(record.name)
            if FileManager.default.fileExists(atPath:file.path){staged=file}
        }
        if staged==nil{
            let site=task.imagePlatform ?? OmniPlatform.detect(task.url),scope=PlatformSession.shared.generation(site)
            if [.image,.imageZip].contains(task.downloadType){staged=try await images(task,work:work,progress:progress)}
            else if site=="Pixiv",let post=try? MediaSources.shared.images(task),post.frameArchive != nil{staged=try await animation(post,task:task,work:work,progress:progress)}
            else{staged=try await video(task,work:work,progress:progress)}
            try Task.checkCancellation()
            guard scope==PlatformSession.shared.generation(site) else{throw OmniPlatform.error("SOURCE_EXPIRED","账号或网络已改变，请重新解析")}
            guard let file=staged else{throw OmniPlatform.error("POSTPROCESS_FAILED","没有生成成品")}
            try await NativeFFmpeg.verify(file,task:task)
            try JSONEncoder().encode(Ready(signature:signature,name:file.lastPathComponent)).write(to:ready,options:[.atomic,.completeFileProtectionUntilFirstUserAuthentication])
        }
        guard let file=staged else{throw OmniPlatform.error("POSTPROCESS_FAILED","没有可导出的成品")}
        try await NativeFFmpeg.verify(file,task:task);try Task.checkCancellation();progress(98,"正在导出成品…")
        let output=try export(file,task:task)
        try? FileManager.default.removeItem(at:work)
        return output
    }
    private static func fetch(_ source:String,site:String,task:DownloadTask,work:URL,name:String,headers:[String:String]=[:],progress:@escaping(Float)->Void)async throws->URL{
        guard let url=URL(string:source),NativeMediaParser.safeURL(source) else{throw OmniPlatform.error("SOURCE_EXPIRED","媒体地址无效，请重新解析")}
        let temporary=try await BackgroundTransfer.shared.download(url,site:site,taskId:task.id,headers:headers,progress:progress)
        defer{try? FileManager.default.removeItem(at:temporary)}
        let target=work.appendingPathComponent(name);try? FileManager.default.removeItem(at:target)
        try Task.checkCancellation();try FileManager.default.moveItem(at:temporary,to:target);return target
    }
    private static func video(_ task:DownloadTask,work:URL,progress:@escaping(Float,String)->Void)async throws->URL{
        let site=OmniPlatform.detect(task.url)
        var input:URL,audio:URL?,hls=false
        if task.downloadType == .cover{
            let meta=try MediaSources.shared.video(task)
            let image=try await fetch(task.thumbnailUrl.isEmpty ? meta.thumbnailUrl:task.thumbnailUrl,site:site,task:task,work:work,name:"cover.source",progress:{progress($0*90,"正在下载封面…")})
            return try imageFile(image,work:work,name:"output")
        }
        if site=="YouTube"{
            var request=task
            if task.downloadType == .gif{request.downloadType = .videoOnly}
            if task.downloadType == .audioOnly{request.audioFormat = .m4a}
            let file=try await YouTubeDownload.download(request,progress:{progress($0*80,"正在下载媒体…")})
            input=work.appendingPathComponent("input."+file.pathExtension);try? FileManager.default.removeItem(at:input);try FileManager.default.moveItem(at:file,to:input)
        }else if site=="哔哩哔哩"{
            let streams=try await BilibiliExtractor.shared.fetchStreamUrls(for:task)
            let audioOnly=task.downloadType == .audioOnly
            input=try await fetch(audioOnly ? streams.audioUrl:streams.videoUrl,site:site,task:task,work:work,name:audioOnly ? "input.m4a":"input.mp4",progress:{progress($0*70,"正在下载媒体…")})
            if task.downloadType == .videoWithAudio,!streams.audioUrl.isEmpty{audio=try await fetch(streams.audioUrl,site:site,task:task,work:work,name:"audio.m4a",progress:{progress(70+$0*15,"正在下载音轨…")})}
        }else{
            let metadata=try MediaSources.shared.video(task)
            let formats=metadata.availableVideoFormats
            let selected=formats.first{$0.formatId==task.selectedVideoFormatId && MediaQuality.meets($0,task)} ?? formats.first{MediaQuality.meets($0,task)}
            guard let selected=selected else{throw OmniPlatform.error("QUALITY_UNAVAILABLE","所选规格已不可用，请重新解析")}
            let source=selected.sourceUrl ?? selected.formatId
            if let url=URL(string:source),url.pathExtension.lowercased()=="m3u8" || source.contains(".m3u8?"){
                let local=try await NativeHLS.prepare(url,site:site,taskId:task.id,directory:work.appendingPathComponent("hls"),progress:{progress($0*75,"正在下载分段媒体…")});input=local.video;audio=local.audio;hls=true
                if audio==nil,[.videoWithAudio,.audioOnly].contains(task.downloadType),let extra=selected.audioSourceUrl,let address=URL(string:extra){audio=try await NativeHLS.prepare(address,site:site,taskId:task.id,directory:work.appendingPathComponent("hls-audio"),progress:{progress(75+$0*10,"正在下载音轨…")}).video}
            }else{input=try await fetch(source,site:site,task:task,work:work,name:"input.media",headers:selected.sourceHeaders ?? [:],progress:{progress($0*85,"正在下载媒体…")})}
        }
        try Task.checkCancellation();progress(88,"正在处理媒体…")
        let output=work.appendingPathComponent("output."+(task.downloadType == .gif ? "gif":task.downloadType == .audioOnly ? task.audioFormat.rawValue:"mp4"))
        try? FileManager.default.removeItem(at:output)
        switch task.downloadType{
        case .gif:
            let header=try Data(contentsOf:input,options:.mappedIfSafe).prefix(6)
            if ["GIF87a","GIF89a"].contains(String(decoding:header,as:UTF8.self)){try FileManager.default.copyItem(at:input,to:output)}
            else{try await NativeFFmpeg.gif(input,output:output)}
        case .audioOnly:
            if task.audioFormat == .m4a{
                let flags=hls ? ["-protocol_whitelist","file,crypto,data","-allowed_extensions","ALL"]:[]
                do{try await NativeFFmpeg.run(flags+["-i",(audio ?? input).path,"-map","0:a:0","-vn","-c:a","copy",output.path])}
                catch is CancellationError{throw CancellationError()}
                catch{try await NativeFFmpeg.audio(audio ?? input,output:output,format:.m4a)}
            }else{try await NativeFFmpeg.audio(audio ?? input,output:output,format:task.audioFormat)}
        default:
            var args=hls ? ["-protocol_whitelist","file,crypto,data","-allowed_extensions","ALL"]:[]
            args += ["-i",input.path]
            if let audio=audio,task.downloadType == .videoWithAudio{
                if hls{args += ["-protocol_whitelist","file,crypto,data","-allowed_extensions","ALL"]}
                args += ["-i",audio.path,"-map","0:v:0","-map","1:a:0"]
            }else{args += ["-map","0:v:0"];if task.downloadType != .videoOnly{args += ["-map","0:a?"]}}
            args += ["-c","copy","-movflags","+faststart",output.path]
            if task.downloadType == .videoWithAudio,audio==nil,!hls{
                let handle=try FileHandle(forReadingFrom:input);let bytes=try handle.read(upToCount:16) ?? Data();try handle.close()
                if bytes.count>=8,String(decoding:bytes[4..<8],as:UTF8.self)=="ftyp"{try FileManager.default.copyItem(at:input,to:output)}
                else{try await NativeFFmpeg.run(args)}
            }else{try await NativeFFmpeg.run(args)}
        }
        return output
    }
    private static func imageFile(_ source:URL,work:URL,name:String)throws->URL{
        guard let image=CGImageSourceCreateWithURL(source as CFURL,nil),let type=CGImageSourceGetType(image),CGImageSourceGetCount(image)>0 else{throw OmniPlatform.error("POSTPROCESS_FAILED","服务器没有返回可解码图片")}
        let ext=UTType(type as String)?.preferredFilenameExtension ?? "image"
        let file=work.appendingPathComponent(name+"."+ext);try? FileManager.default.removeItem(at:file);try FileManager.default.moveItem(at:source,to:file);return file
    }
    private static func images(_ task:DownloadTask,work:URL,progress:@escaping(Float,String)->Void)async throws->URL{
        let post=try MediaSources.shared.images(task)
        if post.frameArchive != nil{return try await animation(post,task:task,work:work,progress:progress)}
        let ids=Set(task.selectedImageIds ?? []),selected=post.resources.filter{ids.contains($0.id)}
        guard !selected.isEmpty,selected.count<=500,task.downloadType != .image || selected.count==1 else{throw OmniPlatform.error("NO_MEDIA","请重新选择目标图片")}
        var files:[URL]=[]
        for(index,image)in selected.enumerated(){
            let source=try await fetch(image.url,site:post.platform,task:task,work:work,name:"image-\(index).source",progress:{progress((Float(index)+$0)/Float(selected.count)*90,"正在下载原图 \(index+1)/\(selected.count)…")})
            files.append(try imageFile(source,work:work,name:"image-\(index)"))
        }
        if task.downloadType == .image{return files[0]}
        let output=work.appendingPathComponent("output.zip");try MediaArchive.pack(files,to:output);return output
    }
    private static func animation(_ post:ImagePost,task:DownloadTask,work:URL,progress:@escaping(Float,String)->Void)async throws->URL{
        guard let archive=post.frameArchive,let names=post.frameNames,let delays=post.frameDelays,names.count==delays.count,delays.allSatisfy({$0>0 && $0<=60_000}) else{throw OmniPlatform.error("EXTRACTOR_CHANGED","Ugoira 帧信息不完整")}
        let zip=try await fetch(archive,site:post.platform,task:task,work:work,name:"frames.zip",progress:{progress($0*75,"正在下载原始帧…")})
        let directory=work.appendingPathComponent("frames",isDirectory:true),frames=try MediaArchive.unpackFrames(zip,names:names,to:directory)
        if task.downloadType == .imageZip{return zip}
        if task.downloadType == .image{guard let first=frames.first else{throw OmniPlatform.error("NO_MEDIA","没有动画帧")};let output=work.appendingPathComponent("output."+first.pathExtension);try? FileManager.default.removeItem(at:output);try FileManager.default.copyItem(at:first,to:output);return output}
        var list=""
        for(index,file)in frames.enumerated(){list += "file '\(file.lastPathComponent)'\nduration \(Double(delays[index])/1000)\n"}
        list += "file '\(frames.last!.lastPathComponent)'\n"
        let manifest=directory.appendingPathComponent("frames.txt");try list.write(to:manifest,atomically:true,encoding:.utf8)
        let output=work.appendingPathComponent(task.downloadType == .gif ? "output.gif":"output.mp4")
        let base=["-f","concat","-safe","1","-i",manifest.path]
        if task.downloadType == .gif{try await NativeFFmpeg.run(base+["-filter_complex","[0:v]split[a][b];[a]palettegen[p];[b][p]paletteuse","-vsync","vfr","-loop","0","-f","gif",output.path])}
        else{try await NativeFFmpeg.run(base+["-vf","pad=ceil(iw/2)*2:ceil(ih/2)*2","-vsync","vfr","-c:v","libx264","-pix_fmt","yuv420p","-movflags","+faststart",output.path])}
        return output
    }
    private static func export(_ source:URL,task:DownloadTask)throws->URL{
        var destination:URL,access=false
        if let bookmark=UserDefaults.standard.data(forKey:"omni.folderBookmark"){
            var stale=false
            do{destination=try URL(resolvingBookmarkData:bookmark,options:[],relativeTo:nil,bookmarkDataIsStale:&stale);access=destination.startAccessingSecurityScopedResource();guard access,!stale else{throw OmniPlatform.error("EXPORT_FAILED","请在下载与存储中重新选择保存目录")}}
            catch{throw OmniPlatform.error("EXPORT_FAILED","保存目录无法访问，已保留成品；请重新选择目录后重试")}
        }else{destination=FileManager.default.urls(for:.documentDirectory,in:.userDomainMask)[0].appendingPathComponent("Downloads",isDirectory:true);try FileManager.default.createDirectory(at:destination,withIntermediateDirectories:true)}
        defer{if access{destination.stopAccessingSecurityScopedResource()}}
        let clean=String(task.title.replacingOccurrences(of:#"[\\/:*?"<>|\p{C}]"#,with:"_",options:.regularExpression).prefix(60))
        let name=(clean.isEmpty ? "OmniDownloader":clean)+"_"+task.id
        var output=destination.appendingPathComponent(name+"."+source.pathExtension),index=1
        while FileManager.default.fileExists(atPath:output.path){output=destination.appendingPathComponent(name+"_\(index)."+source.pathExtension);index+=1}
        do{try FileManager.default.copyItem(at:source,to:output)}catch{throw OmniPlatform.error("EXPORT_FAILED","导出失败，已保留校验通过的成品，重试时无需重新下载")}
        return output
    }
}
