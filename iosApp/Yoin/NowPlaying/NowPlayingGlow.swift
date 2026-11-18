import SwiftUI
import Observation

/*
 * Now Playing's lights (Android NowPlayingAuroraBackground + ActionGlow.kt +
 * EdgeDotBand drawTapDots, owner 2026-11-04 prototype v1, play mode calmed
 * 2026-11-06). At rest the room is still; it lights only to answer a finger:
 *
 *  - Play / pause: light gathers at PLAY while the finger holds it; on the
 *    commit two rings go out (play) or a bloom sinks (pause) from the button,
 *    or from the neutral point (0.5 W, 0.62 H) when nothing here was tapped
 *    (lock screen, headphones).
 *  - PREVIOUS / PLAY / NEXT: a ring of fine dots leaves the key's outline,
 *    leaning the way the key points.
 *  - A song change: while the lyrics lead, a light rises with the lyric stream
 *    (next from below, previous from above); while the cover leads, the new
 *    cover's light sweeps in from the tapped side (fresh taps only; untapped
 *    changes just cross over in the room).
 *  - A tapped lyric line: a wide, faint band at its height.
 *  - Every action that changes something (ActionGlow): from the control that
 *    was pressed, in the cover's colours with fine dots, 0.55–2.1 s. Adding
 *    sends the light out; taking away draws a dimmer, shorter light back in.
 *    A wait (translation) is a slow sweep in the cover's colours.
 *
 * Controls fire on a `NowPlayingGlow` (one per player); `NowPlayingGlowLayer`
 * draws it behind the page's content. Nothing ticks while nothing is lit.
 */

/// The lights of one Now Playing page. Only `live` is observed; everything else
/// is read when a light fires or while one draws, so taps and layout never
/// re-render the page.
@MainActor @Observable
final class NowPlayingGlow {
    enum Kind {
        // Action lights (Android ActionGlowKind).
        case favoriteOn, favoriteOff, gatherInto, ratingCommit, ratingClear
        case shuffleOn, shuffleOff, repeatAll, repeatOne, repeatOff
        case seekRelease, noteSave, noteDelete, lyricsReveal, translationWave, deviceHandoff, roomLift, coverHold, lineGlow
        // Transport (Android NowPlayingAuroraBackground's pulses and tap dots).
        case playBurst, pauseBurst, tapDots, sideFlow, flowLight, lineBand

        /// Seconds.
        func duration(_ value: Double) -> Double {
            switch self {
            case .favoriteOn: 1.4
            case .favoriteOff: 0.7
            case .gatherInto: 1.3
            case .ratingCommit: value >= NowPlayingGlow.fullRating ? 1.7 : 1.3
            case .ratingClear: 0.8
            case .shuffleOn, .repeatAll, .repeatOne: 0.8
            case .shuffleOff, .repeatOff: 0.55
            case .seekRelease: 0.65
            case .noteSave: 1.7
            case .noteDelete: 0.7
            case .lyricsReveal: 1.3
            case .translationWave: 1.9
            case .deviceHandoff: 2.1
            case .roomLift: 0.9
            case .coverHold: 0.7
            case .lineGlow: 1.4
            case .playBurst: 1.9
            case .pauseBurst: 2.2
            case .tapDots: 1.05
            case .sideFlow: 0.9
            case .flowLight: 1.1
            case .lineBand: 1.4
            }
        }

        /// A play-mode light: a newer one takes over from it instead of piling on.
        var isPlayMode: Bool { [.shuffleOn, .shuffleOff, .repeatAll, .repeatOne, .repeatOff].contains(self) }

        /// Moves across the room (a sweep, a flow, dots flying): left out under Reduce Motion.
        var moves: Bool { [.tapDots, .sideFlow, .flowLight, .deviceHandoff, .lyricsReveal, .translationWave].contains(self) }
    }

    /// Controls whose bounds a light starts from.
    enum Anchor: Hashable { case play, next, previous, heart, rating, progress, mode, write, devices, cover, lyrics }

    /// One fired light. `anchor`: the control (global), nil = the neutral point; `value`: the rating
    /// (rating lights), the lean (tap dots: −1 previous, +1 next, 0 play) or the way (flows: +1 next).
    struct Light {
        var kind: Kind
        var anchor: CGRect?
        var born: Date
        var value: Double = 0
        var targetY: CGFloat?
        var area: CGRect?
        /// When a newer light of the same family took over; this one fades out over `handOverS`.
        var retired: Date?

        var duration: Double { kind.duration(value) }

        func isDone(_ now: Date) -> Bool {
            now.timeIntervalSince(born) > duration
                || retired.map { now.timeIntervalSince($0) > NowPlayingGlow.handOverS } == true
        }
    }

    enum HoldKind { case rating, scrub, play }

    /// A finger held on a control: the rating bar's column, the wave bar's scrub, PLAY's gather.
    struct Hold {
        var kind: HoldKind
        /// The finger's spot (global): the value's point on the bar, PLAY's centre.
        var point: CGPoint
        var bar: CGRect
        /// The rating under the finger (0–10); for PLAY, 1 while playing (the gather is pause's colour).
        var value: Double = 0
    }

    enum TapKind { case playPause, next, previous }

    /// Something is drawing: the layer's clock runs.
    private(set) var live = false

