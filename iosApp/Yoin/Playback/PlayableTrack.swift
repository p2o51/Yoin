import Foundation

/// One streamable song, already resolved by its provider (a Subsonic
/// `stream` URL, a cover URL). The engine never talks to a provider itself.
struct PlayableTrack: Identifiable, Hashable, Sendable {
    var id: String
    var provider: String
    var title: String
    var artist: String
    var album: String
    var albumId: String?
    var durationSec: Double
    var streamURL: URL
    var coverURL: URL?
    /// The provider's favorite flag when the track was resolved (nil = unknown).
    var starred: Bool? = nil
    /// The provider's own 1–5 rating (nil = unrated or unknown).
    var userRating: Int? = nil
}

/// A queue slot. The same track can sit in the queue twice, so lists and
/// shuffle restore key on the slot, never on the track.
struct QueueItem: Identifiable, Hashable, Sendable {
    let id: UUID
    let track: PlayableTrack
    /// Added by Play next / Add to queue (Android `EXTRA_USER_QUEUED`).
    var userQueued: Bool

    init(_ track: PlayableTrack, userQueued: Bool = false, id: UUID = UUID()) {
        self.id = id
        self.track = track
        self.userQueued = userQueued
    }
}

/// Repeat axis (Android `PlayMode`): all is the default, one repeats the
/// current track on auto-advance only, off plays to the queue's end and stops.
enum RepeatMode: String, Sendable, CaseIterable {
    case all, one, off

    /// The split pill's repeat segment on Android: all → one → off → all.
    var next: RepeatMode {
        switch self {
        case .all: .one
        case .one: .off
        case .off: .all
        }
    }
}

/// Things the engine did on its own that the UI may want to surface.
enum PlayerEvent: Sendable, Equatable {
    /// [track] failed to load or play and was skipped.
    case skippedAfterError(track: PlayableTrack, message: String)
    /// Every track in the queue failed in a row; playback stopped.
    case queueFailed
    /// Repeat off reached the end of the queue.
    case queueEnded
}
