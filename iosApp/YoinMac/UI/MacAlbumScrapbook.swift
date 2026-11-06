import AppKit
import SwiftUI
import YoinShared

/// The album's scrapbook on the Mac (iOS AlbumScrapbookView, Android AlbumScrapbookPage): the same pieces
/// on dot paper — the cover with its score stamp, the Memory title (click to rename), Last Play, the review
/// clipping (click: the Rate sheet) or the blank pen, then per song a ticket (a cluster when it has notes or
/// the best score, contact sheets of two for score-only runs) with its notes, and the receipt. A ticket's
/// click opens the song in place: its score and its notes (add, edit, delete). The listener's own records
/// (AlbumMemoryStore, NotesStore, LocalRatings, HistoryStore), synced like everywhere else.
struct MacAlbumScrapbook: View {
    var album: Album
    var onRate: () -> Void
    @Environment(MacModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme
    @State private var stamped = false
    @State private var award: ScoreEmblemAward?
    @State private var renaming = false
    @State private var titleDraft = ""
    @FocusState private var titleFocused: Bool
    @State private var openTrack: String?

    private var s: CoverScheme { CoverScheme(album: album, dark: colorScheme == .dark) }
    private var provider: String { album.id.provider }
    private var memories: AlbumMemoryStore { .shared }
    private var bun: AlbumBun { AlbumBun.of(album, memories: memories) }

    /// One lane, as on the phone, centered in the page.
    static let lane: CGFloat = 560
    private static let titleFont: NSFont = {
        let base = NSFont.systemFont(ofSize: 22, weight: .semibold)
        return base.fontDescriptor.withDesign(.serif).flatMap { NSFont(descriptor: $0, size: 22) } ?? base
    }()

    // MARK: Records

    private struct Note { var time: String; var text: String }

    private var memoryTitle: String? { memories.memoryTitle(provider: provider, albumId: album.id.rawId) }
    private var userTitle: String? { memories.userTitle(provider: provider, albumId: album.id.rawId) }
    private var aiTitle: String? {
        let t = memories.memory(provider: provider, entityId: album.id.rawId)?.title?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return t?.isEmpty == false ? t : nil
    }
    private var review: String? {
        let text = memories.album(provider: provider, albumId: album.id.rawId)?.review?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return text?.isEmpty == false ? text : nil
    }
    private var reviewAt: String? {
        guard let at = memories.album(provider: provider, albumId: album.id.rawId)?.reviewUpdatedAt else { return nil }
        return HomeHistory.relativeDay(at, now: Self.now)
    }
    private func notes(_ t: Track) -> [Note]? {
        let list = NotesStore.shared.notes(provider: provider, trackId: t.id.rawId)
        return list.isEmpty ? nil : list.map { Note(time: $0.positionMs.map(NotesStore.formatPosition) ?? "", text: $0.content) }
    }
    private func score(_ t: Track) -> Double? {
        guard let i = album.tracks.firstIndex(where: { $0.id == t.id }) else { return nil }
        return bun.trackScores[i]
    }
    private var stats: (plays: Int, first: Int64?, last: Int64?, songs: [String: Int]) {
        let h = HistoryStore.shared
        let scope = model.session.accountKey
        let st = h.albumStats(scope: scope, provider: provider, albumId: album.id.rawId)
        return (st?.playCount ?? 0, st?.firstPlayedAt, st?.lastPlayedAt,
                h.songPlayCounts(scope: scope, provider: provider, albumId: album.id.rawId))
    }
    private func playCount(_ t: Track) -> Int { stats.songs[t.id.rawId] ?? (t.playCount ?? 0) }
    private static var now: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    var body: some View {
        VStack(alignment: .leading, spacing: Tokens.detail.SCRAPBOOK_PIECE_GAP.cg) {
            opening
            ForEach(Array(blocks.enumerated()), id: \.offset) { i, b in
                block(b, mirrored: (i + mirrorSeed).isMultiple(of: 2))
            }
            if stats.plays > 0 {
                receipt.frame(maxWidth: .infinity)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 20)
        .frame(width: Self.lane)
        .background { DotPaper(color: s.outlineVariant.opacity(colorScheme == .dark ? 0.35 : 0.45)) }
        .frame(maxWidth: .infinity)
        .onChange(of: awardKey, initial: true) { _, _ in
            let a = ScoreEmblemAward(score: bun.score, kind: bun.kind, rated: bun.rated,
                                     size: Tokens.detail.SCRAPBOOK_BADGE.cg, haptics: false)
            if !stamped { a.setPending(true) }
            award = a
        }
        .task(id: album.id) {
            // The stamp plays once the page has come to rest on the scrapbook.
            try? await Task.sleep(for: .milliseconds(450))
            guard !stamped, let award else { return }
            award.play()
            stamped = true
        }
    }

    private var awardKey: String { "\(bun.score)|\(bun.kind)|\(bun.rated)" }

    // MARK: Opening cluster

    private var opening: some View {
        let cover = Tokens.detail.SCRAPBOOK_COVER.cg
        let badge = Tokens.detail.SCRAPBOOK_BADGE.cg
        let overhangX = Tokens.detail.SCRAPBOOK_BADGE_OVERHANG_X.cg
        return VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .top, spacing: 0) {
                ZStack(alignment: .bottomTrailing) {
                    MacCover(palette: album.palette, corner: Tokens.shape.HERO.cg)
                        .frame(width: cover, height: cover)
                        .rotationEffect(.degrees(tilt("cover", Tilt.cover)))
                        .frame(width: cover, height: cover + Tokens.detail.SCRAPBOOK_BADGE_OVERHANG_Y.cg * badge, alignment: .top)
                    ScoreEmblem(score: bun.score, kind: bun.kind, rated: bun.rated, palette: album.palette, size: badge,
                                award: bun.kind == .unrated ? nil : award, onCover: true)
                        .offset(x: overhangX * badge)
                }
                Spacer().frame(width: overhangX * badge + 12)
                VStack(alignment: .leading, spacing: 0) {
                    if let memoryTitle { openingTitle(memoryTitle) }
                    if let last = stats.last {
                        let l = HomeHistory.lastPlayLabels(last, now: Self.now)
                        Text("Last Play").underline()
                            .font(.gsf(Tokens.type.labelLarge.size.cg, .medium)).foregroundStyle(s.onSurface)
                            .padding(.top, 16).padding(.bottom, 4)
                        Text("\(l.day) \(l.time)").monospacedDigit().lineLimit(1)
                            .font(.gsf(Tokens.type.titleMedium.size.cg, .medium)).foregroundStyle(s.onSurface)
                    }
                }
                .padding(.top, 4)
            }
            if let review {
                Button(action: onRate) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(review).font(.gsf(15)).lineSpacing(15 * 0.55 * 0.5)
                            .foregroundStyle(s.onPrimaryContainer)
                            .fixedSize(horizontal: false, vertical: true)
                        if let reviewAt {
                            Text(reviewAt).font(.gsf(12, .medium)).foregroundStyle(s.onPrimaryContainer.opacity(0.6))
                        }
                    }
                    .padding(.horizontal, 16).padding(.vertical, 14)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(s.primaryContainer, in: .rect(cornerRadius: Tokens.shape.CARD.cg, style: .continuous))
                    .contentShape(.rect(cornerRadius: Tokens.shape.CARD.cg, style: .continuous))
                }
                .buttonStyle(.plain)
                .help("Edit Review")
                .rotationEffect(.degrees(tilt("review", Tilt.paper)))
            } else {
                Button(action: onRate) {
                    YoinIcon("edit", size: 20).foregroundStyle(s.onSurface)
                        .frame(width: 48, height: 48)
                        .overlay { Circle().strokeBorder(s.outline, style: StrokeStyle(lineWidth: 1.5, dash: [5, 5])) }
                        .contentShape(.circle)
                }
                .buttonStyle(PressDown())
                .help("Write a Review")
                .padding(.top, 8)
            }
        }
    }

    @ViewBuilder
    private func openingTitle(_ title: String) -> some View {
        if renaming {
            HStack(alignment: .top, spacing: 4) {
                TextField("", text: $titleDraft)
                    .textFieldStyle(.plain)
                    .font(Font(Self.titleFont))
                    .foregroundStyle(s.onSurface)
                    .focused($titleFocused)
                    .onSubmit { commitTitle(was: title) }
                    .onExitCommand { renaming = false }
                if userTitle != nil, aiTitle != nil {
                    Button {
                        renaming = false
                        setTitle(nil)
                    } label: {
                        YoinIcon("sparkle", size: 20).foregroundStyle(s.onSurfaceVariant)
                            .frame(width: 32, height: 32).contentShape(.circle)
                    }
                    .buttonStyle(PressDown())
                    .help("Restore Yoin's Title")
                }
            }
            .onAppear { titleFocused = true }
            .onChange(of: titleFocused) { was, now in if was, !now { commitTitle(was: title) } }
        } else {
            Text(title).font(Font(Self.titleFont)).foregroundStyle(s.onSurface)
                .fixedSize(horizontal: false, vertical: true)
                .contentShape(.rect)
                .onTapGesture {
                    titleDraft = userTitle ?? title
                    renaming = true
                }
                .help("Rename")
        }
    }

    private func commitTitle(was title: String) {
        guard renaming else { return }
        renaming = false
        let text = titleDraft.replacingOccurrences(of: "\n", with: " ").trimmingCharacters(in: .whitespacesAndNewlines)
        guard text != title else { return }
        setTitle(text.isEmpty ? nil : String(text.prefix(80)))
    }

    private func setTitle(_ text: String?) {
        withAnimation(Yoin.effects) {
            if let text {
                memories.putTitle(.init(provider: provider, albumId: album.id.rawId, title: text, updatedAt: Self.now))
            } else {
                memories.removeTitle(provider: provider, albumId: album.id.rawId)
            }
        }
        CloudSync.shared.localChanged()
    }

    // MARK: Track blocks

    private enum Block { case cluster(Track), sheet([Track]) }

    private var blocks: [Block] {
        var out: [Block] = []
        var run: [Track] = []
        func flush() {
            var i = 0
            while i < run.count { out.append(.sheet(Array(run[i..<min(i + 2, run.count)]))); i += 2 }
            run.removeAll()
        }
        for t in album.tracks {
            if notes(t) != nil || t.id == bestTrack?.id {
                flush(); out.append(.cluster(t))
            } else if score(t) != nil {
                run.append(t)
            }
        }
        flush()
        return out
    }

    private var bestTrack: Track? {
        album.tracks.filter { (score($0) ?? 0) >= 9 }.max { (score($0) ?? 0) < (score($1) ?? 0) }
    }

    private var mirrorSeed: Int { ScrapbookTilt.shared.startsMirrored(seed: album.id.rawId) ? 1 : 0 }

    @ViewBuilder
    private func block(_ b: Block, mirrored: Bool) -> some View {
        switch b {
        case .cluster(let t):
            VStack(alignment: mirrored ? .trailing : .leading, spacing: -Tokens.detail.SCRAPBOOK_PIECE_OVERLAP.cg) {
                ticket(t, width: 300, mini: false).zIndex(0)
                if let ns = notes(t) {
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
                ticket(ts[0], width: 240, mini: true)
                    .frame(maxWidth: .infinity, alignment: mirrored ? .trailing : .leading)
                    .padding(.top, 6)
            }
        }
    }

    private func ticket(_ t: Track, width: CGFloat?, mini: Bool) -> some View {
        let best = t.id == bestTrack?.id
        let fill = best ? s.primary : s.surfaceContainerHigh
        let ink = best ? s.onPrimary : s.onSurface
        let scoreText = score(t).map { String(format: "%.1f", $0) } ?? ""
        return HStack(spacing: 0) {
            Text(String(format: "%02d", t.number))
                .font(.gsf(mini ? 16 : 18, .medium)).monospacedDigit()
                .foregroundStyle(ink.opacity(0.72))
                .frame(width: Tokens.detail.SCRAPBOOK_TICKET_STUB.cg)
            VStack(alignment: .leading, spacing: 3) {
                Text(t.title).font(.gsf(mini ? 15 : 16, .semibold)).foregroundStyle(ink).lineLimit(2)
                HStack(spacing: 12) {
                    Text(formatTime(t.durationSec)).font(.gsf(12, .medium))
                    HStack(spacing: 4) {
                        Text("\(playCount(t))").font(.gsf(12, .medium))
                        Text("plays").font(.gsf(12)).opacity(0.6)
                    }
                    if mini {
                        Spacer(minLength: 4)
                        Text(scoreText).font(.gsf(15, .semibold)).foregroundStyle(best ? ink : s.primary)
                    }
                }
                .lineLimit(1).monospacedDigit()
                .foregroundStyle(ink.opacity(0.72))
            }
            .padding(.leading, 13).padding(.vertical, 10).padding(.trailing, mini ? 12 : 8)
            .frame(maxWidth: .infinity, alignment: .leading)
            if !mini {
                Text(scoreText).font(.gsf(best ? 22 : 16, .semibold)).monospacedDigit()
                    .foregroundStyle(best ? ink : s.primary)
                    .padding(.trailing, 14)
            }
        }
        .frame(minHeight: mini ? 56 : 64)
        .frame(width: width)
        .frame(maxWidth: width == nil ? .infinity : nil)
        .background { TicketShape().fill(fill) }
        .overlay(alignment: .leading) {
            let stub = Tokens.detail.SCRAPBOOK_TICKET_STUB.cg
            Path { p in p.move(to: CGPoint(x: stub, y: 8)); p.addLine(to: CGPoint(x: stub, y: 200)) }
                .stroke(ink.opacity(0.24), style: StrokeStyle(lineWidth: 1.5, dash: [4, 4]))
                .mask(Rectangle().padding(.vertical, 8))
        }
        .overlay(alignment: .topLeading) {
            if t.starred {
                YoinIcon("favorite_filled", size: 26).foregroundStyle(s.primary)
                    .rotationEffect(.degrees(tilt("\(t.number)/heart", Tilt.sticker)))
                    .offset(x: -9, y: -10)
            }
        }
        .contentShape(TicketShape())
        .onTapGesture { openTrack = t.id.rawId }
        .popover(isPresented: Binding(get: { openTrack == t.id.rawId }, set: { if !$0 { openTrack = nil } }),
                 arrowEdge: .trailing) {
            MacScrapTrack(track: t, album: album, scheme: s)
        }
        .help("Score and Notes")
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
                        Text(n.text).font(.gsf(15)).foregroundStyle(s.onSurface)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .padding(.vertical, 4)
                }
            }
            .padding(.leading, 24).padding(.trailing, 12).padding(.vertical, 8)
        }
        .frame(width: 340, alignment: .leading)
        .background(s.surfaceContainerHighest, in: .rect(cornerRadius: Tokens.shape.CARD.cg, style: .continuous))
        .rotationEffect(.degrees(-sign(tilt("\(t.number)/tk", Tilt.ticket)) * abs(tilt("\(t.number)/nt", Tilt.paper))))
        .onTapGesture { openTrack = t.id.rawId }
    }

    // MARK: Receipt

    private var receipt: some View {
        let st = stats
        var rows: [(String, String)] = [("Plays in Yoin", "\(st.plays)")]
        if let first = st.first { rows.append(("Days since first play", "\(HomeHistory.calendarDays(from: first, to: Self.now))")) }
        if let last = st.last { rows.append(("Last played", HomeHistory.relativeDay(last, now: Self.now))) }
        let noteCount = album.tracks.reduce(0) { $0 + (notes($1)?.count ?? 0) }
        let asked = album.tracks.reduce(0) { n, t in
            n + SongAboutStore.shared.entries(SongAboutStore.key(title: t.title, artist: t.artist ?? album.artist,
                                                                 album: album.title))
                .filter { $0.kind == SongAboutStore.Kind.ask }.count
        }
        rows += [("Rated", "\(album.tracks.filter { score($0) != nil }.count) / \(album.tracks.count)"),
                 ("Notes", "\(noteCount)"), ("Asked", "\(asked)")]
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
                        .frame(height: 12).padding(.horizontal, 6).clipped()
                    Text(value)
                }
                .font(.system(size: 12.5, design: .monospaced))
                .foregroundStyle(s.onSurface)
                .frame(height: 24)
            }
        }
        .padding(.leading, 18).padding(.trailing, 18).padding(.top, 16).padding(.bottom, 24)
        .frame(maxWidth: 340)
        .background { ReceiptShape().fill(s.surfaceContainer) }
        .rotationEffect(.degrees(tilt("receipt", Tilt.paper)))
    }

    // MARK: Tilt (shared Kotlin ScrapbookTilt)

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

