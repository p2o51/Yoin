import SwiftUI

// The landing's character (Android LandingMascot.kt): the three-arrow mark
// split into its fins. Pink is its left hand, navy its right, violet its head;
// the hands wave from the joint where the fins meet. Every value is a spring
// with its own velocity (Android Animatable), stepped once per frame and read
// while drawing, so a gesture can start from whatever pose is on screen and a
// wave never re-lays out anything.

/// Damping ratio + stiffness (Android YoinMotion.mascot*Spring).
struct MascotSpring: Sendable {
    var damping: Double
    var stiffness: Double

    static let land = MascotSpring(damping: 0.62, stiffness: 340)
    static let gesture = MascotSpring(damping: 0.5, stiffness: 420)
    static let hop = MascotSpring(damping: 0.42, stiffness: 520)
    static let shake = MascotSpring(damping: 0.18, stiffness: 520)
}

/// One sprung value (mass 1).
@MainActor
final class SpringValue {
    private(set) var value: Double
    private(set) var velocity: Double = 0
    private var target: Double
    private var spring = MascotSpring.gesture

    init(_ value: Double) {
        self.value = value
        target = value
    }

    func snap(_ v: Double) {
        value = v
        target = v
        velocity = 0
    }

    /// Retargets from the current value and velocity; [velocity] replaces the velocity (a kick).
    func animate(to t: Double, _ spec: MascotSpring, velocity v0: Double? = nil) {
        target = t
        spring = spec
        if let v0 { velocity = v0 }
    }

    var resting: Bool { abs(velocity) < 0.001 && abs(value - target) < 0.001 }

    func step(_ dt: Double) {
        guard !resting else {
            value = target
            velocity = 0
            return
        }
        let k = spring.stiffness, c = 2 * spring.damping * k.squareRoot()
        velocity += (-k * (value - target) - c * velocity) * dt
        value += velocity * dt
    }
}

@MainActor
final class MascotFin {
    let parts: [(path: Path, fill: Color?, strokeWidth: CGFloat)]
    /// The joint it turns around, in viewport units.
    let pivot: CGPoint
    let dx = SpringValue(0), dy = SpringValue(0), rot = SpringValue(0), scale = SpringValue(1), arm = SpringValue(0)

    init(_ parts: [MascotPart], pivot: CGPoint) {
        self.parts = parts.map { part in
            let path = YoinMarkPaths.parse(part.d).offsetBy(dx: LandingMascotPaths.groupShiftX, dy: 0)
            let fill = part.fill.map { v in
                Color(red: Double((v >> 16) & 0xFF) / 255, green: Double((v >> 8) & 0xFF) / 255, blue: Double(v & 0xFF) / 255)
            }
            return (path, fill, part.strokeWidth)
        }
        self.pivot = pivot
    }

    var springs: [SpringValue] { [dx, dy, rot, scale, arm] }

    func rest() {
        dx.snap(0); dy.snap(0); rot.snap(0); scale.snap(1); arm.snap(0)
    }
}

/// The mascot's choreography. Gestures replace each other: a new one cancels
/// the one running and starts from the pose on screen.
@MainActor
final class MascotModel {
    let pink = MascotFin(LandingMascotPaths.pink, pivot: CGPoint(x: 660, y: 520))
    let violet = MascotFin(LandingMascotPaths.violet, pivot: CGPoint(x: 652, y: 480))
    let navy = MascotFin(LandingMascotPaths.navy, pivot: CGPoint(x: 660, y: 500))
    var fins: [MascotFin] { [pink, violet, navy] }
    /// Whole-character alpha (the entrance fade).
    let alpha = SpringValue(1)
    /// Vertical kick, in viewport units (negative = up).
    let hop = SpringValue(0)

    var reduced = false
    /// A wave peak's light tick (the view turns it into a haptic).
    var onTick: (() -> Void)?

    private var gesture: Task<Void, Never>?
    private var last: Date?
    private var breathStart = Date()

    private func play(_ body: @escaping @MainActor () async -> Void) {
        gesture?.cancel()
        gesture = Task { @MainActor in await body() }
    }

    private func wait(_ ms: Double) async -> Bool {
        try? await Task.sleep(for: .milliseconds(Int(ms / LaunchConfig.animationSpeed)))
        return !Task.isCancelled
    }

    /// Steps every spring to [date]. Returns the breath lift (viewport units).
    func advance(to date: Date, breathing: Bool) -> Double {
        let dt = min(date.timeIntervalSince(last ?? date), 1.0 / 20) * LaunchConfig.animationSpeed
        last = date
        if dt > 0 {
            let n = max(1, Int((dt * 240).rounded(.up)))
            let h = dt / Double(n)
            let all = fins.flatMap(\.springs) + [alpha, hop]
            for _ in 0..<n { all.forEach { $0.step(h) } }
        }
        guard breathing, !reduced else { return 0 }
        let phase = date.timeIntervalSince(breathStart) * LaunchConfig.animationSpeed / 3.4
        return sin(phase * 2 * .pi) * 5
    }

    /// Hidden until [intro] runs (first frame of a landing that plays it).
    func hideForIntro() {
        alpha.snap(0)
    }

