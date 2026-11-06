import Foundation
import SwiftUI

/// Spotify DTOs → the Swift UI models (Android SpotifyMappers.kt). Pure, so
/// it all unit-tests without a network.
enum SpotifyMapping {
    /// Android MediaId.PROVIDER_SPOTIFY.
    static let provider = "spotify"

    // MARK: Scalars

    /// Spotify lists the largest image first (Android bestImageUrl).
    static func imageURL(_ images: [SpotifyDTO.Image]?) -> URL? {
        guard let s = images?.first?.url, let url = URL(string: s), let scheme = url.scheme?.lowercased(),
              scheme == "https" || scheme == "http" else { return nil }
        return url
    }

    static func palette(_ images: [SpotifyDTO.Image]?, key: String) -> CoverPalette {
        let ref = imageURL(images).map(CoverRef.init(url:))
        let (a, b, c) = SubsonicMapping.placeholder(ref?.key ?? key)
        return CoverPalette(a: a, b: b, c: c, cover: ref)
    }

    /// "2019-05-24" / "2019-05" / "2019" → 2019; 0 when unknown.
    static func year(_ releaseDate: String?) -> Int {
        guard let releaseDate, releaseDate.count >= 4 else { return 0 }
        return Int(releaseDate.prefix(4)) ?? 0
    }

    /// Spotify `album_type` → kind. It files EPs under "single"; its own clients
    /// show a single with 4+ tracks as an EP, so this does too (Android spotifyReleaseType).
    static func kind(albumType: String?, totalTracks: Int?) -> ReleaseKind {
        switch albumType?.lowercased() {
        case "compilation": .compilation
        case "single": (totalTracks ?? 0) >= 4 ? .ep : .single
        default: .album
        }
    }

    /// A track the Web API can name and play: a real id, not a local file or an episode.
    static func isPlayable(_ t: SpotifyDTO.Track) -> Bool {
        guard t.isLocal != true, t.type != "episode", let id = t.id, !id.isEmpty else { return false }
        return true
    }

    /// Editorial playlist descriptions are an HTML fragment (entities, `<a>` links).
    /// Plain text, whitespace collapsed; nil when nothing is left (Spotify sends "" for none).
    static func plainText(_ html: String?) -> String? {
        guard var s = html, !s.isEmpty else { return nil }
        // Break-like tags separate words; inline anchors must not leave a gap.
        s = s.replacing(try! Regex("</?(?:br|p|div|li)\\b[^>]*>").ignoresCase(), with: " ")
        s = s.replacing(try! Regex("<[^>]*>"), with: "")
        let named = ["amp": "&", "lt": "<", "gt": ">", "quot": "\"", "apos": "'", "nbsp": " "]
        s = s.replacing(try! Regex("&(#[xX]?[0-9a-fA-F]+|[a-zA-Z]+);")) { m in
            let body = String(m.output[1].substring ?? "")
            let scalar: UInt32?
            if body.lowercased().hasPrefix("#x") { scalar = UInt32(body.dropFirst(2), radix: 16) }
            else if body.hasPrefix("#") { scalar = UInt32(body.dropFirst(1)) }
            else { return named[body] ?? String(m.output[0].substring ?? "") }
            return scalar.flatMap(Unicode.Scalar.init).map { String(Character($0)) } ?? String(m.output[0].substring ?? "")
        }
        s = s.replacing(try! Regex("\\s+"), with: " ").trimmingCharacters(in: .whitespaces)
        return s.isEmpty ? nil : s
    }

    // MARK: Tracks

    /// The track at [index] of its list. [albumArtist]: a track by the album's own artist carries none (Apple parity).
    static func track(_ t: SpotifyDTO.Track, index: Int = 0, albumArtist: String? = nil, saved: Set<String> = []) -> Track? {
        guard isPlayable(t), let id = t.id else { return nil }
        let artist = t.artists?.first?.name
        return Track(id: MediaId(provider: provider, rawId: id),
                     number: t.trackNumber ?? index + 1,
                     title: t.name ?? "",
                     durationSec: (t.durationMs ?? 0) / 1000,
                     rating: nil,
                     artist: artist == albumArtist ? nil : artist,
                     starred: saved.contains(id),
                     disc: t.discNumber)
    }

    /// A loose track's album as far as the track knows it: no tracks, its own cover.
    static func albumOf(_ t: SpotifyDTO.Track) -> Album {
        let a = t.album
        let id = a?.id.flatMap { $0.isEmpty ? nil : $0 }
        let artist = a?.artists?.first?.name ?? t.artists?.first?.name ?? ""
        return Album(id: MediaId(provider: provider, rawId: id ?? "song:\(t.id ?? "")"),
                     title: a?.name ?? "",
                     artist: artist,
                     year: year(a?.releaseDate),
                     palette: palette(a?.images, key: id ?? t.id ?? ""),
                     tracks: [],
                     kind: kind(albumType: a?.albumType, totalTracks: a?.totalTracks),
                     artistId: a?.artists?.first?.id.flatMap { $0.isEmpty ? nil : $0 } ?? t.artists?.first?.id)
    }

    static func entry(_ t: SpotifyDTO.Track, index: Int = 0, saved: Set<String> = []) -> PlaylistEntry? {
        guard let tr = track(t, index: index, saved: saved) else { return nil }
        return PlaylistEntry(album: albumOf(t), track: tr)
    }

