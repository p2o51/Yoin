import CoreGraphics
import SwiftUI

/// iPad's Full Now Playing, Android's way (NowPlayingPresentation.kt): the
/// window's size picks the frame, never the device model.
/// - wide: width ≥ 840, height ≥ 480 and wider than tall — the two-column
///   player (option A, owner 2026-11-27: centred, cover 400–460).
/// - enlarged: any other iPad window — the phone column enlarged (Android
///   Enlarged on Medium, TabletPortraitNP). A portrait window ≥ 840 (13"
///   portrait, a tall Split View) is the enlarged poster too (option B2, owner
///   2026-11-27: two columns there squeezed the player into the top third).
enum NowPlayingPadFrame: Equatable {
    case wide, enlarged

    /// Android LayoutMode.Wide (≥ 840) with heightAtLeastMedium (≥ 480).
    static let wideMinWidth: CGFloat = 840
    static let mediumMinHeight: CGFloat = 480

    static func resolve(width: CGFloat, height: CGFloat) -> NowPlayingPadFrame {
        width >= wideMinWidth && height >= mediumMinHeight && width > height ? .wide : .enlarged
    }

    /// Android nowPlayingEnlargedColumnWidth (the window less 80 pt margins, from 360), capped at the iPad's
    /// poster width (option B2: a centred column with a 440–480 cover beside its rating column; Android's 640
    /// gave 13" and 11" portrait a 523 cover). Every portrait iPad gets the same poster (mini: 744 − 160 = 584).
    static let posterMaxWidth: CGFloat = 584
    static func enlargedColumnWidth(windowWidth: CGFloat) -> CGFloat {
        min(max(windowWidth - 80 * 2, 360), posterMaxWidth)
    }

    /// Android nowPlayingEnlargedSpec: the rating column grows with the
    /// column (phone 400 → 56, tablet 640 → 104).
    static func enlargedRatingColumn(column: CGFloat) -> CGFloat {
        min(max(56 + 80 * (column / 400 - 1), 56), 104)
    }

    /// Android nowPlayingEnlargedSpec: the controls grow to 60 once the
    /// column is 1.4 phone columns (560), else the phone's 56.
    static func enlargedControlSize(column: CGFloat) -> CGFloat {
        column / 400 >= 1.4 ? 60 : 56
    }
}

/// iPad's two-column Full Now Playing (option A, owner 2026-11-27, after the iPad audit; it replaced Android
/// DualPane's 312 stack, which left a 13" window's cover at 23 % of the width and a ~500 pt hole under it).
/// The content is at most 1220 wide, centred; the columns share it 44 : 56. LEFT: the cover (as large as the
/// column and the height allow, 460 at most) with the 56 rating column and the heart at its right; the title
/// (artist, format, two-line title) and the transport right under it; the whole block centred in the height.
/// RIGHT: the expanded page (its tabs in the top bar), the lyrics larger (28) at a comfortable measure (≤ 600),
/// only the page's own tools at its foot.
///
/// Ladder for a short window: gaps → two-line title → one-line → cover shrinks → title into the top bar.
/// Focus (the cover tapped): the rating column retreats and the cover grows (≤ 1.5×, the right column keeps
/// 320) — offered only where it grows by max(40, 12 %). The title stays as it rests.
enum DualPaneBudget {
    /// How the identity block is set. Decided per window, never per song.
    enum TitleForm: Equatable { case twoLines, oneLine, topBar }

    /// Content minimums of the left block.
    struct Metrics: Equatable {
        var transport: CGFloat
        /// Artist line + 2 + one / two title lines.
        var titleOneLine: CGFloat
        var titleTwoLines: CGFloat
        /// The bottom system inset the content clears.
        var navBottom: CGFloat
    }

    struct Pose: Equatable {
        /// The left block's width: the cover with the rating column at its side (the transport's width).
        var slot: CGFloat
        var cover: CGFloat
        /// The rating column beside the cover with its gap: 0 when it retreats (focus).
        var ratingSide: CGFloat
        var coverToTitle: CGFloat
        var titleToTransport: CGFloat
        /// 0 when the title folded into the top bar.
        var title: CGFloat
        /// The left column (≥ the block; the block is centred in it) and the right one beside it.
        var leftColumn: CGFloat
        var rightColumn: CGFloat
        /// The block's height: cover, title and transport with their gaps (centred in the row).
        var block: CGFloat
    }

    struct Result: Equatable {
        /// Outer frame: top padding, the gap under the top bar, bottom padding.
        var frameTop: CGFloat
        var barGap: CGFloat
        var frameBottom: CGFloat
        /// The centred content's width and its inset from the frame padding on each side.
        var contentWidth: CGFloat
        var contentInset: CGFloat
        /// The row under the top bar both columns share.
        var rowHeight: CGFloat
        var titleForm: TitleForm
        var rest: Pose
        var focus: Pose?
        var focusOffered: Bool { focus != nil }
    }

