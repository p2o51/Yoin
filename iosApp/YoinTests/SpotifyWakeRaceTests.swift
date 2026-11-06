import XCTest
@testable import Yoin

/// 51's build 11 log, 23:02: Spotify was woken on the song and played it, but the handback's first read
/// (1 ms after the connection) had no song yet. Yoin sent a play, that failed, and it held at 0:00 with
/// "Playback unavailable" while Spotify played on.
@MainActor
final class SpotifyWakeRaceTests: XCTestCase {
    private let wanted = "spotify:track:381A83PumcLaESIieX4PCu"

    func test_should_wait_then_take_the_song_when_the_first_state_arrives_after_the_connection() {
        // At the connection: no song yet. Waiting, nothing sent.
        XCTAssertEqual(SpotifyAudioOutput.wakeStep(playingURI: nil, paused: true, wanted: wanted, wantsPlay: true,
                                                   final: false), .wait)
        XCTAssertEqual(SpotifyAudioOutput.wakeStep(playingURI: "", paused: true, wanted: wanted, wantsPlay: true,
                                                   final: false), .wait)
        // 50 ms later the state has the song, playing: the start landed, no command goes out.
        XCTAssertEqual(SpotifyAudioOutput.wakeStep(playingURI: wanted, paused: false, wanted: wanted, wantsPlay: true,
                                                   final: false), .finish(.none))
    }

    func test_should_finish_the_start_only_when_no_state_with_the_song_ever_came() {
        XCTAssertEqual(SpotifyAudioOutput.wakeStep(playingURI: "spotify:track:other", paused: false, wanted: wanted,
                                                   wantsPlay: true, final: false), .wait)
        XCTAssertEqual(SpotifyAudioOutput.wakeStep(playingURI: nil, paused: true, wanted: wanted, wantsPlay: true,
                                                   final: true), .finish(.play))
        XCTAssertEqual(SpotifyAudioOutput.wakeStep(playingURI: wanted, paused: true, wanted: wanted, wantsPlay: true,
                                                   final: false), .finish(.resume))
    }

    func test_should_count_a_start_as_landed_when_spotify_plays_the_song_despite_an_error() {
        XCTAssertTrue(SpotifyAudioOutput.startLanded(playingURI: wanted, wanted: wanted))
        XCTAssertFalse(SpotifyAudioOutput.startLanded(playingURI: nil, wanted: wanted))
    }

    func test_should_clear_the_failure_and_its_message_when_the_song_is_seen_playing() {
        XCTAssertTrue(SpotifyAudioOutput.recovers(failed: true, paused: false))
        XCTAssertFalse(SpotifyAudioOutput.recovers(failed: true, paused: true))
        XCTAssertFalse(SpotifyAudioOutput.recovers(failed: false, paused: false))
        let output = FakeAudioOutput()
        let engine = PlayerEngine(output: output)
        engine.play(queue: [PlayableTrack(id: "a", provider: SpotifyMapping.provider, title: "A", artist: "", album: "",
                                          albumId: nil, durationSec: 200,
                                          streamURL: SpotifyAudioOutput.streamURL(trackId: "a"), coverURL: nil)])
        engine.outputDidFail("Open Spotify on this iPhone, then tap Play", network: true)
        XCTAssertNotNil(engine.failure)
        XCTAssertFalse(engine.isPlaying)
        engine.outputDidRecover(playing: true)
        XCTAssertNil(engine.failure)
        XCTAssertTrue(engine.isPlaying)
    }

    func test_should_keep_the_context_start_off_by_default() {
        XCTAssertFalse(SpotifyAudioOutput.contextStartEnabled)
    }
}
