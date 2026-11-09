import XCTest
import ImageIO
@testable import OmniDownloader

final class NativeMediaTests:XCTestCase {
    func testStrictTargetAndUnknownDimensions()throws{
        let url="https://www.douyin.com/video/1234567890123456789"
        let media:[String:Any]=["mediaId":"1234567890123456789","formats":[["url":"https://example.com/source.mp4"]]]
        XCTAssertThrowsError(try NativeMediaParser.parse(["contentId":"999","media":[media]],url:url))
        let value=try XCTUnwrap(NativeMediaParser.parse(["contentId":"1234567890123456789","media":[media]],url:url).0)
        XCTAssertEqual(value.availableVideoFormats[0].width,0)
        XCTAssertEqual(value.availableVideoFormats[0].height,0)
        XCTAssertTrue(value.availableVideoFormats[0].resolutionLabel.contains("未知"))
    }
    func testMultiVideoKeepsActualMediaIdentity()throws{
        let url="https://x.com/example/status/1234567890123456789"
        let rows:[[String:Any]]=["111","222"].map{["mediaId":$0,"formats":[["url":"https://video.twimg.com/ext_tw_video/\($0)/source.mp4","width":1920,"height":1080]] ]}
        let value=try XCTUnwrap(NativeMediaParser.parse(["contentId":"1234567890123456789","media":rows],url:url).0)
        XCTAssertEqual(value.multiMediaList.map{$0.mediaId},["111","222"])
    }
    func testQualityKeepsHDRAndFrameRate(){
        let low=FormatOption(formatId:"720",resolutionLabel:"720p",width:1280,height:720,fps:60)
        let high=FormatOption(formatId:"4k",resolutionLabel:"4K",width:3840,height:2160,fps:30,vcodec:"hvc1",dynamicRange:"HDR10")
        XCTAssertEqual(MediaQuality.sorted([low,high]).map{$0.formatId},["4k","720"])
        XCTAssertEqual(MediaQuality.sorted([high])[0].dynamicRange,"HDR10")
    }
    func testZIPOriginalBytesAndCRC()throws{
        let root=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at:root,withIntermediateDirectories:true)
        defer{try? FileManager.default.removeItem(at:root)}
        let bytes=Data("original-image-bytes".utf8),file=root.appendingPathComponent("original.png")
        try bytes.write(to:file)
        let archive=root.appendingPathComponent("images.zip")
        try MediaArchive.pack([file],to:archive)
        let output=try MediaArchive.unpackFrames(archive,names:["0001.png"],to:root.appendingPathComponent("unpacked"))
        XCTAssertEqual(try Data(contentsOf:output[0]),bytes)
        XCTAssertEqual(MediaArchive.crc(Data("123456789".utf8)),0xcbf43926)
        XCTAssertThrowsError(try MediaArchive.unpackFrames(archive,names:["../escape.png"],to:root.appendingPathComponent("bad")))
    }
    func testCompressedUgoiraFrames()throws{
        let archive=try XCTUnwrap(Bundle(for:Self.self).url(forResource:"ugoira-deflated",withExtension:"bin"))
        let root=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer{try? FileManager.default.removeItem(at:root)}
        let frames=try MediaArchive.unpackFrames(archive,names:["0.png","1.png","2.png"],to:root)
        XCTAssertEqual(frames.count,3)
        for frame in frames{XCTAssertNotNil(CGImageSourceCreateWithURL(frame as CFURL,nil))}
    }
    @MainActor func testRealVideoGIFAndAudioCodecs()async throws{
        let root=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at:root,withIntermediateDirectories:true)
        defer{try? FileManager.default.removeItem(at:root)}
        let source=root.appendingPathComponent("source.mp4")
        try await NativeFFmpeg.run(["-f","lavfi","-i","testsrc2=size=320x180:rate=12","-f","lavfi","-i","sine=frequency=440:sample_rate=44100","-t","1","-c:v","libx264","-pix_fmt","yuv420p","-c:a","aac",source.path])
        let original=try await NativeFFmpeg.probe(source)
        XCTAssertEqual(original.width,320);XCTAssertEqual(original.height,180);XCTAssertTrue(original.audio)
        let gif=root.appendingPathComponent("animation.gif")
        try await NativeFFmpeg.gif(source,output:gif)
        let image=try XCTUnwrap(CGImageSourceCreateWithURL(gif as CFURL,nil))
        XCTAssertGreaterThan(CGImageSourceGetCount(image),1)
        try await NativeFFmpeg.verify(gif,task:DownloadTask(url:"https://example.com/video",title:"GIF",downloadType:.gif))
        for format in [AudioFormat.mp3,.m4a,.flac,.opus]{
            let audio=root.appendingPathComponent("audio."+format.rawValue)
            try await NativeFFmpeg.audio(source,output:audio,format:format)
            let probe=try await NativeFFmpeg.probe(audio);XCTAssertTrue(probe.audio)
        }
        let mute=root.appendingPathComponent("mute.mp4")
        try await NativeFFmpeg.run(["-i",source.path,"-map","0:v:0","-c:v","copy","-an",mute.path])
        let silent=try await NativeFFmpeg.probe(mute);XCTAssertFalse(silent.audio);XCTAssertEqual(silent.width,320)
    }
}
