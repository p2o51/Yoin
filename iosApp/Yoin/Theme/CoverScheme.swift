import SwiftUI
import UIKit
import YoinShared
import os

/// The color roles Android derives from a cover, with Android's algorithm.
///
/// Android: CoverSeedExtractor (Palette, vibrant → dominant → muted) picks a
/// seed; ExpressiveColorSchemeFactory builds an MCU `SchemeExpressive`
/// (SPEC_2025, contrast 0, phone) from it. Both run here from the shared
/// Kotlin (YoinShared: the Palette port and MCU in :shared), so a seed yields
/// the same ARGB roles as on Android (golden-tested in YoinTests).
struct CoverScheme: Sendable {
    var primary: Color
    var onPrimary: Color
    var primaryContainer: Color
    var onPrimaryContainer: Color
    var inversePrimary: Color
    var secondary: Color
    var onSecondary: Color
    var secondaryContainer: Color
    var onSecondaryContainer: Color
    var tertiary: Color
    var onTertiary: Color
    var tertiaryContainer: Color
    var onTertiaryContainer: Color
    var background: Color
    var onBackground: Color
    var surface: Color
    var onSurface: Color
    var surfaceVariant: Color
    var onSurfaceVariant: Color
    var surfaceTint: Color
    var inverseSurface: Color
    var inverseOnSurface: Color
    var error: Color
    var onError: Color
    var errorContainer: Color
    var onErrorContainer: Color
    var outline: Color
    var outlineVariant: Color
    var scrim: Color
    var surfaceBright: Color
    var surfaceDim: Color
    var surfaceContainer: Color
    var surfaceContainerHigh: Color
    var surfaceContainerHighest: Color
    var surfaceContainerLow: Color
    var surfaceContainerLowest: Color

    /// Builds the scheme from a seed color (seed → MCU SchemeExpressive SPEC_2025).
    init(seed: Color, dark: Bool) {
        self.init(seedArgb: CoverScheme.argb(of: seed), dark: dark)
    }

    /// Builds the scheme from an album's cover: the seed is read off the
    /// rendered cover the way Android reads it (see `CoverSeed`).
    @MainActor
    init(album: Album, dark: Bool) {
        self.init(seedArgb: CoverSeed.seedArgb(for: album), dark: dark)
    }

    init(seedArgb: Int, dark: Bool) {
        let roles = CoverScheme.cachedRoles(seedArgb: seedArgb, dark: dark)
        func c(_ name: String) -> Color { CoverScheme.color(roles[name]!) }
        primary = c("primary")
        onPrimary = c("onPrimary")
        primaryContainer = c("primaryContainer")
        onPrimaryContainer = c("onPrimaryContainer")
        inversePrimary = c("inversePrimary")
        secondary = c("secondary")
        onSecondary = c("onSecondary")
        secondaryContainer = c("secondaryContainer")
        onSecondaryContainer = c("onSecondaryContainer")
        tertiary = c("tertiary")
        onTertiary = c("onTertiary")
        tertiaryContainer = c("tertiaryContainer")
        onTertiaryContainer = c("onTertiaryContainer")
        background = c("background")
        onBackground = c("onBackground")
        surface = c("surface")
        onSurface = c("onSurface")
        surfaceVariant = c("surfaceVariant")
        onSurfaceVariant = c("onSurfaceVariant")
        surfaceTint = c("surfaceTint")
        inverseSurface = c("inverseSurface")
        inverseOnSurface = c("inverseOnSurface")
        error = c("error")
        onError = c("onError")
        errorContainer = c("errorContainer")
        onErrorContainer = c("onErrorContainer")
        outline = c("outline")
        outlineVariant = c("outlineVariant")
        scrim = c("scrim")
        surfaceBright = c("surfaceBright")
        surfaceDim = c("surfaceDim")
        surfaceContainer = c("surfaceContainer")
        surfaceContainerHigh = c("surfaceContainerHigh")
        surfaceContainerHighest = c("surfaceContainerHighest")
        surfaceContainerLow = c("surfaceContainerLow")
        surfaceContainerLowest = c("surfaceContainerLowest")
    }

    // MARK: - Roles as ARGB

    /// Every role ExpressiveColorSchemeFactory maps into the Compose ColorScheme, as
    /// unsigned ARGB (0xAARRGGBB), keyed by the Compose role name.
    static func argbRoles(seedArgb: Int, dark: Bool) -> [String: Int] {
        let roles = ExpressiveRoles.shared.argbRoles(seedArgb: Int32(truncatingIfNeeded: seedArgb), isDark: dark,
                                                     contrastLevel: 0.0)
        return roles.mapValues { Int(UInt32(bitPattern: $0.int32Value)) }
    }

    private struct CacheKey: Hashable, Sendable { let argb: Int; let dark: Bool }

    /// Views rebuild the scheme on every body pass; the MCU solve runs once
    /// per (seed, mode).
    private static let cache = OSAllocatedUnfairLock<[CacheKey: [String: Int]]>(initialState: [:])

    private static func cachedRoles(seedArgb: Int, dark: Bool) -> [String: Int] {
        let key = CacheKey(argb: seedArgb, dark: dark)
        if let hit = cache.withLock({ $0[key] }) { return hit }
        let roles = argbRoles(seedArgb: seedArgb, dark: dark)
        cache.withLock { $0[key] = roles }
        return roles
    }

    /// sRGB `Color` from unsigned ARGB.
    static func color(_ argb: Int) -> Color {
        Color(
            .sRGB,
            red: Double((argb >> 16) & 0xFF) / 255,
            green: Double((argb >> 8) & 0xFF) / 255,
            blue: Double(argb & 0xFF) / 255,
            opacity: Double((argb >> 24) & 0xFF) / 255
        )
    }

