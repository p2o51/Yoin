import XCTest
import YoinShared
@testable import Yoin

/// Home's layout and edit core on iOS (parity p4 B0 + B1): the golden vectors of :shared
/// (shared/src/commonTest/resources/golden/home-layout.json, home-drag.json, home-edit-gestures.json) run
/// through what iOS adds — the Swift `HomeLayout` wrapper, its codec and `HomeLayoutStore` (byte checks of what
/// the store writes), `HomeDragController` over a fake host (the timelines frame by frame, the scroll it asks
/// for, the order it writes), and `HomeEditGestureDriver` (the machine on the events' own clock).
@MainActor
final class HomeEditGoldenTests: XCTestCase {
    nonisolated private static let repo = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()

    nonisolated private static func golden(_ name: String) -> [String: Any] {
        let url = repo.appendingPathComponent("shared/src/commonTest/resources/golden/\(name)")
        return try! JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
    }

    nonisolated(unsafe) private static let layoutGolden = golden("home-layout.json")
    private static let dragGolden = golden("home-drag.json")
    private static let gestureGolden = golden("home-edit-gestures.json")

    nonisolated private static func opt<T>(_ o: Any?) -> T? { o is NSNull ? nil : o as? T }
    private var core: YoinHomeLayout { YoinHomeLayout.shared }

    // MARK: JSON comparison

    /// [a] and [b] say the same: numbers within [tol] ("NaN" strings equal NaN), arrays in order, objects by key.
    private static func close(_ a: Any?, _ b: Any?, tol: Double = 1e-4) -> Bool {
        let a = a is NSNull ? nil : a, b = b is NSNull ? nil : b
        switch (a, b) {
        case (nil, nil): return true
        case let (x as String, y as String): return x == y
        case let (x as NSNumber, y as NSNumber):
            if CFGetTypeID(x) == CFBooleanGetTypeID() || CFGetTypeID(y) == CFBooleanGetTypeID() { return x.boolValue == y.boolValue }
            return abs(x.doubleValue - y.doubleValue) <= tol
        case let (x as NSNumber, "NaN" as String), let ("NaN" as String, x as NSNumber): return x.doubleValue.isNaN
        case let (x as [Any], y as [Any]): return x.count == y.count && zip(x, y).allSatisfy { close($0, $1, tol: tol) }
        case let (x as [String: Any], y as [String: Any]):
            let keys = Set(x.keys).union(y.keys)
            return keys.allSatisfy { close(x[$0], y[$0], tol: tol) }
        default: return false
        }
    }

    private func expectClose(_ actual: Any?, _ expected: Any?, _ what: String, tol: Double = 1e-4,
                             file: StaticString = #filePath, line: UInt = #line) {
        if !Self.close(actual, expected, tol: tol) {
            XCTFail("\(what): expected \(String(describing: expected)) but was \(String(describing: actual))", file: file, line: line)
        }
    }

    nonisolated private static func parse(_ json: String?) -> Any? {
        json.flatMap { try? JSONSerialization.jsonObject(with: Data($0.utf8)) }
    }

    nonisolated private static func text(_ o: Any?) -> String? {
        guard let o = opt(o) as Any?, JSONSerialization.isValidJSONObject(o) else { return nil }
        return String(decoding: try! JSONSerialization.data(withJSONObject: o), as: UTF8.self)
    }

    // MARK: State ⇄ JSON (HomeLayoutGolden.stateJson / stateOf)

    nonisolated private static func stateJSON(_ s: HomeLayoutState) -> [String: Any] {
        var out: [String: Any] = [:]
        out["sections"] = s.sections.map { sec -> [String: Any] in
            var o: [String: Any] = ["id": sec.id, "enabled": sec.enabled]
            if sec.rows != "l" { o["rows"] = sec.rows }
            if sec.rowsSet { o["rowsSet"] = true }
            if sec.half { o["half"] = true }
            if let u = sec.units { o["units"] = u.intValue }
            if let c = parse(sec.configExtrasJson) { o["configExtras"] = c }
            if let e = parse(sec.entryExtrasJson) { o["entryExtras"] = e }
            return o
        }
        if !s.retained.isEmpty {
            out["retained"] = s.retained.map { r -> [String: Any] in
                var o: [String: Any] = ["id": r.id, "enabled": r.enabled]
                if let c = parse(r.configJson) { o["config"] = c }
                if let e = parse(r.extrasJson) { o["extras"] = e }
                return o
            }
        }
        if !s.newSections.isEmpty { out["newSections"] = s.newSections }
        out["widePairs"] = s.widePairs
        if let w = parse(s.retainedWideJson) { out["retainedWide"] = w }
        if let d = parse(s.retainedDocumentJson) { out["retainedDocument"] = d }
        return out
    }

