import SwiftUI

/*
 * The scrapbook's album notes as stickies (Android ui/detail/AlbumScrapbookNotes.kt, notes revamp phase B;
 * iOS notes phase 1b). Every album note is a sticky under the cover and review, oldest first; a dashed sticky
 * writes the next one in place (so does a tap on bare paper). A sticky opens in place into a plain text
 * editor (the @ editor is phase 1c): the stored text is edited as it is, and words that don't change are
 * never rewritten, so an Android note's mentions and markup come back byte for byte. Clearing a sticky and
 * saving deletes it, with Undo.
 */

/// One album note on the page (Android ScrapSticky): its stored text and its lean.
struct ScrapSticky: Identifiable, Equatable {
    var noteId: String
    var text: String
    var tilt: Double
    var id: String { noteId }
}

/// Pure rules, for the tests (Android buildAlbumScrapbook's stickies, `opposite`, stickySave).
enum ScrapStickyRules {
    /// What the dashed sticky (or bare paper) opens: a new note (Android StickyEditing.NEW).
    static let new = "\u{0}new"

    /// Android `opposite`: [angle], turned the other way when it leans the way [previous] does.
    static func opposite(_ previous: Double?, _ angle: Double) -> Double {
        guard let previous, sign(previous) == sign(angle) else { return angle }
        return -angle
    }

    private static func sign(_ x: Double) -> Double { x > 0 ? 1 : (x < 0 ? -1 : 0) }

    /// Every album note with words as a sticky, in the order given (oldest first): the first leans against
    /// the review, each next one against the one before. [tilt] is the page's hash tilt for a key.
    static func stickies(_ notes: [AlbumNote], reviewTilt: Double, tilt: (String) -> Double) -> [ScrapSticky] {
        var previous: Double?
        return notes.compactMap { note -> ScrapSticky? in
            let words = note.content.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !words.isEmpty else { return nil }
            let lean = previous == nil ? opposite(reviewTilt, tilt("albumnote"))
                : opposite(previous, tilt("albumnote/\(note.id)"))
            previous = lean
            return ScrapSticky(noteId: note.id, text: words, tilt: lean)
        }
    }

    enum Edit: Equatable {
        /// A new note with these words (trimmed).
        case add(String)
        /// New text for a note, as typed (the store trims it, and keeps the stored text when the words match).
        case text(noteId: String, String)
        /// A sticky cleared and saved: the note goes, with Undo.
        case delete(noteId: String)
    }

    /// Android stickySave: saving the open sticky. A blank new sticky writes nothing (nil).
    static func save(open: String, text: String) -> Edit? {
        let words = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if open == new { return words.isEmpty ? nil : .add(words) }
        return words.isEmpty ? .delete(noteId: open) : .text(noteId: open, text)
    }
}

extension CoverScheme {
    /// The sticky's folded corner (Android stickyFold): its paper with a third of the secondary ink.
    var stickyFold: Color { secondaryContainer.mix(with: secondary, by: 0.32, in: .perceptual) }
}

/// One album note (Android Sticky): secondaryContainer paper with a folded corner, its words drawn from the
/// stored text (NoteMarkupText; SwiftUI never parses it). The caller lays the tilt on and takes the tap.
struct ScrapStickyCard: View {
    var text: String
    var scheme: CoverScheme
    /// A tap on a to-do's box (an editable sticky); nil draws the boxes only.
    var onToggleChecklist: ((Int) -> Void)? = nil

    var body: some View {
        NoteMarkupText(text: text, size: 14.5, mentionColor: scheme.onSecondaryContainer, glyphColor: scheme.primary,
                       onToggleChecklist: onToggleChecklist)
            // userTextStyle at 14.5: line 1.5 em.
            .lineSpacing(YoinType.lineSpacing(size: 14.5, lineHeight: 14.5 * 1.5))
            .foregroundStyle(scheme.onSecondaryContainer)
            .multilineTextAlignment(.leading)
            .padding(.leading, 18).padding(.trailing, 18).padding(.top, 14).padding(.bottom, 18)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background { StickyNote(fill: scheme.secondaryContainer, flap: scheme.stickyFold) }
            .contentShape(StickyOutline())
    }
}

/// "Write an album note" (Android DashedSticky): a dashed sticky outline with the note glyph.
struct DashedSticky: View {
    var scheme: CoverScheme
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            YoinIcon("note", size: 22)
                .foregroundStyle(scheme.onSurfaceVariant)
                .frame(width: 64, height: 56)
                .overlay { StickyOutline().stroke(scheme.outline, style: StrokeStyle(lineWidth: 1.5, dash: [5, 5])) }
                .contentShape(StickyOutline())
        }
        .buttonStyle(PressScale())
        .pointerHighlight()
        .accessibilityLabel(Text("Write an album note"))
    }
}

/// A sticky being written (Android StickyEditor): a plain field on the sticky's paper, then Cancel and Save.
/// The field edits the stored text as it is; the formatting row and @ mentions are phase 1c.
struct ScrapStickyEditor: View {
    @Binding var text: String
    var scheme: CoverScheme
    var autoFocus = true
    var onSave: () -> Void
    var onCancel: () -> Void

    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            TextField(text: $text,
                      prompt: Text("Write about the whole album").foregroundStyle(scheme.onSecondaryContainer.opacity(0.6)),
                      axis: .vertical) { Text("Write about the whole album") }
                .lineLimit(3...10)
                .font(.gsf(14.5))
                .lineSpacing(YoinType.lineSpacing(size: 14.5, lineHeight: 14.5 * 1.5))
                .foregroundStyle(scheme.onSecondaryContainer)
                .tint(scheme.primary)
                .textInputAutocapitalization(.sentences)
                .focused($focused)
            HStack(spacing: 8) {
                Spacer(minLength: 0)
                Button(action: onCancel) {
                    Text("Cancel").yoinRole(Tokens.type.labelLarge).lineLimit(1)
                        .foregroundStyle(scheme.primary)
                        .padding(.horizontal, 12)
                        .frame(minHeight: 40)
                        .contentShape(.capsule)
                }
                .buttonStyle(PressScale())
                Button(action: onSave) {
                    // Android FilledTonalButton; the tone is the fold's, so it reads on the sticky's own paper.
                    Text("Save").yoinRole(Tokens.type.labelLarge).lineLimit(1)
                        .foregroundStyle(scheme.onSecondaryContainer)
                        .padding(.horizontal, 16)
                        .frame(minHeight: 40)
                        .background(scheme.stickyFold, in: .capsule)
                }
                .buttonStyle(PressScale())
            }
        }
        .padding(.leading, 14).padding(.trailing, 14).padding(.top, 12).padding(.bottom, 14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background { StickyNote(fill: scheme.secondaryContainer, flap: scheme.stickyFold) }
        .onAppear { if autoFocus { focused = true } }
    }
}
