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
    @State private var quality = StreamQualityStore.load()
    /// The quality row whose choices are open under it, if any.
    @State private var qualityOpen: QualityNetwork?
    /// The service setup page, when pushed (Add account, or an account's page).
    @State private var setup: ServiceSetupRequest?

    /// Android Settings › Playback quality: one choice per network.
    private enum QualityNetwork: Hashable {
        case wifi, mobile
        var title: String {
            switch self {
            case .wifi: "Wi\u{2011}Fi"
            case .mobile: "Mobile data"
            }
        }
    }

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
                if active == .subsonic, model.session.isSignedIn {
                    qualityGroup.transition(.opacity)
                }
                GeminiSettingsSection(app: app, row: row)
                NeoDBSettingsSection(app: app, row: row)
                StorageSettingsSection(app: app, row: row)
                aboutGroup
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 40 + ShellBarMetrics.clearance)
            .animation(Yoin.spatial, value: active)
            .animation(Yoin.spatial, value: qualityOpen)
            .animation(Yoin.effects, value: quality)
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

    private var qualityGroup: some View {
        group("Playback") {
            var rows: [AnyView] = []
            for network in [QualityNetwork.wifi, .mobile] {
                rows.append(AnyView(qualityRow(network)))
                if qualityOpen == network {
                    rows += StreamQuality.companion.all.map { AnyView(qualityChoice($0, for: network)) }
                }
            }
            return rows
        }
    }

    private func selected(_ network: QualityNetwork) -> StreamQuality {
        network == .wifi ? quality.wifi : quality.mobile
    }

    /// Android Settings › Playback quality, as two rows (Wi‑Fi, Mobile data):
    /// each shows its choice; a tap opens Android's five choices right under
    /// it. Auto follows the output (wired Original, Bluetooth 320 kbps,
    /// speaker 192 kbps). The next song opened plays at the new choice.
    private func qualityRow(_ network: QualityNetwork) -> some View {
        let open = qualityOpen == network
        return Button { qualityOpen = open ? nil : network } label: {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(network.title)
                        .yoinRole(Tokens.type.bodyLarge)
                        .foregroundStyle(app.onSurface)
                    Text(selected(network).label)
                        .yoinRole(Tokens.type.bodyMedium)
                        .foregroundStyle(app.onSurfaceVariant)
                        .contentTransition(.opacity)
                }
                Spacer(minLength: 16)
                YoinIcon("chevron_down", size: 20)
                    .foregroundStyle(app.onSurfaceVariant)
                    .rotationEffect(.degrees(open ? 180 : 0))
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(network.title)
        .accessibilityValue(selected(network).label)
    }

    private func qualityChoice(_ choice: StreamQuality, for network: QualityNetwork) -> some View {
        let isSelected = choice == selected(network)
        return Button {
            guard !isSelected else { return }
            switch network {
            case .wifi: StreamQualityStore.setWifi(choice)
            case .mobile: StreamQualityStore.setMobile(choice)
            }
            quality = StreamQualityStore.load()
            model.player.streamQualityDidChange()
        } label: {
            HStack(spacing: 16) {
                ZStack {
                    Circle().strokeBorder(isSelected ? app.primary : app.onSurfaceVariant, lineWidth: 2)
                    Circle().fill(app.primary).padding(5).scaleEffect(isSelected ? 1 : 0.01).opacity(isSelected ? 1 : 0)
                }
                .frame(width: 20, height: 20)
                .padding(.horizontal, 2)
                VStack(alignment: .leading, spacing: 2) {
                    Text(choice.label)
                        .yoinRole(Tokens.type.titleMedium)
                        .foregroundStyle(app.onSurface)
                    if choice == .auto_ {
                        Text(AudioOutputKind.companion.autoDetail)
                            .yoinRole(Tokens.type.bodyMedium)
                            .foregroundStyle(app.onSurfaceVariant)
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .transition(.opacity.combined(with: .move(edge: .top)))
        .accessibilityAddTraits(isSelected ? [.isButton, .isSelected] : .isButton)
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
        group("About") {
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
            .buttonStyle(.plain))]
        }
    }

    // MARK: Apple Music

    static func subscriptionText(_ s: AppleMusicSource.Subscription) -> String {
        if s.canPlayCatalogContent { return "Active" }
        return s.canBecomeSubscriber ? "Not subscribed" : "Unavailable"
    }
}
