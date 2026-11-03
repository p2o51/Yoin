import SwiftUI
import UIKit

/// Port of Android detail/AlbumSpectrum.kt: the cover's colors as 28 flat
/// strips for the album page's top bar when the track list is pulled up.
///
/// The cover is rendered at 28×28 and converted to OKLab, then clustered
/// with farthest-point seeds and k-means (k = 5, 10 rounds).
/// - Kept clusters: share > 1 %; under 5 % only when distinct (> 0.03).
/// - A single color is split into L ± 0.12.
/// - Bands are ordered by hue, with neutrals (C < 0.04) first, dark → light.
/// - Band widths follow the share, at least 2 strips each.
/// - Tone lock: L' = 0.30 + 0.16 L, chroma × 1.15, stepped down until white
///   text is ≥ 7:1 on it.
enum AlbumSpectrum {
    static let strips = 28

    private struct Lab { var l, a, b: Double }

    @MainActor private static var cache: [MediaId: [Color]] = [:]

    @MainActor
    static func colors(for album: Album) -> [Color] {
        if let c = cache[album.id] { return c }
        let renderer = ImageRenderer(content: CoverArt(palette: album.palette, cornerRadius: 0, seed: album.seed)
            .frame(width: 28, height: 28))
        renderer.scale = 1
        guard let cg = renderer.cgImage, let labs = pixels(cg), !labs.isEmpty else {
            return Array(repeating: album.palette.a, count: strips)
        }
        let result = build(labs)
        cache[album.id] = result
        return result
    }

    private static func pixels(_ cg: CGImage) -> [Lab]? {
        let w = 28, h = 28
        var buf = [UInt8](repeating: 0, count: w * h * 4)
        guard let ctx = CGContext(data: &buf, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                  space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
        return stride(from: 0, to: buf.count, by: 4).map {
            toLab(Double(buf[$0]) / 255, Double(buf[$0 + 1]) / 255, Double(buf[$0 + 2]) / 255)
        }
    }

    private static func lin(_ c: Double) -> Double { c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4) }
    private static func gam(_ c: Double) -> Double {
        let v = max(0, min(1, c))
        return v <= 0.0031308 ? 12.92 * v : 1.055 * pow(v, 1 / 2.4) - 0.055
    }

    private static func toLab(_ r: Double, _ g: Double, _ b: Double) -> Lab {
        let (lr, lg, lb) = (lin(r), lin(g), lin(b))
        let l = cbrt(0.4122214708 * lr + 0.5363325363 * lg + 0.0514459929 * lb)
        let m = cbrt(0.2119034982 * lr + 0.6806995451 * lg + 0.1073969566 * lb)
        let s = cbrt(0.0883024619 * lr + 0.2817188376 * lg + 0.6299787005 * lb)
        return Lab(l: 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
                   a: 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
                   b: 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s)
    }

    private static func toRGB(_ c: Lab) -> (Double, Double, Double) {
        let l = pow(c.l + 0.3963377774 * c.a + 0.2158037573 * c.b, 3)
        let m = pow(c.l - 0.1055613458 * c.a - 0.0638541728 * c.b, 3)
        let s = pow(c.l - 0.0894841775 * c.a - 1.2914855480 * c.b, 3)
        return (4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
                -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
                -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s)
    }

    private static func dist(_ x: Lab, _ y: Lab) -> Double {
        let (dl, da, db) = (x.l - y.l, x.a - y.a, x.b - y.b)
        return (dl * dl + da * da + db * db).squareRoot()
    }

    private static func build(_ px: [Lab]) -> [Color] {
        // Farthest-point seeds, then k-means.
        var centers = [px[px.count / 2]]
        while centers.count < 5 {
            let next = px.max { a, b in
                centers.map { dist($0, a) }.min()! < centers.map { dist($0, b) }.min()!
            }!
            centers.append(next)
        }
        var assign = [Int](repeating: 0, count: px.count)
        for _ in 0..<10 {
            for (i, p) in px.enumerated() {
                assign[i] = centers.indices.min { dist(centers[$0], p) < dist(centers[$1], p) }!
            }
            for k in centers.indices {
                let members = px.indices.filter { assign[$0] == k }.map { px[$0] }
                guard !members.isEmpty else { continue }
                let n = Double(members.count)
                centers[k] = Lab(l: members.map(\.l).reduce(0, +) / n,
                                 a: members.map(\.a).reduce(0, +) / n,
                                 b: members.map(\.b).reduce(0, +) / n)
            }
        }
        var clusters: [(Lab, Double)] = centers.indices.map { k in
            (centers[k], Double(assign.filter { $0 == k }.count) / Double(px.count))
        }
        .filter { $0.1 > 0.01 }
        .sorted { $0.1 > $1.1 }
        var kept: [(Lab, Double)] = []
        for c in clusters where c.1 >= 0.05 || kept.allSatisfy({ dist($0.0, c.0) > 0.03 }) {
            kept.append(c)
        }
        clusters = kept
        if clusters.count == 1 {
            let c = clusters[0].0
            clusters = [(Lab(l: c.l - 0.12, a: c.a, b: c.b), 0.5), (Lab(l: c.l + 0.12, a: c.a, b: c.b), 0.5)]
        }
        // Neutrals first (dark → light), then by hue.
        clusters.sort { x, y in
            let cx = (x.0.a * x.0.a + x.0.b * x.0.b).squareRoot()
            let cy = (y.0.a * y.0.a + y.0.b * y.0.b).squareRoot()
            if (cx < 0.04) != (cy < 0.04) { return cx < 0.04 }
            if cx < 0.04 { return x.0.l < y.0.l }
            return atan2(x.0.b, x.0.a) < atan2(y.0.b, y.0.a)
        }
        // Widths ∝ share, min 2 strips each.
        let total = clusters.map(\.1).reduce(0, +)
        var widths = clusters.map { max(2, Int(($0.1 / total * Double(strips)).rounded())) }
        while widths.reduce(0, +) > strips, let i = widths.indices.max(by: { widths[$0] < widths[$1] }) { widths[i] -= 1 }
        while widths.reduce(0, +) < strips, let i = widths.indices.max(by: { widths[$0] < widths[$1] }) { widths[i] += 1 }
        var out: [Color] = []
        for (c, w) in zip(clusters.map(\.0), widths) {
            out += Array(repeating: locked(c), count: w)
        }
        return out
    }

    /// Tone lock so white text stays ≥ 7:1.
    private static func locked(_ c: Lab) -> Color {
        var l = 0.30 + 0.16 * c.l
        var a = c.a * 1.15, b = c.b * 1.15
        func rgb() -> (Double, Double, Double) { toRGB(Lab(l: l, a: a, b: b)) }
        var (r, g, bl) = rgb()
        while [r, g, bl].contains(where: { $0 < 0 || $0 > 1 }), abs(a) + abs(b) > 0.001 {
            a *= 0.9; b *= 0.9; (r, g, bl) = rgb()
        }
        func lum() -> Double {
            0.2126 * max(0, min(1, r)) + 0.7152 * max(0, min(1, g)) + 0.0722 * max(0, min(1, bl))
        }
        while 1.05 / (lum() + 0.05) < 7, l > 0.05 {
            l -= 0.005; (r, g, bl) = rgb()
        }
        return Color(red: gam(r), green: gam(g), blue: gam(bl))
    }
}
