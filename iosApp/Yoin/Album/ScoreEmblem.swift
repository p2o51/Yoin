import SwiftUI
import UIKit

/// Port of Android component/ScoreEmblem.kt (Memories "groove" emblem) for
/// the Page surface, light and dark Album/Average kinds.
///
/// Geometry: rim radius s/2 − 0.5, label radius 0.305 s. Rings: 6 at 64 pt
/// (3 below 64, 8 from 88, 12 from 110). Each ring holds a contiguous chunk
/// of tracks, outer ring = track 1, each track an equal arc from 12 o'clock
/// clockwise. Rated tracks are solid butt arcs; unrated ones a dotted lattice.
/// Label: Album = filled Cookie12Sided + thin accent ring; Average = outlined
/// circle. Score: GrooveType (wght 690/640, ROND 50, wdth 76/92).
struct ScoreEmblem: View {
    enum Kind { case album, average }
    var score: Double
    var kind: Kind
    var rated: [Bool]
    var palette: CoverPalette
    var size: CGFloat = 64
    @Environment(\.colorScheme) private var colorScheme

    private struct Colors {
        var ground, cut, dots, rim, label, labelRing, ink: Color
    }

    private var colors: Colors {
        func hsl(_ c: Color) -> (CGFloat, CGFloat, CGFloat) {
            var h: CGFloat = 0, s: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
            UIColor(c).getHue(&h, saturation: &s, brightness: &b, alpha: &a)
            let l = b * (1 - s / 2)
            let sl = (l == 0 || l == 1) ? 0 : (b - l) / min(l, 1 - l)
            return (h, sl, l)
        }
        func fromHSL(_ h: CGFloat, _ s: CGFloat, _ l: CGFloat) -> Color {
            let v = l + s * min(l, 1 - l)
            return Color(hue: h, saturation: v == 0 ? 0 : 2 * (1 - l / v), brightness: v)
        }
        func mix(_ a: Color, _ b: Color, _ t: Double) -> Color { a.mix(with: b, by: t, in: .device) }
        let (h, s, l) = hsl(CoverSeed.seed(for: palette))
        let base = fromHSL(h, s, min(0.46, max(0.22, l)))
        let accent = BackdropColors(palette).accent
        let deep = mix(base, .black, 0.55)
        let soft = mix(base, .white, 0.8)
        if colorScheme == .dark {
            return Colors(ground: mix(base, .black, 0.62), cut: mix(soft, accent, 0.3), dots: soft.opacity(0.42),
                          rim: soft, label: mix(soft, accent, 0.25), labelRing: accent, ink: deep)
        }
        return Colors(ground: mix(mix(soft, .white, 0.62), mix(soft, base, 0.06), 0.6),
                      cut: mix(mix(base, deep, 0.2), mix(base, accent, 0.32), 0.4),
                      dots: base.opacity(0.42), rim: base,
                      label: mix(mix(base, .white, 0.14), mix(base, deep, 0.3), 0.5),
                      labelRing: accent, ink: mix(soft, .white, 0.72))
    }

    /// GrooveType: max(s × 0.29, 21.5) sp (21.5 at 64 pt).
    private var scoreSize: CGFloat { size >= 64 ? max(size * 0.29, 21.5) : size * 0.34 }

    private var rings: Int { size < 64 ? 3 : size < 88 ? 6 : size < 110 ? 8 : 12 }

    var body: some View {
        let c = colors
        let label = String(format: "%.1f", (score * 10).rounded() / 10)
        ZStack {
            Canvas { gc, sz in
                let center = CGPoint(x: sz.width / 2, y: sz.height / 2)
                let rimR = sz.width / 2 - 0.5
                let labelR = sz.width * 0.305
                gc.fill(Path(ellipseIn: CGRect(x: center.x - rimR, y: center.y - rimR, width: rimR * 2, height: rimR * 2)),
                        with: .color(c.ground))
                let rimW = kind == .album ? max(1.1, sz.width * 0.019) : max(0.9, sz.width * 0.012)
                gc.stroke(Path(ellipseIn: CGRect(x: center.x - rimR, y: center.y - rimR, width: rimR * 2, height: rimR * 2)),
                          with: .color(c.rim), lineWidth: rimW)
                let n = max(rated.count, 1)
                let inner = labelR + 2, outer = rimR - rimW - 1.5
                let pitch = (outer - inner) / CGFloat(rings)
                let dot = min(pitch * 0.58, 1.2)
                for ring in 0..<rings {
                    let from = Int((Double(ring) * Double(n) / Double(rings)).rounded())
                    let to = Int((Double(ring + 1) * Double(n) / Double(rings)).rounded())
                    guard to > from else { continue }
                    let r = outer - pitch * (CGFloat(ring) + 0.5)
                    for t in from..<to {
                        let share = 360.0 / Double(to - from)
                        let a0 = -90 + share * Double(t - from)
                        if rated.indices.contains(t) && rated[t] {
                            var p = Path()
                            p.addArc(center: center, radius: r, startAngle: .degrees(a0), endAngle: .degrees(a0 + share),
                                     clockwise: false)
                            gc.stroke(p, with: .color(c.cut), style: StrokeStyle(lineWidth: min(pitch * 0.64, dot + 0.25)))
                        } else {
                            let spacing = max(2.6, dot * 2.3)
                            let arcLen = Double(r) * share * .pi / 180
                            let count = max(1, Int(arcLen / spacing))
                            for i in 0..<count {
                                let ang = (a0 + share * (Double(i) + (ring.isMultiple(of: 2) ? 0 : 0.5)) / Double(count)) * .pi / 180
                                let pt = CGPoint(x: center.x + r * cos(ang), y: center.y + r * sin(ang))
                                gc.fill(Path(ellipseIn: CGRect(x: pt.x - dot / 2, y: pt.y - dot / 2, width: dot, height: dot)),
                                        with: .color(c.dots))
                            }
                        }
                    }
                }
            }
            if kind == .album {
                CookieShape(lobes: 12, depth: 0.08)
                    .fill(c.label)
                    .overlay(CookieShape(lobes: 12, depth: 0.08).stroke(c.labelRing, lineWidth: size * 0.007))
                    .frame(width: size * 0.61, height: size * 0.61)
                Text(label)
                    .font(YoinFont.score(label, size: scoreSize, albumRating: true))
                    .foregroundStyle(c.ink)
            } else {
                Circle()
                    .stroke(c.label, lineWidth: max(1.2, size * 0.017))
                    .frame(width: size * 0.61, height: size * 0.61)
                Text(label)
                    .font(YoinFont.score(label, size: scoreSize, albumRating: false))
                    .foregroundStyle(c.label)
            }
        }
        .frame(width: size, height: size)
        .accessibilityElement()
        .accessibilityLabel(kind == .album ? "Album rating \(label)" : "Track average \(label)")
    }
}

extension CoverSeed {
    /// Seed color for a palette (renders the mock cover through Palette).
    @MainActor static func seed(for palette: CoverPalette) -> Color {
        let album = MockLibrary.allAlbums.first { $0.palette == palette }
        return album.map { CoverSeed.seed(for: $0) } ?? palette.a
    }
}
