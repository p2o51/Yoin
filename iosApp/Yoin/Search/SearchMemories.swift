import Foundation
import Observation
import SwiftUI
import YoinShared

/*
 * Library search's Memories & notes (docs/search.md; Android 0.6.3 build 29+, #Yoin:#18): every account's notes,
 * album notes, reviews, memory titles (the listener's and the AI's) and scores, searched beside the service and
 * laid out with its rows on the All page. The rules are :shared's (`YoinMemorySearch`, iosApp/docs/shared-kmp.md);
 * this file hands it the iOS ledger and keeps what it answered for the query on screen.
 */

/// What search reads of the ledger for one query: the facade's entries and AI titles, and each ledger album's
/// cover (by the facade's album key, `profileId|provider|albumId`).
struct SearchLedgerSnapshot {
    var entries: [YoinLedgerEntry] = []
    var aiTitles: [YoinAiTitle] = []
    var covers: [String: CoverPalette] = [:]

    static var empty: SearchLedgerSnapshot { SearchLedgerSnapshot() }
}

/// The iOS ledger (Data/Memories/MemoryLedger.swift) as the facade reads it. An account is named by its profile's
/// id, as Android's ledger names it; a row whose account has no profile (a removed account) isn't anyone's now.
enum SearchLedgerMapping {
    static func kind(_ k: LedgerKind) -> YoinLedgerKind {
        switch k {
        case .songNote: .songnote
        case .review: .review
        case .title: .title
        case .albumRating: .albumrating
        case .trackRating: .trackrating
        }
    }

    static func album(_ a: LedgerAlbum, profileId: String) -> YoinLedgerAlbum {
        YoinLedgerAlbum(profileId: profileId, provider: a.account.provider, albumId: a.albumId, name: a.name,
                        artist: a.artist, coverKey: a.coverKey)
    }

    /// One entry. A song note's or score's song is in its account's provider (Android songProvider).
    static func entry(_ e: LedgerEntry, profileId: String) -> YoinLedgerEntry {
        let song = e.kind == .songNote || e.kind == .trackRating
        return YoinLedgerEntry(id: e.id, kind: kind(e.kind), at: e.at, profileId: profileId,
                               album: e.album.map { album($0, profileId: profileId) }, text: e.text,
                               trackTitle: e.trackTitle, songId: e.songId,
                               songProvider: song ? e.account.provider : nil,
                               positionMs: e.positionMs.map { KotlinLong(value: $0) },
                               score: e.score.map { KotlinFloat(value: Float($0)) })
    }

    /// An album note (Android album_notes, `album-note:<id>`): the iOS ledger doesn't carry them, so search adds
    /// them. Its album is the one the ledger knows by that id, else the names the note was written under.
    static func albumNote(_ n: AlbumNote, profileId: String, known: LedgerAlbum?) -> YoinLedgerEntry? {
        let text = n.content.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return nil }
        let account = LedgerAccount(provider: n.provider, scope: n.scope)
        let named = known ?? LedgerAlbum(account: account, albumId: n.albumId, name: n.albumName, artist: n.artist,
                                         coverKey: nil)
        guard named.name.nonBlank != nil else { return nil }
        return YoinLedgerEntry(id: "album-note:\(n.id)", kind: .albumnote, at: n.createdAt, profileId: profileId,
                               album: album(named, profileId: profileId), text: n.content, trackTitle: nil,
                               songId: nil, songProvider: nil, positionMs: nil, score: nil)
    }

    /// The AI-written memory titles (memory_copy_cache's title) of albums in a counted account.
    static func aiTitles(_ memories: [AlbumMemoryStore.MemoryCopy], profileIds: [LedgerAccount: String]) -> [YoinAiTitle] {
        memories.compactMap { m in
            guard m.entityType == "album", let title = m.title?.nonBlank,
                  let profileId = profileIds[LedgerAccount(provider: m.provider, scope: m.scope)] else { return nil }
            return YoinAiTitle(profileId: profileId, provider: m.provider, albumId: m.entityId,
                               title: title.trimmingCharacters(in: .whitespacesAndNewlines))
        }
    }

    /// Everything search reads: the ledger's entries and the album notes of the accounts in [profileIds], the AI
    /// titles, and the covers ([covers] by `LedgerAlbum.key`, re-keyed by the facade's album key). An album note's
    /// album is the ledger's, else [album] (what plays and visits know of it), else the note's own names.
    static func snapshot(entries: [LedgerEntry], albumNotes: [AlbumNote], memories: [AlbumMemoryStore.MemoryCopy],
                         profileIds: [LedgerAccount: String], covers: [String: CoverPalette],
                         album lookup: ((LedgerAccount, String) -> LedgerAlbum?)? = nil) -> SearchLedgerSnapshot {
        var out = SearchLedgerSnapshot()
        var albums: [String: LedgerAlbum] = [:]
        for e in entries {
            guard let profileId = profileIds[e.account] else { continue }
            out.entries.append(entry(e, profileId: profileId))
            if let a = e.album {
                albums[a.key] = a
                let key = YoinMemorySearch.companion.albumKey(profileId: profileId, provider: a.account.provider,
                                                              albumId: a.albumId)
                if let cover = covers[a.key] { out.covers[key] = cover }
            }
        }
        for n in albumNotes {
            let account = LedgerAccount(provider: n.provider, scope: n.scope)
            guard let profileId = profileIds[account] else { continue }
            let known = albums["\(account.key)|\(n.albumId)"] ?? lookup?(account, n.albumId)
            guard let e = albumNote(n, profileId: profileId, known: known) else { continue }
            out.entries.append(e)
        }
        out.aiTitles = aiTitles(memories, profileIds: profileIds)
        return out
    }
}

