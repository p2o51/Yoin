import SwiftUI
import UIKit
import YoinShared

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

    /// One strip of the cover: the bar slot it flies to and the locked color it turns into.
    struct Strip {
        let slot: Int
        let color: Color
    }

    @MainActor private static var cache: [MediaId: [Strip]] = [:]

    /// For each source column of the cover (left to right), its slot and color.
    @MainActor private static var coverCache: [String: [Strip]] = [:]

    @MainActor
    static func strips(for album: Album) -> [Strip] {
        if let cover = album.palette.cover {
            // A server cover: its image once loaded (tracked), flat until then.
            if let c = coverCache[cover.key] { return c }
            guard let cg = CoverArtwork.shared.image(cover)?.cgImage, let result = strips(of: cg) else {
                return (0..<strips).map { Strip(slot: $0, color: album.palette.a) }
            }
            coverCache[cover.key] = result
            return result
        }
        if let c = cache[album.id] { return c }
        let renderer = ImageRenderer(content: CoverArt(palette: album.palette, cornerRadius: 0, seed: album.seed)
            .frame(width: CGFloat(strips), height: CGFloat(strips)))
        renderer.scale = 1
        guard let cg = renderer.cgImage, let result = strips(of: cg) else {
            return (0..<strips).map { Strip(slot: $0, color: album.palette.a) }
        }
        cache[album.id] = result
        return result
    }

    private static func strips(of cg: CGImage) -> [Strip]? {
        guard let pixels = argbPixels(cg) else { return nil }
        let bands = AlbumSpectrumKt.computeAlbumSpectrum(pixels: pixels, n: Int32(strips))
        return (0..<strips).map { x in
            Strip(slot: Int(bands.target.get(index: Int32(x))),
                  color: CoverScheme.color(Int(UInt32(bitPattern: bands.color.get(index: Int32(x))))))
        }
    }

    /// The 28 × 28 render as opaque ARGB ints (Android `Bitmap.getPixels` layout).
    private static func argbPixels(_ cg: CGImage) -> KotlinIntArray? {
        let w = strips, h = strips
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
