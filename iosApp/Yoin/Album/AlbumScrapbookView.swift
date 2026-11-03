import SwiftUI
import YoinShared

/// Album page 2, the scrapbook (Android detail/AlbumScrapbookPage.kt,
/// AlbumScrapbook.kt), phone width = one lane, every piece a full-width row
/// 28 pt apart on dot paper. Order: opening cluster (cover + emblem, title,
/// Last Play, review, album note) → per track in order: a cluster for tracks
/// with notes or the best score, contact sheets of two for score-only runs →
/// receipt. Tilts come from an FNV-1a hash of "albumId/key" (shared Kotlin; graphics only;
/// layout stays square). Mock content stands in for the user's records.
struct AlbumScrapbookView: View {
    var album: Album
    var settled: Bool
    @Environment(\.colorScheme) private var colorScheme
    @State private var stamped = false

    private var s: CoverScheme { CoverScheme(album: album, dark: colorScheme == .dark) }

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
            VStack(alignment: .leading, spacing: 28) {
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
        .defaultScrollAnchor(LaunchConfig.screen == "album-scrapbook-bottom" ? .bottom : .top)
        .onChange(of: settled, initial: true) { _, now in
            if now && !stamped {
                withAnimation(.spring(duration: 1.3, bounce: 0.35)) { stamped = true }
            }
        }
    }

    // MARK: Opening cluster

