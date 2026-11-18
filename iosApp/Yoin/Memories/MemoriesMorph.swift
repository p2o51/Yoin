import SwiftUI

/*
 * The card ⇄ diary shared-element morph (Android showcase/MemoriesMorph,
 * twostate4 v4.1). p (MemoriesDiary) is the only driver. Every element is
 * laid out complete in both states and only transformed here (offset,
 * scale, alpha, a corner): the card and the diary are never resized or cut
 * while p moves.
 *
 *  · the cover shrinks and flies into the bar's 40 pt slot (size leads
 *    position), 8 → 4 corners, handing over by alpha to the bar's own cover
 *    between fp .62 and .9;
 *  · the title holds until p .2, then flies to the diary title (crossfading its
 *    twin over .42–.78); the 96 emblem travels with it to the 48 right of the
 *    diary title (hand-over .6–.95);
 *  · the album row lifts 36 and fades by p .32; the excerpt and the buttons drop 56 and fade;
 *  · the diary's blocks rise, staggered, behind the title.
 *
 * Frozen collapse: closing a diary whose title has scrolled under the bar
 * never rewinds the text. The text sinks and fades in place first, the cover
 * waits in the bar (fp = p / .55), the card's title and emblem fade in where
 * they live; the scroll returns to 0 unseen at p < .05.
 *
 * Reduce Motion: the same p, alpha only (the card gone by p .5, the diary from there).
 */

/// The morph's choreography windows (they time a flight against p; they are not back thresholds).
enum MemoriesMorphTokens {
    static let titleHold: CGFloat = 0.2
    static let titleSwapFrom: CGFloat = 0.42
    static let titleSwapTo: CGFloat = 0.78
    /// Fallback A: the album-name title flies to the bar's album name and is gone by here.
    static let albumTitleFadeFrom: CGFloat = 0.36
    static let albumTitleFadeTo: CGFloat = 0.72
    static let emblemSwapFrom: CGFloat = 0.6
    static let emblemSwapTo: CGFloat = 0.95
    static let coverSwapFrom: CGFloat = 0.62
    static let coverSwapTo: CGFloat = 0.9
    static let coverFloorFrom: CGFloat = 0.75
    static let coverCornerCard: CGFloat = 8
    static let coverCornerBar: CGFloat = 4
    static let albumRowLift: CGFloat = 36
    static let albumRowFadeTo: CGFloat = 0.32
    static let teaserDrop: CGFloat = 56
    static let teaserFadeTo: CGFloat = 0.3
    static let blockFadeFrom: CGFloat = 0.26
    static let blockFadeStep: CGFloat = 0.04
    static let blockFadeTo: CGFloat = 0.92
    static let blockStagger: CGFloat = 28
    static let blockMaxIndex = 6
    static let blockLagExtra: CGFloat = 36
    static let frozenCoverSpan: CGFloat = 0.55
    static let frozenTextFadeFrom: CGFloat = 0.55
    static let frozenTextFadeStep: CGFloat = 0.02
    static let frozenTextFadeTo: CGFloat = 0.95
    static let frozenCardFadeFrom: CGFloat = 0.3
    static let frozenCardFadeTo: CGFloat = 0.55
    static let frozenResetBelow: CGFloat = 0.05
    /// The bar's slot B (album name + ⌄) arrives.
    /// Slot A lifts this far and is gone by this p as the diary opens.
    static let slotALift: CGFloat = 6
    static let slotAFadeTo: CGFloat = 0.45
    static let slotBFadeFrom: CGFloat = 0.55
    static let slotBFadeTo: CGFloat = 0.92
    static let slotBDrop: CGFloat = 10
    /// The Home pill narrows 88 → 36 (its label fades) over the cover's flight.
    static let pillNarrowTo: CGFloat = 0.6
    static let pillLabelFadeTo: CGFloat = 0.4
    /// Reduce Motion: the card is gone by here and the diary starts here.
    static let reducedSwap: CGFloat = 0.5
}

