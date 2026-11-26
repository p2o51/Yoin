import SwiftUI
import UIKit
import YoinShared

/// What a drag reads of the list it runs in (the feed, or the pinched list): the rows laid out for an
/// order, where the content sits across, the area whose ends scroll a held block, and the scroll itself.
/// Lengths are points in the list's viewport (its visible frame: y = 0 at its top edge), as
/// `HomeDragCore` reads them (Android's Box px; density 1).
@MainActor
protocol HomeDragHost: AnyObject {
    /// The pinched list, not the feed.
    var isList: Bool { get }
    /// The sections the list lays out now, in order.
    var order: [HomeSection] { get }
    /// The rows [order] lays out, top to bottom, in the viewport (each row's first section, its extent).
    func bands(for order: [HomeSection]) -> [(section: HomeSection, top: CGFloat, bottom: CGFloat)]
    var contentLeft: CGFloat { get }
    var contentWidth: CGFloat { get }
    /// Between the status tide and the bar: the 72 pt at each end scroll a held block.
    var scrollArea: (top: CGFloat, bottom: CGFloat) { get }
    /// Scrolls by [dy] (+ = toward the end) and answers what it moved (0 at either end).
    func scrollBy(_ dy: CGFloat) -> CGFloat
    /// The viewport's top in the list's content (feed) space: content y = viewport y + this.
    var viewportTop: CGFloat { get }
}

/// Lift and the in-place drag (owner 2026-11-22; Android HomeDragEngine): the lifted block follows the
/// finger in its own layer while the live order the feed (or the pinched list) renders changes under
/// it, every other block making way on the spatial spring, and a finger near the top or bottom scrolls.
/// The drop writes the order once (one Undo step), the block glides into its slot, and it and the blocks
/// it moved are kicked. Every decision (the half-travel swap, the 12 pt travel gate, the edge scroll and
/// its start guard, the re-decide on release, the rollback) is :shared's `HomeDragCore`, the same as
/// Android's, pinned by shared/src/commonTest/resources/golden/home-drag.json; this keeps the
/// animations, the CADisplayLink and the host.
///
/// Frame cost: a frame writes [offset] (read by the dragged block's follow modifier alone) and, on a
/// slot change, [live] (the one re-layout a drag makes).
@MainActor
@Observable
final class HomeDragController {
    // MARK: Lift

    /// The lifted block (lifted, dragged, or still settling its lift down).
    private(set) var liftSection: HomeSection?
    /// 0 rest … 1 lifted: scale 1 + .02·lift and a 6 pt shadow (Android LiftScale / LiftShadow).
    private(set) var lift: CGFloat = 0
    /// The plate's tint toward surfaceContainerHighest.
    private(set) var liftTint: CGFloat = 0

    // MARK: Drag

    /// The block being dragged; nil between drags.
    private(set) var dragging: HomeSection?
    /// The order the feed (or the list) renders while dragging; nil between drags.
    private(set) var live: [HomeSection]?
    /// The dropped block gliding from where it was let go into its slot.
    private(set) var settling: HomeSection?
    /// The drag (or the glide) runs in the pinched list, not the feed.
    private(set) var inList = false
    /// The dragged (or settling) block's offset from where its slot is laid out, in points.
    private(set) var offset: CGSize = .zero

    /// A drag is on: Undo, Reset and the other edits wait for its drop.
    var isCarrying: Bool { dragging != nil }

    /// [section] draws over every other block: lifted, dragged or gliding home.
    func drawsOnTop(_ section: HomeSection) -> Bool { dragging == section || settling == section || liftSection == section }

    /// [section]'s slot is marked where the drop lands (the dragged or gliding block), in the list or the feed.
    func slotShown(_ section: HomeSection, inList: Bool) -> Bool {
        (dragging == section || settling == section) && self.inList == inList
    }

    // MARK: Wiring

