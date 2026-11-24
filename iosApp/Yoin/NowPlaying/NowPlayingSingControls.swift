import SwiftUI
import UIKit

/// Shared 24×24 Luminous geometry & cadence constants for the two approved Sing UI animations
/// (`A + 1`, locked 2026-11-24 — 1:1 parity with Android `SingMotionGeometry`):
/// - **A (Preparing · 拾音三柱)**: mic capsule morphs into 3 acoustic equalizer bars inside the steady
///   U-cradle & stem on a 4-beat spring+hold cadence, reuniting cleanly to the resting mic silhouette
///   before the active capsule expands.
/// - **1 (Enter / Exit · 麦头展成爱心)**: true 6-segment cubic Bézier point-to-point interpolation
///   between the Yoin mic capsule (`x: 9..15, y: 3.2..13`) and the exact `yoin_favorite.svg` heart
///   contour, paired with an `S·fast` retracting diagonal slash and gentle cradle fold.
enum SingMotionGeometry {
    static let viewBox: CGFloat = 24.0
    static let strokeWidth: CGFloat = 1.5

    struct CubicSeg: Equatable {
        let c1x, c1y, c2x, c2y, x, y: CGFloat
    }

    struct ClosedCubicPath: Equatable {
        let mx, my: CGFloat
        let segs: [CubicSeg]
    }

    struct PrepBeat: Equatable {
        let split: CGFloat
        let barL: CGFloat
        let barC: CGFloat
        let barR: CGFloat
        let holdMs: UInt64
    }

    /// Exact `docs/icons/svg/yoin_favorite.svg` 6-segment clockwise contour from bottom tip `(12, 19.35)`.
    static let heartClosed = ClosedCubicPath(
        mx: 12,
        my: 19.35,
        segs: [
            CubicSeg(c1x: 12, c1y: 19.35, c2x: 3.75, c2y: 14.55, x: 3.75, y: 9.15),
            CubicSeg(c1x: 3.75, c1y: 6.5, c2x: 5.7, c2y: 4.65, x: 8.05, y: 4.65),
            CubicSeg(c1x: 9.7, c1y: 4.65, c2x: 11.15, c2y: 5.5, x: 12, y: 6.95),
            CubicSeg(c1x: 12.85, c1y: 5.5, c2x: 14.3, c2y: 4.65, x: 15.95, y: 4.65),
            CubicSeg(c1x: 18.3, c1y: 4.65, c2x: 20.25, c2y: 6.5, x: 20.25, y: 9.15),
            CubicSeg(c1x: 20.25, c1y: 14.55, c2x: 12, c2y: 19.35, x: 12, y: 19.35),
        ]
    )

    /// Matching 6-segment clockwise contour for the Yoin mic capsule (`x: 9..15, y: 3.2..13, r: 3`),
    /// starting at bottom center `(12, 13)` so every segment aligns 1:1 with `heartClosed`.
    static let micCapsuleClosed = ClosedCubicPath(
        mx: 12,
        my: 13.0,
        segs: [
            CubicSeg(c1x: 10.343, c1y: 13.0, c2x: 9.0, c2y: 11.657, x: 9.0, y: 10.0),
            CubicSeg(c1x: 9.0, c1y: 8.73, c2x: 9.0, c2y: 7.47, x: 9.0, y: 6.2),
            CubicSeg(c1x: 9.0, c1y: 4.543, c2x: 10.343, c2y: 3.2, x: 12.0, y: 3.2),
            CubicSeg(c1x: 13.657, c1y: 3.2, c2x: 15.0, c2y: 4.543, x: 15.0, y: 6.2),
            CubicSeg(c1x: 15.0, c1y: 7.47, c2x: 15.0, c2y: 8.73, x: 15.0, y: 10.0),
            CubicSeg(c1x: 15.0, c1y: 11.657, c2x: 13.657, c2y: 13.0, x: 12.0, y: 13.0),
        ]
    )

