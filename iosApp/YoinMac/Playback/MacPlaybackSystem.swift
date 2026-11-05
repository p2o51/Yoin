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
        center.playCommand.addTarget { [weak self] _ in self?.player.resume(); return .success }
        center.pauseCommand.addTarget { [weak self] _ in self?.player.pause(); return .success }
        center.togglePlayPauseCommand.addTarget { [weak self] _ in self?.player.togglePlayPause(); return .success }
        center.nextTrackCommand.addTarget { [weak self] _ in self?.player.next(); return .success }
        center.previousTrackCommand.addTarget { [weak self] _ in self?.player.previous(); return .success }
        center.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let e = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            self?.player.seek(to: e.positionTime)
            return .success
        }
    }

    /// Track, play state, duration or a seek changed.
    func refresh() {
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
                    self.artwork = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
                    self.refresh()
                }
            }
            if let artwork { d[MPMediaItemPropertyArtwork] = artwork }
        }
        info.nowPlayingInfo = d
        info.playbackState = player.isPlaying ? .playing : .paused
    }
}
