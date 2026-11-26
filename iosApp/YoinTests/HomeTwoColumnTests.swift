import XCTest
import YoinShared
@testable import Yoin

/// Two-column Home on iPad and Mac (parity p4 B2; design.md §主页「两栏」): shared/…/golden/home-grid.json replayed
/// through what iOS lays out with (`HomeGrid`, `HomeDensity`'s tier, the shelf pager's filler and release), and the
/// edit session in two columns: the large-screen default, the draft following a width change, a width change
/// mid-drag rolling back, Reset, the persist rule, a two-column drop writing its pairs, the span and unit edits and
/// VoiceOver's words.
@MainActor
final class HomeTwoColumnTests: XCTestCase {
    nonisolated private static let repo = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()

    private static let grid: [String: Any] = {
        let url = repo.appendingPathComponent("shared/src/commonTest/resources/golden/home-grid.json")
        return try! JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
    }()

    private func vectors(_ section: String) -> [[String: Any]] { Self.grid[section] as! [[String: Any]] }
    private func f(_ v: [String: Any], _ k: String) -> CGFloat { CGFloat((v[k] as! NSNumber).doubleValue) }
    private func i(_ v: [String: Any], _ k: String) -> Int { (v[k] as! NSNumber).intValue }
    private func close(_ a: CGFloat, _ b: CGFloat, _ what: String, tol: CGFloat = 1e-3, line: UInt = #line) {
        XCTAssertLessThanOrEqual(abs(a - b), tol, "\(what): \(a) vs \(b)", line: line)
    }
    private func geometry(_ o: Any?) -> ShelfPageGeometry {
        let g = o as! [String: Any]
        return ShelfPageGeometry(pagePx: Float(f(g, "pagePx")), cardPx: Float(f(g, "cardPx")), perPage: Int32(i(g, "perPage")),
                                 gapPx: Float(f(g, "gapPx")))
    }

    // MARK: home-grid.json

