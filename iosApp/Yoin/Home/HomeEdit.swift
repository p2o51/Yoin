import SwiftUI
import UIKit
import YoinShared

/// Home edit mode, in place (Android ui/home/edit, owner decisions
/// 2026-10-04): whole sections are the unit. Long-press a section (or the
/// footer's Edit Home) and Home becomes editable where it is: plates come up
/// under the sections, each title row gets Hide and a drag handle, the
/// sections sway and settle after 6 s untouched; dragging a handle folds every
/// section into a cover strip, the carried one follows the finger 1:1 and the
/// rest make way, and letting go unfolds them in the new order. Hidden
/// sections wait in the tray under the last one. Every change is saved at once
/// for this account (HomeLayoutStore); Undo walks them back; Done leaves.
///
/// One progress P (0 feed … 1 editing) on the stage-settle spring drives the
/// header's title swap, the plates, the badges' ripple and the tray.
@MainActor
@Observable
final class HomeEditController {
    /// 0 = the feed, 1 = editing (the stage-settle spring).
    private(set) var progress: CGFloat = 0
    private(set) var isEditing = false
    /// The layout being edited (the saved one while not editing).
    private(set) var layout: HomeLayout = .default
    private(set) var undoStack: [HomeLayout] = []
    /// Strips: 0 = sections, 1 = folded into strips (while carrying).
    private(set) var fold: CGFloat = 0
    /// The section being carried, and the live order while it is.
    private(set) var carried: HomeSection?
    private(set) var liveOrder: [HomeSection] = []
    /// The sway's envelope (0 still … 1 swaying); it settles after 6 s untouched.
    private(set) var sway: CGFloat = 0
    /// When a long-press entered edit: the press's own release must not tap the card under it.
    @ObservationIgnored private(set) var enteredAt: Date = .distantPast

    @ObservationIgnored private(set) var scope = ""
    @ObservationIgnored private let store: HomeLayoutStore
    @ObservationIgnored private let stage = SpringRunner()
    @ObservationIgnored private let foldSpring = SpringRunner()
    @ObservationIgnored private let carrySpring = SpringRunner()
    @ObservationIgnored private let swaySpring = SpringRunner()
    @ObservationIgnored private var carryStart = 0
    @ObservationIgnored private var idleTask: Task<Void, Never>?

    nonisolated static let undoMax = 20
    /// The feed's coordinate space (the sections report their frames in it).
    nonisolated static let feedSpace = "homeFeed"
    /// How long a finger holds a section's body before it lifts: out of edit mode the long-press
    /// that enters it; in edit mode a short hold, so a scroll still scrolls (Android's T and slop).
    nonisolated static func holdDuration(editing: Bool) -> TimeInterval { editing ? 0.2 : 0.4 }
    /// IdleSettle: this long without a touch and the sway settles.
    nonisolated static let idleSeconds: Double = 6
    /// A strip's height and the gap between strips (HomeEditTokens §4).
    nonisolated static let stripHeight: CGFloat = 56
    nonisolated static let stripGap: CGFloat = 8
    nonisolated static var stripPitch: CGFloat { stripHeight + stripGap }

    init(store: HomeLayoutStore = .shared) { self.store = store }

    /// The account changed (or Home appeared): its saved layout, unless mid-edit.
    func load(scope: String) {
        guard scope != self.scope || (!isEditing && layout != store.layout(scope)) else { return }
        if scope != self.scope, isEditing { snapExit() }
        self.scope = scope
        layout = store.layout(scope)
        undoStack = []
    }

    /// The sections in the order the feed draws them right now.
    var order: [HomeSection] { hole != nil ? liveOrder : layout.shownSections }

    // MARK: Enter / leave

    func enter() {
        guard !isEditing else { return }
        enteredAt = Date()
        isEditing = true
        undoStack = []
        run(stage, from: progress, to: 1, spring: Self.stageSpring) { [weak self] in self?.progress = $0 }
        touched()
    }

