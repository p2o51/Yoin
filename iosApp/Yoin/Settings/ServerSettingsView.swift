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

    private var apple: AppleMusicSource { model.appleMusic }
    private var active: ActiveSource { model.sources.active }
    private var app: CoverScheme {
        model.isIdle ? .brand(dark: colorScheme == .dark)
                     : CoverScheme(album: model.playback.album, dark: colorScheme == .dark)
    }
    /// Pixel's rule: page surfaceContainer, rows surfaceBright.
    private var page: Color { app.surfaceContainer }
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
                                onAdd: { setup = ServiceSetupRequest() },
                                onManage: { setup = ServiceSetupRequest(profileId: $0.id) })
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
        // `-settingsOpenSetup add|active` (QA screenshots): Add account, or the account in use's page.
        .task {
            guard let open = UserDefaults.standard.string(forKey: "settingsOpenSetup") else { return }
            try? await Task.sleep(for: .milliseconds(900))
            setup = ServiceSetupRequest(profileId: open == "active" ? model.profiles.activeId : nil)
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
            Button { model.back() } label: {
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

    /// Group label (labelLarge primary, 8 in) over its rows as segments.
    private func group(_ title: String, rows: () -> [AnyView]) -> some View {
        let items = rows()
        return VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .yoinRole(Tokens.type.labelLarge)
                .foregroundStyle(app.primary)
                .padding(.horizontal, 8)
                .accessibilityAddTraits(.isHeader)
            VStack(spacing: 2) {
                ForEach(items.indices, id: \.self) { i in
                    items[i]
                        .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
                        .background(row, in: segment(i, items.count))
                }
            }
        }
    }

    private func segment(_ i: Int, _ count: Int) -> UnevenRoundedRectangle {
        let top: CGFloat = i == 0 ? 20 : 4
        let bottom: CGFloat = i == count - 1 ? 20 : 4
        return UnevenRoundedRectangle(topLeadingRadius: top, bottomLeadingRadius: bottom,
                                      bottomTrailingRadius: bottom, topTrailingRadius: top, style: .circular)
    }

    /// Another screen asked for one of the pages (AppModel.openSettings(_:)): opened once.
    private func takeDestination() {
        guard let d = model.settingsDestination else { return }
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
            [AnyView(SettingsNavRow(title: String(localized: "AI features"), summary: geminiSummary, app: app) {
                SettingsPage(title: String(localized: "AI features")) { app, row in
                    GeminiSettingsSection(app: app, row: row, page: true)
                }
            }),
             AnyView(SettingsNavRow(title: "Yoin Labs", summary: String(localized: "Experimental features"), app: app) {
                LabsView()
             })]
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
            [AnyView(TipsEntryRow(app: app)),
             AnyView(Button { model.openWelcomeGuide() } label: {
                SettingsRowLabel(title: String(localized: "Welcome guide"), app: app)
             }
             .buttonStyle(.plain)),
             AnyView(aboutLink(String(localized: "Docs"), AboutLinks.docs(language: AboutLinks.currentLanguage))),
             AnyView(aboutLink(String(localized: "Send feedback"), AboutLinks.feedback)),
             // Playback diagnostics, shared from the phone (Spotify issues away from the Mac).
             AnyView(ShareLink(item: PlayTraceExport(), preview: SharePreview("Yoin log")) {
                SettingsRowLabel(title: String(localized: "Export log"), summary: String(localized: "Playback diagnostics"),
                                 app: app, trailing: "share")
             }
             .buttonStyle(.plain)),
             AnyView(SettingsNavRow(title: "Yoin", summary: String(localized: "Version \(AboutLinks.versionName())"), app: app) {
                AboutYoinView()
             }),
             AnyView(SettingsNavRow(title: String(localized: "Open source licenses"), app: app) {
                OpenSourceLicensesView()
             })]
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
