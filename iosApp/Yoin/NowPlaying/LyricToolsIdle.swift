import SwiftUI

/// When the lyric tools (search / translate / select / recenter) step away, and who takes a tap on their spot
/// (Android `ui/nowplaying/LyricToolsIdle.kt`, #334; docs/design.md 自动沉浸). The tools fade after 3 s without a
/// touch; until the fade has finished they are still on screen, so they still take their taps.
///
/// - **Visible (also while fading out):** a tap on a key is that key's tap. It runs the action, and the touch
///   brings the tools back.
/// - **Hidden (faded all the way):** the keys take no taps and are not announced. The first tap on the strip
///   they stood in only brings them back; it never seeks the lyrics or opens search.
/// - A tap on a lyric line outside that strip still seeks, hidden or not.
/// - **VoiceOver / Switch Control:** the tools never step away. VoiceOver's explore and double-tap send the app
///   no touches, so nothing could bring them back.
enum LyricToolsIdleRules {
    /// Android LyricToolsIdleMs.
    static let idleSeconds: Double = 3

    /// The count runs at all: the page allows it, and no assistive technology that can't touch is on.
    static func autoHides(eligible: Bool, voiceOver: Bool, switchControl: Bool) -> Bool {
        eligible && !voiceOver && !switchControl
    }

    /// The keys take taps and are in the accessibility tree: everything short of fully hidden.
    static func keysInteractive(hidden: Bool) -> Bool { !hidden }

    /// The wake guard over the strip is up only once the keys are fully gone (Android routes on `hidden`, not on
    /// `idle`, which turns true when the fade *starts*).
    static func wakeGuardUp(hidden: Bool) -> Bool { hidden }

    /// Who a tap goes to.
    enum Tap: Equatable {
        /// The key under the finger runs.
        case key
        /// Nothing runs; the tools come back.
        case wakeOnly
        /// The lyrics under the finger take it (a seek), and the tools come back.
        case lyrics
    }

    /// Android `lyricToolsWakeTapSwallowed` as a route: [inStrip] is a tap in the strip (or footprint) the keys
    /// stand in.
    static func route(hidden: Bool, inStrip: Bool) -> Tap {
        guard inStrip else { return .lyrics }
        return hidden ? .wakeOnly : .key
    }
}

extension View {
    /// The lyric keys as [LyricToolsIdleRules.keysInteractive] says: hidden keys take no taps, are disabled
    /// (keyboard focus skips them) and are not announced.
    func lyricKeysInteractive(_ interactive: Bool) -> some View {
        allowsHitTesting(interactive)
            .disabled(!interactive)
            .accessibilityHidden(!interactive)
    }
}
