//  WavyProgress.swift — Yoin's wavy seek bar for iOS 26 (SwiftUI, Swift 6).
//
//  A port of Material 3 Expressive's LinearWavyProgressIndicator as Yoin uses it
//  (app/.../ui/component/WaveProgressBar.kt). Self-contained: drop the file into the app.
//
//  Where every number comes from (androidx.compose.material3 1.5.0-alpha27, the version
//  Yoin pins in gradle/libs.versions.toml as material3Expressive):
//    tokens/LinearProgressIndicatorTokens.kt   ActiveThickness 4, TrackThickness 4,
//                                              WaveHeight 10, ActiveWaveAmplitude 3,
//                                              ActiveWaveWavelength 40, TrackActiveSpace 4,
//                                              StopSize 4
//    WavyProgressIndicator.kt                  WavyProgressIndicatorDefaults (above, by name),
//                                              waveSpeed = wavelength (one wave per second),
//                                              Increasing/DecreasingAmplitudeAnimationSpec
//                                              (tween 500 ms, Standard / EmphasizedAccelerate)
//    internal/LinearWavyProgressModifiers.kt   the path: quadratic half-waves whose control
//                                              points sit at ±(height − stroke), trimmed by
//                                              arc length, then y-scaled by the amplitude
//    tokens/MotionTokens.kt                    the easing curves
//  Yoin's own overrides (WaveProgressBar.kt): height 18, stroke 4 × 0.92, amplitude 0.28,
//  wavelength 24, gap 1, no stop dot, butt-capped track at 14 % (20 % while buffered ahead),
//  amplitude follows play state only (no flat first 10 % / last 5 %), 300 ms linear glide
//  between the player's 4 Hz position ticks, snap on jumps over 6 %.

import SwiftUI

// MARK: - Numbers

/// Geometry of a linear wavy indicator, in points (1 dp ≈ 1 pt).
public struct WavyProgressMetrics: Sendable, Equatable {
    /// The indicator's own height. The wave's peak is `amplitude × (height − stroke) / 2`.
    public var height: CGFloat
    public var stroke: CGFloat
    public var trackStroke: CGFloat
    /// 0…1, the fraction of the available half-height the wave swings while playing.
    public var amplitude: CGFloat
    public var wavelength: CGFloat
    /// Points per second the wave travels. Equal to `wavelength` = one wave per second.
    public var waveSpeed: CGFloat
    /// Space between the head of the active line and the track.
    public var gap: CGFloat
    /// The dot at the far end of the track (0 = none).
    public var stopSize: CGFloat
    public var roundTrackCap: Bool

    /// Material 3 Expressive defaults, for reference (`WavyProgressIndicatorDefaults`).
    public static let material = WavyProgressMetrics(
        height: 10, stroke: 4, trackStroke: 4, amplitude: 1, wavelength: 40, waveSpeed: 40,
        gap: 4, stopSize: 4, roundTrackCap: true
    )

    /// Yoin's seek bar on Android (`WaveProgressBar.kt`): a peak of 2.0 pt.
    public static let yoin = WavyProgressMetrics(
        height: 18, stroke: 3.68, trackStroke: 3.68, amplitude: 0.28, wavelength: 24, waveSpeed: 24,
        gap: 1, stopSize: 0, roundTrackCap: false
    )

    /// Peak displacement of the wave from the centre line at full amplitude, in points.
    public var peak: CGFloat { amplitude * (height - stroke) / 2 }
}

/// Motion, copied from M3E (`MotionTokens`) and Yoin.
public enum WavyMotion {
    /// IncreasingAmplitudeAnimationSpec: tween 500 ms, EasingStandardCubicBezier (0.2, 0, 0, 1).
    public static let amplitudeIn = Animation.timingCurve(0.2, 0, 0, 1, duration: 0.5)
    /// DecreasingAmplitudeAnimationSpec: tween 500 ms, EasingEmphasizedAccelerate (0.3, 0, 0.8, 0.15).
    public static let amplitudeOut = Animation.timingCurve(0.3, 0, 0.8, 0.15, duration: 0.5)
    /// Yoin: the head glides linearly between the player's 4 Hz position updates.
    public static let glide = Animation.linear(duration: 0.3)
    /// Yoin: a jump bigger than this (seek, next track) snaps instead of sweeping.
    public static let snapThreshold = 0.06
}

// MARK: - Shapes

