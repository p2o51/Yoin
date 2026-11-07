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
        // An edit that didn't reach the disk isn't saved: nil, and the note keeps its old words (Codex 11-07).
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("notes-edit-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: dir) }
        let url = dir.appendingPathComponent("notes.json")
        let notes = NotesStore(fileURL: url, scopes: AccountScope())
        let note = try XCTUnwrap(notes.add(provider: "subsonic", trackId: "t1", content: "Old words",
                                           positionMs: 1000, title: "T", artist: "A"))
        // The file becomes a folder: every write fails from here.
        try FileManager.default.removeItem(at: url)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        XCTAssertNil(notes.update(id: note.id, content: "New words"))
        XCTAssertNil(notes.realign(id: note.id, positionMs: 5000))
        let kept = try XCTUnwrap(notes.notes(provider: "subsonic", trackId: "t1").first)
        XCTAssertEqual(kept.content, "Old words")
        XCTAssertEqual(kept.positionMs, 1000)
    }
}
