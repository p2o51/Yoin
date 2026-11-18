import XCTest
@testable import Yoin

/// Audit 11-15 #4 (device 11-16): the songs Yoin puts into this iPhone's Spotify queue stay there after a
/// play(uri), so they played again later ("Sentence" after Sentence) and Yoin sat on them. Yoin queues the
/// next only in a song's last seconds, goes to a song it queued by a skip, and skips past a leftover. Same
/// fakes as SpotifyBrokenConnectionTests, with Spotify's queue.
@MainActor
final class SpotifyQueueLeftoverTests: XCTestCase {
    private var app: FakeSpotifyApp!
    private var remote: SpotifyRemote!
    private var spotify: SpotifyAudioOutput!
    private var engine: PlayerEngine!
    private var source: SpotifySource!

    private let a = "4unM7lTfMfbiFMc6Hgnar6", b = "2JXQjdWdKhpdYYs48QEc2j", c = "3rH0pSMX1qwb5CaiefcTi4"

    override func setUp() {
        WebStub.reset()
        let app = FakeSpotifyApp()
        self.app = app
        remote = SpotifyRemote(makeLink: { _, _ in FakeLink(app: app) }, clientId: { "cid" }, enabled: { true })
        app.remote = remote
        remote.bind(account: "p1")
        remote.token = { "tok" }
        spotify = SpotifyAudioOutput(remote: remote, issues: SpotifyIssues(), spotifyInstalled: { true }, device: .phone)
        engine = PlayerEngine(output: spotify)
        let cfg = URLSessionConfiguration.ephemeral
        cfg.protocolClasses = [WebStub.self]
        source = SpotifySource(api: SpotifyWebAPI(token: { "tok" }, session: URLSession(configuration: cfg), sleep: { _ in }))
        spotify.control = source
    }

    override func tearDown() {
        spotify.control = nil
        spotify.stop()
        remote.disconnect()
        spotify = nil
        engine = nil
        remote = nil
        app = nil
        source = nil
        SpotifyPlayingDevice.shared.name = nil
    }

    private func song(_ id: String) -> PlayableTrack {
        PlayableTrack(id: id, provider: SpotifyMapping.provider, title: id, artist: "A", album: "Al", albumId: nil,
                      durationSec: 200, streamURL: SpotifyAudioOutput.streamURL(trackId: id), coverURL: nil)
    }

    private func uri(_ id: String) -> String { "spotify:track:\(id)" }

    private func waitUntil(_ seconds: Double = 8, _ what: String = "", _ done: () -> Bool) async throws {
        let end = Date().addingTimeInterval(seconds)
        while !done() {
            guard Date() < end else { return XCTFail("timed out \(what)") }
            try await Task.sleep(for: .milliseconds(20))
        }
    }

    private func play(_ ids: [String]) async throws {
        engine.play(queue: ids.map(song))
        try await waitUntil(8, "for the first song") { self.app.statesSent > 0 && self.engine.failure == nil && self.engine.isPlaying }
        try await Task.sleep(for: .milliseconds(300))
    }

    /// Spotify reports the song at 188 s of 200: the next goes into its queue.
    private func nearTheEnd(expecting queued: [String]) async throws {
        app.report(at: 188_000)
        try await waitUntil(3, "for the enqueue") { self.app.enqueues == queued.map(self.uri) }
        try await Task.sleep(for: .milliseconds(100))
    }

    func test_should_queueNothing_when_theSongIsNotInItsLastSeconds() async throws {
        try await play([a, b])
        app.report(at: 100_000)
        // Past the start's own enqueue checks (2 s and 3 s after it).
        try await Task.sleep(for: .seconds(SpotifyAudioOutput.enqueueCheckSec.reduce(0, +) + 0.5))
        XCTAssertEqual(app.enqueues, [], "nothing queued in Spotify mid-song in front")
        try await nearTheEnd(expecting: [b])
        XCTAssertEqual(app.userQueue, [uri(b)])
    }

    func test_should_skipToTheNext_when_itHeadsSpotifysQueue() async throws {
        try await play([a, b, c])
        try await nearTheEnd(expecting: [b])
        engine.next()
        try await waitUntil(5, "for B") { self.engine.currentTrack?.id == self.b && self.app.playing == self.uri(self.b) }
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertTrue(app.commands.contains("next"), "Spotify skipped to the queued copy")
        XCTAssertFalse(app.plays.contains(uri(b)), "not played over it (the copy would stay queued)")
        XCTAssertEqual(app.userQueue, [], "nothing of Yoin's is left in Spotify's queue")
        XCTAssertNil(engine.externalTrack)
    }

