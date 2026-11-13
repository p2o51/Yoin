import XCTest
@testable import Yoin

/// Which Connect device is "this one" (parity I23): an iPhone is the Smartphone named iPhone, an iPad
/// the Tablet named iPad, and a Mac's Spotify app the Computer listed under the Mac's own name.
final class SpotifyThisDeviceTests: XCTestCase {
    private func now(_ name: String, _ type: String, playing: Bool = true, id: String = "d") -> SpotifySource.PlayerNow {
        SpotifySource.PlayerNow(deviceId: id, deviceName: name, deviceType: type, isPlaying: playing)
    }

    private func device(_ id: String, _ name: String, _ type: String, active: Bool = false) -> SpotifySource.Device {
        SpotifySource.Device(id: id, name: name, type: type, isActive: active)
    }

    func test_should_matchByTypeAndProductName_when_onAnIPhoneOrIPad() {
        XCTAssertTrue(SpotifyThisDevice.phone.matches(now("51's iPhone Air", "Smartphone")))
        XCTAssertFalse(SpotifyThisDevice.phone.matches(now("Pixel 11 Pro", "Smartphone")))
        XCTAssertFalse(SpotifyThisDevice.phone.matches(now("51's iPad", "Tablet")))
        // An iPad was never "here" before: Spotify lists it as a Tablet named iPad.
        XCTAssertTrue(SpotifyThisDevice.tablet.matches(now("51's iPad", "Tablet")))
        XCTAssertTrue(SpotifyThisDevice.tablet.matches(now("iPad Pro", "tablet")))
        XCTAssertFalse(SpotifyThisDevice.tablet.matches(now("Pixel Tablet", "Tablet")))
        XCTAssertFalse(SpotifyThisDevice.tablet.matches(now("51's iPhone Air", "Smartphone")))
    }

    func test_should_matchAMacByItsOwnName_when_listedAsAComputer() {
        let mac = SpotifyThisDevice.mac(["51's MacBook Pro"])
        XCTAssertTrue(mac.matches(now("51's MacBook Pro", "Computer")))
        XCTAssertTrue(mac.matches(now(" 51's macbook pro ", "computer")))
        // Never another computer, and never a phone that happens to share the name.
        XCTAssertFalse(mac.matches(now("Office iMac", "Computer")))
        XCTAssertFalse(mac.matches(now("51's MacBook Pro", "Smartphone")))
        XCTAssertFalse(SpotifyThisDevice.mac([]).matches(now("51's MacBook Pro", "Computer")))
        XCTAssertFalse(SpotifyThisDevice.mac([""]).matches(now("", "Computer")))
    }

    func test_should_matchTheHostName_when_theComputersNameIsntPlainASCII() {
        // An iPad app can read the host name, not the computer's name: macOS makes one from the other.
        XCTAssertTrue(SpotifyThisDevice.mac(["51deMac-mini"]).matches(now("51的Mac mini", "Computer")))
        XCTAssertTrue(SpotifyThisDevice.mac(["51s-MacBook-Pro"]).matches(now("51’s MacBook Pro", "Computer")))
        XCTAssertTrue(SpotifyThisDevice.mac(["Jorgs-MacBook-Air"]).matches(now("Jörg's MacBook Air", "Computer")))
        XCTAssertFalse(SpotifyThisDevice.mac(["51s-MacBook-Pro"]).matches(now("51's MacBook Air", "Computer")))
        XCTAssertEqual(SpotifyThisDevice.fold("51的Mac mini"), "51demacmini")
        XCTAssertEqual(SpotifyThisDevice.fold("—"), "")
    }

    func test_should_readTheMacsNames_when_asked() {
        XCTAssertEqual(SpotifyThisDevice.macNames(hostName: "51deMac-mini.local", uiName: "iPad"), ["iPad", "51deMac-mini"])
        XCTAssertEqual(SpotifyThisDevice.macNames(hostName: "localhost", uiName: ""), [])
        // A debug launch names its stand-in Mac itself.
        XCTAssertEqual(SpotifyThisDevice.macNames(hostName: "host.local", uiName: "iPad", override: "QA Mac"), ["QA Mac"])
    }