    /// 4-beat preparing cadence: 3 expressive bar beats + 1 reunite beat back into the intact mic.
    static let prepBeats: [PrepBeat] = [
        PrepBeat(split: 1, barL: 3.2, barC: 9.2, barR: 5.2, holdMs: 240),
        PrepBeat(split: 1, barL: 7.6, barC: 3.8, barR: 8.2, holdMs: 240),
        PrepBeat(split: 1, barL: 4.4, barC: 8.8, barR: 3.4, holdMs: 240),
        PrepBeat(split: 0, barL: 3.2, barC: 8.8, barR: 3.2, holdMs: 340),
    ]

    static func lerp(_ a: CGFloat, _ b: CGFloat, _ t: CGFloat) -> CGFloat {
        a + (b - a) * t
    }

    static func interpolateMicToHeart(_ t: CGFloat) -> ClosedCubicPath {
        let a = micCapsuleClosed
        let b = heartClosed
        return ClosedCubicPath(
            mx: lerp(a.mx, b.mx, t),
            my: lerp(a.my, b.my, t),
            segs: zip(a.segs, b.segs).map { sa, sb in
                CubicSeg(
                    c1x: lerp(sa.c1x, sb.c1x, t),
                    c1y: lerp(sa.c1y, sb.c1y, t),
                    c2x: lerp(sa.c2x, sb.c2x, t),
                    c2y: lerp(sa.c2y, sb.c2y, t),
                    x: lerp(sa.x, sb.x, t),
                    y: lerp(sa.y, sb.y, t)
                )
            }
        )
    }

    static func path(for contour: ClosedCubicPath, scale s: CGFloat) -> Path {
        var p = Path()
        p.move(to: CGPoint(x: contour.mx * s, y: contour.my * s))
        for seg in contour.segs {
            p.addCurve(
                to: CGPoint(x: seg.x * s, y: seg.y * s),
                control1: CGPoint(x: seg.c1x * s, y: seg.c1y * s),
                control2: CGPoint(x: seg.c2x * s, y: seg.c2y * s)
            )
        }
        p.closeSubpath()
        return p
    }
}

/// Option A (**拾音三柱**) Sing preparing icon: replaces `ProgressView` with a living mic glyph
/// whose capsule splits into 3 acoustic bars inside the steady U-cradle & stem.
struct SingPreparingMicGlyph: View {
    var isPreparing: Bool
    var size: CGFloat = 22
    var fastSettle: Bool = false

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var split: CGFloat = 0
    @State private var barL: CGFloat = 3.2
    @State private var barC: CGFloat = 8.8
    @State private var barR: CGFloat = 3.2
    @State private var reducedOpacity: Double = 1

    var body: some View {
        SingPreparingMicCanvas(split: split, barL: barL, barC: barC, barR: barR)
            .opacity(reducedOpacity)
            .frame(width: size, height: size)
            .accessibilityHidden(true)
            .task(id: "\(isPreparing)-\(reduceMotion)") {
                if isPreparing {
                    if reduceMotion {
                        split = 0
                        var dim = true
                        while !Task.isCancelled {
                            withAnimation(Yoin.slowEffects) {
                                reducedOpacity = dim ? 0.42 : 1.0
                            }
                            try? await Task.sleep(for: .milliseconds(550))
                            dim.toggle()
                        }
                    } else {
                        reducedOpacity = 1
                        var step = 0
                        while !Task.isCancelled {
                            let beat = SingMotionGeometry.prepBeats[step % SingMotionGeometry.prepBeats.count]
                            withAnimation(Yoin.spatial) {
                                split = beat.split
                            }
                            withAnimation(Yoin.spatialFast) {
                                barL = beat.barL
                                barC = beat.barC
                                barR = beat.barR
                            }
                            try? await Task.sleep(for: .milliseconds(Int(beat.holdMs) + 190))
                            step += 1
                        }
                    }
                } else {
                    let settleAnim = fastSettle ? Yoin.spatialFast : Yoin.spatial
                    withAnimation(Yoin.effects) {
                        reducedOpacity = 1
                    }
                    withAnimation(settleAnim) {
                        split = 0
                        barL = 3.2
                        barC = 8.8
                        barR = 3.2
                    }
                }
            }
    }
}

