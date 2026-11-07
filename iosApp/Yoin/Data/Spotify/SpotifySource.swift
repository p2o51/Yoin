import Foundation
import os

/// Spotify through its Web API (Android SpotifyMusicSource), shaped like
/// `AppleMusicSource` so `LibraryStore` can branch on it the same way: every
/// read the screens need, parsed by `SpotifyMapping`. No playback and no OAuth
/// here: the caller builds the `SpotifyWebAPI` with its token provider.
///
/// 2026 Development Mode limits, as Android handles them: `top-tracks` is gone
/// (an artist's Most Played is empty), `/playlists/{id}/items` answers 403 for
/// playlists the user neither owns nor collaborates on (the playlist opens
/// without tracks), a missing id reads as nil, and each Home list fails on its own.
@MainActor
final class SpotifySource {
    let api: SpotifyWebAPI
    let log = Logger(subsystem: "com.gpo.yoin", category: "spotify")

    /// Track ids known to be in Liked Songs (library reads, saves, contains answers).
    private(set) var liked: Set<String> = []
    /// Playlist raw id → the raw offset of each row the page shows, for `resolvePlaylistContextOffset`.
    private var playlistOffsets: [String: [Int]] = [:]
    /// The signed-in user's id, for playlist ownership (SpotifySource+Playlists); read from `me` when unset.
    var userId: String?

    init(api: SpotifyWebAPI) {
        self.api = api
    }

    nonisolated static func message(for error: Error) -> String {
        if let e = error as? SpotifyAPIError {
            switch e {
            case .unauthorized:
                return "Reconnect Spotify in Settings"
            case .http(403, let m) where (m ?? "").localizedCaseInsensitiveContains("scope"):
                return "Reconnect Spotify in Settings to let Yoin control playback"
            default:
                return e.errorDescription ?? error.localizedDescription
            }
        }
        return error.localizedDescription
    }

    // MARK: Home

    struct HomeLists: Sendable {
        var recent: [Album]
        var frequent: [Album]
        var newest: [Album]
        var older: [Album]
        var recentTracks: [PlaylistEntry]
    }

    /// Activities = recently played (one request, 50 plays: its albums newest
    /// first, once each, and its last songs); Recently Added = the newest saved
    /// albums, the saved albums before them the older shelf. There is no heavy
    /// rotation on Spotify. Each list fails on its own: an account with no
    /// history still gets a page.
    func homeLists() async throws -> HomeLists {
        var firstError: Error?
        func attempt<T>(_ name: String, _ body: () async throws -> [T]) async -> [T] {
            do { return try await body() } catch {
                firstError = firstError ?? error
                log.info("home \(name, privacy: .public) failed: \(Self.message(for: error), privacy: .public)")
                return []
            }
        }
        let plays = await attempt("recent") {
            try await api.get("me/player/recently-played", ["limit": "50"], as: SpotifyDTO.RecentlyPlayed.self).items
        }
        let saved = await attempt("saved albums") {
            try await api.all("me/albums", ["limit": "50"], of: SpotifyDTO.SavedAlbum.self, cap: 200)
        }
        if plays.isEmpty && saved.isEmpty, let firstError { throw firstError }
        let played = plays.compactMap(\.track)
        let recent = SpotifyMapping.uniqueById(played.compactMap { $0.album.flatMap { SpotifyMapping.album($0) } })
        let added = saved.sorted { ($0.addedAt ?? "") > ($1.addedAt ?? "") }
            .compactMap { s in s.album.flatMap { SpotifyMapping.album($0, addedAt: s.addedAt) } }
        return HomeLists(recent: Array(recent.prefix(20)), frequent: [],
                         newest: Array(added.prefix(10)), older: Array(added.dropFirst(10)).shuffled(),
                         recentTracks: Array(SpotifyMapping.entries(played, saved: liked).prefix(4)))
    }

    // MARK: Library

