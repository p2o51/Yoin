import XCTest
@testable import Yoin

/// The Spotoolfy recap kept for Settings › Yoin, and a shared export routed to the welcome guide.
@MainActor
final class SpotoolfyRecapStoreTests: XCTestCase {
    func test_should_keepTheRecapPerAccount_when_anImportIsWritten() {
        let defaults = UserDefaults(suiteName: "spotoolfy-recap-\(UUID().uuidString)")!
        let recap = SpotoolfyRecap(noteCount: 3, trackCount: 2, artistCount: 1, ratedTrackCount: 1, firstRecordAt: 1,
                                   lastRecordAt: 2, coverUrls: [], keywords: [RecapKeyword(text: "rain", count: 2, notes: [])],
                                   emoji: [], lyricSnippets: [], fallbackNotes: [])
        SpotoolfyRecapStore.save(recap, scope: "spotify|a", defaults: defaults)
        XCTAssertEqual(SpotoolfyRecapStore.load(scope: "spotify|a", defaults: defaults)?.noteCount, 3)
        XCTAssertNil(SpotoolfyRecapStore.load(scope: "spotify|b", defaults: defaults))
        SpotoolfyRecapStore.remove(scope: "spotify|a", defaults: defaults)
        XCTAssertNil(SpotoolfyRecapStore.load(scope: "spotify|a", defaults: defaults))
    }

    func test_should_holdASharedFile_onlyWhileTheGuideIsOpen() {
        let inbox = SpotoolfyInbox()
        let file = URL(fileURLWithPath: "/tmp/export.json")
        XCTAssertFalse(inbox.receive(file, landingOpen: false))
        XCTAssertNil(inbox.pending)
        XCTAssertFalse(inbox.receive(URL(string: "yoin://widget")!, landingOpen: true))
        XCTAssertTrue(inbox.receive(file, landingOpen: true))
        XCTAssertEqual(inbox.take(), file)
        XCTAssertNil(inbox.pending)
    }
}
