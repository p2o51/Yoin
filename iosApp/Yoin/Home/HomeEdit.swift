import SwiftUI
import UIKit
import YoinShared

/// Home edit mode, in place (Android ui/home/edit; design.md §主页 › 就地编辑): whole sections are the
/// unit. Long-press a section (or the footer's Edit Home) and Home becomes editable where it is: plates
/// come up under the sections, each title row gets Hide and a drag handle, the sections sway and settle
/// after 6 s untouched. Hold a block (or press its handle) and it lifts and follows the finger in place
/// while the others make way (HomeDragController, on :shared's HomeDragCore), the feed scrolling at its
/// ends; a pinch folds the feed into a compact list of strips (HomeEditList). Hidden sections wait in the
/// tray under the last one. Every change is saved at once for this account (HomeLayoutStore); Undo walks
/// them back; Done, a blank tap or ⌘[ leaves.
///
/// One progress P (0 feed … 1 editing) on the stage-settle spring drives the header's title swap, the
/// plates, the badges' ripple and the tray.
@MainActor
@Observable
final class HomeEditController {
    /// 0 = the feed, 1 = editing (the stage-settle spring).
    private(set) var progress: CGFloat = 0
    private(set) var isEditing = false
    /// The layout being edited (the saved one while not editing).
    private(set) var layout: HomeLayout = .default
    private(set) var undoStack: [HomeLayout] = []
    /// The sway's envelope (0 still … 1 swaying); it settles after 6 s untouched.
    private(set) var sway: CGFloat = 0
    /// The block a normal-mode press is charging (half the long press in): it shrinks a little.
    private(set) var charged: HomeSection?
    /// Asks the feed to scroll to the tray (the header's Add).
    private(set) var trayRequest = 0
    /// When a long-press entered edit: the press's own release must not tap the card under it.
    @ObservationIgnored private(set) var enteredAt: Date = .distantPast
    #if DEBUG
    /// Screenshots: Lyric Cards' dots held open at this count (`-homeLyricCountShot 3`).
    var lyricCountShot: Int?
    #endif

    /// The lift, the in-place drag, the glide (HomeDragController).
    let drag = HomeDragController()
    /// The pinched compact list (HomeEditList).
    let list = HomeEditListMode()
    /// Two columns' ⌟: the span and unit drags (HomeGridResize).
    let resize = HomeGridResize()

    @ObservationIgnored private(set) var scope = ""
    /// Homes on screen with this session (a rebuild shows the new one before the old one goes).
    @ObservationIgnored var homesShown = 0
    @ObservationIgnored private let store: HomeLayoutStore
    @ObservationIgnored private let stage = SpringRunner()
    @ObservationIgnored private let swaySpring = SpringRunner()
    @ObservationIgnored private var idleTask: Task<Void, Never>?
    @ObservationIgnored private var kicks: [HomeSection: HomeEditKick] = [:]
    /// The recognizers on the feed and on the list (a drag let go from outside tells them).
    @ObservationIgnored weak var feedRecognizer: HomeEditTouchRecognizer?
    @ObservationIgnored weak var listRecognizer: HomeEditTouchRecognizer?

    nonisolated static let undoMax = Int(YoinHomeLayout.shared.undoMax)
    /// The feed's coordinate space (the sections report their frames in it).
    nonisolated static let feedSpace = "homeFeed"
    /// The pinched list's coordinate space (its strips report their frames in it).
    nonisolated static let listSpace = "homeEditList"
    /// How long a finger holds a section's body before it lifts: out of edit mode the long press that
    /// enters it (T); in edit mode the body hold, max(150 ms, T/2) (shared HomeEditGestureMachine).
    nonisolated static func holdDuration(editing: Bool) -> TimeInterval {
        let t = Double(HomeEditGestureDriver.longPressMs) / 1000
        return editing ? max(0.15, t / 2) : t
    }
    /// IdleSettle: this long without a touch and the sway settles.
    nonisolated static let idleSeconds: Double = 6
    /// An exclusion under a key with this prefix holds only out of edit mode.
    nonisolated static let normalOnly = "normal."

    init(store: HomeLayoutStore = .shared) {
        self.store = store
        drag.commit = { [weak self] order, pairs in self?.commitDrop(order, pairs: pairs) ?? false }
        drag.kick = { [weak self] section, a in self?.kick(section, a) }
        drag.letGo = { [weak self] in
            self?.feedRecognizer?.carryEnded()
            self?.listRecognizer?.carryEnded()
        }
        list.canChange = { [weak self] in self.map { $0.isEditing && !$0.drag.isCarrying && $0.drag.liftSection == nil } ?? false }
        resize.commitSpan = { [weak self] section, write in self?.commitSpan(section, write) }
        resize.commitUnits = { [weak self] section, units in self?.setUnits(section, units) }
    }

    /// The account changed (or Home appeared): its saved layout, unless mid-edit.
    func load(scope: String) {
        guard scope != self.scope || (!isEditing && layout != store.layout(scope)) else { return }
        if scope != self.scope, isEditing { snapExit() }
        self.scope = scope
        layout = store.layout(scope)
        undoStack = []
    }

    // MARK: Two columns (parity p4 B2)

    /// The feed lays out in two columns (Home sets it from its container: `HomeDensity.wide`).
    private(set) var twoColumn = false
    /// The layout edit mode started from (the stored one): an untouched draft follows a width change from it.
    @ObservationIgnored private var base: HomeLayout = .default

    /// The feed's width crossed the two-column tier. A drag still on rolls back first (its geometry is gone); in
    /// edit mode the draft follows (shared `homeDraftAtWidth`): untouched it is what entering at this width would
    /// have made it, edited it keeps its order and two columns pair it from its memory.
    func setTwoColumn(_ on: Bool) {
        guard on != twoColumn else { return }
        drag.cancel()
        resize.snap()
        twoColumn = on
        guard isEditing else { return }
        let next = layout.atWidth(edited: !undoStack.isEmpty, base: base, twoColumn: on)
        if next != layout { layout = next }
    }

    /// What the feed shows: the draft while editing; otherwise the saved layout at this width (in two columns a
    /// never-customized one is the large-screen default).
    var shown: HomeLayout {
        let l = layout, e = isEditing, t = twoColumn
        if let c = shownCache, c.editing == e, c.twoColumn == t, c.layout == l { return c.shown }
        let v = e ? l : l.forTwoColumn(t)
        shownCache = (l, e, t, v)
        return v
    }
    @ObservationIgnored private var shownCache: (layout: HomeLayout, editing: Bool, twoColumn: Bool, shown: HomeLayout)?

    /// The pairs the feed shows side by side now (the drag's live ones while one runs in the feed); none in one column.
    var pairs: [(left: HomeSection, right: HomeSection)] {
        guard twoColumn else { return [] }
        if drag.live != nil, !drag.inList { return drag.livePairs }
        return shown.pairs
    }

    /// The sections in the order the feed draws them right now (the drag's live order while one runs in the feed).
    var order: [HomeSection] { (drag.inList ? nil : drag.live) ?? shown.shownSections }

    /// The sections in the order the list draws them right now.
    var listOrder: [HomeSection] { (drag.inList ? drag.live : nil) ?? shown.shownSections }

    // MARK: Enter / leave

    func enter() {
        guard !isEditing else { return }
        enteredAt = Date()
        base = layout
        // Two columns edit what they show: a never-customized layout starts as the large-screen default.
        layout = layout.forTwoColumn(twoColumn)
        isEditing = true
        undoStack = []
        run(stage, from: progress, to: 1, spring: Self.stageSpring) { [weak self] in self?.progress = $0 }
        touched()
    }