    func libraryAlbums(cap: Int = 2000, onPage: (([Album]) -> Void)? = nil) async throws -> [Album] {
        func albums(_ r: [SpotifyDTO.SavedAlbum]) -> [Album] {
            SpotifyMapping.uniqueById(r.compactMap { s in s.album.flatMap { SpotifyMapping.album($0, addedAt: s.addedAt) } })
        }
        let r = try await api.all("me/albums", ["limit": "50"], of: SpotifyDTO.SavedAlbum.self, cap: cap,
                                  onPage: onPage.map { f in { f(albums($0)) } })
        return albums(r)
    }

    /// The followed artists (a cursor list: `artists.next` is a full link).
    func libraryArtists(cap: Int = 2000, onPage: (([LibraryItem]) -> Void)? = nil) async throws -> [LibraryItem] {
        func items(_ r: [SpotifyDTO.Artist]) -> [LibraryItem] {
            var seen = Set<MediaId>()
            return r.compactMap(SpotifyMapping.artistItem).filter { seen.insert($0.id).inserted }
        }
        var out: [SpotifyDTO.Artist] = []
        var visited = Set<String>()
        var page = try await api.get("me/following", ["type": "artist", "limit": "50"], as: SpotifyDTO.FollowedArtists.self).artists
        out += page.items
        while out.count < cap, let next = page.next {
            guard visited.insert(next).inserted else { throw SpotifyAPIError.decoding("repeated page") }
            onPage?(items(out))
            page = try await api.get(next, ["type": "artist", "limit": "50"], as: SpotifyDTO.FollowedArtists.self).artists
            out += page.items
        }
        return items(Array(out.prefix(cap)))
    }

    /// Every playlist of the account, all pages (a backup tells a playlist gone by its absence).
    /// Spotify can list a null in place of a playlist it won't show.
    func libraryPlaylists() async throws -> [LibraryItem] {
        let r = try await api.all("me/playlists", ["limit": "50"], of: SpotifyDTO.Playlist?.self, cap: .max)
        let me = await meId()
        return r.compactMap { $0 }.map { SpotifyMapping.playlistItem($0, me: me) }
    }

    func noteLiked(_ ids: [String]) { liked.formUnion(ids) }

    /// Liked Songs, newest like first (the API's order), the first few hundred.
    func librarySongs(cap: Int = 300) async throws -> [PlaylistEntry] {
        let r = try await api.all("me/tracks", ["limit": "50"], of: SpotifyDTO.SavedTrack.self, cap: cap)
        let tracks = r.compactMap(\.track)
        liked.formUnion(tracks.compactMap(\.id))
        return SpotifyMapping.entries(tracks, saved: liked)
    }

    // MARK: Details

    /// The album and all its tracks (the album object embeds the first page).
    /// An id Spotify doesn't have reads as nil.
    func album(_ rawId: String) async throws -> Album? {
        do {
            let a = try await api.get("albums/\(rawId)", as: SpotifyDTO.Album.self)
            var tracks = a.tracks?.items ?? []
            var next = a.tracks?.next
            var visited = Set<String>()
            while let page = next, tracks.count < 300, visited.insert(page).inserted {
                let more = try await api.get(page, as: SpotifyDTO.Paging<SpotifyDTO.Track>.self)
                tracks += more.items
                next = more.next
            }
            // Each track's heart, asked (Codex N11): the Liked Songs read so far covers only some.
            _ = await savedStates(tracks.compactMap(\.id))
            return SpotifyMapping.album(a, tracks: tracks, saved: liked)
        } catch let e as SpotifyAPIError where e.isNotFound {
            return nil
        }
    }

    /// The artist and its own releases (albums, singles, compilations; at most
    /// 60, 10 a request since the 2026-02 migration; `appears_on` left out).
    func artist(_ rawId: String) async throws -> ArtistPage? {
        let artist: SpotifyDTO.Artist
        do {
            artist = try await api.get("artists/\(rawId)", as: SpotifyDTO.Artist.self)
        } catch let e as SpotifyAPIError where e.isNotFound {
            return nil
        }
        let releases = try await api.all("artists/\(rawId)/albums",
                                         ["limit": "10", "include_groups": "album,single,compilation"],
                                         of: SpotifyDTO.Album.self, cap: 60)
        // The follow state is a nicety: a failed read shows the star off.
        let followed = (try? await contains(["spotify:artist:\(rawId)"]).first) ?? false
        return SpotifyMapping.artistPage(artist, releases: releases.compactMap { SpotifyMapping.album($0) },
                                         starred: followed)
    }

