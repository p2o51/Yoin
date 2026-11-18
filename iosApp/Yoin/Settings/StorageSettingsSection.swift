import SwiftUI

/// Settings › Storage › Playback cache: Android `SettingsScreen` `CacheItem`.
/// The streamed songs held on disk (Data/Cache/AudioCache) and their size;
/// Clear drops them (disabled while there is nothing to drop). The album
/// and list pages the app keeps for offline aren't counted or cleared,
/// as on Android.
///
/// Drawn in ServerSettingsView's Pixel style: primary group label 8 in,
/// each row its own surfaceBright segment (20 outer / 4 inner corners).
struct StorageSettingsSection: View {
    var app: CoverScheme
    var row: Color
    /// The NeoDB page, opened by its row (and by Settings for "Sign in to sync").
    @Binding var neodbOpen: Bool
    @State private var neodb = NeoDBAccount.shared
    /// The two-pane Settings' list: NeoDB's row picks its page beside it.
    @Environment(\.settingsList) private var list

    @State private var bytes: Int64?
    @State private var clearing = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            // 51 2026-11-09 (Android #191): Cloud sync, NeoDB, then the playback cache.
            Text("Sync and storage")
                .yoinRole(Tokens.type.labelLarge)
                .foregroundStyle(app.primary)
                .padding(.horizontal, 8)
                .accessibilityAddTraits(.isHeader)
            VStack(spacing: 2) {
                CloudSyncEntryRow(app: app, row: row, shape: CloudSyncView.segment(0, 3))
                Button { if let list { list.pick(.neodb) } else { neodbOpen = true } } label: {
                    SettingsRowLabel(title: "NeoDB", summary: neodbSummary, app: app, trailing: "chevron_right")
                }
                .buttonStyle(.plain).pointerHighlight()
                .settingsSegment(1, 3, row: row, app: app, picked: list?.picked == .neodb)
                // Downloads (Subsonic only) are on the Subsonic account's page (SubsonicAccountSettings).
                cacheRow
                    .background(row, in: CloudSyncView.segment(2, 3))
            }
        }
        .animation(Yoin.effects, value: bytes)
        .task { await refresh() }
    }

    private var neodbSummary: String {
        neodb.isSignedIn ? String(localized: "Signed in") : String(localized: "Not signed in")
    }

    private var canClear: Bool { !clearing && (bytes ?? 0) > 0 }

    private var cacheRow: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text("Playback cache")
                    .yoinRole(Tokens.type.bodyLarge)
                    .foregroundStyle(app.onSurface)
                Text(bytes.map(Self.format) ?? " ")
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

    /// Android `formatBytes`: "0 B", "12.3 MB".
    static func format(_ bytes: Int64) -> String {
        bytes == 0 ? "0 B" : ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }
}
