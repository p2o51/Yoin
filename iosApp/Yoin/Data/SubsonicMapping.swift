import SwiftUI
import YoinShared

/// Kotlin (YoinShared) Subsonic models → the Swift UI models.
///
/// Scales: a server's 1–5 `userRating` is Yoin's 0–10 × ½, so it comes in
/// × 2 (Android sends `round(local / 2)`). Covers: one URL per cover id at
/// `coverSize`; until the image loads, a quiet placeholder palette hashed
/// from the cover id.
struct SubsonicMapping {
    /// coverArtId → URL (the client's `coverArtUrl`).
    var coverURL: (String) -> URL?

    static let coverSize = 600

    init(coverURL: @escaping (String) -> URL?) {
        self.coverURL = coverURL
    }

    init(client: SubsonicClient) {
        self.init { id in URL(string: client.coverArtUrl(id: id, size: KotlinInt(int: Int32(Self.coverSize)))) }
    }

    // MARK: Scalars

    static func rating(_ userRating: KotlinInt?) -> Double? {
        guard let r = userRating?.intValue, r > 0 else { return nil }
        return Double(min(r, 5) * 2)
    }

    /// Yoin's 0–10 → the server's 0–5 (Android `(local / 2).roundToInt()`).
    static func serverRating(_ rating: Double?) -> Int {
        guard let rating else { return 0 }
        return min(5, max(0, Int((rating / 2).rounded(.toNearestOrAwayFromZero))))
    }

    static func kind(_ releaseType: String?) -> ReleaseKind {
        switch releaseType?.lowercased() {
        case "ep": .ep
        case "single": .single
        case "compilation": .compilation
        default: .album
        }
    }

    static func int(_ v: KotlinInt?) -> Int? { v.map { Int($0.int32Value) } }

    /// A quiet placeholder for a cover still loading: low-saturation tones
    /// with a hue hashed from the key (stable per cover).
    static func placeholder(_ key: String) -> (Color, Color, Color) {
        let h = Double(key.unicodeScalars.reduce(UInt32(7)) { ($0 &* 31) &+ $1.value } % 360) / 360
        return (Color(hue: h, saturation: 0.16, brightness: 0.62),
                Color(hue: h, saturation: 0.08, brightness: 0.9),
                Color(hue: h, saturation: 0.2, brightness: 0.3))
    }

    func palette(coverId: String?) -> CoverPalette {
        let ref = coverId.flatMap(coverURL).map(CoverRef.init(url:))
        let (a, b, c) = Self.placeholder(ref?.key ?? coverId ?? "")
        return CoverPalette(a: a, b: b, c: c, cover: ref)
    }

    // MARK: Entities

    func track(_ s: SongInfo, index: Int = 0, albumArtist: String? = nil) -> Track {
        let artist = s.artist
        return Track(id: MediaId(provider: s.ref.provider, rawId: s.id),
                     number: Self.int(s.track) ?? index + 1,
                     title: s.title ?? "",
                     durationSec: Self.int(s.durationSec) ?? 0,
                     rating: Self.rating(s.userRating),
                     artist: artist == albumArtist ? nil : artist,
                     starred: s.starred,
                     playCount: Self.int(s.playCount),
                     disc: Self.int(s.disc),
                     bitRateKbps: Self.int(s.bitRate),
                     credits: s.artists.map { ArtistCredit(name: $0.name, id: $0.id) })
    }

    func album(_ a: AlbumInfo) -> Album {
        let tracks = a.songs.enumerated().map { i, s in track(s, index: i, albumArtist: a.artist) }
        return Album(id: MediaId(provider: a.ref.provider, rawId: a.id),
                     title: a.name,
                     artist: a.artist ?? "",
                     year: Self.int(a.year) ?? 0,
                     palette: palette(coverId: a.coverArtId),
                     tracks: tracks,
                     rating: Self.rating(a.userRating),
                     kind: Self.kind(a.releaseType),
                     artistId: a.artistId,
                     starred: a.starred,
                     playCount: Self.int(a.playCount),
                     created: a.created,
                     songCount: Self.int(a.songCount),
                     durationSec: Self.int(a.durationSec),
                     genre: a.genre)
    }