// smoothstep(a, b, x), the prototype's `ss`, is HomeMemories.swift's.

private func lerp(_ a: CGFloat, _ b: CGFloat, _ t: CGFloat) -> CGFloat { a + (b - a) * t }
private func ratio(_ a: CGFloat, _ b: CGFloat) -> CGFloat { b > 0 ? a / b : 1 }

/// A layer's transform: offset, uniform scale (from its top centre for text,
/// its centre otherwise) and alpha.
struct MorphPose: Equatable {
    var dx: CGFloat = 0
    var dy: CGFloat = 0
    var scale: CGFloat = 1
    var alpha: CGFloat = 1
    var corner: CGFloat = 0
}

/// A line of text as the morph reads it: centre x, top y, font size (page pt).
struct TextAnchor: Equatable {
    var cx: CGFloat
    var y: CGFloat
    var font: CGFloat
}

enum MemoriesMorph {
    /// How far the title (and the emblem) has travelled: held until p .2; never while frozen.
    static func titleTravel(_ p: CGFloat, frozen: Bool) -> CGFloat {
        frozen ? 0 : min(max((p - MemoriesMorphTokens.titleHold) / (1 - MemoriesMorphTokens.titleHold), 0), 1)
    }

    /// The cover's flight progress fp: p, or, frozen, p / .55 (it waits in the bar while the text sinks).
    static func coverProgress(_ p: CGFloat, frozen: Bool) -> CGFloat {
        frozen ? min(max(p / MemoriesMorphTokens.frozenCoverSpan, 0), 1) : min(max(p, 0), 1)
    }

    /// The bar cover's alpha at fp: 0 below .62, all of it from 1 (the page's cover is gone exactly there).
    static func barCoverShown(_ fp: CGFloat) -> CGFloat {
        max(smoothstep(MemoriesMorphTokens.coverFloorFrom, 1, fp),
            smoothstep(MemoriesMorphTokens.coverSwapFrom, MemoriesMorphTokens.coverSwapTo, fp))
    }

    /// The flying cover at fp: its size leads its position (1 − (1 − fp)²) and its corner goes card → bar.
    static func coverPose(fp: CGFloat, coverCenter: CGPoint, barCenter: CGPoint, cover: CGFloat, barCover: CGFloat,
                          cardCorner: CGFloat = MemoriesMorphTokens.coverCornerCard,
                          barCorner: CGFloat = MemoriesMorphTokens.coverCornerBar) -> MorphPose {
        let lead = 1 - (1 - fp) * (1 - fp)
        let scale = lerp(1, cover > 0 ? barCover / cover : 1, lead)
        // The corner as shown: the layer is scaled, so its own radius is the shown one ÷ scale.
        let shown = lerp(cardCorner, barCorner, fp)
        return MorphPose(dx: (barCenter.x - coverCenter.x) * fp, dy: (barCenter.y - coverCenter.y) * fp,
                         scale: scale, alpha: 1 - smoothstep(MemoriesMorphTokens.coverSwapFrom, 1, fp),
                         corner: scale > 0 ? shown / scale : shown)
    }

    /// Prototype `pair`: both copies travel one path (centre x, top y, scaled
    /// from the top centre) and hand over by alpha inside [from]…[to] of t.
    static func textPair(card: TextAnchor, diary: TextAnchor, t: CGFloat,
                         from: CGFloat = MemoriesMorphTokens.titleSwapFrom,
                         to: CGFloat = MemoriesMorphTokens.titleSwapTo) -> (card: MorphPose, diary: MorphPose) {
        let cx = lerp(card.cx, diary.cx, t), y = lerp(card.y, diary.y, t)
        let k = smoothstep(from, to, t)
        return (MorphPose(dx: cx - card.cx, dy: y - card.y, scale: lerp(1, ratio(diary.font, card.font), t), alpha: 1 - k),
                MorphPose(dx: cx - diary.cx, dy: y - diary.y, scale: lerp(ratio(card.font, diary.font), 1, t), alpha: k))
    }

