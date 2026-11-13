import SwiftUI
import UIKit.UIGestureRecognizerSubclass
import YoinShared

/*
 * The tip page's demo phone (Android ui/tips/TipDemoPhone.kt). Its screen is laid out at a real phone's
 * size (`DemoPhoneMetrics.screenWidth` wide) out of the app's own views, then drawn scaled down to the
 * 168 × 300 frame, so every view is the one in the app at its true proportions. The scale is on the
 * screen, so a press lands on whatever is drawn under it.
 *
 * A touch dot acts the gesture out on a loop (tap: a ripple; hold: its ring fills; drag) and the phone
 * answers the way the app does. Once the user touches the phone the demo steps aside and the phone is
 * theirs; left alone for `DemoTiming.takeoverIdleMs`, the demo starts over.
 */

// MARK: - Numbers (Android TipDemoPhone.kt)

/// The frame's sizes: the phone on the page and the screen laid out inside it.
struct DemoPhoneMetrics: Equatable {
    /// The phone on the page, before it grows.
    static let phoneWidth: CGFloat = 168
    static let phoneHeight: CGFloat = 300
    /// The demo screen's width: a common phone's.
    static let screenWidth: CGFloat = 360
    /// Width over height of a `tall` frame (20:9), and its screen's width (a 20:9 phone's).
    static let tallAspect: CGFloat = 9.0 / 20.0
    static let tallScreenWidth: CGFloat = 412
    /// The phone's own corner, on the page (not scaled with its screen).
    static let corner: CGFloat = 24
    /// How much of the container's height the phone may take before it stops growing, and its largest size.
    static let heightShare: CGFloat = 0.36
    static let maxGrow: CGFloat = 1.7
    /// The touch dot's radius, before it grows.
    static let fingerRadius: CGFloat = 14
    /// What the screen keeps free at its top, as a status bar does (Android's scenes pad by the window's own).
    static let statusBar: CGFloat = 28

    /// The phone as drawn.
    var phone: CGSize
    /// The screen as laid out.
    var screen: CGSize
    var grow: CGFloat
    /// Screen points to page points.
    var scale: CGFloat { phone.width / screen.width }

    /// [container] is the page the phone sits on (Android: the window): a tall one gets a bigger phone, the
    /// demo being the page's subject; width keeps it to half the container. [tall]: a 20:9 phone as tall as
    /// the others (narrower), for a scene whose layout differs on a 16:9 screen.
    init(container: CGSize, tall: Bool = false) {
        let width = tall ? Self.phoneHeight * Self.tallAspect : Self.phoneWidth
        let layoutWidth = tall ? Self.tallScreenWidth : Self.screenWidth
        let fit = min(container.height * Self.heightShare / Self.phoneHeight, container.width * 0.5 / width)
        grow = min(max(fit, 1), Self.maxGrow)
        phone = CGSize(width: width * grow, height: Self.phoneHeight * grow)
        screen = CGSize(width: layoutWidth, height: (layoutWidth * Self.phoneHeight / width).rounded())
    }
}

enum DemoTiming {
    /// The long-press time the hold ring fills over.
    static let holdMs = 550
    static let startDelayMs = 500
    static let loopGapMs = 700
    /// Untouched this long, the phone is the demo's again.
    static let takeoverIdleMs = 5_000
    static let appearMs = 180
    static let tapDownMs = 70
    static let rippleMs = 420
    static let holdDownMs = 90
    static let dragDownMs = 80
    static let liftMs = 220
    /// Letting go of a hold the finger slid from (the account column's).
    static let releaseMs = 120

    /// Android's `YoinMotion.spatialSpring` (the Expressive default spatial spring).
    static var spring: Spring {
        let s = Tokens.motion.expressiveDefaultSpatial
        return Spring(mass: 1, stiffness: s.stiffness, damping: s.damping * 2 * s.stiffness.squareRoot())
    }

    /// How long the script waits for [spring] to come to rest.
    static var springMs: Int { Int((spring.settlingDuration * 1000).rounded()) }

    static var springAnimation: Animation { .interpolatingSpring(spring).speed(LaunchConfig.animationSpeed) }

    /// Compose's `tween(ms)`: FastOutSlowIn.
    static func tween(_ ms: Int) -> Animation {
        .timingCurve(0.4, 0, 0.2, 1, duration: Double(ms) / 1000).speed(LaunchConfig.animationSpeed)
    }

