import Foundation
import Photos
import UIKit

/// iOS 系统相册安全入库与分享服务
public class MediaSaver {
    
    public static let shared = MediaSaver()
    private let albumName = "OmniDownloader"
    
    /// 保存视频到系统相册《照片》
    public func saveVideoToAlbum(fileUrl: URL) async throws {
        try await requestPhotoPermission()
        
        let album = try await getOrCreateAlbum()
        
        try await PHPhotoLibrary.shared().performChanges {
            let createAssetRequest = PHAssetCreationRequest.forAsset()
            createAssetRequest.addResource(with: .video, fileURL: fileUrl, options: nil)
            guard let placeholder = createAssetRequest.placeholderForCreatedAsset,
                  let albumChangeRequest = PHAssetCollectionChangeRequest(for: album) else { return }
            albumChangeRequest.addAssets([placeholder] as NSArray)
        }
    }
    
    /// 保存图片到系统相册
    public func saveImageToAlbum(fileUrl: URL) async throws {
        try await requestPhotoPermission()
        let album = try await getOrCreateAlbum()
        
        try await PHPhotoLibrary.shared().performChanges {
            let createAssetRequest = PHAssetCreationRequest.forAsset()
            createAssetRequest.addResource(with: .photo, fileURL: fileUrl, options: nil)
            guard let placeholder = createAssetRequest.placeholderForCreatedAsset,
                  let albumChangeRequest = PHAssetCollectionChangeRequest(for: album) else { return }
            albumChangeRequest.addAssets([placeholder] as NSArray)
        }
    }
    
    /// 获取相册权限
    private func requestPhotoPermission() async throws {
        let status = PHPhotoLibrary.authorizationStatus(for: .addOnly)
        if status == .authorized || status == .limited {
            return
        }
        
        let newStatus = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        if newStatus != .authorized && newStatus != .limited {
            throw NSError(domain: "MediaSaver", code: 403, userInfo: [NSLocalizedDescriptionKey: "请在 iPhone【设置】中允许 OmniDownloader 访问您的《照片》以保存媒体文件"])
        }
    }
    
    /// 获取或创建专属相簿 "OmniDownloader"
    private func getOrCreateAlbum() async throws -> PHAssetCollection {
        let fetchOptions = PHFetchOptions()
        fetchOptions.predicate = NSPredicate(format: "title = %@", albumName)
        let collections = PHAssetCollection.fetchAssetCollections(with: .album, subtype: .any, options: fetchOptions)
        if let first = collections.firstObject {
            return first
        }
        
        var placeholder: PHObjectPlaceholder?
        try await PHPhotoLibrary.shared().performChanges {
            let req = PHAssetCollectionChangeRequest.creationRequestForAssetCollection(withTitle: self.albumName)
            placeholder = req.placeholderForCreatedAssetCollection
        }
        
        guard let p = placeholder,
              let created = PHAssetCollection.fetchAssetCollections(withLocalIdentifiers: [p.localIdentifier], options: nil).firstObject else {
            throw NSError(domain: "MediaSaver", code: 500, userInfo: [NSLocalizedDescriptionKey: "创建专属相簿失败"])
        }
        return created
    }
}
