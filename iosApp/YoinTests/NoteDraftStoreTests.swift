import XCTest
@testable import Yoin

/// The new note's draft outside Now Playing (C-F2): per account, persisted, dropped when emptied.
@MainActor
final class NoteDraftStoreTests: XCTestCase {
    private var defaults: UserDefaults!

    override func setUp() {
        defaults = UserDefaults(suiteName: "NoteDraftStoreTests")
        defaults.removePersistentDomain(forName: "NoteDraftStoreTests")
    }

    private let draft = NoteDraftStore.Draft(
        words: "the bass walks up here", anchorMs: 61_500,
        target: .init(provider: "subsonic", rawId: "s1", title: "Afternoon Soda", artist: "Sora Hayama"))

    func test_should_restore_words_moment_and_song_when_reopened() {
        NoteDraftStore(defaults: defaults).save(draft, scope: "subsonic|a")
        // A fresh store: what a relaunch (or a remounted Now Playing) reads.
        let back = NoteDraftStore(defaults: defaults).draft(scope: "subsonic|a")
        XCTAssertEqual(back, draft)
        XCTAssertEqual(back?.target?.noteTarget, NoteTarget(provider: "subsonic", rawId: "s1",
                                                            title: "Afternoon Soda", artist: "Sora Hayama"))
    }

    func test_should_keep_drafts_apart_when_accounts_differ() {
        let store = NoteDraftStore(defaults: defaults)
        store.save(draft, scope: "subsonic|a")
        XCTAssertNil(store.draft(scope: "spotify|b"))
    }

    func test_should_drop_draft_when_words_are_cleared() {
        let store = NoteDraftStore(defaults: defaults)
        store.save(draft, scope: "subsonic|a")
        store.save(.init(words: "  ", anchorMs: nil, target: nil), scope: "subsonic|a")
        XCTAssertNil(NoteDraftStore(defaults: defaults).draft(scope: "subsonic|a"))
        store.save(draft, scope: "subsonic|a")
        store.save(nil, scope: "subsonic|a")
        XCTAssertNil(NoteDraftStore(defaults: defaults).draft(scope: "subsonic|a"))
    }
}
