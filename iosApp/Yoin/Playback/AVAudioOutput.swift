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
    private let singMixer = SingQuadDownmixer(initialGain: 1.0)
    private var singActive = false
    private var singOriginalURL: URL?
    private var singPrebufferTask: Task<Void, Never>?
    private var singExitTask: Task<Void, Never>?
    private var singStandbyPlayer: AVPlayer?
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
        player.isMuted = LaunchConfig.silentAudio
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
        cancelSingTransitions()
        singActive = false
        singOriginalURL = url
        singMixer.setVocalGain(1.0, rampSeconds: 0)
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
        cancelSingTransitions()
        singActive = false
        singOriginalURL = nil
        singMixer.setVocalGain(1.0, rampSeconds: 0)
        player.pause()
        player.removeAllItems()
        current = nil
        next = nil
        nextURL = nil
        itemObservations = []
    }

    // MARK: - Sing 4-channel quad playback

    private func cancelSingTransitions() {
        singPrebufferTask?.cancel()
        singPrebufferTask = nil
        singExitTask?.cancel()
        singExitTask = nil
        singStandbyPlayer?.pause()
        singStandbyPlayer?.replaceCurrentItem(with: nil)
        singStandbyPlayer = nil
    }

    private static func bufferedAheadSeconds(for item: AVPlayerItem, at positionSec: Double) -> Double {
        let ranges = item.loadedTimeRanges.map(\.timeRangeValue)
        var maxEnd = positionSec
        for range in ranges {
            let start = range.start.seconds
            let end = (range.start + range.duration).seconds
            if start.isFinite, end.isFinite, start <= positionSec + 0.5, end > maxEnd {
                maxEnd = end
            }
        }
        return max(0, maxEnd - positionSec)
    }

    /// Pre-buffers a 4-channel Quad AAC Sing stream (`[I_L, I_R, V_L, V_R]`) on a muted standby player
    /// while the original stereo track keeps playing uninterrupted. Once buffered ahead of the live
    /// playhead, swaps in frame-accurately at `g = 1.0` (inaudible cut-in), invokes `onHandoverReady`,
    /// and smoothly glides vocals down along `.easeOutCubic` over `~0.95 s`.
    func enterSingMode(
        source: SingQuadSource,
        sliderPosition: Float = SingGainCurve.defaultSlider,
        onHandoverReady: (@MainActor () -> Void)? = nil,
        onFailure: (@MainActor () -> Void)? = nil
    ) {
        cancelSingTransitions()
        if singOriginalURL == nil, let cur = current {
            singOriginalURL = itemURLs[ObjectIdentifier(cur)] ?? (cur.asset as? AVURLAsset)?.url
        }

        let asset = AVURLAsset(url: source.url)
        let item = AVPlayerItem(asset: asset)
        item.preferredForwardBufferDuration = 30
        // Quad Sing channels are [I_L, I_R, V_L, V_R], never spatialized surround.
        item.allowedAudioSpatializationFormats = []

        singPrebufferTask = Task { @MainActor [weak self, weak item] in
            guard let self, let item else { return }
            defer {
                if self.singStandbyPlayer?.currentItem === item {
                    self.singStandbyPlayer?.replaceCurrentItem(with: nil)
                }
                self.singStandbyPlayer?.pause()
                self.singStandbyPlayer = nil
            }

            guard let tracks = try? await asset.loadTracks(withMediaType: .audio),
                  let track = tracks.first,
                  let mix = SingQuadAudioTap.makeAudioMix(for: track, mixer: self.singMixer) else {
                PlayTrace.log("AV Sing tap attach failed")
                onFailure?()
                return
            }
            guard !Task.isCancelled else { return }
            item.audioMix = mix

            // Pre-buffer on a muted standby player so the original stereo track plays uninterrupted
            // until the quad stream has decoded & buffered ahead of the live playhead.
            let standby = AVPlayer(playerItem: item)
            standby.isMuted = true
            standby.automaticallyWaitsToMinimizeStalling = false
            self.singStandbyPlayer = standby

            let initialPos = self.currentTimeSec
            if initialPos > 0 {
                await item.seek(
                    to: CMTime(seconds: initialPos, preferredTimescale: 600),
                    toleranceBefore: .zero,
                    toleranceAfter: .zero
                )
            }

            let isLocal = source.url.isFileURL
            let targetBufferAhead: Double = isLocal ? 0.25 : 2.0
            let deadline = ProcessInfo.processInfo.systemUptime + (isLocal ? 3.0 : 8.0)

            while !Task.isCancelled {
                if item.status == .failed {
                    PlayTrace.log("AV Sing prebuffer failed: \(item.error?.localizedDescription ?? "unknown")")
                    onFailure?()
                    return
                }
                if item.status == .readyToPlay {
                    let ahead = Self.bufferedAheadSeconds(for: item, at: self.currentTimeSec)
                    if isLocal || item.isPlaybackLikelyToKeepUp || ahead >= targetBufferAhead {
                        break
                    }
                }
                if ProcessInfo.processInfo.systemUptime >= deadline {
                    if item.status == .readyToPlay { break }
                    PlayTrace.log("AV Sing prebuffer timed out")
                    onFailure?()
                    return
                }
                try? await Task.sleep(for: .milliseconds(40))
            }
            guard !Task.isCancelled else { return }

            // Detach from standby cleanly before inserting into the live queue player.
            standby.pause()
            standby.replaceCurrentItem(with: nil)
            self.singStandbyPlayer = nil

            let livePos = self.currentTimeSec
            let wasPlaying = self.player.rate > 0
            let savedNext = self.nextURL

            // Lock mixer at unity (1.0 = exact original stereo mix) BEFORE inserting so cut-in is inaudible.
            self.singMixer.setVocalGain(1.0, rampSeconds: 0)
            self.singActive = true

            if livePos > 0 {
                await item.seek(
                    to: CMTime(seconds: livePos, preferredTimescale: 600),
                    toleranceBefore: .zero,
                    toleranceAfter: .zero
                )
            }
            guard !Task.isCancelled, self.singActive else { return }

            self.player.removeAllItems()
            self.current = item
            self.player.insert(item, after: nil)
            self.observeCurrent(item)
            self.next = nil
            self.nextURL = nil
            self.preload(savedNext)
            if wasPlaying { self.player.play() }

            // Notify UI to expand capsule in exact sync with the ~0.95s ease-out vocal fade.
            onHandoverReady?()
            self.singMixer.setSliderPosition(
                sliderPosition,
                rampSeconds: SingGainCurve.cutInRampSeconds,
                curve: .easeOutCubic
            )
            PlayTrace.log("AV Sing active (slider=\(Int((sliderPosition * 100).rounded()))%)")
        }
    }

    /// Adjusts real-time vocal level (`0.0 ... 1.0`) with a click-free 20 ms linear gain ramp.
    func setSingVocalSlider(_ position: Float) {
        guard singActive else { return }
        singMixer.setSliderPosition(position, rampSeconds: SingGainCurve.smoothingWindowSeconds, curve: .linear)
    }

    /// Smoothly raises vocals back to `1.0` (`~0.75 s`, `.easeInOut`) before restoring the original stereo
    /// stream at the live playhead so exiting Sing has zero sudden jump.
    func exitSingMode(originalURL: URL? = nil, completion: (@MainActor () -> Void)? = nil) {
        singPrebufferTask?.cancel()
        singPrebufferTask = nil
        singStandbyPlayer?.pause()
        singStandbyPlayer?.replaceCurrentItem(with: nil)
        singStandbyPlayer = nil

        guard singActive else {
            completion?()
            return
        }

        let restoreURL = originalURL ?? singOriginalURL
        singExitTask?.cancel()
        singMixer.setVocalGain(1.0, rampSeconds: SingGainCurve.cutOutRampSeconds, curve: .easeInOut)

        singExitTask = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .milliseconds(Int((SingGainCurve.cutOutRampSeconds * 1000).rounded())))
            guard let self, !Task.isCancelled, self.singActive else { return }
            let posSec = self.currentTimeSec
            let wasPlaying = self.player.rate > 0
            let savedNext = self.nextURL
            self.singActive = false
            self.singExitTask = nil
            if let restoreURL {
                self.load(restoreURL, next: savedNext, startSec: posSec)
                if wasPlaying { self.play() }
            }
            completion?()
        }
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
