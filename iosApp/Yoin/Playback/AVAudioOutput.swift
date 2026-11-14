import AVFoundation
import os

/// [AudioOutput] on an AVQueuePlayer: the current item plus one preloaded
/// next item, so the hand-over at a song's end needs no new request.
@MainActor
final class AVAudioOutput: AudioOutput {
    weak var events: AudioOutputEvents?

    private let player = AVQueuePlayer()
    private var current: AVPlayerItem?
    private var next: AVPlayerItem?
    private var nextURL: URL?
    /// Each item's stream URL (a cached asset's own URL is the cache's, not the stream's).
    private var itemURLs: [ObjectIdentifier: URL] = [:]
    private var timeObserver: Any?
    private var observations: [NSKeyValueObservation] = []
    private var itemObservations: [NSKeyValueObservation] = []
    private var endObservers: [NSObjectProtocol] = []
    static let log = Logger(subsystem: "com.gpo.yoin", category: "player")

    /// A stream URL as it may be logged (Android PlayTrace.describe): the scheme, the endpoint and the
    /// song / delivery parameters, never the server's address, the credentials (`u`, `t`, `s`, `p`,
    /// `apiKey`) or anything unknown. The exported log goes to whoever the listener sends it to.
    nonisolated static func describe(_ url: URL) -> String {
        let safe: Set<String> = ["id", "maxBitRate", "format", "timeOffset", "estimateContentLength"]
        guard let c = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return "<url>" }
        let items = (c.queryItems ?? []).filter { safe.contains($0.name) }
            .map { "\($0.name)=\($0.value ?? "")" }.joined(separator: "&")
        let endpoint = c.path.split(separator: "/").last.map(String.init) ?? ""
        return "\(c.scheme ?? "")://…/\(endpoint)" + (items.isEmpty ? "" : "?" + items)
    }

    init() {
        player.actionAtItemEnd = .advance
        player.automaticallyWaitsToMinimizeStalling = true
        // `-silentAudio YES`: play muted (silent QA runs; the clock still advances).
        player.isMuted = UserDefaults.standard.bool(forKey: "silentAudio")
        // ≤ 4 Hz: the progress UI and the scrobble clock are all that read it.
        timeObserver = player.addPeriodicTimeObserver(
            forInterval: CMTime(value: 1, timescale: 4), queue: .main
        ) { [weak self] time in
            MainActor.assumeIsolated {
                guard let self, self.player.currentItem != nil else { return }
                self.events?.outputTimeDidChange(time.seconds.isFinite ? time.seconds : 0)
            }
        }
        observations.append(player.observe(\.timeControlStatus, options: [.new]) { [weak self] player, _ in
            let waiting = player.timeControlStatus == .waitingToPlayAtSpecifiedRate
            Task { @MainActor in self?.events?.outputIsBufferingDidChange(waiting) }
        })
        observations.append(player.observe(\.currentItem, options: [.new]) { [weak self] _, _ in
            Task { @MainActor in self?.currentItemDidChange() }
        })
        // Volume leveling changed: the song playing and the one preloaded follow at once (Android #139).
        endObservers.append(NotificationCenter.default.addObserver(forName: ReplayGainSettings.changed, object: nil,
                                                                   queue: .main) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self else { return }
                for item in [self.current, self.next].compactMap({ $0 }) { self.applyGain(item) }
            }
        })
    }

    /// The song id a stream URL names (Subsonic `id`); nil for other services.
    nonisolated static func songId(_ url: URL) -> String? {
        URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "id" }?.value
    }

    /// ReplayGain as the item's audio mix (per item, so the preloaded next song has its own). Unity: no mix.
    private func applyGain(_ item: AVPlayerItem) {
        // Spatial audio for 5.1 / 7.1 files only; stereo songs are never changed (Android #139).
        item.allowedAudioSpatializationFormats = ReplayGainSettings.shared.spatializeMultichannel ? .multichannel : []
        guard let url = itemURLs[ObjectIdentifier(item)] ?? (item.asset as? AVURLAsset)?.url else { return }
        let gain = ReplayGainSettings.shared.applied(songId: Self.songId(url))
        guard gain.linear < 0.999 else { item.audioMix = nil; return }
        let asset = item.asset
        Task { @MainActor [weak item] in
            guard let tracks = try? await asset.loadTracks(withMediaType: .audio), let track = tracks.first,
                  let item else { return }
            let params = AVMutableAudioMixInputParameters(track: track)
            params.setVolume(Float(gain.linear), at: .zero)
            let mix = AVMutableAudioMix()
            mix.inputParameters = [params]
            item.audioMix = mix
            PlayTrace.log(String(format: "AV gain %.1f dB%@", gain.db, gain.peakLimited ? " (peak)" : ""))
        }
    }

    var currentTimeSec: Double {
        let t = player.currentTime().seconds
        return t.isFinite ? t : 0
    }

    func load(_ url: URL, next nextURL: URL?, startSec: Double) {
        PlayTrace.log("AV load \(Self.describe(url))")
        player.removeAllItems()
        let item = makeItem(url)
        current = item
        if startSec > 0 {
            item.seek(to: CMTime(seconds: startSec, preferredTimescale: 600), completionHandler: nil)
        }
        player.insert(item, after: nil)
        observeCurrent(item)
        next = nil
        self.nextURL = nil
        preload(nextURL)
        // Never the URLs themselves: a Subsonic stream URL carries u/t/s, enough to sign in as the user.
        Self.log.info("load \(Self.describe(url), privacy: .public) next=\(nextURL.map(Self.describe) ?? "-", privacy: .public)")
    }

    func preload(_ url: URL?) {
        if let next { player.remove(next) }
        next = nil
        nextURL = url
        guard let url, let current else { return }
        let item = makeItem(url)
        if player.canInsert(item, after: current) {
            player.insert(item, after: current)
            next = item
        }
    }

    func play() { PlayTrace.log("AV play"); player.play() }
    func pause() { PlayTrace.log("AV pause"); player.pause() }

    func seek(to sec: Double) {
        player.seek(to: CMTime(seconds: sec, preferredTimescale: 600),
                    toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                self.events?.outputTimeDidChange(self.currentTimeSec)
            }
        }
    }

    func stop() {
        PlayTrace.log("AV stop")
        player.pause()
        player.removeAllItems()
        current = nil
        next = nil
        nextURL = nil
        itemObservations = []
    }

    // MARK: -

    private func makeItem(_ url: URL) -> AVPlayerItem {
        // A Subsonic stream reads through the disk cache (Data/Cache): stored
        // as it plays, played offline once held.
        let item = AVPlayerItem(asset: CachedAudio.asset(for: url))
        // Buffer ahead a little so the preloaded item is ready at the hand-over.
        item.preferredForwardBufferDuration = 30
        itemURLs[ObjectIdentifier(item)] = url
        if itemURLs.count > 8 { itemURLs = itemURLs.filter { [current, next].compactMap { $0 }.map(ObjectIdentifier.init).contains($0.key) } }
        applyGain(item)
        return item
    }

    /// Status, duration and end-of-item for whichever item is current.
    private func observeCurrent(_ item: AVPlayerItem) {
        let id = ObjectIdentifier(item)
        itemObservations = [
            item.observe(\.status, options: [.new]) { [weak self] item, _ in
                let failed = item.status == .failed
                let message = item.error?.localizedDescription ?? "unknown error"
                let network = PlaybackFailureKind.isNetwork(item.error)
                Task { @MainActor in
                    guard let self, failed, self.isCurrent(id) else { return }
                    Self.log.error("item failed: \(PlayTrace.maskHosts(message), privacy: .public) network=\(network)")
                    self.events?.outputDidFail(message, network: network)
                }
            },
            item.observe(\.duration, options: [.new]) { [weak self] item, _ in
                let sec = item.duration.seconds
                Task { @MainActor in
                    guard let self, self.isCurrent(id), sec.isFinite else { return }
                    self.events?.outputDurationDidChange(sec)
                }
            },
        ]
        for o in endObservers { NotificationCenter.default.removeObserver(o) }
        endObservers = [
            NotificationCenter.default.addObserver(
                forName: AVPlayerItem.failedToPlayToEndTimeNotification, object: item, queue: .main
            ) { [weak self] note in
                let error = note.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error
                let message = error?.localizedDescription ?? "playback stopped"
                let network = PlaybackFailureKind.isNetwork(error)
                MainActor.assumeIsolated {
                    guard let self, self.isCurrent(id) else { return }
                    self.events?.outputDidFail(message, network: network)
                }
            },
        ]
    }

    private func isCurrent(_ id: ObjectIdentifier) -> Bool {
        current.map(ObjectIdentifier.init) == id
    }

    /// AVQueuePlayer moved on by itself: to the preloaded item, or past the last one.
    private func currentItemDidChange() {
        let now = player.currentItem
        guard now !== current else { return }
        if let now, now === next {
            current = now
            next = nil
            nextURL = nil
            observeCurrent(now)
            Self.log.info("advanced to preloaded item")
            events?.outputDidAdvanceToPreloaded()
        } else if now == nil, current != nil {
            current = nil
            itemObservations = []
            Self.log.info("reached end of queue")
            events?.outputDidReachEnd()
        }
    }
}
