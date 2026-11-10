import Foundation

/// What the detail cache stores of the UI models (Android `DetailCacheDtos`):
/// plain values, covers as their storage key only. A Subsonic cover is its
/// cover id, never its signed URL (that carries the token); it is signed
/// again with the client of the day when read back. An Apple Music cover is
/// its artwork URL, which carries nothing secret. Palettes are not stored:
/// they are the placeholder hashed from the cover, rebuilt the same way.
enum DetailCacheDTO {
    struct Track: Codable, Sendable, Equatable {
        var provider: String
        var id: String
        var number: Int
        var title: String
        var durationSec: Int
        var rating: Double?
        var artist: String?
        var starred: Bool
        var playCount: Int?
        var disc: Int?
        var bitRateKbps: Int?
    }

    struct Album: Codable, Sendable, Equatable {
        var provider: String
        var id: String
        var title: String
        var artist: String
        var year: Int
        var cover: String?
        var tracks: [Track]
        var rating: Double?
        var kind: String
        var artistId: String?
        var starred: Bool
        var playCount: Int?
        var created: String?
        var songCount: Int?
        var durationSec: Int?
        var genre: String?
        /// The release's version tag (Album.version); absent in pages stored before it.
        var version: String? = nil
    }

    struct Entry: Codable, Sendable, Equatable {
        var album: Album
        var track: Track
    }

    struct Playlist: Codable, Sendable, Equatable {
        var provider: String
        var id: String
        var title: String
        var owner: String
        var isPublic: Bool?
        var description: String?
        var cover: String?
        var entries: [Entry]
        /// Spotify's write metadata (PlaylistInfo); absent in pages stored before it.
        var ownedByMe: Bool? = nil
        var collaborative: Bool? = nil
        var snapshotId: String? = nil
        var positions: [Int]? = nil
        var canWrite: Bool? = nil
    }

    struct ArtistPage: Codable, Sendable, Equatable {
        var provider: String
        var id: String
        var name: String
        var starred: Bool
        var cover: String?
        var releases: [Album]
        var mostPlayed: [Entry]
    }

    struct Item: Codable, Sendable, Equatable {
        var provider: String
        var id: String
        var kind: String
        var title: String
        var subtitle: String
        var cover: String?
        var album: Album?
        var count: Int?
        /// Playlists' owner fields; absent in rows stored before By You read them.
        var owner: String? = nil
        var ownedByMe: Bool? = nil
        /// A playlist's library date; absent in rows stored before Recently added dated playlists.
        var addedAt: String? = nil
    }

    struct Home: Codable, Sendable, Equatable {
        var recent: [Album]
        var frequent: [Album]
        var newest: [Album]
        var highest: [Album]
        var random: [Album]
        var recentTracks: [Entry]
    }
}

/// UI models ⇄ [DetailCacheDTO] for one source: how a cover is kept and
/// how its palette comes back.
struct DetailCacheCodec {
    /// A palette from a stored cover key; [seed] stands in for a missing cover.
    var palette: (_ cover: String?, _ seed: String) -> CoverPalette
    var coverKey: (CoverPalette) -> String?

    /// Subsonic: the cover id, signed again by today's client.
    static func subsonic(_ mapping: SubsonicMapping) -> DetailCacheCodec {
        DetailCacheCodec(
            palette: { cover, _ in mapping.palette(coverId: cover) },
            coverKey: { p in
                p.cover.flatMap { URLComponents(url: $0.url, resolvingAgainstBaseURL: false)?.queryItems?
                    .first { $0.name == "id" }?.value }
            })
    }

    /// Apple Music: the artwork URL as it is.
    static var appleMusic: DetailCacheCodec { DetailCacheCodec(
        palette: { cover, seed in AppleMusicMapping.palette(cover.flatMap(URL.init(string:)), key: seed) },
        coverKey: { $0.cover?.url.absoluteString }) }

    // MARK: Out

    func dto(_ t: Track) -> DetailCacheDTO.Track {
        .init(provider: t.id.provider, id: t.id.rawId, number: t.number, title: t.title, durationSec: t.durationSec,
              rating: t.rating, artist: t.artist, starred: t.starred, playCount: t.playCount, disc: t.disc,
              bitRateKbps: t.bitRateKbps)
    }

    func dto(_ a: Album) -> DetailCacheDTO.Album {
        .init(provider: a.id.provider, id: a.id.rawId, title: a.title, artist: a.artist, year: a.year,
              cover: coverKey(a.palette), tracks: a.tracks.map(dto), rating: a.rating, kind: a.kind.rawValue,
              artistId: a.artistId, starred: a.starred, playCount: a.playCount, created: a.created,
              songCount: a.songCount, durationSec: a.durationSec, genre: a.genre, version: a.version)
    }

