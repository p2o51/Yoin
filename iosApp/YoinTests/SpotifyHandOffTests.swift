import SpotifyiOS
import XCTest
@testable import Yoin

/// C-F1: Spotify carries on through Yoin's queue while iOS suspends Yoin. Another device is handed the
/// queue (≤100) and re-handed only at a song's start; this iPhone plays an album or playlist as its own
/// context while the queue follows it; back in front, the engine catches up with what Spotify plays.
@MainActor
final class SpotifyHandOffTests: XCTestCase {
    private func item(_ id: String) -> SpotifyAudioOutput.Item { SpotifyAudioOutput.Item(SpotifyAudioOutput.streamURL(trackId: id))! }
    private func url(_ id: String) -> URL { SpotifyAudioOutput.streamURL(trackId: id) }

    private func song(_ id: String) -> PlayableTrack {
        PlayableTrack(id: id, provider: SpotifyMapping.provider, title: id, artist: "A", album: "Al", albumId: nil,
                      durationSec: 200, streamURL: url(id), coverURL: nil)
    }

    // MARK: The engine's side

    func test_should_list_upcoming_in_play_order_when_repeat_all_off_or_one() {
        let engine = PlayerEngine(output: FakeAudioOutput())
        engine.play(queue: ["a", "b", "c"].map(song), startAt: 1)
        XCTAssertEqual(engine.outputUpcoming(limit: 4), [url("c"), url("a"), url("b"), url("c")])
        engine.setRepeat(.off)
        XCTAssertEqual(engine.outputUpcoming(limit: 4), [url("c")])
        engine.setRepeat(.one)
        XCTAssertEqual(engine.outputUpcoming(limit: 2), [url("b"), url("b")])
    }

    func test_should_take_the_song_without_loading_when_output_jumps_ahead() {
        let output = FakeAudioOutput()
        let engine = PlayerEngine(output: output)
        engine.play(queue: ["a", "b", "c", "d"].map(song))
        let loads = output.loads
        XCTAssertTrue(engine.outputDidJump(to: url("c")))
        XCTAssertEqual(engine.currentTrack?.id, "c")
        XCTAssertEqual(output.loads, loads)
        XCTAssertEqual(output.preloaded, url("d"))
        XCTAssertFalse(engine.outputDidJump(to: url("zz")))
    }

    // MARK: Another device (Web API)

    func test_should_hand_the_upcoming_list_or_just_the_next() {
        XCTAssertEqual(SpotifyAudioOutput.handOff(next: item("b"), upcoming: [item("b"), item("c")]), [item("b"), item("c")])
        XCTAssertEqual(SpotifyAudioOutput.handOff(next: item("b"), upcoming: []), [item("b")])
        XCTAssertEqual(SpotifyAudioOutput.handOff(next: nil, upcoming: []), [])
    }

    func test_should_rehand_only_at_a_songs_start_when_the_queue_changed() {
        let handed = [item("b"), item("c")]
        XCTAssertEqual(SpotifyAudioOutput.rehandTiming(handed: handed, fresh: handed, elapsedSec: 90, remainingSec: 100), .none)
        let fresh = [item("x"), item("b"), item("c")]
        XCTAssertEqual(SpotifyAudioOutput.rehandTiming(handed: handed, fresh: fresh, elapsedSec: 1, remainingSec: 199), .now)
        XCTAssertEqual(SpotifyAudioOutput.rehandTiming(handed: handed, fresh: fresh, elapsedSec: 90, remainingSec: 110), .atNextSong)
        XCTAssertEqual(SpotifyAudioOutput.rehandTiming(handed: [], fresh: fresh, elapsedSec: 1, remainingSec: 199), .atNextSong)
    }

