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

    private var remote: SPTAppRemote?
    private var clientId: String?
    private var connectWaiters: [CheckedContinuation<Bool, Never>] = []
    private static let log = Logger(subsystem: "com.gpo.yoin", category: "spotify")

    var isConnected: Bool { remote?.isConnected ?? false }

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
        guard let r = appRemote() else { return false }
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
            appRemote.playerAPI?.delegate = self
            appRemote.playerAPI?.subscribe(toPlayerState: nil)
            finishConnect(true)
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
        }
    }

    nonisolated func playerStateDidChange(_ playerState: SPTAppRemotePlayerState) {
        MainActor.assumeIsolated { onPlayerState?(playerState) }
    }
}

/// [AudioOutput] on the Spotify app (Android SpotifyAppRemotePlayer + SpotifyStartPlan, simplified): a
/// song starts through the Web API with its album as the context (`offset.uri` = the song), so the
/// Spotify app shows where it plays from and carries on by itself; App Remote reports the app's state
/// back. The engine keeps the queue: when Spotify moves on to the song the engine preloaded, that is
/// reported as the advance; any other song ending is reported as the end, and the engine starts the
/// next one. Pause, resume and seek go through App Remote.
///
/// `-silentAudio YES`: nothing is started (the Spotify app can't be muted from here); the engine still
/// moves through its queue.
@MainActor
final class SpotifyAudioOutput: AudioOutput {
    weak var events: AudioOutputEvents?
    /// Starts a song in the Spotify app (Web API PUT /me/player/play); set by the app.
    var startPlayback: ((_ trackURI: String, _ contextURI: String?) async throws -> Void)?

    private let remote = SpotifyRemote.shared
    private let silent = UserDefaults.standard.bool(forKey: "silentAudio")
    private var current: Item?
    private var next: Item?
    private var generation = 0
    private var wantsPlay = false
    private var lastTime: Double = 0
    private var lastTick = Date()
    private var playing = false
    private var duration: Double = 0
    /// The Spotify app has been seen on the current song (an end is only read after that).
    private var sawCurrent = false
    private var timer: Timer?
    private static let log = Logger(subsystem: "com.gpo.yoin", category: "spotify")

    /// A song as the engine names it: spotify://track/<id>?album=<id>.
    struct Item: Equatable {
        var trackId: String
        var albumId: String?
        var trackURI: String { "spotify:track:\(trackId)" }
        var contextURI: String? { albumId.map { "spotify:album:\($0)" } }

        init?(_ url: URL?) {
            guard let url, url.scheme == SpotifyAudioOutput.scheme else { return nil }
            let id = url.lastPathComponent
            guard !id.isEmpty, id != "/" else { return nil }
            trackId = id
            albumId = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "album" }?.value
        }
    }

    nonisolated static let scheme = "spotify-yoin"

    /// The engine's stand-in stream URL for a Spotify song (its album, when known, is the context).
    nonisolated static func streamURL(trackId: String, albumId: String?) -> URL {
        var c = URLComponents()
        c.scheme = scheme
        c.host = "track"
        c.path = "/" + trackId
        if let albumId { c.queryItems = [URLQueryItem(name: "album", value: albumId)] }
        return c.url ?? URL(string: "\(scheme)://track/unknown")!
    }

    nonisolated static func isSpotify(_ url: URL?) -> Bool { url?.scheme == scheme }

    init() {
        remote.onPlayerState = { [weak self] s in self?.playerState(s) }
        remote.onFailure = { [weak self] m in self?.events?.outputDidFail(m, network: false) }
    }

    var currentTimeSec: Double {
        playing ? lastTime + Date().timeIntervalSince(lastTick) : lastTime
    }

    func load(_ current: URL, next: URL?, startSec: Double) {
        generation += 1
        let gen = generation
        let item = Item(current)
        self.current = item
        self.next = Item(next)
        sawCurrent = false
        lastTime = startSec
        lastTick = Date()
        duration = 0
        startTicking()
        guard let item else { return events?.outputDidFail("Not a Spotify song") ?? () }
        guard !silent else { return }
        Task { await start(item, at: startSec, gen: gen) }
    }

    private func start(_ item: Item, at startSec: Double, gen: Int) async {
        let connected = await remote.connect()
        guard gen == generation else { return }
        if !connected {
            // The Spotify app isn't running: it wakes on this song (and plays it), then connects.
            remote.wake(playing: item.contextURI ?? item.trackURI)
            return
        }
        do {
            try await startPlayback?(item.trackURI, item.contextURI)
            if startSec > 1 { remote.player?.seek(toPosition: Int(startSec * 1000), callback: nil) }
            if !wantsPlay { remote.player?.pause(nil) }
        } catch {
            guard gen == generation else { return }
            Self.log.error("start failed: \(error.localizedDescription, privacy: .public)")
            events?.outputDidFail(SpotifySource.message(for: error), network: PlaybackFailureKind.isNetwork(error))
        }
    }

    func preload(_ next: URL?) { self.next = Item(next) }

    func play() {
        wantsPlay = true
        guard !silent else { return }
        remote.player?.resume(nil)
    }

    func pause() {
        wantsPlay = false
        guard !silent else { return }
        remote.player?.pause(nil)
    }

    func seek(to sec: Double) {
        lastTime = sec
        lastTick = Date()
        events?.outputTimeDidChange(sec)
        guard !silent else { return }
        remote.player?.seek(toPosition: Int(sec * 1000), callback: nil)
    }

    func stop() {
        generation += 1
        if !silent, wantsPlay { remote.player?.pause(nil) }
        wantsPlay = false
        current = nil
        next = nil
        playing = false
        timer?.invalidate()
        timer = nil
    }

    // MARK: The Spotify app's state

    private func playerState(_ s: SPTAppRemotePlayerState) {
        let uri = s.track.uri
        lastTime = Double(s.playbackPosition) / 1000
        lastTick = Date()
        playing = !s.isPaused
        if let current, uri == current.trackURI {
            sawCurrent = true
            let d = Double(s.track.duration) / 1000
            if d > 0, d != duration {
                duration = d
                events?.outputDurationDidChange(d)
            }
            events?.outputTimeDidChange(lastTime)
            // Paused or played from the Spotify app or its lock screen card.
            if playing != wantsPlay {
                wantsPlay = playing
                events?.outputPlayingDidChangeExternally(playing)
            }
            return
        }
        guard sawCurrent, current != nil else { return }
        if let next, uri == next.trackURI {
            // Spotify carried on to the song the engine has next.
            current = next
            self.next = nil
            sawCurrent = true
            events?.outputDidAdvanceToPreloaded()
        } else {
            // Another song: the current one ended (or the user picked something in Spotify).
            sawCurrent = false
            events?.outputDidReachEnd()
        }
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
