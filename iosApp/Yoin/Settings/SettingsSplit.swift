import SwiftUI

/// One of the pages Settings' rows open. In the two-pane Settings it is the row picked in the list and the
/// page shown beside it.
enum SettingsPane: Hashable, Sendable {
    /// An account's own page (ServiceSetupView).
    case account(String)
    /// Add account (ServiceSetupView with no account).
    case addAccount
    case aiFeatures
    case labs
    case cloudSync
    case neodb
    case tips
    case about
    case licenses
}

/// Settings in two panes on a wide iPad window (owner 2026-11-13, I27; Android's Settings list-detail,
/// res/xml/main_split_config.xml and SplitLayout.kt): the list on the left, the page its picked row opens
/// on the right. Narrow windows and the iPhone keep the pushed pages.
enum SettingsSplitLayout {
    /// Android `splitMinWidthDp`: the same 840 the detail column opens at.
    static let minWidth: CGFloat = 840
    /// Android SettingsTablet: the list takes about 420, a third of the window at least and 40 % at most.
    static let listWidth: CGFloat = 420
    static let minShare: CGFloat = 0.3
    static let maxShare: CGFloat = 0.4

    static func splits(window: CGFloat) -> Bool { window >= minWidth }

    /// The list's width in a window `window` wide (Android `settingsSplitRatio`).
    static func listWidth(window: CGFloat) -> CGFloat {
        min(max(listWidth, window * minShare), window * maxShare)
    }

    /// The list's first row, picked when the panes open: the first account, else Add account.
    static func firstRow(accounts: [String]) -> SettingsPane {
        accounts.first.map(SettingsPane.account) ?? .addAccount
    }

    /// The picked row after the accounts changed from `old` to `new`: an account added from Add account
    /// is picked (its page replaces Add account), a removed account's row hands over to the first row.
    static func reconciled(_ pane: SettingsPane, old: [String], new: [String]) -> SettingsPane {
        switch pane {
        case .addAccount:
            if let added = new.first(where: { !old.contains($0) }) { return .account(added) }
            return pane
        case .account(let id):
            return new.contains(id) ? pane : firstRow(accounts: new)
        default:
            return pane
        }
    }
}

/// The list's side of the two panes: which row is picked, and picking one.
struct SettingsListSelection {
    var picked: SettingsPane?
    var pick: (SettingsPane) -> Void
}

extension EnvironmentValues {
    /// Set on the list of the two-pane Settings: its page rows pick instead of pushing.
    @Entry var settingsList: SettingsListSelection? = nil
    /// Set on the page side of the two-pane Settings.
    @Entry var inSettingsPane = false
}

/// The two-pane Settings, the whole window under the dock: the list on surfaceDim, the picked page on
/// its own surfaceContainer, the picked row in the page's colour so it reads as attached to it (Android
/// SettingsColors `listPane`). The list's ← closes Settings; the page has no ← of its own, the pages it
/// pushes do.
struct SettingsSplitView: View {
    /// The page side's pushed routes (`model.path` after `.settings`).
    var tail: Binding<[Route]>
    var destination: (Route) -> AnyView
    @Environment(AppModel.self) private var model
    @State private var picked: SettingsPane?

