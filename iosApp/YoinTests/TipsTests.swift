import XCTest
@testable import Yoin

@MainActor
final class TipsTests: XCTestCase {
    private func tip(_ id: String, updated: String = "2026-10-17") -> Tip {
        Tip(id: id, icon: "sparkle", updated: updated, title: id, text: id, titleZhHans: id, textZhHans: id)
    }

    private func store(_ tips: [Tip], suite: String = UUID().uuidString) -> (TipReadStore, UserDefaults) {
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        return (TipReadStore(defaults: defaults, tips: tips), defaults)
    }

    func test_should_listEveryTipOnce_when_readingTheCatalog() {
        XCTAssertEqual(Set(Tips.all.map(\.id)).count, Tips.all.count)
        XCTAssertFalse(Tips.all.contains { $0.id == "ScrollEdge" }, "iOS has no Settings › Motion › Scroll edge")
        for id in ["ScreenCorners", "SettingsRegrouped"] {
            XCTAssertNil(Tips.tip(id), "\(id) is the system's on iOS, or never applied")
        }
    }

    func test_should_comeBackOnAndroidsDay_when_iOSHasTheSameSongMenu() throws {
        // Android's SongMenu moved to 11-07 when Library's rows got the sheet; iOS's Library rows have it too.
        let songMenu = try XCTUnwrap(Tips.tip("SongMenu"))
        XCTAssertEqual(songMenu.updated, "2026-11-07")
        XCTAssertTrue(songMenu.text.contains("Library"))
        XCTAssertTrue(songMenu.textZhHans.contains("资料库"))
        // A reader who read it on its old day sees it again.
        let (s, defaults) = store([tip("SongMenu", updated: "2026-11-04")])
        s.setRead(tip("SongMenu", updated: "2026-11-04"), true)
        XCTAssertTrue(TipReadStore(defaults: defaults, tips: [songMenu]).unread.contains { $0.id == "SongMenu" })
    }

    func test_should_beUnread_when_nothingWasMarked() {
        let tips = [tip("A"), tip("B")]
        let (s, _) = store(tips)
        XCTAssertEqual(s.unread.map(\.id), ["A", "B"])
    }

    func test_should_beRead_when_markedAndReloaded() {
        let tips = [tip("A"), tip("B")]
        let (s, defaults) = store(tips)
        s.setRead(tips[0], true)
        XCTAssertTrue(s.isRead(tips[0]))
        XCTAssertEqual(s.unread.map(\.id), ["B"])
        let again = TipReadStore(defaults: defaults, tips: tips)
        XCTAssertTrue(again.isRead(tips[0]))
        XCTAssertFalse(again.isRead(tips[1]))
    }

    func test_should_beUnreadAgain_when_theTipsUpdatedDayMovesOn() {
        let before = [tip("A", updated: "2026-10-17")]
        let (s, defaults) = store(before)
        s.setRead(before[0], true)
        let after = [tip("A", updated: "2026-11-04")]
        let reloaded = TipReadStore(defaults: defaults, tips: after)
        XCTAssertFalse(reloaded.isRead(after[0]))
        XCTAssertEqual(reloaded.unread.map(\.id), ["A"])
    }

    func test_should_keepRead_when_theUpdatedDayIsTheSame() {
        let tips = [tip("A", updated: "2026-11-04")]
        XCTAssertEqual(Tips.decodeRead(Tips.encodeRead(["A"], in: tips), in: tips), ["A"])
    }

    func test_should_dropReadTip_when_itWasRemoved() {
        let saved = Tips.encodeRead(["A", "B"], in: [tip("A"), tip("B")])
        XCTAssertEqual(Tips.decodeRead(saved, in: [tip("B")]), ["B"])
    }

    func test_should_markEverythingUnread_when_setAllUnread() {
        let tips = [tip("A"), tip("B")]
        let (s, defaults) = store(tips)
        s.setRead(tips[0], true)
        s.setRead(tips[1], true)
        s.setAllUnread()
        XCTAssertTrue(s.read.isEmpty)
        XCTAssertTrue(TipReadStore(defaults: defaults, tips: tips).read.isEmpty)
    }

