import SwiftUI
import YoinShared

/// Settings, a pushed page (PushPage): Home's or Library's account avatar →
/// here, back (button or edge swipe) → where it was opened. The accounts lead
/// (AccountCards.swift: switch on tap, manage or remove from a card's menu);
/// Add account and each account's page are the service setup page
/// (ServiceSetupView), the only place that says what a service does.
///
/// Styled after Android's Settings visual system (ui/settings
/// SettingsComponents.kt, Pixel Settings): a plain page on surfaceContainer,
/// a large title, small primary group labels 8 inside the rows' edge, and
/// each row its own segment on surfaceBright (20 outer / 4 inner corners,
/// 2 apart). Labels only, no instructions.
struct ServerSettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme
    /// The service setup page, when pushed (Add account, or an account's page).
    @State private var setup: ServiceSetupRequest?
    /// Settings › Sync and storage › NeoDB, open (also straight from the scrapbook's "Sign in to sync").
    @State private var neodbOpen = false
    /// The two-pane Settings' list (a wide iPad window): page rows pick the page beside it.
    @Environment(\.settingsList) private var list

    private var apple: AppleMusicSource { model.appleMusic }
    private var active: ActiveSource { model.sources.active }
    private var app: CoverScheme {
        model.isIdle ? .brand(dark: colorScheme == .dark)
                     : CoverScheme(album: model.playback.album, dark: colorScheme == .dark)
    }
    /// Pixel's rule: page surfaceContainer, rows surfaceBright; the two-pane list steps down to
    /// surfaceDim, the page beside it keeps surfaceContainer (Android SettingsColors `listPane`).
    private var page: Color { list == nil ? app.surfaceContainer : app.surfaceDim }
    private var row: Color { app.surfaceBright }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                Text("Settings")
                    .yoinRole(Tokens.type.displaySmall)
                    .foregroundStyle(app.onSurface)
                    .padding(.horizontal, 8)
                    .padding(.top, 8)
                AccountsSection(app: app, row: row,
                                onAdd: { open(.addAccount) { setup = ServiceSetupRequest() } },
                                onManage: { p in open(.account(p.id)) { setup = ServiceSetupRequest(profileId: p.id) } })
                // 51 2026-11-09 (Android #191): Features / Sync and storage / Help and about, every row a page.
                featuresGroup
                StorageSettingsSection(app: app, row: row, neodbOpen: $neodbOpen)
                aboutGroup
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 40 + ShellBarMetrics.clearance)
            .animation(Yoin.spatial, value: active)
            .animation(Yoin.effects, value: apple.authorization)
        }
        .safeAreaBar(edge: .top) { topBar }
        .background(page.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .navigationDestination(item: $setup) { ServiceSetupView(request: $0) }
        .navigationDestination(isPresented: $neodbOpen) { NeoDBSettingsPage() }
        .onAppear {
            apple.refreshAuthorization()
            takeDestination()
        }
        .onChange(of: model.settingsDestination) { takeDestination() }
        #if DEBUG
        // `-settingsOpenSetup add|active|apple` (QA screenshots): Add account, the account in use's page, or
        // the Apple Music account's page (its Song names group; no MusicKit sign-in on a simulator).
        .task {
            guard list == nil, let open = UserDefaults.standard.string(forKey: "settingsOpenSetup") else { return }
            try? await Task.sleep(for: .milliseconds(900))
            setup = ServiceSetupRequest(profileId: open == "active" ? model.profiles.activeId
                                        : open == "apple" ? ProfileStore.appleMusicId : nil)
        }
        // `-openWelcomeGuide YES` (QA): About › Welcome guide, a beat after the page opens.
        .task {
            guard UserDefaults.standard.bool(forKey: "openWelcomeGuide") else { return }
            try? await Task.sleep(for: .milliseconds(1200))
            model.openWelcomeGuide()
        }
        #endif
    }

    // MARK: Pieces

    private var topBar: some View {
        HStack {
            // The two-pane list's ← closes Settings, whatever the page beside it has pushed.
            Button { if list == nil { model.back() } else { model.closeSettings() } } label: {
                YoinIcon("back", size: 22)
                    .foregroundStyle(app.onSurface)
                    .frame(width: 40, height: 40)
                    .contentShape(.circle)
            }
            .buttonStyle(.plain)
            .glassEffect(.regular.interactive(), in: .circle)
            .frame(width: 48, height: 48)
            .accessibilityLabel("Back")
            Spacer()
        }
        .padding(.leading, 8)
        .padding(.vertical, 4)
    }

    /// A row in a group: what it shows, and the page it opens (its pick in the two-pane list).
    private struct Row {
        var pane: SettingsPane?
        var view: AnyView
        init(_ view: some View, pane: SettingsPane? = nil) { self.view = AnyView(view); self.pane = pane }
    }

    /// Group label (labelLarge primary, 8 in) over its rows as segments.
    private func group(_ title: String, rows: () -> [Row]) -> some View {
        let items = rows()
        return VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .yoinRole(Tokens.type.labelLarge)
                .foregroundStyle(app.primary)
                .padding(.horizontal, 8)
                .accessibilityAddTraits(.isHeader)
            VStack(spacing: 2) {
                ForEach(items.indices, id: \.self) { i in
                    items[i].view
                        .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
                        .settingsSegment(i, items.count, row: row, app: app,
                                         picked: items[i].pane != nil && items[i].pane == list?.picked)
                }
            }
        }
    }

    /// A page row: in the two-pane list it picks `pane`; otherwise `push` pushes the page.
    private func open(_ pane: SettingsPane, push: () -> Void) {
        if let list { list.pick(pane) } else { push() }
    }

    /// A row that opens one of Settings' pages: pushed, or picked beside the two-pane list.
    private func pageRow(_ title: String, summary: String? = nil, pane: SettingsPane,
                         @ViewBuilder destination: @escaping () -> some View) -> Row {
        if let list {
            return Row(Button { list.pick(pane) } label: {
                SettingsRowLabel(title: title, summary: summary, app: app, trailing: "chevron_right")
            }
            .buttonStyle(.plain), pane: pane)
        }
        return Row(SettingsNavRow(title: title, summary: summary, app: app, destination: destination), pane: pane)
    }

    /// Another screen asked for one of the pages (AppModel.openSettings(_:)): opened once. The two-pane
    /// Settings takes it itself (SettingsSplitView).
    private func takeDestination() {
        guard list == nil, let d = model.settingsDestination else { return }
        model.settingsDestination = nil
        switch d {
        case .neodb: neodbOpen = true
        case .account(let id): setup = ServiceSetupRequest(profileId: id)
        }
    }

    // MARK: Features

    /// Features: AI features and Yoin Labs (Android #189 #191).
    private var featuresGroup: some View {
        group(String(localized: "Features")) {
            [pageRow(String(localized: "AI features"), summary: geminiSummary, pane: .aiFeatures) {
                AIFeaturesPage()
             },
             pageRow("Yoin Labs", summary: String(localized: "Experimental features"), pane: .labs) {
                LabsView()
             }]
        }
    }

    private var geminiSummary: String {
        let g = GeminiSettings.shared
        return g.apiKey.isEmpty ? String(localized: "Song info, Ask Gemini, translation and Memory titles") : g.targetLanguage
    }

    // MARK: Help and about

    /// Android #191's last group, in its order: Tips, Welcome guide, Docs, Send feedback, Yoin (the
    /// version; the website and privacy policy inside), Open source licenses; and iOS's Export log.
    private var aboutGroup: some View {
        group(String(localized: "Help and about")) {
            [Row(TipsEntryRow(app: app), pane: .tips),
             Row(Button { model.openWelcomeGuide() } label: {
                SettingsRowLabel(title: String(localized: "Welcome guide"), app: app)
             }
             .buttonStyle(.plain)),
             Row(aboutLink(String(localized: "Docs"), AboutLinks.docs(language: AboutLinks.currentLanguage))),
             Row(aboutLink(String(localized: "Send feedback"), AboutLinks.feedback)),
             // Playback diagnostics, shared from the phone (Spotify issues away from the Mac).
             Row(ShareLink(item: PlayTraceExport(), preview: SharePreview("Yoin log")) {
                SettingsRowLabel(title: String(localized: "Export log"), summary: String(localized: "Playback diagnostics"),
                                 app: app, trailing: "share")
             }
             .buttonStyle(.plain)),
             pageRow("Yoin", summary: String(localized: "Version \(AboutLinks.versionName())"), pane: .about) {
                AboutYoinView()
             },
             pageRow(String(localized: "Open source licenses"), pane: .licenses) {
                OpenSourceLicensesView()
             }]
        }
    }

    /// A row that opens a public page in the browser.
    private func aboutLink(_ title: String, _ url: URL) -> some View {
        Link(destination: url) {
            HStack {
                Text(title)
                    .yoinRole(Tokens.type.bodyLarge)
                    .foregroundStyle(app.onSurface)
                Spacer(minLength: 16)
                Image(systemName: "arrow.up.right").font(.system(size: 16, weight: .medium)).foregroundStyle(app.onSurfaceVariant)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
            .contentShape(.rect)
        }
    }

    // MARK: Apple Music

    static func subscriptionText(_ s: AppleMusicSource.Subscription) -> String {
        if s.canPlayCatalogContent { return String(localized: "Active") }
        return s.canBecomeSubscriber ? String(localized: "Not subscribed") : String(localized: "Unavailable")
    }
}