    func exit() {
        guard isEditing else { return }
        isEditing = false
        endCarry(commit: true, velocity: 0)
        idleTask?.cancel()
        run(swaySpring, from: sway, to: 0, spring: .yoin(Tokens.motion.expressiveDefaultSpatial)) { [weak self] in self?.sway = $0 }
        run(stage, from: progress, to: 0, spring: Self.stageSpring) { [weak self] in self?.progress = $0 }
    }

    /// Gone at once (Home left the screen, the account changed).
    func snapExit() {
        isEditing = false
        [stage, foldSpring, carrySpring, swaySpring].forEach { $0.stop() }
        idleTask?.cancel()
        progress = 0; fold = 0; sway = 0; carried = nil; landing = nil
    }

    /// A press under the long-press that entered edit lands on a card: drop that tap.
    var swallowsTap: Bool { isEditing || Date().timeIntervalSince(enteredAt) < 0.8 }

    // MARK: Changes (each one saved, each one an Undo step)

    // MARK: Row presets (D1 resize)

    /// A handle drag's preset while the finger is down; written (one Undo step) on release.
    private(set) var rowsPreview: [HomeSection: HomeRowPreset] = [:]
    /// Each section's resize handle, in the feed's space: a hold on it never lifts the block.
    @ObservationIgnored private var rowsHandleFrames: [HomeSection: CGRect] = [:]

    /// [s]'s row preset as drawn now: the drag's, else the layout's.
    func rowsOf(_ s: HomeSection) -> HomeRowPreset { rowsPreview[s] ?? layout.rowsOf(s) }

    func reportRowsHandle(_ s: HomeSection, frame: CGRect) { rowsHandleFrames[s] = frame }

    func isOverRowsHandle(_ point: CGPoint) -> Bool { rowsHandleFrames.values.contains { $0.contains(point) } }

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

    func hide(_ s: HomeSection) { commit(layout.withEnabled(s, false)) }
    func show(_ s: HomeSection) { commit(layout.withEnabled(s, true)) }
    func move(_ s: HomeSection, to index: Int) { commit(layout.movedShown(s, to: index)) }

    func reset() {
        let next = layout.reset()
        guard !next.sameSections(as: layout) else { return }
        push()
        layout = next
        store.clear(scope)
        touched()
    }

    func undo() {
        guard let prev = undoStack.popLast() else { return }
        withAnimation(Yoin.spatial) { layout = prev }
        store.save(prev, scope: scope)
        touched()
    }

    private func commit(_ next: HomeLayout, animated: Bool = true) {
        guard !next.sameSections(as: layout) else { return }
        push()
        if animated { withAnimation(Yoin.spatial) { layout = next } } else { layout = next }
        store.save(next, scope: scope)
        touched()
    }

    private func push() {
        undoStack.append(layout)
        if undoStack.count > Self.undoMax { undoStack.removeFirst(undoStack.count - Self.undoMax) }
    }

    // MARK: Sway (IdleSettle)