    func test_should_answerAsTheGoldenGrid_when_iOSLaysOutTwoColumns() {
        let c = Self.grid["constants"] as! [String: Any]
        XCTAssertEqual(HomeGrid.unit, f(c, "unit"))
        XCTAssertEqual(HomeGrid.gap, f(c, "gap"))
        XCTAssertEqual(HomeGrid.titleRow, f(c, "titleRow"))
        XCTAssertEqual(HomeGrid.overdrag, f(c, "overdrag"))
        XCTAssertEqual(HomeGrid.columnGap, f(c, "columnGap"))
        XCTAssertEqual(HomeGrid.trackRowGap, f(c, "trackRowGap"))
        XCTAssertEqual(HomeGrid.rowGap, f(c, "pairGap"))
        XCTAssertEqual(HomeGrid.cover, f(c, "unit") - f(c, "captionBand"))

        // The tier: iOS has no tabletop, so its own vectors go through HomeDensity (the feed's container).
        let tiers = vectors("twoColumn")
        XCTAssertGreaterThan(tiers.count, 20)
        for v in tiers {
            let name = v["name"] as! String, expected = v["twoColumn"] as! Bool
            if v["tabletop"] as! Bool {
                XCTAssertEqual(YoinHomeLayout.shared.twoColumn(containerWidthDp: Float(f(v, "widthDp")),
                                                                containerHeightDp: Float(f(v, "heightDp")), tabletop: true), expected, name)
            } else {
                XCTAssertEqual(HomeDensity(width: f(v, "widthDp"), height: f(v, "heightDp")).wide, expected, name)
                XCTAssertEqual(HomeGrid.twoColumn(width: f(v, "widthDp"), height: f(v, "heightDp")), expected, name)
            }
        }
        for v in vectors("heights") {
            let n = i(v, "units"), name = v["name"] as! String
            close(HomeGrid.height(units: n), f(v, "heightDp"), name)
            close(HomeGrid.content(units: n), f(v, "contentDp"), name)
            XCTAssertEqual(HomeGrid.trackRows(units: n), i(v, "trackRows"), name)
            close(HomeGrid.trackRowHeight(units: n), f(v, "trackRowDp"), name)
        }
        for v in vectors("columns") {
            let w = f(v, "widthDp"), name = v["name"] as! String
            XCTAssertEqual(HomeGrid.columns(width: w), i(v, "coverColumns"), name)
            XCTAssertEqual(HomeGrid.trackColumns(width: w), i(v, "trackColumns"), name)
            XCTAssertEqual(HomeGrid.activityColumns(width: w), i(v, "activityColumns"), name)
        }
        for v in vectors("pack") {
            let slots = HomeGrid.pack(spans: (v["spans"] as! [NSNumber]).map(\.intValue), columns: i(v, "columns"), rows: i(v, "rows"))
            XCTAssertEqual(slots.map { [$0.index, $0.row, $0.column, $0.span] }, (v["cells"] as! [[NSNumber]]).map { $0.map(\.intValue) },
                           v["name"] as! String)
        }
        for v in vectors("cellX") {
            let x = HomeGrid.cellX(column: i(v, "column"), span: i(v, "span"), columns: i(v, "columns"), width: f(v, "widthPx"),
                                   gap: f(v, "gapPx"))
            XCTAssertEqual([Int(x.x), Int(x.width)], (v["cell"] as! [NSNumber]).map(\.intValue), v["name"] as! String)
        }
        for v in vectors("unitsNearest") {
            XCTAssertEqual(HomeGrid.unitsNearest(height: f(v, "heightPx"), range: i(v, "first")...i(v, "last"), density: f(v, "density")),
                           i(v, "units"), v["name"] as! String)
        }
        for v in vectors("rowCellWidths") {
            let row = (v["row"] as! [String]).compactMap(AppHomeSection.init(rawValue:))
            let dragged = (v["dragged"] as? String).flatMap(AppHomeSection.init(rawValue:))
            let widths = HomeGrid.cellWidths(row, width: f(v, "widthPx"), gap: f(v, "gapPx"), dragged: dragged, dragWidth: f(v, "dragPx"))
            XCTAssertEqual(widths.map { Int($0) }, (v["widths"] as! [NSNumber]).map(\.intValue), v["name"] as! String)
        }
        for v in vectors("shelfGeometry") {
            let g = HomeGrid.pageGeometry(page: f(v, "pagePx"), card: f(v, "cardPx"), minGap: f(v, "minGapPx"))
            let e = v["geometry"] as! [String: Any]
            XCTAssertEqual(Int(g.perPage), i(e, "perPage"), v["name"] as! String)
            close(CGFloat(g.gapPx), f(e, "gapPx"), v["name"] as! String)
            close(CGFloat(g.stridePx), f(e, "stridePx"), v["name"] as! String)
        }
        for v in vectors("shelfPages") {
            let g = geometry(v["geometry"])
            let name = v["name"] as! String
            XCTAssertEqual(HomeGrid.pageCount(cards: i(v, "cardCount"), perPage: Int(g.perPage), leadPages: i(v, "leadPages")),
                           i(v, "pageCount"), name)
            close(HomeGrid.filler(cards: i(v, "cardCount"), geometry: g), f(v, "fillerPx"), name)
            close(HomeGrid.cardWidth(page: CGFloat(g.pagePx), perPage: Int(g.perPage)), f(v, "cardWidthDp"), name)
        }
        for v in vectors("shelfRelease") {
            XCTAssertEqual(HomeGrid.releasePage(scroll: f(v, "scrollPx"), velocity: f(v, "velocityPx"), stride: f(v, "stridePx"),
                                                startPage: i(v, "startPage"), pages: i(v, "pageCount"), fling: f(v, "flingPx")),
                           i(v, "page"), v["name"] as! String)
        }
        // Where a section sits for its ⌟ (the feed's rows, the sections iOS draws).
        for v in vectors("spanInfo") {
            let rows = (v["rows"] as! [[String]]).map { $0.compactMap(AppHomeSection.init(rawValue:)) }
            let order = (v["enabledOrder"] as! [String]).compactMap(AppHomeSection.init(rawValue:))
            let section = AppHomeSection(rawValue: v["id"] as! String)!
            let place = HomeGridPlace.of(section, rows: rows, enabledOrder: order, fullWidth: f(v, "fullPx"), layout: .wideDefault)
            guard let e = v["info"] as? [String: Any] else {
                XCTAssertNil(place, v["name"] as! String)
                continue
            }
            let span: HomeGridPlace.Span = switch e["span"] as! String { case "Full": .full; case "LeftHalf": .left; default: .right }
            XCTAssertEqual(place?.span, span, v["name"] as! String)
            XCTAssertEqual(place?.hasNext, e["hasNext"] as? Bool, v["name"] as! String)
        }
    }

    // MARK: Cells

