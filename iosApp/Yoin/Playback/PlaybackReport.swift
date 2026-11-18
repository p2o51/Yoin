import Foundation
import YoinShared

/// OpenSubsonic `reportPlayback` states (Android PlaybackReportState, #130).
enum PlaybackReportState: String, Sendable { case starting, playing, paused, stopped }

/// Follows the player and says what to tell the server's now-playing view (Android ScrobbleTracker's
/// StateChanged side): a song that starts is `starting`, then `playing` / `paused` as the listener toggles,
/// and `stopped` once another song (or nothing) takes its place. Never counts a play; plays are scrobbles.
struct PlaybackReportTracker: Sendable {
    struct Event: Equatable, Sendable {
        var songId: String
        var state: PlaybackReportState
        var positionMs: Int64
    }

    private var songId: String?
    private var playing = false
    private var lastPositionMs: Int64 = 0

    /// [songId] nil = nothing of this server's plays now.
    mutating func update(songId new: String?, isPlaying: Bool, positionMs: Int64) -> [Event] {
        var out: [Event] = []
        if let old = songId, old != new {
            out.append(Event(songId: old, state: .stopped, positionMs: lastPositionMs))
            songId = nil
        }
        guard let new else { return out }
        if songId == nil {
            songId = new
            playing = isPlaying
            out.append(Event(songId: new, state: isPlaying ? .starting : .paused, positionMs: positionMs))
        } else if isPlaying != playing {
            playing = isPlaying
            out.append(Event(songId: new, state: isPlaying ? .playing : .paused, positionMs: positionMs))
        }
        lastPositionMs = positionMs
        return out
    }
}

extension LibraryStore {
    /// The player changed: the server's now-playing view hears it, when it takes `playbackReport`. A failure
    /// (offline) is dropped: it is a live view, nothing to catch up on later.
    func reportPlayback(track: PlayableTrack?, isPlaying: Bool, positionSec: Double) {
        guard let client, session.supportsPlaybackReport else { return }
        let id = track.flatMap { $0.provider == MediaRef.companion.PROVIDER_SUBSONIC ? $0.id : nil }
        for e in playbackReports.update(songId: id, isPlaying: isPlaying, positionMs: Int64(positionSec * 1000)) {
            Task { try? await client.reportPlayback(mediaId: e.songId, state: e.state.rawValue, positionMs: e.positionMs) }
        }
    }
}
