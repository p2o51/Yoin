import CoreGraphics

/*
 * Memories' container budget (Android showcase/MemoriesLayout, twostate4
 * `layoutFor` / `medCov` / `sealFor`). A page reads only its own container's
 * size, never the device's (adaptive principle 1): an iPad window with the
 * album column open lays Memories out as the Medium it is.
 *
 *  · Phone (W < 600): the two states (card ⇄ diary).
 *  · Medium (600 ≤ W < 900, or a spread whose right column would be under
 *    360, or whose left page can't hold Medium's cover): the phone's two
 *    states enlarged, the card in a 480 column, the diary in min(640, W − 32).
 *  · Spread (everything else): two pages, no morph. The left page is the
 *    exhibit; the right page is the diary from your own entry on.
 *
 * Phone landscape (height under 480 with room for two pages) is the spread's
 * structure with a lower cover floor (88) and a smaller emblem.
 */

enum MemoriesTier: String, Equatable { case phone, medium, spread }

/// One container's budget, in pt.
struct MemoriesLayout: Equatable {
    var tier: MemoriesTier
    /// Shorter than 760: the 16:9 rhythm.
    var short: Bool
    var landscape: Bool
    var cover: CGFloat
    var seal: CGFloat
    /// The Home pill's start and the dots' end inset.
    var pillStart: CGFloat
    var dotsEnd: CGFloat
    var barHeight: CGFloat = MemoriesLayoutTokens.barHeight
    /// Spread: the left page's width and the right column's measure.
    var leftPage: CGFloat = 0
    var measure: CGFloat = 0
    /// Medium: the diary column.
    var diaryColumn: CGFloat = 0
    /// The card's top air (phone / Medium).
    var air1: CGFloat = 0
    /// Spread: the cover's floor.
    var minCover: CGFloat = 0

    var isSpread: Bool { tier == .spread }
    var twoState: Bool { tier != .spread }
    /// The bar cover's start (the pill is 36 there) and slot B's.
    var barCoverStart: CGFloat { pillStart + 48 }
    var slotBStart: CGFloat { pillStart + 102 }
}

/// The budget's numbers. Layout constants, not motion tokens.
enum MemoriesLayoutTokens {
    static let mediumFrom: CGFloat = 600
    static let spreadFrom: CGFloat = 900
    static let shortBelow: CGFloat = 760
    static let landscapeBelow: CGFloat = 480
    static let phoneCover: CGFloat = 256
    static let phoneCoverShort: CGFloat = 168
    static let phoneSeal: CGFloat = 96
    static let phoneSealShort: CGFloat = 72
    static let phoneAir1: CGFloat = 59
    static let phoneAir1Short: CGFloat = 24
    static let mediumCoverMax: CGFloat = 360
    static let mediumCoverOffset: CGFloat = 580
    static let mediumDiaryColumn: CGFloat = 640
    static let mediumDiaryMargin: CGFloat = 32
    static let mediumSealRatio: CGFloat = 0.375
    static let mediumAir1Max: CGFloat = 120
    static let mediumAir1Min: CGFloat = 24
    static let mediumAir1Ratio: CGFloat = 0.2
    static let mediumCardColumn: CGFloat = 480
    static let sealMin: CGFloat = 96
    static let sealMinLadder: CGFloat = 72
    static let sealMax: CGFloat = 124
    static let spreadLeftRatio: CGFloat = 0.46
    static let spreadLeftMin: CGFloat = 400
    static let spreadLeftMax: CGFloat = 600
    static let spreadGutters: CGFloat = 104
    static let spreadMeasureMin: CGFloat = 360
    static let spreadMeasureMax: CGFloat = 560
    static let spreadCoverMin: CGFloat = 300
    static let spreadCoverOfLeft: CGFloat = 96
    static let spreadCoverOfHeight: CGFloat = 386
    static let spreadSealRatio: CGFloat = 0.4
    static let spreadMinCover: CGFloat = 200
    static let landscapeLeftMin: CGFloat = 280
    static let landscapeMeasureMin: CGFloat = 240
    static let landscapeMinCover: CGFloat = 88
    static let landscapeSealRatio: CGFloat = 0.55
    static let landscapeSealMin: CGFloat = 48
    static let landscapeSealMax: CGFloat = 72
    static let barHeight: CGFloat = 64
    /// The spread's left page under the cover: title, album line and Go to album (the fit's text budget).
    static let spreadTextBudget: CGFloat = 230
    /// Bar insets per tier (pill start, dots end).
    static let phoneBar: (CGFloat, CGFloat) = (16, 12)
    static let mediumBar: (CGFloat, CGFloat) = (24, 18)
    static let spreadBar: (CGFloat, CGFloat) = (32, 26)
}

