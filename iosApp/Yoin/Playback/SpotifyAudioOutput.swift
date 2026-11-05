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
    /// Connected (after a wake too): the output carries on with what it was asked to start.
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
    func connect() async -> Bool {
        guard account != nil, let r = appRemote() else { return false }
        if r.isConnected { return true }
        if let token, let t = try? await token() { r.connectionParameters.accessToken = t }
        return await withCheckedContinuation { cont in
            connectWaiters.append(cont)
            if connectWaiters.count == 1 { r.connect() }
        }
    }

    /// Opens the Spotify app on [uri] (it starts playing it) and connects when it hands back.
    func wake(playing uri: String) {
        guard let r = appRemote() else { return onFailure?("Add your Spotify Client ID in Settings") ?? () }
        r.authorizeAndPlayURI(uri) { [weak self] opened in
            if !opened { MainActor.assumeIsolated { self?.onFailure?("Install the Spotify app to play Spotify songs") } }
        }
    }

    /// Spotify came back to yoin://auth/spotify/app-remote: its token, then the connection.
    func handle(_ url: URL) -> Bool {
        guard url.absoluteString.hasPrefix(SpotifyConfig.appRemoteRedirectURI), let r = appRemote() else { return false }
        let params = r.authorizationParameters(from: url)
        if let t = params?[SPTAppRemoteAccessTokenKey] {
            r.connectionParameters.accessToken = t
            r.connect()
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
            appRemote.playerAPI?.delegate = self
            appRemote.playerAPI?.subscribe(toPlayerState: nil)
            finishConnect(true)
            onConnected?()
        }
    }

    nonisolated func appRemote(_ appRemote: SPTAppRemote, didFailConnectionAttemptWithError error: Error?) {
        MainActor.assumeIsolated {
            Self.log.info("connect failed: \(error?.localizedDescription ?? "-", privacy: .public)")
            finishConnect(false)
        }
    }

    nonisolated func appRemote(_ appRemote: SPTAppRemote, didDisconnectWithError error: Error?) {
        MainActor.assumeIsolated {
            if let error { Self.log.info("disconnected: \(error.localizedDescription, privacy: .public)") }
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
/// `-silentAudio YES`: nothing is started (the Spotify app can't be muted from here); the engine still
/// moves through its queue.
@MainActor
final class SpotifyAudioOutput: AudioOutput {
    weak var events: AudioOutputEvents?
    /// Starts songs in the Spotify app (Web API PUT /me/player/play with these uris); set by the app.
    var startPlayback: ((_ uris: [String]) async throws -> Void)?

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
    private static let log = Logger(subsystem: "com.gpo.yoin", category: "spotify")

    /// A song as the engine names it: spotify-yoin://track/<id>.
    struct Item: Equatable {
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
        remote.onConnected = { [weak self] in self?.connected() }
        remote.onDisconnected = { [weak self] in self?.disconnected() }
    }

    var currentTimeSec: Double {
        playing ? lastTime + Date().timeIntervalSince(lastTick) : lastTime
    }

    func load(_ current: URL, next: URL?, startSec: Double) {
        generation += 1
        let item = Item(current)
        self.current = item
        self.next = Item(next)
        handedNext = nil
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

    private func start(_ item: Item, gen: Int) async {
        let connected = await remote.connect()
        guard gen == generation else { return }
        if !connected {
            // The Spotify app isn't running: it wakes on this very song (N01), hands back, connects,
            // and `connected()` makes the real start (with the next song and the position).
            remote.wake(playing: item.trackURI)
            return
        }
        await send(item, gen: gen)
    }

    /// The Web API start: [current, next] from the remembered position, Spotify's own modes off.
    private func send(_ item: Item, gen: Int) async {
        let next = self.next
        do {
            try await startPlayback?([item.trackURI] + (next.map { [$0.trackURI] } ?? []))
            guard gen == generation else { return }
            handedNext = next
            starting = false
            remote.player?.setShuffle(false, callback: nil)
            remote.player?.setRepeatMode(.off, callback: nil)
            if pendingStart > 1 { remote.player?.seek(toPosition: Int(pendingStart * 1000), callback: nil) }
            if !wantsPlay { remote.player?.pause(nil) }
        } catch {
            guard gen == generation else { return }
            starting = false
            Self.log.error("start failed: \(error.localizedDescription, privacy: .public)")
            events?.outputDidFail(SpotifySource.message(for: error), network: PlaybackFailureKind.isNetwork(error))
        }
    }

    /// Connected after a wake (or again after a drop): a start still waiting is made now.
    private func connected() {
        guard starting, let current, !silent else { return }
        let gen = generation
        Task { await send(current, gen: gen) }
    }

    /// The Spotify app went away (Codex N05): the clock stops; the next transport command reconnects.
    private func disconnected() {
        playing = false
        lastTick = Date()
        events?.outputTimeDidChange(lastTime)
    }

    func preload(_ next: URL?) { self.next = Item(next) }

    func play() {
        wantsPlay = true
        // A start on its way plays when it lands; resuming now would sound the previous song.
        guard !silent, !starting else { return }
        command { $0.resume(nil) }
    }

    func pause() {
        wantsPlay = false
        guard !silent, !starting else { return }
        command { $0.pause(nil) }
    }

    func seek(to sec: Double) {
        lastTime = sec
        lastTick = Date()
        pendingStart = sec
        events?.outputTimeDidChange(sec)
        guard !silent, !starting else { return }
        command { $0.seek(toPosition: Int(sec * 1000), callback: nil) }
    }

    /// A transport command, reconnecting first when the connection dropped (N05).
    private func command(_ act: @escaping (SPTAppRemotePlayerAPI) -> Void) {
        if remote.isConnected, let p = remote.player { return act(p) }
        Task {
            guard await remote.connect(), let p = remote.player else {
                events?.outputDidFail("Open the Spotify app to keep playing", network: false)
                return
            }
            act(p)
        }
    }

    func stop() {
        generation += 1
        if !silent, wantsPlay, remote.isConnected { remote.player?.pause(nil) }
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

    private func playerState(_ s: SPTAppRemotePlayerState) {
        let uri = s.track.uri
        let before = reportedTime
        lastTime = Double(s.playbackPosition) / 1000
        reportedTime = lastTime
        lastTick = Date()
        playing = !s.isPaused
        guard !starting else { return }
        if let current, uri == current.trackURI {
            sawCurrent = true
            let d = Double(s.track.duration) / 1000
            if d > 0, d != duration {
                duration = d
                events?.outputDurationDidChange(d)
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