    /// One row per track id, so list keys never collide (as Apple's mapping does).
    static func entries(_ tracks: [SpotifyDTO.Track], saved: Set<String> = []) -> [PlaylistEntry] {
        var seen = Set<MediaId>()
        return tracks.enumerated().compactMap { i, t in entry(t, index: i, saved: saved) }
            .filter { seen.insert($0.track.id).inserted }
    }

    // MARK: Albums

    /// An album with whatever tracks [tracks] hands over (the full album's pages), else none (a list row).
    static func album(_ a: SpotifyDTO.Album, tracks: [SpotifyDTO.Track] = [], addedAt: String? = nil,
                      saved: Set<String> = []) -> Album? {
        guard let id = a.id, !id.isEmpty else { return nil }
        let artist = a.artists?.first?.name ?? ""
        var seen = Set<String>()
        let mapped = tracks.enumerated().compactMap { i, t in track(t, index: i, albumArtist: artist, saved: saved) }
            .filter { seen.insert($0.id.rawId).inserted }
        return Album(id: MediaId(provider: provider, rawId: id),
                     title: a.name ?? "",
                     artist: artist,
                     year: year(a.releaseDate),
                     palette: palette(a.images, key: id),
                     tracks: mapped,
                     rating: nil,
                     kind: kind(albumType: a.albumType, totalTracks: a.totalTracks),
                     artistId: a.artists?.first?.id.flatMap { $0.isEmpty ? nil : $0 },
                     created: addedAt,
                     songCount: a.totalTracks,
                     durationSec: mapped.isEmpty ? nil : mapped.map(\.durationSec).reduce(0, +))
    }

    static func albumItem(_ a: Album) -> LibraryItem {
        LibraryItem(id: a.id, kind: .album, title: a.title, subtitle: String(localized: "Album · \(a.artist)"), palette: a.palette, album: a)
    }

    /// Unique albums, first seen first.
    static func uniqueById(_ albums: [Album]) -> [Album] {
        var seen = Set<MediaId>()
        return albums.filter { seen.insert($0.id).inserted }
    }

    // MARK: Artists

    static func artistItem(_ a: SpotifyDTO.Artist) -> LibraryItem? {
        guard let id = a.id, !id.isEmpty else { return nil }
        return LibraryItem(id: MediaId(provider: provider, rawId: id), kind: .artist, title: a.name ?? "",
                           subtitle: String(localized: "Artist"), palette: palette(a.images, key: id))
    }

    /// Releases newest first. Most Played is empty: `top-tracks` is gone for
    /// Development Mode apps (2026-02) and nothing replaces it.
    static func artistPage(_ a: SpotifyDTO.Artist, releases: [Album], starred: Bool) -> ArtistPage? {
        guard let id = a.id, !id.isEmpty else { return nil }
        let sorted = uniqueById(releases).sorted { ($0.year, $0.title.lowercased()) > ($1.year, $1.title.lowercased()) }
        return ArtistPage(id: MediaId(provider: provider, rawId: id), name: a.name ?? "", starred: starred,
                          palette: imageURL(a.images) != nil ? palette(a.images, key: id)
                              : sorted.first?.palette ?? palette(nil, key: id),
                          releases: sorted, mostPlayed: [])
    }

    // MARK: Playlists

    static func owner(_ p: SpotifyDTO.Playlist) -> String { p.owner?.displayName ?? p.owner?.id ?? "" }

    /// [me]: the signed-in user's id, for By You (nil: ownership unknown).
    static func playlistItem(_ p: SpotifyDTO.Playlist, me: String?) -> LibraryItem {
        let count = p.entries?.total
        return LibraryItem(id: MediaId(provider: provider, rawId: p.id), kind: .playlist, title: p.name ?? "",
                           subtitle: count.map { "Playlist · \($0) songs" } ?? "Playlist",
                           palette: palette(p.images, key: p.id), count: count,
                           owner: owner(p).isEmpty ? nil : owner(p), ownedByMe: SpotifySource.owned(p, by: me))
    }

    static func playlistItem(_ p: SpotifyDTO.Playlist) -> LibraryItem { playlistItem(p, me: nil) }

    /// [tracks] are the playlist's playable entries, already in order. A
    /// playlist the app may not read the items of (someone else's, 2026-02) opens without any.
    static func playlist(_ p: SpotifyDTO.Playlist, tracks: [SpotifyDTO.Track], saved: Set<String> = []) -> PlaylistInfo {
        // Every occurrence (Codex N12): a playlist may hold a song twice; its rows key by position.
        var info = PlaylistInfo(id: MediaId(provider: provider, rawId: p.id), title: p.name ?? "", owner: owner(p),
                                isPublic: p.public, description: plainText(p.description),
                                palette: palette(p.images, key: p.id),
                                entries: tracks.enumerated().compactMap { i, t in entry(t, index: i, saved: saved) })
        info.songCount = p.entries?.total
        return info
    }

    /// The playlist's playable tracks in order, and each one's raw offset in the
    /// playlist (local files, episodes and removed entries are skipped; the
    /// offsets are what `offset.position` of a play request needs).
    static func playableTracks(_ items: [SpotifyDTO.PlaylistItem]) -> (tracks: [SpotifyDTO.Track], offsets: [Int]) {
        var tracks: [SpotifyDTO.Track] = [], offsets: [Int] = []
        for (i, item) in items.enumerated() {
            // Every occurrence, as the page shows them: row i of the page is offsets[i].
            guard let t = item.playable, isPlayable(t), t.id != nil else { continue }
            tracks.append(t)
            offsets.append(i)
        }
        return (tracks, offsets)
    }
}
