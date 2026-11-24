import Foundation
import YoinShared

/*
 * The @ popup's rows (Android ui/notes/editor/MentionCandidates.kt): what is playing (song, album, artist),
 * recent plays, then the account's service search for the words typed. They are ordered by :shared's
 * YoinMentionRanking (the same ranking Android uses), Chinese names spelled by PinyinRomanizer.
 */

/// One row of the @ popup: what it inserts, and how it's shown.
struct MentionRow: Hashable, Identifiable {
    var kind: MentionKind
    var provider: String
    var rawId: String
    var title: String
    /// The second line's artist (a song's, an album's); nil for an artist.
    var artist: String?
    /// The second line's album (a song's), muted after the artist.
    var album: String?
    var cover: CoverRef?
    var source: YoinMentionSource

    var id: String { "\(kind.token)|\(provider)|\(rawId)" }

    /// What picking it inserts; nil when the provider or id can't make a mention.
    var mention: YoinNoteMention? {
        YoinNoteEditor.shared.mention(kind: kind, provider: provider, id: rawId, title: title, artist: artist, album: album)
    }
}

/// The popup's rows by kind, in the order shown.
struct MentionRows: Equatable {
    var tracks: [MentionRow] = []
    var albums: [MentionRow] = []
    var artists: [MentionRow] = []
    /// The kinds in listing order (the best match's kind first once something is typed).
    var order: [MentionKind] = YoinMentionRanking.shared.defaultOrder

    nonisolated(unsafe) static let empty = MentionRows()

    func of(_ kind: MentionKind) -> [MentionRow] {
        switch kind {
        case .track: tracks
        case .album: albums
        default: artists
        }
    }

    /// Every row in listing order: what ↑ ↓ walk; the first is what Return picks.
    var all: [MentionRow] { order.flatMap(of) }

    var isEmpty: Bool { tracks.isEmpty && albums.isEmpty && artists.isEmpty }

    /// These rows and [other]'s, each item once (the first seen keeps its place).
    func merge(_ other: MentionRows) -> MentionRows {
        func once(_ a: [MentionRow], _ b: [MentionRow]) -> [MentionRow] {
            var seen = Set<String>()
            return (a + b).filter { seen.insert($0.id).inserted }
        }
        return MentionRows(tracks: once(tracks, other.tracks), albums: once(albums, other.albums),
                           artists: once(artists, other.artists))
    }

    /// An earlier search's rows: listed after the current search's, and only while they still match.
    func withoutSearchRows() -> MentionRows {
        func known(_ rows: [MentionRow]) -> [MentionRow] {
            rows.map { var r = $0; if r.source == .search { r.source = .seen }; return r }
        }
        return MentionRows(tracks: known(tracks), albums: known(albums), artists: known(artists), order: order)
    }

    func capped(_ n: Int) -> MentionRows {
        MentionRows(tracks: Array(tracks.prefix(n)), albums: Array(albums.prefix(n)), artists: Array(artists.prefix(n)),
                    order: order)
    }
}

enum MentionRanking {
    /// [rows] ordered for [query] by :shared's ranking; a blank query leaves them as they are.
    static func rank(_ query: String, _ rows: MentionRows, romanizer: YoinMentionRomanizer) -> MentionRows {
        func input(_ list: [MentionRow]) -> [YoinMentionRankRow] {
            list.map { YoinMentionRankRow(title: $0.title, artist: $0.artist, source: $0.source) }
        }
        let ranked = YoinMentionRanking.shared.rank(query: query, tracks: input(rows.tracks), albums: input(rows.albums),
                                                    artists: input(rows.artists), romanizer: romanizer)
        func pick(_ indexes: [KotlinInt], _ list: [MentionRow]) -> [MentionRow] { indexes.map { list[$0.intValue] } }
        return MentionRows(tracks: pick(ranked.tracks, rows.tracks), albums: pick(ranked.albums, rows.albums),
                           artists: pick(ranked.artists, rows.artists), order: ranked.order)
    }

