import XCTest
@preconcurrency import SpotifyiOS
@testable import Yoin

/// 51's 11-08 log: the App Remote connection made right after a wake's handback failed its subscription
/// and every play with "The request failed" while it read as connected, so every album after it failed
/// on it ("Open Spotify on this iPhone, then tap Play") though Spotify played. Against a fake Spotify app
/// whose woken first connection carries nothing and whose next one works, and a fake Web API.
@MainActor
final class SpotifyBrokenConnectionTests: XCTestCase {
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
        remote = SpotifyRemote(makeLink: { _, _ in FakeLink(app: app) },
                               clientId: { "cid" }, enabled: { true })
        app.remote = remote
        remote.bind(account: "p1")
        remote.token = { "tok" }
        // The fake Spotify app is installed (the simulator has none: canOpenURL would say so), and the
        // stub's me/player names an iPhone: this device is one, whatever simulator runs the tests.
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

    private func waitUntil(_ seconds: Double = 8, _ what: String = "", _ done: () -> Bool) async throws {
        let end = Date().addingTimeInterval(seconds)
        while !done() {
            guard Date() < end else { return XCTFail("timed out \(what)") }
            try await Task.sleep(for: .milliseconds(20))
        }
    }

    private func uri(_ id: String) -> String { "spotify:track:\(id)" }

    func test_should_replaceTheWokenConnection_and_playTheNextAlbums_when_itCarriesNothing() async throws {
        app.wakeBreaksConnection = true
        engine.play(queue: [song(a)])
        // Woken on A: the woken app plays it; the handback's connection fails its subscription and is
        // replaced (no second wake), and the replacement's state has A.
        try await waitUntil(8, "for A") { self.app.connectionsMade >= 3 && self.spotify.currentTimeSec >= 0 && self.engine.failure == nil && self.app.statesSent > 0 }
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertEqual(app.wakes, 1)
        XCTAssertNil(engine.failure)
        XCTAssertEqual(app.plays, [], "the woken app plays A by itself: nothing is sent over it")

        engine.play(queue: [song(b)])
        try await waitUntil(5, "for B") { self.app.plays.last == self.uri(self.b) }
        engine.play(queue: [song(c)])
        try await waitUntil(5, "for C") { self.app.plays.last == self.uri(self.c) }
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertNil(engine.failure)
        XCTAssertEqual(app.wakes, 1, "a broken connection is replaced, Spotify isn't woken again")
        XCTAssertEqual(app.playing, uri(c))
    }

    func test_should_reconnectAndPlay_when_aPlayFailsOnAConnectionThatReadsAsConnected() async throws {
        // The woken connection says nothing of its subscription (no failure to act on), and its plays fail.
        app.wakeBreaksConnection = true
        app.badSubscribeSilent = true
        engine.play(queue: [song(a)])
        // No App Remote state ever comes: after the wait, me/player shows A playing here.
        try await waitUntil(8, "for A on me/player") { WebStub.reads >= 2 && self.app.wakes == 1 }
        try await Task.sleep(for: .seconds(SpotifyAudioOutput.wakeSettleSec + 0.6))
        XCTAssertNil(engine.failure)
        XCTAssertEqual(app.plays, [], "me/player showed A: no play over it")

        engine.play(queue: [song(b)])
        try await waitUntil(8, "for B") { self.app.plays.last == self.uri(self.b) }
        engine.play(queue: [song(c)])
        try await waitUntil(5, "for C") { self.app.plays.last == self.uri(self.c) }
        try await Task.sleep(for: .milliseconds(300))
        XCTAssertNil(engine.failure)
        XCTAssertEqual(app.wakes, 1)
    }

    func test_should_takeTheWokenStart_when_theFirstStateComesLate() async throws {
        app.firstStateDelaySec = 1.2
        engine.play(queue: [song(a)])
        try await waitUntil(6, "for the late state") { self.app.statesSent > 0 }
        try await Task.sleep(for: .milliseconds(SpotifyAudioOutput.wakeSettleSec * 1000 + 500))
        XCTAssertNil(engine.failure)
        XCTAssertEqual(app.plays, [], "the song was already playing: no play sent")
        XCTAssertEqual(app.wakes, 1)
    }

