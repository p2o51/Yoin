import AppKit
import SwiftUI

/// Android FocusField's six cover points, eight room points, lower hue and wanderer.
/// Sampling happens once per cover; frames interpolate a small bitmap without blur or full-size effects.
struct MacFocusField: View {
    var coverBounds: CGRect
    @Environment(MacModel.self) private var model
    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var windowVisible = false
    @State private var appeared = false
    @State private var palette: FocusPalette?
    @State private var outgoing: FocusPalette?
    @State private var changedAt = Date.now
    @State private var fromRight = true
    @State private var epoch = Date.now

    private struct LoadKey: Hashable {
        var account: String
        var provider: String?
        var song: String?
        var cover: URL?
        var dark: Bool
    }

    private var key: LoadKey {
        let t = model.player.currentTrack
        return LoadKey(account: model.session.accountKey, provider: t?.provider, song: t?.id,
                       cover: t?.coverURL, dark: scheme == .dark)
    }

    var body: some View {
        Group {
            if appeared && windowVisible && !reduceMotion && model.focus && model.npMode != .closed {
                TimelineView(.animation(minimumInterval: 1.0 / 30)) { tick in
                    field(at: tick.date, moving: true)
                }
            } else {
                field(at: epoch, moving: false)
            }
        }
        .background { FocusWindowVisibility(visible: $windowVisible) }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        .onAppear { appeared = true }
        .onDisappear { appeared = false }
        .task(id: key) { await load(key) }
    }

    private func field(at date: Date, moving: Bool) -> some View {
        // Keep progress reads inside the field so the rest of Now Playing doesn't redraw at 4 Hz.
        let progress = moving ? model.player.progress.fraction : 0
        let time = moving ? date.timeIntervalSince(epoch) : 0
        // The effects spring slowed to cross the whole room, evaluated only on the 30 Hz timeline.
        let elapsed = max(0, date.timeIntervalSince(changedAt))
        let fade = moving ? min(1, max(0, Yoin.effectsSpring.value(target: 1.0, time: elapsed / 3))) : 1
        return Canvas(opaque: true) { context, size in
            guard let palette else {
                context.fill(Path(CGRect(origin: .zero, size: size)), with: .color(Yoin.surface))
                return
            }
            let points = palette.points(size: size, cover: coverBounds, time: time,
                                        progress: progress, fromRight: fromRight)
            let old = fade < 1 ? outgoing?.points(size: size, cover: coverBounds, time: time,
                                                progress: progress, fromRight: fromRight) : nil
            let mixed = points.enumerated().map { i, p in
                guard let old else { return p }
                var p = p
                p.color = FocusPalette.mix(old[i].color, p.color,
                                           FocusPalette.sweep(x: p.x, progress: fade, fromRight: fromRight))
                return p
            }
            if let image = FocusPalette.render(mixed, size: size) {
                context.draw(Image(decorative: image, scale: 1, orientation: .up).interpolation(.medium),
                             in: CGRect(origin: .zero, size: size))
            }
        }
    }

    @MainActor private func load(_ key: LoadKey) async {
        let generation = model.library.accountGeneration
        let direction = model.player.focusJourneyForward
        let fallback = model.currentEntry?.album.palette
        let colors = [fallback?.a ?? Yoin.violet, fallback?.b ?? Yoin.violet, fallback?.c ?? Yoin.violet]
        if palette == nil { palette = FocusPalette.fallback(colors, dark: key.dark) }
        var sampled: FocusPalette?
        if let url = key.cover {
            let ref = CoverRef(url: url)
            await CoverArtwork.shared.load(ref)
            guard !Task.isCancelled, generation == model.library.accountGeneration else { return }
            if let image = CoverArtwork.shared.image(ref)?.cgImage {
                sampled = await Task.detached(priority: .utility) { FocusPalette.sample(image, dark: key.dark) }.value
            }
        }
        guard !Task.isCancelled, generation == model.library.accountGeneration else { return }
        let next = sampled ?? FocusPalette.fallback(colors, dark: key.dark)
        outgoing = reduceMotion ? nil : palette
        palette = next
        fromRight = direction
        changedAt = .now
    }
}

private struct FocusPalette: Sendable {
    typealias RGB = SIMD3<Double>
    var grid: [RGB]
    var hues: [RGB]
    var accent: RGB

    struct Point {
        var x: Double
        var y: Double
        var color: RGB
        var reach: Double
        var weight: Double
    }

    static func mix(_ a: RGB, _ b: RGB, _ k: Double) -> RGB { a + (b - a) * k }

    static func sweep(x: Double, progress: Double, fromRight: Bool) -> Double {
        let entry = fromRight ? 1 - min(1, max(0, x)) : min(1, max(0, x))
        let t = min(1, max(0, (progress * 1.3 - entry) / 0.3))
        return t * t * (3 - 2 * t)
    }

