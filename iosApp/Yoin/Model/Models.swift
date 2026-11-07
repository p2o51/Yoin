import SwiftUI

// Neutral domain models, shaped after the Android `data/model` layer so a
// shared KMP module can replace them later. UI code only talks to these
// structs and to the `MusicLibrary` protocol, never to mock internals.

struct MediaId: Hashable, Sendable {
    var provider: String
    var rawId: String
}

/// Three colors per cover; every derived tint comes from these.
///
/// A real cover (`cover` set) answers with the colors read off its image once
/// `CoverArtwork` has loaded it, and with the placeholder colors until then.
/// The read goes through an observable slot, so views that use the colors
/// redraw (and animate) when the image lands.
struct CoverPalette: Hashable, Sendable {
    private var a0: Color
    private var b0: Color
    private var c0: Color
    /// The server's cover image, when there is one.
    var cover: CoverRef?

    init(a: Color, b: Color, c: Color, cover: CoverRef? = nil) {
        a0 = a
        b0 = b
        c0 = c
        self.cover = cover
    }

    private var derived: CoverArtwork.Colors? { cover.flatMap { CoverArtwork.shared.colors($0) } }
    var a: Color { derived?.a ?? a0 }
    var b: Color { derived?.b ?? b0 }
    var c: Color { derived?.c ?? c0 }

    /// Accent used for tinted glass and primary actions.
    var tint: Color { a }
    /// Solid card background, the cover color washed into the page surface.
    var container: Color { Yoin.surface.mix(with: a, by: 0.24, in: .perceptual) }
    var containerStrong: Color { Yoin.surface.mix(with: a, by: 0.42, in: .perceptual) }
    /// Text/ink that sits on `container`.
    var onContainer: Color { a.mix(with: Yoin.ink, by: 0.55, in: .perceptual) }
    /// Deep version of the cover for the flat Now Playing base.
    var deep: Color { a.mix(with: .black, by: 0.45, in: .perceptual) }
}

struct Track: Identifiable, Hashable, Sendable {
    var id: MediaId
    var number: Int
    var title: String
    var durationSec: Int
    /// Yoin's 0–10 scale (a server's 1–5 stars × 2).
    var rating: Double?
    /// The track's own artist (nil = the album's).
    var artist: String? = nil
    /// The heart: the server's favorite flag.
    var starred: Bool = false
    var playCount: Int? = nil
    var disc: Int? = nil
    /// The file's bit rate as the server lists it (Subsonic), for the stream quality.
    var bitRateKbps: Int? = nil
    /// Every credited artist with the page each opens, when the service names more than one (Android
    /// Track.artistCredits); empty = just [artist].
    var credits: [ArtistCredit] = []
}

enum ReleaseKind: String, Hashable, Sendable {
    case album = "Album", ep = "EP", single = "Single", compilation = "Compilation"

    /// The release's word in a mixed list ("Album", "Single", …).
    var label: String {
        switch self {
        case .album: String(localized: "Album")
        case .ep: String(localized: "EP")
        case .single: String(localized: "Single")
        case .compilation: String(localized: "Compilation")
        }
    }
}

/// A mixed list's subtitle, Spotify's way (51, 2026-11-07): what it is, Spotify's
/// bullet, then who: "Album • Kiri Ono", "Playlist • owner", "Artist" alone. The kind is
/// a label, the name is data; a name that only repeats the kind is dropped.
enum KindLine {
    static func make(_ kind: String, _ name: String) -> String {
        let n = name.trimmingCharacters(in: .whitespaces)
        guard !n.isEmpty, n.caseInsensitiveCompare(kind) != .orderedSame,
              !["album", "artist", "playlist", "song", "track"].contains(n.lowercased()) else { return kind }
        return "\(kind) \u{2022} \(n)"
    }
}

extension Album {
    /// "Album • Artist" (or Single / EP / Compilation).
    var kindLine: String { KindLine.make(kind.label, artist) }
}

struct Album: Identifiable, Hashable, Sendable {
    var id: MediaId
    var title: String
    var artist: String
    var year: Int
    var palette: CoverPalette
    var tracks: [Track]
    var rating: Double?
    var kind: ReleaseKind = .album
    var artistId: String? = nil
    var starred: Bool = false
    var playCount: Int? = nil
    /// When the server added it (ISO-8601, as the server sent it).
    var created: String? = nil
    /// The server's counts, for list albums whose tracks aren't loaded yet.
    var songCount: Int? = nil
    var durationSec: Int? = nil
    var genre: String? = nil

