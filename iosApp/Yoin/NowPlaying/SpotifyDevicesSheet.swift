import SwiftUI

/// Now Playing's Devices for a Spotify song (Android NowPlayingViewModel's Spotify Connect devices,
/// Codex N17): the account's Connect devices from the Web API, the one playing marked; a tap moves
/// playback there. The system route picker (AirPlay) is for Yoin's own audio, not the Spotify app's.
struct SpotifyDevicesSheet: View {
    var spotify: SpotifySource
    var scheme: CoverScheme
    @Environment(\.dismiss) private var dismiss
    @State private var devices: [SpotifySource.Device]?
    @State private var failure: String?
    @State private var moving: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Devices")
                    .yoinRole(Tokens.type.headlineSmall)
                    .foregroundStyle(scheme.onSurface)
                if let devices, !devices.isEmpty {
                    VStack(spacing: 2) {
                        ForEach(Array(devices.enumerated()), id: \.element.id) { i, d in
                            row(d).background(scheme.surfaceBright, in: CloudSyncView.segment(i, devices.count))
                        }
                    }
                } else {
                    Text(failure ?? (devices == nil ? "Looking for devices…" : "No Spotify devices found. Open Spotify on the device you want to play on."))
                        .yoinRole(Tokens.type.bodyMedium)
                        .foregroundStyle(scheme.onSurfaceVariant)
                }
            }
            .padding(24)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .presentationDetents([.medium, .large])
        .presentationBackground(scheme.surfaceContainerLow)
        .task { await load() }
        .animation(Yoin.effects, value: devices)
    }

    private func row(_ d: SpotifySource.Device) -> some View {
        Button {
            Task { await move(to: d) }
        } label: {
            HStack(spacing: 16) {
                YoinIcon(d.isActive ? "equalizer" : "devices", size: 22)
                    .foregroundStyle(d.isActive ? scheme.primary : scheme.onSurfaceVariant)
                VStack(alignment: .leading, spacing: 2) {
                    Text(d.name)
                        .yoinRole(Tokens.type.bodyLarge)
                        .foregroundStyle(d.isActive ? scheme.primary : scheme.onSurface)
                    Text(moving == d.id ? "Moving…" : d.isActive ? "Playing here" : d.type)
                        .yoinRole(Tokens.type.bodyMedium)
                        .foregroundStyle(scheme.onSurfaceVariant)
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .frame(maxWidth: .infinity, minHeight: 64, alignment: .leading)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .disabled(d.isActive || moving != nil)
    }

    private func load() async {
        do { devices = try await spotify.devices() } catch {
            devices = []
            failure = SpotifySource.message(for: error)
        }
    }

    private func move(to d: SpotifySource.Device) async {
        moving = d.id
        defer { moving = nil }
        do {
            try await spotify.transfer(to: d.id)
            dismiss()
        } catch {
            failure = SpotifySource.message(for: error)
            await load()
        }
    }
}
