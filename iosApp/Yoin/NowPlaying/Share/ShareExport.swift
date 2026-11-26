import Photos
import SwiftUI
import UIKit
import YoinShared

/// The PNG behind Save and Share (Android recordForExport / saveImageToGallery / shareImageFile): the very
/// image the sheet previews, drawn by `ImageRenderer` at its logical size and a fixed scale — a Card 1440 px wide
/// (its height following the content), a Story 1080 × 1920 — opaque, square-cornered, its colour to every edge.
@MainActor
enum ShareExport {
    /// The render scale for [format]: the export width over the logical 360.
    static func scale(_ format: ShareFormat) -> CGFloat {
        let cards = YoinShareCards.shared
        let width = format == .story ? cards.storyExportWidthPx : cards.cardExportWidthPx
        return CGFloat(width) / ShareMetrics.cardWidth
    }

    /// The image's logical height: a Story's 640, a Card's own (laid out 360 wide).
    static func logicalHeight(_ image: ShareImage) -> CGFloat {
        guard image.format != .story else { return ShareMetrics.story.height }
        let host = UIHostingController(rootView: image)
        return host.sizeThatFits(in: CGSize(width: ShareMetrics.cardWidth, height: .greatestFiniteMagnitude)).height
    }

    /// The image as a bitmap at the export size; nil when it couldn't be drawn. [opaque] false only for tests:
    /// the image itself must cover every edge, not the renderer's opaque context.
    static func render(_ image: ShareImage, opaque: Bool = true) -> UIImage? {
        let height = logicalHeight(image)
        guard height > 0 else { return nil }
        let size = YoinShareCards.shared.exportSize(format: image.format, laidOutWidth: Float(ShareMetrics.cardWidth),
                                                    laidOutHeight: Float(height))
        let scale = scale(image.format)
        // Laid out exactly as tall as the export's whole pixels: no part-covered last row.
        let r = ImageRenderer(content: image.frame(width: ShareMetrics.cardWidth, height: CGFloat(size.height) / scale))
        r.scale = scale
        r.isOpaque = opaque
        r.proposedSize = ProposedViewSize(width: ShareMetrics.cardWidth, height: CGFloat(size.height) / scale)
        return r.uiImage
    }

    /// The PNG's bytes.
    static func png(_ image: ShareImage) -> Data? { render(image)?.pngData() }

    /// The PNG written to a temporary file named as Android names it ("Yoin - <title> - <ms>.png").
    static func file(_ image: ShareImage, title: String, now: Date = .now) -> URL? {
        guard let data = png(image) else { return nil }
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("yoin-share", isDirectory: true)
        try? FileManager.default.removeItem(at: dir)
        do {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            let name = YoinShareCards.shared.fileName(songTitle: title, nowMs: Int64(now.timeIntervalSince1970 * 1000))
            let url = dir.appendingPathComponent(name)
            try data.write(to: url)
            return url
        } catch {
            return nil
        }
    }

    /// Saves the PNG to the photo library (add-only access, which can't make a "Yoin" album: the library itself).
    static func save(_ image: ShareImage, title: String) async -> Bool {
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited, let data = png(image) else { return false }
        let name = YoinShareCards.shared.fileName(songTitle: title, nowMs: Int64(Date.now.timeIntervalSince1970 * 1000))
        return await withCheckedContinuation { done in
            PHPhotoLibrary.shared().performChanges({
                let options = PHAssetResourceCreationOptions()
                options.originalFilename = name
                PHAssetCreationRequest.forAsset().addResource(with: .photo, data: data, options: options)
            }, completionHandler: { ok, _ in done.resume(returning: ok) })
        }
    }

    /// The system share sheet on [file] with [caption] as the text for apps that take one; on iPad a popover
    /// pointing at [anchor] (window coordinates). [completion] says whether it was shared.
    static func present(file: URL, caption: String?, anchor: CGRect?, completion: @escaping (Bool) -> Void) -> Bool {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        guard let window = scenes.flatMap(\.windows).first(where: \.isKeyWindow) ?? scenes.first?.windows.first,
              var top = window.rootViewController else { return false }
        while let next = top.presentedViewController, !next.isBeingDismissed { top = next }
        var items: [Any] = [file]
        if let caption { items.append(caption) }
        let sheet = UIActivityViewController(activityItems: items, applicationActivities: nil)
        if let popover = sheet.popoverPresentationController {
            popover.sourceView = window
            popover.sourceRect = anchor ?? CGRect(x: window.bounds.midX, y: window.bounds.midY, width: 0, height: 0)
            if anchor == nil { popover.permittedArrowDirections = [] }
        }
        sheet.completionWithItemsHandler = { _, completed, _, _ in completion(completed) }
        top.present(sheet, animated: true)
        return true
    }
}
