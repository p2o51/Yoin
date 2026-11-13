import SwiftUI

/// A song Apple lists but doesn't stream ([Track.notStreamed]) in a song list (Android SongListItem's
/// isUnavailable, UnavailableTrackBadge, UnavailableTrackReason): the row is dimmed, a "?" sits on the
/// cover, and a tap on the row or the "?" shows or hides the reason under it instead of playing.
enum NotStreamedRow {
    /// Android UnavailableTrackAlpha.
    static let alpha = 0.55

    enum Tap: Equatable { case play, explain }

    /// What a tap on [track]'s row does: a song Apple won't stream never starts (an explicit start would
    /// hand it to MusicKit, which drops it and plays the next song without a word).
    static func tap(_ track: Track) -> Tap { track.notStreamed ? .explain : .play }

    /// Shows or hides the reason under the row keyed [key].
    static func toggle<K: Hashable>(_ key: K, in shown: inout Set<K>) {
        if shown.remove(key) == nil { shown.insert(key) }
    }
}

/// Android UnavailableTrackBadge: an 18 "?" circle in secondaryContainer, labelMedium onSecondaryContainer.
/// In dark, secondaryContainer is a few tones off the dimmed row and the badge all but disappears, so it
/// takes secondary / onSecondary there.
struct NotStreamedBadge: View {
    var scheme: CoverScheme
    var action: () -> Void
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let tone = Self.tone(scheme, dark: colorScheme == .dark)
        Button(action: action) {
            Text(verbatim: "?")
                .yoinRole(Tokens.type.labelMedium)
                .foregroundStyle(tone.ink)
                .frame(width: 18, height: 18)
                .background(tone.fill, in: .circle)
                .contentShape(.circle)
        }
        .buttonStyle(PressScale())
        .accessibilityLabel("Why can't this play")
    }

    /// The badge's circle and its "?" (the album page's own badge uses them too).
    static func tone(_ scheme: CoverScheme, dark: Bool) -> (fill: Color, ink: Color) {
        dark ? (scheme.secondary, scheme.onSecondary) : (scheme.secondaryContainer, scheme.onSecondaryContainer)
    }
}

/// Android UnavailableTrackReason: bodySmall onSurfaceVariant, indented to the title, 10 below.
struct NotStreamedReason: View {
    var scheme: CoverScheme
    var leading: CGFloat
    var trailing: CGFloat

    var body: some View {
        Text("Apple Music doesn't stream this song in your region yet.")
            .yoinRole(Tokens.type.bodySmall)
            .lineSpacing(YoinType.lineSpacing(Tokens.type.bodySmall))
            .foregroundStyle(scheme.onSurfaceVariant)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.leading, leading)
            .padding(.trailing, trailing)
            .padding(.bottom, 10)
            .transition(.opacity.combined(with: .move(edge: .top)))
    }
}
