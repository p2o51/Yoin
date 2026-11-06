import Foundation
import SwiftUI

/// Apple Music API resources (the JSON `MusicDataRequest` answers) → plain
/// values → the Swift UI models. No MusicKit here, so it all unit-tests.
///
/// Identity follows Android (`AppleMusicSong.toTrack`, `AppleMusicSource`):
/// a song, album, artist or playlist is its CATALOG id when it has one, and
/// `library:<library id>` only when it has none (an import, a user playlist).
/// So a library row and the same song found in search are one track for
/// ratings and notes.
enum AppleMusicJSON {
    typealias Object = [String: Any]

    static func object(_ data: Data) -> Object? {
        (try? JSONSerialization.jsonObject(with: data)) as? Object
    }

    /// A response's top-level `data` array.
    static func resources(_ root: Object?) -> [Object] { root?["data"] as? [Object] ?? [] }

    /// `next` of a paged response (a path such as `/v1/me/library/albums?offset=100`).
    static func next(_ root: Object?) -> String? { root?["next"] as? String }

    static func attributes(_ r: Object) -> Object { r["attributes"] as? Object ?? [:] }

    /// The related resources, when the request included that relationship.
    static func related(_ r: Object, _ name: String) -> [Object]? {
        ((r["relationships"] as? Object)?[name] as? Object)?["data"] as? [Object]
    }

    static func relatedNext(_ r: Object, _ name: String) -> String? {
        ((r["relationships"] as? Object)?[name] as? Object)?["next"] as? String
    }

    /// A `views` entry (`top-songs`) of a catalog artist.
    static func view(_ r: Object, _ name: String) -> [Object]? {
        ((r["views"] as? Object)?[name] as? Object)?["data"] as? [Object]
    }

    static func string(_ o: Object?, _ key: String) -> String? {
        guard let s = o?[key] as? String else { return nil }
        let t = s.trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : t
    }

    static func int(_ o: Object?, _ key: String) -> Int? {
        if let n = o?[key] as? NSNumber { return n.intValue }
        if let s = o?[key] as? String { return Int(s) }
        return nil
    }

    static func bool(_ o: Object?, _ key: String) -> Bool { (o?[key] as? NSNumber)?.boolValue ?? false }
}

// MARK: - Plain values

struct AMSong: Hashable, Sendable {
    var resourceId: String
    var libraryId: String?
    var catalogId: String?
    var title: String
    var artist: String
    var album: String
    /// The album's UI raw id (catalog id, or `library:` id).
    var albumId: String?
    var artistId: String?
    var durationMs: Int?
    var trackNumber: Int?
    var disc: Int?
    var artwork: URL?
    var genre: String?
    var releaseDate: String?

    /// Catalog id, else `library:<id>` (Android `Track.id`).
    var rawId: String { catalogId ?? AppleMusicMapping.libraryPrefix + resourceId }
    /// In the library with no catalog song: an upload or a song the store no longer sells.
    var isImport: Bool { libraryId != nil && catalogId == nil }
}

struct AMAlbum: Hashable, Sendable {
    var resourceId: String
    var isLibrary: Bool
    var catalogId: String?
    var title: String
    var artist: String
    var artistId: String?
    var artwork: URL?
    var releaseDate: String?
    var dateAdded: String?
    var trackCount: Int?
    var isSingle: Bool
    var isCompilation: Bool
    var genre: String?
    var tracks: [AMSong]

    var rawId: String { catalogId ?? AppleMusicMapping.libraryPrefix + resourceId }
}

struct AMArtist: Hashable, Sendable {
    var resourceId: String
    var isLibrary: Bool
    var catalogId: String?
    var name: String
    var artwork: URL?

    var rawId: String { catalogId ?? AppleMusicMapping.libraryPrefix + resourceId }
}