    var coverURL: URL? { palette.cover?.url }
    /// Tracks loaded, else the server's count.
    var trackCount: Int { tracks.isEmpty ? (songCount ?? 0) : tracks.count }
    var totalSec: Int { tracks.isEmpty ? (durationSec ?? 0) : tracks.map(\.durationSec).reduce(0, +) }

    var ratedCount: Int { tracks.filter { $0.rating != nil }.count }
    var averageRating: Double? {
        let rated = tracks.compactMap(\.rating)
        guard !rated.isEmpty else { return nil }
        return rated.reduce(0, +) / Double(rated.count)
    }
}

enum LibraryKind: String, CaseIterable, Sendable {
    case playlists = "Playlists"
    case artists = "Artists"
    case albums = "Albums"
    case songs = "Songs"
    case favorites = "Favorites"

    /// The chip's text in the reader's language (the raw value stays the stored key).
    var title: String {
        switch self {
        case .playlists: String(localized: "Playlists")
        case .artists: String(localized: "Artists")
        case .albums: String(localized: "Albums")
        case .songs: String(localized: "Songs")
        case .favorites: String(localized: "Favorites")
        }
    }
}

struct LibraryItem: Identifiable, Hashable, Sendable {
    enum Kind: Hashable, Sendable { case album, artist, playlist }
    var id: MediaId
    var kind: Kind
    var title: String
    var subtitle: String
    var palette: CoverPalette
    var album: Album?
    /// Artists: album count; playlists: song count.
    var count: Int? = nil
    /// Playlists: who made it, where the source says (Android Playlist.owner).
    var owner: String? = nil
    /// Playlists: the signed-in user made it (Android Playlist.ownedByMe, By You); nil where the source
    /// doesn't say.
    var ownedByMe: Bool? = nil
    /// Playlists: when it joined the library (ISO-8601; Android Playlist.libraryAddedAt: Subsonic's created,
    /// an Apple library playlist's dateAdded; Spotify dates none), for Recently added.
    var addedAt: String? = nil
}

struct ActivityEntry: Identifiable, Hashable, Sendable {
    var id: String
    var eyebrow: String
    /// The cover, palette and words the card shows. An album is the album; an artist or a
    /// playlist is made from its log row (title = its name, artist = its subtitle).
    var album: Album
    /// What the card is about, so it renders and opens its real target (Android `HomeEntryTarget`).
    var entity: ActivityEntity = .album
    /// The entity's own id (the album's, artist's or playlist's); nil = the album's.
    var entityId: String? = nil

    var targetId: String { entityId ?? album.id.rawId }

    /// The backdrop shape: an artist is a circle, a playlist its own, an album the Bun.
    var backdrop: EntityBackdrop.Entity {
        switch entity {
        case .artist: .artist
        case .playlist: .playlist
        case .album: .album
        case .song: .song
        }
    }

    /// The card's subtitle in a mixed list: "Album • Artist", "Playlist • owner", "Artist".
    var kindLine: String {
        switch entity {
        case .artist: typeLabel
        case .album: album.kindLine
        default: KindLine.make(typeLabel, album.artist)
        }
    }

    /// The small card's type line (Android `activityTypeLabel`).
    var typeLabel: String {
        switch entity {
        case .album: album.kind.label
        case .artist: String(localized: "Artist")
        case .playlist: String(localized: "Playlist")
        case .song: String(localized: "Track")
        }
    }
}

struct PlaybackSnapshot: Sendable {
    var album: Album
    var track: Track
    var positionSec: Int
    var isPlaying: Bool
    var lyricLine: String
    var nextLyricLine: String
    var lyrics: [String] = []
    var activeLyric: Int = 0
}

// MARK: - Source protocols (the KMP module will implement these)

protocol MusicLibrary: Sendable {
    func albums() -> [Album]
    func libraryItems() -> [LibraryItem]
    func activities() -> [ActivityEntry]
    func jumpBackIn() -> [Album]
}

protocol PlaybackSource: Sendable {
    func current() -> PlaybackSnapshot
}