    /// The fins fly in from three sides and land as the mark, then it waves.
    func intro(onLanded: @escaping @MainActor () -> Void) {
        play { [self] in
            if reduced {
                fins.forEach { $0.rest() }
                alpha.snap(1)
                onLanded()
                return
            }
            let from: [(MascotFin, Double, Double, Double)] = [(pink, -330, 220, -55), (violet, 0, -300, 24), (navy, 330, 230, 55)]
            for (fin, x, y, r) in from {
                fin.dx.snap(x); fin.dy.snap(y); fin.rot.snap(r); fin.scale.snap(0.35); fin.arm.snap(0)
            }
            alpha.snap(0)
            Task { @MainActor [alpha] in
                try? await Task.sleep(for: .milliseconds(Int(60 / LaunchConfig.animationSpeed)))
                alpha.animate(to: 1, .land)
            }
            for (fin, wait) in [(violet, 80.0), (pink, 170.0), (navy, 260.0)] {
                Task { @MainActor in
                    try? await Task.sleep(for: .milliseconds(Int(wait / LaunchConfig.animationSpeed)))
                    for s in [fin.dx, fin.dy, fin.rot] { s.animate(to: 0, .land) }
                    fin.scale.animate(to: 1, .land)
                }
            }
            guard await wait(760) else { return }
            onLanded()
            await waveSequence(times: 2, ticks: true)
        }
    }

    /// Both hands up and back, [times] swings.
    func wave(times: Int = 1, ticks: Bool = false) {
        play { [self] in await waveSequence(times: times, ticks: ticks) }
    }

    private func waveSequence(times: Int, ticks: Bool) async {
        if reduced { return }
        // (delay before this pose, pink arm, navy arm, head tilt): pink swings clockwise to raise, navy the other way.
        let poses: [(Double, Double, Double, Double)] = times <= 1
            ? [(0, 28, -12, -2.5), (240, 8, -2, 2.5), (240, 0, 0, 0)]
            : [(0, 26, -12, -2.5), (230, 9, -3, 2.5), (230, 30, -14, -2.5), (230, 10, -4, 2.5), (240, 0, 0, 0)]
        for (i, pose) in poses.enumerated() {
            guard await wait(pose.0) else { return }
            pink.arm.animate(to: pose.1, .gesture)
            navy.arm.animate(to: pose.2, .gesture)
            violet.arm.animate(to: pose.3, .gesture)
            if ticks, pose.1 > 20, i == 0 || i == 2 {
                Task { @MainActor [weak self] in
                    try? await Task.sleep(for: .milliseconds(Int(120 / LaunchConfig.animationSpeed)))
                    self?.onTick?()
                }
            }
        }
    }

    /// Both hands up and a hop: a connection went through.
    func cheer() {
        play { [self] in
            if reduced { return }
            hop.animate(to: 0, .hop, velocity: -1700)
            pink.arm.animate(to: 36, .gesture)
            navy.arm.animate(to: -16, .gesture)
            guard await wait(520) else { return }
            pink.arm.animate(to: 0, .gesture)
            navy.arm.animate(to: 0, .gesture)
        }
    }

    /// A small nod toward something the user picked.
    func nod() {
        play { [self] in
            if reduced { return }
            hop.animate(to: 0, .hop, velocity: -700)
            violet.arm.animate(to: 7, .gesture)
            guard await wait(380) else { return }
            violet.arm.animate(to: 0, .gesture)
        }
    }

    /// The head rings out a "no": something was refused.
    func shake() {
        play { [self] in
            if reduced { return }
            violet.arm.animate(to: 0, .shake, velocity: 160)
        }
    }
}

/// The mascot, drawn at its frame's width (the height follows the mark's aspect, 1.25).
struct LandingMascotView: View {
    let model: MascotModel
    var breathing = true

    static let aspect: CGFloat = LandingMascotPaths.viewport.width / LandingMascotPaths.viewport.height

    var body: some View {
        TimelineView(.animation) { context in
            let lift = model.advance(to: context.date, breathing: breathing)
            Canvas { gc, size in
                draw(&gc, size: size, lift: lift)
            }
        }
        .accessibilityHidden(true)
    }

    private func draw(_ gc: inout GraphicsContext, size: CGSize, lift: Double) {
        let unit = size.width / LandingMascotPaths.viewport.width
        let alpha = min(max(model.alpha.value, 0), 1)
        guard alpha > 0 else { return }
        gc.opacity = alpha
        let up = (model.hop.value + lift) * unit
        for fin in model.fins {
            var c = gc
            c.translateBy(x: fin.dx.value * unit, y: fin.dy.value * unit + up)
            let p = CGPoint(x: fin.pivot.x * unit, y: fin.pivot.y * unit)
            c.translateBy(x: p.x, y: p.y)
            c.rotate(by: .degrees(fin.rot.value + fin.arm.value))
            c.scaleBy(x: fin.scale.value, y: fin.scale.value)
            c.translateBy(x: -p.x, y: -p.y)
            c.scaleBy(x: unit, y: unit)
            for part in fin.parts {
                if part.strokeWidth > 0 {
                    c.stroke(part.path, with: .color(.white), lineWidth: part.strokeWidth)
                } else if let fill = part.fill {
                    c.fill(part.path, with: .color(fill))
                }
            }
        }
    }
}

#Preview {
    LandingMascotView(model: MascotModel())
        .frame(width: 240, height: 192)
}
