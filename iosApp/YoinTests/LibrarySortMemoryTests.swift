import XCTest
@testable import Yoin

/// Library's sort choices are kept per account across launches (LibraryMemory, Android LibrarySortStore).
@MainActor
final class LibrarySortMemoryTests: XCTestCase {
    func test_should_keepEachAccountsSorts_when_switchingAndRelaunching() {
        let defaults = UserDefaults(suiteName: "library-sort-\(UUID().uuidString)")!
        let memory = LibraryMemory()
        memory.defaults = defaults
        memory.scope = "subsonic|a"
        memory.sorts[.albums] = .alphabetical
        memory.scope = "spotify|b"
        XCTAssertNil(memory.sorts[.albums], "another account starts from its own")
        memory.sorts[.albums] = .creator
        memory.scope = "subsonic|a"
        XCTAssertEqual(memory.sorts[.albums], .alphabetical)
        // A relaunch reads them back.
        XCTAssertEqual(LibraryMemory.load(scope: "spotify|b", defaults: defaults)[.albums], .creator)
        XCTAssertEqual(memory.sort(for: .albums, offered: [.recents, .alphabetical]), .alphabetical)
        XCTAssertEqual(memory.sort(for: .albums, offered: [.recents]), .recents, "an order no longer offered falls back")
    }
}
