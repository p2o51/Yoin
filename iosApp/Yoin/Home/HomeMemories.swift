import CoreGraphics
import Foundation
import SwiftUI

// The Home side of Memories, pure: what the bubble says and when (Android
// ui/home/HomeMemoryPill.kt, HomeMemoryBubble.kt, HomeBubbleSay.kt), where its
// arrow sits and the outline it unrolls into. Unit-tested (HomeMemoriesTests).

/// What the Memories bubble can say (Android `HomeMemoryPill`): the latest
/// written memory, the others its idle reminder brings back, the note count
/// and the key that says something new was written.
struct HomeMemoryPill: Hashable, Sendable {
    struct Latest: Hashable, Sendable {
        /// The album the deck stops on (Android `sessionId`).
        var album: Album
        var albumName: String
        var artistName: String?
        /// "%.1f": your album rating, else your track average; nil for none.
        var scoreText: String?
        /// When you last wrote on it (the bubble's "3d ago"); nil = plays only.
        var writtenAt: Int64?
        /// Its Memory title (yours or the AI's); nil = the album name stands in.
        var memoryTitle: String?

        var albumId: String { album.id.rawId }
    }

    var latest: Latest?
    var noteCount: Int
    /// Whose memories these are (the bubble's "seen" mark is per account).
    var scope: String
    /// "newest write#note count": the bubble speaks when it grows.
    var newsKey: String
    /// The others, newest written first (the idle reminder's turns).
    var recall: [Latest] = []

    /// A latest memory or kept notes.
    var hasSomethingToSay: Bool { latest != nil || noteCount > 0 }
}

enum HomeMemories {
    static let recallLimit = 8

    /// `pickLatestMemory`: the most recently WRITTEN memory; plays only break
    /// ties for memories never written on. The first of equal keys wins (the
    /// candidates arrive strength-ranked).
    static func pickLatest(_ memories: [AlbumMemory]) -> AlbumMemory? {
        var best: AlbumMemory?
        var bestKey: Int64 = .min
        for m in memories {
            let k = m.lastWrittenAt ?? m.lastPlayedAt ?? m.firstPlayedAt ?? 0
            if best == nil || k > bestKey { best = m; bestKey = k }
        }
        return best
    }

    /// `pickMemoryRecall`: every memory but [latest], one per album, written
    /// ones first (newest write first), plays-only after them.
    static func pickRecall(_ memories: [AlbumMemory], latest: AlbumMemory?) -> [AlbumMemory] {
        var seen = Set<String>()
        if let latest { seen.insert(latest.albumId) }
        let rest = memories.filter { seen.insert($0.albumId).inserted }
        return rest.enumerated().sorted { a, b in
            let wa = a.element.lastWrittenAt != nil, wb = b.element.lastWrittenAt != nil
            if wa != wb { return wa }
            let ka = a.element.lastWrittenAt ?? a.element.lastPlayedAt ?? a.element.firstPlayedAt ?? 0
            let kb = b.element.lastWrittenAt ?? b.element.lastPlayedAt ?? b.element.firstPlayedAt ?? 0
            if ka != kb { return ka > kb }
            return a.offset < b.offset
        }
        .prefix(recallLimit).map(\.element)
    }

    /// "time#count": the newest write on any memory and the note count.
    static func newsKey(_ latest: AlbumMemory?, noteCount: Int) -> String {
        "\(latest?.lastWrittenAt ?? 0)#\(noteCount)"
    }

    /// The pill from the account's memories: only the eligible ones are the
    /// Memory pool (Android reads `memoryEligible`).
    static func pill(_ memories: [AlbumMemory], noteCount: Int, scope: String,
                     title: (AlbumMemory) -> String? = { _ in nil }) -> HomeMemoryPill {
        let pool = memories.filter(\.isMemoryEligible)
        let latest = pickLatest(pool)
        let notes = max(0, noteCount)
        func line(_ m: AlbumMemory) -> HomeMemoryPill.Latest {
            HomeMemoryPill.Latest(album: m.album, albumName: m.album.title,
                                  artistName: m.album.artist.isEmpty ? nil : m.album.artist,
                                  scoreText: scoreText(m), writtenAt: m.lastWrittenAt, memoryTitle: title(m))
        }
        return HomeMemoryPill(latest: latest.map(line), noteCount: notes, scope: scope,
                              newsKey: newsKey(latest, noteCount: notes),
                              recall: pickRecall(pool, latest: latest).map(line))
    }

