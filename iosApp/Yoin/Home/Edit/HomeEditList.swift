import SwiftUI
import UIKit
import YoinShared

// Home edit mode's compact list (owner 2026-11-22; Android HomeEditList): a two-finger pinch in folds the
// feed into one strip per section, a pinch out unfolds it, and the header's switch does either with one
// hand. The strips are reordered by dragging, as the feed's blocks are (HomeDragController), and a finger
// near the top or bottom scrolls the list. Hide works on each strip; the hidden ones and Reset follow under
// the strips, as in the feed.

/// Numbers of the list (Android HomeEditListTokens).
enum HomeEditListTokens {
    /// The strips grow into place from this scale as the list comes in.
    static let enterScale: CGFloat = 0.94
    static let rowHeight: CGFloat = 64
    static let rowGap: CGFloat = 8
    static let headerHeight: CGFloat = 48
    static let hideButton: CGFloat = 32
    static let handleTouch: CGFloat = 40
    /// Cover edge = strip height − 24, held in 24…36; each cover after the first overlaps by 30 %.
    static var coverSize: CGFloat { min(max(rowHeight - 24, 24), 36) }
    static let coverOverlap: CGFloat = 0.3
}

/// Feed ⇄ list (Android HomeEditListMode): [progress] follows the pinch (shared `homeListPinchProgress`:
/// in to 55 % of the starting span collapses fully, out to 1.8× expands fully), then springs to where the
/// pinch lands ([on]; letting go 35 % of the way or further finishes it, `homeListSettlesOn`). A tick as the
/// pinch crosses the point where letting go would land. Reduce Motion: the list only fades.
@MainActor
@Observable
final class HomeEditListMode {
    /// 0: the feed; 1: the list over it.
    private(set) var progress: CGFloat = 0
    /// Where the list is going: shown (a pinch in landed) or not. Presses go to the list while on.
    private(set) var on = false

    /// The list is drawn: on, or on its way in or out.
    var shown: Bool { on || progress > 0.001 }

    /// Edit mode allows the change now (no block lifted or dragged). Set by the controller.
    @ObservationIgnored var canChange: () -> Bool = { true }

    @ObservationIgnored private let spring = SpringRunner()
    @ObservationIgnored private var pinching = false
    @ObservationIgnored private var pinchFrom = false
    @ObservationIgnored private var armed = false

    /// Two fingers went down in edit mode.
    func pinchStart() {
        guard canChange() else { return }
        spring.stop()
        pinching = true
        pinchFrom = on
        armed = false
    }

    /// The fingers are at [scale] of their starting span: the list follows them.
    func pinchBy(_ scale: CGFloat) {
        guard pinching else { return }
        let p = CGFloat(YoinHomeLayout.shared.listPinchProgress(fromOn: pinchFrom, scale: Float(scale)))
        let lands = YoinHomeLayout.shared.listSettlesOn(fromOn: pinchFrom, progress: Float(p)) != pinchFrom
        if lands != armed {
            armed = lands
            UISelectionFeedbackGenerator().selectionChanged()
        }
        progress = p
    }

    /// A finger lifted: the list lands where the pinch left it.
    func pinchEnd() {
        guard pinching else { return }
        pinching = false
        set(YoinHomeLayout.shared.listSettlesOn(fromOn: pinchFrom, progress: Float(progress)))
    }

    /// Springs to the list ([value]) or the feed.
    func set(_ value: Bool) {
        on = value
        let to: CGFloat = value ? 1 : 0
        if UIAccessibility.isReduceMotionEnabled {
            withAnimation(Yoin.effects) { progress = to }
            return
        }
        spring.run(from: progress, to: to, velocity: 0, spring: .yoin(Tokens.motion.expressiveDefaultSpatial)) { [weak self] in
            self?.progress = $0
        }
    }

    /// The header's switch: list ⇄ full layout, staying in edit mode. Ignored mid-pinch or mid-drag.
    func toggle() {
        guard !pinching, canChange() else { return }
        UISelectionFeedbackGenerator().selectionChanged()
        set(!on)
    }

    /// Edit mode is ending: back to the feed alongside it.
    func close() {
        pinching = false
        if on || progress > 0 { set(false) }
    }

    /// Back to the feed now.
    func snapOff() {
        pinching = false
        on = false
        spring.stop()
        progress = 0
    }
}

/// The compact list over the feed while it is shown: the page's own wash fading in over the feed, a row
/// with the switch back to the full layout, one strip per section, then the tray and Reset. Presses reach
/// it through its own edit recognizer (HomeEditTouchView, `inList`), so a strip lifts and drags as a block
/// does and the list scrolls at its ends.
struct HomeEditList: View {
    @Environment(\.homeScheme) private var scheme
    var edit: HomeEditController
    /// The page's wash (its gradient stops).
    var background: [Color]
    var margin: CGFloat
    /// Up to three covers for [section]'s strip.
    var covers: (HomeSection) -> [CoverPalette]