    /// Leaves edit mode. A drag still on ends first: Done and the app's own exits keep its drop, Back and a
    /// blank tap roll it back (shared `dragExitKeepsDrop`).
    func exit(_ reason: HomeEditExitReason = .done) {
        guard isEditing else { return }
        if drag.isCarrying { drag.exit(reason) } else { drag.dropLift(0) }
        list.close()
        resize.snap()
        isEditing = false
        charged = nil
        feedRecognizer?.editEnded()
        listRecognizer?.editEnded()
        if reason == .done { UIImpactFeedbackGenerator(style: .medium).impactOccurred() }
        // The saved layout again (either default was written as "never customized": it shows per width).
        let saved = store.layout(scope)
        if saved != layout { layout = saved }
        idleTask?.cancel()
        run(swaySpring, from: sway, to: 0, spring: .yoin(Tokens.motion.expressiveDefaultSpatial)) { [weak self] in self?.sway = $0 }
        run(stage, from: progress, to: 0, spring: Self.stageSpring) { [weak self] in self?.progress = $0 }
    }

    /// Gone at once (Home left the screen, the account changed): a drag still on rolls back.
    func snapExit() {
        drag.snapToRest()
        list.snapOff()
        resize.snap()
        if isEditing, !scope.isEmpty { layout = store.layout(scope) }
        isEditing = false
        [stage, swaySpring].forEach { $0.stop() }
        idleTask?.cancel()
        progress = 0; sway = 0; charged = nil
    }

    /// The feed's geometry changed under a drag (a rotation, a width or size-class change) or the app went
    /// to the background: the drag rolls back to before the lift, nothing written.
    func cancelDrag() { drag.cancel() }

    /// A press under the long-press that entered edit lands on a card: drop that tap.
    var swallowsTap: Bool { isEditing || Date().timeIntervalSince(enteredAt) < 0.8 }

    /// A tap on a Jump Back In card while editing (not the release of the hold that entered edit): the
    /// "not now" menu's way in (Android's edit-mode card tap).
    var acceptsCardTap: Bool { isEditing && drag.liftSection == nil && Date().timeIntervalSince(enteredAt) >= 0.8 }

    // MARK: Row presets (D1 resize)

    /// A handle drag's preset while the finger is down; written (one Undo step) on release.
    private(set) var rowsPreview: [HomeSection: HomeRowPreset] = [:]

    /// [s]'s row preset as drawn now: the drag's, else the layout's.
    func rowsOf(_ s: HomeSection) -> HomeRowPreset { rowsPreview[s] ?? shown.rowsOf(s) }

    /// The handle crossed a stop: the section recomposes at [rows] under the finger.
    func previewRows(_ s: HomeSection, _ rows: HomeRowPreset) {
        guard rowsPreview[s] != rows else { return }
        withAnimation(Yoin.spatial) { rowsPreview[s] = rows }
        UISelectionFeedbackGenerator().selectionChanged()
        touched()
    }

    /// The finger lifted: the preset is written, with Undo.
    func endRows(_ s: HomeSection) {
        guard let rows = rowsPreview[s] else { return }
        commit(layout.withRows(s, rows))
        rowsPreview[s] = nil
    }

    /// A step by keyboard / VoiceOver: the preset written at once.
    func setRows(_ s: HomeSection, _ rows: HomeRowPreset) { commit(layout.withRows(s, rows)) }

    /// Lyric Cards' dots crossing to another count: the resize's tick (Android lyricCountTick).
    func lyricCountTick() {
        UISelectionFeedbackGenerator().selectionChanged()
        touched()
    }

    /// Lyric Cards at [count] cards, dropped from its dots (Android setLyricCount): one Undo step,
    /// a confirming tap when it changed.
    func setLyricCount(_ count: Int) {
        let next = layout.withRows(.lyricHighlight, .lyricCards(count))
        guard !next.sameSections(as: layout) else { return }
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        commit(next)
    }

    // MARK: Changes (each one saved, each one an Undo step; a drag's drop waits for nothing else)

    func hide(_ s: HomeSection) { commit(layout.withEnabled(s, false)) }
    func show(_ s: HomeSection) { commit(layout.withEnabled(s, true)) }
    func move(_ s: HomeSection, to index: Int) { commit(layout.movedShown(s, to: index)) }

    /// The header's Add: the feed scrolls to the tray.
    func showTray() {
        trayRequest += 1
        touched()
    }

    /// Back to this width's default (two columns: the large-screen one), written by the persist rule (either
    /// default with nothing retained clears the account back to "never customized").
    func reset() {
        guard !drag.isCarrying else { return }
        let next = layout.reset(twoColumn: twoColumn)
        guard !next.sameSections(as: layout) else { return }
        push()
        withAnimation(Yoin.spatial) { layout = next }
        store.save(next, scope: scope)
        touched()
    }

    /// Reset has nothing to do: the draft is this width's default.
    var isAtDefault: Bool { layout.isDefault(twoColumn: twoColumn) }

    // MARK: Two-column edits (the span handle, the unit handle, VoiceOver)

    /// [s] (full width) the left half of a pair with the row after it.
    func setHalf(_ s: HomeSection) { commit(layout.withHalf(s)) }
    /// [s] (one of a pair) full width; its partner takes its own row after it.
    func setFull(_ s: HomeSection) { commit(layout.withFull(s)) }
    /// [s] (full width) the right half beside the full section before it.
    func placeBesidePrevious(_ s: HomeSection) { commit(layout.withBesidePrevious(s)) }
    /// [s]'s row (and its partner) at [units] grid units.
    func setUnits(_ s: HomeSection, _ units: Int) { commit(layout.withUnits(s, units)) }

    /// A span drag or key's write: half (pulling the row after it up beside it) or full.
    func commitSpan(_ s: HomeSection, _ write: HomeSpanCommit) {
        switch write {
        case .tohalf: setHalf(s)
        case .tofull: setFull(s)
        default: break
        }
    }

    /// VoiceOver's "Place beside previous" applies to [s] now.
    func canPlaceBesidePrevious(_ s: HomeSection) -> Bool { twoColumn && layout.withBesidePrevious(s) != layout }
    /// [s] is half a pair in the draft.
    func isHalf(_ s: HomeSection) -> Bool { twoColumn && shown.partner(of: s) != nil }

    func undo() {
        guard !drag.isCarrying, let prev = undoStack.popLast() else { return }
        withAnimation(Yoin.spatial) { layout = prev }
        store.save(prev, scope: scope)
        touched()
    }

    // One edit's step (shared `homeEditStep`): nothing changed is no step; two columns also make the memory
    // what they show, so a pair formed or split here is remembered for the next time the feed is wide.
    private func commit(_ next: HomeLayout, animated: Bool = true) {
        guard !drag.isCarrying, !next.sameSections(as: layout) else { return }
        apply(twoColumn ? next.rememberingShownPairs() : next, animated: animated)
    }

    private func apply(_ next: HomeLayout, animated: Bool) {
        push()
        if animated { withAnimation(Yoin.spatial) { layout = next } } else { layout = next }
        store.save(next, scope: scope)
        touched()
    }

    /// A drag's drop: the order it left (every block the feed shows) and, in two columns, exactly the pairs it
    /// left side by side (shared `withArrangement`), written once with a confirming tap.
    private func commitDrop(_ order: [HomeSection], pairs: [(HomeSection, HomeSection)]) -> Bool {
        let unseen = layout.enabledSections.filter { !order.contains($0) }
        let next = twoColumn && !drag.inList
            ? layout.withArrangement(order, pairs: pairs, unseen: unseen)
            : layout.withEnabledOrder(order, unseen: unseen)
        guard !next.sameSections(as: layout) else { return false }
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        apply(twoColumn ? next.rememberingShownPairs() : next, animated: false)
        return true
    }

    private func push() {
        undoStack.append(layout)
        if undoStack.count > Self.undoMax { undoStack.removeFirst(undoStack.count - Self.undoMax) }
    }

    // MARK: Sway (IdleSettle) and kicks

    /// A finger is down on the page: the sway stays up until it lifts.
    @ObservationIgnored private var fingerDown = false