enum MemoriesLayouts {
    private static func clamp(_ x: CGFloat, _ lo: CGFloat, _ hi: CGFloat) -> CGFloat { min(hi, max(lo, x)) }
    /// JavaScript's Math.round: half up.
    private static func jsRound(_ x: CGFloat) -> CGFloat { (x + 0.5).rounded(.down) }

    /// Medium's cover at a height: the phone's at the same height up to 360.
    static func mediumCover(height h: CGFloat) -> CGFloat {
        typealias T = MemoriesLayoutTokens
        return clamp(h - T.mediumCoverOffset, h < T.shortBelow ? T.phoneCoverShort : T.phoneCover, T.mediumCoverMax)
    }

    /// The budget for a container of [w] × [h].
    static func layout(width w: CGFloat, height h: CGFloat) -> MemoriesLayout {
        typealias T = MemoriesLayoutTokens
        let short = h < T.shortBelow
        if h < T.landscapeBelow && w >= T.mediumFrom { return landscapeSpread(w) }
        if w < T.mediumFrom {
            return MemoriesLayout(tier: .phone, short: short, landscape: false,
                                  cover: short ? T.phoneCoverShort : T.phoneCover,
                                  seal: short ? T.phoneSealShort : T.phoneSeal,
                                  pillStart: T.phoneBar.0, dotsEnd: T.phoneBar.1,
                                  air1: short ? T.phoneAir1Short : T.phoneAir1)
        }
        let lp = clamp(jsRound(T.spreadLeftRatio * w), T.spreadLeftMin, T.spreadLeftMax)
        let meas = w - lp - T.spreadGutters
        let medium = mediumCover(height: h)
        let spreadCover = max(T.phoneCoverShort, min(max(T.spreadCoverMin, medium), lp - T.spreadCoverOfLeft,
                                                     h - T.spreadCoverOfHeight))
        // A spread whose left page can't hold Medium's cover stays Medium: the bigger tier never shows a smaller exhibit.
        if w < T.spreadFrom || meas < T.spreadMeasureMin || spreadCover < medium {
            let air1 = short ? T.mediumAir1Min : clamp(jsRound((h - T.shortBelow) * T.mediumAir1Ratio), T.mediumAir1Min, T.mediumAir1Max)
            return MemoriesLayout(tier: .medium, short: short, landscape: false, cover: medium,
                                  seal: jsRound(clamp(medium * T.mediumSealRatio, short ? T.phoneSealShort : T.sealMin, T.sealMax)),
                                  pillStart: T.mediumBar.0, dotsEnd: T.mediumBar.1,
                                  diaryColumn: min(T.mediumDiaryColumn, w - T.mediumDiaryMargin), air1: air1)
        }
        return MemoriesLayout(tier: .spread, short: false, landscape: false, cover: spreadCover,
                              seal: jsRound(clamp(spreadCover * T.spreadSealRatio, T.sealMin, T.sealMax)),
                              pillStart: T.spreadBar.0, dotsEnd: T.spreadBar.1,
                              leftPage: lp, measure: clamp(meas, T.spreadMeasureMin, T.spreadMeasureMax),
                              minCover: T.spreadMinCover)
    }

    private static func landscapeSpread(_ w: CGFloat) -> MemoriesLayout {
        typealias T = MemoriesLayoutTokens
        let lp = clamp(jsRound(T.spreadLeftRatio * w), T.landscapeLeftMin, T.spreadLeftMax)
        var l = MemoriesLayout(tier: .spread, short: true, landscape: true, cover: T.phoneCoverShort,
                               seal: T.phoneSealShort, pillStart: T.spreadBar.0, dotsEnd: T.spreadBar.1,
                               leftPage: lp, measure: clamp(w - lp - T.spreadGutters, T.landscapeMeasureMin, T.spreadMeasureMax),
                               minCover: T.landscapeMinCover)
        l.seal = seal(for: l, cover: l.cover)
        return l
    }

    /// The emblem for a cover on the height ladder (prototype sealFor): the
    /// two states keep the budget's; the spread follows the cover at .4 (to 72
    /// below the offer); landscape .55 of the cover, 48–72.
    static func seal(for l: MemoriesLayout, cover c: CGFloat) -> CGFloat {
        typealias T = MemoriesLayoutTokens
        if l.twoState { return l.seal }
        if l.landscape { return clamp(jsRound(c * T.landscapeSealRatio), T.landscapeSealMin, T.landscapeSealMax) }
        return jsRound(clamp(c * T.spreadSealRatio, c < l.cover ? T.sealMinLadder : T.sealMin, T.sealMax))
    }

    /// The spread's one cover for the deck at a page height [room] under the bar
    /// (a simple fit in place of Android's measured height ladder): the offer,
    /// stepped down to the floor when the left page's text wouldn't fit.
    static func spreadCover(_ l: MemoriesLayout, room: CGFloat) -> CGFloat {
        max(l.minCover, min(l.cover, room - MemoriesLayoutTokens.spreadTextBudget))
    }
}
