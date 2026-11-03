import SwiftUI

/// Album page. Pushed with NavigationStack; back = system back + edge swipe.
struct AlbumDetailView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.glassFlavor) private var flavor
    var album: Album
    var padColumn = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                titleBlock
                coverBlock
                trackFlow
            }
            .padding(.horizontal, 20)
            .padding(.top, 4)
            .frame(maxWidth: 640, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
        .contentMargins(.bottom, padColumn ? 110 : 120, for: .scrollContent)
        .scrollEdgeEffectStyle(.soft, for: .all)
        .background(Yoin.surface)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {} label: { YoinIcon("more_horizontal", size: 22) }
                    .accessibilityLabel("More")
            }
            if padColumn {
                ToolbarItem(placement: .bottomBar) { padActions }
            }
        }
    }

    private var titleBlock: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(album.title)
                .font(.gsf(34, .semibold))
                .foregroundStyle(Yoin.ink)
                .lineLimit(2)
            Text(subtitle)
                .font(.gsf(16, .medium))
        }
    }

    private var subtitle: AttributedString {
        var artist = AttributedString(album.artist)
        artist.foregroundColor = album.palette.tint
        var rest = AttributedString("  •  Album  •  \(album.year)")
        rest.foregroundColor = Yoin.inkVariant
        return artist + rest
    }

    private var coverBlock: some View {
        let score = album.rating ?? album.averageRating
        return VStack(alignment: .leading, spacing: 10) {
            CoverArt(palette: album.palette, cornerRadius: 8, seed: album.seed)
                .aspectRatio(1, contentMode: .fit)
                .padding(.trailing, 44)
                .overlay(alignment: .bottomTrailing) {
                    CookieBadge(score: score ?? 0, palette: album.palette, size: 96,
                                rings: album.tracks.count, ratedRings: album.ratedCount,
                                albumRating: album.rating != nil)
                        .opacity(score == nil ? 0.5 : 1)
                        .offset(x: 0, y: 26)
                }
            HStack(spacing: 6) {
                if album.rating == nil, album.ratedCount > 0 {
                    Text("Based on \(album.ratedCount)/\(album.tracks.count)")
                } else if album.rating != nil {
                    Text("Your rating")
                } else {
                    Text("Unrated")
                }
            }
            .font(.gsf(14, .medium))
            .foregroundStyle(Yoin.inkVariant)
            .padding(.top, 4)
        }
    }

    /// The flowing inline track list: one paragraph, alternating colors.
    private var trackFlow: some View {
        var text = AttributedString()
        for (i, t) in album.tracks.enumerated() {
            // NBSP keeps a number on the same line as its title.
            var num = AttributedString("\(t.number)\u{00A0}")
            num.foregroundColor = Yoin.inkVariant.opacity(0.7)
            num.font = .gsf(13, .medium)
            num.baselineOffset = 4
            var title = AttributedString(t.title)
            title.foregroundColor = i.isMultiple(of: 2) ? Yoin.ink : album.palette.tint
            title.font = .gsf(22, .medium)
            var gap = AttributedString("   ")
            gap.font = .gsf(22)
            text += num + title + gap
        }
        return Text(text)
            .lineSpacing(6)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 8)
    }

    private var padActions: some View {
        HStack {
            Button {} label: {
                Label { Text("Play").font(.gsf(16, .semibold)) } icon: { YoinIcon("play_filled", size: 20) }
            }
            .buttonStyle(.glassProminent)
            .tint(album.palette.tint)
        }
    }
}