    /// Any touch while editing revives the sway; 6 s without one settles it.
    func touched() {
        guard isEditing else { return }
        if sway < 1 { run(swaySpring, from: sway, to: 1, spring: .yoin(Tokens.motion.expressiveFastSpatial)) { [weak self] in self?.sway = $0 } }
        idleTask?.cancel()
        idleTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(Self.idleSeconds))
            guard let self, !Task.isCancelled, !self.drag.isCarrying, !self.fingerDown else { return }
            self.run(self.swaySpring, from: self.sway, to: 0, spring: .yoin(Tokens.motion.expressiveDefaultSpatial)) { [weak self] in self?.sway = $0 }
        }
    }

    /// [section]'s kick (one observable per block, so a kick redraws that block alone).
    func kickState(_ section: HomeSection) -> HomeEditKick {
        if let k = kicks[section] { return k }
        let k = HomeEditKick(sign: Self.swaySign(section))
        kicks[section] = k
        return k
    }

    /// Kicks [section] by [a] (first peak = a), along a kick still ringing (Android impulse).
    func kick(_ section: HomeSection, _ a: CGFloat) {
        guard !UIAccessibility.isReduceMotionEnabled else { return }
        kickState(section).kick(a)
    }

    /// Port sheet §3: a card-sized rotation, detuned per section, ~2.4 Hz.
    static func swayAngle(_ section: HomeSection, time: TimeInterval, envelope: CGFloat) -> Double {
        guard envelope > 0.0005 else { return 0 }
        let hash = sectionHash(section)
        let detune = 1 + 0.07 * (Double(hash % 200) / 100 - 1)
        let phase = Double(hash % 628) / 100
        return sin(2 * .pi * 2.4 * detune * time + phase) * 0.35 * Double(envelope)
    }

    private static func sectionHash(_ section: HomeSection) -> Int {
        section.id.unicodeScalars.reduce(0) { ($0 &* 31 &+ Int($1.value)) & 0xFFFF }
    }

    /// A block's kick direction: its own, alternating by its id (Android swaySign).
    static func swaySign(_ section: HomeSection) -> CGFloat { sectionHash(section) % 2 == 0 ? 1 : -1 }

    // MARK: Geometry (feed space), hits and the drag's rows

    /// Each shown section's block, as laid out (not as drawn), in the feed's space; the list's strips in the list's.
    @ObservationIgnored private var frames: [HomeSection: CGRect] = [:]
    @ObservationIgnored private var listFrames: [HomeSection: CGRect] = [:]
    /// Each block's drag handle (edit mode), in the feed's space; the list's in the list's.
    @ObservationIgnored private var handles: [HomeSection: CGRect] = [:]
    @ObservationIgnored private var listHandles: [HomeSection: CGRect] = [:]
    /// Controls a press leaves alone (header actions, Hide, the resize handles, the tray, the footer).
    @ObservationIgnored private var exclusions: [String: CGRect] = [:]
    @ObservationIgnored private var listExclusions: [String: CGRect] = [:]
    // The rows' top and gap, read at rest: a drag lays a new order out from them at once.
    @ObservationIgnored private var restTop: [Bool: CGFloat] = [:]
    @ObservationIgnored private var restGap: [Bool: CGFloat] = [:]

    /// The section's block, as laid out (nil: gone).
    func report(_ s: HomeSection, frame: CGRect?, inList: Bool = false, owner: UUID? = nil) {
        guard owns("block.\(s.id).\(inList)", frame, owner) else { return }
        if inList { listFrames[s] = frame } else { frames[s] = frame }
    }

    func reportHandle(_ s: HomeSection, frame: CGRect?, inList: Bool = false, owner: UUID? = nil) {
        guard owns("handle.\(s.id).\(inList)", frame, owner) else { return }
        if inList { listHandles[s] = frame } else { handles[s] = frame }
    }

    /// A control a press leaves alone, under [key]; nil frame = gone.
    func reportExcluded(_ key: String, frame: CGRect?, inList: Bool = false, owner: UUID? = nil) {
        guard owns("excl.\(key).\(inList)", frame, owner) else { return }
        if inList { listExclusions[key] = frame } else { exclusions[key] = frame }
    }

    // Who reported each frame last: a view going away clears only its own (a rebuilt block appears before
    // the old one disappears).
    @ObservationIgnored private var frameOwners: [String: UUID] = [:]

    private func owns(_ key: String, _ frame: CGRect?, _ owner: UUID?) -> Bool {
        guard let owner else { return true }
        if frame != nil { frameOwners[key] = owner; return true }
        guard frameOwners[key] == owner else { return false }
        frameOwners[key] = nil
        return true
    }

    /// Each section's resize handle: a hold on it never lifts the block.
    func reportRowsHandle(_ s: HomeSection, frame: CGRect) {
        exclusions["rows.\(s.id)"] = frame.isNull ? nil : frame
    }

    /// What a press at [p] (the feed's or the list's space) lands on, for the gesture machine.
    func editHit(at p: CGPoint, inList: Bool) -> HomeEditHit {
        let excluded = inList ? listExclusions : exclusions
        if excluded.contains(where: { (!isEditing || !$0.key.hasPrefix(Self.normalOnly)) && $0.value.contains(p) }) {
            return HomeEditHitExcluded.shared
        }
        let blocks = (inList ? listFrames : frames).filter { (inList ? listOrder : order).contains($0.key) }
        if isEditing, let h = (inList ? listHandles : handles).first(where: { blocks[$0.key] != nil && $0.value.insetBy(dx: -4, dy: -4).contains(p) }) {
            let block = blocks[h.key]!
            return HomeEditHitHandle(section: HomeDragController.shared(h.key), local: local(p, in: block), size: size(block))
        }
        // The plate's outset (8 / 6) counts as the block.
        if let b = blocks.first(where: { $0.value.insetBy(dx: inList ? 0 : -8, dy: inList ? 0 : -6).contains(p) }) {
            return HomeEditHitBlock(section: HomeDragController.shared(b.key), local: local(p, in: b.value), size: size(b.value))
        }
        let nearest = blocks.min { abs($0.value.midY - p.y) < abs($1.value.midY - p.y) }?.key
        return HomeEditHitBlank(nearest: nearest.map(HomeDragController.shared))
    }

    private func local(_ p: CGPoint, in r: CGRect) -> HomePoint { HomePoint(x: Float(p.x - r.minX), y: Float(p.y - r.minY)) }
    private func size(_ r: CGRect) -> HomeSize { HomeSize(width: Float(r.width), height: Float(r.height)) }

    /// The sections the feed (or the list) lays out now, for a drag's start.
    func dragOrder(inList: Bool) -> [HomeSection] {
        let on = inList ? listOrder : order
        let laid = inList ? listFrames : frames
        return on.filter { laid[$0] != nil }
    }

    /// The rows [order] lays out in the feed's (or the list's) space: the blocks' measured heights from
    /// the rows' top, one gap apart, so a new order is laid out the moment the drag decides it.
    func layoutBands(for order: [HomeSection], inList: Bool) -> [(section: HomeSection, top: CGFloat, bottom: CGFloat)] {
        let laid = inList ? listFrames : frames
        let at = order.compactMap { s in laid[s].map { (s, $0) } }
        guard !at.isEmpty else { return [] }
        if !drag.isCarrying && drag.settling == nil || restTop[inList] == nil {
            let sorted = at.map(\.1).sorted { $0.minY < $1.minY }
            restTop[inList] = sorted.first!.minY
            let gaps = zip(sorted, sorted.dropFirst()).map { $1.minY - $0.maxY }.filter { $0 >= 0 }
            restGap[inList] = gaps.min() ?? (inList ? HomeEditListTokens.rowGap : 18)
        }
        var y = restTop[inList] ?? 0
        let gap = restGap[inList] ?? 18
        return at.map { s, r in
            defer { y += r.height + gap }
            return (s, y, y + r.height)
        }
    }

    /// Two columns: the rows [rows] lay out in the feed's space, each exactly its grid height (its units in the
    /// draft: a pair its left half's count held in both ranges), one feed spacing apart from the rows' top at rest.
    func layoutRowBands(for rows: [[HomeSection]]) -> [(section: HomeSection, top: CGFloat, bottom: CGFloat)] {
        let laid = rows.compactMap(\.first).compactMap { frames[$0] }
        if !drag.isCarrying && drag.settling == nil || restTop[false] == nil, let top = laid.map(\.minY).min() {
            restTop[false] = top
        }
        var y = restTop[false] ?? 0
        let l = shown
        return rows.compactMap { row in
            guard let first = row.first else { return nil }
            let h = HomeGrid.height(units: HomeGrid.shownUnits(l.units(ofRow: row), row: row, lyricIntro: HomeGrid.lyricIntroPending))
            defer { y += h + HomeGrid.feedSpacing }
            return (first, y, y + h)
        }
    }

    /// The content's left edge and width across, in the feed's (or the list's) space.
    func contentBand(inList: Bool) -> CGRect {
        let laid = (inList ? listFrames : frames).filter { (inList ? listOrder : order).contains($0.key) }.values
        guard let minX = laid.map(\.minX).min(), let maxX = laid.map(\.maxX).max() else { return .zero }
        return CGRect(x: minX, y: 0, width: maxX - minX, height: 0)
    }

    // MARK: The gesture machine's actions

    /// Applies the shared machine's actions in order (Android HomeEditGestureExecutor).
    func perform(_ actions: [HomeEditGestureAction], inList: Bool, finger: CGPoint) {
        for a in actions {
            switch a {
            case is HomeEditGestureActionEnter:
                enter()
            case let s as HomeEditGestureActionStartCharge:
                if let section = HomeSection(rawValue: s.section.id) { withAnimation(Yoin.spatialFast) { charged = section } }
            case is HomeEditGestureActionReleaseCharge:
                if charged != nil { withAnimation(Yoin.spatial) { charged = nil } }
            case is HomeEditGestureActionLongPressHaptic:
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            case is HomeEditGestureActionDragStartHaptic:
                UIImpactFeedbackGenerator(style: .light).impactOccurred()
            case let l as HomeEditGestureActionLift:
                guard rowsPreview.isEmpty, !resize.isActive, let section = HomeSection(rawValue: l.section.id) else { break }
                kickState(section).still()
                drag.liftBlock(section)
            case let d as HomeEditGestureActionDropLift:
                drag.dropLift(CGFloat(d.kick))
            case let s as HomeEditGestureActionStartCarry:
                drag.setFingerX(finger.x)
                drag.start(fingerY: CGFloat(s.y), inList: inList)
            case let m as HomeEditGestureActionCarryMove:
                drag.setFingerX(finger.x)
                drag.move(y: CGFloat(m.y))
            case let r as HomeEditGestureActionCarryRelease:
                drag.release(cancelled: r.cancelled)
                touched()
            case let t as HomeEditGestureActionBlockTap:
                if let section = HomeSection(rawValue: t.section.id) { kick(section, HomeEditMotionTokens.kickTap) }
            case is HomeEditGestureActionExitBlank:
                exit(.blank)
            case is HomeEditGestureActionTouch:
                touched()
            case let p as HomeEditGestureActionPointerDown:
                fingerDown = p.down
            case is HomeEditGestureActionPinchStart:
                list.pinchStart()
            case let p as HomeEditGestureActionPinchBy:
                list.pinchBy(CGFloat(p.scale))
            case is HomeEditGestureActionPinchEnd:
                list.pinchEnd()
            default:
                break
            }
        }
    }

    // MARK: Springs

    /// P's spring: Android stageSettle (0.85, 700).
    static var stageSpring: Spring { .yoin(Tokens.motion.stageSettle) }

    private func run(_ r: SpringRunner, from: CGFloat, to: CGFloat, velocity: CGFloat = 0, spring: Spring,
                     apply: @escaping (CGFloat) -> Void) {
        r.run(from: from, to: to, velocity: velocity, spring: spring, apply: apply)
    }

    /// A block's ripple: each section a beat after the one above (port sheet §2.5).
    static func ripple(_ p: CGFloat, index: Int) -> CGFloat {
        let step = 0.06 * CGFloat(min(index, 4))
        return min(max((p - step) / (1 - step), 0), 1)
    }
}

