import SwiftUI
import YoinShared

/// Settings › Sync and storage (Settings IA v4): two hub rows on root Settings — `Sync and backups`
/// (Cloud sync, NeoDB, Playlist cloud backup) and `Storage` (Playback cache + active Subsonic Downloads).
struct StorageSettingsSection: View {
    var app: CoverScheme
    var row: Color
    /// The NeoDB page, opened directly by deep links (`SettingsDestination.neodb`).
    @Binding var neodbOpen: Bool
    @Environment(AppModel.self) private var model
    @Environment(\.settingsList) private var list

    @State private var sync = CloudSync.shared
    @State private var backups = PlaylistBackupStore.shared
    @State private var offline = OfflineDownloads.shared
    @State private var bytes: Int64?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Sync and storage")
                .yoinRole(Tokens.type.labelLarge)
                .foregroundStyle(app.primary)
                .padding(.horizontal, 8)
                .accessibilityAddTraits(.isHeader)
            VStack(spacing: 2) {
                syncAndBackupsRow
                    .settingsSegment(0, 2, row: row, app: app, picked: syncPicked)
                storageRow
                    .settingsSegment(1, 2, row: row, app: app, picked: storagePicked)
            }
        }
        .animation(Yoin.effects, value: bytes)
        .task { await refresh() }
    }

    private var syncPicked: Bool {
        guard let picked = list?.picked else { return false }
        return picked == .syncAndBackups || picked == .cloudSync || picked == .neodb || picked == .playlistBackups
    }

    private var storagePicked: Bool {
        guard let picked = list?.picked else { return false }
        return picked == .storage || picked == .downloads
    }

    @ViewBuilder
    private var syncAndBackupsRow: some View {
        let summary = syncSummary
        if let list {
            Button { list.pick(.syncAndBackups) } label: {
                SettingsRowLabel(title: String(localized: "Sync and backups"), summary: summary, app: app, trailing: "chevron_right")
            }
            .buttonStyle(.plain).pointerHighlight()
        } else {
            SettingsNavRow(title: String(localized: "Sync and backups"), summary: summary, app: app) {
                SyncAndBackupsPage()
            }
        }
    }

    @ViewBuilder
    private var storageRow: some View {
        let summary = storageSummary
        if let list {
            Button { list.pick(.storage) } label: {
                SettingsRowLabel(title: String(localized: "Storage"), summary: summary, app: app, trailing: "chevron_right")
            }
            .buttonStyle(.plain).pointerHighlight()
        } else {
            SettingsNavRow(title: String(localized: "Storage"), summary: summary, app: app) {
                StorageSettingsPage()
            }
        }
    }

    private var syncSummary: String {
        let base = CloudSyncTexts.summary(sync)
        let count = backups.all.count
        guard count > 0 else { return base }
        let playlists = count == 1 ? String(localized: "1 playlist") : String(localized: "\(count) playlists")
        return "\(base)   \(playlists)"
    }

    private var storageSummary: String {
        let cache = bytes.map(Self.format) ?? "0 B"
        if model.profiles.active?.service == .subsonic, !offline.entries.isEmpty {
            let dl = Self.format(offline.bytes)
            return Tips.showsChinese ? "已下载 \(dl)   缓存 \(cache)" : "\(dl) downloaded   \(cache) cache"
        }
        if (bytes ?? 0) <= 0 {
            return String(localized: "Empty")
        }
        return Tips.showsChinese ? "播放缓存 \(cache)" : "Playback cache \(cache)"
    }

    private func refresh() async {
        let n = await Task.detached(priority: .userInitiated) { AudioCache.shared.sizeBytes() }.value
        bytes = n
    }

    /// Android `formatBytes`: "0 B", "12.3 MB".
    static func format(_ bytes: Int64) -> String {
        bytes == 0 ? "0 B" : ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }
}

/// Settings › Sync and storage › Sync and backups (Settings IA v4): Cloud sync, NeoDB, and Playlist cloud backup.
struct SyncAndBackupsPage: View {
    @State private var sync = CloudSync.shared
    @State private var neodb = NeoDBAccount.shared

    private var neodbSummary: String {
        neodb.isSignedIn ? String(localized: "Signed in") : String(localized: "Not signed in")
    }

    var body: some View {
        SettingsPage(title: String(localized: "Sync and backups")) { app, row in
            VStack(spacing: 2) {
                SettingsNavRow(title: String(localized: "Cloud sync"),
                               summary: CloudSyncTexts.summary(sync),
                               app: app) {
                    CloudSyncView()
                }
                .background(row, in: CloudSyncView.segment(0, 3))
                SettingsNavRow(title: "NeoDB", summary: neodbSummary, app: app) {
                    NeoDBSettingsPage()
                }
                .background(row, in: CloudSyncView.segment(1, 3))
                PlaylistBackupsEntryRow(app: app, row: row, shape: CloudSyncView.segment(2, 3))
            }
        }
    }
}

/// Settings › Sync and storage › Storage (Settings IA v4): Playback cache and the active Subsonic account's Downloads.
struct StorageSettingsPage: View {
    @Environment(AppModel.self) private var model
    @State private var bytes: Int64?
    @State private var clearing = false

    private var showsDownloads: Bool { model.profiles.active?.service == .subsonic }
    private var canClear: Bool { !clearing && (bytes ?? 0) > 0 }

    var body: some View {
        SettingsPage(title: String(localized: "Storage")) { app, row in
            let count = showsDownloads ? 2 : 1
            VStack(spacing: 2) {
                cacheRow(app: app)
                    .background(row, in: CloudSyncView.segment(0, count))
                if showsDownloads {
                    DownloadsEntryRow(app: app, row: row, shape: CloudSyncView.segment(1, count))
                }
            }
            .animation(Yoin.effects, value: bytes)
        }
        .task { await refresh() }
    }

    private func cacheRow(app: CoverScheme) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text("Playback cache")
                    .yoinRole(Tokens.type.bodyLarge)
                    .foregroundStyle(app.onSurface)
                Text(bytes.map(StorageSettingsSection.format) ?? " ")
                    .yoinRole(Tokens.type.bodyMedium)
                    .foregroundStyle(app.onSurfaceVariant)
                    .contentTransition(.numericText())
            }
            Spacer(minLength: 16)
            Button(action: clear) {
                Text("Clear")
                    .yoinRole(Tokens.type.labelLarge)
                    .foregroundStyle(app.primary)
                    .padding(.horizontal, 12)
                    .frame(minHeight: 40)
                    .contentShape(.capsule)
            }
            .buttonStyle(PressScale())
            .disabled(!canClear)
            .opacity(canClear ? 1 : 0.38)
            .animation(Yoin.effects, value: canClear)
            .sensoryFeedback(.impact(weight: .medium), trigger: clearing) { _, now in now }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
    }

    private func clear() {
        clearing = true
        Task {
            await AudioCache.shared.clear()
            await refresh()
            clearing = false
        }
    }

    private func refresh() async {
        let n = await Task.detached(priority: .userInitiated) { AudioCache.shared.sizeBytes() }.value
        bytes = n
    }
}
