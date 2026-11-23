import Foundation
import Observation

/// Main-actor observable Sing session mounted on `AppModel.shared.sing`.
///
/// Coordinates:
/// - Track eligibility (`supportsSing`: Subsonic/local only; hidden on Spotify, Apple Music, podcasts)
/// - Cached Lyra `/api/v1/capabilities` probe (5 min TTL) + automatic fallback to on-device `SingCoreMLSeparator`
/// - Zero-popup preparing state (quiet button spinner while original stereo track keeps playing uninterrupted)
/// - Pre-buffered handover via `AVAudioOutput.enterSingMode` → ~0.95s ease-out vocal glide synced with capsule expansion
/// - Smooth ~0.75s ease-in-out vocal rise back to 1.0 before restoring the original stereo stream on exit
/// - Track-change carryover when Sing is already on
@MainActor
@Observable
final class SingSessionController {
    enum Phase: Equatable, Sendable {
        case off
        case preparing
        case active
        case unavailable
        case failed
    }

    private(set) var isEnabled: Bool = false
    private(set) var phase: Phase = .off
    var sliderPosition: Float = SingGainCurve.defaultSlider
    /// Incremented when Sing is enabled from the Cover Long-Press menu so `NowPlayingView` expands to Full-Screen Lyrics.
    private(set) var pendingExpandToLyricsTick: Int = 0
    /// Incremented whenever Sing preparation fails so the button shows a brief non-modal error cue.
    private(set) var failureTick: Int = 0

    var isActive: Bool { phase == .active }
    var isPreparing: Bool { phase == .preparing }

    @ObservationIgnored private var activeTrackKey: String?
    @ObservationIgnored private var prepareTask: Task<Void, Never>?
    @ObservationIgnored private var capabilitiesCache: [String: (enabled: Bool, checkedAt: Date)] = [:]
    @ObservationIgnored private let localSeparator: SingCoreMLSeparator
    @ObservationIgnored private let urlSession: URLSession

    /// Bound by `AppModel` so tests can substitute fake outputs/accounts without touching UIKit.
    @ObservationIgnored var avOutputProvider: () -> AVAudioOutput? = { nil }
    @ObservationIgnored var serverAccountProvider: () -> ServerSession.Account? = { nil }
    @ObservationIgnored var localAudioURLProvider: (PlayableTrack) -> URL? = { track in
        if track.streamURL.isFileURL { return track.streamURL }
        return OfflineDownloads.shared.localFile(for: track.streamURL)
    }

    init(
        localSeparator: SingCoreMLSeparator = SingCoreMLSeparator(),
        urlSession: URLSession = .shared
    ) {
        self.localSeparator = localSeparator
        self.urlSession = urlSession
    }

    /// True ONLY for self-hosted Subsonic/Lyra or local tracks. Commercial DRM sources (Spotify, Apple Music) return false.
    func supportsSing(_ track: PlayableTrack?) -> Bool {
        guard let track else { return false }
        return Self.supportsProvider(track.provider)
    }

    static func supportsProvider(_ provider: String) -> Bool {
        if provider == SpotifyMapping.provider || provider == AppleMusicMapping.provider {
            return false
        }
        return true
    }

    func toggle(track: PlayableTrack?, openLyrics: Bool = false) {
        if isEnabled {
            exit()
        } else {
            enter(track: track, openLyrics: openLyrics)
        }
    }

    func enter(track: PlayableTrack?, openLyrics: Bool = false) {
        guard let track, supportsSing(track) else {
            phase = .off
            isEnabled = false
            return
        }
        isEnabled = true
        if openLyrics {
            pendingExpandToLyricsTick += 1
        }
        startPreparing(for: track)
    }

    func exit() {
        prepareTask?.cancel()
        prepareTask = nil
        activeTrackKey = nil
        let wasEnabled = isEnabled
        isEnabled = false
        phase = .off
        guard wasEnabled else { return }
        avOutputProvider()?.exitSingMode()
    }

    func setSliderPosition(_ position: Float) {
        let clamped = max(0, min(1, position))
        sliderPosition = clamped
        if isActive {
            avOutputProvider()?.setSingVocalSlider(clamped)
        }
    }

    /// Carry Sing over on track change: new song plays normally until its quad stream is buffered, then glides in.
    func onTrackChanged(_ track: PlayableTrack?) {
        let nextKey = track.map { "\($0.provider):\($0.id)" }
        guard nextKey != activeTrackKey else { return }
        guard isEnabled else {
            activeTrackKey = nextKey
            return
        }
        guard let track else {
            prepareTask?.cancel()
            prepareTask = nil
            activeTrackKey = nil
            phase = .off
            return
        }
        guard supportsSing(track) else {
            // Commercial DRM song in mixed queue: keep user intent quiet, stay off on this track.
            prepareTask?.cancel()
            prepareTask = nil
            activeTrackKey = nextKey
            phase = .unavailable
            return
        }
        startPreparing(for: track)
    }

