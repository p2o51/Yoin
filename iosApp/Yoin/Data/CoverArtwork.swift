import CryptoKit
import ImageIO
import SwiftUI
import os

/// A server cover image. `key` names the image without the auth query
/// (`t`, `s`), so the disk cache survives a new sign-in's fresh salt.
struct CoverRef: Hashable, Sendable {
    var url: URL
    var key: String

    init(url: URL) {
        self.url = url
        let c = URLComponents(url: url, resolvingAgainstBaseURL: false)
        let keep = Set(["id", "size"])
        let query = (c?.queryItems ?? []).filter { keep.contains($0.name) }
            .sorted { $0.name < $1.name }.map { "\($0.name)=\($0.value ?? "")" }.joined(separator: "&")
        key = "\(c?.host ?? ""):\(c?.port.map(String.init) ?? "")\(c?.path ?? "")?\(query)"
    }
}

/// Real cover images: memory (per-cover observable slots) and disk
/// (Caches/YoinCovers), and the colors read off each one with the same
/// Palette pass Android runs (`CoverSeed`). A view that draws a cover asks
/// for it (`load`); everything that reads the cover's colors redraws, on the
/// effects spring, when the image lands.
final class CoverArtwork: @unchecked Sendable {
    static let shared = CoverArtwork()

    /// The three palette colors of a loaded cover, and its Palette seed.
    struct Colors: Hashable, Sendable {
        var a: Color
        var b: Color
        var c: Color
        var seedArgb: Int
    }

    @Observable
    final class Slot: @unchecked Sendable {
        var image: PlatformImage?
        var colors: Colors?
        @ObservationIgnored var lastUse = Date.distantPast
    }

    private let slots = OSAllocatedUnfairLock<[String: Slot]>(initialState: [:])
    @MainActor private var tasks: [String: Task<Void, Never>] = [:]
    /// Decoded images kept in memory; older ones drop their image (the colors stay).
    private static let memoryLimit = 160
    private static let log = Logger(subsystem: "com.gpo.yoin", category: "covers")

    func slot(_ ref: CoverRef) -> Slot {
        slots.withLock { all in
            if let s = all[ref.key] { return s }
            let s = Slot()
            all[ref.key] = s
            return s
        }
    }

    /// The cover's colors once loaded (tracked: readers redraw when they land).
    func colors(_ ref: CoverRef) -> Colors? { slot(ref).colors }

    /// The cover's image once loaded (tracked).
    func image(_ ref: CoverRef) -> PlatformImage? { slot(ref).image }

    /// Loads the image (disk, else network) and its colors; no-op when it is in memory.
    @MainActor
    func load(_ ref: CoverRef) async {
        let slot = slot(ref)
        slot.lastUse = .now
        if slot.image != nil { return }
        if let running = tasks[ref.key] { return await running.value }
        let task = Task { @MainActor in
            defer { tasks[ref.key] = nil }
            guard let (image, colors) = await Self.fetch(ref) else { return }
            withAnimation(Yoin.effects) {
                slot.image = image
                slot.colors = colors
            }
            trimMemory()
        }
        tasks[ref.key] = task
        await task.value
    }

    @MainActor
    private func trimMemory() {
        let loaded = slots.withLock { $0.values.filter { $0.image != nil } }
        guard loaded.count > Self.memoryLimit else { return }
        for s in loaded.sorted(by: { $0.lastUse < $1.lastUse }).prefix(loaded.count - Self.memoryLimit) {
            s.image = nil
        }
    }

    // MARK: Fetch + decode (off the main thread)

    private static let directory: URL = {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("YoinCovers", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()

    static func file(for key: String) -> URL {
        let hash = SHA256.hash(data: Data(key.utf8)).map { String(format: "%02x", $0) }.joined()
        return directory.appendingPathComponent(hash)
    }

    private static func fetch(_ ref: CoverRef) async -> (PlatformImage, Colors?)? {
        let file = file(for: ref.key)
        var data = try? Data(contentsOf: file)
        if data == nil {
            do {
                let (body, response) = try await URLSession.shared.data(from: ref.url)
                guard (response as? HTTPURLResponse)?.statusCode ?? 200 == 200 else { return nil }
                data = body
            } catch {
                log.info("cover fetch failed: \(error.localizedDescription, privacy: .public)")
                return nil
            }
        }
        guard let data, let image = decode(data, maxPixel: 640) else { return nil }
        if !FileManager.default.fileExists(atPath: file.path) { try? data.write(to: file, options: .atomic) }
        return (image, colors(of: image))
    }

    /// Downsampled decode (ImageIO thumbnail, orientation applied).
    static func decode(_ data: Data, maxPixel: Int) -> PlatformImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixel,
        ]
        guard let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        return PlatformImage(cgImage: cg)
    }

    /// The image drawn at Android's 200 px, through `CoverSeed.swatches`.
    static func colors(of image: PlatformImage) -> Colors? {
        guard let cg = image.cgImage else { return nil }
        let side = CoverSeed.bitmapSizePx
        guard let space = CGColorSpace(name: CGColorSpace.sRGB),
              let ctx = CGContext(data: nil, width: side, height: side, bitsPerComponent: 8, bytesPerRow: side * 4,
                                  space: space,
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
                                      | CGBitmapInfo.byteOrder32Big.rawValue) else { return nil }
        ctx.interpolationQuality = .medium
        // Aspect fill, like the square cover views.
        let w = CGFloat(cg.width), h = CGFloat(cg.height), s = CGFloat(side)
        let scale = max(s / w, s / h)
        ctx.draw(cg, in: CGRect(x: (s - w * scale) / 2, y: (s - h * scale) / 2, width: w * scale, height: h * scale))
        guard let small = ctx.makeImage(), let (pixels, pw, ph) = CoverSeed.argbPixels(of: small),
              let sw = CoverSeed.swatches(pixels: pixels, width: pw, height: ph) else { return nil }
        let seed = CoverScheme.color(sw.seed)
        return Colors(a: seed,
                      b: sw.light.map(CoverScheme.color) ?? seed.mix(with: .white, by: 0.55, in: .perceptual),
                      c: sw.dark.map(CoverScheme.color) ?? seed.mix(with: .black, by: 0.55, in: .perceptual),
                      seedArgb: sw.seed)
    }
}

/// A server cover: the image once loaded, a flat placeholder in the cover's
/// (placeholder) color until then. Bare: no border.
struct RemoteCover: View {
    var ref: CoverRef
    var placeholder: Color

    var body: some View {
        let image = CoverArtwork.shared.image(ref)
        // The image fills the frame it is given (an overlay never grows it).
        Rectangle().fill(placeholder)
            .overlay {
                if let image {
                    Image(platformImage: image)
                        .resizable()
                        .scaledToFill()
                        .transition(.opacity)
                }
            }
            .clipped()
        .task(id: ref.key) { await CoverArtwork.shared.load(ref) }
    }
}
