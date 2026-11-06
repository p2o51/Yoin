import SwiftUI

/// Library › Memories: the deck as a grid, a click opens the diary.
struct MacMemoriesGrid: View {
    @Environment(MacModel.self) private var model
    @State private var open: MemoryCard?

    var body: some View {
        let cards = model.memoryCards()
        VStack(alignment: .leading, spacing: 0) {
            if cards.isEmpty {
                Text("Rate songs, write notes or a review, and the albums you keep something for gather here.")
                    .font(.gsf(14)).foregroundStyle(Yoin.inkVariant).padding(.top, 8)
            }
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 200), spacing: 20, alignment: .top)], spacing: 24) {
                ForEach(cards) { c in MemoryTile(card: c).onTapGesture { open = c } }
            }
        }
        .sheet(item: $open) { c in MacMemoryDiary(card: c) }
        #if DEBUG
        .onReceive(NotificationCenter.default.publisher(for: Notification.Name("YoinQADiary"))) { _ in open = cards.first }
        #endif
        .task(id: model.session.accountKey) { await model.hydrateMemoryAlbums() }
    }
}

private struct MemoryTile: View {
    var card: MemoryCard
    @State private var hover = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            MacCover(palette: card.album.palette)
                .scaleEffect(hover ? 1.02 : 1)
            HStack(alignment: .firstTextBaseline) {
                Text(card.title).font(.gsf(15, .semibold)).foregroundStyle(Yoin.ink).lineLimit(2)
                Spacer(minLength: 6)
                if let s = card.score { Text(String(format: "%.1f", s)).font(.gsf(15, .medium).monospacedDigit()) }
            }
            Text(card.album.artist).font(.gsf(12.5)).foregroundStyle(Yoin.inkVariant).lineLimit(1)
            if let e = card.excerpt {
                Text(e.text).font(.gsf(13)).foregroundStyle(Yoin.ink).lineLimit(3)
                    .padding(.top, 2)
            }
            if card.noteCount > 0 {
                Text("\(card.noteCount) \(card.noteCount == 1 ? "note" : "notes")").font(.gsf(12)).foregroundStyle(Yoin.ink3)
            }
            // A diary's words that didn't reach NeoDB after it closed: said here, with Retry.
            if AlbumNeoDBSync.shared.state(card.album) == .failed {
                HStack(spacing: 6) {
                    Text("Couldn't sync to NeoDB").font(.gsf(12)).foregroundStyle(.red)
                    Button("Retry") { Task { await AlbumNeoDBSync.shared.retry(card.album) } }
                        .buttonStyle(.link).font(.gsf(12))
                }
            }
        }
        .contentShape(Rectangle())
        .onHover { h in withAnimation(Yoin.spatialFast) { hover = h } }
    }
}

