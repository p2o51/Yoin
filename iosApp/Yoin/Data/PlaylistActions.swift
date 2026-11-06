import SwiftUI
import YoinShared
import os

/// Playlist writes (Android PlaylistDetailViewModel rename / delete /
/// removeTrack and NowPlayingViewModel's add-to-playlist) and the one host
/// for the song sheet, the Add to playlist sheet, the playlist dialogs and
/// their one-line confirmations.
///
/// Provider rule (Android `Capability.PLAYLISTS_WRITE`): Subsonic and Spotify
/// write; Apple Music doesn't (its API has no rename, delete or remove, so
/// Android leaves it out and every write affordance hides). On Spotify only
/// the user's own playlists are renamed or deleted (delete = unfollow), and a
/// collaborative one also takes songs; removals name the occurrence by its
/// position and the snapshot it was read at (SpotifySource+Playlists).
/// Signed out, the mock screens show the rows and only confirm.
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

    /// The account in use's playlist writer: its Subsonic server or its Spotify Web API.
    private enum Writer {
        case subsonic(SubsonicClient)
        case spotify(SpotifySource)

        var provider: String {
            switch self {
            case .subsonic: MediaRef.companion.PROVIDER_SUBSONIC
            case .spotify: SpotifyMapping.provider
            }
        }
    }

    private func writer(_ store: LibraryStore) -> Writer? {
        if let spotify = store.spotify { return .spotify(spotify) }
        return store.client.map(Writer.subsonic)
    }

    func canAddToPlaylist(_ store: LibraryStore) -> Bool { writer(store) != nil || !store.isReal }

    /// Library's New playlist: a service that makes one here (Apple Music's are made in its own app).
    func canCreate(_ store: LibraryStore) -> Bool { writer(store) != nil }

    /// Android `canWrite`, for Rename and Delete: Subsonic, owned by the signed-in user (or no owner);
    /// Spotify, made by the signed-in user. Followed playlists hide them.
    func canEdit(_ p: PlaylistInfo, store: LibraryStore) -> Bool {
        if p.id.provider == "mock" { return !store.isReal }
        if p.id.provider == SpotifyMapping.provider { return store.spotify != nil && p.ownedByMe == true }
        guard store.client != nil, p.id.provider == MediaRef.companion.PROVIDER_SUBSONIC else { return false }
        return Self.subsonicCanEdit(p, username: store.session.account?.username)
    }

    /// The server's readonly flag wins (Android canWrite, read from the playlist); a page not read
    /// yet falls back to the owner: the user's, or nobody's.
    nonisolated static func subsonicCanEdit(_ p: PlaylistInfo, username: String?) -> Bool {
        if let canWrite = p.canWrite { return canWrite }
        return p.owner.isEmpty || p.owner == username
    }

    /// Remove from playlist: as [canEdit], and on Spotify a collaborative playlist too (its songs read).
    func canEditSongs(_ p: PlaylistInfo, store: LibraryStore) -> Bool {
        guard p.id.provider == SpotifyMapping.provider else { return canEdit(p, store: store) }
        return store.spotify != nil && !p.tracksUnavailable && (p.ownedByMe == true || p.collaborative)
    }

    // MARK: Picker

    /// The writable playlists, fresh each time the sheet opens (Android
    /// refetches too, so a playlist made elsewhere shows up).
    func loadPicks(_ store: LibraryStore, library: any MusicLibrary) async {
        let gen = store.generation
        if let spotify = store.spotify {
            do {
                let read = try await spotify.writablePlaylists()
                // Another account since: its list isn't this one's.
                guard gen == store.generation else { return }
                store.patchPlaylists { rows, _ in rows = read.all }
                picks = read.writable.map {
                    Pick(id: $0.id.rawId, name: $0.title, songCount: $0.count, palette: $0.palette)
                }
            } catch {
                log.error("playlists failed: \(SpotifySource.message(for: error), privacy: .public)")
                picks = []
            }
            return
        }
        guard let client = store.client else {
            picks = store.isReal ? [] : library.libraryItems().filter { $0.kind == .playlist }.map {
                Pick(id: $0.id.rawId, name: $0.title, songCount: $0.count, palette: $0.palette)
            }
            return
        }
        let mapping = SubsonicMapping(client: client)
        do {
            let all = try await client.getPlaylists()
            guard gen == store.generation else { return }
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

    /// The songs [writer]'s service can hold: its own.
    private func ids(_ entries: [PlaylistEntry], for writer: Writer) -> [String] {
        entries.filter { $0.track.id.provider == writer.provider }.map(\.track.id.rawId)
    }

    private func message(_ error: Error, _ writer: Writer) -> String {
        if case .spotify = writer { return SpotifySource.message(for: error) }
        return ServerSession.message(for: error)
    }

    /// Adds [entries] to playlist [pick]; "Added to …" or "Couldn't add to …".
    func add(_ entries: [PlaylistEntry], to pick: Pick, store: LibraryStore) {
        guard let writer = writer(store) else { return show(String(localized: "Added to \(pick.name)")) }
        let mine = entries.filter { $0.track.id.provider == writer.provider }
        let songIds = ids(mine, for: writer)
        guard !songIds.isEmpty else { return }
        let entries = mine
        let id = pick.id
        let gen = store.generation
        store.patchPlaylists { rows, details in
            details[id]?.entries += entries
            // Spotify: the new rows' positions are known once the page is read again.
            if case .spotify = writer { details[id]?.positions = nil }
            Self.bump(&rows, id, by: songIds.count)
        }
        serial { [self] in
            do {
                switch writer {
                case .subsonic(let client):
                    try await client.updatePlaylist(id: id, name: nil, comment: nil, isPublic: nil,
                                                    songIdsToAdd: songIds, songIndexesToRemove: [])
                case .spotify(let spotify):
                    try await spotify.addTracks(songIds, to: id)
                    // Its new snapshot and positions, for a removal later (none queued: nothing to clobber).
                    if gen == store.generation, store.playlist(id) != nil, (queuedRemovals[id] ?? 0) == 0 {
                        await store.loadPlaylist(id, force: true)
                    }
                }
                show(String(localized: "Added to \(pick.name)"))
            } catch {
                log.error("add failed: \(self.message(error, writer), privacy: .public)")
                guard gen == store.generation else { return show(String(localized: "Couldn't add to \(pick.name)")) }
                if case .spotify = writer {
                    // Spotify adds 100 a request: earlier batches may have landed. Read what it holds.
                    if store.playlist(id) != nil, (queuedRemovals[id] ?? 0) == 0 {
                        await store.loadPlaylist(id, force: true)
                    }
                    await store.loadPlaylists(force: true)
                    return show(String(localized: "Couldn't add to \(pick.name)"))
                }
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
                show(String(localized: "Couldn't add to \(pick.name)"))
            }
        }
    }

    /// Android createPlaylistAndAddTargets: a new playlist holding [entries].
    func create(_ name: String, with entries: [PlaylistEntry], store: LibraryStore) {
        let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else { return }
        guard let writer = writer(store) else { return show(String(localized: "Added to \(name)")) }
        let songIds = ids(entries, for: writer)
        // A row at once, its real id when the server answers.
        let temp = "pending:\(UUID().uuidString)"
        let palette = entries.first?.album.palette ?? store.placeholderPalette(coverId: nil)
        let row = LibraryItem(id: MediaId(provider: writer.provider, rawId: temp), kind: .playlist,
                              title: name, subtitle: String(localized: "Playlist · \(songIds.count) songs"), palette: palette,
                              count: songIds.count)
        withAnimation(Yoin.spatial) { store.patchPlaylists { rows, _ in rows.insert(row, at: 0) } }
        let gen = store.generation
        serial { [self] in
            do {
                switch writer {
                case .subsonic(let client):
                    let made = try await client.createPlaylist(name: name, comment: nil, songIds: songIds)
                    guard gen == store.generation else { return show(String(localized: "Added to \(name)")) }
                    let mapping = SubsonicMapping(client: client)
                    store.patchPlaylists { rows, details in
                        rows.removeAll { $0.id.rawId == temp }
                        if let made {
                            rows.insert(mapping.playlistItem(made.summary), at: 0)
                            details[made.summary.id] = mapping.playlist(made)
                        }
                    }
                    if made == nil { await store.loadPlaylists(force: true) }
                case .spotify(let spotify):
                    // Android createPlaylistAndAddTargets: made private and empty, then the songs added.
                    let made = try await spotify.createPlaylist(name: name)
                    do {
                        try await spotify.addTracks(songIds, to: made.id)
                    } catch {
                        // The playlist stands; only the songs didn't land.
                        log.error("create add failed: \(SpotifySource.message(for: error), privacy: .public)")
                        if gen == store.generation { await store.loadPlaylists(force: true) }
                        show(String(localized: "Couldn't add to \(name)"))
                        return
                    }
                    var item = SpotifyMapping.playlistItem(made)
                    item.count = songIds.count
                    item.subtitle = "Playlist · \(songIds.count) songs"
                    if item.palette.cover == nil { item.palette = palette }
                    guard gen == store.generation else { return show(String(localized: "Added to \(name)")) }
                    store.patchPlaylists { rows, _ in
                        rows.removeAll { $0.id.rawId == temp }
                        rows.insert(item, at: 0)
                    }
                }
                show(String(localized: "Added to \(name)"))
            } catch {
                log.error("create failed: \(self.message(error, writer), privacy: .public)")
                guard gen == store.generation else { return show(String(localized: "Couldn't create \"\(name)\"")) }
                withAnimation(Yoin.spatial) { store.patchPlaylists { rows, _ in rows.removeAll { $0.id.rawId == temp } } }
                show(String(localized: "Couldn't create \"\(name)\""))
            }
        }
    }

    func rename(_ p: PlaylistInfo, to name: String, store: LibraryStore) {
        let name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty, name != p.title else { return }
        guard let writer = writer(store) else { return show(String(localized: "Renamed")) }
        let id = p.id.rawId
        let old = p.title
        let gen = store.generation
        withAnimation(Yoin.spatial) {
            store.patchPlaylists { rows, details in
                details[id]?.title = name
                if let i = rows.firstIndex(where: { $0.id.rawId == id }) { rows[i].title = name }
            }
        }
        serial { [self] in
            do {
                switch writer {
                case .subsonic(let client):
                    try await client.updatePlaylist(id: id, name: name, comment: nil, isPublic: nil,
                                                    songIdsToAdd: [], songIndexesToRemove: [])
                case .spotify(let spotify):
                    try await spotify.renamePlaylist(id, name: name)
                }
                show(String(localized: "Renamed"))
            } catch {
                log.error("rename failed: \(self.message(error, writer), privacy: .public)")
                guard gen == store.generation else { return show(String(localized: "Couldn't rename playlist")) }
                withAnimation(Yoin.spatial) {
                    store.patchPlaylists { rows, details in
                        if details[id]?.title == name { details[id]?.title = old }
                        if let i = rows.firstIndex(where: { $0.id.rawId == id }), rows[i].title == name { rows[i].title = old }
                    }
                }
                show(String(localized: "Couldn't rename playlist"))
            }
        }
    }

    /// Delete: the page closes and the row goes at once; both come back on a refusal.
    func delete(_ p: PlaylistInfo, model: AppModel) {
        let store = model.store
        let id = p.id.rawId
        if case .playlist(id)? = model.path.last { model.back() }
        guard let writer = writer(store) else { return show(String(localized: "Playlist deleted")) }
        let undo = store.snapshotPlaylist(id)
        let gen = store.generation
        withAnimation(Yoin.spatial) {
            store.patchPlaylists { rows, details in
                rows.removeAll { $0.id.rawId == id }
                details[id] = nil
            }
        }
        serial { [self] in
            do {
                switch writer {
                case .subsonic(let client): try await client.deletePlaylist(id: id)
                // Spotify has no delete: unfollowing takes it out of the user's playlists.
                case .spotify(let spotify): try await spotify.unfollowPlaylist(id)
                }
                show(String(localized: "Playlist deleted"))
            } catch {
                log.error("delete failed: \(self.message(error, writer), privacy: .public)")
                if gen == store.generation { withAnimation(Yoin.spatial) { store.restorePlaylist(id, undo) } }
                show(String(localized: "Couldn't delete playlist"))
            }
        }
    }

    /// Per playlist, its entries as the server holds them while removals are queued: each queued removal
    /// finds its position here when it runs, not when it was tapped, so a failed earlier removal (its row
    /// restored) can't shift a later one onto the wrong song (Codex C05).
    @ObservationIgnored private var serverEntries: [String: [PlaylistEntry]] = [:]
    @ObservationIgnored private var queuedRemovals: [String: Int] = [:]

    /// Removes the song at [index] (Subsonic removes by position; Spotify by the occurrence's position).
    func remove(at index: Int, from playlistId: String, store: LibraryStore) {
        if let spotify = store.spotify { return removeOnSpotify(at: index, from: playlistId, spotify: spotify, store: store) }
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
                show(String(localized: "Couldn't remove track"))
            }
        }
    }

    /// A Spotify playlist as the server holds it while removals are queued: its rows, each row's position
    /// among all its items, and the snapshot those positions are in.
    struct SpotifyServerState: Equatable {
        var entries: [PlaylistEntry]
        var positions: [Int]
        var snapshot: String?

        /// [at]'s row gone, as Spotify then numbers the rest (every later item moves up one).
        mutating func removeRow(_ at: Int) {
            let position = positions[at]
            entries.remove(at: at)
            positions.remove(at: at)
            positions = positions.map { $0 > position ? $0 - 1 : $0 }
        }
    }

    @ObservationIgnored private var spotifyServer: [String: SpotifyServerState] = [:]

    /// Android removeTracksFromPlaylist: the one occurrence at its position, against the snapshot the
    /// positions were read at; the answer's snapshot is the next removal's. A page read without positions
    /// (songs just added) is read again first, and only then loses the row.
    private func removeOnSpotify(at index: Int, from playlistId: String, spotify: SpotifySource, store: LibraryStore) {
        guard let page = store.playlist(playlistId), index < page.entries.count else { return }
        let removed = page.entries[index]
        let gen = store.generation
        let known = page.positions.flatMap { $0.count == page.entries.count ? $0 : nil }
        if (queuedRemovals[playlistId] ?? 0) == 0 {
            spotifyServer[playlistId] = known.map { SpotifyServerState(entries: page.entries, positions: $0,
                                                                        snapshot: page.snapshotId) }
        }
        queuedRemovals[playlistId, default: 0] += 1
        let optimistic = known != nil
        if optimistic {
            withAnimation(Yoin.spatial) {
                store.patchPlaylists { rows, details in
                    details[playlistId]?.entries.remove(at: index)
                    details[playlistId]?.positions?.remove(at: index)
                    Self.bump(&rows, playlistId, by: -1)
                }
            }
        }
        serial { [self] in
            defer {
                queuedRemovals[playlistId, default: 1] -= 1
                if queuedRemovals[playlistId] == 0 {
                    queuedRemovals[playlistId] = nil
                    spotifyServer[playlistId] = nil
                }
            }
            if spotifyServer[playlistId] == nil {
                await store.loadPlaylist(playlistId, force: true)
                if let fresh = store.playlist(playlistId), let positions = fresh.positions,
                   positions.count == fresh.entries.count {
                    spotifyServer[playlistId] = SpotifyServerState(entries: fresh.entries, positions: positions,
                                                                   snapshot: fresh.snapshotId)
                }
            }
            guard var server = spotifyServer[playlistId],
                  let at = Self.serverIndex(of: removed, in: server.entries, near: index) else {
                if optimistic, gen == store.generation { restoreRow(removed, at: index, position: nil, in: playlistId, store: store) }
                return show(String(localized: "Couldn't remove track"))
            }
            let position = server.positions[at]
            do {
                let snapshot = try await spotify.removeTracks([.init(trackRawId: removed.track.id.rawId, position: position)],
                                                              from: playlistId, snapshotId: server.snapshot)
                server.removeRow(at)
                server.snapshot = snapshot
                spotifyServer[playlistId] = server
                guard gen == store.generation else { return }
                // The page's rows now count from the new snapshot too.
                store.patchPlaylists { rows, details in
                    guard var d = details[playlistId] else { return }
                    if !optimistic, let i = Self.serverIndex(of: removed, in: d.entries, near: index) {
                        d.entries.remove(at: i)
                        if d.positions?.count == d.entries.count + 1 { d.positions?.remove(at: i) }
                        Self.bump(&rows, playlistId, by: -1)
                    }
                    d.positions = d.positions.map { $0.map { $0 > position ? $0 - 1 : $0 } }
                    d.snapshotId = snapshot
                    details[playlistId] = d
                }
                // The song is in the playlist again elsewhere: the /items removal no longer documents
                // per-item positions, so read what Spotify kept rather than trust the one row going.
                if server.entries.contains(where: { $0.track.id == removed.track.id }) {
                    await store.loadPlaylist(playlistId, force: true)
                    if let fresh = store.playlist(playlistId), let positions = fresh.positions,
                       positions.count == fresh.entries.count {
                        spotifyServer[playlistId] = SpotifyServerState(entries: fresh.entries, positions: positions,
                                                                       snapshot: fresh.snapshotId)
                    }
                }
            } catch {
                log.error("remove failed: \(SpotifySource.message(for: error), privacy: .public)")
                if optimistic, gen == store.generation { restoreRow(removed, at: index, position: position, in: playlistId, store: store) }
                show(String(localized: "Couldn't remove track"))
            }
        }
    }

    /// A refused removal's row back at [index], with its position as the server now numbers it.
    private func restoreRow(_ entry: PlaylistEntry, at index: Int, position: Int?, in playlistId: String,
                            store: LibraryStore) {
        withAnimation(Yoin.spatial) {
            store.patchPlaylists { rows, details in
                guard var d = details[playlistId] else { return }
                let i = min(index, d.entries.count)
                d.entries.insert(entry, at: i)
                if let position, var positions = d.positions, positions.count == d.entries.count - 1 {
                    positions.insert(position, at: i)
                    d.positions = positions
                } else {
                    d.positions = nil
                }
                details[playlistId] = d
                Self.bump(&rows, playlistId, by: 1)
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