private struct SingPreparingMicCanvas: View, @preconcurrency Animatable {
    var split: CGFloat
    var barL: CGFloat
    var barC: CGFloat
    var barR: CGFloat

    var animatableData: AnimatablePair<AnimatablePair<CGFloat, CGFloat>, AnimatablePair<CGFloat, CGFloat>> {
        get { AnimatablePair(AnimatablePair(split, barL), AnimatablePair(barC, barR)) }
        set {
            split = newValue.first.first
            barL = newValue.first.second
            barC = newValue.second.first
            barR = newValue.second.second
        }
    }

    var body: some View {
        Canvas { ctx, canvasSize in
            let s = min(canvasSize.width, canvasSize.height) / SingMotionGeometry.viewBox
            let stroke = StrokeStyle(lineWidth: SingMotionGeometry.strokeWidth * s, lineCap: .round, lineJoin: .round)

            // Steady U-cradle (center 12, 10.6; r = 5.7; bottom semicircle)
            var cradle = Path()
            cradle.addArc(
                center: CGPoint(x: 12 * s, y: 10.6 * s),
                radius: 5.7 * s,
                startAngle: .degrees(0),
                endAngle: .degrees(180),
                clockwise: false
            )
            ctx.stroke(cradle, with: .foreground, style: stroke)

            // Steady stem
            var stem = Path()
            stem.move(to: CGPoint(x: 12 * s, y: 16.4 * s))
            stem.addLine(to: CGPoint(x: 12 * s, y: 20.5 * s))
            ctx.stroke(stem, with: .foreground, style: stroke)

            let sp = max(0, min(1, split))
            let capsuleAlpha = max(0, min(1, 1 - sp * 1.6))
            let barsAlpha = max(0, min(1, (sp - 0.15) / 0.85))

            if capsuleAlpha > 0.01 {
                let sx = 1 - 0.12 * sp
                let sy = 1 - 0.16 * sp
                let cx = 12 * s
                let cy = 8.1 * s
                var capCtx = ctx
                capCtx.opacity = capsuleAlpha
                capCtx.translateBy(x: cx, y: cy)
                capCtx.scaleBy(x: sx, y: sy)
                capCtx.translateBy(x: -cx, y: -cy)
                let capPath = SingMotionGeometry.path(for: SingMotionGeometry.micCapsuleClosed, scale: s)
                capCtx.stroke(capPath, with: .foreground, style: stroke)
            }

            if barsAlpha > 0.01 {
                var barCtx = ctx
                barCtx.opacity = barsAlpha
                let cy = 8.1 * s
                let drawBar: (CGFloat, CGFloat) -> Void = { x, h in
                    let half = max(0.4, h * 0.5) * s
                    var line = Path()
                    line.move(to: CGPoint(x: x * s, y: cy - half))
                    line.addLine(to: CGPoint(x: x * s, y: cy + half))
                    barCtx.stroke(line, with: .foreground, style: stroke)
                }
                drawBar(9, barL)
                drawBar(12, barC)
                drawBar(15, barR)
            }
        }
    }
}

/// Option 1 (**麦头展成爱心**) 120Hz Animatable 6-cubic Bézier morph between `MicOff` (`morph = 0`)
/// and `yoin_favorite` Heart (`morph = 1`), with independent `slash` and `fillAlpha`.
struct SingHeartMicMorphGlyph: View, @preconcurrency Animatable {
    var morph: CGFloat
    var slash: CGFloat
    var fillAlpha: CGFloat
    var size: CGFloat = 22

    var animatableData: AnimatablePair<AnimatablePair<CGFloat, CGFloat>, CGFloat> {
        get { AnimatablePair(AnimatablePair(morph, slash), fillAlpha) }
        set {
            morph = newValue.first.first
            slash = newValue.first.second
            fillAlpha = newValue.second
        }
    }

