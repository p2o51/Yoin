// Mac copy of iosApp/Yoin/Data/Spotify (branch claude/project-thread-2amedi); merge once the branches meet.
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
        // The Mac's PlaylistInfo has no write fields yet (no Spotify playlist editing on the Mac).
        info
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
    /// loses only the occurrences named), against [snapshotId]. The field is `items` on the /items
    /// endpoint (`tracks` was the deprecated /tracks endpoint's); per-item positions are no longer in the
    /// reference, so a caller re-reads a playlist that still holds the song elsewhere.
    nonisolated static func removalBody(_ items: [Removal], snapshotId: String?) -> [String: Any] {
        var order: [String] = []
        var positions: [String: [Int]] = [:]
        for item in items {
            let uri = trackURI(item.trackRawId)
            if positions[uri] == nil { order.append(uri) }
            positions[uri, default: []].append(item.position)
        }
        var body: [String: Any] = ["items": order.map { ["uri": $0, "positions": positions[$0]!.sorted()] }]
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

    // Backup / restore and track matching are iOS-only for now (their snapshot types aren't on the Mac).
}
