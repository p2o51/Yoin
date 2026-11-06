import SwiftUI
import YoinShared

/// Settings › Backups: playlist cloud backups (iOS PlaylistBackupsView, the Mac's layout). Each backup is a
/// playlist kept as it was, followed from its account every few hours, synced with Android and iOS; Restore
/// matches its songs on any account here and writes a new playlist after a review.
struct MacBackupsSettings: View {
    @Environment(MacModel.self) private var model
    @State private var restoring: PlaylistBackupRecord?
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
                            Text("\(b.sourceProfileLabel)  \(b.trackCount) \(b.trackCount == 1 ? "song" : "songs")  "
                                 + Date(timeIntervalSince1970: Double(b.lastBackedUpAt) / 1000).formatted(date: .abbreviated, time: .omitted))
                                .font(.caption).foregroundStyle(.secondary)
                            if b.sourceGoneAt != nil {
                                Text("The playlist is gone from its account").font(.caption).foregroundStyle(.secondary)
                            }
                        }
                        Spacer()
                        Button("Restore…") { restoring = b }
                        Button("Delete", role: .destructive) {
                            if PlaylistBackupStore.shared.delete(b.id) { CloudSync.shared.localChanged() }
                        }
                    }
                }
            }
        }
        .formStyle(.grouped)
        .sheet(item: Binding(get: { restoring.map(RestoreItem.init) }, set: { restoring = $0?.record })) { item in
            MacRestoreSheet(backupId: item.record.id)
        }
    }

    private struct RestoreItem: Identifiable {
        var record: PlaylistBackupRecord
        var id: String { record.id }
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
    @Environment(\.dismiss) private var dismiss
    @State private var model: PlaylistRestoreModel

    init(backupId: String) {
        self.backupId = backupId
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
                List {
                    ForEach(rows, id: \.position) { r in
                        Toggle(isOn: Binding(get: { r.checked }, set: { model.setChecked(r.position, $0) })) {
                            VStack(alignment: .leading, spacing: 1) {
                                Text(r.choice?.title ?? Self.title(model.backup, r.position)).lineLimit(1)
                                Text(Self.status(r)).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                            }
                        }
                        .disabled(!r.checkable)
                    }
                }
                .frame(height: 300)
                HStack {
                    Spacer()
                    if case .writing = model.phase { ProgressView().controlSize(.small) }
                    Button("Create Playlist") { model.create() }
                        .disabled({ if case .writing = model.phase { return true }; return false }())
                        .keyboardShortcut(.defaultAction)
                }
            case .done(let t, let record):
                Text("Restored to \(t.label): \(record.added) added, \(record.skipped) skipped.")
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
        .onAppear { model.open() }
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
