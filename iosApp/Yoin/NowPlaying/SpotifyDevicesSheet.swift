import SwiftUI

/// Now Playing's Devices for a Spotify song (Android NowPlayingViewModel's Spotify Connect devices,
/// Codex N17): the account's Connect devices from the Web API, the one playing marked; a tap moves
/// playback there. The system route picker (AirPlay) is for Yoin's own audio, not the Spotify app's.
///
/// A speaker Spotify names without an id, or won't move to through the Web API (Sonos), is listed and
/// opens the Spotify app to switch there: iOS has no output panel for another app's audio (Android
/// opens its system output switcher). Devices seen before stay as recently used for 30 days
/// (`SpotifyDeviceMemory`), and the list reads again when Yoin comes back (from Spotify, say).
struct SpotifyDevicesSheet: View {
    var spotify: SpotifySource
    /// The Spotify account's scope, the key its remembered devices are kept under (nil: none kept).
    var scope: String?
    var scheme: CoverScheme
    var memory = SpotifyDeviceMemory()
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @Environment(\.scenePhase) private var scenePhase
    @State private var devices: [SpotifySource.Device]?
    @State private var failure: String?
    @State private var moving: String?
    /// A read is under way: the header's refresh turns to a spinner (Android DevicesSheet loading).
    @State private var loading = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack(spacing: 8) {
                    Text("Devices")
                        .yoinRole(Tokens.type.headlineSmall)
                        .foregroundStyle(scheme.onSurface)
                    Spacer(minLength: 0)
                    refreshButton
                }
                // A failed load or move says so above the list, whether or not the list has devices
                // (Android DevicesSheet errorMessage, parity C-U10).
                if let failure {
                    Text(failure)
                        .yoinRole(Tokens.type.bodySmall)
                        .foregroundStyle(scheme.error)
                        .padding(.horizontal, 8)
                        .transition(.opacity)
                }
                if let devices, !devices.isEmpty {
                    VStack(spacing: 2) {
                        ForEach(Array(devices.enumerated()), id: \.element.id) { i, d in
                            row(d).background(scheme.surfaceBright, in: CloudSyncView.segment(i, devices.count))
                        }
                    }
                } else if let line = Self.statusLine(devices: devices, failed: failure != nil) {
                    Text(line)
                        .yoinRole(Tokens.type.bodyMedium)
                        .foregroundStyle(scheme.onSurfaceVariant)
                }
                // The Web API lists only devices online in Spotify's cloud; Sonos and Cast speakers
                // wake only from Spotify's own picker (Android MoreDevicesRow, owner 2026-11-08).
                if devices != nil { moreDevices.transition(.opacity) }
            }
            .padding(24)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .refreshable { await load() }
        .presentationDetents([.medium, .large])
        .presentationBackground(scheme.surfaceContainerLow)
        .task { await load() }
        // Back from the Spotify app (a speaker picked there): read where it plays now.
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await load() } }
        }
        .animation(Yoin.effects, value: devices)
        .animation(Yoin.effects, value: failure)
    }

    /// The line where the list would be: looking (not loaded yet), none found, or nothing when a
    /// failed load already says why.
    static func statusLine(devices: [SpotifySource.Device]?, failed: Bool) -> String? {
        guard devices?.isEmpty ?? true else { return nil }
        if devices == nil { return String(localized: "Looking for devices…") }
        return failed ? nil : String(localized: "No Spotify devices found. Open Spotify on the device you want to play on.")
    }

    /// A row's second line (Android localizedForSheet): moving, a private session (Android's status wins
    /// over the rest), playing, through Spotify, recently used, else Spotify's type.
    static func detail(_ d: SpotifySource.Device, moving: Bool) -> String {
        if moving { return String(localized: "Moving…") }
        if d.privateSession { return String(localized: "Private session") }
        if d.isActive { return String(localized: "Playing here") }
        if d.spotifyOnly { return String(localized: "Switch through Spotify") }
        if d.remembered { return String(localized: "Recently used · may need waking in Spotify") }
        return typeLabel(d.type)
    }

    /// Spotify's device type in the app's language (Android shows an icon, no word, so there are no
    /// Android strings for these); a type Spotify adds later shows as it comes.
    static func typeLabel(_ type: String) -> String {
        switch type {
        case "Computer": String(localized: "Computer", comment: "A Spotify device type")
        case "Smartphone": String(localized: "Smartphone", comment: "A Spotify device type")
        case "Tablet": String(localized: "Tablet", comment: "A Spotify device type")
        case "Speaker": String(localized: "Speaker", comment: "A Spotify device type")
        case "TV": String(localized: "TV", comment: "A Spotify device type")
        case "AVR": String(localized: "AVR", comment: "A Spotify device type: an AV receiver")
        case "STB": String(localized: "STB", comment: "A Spotify device type: a set-top box")
        case "GameConsole": String(localized: "GameConsole", comment: "A Spotify device type")
        case "CastVideo": String(localized: "CastVideo", comment: "A Spotify device type: a Google Cast video device")
        case "CastAudio": String(localized: "CastAudio", comment: "A Spotify device type: a Google Cast audio device")
        case "Automobile": String(localized: "Automobile", comment: "A Spotify device type")
        default: type
        }
    }

    /// Reads the list again (Android DevicesSheet's header IconButton): a spinner while it reads, which
    /// pulling the list down does too.
    private var refreshButton: some View {
        Button { Task { await load() } } label: {
            Group {
                if loading {
                    ProgressView().controlSize(.small).tint(scheme.onSurfaceVariant)
                } else {
                    YoinIcon("refresh", size: 22).foregroundStyle(scheme.onSurfaceVariant)
                }
            }
            .frame(width: 44, height: 44)
            .contentShape(.rect)
        }
        .buttonStyle(.plain).pointerHighlight()
        .disabled(loading)
        .accessibilityLabel(String(localized: "Refresh devices"))
    }

    private var moreDevices: some View {
        Button(action: openSpotify) {
            HStack(spacing: 16) {
                // No speaker symbol in Yoin Symbols for iOS yet: Cast is the nearest.
                YoinIcon("cast", size: 22)
                    .foregroundStyle(scheme.onSurfaceVariant)
                VStack(alignment: .leading, spacing: 2) {
                    Text("More devices…")
                        .yoinRole(Tokens.type.bodyLarge)
                        .foregroundStyle(scheme.onSurface)
                    Text("Sonos and other speakers, through Spotify. Yoin keeps control.")
                        .yoinRole(Tokens.type.bodySmall)
                        .foregroundStyle(scheme.onSurfaceVariant)
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .frame(maxWidth: .infinity, minHeight: 64, alignment: .leading)
            .background(scheme.surfaceContainer, in: CloudSyncView.segment(0, 1))
            .contentShape(.rect)
        }
        .buttonStyle(.plain).pointerHighlight()
    }

    /// The Spotify app, where its own picker reaches the speakers Yoin can't move to.
    private func openSpotify() {
        guard let url = URL(string: "spotify:") else { return }
        openURL(url) { opened in
            if !opened { failure = String(localized: "Couldn't open Spotify.") }
        }
    }

    private func row(_ d: SpotifySource.Device) -> some View {
        Button {
            // Sonos and the like take no Web API transfer: the Spotify app does it.
            if d.spotifyOnly { openSpotify() } else { Task { await move(to: d) } }
        } label: {
            HStack(spacing: 16) {
                YoinIcon(d.isActive ? "equalizer" : "devices", size: 22)
                    .foregroundStyle(d.isActive ? scheme.primary : scheme.onSurfaceVariant)
                VStack(alignment: .leading, spacing: 2) {
                    Text(d.name)
                        .yoinRole(Tokens.type.bodyLarge)
                        .foregroundStyle(d.isActive ? scheme.primary : scheme.onSurface)
                    Text(Self.detail(d, moving: moving == d.id))
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
        .buttonStyle(.plain).pointerHighlight()
        // The playing row keeps full strength (Android DeviceRow: alpha 1 when active); the others dim
        // while a move is under way.
        .allowsHitTesting(!d.isActive && moving == nil)
        .opacity(moving != nil && moving != d.id && !d.isActive ? 0.6 : 1)
        .animation(Yoin.effects, value: moving)
        .accessibilityAddTraits(d.isActive ? .isSelected : [])
    }

    private func load() async {
        loading = true
        defer { loading = false }
        do {
            let listed = try await spotify.sheetDevices()
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            devices = scope.map { memory.rows(listed, scope: $0, nowMs: now) } ?? listed
        } catch {
            // A move's failure (said just before this reload) isn't replaced by an empty list.
            if devices?.isEmpty == false { return }
            devices = []
            failure = SpotifySource.message(for: error)
        }
    }

    private func move(to d: SpotifySource.Device) async {
        failure = nil
        moving = d.id
        defer { moving = nil }
        do {
            try await spotify.transfer(to: d.id)
            NotificationCenter.default.post(name: SpotifyPlayingDevice.transferred, object: nil,
                                            userInfo: ["id": d.id, "name": d.name, "type": d.type])
            dismiss()
        } catch {
            if d.remembered {
                // Asleep: Spotify only wakes a local speaker from its own picker.
                failure = String(localized: "That speaker is asleep. Pick it once in Spotify (More devices), then Yoin can control it.")
                openSpotify()
            } else {
                failure = SpotifySource.message(for: error)
            }
            await load()
        }
    }
}