    /// Any touch while editing revives the sway; 6 s without one settles it.
    func touched() {
        guard isEditing else { return }
        if sway < 1 { run(swaySpring, from: sway, to: 1, spring: .yoin(Tokens.motion.expressiveFastSpatial)) { [weak self] in self?.sway = $0 } }
        idleTask?.cancel()
        idleTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(Self.idleSeconds))
            guard let self, !Task.isCancelled, self.carried == nil else { return }
            self.run(self.swaySpring, from: self.sway, to: 0, spring: .yoin(Tokens.motion.expressiveDefaultSpatial)) { [weak self] in self?.sway = $0 }
        }
    }

    /// Port sheet §3: a card-sized rotation, detuned per section, ~2.4 Hz.
    static func swayAngle(_ section: HomeSection, time: TimeInterval, envelope: CGFloat) -> Double {
        guard envelope > 0.0005 else { return 0 }
        let hash = section.id.unicodeScalars.reduce(0) { ($0 &* 31 &+ Int($1.value)) & 0xFFFF }
        let detune = 1 + 0.07 * (Double(hash % 200) / 100 - 1)
        let phase = Double(hash % 628) / 100
        return sin(2 * .pi * 2.4 * detune * time + phase) * 0.35 * Double(envelope)
    }

    // MARK: Carry (fold into strips, reorder, unfold)

    /// Each shown section's frame in the feed's space ("homeFeed"), as laid out.
    @ObservationIgnored private var frames: [HomeSection: CGRect] = [:]
    /// The carried strip's top in the feed's space, under the finger.
    private(set) var floatY: CGFloat = 0
    /// The carried strip is landing in its slot (the finger is up).
    private(set) var landing: HomeSection?
    @ObservationIgnored private var floatStart: CGFloat = 0

    /// The section's block, as laid out (not as drawn).
    func report(_ s: HomeSection, frame: CGRect) { frames[s] = frame }

    /// The shown section whose block holds feed-space [y].
    func section(atY y: CGFloat) -> HomeSection? {
        order.first { frames[$0].map { $0.minY <= y && y <= $0.maxY } ?? false }
    }

    func beginCarry(_ s: HomeSection) {
        guard isEditing, carried == nil, landing == nil, let i = layout.shownSections.firstIndex(of: s) else { return }
        liveOrder = layout.shownSections
        carryStart = i
        floatStart = frames[s]?.minY ?? 0
        floatY = floatStart
        carried = s
        carrySpring.stop()
        idleTask?.cancel()
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        run(foldSpring, from: fold, to: 1, spring: .yoin(Tokens.motion.expressiveDefaultSpatial)) { [weak self] in self?.fold = $0 }
    }

    /// The finger moved [ty] pt from where the carry began: the strip follows
    /// 1:1, and the hole goes where its centre is.
    func carry(_ ty: CGFloat) {
        guard let s = carried else { return }
        floatY = floatStart + ty
        let centre = floatY + Self.stripHeight / 2
        let others = liveOrder.filter { $0 != s }
        let target = Self.slot(centre: centre, others: others.map { frames[$0] ?? .zero })
        if liveOrder.firstIndex(of: s) != target {
            var o = others
            o.insert(s, at: target)
            withAnimation(Yoin.spatial) { liveOrder = o }
            UISelectionFeedbackGenerator().selectionChanged()
        }
    }

    /// Let go: the order is committed (one Undo step), the strip lands in its
    /// hole with the finger's velocity, then the sections unfold.
    func endCarry(commit: Bool, velocity: CGFloat) {
        guard let s = carried else { return }
        let to = liveOrder.firstIndex(of: s) ?? carryStart
        let order = liveOrder
        landing = s
        carried = nil
        let hole = frames[s]?.minY ?? floatY
        carrySpring.run(from: floatY, to: hole, velocity: velocity, spring: .yoin(Tokens.motion.expressiveDefaultSpatial)) { [weak self] in
            self?.floatY = $0
        } completion: { [weak self] in
            guard let self else { return }
            var t = Transaction()
            t.disablesAnimations = true
            withTransaction(t) {
                if commit, to != self.carryStart { self.commit(self.layout.movedShown(s, to: to), animated: false) }
                self.liveOrder = order
                self.landing = nil
            }
            self.run(self.foldSpring, from: self.fold, to: 0, spring: .yoin(Tokens.motion.expressiveDefaultSpatial)) { [weak self] in self?.fold = $0 }
            self.touched()
        }
    }

    /// The hole's index: how many of the other strips' centres lie above [centre].
    nonisolated static func slot(centre: CGFloat, others: [CGRect]) -> Int {
        others.filter { $0.midY < centre }.count
    }

    /// Which section's strip the feed draws in flow as the hole.
    var hole: HomeSection? { carried ?? landing }

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

// MARK: - Edit-mode pieces

/// One section on the feed, editable: the plate that comes up under it, its
/// title-row badges (Hide, the drag handle), and its fold into a strip while
/// one is carried. The carried one is drawn floating (HomeEditStrip, by
/// HomeView) and leaves a hole here.
struct HomeEditBlock<Content: View>: View {
    @Environment(\.homeScheme) private var scheme
    var edit: HomeEditController
    var section: HomeSection
    var index: Int
    /// Up to three covers for the strip.
    var covers: [CoverPalette]
    /// The section's resize handle (Activities, Jump Back In); nil = none.
    var rows: HomeRowsHandle? = nil
    @ViewBuilder var content: () -> Content
    @State private var naturalHeight: CGFloat = 0