/// The active (played) part: a wave from the start to the head.
/// `amplitude` (0…1) multiplies `metrics.amplitude`; `phase` (0..<1) is the wave offset in wavelengths.
public struct WavyActiveShape: Shape {
    public var progress: CGFloat
    public var amplitude: CGFloat
    public var phase: CGFloat
    public var metrics: WavyProgressMetrics

    public init(progress: CGFloat, amplitude: CGFloat, phase: CGFloat, metrics: WavyProgressMetrics) {
        self.progress = progress
        self.amplitude = amplitude
        self.phase = phase
        self.metrics = metrics
    }

    // progress and amplitude animate through SwiftUI; phase is driven per frame by TimelineView.
    public var animatableData: AnimatablePair<CGFloat, CGFloat> {
        get { AnimatablePair(progress, amplitude) }
        set { progress = newValue.first; amplitude = newValue.second }
    }

    public func path(in rect: CGRect) -> Path {
        let cap = max(metrics.stroke, metrics.trackStroke) / 2 // room for the round cap
        let width = rect.width
        let tail = cap
        let head = min(max(progress * width, cap), width - cap)
        guard head > tail + 0.01 else { return Path() }
        let a = max(0, amplitude) * metrics.amplitude

        if a < 0.0005 {
            // Flat: M3 draws a plain line and does not shift it.
            var line = Path()
            line.move(to: CGPoint(x: tail, y: rect.midY))
            line.addLine(to: CGPoint(x: head, y: rect.midY))
            return line
        }

        // Exactly M3's construction: a full-amplitude wave of quadratic half-waves, a couple of
        // wavelengths longer than the bar so it can slide, cut by arc length, then flattened.
        let lambda = metrics.wavelength
        let half = lambda / 2
        let full = Path { p in
            p.move(to: .zero)
            var anchorX = half
            var controlX = half / 2
            var controlY = metrics.height - metrics.stroke
            let limit = width + lambda * 2
            while anchorX <= limit {
                p.addQuadCurve(to: CGPoint(x: anchorX, y: 0), control: CGPoint(x: controlX, y: controlY))
                anchorX += half
                controlX += half
                controlY = -controlY
            }
        }
        let extent = max(full.boundingRect.maxX, 1)
        let shift = phase.truncatingRemainder(dividingBy: 1) * lambda
        let piece = full.trimmedPath(from: (tail + shift) / extent, to: (head + shift) / extent)
        // x − shift slides the wave (crests travel toward the start, as on Android);
        // y × a flattens it around the centre line.
        return piece.applying(CGAffineTransform(a: 1, b: 0, c: 0, d: a, tx: -shift, ty: rect.midY))
    }
}

/// The unplayed part: a straight line from just after the head to the end.
public struct WavyTrackShape: Shape {
    public var progress: CGFloat
    public var metrics: WavyProgressMetrics

    public init(progress: CGFloat, metrics: WavyProgressMetrics) {
        self.progress = progress
        self.metrics = metrics
    }

    public var animatableData: CGFloat {
        get { progress }
        set { progress = newValue }
    }

    public func path(in rect: CGRect) -> Path {
        let cap = max(metrics.stroke, metrics.trackStroke) / 2
        let width = rect.width
        let barHead = progress * width
        let head = min(max(barHead, cap), width - cap)
        // M3: the gap shrinks while the head is still entering, and counts both caps once visible.
        let visible = barHead >= cap
        let gap = barHead < cap ? 0 : min(barHead - cap, metrics.gap)
        let start = max(cap, head + (visible ? gap + cap * 2 : gap))
        let end = width - cap
        guard end > start else { return Path() }
        var p = Path()
        p.move(to: CGPoint(x: start, y: rect.midY))
        p.addLine(to: CGPoint(x: end, y: rect.midY))
        return p
    }
}

// MARK: - Seek bar

/// How the played part is drawn in a Liquid Glass interface.
public enum WavyLook: Sendable {
    /// (a) Recommended: a solid wave in the cover tint over a quiet track.
    case tint
    /// (b) Experiment: the wave rendered as a tinted Liquid Glass tube (thicker, needs a busy background to read).
    case glassTube
}

