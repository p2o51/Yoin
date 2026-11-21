import CoreImage
import CryptoKit
import ImageIO
import SwiftUI
import UIKit
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
        var lightArgb: Int?
        var darkArgb: Int?
        var bArgb: Int

        init(seedArgb: Int, lightArgb: Int?, darkArgb: Int?) {
            let seed = CoverScheme.color(seedArgb)
            let bColor = lightArgb.map(CoverScheme.color) ?? seed.mix(with: .white, by: 0.55, in: .perceptual)
            let cColor = darkArgb.map(CoverScheme.color) ?? seed.mix(with: .black, by: 0.55, in: .perceptual)
            self.a = seed
            self.b = bColor
            self.c = cColor
            self.seedArgb = seedArgb
            self.lightArgb = lightArgb
            self.darkArgb = darkArgb
            self.bArgb = lightArgb ?? CoverScheme.argb(of: bColor)
        }
    }

    @Observable
    final class Slot: @unchecked Sendable {
        var image: UIImage?
        var cardImage: UIImage?
        var colors: Colors?
        @ObservationIgnored var lastUse = Date.distantPast
    }

    private struct FetchResult: @unchecked Sendable {
        let image: UIImage
        let cardImage: UIImage?
        let colors: Colors?
        let fromDisk: Bool
    }

    private let slots = OSAllocatedUnfairLock<[String: Slot]>(initialState: [:])
    @MainActor private var tasks: [String: Task<Void, Never>] = [:]
    /// Decoded images kept in memory; older ones drop their image (the colors stay).
    private static let memoryLimit = 160
    private static let cardImageSize = 48
    private static let cardBlurSigma: Double = 8
    private static let ciContext = CIContext(options: [.cacheIntermediates: false])
    private static let log = Logger(subsystem: "com.gpo.yoin", category: "covers")

    func slot(_ ref: CoverRef) -> Slot {
        if let existing = slots.withLock({ $0[ref.key] }) {
            return existing
        }
        // Read the tiny 14-byte persisted color sidecar outside the lock so
        // previously seen covers paint with their true album colors on Frame 1
        // without holding the unfair lock across disk I/O.
        let sidecar = Self.file(for: ref.key).appendingPathExtension("colors")
        let persisted = (try? Data(contentsOf: sidecar)).flatMap(Self.decodeColors)
        return slots.withLock { all in
            if let existing = all[ref.key] { return existing }
            let s = Slot()
            s.colors = persisted
            all[ref.key] = s
            return s
        }
    }

    /// The cover's colors once loaded (tracked: readers redraw when they land).
    func colors(_ ref: CoverRef) -> Colors? { slot(ref).colors }

    /// The cover's image once loaded (tracked).
    func image(_ ref: CoverRef) -> UIImage? { slot(ref).image }

    /// Pre-blurred tiny cover for Home cards once loaded (tracked).
    func cardImage(_ ref: CoverRef) -> UIImage? { slot(ref).cardImage }

    /// Loads the image (disk, else network) and its colors; no-op when it is in memory.
    @MainActor
    func load(_ ref: CoverRef) async {
        let slot = slot(ref)
        slot.lastUse = .now
        if slot.image != nil && slot.cardImage != nil { return }
        if let running = tasks[ref.key] { return await running.value }
        let task = Task { @MainActor in
            defer { tasks[ref.key] = nil }
            guard let result = await Self.fetch(ref) else { return }
            let hadColors = slot.colors != nil
            let applyImage = {
                slot.image = result.image
                slot.cardImage = result.cardImage
            }
            let applyColors = {
                if slot.colors != result.colors {
                    slot.colors = result.colors
                }
            }
            if result.fromDisk {
                applyImage()
                if hadColors || result.colors == nil {
                    applyColors()
                } else {
                    // Legacy cached image without a .colors sidecar yet: glide
                    // the newly extracted color in rather than snapping abruptly.
                    withAnimation(Yoin.effects) { applyColors() }
                }
            } else {
                withAnimation(Yoin.effects) {
                    applyImage()
                    applyColors()
                }
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
            s.cardImage = nil
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

    private static func fetch(_ ref: CoverRef) async -> FetchResult? {
        let file = file(for: ref.key)
        let sidecar = file.appendingPathExtension("colors")
        if let disk = await Task.detached(priority: .userInitiated, operation: { () -> FetchResult? in
            guard let data = try? Data(contentsOf: file),
                  let image = decode(data, maxPixel: 640) else { return nil }
            let card = makeCardImage(from: image)
            var loadedColors = (try? Data(contentsOf: sidecar)).flatMap(decodeColors)
            if loadedColors == nil, let computed = colors(of: image) {
                loadedColors = computed
                try? encodeColors(computed).write(to: sidecar, options: .atomic)
            }
            return FetchResult(image: image, cardImage: card, colors: loadedColors, fromDisk: true)
        }).value {
            return disk
        }

        let body: Data
        do {
            let (data, response) = try await URLSession.shared.data(from: ref.url)
            guard (response as? HTTPURLResponse)?.statusCode ?? 200 == 200 else { return nil }
            body = data
        } catch {
            log.info("cover fetch failed: \(error.localizedDescription, privacy: .public)")
            return nil
        }

        return await Task.detached(priority: .userInitiated) {
            guard let image = decode(body, maxPixel: 640) else { return nil }
            let card = makeCardImage(from: image)
            let computed = colors(of: image)
            try? body.write(to: file, options: .atomic)
            if let computed {
                try? encodeColors(computed).write(to: sidecar, options: .atomic)
            }
            return FetchResult(image: image, cardImage: card, colors: computed, fromDisk: false)
        }.value
    }

    /// Downsampled decode (ImageIO thumbnail, orientation applied).
    static func decode(_ data: Data, maxPixel: Int) -> UIImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixel,
        ]
        guard let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        return UIImage(cgImage: cg)
    }

    /// Pre-blurred 48x48 cover texture for Home cards, clamped so edges stay solid.
    private static func makeCardImage(from image: UIImage) -> UIImage? {
        guard let cg = image.cgImage else { return nil }
        let side = cardImageSize
        guard let space = CGColorSpace(name: CGColorSpace.sRGB),
              let ctx = CGContext(
                  data: nil, width: side, height: side, bitsPerComponent: 8, bytesPerRow: side * 4,
                  space: space,
                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue
              ) else { return nil }
        ctx.interpolationQuality = .medium
        let w = CGFloat(cg.width), h = CGFloat(cg.height), s = CGFloat(side)
        let scale = max(s / w, s / h)
        ctx.draw(cg, in: CGRect(x: (s - w * scale) / 2, y: (s - h * scale) / 2, width: w * scale, height: h * scale))
        guard let small = ctx.makeImage() else { return nil }
        let extent = CGRect(x: 0, y: 0, width: side, height: side)
        let blurred = CIImage(cgImage: small)
            .clampedToExtent()
            .applyingGaussianBlur(sigma: cardBlurSigma)
            .cropped(to: extent)
        if let out = ciContext.createCGImage(blurred, from: extent, format: .RGBA8, colorSpace: space) {
            return UIImage(cgImage: out)
        }
        return UIImage(cgImage: small)
    }

    static func encodeColors(_ colors: Colors) -> Data {
        var data = Data(capacity: 14)
        data.append(0x59)
        var flags: UInt8 = 0
        if colors.lightArgb != nil { flags |= 0x01 }
        if colors.darkArgb != nil { flags |= 0x02 }
        data.append(flags)
        func appendU32(_ v: Int) {
            var le = UInt32(truncatingIfNeeded: v).littleEndian
            withUnsafeBytes(of: &le) { data.append(contentsOf: $0) }
        }
        appendU32(colors.seedArgb)
        appendU32(colors.lightArgb ?? 0)
        appendU32(colors.darkArgb ?? 0)
        return data
    }

    static func decodeColors(_ data: Data) -> Colors? {
        guard data.count == 14, data[0] == 0x59 else { return nil }
        let flags = data[1]
        func readU32(at offset: Int) -> Int {
            let b0 = UInt32(data[offset])
            let b1 = UInt32(data[offset + 1]) << 8
            let b2 = UInt32(data[offset + 2]) << 16
            let b3 = UInt32(data[offset + 3]) << 24
            return Int(b0 | b1 | b2 | b3)
        }
        let seed = readU32(at: 2)
        let light: Int? = (flags & 0x01) != 0 ? readU32(at: 6) : nil
        let dark: Int? = (flags & 0x02) != 0 ? readU32(at: 10) : nil
        return Colors(seedArgb: seed, lightArgb: light, darkArgb: dark)
    }

    /// The image drawn once at Android's 200 px directly into an RGBA buffer, through `CoverSeed.swatches`.
    static func colors(of image: UIImage) -> Colors? {
        guard let cg = image.cgImage else { return nil }
        let side = CoverSeed.bitmapSizePx
        guard let space = CGColorSpace(name: CGColorSpace.sRGB) else { return nil }
        var rgba = [UInt8](repeating: 0, count: side * side * 4)
        let drawn: Bool = rgba.withUnsafeMutableBytes { buffer in
            guard let ctx = CGContext(
                data: buffer.baseAddress, width: side, height: side, bitsPerComponent: 8, bytesPerRow: side * 4,
                space: space,
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue
            ) else { return false }
            ctx.interpolationQuality = .medium
            // Aspect fill, like the square cover views.
            let w = CGFloat(cg.width), h = CGFloat(cg.height), s = CGFloat(side)
            let scale = max(s / w, s / h)
            ctx.draw(cg, in: CGRect(x: (s - w * scale) / 2, y: (s - h * scale) / 2, width: w * scale, height: h * scale))
            return true
        }
        guard drawn else { return nil }
        var pixels = [UInt32](repeating: 0, count: side * side)
        for i in 0..<(side * side) {
            var r = UInt32(rgba[i * 4]), g = UInt32(rgba[i * 4 + 1]), b = UInt32(rgba[i * 4 + 2])
            let a = UInt32(rgba[i * 4 + 3])
            if a > 0 && a < 255 {
                r = min(255, (r * 255 + a / 2) / a)
                g = min(255, (g * 255 + a / 2) / a)
                b = min(255, (b * 255 + a / 2) / a)
            }
            pixels[i] = (a << 24) | (r << 16) | (g << 8) | b
        }
        guard let sw = CoverSeed.swatches(pixels: pixels, width: side, height: side) else { return nil }
        return Colors(seedArgb: sw.seed, lightArgb: sw.light, darkArgb: sw.dark)
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
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                        .transition(.opacity)
                }
            }
            .clipped()
        .task(id: ref.key) { await CoverArtwork.shared.load(ref) }
    }
}

/// Nothing is drawn over Spotify artwork (51's rule, after Spotify's design guidelines): a stamp sits
/// beside or under a Spotify cover, a gap clear of it; other providers' covers keep it on the corner.
enum CoverMarks {
    static func onCover(provider: String) -> Bool { provider != SpotifyMapping.provider }
    /// The clear gap between a Spotify cover and the stamp beside it (past the cover's tilt, too).
    static let gap: CGFloat = 14
}

extension CoverRef {
    /// Served by Spotify's image hosts (i.scdn.co, mosaic.scdn.co, *.spotifycdn.com).
    var isSpotify: Bool {
        guard let host = url.host?.lowercased() else { return false }
        return host.hasSuffix("scdn.co") || host.hasSuffix("spotifycdn.com")
    }
}
