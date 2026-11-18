import Foundation
import os
@preconcurrency import SpotifyiOS
import UIKit

/// What Yoin reads of the Spotify app's state (App Remote's SPTAppRemotePlayerState, or a test's).
struct RemotePlayerState: Equatable, Sendable {
    var uri: String
    var isPaused: Bool
    var positionMs: Int = 0
    var durationMs: Int = 0
    var shuffling: Bool = false
    var repeatRaw: UInt = 0
    var title: String? = nil
    var artist: String? = nil
    var album: String? = nil
    var imageIdentifier: String = ""
    /// The song's album, `spotify:album:<id>` (empty for a podcast episode or a local file).
    var albumURI: String? = nil
    var contextURI: String? = nil
    var contextTitle: String? = nil
}

struct SpotifyContentItem: Equatable, Sendable {
    let title: String
    let subtitle: String
    let uri: String
    let imageIdentifier: String?
    let isPlayable: Bool
    let isContainer: Bool
}

struct SpotifyContentContainer: Identifiable, Equatable, Sendable {
    var id: String { uri }
    let title: String
    let subtitle: String
    let uri: String
    let items: [SpotifyContentItem]
}

extension SpotifyRemote {
    enum ConnectFailure: Error, Sendable, Equatable {
        case noClientId
        case appMissing
        case premiumRequired
        case auth(String)
        case localRefused(String)
        case transport(String)

        var message: String {
            switch self {
            case .noClientId: return "Add your Spotify Client ID in Settings"
            case .appMissing: return SpotifyFailureCopy.appMissing
            case .premiumRequired: return "Spotify Premium is required to play"
            case .auth(let m): return m.isEmpty ? String(localized: "Reconnect Spotify in Settings") : m
            case .localRefused(let m): return m.isEmpty ? "Spotify didn't answer locally" : m
            case .transport(let m): return m.isEmpty ? "Couldn't reach Spotify" : m
            }
        }

        static func isLocalConnectionRefused(_ error: Error?) -> Bool {
            var current = error as NSError?
            for _ in 0..<8 {
                guard let ns = current else { return false }
                if ns.domain == NSPOSIXErrorDomain, ns.code == Int(ECONNREFUSED) { return true }
                current = ns.userInfo[NSUnderlyingErrorKey] as? NSError
            }
            return false
        }

        static func classify(error: Error?, message: String?, hasClientId: Bool = true) -> ConnectFailure {
            if !hasClientId { return .noClientId }
            let raw = (message?.isEmpty == false ? message : error?.localizedDescription) ?? ""
            if isLocalConnectionRefused(error) { return .localRefused(raw) }
            let lower = [message, error?.localizedDescription]
                .compactMap { $0?.lowercased() }
                .joined(separator: " ")
            if lower.contains("client_id") || lower.contains("client id") { return .noClientId }
            if lower.contains("not installed") || lower.contains("cannot open") { return .appMissing }
            if lower.contains("premium") { return .premiumRequired }
            if lower.contains("unauthor") || lower.contains("token") || lower.contains("auth")
                || lower.contains("consent") || lower.contains("invalid_grant")
                || lower.contains("revoked") || lower.contains("redirect")
                || lower.contains("access_denied") {
                return .auth(raw)
            }
            if lower.contains("connection refused") || (lower.contains("posix") && lower.contains("61")) {
                return .localRefused(raw)
            }
            return .transport(raw)
        }
    }
}