    func test_should_countARelinkedCopy_asTheSong_when_meplayerNamesItInLinkedFrom() async throws {
        app.appRemoteSilent = true
        app.relinkTo = "spotify:track:0relinkedCopy0000000000"
        engine.play(queue: [song(a)])
        try await waitUntil(8, "for the web check") { WebStub.reads >= 2 }
        try await Task.sleep(for: .milliseconds(SpotifyAudioOutput.wakeSettleSec * 1000 + 800))
        XCTAssertNil(engine.failure)
        XCTAssertEqual(app.plays, [], "Spotify plays A's copy: nothing is sent")
        XCTAssertTrue(SpotifyAudioOutput.landedHere(
            .init(deviceId: "x", deviceName: "iPhone", deviceType: "Smartphone", isPlaying: true,
                  trackURI: "spotify:track:copy", linkedFromURI: uri(a)), wanted: uri(a), this: .phone))
    }

    func test_should_readMeplayer_beforeSayingTheStartFailed() async throws {
        // Nothing ever works and Spotify plays nothing: the failure comes, but only after me/player said so.
        app.allConnectionsBad = true
        app.webEmpty = true
        engine.play(queue: [song(a)])
        try await waitUntil(25, "for the failure") { self.engine.failure != nil }
        XCTAssertGreaterThanOrEqual(WebStub.readsAfterLastPlay(app.lastPlayAt), 1)
        XCTAssertLessThanOrEqual(app.wakes, 2, "never a loop of trips to Spotify")
    }

    func test_should_markTheConnectionBroken_only_onRequestFailedOrTerminated() {
        let failed = NSError(domain: SPTAppRemoteErrorDomain, code: SPTAppRemoteErrorCode.requestFailedError.rawValue)
        let terminated = NSError(domain: SPTAppRemoteErrorDomain, code: SPTAppRemoteErrorCode.connectionTerminatedError.rawValue)
        let later = NSError(domain: SPTAppRemoteErrorDomain, code: -3000)
        XCTAssertTrue(SpotifyRemote.breaksConnection(failed))
        XCTAssertTrue(SpotifyRemote.breaksConnection(terminated))
        XCTAssertFalse(SpotifyRemote.breaksConnection(later))
        XCTAssertFalse(SpotifyRemote.breaksConnection(NSError(domain: NSURLErrorDomain, code: -2001)))
    }
}

/// The Spotify app as App Remote and me/player see it.
@MainActor
final class FakeSpotifyApp {
    weak var remote: SpotifyRemote?
    var running = false
    var playing: String? { didSet { publish() } }
    /// The connection the handback opens carries nothing (51's 11-08 log); the next one works.
    var wakeBreaksConnection = false
    /// That connection's subscription never answers (so only a failed play shows it's broken).
    var badSubscribeSilent = false
    var allConnectionsBad = false
    /// App Remote reports no song at all (only me/player knows).
    var appRemoteSilent = false
    /// The first state after a wake comes this late (its read before then shows no song).
    var firstStateDelaySec: Double = 0
    /// me/player shows this copy of the song, naming the song in linked_from.
    var relinkTo: String? { didSet { publish() } }
    var webEmpty = false { didSet { publish() } }
    var nextConnectionBad = false
    var plays: [String] = []
    var lastPlayAt = Date.distantPast
    var wakes = 0
    var connectionsMade = 0
    var statesSent = 0
    private var stateReadyAt = Date.distantPast

    var stateReady: Bool { Date() >= stateReadyAt }

    func woke(on uri: String) {
        running = true
        playing = uri
        stateReadyAt = Date().addingTimeInterval(firstStateDelaySec)
        nextConnectionBad = wakeBreaksConnection
    }

    func publish() {
        WebStub.set(webEmpty || !running ? nil : playing, relinkedTo: relinkTo)
    }
}

@MainActor
final class FakeLink: AppRemoteLink {
    private let app: FakeSpotifyApp
    private(set) var isConnected = false
    private var bad = false
    var accessToken: String?
    var onEstablished: (() -> Void)?
    var onConnectFailed: ((String) -> Void)?
    var onDisconnected: ((String) -> Void)?
    var onPlayerState: ((RemotePlayerState) -> Void)?

    init(app: FakeSpotifyApp) { self.app = app }

    nonisolated static let requestFailed = NSError(domain: SPTAppRemoteErrorDomain,
                                                   code: SPTAppRemoteErrorCode.requestFailedError.rawValue,
                                                   userInfo: [NSLocalizedDescriptionKey: "The request failed."])

