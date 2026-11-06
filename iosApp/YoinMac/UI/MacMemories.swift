import SwiftUI

/// The Mac's stand-in for iOS ScoreEmblem's kind (the emblem view is iOS's; the Mac shows the score as text).
enum ScoreEmblem {
    enum Kind {
        case album, average, unrated

        /// The Bun rule: manual album rating > average of the rated tracks > N/A.
        init(rating: Double?, average: Double?) {
            self = rating != nil ? .album : average != nil ? .average : .unrated
        }
    }
}

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
            if !card.diaryTracks.isEmpty {
                ScrollView {
                    VStack(alignment: .leading, spacing: 10) {
                        ForEach(card.diaryTracks) { d in
                            VStack(alignment: .leading, spacing: 3) {
                                HStack {
                                    Text(d.track.title).font(.gsf(14, .medium))
                                    Spacer()
                                    if let r = d.rating { Text(String(format: "%.1f", r)).font(.gsf(13).monospacedDigit()) }
                                }
                                ForEach(d.notes) { n in
                                    Text(n.content).font(.gsf(13)).foregroundStyle(Yoin.inkVariant)
                                        .fixedSize(horizontal: false, vertical: true)
                                }
                            }
                        }
                    }
                }
                .frame(maxHeight: 200)
            }
            HStack {
                Button("Go to Album") { dismiss(); model.open(.album(card.album)) }
                Spacer()
                Button("Cancel", role: .cancel) { dismiss() }
                Button("Save") { save(); dismiss() }.keyboardShortcut(.defaultAction)
            }
        }
        .padding(22)
        .frame(width: 480)
        .onAppear {
            title = card.titleKind == .user ? card.title : ""
            review = card.review?.text ?? ""
        }
    }

    private var stats: String {
        var parts = ["\(card.playCount) \(card.playCount == 1 ? "play" : "plays")"]
        if let first = card.firstPlayedAt { parts.append("since " + HomeHistory.memoryDate(first)) }
        return parts.joined(separator: "  ")
    }

    /// iOS MemoryWrites: the review (blank keeps the old one) and your title (blank: back to Yoin's).
    private func save() {
        let album = card.album
        let store = AlbumMemoryStore.shared
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let words = review.trimmingCharacters(in: .whitespacesAndNewlines)
        let was = store.album(provider: album.id.provider, albumId: album.id.rawId)
        if !words.isEmpty, words != was?.review {
            store.putAlbum(.init(provider: album.id.provider, albumId: album.id.rawId, rating: was?.rating ?? 0,
                                 review: words, reviewUpdatedAt: now, updatedAt: now))
        }
        let t = title.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty {
            if card.titleKind == .user { store.removeTitle(provider: album.id.provider, albumId: album.id.rawId) }
        } else if t != card.title || card.titleKind != .user {
            store.putTitle(.init(provider: album.id.provider, albumId: album.id.rawId, title: t, updatedAt: now))
        }
    }
}
