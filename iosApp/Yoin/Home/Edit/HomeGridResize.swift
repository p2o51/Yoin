import SwiftUI
import UIKit
import YoinShared

/*
 * Two columns' resize (parity p4 B2; Android HomeSpanResize.kt, HomeGridUnits.kt HomeUnitDrag; design.md §主页「两栏」):
 * every section's ⌟ drags both ways in two columns, and the first direction a drag takes locks it (shared
 * `homeHandleAxis`). Sideways is the span: a full row dragged left past the midpoint becomes a half and pulls the
 * row after it up beside it; a left half dragged right (a right half left) past it becomes full, its partner on
 * its own row after it. While the finger is down the block's width follows it 1:1 (a rubber band a little under a
 * half) and a dashed outline shows the width it will snap to; on release it springs there and the change is
 * written (one Undo step), or springs back when there is nothing to pair with. Down and up is the units: the row's
 * height follows the finger over the pair's overlap range (24 pt of rubber band past either end), ticking at each
 * count, and springs to the nearest on release. ← / → and ↑ / ↓ on a focused handle do the same at once.
 */

/// Where a section sits in two columns, for its ⌟ (shared `HomeSpanInfo` + the row's units).
struct HomeGridPlace: Equatable {
    enum Span: Equatable { case full, left, right }
    var section: HomeSection
    var row: [HomeSection]
    var span: Span
    /// A full row has a row after it to pull up beside it.
    var hasNext: Bool
    var units: Int
    var range: ClosedRange<Int>
    /// The content's width and a half's.
    var fullWidth: CGFloat
    var halfWidth: CGFloat
    var width: CGFloat { span == .full ? fullWidth : halfWidth }

    var sharedSpan: HomeSpan {
        switch span {
        case .full: .full
        case .left: .lefthalf
        case .right: .righthalf
        }
    }

    /// [section]'s place in [rows] (the feed's rows now), [enabledOrder] the sections iOS draws.
    static func of(_ section: HomeSection, rows: [[HomeSection]], enabledOrder: [HomeSection], fullWidth: CGFloat,
                   layout: HomeLayout, lyricIntro: Bool = false) -> HomeGridPlace? {
        guard let row = rows.first(where: { $0.contains(section) }),
              let info = YoinHomeLayout.shared.spanInfo(id: section.id, rows: rows.map { $0.map(\.id) },
                                                        enabledOrder: enabledOrder.map(\.id), fullPx: Float(fullWidth),
                                                        gapPx: Float(HomeGrid.rowGap)) else { return nil }
        let span: Span = info.span == .full ? .full : info.span == .lefthalf ? .left : .right
        return HomeGridPlace(section: section, row: row, span: span, hasNext: info.hasNext,
                             units: HomeGrid.shownUnits(layout.units(ofRow: row), row: row, lyricIntro: lyricIntro),
                             range: HomeGrid.rowUnitRange(row[0], row.count > 1 ? row[1] : nil, lyricIntro: lyricIntro),
                             fullWidth: fullWidth, halfWidth: CGFloat(info.halfPx))
    }
}

/// The one resize in flight (one finger on one handle): a span drag's live width and the span it will snap to,
/// or a unit drag's live height and the count it will settle on. The feed's row layout reads them.
@MainActor
@Observable
final class HomeGridResize {
    // MARK: Span (horizontal)

    /// The section whose width is dragged (or springing to where it lands); nil: none.
    private(set) var spanSection: HomeSection?
    /// Its live width.
    private(set) var spanWidth: CGFloat = 0
    /// What the outline shows and a release springs to: half or full.
    private(set) var spanTarget: HomeGridPlace.Span = .full
    /// The finger is down (the outline at full strength), not the release spring.
    private(set) var spanDragging = false

    // MARK: Units (vertical)

    /// The row whose height is dragged (or springing); empty: none.
    private(set) var unitRow: [HomeSection] = []
    /// Its live height.
    private(set) var unitHeight: CGFloat = 0
    /// The count it settles on.
    private(set) var unitTarget = 1