    /// A clock: linear.
    static func linear(_ ms: Int) -> Animation {
        .linear(duration: Double(ms) / 1000).speed(LaunchConfig.animationSpeed)
    }

    /// The script's `delay(ms)`. Throws when the demo stops (its page left, the user took the phone).
    static func delay(_ ms: Int) async throws {
        try await Task.sleep(for: .milliseconds(Int(Double(ms) / LaunchConfig.animationSpeed)))
    }
}

// MARK: - Targets

/// Where the scene's named parts are, for the demo touch to go to.
@MainActor
final class DemoTargets {
    static let space = "tipDemoScreen"

    /// The screen as laid out.
    var screen: CGSize = .zero
    private var parts: [String: CGRect] = [:]

    func report(_ key: String, _ frame: CGRect) { parts[key] = frame }

    /// Part [key]'s frame on the screen as laid out; nil until it is.
    func rect(_ key: String) -> CGRect? { parts[key] }

    /// The point ([fx], [fy]) of part [key] (0…1 across it), moved by ([dx], [dy]) points of the screen, as
    /// fractions of the screen. Nil until laid out.
    func fraction(_ key: String, fx: CGFloat, fy: CGFloat, dx: CGFloat = 0, dy: CGFloat = 0) -> CGPoint? {
        Self.fraction(of: parts[key], in: screen, fx: fx, fy: fy, dx: dx, dy: dy)
    }

    nonisolated static func fraction(of part: CGRect?, in screen: CGSize, fx: CGFloat, fy: CGFloat,
                                     dx: CGFloat = 0, dy: CGFloat = 0) -> CGPoint? {
        guard let part, screen.width > 0, screen.height > 0 else { return nil }
        return CGPoint(x: (part.minX + part.width * fx + dx) / screen.width,
                       y: (part.minY + part.height * fy + dy) / screen.height)
    }
}

extension EnvironmentValues {
    /// The demo phone a view is drawn in; nil in the app itself.
    @Entry var demoTargets: DemoTargets? = nil
    /// The page the demo phone sits on, for its size.
    @Entry var tipContainer: CGSize = .zero
}

private struct DemoTarget: ViewModifier {
    @Environment(\.demoTargets) private var targets
    var key: String

    func body(content: Content) -> some View {
        if let targets {
            content.onGeometryChange(for: CGRect.self) { $0.frame(in: .named(DemoTargets.space)) } action: {
                targets.report(key, $0)
            }
        } else {
            content
        }
    }
}

extension View {
    /// Names this view [key] for the demo touch. Nothing outside a demo phone.
    func demoTarget(_ key: String) -> some View { modifier(DemoTarget(key: key)) }
}

// MARK: - The touch

/// The demo's touch: where it is (fractions of the phone), whether it is down, its hold ring.
@MainActor
@Observable
final class DemoFinger {
    private(set) var x: CGFloat = 0.5
    private(set) var y: CGFloat = 0.5
    private(set) var alpha: Double = 0
    private(set) var press: CGFloat = 0
    private(set) var ring: CGFloat = 0
    private(set) var ripple: CGFloat = 1
    @ObservationIgnored let targets: DemoTargets

    init(targets: DemoTargets) { self.targets = targets }

    /// Where part [key] is (see `DemoTargets.fraction`); the phone's middle until it is laid out.
    func at(_ key: String, fx: CGFloat = 0.5, fy: CGFloat = 0.5, dx: CGFloat = 0, dy: CGFloat = 0) -> CGPoint {
        targets.fraction(key, fx: fx, fy: fy, dx: dx, dy: dy) ?? CGPoint(x: 0.5, y: 0.5)
    }

    func moveTo(_ key: String, fx: CGFloat = 0.5, fy: CGFloat = 0.5, dx: CGFloat = 0, dy: CGFloat = 0) async throws {
        let p = at(key, fx: fx, fy: fy, dx: dx, dy: dy)
        try await moveTo(x: p.x, y: p.y)
    }

    func moveTo(x fx: CGFloat, y fy: CGFloat) async throws {
        if alpha < 0.05 {
            snap { x = fx; y = fy }
            try await frame()
            withAnimation(DemoTiming.tween(DemoTiming.appearMs)) { alpha = 1 }
            try await DemoTiming.delay(DemoTiming.appearMs)
        } else {
            withAnimation(DemoTiming.springAnimation) { x = fx; y = fy }
            try await DemoTiming.delay(DemoTiming.springMs)
        }
    }

