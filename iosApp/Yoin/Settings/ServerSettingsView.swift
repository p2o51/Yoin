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
                GeminiSettingsSection(app: app, row: row)
                NeoDBSettingsSection(app: app, row: row)
                StorageSettingsSection(app: app, row: row)
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
        .onAppear { apple.refreshAuthorization() }
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

    // MARK: About

    /// Android Settings › About › Welcome guide: the landing again, over everything.
    private var aboutGroup: some View {
        group(String(localized: "About")) {
            [AnyView(Button { model.openWelcomeGuide() } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Welcome guide")
                        .yoinRole(Tokens.type.bodyLarge)
                        .foregroundStyle(app.onSurface)
                    Text("Accounts")
                        .yoinRole(Tokens.type.bodyMedium)
                        .foregroundStyle(app.onSurfaceVariant)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 14)
                .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
                .contentShape(.rect)
            }
            .buttonStyle(.plain)),
             // Android Settings › About › Tips.
             AnyView(TipsEntryRow(app: app)),
             AnyView(aboutLink(String(localized: "Website"), AboutLinks.website(language: AboutLinks.currentLanguage))),
             AnyView(aboutLink(String(localized: "Docs"), AboutLinks.docs(language: AboutLinks.currentLanguage))),
             AnyView(aboutLink(String(localized: "Send feedback"), AboutLinks.feedback)),
             AnyView(aboutLink(String(localized: "Privacy policy"), AboutLinks.privacyPolicy)),
             AnyView(NavigationLink { OpenSourceLicensesView() } label: {
                HStack {
                    Text("Open source licenses")
                        .yoinRole(Tokens.type.bodyLarge)
                        .foregroundStyle(app.onSurface)
                    Spacer(minLength: 16)
                    YoinIcon("chevron_right", size: 20).foregroundStyle(app.onSurfaceVariant)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 14)
                .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
                .contentShape(.rect)
            }
            .buttonStyle(.plain)),
             // Playback diagnostics, shared from the phone (Spotify issues away from the Mac).
             AnyView(ShareLink(item: PlayTraceExport(), preview: SharePreview("Yoin log")) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Export log")
                        .yoinRole(Tokens.type.bodyLarge)
                        .foregroundStyle(app.onSurface)
                    Text("Playback diagnostics")
                        .yoinRole(Tokens.type.bodyMedium)
                        .foregroundStyle(app.onSurfaceVariant)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 14)
                .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
                .contentShape(.rect)
            }
            .buttonStyle(.plain)),
             // Android's last About row: the app's name and version.
             AnyView(VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: "Yoin")
                    .yoinRole(Tokens.type.bodyLarge)
                    .foregroundStyle(app.onSurface)
                Text(String(localized: "Version \(AboutLinks.versionName())"))
                    .yoinRole(Tokens.type.bodyMedium)
                    .foregroundStyle(app.onSurfaceVariant)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
            .accessibilityElement(children: .combine))]
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
