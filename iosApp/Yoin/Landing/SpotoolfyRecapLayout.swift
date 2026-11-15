import CoreGraphics
import Foundation

// The recap's keyword burst, laid out (Android ui/spotoolfy/RecapLayout.kt): the words by rank, and where each
// one lands. Pure and deterministic, so the same words on the same area land in the same places.

/// A placed box, in points from the area's top left.
struct RecapBox: Equatable {
    var x: CGFloat
    var y: CGFloat
    var width: CGFloat
    var height: CGFloat

    func overlaps(_ other: RecapBox, gap: CGFloat) -> Bool {
        x < other.x + other.width + gap && x + width + gap > other.x &&
            y < other.y + other.height + gap && y + height + gap > other.y
    }

    var midX: CGFloat { x + width / 2 }
    var midY: CGFloat { y + height / 2 }
}

/// The keyword burst's size steps, by rank: three big words, then six, nine, and the rest.
enum RecapWordTier: Equatable {
    case largest, large, medium, small

    init(rank: Int) {
        switch rank {
        case ..<3: self = .largest
        case ..<9: self = .large
        case ..<18: self = .medium
        default: self = .small
        }
    }
}

/// One chip of the burst: a keyword or one of the few emoji.
struct RecapWord: Equatable {
    var text: String
    var count: Int
    var tier: RecapWordTier
    var keyword: RecapKeyword?
}

enum SpotoolfyRecapLayout {
    static let emojiFirstSlot = 4
    static let emojiSpacing = 5
    /// The spiral's angle range and resolution (radians), and its slight widening into an ellipse.
    static let spiralTurns: CGFloat = 120
    static let spiralDt: CGFloat = 0.09
    static let stretchX: CGFloat = 1.05
    static let stretchY: CGFloat = 1.18

    /// The burst's words in rank order, the emoji tucked in among the first few (they read as words, and an emoji
    /// only at the end would sit at the rim).
    static func words(_ keywords: [RecapKeyword], emoji: [RecapEmoji]) -> [RecapWord] {
        var words = keywords.enumerated().map { rank, k in
            RecapWord(text: k.text, count: k.count, tier: RecapWordTier(rank: rank), keyword: k)
        }
        for (i, e) in emoji.enumerated() {
            let at = min(words.count, emojiFirstSlot + i * emojiSpacing)
            words.insert(RecapWord(text: e.emoji, count: e.count, tier: .medium, keyword: nil), at: at)
        }
        return words
    }

    /// Lays `sizes` out from the area's centre along an outward spiral, in order, around `obstacles` (the lyric
    /// cards). The first word lands nearest the centre. A word that finds no free spot inside the area gets nil
    /// and is not shown.
    static func spiral(_ sizes: [CGSize], width: CGFloat, height: CGFloat, obstacles: [RecapBox],
                       gap: CGFloat, step: CGFloat) -> [RecapBox?] {
        var placed = obstacles
        let cx = width / 2, cy = height / 2
        return sizes.map { size in
            let w = size.width, h = size.height
            var t: CGFloat = 0
            var found: RecapBox?
            while found == nil && t < spiralTurns {
                let r = step * t
                let box = RecapBox(x: cx + r * cos(t) * stretchX - w / 2, y: cy + r * sin(t) * stretchY - h / 2,
                                   width: w, height: h)
                let inside = box.x >= 0 && box.y >= 0 && box.x + w <= width && box.y + h <= height
                if inside && !placed.contains(where: { $0.overlaps(box, gap: gap) }) { found = box }
                t += spiralDt
            }
            if let found { placed.append(found) }
            return found
        }
    }

    /// Up to `maxWall` covers; a short list repeats so the wall still fills, and none at all gives colour tiles.
    static func wallCovers(_ urls: [String], min minWall: Int = 20, max maxWall: Int = 24) -> [String?] {
        if urls.isEmpty { return Array(repeating: nil, count: minWall) }
        if urls.count < minWall { return (0..<minWall).map { urls[$0 % urls.count] } }
        return Array(urls.prefix(maxWall))
    }

    /// The cover wall's tiles: five columns, rows a little apart, a slight hand-placed scatter, the same on
    /// every layout. Returns each tile's centre and resting tilt (degrees).
    static func wall(count: Int, tile: CGFloat, width: CGFloat, height: CGFloat,
                     columns: Int = 5, rowPitch: CGFloat = 1.6, jitter: CGFloat = 1.2) -> [(center: CGPoint, tilt: Double)] {
        guard count > 0 else { return [] }
        let rows = (count + columns - 1) / columns
        let gapX = columns > 1 ? (width - tile) / CGFloat(columns - 1) : 0
        let spare = max(0, height - tile)
        let gapY = rows > 1 ? min(tile * rowPitch, spare / CGFloat(rows - 1)) : 0
        let top = (height - CGFloat(rows - 1) * gapY - tile) / 2
        let maxX = max(0, width - tile), maxY = max(0, height - tile)
        return (0..<count).map { i in
            let column = i % columns, row = i / columns
            let jx = CGFloat((i * 37) % 21 - 10) * jitter
            let jy = CGFloat((i * 53) % 15 - 7) * jitter
            let x = min(max(0, CGFloat(column) * gapX + jx), maxX)
            let y = min(max(0, top + CGFloat(row) * gapY + jy), maxY)
            return (CGPoint(x: x + tile / 2, y: y + tile / 2), Double((i * 29) % 17 - 8))
        }
    }

    /// A word's flying spin, by place: the same scatter Android uses.
    static func spin(_ index: Int) -> Double { Double((index * 41) % 51 - 25) }
}
