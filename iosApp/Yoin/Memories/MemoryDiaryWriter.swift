import SwiftUI

/*
 * Your entry in the diary (Android showcase/MemoryDiaryWriter): the review,
 * or without one today's blank page. The page speaks the journal language:
 * a rail, one placeholder line, no box, no outline. A tap starts writing (the
 * rail comes up to full ink); Cancel and Save drop in under it, Save the
 * album-coloured pill. Saving turns the same block into the review in place:
 * the words stay where they are, the rail fades, the body slides 14 → 0 onto
 * the entry's line on the spatial spring, and Today crossfades to Your review.
 *
 * Drafts: what you type is kept per album while Memories is open (closing
 * the diary or swiping away keeps it; Cancel drops it), and a save that did
 * not land comes back as the draft.
 */

/// The blank page's geometry. Layout constants.
enum DiaryWriterTokens {
    static let bodyInset: CGFloat = 14
    static let railWidth: CGFloat = 2
    static let railInset: CGFloat = 4
    static let railRest: CGFloat = 0.32
    static let placeholderAlpha: CGFloat = 0.7
    static let minHeight: CGFloat = 64
    static let rowHit: CGFloat = 48
    static let pillHeight: CGFloat = 36
    /// A review this short (characters) is set large.
    static let shortReviewMax = 16
}

/// The diary's typography (Android MemoriesTypeScale, phone tier).
enum DiaryType {
    static let review: CGFloat = 17
    static let reviewLine: CGFloat = 1.55
    static let reviewShort: CGFloat = 26
    static let note: CGFloat = 15
    static let noteLine: CGFloat = 1.5
    static let ui: CGFloat = 14

    static func lineSpacing(_ size: CGFloat, _ line: CGFloat) -> CGFloat {
        YoinType.lineSpacing(size: size, lineHeight: size * line)
    }
}

/// Drafts by album, for this Memories open.
@MainActor
@Observable
final class MemoryDiaryDrafts {
    var texts: [String: String] = [:]
}

/// Where a memory's words go: the stores when signed in (and NeoDB, on its
/// own, when that is signed in), an in-memory overlay for the mock deck.
@MainActor
enum MemoryWrites {
    private static var now: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    /// Android setAlbumReview + the R3 automatic push. Returns false when it did not save.
    @discardableResult
    static func saveReview(_ card: MemoryCard, text: String) -> Bool {
        let words = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !words.isEmpty else { return false }
        let album = card.album
        if album.id.provider == "mock" {
            MemoryDeck.MockWrites.shared.reviews[album.id.rawId] = (words, now)
            return true
        }
        let store = AlbumMemoryStore.shared
        let was = store.album(provider: album.id.provider, albumId: album.id.rawId)
        guard words != was?.review else { return true }
        let saved = store.putAlbum(.init(provider: album.id.provider, albumId: album.id.rawId, rating: was?.rating ?? 0,
                                         review: words, reviewUpdatedAt: now, updatedAt: now))
        guard saved else { return false }
        NeoDBPendingStore.shared.markReviewChanged(provider: album.id.provider, albumId: album.id.rawId)
        CloudSync.shared.localChanged()
        Task { await AlbumNeoDBSync.shared.sheetClosed(album) }
        return true
    }

    /// The user's title (nil = back to Yoin's own: the row is deleted).
    static func saveTitle(_ card: MemoryCard, _ title: String?) {
        let album = card.album
        if album.id.provider == "mock" {
            MemoryDeck.MockWrites.shared.titles[album.id.rawId] = .some(title)
            return
        }
        let store = AlbumMemoryStore.shared
        if let title {
            store.putTitle(.init(provider: album.id.provider, albumId: album.id.rawId, title: title, updatedAt: now))
        } else {
            store.removeTitle(provider: album.id.provider, albumId: album.id.rawId)
        }
        CloudSync.shared.localChanged()
    }
}

/// Your entry: the review, or today's blank page. [enabled]: the diary is open
/// and this page is current (no focus behind the card).
struct MemoryDiaryEntry: View {
    var card: MemoryCard
    var tones: MemoryTones
    var scheme: CoverScheme
    var drafts: MemoryDiaryDrafts
    var enabled: Bool
    /// Saves; false = it did not land (the draft stays).
    var onSave: (String) -> Bool

    @State private var writing = false
    @FocusState private var focused: Bool
    /// A review that appears here (a save) keeps the size it was typed at.
    @State private var bornBlank: Bool?

