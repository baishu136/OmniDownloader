import Foundation
import Combine

enum BatchInput {
    static func links(_ text:String)->[String] {
        let pattern = #"https?://[^\s<>\"“”]+|\bBV[a-zA-Z0-9]{10}\b"#
        guard let regex = try? NSRegularExpression(pattern:pattern,options:.caseInsensitive) else{return []}
        var seen=Set<String>()
        return regex.matches(in:text,range:NSRange(text.startIndex...,in:text)).compactMap { match -> String? in
            guard let range=Range(match.range,in:text) else{return nil}
            var value=String(text[range]).trimmingCharacters(in:CharacterSet(charactersIn:"，。；、）)]}"))
            if value.hasPrefix("BV"){value="https://www.bilibili.com/video/"+value}
            guard NativeMediaParser.safeURL(value),seen.insert(value).inserted else{return nil}
            return value
        }
    }
}

struct BatchEntry:Identifiable,Codable {
    var id=UUID().uuidString
    let url:String
    var state="等待"
    var message=""
    var count=0
}

@MainActor final class BatchDownloads:ObservableObject {
    static let shared=BatchDownloads()
    @Published var draft=""
    @Published private(set) var rows:[BatchEntry]=[]
    @Published private(set) var running=false
    @Published var notice=""
    @Published var mode=DownloadType.auto
    @Published var audioFormat=AudioFormat.mp3
    @Published var maxHeight=0
    @Published var allEpisodes=true
    @Published var saveCover=false
    private var operation:Task<Void,Never>?
    private var epoch=UUID()
    private let file:URL
    private struct Snapshot:Codable {let draft:String;let rows:[BatchEntry]}
    init(file:URL?=nil) {
        self.file=file ?? FileManager.default.urls(for:.applicationSupportDirectory,in:.userDomainMask)[0].appendingPathComponent("omni-batch.json")
        if let data=try? Data(contentsOf:self.file),let saved=try? JSONDecoder().decode(Snapshot.self,from:data){
            draft=saved.draft;rows=saved.rows.map{var row=$0;if row.state=="解析中"{row.state="等待"};return row}
        }
    }
    func save() {
        do {
            try FileManager.default.createDirectory(at:file.deletingLastPathComponent(),withIntermediateDirectories:true)
            let data=try JSONEncoder().encode(Snapshot(draft:draft,rows:rows))
            try data.write(to:file,options:[.atomic,.completeFileProtectionUntilFirstUserAuthentication])
        }catch{notice="批量草稿保存失败"}
    }
    func prepare() {
        guard !running else{return}
        let urls=BatchInput.links(draft)
        guard urls.count<=100 else{notice="单次最多 100 条链接，请拆分后导入";return}
        let old=Dictionary(rows.map{($0.url,$0)},uniquingKeysWith:{first,_ in first})
        rows=urls.map{old[$0] ?? BatchEntry(url:$0)};notice=urls.isEmpty ? "没有找到可用链接":"已整理 \(rows.count) 条链接";save()
    }
    func clear(){guard !running else{return};draft="";rows=[];notice="";save()}
    func cancel(){epoch=UUID();operation?.cancel();operation=nil;running=false;for i in rows.indices where rows[i].state=="解析中"{rows[i].state="等待"};save()}
    func start(manager:DownloadManager,capture:@escaping(String)async throws->CapturedTarget,retry:Bool=false) {
        guard !running else{return}
        if !retry{prepare()}
        guard !rows.isEmpty else{return}
        let pending=rows.filter{retry ? $0.state=="失败":$0.state != "已加入"}.map(\.id)
        let scope=UUID();epoch=scope;running=true;notice=""
        operation=Task {
            defer{if epoch==scope{running=false;save()}}
            for id in pending {
                guard !Task.isCancelled,epoch==scope,let index=rows.firstIndex(where:{$0.id==id}) else{return}
                if manager.tasks.contains(where:{$0.collectionId==id}){rows[index].state="已加入";continue}
                rows[index].state="解析中";rows[index].message="";save()
                do {
                    let result=try await capture(rows[index].url)
                    try Task.checkCancellation();guard epoch==scope else{return}
                    let tasks=try makeTasks(result,group:id)
                    manager.addTasks(tasks);rows[index].count=tasks.count;rows[index].state="已加入"
                }catch is CancellationError{if epoch==scope{rows[index].state="等待"};return}
                catch{rows[index].state="失败";rows[index].message=error.localizedDescription}
                save()
            }
        }
    }
    func makeTasks(_ result:CapturedTarget,group:String)throws->[DownloadTask] {
        switch result {
        case .images(let post):
            guard mode == .auto || mode == .imageZip else{throw OmniPlatform.error("NO_MEDIA","目标为图文，请选择自动识别或图文 ZIP")}
            guard !post.resources.isEmpty || post.frameArchive != nil else{throw OmniPlatform.error("NO_MEDIA","没有可用图片")}
            MediaSources.shared.remember(post)
            return [DownloadTask(url:post.url,title:post.title,author:post.author,thumbnailUrl:post.resources.first?.url ?? "",downloadType:.imageZip,collectionId:group,collectionTitle:post.title,episodeIndex:1,episodeTotal:1,mediaId:post.contentId,imagePlatform:post.platform,selectedImageIds:post.resources.map(\.id))]
        case .video(let metadata):
            guard mode != .imageZip else{throw OmniPlatform.error("NO_MEDIA","目标不是图文")}
            let values=metadata.multiMediaList.isEmpty || !allEpisodes ? [metadata]:metadata.multiMediaList
            guard values.count<=100 else{throw OmniPlatform.error("BATCH_LIMIT","单条链接最多 100 个媒体，请在首页选择范围")}
            let output=try values.enumerated().map { index,value -> DownloadTask in
                let kind=mode == .auto ? DownloadType.videoWithAudio:mode
                let formats=MediaQuality.sorted(value.availableVideoFormats.filter{!$0.isAudioOnly && (maxHeight==0 || ($0.height>0 && min($0.width,$0.height)<=maxHeight))})
                let selected=kind == .audioOnly ? value.availableAudioFormats.first ?? formats.first:formats.first
                guard selected != nil || kind == .cover || (kind == .gif && value.isGif) else{throw OmniPlatform.error("FORMAT_NOT_AVAILABLE","没有符合所选画质的媒体源")}
                return DownloadTask(url:value.url,title:value.title,author:value.author,thumbnailUrl:value.thumbnailUrl,downloadType:kind,selectedResolution:selected?.resolutionLabel ?? "原始",audioFormat:audioFormat,collectionId:group,collectionTitle:metadata.title,episodeIndex:index+1,episodeTotal:values.count,selectedVideoFormatId:selected?.formatId,mediaId:value.mediaId,expectedDurationMs:value.expectedDurationMs,selectedWidth:selected?.width,selectedHeight:selected?.height,saveCover:saveCover,selectedFPS:selected?.fps,selectedDynamicRange:selected?.dynamicRange,selectedCodec:selected?.vcodec)
            }
            MediaSources.shared.remember(metadata);return output
        }
    }
}
