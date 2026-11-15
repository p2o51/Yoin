import Foundation
import Observation

/// Where Spotify's own song plays from (audit 11-15 #35; Android derives "Playing from" from Spotify's
/// player context, PlaybackManager): the album, playlist or artist Spotify plays it in, read from
/// me/player's context once per song, its name from what Yoin already holds or one read. Now Playing's
/// top line and the queue sheet's header show it; nil while unknown, for a context Yoin can't name, or
/// when Spotify plays the song on its own (no context).
@MainActor
@Observable
final class SpotifyPlayingFrom {
    static let shared = SpotifyPlayingFrom()

    /// What Spotify's context URI names.
    enum Context: Equatable, Sendable {
        case album(String), playlist(String), artist(String), likedSongs

        /// spotify:album:<id>, spotify:playlist:<id>, spotify:user:<u>:playlist:<id> (older lists),
        /// spotify:artist:<id>, spotify:user:<u>:collection / spotify:collection (Liked Songs).
        static func parse(_ uri: String?) -> Context? {
            guard let uri else { return nil }
            let parts = uri.split(separator: ":", omittingEmptySubsequences: false).map(String.init)
            guard parts.first == "spotify", parts.count >= 2 else { return nil }
            if parts.last == "collection" || parts.dropFirst().first == "collection" { return .likedSongs }
            for (i, kind) in parts.enumerated().dropFirst() where i + 1 < parts.count {
                let id = parts[i + 1]
                guard !id.isEmpty else { continue }
                switch kind {
                case "album": return .album(id)
                case "playlist": return .playlist(id)
                case "artist": return .artist(id)
                default: continue
                }
            }
            return nil
        }
    }

    struct Label: Equatable {
        var kind: String
        var name: String
        var hint: String
        var route: Route?
    }

    /// The song [label] was read for (provider:id).
    private(set) var songKey: String?
    private(set) var label: Label?

    @ObservationIgnored private var task: Task<Void, Never>?
    /// Context URI → its name, so a playlist played through is named once.
    @ObservationIgnored private var names: [String: String] = [:]

    /// The label for [track], when it is the song it was read for.
    func label(for track: PlayableTrack?) -> Label? {
        guard let track, songKey == Self.key(track) else { return nil }
        return label
    }

    nonisolated static func key(_ t: PlayableTrack) -> String { "\(t.provider):\(t.id)" }

    /// Spotify's own song now (Now Playing and the queue sheet ask while they show it): read once per song.
    func observe(_ track: PlayableTrack?, model: AppModel) {
        guard let track, track.provider == SpotifyMapping.provider, !SpotifyEpisodes.isEpisode(track),
              let source = model.store.spotify else {
            if songKey != nil { reset() }
            return
        }
        let key = Self.key(track)
        guard key != songKey else { return }
        songKey = key
        label = nil
        task?.cancel()
        task = Task { [weak self] in
            guard let uri = try? await source.playerContext(forTrack: track.id), !Task.isCancelled,
                  let context = Context.parse(uri) else { return }
            let label = await self?.resolve(context, uri: uri, track: track, model: model, source: source)
            guard !Task.isCancelled, let self, self.songKey == key else { return }
            self.label = label
        }
    }

    func reset() {
        task?.cancel()
        songKey = nil
        label = nil
    }

    private func resolve(_ context: Context, uri: String, track: PlayableTrack, model: AppModel,
                         source: SpotifySource) async -> Label? {
        switch context {
        case .likedSongs:
            return Label(kind: String(localized: "PLAYING FROM PLAYLIST"), name: String(localized: "Liked Songs"), hint: "", route: nil)
        case .album(let id):
            // The song's own album: its page is the one Now Playing already builds for the song.
            guard track.albumId == id, !track.album.isEmpty else { return nil }
            return Label(kind: String(localized: "PLAYING FROM ALBUM"), name: track.album,
                         hint: String(localized: "Go to album"), route: .album(model.playback.album))
        case .playlist(let id):
            var name = names[uri] ?? model.store.playlistSummary(id)?.title
            if name == nil { name = try? await source.playlistName(id) }
            guard let name else { return nil }
            names[uri] = name
            // Spotify's own lists (37i9dQZ…) open empty in Development Mode (#24): named, not linked.
            let opens = !Self.isSpotifyMade(id)
            return Label(kind: String(localized: "PLAYING FROM PLAYLIST"), name: name,
                         hint: opens ? String(localized: "Go to playlist") : "", route: opens ? .playlist(id) : nil)
        case .artist(let id):
            var name = names[uri]
            if name == nil { name = try? await source.artistName(id) }
            guard let name else { return nil }
            names[uri] = name
            return Label(kind: String(localized: "PLAYING FROM ARTIST"), name: name,
                         hint: String(localized: "Go to artist"), route: .artist(ArtistRef(name, id: id)))
        }
    }

    nonisolated static func isSpotifyMade(_ playlistId: String) -> Bool { playlistId.hasPrefix("37i9dQZ") }
}

extension SpotifySource {
    private struct PlayerContextDTO: Decodable {
        struct Context: Decodable { let uri: String? }
        struct Item: Decodable { let id: String? }
        let context: Context?
        let item: Item?
    }

    /// Spotify's player context URI (GET me/player), when it still plays [trackId]; nil without one.
    func playerContext(forTrack trackId: String) async throws -> String? {
        let data = try await api.send("GET", "me/player")
        guard !data.isEmpty else { return nil }
        let r = try SpotifyWebAPI.decoder.decode(PlayerContextDTO.self, from: data)
        guard r.item?.id == trackId else { return nil }
        return r.context?.uri
    }

    private struct NameDTO: Decodable { let name: String? }

    /// A playlist's name alone (GET playlists/{id}?fields=name).
    func playlistName(_ id: String) async throws -> String? {
        try await api.get("playlists/\(id)", ["fields": "name"], as: NameDTO.self).name.flatMap { $0.isEmpty ? nil : $0 }
    }

    /// An artist's name (GET artists/{id}).
    func artistName(_ id: String) async throws -> String? {
        try await api.get("artists/\(id)", as: NameDTO.self).name.flatMap { $0.isEmpty ? nil : $0 }
    }
}