    /// The seal's own score: your album rating, else your track average.
    static func scoreText(_ m: AlbumMemory) -> String? {
        if let r = m.albumRating, r > 0 { return HomeHistory.scoreText(r) }
        if let a = m.averageSongRating, a > 0 { return HomeHistory.scoreText(a) }
        return nil
    }

    /// A news key read back: (newest write, note count); nil = unreadable.
    static func parseNewsKey(_ key: String?) -> (Int64, Int)? {
        guard let parts = key?.split(separator: "#", omittingEmptySubsequences: false), parts.count == 2,
              let t = Int64(parts[0]), let n = Int(parts[1]) else { return nil }
        return (t, n)
    }

    /// `isMemoryNews`: a write newer than the last one it spoke about, or
    /// more notes than it last saw. A deletion moves the key back: not news.
    static func isNews(_ pill: HomeMemoryPill?, lastSeen: String?) -> Bool {
        guard let pill, pill.hasSomethingToSay, !pill.newsKey.isEmpty else { return false }
        guard let now = parseNewsKey(pill.newsKey) else { return pill.newsKey != lastSeen }
        guard let seen = parseNewsKey(lastSeen) else { return true }
        return now.0 > seen.0 || now.1 > seen.1
    }

    /// `memoryBubbleSubject`: news is always the latest; the idle reminder
    /// brings back the next of the others on each turn.
    static func subject(_ pill: HomeMemoryPill?, reason: BubbleReason, turn: Int) -> HomeMemoryPill.Latest? {
        guard let pill else { return nil }
        if reason == .news || pill.recall.isEmpty { return pill.latest }
        let n = pill.recall.count
        return pill.recall[((turn % n) + n) % n]
    }
}

// MARK: - When it speaks, when it goes

enum BubbleReason: Hashable, Sendable { case news, idle }

/// What happens to the bubble (Android `MemoryBubbleCue`).
enum BubbleCue: Hashable, Sendable {
    /// Something written this account hasn't been told, and Home can be read.
    case news
    /// Home sat readable and untouched for the idle window.
    case idleElapsed
    /// Said in full, it held for the dwell.
    case dwellOver
    /// A touch anywhere (a tap on it too).
    case touch
    /// Home went out of reach: covered, scrolled, pulled toward Memories.
    case outOfReach
    /// The app went to the background.
    case backgrounded
}

/// What it is saying (nil = tucked away) and whether it has spoken since the
/// app came to the foreground (Android `MemoryBubbleTurn.after`).
struct BubbleTurn: Hashable, Sendable {
    var reason: BubbleReason?
    var spokeThisForeground = false

    func after(_ cue: BubbleCue) -> BubbleTurn {
        switch cue {
        case .news: BubbleTurn(reason: .news, spokeThisForeground: true)
        case .idleElapsed: reason == nil ? BubbleTurn(reason: .idle, spokeThisForeground: true) : self
        case .dwellOver, .touch, .outOfReach: BubbleTurn(reason: nil, spokeThisForeground: spokeThisForeground)
        case .backgrounded: BubbleTurn()
        }
    }
}

/// Timing (HomeMemoryBubble.kt / HomeBubbleSay.kt, owner 2026-10-11 / 10-17).
enum BubbleTiming {
    /// "不动就出现": Home readable and untouched this long, a fresh pick each time.
    static let idleRange: ClosedRange<Double> = 4.0...5.0
    /// News said in full holds this long, then flies back into the arrow.
    static let newsDwell: Double = 6
    /// What it says when left alone holds this long.
    static let chatterDwellRange: ClosedRange<Double> = 2.0...3.0
    /// News waits this long after Home is up.
    static let newsDelay: Double = 0.45
    /// The mark rests beside the title this long before it rolls off.
    static let markHold: Double = 0.7
}

// MARK: - Geometry (HomeMemoryBubble.kt constants, dp = pt)