    func test_should_layEachCellOutForItsOwnWidth() {
        // iPad Pro 13" landscape: 1376 wide, 32 margins, halves (1312 − 20) / 2.
        let feed = HomeDensity(width: 1376, height: 1032)
        XCTAssertTrue(feed.wide)
        let half = HomeDensity(column: (feed.content - HomeGrid.rowGap) / 2, margin: feed.margin)
        XCTAssertEqual(half.content, 646)
        XCTAssertFalse(half.wide, "a half is under 776: the medium recipe")
        XCTAssertEqual(half.units, 4)
        let full = HomeDensity(column: feed.content, margin: feed.margin)
        XCTAssertTrue(full.wide)
        XCTAssertEqual(full.units, 8)
        // An 11" iPad in portrait is one column; a phone never pairs.
        XCTAssertFalse(HomeDensity(width: 834, height: 1210).wide)
        XCTAssertFalse(HomeDensity(width: 956, height: 440).wide)
        XCTAssertEqual(HomeGrid.activityPreset(units: 1), .s)
        XCTAssertEqual(HomeGrid.activityPreset(units: 2), .m)
        XCTAssertEqual(HomeGrid.activityPreset(units: 3), .xl)
        XCTAssertEqual(HomeEditAccessibility.position(3, of: 9, rows: "2 rows", span: .right),
                       HomeEditAccessibility.position(3, of: 9, rows: "2 rows") + ", " + String(localized: "Right half"))
        XCTAssertEqual(HomeEditAccessibility.position(1, of: 9, rows: nil, span: .full), HomeEditAccessibility.position(1, of: 9, rows: nil))
    }

    // MARK: The edit session in two columns

    private func tempStore() -> HomeLayoutStore {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("home-2col-\(UUID().uuidString).json")
        addTeardownBlock { try? FileManager.default.removeItem(at: url) }
        return HomeLayoutStore(fileURL: url)
    }

    private func pairs(_ l: AppHomeLayout) -> [[AppHomeSection]] { l.pairs.map { [$0.left, $0.right] } }

    func test_should_editTheLargeScreenDefault_andWriteNothingUntouched_when_enteredInTwoColumns() {
        let store = tempStore()
        let edit = HomeEditController(store: store)
        edit.load(scope: "acc")
        edit.setTwoColumn(true)
        XCTAssertEqual(pairs(edit.shown), pairs(.wideDefault), "out of edit mode two columns show the large-screen default")
        edit.enter()
        XCTAssertTrue(edit.layout.sameSections(as: .wideDefault))
        XCTAssertTrue(edit.isAtDefault)
        edit.exit(.done)
        XCTAssertFalse(store.isCustomized("acc"), "an untouched session writes nothing")
        XCTAssertTrue(edit.layout.sameSections(as: .default))
    }

    func test_should_followTheWidth_when_theFeedCrossesTheTierInEditMode() {
        // Untouched: what entering at the new width would have made it.
        let edit = HomeEditController(store: tempStore())
        edit.load(scope: "acc")
        edit.setTwoColumn(true)
        edit.enter()
        edit.setTwoColumn(false)
        XCTAssertTrue(edit.layout.sameSections(as: .default))
        edit.setTwoColumn(true)
        XCTAssertTrue(edit.layout.sameSections(as: .wideDefault))
        // Edited: it keeps its order, and two columns pair it from its memory again.
        edit.hide(.rediscover)
        let order = edit.layout.shownSections
        edit.setTwoColumn(false)
        XCTAssertEqual(edit.layout.shownSections, order)
        XCTAssertTrue(edit.shown.pairs.isEmpty == false || edit.layout.widePairs.count > 0, "the memory is kept in one column")
        edit.setTwoColumn(true)
        XCTAssertEqual(edit.layout.shownSections, order)
        XCTAssertTrue(pairs(edit.shown).contains([.activities, .jumpBackIn]))
    }

    func test_should_resetToTheLargeScreenDefault_andClearTheAccount_when_resetInTwoColumns() {
        let store = tempStore()
        let edit = HomeEditController(store: store)
        edit.load(scope: "acc")
        edit.setTwoColumn(true)
        edit.enter()
        edit.setFull(.activities)
        XCTAssertNil(edit.layout.partner(of: .activities))
        XCTAssertTrue(store.isCustomized("acc"))
        XCTAssertTrue(store.synced("acc")!.json.contains("\"wide\""))
        edit.reset()
        XCTAssertTrue(edit.layout.sameSections(as: .wideDefault))
        XCTAssertFalse(store.isCustomized("acc"), "either default writes as never customized")
        XCTAssertEqual(edit.undoStack.count, 2)
    }

