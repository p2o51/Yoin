import SwiftUI
import UIKit

/// Yoin 1.5pt rounded-cap stroke `mic_off` glyph (matches `yoin.mic` proportions with a clean diagonal slash).
struct SingMicOffGlyph: View {
    var size: CGFloat = 22

    var body: some View {
        Canvas { ctx, canvasSize in
            let s = min(canvasSize.width, canvasSize.height) / 24.0
            let stroke = StrokeStyle(lineWidth: 1.5 * s, lineCap: .round, lineJoin: .round)

            // Microphone capsule body (x: 9..15, y: 3..13, r: 3)
            var capsule = Path()
            capsule.addRoundedRect(
                in: CGRect(x: 9 * s, y: 3.2 * s, width: 6 * s, height: 9.8 * s),
                cornerSize: CGSize(width: 3 * s, height: 3 * s)
            )
            ctx.stroke(capsule, with: .foreground, style: stroke)

            // Cradle U-arc (x: 5.5..18.5, bottom at y: 16.5)
            var cradle = Path()
            cradle.addArc(
                center: CGPoint(x: 12 * s, y: 11 * s),
                radius: 5.75 * s,
                startAngle: .degrees(14),
                endAngle: .degrees(166),
                clockwise: false
            )
            ctx.stroke(cradle, with: .foreground, style: stroke)

            // Stem
            var stem = Path()
            stem.move(to: CGPoint(x: 12 * s, y: 16.8 * s))
            stem.addLine(to: CGPoint(x: 12 * s, y: 20.5 * s))
            ctx.stroke(stem, with: .foreground, style: stroke)

            // Diagonal slash (top-left to bottom-right)
            var slash = Path()
            slash.move(to: CGPoint(x: 4.5 * s, y: 4.5 * s))
            slash.addLine(to: CGPoint(x: 19.5 * s, y: 19.5 * s))
            ctx.stroke(slash, with: .foreground, style: stroke)
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

/// Full-Screen Lyrics 5th (LAST) control:
/// - Off: `keySize × keySize` rounded square (`keySize * 0.31`) matching `LyricKeyRow`
/// - Preparing: native `ProgressView` tinted `onPrimaryContainer` (tap cancels, zero popups)
/// - Failure: brief error-container tint flash + horizontal micro-shake (no modal/toast)
/// - Active: expands in-place on `Yoin.spatial` into a `124pt × keySize` short `Capsule` vocal slider
///   with a subtle `12%` detent notch, left mic tap-to-exit, two-step anti-accidental-dismiss left-drag,
///   and **zero percentage text**.
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

    @State private var gesture = SingCapsuleDismissGesture(dismissThresholdPt: 28)
    @State private var overpull: CGFloat = 0
    @State private var wasInDetent = false
    @State private var dragActive = false
    @State private var errorFlash = false
    @State private var errorShakeX: CGFloat = 0

    private var isActive: Bool { phase == .active }
    private var isPreparing: Bool { phase == .preparing }
    /// Exact flush width in Yoin's 364pt lyrics row (`4*52 + 4*8 + 124 = 364`), scaled for enlarged 44pt key.
    private var expandedWidth: CGFloat { keySize >= 50 ? 124 : 108 }
    private var cornerRadius: CGFloat { isActive ? keySize / 2 : keySize * 0.31 }

    var body: some View {
        Group {
            if isActive {
                activeCapsule
            } else {
                compactKey
            }
        }
        .frame(width: isActive ? expandedWidth : keySize, height: keySize)
        .offset(x: (isActive ? -overpull * 6 : 0) + errorShakeX)
        .animation(Yoin.spatial, value: isActive)
        .animation(Yoin.effects, value: isPreparing)
        .animation(Yoin.effects, value: errorFlash)
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
            ZStack {
                if isPreparing {
                    ProgressView()
                        .controlSize(.small)
                        .tint(fg)
                } else {
                    YoinIcon("mic", size: 22)
                        .foregroundStyle(fg)
                }
            }
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
                        switch gesture.drag(translationX: value.translation.width, trackWidth: trackW) {
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
/// Zero percentage text; bottom `yoin.mic` icon with fill-clipped dual-tone contrast + `12%` detent notch.
struct SingVocalVerticalSlider: View {
    var level: Float
    var scheme: CoverScheme
    var haptics: Bool = true
    var onLevelChange: (Float) -> Void

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

                // Subtle 12% detent notch
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
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        if !dragging {
                            dragging = true
                            wasInDetent = SingGainCurve.isInDetent(level)
                        }
                        let raw = Float(1.0 - value.location.y / h)
                        let snapped = SingGainCurve.snap(raw)
                        let inDetent = SingGainCurve.isInDetent(snapped)
                        if haptics && inDetent && !wasInDetent {
                            UISelectionFeedbackGenerator().selectionChanged()
                        }
                        wasInDetent = inDetent
                        onLevelChange(snapped)
                    }
                    .onEnded { _ in
                        dragging = false
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

/// Compact Now Playing exit button — morphs in-place from `heart` (`44pt` circle with `SingMicOffGlyph`).
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
                    ProgressView()
                        .controlSize(.small)
                        .tint(scheme.onPrimaryContainer)
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