    /// The playlist and its playable tracks, every occurrence (up to [cap], Codex N12). Someone else's
    /// playlist answers 403 for its items: it opens marked unreadable (N18). Local files, episodes and
    /// removed entries are left out.
    func playlist(_ rawId: String, cap: Int = 10_000) async throws -> PlaylistInfo? {
        let p: SpotifyDTO.Playlist
        do {
            p = try await api.get("playlists/\(rawId)", ["additional_types": "track"], as: SpotifyDTO.Playlist.self)
        } catch let e as SpotifyAPIError where e.isNotFound {
            playlistOffsets[rawId] = nil
            return nil
        }
        var items: [SpotifyDTO.PlaylistItem] = []
        do {
            if let first = p.entries, !first.items.isEmpty || first.next != nil {
                items = first.items
                var next = first.next
                var visited = Set<String>()
                while let page = next, items.count < cap, visited.insert(page).inserted {
                    let more = try await api.get(page, ["additional_types": "track"],
                                                 as: SpotifyDTO.Paging<SpotifyDTO.PlaylistItem>.self)
                    items += more.items
                    next = more.next
                }
            } else {
                items = try await api.all("playlists/\(rawId)/items", ["limit": "50", "additional_types": "track"],
                                          of: SpotifyDTO.PlaylistItem.self, cap: cap)
            }
        } catch let e as SpotifyAPIError where e.isForbidden {
            playlistOffsets[rawId] = []
            var info = SpotifyMapping.playlist(p, tracks: [], saved: liked)
            info.tracksUnavailable = true
            return await withWriteMetadata(info, p, positions: nil)
        }
        let (tracks, offsets) = SpotifyMapping.playableTracks(Array(items.prefix(cap)))
        playlistOffsets[rawId] = offsets
        _ = await savedStates(Array(Set(tracks.compactMap(\.id))))
        return await withWriteMetadata(SpotifyMapping.playlist(p, tracks: tracks, saved: liked), p, positions: offsets)
    }

    /// The raw playlist offset of the [visibleIndex]th row of its page, for a
    /// `play` request with a context; nil when unknown (the caller plays the bare track).
    func resolvePlaylistContextOffset(_ rawId: String, visibleIndex: Int) -> Int? {
        playlistOffsets[rawId]?[safe: visibleIndex]
    }

    // MARK: Search

    struct SearchResults: Sendable {
        var artists: [LibraryItem]
        var albums: [Album]
        var songs: [PlaylistEntry]
        var playlists: [LibraryItem]
        /// Catalog search (SpotifySource+Search): the kinds with a next page, and the items each page held.
        var more: Set<SearchKind> = []
        var counts: [SearchKind: Int] = [:]
    }

    // MARK: Playback (Android SpotifyStartPlan: Web API play with a context and offset.uri)

    /// Starts [uris] (the current song and the engine's next) in the Spotify app. Yoin's queue stays
    /// the truth: an album context let Spotify carry on through the album while Yoin played a playlist
    /// (Codex N02). No [deviceId]: Spotify's active device; with none active the call fails with
    /// `isNoActiveDevice` and the output picks this iPhone itself.
    /// [still]: asked before the start goes out and before each retry (a newer start drops this one).
    func startPlayback(uris: [String], positionMs: Int = 0, deviceId: String? = nil,
                       while still: SpotifyWebAPI.StillWanted? = nil) async throws {
        var body: [String: Any] = ["uris": uris]
        if positionMs > 0 { body["position_ms"] = positionMs }
        let json = try JSONSerialization.data(withJSONObject: body)
        PlayTrace.log("API PUT play body \(String(data: json, encoding: .utf8) ?? "-")")
        try await api.send("PUT", "me/player/play", deviceId.map { ["device_id": $0] } ?? [:], json: json, while: still)
    }