extension HomeEditController: HomeEditTouchTarget {}

/// One block's kick (Android HomeEditMotion.impulse): a light, ringing spring from where it is, its start
/// velocity the amplitude × KickGain, along a kick still ringing.
@MainActor
@Observable
final class HomeEditKick {
    /// Normalised (first peak = the amplitude).
    private(set) var value: CGFloat = 0
    @ObservationIgnored private let sign: CGFloat
    @ObservationIgnored private let spring = SpringRunner()

    init(sign: CGFloat) { self.sign = sign }

    func kick(_ a: CGFloat) {
        let base = a * HomeEditMotionTokens.kickGain
        let v = spring.isRunning && abs(spring.currentVelocity) > 1e-3
            ? spring.currentVelocity + (spring.currentVelocity > 0 ? base : -base)
            : base * sign
        spring.run(from: value, to: 0, velocity: v, spring: .yoin(Tokens.motion.homeEditKick)) { [weak self] in self?.value = $0 }
    }

    /// A lift stills the block.
    func still() {
        spring.stop()
        value = 0
    }
}

// MARK: - Edit-mode pieces

/// One section on the feed, editable: the plate that comes up under it, its title-row badges (Hide, the
/// drag handle), its lift and its place in a drag. Dragged, it follows the finger in its own layer
/// (HomeDragFollow) over every other block, its slot marked where it will land (HomeDropSlot); dropped, it
/// glides home and is kicked. In edit mode it is one VoiceOver element: "Section j of N", with Move up /
/// Move down, Hide and (Activities, Jump Back In) More rows / Fewer rows.
struct HomeEditBlock<Content: View>: View {
    @Environment(\.homeScheme) private var scheme
    var edit: HomeEditController
    var section: HomeSection
    var index: Int
    /// Up to three covers (the pinched list's strip).
    var covers: [CoverPalette]
    /// The section's resize handle (Activities, Jump Back In); nil = none.
    var rows: HomeRowsHandle? = nil
    /// Lyric Cards' count handle (its dots) instead of a resize.
    var lyricCount: Bool = false
    /// How many blocks the feed shows (VoiceOver's "of N").
    var count: Int = 0
    /// Two columns: where it sits (its ⌟ drags its span and its row's units instead of the phone's rows).
    var place: HomeGridPlace? = nil
    @ViewBuilder var content: () -> Content

    var body: some View {
        let s = scheme!
        let p = edit.progress
        let r = HomeEditController.ripple(p, index: index)
        let onTop = edit.drag.drawsOnTop(section)
        content()
            .allowsHitTesting(!edit.isEditing || (section == .jumpBackIn && edit.drag.liftSection == nil))
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityHidden(edit.isEditing)
            .frame(maxWidth: .infinity, alignment: .top)
            .modifier(HomeBlockChrome(edit: edit, section: section, ripple: r, scheme: s))
            .overlay(alignment: .topTrailing) {
                if p > 0.02 { badges(s, r) }
            }
            .overlay(alignment: place?.span == .right ? .topTrailing : .topLeading) {
                if let place, edit.resize.spanSection == section {
                    HomeSpanOutline(resize: edit.resize, place: place, scheme: s)
                }
            }
            .overlay(alignment: .bottomTrailing) {
                if let place, p > 0.02 {
                    HomeGridHandleView(edit: edit, place: place, scheme: s, ripple: r)
                } else if let rows, rows.stops.count > 1, p > 0.02 {
                    HomeRowsHandleView(edit: edit, section: section, handle: rows, scheme: s, ripple: r)
                } else if lyricCount, p > 0.02 {
                    HomeLyricCountHandleView(edit: edit, scheme: s, ripple: r)
                }
            }
            .modifier(SwayModifier(edit: edit, section: section, kick: edit.kickState(section)))
            // Where it lands stays at its laid-out place; the block follows the finger.
            .modifier(HomeDragFollow(drag: edit.drag, section: section, inList: false) {
                HomeDropSlot(drag: edit.drag, section: section, inList: false, scheme: s).padding(.horizontal, -8).padding(.vertical, -6)
            })
            .overlay { if edit.isEditing { accessibilityStop } }
            .modifier(EditFrameReport(space: HomeEditController.feedSpace) { edit.report(section, frame: $0, owner: $1) })
            // Over every row from its lift until it has landed; its own move is the finger's, never a spring.
            .zIndex(onTop ? 1 : 0)
            .transaction { if onTop { $0.animation = nil } }
    }

