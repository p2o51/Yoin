import SwiftUI
import YoinShared

/// Settings › Storage › Cloud sync, the entry row (Android `CloudSyncEntry`):
/// the title and the one-line state; opens the page.
struct CloudSyncEntryRow: View {
    var app: CoverScheme
    var row: Color
    var shape: UnevenRoundedRectangle
    @State private var sync = CloudSync.shared
    /// DEBUG `-syncOpenPage YES` (with `-serverRoute settings`): opens the page at launch for screenshots.
    @State private var open = {
        #if DEBUG
        UserDefaults.standard.bool(forKey: "syncOpenPage")
        #else
        false
        #endif
    }()

    var body: some View {
        Button { open = true } label: {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Cloud sync")
                        .yoinRole(Tokens.type.bodyLarge)
                        .foregroundStyle(app.onSurface)
                    Text(CloudSyncTexts.summary(sync))
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
        .animation(Yoin.effects, value: CloudSyncTexts.summary(sync))
        .navigationDestination(isPresented: $open) { CloudSyncView() }
    }
}

/// Android's CloudSyncTexts: labels only.
enum CloudSyncTexts {
    @MainActor
    static func summary(_ sync: CloudSync) -> String {
        guard sync.isConfigured else { return "Not configured" }
        let s = sync.state
        switch s.phase {
        case is CloudSyncPhaseSyncing: return "Syncing…"
        case is CloudSyncPhaseOffline: return s.pendingChanges ? "Changes waiting to upload" : "Offline"
        case is CloudSyncPhaseNeedsReauth: return "Reconnect Google"
        case is CloudSyncPhaseStorageFull: return "Google storage full"
        case is CloudSyncPhaseCloudCopyRemoved: return "Drive copy removed"
        case is CloudSyncPhaseUpdateRequired: return "Update Yoin"
        case is CloudSyncPhaseNeedsReview: return "Needs review"
        case is CloudSyncPhaseError: return "Couldn't sync"
        case let r as CloudSyncPhaseResetElsewhere:
            return r.deviceName.map { "Turned off on \($0)" } ?? "Turned off on another device"
        case is CloudSyncPhaseUpToDate:
            if s.pendingChanges { return "Changes waiting to upload" }
            return s.lastSyncAt.map { "Synced \(relative($0.int64Value))" } ?? "On"
        default: return "Off"
        }
    }