    /// Where Spotify plays now (Android getPlaybackState, a read only): nil when nothing is active.
    struct PlayerNow: Equatable, Sendable {
        var deviceId: String?
        var deviceName: String
        var deviceType: String = ""
        var isPlaying: Bool
        var trackURI: String? = nil
        var progressMs: Int = 0
        var durationMs: Int = 0
        var shuffle: Bool = false
        /// "off", "track" or "context".
        var repeatState: String = "off"
        var trackName: String? = nil
        var artistName: String? = nil
        var albumName: String? = nil
        var coverURL: URL? = nil
    }

    private struct PlayerDTO: Decodable {
        struct D: Decodable { let id: String?; let name: String?; let type: String? }
        struct Named: Decodable { let name: String? }
        struct Image: Decodable { let url: String?; let width: Int? }
        struct Album: Decodable { let name: String?; let images: [Image]? }
        struct I: Decodable {
            let uri: String?
            let durationMs: Int?
            let name: String?
            let artists: [Named]?
            let album: Album?
        }
        let device: D?
        let isPlaying: Bool?
        let item: I?
        let progressMs: Int?
        let shuffleState: Bool?
        let repeatState: String?
    }

    func playerNow() async throws -> PlayerNow? {
        let data = try await api.send("GET", "me/player")
        guard !data.isEmpty else { return nil }  // 204: no active device
        let r = try SpotifyWebAPI.decoder.decode(PlayerDTO.self, from: data)
        guard let d = r.device else { return nil }
        return PlayerNow(deviceId: d.id, deviceName: d.name ?? "another device", deviceType: d.type ?? "",
                         isPlaying: r.isPlaying ?? false, trackURI: r.item?.uri, progressMs: r.progressMs ?? 0,
                         durationMs: r.item?.durationMs ?? 0, shuffle: r.shuffleState ?? false,
                         repeatState: r.repeatState ?? "off", trackName: r.item?.name,
                         artistName: r.item?.artists?.compactMap(\.name).joined(separator: ", "),
                         albumName: r.item?.album?.name,
                         // The largest picture first (Spotify lists them biggest first; not relied on).
                         coverURL: r.item?.album?.images?.max { ($0.width ?? 0) < ($1.width ?? 0) }?.url.flatMap(URL.init(string:)))
    }

    // Transport on a Connect device (Android SpotifyApiClient pause / resume / seek).

    func pausePlayback(deviceId: String?, while still: SpotifyWebAPI.StillWanted? = nil) async throws {
        try await api.send("PUT", "me/player/pause", deviceId.map { ["device_id": $0] } ?? [:], while: still)
    }

    func resumePlayback(deviceId: String?, while still: SpotifyWebAPI.StillWanted? = nil) async throws {
        try await api.send("PUT", "me/player/play", deviceId.map { ["device_id": $0] } ?? [:], while: still)
    }

    func seekPlayback(toMs ms: Int, deviceId: String?, while still: SpotifyWebAPI.StillWanted? = nil) async throws {
        var q = ["position_ms": String(max(0, ms))]
        if let deviceId { q["device_id"] = deviceId }
        try await api.send("PUT", "me/player/seek", q, while: still)
    }

    /// Next or previous on the device (POST me/player/next | previous).
    func skipPlayback(forward: Bool, deviceId: String?, while still: SpotifyWebAPI.StillWanted? = nil) async throws {
        try await api.send("POST", forward ? "me/player/next" : "me/player/previous", deviceId.map { ["device_id": $0] } ?? [:],
                           while: still)
    }

    /// Spotify's own shuffle and repeat off (Yoin's engine owns the play modes).
    func playModesOff(deviceId: String?) async {
        var q = deviceId.map { ["device_id": $0] } ?? [:]
        q["state"] = "false"
        _ = try? await api.send("PUT", "me/player/shuffle", q)
        q["state"] = "off"
        _ = try? await api.send("PUT", "me/player/repeat", q)
    }

    // MARK: The heart (Liked Songs)