    // A 24 × 24 box-filtered cover grid, as in Android's buildCoverField.
    static func sample(_ image: CGImage, dark: Bool) -> Self? {
        let n = 24
        var bytes = [UInt8](repeating: 0, count: n * n * 4)
        let ok = bytes.withUnsafeMutableBytes { buffer -> Bool in
            guard let space = CGColorSpace(name: CGColorSpace.sRGB),
                  let context = CGContext(data: buffer.baseAddress, width: n, height: n, bitsPerComponent: 8,
                                          bytesPerRow: n * 4, space: space,
                                          bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
            context.interpolationQuality = .high
            context.draw(image, in: CGRect(x: 0, y: 0, width: n, height: n))
            return true
        }
        guard ok else { return nil }
        var raw: [RGB] = []
        var buckets = [RGB](repeating: .zero, count: 12)
        var weights = [Double](repeating: 0, count: 12)
        var vivid = RGB.zero
        var best = -1.0
        for i in 0..<(n * n) {
            let alpha = max(1, Double(bytes[i * 4 + 3]))
            let c = RGB(Double(bytes[i * 4]), Double(bytes[i * 4 + 1]), Double(bytes[i * 4 + 2])) / alpha
            raw.append(c)
            let hsl = hsl(c)
            let chroma = hsl.y * (1 - abs(2 * hsl.z - 1))
            if chroma > best { best = chroma; vivid = c }
            let bucket = min(11, Int(hsl.x * 12))
            let weight = 0.15 + chroma
            buckets[bucket] += c * weight
            weights[bucket] += weight
        }
        let order = weights.indices.filter { weights[$0] > 0 }.sorted { weights[$0] > weights[$1] }
        let hues = (0..<4).map { i -> RGB in
            let k = order[min(i, order.count - 1)]
            return tone(buckets[k] / weights[k], dark: dark)
        }
        return Self(grid: raw.map { tone($0, dark: dark) }, hues: hues, accent: tone(vivid, dark: dark))
    }

    @MainActor static func fallback(_ colors: [Color], dark: Bool) -> Self {
        let c = colors.map { color -> RGB in
            let (r, g, b, _) = rgbaComponents(color)
            return tone(RGB(Double(r), Double(g), Double(b)), dark: dark)
        }
        return Self(grid: (0..<576).map { c[$0 % c.count] }, hues: [c[0], c[1], c[2], c[0]], accent: c[0])
    }

    private func at(_ u: Double, _ v: Double) -> RGB {
        grid[min(23, max(0, Int(v * 24))) * 24 + min(23, max(0, Int(u * 24)))]
    }

    func points(size: CGSize, cover: CGRect, time: Double, progress: Double, fromRight: Bool) -> [Point] {
        let w = max(1, size.width), h = size.height / w
        let cx = cover.isEmpty ? 0.33 : cover.midX / w
        let cy = cover.isEmpty ? h * 0.33 : cover.midY / w
        let side = cover.isEmpty ? 0.4 : max(0.2, min(cover.width, cover.height) / w)
        let t = time * 1.5
        var out: [Point] = []
        func point(_ x: Double, _ y: Double, _ c: RGB, _ reach: Double, _ weight: Double) -> Point {
            let journey = 0.6 * Self.sweep(x: x, progress: progress, fromRight: fromRight)
            return Point(x: x, y: y, color: Self.mix(c, hues[1], journey), reach: reach, weight: weight)
        }
        for i in 0..<6 {
            let a = Double(i) / 6 * 2 * .pi + 0.4
            let orbit = a + t / 16
            out.append(point(cx + cos(a) * side * 0.48, cy + sin(a) * side * 0.48,
                             at(0.5 + 0.4 * cos(orbit), 0.5 + 0.4 * sin(orbit)), side * 0.3, 1))
        }
        let anchors: [(Double, Double)] = [(0.06, 0.08), (0.5, 0.02), (0.94, 0.12), (0.98, 0.5),
                                          (0.9, 0.92), (0.5, 0.98), (0.06, 0.9), (0.02, 0.46)]
        let order = [0, 1, 2, 3, 0, 2, 1, 3]
        for (i, anchor) in anchors.enumerated() {
            let d = Double(i)
            let x = anchor.0 + sin(t / (5.2 + d * 0.64) + d) * side * 0.18
            let y = anchor.1 * h + cos(t / (6.1 + d * 0.53) + d * 1.7) * side * 0.2
            out.append(point(x, y, Self.mix(hues[order[i]], at(anchor.0, anchor.1), 0.3), side * 0.69, 0.9))
        }
        out.append(point(0.23 + sin(t / 8.8) * side * 0.24, h * 0.81 + sin(t / 6.1 + 1) * side * 0.4,
                         hues[1], side * 0.34, 1.4))
        out.append(point(0.5 + 0.44 * sin(t / 7.3 + 0.6), h * (0.52 + 0.42 * sin(t / 9.7 + 2.2)),
                         accent, side * 0.37, 2.2))
        return out
    }

    static func render(_ points: [Point], size: CGSize) -> CGImage? {
        guard size.width > 0, size.height > 0 else { return nil }
        let columns = 48
        let rows = max(2, min(96, Int(Double(columns) * size.height / size.width)))
        let aspect = size.height / size.width
        var bytes = [UInt8](repeating: 255, count: columns * rows * 4)
        let inverses = points.map { 1 / (2 * $0.reach * $0.reach) }
        for y in 0..<rows {
            let py = (Double(y) + 0.5) / Double(rows) * aspect
            for x in 0..<columns {
                let px = (Double(x) + 0.5) / Double(columns)
                var rgb = points[0].color * 0.01
                var total = 0.01
                for (i, point) in points.enumerated() {
                    let dx = px - point.x, dy = py - point.y
                    let weight = exp(-(dx * dx + dy * dy) * inverses[i]) * point.weight
                    rgb += point.color * weight
                    total += weight
                }
                rgb /= total
                let offset = (y * columns + x) * 4
                for c in 0..<3 { bytes[offset + c] = UInt8(min(255, max(0, rgb[c] * 255))) }
            }
        }
        guard let provider = CGDataProvider(data: Data(bytes) as CFData),
              let space = CGColorSpace(name: CGColorSpace.sRGB) else { return nil }
        return CGImage(width: columns, height: rows, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: columns * 4,
                       space: space, bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue),
                       provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent)
    }

