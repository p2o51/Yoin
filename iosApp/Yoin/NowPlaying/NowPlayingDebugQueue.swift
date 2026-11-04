#if DEBUG
import Foundation
import YoinShared

/// Debug aid until the sign-in UI lands: signs in to a Subsonic server named
/// on the command line and plays one of its playlists through the shared
/// client, so Now Playing runs on real lyrics, stars and ratings.
///
///     -npServer http://127.0.0.1:4980 -npUser demo -npPassword demo
///     -npPlaylist pl-drive -npStart 0 -npSeek 40
///
/// Nothing is compiled into release builds; the credentials come from the
/// launch arguments only (ServerSession keeps them in the Keychain).
@MainActor
enum NowPlayingDebugQueue {
    private static var started = false

    static func startIfRequested(_ model: AppModel) async {
        let d = UserDefaults.standard
        guard !started, let server = d.string(forKey: "npServer") else { return }
        started = true
        let signedIn = await model.session.signIn(baseUrl: server, username: d.string(forKey: "npUser") ?? "demo",
                                                  password: d.string(forKey: "npPassword") ?? "demo")
        guard signedIn, let client = model.session.client,
              let playlist = try? await client.getPlaylist(id: d.string(forKey: "npPlaylist") ?? "pl-drive")
        else { return }
        let tracks = playlist.entries.compactMap { playable($0, client) }
        guard !tracks.isEmpty else { return }
        model.player.play(queue: tracks, startAt: min(max(d.integer(forKey: "npStart"), 0), tracks.count - 1))
        let seek = d.double(forKey: "npSeek")
        if seek > 0 {
            try? await Task.sleep(for: .seconds(1.5))
            model.player.seek(to: seek)
        }
        guard let t = model.player.currentTrack else { return }
        let data = NowPlayingData.shared
        // -npNotes "12:Bass walks up|48:Everyone shouts": notes at seconds, through the store.
        for entry in (d.string(forKey: "npNotes") ?? "").split(separator: "|") {
            let parts = entry.split(separator: ":", maxSplits: 1)
            guard parts.count == 2, let sec = Int64(parts[0]) else { continue }
            NotesStore.shared.add(provider: t.provider, trackId: t.id, content: String(parts[1]),
                                  positionMs: sec * 1000, title: t.title, artist: t.artist)
        }
        // -npRating 8.4: a rating drag released at that value.
        if d.object(forKey: "npRating") != nil {
            data.dragRating(d.double(forKey: "npRating"), t)
            data.commitRating(t, client: client)
        }
        // -npHeart YES: one tap on the heart, once the star state is known.
        if d.bool(forKey: "npHeart") {
            await data.prepare(t, client: client)
            data.toggleFavorite(t, client: client)
        }
    }

    /// View-side aid: `-npCompose "words"` opens the composer (as the Write
    /// pill does) holding those words. Tabs and stages: NowPlayingStageLaunch.
    static var compose: String? { UserDefaults.standard.string(forKey: "npCompose") }

    /// `-npEditNote YES`: opens the composer on the song's first note (the
    /// long-press menu's Edit), with `-npEditWords "…"` typed over it.
    static var editNote: Bool { UserDefaults.standard.bool(forKey: "npEditNote") }
    static var editWords: String? { UserDefaults.standard.string(forKey: "npEditWords") }
    static var editSave: Bool { UserDefaults.standard.bool(forKey: "npEditSave") }

    /// A queue entry from the shared model: stream and cover URLs from the
    /// client, the star and rating as the server sent them.
    static func playable(_ s: SongInfo, _ client: SubsonicClient) -> PlayableTrack? {
        guard let stream = URL(string: client.streamUrl(id: s.id, maxBitRate: nil, format: nil)) else { return nil }
        return PlayableTrack(id: s.id, provider: s.ref.provider, title: s.title ?? "", artist: s.artist ?? "",
                             album: s.album ?? "", albumId: s.albumId,
                             durationSec: Double(s.durationSec?.int32Value ?? 0), streamURL: stream,
                             coverURL: s.coverArtId.flatMap { URL(string: client.coverArtUrl(id: $0, size: KotlinInt(int: 600))) },
                             starred: s.starred, userRating: s.userRating.map { Int($0.int32Value) },
                             sourceKbps: s.bitRate.map { Int($0.int32Value) })
    }
}
#endif
