import SwiftUI
import YoinShared

/// Album page 2, the scrapbook (Android detail/AlbumScrapbookPage.kt,
/// AlbumScrapbook.kt), phone width = one lane, every piece a full-width row
/// 28 pt apart on dot paper. Order: opening cluster (cover + emblem, title,
/// Last Play, review, album note) → per track in order: a cluster for tracks
/// with notes or the best score, contact sheets of two for score-only runs →
/// receipt. Tilts come from an FNV-1a hash of "albumId/key" (shared Kotlin; graphics only;
/// layout stays square). Mock content stands in for the user's records.
///
/// While the pager drags, each piece trails by its depth × 12 % of the page
/// and leans 3° further, settling to its own angle (Android scrapLayer). The
/// page scrolls under the album header with the system's soft scroll edge.
struct AlbumScrapbookView: View {
    var album: Album
    var settled: Bool
    /// The album header's height: the page scrolls under it.
    var topInset: CGFloat = 0
    /// The pager as the pieces read it: 0 settled here, 1 a page away.
    var pageOffset: CGFloat = 0
    var pageWidth: CGFloat = 402
    /// The hero cover is flying here: this page's cover waits hidden until it lands.
    var coverInFlight = false
    /// The cover's resting rect in `space` and its tilt (the flight's target).
    var onCoverPlaced: (CGRect, Double) -> Void = { _, _ in }

    /// The coordinate space the cover reports in; the host names it.
    static let space = "albumScrapbook"

    /// A top safe-area bar that only holds room for the album header (which
    /// floats above, outside the scroll view). Not `Color.clear`: an empty bar
    /// draws no scroll edge effect.
    static func edgeBar(height: CGFloat) -> some View {
        Rectangle().fill(.white.opacity(0.01)).frame(height: height).allowsHitTesting(false)
    }
    @Environment(\.colorScheme) private var colorScheme
    /// The stamp (Android CoverWithEmblem, D3 §8): once per album page, the
    /// emblem's award plays when the pager comes to rest here; until then it
    /// waits uncut. No haptics on the album page (D3 §11.12).
    @State private var stamped = false
    @State private var award: ScoreEmblemAward?

    private var s: CoverScheme { CoverScheme(album: album, dark: colorScheme == .dark) }

    /// The Memory title's serif (Android FontFamily.Serif), 22 SemiBold.
    private static let titleFont: UIFont = {
        let base = UIFont.systemFont(ofSize: 22, weight: .semibold)
        return base.fontDescriptor.withDesign(.serif).map { UIFont(descriptor: $0, size: 22) } ?? base
    }()

    // MARK: Mock records