    var body: some View {
        Canvas { ctx, canvasSize in
            let s = min(canvasSize.width, canvasSize.height) / SingMotionGeometry.viewBox
            let clamped = max(0, min(1, morph))
            let sl = max(0, min(1, slash))
            let fo = max(0, min(1, fillAlpha))
            let stroke = StrokeStyle(lineWidth: SingMotionGeometry.strokeWidth * s, lineCap: .round, lineJoin: .round)

            let contour = SingMotionGeometry.interpolateMicToHeart(morph)
            let morphPath = SingMotionGeometry.path(for: contour, scale: s)

            // Optional heart fill blooming from center for favorited tracks
            if fo > 0.01 {
                let fs = SingMotionGeometry.lerp(0.5, 1.0, fo)
                var fillCtx = ctx
                fillCtx.opacity = fo
                fillCtx.translateBy(x: 12 * s, y: 12 * s)
                fillCtx.scaleBy(x: fs, y: fs)
                fillCtx.translateBy(x: -12 * s, y: -12 * s)
                fillCtx.fill(morphPath, with: .foreground)
            }

            // Cradle + stem gently fold toward bottom tip (12, 19.35)
            let auxAlpha = max(0, min(1, 1 - clamped * 1.65))
            if auxAlpha > 0.01 {
                var auxCtx = ctx
                auxCtx.opacity = auxAlpha
                let auxScale = SingMotionGeometry.lerp(1.0, 0.55, clamped)
                let auxTy = SingMotionGeometry.lerp(0, 3.2, clamped) * s
                var cradleCtx = auxCtx
                cradleCtx.translateBy(x: 12 * s, y: 14 * s + auxTy)
                cradleCtx.scaleBy(x: auxScale, y: auxScale)
                cradleCtx.translateBy(x: -12 * s, y: -14 * s)
                var cradle = Path()
                cradle.addArc(
                    center: CGPoint(x: 12 * s, y: 10.6 * s),
                    radius: 5.7 * s,
                    startAngle: .degrees(0),
                    endAngle: .degrees(180),
                    clockwise: false
                )
                cradleCtx.stroke(cradle, with: .foreground, style: stroke)

                var stem = Path()
                stem.move(to: CGPoint(x: 12 * s, y: SingMotionGeometry.lerp(16.4, 19.2, clamped) * s))
                stem.addLine(to: CGPoint(x: 12 * s, y: SingMotionGeometry.lerp(20.5, 19.35, clamped) * s))
                auxCtx.stroke(stem, with: .foreground, style: stroke)
            }

            // Main morphing stroke (Mic capsule ↔ Heart contour)
            ctx.stroke(morphPath, with: .foreground, style: stroke)

            // Diagonal slash retracts cleanly toward top-left on S·fast
            if sl > 0.03 {
                var slashPath = Path()
                slashPath.move(to: CGPoint(x: 4.5 * s, y: 4.5 * s))
                slashPath.addLine(to: CGPoint(
                    x: SingMotionGeometry.lerp(4.5, 19.5, sl) * s,
                    y: SingMotionGeometry.lerp(4.5, 19.5, sl) * s
                ))
                ctx.stroke(slashPath, with: .foreground, style: stroke)
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

/// Yoin 1.5pt rounded-cap stroke `mic_off` glyph (matches `yoin.mic` proportions with a clean diagonal slash).
struct SingMicOffGlyph: View {
    var size: CGFloat = 22

    var body: some View {
        SingHeartMicMorphGlyph(morph: 0, slash: 1, fillAlpha: 0, size: size)
    }
}

/// Full-Screen Lyrics 5th (LAST) control:
/// - Off: `keySize × keySize` rounded square (`keySize * 0.31`) matching `LyricKeyRow`
/// - Preparing: native `ProgressView` tinted `onPrimaryContainer` (tap cancels, zero popups)
/// - Failure: brief error-container tint flash + horizontal micro-shake (no modal/toast)
/// - Active: expands in-place on `Yoin.spatial` into a `124pt × keySize` short `Capsule` vocal slider
///   with a subtle default-detent notch (~1/3 travel), left mic tap-to-exit, single-gesture right-overpull
///   past 100% to return to original vocals, and **zero percentage text**.
struct SingLyricsCapsuleKey: View {
    var phase: SingSessionController.Phase
    var level: Float
    var failureTick: Int = 0
    var scheme: CoverScheme
    var keySize: CGFloat = 52
    var haptics: Bool = true
    var onToggle: () -> Void
    var onLevelChange: (Float) -> Void
    var onExit: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var gesture = SingCapsuleDismissGesture(dismissThresholdPt: 28)
    @State private var overpull: CGFloat = 0
    @State private var wasInDetent = false
    @State private var dragActive = false
    @State private var errorFlash = false
    @State private var errorShakeX: CGFloat = 0
    @State private var holdExpand = false

    private var isActive: Bool { phase == .active }
    private var isPreparing: Bool { phase == .preparing }
    private var capsuleExpanded: Bool { isActive && !holdExpand }
    /// Exact flush width in Yoin's 364pt lyrics row (`4*52 + 4*8 + 124 = 364`), scaled for enlarged 44pt key.
    private var expandedWidth: CGFloat { keySize >= 50 ? 124 : 108 }
    private var cornerRadius: CGFloat { capsuleExpanded ? keySize / 2 : keySize * 0.31 }

    var body: some View {
        Group {
            if capsuleExpanded {
                activeCapsule
            } else {
                compactKey
            }
        }
        .frame(width: capsuleExpanded ? expandedWidth : keySize, height: keySize)
        .offset(x: (capsuleExpanded ? overpull * 6 : 0) + errorShakeX)
        .animation(Yoin.spatial, value: capsuleExpanded)
        .animation(Yoin.effects, value: isPreparing)
        .animation(Yoin.effects, value: errorFlash)
        .onChange(of: phase) { oldPhase, newPhase in
            if oldPhase == .preparing && newPhase == .active && !reduceMotion {
                // Fast-settle the preparing mic bars back into the resting mic silhouette (~130ms)
                // before expanding into the active vocal capsule.
                holdExpand = true
            } else {
                holdExpand = false
            }
        }
        .task(id: holdExpand) {
            guard holdExpand else { return }
            try? await Task.sleep(for: .milliseconds(130))
            guard !Task.isCancelled else { return }
            withAnimation(Yoin.spatial) {
                holdExpand = false
            }
        }
        .task(id: failureTick) {
            guard failureTick > 0 else { return }
            if haptics { UINotificationFeedbackGenerator().notificationOccurred(.error) }
            errorFlash = true
            withAnimation(Yoin.spatialFast) { errorShakeX = -5 }
            try? await Task.sleep(for: .milliseconds(70))
            withAnimation(Yoin.spatialFast) { errorShakeX = 5 }
            try? await Task.sleep(for: .milliseconds(70))
            withAnimation(Yoin.spatialFast) { errorShakeX = -3 }
            try? await Task.sleep(for: .milliseconds(70))
            withAnimation(Yoin.spatial) { errorShakeX = 0 }
            try? await Task.sleep(for: .milliseconds(260))
            errorFlash = false
        }
    }

    private var compactKey: some View {
        let bg = errorFlash ? scheme.errorContainer : scheme.primaryContainer
        let fg = errorFlash ? scheme.onErrorContainer : scheme.onPrimaryContainer
        return Button {
            if haptics { UISelectionFeedbackGenerator().selectionChanged() }
            onToggle()
        } label: {
            SingPreparingMicGlyph(
                isPreparing: isPreparing,
                size: 22,
                fastSettle: holdExpand
            )
            .foregroundStyle(fg)
            .frame(width: keySize, height: keySize)
            .background(bg, in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
        }
        .buttonStyle(PressScale())
        .accessibilityLabel(String(localized: "Sing"))
        .accessibilityValue(isPreparing ? String(localized: "Preparing") : String(localized: "Off"))
    }

    private var activeCapsule: some View {
        let clamped = CGFloat(max(0, min(1, level)))
        let detentFraction = CGFloat(SingGainCurve.defaultSlider)
        let micZoneWidth: CGFloat = 34

        return GeometryReader { geo in
            let w = max(1, geo.size.width)
            let h = geo.size.height
            let fillWidth = max(0, min(w, w * clamped))

            ZStack(alignment: .leading) {
                // High-contrast track matching RatingBar (`surfaceVariant` vs `primary` fill)
                Capsule(style: .continuous)
                    .fill(scheme.surfaceVariant)

                // Flat-edged live vocal fill clipped cleanly by the outer Capsule — zero distortion near 0%.
                Rectangle()
                    .fill(scheme.primary)
                    .frame(width: fillWidth, height: h)
                    .animation(dragActive ? nil : Yoin.spatial, value: clamped)

                // Subtle 12% detent notch
                Capsule(style: .continuous)
                    .fill(clamped >= detentFraction ? scheme.onPrimary.opacity(0.48) : scheme.onSurfaceVariant.opacity(0.36))
                    .frame(width: 2, height: 10)
                    .offset(x: max(micZoneWidth, w * detentFraction) - 1)

                // Left mic glyph: dual-tone clip over the flat fill edge so legibility stays crisp at any vocal level.
                micGlyph(color: scheme.onSurfaceVariant)
                micGlyph(color: scheme.onPrimary)
                    .mask(alignment: .leading) {
                        Rectangle().frame(width: fillWidth, height: h)
                    }
            }
            .clipShape(Capsule(style: .continuous))
            .contentShape(Capsule(style: .continuous))
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        if !dragActive {
                            dragActive = true
                            gesture.begin(currentLevel: level)
                            wasInDetent = SingGainCurve.isInDetent(level)
                        }
                        // Small movement: wait until past slop before scrubbing so a clean tap on the left mic exits.
                        let dist = hypot(value.translation.width, value.translation.height)
                        if dist < 5 { return }

                        let trackW = max(1, w - 16)
                        switch gesture.drag(deltaPt: value.translation.width, trackLength: trackW) {
                        case .update(let nextLevel, let pull):
                            overpull = pull
                            let inDetent = SingGainCurve.isInDetent(nextLevel)
                            if haptics && inDetent && !wasInDetent {
                                UISelectionFeedbackGenerator().selectionChanged()
                            }
                            wasInDetent = inDetent
                            onLevelChange(nextLevel)
                        case .dismiss:
                            if haptics {
                                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                            }
                            overpull = 0
                            dragActive = false
                            gesture.end()
                            onExit()
                        }
                    }
                    .onEnded { value in
                        let dist = hypot(value.translation.width, value.translation.height)
                        let wasDismissed = gesture.dismissed
                        gesture.end()
                        dragActive = false
                        withAnimation(Yoin.spatial) { overpull = 0 }
                        if !wasDismissed && dist < 8 && value.startLocation.x <= micZoneWidth {
                            // Clean tap on the left mic icon exits Sing immediately.
                            if haptics { UISelectionFeedbackGenerator().selectionChanged() }
                            onExit()
                        }
                    }
            )
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(String(localized: "Vocal volume"))
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: onLevelChange(min(1, level + 0.1))
            case .decrement: onLevelChange(max(0, level - 0.1))
            @unknown default: break
            }
        }
    }

    private func micGlyph(color: Color) -> some View {
        YoinIcon("mic", size: 18)
            .foregroundStyle(color)
            .frame(width: 34, height: keySize)
            .allowsHitTesting(false)
    }
}

/// Compact Now Playing vertical vocal slider — morphs in-place from `RatingBar` (`48pt` wide Capsule).
/// Zero percentage text; bottom `yoin.mic` icon with fill-clipped dual-tone contrast + default detent notch (~1/3).
/// Dragging down to 0% stops cleanly at pure instrumental; dragging up past 100% in a single gesture
/// overpulls elastically and exits Sing back to original vocals.
struct SingVocalVerticalSlider: View {
    var level: Float
    var scheme: CoverScheme
    var haptics: Bool = true
    var onLevelChange: (Float) -> Void
    var onExit: () -> Void = {}

    @State private var gesture = SingCapsuleDismissGesture(dismissThresholdPt: 28)
    @State private var overpull: CGFloat = 0
    @State private var dragging = false
    @State private var wasInDetent = false

    var body: some View {
        let clamped = CGFloat(max(0, min(1, level)))
        let detentFraction = CGFloat(SingGainCurve.defaultSlider)

        GeometryReader { geo in
            let w = geo.size.width
            let h = max(1, geo.size.height)
            let fillHeight = max(0, min(h, h * clamped))

            ZStack(alignment: .bottom) {
                Capsule(style: .continuous)
                    .fill(scheme.surfaceVariant)

                // Flat-edged fill clipped by the outer Capsule — disappears cleanly at 0% with zero bowl distortion.
                Rectangle()
                    .fill(scheme.primary)
                    .frame(width: w, height: fillHeight)
                    .animation(dragging ? nil : Yoin.spatial, value: clamped)

                // Subtle default detent notch (~34%)
                Capsule(style: .continuous)
                    .fill(clamped >= detentFraction ? scheme.onPrimary.opacity(0.48) : scheme.onSurfaceVariant.opacity(0.34))
                    .frame(width: 12, height: 2)
                    .offset(y: -max(36, h * detentFraction))

                // Bottom mic glyph (dual-tone clipped along the flat live fill edge)
                bottomMic(color: scheme.onSurfaceVariant)
                bottomMic(color: scheme.onPrimary)
                    .mask(alignment: .bottom) {
                        Rectangle().frame(width: w, height: fillHeight)
                    }
            }
            .clipShape(Capsule(style: .continuous))
            .contentShape(Capsule(style: .continuous))
            .offset(y: -overpull * 6)
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        if !dragging {
                            dragging = true
                            gesture.begin(currentLevel: level)
                            wasInDetent = SingGainCurve.isInDetent(level)
                        }
                        switch gesture.dragVerticalAbsolute(locationY: value.location.y, trackHeight: h) {
                        case .update(let snapped, let pull):
                            overpull = pull
                            let inDetent = SingGainCurve.isInDetent(snapped)
                            if haptics && inDetent && !wasInDetent {
                                UISelectionFeedbackGenerator().selectionChanged()
                            }
                            wasInDetent = inDetent
                            onLevelChange(snapped)
                        case .dismiss:
                            if haptics {
                                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                            }
                            overpull = 0
                            dragging = false
                            gesture.end()
                            onExit()
                        }
                    }
                    .onEnded { _ in
                        gesture.end()
                        dragging = false
                        withAnimation(Yoin.spatial) { overpull = 0 }
                    }
            )
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(String(localized: "Vocal volume"))
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: onLevelChange(min(1, level + 0.1))
            case .decrement: onLevelChange(max(0, level - 0.1))
            @unknown default: break
            }
        }
    }

    private func bottomMic(color: Color) -> some View {
        YoinIcon("mic", size: 20)
            .foregroundStyle(color)
            .padding(.bottom, 12)
            .allowsHitTesting(false)
    }
}