/// Yoin's seek bar. Waves while playing, relaxes to a line when paused or (by default) while scrubbing.
public struct WavyProgressBar: View {
    public var progress: Double
    public var buffered: Double
    public var duration: TimeInterval
    public var isPlaying: Bool
    public var tint: Color
    public var trackTint: Color
    public var metrics: WavyProgressMetrics
    public var look: WavyLook
    /// (c) Flatten the wave while the finger is on it (Android keeps waving). A wave under the
    /// finger makes the exact position harder to read; the glass time bubble carries the motion.
    public var flattensWhileScrubbing: Bool
    public var onSeek: (Double) -> Void
    /// True while a finger is on the bar, false when it lifts.
    public var onScrubbing: (Bool) -> Void
    /// Song-moment notes as fractions of the bar (Android `noteAnchorsMs`):
    /// the thumb is pulled onto them, scrubbing across one ticks, and the
    /// bubble reads "Note" (in the tint, with `noteInk`) while it sits on one.
    public var noteAnchors: [Double]
    public var noteInk: Color
    /// The bar is on screen. Off (Now Playing's expanded stage, where the
    /// transport row is 0 tall and transparent; Android stageControlsLive),
    /// the wave's frame clock and the glide stop; they resume as it returns.
    public var live: Bool

    @State private var shown: Double
    @State private var amplitude: CGFloat
    @State private var phaseBase: Double = 0
    @State private var phaseStart: Date?
    @State private var scrub: Double?
    @State private var seekCount = 0
    /// The raw finger fraction (`scrub` is its magnetized projection).
    @State private var rawScrub: Double?
    @State private var snapped: Double?
    @State private var captureTicks = 0
    @State private var crossTicks = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    public init(
        progress: Double,
        buffered: Double = 0,
        duration: TimeInterval,
        isPlaying: Bool,
        tint: Color,
        trackTint: Color = .primary,
        metrics: WavyProgressMetrics = .yoin,
        look: WavyLook = .tint,
        flattensWhileScrubbing: Bool = true,
        noteAnchors: [Double] = [],
        noteInk: Color = .white,
        live: Bool = true,
        onScrubbing: @escaping (Bool) -> Void = { _ in },
        onSeek: @escaping (Double) -> Void
    ) {
        self.live = live
        self.noteAnchors = noteAnchors.map { min(max($0, 0), 1) }.sorted()
        self.noteInk = noteInk
        self.progress = progress
        self.buffered = buffered
        self.duration = duration
        self.isPlaying = isPlaying
        self.tint = tint
        self.trackTint = trackTint
        self.metrics = metrics
        self.look = look
        self.flattensWhileScrubbing = flattensWhileScrubbing
        self.onSeek = onSeek
        self.onScrubbing = onScrubbing
        _shown = State(initialValue: progress)
        _amplitude = State(initialValue: isPlaying ? 1 : 0)
    }

    private var displayed: Double { scrub ?? shown }

    private var wantsWave: Bool {
        isPlaying && !(flattensWhileScrubbing && scrub != nil)
    }

    private var waveMoves: Bool { isPlaying && !reduceMotion && live }

    public var body: some View {
        GeometryReader { geo in
            let width = geo.size.width
            ZStack(alignment: .topLeading) {
                Color.clear
                // The bar: the TimelineView only ticks while the wave travels (zero cost when paused).
                TimelineView(.animation(paused: !waveMoves)) { context in
                    bar(phase: phase(at: context.date))
                }
                .frame(width: width, height: metrics.height)
                .frame(maxHeight: .infinity, alignment: .bottom)

                if let s = scrub {
                    bubble(for: s)
                        .alignmentGuide(.leading) { d in
                            -min(max(s * width - d.width / 2, 0), max(width - d.width, 0))
                        }
                        .transition(.scale(scale: 0.6, anchor: .bottom).combined(with: .opacity))
                }
            }
            .contentShape(Rectangle())
            .gesture(scrubGesture(width: width))
        }
        .frame(height: 40)
        .sensoryFeedback(.selection, trigger: seekCount)
        .sensoryFeedback(.impact(weight: .light, intensity: 0.6), trigger: captureTicks)
        .sensoryFeedback(.impact(weight: .medium), trigger: crossTicks)
        .onAppear { if waveMoves { phaseStart = .now } }
        .onChange(of: waveMoves) { _, moves in
            if moves {
                phaseStart = .now
            } else {
                // Freeze the phase where it is (M3 snaps it to 0 here; freezing has no jump).
                phaseBase = phase(at: .now)
                phaseStart = nil
            }
        }
        .onChange(of: wantsWave) { _, wave in
            withAnimation(wave ? WavyMotion.amplitudeIn : WavyMotion.amplitudeOut) {
                amplitude = wave ? 1 : 0
            }
        }
        .onChange(of: progress) { _, new in
            guard scrub == nil else { return }
            if !isPlaying || !live || abs(new - shown) > WavyMotion.snapThreshold {
                var t = Transaction()
                t.disablesAnimations = true
                withTransaction(t) { shown = new }
            } else {
                withAnimation(WavyMotion.glide) { shown = new }
            }
        }
        .accessibilityElement()
        .accessibilityLabel(Text("Playback position"))
        .accessibilityValue(Text("\(timeText(progress)) of \(timeText(1))"))
        .accessibilityAdjustableAction { direction in
            let step = duration > 0 ? min(10 / duration, 1) : 0.05
            switch direction {
            case .increment: onSeek(min(progress + step, 1))
            case .decrement: onSeek(max(progress - step, 0))
            @unknown default: break
            }
        }
    }