    private var opening: some View {
        let kind: ScoreEmblem.Kind = album.rating != nil ? .album : .average
        return VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .top, spacing: 0) {
                ZStack(alignment: .bottomTrailing) {
                    CoverArt(palette: album.palette, cornerRadius: 0, seed: album.seed)
                        .frame(width: 148, height: 148)
                        .clipShape(.rect(cornerRadius: 8, style: .continuous))
                        .rotationEffect(.degrees(tilt("cover", Tilt.cover)))
                        .frame(width: 148, height: 148 + 0.24 * 64, alignment: .top)
                    ScoreEmblem(score: album.rating ?? album.averageRating ?? 0, kind: kind,
                                rated: album.tracks.map { $0.rating != nil }, palette: album.palette, size: 64)
                        // The award "stamp" once page 2 settles: lands from a swell.
                        .scaleEffect(stamped ? 1 : 1.35)
                        .opacity(stamped ? 1 : 0.0)
                        .offset(x: 0.30 * 64)
                }
                Spacer().frame(width: 0.3 * 64 + 8)
                VStack(alignment: .leading, spacing: 0) {
                    Text(memoryTitle)
                        .font(.system(size: 22, weight: .semibold, design: .serif))
                        .foregroundStyle(s.onSurface)
                        .lineSpacing(5)
                    Text("Last Play")
                        .font(.gsf(14, .medium)).underline()
                        .foregroundStyle(s.onSurface)
                        .padding(.top, 16).padding(.bottom, 4)
                    Text("Yesterday 16:04")
                        .font(.gsf(16, .medium)).monospacedDigit()
                        .foregroundStyle(s.onSurface)
                }
                .padding(.top, 4)
            }
            // Title present → the review goes below, full width.
            VStack(alignment: .leading, spacing: 6) {
                Text(review)
                    .font(.gsf(15)).lineSpacing(8)
                    .foregroundStyle(s.onPrimaryContainer)
                Text("Yesterday")
                    .font(.gsf(12, .medium))
                    .foregroundStyle(s.onPrimaryContainer.opacity(0.6))
            }
            .padding(.horizontal, 16).padding(.vertical, 14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(s.primaryContainer, in: .rect(cornerRadius: 16, style: .continuous))
            .rotationEffect(.degrees(tilt("review", Tilt.paper)))
            // Album note sticky: own row on the end side, tucks 6 over the review.
            Text(albumNote)
                .font(.gsf(14.5)).lineSpacing(7)
                .foregroundStyle(s.onSecondaryContainer)
                .padding(.leading, 18).padding(.trailing, 18).padding(.top, 14).padding(.bottom, 18)
                .frame(width: min(0.72 * 368, 280), alignment: .leading)
                .background { StickyNote(fill: s.secondaryContainer,
                                         flap: s.secondaryContainer.mix(with: s.secondary, by: 0.32, in: .perceptual)) }
                .rotationEffect(.degrees(-sign(tilt("review", Tilt.paper)) * abs(tilt("albumnote", Tilt.paper))))
                .frame(maxWidth: .infinity, alignment: .trailing)
                .padding(.top, -6)
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
            VStack(alignment: mirrored ? .trailing : .leading, spacing: -12) {
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
                .frame(width: 48)
            VStack(alignment: .leading, spacing: 3) {
                Text(t.title).font(.gsf(mini ? 15 : 16, .semibold)).foregroundStyle(ink).lineLimit(2)
                // Duration and plays on one row; when it doesn't fit, plays
                // wrap under the duration (both kept). Mini tickets trail the score.
                let duration = Text(formatTime(t.durationSec)).fontWeight(.medium)
                let plays = HStack(spacing: 4) {
                    Text("\(t.number * 3 + 5)").fontWeight(.medium); Text("plays").opacity(0.6)
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
                .font(.gsf(12)).monospacedDigit()
                .foregroundStyle(ink.opacity(0.72))
            }
            .padding(.leading, 13).padding(.vertical, 10)
            Spacer(minLength: 8)
            if !mini {
                Text(score)
                    .font(.gsf(best ? 22 : 16, .semibold)).monospacedDigit()
                    .foregroundStyle(best ? ink : s.primary)
                    .padding(.trailing, 14)
            }
        }
        .padding(.trailing, mini ? 12 : 0)
        .frame(minHeight: mini ? 56 : 64)
        .frame(width: width)
        .frame(maxWidth: width == nil ? .infinity : nil)
        .background { TicketShape().fill(fill) }
        .overlay(alignment: .leading) {
            // Perforation at the stub.
            Path { p in p.move(to: CGPoint(x: 48, y: 8)); p.addLine(to: CGPoint(x: 48, y: 200)) }
                .stroke(ink.opacity(0.24), style: StrokeStyle(lineWidth: 1.5, dash: [4, 4]))
                .mask(Rectangle().padding(.vertical, 8))
        }
        .overlay(alignment: .topLeading) {
            if starred.contains(t.number) {
                YoinIcon("favorite_filled", size: 26)
                    .foregroundStyle(s.primary)
                    .rotationEffect(.degrees(tilt("\(t.number)/heart", Tilt.sticker)))
                    .offset(x: -9, y: -10)
            }
        }
        .rotationEffect(.degrees(tilt("\(t.number)/tk", Tilt.ticket)))
    }

    private func notesClipping(_ t: Track, _ ns: [Note]) -> some View {
        ZStack(alignment: .leading) {
            Capsule().fill(s.primary).frame(width: 3).padding(.vertical, 14).padding(.leading, 11)
            VStack(alignment: .leading, spacing: 0) {
                ForEach(Array(ns.prefix(3).enumerated()), id: \.offset) { _, n in
                    HStack(alignment: .firstTextBaseline, spacing: 0) {
                        Text(n.time).font(.gsf(12, .medium)).monospacedDigit().foregroundStyle(s.primary)
                            .frame(width: 42, alignment: .leading)
                        Text(n.text).font(.gsf(15)).lineSpacing(7).foregroundStyle(s.onSurface)
                    }
                    .padding(.vertical, 4)
                }
            }
            .padding(.leading, 24).padding(.trailing, 12).padding(.vertical, 8)
        }
        .frame(width: min(330, 0.78 * 368), alignment: .leading)
        .background(s.surfaceContainerHighest, in: .rect(cornerRadius: 16, style: .continuous))
        .rotationEffect(.degrees(-sign(tilt("\(t.number)/tk", Tilt.ticket)) * abs(tilt("\(t.number)/nt", Tilt.paper))))
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
        .rotationEffect(.degrees(tilt("receipt", Tilt.paper)))
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
}

// MARK: - Shapes

/// 16 pt card with 7 pt half-circle notches top and bottom at x = 48.
struct TicketShape: Shape {
    func path(in r: CGRect) -> Path {
        let card = Path(roundedRect: r, cornerRadius: 16, style: .continuous)
        var notches = Path()
        notches.addEllipse(in: CGRect(x: r.minX + 48 - 7, y: r.minY - 7, width: 14, height: 14))
        notches.addEllipse(in: CGRect(x: r.minX + 48 - 7, y: r.maxY - 7, width: 14, height: 14))
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
            let w = g.size.width, h = g.size.height
            ZStack {
                Path { p in
                    p.move(to: CGPoint(x: 16, y: 0))
                    p.addLine(to: CGPoint(x: w - 16, y: 0))
                    p.addQuadCurve(to: CGPoint(x: w, y: 16), control: CGPoint(x: w, y: 0))
                    p.addLine(to: CGPoint(x: w, y: h - 17))
                    p.addLine(to: CGPoint(x: w - 17, y: h))
                    p.addLine(to: CGPoint(x: 16, y: h))
                    p.addQuadCurve(to: CGPoint(x: 0, y: h - 16), control: CGPoint(x: 0, y: h))
                    p.addLine(to: CGPoint(x: 0, y: 16))
                    p.addQuadCurve(to: CGPoint(x: 16, y: 0), control: .zero)
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
        p.move(to: CGPoint(x: r.minX, y: r.minY + 20))
        p.addQuadCurve(to: CGPoint(x: r.minX + 20, y: r.minY), control: CGPoint(x: r.minX, y: r.minY))
        p.addLine(to: CGPoint(x: r.maxX - 20, y: r.minY))
        p.addQuadCurve(to: CGPoint(x: r.maxX, y: r.minY + 20), control: CGPoint(x: r.maxX, y: r.minY))
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
        Canvas { gc, size in
            var y: CGFloat = 8
            while y < size.height {
                var x: CGFloat = 8
                while x < size.width + 16 {
                    gc.fill(Path(ellipseIn: CGRect(x: x - 1.2, y: y - 1.2, width: 2.4, height: 2.4)), with: .color(color))
                    x += 16
                }
                y += 16
            }
        }
        .padding(.horizontal, -16)
    }
}
