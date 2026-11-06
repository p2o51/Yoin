import SwiftUI
import YoinShared

/// Settings › Backups: playlist cloud backups (iOS PlaylistBackupsView, the Mac's layout). Each backup is a
/// playlist kept as it was, followed from its account every few hours, synced with Android and iOS; Restore
/// matches its songs on any account here and writes a new playlist after a review.
struct MacBackupsSettings: View {
    @Environment(MacModel.self) private var model
    @State private var restoring: RestoreItem?
    @State private var deleting: PlaylistBackupRecord?
    @State private var message: String?
    @State private var busy = false

    var body: some View {
        let backups = PlaylistBackupStore.shared.all.sorted { $0.lastBackedUpAt > $1.lastBackedUpAt }
        Form {
            Section("Back up") {
                Menu("Back Up a Playlist") {
                    ForEach(model.library.playlists, id: \.id) { p in
                        Button(p.title) { backUp(p) }
                    }
                    if model.library.playlists.isEmpty { Text("No playlists on this account") }
                }
                .disabled(busy || PlaylistBackups.shared.backingUp.count > 0)
                if !PlaylistBackups.shared.backingUp.isEmpty { ProgressView().controlSize(.small) }
                if let message { Text(message).font(.caption).foregroundStyle(.secondary) }
            }
            Section("Backups") {
                if backups.isEmpty {
                    Text("No backups yet.").foregroundStyle(.secondary)
                }
                ForEach(backups, id: \.id) { b in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(b.name)
                            Text("\(b.sourceProfileLabel)  \(Self.songs(Int(b.trackCount)))  " + Self.date(b.lastBackedUpAt))
                                .font(.caption).foregroundStyle(.secondary)
                            if b.sourceGoneAt != nil {
                                Text("The playlist is gone from its account").font(.caption).foregroundStyle(.secondary)
                            }
                        }
                        Spacer()
                        Button("Restore…") { restoring = RestoreItem(record: b, since: nil) }
                        Button("Delete", role: .destructive) { deleting = b }
                    }
                    DisclosureGroup("Versions and restores") { details(b) }
                        .font(.gsf(12.5))
                }
            }
        }
        .formStyle(.grouped)
        .sheet(item: $restoring) { item in
            MacRestoreSheet(backupId: item.record.id, version: item.since)
        }
        .confirmationDialog("Delete the backup of \(deleting?.name ?? "")?",
                            isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } })) {
            Button("Delete", role: .destructive) {
                if let b = deleting, PlaylistBackupStore.shared.delete(b.id) { CloudSync.shared.localChanged() }
                deleting = nil
            }
        } message: {
            Text("It goes from every device. The playlist itself stays.")
        }
    }

    /// Android PlaylistBackupScreen's Versions (when each began, its songs, + added / − removed against the
    /// one before, Restore) and Restores (where each went and what came of it), newest first.
    @ViewBuilder private func details(_ b: PlaylistBackupRecord) -> some View {
        let older = b.versions
        versionRow(since: b.lastChangedAt, songs: b.content.tracks, older: older.first?.content.tracks, current: true) {
            restoring = RestoreItem(record: b, since: nil)
        }
        ForEach(Array(older.enumerated()), id: \.element.since) { i, v in
            versionRow(since: v.since, songs: v.content.tracks,
                       older: i + 1 < older.count ? older[i + 1].content.tracks : nil, current: false) {
                restoring = RestoreItem(record: b, since: v.since)
            }
        }
        ForEach(Array(b.restores.reversed().enumerated()), id: \.offset) { _, r in
            HStack {
                VStack(alignment: .leading, spacing: 1) {
                    Text("Restored to \(r.targetLabel)")
                    Text(Self.date(r.at)).font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                MacRestoreCounts(record: r)
            }
        }
    }

    private func versionRow(since: Int64, songs: [BackupTrack], older: [BackupTrack]?, current: Bool,
                            onRestore: @escaping () -> Void) -> some View {
        let diff = older.map { Self.versionDiff(older: $0, newer: songs) }
        return HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 1) {
                Text(Self.date(since, time: true))
                HStack(spacing: 8) {
                    Text(Self.songs(songs.count)).foregroundStyle(.secondary)
                    if let diff, diff.added > 0 { Text("+\(diff.added)").foregroundStyle(.green) }
                    if let diff, diff.removed > 0 { Text("\u{2212}\(diff.removed)").foregroundStyle(.red) }
                    if current { Text("Current").foregroundStyle(.secondary) }
                }
                .font(.caption)
            }
            Spacer()
            Button("Restore…", action: onRestore).disabled(songs.isEmpty)
        }
    }

    /// Songs added and removed going from [older] to [newer], by their source ids (Android versionDiff).
    static func versionDiff(older: [BackupTrack], newer: [BackupTrack]) -> (added: Int, removed: Int) {
        let before = Set(older.map(\.sourceTrackId)), after = Set(newer.map(\.sourceTrackId))
        return (after.subtracting(before).count, before.subtracting(after).count)
    }

    static func songs(_ n: Int) -> String { "\(n) \(n == 1 ? "song" : "songs")" }

    static func date(_ ms: Int64, time: Bool = false) -> String {
        Date(timeIntervalSince1970: Double(ms) / 1000).formatted(date: .abbreviated, time: time ? .shortened : .omitted)
    }

    private struct RestoreItem: Identifiable {
        var record: PlaylistBackupRecord
        /// The earlier version restored (its `since`); nil = the current one.
        var since: Int64?
        var id: String { "\(record.id)|\(since ?? 0)" }
    }

    private func backUp(_ p: LibraryItem) {
        guard let id = model.session.activeId, let source = MacBackupAccounts.shared.source(id) else {
            message = "This account can't be read now"
            return
        }
        message = nil
        // One back-up at a time from here: two reads of the same playlist could land out of order.
        busy = true
        Task {
            defer { source.close(); busy = false }
            do {
                let r = try await PlaylistBackups.shared.backUp(rawId: p.id.rawId, from: source)
                CloudSync.shared.localChanged()
                let songs = Int(r.trackCount)
                message = "Backed up \(songs) \(songs == 1 ? "song" : "songs") of \(r.name)"
            } catch {
                message = "Couldn't back up the playlist"
            }
        }
    }
}

