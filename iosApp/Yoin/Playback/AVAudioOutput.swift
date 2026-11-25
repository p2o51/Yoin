import AVFoundation
import os

/// [AudioOutput] on an AVQueuePlayer: the current item plus one preloaded
/// next item, so the hand-over at a song's end needs no new request.
@MainActor
final class AVAudioOutput: AudioOutput {
    weak var events: AudioOutputEvents?

    private var player = AVQueuePlayer()
    private var current: AVPlayerItem?
    private var next: AVPlayerItem?
    private var nextURL: URL?
    private let singMixer = SingQuadDownmixer(initialGain: 1.0)
    private var singActive = false
    /// Non-nil while `current` is playing a 4-channel Quad AAC Sing stream (`I + g*V`).
    /// Kept loaded at `g = 1.0` after exiting Sing so exit has zero audio gap and re-entry is instant.
    private var singLoadedSource: SingQuadSource?
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
    private var replayGainObserver: NSObjectProtocol?
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
        configurePlayer(player)
        attachPlayerObservers(to: player)
        // Volume leveling changed: the song playing and the one preloaded follow at once (Android #139).
        replayGainObserver = NotificationCenter.default.addObserver(forName: ReplayGainSettings.changed, object: nil,
                                                                    queue: .main) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self else { return }
                let activeItems = [self.singLoadedSource != nil ? nil : self.current, self.next].compactMap { $0 }
                for item in activeItems { self.applyGain(item) }
            }
        }
    }

    private func configurePlayer(_ p: AVQueuePlayer) {
        p.actionAtItemEnd = .advance
        p.automaticallyWaitsToMinimizeStalling = true
        // `-silentAudio YES`: play muted (silent QA runs; the clock still advances).
        p.isMuted = LaunchConfig.silentAudio
    }

    private func detachPlayerObservers(from p: AVQueuePlayer) {
        if let obs = timeObserver {
            p.removeTimeObserver(obs)
            timeObserver = nil
        }
        observations.removeAll()
    }

    private func attachPlayerObservers(to p: AVQueuePlayer) {
        // ≤ 4 Hz: the progress UI and the scrobble clock are all that read it.
        timeObserver = p.addPeriodicTimeObserver(
            forInterval: CMTime(value: 1, timescale: 4), queue: .main
        ) { [weak self] time in
            MainActor.assumeIsolated {
                guard let self, self.player === p, self.player.currentItem != nil else { return }
                self.events?.outputTimeDidChange(time.seconds.isFinite ? time.seconds : 0)
            }
        }
        observations = [
            p.observe(\.timeControlStatus, options: [.new]) { [weak self] player, _ in
                let waiting = player.timeControlStatus == .waitingToPlayAtSpecifiedRate
                Task { @MainActor in
                    guard let self, self.player === player else { return }
                    self.events?.outputIsBufferingDidChange(waiting)
                }
            },
            p.observe(\.currentItem, options: [.new]) { [weak self] player, _ in
                Task { @MainActor in
                    guard let self, self.player === player else { return }
                    self.currentItemDidChange()
                }
            },
        ]
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
        singLoadedSource = nil
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
        singLoadedSource = nil
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

    /// Pre-buffers a 4-channel Quad AAC Sing stream (`[I_L, I_R, V_L, V_R]`) on a muted incoming player
    /// while the original stereo track keeps playing uninterrupted, crossfades seamlessly at `g = 1.0`
    /// (zero silence gap, zero lyric time jump), invokes `onHandoverReady`, and smoothly glides vocals
    /// down along `.easeOutCubic` over `~0.95 s`.
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

        // Fast path: this track's quad.m4a is already the live item (e.g. re-entering Sing on the same song).
        // Zero player swap, zero gap — just glide vocals down smoothly!
        if singLoadedSource == source, current != nil {
            singActive = true
            onHandoverReady?()
            singMixer.setSliderPosition(
                sliderPosition,
                rampSeconds: SingGainCurve.cutInRampSeconds,
                curve: .easeOutCubic
            )
            PlayTrace.log("AV Sing re-engaged seamlessly (slider=\(Int((sliderPosition * 100).rounded()))%)")
            return
        }

        let asset = AVURLAsset(url: source.url)
        let isLocal = source.url.isFileURL

        singPrebufferTask = Task { @MainActor [weak self] in
            guard let self else { return }
            var adoptedIncoming = false
            defer {
                if !adoptedIncoming {
                    self.singStandbyPlayer?.pause()
                    self.singStandbyPlayer?.replaceCurrentItem(with: nil)
                }
                self.singStandbyPlayer = nil
            }

            guard let tracks = try? await asset.loadTracks(withMediaType: .audio),
                  let track = tracks.first else {
                PlayTrace.log("AV Sing track load failed")
                onFailure?()
                return
            }
            guard !Task.isCancelled else { return }

            // Lock mixer at unity (1.0 = exact original stereo mix) BEFORE creating the tap/item so cut-in is inaudible.
            self.singMixer.setVocalGain(1.0, rampSeconds: 0)
            guard let mix = SingQuadAudioTap.makeAudioMix(for: track, mixer: self.singMixer) else {
                PlayTrace.log("AV Sing tap attach failed")
                onFailure?()
                return
            }

            let liveItem = AVPlayerItem(asset: asset)
            liveItem.preferredForwardBufferDuration = 30
            // Quad Sing channels are [I_L, I_R, V_L, V_R], never spatialized surround.
            liveItem.allowedAudioSpatializationFormats = []
            liveItem.audioMix = mix

            let incoming = AVQueuePlayer(playerItem: liveItem)
            self.configurePlayer(incoming)
            incoming.automaticallyWaitsToMinimizeStalling = false
            incoming.volume = 0
            self.singStandbyPlayer = incoming

            // Wait for the incoming item to become ready (and buffered ahead for remote HTTP streams)
            // while the original stereo track keeps playing uninterrupted.
            let deadline = ProcessInfo.processInfo.systemUptime + 8.0
            while !Task.isCancelled {
                if liveItem.status == .failed {
                    PlayTrace.log("AV Sing prebuffer failed: \(liveItem.error?.localizedDescription ?? "unknown")")
                    onFailure?()
                    return
                }
                if liveItem.status == .readyToPlay {
                    if isLocal {
                        break
                    }
                    let ahead = Self.bufferedAheadSeconds(for: liveItem, at: self.currentTimeSec)
                    if liveItem.isPlaybackLikelyToKeepUp || ahead >= 2.0 {
                        break
                    }
                }
                if ProcessInfo.processInfo.systemUptime >= deadline {
                    if liveItem.status == .readyToPlay { break }
                    PlayTrace.log("AV Sing prebuffer timed out")
                    onFailure?()
                    return
                }
                try? await Task.sleep(for: .milliseconds(25))
            }
            guard !Task.isCancelled else { return }

            let wasPlaying = self.player.rate > 0
            let savedNext = self.nextURL

            // Prime incoming decoder at the current playhead BEFORE starting crossfade.
            let initialPos = self.currentTimeSec
            if initialPos > 0 {
                await incoming.seek(
                    to: CMTime(seconds: initialPos, preferredTimescale: 600),
                    toleranceBefore: .zero,
                    toleranceAfter: .zero
                )
            }
            guard !Task.isCancelled else { return }

            if wasPlaying {
                // Re-align tightly right before play so audio continuity stays within ~20 ms.
                let freshPos = self.currentTimeSec
                if abs(incoming.currentTime().seconds - freshPos) > 0.04 {
                    await incoming.seek(
                        to: CMTime(seconds: freshPos + 0.03, preferredTimescale: 600),
                        toleranceBefore: .zero,
                        toleranceAfter: .zero
                    )
                }
                guard !Task.isCancelled else { return }

                incoming.play()
                // Wait briefly for CoreAudio to begin rendering incoming frames before crossfading.
                let playDeadline = ProcessInfo.processInfo.systemUptime + 0.4
                while !Task.isCancelled && incoming.timeControlStatus != .playing && ProcessInfo.processInfo.systemUptime < playDeadline {
                    try? await Task.sleep(for: .milliseconds(15))
                }
                guard !Task.isCancelled else { return }

                // 120 ms equal-power-feel crossfade at unity vocal gain (1.0): zero dropout, zero click.
                let oldPlayer = self.player
                let steps = 12
                for step in 1...steps {
                    guard !Task.isCancelled else {
                        oldPlayer.volume = 1
                        return
                    }
                    let t = Float(step) / Float(steps)
                    incoming.volume = t
                    oldPlayer.volume = 1 - t
                    try? await Task.sleep(for: .milliseconds(10))
                }
                incoming.volume = 1
                oldPlayer.volume = 0
            } else {
                incoming.volume = 1
            }
            guard !Task.isCancelled else { return }

            // Atomically adopt `incoming` as `self.player` now that its clock matches the live playhead
            // (so `timeObserver` never emits 0.0 and lyrics never jump).
            let oldPlayer = self.player
            adoptedIncoming = true
            self.singStandbyPlayer = nil
            self.detachPlayerObservers(from: oldPlayer)
            oldPlayer.pause()
            oldPlayer.removeAllItems()

            incoming.automaticallyWaitsToMinimizeStalling = true
            self.player = incoming
            self.current = liveItem
            self.singLoadedSource = source
            self.singActive = true
            self.attachPlayerObservers(to: incoming)
            self.observeCurrent(liveItem)
            self.next = nil
            self.nextURL = nil
            self.preload(savedNext)

            // Expand UI capsule and glide vocals smoothly from 1.0 → target over ~0.95 s.
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

    /// Smoothly raises vocals back to `1.0` (`~0.75 s`, `.easeInOut`) without tearing down the live
    /// quad stream (`I + 1.0 * V == original stereo`), eliminating exit silence gaps, premature ramp
    /// cutoff, and lyric jumps. Normal stereo resumes automatically on the next track.
    func exitSingMode(originalURL: URL? = nil, completion: (@MainActor () -> Void)? = nil) {
        _ = originalURL
        singPrebufferTask?.cancel()
        singPrebufferTask = nil
        singExitTask?.cancel()
        singExitTask = nil
        singStandbyPlayer?.pause()
        singStandbyPlayer?.replaceCurrentItem(with: nil)
        singStandbyPlayer = nil

        guard singActive else {
            completion?()
            return
        }

        singActive = false
        singMixer.setVocalGain(1.0, rampSeconds: SingGainCurve.cutOutRampSeconds, curve: .easeInOut)
        PlayTrace.log("AV Sing exiting smoothly (gliding vocals back to 1.0)")
        completion?()
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
            cancelSingTransitions()
            singActive = false
            singLoadedSource = nil
            singOriginalURL = itemURLs[ObjectIdentifier(now)] ?? (now.asset as? AVURLAsset)?.url
            singMixer.setVocalGain(1.0, rampSeconds: 0)
            current = now
            next = nil
            nextURL = nil
            observeCurrent(now)
            Self.log.info("advanced to preloaded item")
            events?.outputDidAdvanceToPreloaded()
        } else if now == nil, current != nil {
            cancelSingTransitions()
            singActive = false
            singLoadedSource = nil
            singOriginalURL = nil
            singMixer.setVocalGain(1.0, rampSeconds: 0)
            current = nil
            itemObservations = []
            Self.log.info("reached end of queue")
            events?.outputDidReachEnd()
        }
    }
}
