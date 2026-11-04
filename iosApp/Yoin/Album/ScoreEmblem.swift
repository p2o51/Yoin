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
///
/// 唱片刻纹 award: pass a [ScoreEmblemAward] (GrooveAward.swift) to play the
/// tiered cutting ceremony — disc spin, grooves cut ring by ring, label
/// lift/stamp, lit rings, the 10.0 rim flare and released ring — with its
/// haptic beats. `tilt: true` adds the CoreMotion tilt colour (GrooveTilt.swift).
struct ScoreEmblem: View {
    /// Android ScoreEmblemKind: the user's album rating, the rated tracks'
    /// average, or nothing rated (the dashed empty mould, "Unrated").
    enum Kind {
        case album, average, unrated

        /// The Bun rule (AlbumDetailUiState.albumScore): manual album rating >
        /// average of the rated tracks > N/A.
        init(rating: Double?, average: Double?) {
            self = rating != nil ? .album : average != nil ? .average : .unrated
        }
    }
    var score: Double
    var kind: Kind
    var rated: [Bool]
    var palette: CoverPalette
    var size: CGFloat = 64
    /// The award ceremony (see [ScoreEmblemAward]); built from the same
    /// score / kind / rated / size, else ignored. nil draws the resting emblem.
    var award: ScoreEmblemAward? = nil
    /// Device tilt as colour on the rim and outer cut rings (CoreMotion).
    /// Off by default, as on Android's album page; never under Reduce Motion.
    var tilt = false
    /// Theme neutrals for the unrated mould (Android reads MaterialTheme's
    /// outline / onSurfaceVariant); nil = the brand scheme.
    var neutrals: CoverScheme? = nil
    /// Pinned on artwork (Android ScoreEmblemSurface.Artwork): an unrated
    /// emblem gets an opaque ground and a hairline rim to separate from the cover.
    var onCover = false
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private struct Colors {
        var ground, cut, dots, rim, label, labelRing, ink: Color
        var lit, flare, tiltHigh, tiltLow: Color
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
        if kind == .unrated {
            // GrooveColors Unrated on a page: a faint outline ground, an outline
            // lattice, no rim; the mould (slot) is drawn by the label.
            let dark = colorScheme == .dark
            let theme = neutrals ?? .brand(dark: dark)
            let n = theme.outline
            let clear = Color.clear
            return Colors(ground: onCover ? theme.surface : n.opacity(dark ? 0.1 : 0.06), cut: clear,
                          dots: n.opacity(dark ? 0.82 : 0.6),
                          rim: onCover ? n.opacity(dark ? 0.7 : 0.45) : clear, label: n, labelRing: clear,
                          ink: theme.onSurfaceVariant,
                          lit: clear, flare: clear, tiltHigh: clear, tiltLow: clear)
        }
        let (h, s, l) = hsl(CoverSeed.seed(for: palette))
        let base = fromHSL(h, s, min(0.46, max(0.22, l)))
        let accent = BackdropColors(palette).accent
        let deep = mix(base, .black, 0.55)
        let soft = mix(base, .white, 0.8)
        if colorScheme == .dark {
            return Colors(ground: mix(base, .black, 0.62), cut: mix(soft, accent, 0.3), dots: soft.opacity(0.42),
                          rim: soft, label: mix(soft, accent, 0.25), labelRing: accent, ink: deep,
                          lit: mix(accent, .white, 0.3), flare: mix(accent, .white, 0.15),
                          tiltHigh: mix(accent, .white, 0.45), tiltLow: mix(base, .black, 0.7))
        }
        return Colors(ground: mix(mix(soft, .white, 0.62), mix(soft, base, 0.06), 0.6),
                      cut: mix(mix(base, deep, 0.2), mix(base, accent, 0.32), 0.4),
                      dots: base.opacity(0.42), rim: base,
                      label: mix(mix(base, .white, 0.14), mix(base, deep, 0.3), 0.5),
                      labelRing: accent, ink: mix(soft, .white, 0.72),
                      lit: accent, flare: mix(accent, base, 0.1), tiltHigh: accent, tiltLow: deep)
    }

    /// GrooveType: max(s × 0.29, 21.5) sp (21.5 at 64 pt).
    private var scoreSize: CGFloat { size >= 64 ? max(size * 0.29, 21.5) : size * 0.34 }

    private var driving: ScoreEmblemAward? {
        award.flatMap { $0.drives(score: score, kind: kind, rated: rated, size: size) ? $0 : nil }
    }