    private static func hsl(_ rgb: RGB) -> RGB {
        let hi = max(rgb.x, rgb.y, rgb.z), lo = min(rgb.x, rgb.y, rgb.z)
        let delta = hi - lo, light = (hi + lo) / 2
        guard delta > 0.0001 else { return RGB(0, 0, light) }
        var hue = hi == rgb.x ? (rgb.y - rgb.z) / delta : hi == rgb.y ? (rgb.z - rgb.x) / delta + 2 : (rgb.x - rgb.y) / delta + 4
        hue = (hue / 6 + 1).truncatingRemainder(dividingBy: 1)
        return RGB(hue, delta / max(0.001, 1 - abs(2 * light - 1)), light)
    }

    private static func tone(_ rgb: RGB, dark: Bool) -> RGB {
        let h = hsl(rgb)
        let saturation = h.y < 0.04 ? h.y : min(0.85, max(0.3, h.y))
        let light = dark ? 0.12 + h.z * 0.12 : 0.84 + h.z * 0.1
        let chroma = (1 - abs(2 * light - 1)) * saturation
        let sector = h.x * 6
        let x = chroma * (1 - abs(sector.truncatingRemainder(dividingBy: 2) - 1))
        let c: RGB
        switch sector {
        case ..<1: c = RGB(chroma, x, 0)
        case ..<2: c = RGB(x, chroma, 0)
        case ..<3: c = RGB(0, chroma, x)
        case ..<4: c = RGB(0, x, chroma)
        case ..<5: c = RGB(x, 0, chroma)
        default: c = RGB(chroma, 0, x)
        }
        return c + RGB(repeating: light - chroma / 2)
    }
}

/// Observes the containing window, including minimization, app hiding and full occlusion.
private struct FocusWindowVisibility: NSViewRepresentable {
    @Binding var visible: Bool
    func makeNSView(context: Context) -> VisibilityView {
        let view = VisibilityView()
        view.changed = { visible = $0 }
        return view
    }
    func updateNSView(_ nsView: VisibilityView, context: Context) {}

    final class VisibilityView: NSView {
        var changed: ((Bool) -> Void)?
        private var observers: [NSObjectProtocol] = []
        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            observers.forEach(NotificationCenter.default.removeObserver)
            observers = []
            if let window {
                for name in [NSWindow.didChangeOcclusionStateNotification, NSWindow.didMiniaturizeNotification,
                             NSWindow.didDeminiaturizeNotification, NSWindow.willCloseNotification] {
                    observers.append(NotificationCenter.default.addObserver(forName: name, object: window, queue: .main) { [weak self] _ in
                        Task { @MainActor in self?.refresh() }
                    })
                }
            }
            Task { @MainActor [weak self] in self?.refresh() }
        }
        private func refresh() {
            changed?(window.map { $0.isVisible && !$0.isMiniaturized && $0.occlusionState.contains(.visible) } ?? false)
        }
        isolated deinit { observers.forEach(NotificationCenter.default.removeObserver) }
    }
}
