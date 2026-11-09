import Foundation
import Network

/// Loopback-only HTTP fixture exercising URLSession and sparse Range probing.
final class LocalMediaServer {
    private let listener:NWListener
    private let queue=DispatchQueue(label:"omni.tests.http")
    private let bytes:Data
    private let segment:Data
    private(set) var port:UInt16=0
    init(_ bytes:Data,segment:Data?=nil)throws{
        self.bytes=bytes
        self.segment=segment ?? bytes
        let parameters=NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host:"127.0.0.1",port:.any)
        listener=try NWListener(using:parameters)
    }
    func start()async throws{
        try await withCheckedThrowingContinuation{(continuation:CheckedContinuation<Void,Error>)in
            var finished=false
            listener.stateUpdateHandler={state in
                guard !finished else{return}
                if case .ready=state{finished=true;self.port=self.listener.port!.rawValue;continuation.resume()}
                else if case .failed(let error)=state{finished=true;continuation.resume(throwing:error)}
            }
            let content=bytes
            let segmentBytes=segment
            listener.newConnectionHandler={connection in
                connection.start(queue:self.queue)
                connection.receive(minimumIncompleteLength:1,maximumLength:8192){request,_,_,_ in
                    let text=String(decoding:request ?? Data(),as:UTF8.self)
                    if text.contains("?referer=required"),!text.lowercased().contains("referer: https://relay.example/"){
                        connection.send(content:Data("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".utf8),completion:.contentProcessed{_ in connection.cancel()});return
                    }
                    let payload=text.components(separatedBy:"\r\n").first?.contains("/segment.ts")==true ? segmentBytes:content
                    var data=payload,status="200 OK",range=""
                    let manifest=text.components(separatedBy:"\r\n").first?.contains("/fixture.m3u8") ?? false
                    if manifest{data=Data("#EXTM3U\n#EXT-X-VERSION:4\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:1.0,\n#EXT-X-BYTERANGE:\(segmentBytes.count)@0\nsegment.ts?referer=required\n#EXT-X-ENDLIST\n".utf8)}
                    if let line=text.components(separatedBy:"\r\n").first(where:{$0.lowercased().hasPrefix("range: bytes=")}){
                        let specification=line.components(separatedBy:"=").last ?? ""
                        let edges=specification.split(separator:"-",omittingEmptySubsequences:false)
                        let begin=Int(edges.first ?? "") ?? 0
                        let end=min(payload.count-1,Int(edges.count>1 ? edges[1]:"") ?? payload.count-1)
                        if begin>=0,begin<=end,end<payload.count{data=payload.subdata(in:begin..<(end+1));status="206 Partial Content";range="Content-Range: bytes \(begin)-\(end)/\(payload.count)\r\n"}
                    }
                    let header="HTTP/1.1 \(status)\r\nContent-Type: video/mp4\r\nContent-Length: \(data.count)\r\n\(range)Connection: close\r\n\r\n"
                    var response=Data(header.utf8);if !text.hasPrefix("HEAD "){response.append(data)}
                    connection.send(content:response,completion:.contentProcessed{_ in connection.cancel()})
                }
            }
            listener.start(queue:queue)
        }
    }
    var url:String{"http://127.0.0.1:\(port)/fixture.mp4"}
    func stop(){listener.stateUpdateHandler=nil;listener.newConnectionHandler=nil;listener.cancel()}
}
