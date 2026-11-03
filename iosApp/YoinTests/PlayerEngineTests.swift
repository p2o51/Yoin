import XCTest
@testable import Yoin

/// Records what the engine asks of its output; tests fire the output's events by hand.
@MainActor
final class FakeAudioOutput: AudioOutput {
    weak var events: AudioOutputEvents?
    var loaded: URL?
    var preloaded: URL?
    var startSec: Double = 0
    var playing = false
    var seeks: [Double] = []
    var loads = 0
    var currentTimeSec: Double = 0

    func load(_ current: URL, next: URL?, startSec: Double) {
        loaded = current
        preloaded = next
        self.startSec = startSec
        currentTimeSec = startSec
        loads += 1
    }
    func preload(_ next: URL?) { preloaded = next }
    func play() { playing = true }
    func pause() { playing = false }
    func seek(to sec: Double) { seeks.append(sec); currentTimeSec = sec }
    func stop() { loaded = nil; preloaded = nil; playing = false }

    /// The current item ended: hand over to the preloaded one, or run out.
    func finishItem() {
        if let preloaded {
            loaded = preloaded
            self.preloaded = nil
            currentTimeSec = 0
            events?.outputDidAdvanceToPreloaded()
        } else {
            loaded = nil
            events?.outputDidReachEnd()
        }
    }

    func tick(to sec: Double) {
        currentTimeSec = sec
        events?.outputTimeDidChange(sec)
    }
}

@MainActor
final class PlayerEngineTests: XCTestCase {
    private var output: FakeAudioOutput!
    private var engine: PlayerEngine!

    override func setUp() async throws {
        output = FakeAudioOutput()
        // Deterministic "shuffle": reverse.
        engine = PlayerEngine(output: output, shuffler: { $0.reversed() })
    }

    private func track(_ n: Int, duration: Double = 200) -> PlayableTrack {
        PlayableTrack(id: "t\(n)", provider: "subsonic", title: "Track \(n)", artist: "A", album: "Al",
                      albumId: "al", durationSec: duration,
                      streamURL: URL(string: "http://x/stream?id=t\(n)")!, coverURL: nil)
    }

    private func tracks(_ n: Int) -> [PlayableTrack] { (1...n).map { track($0) } }
    private var ids: [String] { engine.queue.map(\.track.id) }

    // MARK: Start & transport

    func test_should_load_start_and_preload_next_when_playing_a_queue() {
        engine.play(queue: tracks(3), startAt: 1)
        XCTAssertEqual(engine.currentTrack?.id, "t2")
        XCTAssertTrue(engine.isPlaying)
        XCTAssertTrue(output.playing)
        XCTAssertEqual(output.loaded?.absoluteString, "http://x/stream?id=t2")
        XCTAssertEqual(output.preloaded?.absoluteString, "http://x/stream?id=t3")
    }

    func test_should_toggle_play_pause_when_toggled() {
        engine.play(queue: tracks(2))
        engine.togglePlayPause()
        XCTAssertFalse(engine.isPlaying)
        XCTAssertFalse(output.playing)
        engine.togglePlayPause()
        XCTAssertTrue(engine.isPlaying)
        XCTAssertTrue(output.playing)
    }

    func test_should_advance_to_preloaded_when_item_ends() {
        engine.play(queue: tracks(3))
        output.finishItem()
        XCTAssertEqual(engine.currentIndex, 1)
        XCTAssertEqual(output.preloaded?.absoluteString, "http://x/stream?id=t3")
    }

    // MARK: Previous

    func test_should_restart_song_when_previous_after_three_seconds() {
        engine.play(queue: tracks(3), startAt: 1)
        output.tick(to: 3.5)
        engine.previous()
        XCTAssertEqual(engine.currentIndex, 1)
        XCTAssertEqual(output.seeks.last, 0)
        XCTAssertEqual(engine.progress.positionSec, 0)
    }

    func test_should_go_back_a_song_when_previous_within_three_seconds() {
        engine.play(queue: tracks(3), startAt: 1)
        output.tick(to: 2.5)
        engine.previous()
        XCTAssertEqual(engine.currentIndex, 0)
        XCTAssertEqual(output.loads, 2)
    }

    func test_should_wrap_to_last_when_previous_at_first_under_repeat_all() {
        engine.play(queue: tracks(3))
        engine.previous()
        XCTAssertEqual(engine.currentIndex, 2)
    }

    func test_should_restart_first_when_previous_at_first_under_repeat_off() {
        engine.setRepeat(.off)
        engine.play(queue: tracks(3))
        engine.previous()
        XCTAssertEqual(engine.currentIndex, 0)
        XCTAssertEqual(output.seeks.last, 0)
    }

    // MARK: Repeat

    func test_should_wrap_to_first_when_next_at_end_under_repeat_all() {
        engine.play(queue: tracks(2), startAt: 1)
        XCTAssertEqual(output.preloaded?.absoluteString, "http://x/stream?id=t1")
        engine.next()
        XCTAssertEqual(engine.currentIndex, 0)
    }

