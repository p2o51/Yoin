import XCTest
@testable import Yoin

/// Parity I13 (Android SpotifyConnectFailure.SpotifyAppMissing / PremiumRequired over
/// SpotifyProviderStatus): a start with no Spotify app on the device, or refused for a free account,
/// holds the queue on the song with what to do, and the account's card says the same.
@MainActor
final class SpotifyIssueTests: XCTestCase {
    private var app: FakeSpotifyApp!
    private var remote: SpotifyRemote!
    private var issues: SpotifyIssues!
    private var spotify: SpotifyAudioOutput!
    private var engine: PlayerEngine!
    private var source: SpotifySource!
    private let device = Device()
    private var installed: Bool {
        get { device.hasSpotify }
        set { device.hasSpotify = newValue }
    }

    /// The device as the output asks it (the simulator has no Spotify app).
    @MainActor
    private final class Device {
        var hasSpotify = false
    }

    override func setUp() {
        IssueStub.reset()
        installed = false
        let app = FakeSpotifyApp()
        self.app = app
        remote = SpotifyRemote(makeLink: { _, _ in FakeLink(app: app) }, clientId: { "cid" }, enabled: { true })
        app.remote = remote
        remote.bind(account: "p1")
        remote.token = { "tok" }
        issues = SpotifyIssues()
        spotify = SpotifyAudioOutput(remote: remote, issues: issues, spotifyInstalled: { [device] in device.hasSpotify })
        engine = PlayerEngine(output: spotify)
        let cfg = URLSessionConfiguration.ephemeral
        cfg.protocolClasses = [IssueStub.self]
        source = SpotifySource(api: SpotifyWebAPI(token: { "tok" }, session: URLSession(configuration: cfg), sleep: { _ in }))
        spotify.adopt = { false }
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
        issues = nil
        SpotifyPlayingDevice.shared.name = nil
    }

    private func song(_ id: String) -> PlayableTrack {
        PlayableTrack(id: id, provider: SpotifyMapping.provider, title: id, artist: "A", album: "Al", albumId: nil,
                      durationSec: 200, streamURL: SpotifyAudioOutput.streamURL(trackId: id), coverURL: nil)
    }

    private func waitUntil(_ seconds: Double = 5, _ what: String = "", _ done: () -> Bool) async throws {
        let end = Date().addingTimeInterval(seconds)
        while !done() {
            guard Date() < end else { return XCTFail("timed out \(what)") }
            try await Task.sleep(for: .milliseconds(20))
        }
    }

    private var plays: Int { IssueStub.requests.filter { $0.method == "PUT" && $0.path == "/v1/me/player/play" }.count }

    // MARK: No Spotify app

    func test_should_holdOnInstallSpotify_when_theDeviceHasNoSpotifyApp() async throws {
        engine.play(queue: [song("s1"), song("s2")])
        try await waitUntil(5, "for the refusal") { self.engine.failure != nil }
        try await Task.sleep(for: .milliseconds(200))
        let failure = try XCTUnwrap(engine.failure)
        XCTAssertEqual(failure.message, SpotifyFailureCopy.appMissing)
        XCTAssertEqual(failure.action, .installSpotify)
        XCTAssertTrue(failure.network, "held, with the banner's action")
        XCTAssertEqual(engine.currentTrack?.id, "s1", "the queue stays on the song: the next would be refused too")
        XCTAssertFalse(engine.isPlaying)
        XCTAssertEqual(app.wakes, 0, "nothing to wake")
        XCTAssertEqual(app.connectionsMade, 0, "nothing to connect to")
        XCTAssertEqual(issues.issue(for: "p1"), .appMissing)
        XCTAssertEqual(issues.issue(for: "another-spotify-account"), .appMissing, "the app is the device's, not the account's")
    }

    func test_should_stillSayInstallSpotify_when_theRunIsSilent() async throws {
        // `-silentAudio YES` starts nothing (every simulator launch), but a start that could never run
        // here is said all the same: the simulator shows what a phone without Spotify shows.
        UserDefaults.standard.set(true, forKey: "silentAudio")
        defer { UserDefaults.standard.removeObject(forKey: "silentAudio") }
        let quiet = SpotifyAudioOutput(remote: remote, issues: issues, spotifyInstalled: { false })
        let engine = PlayerEngine(output: quiet)
        quiet.control = source
        defer { quiet.control = nil; quiet.stop() }
        engine.play(queue: [song("s1"), song("s2")])
        XCTAssertNil(engine.failure, "said once the engine has finished its load")
        try await waitUntil(3, "for the refusal") { engine.failure != nil }
        XCTAssertEqual(engine.failure?.action, .installSpotify)
        XCTAssertEqual(engine.currentTrack?.id, "s1")
        XCTAssertFalse(engine.isPlaying)
        XCTAssertEqual(issues.issue(for: "p1"), .appMissing)
        XCTAssertEqual(app.wakes, 0)
        XCTAssertEqual(plays, 0, "a silent run starts nothing")
    }

