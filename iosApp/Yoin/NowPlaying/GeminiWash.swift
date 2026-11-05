import SwiftUI

/// Gemini thinking on Now Playing (Android NowPlayingAuroraBackground
/// drawGeminiWash): a pastel wash from the top whose hue runs round
/// primary → Gemini blue → secondary → tertiary, pulled 80 % toward a slow
/// rainbow; two corner blooms a step further round; fine dots where it fades.
/// Blooms in while an Ask question or About's own first fetch waits, out when
/// the answer lands. `fixedHue`: the key hint's one soft pink, no drift.
struct GeminiWash: View {
    var active: Bool
    var primary: Color
    var secondary: Color
    var tertiary: Color
    var fixedHue: Double? = nil
    var strength: Double = 1
    @Environment(\.colorScheme) private var colorScheme
    /// The envelope's last change: it runs 0 ↔ 1 over `ramp` from there.
    @State private var changedAt = Date.distantPast
    @State private var from: Double = 0
    @State private var target: Double = 0

    private static let ramp: TimeInterval = 0.6

    var body: some View {
        let live = active || target > 0 || from > 0
        TimelineView(.animation(paused: !live)) { ctx in
            let f = envelope(ctx.date)
            Canvas { gc, size in
                guard f > 0.001 else { return }
                draw(&gc, size, t: ctx.date.timeIntervalSinceReferenceDate * 1000, frac: f)
            }
        }
        .allowsHitTesting(false)
        .onAppear { if active { set(1) } }
        .onChange(of: active) { _, on in set(on ? 1 : 0) }
    }

    private func set(_ to: Double) {
        let now = Date()
        from = envelope(now)
        target = to
        changedAt = now
    }

    /// Smoothstep from `from` to `target` over the ramp (the slow effects feel).
    private func envelope(_ now: Date) -> Double {
        let k = min(max(now.timeIntervalSince(changedAt) / Self.ramp, 0), 1)
        let s = k * k * (3 - 2 * k)
        return from + (target - from) * s
    }

    private func draw(_ gc: inout GraphicsContext, _ size: CGSize, t: Double, frac: Double) {
        let dark = colorScheme == .dark
        let w = size.width, h = size.height
        let ring = [Self.hue(primary), 217, Self.hue(secondary), Self.hue(tertiary)]
        func ringHue(_ phase: Double) -> Double {
            let x = (phase.truncatingRemainder(dividingBy: 4) + 4).truncatingRemainder(dividingBy: 4)
            let i = Int(x)
            return Self.lerpHue(ring[i], ring[(i + 1) % 4], Self.smoothstep(x - Double(i)))
        }
        let phase = t / 2600
        let rainbow = (t / 7000 * 360 + 200).truncatingRemainder(dividingBy: 360)
        let mix = 0.8
        let hueA = fixedHue ?? Self.lerpHue(ringHue(phase), rainbow, mix)
        let hueB = fixedHue.map { $0 - 12 } ?? Self.lerpHue(ringHue(phase + 1.3), rainbow + 70, mix)
        let hueC = fixedHue.map { $0 + 10 } ?? Self.lerpHue(ringHue(phase + 2.6), rainbow + 150, mix)
        let s = dark ? 0.78 : 0.95
        let l = dark ? 0.52 : 0.82
        let a = (dark ? 0.62 : 0.92) * min(max(frac, 0), 1) * strength
        func hsl(_ hue: Double, _ alpha: Double, _ light: Double? = nil) -> Color {
            Self.hsl(hue, s, light ?? l, min(max(alpha, 0), 1))
        }
        if dark { gc.blendMode = .screen }
        let depth = h * (0.22 + 0.38 * Self.smoothstep(min(1, frac)))
        let bottom = depth + h * 0.12
        gc.fill(Path(CGRect(x: 0, y: 0, width: w, height: bottom)),
                with: .linearGradient(Gradient(stops: [.init(color: hsl(hueA, a), location: 0),
                                                       .init(color: hsl(hueA, a * 0.45), location: 0.55),
                                                       .init(color: hsl(hueA, 0), location: 1)]),
                                      startPoint: .zero, endPoint: CGPoint(x: 0, y: bottom)))
        let dx = sin(t / 3100) * w * 0.08
        bloom(&gc, CGPoint(x: w * 0.12 + dx, y: h * 0.04), w * 0.78, hsl(hueB, 1), a * 0.85)
        bloom(&gc, CGPoint(x: w * 0.92 - dx, y: h * 0.10), w * 0.70, hsl(hueC, 1), a * 0.75)
        // Fine dots where the wash fades out.
        let grid = 6.0, rMax = 1.3
        let y0 = depth * 0.55, y1 = depth + h * 0.14
        let dot = hsl(hueB, (dark ? 0.55 : 0.45) * frac, dark ? 0.7 : 0.62)
        var dots = Path()
        var y = y0, row = 0
        while y < y1 {
            let kk = pow(1 - (y - y0) / (y1 - y0), 1.2)
            var x = row & 1 == 1 ? grid / 2 : 0
            while x < w {
                let n = 0.5 + 0.5 * sin(x * 0.045 + t / 900) * cos(y * 0.05 - t / 1300)
                let r = rMax * kk * (0.35 + 0.65 * n)
                if r >= 0.3 { dots.addEllipse(in: CGRect(x: x - r, y: y - r, width: r * 2, height: r * 2)) }
                x += grid
            }
            y += grid
            row += 1
        }
        gc.fill(dots, with: .color(dot))
        bloom(&gc, CGPoint(x: w * 0.5, y: h * 0.95), w * 0.6, hsl(hueB, 1), a * 0.35)
    }

