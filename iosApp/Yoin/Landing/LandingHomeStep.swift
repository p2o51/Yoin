import SwiftUI

// The Home step (Android ui/landing/LandingHomeStep.kt, LandingViewModel.finish): every section Home
// can show, on or off and in order, before the first Home is drawn. The choice is written for each
// account added during the landing (and, on a re-run, the one in use) through the same per-account
// `HomeLayoutStore` Home's edit mode writes, when "Open Yoin" is pressed.

/// The step's logic, apart from the screen.
enum LandingHomeLayout {
    /// The rows the step lists: every section iOS can draw, hidden ones included (the layout also carries
    /// sections iOS keeps but doesn't draw; they keep their slots).
    static func rows(_ layout: HomeLayout) -> [HomeSectionState] {
        layout.sections.filter(\.section.supportedOnIOS)
    }

    /// Moves the row at [from] to [to] among the listed rows, hidden ones included. The slots of the
    /// sections iOS doesn't list stay where they are.
    static func move(_ layout: HomeLayout, from: Int, to: Int) -> HomeLayout {
        let slots = layout.sections.indices.filter { layout.sections[$0].section.supportedOnIOS }
        guard slots.indices.contains(from), slots.indices.contains(to), from != to else { return layout }
        var rows = slots.map { layout.sections[$0] }
        rows.insert(rows.remove(at: from), at: to)
        var next = layout
        for (slot, row) in zip(slots, rows) { next.sections[slot] = row }
        return next
    }

    /// Android `finish`: a layout equal to the default (nothing retained either) clears the account back
    /// to "never customized"; anything else is saved.
    @MainActor
    static func write(_ layout: HomeLayout, scope: String, to store: HomeLayoutStore = .shared) {
        if layout.isDefault && layout.retained.isEmpty { store.clear(scope) } else { store.save(layout, scope: scope) }
    }

    /// The Home layout scope of [profile]'s account (`LibraryStore.homeScope`: provider|account), nil when
    /// it can't be known yet.
    @MainActor
    static func homeScope(of profile: Profile) -> String? {
        switch profile.service {
        case .subsonic: profile.subsonicScope.map { "\(AccountScope.subsonicProvider)|\($0)" }
        case .spotify: profile.spotifyUserId.map { "\(AccountScope.spotifyProvider)|\(AccountScope.spotifyScope(userId: $0))" }
        case .appleMusic: "\(AppleMusicMapping.provider)|\(AppleMusicMapping.provider)"
        }
    }

    /// Which accounts get the layout: every one added in this landing, and on a re-run the one in use.
    static func targets(created: [Profile], active: Profile?, mode: LandingMode) -> [Profile] {
        var out = created
        if mode == .rerun, let active, !out.contains(where: { $0.id == active.id }) { out.append(active) }
        return out
    }
}

struct LandingHomeScene: View {
    var layout: HomeLayout
    var scheme: CoverScheme
    var onEnabled: (HomeSection, Bool) -> Void
    var onMove: (Int, Int) -> Void

    private static let rowHeight: CGFloat = 64
    private static let gap: CGFloat = 2
    private var step: CGFloat { Self.rowHeight + Self.gap }

    @State private var lifted: HomeSection?
    @State private var liftedFrom = 0
    @State private var drag: CGFloat = 0
    @State private var ticks = 0
    @State private var toggles = 0
    @State private var drops = 0

    var body: some View {
        let rows = LandingHomeLayout.rows(layout)
        LandingSceneColumn {
            ZStack(alignment: .top) {
                ForEach(Array(rows.enumerated()), id: \.element.section) { index, state in
                    row(state, index: index, count: rows.count)
                }
            }
            .frame(height: CGFloat(rows.count) * step - Self.gap, alignment: .top)
            Text("To change this later, long-press Home.")
                .yoinRole(Tokens.type.bodySmall)
                .foregroundStyle(scheme.onSurfaceVariant)
                .padding(.horizontal, 8)
                .padding(.top, 10)
        }
        .sensoryFeedback(.impact(weight: .light), trigger: ticks)
        .sensoryFeedback(.selection, trigger: toggles)
        .sensoryFeedback(.success, trigger: drops)
    }

    @ViewBuilder
    private func row(_ state: HomeSectionState, index: Int, count: Int) -> some View {
        let section = state.section
        let isLifted = lifted == section
        let top: CGFloat = index == 0 || isLifted ? 20 : 4
        let bottom: CGFloat = index == count - 1 || isLifted ? 20 : 4
        let y = isLifted ? min(max(CGFloat(liftedFrom) * step + drag, 0), CGFloat(count - 1) * step) : CGFloat(index) * step
        HStack(spacing: 0) {
            YoinIcon("drag_handle", size: 20)
                .foregroundStyle(scheme.onSurfaceVariant)
                .frame(width: 40, height: Self.rowHeight)
                .contentShape(.rect)
                .highPriorityGesture(dragGesture(section, index: index, count: count))
                .accessibilityLabel(Text("Reorder \(section.title)"))
            SectionGlyph(section: section, color: scheme.primary)
                .frame(width: 36, height: 36)
                .opacity(state.enabled ? 1 : 0.5)
            VStack(alignment: .leading, spacing: 0) {
                Text(section.title)
                    .yoinRole(Tokens.type.titleMedium, weight: .semibold)
                    .foregroundStyle(scheme.onSurface.opacity(state.enabled ? 1 : 0.5))
                    .lineLimit(1)
                Text(LocalizedStringKey(section.supportingText))
                    .yoinRole(Tokens.type.bodySmall)
                    .foregroundStyle(scheme.onSurfaceVariant)
                    .lineLimit(1)
            }
            .padding(.leading, 12)
            Spacer(minLength: 8)
            Toggle("", isOn: Binding(get: { state.enabled }, set: { on in
                toggles += 1
                onEnabled(section, on)
            }))
            .labelsHidden()
            .accessibilityLabel(Text(section.title))
            .padding(.trailing, 12)
        }
        .frame(height: Self.rowHeight)
        .background(isLifted ? scheme.surfaceContainerHighest : scheme.surfaceBright,
                    in: UnevenRoundedRectangle(topLeadingRadius: top, bottomLeadingRadius: bottom,
                                               bottomTrailingRadius: bottom, topTrailingRadius: top))
        .scaleEffect(isLifted ? 1.02 : 1)
        .offset(y: y)
        .zIndex(isLifted ? 1 : 0)
        .animation(isLifted ? nil : Yoin.spatial, value: y)
        .animation(Yoin.spatialFast, value: isLifted)
        .accessibilityElement(children: .contain)
        .accessibilityAction(named: Text("Move up")) { if index > 0 { onMove(index, index - 1); ticks += 1 } }
        .accessibilityAction(named: Text("Move down")) { if index < count - 1 { onMove(index, index + 1); ticks += 1 } }
    }

