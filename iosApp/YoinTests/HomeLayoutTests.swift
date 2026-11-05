import XCTest
@testable import Yoin

/// The Home section catalog, its reconcile and edit operations, the layout
/// document (Android HomeLayoutStore's shape) and per-account persistence.
@MainActor
final class HomeLayoutTests: XCTestCase {
    private func tempURL() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("home-layout-\(UUID().uuidString).json")
    }

    func test_should_beCatalogDefault_when_neverCustomized() {
        XCTAssertEqual(HomeLayout.reconcile(nil), .default)
        XCTAssertEqual(HomeLayout.reconcile([]), .default)
        XCTAssertEqual(HomeLayout.default.shownSections, [.activities, .jumpBackIn, .recentlyAdded, .rediscover])
        XCTAssertTrue(HomeLayout.default.isDefault)
    }

    func test_should_keepSavedOrderDropRetiredRetainUnknown_when_reconciling() {
        let prefs = [
            HomeSectionPref(id: "rediscover", enabled: true),
            HomeSectionPref(id: "memory_teaser", enabled: true),
            HomeSectionPref(id: "future_thing", enabled: true, config: Data(#"{"rows":"xl"}"#.utf8)),
            HomeSectionPref(id: "activities", enabled: false),
            HomeSectionPref(id: "activities", enabled: true),
            HomeSectionPref(id: "", enabled: true),
        ]
        let l = HomeLayout.reconcile(prefs)
        XCTAssertEqual(Array(l.sections.prefix(2).map(\.section)), [.rediscover, .activities])
        // First occurrence wins.
        XCTAssertFalse(l.sections[1].enabled)
        XCTAssertEqual(l.retained.map(\.id), ["future_thing"])
        // Appended at appendEnabled: none of these is Rediscover, so all show and none is "New".
        XCTAssertEqual(Set(l.sections.dropFirst(2).map(\.section)),
                       [.jumpBackIn, .recentlyAdded, .recentlyPlayed, .yourPlaylists, .otherProfiles])
        XCTAssertTrue(l.newSections.isEmpty)
    }

    func test_should_appendRediscoverHiddenAndNew_when_customizedLayoutPredatesIt() {
        let l = HomeLayout.reconcile([HomeSectionPref(id: "jump_back_in", enabled: true)])
        let rediscover = l.sections.first { $0.section == .rediscover }
        XCTAssertEqual(rediscover?.enabled, false)
        XCTAssertEqual(l.newSections, [.rediscover])
        XCTAssertEqual(l.trayed, [.rediscover])
        // Shown from the tray it is no longer new.
        XCTAssertTrue(l.withEnabled(.rediscover, true).newSections.isEmpty)
    }

    func test_should_moveAmongShownKeepingHiddenSlots_when_reordering() {
        let l = HomeLayout.default.withEnabled(.jumpBackIn, false)
        let moved = l.movedShown(.rediscover, to: 0)
        XCTAssertEqual(moved.shownSections, [.rediscover, .activities, .recentlyAdded])
        // The hidden one keeps its absolute index.
        XCTAssertEqual(moved.sections[1].section, .jumpBackIn)
        XCTAssertFalse(moved.sections[1].enabled)
        // Unchanged when nothing moves.
        XCTAssertEqual(moved.movedShown(.rediscover, to: 0), moved)
        XCTAssertEqual(l.movedShown(.jumpBackIn, to: 2), l)
    }

    func test_should_rejectNonPermutation_when_settingEnabledOrder() {
        let l = HomeLayout.default
        XCTAssertEqual(l.withEnabledOrder([.activities]), l)
        XCTAssertEqual(l.withEnabledOrder(l.enabledSections + [.activities]), l)
    }

    func test_should_backToCatalogKeepingRetained_when_reset() {
        var l = HomeLayout.reconcile([HomeSectionPref(id: "x", enabled: true), HomeSectionPref(id: "rediscover", enabled: true)])
        l = l.withEnabled(.activities, false)
        let r = l.reset()
        XCTAssertTrue(r.isDefault)
        XCTAssertEqual(r.retained.map(\.id), ["x"])
    }

    func test_should_roundTripAndDecodeEntryByEntry_when_codingTheDocument() {
        let prefs = [HomeSectionPref(id: "activities", enabled: false),
                     HomeSectionPref(id: "z", enabled: true, config: Data(#"{"rows":"s"}"#.utf8))]
        let data = HomeLayoutCodec.encode(prefs)
        let json = String(decoding: data, as: UTF8.self)
        XCTAssertTrue(json.contains(#""version":1"#))
        XCTAssertFalse(json.contains("null"))
        XCTAssertEqual(HomeLayoutCodec.decode(data)?.map(\.id), ["activities", "z"])
        XCTAssertEqual(HomeLayoutCodec.decode(data)?.first?.enabled, false)
        // Android's document, one bad entry: only it drops.
        let android = #"{"sections":[{"id":"rediscover","enabled":true},{"id":5},{"id":"activities","enabled":"yes"}]}"#
        XCTAssertEqual(HomeLayoutCodec.decode(Data(android.utf8))?.map(\.id), ["rediscover"])
        XCTAssertNil(HomeLayoutCodec.decode(Data("nope".utf8)))
    }

    func test_should_persistPerAccount_when_saved() {
        let url = tempURL()
        defer { try? FileManager.default.removeItem(at: url) }
        let store = HomeLayoutStore(fileURL: url)
        let a = HomeLayout.default.movedShown(.rediscover, to: 0)
        store.save(a, scope: "subsonic|https://a|me")
        // Another account keeps its own (the default).
        XCTAssertEqual(store.layout("subsonic|https://b|me"), .default)
        // Read back from disk.
        let again = HomeLayoutStore(fileURL: url)
        XCTAssertEqual(again.layout("subsonic|https://a|me").shownSections.first, .rediscover)
        again.clear("subsonic|https://a|me")
        XCTAssertNil(HomeLayoutStore(fileURL: url).prefs("subsonic|https://a|me"))
    }

    func test_should_keepSyncedTextAsReceived_when_anotherDeviceSendsLayout() {
        let url = tempURL()
        defer { try? FileManager.default.removeItem(at: url) }
        let store = HomeLayoutStore(fileURL: url)
        // Android's encoder: id before enabled, no sorted keys.
        let android = #"{"version":1,"sections":[{"id":"rediscover","enabled":true},{"id":"activities","enabled":false}]}"#
        XCTAssertTrue(store.applySynced(json: android, updatedAt: 700, scope: "acc"))
        XCTAssertEqual(store.synced("acc")?.json, android)
        XCTAssertEqual(store.synced("acc")?.updatedAt, 700)
        XCTAssertEqual(store.layout("acc").shownSections.first, .rediscover)
        XCTAssertEqual(store.remoteRevision, 1)
        // Read back from disk unchanged.
        XCTAssertEqual(HomeLayoutStore(fileURL: url).synced("acc")?.json, android)
        // Not a layout document: nothing changes.
        XCTAssertFalse(store.applySynced(json: "nope", updatedAt: 800, scope: "acc"))
        XCTAssertEqual(store.synced("acc")?.updatedAt, 700)
        XCTAssertTrue(store.clearSynced("acc"))
        XCTAssertNil(store.synced("acc"))
    }

    func test_should_readFirstFileFormat_when_upgrading() throws {
        let url = tempURL()
        defer { try? FileManager.default.removeItem(at: url) }
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let old = #"{"acc":{"version":1,"sections":[{"enabled":true,"id":"rediscover"}]}}"#
        try Data(old.utf8).write(to: url)
        let store = HomeLayoutStore(fileURL: url)
        XCTAssertEqual(store.layout("acc").shownSections.first, .rediscover)
        XCTAssertEqual(store.synced("acc")?.updatedAt, 0)
    }

    func test_should_askSyncToSend_when_layoutEditedLocally() {
        let url = tempURL()
        defer { try? FileManager.default.removeItem(at: url); HomeLayoutStore.onLocalChange = nil }
        var sent = 0
        HomeLayoutStore.onLocalChange = { sent += 1 }
        let store = HomeLayoutStore(fileURL: url)
        store.save(HomeLayout.default.movedShown(.rediscover, to: 0), scope: "acc")
        XCTAssertEqual(sent, 1)
        XCTAssertGreaterThan(store.synced("acc")?.updatedAt ?? 0, 0)
        // A remote apply is not sent back.
        _ = store.applySynced(json: #"{"version":1,"sections":[]}"#, updatedAt: 5, scope: "acc")
        XCTAssertEqual(sent, 1)
        store.clear("acc")
        XCTAssertEqual(sent, 2)
    }

    func test_should_saveEachChangeAndUndo_when_editing() {
        let url = tempURL()
        defer { try? FileManager.default.removeItem(at: url) }
        let store = HomeLayoutStore(fileURL: url)
        let edit = HomeEditController(store: store)
        edit.load(scope: "acc")
        edit.enter()
        edit.hide(.jumpBackIn)
        XCTAssertEqual(store.layout("acc").trayed, [.jumpBackIn])
        edit.move(.rediscover, to: 0)
        XCTAssertEqual(store.layout("acc").shownSections.first, .rediscover)
        XCTAssertEqual(edit.undoStack.count, 2)
        edit.undo()
        XCTAssertEqual(store.layout("acc").shownSections, [.activities, .recentlyAdded, .rediscover])
        edit.reset()
        XCTAssertNil(store.prefs("acc"))
        XCTAssertTrue(edit.layout.isDefault)
    }

    func test_should_countStripsAbove_when_choosingTheHole() {
        let frames = [CGRect(x: 0, y: 0, width: 10, height: 56), CGRect(x: 0, y: 64, width: 10, height: 56),
                      CGRect(x: 0, y: 128, width: 10, height: 56)]
        XCTAssertEqual(HomeEditController.slot(centre: 10, others: frames), 0)
        XCTAssertEqual(HomeEditController.slot(centre: 100, others: frames), 2)
        XCTAssertEqual(HomeEditController.slot(centre: 500, others: frames), 3)
    }

    func test_should_rippleEachSectionABeatLater_when_entering() {
        XCTAssertEqual(HomeEditController.ripple(1, index: 3), 1)
        XCTAssertEqual(HomeEditController.ripple(0.06, index: 1), 0, accuracy: 1e-9)
        XCTAssertGreaterThan(HomeEditController.ripple(0.5, index: 0), HomeEditController.ripple(0.5, index: 2))
        XCTAssertEqual(HomeEditController.swayAngle(.activities, time: 3, envelope: 0), 0)
    }
}