    func test_should_preload_same_song_when_repeat_one() {
        engine.play(queue: tracks(3))
        engine.setRepeat(.one)
        XCTAssertEqual(output.preloaded?.absoluteString, "http://x/stream?id=t1")
        output.finishItem()
        XCTAssertEqual(engine.currentIndex, 0)
        XCTAssertTrue(engine.isPlaying)
    }

    func test_should_move_on_when_next_under_repeat_one() {
        engine.setRepeat(.one)
        engine.play(queue: tracks(3))
        engine.next()
        XCTAssertEqual(engine.currentIndex, 1)
    }

    func test_should_stop_at_end_when_repeat_off() {
        var events: [PlayerEvent] = []
        engine.onEvent = { events.append($0) }
        engine.setRepeat(.off)
        engine.play(queue: tracks(2), startAt: 1)
        XCTAssertNil(output.preloaded)
        output.finishItem()
        XCTAssertFalse(engine.isPlaying)
        XCTAssertEqual(engine.currentIndex, 1)
        XCTAssertEqual(events, [.queueEnded])
        engine.next()
        XCTAssertEqual(engine.currentIndex, 1, "next at the end of a repeat-off queue does nothing")
        engine.togglePlayPause()
        XCTAssertTrue(engine.isPlaying)
        XCTAssertEqual(output.startSec, 0, "play after the end starts the last song over")
    }

    // MARK: Shuffle

    func test_should_keep_current_first_when_shuffle_turns_on() {
        engine.play(queue: tracks(4), startAt: 1)
        engine.setShuffle(true)
        XCTAssertEqual(ids, ["t2", "t4", "t3", "t1"])
        XCTAssertEqual(engine.currentIndex, 0)
        XCTAssertEqual(output.loads, 1, "shuffling must not restart the song")
        XCTAssertEqual(output.preloaded?.absoluteString, "http://x/stream?id=t4")
    }

    func test_should_restore_original_order_when_shuffle_turns_off() {
        engine.play(queue: tracks(4), startAt: 1)
        engine.setShuffle(true)
        engine.next() // t4
        engine.setShuffle(false)
        XCTAssertEqual(ids, ["t1", "t2", "t3", "t4"])
        XCTAssertEqual(engine.currentIndex, 3)
        XCTAssertEqual(engine.currentTrack?.id, "t4")
    }

    func test_should_start_with_chosen_song_when_playing_with_shuffle_on() {
        engine.setShuffle(true)
        engine.play(queue: tracks(4), startAt: 2)
        XCTAssertEqual(ids, ["t3", "t4", "t2", "t1"])
        engine.setShuffle(false)
        XCTAssertEqual(ids, ["t1", "t2", "t3", "t4"])
        XCTAssertEqual(engine.currentIndex, 2)
    }

    func test_should_keep_queued_songs_when_shuffle_turns_off() {
        engine.play(queue: tracks(3))
        engine.setShuffle(true) // t1 t3 t2
        engine.playNext(track(9))
        engine.addToQueue(track(8))
        XCTAssertEqual(ids, ["t1", "t9", "t8", "t3", "t2"])
        engine.setShuffle(false)
        XCTAssertEqual(ids, ["t1", "t9", "t8", "t2", "t3"])
    }

    // MARK: Queue edits

    func test_should_stack_play_next_after_current() {
        engine.play(queue: tracks(3))
        engine.playNext(track(8))
        engine.playNext(track(9))
        XCTAssertEqual(ids, ["t1", "t9", "t8", "t2", "t3"])
        XCTAssertEqual(output.preloaded?.absoluteString, "http://x/stream?id=t9")
    }

    func test_should_add_to_queue_after_user_queued_songs() {
        engine.play(queue: tracks(3))
        engine.addToQueue(track(7))
        engine.addToQueue(track(8))
        engine.playNext(track(9))
        XCTAssertEqual(ids, ["t1", "t9", "t7", "t8", "t2", "t3"])
    }

    func test_should_load_without_playing_when_queueing_into_empty_player() {
        engine.addToQueue(track(5))
        XCTAssertEqual(engine.currentTrack?.id, "t5")
        XCTAssertFalse(engine.isPlaying)
        XCTAssertEqual(output.loaded?.absoluteString, "http://x/stream?id=t5")
    }

    func test_should_keep_current_and_repreload_when_moving() {
        engine.play(queue: tracks(4), startAt: 1)
        engine.move(from: 3, to: 2)
        XCTAssertEqual(ids, ["t1", "t2", "t4", "t3"])
        XCTAssertEqual(engine.currentIndex, 1)
        XCTAssertEqual(output.preloaded?.absoluteString, "http://x/stream?id=t4")
        engine.move(from: 0, to: 3)
        XCTAssertEqual(ids, ["t2", "t4", "t3", "t1"])
        XCTAssertEqual(engine.currentIndex, 0)
    }

    func test_should_move_with_list_offsets() {
        engine.play(queue: tracks(4))
        engine.move(fromOffsets: IndexSet(integer: 3), toOffset: 1)
        XCTAssertEqual(ids, ["t1", "t4", "t2", "t3"])
        XCTAssertEqual(engine.currentIndex, 0)
    }