struct AMPlaylist: Hashable, Sendable {
    var resourceId: String
    var isLibrary: Bool
    var name: String
    var curator: String?
    var description: String?
    var artwork: URL?
    var trackCount: Int?
    /// A library playlist the user can edit (Apple's `canEdit`): theirs. Apple names no owner, and its
    /// Purchased and Favorites Mix aren't editable (Android ownedByMe). nil: a catalog playlist.
    var canEdit: Bool? = nil

    /// Playlists keep their own id: a library playlist is the user's (`library:p.…`).
    var rawId: String { isLibrary ? AppleMusicMapping.libraryPrefix + resourceId : resourceId }
}

// MARK: - Mapping

enum AppleMusicMapping {
    static let provider = "applemusic"
    static let libraryPrefix = "library:"
    static let coverSize = 600
    /// Queue entries carry this scheme instead of a stream URL; the Apple Music
    /// output plays them through ApplicationMusicPlayer.
    static let streamScheme = "applemusic"

    // MARK: Scalars

    /// An artwork template (`…/{w}x{h}bb.jpg`) at [size] px; only http(s) URLs
    /// (the cover cache fetches with URLSession).
    static func artworkURL(_ template: String?, size: Int = coverSize) -> URL? {
        guard let template else { return nil }
        let s = template.replacingOccurrences(of: "{w}", with: String(size))
            .replacingOccurrences(of: "{h}", with: String(size))
            .replacingOccurrences(of: "{f}", with: "jpg")
        guard let url = URL(string: s), let scheme = url.scheme?.lowercased(),
              scheme == "https" || scheme == "http" else { return nil }
        return url
    }

    /// "2019-05-24" / "2019" → 2019; 0 when unknown.
    static func year(_ releaseDate: String?) -> Int {
        guard let releaseDate, releaseDate.count >= 4 else { return 0 }
        return Int(releaseDate.prefix(4)) ?? 0
    }

    /// Apple names EPs and singles in the title ("Name - EP"): the kind, and
    /// the title without the suffix.
    static func release(title: String, isSingle: Bool, isCompilation: Bool) -> (ReleaseKind, String) {
        for (suffix, kind) in [(" - EP", ReleaseKind.ep), (" - Single", ReleaseKind.single)] where title.hasSuffix(suffix) {
            return (kind, String(title.dropLast(suffix.count)))
        }
        if isSingle { return (.single, title) }
        if isCompilation { return (.compilation, title) }
        return (.album, title)
    }

    /// The first genre that says something ("Music" is every song's).
    static func genre(_ names: [String]?) -> String? {
        names?.first { $0 != "Music" && !$0.isEmpty }
    }

    /// The album id in a catalog song's web URL (`…/album/name/1440857781?i=1440857786`).
    static func albumId(fromSongURL raw: String?) -> String? {
        guard let raw, let c = URLComponents(string: raw), c.path.contains("/album/") else { return nil }
        let last = c.path.split(separator: "/").last.map(String.init)
        return last.flatMap { $0.allSatisfy(\.isNumber) ? $0 : nil }
    }

    static func isLibraryId(_ rawId: String) -> Bool { rawId.hasPrefix(libraryPrefix) }

    static func resourceId(_ rawId: String) -> String {
        isLibraryId(rawId) ? String(rawId.dropFirst(libraryPrefix.count)) : rawId
    }

    /// A catalog song id is all digits.
    static func isCatalogSongId(_ rawId: String) -> Bool { !rawId.isEmpty && rawId.allSatisfy(\.isNumber) }

    static func streamURL(rawId: String) -> URL {
        var c = URLComponents()
        c.scheme = streamScheme
        c.host = "song"
        c.path = "/" + rawId
        return c.url ?? URL(string: "\(streamScheme)://song/unknown")!
    }

    static func rawId(fromStreamURL url: URL) -> String? {
        guard url.scheme == streamScheme else { return nil }
        let p = url.path
        return p.count > 1 ? String(p.dropFirst()) : nil
    }

