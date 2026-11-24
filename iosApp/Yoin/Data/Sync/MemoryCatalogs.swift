import Foundation
import YoinShared

/// The catalogs a Memories transfer matches on (Android AppMemoriesOps.withCatalog + ServiceMemoryCatalog):
/// the target account's own service, opened on its own (never the account in use's client), plus the source
/// account's service when the source is on this device (only to describe a song it knows nothing about).
@MainActor
enum MemoryCatalogs {
    /// The catalog for copying into [targetProfileId]; nil when its service can't be reached now (signed out,
    /// Apple Music access off).
    static func make(target targetProfileId: String, source sourceProfileId: String?,
                     model: AppModel = .shared) -> MemoryCatalog? {
        let manager = model.profiles
        guard let target = manager.profile(targetProfileId) else { return nil }
        let describe = sourceProfileId.flatMap { describer(for: $0, model: model) }
        switch target.service {
        case .subsonic:
            guard let account = manager.signIn(target.id) else { return nil }
            return SubsonicMemoryCatalog(account: account, describe: describe)
        case .appleMusic:
            let apple = model.store.appleMusic
            guard apple.isAuthorized else { return nil }
            return AppleMusicMemoryCatalog(apple: apple, describe: describe)
        case .spotify:
            guard let spotify = model.spotifySource(forProfile: target.id) else { return nil }
            return SpotifyMemoryCatalog(spotify: spotify, describe: describe)
        }
    }

    /// How the source's service describes one of its songs (title, artist, album, duration, ISRC).
    typealias Describe = @MainActor (_ provider: String, _ trackId: String) async -> MatchTrack?

    private static func describer(for profileId: String, model: AppModel) -> Describe? {
        guard let p = model.profiles.profile(profileId) else { return nil }
        switch p.service {
        case .spotify:
            guard let spotify = model.spotifySource(forProfile: p.id) else { return nil }
            return { provider, trackId in
                guard provider == SpotifyMapping.provider,
                      let entry = try? await spotify.track(trackId) else { return nil }
                let isrc = await spotify.isrc(trackId: trackId)
                return MatchTrack(id: "\(provider):\(trackId)", title: entry.track.title,
                                  artist: entry.track.artist ?? entry.album.artist, album: entry.album.title,
                                  durationSec: entry.track.durationSec > 0 ? KotlinInt(int: Int32(entry.track.durationSec)) : nil,
                                  isrc: isrc)
            }
        case .appleMusic:
            let apple = model.store.appleMusic
            guard apple.isAuthorized else { return nil }
            return { provider, trackId in
                guard provider == AppleMusicMapping.provider, let isrc = await apple.isrc(trackId: trackId) else { return nil }
                return MatchTrack(id: "\(provider):\(trackId)", title: nil, artist: nil, album: nil, durationSec: nil, isrc: isrc)
            }
        case .subsonic:
            return nil
        }
    }
}

/// A Subsonic server's catalog: `search3` songs and albums (no ISRC lookup).
@MainActor
final class SubsonicMemoryCatalog: MemoryCatalog {
    private let client: LazySubsonicClient
    private let describe: MemoryCatalogs.Describe?

    init(account: ServerSession.Account, describe: MemoryCatalogs.Describe?) {
        client = LazySubsonicClient(account)
        self.describe = describe
    }

    func searchTracks(_ query: String) async -> [CatalogTrack] {
        guard let found = try? await client.get().search3(query: query, artistCount: 0, albumCount: 0, songCount: 20) else { return [] }
        return found.songs.map { s in
            CatalogTrack(track: SubsonicRestoreDestination.track(s), albumProvider: s.ref.provider, albumId: s.albumId)
        }
    }

    func searchAlbums(_ query: String) async -> [CatalogAlbum] {
        guard let found = try? await client.get().search3(query: query, artistCount: 0, albumCount: 20, songCount: 0) else { return [] }
        return found.albums.map { CatalogAlbum(provider: $0.ref.provider, id: $0.id, name: $0.name, artist: $0.artist) }
    }

