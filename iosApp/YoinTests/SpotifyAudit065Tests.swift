import AVKit
import SpotifyiOS
import UIKit
import XCTest
@testable import Yoin

@MainActor
final class SpotifyAudit065Tests: XCTestCase {
    private var defaultContextRetry: Double = 2

    override func setUp() {
        super.setUp()
        defaultContextRetry = SpotifyPlayingFrom.contextRetryDelaySec
        AuditStubProtocol.reset()
        SpotifyPlayingDevice.shared.name = nil
        SpotifyPlayingFrom.shared.reset()
    }

    override func tearDown() {
        SpotifyPlayingFrom.contextRetryDelaySec = defaultContextRetry
        SpotifyPlayingDevice.shared.name = nil
        SpotifyPlayingFrom.shared.reset()
        AuditStubProtocol.reset()
        super.tearDown()
    }

    private func makeAPI(gate: SpotifyRateGate = SpotifyRateGate(), maxRetries: Int = 0) -> SpotifyWebAPI {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.protocolClasses = [AuditStubProtocol.self]
        return SpotifyWebAPI(
            token: { "tok" },
            gate: gate,
            session: URLSession(configuration: cfg),
            maxRetries: maxRetries,
            sleep: { _ in }
        )
    }

    // MARK: - P0-1: Wake / POSIX 61 & Dual-Connect Race

    func test_should_classifyPosix61InUnderlyingErrorChain_asLocalRefused() {
        let posix = NSError(domain: NSPOSIXErrorDomain, code: Int(ECONNREFUSED),
                            userInfo: [NSLocalizedDescriptionKey: "Connection refused"])
        let wrapped = NSError(domain: "com.spotify.app-remote", code: -1000,
                              userInfo: [NSUnderlyingErrorKey: posix, NSLocalizedDescriptionKey: "Connection attempt failed"])
        let failure = SpotifyRemote.ConnectFailure.classify(error: wrapped, message: wrapped.localizedDescription)
        XCTAssertEqual(failure, .localRefused(wrapped.localizedDescription))
        XCTAssertTrue(SpotifyRemote.ConnectFailure.isLocalConnectionRefused(wrapped))

        let authErr = SpotifyRemote.ConnectFailure.classify(error: nil, message: "invalid_grant: token revoked")
        XCTAssertEqual(authErr, .auth("invalid_grant: token revoked"))

        let noId = SpotifyRemote.ConnectFailure.classify(error: nil, message: "missing client_id", hasClientId: false)
        XCTAssertEqual(noId, .noClientId)

        let premium = SpotifyRemote.ConnectFailure.classify(error: nil, message: "Spotify Premium required")
        XCTAssertEqual(premium, .premiumRequired)
    }

    func test_should_avoidDuplicateConnect_when_handbackArrivesWhileConnectIsInFlight() async {
        let link = AuditFakeAppRemoteLink()
        link.handbackResult = ("tok-1", nil)
        let remote = SpotifyRemote(makeLink: { _, _ in link }, clientId: { "cid" }, enabled: { true })
        remote.bind(account: "acc-1")

        let task = Task { await remote.connect(timeout: 0.4) }
        await Task.yield()
        XCTAssertEqual(link.connectCalls, 1)

        // App Remote handback while first connect attempt is already in flight must NOT fire a 2nd connect().
        let handled = remote.handle(URL(string: "\(SpotifyConfig.appRemoteRedirectURI)?code=1")!)
        XCTAssertTrue(handled)
        XCTAssertEqual(link.connectCalls, 1)

        link.establish()
        let ok = await task.value
        XCTAssertTrue(ok)
    }

    func test_should_resolveAuthAndMissingTokenHandbacks_withoutHangingWaiters() {
        let link = AuditFakeAppRemoteLink()
        var failures: [SpotifyRemote.ConnectFailure] = []
        let remote = SpotifyRemote(makeLink: { _, _ in link }, clientId: { "cid" }, enabled: { true })
        remote.bind(account: "acc-1")
        remote.onConnectFailure = { failures.append($0) }

        link.handbackResult = (nil, "access_denied")
        XCTAssertTrue(remote.handle(URL(string: "\(SpotifyConfig.appRemoteRedirectURI)?error=access_denied")!))
        if case .auth = failures.last {} else {
            XCTFail("Expected .auth connect failure, got \(String(describing: failures.last))")
        }

        link.handbackResult = (nil, nil)
        XCTAssertTrue(remote.handle(URL(string: SpotifyConfig.appRemoteRedirectURI)!))
        if case .auth = failures.last {} else {
            XCTFail("Expected .auth connect failure when token and error are missing")
        }
    }

