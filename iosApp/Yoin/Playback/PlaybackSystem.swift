import AVFoundation
import MediaPlayer
import UIKit
import os

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

    /// The app's engine: AVQueuePlayer output, wired to the OS.
    static func live() -> PlaybackSystem {
        PlaybackSystem(engine: PlayerEngine(output: AVAudioOutput()))
    }

    init(engine: PlayerEngine) {
        self.engine = engine
        engine.onWillPlay = { [weak self] in self?.prepareForSound() }
        engine.onStateChange = { [weak self] in self?.refreshNowPlaying() }
        observeSession()
    }

    // MARK: Audio session

    private func prepareForSound() {
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

    // MARK: Lock screen

    private func refreshNowPlaying() {
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
