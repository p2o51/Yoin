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
    /// The current item could not be loaded or stopped with an error.
    func outputDidFail(_ message: String)
    /// Paused or played from outside the app's own controls (the lock
    /// screen of a player that owns its remote commands, e.g. MusicKit).
    func outputPlayingDidChangeExternally(_ playing: Bool)
}