/// One App Remote connection to the Spotify app as SpotifyRemote drives it: the SDK's SPTAppRemote, or a
/// test's fake (a woken app's first connection that fails every request, 51's 11-08 log).
@MainActor
protocol AppRemoteLink: AnyObject {
    var isConnected: Bool { get }
    var accessToken: String? { get set }
    /// The SDK's connection events, set by SpotifyRemote.
    var onEstablished: (() -> Void)? { get set }
    var onConnectFailed: ((String) -> Void)? { get set }
    var onDisconnected: ((String) -> Void)? { get set }
    var onPlayerState: ((RemotePlayerState) -> Void)? { get set }
    var onConnectFailedError: ((Error?, String) -> Void)? { get set }
    func connect()
    func disconnect()
    /// Opens the Spotify app on [uri] (it plays it and hands back); false: the app isn't installed.
    func authorizeAndPlay(_ uri: String, opened: @escaping @MainActor (Bool) -> Void)
    /// The handback's token, or its error.
    func handback(_ url: URL) -> (token: String?, error: String?)
    func subscribe(_ done: @escaping (Error?) -> Void)
    func getPlayerState(_ done: @escaping (RemotePlayerState?, Error?) -> Void)
    func play(_ uri: String, _ done: @escaping (Error?) -> Void)
    func playContext(_ uri: String, index: Int, _ done: @escaping (Error?) -> Void)
    func resume(_ done: @escaping (Error?) -> Void)
    func pause(_ done: @escaping (Error?) -> Void)
    func seek(toMs ms: Int, _ done: @escaping (Error?) -> Void)
    func skip(forward: Bool, _ done: @escaping (Error?) -> Void)
    func enqueue(_ uri: String, _ done: @escaping (Error?) -> Void)
    func setShuffle(_ on: Bool)
    func setRepeat(_ mode: SPTAppRemotePlaybackOptionsRepeatMode)
    func fetchRecommendedContainers(_ completion: @escaping @MainActor ([SpotifyContentContainer]) -> Void)
}

@MainActor
extension AppRemoteLink {
    var onConnectFailedError: ((Error?, String) -> Void)? {
        get { nil }
        set {}
    }

    func fetchRecommendedContainers(_ completion: @escaping @MainActor ([SpotifyContentContainer]) -> Void) {
        completion([])
    }
}

/// The connection to the Spotify app (Android SpotifyAppRemotePlayer's connection half): App Remote
/// on the active Spotify account's token, woken with `authorizeAndPlayURI` when the app isn't running
/// (it opens Spotify, starts the song and comes back to yoin://auth/spotify/app-remote).
///
/// On a Mac (the iPad app there) there is no App Remote: the desktop Spotify app doesn't speak it, so
/// the remote is off and never connects or wakes anything (SpotifyAudioOutput drives the Web API).
///
/// A connection can come up and still answer nothing (51's 11-08 log: the one made right after a wake's
/// handback failed its subscription and every play with "The request failed", read as connected, and
/// every album after it failed on it). A failed subscription or a request failing that way marks the
/// connection broken: it is closed and a fresh one is opened, without waking Spotify.
@MainActor
final class SpotifyRemote {
    static let shared = SpotifyRemote()

    /// The active Spotify account's token (set by the app as accounts switch); nil: no Spotify account.
    var token: (() async throws -> String)?
    /// Called with the Spotify app's player state, on every change.
    var onPlayerState: ((RemotePlayerState) -> Void)?
    /// The connection failed or dropped with this message (a typed failure on Android).
    var onFailure: ((String) -> Void)?
    /// Structured connection failure (preserves NSPOSIXErrorDomain ECONNREFUSED / auth / missing Client ID).
    var onConnectFailure: ((ConnectFailure) -> Void)?
    private(set) var lastConnectFailure: ConnectFailure?
    private var connectInFlight = false
    /// The Spotify app woke on a song and handed back to Yoin.
    var onWoken: (() -> Void)?
    /// A connection is up (after a wake's handback too).
    var onConnected: (() -> Void)?
    /// The connection dropped (the Spotify app was closed or lost the session).
    var onDisconnected: (() -> Void)?

    private let makeLink: @MainActor (_ clientId: String, _ redirect: URL) -> AppRemoteLink
    private let clientIdOf: () -> String?
    private let enabled: () -> Bool
    private var link: AppRemoteLink?
    private var clientId: String?
    /// Whose remote this is (the account's profile id): another account gets a remote of its own.
    private(set) var account: String?
    private var connectWaiters: [CheckedContinuation<Bool, Never>] = []
    private static let log = Logger(subsystem: "com.gpo.yoin", category: "spotify")

    init(makeLink: @escaping @MainActor (String, URL) -> AppRemoteLink = { SDKAppRemoteLink(clientId: $0, redirect: $1) },
         clientId: @escaping () -> String? = { SpotifyConfig.clientId },
         enabled: @escaping () -> Bool = { !SpotifyFake.isOn && !Platform.current.isMac }) {
        self.makeLink = makeLink
        clientIdOf = clientId
        self.enabled = enabled
    }

    var isConnected: Bool { link?.isConnected ?? false }

    /// The connection reads as connected but a request on it failed: the next connect opens a new one.
    private(set) var broken = false
    /// Numbers each connection the SDK is asked for (the log names it: which one failed, which healed).
    private(set) var connectionNumber = 0
    /// Fresh connections opened on their own for a broken one since the last one that worked.
    private var heals = 0
    private var healTask: Task<Bool, Never>?
    /// A close Yoin asked for: not the Spotify app dropping the session.
    private var closing = false