    func test_should_pickThisDevice_andTheActiveOneOfSeveral_when_readingTheList() {
        let mac = SpotifyThisDevice.mac(["QA Mac"])
        XCTAssertNil(mac.pick([device("p", "Pixel 11 Pro", "Smartphone", active: true), device("o", "Office iMac", "Computer")]))
        XCTAssertEqual(mac.pick([device("p", "Pixel 11 Pro", "Smartphone"), device("m", "QA Mac", "Computer")])?.id, "m")
        XCTAssertEqual(mac.pick([device("m1", "QA Mac", "Computer"), device("m2", "QA Mac", "Computer", active: true)])?.id, "m2")
    }

    func test_should_startHere_when_thisIPadIsTheOnePlaying() {
        let ipad = now("51's iPad", "Tablet", id: "t")
        // On the iPad itself it is here (App Remote); from an iPhone it is another device.
        XCTAssertNil(SpotifyAudioOutput.remoteTarget(ipad, this: .tablet))
        XCTAssertEqual(SpotifyAudioOutput.remoteTarget(ipad, this: .phone), "t")
        XCTAssertEqual(SpotifyAudioOutput.remoteTarget(now("51's iPhone Air", "Smartphone", id: "i"), this: .tablet), "i")
        var song = ipad
        song.trackURI = "spotify:track:a"
        XCTAssertTrue(SpotifyAudioOutput.landedHere(song, wanted: "spotify:track:a", this: .tablet))
        XCTAssertFalse(SpotifyAudioOutput.landedHere(song, wanted: "spotify:track:a", this: .phone))
    }

    // MARK: The Mac's rule (YoinMac MacSpotifyOutput.decide; 51, 2026-11-06)

    func test_should_goToTheDeviceThatPlays_when_somethingPlays() {
        let mac = SpotifyThisDevice.mac(["QA Mac"])
        let own = device("m", "QA Mac", "Computer")
        XCTAssertEqual(SpotifyAudioOutput.macTarget(now: now("Pixel 11 Pro", "Smartphone", id: "p"), devices: [own], this: mac),
                       .device(id: "p", name: "Pixel 11 Pro", here: false))
        XCTAssertEqual(SpotifyAudioOutput.macTarget(now: now("QA Mac", "Computer", id: "m"), devices: [], this: mac),
                       .device(id: "m", name: "QA Mac", here: true))
    }

    func test_should_goToThisMacsSpotify_when_nothingPlays() {
        let mac = SpotifyThisDevice.mac(["QA Mac"])
        let own = device("m", "QA Mac", "Computer")
        let pixel = device("p", "Pixel 11 Pro", "Smartphone", active: true)
        XCTAssertEqual(SpotifyAudioOutput.macTarget(now: nil, devices: [own], this: mac), .device(id: "m", name: "QA Mac", here: true))
        // A paused device elsewhere, even the active one, never takes the start.
        XCTAssertEqual(SpotifyAudioOutput.macTarget(now: now("Pixel 11 Pro", "Smartphone", playing: false, id: "p"),
                                                    devices: [pixel, own], this: mac),
                       .device(id: "m", name: "QA Mac", here: true))
    }

    func test_should_beUnreachable_when_thisMacsSpotifyIsntListed() {
        let mac = SpotifyThisDevice.mac(["QA Mac"])
        let pixel = device("p", "Pixel 11 Pro", "Smartphone", active: true)
        XCTAssertEqual(SpotifyAudioOutput.macTarget(now: nil, devices: [], this: mac), .unreachable)
        XCTAssertEqual(SpotifyAudioOutput.macTarget(now: now("Pixel 11 Pro", "Smartphone", playing: false, id: "p"),
                                                    devices: [pixel, device("o", "Office iMac", "Computer")], this: mac),
                       .unreachable)
    }

