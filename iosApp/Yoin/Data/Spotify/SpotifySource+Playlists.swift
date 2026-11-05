import Foundation
import YoinShared

/// Spotify playlist writes and the reads a backup and a restore make (Android SpotifyMusicSource's
/// writeActions and `getPlaylistInFull`, SpotifyApiClient's playlist mutation):
///
/// - create: `POST me/playlists` (private; `/users/{id}/playlists` is deprecated), owned by the caller;
/// - rename: `PUT playlists/{id}`;
/// - add: `POST playlists/{id}/items` with track URIs, answering the new `snapshot_id`;
/// - remove: `DELETE playlists/{id}/items` with each URI's positions and the `snapshot_id` they were read
///   at, so one occurrence of a repeated song goes and not the others;
/// - delete: Spotify has none; unfollowing removes it from the user's playlists
///   (`DELETE me/library?uris=spotify:playlist:…`; `/playlists/{id}/followers` is gone for
///   Development Mode apps since 2026-02).
///
/// Only the user's own playlists are renamed or deleted; a collaborative one also takes songs added and
/// removed. Someone else's answers 403 for its items (2026-02), so it is neither edited nor backed up.
extension SpotifySource {
    enum PlaylistError: Error, Equatable {
        /// Spotify refused the playlist's songs (someone else's playlist): nothing to back up.
        case tracksUnavailable
        /// A write's answer named no playlist.
        case noPlaylist
    }

    /// One occurrence to remove: the track and its position among all the playlist's items.
    struct Removal: Hashable, Sendable {
        var trackRawId: String
        var position: Int
    }

    /// Android's create/add batch maximum (Spotify takes 100 URIs a request).
    static let addBatch = 100

    // MARK: Ownership

    /// The signed-in user's id, from `me` when the account didn't hand it over.
    func meId() async -> String? {
        if let userId { return userId }
        struct Me: Decodable { var id: String? }
        let id = try? await api.get("me", as: Me.self).id
        if let id, !id.isEmpty { userId = id }
        return userId
    }

    /// Android `ownedBy`: the user made it; nil when either id is unknown. A collaborator doesn't own it.
    nonisolated static func owned(_ p: SpotifyDTO.Playlist, by me: String?) -> Bool? {
        guard let owner = p.owner?.id, !owner.isEmpty, let me, !me.isEmpty else { return nil }
        return owner == me
    }

    /// Songs can be added and removed: the user's own playlist, or a collaborative one.
    nonisolated static func takesSongs(_ p: SpotifyDTO.Playlist, me: String?) -> Bool {
        owned(p, by: me) == true || p.collaborative == true
    }

    /// [info] with what a write needs: who owns it, its snapshot, and each entry's raw position.
    func withWriteMetadata(_ info: PlaylistInfo, _ p: SpotifyDTO.Playlist, positions: [Int]?) async -> PlaylistInfo {
        var info = info
        info.ownedByMe = Self.owned(p, by: await meId())
        info.collaborative = p.collaborative == true
        info.snapshotId = p.snapshotId
        info.positions = positions?.count == info.entries.count ? positions : nil
        return info
    }

    /// Every playlist of the account (the Library rows), and the ones it can add songs to (the picker).
    func writablePlaylists() async throws -> (all: [LibraryItem], writable: [LibraryItem]) {
        let read = try await api.all("me/playlists", ["limit": "50"], of: SpotifyDTO.Playlist?.self, cap: .max)
            .compactMap { $0 }
        let me = await meId()
        return (read.map(SpotifyMapping.playlistItem),
                read.filter { Self.takesSongs($0, me: me) }.map(SpotifyMapping.playlistItem))
    }

    // MARK: Writes

    private struct SnapshotAnswer: Decodable { var snapshotId: String? }

    private static func json(_ body: [String: Any]) throws -> Data {
        try JSONSerialization.data(withJSONObject: body, options: [.sortedKeys])
    }

    nonisolated static func trackURI(_ rawId: String) -> String { "spotify:track:\(rawId)" }

