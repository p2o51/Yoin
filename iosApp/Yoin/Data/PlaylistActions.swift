import SwiftUI
import YoinShared
import os

/// Playlist writes (Android PlaylistDetailViewModel rename / delete /
/// removeTrack and NowPlayingViewModel's add-to-playlist) and the one host
/// for the song sheet, the Add to playlist sheet, the playlist dialogs and
/// their one-line confirmations.
///
/// Provider rule (Android `Capability.PLAYLISTS_WRITE`): Subsonic writes;
/// Apple Music doesn't (its API has no rename, delete or remove, so Android
/// leaves it out and every write affordance hides). Signed out, the mock
/// screens show the rows and only confirm.
///
/// Every write is optimistic: the Library row and the cached page change at
/// once and go back when the server refuses. Writes run one after another,
/// so index-based removals stay in step with the server.
@MainActor
@Observable
final class PlaylistActions {
    static let shared = PlaylistActions()

    /// The song sheet's target (root host presents it).
    struct MenuTarget: Identifiable {
        let id = UUID()
        var entry: PlaylistEntry
        var context: TrackMenuContext
    }

    /// The songs the Add to playlist sheet is for.
    struct AddTarget: Identifiable {
        let id = UUID()
        var entries: [PlaylistEntry]
    }

    /// A playlist the picker can add to (fresh from the server, writable only).
    struct Pick: Identifiable, Hashable {
        var id: String
        var name: String
        var songCount: Int?
        var palette: CoverPalette
    }

    struct Toast: Identifiable, Equatable {
        let id = UUID()
        var text: String
        /// A button beside the text (Undo), when the toast offers one.
        var action: ToastAction?

        static func == (a: Toast, b: Toast) -> Bool { a.id == b.id && a.text == b.text }
    }

    /// The toast's one button: its label, and what a tap does (the toast then goes).
    struct ToastAction {
        var label: String
        var run: @MainActor () -> Void
    }

    var menu: MenuTarget?
    var adding: AddTarget?
    var renaming: PlaylistInfo?
    var deleting: PlaylistInfo?
    private(set) var toast: Toast?

    /// The picker's rows (nil = not fetched yet this time).
    private(set) var picks: [Pick]?

    /// Songs the menu handed to Add to playlist; shown once the menu is down.
    @ObservationIgnored private var pendingAdd: [PlaylistEntry]?
    @ObservationIgnored private var tail: Task<Void, Never>?
    @ObservationIgnored private let log = Logger(subsystem: "com.gpo.yoin", category: "playlists")

    // MARK: Opening

    func openMenu(_ entry: PlaylistEntry, context: TrackMenuContext) {
        menu = MenuTarget(entry: entry, context: context)
    }

    /// Add to playlist straight away (the ▾ menu; nothing else is up).
    func requestAdd(_ entries: [PlaylistEntry]) {
        guard !entries.isEmpty else { return }
        picks = nil
        adding = AddTarget(entries: entries)
    }

    /// From inside a sheet: Add to playlist once that sheet is down. The root
    /// host calls `flushPendingAdd()` from the song sheet's onDismiss; a view
    /// that presents `TrackMenuSheet` in a sheet of its own does the same.
    func addAfterDismiss(_ entries: [PlaylistEntry]) {
        pendingAdd = entries
    }

    func flushPendingAdd() {
        guard let e = pendingAdd else { return }
        pendingAdd = nil
        requestAdd(e)
    }

    /// A one-line confirmation; with `action`, a button beside it (Undo) and a
    /// longer stay, so there is time to reach it.
    func show(_ text: String, action: ToastAction? = nil) {
        let t = Toast(text: text, action: action)
        withAnimation(Yoin.spatial) { toast = t }
        Task {
            try? await Task.sleep(for: .seconds(action == nil ? 2.6 : 4))
            if toast?.id == t.id { withAnimation(Yoin.spatial) { toast = nil } }
        }
    }

    /// The toast's button was tapped: it runs once and the toast goes.
    func runToastAction() {
        guard let action = toast?.action else { return }
        withAnimation(Yoin.spatial) { toast = nil }
        action.run()
    }

    // MARK: Rules