    /// The account the remote now serves (Codex N06): a different one disconnects the old remote, so
    /// account B never drives the Spotify app through account A's session.
    func bind(account id: String?) {
        guard id != account else { return }
        disconnect()
        account = id
    }

    private func appRemote() -> AppRemoteLink? {
        guard let id = clientIdOf(), let url = URL(string: SpotifyConfig.appRemoteRedirectURI) else { return nil }
        if let link, clientId == id { return link }
        let l = makeLink(id, url)
        wire(l)
        link = l
        clientId = id
        return l
    }

    /// Connected to the Spotify app (true), trying once with the account's token. False when the app
    /// isn't running or won't take the token: the caller wakes it with [wake]. A broken connection is
    /// closed first and a fresh one opened.
    func connect(timeout: Double = SpotifyRemote.connectTimeoutSec) async -> Bool {
        PlayTrace.log("SR connect connected=\(isConnected)\(broken ? " broken #\(connectionNumber)" : "")")
        if let healTask { return await healTask.value }
        guard enabled(), account != nil else { return false }
        guard let r = appRemote() else {
            reportFailure(.noClientId)
            return false
        }
        if r.isConnected, broken {
            PlayTrace.log("SR reconnect #\(connectionNumber): broken")
            close(r)
            guard await closed(r) else { return false }
        }
        // A broken connection still reading as connected isn't one (audit 11-15 #6: past the old fixed
        // 600 ms gap an SDK still closing it read as connected).
        if r.isConnected { return !broken }
        if let token, let t = try? await token() { r.accessToken = t }
        if r.isConnected { return !broken }
        return await withCheckedContinuation { cont in
            connectWaiters.append(cont)
            guard connectWaiters.count == 1 else { return }
            lastConnectFailure = nil
            // One attempt per connect the SDK is asked for. With the Spotify app asleep the SDK neither
            // connects nor fails for a long while (Play looked dead until Spotify was opened by hand):
            // past [timeout] the caller wakes it. The timeout ends only its own attempt: an earlier
            // one's timer firing during a later connect failed it (Codex 11-07: after one song, another
            // album went to Spotify again).
            attempt += 1
            let mine = attempt
            if !connectInFlight {
                connectInFlight = true
                connectionNumber += 1
                PlayTrace.log("SR connecting #\(connectionNumber)")
                r.connect()
            } else {
                PlayTrace.log("SR connect joined in-flight #\(connectionNumber)")
            }
            Task { [weak self] in
                try? await Task.sleep(for: .seconds(timeout))
                guard let self, Self.timeoutEnds(attempt: mine, current: self.attempt) else { return }
                PlayTrace.log("SR connect timed out #\(self.connectionNumber)")
                self.finishConnect(false)
            }
        }
    }

    nonisolated static let connectTimeoutSec: Double = 3
    /// The close lands before the next connect, else the SDK reads the old connection as still up.
    nonisolated static let reconnectGapMs = 600
    /// How long a close is waited for past that gap before the reconnect gives up on it (audit 11-15 #6).
    nonisolated static let closeWaitCapMs = 2000

    /// Waits for [r]'s close to land: the gap, then until the SDK no longer reads it as connected (up to
    /// [closeWaitCapMs]). False when [r] was replaced meanwhile or never closed.
    private func closed(_ r: AppRemoteLink) async -> Bool {
        try? await Task.sleep(for: .milliseconds(Self.reconnectGapMs))
        var waited = 0
        while r.isConnected, r === link, waited < Self.closeWaitCapMs {
            try? await Task.sleep(for: .milliseconds(100))
            waited += 100
        }
        return r === link && !r.isConnected
    }
    /// Fresh connections opened on their own (a failed subscription) before the caller decides.
    nonisolated static let maxHeals = 2

    /// The connect attempt waiting for the SDK; a finished attempt moves it on.
    private var attempt = 0

    /// A timer ends a connect only while its own attempt is the one waiting.
    nonisolated static func timeoutEnds(attempt: Int, current: Int) -> Bool { attempt == current }

    /// App Remote's "The request failed" (-2001) and a terminated connection (-1002): the connection
    /// doesn't carry requests any more. Spotify's own refusals (-3000, try later) say nothing of it.
    nonisolated static func breaksConnection(_ error: Error) -> Bool {
        let e = error as NSError
        return e.domain == SPTAppRemoteErrorDomain
            && (e.code == SPTAppRemoteErrorCode.requestFailedError.rawValue
                || e.code == SPTAppRemoteErrorCode.connectionTerminatedError.rawValue)
    }

    /// A request on the connection failed as [breaksConnection] says: the next connect opens a new one.
    func noteFailure(_ error: Error, of request: String) {
        guard Self.breaksConnection(error), isConnected, !broken else { return }
        PlayTrace.log("SR #\(connectionNumber) broken: \(request) failed")
        broken = true
    }

    /// A start sent over the connection didn't land: the next connect opens a new one, whatever the
    /// error said.
    func markBroken(_ reason: String) {
        guard isConnected, !broken else { return }
        PlayTrace.log("SR #\(connectionNumber) broken: \(reason)")
        broken = true
    }

    /// The subscription failed: the connection is closed and a fresh one opened (no wake), at most
    /// [maxHeals] times until one works; past that the caller's next connect tries again.
    private func heal(_ r: AppRemoteLink, reason: String) {
        broken = true
        guard healTask == nil else { return }
        guard heals < Self.maxHeals else { return PlayTrace.log("SR #\(connectionNumber) broken (\(reason)): no more reconnects") }
        heals += 1
        PlayTrace.log("SR reconnect #\(connectionNumber) (\(heals)/\(Self.maxHeals)): \(reason)")
        healTask = Task { [weak self] in
            guard let self else { return false }
            self.close(r)
            let gone = await self.closed(r)
            self.healTask = nil
            guard gone else { return false }
            return await self.connect(timeout: Self.connectTimeoutSec)
        }
    }

    private func close(_ r: AppRemoteLink) {
        guard r.isConnected else { return }
        closing = true
        r.disconnect()
    }

    /// Yoin is leaving the foreground: iOS would cut the connection silently (it would still read as
    /// connected); it is closed cleanly instead and reconnected on return. Spotify keeps playing.
    func dropForBackground() {
        guard let link, link.isConnected else { return }
        // Yoin's own close: not Spotify going away (audit 11-15 #27: read as that, it stopped the clock
        // and showed the position App Remote last reported).
        closing = true
        link.disconnect()
    }

    private func reportFailure(_ failure: ConnectFailure) {
        lastConnectFailure = failure
        onConnectFailure?(failure)
        onFailure?(failure.message)
    }

    /// Loads Spotify's "Made for You" / recommended containers via App Remote Content API when connected on iOS.
    func fetchMadeForYouContainers() async -> [SpotifyContentContainer] {
        guard enabled(), let r = link, r.isConnected else { return [] }
        return await withCheckedContinuation { cont in
            var resumed = false
            r.fetchRecommendedContainers { containers in
                guard !resumed else { return }
                resumed = true
                cont.resume(returning: containers)
            }
        }
    }

    /// Opens the Spotify app on [uri] (it starts playing it) and connects when it hands back.
    func wake(playing uri: String) {
        PlayTrace.log("SR wake \(uri)")
        guard enabled() else {
            // Off for the QA fake, or on a Mac (no App Remote there; the output never asks).
            reportFailure(.transport(SpotifyFake.isOn ? "The QA fake doesn't play songs" : SpotifyFailureCopy.notOpenOnMac))
            return
        }
        guard let r = appRemote() else {
            reportFailure(.noClientId)
            return
        }
        r.authorizeAndPlay(uri) { [weak self] opened in
            guard !opened else { return }
            // Installed but refused (a tester's 11-15 log said "Install the Spotify app" while Spotify was
            // playing on that very iPhone): only a missing app is called missing.
            let installed = SpotifyApp.isInstalled
            PlayTrace.log("SR wake not opened installed=\(installed)")
            let failure: ConnectFailure = installed
                ? .transport(SpotifyFailureCopy.openSpotify(on: SpotifyThisDevice.current.word))
                : .appMissing
            self?.reportFailure(failure)
        }
    }

    /// Spotify came back to yoin://auth/spotify/app-remote: its token, then the connection.
    func handle(_ url: URL) -> Bool {
        PlayTrace.log("SR handback")
        guard url.absoluteString.hasPrefix(SpotifyConfig.appRemoteRedirectURI) else { return false }
        guard let r = appRemote() else {
            reportFailure(.noClientId)
            finishConnect(false)
            return true
        }
        let params = r.handback(url)
        if let t = params.token, !t.isEmpty {
            // The SDK's own flow: the woken app plays the song; connect with its token (never a
            // second connection over a live one) and drive it through App Remote from here.
            r.accessToken = t
            let activeConnect = connectInFlight || !connectWaiters.isEmpty
            if r.isConnected, broken {
                // A broken connection is still up (a wake after its starts failed): a fresh one.
                PlayTrace.log("SR reconnect #\(connectionNumber): broken (handback)")
                close(r)
                if !activeConnect {
                    Task { [weak self] in
                        guard let self, await self.closed(r) else { return }
                        guard !self.connectInFlight, self.connectWaiters.isEmpty, !r.isConnected else { return }
                        self.connectionNumber += 1
                        self.connectInFlight = true
                        PlayTrace.log("SR connecting #\(self.connectionNumber) (handback)")
                        r.connect()
                    }
                }
            } else if !r.isConnected, !activeConnect {
                connectionNumber += 1
                connectInFlight = true
                PlayTrace.log("SR connecting #\(connectionNumber) (handback)")
                r.connect()
            } else if !r.isConnected {
                PlayTrace.log("SR handback skipped duplicate connect (already in flight)")
            }
            onWoken?()
        } else if let e = params.error, !e.isEmpty {
            let failure = ConnectFailure.classify(error: nil, message: e, hasClientId: true)
            reportFailure(failure)
            finishConnect(false)
        } else {
            reportFailure(.auth("Spotify returned no token"))
            finishConnect(false)
        }
        return true
    }

    func disconnect() {
        healTask?.cancel()
        healTask = nil
        connectInFlight = false
        lastConnectFailure = nil
        if let link, link.isConnected {
            closing = true
            link.disconnect()
        }
        link = nil
        clientId = nil
        broken = false
        heals = 0
        finishConnect(false)
    }

    private func finishConnect(_ ok: Bool) {
        connectInFlight = false
        // Whatever ended this attempt, its timer no longer ends anything.
        attempt += 1
        let waiters = connectWaiters
        connectWaiters = []
        waiters.forEach { $0.resume(returning: ok) }
    }

    // MARK: The player (each request after a reconnect under way, on the connection it opens)

    private func request(_ name: String, _ send: @escaping (AppRemoteLink, @escaping (Error?) -> Void) -> Void,
                         _ done: ((Error?) -> Void)?) {
        let go = { [weak self] in
            guard let self, let link = self.link else { return done?(Self.notConnected) ?? () }
            send(link) { [weak self, weak link] error in
                if let error, let self, link === self.link { self.noteFailure(error, of: name) }
                done?(error)
            }
        }
        guard let healTask else { return go() }
        Task { _ = await healTask.value; go() }
    }

    nonisolated static let notConnected = NSError(domain: SPTAppRemoteErrorDomain,
                                                  code: SPTAppRemoteErrorCode.connectionTerminatedError.rawValue,
                                                  userInfo: [NSLocalizedDescriptionKey: "Not connected"])

    func play(_ uri: String, _ done: @escaping (Error?) -> Void) { request("play", { $0.play(uri, $1) }, done) }
    func playContext(_ uri: String, index: Int, _ done: @escaping (Error?) -> Void) {
        request("context play", { $0.playContext(uri, index: index, $1) }, done)
    }
    func resume(_ done: ((Error?) -> Void)? = nil) { request("resume", { $0.resume($1) }, done) }
    func pause(_ done: ((Error?) -> Void)? = nil) { request("pause", { $0.pause($1) }, done) }
    func enqueue(_ uri: String, _ done: @escaping (Error?) -> Void) { request("enqueue", { $0.enqueue(uri, $1) }, done) }
    /// A failed seek marks the connection as any failed request does (a tapped lyric line went nowhere).
    func seek(toMs ms: Int) { request("seek", { $0.seek(toMs: ms, $1) }, nil) }
    func skip(forward: Bool, _ done: ((Error?) -> Void)? = nil) {
        request(forward ? "next" : "previous", { $0.skip(forward: forward, $1) }, done)
    }
    func setShuffle(_ on: Bool) { link?.setShuffle(on) }
    func setRepeat(_ mode: SPTAppRemotePlaybackOptionsRepeatMode) { link?.setRepeat(mode) }

    func getPlayerState(_ done: @escaping (RemotePlayerState?) -> Void) {
        guard let link else { return done(nil) }
        link.getPlayerState { [weak self, weak link] state, error in
            if let error, let self, link === self.link { self.noteFailure(error, of: "player state") }
            done(state)
        }
    }

    // MARK: Connection events

    private func wire(_ l: AppRemoteLink) {
        l.onEstablished = { [weak self, weak l] in
            guard let self, let l, l === self.link else { return }
            PlayTrace.log("SR established #\(self.connectionNumber)")
            self.connectInFlight = false
            self.lastConnectFailure = nil
            self.broken = false
            self.closing = false
            self.subscribe(l, number: self.connectionNumber)
            self.finishConnect(true)
            self.onConnected?()
        }
        l.onConnectFailed = { [weak self, weak l] message in
            Self.log.info("connect failed: \(message, privacy: .public)")
            PlayTrace.log("SR connect failed \(message)")
            // A dropped remote's late failure isn't the current connection's.
            guard let self, let l, l === self.link else { return }
            self.connectInFlight = false
            let failure = ConnectFailure.classify(error: nil, message: message, hasClientId: true)
            self.lastConnectFailure = failure
            self.onConnectFailure?(failure)
            self.finishConnect(false)
        }
        l.onConnectFailedError = { [weak self, weak l] error, message in
            Self.log.info("connect failed: \(message, privacy: .public)")
            PlayTrace.log("SR connect failed \(message)")
            guard let self, let l, l === self.link else { return }
            self.connectInFlight = false
            let failure = ConnectFailure.classify(error: error, message: message, hasClientId: true)
            self.lastConnectFailure = failure
            self.onConnectFailure?(failure)
            self.finishConnect(false)
        }
        l.onDisconnected = { [weak self, weak l] message in
            PlayTrace.log("SR disconnected \(message)")
            guard let self, let l, l === self.link else { return }
            // A close Yoin asked for (a broken connection, an account switch) isn't Spotify dropping it.
            if self.closing { self.closing = false; return }
            self.onDisconnected?()
        }
        l.onPlayerState = { [weak self, weak l] s in
            guard let self, let l, l === self.link else { return }
            self.onPlayerState?(s)
        }
    }

    /// Player state pushes (51's log, 23:02:07: none came for 90 s after a wake's connection). A failed
    /// subscription is a connection that carries nothing (11-08: its plays failed too): it is replaced
    /// (heal). Once on, the state is read once so a start doesn't wait for the next change.
    private func subscribe(_ l: AppRemoteLink, number: Int) {
        l.subscribe { [weak self, weak l] error in
            guard let self, let l, l === self.link, number == self.connectionNumber else { return }
            if let error {
                PlayTrace.log("SR subscribe failed #\(number) \(error.localizedDescription)")
                return self.heal(l, reason: "subscribe failed")
            }
            PlayTrace.log("SR subscribed #\(number)")
            self.heals = 0
            l.getPlayerState { [weak self, weak l] state, _ in
                guard let self, let l, l === self.link, let state else { return }
                self.onPlayerState?(state)
            }
        }
    }
}

/// The SDK's SPTAppRemote behind [AppRemoteLink].
@MainActor
final class SDKAppRemoteLink: NSObject, AppRemoteLink {
    private let remote: SPTAppRemote
    var onEstablished: (() -> Void)?
    var onConnectFailed: ((String) -> Void)?
    var onConnectFailedError: ((Error?, String) -> Void)?
    var onDisconnected: ((String) -> Void)?
    var onPlayerState: ((RemotePlayerState) -> Void)?

