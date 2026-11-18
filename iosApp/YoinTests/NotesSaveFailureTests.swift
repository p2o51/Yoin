import XCTest
@testable import Yoin

/// A note that couldn't be written isn't handed back as saved (Codex, 2026-11-06).
@MainActor
final class NotesSaveFailureTests: XCTestCase {
    func test_should_returnNothingAndKeepNothing_when_theDiskRefusesTheNote() throws {
        // The notes file's folder is a file: every write fails.
        let blocker = FileManager.default.temporaryDirectory.appendingPathComponent("notes-blocker-\(UUID().uuidString)")
        try Data().write(to: blocker)
        defer { try? FileManager.default.removeItem(at: blocker) }
        let notes = NotesStore(fileURL: blocker.appendingPathComponent("notes.json"), scopes: AccountScope())
        XCTAssertNil(notes.add(provider: "applemusic", trackId: "t1", content: "Bass walks up here",
                               positionMs: 1000, title: "T", artist: "A"))
        XCTAssertTrue(notes.notes(provider: "applemusic", trackId: "t1").isEmpty)
    }

    func test_should_keepTheOldWords_when_anEditCantBeWritten() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("notes-edit-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: dir) }
        let file = dir.appendingPathComponent("notes.json")
        let notes = NotesStore(fileURL: file, scopes: AccountScope())
        let note = try XCTUnwrap(notes.add(provider: "applemusic", trackId: "t1", content: "First words",
                                           positionMs: 1000, title: "T", artist: "A"))
        // The folder becomes a file: the next write fails.
        try FileManager.default.removeItem(at: dir)
        try Data().write(to: dir)
        XCTAssertNil(notes.update(id: note.id, content: "New words"))
        XCTAssertNil(notes.realign(id: note.id, positionMs: 5000))
        XCTAssertNil(notes.delete(id: note.id))
        let kept = try XCTUnwrap(notes.note(id: note.id))
        XCTAssertEqual(kept.content, "First words")
        XCTAssertEqual(kept.positionMs, 1000)
    }
}