    static func palette(_ artwork: URL?, key: String) -> CoverPalette {
        let ref = artwork.map(CoverRef.init(url:))
        let (a, b, c) = SubsonicMapping.placeholder(ref?.key ?? key)
        return CoverPalette(a: a, b: b, c: c, cover: ref)
    }

    // MARK: JSON → plain

    static func song(_ r: AppleMusicJSON.Object) -> AMSong? {
        guard let id = r["id"] as? String, let type = r["type"] as? String,
              type == "songs" || type == "library-songs" else { return nil }
        let a = AppleMusicJSON.attributes(r)
        let isLibrary = type == "library-songs"
        let play = a["playParams"] as? AppleMusicJSON.Object
        let catalog = AppleMusicJSON.related(r, "catalog")?.first
        let catalogId: String? = isLibrary
            ? (catalog?["id"] as? String ?? AppleMusicJSON.string(play, "catalogId"))
            : id
        let album = AppleMusicJSON.related(r, "albums")?.first
            ?? catalog.flatMap { AppleMusicJSON.related($0, "albums")?.first }
        let artist = AppleMusicJSON.related(r, "artists")?.first
            ?? catalog.flatMap { AppleMusicJSON.related($0, "artists")?.first }
        let catalogAttributes = catalog.map(AppleMusicJSON.attributes)
        return AMSong(
            resourceId: id,
            libraryId: isLibrary ? id : nil,
            catalogId: catalogId.flatMap { isCatalogSongId($0) ? $0 : nil },
            title: AppleMusicJSON.string(a, "name") ?? "",
            artist: AppleMusicJSON.string(a, "artistName") ?? "",
            album: AppleMusicJSON.string(a, "albumName") ?? "",
            albumId: album.flatMap(relatedRawId)
                ?? albumId(fromSongURL: AppleMusicJSON.string(a, "url") ?? AppleMusicJSON.string(catalogAttributes, "url")),
            artistId: artist.flatMap(relatedRawId),
            durationMs: AppleMusicJSON.int(a, "durationInMillis"),
            trackNumber: AppleMusicJSON.int(a, "trackNumber"),
            disc: AppleMusicJSON.int(a, "discNumber"),
            artwork: artworkURL(AppleMusicJSON.string(a["artwork"] as? AppleMusicJSON.Object, "url")
                ?? AppleMusicJSON.string(catalogAttributes?["artwork"] as? AppleMusicJSON.Object, "url")),
            genre: genre(a["genreNames"] as? [String]),
            releaseDate: AppleMusicJSON.string(a, "releaseDate"))
    }

    /// A related resource's UI raw id (`library:` for library types).
    static func relatedRawId(_ r: AppleMusicJSON.Object) -> String? {
        guard let id = r["id"] as? String else { return nil }
        return (r["type"] as? String)?.hasPrefix("library-") == true ? libraryPrefix + id : id
    }

