import Foundation
import MediaPlayer
import os

/// Debug aid until the shared data layer feeds the engine: with
/// `-demoStream http://127.0.0.1:4978` the app reads one playlist from that
/// Subsonic server (`-demoPlaylist`, default `pl-drive`) and plays it.
/// `-demoSeekToEnd YES` jumps to 6 s before the first song's end, to watch
/// the hand-over to the preloaded next song.
@MainActor
enum DemoQueue {
    private static let log = Logger(subsystem: "com.gpo.yoin", category: "player")

    static func startIfRequested(_ engine: PlayerEngine) {
        let defaults = UserDefaults.standard
        guard let base = defaults.string(forKey: "demoStream").flatMap(URL.init(string:)) else { return }
        let playlist = defaults.string(forKey: "demoPlaylist") ?? "pl-drive"
        let seekToEnd = defaults.bool(forKey: "demoSeekToEnd")
        engine.onScrobble = { track, submission in
            log.info("scrobble \(submission ? "submission" : "now playing", privacy: .public): \(track.title, privacy: .public)")
        }
        engine.onEvent = { event in log.info("player event: \(String(describing: event), privacy: .public)") }
        Task {
            do {
                let tracks = try await load(base: base, playlist: playlist)
                log.info("demo queue: \(tracks.count) tracks from \(base.absoluteString, privacy: .public)")
                engine.play(queue: tracks)
                Task {
                    try? await Task.sleep(for: .seconds(4))
                    let info = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
                    let summary = info.keys.sorted().map { k in "\(k)=\(info[k] is MPMediaItemArtwork ? "artwork" : String(describing: info[k]!))" }
                    log.info("now playing info: \(summary.joined(separator: "; "), privacy: .public)")
                }
                if seekToEnd, let first = tracks.first {
                    try? await Task.sleep(for: .seconds(2))
                    engine.seek(to: first.durationSec - 6)
                }
            } catch {
                log.error("demo queue failed: \(error.localizedDescription, privacy: .public)")
            }
        }
    }

    private static func api(_ base: URL, _ endpoint: String, _ query: [String: String]) -> URL {
        var c = URLComponents(url: base.appendingPathComponent("rest/\(endpoint)"), resolvingAgainstBaseURL: false)!
        let auth = ["u": "demo", "p": "demo", "v": "1.16.1", "c": "yoin-ios", "f": "json"]
        c.queryItems = auth.merging(query) { $1 }.sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) }
        return c.url!
    }

    private struct Response: Decodable {
        struct Body: Decodable {
            struct Playlist: Decodable { var entry: [Song] }
            var playlist: Playlist
        }
        struct Song: Decodable {
            var id: String
            var title: String
            var artist: String?
            var album: String?
            var albumId: String?
            var coverArt: String?
            var duration: Double?
        }
        var body: Body
        enum CodingKeys: String, CodingKey { case body = "subsonic-response" }
    }

    private static func load(base: URL, playlist: String) async throws -> [PlayableTrack] {
        let (data, _) = try await URLSession.shared.data(from: api(base, "getPlaylist", ["id": playlist]))
        let songs = try JSONDecoder().decode(Response.self, from: data).body.playlist.entry
        return songs.map { s in
            PlayableTrack(id: s.id, provider: "subsonic", title: s.title, artist: s.artist ?? "",
                          album: s.album ?? "", albumId: s.albumId, durationSec: s.duration ?? 0,
                          streamURL: api(base, "stream", ["id": s.id]),
                          coverURL: s.coverArt.map { api(base, "getCoverArt", ["id": $0, "size": "600"]) })
        }
    }
}
