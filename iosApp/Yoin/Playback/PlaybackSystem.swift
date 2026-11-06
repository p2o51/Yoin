import AVFoundation
import MediaPlayer
import Network
import UIKit
import os
import YoinShared

/// The engine's ties to the OS: the audio session (category, interruptions,
/// headphones unplugged), the lock screen / Control Center card and the
/// remote commands. Tests build a bare [PlayerEngine] without this.
@MainActor
final class PlaybackSystem {
    let engine: PlayerEngine
    private let nowPlaying = NowPlayingInfoController()
    private var observers: [NSObjectProtocol] = []
    private var commandsInstalled = false
    private var sessionActive = false
    /// Paused by an interruption (a call), so its end may resume.
    private var pausedByInterruption = false
    private static let log = Logger(subsystem: "com.gpo.yoin", category: "player")

    /// The network and route behind the stream quality (set when built by `live()`).
    private(set) var streamConditions: StreamQualityConditions?

    /// Apple Music songs play through MusicKit (set when built by `live()`).
    private(set) var appleOutput: AppleMusicAudioOutput?
    /// Spotify songs play in the Spotify app (set when built by `live()`).
    private(set) var spotifyOutput: SpotifyAudioOutput?
    /// MusicKit owns the lock screen card and remote commands while it plays.
    private var nowPlayingWritten = false

    /// The app's engine: AVQueuePlayer for streams, ApplicationMusicPlayer for
    /// Apple Music, wired to the OS.
    static func live() -> PlaybackSystem {
        let apple = AppleMusicAudioOutput()
        let spotify = SpotifyAudioOutput()
        let system = PlaybackSystem(engine: PlayerEngine(output: RoutingAudioOutput(av: AVAudioOutput(), apple: apple,
                                                                                    spotify: spotify)))
        let conditions = StreamQualityConditions()
        system.streamConditions = conditions
        system.engine.streamQuality = { conditions.resolvedNow() }
        // The network decides Wi‑Fi or mobile from the next song on (Android
        // SubsonicStreamResolver: chosen when a song opens, never mid-song).
        conditions.onNetworkChange = { [weak engine = system.engine] in engine?.streamQualityDidChange() }
        conditions.start()
        system.engine.isStoredWhole = { AudioCache.shared.isComplete(url: $0) }
        system.engine.downloadedFile = { OfflineDownloads.shared.localFile(for: $0) }
        AudioCache.shared.warmUp()
        system.appleOutput = apple
        system.spotifyOutput = spotify
        return system
    }

    /// The current song plays through MusicKit.
    private var isAppleMusic: Bool {
        // The Spotify app, like MusicKit, owns its own sound, lock screen card and commands.
        engine.currentTrack?.provider == AppleMusicMapping.provider || engine.currentTrack?.provider == "spotify"
    }

    init(engine: PlayerEngine) {
        self.engine = engine
        engine.onWillPlay = { [weak self] in self?.prepareForSound() }
        engine.onStateChange = { [weak self] in self?.refreshNowPlaying() }
        observeSession()
    }

    // MARK: Audio session