    init(clientId: String, redirect: URL) {
        remote = SPTAppRemote(configuration: SPTConfiguration(clientID: clientId, redirectURL: redirect), logLevel: .error)
        super.init()
        remote.delegate = self
    }

    var isConnected: Bool { remote.isConnected }
    var accessToken: String? {
        get { remote.connectionParameters.accessToken }
        set { remote.connectionParameters.accessToken = newValue }
    }

    func connect() { remote.connect() }
    func disconnect() { remote.disconnect() }

    func authorizeAndPlay(_ uri: String, opened: @escaping @MainActor (Bool) -> Void) {
        remote.authorizeAndPlayURI(uri) { ok in MainActor.assumeIsolated { opened(ok) } }
    }

    func handback(_ url: URL) -> (token: String?, error: String?) {
        let params = remote.authorizationParameters(from: url)
        let token = params?[SPTAppRemoteAccessTokenKey]
        let error = params?[SPTAppRemoteErrorDescriptionKey]
            ?? params?["error"]
            ?? URLComponents(url: url, resolvingAgainstBaseURL: false)?
                .queryItems?.first(where: { $0.name == "error" })?.value
        return (token, error)
    }

    func fetchRecommendedContainers(_ completion: @escaping @MainActor ([SpotifyContentContainer]) -> Void) {
        guard let api = remote.contentAPI else {
            completion([])
            return
        }
        api.fetchRecommendedContentItems(forType: SPTAppRemoteContentTypeDefault, flattenContainers: false) { result, error in
            guard error == nil, let raw = result as? [SPTAppRemoteContentItem] else {
                Task { @MainActor in completion([]) }
                return
            }
            let candidates = raw.filter { $0.isContainer || !($0.children ?? []).isEmpty }
            guard !candidates.isEmpty else {
                Task { @MainActor in completion([]) }
                return
            }
            final class ContainerBox: @unchecked Sendable {
                let lock = NSLock()
                var items: [Int: SpotifyContentContainer] = [:]
            }
            let box = ContainerBox()
            let group = DispatchGroup()
            func mapItems(_ list: [SPTAppRemoteContentItem]) -> [SpotifyContentItem] {
                list.map { child in
                    SpotifyContentItem(
                        title: child.title ?? "",
                        subtitle: child.subtitle ?? "",
                        uri: child.uri,
                        imageIdentifier: child.imageIdentifier,
                        isPlayable: child.isPlayable,
                        isContainer: child.isContainer
                    )
                }
            }
            for (idx, container) in candidates.enumerated() {
                let existing = container.children ?? []
                if !existing.isEmpty || !container.isContainer {
                    let mapped = SpotifyContentContainer(
                        title: container.title ?? "",
                        subtitle: container.subtitle ?? "",
                        uri: container.uri,
                        items: mapItems(existing)
                    )
                    box.lock.lock()
                    box.items[idx] = mapped
                    box.lock.unlock()
                    continue
                }
                let containerTitle = container.title ?? ""
                let containerSubtitle = container.subtitle ?? ""
                let containerURI = container.uri
                group.enter()
                api.fetchChildren(of: container) { childResult, _ in
                    let fetched = (childResult as? [SPTAppRemoteContentItem]) ?? []
                    let mapped = SpotifyContentContainer(
                        title: containerTitle,
                        subtitle: containerSubtitle,
                        uri: containerURI,
                        items: mapItems(fetched)
                    )
                    box.lock.lock()
                    box.items[idx] = mapped
                    box.lock.unlock()
                    group.leave()
                }
            }
            group.notify(queue: .main) {
                box.lock.lock()
                let ordered = (0..<candidates.count).compactMap { box.items[$0] }
                box.lock.unlock()
                Task { @MainActor in completion(ordered) }
            }
        }
    }

