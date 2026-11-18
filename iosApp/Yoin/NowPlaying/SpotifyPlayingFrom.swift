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

    /// Seconds to wait before retrying `me/player` once when playback started without context.
    nonisolated(unsafe) static var contextRetryDelaySec: Double = 2

    /// The song [label] was read for (provider:id).
    private(set) var songKey: String?
    private(set) var label: Label?

    @ObservationIgnored private var task: Task<Void, Never>?
    /// Context key → its name, so a playlist or artist played through is named once.
    @ObservationIgnored private var names: [String: String] = [:]
    @ObservationIgnored private var heardContext: (trackURI: String, contextURI: String, title: String?)?
    @ObservationIgnored private var observedTrack: PlayableTrack?
    @ObservationIgnored private weak var observedSource: SpotifySource?
    @ObservationIgnored private var observedPlaylistTitle: (@MainActor (String) -> String?)?
    @ObservationIgnored private var observedAlbum: (@MainActor () -> Album?)?

    /// The label for [track], when it is the song it was read for.
    func label(for track: PlayableTrack?) -> Label? {
        guard let track, songKey == Self.key(track) else { return nil }
        return label
    }

    nonisolated static func key(_ t: PlayableTrack) -> String { "\(t.provider):\(t.id)" }

    nonisolated static func nameKey(_ uri: String) -> String {
        switch Context.parse(uri) {
        case .likedSongs: return "collection:tracks"
        case .album(let id): return "album:\(id)"
        case .playlist(let id): return "playlist:\(id)"
        case .artist(let id): return "artist:\(id)"
        case nil:
            if let url = URL(string: uri), url.host?.contains("spotify.com") == true {
                let segs = url.pathComponents.filter { $0 != "/" }
                if segs.count >= 2, ["album", "playlist", "artist"].contains(segs[0]), !segs[1].isEmpty {
                    return "\(segs[0]):\(segs[1])"
                }
            }
            return uri
        }
    }

    /// Context pushed by App Remote (`SPTAppRemotePlayerState.contextURI` / `contextTitle`).
    func heard(trackURI: String, contextURI: String?, title: String?) {
        guard !trackURI.hasPrefix("spotify:local:") else { return }
        let trackId = trackURI.hasPrefix("spotify:track:")
            ? String(trackURI.dropFirst("spotify:track:".count))
            : trackURI
        guard !trackId.isEmpty else { return }
        let cleanContext = contextURI.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.flatMap { $0.isEmpty ? nil : $0 }
        let cleanTitle = title.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.flatMap { $0.isEmpty ? nil : $0 }
        guard let cleanContext else { return }
        if let cleanTitle {
            names[Self.nameKey(cleanContext)] = cleanTitle
        }
        heardContext = (trackURI: trackURI, contextURI: cleanContext, title: cleanTitle)

        let expectedKey = "\(SpotifyMapping.provider):\(trackId)"
        guard songKey == expectedKey, let context = Context.parse(cleanContext) else { return }
        let canUpgradePlaceholder: Bool = {
            guard case .playlist(let id) = context, Self.isSpotifyMade(id) else { return false }
            return label?.name == "Spotify" && cleanTitle != nil
        }()
        guard label == nil || canUpgradePlaceholder else { return }
        switch context {
        case .likedSongs:
            label = Label(kind: String(localized: "PLAYING FROM PLAYLIST"), name: String(localized: "Liked Songs"), hint: "", route: nil)
        case .playlist(let id):
            let resolvedName = names[Self.nameKey(cleanContext)] ?? cleanTitle
            if let next = Self.playlistLabel(id: id, name: resolvedName) {
                label = next
            } else if let source = observedSource, let observedTrack {
                let playlistTitle = observedPlaylistTitle
                let albumForRoute = observedAlbum
                task?.cancel()
                task = Task { [weak self] in
                    let fetched = await self?.resolve(
                        context, uri: cleanContext, track: observedTrack,
                        source: source, playlistTitle: playlistTitle, albumForRoute: albumForRoute
                    )
                    guard !Task.isCancelled, let self, self.songKey == expectedKey, self.label == nil else { return }
                    self.label = fetched
                }
            }
        case .artist(let id):
            if let resolvedName = names[Self.nameKey(cleanContext)] ?? cleanTitle {
                label = Label(kind: String(localized: "PLAYING FROM ARTIST"), name: resolvedName,
                              hint: String(localized: "Go to artist"), route: .artist(ArtistRef(resolvedName, id: id)))
            }
        case .album:
            if let source = observedSource, let observedTrack {
                let playlistTitle = observedPlaylistTitle
                let albumForRoute = observedAlbum
                task?.cancel()
                task = Task { [weak self] in
                    let fetched = await self?.resolve(
                        context, uri: cleanContext, track: observedTrack,
                        source: source, playlistTitle: playlistTitle, albumForRoute: albumForRoute
                    )
                    guard !Task.isCancelled, let self, self.songKey == expectedKey, self.label == nil else { return }
                    self.label = fetched
                }
            }
        }
    }

    /// Spotify's own song now (Now Playing and the queue sheet ask while they show it): read once per song
    /// (with one delayed retry if Web API hasn't populated the active context yet).
    func observe(_ track: PlayableTrack?, model: AppModel) {
        guard let source = model.store.spotify else {
            if songKey != nil { reset() }
            return
        }
        observe(
            track,
            source: source,
            playlistTitle: { [weak model] id in model?.store.playlistSummary(id)?.title },
            albumForRoute: { [weak model] in model?.playback.album }
        )
    }

    func observe(
        _ track: PlayableTrack?,
        source: SpotifySource,
        playlistTitle: (@MainActor (String) -> String?)? = nil,
        albumForRoute: (@MainActor () -> Album?)? = nil
    ) {
        guard let track, track.provider == SpotifyMapping.provider,
              !track.id.hasPrefix("local:"), !track.id.hasPrefix("spotify:local:"),
              !SpotifyEpisodes.isEpisode(track) else {
            if songKey != nil { reset() }
            return
        }
        let key = Self.key(track)
        guard key != songKey else { return }
        songKey = key
        label = nil
        observedTrack = track
        observedSource = source
        observedPlaylistTitle = playlistTitle
        observedAlbum = albumForRoute
        task?.cancel()
        task = Task { [weak self] in
            guard let self else { return }
            var uri = self.matchingHeardContextURI(forTrackId: track.id)
            if uri == nil {
                uri = try? await source.playerContext(forTrack: track.id)
            }
            if uri == nil, !Task.isCancelled, self.songKey == key, self.label == nil {
                if Self.contextRetryDelaySec > 0 {
                    try? await Task.sleep(for: .seconds(Self.contextRetryDelaySec))
                }
                guard !Task.isCancelled, self.songKey == key, self.label == nil else { return }
                var retried = self.matchingHeardContextURI(forTrackId: track.id)
                if retried == nil {
                    retried = try? await source.playerContext(forTrack: track.id)
                }
                uri = retried
            }
            guard !Task.isCancelled, self.songKey == key, self.label == nil,
                  let uri, let context = Context.parse(uri) else { return }
            let resolved = await self.resolve(
                context, uri: uri, track: track,
                source: source, playlistTitle: playlistTitle, albumForRoute: albumForRoute
            )
            guard !Task.isCancelled, self.songKey == key, self.label == nil else { return }
            self.label = resolved
        }
    }

    private func matchingHeardContextURI(forTrackId trackId: String) -> String? {
        guard let heard = heardContext else { return nil }
        guard heard.trackURI == trackId || heard.trackURI == "spotify:track:\(trackId)" else { return nil }
        return heard.contextURI
    }

    func reset() {
        task?.cancel()
        songKey = nil
        label = nil
        observedTrack = nil
        observedSource = nil
        observedPlaylistTitle = nil
        observedAlbum = nil
    }

    nonisolated static func playlistLabel(id: String, name: String?) -> Label? {
        let clean = name.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.flatMap { $0.isEmpty ? nil : $0 }
        if let clean {
            // Spotify's own lists (37i9dQZ…) open empty in Development Mode (#24): named, not linked.
            let opens = !Self.isSpotifyMade(id)
            return Label(kind: String(localized: "PLAYING FROM PLAYLIST"), name: clean,
                         hint: opens ? String(localized: "Go to playlist") : "", route: opens ? .playlist(id) : nil)
        }
        guard Self.isSpotifyMade(id) else { return nil }
        return Label(kind: String(localized: "PLAYING FROM PLAYLIST"), name: "Spotify", hint: "", route: nil)
    }

    private func resolve(
        _ context: Context, uri: String, track: PlayableTrack,
        source: SpotifySource, playlistTitle: (@MainActor (String) -> String?)?, albumForRoute: (@MainActor () -> Album?)?
    ) async -> Label? {
        switch context {
        case .likedSongs:
            return Label(kind: String(localized: "PLAYING FROM PLAYLIST"), name: String(localized: "Liked Songs"), hint: "", route: nil)
        case .album(let id):
            // The song's own album: its page is the one Now Playing already builds for the song.
            guard track.albumId == id, !track.album.isEmpty, let album = albumForRoute?() else { return nil }
            return Label(kind: String(localized: "PLAYING FROM ALBUM"), name: track.album,
                         hint: String(localized: "Go to album"), route: .album(album))
        case .playlist(let id):
            let cacheKey = Self.nameKey(uri)
            var name = names[cacheKey] ?? playlistTitle?(id)
            if name == nil { name = try? await source.playlistName(id) }
            if let name, !name.isEmpty { names[cacheKey] = name }
            return Self.playlistLabel(id: id, name: name)
        case .artist(let id):
            let cacheKey = Self.nameKey(uri)
            var name = names[cacheKey]
            if name == nil { name = try? await source.artistName(id) }
            guard let name else { return nil }
            names[cacheKey] = name
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