    func test_should_pairAndUnpair_through_halfFullBesideAndUnits() {
        let store = tempStore()
        let edit = HomeEditController(store: store)
        edit.load(scope: "acc")
        edit.setTwoColumn(true)
        edit.enter()
        XCTAssertFalse(edit.isHalf(.madeForYou))
        XCTAssertTrue(edit.canPlaceBesidePrevious(.madeForYou))
        edit.placeBesidePrevious(.madeForYou)
        XCTAssertEqual(edit.layout.partner(of: .madeForYou), .memoryGrid)
        XCTAssertTrue(edit.isHalf(.memoryGrid))
        // The memory is what two columns show (shared homeEditStep).
        XCTAssertTrue(edit.layout.widePairs.contains(["memory_grid", "made_for_you"]))
        // A right half turned full goes ahead of its old partner (shared withFull)…
        edit.setFull(.madeForYou)
        XCTAssertNil(edit.layout.partner(of: .memoryGrid))
        XCTAssertLessThan(edit.layout.shownSections.firstIndex(of: .madeForYou)!, edit.layout.shownSections.firstIndex(of: .memoryGrid)!)
        // …so made half again pulls the row after it (All Memories) up beside it.
        edit.setHalf(.madeForYou)
        XCTAssertEqual(edit.layout.partner(of: .memoryGrid), .madeForYou)
        // Units: a pair shares one count, held in both ranges (Activities 1…3, Jump Back In 1…4).
        edit.setUnits(.jumpBackIn, 4)
        XCTAssertEqual(edit.layout.units(ofRow: [.activities, .jumpBackIn]), 3)
        edit.setUnits(.activities, 1)
        XCTAssertEqual(edit.layout.units(ofRow: [.activities, .jumpBackIn]), 1)
        XCTAssertEqual(edit.undoStack.count, 5, "beside, full, half and two counts: a step each")
        edit.commitSpan(.memoryGrid, .tofull)
        XCTAssertNil(edit.layout.partner(of: .memoryGrid))
    }

    /// A two-column feed of fixed rows: each row its grid height, 18 apart from y = 100; the content 32…1344.
    private final class GridHost: HomeDragHost {
        weak var edit: HomeEditController?
        var order: [AppHomeSection]
        var isList: Bool { false }
        init(_ edit: HomeEditController) {
            self.edit = edit
            order = edit.shown.shownSections
        }

        func bands(for order: [AppHomeSection]) -> [(section: AppHomeSection, top: CGFloat, bottom: CGFloat)] {
            bands(forRows: order.map { [$0] })
        }

        func bands(forRows rows: [[AppHomeSection]]) -> [(section: AppHomeSection, top: CGFloat, bottom: CGFloat)] {
            MainActor.assumeIsolated {
                var y: CGFloat = 100
                return rows.map { row in
                    let h = HomeGrid.height(units: edit!.shown.units(ofRow: row))
                    defer { y += h + 18 }
                    return (row[0], y, y + h)
                }
            }
        }

        var pairs: [(AppHomeSection, AppHomeSection)]? { MainActor.assumeIsolated { edit!.shown.pairs.map { ($0.left, $0.right) } } }
        var contentLeft: CGFloat { 32 }
        var contentWidth: CGFloat { 1312 }
        var scrollArea: (top: CGFloat, bottom: CGFloat) { (0, 5000) }
        var viewportTop: CGFloat { 0 }
        func scrollBy(_ dy: CGFloat) -> CGFloat { 0 }
    }

    private var hosts: [GridHost] = []

    private func carry(_ edit: HomeEditController, _ section: AppHomeSection, x: CGFloat, from: CGFloat, to: CGFloat) {
        edit.drag.liftBlock(section)
        edit.drag.setFingerX(x)
        edit.drag.start(fingerY: from, inList: false)
        edit.drag.frame(dt: 0)
        for k in 1...12 {
            edit.drag.move(y: from + (to - from) * CGFloat(k) / 12)
            edit.drag.frame(dt: 0.016)
        }
    }