    @ObservationIgnored private(set) var lights: [Light] = []
    @ObservationIgnored private var anchors: [Anchor: CGRect] = [:]
    @ObservationIgnored private(set) var hold: Hold?
    /// The last hold drawn, kept while its light fades after the finger lifts.
    @ObservationIgnored private(set) var shownHold: Hold?
    @ObservationIgnored private(set) var holdFade = Envelope(ramp: 0.3)
    @ObservationIgnored private(set) var waitFade = Envelope(ramp: 0.6)
    @ObservationIgnored private(set) var waitArea: CGRect?
    @ObservationIgnored private var lastTap: (kind: TapKind, at: Date)?
    @ObservationIgnored private var lastSongChange = Date.distantPast
    @ObservationIgnored private var lastSeek = Date.distantPast
    @ObservationIgnored private var touchDown: (point: CGPoint, at: Date)?
    @ObservationIgnored private var sleeper: Task<Void, Never>?
    /// Reduce Motion: the lights that travel are left out.
    @ObservationIgnored var reduceMotion = false

    // MARK: Anchors

    func report(_ anchor: Anchor, _ frame: CGRect) { anchors[anchor] = frame }

    func frame(_ anchor: Anchor) -> CGRect? { anchors[anchor].flatMap { $0.isEmpty ? nil : $0 } }

    // MARK: Firing

    /// Light the room for an action at `anchor` (a control's place, or a rect of its own).
    func glow(_ kind: Kind, from anchor: Anchor, value: Double = 0, targetY: CGFloat? = nil, area: CGRect? = nil) {
        guard let frame = frame(anchor) else { return }
        glow(kind, at: frame, value: value, targetY: targetY, area: area)
    }

    func glow(_ kind: Kind, at rect: CGRect?, value: Double = 0, targetY: CGFloat? = nil, area: CGRect? = nil) {
        if reduceMotion && kind.moves { return }
        let now = Date()
        if kind.isPlayMode || kind == .translationWave {
            let matches: (Kind) -> Bool = kind.isPlayMode ? { $0.isPlayMode } : { $0 == .translationWave }
            for i in lights.indices where matches(lights[i].kind) && lights[i].retired == nil { lights[i].retired = now }
        }
        lights.append(Light(kind: kind, anchor: rect, born: now, value: value, targetY: targetY, area: area))
        if lights.count > Self.maxLights { lights.removeFirst(lights.count - Self.maxLights) }
        wake()
    }

    /// A held finger (nil when it lifts: its light fades out).
    func setHold(_ new: Hold?) {
        let now = Date()
        if (new == nil) != (hold == nil) { holdFade.set(new == nil ? 0 : 1, now) }
        hold = new
        if let new { shownHold = new }
        wake()
    }

    /// Something is fetched for the lyrics (a translation): the slow sweep runs over `area`.
    func setWaiting(_ waiting: Bool, area: CGRect?) {
        if let area { waitArea = area }
        guard (waitFade.target > 0) != waiting else { return }
        waitFade.set(waiting ? 1 : 0, Date())
        wake()
    }

    // MARK: Transport

    /// A transport key was tapped: its ring of dots, and the tap is kept (3 s, once) for the commit.
    func tapped(_ kind: TapKind) {
        lastTap = (kind, Date())
        switch kind {
        case .playPause: glow(.tapDots, from: .play, value: 0)
        case .next: glow(.tapDots, from: .next, value: 1)
        case .previous: glow(.tapDots, from: .previous, value: -1)
        }
    }

    /// The finger went down on / lifted off PLAY: the gather.
    func playHeld(_ down: Bool, playing: Bool) {
        guard down, let f = frame(.play) else { return setHold(hold?.kind == .play ? nil : hold) }
        setHold(Hold(kind: .play, point: CGPoint(x: f.midX, y: f.midY), bar: f, value: playing ? 1 : 0))
    }

    /// The player committed play or pause. A dip and resume right after a skip, a seek or a song
    /// change is the player buffering, not a breath (Android PlaySettle).
    func playingChanged(to playing: Bool) {
        let now = Date()
        let tap = freshTap(now)
        if tap?.kind != .playPause {
            if tap != nil || now.timeIntervalSince(lastSongChange) < Self.settleS
                || now.timeIntervalSince(lastSeek) < Self.settleS { return }
        }
        let from = tap?.kind == .playPause ? frame(.play) : nil
        if tap?.kind == .playPause { lastTap = nil }
        glow(playing ? .playBurst : .pauseBurst, at: from)
    }

    /// The song changed: with the lyric stream while the lyrics lead (unless the lyrics already
    /// handed it over in place), else from the tapped side for a fresh tap only.
    func songChanged(forward: Bool, lyricsLead: Bool, inPlace: Bool) {
        let now = Date()
        lastSongChange = now
        let tap = freshTap(now).flatMap { $0.kind == .playPause ? nil : $0 }
        if tap != nil { lastTap = nil }
        if lyricsLead {
            if !inPlace { glow(.flowLight, at: frame(.lyrics), value: forward ? 1 : -1, area: frame(.lyrics)) }
        } else if let tap {
            glow(.sideFlow, at: nil, value: tap.kind == .next ? 1 : -1)
        }
    }

    func sought() { lastSeek = Date() }

    private func freshTap(_ now: Date) -> (kind: TapKind, at: Date)? {
        guard let t = lastTap, now.timeIntervalSince(t.at) <= Self.tapValidityS else { return nil }
        return t
    }

    // MARK: Lyric taps

    /// A finger went down (global).
    func touched(at point: CGPoint) { touchDown = (point, Date()) }

    /// A lyric line (or a note row) was tapped to seek: a band at the finger's height. A seek with no
    /// fresh finger (keyboard, VoiceOver) lights nothing.
    func lineTapped() {
        sought()
        guard let t = touchDown, Date().timeIntervalSince(t.at) <= 1.5 else { return }
        glow(.lineBand, at: CGRect(origin: t.point, size: .zero), targetY: t.point.y)
    }