    /// `GET /me/library/contains`, 40 URIs at a time (the endpoint's maximum).
    private func contains(_ uris: [String]) async throws -> [Bool] {
        var out: [Bool] = []
        for batch in uris.chunked(40) {
            // Colons percent-encoded, commas as the list separator (the reference's own form).
            let encoded = batch.map { $0.replacingOccurrences(of: ":", with: "%3A") }.joined(separator: ",")
            let answer = try await api.get("me/library/contains?uris=\(encoded)", as: [Bool].self)
            guard answer.count == batch.count else { throw SpotifyAPIError.decoding("library/contains") }
            out += answer
        }
        return out
    }

    // MARK: Connect devices (Android getDevices / transferPlayback)

    struct Device: Identifiable, Equatable, Sendable {
        var id: String
        var name: String
        var type: String
        var isActive: Bool
    }

    private struct DevicesDTO: Decodable {
        struct D: Decodable { let id: String?; let name: String?; let type: String?; let isActive: Bool? }
        let devices: [D]
    }

    /// The account's Spotify Connect devices (user-read-playback-state).
    func devices() async throws -> [Device] {
        let r = try await api.get("me/player/devices", as: DevicesDTO.self)
        return r.devices.compactMap { d in
            guard let id = d.id else { return nil }
            return Device(id: id, name: d.name ?? "Device", type: d.type ?? "", isActive: d.isActive ?? false)
        }
    }

    /// Moves playback to [deviceId], playing there.
    func transfer(to deviceId: String) async throws {
        let json = try JSONSerialization.data(withJSONObject: ["device_ids": [deviceId], "play": true])
        try await api.send("PUT", "me/player", json: json)
    }

    // MARK: Following an artist (Android SpotifyMusicSource.setArtistFollowed)

    /// Follows or unfollows the artist on Spotify: PUT / DELETE me/library with the artist's URI
    /// (the Feb 2026 Development Mode change removed PUT / DELETE me/following).
    func setFollowed(_ on: Bool, artist rawId: String) async throws {
        try await api.send(on ? "PUT" : "DELETE", "me/library", ["uris": "spotify:artist:\(rawId)"])
    }

    /// Always read Spotify's answer; library lists may have been loaded before a change elsewhere.
    func isAlbumSaved(_ rawId: String) async throws -> Bool {
        try await contains(["spotify:album:\(rawId)"])[0]
    }

    func setAlbumSaved(_ on: Bool, album rawId: String) async throws {
        try await api.send(on ? "PUT" : "DELETE", "me/library", ["uris": "spotify:album:\(rawId)"])
    }

    /// Whether the track is in Liked Songs (nil: unknown, the read failed).
    func isSaved(_ rawId: String) async -> Bool? {
        if liked.contains(rawId) { return true }
        do {
            let saved = try await contains(["spotify:track:\(rawId)"]).first ?? false
            if saved { liked.insert(rawId) }
            return saved
        } catch {
            log.info("library check failed: \(Self.message(for: error), privacy: .public)")
            return nil
        }
    }

    /// Several tracks' hearts in one go (one request per 40); a track whose batch failed is left out.
    func savedStates(_ rawIds: [String]) async -> [String: Bool] {
        var out: [String: Bool] = [:]
        for batch in rawIds.chunked(40) {
            guard let answer = try? await contains(batch.map { "spotify:track:\($0)" }) else { continue }
            for (id, saved) in zip(batch, answer) {
                out[id] = saved
                if saved { liked.insert(id) } else { liked.remove(id) }
            }
        }
        return out
    }

    func save(_ rawId: String) async throws {
        try await api.send("PUT", "me/library", ["uris": "spotify:track:\(rawId)"])
        liked.insert(rawId)
    }

    func unsave(_ rawId: String) async throws {
        try await api.send("DELETE", "me/library", ["uris": "spotify:track:\(rawId)"])
        liked.remove(rawId)
    }
}

private extension Array {
    subscript(safe i: Int) -> Element? { indices.contains(i) ? self[i] : nil }

    func chunked(_ n: Int) -> [[Element]] {
        stride(from: 0, to: count, by: n).map { Array(self[$0 ..< Swift.min($0 + n, count)]) }
    }
}