    /// The feed's rows (set by Home's touch view) and the pinched list's.
    @ObservationIgnored weak var feedHost: HomeDragHost?
    @ObservationIgnored weak var listHost: HomeDragHost?
    /// Writes the dropped order (one Undo step); false when nothing changed.
    @ObservationIgnored var commit: ([HomeSection]) -> Bool = { _ in false }
    /// A slot change: SEGMENT_TICK.
    @ObservationIgnored var tick: () -> Void = { UISelectionFeedbackGenerator().selectionChanged() }
    /// Kicks [section] by a normalised amplitude.
    @ObservationIgnored var kick: (HomeSection, CGFloat) -> Void = { _, _ in }
    /// The drag ended while its finger is still down (an exit, a resize): the gesture swallows the rest.
    @ObservationIgnored var letGo: () -> Void = {}
    /// Reduce Motion: the glide and the lift land at once.
    var reduced: Bool { UIAccessibility.isReduceMotionEnabled }

    @ObservationIgnored private let core: HomeDragCore
    @ObservationIgnored private var twoColumn = false
    @ObservationIgnored private var active: HomeDragHost?
    @ObservationIgnored private var shown: DragArrangement?
    @ObservationIgnored private var fingerX: CGFloat = .nan
    @ObservationIgnored private var lastDrawnTop: CGFloat?
    @ObservationIgnored private var link: CADisplayLink?
    @ObservationIgnored private var linkTarget: LinkTarget?
    @ObservationIgnored private var lastFrame: CFTimeInterval = -1
    @ObservationIgnored private let liftSpring = SpringRunner()
    @ObservationIgnored private let tintSpring = SpringRunner()
    @ObservationIgnored private let settleSpring = SpringRunner()
    @ObservationIgnored private var settleFrom: CGFloat = 0
    /// How far the drag scrolled its list (tests read it).
    @ObservationIgnored private(set) var scrolled: CGFloat = 0

    /// [density]: points per dp for the shared tokens (1 on iOS; the golden timelines replay at theirs).
    init(density: CGFloat = 1) {
        core = HomeDragCore(density: Float(density))
    }

    /// The finger's offset from the dragged block's top-left at the start (tests read it).
    var grab: CGPoint { CGPoint(x: CGFloat(core.grabX), y: CGFloat(core.grabY)) }
    /// The order the last drag ended on, before it was written or rolled back (tests read it).
    @ObservationIgnored private(set) var lastArrangement: [HomeSection] = []

    // MARK: Lift

    /// Picks [section] up: a block still lifted elsewhere is let go at once.
    func liftBlock(_ section: HomeSection) {
        if liftSection != nil, liftSection != section { lift = 0; liftTint = 0 }
        liftSection = section
        run(liftSpring, from: lift, to: 1, spring: .yoin(Tokens.motion.expressiveFastSpatial)) { [weak self] in self?.lift = $0 }
        run(tintSpring, from: liftTint, to: 1, spring: .yoin(Tokens.motion.expressiveFastEffects)) { [weak self] in self?.liftTint = $0 }
    }

    /// Puts the lifted block down where it is; a [kickA] > 0 kicks it.
    func dropLift(_ kickA: CGFloat) {
        guard let section = liftSection else { return }
        lowerLift(section)
        if kickA > 0 { kick(section, kickA) }
    }

    private func lowerLift(_ section: HomeSection) {
        run(liftSpring, from: lift, to: 0, spring: .yoin(Tokens.motion.expressiveDefaultSpatial)) { [weak self] in self?.lift = $0 } done: { [weak self] in
            guard let self, self.liftSection == section, self.dragging == nil else { return }
            self.liftSection = nil
        }
        run(tintSpring, from: liftTint, to: 0, spring: .yoin(Tokens.motion.expressiveFastEffects)) { [weak self] in self?.liftTint = $0 }
    }

    // MARK: Drag

    /// The finger's x, set before each move (two columns read it).
    func setFingerX(_ x: CGFloat) { fingerX = x }

    /// Starts dragging the lifted block with the finger at [fingerY] (viewport) in the feed, or the list while it is up.
    func start(fingerY: CGFloat, inList list: Bool) {
        guard let dragged = liftSection, dragging == nil, let host = list ? listHost : feedHost else { return }
        startDrag(dragged, host: host, fingerY: fingerY)
    }

