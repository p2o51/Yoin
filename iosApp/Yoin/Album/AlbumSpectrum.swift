import SwiftUI
import UIKit
import YoinShared

@MainActor
@Observable
private final class AlbumSpectrumSlot {
    var entry: AlbumSpectrum.Entry?
}

/// The album spectrum (Android detail/AlbumSpectrum.kt): the cover's colors as
/// 28 strips for the album page's top bar when the track list is pulled up.
///
/// The clustering and the tone lock are the shared Kotlin
/// (`computeAlbumSpectrum`, YoinShared): OKLab farthest-point seeds and
/// k-means (k = 5, 10 rounds), band widths by share (at least 2 strips), each
/// source column assigned to the nearest free slot, tone lock L' = 0.30 +
/// 0.16 L with white text ≥ 7:1. iOS renders the cover at 28 × 28 and hands
/// over its ARGB pixels, as Android scales its 256 px decode to 28 × 28.
enum AlbumSpectrum {
    static let strips = Int(Tokens.detail.SPECTRUM_STRIPS)
    private static let cacheLimit = 128

    /// One strip of the cover: the bar slot it flies to and the locked color it turns into.
    struct Strip: Sendable {
        let slot: Int
        let color: Color
    }

    /// Cached spectrum strips paired with precomputed landing thresholds (`bandLandings`).
    struct Entry: Sendable {
        let strips: [Strip]
        let bandLandings: [CGFloat]
        let isFallback: Bool
    }

    /// Single shared fallback array while a server cover image is still loading.
    private static let fallbackStrips: [Strip] = (0..<strips).map { Strip(slot: $0, color: .clear) }
    private static let fallbackLandings: [CGFloat] = [0.6]
    static let fallbackEntry = Entry(strips: fallbackStrips, bandLandings: fallbackLandings, isFallback: true)

    @MainActor private static var cache: [MediaId: Entry] = [:]
    @MainActor private static var cacheOrder: [MediaId] = []

    /// For each source column of the cover (left to right), its slot and color.
    @MainActor private static var coverCache: [String: Entry] = [:]
    @MainActor private static var coverOrder: [String] = []

    @MainActor private static var slots: [String: AlbumSpectrumSlot] = [:]
    @MainActor private static var inFlight: [String: Task<Void, Never>] = [:]

    @MainActor
    static func strips(for album: Album) -> [Strip] {
        entry(for: album, allowSyncMock: true).strips
    }

    @MainActor
    static func entry(for album: Album, allowSyncMock: Bool = false) -> Entry {
        if let cover = album.palette.cover {
            if let hit = coverCache[cover.key] { return hit }
            let slot = self.slot(for: cover.key)
            if let ready = slot.entry { return ready }
            if let cg = CoverArtwork.shared.image(cover)?.cgImage {
                scheduleCoverExtraction(key: cover.key, cgImage: cg)
            }
            return fallbackEntry
        }
        if let hit = cache[album.id] { return hit }
        let key = mockKey(for: album.id)
        let slot = self.slot(for: key)
        if let ready = slot.entry { return ready }
        if allowSyncMock, let cg = renderMockCGImage(for: album), let built = extractEntry(from: cg, count: strips) {
            storeMock(built, for: album.id, key: key)
            return built
        }
        scheduleMockExtraction(for: album)
        return fallbackEntry
    }

    @MainActor
    static func prepare(for album: Album) async {
        if let cover = album.palette.cover {
            if coverCache[cover.key] != nil { return }
            if CoverArtwork.shared.image(cover) == nil {
                await CoverArtwork.shared.load(cover)
            }
            guard coverCache[cover.key] == nil,
                  let cg = CoverArtwork.shared.image(cover)?.cgImage else { return }
            scheduleCoverExtraction(key: cover.key, cgImage: cg)
            await inFlight[cover.key]?.value
            return
        }
        if cache[album.id] != nil { return }
        scheduleMockExtraction(for: album)
        await inFlight[mockKey(for: album.id)]?.value
    }

    @MainActor
    private static func mockKey(for id: MediaId) -> String { "mock:\(id.provider):\(id.rawId)" }

    @MainActor
    private static func slot(for key: String) -> AlbumSpectrumSlot {
        if let existing = slots[key] { return existing }
        let s = AlbumSpectrumSlot()
        slots[key] = s
        return s
    }

