import CoreGraphics

/// iPad's Full Now Playing, Android's way (NowPlayingPresentation.kt): the
/// window's size picks the frame, never the device model.
/// - wide: width ≥ 840 and height ≥ 480 — the two-column player (DualPane,
///   TabletNP, Android PR #163).
/// - enlarged: any other iPad window — the phone column enlarged (Android
///   Enlarged on Medium, TabletPortraitNP).
enum NowPlayingPadFrame: Equatable {
    case wide, enlarged

    /// Android LayoutMode.Wide (≥ 840) with heightAtLeastMedium (≥ 480).
    static let wideMinWidth: CGFloat = 840
    static let mediumMinHeight: CGFloat = 480

    static func resolve(width: CGFloat, height: CGFloat) -> NowPlayingPadFrame {
        width >= wideMinWidth && height >= mediumMinHeight ? .wide : .enlarged
    }

    /// Android nowPlayingEnlargedColumnWidth: the window less 80 pt margins,
    /// 360…640 (tablet portrait 800 → 640).
    static func enlargedColumnWidth(windowWidth: CGFloat) -> CGFloat {
        min(max(windowWidth - 80 * 2, 360), 640)
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

/// Android NowPlayingBudget.kt's DualPane section (`resolveDualPaneBudget`,
/// owner 2026-11-06, prototype v10), ported as plain point math. The left
/// stack is the cover with the standard rating column at its right, the
/// transport under them and the title set at the bottom; its controls are
/// always measured at `stackWidth` (312), the cover never narrows them.
///
/// Ladder: gaps → two-line title → one-line → cover shrinks → title to the
/// top bar. Focus (the cover tapped): the rating column retreats, the gap
/// under the cover closes and the cover grows (≤ 1.5×, the lyrics keep 320)
/// — offered only where it grows by max(40, 12 %). The title stays as it rests.
enum DualPaneBudget {
    /// How the identity block is set. Decided per window, never per song.
    enum TitleForm: Equatable { case twoLines, oneLine, topBar }

    /// Content minimums of the left stack (measured at `stackWidth`).
    struct Metrics: Equatable {
        var transport: CGFloat
        /// Artist line + 2 + one / two title lines.
        var titleOneLine: CGFloat
        var titleTwoLines: CGFloat
        /// The bottom system inset the title clears.
        var navBottom: CGFloat
    }

    struct Pose: Equatable {
        /// The left stack's width: the cover with the rating column at its side.
        var slot: CGFloat
        var cover: CGFloat
        /// The rating column beside the cover with its gap: 0 when it retreats (focus).
        var ratingSide: CGFloat
        var coverToTransport: CGFloat
        /// The least room between the transport and the bottom-set title.
        var transportToTitle: CGFloat
        /// 0 when the title folded into the top bar.
        var title: CGFloat
    }

    struct Result: Equatable {
        /// Outer frame: top padding, the gap under the top bar, bottom padding.
        var frameTop: CGFloat
        var barGap: CGFloat
        var frameBottom: CGFloat
        var titleForm: TitleForm
        var rest: Pose
        var focus: Pose?
        var focusOffered: Bool { focus != nil }
    }

    // Android tokens (NowPlayingBudget.kt / NowPlayingScreen.kt).
    /// DualPaneStackWidth: TabletNP's 312 cover side.
    static let stackWidth: CGFloat = 312
    static let coverMin: CGFloat = 168
    static let coverFloor: CGFloat = 140
    /// DualPaneRatingSide: the standard rating column (56) and its 12 gap.
    static let ratingSide: CGFloat = 68
    static let ratingColumn: CGFloat = 56
    static let frameNominal: CGFloat = 16, frameMin: CGFloat = 8
    static let barGapNominal: CGFloat = 8, barGapMin: CGFloat = 4
    static let coverToTransportNominal: CGFloat = 12, coverToTransportMin: CGFloat = 8
    static let transportToTitleNominal: CGFloat = 16, transportToTitleMin: CGFloat = 8
    /// WideColumnGap / WideFramePadding / WideTopBarHeight / WideAccessoryHeight.
    static let columnGap: CGFloat = 24
    static let framePadding: CGFloat = 24
    static let topBarHeight: CGFloat = 48
    static let accessoryHeight: CGFloat = 68
    static let rightColumnMin: CGFloat = 320
    /// CoverFocusMaxScale, CoverFocusMinGrowth, CoverFocusMinGrowthFraction.
    static let focusMaxScale: CGFloat = 1.5
    static let focusMinGrowth: CGFloat = 40
    static let focusMinGrowthFraction: CGFloat = 0.12
    /// PlaybackLabelsInlineMinWidth: below it the time labels drop under the wave.
    static let labelsInlineMinWidth: CGFloat = 360
    /// FocusIdleMs: focus at ease after this long without a touch (Android PR #185).
    static let focusIdleSeconds: Double = 4

    /// Android rememberDualPaneMetrics at font scale 1: the transport measured
    /// at the stack's width, artist (titleMedium) + 2 + headlineMedium lines.
    static func metrics(controlSize: CGFloat, rowGap: CGFloat, labelLine: CGFloat, artistLine: CGFloat,
                        titleLine: CGFloat, navBottom: CGFloat) -> Metrics {
        let transport = controlSize * 2 + rowGap + (stackWidth >= labelsInlineMinWidth ? 0 : labelLine)
        let artist = artistLine + 2
        return Metrics(transport: transport, titleOneLine: artist + titleLine,
                       titleTwoLines: artist + titleLine * 2, navBottom: navBottom)
    }

    /// Android coverFocusOffered: focus is worth a tap only when the cover
    /// grows by max(40, 12 %).
    static func focusOffered(rest: CGFloat, focus: CGFloat) -> Bool {
        focus - rest >= max(focusMinGrowth, rest * focusMinGrowthFraction)
    }

    /// `rowWidth` = the width both columns share (window less side insets and
    /// the 24 frame padding each side); `contentHeight` = the window less its
    /// top inset; `barHeight` = the top bar.
    static func resolve(rowWidth: CGFloat, contentHeight: CGFloat, barHeight: CGFloat = topBarHeight,
                        metrics: Metrics) -> Result {
        let frameTop = Gap(frameNominal, frameMin)
        let barGap = Gap(barGapNominal, barGapMin)
        let frameBottom = Gap(frameNominal, frameMin)
        let coverToTransport = Gap(coverToTransportNominal, coverToTransportMin)
        let transportToTitle = Gap(transportToTitleNominal, transportToTitleMin)
        let lower = [transportToTitle, frameBottom]
        let upper = [frameTop, barGap, coverToTransport]
        let fixedContent = barHeight + metrics.transport + metrics.navBottom
        let gapsNominal = (lower + upper).reduce(0) { $0 + $1.nominal }
        let gapsSlack = Gap.slack(lower) + Gap.slack(upper)
        // The right column keeps its minimum beside the cover and its rating.
        let widthCap = rowWidth - columnGap - rightColumnMin - ratingSide

        func coverFor(_ title: CGFloat, squeezeAll: Bool) -> CGFloat {
            let gaps = squeezeAll ? gapsNominal - gapsSlack : gapsNominal
            return contentHeight - fixedContent - title - gaps
        }

        func budgetFor(_ form: TitleForm, title: CGFloat) -> Result {
            let restCover = max(min(coverFor(title, squeezeAll: true), stackWidth, widthCap), coverFloor)
            let restSqueeze = squeeze(max(0, fixedContent + title + gapsNominal + restCover - contentHeight),
                                      lower: lower, upper: upper)
            let restPose = Pose(slot: max(stackWidth, restCover + ratingSide), cover: restCover,
                                ratingSide: ratingSide,
                                coverToTransport: coverToTransport.at(restSqueeze.upper),
                                transportToTitle: transportToTitle.at(restSqueeze.lower), title: title)
            let focusHeight = contentHeight - barHeight - metrics.transport - metrics.navBottom
                - frameTop.at(restSqueeze.upper) - barGap.at(restSqueeze.upper) - frameBottom.at(restSqueeze.lower)
                - coverToTransportMin - restPose.transportToTitle - restPose.title
            let focusCover = min(restCover * focusMaxScale, rowWidth - columnGap - rightColumnMin, focusHeight)
            let focusPose: Pose? = focusOffered(rest: restCover, focus: focusCover)
                ? Pose(slot: max(stackWidth, focusCover), cover: focusCover, ratingSide: 0,
                       coverToTransport: coverToTransportMin, transportToTitle: restPose.transportToTitle,
                       title: restPose.title)
                : nil
            return Result(frameTop: frameTop.at(restSqueeze.upper), barGap: barGap.at(restSqueeze.upper),
                          frameBottom: frameBottom.at(restSqueeze.lower), titleForm: form,
                          rest: restPose, focus: focusPose)
        }

        // Two lines give way to one where only one line leaves the cover room to focus.
        let twoLines = coverFor(metrics.titleTwoLines, squeezeAll: true) >= stackWidth
            ? budgetFor(.twoLines, title: metrics.titleTwoLines) : nil
        let oneLine = coverFor(metrics.titleOneLine, squeezeAll: true) >= coverMin
            ? budgetFor(.oneLine, title: metrics.titleOneLine) : nil
        if let twoLines, twoLines.focusOffered || oneLine?.focusOffered != true { return twoLines }
        if let oneLine { return oneLine }
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
