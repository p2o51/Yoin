import SwiftUI
import YoinShared

/// Settings › Storage › Playlist cloud backup, the entry row (Android
/// `PlaylistBackupsItem`): opens the list. Android puts it in Settings'
/// Storage group right after Cloud sync; until the iOS Settings rework lands
/// it sits on the Cloud sync page.
struct PlaylistBackupsEntryRow: View {
    var app: CoverScheme
    var row: Color
    var shape: UnevenRoundedRectangle
    @State private var backups = PlaylistBackupStore.shared
    /// DEBUG `-backupsOpenPage YES` (with `-syncOpenPage YES`): opens the list at launch for screenshots.
    @State private var open = {
        #if DEBUG
        UserDefaults.standard.bool(forKey: "backupsOpenPage")
        #else
        false
        #endif
    }()

    var body: some View {
        Button { open = true } label: {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Playlist cloud backup")
                        .yoinRole(Tokens.type.bodyLarge)
                        .foregroundStyle(app.onSurface)
                    Text(summary)
                        .yoinRole(Tokens.type.bodyMedium)
                        .foregroundStyle(app.onSurfaceVariant)
                        .contentTransition(.numericText())
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
        .navigationDestination(isPresented: $open) { PlaylistBackupsView() }
    }

    private var summary: String {
        let n = backups.all.count
        return n == 0 ? "No backups" : "\(n) \(n == 1 ? "playlist" : "playlists")"
    }
}

/// Settings › Playlist cloud backup (Android `PlaylistBackupsPage`): every
/// backup, latest change first. A row opens the backup's page. Pixel style as
/// Downloads: page surfaceContainer, rows surfaceBright segments.
struct PlaylistBackupsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dismiss) private var dismiss
    @State private var store = PlaylistBackupStore.shared
    @State private var opened: String?

    private var app: CoverScheme { BackupTexts.scheme(model, colorScheme) }
    private var row: Color { app.surfaceBright }

    var body: some View {
        let backups = store.all
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                Text("Playlist cloud backup")
                    .yoinRole(Tokens.type.displaySmall)
                    .foregroundStyle(app.onSurface)
                    .padding(.horizontal, 8)
                    .padding(.top, 8)
                if backups.isEmpty {
                    Text("No backups")
                        .yoinRole(Tokens.type.bodyLarge)
                        .foregroundStyle(app.onSurfaceVariant)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 24)
                        .transition(.opacity)
                } else {
                    VStack(spacing: 2) {
                        ForEach(Array(backups.enumerated()), id: \.element.id) { i, b in
                            backupRow(b)
                                .background(row, in: CloudSyncView.segment(i, backups.count))
                                .transition(.opacity)
                        }
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 40 + ShellBarMetrics.clearance)
            .animation(Yoin.spatial, value: backups.map(\.id))
        }
        .safeAreaBar(edge: .top) { BackupChrome.topBar(app) { dismiss() } }
        .background(app.surfaceContainer.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .navigationDestination(item: $opened) { PlaylistBackupView(backupId: $0) }
    }

    /// Service glyph in its tone; name; account; a dot (following) or "Original playlist gone", the last
    /// backup; what couldn't be backed up in error; the song count trailing.
    private func backupRow(_ b: PlaylistBackupRecord) -> some View {
        let tone = BackupTexts.tone(b.sourceProvider, dark: colorScheme == .dark)
        return Button { opened = b.id } label: {
            HStack(spacing: 16) {
                ZStack {
                    Circle().fill(tone.container)
                    YoinIcon(BackupTexts.service(b.sourceProvider).glyph, size: 22).foregroundStyle(tone.onContainer)
                }
                .frame(width: 40, height: 40)
                VStack(alignment: .leading, spacing: 2) {
                    Text(b.name)
                        .yoinRole(Tokens.type.bodyLarge)
                        .foregroundStyle(app.onSurface)
                        .lineLimit(1)
                    Text(b.sourceProfileLabel)
                        .yoinRole(Tokens.type.bodyMedium)
                        .foregroundStyle(app.onSurfaceVariant)
                        .lineLimit(1)
                    HStack(spacing: 6) {
                        Circle().fill(b.following ? tone.accent : app.outline).frame(width: 8, height: 8)
                        Text(b.following ? BackupTexts.time(b.lastBackedUpAt) : "Original playlist gone")
                            .yoinRole(Tokens.type.bodyMedium)
                            .foregroundStyle(app.onSurfaceVariant)
                            .lineLimit(1)
                    }
                    if b.unexportedCount > 0 {
                        Text("\(b.unexportedCount) couldn't be backed up")
                            .yoinRole(Tokens.type.bodyMedium)
                            .foregroundStyle(app.error)
                            .lineLimit(1)
                    }
                }
                Spacer(minLength: 8)
                Text(BackupTexts.songs(Int(b.trackCount)))
                    .yoinRole(Tokens.type.labelLarge)
                    .foregroundStyle(app.onSurfaceVariant)
                    .contentTransition(.numericText())
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .animation(Yoin.effects, value: b.following)
    }
}

/// One backup's page (Android `PlaylistBackupScreen`, a PushPage): where it
/// came from and whether it still follows the playlist, Restore and Back up
/// now, the versions, the restores, what couldn't be backed up, the songs.
/// ⋮ › Delete removes it here and on synced devices.
struct PlaylistBackupView: View {
    let backupId: String

    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dismiss) private var dismiss
    @State private var store = PlaylistBackupStore.shared
    @State private var restore: PlaylistRestoreModel
    @State private var confirmDelete = false
    @State private var backingUp = false
    @State private var seen = false

    init(backupId: String) {
        self.backupId = backupId
        _restore = State(initialValue: PlaylistRestoreModel(backupId: backupId) { target in
            BackupTexts.destination(for: target)
        })
    }

    private var app: CoverScheme { BackupTexts.scheme(model, colorScheme) }
    private var row: Color { app.surfaceBright }

    var body: some View {
        let backup = store.record(backupId)
        ScrollView {
            if let backup {
                VStack(alignment: .leading, spacing: 0) {
                    Text(backup.name)
                        .yoinRole(Tokens.type.displaySmall)
                        .foregroundStyle(app.onSurface)
                        .padding(.horizontal, 8)
                        .padding(.top, 8)
                        .padding(.bottom, 24)
                        .contentTransition(.opacity)
                    status(backup)
                    actions(backup).padding(.top, 16)
                    if !backup.versions.isEmpty { versions(backup).padding(.top, 28) }
                    if !backup.restores.isEmpty { restores(backup).padding(.top, 28) }
                    if !backup.content.unexported.isEmpty { unexported(backup).padding(.top, 28) }
                    songs(backup).padding(.top, 28)
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 40 + ShellBarMetrics.clearance)
                .animation(Yoin.spatial, value: backup.restores.count)
                .animation(Yoin.spatial, value: backup.versions.count)
                .animation(Yoin.effects, value: backup.lastBackedUpAt)
            }
        }
        .safeAreaBar(edge: .top) {
            BackupChrome.topBar(app, menu: AnyView(menu)) { dismiss() }
        }
        .background(app.surfaceContainer.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .sheet(isPresented: Binding(get: { restore.sheetOpen }, set: { if !$0 { restore.sheetClosed() } })) {
            PlaylistRestoreSheet(model: restore, targets: BackupTexts.targets())
        }
        .alert("Delete this backup?", isPresented: $confirmDelete) {
            Button("Cancel", role: .cancel) {}
            Button("Delete", role: .destructive) {
                restore.cancel()
                store.delete(backupId)
            }
        } message: {
            Text("Removed here and on your synced devices.")
        }
        // Deleted here or on another device: the page closes.
        .onChange(of: backup == nil, initial: true) { _, gone in
            if !gone { seen = true } else if seen { dismiss() }
        }
        #if DEBUG
        .task { await runDemo() }
        #endif
    }

    private var menu: some View {
        Menu {
            Button(role: .destructive) { confirmDelete = true } label: {
                Label { Text("Delete") } icon: { Image("yoin.delete") }
            }
        } label: {
            YoinIcon("more_vertical", size: 22)
                .foregroundStyle(app.onSurface)
                .frame(width: 40, height: 40)
                .contentShape(.circle)
        }
        .buttonStyle(.plain)
        .glassEffect(.regular.interactive(), in: .circle)
        .frame(width: 48, height: 48)
        .accessibilityLabel("More options")
    }

    // MARK: Status

    /// Android BackupStatusCard: the account in the service's tone, following or gone, and the four dates.
    private func status(_ b: PlaylistBackupRecord) -> some View {
        let tone = BackupTexts.tone(b.sourceProvider, dark: colorScheme == .dark)
        let service = BackupTexts.service(b.sourceProvider)
        let state = !b.following ? "Original playlist gone"
            : b.sourceProfileId == nil ? "Followed on another device" : "Following changes"
        var tiles: [(String, Int64)] = []
        if let created = BackupTexts.serviceDate(b.playlistCreatedAt) { tiles.append(("Playlist created", created)) }
        tiles += [("Backed up since", b.firstBackedUpAt), ("Last backup", b.lastBackedUpAt), ("Last change", b.lastChangedAt)]
        return VStack(alignment: .leading, spacing: 18) {
            HStack(spacing: 14) {
                ZStack {
                    Circle().fill(tone.accent)
                    YoinIcon(service.glyph, size: 26).foregroundStyle(tone.onAccent)
                }
                .frame(width: 52, height: 52)
                VStack(alignment: .leading, spacing: 0) {
                    Text(b.sourceProfileLabel).yoinRole(Tokens.type.titleLarge).lineLimit(1)
                    if service.name != b.sourceProfileLabel {
                        Text(service.name).yoinRole(Tokens.type.bodyMedium).opacity(0.75)
                    }
                }
            }
            HStack(spacing: 8) {
                Circle().fill(b.following ? tone.accent : app.outline).frame(width: 10, height: 10)
                Text(state).yoinRole(Tokens.type.labelLarge)
                if let gone = b.sourceGoneAt {
                    Text(BackupTexts.time(gone.int64Value)).yoinRole(Tokens.type.labelLarge).opacity(0.7)
                }
            }
            .contentTransition(.opacity)
            Grid(horizontalSpacing: 8, verticalSpacing: 8) {
                ForEach(Array(stride(from: 0, to: tiles.count, by: 2)), id: \.self) { i in
                    GridRow {
                        tile(tiles[i], tone)
                        if i + 1 < tiles.count { tile(tiles[i + 1], tone) } else { Color.clear.gridCellUnsizedAxes(.vertical) }
                    }
                }
            }
        }
        .foregroundStyle(tone.onContainer)
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(tone.container, in: .rect(cornerRadius: 32, style: .continuous))
        .animation(Yoin.effects, value: b.following)
    }

    private func tile(_ t: (String, Int64), _ tone: LandingTone) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(t.0).yoinRole(Tokens.type.labelMedium).opacity(0.75)
            Text(BackupTexts.time(t.1)).yoinRole(Tokens.type.titleSmall).lineLimit(1).contentTransition(.numericText())
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(tone.onContainer.opacity(0.07), in: .rect(cornerRadius: 20, style: .continuous))
    }

    // MARK: Actions

    private func actions(_ b: PlaylistBackupRecord) -> some View {
        let canRestore = b.trackCount > 0
        // Only the device whose account follows the playlist can read it again.
        let canFollow = b.following && PlaylistBackups.shared.followsHere(b)
        return HStack(spacing: 8) {
            Button { restore.open() } label: {
                HStack(spacing: 8) {
                    YoinIcon("add", size: 22)
                    Text("Restore").yoinRole(Tokens.type.titleMedium)
                }
                .foregroundStyle(app.onPrimary)
                .frame(maxWidth: .infinity, minHeight: 56)
                .background(app.primary, in: .capsule)
                .contentShape(.capsule)
            }
            .buttonStyle(PressScale())
            .disabled(!canRestore)
            .opacity(canRestore ? 1 : 0.38)
            if canFollow {
                Button { backUpNow(b) } label: {
                    HStack(spacing: 8) {
                        if backingUp {
                            ProgressView().tint(app.onSecondaryContainer)
                        } else {
                            YoinIcon("refresh", size: 22)
                        }
                        Text("Back up now").yoinRole(Tokens.type.labelLarge)
                    }
                    .foregroundStyle(app.onSecondaryContainer)
                    .padding(.horizontal, 20)
                    .frame(minHeight: 56)
                    .background(app.secondaryContainer, in: .capsule)
                    .contentShape(.capsule)
                }
                .buttonStyle(PressScale())
                .disabled(backingUp)
                .transition(.opacity.combined(with: .scale(scale: 0.9)))
            }
        }
        .animation(Yoin.spatial, value: canFollow)
        .animation(Yoin.effects, value: backingUp)
    }

    private func backUpNow(_ b: PlaylistBackupRecord) {
        guard !backingUp else { return }
        backingUp = true
        Task {
            // Left as it was on failure; the page keeps showing the last backup.
            _ = try? await PlaylistBackups.shared.followNow(b)
            backingUp = false
        }
    }

    // MARK: Sections

    private func versions(_ b: PlaylistBackupRecord) -> some View {
        let older = b.versions
        return VStack(alignment: .leading, spacing: 8) {
            BackupChrome.label("Versions", trailing: "\(older.count + 1)", app)
            VStack(spacing: 2) {
                let count = older.count + 1
                versionRow(since: b.lastChangedAt, songs: b.content.tracks, older: older.first?.content.tracks,
                           current: true) { restore.open() }
                    .background(row, in: CloudSyncView.segment(0, count))
                ForEach(Array(older.enumerated()), id: \.element.since) { i, v in
                    versionRow(since: v.since, songs: v.content.tracks,
                               older: i + 1 < older.count ? older[i + 1].content.tracks : nil, current: false) {
                        restore.open(version: v.since)
                    }
                    .background(row, in: CloudSyncView.segment(i + 1, count))
                }
            }
        }
    }

    /// Android VersionRow: when it began, how many songs, + added / − removed against the one before, Restore.
    private func versionRow(since: Int64, songs: [BackupTrack], older: [BackupTrack]?, current: Bool,
                            onRestore: @escaping () -> Void) -> some View {
        let diff = older.map { BackupTexts.versionDiff(older: $0, newer: songs) }
        return HStack(spacing: 16) {
            YoinIcon(current ? "cloud" : "undo", size: 22).foregroundStyle(app.onSurfaceVariant)
            VStack(alignment: .leading, spacing: 2) {
                Text(BackupTexts.time(since)).yoinRole(Tokens.type.bodyLarge).foregroundStyle(app.onSurface)
                HStack(spacing: 10) {
                    Text(BackupTexts.songs(songs.count)).yoinRole(Tokens.type.bodyMedium).foregroundStyle(app.onSurfaceVariant)
                    if let diff, diff.added > 0 {
                        Text("+\(diff.added)").yoinRole(Tokens.type.labelLarge).foregroundStyle(app.primary)
                    }
                    if let diff, diff.removed > 0 {
                        Text("\u{2212}\(diff.removed)").yoinRole(Tokens.type.labelLarge).foregroundStyle(app.error)
                    }
                    if current {
                        Text("Current")
                            .yoinRole(Tokens.type.labelMedium)
                            .foregroundStyle(app.onPrimaryContainer)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 2)
                            .background(app.primaryContainer, in: .capsule)
                    }
                }
            }
            Spacer(minLength: 8)
            Button(action: onRestore) {
                Text("Restore")
                    .yoinRole(Tokens.type.labelLarge)
                    .foregroundStyle(app.onSecondaryContainer)
                    .padding(.horizontal, 14)
                    .frame(minHeight: 40)
                    .background(app.secondaryContainer, in: .capsule)
                    .contentShape(.capsule)
            }
            .buttonStyle(PressScale())
            .disabled(songs.isEmpty)
            .opacity(songs.isEmpty ? 0.38 : 1)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
    }

    private func restores(_ b: PlaylistBackupRecord) -> some View {
        // Newest first, like the backups themselves.
        let list = Array(b.restores.reversed())
        return VStack(alignment: .leading, spacing: 8) {
            BackupChrome.label("Restores", app)
            VStack(spacing: 2) {
                ForEach(Array(list.enumerated()), id: \.offset) { i, r in
                    restoreRow(r)
                        .background(row, in: CloudSyncView.segment(i, list.count))
                        .transition(.opacity.combined(with: .move(edge: .top)))
                }
            }
        }
    }

    private func restoreRow(_ r: PlaylistRestoreRecord) -> some View {
        let tone = BackupTexts.tone(r.targetProvider, dark: colorScheme == .dark)
        return HStack(spacing: 16) {
            ZStack {
                Circle().fill(tone.container)
                YoinIcon(BackupTexts.service(r.targetProvider).glyph, size: 22).foregroundStyle(tone.onContainer)
            }
            .frame(width: 40, height: 40)
            VStack(alignment: .leading, spacing: 2) {
                Text(r.targetLabel).yoinRole(Tokens.type.bodyLarge).foregroundStyle(app.onSurface).lineLimit(1)
                Text(BackupTexts.time(r.at)).yoinRole(Tokens.type.bodyMedium).foregroundStyle(app.onSurfaceVariant)
                BackupCounts(record: r, app: app)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
    }

    private func unexported(_ b: PlaylistBackupRecord) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            BackupChrome.label("Couldn't be backed up", trailing: "\(b.unexportedCount)", app)
            ForEach(Array(b.content.unexported.enumerated()), id: \.offset) { _, u in
                HStack(spacing: 16) {
                    YoinIcon("visibility_off", size: 20).foregroundStyle(app.onSurfaceVariant).frame(minWidth: 24)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(u.title ?? "Untitled").yoinRole(Tokens.type.bodyLarge).foregroundStyle(app.onSurface).lineLimit(1)
                        Text([u.artist, BackupTexts.reason(u.reasonKind)].compactMap { $0 }.joined(separator: "   "))
                            .yoinRole(Tokens.type.bodyMedium)
                            .foregroundStyle(app.onSurfaceVariant)
                            .lineLimit(1)
                    }
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, 8)
                .padding(.vertical, 8)
                .frame(minHeight: 56)
            }
        }
    }

    private func songs(_ b: PlaylistBackupRecord) -> some View {
        LazyVStack(alignment: .leading, spacing: 0) {
            BackupChrome.label("Songs", trailing: "\(b.trackCount)", app).padding(.bottom, 4)
            ForEach(Array(b.content.tracks.enumerated()), id: \.offset) { i, t in
                BackupTrackRow(index: i, track: t, app: app)
            }
        }
    }

    #if DEBUG
    /// `-backupDemo restore|review|create` (PlaylistBackups.runDemo opens this page): the restore sheet, then
    /// matching on the first account, then the unsure rows checked and the playlist created.
    private func runDemo() async {
        guard let demo = UserDefaults.standard.string(forKey: "backupDemo"),
              ["restore", "review", "create"].contains(demo) else { return }
        try? await Task.sleep(for: .seconds(1.5))
        restore.open()
        guard demo != "restore", let target = BackupTexts.targets().first(where: \.available) else { return }
        try? await Task.sleep(for: .seconds(1.5))
        restore.restore(to: target)
        guard demo == "create" else { return }
        for _ in 0..<100 { if case .review = restore.phase { break }; try? await Task.sleep(for: .milliseconds(200)) }
        try? await Task.sleep(for: .seconds(2.5))
        guard case .review(_, let rows) = restore.phase else { return }
        for r in rows where r.status == .uncertain { restore.setChecked(r.position, true) }
        try? await Task.sleep(for: .seconds(1.5))
        restore.create()
    }
    #endif
}