    @MainActor
    private static func scheduleCoverExtraction(key: String, cgImage: CGImage) {
        guard inFlight[key] == nil else { return }
        let count = strips
        let task = Task { @MainActor in
            defer { inFlight.removeValue(forKey: key) }
            let built = await Task.detached(priority: .userInitiated) {
                extractEntry(from: cgImage, count: count)
            }.value
            guard let built else { return }
            storeCover(built, for: key)
        }
        inFlight[key] = task
    }

    @MainActor
    private static func scheduleMockExtraction(for album: Album) {
        let key = mockKey(for: album.id)
        guard inFlight[key] == nil, cache[album.id] == nil else { return }
        let count = strips
        let task = Task { @MainActor in
            defer { inFlight.removeValue(forKey: key) }
            guard cache[album.id] == nil, let cg = renderMockCGImage(for: album) else { return }
            let built = await Task.detached(priority: .userInitiated) {
                extractEntry(from: cg, count: count)
            }.value
            guard let built else { return }
            storeMock(built, for: album.id, key: key)
        }
        inFlight[key] = task
    }

    @MainActor
    private static func renderMockCGImage(for album: Album) -> CGImage? {
        let renderer = ImageRenderer(content: CoverArt(palette: album.palette, cornerRadius: 0, seed: album.seed)
            .frame(width: CGFloat(strips), height: CGFloat(strips)))
        renderer.scale = 1
        return renderer.cgImage
    }

    @MainActor
    private static func storeCover(_ entry: Entry, for key: String) {
        if coverCache[key] == nil {
            coverOrder.append(key)
            if coverOrder.count > cacheLimit {
                let evicted = coverOrder.removeFirst()
                coverCache.removeValue(forKey: evicted)
                slots.removeValue(forKey: evicted)
            }
        }
        coverCache[key] = entry
        slot(for: key).entry = entry
    }

    @MainActor
    private static func storeMock(_ entry: Entry, for id: MediaId, key: String) {
        if cache[id] == nil {
            cacheOrder.append(id)
            if cacheOrder.count > cacheLimit {
                let evicted = cacheOrder.removeFirst()
                cache.removeValue(forKey: evicted)
                slots.removeValue(forKey: mockKey(for: evicted))
            }
        }
        cache[id] = entry
        slot(for: key).entry = entry
    }

    private nonisolated static func extractEntry(from cg: CGImage, count: Int) -> Entry? {
        guard let pixels = argbPixels(cg, count: count) else { return nil }
        let bands = AlbumSpectrumKt.computeAlbumSpectrum(pixels: pixels, n: Int32(count))
        var raw: [(slot: Int, argb: Int)] = []
        raw.reserveCapacity(count)
        var result: [Strip] = []
        result.reserveCapacity(count)
        for x in 0..<count {
            let slot = Int(bands.target.get(index: Int32(x)))
            let argb = Int(UInt32(bitPattern: bands.color.get(index: Int32(x))))
            raw.append((slot: slot, argb: argb))
            result.append(Strip(slot: slot, color: CoverScheme.color(argb)))
        }
        let bySlot = raw.sorted { $0.slot < $1.slot }
        var landings: [CGFloat] = []
        var lastArgb: Int?
        let nf = CGFloat(count)
        for st in bySlot where st.argb != lastArgb {
            lastArgb = st.argb
            landings.append(0.6 + 0.22 * CGFloat(st.slot) / nf)
        }
        return Entry(strips: result, bandLandings: landings, isFallback: false)
    }

    /// The 28 × 28 render as opaque ARGB ints (Android `Bitmap.getPixels` layout).
    private nonisolated static func argbPixels(_ cg: CGImage, count: Int) -> KotlinIntArray? {
        let w = count, h = count
        var buf = [UInt8](repeating: 0, count: w * h * 4)
        guard let ctx = CGContext(data: &buf, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                  space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
        let out = KotlinIntArray(size: Int32(w * h))
        for i in 0..<(w * h) {
            let argb = 0xFF00_0000 | (UInt32(buf[i * 4]) << 16) | (UInt32(buf[i * 4 + 1]) << 8) | UInt32(buf[i * 4 + 2])
            out.set(index: Int32(i), value: Int32(bitPattern: argb))
        }
        return out
    }
}
