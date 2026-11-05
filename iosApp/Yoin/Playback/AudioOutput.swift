import Foundation

/// What the engine needs from an audio renderer. `AVAudioOutput` is the real
/// one (AVQueuePlayer); tests drive a fake so queue logic runs without audio.
///
/// The output holds at most two items: the current one and a preloaded next
/// one. When the current item ends and a next one is loaded the output moves
/// to it by itself (gapless-ish) and reports `didAdvanceToPreloaded`; with no
/// next item it reports `didReachEnd`.
@MainActor
protocol AudioOutput: AnyObject {
    var events: AudioOutputEvents? { get set }
    /// Replaces everything: [current] starts loading at [startSec], [next] is preloaded.
    func load(_ current: URL, next: URL?, startSec: Double)
    /// Swaps only the preloaded next item (nil drops it).
    func preload(_ next: URL?)
    func play()
    func pause()
    func seek(to sec: Double)
    /// Drops every item.
    func stop()
    var currentTimeSec: Double { get }
}

/// Callbacks from an [AudioOutput], all on the main actor.
@MainActor
protocol AudioOutputEvents: AnyObject {
    /// ≤ 4 Hz while playing, plus once after every seek.
    func outputTimeDidChange(_ sec: Double)
    func outputDurationDidChange(_ sec: Double)
    func outputIsBufferingDidChange(_ buffering: Bool)
    /// The current item finished and the preloaded next one took over.
    func outputDidAdvanceToPreloaded()
    /// The current item finished with nothing preloaded.
    func outputDidReachEnd()
    /// The current item could not be loaded or stopped with an error. [network]: the connection or the
    /// server failed (not the song), so the next song would fail the same way.
    func outputDidFail(_ message: String, network: Bool)
    /// Paused or played from outside the app's own controls (the lock
    /// screen of a player that owns its remote commands, e.g. MusicKit).
    func outputPlayingDidChangeExternally(_ playing: Bool)
    /// Shuffle or repeat changed in the player that owns the sound (the Spotify app); nil: unchanged.
    func outputPlayModesDidChangeExternally(shuffle: Bool?, repeatMode: RepeatMode?)
}

extension AudioOutputEvents {
    func outputPlayModesDidChangeExternally(shuffle: Bool?, repeatMode: RepeatMode?) {}

    /// A failure of the song itself.
    func outputDidFail(_ message: String) { outputDidFail(message, network: false) }
}

/// Errors that mean "can't reach the server", not "this song is broken": offline, timeouts, DNS, TLS,
/// lost connections, and the server refusing the sign-in (401 / 403).
enum PlaybackFailureKind {
    static func isNetwork(_ error: Error?) -> Bool {
        guard let error else { return false }
        if let cache = error as? AudioCacheError, case .http(let status) = cache { return status == 401 || status == 403 }
        let ns = error as NSError
        if ns.domain == NSURLErrorDomain {
            return [NSURLErrorNotConnectedToInternet, NSURLErrorTimedOut, NSURLErrorCannotFindHost,
                    NSURLErrorCannotConnectToHost, NSURLErrorNetworkConnectionLost, NSURLErrorDNSLookupFailed,
                    NSURLErrorInternationalRoamingOff, NSURLErrorDataNotAllowed, NSURLErrorSecureConnectionFailed,
                    NSURLErrorUserAuthenticationRequired].contains(ns.code)
        }
        if let underlying = ns.userInfo[NSUnderlyingErrorKey] as? Error { return isNetwork(underlying) }
        return false
    }
}
