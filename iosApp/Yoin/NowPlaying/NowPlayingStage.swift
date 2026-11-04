import SwiftUI
import YoinShared

/// Android `NowPlayingStageMode`: how Now Playing reshapes in place.
/// - compact: the resting player.
/// - expanded: the detail page (Lyrics / About / Note) takes the stage; the
///   cover docks into the top bar, the transport steps away.
/// - focus: Android's Immersive. Tap the cover: it grows, the rating and the
///   tabs retreat, the lyrics fold to the current line.
enum NowPlayingStage: Equatable {
    case compact, expanded, focus
}

/// Android `NowPlayingDetailPage`, in pager order.
enum NowPlayingDetailPage: Int, CaseIterable {
    case lyrics, about, note

    var title: String {
        switch self {
        case .lyrics: "Lyrics"
        case .about: "About"
        case .note: "Note"
        }
    }
}

/// Android NowPlayingScreen.kt's Expanded slot heights.
enum NowPlayingExpanded {
    static let topBar: CGFloat = 56
    static let coverGap: CGFloat = 8
    static let tabRow: CGFloat = 52
    static let tabGap: CGFloat = 12
    /// DockedCoverSlot: the docked cover's 48 pt slot beside the chevron.
    static let dockedSlot: CGFloat = 48
    static let dockedCover: CGFloat = 44
    /// The Note page's write bar: NoteWriteBarDefaults.IdleHeight + TopGap.
    static let writeBar: CGFloat = 56
    static let writeBarGap: CGFloat = 12
    /// Air under the hero when the page has no accessory (Lyrics / About on iOS).
    static let bareAccessory: CGFloat = 12
}

/// Android NowPlayingBudget (phone), run from the shared Kotlin
/// (`resolveStageColumnBudget`, YoinShared): nominal → minimum slot heights,
/// lower gaps squeezed first, then upper; then the inline hero, then the
/// one-line lyric. The content minimums are Android's at font scale 1, from
/// YoinTokens. `focus` is the shared Immersive pose, nil where the cover
/// can't grow by max(40 pt, 12 %) (then the cover is a plain image).
struct NowPlayingBudget {
    struct Pose: Equatable {
        var topBar: CGFloat
        var cover: CGFloat
        var coverGap: CGFloat
        var tabs: CGFloat
        var tabGap: CGFloat
        var transport: CGFloat
        var hero: CGFloat
        var pills: CGFloat
        /// The lyric window (list) or the one-line lyric's slot.
        var lyricWindow: CGFloat
        var oneLineLyric: Bool
        /// Focus only: the slot shows the current line instead of the list.
        var preview: Bool = false
        /// Height Android wouldn't have (forced short layout), or the one-line
        /// slot's air: given between the transport and the hero.
        var slack: CGFloat = 0
    }

    var rest: Pose
    var focus: Pose?
    var inlineHero: Bool

    // The resting pose's slots, as before.
    var topBar: CGFloat { rest.topBar }
    var cover: CGFloat { rest.cover }
    var coverGap: CGFloat { rest.coverGap }
    var tabs: CGFloat { rest.tabs }
    var tabGap: CGFloat { rest.tabGap }
    var transport: CGFloat { rest.transport }
    var hero: CGFloat { rest.hero }
    var pills: CGFloat { rest.pills }
    var lyricWindow: CGFloat { rest.lyricWindow }
    var oneLineLyric: Bool { rest.oneLineLyric }
    var slack: CGFloat { rest.slack }

    /// `-screen np-short`: the column of the ~700 dp Android phone the
    /// reference shots come from.
    static let referenceShortHeight: CGFloat = 700

    /// Android NowPlayingMetrics at font scale 1 (M3 line heights + YoinTokens).
    nonisolated(unsafe) static let metrics: StageColumnMetrics = {
        let t = Tokens.type, n = Tokens.np
        func f(_ v: Double) -> Float { Float(v) }
        return StageColumnMetrics(
            tabText: f(t.labelLarge.lineHeight),
            transport: f(n.CONTROL_SIZE * 2 + n.CONTROL_ROW_GAP),
            hero: f(t.titleMedium.lineHeight + n.HERO_LINE_GAP + t.displaySmall.lineHeight),
            heroOneLine: f(t.displaySmall.lineHeight),
            pills: f(n.PILL_HEIGHT),
            oneLineContent: f(t.bodyLarge.lineHeight + n.LYRIC_LINE_PADS),
            lyric: LyricWindowMetrics(activeLine: f(t.bodyLarge.lineHeight + n.LYRIC_LINE_PADS),
                                      inactiveLine: f(t.bodyMedium.lineHeight + n.LYRIC_LINE_PADS))
        )
    }()

    /// `height` = the column's height: from below the status bar to
    /// max(home indicator, 16) above the bottom.
    init(width: CGFloat, height: CGFloat, forceShort: Bool) {
        let resolved = forceShort ? min(height, Self.referenceShortHeight) : height
        let budget = NowPlayingBudgetKt.resolveStageColumnBudget(
            width: Float(width), height: Float(resolved), metrics: Self.metrics, enlarged: nil, previous: nil)
        inlineHero = budget.heroOneLine
        rest = Self.pose(budget.rest, height: height)
        focus = budget.focus.map { Self.pose($0, height: height) }
    }

