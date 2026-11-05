import SwiftUI

/// `-screen emblem-glass`: every EmblemGlass kind (rows) on three covers (columns), each pinned
/// on its cover as Memories and the scrapbook pin it, and on the page beside it as the album
/// page shows it; the last column is the Unrated mould. For picking the glass in light and dark.
struct EmblemGlassLab: View {
    private static let covers: [CoverPalette] = {
        let all = MockLibrary.allAlbums.map(\.palette)
        return [0, 3, 6].map { all[$0 % max(1, all.count)] }
    }()

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            ForEach(EmblemGlass.allCases, id: \.self) { kind in
                VStack(alignment: .leading, spacing: 6) {
                    Text(kind.title).font(.gsf(13, .semibold)).foregroundStyle(Yoin.inkVariant)
                    HStack(spacing: 6) {
                        ForEach(Array(Self.covers.enumerated()), id: \.offset) { i, palette in
                            cell(palette, score: [7.4, 9.1, 6.2][i], kind: i == 2 ? .average : .album)
                        }
                        ScoreEmblem(score: 0, kind: .unrated, rated: Array(repeating: false, count: 9),
                                    palette: Self.covers[0], size: 56)
                    }
                }
                .environment(\.emblemGlassOverride, kind)
            }
        }
        .padding(.horizontal, 16)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Yoin.surface)
    }

    private func cell(_ palette: CoverPalette, score: Double, kind: ScoreEmblem.Kind) -> some View {
        let rated = (0..<10).map { $0 % 4 != 3 }
        return ZStack(alignment: .bottomTrailing) {
            CoverArt(palette: palette, cornerRadius: 8, seed: Int(score * 10))
                .frame(width: 76, height: 76)
            ScoreEmblem(score: score, kind: kind, rated: rated, palette: palette, size: 64, onCover: true)
                .offset(x: 14, y: 14)
        }
        .frame(width: 76, height: 76)
        .padding(.trailing, 14)
        .padding(.bottom, 14)
    }
}

private struct EmblemGlassOverrideKey: EnvironmentKey {
    static let defaultValue: EmblemGlass? = nil
}

extension EnvironmentValues {
    /// A lab's pick of the emblem's glass for the views under it (nil = Glass Lab's setting).
    var emblemGlassOverride: EmblemGlass? {
        get { self[EmblemGlassOverrideKey.self] }
        set { self[EmblemGlassOverrideKey.self] = newValue }
    }
}

#Preview { EmblemGlassLab() }
