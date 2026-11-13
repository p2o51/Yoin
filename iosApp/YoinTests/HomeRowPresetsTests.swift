import XCTest
@testable import Yoin

/// Row presets (Android HomeRowPreset / HomeSectionState.config / HomeRowPresets.kt): the settings
/// bag's JSON is Android's own, so the layout syncs both ways; the ladders pick each screen's stops.
@MainActor
final class HomeRowPresetsTests: XCTestCase {
    private func json(_ data: Data) -> NSDictionary {
        (try? JSONSerialization.jsonObject(with: data)) as? NSDictionary ?? [:]
    }

    /// A layout document as Android's HomeLayoutStore writes it.
    private let androidDoc = #"{"version":1,"sections":[{"id":"activities","enabled":true,"config":{"rows":"xl"}},"#
        + #"{"id":"jump_back_in","enabled":true,"config":{"rows":"s","future":{"a":[1,2]}}},"#
        + #"{"id":"recently_added","enabled":false},{"id":"rediscover","enabled":true,"config":{"rows":"huge"}}]}"#

    func test_should_readPresetsAndKeepTheRest_when_decodingAndroidsDocument() throws {
        let prefs = try XCTUnwrap(HomeLayoutCodec.decode(Data(androidDoc.utf8)))
        let layout = HomeLayout.reconcile(prefs)
        XCTAssertEqual(layout.rowsOf(.activities), .xl)
        XCTAssertEqual(layout.rowsOf(.jumpBackIn), .s)
        XCTAssertEqual(layout.rowsOf(.recentlyAdded), .l)
        // A rows value this build can't read is the default, and stays in the bag.
        XCTAssertEqual(layout.rowsOf(.rediscover), .l)
        // Re-encoded, nothing is lost: same document, same keys, the unknown ones verbatim.
        let again = json(HomeLayoutCodec.encode(layout.toPrefs()))
        let sections = try XCTUnwrap(again["sections"] as? [NSDictionary])
        XCTAssertEqual(sections[0]["config"] as? NSDictionary, ["rows": "xl"])
        XCTAssertEqual(sections[1]["config"] as? NSDictionary, ["rows": "s", "future": ["a": [1, 2]]])
        XCTAssertNil(sections[2]["config"])
        XCTAssertEqual(sections[3]["config"] as? NSDictionary, ["rows": "huge"])
    }

    func test_should_encodeAndroidsShape_when_resizing() throws {
        let layout = HomeLayout.default.withRows(.activities, .xl).withRows(.jumpBackIn, .s)
        XCTAssertEqual(layout.rowsOf(.activities), .xl)
        let doc = json(HomeLayoutCodec.encode(layout.toPrefs()))
        XCTAssertEqual(doc["version"] as? Int, 1)
        let sections = try XCTUnwrap(doc["sections"] as? [NSDictionary])
        XCTAssertEqual(sections.map { $0["id"] as? String }.prefix(3), ["activities", "jump_back_in", "recently_added"])
        XCTAssertEqual(sections[0], ["id": "activities", "enabled": true, "config": ["rows": "xl"]] as NSDictionary)
        XCTAssertEqual(sections[1], ["id": "jump_back_in", "enabled": true, "config": ["rows": "s"]] as NSDictionary)
        // The default preset stores nothing: no config key at all.
        XCTAssertEqual(sections[2], ["id": "recently_added", "enabled": true] as NSDictionary)
        // And it reads back.
        let back = HomeLayout.reconcile(HomeLayoutCodec.decode(HomeLayoutCodec.encode(layout.toPrefs())))
        XCTAssertEqual(back.rowsOf(.activities), .xl)
        XCTAssertEqual(back.rowsOf(.jumpBackIn), .s)
        XCTAssertTrue(back.sameSections(as: layout))
    }

    func test_should_dropTheKeyAndKeepOthers_when_goingBackToDefault() throws {
        let prefs = try XCTUnwrap(HomeLayoutCodec.decode(Data(androidDoc.utf8)))
        let layout = HomeLayout.reconcile(prefs)
        let back = layout.withRows(.jumpBackIn, .l)
        let config = json(try XCTUnwrap(back.sections.first { $0.section == .jumpBackIn }?.config))
        XCTAssertEqual(config, ["future": ["a": [1, 2]]])
        // Nothing left in the bag: no bag.
        XCTAssertNil(HomeLayout.default.withRows(.activities, .m).withRows(.activities, .l).sections[0].config)
        XCTAssertEqual(HomeLayout.default.withRows(.activities, .m).withRows(.activities, .l), HomeLayout.default)
        // The unreadable value is overwritten by a real choice.
        let fixed = layout.withRows(.rediscover, .m)
        XCTAssertEqual(fixed.rowsOf(.rediscover), .l, "Rediscover takes no presets")
        XCTAssertEqual(fixed, layout)
    }