    private func answer(_ done: @escaping (Error?) -> Void) -> SPTAppRemoteCallback {
        { _, error in MainActor.assumeIsolated { done(error) } }
    }

    func subscribe(_ done: @escaping (Error?) -> Void) {
        guard let player = remote.playerAPI else { return done(SpotifyRemote.notConnected) }
        player.subscribe(toPlayerState: answer(done))
    }

    func getPlayerState(_ done: @escaping (RemotePlayerState?, Error?) -> Void) {
        guard let player = remote.playerAPI else { return done(nil, SpotifyRemote.notConnected) }
        player.getPlayerState { state, error in
            MainActor.assumeIsolated { done((state as? SPTAppRemotePlayerState).map(Self.state), error) }
        }
    }

    func play(_ uri: String, _ done: @escaping (Error?) -> Void) {
        guard let player = remote.playerAPI else { return done(SpotifyRemote.notConnected) }
        player.play(uri, callback: answer(done))
    }

    func playContext(_ uri: String, index: Int, _ done: @escaping (Error?) -> Void) {
        guard let content = remote.contentAPI, let player = remote.playerAPI else { return done(SpotifyRemote.notConnected) }
        content.fetchContentItem(forURI: uri) { result, error in
            MainActor.assumeIsolated {
                guard let item = result as? SPTAppRemoteContentItem, error == nil else {
                    return done(error ?? SpotifyRemote.notConnected)
                }
                player.play(item, skipToTrackIndex: index) { _, error in MainActor.assumeIsolated { done(error) } }
            }
        }
    }