    static func album(_ r: AppleMusicJSON.Object) -> AMAlbum? {
        guard let id = r["id"] as? String, let type = r["type"] as? String,
              type == "albums" || type == "library-albums" else { return nil }
        let a = AppleMusicJSON.attributes(r)
        let isLibrary = type == "library-albums"
        let catalog = AppleMusicJSON.related(r, "catalog")?.first
        let catalogAttributes = catalog.map(AppleMusicJSON.attributes)
        let play = a["playParams"] as? AppleMusicJSON.Object
        let catalogId = isLibrary ? (catalog?["id"] as? String ?? AppleMusicJSON.string(play, "catalogId")) : id
        let tracks = (AppleMusicJSON.related(r, "tracks") ?? []).compactMap(song)
        return AMAlbum(
            resourceId: id,
            isLibrary: isLibrary,
            catalogId: catalogId.flatMap { isCatalogSongId($0) ? $0 : nil },
            title: AppleMusicJSON.string(a, "name") ?? "",
            artist: AppleMusicJSON.string(a, "artistName") ?? "",
            artistId: AppleMusicJSON.related(r, "artists")?.first.flatMap(relatedRawId),
            artwork: artworkURL(AppleMusicJSON.string(a["artwork"] as? AppleMusicJSON.Object, "url")
                ?? AppleMusicJSON.string(catalogAttributes?["artwork"] as? AppleMusicJSON.Object, "url")),
            releaseDate: AppleMusicJSON.string(a, "releaseDate") ?? AppleMusicJSON.string(catalogAttributes, "releaseDate"),
            dateAdded: AppleMusicJSON.string(a, "dateAdded"),
            trackCount: AppleMusicJSON.int(a, "trackCount"),
            isSingle: AppleMusicJSON.bool(a, "isSingle") || AppleMusicJSON.bool(catalogAttributes, "isSingle"),
            isCompilation: AppleMusicJSON.bool(a, "isCompilation") || AppleMusicJSON.bool(catalogAttributes, "isCompilation"),
            genre: genre((a["genreNames"] ?? catalogAttributes?["genreNames"]) as? [String]),
            tracks: tracks)
    }

    static func artist(_ r: AppleMusicJSON.Object) -> AMArtist? {
        guard let id = r["id"] as? String, let type = r["type"] as? String,
              type == "artists" || type == "library-artists" else { return nil }
        let a = AppleMusicJSON.attributes(r)
        let isLibrary = type == "library-artists"
        let catalog = AppleMusicJSON.related(r, "catalog")?.first
        let catalogAttributes = catalog.map(AppleMusicJSON.attributes)
        return AMArtist(
            resourceId: id, isLibrary: isLibrary,
            catalogId: isLibrary ? catalog?["id"] as? String : id,
            name: AppleMusicJSON.string(a, "name") ?? "",
            artwork: artworkURL(AppleMusicJSON.string(a["artwork"] as? AppleMusicJSON.Object, "url")
                ?? AppleMusicJSON.string(catalogAttributes?["artwork"] as? AppleMusicJSON.Object, "url")))
    }

    static func playlist(_ r: AppleMusicJSON.Object) -> AMPlaylist? {
        guard let id = r["id"] as? String, let type = r["type"] as? String,
              type == "playlists" || type == "library-playlists" else { return nil }
        let a = AppleMusicJSON.attributes(r)
        let description = a["description"] as? AppleMusicJSON.Object
        return AMPlaylist(
            resourceId: id, isLibrary: type == "library-playlists",
            name: AppleMusicJSON.string(a, "name") ?? "",
            curator: AppleMusicJSON.string(a, "curatorName"),
            description: AppleMusicJSON.string(description, "standard") ?? AppleMusicJSON.string(description, "short"),
            artwork: artworkURL(AppleMusicJSON.string(a["artwork"] as? AppleMusicJSON.Object, "url")),
            trackCount: AppleMusicJSON.int(a, "trackCount"),
            canEdit: type == "library-playlists" ? a["canEdit"] as? Bool : nil)
    }

    // MARK: Plain → UI

    static func track(_ s: AMSong, index: Int = 0, albumArtist: String? = nil) -> Track {
        Track(id: MediaId(provider: provider, rawId: s.rawId),
              number: s.trackNumber ?? index + 1,
              title: s.title,
              durationSec: (s.durationMs ?? 0) / 1000,
              rating: nil,
              artist: s.artist == albumArtist ? nil : s.artist,
              disc: s.disc)
    }