    /// The centred content's widest, and the left column's share of it (option A).
    static let contentMaxWidth: CGFloat = 1220
    static let leftShare: CGFloat = 0.44
    /// The cover's largest and smallest at rest.
    static let coverMax: CGFloat = 460
    static let coverMin: CGFloat = 168
    static let coverFloor: CGFloat = 140
    /// Below this a two-line title gives way to one line.
    static let twoLineCoverMin: CGFloat = 312
    /// DualPaneRatingSide: the standard rating column (56) and its 12 gap.
    static let ratingSide: CGFloat = 68
    static let ratingColumn: CGFloat = 56
    static let frameTop: CGFloat = 16
    static let barGap: CGFloat = 8
    static let frameBottom: CGFloat = 16
    static let coverToTitleNominal: CGFloat = 20, coverToTitleMin: CGFloat = 12
    static let titleToTransportNominal: CGFloat = 12, titleToTransportMin: CGFloat = 8
    /// WideColumnGap / WideFramePadding / WideTopBarHeight / WideAccessoryHeight.
    static let columnGap: CGFloat = 24
    static let framePadding: CGFloat = 24
    static let topBarHeight: CGFloat = 48
    static let accessoryHeight: CGFloat = 68
    static let rightColumnMin: CGFloat = 320
    /// The right column's lyrics (option A: 26–30 on a tablet, the phone's 24 read as a footnote) and their
    /// translations, at a measure of at most `lyricMeasure`.
    static let lyricSize: CGFloat = 28
    static let lyricTranslationSize: CGFloat = 18
    static let lyricMeasure: CGFloat = 600
    /// CoverFocusMaxScale, CoverFocusMinGrowth, CoverFocusMinGrowthFraction.
    static let focusMaxScale: CGFloat = 1.5
    static let focusMinGrowth: CGFloat = 40
    static let focusMinGrowthFraction: CGFloat = 0.12
    /// PlaybackLabelsInlineMinWidth: below it the time labels drop under the wave.
    static let labelsInlineMinWidth: CGFloat = 360
    /// FocusIdleMs: focus at ease after this long without a touch (Android PR #185).
    static let focusIdleSeconds: Double = 4

    /// The transport (two control rows, the time labels under the wave when the block is narrow), the artist
    /// (titleMedium) + 2 + the title's headlineMedium lines, at font scale 1.
    static func metrics(controlSize: CGFloat, rowGap: CGFloat, labelLine: CGFloat, artistLine: CGFloat,
                        titleLine: CGFloat, navBottom: CGFloat, slot: CGFloat = labelsInlineMinWidth) -> Metrics {
        let transport = controlSize * 2 + rowGap + (slot >= labelsInlineMinWidth ? 0 : labelLine)
        let artist = artistLine + 2
        return Metrics(transport: transport, titleOneLine: artist + titleLine,
                       titleTwoLines: artist + titleLine * 2, navBottom: navBottom)
    }

    /// Android coverFocusOffered: focus is worth a tap only when the cover
    /// grows by max(40, 12 %).
    static func focusOffered(rest: CGFloat, focus: CGFloat) -> Bool {
        focus - rest >= max(focusMinGrowth, rest * focusMinGrowthFraction)
    }