    func test_should_tryThisMacOnceMore_only_when_anotherGoCanHelp() {
        let gone = SpotifyAPIError.http(status: 404, message: "Device not found")
        XCTAssertTrue(SpotifyAudioOutput.retriesOnMac(alreadyTried: false, toThisMac: true, error: gone))
        XCTAssertTrue(SpotifyAudioOutput.retriesOnMac(alreadyTried: false, toThisMac: true, error: nil))
        XCTAssertFalse(SpotifyAudioOutput.retriesOnMac(alreadyTried: true, toThisMac: true, error: gone))
        // A device playing elsewhere is the listener's: no second start there.
        XCTAssertFalse(SpotifyAudioOutput.retriesOnMac(alreadyTried: false, toThisMac: false, error: gone))
        XCTAssertFalse(SpotifyAudioOutput.retriesOnMac(alreadyTried: false, toThisMac: true,
                                                       error: SpotifyAPIError.http(status: 403, message: "Premium required")))
        XCTAssertFalse(SpotifyAudioOutput.retriesOnMac(alreadyTried: false, toThisMac: true,
                                                       error: SpotifyAPIError.rateLimited(retryAfter: 30)))
        XCTAssertFalse(SpotifyAudioOutput.retriesOnMac(alreadyTried: false, toThisMac: true, error: SpotifyAPIError.unauthorized))
    }

    // MARK: Copy

    func test_should_nameTheDeviceItRunsOn_when_sayingWhereToOpenSpotify() {
        XCTAssertEqual(SpotifyFailureCopy.openSpotify(on: SpotifyThisDevice.phone.word), loc("Open Spotify on this \("iPhone"), then tap Play"))
        XCTAssertEqual(SpotifyFailureCopy.openSpotify(on: SpotifyThisDevice.tablet.word), loc("Open Spotify on this \("iPad"), then tap Play"))
        XCTAssertNotEqual(SpotifyFailureCopy.openSpotify(on: "iPad"), SpotifyFailureCopy.openSpotify(on: "iPhone"))
        XCTAssertEqual(SpotifyAudioOutput.droppedMessage(device: "Pixel 11 Pro", here: .tablet),
                       loc("Spotify on \("Pixel 11 Pro") stopped the song. Retry plays it on this \("iPad")."))
        XCTAssertEqual(SpotifyAudioOutput.droppedMessage(device: "Pixel 11 Pro", here: .mac([])),
                       loc("Spotify on \("Pixel 11 Pro") stopped the song. Retry plays it on this \("Mac")."))
        XCTAssertEqual(SpotifyAudioOutput.droppedMessage(device: nil, here: .mac([])), loc("Spotify on this Mac stopped the song"))
        XCTAssertEqual(SpotifyAudioOutput.macStartFailure(SpotifyAPIError.http(status: 404, message: "Device not found")),
                       SpotifyFailureCopy.notOpenOnMac)
        XCTAssertEqual(SpotifyAudioOutput.macStartFailure(SpotifyAPIError.http(status: 403, message: "Premium required")),
                       SpotifyFailureCopy.premium)
    }
}

/// A Spotify start on a Mac (the iPad app there; MAC blocker 2, parity I24), against a fake Web API and a
/// fake Spotify app that would answer App Remote if it were ever asked: the song goes to the device that
/// plays, else to this Mac's own Spotify by its id, else nothing is sent and the banner says Spotify
/// isn't open here.
@MainActor
final class SpotifyOnMacTests: XCTestCase {
    private var app: FakeSpotifyApp!
    private var remote: SpotifyRemote!
    private var spotify: SpotifyAudioOutput!
    private var engine: PlayerEngine!
    private var source: SpotifySource!

    private static let mac = ConnectStub.Device(id: "mac1", name: "QA Mac", type: "Computer")
    private static let pixel = ConnectStub.Device(id: "7a3d", name: "Pixel 11 Pro", type: "Smartphone")
    private static let imac = ConnectStub.Device(id: "im4c", name: "Office iMac", type: "Computer")

    @MainActor override func setUp() async throws {
        ConnectStub.reset()
        make(.mac(["QA Mac"]))
    }

