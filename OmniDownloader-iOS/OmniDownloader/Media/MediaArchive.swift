import Foundation
import Compression

/// A bounded ZIP implementation: original bytes, CRC checks and no arbitrary paths.
enum MediaArchive {
    private static let crcTable:[UInt32]=(0..<256).map{value in var c=UInt32(value);for _ in 0..<8{c=(c&1)==1 ? 0xedb88320^(c>>1):c>>1};return c}
    static func crc(_ data:Data)->UInt32{var c:UInt32=0xffffffff;for b in data{c=crcTable[Int((c^UInt32(b))&255)]^(c>>8)};return c^0xffffffff}
    private static func integer(_ data:Data,_ offset:Int,_ bytes:Int)throws->UInt32{
        guard offset>=0,bytes>0,offset<=data.count-bytes else{throw OmniPlatform.error("POSTPROCESS_FAILED","ZIP 数据不完整")}
        return (0..<bytes).reduce(UInt32(0)){$0 | UInt32(data[offset+$1]) << UInt32($1*8)}
    }
    private static func append(_ value:UInt32,_ bytes:Int,to data:inout Data){for i in 0..<bytes{data.append(UInt8(truncatingIfNeeded:value>>UInt32(i*8)))}}
    static func pack(_ files:[URL],to destination:URL)throws{
        guard !files.isEmpty,files.count<=2000 else{throw OmniPlatform.error("POSTPROCESS_FAILED","ZIP 文件数量不合法")}
        FileManager.default.createFile(atPath:destination.path,contents:nil)
        let handle=try FileHandle(forWritingTo:destination);defer{try? handle.close()}
        var central=Data(),offset:UInt32=0
        for(index,file)in files.enumerated(){
            let size=try file.resourceValues(forKeys:[.fileSizeKey]).fileSize ?? 0
            guard size>0,size<=67_108_864 else{throw OmniPlatform.error("POSTPROCESS_FAILED","单张图片过大或为空")}
            let bytes=try Data(contentsOf:file),sum=crc(bytes)
            let name=Data(String(format:"%04d",index+1).appending("."+file.pathExtension).utf8)
            var local=Data();for(v,n)in [(UInt32(0x04034b50),4),(20,2),(0x800,2),(0,2),(0,2),(0,2),(sum,4),(UInt32(size),4),(UInt32(size),4),(UInt32(name.count),2),(0,2)]{append(v,n,to:&local)}
            local.append(name);try handle.write(contentsOf:local);try handle.write(contentsOf:bytes)
            for(v,n)in [(UInt32(0x02014b50),4),(20,2),(20,2),(0x800,2),(0,2),(0,2),(0,2),(sum,4),(UInt32(size),4),(UInt32(size),4),(UInt32(name.count),2),(0,2),(0,2),(0,2),(0,2),(0,4),(offset,4)]{append(v,n,to:&central)}
            central.append(name)
            let next=UInt64(offset)+UInt64(local.count)+UInt64(size)
            guard next<UInt32.max else{throw OmniPlatform.error("POSTPROCESS_FAILED","ZIP 超过当前归档上限")};offset=UInt32(next)
        }
        try handle.write(contentsOf:central)
        var end=Data();for(v,n)in [(UInt32(0x06054b50),4),(0,2),(0,2),(UInt32(files.count),2),(UInt32(files.count),2),(UInt32(central.count),4),(offset,4),(0,2)]{append(v,n,to:&end)}
        try handle.write(contentsOf:end)
    }
    static func unpackFrames(_ archive:URL,names:[String],to directory:URL)throws->[URL]{
        let size=try archive.resourceValues(forKeys:[.fileSizeKey]).fileSize ?? 0
        guard size>0,size<=268_435_456,!names.isEmpty,names.count<=2000,Set(names).count==names.count else{throw OmniPlatform.error("POSTPROCESS_FAILED","帧归档大小或数量不合法")}
        let data=try Data(contentsOf:archive),marker=Data([0x50,0x4b,0x05,0x06])
        guard let found=data.range(of:marker,options:.backwards,in:max(0,data.count-65557)..<data.count) else{throw OmniPlatform.error("POSTPROCESS_FAILED","不是完整 ZIP")}
        let count=Int(try integer(data,found.lowerBound+10,2));var offset=Int(try integer(data,found.lowerBound+16,4))
        guard count<=2000 else{throw OmniPlatform.error("POSTPROCESS_FAILED","帧归档数量过多")}
        var result:[String:URL]=[:],total=0
        try FileManager.default.createDirectory(at:directory,withIntermediateDirectories:true)
        for _ in 0..<count{
            guard try integer(data,offset,4)==0x02014b50 else{throw OmniPlatform.error("POSTPROCESS_FAILED","ZIP 目录损坏")}
            let flags=try integer(data,offset+8,2),method=try integer(data,offset+10,2),sum=try integer(data,offset+16,4)
            let compressed=Int(try integer(data,offset+20,4)),uncompressed=Int(try integer(data,offset+24,4)),nameSize=Int(try integer(data,offset+28,2))
            let extra=Int(try integer(data,offset+30,2)),comment=Int(try integer(data,offset+32,2)),local=Int(try integer(data,offset+42,4))
            guard nameSize<=256,offset+46+nameSize<=data.count,let name=String(data:data.subdata(in:offset+46..<offset+46+nameSize),encoding:.utf8),!name.contains(".."),!name.contains("\\"),!name.hasPrefix("/"),!name.contains(":"),flags&1==0 else{throw OmniPlatform.error("POSTPROCESS_FAILED","ZIP 包含不安全路径或加密内容")}
            offset+=46+nameSize+extra+comment
            guard let index=names.firstIndex(of:name) else{continue}
            guard result[name]==nil,uncompressed>0,uncompressed<=33_554_432,compressed>=0,total<=536_870_912-uncompressed,
                  try integer(data,local,4)==0x04034b50 else{throw OmniPlatform.error("POSTPROCESS_FAILED","帧归档异常")}
            let begin=local+30+Int(try integer(data,local+26,2))+Int(try integer(data,local+28,2))
            guard begin>=0,begin<=data.count-compressed else{throw OmniPlatform.error("POSTPROCESS_FAILED","ZIP 内容不完整")}
            let input=data.subdata(in:begin..<begin+compressed);var output:Data
            if method==0{output=input}
            else if method==8{
                output=Data(count:uncompressed)
                let decoded=output.withUnsafeMutableBytes{destination in input.withUnsafeBytes{source in
                    compression_decode_buffer(destination.bindMemory(to:UInt8.self).baseAddress!,uncompressed,source.bindMemory(to:UInt8.self).baseAddress!,compressed,nil,COMPRESSION_ZLIB)
                }}
                guard decoded==uncompressed else{throw OmniPlatform.error("POSTPROCESS_FAILED","帧解压失败")}
            }else{throw OmniPlatform.error("POSTPROCESS_FAILED","帧 ZIP 使用了不支持的压缩方式")}
            guard output.count==uncompressed,crc(output)==sum else{throw OmniPlatform.error("POSTPROCESS_FAILED","ZIP CRC 校验失败")}
            let ext=URL(fileURLWithPath:name).pathExtension.lowercased()
            guard ["jpg","jpeg","png","webp","gif"].contains(ext) else{throw OmniPlatform.error("POSTPROCESS_FAILED","帧文件格式异常")}
            let file=directory.appendingPathComponent(String(format:"frame-%04d",index)+"."+ext)
            try output.write(to:file,options:.atomic);result[name]=file;total+=uncompressed
        }
        guard names.allSatisfy({result[$0] != nil}) else{throw OmniPlatform.error("POSTPROCESS_FAILED","归档缺少目标帧")}
        return names.map{result[$0]!}
    }
}