    private func prepareForSound() {
        if isAppleMusic {
            // MusicKit plays outside our audio session and drives the lock
            // screen itself: an active non-mixable session of ours would
            // interrupt it, and our commands would double its own.
            removeRemoteCommands()
            if sessionActive { deactivateSession() }
            return
        }
        installRemoteCommands()
        guard !sessionActive else { return }
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playback, mode: .default, policy: .longFormAudio)
            try session.setActive(true)
            sessionActive = true
        } catch {
            Self.log.error("audio session: \(error.localizedDescription, privacy: .public)")
        }
    }

    /// Lets the session go for a source that plays outside it. The local player stopped a moment ago
    /// and may still be running its I/O, which makes the first try fail: it is tried again shortly.
    private func deactivateSession(retry: Bool = true) {
        do {
            try AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
            sessionActive = false
        } catch {
            Self.log.info("audio session release: \(error.localizedDescription, privacy: .public)")
            guard retry else { return }
            Task { @MainActor [weak self] in
                try? await Task.sleep(for: .milliseconds(300))
                guard let self, self.isAppleMusic, self.sessionActive else { return }
                self.deactivateSession(retry: false)
            }
        }
    }

    private func observeSession() {
        let center = NotificationCenter.default
        observers.append(center.addObserver(
            forName: AVAudioSession.interruptionNotification, object: nil, queue: .main
        ) { [weak self] note in
            let type = (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt)
                .flatMap(AVAudioSession.InterruptionType.init)
            let options = (note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt)
                .map(AVAudioSession.InterruptionOptions.init) ?? []
            MainActor.assumeIsolated { self?.interruption(type, options: options) }
        })
        observers.append(center.addObserver(
            forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main
        ) { [weak self] note in
            let reason = (note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt)
                .flatMap(AVAudioSession.RouteChangeReason.init)
            MainActor.assumeIsolated {
                // Headphones out: pause, never blast the speaker (Android AUDIO_BECOMING_NOISY).
                if reason == .oldDeviceUnavailable { self?.engine.pause() }
                // Auto quality follows the route from the next song on.
                self?.engine.streamQualityDidChange()
            }
        })
        observers.append(center.addObserver(
            forName: AVAudioSession.mediaServicesWereResetNotification, object: nil, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated {
                self?.sessionActive = false
                self?.commandsInstalled = false
                self?.engine.pause()
            }
        })
    }

    private func interruption(_ type: AVAudioSession.InterruptionType?, options: AVAudioSession.InterruptionOptions) {
        switch type {
        case .began:
            sessionActive = false
            // Apple Music and the Spotify app play outside our session: its interruption is their
            // own sound starting (Spotify woken on a song), not something to pause for. Pausing here
            // stopped a Spotify start a moment after it began.
            if isAppleMusic { return }
            if engine.isPlaying {
                pausedByInterruption = true
                engine.pause()
            }
        case .ended:
            if pausedByInterruption, options.contains(.shouldResume) { engine.resume() }
            pausedByInterruption = false
        default:
            break
        }
    }

    // MARK: Remote commands

    private func installRemoteCommands() {
        guard !commandsInstalled else { return }
        commandsInstalled = true
        let c = MPRemoteCommandCenter.shared()
        c.playCommand.addTarget { [weak self] _ in
            MainActor.assumeIsolated { self?.engine.resume() }
            return .success
        }
        c.pauseCommand.addTarget { [weak self] _ in
            MainActor.assumeIsolated { self?.engine.pause() }
            return .success
        }
        c.togglePlayPauseCommand.addTarget { [weak self] _ in
            MainActor.assumeIsolated { self?.engine.togglePlayPause() }
            return .success
        }
        c.nextTrackCommand.addTarget { [weak self] _ in
            MainActor.assumeIsolated { self?.engine.next() }
            return .success
        }
        c.previousTrackCommand.addTarget { [weak self] _ in
            MainActor.assumeIsolated { self?.engine.previous() }
            return .success
        }
        c.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let e = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            let sec = e.positionTime
            MainActor.assumeIsolated { self?.engine.seek(to: sec) }
            return .success
        }
        for unused in [c.likeCommand, c.dislikeCommand, c.bookmarkCommand, c.skipForwardCommand,
                       c.skipBackwardCommand, c.seekForwardCommand, c.seekBackwardCommand] {
            unused.isEnabled = false
        }
    }

    private func removeRemoteCommands() {
        guard commandsInstalled else { return }
        commandsInstalled = false
        let c = MPRemoteCommandCenter.shared()
        for command in [c.playCommand, c.pauseCommand, c.togglePlayPauseCommand, c.nextTrackCommand,
                        c.previousTrackCommand, c.changePlaybackPositionCommand] {
            command.removeTarget(nil)
        }
    }

    // MARK: Lock screen

    private func refreshNowPlaying() {
        if isAppleMusic {
            // Clear our card once; after that the card is MusicKit's.
            if nowPlayingWritten {
                nowPlaying.update(track: nil, elapsed: 0, duration: 0, playing: false, index: nil, count: 0)
                nowPlayingWritten = false
            }
            return
        }
        nowPlayingWritten = engine.currentTrack != nil
        nowPlaying.update(
            track: engine.currentTrack,
            elapsed: engine.progress.positionSec,
            duration: engine.progress.durationSec,
            playing: engine.isPlaying,
            index: engine.currentIndex,
            count: engine.queue.count
        )
    }
}