/// A ticket opened in place (iOS ScrapTrackSheet): the song's score — 0–10 in tenths, Clear to never
/// rated — and its notes, each editable or deleted where it is, a new one added at the bottom.
private struct MacScrapTrack: View {
    var track: Track
    var album: Album
    var scheme: CoverScheme
    @Environment(MacModel.self) private var model
    @State private var score: Double = 0
    @State private var rated = false
    @State private var editing: String?
    @State private var editDraft = ""
    @State private var draft = ""

    private var notes: [SongNote] { NotesStore.shared.notes(provider: track.id.provider, trackId: track.id.rawId) }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(track.title).font(.gsf(15, .semibold)).lineLimit(2)
            HStack(spacing: 10) {
                Slider(value: Binding(get: { score }, set: { score = $0; rated = true }), in: 0...10, step: 0.1) { editingNow in
                    if !editingNow, rated { save() }
                }
                Text(rated ? String(format: "%.1f", score) : "–").font(.gsf(18, .semibold).monospacedDigit())
                    .frame(width: 40, alignment: .trailing)
                Button("Clear") {
                    rated = false
                    score = 0
                    model.library.setRating(provider: track.id.provider, songId: track.id.rawId, nil)
                }
                .disabled(!rated)
            }
            Divider()
            Text("Notes").font(.gsf(13, .medium)).foregroundStyle(Yoin.inkVariant)
            ForEach(notes) { n in
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(n.positionMs.map(NotesStore.formatPosition) ?? "").font(.gsf(12, .medium)).monospacedDigit()
                        .foregroundStyle(scheme.primary).frame(width: 40, alignment: .leading)
                    if editing == n.id {
                        TextField("", text: $editDraft, axis: .vertical)
                            .textFieldStyle(.plain).font(.gsf(14))
                            .onSubmit { commitEdit(n) }
                        Button { commitEdit(n) } label: { YoinIcon("check", size: 16) }.buttonStyle(.plain)
                    } else {
                        Text(n.content).font(.gsf(14)).fixedSize(horizontal: false, vertical: true)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .contentShape(Rectangle())
                            .onTapGesture { editing = n.id; editDraft = n.content }
                        Button {
                            if NotesStore.shared.delete(id: n.id) != nil { CloudSync.shared.localChanged() }
                        } label: { YoinIcon("delete", size: 16).foregroundStyle(Yoin.inkVariant) }
                            .buttonStyle(.plain).help("Delete Note")
                    }
                }
            }
            HStack(spacing: 8) {
                TextField("Add a note", text: $draft, axis: .vertical).textFieldStyle(.plain).font(.gsf(14))
                    .onSubmit(add)
                Button(action: add) { YoinIcon("add", size: 16) }.buttonStyle(.plain)
                    .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            .padding(8)
            .background(scheme.surfaceContainerHigh, in: .rect(cornerRadius: 10, style: .continuous))
        }
        .padding(16)
        .frame(width: 340)
        .onAppear {
            let r = model.library.rating(track)
            rated = r != nil
            score = r ?? 0
        }
    }

    private func save() {
        model.library.setRating(provider: track.id.provider, songId: track.id.rawId, (score * 10).rounded() / 10)
    }

    private func commitEdit(_ n: SongNote) {
        if editDraft != n.content, NotesStore.shared.update(id: n.id, content: editDraft) != nil {
            CloudSync.shared.localChanged()
        }
        editing = nil
    }

    /// A note written here has no moment in the song (Android: a note from the scrapbook carries none).
    private func add() {
        if NotesStore.shared.add(provider: track.id.provider, trackId: track.id.rawId, content: draft, positionMs: nil,
                                 title: track.title, artist: track.artist ?? album.artist) != nil {
            CloudSync.shared.localChanged()
        }
        draft = ""
    }
}
