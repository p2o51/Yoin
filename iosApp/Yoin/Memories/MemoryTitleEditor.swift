import SwiftUI

/*
 * The user's own title for a memory (Android showcase/MemoryTitleEditor;
 * owner 2026-10-05: the AI title leaves room for the user to change it). Tap
 * the title, on the card, in the diary or on the spread's left page, and it
 * becomes the field in the title's own typography: no box, no outline. Under
 * it a quiet row drops in: [Restore AI title] Cancel Save, Save the album's
 * pill. An empty save is a restore. Saved titles go to AlbumMemoryStore (and
 * sync), the same rows the album scrapbook renames.
 *
 * Editing is an in-page mode, not a place: while it lasts no drag pages the
 * deck or moves q / p (the router holds them), and back cancels the edit first.
 */

/// The three places a title can be edited; each edits on its own.
enum MemoryTitleSurface: String { case card, diary, spread }

/// The deck's one title editor: which title is open, its draft and seed.
@MainActor
@Observable
final class MemoryTitleEditor {
    /// "surface:albumId" of the title being edited.
    private(set) var editingKey: String?
    var draft = ""
    private(set) var seed = ""

    var isEditing: Bool { editingKey != nil }

    func isEditing(_ surface: MemoryTitleSurface, _ card: MemoryCard) -> Bool {
        editingKey == Self.key(surface, card)
    }

    func begin(_ surface: MemoryTitleSurface, _ card: MemoryCard) {
        seed = MemoryTitles.seed(card)
        draft = seed
        editingKey = Self.key(surface, card)
    }

    func end() { editingKey = nil }

    static func key(_ surface: MemoryTitleSurface, _ card: MemoryCard) -> String { "\(surface.rawValue):\(card.id)" }
}

/// The pure rules (Android MemoryTitleEditor's, MemoryTitleEditorTest).
enum MemoryTitles {
    /// A title, not a note: the card's slot has to hold it (AlbumMemoryTitleStore's cap).
    static let maxLength = 80

    enum Outcome: Equatable {
        case unchanged
        /// Back to Yoin's own title (the user's row is deleted).
        case restore
        case save(String)
    }

    /// What the field opens with: the card's title, or nothing when the album name stands in.
    static func seed(_ card: MemoryCard) -> String { card.titleKind == .album ? "" : card.title }

    /// The empty field's hint: what an empty save shows (Yoin's title, else the album name).
    static func placeholder(_ card: MemoryCard) -> String { card.yoinTitle ?? card.album.title }

    /// Restore shows only over a user title with Yoin's own under it.
    static func canRestore(_ card: MemoryCard) -> Bool { card.titleKind == .user && card.yoinTitle != nil }

    static let restoreLabel = "Restore AI title"

    /// One line: breaks become spaces, at most [maxLength].
    static func sanitize(_ text: String) -> String {
        let flat = text.replacingOccurrences(of: "\r", with: " ").replacingOccurrences(of: "\n", with: " ")
        return flat.count > maxLength ? String(flat.prefix(maxLength)) : flat
    }

    /// Save on [draft]: the seed's words change nothing; empty restores Yoin's
    /// title when the user's is in use (else nothing); anything else is the new title.
    static func outcome(draft: String, seed: String, userTitleInUse: Bool) -> Outcome {
        let words = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        if words == seed.trimmingCharacters(in: .whitespacesAndNewlines) { return .unchanged }
        if words.isEmpty { return userTitleInUse ? .restore : .unchanged }
        return .save(words)
    }

    /// The card's title after [outcome] (shown at once, before the deck refreshes).
    static func applied(_ outcome: Outcome, to card: MemoryCard) -> MemoryCard {
        var c = card
        switch outcome {
        case .unchanged: break
        case .restore:
            if let yoin = card.yoinTitle {
                c.title = yoin
                c.titleKind = .ai
            } else {
                c.title = card.album.title
                c.titleKind = .album
            }
        case .save(let t):
            c.title = t
            c.titleKind = .user
        }
        return c
    }
}