    /// Opaque sRGB ARGB of a `Color` (components clamped to sRGB).
    static func argb(of color: Color) -> Int {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        UIColor(color).getRed(&r, green: &g, blue: &b, alpha: &a)
        // MCU ColorUtils.argbFromRgb of Kotlin's roundToInt (Java Math.round: halves round up).
        func byte(_ v: CGFloat) -> Int {
            let x = Double(min(max(v, 0), 1)) * 255, f = x.rounded(.down)
            return Int(x - f >= 0.5 ? f + 1 : f)
        }
        return 0xFF00_0000 | (byte(r) << 16) | (byte(g) << 8) | byte(b)
    }
}

// MARK: - Cover seed

/// The album's colour, read off its cover the way Android's CoverSeedExtractor
/// reads it: a Palette pass (16 colours, default filter, the default 112×112
/// resize of a 200 px decode), then vibrant → dominant → muted.
enum CoverSeed {
    /// Which swatch produced the seed.
    enum Source: String, Sendable { case vibrant, dominant, muted }

    struct Pick: Sendable {
        let argb: Int
        let source: Source
    }

    /// The edge, in px, the cover is rendered at (Android's `CoverSeedExtractor.BitmapSizePx`).
    static let bitmapSizePx = 200
    static let maxColors = 16

    /// The seed of a cover image, or nil when the Palette pass finds no swatch.
    static func extract(from image: UIImage) -> Color? {
        guard let cgImage = image.cgImage, let pick = pick(from: cgImage) else { return nil }
        return CoverScheme.color(pick.argb)
    }

    /// The seed swatch of a bitmap, and which target produced it.
    static func pick(from cgImage: CGImage) -> Pick? {
        guard let (pixels, width, height) = argbPixels(of: cgImage) else { return nil }
        return pick(pixels: pixels, width: width, height: height)
    }

    /// The seed swatch of an ARGB pixel buffer (Android `Bitmap.getPixels` layout).
    /// Android decodes at 200 px and lets Palette.Builder resize to its 112 × 112 area;
    /// `scaleDownPixels` is that resize (pixel-exact against Android at 200 px).
    static func pick(pixels: [UInt32], width: Int, height: Int) -> Pick? {
        let input = KotlinIntArray(size: Int32(pixels.count))
        for (i, p) in pixels.enumerated() { input.set(index: Int32(i), value: Int32(bitPattern: p)) }
        let palettes = Palette.companion
        let scaled = palettes.scaleDownPixels(pixels: input, width: Int32(width), height: Int32(height))
        let palette = palettes.generate(pixels: scaled, maximumColorCount: Int32(maxColors),
                                        filters: [palettes.DEFAULT_FILTER])
        func argb(_ s: Palette.Swatch) -> Int { Int(UInt32(bitPattern: s.rgb)) }
        if let s = palette.vibrantSwatch { return Pick(argb: argb(s), source: .vibrant) }
        if let s = palette.dominantSwatch { return Pick(argb: argb(s), source: .dominant) }
        if let s = palette.mutedSwatch { return Pick(argb: argb(s), source: .muted) }
        return nil
    }

    /// Un-premultiplied ARGB pixels of an image, drawn into an sRGB RGBA8 buffer.
    static func argbPixels(of cgImage: CGImage) -> ([UInt32], Int, Int)? {
        let width = cgImage.width, height = cgImage.height
        guard width > 0, height > 0, let space = CGColorSpace(name: CGColorSpace.sRGB) else { return nil }
        var rgba = [UInt8](repeating: 0, count: width * height * 4)
        let drawn: Bool = rgba.withUnsafeMutableBytes { buffer in
            guard let ctx = CGContext(
                data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8,
                bytesPerRow: width * 4, space: space,
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue
            ) else { return false }
            ctx.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))
            return true
        }
        guard drawn else { return nil }
        var argb = [UInt32](repeating: 0, count: width * height)
        for i in 0..<(width * height) {
            var r = UInt32(rgba[i * 4]), g = UInt32(rgba[i * 4 + 1]), b = UInt32(rgba[i * 4 + 2])
            let a = UInt32(rgba[i * 4 + 3])
            if a > 0 && a < 255 {
                r = min(255, (r * 255 + a / 2) / a)
                g = min(255, (g * 255 + a / 2) / a)
                b = min(255, (b * 255 + a / 2) / a)
            }
            argb[i] = (a << 24) | (r << 16) | (g << 8) | b
        }
        return (argb, width, height)
    }

    // MARK: Mock covers

    @MainActor private static var cache: [MediaId: Pick?] = [:]

    /// The seed of an album's (mock) cover; palette.a when the cover yields none.
    @MainActor
    static func seed(for album: Album) -> Color {
        CoverScheme.color(seedArgb(for: album))
    }

    @MainActor
    static func seedArgb(for album: Album) -> Int {
        pick(for: album)?.argb ?? CoverScheme.argb(of: album.palette.a)
    }

    /// The seed swatch of an album's rendered `CoverArt`, cached by album id.
    @MainActor
    static func pick(for album: Album) -> Pick? {
        if let cached = cache[album.id] { return cached }
        let result = renderCover(album).flatMap { CoverSeed.pick(from: $0) }
        cache[album.id] = .some(result)
        return result
    }

    /// The album's `CoverArt` rendered at 200 × 200 px (square, no corner).
    @MainActor
    static func renderCover(_ album: Album) -> CGImage? {
        let size = CGFloat(bitmapSizePx)
        let cover = CoverArt(palette: album.palette, cornerRadius: 0, seed: album.seed)
        let content = cover.frame(width: size, height: size)
        let renderer = ImageRenderer(content: content)
        renderer.scale = 1
        renderer.isOpaque = true
        return renderer.cgImage
    }
}