    var body: some View {
        GeometryReader { geo in
            let list = SettingsSplitLayout.listWidth(window: geo.size.width)
            HStack(spacing: 0) {
                ServerSettingsView()
                    .environment(\.settingsList, SettingsListSelection(picked: picked, pick: pick))
                    .frame(width: list)
                Group {
                    if let picked {
                        NavigationStack(path: tail) {
                            page(picked)
                                .navigationDestination(for: Route.self) { destination($0) }
                        }
                        .environment(\.inSettingsPane, true)
                        // Another row: a fresh stack (whatever the last page pushed goes with it).
                        .id(picked)
                        .transition(.opacity)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .animation(Yoin.effects, value: picked)
            }
        }
        .onAppear {
            if !takeDestination(), picked == nil {
                picked = SettingsSplitLayout.firstRow(accounts: model.profiles.profiles.map(\.id))
            }
        }
        .onChange(of: model.settingsDestination) { _ = takeDestination() }
        .onChange(of: model.profiles.profiles.map(\.id)) { old, new in
            guard let now = picked else { return }
            let next = SettingsSplitLayout.reconciled(now, old: old, new: new)
            if next != now { pick(next) }
        }
        #if DEBUG
        // `-settingsPick <pane>` (QA screenshots): the row to pick once the panes are up
        // (ai, labs, sync, neodb, tips, about, licenses, add, active).
        .task {
            guard let p = UserDefaults.standard.string(forKey: "settingsPick") else { return }
            try? await Task.sleep(for: .milliseconds(700))
            let panes: [String: SettingsPane] = ["ai": .aiFeatures, "labs": .labs, "sync": .cloudSync,
                                                 "neodb": .neodb, "tips": .tips, "about": .about,
                                                 "licenses": .licenses, "add": .addAccount]
            if p == "active", let id = model.profiles.activeId { pick(.account(id)) } else if let pane = panes[p] { pick(pane) }
        }
        #endif
    }

    private func pick(_ pane: SettingsPane) {
        // The page side starts over on the new row.
        if !tail.wrappedValue.isEmpty { tail.wrappedValue = [] }
        picked = pane
    }

    /// Another screen asked for one of the pages (AppModel.openSettings(_:)): it is picked.
    private func takeDestination() -> Bool {
        guard let d = model.settingsDestination else { return false }
        model.settingsDestination = nil
        switch d {
        case .neodb: pick(.neodb)
        case .account(let id): pick(.account(id))
        }
        return true
    }

    @ViewBuilder
    private func page(_ pane: SettingsPane) -> some View {
        switch pane {
        case .account(let id): ServiceSetupView(request: ServiceSetupRequest(profileId: id))
        case .addAccount: ServiceSetupView(request: ServiceSetupRequest())
        case .aiFeatures: AIFeaturesPage()
        case .labs: LabsView()
        case .cloudSync: CloudSyncView()
        case .neodb: NeoDBSettingsPage()
        case .tips: TipsView(focus: nil)
        case .about: AboutYoinView()
        case .licenses: OpenSourceLicensesView()
        }
    }
}

/// A page pushed on the two-pane Settings' page side, as ⌘[ pops it.
struct SettingsPanePop {
    let token: UUID
    let pop: DismissAction
}

/// A Settings page's ←: the two-pane Settings' page side has none at its root (the list is beside it,
/// and its own ← closes Settings); a page pushed there keeps it, and lets ⌘[ pop it
/// (`AppModel.settingsPanePops`).
struct SettingsPaneBack: ViewModifier {
    @Environment(AppModel.self) private var model
    @Environment(\.inSettingsPane) private var inPane
    @Environment(\.isPresented) private var isPresented
    @Environment(\.dismiss) private var dismiss
    @State private var token = UUID()

    private var paneRoot: Bool { inPane && !isPresented }

    func body(content: Content) -> some View {
        content
            .opacity(paneRoot ? 0 : 1)
            .allowsHitTesting(!paneRoot)
            .accessibilityHidden(paneRoot)
            .onAppear {
                guard inPane, isPresented else { return }
                model.settingsPanePops.removeAll { $0.token == token }
                model.settingsPanePops.append(.init(token: token, pop: dismiss))
            }
            .onDisappear { model.settingsPanePops.removeAll { $0.token == token } }
    }
}

extension View {
    /// On a Settings page's ← (SettingsPaneBack).
    func settingsPaneBack() -> some View { modifier(SettingsPaneBack()) }

    /// A row's segment in a Settings group; in the two-pane list, the picked row takes the page's colour
    /// (surfaceContainer) and rounds all its corners (Android SelectableSegment: 28, the spatial spring
    /// for the shape, the effects spring for the colour).
    func settingsSegment(_ i: Int, _ count: Int, row: Color, app: CoverScheme, picked: Bool = false) -> some View {
        settingsSegment(CloudSyncView.segment(i, count), row: row, app: app, picked: picked)
    }

    /// The same on a segment shape a row already has.
    func settingsSegment(_ shape: UnevenRoundedRectangle, row: Color, app: CoverScheme, picked: Bool) -> some View {
        modifier(SettingsSegmentBackground(radii: shape.cornerRadii, row: row, app: app, picked: picked))
    }
}

private struct SettingsSegmentBackground: ViewModifier {
    var radii: RectangleCornerRadii
    var row: Color
    var app: CoverScheme
    var picked: Bool

    /// Android SettingsSelectedRowShape.
    static let pickedRadius: CGFloat = 28

    func body(content: Content) -> some View {
        let r = picked ? RectangleCornerRadii(topLeading: Self.pickedRadius, bottomLeading: Self.pickedRadius,
                                              bottomTrailing: Self.pickedRadius, topTrailing: Self.pickedRadius)
                       : radii
        let shape = UnevenRoundedRectangle(cornerRadii: r, style: .circular)
        content
            .background {
                ZStack {
                    shape.fill(row)
                    shape.fill(app.surfaceContainer)
                        .animation(Yoin.effects) { $0.opacity(picked ? 1 : 0) }
                }
                .animation(Yoin.spatial, value: picked)
            }
            .accessibilityAddTraits(picked ? .isSelected : [])
    }
}

/// Settings › Features › AI features, its own page.
struct AIFeaturesPage: View {
    var body: some View {
        SettingsPage(title: String(localized: "AI features")) { app, row in
            GeminiSettingsSection(app: app, row: row, page: true)
        }
    }
}
