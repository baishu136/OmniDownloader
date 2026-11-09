import Foundation
import Network

/// Loopback-only HTTP fixture exercising URLSession and sparse Range probing.
final class LocalMediaServer {
    private let listener:NWListener
    private let queue=DispatchQueue(label:"omni.tests.http")
    private let bytes:Data
    private(set) var port:UInt16=0
    init(_ bytes:Data)throws{
        self.bytes=bytes
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
            listener.newConnectionHandler={connection in
                connection.start(queue:self.queue)
                connection.receive(minimumIncompleteLength:1,maximumLength:8192){request,_,_,_ in
                    let text=String(decoding:request ?? Data(),as:UTF8.self)
                    var data=content,status="200 OK",range=""
                    if let line=text.components(separatedBy:"\r\n").first(where:{$0.lowercased().hasPrefix("range: bytes=")}){
                        let specification=line.components(separatedBy:"=").last ?? ""
                        let edges=specification.split(separator:"-",omittingEmptySubsequences:false)
                        let begin=Int(edges.first ?? "") ?? 0
                        let end=min(content.count-1,Int(edges.count>1 ? edges[1]:"") ?? content.count-1)
                        if begin>=0,begin<=end,end<content.count{data=content.subdata(in:begin..<(end+1));status="206 Partial Content";range="Content-Range: bytes \(begin)-\(end)/\(content.count)\r\n"}
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