    func describeSourceTrack(provider: String, trackId: String) async -> MatchTrack? { await describe?(provider, trackId) }

    func close() { client.close() }
}

/// This device's Apple Music catalog: songs (with ISRC lookups) and albums.
@MainActor
final class AppleMusicMemoryCatalog: MemoryCatalog {
    private let apple: AppleMusicSource
    private let describe: MemoryCatalogs.Describe?

    init(apple: AppleMusicSource, describe: MemoryCatalogs.Describe?) {
        self.apple = apple
        self.describe = describe
    }

    private static func track(_ s: AMSong) -> CatalogTrack {
        CatalogTrack(track: AppleMusicRestoreDestination.track(s), albumProvider: AppleMusicMapping.provider, albumId: s.albumId)
    }

    func searchTracks(_ query: String) async -> [CatalogTrack] {
        ((try? await apple.searchSongs(query)) ?? []).map(Self.track)
    }

    func tracksByIsrc(_ isrcs: [String]) async -> [String: [CatalogTrack]] {
        await apple.catalogSongsByIsrc(isrcs).mapValues { $0.map(Self.track) }
    }

    func searchAlbums(_ query: String) async -> [CatalogAlbum] {
        guard let found = try? await apple.search(query) else { return [] }
        return found.albums.map { CatalogAlbum(provider: $0.id.provider, id: $0.id.rawId, name: $0.title, artist: $0.artist) }
    }

    func describeSourceTrack(provider: String, trackId: String) async -> MatchTrack? { await describe?(provider, trackId) }
}

/// A Spotify account's catalog (its Web API): songs, `isrc:` searches and albums.
@MainActor
final class SpotifyMemoryCatalog: MemoryCatalog {
    private let spotify: SpotifySource
    private let describe: MemoryCatalogs.Describe?

    init(spotify: SpotifySource, describe: MemoryCatalogs.Describe?) {
        self.spotify = spotify
        self.describe = describe
    }

    private static func track(_ e: PlaylistEntry) -> CatalogTrack {
        let seconds = e.track.durationSec
        let artist = e.track.artist ?? e.album.artist
        return CatalogTrack(track: MatchTrack(id: "\(e.track.id.provider):\(e.track.id.rawId)", title: e.track.title,
                                              artist: artist.isEmpty ? nil : artist, album: e.album.title,
                                              durationSec: seconds > 0 ? KotlinInt(int: Int32(seconds)) : nil, isrc: nil),
                            albumProvider: e.album.id.provider, albumId: e.album.id.rawId)
    }

    func searchTracks(_ query: String) async -> [CatalogTrack] {
        ((try? await spotify.search(query, kinds: [.songs]))?.songs ?? []).map(Self.track)
    }

    /// One `isrc:` search per code (Android `tracksByIsrc`), keeping the album each song is on.
    func tracksByIsrc(_ isrcs: [String]) async -> [String: [CatalogTrack]] {
        var found: [String: [CatalogTrack]] = [:]
        for code in Set(isrcs.map { $0.trimmingCharacters(in: .whitespaces).uppercased() }) where !code.isEmpty {
            let songs = ((try? await spotify.search("isrc:\(code)", kinds: [.songs]))?.songs ?? []).map(Self.track)
            if !songs.isEmpty { found[code] = songs }
        }
        return found
    }

    func searchAlbums(_ query: String) async -> [CatalogAlbum] {
        ((try? await spotify.search(query, kinds: [.albums]))?.albums ?? [])
            .map { CatalogAlbum(provider: $0.id.provider, id: $0.id.rawId, name: $0.title, artist: $0.artist.isEmpty ? nil : $0.artist) }
    }

    func describeSourceTrack(provider: String, trackId: String) async -> MatchTrack? { await describe?(provider, trackId) }
}