    nonisolated private static func stateOf(_ o: [String: Any]) -> HomeLayoutState {
        let sections = (o["sections"] as! [[String: Any]]).map { s in
            HomeLayoutSectionState(id: s["id"] as! String, enabled: s["enabled"] as! Bool, rows: (s["rows"] as? String) ?? "l",
                                   rowsSet: (s["rowsSet"] as? Bool) ?? false, half: (s["half"] as? Bool) ?? false,
                                   units: (s["units"] as? Int).map { KotlinInt(int: Int32($0)) },
                                   configExtrasJson: text(s["configExtras"]), entryExtrasJson: text(s["entryExtras"]))
        }
        let retained = ((o["retained"] as? [[String: Any]]) ?? []).map { r in
            HomeLayoutRetainedState(id: r["id"] as! String, enabled: r["enabled"] as! Bool, configJson: text(r["config"]),
                                    extrasJson: text(r["extras"]))
        }
        return HomeLayoutState(sections: sections, retained: retained, newSections: (o["newSections"] as? [String]) ?? [],
                               widePairs: (o["widePairs"] as? [[String]]) ?? [], retainedWideJson: text(o["retainedWide"]),
                               retainedDocumentJson: text(o["retainedDocument"]))
    }

    nonisolated(unsafe) private static let states: [String: HomeLayoutState] =
        (layoutGolden["states"] as! [String: [String: Any]]).mapValues(stateOf)

    private static func state(_ v: Any?) -> HomeLayoutState {
        if let name = v as? String { return states[name]! }
        return stateOf(v as! [String: Any])
    }

    private static func layout(_ v: Any?) -> AppHomeLayout { AppHomeLayout(state: state(v)) }

    private func vectors(_ golden: [String: Any], _ section: String) -> [[String: Any]] { golden[section] as! [[String: Any]] }

    // MARK: home-layout.json