    private func make(_ device: SpotifyThisDevice) {
        let app = FakeSpotifyApp()
        // An App Remote that would connect and play: the Mac must never get that far.
        app.running = true
        self.app = app
        remote = SpotifyRemote(makeLink: { _, _ in FakeLink(app: app) }, clientId: { "cid" }, enabled: { true })
        app.remote = remote
        remote.bind(account: "p1")
        remote.token = { "tok" }
        // This device has the Spotify app (the simulator has none: canOpenURL would say so), and what a
        // start says of the account stays out of the app's own cards.
        spotify = SpotifyAudioOutput(remote: remote, issues: SpotifyIssues(), spotifyInstalled: { true }, device: device)
        engine = PlayerEngine(output: spotify)
        let cfg = URLSessionConfiguration.ephemeral
        cfg.protocolClasses = [ConnectStub.self]
        source = SpotifySource(api: SpotifyWebAPI(token: { "tok" }, session: URLSession(configuration: cfg), sleep: { _ in }))
        spotify.adopt = { false }
    }

    @MainActor override func tearDown() async throws {
        // No account first: a stop while playing would pause Spotify into the next test's log.
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

    private func waitUntil(_ seconds: Double = 4, _ what: String = "", _ done: () -> Bool) async throws {
        let end = Date().addingTimeInterval(seconds)
        while !done() {
            guard Date() < end else { return XCTFail("timed out \(what)") }
            try await Task.sleep(for: .milliseconds(20))
        }
    }

    /// The writes Spotify was sent, as "PUT /v1/me/player/play?device_id=…".
    private var writes: [String] {
        ConnectStub.requests.filter { $0.method != "GET" }.map { "\($0.method) \($0.path)\($0.query.map { "?" + $0 } ?? "")" }
    }

    private var plays: [ConnectStub.Seen] { ConnectStub.requests.filter { $0.method == "PUT" && $0.path == "/v1/me/player/play" } }

    /// The reads of me/player Spotify answered after the last start (the first is the check that it landed).
    private var readsSinceStart: Int {
        let all = ConnectStub.requests
        guard let i = all.lastIndex(where: { $0.method == "PUT" && $0.path == "/v1/me/player/play" }) else { return 0 }
        return all[i...].filter { $0.method == "GET" && $0.path == "/v1/me/player" }.count
    }

    private func assertNoAppRemote(file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(app.connectionsMade, 0, "App Remote was asked to connect", file: file, line: line)
        XCTAssertEqual(app.wakes, 0, "Spotify was woken through App Remote", file: file, line: line)
        XCTAssertEqual(app.plays, [], file: file, line: line)
    }

    func test_should_startOnThisMacsSpotify_byItsId_when_nothingPlaysAnywhere() async throws {
        ConnectStub.devices = [Self.pixel, Self.mac]
        spotify.control = source
        engine.play(queue: [song("s1"), song("s2")])
        try await waitUntil(4, "for the start") { !self.plays.isEmpty }
        XCTAssertEqual(plays.first?.query, "device_id=mac1")
        // The song and the queue after it, as any Connect device is handed them.
        XCTAssertEqual(plays.first?.uris.prefix(2), ["spotify:track:s1", "spotify:track:s2"])
        // Seen playing there: no failure, and this Mac isn't named as "another device".
        try await waitUntil(6, "for the start to be seen") { self.readsSinceStart >= 1 }
        try await Task.sleep(for: .milliseconds(200))
        XCTAssertNil(engine.failure)
        XCTAssertTrue(engine.isPlaying)
        XCTAssertNil(SpotifyPlayingDevice.shared.name)
        XCTAssertEqual(plays.count, 1)
        assertNoAppRemote()
    }

    func test_should_sayItIsntOpen_and_sendNothing_when_thisMacsSpotifyIsntAmongTheDevices() async throws {
        // Another computer is listed, and a phone: neither takes the start.
        ConnectStub.devices = [Self.pixel, Self.imac]
        spotify.control = source
        engine.play(queue: [song("s1")])
        try await waitUntil(4, "for the failure") { self.engine.failure != nil }
        XCTAssertEqual(engine.failure?.message, SpotifyFailureCopy.notOpenOnMac)
        XCTAssertEqual(engine.failure?.action, .openSpotifyThenRetry)
        XCTAssertFalse(engine.isPlaying)
        try await Task.sleep(for: .milliseconds(300))
        // Not the start, and not the pause the engine answers a failure with.
        XCTAssertEqual(writes, [])
        assertNoAppRemote()

        // Spotify opened on this Mac: Retry plays the song there.
        ConnectStub.devices = [Self.pixel, Self.imac, Self.mac]
        engine.retry()
        try await waitUntil(4, "for the retry's start") { !self.plays.isEmpty }
        XCTAssertEqual(plays.first?.query, "device_id=mac1")
        XCTAssertNil(engine.failure)
        assertNoAppRemote()
    }

    func test_should_neverStartOnAPausedDeviceElsewhere_when_spotifySitsPausedThere() async throws {
        // The Pixel is Spotify's active device, paused on a song (I24).
        ConnectStub.devices = [Self.pixel]
        ConnectStub.set(active: "7a3d", playing: false, track: "spotify:track:old")
        spotify.control = source
        engine.play(queue: [song("s1")])
        try await waitUntil(4, "for the failure") { self.engine.failure != nil }
        XCTAssertEqual(engine.failure?.message, SpotifyFailureCopy.notOpenOnMac)
        XCTAssertEqual(writes, [])

        // With Spotify open here, the song goes to this Mac, not to the paused Pixel.
        ConnectStub.devices = [Self.pixel, Self.mac]
        engine.retry()
        try await waitUntil(4, "for the start") { !self.plays.isEmpty }
        XCTAssertEqual(plays.map(\.query), ["device_id=mac1"])
        assertNoAppRemote()
    }

    func test_should_playOnTheDeviceThatPlays_asItsRemote_when_spotifyPlaysElsewhere() async throws {
        ConnectStub.devices = [Self.pixel, Self.mac]
        ConnectStub.set(active: "7a3d", playing: true, track: "spotify:track:theirs")
        spotify.control = source
        engine.play(queue: [song("s1")])
        try await waitUntil(4, "for the start") { !self.plays.isEmpty }
        XCTAssertEqual(plays.first?.query, "device_id=7a3d")
        XCTAssertEqual(SpotifyPlayingDevice.shared.name, "Pixel 11 Pro")
        // The playing device is the answer: the devices list wasn't needed.
        XCTAssertFalse(ConnectStub.requests.contains { $0.path == "/v1/me/player/devices" })
        assertNoAppRemote()
    }

    func test_should_pauseThroughTheWebAPI_onThisMacsDevice_when_pausedInYoin() async throws {
        ConnectStub.devices = [Self.mac]
        spotify.control = source
        engine.play(queue: [song("s1")])
        try await waitUntil(4, "for the start") { !self.plays.isEmpty }
        // Paused while the start is still landing: the pause waits for it, then goes to this Mac's device.
        engine.pause()
        try await waitUntil(6, "for the pause") { self.writes.contains("PUT /v1/me/player/pause?device_id=mac1") }
        // The read that showed the start landing doesn't flip Yoin back to playing.
        XCTAssertFalse(engine.isPlaying)
        try await Task.sleep(for: .seconds(1))
        XCTAssertFalse(engine.isPlaying)
        XCTAssertNil(engine.failure)
        assertNoAppRemote()
    }

    func test_should_readTheDevicesAgain_and_sayItIsntOpen_when_thisMacsSpotifyQuitUnderTheStart() async throws {
        ConnectStub.devices = [Self.mac]
        // Spotify still lists the Mac, but it has quit: the start is refused, and then it is gone.
        ConnectStub.onPlay = { _ in
            ConnectStub.devices = []
            return 404
        }
        spotify.control = source
        engine.play(queue: [song("s1"), song("s2")])
        try await waitUntil(5, "for the failure") { self.engine.failure != nil }
        XCTAssertEqual(engine.failure?.message, SpotifyFailureCopy.notOpenOnMac)
        // The song is held (not skipped as unplayable), and only the one start went out.
        XCTAssertEqual(engine.currentTrack?.id, "s1")
        XCTAssertEqual(plays.count, 1)
        XCTAssertEqual(writes.filter { $0.contains("pause") }, [])
        assertNoAppRemote()
    }

    func test_should_sendTheSongOnceMore_when_thisMacsSpotifyRefusesTheFirstStart() async throws {
        ConnectStub.devices = [Self.mac]
        // Spotify here is still coming up: the first start is refused, the second lands.
        ConnectStub.onPlay = { nth in nth == 1 ? 404 : 204 }
        spotify.control = source
        engine.play(queue: [song("s1")])
        try await waitUntil(5, "for the second start") { self.plays.count == 2 }
        try await waitUntil(6, "for the start to be seen") { self.readsSinceStart >= 1 }
        try await Task.sleep(for: .milliseconds(200))
        XCTAssertNil(engine.failure)
        XCTAssertTrue(engine.isPlaying)
        XCTAssertEqual(plays.map(\.query), ["device_id=mac1", "device_id=mac1"])
        assertNoAppRemote()
    }

    func test_should_showWhatThisMacPlays_unnamed_andPauseItByItsId_when_yoinOpensWhileSpotifyPlaysHere() async throws {
        ConnectStub.devices = [Self.mac]
        ConnectStub.set(active: "mac1", playing: true, track: "spotify:track:theirs")
        spotify.adopt = { true }
        spotify.control = source
        try await waitUntil(4, "for Spotify's song") { self.engine.currentTrack?.title == "Song theirs" }
        XCTAssertTrue(engine.isPlaying)
        XCTAssertNil(SpotifyPlayingDevice.shared.name)
        XCTAssertEqual(writes, [])
        engine.pause()
        try await waitUntil(4, "for the pause") { self.writes == ["PUT /v1/me/player/pause?device_id=mac1"] }
        assertNoAppRemote()
    }

    func test_should_followAMoveToThisMac_unnamed_when_chosenInDevices() async throws {
        ConnectStub.devices = [Self.pixel, Self.mac]
        ConnectStub.set(active: "7a3d", playing: true, track: "spotify:track:theirs")
        spotify.control = source
        engine.play(queue: [song("s1")])
        try await waitUntil(4, "for the start") { !self.plays.isEmpty }
        XCTAssertEqual(SpotifyPlayingDevice.shared.name, "Pixel 11 Pro")
        // Devices › QA Mac (the sheet made the transfer).
        ConnectStub.set(active: "mac1", playing: true, track: "spotify:track:s1")
        NotificationCenter.default.post(name: SpotifyPlayingDevice.transferred, object: nil,
                                        userInfo: ["id": "mac1", "name": "QA Mac", "type": "Computer"])
        try await waitUntil(2, "for the move") { SpotifyPlayingDevice.shared.name == nil }
        engine.pause()
        try await waitUntil(6, "for the pause") { self.writes.contains("PUT /v1/me/player/pause?device_id=mac1") }
        assertNoAppRemote()
    }

    /// I23: on an iPad, its own Spotify is "here" (App Remote), not another device to be the remote of.
    func test_should_startThroughAppRemote_notAsARemote_when_thisIPadIsWhatPlays() async throws {
        make(.tablet)
        ConnectStub.devices = [ConnectStub.Device(id: "pad1", name: "51's iPad", type: "Tablet")]
        ConnectStub.set(active: "pad1", playing: true, track: "spotify:track:theirs")
        spotify.control = source
        engine.play(queue: [song("s1")])
        try await waitUntil(4, "for App Remote's play") { self.app.plays == ["spotify:track:s1"] }
        XCTAssertEqual(plays.count, 0, "the iPad's own Spotify isn't started through the Web API")
        XCTAssertNil(SpotifyPlayingDevice.shared.name)
    }
}

/// A small Spotify Connect behind URLProtocol: the account's devices, the one that is active and what it
/// plays; a start names a device (404 when it isn't listed) and makes it the one playing.
final class ConnectStub: URLProtocol {
    struct Device: Sendable {
        var id: String
        var name: String
        var type: String
    }

