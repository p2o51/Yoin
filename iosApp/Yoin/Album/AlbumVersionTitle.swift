import SwiftUI

/// An album's version ("Deluxe", "全景声") as a small tonal tag after its name,
/// wherever the name is shown (Android AlbumVersionTitle, #154; 51 2026-11-06:
/// not only the album page). The value is the server's, never translated; the
/// name drops the " (version)" the server appended, so it isn't said twice.
struct AlbumVersionTitle<Title: View>: View {
    var album: Album
    var scheme: CoverScheme
    var spacing: CGFloat = 6
    /// Over the album page's white header twin.
    var onBar = false
    /// The name as the caller draws it (font, color, line limit).
    @ViewBuilder var title: (String) -> Title

    var body: some View {
        if let tag = album.versionTag {
            // The tag only where the name and the whole tag fit; on a narrow card the name alone
            // (a squeezed "H…" tag says nothing).
            ViewThatFits(in: .horizontal) {
                HStack(spacing: spacing) {
                    title(album.titleWithoutVersion).fixedSize(horizontal: true, vertical: false)
                    AlbumVersionTag(version: tag, scheme: scheme, onBar: onBar).fixedSize()
                }
                title(album.titleWithoutVersion)
            }
        } else {
            title(album.title)
        }
    }
}

/// The version as a small tonal block, like the format tags (Android AlbumVersionTag).
struct AlbumVersionTag: View {
    var version: String
    var scheme: CoverScheme
    /// Over the album page's spectrum bar: white on a white wash.
    var onBar = false

    var body: some View {
        Text(version)
            .lineLimit(1)
            .truncationMode(.tail)
            .yoinRole(Tokens.type.labelSmall)
            .foregroundStyle(onBar ? Color.white : scheme.onSecondaryContainer)
            .padding(.horizontal, 6)
            .padding(.vertical, 1)
            .background(onBar ? Color.white.opacity(0.18) : scheme.secondaryContainer,
                        in: .rect(cornerRadius: 6))
    }
}