    func test_should_answerAsTheGoldenFile_when_readingAndWritingDocuments() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("home-golden-\(UUID().uuidString).json")
        addTeardownBlock { try? FileManager.default.removeItem(at: url) }
        let store = HomeLayoutStore(fileURL: url)
        let vs = vectors(Self.layoutGolden, "codec")
        XCTAssertEqual(vs.count, 33)
        for (i, v) in vs.enumerated() {
            let name = v["name"] as! String, raw = v["raw"] as! String
            let decoded = AppHomeLayoutCodec.decode(raw)
            XCTAssertEqual(decoded == nil, v["document"] is NSNull, "\(name): readable")
            // What the layout read from it writes, byte for byte (an unreadable one reads as the default).
            XCTAssertEqual(AppHomeLayoutCodec.encode(decoded ?? .default), v["layoutEncoded"] as? String, name)
            guard let decoded else {
                XCTAssertFalse(store.applySynced(json: raw, updatedAt: 1, scope: "c\(i)"), name)
                continue
            }
            // A peer's text is kept as it arrived, and saving what it reads as unchanged writes nothing.
            XCTAssertTrue(store.applySynced(json: raw, updatedAt: 1, scope: "c\(i)"), name)
            store.save(store.layout("c\(i)"), scope: "c\(i)")
            if store.isCustomized("c\(i)") { XCTAssertEqual(store.synced("c\(i)")?.json, raw, name) }
            // Saved fresh, the store writes the shared encoding (or clears a default).
            store.save(decoded, scope: "fresh\(i)")
            switch AppHomeLayoutCodec.persist(decoded) {
            case .clear: XCTAssertFalse(store.isCustomized("fresh\(i)"), name)
            case .write(let json):
                XCTAssertEqual(json, v["layoutEncoded"] as? String, name)
                XCTAssertEqual(store.synced("fresh\(i)")?.json, json, name)
            }
        }
        // And all of it read back from the file.
        let reopened = HomeLayoutStore(fileURL: url)
        for (i, v) in vs.enumerated() where !(v["document"] is NSNull) {
            if let json = reopened.synced("fresh\(i)")?.json { XCTAssertEqual(json, v["layoutEncoded"] as? String) }
        }
    }

    func test_should_reconcileAndPersistAsTheGoldenFile_when_readingStoredLayouts() {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("home-golden-\(UUID().uuidString).json")
        addTeardownBlock { try? FileManager.default.removeItem(at: url) }
        let store = HomeLayoutStore(fileURL: url)
        let vs = vectors(Self.layoutGolden, "reconcile")
        XCTAssertEqual(vs.count, 31)
        for (i, v) in vs.enumerated() {
            let name = v["name"] as! String
            let read = Self.text(v["document"]).flatMap(AppHomeLayoutCodec.decode) ?? .default
            expectClose(Self.stateJSON(read.state), v["state"], "\(name): state")
            XCTAssertEqual(read.isDefault, v["isDefault"] as? Bool, name)
            let persist = v["persist"] as! [String: Any]
            store.save(read, scope: "r\(i)")
            switch AppHomeLayoutCodec.persist(read) {
            case .clear:
                XCTAssertEqual(persist["clear"] as? Bool, true, name)
                XCTAssertFalse(store.isCustomized("r\(i)"), name)
            case .write(let json):
                XCTAssertEqual(persist["clear"] as? Bool, false, name)
                expectClose(Self.parse(json), Self.parse(persist["document"] as? String), "\(name): document")
                XCTAssertEqual(store.synced("r\(i)")?.json, json, name)
            }
        }
    }

    /// One `ops` / `editStep` edit, through the Swift wrapper where it has the edit (the single column's), else the facade.
    private func apply(_ v: [String: Any], to s: HomeLayoutState) -> (changed: Bool, state: HomeLayoutState) {
        let layout = AppHomeLayout(state: s)
        let id = v["id"] as? String
        let section = id.flatMap(AppHomeSection.init(rawValue:))
        func wrapped(_ next: AppHomeLayout) -> (Bool, HomeLayoutState) { (next != layout, next.state) }
        func facade(_ r: HomeLayoutResult) -> (Bool, HomeLayoutState) { (r.changed, r.state) }
        switch v["op"] as! String {
        case "withEnabled": return wrapped(layout.withEnabled(section!, v["enabled"] as! Bool))
        case "withEnabledOrder":
            let order = (v["order"] as! [String]).compactMap(AppHomeSection.init(rawValue:))
            let unseen = (v["unseen"] as! [String]).compactMap(AppHomeSection.init(rawValue:))
            guard order.count == (v["order"] as! [String]).count else {
                return facade(core.withEnabledOrder(state: s, order: v["order"] as! [String], unseen: v["unseen"] as! [String]))
            }
            return wrapped(layout.withEnabledOrder(order, unseen: unseen))
        case "moved":
            guard let section else { return facade(core.moved(state: s, id: id!, toEnabledIndex: Int32(v["index"] as! Int))) }
            return wrapped(layout.moved(section, toEnabledIndex: v["index"] as! Int))
        case "withRows":
            guard let section, let rows = AppHomeRowPreset(key: v["rows"] as? String) else {
                return facade(core.withRows(state: s, id: id!, rowsKey: v["rows"] as! String))
            }
            return wrapped(layout.withRows(section, rows))
        case "reset":
            if v["twoColumn"] as? Bool == false { return wrapped(layout.reset()) }
            return facade(core.reset(state: s, twoColumn: true))
        case "withHalf": return facade(core.withHalf(state: s, id: id!))
        case "withFull": return facade(core.withFull(state: s, id: id!))
        case "withBesidePrevious": return facade(core.withBesidePrevious(state: s, id: id!))
        case "withArrangement":
            return facade(core.withArrangement(state: s, order: v["order"] as! [String], pairs: v["pairs"] as! [[String]],
                                               unseen: v["unseen"] as! [String]))
        case "withUnits": return facade(core.withUnits(state: s, id: id!, units: Int32(v["units"] as! Int)))
        case "forTwoColumn": return facade(core.forTwoColumn(state: s, twoColumn: v["twoColumn"] as! Bool))
        case "shownAt": return facade(core.shownAt(state: s, twoColumn: v["twoColumn"] as! Bool))
        case "rememberingShownPairs": return facade(core.rememberingShownPairs(state: s))
        case "withRememberedPairs": return facade(core.withRememberedPairs(state: s))
        case "normalizedSpans": return facade(core.normalizedSpans(state: s))
        default: XCTFail("unknown op \(v["op"]!)"); return (false, s)
        }
    }

    func test_should_editAsTheGoldenFile_when_everyOpRuns() {
        let vs = vectors(Self.layoutGolden, "ops")
        XCTAssertEqual(vs.count, 422)
        for v in vs {
            let name = v["name"] as! String
            let (changed, after) = apply(v, to: Self.state(v["state"]))
            XCTAssertEqual(changed, v["changed"] as? Bool, "\(name): changed")
            if changed { expectClose(Self.stateJSON(after), v["after"], "\(name): after") }
        }
        for v in vectors(Self.layoutGolden, "editStep") {
            let (changed, after) = apply(v, to: Self.state(v["state"]))
            let step = core.editStep(result: HomeLayoutResult(state: after, changed: changed), twoColumn: v["twoColumn"] as! Bool)
            XCTAssertEqual(step.changed, v["changed"] as? Bool, v["name"] as! String)
            if step.changed { expectClose(Self.stateJSON(step.state), v["after"], v["name"] as! String) }
        }
    }

    func test_should_answerPredicatesAsTheGoldenFile_when_askingALayout() {
        for v in vectors(Self.layoutGolden, "predicates") {
            let name = v["name"] as! String
            let s = Self.state(v["state"])
            let layout = AppHomeLayout(state: s)
            XCTAssertEqual(layout.enabledSections.map(\.id), v["enabled"] as? [String], name)
            XCTAssertEqual(layout.hiddenSections.map(\.id), v["hidden"] as? [String], name)
            XCTAssertEqual(layout.isDefault, v["isDefault"] as? Bool, name)
            XCTAssertEqual(layout.isAnyDefault, v["isAnyDefault"] as? Bool, name)
            XCTAssertEqual(core.isWideDefault(state: s), v["isWideDefault"] as? Bool, name)
            XCTAssertEqual(core.isDefaultFor(state: s, twoColumn: true), v["isDefaultForTwoColumn"] as? Bool, name)
            XCTAssertEqual(core.pairs(state: s), v["pairs"] as? [[String]], name)
            for (id, rows) in v["rows"] as! [String: String] {
                XCTAssertEqual(AppHomeSection(rawValue: id).map { layout.rowsOf($0).key }, rows, "\(name): rows \(id)")
            }
            for (id, partner) in v["partners"] as! [String: Any] {
                XCTAssertEqual(core.partnerOf(state: s, id: id), Self.opt(partner) as String?, "\(name): partner \(id)")
            }
            for (id, units) in v["units"] as! [String: Int] {
                XCTAssertEqual(Int(core.unitsOf(state: s, id: id)), units, "\(name): units \(id)")
            }
            let persist = v["persist"] as! [String: Any]
            switch AppHomeLayoutCodec.persist(layout) {
            case .clear: XCTAssertEqual(persist["clear"] as? Bool, true, name)
            case .write(let json): expectClose(Self.parse(json), Self.parse(persist["document"] as? String), "\(name): persist")
            }
        }
        for v in vectors(Self.layoutGolden, "sameSections") {
            XCTAssertEqual(Self.layout(v["a"]).sameSections(as: Self.layout(v["b"])), v["same"] as? Bool, v["name"] as! String)
        }
    }

    func test_should_pairAndCountAsTheGoldenFile_when_askingTheRules() {
        for v in vectors(Self.layoutGolden, "widePairsAt") {
            XCTAssertEqual(core.widePairsAt(order: v["order"] as! [String], memory: v["memory"] as! [[String]]), v["pairs"] as? [[String]])
        }
        for v in vectors(Self.layoutGolden, "samePairs") {
            XCTAssertEqual(core.samePairs(a: v["a"] as! [[String]], b: v["b"] as! [[String]]), v["same"] as? Bool)
        }
        for v in vectors(Self.layoutGolden, "spanPairs") {
            let halves = (v["halves"] as! [Bool]).map { KotlinBoolean(bool: $0) }
            XCTAssertEqual(core.spanPairs(order: v["order"] as! [String], halves: halves), v["pairs"] as? [[String]])
        }
        for v in vectors(Self.layoutGolden, "feedRows") {
            XCTAssertEqual(core.feedRows(displayed: v["displayed"] as! [String], pairs: v["pairs"] as! [[String]],
                                         twoColumn: v["twoColumn"] as! Bool), v["rows"] as? [[String]])
        }
        for v in vectors(Self.layoutGolden, "rowUnits") {
            XCTAssertEqual(Int(core.rowUnits(state: Self.state(v["state"]), row: v["row"] as! [String])), v["units"] as? Int)
        }
        for v in vectors(Self.layoutGolden, "canPair") {
            let left = v["left"] as! String, right: String? = Self.opt(v["right"])
            if let right { XCTAssertEqual(core.canPair(left: left, right: right), v["canPair"] as? Bool) }
            XCTAssertEqual(core.rowUnitRange(left: left, right: right).map(\.intValue), v["rowUnitRange"] as? [Int])
        }
        for v in vectors(Self.layoutGolden, "unitsPreset") {
            XCTAssertEqual(core.unitsPreset(units: Int32(v["units"] as! Int)), v["preset"] as? String)
            // iOS's own presets carry the same keys.
            XCTAssertNotNil(AppHomeRowPreset(key: v["preset"] as? String))
        }
        for v in vectors(Self.layoutGolden, "draftAtWidth") {
            let s = core.draftAtWidth(draft: Self.state(v["draft"]), edited: v["edited"] as! Bool, base: Self.state(v["base"]),
                                      twoColumn: v["twoColumn"] as! Bool)
            expectClose(Self.stateJSON(s), v["state"], v["name"] as! String)
        }
        for v in vectors(Self.layoutGolden, "echoes") {
            XCTAssertEqual(core.echoes(stored: Self.state(v["stored"]), shown: Self.state(v["shown"])), v["echoes"] as? Bool)
        }
        // The catalog iOS draws is the shared one, in its order.
        let catalog = (Self.layoutGolden["constants"] as! [String: Any])["catalog"] as! [[String: Any]]
        XCTAssertEqual(AppHomeSection.allCases.map(\.id), catalog.map { $0["id"] as! String })
        for c in catalog {
            let s = AppHomeSection(rawValue: c["id"] as! String)!
            XCTAssertEqual(s.appendEnabled, c["appendEnabled"] as? Bool, s.id)
            XCTAssertEqual(s.supportsRows, c["supportsRows"] as? Bool, s.id)
            XCTAssertEqual(s.takesPresets, c["takesPresets"] as? Bool, s.id)
        }
        XCTAssertEqual(HomeEditController.undoMax, (Self.layoutGolden["constants"] as! [String: Any])["undoMax"] as? Int)
    }

    // MARK: home-drag.json timelines, through HomeDragController

    /// The timelines' fixed row model (HomeDragTimelines.kt): rows [rowHeight] tall, [gap] apart, the first at
    /// [top] minus the scroll; a scroll consumes what it asks, held between 0 and [scrollMax].
    private final class FakeHost: HomeDragHost {
        let rowHeight, gap, top, width, areaTop, areaBottom, scrollMax: CGFloat
        var scrolled: CGFloat = 0
        var asked: [CGFloat] = []
        let order: [AppHomeSection]
        var isList: Bool { false }

        init(_ v: [String: Any]) {
            func f(_ k: String) -> CGFloat { CGFloat((v[k] as! NSNumber).doubleValue) }
            rowHeight = f("rowHeight"); gap = f("gap"); top = f("top"); width = f("contentWidth")
            areaTop = f("areaTop"); areaBottom = f("areaBottom"); scrollMax = f("scrollMax")
            order = (v["order"] as! [String]).compactMap(AppHomeSection.init(rawValue:))
        }

        func bands(for order: [AppHomeSection]) -> [(section: AppHomeSection, top: CGFloat, bottom: CGFloat)] {
            var y = top - scrolled
            return order.map { s in
                defer { y += rowHeight + gap }
                return (s, y, y + rowHeight)
            }
        }

        var contentLeft: CGFloat { 0 }
        var contentWidth: CGFloat { width }
        var scrollArea: (top: CGFloat, bottom: CGFloat) { (areaTop, areaBottom) }
        var viewportTop: CGFloat { scrolled }

        func scrollBy(_ dy: CGFloat) -> CGFloat {
            asked.append(dy)
            let consumed = min(max(dy, -scrolled), scrollMax - scrolled)
            scrolled += consumed
            return consumed
        }
    }

    private static func ids(_ a: Any?) -> [String]? { (opt(a) as [String: Any]?)?["ids"] as? [String] }

    func test_should_dragAsTheGoldenTimelines_when_framesRun() {
        let vs = vectors(Self.dragGolden, "timelines")
        XCTAssertEqual(vs.count, 19)
        for v in vs {
            let name = v["name"] as! String
            let host = FakeHost(v)
            let drag = HomeDragController(density: CGFloat((v["density"] as! NSNumber).doubleValue))
            drag.schedulesFrames = false
            var written: [String]?
            drag.commit = { order in written = order.map(\.id); return true }
            let dragged = AppHomeSection(rawValue: v["dragged"] as! String)!
            let finger = (v["finger"] as! [NSNumber]).map { CGFloat($0.doubleValue) }
            let twoColumn = v["twoColumn"] as! Bool
            let pairs = (v["pairs"] as! [[String]]).map { (AppHomeSection(rawValue: $0[0])!, AppHomeSection(rawValue: $0[1])!) }
            drag.setFingerX(finger[0])
            drag.startDrag(dragged, host: host, fingerY: finger[1], order: host.order, pairs: pairs, twoColumn: twoColumn)
            let started = v["started"] as! [String: Any]
            expectClose([Double(drag.grab.x), Double(drag.grab.y)], started["grab"], "\(name): grab")
            let frames = v["frames"] as! [[String: Any]]
            let decisions = v["decisions"] as! [[String: Any]]
            for (i, f) in frames.enumerated() {
                if let m = f["move"] as? [Any] {
                    drag.setFingerX(m[0] is String ? .nan : CGFloat((m[0] as! NSNumber).doubleValue))
                    drag.move(y: CGFloat((m[1] as! NSNumber).doubleValue))
                }
                let result = drag.frame(dt: CGFloat((f["dt"] as! NSNumber).doubleValue), laidOut: (f["laidOut"] as? Bool) ?? true)
                let expected = decisions[i]
                switch expected["kind"] as! String {
                case "Step":
                    let step = result as? HomeDragFrameStep
                    XCTAssertNotNil(step, "\(name): frame \(i) steps")
                    XCTAssertEqual(step.map { $0.arrangement.ids.map(\.id) }, Self.ids(expected["arrangement"]), "\(name): frame \(i)")
                case "Scroll":
                    let scroll = result as? HomeDragFrameScroll
                    XCTAssertNotNil(scroll, "\(name): frame \(i) scrolls")
                    expectClose(scroll.map { Double($0.px) }, expected["px"], "\(name): frame \(i) px", tol: 1e-3)
                default:
                    XCTAssertTrue(result is HomeDragFrameNone, "\(name): frame \(i) does nothing, was \(String(describing: result))")
                }
            }
            let end = v["end"] as! [String: Any]
            switch end["kind"] as! String {
            case "release":
                if let m = end["move"] as? [NSNumber] {
                    drag.setFingerX(CGFloat(m[0].doubleValue))
                    drag.move(y: CGFloat(m[1].doubleValue))
                }
                drag.release(cancelled: end["cancelled"] as! Bool)
            case "exit": drag.exit(HomeEditExitReason.entries.first { $0.name == end["reason"] as? String }!)
            default: drag.cancel()
            }
            let result = v["result"] as! [String: Any]
            XCTAssertEqual(written, Self.ids(result["write"]), "\(name): write")
            expectClose(Double(host.scrolled), result["scrolled"], "\(name): scrolled", tol: 1e-3)
            XCTAssertNil(drag.dragging, name)
            XCTAssertNil(drag.live, name)
        }
    }

    // MARK: home-edit-gestures.json, through the driver

    private static func point(_ o: Any?) -> CGPoint {
        let a = o as! [NSNumber]
        return CGPoint(x: a[0].doubleValue, y: a[1].doubleValue)
    }

    private static func hit(_ o: [String: Any]) -> HomeEditHit {
        func section(_ k: String) -> YoinShared.HomeSection { YoinShared.HomeSection.entries.first { $0.id == o[k] as? String }! }
        func size() -> HomeSize {
            guard let s = o["size"] as? [NSNumber] else { return HomeSize(width: 0, height: 0) }
            return HomeSize(width: s[0].floatValue, height: s[1].floatValue)
        }
        func local() -> HomePoint { let p = point(o["local"]); return HomePoint(x: Float(p.x), y: Float(p.y)) }
        switch o["kind"] as! String {
        case "Handle": return HomeEditHitHandle(section: section("section"), local: local(), size: size())
        case "Block": return HomeEditHitBlock(section: section("section"), local: local(), size: size())
        case "Blank": return HomeEditHitBlank(nearest: (opt(o["nearest"]) as String?).map { _ in section("nearest") })
        default: return HomeEditHitExcluded.shared
        }
    }

    private static func event(_ o: [String: Any], _ machine: HomeEditGestureMachine) -> HomeEditGestureEvent {
        let t = (o["t"] as? NSNumber)?.int64Value ?? 0
        switch o["e"] as! String {
        case "down":
            let p = point(o["at"])
            return HomeEditGestureEventDown(hit: hit(o["hit"] as! [String: Any]), position: HomePoint(x: Float(p.x), y: Float(p.y)),
                                            editing: o["editing"] as? Bool ?? false, flingInProgress: o["fling"] as? Bool ?? false,
                                            t: t, secondaryClick: o["secondary"] as? Bool ?? false)
        case "move":
            let p = point(o["at"])
            return HomeEditGestureEventMove(position: HomePoint(x: Float(p.x), y: Float(p.y)), t: t)
        case "up": return HomeEditGestureEventUp(t: t)
        case "cancel": return HomeEditGestureEventCancel(t: t)
        case "timer":
            let kind = o["kind"] as! String
            let timer = kind == "next" ? machine.pendingTimer!.kind : HomeEditTimer.entries.first { $0.name == kind }!
            return HomeEditGestureEventTimer(kind: timer, flinging: o["flinging"] as? Bool ?? false)
        case "editEnded": return HomeEditGestureEventEditEnded.shared
        case "carryEnded": return HomeEditGestureEventCarryEnded.shared
        case "other": return HomeEditGestureEventOtherPointer.shared
        case "pinchDown": return HomeEditGestureEventPinchDown(span: (o["span"] as! NSNumber).floatValue)
        case "pinchMove": return HomeEditGestureEventPinchMove(span: (o["span"] as! NSNumber).floatValue)
        default: return HomeEditGestureEventPinchUp.shared
        }
    }

    /// An action as the golden file writes it (HomeEditGesturesGolden.actionJson).
    static func actionJSON(_ a: HomeEditGestureAction) -> [String: Any] {
        func pt(_ p: HomePoint) -> [Double] { [Double(p.x), Double(p.y)] }
        let names: [((HomeEditGestureAction) -> Bool, String)] = [
            ({ $0 is HomeEditGestureActionAbandon }, "Abandon"), ({ $0 is HomeEditGestureActionStartCharge }, "StartCharge"),
            ({ $0 is HomeEditGestureActionReleaseCharge }, "ReleaseCharge"), ({ $0 is HomeEditGestureActionLongPressHaptic }, "LongPressHaptic"),
            ({ $0 is HomeEditGestureActionDragStartHaptic }, "DragStartHaptic"), ({ $0 is HomeEditGestureActionEnter }, "Enter"),
            ({ $0 is HomeEditGestureActionLatchPlate }, "LatchPlate"), ({ $0 is HomeEditGestureActionLift }, "Lift"),
            ({ $0 is HomeEditGestureActionDropLift }, "DropLift"), ({ $0 is HomeEditGestureActionOpenPlaceholders }, "OpenPlaceholders"),
            ({ $0 is HomeEditGestureActionStartCarry }, "StartCarry"), ({ $0 is HomeEditGestureActionCarryMove }, "CarryMove"),
            ({ $0 is HomeEditGestureActionCarryRelease }, "CarryRelease"), ({ $0 is HomeEditGestureActionPinchStart }, "PinchStart"),
            ({ $0 is HomeEditGestureActionPinchBy }, "PinchBy"), ({ $0 is HomeEditGestureActionPinchEnd }, "PinchEnd"),
            ({ $0 is HomeEditGestureActionBlockTap }, "BlockTap"), ({ $0 is HomeEditGestureActionExitBlank }, "ExitBlank"),
            ({ $0 is HomeEditGestureActionTouch }, "Touch"), ({ $0 is HomeEditGestureActionPointerDown }, "PointerDown"),
            ({ $0 is HomeEditGestureActionConsumeRest }, "ConsumeRest"), ({ $0 is HomeEditGestureActionConsumeOther }, "ConsumeOther"),
        ]
        var o: [String: Any] = ["a": names.first { $0.0(a) }?.1 ?? "?"]
        switch a {
        case let x as HomeEditGestureActionStartCharge: o["section"] = x.section.id; o["local"] = pt(x.local)
        case let x as HomeEditGestureActionEnter: o["origin"] = x.origin?.id ?? NSNull(); o["lifted"] = x.lifted
        case let x as HomeEditGestureActionLatchPlate:
            o["section"] = x.section.id; o["local"] = pt(x.local); o["size"] = [Double(x.size.width), Double(x.size.height)]
        case let x as HomeEditGestureActionLift: o["section"] = x.section.id; o["local"] = pt(x.local)
        case let x as HomeEditGestureActionDropLift: o["kick"] = Double(x.kick)
        case let x as HomeEditGestureActionStartCarry: o["y"] = Double(x.y); o["t"] = x.t
        case let x as HomeEditGestureActionCarryMove: o["y"] = Double(x.y); o["t"] = x.t
        case let x as HomeEditGestureActionCarryRelease: o["cancelled"] = x.cancelled; o["t"] = x.t
        case let x as HomeEditGestureActionPinchBy: o["scale"] = Double(x.scale)
        case let x as HomeEditGestureActionBlockTap: o["section"] = x.section.id; o["local"] = x.local.map(pt) ?? NSNull()
        case let x as HomeEditGestureActionPointerDown: o["down"] = x.down
        default: break
        }
        return o
    }

    func test_should_answerAsTheGoldenMachine_when_eventsArrive() {
        let vs = vectors(Self.gestureGolden, "machine")
        XCTAssertEqual(vs.count, 42)
        for v in vs {
            let name = v["name"] as! String
            let machine = HomeEditGestureMachine(longPressMs: (v["longPressMs"] as! NSNumber).int64Value,
                                                 slopPx: (v["slopPx"] as! NSNumber).floatValue)
            for (i, (e, step)) in zip(v["events"] as! [[String: Any]], v["steps"] as! [[String: Any]]).enumerated() {
                let actions = machine.handle(event: Self.event(e, machine)).map(Self.actionJSON)
                expectClose(actions, step["actions"], "\(name): step \(i)")
                XCTAssertEqual(machine.state.name, step["state"] as? String, "\(name): step \(i) state")
            }
        }
    }

    func test_should_answerAsTheGoldenTimeline_when_theDriverRunsOnTheEventsClock() {
        let vs = vectors(Self.gestureGolden, "timeline")
        XCTAssertEqual(vs.count, 8)
        for v in vs {
            let name = v["name"] as! String
            var stepped: [HomeEditGestureAction] = []
            let flinging = v["flinging"] as? Bool ?? false
            let driver = HomeEditGestureDriver(longPressMs: (v["longPressMs"] as! NSNumber).int64Value,
                                               slop: CGFloat((v["slopPx"] as! NSNumber).doubleValue),
                                               flinging: { flinging }) { stepped += $0 }
            driver.schedulesTimers = false
            driver.timeline.start(t: (v["start"] as! NSNumber).int64Value)
            for (i, (op, result)) in zip(v["ops"] as! [[String: Any]], v["results"] as! [[String: Any]]).enumerated() {
                stepped = []
                switch op["op"] as! String {
                case "step": stepped = driver.machine.handle(event: Self.event(op["event"] as! [String: Any], driver.machine))
                case "sample":
                    let e = op["event"] as! [String: Any]
                    let t = (e["t"] as! NSNumber).int64Value
                    switch e["e"] as! String {
                    case "move": driver.move(to: Self.point(e["at"]), t: t)
                    case "up": driver.up(t: t)
                    default: driver.cancel(t: t)
                    }
                case "advance": driver.timeline.advanceTo(t: (op["t"] as! NSNumber).int64Value)
                case "timeout": driver.fire()
                default:
                    XCTAssertEqual(driver.timeline.graceAfter(wallMs: (op["wall"] as! NSNumber).int64Value),
                                   (result["grace"] as! NSNumber).int64Value, "\(name): op \(i) grace")
                }
                expectClose(stepped.map(Self.actionJSON), result["actions"], "\(name): op \(i)")
                XCTAssertEqual(driver.state.name, result["state"] as? String, "\(name): op \(i) state")
                XCTAssertEqual(driver.timeline.now, (result["now"] as! NSNumber).int64Value, "\(name): op \(i) now")
                XCTAssertEqual(driver.timeline.untilDue()?.int64Value, (Self.opt(result["untilDue"]) as NSNumber?)?.int64Value,
                               "\(name): op \(i) untilDue")
            }
        }
    }
}