    private struct Note { var time: String; var text: String }
    private let memoryTitle = "Paper moons and late buses"
    private let review = "Warm even on the cold tracks. The middle stretch from 余韻 to Kites in July is the reason I keep coming back; it sounds like the walk home after the last train."
    private let albumNote = "Bought on a rainy Tuesday in Shimokitazawa."
    private var notes: [Int: [Note]] {
        [3: [Note(time: "1:14", text: "The echo stays after the song is gone"),
             Note(time: "2:40", text: "That piano drop, every single time")],
         9: [Note(time: "0:52", text: "Sounds like a harbor at 6 a.m.")]]
    }
    private let starred: Set<Int> = [2, 3]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Tokens.detail.SCRAPBOOK_PIECE_GAP.cg) {
                opening
                ForEach(Array(blocks.enumerated()), id: \.offset) { i, b in
                    block(b, mirrored: (i + mirrorSeed).isMultiple(of: 2))
                }
                receipt
                    .frame(maxWidth: .infinity)
            }
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 112)
            .background { DotPaper(color: s.outlineVariant.opacity(colorScheme == .dark ? 0.35 : 0.45)) }
        }
        .scrollIndicators(.hidden)
        // The header's room as a bar: pieces fade under the status bar with
        // the system's soft edge; the host lays a ProgressiveEdgeBlur over
        // the header band, so nothing meets it in a hard cut.
        .scrollEdgeEffectStyle(.soft, for: .top)
        .safeAreaBar(edge: .top) { Self.edgeBar(height: topInset) }
        .defaultScrollAnchor(LaunchConfig.screen == "album-scrapbook-bottom" ? .bottom : .top)
        .onChange(of: awardKey, initial: true) { _, _ in
            // Built from the emblem's own inputs; rebuilt (at rest once stamped) when they change.
            let a = ScoreEmblemAward(score: emblemScore, kind: emblemKind, rated: emblemRated,
                                     size: Tokens.detail.SCRAPBOOK_BADGE.cg, haptics: false)
            if !stamped { a.setPending(true) }
            award = a
            stampIfSettled()
        }
        .onChange(of: settled) { _, _ in stampIfSettled() }
    }

    // MARK: Stamp

    private var emblemKind: ScoreEmblem.Kind { album.rating != nil ? .album : .average }
    private var emblemScore: Double { album.rating ?? album.averageRating ?? 0 }
    private var emblemRated: [Bool] { album.tracks.map { $0.rating != nil } }
    private var awardKey: String { "\(emblemScore)|\(emblemKind)|\(emblemRated)" }

    private func stampIfSettled() {
        guard settled, !stamped, let award else { return }
        award.play()
        stamped = true
    }

    // MARK: Opening cluster

    private var opening: some View {
        let kind: ScoreEmblem.Kind = album.rating != nil ? .album : .average
        let cover = Tokens.detail.SCRAPBOOK_COVER.cg
        let badge = Tokens.detail.SCRAPBOOK_BADGE.cg
        let overhangX = Tokens.detail.SCRAPBOOK_BADGE_OVERHANG_X.cg
        return VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .top, spacing: 0) {
                ZStack(alignment: .bottomTrailing) {
                    // Where the hero's cover lands: the slot's resting rect,
                    // measured outside the piece's own drift and tilt (Android
                    // restRect), so the target holds still while the pager moves.
                    Color.clear
                        .frame(width: cover, height: cover)
                        .onGeometryChange(for: CGRect.self) { $0.frame(in: .named(Self.space)) } action: {
                            onCoverPlaced($0, tilt("cover", Tilt.cover))
                        }
                        .overlay {
                            CoverArt(palette: album.palette, cornerRadius: 0, seed: album.seed)
                                .frame(width: cover, height: cover)
                                .clipShape(.rect(cornerRadius: Tokens.shape.HERO.cg, style: .continuous))
                                // Hidden until the hero's cover has landed: one cover on screen, never two.
                                .opacity(coverInFlight ? 0 : 1)
                                .modifier(layer(tilt("cover", Tilt.cover), depth: 0.25))
                        }
                        .frame(width: cover, height: cover + Tokens.detail.SCRAPBOOK_BADGE_OVERHANG_Y.cg * badge, alignment: .top)
                    ScoreEmblem(score: emblemScore, kind: kind, rated: emblemRated,
                                palette: album.palette, size: badge, award: award)
                        .offset(x: overhangX * badge)
                        .modifier(layer(0, depth: 0.6))
                }
                Spacer().frame(width: overhangX * badge + 8)
                VStack(alignment: .leading, spacing: 0) {
                    // OpeningTitle: the serif (a title someone or the AI wrote),
                    // 22 SemiBold, line 1.25 em (27.5).
                    Text(memoryTitle)
                        .font(Font(Self.titleFont))
                        .lineSpacing(max(0, 22 * 1.25 - Self.titleFont.lineHeight))
                        .foregroundStyle(s.onSurface)
                    // AlbumScrapLastPlay: AlbumSectionLabel (labelLarge
                    // underlined), 4, then titleMedium tabular.
                    Text("Last Play")
                        .underline()
                        .yoinRole(Tokens.type.labelLarge)
                        .foregroundStyle(s.onSurface)
                        .padding(.top, 16).padding(.bottom, 4)
                    Text("Yesterday 16:04")
                        .monospacedDigit()
                        .lineLimit(1)
                        .yoinRole(Tokens.type.titleMedium)
                        .foregroundStyle(s.onSurface)
                }
                .padding(.top, 4)
            }
            // Title present → the review goes below, full width.
            VStack(alignment: .leading, spacing: 6) {
                // ReviewClipping: GSF 15, line 1.55 em; its date in labelMedium.
                Text(review)
                    .font(.gsf(15)).lineSpacing(YoinType.lineSpacing(size: 15, lineHeight: 15 * 1.55))
                    .foregroundStyle(s.onPrimaryContainer)
                Text("Yesterday")
                    .yoinRole(Tokens.type.labelMedium)
                    .foregroundStyle(s.onPrimaryContainer.opacity(0.6))
            }
            .padding(.horizontal, 16).padding(.vertical, 14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(s.primaryContainer, in: .rect(cornerRadius: Tokens.shape.CARD.cg, style: .continuous))
            .modifier(layer(tilt("review", Tilt.paper), depth: 0.35))
            // Album note sticky: own row on the end side, tucks 6 over the review.
            Text(albumNote)
                // userTextStyle at 14.5: line 1.5 em.
                .font(.gsf(14.5)).lineSpacing(YoinType.lineSpacing(size: 14.5, lineHeight: 14.5 * 1.5))
                .foregroundStyle(s.onSecondaryContainer)
                .padding(.leading, 18).padding(.trailing, 18).padding(.top, 14).padding(.bottom, 18)
                .frame(width: min(0.72 * 368, Tokens.detail.SCRAPBOOK_STICKY_MAX_WIDTH.cg), alignment: .leading)
                .background { StickyNote(fill: s.secondaryContainer,
                                         flap: s.secondaryContainer.mix(with: s.secondary, by: 0.32, in: .perceptual)) }
                .modifier(layer(-sign(tilt("review", Tilt.paper)) * abs(tilt("albumnote", Tilt.paper)), depth: 0.45))
                .frame(maxWidth: .infinity, alignment: .trailing)
                .padding(.top, -Tokens.detail.SCRAPBOOK_STICKY_TUCK.cg)
                .accessibilityLabel("Album note: \(albumNote)")
        }
    }

    // MARK: Track blocks

    private enum Block { case cluster(Track), sheet([Track]) }

    /// Tracks with notes or the best score get a cluster; runs of score-only
    /// tracks become contact sheets of two; untouched tracks are skipped.
    private var blocks: [Block] {
        var out: [Block] = []
        var run: [Track] = []
        func flush() {
            var i = 0
            while i < run.count { out.append(.sheet(Array(run[i..<min(i + 2, run.count)]))); i += 2 }
            run.removeAll()
        }
        for t in album.tracks {
            if notes[t.number] != nil || t.id == bestTrack?.id {
                flush(); out.append(.cluster(t))
            } else if t.rating != nil {
                run.append(t)
            }
        }
        flush()
        return out
    }

    private var bestTrack: Track? {
        album.tracks.filter { ($0.rating ?? 0) >= 9 }.max { ($0.rating ?? 0) < ($1.rating ?? 0) }
    }

    private var mirrorSeed: Int { ScrapbookTilt.shared.startsMirrored(seed: album.id.rawId) ? 1 : 0 }

    @ViewBuilder
    private func block(_ b: Block, mirrored: Bool) -> some View {
        switch b {
        case .cluster(let t):
            VStack(alignment: mirrored ? .trailing : .leading, spacing: -Tokens.detail.SCRAPBOOK_PIECE_OVERLAP.cg) {
                ticket(t, width: min(280, max(200, 0.66 * 368)), mini: false)
                    .zIndex(0)
                if let ns = notes[t.number] {
                    notesClipping(t, ns)
                        .frame(maxWidth: .infinity, alignment: mirrored ? .leading : .trailing)
                        .zIndex(1)
                }
            }
            .frame(maxWidth: .infinity, alignment: mirrored ? .trailing : .leading)
            .padding(.top, 6)
        case .sheet(let ts):
            if ts.count == 2 {
                HStack(alignment: .top, spacing: 14) {
                    ticket(ts[0], width: nil, mini: true)
                    ticket(ts[1], width: nil, mini: true).padding(.top, 12)
                }
                .padding(.top, 6)
            } else {
                ticket(ts[0], width: min(220, max(168, 0.52 * 368)), mini: true)
                    .frame(maxWidth: .infinity, alignment: mirrored ? .trailing : .leading)
                    .padding(.top, 6)
            }
        }
    }

    private func ticket(_ t: Track, width: CGFloat?, mini: Bool) -> some View {
        let best = t.id == bestTrack?.id
        let fill = best ? s.primary : s.surfaceContainerHigh
        let ink = best ? s.onPrimary : s.onSurface
        let score = t.rating.map { String(format: "%.1f", $0) } ?? ""
        return HStack(spacing: 0) {
            Text(String(format: "%02d", t.number))
                .font(.gsf(mini ? 16 : 18, .medium)).monospacedDigit()
                .foregroundStyle(ink.opacity(0.72))
                .frame(width: Tokens.detail.SCRAPBOOK_TICKET_STUB.cg)
            VStack(alignment: .leading, spacing: 3) {
                // titleMedium SemiBold at 15 / 16, line 1.25 em.
                Text(t.title).font(.gsf(mini ? 15 : 16, .semibold)).foregroundStyle(ink).lineLimit(2)
                    .lineSpacing(YoinType.lineSpacing(size: mini ? 15 : 16, lineHeight: (mini ? 15 : 16) * 1.25))
                // Duration and plays on one row; when it doesn't fit, plays
                // wrap under the duration (both kept). Mini tickets trail the score.
                // ScrapTicketMetaLine: Stat groups in labelMedium at 12,
                // tracking 0: numbers Medium tabular, the unit Regular at 60 %.
                let duration = Text(formatTime(t.durationSec)).font(.gsf(12, .medium))
                let plays = HStack(spacing: 4) {
                    Text("\(t.number * 3 + 5)").font(.gsf(12, .medium))
                    Text("plays").font(.gsf(12)).opacity(0.6)
                }
                let miniScore = Text(score).font(.gsf(15, .semibold)).foregroundStyle(best ? ink : s.primary)
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 12) {
                        duration; plays
                        if mini { Spacer(minLength: 4); miniScore }
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: 12) {
                            duration
                            if mini { Spacer(minLength: 4); miniScore }
                        }
                        plays
                    }
                }
                .lineLimit(1)
                .monospacedDigit()
                .frame(minHeight: 16)
                .foregroundStyle(ink.opacity(0.72))
            }
            // The body takes the rest (weight 1): a mini ticket's score sits at
            // its end, 12 from the edge; a full one's column ends 8 before the score.
            .padding(.leading, 13).padding(.vertical, 10).padding(.trailing, mini ? 12 : 8)
            .frame(maxWidth: .infinity, alignment: .leading)
            if !mini {
                Text(score)
                    .font(.gsf(best ? 22 : 16, .semibold)).monospacedDigit()
                    .foregroundStyle(best ? ink : s.primary)
                    .padding(.trailing, 14)
            }
        }
        .frame(minHeight: mini ? 56 : 64)
        .frame(width: width)
        .frame(maxWidth: width == nil ? .infinity : nil)
        .background { TicketShape().fill(fill) }
        .overlay(alignment: .leading) {
            // Perforation at the stub.
            let stub = Tokens.detail.SCRAPBOOK_TICKET_STUB.cg
            Path { p in p.move(to: CGPoint(x: stub, y: 8)); p.addLine(to: CGPoint(x: stub, y: 200)) }
                .stroke(ink.opacity(0.24), style: StrokeStyle(lineWidth: 1.5, dash: [4, 4]))
                .mask(Rectangle().padding(.vertical, 8))
        }
        .overlay(alignment: .topLeading) {
            if starred.contains(t.number) {
                YoinIcon("favorite_filled", size: 26)
                    .foregroundStyle(s.primary)
                    // Rides on the ticket's layer: only a little extra lag of its own.
                    .modifier(layer(tilt("\(t.number)/heart", Tilt.sticker), depth: 0.1))
                    .offset(x: -9, y: -10)
            }
        }
        .modifier(layer(tilt("\(t.number)/tk", Tilt.ticket), depth: 0.5))
    }

    private func notesClipping(_ t: Track, _ ns: [Note]) -> some View {
        ZStack(alignment: .leading) {
            Capsule().fill(s.primary).frame(width: 3).padding(.vertical, 14).padding(.leading, 11)
            VStack(alignment: .leading, spacing: 0) {
                ForEach(Array(ns.prefix(3).enumerated()), id: \.offset) { _, n in
                    HStack(alignment: .firstTextBaseline, spacing: 0) {
                        Text(n.time).font(.gsf(12, .medium)).monospacedDigit().foregroundStyle(s.primary)
                            .frame(width: 42, alignment: .leading)
                        // userTextStyle: GSF 15, line 1.5 em.
                        Text(n.text).font(.gsf(15))
                            .lineSpacing(YoinType.lineSpacing(size: 15, lineHeight: 15 * 1.5))
                            .foregroundStyle(s.onSurface)
                    }
                    .padding(.vertical, 4)
                }
            }
            .padding(.leading, 24).padding(.trailing, 12).padding(.vertical, 8)
        }
        .frame(width: min(330, 0.78 * 368), alignment: .leading)
        .background(s.surfaceContainerHighest, in: .rect(cornerRadius: Tokens.shape.CARD.cg, style: .continuous))
        .modifier(layer(-sign(tilt("\(t.number)/tk", Tilt.ticket)) * abs(tilt("\(t.number)/nt", Tilt.paper)), depth: 0.4))
    }

    // MARK: Receipt

    private var receipt: some View {
        let rows: [(String, String)] = [
            ("Plays in Yoin", "42"), ("Days since first play", "18"), ("Last played", "Yesterday"),
            ("Rated", "\(album.ratedCount) / \(album.tracks.count)"), ("Notes", "4"),
        ]
        return VStack(alignment: .leading, spacing: 0) {
            Text("\(album.title) — \(album.artist)".uppercased())
                .font(.system(size: 11.5, weight: .medium, design: .monospaced))
                .foregroundStyle(s.onSurface.opacity(0.7))
                .padding(.bottom, 6)
            ForEach(rows, id: \.0) { label, value in
                HStack(alignment: .lastTextBaseline, spacing: 0) {
                    Text(label)
                    Path { p in p.move(to: CGPoint(x: 0, y: 8)); p.addLine(to: CGPoint(x: 400, y: 8)) }
                        .stroke(s.onSurface.opacity(0.3), style: StrokeStyle(lineWidth: 1.5, lineCap: .round, dash: [0.1, 4]))
                        .frame(height: 12)
                        .padding(.horizontal, 6)
                        .clipped()
                    Text(value)
                }
                .font(.system(size: 12.5, design: .monospaced))
                .foregroundStyle(s.onSurface)
                .frame(height: 24)
            }
        }
        .padding(.leading, 18).padding(.trailing, 18).padding(.top, 16).padding(.bottom, 24)
        .frame(maxWidth: 330)
        .background { ReceiptShape().fill(s.surfaceContainer) }
        .modifier(layer(tilt("receipt", Tilt.paper), depth: 0.35))
    }

    // MARK: Tilt

    /// The tilt sets and the FNV-1a hash are the shared Kotlin (ScrapbookTilt, YoinShared).
    private enum Tilt {
        static var paper: KotlinFloatArray { ScrapbookTilt.shared.PAPER }
        static var ticket: KotlinFloatArray { ScrapbookTilt.shared.TICKET }
        static var sticker: KotlinFloatArray { ScrapbookTilt.shared.STICKER }
        static var cover: KotlinFloatArray { ScrapbookTilt.shared.COVER }
    }

    private func tilt(_ key: String, _ set: KotlinFloatArray) -> Double {
        Double(ScrapbookTilt.shared.tilt(seed: album.id.rawId, key: key, set: set))
    }

    private func sign(_ x: Double) -> Double { x < 0 ? -1 : 1 }

    private func layer(_ tilt: Double, depth: CGFloat) -> ScrapLayer {
        ScrapLayer(tilt: tilt, depth: depth, offset: pageOffset, width: pageWidth)
    }
}

