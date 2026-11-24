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
        XCTAssertEqual(Set(Tips.catalog.map(\.id)).count, Tips.catalog.count)
        XCTAssertFalse(Tips.all.contains { $0.id == "ScrollEdge" }, "iOS has no Settings › Motion › Scroll edge")
        for id in ["ScreenCorners", "SettingsRegrouped"] {
            XCTAssertNil(Tips.tip(id), "\(id) is the system's on iOS, or never applied")
        }
    }

    func test_should_leaveOutTheKeyboardTip_when_theDeviceHasNoShortcutMenu() throws {
        let keyboard = try XCTUnwrap(Tips.catalog.first { $0.id == "KeyboardShortcuts" })
        XCTAssertTrue(keyboard.keyboard)
        XCTAssertFalse(Tips.available(Tips.catalog, keyboard: false).contains { $0.id == "KeyboardShortcuts" })
        XCTAssertTrue(Tips.available(Tips.catalog, keyboard: true).contains { $0.id == "KeyboardShortcuts" })
        // Only the keyboard's tip depends on it.
        XCTAssertEqual(Tips.available(Tips.catalog, keyboard: false).count, Tips.catalog.count - 1)
        XCTAssertNotNil(Tips.catalog.first { $0.id == "LibraryRefresh" })
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
        XCTAssertEqual(tip.updated, "2026-11-15")
        XCTAssertEqual(tip.title, "Spotify speakers")
        XCTAssertEqual(tip.titleZhHans, "Spotify 音箱")
        XCTAssertEqual(tip.text, "In Now Playing > Devices, switch Spotify to a speaker and keep controlling it in Yoin; tap refresh to update, and recently used speakers stay in the list")
        XCTAssertEqual(tip.textZhHans, "正在播放 > 设备里将 Spotify 切到音箱上，继续在 Yoin 里控制；点刷新可更新，最近用过的音箱会留在列表里")
    }

    func test_should_includeOnlineAiAndCloudSync_andFollowMemoriesGlossary() throws {
        let ai = try XCTUnwrap(Tips.tip("AiServices"))
        XCTAssertEqual(ai.titleZhHans, "在线 AI")
        let cloud = try XCTUnwrap(Tips.tip("CloudSync"))
        XCTAssertEqual(cloud.titleZhHans, "云同步")
        let amNames = try XCTUnwrap(Tips.tip("AppleMusicSongNames"))
        XCTAssertEqual(amNames.updated, "2026-11-23")
        XCTAssertEqual(amNames.titleZhHans, "Apple Music 歌曲名")
        XCTAssertTrue(TipIllustration.has("AppleMusicSongNames"))
        let icloud = try XCTUnwrap(Tips.tip("ICloudSync"))
        XCTAssertEqual(icloud.updated, "2026-11-23")
        XCTAssertEqual(icloud.titleZhHans, "iCloud 云同步")
        XCTAssertTrue(TipIllustration.has("ICloudSync"))
        let ledger = try XCTUnwrap(Tips.tip("MemoryLedger"))
        XCTAssertTrue(ledger.titleZhHans.contains("Memories"))
        XCTAssertFalse(ledger.textZhHans.contains("回忆"))
        let across = try XCTUnwrap(Tips.tip("MemoriesAllAccounts"))
        XCTAssertTrue(across.titleZhHans.contains("Memories"))
        XCTAssertFalse(across.textZhHans.contains("回忆"))
        let labs = try XCTUnwrap(Tips.tip("Labs"))
        XCTAssertEqual(labs.updated, "2026-11-12")
        for tip in Tips.catalog {
            for forbidden in ["回忆", "手账", "Nowledge Mem", "国内直连"] {
                XCTAssertFalse(tip.titleZhHans.contains(forbidden) || tip.textZhHans.contains(forbidden),
                               "Tip \(tip.id) uses forbidden term \(forbidden)")
            }
        }
    }
}