    private var tiltOn: Bool { tilt && !reduceMotion }

    var body: some View {
        let award = driving
        TimelineView(.animation(paused: !(award?.isAnimating ?? false))) { context in
            let layout = award?.layout ?? GrooveLayout(rated: rated, size: size)
            let ch = award?.channels(at: context.date)
                ?? .rest(orders: max(1, layout.ratedRings.count), rings: layout.rings)
            let t = tiltOn ? GrooveTiltVars(x: GrooveTilt.shared.x, y: GrooveTilt.shared.y) : GrooveTiltVars(x: 0, y: 0)
            emblem(ch, layout: layout, tilt: t)
        }
        .frame(width: size, height: size)
        .onChange(of: reduceMotion, initial: true) { _, reduced in award?.reducedMotion = reduced }
        .onAppear { if tiltOn { GrooveTilt.shared.acquire() } }
        .onDisappear { if tiltOn { GrooveTilt.shared.release() } }
        .onChange(of: tiltOn) { _, on in on ? GrooveTilt.shared.acquire() : GrooveTilt.shared.release() }
        .accessibilityElement()
        .accessibilityLabel(kind == .album ? "Album rating \(scoreText)"
            : kind == .average ? "Track average \(scoreText)" : "Not rated")
    }

    private var scoreText: String { String(format: "%.1f", (score * 10).rounded() / 10) }

