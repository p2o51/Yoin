import XCTest
@testable import Yoin

/// The search rows' + / ✓ (B-U13, Android addSongToLibrary): membership, working and the answer under the row.
@MainActor
final class SongLibraryAdderTests: XCTestCase {
    @MainActor private final class Stub: LibraryAddBackend {
        var inLibrary: Set<String> = []
        /// The write succeeds but the library doesn't show the song yet.
        var confirms = true
        var fails = false
        private(set) var added: [String] = []
        private(set) var checks: [String] = []

        func isInLibrary(_ rawId: String) async -> Bool? { checks.append(rawId); return inLibrary.contains(rawId) }
        func addToLibrary(_ rawId: String) async throws {
            added.append(rawId)
            if fails { throw NSError(domain: "x", code: 1) }
            if confirms { inLibrary.insert(rawId) }
        }
    }

    func test_should_read_membership_when_results_are_listed() async {
        let stub = Stub(); stub.inLibrary = ["1"]
        let adder = SongLibraryAdder(); adder.use(stub)
        await adder.refresh(["1", "2", "3", "4"])
        XCTAssertEqual(adder.state("1"), .added)
        XCTAssertEqual(adder.state("2"), .notAdded)
        XCTAssertEqual(adder.state("9"), .unknown)
        await adder.refresh(["1", "2"])
        XCTAssertEqual(stub.checks.count, 4, "known songs aren't read again")
    }

    func test_should_add_and_say_so_when_the_library_confirms() async {
        let stub = Stub()
        let adder = SongLibraryAdder(); adder.use(stub)
        await adder.add("7")
        XCTAssertEqual(stub.added, ["7"])
        XCTAssertEqual(adder.state("7"), .added)
        XCTAssertEqual(adder.feedback["7"], .init(message: loc("Added to library"), isError: false))
        XCTAssertTrue(adder.working.isEmpty)
        await adder.add("7")
        XCTAssertEqual(stub.added, ["7"], "an added song is not posted again")
    }

    func test_should_stay_pending_and_only_recheck_when_the_library_does_not_confirm_yet() async {
        let stub = Stub(); stub.confirms = false
        let adder = SongLibraryAdder(); adder.use(stub)
        await adder.add("7")
        XCTAssertEqual(adder.state("7"), .pending)
        XCTAssertEqual(adder.feedback["7"]?.message, loc("Waiting for Apple Music"))
        stub.inLibrary.insert("7")
        await adder.add("7")
        XCTAssertEqual(stub.added, ["7"], "the second tap rechecks without posting")
        XCTAssertEqual(adder.state("7"), .added)
    }

    func test_should_show_an_error_and_allow_retry_when_the_write_fails() async {
        let stub = Stub(); stub.fails = true
        let adder = SongLibraryAdder(); adder.use(stub)
        await adder.add("7")
        XCTAssertEqual(adder.feedback["7"], .init(message: loc("Couldn't add this song to the library."), isError: true))
        XCTAssertNotEqual(adder.state("7"), .added)
        XCTAssertTrue(adder.working.isEmpty)
        stub.fails = false
        await adder.add("7")
        XCTAssertEqual(adder.state("7"), .added)
        XCTAssertEqual(adder.feedback["7"]?.isError, false)
    }

    func test_should_forget_everything_when_the_service_changes() async {
        let adder = SongLibraryAdder(); adder.use(Stub())
        await adder.add("7")
        adder.use(nil)
        XCTAssertEqual(adder.state("7"), .unknown)
        XCTAssertNil(adder.feedback["7"])
        XCTAssertFalse(adder.canAdd)
    }
}