    func tap(_ action: () -> Void) async throws {
        withAnimation(DemoTiming.tween(DemoTiming.tapDownMs)) { press = 1 }
        try await DemoTiming.delay(DemoTiming.tapDownMs)
        action()
        snap { ripple = 0 }
        try await frame()
        withAnimation(DemoTiming.tween(DemoTiming.rippleMs)) { ripple = 1 }
        withAnimation(DemoTiming.springAnimation) { press = 0 }
        try await DemoTiming.delay(max(DemoTiming.rippleMs, DemoTiming.springMs))
    }

    /// Holds for the long-press time (the ring fills), then [action]; [pressed] is told down and up.
    func hold(pressed: (Bool) -> Void = { _ in }, _ action: () -> Void) async throws {
        withAnimation(DemoTiming.tween(DemoTiming.holdDownMs)) { press = 1 }
        try await DemoTiming.delay(DemoTiming.holdDownMs)
        pressed(true)
        snap { ring = 0 }
        try await frame()
        withAnimation(DemoTiming.linear(DemoTiming.holdMs)) { ring = 1 }
        try await DemoTiming.delay(DemoTiming.holdMs)
        action()
        snap { ring = 0 }
        pressed(false)
        withAnimation(DemoTiming.springAnimation) { press = 0 }
        try await DemoTiming.delay(DemoTiming.springMs)
    }

    /// Down and held where it is for the long-press time (the ring fills), and still down after: a hold the
    /// finger then slides from. `release` lets go.
    func pressAndHold() async throws {
        withAnimation(DemoTiming.tween(DemoTiming.holdDownMs)) { press = 1 }
        try await DemoTiming.delay(DemoTiming.holdDownMs)
        snap { ring = 0 }
        try await frame()
        withAnimation(DemoTiming.linear(DemoTiming.holdMs)) { ring = 1 }
        try await DemoTiming.delay(DemoTiming.holdMs)
        snap { ring = 0 }
    }

    /// Lets go of a `pressAndHold`.
    func release() async throws {
        withAnimation(DemoTiming.tween(DemoTiming.releaseMs)) { press = 0 }
        try await DemoTiming.delay(DemoTiming.releaseMs)
    }

    /// Down where it is, slide to ([fx], [fy]) over [ms]. What [along] changes rides the same curve: the
    /// scene's own value, moved with the finger.
    func drag(toX fx: CGFloat, toY fy: CGFloat, ms: Int, along: () -> Void = {}) async throws {
        withAnimation(DemoTiming.tween(DemoTiming.dragDownMs)) { press = 1 }
        try await DemoTiming.delay(DemoTiming.dragDownMs)
        withAnimation(DemoTiming.tween(ms)) {
            x = fx
            y = fy
            along()
        }
        try await DemoTiming.delay(ms)
        withAnimation(DemoTiming.springAnimation) { press = 0 }
        try await DemoTiming.delay(DemoTiming.springMs)
    }

    func lift() async throws {
        withAnimation(DemoTiming.tween(DemoTiming.liftMs)) { alpha = 0 }
        try await DemoTiming.delay(DemoTiming.liftMs)
    }

    func hide() {
        snap {
            alpha = 0
            press = 0
            ring = 0
            ripple = 1
        }
    }

    private func snap(_ change: () -> Void) {
        var t = Transaction()
        t.disablesAnimations = true
        withTransaction(t, change)
    }

    /// One frame, so a value snapped to its start is drawn there before it animates on.
    private func frame() async throws { try await Task.sleep(for: .milliseconds(17)) }
}

/// The touch as drawn: a soft disc and a dot, the tap's ripple, the hold's ring.
private struct DemoFingerView: View {
    var finger: DemoFinger
    var grow: CGFloat
    var color: Color

    var body: some View {
        GeometryReader { geo in
            let r = DemoPhoneMetrics.fingerRadius * grow
            let down = 1 - 0.15 * finger.press
            ZStack {
                Circle().fill(color.opacity(0.22))
                    .frame(width: r * 2, height: r * 2)
                    .scaleEffect(down)
                Circle().fill(color.opacity(0.9))
                    .frame(width: r * 0.84, height: r * 0.84)
                    .scaleEffect(down)
                DemoRipple(radius: r * (1 + finger.ripple * 1.4))
                    .stroke(color, lineWidth: 2)
                    .opacity(Double(1 - finger.ripple) * 0.5)
                Circle().trim(from: 0, to: finger.ring)
                    .stroke(color, style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .frame(width: r * 2.6, height: r * 2.6)
            }
            .opacity(finger.alpha)
            .position(x: geo.size.width * finger.x, y: geo.size.height * finger.y)
        }
        .allowsHitTesting(false)
    }
}

/// A circle of [radius] about the frame's middle, so the ripple's line keeps its width as it spreads.
private struct DemoRipple: Shape {
    var radius: CGFloat
    var animatableData: CGFloat {
        get { radius }
        set { radius = newValue }
    }