/// The platform's ledger for `YoinMemorySearch` (Android MemorySearchSource): every counted account's ledger as
/// Data/Memories builds it, plus the album notes. Read once per query: Search takes `entries()` and `aiTitles()`
/// together and hands them to the companion's `searchEntries` / `attachmentsOf`.
final class MemorySearchLedger: NSObject, YoinMemorySearchLedger, @unchecked Sendable {
    let snapshot: SearchLedgerSnapshot

    init(snapshot: SearchLedgerSnapshot) {
        self.snapshot = snapshot
    }

    func entries() throws -> [YoinLedgerEntry] { snapshot.entries }

    func aiTitles() throws -> [YoinAiTitle] { snapshot.aiTitles }

    /// The stores as they are now. The ledger itself is MemoryLedgerSource's (built when Home, All Memories or
    /// Search appears, and after a write); album notes and AI titles are read here.
    @MainActor
    static func current(model: AppModel = .shared) -> MemorySearchLedger {
        #if DEBUG
        if let demo = SearchMemoriesDemo.snapshot { return MemorySearchLedger(snapshot: demo) }
        #endif
        let source = MemoryLedgerSource.shared
        if source.state == nil { source.reload(model: model) }
        var profileIds: [LedgerAccount: String] = [:]
        for p in model.profiles.profiles { if let a = MemoryLedgerSource.account(of: p) { profileIds[a] = p.id } }
        guard let state = source.state, !profileIds.isEmpty else { return MemorySearchLedger(snapshot: .empty) }
        let notes = AlbumNotesStore.shared.everyAccount
        // What plays and visits know of an album only a note names (its cover key): read when there is one.
        let named = Set(state.entries.compactMap { $0.album?.key })
        var lookup: ((LedgerAccount, String) -> LedgerAlbum?)?
        let unnamed = notes.contains { n in
            !named.contains(LedgerAccount(provider: n.provider, scope: n.scope).key + "|" + n.albumId)
        }
        if unnamed {
            let found = MemoryLedgerSource.lookup(history: HistoryStore.shared, active: state.activeAccount,
                                                  details: model.store.albumDetails)
            lookup = { account, id in found.album(account, id) }
        }
        return MemorySearchLedger(snapshot: SearchLedgerMapping.snapshot(
            entries: state.entries, albumNotes: notes, memories: AlbumMemoryStore.shared.everyMemory,
            profileIds: profileIds, covers: state.covers, album: lookup))
    }
}

/// What the ledger answered for the query on screen (Android LibraryUiState.searchMemories and
/// searchMemoryAttachments): local, so it lands before the service answers.
@MainActor
@Observable
final class SearchMemories {
    /// The query [result] answers (trimmed); empty: nothing searched.
    private(set) var query = ""
    private(set) var result = SearchMemories.emptyResult
    /// The account in use when it was asked: its notes join the songs returned, its memories the albums.
    private(set) var activeProfileId: String?
    /// The ledger read for [query]: the album rows' attachments come from the same read.
    @ObservationIgnored private(set) var snapshot = SearchLedgerSnapshot.empty
    @ObservationIgnored private var pageCache: (key: PageKey, page: YoinSearchPage)?
    @ObservationIgnored private var attachmentCache: (key: [String], value: [YoinMemoryAttachment])?

    static var emptyResult: YoinMemorySearchResult { YoinMemorySearchResult(memories: [], notes: []) }

    var isEmpty: Bool { result.memories.isEmpty && result.notes.isEmpty }