    /// Typing: the rows already offered, filtered at once while the search for the new words is on its way.
    static func whileSearching(_ query: String, pool: MentionRows, romanizer: YoinMentionRomanizer, cap: Int) -> MentionRows {
        rank(query, pool.withoutSearchRows(), romanizer: romanizer).capped(cap)
    }

    /// The search's rows for [query], with any row from [pool] that matches ranked in among them.
    static func afterSearch(_ query: String, found: MentionRows, pool: MentionRows, romanizer: YoinMentionRomanizer,
                            cap: Int) -> MentionRows {
        if query.isEmpty { return found }
        return rank(query, found.merge(pool.withoutSearchRows()), romanizer: romanizer).capped(cap)
    }
}

/// Pinyin for Chinese names from the system's own transform (CFStringTransform Mandarin → Latin, marks
/// stripped), so Yoin carries no table: "纽约" → "niu yue". Nil for names without Han characters.
final class PinyinRomanizer: NSObject, YoinMentionRomanizer, @unchecked Sendable {
    static let shared = PinyinRomanizer()
    private let cache = NSCache<NSString, NSString>()

    func romanize(text: String) -> String? {
        guard text.unicodeScalars.contains(where: Self.isHan) else { return nil }
        if let hit = cache.object(forKey: text as NSString) { return hit as String }
        let s = NSMutableString(string: text)
        guard CFStringTransform(s, nil, kCFStringTransformMandarinLatin, false),
              CFStringTransform(s, nil, kCFStringTransformStripCombiningMarks, false) else { return nil }
        cache.setObject(s, forKey: text as NSString)
        return s as String
    }

    static func isHan(_ u: Unicode.Scalar) -> Bool {
        u.properties.isIdeographic || (0x3400...0x9FFF).contains(u.value) || (0x20000...0x323AF).contains(u.value)
    }
}

/// Where the popup gets its rows.
@MainActor
protocol MentionRowSource {
    /// [query] empty: what's playing (song, album, artist) and recent plays; otherwise search results.
    func rows(query: String, limit: Int) async -> MentionRows
}

/// The account in use: the song playing, its album and artist, recent plays; a query searches the service
/// (library and catalogue, as Search does) without touching the Search page's own results.
@MainActor
struct AccountMentionRows: MentionRowSource {
    var romanizer: YoinMentionRomanizer = PinyinRomanizer.shared

    func rows(query: String, limit: Int) async -> MentionRows {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        let recents = recentTracks()
        if q.isEmpty {
            let current = currentRows()
            var seen = Set<String>()
            let tracks = (current.tracks + recents).filter { seen.insert($0.id).inserted }
            return MentionRows(tracks: Array(tracks.prefix(limit)), albums: current.albums, artists: current.artists)
        }
        let found = await search(q)
        var seen = Set<String>()
        let ranked = MentionRanking.rank(q, MentionRows(
            tracks: (recents + found.tracks).filter { seen.insert($0.id).inserted },
            albums: found.albums, artists: found.artists), romanizer: romanizer)
        return MentionRows(tracks: Array(ranked.tracks.prefix(limit)), albums: Array(ranked.albums.prefix(limit / 2 + 1)),
                           artists: Array(ranked.artists.prefix(limit / 2 + 1)), order: ranked.order)
    }

    private func currentRows() -> MentionRows {
        let model = AppModel.shared
        let playback = model.playback
        let album = playback.album, track = playback.track
        guard !track.title.isEmpty else { return .empty }
        let provider = track.id.provider
        let tracks = [MentionRow(kind: .track, provider: provider, rawId: track.id.rawId, title: track.title,
                                 artist: track.artist ?? album.artist, album: album.title, cover: album.palette.cover,
                                 source: .current)]
        var albums: [MentionRow] = []
        if !album.id.rawId.isEmpty, !album.id.rawId.hasPrefix("song:"), !album.title.isEmpty {
            albums = [MentionRow(kind: .album, provider: album.id.provider, rawId: album.id.rawId, title: album.title,
                                 artist: album.artist, album: nil, cover: album.palette.cover, source: .current)]
        }
        var artists: [MentionRow] = []
        if let artistId = album.artistId, !artistId.isEmpty, !album.artist.isEmpty {
            artists = [MentionRow(kind: .artist, provider: album.id.provider, rawId: artistId, title: album.artist,
                                  artist: nil, album: nil, cover: nil, source: .current)]
        }
        return MentionRows(tracks: tracks, albums: albums, artists: artists)
    }