    func canAddToPlaylist(_ store: LibraryStore) -> Bool { store.client != nil || !store.isReal }

    /// Android `canWrite`: Subsonic, and owned by the signed-in user (or no
    /// owner); followed playlists hide Rename / Delete / Remove.
    func canEdit(_ p: PlaylistInfo, store: LibraryStore) -> Bool {
        if p.id.provider == "mock" { return !store.isReal }
        guard store.client != nil, p.id.provider == MediaRef.companion.PROVIDER_SUBSONIC else { return false }
        return p.owner.isEmpty || p.owner == store.session.account?.username
    }

    // MARK: Picker

    /// The writable playlists, fresh each time the sheet opens (Android
    /// refetches too, so a playlist made elsewhere shows up).
    func loadPicks(_ store: LibraryStore, library: any MusicLibrary) async {
        guard let client = store.client else {
            picks = store.isReal ? [] : library.libraryItems().filter { $0.kind == .playlist }.map {
                Pick(id: $0.id.rawId, name: $0.title, songCount: $0.count, palette: $0.palette)
            }
            return
        }
        let mapping = SubsonicMapping(client: client)
        do {
            let all = try await client.getPlaylists()
            store.patchPlaylists { rows, _ in rows = all.map(mapping.playlistItem) }
            picks = all.filter(\.canWrite).map {
                Pick(id: $0.id, name: $0.name, songCount: $0.songCount.map { Int($0.int32Value) },
                     palette: mapping.palette(coverId: $0.coverArtId))
            }
        } catch {
            log.error("playlists failed: \(ServerSession.message(for: error), privacy: .public)")
            picks = []
        }
    }

    // MARK: Writes

    private func serial(_ body: @escaping @MainActor () async -> Void) {
        let before = tail
        tail = Task { @MainActor in
            await before?.value
            await body()
        }
    }

    private func ids(_ entries: [PlaylistEntry]) -> [String] {
        entries.filter { $0.track.id.provider == MediaRef.companion.PROVIDER_SUBSONIC }.map(\.track.id.rawId)
    }

    /// Adds [entries] to playlist [pick]; "Added to …" or "Couldn't add to …".
    func add(_ entries: [PlaylistEntry], to pick: Pick, store: LibraryStore) {
        guard let client = store.client else { return show("Added to \(pick.name)") }
        let songIds = ids(entries)
        guard !songIds.isEmpty else { return }
        let id = pick.id
        store.patchPlaylists { rows, details in
            details[id]?.entries += entries
            Self.bump(&rows, id, by: songIds.count)
        }
        serial { [self] in
            do {
                try await client.updatePlaylist(id: id, name: nil, comment: nil, isPublic: nil,
                                                songIdsToAdd: songIds, songIndexesToRemove: [])
                show("Added to \(pick.name)")
            } catch {
                log.error("add failed: \(ServerSession.message(for: error), privacy: .public)")
                // Only this write's part comes back out (others may have landed since).
                withAnimation(Yoin.spatial) {
                    store.patchPlaylists { rows, details in
                        if var d = details[id] {
                            for e in entries.reversed() {
                                if let i = d.entries.lastIndex(of: e) { d.entries.remove(at: i) }
                            }
                            details[id] = d
                        }
                        Self.bump(&rows, id, by: -songIds.count)
                    }
                }
                show("Couldn't add to \(pick.name)")
            }
        }
    }

    /// Android createPlaylistAndAddTargets: a new playlist holding [entries].
    func create(_ name: String, with entries: [PlaylistEntry], store: LibraryStore) {
        let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else { return }
        guard let client = store.client else { return show("Added to \(name)") }
        let songIds = ids(entries)
        // A row at once, its real id when the server answers.
        let temp = "pending:\(UUID().uuidString)"
        let palette = entries.first?.album.palette ?? store.placeholderPalette(coverId: nil)
        let row = LibraryItem(id: MediaId(provider: MediaRef.companion.PROVIDER_SUBSONIC, rawId: temp), kind: .playlist,
                              title: name, subtitle: "Playlist · \(songIds.count) songs", palette: palette,
                              count: songIds.count)
        withAnimation(Yoin.spatial) { store.patchPlaylists { rows, _ in rows.insert(row, at: 0) } }
        serial { [self] in
            do {
                let made = try await client.createPlaylist(name: name, comment: nil, songIds: songIds)
                let mapping = SubsonicMapping(client: client)
                store.patchPlaylists { rows, details in
                    rows.removeAll { $0.id.rawId == temp }
                    if let made {
                        rows.insert(mapping.playlistItem(made.summary), at: 0)
                        details[made.summary.id] = mapping.playlist(made)
                    }
                }
                if made == nil { await store.loadPlaylists(force: true) }
                show("Added to \(name)")
            } catch {
                log.error("create failed: \(ServerSession.message(for: error), privacy: .public)")
                withAnimation(Yoin.spatial) { store.patchPlaylists { rows, _ in rows.removeAll { $0.id.rawId == temp } } }
                show("Couldn't create \"\(name)\"")
            }
        }
    }