    private func emblem(_ ch: GrooveChannels, layout: GrooveLayout, tilt tv: GrooveTiltVars) -> some View {
        let c = colors
        let label = scoreText
        // unrated: no rim, but a 0.8 hairline on a cover
        let rimW = kind == .album ? max(1.1, size * 0.019) : kind == .average ? max(0.9, size * 0.012)
            : onCover ? 0.8 : 0
        return ZStack {
            Canvas { gc, sz in
                draw(&gc, sz, ch, layout, tv, c, rimW)
            }
            if ch.burstAlpha > 0.002 {
                // the 10.0 award's released ring; a view so it may leave the box
                Circle()
                    .stroke(c.flare, lineWidth: max(1, size * 0.014))
                    .frame(width: size - 1, height: size - 1)
                    .scaleEffect(ch.burstScale)
                    .opacity(min(1, ch.burstAlpha))
            }
            Group {
                if kind == .unrated {
                    unratedMould(c)
                } else if kind == .album {
                    CookieShape(lobes: 12, depth: 0.08)
                        .fill(c.label)
                        .overlay(CookieShape(lobes: 12, depth: 0.08).stroke(c.labelRing, lineWidth: size * 0.007))
                        .frame(width: size * 0.61, height: size * 0.61)
                        .rotationEffect(.degrees(ch.disc))
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
            .scaleEffect(ch.labelScale)
            .opacity(min(1, max(0, ch.labelAlpha)))
        }
    }

    /// The unrated label (GrooveEmblem drawLabel/drawType, Unrated): a dashed
    /// Cookie12Sided mould, opened 7 % wider from 60 to 88 pt; "Unrated" in it
    /// from 64 pt, a spindle hole below.
    private func unratedMould(_ c: Colors) -> some View {
        let tiny = size < 60
        let l = labelRadius
        let w = tiny ? 1.4 : max(1.1, size * 0.013)
        let d = 2 * (l - w / 2)
        // 24 dashes around the mould, two per lobe.
        let per = 2 * .pi * (l - w / 2) * 1.02 / 24
        let mould = CookieShape(lobes: 12, depth: 0.08)
        let dark = colorScheme == .dark
        return ZStack {
            mould.fill(c.label.opacity(dark ? 0.14 : 0.08)).frame(width: d, height: d)
            mould.stroke(c.label, style: StrokeStyle(lineWidth: w, lineCap: .round, dash: [per * 0.55, per * 0.45]))
                .frame(width: d, height: d)
            if size >= 64 {
                let f: CGFloat = size < 88 ? 9.5 : max(10, size * 0.125)
                Text("Unrated")
                    // a fixed-size graphic: the word doesn't follow Dynamic Type
                    .font(Font(YoinFont.uiFont(size: f, YoinFont.Axes(weight: CGFloat(Tokens.type.CAPTION_WEIGHT),
                                                                       width: size < 88 ? 76 : 92))))
                    .foregroundStyle(c.ink)
                    .lineLimit(1)
                    .fixedSize()
            } else {
                Circle().fill(c.label.opacity(0.85))
                    .frame(width: 2 * max(1.6, size * 0.045), height: 2 * max(1.6, size * 0.045))
            }
        }
    }

    /// GrooveGeometry labelR: 0.305 s (0.345 tiny, 0.3 from 110); the unrated
    /// mould holds a word, so it opens 7 % wider from 60 to 88.
    private var labelRadius: CGFloat {
        size * (size < 60 ? 0.345 : size >= 110 ? 0.3 : 0.305)
            * (kind == .unrated && size >= 60 && size < 88 ? 1.07 : 1)
    }

    // swiftlint:disable:next function_parameter_count
    private func draw(_ gc: inout GraphicsContext, _ sz: CGSize, _ ch: GrooveChannels, _ layout: GrooveLayout,
                      _ tv: GrooveTiltVars, _ c: Colors, _ rimW: CGFloat) {
        let center = CGPoint(x: sz.width / 2, y: sz.height / 2)
        let rimR = sz.width / 2 - 0.5
        let labelR = kind == .unrated ? labelRadius : sz.width * 0.305
        let tm = tv.tm * ch.tintAlpha
        func circle(_ r: CGFloat) -> Path {
            Path(ellipseIn: CGRect(x: center.x - r, y: center.y - r, width: r * 2, height: r * 2))
        }
        func arc(_ r: CGFloat, _ start: Double, _ sweep: Double) -> Path {
            var p = Path()
            p.addArc(center: center, radius: r, startAngle: .degrees(start), endAngle: .degrees(start + sweep),
                     clockwise: false)
            return p
        }
        /// Prototype `tintRing`: 24 flat 15° segments; the high tone follows
        /// max(0, cos θ), the low tone max(0, −cos θ), turned to the tilt.
        func tintRing(_ ctx: inout GraphicsContext, _ r: CGFloat, _ width: CGFloat, _ base: Color, _ weight: Double, clip: [(Double, Double)]?) {
            guard tm > 0.001 else { return }
            let high = base.mix(with: c.tiltHigh, by: 0.55, in: .device)
            let low = base.mix(with: c.tiltLow, by: 0.5, in: .device)
            let step = 15.0
            for n in 0..<24 {
                let a = -90 + Double(n) * step
                let k = cos((a + step / 2) * .pi / 180)
                guard abs(k) > 0.02 else { continue }
                let color = (k > 0 ? high : low).opacity(min(1, abs(k) * weight * tm))
                let s0 = a + tv.ta, s1 = s0 + step
                if let clip {
                    for (r0, r1) in clip {
                        for shift in [-360.0, 0, 360] {
                            let lo = max(s0 + shift, r0), hi = min(s1 + shift, r1)
                            if hi > lo { ctx.stroke(arc(r, lo, hi - lo), with: .color(color), lineWidth: width) }
                        }
                    }
                } else {
                    ctx.stroke(arc(r, s0, step), with: .color(color), lineWidth: width)
                }
            }
        }

        gc.fill(circle(rimR), with: .color(c.ground))
        if rimW > 0 {
            let rimPath = circle(rimR)
            gc.stroke(rimPath, with: .color(c.rim), lineWidth: rimW)
            tintRing(&gc, rimR, rimW, c.rim, 1, clip: nil)
        }

        let inner = labelR + 2, outer = rimR - rimW - 1.5
        let pitch = (outer - inner) / CGFloat(layout.rings)
        let dot = min(pitch * 0.58, 1.2)
        let cutW = min(pitch * 0.64, dot + 0.25)

        // disc space: turns with the award
        var disc = gc
        disc.translateBy(x: center.x, y: center.y)
        disc.rotate(by: .degrees(ch.disc))
        disc.translateBy(x: -center.x, y: -center.y)
        for ring in 0..<layout.rings {
            let chunk = layout.chunks[ring]
            guard !chunk.isEmpty else { continue }
            let r = outer - pitch * (CGFloat(ring) + 0.5)
            let share = 360.0 / Double(chunk.count)
            // the pre-cut lattice, under every track (cuts cover the rated ones)
            for t in chunk {
                let a0 = -90 + share * Double(t - chunk.lowerBound)
                let spacing = max(2.6, dot * 2.3)
                let arcLen = Double(r) * share * .pi / 180
                let count = max(1, Int(arcLen / spacing))
                for i in 0..<count {
                    let ang = (a0 + share * (Double(i) + (ring.isMultiple(of: 2) ? 0 : 0.5)) / Double(count)) * .pi / 180
                    let pt = CGPoint(x: center.x + r * cos(ang), y: center.y + r * sin(ang))
                    disc.fill(Path(ellipseIn: CGRect(x: pt.x - dot / 2, y: pt.y - dot / 2, width: dot, height: dot)),
                              with: .color(c.dots))
                }
            }
            // an unrated emblem cuts no ring
            let runs = kind == .unrated ? [] : layout.runs[ring]
            guard !runs.isEmpty else { continue }
            let order = layout.order(of: ring)
            let progress = ch.cut(order), alpha = min(1, max(0, ch.alpha(order)))
            guard progress > 0, alpha > 0 else { continue }
            for run in runs {
                disc.stroke(arc(r, run.start, run.sweep * progress), with: .color(c.cut.opacity(alpha)),
                            style: StrokeStyle(lineWidth: cutW, lineCap: .butt))
            }
            if ring < 2 {
                let weight = (ring == 0 ? 0.85 : 0.55) * alpha
                tintRing(&disc, r, cutW, c.cut, weight, clip: runs.map { ($0.start, $0.start + $0.sweep * progress) })
            }
        }
        if !layout.tiny && kind != .unrated {
            for ring in 0..<layout.rings {
                let r = outer - pitch * (CGFloat(ring) + 0.5)
                let glow = ch.glow(layout.order(of: ring))
                if glow > 0.002 {
                    for run in layout.runs[ring] {
                        disc.stroke(arc(r, run.start, run.sweep), with: .color(c.lit.opacity(min(1, glow))),
                                    style: StrokeStyle(lineWidth: cutW, lineCap: .butt))
                    }
                }
                let whole = ch.ring(ring)
                if whole > 0.002 {
                    disc.stroke(circle(r), with: .color(c.lit.opacity(min(1, whole))), lineWidth: cutW)
                }
            }
            // the 10.0 moment: the rim pulses in flat accent
            if ch.flare > 0.002 {
                let fw = max(1.6, sz.width * 0.026)
                gc.stroke(circle(rimR - fw / 2), with: .color(c.flare.opacity(min(1, ch.flare))), lineWidth: fw)
            }
        }
    }
}

extension CoverSeed {
    /// Seed color for a palette (renders the mock cover through Palette).
    @MainActor static func seed(for palette: CoverPalette) -> Color {
        let album = MockLibrary.allAlbums.first { $0.palette == palette }
        return album.map { CoverSeed.seed(for: $0) } ?? palette.a
    }
}

/// An album's Bun as Android's album page builds it (AlbumDetailViewModel:
/// `album_ratings` → `userRating`, AlbumScrapbookSource's `local_ratings` →
/// the track average and rated set; AlbumDetailUiState.albumScore): the manual
/// album rating > the average of the locally rated tracks > N/A. Only this
/// device's records (and what cloud sync brought from Android) count — Android
/// never reads the server's stars here. Page 1, page 2 and Home all read this.
/// The mock library keeps its hand-set values (screenshots).
struct AlbumBun: Equatable {
    /// The album's own rating (> 0), else nil.
    var albumRating: Double?
    /// Each track's score (> 0), in track order; nil = not rated.
    var trackScores: [Double?]

    var rated: [Bool] { trackScores.map { $0 != nil } }
    var ratedCount: Int { trackScores.compactMap { $0 }.count }
    var average: Double? {
        let s = trackScores.compactMap { $0 }
        return s.isEmpty ? nil : s.reduce(0, +) / Double(s.count)
    }
    var kind: ScoreEmblem.Kind { ScoreEmblem.Kind(rating: albumRating, average: average) }
    var score: Double { albumRating ?? average ?? 0 }

    @MainActor
    static func of(_ album: Album, ratings: LocalRatings = .shared, memories: AlbumMemoryStore = .shared) -> AlbumBun {
        if album.id.provider == "mock" {
            return AlbumBun(albumRating: album.rating, trackScores: album.tracks.map(\.rating))
        }
        let provider = album.id.provider
        let own = memories.album(provider: provider, albumId: album.id.rawId)?.rating
        return AlbumBun(
            albumRating: own.flatMap { $0 > 0 ? $0 : nil },
            trackScores: album.tracks.map { t in
                ratings.rating(provider: t.id.provider, songId: t.id.rawId).flatMap { $0 > 0 ? $0 : nil }
            })
    }
}