/// A memory's title on [surface]: [display] at rest, the field while it is
/// being edited (in the same typography), crossfading between them on the
/// effects spring. [enabled]: this page is current and the surface takes
/// input; an edit whose surface stops taking input ends unsaved.
struct MemoryTitleSlot<Display: View>: View {
    var card: MemoryCard
    var surface: MemoryTitleSurface
    var editor: MemoryTitleEditor
    var scheme: CoverScheme
    var tones: MemoryTones
    var font: Font
    var lineSpacing: CGFloat
    var centred: Bool
    var enabled: Bool
    var onCommit: (MemoryTitles.Outcome) -> Void
    @ViewBuilder var display: () -> Display

    @FocusState private var focused: Bool

    private var open: Bool { editor.isEditing(surface, card) }

    var body: some View {
        ZStack(alignment: centred ? .top : .topLeading) {
            if open {
                TextField("", text: Binding(get: { editor.draft }, set: { new in
                    // Return saves; pasted breaks become spaces.
                    if new.hasSuffix("\n"), !new.dropLast().contains("\n") {
                        editor.draft = MemoryTitles.sanitize(String(new.dropLast()))
                        commit()
                    } else {
                        editor.draft = MemoryTitles.sanitize(new)
                    }
                }), prompt: Text(MemoryTitles.placeholder(card)).foregroundStyle(scheme.onSurfaceVariant.opacity(DiaryWriterTokens.placeholderAlpha)),
                axis: .vertical)
                .font(font)
                .lineSpacing(lineSpacing)
                .multilineTextAlignment(centred ? .center : .leading)
                .foregroundStyle(scheme.onSurface)
                .tint(tones.ink)
                .focused($focused)
                .submitLabel(.done)
                .textInputAutocapitalization(.sentences)
                .accessibilityLabel("Title")
                .onAppear { focused = true }
                .transition(.opacity)
            } else {
                display()
                    .contentShape(.rect)
                    .onTapGesture {
                        guard enabled, card.album.id.rawId.isEmpty == false else { return }
                        editor.begin(surface, card)
                    }
                    .accessibilityAddTraits(enabled ? .isButton : [])
                    .accessibilityHint(enabled ? String(localized: "Edit title") : "")
                    .transition(.opacity)
            }
        }
        .animation(Yoin.effects, value: open)
        .onChange(of: enabled) { _, on in if !on && open { editor.end() } }
        .onChange(of: open) { _, now in if !now { focused = false } }
    }

    private func commit() {
        let outcome = MemoryTitles.outcome(draft: editor.draft, seed: editor.seed, userTitleInUse: card.titleKind == .user)
        MemoriesHaptics.confirm()
        onCommit(outcome)
        editor.end()
    }
}

/// The quiet row under an open title: [Restore AI title] Cancel Save.
struct MemoryTitleEditRow: View {
    var card: MemoryCard
    var surface: MemoryTitleSurface
    var editor: MemoryTitleEditor
    var scheme: CoverScheme
    var tones: MemoryTones
    var centred: Bool
    var onCommit: (MemoryTitles.Outcome) -> Void

    var body: some View {
        let open = editor.isEditing(surface, card)
        VStack {
            if open {
                HStack(spacing: 0) {
                    if !centred { Spacer(minLength: 0) }
                    if MemoryTitles.canRestore(card) {
                        quiet(MemoryTitles.restoreLabel) {
                            MemoriesHaptics.confirm()
                            onCommit(.restore)
                            editor.end()
                        }
                    }
                    quiet(String(localized: "Cancel")) { editor.end() }
                        .accessibilityHint("Discards the title")
                    Button {
                        let outcome = MemoryTitles.outcome(draft: editor.draft, seed: editor.seed,
                                                           userTitleInUse: card.titleKind == .user)
                        MemoriesHaptics.confirm()
                        onCommit(outcome)
                        editor.end()
                    } label: {
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
                    .accessibilityLabel("Save title")
                }
                .padding(.top, 6)
                .transition(.opacity.combined(with: .offset(y: -8)))
            }
        }
        .frame(maxWidth: .infinity)
        .animation(Yoin.spatial, value: open)
    }

    private func quiet(_ label: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(.gsf(DiaryType.ui, .semibold))
                .foregroundStyle(scheme.onSurfaceVariant)
                .padding(.horizontal, 14)
                .frame(height: DiaryWriterTokens.rowHit)
                .contentShape(.rect)
        }
        .buttonStyle(.plain)
    }
}