    @ObservationIgnored private var place: HomeGridPlace?
    @ObservationIgnored private var startX: CGFloat = 0
    @ObservationIgnored private var startY: CGFloat = 0
    @ObservationIgnored private let spring = SpringRunner()
    /// Writes a span change (one Undo step).
    @ObservationIgnored var commitSpan: (HomeSection, HomeSpanCommit) -> Void = { _, _ in }
    /// Writes a unit count (one Undo step, both halves of a pair).
    @ObservationIgnored var commitUnits: (HomeSection, Int) -> Void = { _, _ in }
    /// A target crossed: SEGMENT_TICK.
    @ObservationIgnored var tick: () -> Void = { UISelectionFeedbackGenerator().selectionChanged() }

    var isActive: Bool { spanSection != nil || !unitRow.isEmpty }

    /// The live height for [row], nil when it isn't the one dragged.
    func liveHeight(_ row: [HomeSection]) -> CGFloat? { !unitRow.isEmpty && unitRow == row ? unitHeight : nil }

    /// The span drag for [row], nil when none of its sections is dragged.
    func liveSpan(_ row: [HomeSection]) -> (section: HomeSection, width: CGFloat)? {
        guard let s = spanSection, row.contains(s) else { return nil }
        return (s, spanWidth)
    }

    // MARK: Drag

    /// The finger came down on [place]'s handle at [p] (global).
    func grab(_ place: HomeGridPlace, at p: CGPoint) {
        spring.stop()
        self.place = place
        startX = p.x
        startY = p.y
    }

    /// The finger moved to [p]: the first direction past the slop locks the axis.
    func drag(to p: CGPoint) {
        guard let place else { return }
        let dx = p.x - startX, dy = p.y - startY
        if spanSection == nil && unitRow.isEmpty {
            guard let axis = YoinHomeLayout.shared.handleAxis(dx: Float(dx), dy: Float(dy), slopPx: Float(HomeEditGestureDriver.slop),
                                                              horizontal: true) else { return }
            if axis == .horizontal {
                spanSection = place.section
                spanWidth = place.width
                spanTarget = place.span == .full ? .full : .left
                spanDragging = true
            } else {
                unitRow = place.row
                unitHeight = HomeGrid.height(units: place.units)
                unitTarget = place.units
            }
        }
        if spanSection != nil {
            let w = CGFloat(YoinHomeLayout.shared.spanDragWidth(from: place.sharedSpan, startPx: Float(place.width), dx: Float(dx),
                                                                halfPx: Float(place.halfWidth), fullPx: Float(place.fullWidth)))
            spanWidth = w
            let target: HomeGridPlace.Span =
                YoinHomeLayout.shared.spanTarget(widthPx: Float(w), halfPx: Float(place.halfWidth), fullPx: Float(place.fullWidth)) == .full
                ? .full : .left
            if target != spanTarget {
                spanTarget = target
                tick()
            }
        } else if !unitRow.isEmpty {
            let lo = HomeGrid.height(units: place.range.lowerBound) - HomeGrid.overdrag
            let hi = HomeGrid.height(units: place.range.upperBound) + HomeGrid.overdrag
            let h = min(max(HomeGrid.height(units: place.units) + dy, lo), hi)
            unitHeight = h
            let next = HomeGrid.unitsNearest(height: h, range: place.range)
            if next != unitTarget {
                unitTarget = next
                tick()
            }
        }
    }

    /// The finger lifted ([cancelled]: the system took it): springs to where it lands, then writes it.
    func release(cancelled: Bool) {
        guard let place else { return }
        self.place = nil
        if let section = spanSection {
            spanDragging = false
            let write: HomeSpanCommit = cancelled ? .none
                : YoinHomeLayout.shared.spanCommit(from: place.sharedSpan, target: spanTarget == .full ? .full : .lefthalf,
                                                   hasNext: place.hasNext)
            let landing = write == .tohalf ? place.halfWidth : write == .tofull ? place.fullWidth : place.width
            run(from: spanWidth, to: landing, apply: { [weak self] in self?.spanWidth = $0 }) { [weak self] in
                guard let self, self.spanSection == section, !self.spanDragging else { return }
                // The change lands with the cell already at its new width: no second move.
                var t = Transaction()
                t.disablesAnimations = true
                withTransaction(t) {
                    if write != .none { self.commitSpan(section, write) }
                    self.spanSection = nil
                }
            }
        } else if !unitRow.isEmpty {
            let row = unitRow
            let units = cancelled ? place.units : unitTarget
            run(from: unitHeight, to: HomeGrid.height(units: units), apply: { [weak self] in self?.unitHeight = $0 }) { [weak self] in
                guard let self, self.unitRow == row else { return }
                var t = Transaction()
                t.disablesAnimations = true
                withTransaction(t) {
                    if units != place.units { self.commitUnits(place.section, units) }
                    self.unitRow = []
                }
            }
        }
    }