    func rename(_ p: PlaylistInfo, to name: String, store: LibraryStore) {
        let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty, name != p.title else { return }
        guard let client = store.client else { return show("Renamed") }
        let id = p.id.rawId
        let old = p.title
        withAnimation(Yoin.spatial) {
            store.patchPlaylists { rows, details in
                details[id]?.title = name
                if let i = rows.firstIndex(where: { $0.id.rawId == id }) { rows[i].title = name }
            }
        }
        serial { [self] in
            do {
                try await client.updatePlaylist(id: id, name: name, comment: nil, isPublic: nil,
                                                songIdsToAdd: [], songIndexesToRemove: [])
                show("Renamed")
            } catch {
                log.error("rename failed: \(ServerSession.message(for: error), privacy: .public)")
                withAnimation(Yoin.spatial) {
                    store.patchPlaylists { rows, details in
                        if details[id]?.title == name { details[id]?.title = old }
                        if let i = rows.firstIndex(where: { $0.id.rawId == id }), rows[i].title == name { rows[i].title = old }
                    }
                }
                show("Couldn't rename playlist")
            }
        }
    }

    /// Delete: the page closes and the row goes at once; both come back on a refusal.
    func delete(_ p: PlaylistInfo, model: AppModel) {
        let store = model.store
        let id = p.id.rawId
        if case .playlist(id)? = model.path.last { model.back() }
        guard let client = store.client else { return show("Playlist deleted") }
        let undo = store.snapshotPlaylist(id)
        withAnimation(Yoin.spatial) {
            store.patchPlaylists { rows, details in
                rows.removeAll { $0.id.rawId == id }
                details[id] = nil
            }
        }
        serial { [self] in
            do {
                try await client.deletePlaylist(id: id)
                show("Playlist deleted")
            } catch {
                log.error("delete failed: \(ServerSession.message(for: error), privacy: .public)")
                withAnimation(Yoin.spatial) { store.restorePlaylist(id, undo) }
                show("Couldn't delete playlist")
            }
        }
    }

    /// Per playlist, its entries as the server holds them while removals are queued: each queued removal
    /// finds its position here when it runs, not when it was tapped, so a failed earlier removal (its row
    /// restored) can't shift a later one onto the wrong song (Codex C05).
    @ObservationIgnored private var serverEntries: [String: [PlaylistEntry]] = [:]
    @ObservationIgnored private var queuedRemovals: [String: Int] = [:]

    /// Removes the song at [index] (Subsonic removes by position).
    func remove(at index: Int, from playlistId: String, store: LibraryStore) {
        guard let client = store.client else { return }
        guard let entries = store.playlist(playlistId)?.entries, index < entries.count else { return }
        let removed = entries[index]
        // Nothing queued: what's on screen is what the server holds.
        if (queuedRemovals[playlistId] ?? 0) == 0 { serverEntries[playlistId] = entries }
        queuedRemovals[playlistId, default: 0] += 1
        withAnimation(Yoin.spatial) {
            store.patchPlaylists { rows, details in
                details[playlistId]?.entries.remove(at: index)
                Self.bump(&rows, playlistId, by: -1)
            }
        }
        serial { [self] in
            defer {
                queuedRemovals[playlistId, default: 1] -= 1
                if queuedRemovals[playlistId] == 0 {
                    queuedRemovals[playlistId] = nil
                    serverEntries[playlistId] = nil
                }
            }
            let server = serverEntries[playlistId] ?? entries
            guard let at = Self.serverIndex(of: removed, in: server, near: index) else { return }
            do {
                try await client.updatePlaylist(id: playlistId, name: nil, comment: nil, isPublic: nil,
                                                songIdsToAdd: [], songIndexesToRemove: [KotlinInt(int: Int32(at))])
                serverEntries[playlistId]?.remove(at: at)
            } catch {
                log.error("remove failed: \(ServerSession.message(for: error), privacy: .public)")
                withAnimation(Yoin.spatial) {
                    store.patchPlaylists { rows, details in
                        if var d = details[playlistId] {
                            d.entries.insert(removed, at: min(index, d.entries.count))
                            details[playlistId] = d
                        }
                        Self.bump(&rows, playlistId, by: 1)
                    }
                }
                show("Couldn't remove track")
            }
        }
    }