    /// Where the lyrics' light lands: the finger's last line if it was just now, else the lyrics' middle.
    var lyricsLineY: CGFloat? {
        if let t = touchDown, Date().timeIntervalSince(t.at) <= 4, frame(.lyrics)?.contains(t.point) == true {
            return t.point.y
        }
        return frame(.lyrics).map { $0.midY }
    }

    /// Clears all running lights immediately when a resident player folds away.
    func stopAll() {
        sleeper?.cancel()
        sleeper = nil
        lights.removeAll()
        hold = nil
        shownHold = nil
        let past = Date.distantPast
        holdFade.set(0, past)
        waitFade.set(0, past)
        if live { live = false }
    }

    // MARK: Clock

    /// The clock runs while anything is lit; it stops by itself once all of it has settled.
    private func wake() {
        if !live { live = true }
        guard sleeper == nil else { return }
        sleeper = Task { @MainActor [weak self] in
            while true {
                try? await Task.sleep(for: .milliseconds(250))
                guard let self else { return }
                let now = Date()
                lights.removeAll { $0.isDone(now) }
                let idle = lights.isEmpty && hold == nil && holdFade.settled(now) && holdFade.target == 0
                    && waitFade.settled(now) && waitFade.target == 0
                if idle || Task.isCancelled {
                    if idle { shownHold = nil; live = false }
                    sleeper = nil
                    return
                }
            }
        }
    }

    static let maxLights = 6
    nonisolated static let handOverS = 0.16
    nonisolated static let fullRating = 9.5
    static let tapValidityS = 3.0
    static let settleS = 1.5

    /// A 0 ↔ 1 fade with a smoothstep over `ramp` seconds.
    struct Envelope {
        var ramp: Double
        private(set) var from: Double = 0
        private(set) var target: Double = 0
        private var at = Date.distantPast

        init(ramp: Double) { self.ramp = ramp }

        func value(_ now: Date) -> Double {
            let k = min(max(now.timeIntervalSince(at) / ramp, 0), 1)
            return from + (target - from) * k * k * (3 - 2 * k)
        }

        func settled(_ now: Date) -> Bool { now.timeIntervalSince(at) >= ramp }

        mutating func set(_ to: Double, _ now: Date) {
            from = value(now)
            target = to
            at = now
        }
    }
}

extension View {
    /// Reports this control's place for the lights that start from it.
    func glowAnchor(_ anchor: NowPlayingGlow.Anchor, _ glow: NowPlayingGlow?) -> some View {
        onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { glow?.report(anchor, $0) }
    }
}

/// The value's point on a vertical rating bar (0–10 from the bottom up).
func ratingGlowPoint(_ bar: CGRect, _ value: Double) -> CGPoint {
    let f = min(max(value / 10, 0), 1)
    return CGPoint(x: bar.midX, y: bar.maxY - bar.height * f)
}

// MARK: - Drawing

/// The lights, behind the page's content, in the cover's colours.
struct NowPlayingGlowLayer: View {
    var glow: NowPlayingGlow
    var primary: Color
    var secondary: Color
    var tertiary: Color
    var dark: Bool