/// A piece's resting tilt plus its share of the pager drag (Android
/// scrapLayer): it trails by depth × 12 % of the page and leans 3° further
/// while away. Transform only; layout and hit areas stay square.
private struct ScrapLayer: ViewModifier {
    static var share: CGFloat { Tokens.detail.SCRAPBOOK_PARALLAX_SHARE.cg }
    static var leanDegrees: Double { Tokens.detail.SCRAPBOOK_LEAN_DEGREES }
    var tilt: Double
    var depth: CGFloat
    var offset: CGFloat
    var width: CGFloat

    func body(content: Content) -> some View {
        let lean = tilt == 0 ? 0 : (tilt < 0 ? -1 : 1) * Self.leanDegrees * Double(offset)
        content
            .rotationEffect(.degrees(tilt + lean))
            .offset(x: offset * width * depth * Self.share)
    }
}

// MARK: - Shapes

/// Card-cornered (16) ticket with 7 pt half-circle notches top and bottom at
/// the stub's edge (x = 48).
struct TicketShape: Shape {
    func path(in r: CGRect) -> Path {
        let card = Path(roundedRect: r, cornerRadius: Tokens.shape.CARD.cg, style: .continuous)
        let x = r.minX + Tokens.detail.SCRAPBOOK_TICKET_STUB.cg, n = Tokens.detail.SCRAPBOOK_TICKET_NOTCH.cg
        var notches = Path()
        notches.addEllipse(in: CGRect(x: x - n, y: r.minY - n, width: 2 * n, height: 2 * n))
        notches.addEllipse(in: CGRect(x: x - n, y: r.maxY - n, width: 2 * n, height: 2 * n))
        return card.subtracting(notches)
    }
}