    func resume(_ done: @escaping (Error?) -> Void) { remote.playerAPI?.resume(answer(done)) }
    func pause(_ done: @escaping (Error?) -> Void) { remote.playerAPI?.pause(answer(done)) }
    func seek(toMs ms: Int, _ done: @escaping (Error?) -> Void) {
        guard let player = remote.playerAPI else { return done(SpotifyRemote.notConnected) }
        player.seek(toPosition: ms, callback: answer(done))
    }
    func skip(forward: Bool, _ done: @escaping (Error?) -> Void) {
        // No player: said so (a start waiting on the skip's answer would wait for ever).
        guard let player = remote.playerAPI else { return done(SpotifyRemote.notConnected) }
        forward ? player.skip(toNext: answer(done)) : player.skip(toPrevious: answer(done))
    }
    func enqueue(_ uri: String, _ done: @escaping (Error?) -> Void) {
        guard let player = remote.playerAPI else { return done(SpotifyRemote.notConnected) }
        player.enqueueTrackUri(uri, callback: answer(done))
    }
    func setShuffle(_ on: Bool) { remote.playerAPI?.setShuffle(on, callback: nil) }
    func setRepeat(_ mode: SPTAppRemotePlaybackOptionsRepeatMode) { remote.playerAPI?.setRepeatMode(mode, callback: nil) }