    func test_should_fastConfirmOverWebWithout8sFreeze_when_wakeHitsPosix61() async throws {
        let link = AuditFakeAppRemoteLink()
        let api = makeAPI()
        let source = SpotifySource(api: api)
        let events = AuditAudioOutputEvents()

        AuditStubProtocol.route = { req, _ in
            if req.url?.path.hasSuffix("/me/player") == true {
                return .init(status: 200, body: #"""
                {"device":{"id":"iph-1","name":"iPhone","type":"Smartphone","is_active":true},
                 "is_playing":true,"progress_ms":12000,"shuffle_state":false,"repeat_state":"off",
                 "item":{"uri":"spotify:track:t1","duration_ms":210000,"name":"Song","artists":[{"name":"Artist"}],"album":{"name":"Alb","images":[]}}}
                """#)
            }
            return .init(status: 204, body: "")
        }

        let remote = SpotifyRemote(makeLink: { _, _ in link }, clientId: { "cid" }, enabled: { true })
        remote.bind(account: "acc-1")
        let output = SpotifyAudioOutput(remote: remote, spotifyInstalled: { true }, device: .phone)
        output.control = source
        output.events = events

        let startAt = Date()
        output.load(SpotifyAudioOutput.streamURL(trackId: "t1"), next: nil, startSec: 0)
        // Wait until startHere finishes initial connect and enters awaitingWake -> wake().
        for _ in 0..<40 where link.wakeCount == 0 {
            try? await Task.sleep(for: .milliseconds(50))
        }
        XCTAssertEqual(link.wakeCount, 1)

        // Bounce back and hit POSIX 61 ECONNREFUSED via onConnectFailedError.
        let posix61 = NSError(domain: NSPOSIXErrorDomain, code: Int(ECONNREFUSED))
        link.failConnect(error: posix61, message: "ECONNREFUSED")

        // Must confirm cleanly via me/player in ~0.25-1.5s, well under wakeDeadlineSec (8s).
        for _ in 0..<40 where events.durations.isEmpty {
            try? await Task.sleep(for: .milliseconds(50))
        }
        let elapsed = Date().timeIntervalSince(startAt)
        XCTAssertLessThan(elapsed, 3.0)
        XCTAssertEqual(events.durations.last, 210)
        XCTAssertTrue(events.failures.isEmpty)
        output.control = nil
        output.stop()
        _ = source
    }

    func test_should_abortWithoutWake_when_connectFailsWithAuthError() async throws {
        let link = AuditFakeAppRemoteLink()
        link.autoFailNextConnect = (nil, "invalid_grant revoked")
        let remote = SpotifyRemote(makeLink: { _, _ in link }, clientId: { "cid" }, enabled: { true })
        remote.bind(account: "acc-1")
        let events = AuditAudioOutputEvents()
        let output = SpotifyAudioOutput(remote: remote, spotifyInstalled: { true }, device: .phone)
        output.events = events

        output.load(SpotifyAudioOutput.streamURL(trackId: "t1"), next: nil, startSec: 0)
        for _ in 0..<30 where events.failures.isEmpty {
            try? await Task.sleep(for: .milliseconds(50))
        }
        XCTAssertEqual(link.wakeCount, 0)
        XCTAssertFalse(events.failures.isEmpty)
        XCTAssertTrue(events.failures.first?.contains(String(localized: "Reconnect Spotify in Settings")) == true)
        output.stop()
    }

    // MARK: - P0-2: PR #260 Zombie Speaker Deadlock

    func test_should_clearZombieDeviceAndTargetAfterConsecutive204_evenWhenFailedHere_andStopRetryLoop() async throws {
        let link = AuditFakeAppRemoteLink()
        let remote = SpotifyRemote(makeLink: { _, _ in link }, clientId: { "cid" }, enabled: { false })
        let api = makeAPI()
        let source = SpotifySource(api: api)
        let routeMode = AuditBox(0)
        let resumeCalls = AuditBox(0)
        AuditStubProtocol.route = { req, _ in
            if req.url?.path.hasSuffix("/me/player/play") == true {
                resumeCalls.withValue { $0 += 1 }
                return .init(status: 404, body: #"{"error":{"status":404,"message":"Device not found"}}"#)
            }
            if req.url?.path.hasSuffix("/me/player") == true {
                if routeMode.value == 0 {
                    return .init(status: 200, body: #"""
                    {"device":{"id":"spk-dead","name":"Kitchen Speaker","type":"Speaker","is_active":true},
                     "is_playing":true,"progress_ms":5000,"shuffle_state":false,"repeat_state":"off",
                     "item":{"uri":"spotify:track:ext1","duration_ms":180000,"name":"Ext","artists":[{"name":"Art"}],"album":{"name":"Alb","images":[]}}}
                    """#)
                }
                return .init(status: 204, body: "")
            }
            return .init(status: 204, body: "")
        }

        let output = SpotifyAudioOutput(remote: remote, spotifyInstalled: { false }, device: .phone)
        output.control = source
        output.adopt = { true }
        let engine = PlayerEngine(output: output)

        // Adopt the playing external speaker.
        output.watch()
        for _ in 0..<20 where engine.externalTrack == nil {
            try? await Task.sleep(for: .milliseconds(50))
        }
        XCTAssertEqual(SpotifyPlayingDevice.shared.name, "Kitchen Speaker")
        XCTAssertNotNil(engine.externalTrack)

        // Command fails with 404 (failedHere = true).
        output.play()
        for _ in 0..<30 where engine.failure == nil {
            try? await Task.sleep(for: .milliseconds(50))
        }
        XCTAssertNotNil(engine.failure)
        let beforeCalls = resumeCalls.value

        // Speaker returns consecutive 204s while failedHere == true: device name & target MUST clear cleanly.
        routeMode.value = 1
        await output.readOnce()
        await output.readOnce()
        XCTAssertNil(SpotifyPlayingDevice.shared.name)

        // Re-verify PlayerEngine.retry() never fires 404 resumePlayback when target is dead.
        engine.outputExternalTrackDidChange(engine.externalTrack ?? PlayableTrack(
            id: "ext1", provider: SpotifyMapping.provider, title: "Ext", artist: "Art", album: "",
            albumId: nil, durationSec: 180, streamURL: SpotifyAudioOutput.streamURL(trackId: "ext1"), coverURL: nil
        ), positionSec: 5, playing: false)
        engine.outputDidFail("Speaker unavailable", network: true)
        engine.retry()
        try? await Task.sleep(for: .milliseconds(100))
        XCTAssertNil(engine.externalTrack)
        XCTAssertEqual(resumeCalls.value, beforeCalls)
        output.control = nil
        output.stop()
    }

    // MARK: - P0-3: Companion enqueueOnSpotify on Mac & POSIX 61

    func test_should_fallbackEnqueueOnSpotifyToWebAPI_onMacAndWhenLocalConnectFails() async throws {
        let queuedURIs = AuditBox<[String]>([])
        AuditStubProtocol.route = { req, _ in
            if req.url?.path.hasSuffix("/me/player/queue") == true, req.httpMethod == "POST" {
                let uri = URLComponents(url: req.url!, resolvingAgainstBaseURL: false)?
                    .queryItems?.first(where: { $0.name == "uri" })?.value ?? ""
                queuedURIs.withValue { $0.append(uri) }
                return .init(status: 204, body: "")
            }
            return .init(status: 204, body: "")
        }
        let source = SpotifySource(api: makeAPI())

        // 1) Mac (!isHere) -> immediately uses Web API POST /v1/me/player/queue
        let macLink = AuditFakeAppRemoteLink()
        let macRemote = SpotifyRemote(makeLink: { _, _ in macLink }, clientId: { "cid" }, enabled: { false })
        let macOut = SpotifyAudioOutput(remote: macRemote, spotifyInstalled: { false }, device: .mac(["My Mac"]))
        macOut.control = source

        let macDone = expectation(description: "mac enqueue")
        macOut.enqueueOnSpotify(["tr-mac-1", "tr-mac-2"]) { ok in
            XCTAssertTrue(ok)
            macDone.fulfill()
        }
        await fulfillment(of: [macDone], timeout: 3)

        // 2) iPhone with POSIX 61 ECONNREFUSED on connect -> falls back to Web API POST /v1/me/player/queue
        let iosLink = AuditFakeAppRemoteLink()
        let posix61 = NSError(domain: NSPOSIXErrorDomain, code: Int(ECONNREFUSED))
        iosLink.autoFailNextConnect = (posix61, "ECONNREFUSED")
        let iosRemote = SpotifyRemote(makeLink: { _, _ in iosLink }, clientId: { "cid" }, enabled: { true })
        iosRemote.bind(account: "acc-1")
        let iosOut = SpotifyAudioOutput(remote: iosRemote, spotifyInstalled: { true }, device: .phone)
        iosOut.control = source

        let iosDone = expectation(description: "ios fallback enqueue")
        iosOut.enqueueOnSpotify(["tr-ios-1"]) { ok in
            XCTAssertTrue(ok)
            iosDone.fulfill()
        }
        await fulfillment(of: [iosDone], timeout: 3)

        XCTAssertEqual(queuedURIs.value, ["spotify:track:tr-mac-1", "spotify:track:tr-mac-2", "spotify:track:tr-ios-1"])
    }

    // MARK: - P0-4 & P0-5: Clock Leak, Carried-On Guard, Repeat-1, & Early Return Sync

    func test_should_ignoreOldTrackClock_whileStarting_andSyncOnAdvance() async {
        let link = AuditFakeAppRemoteLink()
        link.isConnected = true
        let remote = SpotifyRemote(makeLink: { _, _ in link }, clientId: { "cid" }, enabled: { true })
        remote.bind(account: "acc-1")
        let events = AuditAudioOutputEvents()
        events.snapshot = OutputQueueSnapshot(urls: [
            SpotifyAudioOutput.streamURL(trackId: "a"),
            SpotifyAudioOutput.streamURL(trackId: "b")
        ], userQueued: false, shuffle: false, repeatMode: .off)

        let output = SpotifyAudioOutput(remote: remote, spotifyInstalled: { true }, device: .phone)
        output.events = events

        // P0-5: While starting == true, old song state push must NOT mutate time or duration.
        output.load(SpotifyAudioOutput.streamURL(trackId: "a"),
                    next: SpotifyAudioOutput.streamURL(trackId: "b"), startSec: 0)
        // Before connect/start settles, an old song state arrives on link.onPlayerState:
        link.onPlayerState?(RemotePlayerState(
            uri: "spotify:track:old", isPaused: false, positionMs: 140_000, durationMs: 250_000,
            shuffling: false, repeatRaw: 0
        ))
        XCTAssertEqual(output.currentTimeSec, 0, accuracy: 0.25)
        XCTAssertTrue(events.durations.isEmpty)

        // Let startHere finish connecting link -> remote.isConnected becomes true.
        for _ in 0..<20 where !remote.isConnected {
            try? await Task.sleep(for: .milliseconds(20))
        }

        // P0-4 Repeat-1: queue.count == 1 -> remote.setRepeat(.track); > 1 -> .off
        events.snapshot = OutputQueueSnapshot(urls: [SpotifyAudioOutput.streamURL(trackId: "a")],
                                              userQueued: false, shuffle: false, repeatMode: .one)
        output.playModesDidChange(shuffle: false, repeatMode: .one)
        XCTAssertEqual(link.repeatModes.last, .track)

        events.snapshot = OutputQueueSnapshot(urls: [
            SpotifyAudioOutput.streamURL(trackId: "a"),
            SpotifyAudioOutput.streamURL(trackId: "b")
        ], userQueued: false, shuffle: false, repeatMode: .one)
        output.playModesDidChange(shuffle: false, repeatMode: .one)
        XCTAssertEqual(link.repeatModes.last, .off)
        output.stop()
    }

    func test_should_notHijackPausedEndSwitch_inCarriedOnWindow_butShouldAdvancePausedJumpSync() {
        // P0-4: pausing near end (2:57/3:00) and picking another track must NOT hijack back to Yoin
        XCTAssertFalse(SpotifyAudioOutput.shouldCarryOnAtTrackChange(
            wasPlaying: false, incomingPaused: false, durationSec: 180,
            reachedSec: 177, sinceRemoteStateSec: 12, isStaleHandoff: false
        ))
        XCTAssertFalse(SpotifyAudioOutput.shouldCarryOnAtTrackChange(
            wasPlaying: true, incomingPaused: true, durationSec: 180,
            reachedSec: 177, sinceRemoteStateSec: 12, isStaleHandoff: false
        ))
        XCTAssertFalse(SpotifyAudioOutput.shouldCarryOnAtTrackChange(
            wasPlaying: true, incomingPaused: false, durationSec: 180,
            reachedSec: 250, sinceRemoteStateSec: 250, isStaleHandoff: false
        ))
        XCTAssertTrue(SpotifyAudioOutput.shouldCarryOnAtTrackChange(
            wasPlaying: true, incomingPaused: false, durationSec: 180,
            reachedSec: 177, sinceRemoteStateSec: 180, isStaleHandoff: false
        ))
    }

    // MARK: - P1-1: PlayingFrom daylist / 37i9dQZ* & 2s Retry

    func test_should_keepPlaylistLabelFor37i9dQZ_andRetryOnceOnEmptyContext() async {
        XCTAssertEqual(SpotifyPlayingFrom.nameKey("https://open.spotify.com/playlist/37i9dQZF1EP6YuccBxUiC1?si=x"),
                       "playlist:37i9dQZF1EP6YuccBxUiC1")
        let fallback = SpotifyPlayingFrom.playlistLabel(id: "37i9dQZF1EP6YuccBxUiC1", name: nil)
        XCTAssertEqual(fallback?.kind, String(localized: "PLAYING FROM PLAYLIST"))
        XCTAssertEqual(fallback?.name, "Spotify")
        XCTAssertNil(fallback?.route)

        let named = SpotifyPlayingFrom.playlistLabel(id: "37i9dQZF1EP6YuccBxUiC1", name: "daylist")
        XCTAssertEqual(named?.kind, String(localized: "PLAYING FROM PLAYLIST"))
        XCTAssertEqual(named?.name, "daylist")
        XCTAssertNil(named?.route)

        // observe() + 1 delayed retry when Web API returns empty first, then 37i9dQZ context returning 404 on details
        SpotifyPlayingFrom.contextRetryDelaySec = 0.02
        let mePlayerCalls = AuditBox(0)
        AuditStubProtocol.route = { req, _ in
            if req.url?.path.hasSuffix("/me/player") == true {
                let count = mePlayerCalls.withValue { $0 += 1; return $0 }
                if count == 1 { return .init(status: 204, body: "") }
                return .init(status: 200, body: #"{"context":{"uri":"spotify:playlist:37i9dQZF1EP6YuccBxUiC1"},"item":{"id":"t-day"}}"#)
            }
            if req.url?.path.contains("/playlists/37i9dQZF1EP6YuccBxUiC1") == true {
                return .init(status: 404, body: "")
            }
            return .init(status: 404, body: "")
        }

        let source = SpotifySource(api: makeAPI())
        let track = PlayableTrack(id: "t-day", provider: SpotifyMapping.provider, title: "Day", artist: "Art",
                                  album: "Alb", albumId: "al1", durationSec: 120,
                                  streamURL: SpotifyAudioOutput.streamURL(trackId: "t-day"), coverURL: nil)

        SpotifyPlayingFrom.shared.observe(track, source: source)
        for _ in 0..<40 where SpotifyPlayingFrom.shared.label(for: track) == nil {
            try? await Task.sleep(for: .milliseconds(25))
        }
        let resolved = SpotifyPlayingFrom.shared.label(for: track)
        XCTAssertEqual(resolved?.kind, String(localized: "PLAYING FROM PLAYLIST"))
        XCTAssertEqual(resolved?.name, "Spotify")
        XCTAssertNil(resolved?.route)

        // App Remote pushing contextTitle ("daylist") upgrades placeholder cleanly.
        SpotifyPlayingFrom.shared.heard(trackURI: "spotify:track:t-day",
                                        contextURI: "spotify:playlist:37i9dQZF1EP6YuccBxUiC1",
                                        title: "daylist")
        XCTAssertEqual(SpotifyPlayingFrom.shared.label(for: track)?.name, "daylist")
        XCTAssertNil(SpotifyPlayingFrom.shared.label(for: track)?.route)
    }

    // MARK: - P1-4, P1-5, P1-7, P1-8, P2-1, P2-2

    func test_should_matchRelinkedSongByTitleAndFirstArtist_inIsSameSongAndJumpTarget() {
        XCTAssertTrue(SpotifyAudioOutput.isSameSong(
            title: "Yesterday", artist: "The Beatles, John Lennon",
            title: "yesterday", artist: "The Beatles"
        ))
        XCTAssertFalse(SpotifyAudioOutput.isSameSong(
            title: "Yesterday", artist: "The Beatles",
            title: "Yesterday", artist: "Paul McCartney"
        ))

        let targetItem = SpotifyAudioOutput.Item(SpotifyAudioOutput.streamURL(trackId: "orig-2"))!
        let snapshot = OutputQueueSnapshot(urls: [
            SpotifyAudioOutput.streamURL(trackId: "orig-1"),
            SpotifyAudioOutput.streamURL(trackId: "orig-2")
        ], userQueued: false, shuffle: false, repeatMode: .off)
        let jumped = SpotifyAudioOutput.jumpTarget(
            uri: "spotify:track:relinked-2",
            handed: [],
            upcoming: [targetItem],
            context: nil,
            snapshot: snapshot,
            title: "Blue Sky",
            artist: "Main Artist",
            metadata: { $0 == "orig-2" ? ("Blue Sky", "Main Artist, Guest") : nil }
        )
        XCTAssertEqual(jumped, targetItem)
    }

    func test_should_returnFromExternalTrackToYoinQueue_whenHandedNextOrUpcomingPlays() async {
        let link = AuditFakeAppRemoteLink()
        link.isConnected = true
        let remote = SpotifyRemote(makeLink: { _, _ in link }, clientId: { "cid" }, enabled: { true })
        remote.bind(account: "acc-1")
        let events = AuditAudioOutputEvents()
        let urlA = SpotifyAudioOutput.streamURL(trackId: "a")
        let urlB = SpotifyAudioOutput.streamURL(trackId: "b")
        events.upcomingURLs = [urlB]
        events.snapshot = OutputQueueSnapshot(urls: [urlA, urlB], userQueued: false, shuffle: false, repeatMode: .off)

        let output = SpotifyAudioOutput(remote: remote, spotifyInstalled: { true }, device: .phone)
        output.events = events
        output.load(urlA, next: urlB, startSec: 0)
        // Wait for startHere Task -> remote.connect -> startSingleHere -> startedHere (starting = false).
        for _ in 0..<20 where link.playedURIs.isEmpty {
            try? await Task.sleep(for: .milliseconds(20))
        }
        // Track A reaches end window (<15s left) so track B is handed into Spotify's queue (handedNext = b).
        link.onPlayerState?(RemotePlayerState(uri: "spotify:track:a", isPaused: false, positionMs: 190_000, durationMs: 200_000, shuffling: false, repeatRaw: 0))
        // Switch in Spotify to unrelated track E (demoted to externalTrack).
        link.onPlayerState?(RemotePlayerState(uri: "spotify:track:ext", isPaused: false, positionMs: 5000, durationMs: 180_000, shuffling: false, repeatRaw: 0, title: "Ext", artist: "Art"))
        XCTAssertNotNil(events.externalUpdates.last?.track)

        // Now Spotify advances from ext to queued track B (handedNext == next): MUST lift externalURI and advance!
        link.onPlayerState?(RemotePlayerState(uri: "spotify:track:b", isPaused: false, positionMs: 500, durationMs: 220_000, shuffling: false, repeatRaw: 0, title: "B", artist: "Art"))
        XCTAssertGreaterThanOrEqual(events.advancedCount, 1)
        XCTAssertNil(events.externalUpdates.last?.track)
        XCTAssertEqual(events.durations.last, 220)
        output.stop()
    }

    func test_should_updateDeviceNameWithNilId_andNotPauseOnFailedSeek() async throws {
        let link = AuditFakeAppRemoteLink()
        let remote = SpotifyRemote(makeLink: { _, _ in link }, clientId: { "cid" }, enabled: { false })
        let events = AuditAudioOutputEvents()
        let seekStatus = AuditBox(500)
        AuditStubProtocol.route = { req, _ in
            if req.url?.path.hasSuffix("/me/player/seek") == true {
                return .init(status: seekStatus.value, body: "")
            }
            if req.url?.path.hasSuffix("/me/player") == true {
                return .init(status: 200, body: #"""
                {"device":{"id":null,"name":"Living Room Sonos","type":"Speaker","is_active":true},
                 "is_playing":true,"progress_ms":10000,"shuffle_state":false,"repeat_state":"off",
                 "item":{"uri":"spotify:track:ext1","duration_ms":200000,"name":"Sonos Song","artists":[{"name":"Art"}],"album":{"name":"Alb","images":[]}}}
                """#)
            }
            return .init(status: 204, body: "")
        }
        let source = SpotifySource(api: makeAPI())
        let output = SpotifyAudioOutput(remote: remote, spotifyInstalled: { false }, device: .phone)
        output.control = source
        output.events = events
        output.adopt = { true }

        output.watch()
        for _ in 0..<20 where SpotifyPlayingDevice.shared.name == nil {
            try? await Task.sleep(for: .milliseconds(50))
        }
        XCTAssertEqual(SpotifyPlayingDevice.shared.name, "Living Room Sonos")

        // Seek fails on restricted speaker: must NOT flip external playing to false.
        seekStatus.value = 403
        output.seek(to: 45)
        try? await Task.sleep(for: .milliseconds(150))
        XCTAssertFalse(events.externalPlayChanges.contains(false))
        output.control = nil
        output.stop()
    }

    func test_should_persistLearnedDeviceIdPerProfile_andOmitDeviceIdOnLocalActiveResume() async throws {
        let suiteName = "SpotifyAudit065Tests.Device.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }

        SpotifyThisDevice.saveLearnedId("dev-renamed-id", profileId: "prof-1", kind: .phone, defaults: defaults)
        let loaded = SpotifyThisDevice.loadLearnedId(profileId: "prof-1", kind: .phone, defaults: defaults)
        XCTAssertEqual(loaded, "dev-renamed-id")
        XCTAssertNil(SpotifyThisDevice.loadLearnedId(profileId: "prof-2", kind: .phone, defaults: defaults))

        let renamedThis = SpotifyThisDevice.phone.withLearnedId(loaded)
        XCTAssertTrue(renamedThis.matches(id: "dev-renamed-id", name: "Pocket", type: "Smartphone"))
        XCTAssertFalse(renamedThis.matches(id: "other-id", name: "Pocket", type: "Smartphone"))

        let devices = [
            SpotifySource.Device(id: "other", name: "Work iPhone", type: "Smartphone", isActive: true),
            SpotifySource.Device(id: "dev-renamed-id", name: "Pocket", type: "Smartphone", isActive: false),
        ]
        XCTAssertEqual(renamedThis.pick(devices)?.id, "dev-renamed-id")

        // Verify play() Web fallback passes deviceId: nil when local device is active on me/player.
        let playQueryDeviceId = AuditBox<String?>("sentinel")
        AuditStubProtocol.route = { req, _ in
            if req.url?.path.hasSuffix("/me/player/play") == true {
                let param = URLComponents(url: req.url!, resolvingAgainstBaseURL: false)?
                    .queryItems?.first(where: { $0.name == "device_id" })?.value
                playQueryDeviceId.value = param
                return .init(status: 204, body: "")
            }
            if req.url?.path.hasSuffix("/me/player") == true {
                return .init(status: 200, body: #"""
                {"device":{"id":"iph-local","name":"iPhone","type":"Smartphone","is_active":true},
                 "is_playing":false,"progress_ms":5000,"shuffle_state":false,"repeat_state":"off",
                 "item":{"uri":"spotify:track:s1","duration_ms":200000,"name":"S1","artists":[{"name":"Art"}],"album":{"name":"Alb","images":[]}}}
                """#)
            }
            return .init(status: 204, body: "")
        }

        let link = AuditFakeAppRemoteLink()
        link.autoFailNextConnect = (NSError(domain: NSPOSIXErrorDomain, code: Int(ECONNREFUSED)), "refused")
        let remote = SpotifyRemote(makeLink: { _, _ in link }, clientId: { "cid" }, enabled: { true })
        remote.bind(account: "acc-local")
        let source = SpotifySource(api: makeAPI())
        let output = SpotifyAudioOutput(remote: remote, spotifyInstalled: { true }, device: .phone)
        output.control = source
        output.play()
        for _ in 0..<40 where playQueryDeviceId.value == "sentinel" {
            try? await Task.sleep(for: .milliseconds(50))
        }
        XCTAssertNil(playQueryDeviceId.value)
        _ = source
        output.control = nil
        output.stop()
    }

    func test_should_supportSpotifyLocalAsExternalTrack_andConsumePendingResumeOnWebRead() async throws {
        // P2-1: spotify:local:...
        let localState = SpotifyAudioOutput.State(
            uri: "spotify:local:Artist:Album:My+Track:180",
            isPaused: false, positionMs: 1000, durationMs: 180_000,
            shuffling: false, repeatRaw: 0, title: "My Track", artist: "Artist", album: "Album"
        )
        let localTrack = SpotifyAudioOutput.externalTrack(localState)
        XCTAssertEqual(localTrack?.title, "My Track")
        XCTAssertEqual(localTrack?.id, "spotify:local:Artist:Album:My+Track:180")

        // P2-2: pendingResume consumed on Web API read when local device sits paused on track.
        let resumeSent = AuditBox(0)
        let routeStep = AuditBox(0)
        AuditStubProtocol.route = { req, _ in
            if req.url?.path.hasSuffix("/me/player/play") == true {
                resumeSent.withValue { $0 += 1 }
                return .init(status: 204, body: "")
            }
            if req.url?.path.hasSuffix("/me/player") == true {
                if routeStep.value == 0 {
                    // During commandOverWeb failure: nothing active
                    return .init(status: 204, body: "")
                }
                // Next poll: local device paused on external track
                return .init(status: 200, body: #"""
                {"device":{"id":"iph-2","name":"iPhone","type":"Smartphone","is_active":true},
                 "is_playing":false,"progress_ms":5000,"shuffle_state":false,"repeat_state":"off",
                 "item":{"uri":"spotify:track:ext2","duration_ms":200000,"name":"Ext2","artists":[{"name":"Art"}],"album":{"name":"Alb","images":[]}}}
                """#)
            }
            return .init(status: 204, body: "")
        }

        let link = AuditFakeAppRemoteLink()
        link.autoFailNextConnect = (nil, "asleep")
        let remote = SpotifyRemote(makeLink: { _, _ in link }, clientId: { "cid" }, enabled: { true })
        remote.bind(account: "acc-1")
        let source = SpotifySource(api: makeAPI())
        let events = AuditAudioOutputEvents()
        let output = SpotifyAudioOutput(remote: remote, spotifyInstalled: { true }, device: .phone)
        output.control = source
        output.events = events
        output.adopt = { true }
        output.canAdoptPaused = { true }

        // Put output in adopted paused state via Web watch, then attempt play() which fails and stores pendingResume
        routeStep.value = 1
        output.watch()
        for _ in 0..<20 where events.externalUpdates.isEmpty {
            try? await Task.sleep(for: .milliseconds(50))
        }
        routeStep.value = 0
        output.play()
        for _ in 0..<30 where events.failures.isEmpty {
            try? await Task.sleep(for: .milliseconds(50))
        }
        // Now Web API reads the local device paused on ext2 -> MUST consume pendingResume and send play!
        routeStep.value = 1
        await output.readOnce()
        for _ in 0..<30 where resumeSent.value == 0 {
            try? await Task.sleep(for: .milliseconds(50))
        }
        XCTAssertEqual(resumeSent.value, 1)
        output.control = nil
        output.stop()
    }

    // MARK: - P1-6 Backfill & P1-9 Persisted RateGate + Custom Headers

    func test_should_resolveBackfillSinceWhenPausedOrColdLaunched_andSkipShortWakeBounces() {
        let suiteName = "SpotifyAudit065Tests.Backfill.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }

        SpotifyListeningBackfill.saveLastBackfillMs(1_000_000, scope: "spotify:acc1", defaults: defaults)
        XCTAssertEqual(SpotifyListeningBackfill.loadLastBackfillMs(scope: "spotify:acc1", defaults: defaults), 1_000_000)

        // Cold launch (awayAt == nil, persistedMs != nil): runs backfill from persisted watermark
        XCTAssertEqual(SpotifyListeningBackfill.resolveSinceMs(
            nowMs: 1_100_000, awayAt: nil, lastAttemptMs: nil, persistedMs: 1_000_000
        ), 1_000_000)

        // Quick App Remote wake bounce (<15s) with no persisted watermark: suppressed
        XCTAssertNil(SpotifyListeningBackfill.resolveSinceMs(
            nowMs: 1_005_000, awayAt: 1_000_000, lastAttemptMs: nil, persistedMs: nil
        ))

        // Cooldown guard after recent attempt: suppressed
        XCTAssertNil(SpotifyListeningBackfill.resolveSinceMs(
            nowMs: 1_010_000, awayAt: nil, lastAttemptMs: 1_000_000, persistedMs: 900_000
        ))
    }

    func test_should_persistRateGateBackoffAcrossInstances_andBlockAllPlayerRequestsAndApplyHeaders() async throws {
        let suiteName = "SpotifyAudit065Tests.RateGate.\(UUID().uuidString)"
        defer { UserDefaults(suiteName: suiteName)?.removePersistentDomain(forName: suiteName) }

        let now = Date()
        let g1 = SpotifyRateGate(now: { now }, storageKey: "test.gate", defaults: UserDefaults(suiteName: suiteName)!)
        await g1.recordBackoff(45)

        let g2 = SpotifyRateGate(now: { now.addingTimeInterval(10) }, storageKey: "test.gate", defaults: UserDefaults(suiteName: suiteName)!)
        let left = await g2.remaining()
        XCTAssertEqual(left, 35)

        // Player GET must be blocked locally without touching network when gate has any backoff
        AuditStubProtocol.reset()
        let seenHeader = AuditBox<String?>(nil)
        AuditStubProtocol.route = { req, _ in
            seenHeader.value = req.value(forHTTPHeaderField: "Accept-Language")
            return .init(status: 200, body: "{}")
        }

        let blockedAPI = makeAPI(gate: g2)
        do {
            _ = try await blockedAPI.send("GET", "me/player")
            XCTFail("Expected GET me/player to throw .rateLimited while gate backoff is active")
        } catch let error as SpotifyAPIError {
            guard case .rateLimited = error else { return XCTFail("Wrong error: \(error)") }
        }
        XCTAssertEqual(AuditStubProtocol.requests.filter { $0.url?.path.hasSuffix("/me/player") == true }.count, 0)

        // Custom headers seam reaches outgoing request when unblocked
        await g2.clear()
        _ = try await blockedAPI.send("GET", "me", headers: ["Accept-Language": "ja"])
        XCTAssertEqual(seenHeader.value, "ja")
    }

    // MARK: - Made For You Helper & AirPlayRoutePicker

    func test_should_returnEmptyMadeForYou_whenDisconnectedOrDisabled() async {
        let link = AuditFakeAppRemoteLink()
        let disconnected = SpotifyRemote(makeLink: { _, _ in link }, clientId: { "cid" }, enabled: { true })
        let emptyDisconnected = await disconnected.fetchMadeForYouContainers()
        XCTAssertEqual(emptyDisconnected, [])

        link.isConnected = true
        let macDisabled = SpotifyRemote(makeLink: { _, _ in link }, clientId: { "cid" }, enabled: { false })
        let emptyMac = await macDisabled.fetchMadeForYouContainers()
        XCTAssertEqual(emptyMac, [])
    }

    func test_should_provideInteractiveSizeHitTestAndUIControlFallback_inAirPlayRoutePicker() {
        let host = UIView()
        let control = UIControl()
        host.addSubview(control)
        XCTAssertTrue(AirPlayRouteTrigger.firstControl(in: host) === control)

        let button = UIButton()
        host.addSubview(button)
        XCTAssertTrue(AirPlayRouteTrigger.firstControl(in: host) === button)

        let container = AirPlayRouteContainerView(frame: .zero)
        let size = container.sizeThatFits(CGSize(width: 20, height: 20))
        XCTAssertGreaterThanOrEqual(size.width, 44)
        XCTAssertGreaterThanOrEqual(size.height, 44)
        container.frame = CGRect(x: 0, y: 0, width: 44, height: 44)
        container.layoutIfNeeded()
        XCTAssertEqual(container.picker.frame, container.bounds)
        XCTAssertNotNil(container.hitTest(CGPoint(x: 22, y: 22), with: nil))
        XCTAssertNil(container.hitTest(CGPoint(x: -5, y: 22), with: nil))
    }
}

// MARK: - Test Doubles

private final class AuditBox<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private var _value: Value
    init(_ value: Value) { _value = value }
    var value: Value {
        get { lock.withLock { _value } }
        set { lock.withLock { _value = newValue } }
    }
    @discardableResult
    func withValue<R>(_ body: (inout Value) -> R) -> R {
        lock.withLock { body(&_value) }
    }
}

@MainActor
private final class AuditFakeAppRemoteLink: AppRemoteLink {
    var isConnected = false
    var accessToken: String?
    var onEstablished: (() -> Void)?
    var onConnectFailed: ((String) -> Void)?
    var onConnectFailedError: ((Error?, String) -> Void)?
    var onDisconnected: ((String) -> Void)?
    var onPlayerState: ((RemotePlayerState) -> Void)?

    var connectCalls = 0
    var wakeCount = 0
    var playedURIs: [String] = []
    var repeatModes: [SPTAppRemotePlaybackOptionsRepeatMode] = []
    var handbackResult: (token: String?, error: String?) = (nil, nil)
    var autoFailNextConnect: (error: Error?, message: String)?

    func establish() {
        isConnected = true
        onEstablished?()
    }

    func failConnect(error: Error?, message: String) {
        isConnected = false
        onConnectFailedError?(error, message)
    }

    func connect() {
        connectCalls += 1
        if let autoFailNextConnect {
            let failure = autoFailNextConnect
            Task { @MainActor [weak self] in
                try? await Task.sleep(for: .milliseconds(10))
                self?.failConnect(error: failure.error, message: failure.message)
            }
        }
    }

    func disconnect() { isConnected = false }
    func authorizeAndPlay(_ uri: String, opened: @escaping @MainActor (Bool) -> Void) {
        wakeCount += 1
        opened(true)
    }
    func handback(_ url: URL) -> (token: String?, error: String?) { handbackResult }
    func subscribe(_ done: @escaping (Error?) -> Void) { done(nil) }
    func getPlayerState(_ done: @escaping (RemotePlayerState?, Error?) -> Void) { done(nil, nil) }
    func play(_ uri: String, _ done: @escaping (Error?) -> Void) {
        playedURIs.append(uri)
        done(nil)
    }
    func playContext(_ uri: String, index: Int, _ done: @escaping (Error?) -> Void) { done(nil) }
    func resume(_ done: @escaping (Error?) -> Void) { done(nil) }
    func pause(_ done: @escaping (Error?) -> Void) { done(nil) }
    func seek(toMs ms: Int, _ done: @escaping (Error?) -> Void) { done(nil) }
    func skip(forward: Bool, _ done: @escaping (Error?) -> Void) { done(nil) }
    func enqueue(_ uri: String, _ done: @escaping (Error?) -> Void) { done(nil) }
    func setShuffle(_ on: Bool) {}
    func setRepeat(_ mode: SPTAppRemotePlaybackOptionsRepeatMode) { repeatModes.append(mode) }
}

@MainActor
private final class AuditAudioOutputEvents: AudioOutputEvents {
    var times: [Double] = []
    var durations: [Double] = []
    var failures: [String] = []
    var externalUpdates: [(track: PlayableTrack?, pos: Double, playing: Bool)] = []
    var externalPlayChanges: [Bool] = []
    var jumpedURLs: [URL] = []
    var advancedCount = 0
    var upcomingURLs: [URL] = []
    var snapshot = OutputQueueSnapshot()

    func outputTimeDidChange(_ sec: Double) { times.append(sec) }
    func outputDurationDidChange(_ sec: Double) { durations.append(sec) }
    func outputIsBufferingDidChange(_ buffering: Bool) {}
    func outputDidAdvanceToPreloaded() { advancedCount += 1 }
    func outputDidReachEnd() {}
    func outputDidFail(_ message: String, network: Bool) { failures.append(message) }
    func outputPlayingDidChangeExternally(_ playing: Bool) { externalPlayChanges.append(playing) }
    func outputPlayModesDidChangeExternally(shuffle: Bool?, repeatMode: RepeatMode?) {}
    func outputExternalTrackDidChange(_ track: PlayableTrack?, positionSec: Double, playing: Bool) {
        externalUpdates.append((track, positionSec, playing))
    }
    func outputUpcoming(limit: Int) -> [URL] { Array(upcomingURLs.prefix(limit)) }
    func outputDidJump(to url: URL) -> Bool {
        jumpedURLs.append(url)
        return upcomingURLs.contains(url)
    }
    func outputQueueSnapshot() -> OutputQueueSnapshot { snapshot }
    func outputDidRecover(playing: Bool) {}
    func outputCurrentDidFinish() {}
}

private final class AuditStubProtocol: URLProtocol {
    struct Reply {
        var status: Int
        var headers: [String: String] = [:]
        var body: String
    }

    private static let lock = NSLock()
    nonisolated(unsafe) static var route: @Sendable (URLRequest, Int) -> Reply = { _, _ in Reply(status: 500, body: "") }
    nonisolated(unsafe) private static var seen: [URLRequest] = []

    static func reset() {
        lock.withLock {
            seen = []
            route = { _, _ in Reply(status: 500, body: "") }
        }
    }

    static var requests: [URLRequest] { lock.withLock { seen } }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let count = Self.lock.withLock {
            Self.seen.append(request)
            return Self.seen.count
        }
        let reply = Self.route(request, count)
        let response = HTTPURLResponse(url: request.url!, statusCode: reply.status, httpVersion: nil, headerFields: reply.headers)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(reply.body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