    private static func pose(_ r: StageColumnPose, height: CGFloat) -> Pose {
        let oneLine = r.lyricFrame == LyricFrame.oneline
        let slot = CGFloat(r.lyricSlot)
        // The one-line lyric is one tappable row; what the slot has beyond
        // that is air, given to the slack (between transport and hero).
        let window = oneLine ? min(slot, Tokens.np.ONE_LINE_ROW_NOMINAL.cg) : slot
        return Pose(topBar: CGFloat(r.topBar), cover: CGFloat(r.cover), coverGap: CGFloat(r.coverGap),
                    tabs: CGFloat(r.tabs), tabGap: CGFloat(r.tabGap), transport: CGFloat(r.controls),
                    hero: CGFloat(r.hero), pills: CGFloat(r.accessory), lyricWindow: window,
                    oneLineLyric: oneLine, preview: r.lyricFrame == LyricFrame.preview,
                    slack: max(0, height - CGFloat(r.total)) + (slot - window))
    }
}

/// Expanded: the cover flies from its row into the top bar's docked slot
/// (Android CoverTransitionOverlay: the chevron's 48 + 4, then the 44 pt
/// cover centred in the 48 pt slot of the 56 pt bar). The row sits right
/// under the bar, so the flight is a scale about the top-leading corner and
/// an offset back up into the bar.
struct CoverDock: Equatable {
    var scale: CGFloat
    var offset: CGSize

    init(cover: CGFloat, topBar: CGFloat) {
        let e = NowPlayingExpanded.self
        scale = e.dockedCover / max(cover, 1)
        // Top bar: 4 leading, the 48 chevron button, 4 apart, then the slot.
        let slotX: CGFloat = 4 + 48 + 4
        offset = CGSize(width: slotX + (e.dockedSlot - e.dockedCover) / 2,
                        height: (e.topBar - e.dockedCover) / 2 - topBar)
    }
}

/// The cover's stage gestures and the dock flight. The tap is attached under
/// the transform, so it lands where the cover is drawn.
struct CoverStageEffect: ViewModifier {
    var dock: CoverDock?
    var tappable: Bool
    var label: String
    var onTap: () -> Void

    func body(content: Content) -> some View {
        content
            .contentShape(.rect)
            .gesture(TapGesture().onEnded(onTap), isEnabled: tappable)
            .accessibilityAddTraits(tappable ? .isButton : [])
            .accessibilityLabel(tappable ? label : "")
            .scaleEffect(dock?.scale ?? 1, anchor: .topLeading)
            .offset(dock?.offset ?? .zero)
    }
}

/// Focus's background field: the cover's colours bloom behind it while it
/// is focused, on the effects spring.
struct NowPlayingFocusField: View {
    var scheme: CoverScheme
    var size: CGFloat
    var on: Bool

    var body: some View {
        ZStack {
            Circle().fill(scheme.primaryContainer)
                .frame(width: size * 1.3, height: size * 1.3)
                .offset(x: -size * 0.08, y: size * 0.1)
                .blur(radius: 70)
            Circle().fill(scheme.tertiaryContainer.opacity(0.6))
                .frame(width: size * 0.8, height: size * 0.8)
                .offset(x: size * 0.42, y: size * 0.55)
                .blur(radius: 60)
        }
        .frame(width: size, height: size)
        .opacity(on ? 0.8 : 0)
        .scaleEffect(on ? 1 : 0.85)
        .animation(Yoin.effects, value: on)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// Android OneLineLyricPreview's line: bodyLarge rounded SemiBold in primary;
/// the next line lands from 94 % at its leading edge while the old one fades.
struct OneLineLyricLine: View, Equatable {
    var text: String
    var primary: Color

    var body: some View {
        ZStack(alignment: .leading) {
            Text(text)
                .font(YoinFont.rounded(16, semibold: true))
                .tracking(0)
                .foregroundStyle(primary)
                .lineLimit(1)
                .padding(.vertical, 4)
                .frame(maxWidth: .infinity, alignment: .leading)
                .id(text)
                .transition(.asymmetric(insertion: .scale(scale: 0.94, anchor: .leading).combined(with: .opacity),
                                        removal: .opacity))
        }
        .animation(Yoin.spatial, value: text)
    }
}

#if DEBUG
/// View-side launch aids for the stage: `-npTab lyrics|about|note` (or
/// 0 / 1 / 2) picks the tab; `about-full` / `note-full` open it expanded;
/// `-npFocus YES` focuses the cover.
enum NowPlayingStageLaunch {
    static var page: NowPlayingDetailPage? {
        guard let raw = UserDefaults.standard.string(forKey: "npTab") else { return nil }
        let name = raw.replacingOccurrences(of: "-full", with: "")
        if let i = Int(name) { return NowPlayingDetailPage(rawValue: i) }
        return NowPlayingDetailPage.allCases.first { $0.title.lowercased() == name.lowercased() }
    }

    static var focusDemo: Bool { UserDefaults.standard.bool(forKey: "npFocusDemo") }

    static var stage: NowPlayingStage? {
        if UserDefaults.standard.bool(forKey: "npFocus") { return .focus }
        if UserDefaults.standard.string(forKey: "npTab")?.hasSuffix("-full") == true { return .expanded }
        return nil
    }
}
#endif
