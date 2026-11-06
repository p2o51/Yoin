// Mac copy of iosApp/Yoin/Data/Spotify (branch claude/project-thread-2amedi); merge once the branches meet.
import Foundation

/// Spotify Web API JSON (the shapes Android's SpotifyDtos.kt reads). Everything
/// a real answer can leave out is optional: removed tracks come back as null,
/// a local file has no ids, and the February 2026 changes renamed fields
/// (`tracks` → `items`) while still sending the old ones. Decoded with
/// `SpotifyWebAPI.decoder` (snake_case keys).
enum SpotifyDTO {
    struct Image: Decodable, Hashable, Sendable {
        var url: String
        var width: Int?
        var height: Int?
    }

    /// An artist as an album or track names it (simplified), or the full object
    /// with `images` from `/artists/{id}`, search and the follow list.
    /// A local file's artist has a null id.
    struct Artist: Decodable, Hashable, Sendable {
        var id: String?
        var name: String?
        var images: [Image]?
    }

    /// An album, simplified (inside a track, an artist's releases, search) or
    /// full (`/albums/{id}` carries `tracks`). A local file's album has a null id.
    struct Album: Decodable, Hashable, Sendable {
        var id: String?
        var name: String?
        var artists: [Artist]?
        var images: [Image]?
        var totalTracks: Int?
        var releaseDate: String?
        var albumType: String?
        var tracks: Paging<Track>?
    }

    struct ExternalIds: Decodable, Hashable, Sendable {
        var isrc: String?
    }

    struct Track: Decodable, Hashable, Sendable {
        var id: String?
        var name: String?
        var artists: [Artist]?
        var album: Album?
        var durationMs: Int?
        var trackNumber: Int?
        var discNumber: Int?
        var externalIds: ExternalIds?
        /// `track`, or `episode` for a podcast episode in a playlist.
        var type: String?
        /// A file from the user's device: no id, never playable through the Web API.
        var isLocal: Bool?
    }

    struct Owner: Decodable, Hashable, Sendable {
        var id: String?
        var displayName: String?
    }

    struct PlaylistItem: Decodable, Hashable, Sendable {
        /// `item` since the February 2026 migration; `track` before (still sent).
        var item: Track?
        var track: Track?
        var playable: Track? { item ?? track }
    }

    struct Playlist: Decodable, Hashable, Sendable {
        var id: String
        var name: String?
        var description: String?
        var images: [Image]?
        var owner: Owner?
        var `public`: Bool?
        var items: Paging<PlaylistItem>?
        var tracks: Paging<PlaylistItem>?
        var snapshotId: String?
        /// Its owner lets others add and remove songs.
        var collaborative: Bool?
        /// The entries under their current name, else the deprecated one. Only
        /// playlists the user owns or collaborates on fill it (2026-02).
        var entries: Paging<PlaylistItem>? { items ?? tracks }
    }

    struct SavedTrack: Decodable, Hashable, Sendable {
        var addedAt: String?
        var track: Track?
    }

    struct SavedAlbum: Decodable, Hashable, Sendable {
        var addedAt: String?
        var album: Album?
    }

    struct PlayHistory: Decodable, Hashable, Sendable {
        var track: Track?
        var playedAt: String?
    }

    /// An offset page. A playlist's stub (`{href,total}`) has no items: empty.
    struct Paging<T: Decodable & Hashable & Sendable>: Decodable, Hashable, Sendable {
        var items: [T]
        var next: String?
        var total: Int?

        enum CodingKeys: String, CodingKey { case items, next, total }

        init(items: [T] = [], next: String? = nil, total: Int? = nil) {
            self.items = items
            self.next = next
            self.total = total
        }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            items = try c.decodeIfPresent([T].self, forKey: .items) ?? []
            next = try c.decodeIfPresent(String.self, forKey: .next)
            total = try c.decodeIfPresent(Int.self, forKey: .total)
        }
    }

    /// `/me/following?type=artist` wraps a cursor page in `artists`.
    struct FollowedArtists: Decodable, Sendable {
        var artists: Paging<Artist>
    }

    /// `/me/player/recently-played`: a cursor page; one request is enough for Home.
    struct RecentlyPlayed: Decodable, Sendable {
        var items: [PlayHistory]
    }

    struct Search: Decodable, Sendable {
        var tracks: Paging<Track?>?
        var albums: Paging<Album?>?
        var artists: Paging<Artist?>?
        var playlists: Paging<Playlist?>?
    }
}