enum BubbleMetrics {
    static let bodyHeight: CGFloat = 60
    /// Fully round ends (owner 10-09, option B).
    static let corner: CGFloat = 30
    static let coverSize: CGFloat = 40
    static let coverInset: CGFloat = 15
    static let coverTitleGap: CGFloat = 12
    /// The title stops clear of the mark on the corner.
    static let trail: CGFloat = 48
    static let tipAboveChevron: CGFloat = 26
    static let chevronAboveBody: CGFloat = 10
    static let tailFlank: CGFloat = 46
    static let tipLean: CGFloat = 6
    static let tipDrop: CGFloat = 18
    /// The body ends this far right of the chevron.
    static let reach: CGFloat = 80
    static let minLeft: CGFloat = 150
    static let maxWidth: CGFloat = 440
    static let edgeMargin: CGFloat = 12
    static let markSize: CGFloat = 44
    static let markSqueeze: CGFloat = 16
    static let markTitleGap: CGFloat = 12
    static let markCornerInsetX: CGFloat = 20
    static let markCornerInsetY: CGFloat = 10
    static let arrowTouch: CGFloat = 48
    static let arrowIcon: CGFloat = 20
    static let arrowBelowCutout: CGFloat = 2
    static let arrowPullNudge: CGFloat = 2
    /// The arrow fades over the first stretch of scroll.
    static let arrowScrollFade: CGFloat = 48
    /// With this much room beside the cover the bubble says when, and the Memory's title if it fits.
    static let saysMoreFrom: CGFloat = 200
    static let agoGap: CGFloat = 8
    /// The tail rises over the last stretch of the unroll.
    static let tailRisesFrom: CGFloat = 0.82
    static let flankSettle: CGFloat = 0.62
    /// Flying away it shrinks by this about the tail's tip and lifts this far.
    static let flyShrink: CGFloat = 0.75
    static let flyLift: CGFloat = 20
    static let flyFadeFrom: CGFloat = 0.35
    static let flyGoneAt: CGFloat = 0.9
    /// Tucked from this far unrolled it flies away; less, the mark folds back.
    static let flyAwayFrom: CGFloat = 0.5
    static let armStagger: CGFloat = 0.12
}

/// Where the speaking bubble lands (Android `MemoryBubbleGeometry`).
struct BubbleGeometry: Hashable, Sendable {
    var body: CGRect
    var tailX: CGFloat
    var tailTop: CGFloat
    var markStart: CGPoint
    var markEnd: CGPoint

    /// The body at unroll [s]: the mark's own box at 0, grown to `body` at 1,
    /// its right end riding with the mark (past 1 on the spring's overshoot).
    func body(at s: CGFloat, markHalf: CGFloat = BubbleMetrics.markSize / 2) -> CGRect {
        let mark = CGPoint(x: lerpf(markStart.x, markEnd.x, s), y: lerpf(markStart.y, markEnd.y, s))
        let left = lerpf(markStart.x - markHalf, body.minX, s)
        let top = lerpf(markStart.y - markHalf, body.minY, s)
        let right = mark.x + (body.maxX - markEnd.x)
        let bottom = lerpf(markStart.y + markHalf, body.maxY, s)
        return CGRect(x: left, y: top, width: max(0, right - left), height: max(0, bottom - top))
    }
}

func lerpf(_ a: CGFloat, _ b: CGFloat, _ t: CGFloat) -> CGFloat { a + (b - a) * t }

/// Prototype `ss(a, b, x)`.
func smoothstep(_ a: CGFloat, _ b: CGFloat, _ x: CGFloat) -> CGFloat {
    let t = min(max((x - a) / (b - a), 0), 1)
    return t * t * (3 - 2 * t)
}

enum BubbleLayout {
    /// `memoryArrowCenterX`: under the cutout when the bubble has room there,
    /// else the page's centre, else as far left as the bubble allows.
    static func arrowCenterX(cutoutCenterX: CGFloat?, pageCentre: CGFloat, titleLeft: CGFloat, freeEnd: CGFloat,
                             minLeft: CGFloat = BubbleMetrics.minLeft,
                             reach: CGFloat = BubbleMetrics.reach) -> (x: CGFloat, underCutout: Bool) {
        func fits(_ x: CGFloat) -> Bool { x - minLeft >= titleLeft && x + reach <= freeEnd }
        if let c = cutoutCenterX, fits(c) { return (c, true) }
        if fits(pageCentre) { return (pageCentre, false) }
        return (titleLeft + minLeft, false)
    }

    /// The geometry for a header whose title sits at [titleFrame], the
    /// arrow at ([arrowX], [arrowCenterY]).
    static func geometry(titleFrame: CGRect, arrowX: CGFloat, arrowCenterY: CGFloat) -> BubbleGeometry {
        let m = BubbleMetrics.self
        let right = arrowX + m.reach
        let left = max(titleFrame.minX, right - m.maxWidth)
        let top = max(titleFrame.minY, arrowCenterY + m.chevronAboveBody)
        let body = CGRect(x: left, y: top, width: right - left, height: m.bodyHeight)
        return BubbleGeometry(body: body, tailX: arrowX, tailTop: arrowCenterY - m.tipAboveChevron,
                              markStart: CGPoint(x: titleFrame.minX + m.markSize / 2, y: titleFrame.midY),
                              markEnd: CGPoint(x: body.maxX - m.markCornerInsetX, y: body.maxY - m.markCornerInsetY))
    }