    @ViewBuilder
    private func bar(phase: CGFloat) -> some View {
        let p = CGFloat(displayed)
        let active = WavyActiveShape(progress: p, amplitude: amplitude, phase: phase, metrics: metrics)
        let trackOpacity = buffered > displayed + 0.02 ? 0.20 : 0.14
        ZStack {
            WavyTrackShape(progress: p, metrics: metrics)
                .stroke(
                    trackTint.opacity(trackOpacity),
                    style: StrokeStyle(lineWidth: metrics.trackStroke, lineCap: metrics.roundTrackCap ? .round : .butt)
                )
            if metrics.stopSize > 0 {
                stopDot(progress: p)
            }
            switch look {
            case .tint:
                active.stroke(tint, style: StrokeStyle(lineWidth: metrics.stroke, lineCap: .round, lineJoin: .round))
            case .glassTube:
                Color.clear.glassEffect(
                    .regular.tint(tint.opacity(0.55)),
                    in: active.stroke(style: StrokeStyle(lineWidth: metrics.stroke * 2.2, lineCap: .round, lineJoin: .round))
                )
            }
        }
    }

    private func stopDot(progress p: CGFloat) -> some View {
        GeometryReader { geo in
            let w = geo.size.width
            let x = w - metrics.stopSize / 2
            let headEdge = p * w + max(metrics.stroke, metrics.trackStroke) / 2
            let size = max(0, min(metrics.stopSize, x + metrics.stopSize / 2 - headEdge))
            Circle()
                .fill(tint)
                .frame(width: size, height: size)
                .position(x: x, y: geo.size.height / 2)
        }
    }

    private func bubble(for s: Double) -> some View {
        let onNote = isOnNote(s)
        // Android PreviewTimeBubble: labelLarge, tabular figures, in Google
        // Sans Flex (the system font read as a different app).
        return Text(onNote ? String(localized: "Note") : timeText(s))
            .font(.gsf(Tokens.type.labelLarge.size.cg, Font.Weight(gsf: Tokens.type.labelLarge.weight)))
            .tracking(Tokens.type.labelLarge.letterSpacing.cg)
            .monospacedDigit()
            .foregroundStyle(onNote ? noteInk : Color.primary)
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .glassEffect(.regular.tint(tint.opacity(onNote ? 0.9 : 0.35)), in: .capsule)
            .fixedSize()
            .animation(.spring(duration: 0.3, bounce: 0), value: onNote)
    }

    // MARK: Note anchors (WaveProgressBar.kt)

    /// "On a note": ±1.5 s of song, floored at 1 % of the bar.
    private var noteWindow: Double {
        duration > 0 ? max(1.5 / duration, 0.01) : 0
    }

    private func isOnNote(_ f: Double) -> Bool {
        noteAnchors.contains { abs($0 - f) <= noteWindow }
    }

    private func nearestAnchor(_ f: Double) -> Double? {
        noteAnchors.min { abs($0 - f) < abs($1 - f) }
    }

    /// Inside the inner half of the window the thumb sticks to the note; over
    /// the outer half it eases back to the finger on a t² ramp that meets the
    /// window's edge, so capture and escape are both continuous.
    private func magnetized(_ raw: Double) -> Double {
        let w = noteWindow
        guard let nearest = nearestAnchor(raw), w > 0 else { return raw }
        let d = raw - nearest, ad = abs(d), inner = w * 0.5
        if ad >= w { return raw }
        if ad <= inner { return nearest }
        let t = (ad - inner) / (w - inner)
        return min(max(nearest + (d < 0 ? -1 : 1) * w * t * t, 0), 1)
    }