/// Sticky note: 16 pt corners, bottom-right corner cut from (w−17, h) to
/// (w, h−17), with the folded flap drawn in the flap color.
struct StickyNote: View {
    var fill: Color
    var flap: Color
    var body: some View {
        GeometryReader { g in
            let w = g.size.width, h = g.size.height, c = Tokens.shape.CARD.cg
            ZStack {
                Path { p in
                    p.move(to: CGPoint(x: c, y: 0))
                    p.addLine(to: CGPoint(x: w - c, y: 0))
                    p.addQuadCurve(to: CGPoint(x: w, y: c), control: CGPoint(x: w, y: 0))
                    p.addLine(to: CGPoint(x: w, y: h - 17))
                    p.addLine(to: CGPoint(x: w - 17, y: h))
                    p.addLine(to: CGPoint(x: c, y: h))
                    p.addQuadCurve(to: CGPoint(x: 0, y: h - c), control: CGPoint(x: 0, y: h))
                    p.addLine(to: CGPoint(x: 0, y: c))
                    p.addQuadCurve(to: CGPoint(x: c, y: 0), control: .zero)
                }
                .fill(fill)
                Path { p in
                    p.move(to: CGPoint(x: w - 17, y: h))
                    p.addLine(to: CGPoint(x: w - 17, y: h - 13))
                    p.addQuadCurve(to: CGPoint(x: w - 13, y: h - 17), control: CGPoint(x: w - 17, y: h - 17))
                    p.addLine(to: CGPoint(x: w, y: h - 17))
                    p.closeSubpath()
                }
                .fill(flap)
            }
        }
    }
}

