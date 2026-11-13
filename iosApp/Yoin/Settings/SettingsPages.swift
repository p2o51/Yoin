import SwiftUI

/// A Settings page another screen can open straight to (Android SettingsScreen's initial feature page).
enum SettingsDestination: Hashable, Sendable {
    case neodb
    /// An account's own page (the account card's Reconnect, when it can't fix the account).
    case account(String)
}

/// One of Settings' own pages: the large title, the ← in glass, the rows on surfaceBright (Android
/// SettingsFeatureActivity; 51 2026-11-09: every Settings row opens a page, nothing unfolds in the list).
struct SettingsPage<Content: View>: View {
    var title: String
    /// The tip page's demo phone: the page in these colours, its back button going nowhere.
    var demoScheme: CoverScheme? = nil
    @ViewBuilder var content: (_ app: CoverScheme, _ row: Color) -> Content

    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dismiss) private var dismiss

    private var app: CoverScheme {
        if let demoScheme { return demoScheme }
        return model.isIdle ? .brand(dark: colorScheme == .dark)
                     : CoverScheme(album: model.playback.album, dark: colorScheme == .dark)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Text(title)
                    .yoinRole(Tokens.type.displaySmall)
                    .foregroundStyle(app.onSurface)
                    .padding(.horizontal, 8)
                    .padding(.top, 8)
                    .accessibilityAddTraits(.isHeader)
                content(app, app.surfaceBright)
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 40 + ShellBarMetrics.clearance)
        }
        .scrollDismissesKeyboard(.interactively)
        .safeAreaBar(edge: .top) {
            HStack {
                Button { if demoScheme == nil { dismiss() } } label: {
                    YoinIcon("back", size: 22)
                        .foregroundStyle(app.onSurface)
                        .frame(width: 40, height: 40)
                        .contentShape(.circle)
                }
                .buttonStyle(.plain).pointerHighlight()
                .glassEffect(.regular.interactive(), in: .circle)
                .frame(width: 48, height: 48)
                .accessibilityLabel("Back")
                .settingsPaneBack()
                Spacer()
            }
            .padding(.leading, 8)
            .padding(.vertical, 4)
        }
        .background(app.surfaceContainer.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
    }
}

/// A Settings row that opens a page: title, a one-line summary, ›.
struct SettingsNavRow<Destination: View>: View {
    var title: String
    var summary: String?
    var app: CoverScheme
    @ViewBuilder var destination: () -> Destination

    var body: some View {
        NavigationLink { destination() } label: {
            SettingsRowLabel(title: title, summary: summary, app: app, trailing: "chevron_right")
        }
        .buttonStyle(.plain).pointerHighlight()
    }
}

/// A row's words: bodyLarge title over a bodyMedium summary, with a trailing icon.
struct SettingsRowLabel: View {
    var title: String
    var summary: String?
    var app: CoverScheme
    var trailing: String? = nil
    var external = false

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .yoinRole(Tokens.type.bodyLarge)
                    .foregroundStyle(app.onSurface)
                if let summary, !summary.isEmpty {
                    Text(summary)
                        .yoinRole(Tokens.type.bodyMedium)
                        .foregroundStyle(app.onSurfaceVariant)
                        .lineLimit(2)
                        .contentTransition(.opacity)
                }
            }
            Spacer(minLength: 16)
            if external {
                Image(systemName: "arrow.up.right").font(.system(size: 16, weight: .medium)).foregroundStyle(app.onSurfaceVariant)
            } else if let trailing {
                YoinIcon(trailing, size: 20).foregroundStyle(app.onSurfaceVariant)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
        .contentShape(.rect)
    }
}

/// Settings › Help and about › Yoin (Android AboutYoinPage, #191): the version first, then the website
/// and the privacy policy.
struct AboutYoinView: View {
    @Environment(AppModel.self) private var model
    @State private var recapOpen = false

    /// The Spotify account in use's newest Spotoolfy recap (Android D15), nil without one.
    private var recap: SpotoolfyRecap? {
        guard let p = model.profiles.active, p.service == .spotify, let user = p.spotifyUserId else { return nil }
        return SpotoolfyRecapStore.load(scope: AccountScope.spotifyScope(userId: user))
    }

    var body: some View {
        SettingsPage(title: "Yoin") { app, row in
            about(app, row)
            if let recap {
                Button { recapOpen = true } label: {
                    SettingsRowLabel(title: String(localized: "Spotoolfy recap"),
                                     summary: String(localized: "The notes and ratings that came over from Spotoolfy"),
                                     app: app, trailing: "chevron_right")
                }
                .buttonStyle(.plain).pointerHighlight()
                .background(row, in: CloudSyncView.segment(0, 1))
                .sheet(isPresented: $recapOpen) {
                    SpotoolfyRecapView(recap: recap, scheme: app) { recapOpen = false }
                }
            }
        }
    }

    private func about(_ app: CoverScheme, _ row: Color) -> some View {
            VStack(spacing: 2) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(String(localized: "Version \(AboutLinks.versionName())"))
                        .yoinRole(Tokens.type.bodyLarge)
                        .foregroundStyle(app.onSurface)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 14)
                .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
                .background(row, in: CloudSyncView.segment(0, 3))
                .accessibilityElement(children: .combine)
                Link(destination: AboutLinks.website(language: AboutLinks.currentLanguage)) {
                    SettingsRowLabel(title: String(localized: "Website"), app: app, external: true)
                }
                .buttonStyle(.plain).pointerHighlight()
                .background(row, in: CloudSyncView.segment(1, 3))
                Link(destination: AboutLinks.privacyPolicy) {
                    SettingsRowLabel(title: String(localized: "Privacy policy"), app: app, external: true)
                }
                .buttonStyle(.plain).pointerHighlight()
                .background(row, in: CloudSyncView.segment(2, 3))
            }
    }
}

/// Settings › Sync and storage › NeoDB, its own page.
struct NeoDBSettingsPage: View {
    var body: some View {
        SettingsPage(title: "NeoDB") { app, row in
            NeoDBSettingsSection(app: app, row: row, page: true)
        }
    }
}
