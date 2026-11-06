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
}