    struct Seen: Sendable {
        var method: String
        var path: String
        var query: String?
        var uris: [String] = []
    }

    private struct State {
        var devices: [Device] = []
        var active: String?
        var playing = false
        var track: String?
        var startedAt = Date()
        var seen: [Seen] = []
        var playCalls = 0
        var onPlay: (@Sendable (Int) -> Int)?
    }

    private static let lock = NSLock()
    nonisolated(unsafe) private static var state = State()

    static func reset() { lock.withLock { state = State() } }

    static var devices: [Device] {
        get { lock.withLock { state.devices } }
        set { lock.withLock { state.devices = newValue } }
    }

    /// The status a start gets ([nth] start, from 1); nil: 204, or 404 for a device that isn't listed.
    static var onPlay: (@Sendable (Int) -> Int)? {
        get { lock.withLock { state.onPlay } }
        set { lock.withLock { state.onPlay = newValue } }
    }

    static func set(active: String?, playing: Bool, track: String?) {
        lock.withLock {
            state.active = active
            state.playing = playing
            state.track = track
            state.startedAt = Date()
        }
    }

    static var requests: [Seen] { lock.withLock { state.seen } }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    private static func body(_ request: URLRequest) -> Data {
        if let data = request.httpBody { return data }
        guard let stream = request.httpBodyStream else { return Data() }
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 4096)
        while stream.hasBytesAvailable {
            let n = stream.read(&buffer, maxLength: buffer.count)
            guard n > 0 else { break }
            data.append(buffer, count: n)
        }
        return data
    }