    /// A light tick the moment a note captures the thumb, a full one for
    /// every note the finger sweeps across.
    private func tickAnchors(from before: Double?, to raw: Double) {
        guard !noteAnchors.isEmpty else { return }
        let captured = nearestAnchor(raw).flatMap { abs($0 - raw) <= noteWindow * 0.5 ? $0 : nil }
        if let captured, captured != snapped { captureTicks += 1 }
        snapped = captured
        if let before, before != raw {
            let lo = min(before, raw), hi = max(before, raw)
            if noteAnchors.contains(where: { $0 > lo && $0 <= hi }) { crossTicks += 1 }
        }
    }

    private func scrubGesture(width: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in
                let raw = min(max(value.location.x / max(width, 1), 0), 1)
                tickAnchors(from: rawScrub, to: raw)
                rawScrub = raw
                let f = magnetized(raw)
                if scrub == nil {
                    onScrubbing(true)
                    withAnimation(.spring(duration: 0.35, bounce: 0.3)) { scrub = f }
                } else {
                    scrub = f
                }
            }
            .onEnded { value in
                let f = magnetized(min(max(value.location.x / max(width, 1), 0), 1))
                rawScrub = nil
                snapped = nil
                var t = Transaction()
                t.disablesAnimations = true
                withTransaction(t) { shown = f }
                withAnimation(.spring(duration: 0.3, bounce: 0)) { scrub = nil }
                seekCount += 1
                onScrubbing(false)
                onSeek(f)
            }
    }

    private func phase(at date: Date) -> CGFloat {
        // phaseStart is nil whenever the wave stands still, so this also freezes it correctly
        // inside onChange(of: waveMoves), where waveMoves has already turned false.
        guard let start = phaseStart, metrics.wavelength > 0 else { return CGFloat(phaseBase) }
        let travelled = date.timeIntervalSince(start) * Double(metrics.waveSpeed / metrics.wavelength)
        return CGFloat((phaseBase + travelled).truncatingRemainder(dividingBy: 1))
    }

    private func timeText(_ fraction: Double) -> String {
        let total = Int((fraction * duration).rounded())
        return String(format: "%d:%02d", total / 60, total % 60)
    }
}

// MARK: - Preview

#Preview("Yoin seek bar") {
    @Previewable @State var playing = true
    @Previewable @State var position: Double = 0.42
    let duration: TimeInterval = 220
    let cover = Color(red: 0.36, green: 0.22, blue: 0.48) // stand-in for the cover colour

    ZStack {
        LinearGradient(colors: [cover, cover.opacity(0.75)], startPoint: .top, endPoint: .bottom)
            .ignoresSafeArea()
        VStack(alignment: .leading, spacing: 28) {
            Text("(a) tint wave · recommended").font(.caption).foregroundStyle(.white.opacity(0.7))
            WavyProgressBar(
                progress: position, buffered: 0.8, duration: duration, isPlaying: playing,
                tint: .white, trackTint: .white
            ) { position = $0 }

            Text("(b) glass tube").font(.caption).foregroundStyle(.white.opacity(0.7))
            WavyProgressBar(
                progress: position, duration: duration, isPlaying: playing,
                tint: .white, trackTint: .white, look: .glassTube
            ) { position = $0 }

            Text("M3E defaults, for comparison").font(.caption).foregroundStyle(.white.opacity(0.7))
            WavyProgressBar(
                progress: position, duration: duration, isPlaying: playing,
                tint: .white, trackTint: .white, metrics: .material, flattensWhileScrubbing: false
            ) { position = $0 }

            Button(playing ? String(localized: "Pause") : String(localized: "Play")) { playing.toggle() }
                .buttonStyle(.glassProminent)
                .tint(cover)
        }
        .padding(24)
    }
    // The player reports the position at 4 Hz, like Yoin's Android player.
    .task(id: playing) {
        while playing && !Task.isCancelled {
            try? await Task.sleep(for: .milliseconds(250))
            position = position >= 1 ? 0 : min(position + 0.25 / duration, 1)
        }
    }
}