    /// The 96 and the 48 on one flight ([te] = the title's travel): each relative to its own rest.
    static func emblemPose(te: CGFloat, sealCenter: CGPoint, demCenter: CGPoint, seal: CGFloat,
                           dem: CGFloat) -> (seal: MorphPose, dem: MorphPose) {
        let s = lerp(1, ratio(dem, seal), te)
        let ex = (demCenter.x - sealCenter.x) * te, ey = (demCenter.y - sealCenter.y) * te
        let k = smoothstep(MemoriesMorphTokens.emblemSwapFrom, MemoriesMorphTokens.emblemSwapTo, te)
        let landed = te >= 1
        return (MorphPose(dx: ex, dy: ey, scale: s, alpha: 1 - k),
                MorphPose(dx: landed ? 0 : ex + sealCenter.x - demCenter.x,
                          dy: landed ? 0 : ey + sealCenter.y - demCenter.y,
                          scale: landed ? 1 : ratio(seal * s, dem), alpha: k))
    }

    /// A diary block's rise: (1 − p)(lag + 28k); none while frozen.
    static func blockOffset(_ p: CGFloat, k: Int, lag: CGFloat, frozen: Bool) -> CGFloat {
        if frozen || p >= 1 { return 0 }
        return (1 - min(max(p, 0), 1)) * (lag + CGFloat(min(k, MemoriesMorphTokens.blockMaxIndex)) * MemoriesMorphTokens.blockStagger)
    }

    static func blockAlpha(_ p: CGFloat, k: Int, frozen: Bool) -> CGFloat {
        let kk = CGFloat(min(k, MemoriesMorphTokens.blockMaxIndex))
        if frozen {
            return smoothstep(MemoriesMorphTokens.frozenTextFadeFrom + kk * MemoriesMorphTokens.frozenTextFadeStep,
                              MemoriesMorphTokens.frozenTextFadeTo, p)
        }
        return smoothstep(MemoriesMorphTokens.blockFadeFrom + kk * MemoriesMorphTokens.blockFadeStep,
                          MemoriesMorphTokens.blockFadeTo, p)
    }

    /// Frozen: the text sinks as one block from where it is, (1 − p)·lag.
    static func frozenSink(_ p: CGFloat, lag: CGFloat, frozen: Bool) -> CGFloat {
        frozen && p < 1 ? (1 - min(max(p, 0), 1)) * lag : 0
    }

    /// The blocks' lag: the title's remaining travel (÷ .8, it is held until .2) + 36.
    static func blockLag(cardTitleTop: CGFloat, diaryTop: CGFloat, cardTitleHeight: CGFloat? = nil) -> CGFloat {
        max(0, cardTitleTop - diaryTop) / (1 - MemoriesMorphTokens.titleHold) + MemoriesMorphTokens.blockLagExtra
            + (cardTitleHeight ?? 0)
    }

    static func reducedCardAlpha(_ p: CGFloat) -> CGFloat { 1 - smoothstep(0, MemoriesMorphTokens.reducedSwap, p) }
    static func reducedDiaryAlpha(_ p: CGFloat) -> CGFloat { smoothstep(MemoriesMorphTokens.reducedSwap, 1, p) }

    /// The album row: lifts 36 and fades by p .32.
    static func albumRow(_ p: CGFloat) -> MorphPose {
        let c = min(max(p, 0), 1)
        return MorphPose(dy: -MemoriesMorphTokens.albumRowLift * c,
                         alpha: 1 - smoothstep(0, MemoriesMorphTokens.albumRowFadeTo, c))
    }

    /// The teaser (excerpt, buttons): drops 56 and fades by p .3.
    static func teaser(_ p: CGFloat) -> MorphPose {
        let c = min(max(p, 0), 1)
        return MorphPose(dy: MemoriesMorphTokens.teaserDrop * c, alpha: 1 - smoothstep(0, MemoriesMorphTokens.teaserFadeTo, c))
    }
}
