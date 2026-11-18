import SwiftUI

/// Focus's edge dots (Android EdgeDotBand.kt, owner 2026-11-03, "整齐细点带"),
/// on the iPad's two-column Now Playing only (owner 2026-11-13; the iPhone
/// keeps its focus without dots). One fixed hex lattice; only the dots' size
/// changes, biggest at the window's edge and shrinking to nothing inward, over
/// a faint wash of the cover's colours. An entrance, not a fixture: it bursts
/// out from the cover's side when focus opens (≈0.2 s to widest), settles
/// within a second, recedes from 2 s and is gone at 8 s. A song change in
/// focus bursts it again with a sweep from the left; a new lyric line sends one
/// soft swell out from the lyrics; pausing slows the waves and thins the band.
///
/// Drawn per pixel by `yoinEdgeBand` (Theme/DotDissolve.metal); nothing runs
/// once the band is gone.
struct NowPlayingEdgeBand: View {
    /// Two-column focus is open.
    var on: Bool
    var playing: Bool
    /// The cover's and the lyrics' centres, global.
    var cover: CGPoint
    var lyrics: CGPoint?
    /// The cover's main colours (wash: bottom, top, left, right; dots by angle).
    var palette: [Color]
    var primary: Color
    var dark: Bool
    /// Bumped on each new lyric line.
    var lineTick: Int
    /// The playing song: a change in focus bursts the band again.
    var songKey: String

    @State private var clock = EdgeBandClock()
    /// While the band has something to draw.
    @State private var alive = false
    /// The burst's first second runs every frame; then ≤30 fps (Android EdgeBandFastMs / FocusFrameMs).
    @State private var fast = false
    @State private var life: Task<Void, Never>?

    var body: some View {
        EdgeBandLayer(focus: on ? 1 : 0, playing: playing ? 1 : 0, cover: cover, lyrics: lyrics,
                      inks: inks, washes: washes, dark: dark, clock: clock, alive: alive, fast: fast)
            .animation(Yoin.stageSettle, value: on)
            .animation(Yoin.slowEffects, value: playing)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
            .onChange(of: on, initial: true) { _, now in
                if now { start(song: false) }
            }
            .onChange(of: songKey) {
                if on { start(song: true) }
            }
            .onChange(of: lineTick) {
                if on, alive { clock.linePulse = Date() }
            }
            .onDisappear { life?.cancel() }
    }

    /// Android EdgeBandState.enter / songChange.
    private func start(song: Bool) {
        let now = Date()
        clock.born = now
        if song { clock.songPulse = now }
        alive = true
        fast = true
        life?.cancel()
        life = Task { @MainActor in
            try? await Task.sleep(for: .seconds(Self.fastS))
            guard !Task.isCancelled else { return }
            fast = false
            try? await Task.sleep(for: .seconds(EdgeBandLife.goneS - Self.fastS))
            guard !Task.isCancelled else { return }
            alive = false
        }
    }

    static let fastS = 1.2

    private var inks: [Color] {
        palette.map { dark ? $0.mix(with: .white, by: 0.35) : $0.mix(with: primary, by: 0.55) }
    }

    private var washes: [Color] { palette }
}

/// When the band was (re)born and what last stirred it; the waves' own clock.
/// A plain box: written by effects, read while drawing, never a render input.
final class EdgeBandClock {
    var born = Date.distantPast
    var linePulse = Date.distantPast
    var songPulse = Date.distantPast
    /// The waves' clock (s): runs at 0.2× while paused.
    var phase: Double = 0
    private var lastDraw: Date?

    func advance(to now: Date, playing: Double) {
        defer { lastDraw = now }
        guard let last = lastDraw, now > last else { return }
        let dt = min(0.25, now.timeIntervalSince(last))
        phase += dt * (EdgeBandLife.pausedWaveRate + (1 - EdgeBandLife.pausedWaveRate) * playing)
    }
}

/// Android edgeBandLife: (burst, fade) at `s` seconds of the band's life.
enum EdgeBandLife {
    static let burstRiseS = 0.22
    static let burstDecay = 3.2
    static let recedeS = 2.0
    static let goneS = 8.0
    static let pausedWaveRate = 0.2

    static func at(_ s: Double) -> (burst: Double, fade: Double) {
        let burst: Double
        if s < 0 {
            burst = 0
        } else if s < burstRiseS {
            burst = 1 - pow(1 - s / burstRiseS, 3)
        } else {
            burst = exp(-(s - burstRiseS) * burstDecay)
        }
        let fade: Double
        if s < recedeS {
            fade = 1
        } else if s >= goneS {
            fade = 0
        } else {
            let x = (s - recedeS) / (goneS - recedeS)
            fade = 1 - x * x * (3 - 2 * x)
        }
        return (burst, fade)
    }
}

/// The band at an animated `focus` and `playing` (both 0..1, on the stage's
/// and the effects' springs).
nonisolated private struct EdgeBandLayer: View, Animatable {
    var focus: CGFloat
    var playing: CGFloat
    var cover: CGPoint
    var lyrics: CGPoint?
    var inks: [Color]
    var washes: [Color]
    var dark: Bool
    var clock: EdgeBandClock
    var alive: Bool
    var fast: Bool

    var animatableData: AnimatablePair<CGFloat, CGFloat> {
        get { AnimatablePair(focus, playing) }
        set { focus = newValue.first; playing = newValue.second }
    }

    var body: some View {
        if alive && focus > 0.01 && inks.count == 4 && washes.count == 4 {
            TimelineView(.animation(minimumInterval: fast ? nil : 1.0 / 30, paused: false)) { tl in
                GeometryReader { g in
                    let now = tl.date
                    let _ = clock.advance(to: now, playing: Double(playing))
                    let life = EdgeBandLife.at(now.timeIntervalSince(clock.born))
                    let line = clock.linePulse == .distantPast ? -1 : now.timeIntervalSince(clock.linePulse)
                    let song = clock.songPulse == .distantPast ? -1 : now.timeIntervalSince(clock.songPulse)
                    let o = g.frame(in: .global).origin
                    let center = lyrics.map { CGPoint(x: $0.x - o.x, y: $0.y - o.y) }
                        ?? CGPoint(x: g.size.width * 0.5, y: g.size.height * 0.6)
                    Rectangle()
                        .fill(.white)
                        .colorEffect(ShaderLibrary.yoinEdgeBand(
                            .float2(g.size),
                            .float(clock.phase),
                            .float(focus),
                            .float(life.burst),
                            .float(life.fade),
                            .float(playing),
                            .float2(CGPoint(x: cover.x - o.x, y: cover.y - o.y)),
                            .float2(center),
                            .float(line),
                            .float(song),
                            .float(dark ? 1 : 0),
                            .color(inks[0]), .color(inks[1]), .color(inks[2]), .color(inks[3]),
                            .color(washes[0]), .color(washes[1]), .color(washes[2]), .color(washes[3])
                        ))
                }
            }
        }
    }
}