    private func badges(_ s: CoverScheme, _ r: CGFloat) -> some View {
        let shown = smoothstep(0.25, 1, r)
        return HStack(spacing: 8) {
            Button { edit.hide(section) } label: {
                YoinIcon("visibility_off", size: 18)
                    .foregroundStyle(s.onSurface)
                    .frame(width: 32, height: 32)
                    .background(s.surfaceContainerHighest, in: .circle)
                    .frame(width: 48, height: 48)
                    .contentShape(.rect)
            }
            .buttonStyle(PressScale())
            .disabled(!edit.isEditing || edit.drag.isCarrying)
            .modifier(EditFrameReport(space: HomeEditController.feedSpace) { edit.reportExcluded("hide.\(section.id)", frame: $0, owner: $1) })
            YoinIcon("drag_handle", size: 24)
                .foregroundStyle(s.onSurfaceVariant)
                .frame(width: 48, height: 48)
                .contentShape(.rect)
                // A pointer over it in edit mode: the move cursor (both ways in two columns, where a drag pairs too).
                .overlay { if edit.isEditing { HomeHandlePointer(axes: edit.twoColumn ? .both : .vertical) } }
                .modifier(EditFrameReport(space: HomeEditController.feedSpace) { edit.reportHandle(section, frame: $0, owner: $1) })
        }
        .accessibilityHidden(true)
        .scaleEffect(0.6 + 0.4 * shown)
        .opacity(Double(shown))
        // The 48 targets centre on the 28 title row and overhang the plate's edge.
        .offset(x: 12, y: -10)
    }

    /// The block as one VoiceOver element in edit mode (Android HomeEditBlock semantics).
    private var accessibilityStop: some View {
        let rowsLabel = rows.flatMap { h in h.stops.indices.contains(h.index) ? HomeRowsHandle.label(h.stops[h.index].rowCount) : nil }
        let n = max(count, index + 1)
        return Color.clear
            .allowsHitTesting(false)
            .accessibilityElement()
            .accessibilityLabel(section.title)
            .accessibilityValue(HomeEditAccessibility.position(index + 1, of: n, rows: place.map { HomeRowsHandle.label($0.units) } ?? rowsLabel,
                                                               span: place.map(\.span)))
            .accessibilityIdentifier("home.section.\(section.id)")
            .accessibilityActions {
                if index > 0 { Button(String(localized: "Move up")) { edit.move(section, to: index - 1) } }
                if index < n - 1 { Button(String(localized: "Move down")) { edit.move(section, to: index + 1) } }
                // Two columns: pairing without a drag (beside the block before, or back to a full row).
                if edit.canPlaceBesidePrevious(section) {
                    Button(HomeGridAccessibility.placeBesidePrevious) { edit.placeBesidePrevious(section) }
                }
                if edit.isHalf(section) {
                    Button(HomeGridAccessibility.makeFullWidth) { edit.setFull(section) }
                }
                Button(String(localized: "Hide")) { edit.hide(section) }
                if let place {
                    if place.range.contains(place.units + 1) {
                        Button(String(localized: "More rows")) { edit.setUnits(section, place.units + 1) }
                    }
                    if place.range.contains(place.units - 1) {
                        Button(String(localized: "Fewer rows")) { edit.setUnits(section, place.units - 1) }
                    }
                } else if let rows, rows.stops.count > 1 {
                    if rows.index < rows.stops.count - 1 {
                        Button(String(localized: "More rows")) { edit.setRows(section, rows.stops[rows.index + 1].preset) }
                    }
                    if rows.index > 0 {
                        Button(String(localized: "Fewer rows")) { edit.setRows(section, rows.stops[rows.index - 1].preset) }
                    }
                }
            }
    }
}

/// Reports a view's frame in [space] for the edit gestures' hit test and the drag's rows: as it changes, again
/// when the view comes back on screen (its geometry unchanged, `onGeometryChange` stays quiet), and nil while
/// it is off.
struct EditFrameReport: ViewModifier {
    var space: String
    /// The frame (nil: gone) and this view's own token.
    var report: (CGRect?, UUID) -> Void
    @State private var last: CGRect?
    @State private var token = UUID()

    func body(content: Content) -> some View {
        content
            .onGeometryChange(for: CGRect.self) { $0.frame(in: .named(space)) } action: {
                last = $0
                report($0, token)
            }
            .onAppear { if let last { report(last, token) } }
            .onDisappear { report(nil, token) }
    }
}

/// VoiceOver's words for a block in edit mode.
enum HomeEditAccessibility {
    /// "Section 2 of 9" (Android home_cd_section_index), with ", 4 rows" for a section that has rows.
    static func position(_ j: Int, of n: Int, rows: String?, span: HomeGridPlace.Span? = nil) -> String {
        let base: String
        if let rows { base = String(localized: "Section \(j) of \(n), \(rows)") } else { base = String(localized: "Section \(j) of \(n)") }
        // Two columns: which side of its row a half is on ("Left half" / "Right half").
        switch span {
        case .left: return base + ", " + String(localized: "Left half")
        case .right: return base + ", " + String(localized: "Right half")
        default: return base
        }
    }
}

/// A block's plate, charge and lift (Android HomeEditBlock + HomeDragEngine.lift): the plate comes up
/// under it through the ripple; a normal-mode press half way to the long press shrinks it a little; lifted
/// it grows 2 %, casts a 6 pt shadow and its plate tints toward surfaceContainerHighest. The lift is read
/// only by the lifted block, so its frames redraw that one alone.
struct HomeBlockChrome: ViewModifier {
    var edit: HomeEditController
    var section: HomeSection
    var ripple: CGFloat
    var scheme: CoverScheme

    func body(content: Content) -> some View {
        let lifted = edit.drag.liftSection == section
        let lift = lifted ? min(max(edit.drag.lift, 0), 1.2) : 0
        let tint = lifted ? min(max(edit.drag.liftTint, 0), 1) : 0
        let charge: CGFloat = edit.charged == section ? 0.985 : 1
        content
            .background {
                // Plate: the section's container comes up under it (outset 8 / 6).
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .fill(scheme.surfaceContainer.mix(with: scheme.surfaceContainerHighest, by: Double(tint), in: .perceptual)
                        .opacity(max(Double(ripple), Double(tint))))
                    .padding(.horizontal, -8)
                    .padding(.vertical, -6)
                    .scaleEffect(0.96 + 0.04 * max(ripple, tint))
                    .shadow(color: .black.opacity(0.2 * Double(lift)), radius: HomeEditMotionTokens.liftShadow * lift, y: 3 * lift)
            }
            .scaleEffect(charge * (1 + HomeEditMotionTokens.liftScale * lift))
    }
}

/// The dragged (or gliding) block's offset from its slot: read here alone, so a frame of the drag moves
/// this one layer and lays nothing out.
struct HomeDragFollow<Slot: View>: ViewModifier {
    var drag: HomeDragController
    var section: HomeSection
    var inList: Bool
    /// Drawn behind the block at its laid-out place (the drop slot): it doesn't follow.
    @ViewBuilder var slot: () -> Slot

    func body(content: Content) -> some View {
        let follows = (drag.dragging == section || drag.settling == section) && drag.inList == inList
        let o = follows ? drag.offset : .zero
        content
            .background { slot().offset(x: -o.width, y: -o.height) }
            .offset(o)
    }
}

/// Where the dragged block will land (Android dragSlotMark): the lifted plate colour at 55 % with a 2 pt
/// outline, at the slot the live order gives it, until it has glided home.
struct HomeDropSlot: View {
    var drag: HomeDragController
    var section: HomeSection
    var inList: Bool
    var scheme: CoverScheme