    static func album(_ a: AMAlbum) -> Album {
        let (kind, title) = release(title: a.title, isSingle: a.isSingle, isCompilation: a.isCompilation)
        let tracks = uniqueById(a.tracks).enumerated().map { i, s in track(s, index: i, albumArtist: a.artist) }
        return Album(id: MediaId(provider: provider, rawId: a.rawId),
                     title: title,
                     artist: a.artist,
                     year: year(a.releaseDate),
                     palette: palette(a.artwork, key: a.rawId),
                     tracks: tracks,
                     rating: nil,
                     kind: kind,
                     artistId: a.artistId,
                     created: a.dateAdded,
                     songCount: a.trackCount,
                     durationSec: tracks.isEmpty ? nil : tracks.map(\.durationSec).reduce(0, +),
                     genre: a.genre)
    }

    /// A loose song's album as far as the song knows it: no tracks, its own cover.
    static func albumOf(_ s: AMSong) -> Album {
        Album(id: MediaId(provider: provider, rawId: s.albumId ?? "song:\(s.rawId)"),
              title: s.album,
              artist: s.artist,
              year: year(s.releaseDate),
              palette: palette(s.artwork, key: s.albumId ?? s.rawId),
              tracks: [],
              artistId: s.artistId,
              genre: s.genre)
    }

    static func entry(_ s: AMSong, index: Int = 0) -> PlaylistEntry {
        PlaylistEntry(album: albumOf(s), track: track(s, index: index))
    }

    static func entries(_ songs: [AMSong]) -> [PlaylistEntry] {
        uniqueById(songs).enumerated().map { entry($1, index: $0) }
    }

    static func albumItem(_ a: Album) -> LibraryItem {
        LibraryItem(id: a.id, kind: .album, title: a.title, subtitle: "Album · \(a.artist)", palette: a.palette, album: a)
    }

    static func artistItem(_ a: AMArtist) -> LibraryItem {
        LibraryItem(id: MediaId(provider: provider, rawId: a.rawId), kind: .artist, title: a.name,
                    subtitle: "Artist", palette: palette(a.artwork, key: a.rawId))
    }

    static func playlistItem(_ p: AMPlaylist) -> LibraryItem {
        LibraryItem(id: MediaId(provider: provider, rawId: p.rawId), kind: .playlist, title: p.name,
                    subtitle: p.trackCount.map { "Playlist · \($0) songs" } ?? "Playlist",
                    palette: palette(p.artwork, key: p.rawId), count: p.trackCount, ownedByMe: p.canEdit)
    }

    static func playlist(_ p: AMPlaylist, songs: [AMSong]) -> PlaylistInfo {
        PlaylistInfo(id: MediaId(provider: provider, rawId: p.rawId), title: p.name,
                     owner: p.curator ?? "", isPublic: nil, description: p.description,
                     palette: palette(p.artwork, key: p.rawId), entries: entries(songs))
    }

    /// Releases newest first; Most Played = Apple's top songs (top 5).
    static func artistPage(_ a: AMArtist, albums: [Album], topSongs: [AMSong]) -> ArtistPage {
        let releases = uniqueById(albums).sorted { ($0.year, $0.title) > ($1.year, $1.title) }
        return ArtistPage(id: MediaId(provider: provider, rawId: a.rawId), name: a.name, starred: false,
                          palette: a.artwork != nil ? palette(a.artwork, key: a.rawId)
                              : releases.first?.palette ?? palette(nil, key: a.rawId),
                          releases: releases, mostPlayed: Array(entries(topSongs).prefix(5)))
    }

    /// Two library songs can stand for one catalog song: one row per id, so
    /// list keys never collide (Android dedupes the same way).
    static func uniqueById(_ songs: [AMSong]) -> [AMSong] {
        var seen = Set<String>()
        return songs.filter { seen.insert($0.rawId).inserted }
    }

    static func uniqueById(_ albums: [Album]) -> [Album] {
        var seen = Set<MediaId>()
        return albums.filter { seen.insert($0.id).inserted }
    }

    // MARK: Lists

    /// The albums in a mixed list (recently played, heavy rotation, recently added).
    static func albums(in resources: [AppleMusicJSON.Object]) -> [Album] {
        uniqueById(resources.compactMap(album).map(album))
    }
}
