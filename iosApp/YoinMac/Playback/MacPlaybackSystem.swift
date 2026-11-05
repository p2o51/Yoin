import AppKit
import MediaPlayer

/// The Mac side of the iOS `PlaybackSystem`: media keys, Control Center and the
/// Touch Bar through MPRemoteCommandCenter, and the Now Playing card through
/// MPNowPlayingInfoCenter. macOS has no AVAudioSession, so nothing to activate.
@MainActor
final class MacPlaybackSystem {
    private let player: PlayerEngine
    private var artworkKey: String?
    private var artwork: MPMediaItemArtwork?

    init(player: PlayerEngine) {
        self.player = player
        let center = MPRemoteCommandCenter.shared()
        // MediaPlayer may call these off the main thread: hop to the player's actor.
        center.playCommand.addTarget(handler: Self.handler { [weak self] in self?.player.resume() })
        center.pauseCommand.addTarget(handler: Self.handler { [weak self] in self?.player.pause() })
        center.togglePlayPauseCommand.addTarget(handler: Self.handler { [weak self] in self?.player.togglePlayPause() })
        center.nextTrackCommand.addTarget(handler: Self.handler { [weak self] in self?.player.next() })
        center.previousTrackCommand.addTarget(handler: Self.handler { [weak self] in self?.player.previous() })
        center.changePlaybackPositionCommand.addTarget(handler: Self.seekHandler { [weak self] sec in
            self?.player.seek(to: sec)
        })
    }

    private nonisolated static func handler(
        _ action: @escaping @MainActor @Sendable () -> Void
    ) -> @Sendable (MPRemoteCommandEvent) -> MPRemoteCommandHandlerStatus {
        { _ in
            Task { @MainActor in action() }
            return .success
        }
    }

    private nonisolated static func seekHandler(
        _ action: @escaping @MainActor @Sendable (Double) -> Void
    ) -> @Sendable (MPRemoteCommandEvent) -> MPRemoteCommandHandlerStatus {
        { event in
            guard let e = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            let sec = e.positionTime
            Task { @MainActor in action(sec) }
            return .success
        }
    }

    /// Track, play state, duration or a seek changed.
    func refresh() {
        // MusicKit's player owns the Now Playing card while an Apple Music song plays (as on iOS).
        if player.currentTrack?.provider == AppleMusicMapping.provider { return }
        let info = MPNowPlayingInfoCenter.default()
        guard let t = player.currentTrack else {
            info.nowPlayingInfo = nil
            info.playbackState = .stopped
            return
        }
        var d: [String: Any] = [
            MPMediaItemPropertyTitle: t.title,
            MPMediaItemPropertyArtist: t.artist,
            MPMediaItemPropertyAlbumTitle: t.album,
            MPMediaItemPropertyPlaybackDuration: player.progress.durationSec,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: player.progress.positionSec,
            MPNowPlayingInfoPropertyPlaybackRate: player.isPlaying ? 1.0 : 0.0,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue,
        ]
        if let cover = t.coverURL {
            let ref = CoverRef(url: cover)
            if artworkKey != ref.key {
                artworkKey = ref.key
                artwork = nil
                Task {
                    await CoverArtwork.shared.load(ref)
                    guard self.artworkKey == ref.key, let image = CoverArtwork.shared.image(ref) else { return }
                    self.artwork = Self.artwork(image)
                    self.refresh()
                }
            }
            if let artwork { d[MPMediaItemPropertyArtwork] = artwork }
        }
        info.nowPlayingInfo = d
        info.playbackState = player.isPlaying ? .playing : .paused
    }

    /// MediaPlayer asks for the image on its own queue: the handler must not be main-actor isolated.
    nonisolated private static func artwork(_ image: NSImage) -> MPMediaItemArtwork {
        nonisolated(unsafe) let image = image
        return MPMediaItemArtwork(boundsSize: image.size) { @Sendable _ in image }
    }
}
