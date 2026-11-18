import SwiftUI

/// `-screen emblem-award`: the four award tiers side by side, waiting uncut,
/// then cut together after a second, and again every few seconds (for frame
/// strips and haptic review). `-screen emblem-award-replay` replays instead.
struct EmblemAwardLab: View {
    private struct Sample: Identifiable {
        let id: Int
        let score: Double
        let kind: ScoreEmblem.Kind
        let rated: [Bool]
        let palette: CoverPalette
        let award: ScoreEmblemAward
    }

    private static let size: CGFloat = 132
    @State private var samples: [Sample] = {
        let palettes = MockLibrary.allAlbums.map(\.palette)
        let specs: [(Double, ScoreEmblem.Kind, Int)] = [(5.4, .average, 9), (7.2, .album, 11), (8.8, .album, 12),
                                                        (10, .album, 14)]
        return specs.enumerated().map { i, spec in
            let rated = (0..<spec.2).map { ($0 * 7 + i) % 5 != 0 }
            return Sample(id: i, score: spec.0, kind: spec.1, rated: rated,
                          palette: palettes[(i * 3) % max(1, palettes.count)],
                          award: ScoreEmblemAward(score: spec.0, kind: spec.1, rated: rated, size: size))
        }
    }()

    private var replay: Bool { LaunchConfig.baseScreen == "emblem-award-replay" }

    var body: some View {
        LazyVGrid(columns: [GridItem(.fixed(180)), GridItem(.fixed(180))], spacing: 48) {
            ForEach(samples) { s in
                VStack(spacing: 14) {
                    ScoreEmblem(score: s.score, kind: s.kind, rated: s.rated, palette: s.palette,
                                size: Self.size, award: s.award, tilt: true)
                    Text("Tier \(s.award.tier)").font(.gsf(13, .semibold)).foregroundStyle(Yoin.inkVariant)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Yoin.surface)
        .task {
            while !Task.isCancelled {
                if !replay { samples.forEach { $0.award.setPending(true) } }
                try? await Task.sleep(for: .seconds(1))
                samples.forEach { $0.award.play(replay ? .replay : .first) }
                try? await Task.sleep(for: .seconds(3))
            }
        }
    }
}

#Preview { EmblemAwardLab() }
