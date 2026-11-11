import Foundation

/*
 * Jump Back In's covers, picked the way Android's `buildWidgetGrid` does (HomeViewModel.kt): three pools
 * (albums, songs, playlists), the cards the 1×2 signal cards already show left out, art-less ones behind
 * the rest, round-robined 3 albums : 2 songs : 3 playlists, then the leftovers, at most 28 covers.
 * Pure: no UI types, unit-tested (HomeJbiPoolsTests).
 *
 * Where iOS differs from Android (the data it has):
 * - Android rolls its pools once per 6 h and persists the roll. iOS keeps the album and song pools in the
 *   stored Home lists (the server's own random reads) and orders the playlist pool by a hash of the id and
 *   the 6 h window, so the same window always lands the same way without a stored shuffle.
 * - The song pool is the server's `getRandomSongs` on Subsonic. Apple Music and Spotify have no random read
 *   here; their pool is the songs last played (what Recently Added's grid is made from).
 * - A short album pool (Apple Music / Spotify, whose "random" is the older library) is topped up from the
 *   recent / frequent albums; Android's has no fallback.
 */

/// One cover of the Jump Back In shelf: an album, a song, or a playlist.
enum JBIItem: Hashable, Sendable {
    case album(Album)
    case track(PlaylistEntry)
    case playlist(LibraryItem)

    var stableId: String {
        switch self {
        case let .album(a): "cover:album:\(a.id.provider):\(a.id.rawId)"
        case let .track(e): "cover:song:\(e.track.id.provider):\(e.track.id.rawId)"
        case let .playlist(p): "cover:playlist:\(p.id.provider):\(p.id.rawId)"
        }
    }

    var palette: CoverPalette {
        switch self {
        case let .album(a): a.palette
        case let .track(e): e.album.palette
        case let .playlist(p): p.palette
        }
    }

    /// Whether the cover has artwork (Android `coverArtUrl != null`); a generated palette alone is a "hole".
    var hasArt: Bool { palette.cover != nil }

    /// The album a cover stands for in Home's strips and the memory marks (a playlist has none).
    var album: Album? {
        switch self {
        case let .album(a): a
        case let .track(e): e.album
        case .playlist: nil
        }
    }
}

enum HomeJbiPools {
    /// Android GRID_POOL_ALBUMS / TRACKS / PLAYLISTS and GRID_MAX_COMPACTS.
    static let albumPoolSize = 14
    static let trackPoolSize = 10
    static let playlistPoolSize = 10
    static let maxCovers = 28
    /// Android GRID_POOLS_TTL_MS: how long a roll of the playlist pool stands.
    static let rotationWindowMs: Int64 = 6 * 60 * 60 * 1000

    /// The album pool: [random] first (Android's `getAlbumList("random")`), topped up from [fallback], each
    /// album once, at most 14.
    static func albumPool(random: [Album], fallback: [Album]) -> [Album] {
        var seen = Set<MediaId>()
        return (random + fallback).filter { seen.insert($0.id).inserted }.prefix(albumPoolSize).map { $0 }
    }

    /// The song pool: each song once, at most 10.
    static func trackPool(_ tracks: [PlaylistEntry]) -> [PlaylistEntry] {
        var seen = Set<MediaId>()
        return tracks.filter { seen.insert($0.track.id).inserted }.prefix(trackPoolSize).map { $0 }
    }

    /// The playlist pool: each playlist once, in this window's order, artless ones only when there aren't
    /// enough with artwork, at most 10 (Android: shuffled, then `sortedBy { coverArt == null }`).
    static func playlistPool(_ playlists: [LibraryItem], now: Int64) -> [LibraryItem] {
        let window = now / rotationWindowMs
        var seen = Set<MediaId>()
        let rolled = playlists.filter { $0.kind == .playlist && seen.insert($0.id).inserted }
            .map { ($0, stableHash("\($0.id.provider):\($0.id.rawId)#\(window)")) }
            .sorted { $0.1 < $1.1 }
            .map(\.0)
        return Array((rolled.filter { $0.palette.cover != nil } + rolled.filter { $0.palette.cover == nil })
            .prefix(playlistPoolSize))
    }

    /// FNV-1a: the same on every launch (Swift's `hashValue` is seeded per process).
    static func stableHash(_ s: String) -> UInt64 {
        var h: UInt64 = 0xcbf2_9ce4_8422_2325
        for b in s.utf8 { h = (h ^ UInt64(b)) &* 0x100_0000_01b3 }
        return h
    }

    /// The shelf's covers: [albums] / [tracks] / [playlists] are the pools (already trimmed); the ones a
    /// signal card shows are left out ([excludedAlbums], [excludedTracks]; Android `wideIds`).
    static func select(albums: [Album], tracks: [PlaylistEntry], playlists: [LibraryItem],
                       excludedAlbums: Set<MediaId> = [], excludedTracks: Set<MediaId> = []) -> [JBIItem] {
        // Covers first inside each pool (a stable sort keeps the pool's order within each group) ...
        let albumPool = albums.filter { !excludedAlbums.contains($0.id) }.map(JBIItem.album).artFirst()
        let trackPool = tracks.filter { !excludedTracks.contains($0.track.id) }.map(JBIItem.track).artFirst()
        let playlistPool = playlists.map(JBIItem.playlist).artFirst()
        let primary = interleave(Array(albumPool.prefix(3)), Array(trackPool.prefix(2)), Array(playlistPool.prefix(3)))
        let rest = interleave(Array(albumPool.dropFirst(3)), Array(trackPool.dropFirst(2)),
                              Array(playlistPool.dropFirst(3)))
        // ... and across the whole shelf, so a spare album with art beats an artless playlist.
        return Array((primary + rest).artFirst().prefix(maxCovers))
    }

    /// Round-robin across the groups: album, song, playlist, album, … (Android `interleaveCards`).
    static func interleave<T>(_ groups: [T]...) -> [T] {
        var out: [T] = []
        var i = 0
        while groups.contains(where: { i < $0.count }) {
            for g in groups where i < g.count { out.append(g[i]) }
            i += 1
        }
        return out
    }
}

private extension Array where Element == JBIItem {
    /// Stable: items with artwork first.
    func artFirst() -> [JBIItem] { filter(\.hasArt) + filter { !$0.hasArt } }
}