    var body: some View {
        let s = scheme!
        let p = edit.progress
        let r = HomeEditController.ripple(p, index: index)
        let f = edit.fold
        let isHole = edit.hole == section
        let height = naturalHeight > 0 ? naturalHeight + (HomeEditController.stripHeight - naturalHeight) * f : nil
        ZStack(alignment: .top) {
            content()
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { naturalHeight = $0 }
                .opacity(1 - smoothstep(0, 0.45, f))
                .allowsHitTesting(!edit.isEditing)
                .fixedSize(horizontal: false, vertical: true)
            if f > 0.001 {
                if isHole {
                    // The hole the carried strip will land in.
                    RoundedRectangle(cornerRadius: 20, style: .continuous)
                        .fill(s.secondaryContainer.opacity(0.35 * Double(smoothstep(0.3, 1, f))))
                        .frame(height: HomeEditController.stripHeight)
                } else {
                    HomeEditStrip(section: section, covers: covers, fold: f)
                }
            }
        }
        .frame(height: height, alignment: .top)
        .frame(maxWidth: .infinity, alignment: .top)
        .background {
            // Plate: the section's container comes up under it (outset 8 / 6).
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .fill(s.surfaceContainer.opacity(Double(r) * Double(1 - smoothstep(0, 0.45, f))))
                .padding(.horizontal, -8)
                .padding(.vertical, -6)
                .scaleEffect(0.96 + 0.04 * r)
        }
        .overlay(alignment: .topTrailing) {
            if p > 0.02 && f < 0.5 { badges(s, r).opacity(Double(1 - smoothstep(0, 0.3, f))) }
        }
        .overlay(alignment: .bottomTrailing) {
            if let rows, rows.stops.count > 1, p > 0.02, f < 0.5 {
                HomeRowsHandleView(edit: edit, section: section, handle: rows, scheme: s, ripple: r)
                    .opacity(Double(1 - smoothstep(0, 0.3, f)))
            }
        }
        .onGeometryChange(for: CGRect.self) { $0.frame(in: .named(HomeEditController.feedSpace)) } action: {
            edit.report(section, frame: $0)
        }
        .modifier(SwayModifier(section: section, envelope: edit.sway * (1 - f)))
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
            .disabled(!edit.isEditing)
            .accessibilityLabel("Hide \(section.title)")
            YoinIcon("drag_handle", size: 24)
                .foregroundStyle(s.onSurfaceVariant)
                .frame(width: 48, height: 48)
                .contentShape(.rect)
                .accessibilityLabel("Reorder \(section.title)")
                .accessibilityActions {
                    Button("Move up") { edit.move(section, to: index - 1) }
                    Button("Move down") { edit.move(section, to: index + 1) }
                }
        }
        .scaleEffect(0.6 + 0.4 * shown)
        .opacity(Double(shown))
        // The 48 targets centre on the 28 title row and overhang the plate's edge.
        .offset(x: 12, y: -10)
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

/// The strip a section folds into (HomeEditTokens §4): three overlapping
/// covers, its title, the handle; washed toward its first cover.
struct HomeEditStrip: View {
    @Environment(\.homeScheme) private var scheme
    var section: HomeSection
    var covers: [CoverPalette]
    var fold: CGFloat
    var lifted = false