    func test_should_skipPastALeftover_andPlayTheEnginesNext() async throws {
        try await play([a, b, c])
        try await nearTheEnd(expecting: [b])
        // A row tapped: C plays over B, which stays in Spotify's queue.
        engine.jump(to: 2)
        try await waitUntil(5, "for C") { self.engine.currentTrack?.id == self.c && self.app.playing == self.uri(self.c) }
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertEqual(app.userQueue, [uri(b)])
        // C's next (repeat all) is A.
        try await nearTheEnd(expecting: [b, a])
        // C ends and Spotify plays B, the leftover, from its queue.
        app.endSong()
        try await waitUntil(5, "for A") { self.engine.currentTrack?.id == self.a && self.app.playing == self.uri(self.a) }
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertNil(engine.externalTrack, "the leftover isn't taken for Spotify's own song")
        XCTAssertEqual(engine.currentTrack?.id, a)
        XCTAssertEqual(app.plays, [uri(c)], "A came by a skip to its queued copy")
        XCTAssertEqual(app.userQueue, [])
    }

    func test_should_playTheEnginesNext_when_spotifyReplaysTheEndedSongFromItsQueue() async throws {
        try await play([a, b])
        try await nearTheEnd(expecting: [b])
        // Next, and Spotify won't skip: B is played over its queued copy.
        app.refuseSkips = true
        engine.next()
        try await waitUntil(5, "for B") { self.engine.currentTrack?.id == self.b && self.app.plays.last == self.uri(self.b) }
        try await Task.sleep(for: .milliseconds(300))
        app.refuseSkips = false
        XCTAssertEqual(app.userQueue, [uri(b)])
        try await nearTheEnd(expecting: [b, a])
        app.report(at: 199_500)
        try await Task.sleep(for: .milliseconds(100))
        // B ends and Spotify plays its queued copy of B (device 11-16: "Sentence" again).
        app.endSong()
        try await waitUntil(5, "for A") { self.engine.currentTrack?.id == self.a && self.app.playing == self.uri(self.a) }
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertNil(engine.externalTrack)
        XCTAssertEqual(engine.currentTrack?.id, a)
        XCTAssertEqual(app.userQueue, [])
    }

    func test_should_tellTheEnginesNext_fromALeftover() {
        typealias O = SpotifyAudioOutput
        let item = { O.Item(O.streamURL(trackId: $0))! }
        let queued = [item("b"), item("a")]
        XCTAssertEqual(O.queuedSong(uri: "spotify:track:b", queued: queued, next: item("a"), atBoundary: false), .leftover)
        XCTAssertEqual(O.queuedSong(uri: "spotify:track:b", queued: queued, next: item("b"), atBoundary: false), .enginesNext)
        // Past the head: only at a song's end (one picked mid-song is the listener's).
        XCTAssertEqual(O.queuedSong(uri: "spotify:track:a", queued: queued, next: item("c"), atBoundary: false), .notQueued)
        XCTAssertEqual(O.queuedSong(uri: "spotify:track:a", queued: queued, next: item("c"), atBoundary: true), .leftover)
        XCTAssertEqual(O.queuedSong(uri: "spotify:track:z", queued: queued, next: item("c"), atBoundary: true), .notQueued)
        XCTAssertTrue(O.advancesBySkip(item("b"), queued: queued))
        XCTAssertFalse(O.advancesBySkip(item("a"), queued: queued))
        XCTAssertFalse(O.advancesBySkip(item("a"), queued: []))
    }

    func test_should_queueTheNext_onlyInTheLastSeconds() {
        typealias O = SpotifyAudioOutput
        XCTAssertFalse(O.enqueueDue(durationMs: 200_000, positionMs: 1000))
        XCTAssertFalse(O.enqueueDue(durationMs: 200_000, positionMs: 180_000))
        XCTAssertTrue(O.enqueueDue(durationMs: 200_000, positionMs: 186_000))
        XCTAssertFalse(O.enqueueDue(durationMs: 0, positionMs: 186_000), "an unknown length waits")
        XCTAssertTrue(O.enqueueDue(remainingSec: 3))
        XCTAssertFalse(O.enqueueDue(remainingSec: nil))
    }

    func test_should_readASkip_asLandedOrElsewhere() {
        typealias O = SpotifyAudioOutput
        let a = "spotify:track:a", b = "spotify:track:b", x = "spotify:track:x"
        XCTAssertEqual(O.skipOutcome(stateURI: b, from: a, wanted: b, readsLeft: 3), .landed)
        XCTAssertEqual(O.skipOutcome(stateURI: a, from: a, wanted: b, readsLeft: 3), .wait, "not moved yet")
        XCTAssertEqual(O.skipOutcome(stateURI: x, from: a, wanted: b, readsLeft: 3), .elsewhere, "something queued ahead")
        XCTAssertEqual(O.skipOutcome(stateURI: a, from: a, wanted: b, readsLeft: 0), .elsewhere)
    }
}