/// Receipt: 20 pt top corners, zig-zag bottom (≈14 pt teeth, 8 pt deep).
struct ReceiptShape: Shape {
    func path(in r: CGRect) -> Path {
        var p = Path()
        let teeth = max(1, Int((r.width / 14).rounded()))
        let tw = r.width / CGFloat(teeth)
        // Circular 20 pt arcs, as Compose's arcTo.
        let c = min(20, min(r.width, r.height) / 2)
        p.move(to: CGPoint(x: r.minX, y: r.minY + c))
        p.addArc(center: CGPoint(x: r.minX + c, y: r.minY + c), radius: c,
                 startAngle: .degrees(180), endAngle: .degrees(270), clockwise: false)
        p.addLine(to: CGPoint(x: r.maxX - c, y: r.minY))
        p.addArc(center: CGPoint(x: r.maxX - c, y: r.minY + c), radius: c,
                 startAngle: .degrees(270), endAngle: .degrees(0), clockwise: false)
        p.addLine(to: CGPoint(x: r.maxX, y: r.maxY - 8))
        for i in (0..<teeth).reversed() {
            let x0 = r.minX + CGFloat(i) * tw
            p.addLine(to: CGPoint(x: x0 + tw / 2, y: r.maxY))
            p.addLine(to: CGPoint(x: x0, y: r.maxY - 8))
        }
        p.closeSubpath()
        return p
    }
}

