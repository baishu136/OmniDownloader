import Foundation

enum NativeHLS {
    struct Inputs{let video:URL;let audio:URL?}
    static func inspect(_ url:URL,site:String)async throws->[FormatOption]{
        let root=try await lines(url,site:site)
        let audio=root.filter{$0.hasPrefix("#EXT-X-MEDIA:")}.map(attributes).filter{$0["TYPE"]=="AUDIO"}
        var formats:[FormatOption]=[]
        for(index,line)in root.enumerated() where line.hasPrefix("#EXT-X-STREAM-INF:"){
            let attrs=attributes(line)
            guard let next=root.dropFirst(index+1).first(where:{!$0.isEmpty && !$0.hasPrefix("#")}),let source=URL(string:next,relativeTo:url)?.absoluteURL,NativeMediaParser.safeURL(source.absoluteString) else{continue}
            let size=(attrs["RESOLUTION"] ?? "").split(separator:"x").compactMap{Int($0)},w=size.count==2 ? size[0]:0,h=size.count==2 ? size[1]:0
            let group=audio.filter{attrs["AUDIO"] != nil && $0["GROUP-ID"]==attrs["AUDIO"]},chosen=group.first{$0["DEFAULT"]=="YES"} ?? group.first
            let audioURL=chosen?["URI"].flatMap{URL(string:$0,relativeTo:url)?.absoluteURL.absoluteString}
            let codec=(attrs["CODECS"] ?? "").split(separator:",").map(String.init).first{candidate in ["avc","hev","hvc","av01","vp"].contains{candidate.hasPrefix($0)}} ?? (attrs["CODECS"] ?? "")
            formats.append(FormatOption(formatId:source.absoluteString,resolutionLabel:MediaQuality.label(w,h),ext:"m3u8",width:w,height:h,fps:Int((Double(attrs["FRAME-RATE"] ?? "") ?? 0).rounded()),sourceUrl:source.absoluteString,vcodec:codec,dynamicRange:attrs["VIDEO-RANGE"],bitrateKbps:(Double(attrs["AVERAGE-BANDWIDTH"] ?? attrs["BANDWIDTH"] ?? "") ?? 0)/1000,audioSourceUrl:audioURL))
        };return MediaQuality.sorted(formats)
    }
    static func attributes(_ line:String)->[String:String]{
        guard let regex=try? NSRegularExpression(pattern:#"([A-Z0-9-]+)=(?:"([^"]*)"|([^,]*))"#) else{return [:]}
        var output:[String:String]=[:]
        for match in regex.matches(in:line,range:NSRange(line.startIndex...,in:line)){
            let text=line as NSString,key=text.substring(with:match.range(at:1))
            output[key]=match.range(at:2).location != NSNotFound ? text.substring(with:match.range(at:2)):text.substring(with:match.range(at:3))
        };return output
    }
    private static func lines(_ url:URL,site:String)async throws->[String]{
        let(data,_)=try await OmniNetwork.data(url,site:site)
        guard data.count<=2_097_152,let text=String(data:data,encoding:.utf8),text.trimmingCharacters(in:.whitespacesAndNewlines).hasPrefix("#EXTM3U") else{throw OmniPlatform.error("EXTRACTOR_CHANGED","不是有效 HLS 清单")}
        return text.components(separatedBy:.newlines).map{$0.trimmingCharacters(in:.whitespacesAndNewlines)}
    }
    static func prepare(_ url:URL,site:String,taskId:String,directory:URL,progress:@escaping(Float)->Void)async throws->Inputs{
        let root=try await lines(url,site:site)
        var streams:[(attrs:[String:String],url:URL)]=[]
        for(index,line)in root.enumerated() where line.hasPrefix("#EXT-X-STREAM-INF:"){
            if let relative=root.dropFirst(index+1).first(where:{!$0.isEmpty && !$0.hasPrefix("#")}),let source=URL(string:relative,relativeTo:url)?.absoluteURL{streams.append((attributes(line),source))}
        }
        func area(_ value:String)->Int{let parts=value.split(separator:"x").compactMap{Int($0)};return parts.count==2 ? parts[0]*parts[1]:0}
        let selected=streams.max{a,b in let left=area(a.attrs["RESOLUTION"] ?? ""),right=area(b.attrs["RESOLUTION"] ?? "");return left==right ? (Int(a.attrs["BANDWIDTH"] ?? "") ?? 0)<(Int(b.attrs["BANDWIDTH"] ?? "") ?? 0):left<right}
        var audio:URL?
        if let group=selected?.attrs["AUDIO"]{
            let choices=root.filter{$0.hasPrefix("#EXT-X-MEDIA:")}.map(attributes).filter{$0["TYPE"]=="AUDIO" && $0["GROUP-ID"]==group}
            let choice=choices.first{$0["DEFAULT"]=="YES"} ?? choices.first
            if let path=choice?["URI"]{audio=URL(string:path,relativeTo:url)?.absoluteURL}
        }
        let video=try await localize(selected?.url ?? url,site:site,taskId:taskId,directory:directory.appendingPathComponent("video"),progress:{progress($0*(audio==nil ? 1:0.8))})
        let localAudio:URL?
        if let audio=audio{localAudio=try await localize(audio,site:site,taskId:taskId,directory:directory.appendingPathComponent("audio"),progress:{progress(0.8+$0*0.2)})}else{localAudio=nil}
        return Inputs(video:video,audio:localAudio)
    }
    private static func localize(_ url:URL,site:String,taskId:String,directory:URL,progress:@escaping(Float)->Void)async throws->URL{
        let input=try await lines(url,site:site)
        guard input.contains("#EXT-X-ENDLIST"),!input.contains(where:{$0.hasPrefix("#EXT-X-STREAM-INF")}) else{throw OmniPlatform.error("NO_MEDIA","本机离线导出需要完整点播清单，暂不接收直播或多层嵌套清单")}
        let count=input.filter{!$0.isEmpty && !$0.hasPrefix("#")}.count
        guard count>0,count<=5000 else{throw OmniPlatform.error("NO_MEDIA","HLS 分段数量不合法")}
        try FileManager.default.createDirectory(at:directory,withIntermediateDirectories:true)
        var output:[String]=[],index=0,resourceIndex=0,pendingRange:String?,offsets:[String:Int]=[:]
        func resource(_ relative:String,range:String?,name:String)async throws->String{
            guard !relative.contains("{$"),let source=URL(string:relative,relativeTo:url)?.absoluteURL,NativeMediaParser.safeURL(source.absoluteString) else{throw OmniPlatform.error("EXTRACTOR_CHANGED","HLS 资源地址无效")}
            var headers:[String:String]=[:]
            if let range=range{
                let values=range.split(separator:"@",maxSplits:1).compactMap{Int($0)}
                guard let length=values.first,length>0,length<=134_217_728 else{throw OmniPlatform.error("EXTRACTOR_CHANGED","HLS 区间无效")}
                let start=values.count==2 ? values[1]:(offsets[source.absoluteString] ?? 0)
                guard start>=0,start<=Int.max-length else{throw OmniPlatform.error("EXTRACTOR_CHANGED","HLS 区间溢出")}
                headers["Range"]="bytes=\(start)-\(start+length-1)";offsets[source.absoluteString]=start+length
            }
            let staged=try await BackgroundTransfer.shared.download(source,site:site,taskId:taskId,headers:headers,progress:{_ in})
            let destination=directory.appendingPathComponent(name)
            try FileManager.default.moveItem(at:staged,to:destination);return name
        }
        for line in input{
            try Task.checkCancellation()
            if line.hasPrefix("#EXT-X-KEY:"){
                let attrs=attributes(line),method=attrs["METHOD"] ?? ""
                guard ["NONE","AES-128"].contains(method),attrs["KEYFORMAT"]==nil || attrs["KEYFORMAT"]=="identity" else{throw OmniPlatform.error("DRM_UNSUPPORTED","受保护内容不能导出")}
                if method=="NONE"{output.append(line);continue}
                guard let path=attrs["URI"] else{throw OmniPlatform.error("EXTRACTOR_CHANGED","HLS 缺少密钥地址")}
                let file=try await resource(path,range:nil,name:"key-\(resourceIndex).bin");resourceIndex+=1
                let bytes=try directory.appendingPathComponent(file).resourceValues(forKeys:[.fileSizeKey]).fileSize ?? 0
                guard bytes==16 else{throw OmniPlatform.error("DRM_UNSUPPORTED","HLS 密钥格式无效")}
                output.append("#EXT-X-KEY:METHOD=AES-128,URI=\"\(file)\""+(attrs["IV"].map{",IV="+$0} ?? ""))
            }else if line.hasPrefix("#EXT-X-MAP:"){
                let attrs=attributes(line);guard let path=attrs["URI"] else{throw OmniPlatform.error("EXTRACTOR_CHANGED","HLS 缺少初始化片段")}
                let file=try await resource(path,range:attrs["BYTERANGE"],name:"init-\(resourceIndex).mp4");resourceIndex+=1;output.append("#EXT-X-MAP:URI=\"\(file)\"")
            }else if line.hasPrefix("#EXT-X-BYTERANGE:"){pendingRange=String(line.dropFirst(17))}
            else if !line.isEmpty && !line.hasPrefix("#"){
                let file=try await resource(line,range:pendingRange,name:String(format:"segment-%05d.bin",index));pendingRange=nil;index+=1;output.append(file);progress(Float(index)/Float(count))
            }else{output.append(line)}
        }
        let playlist=directory.appendingPathComponent("local.m3u8");try output.joined(separator:"\n").write(to:playlist,atomically:true,encoding:.utf8);return playlist
    }
}