    /// The album a loose song (playlist, search, random) belongs to, as far as
    /// the song knows it: no tracks, its own cover.
    func albumOf(_ s: SongInfo) -> Album {
        Album(id: MediaId(provider: s.ref.provider, rawId: s.albumId ?? "song:\(s.id)"),
              title: s.album ?? "",
              artist: s.artist ?? "",
              year: Self.int(s.year) ?? 0,
              palette: palette(coverId: s.coverArtId ?? s.albumId),
              tracks: [],
              artistId: s.artistId,
              genre: s.genre)
    }

    func entry(_ s: SongInfo, index: Int = 0) -> PlaylistEntry {
        PlaylistEntry(album: albumOf(s), track: track(s, index: index))
    }

    func albumItem(_ a: Album) -> LibraryItem {
        LibraryItem(id: a.id, kind: .album, title: a.title, subtitle: String(localized: "Album · \(a.artist)"),
                    palette: a.palette, album: a)
    }

    func artistItem(_ a: ArtistInfo) -> LibraryItem {
        LibraryItem(id: MediaId(provider: a.ref.provider, rawId: a.id), kind: .artist, title: a.name,
                    subtitle: String(localized: "Artist"), palette: palette(coverId: a.coverArtId), count: Self.int(a.albumCount))
    }

    func playlistItem(_ p: PlaylistSummary) -> LibraryItem {
        let n = Self.int(p.songCount) ?? 0
        return LibraryItem(id: MediaId(provider: p.ref.provider, rawId: p.id), kind: .playlist, title: p.name,
                           subtitle: String(localized: "Playlist · \(n) songs"), palette: palette(coverId: p.coverArtId), count: n,
                           owner: p.owner, ownedByMe: p.ownedByMe?.boolValue)
    }

    func playlist(_ d: PlaylistDetail) -> PlaylistInfo {
        let p = d.summary
        let comment = p.comment?.trimmingCharacters(in: .whitespacesAndNewlines)
        return PlaylistInfo(id: MediaId(provider: p.ref.provider, rawId: p.id), title: p.name,
                            owner: p.owner ?? "",
                            isPublic: p.isPublic?.boolValue,
                            description: comment?.isEmpty == false ? comment : nil,
                            palette: palette(coverId: p.coverArtId),
                            entries: d.entries.enumerated().map { i, s in entry(s, index: i) },
                            ownedByMe: p.ownedByMe?.boolValue, canWrite: p.canWrite)
    }

    /// Releases newest first; Most Played = the loaded albums' songs by play count (top 5).
    func artistPage(_ a: ArtistInfo, loadedAlbums: [Album]) -> ArtistPage {
        let byId = Dictionary(loadedAlbums.map { ($0.id.rawId, $0) }, uniquingKeysWith: { x, _ in x })
        let releases = a.albums.map { byId[$0.id] ?? album($0) }
            .sorted { ($0.year, $0.created ?? "") > ($1.year, $1.created ?? "") }
        let played = releases.flatMap { al in al.tracks.map { PlaylistEntry(album: al, track: $0) } }
            .filter { ($0.track.playCount ?? 0) > 0 }
            .sorted { ($0.track.playCount ?? 0) > ($1.track.playCount ?? 0) }
        let hero = releases.first
        return ArtistPage(id: MediaId(provider: a.ref.provider, rawId: a.id), name: a.name, starred: a.starred,
                          palette: a.coverArtId.map { palette(coverId: $0) } ?? hero?.palette ?? palette(coverId: nil),
                          releases: releases, mostPlayed: Array(played.prefix(5)))
    }
}