    /// Searches [query] in [ledger], read once (Android: searchMemories before the service's search).
    func run(_ query: String, ledger: MemorySearchLedger, activeProfileId: String?) {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return clear() }
        let entries = (try? ledger.entries()) ?? []
        let titles = (try? ledger.aiTitles()) ?? []
        snapshot = SearchLedgerSnapshot(entries: entries, aiTitles: titles, covers: ledger.snapshot.covers)
        self.activeProfileId = activeProfileId
        result = YoinMemorySearch.companion.searchEntries(entries: entries, aiTitles: titles, query: q,
                                                          activeProfileId: activeProfileId)
        self.query = q
        pageCache = nil
        attachmentCache = nil
    }

    func clear() {
        guard !query.isEmpty || !isEmpty else { return }
        query = ""
        result = Self.emptyResult
        snapshot = .empty
        pageCache = nil
        attachmentCache = nil
    }

    /// The memories kept on the service's [albums] in the account in use, from the query's ledger read.
    func attachments(_ albums: [YoinSearchAlbum]) -> [YoinMemoryAttachment] {
        guard !albums.isEmpty, activeProfileId != nil else { return [] }
        let key = albums.map { "\($0.provider)|\($0.rawId)" }
        if let cached = attachmentCache, cached.key == key { return cached.value }
        let value = YoinMemorySearch.companion.attachmentsOf(albums: albums, entries: snapshot.entries,
                                                             aiTitles: snapshot.aiTitles, activeProfileId: activeProfileId)
        attachmentCache = (key, value)
        return value
    }

    /// The All page and the Memories list for [rows] at [columns] (:shared's `page`), kept while nothing changes.
    func page(_ rows: SearchServiceNames, query shown: String, columns: Int) -> YoinSearchPage {
        let key = PageKey(query: shown, memoriesQuery: query, rows: rows.keys, columns: columns,
                          activeProfileId: activeProfileId)
        if let cached = pageCache, cached.key == key { return cached.page }
        let attachments = attachments(rows.albums)
        let result = shown.trimmingCharacters(in: .whitespacesAndNewlines) == query ? self.result : Self.emptyResult
        let page = YoinMemorySearch.companion.page(
            query: shown, songs: rows.songs, albums: rows.albums, artists: rows.artists, playlists: rows.playlists,
            result: result, attachments: attachments, activeProfileId: activeProfileId, columns: Int32(columns),
            maxRows: YoinMemorySearch.companion.allMaxRows, maxWide: YoinMemorySearch.companion.allMaxWide)
        pageCache = (key, page)
        return page
    }

    private struct PageKey: Equatable {
        var query: String
        var memoriesQuery: String
        var rows: [String]
        var columns: Int
        var activeProfileId: String?
    }

    /// The cover a ledger album is drawn with (Home's for the same album), or one made from its key.
    func cover(_ album: YoinLedgerAlbum?) -> CoverPalette {
        guard let album else { return MemoryGridView.fallback("none") }
        return snapshot.covers[album.key] ?? MemoryGridView.fallback(album.key)
    }

    // MARK: Columns

    /// The All page's tile columns (Android searchTileColumns): Home's three in a phone's window, the Library
    /// grid's count wider (iPad, Mac, a phone on its side), at most eight. [width] is the whole pane's.
    nonisolated static func columns(width: CGFloat, regularWidth: Bool, compactHeight: Bool) -> Int {
        guard regularWidth || compactHeight || width >= 600 else { return 3 }
        return min(8, max(3, LibraryGrid.columns(width: width, regularWidth: regularWidth, compactHeight: compactHeight)))
    }
}

/// The service's rows by name, in its order, as the facade reads them (Android SearchServiceRows): a tile's
/// `index` points back into the arrays they came from.
struct SearchServiceNames {
    var songs: [YoinSearchSong] = []
    var albums: [YoinSearchAlbum] = []
    var artists: [YoinSearchArtist] = []
    var playlists: [YoinSearchPlaylist] = []

    init(songs: [(Album, Track)], albums: [Album], artists: [(id: MediaId, name: String)], playlists: [LibraryItem]) {
        self.songs = songs.map { a, t in
            YoinSearchSong(provider: t.id.provider, rawId: t.id.rawId, title: t.title, artist: t.artist ?? a.artist,
                           album: a.title)
        }
        self.albums = albums.map { YoinSearchAlbum(provider: $0.id.provider, rawId: $0.id.rawId, name: $0.title, artist: $0.artist) }
        self.artists = artists.map { YoinSearchArtist(provider: $0.id.provider, rawId: $0.id.rawId, name: $0.name) }
        self.playlists = playlists.map {
            YoinSearchPlaylist(provider: $0.id.provider, rawId: $0.id.rawId, name: $0.title, owner: $0.owner)
        }
    }

    /// Identity for the page cache: every row's ids and names.
    var keys: [String] {
        songs.map { "s|\($0.provider)|\($0.rawId)|\($0.title ?? "")|\($0.artist ?? "")|\($0.album ?? "")" }
            + albums.map { "a|\($0.provider)|\($0.rawId)|\($0.name)|\($0.artist ?? "")" }
            + artists.map { "r|\($0.provider)|\($0.rawId)|\($0.name)" }
            + playlists.map { "p|\($0.provider)|\($0.rawId)|\($0.name)|\($0.owner ?? "")" }
    }
}