    private func bloom(_ gc: inout GraphicsContext, _ c: CGPoint, _ r: Double, _ color: Color, _ alpha: Double) {
        guard r > 0, alpha > 0.001 else { return }
        let a = min(max(alpha, 0), 1)
        gc.fill(Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2)),
                with: .radialGradient(Gradient(colors: [color.opacity(a), color.opacity(0)]),
                                      center: c, startRadius: 0, endRadius: r))
    }

    // MARK: Colour

    static func smoothstep(_ x: Double) -> Double { let k = min(max(x, 0), 1); return k * k * (3 - 2 * k) }

    static func lerpHue(_ a: Double, _ b: Double, _ t: Double) -> Double {
        var d = (b - a).truncatingRemainder(dividingBy: 360)
        if d > 180 { d -= 360 } else if d < -180 { d += 360 }
        return a + d * t
    }

    static func hue(_ c: Color) -> Double {
        var h: CGFloat = 0, s: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        UIColor(c).getHue(&h, saturation: &s, brightness: &b, alpha: &a)
        return Double(h) * 360
    }

    static func hsl(_ hue: Double, _ s: Double, _ l: Double, _ alpha: Double) -> Color {
        let h = ((hue.truncatingRemainder(dividingBy: 360)) + 360).truncatingRemainder(dividingBy: 360) / 360
        // HSL → HSB.
        let v = l + s * min(l, 1 - l)
        let sb = v == 0 ? 0 : 2 * (1 - l / v)
        return Color(hue: h, saturation: sb, brightness: v).opacity(alpha)
    }
}

/// The soft pink glow when About needs a Gemini key that isn't set (Android
/// GeminiKeyHint): at most once every three days, held 3.2 s. This device only.
enum GeminiKeyHint {
    static let cooldownMs: Int64 = 3 * 24 * 60 * 60 * 1000
    static let holdSeconds: Double = 3.2
    static let key = "geminiKeyHintLastShownMs"

    /// True when the glow may show now; records it as shown if so.
    static func claim(_ defaults: UserDefaults = .standard, nowMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> Bool {
        let last = defaults.object(forKey: key) == nil ? nil : Int64(defaults.double(forKey: key))
        guard isDue(last, nowMs: nowMs) else { return false }
        defaults.set(Double(nowMs), forKey: key)
        return true
    }

    /// Never shown, or shown at least the cooldown ago (a clock set back counts as due).
    static func isDue(_ lastShownMs: Int64?, nowMs: Int64) -> Bool {
        guard let last = lastShownMs else { return true }
        return nowMs < last || nowMs - last >= cooldownMs
    }
}