    /// The row follows the finger one to one; each slot it passes swaps the list under it.
    private func dragGesture(_ section: HomeSection, index: Int, count: Int) -> some Gesture {
        DragGesture(minimumDistance: 2, coordinateSpace: .global)
            .onChanged { v in
                if lifted == nil {
                    lifted = section
                    liftedFrom = index
                    ticks += 1
                }
                guard lifted == section else { return }
                drag = v.translation.height
                let current = LandingHomeLayout.rows(layout).firstIndex { $0.section == section } ?? index
                let target = min(max(liftedFrom + Int((drag / step).rounded()), 0), count - 1)
                if target != current {
                    onMove(current, target)
                    ticks += 1
                }
            }
            .onEnded { _ in
                let moved = LandingHomeLayout.rows(layout).firstIndex { $0.section == section } != liftedFrom
                if moved { drops += 1 }
                withAnimation(Yoin.spatial) {
                    lifted = nil
                    drag = 0
                }
            }
    }
}

/// A 36pt sketch of the section's layout on Home, in the primary colour (Android SectionGlyph).
private struct SectionGlyph: View {
    var section: HomeSection
    var color: Color

    var body: some View {
        Canvas { ctx, size in
            let u = size.width / 36
            func box(_ x: CGFloat, _ y: CGFloat, _ w: CGFloat, _ h: CGFloat, _ a: Double, _ r: CGFloat = 2.5) {
                ctx.fill(Path(roundedRect: CGRect(x: x * u, y: y * u, width: w * u, height: h * u), cornerRadius: r * u),
                         with: .color(color.opacity(a)))
            }
            func dot(_ x: CGFloat, _ y: CGFloat, _ r: CGFloat, _ a: Double) {
                ctx.fill(Path(ellipseIn: CGRect(x: (x - r) * u, y: (y - r) * u, width: 2 * r * u, height: 2 * r * u)),
                         with: .color(color.opacity(a)))
            }
            switch section {
            case .activities:
                box(4, 6, 16, 24, 1, 3); box(22, 6, 10, 11, 0.55, 3); box(22, 19, 10, 11, 0.3, 3)
            case .jumpBackIn:
                for c in 0..<3 { for r in 0..<2 { box(4 + CGFloat(c) * 10, 8 + CGFloat(r) * 10, 8, 8, r == 0 ? 0.9 : 0.45, 2) } }
            case .recentlyAdded:
                for c in 0..<3 { box(3 + CGFloat(c) * 10.5, 12, 9, 9, 0.9 - Double(c) * 0.25, 2) }
                box(3, 24, 14, 2.5, 0.35, 1.2)
            case .rediscover:
                box(5, 10, 15, 15, 0.45, 3); box(14, 12, 15, 15, 1, 3)
            case .recentlyPlayed:
                for c in 0..<3 { dot(8.5 + CGFloat(c) * 9.5, 17, 4.3, 0.9 - Double(c) * 0.25) }
                box(6, 25, 22, 2.4, 0.35, 1.2)
            case .yourPlaylists:
                for r in 0..<3 {
                    box(5, 8 + CGFloat(r) * 7.5, 6, 6, 0.9 - Double(r) * 0.2, 1.5)
                    box(14, 9.8 + CGFloat(r) * 7.5, 16 - CGFloat(r) * 3, 2.4, 0.4, 1.2)
                }
            case .otherProfiles:
                box(4, 10, 12, 12, 0.9, 3); box(19, 10, 12, 12, 0.55, 3)
                box(4, 25, 8, 2.4, 0.35, 1.2); box(19, 25, 8, 2.4, 0.35, 1.2)
            case .lyricHighlight:
                box(5, 9, 24, 17, 0.45, 3)
                box(8, 13, 17, 2.4, 0.9, 1.2); box(8, 18, 12, 2.4, 0.9, 1.2)
            case .memoryGrid:
                for c in 0..<5 { for r in 0..<3 { box(4 + CGFloat(c) * 5.6, 9 + CGFloat(r) * 5.6, 4.4, 4.4, (c + r) % 3 == 0 ? 0.9 : 0.3, 1) } }
            }
        }
        .accessibilityHidden(true)
    }
}

#Preview("Home step") {
    LandingHomeScene(layout: .default, scheme: .brand(dark: false), onEnabled: { _, _ in }, onMove: { _, _ in })
        .background(CoverScheme.brand(dark: false).surfaceContainer)
}