/// A memory's diary (iOS MemoryDiary): your title and review (saved to AlbumMemoryStore, synced), Yoin's
/// paragraph when one was written, the rated and noted songs with their notes, the play numbers.
struct MacMemoryDiary: View {
    var card: MemoryCard
    @Environment(MacModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var title = ""
    @State private var review = ""
    /// What the fields held when the diary opened (an untouched field is never written back).
    @State private var openedTitle = ""
    @State private var openedReview = ""
    @State private var failed = false

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top, spacing: 16) {
                MacCover(palette: card.album.palette).frame(width: 96, height: 96)
                VStack(alignment: .leading, spacing: 4) {
                    TextField(card.album.title, text: $title)
                        .textFieldStyle(.plain).font(.gsf(22, .semibold))
                    Text(card.album.artist).font(.gsf(14)).foregroundStyle(Yoin.inkVariant)
                    if let s = card.score {
                        Text(String(format: "%.1f", s)).font(.gsf(15, .medium).monospacedDigit())
                    }
                    Text(stats).font(.gsf(12.5)).foregroundStyle(Yoin.ink3)
                }
            }
            if let p = card.paragraph {
                Text(p).font(.gsf(14)).foregroundStyle(Yoin.inkVariant).fixedSize(horizontal: false, vertical: true)
            }
            Text("Your review").font(.gsf(12.5, .medium)).foregroundStyle(Yoin.inkVariant)
            TextEditor(text: $review)
                .font(.gsf(14)).scrollContentBackground(.hidden)
                .padding(8).frame(minHeight: 110)
                .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Yoin.surfaceContainerHigh))
            neoDBLine
            if !card.diaryTracks.isEmpty {
                ScrollView {
                    VStack(alignment: .leading, spacing: 10) {
                        ForEach(card.diaryTracks) { d in diaryTrack(d) }
                    }
                }
                .frame(maxHeight: 200)
            }
            HStack {
                Button("Go to Album") { dismiss(); model.open(.album(card.album)) }
                Spacer()
                Button("Cancel", role: .cancel) { dismiss() }
                if failed { Text("Couldn't save").foregroundStyle(.red) }
                Button("Save") { if save() { dismiss() } else { failed = true } }.keyboardShortcut(.defaultAction)
            }
        }
        .padding(22)
        .frame(width: 480)
        .onAppear {
            title = card.titleKind == .user ? card.title : ""
            review = card.review?.text ?? ""
            openedTitle = title
            openedReview = review
            AlbumNeoDBSync.shared.sheetOpened(card.album)
        }
        .onChange(of: review) { _, r in
            AlbumNeoDBSync.shared.edited(card.album, unsavedWords: r.trimmingCharacters(in: .whitespacesAndNewlines)
                != (card.review?.text ?? ""))
        }
    }

    /// Where NeoDB stands for the review (Android MemoryDiary's footer): syncing, synced, or Couldn't sync
    /// with Retry.
    @ViewBuilder private var neoDBLine: some View {
        let state = AlbumNeoDBSync.shared.state(card.album)
        if state.isShown, state != .signedOut {
            HStack(spacing: 8) {
                Text("NeoDB").font(.gsf(13, .semibold))
                Text(state.label).font(.gsf(13)).foregroundStyle(state == .failed ? .red : Yoin.inkVariant)
                if state == .failed {
                    Button("Retry") { Task { await AlbumNeoDBSync.shared.retry(card.album) } }.buttonStyle(.link)
                }
            }
        }
    }

    /// A rated or noted song (Android MemoriesScreen.kt:203): a click plays the album from it; a note plays from
    /// its moment. The song playing is marked, and so is the note its playhead has passed last.
    private func diaryTrack(_ d: MemoryCard.DiaryTrack) -> some View {
        let p = model.player
        let playing = p.currentTrack?.id == d.track.id.rawId && p.currentTrack?.provider == d.track.id.provider
        let posMs = Int64(p.progress.positionSec * 1000)
        let current = playing ? d.notes.last { ($0.positionMs ?? .max) <= posMs }?.id : nil
        return VStack(alignment: .leading, spacing: 3) {
            Button { model.play(album: card.album, song: d.track.id.rawId) } label: {
                HStack(spacing: 8) {
                    if playing { EqualizerBars(playing: p.isPlaying, color: Yoin.violet).frame(width: 14, height: 12) }
                    Text(d.track.title).font(.gsf(14, .medium)).foregroundStyle(playing ? Yoin.violet : Yoin.ink)
                    Spacer()
                    if let r = d.rating { Text(String(format: "%.1f", r)).font(.gsf(13).monospacedDigit()) }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .help(playing ? "Playing" : "Play \(d.track.title)")
            ForEach(d.notes) { n in
                Button { model.play(album: card.album, song: d.track.id.rawId, at: n.positionMs) } label: {
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text(n.positionMs.map(NotesStore.formatPosition) ?? "")
                            .font(.gsf(12, .medium)).monospacedDigit()
                            .foregroundStyle(n.id == current ? Yoin.violet : Yoin.ink3)
                            .frame(width: 40, alignment: .leading)
                        Text(n.content).font(.gsf(13))
                            .foregroundStyle(n.id == current ? Yoin.ink : Yoin.inkVariant)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .padding(.vertical, 2).padding(.horizontal, 4)
                    .background(RoundedRectangle(cornerRadius: 6).fill(Yoin.violet.opacity(n.id == current ? 0.1 : 0)))
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .help(n.positionMs.map { "Play from \(NotesStore.formatPosition($0))" } ?? "Play \(d.track.title)")
            }
        }
        .animation(Yoin.effects, value: current)
        .animation(Yoin.effects, value: playing)
    }

    private var stats: String {
        var parts = ["\(card.playCount) \(card.playCount == 1 ? "play" : "plays")"]
        if let first = card.firstPlayedAt { parts.append("since " + HomeHistory.memoryDate(first)) }
        return parts.joined(separator: "  ")
    }

    /// iOS MemoryWrites: only the fields edited here are written (a review synced in meanwhile isn't overwritten
    /// by the untouched draft); blank review keeps the old one, blank title goes back to Yoin's. Cloud sync
    /// hears of it. False: nothing saved.
    private func save() -> Bool {
        let album = card.album
        let store = AlbumMemoryStore.shared
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        var ok = true
        var changed = false
        var neoDB = false
        let words = review.trimmingCharacters(in: .whitespacesAndNewlines)
        if review != openedReview, !words.isEmpty {
            let was = store.album(provider: album.id.provider, albumId: album.id.rawId)
            if words != was?.review {
                if store.putAlbum(.init(provider: album.id.provider, albumId: album.id.rawId, rating: was?.rating ?? 0,
                                        review: words, reviewUpdatedAt: now, updatedAt: now)) {
                    NeoDBPendingStore.shared.markReviewChanged(provider: album.id.provider, albumId: album.id.rawId)
                    neoDB = true
                } else {
                    ok = false
                }
                changed = true
            }
        }
        if title != openedTitle {
            let t = title.trimmingCharacters(in: .whitespacesAndNewlines)
            if t.isEmpty {
                ok = store.removeTitle(provider: album.id.provider, albumId: album.id.rawId) && ok
            } else {
                ok = store.putTitle(.init(provider: album.id.provider, albumId: album.id.rawId, title: t, updatedAt: now)) && ok
            }
            changed = true
        }
        if changed { CloudSync.shared.localChanged() }
        // iOS MemoryDiaryWriter: changed words go to NeoDB when signed in.
        if neoDB { Task { await AlbumNeoDBSync.shared.sheetClosed(album) } }
        return ok
    }
}