    var body: some View {
        let s = scheme!
        HStack(spacing: 12) {
            HStack(spacing: -10) {
                ForEach(Array(covers.prefix(3).enumerated()), id: \.offset) { _, pal in
                    CoverArt(palette: pal, cornerRadius: Tokens.shape.THUMB.cg)
                        .frame(width: 32, height: 32)
                }
            }
            Text(section.title)
                .homeType(Tokens.type.titleMedium, .semibold)
                .foregroundStyle(s.onSurface)
                .lineLimit(1)
                .opacity(Double(smoothstep(0.55, 1, fold)))
            Spacer(minLength: 0)
            YoinIcon("drag_handle", size: 24).foregroundStyle(s.onSurfaceVariant)
        }
        .padding(.horizontal, 16)
        .frame(height: HomeEditController.stripHeight)
        .background {
            let tint = covers.first.map { BackdropColors($0).base } ?? s.surfaceContainerHigh
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .fill(s.surfaceContainerHigh.mix(with: tint, by: 0.3 * Double(fold), in: .perceptual))
                .shadow(color: .black.opacity(lifted ? 0.18 * Double(smoothstep(0.6, 1, fold)) : 0), radius: 6, y: 3)
        }
        .opacity(Double(smoothstep(0, 0.45, fold)))
        .scaleEffect(lifted ? 1.02 : 1)
    }
}

/// The edit-mode sway: a TimelineView of its own, so only this modifier
/// redraws each frame while the envelope is up.
private struct SwayModifier: ViewModifier {
    var section: HomeSection
    var envelope: CGFloat

    func body(content: Content) -> some View {
        if envelope > 0.0005 {
            TimelineView(.animation) { t in
                content.rotationEffect(.degrees(HomeEditController.swayAngle(section, time: t.date.timeIntervalSinceReferenceDate,
                                                                           envelope: envelope)))
            }
        } else {
            content
        }
    }
}

/// The tray under the last section while editing (Android HomeEditTray):
/// "Hidden", one row per hidden section with its Show button, then Reset Home.
struct HomeEditTray: View {
    @Environment(\.homeScheme) private var scheme
    var edit: HomeEditController

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
                .foregroundStyle(edit.layout.isDefault ? s.onSurface.opacity(0.38) : s.primary)
                .padding(.horizontal, 16)
                .frame(height: 48)
            }
            .buttonStyle(PressScale())
            .disabled(edit.layout.isDefault)
            .frame(maxWidth: .infinity)
        }
    }
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

/// The header's end through P: the account button gives way (gone by P .5)
/// to Undo and Done. (Android puts [Undo|Add][Done] in the bar; iOS keeps the
/// bar as it is and puts them where the account button was.)
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
                HStack(spacing: 8) {
                    Button { edit.undo() } label: {
                        YoinIcon("undo", size: 20)
                            .foregroundStyle(scheme.onSurface)
                            .frame(width: 40, height: 40)
                            .background(scheme.surfaceContainerHigh, in: .circle)
                            .frame(width: 48, height: 48)
                    }
                    .buttonStyle(PressScale())
                    .disabled(edit.undoStack.isEmpty)
                    .opacity(edit.undoStack.isEmpty ? 0.38 : 1)
                    .accessibilityLabel("Undo")
                    Button { edit.exit() } label: {
                        Text("Done")
                            .homeType(Tokens.type.labelLarge, .semibold)
                            .foregroundStyle(scheme.onPrimary)
                            .padding(.horizontal, 20)
                            .frame(height: 40)
                            .background(scheme.primary, in: .capsule)
                    }
                    .buttonStyle(PressScale())
                }
                .opacity(Double(smoothstep(0.5, 1, p)))
                .scaleEffect(0.9 + 0.1 * smoothstep(0.5, 1, p), anchor: .trailing)
                .allowsHitTesting(edit.isEditing)
            }
        }
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

/// The carried strip, floating under the finger over the feed (its hole
/// waits in the flow), then landing in it.
struct CarriedStrip: View {
    var edit: HomeEditController
    var covers: [CoverPalette]