/// Android BackupTrackRow: number, title over artists, length.
struct BackupTrackRow: View {
    var index: Int
    var track: BackupTrack
    var app: CoverScheme

    var body: some View {
        HStack(spacing: 16) {
            Text("\(index + 1)")
                .yoinRole(Tokens.type.labelLarge)
                .foregroundStyle(app.onSurfaceVariant)
                .frame(minWidth: 24, alignment: .leading)
            VStack(alignment: .leading, spacing: 2) {
                Text(track.title).yoinRole(Tokens.type.bodyLarge).foregroundStyle(app.onSurface).lineLimit(1)
                if !track.artists.isEmpty {
                    Text(track.artists.joined(separator: ", "))
                        .yoinRole(Tokens.type.bodyMedium)
                        .foregroundStyle(app.onSurfaceVariant)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 8)
            if let d = track.durationSec?.intValue {
                Text(formatTime(d)).yoinRole(Tokens.type.labelLarge).foregroundStyle(app.onSurfaceVariant)
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 8)
        .frame(minHeight: 56)
    }
}

/// "2 added  1 skipped  1 not found", or "Not created" (Android RestoreCounts). Labels only.
struct BackupCounts: View {
    var record: PlaylistRestoreRecord
    var app: CoverScheme

    var body: some View {
        HStack(spacing: 10) {
            if record.failed {
                Text("Not created").foregroundStyle(app.error)
            } else {
                Text("\(record.added) added").foregroundStyle(app.primary)
                if record.skipped > 0 { Text("\(record.skipped) skipped").foregroundStyle(app.onSurfaceVariant) }
                if record.notFound > 0 { Text("\(record.notFound) not found").foregroundStyle(app.error) }
            }
        }
        .yoinRole(Tokens.type.labelLarge)
    }
}

/// The backup pages' chrome: Downloads' glass back button, an optional ⋮, primary group labels.
enum BackupChrome {
    @MainActor
    static func topBar(_ app: CoverScheme, menu: AnyView? = nil, back: @escaping () -> Void) -> some View {
        HStack {
            Button(action: back) {
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
            if let menu { menu }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
    }

    @MainActor
    static func label(_ title: String, trailing: String? = nil, _ app: CoverScheme) -> some View {
        HStack {
            Text(title).yoinRole(Tokens.type.labelLarge).foregroundStyle(app.primary)
            Spacer()
            if let trailing {
                Text(trailing).yoinRole(Tokens.type.labelLarge).foregroundStyle(app.onSurfaceVariant)
                    .contentTransition(.numericText())
            }
        }
        .padding(.horizontal, 8)
        .accessibilityAddTraits(.isHeader)
    }
}

/// Android PlaylistBackupTexts and friends: times, services, labels.
@MainActor
enum BackupTexts {
    static func scheme(_ model: AppModel, _ colorScheme: ColorScheme) -> CoverScheme {
        model.isIdle ? .brand(dark: colorScheme == .dark)
                     : CoverScheme(album: model.playback.album, dark: colorScheme == .dark)
    }

    /// The service a backup or a restore target belongs to (Android `ProviderKind.fromKeyOrSubsonic`).
    static func service(_ provider: String) -> LandingService {
        switch provider {
        case AppleMusicMapping.provider: .appleMusic
        case "spotify": .spotify
        default: .subsonic
        }
    }

    static func tone(_ provider: String, dark: Bool) -> LandingTone { LandingTone(service(provider), dark: dark) }

    /// "Oct 19, 14:05" this year, "Oct 19, 2025, 14:05" before, in the device's locale.
    nonisolated static func time(_ ms: Int64, now: Date = Date(), locale: Locale = .current,
                                 calendar: Calendar = .current) -> String {
        let then = Date(timeIntervalSince1970: Double(ms) / 1000)
        let f = DateFormatter()
        f.locale = locale
        f.calendar = calendar
        f.timeZone = calendar.timeZone
        let sameYear = calendar.component(.year, from: then) == calendar.component(.year, from: now)
        f.setLocalizedDateFormatFromTemplate(sameYear ? "MMMdjm" : "yMMMdjm")
        return f.string(from: then)
    }

    nonisolated static func songs(_ n: Int) -> String { "\(n) \(n == 1 ? "song" : "songs")" }

    nonisolated static func reason(_ r: UnexportedReason) -> String {
        switch r {
        case .localfile: "Local file"
        case .episode: "Podcast episode"
        case .video: "Music video"
        default: "Unavailable"
        }
    }

    /// A service's ISO-8601 date ("2024-03-01T10:00:00Z", or a bare date) as epoch ms; nil when unreadable.
    nonisolated static func serviceDate(_ text: String?) -> Int64? {
        guard let value = text?.trimmingCharacters(in: .whitespaces), !value.isEmpty else { return nil }
        let iso = ISO8601DateFormatter()
        for options: ISO8601DateFormatter.Options in [[.withInternetDateTime, .withFractionalSeconds], [.withInternetDateTime]] {
            iso.formatOptions = options
            if let d = iso.date(from: value) { return Int64(d.timeIntervalSince1970 * 1000) }
        }
        iso.formatOptions = [.withFullDate]
        return iso.date(from: String(value.prefix(10))).map { Int64($0.timeIntervalSince1970 * 1000) }
    }

    /// Songs added and removed going from [older] to [newer], by their source ids (Android versionDiff).
    nonisolated static func versionDiff(older: [BackupTrack], newer: [BackupTrack]) -> (added: Int, removed: Int) {
        let before = Set(older.map(\.sourceTrackId)), after = Set(newer.map(\.sourceTrackId))
        return (after.subtracting(before).count, before.subtracting(after).count)
    }

    /// Where a backup can go on this device (Android `restoreTargets`): every account configured here
    /// whose service takes a new playlist — each Subsonic server, Apple Music and Spotify — in the accounts' order,
    /// whichever is in use. One that can't be used now is listed, not choosable, with why.
    static func targets(_ accounts: BackupAccounts = LiveBackupAccounts.shared) -> [RestoreTarget] {
        accounts.profiles.map { p in
            let usable = accounts.canRead(p.id)
            switch p.service {
            case .subsonic:
                return RestoreTarget(id: p.id, label: p.title, provider: AccountScope.subsonicProvider,
                                     available: usable, note: usable ? p.host : "Sign in again")
            case .appleMusic:
                return RestoreTarget(id: p.id, label: "Apple Music", provider: AppleMusicMapping.provider,
                                     available: usable, note: usable ? nil : "Apple Music access is off for Yoin")
            case .spotify:
                // A new private playlist through the Web API (Android SpotifyMusicSource.createPlaylist).
                return RestoreTarget(id: p.id, label: p.title, provider: SpotifyMapping.provider,
                                     available: usable, note: usable ? nil : "Sign in again")
            }
        }
    }

    /// The account [target] names, opened for one restore (by its profile id, never the account in use).
    static func destination(for target: RestoreTarget,
                            accounts: BackupAccounts = LiveBackupAccounts.shared) -> RestoreDestination? {
        guard target.available else { return nil }
        return accounts.destination(target.id)
    }
}