/// A playlist: mixed tracks, each with its own album.
struct PlaylistEntry: Hashable, Sendable {
    var album: Album
    var track: Track
}

struct PlaylistInfo: Hashable, Sendable {
    var id: MediaId
    var title: String
    var owner: String
    var isPublic: Bool?
    var description: String?
    var palette: CoverPalette
    var entries: [PlaylistEntry]
    /// The service wouldn't hand over the songs (Spotify: someone else's playlist since 2026-02):
    /// the page says so instead of showing an empty playlist (Android tracksUnavailable).
    var tracksUnavailable = false
    /// How many entries the service says it holds (Android songCount): the Length a playlist whose songs
    /// weren't handed over still shows. nil: count [entries].
    var songCount: Int? = nil
    /// Spotify (Android ownedByMe): whether the signed-in user made it; nil where the source doesn't say.
    var ownedByMe: Bool? = nil
    /// Spotify: its owner lets others add and remove songs.
    var collaborative = false
    /// Spotify's version of the playlist the entries were read at; a removal names it (Android snapshotId).
    var snapshotId: String? = nil
    /// Spotify: each entry's position among all the playlist's items (local files and episodes count), so a
    /// removal names the one occurrence.
    var positions: [Int]? = nil
    /// Subsonic (Android canWrite): not readonly, and the user's or nobody's. nil where not read.
    var canWrite: Bool? = nil

    /// The playlist as an Album, so it can reuse the album page's hero,
    /// spectrum and pull-up (Android shares them the same way).
    var asAlbum: Album {
        Album(id: id, title: title, artist: owner, year: 0, palette: palette,
              tracks: entries.enumerated().map { i, e in
                  var t = e.track; t.number = i + 1; return t
              })
    }
}

/// An artist page's address: the name for the mock library, plus the
/// server's id for a real one.
struct ArtistRef: Hashable, Sendable {
    var name: String
    var id: String?

    init(_ name: String, id: String? = nil) {
        self.name = name
        self.id = id
    }
}

/// What the artist page shows: releases newest first, the most-played songs.
struct ArtistPage: Hashable, Sendable {
    var id: MediaId
    var name: String
    var starred: Bool
    var palette: CoverPalette
    var releases: [Album]
    var mostPlayed: [PlaylistEntry]
}

/// A Rediscover card.
struct RediscoverEntry: Hashable, Sendable {
    var album: Album
    var reason: String
    var away: String
    var score: Double?
    /// Left group (a date) and the plays count.
    var footnoteDate: String
    var footnotePlays: String
    /// A song card (Android `HomeRediscoverItem.song`): the song a tap plays; nil = an album card.
    var song: PlaylistEntry? = nil
    /// A song card's newest note, when no score badge says it.
    var noteSnippet: String? = nil
}

/// Home's sections, in the order the page lays them out.
struct HomeFeed: Hashable, Sendable {
    enum Layout: Hashable, Sendable {
        /// The hand-placed mock shelf the screenshots use (signal cards, memory marks).
        case mock
        /// A server's lists: plain cells, as many as came back.
        case server
    }

    var layout: Layout
    /// [0] hero, [1] artist card, [2] wide card, [3] strip.
    var activities: [ActivityEntry]
    var jumpBackIn: [Album]
    /// Recently Added's 2 × 2 track grid.
    var recentTracks: [PlaylistEntry]
    var recentAlbums: [Album]
    var rediscover: [RediscoverEntry]
    /// Jump Back In's 1×2s from local history (server layout): the rated
    /// album, then the noted track. The mock layout places its own.
    var signals: [JBISignal] = []
    /// Raw album id → its cover's memory mark (server layout).
    var memoryMarks: [String: AlbumMemoryMark] = [:]
    /// Recently Played (Android `recentlyPlayed`): the source's own recent
    /// albums (any device), less the ones Activities already shows.
    var recentlyPlayed: [Album] = []
    /// Your Playlists: the library's playlists (Android HOME_PLAYLIST_LIMIT).
    var playlists: [LibraryItem] = []

    var isEmpty: Bool {
        activities.isEmpty && jumpBackIn.isEmpty && recentTracks.isEmpty && recentAlbums.isEmpty && rediscover.isEmpty
            && signals.isEmpty && recentlyPlayed.isEmpty && playlists.isEmpty
    }
}