    private func recentTracks() -> [MentionRow] {
        let store = AppModel.shared.store
        guard store.isReal else { return mockRecents() }
        let plays = HistoryStore.shared.plays(scope: store.historyScope, provider: store.provider)
        var seen = Set<String>()
        var rows: [MentionRow] = []
        for p in plays.reversed() where !p.songId.isEmpty && !p.title.isEmpty && seen.insert(p.songId).inserted {
            rows.append(MentionRow(kind: .track, provider: p.provider, rawId: p.songId, title: p.title,
                                   artist: p.artist.isEmpty ? nil : p.artist, album: p.album.isEmpty ? nil : p.album,
                                   cover: Self.cover(key: p.coverKey, id: p.albumId ?? p.songId), source: .recent))
            if rows.count >= 40 { break }
        }
        return rows
    }

    /// The mock library (screenshots, signed out): its songs as recent plays.
    private func mockRecents() -> [MentionRow] {
        MockLibrary.allAlbums.prefix(6).flatMap { album in
            album.tracks.prefix(2).map { t in
                MentionRow(kind: .track, provider: t.id.provider, rawId: t.id.rawId, title: t.title,
                           artist: t.artist ?? album.artist, album: album.title, cover: album.palette.cover, source: .recent)
            }
        }
    }

    private func search(_ q: String) async -> MentionRows {
        let store = AppModel.shared.store
        if !store.isReal { return mockSearch(q) }
        guard let hits = try? await store.mentionSearch(q) else { return .empty }
        let tracks = hits.songs.map { e in
            MentionRow(kind: .track, provider: e.track.id.provider, rawId: e.track.id.rawId, title: e.track.title,
                       artist: e.track.artist ?? e.album.artist, album: e.album.title.isEmpty ? nil : e.album.title,
                       cover: e.album.palette.cover, source: .search)
        }
        let albums = hits.albums.map { a in
            MentionRow(kind: .album, provider: a.id.provider, rawId: a.id.rawId, title: a.title,
                       artist: a.artist.isEmpty ? nil : a.artist, album: nil, cover: a.palette.cover, source: .search)
        }
        let artists = hits.artists.map { a in
            MentionRow(kind: .artist, provider: a.id.provider, rawId: a.id.rawId, title: a.title, artist: nil, album: nil,
                       cover: a.palette.cover, source: .search)
        }
        return MentionRows(tracks: tracks, albums: albums, artists: artists)
    }

    private func mockSearch(_ q: String) -> MentionRows {
        let albums = MockLibrary.allAlbums
        let tracks = albums.flatMap { album in
            album.tracks.map { t in
                MentionRow(kind: .track, provider: t.id.provider, rawId: t.id.rawId, title: t.title,
                           artist: t.artist ?? album.artist, album: album.title, cover: album.palette.cover, source: .search)
            }
        }
        let albumRows = albums.map { a in
            MentionRow(kind: .album, provider: a.id.provider, rawId: a.id.rawId, title: a.title, artist: a.artist,
                       album: nil, cover: a.palette.cover, source: .search)
        }
        // The mock "service" matches like a search would: names holding the words, accents and case folded.
        let ranking = YoinMentionRanking.shared
        func hit(_ r: MentionRow) -> Bool {
            ranking.tier(query: q, title: r.title, artist: r.artist, romanizer: romanizer) != .none
        }
        return MentionRows(tracks: tracks.filter(hit), albums: albumRows.filter(hit), artists: [])
    }

    /// A play's cover (Subsonic: its cover id; other providers: the artwork URL it was stored with).
    static func cover(key: String?, id: String) -> CoverRef? {
        let store = AppModel.shared.store
        if let client = store.client { return SubsonicMapping(client: client).palette(coverId: key ?? id).cover }
        return AppleMusicMapping.palette(key.flatMap(URL.init(string:)), key: id).cover
    }
}