    var body: some View {
        let shown = drag.slotShown(section, inList: inList)
        RoundedRectangle(cornerRadius: 20, style: .continuous)
            .fill(scheme.surfaceContainerHighest.opacity(HomeEditMotionTokens.slotFillAlpha))
            .overlay {
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .strokeBorder(scheme.outlineVariant, lineWidth: HomeEditMotionTokens.slotStroke)
            }
            .opacity(shown ? 1 : 0)
            .animation(Yoin.effects, value: shown)
            .allowsHitTesting(false)
    }
}

/// What a section's resize handle needs (Android `HomeRowsTrack`): the stops on this screen, each
/// with its row count and the preset to store for it, and the one the section stands at.
struct HomeRowsHandle: Equatable {
    var stops: [(rowCount: Int, preset: HomeRowPreset)]
    var index: Int

    static func == (a: HomeRowsHandle, b: HomeRowsHandle) -> Bool {
        a.index == b.index && a.stops.map(\.rowCount) == b.stops.map(\.rowCount) && a.stops.map(\.preset) == b.stops.map(\.preset)
    }

    /// "1 row", "4 rows" (Android home_edit_rows): VoiceOver's count for a resized section.
    static func label(_ rows: Int) -> String {
        rows == 1 ? String(localized: "1 row") : String(localized: "\(rows) rows")
    }
}

/// A section's resize handle (D1 ②): a rounded ⌟ hugging the plate's bottom-right corner, in
/// onSurfaceVariant. Held it turns primary and grows a little; dragging down adds rows, up drops
/// them, a stop at a time, the section recomposing under the finger (one Undo step on release).
/// VoiceOver steps it with adjust.
struct HomeRowsHandleView: View {
    var edit: HomeEditController
    var section: HomeSection
    var handle: HomeRowsHandle
    var scheme: CoverScheme
    var ripple: CGFloat
    @State private var startIndex: Int?

    /// How far the finger travels for one stop.
    private static let pitch: CGFloat = 56

    var body: some View {
        let held = startIndex != nil
        let shown = smoothstep(0.25, 1, ripple)
        CornerGlyph()
            .stroke(held ? scheme.primary : scheme.onSurfaceVariant,
                    style: StrokeStyle(lineWidth: 2.5, lineCap: .round, lineJoin: .round))
            .frame(width: 14, height: 14)
            .scaleEffect(held ? 1.25 : 1)
            .animation(Yoin.spatial, value: held)
            .frame(width: 44, height: 44, alignment: .bottomTrailing)
            .padding(8)
            .contentShape(.rect)
            .overlay { if edit.isEditing { HomeHandlePointer(axes: .vertical) } }
            .scaleEffect(0.6 + 0.4 * shown)
            .opacity(Double(shown))
            // The plate's corner (outset 8 / 6), the glyph's corner inside it.
            .offset(x: 8, y: 6)
            .onGeometryChange(for: CGRect.self) { $0.frame(in: .named(HomeEditController.feedSpace)) } action: {
                edit.reportRowsHandle(section, frame: $0)
            }
            .gesture(DragGesture(minimumDistance: 2, coordinateSpace: .global)
                .onChanged { drag in
                    guard edit.isEditing else { return }
                    let base = startIndex ?? handle.index
                    if startIndex == nil { startIndex = base }
                    let steps = Int((drag.translation.height / Self.pitch).rounded())
                    let target = min(max(base + steps, 0), handle.stops.count - 1)
                    edit.previewRows(section, handle.stops[target].preset)
                }
                .onEnded { _ in
                    startIndex = nil
                    edit.endRows(section)
                })
            .accessibilityElement()
            .accessibilityLabel(String(localized: "Resize \(section.title)"))
            .accessibilityValue(HomeRowsHandle.label(handle.stops[min(handle.index, handle.stops.count - 1)].rowCount))
            .accessibilityAdjustableAction { direction in
                let next = handle.index + (direction == .increment ? 1 : -1)
                guard handle.stops.indices.contains(next) else { return }
                edit.setRows(section, handle.stops[next].preset)
            }
    }
}

/// The ⌟: down the right edge, round the corner, along the bottom.
private struct CornerGlyph: Shape {
    func path(in r: CGRect) -> Path {
        var p = Path()
        p.move(to: CGPoint(x: r.maxX, y: r.minY))
        p.addLine(to: CGPoint(x: r.maxX, y: r.maxY - 5))
        p.addQuadCurve(to: CGPoint(x: r.maxX - 5, y: r.maxY), control: CGPoint(x: r.maxX, y: r.maxY))
        p.addLine(to: CGPoint(x: r.minX, y: r.maxY))
        return p
    }
}

/// Lyric Cards' count handle (Android HomeLyricCountHandle, owner 2026-11-08): the same ⌟ as the
/// resize, on the plate's corner, but held and pulled down it opens a column of dots beside the
/// corner. Still held, sliding down adds a card and up takes one away, two to four, a tick at each;
/// letting go keeps it as one Undo step. VoiceOver steps it with adjust.
struct HomeLyricCountHandleView: View {
    var edit: HomeEditController
    var scheme: CoverScheme
    var ripple: CGFloat
    /// The count when the finger came down; nil while not held.
    @State private var start: Int?
    @State private var count = HomeRowPreset.default.lyricCards
    /// 0 closed, 1 open; follows the finger down.
    @State private var reveal: CGFloat = 0
    /// Where the dots finished opening: further travel counts from here.
    @State private var anchor: CGFloat?

    private var saved: Int { edit.rowsOf(.lyricHighlight).lyricCards }

    var body: some View {
        #if DEBUG
        let shot = edit.lyricCountShot
        #else
        let shot: Int? = nil
        #endif
        let held = start != nil || shot != nil
        let shown = smoothstep(0.25, 1, ripple)
        CornerGlyph()
            .stroke(held ? scheme.primary : scheme.onSurfaceVariant,
                    style: StrokeStyle(lineWidth: 2.5, lineCap: .round, lineJoin: .round))
            .frame(width: 14, height: 14)
            .scaleEffect(held ? 1.25 : 1)
            .animation(Yoin.spatial, value: held)
            .frame(width: 44, height: 44, alignment: .bottomTrailing)
            .padding(8)
            .contentShape(.rect)
            .overlay(alignment: .bottomLeading) {
                // A tall pill just left of the corner, its bottom level with the handle's, over this
                // section's own cards.
                HomeLyricCountPicker(count: shot ?? count, reveal: shot != nil ? 1 : reveal, scheme: scheme)
                    .alignmentGuide(.leading) { $0[.trailing] + 4 }
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
            }
            .scaleEffect(0.6 + 0.4 * shown)
            .opacity(Double(shown))
            // The plate's corner (outset 8 / 6), the glyph's corner inside it.
            .offset(x: 8, y: 6)
            .onGeometryChange(for: CGRect.self) { $0.frame(in: .named(HomeEditController.feedSpace)) } action: {
                edit.reportRowsHandle(.lyricHighlight, frame: $0)
            }
            .onDisappear { edit.reportRowsHandle(.lyricHighlight, frame: .null) }
            .gesture(DragGesture(minimumDistance: 0, coordinateSpace: .global)
                .onChanged { drag in
                    guard edit.isEditing else { return }
                    if start == nil {
                        start = saved
                        count = saved
                        anchor = nil
                    }
                    let dy = drag.translation.height
                    reveal = max(reveal, min(max(dy / HomeLyricCount.revealDistance, 0), 1))
                    guard reveal >= 1, let start else { return }
                    let from = anchor ?? dy
                    if anchor == nil { anchor = dy }
                    let next = HomeLyricCount.count(start: start, travel: dy - from)
                    if next != count {
                        withAnimation(Yoin.spatial) { count = next }
                        edit.lyricCountTick()
                    }
                }
                .onEnded { _ in
                    if start != nil, reveal >= 1 { edit.setLyricCount(count) }
                    start = nil
                    anchor = nil
                    withAnimation(Yoin.spatial) { reveal = 0 }
                    edit.touched()
                })
            .accessibilityElement()
            .accessibilityLabel(HomeSection.lyricHighlight.title)
            .accessibilityValue(HomeLyricCountPicker.label(saved))
            .accessibilityAdjustableAction { direction in
                let next = saved + (direction == .increment ? 1 : -1)
                guard (HomeLyricCount.min...HomeLyricCount.max).contains(next) else { return }
                edit.setLyricCount(next)
            }
    }
}