    func test_should_catch_up_on_the_handed_list_only_when_it_is_still_the_queue() {
        let handed = [item("b"), item("c"), item("d")]
        let snapshot = OutputQueueSnapshot()
        XCTAssertEqual(SpotifyAudioOutput.jumpTarget(uri: "spotify:track:c", handed: handed, upcoming: handed,
                                                     context: nil, snapshot: snapshot), item("c"))
        // The queue changed in front of it: Spotify's list is stale, Yoin's queue wins.
        XCTAssertNil(SpotifyAudioOutput.jumpTarget(uri: "spotify:track:c", handed: handed, upcoming: [item("x"), item("c")],
                                                   context: nil, snapshot: snapshot))
        XCTAssertNil(SpotifyAudioOutput.jumpTarget(uri: "spotify:track:z", handed: handed, upcoming: handed,
                                                   context: nil, snapshot: snapshot))
    }

    // MARK: This iPhone (an album or playlist as Spotify's context)

    private let plan = SpotifyContextPlan(uri: "spotify:album:al", trackIds: ["a", "b", "c"], indices: [0, 1, 2])

    func test_should_follow_the_context_when_the_queue_is_it() {
        let snapshot = OutputQueueSnapshot(urls: [url("a"), url("b"), url("c")])
        XCTAssertTrue(plan.follows(snapshot))
        XCTAssertEqual(plan.contextIndex(of: item("b"), following: snapshot), 1)
        // Shuffled: the same songs in any order (Spotify picks it).
        XCTAssertTrue(plan.follows(OutputQueueSnapshot(urls: [url("c"), url("a"), url("b")], shuffle: true)))
    }

    func test_should_leave_the_context_when_the_queue_deviates() {
        // A song queued by the listener, an order changed, a song removed.
        XCTAssertFalse(plan.follows(OutputQueueSnapshot(urls: [url("a"), url("x"), url("b"), url("c")], userQueued: true)))
        XCTAssertFalse(plan.follows(OutputQueueSnapshot(urls: [url("a"), url("c"), url("b")])))
        XCTAssertFalse(plan.follows(OutputQueueSnapshot(urls: [url("a"), url("b")])))
        XCTAssertNil(plan.contextIndex(of: item("b"), following: OutputQueueSnapshot(urls: [url("b"), url("a")])))
        XCTAssertNil(SpotifyAudioOutput.jumpTarget(uri: "spotify:track:c", handed: [], upcoming: [], context: plan,
                                                   snapshot: OutputQueueSnapshot(urls: [url("a"), url("c"), url("b")])))
    }

    func test_should_catch_up_in_the_context_when_spotify_ran_ahead() {
        let snapshot = OutputQueueSnapshot(urls: [url("a"), url("b"), url("c")])
        XCTAssertEqual(SpotifyAudioOutput.jumpTarget(uri: "spotify:track:c", handed: [], upcoming: [], context: plan,
                                                     snapshot: snapshot), item("c"))
        XCTAssertNil(SpotifyAudioOutput.jumpTarget(uri: "spotify:track:other", handed: [], upcoming: [], context: plan,
                                                   snapshot: snapshot))
    }

    func test_should_place_songs_in_their_context_when_a_playlist_has_gaps() {
        // A playlist whose item 1 isn't a song: positions skip it; an unplayable entry is left out of the queue.
        let p = AppModel.spotifyContext(uri: "spotify:playlist:p", contextIds: ["a", "b", "c"], indices: [0, 2, 3],
                                        queueIds: ["a", "c"])
        XCTAssertEqual(p, SpotifyContextPlan(uri: "spotify:playlist:p", trackIds: ["a", "c"], indices: [0, 3]))
        XCTAssertNil(AppModel.spotifyContext(uri: "u", contextIds: ["a", "b"], indices: [0, 1], queueIds: ["a", "z"]))
        XCTAssertNil(AppModel.spotifyContext(uri: "u", contextIds: ["a", "b"], indices: [0], queueIds: ["a"]))
    }

    func test_should_map_yoins_repeat_onto_spotifys() {
        XCTAssertEqual(SpotifyAudioOutput.spotifyRepeat(.all), .context)
        XCTAssertEqual(SpotifyAudioOutput.spotifyRepeat(.one), .track)
        XCTAssertEqual(SpotifyAudioOutput.spotifyRepeat(.off), .off)
        XCTAssertEqual(SpotifyAudioOutput.repeatMode(SPTAppRemotePlaybackOptionsRepeatMode.context.rawValue), .all)
    }
}