    func test_should_markUnread_when_setReadFalse() {
        let tips = [tip("A")]
        let (s, _) = store(tips)
        s.setRead(tips[0], true)
        s.setRead(tips[0], false)
        XCTAssertFalse(s.isRead(tips[0]))
    }

    func test_should_summariseReadCount() {
        XCTAssertEqual(Tips.readSummary(read: 3, total: 11), String(localized: "\(3) of \(11) read"))
        XCTAssertEqual(Tips.readSummary(read: 11, total: 11), String(localized: "All read"))
    }

    func test_should_formatTheUpdatedDay() {
        XCTAssertEqual(Tips.dayText("2026-11-04", locale: Locale(identifier: "en_US")), "Nov 4, 2026")
        XCTAssertEqual(Tips.dayText("2026-11-04", locale: Locale(identifier: "zh_Hans_CN")), "2026年11月4日")
    }

    // MARK: The bubble's turns

    func test_should_sayMemory_when_itsTheMemoryTurn() {
        let unread = [tip("A"), tip("B")]
        XCTAssertEqual(BubbleSayPlan.idle(hasMemory: true, sayMemory: true, tipTurn: 0, unread: unread), .memory)
    }

    func test_should_sayTip_when_itsTheTipTurn() {
        let unread = [tip("A"), tip("B")]
        XCTAssertEqual(BubbleSayPlan.idle(hasMemory: true, sayMemory: false, tipTurn: 1, unread: unread), .tip(unread[1]))
    }

    func test_should_sayTips_when_thereIsNoMemory() {
        let unread = [tip("A"), tip("B")]
        XCTAssertEqual(BubbleSayPlan.idle(hasMemory: false, sayMemory: true, tipTurn: 0, unread: unread), .tip(unread[0]))
    }

    func test_should_stepThroughEveryUnreadTip_when_tipTurnsGrow() {
        let unread = [tip("A"), tip("B"), tip("C")]
        let said = (0..<4).map { BubbleSayPlan.idle(hasMemory: false, sayMemory: false, tipTurn: $0, unread: unread) }
        XCTAssertEqual(said, [.tip(unread[0]), .tip(unread[1]), .tip(unread[2]), .tip(unread[0])])
    }

    func test_should_sayMemory_when_everyTipIsRead() {
        XCTAssertEqual(BubbleSayPlan.idle(hasMemory: true, sayMemory: false, tipTurn: 0, unread: []), .memory)
    }

    func test_should_stayQuiet_when_noMemoryAndEveryTipRead() {
        XCTAssertNil(BubbleSayPlan.idle(hasMemory: false, sayMemory: true, tipTurn: 0, unread: []))
    }

    func test_should_wrapTheTurn_when_itIsNegative() {
        let unread = [tip("A"), tip("B")]
        XCTAssertEqual(BubbleSayPlan.idle(hasMemory: false, sayMemory: false, tipTurn: -1, unread: unread), .tip(unread[1]))
    }

    func test_should_alternate_when_turnsCount() {
        XCTAssertEqual((0..<4).map(BubbleSayPlan.isMemoryTurn), [true, false, true, false])
    }

    func test_should_carryAndroidsSpotifySpeakersTip_when_theDevicesSheetListsSpeakersWithoutAnId() throws {
        let tip = try XCTUnwrap(Tips.tip("SpotifySpeakers"))
        XCTAssertEqual(tip.updated, "2026-11-08")
        XCTAssertEqual(tip.title, "Connect more speakers")
        XCTAssertEqual(tip.titleZhHans, "连接更多音箱")
        XCTAssertEqual(tip.text, "Tap More devices… in Now Playing > Devices to switch Spotify to Sonos, PS5, and more; recently used speakers stay in the list")
        XCTAssertEqual(tip.textZhHans, "正在播放 > 设备里点「更多设备…」，将 Spotify 切到 Sonos、PS5 等音箱；用过的会留在列表里")
    }
}