    /// A new private playlist (Android passes `public: false` explicitly: Spotify's default is public).
    func createPlaylist(name: String, description: String? = nil) async throws -> SpotifyDTO.Playlist {
        var body: [String: Any] = ["name": name, "public": false]
        if let d = description?.trimmingCharacters(in: .whitespacesAndNewlines), !d.isEmpty { body["description"] = d }
        let data = try await api.send("POST", "me/playlists", json: try Self.json(body))
        do { return try SpotifyWebAPI.decoder.decode(SpotifyDTO.Playlist.self, from: data) } catch {
            throw PlaylistError.noPlaylist
        }
    }

    func renamePlaylist(_ rawId: String, name: String, description: String? = nil) async throws {
        var body: [String: Any] = ["name": name]
        if let description { body["description"] = description }
        try await api.send("PUT", "playlists/\(rawId)", json: try Self.json(body))
    }

    /// Appends [trackRawIds] in order, 100 a request; the playlist's snapshot after the last.
    @discardableResult
    func addTracks(_ trackRawIds: [String], to rawId: String) async throws -> String? {
        var snapshot: String?
        for start in stride(from: 0, to: trackRawIds.count, by: Self.addBatch) {
            let batch = trackRawIds[start..<min(start + Self.addBatch, trackRawIds.count)].map(Self.trackURI)
            let data = try await api.send("POST", "playlists/\(rawId)/items", json: try Self.json(["uris": batch]))
            snapshot = (try? SpotifyWebAPI.decoder.decode(SnapshotAnswer.self, from: data))?.snapshotId ?? snapshot
        }
        return snapshot
    }

    /// The body of a removal: one item per URI with all its positions, sorted (a song in the playlist twice
    /// loses only the occurrences named), against [snapshotId].
    nonisolated static func removalBody(_ items: [Removal], snapshotId: String?) -> [String: Any] {
        var order: [String] = []
        var positions: [String: [Int]] = [:]
        for item in items {
            let uri = trackURI(item.trackRawId)
            if positions[uri] == nil { order.append(uri) }
            positions[uri, default: []].append(item.position)
        }
        var body: [String: Any] = ["tracks": order.map { ["uri": $0, "positions": positions[$0]!.sorted()] }]
        if let snapshotId { body["snapshot_id"] = snapshotId }
        return body
    }

    /// Removes the named occurrences; the playlist's new snapshot ([snapshotId] when nothing was asked).
    func removeTracks(_ items: [Removal], from rawId: String, snapshotId: String?) async throws -> String? {
        guard !items.isEmpty else { return snapshotId }
        let data = try await api.send("DELETE", "playlists/\(rawId)/items",
                                      json: try Self.json(Self.removalBody(items, snapshotId: snapshotId)))
        return (try? SpotifyWebAPI.decoder.decode(SnapshotAnswer.self, from: data))?.snapshotId ?? snapshotId
    }

    /// "Delete": the playlist leaves the user's library.
    func unfollowPlaylist(_ rawId: String) async throws {
        try await api.send("DELETE", "me/library", ["uris": "spotify:playlist:\(rawId)"])
    }

    // MARK: Backup