/// The dots, top to bottom from two to four cards to match the finger: every dot up to the count
/// filled, the count's own one larger, its label at the top, in a rounded bar taller than wide.
private struct HomeLyricCountPicker: View {
    var count: Int
    var reveal: CGFloat
    var scheme: CoverScheme

    /// "3 cards" (Android home_edit_lyric_cards).
    static func label(_ count: Int) -> String { String(localized: "\(count) cards") }

    var body: some View {
        let r = min(max(reveal, 0), 1)
        VStack(spacing: 8) {
            Text(Self.label(count))
                .homeType(Tokens.type.labelMedium)
                .foregroundStyle(scheme.onSurface)
                .lineLimit(1)
                .fixedSize()
                .contentTransition(.numericText())
            VStack(spacing: 0) {
                ForEach(HomeLyricCount.min...HomeLyricCount.max, id: \.self) { n in
                    Circle()
                        .fill(n <= count ? scheme.primary : scheme.outlineVariant)
                        .animation(Yoin.effects, value: count)
                        .frame(width: n == count ? 12 : 8, height: n == count ? 12 : 8)
                        .animation(Yoin.spatial, value: count)
                        .frame(height: 28)
                }
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 14)
        .background(scheme.surfaceContainerHighest, in: .rect(cornerRadius: 24, style: .continuous))
        .shadow(color: .black.opacity(0.18), radius: 6, y: 2)
        .scaleEffect(0.8 + 0.2 * r, anchor: .bottomTrailing)
        .opacity(Double(r))
    }
}

/// The edit-mode sway and the block's kicks: a TimelineView of its own, so only this modifier redraws each
/// frame while the envelope is up; a kick (HomeEditKick) rings on top, in this block alone. Still while
/// the block is lifted, dragged or gliding home (it follows the finger, not the clock).
private struct SwayModifier: ViewModifier {
    var edit: HomeEditController
    var section: HomeSection
    var kick: HomeEditKick

    /// A kick's first peak, in degrees per unit of amplitude.
    private static let kickDegrees = 0.6

    func body(content: Content) -> some View {
        let still = edit.drag.drawsOnTop(section) || UIAccessibility.isReduceMotionEnabled
        let envelope = still ? 0 : edit.sway
        let k = still ? 0 : Double(kick.value) * Self.kickDegrees
        if envelope > 0.0005 {
            TimelineView(.animation) { t in
                content.rotationEffect(.degrees(HomeEditController.swayAngle(section, time: t.date.timeIntervalSinceReferenceDate,
                                                                           envelope: envelope) + k))
            }
        } else {
            content.rotationEffect(.degrees(abs(k) < 0.0005 ? 0 : k))
        }
    }
}

/// The tray under the last section while editing (Android HomeEditTray):
/// "Hidden", one row per hidden section with its Show button, then Reset Home.
struct HomeEditTray: View {
    @Environment(\.homeScheme) private var scheme
    var edit: HomeEditController
    /// Under the pinched list, not the feed.
    var inList = false

    var body: some View {
        let s = scheme!
        let hidden = edit.layout.trayed
        VStack(alignment: .leading, spacing: 8) {
            HomeSectionTitle(String(localized: "Hidden")).padding(.bottom, 4)
            if hidden.isEmpty {
                Text("No hidden sections").homeType(Tokens.type.bodyMedium).foregroundStyle(s.onSurfaceVariant)
            }
            ForEach(hidden, id: \.self) { section in
                Button { edit.show(section) } label: {
                    HStack(spacing: 12) {
                        VStack(alignment: .leading, spacing: 0) {
                            HStack(spacing: 8) {
                                Text(section.title).homeType(Tokens.type.titleMedium, .semibold).foregroundStyle(s.onSurface)
                                if edit.layout.newSections.contains(section) {
                                    Text("New")
                                        .homeType(Tokens.type.labelSmall)
                                        .foregroundStyle(s.onTertiaryContainer)
                                        .padding(.horizontal, 6).padding(.vertical, 2)
                                        .background(s.tertiaryContainer, in: .capsule)
                                }
                            }
                            Text(section.supportingText).homeType(Tokens.type.bodySmall).foregroundStyle(s.onSurfaceVariant)
                        }
                        .lineLimit(1)
                        Spacer(minLength: 0)
                        YoinIcon("add", size: 24)
                            .foregroundStyle(s.onSecondaryContainer)
                            .frame(width: 40, height: 40)
                            .background(s.secondaryContainer, in: .circle)
                    }
                    .padding(.horizontal, 16)
                    .frame(height: 64)
                    .background(s.surfaceContainerHigh, in: RoundedRectangle(cornerRadius: Tokens.shape.PANEL.cg, style: .continuous))
                }
                .buttonStyle(PressScale())
                .accessibilityLabel("Show \(section.title)")
                .transition(.opacity.combined(with: .scale(scale: 0.96)))
            }
            Button { edit.reset() } label: {
                HStack(spacing: 8) {
                    YoinIcon("refresh", size: 18)
                    Text("Reset Home").homeType(Tokens.type.labelLarge, .semibold)
                }
                .foregroundStyle(edit.isAtDefault ? s.onSurface.opacity(0.38) : s.primary)
                .padding(.horizontal, 16)
                .frame(height: 48)
            }
            .buttonStyle(PressScale())
            .disabled(edit.isAtDefault || edit.drag.isCarrying)
            .frame(maxWidth: .infinity)
        }
        .id(HomeEditTray.id)
        .modifier(EditFrameReport(space: inList ? HomeEditController.listSpace : HomeEditController.feedSpace) { edit.reportExcluded("tray", frame: $0, inList: inList, owner: $1) })
    }

    /// The feed scrolls here for the header's Add.
    static let id = "home.edit.tray"
}

// MARK: - Header and feed pieces through P

/// "Home" → "Edit Home" through P (Android HomeEditHeaderTitle): "Home"
/// fades out early, "Edit Home" lies over it at no width of its own and
/// fades in later, so the header never makes room for it.
struct EditTitleSwap: View {
    var edit: HomeEditController
    var scheme: CoverScheme

    var body: some View {
        let p = min(max(edit.progress, 0), 1)
        Text("Home")
            .font(YoinFont.rounded(28, relativeTo: .largeTitle))
            .foregroundStyle(scheme.onSurface)
            .opacity(Double(1 - smoothstep(0.2, 0.6, p)))
            .overlay(alignment: .leading) {
                if p > 0.3 {
                    Text("Edit Home")
                        .font(YoinFont.rounded(28, relativeTo: .largeTitle))
                        .foregroundStyle(scheme.onSurface)
                        .fixedSize()
                        .opacity(Double(smoothstep(0.4, 0.8, p)))
                        .accessibilityAddTraits(.updatesFrequently)
                }
            }
            .accessibilityActions {
                if !edit.isEditing { Button("Edit Home") { edit.enter() } }
            }
    }
}

/// The header's end through P: the account button gives way (gone by P .5) to the edit actions:
/// the list switch, Undo or Add, and Done. (Android puts [Undo|Add][Done] in the bar and the switch where
/// the settings gear was; iOS keeps the bar as it is and puts them where the account button was.) Undo
/// shows while there is a step to undo, else Add while the tray holds a section (it scrolls the feed to the
/// tray), else a dimmed Undo.
struct HeaderActions<Account: View>: View {
    var edit: HomeEditController
    var scheme: CoverScheme
    @ViewBuilder var account: () -> Account

