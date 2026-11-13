import SwiftUI

/// Settings › Storage › Downloads (Android `DownloadsPage`, PR #131): the
/// download quality (320 kbps by default), whether downloads may use mobile
/// data (off: they wait for Wi‑Fi), the space they take, Remove all, and the
/// albums, playlists and songs kept on the device, newest first. A row opens
/// its album or playlist (the detail cache shows it offline).
///
/// Pixel style as Cloud sync: page surfaceContainer, rows surfaceBright
/// segments (20 outer / 4 inner corners), primary group labels.
struct DownloadsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dismiss) private var dismiss
    @State private var offline = OfflineDownloads.shared
    @State private var qualityOpen = false
    @State private var confirmRemoveAll = false

    private var app: CoverScheme {
        model.isIdle ? .brand(dark: colorScheme == .dark)
                     : CoverScheme(album: model.playback.album, dark: colorScheme == .dark)
    }
    private var row: Color { app.surfaceBright }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                Text("Downloads")
                    .yoinRole(Tokens.type.displaySmall)
                    .foregroundStyle(app.onSurface)
                    .padding(.horizontal, 8)
                    .padding(.top, 8)
                settings
                storage
                if !offline.entries.isEmpty { list }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 40 + ShellBarMetrics.clearance)
            .animation(Yoin.spatial, value: qualityOpen)
            .animation(Yoin.spatial, value: offline.entries.map(\.id))
            .animation(Yoin.effects, value: offline.entries)
        }
        .safeAreaBar(edge: .top) { topBar }
        .background(app.surfaceContainer.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .alert("Remove all downloads?", isPresented: $confirmRemoveAll) {
            Button("Cancel", role: .cancel) {}
            Button("Remove all", role: .destructive) { offline.removeAll() }
        } message: {
            Text("They will stream again when you play them.")
        }
    }

    // MARK: Groups

    private var settings: some View {
        group(String(localized: "Settings")) {
            VStack(spacing: 0) {
                qualityRow
                if qualityOpen {
                    ForEach(OfflineDownloads.Quality.allCases, id: \.self) { q in qualityChoice(q) }
                }
            }
            mobileRow
        }
    }

    private var storage: some View {
        group(String(localized: "Storage")) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Storage used")
                        .yoinRole(Tokens.type.bodyLarge)
                        .foregroundStyle(app.onSurface)
                    Text(StorageSettingsSection.format(offline.bytes))
                        .yoinRole(Tokens.type.bodyMedium)
                        .foregroundStyle(app.onSurfaceVariant)
                        .contentTransition(.numericText())
                }
                Spacer(minLength: 16)
                let can = !offline.entries.isEmpty
                Button { confirmRemoveAll = true } label: {
                    Text("Remove all")
                        .yoinRole(Tokens.type.labelLarge)
                        .foregroundStyle(app.primary)
                        .padding(.horizontal, 12)
                        .frame(minHeight: 40)
                        .contentShape(.capsule)
                }
                .buttonStyle(PressScale())
                .disabled(!can)
                .opacity(can ? 1 : 0.38)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
        }
    }

    private var list: some View {
        let entries = offline.entries
        return VStack(alignment: .leading, spacing: 8) {
            label(String(localized: "Downloaded"))
            VStack(spacing: 2) {
                ForEach(Array(entries.enumerated()), id: \.element.id) { i, e in
                    entryRow(e)
                        .background(row, in: CloudSyncView.segment(i, entries.count))
                        .transition(.opacity)
                }
            }
        }
    }

    // MARK: Rows

    private var qualityRow: some View {
        Button { qualityOpen.toggle() } label: {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Download quality")
                        .yoinRole(Tokens.type.bodyLarge)
                        .foregroundStyle(app.onSurface)
                    Text(offline.quality.label)
                        .yoinRole(Tokens.type.bodyMedium)
                        .foregroundStyle(app.onSurfaceVariant)
                        .contentTransition(.opacity)
                }
                Spacer(minLength: 16)
                YoinIcon("chevron_down", size: 20)
                    .foregroundStyle(app.onSurfaceVariant)
                    .rotationEffect(.degrees(qualityOpen ? 180 : 0))
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityValue(offline.quality.label)
    }

    /// The choice applies to songs downloaded from now on (as on Android).
    private func qualityChoice(_ q: OfflineDownloads.Quality) -> some View {
        let on = q == offline.quality
        return Button {
            withAnimation(Yoin.effects) { offline.quality = q }
        } label: {
            HStack(spacing: 16) {
                ZStack {
                    Circle().strokeBorder(on ? app.primary : app.onSurfaceVariant, lineWidth: 2)
                    Circle().fill(app.primary).padding(5).scaleEffect(on ? 1 : 0.01).opacity(on ? 1 : 0)
                }
                .frame(width: 20, height: 20)
                .padding(.horizontal, 2)
                Text(q.label)
                    .yoinRole(Tokens.type.titleMedium)
                    .foregroundStyle(app.onSurface)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .transition(.opacity.combined(with: .move(edge: .top)))
        .accessibilityAddTraits(on ? [.isButton, .isSelected] : .isButton)
    }

    private var mobileRow: some View {
        @Bindable var offline = offline
        return Toggle(isOn: $offline.overMobileData) {
            VStack(alignment: .leading, spacing: 2) {
                Text("Download using mobile data")
                    .yoinRole(Tokens.type.bodyLarge)
                    .foregroundStyle(app.onSurface)
                Text(offline.overMobileData ? String(localized: "On") : String(localized: "Waits for Wi\u{2011}Fi"))
                    .yoinRole(Tokens.type.bodyMedium)
                    .foregroundStyle(app.onSurfaceVariant)
                    .contentTransition(.opacity)
            }
        }
        .tint(app.primary)
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
    }

    private func entryRow(_ e: OfflineDownloads.Entry) -> some View {
        Button { open(e.collection) } label: {
            HStack(spacing: 16) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(e.collection.title)
                        .yoinRole(Tokens.type.bodyLarge)
                        .foregroundStyle(app.onSurface)
                        .lineLimit(1)
                    Text(detail(e))
                        .yoinRole(Tokens.type.bodyMedium)
                        .foregroundStyle(e.status == .failed ? app.error : app.onSurfaceVariant)
                        .lineLimit(1)
                        .contentTransition(.numericText())
                }
                Spacer(minLength: 8)
                Button { offline.remove(e.collection.key) } label: {
                    YoinIcon("delete", size: 20)
                        .foregroundStyle(app.onSurfaceVariant)
                        .frame(width: 40, height: 40)
                        .contentShape(.circle)
                }
                .buttonStyle(PressScale())
                .accessibilityLabel("Remove download")
            }
            .padding(.leading, 16)
            .padding(.trailing, 8)
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
    }

    /// [kind] [subtitle] [status / size]: labels only.
    private func detail(_ e: OfflineDownloads.Entry) -> String {
        let kind = switch e.collection.kind {
        case .album: String(localized: "Album")
        case .playlist: String(localized: "Playlist")
        case .song: String(localized: "Song")
        }
        let status = switch e.status {
        case .downloading(let done, let total): String(localized: "Downloading \(done)/\(total)")
        case .failed: String(localized: "Couldn't download")
        default: StorageSettingsSection.format(e.bytes)
        }
        return [kind, e.collection.subtitle, status].compactMap { $0 }.joined(separator: "   ")
    }

    private func open(_ c: OfflineDownloads.Collection) {
        switch c.kind {
        case .album:
            if let a = model.store.album(c.id) {
                model.open(a)
            } else {
                // A stand-in the page fills from the detail cache or the server.
                let palette = model.store.client.map { SubsonicMapping(client: $0).palette(coverId: c.coverId) }
                    ?? CoverPalette(a: .gray, b: .gray, c: .gray)
                model.open(Album(id: MediaId(provider: AccountScope.subsonicProvider, rawId: c.id), title: c.title,
                                 artist: c.subtitle ?? "", year: 0, palette: palette, tracks: []))
            }
        case .playlist:
            model.openPlaylist(c.id)
        case .song:
            model.playDownloadedSong(c)
        }
    }

    // MARK: Chrome

    private var topBar: some View {
        HStack {
            Button { dismiss() } label: {
                YoinIcon("back", size: 22)
                    .foregroundStyle(app.onSurface)
                    .frame(width: 40, height: 40)
                    .contentShape(.circle)
            }
            .buttonStyle(.plain)
            .glassEffect(.regular.interactive(), in: .circle)
            .frame(width: 48, height: 48)
            .accessibilityLabel("Back")
            .settingsPaneBack()
            Spacer()
        }
        .padding(.leading, 8)
        .padding(.vertical, 4)
    }

    private func label(_ title: String) -> some View {
        Text(title)
            .yoinRole(Tokens.type.labelLarge)
            .foregroundStyle(app.primary)
            .padding(.horizontal, 8)
            .accessibilityAddTraits(.isHeader)
    }

    /// A titled group whose direct children are its segments.
    private func group<Content: View>(_ title: String, @ViewBuilder rows: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            label(title)
            VStack(spacing: 2) {
                Group(subviews: rows()) { subviews in
                    ForEach(Array(subviews.enumerated()), id: \.offset) { i, v in
                        v.frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
                            .background(row, in: CloudSyncView.segment(i, subviews.count))
                    }
                }
            }
        }
    }
}

/// Settings › Storage › Downloads: what is kept on the device.
struct DownloadsEntryRow: View {
    var app: CoverScheme
    var row: Color
    var shape: UnevenRoundedRectangle
    @State private var offline = OfflineDownloads.shared
    @State private var open = false

    var body: some View {
        Button { open = true } label: {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Downloads")
                        .yoinRole(Tokens.type.bodyLarge)
                        .foregroundStyle(app.onSurface)
                    Text(summary)
                        .yoinRole(Tokens.type.bodyMedium)
                        .foregroundStyle(app.onSurfaceVariant)
                        .contentTransition(.opacity)
                }
                Spacer(minLength: 16)
                YoinIcon("chevron_right", size: 20).foregroundStyle(app.onSurfaceVariant)
            }
            .padding(.horizontal, 16)
            .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
            .background(row, in: shape)
            .contentShape(shape)
        }
        .buttonStyle(PressScale())
        .animation(Yoin.effects, value: summary)
        .navigationDestination(isPresented: $open) { DownloadsView() }
    }

    private var summary: String {
        let n = offline.entries.count
        guard n > 0 else { return offline.quality.label }
        return StorageSettingsSection.format(offline.bytes)
    }
}
