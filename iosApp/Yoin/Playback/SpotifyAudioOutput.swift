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

    /// Back in the foreground: a connection iOS cut while Yoin was suspended still reads as connected
    /// but carries nothing (no state, commands lost). It is dropped; the next need reconnects.
    func dropAfterSuspension() {
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
            // No connection is made: Spotify stopped as soon as an App Remote connection dropped,
            // and iOS drops it whenever it suspends Yoin. The Web API drives the phone from here.
            r.connectionParameters.accessToken = t
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
    weak var control: SpotifySource?

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
    /// Reads where Spotify is while Yoin is in the foreground (App Remote's state stops when iOS
    /// suspends Yoin, and says nothing about another device).
    private var pollTimer: Timer?
    private var refreshTask: Task<Void, Never>?
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
        remote.onWoken = { [weak self] in self?.woke() }
        remote.onDisconnected = { [weak self] in self?.disconnected() }
        let c = NotificationCenter.default
        observers.append(c.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil,
                                       queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.foreground() }
        })
        observers.append(c.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil,
                                       queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.stopPolling() }
        })
    }

    var currentTimeSec: Double {
        playing ? lastTime + Date().timeIntervalSince(lastTick) : lastTime
    }

    func load(_ current: URL, next: URL?, startSec: Double) {
        PlayTrace.log("SP load \(current.lastPathComponent) start=\(startSec)")
        generation += 1
        rehandTask?.cancel()
        let item = Item(current)
        self.current = item
        self.next = Item(next)
        handedNext = nil
        awaitingWake = false
        sawCurrent = false
        playing = false
        lastTime = startSec
        lastTick = Date()
        pendingStart = startSec
        duration = 0
        startTicking()
        guard let item else { return events?.outputDidFail("Not a Spotify song") ?? () }
        guard !silent else { return }
        startPolling()
        starting = true
        let gen = generation
        Task { await start(item, gen: gen) }
    }

    /// Where a start goes (51, 2026-11-06): another device that has Spotify keeps it, and Yoin is
    /// its remote; with none, this iPhone, woken through App Remote when the app is asleep.
    private func start(_ item: Item, gen: Int) async {
        let now = (try? await control?.playerNow()) ?? nil
        PlayTrace.log("SP start now=\(String(describing: now)) control=\(control != nil)")
        guard gen == generation else { return }
        if let now, let id = now.deviceId, !Self.isThisPhone(now) {
            target = id
            SpotifyPlayingDevice.shared.name = now.deviceName
            return await send(item, gen: gen)
        }
        SpotifyPlayingDevice.shared.name = nil
        target = now?.deviceId
        guard target != nil else {
            // No device has Spotify: the app here wakes on this very song (N01) and hands back;
            // `woke()` then hands it the pair from the Web API.
            awaitingWake = true
            remote.wake(playing: item.trackURI)
            return
        }
        await send(item, gen: gen)
    }

    /// This iPhone in Spotify's device list: the only phone named iPhone (an app can't read the
    /// device's own name, and a Pixel is a Smartphone too).
    nonisolated static func isThisPhone(_ now: SpotifySource.PlayerNow) -> Bool {
        now.deviceType.caseInsensitiveCompare("Smartphone") == .orderedSame
            && now.deviceName.localizedCaseInsensitiveContains("iPhone")
    }

    /// The Web API start: [current, next] from the remembered position, Spotify's own modes off.
    private func send(_ item: Item, gen: Int) async {
        let next = self.next
        // The start often runs as Spotify comes to the front: iOS would suspend Yoin mid-request.
        let bg = UIApplication.shared.beginBackgroundTask(withName: "spotify-start")
        defer { if bg != .invalid { UIApplication.shared.endBackgroundTask(bg) } }
        do {
            let at = pendingStart > 1 ? Int(pendingStart * 1000) : 0
            let uris = [item.trackURI] + (next.map { [$0.trackURI] } ?? [])
            PlayTrace.log("SP send PUT play \(uris) at=\(at) wantsPlay=\(wantsPlay) target=\(target ?? "-")")
            guard let control else { throw SpotifyPlaybackError("Sign in to Spotify again in Settings.") }
            do {
                try await control.startPlayback(uris: uris, positionMs: at, deviceId: target)
            } catch let e as SpotifyAPIError where e.isNoActiveDevice {
                // The Spotify app is connected but idle, so it isn't Spotify's active device yet.
                try await startOnThisPhone(item, uris: uris, at: at, gen: gen)
            }
            PlayTrace.log("SP send done gen=\(gen == generation)")
            guard gen == generation else { return }
            handedNext = next
            starting = false
            playing = wantsPlay
            lastTime = Double(at) / 1000
            lastTick = Date()
            modesQuietUntil = Date().addingTimeInterval(Self.modesQuietSec)
            Task { await control.playModesOff(deviceId: target) }
            if !wantsPlay { transport { try await $0.pausePlayback(deviceId: $1) } }
            refresh(after: 1)
            // The engine's next changed while this start was on its way.
            scheduleRehand()
        } catch is WakePending {
            return
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

    /// What a start with no active Spotify device does (Android's rule: never cut into playback,
    /// here or on another device).
    enum NoDeviceStep: Equatable {
        /// Another device is playing: say where, start nothing.
        case playingElsewhere(String)
        /// Nothing plays anywhere: start this song in the connected Spotify app on this phone.
        case playHere
        /// The Spotify app isn't connected: ask the listener to open it.
        case openSpotify
    }

    nonisolated static func noDeviceStep(now: SpotifySource.PlayerNow?, appRemoteConnected: Bool) -> NoDeviceStep {
        if let now, now.isPlaying { return .playingElsewhere(now.deviceName) }
        return appRemoteConnected ? .playHere : .openSpotify
    }

    /// No active device: App Remote starts the song in the Spotify app here, which makes this phone
    /// the active device; then the Web API hands it [current, next] from the position, on its id.
    private func startOnThisPhone(_ item: Item, uris: [String], at: Int, gen: Int) async throws {
        // No App Remote connection is held (Spotify stopped as soon as one dropped, and iOS drops it
        // whenever it suspends Yoin): an idle app is woken like an asleep one.
        let now = (try? await control?.playerNow()) ?? nil
        switch Self.noDeviceStep(now: now, appRemoteConnected: false) {
        case .playingElsewhere(let name):
            throw SpotifyPlaybackError("Spotify is playing on \(name). Switch it to this iPhone under Devices.")
        case .openSpotify:
            // Asleep: wake it on this song; the start is made when it hands back.
            awaitingWake = true
            remote.wake(playing: item.trackURI)
            throw WakePending()
        case .playHere:
            break
        }
    }

    /// The Spotify app woke on the song and handed back: once the Web API sees this phone as the
    /// active device, the start hands it [current, next] from the position on its id.
    private func woke() {
        guard awaitingWake, starting, let current, !silent else { return }
        awaitingWake = false
        let gen = generation
        Task {
            for _ in 0..<10 {
                guard gen == generation else { return }
                if let now = (try? await control?.playerNow()) ?? nil, let id = now.deviceId {
                    target = id
                    break
                }
                try? await Task.sleep(for: .milliseconds(300))
            }
            guard gen == generation else { return }
            await send(current, gen: gen)
        }
    }

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
        guard wantsPlay, playing, !starting, let current,
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
        // A start on its way plays when it lands; resuming now would sound the previous song.
        guard !silent, !starting else { return }
        // Paused with a stale next: start the new pair here instead of resuming the old one.
        if let current, Self.needsRehand(handed: handedNext, next: next, remainingSec: duration > 0 ? duration - lastTime : nil) {
            pendingStart = lastTime
            starting = true
            let gen = generation
            Task { await send(current, gen: gen) }
            return
        }
        playing = true
        lastTick = Date()
        transport { try await $0.resumePlayback(deviceId: $1) }
    }

    func pause() {
        PlayTrace.log("SP pause starting=\(starting)")
        wantsPlay = false
        rehandTask?.cancel()
        guard !silent, !starting else { return }
        lastTime = currentTimeSec
        lastTick = Date()
        playing = false
        transport { try await $0.pausePlayback(deviceId: $1) }
    }

    func seek(to sec: Double) {
        PlayTrace.log("SP seek \(sec)")
        lastTime = sec
        lastTick = Date()
        pendingStart = sec
        events?.outputTimeDidChange(sec)
        guard !silent, !starting else { return }
        let ms = Int(sec * 1000)
        transport { try await $0.seekPlayback(toMs: ms, deviceId: $1) }
    }

    /// A transport command through the Web API on the device Yoin drives (App Remote's connection
    /// dies while iOS suspends Yoin, and can't reach another device), then a fresh read of the state.
    private func transport(_ act: @escaping (SpotifySource, String?) async throws -> Void) {
        PlayTrace.log("SP transport target=\(target ?? "-")")
        let gen = generation
        Task {
            do {
                guard let control else { throw SpotifyPlaybackError("Sign in to Spotify again in Settings.") }
                try await act(control, target)
            } catch {
                guard gen == generation else { return }
                Self.log.error("command failed: \(error.localizedDescription, privacy: .public)")
                events?.outputDidFail(SpotifySource.message(for: error), network: true)
            }
            refresh(after: 0.6)
        }
    }

    // MARK: Reading where Spotify is (Web API)

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
        remote.dropAfterSuspension()
        guard current != nil, !silent else { return }
        startPolling()
        refresh()
    }

    /// One read of GET /me/player (after [delay]), applied as the Spotify app's state is.
    private func refresh(after delay: Double = 0) {
        guard current != nil, !silent, control != nil else { return }
        refreshTask?.cancel()
        refreshTask = Task { [weak self] in
            if delay > 0 { try? await Task.sleep(for: .seconds(delay)) }
            guard !Task.isCancelled, let self, let control = self.control else { return }
            guard let now = try? await control.playerNow() else {
                if !Task.isCancelled { self.apply(nil) }
                return
            }
            guard !Task.isCancelled else { return }
            self.apply(now)
        }
    }

    nonisolated static let pollSec: Double = 4

    func stop() {
        PlayTrace.log("SP stop wantsPlay=\(wantsPlay)")
        generation += 1
        rehandTask?.cancel()
        if !silent, wantsPlay, let control {
            let device = target
            Task { try? await control.pausePlayback(deviceId: device) }
        }
        stopPolling()
        refreshTask?.cancel()
        awaitingWake = false
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
    }

    private func playerState(_ s: SPTAppRemotePlayerState) {
        // App Remote speaks for this iPhone only.
        guard SpotifyPlayingDevice.shared.name == nil else { return }
        apply(State(uri: s.track.uri, isPaused: s.isPaused, positionMs: s.playbackPosition,
                    durationMs: Int(s.track.duration), shuffling: s.playbackOptions.isShuffling,
                    repeatRaw: s.playbackOptions.repeatMode.rawValue))
    }

    /// A Web API read: nothing active is Spotify stopped where it was.
    private func apply(_ now: SpotifySource.PlayerNow?) {
        guard !starting, !awaitingWake, current != nil else { return }
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
        }
        let repeatRaw: UInt = switch now.repeatState {
        case "track": SPTAppRemotePlaybackOptionsRepeatMode.track.rawValue
        case "context": SPTAppRemotePlaybackOptionsRepeatMode.context.rawValue
        default: SPTAppRemotePlaybackOptionsRepeatMode.off.rawValue
        }
        apply(State(uri: uri, isPaused: !now.isPlaying, positionMs: now.progressMs, durationMs: now.durationMs,
                    shuffling: now.shuffle, repeatRaw: repeatRaw))
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
        takeOverModes(shuffling: s.shuffling, repeatRaw: s.repeatRaw)
        if let current, uri == current.trackURI {
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
        guard sawCurrent, current != nil else { return }
        if let handed = handedNext, uri == handed.trackURI, handed == next {
            // Spotify carried on to the song the engine has next.
            current = handed
            self.next = nil
            handedNext = nil
            events?.outputDidAdvanceToPreloaded()
        } else {
            // Spotify moved on to a song that isn't the engine's next (the queue changed since, or
            // something was picked in Spotify): the current one is over; the engine starts its next,
            // which takes Spotify back to Yoin's queue.
            sawCurrent = false
            events?.outputDidReachEnd()
        }
    }

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

/// A start that waits on the Spotify app waking (not a failure).
private struct WakePending: Error {}

/// The Connect device Yoin is a remote for (nil: this iPhone), shown by Now Playing.
@MainActor
@Observable
final class SpotifyPlayingDevice {
    static let shared = SpotifyPlayingDevice()
    var name: String?
}