    var body: some View {
        let palette = GlowPalette(primary: primary, secondary: secondary, tertiary: tertiary, dark: dark)
        TimelineView(.animation(paused: !glow.live)) { tl in
            GeometryReader { g in
                let origin = g.frame(in: .global).origin
                Canvas { gc, size in
                    guard glow.live else { return }
                    GlowPainter(gc: gc, size: size, origin: origin, pal: palette).draw(glow, now: tl.date)
                }
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// The palette a light draws in: the cover's primary / secondary / tertiary, made glowy.
@MainActor
struct GlowPalette {
    var dark: Bool
    var p: Color, s: Color, t: Color
    var hueP: Double, hueS: Double, hueT: Double
    var sat: Double
    /// Play / pause: the play ring's and the pause bloom's tints.
    var playTint: Color, pauseTint: Color
    /// The line band's two colours (the cover's hues, lifted toward the page).
    var k1: Color, k3: Color
    var primary: Color

    init(primary: Color, secondary: Color, tertiary: Color, dark: Bool) {
        self.dark = dark
        self.primary = primary
        hueP = GeminiWash.hue(primary)
        hueS = GeminiWash.hue(secondary)
        hueT = GeminiWash.hue(tertiary)
        sat = dark ? 0.75 : 0.85
        p = GeminiWash.hsl(hueP, sat, 0.62, 1)
        s = GeminiWash.hsl(hueS, sat * 0.5, 0.62, 1)
        t = GeminiWash.hsl(hueT, sat, 0.62, 1)
        playTint = GeminiWash.hsl(hueP, dark ? 0.75 : 0.85, dark ? 0.6 : 0.62, 1)
        pauseTint = GeminiWash.hsl(hueT, dark ? 0.6 : 0.7, dark ? 0.6 : 0.62, 1)
        // Lifted from the cover's hues, as Android lifts the cover's own palette: the scheme's
        // primary is a dark tone on a light page, and lifted it greyed the page instead of lighting it.
        k1 = Self.lift(p, dark)
        k3 = Self.lift(t, dark)
    }

    /// The peak alpha a burst uses (Android GlowPalette.cap).
    var cap: Double { dark ? 0.42 : 0.40 }
    /// Play / pause's own peak (Android PULSE_ALPHA_*).
    var pulseCap: Double { dark ? 0.42 : 0.34 }

    /// Dots read a touch darker on a light page, lighter on a dark one.
    func dot(_ hue: Double, _ alpha: Double, half: Bool = false) -> Color {
        GeminiWash.hsl(hue, half ? sat * 0.5 : sat, dark ? 0.72 : 0.42, min(max(alpha, 0), 1))
    }

    private static func lift(_ c: Color, _ dark: Bool) -> Color {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        UIColor(c).getRed(&r, green: &g, blue: &b, alpha: &a)
        func f(_ x: CGFloat) -> Double { dark ? min(1, Double(x) * 1.15 + 25.0 / 255) : Double(x) * 0.55 + 0.45 }
        return Color(red: f(r), green: f(g), blue: f(b))
    }
}

/// Draws one frame of the lights into a Canvas (Android drawActionGlow and the background's pulses).
@MainActor
private struct GlowPainter {
    var gc: GraphicsContext
    var size: CGSize
    var origin: CGPoint
    var pal: GlowPalette

    init(gc: GraphicsContext, size: CGSize, origin: CGPoint, pal: GlowPalette) {
        var g = gc
        g.blendMode = pal.dark ? .screen : .normal
        self.gc = g
        self.size = size
        self.origin = origin
        self.pal = pal
    }

    private var w: CGFloat { size.width }
    private var h: CGFloat { size.height }

    private func local(_ r: CGRect) -> CGRect { r.offsetBy(dx: -origin.x, dy: -origin.y) }
    private func local(_ p: CGPoint) -> CGPoint { CGPoint(x: p.x - origin.x, y: p.y - origin.y) }
    private func local(y: CGFloat) -> CGFloat { y - origin.y }

    func draw(_ glow: NowPlayingGlow, now: Date) {
        if let hold = glow.shownHold {
            let a = glow.holdFade.value(now)
            if a > 0.001 { drawHold(hold, strength: a) }
        }
        let wait = glow.waitFade.value(now)
        if wait > 0.001 { drawWait(glow.waitArea, t: now.timeIntervalSinceReferenceDate * 1000, strength: wait) }
        for light in glow.lights {
            let tMs = now.timeIntervalSince(light.born) * 1000
            draw(light, tMs: tMs, now: now)
        }
    }

    // MARK: Lights

    private func draw(_ light: NowPlayingGlow.Light, tMs: Double, now: Date) {
        let d = light.duration * 1000
        guard tMs >= 0, tMs <= d else { return }
        let u = tMs / d
        // A light that handed over fades out quickly under the one that took over.
        let since = light.retired.map { now.timeIntervalSince($0) } ?? -1
        let k = pal.cap * (since > 0 ? 1 - min(since / NowPlayingGlow.handOverS, 1) : 1)
        let a = light.anchor.map(local) ?? CGRect(x: w * 0.5, y: h * 0.62, width: 0, height: 0)
        let c = CGPoint(x: a.midX, y: a.midY)
        // Leans toward the middle of the screen, away from the edge the control sits on.
        let inward: CGFloat = c.x > w / 2 ? -1 : 1
        switch light.kind {
        case .favoriteOn:
            let beat = max(bell(tMs / 260), 0.75 * bell((tMs - 300) / 300))
            bloom(c, 70 + 50 * u, pal.t, k * 1.5 * beat + k * 0.5 * bell(u))
            bloom(CGPoint(x: c.x + inward * 40, y: c.y - 30), 170 * easeOut(min(1, tMs / 700)), pal.p, k * 0.6 * bell(u))
            if tMs > 120 {
                let r = CGFloat(30 + 110 * easeOut(min(1, (tMs - 120) / 900)))
                var dots: [(CGPoint, CGFloat, Color)] = []
                for i in 0..<14 {
                    let ang = Double(i) / 14 * .pi * 2 + 0.2
                    dots.append((CGPoint(x: c.x + CGFloat(cos(ang)) * r, y: c.y + CGFloat(sin(ang)) * r * 0.9),
                                 CGFloat(2.2 * (1 - u) + 0.4),
                                 pal.dot(i % 3 == 0 ? pal.hueP : pal.hueT, (1 - u) * 0.9)))
                }
                drawDots(dots)
            }
        case .favoriteOff:
            bloom(c, 110 * (1 - easeOut(u)) + 10, pal.s, k * 0.8 * bell(u))
        case .gatherInto:
            let g = min(1, tMs / 700)
            if g < 1 {
                let r = CGFloat(140 * (1 - easeOut(g)) + 6)
                drawDots((0..<18).map { i in
                    let ang = Double(i) / 18 * .pi * 2
                    return (CGPoint(x: c.x + CGFloat(cos(ang)) * r, y: c.y + CGFloat(sin(ang)) * r), CGFloat(1.8),
                            pal.dot(i % 2 == 0 ? pal.hueP : pal.hueT, 0.85))
                })
            }
            if tMs > 650 {
                let v = (tMs - 650) / 650
                bloom(c, 60 + 60 * easeOut(v), pal.p, k * 1.4 * bell(v))
            }
        case .ratingCommit:
            let str = 0.35 + 0.65 * min(max(light.value / 10, 0), 1)
            // A band leaves the bar at the rating's height and crosses the room.
            ellipse(CGPoint(x: c.x + inward * 160 * easeOut(u), y: c.y), rx: 140 + 260 * easeOut(u), ry: 60 + 40 * u,
                    pal.p, k * 1.6 * str * bell(u))
            bloom(c, 50 + 40 * u, pal.t, k * 1.2 * str * bell(u * 1.4))
            if light.value >= NowPlayingGlow.fullRating {
                drawDots((0..<26).map { i in
                    let sx = c.x + inward * CGFloat(Double(i) / 26 * 300 * easeOut(u))
                    let sy = c.y + CGFloat(sin(Double(i) * 1.7 + tMs * 0.006) * 18 * u)
                    return (CGPoint(x: sx, y: sy), CGFloat(1.8 * (1 - u) + 0.3), pal.dot(i % 2 == 0 ? pal.hueP : pal.hueT, 1 - u))
                })
            }
        case .ratingClear:
            let bar = light.area.map(local)
            let bottom = bar.map { $0.maxY } ?? c.y
            ellipse(CGPoint(x: c.x, y: c.y + (bottom - c.y) * easeOut(u)), rx: 70, ry: 40, pal.s, k * 0.9 * (1 - u))
        case .shuffleOn, .repeatAll, .repeatOne:
            bloom(c, 56 + 34 * easeOut(u), pal.p, k * 0.75 * swell(u))
        case .shuffleOff, .repeatOff:
            bloom(c, 72 - 28 * easeOut(u), pal.s, k * 0.45 * swell(u))
        case .seekRelease:
            bloom(c, 40 + 50 * u, pal.p, k * 1.3 * bell(u))
            ellipse(c, rx: 120 + 120 * u, ry: 30, pal.p, k * 0.6 * bell(u))
        case .noteSave:
            let ly = light.targetY.map(local(y:)) ?? h * 0.55
            let q = easeOut(min(1, tMs / 650))
            let toX = w * 0.2
            if tMs < 800 {
                let fade = tMs < 650 ? 1 : 1 - (tMs - 650) / 150
                bloom(CGPoint(x: c.x + (toX - c.x) * q, y: c.y + (ly - c.y) * q), 50, pal.t, k * 1.4 * fade)
            }
            if tMs > 500 {
                let v = (tMs - 500) / 1200
                ellipse(CGPoint(x: w / 2, y: ly), rx: w * 0.9, ry: 36, pal.t, k * 1.3 * bell(v))
                dotRow(y: ly + 18, from: w * 0.06, to: w * 0.94, pal.dot(pal.hueT, 0.7 * bell(v)), tMs: tMs)
            }
        case .noteDelete:
            let ly = light.targetY.map(local(y:)) ?? h * 0.55
            ellipse(CGPoint(x: w * 0.17 + w * 0.33 * u, y: ly), rx: w * 0.55 * (1 - u) + 20, ry: 28, pal.s, k * 0.8 * (1 - u))
        case .lyricsReveal:
            let area = light.area.map(local) ?? CGRect(x: 0, y: h * 0.45, width: w, height: h * 0.3)
            let step: CGFloat = 34
            var i = 0
            var y = area.minY + step * 0.6
            while y < area.maxY && i < 12 {
                let v = (tMs - Double(i) * 90) / 700
                if v >= 0 && v <= 1 { ellipse(CGPoint(x: area.midX, y: y), rx: area.width * 0.95, ry: 24, pal.p, k * 0.9 * bell(v)) }
                y += step
                i += 1
            }
        case .translationWave:
            let step: CGFloat = 40
            guard h > 0, step > 0 else { return }
            let opening = light.value >= 0.5
            let area = light.area.map(local)
            let top = min(max(area?.minY ?? h * 0.3, 0), h)
            let from = min(max(light.targetY.map(local(y:)) ?? top, top), h)
            let color = opening ? pal.p : pal.s
            let strength = opening ? 0.62 : 0.4
            let bandLifeMs = 1100.0
            let msPerPt = 0.55
            var y = top + step * 0.5
            while y < h + step {
                let delay = Double(abs(y - from)) * msPerPt
                let v = (tMs - delay) / bandLifeMs
                if v > 0 && v < 1 {
                    ellipse(CGPoint(x: w / 2, y: y), rx: w * 0.9, ry: step * 1.1,
                            color, k * strength * softBell(v))
                }
                y += step
            }
        case .deviceHandoff:
            if tMs < 1100 {
                let v = tMs / 1100
                let y = c.y + 140 * easeOut(v)
                bloom(CGPoint(x: c.x, y: y), 90 - 30 * v, pal.p, k * 1.4 * bell(v))
                dotRow(y: y + 10, from: c.x - 50, to: c.x + 50, pal.dot(pal.hueP, 0.7 * (1 - v)), tMs: tMs)
            }
            if tMs > 900 {
                let v = (tMs - 900) / 1200
                ring(CGPoint(x: w / 2, y: h + 20), 120 + 520 * easeOut(v), pal.p, k * 1.1 * bell(v), width: 0.1)
            }
        case .roomLift:
            var g = gc
            g.blendMode = pal.dark ? .screen : .normal
            g.fill(Path(CGRect(origin: .zero, size: size)),
                   with: .color(pal.dark ? pal.p.opacity(0.14 * bell(u)) : Color.white.opacity(0.5 * bell(u))))
        case .coverHold:
            ring(c, max(a.width, a.height) * 0.72 - 40 * easeOut(u), pal.p, k * 1.1 * bell(u), width: 0.1)
        case .lineGlow:
            let ly = light.targetY.map(local(y:)) ?? c.y
            ellipse(CGPoint(x: w / 2, y: ly), rx: w, ry: 40, pal.p, k * 1.1 * bell(u))
        case .playBurst, .pauseBurst:
            drawBurst(play: light.kind == .playBurst, focal: c, sweep: easeInOut(u))
        case .tapDots:
            drawTapDots(key: a, lean: light.value, t: u)
        case .sideFlow:
            drawSideFlow(fromEnd: light.value > 0, e: easeOut(u), u: u)
        case .flowLight:
            drawFlowLight(area: light.area.map(local), forward: light.value > 0, u: u)
        case .lineBand:
            drawLineBand(y: light.targetY.map(local(y:)) ?? c.y, b: u)
        }
    }

    // MARK: Holds and waits

    private func drawHold(_ hold: NowPlayingGlow.Hold, strength: Double) {
        let p = local(hold.point)
        switch hold.kind {
        case .rating:
            // A column of light up the bar to the finger, brighter the higher the score.
            let k = pal.cap * strength
            let str = 0.3 + 0.7 * min(max(hold.value / 10, 0), 1)
            let bar = local(hold.bar)
            bloom(p, 70, pal.p, k * 1.3 * str)
            if bar.height > bar.width {
                ellipse(CGPoint(x: p.x, y: (p.y + bar.maxY) / 2), rx: 45, ry: (bar.maxY - p.y) / 2 + 30, pal.p, k * 0.7 * str)
            }
        case .scrub:
            bloom(p, 46, pal.p, pal.cap * strength * 1.1)
        case .play:
            // The gather: light converging on PLAY while the finger holds it.
            let tint = hold.value > 0.5 ? pal.pauseTint : pal.playTint
            bloom(p, w * (0.55 - 0.25 * strength), tint, pal.pulseCap * 1.1 * strength, softStops: false)
        }
    }

    /// A slow sweep in the cover's colours across the lyrics while something is fetched for them.
    private func drawWait(_ areaG: CGRect?, t: Double, strength: Double) {
        let area = areaG.map(local).flatMap { $0.isEmpty ? nil : $0 } ?? CGRect(x: 0, y: h * 0.45, width: w, height: h * 0.3)
        let y = area.minY + 40
        let x = area.minX - 80 + (t / 1400).truncatingRemainder(dividingBy: 1) * (area.width + 160)
        ellipse(CGPoint(x: x, y: y), rx: 160, ry: 70, pal.p, pal.cap * 0.9 * strength)
        dotRow(y: y + 60, from: max(area.minX, x - 60), to: min(area.maxX, x + 60), pal.dot(pal.hueP, 0.55 * strength), tMs: t)
    }

    // MARK: Transport

    /// Play: two rings out from the button, rising; pause: a bloom that sinks.
    private func drawBurst(play: Bool, focal: CGPoint, sweep: Double) {
        let env = sin(.pi * sweep)
        let cap = pal.pulseCap
        if play {
            let center = CGPoint(x: focal.x, y: focal.y - h * 0.05 * sweep)
            for r in 0..<2 {
                let radius = h * (0.1 + 0.5 * sweep + 0.13 * Double(r))
                let a = min(1, cap * 1.2 * env * (1 - 0.35 * Double(r)))
                fillRadial(center, radius, [.init(color: .clear, location: 0),
                                            .init(color: pal.playTint.opacity(0), location: 0.72),
                                            .init(color: pal.playTint.opacity(a), location: 0.88),
                                            .init(color: pal.playTint.opacity(0), location: 1)])
            }
        } else {
            bloom(CGPoint(x: focal.x, y: focal.y + h * 0.05 * sweep), h * (0.42 - 0.22 * sweep), pal.pauseTint,
                  cap * 1.1 * env, softStops: false)
        }
    }

    /// A ring of lattice dots leaves the key's outline (a stadium), leaning toward `lean`.
    private func drawTapDots(key: CGRect, lean: Double, t: Double) {
        guard t > 0, t < 1, !key.isEmpty else { return }
        let pitch = 4.6, rowStep = pitch * 0.866
        let reachBase = 72.0
        let ring = reachBase * (1 - pow(1 - t, 3))
        let width = 9 + 16 * t
        let amp = pow(1 - t, 1.4)
        let rMax = 1.7
        let radius = Double(min(key.width, key.height)) / 2
        let cx = Double(key.midX), cy = Double(key.midY)
        let halfStraight = max(0, Double(key.width) / 2 - radius)
        let halfStraightY = max(0, Double(key.height) / 2 - radius)
        let reach = reachBase * 1.75 + width * 2 + 4
        let top = Double(key.minY) - reach, bottom = Double(key.maxY) + reach
        let left = Double(key.minX) - reach, right = Double(key.maxX) + reach
        var path = Path()
        var j = Int(floor(top / rowStep))
        while Double(j) * rowStep <= bottom {
            let y = Double(j) * rowStep
            let off = j & 1 == 1 ? pitch / 2 : 0
            var i = Int(floor((left - off) / pitch))
            while Double(i) * pitch + off <= right {
                let x = Double(i) * pitch + off
                i += 1
                let qx = max(0, abs(x - cx) - halfStraight)
                let qy = max(0, abs(y - cy) - halfStraightY)
                let sd = hypot(qx, qy) - radius
                if sd <= 0 { continue }
                let ang = atan2(y - cy, x - cx)
                let leanBy = lean != 0 ? max(0.2, 1 + 0.75 * lean * cos(ang)) : 1
                let rr = ring * leanBy + 3 * sin(5 * ang + 9 * t)
                let g = (sd - rr) / width
                let r = rMax * amp * exp(-g * g)
                if r >= 0.25 { path.addEllipse(in: CGRect(x: x - r, y: y - r, width: r * 2, height: r * 2)) }
            }
            j += 1
        }
        var g = gc
        g.blendMode = .normal
        g.fill(path, with: .color(pal.primary.opacity(pal.dark ? 0.75 : 0.6)))
    }

    /// The new cover's light sweeps in from the tapped side (NEXT from the end, PREVIOUS from the
    /// start) and settles into the room's aura (Android's rest wash flowing in sideways).
    private func drawSideFlow(fromEnd: Bool, e: Double, u: Double) {
        let dir: CGFloat = fromEnd ? 1 : -1
        let travel = w * 0.9
        let center = CGPoint(x: w / 2 + dir * travel * (1 - e), y: h / 2)
        let a = pal.cap * 1.25 * min(1, u * 2.2) * (1 - smoothstep((u - 0.55) / 0.45))
        bloom(center, max(w * 0.62, 200), pal.p, a)
        dotRow(y: center.y + 120, from: center.x - 70, to: center.x + 70, pal.dot(pal.hueP, 0.6 * a / pal.cap), tMs: u * 900)
    }

    /// A light rising with the lyric stream (next: from below the lyrics; previous: from above),
    /// landing on the new song's first lines.
    private func drawFlowLight(area areaL: CGRect?, forward: Bool, u: Double) {
        let area = areaL ?? CGRect(x: 0, y: h * 0.35, width: w, height: h * 0.45)
        let start = forward ? area.maxY + h * 0.25 : area.minY - h * 0.25
        let end = area.minY + area.height * 0.3
        let move = easeOut(min(1, u / 0.85))
        let y = start + (end - start) * move
        let rise = easeOut(min(1, u / 0.2))
        let fall = u < 0.85 ? 1 : 1 - smoothstep((u - 0.85) / 0.15)
        let ry = h * (0.25 - 0.16 * move)
        var g = gc
        if areaL != nil && area.width < w * 0.8 { g.clip(to: Path(area)) }
        GlowPainter(gc: g, size: size, origin: .zero, pal: pal, raw: true)
            .ellipse(CGPoint(x: area.midX, y: y), rx: w * 0.62, ry: ry, pal.p, pal.cap * 1.3 * rise * fall)
    }

    /// Two wide, faint ellipses of the cover's colours at the tapped line.
    private func drawLineBand(y: CGFloat, b: Double) {
        let env = pow(sin(.pi * b), 1.5)
        let grow = 0.85 + 0.15 * (1 - pow(1 - b, 3))
        let a = (pal.dark ? 0.42 : 0.34) * env
        let radius = w * 0.36 * grow
        for (color, offset, m) in [(pal.k1, -0.18, 1.0), (pal.k3, 0.22, 0.8)] {
            let c = CGPoint(x: w * (0.5 + offset), y: y)
            var g = gc
            g.translateBy(x: c.x, y: c.y)
            g.scaleBy(x: 2.6 * grow, y: 1)
            g.fill(Path(ellipseIn: CGRect(x: -radius, y: -radius, width: radius * 2, height: radius * 2)),
                   with: .radialGradient(Gradient(stops: [.init(color: color.opacity(a * m), location: 0),
                                                          .init(color: color.opacity(a * m * 0.45), location: 0.55),
                                                          .init(color: color.opacity(0), location: 1)]),
                                         center: .zero, startRadius: 0, endRadius: radius))
        }
    }

    // MARK: Primitives

    private init(gc: GraphicsContext, size: CGSize, origin: CGPoint, pal: GlowPalette, raw: Bool) {
        self.gc = gc
        self.size = size
        self.origin = origin
        self.pal = pal
    }

    private func fillRadial(_ c: CGPoint, _ r: CGFloat, _ stops: [Gradient.Stop]) {
        guard r > 1 else { return }
        gc.fill(Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2)),
                with: .radialGradient(Gradient(stops: stops), center: c, startRadius: 0, endRadius: r))
    }

    /// A soft disk; `softStops` false = a plain two-stop falloff (play / pause's bloom).
    private func bloom(_ c: CGPoint, _ r: CGFloat, _ color: Color, _ alpha: Double, softStops: Bool = true) {
        guard r > 1, alpha > 0.002 else { return }
        let a = min(max(alpha, 0), 1)
        fillRadial(c, r, softStops
                   ? [.init(color: color.opacity(a), location: 0), .init(color: color.opacity(a * 0.45), location: 0.5),
                      .init(color: color.opacity(0), location: 1)]
                   : [.init(color: color.opacity(a), location: 0), .init(color: color.opacity(0), location: 1)])
    }

    /// A soft ellipse: a bloom stretched to rx × ry.
    func ellipse(_ c: CGPoint, rx: CGFloat, ry: CGFloat, _ color: Color, _ alpha: Double) {
        guard rx > 1, ry > 1, alpha > 0.002 else { return }
        let a = min(max(alpha, 0), 1)
        var g = gc
        g.translateBy(x: c.x, y: c.y)
        g.scaleBy(x: rx / ry, y: 1)
        g.fill(Path(ellipseIn: CGRect(x: -ry, y: -ry, width: ry * 2, height: ry * 2)),
               with: .radialGradient(Gradient(stops: [.init(color: color.opacity(a), location: 0),
                                                      .init(color: color.opacity(a * 0.35), location: 0.55),
                                                      .init(color: color.opacity(0), location: 1)]),
                                     center: .zero, startRadius: 0, endRadius: ry))
    }

    private func ring(_ c: CGPoint, _ r: CGFloat, _ color: Color, _ alpha: Double, width: Double) {
        guard r > 1, alpha > 0.002 else { return }
        let a = min(max(alpha, 0), 1)
        fillRadial(c, r, [.init(color: .clear, location: 0),
                          .init(color: color.opacity(0), location: max(0, 1 - width * 2)),
                          .init(color: color.opacity(a), location: 1 - width),
                          .init(color: color.opacity(0), location: 1)])
    }

    private func drawDots(_ dots: [(CGPoint, CGFloat, Color)]) {
        var g = gc
        g.blendMode = .normal
        for (p, r, color) in dots where r > 0.15 {
            g.fill(Path(ellipseIn: CGRect(x: p.x - r, y: p.y - r, width: r * 2, height: r * 2)), with: .color(color))
        }
    }

    /// A row of fine dots whose sizes ripple over time, Yoin's halftone.
    private func dotRow(y: CGFloat, from x0: CGFloat, to x1: CGFloat, _ color: Color, tMs: Double) {
        guard x1 > x0 else { return }
        var path = Path()
        var x = x0
        while x <= x1 {
            let n = 0.5 + 0.5 * sin(x * 0.05 + tMs * 0.004)
            let r = 1.3 * (0.4 + 0.6 * n)
            path.addEllipse(in: CGRect(x: x - r, y: y - r, width: r * 2, height: r * 2))
            x += 6
        }
        var g = gc
        g.blendMode = .normal
        g.fill(path, with: .color(color))
    }
}

private func bell(_ t: Double) -> Double { sin(.pi * min(max(t, 0), 1)) }

/// A bell that rises quickly and lets go slowly (Android `softBell` for translation wave bands).
private func softBell(_ t: Double) -> Double {
    let x = min(max(t, 0), 1)
    return x < 0.3 ? sin(.pi / 2 * (x / 0.3)) : 0.5 + 0.5 * cos(.pi * (x - 0.3) / 0.7)
}

/// Up quickly, then a long settle: a light that arrives and lets go without a bounce.
private func swell(_ t: Double) -> Double {
    let u = min(max(t, 0), 1), peak = 0.28
    if u < peak { return sin(.pi / 2 * u / peak) }
    let v = (u - peak) / (1 - peak)
    return 1 - v * v * (3 - 2 * v)
}

private func easeOut(_ t: Double) -> Double { 1 - pow(1 - min(max(t, 0), 1), 3) }

/// Android FastOutSlowInEasing, near enough (the play / pause sweep).
private func easeInOut(_ t: Double) -> Double {
    let x = min(max(t, 0), 1)
    return x < 0.4 ? 0.5 * pow(x / 0.4, 2) * 0.8 : 0.4 + 0.6 * (1 - pow(1 - (x - 0.4) / 0.6, 2.2))
}

private func smoothstep(_ x: Double) -> Double { let c = min(max(x, 0), 1); return c * c * (3 - 2 * c) }

// MARK: - Wiring

/// The player's state a play-mode light reads (Android playModeGlowKind).
struct PlayModeKey: Equatable {
    var shuffle: Bool
    var repeatMode: RepeatMode

    /// Which light a change gets, or nil when nothing changed. Shuffle wins when one tap changes both.
    static func glowKind(from: PlayModeKey, to: PlayModeKey) -> NowPlayingGlow.Kind? {
        if from.shuffle != to.shuffle { return to.shuffle ? .shuffleOn : .shuffleOff }
        guard from.repeatMode != to.repeatMode else { return nil }
        switch to.repeatMode {
        case .one: return .repeatOne
        case .off: return .repeatOff
        case .all: return .repeatAll
        }
    }
}

/// What the room answers that no control on the page fires: play / pause committed (from anywhere),
/// a song change, a translation's wait and arrival, the song's sheet opened from the cover, playback
/// moved to another device, Keep screen on.
struct GlowWiring: ViewModifier {
    var glow: NowPlayingGlow
    var active = true
    var playing: Bool
    var songKey: String?
    var reduceMotion: Bool
    var onSongChange: () -> Void
    var lyricsArea: () -> CGRect?

    func body(content: Content) -> some View {
        let actions = LyricsActions.shared
        content
            .onChange(of: songKey) { onSongChange() }
            .onChange(of: playing) { _, on in
                guard active else { return }
                glow.playingChanged(to: on)
            }
            .onChange(of: reduceMotion, initial: true) { _, r in glow.reduceMotion = r }
            .onChange(of: actions.inFlight == .translate) { _, waiting in
                guard active else { return }
                glow.setWaiting(waiting, area: lyricsArea())
            }
            // Translation opened or closed: wave across the whole screen bottom + plucked-string haptic.
            .onChange(of: actions.showTranslation) { old, new in
                guard active else { return }
                let oldCount = old.values.filter { $0 }.count
                let newCount = new.values.filter { $0 }.count
                guard newCount != oldCount else { return }
                let opening = newCount > oldCount
                glow.glow(.translationWave, at: nil, value: opening ? 1 : 0, targetY: glow.lyricsLineY, area: lyricsArea())
                YoinHaptics.performPluck(opening: opening)
            }
            // Long-press on the cover: a ring closes in on it.
            .onChange(of: PlaylistActions.shared.menu?.id) { _, id in
                guard active, id != nil, PlaylistActions.shared.menu?.context.kind == .nowPlaying else { return }
                glow.glow(.coverHold, from: .cover)
            }
            .onReceive(NotificationCenter.default.publisher(for: SpotifyPlayingDevice.transferred)) { _ in
                guard active else { return }
                glow.glow(.deviceHandoff, from: .devices)
            }
            .onReceive(NotificationCenter.default.publisher(for: NowPlayingGlow.roomLift)) { _ in
                guard active else { return }
                glow.glow(.roomLift, at: nil)
            }
    }
}

extension NowPlayingGlow {
    /// Keep screen on was switched on (the song's sheet posts it): the whole room brightens a moment.
    nonisolated static let roomLift = Notification.Name("NowPlayingGlow.roomLift")
}