    var body: some View {
        let p = min(max(edit.progress, 0), 1)
        ZStack(alignment: .trailing) {
            account()
                .opacity(Double(1 - smoothstep(0, 0.5, p)))
                .allowsHitTesting(p < 0.5)
            if p > 0.01 {
                HomeEditActions(edit: edit, scheme: scheme)
                    .opacity(Double(smoothstep(0.5, 1, p)))
                    .scaleEffect(0.9 + 0.1 * smoothstep(0.5, 1, p), anchor: .trailing)
                    .allowsHitTesting(edit.isEditing)
                    // Under the compact list, which carries its own.
                    .accessibilityHidden(edit.list.on)
            }
        }
        .onGeometryChange(for: CGRect.self) { $0.frame(in: .named(HomeEditController.feedSpace)) } action: {
            edit.reportExcluded("header.actions", frame: $0)
        }
    }
}

/// Edit mode's actions: the list switch, Undo or Add, and Done (the feed's header and the compact list's).
struct HomeEditActions: View {
    var edit: HomeEditController
    var scheme: CoverScheme

    var body: some View {
        HStack(spacing: 4) {
            HomeEditViewToggle(edit: edit, scheme: scheme)
            undoOrAdd
            Button { edit.exit(.done) } label: {
                Text("Done")
                    .homeType(Tokens.type.labelLarge, .semibold)
                    .foregroundStyle(scheme.onPrimary)
                    .padding(.horizontal, 20)
                    .frame(height: 40)
                    .background(scheme.primary, in: .capsule)
            }
            .buttonStyle(PressScale())
            .demoTarget("editDone")
            .accessibilityIdentifier("home.edit.done")
        }
    }

    @ViewBuilder private var undoOrAdd: some View {
        let canUndo = !edit.undoStack.isEmpty
        let canAdd = !canUndo && !edit.layout.trayed.isEmpty
        Button { canAdd ? edit.showTray() : edit.undo() } label: {
            YoinIcon(canAdd ? "add" : "undo", size: 20)
                .foregroundStyle(scheme.onSurface)
                .frame(width: 40, height: 40)
                .background(scheme.surfaceContainerHigh, in: .circle)
                .frame(width: 44, height: 48)
                .contentTransition(.opacity)
        }
        .buttonStyle(PressScale())
        .disabled((!canUndo && !canAdd) || edit.drag.isCarrying)
        .opacity(canUndo || canAdd ? 1 : 0.38)
        .accessibilityLabel(canAdd ? String(localized: "Add") : String(localized: "Undo"))
        .accessibilityIdentifier(canAdd ? "home.edit.add" : "home.edit.undo")
        .animation(Yoin.effects, value: canAdd)
    }
}

/// The header's one-hand switch between the full layout and the compact list (Android HomeEditViewToggle;
/// the pinch stays the main way): it shows where a tap goes, the list over the full layout and the cards
/// over the list. Ignored while a block is lifted or dragged.
struct HomeEditViewToggle: View {
    var edit: HomeEditController
    var scheme: CoverScheme

    var body: some View {
        let on = edit.list.on
        Button { edit.list.toggle() } label: {
            YoinIcon(on ? "view_cards" : "view_list", size: 22)
                .foregroundStyle(scheme.onSurface)
                .frame(width: 44, height: 48)
                .contentShape(.rect)
                .contentTransition(.symbolEffect(.replace))
        }
        .buttonStyle(PressScale())
        .disabled(!edit.isEditing || edit.drag.isCarrying || edit.drag.liftSection != nil)
        .accessibilityLabel(on ? String(localized: "Full layout") : String(localized: "Compact list"))
        .accessibilityIdentifier("home.edit.toggle")
    }
}

/// Fades with P between [from] and [to] (the tray), in a view of its own.
struct EditFade: ViewModifier {
    var edit: HomeEditController
    var from: CGFloat
    var to: CGFloat

    func body(content: Content) -> some View {
        content.opacity(Double(smoothstep(from, to, edit.progress)))
    }
}

private struct HomeEditKey: EnvironmentKey {
    static let defaultValue: HomeEditController? = nil
}

extension EnvironmentValues {
    /// Home's edit controller: a card's tap asks it first (`swallowsTap`).
    var homeEdit: HomeEditController? {
        get { self[HomeEditKey.self] }
        set { self[HomeEditKey.self] = newValue }
    }
}

#if DEBUG
/// Recording aid: `-homeEditScripted YES` enters edit mode, moves the first
/// section two slots down, hides the next one, shows
/// it again from the tray, undoes, and leaves with Done.
@MainActor
enum HomeEditScript {
    private static var started = false
    /// The Home on screen's controller (Home is rebuilt once at launch).
    static weak var edit: HomeEditController?

    static func run() async {
        let d = UserDefaults.standard
        if let shot = d.string(forKey: "homeEditShot"), !started {
            started = true
            await editShot(shot)
            return
        }
        // `-homeLyricCountShot 3` (with `-lyricHighlightSeed YES`): edit mode with Lyric Cards' dots held
        // open at that count, the section moved first; `-homeLyricCountSet 2` drops the count there as a finger would.
        let shot = d.integer(forKey: "homeLyricCountShot"), set = d.integer(forKey: "homeLyricCountSet")
        if (shot > 0 || set > 0), !started {
            started = true
            try? await Task.sleep(for: .seconds(6))
            guard let edit else { return }
            edit.enter()
            try? await Task.sleep(for: .seconds(1.5))
            // Lyric Cards first, so the shot needs no scroll.
            edit.move(.lyricHighlight, to: 0)
            try? await Task.sleep(for: .seconds(1))
            if set > 0 { edit.setLyricCount(set) }
            if shot > 0 { withAnimation(Yoin.spatial) { edit.lyricCountShot = shot } }
            return
        }
        guard d.bool(forKey: "homeEditScripted"), !started else { return }
        started = true
        try? await Task.sleep(for: .seconds(3))
        guard let edit else { return }
        edit.enter()
        try? await Task.sleep(for: .seconds(2))
        guard let first = edit.layout.shownSections.first else { return }
        // Two slots down, as a drop would write it.
        edit.move(first, to: 2)
        try? await Task.sleep(for: .seconds(2))
        if let second = edit.layout.shownSections.first { edit.hide(second) }
        try? await Task.sleep(for: .seconds(1.5))
        if let back = edit.layout.trayed.first { edit.show(back) }
        try? await Task.sleep(for: .seconds(1.5))
        edit.undo()
        try? await Task.sleep(for: .seconds(1.5))
        edit.exit()
    }

    /// `-homeEditShot drag|edge|drop|list|listdrag`: screenshots and recordings of edit v2 with a scripted finger
    /// on the real drag (HomeDragController): Activities lifted and carried down past Jump Back In and held there
    /// (`drag`), carried into the bottom band and held while the feed scrolls (`edge`), carried and dropped
    /// (`drop`), the compact list (`list`), a strip carried in it (`listdrag`).
    private static func editShot(_ shot: String) async {
        try? await Task.sleep(for: .seconds(4))
        guard let edit else { return }
        edit.enter()
        try? await Task.sleep(for: .seconds(1.5))
        let inList = shot.hasPrefix("list")
        if inList {
            edit.list.set(true)
            try? await Task.sleep(for: .seconds(1.2))
            if shot == "list" { return }
        }
        guard let host = inList ? edit.drag.listHost : edit.drag.feedHost,
              let band = host.bands(for: host.order).first(where: { $0.section == .activities }) else { return }
        let y0 = band.top + min(60, (band.bottom - band.top) / 2)
        edit.drag.setFingerX(120)
        edit.drag.liftBlock(.activities)
        try? await Task.sleep(for: .milliseconds(250))
        edit.drag.start(fingerY: y0, inList: inList)
        let area = host.scrollArea
        let target: CGFloat = switch shot {
        case "edge": area.bottom - 24
        case "listdrag": y0 + (HomeEditListTokens.rowHeight + HomeEditListTokens.rowGap) * 2.6
        default: min(y0 + 480, area.bottom - 140)
        }
        await MemoriesScript.finger(1.1) { t in edit.drag.move(y: y0 + (target - y0) * t) }
        if shot == "drop" {
            try? await Task.sleep(for: .milliseconds(400))
            edit.drag.release(cancelled: false)
        }
    }
}
#endif

