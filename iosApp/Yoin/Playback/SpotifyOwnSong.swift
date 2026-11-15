import Foundation

/// What Yoin knows of a song Spotify plays on its own (engine.externalTrack) beyond its PlayableTrack:
/// whether it is a podcast episode, and the play modes Spotify itself runs it with.

/// Podcast episodes Spotify played here (App Remote names them `spotify:episode:<id>`). The engine shows an
/// episode like a song, so the song-only parts (heart, rating, lyrics, About's lookup, history) ask here.
/// The Web API's me/player leaves episodes out, so one played on another device never shows at all.
@MainActor
enum SpotifyEpisodes {
    private static var ids: Set<String> = []

    /// A URI App Remote reported.
    static func note(_ uri: String) {
        guard let id = episodeId(uri) else { return }
        ids.insert(id)
    }

    static func isEpisode(_ t: PlayableTrack) -> Bool {
        t.provider == SpotifyMapping.provider && ids.contains(t.id)
    }

    nonisolated static func episodeId(_ uri: String) -> String? {
        let prefix = "spotify:episode:"
        guard uri.hasPrefix(prefix) else { return nil }
        let id = String(uri.dropFirst(prefix.count))
        return id.isEmpty ? nil : id
    }
}

/// Spotify's own shuffle and repeat, as the play-mode button shows them on Spotify's own song.
struct SpotifyModes: Equatable, Sendable {
    var shuffle: Bool
    var repeatMode: RepeatMode

    /// App Remote's repeat (SPTAppRemotePlaybackOptionsRepeatMode raw: 0 off, 1 track, 2 context).
    init(shuffle: Bool, repeatRaw: UInt) {
        self.shuffle = shuffle
        repeatMode = switch repeatRaw {
        case 1: .one
        case 2: .all
        default: .off
        }
    }

    /// The Web API's repeat_state ("off", "track", "context").
    init(shuffle: Bool, repeatState: String) {
        self.shuffle = shuffle
        repeatMode = switch repeatState {
        case "track": .one
        case "context": .all
        default: .off
        }
    }

    init(shuffle: Bool, repeatMode: RepeatMode) {
        self.shuffle = shuffle
        self.repeatMode = repeatMode
    }

    /// The round button's tap, the same turn as the queue's (PlayerEngine.cyclePlayMode).
    var cycled: SpotifyModes {
        switch (shuffle, repeatMode) {
        case (false, .all): SpotifyModes(shuffle: true, repeatMode: .all)
        case (true, .all): SpotifyModes(shuffle: true, repeatMode: .one)
        case (true, .one): SpotifyModes(shuffle: false, repeatMode: .one)
        case (false, .one): SpotifyModes(shuffle: false, repeatMode: .all)
        case (_, .off): SpotifyModes(shuffle: shuffle, repeatMode: .all)
        }
    }

    /// The Web API's repeat state for [repeatMode].
    var repeatState: String {
        switch repeatMode {
        case .one: "track"
        case .all: "context"
        case .off: "off"
        }
    }
}

extension SpotifySource {
    /// Spotify's own shuffle on its active device (PUT me/player/shuffle).
    func setShuffle(_ on: Bool) async throws {
        try await api.send("PUT", "me/player/shuffle", ["state": on ? "true" : "false"])
    }

    /// Spotify's own repeat on its active device (PUT me/player/repeat).
    func setRepeat(_ mode: RepeatMode) async throws {
        try await api.send("PUT", "me/player/repeat", ["state": SpotifyModes(shuffle: false, repeatMode: mode).repeatState])
    }
}
