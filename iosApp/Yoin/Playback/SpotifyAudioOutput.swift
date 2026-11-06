import Foundation
import os
@preconcurrency import SpotifyiOS
import UIKit

/// The connection to the Spotify app (Android SpotifyAppRemotePlayer's connection half): App Remote
/// on the active Spotify account's token, woken with `authorizeAndPlayURI` when the app isn't running
/// (it opens Spotify, starts the song and comes back to yoin://auth/spotify/app-remote).
@MainActor
final class SpotifyRemote: NSObject {
    static let shared = SpotifyRemote()

    /// The active Spotify account's token (set by the app as accounts switch); nil: no Spotify account.
    var token: (() async throws -> String)?
    /// Called with the Spotify app's player state, on every change.
    var onPlayerState: ((SPTAppRemotePlayerState) -> Void)?
    /// The connection failed or dropped with this message (a typed failure on Android).
    var onFailure: ((String) -> Void)?
    /// The Spotify app woke on a song and handed back to Yoin.
    var onWoken: (() -> Void)?
    /// A connection is up (after a wake's handback too).
    var onConnected: (() -> Void)?
    /// The connection dropped (the Spotify app was closed or lost the session).
    var onDisconnected: (() -> Void)?

    private var remote: SPTAppRemote?
    private var clientId: String?
    /// Whose remote this is (the account's profile id): another account gets a remote of its own.
    private var account: String?
    private var connectWaiters: [CheckedContinuation<Bool, Never>] = []
    private static let log = Logger(subsystem: "com.gpo.yoin", category: "spotify")

    var isConnected: Bool { remote?.isConnected ?? false }

    /// The account the remote now serves (Codex N06): a different one disconnects the old remote, so
    /// account B never drives the Spotify app through account A's session.
    func bind(account id: String?) {
        guard id != account else { return }
        disconnect()
        account = id
    }

    private func appRemote() -> SPTAppRemote? {
        guard let id = SpotifyConfig.clientId, let url = URL(string: SpotifyConfig.appRemoteRedirectURI) else { return nil }
        if let remote, clientId == id { return remote }
        let r = SPTAppRemote(configuration: SPTConfiguration(clientID: id, redirectURL: url), logLevel: .error)
        r.delegate = self
        remote = r
        clientId = id
        return r
    }

    /// Connected to the Spotify app (true), trying once with the account's token. False when the app
    /// isn't running or won't take the token: the caller wakes it with [wake].
    func connect(timeout: Double = SpotifyRemote.connectTimeoutSec) async -> Bool {
        PlayTrace.log("SR connect connected=\(remote?.isConnected ?? false)")
        // The QA fake (`-spotifyFake`) never reaches the Spotify app.
        guard !SpotifyFake.isOn, account != nil, let r = appRemote() else { return false }
        if r.isConnected { return true }
        if let token, let t = try? await token() { r.connectionParameters.accessToken = t }
        // With the Spotify app asleep the SDK neither connects nor fails for a long while (Play
        // looked dead until Spotify was opened by hand): past [timeout] the caller wakes it.
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(timeout))
            self?.finishConnect(false)
        }
        return await withCheckedContinuation { cont in
            connectWaiters.append(cont)
            if connectWaiters.count == 1 { r.connect() }
        }
    }

    nonisolated static let connectTimeoutSec: Double = 3

    /// Yoin is leaving the foreground: iOS would cut the connection silently (it would still read as
    /// connected); it is closed cleanly instead and reconnected on return. Spotify keeps playing.
    func dropForBackground() {
        guard remote?.isConnected == true else { return }
        remote?.disconnect()
    }

    /// Opens the Spotify app on [uri] (it starts playing it) and connects when it hands back.
    func wake(playing uri: String) {
        PlayTrace.log("SR wake \(uri)")
        guard !SpotifyFake.isOn else { return onFailure?("The QA fake doesn't play songs") ?? () }
        guard let r = appRemote() else { return onFailure?("Add your Spotify Client ID in Settings") ?? () }
        r.authorizeAndPlayURI(uri) { [weak self] opened in
            if !opened { MainActor.assumeIsolated { self?.onFailure?("Install the Spotify app to play Spotify songs") } }
        }
    }

    /// Spotify came back to yoin://auth/spotify/app-remote: its token, then the connection.
    func handle(_ url: URL) -> Bool {
        PlayTrace.log("SR handback")
        guard url.absoluteString.hasPrefix(SpotifyConfig.appRemoteRedirectURI), let r = appRemote() else { return false }
        let params = r.authorizationParameters(from: url)
        if let t = params?[SPTAppRemoteAccessTokenKey] {
            // The SDK's own flow: the woken app plays the song; connect with its token (never a
            // second connection over a live one) and drive it through App Remote from here.
            r.connectionParameters.accessToken = t
            if !r.isConnected { r.connect() }
            onWoken?()
        } else if let e = params?[SPTAppRemoteErrorDescriptionKey] {
            onFailure?(e)
        }
        return true
    }

    func disconnect() {
        if remote?.isConnected == true { remote?.disconnect() }
        remote = nil
        clientId = nil
        finishConnect(false)
    }

    var player: SPTAppRemotePlayerAPI? { remote?.playerAPI }

    private func finishConnect(_ ok: Bool) {
        let waiters = connectWaiters
        connectWaiters = []
        waiters.forEach { $0.resume(returning: ok) }
    }
}

extension SpotifyRemote: SPTAppRemoteDelegate, SPTAppRemotePlayerStateDelegate {
    nonisolated func appRemoteDidEstablishConnection(_ appRemote: SPTAppRemote) {
        MainActor.assumeIsolated {
            guard appRemote === remote else { return }
            PlayTrace.log("SR established")
            appRemote.playerAPI?.delegate = self
            appRemote.playerAPI?.subscribe(toPlayerState: nil)
            finishConnect(true)
            onConnected?()
        }
    }

    nonisolated func appRemote(_ appRemote: SPTAppRemote, didFailConnectionAttemptWithError error: Error?) {
        MainActor.assumeIsolated {
            Self.log.info("connect failed: \(error?.localizedDescription ?? "-", privacy: .public)")
            PlayTrace.log("SR connect failed \(error?.localizedDescription ?? "-")")
            finishConnect(false)
        }
    }