    func dtoAlbums(_ a: [Album]) -> [DetailCacheDTO.Album] { a.map(dto) }

    func dto(_ e: PlaylistEntry) -> DetailCacheDTO.Entry { .init(album: dto(e.album), track: dto(e.track)) }

    func dto(_ p: PlaylistInfo) -> DetailCacheDTO.Playlist {
        .init(provider: p.id.provider, id: p.id.rawId, title: p.title, owner: p.owner, isPublic: p.isPublic,
              description: p.description, cover: coverKey(p.palette), entries: p.entries.map(dto),
              ownedByMe: p.ownedByMe, collaborative: p.collaborative ? true : nil, snapshotId: p.snapshotId,
              positions: p.positions, canWrite: p.canWrite)
    }

    func dto(_ a: ArtistPage) -> DetailCacheDTO.ArtistPage {
        .init(provider: a.id.provider, id: a.id.rawId, name: a.name, starred: a.starred, cover: coverKey(a.palette),
              releases: a.releases.map(dto), mostPlayed: a.mostPlayed.map(dto))
    }

    func dto(_ i: LibraryItem) -> DetailCacheDTO.Item {
        let kind = switch i.kind { case .album: "album"; case .artist: "artist"; case .playlist: "playlist" }
        return .init(provider: i.id.provider, id: i.id.rawId, kind: kind, title: i.title, subtitle: i.subtitle,
                     cover: coverKey(i.palette), album: i.album.map(dto), count: i.count, owner: i.owner,
                     ownedByMe: i.ownedByMe, addedAt: i.addedAt)
    }

    func dto(_ h: LibraryStore.HomeLists) -> DetailCacheDTO.Home {
        .init(recent: h.recent.map(dto), frequent: h.frequent.map(dto), newest: h.newest.map(dto),
              highest: h.highest.map(dto), random: h.random.map(dto), recentTracks: h.recentTracks.map(dto))
    }

    // MARK: In

    func track(_ t: DetailCacheDTO.Track) -> Track {
        Track(id: MediaId(provider: t.provider, rawId: t.id), number: t.number, title: t.title,
              durationSec: t.durationSec, rating: t.rating, artist: t.artist, starred: t.starred,
              playCount: t.playCount, disc: t.disc, bitRateKbps: t.bitRateKbps)
    }

    func album(_ a: DetailCacheDTO.Album) -> Album {
        Album(id: MediaId(provider: a.provider, rawId: a.id), title: a.title, artist: a.artist, year: a.year,
              palette: palette(a.cover, a.id), tracks: a.tracks.map(track), rating: a.rating,
              kind: ReleaseKind(rawValue: a.kind) ?? .album, artistId: a.artistId, starred: a.starred,
              playCount: a.playCount, created: a.created, songCount: a.songCount, durationSec: a.durationSec,
              genre: a.genre, version: a.version)
    }

    func entry(_ e: DetailCacheDTO.Entry) -> PlaylistEntry { PlaylistEntry(album: album(e.album), track: track(e.track)) }

    func playlist(_ p: DetailCacheDTO.Playlist) -> PlaylistInfo {
        PlaylistInfo(id: MediaId(provider: p.provider, rawId: p.id), title: p.title, owner: p.owner,
                     isPublic: p.isPublic, description: p.description, palette: palette(p.cover, p.id),
                     entries: p.entries.map(entry), ownedByMe: p.ownedByMe, collaborative: p.collaborative ?? false,
                     snapshotId: p.snapshotId, positions: p.positions, canWrite: p.canWrite)
    }

    func artistPage(_ a: DetailCacheDTO.ArtistPage) -> ArtistPage {
        ArtistPage(id: MediaId(provider: a.provider, rawId: a.id), name: a.name, starred: a.starred,
                   palette: palette(a.cover, a.id), releases: a.releases.map(album), mostPlayed: a.mostPlayed.map(entry))
    }

    func item(_ i: DetailCacheDTO.Item) -> LibraryItem {
        let kind: LibraryItem.Kind = switch i.kind { case "artist": .artist; case "playlist": .playlist; default: .album }
        return LibraryItem(id: MediaId(provider: i.provider, rawId: i.id), kind: kind, title: i.title,
                           subtitle: i.subtitle, palette: palette(i.cover, i.id), album: i.album.map(album),
                           count: i.count, owner: i.owner, ownedByMe: i.ownedByMe, addedAt: i.addedAt)
    }

    func home(_ h: DetailCacheDTO.Home) -> LibraryStore.HomeLists {
        LibraryStore.HomeLists(recent: h.recent.map(album), frequent: h.frequent.map(album), newest: h.newest.map(album),
                               highest: h.highest.map(album), random: h.random.map(album),
                               recentTracks: h.recentTracks.map(entry))
    }
}