    func test_should_never_remove_current_song() {
        engine.play(queue: tracks(3), startAt: 1)
        engine.remove(at: 1)
        XCTAssertEqual(ids, ["t1", "t2", "t3"])
        engine.remove(at: 0)
        XCTAssertEqual(ids, ["t2", "t3"])
        XCTAssertEqual(engine.currentIndex, 0)
        XCTAssertEqual(engine.currentTrack?.id, "t2")
        engine.remove(at: 1)
        XCTAssertEqual(ids, ["t2"])
        // Repeat all on a one-song queue: the song itself plays next.
        XCTAssertEqual(output.preloaded?.absoluteString, "http://x/stream?id=t2")
    }

    func test_should_play_entry_when_jumping() {
        engine.play(queue: tracks(4))
        engine.pause()
        engine.jump(to: 3)
        XCTAssertEqual(engine.currentTrack?.id, "t4")
        XCTAssertTrue(engine.isPlaying)
    }

    // MARK: Errors

    func test_should_skip_with_event_when_item_fails() {
        var events: [PlayerEvent] = []
        engine.onEvent = { events.append($0) }
        engine.play(queue: tracks(3))
        engine.outputDidFail("404")
        XCTAssertEqual(engine.currentIndex, 1)
        XCTAssertTrue(engine.isPlaying)
        XCTAssertEqual(events.count, 1)
    }

    func test_should_stop_when_every_song_fails() {
        var events: [PlayerEvent] = []
        engine.onEvent = { events.append($0) }
        engine.play(queue: tracks(2))
        engine.outputDidFail("x")
        engine.outputDidFail("x")
        XCTAssertFalse(engine.isPlaying)
        XCTAssertEqual(events.last, .queueFailed)
    }

    // MARK: Scrobble & progress

    func test_should_announce_then_submit_at_half_when_listening() {
        var calls: [(String, Bool)] = []
        engine.onScrobble = { calls.append(($0.id, $1)) }
        engine.play(queue: [track(1, duration: 100)])
        XCTAssertEqual(calls.map(\.1), [false])
        var t = 0.0
        while t < 49 { t += 0.25; output.tick(to: t) }
        XCTAssertEqual(calls.count, 1)
        output.tick(to: 50.25)
        XCTAssertEqual(calls.map(\.1), [false, true])
        while t < 99 { t += 0.25; output.tick(to: t) }
        XCTAssertEqual(calls.count, 2, "one submission per play")
    }

    func test_should_submit_at_four_minutes_when_song_is_long() {
        var submitted = false
        engine.onScrobble = { if $1 { submitted = true } }
        engine.play(queue: [track(1, duration: 600)])
        var t = 0.0
        while t < 239.5 { t += 0.25; output.tick(to: t) }
        XCTAssertFalse(submitted)
        output.tick(to: 240.25)
        XCTAssertTrue(submitted)
    }

    func test_should_not_count_seeks_as_listening() {
        var submitted = false
        engine.onScrobble = { if $1 { submitted = true } }
        engine.play(queue: [track(1, duration: 100)])
        engine.seek(to: 90)
        output.tick(to: 90.25)
        output.tick(to: 95)
        XCTAssertFalse(submitted)
    }

    func test_should_not_submit_short_songs() {
        var calls = 0
        engine.onScrobble = { _, _ in calls += 1 }
        engine.play(queue: [track(1, duration: 20)])
        var t = 0.0
        while t < 19 { t += 0.25; output.tick(to: t) }
        XCTAssertEqual(calls, 1)
    }

    func test_should_tick_progress_without_touching_engine_state() {
        engine.play(queue: tracks(2))
        final class Flag: @unchecked Sendable { var set = false }
        let engineChanged = Flag()
        withObservationTracking {
            _ = engine.isPlaying
            _ = engine.currentTrack
            _ = engine.queue
        } onChange: {
            engineChanged.set = true
        }
        output.tick(to: 1)
        output.tick(to: 1.25)
        XCTAssertEqual(engine.progress.positionSec, 1.25)
        XCTAssertFalse(engineChanged.set)
    }

    func test_should_announce_each_repeat_one_play() {
        var announces = 0
        engine.onScrobble = { if !$1 { announces += 1 } }
        engine.setRepeat(.one)
        engine.play(queue: tracks(2))
        output.finishItem()
        XCTAssertEqual(announces, 2)
    }
}

@MainActor
final class PlayModeCycleTests: XCTestCase {
    func test_should_cycle_android_play_modes_when_round_button_tapped() {
        let engine = PlayerEngine(output: FakeAudioOutput())
        var seen: [String] = []
        for _ in 0..<4 {
            engine.cyclePlayMode()
            seen.append("\(engine.shuffle ? "S" : "-")\(engine.repeatMode.rawValue)")
        }
        XCTAssertEqual(seen, ["Sall", "Sone", "-one", "-all"])
        engine.setShuffle(true)
        engine.setRepeat(.off)
        engine.cyclePlayMode()
        XCTAssertTrue(engine.shuffle)
        XCTAssertEqual(engine.repeatMode, .all)
    }
}