/// Compact Now Playing exit button — morphs in-place from `heart` (`44pt` circle with `SingMicOffGlyph`
/// or `SingPreparingMicGlyph` when preparing).
struct SingExitCircleButton: View {
    var scheme: CoverScheme
    var isPreparing: Bool = false
    var haptics: Bool = true
    var onExit: () -> Void

    var body: some View {
        Button {
            if haptics { UISelectionFeedbackGenerator().selectionChanged() }
            onExit()
        } label: {
            ZStack {
                if isPreparing {
                    SingPreparingMicGlyph(isPreparing: true, size: 22)
                        .foregroundStyle(scheme.onPrimaryContainer)
                } else {
                    SingMicOffGlyph(size: 22)
                        .foregroundStyle(scheme.onPrimaryContainer)
                }
            }
            .frame(width: Tokens.np.HEART_SIZE.cg, height: Tokens.np.HEART_SIZE.cg)
            .background(scheme.primaryContainer, in: Circle())
            .contentShape(Circle())
        }
        .buttonStyle(PressScale())
        .accessibilityLabel(String(localized: "Exit Sing"))
    }
}

/// Option 1 (**麦头展成爱心**) true 6-cubic Bézier in-place morph between the Favorite heart button
/// and `SingExitCircleButton`. Keeps the real `heart` mounted at rest (preserving Liquid Glass,
/// glow anchors, and hit-testing) and plays the 120Hz Animatable path morph across transitions.
struct SingAwareFavoriteSlot<HeartContent: View>: View {
    var showSing: Bool
    var isPreparing: Bool
    var isFavorite: Bool
    var isEpisode: Bool
    var heartKnown: Bool
    var scheme: CoverScheme
    var haptics: Bool = true
    var onExit: () -> Void
    @ViewBuilder var heart: () -> HeartContent

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var isMorphing = false
    @State private var morphToken = 0