    /// [rawId] read whole for a backup (Android `getPlaylistInFull`, up to 10 000 entries): its songs in
    /// order, repeats kept, and each entry that isn't a song (a local file, an episode, one Spotify no longer
    /// has) as unreadable at its place. nil: Spotify doesn't have it. Refused songs throw
    /// `PlaylistError.tracksUnavailable`: a backup isn't made from them, and a followed one stays as it was.
    func playlistForBackup(_ rawId: String, cap: Int = 10_000) async throws -> SnapshotPlaylist? {
        let p: SpotifyDTO.Playlist
        do {
            p = try await api.get("playlists/\(rawId)", ["additional_types": "track"], as: SpotifyDTO.Playlist.self)
        } catch let e as SpotifyAPIError where e.isNotFound {
            return nil
        }
        var items: [SpotifyDTO.PlaylistItem]
        do {
            if let first = p.entries, !first.items.isEmpty || first.next != nil {
                items = first.items
                var next = first.next
                var visited = Set<String>()
                while let page = next, items.count < cap {
                    guard visited.insert(page).inserted else { throw SpotifyAPIError.decoding("repeated page") }
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
            throw PlaylistError.tracksUnavailable
        }
        return Self.snapshot(p, items: Array(items.prefix(cap)))
    }

    /// A playlist's items as a backup keeps them (Android `toTracksWithPlaylistOffsets` + `unreadableItems`).
    nonisolated static func snapshot(_ p: SpotifyDTO.Playlist, items: [SpotifyDTO.PlaylistItem]) -> SnapshotPlaylist {
        var tracks: [SnapshotTrack] = []
        var unreadable: [SnapshotUnreadable] = []
        for (i, item) in items.enumerated() {
            guard let t = item.playable else {
                unreadable.append(SnapshotUnreadable(position: Int32(i), title: nil, artist: nil, reason: .unavailable))
                continue
            }
            let names = (t.artists ?? []).compactMap(\.name).filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
            let reason: UnexportedReason? = if t.isLocal == true { .localfile }
                else if t.type == "episode" { .episode }
                else if (t.id ?? "").isEmpty { .unavailable }
                else { nil }
            if let reason {
                let title = t.name.flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 }
                unreadable.append(SnapshotUnreadable(position: Int32(i), title: title,
                                                     artist: names.isEmpty ? nil : names.joined(separator: ", "),
                                                     reason: reason))
                continue
            }
            tracks.append(snapshotTrack(t))
        }
        return SnapshotPlaylist(id: "\(SpotifyMapping.provider):\(p.id)", name: p.name ?? "",
                                comment: SpotifyMapping.plainText(p.description), createdAt: nil,
                                tracks: tracks, unreadable: unreadable)
    }

    /// One playable track as a backup keeps it: every credited artist and its ISRC (upper case, Android EXTRA_ISRC).
    nonisolated static func snapshotTrack(_ t: SpotifyDTO.Track) -> SnapshotTrack {
        let names = (t.artists ?? []).compactMap(\.name).filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
        let seconds = t.durationMs.map { $0 / 1000 }
        return SnapshotTrack(id: "\(SpotifyMapping.provider):\(t.id ?? "")", title: t.name, artists: names,
                             album: t.album?.name, durationSec: seconds.map { KotlinInt(int: Int32($0)) },
                             isrc: isrc(t))
    }

    nonisolated static func isrc(_ t: SpotifyDTO.Track) -> String? {
        guard let s = t.externalIds?.isrc?.trimmingCharacters(in: .whitespaces), !s.isEmpty else { return nil }
        return s.uppercased()
    }

    // MARK: Restore

    /// A track as a restore's match sees it (`spotify:rawId`, the credited artists joined with ", ").
    nonisolated static func matchTrack(_ t: SpotifyDTO.Track) -> MatchTrack? {
        guard SpotifyMapping.isPlayable(t), let id = t.id else { return nil }
        let names = (t.artists ?? []).compactMap(\.name).filter { !$0.isEmpty }
        let seconds = (t.durationMs ?? 0) / 1000
        return MatchTrack(id: "\(SpotifyMapping.provider):\(id)", title: t.name ?? "",
                          artist: names.isEmpty ? nil : names.joined(separator: ", "), album: t.album?.name,
                          durationSec: seconds > 0 ? KotlinInt(int: Int32(seconds)) : nil, isrc: isrc(t))
    }

    /// Catalog songs for [query] (ten: the Development Mode maximum).
    func searchSongsForMatch(_ query: String) async throws -> [MatchTrack] {
        let r = try await api.get("search", ["q": query, "type": "track", "limit": "10"], as: SpotifyDTO.Search.self)
        return (r.tracks?.items ?? []).compactMap { $0.flatMap(Self.matchTrack) }
    }

    /// Android `tracksByIsrc`: one `isrc:` search per code, one after another; a code whose search fails is
    /// left out (the match falls back to a title search). A row naming another code is not taken.
    func tracksByIsrc(_ isrcs: [String]) async -> [String: [MatchTrack]] {
        var found: [String: [MatchTrack]] = [:]
        var seen = Set<String>()
        let pattern = /^[A-Z]{2}[A-Z0-9]{3}[0-9]{7}$/
        for code in isrcs.map({ $0.trimmingCharacters(in: .whitespaces).uppercased() }) where seen.insert(code).inserted {
            guard code.wholeMatch(of: pattern) != nil, !Task.isCancelled,
                  let tracks = try? await searchSongsForMatch("isrc:\(code)") else { continue }
            let exact = tracks.filter { ($0.isrc ?? code) == code }
            if !exact.isEmpty { found[code] = exact }
        }
        return found
    }
}