    private var blank: Bool { card.review == nil }
    private var draft: Binding<String> {
        Binding(get: { drafts.texts[card.id] ?? "" }, set: { drafts.texts[card.id] = $0 })
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
            Group {
                if let review = card.review {
                    reviewBody(review.text, large: bornBlank == false && review.text.count <= DiaryWriterTokens.shortReviewMax)
                } else {
                    field
                }
            }
            .padding(.leading, blank ? DiaryWriterTokens.bodyInset : 0)
            .frame(maxWidth: .infinity, alignment: .leading)
            // The rail runs the body's own height.
            .background(alignment: .leading) {
                Capsule()
                    .fill(tones.ink.opacity(blank ? (writing && focused ? 1 : DiaryWriterTokens.railRest) : 0))
                    .frame(width: DiaryWriterTokens.railWidth)
                    .padding(.vertical, DiaryWriterTokens.railInset)
                    .animation(Yoin.effects, value: writing && focused)
                    .animation(Yoin.effects, value: blank)
            }
            .animation(Yoin.spatial, value: blank)
            if writing && blank {
                actions
                    .transition(.opacity.combined(with: .offset(y: -8)))
            }
        }
        .frame(maxWidth: .infinity, minHeight: DiaryWriterTokens.minHeight, alignment: .topLeading)
        .contentShape(.rect)
        .onTapGesture { open() }
        .animation(Yoin.spatial, value: writing && blank)
        .onAppear {
            if bornBlank == nil { bornBlank = blank }
            // A draft kept from before reopens writing (not focused: the keyboard waits for a tap).
            if blank, !(drafts.texts[card.id] ?? "").isEmpty { writing = true }
        }
        .onChange(of: enabled) { _, on in if !on { focused = false } }
        .accessibilityElement(children: .contain)
    }

    private func open() {
        guard enabled, blank else { return }
        writing = true
        focused = true
    }

    private var header: some View {
        HStack(spacing: 8) {
            Text(MemoryDiaryFormat.entryDate(card.review?.at))
                .font(.gsf(13, .semibold))
                .foregroundStyle(tones.ink)
            Text(blank ? String(localized: "Today") : String(localized: "Your review"))
                .font(.gsf(13, .medium))
                .foregroundStyle(scheme.onSurfaceVariant)
                .contentTransition(.opacity)
                .animation(Yoin.effects, value: blank)
        }
        .lineLimit(1)
        .padding(.bottom, 10)
    }

    private func reviewBody(_ text: String, large: Bool) -> some View {
        let size = large ? DiaryType.reviewShort : DiaryType.review
        let paragraphs = text.split(separator: "\n").map(String.init).filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
        return VStack(alignment: .leading, spacing: DiaryType.review * 0.85) {
            ForEach(Array(paragraphs.enumerated()), id: \.offset) { _, p in
                Text(p)
                    .font(.gsf(size, large ? .medium : .regular))
                    .lineSpacing(DiaryType.lineSpacing(size, large ? 1.25 : DiaryType.reviewLine))
                    .foregroundStyle(scheme.onSurface)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .accessibilityElement(children: .combine)
    }

    private var field: some View {
        TextField("", text: draft, prompt: Text("Write").foregroundStyle(scheme.onSurfaceVariant.opacity(DiaryWriterTokens.placeholderAlpha)),
                  axis: .vertical)
            .font(.gsf(DiaryType.review))
            .lineSpacing(DiaryType.lineSpacing(DiaryType.review, DiaryType.reviewLine))
            .foregroundStyle(scheme.onSurface)
            .tint(tones.ink)
            .focused($focused)
            .disabled(!enabled)
            .textInputAutocapitalization(.sentences)
            .onChange(of: focused) { _, now in if now { writing = true } }
            .accessibilityLabel("Review")
    }

    private var actions: some View {
        HStack(spacing: 0) {
            Spacer(minLength: 0)
            Button {
                drafts.texts[card.id] = nil
                writing = false
                focused = false
            } label: {
                Text("Cancel")
                    .font(.gsf(DiaryType.ui, .semibold))
                    .foregroundStyle(scheme.onSurfaceVariant)
                    .padding(.horizontal, 16)
                    .frame(height: DiaryWriterTokens.rowHit)
                    .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .accessibilityHint("Discards the draft")
            Button(action: save) {
                Text("Save")
                    .font(.gsf(DiaryType.ui, .semibold))
                    .foregroundStyle(tones.onButton)
                    .padding(.horizontal, 16)
                    .frame(height: DiaryWriterTokens.pillHeight)
                    .background(tones.button, in: .capsule)
                    .frame(height: DiaryWriterTokens.rowHit)
                    .contentShape(.rect)
            }
            .buttonStyle(PressScale())
            .accessibilityLabel("Save review")
        }
        .padding(.top, 6)
    }

    private func save() {
        let words = (drafts.texts[card.id] ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !words.isEmpty else {
            focused = true
            return
        }
        MemoriesHaptics.confirm()
        guard onSave(words) else { return }
        drafts.texts[card.id] = nil
        focused = false
        writing = false
    }
}

/// The diary's dates.
enum MemoryDiaryFormat {
    /// "Jul 26, 2026" (a review's day, else today).
    static func entryDate(_ ms: Int64?, now: Date = Date()) -> String {
        let d = ms.flatMap { $0 > 0 ? Date(timeIntervalSince1970: Double($0) / 1000) : nil } ?? now
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "MMM d, yyyy"
        return f.string(from: d)
    }

    /// "Jul 26" this year, "Nov 8, 2025" in another (Android MemoryDates.day).
    static func day(_ ms: Int64, now: Date = Date(), calendar: Calendar = .current) -> String {
        let d = Date(timeIntervalSince1970: Double(ms) / 1000)
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = calendar.component(.year, from: d) == calendar.component(.year, from: now) ? "MMM d" : "MMM d, yyyy"
        return f.string(from: d)
    }

    /// A note's moment, "2:05".
    static func stamp(_ ms: Int64) -> String {
        let s = Int(ms / 1000)
        return "\(s / 60):" + String(format: "%02d", s % 60)
    }
}