    func test_should_playOnTheOtherDevice_when_spotifyPlaysThereAndThisDeviceHasNoApp() async throws {
        IssueStub.route = { s in s.method == "GET" ? .init(status: 200, body: Self.pixel()) : .init(status: 204, body: "") }
        engine.play(queue: [song("s1")])
        try await waitUntil(5, "for the start on the Pixel") { self.plays == 1 }
        try await Task.sleep(for: .milliseconds(200))
        XCTAssertNil(engine.failure, "a remote for another device needs no Spotify app here")
        XCTAssertNil(issues.issue(for: "p1"))
    }

    func test_should_goBackToAPlainPause_when_spotifyIsInstalledSinceTheRefusal() async throws {
        engine.play(queue: [song("s1")])
        try await waitUntil(5, "for the refusal") { self.engine.failure != nil }
        // Back from the App Store without it: nothing changes.
        spotify.appMayBeBack()
        XCTAssertEqual(engine.failure?.action, .installSpotify)
        XCTAssertEqual(issues.issue(for: "p1"), .appMissing)
        installed = true
        spotify.appMayBeBack()
        XCTAssertNil(engine.failure)
        XCTAssertFalse(engine.isPlaying, "Play starts it: Yoin doesn't open Spotify by itself")
        XCTAssertNil(issues.issue(for: "p1"))
        XCTAssertEqual(app.wakes, 0)
    }

    func test_should_clearInstallSpotify_when_aStartLandsHere() async throws {
        issues.note(.appMissing, account: "p1")
        installed = true
        engine.play(queue: [song("s1")])
        try await waitUntil(8, "for the woken start") { self.app.wakes == 1 && self.app.statesSent > 0 }
        try await waitUntil(5, "for the card") { self.issues.issue(for: "p1") == nil }
        XCTAssertNil(engine.failure)
    }

    // MARK: Premium