    static func flightAlpha(_ fly: CGFloat) -> CGFloat {
        1 - smoothstep(BubbleMetrics.flyFadeFrom, BubbleMetrics.flyGoneAt, fly)
    }

    /// 0 while any of the bubble is seen, 1 as the flight lands.
    static func arrowHomecoming(_ fly: CGFloat) -> CGFloat { smoothstep(BubbleMetrics.flyGoneAt, 1, fly) }

    /// The mark's arms bloom one beat apart, each landing on exactly 1.
    static func armProgress(_ p: CGFloat, arm i: Int) -> CGFloat {
        let s = BubbleMetrics.armStagger * CGFloat(i)
        return max(0, (p - s) / (1 - s))
    }
}

/// `memoryBubbleOutline`: a rounded body; with a [tailX], a tail rising from
/// its top to a sharp tip at ([tailX], [tailTop]) — two mirrored concave
/// flanks [flank] either side, leaving the tip [lean] out and [drop] down
/// and levelling into the top edge. A tail with no room is left off.
struct BubbleOutline: Shape {
    var body: CGRect
    var radius: CGFloat
    var tailX: CGFloat?
    var tailTop: CGFloat = 0
    var flank: CGFloat = BubbleMetrics.tailFlank
    var lean: CGFloat = BubbleMetrics.tipLean
    var drop: CGFloat = BubbleMetrics.tipDrop

    /// Whether the tail fits (into neither corner, its tip above the body).
    var hasTail: Bool {
        guard let tailX else { return false }
        let rr = clampedRadius
        return tailX - flank >= body.minX + rr && tailX + flank <= body.maxX - rr && tailTop < body.minY
    }

    private var clampedRadius: CGFloat { max(0, min(radius, min(body.width, body.height) / 2)) }

    func path(in _: CGRect) -> Path {
        let l = body.minX, t = body.minY, r = body.maxX, b = body.maxY
        let rr = clampedRadius
        let h = rr * 0.5523
        var p = Path()
        p.move(to: CGPoint(x: l, y: t + rr))
        p.addCurve(to: CGPoint(x: l + rr, y: t), control1: CGPoint(x: l, y: t + rr - h), control2: CGPoint(x: l + rr - h, y: t))
        if hasTail, let tx = tailX {
            let fs = tx - flank
            p.addLine(to: CGPoint(x: fs, y: t))
            p.addCurve(to: CGPoint(x: tx, y: tailTop), control1: CGPoint(x: fs + flank * BubbleMetrics.flankSettle, y: t),
                       control2: CGPoint(x: tx - lean, y: tailTop + drop))
            p.addCurve(to: CGPoint(x: tx + flank, y: t), control1: CGPoint(x: tx + lean, y: tailTop + drop),
                       control2: CGPoint(x: tx + flank * (1 - BubbleMetrics.flankSettle), y: t))
        }
        p.addLine(to: CGPoint(x: r - rr, y: t))
        p.addCurve(to: CGPoint(x: r, y: t + rr), control1: CGPoint(x: r - rr + h, y: t), control2: CGPoint(x: r, y: t + rr - h))
        p.addLine(to: CGPoint(x: r, y: b - rr))
        p.addCurve(to: CGPoint(x: r - rr, y: b), control1: CGPoint(x: r, y: b - rr + h), control2: CGPoint(x: r - rr + h, y: b))
        p.addLine(to: CGPoint(x: l + rr, y: b))
        p.addCurve(to: CGPoint(x: l, y: b - rr), control1: CGPoint(x: l + rr - h, y: b), control2: CGPoint(x: l, y: b - rr + h))
        p.closeSubpath()
        return p
    }
}

/// The last news key the bubble spoke, per account scope, and its idle
/// reminder's turn (Android `yoin_ui_hints` memory_bubble_seen:/recall:).
@MainActor
final class MemoryBubbleSeenStore {
    static let shared = MemoryBubbleSeenStore(defaults: .standard)
    private let defaults: UserDefaults

    init(defaults: UserDefaults) { self.defaults = defaults }

    func lastSeen(_ scope: String) -> String? { defaults.string(forKey: "memoryBubbleSeen:\(scope)") }
    func markSeen(_ scope: String, _ key: String) { defaults.set(key, forKey: "memoryBubbleSeen:\(scope)") }

    /// 0, 1, 2… one more each time it is asked.
    func nextRecallTurn(_ scope: String) -> Int {
        let k = "memoryBubbleRecall:\(scope)"
        let turn = defaults.integer(forKey: k)
        defaults.set(turn == Int.max ? 0 : turn + 1, forKey: k)
        return turn
    }
}