    private func later(_ ms: Int, _ work: @escaping @MainActor () -> Void) {
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(ms))
            work()
        }
    }

    func connect() {
        app.connectionsMade += 1
        let bad = app.allConnectionsBad || app.nextConnectionBad
        app.nextConnectionBad = false
        later(10) { [self] in
            guard app.running else { return onConnectFailed?("Connection attempt failed.") ?? () }
            self.bad = bad
            isConnected = true
            onEstablished?()
        }
    }

    func disconnect() {
        guard isConnected else { return }
        isConnected = false
        later(5) { [self] in onDisconnected?("Connection terminated.") }
    }

    func authorizeAndPlay(_ uri: String, opened: @escaping @MainActor (Bool) -> Void) {
        app.wakes += 1
        opened(true)
        later(50) { [app] in
            app.woke(on: uri)
            _ = app.remote?.handle(URL(string: SpotifyConfig.appRemoteRedirectURI + "#access_token=t")!)
        }
    }

    func handback(_ url: URL) -> (token: String?, error: String?) { ("t", nil) }

    private var state: RemotePlayerState {
        RemotePlayerState(uri: app.appRemoteSilent || !app.stateReady ? "" : (app.playing ?? ""), isPaused: false,
                          positionMs: 1000, durationMs: 200_000)
    }

    private func push() {
        guard isConnected, !bad, !app.appRemoteSilent else { return }
        let wait = max(0, Int(app.firstStateDelaySec * 1000) + 20)
        later(app.stateReady ? 20 : wait) { [self] in
            guard isConnected, !bad else { return }
            app.statesSent += 1
            onPlayerState?(state)
        }
    }

    func subscribe(_ done: @escaping (Error?) -> Void) {
        if bad, app.badSubscribeSilent { return }
        later(5) { [self] in
            if bad { return done(Self.requestFailed) }
            done(nil)
            push()
        }
    }

    func getPlayerState(_ done: @escaping (RemotePlayerState?, Error?) -> Void) {
        later(5) { [self] in bad || !isConnected ? done(nil, Self.requestFailed) : done(state, nil) }
    }

    private func answer(_ done: @escaping (Error?) -> Void, _ ok: @escaping () -> Void = {}) {
        let fail = bad || !isConnected
        later(1) {
            if fail { return done(Self.requestFailed) }
            ok()
            done(nil)
        }
    }

    func play(_ uri: String, _ done: @escaping (Error?) -> Void) {
        app.lastPlayAt = Date()
        answer(done) { [self] in
            app.plays.append(uri)
            app.playing = uri
            push()
        }
    }

    func playContext(_ uri: String, index: Int, _ done: @escaping (Error?) -> Void) { answer(done) }
    func resume(_ done: @escaping (Error?) -> Void) { answer(done) }
    func pause(_ done: @escaping (Error?) -> Void) { answer(done) }
    func seek(toMs ms: Int) {}
    func skip(forward: Bool, _ done: @escaping (Error?) -> Void) { answer(done) }
    func enqueue(_ uri: String, _ done: @escaping (Error?) -> Void) { answer(done) }
    func setShuffle(_ on: Bool) {}
    func setRepeat(_ mode: SPTAppRemotePlaybackOptionsRepeatMode) {}
}

/// me/player from the fake app's state: this iPhone playing it, or 204.
private final class WebStub: URLProtocol {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var playing: String?
    nonisolated(unsafe) private static var relinked: String?
    nonisolated(unsafe) private static var readTimes: [Date] = []

    static func reset() { lock.withLock { playing = nil; relinked = nil; readTimes = [] } }
    static func set(_ uri: String?, relinkedTo: String?) { lock.withLock { playing = uri; relinked = relinkedTo } }
    static var reads: Int { lock.withLock { readTimes.count } }
    static func readsAfterLastPlay(_ at: Date) -> Int { lock.withLock { readTimes.filter { $0 > at }.count } }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let url = request.url!
        var status = 204, body = ""
        if request.httpMethod == "GET", url.path.hasSuffix("/me/player") {
            let (uri, copy) = Self.lock.withLock {
                Self.readTimes.append(Date())
                return (Self.playing, Self.relinked)
            }
            if let uri {
                let item = copy.map { #"{"uri":"\#($0)","linked_from":{"uri":"\#(uri)"},"duration_ms":200000,"name":"S"}"# }
                    ?? #"{"uri":"\#(uri)","duration_ms":200000,"name":"S"}"#
                status = 200
                body = #"{"device":{"id":"ip","name":"iPhone","type":"Smartphone"},"is_playing":true,"progress_ms":2729,"shuffle_state":false,"repeat_state":"off","item":\#(item)}"#
            }
        }
        let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1",
                                       headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
