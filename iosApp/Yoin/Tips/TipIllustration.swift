import SwiftUI

/// How a tip is done, as a small live phone built from the app's own views (Android
/// ui/tips/TipIllustration.kt; owner 2026-10-19: native UI the user can press too, with nothing behind it).
/// A touch dot acts the gesture out on a loop and the phone answers the way the app does (DemoPhone). It
/// runs only while [playing] (its page is the settled one). Decorative for accessibility: the text says it.
struct TipIllustration: View {
    var tip: Tip
    var playing: Bool
    var scheme: CoverScheme

    /// The tips that have a demo so far; the others' pages open on their title.
    static let scenes: Set<String> = ["SpotifyElsewhere", "MemoryLedger", "LyricHighlight"]

    static func has(_ id: String) -> Bool { scenes.contains(id) }

    var body: some View {
        Group {
            switch tip.id {
            case "SpotifyElsewhere": SpotifyElsewhereDemo(playing: playing, scheme: scheme)
            case "MemoryLedger": MemoryLedgerDemo(playing: playing, scheme: scheme)
            case "LyricHighlight": LyricHighlightDemo(playing: playing, scheme: scheme)
            default: EmptyView()
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 16)
        .background(scheme.surfaceContainerLow, in: .rect(cornerRadius: 28, style: .continuous))
        .accessibilityHidden(true)
    }
}
