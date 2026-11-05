import AVFoundation
import MusicKit
import XCTest
import YoinShared
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

    func test_should_applyModes_when_thePlayerChangesThemExternally() {
        engine.play(queue: tracks(3), startAt: 0)
        output.events?.outputPlayModesDidChangeExternally(shuffle: true, repeatMode: .one)
        XCTAssertTrue(engine.shuffle)
        XCTAssertEqual(engine.repeatMode, .one)
        // Repeat one: the preloaded next is the current song again.
        XCTAssertEqual(output.preloaded?.absoluteString, "http://x/stream?id=t1")
    }

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

    /// Codex A39: a network / server failure holds on the song (no march through the queue), keeps the
    /// error, and Retry starts it again where it was.
    func test_should_holdOnTheSong_when_theNetworkFails_and_retry() {
        var events: [PlayerEvent] = []
        engine.onEvent = { events.append($0) }
        engine.play(queue: tracks(3))
        output.tick(to: 42)
        engine.outputDidFail("offline", network: true)
        XCTAssertEqual(engine.currentTrack?.id, "t1")
        XCTAssertFalse(engine.isPlaying)
        XCTAssertEqual(engine.failure?.track.id, "t1")
        XCTAssertEqual(engine.failure?.network, true)
        XCTAssertEqual(events.count, 1)
        let loads = output.loads
        engine.togglePlayPause()
        XCTAssertNil(engine.failure)
        XCTAssertEqual(output.loads, loads + 1)
        XCTAssertEqual(output.startSec, 42, accuracy: 0.001)
        XCTAssertTrue(engine.isPlaying)
        XCTAssertEqual(engine.currentTrack?.id, "t1")
    }

    func test_should_stop_when_every_song_fails() {
        var events: [PlayerEvent] = []
        engine.onEvent = { events.append($0) }
        engine.play(queue: tracks(2))
        engine.outputDidFail("x")
        engine.outputDidFail("x")
        XCTAssertFalse(engine.isPlaying)
        XCTAssertEqual(events.last, .queueFailed)
        XCTAssertNotNil(engine.failure, "a failed queue keeps its error")
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

// MARK: - Stream quality

@MainActor
final class StreamQualityTests: XCTestCase {
    private var output: FakeAudioOutput!
    private var engine: PlayerEngine!
    private var quality: StreamQuality = .kbps128
    private var timeOffset = true

    override func setUp() async throws {
        output = FakeAudioOutput()
        engine = PlayerEngine(output: output)
        quality = .kbps128
        timeOffset = true
        engine.streamQuality = { [unowned self] in self.quality }
        engine.timeOffsetSupported = { [unowned self] in self.timeOffset }
    }

    private let base = "https://music.example/rest/stream?u=me&t=tok&s=salt&v=1.16.1&c=Yoin&f=json"

    private func track(_ n: Int, kbps: Int? = 320, duration: Double = 200) -> PlayableTrack {
        PlayableTrack(id: "t\(n)", provider: "subsonic", title: "T\(n)", artist: "A", album: "Al", albumId: nil,
                      durationSec: duration, streamURL: URL(string: "\(base)&id=t\(n)")!, coverURL: nil,
                      sourceKbps: kbps)
    }

    private func query(_ url: URL?) -> [String: String] {
        let items = url.flatMap { URLComponents(url: $0, resolvingAgainstBaseURL: false)?.queryItems } ?? []
        return Dictionary(items.map { ($0.name, $0.value ?? "") }, uniquingKeysWith: { _, b in b })
    }

    // MARK: URLs

    func test_should_add_mp3_transcode_params_when_capped() {
        let url = SubsonicStreamURLs.url(URL(string: "\(base)&id=9")!,
                                         delivery: StreamDelivery(maxBitRateKbps: 128, seeksByTime: true))
        let q = query(url)
        XCTAssertEqual(q["maxBitRate"], "128")
        XCTAssertEqual(q["format"], "mp3")
        XCTAssertEqual(q["estimateContentLength"], "true")
        XCTAssertNil(q["timeOffset"])
        XCTAssertEqual(q["t"], "tok")
        XCTAssertEqual(q["id"], "9")
    }

    func test_should_add_time_offset_and_replace_old_params_when_reopening() {
        let first = SubsonicStreamURLs.url(URL(string: "\(base)&id=9")!,
                                           delivery: StreamDelivery(maxBitRateKbps: 192, seeksByTime: true),
                                           timeOffsetSec: 30)
        let again = SubsonicStreamURLs.url(first, delivery: StreamDelivery(maxBitRateKbps: 192, seeksByTime: true),
                                           timeOffsetSec: 75)
        XCTAssertEqual(query(again)["timeOffset"], "75")
        XCTAssertEqual(again.absoluteString.components(separatedBy: "maxBitRate=").count, 2)
    }

    func test_should_strip_transcode_params_when_original() {
        let capped = URL(string: "\(base)&id=9&maxBitRate=128&format=mp3&timeOffset=4&estimateContentLength=true")!
        let q = query(SubsonicStreamURLs.url(capped, delivery: .original))
        XCTAssertNil(q["maxBitRate"]); XCTAssertNil(q["format"]); XCTAssertNil(q["timeOffset"])
        XCTAssertNil(q["estimateContentLength"])
        XCTAssertEqual(q["id"], "9")
    }

    func test_should_leave_non_stream_urls_alone() {
        let apple = URL(string: "applemusic://song/123")!
        XCTAssertEqual(SubsonicStreamURLs.url(apple, delivery: StreamDelivery(maxBitRateKbps: 128, seeksByTime: true)),
                       apple)
        XCTAssertEqual(StreamDelivery.plan(.kbps128, track: PlayableTrack(
            id: "1", provider: "applemusic", title: "", artist: "", album: "", albumId: nil, durationSec: 1,
            streamURL: apple, coverURL: nil), timeOffset: true), .original)
    }

    func test_should_plan_original_when_file_is_under_the_cap() {
        XCTAssertEqual(StreamDelivery.plan(.kbps192, track: track(1, kbps: 128), timeOffset: true), .original)
        XCTAssertEqual(StreamDelivery.plan(.original, track: track(1, kbps: 1411), timeOffset: true), .original)
        XCTAssertEqual(StreamDelivery.plan(.kbps192, track: track(1, kbps: 1411), timeOffset: true),
                       StreamDelivery(maxBitRateKbps: 192, seeksByTime: true))
        // Unknown source rate: transcode, but a byte isn't known to be a time.
        XCTAssertEqual(StreamDelivery.plan(.kbps128, track: track(1, kbps: nil), timeOffset: true),
                       StreamDelivery(maxBitRateKbps: 128, seeksByTime: false))
        // Codex A38: a server without OpenSubsonic transcodeOffset never gets timeOffset.
        XCTAssertEqual(StreamDelivery.plan(.kbps192, track: track(1, kbps: 1411), timeOffset: false),
                       StreamDelivery(maxBitRateKbps: 192, seeksByTime: false))
    }

    func test_should_seek_in_the_transcode_without_time_offset_when_server_lacks_transcodeOffset() {
        timeOffset = false
        engine.play(queue: [track(1), track(2)])
        let loads = output.loads
        engine.seek(to: 75.6)
        XCTAssertEqual(output.loads, loads, "no reopen")
        XCTAssertEqual(output.seeks, [75.6])
        XCTAssertNil(query(output.loaded)["timeOffset"])
        output.tick(to: 76)
        XCTAssertEqual(engine.progress.positionSec, 76, accuracy: 0.001, "no invented time base")
    }

    // MARK: Rule (shared StreamQualityPolicy, Android StreamQuality.kt; O01)

    func test_should_offer_androids_choices_and_labels() {
        XCTAssertEqual(StreamQuality.companion.all.map(\.label), ["Auto", "Original", "320 kbps", "192 kbps", "128 kbps"])
        XCTAssertEqual(StreamQuality.companion.all.map(\.key), ["auto", "original", "320", "192", "128"])
        XCTAssertEqual(AudioOutputKind.companion.autoDetail, "Wired Original   Bluetooth 320 kbps   Speaker 192 kbps")
    }

    func test_should_map_route_ports_to_androids_output_kinds() {
        for wired in [AVAudioSession.Port.headphones, .usbAudio, .lineOut, .HDMI] {
            XCTAssertEqual(StreamConditions.outputKind([wired]), .wired, wired.rawValue)
        }
        for bluetooth in [AVAudioSession.Port.bluetoothA2DP, .bluetoothLE] {
            XCTAssertEqual(StreamConditions.outputKind([bluetooth]), .bluetooth, bluetooth.rawValue)
        }
        for other in [AVAudioSession.Port.bluetoothHFP, .builtInSpeaker, .builtInReceiver, .airPlay, .carAudio,
                      AVAudioSession.Port(rawValue: "SomethingNew")] {
            XCTAssertEqual(StreamConditions.outputKind([other]), .speaker, other.rawValue)
        }
        XCTAssertEqual(StreamConditions.outputKind([]), .speaker)
        // A wire wins over Bluetooth, Bluetooth over the speaker.
        XCTAssertEqual(StreamConditions.outputKind([.airPlay, .bluetoothA2DP, .headphones]), .wired)
        XCTAssertEqual(StreamConditions.outputKind([.builtInSpeaker, .bluetoothLE]), .bluetooth)
    }

    func test_should_count_only_a_cheap_unconstrained_path_as_unmetered() {
        XCTAssertTrue(StreamConditions.isUnmetered(satisfied: true, expensive: false, constrained: false))
        XCTAssertFalse(StreamConditions.isUnmetered(satisfied: true, expensive: true, constrained: false), "cellular / hotspot")
        XCTAssertFalse(StreamConditions.isUnmetered(satisfied: true, expensive: false, constrained: true), "Low Data Mode")
        XCTAssertFalse(StreamConditions.isUnmetered(satisfied: false, expensive: false, constrained: false), "no network")
    }

    func test_should_resolve_the_defaults_by_network_then_route() {
        let d = StreamQualitySettings.companion.Defaults
        func resolve(_ unmetered: Bool, _ ports: [AVAudioSession.Port]) -> StreamQuality {
            StreamQualityPolicy.shared.resolve(settings: d, unmetered: unmetered, output: StreamConditions.outputKind(ports))
        }
        for ports: [AVAudioSession.Port] in [[.headphones], [.bluetoothA2DP], [.builtInSpeaker], [.airPlay]] {
            XCTAssertEqual(resolve(true, ports), .original, "Wi‑Fi plays the file: \(ports)")
        }
        XCTAssertEqual(resolve(false, [.headphones]), .original)
        XCTAssertEqual(resolve(false, [.usbAudio]), .original)
        XCTAssertEqual(resolve(false, [.bluetoothA2DP]), .kbps320)
        XCTAssertEqual(resolve(false, [.bluetoothLE]), .kbps320)
        XCTAssertEqual(resolve(false, [.builtInSpeaker]), .kbps192)
        XCTAssertEqual(resolve(false, [.airPlay]), .kbps192)
        // A fixed choice ignores the route.
        let fixed = StreamQualitySettings(wifi: .kbps320, mobile: .kbps128)
        XCTAssertEqual(StreamQualityPolicy.shared.resolve(settings: fixed, unmetered: false, output: .wired), .kbps128)
        XCTAssertEqual(StreamQualityPolicy.shared.resolve(settings: fixed, unmetered: true, output: .speaker), .kbps320)
    }

    func test_should_plan_through_the_shared_rule() {
        XCTAssertEqual(StreamDelivery.plan(.auto_, track: track(1, kbps: 1411), timeOffset: true), .original)
        XCTAssertEqual(StreamDelivery.plan(.kbps320, track: track(1, kbps: 0), timeOffset: true),
                       StreamDelivery(maxBitRateKbps: 320, seeksByTime: false), "0 kbps is unknown")
    }

    func test_should_migrate_the_old_single_choice_once() {
        let defaults = UserDefaults(suiteName: "StreamQualityStoreTests")!
        defer { defaults.removePersistentDomain(forName: "StreamQualityStoreTests") }
        func fresh(_ legacy: String?) -> StreamQualitySettings {
            defaults.removePersistentDomain(forName: "StreamQualityStoreTests")
            if let legacy { defaults.set(legacy, forKey: StreamQualityStore.legacyKey) }
            return StreamQualityStore.load(defaults)
        }
        XCTAssertEqual(fresh(nil), StreamQualitySettings(wifi: .original, mobile: .auto_))
        XCTAssertEqual(fresh("automatic"), StreamQualitySettings(wifi: .original, mobile: .auto_))
        XCTAssertNil(defaults.string(forKey: StreamQualityStore.legacyKey))
        XCTAssertEqual(fresh("192"), StreamQualitySettings(wifi: .kbps192, mobile: .kbps192))
        XCTAssertNil(defaults.string(forKey: StreamQualityStore.legacyKey))
        XCTAssertEqual(defaults.string(forKey: StreamQualityStore.wifiKey), "192")
        XCTAssertEqual(fresh("original"), StreamQualitySettings(wifi: .original, mobile: .original))

        StreamQualityStore.setMobile(.kbps128, defaults)
        XCTAssertEqual(StreamQualityStore.load(defaults), StreamQualitySettings(wifi: .original, mobile: .kbps128))
        StreamQualityStore.setWifi(.auto_, defaults)
        XCTAssertEqual(defaults.string(forKey: StreamQualityStore.wifiKey), "auto")
        XCTAssertEqual(defaults.string(forKey: StreamQualityStore.mobileKey), "128")
    }

    // MARK: Engine

    func test_should_open_and_preload_transcodes_when_capped() {
        engine.play(queue: [track(1), track(2)])
        XCTAssertEqual(query(output.loaded)["maxBitRate"], "128")
        XCTAssertEqual(query(output.preloaded)["maxBitRate"], "128")
    }

    func test_should_reopen_with_time_offset_and_offset_position_when_seeking_a_transcode() {
        engine.play(queue: [track(1), track(2)])
        let loads = output.loads
        engine.seek(to: 75.6)
        XCTAssertEqual(output.loads, loads + 1)
        XCTAssertTrue(output.seeks.isEmpty)
        XCTAssertEqual(query(output.loaded)["timeOffset"], "75")
        XCTAssertEqual(output.startSec, 0)
        XCTAssertTrue(output.playing)
        XCTAssertEqual(query(output.preloaded)["id"], "t2")
        XCTAssertEqual(engine.progress.positionSec, 75)

        output.tick(to: 10)
        XCTAssertEqual(engine.progress.positionSec, 85, accuracy: 0.001)
        // The transcode's estimated length (what's left) never replaces the song's.
        output.events?.outputDurationDidChange(125)
        XCTAssertEqual(engine.progress.durationSec, 200)

        output.currentTimeSec = 12
        engine.pause()
        XCTAssertEqual(engine.progress.positionSec, 87, accuracy: 0.001)
        XCTAssertFalse(output.playing)

        engine.seek(to: 0)
        XCTAssertNil(query(output.loaded)["timeOffset"])
        XCTAssertFalse(output.playing)
        output.tick(to: 1)
        XCTAssertEqual(engine.progress.positionSec, 1, accuracy: 0.001)
    }

    func test_should_count_from_zero_again_when_the_next_song_takes_over() {
        engine.play(queue: [track(1), track(2)])
        engine.seek(to: 150)
        output.finishItem()
        XCTAssertEqual(engine.currentTrack?.id, "t2")
        output.tick(to: 3)
        XCTAssertEqual(engine.progress.positionSec, 3, accuracy: 0.001)
    }

    func test_should_seek_the_output_when_playing_the_original() {
        quality = .original
        engine.play(queue: [track(1)])
        XCTAssertNil(query(output.loaded)["maxBitRate"])
        engine.seek(to: 40)
        XCTAssertEqual(output.seeks, [40])
        output.events?.outputDurationDidChange(201)
        XCTAssertEqual(engine.progress.durationSec, 201)
    }

    func test_should_keep_the_current_stream_and_redo_the_next_when_quality_changes() {
        engine.play(queue: [track(1), track(2)])
        quality = .kbps320
        engine.streamQualityDidChange()
        XCTAssertEqual(query(output.preloaded)["maxBitRate"], nil, "320 kbps source under a 320 cap plays as is")
        engine.seek(to: 20)
        XCTAssertEqual(query(output.loaded)["maxBitRate"], "128", "the current song keeps its stream")
        quality = .kbps192
        engine.streamQualityDidChange()
        XCTAssertEqual(query(output.preloaded)["maxBitRate"], "192")
    }
}

/// Now Playing's background edges (shared rule with Android): previous / next
/// in play order, repeat-all wraps, otherwise the playing cover's own scheme.
final class NowPlayingNeighboursTests: XCTestCase {
    typealias N = NowPlayingNeighbours

    func test_should_pickItemsAroundCurrent_when_inTheMiddle() {
        let e = N.edges(count: 5, current: 2, repeatAll: false)
        XCTAssertEqual(e.top, .item(1))
        XCTAssertEqual(e.bottom, .item(3))
    }

    func test_should_fallBackToOwnScheme_when_atTheEndsWithoutRepeatAll() {
        let first = N.edges(count: 3, current: 0, repeatAll: false)
        XCTAssertEqual(first.top, .own)
        XCTAssertEqual(first.bottom, .item(1))
        let last = N.edges(count: 3, current: 2, repeatAll: false)
        XCTAssertEqual(last.top, .item(1))
        XCTAssertEqual(last.bottom, .own)
    }

    func test_should_wrapAround_when_repeatAll() {
        let first = N.edges(count: 3, current: 0, repeatAll: true)
        XCTAssertEqual(first.top, .item(2))
        XCTAssertEqual(first.bottom, .item(1))
        let last = N.edges(count: 3, current: 2, repeatAll: true)
        XCTAssertEqual(last.top, .item(1))
        XCTAssertEqual(last.bottom, .item(0))
    }

    func test_should_beItsOwnNeighbour_when_aSingleSongRepeatsAll() {
        let e = N.edges(count: 1, current: 0, repeatAll: true)
        XCTAssertEqual(e.top, .item(0))
        XCTAssertEqual(e.bottom, .item(0))
    }

    func test_should_useOwnScheme_when_noQueueOrNoCurrent() {
        XCTAssertEqual(N.edges(count: 0, current: nil, repeatAll: true).top, .own)
        XCTAssertEqual(N.edges(count: 4, current: nil, repeatAll: true).bottom, .own)
        XCTAssertEqual(N.edges(count: 4, current: 9, repeatAll: false).top, .own)
    }

    func test_should_followThePlayOrderGiven_when_shuffled() {
        // The engine's queue is already in play order (shuffled when shuffle
        // is on), so the rule is index-based on that order.
        let e = N.edges(count: 4, current: 3, repeatAll: false)
        XCTAssertEqual(e.top, .item(2))
        XCTAssertEqual(e.bottom, .own)
    }
}

/// Codex C05 / C09: pure rules behind playlist removal and Apple Music's end detection.
@MainActor
final class G3RulesTests: XCTestCase {
    private func entry(_ n: Int, song: String) -> PlaylistEntry {
        let album = Album(id: MediaId(provider: "subsonic", rawId: "al"), title: "A", artist: "B", year: 2020,
                          palette: CoverPalette(a: .red, b: .green, c: .blue), tracks: [])
        return PlaylistEntry(album: album, track: Track(id: MediaId(provider: "subsonic", rawId: song), number: n,
                                                        title: song, durationSec: 100))
    }

    func test_should_findTheRemovedSongWhereTheServerHasIt_when_anEarlierRemovalFailed() {
        let a = entry(1, song: "A"), b = entry(2, song: "B"), c = entry(3, song: "C")
        // Tapped: remove A (UI index 0), then B (UI index 0 now). A's removal failed: the server still has A.
        XCTAssertEqual(PlaylistActions.serverIndex(of: b, in: [a, b, c], near: 0), 1)
        // A's removal went through: B is first on the server.
        XCTAssertEqual(PlaylistActions.serverIndex(of: b, in: [b, c], near: 0), 0)
        XCTAssertNil(PlaylistActions.serverIndex(of: b, in: [a, c], near: 0))
    }

    func test_should_treatAPauseNearTheEndAsAPause_and_aRunOutAsTheEnd() {
        // The listener paused 1 s before the end: the playhead is where it was playing.
        XCTAssertFalse(AppleMusicAudioOutput.isNaturalEnd(status: .paused, hasEntry: true, time: 199,
                                                          lastPlayingTime: 199, duration: 200))
        XCTAssertFalse(AppleMusicAudioOutput.isNaturalEnd(status: .interrupted, hasEntry: true, time: 199.9,
                                                          lastPlayingTime: 199.9, duration: 200))
        // Ran out: paused at the end, or back at the start.
        XCTAssertTrue(AppleMusicAudioOutput.isNaturalEnd(status: .paused, hasEntry: true, time: 199.9,
                                                         lastPlayingTime: 199.2, duration: 200))
        XCTAssertTrue(AppleMusicAudioOutput.isNaturalEnd(status: .paused, hasEntry: true, time: 0,
                                                         lastPlayingTime: 199.2, duration: 200))
        XCTAssertTrue(AppleMusicAudioOutput.isNaturalEnd(status: .stopped, hasEntry: true, time: 0,
                                                         lastPlayingTime: 50, duration: 200))
        XCTAssertTrue(AppleMusicAudioOutput.isNaturalEnd(status: .paused, hasEntry: false, time: 0,
                                                         lastPlayingTime: 50, duration: 200))
        // A pause mid-song is never an end.
        XCTAssertFalse(AppleMusicAudioOutput.isNaturalEnd(status: .paused, hasEntry: true, time: 0,
                                                          lastPlayingTime: 50, duration: 200))
    }

    /// Codex C01: a load that began before a reset can't publish after it.
    func test_should_refusePublication_when_aLoadOutlivesAReset() async throws {
        let defaults = UserDefaults(suiteName: "g3-\(UUID().uuidString)")!
        let store = LibraryStore(session: ServerSession(), sources: SourceSelection(subsonicSignedIn: false, defaults: defaults),
                                 appleMusic: AppleMusicSource())
        let gen = store.generation
        try await LibraryStore.$loadGeneration.withValue(gen) {
            XCTAssertNoThrow(try store.ensureLive())
        }
        store.reset()
        try await LibraryStore.$loadGeneration.withValue(gen) {
            XCTAssertThrowsError(try store.ensureLive())
        }
    }

    /// Codex C07: what the player logs of a stream URL never carries the credentials.
    func test_should_logNoCredentials_when_describingAStreamURL() {
        let url = URL(string: "https://music.example/rest/stream.view?id=s1&u=alice&t=abc&s=salt&v=1.16.1&c=Yoin&maxBitRate=128")!
        let logged = AVAudioOutput.describe(url)
        for secret in ["alice", "abc", "salt"] { XCTAssertFalse(logged.contains(secret), logged) }
        XCTAssertTrue(logged.contains("id=s1"))
        XCTAssertTrue(logged.contains("maxBitRate=128"))
    }
}