    func test_should_holdOnPremium_andMarkTheAccount_when_spotifyRefusesAFreeAccountsStart() async throws {
        IssueStub.route = { s in
            if s.method == "GET" { return .init(status: 200, body: Self.pixel()) }
            if s.path == "/v1/me/player/play" {
                return .init(status: 403, body: #"{"error":{"status":403,"message":"Player command failed: Premium required","reason":"PREMIUM_REQUIRED"}}"#)
            }
            return .init(status: 204, body: "")
        }
        engine.play(queue: [song("s1"), song("s2"), song("s3")])
        try await waitUntil(5, "for the refusal") { self.engine.failure != nil }
        try await Task.sleep(for: .milliseconds(400))
        let failure = try XCTUnwrap(engine.failure)
        XCTAssertEqual(failure.message, SpotifyFailureCopy.premium)
        XCTAssertEqual(failure.action, .none)
        XCTAssertEqual(engine.currentTrack?.id, "s1", "held on the song, not skipped through the queue")
        XCTAssertEqual(plays, 1, "one refusal, not one per song")
        XCTAssertEqual(IssueStub.requests.filter { $0.path == "/v1/me/player/pause" }.count, 0,
                       "the engine's pause after a failure isn't sent")
        XCTAssertEqual(issues.issue(for: "p1"), .premiumRequired)
        XCTAssertNil(issues.issue(for: "another-spotify-account"), "Premium is the account's")
    }

    func test_should_clearPremium_when_spotifyTakesAStartForTheAccount() async throws {
        issues.note(.premiumRequired, account: "p1")
        IssueStub.route = { s in s.method == "GET" ? .init(status: 200, body: Self.pixel()) : .init(status: 204, body: "") }
        engine.play(queue: [song("s1")])
        try await waitUntil(5, "for the start") { self.plays == 1 }
        try await waitUntil(2, "for the card") { self.issues.issue(for: "p1") == nil }
    }

    // MARK: The pieces

    func test_should_orderAndForgetIssues_asTheCardReadsThem() {
        let issues = SpotifyIssues()
        XCTAssertNil(issues.issue(for: "a"))
        issues.note(.premiumRequired, account: "a")
        issues.note(.premiumRequired, account: nil)
        XCTAssertEqual(issues.issue(for: "a"), .premiumRequired)
        XCTAssertNil(issues.issue(for: "b"))
        // The missing app first (Android toProfileCard: SpotifyAppMissing before NoPremium).
        issues.note(.appMissing, account: "b")
        XCTAssertEqual(issues.issue(for: "a"), .appMissing)
        XCTAssertEqual(issues.issue(for: "b"), .appMissing)
        issues.appFound()
        XCTAssertEqual(issues.issue(for: "a"), .premiumRequired)
        XCTAssertNil(issues.issue(for: "b"))
        // Signed in again (or removed): what Spotify said of the account no longer counts.
        issues.forget("a")
        XCTAssertNil(issues.issue(for: "a"))
        issues.note(.premiumRequired, account: "a")
        issues.started(account: "b")
        XCTAssertEqual(issues.issue(for: "a"), .premiumRequired)
        issues.started(account: "a")
        XCTAssertNil(issues.issue(for: "a"))
    }

    func test_should_readTheRefusal_fromAFailuresText() {
        XCTAssertEqual(SpotifyAudioOutput.refusal(SpotifyFailureCopy.appMissing), .appMissing)
        XCTAssertEqual(SpotifyAudioOutput.refusal(SpotifyFailureCopy.premium), .premiumRequired)
        // Spotify's own words (Android userNotAuthorizedFailure looks for the same one).
        XCTAssertEqual(SpotifyAudioOutput.refusal("Spotify Premium is required to use this feature"), .premiumRequired)
        XCTAssertEqual(SpotifyAudioOutput.refusal("PREMIUM_REQUIRED"), .premiumRequired)
        XCTAssertNil(SpotifyAudioOutput.refusal("The request failed."))
        XCTAssertNil(SpotifyAudioOutput.refusal(SpotifyFailureCopy.openSpotify(on: "iPhone")))
        XCTAssertNil(SpotifyIssues.Issue(message: "Spotify Premium is required to use this feature"),
                     "only Yoin's own lines are an issue's message")
    }

    func test_should_offerInstallSpotify_andOpenItsStorePage() throws {
        XCTAssertEqual(PlaybackFailure.action(for: SpotifyFailureCopy.appMissing), .installSpotify)
        XCTAssertEqual(PlaybackFailure.action(for: SpotifyFailureCopy.openSpotify(on: "iPhone")), .openSpotify)
        XCTAssertEqual(SpotifyApp.storeURL(itemId: 123)?.absoluteString, "https://apps.apple.com/app/id123")
        XCTAssertNil(SpotifyApp.storeURL(itemId: 0))
        // The id is the Spotify SDK's own.
        let url = try XCTUnwrap(SpotifyApp.storeURL)
        XCTAssertEqual(url.host, "apps.apple.com")
        let id = try XCTUnwrap(url.lastPathComponent.hasPrefix("id") ? Int(url.lastPathComponent.dropFirst(2)) : nil)
        XCTAssertGreaterThan(id, 0)
    }

    func test_should_useAndroidsWords_inBothLanguages() {
        let zh = Bundle.main.preferredLocalizations.first?.hasPrefix("zh") ?? false
        XCTAssertEqual(SpotifyIssues.Issue.appMissing.label, zh ? "安装 Spotify" : "Install Spotify")
        XCTAssertEqual(SpotifyIssues.Issue.premiumRequired.label, zh ? "需要 Premium" : "Premium required")
        XCTAssertEqual(SpotifyIssues.Issue.appMissing.message,
                       zh ? "请安装 Spotify 应用以播放 Spotify 曲目。" : "Install the Spotify app to play Spotify tracks.")
        XCTAssertEqual(SpotifyIssues.Issue.premiumRequired.message,
                       zh ? "应用内播放需要 Spotify Premium。" : "Spotify Premium is required for in-app playback.")
    }

    func test_should_carryZhHans_forEveryNewString() throws {
        // The catalog itself, whatever language the test host runs in.
        let url = try XCTUnwrap(Bundle.main.url(forResource: "Localizable", withExtension: "strings", subdirectory: nil,
                                                localization: "zh-Hans"))
        let table = try XCTUnwrap(NSDictionary(contentsOf: url) as? [String: String])
        XCTAssertEqual(table["Install Spotify"], "安装 Spotify")
        XCTAssertEqual(table["Premium required"], "需要 Premium")
        XCTAssertEqual(table["Install the Spotify app to play Spotify tracks."], "请安装 Spotify 应用以播放 Spotify 曲目。")
    }

    nonisolated static func pixel() -> String {
        #"{"device":{"id":"7a3d","name":"Pixel 11 Pro","type":"Smartphone"},"is_playing":true,"progress_ms":1000,"shuffle_state":false,"repeat_state":"off","item":{"uri":"spotify:track:other","duration_ms":200000,"name":"Song On Pixel","artists":[{"name":"Them"}],"album":{"name":"Theirs","images":[]}}}"#
    }
}

/// The Web API as these tests need it: nothing active (204) unless [route] says otherwise.
private final class IssueStub: URLProtocol {
    struct Seen: Sendable {
        var method: String
        var path: String
    }

    struct Reply {
        var status: Int
        var body: String
    }

    private static let lock = NSLock()
    nonisolated(unsafe) static var route: @Sendable (Seen) -> Reply = { _ in Reply(status: 204, body: "") }
    nonisolated(unsafe) private static var seen: [Seen] = []

    static func reset() {
        lock.withLock { seen = [] }
        route = { _ in Reply(status: 204, body: "") }
    }

    static var requests: [Seen] { lock.withLock { seen } }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let s = Seen(method: request.httpMethod ?? "GET", path: request.url!.path)
        let reply = Self.route(s)
        Self.lock.withLock { Self.seen.append(s) }
        let response = HTTPURLResponse(url: request.url!, statusCode: reply.status, httpVersion: "HTTP/1.1",
                                       headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(reply.body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
