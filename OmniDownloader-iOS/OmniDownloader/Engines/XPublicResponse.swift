import Foundation

enum XPublicResponse {
    static func identity(_ item:[String:Any])->String {
        if let value=item["id_str"] as? String,!value.isEmpty{return value}
        if let value=item["id"] as? String{return value}
        if let value=item["id"] as? NSNumber,["q","Q","i","I","l","L"].contains(String(cString:value.objCType)){return value.stringValue}
        return ""
    }
    static func retryable(_ error:Error)->Bool {
        let value=error as NSError
        if value.domain == NSURLErrorDomain {
            if value.code == NSURLErrorSecureConnectionFailed {
                // Only explicit TLS transport EOFs; never retry trust/certificate errors.
                let codes = [value.userInfo["_kCFStreamErrorCodeKey"] as? Int,
                             (value.userInfo[NSUnderlyingErrorKey] as? NSError)?.userInfo["_kCFStreamErrorCodeKey"] as? Int]
                return codes.compactMap{$0}.contains{[-9806,-9816].contains($0)}
            }
            return [NSURLErrorNetworkConnectionLost,NSURLErrorTimedOut,NSURLErrorCannotConnectToHost].contains(value.code)
        }
        return value.localizedDescription.contains("[MEDIA_UNCONFIRMED]")
    }
    static func confirmed(id:String,fetch:(Int)async throws->[String:Any])async throws->[String:Any] {
        for attempt in 0..<2 {
            do {
                try Task.checkCancellation()
                let item=try await fetch(attempt),actual=Self.identity(item)
                guard !actual.isEmpty else{throw OmniPlatform.error("MEDIA_UNCONFIRMED","平台暂未确认目标帖子")}
                guard actual==id else{throw OmniPlatform.error("MEDIA_ID_MISMATCH","X 返回了其他帖子")}
                return item
            }catch {
                guard attempt==0,!(error is CancellationError),retryable(error) else{throw error}
                try await Task.sleep(nanoseconds:400_000_000)
            }
        }
        throw OmniPlatform.error("NETWORK_ERROR","X 公共媒体请求失败")
    }
}