    /// [start] against [host] (tests drive it with a fake host).
    func startDrag(_ dragged: HomeSection, host: HomeDragHost, fingerY: CGFloat, order: [HomeSection]? = nil,
                   pairs: [(HomeSection, HomeSection)] = [], twoColumn: Bool = false) {
        let ids = order ?? host.order
        guard ids.contains(dragged) else { return }
        let before = Self.arrangement(ids, pairs: twoColumn ? pairs : [])
        self.twoColumn = twoColumn
        let rows = core.rowsOf(arrangement: before, twoColumn: twoColumn)
        guard let row = rows.first(where: { Self.sections(of: $0).contains(dragged) }),
              let band = bands(host, before).first(where: { $0.section.id == row.first.id }) else { return }
        let lane = Self.lane(of: dragged, in: rows)
        let left = lane == .right ? host.contentLeft + (host.contentWidth - Self.columnGap) / 2 + Self.columnGap : host.contentLeft
        settleSpring.stop()
        // Picked up mid-glide: from where it is drawn on its way.
        let top = band.top + (settling == dragged ? offset.height : 0)
        settling = nil
        active = host
        inList = host.isList
        let x = fingerX.isNaN ? left : fingerX
        let area = host.scrollArea
        core.start(dragged: Self.shared(dragged), before: before, twoColumn: twoColumn, fingerX: Float(x), fingerY: Float(fingerY),
                   blockLeft: Float(left), blockTop: Float(top), areaTop: Float(area.top), areaBottom: Float(area.bottom))
        shown = before
        core.placed()
        scrolled = 0
        lastDrawnTop = nil
        live = ids
        dragging = dragged
        updateOffset()
        if schedulesFrames { startFrames() }
    }

    /// Frames run on a CADisplayLink; tests turn it off and call [frame] themselves.
    @ObservationIgnored var schedulesFrames = true

    // The laid-out rows of [arrangement] (each row keyed by its first section).
    private func bands(_ host: HomeDragHost, _ arrangement: DragArrangement) -> [(section: HomeSection, top: CGFloat, bottom: CGFloat)] {
        let rows = core.rowsOf(arrangement: arrangement, twoColumn: twoColumn)
        return host.bands(for: rows.compactMap { HomeSection(rawValue: $0.first.id) })
    }

    /// The finger moved to [y] (viewport). The next frame decides on it.
    func move(y: CGFloat) {
        guard dragging != nil else { return }
        core.move(x: Float(fingerX), y: Float(y))
        updateOffset()
    }

    /// The finger lifted: a changed order is written now and the block glides into its slot. [cancelled]
    /// (the system took the finger): nothing is written, the order goes back to before the lift. A drag
    /// that never started puts the lift down.
    func release(cancelled: Bool) {
        guard dragging != nil, let host = active else {
            dropLift(0)
            return
        }
        if !cancelled, let arrangement = shown {
            _ = core.release(cancelled: false, view: view(host, arrangement))
        }
        finish(keep: !cancelled, glide: true)
    }

    /// Rotation, a width change, the app going to the background, Home leaving the screen: the drag's
    /// geometry is gone, so it rolls back to before the lift, nothing written.
    func cancel() {
        guard dragging != nil else { return }
        letGo()
        finish(keep: false, glide: false)
    }

    /// An exit arrived mid-drag: Done (and the app's own exits) write the live order, Back and a blank tap
    /// roll it back (`dragExitKeepsDrop`). Then the exit runs.
    func exit(_ reason: HomeEditExitReason) {
        guard dragging != nil else { return }
        letGo()
        let write = core.exit(reason: reason)
        finish(keep: write != nil, glide: true)
    }

    /// Every drag value at rest now (a snap exit): a drag still on rolls back.
    func snapToRest() {
        if dragging != nil { cancel() }
        [liftSpring, tintSpring, settleSpring].forEach { $0.stop() }
        stopFrames()
        liftSection = nil
        lift = 0
        liftTint = 0
        settling = nil
        offset = .zero
    }

    // MARK: Frames

    /// One frame [dt] s after the last (0 on the first): one decision, or else the edge scroll. [laidOut]:
    /// the list lays out what the frame decided before the next one (tests can hold it back).
    @discardableResult
    func frame(dt: CGFloat, laidOut: Bool = true) -> HomeDragFrame? {
        guard dragging != nil, let host = active, let arrangement = shown else { return nil }
        let result = core.frame(dtSeconds: Float(min(dt, CGFloat(HomeDragTokens.shared.MaxFrameSeconds))), view: view(host, arrangement))
        if let step = result as? HomeDragFrameStep {
            withAnimation(reduced ? nil : Yoin.spatial) { live = Self.ids(step.arrangement) }
            tick()
        } else if let scroll = result as? HomeDragFrameScroll {
            let consumed = host.scrollBy(CGFloat(scroll.px))
            scrolled += consumed
            core.scrolled(consumedPx: Float(consumed))
        }
        if laidOut, let now = core.arrangement {
            shown = now
            core.placed()
        }
        updateOffset()
        return result
    }