    /// Drops any resize at once (edit mode left, a drag started, the width changed).
    func snap() {
        spring.stop()
        place = nil
        spanSection = nil
        spanDragging = false
        unitRow = []
    }

    private func run(from: CGFloat, to: CGFloat, apply: @escaping (CGFloat) -> Void, done: @escaping () -> Void) {
        if UIAccessibility.isReduceMotionEnabled {
            apply(to)
            done()
            return
        }
        spring.run(from: from, to: to, velocity: 0, spring: .yoin(Tokens.motion.expressiveDefaultSpatial), apply: apply, completion: done)
    }

    // MARK: Keys and VoiceOver

    /// ← / → on a focused handle (shared `homeSpanKey`): what it writes, nil when it does nothing here.
    static func spanKey(_ place: HomeGridPlace, left: Bool) -> HomeSpanCommit? {
        YoinHomeLayout.shared.spanKey(from: place.sharedSpan, left: left, hasNext: place.hasNext)
    }
}

/// VoiceOver's words for a two-column ⌟.
enum HomeGridAccessibility {
    static var halfWidth: String { String(localized: "Half width") }
    static var fullWidth: String { String(localized: "Full width") }
    static var placeBesidePrevious: String { String(localized: "Place beside previous") }
    static var makeFullWidth: String { String(localized: "Make full width") }
}

/// The two-column ⌟ on a block's plate corner: drags sideways for the span and down / up for the units, steps with
/// the arrow keys when focused (an iPad keyboard, a Mac), and is one VoiceOver element whose actions are Half width
/// / Full width and whose adjust steps the units. In edit mode a pointer over it shows a four-way move cursor.
struct HomeGridHandleView: View {
    var edit: HomeEditController
    var place: HomeGridPlace
    var scheme: CoverScheme
    var ripple: CGFloat
    @State private var held = false
    @FocusState private var focused: Bool

    var body: some View {
        let shown = smoothstep(0.25, 1, ripple)
        let on = held || focused
        HomeCornerGlyph()
            .stroke(on ? scheme.primary : scheme.onSurfaceVariant,
                    style: StrokeStyle(lineWidth: 2.5, lineCap: .round, lineJoin: .round))
            .frame(width: 14, height: 14)
            .scaleEffect(held ? 1.25 : 1)
            .animation(Yoin.spatial, value: held)
            .frame(width: 44, height: 44, alignment: .bottomTrailing)
            .padding(8)
            .contentShape(.rect)
            .overlay { if edit.isEditing { HomeHandlePointer(axes: .both) } }
            .scaleEffect(0.6 + 0.4 * shown)
            .opacity(Double(shown))
            .offset(x: 8, y: 6)
            .onGeometryChange(for: CGRect.self) { $0.frame(in: .named(HomeEditController.feedSpace)) } action: {
                edit.reportRowsHandle(place.section, frame: $0)
            }
            .onDisappear { edit.reportRowsHandle(place.section, frame: .null) }
            .gesture(DragGesture(minimumDistance: 0, coordinateSpace: .global)
                .onChanged { g in
                    guard edit.isEditing, !edit.drag.isCarrying else { return }
                    if !held {
                        held = true
                        edit.resize.grab(place, at: g.startLocation)
                    }
                    edit.resize.drag(to: g.location)
                    edit.touched()
                }
                .onEnded { _ in
                    guard held else { return }
                    held = false
                    edit.resize.release(cancelled: false)
                    edit.touched()
                })
            .focusable(edit.isEditing)
            .focused($focused)
            .focusEffectDisabled()
            .onKeyPress(keys: [.leftArrow, .rightArrow, .upArrow, .downArrow]) { press in
                switch press.key {
                case .leftArrow, .rightArrow:
                    guard let write = HomeGridResize.spanKey(place, left: press.key == .leftArrow) else { return .ignored }
                    edit.commitSpan(place.section, write)
                case .upArrow, .downArrow:
                    let next = place.units + (press.key == .downArrow ? 1 : -1)
                    guard place.range.contains(next) else { return .ignored }
                    edit.setUnits(place.section, next)
                default: return .ignored
                }
                return .handled
            }
            .accessibilityElement()
            .accessibilityLabel(String(localized: "Resize \(place.section.title)"))
            .accessibilityValue(place.span == .full ? HomeGridAccessibility.fullWidth : HomeGridAccessibility.halfWidth)
            .accessibilityIdentifier("home.resize.\(place.section.id)")
            .accessibilityActions {
                if place.span == .full, place.hasNext {
                    Button(HomeGridAccessibility.halfWidth) { edit.setHalf(place.section) }
                }
                if place.span != .full {
                    Button(HomeGridAccessibility.fullWidth) { edit.setFull(place.section) }
                }
            }
            .accessibilityAdjustableAction { direction in
                let next = place.units + (direction == .increment ? 1 : -1)
                guard place.range.contains(next) else { return }
                edit.setUnits(place.section, next)
            }
    }
}