    func path(in rect: CGRect) -> Path {
        Path(ellipseIn: CGRect(x: rect.midX - radius, y: rect.midY - radius, width: radius * 2, height: radius * 2))
    }
}

// MARK: - The phone

/// A 168 × 300 phone (larger on a tall page) that plays [script] on a loop while [playing] and the user
/// isn't touching it. [script] puts the scene back at rest first. Reduced motion keeps the phone still but
/// still pressable.
struct DemoPhone<Content: View>: View {
    var playing: Bool
    var tall = false
    var scheme: CoverScheme
    var script: @MainActor (DemoFinger) async throws -> Void
    @ViewBuilder var content: () -> Content

    @Environment(\.tipContainer) private var container
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var stage = DemoStage()
    @State private var userHas = false
    @State private var touches = 0

    private struct Run: Hashable {
        var playing: Bool
        var userHas: Bool
        var reduced: Bool
    }

    var body: some View {
        let m = DemoPhoneMetrics(container: container, tall: tall)
        let shape = RoundedRectangle(cornerRadius: DemoPhoneMetrics.corner, style: .continuous)
        ZStack(alignment: .topLeading) {
            content()
                .frame(width: m.screen.width, height: m.screen.height, alignment: .topLeading)
                .clipped()
                .coordinateSpace(.named(DemoTargets.space))
                .environment(\.demoTargets, stage.targets)
                .scaleEffect(m.scale, anchor: .topLeading)
                .frame(width: m.phone.width, height: m.phone.height, alignment: .topLeading)
            DemoFingerView(finger: stage.finger, grow: m.grow, color: scheme.primary)
        }
        .frame(width: m.phone.width, height: m.phone.height)
        .background(scheme.surface)
        .clipShape(shape)
        .overlay { shape.strokeBorder(scheme.outlineVariant, lineWidth: 1.5) }
        .contentShape(shape)
        // Watch (never take) the user's touches: the phone becomes theirs.
        .gesture(DemoTouchWatch {
            if !userHas { userHas = true }
            touches += 1
        })
        .onChange(of: m.screen, initial: true) { _, size in stage.targets.screen = size }
        .task(id: Run(playing: playing, userHas: userHas, reduced: reduceMotion)) {
            let finger = stage.finger
            guard playing, !userHas, !reduceMotion else {
                finger.hide()
                return
            }
            do {
                try await DemoTiming.delay(DemoTiming.startDelayMs)
                while !Task.isCancelled {
                    try await script(finger)
                    try await finger.lift()
                    try await DemoTiming.delay(DemoTiming.loopGapMs)
                }
            } catch {
                // Stopped part-way (the page left, the user's touch): the next run's script resets the scene.
            }
        }
        .task(id: touches) {
            guard touches > 0 else { return }
            try? await Task.sleep(for: .milliseconds(DemoTiming.takeoverIdleMs))
            if !Task.isCancelled { userHas = false }
        }
    }
}

/// Tells of every finger that comes down on the phone and then stands aside: it never recognises, so the
/// scene's own buttons and scrolls, and the tip pager around the phone, get the touch as if it weren't there.
/// (A SwiftUI gesture that watched from the first point kept the pager from turning under the phone.)
private struct DemoTouchWatch: UIGestureRecognizerRepresentable {
    var onDown: () -> Void

    func makeUIGestureRecognizer(context: Context) -> Recognizer {
        let recognizer = Recognizer()
        recognizer.cancelsTouchesInView = false
        recognizer.delaysTouchesBegan = false
        recognizer.delaysTouchesEnded = false
        recognizer.onDown = onDown
        return recognizer
    }

    func updateUIGestureRecognizer(_ recognizer: Recognizer, context: Context) {
        recognizer.onDown = onDown
    }

    final class Recognizer: UIGestureRecognizer {
        var onDown: (() -> Void)?

        override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
            onDown?()
            state = .failed
        }
    }
}

/// One phone's targets and touch, kept across redraws.
@MainActor
private final class DemoStage {
    let targets = DemoTargets()
    let finger: DemoFinger

    init() { finger = DemoFinger(targets: targets) }
}