    private func startFrames() {
        stopFrames()
        lastFrame = -1
        let target = LinkTarget { [weak self] link in self?.linkFired(link) }
        let l = CADisplayLink(target: target, selector: #selector(LinkTarget.fire(_:)))
        l.preferredFrameRateRange = CAFrameRateRange(minimum: 60, maximum: 120, preferred: 120)
        l.add(to: .main, forMode: .common)
        link = l
        linkTarget = target
    }

    private func stopFrames() {
        link?.invalidate()
        link = nil
        linkTarget = nil
    }

    private func linkFired(_ link: CADisplayLink) {
        let dt = lastFrame < 0 ? 0 : link.targetTimestamp - lastFrame
        lastFrame = link.targetTimestamp
        frame(dt: CGFloat(dt))
    }

    // MARK: Ending

    // [keep] writes a changed order, else the order before the lift comes back; [glide]: the block glides
    // from where it was let go into whichever slot the layout gives it.
    private func finish(keep: Bool, glide: Bool) {
        guard let dragged = dragging, let host = active else { return }
        stopFrames()
        let before = core.origin.map(Self.ids) ?? live ?? []
        let after = core.arrangement.map(Self.ids) ?? before
        lastArrangement = after
        let landed = keep ? after : before
        // The arrangement moved (the order, or in two columns the pairs).
        let rearranged = !(core.arrangement.map { a in core.origin.map { a.isEqual($0) } ?? false } ?? true)
        let moved = keep && after != before ? Self.moved(before, after) : []
        // It glides from where it was last drawn into its slot in the order it landed in (content space).
        let slotTop = bands(host, Self.arrangement(landed, pairs: [])).first { $0.section == dragged }.map { $0.top + host.viewportTop }
        settleFrom = glide && !reduced ? (lastDrawnTop.flatMap { d in slotTop.map { d - $0 } } ?? 0) : 0
        settling = dragged
        offset = CGSize(width: 0, height: settleFrom)
        core.end()
        var changed = false
        if keep {
            // The written order and the end of the live one together: the feed never shows the order it started from.
            var t = Transaction()
            t.disablesAnimations = true
            withTransaction(t) {
                if rearranged { changed = commit(after) }
                dragging = nil
                live = nil
            }
        } else {
            // Rolled back: the others make their way home on the spring.
            dragging = nil
            withAnimation(reduced ? nil : Yoin.spatial) { live = nil }
        }
        let kicked = changed ? moved : []
        if settleFrom != 0 {
            settleSpring.run(from: 0, to: 1, velocity: 0, spring: .yoin(Tokens.motion.expressiveDefaultSpatial)) { [weak self] p in
                guard let self, self.settling == dragged else { return }
                self.offset = CGSize(width: 0, height: self.settleFrom * (1 - p))
            } completion: { [weak self] in
                self?.settled(dragged, moved: kicked)
            }
        } else {
            settled(dragged, moved: kicked)
        }
        lowerLift(dragged)
    }

    private func settled(_ section: HomeSection, moved: [HomeSection]) {
        guard settling == section else { return }
        settling = nil
        offset = .zero
        kick(section, HomeEditMotionTokens.kickDrop)
        for s in moved where s != section { kick(s, HomeEditMotionTokens.kickNeighbour) }
    }

    // MARK: Geometry

    // The dragged block's offset from its slot in the live order: the finger minus the grab, against where
    // the slot is laid out on this frame (a scroll or a step moved it). One column follows the finger down
    // and up only (design.md: 两栏时横竖都跟).
    private func updateOffset() {
        guard let dragged = dragging, let host = active, let order = live else { return }
        let top = CGFloat(core.fingerY - core.grabY)
        lastDrawnTop = top + host.viewportTop
        guard let slot = bands(host, Self.arrangement(order, pairs: [])).first(where: { $0.section == dragged }) else { return }
        offset = CGSize(width: 0, height: top - slot.top)
    }

    private func view(_ host: HomeDragHost, _ arrangement: DragArrangement) -> HomeDragView {
        let area = host.scrollArea
        let visible = bands(host, arrangement).map {
            DragItemBand(key: Self.shared($0.section), top: Float($0.top), bottom: Float($0.bottom))
        }
        return HomeDragView(visible: visible, contentLeft: Float(host.contentLeft), contentWidth: Float(host.contentWidth),
                            areaTop: Float(area.top), areaBottom: Float(area.bottom), twoColumn: twoColumn)
    }

    private func run(_ r: SpringRunner, from start: CGFloat, to: CGFloat, spring: Spring,
                     apply: @escaping (CGFloat) -> Void, done: @escaping () -> Void = {}) {
        if reduced {
            r.stop()
            apply(to)
            done()
            return
        }
        r.run(from: start, to: to, velocity: 0, spring: spring, apply: apply, completion: done)
    }

    // MARK: Shared types

    /// The two columns' gap (shared `HomeSpanRules.ColumnGap`).
    nonisolated static let columnGap: CGFloat = 20

    private static let byId: [String: YoinShared.HomeSection] =
        Dictionary(uniqueKeysWithValues: YoinShared.HomeSection.entries.map { ($0.id, $0) })

    static func shared(_ s: HomeSection) -> YoinShared.HomeSection { byId[s.id]! }

    static func ids(_ a: DragArrangement) -> [HomeSection] { a.ids.compactMap { HomeSection(rawValue: $0.id) } }

    static func arrangement(_ ids: [HomeSection], pairs: [(HomeSection, HomeSection)]) -> DragArrangement {
        var map: [YoinShared.HomeSection: YoinShared.HomeSection] = [:]
        for (l, r) in pairs where ids.contains(l) && ids.contains(r) { map[shared(l)] = shared(r) }
        return DragArrangement(ids: ids.map(shared), pairs: map)
    }

    static func sections(of row: HomeFeedRow) -> [HomeSection] {
        if let half = row as? HomeFeedRowHalves { return [half.left, half.right].compactMap { HomeSection(rawValue: $0.id) } }
        return [row.first].compactMap { HomeSection(rawValue: $0.id) }
    }

    private enum Lane { case full, left, right }

    private static func lane(of s: HomeSection, in rows: [HomeFeedRow]) -> Lane {
        for row in rows {
            if let h = row as? HomeFeedRowHalves {
                if h.left.id == s.id { return .left }
                if h.right.id == s.id { return .right }
            }
        }
        return .full
    }

    /// Sections whose index changed from [before] to [after] (the drop's neighbours).
    static func moved(_ before: [HomeSection], _ after: [HomeSection]) -> [HomeSection] {
        before.enumerated().filter { i, s in after.indices.contains(i) ? after[i] != s : true }.map(\.element)
    }
}

/// Kicks (Android HomeEditTokens §3.7): normalised amplitudes, first peak = a.
enum HomeEditMotionTokens {
    /// An edit-mode tap, and a block dropped where it was lifted (shared `HomeEditGestureTokens.KickTap`).
    static let kickTap: CGFloat = CGFloat(HomeEditGestureTokens.shared.KickTap)
    /// The dragged block once it has settled into its slot.
    static let kickDrop: CGFloat = 0.7
    /// Neighbours whose index changed.
    static let kickNeighbour: CGFloat = 0.35
    /// Start velocity per unit of amplitude (Android KickGain).
    static let kickGain: CGFloat = 31.6
    /// The lifted block's scale and shadow (Android LiftScale, LiftShadow).
    static let liftScale: CGFloat = 0.02
    static let liftShadow: CGFloat = 6
    /// The drop slot: the lifted plate colour at 55% and a 2 pt outline (Android SlotFillAlpha, SlotStroke).
    static let slotFillAlpha: Double = 0.55
    static let slotStroke: CGFloat = 2
}

/// CADisplayLink retains its target: this holds the drag weakly.
@MainActor
private final class LinkTarget: NSObject {
    let body: (CADisplayLink) -> Void
    init(_ body: @escaping (CADisplayLink) -> Void) { self.body = body }
    @objc func fire(_ link: CADisplayLink) { body(link) }
}
