import SwiftUI

/*
 * Home's cold-start intro (Android ui/home/HomeGreetingTitle, parity A-U4): the Yoin mark blooms where
 * the title will be — each arm springing open a beat after the last, a little overshoot — then turns
 * and shrinks away to the left as the greeting and "Home" rise into place. Once per process (a cold
 * start, not a return to Home or a rotation); with Reduce Motion the two lines are simply there. The
 * Memories bubble waits for it.
 */
@MainActor @Observable
final class HomeIntro {
    static let shared = HomeIntro()

    /// All 0 → 1; at rest (played, or Reduce Motion) everything is 1.
    private(set) var arms: [CGFloat] = [0, 0, 0]
    private(set) var exit: CGFloat = 0
    private(set) var fade: CGFloat = 0
    private(set) var greeting: CGFloat = 0
    private(set) var title: CGFloat = 0
    private(set) var markShown = false
    /// True while it plays: the Memories bubble holds its news.
    private(set) var running = false
    @ObservationIgnored private var played = false

    /// Android's timings.
    static let armStagger = 0.070
    static let exitAt = 0.950
    static let greetingAfterExit = 0.100
    static let titleAfterGreeting = 0.080
    /// dampingRatio 0.55, stiffness 260.
    static let bloom = Animation.interpolatingSpring(mass: 1, stiffness: 260, damping: 0.55 * 2 * 260.0.squareRoot())
    static let markSize: CGFloat = 56

    /// The first Home on screen plays it; every later one finds it at rest.
    func play(reduceMotion: Bool) {
        guard !played else { return }
        played = true
        guard !reduceMotion else { return rest() }
        markShown = true
        running = true
        Task { @MainActor in
            for i in 0..<3 {
                withAnimation(Self.bloom.delay(Double(i) * Self.armStagger)) { arms[i] = 1 }
            }
            try? await Task.sleep(for: .seconds(Self.exitAt))
            withAnimation(Yoin.spatial) { exit = 1 }
            withAnimation(Yoin.effects) { fade = 1 }
            try? await Task.sleep(for: .seconds(Self.greetingAfterExit))
            withAnimation(Yoin.spatial) { greeting = 1 }
            try? await Task.sleep(for: .seconds(Self.titleAfterGreeting))
            withAnimation(Yoin.spatial) { title = 1 }
            try? await Task.sleep(for: .seconds(0.6))
            markShown = false
            running = false
        }
    }

    private func rest() {
        arms = [1, 1, 1]
        exit = 1
        fade = 1
        greeting = 1
        title = 1
        markShown = false
        running = false
    }
}

/// The mark as it blooms and leaves (Android YoinMark with YoinArmTransform per arm).
struct HomeIntroMark: View {
    var intro: HomeIntro
    var scheme: CoverScheme

    var body: some View {
        let colors = [scheme.tertiary, scheme.primary, scheme.secondary]
        let e = intro.exit
        Canvas { gc, size in
            let s = min(size.width, size.height) / YoinMarkPaths.viewBox
            gc.scaleBy(x: s, y: s)
            let hub = YoinMarkPaths.hub
            for i in 0..<3 {
                let p = intro.arms[i]
                guard p > 0.001 else { continue }
                var g = gc
                g.translateBy(x: hub.x, y: hub.y)
                g.rotate(by: .degrees(Double((1 - p) * -80)))
                g.scaleBy(x: max(0, p), y: max(0, p))
                g.translateBy(x: -hub.x, y: -hub.y)
                let a = YoinMarkPaths.arms[i]
                g.fill(a.fill, with: .color(colors[i]))
                if a.lineWidth > 0 {
                    g.stroke(a.line, with: .color(.white), style: StrokeStyle(lineWidth: a.lineWidth, lineCap: .round))
                } else {
                    g.fill(a.line, with: .color(.white))
                }
            }
        }
        .frame(width: HomeIntro.markSize, height: HomeIntro.markSize)
        .scaleEffect(1 - 0.6 * e)
        .rotationEffect(.degrees(70 * e))
        .offset(x: -2 - 6 * e)
        .opacity(1 - intro.fade)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}