    nonisolated func appRemote(_ appRemote: SPTAppRemote, didDisconnectWithError error: Error?) {
        MainActor.assumeIsolated {
            PlayTrace.log("SR disconnected \(error?.localizedDescription ?? "-")")
            guard appRemote === remote else { return }
            onDisconnected?()
        }
    }

    nonisolated func playerStateDidChange(_ playerState: SPTAppRemotePlayerState) {
        MainActor.assumeIsolated { onPlayerState?(playerState) }
    }
}

/// [AudioOutput] on the Spotify app (Android SpotifyAppRemotePlayer, reduced to what keeps Yoin's queue
/// the truth). Each song starts through the Web API as a two-song list, [current, next] (Codex N02:
/// an album context let Spotify carry on through the album while Yoin played a playlist), with
/// Spotify's own shuffle and repeat off, since the engine owns the play modes (N03). When Spotify moves
/// on to that next song, the engine is told it advanced; anything else, or the list running out, is the
/// end of the current song and the engine starts its next. App Remote reports the app's state back;
/// pause, resume and seek go through it.
///
/// Play modes (Codex N03): a mode change or a queue edit that changes the engine's next song hands
/// Spotify the new pair from the current position (paused: on the next play), so Spotify never carries
/// on to a stale song. Shuffle or repeat turned on in the Spotify app is taken over by the engine and
/// switched back off in Spotify. Repeat one hands [song, song]; the wrap to the second copy is an
/// advance.
///
/// `-silentAudio YES`: nothing is started (the Spotify app can't be muted from here); the engine still
/// moves through its queue.
@MainActor
final class SpotifyAudioOutput: AudioOutput {
    weak var events: AudioOutputEvents?
    /// The active Spotify account's Web API (start, transport, where it plays); set by the app.
    weak var control: SpotifySource? {
        didSet { control == nil ? stopWatching() : startWatching() }
    }
    /// Makes this the output the player drives, for Spotify playing something Yoin didn't start; false
    /// while the player plays another source's song (set by the app).
    var adopt: (() -> Bool)?

    private let remote = SpotifyRemote.shared
    private let silent = UserDefaults.standard.bool(forKey: "silentAudio")
    private var current: Item?
    private var next: Item?
    /// The next song Spotify was handed with the current one (what it carries on to by itself).
    private var handedNext: Item?
    private var generation = 0
    private var wantsPlay = false
    /// A start is on its way (Codex N04): a Resume now would wake the previous song first.
    private var starting = false
    /// Where the current start should begin (kept for a start that waits on a wake, N01).
    private var pendingStart: Double = 0
    private var lastTime: Double = 0
    private var lastTick = Date()
    private var playing = false
    private var duration: Double = 0
    /// The Spotify app has been seen on the current song (an end is only read after that).
    private var sawCurrent = false
    /// The position the last report gave (a song ended when Spotify stops at 0 right after its end).
    private var reportedTime: Double = 0
    private var timer: Timer?
    /// A coalesced re-hand of [current, next] after the engine's next changed.
    private var rehandTask: Task<Void, Never>?
    /// Mode reports right after a start still show Spotify's old modes; they're not the listener's.
    private var modesQuietUntil = Date.distantPast
    /// The Connect device Yoin drives (nil: Spotify's active device, or this iPhone once it plays).
    private var target: String?
    /// A start waits on the Spotify app waking (it hands back and connects).
    private var awaitingWake = false
    /// Spotify plays this song, not the queue's (shown through `outputExternalTrackDidChange`).
    private var externalURI: String?
    /// Reads where Spotify is while Yoin is in the foreground (App Remote's state stops when iOS
    /// suspends Yoin, and says nothing about another device).
    private var pollTimer: Timer?
    private var refreshTask: Task<Void, Never>?
    /// Spotify was found playing with nothing of Yoin's loaded: Yoin shows it and is its remote.
    private var adopted = false
    /// Looks for Spotify playing somewhere while Yoin is visible, even with its own player idle (51,
    /// 2026-11-06: open Yoin while the Pixel plays and Yoin shows that song).
    private var watchTimer: Timer?
    /// The engine answers a failure with a pause; after a failure Yoin reported that pause is not
    /// sent (a command is never error handling: a pause to a device that lost the song was refused).
    private var quietPause = false
    /// Spotify answered a start on another device but no read has shown it there yet: transport
    /// commands wait in [held], in order (a resume sent right after a start got 403 Restriction
    /// violated, 2026-11-06 Mac run). They go when the start is seen, or are dropped with it.
    private var landing = false
    private var held: [() -> Void] = []
    /// When the last start on another device went out, from where, and whether it's still waiting to
    /// be seen playing: a device that drops it is said so, not shown as a plain pause.
    private var startedAt = Date.distantPast
    private var startedAtSec: Double = 0
    private var awaitingPlay = false
    /// The last thing the engine did was stop (not load another song).
    private var stopped = false
    private var observers: [NSObjectProtocol] = []
    private static let log = Logger(subsystem: "com.gpo.yoin", category: "spotify")

    /// A song as the engine names it: spotify-yoin://track/<id>.
    struct Item: Equatable, Sendable {
        var trackId: String
        var trackURI: String { "spotify:track:\(trackId)" }

        init?(_ url: URL?) {
            guard let url, url.scheme == SpotifyAudioOutput.scheme else { return nil }
            let id = url.lastPathComponent
            guard !id.isEmpty, id != "/" else { return nil }
            trackId = id
        }
    }

    nonisolated static let scheme = "spotify-yoin"

    /// The engine's stand-in stream URL for a Spotify song.
    nonisolated static func streamURL(trackId: String) -> URL {
        var c = URLComponents()
        c.scheme = scheme
        c.host = "track"
        c.path = "/" + trackId
        return c.url ?? URL(string: "\(scheme)://track/unknown")!
    }

    nonisolated static func isSpotify(_ url: URL?) -> Bool { url?.scheme == scheme }