    nonisolated static func state(_ s: SPTAppRemotePlayerState) -> RemotePlayerState {
        let obj = s as? NSObject
        let contextURI: String? = if obj?.responds(to: NSSelectorFromString("contextURI")) == true {
            ((obj?.value(forKey: "contextURI") as? URL)?.absoluteString
                ?? (obj?.value(forKey: "contextURI") as? String))
                .flatMap { $0.isEmpty ? nil : $0 }
        } else {
            nil
        }
        let contextTitle: String? = if obj?.responds(to: NSSelectorFromString("contextTitle")) == true {
            (obj?.value(forKey: "contextTitle") as? String)
                .flatMap { $0.isEmpty ? nil : $0 }
        } else {
            nil
        }
        return RemotePlayerState(
            uri: s.track.uri, isPaused: s.isPaused, positionMs: s.playbackPosition,
            durationMs: Int(s.track.duration), shuffling: s.playbackOptions.isShuffling,
            repeatRaw: s.playbackOptions.repeatMode.rawValue, title: s.track.name,
            artist: s.track.artist.name, album: s.track.album.name,
            imageIdentifier: s.track.imageIdentifier, albumURI: s.track.album.uri,
            contextURI: contextURI, contextTitle: contextTitle
        )
    }
}

extension SDKAppRemoteLink: SPTAppRemoteDelegate, SPTAppRemotePlayerStateDelegate {
    nonisolated func appRemoteDidEstablishConnection(_ appRemote: SPTAppRemote) {
        MainActor.assumeIsolated {
            appRemote.playerAPI?.delegate = self
            onEstablished?()
        }
    }