/// The ⌟: down the right edge, round the corner, along the bottom.
struct HomeCornerGlyph: Shape {
    func path(in r: CGRect) -> Path {
        var p = Path()
        p.move(to: CGPoint(x: r.maxX, y: r.minY))
        p.addLine(to: CGPoint(x: r.maxX, y: r.maxY - 5))
        p.addQuadCurve(to: CGPoint(x: r.maxX - 5, y: r.maxY), control: CGPoint(x: r.maxX, y: r.maxY))
        p.addLine(to: CGPoint(x: r.minX, y: r.maxY))
        return p
    }
}

/// The span drag's dashed outline (Android HomeFeedRowLayout's): the width the dragged block will snap to, from
/// the row's leading edge (a right half's from its trailing one), at full strength while the finger is down.
struct HomeSpanOutline: View {
    var resize: HomeGridResize
    var place: HomeGridPlace
    var scheme: CoverScheme

    var body: some View {
        let half = resize.spanTarget != .full
        let w = half ? place.halfWidth : place.fullWidth
        RoundedRectangle(cornerRadius: 20, style: .continuous)
            .strokeBorder(scheme.primary, style: StrokeStyle(lineWidth: 2, dash: [8, 6]))
            .opacity(resize.spanDragging ? 1 : 0.5)
            .frame(width: w)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: place.span == .right ? .trailing : .leading)
            .allowsHitTesting(false)
            .animation(Yoin.spatial, value: half)
    }
}

// MARK: - The handles' pointer

/// The pointer over an edit handle (ipad.md 指针): a four-way move cursor (arrows on the axes the handle drags),
/// the iPadOS way of saying "drag me" (SwiftUI's `pointerStyle(.move)` is macOS / visionOS only). It takes hover
/// events only: every touch and click goes on to the handle beneath.
struct HomeHandlePointer: UIViewRepresentable {
    enum Axes { case vertical, both }
    var axes: Axes

    func makeUIView(context: Context) -> HomeHandlePointerView {
        let v = HomeHandlePointerView()
        v.axes = axes
        return v
    }

    func updateUIView(_ v: HomeHandlePointerView, context: Context) { v.axes = axes }
}

final class HomeHandlePointerView: UIView, UIPointerInteractionDelegate {
    var axes: HomeHandlePointer.Axes = .both

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        addInteraction(UIPointerInteraction(delegate: self))
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        guard ColumnResizePointerView.takes(event?.type), self.point(inside: point, with: event) else { return nil }
        return self
    }

    func pointerInteraction(_ interaction: UIPointerInteraction, regionFor request: UIPointerRegionRequest,
                            defaultRegion: UIPointerRegion) -> UIPointerRegion? { defaultRegion }

    func pointerInteraction(_ interaction: UIPointerInteraction, styleFor region: UIPointerRegion) -> UIPointerStyle? {
        let style = UIPointerStyle(shape: .roundedRect(CGRect(x: 0, y: 0, width: 10, height: 10), radius: 5))
        var arrows: [UIPointerAccessory] = [.arrow(.top), .arrow(.bottom)]
        if axes == .both { arrows += [.arrow(.left), .arrow(.right)] }
        style.accessories = arrows
        return style
    }
}
