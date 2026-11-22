import XCTest
@testable import OmniDownloader

final class MobileParityTests:XCTestCase {
    @MainActor func testCancelledBatchCannotWriteIntoClearedRows()async throws {
        let file=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString+".json")
        defer{try? FileManager.default.removeItem(at:file)}
        let batch=BatchDownloads(file:file);batch.draft="https://example.com/a.mp4"
        var pending:CheckedContinuation<CapturedTarget,Error>?
        batch.start(manager:DownloadManager(),capture:{_ in try await withCheckedThrowingContinuation{pending=$0}})
        for _ in 0..<100{if pending != nil{break};await Task.yield()}
        guard let pending=pending else{batch.cancel();XCTFail("batch did not start");return}
        batch.cancel();batch.clear();pending.resume(throwing:URLError(.cancelled))
        for _ in 0..<5{await Task.yield()}
        XCTAssertTrue(batch.rows.isEmpty);XCTAssertFalse(batch.running)
    }
    func testSharingTextDeduplicatesAndKeepsSignedURL() {
        XCTAssertEqual(BatchInput.links("分享 https://example.com/a.mp4?a=b%2Fc， https://example.com/a.mp4?a=b%2Fc BV1xx411c7mD"),["https://example.com/a.mp4?a=b%2Fc","https://www.bilibili.com/video/BV1xx411c7mD"])
        XCTAssertTrue(BatchInput.links("javascript:alert(1)").isEmpty)
    }
    func testReleaseVersionRejectsDraftNamesAndComparesNumerically() {
        XCTAssertTrue(ReleaseVersion.newer("v1.7.100",than:"1.7.61"))
        XCTAssertFalse(ReleaseVersion.newer("v1.7.61-beta",than:"1.7.60"))
        XCTAssertFalse(ReleaseVersion.newer("1.7.61",than:"1.7.61"))
        XCTAssertFalse(ReleaseVersion.newer("1.7.9",than:"1.7.61"))
    }
    func testWeiboVerifiesIdentityAndPreservesActualDimensions()throws {
        let url="https://weibo.com/7827771738/N4xlMvjhI"
        let item:[String:Any]=["idstr":"4910815147462302","mblogid":"N4xlMvjhI","text_raw":"fixture","page_info":["media_info":["playback_list":[["play_info":["url":"https://example.com/a.mp4","width":1920,"height":1080]]]]]]
        let json=try WeiboExtractor.parse(item,url:url)
        let value=try XCTUnwrap(NativeMediaParser.parse(json,url:url).0)
        XCTAssertEqual(value.availableVideoFormats.first?.height,1080)
        XCTAssertThrowsError(try WeiboExtractor.parse(item,url:"https://m.weibo.cn/status/9999999999"))
        XCTAssertEqual(OmniPlatform.detect("https://weibo.com.evil.invalid/1/2"),"通用媒体")
        var mixed=item
        mixed["mix_media_info"]=["items":[["type":"video","data":["media_info":["stream_url":"https://example.com/a.mp4"]]]]]
        XCTAssertEqual((try WeiboExtractor.parse(mixed,url:url)["media"] as? [[String:Any]])?.count,1)
        XCTAssertThrowsError(try WeiboExtractor.parse([:],url:url)){XCTAssertTrue($0.localizedDescription.contains("MEDIA_UNCONFIRMED"))}
    }
    func testXRetryIsBoundedAndMismatchNeverRetries()async throws {
        let id="2124444023373074488";var count=0
        let item=try await XPublicResponse.confirmed(id:id){_ in count+=1;if count==1{throw URLError(.networkConnectionLost)};return ["id_str":id]}
        XCTAssertEqual(item["id_str"] as? String,id);XCTAssertEqual(count,2)
        count=0
        do{_ = try await XPublicResponse.confirmed(id:id){_ in count+=1;return ["id_str":"9999999999999"]};XCTFail("mismatch accepted")}catch{XCTAssertTrue(error.localizedDescription.contains("MEDIA_ID_MISMATCH"))}
        XCTAssertEqual(count,1)
        count=0
        do{_ = try await XPublicResponse.confirmed(id:id){_ in count+=1;throw URLError(.serverCertificateUntrusted)};XCTFail("certificate accepted")}catch{}
        XCTAssertEqual(count,1)
    }
    func testXIdentityNeverRoundsSnowflakeOrAcceptsQuotedTweet()throws {
        let json=try JSONSerialization.jsonObject(with:Data(#"{"id_str":null,"id":2124444023373074488}"#.utf8)) as! [String:Any]
        XCTAssertEqual(XPublicResponse.identity(json),"2124444023373074488")
        XCTAssertEqual(XPublicResponse.identity(["id":2.1244440233730744e18]),"")
        XCTAssertEqual(XPublicResponse.identity(["quoted_tweet":["id_str":"2124444023373074488"]]),"")
        XCTAssertFalse(XPublicResponse.retryable(URLError(.cancelled)))
        let eof=NSError(domain:NSURLErrorDomain,code:NSURLErrorSecureConnectionFailed,userInfo:["_kCFStreamErrorCodeKey":-9806])
        XCTAssertTrue(XPublicResponse.retryable(eof))
        XCTAssertFalse(XPublicResponse.retryable(URLError(.serverCertificateUntrusted)))
    }
    @MainActor func testBatchImagesAndQualityUseExistingTaskModel()throws {
        let file=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString+".json")
        defer{try? FileManager.default.removeItem(at:file)}
        let batch=BatchDownloads(file:file)
        batch.draft="https://example.com/a.mp4";batch.prepare();batch.save()
        XCTAssertEqual(BatchDownloads(file:file).rows.count,1)
        let post=ImagePost(url:"https://m.weibo.cn/status/123456789",platform:"微博",contentId:"123456789",title:"photos",resources:[PostImageResource(id:"1",url:"https://example.com/a.jpg")])
        let tasks=try batch.makeTasks(.images(post),group:"group")
        XCTAssertEqual(tasks[0].downloadType,.imageZip);XCTAssertEqual(tasks[0].selectedImageIds,["1"])
        batch.mode = .audioOnly
        XCTAssertThrowsError(try batch.makeTasks(.images(post),group:"group"))
        batch.mode = .videoWithAudio;batch.maxHeight=720
        let value=VideoMetadata(url:"https://example.com/a.mp4",title:"fixture",availableVideoFormats:[FormatOption(formatId:"1080",resolutionLabel:"1080p",width:1920,height:1080),FormatOption(formatId:"720",resolutionLabel:"720p",width:1280,height:720)])
        XCTAssertEqual(try batch.makeTasks(.video(value),group:"group")[0].selectedVideoFormatId,"720")
        let unknown=VideoMetadata(url:value.url,title:"unknown",availableVideoFormats:[FormatOption(formatId:"unknown",resolutionLabel:"unknown",height:1080)])
        XCTAssertThrowsError(try batch.makeTasks(.video(unknown),group:"group"))
        let covered=DownloadManager.includingCovers([DownloadTask(url:"https://example.com/a.mp4",title:"fixture",saveCover:true)])
        XCTAssertEqual(covered.count,2);XCTAssertEqual(covered[1].downloadType,.cover)
        XCTAssertEqual(covered[0].saveCover,false)
        XCTAssertEqual(DownloadManager.includingCovers(covered).count,2)
    }
}
