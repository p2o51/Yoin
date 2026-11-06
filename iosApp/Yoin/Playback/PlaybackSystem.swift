import AVFoundation
import MediaPlayer
import Network
import Observation
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
    /// The heart behind the lock screen's like button: whether the song is
    /// liked, whether this source can be liked from here, and the flip (the
    /// same `FavoritesStore` write Now Playing's heart makes). Set by `live()`.
    var favorite = FavoriteHooks()

    struct FavoriteHooks {
        var isFavorite: (PlayableTrack) -> Bool = { _ in false }
        var toggle: (PlayableTrack) -> Void = { _ in }
    }

    /// The app's engine: AVQueuePlayer for streams, ApplicationMusicPlayer for
    /// Apple Music, wired to the OS.
    static func live() -> PlaybackSystem {
        let apple = AppleMusicAudioOutput()
        let spotify = SpotifyAudioOutput()
        let router = RoutingAudioOutput(av: AVAudioOutput(), apple: apple, spotify: spotify)
        let system = PlaybackSystem(engine: PlayerEngine(output: router))
        // Spotify playing somewhere is what Yoin shows, unless Yoin itself plays another source.
        spotify.adopt = { [weak router, weak spotify, weak engine = system.engine] in
            guard let router, let spotify, let engine else { return false }
            if engine.isPlaying, engine.currentTrack?.provider != SpotifyMapping.provider { return false }
            router.adopt(spotify)
            return true
        }
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
        system.favorite = FavoriteHooks(
            isFavorite: { NowPlayingData.shared.isFavorite($0) },
            toggle: { t in
                let model = AppModel.shared
                NowPlayingData.shared.toggleFavorite(t, client: model.session.client, apple: model.store.apple)
            })
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
        PlayTrace.log("prepareForSound external=\(isAppleMusic) provider=\(engine.currentTrack?.provider ?? "-") sessionActive=\(sessionActive)")
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
            PlayTrace.log("session setActive(true)")
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
            PlayTrace.log("session released")
        } catch {
            PlayTrace.log("session release failed \(error)")
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
        PlayTrace.log("interruption \(String(describing: type?.rawValue)) external=\(isAppleMusic) playing=\(engine.isPlaying)")
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
        c.changeShuffleModeCommand.isEnabled = true
        c.changeShuffleModeCommand.addTarget { [weak self] event in
            guard let e = event as? MPChangeShuffleModeCommandEvent else { return .commandFailed }
            let on = RemoteCommandState.shuffle(e.shuffleType)
            MainActor.assumeIsolated { self?.engine.setShuffle(on) }
            return .success
        }
        c.changeRepeatModeCommand.isEnabled = true
        c.changeRepeatModeCommand.addTarget { [weak self] event in
            guard let e = event as? MPChangeRepeatModeCommandEvent else { return .commandFailed }
            let mode = RemoteCommandState.repeatMode(e.repeatType)
            MainActor.assumeIsolated { self?.engine.setRepeat(mode) }
            return .success
        }
        // The heart (Android's quick-action favorite): the same write Now
        // Playing's heart makes. Enabled per song in `refreshFavoriteCommand`.
        c.likeCommand.addTarget { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, let t = self.engine.currentTrack,
                      RemoteCommandState.canLike(provider: t.provider) else { return MPRemoteCommandHandlerStatus.commandFailed }
                self.favorite.toggle(t)
                self.refreshRemoteCommandState()
                return .success
            }
        }
        refreshRemoteCommandState()
        for unused in [c.dislikeCommand, c.bookmarkCommand, c.skipForwardCommand,
                       c.skipBackwardCommand, c.seekForwardCommand, c.seekBackwardCommand] {
            unused.isEnabled = false
        }
    }

    private func removeRemoteCommands() {
        guard commandsInstalled else { return }
        commandsInstalled = false
        let c = MPRemoteCommandCenter.shared()
        for command in [c.playCommand, c.pauseCommand, c.togglePlayPauseCommand, c.nextTrackCommand,
                        c.previousTrackCommand, c.changePlaybackPositionCommand, c.changeShuffleModeCommand,
                        c.changeRepeatModeCommand, c.likeCommand] {
            command.removeTarget(nil)
        }
        c.changeShuffleModeCommand.isEnabled = false
        c.changeRepeatModeCommand.isEnabled = false
        c.likeCommand.isEnabled = false
        favoriteObservation = false
    }

    // MARK: Lock screen

    /// Whether the heart is being watched for changes (one pending observation).
    private var favoriteObservation = false

    /// Shuffle, repeat and the heart as the lock screen shows them. The heart
    /// re-reads itself when the favorites store changes (a tap here, in Now
    /// Playing, or a server answer rolling it back).
    private func refreshRemoteCommandState() {
        guard commandsInstalled else { return }
        let c = MPRemoteCommandCenter.shared()
        let track = engine.currentTrack
        // One pending observation at a time: re-armed by its own firing.
        let watch = !favoriteObservation
        let liked: Bool = track.map { t in
            guard watch else { return favorite.isFavorite(t) }
            favoriteObservation = true
            return withObservationTracking({ favorite.isFavorite(t) }, onChange: { [weak self] in
                Task { @MainActor in
                    self?.favoriteObservation = false
                    self?.refreshRemoteCommandState()
                }
            })
        } ?? false
        let state = RemoteCommandState(shuffle: engine.shuffle, repeatMode: engine.repeatMode,
                                       provider: track?.provider, liked: liked)
        c.changeShuffleModeCommand.currentShuffleType = state.shuffleType
        c.changeRepeatModeCommand.currentRepeatType = state.repeatType
        c.likeCommand.isEnabled = state.likeEnabled
        c.likeCommand.isActive = state.liked
        c.likeCommand.localizedTitle = state.liked ? String(localized: "Unfavorite") : String(localized: "Favorite")
    }

    private func refreshNowPlaying() {
        refreshRemoteCommandState()
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


/// What the lock screen and Control Center show of the play modes and the
/// heart, and how their commands map onto the engine (Android
/// `SessionQuickActions`: shuffle and favorite).
struct RemoteCommandState: Equatable {
    var shuffle: Bool
    var repeatMode: RepeatMode
    var provider: String?
    var liked: Bool

    var shuffleType: MPShuffleType { shuffle ? .items : .off }
    var repeatType: MPRepeatType {
        switch repeatMode {
        case .all: .all
        case .one: .one
        case .off: .off
        }
    }
    /// The heart shows only where this app writes it from here.
    var likeEnabled: Bool { provider.map(Self.canLike) ?? false }

    /// Subsonic only: Apple Music and Spotify songs play outside this
    /// session (their own card and commands), so the commands aren't ours.
    static func canLike(provider: String) -> Bool { provider == MediaRef.companion.PROVIDER_SUBSONIC }

    static func shuffle(_ type: MPShuffleType) -> Bool { type != .off }

    static func repeatMode(_ type: MPRepeatType) -> RepeatMode {
        switch type {
        case .one: .one
        case .all: .all
        case .off: .off
        @unknown default: .all
        }
    }
}
