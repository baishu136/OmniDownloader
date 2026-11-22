import Foundation

enum XPublicResponse {
    static func retryable(_ error:Error)->Bool {
        let value=error as NSError
        if value.domain == NSURLErrorDomain {
            return [NSURLErrorNetworkConnectionLost,NSURLErrorTimedOut,NSURLErrorCannotConnectToHost].contains(value.code)
        }
        return value.localizedDescription.contains("[MEDIA_UNCONFIRMED]")
    }
    static func confirmed(id:String,fetch:(Int)async throws->[String:Any])async throws->[String:Any] {
        for attempt in 0..<2 {
            do {
                try Task.checkCancellation()
                let item=try await fetch(attempt),identity=item["id_str"] as? String ?? ""
                guard !identity.isEmpty else{throw OmniPlatform.error("MEDIA_UNCONFIRMED","平台暂未确认目标帖子")}
                guard identity==id else{throw OmniPlatform.error("MEDIA_ID_MISMATCH","X 返回了其他帖子")}
                return item
            }catch {
                guard attempt==0,!(error is CancellationError),retryable(error) else{throw error}
                try await Task.sleep(nanoseconds:400_000_000)
            }
        }
        throw OmniPlatform.error("NETWORK_ERROR","X 公共媒体请求失败")
    }
}