    var body: some View {
        let morphTarget: CGFloat = showSing ? 0 : 1
        let slashTarget: CGFloat = (showSing && !isPreparing) ? 1 : 0
        let fillTarget: CGFloat = (!showSing && isFavorite) ? 1 : 0
        let morphBg = showSing ? scheme.primaryContainer : (isFavorite ? scheme.tertiary : scheme.tertiaryContainer)
        let morphFg = showSing ? scheme.onPrimaryContainer : (isFavorite ? scheme.onTertiary : scheme.onTertiaryContainer)

        ZStack {
            heart()
                .opacity(showSing || isMorphing ? 0 : (isEpisode ? 0 : heartKnown ? 1 : 0.38))
                .allowsHitTesting(!showSing && !isEpisode && heartKnown)
                .animation(Yoin.effects, value: isEpisode || !heartKnown)

            SingExitCircleButton(
                scheme: scheme,
                isPreparing: isPreparing,
                haptics: haptics,
                onExit: onExit
            )
            .opacity(showSing && !isMorphing ? 1 : 0)
            .allowsHitTesting(showSing)

            // Always mounted so the 6-cubic morph begins on frame 0 with zero flicker.
            ZStack {
                Circle()
                    .fill(morphBg)
                    .animation(Yoin.effects, value: showSing)
                    .animation(Yoin.effects, value: isFavorite)
                SingHeartMicMorphGlyph(
                    morph: morphTarget,
                    slash: slashTarget,
                    fillAlpha: fillTarget,
                    size: 22
                )
                .foregroundStyle(morphFg)
                .animation(reduceMotion ? Yoin.effects : Yoin.spatial, value: morphTarget)
                .animation(reduceMotion ? Yoin.effects : Yoin.standardFastSpatial, value: slashTarget)
                .animation(Yoin.effects, value: fillTarget)
                .animation(Yoin.effects, value: showSing)
            }
            .frame(width: Tokens.np.HEART_SIZE.cg, height: Tokens.np.HEART_SIZE.cg)
            .opacity(isMorphing ? 1 : 0)
            .allowsHitTesting(false)
        }
        .frame(width: Tokens.np.HEART_SIZE.cg, height: Tokens.np.HEART_SIZE.cg)
        .onChange(of: showSing) { _, _ in
            isMorphing = true
            morphToken += 1
        }
        .task(id: morphToken) {
            guard morphToken > 0 else { return }
            let durationMs = reduceMotion ? 200 : 420
            try? await Task.sleep(for: .milliseconds(durationMs))
            guard !Task.isCancelled else { return }
            isMorphing = false
        }
    }
}
