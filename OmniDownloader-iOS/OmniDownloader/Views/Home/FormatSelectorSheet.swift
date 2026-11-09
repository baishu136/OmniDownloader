import SwiftUI

@MainActor struct FormatSelectorSheet:View{
    let metadata:VideoMetadata
    var onMoreQuality:(()->Void)?=nil
    @EnvironmentObject private var manager:DownloadManager
    @Environment(\.presentationMode) private var presentation
    @AppStorage("omni.language") private var language="system"
    @State private var type:DownloadType = .videoWithAudio
    @State private var audio:AudioFormat = .mp3
    @State private var selectedFormat=""
    @State private var selectedEpisodes=Set<String>()
    @State private var saveCover=false
    @State private var collection=false
    private func L(_ text:String)->String{OmniLocalization.text(text,language:language)}
    private var episodes:[VideoMetadata]{metadata.multiMediaList.isEmpty ? [metadata]:metadata.multiMediaList}
    private func identity(_ item:VideoMetadata)->String{item.mediaId ?? item.url}
    var body:some View{
        VStack(spacing:0){
            Capsule().fill(OmniPalette.secondary.opacity(0.35)).frame(width:36,height:5).padding(.vertical,12)
            ScrollView{
                VStack(alignment:.leading,spacing:24){
                    HStack(alignment:.top,spacing:14){OmniRemoteImage(url:metadata.thumbnailUrl,site:metadata.siteName).frame(width:118,height:78).clipShape(RoundedRectangle(cornerRadius:12));VStack(alignment:.leading,spacing:6){Text(metadata.title).font(.headline).lineLimit(3);HStack{Text(metadata.siteName).font(.caption.bold()).padding(.horizontal,8).padding(.vertical,3).foregroundColor(OmniPalette.onPrimary).background(OmniPalette.primary,in:RoundedRectangle(cornerRadius:5));if metadata.isGif{Text(L("GIF 动图")).font(.caption.bold()).padding(.horizontal,8).padding(.vertical,3).background(Color.purple.opacity(0.3),in:RoundedRectangle(cornerRadius:5))};Text(metadata.author).font(.caption).foregroundColor(OmniPalette.secondary).lineLimit(1)}}}
                    Divider()
                    if episodes.count>1{
                        Toggle(L("批量下载合集")+" (\(episodes.count))",isOn:$collection)
                        if collection{
                            HStack{Button(L("全选")){selectedEpisodes=Set(episodes.map(identity))};Spacer();Button(L("反选")){selectedEpisodes=Set(episodes.map(identity)).subtracting(selectedEpisodes)}}
                            LazyVStack(spacing:4){ForEach(Array(episodes.enumerated()),id:\.offset){index,item in
                                Button{let id=identity(item);if !selectedEpisodes.insert(id).inserted{selectedEpisodes.remove(id)}}label:{HStack{Image(systemName:selectedEpisodes.contains(identity(item)) ? "checkmark.circle.fill":"circle");Text("\(index+1). "+item.title).lineLimit(2);Spacer()}.font(.subheadline).padding(.vertical,10).contentShape(Rectangle())}.buttonStyle(OmniPressStyle())
                            }}
                        }
                    }
                    HStack{Text(L("下载模式")).font(.title3.bold());Spacer();Toggle(L("保存封面"),isOn:$saveCover).font(.subheadline).fixedSize()}
                    LazyVGrid(columns:[GridItem(.flexible()),GridItem(.flexible()),GridItem(.flexible())],spacing:8){
                        mode(.videoWithAudio,L("音画合流"),"video");mode(.videoOnly,L("纯画面"),"speaker.slash")
                        if !metadata.isGif{mode(.audioOnly,L("提取音频"),"music.note")};mode(.gif,L("动态图"),"gif")
                    }
                    if type == .audioOnly{
                        VStack(alignment:.leading,spacing:10){Text(L("音频格式")).font(.headline);ForEach([AudioFormat.mp3,.m4a,.flac,.opus],id:\.rawValue){format in Button{audio=format}label:{HStack{Image(systemName:audio==format ? "largecircle.fill.circle":"circle");Text(format.rawValue.uppercased());Spacer()}.padding(14).background(audio==format ? OmniPalette.primaryContainer:OmniPalette.surfaceVariant.opacity(0.25),in:RoundedRectangle(cornerRadius:16))}.buttonStyle(OmniPressStyle())}}
                    }else if type != .gif{
                        VStack(alignment:.leading,spacing:10){Text(L("视频画质")).font(.headline)
                            Button{selectedFormat=""}label:{qualityRow(L("自适应最高画质 (推荐)"),detail:"",selected:selectedFormat.isEmpty)}.buttonStyle(OmniPressStyle())
                            ForEach(metadata.availableVideoFormats){format in Button{selectedFormat=format.formatId}label:{qualityRow(format.resolutionLabel,detail:[format.fps>0 ? "\(format.fps)fps":"",format.vcodec ?? "",format.dynamicRange ?? "",format.approximateSize].filter{!$0.isEmpty}.joined(separator:" · "),selected:selectedFormat==format.formatId)}.buttonStyle(OmniPressStyle())}
                        }
                    }
                    if let evidence=metadata.qualityEvidence,!evidence.isEmpty{Text(evidence).font(.caption).foregroundColor(OmniPalette.secondary)}
                    if let warning=metadata.qualityWarning,!warning.isEmpty{Text(warning).font(.caption).foregroundColor(OmniPalette.error)}
                    if metadata.siteName=="抖音",let more=onMoreQuality{Button(L("更多画质 / 浏览器验证"),action:more).buttonStyle(OmniPressStyle())}
                }.padding(22)
            }
            VStack(spacing:0){Divider();OmniAction(title:L("开始下载")+(type == .gif ? " (GIF)":type == .audioOnly ? " (\(audio.rawValue.uppercased()))":""),icon:"arrow.down.to.line",prominent:true,disabled:collection && selectedEpisodes.isEmpty,action:submit).padding(18)}.background(OmniPalette.surface)
        }.background(OmniPalette.surface).foregroundColor(OmniPalette.onSurface).tint(OmniPalette.primary)
        .onAppear{type=metadata.isGif ? .gif:.videoWithAudio;selectedEpisodes=Set(episodes.map(identity))}
    }
    private func mode(_ value:DownloadType,_ title:String,_ icon:String)->some View{
        Button{type=value}label:{VStack(spacing:4){OmniIcon(name:icon,size:20);Text(title).font(.caption.weight(type==value ? .bold:.medium)).lineLimit(1).minimumScaleFactor(0.8)}.frame(maxWidth:.infinity,minHeight:62).foregroundColor(type==value ? OmniPalette.primary:OmniPalette.onSurface).padding(.horizontal,2).background(type==value ? OmniPalette.primaryContainer.opacity(0.4):OmniPalette.surfaceVariant.opacity(0.3),in:RoundedRectangle(cornerRadius:10)).overlay(RoundedRectangle(cornerRadius:10).strokeBorder(type==value ? OmniPalette.primary:OmniPalette.outline,lineWidth:type==value ? 2:1))}.buttonStyle(OmniPressStyle(cornerRadius:10)).accessibilityAddTraits(type==value ? .isSelected:[])
    }
    private func qualityRow(_ title:String,detail:String,selected:Bool)->some View{HStack(spacing:10){Image(systemName:selected ? "largecircle.fill.circle":"circle");VStack(alignment:.leading,spacing:3){Text(title).font(.subheadline.weight(.medium));if !detail.isEmpty{Text(detail).font(.caption).foregroundColor(OmniPalette.secondary)}};Spacer()}.padding(13).background(selected ? OmniPalette.primaryContainer.opacity(0.6):OmniPalette.surfaceVariant.opacity(0.25),in:RoundedRectangle(cornerRadius:15))}
    private func submit(){
        let rows=collection ? episodes.filter{selectedEpisodes.contains(identity($0))}:[metadata]
        let collectionId=rows.count>1 ? UUID().uuidString:nil
        let tasks=rows.enumerated().map{index,row->DownloadTask in
            let intended=metadata.availableVideoFormats.first{$0.formatId==selectedFormat}
            let format=row.availableVideoFormats.first{$0.formatId==selectedFormat} ?? (intended == nil ? row.availableVideoFormats.first:nil)
            let quality=format ?? intended
            return DownloadTask(url:row.url,title:row.title,author:row.author,thumbnailUrl:row.thumbnailUrl,downloadType:type,selectedResolution:quality?.resolutionLabel ?? "best",audioFormat:audio,collectionId:collectionId,collectionTitle:collectionId==nil ? nil:metadata.title,episodeIndex:index+1,episodeTotal:rows.count,selectedVideoFormatId:format?.formatId,mediaId:row.mediaId,expectedDurationMs:format?.sourceDurationMs ?? row.expectedDurationMs,selectedWidth:quality?.width,selectedHeight:quality?.height,saveCover:saveCover,selectedFPS:quality?.fps,selectedDynamicRange:quality?.dynamicRange,selectedCodec:quality?.vcodec)
        }
        manager.addTasks(tasks);presentation.wrappedValue.dismiss()
    }
}