    var body: some View {
        if let s = edit.hole {
            HomeEditStrip(section: s, covers: covers, fold: max(edit.fold, 0.0001), lifted: edit.carried != nil)
                .offset(y: edit.floatY)
                .allowsHitTesting(false)
                .zIndex(3)
        }
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
/// Recording aid: `-homeEditScripted YES` enters edit mode, carries the first
/// section two slots down (a finger on its handle), hides the next one, shows
/// it again from the tray, undoes, and leaves with Done.
@MainActor
enum HomeEditScript {
    private static var started = false
    /// The Home on screen's controller (Home is rebuilt once at launch).
    static weak var edit: HomeEditController?

    static func run() async {
        guard UserDefaults.standard.bool(forKey: "homeEditScripted"), !started else { return }
        started = true
        try? await Task.sleep(for: .seconds(3))
        guard let edit else { return }
        edit.enter()
        try? await Task.sleep(for: .seconds(2))
        guard let first = edit.layout.shownSections.first else { return }
        edit.beginCarry(first)
        try? await Task.sleep(for: .milliseconds(450))
        let distance = HomeEditController.stripPitch * 2
        await MemoriesScript.finger(0.9) { edit.carry($0 * distance) }
        try? await Task.sleep(for: .milliseconds(200))
        edit.endCarry(commit: true, velocity: 0)
        try? await Task.sleep(for: .seconds(2))
        if let second = edit.layout.shownSections.first { edit.hide(second) }
        try? await Task.sleep(for: .seconds(1.5))
        if let back = edit.layout.trayed.first { edit.show(back) }
        try? await Task.sleep(for: .seconds(1.5))
        edit.undo()
        try? await Task.sleep(for: .seconds(1.5))
        edit.exit()
    }
}
#endif

/// Home's hold over the whole feed (Android's page-wide edit detector): a
/// finger held on a section's body enters edit mode and lifts it, then the
/// same touch carries it until it lifts. It is a UIKit long-press on the
/// feed's scroll view (found from this view, set behind the feed's stack):
/// it keeps tracking after it begins whatever the views under the finger do
/// (a SwiftUI gesture on the section was cancelled the moment the section
/// folded into its strip), and it runs beside the scroll view's own pan.
struct HomeHoldObserver: UIViewRepresentable {
    var edit: HomeEditController

    func makeUIView(context: Context) -> HoldView { HoldView() }

    func updateUIView(_ v: HoldView, context: Context) {
        v.edit = edit
        v.hold.minimumPressDuration = HomeEditController.holdDuration(editing: edit.isEditing)
    }

    final class HoldView: UIView, UIGestureRecognizerDelegate {
        var edit: HomeEditController?
        let hold = UILongPressGestureRecognizer()
        private var startY: CGFloat = 0
        private var lastY: CGFloat = 0
        private var lastTime: TimeInterval = 0
        private var velocity: CGFloat = 0

        override init(frame: CGRect) {
            super.init(frame: frame)
            isUserInteractionEnabled = false
            hold.minimumPressDuration = HomeEditController.holdDuration(editing: false)
            hold.delegate = self
            hold.addTarget(self, action: #selector(held(_:)))
        }

        required init?(coder: NSCoder) { fatalError() }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            guard window != nil, hold.view == nil else { return }
            var v: UIView? = superview
            while let cur = v, !(cur is UIScrollView) { v = cur.superview }
            v?.addGestureRecognizer(hold)
        }

        func gestureRecognizer(_ g: UIGestureRecognizer,
                               shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }

        @objc private func held(_ g: UILongPressGestureRecognizer) {
            guard let edit else { return }
            // This view lies behind the feed's stack: its space is the feed's.
            let y = g.location(in: self).y
            switch g.state {
            case .began:
                // A hold on a resize handle is the handle's: the section stays put.
                guard !edit.isOverRowsHandle(g.location(in: self)), let section = edit.section(atY: y) else { return }
                if !edit.isEditing { edit.enter() }
                edit.beginCarry(section)
                startY = y; lastY = y; lastTime = CACurrentMediaTime(); velocity = 0
            case .changed:
                guard edit.carried != nil else { return }
                let now = CACurrentMediaTime()
                if now > lastTime { velocity = (y - lastY) / CGFloat(now - lastTime) }
                lastY = y; lastTime = now
                edit.carry(y - startY)
            case .ended, .cancelled, .failed:
                if edit.carried != nil { edit.endCarry(commit: g.state == .ended, velocity: velocity) }
            default: break
            }
        }
    }
}