    nonisolated func appRemote(_ appRemote: SPTAppRemote, didFailConnectionAttemptWithError error: Error?) {
        // The whole error, underlying ones too: "Connection attempt failed." alone said nothing of why
        // (a tester's 11-15 log, every connect failing in 10 ms while Spotify played here).
        let text = error.map { Self.describe($0 as NSError) } ?? "-"
        MainActor.assumeIsolated {
            if let onConnectFailedError {
                onConnectFailedError(error, text)
            } else {
                onConnectFailed?(text)
            }
        }
    }

    nonisolated static func describe(_ e: NSError) -> String {
        var parts = ["\(e.localizedDescription) [\(e.domain) \(e.code)]"]
        var next = e.userInfo[NSUnderlyingErrorKey] as? NSError
        while let u = next, parts.count < 4 {
            parts.append("← \(u.localizedDescription) [\(u.domain) \(u.code)]")
            next = u.userInfo[NSUnderlyingErrorKey] as? NSError
        }
        return parts.joined(separator: " ")
    }

    nonisolated func appRemote(_ appRemote: SPTAppRemote, didDisconnectWithError error: Error?) {
        MainActor.assumeIsolated { onDisconnected?(error?.localizedDescription ?? "-") }
    }

    nonisolated func playerStateDidChange(_ playerState: SPTAppRemotePlayerState) {
        let s = Self.state(playerState)
        MainActor.assumeIsolated { onPlayerState?(s) }
    }
}