/// 16 pt tiles with a 1.2 pt dot at the centre.
struct DotPaper: View {
    var color: Color
    var body: some View {
        let pitch = Tokens.detail.SCRAPBOOK_DOT_SPACING.cg, dot = Tokens.detail.SCRAPBOOK_DOT_RADIUS.cg
        Canvas { gc, size in
            var y: CGFloat = pitch / 2
            while y < size.height {
                var x: CGFloat = pitch / 2
                while x < size.width + pitch {
                    gc.fill(Path(ellipseIn: CGRect(x: x - dot, y: y - dot, width: 2 * dot, height: 2 * dot)), with: .color(color))
                    x += pitch
                }
                y += pitch
            }
        }
        .padding(.horizontal, -16)
    }
}

/// A progressive blur for content passing under a floating header: a thin
/// material plus the page's own color, solid at the top and fading out over
/// the lower 45 %. Draws only; touches pass through.
struct ProgressiveEdgeBlur: View {
    var tint: Color

    var body: some View {
        ZStack {
            Rectangle().fill(.thinMaterial)
            Rectangle().fill(tint.opacity(0.72))
        }
        .mask {
            LinearGradient(stops: [.init(color: .black, location: 0), .init(color: .black, location: 0.55),
                                   .init(color: .black.opacity(0.6), location: 0.75), .init(color: .clear, location: 1)],
                           startPoint: .top, endPoint: .bottom)
        }
        .allowsHitTesting(false)
    }
}
