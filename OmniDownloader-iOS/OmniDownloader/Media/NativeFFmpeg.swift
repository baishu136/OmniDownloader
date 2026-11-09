import Foundation
import ImageIO
import ffmpegkit
import UIKit

/// Linked iOS libraries, never a downloaded executable or a remote conversion service.
enum NativeFFmpeg {
    private final class CancellationSlot: @unchecked Sendable {
        let lock=NSLock();var session:FFmpegSession?;var cancelled=false
        func install(_ value:FFmpegSession){lock.lock();session=value;let stop=cancelled;lock.unlock();if stop{value.cancel()}}
        func cancel(){lock.lock();cancelled=true;let value=session;lock.unlock();value?.cancel()}
    }
    static func run(_ arguments:[String])async throws{
        guard await MainActor.run(body: { UIApplication.shared.applicationState != .background }) else { throw OmniPlatform.error("FOREGROUND_REQUIRED","媒体处理已暂停，回到应用后继续") }
        let slot=CancellationSlot()
        try Task.checkCancellation()
        try await withTaskCancellationHandler(operation:{
            try await withCheckedThrowingContinuation{(continuation:CheckedContinuation<Void,Error>)in
                FFmpegKitConfig.enableLogCallback{_ in}
                let session=FFmpegKit.executeWithArgumentsAsync(["-hide_banner","-nostdin","-y"]+arguments,withCompleteCallback:{ completed in
                    if ReturnCode.isSuccess(completed.getReturnCode()){continuation.resume()}
                    else if completed.getReturnCode()?.getValue()==255{continuation.resume(throwing:CancellationError())}
                    else{continuation.resume(throwing:OmniPlatform.error("POSTPROCESS_FAILED","媒体处理未完成，请重新选择可用源或重试"))}
                })
                slot.install(session)
            }
        },onCancel:{slot.cancel()})
        try Task.checkCancellation()
    }
    struct Probe { var width:Int;var height:Int;var durationMs:Int64;var audio:Bool }
    static func probe(_ file:URL)async throws->Probe{
        try await withCheckedThrowingContinuation{continuation in DispatchQueue.global(qos:.utility).async{
            let report=FileManager.default.temporaryDirectory.appendingPathComponent("omni-probe-"+UUID().uuidString+".json")
            defer{try? FileManager.default.removeItem(at:report)}
            let session=FFprobeKit.executeWithArguments(["-v","error","-show_streams","-show_format","-of","json","-o",report.path,file.path])
            let text=(try? String(contentsOf:report,encoding:.utf8)) ?? ""
            guard ReturnCode.isSuccess(session.getReturnCode()),text.utf8.count<8_388_608,
                  let start=text.firstIndex(of:"{"),let end=text.lastIndex(of:"}"),start<=end,
                  let data=String(text[start...end]).data(using:.utf8),let json=try? JSONSerialization.jsonObject(with:data) as? [String:Any] else{continuation.resume(throwing:OmniPlatform.error("POSTPROCESS_FAILED","无法读取成品媒体信息"));return}
            let streams=json["streams"] as? [[String:Any]] ?? [],video=streams.first{($0["codec_type"] as? String)=="video"}
            var width=NativeMediaParser.number(video?["width"]),height=NativeMediaParser.number(video?["height"])
            let rotation=(video?["tags"] as? [String:Any])?["rotate"] ?? (video?["side_data_list"] as? [[String:Any]])?.first?["rotation"]
            if abs((rotation as? NSNumber)?.intValue ?? Int(rotation as? String ?? "") ?? 0)%180==90{swap(&width,&height)}
            let duration=Double((json["format"] as? [String:Any])?["duration"] as? String ?? "") ?? 0
            continuation.resume(returning:Probe(width:width,height:height,durationMs:Int64(duration*1000),audio:streams.contains{($0["codec_type"] as? String)=="audio"}))
        }}
    }
    static func verify(_ file:URL,task:DownloadTask)async throws{
        let size=try file.resourceValues(forKeys:[.fileSizeKey]).fileSize ?? 0
        guard size>0 else{throw OmniPlatform.error("POSTPROCESS_FAILED","成品为空")}
        if task.downloadType == .gif{
            let header=try FileHandle(forReadingFrom:file);defer{try? header.close()}
            guard let bytes=try header.read(upToCount:6),["GIF87a","GIF89a"].contains(String(decoding:bytes,as:UTF8.self)),
                  let image=CGImageSourceCreateWithURL(file as CFURL,nil),CGImageSourceGetCount(image)>0,
                  CGImageSourceCreateImageAtIndex(image,0,nil) != nil else{throw OmniPlatform.error("POSTPROCESS_FAILED","GIF 文件无法解码")};return
        }
        if [.image,.imageZip,.cover].contains(task.downloadType){return}
        let actual=try await probe(file)
        if task.downloadType == .audioOnly{guard actual.audio else{throw OmniPlatform.error("POSTPROCESS_FAILED","成品没有音轨")};return}
        guard actual.width>0,actual.height>0,actual.durationMs>0 else{throw OmniPlatform.error("POSTPROCESS_FAILED","成品不是有效视频")}
        let expected=(task.selectedWidth ?? 0)*(task.selectedHeight ?? 0)
        guard expected==0 || Double(actual.width*actual.height)>=Double(expected)*0.98 else{throw OmniPlatform.error("QUALITY_UNAVAILABLE","成品尺寸低于所选源")}
        if (task.selectedWidth ?? 0)==0,let edge=task.selectedHeight,edge>0,min(actual.width,actual.height)<edge{throw OmniPlatform.error("QUALITY_UNAVAILABLE","成品低于所选清晰度")}
        if let duration=task.expectedDurationMs,duration>0,abs(actual.durationMs-duration)>max(2000,duration/100){throw OmniPlatform.error("POSTPROCESS_FAILED","成品时长与目标不符")}
        if task.downloadType == .videoOnly,actual.audio{throw OmniPlatform.error("POSTPROCESS_FAILED","纯画面成品仍包含音轨")}
    }
    static func gif(_ source:URL,output:URL)async throws{
        let hls=source.pathExtension=="m3u8" ? ["-protocol_whitelist","file,crypto,data","-allowed_extensions","ALL"]:[]
        try await run(hls+["-i",source.path,"-t","15","-filter_complex","[0:v]fps=15,scale='min(480,iw)':-1:flags=lanczos,split[a][b];[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=5:diff_mode=rectangle","-an","-loop","0","-f","gif",output.path])
    }
    static func audio(_ source:URL,output:URL,format:AudioFormat)async throws{
        let codec:[String]
        switch format{
        case .mp3:codec=["-c:a","libmp3lame","-b:a","192k"]
        case .opus:codec=["-c:a","libopus","-b:a","128k"]
        case .flac:codec=["-c:a","flac"]
        case .wav:codec=["-c:a","pcm_s16le"]
        case .aac:codec=["-c:a","aac","-b:a","192k","-f","adts"]
        case .m4a:codec=["-c:a","aac","-b:a","192k"]
        }
        let hls=source.pathExtension=="m3u8" ? ["-protocol_whitelist","file,crypto,data","-allowed_extensions","ALL"]:[]
        try await run(hls+["-i",source.path,"-map","0:a:0","-vn"]+codec+[output.path])
    }
}