/// A restore (iOS PlaylistRestoreSheet): choose an account here, the songs are matched there, check the
/// ones to write, and a new playlist is made.
private struct MacRestoreSheet: View {
    let backupId: String
    let version: Int64?
    @Environment(\.dismiss) private var dismiss
    @State private var model: PlaylistRestoreModel
    @State private var query = ""

    init(backupId: String, version: Int64?) {
        self.backupId = backupId
        self.version = version
        _model = State(initialValue: PlaylistRestoreModel(backupId: backupId) { target in
            MacBackupAccounts.shared.destination(target.id)
        })
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Restore \(model.backup?.name ?? "")").font(.gsf(17, .semibold))
            switch model.phase {
            case .idle, .choosing:
                Text("Restore to").font(.gsf(13, .medium)).foregroundStyle(Yoin.inkVariant)
                ForEach(Self.targets(), id: \.id) { t in
                    Button { model.restore(to: t) } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 1) {
                                Text(t.label)
                                if let n = t.note { Text(n).font(.caption).foregroundStyle(.secondary) }
                            }
                            Spacer()
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .disabled(!t.available)
                }
            case .matching(let t, let done, let total, _):
                Text("Finding the songs on \(t.label)…")
                ProgressView(value: Double(done), total: Double(max(total, 1)))
            case .review(let t, let rows), .writing(let t, let rows):
                Text("\(rows.filter(\.checked).count) of \(rows.count) songs will go to \(t.label)")
                    .font(.gsf(13)).foregroundStyle(Yoin.inkVariant)
                let writing = { if case .writing = model.phase { return true }; return false }()
                if let search = model.search {
                    searchPane(search, target: t)
                } else {
                    List {
                        ForEach(rows, id: \.position) { r in
                            reviewRow(r).disabled(writing)
                        }
                    }
                    .frame(height: 300)
                }
                HStack {
                    Spacer()
                    if case .writing = model.phase { ProgressView().controlSize(.small) }
                    Button("Create Playlist") { model.create() }
                        .disabled({ if case .writing = model.phase { return true }; return false }())
                        .keyboardShortcut(.defaultAction)
                }
            case .done(let t, let record):
                // A create that failed made nothing (Android PlaylistRestoreSheet.kt:1033).
                Text(record.failed ? "The playlist wasn't created on \(t.label)." : "Created on \(t.label).")
                MacRestoreCounts(record: record)
            case .failed(let t):
                Text("Couldn't reach \(t.label).")
            }
            HStack {
                Spacer()
                Button("Close") { model.cancel(); dismiss() }
            }
        }
        .padding(20)
        .frame(width: 460)
        .onAppear { model.open(version: version) }
        // The sheet owns the restore: closed while matching or at review, it stops and lets go of its client
        // (cancel leaves a write under way to finish).
        .onDisappear { model.cancel() }
    }

    /// Every account here as a restore target (iOS PlaylistBackupsView.targets).
    static func targets(_ accounts: BackupAccounts = MacBackupAccounts.shared) -> [RestoreTarget] {
        accounts.profiles.map { p in
            let usable = accounts.canRead(p.id)
            switch p.service {
            case .subsonic:
                return RestoreTarget(id: p.id, label: p.title, provider: AccountScope.subsonicProvider,
                                     available: usable, note: usable ? nil : "Sign in again")
            case .appleMusic:
                return RestoreTarget(id: p.id, label: "Apple Music", provider: AppleMusicMapping.provider,
                                     available: usable, note: usable ? nil : "Apple Music access is off for Yoin")
            case .spotify:
                return RestoreTarget(id: p.id, label: p.title, provider: SpotifyMapping.provider,
                                     available: usable, note: usable ? nil : "Sign in again")
            }
        }
    }

    /// A song to write: its checkbox and what it matched; an unsure one offers its candidates and a search,
    /// one not found a search (Android PlaylistRestoreSheet.kt:614, 677).
    private func reviewRow(_ r: ReviewRow) -> some View {
        let track = model.backup.flatMap { b in b.content.tracks.indices.contains(Int(r.position)) ? b.content.tracks[Int(r.position)] : nil }
        return HStack(spacing: 8) {
            Toggle(isOn: Binding(get: { r.checked }, set: { model.setChecked(r.position, $0) })) {
                VStack(alignment: .leading, spacing: 1) {
                    Text(track?.title ?? "").lineLimit(1)
                    Text(Self.status(r)).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            .disabled(!r.checkable)
            Spacer(minLength: 4)
            switch r.status {
            case .uncertain, .picked, .matched:
                Menu(r.status == .uncertain ? "To Check" : "Change") {
                    ForEach(Array(r.candidates.enumerated()), id: \.offset) { i, c in
                        Button {
                            model.choose(r.position, Int32(i))
                        } label: {
                            Text([c.title, c.artist, c.album].compactMap { $0 }.joined(separator: "   "))
                        }
                    }
                    if !r.candidates.isEmpty { Divider() }
                    Button("Search…") { openSearch(r, track) }
                }
                .fixedSize()
            default:
                Button("Search…") { openSearch(r, track) }
            }
        }
    }

    private func openSearch(_ r: ReviewRow, _ track: BackupTrack?) {
        let q = track.map { [$0.title, $0.artists.first].compactMap { $0 }.joined(separator: " ") } ?? ""
        query = q
        model.openSearch(r.position, query: q)
    }

    /// The review's search on the restore's account: a result becomes the song's match.
    private func searchPane(_ search: PlaylistRestoreModel.Search, target: RestoreTarget) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                TextField("Search \(target.label)", text: $query)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit { model.runSearch(query, debounce: false) }
                    .onChange(of: query) { _, q in if q != model.search?.query { model.runSearch(q) } }
                if search.loading { ProgressView().controlSize(.small) }
                Button("Cancel") { model.closeSearch() }
            }
            List {
                if search.results.isEmpty, !search.loading, !query.trimmingCharacters(in: .whitespaces).isEmpty {
                    Text(search.failed ? "Couldn't reach \(target.label)" : "No results").foregroundStyle(.secondary)
                }
                ForEach(search.results, id: \.id) { c in
                    Button { model.pick(c) } label: {
                        VStack(alignment: .leading, spacing: 1) {
                            Text(c.title ?? "").lineLimit(1)
                            Text([c.artist, c.album].compactMap { $0 }.joined(separator: "  "))
                                .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
            .frame(height: 260)
        }
    }

    private static func title(_ b: PlaylistBackupRecord?, _ position: Int32) -> String {
        guard let b, b.content.tracks.indices.contains(Int(position)) else { return "" }
        return b.content.tracks[Int(position)].title
    }

    private static func status(_ r: ReviewRow) -> String {
        switch r.status {
        case .matched: [r.choice?.artist, r.choice?.album].compactMap { $0 }.joined(separator: "  ")
        case .uncertain: "Check this one"
        case .picked: "Your pick"
        default: "Not found"
        }
    }
}

/// "2 added  1 skipped  1 not found", or "Not created" (Android RestoreCounts).
private struct MacRestoreCounts: View {
    var record: PlaylistRestoreRecord

    var body: some View {
        HStack(spacing: 8) {
            if record.failed {
                Text("Not created").foregroundStyle(.red)
            } else {
                Text("\(record.added) added")
                if record.skipped > 0 { Text("\(record.skipped) skipped").foregroundStyle(.secondary) }
                if record.notFound > 0 { Text("\(record.notFound) not found").foregroundStyle(.red) }
            }
        }
        .font(.caption)
    }
}
