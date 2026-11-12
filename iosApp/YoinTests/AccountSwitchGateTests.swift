import XCTest
@testable import Yoin

/// The one-off "this switches accounts" notice Home's other-accounts shelf and All Memories share (I03,
/// Android MemorySwitchGate + OtherProfilesNoticeStore): asked once, then straight through.
@MainActor
final class AccountSwitchGateTests: XCTestCase {
    private var suite = ""
    private var defaults: UserDefaults!

    override func setUp() {
        super.setUp()
        suite = "AccountSwitchGateTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suite)
        super.tearDown()
    }

    private func gate() -> AccountSwitchGate { AccountSwitchGate(store: AccountSwitchNoticeStore(defaults: defaults)) }
    private var seen: Bool { AccountSwitchNoticeStore(defaults: defaults).seen }

    func test_should_hold_the_switch_when_the_notice_was_never_seen() {
        let g = gate()
        var switched = 0
        g.request(from: "card", account: "Home server", subject: "Low Tide", opensAlbum: true) { switched += 1 }

        XCTAssertEqual(switched, 0)
        XCTAssertEqual(g.pending?.anchor, "card", "the notice comes out of the card that asked")
        XCTAssertEqual(g.pending?.account, "Home server")
        XCTAssertEqual(g.pending?.subject, "Low Tide")
        XCTAssertEqual(g.pending?.opensAlbum, true)
        XCTAssertFalse(seen, "asking alone doesn't count as seen")
    }

    func test_should_switch_once_and_never_ask_again_when_confirmed() {
        let g = gate()
        var switched = 0
        g.request(from: "card", account: "Home server", subject: "Low Tide", opensAlbum: true) { switched += 1 }
        g.confirm()

        XCTAssertEqual(switched, 1)
        XCTAssertNil(g.pending)
        XCTAssertTrue(seen)

        g.confirm()
        XCTAssertEqual(switched, 1, "a second confirm has nothing to switch")

        g.request(from: "card", account: "Home server", subject: "Field Notes", opensAlbum: true) { switched += 1 }
        XCTAssertEqual(switched, 2, "seen: straight through")
        XCTAssertNil(g.pending)
    }

    func test_should_drop_the_switch_and_ask_again_when_dismissed() {
        let g = gate()
        var switched = 0
        g.request(from: "card", account: "Home server", subject: "Low Tide", opensAlbum: true) { switched += 1 }
        g.dismiss()

        XCTAssertEqual(switched, 0)
        XCTAssertNil(g.pending)
        XCTAssertFalse(seen)

        g.request(from: "card", account: "Home server", subject: "Low Tide", opensAlbum: true) { switched += 1 }
        XCTAssertEqual(switched, 0)
        XCTAssertNotNil(g.pending, "cancelled: the next tap asks again")
    }

    func test_should_still_switch_when_the_notice_closes_before_its_button_runs() {
        let g = gate()
        var switched = 0
        g.request(from: "card", account: "Home server", subject: "Low Tide", opensAlbum: true) { switched += 1 }
        let shown = g.pending
        // The dialog's binding going false first, then the confirm button's action.
        g.dismiss()
        g.confirm(shown)

        XCTAssertEqual(switched, 1)
        XCTAssertNil(g.pending)
        XCTAssertTrue(seen)
    }

    func test_should_ask_in_neither_place_when_confirmed_in_one() {
        let home = gate(), memories = gate()
        var played = 0, opened = 0
        home.request(from: "shelf", account: "Spare", subject: "Road Trip", opensAlbum: false) { played += 1 }
        home.confirm()
        memories.request(from: "card", account: "Spare", subject: "Low Tide", opensAlbum: true) { opened += 1 }

        XCTAssertEqual(played, 1)
        XCTAssertEqual(opened, 1, "Home's confirm covers All Memories")
        XCTAssertNil(memories.pending)
    }

    func test_should_keep_the_flag_home_already_wrote_when_reading_whether_it_was_seen() {
        // The shelf's own @AppStorage key before the notice was shared: an install that confirmed there stays quiet.
        XCTAssertEqual(AccountSwitchNoticeStore.key, "homeOtherProfilesSwitchNoticeSeen")
        defaults.set(true, forKey: "homeOtherProfilesSwitchNoticeSeen")
        var opened = 0
        gate().request(from: "card", account: "Spare", subject: "Low Tide", opensAlbum: true) { opened += 1 }
        XCTAssertEqual(opened, 1)
    }

    func test_should_word_the_notice_for_opening_or_playing_when_asked() {
        let open = PendingAccountSwitch(anchor: "card", account: "Home server", subject: "Low Tide", opensAlbum: true, proceed: {})
        XCTAssertEqual(open.title, loc("Switch to \("Home server")?"))
        XCTAssertEqual(open.message, loc("To open \("Low Tide"), Yoin switches to that account first. What's playing now stops."))
        XCTAssertEqual(open.confirm, loc("Switch and open"))

        let play = PendingAccountSwitch(anchor: "card", account: "Home server", subject: "Road Trip", opensAlbum: false, proceed: {})
        XCTAssertEqual(play.title, open.title)
        XCTAssertEqual(play.message, loc("To play \("Road Trip"), Yoin switches to that account first. What's playing now stops."))
        XCTAssertEqual(play.confirm, loc("Switch and play"))
        XCTAssertNotEqual(open.message, play.message)
        XCTAssertTrue(open.message.contains("Low Tide"))
    }
}