    func test_should_leaveLayoutAlone_when_nothingChanges() {
        let l = HomeLayout.default
        XCTAssertEqual(l.withRows(.activities, .l), l)
        XCTAssertEqual(l.withRows(.yourPlaylists, .xl), l)
        XCTAssertFalse(HomeLayout.default.withRows(.activities, .s).isDefault)
        XCTAssertTrue(HomeLayout.default.withRows(.activities, .s).reset().isDefault)
        XCTAssertEqual(HomeRowPreset(key: "xl"), .xl)
        XCTAssertNil(HomeRowPreset(key: "XL"))
    }

    func test_should_persistRowsWithTheDocument_when_storeSavesAndSyncs() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("rows-\(UUID().uuidString).json")
        let store = HomeLayoutStore(fileURL: url)
        store.save(HomeLayout.default.withRows(.activities, .s), scope: "a")
        XCTAssertEqual(HomeLayoutStore(fileURL: url).layout("a").rowsOf(.activities), .s)
        // A peer's document text is kept as it arrived.
        XCTAssertTrue(store.applySynced(json: androidDoc, updatedAt: 5, scope: "b"))
        XCTAssertEqual(store.layout("b").rowsOf(.activities), .xl)
        XCTAssertEqual(store.synced("b")?.json, androidDoc)
    }

    // MARK: Ladders

    func test_should_growRowsByPreset_when_phoneBentoHasEnoughEntries() {
        func shape(_ p: HomeRowPreset) -> [String] {
            HomeRowPresets.activityRows(preset: p, hasHero: true, supportingCount: 10).map { row in
                row.slots.map { "\($0.kind)\($0.entryIndex)" }.joined(separator: "+")
            }
        }
        XCTAssertEqual(shape(.s), ["hero-1"])
        XCTAssertEqual(shape(.m), ["hero-1", "small0+wide1"])
        XCTAssertEqual(shape(.l), ["hero-1", "small0+wide1", "strip2"])
        XCTAssertEqual(shape(.xl), ["hero-1", "small0+wide1", "wide2+small3", "strip4"])
        // L's strip grows into XL's wide card: every card of a smaller preset stays on the larger.
        XCTAssertEqual(HomeRowPresets.activitySeats(preset: .xl, hasHero: true), 5)
        XCTAssertEqual(HomeRowPresets.activitySeats(preset: .s, hasHero: true), 0)
        // No hero: S is the first card row.
        XCTAssertEqual(HomeRowPresets.activityRows(preset: .s, hasHero: false, supportingCount: 5).count, 1)
    }

    func test_should_mergeStops_when_historyIsShort() {
        // Hero + two entries: L and XL show the same (no strip, no second row to seat).
        let short = HomeRowPresets.activityLadder(hasHero: true, supportingCount: 2)
        XCTAssertEqual(short.map(\.presets), [[.s], [.m, .l, .xl]])
        XCTAssertEqual(short[1].presetFrom(.l), .l, "the stored choice stays")
        XCTAssertEqual(short[1].presetFrom(.s), .l, "else L")
        let full = HomeRowPresets.activityLadder(hasHero: true, supportingCount: 5)
        XCTAssertEqual(full.map(\.presets), [[.s], [.m], [.l], [.xl]])
        XCTAssertEqual(full.map(\.rowCount), [1, 2, 3, 4])
        XCTAssertEqual(full.stopIndex(of: .xl), 3)
        // A hero alone: one stop, nothing to resize.
        XCTAssertEqual(HomeRowPresets.activityLadder(hasHero: true, supportingCount: 0).count, 1)
        // A spacer: the second row's wide card has no entry yet.
        let rows = HomeRowPresets.activityRows(preset: .m, hasHero: true, supportingCount: 1)
        XCTAssertEqual(rows[1].slots.map(\.entryIndex), [0, 1])
    }

    func test_should_pickJumpBackInRowsAndSignals_when_presetChanges() {
        XCTAssertEqual(HomeRowPreset.allCases.map(HomeRowPresets.jbiRows), [2, 3, 4, 6])
        XCTAssertEqual(HomeRowPresets.jbiSignalLimit(rows: 1, cells: 3), 1)
        XCTAssertEqual(HomeRowPresets.jbiSignalLimit(rows: 2, cells: 6), 1)
        XCTAssertEqual(HomeRowPresets.jbiSignalLimit(rows: 3, cells: 9), 2)
        XCTAssertEqual(HomeRowPresets.jbiSignalLimit(rows: 4, cells: 12), 2)
        XCTAssertEqual(HomeRowPresets.jbiSignalLimit(rows: 6, cells: 18), 3)
        XCTAssertEqual(HomeRowPresets.jbiSignals(.s, available: 2), 1)
        XCTAssertEqual(HomeRowPresets.jbiSignals(.l, available: 2), 2)
        XCTAssertEqual(HomeRowPresets.jbiSignals(.xl, available: 2), 2)
    }

    // MARK: Lyric Cards' count (Android HomeLyricCountHandle / lyricCards / takesPresets)

    func test_should_mapPresetsToTwoThreeFourCards_when_readingLyricCards() {
        XCTAssertEqual(HomeRowPreset.s.lyricCards, 2)
        XCTAssertEqual(HomeRowPreset.m.lyricCards, 3)
        XCTAssertEqual(HomeRowPreset.l.lyricCards, 4)
        // XL is never written; a stored one reads as four.
        XCTAssertEqual(HomeRowPreset.xl.lyricCards, 4)
        XCTAssertEqual(HomeRowPreset.default.lyricCards, 4)
        XCTAssertEqual([2, 3, 4].map(HomeRowPreset.lyricCards), [.s, .m, .l])
    }

    func test_should_storeLyricCardsPresetInAndroidsShape_when_countChanges() throws {
        XCTAssertTrue(HomeSection.lyricHighlight.takesPresets)
        XCTAssertFalse(HomeSection.lyricHighlight.supportsRows)
        XCTAssertFalse(HomeSection.recentlyAdded.takesPresets)
        let layout = HomeLayout.default.withRows(.lyricHighlight, .lyricCards(2))
        XCTAssertEqual(layout.rowsOf(.lyricHighlight).lyricCards, 2)
        let doc = json(HomeLayoutCodec.encode(layout.toPrefs()))
        let sections = try XCTUnwrap(doc["sections"] as? [NSDictionary])
        let lyric = try XCTUnwrap(sections.first { $0["id"] as? String == "lyric_highlight" })
        XCTAssertEqual(lyric["config"] as? NSDictionary, ["rows": "s"])
        // Back to four: the default, nothing stored.
        XCTAssertEqual(layout.withRows(.lyricHighlight, .lyricCards(4)), HomeLayout.default)
        // A section without presets stays as it is.
        XCTAssertEqual(HomeLayout.default.withRows(.recentlyAdded, .s), HomeLayout.default)
    }

    func test_should_readAndroidsLyricCardsPreset_when_decoding() throws {
        let doc = #"{"version":1,"sections":[{"id":"lyric_highlight","enabled":true,"config":{"rows":"m"}}]}"#
        let layout = HomeLayout.reconcile(try XCTUnwrap(HomeLayoutCodec.decode(Data(doc.utf8))))
        XCTAssertEqual(layout.rowsOf(.lyricHighlight).lyricCards, 3)
    }

    func test_should_stepWholeCardsWithinTwoToFour_when_handleIsDragged() {
        let step = HomeLyricCount.stepDistance
        XCTAssertEqual(HomeLyricCount.count(start: 4, travel: 0), 4)
        // Short of a whole step, nothing changes either way.
        XCTAssertEqual(HomeLyricCount.count(start: 3, travel: step - 1), 3)
        XCTAssertEqual(HomeLyricCount.count(start: 3, travel: -(step - 1)), 3)
        XCTAssertEqual(HomeLyricCount.count(start: 3, travel: step), 4)
        XCTAssertEqual(HomeLyricCount.count(start: 4, travel: -step), 3)
        XCTAssertEqual(HomeLyricCount.count(start: 4, travel: -2 * step), 2)
        // Clamped at both ends.
        XCTAssertEqual(HomeLyricCount.count(start: 4, travel: 5 * step), 4)
        XCTAssertEqual(HomeLyricCount.count(start: 2, travel: -5 * step), 2)
    }
}