    /// `rowWidth` = the width the page gives the content (the window less its side insets and the 24 frame
    /// padding each side); `contentHeight` = the window less its top inset; `barHeight` = the top bar.
    static func resolve(rowWidth: CGFloat, contentHeight: CGFloat, barHeight: CGFloat = topBarHeight,
                        metrics: Metrics) -> Result {
        let content = max(0, min(rowWidth, contentMaxWidth))
        let inset = max(0, (rowWidth - content) / 2)
        let leftColumn = ((content - columnGap) * leftShare).rounded(.down)
        let rightColumn = max(0, content - columnGap - leftColumn)
        let row = max(0, contentHeight - metrics.navBottom - frameTop - barGap - frameBottom - barHeight)
        let widthCap = min(leftColumn - ratingSide, coverMax)

        /// The cover the height leaves beside [title] with the given gaps.
        func coverFor(_ title: CGFloat, tight: Bool) -> CGFloat {
            let gaps = title > 0
                ? (tight ? coverToTitleMin + titleToTransportMin : coverToTitleNominal + titleToTransportNominal)
                : (tight ? coverToTitleMin : coverToTitleNominal)
            return row - title - metrics.transport - gaps
        }

        func budgetFor(_ form: TitleForm, title: CGFloat) -> Result {
            let roomy = coverFor(title, tight: false) >= min(widthCap, coverMin)
            let coverToTitle = title > 0 ? (roomy ? coverToTitleNominal : coverToTitleMin) : 0
            let titleToTransport = roomy ? (title > 0 ? titleToTransportNominal : coverToTitleNominal)
                                         : (title > 0 ? titleToTransportMin : coverToTitleMin)
            let restCover = max(min(coverFor(title, tight: !roomy), widthCap), coverFloor).rounded(.down)
            let restSlot = restCover + ratingSide
            let block = restCover + coverToTitle + title + titleToTransport + metrics.transport
            let rest = Pose(slot: restSlot, cover: restCover, ratingSide: ratingSide, coverToTitle: coverToTitle,
                            titleToTransport: titleToTransport, title: title, leftColumn: leftColumn,
                            rightColumn: rightColumn, block: block)
            // Focus: the rating retreats, the cover takes what the height and the right column's minimum leave.
            let focusHeight = row - title - metrics.transport - (title > 0 ? coverToTitleMin : 0) - titleToTransport
            let focusCover = min(restCover * focusMaxScale, content - columnGap - rightColumnMin, focusHeight).rounded(.down)
            var focus: Pose?
            if focusOffered(rest: restCover, focus: focusCover) {
                let focusLeft = max(leftColumn, focusCover)
                focus = Pose(slot: max(restSlot, focusCover), cover: focusCover, ratingSide: 0,
                             coverToTitle: title > 0 ? coverToTitleMin : 0, titleToTransport: titleToTransport,
                             title: title, leftColumn: focusLeft, rightColumn: max(0, content - columnGap - focusLeft),
                             block: focusCover + (title > 0 ? coverToTitleMin : 0) + title + titleToTransport
                                 + metrics.transport)
            }
            return Result(frameTop: frameTop, barGap: barGap, frameBottom: frameBottom, contentWidth: content,
                          contentInset: inset, rowHeight: row, titleForm: form, rest: rest, focus: focus)
        }

        if coverFor(metrics.titleTwoLines, tight: true) >= min(twoLineCoverMin, widthCap) {
            return budgetFor(.twoLines, title: metrics.titleTwoLines)
        }
        if coverFor(metrics.titleOneLine, tight: true) >= coverMin {
            return budgetFor(.oneLine, title: metrics.titleOneLine)
        }
        return budgetFor(.topBar, title: 0)
    }

    // MARK: Gaps (Android NpGap / squeezeGaps)

    struct Gap: Equatable {
        var nominal: CGFloat
        var min: CGFloat
        init(_ nominal: CGFloat, _ min: CGFloat) { self.nominal = nominal; self.min = min }
        var slackValue: CGFloat { Swift.max(0, nominal - min) }
        /// 0 = nominal, 1 = fully compressed.
        func at(_ squeeze: CGFloat) -> CGFloat { nominal + (min - nominal) * Swift.min(Swift.max(squeeze, 0), 1) }
        static func slack(_ gaps: [Gap]) -> CGFloat { gaps.reduce(0) { $0 + $1.slackValue } }
    }

    struct Squeeze: Equatable {
        var lower: CGFloat
        var upper: CGFloat
        var unmet: CGFloat
    }

    /// Spend `deficit` from the LOWER gaps first, then the UPPER ones, each
    /// tier on one shared fraction.
    static func squeeze(_ deficit: CGFloat, lower: [Gap], upper: [Gap]) -> Squeeze {
        guard deficit > 0 else { return Squeeze(lower: 0, upper: 0, unmet: 0) }
        let lowerSlack = Gap.slack(lower), upperSlack = Gap.slack(upper)
        if deficit <= lowerSlack {
            return Squeeze(lower: lowerSlack > 0 ? deficit / lowerSlack : 0, upper: 0, unmet: 0)
        }
        let rest = deficit - lowerSlack
        if rest <= upperSlack {
            return Squeeze(lower: 1, upper: upperSlack > 0 ? rest / upperSlack : 0, unmet: 0)
        }
        return Squeeze(lower: 1, upper: 1, unmet: rest - upperSlack)
    }
}

/// The two columns' Lyrics page for the demo song (signed out, `-screen np`): its lines in the expanded list's
/// type at the two columns' size (DualPaneBudget.lyricSize), the playing one lit, a few lines above it.
struct WideDemoLyrics: View {
    var lines: [String]
    var active: Int
    var primary: Color
    var ink: Color

    var body: some View {
        let first = max(0, active - 2)
        let script = YoinCJKScript.detect(forLines: lines)
        VStack(alignment: .leading, spacing: 4) {
            ForEach(Array(lines.enumerated()).dropFirst(first), id: \.offset) { i, line in
                let lit = i == active
                let d = abs(i - active)
                Text(line)
                    .font(lit ? YoinFont.rounded(DualPaneBudget.lyricSize, semibold: true, script: script)
                          : .gsf(DualPaneBudget.lyricSize, .medium, script: script))
                    .foregroundStyle(lit ? primary : ink)
                    .opacity(lit ? 1 : d <= 1 ? 0.62 : d == 2 ? 0.5 : 0.4)
                    .scaleEffect(lit ? 1 : 0.96, anchor: .leading)
                    .padding(.vertical, 6)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(.top, 48)
        .mask(NowPlayingAboutView.fade(top: ExpandedLyricsList.fadeTop, bottom: ExpandedLyricsList.fadeBottom))
    }
}