    init() {
        remote.onPlayerState = { [weak self] s in self?.playerState(s) }
        remote.onFailure = { [weak self] m in
            self?.starting = false
            self?.events?.outputDidFail(m, network: false)
        }
        remote.onWoken = { PlayTrace.log("SP woken") }
        remote.onConnected = { [weak self] in self?.connected() }
        remote.onDisconnected = { [weak self] in self?.disconnected() }
        let c = NotificationCenter.default
        observers.append(c.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil,
                                       queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.foreground() }
        })
        observers.append(c.addObserver(forName: SpotifyPlayingDevice.transferred, object: nil,
                                       queue: .main) { [weak self] n in
            let id = n.userInfo?["id"] as? String
            let name = n.userInfo?["name"] as? String
            MainActor.assumeIsolated { self?.followTransfer(id: id, name: name) }
        })
        observers.append(c.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil,
                                       queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.background() }
        })
    }

    var currentTimeSec: Double {
        playing ? lastTime + Date().timeIntervalSince(lastTick) : lastTime
    }

    func load(_ current: URL, next: URL?, startSec: Double) {
        PlayTrace.log("SP load \(current.lastPathComponent) start=\(startSec)")
        generation += 1
        stopped = false
        rehandTask?.cancel()
        let item = Item(current)
        self.current = item
        self.next = Item(next)
        handedNext = nil
        awaitingWake = false
        externalURI = nil
        adopted = false
        quietPause = false
        resetStart()
        enqueueWhenPlaying = false
        sawCurrent = false
        playing = false
        lastTime = startSec
        lastTick = Date()
        pendingStart = startSec
        duration = 0
        startTicking()
        guard let item else { return events?.outputDidFail("Not a Spotify song") ?? () }
        guard !silent else { return }
        starting = true
        let gen = generation
        Task { await start(item, gen: gen) }
    }

    /// Where a start goes (51, 2026-11-06): a device that is actually playing Spotify keeps it, and
    /// Yoin is its remote (Web API); otherwise this iPhone, through App Remote. me/player also names
    /// the last-used device when nothing plays (an asleep Pixel took a start and did nothing).
    private func start(_ item: Item, gen: Int) async {
        let now = (try? await control?.playerNow()) ?? nil
        PlayTrace.log("SP start now=\(Self.describe(now))")
        guard gen == generation else { return }
        if let now, let id = Self.remoteTarget(now) {
            target = id
            SpotifyPlayingDevice.shared.name = now.deviceName
            startPolling()
            return await send(item, gen: gen, modes: now)
        }
        target = nil
        SpotifyPlayingDevice.shared.name = nil
        stopPolling()
        await startHere(item, gen: gen)
    }

    nonisolated static func describe(_ now: SpotifySource.PlayerNow?) -> String {
        guard let now else { return "nothing active" }
        return "\(now.deviceName) [\(now.deviceType)] playing=\(now.isPlaying) track=\(now.trackURI ?? "-") at=\(now.progressMs)"
    }

    // MARK: This iPhone (App Remote — the SDK's flow)

    /// A Web API start to this iPhone's Spotify while it sits in the background was ignored and then
    /// stopped it (51's log, every run): this iPhone is only ever driven through App Remote. An asleep
    /// app is woken on the song (it plays it) and connects on the handback.
    private func startHere(_ item: Item, gen: Int) async {
        if await remote.connect(timeout: Self.localConnectSec) {
            guard gen == generation else { return }
            remote.player?.play(item.trackURI) { [weak self] _, error in
                MainActor.assumeIsolated {
                    guard let self, gen == self.generation else { return }
                    if let error {
                        PlayTrace.log("SP AR play failed \(error.localizedDescription)")
                        self.starting = false
                        self.events?.outputDidFail("Open Spotify on this iPhone, then tap Play", network: true)
                    } else {
                        self.startedHere(item)
                    }
                }
            }
            return
        }
        guard gen == generation else { return }
        awaitingWake = true
        remote.wake(playing: item.trackURI)
    }

    nonisolated static let localConnectSec: Double = 1.5

    /// The song plays here: the engine's next goes into Spotify's queue (it carries on there while iOS
    /// suspends Yoin), Spotify's own modes off, the position and a pause applied.
    private func startedHere(_ item: Item) {
        PlayTrace.log("SP started here \(item.trackId)")
        starting = false
        playing = wantsPlay
        lastTick = Date()
        modesQuietUntil = Date().addingTimeInterval(Self.modesQuietSec)
        remote.player?.setShuffle(false, callback: nil)
        remote.player?.setRepeatMode(.off, callback: nil)
        if pendingStart > 1 { remote.player?.seek(toPosition: Int(pendingStart * 1000), callback: nil) }
        lastTime = pendingStart > 1 ? pendingStart : 0
        if !wantsPlay { remote.player?.pause(nil) }
        // The next goes into Spotify's queue once the song is seen playing: right after the start App Remote
        // refused it (-3000, 51's build 7 log, 18:22:38).
        enqueueWhenPlaying = true
    }

    private var enqueueWhenPlaying = false

    nonisolated static func readyToEnqueue(waiting: Bool, stateURI: String, current: String?, paused: Bool,
                                           positionMs: Int) -> Bool {
        waiting && stateURI == current && !paused && positionMs > 0
    }

    /// App Remote's "try again later" (-3000): worth one more go a second later.
    nonisolated static func retriesEnqueue(errorCode: Int, alreadyRetried: Bool) -> Bool {
        errorCode == -3000 && !alreadyRetried
    }

    /// The engine's next, queued in Spotify once per song.
    private func enqueueNext(retried: Bool = false) {
        guard isHere, !onSpotifysOwnSong, handedNext == nil || retried, let next, let player = remote.player else { return }
        enqueueWhenPlaying = false
        handedNext = next
        let gen = generation, song = current
        player.enqueueTrackUri(next.trackURI) { [weak self] _, error in
            guard let error else { return }
            MainActor.assumeIsolated {
                guard let self else { return }
                PlayTrace.log("SP enqueue failed \(error.localizedDescription)")
                guard gen == self.generation, self.current == song, self.next == next else { return }
                guard Self.retriesEnqueue(errorCode: (error as NSError).code, alreadyRetried: retried) else {
                    // Spotify is left alone: at the song's end the engine starts its next itself.
                    self.handedNext = nil
                    return
                }
                Task { [weak self] in
                    try? await Task.sleep(for: .seconds(1))
                    guard let self, gen == self.generation, self.current == song, self.next == next else { return }
                    self.enqueueNext(retried: true)
                }
            }
        }
    }

    private var isHere: Bool { SpotifyPlayingDevice.shared.name == nil }

    /// Spotify plays a song that isn't the queue's (picked there, or found playing on open).
    private var onSpotifysOwnSong: Bool { externalURI != nil || adopted }

    /// The song a command is meant for: dropped when, before it goes out (or before a retry), another
    /// load happened or Spotify moved to another song (Codex: a late pause or seek landed on the next).
    struct Aim: Equatable {
        var generation: Int
        var uri: String?
    }

    private func aim(song: Bool) -> Aim {
        Aim(generation: generation, uri: song ? shownURI : nil)
    }

    /// The song Yoin shows as playing in Spotify.
    private var shownURI: String? { externalURI ?? current?.trackURI }

    nonisolated static func stillAimed(_ aim: Aim, generation: Int, shownURI: String?) -> Bool {
        aim.generation == generation && (aim.uri == nil || aim.uri == shownURI)
    }

    private func isStill(_ aim: Aim) -> Bool {
        Self.stillAimed(aim, generation: generation, shownURI: shownURI)
    }

    /// A connection came up: after a wake the woken app already plays the song; otherwise (back in
    /// the foreground) its state arrives by itself.
    private func connected() {
        guard awaitingWake, starting, let current, !silent else { return }
        awaitingWake = false
        // The woken app doesn't always play the song it was woken on (51's build 7 log, 16:42: handed back
        // paused with no song): then it's started over the connection.
        let gen = generation
        guard let player = remote.player else { return startedHere(current) }
        player.getPlayerState { [weak self] state, _ in
            MainActor.assumeIsolated {
                guard let self, gen == self.generation else { return }
                let s = state as? SPTAppRemotePlayerState
                let finish = Self.wakeFinish(playingURI: s?.track.uri, paused: s?.isPaused ?? true,
                                             wanted: current.trackURI, wantsPlay: self.wantsPlay)
                guard finish != .none else { return self.startedHere(current) }
                PlayTrace.log("SP woken \(finish == .resume ? "paused on the song: resume" : "without the song: play it")")
                let done: SPTAppRemoteCallback = { [weak self] _, error in
                    MainActor.assumeIsolated {
                        guard let self, gen == self.generation else { return }
                        if let error {
                            PlayTrace.log("SP AR play failed \(error.localizedDescription)")
                            self.starting = false
                            self.events?.outputDidFail("Open Spotify on this iPhone, then tap Play", network: true)
                        } else {
                            self.startedHere(current)
                        }
                    }
                }
                if finish == .resume { player.resume(done) } else { player.play(current.trackURI, callback: done) }
            }
        }
    }

    enum WakeFinish: Equatable { case none, resume, play }

    /// After a wake, the rest of the start (once): the song paused there resumes, another song (or none) is
    /// replaced by it; already playing it, nothing.
    nonisolated static func wakeFinish(playingURI: String?, paused: Bool, wanted: String, wantsPlay: Bool) -> WakeFinish {
        if playingURI != wanted { return .play }
        return wantsPlay && paused ? .resume : .none
    }

    /// Playback moved in Devices: commands and reads follow it there (this iPhone: App Remote).
    private func followTransfer(id: String?, name: String?) {
        guard let id, isDriving else { return }
        let here = name.map { Self.isThisPhone(.init(deviceId: id, deviceName: $0, deviceType: "Smartphone", isPlaying: true)) } ?? false
        target = here ? nil : id
        SpotifyPlayingDevice.shared.name = here ? nil : name
        if here {
            stopPolling()
            Task { _ = await remote.connect(timeout: Self.localConnectSec) }
        } else {
            startPolling()
            refresh(after: 1)
        }
    }

    private func background() {
        stopPolling()
        stopWatching()
        remote.dropForBackground()
    }

    /// This iPhone in Spotify's device list: the only phone named iPhone (an app can't read the
    /// device's own name, and a Pixel is a Smartphone too).
    nonisolated static func isThisPhone(_ now: SpotifySource.PlayerNow) -> Bool {
        now.deviceType.caseInsensitiveCompare("Smartphone") == .orderedSame
            && now.deviceName.localizedCaseInsensitiveContains("iPhone")
    }

    /// The Web API start: [current, next] from the remembered position. Spotify's own modes go off
    /// first, and only when [modes] (the device's state just read) has one on: a mode change right
    /// after the start reached an Android device while it loaded the song and left it with none (51's
    /// log, build 4: shuffle took 2 s, then the Pixel had no song).
    private func send(_ item: Item, gen: Int, modes: SpotifySource.PlayerNow? = nil) async {
        let next = self.next
        // The start often runs as Spotify comes to the front: iOS would suspend Yoin mid-request.
        let bg = UIApplication.shared.beginBackgroundTask(withName: "spotify-start")
        defer { if bg != .invalid { UIApplication.shared.endBackgroundTask(bg) } }
        do {
            let at = pendingStart > 1 ? Int(pendingStart * 1000) : 0
            let uris = [item.trackURI] + (next.map { [$0.trackURI] } ?? [])
            PlayTrace.log("SP send PUT play \(uris) at=\(at) wantsPlay=\(wantsPlay) target=\(target ?? "-")")
            guard let control else { throw SpotifyPlaybackError("Sign in to Spotify again in Settings.") }
            if let modes, modes.shuffle || modes.repeatState != "off" {
                PlayTrace.log("SP modes off before start shuffle=\(modes.shuffle) repeat=\(modes.repeatState)")
                await control.playModesOff(deviceId: target)
                guard gen == generation else { return }
            }
            let device = target
            try await control.startPlayback(uris: uris, positionMs: at, deviceId: device)
            PlayTrace.log("SP send done gen=\(gen == generation)")
            guard gen == generation else {
                // Stopped while the start was on its way: it mustn't keep playing there (stop() sends
                // nothing for a start not yet answered).
                if stopped { Task { try? await control.pausePlayback(deviceId: device) } }
                return
            }
            handedNext = next
            starting = false
            playing = wantsPlay
            lastTime = Double(at) / 1000
            lastTick = Date()
            modesQuietUntil = Date().addingTimeInterval(Self.modesQuietSec)
            startedAt = Date()
            startedAtSec = lastTime
            awaitingPlay = wantsPlay
            landing = true
            if !wantsPlay { held.append { [weak self] in self?.sendPause() } }
            verifyStarted(item, gen: gen)
            // The engine's next changed while this start was on its way.
            scheduleRehand()
        } catch {
            PlayTrace.log("SP send failed \(error)")
            guard gen == generation else { return }
            starting = false
            Self.log.error("start failed: \(error.localizedDescription, privacy: .public)")
            // No device to play on holds the queue (Retry) rather than skipping through it.
            events?.outputDidFail(SpotifySource.message(for: error),
                                  network: error is SpotifyPlaybackError || PlaybackFailureKind.isNetwork(error))
        }
    }

    /// The device a start is sent to as a remote: one that is playing Spotify right now and isn't
    /// this iPhone. nil: start here.
    nonisolated static func remoteTarget(_ now: SpotifySource.PlayerNow?) -> String? {
        guard let now, now.isPlaying, let id = now.deviceId, !isThisPhone(now) else { return nil }
        return id
    }

    /// A device can take a start (204) and do nothing (asleep): read it back after a moment and say
    /// so once, instead of showing a song that isn't playing.
    private func verifyStarted(_ item: Item, gen: Int) {
        Task {
            try? await Task.sleep(for: .seconds(Self.verifySec))
            guard gen == generation, let control else { return }
            let now = (try? await control.playerNow()) ?? nil
            PlayTrace.log("SP verify \(Self.describe(now))")
            guard gen == generation else { return }
            if let now, now.trackURI == item.trackURI || now.trackURI == handedNext?.trackURI { return apply(now) }
            // Something else plays there (the start is late, or never took): Yoin shows that, and
            // moves back onto the queue's song if Spotify gets to it.
            if let now, now.isPlaying, now.trackURI != nil {
                land(false)
                return apply(now)
            }
            dropped()
        }
    }

    /// The device took the start and then had no song, or never played it: said once, with Retry (which
    /// starts on this iPhone, as nothing plays there); no command follows and Yoin doesn't switch by itself.
    private func dropped() {
        PlayTrace.log("SP start dropped on \(SpotifyPlayingDevice.shared.name ?? "-")")
        resetStart()
        lastTime = currentTimeSec
        lastTick = Date()
        playing = false
        wantsPlay = false
        quietPause = true
        events?.outputDidFail(Self.droppedMessage(device: SpotifyPlayingDevice.shared.name), network: true)
    }

    nonisolated static func droppedMessage(device: String?) -> String {
        "Spotify on \(device ?? "that device") stopped the song. Retry plays it on this iPhone."
    }

    /// What a read within [dropWindowSec] of a start says of it: dropped (no song there, or the queue's
    /// song still parked at its start after [stalledSec]), waiting (not playing yet), or nil (it plays,
    /// or something else is the listener's).
    nonisolated static func startCheck(_ now: SpotifySource.PlayerNow?, uri: String, fromSec: Double,
                                       elapsed: Double) -> StartCheck? {
        guard elapsed < dropWindowSec else { return nil }
        guard let now, let playingURI = now.trackURI else { return .dropped }
        guard playingURI == uri, !now.isPlaying else { return nil }
        if Double(now.progressMs) / 1000 > fromSec + 1 { return nil }
        return elapsed > stalledSec ? .dropped : .waiting
    }

    enum StartCheck { case dropped, waiting }

    nonisolated static let dropWindowSec: Double = 20
    nonisolated static let stalledSec: Double = 6

    /// The start was seen ([ok]) or given up: held commands go, or are dropped with it.
    private func land(_ ok: Bool) {
        guard landing else { return }
        landing = false
        let acts = held
        held = []
        PlayTrace.log("SP start \(ok ? "landed" : "not landed"), held=\(acts.count)")
        if ok { acts.forEach { $0() } }
    }

    private func resetStart() {
        landing = false
        held = []
        awaitingPlay = false
        startedAt = .distantPast
    }

    /// A transport command to another device waits while a start lands there.
    private func holdWhileLanding(_ act: @escaping () -> Void) -> Bool {
        // Reads that never come (the network) don't hold commands for good.
        if landing, Date().timeIntervalSince(startedAt) > Self.dropWindowSec { land(true) }
        guard landing else { return false }
        PlayTrace.log("SP command held (start landing)")
        held.append(act)
        return true
    }

    private func sendPause() {
        transport(report: false, aim(song: true)) { try await $0.pausePlayback(deviceId: $1, while: $2) }
    }

    nonisolated static let verifySec: Double = 2.5

    /// The Spotify app went away (Codex N05): the clock stops; the next transport command reconnects.
    private func disconnected() {
        playing = false
        lastTick = Date()
        events?.outputTimeDidChange(lastTime)
    }

    func preload(_ next: URL?) {
        self.next = Item(next)
        scheduleRehand()
    }

    /// Spotify was handed a next song the engine no longer has next: hand it the new pair from here,
    /// after a short pause so a run of taps on the mode button makes one start.
    private func scheduleRehand() {
        rehandTask?.cancel()
        // Spotify's own song plays (Codex, 2026-11-06): a queue edit or a shuffle change is Yoin's queue
        // only; a start of the pair would cut into it, and Spotify's queue isn't touched.
        guard !onSpotifysOwnSong else { return }
        // Here the next goes into Spotify's queue instead (it can't be taken back out).
        guard !isHere else { return enqueueNext() }
        guard !silent, !starting, playing, current != nil,
              Self.needsRehand(handed: handedNext, next: next, remainingSec: duration > 0 ? duration - currentTimeSec : nil)
        else { return }
        rehandTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(Self.rehandDelayMs))
            guard !Task.isCancelled else { return }
            self?.rehand()
        }
    }

    private func rehand() {
        // Paused since it was scheduled: the next play hands the new pair.
        guard !onSpotifysOwnSong, wantsPlay, playing, !starting, let current,
              Self.needsRehand(handed: handedNext, next: next, remainingSec: duration > 0 ? duration - currentTimeSec : nil)
        else { return }
        pendingStart = currentTimeSec
        starting = true
        let gen = generation
        Task { await send(current, gen: gen) }
    }

    /// Spotify carries on to [handed] by itself; re-hand when that's a song the engine no longer has
    /// next. Nothing handed (the list runs out after the current song) needs nothing: the end starts the
    /// engine's next. Near the end a restart would cut the song's last seconds; the end handles it.
    nonisolated static func needsRehand(handed: Item?, next: Item?, remainingSec: Double?) -> Bool {
        guard let handed, handed != next else { return false }
        return (remainingSec ?? .infinity) > rehandMinRemainingSec
    }

    nonisolated static let rehandMinRemainingSec: Double = 4
    nonisolated static let rehandDelayMs = 600
    nonisolated static let modesQuietSec: Double = 3

    func play() {
        PlayTrace.log("SP play starting=\(starting) connected=\(remote.isConnected)")
        wantsPlay = true
        quietPause = false
        // A start on its way plays when it lands; resuming now would sound the previous song.
        guard !silent, !starting else { return }
        // Paused with a stale next: start the new pair here instead of resuming the old one.
        if externalURI == nil, let current, Self.needsRehand(handed: handedNext, next: next, remainingSec: duration > 0 ? duration - lastTime : nil) {
            pendingStart = lastTime
            starting = true
            let gen = generation
            Task { await send(current, gen: gen) }
            return
        }
        playing = true
        lastTick = Date()
        if isHere { return command(aim(song: false)) { $0.resume(nil) } }
        let resume = { [weak self] in
            guard let self else { return }
            self.transport(report: true, self.aim(song: false)) { try await $0.resumePlayback(deviceId: $1, while: $2) }
        }
        if !holdWhileLanding(resume) { resume() }
    }

    func pause() {
        PlayTrace.log("SP pause starting=\(starting)")
        wantsPlay = false
        rehandTask?.cancel()
        if quietPause {
            quietPause = false
            PlayTrace.log("SP pause not sent (answer to a failure)")
            return
        }
        guard !silent, !starting else { return }
        lastTime = currentTimeSec
        lastTick = Date()
        playing = false
        awaitingPlay = false
        if isHere { return command(aim(song: true)) { $0.pause(nil) } }
        if !holdWhileLanding({ [weak self] in self?.sendPause() }) { sendPause() }
    }

    func seek(to sec: Double) {
        PlayTrace.log("SP seek \(sec)")
        lastTime = sec
        lastTick = Date()
        pendingStart = sec
        events?.outputTimeDidChange(sec)
        guard !silent, !starting else { return }
        let ms = Int(sec * 1000)
        if isHere { return command(aim(song: true)) { $0.seek(toPosition: ms, callback: nil) } }
        let seek = { [weak self] in
            guard let self else { return }
            self.transport(report: false, self.aim(song: true)) { try await $0.seekPlayback(toMs: ms, deviceId: $1, while: $2) }
        }
        if !holdWhileLanding(seek) { seek() }
    }

    /// A command to this iPhone's Spotify through App Remote, reconnecting first (the connection is
    /// closed while Yoin is in the background). One message when the app can't be reached.
    private func command(_ aim: Aim, _ act: @escaping (SPTAppRemotePlayerAPI) -> Void) {
        PlayTrace.log("SP command here connected=\(remote.isConnected)")
        if remote.isConnected, let p = remote.player { return act(p) }
        let gen = generation
        Task {
            guard await remote.connect(timeout: Self.localConnectSec), let p = remote.player else {
                guard gen == generation else { return }
                playing = false
                if wantsPlay {
                    wantsPlay = false
                    events?.outputPlayingDidChangeExternally(false)
                }
                return reportOnce("Open Spotify on this iPhone, then tap Play")
            }
            guard isStill(aim) else { return PlayTrace.log("SP command dropped (the song changed)") }
            act(p)
        }
    }

    /// One failure message per few seconds: the engine answers a failure by pausing, and a pause that
    /// failed again used to loop (25 pauses in 10 s against a vanished device).
    private func reportOnce(_ message: String) {
        guard Date().timeIntervalSince(lastReport) > Self.reportGapSec else { return }
        lastReport = Date()
        quietPause = true
        events?.outputDidFail(message, network: true)
    }

    private var lastReport = Date.distantPast
    nonisolated static let reportGapSec: Double = 5

    /// A transport command through the Web API on the device Yoin drives (App Remote's connection
    /// dies while iOS suspends Yoin, and can't reach another device), then a fresh read of the state.
    private func transport(report: Bool, _ aim: Aim,
                           _ act: @escaping (SpotifySource, String?, @escaping SpotifyWebAPI.StillWanted) async throws -> Void) {
        PlayTrace.log("SP transport target=\(target ?? "-")")
        let gen = generation
        let still: SpotifyWebAPI.StillWanted = { [weak self] in
            await MainActor.run { self?.isStill(aim) ?? false }
        }
        Task {
            do {
                guard let control else { throw SpotifyPlaybackError("Sign in to Spotify again in Settings.") }
                try await act(control, target, still)
            } catch is SpotifyCommandDropped {
                PlayTrace.log("SP command dropped (the song changed)")
                return
            } catch {
                guard gen == generation else { return }
                Self.log.error("command failed: \(error.localizedDescription, privacy: .public)")
                PlayTrace.log("SP command failed \(SpotifySource.message(for: error))")
                // A vanished device: read where Spotify is now instead of trying again.
                playing = false
                if report {
                    let name = SpotifyPlayingDevice.shared.name ?? "that device"
                    let gone = (error as? SpotifyAPIError)?.isNotFound == true
                    reportOnce(gone ? "Spotify on \(name) isn't responding — open Spotify there"
                                    : SpotifySource.message(for: error))
                }
            }
            refresh(after: 0.6)
        }
    }

    // MARK: Reading where Spotify is (Web API)

    private var isDriving: Bool { current != nil || adopted }

    private func startWatching() {
        guard watchTimer == nil, !silent, control != nil, UIApplication.shared.applicationState != .background
        else { return }
        watchTimer = Timer.scheduledTimer(withTimeInterval: Self.watchSec, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.watch() }
        }
        watch()
    }

    private func stopWatching() {
        watchTimer?.invalidate()
        watchTimer = nil
    }

    /// One look at where Spotify plays (the remote poll covers a device Yoin already drives).
    private func watch() {
        guard !silent, !starting, !awaitingWake, pollTimer == nil, let control else { return }
        Task { [weak self] in
            guard let now = try? await control.playerNow() else { return }
            guard let self, !self.starting, !self.awaitingWake, self.pollTimer == nil else { return }
            PlayTrace.log("SP watch \(Self.describe(now)) driving=\(self.isDriving)")
            guard now.isPlaying, now.trackURI != nil else { return }
            if self.isDriving {
                // Playing somewhere App Remote doesn't report from (another device, or this iPhone
                // with the connection closed): read it the Web API's way.
                guard !(self.isHere && self.remote.isConnected && Self.isThisPhone(now)) else { return }
                return self.apply(now)
            }
            guard self.adopt?() == true else { return }
            PlayTrace.log("SP adopt \(Self.describe(now))")
            self.adopted = true
            self.generation += 1
            self.startTicking()
            let here = Self.isThisPhone(now)
            self.target = here ? nil : now.deviceId
            SpotifyPlayingDevice.shared.name = here ? nil : now.deviceName
            self.apply(now)
            if here {
                Task { _ = await self.remote.connect(timeout: Self.localConnectSec) }
            } else {
                self.startPolling()
            }
        }
    }

    nonisolated static let watchSec: Double = 10

    private func startPolling() {
        guard pollTimer == nil, !silent, UIApplication.shared.applicationState != .background else { return }
        pollTimer = Timer.scheduledTimer(withTimeInterval: Self.pollSec, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.refresh() }
        }
    }

    private func stopPolling() {
        pollTimer?.invalidate()
        pollTimer = nil
    }

    private func foreground() {
        startWatching()
        guard isDriving, !silent else { return }
        if isHere {
            // Back to App Remote's own state stream (Spotify kept playing while Yoin was away).
            Task { _ = await remote.connect(timeout: Self.localConnectSec) }
        } else {
            startPolling()
            refresh()
        }
    }

    /// One read of GET /me/player (after [delay]), applied as the Spotify app's state is.
    private func refresh(after delay: Double = 0) {
        guard isDriving, !silent, control != nil, !isHere else { return }
        refreshTask?.cancel()
        refreshTask = Task { [weak self] in
            if delay > 0 { try? await Task.sleep(for: .seconds(delay)) }
            guard !Task.isCancelled, let self, let control = self.control else { return }
            let now: SpotifySource.PlayerNow?
            do { now = try await control.playerNow() } catch {
                // A failed read keeps what was last seen (only 204 means nothing plays).
                PlayTrace.log("SP read failed \(SpotifySource.message(for: error))")
                return
            }
            guard !Task.isCancelled else { return }
            PlayTrace.log("SP read \(Self.describe(now))")
            self.apply(now)
        }
    }

    nonisolated static let pollSec: Double = 4

    func stop() {
        PlayTrace.log("SP stop wantsPlay=\(wantsPlay)")
        generation += 1
        stopped = true
        rehandTask?.cancel()
        // Only a song Yoin has playing is paused: a stop after a start that never went out (Spotify
        // not installed, the wake failed) sent a pause to whatever device Spotify had active.
        if !silent, wantsPlay, playing, !starting, !awaitingWake, let control {
            let device = target
            Task { try? await control.pausePlayback(deviceId: device) }
        }
        stopPolling()
        refreshTask?.cancel()
        awaitingWake = false
        adopted = false
        externalURI = nil
        quietPause = false
        resetStart()
        SpotifyPlayingDevice.shared.name = nil
        wantsPlay = false
        starting = false
        current = nil
        next = nil
        handedNext = nil
        playing = false
        timer?.invalidate()
        timer = nil
    }

    // MARK: The Spotify app's state

    /// The Spotify app's state as App Remote and the Web API both report it.
    struct State {
        var uri: String
        var isPaused: Bool
        var positionMs: Int
        var durationMs: Int
        var shuffling: Bool
        var repeatRaw: UInt
        var title: String? = nil
        var artist: String? = nil
        var album: String? = nil
        var coverURL: URL? = nil
    }

    private func playerState(_ s: SPTAppRemotePlayerState) {
        // App Remote speaks for this iPhone only.
        guard SpotifyPlayingDevice.shared.name == nil else { return }
        apply(State(uri: s.track.uri, isPaused: s.isPaused, positionMs: s.playbackPosition,
                    durationMs: Int(s.track.duration), shuffling: s.playbackOptions.isShuffling,
                    repeatRaw: s.playbackOptions.repeatMode.rawValue, title: s.track.name,
                    artist: s.track.artist.name, album: s.track.album.name,
                    coverURL: Self.coverURL(imageIdentifier: s.track.imageIdentifier)))
    }

    /// A Web API read: nothing active is Spotify stopped where it was.
    private func apply(_ now: SpotifySource.PlayerNow?) {
        guard !starting, !awaitingWake, isDriving else { return }
        if awaitingPlay, !isHere, let current {
            switch Self.startCheck(now, uri: current.trackURI, fromSec: startedAtSec,
                                   elapsed: Date().timeIntervalSince(startedAt)) {
            case .dropped: return dropped()
            case .waiting: return
            case nil: awaitingPlay = false
            }
        }
        if landing { land(now?.trackURI == current?.trackURI || now?.trackURI == handedNext?.trackURI) }
        guard let now, let uri = now.trackURI else {
            guard playing || wantsPlay else { return }
            lastTime = currentTimeSec
            lastTick = Date()
            playing = false
            if wantsPlay {
                wantsPlay = false
                events?.outputPlayingDidChangeExternally(false)
            }
            return
        }
        // Playback moved to another device in Spotify: Yoin follows it there.
        if let id = now.deviceId, id != target {
            target = id
            SpotifyPlayingDevice.shared.name = Self.isThisPhone(now) ? nil : now.deviceName
            if !isHere { startPolling() }
        }
        let repeatRaw: UInt = switch now.repeatState {
        case "track": SPTAppRemotePlaybackOptionsRepeatMode.track.rawValue
        case "context": SPTAppRemotePlaybackOptionsRepeatMode.context.rawValue
        default: SPTAppRemotePlaybackOptionsRepeatMode.off.rawValue
        }
        apply(State(uri: uri, isPaused: !now.isPlaying, positionMs: now.progressMs, durationMs: now.durationMs,
                    shuffling: now.shuffle, repeatRaw: repeatRaw, title: now.trackName, artist: now.artistName,
                    album: now.albumName, coverURL: now.coverURL))
    }

    /// App Remote names a cover `spotify:image:<id>`; Spotify serves it at i.scdn.co/image/<id>.
    nonisolated static func coverURL(imageIdentifier: String) -> URL? {
        let prefix = "spotify:image:"
        guard imageIdentifier.hasPrefix(prefix) else { return nil }
        return URL(string: "https://i.scdn.co/image/" + imageIdentifier.dropFirst(prefix.count))
    }

    /// A song Spotify plays that isn't the queue's, as the engine shows it.
    nonisolated static func externalTrack(_ s: State) -> PlayableTrack? {
        guard let id = s.uri.split(separator: ":").last.map(String.init), !id.isEmpty,
              s.uri.hasPrefix("spotify:track:") || s.uri.hasPrefix("spotify:episode:") else { return nil }
        return PlayableTrack(id: id, provider: SpotifyMapping.provider, title: s.title ?? "Spotify",
                             artist: s.artist ?? "", album: s.album ?? "", albumId: nil,
                             durationSec: Double(s.durationMs) / 1000, streamURL: streamURL(trackId: id),
                             coverURL: s.coverURL)
    }

    private func apply(_ s: State) {
        PlayTrace.log("SP state \(s.uri) paused=\(s.isPaused) pos=\(s.positionMs) starting=\(starting) wantsPlay=\(wantsPlay)")
        let uri = s.uri
        let before = reportedTime
        lastTime = Double(s.positionMs) / 1000
        reportedTime = lastTime
        lastTick = Date()
        playing = !s.isPaused
        guard !starting else { return }
        if Self.readyToEnqueue(waiting: enqueueWhenPlaying, stateURI: uri, current: current?.trackURI,
                               paused: s.isPaused, positionMs: s.positionMs) {
            enqueueNext()
        }
        if let current, uri == current.trackURI {
            // Spotify's own song keeps Spotify's modes; only the queue's are the engine's.
            takeOverModes(shuffling: s.shuffling, repeatRaw: s.repeatRaw)
            if externalURI != nil {
                // Back on the queue's song.
                externalURI = nil
                wantsPlay = playing
                events?.outputExternalTrackDidChange(nil, positionSec: lastTime, playing: playing)
            }
            sawCurrent = true
            let d = Double(s.durationMs) / 1000
            if d > 0, d != duration {
                duration = d
                events?.outputDurationDidChange(d)
            }
            // Repeat one: Spotify moved from the first copy of the song to the second.
            if !s.isPaused, d > 0, handedNext == current, lastTime < 1, before >= d - Self.endSlackSec - 1.5 {
                handedNext = nil
                next = nil
                events?.outputDidAdvanceToPreloaded()
                events?.outputTimeDidChange(lastTime)
                return
            }
            events?.outputTimeDidChange(lastTime)
            // The list ran out: Spotify stops at the song's end, or back at its start right after it.
            let atEnd = lastTime >= d - Self.endSlackSec || (lastTime < 1 && before >= d - Self.endSlackSec - 1.5)
            if s.isPaused, wantsPlay, d > 0, atEnd {
                sawCurrent = false
                return events?.outputDidReachEnd() ?? ()
            }
            // Paused or played from the Spotify app or its lock screen card.
            if playing != wantsPlay {
                wantsPlay = playing
                events?.outputPlayingDidChangeExternally(playing)
            }
            return
        }
        if externalURI == nil, sawCurrent, current != nil {
            if let handed = handedNext, uri == handed.trackURI, handed == next {
                // Spotify carried on to the song the engine has next.
                current = handed
                self.next = nil
                handedNext = nil
                return events?.outputDidAdvanceToPreloaded() ?? ()
            }
            if duration > 0, before >= duration - Self.carriedOnWindowSec {
                // The song ran out and Spotify carried on by itself to something else (the list was
                // stale, or its autoplay): the engine starts its next, back in Yoin's queue.
                sawCurrent = false
                return events?.outputDidReachEnd() ?? ()
            }
        }
        // Picked in Spotify, here or on the device Yoin drives (or a start that never took): the
        // listener's choice. Yoin shows that song and controls it there; the queue stays parked and
        // Spotify is never taken back to it — Yoin never cuts into playback.
        guard let track = Self.externalTrack(s) else { return }
        sawCurrent = false
        externalURI = uri
        wantsPlay = playing
        events?.outputExternalTrackDidChange(track, positionSec: lastTime, playing: playing)
    }

    /// Next / previous on Spotify's own song: Spotify skips, here or on the device Yoin drives.
    func skipExternal(forward: Bool) {
        guard !silent else { return }
        if isHere { return command(aim(song: true)) { forward ? $0.skip(toNext: nil) : $0.skip(toPrevious: nil) } }
        transport(report: true, aim(song: true)) { try await $0.skipPlayback(forward: forward, deviceId: $1, while: $2) }
    }

    /// How close to its end the song was last seen for a switch to read as Spotify carrying on
    /// (the Web API is read every [pollSec]).
    nonisolated static let carriedOnWindowSec: Double = 8

    nonisolated static let endSlackSec: Double = 1.5

    /// Shuffle or repeat turned on in the Spotify app (Yoin keeps both off there): the engine takes the
    /// mode over and Spotify's goes back off. Off can't be told apart from Yoin's own setting.
    private func takeOverModes(shuffling: Bool, repeatRaw: UInt) {
        guard Date() >= modesQuietUntil,
              let modes = Self.externalModes(shuffling: shuffling, repeatRaw: repeatRaw)
        else { return }
        modesQuietUntil = Date().addingTimeInterval(Self.modesQuietSec)
        let device = target
        Task { await control?.playModesOff(deviceId: device) }
        events?.outputPlayModesDidChangeExternally(shuffle: modes.shuffle, repeatMode: modes.repeatMode)
    }

    /// What the Spotify app's modes ask of the engine; nil when both are off (Yoin's own state there).
    nonisolated static func externalModes(
        shuffling: Bool, repeatRaw: UInt
    ) -> (shuffle: Bool?, repeatMode: RepeatMode?)? {
        let repeatMode: RepeatMode? = switch repeatRaw {
        case SPTAppRemotePlaybackOptionsRepeatMode.track.rawValue: .one
        case SPTAppRemotePlaybackOptionsRepeatMode.context.rawValue: .all
        default: nil
        }
        guard shuffling || repeatMode != nil else { return nil }
        return (shuffling ? true : nil, repeatMode)
    }

    private func startTicking() {
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, self.playing else { return }
                self.events?.outputTimeDidChange(self.currentTimeSec)
            }
        }
    }
}

/// A Spotify start with nowhere to play, said so the listener knows what to do.
struct SpotifyPlaybackError: Error, LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

/// The Connect device Yoin is a remote for (nil: this iPhone), shown by Now Playing.
@MainActor
@Observable
final class SpotifyPlayingDevice {
    static let shared = SpotifyPlayingDevice()
    var name: String?
    /// Posted by Devices after a transfer (userInfo: id, name).
    nonisolated static let transferred = Notification.Name("SpotifyPlayingDevice.transferred")
}