    private static func item(_ uri: String) -> String {
        let id = uri.split(separator: ":").last.map(String.init) ?? uri
        return #"{"uri":"\#(uri)","duration_ms":200000,"name":"Song \#(id)","artists":[{"name":"Them"}],"album":{"name":"Theirs","images":[]}}"#
    }

    override func startLoading() {
        let url = request.url!
        let method = request.httpMethod ?? "GET"
        let query = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        let deviceId = query.first { $0.name == "device_id" }?.value
        var seen = Seen(method: method, path: url.path, query: url.query)
        if method == "PUT", url.path.hasSuffix("/me/player/play"),
           let json = try? JSONSerialization.jsonObject(with: Self.body(request)) as? [String: Any] {
            seen.uris = json["uris"] as? [String] ?? []
        }
        var onPlay: (@Sendable (Int) -> Int)?
        var nth = 0
        Self.lock.withLock {
            Self.state.seen.append(seen)
            if method == "PUT", url.path.hasSuffix("/me/player/play") {
                Self.state.playCalls += 1
                nth = Self.state.playCalls
                onPlay = Self.state.onPlay
            }
        }
        // Outside the lock: the hook may change the devices.
        let forced = onPlay.map { $0(nth) }
        let (status, body): (Int, String) = Self.lock.withLock {
            var s = Self.state
            defer { Self.state = s }
            let listed = deviceId.map { id in s.devices.contains { $0.id == id } } ?? (s.active != nil)
            switch (method, url.path) {
            case ("GET", let p) where p.hasSuffix("/me/player/devices"):
                let list = s.devices.map {
                    #"{"id":"\#($0.id)","name":"\#($0.name)","type":"\#($0.type)","is_active":\#($0.id == s.active)}"#
                }
                return (200, #"{"devices":[\#(list.joined(separator: ","))]}"#)
            case ("GET", let p) where p.hasSuffix("/me/player"):
                guard let id = s.active, let d = s.devices.first(where: { $0.id == id }) else { return (204, "") }
                let progress = s.playing ? 1000 + Int(Date().timeIntervalSince(s.startedAt) * 1000) : 1000
                return (200, #"{"device":{"id":"\#(d.id)","name":"\#(d.name)","type":"\#(d.type)","is_active":true},"is_playing":\#(s.playing),"progress_ms":\#(progress),"shuffle_state":false,"repeat_state":"off","item":\#(s.track.map(Self.item) ?? "null")}"#)
            case ("PUT", let p) where p.hasSuffix("/me/player/play"):
                if let forced, forced != 204 { return (forced, #"{"error":{"status":\#(forced),"message":"Device not found"}}"#) }
                guard listed else { return (404, #"{"error":{"status":404,"message":"Device not found"}}"#) }
                if let deviceId { s.active = deviceId }
                if let first = seen.uris.first { s.track = first }
                s.playing = true
                s.startedAt = Date()
                return (204, "")
            case ("PUT", let p) where p.hasSuffix("/me/player/pause"):
                guard listed else { return (404, #"{"error":{"status":404,"message":"Device not found"}}"#) }
                s.playing = false
                return (204, "")
            default:
                return (204, "")
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