    func test_should_writeThePairOnce_when_aFullRowIsDroppedAt85PercentAcrossTheRowAbove() {
        let store = tempStore()
        let edit = HomeEditController(store: store)
        edit.load(scope: "acc")
        edit.setTwoColumn(true)
        edit.enter()
        let host = GridHost(edit)
        hosts.append(host)
        edit.drag.feedHost = host
        edit.drag.schedulesFrames = false
        // Rows: Activities | Jump Back In 100…454, All Memories 472…662, Made For You 680…870.
        carry(edit, .madeForYou, x: 32 + 0.85 * 1312, from: 695, to: 567)
        XCTAssertEqual(edit.pairs.map { [$0.left, $0.right] }.first { $0.contains(.madeForYou) }, [.memoryGrid, .madeForYou],
                       "the feed shows the pair while the finger is down")
        XCTAssertTrue(edit.undoStack.isEmpty)
        edit.drag.release(cancelled: false)
        XCTAssertEqual(edit.layout.partner(of: .madeForYou), .memoryGrid)
        XCTAssertEqual(edit.undoStack.count, 1)
        XCTAssertTrue(store.synced("acc")!.json.contains("[\"memory_grid\",\"made_for_you\"]"), store.synced("acc")!.json)
        edit.undo()
        XCTAssertNil(edit.layout.partner(of: .madeForYou))
    }

    func test_should_rollBack_when_theWidthChangesMidDrag() {
        let store = tempStore()
        let edit = HomeEditController(store: store)
        edit.load(scope: "acc")
        edit.setTwoColumn(true)
        edit.enter()
        let host = GridHost(edit)
        hosts.append(host)
        edit.drag.feedHost = host
        edit.drag.schedulesFrames = false
        carry(edit, .madeForYou, x: 32 + 0.85 * 1312, from: 695, to: 567)
        XCTAssertNotNil(edit.drag.live)
        edit.setTwoColumn(false)
        XCTAssertNil(edit.drag.dragging)
        XCTAssertNil(edit.layout.partner(of: .madeForYou))
        XCTAssertTrue(edit.undoStack.isEmpty)
        XCTAssertFalse(store.isCustomized("acc"))
        XCTAssertTrue(edit.layout.sameSections(as: .default), "untouched, the one-column draft")
    }

    func test_should_snapTheSpanAndTheUnits_when_theHandleIsDragged() {
        let resize = HomeGridResize()
        var ticks = 0
        resize.tick = { ticks += 1 }
        let place = HomeGridPlace(section: .memoryGrid, row: [.memoryGrid], span: .full, hasNext: true, units: 1, range: 1...3,
                                  fullWidth: 1312, halfWidth: 646)
        // Sideways first: the span axis; past the midpoint the outline turns half.
        resize.grab(place, at: CGPoint(x: 1300, y: 500))
        resize.drag(to: CGPoint(x: 1286, y: 503))
        XCTAssertEqual(resize.spanSection, .memoryGrid)
        XCTAssertEqual(resize.spanTarget, .full)
        resize.drag(to: CGPoint(x: 1300 - 400, y: 520))
        XCTAssertEqual(resize.spanWidth, 912)
        XCTAssertEqual(resize.spanTarget, .left)
        XCTAssertEqual(ticks, 1)
        resize.drag(to: CGPoint(x: 0, y: 520))
        XCTAssertEqual(resize.spanWidth, 646 * 0.82, accuracy: 0.01, "a rubber band a little under a half")
        resize.snap()
        // Down first: the units axis over the range, 24 pt of rubber band past either end.
        resize.grab(place, at: CGPoint(x: 1300, y: 500))
        resize.drag(to: CGPoint(x: 1302, y: 520))
        XCTAssertEqual(resize.unitRow, [.memoryGrid])
        resize.drag(to: CGPoint(x: 1302, y: 500 + 170))
        XCTAssertEqual(resize.unitTarget, 2)
        resize.drag(to: CGPoint(x: 1302, y: 2000))
        XCTAssertEqual(resize.unitHeight, HomeGrid.height(units: 3) + HomeGrid.overdrag)
        XCTAssertEqual(resize.unitTarget, 3)
        XCTAssertEqual(resize.liveHeight([.memoryGrid]), resize.unitHeight)
        resize.snap()
        XCTAssertFalse(resize.isActive)
        // Keys: ← on a full row with a row after it turns half; → on a left half turns full.
        XCTAssertEqual(HomeGridResize.spanKey(place, left: true), .tohalf)
        XCTAssertNil(HomeGridResize.spanKey(place, left: false))
        var left = place
        left.span = .left
        XCTAssertEqual(HomeGridResize.spanKey(left, left: false), .tofull)
    }
}