/// MPNowPlayingInfoCenter writer. Elapsed time is written on state changes
/// only; the system runs the clock from the rate between them.
@MainActor
final class NowPlayingInfoController {
    private var artworkFor: URL?
    private var artwork: MPMediaItemArtwork?
    private var artworkTask: Task<Void, Never>?

    func update(track: PlayableTrack?, elapsed: Double, duration: Double, playing: Bool, index: Int?, count: Int) {
        let center = MPNowPlayingInfoCenter.default()
        guard let track else {
            center.nowPlayingInfo = nil
            return
        }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: track.title,
            MPMediaItemPropertyArtist: track.artist,
            MPMediaItemPropertyAlbumTitle: track.album,
            MPMediaItemPropertyPlaybackDuration: duration > 0 ? duration : track.durationSec,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: elapsed,
            MPNowPlayingInfoPropertyPlaybackRate: playing ? 1.0 : 0.0,
            MPNowPlayingInfoPropertyDefaultPlaybackRate: 1.0,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue,
        ]
        if let index {
            info[MPNowPlayingInfoPropertyPlaybackQueueIndex] = index
            info[MPNowPlayingInfoPropertyPlaybackQueueCount] = count
        }
        if track.coverURL == artworkFor, let artwork {
            info[MPMediaItemPropertyArtwork] = artwork
        }
        center.nowPlayingInfo = info
        loadArtworkIfNeeded(track.coverURL)
    }

    private func loadArtworkIfNeeded(_ url: URL?) {
        guard url != artworkFor else { return }
        artworkFor = url
        artwork = nil
        artworkTask?.cancel()
        guard let url else { return }
        artworkTask = Task { [weak self] in
            guard let (data, _) = try? await URLSession.shared.data(from: url),
                  let image = UIImage(data: data), !Task.isCancelled else { return }
            let art = Self.makeArtwork(image)
            guard let self, self.artworkFor == url else { return }
            self.artwork = art
            var info = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
            info[MPMediaItemPropertyArtwork] = art
            MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        }
    }

    /// Nonisolated on purpose: MediaPlayer calls the request handler off the
    /// main thread, and a main-actor closure would trap there.
    nonisolated private static func makeArtwork(_ image: UIImage) -> MPMediaItemArtwork {
        MPMediaItemArtwork(boundsSize: image.size) { @Sendable _ in image }
    }
}

// MARK: - Stream quality conditions

/// What the shared stream-quality rule reads on iOS: whether the network is
/// unmetered (NWPathMonitor) and what the sound goes out through (the audio
/// session's current route), plus the two stored choices.
@MainActor
final class StreamQualityConditions {
    private let monitor = NWPathMonitor()
    /// No path yet counts as metered, as no network does on Android.
    private(set) var unmetered = false
    var onNetworkChange: (() -> Void)?
    private static let log = Logger(subsystem: "com.gpo.yoin", category: "player")

    func start() {
        monitor.pathUpdateHandler = { [weak self] path in
            let unmetered = StreamConditions.isUnmetered(satisfied: path.status == .satisfied,
                                                         expensive: path.isExpensive,
                                                         constrained: path.isConstrained)
            Task { @MainActor in self?.networkChanged(unmetered: unmetered) }
        }
        monitor.start(queue: DispatchQueue(label: "com.gpo.yoin.stream-path"))
    }

    private func networkChanged(unmetered: Bool) {
        guard unmetered != self.unmetered else { return }
        self.unmetered = unmetered
        Self.log.info("stream network unmetered=\(unmetered, privacy: .public)")
        onNetworkChange?()
    }

    /// The fixed quality a song opened now plays at.
    func resolvedNow() -> StreamQuality {
        let ports = AVAudioSession.sharedInstance().currentRoute.outputs.map(\.portType)
        let output = StreamConditions.outputKind(ports)
        let settings = StreamQualityStore.load()
        let quality = StreamQualityPolicy.shared.resolve(settings: settings, unmetered: unmetered, output: output)
        Self.log.info("""
            stream quality wifi=\(settings.wifi.key, privacy: .public) mobile=\(settings.mobile.key, privacy: .public) \
            unmetered=\(self.unmetered, privacy: .public) route=\(ports.map(\.rawValue).joined(separator: ","), privacy: .public) \
            output=\(output.name, privacy: .public) -> \(quality.key, privacy: .public)
            """)
        return quality
    }
}