    /// Where [entry] sits in the server's list: the occurrence nearest [hint] (a song can be in a
    /// playlist twice; entries of one playlist differ by position number otherwise).
    nonisolated static func serverIndex(of entry: PlaylistEntry, in server: [PlaylistEntry], near hint: Int) -> Int? {
        server.indices.filter { server[$0] == entry }.min { abs($0 - hint) < abs($1 - hint) }
    }

    #if DEBUG
    /// `-playlistDemo menu|np-menu|add|rename|delete|writes` (with `-serverRoute
    /// playlist:<id>` and `-playlistDemoId <id>`): opens a sheet or runs the
    /// writes once the page has loaded, for screenshots and QA without touch.
    func runDemo(_ model: AppModel) async {
        let d = UserDefaults.standard
        guard let demo = d.string(forKey: "playlistDemo") else { return }
        let id = d.string(forKey: "playlistDemoId") ?? "pl-commute"
        for _ in 0..<60 where model.store.playlist(id) == nil { try? await Task.sleep(for: .milliseconds(250)) }
        try? await Task.sleep(for: .milliseconds(800))
        guard let p = model.store.playlist(id), p.entries.count > 1 else { return log.error("demo: no playlist") }
        let store = model.store
        switch demo {
        case "menu": openMenu(p.entries[1], context: .playlist(id, index: 1))
        case "np-menu": openMenu(p.entries[1], context: .nowPlaying)
        case "add": requestAdd([p.entries[1]])
        case "rename": renaming = p
        case "delete": deleting = p
        case "writes":
            rename(p, to: "Rainy Commute", store: store)
            remove(at: 0, from: id, store: store)
            add([p.entries[0]], to: Pick(id: "pl-drive", name: "Late Night Drive", songCount: nil,
                                       palette: p.palette), store: store)
            create("Demo Mix", with: Array(p.entries.prefix(3)), store: store)
        case "remove-then-delete":
            remove(at: 0, from: id, store: store)
            delete(p, model: model)
        default: break
        }
    }
    #endif

    private static func bump(_ rows: inout [LibraryItem], _ id: String, by n: Int) {
        guard let i = rows.firstIndex(where: { $0.id.rawId == id }) else { return }
        let c = max(0, (rows[i].count ?? 0) + n)
        rows[i].count = c
        rows[i].subtitle = "Playlist · \(c) songs"
    }
}

// MARK: Store hooks

extension LibraryStore {
    /// What a playlist write may have to put back: its Library row (and
    /// where it stood) and its cached page.
    struct PlaylistUndo {
        var row: LibraryItem?
        var rowIndex: Int?
        var detail: PlaylistInfo?
    }

    func snapshotPlaylist(_ id: String) -> PlaylistUndo {
        let i = playlists.firstIndex { $0.id.rawId == id }
        return PlaylistUndo(row: i.map { playlists[$0] }, rowIndex: i, detail: playlistDetails[id])
    }

    func restorePlaylist(_ id: String, _ undo: PlaylistUndo) {
        patchPlaylists { rows, details in
            if let row = undo.row {
                if let i = rows.firstIndex(where: { $0.id.rawId == id }) { rows[i] = row }
                else { rows.insert(row, at: min(undo.rowIndex ?? 0, rows.count)) }
            }
            details[id] = undo.detail
        }
    }
}