    private func startPreparing(for track: PlayableTrack) {
        prepareTask?.cancel()
        let trackKey = "\(track.provider):\(track.id)"
        activeTrackKey = trackKey
        phase = .preparing

        let localURL = localAudioURLProvider(track)
        let account = serverAccountProvider()
        let songId = Self.rawSubsonicSongId(from: track)

        prepareTask = Task { @MainActor [weak self] in
            guard let self else { return }
            let coordinator: SingCoordinator
            let origin: SingCoordinator.TrackOrigin

            if let account, let baseURL = URL(string: account.baseUrl) {
                let lyraSingReady = await self.probeLyraSingEnabled(account: account, baseURL: baseURL)
                let config = LyraSingServerConfig(
                    baseURL: baseURL,
                    username: account.username,
                    password: account.password
                )
                let remote = LyraSingRemoteClient(config: config, session: self.urlSession)
                coordinator = SingCoordinator(remoteProvider: remote, localProvider: self.localSeparator)
                origin = .lyra(songId: songId, localAudioURL: localURL, serverOnline: lyraSingReady)
            } else {
                coordinator = SingCoordinator(remoteProvider: nil, localProvider: self.localSeparator)
                origin = .navidrome(songId: songId, localAudioURL: localURL)
            }

            for await state in coordinator.prepare(origin: origin) {
                guard !Task.isCancelled, self.isEnabled, self.activeTrackKey == trackKey else { return }
                switch state {
                case .preparing, .downloadingModel:
                    self.phase = .preparing
                case .ready(let source):
                    guard let av = self.avOutputProvider() else {
                        // Preview / headless test fallback: transition directly to active.
                        self.phase = .active
                        return
                    }
                    // Keep phase == .preparing (button quietly spinning) until pre-buffer handover completes!
                    av.enterSingMode(
                        source: source,
                        sliderPosition: self.sliderPosition,
                        onHandoverReady: { [weak self] in
                            guard let self, self.isEnabled, self.activeTrackKey == trackKey else { return }
                            self.phase = .active
                        },
                        onFailure: { [weak self] in
                            guard let self, self.activeTrackKey == trackKey else { return }
                            self.failQuietly()
                        }
                    )
                    return
                case .unavailable, .needsModelDownload, .failed:
                    // Turn Sing off cleanly and emit a brief button-level error cue (no popups).
                    self.failQuietly()
                    return
                case .idle:
                    break
                }
            }
        }
    }

    private func failQuietly() {
        prepareTask?.cancel()
        prepareTask = nil
        activeTrackKey = nil
        isEnabled = false
        phase = .off
        failureTick += 1
    }

    private static func rawSubsonicSongId(from track: PlayableTrack) -> String {
        if let queryId = AVAudioOutput.songId(track.streamURL), !queryId.isEmpty {
            return queryId
        }
        return track.id
    }

    private func probeLyraSingEnabled(account: ServerSession.Account, baseURL: URL) async -> Bool {
        let key = "\(account.baseUrl)|\(account.username)"
        let now = Date()
        if let cached = capabilitiesCache[key], now.timeIntervalSince(cached.checkedAt) < 300 {
            return cached.enabled
        }
        guard var comps = URLComponents(url: baseURL.appendingPathComponent("api/v1/capabilities"), resolvingAgainstBaseURL: false) else {
            return false
        }
        let cfg = LyraSingServerConfig(baseURL: baseURL, username: account.username, password: account.password)
        comps.queryItems = cfg.authQueryItems()
        guard let url = comps.url else { return false }

        var req = URLRequest(url: url)
        req.timeoutInterval = 4
        do {
            let (data, resp) = try await urlSession.data(for: req)
            guard let http = resp as? HTTPURLResponse, (200...299).contains(http.statusCode),
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                capabilitiesCache[key] = (false, now)
                return false
            }
            let enabled: Bool
            if let singObj = json["sing"] as? [String: Any], let flag = singObj["enabled"] as? Bool {
                enabled = flag
            } else if let flag = json["sing"] as? Bool {
                enabled = flag
            } else if let caps = json["capabilities"] as? [String: Any],
                      let singObj = caps["sing"] as? [String: Any],
                      let flag = singObj["enabled"] as? Bool {
                enabled = flag
            } else {
                enabled = false
            }
            capabilitiesCache[key] = (enabled, now)
            return enabled
        } catch {
            return false
        }
    }
}
