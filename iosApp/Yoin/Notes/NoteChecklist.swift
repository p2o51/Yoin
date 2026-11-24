import Foundation
import YoinShared

/// A tap on a to-do's box in a drawn note (Android NoteMarkupText onToggleChecklist): the line ticked or
/// unticked by :shared's NoteMarkup (every other character as written) and saved at once. Song notes and album
/// notes; reviews aren't ticked here (as on Android).
@MainActor
enum NoteChecklist {
    /// [text] with line [line]'s to-do flipped; unchanged when that line isn't one.
    static func toggled(_ text: String, line: Int) -> String {
        YoinNoteEditor.shared.toggleChecklist(text: text, lineIndex: Int32(line))
    }

    /// Ticks song note [id]'s line [line]; the saved note, or nil (no such note, not a to-do, not written).
    @discardableResult
    static func toggleSongNote(_ id: String, line: Int, store: NotesStore = .shared) -> SongNote? {
        guard let note = store.note(id: id) else { return nil }
        let next = toggled(note.content, line: line)
        guard next != note.content else { return nil }
        return store.update(id: id, content: next)
    }

    /// Ticks album note [id]'s line [line]; the note as it was before, or nil (nothing written).
    @discardableResult
    static func toggleAlbumNote(_ id: String, line: Int, store: AlbumNotesStore = .shared) -> AlbumNote? {
        guard let note = store.note(id: id) else { return nil }
        let next = toggled(note.content, line: line)
        guard next != note.content else { return nil }
        return store.edit(id: id, content: next)
    }
}
