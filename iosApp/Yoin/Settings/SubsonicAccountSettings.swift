import SwiftUI
import YoinShared

/// A Subsonic account's own settings, at the bottom of its page (ServiceSetupView): settings that
/// belong to one service live on that account's page, never in global Settings (51, 2026-11-04 /
/// 11-06). Stream quality per network, and Downloads (a Subsonic server's songs kept on the device).
/// Same stores and keys as when they were in Settings, so the choices carry over.
///
/// Drawn in ServerSettingsView's Pixel style: primary group label 8 in, each row its own
/// surfaceBright segment (20 outer / 4 inner corners).
struct SubsonicAccountSettings: View {
    var app: CoverScheme
    var row: Color
    /// Downloads come from the account in use.
    var showsDownloads: Bool

    @Environment(AppModel.self) private var model
    @State private var quality = StreamQualityStore.load()
    /// The quality row whose choices are open under it, if any.
    @State private var qualityOpen: QualityNetwork?

    /// Android Settings › Playback quality: one choice per network.
    private enum QualityNetwork: Hashable {
        case wifi, mobile
        var title: String {
            switch self {
            case .wifi: String(localized: "Wi\u{2011}Fi")
            case .mobile: String(localized: "Mobile data")
            }
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 28) {
            qualityGroup
            levelingGroup
            spatialGroup
            if showsDownloads {
                VStack(alignment: .leading, spacing: 8) {
                    label(String(localized: "Downloads"))
                    DownloadsEntryRow(app: app, row: row, shape: CloudSyncView.segment(0, 1))
                }
            }
        }
        .animation(Yoin.spatial, value: qualityOpen)
        .animation(Yoin.effects, value: quality)
    }

    private var qualityGroup: some View {
        group(String(localized: "Stream quality")) {
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

    /// Android Settings › Playback › Volume leveling (#139): one choice, applied at once, to the song playing
    /// too. Only songs the server tagged with ReplayGain change.
    private var levelingGroup: some View {
        let leveling = ReplayGainSettings.shared
        return group(String(localized: "Volume leveling")) {
            ReplayGainMode.allCases.map { mode in
                AnyView(Button {
                    withAnimation(Yoin.effects) { leveling.mode = mode }
                } label: {
                    HStack(spacing: 16) {
                        ZStack {
                            Circle().strokeBorder(leveling.mode == mode ? app.primary : app.onSurfaceVariant, lineWidth: 2)
                            Circle().fill(app.primary).padding(5).scaleEffect(leveling.mode == mode ? 1 : 0.01)
                                .opacity(leveling.mode == mode ? 1 : 0)
                        }
                        .frame(width: 20, height: 20)
                        .padding(.horizontal, 2)
                        Text(mode.label).yoinRole(Tokens.type.titleMedium).foregroundStyle(app.onSurface)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 14)
                    .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
                    .contentShape(.rect)
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(leveling.mode == mode ? [.isButton, .isSelected] : .isButton))
            }
        }
    }

    /// Android Settings › Spatial audio: 5.1 / 7.1 files spatialized on headphones that support it.
    private var spatialGroup: some View {
        let s = ReplayGainSettings.shared
        return group(String(localized: "Spatial audio")) {
            [AnyView(Toggle(isOn: Binding(get: { s.spatializeMultichannel },
                                          set: { on in withAnimation(Yoin.effects) { s.spatializeMultichannel = on } })) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Spatialize 5.1 and 7.1 songs").yoinRole(Tokens.type.bodyLarge).foregroundStyle(app.onSurface)
                    Text(s.spatializeMultichannel
                         ? String(localized: "Only for songs that are 5.1 or 7.1 files, played as the original. Stereo songs aren't changed.")
                         : String(localized: "Off. Multichannel songs play folded down to stereo."))
                        .yoinRole(Tokens.type.bodyMedium).foregroundStyle(app.onSurfaceVariant)
                        .contentTransition(.opacity)
                }
            }
            .tint(app.primary)
            .padding(.horizontal, 16)
            .padding(.vertical, 14))]
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

    private func label(_ title: String) -> some View {
        Text(title)
            .yoinRole(Tokens.type.labelLarge)
            .foregroundStyle(app.primary)
            .padding(.horizontal, 8)
            .accessibilityAddTraits(.isHeader)
    }

    private func group(_ title: String, rows: () -> [AnyView]) -> some View {
        let items = rows()
        return VStack(alignment: .leading, spacing: 8) {
            label(title)
            VStack(spacing: 2) {
                ForEach(items.indices, id: \.self) { i in
                    items[i]
                        .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
                        .background(row, in: CloudSyncView.segment(i, items.count))
                }
            }
        }
    }
}