    static func relative(_ ms: Int64) -> String {
        let delta = max(0, Date().timeIntervalSince1970 - Double(ms) / 1000)
        if delta < 60 { return "just now" }
        let f = RelativeDateTimeFormatter()
        f.unitsStyle = .full
        return f.localizedString(for: Date(timeIntervalSince1970: Double(ms) / 1000), relativeTo: Date())
    }
}

/// The Cloud sync page (PushPage from Settings; Android CloudSyncActivity).
/// Pixel settings style as ServerSettingsView: large title, primary group
/// labels, rows as surfaceBright segments (20 outer / 4 inner corners).
struct CloudSyncView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dismiss) private var dismiss
    @State private var sync = CloudSync.shared
    @State private var confirmDelete = false
    @State private var confirmFresh: SyncAccountRow?
    @State private var export: URL?

    private var app: CoverScheme {
        model.isIdle ? .brand(dark: colorScheme == .dark)
                     : CoverScheme(album: model.playback.album, dark: colorScheme == .dark)
    }
    private var row: Color { app.surfaceBright }
    private var state: CloudSyncState { sync.state }
    private var enabled: Bool { state.phase.enabled }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                Text("Cloud sync")
                    .yoinRole(Tokens.type.displaySmall)
                    .foregroundStyle(app.onSurface)
                    .padding(.horizontal, 8)
                    .padding(.top, 8)
                if !sync.isConfigured {
                    group("Google account") { [AnyView(info("Not configured", nil))] }
                } else if enabled {
                    onGroups
                } else {
                    offGroups
                }
                if let message = sync.message {
                    Text(message)
                        .yoinRole(Tokens.type.bodyMedium)
                        .foregroundStyle(app.error)
                        .padding(.horizontal, 8)
                        .transition(.opacity)
                }
                // Android keeps this in Settings › Storage after Cloud sync; here until the Settings rework lands.
                group("Playlist cloud backup") {
                    [AnyView(PlaylistBackupsEntryRow(app: app, row: row, shape: Self.segment(0, 1)))]
                }
                whatSyncs
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 40)
            .animation(Yoin.spatial, value: enabled)
            .animation(Yoin.effects, value: CloudSyncTexts.summary(sync))
            .animation(Yoin.effects, value: sync.message)
        }
        .safeAreaBar(edge: .top) { topBar }
        .background(app.surfaceContainer.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .alert("Delete cloud data?", isPresented: $confirmDelete) {
            Button("Cancel", role: .cancel) {}
            Button("Delete", role: .destructive) { Task { await sync.deleteCloudData() } }
        } message: {
            Text("Deletes Yoin's data from your Google Drive for all devices, and turns sync off on them. Data on this device stays.")
        }
        .alert("Start fresh as this account?", isPresented: Binding(get: { confirmFresh != nil },
                                                                     set: { if !$0 { confirmFresh = nil } })) {
            Button("Cancel", role: .cancel) {}
            Button("Start fresh", role: .destructive) {
                if let r = confirmFresh { Task { await sync.startFresh(r.localProfileId) } }
            }
        } message: {
            Text("“\(confirmFresh?.displayName ?? "")” is now signed in as a different account. Notes and ratings it synced before are removed from this device, and it syncs as the new account from now on. Your Drive and your other devices keep the old ones.")
        }
        .sheet(item: Binding(get: { export.map(ShareItem.init) }, set: { if $0 == nil { export = nil } })) { item in
            ShareSheet(url: item.url)
        }
    }

    // MARK: Off

    @ViewBuilder private var offGroups: some View {
        if let reset = state.phase as? CloudSyncPhaseResetElsewhere {
            Text(reset.deviceName.map { "Cloud sync was turned off and its Drive data deleted on \($0). Turn it on again to start fresh from this device." }
                 ?? "Cloud sync was turned off and its Drive data deleted on another device. Turn it on again to start fresh from this device.")
                .yoinRole(Tokens.type.bodyMedium)
                .foregroundStyle(app.onSurfaceVariant)
                .padding(.horizontal, 8)
        }
        group("Google account") {
            [AnyView(action(sync.working ? "Waiting for Google…" : "Turn on with Google", ink: app.primary,
                            disabled: sync.working) { Task { await sync.turnOn() } })]
        }
    }

    // MARK: On

    @ViewBuilder private var onGroups: some View {
        group("Google account") {
            var rows: [AnyView] = [AnyView(info(state.account?.email ?? state.account?.displayName ?? "Google account",
                                                status))]
            if let review = state.phase as? CloudSyncPhaseNeedsReview {
                rows.append(AnyView(info("\(review.count) notes disappeared from \(review.accountName)", nil)))
                rows.append(AnyView(action("Restore here", ink: app.primary) { Task { await sync.resolveReview(restore: true) } }))
                rows.append(AnyView(action("Delete on all devices", ink: app.error) { Task { await sync.resolveReview(restore: false) } }))
            }
            if state.phase is CloudSyncPhaseNeedsReauth {
                rows.append(AnyView(action("Sign in", ink: app.primary) { Task { await sync.turnOn() } }))
            }
            if state.phase is CloudSyncPhaseCloudCopyRemoved {
                rows.append(AnyView(action("Upload again", ink: app.primary) { Task { await sync.uploadAgain() } }))
            }
            rows.append(AnyView(action(state.phase is CloudSyncPhaseSyncing ? "Syncing…" : "Sync now", ink: app.primary,
                                       disabled: state.phase is CloudSyncPhaseSyncing) { Task { await sync.syncNow() } }))
            return rows
        }
        if !state.accounts.isEmpty {
            group("Accounts") { state.accounts.map { AnyView(accountRow($0)) } }
        }
        if !state.cloudOnlyAccounts.isEmpty {
            group("In your Drive, not on this device") {
                state.cloudOnlyAccounts.map { a in
                    AnyView(info(a.displayName.isEmpty ? a.provider : a.displayName,
                                 [a.hint, a.fromDeviceName.map { "from \($0)" }].compactMap { $0 }.joined(separator: "\n")))
                }
            }
        }
        if !state.devices.isEmpty {
            group("Devices") {
                state.devices.map { d in
                    AnyView(info(d.isThisDevice ? "This device" : d.name,
                                 d.lastSyncAt.map { "Last synced \(CloudSyncTexts.relative($0.int64Value))" } ?? "Hasn't synced yet"))
                }
            }
        }
        group("Backups") {
            [AnyView(info("Drive backup", state.lastBackup ?? "None yet")),
             AnyView(action("Back up and export", ink: app.primary, disabled: sync.working) {
                 Task {
                     if await sync.backupNow() != nil { export = sync.exportLatestBackup() }
                 }
             })]
        }
        group("This device") {
            [AnyView(action("Turn off on this device", ink: app.primary) { Task { await sync.turnOff() } }),
             AnyView(action(sync.working ? "Deleting…" : "Delete cloud data…", ink: app.error, disabled: sync.working) {
                 confirmDelete = true
             })]
        }
    }

    private var status: String {
        if state.readOnly { return "Reading your Drive" }
        switch state.phase {
        case is CloudSyncPhaseSyncing: return "Syncing…"
        case is CloudSyncPhaseOffline: return "Can't reach Google"
        case let e as CloudSyncPhaseError: return e.message
        default:
            if state.pendingChanges { return "Changes waiting to upload" }
            return state.lastSyncAt.map { "Last synced \(CloudSyncTexts.relative($0.int64Value))" } ?? "Not synced yet"
        }
    }

    private func accountRow(_ a: SyncAccountRow) -> some View {
        let label: String
        var buttons: [(String, () -> Void)] = []
        switch a.status {
        case is SyncAccountStatusSyncing: label = "Syncing"
        case is SyncAccountStatusNeedsIdentity: label = "Not synced"
        case is SyncAccountStatusNeedsReconnect: label = "Reconnect to sync"
        case let d as SyncAccountStatusDuplicateOf: label = "Same as \(d.otherDisplayName)"
        case let n as SyncAccountStatusNotSynced:
            label = "Not synced"
            buttons.append(("Start syncing", { Task { await sync.startSyncing(a.localProfileId) } }))
            for c in n.linkCandidates {
                buttons.append(("Link to \(c.displayName)", { Task { await sync.link(a.localProfileId, to: c.syncProfileId) } }))
            }
        case is SyncAccountStatusAccountChanged:
            label = "Paused"
            buttons.append(("Keep syncing as before", { Task { await sync.keepAsBefore(a.localProfileId) } }))
            buttons.append(("Start fresh", { confirmFresh = a }))
        default: label = ""
        }
        return VStack(alignment: .leading, spacing: 8) {
            info(a.displayName, label)
            if !buttons.isEmpty {
                HStack(spacing: 16) {
                    ForEach(buttons.indices, id: \.self) { i in
                        Button(action: buttons[i].1) {
                            Text(buttons[i].0).yoinRole(Tokens.type.labelLarge).foregroundStyle(app.primary)
                                .frame(minHeight: 40)
                        }
                        .buttonStyle(PressScale())
                    }
                }
                .padding(.horizontal, 16).padding(.bottom, 8)
            }
        }
    }

    private var whatSyncs: some View {
        group("What syncs") {
            [AnyView(info("Syncs", "Notes on songs\nSong and album ratings, album reviews\nMemory titles, About and Ask\nPlaylist cloud backups")),
             AnyView(info("Stays on this device", "Listening history\nPasswords, sign-ins and API keys\nCaches and downloads"))]
        }
    }

    // MARK: Pieces (ServerSettingsView's)

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
            Spacer()
        }
        .padding(.leading, 8)
        .padding(.vertical, 4)
    }

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
                        .background(row, in: Self.segment(i, items.count))
                }
            }
        }
    }

    static func segment(_ i: Int, _ count: Int) -> UnevenRoundedRectangle {
        let top: CGFloat = i == 0 ? 20 : 4
        let bottom: CGFloat = i == count - 1 ? 20 : 4
        return UnevenRoundedRectangle(topLeadingRadius: top, bottomLeadingRadius: bottom,
                                      bottomTrailingRadius: bottom, topTrailingRadius: top, style: .circular)
    }

    private func info(_ title: String, _ value: String?) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).yoinRole(Tokens.type.bodyLarge).foregroundStyle(app.onSurface)
            if let value, !value.isEmpty {
                Text(value).yoinRole(Tokens.type.bodyMedium).foregroundStyle(app.onSurfaceVariant)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }

    private func action(_ title: String, ink: Color, disabled: Bool = false, _ run: @escaping () -> Void) -> some View {
        Button(action: run) {
            Text(title)
                .yoinRole(Tokens.type.bodyLarge)
                .foregroundStyle(ink)
                .padding(.horizontal, 16)
                .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
                .contentShape(.rect)
        }
        .buttonStyle(PressScale())
        .disabled(disabled)
        .opacity(disabled ? 0.38 : 1)
    }
}

private struct ShareItem: Identifiable {
    let url: URL
    var id: String { url.path }
}

private struct ShareSheet: UIViewControllerRepresentable {
    var url: URL
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [url], applicationActivities: nil)
    }
    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}