    var body: some View {
        let s = scheme!
        let p = min(max(edit.list.progress, 0), 1)
        let reduced = UIAccessibility.isReduceMotionEnabled
        let order = edit.listOrder
        ZStack(alignment: .top) {
            LinearGradient(colors: background, startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()
                .opacity(Double(p))
            ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: HomeEditListTokens.rowGap) {
                    // Where the feed's header was: "Edit Home", then the switch back, Undo or Add, and Done.
                    HStack(spacing: 2) {
                        Text("Edit Home")
                            .font(YoinFont.rounded(28, relativeTo: .largeTitle))
                            .foregroundStyle(s.onSurface)
                            .lineLimit(1)
                        Spacer(minLength: 0)
                        HomeEditActions(edit: edit, scheme: s)
                            .onGeometryChange(for: CGRect.self) { $0.frame(in: .named(HomeEditController.listSpace)) } action: {
                                edit.reportExcluded("list.actions", frame: $0, inList: true)
                            }
                    }
                    .frame(minHeight: HomeEditListTokens.headerHeight)
                    .padding(.top, 36)
                    .padding(.bottom, 8)
                    ForEach(Array(order.enumerated()), id: \.element) { i, section in
                        HomeEditListStrip(edit: edit, section: section, index: i, count: order.count, covers: covers(section))
                    }
                    HomeEditTray(edit: edit, inList: true)
                        .padding(.top, 8)
                }
                .padding(.horizontal, margin)
                .coordinateSpace(.named(HomeEditController.listSpace))
                .background(HomeEditTouchView(edit: edit, inList: true))
            }
            .onChange(of: edit.trayRequest) {
                guard edit.list.on else { return }
                withAnimation(Yoin.spatial) { proxy.scrollTo(HomeEditTray.id, anchor: UnitPoint(x: 0.5, y: 0.1)) }
            }
            }
            .contentMargins(.bottom, 24, for: .scrollContent)
            .accessibilityIdentifier("home.edit.list")
            .opacity(Double(p))
            .scaleEffect(reduced ? 1 : HomeEditListTokens.enterScale + (1 - HomeEditListTokens.enterScale) * p, anchor: .top)
        }
        .allowsHitTesting(edit.list.on)
    }
}

/// One section as a strip (Android HomeEditListStrip): its covers, its title, Hide and the drag handle. It
/// follows the finger in its layer while dragged and lifts as the feed's blocks do (scale, shadow, plate
/// tint); one VoiceOver element with the block's position and Move up / Move down / Hide.
struct HomeEditListStrip: View {
    @Environment(\.homeScheme) private var scheme
    var edit: HomeEditController
    var section: HomeSection
    var index: Int
    var count: Int
    var covers: [CoverPalette]

    var body: some View {
        let s = scheme!
        let onTop = edit.drag.drawsOnTop(section)
        let lifted = edit.drag.liftSection == section
        let lift = lifted ? min(max(edit.drag.lift, 0), 1.2) : 0
        let tint = lifted ? min(max(edit.drag.liftTint, 0), 1) : 0
        let size = HomeEditListTokens.coverSize
        HStack(spacing: 12) {
            if !covers.isEmpty {
                HStack(spacing: -size * HomeEditListTokens.coverOverlap) {
                    ForEach(Array(covers.prefix(3).enumerated()), id: \.offset) { i, pal in
                        CoverArt(palette: pal, cornerRadius: Tokens.shape.THUMB.cg)
                            .frame(width: size, height: size)
                            .zIndex(Double(3 - i))
                    }
                }
            }
            Text(section.title)
                .homeType(Tokens.type.titleMedium, .semibold)
                .foregroundStyle(s.onSurface)
                .lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button { edit.hide(section) } label: {
                YoinIcon("visibility_off", size: 18)
                    .foregroundStyle(s.onSurface)
                    .frame(width: HomeEditListTokens.hideButton, height: HomeEditListTokens.hideButton)
                    .background(s.surfaceContainerHighest, in: .circle)
                    .frame(width: 44, height: 44)
                    .contentShape(.rect)
            }
            .buttonStyle(PressScale())
            .disabled(!edit.isEditing || edit.drag.isCarrying)
            .modifier(EditFrameReport(space: HomeEditController.listSpace) { edit.reportExcluded("hide.\(section.id)", frame: $0, inList: true, owner: $1) })
            YoinIcon("drag_handle", size: 24)
                .foregroundStyle(s.onSurfaceVariant)
                .frame(width: HomeEditListTokens.handleTouch, height: HomeEditListTokens.handleTouch)
                .contentShape(.rect)
                .modifier(EditFrameReport(space: HomeEditController.listSpace) { edit.reportHandle(section, frame: $0, inList: true, owner: $1) })
        }
        .padding(.leading, 16)
        .padding(.trailing, 4)
        .frame(height: HomeEditListTokens.rowHeight)
        .background {
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .fill(s.surfaceContainerHigh.mix(with: s.surfaceContainerHighest, by: Double(tint), in: .perceptual))
                .shadow(color: .black.opacity(0.2 * Double(lift)), radius: HomeEditMotionTokens.liftShadow * lift, y: 3 * lift)
        }
        .scaleEffect(1 + HomeEditMotionTokens.liftScale * lift)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(section.title)
        .accessibilityValue(HomeEditAccessibility.position(index + 1, of: count, rows: nil))
        .accessibilityIdentifier("home.edit.strip.\(section.id)")
        .accessibilityActions {
            if index > 0 { Button(String(localized: "Move up")) { edit.move(section, to: index - 1) } }
            if index < count - 1 { Button(String(localized: "Move down")) { edit.move(section, to: index + 1) } }
            Button(String(localized: "Hide")) { edit.hide(section) }
        }
        .modifier(HomeDragFollow(drag: edit.drag, section: section, inList: true) {
            HomeDropSlot(drag: edit.drag, section: section, inList: true, scheme: s)
        })
        .modifier(EditFrameReport(space: HomeEditController.listSpace) { edit.report(section, frame: $0, inList: true, owner: $1) })
        .zIndex(onTop ? 1 : 0)
        .transaction { if onTop { $0.animation = nil } }
        .transition(.opacity.combined(with: .scale(scale: 0.96)))
    }
}
