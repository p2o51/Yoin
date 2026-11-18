import SwiftUI

/// Reads the playing position in its own body, so the engine's 4 Hz ticks
/// re-render only the progress UI inside it, never the page around it
/// (Android: per-tick position stays out of the Now Playing state).
/// Falls back to the mock snapshot while the engine has no song.
struct PlaybackPosition<Content: View>: View {
    @Environment(AppModel.self) private var model
    /// The tip page's demo phone: its own playhead, never the engine's.
    @Environment(\.demoPlayhead) private var demo
    /// False while a resident host (PhoneShell's NowPlayingMorph) is closed and hidden,
    /// so 4 Hz playhead ticks never re-evaluate hidden lyrics or progress subtrees.
    var live = true
    @ViewBuilder var content: (_ positionSec: Double, _ durationSec: Double) -> Content

    var body: some View {
        if let demo {
            content(demo.positionSec, max(demo.durationSec, 1))
        } else if !live {
            let d = model.player.currentTrack?.durationSec ?? Double(model.mockPlayback.track.durationSec)
            content(0, max(d, 1))
        } else if model.player.currentTrack != nil {
            let p = model.player.progress
            content(p.positionSec, max(p.durationSec, 1))
        } else {
            let m = model.mockPlayback
            content(Double(m.positionSec), Double(max(m.track.durationSec, 1)))
        }
    }
}
