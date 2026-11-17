import SwiftUI
import UIKit
import UIKit.UIGestureRecognizerSubclass
import YoinShared
import os

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
        case .lyrics: String(localized: "Lyrics")
        case .about: String(localized: "About")
        case .note: String(localized: "Note")
        }
    }
}

extension Yoin {
    /// Android YoinMotion.stageSettleSpring (YoinTokens stageSettle: damping
    /// 0.85, stiffness 700): the ONE spring every stage reshape rides —
    /// compact ⇄ focus ⇄ expanded, the cover's dock, the rating's retreat,
    /// the slots and their fades all move in that one transaction, so they
    /// read as one progress. Fast and near-critical: no overshoot for the
    /// cover flight to fight.
    @MainActor static var stageSettle: Animation {
        let s = Tokens.motion.stageSettle
        let k = s.stiffness
        return Animation.interpolatingSpring(mass: 1, stiffness: k, damping: s.damping * 2 * k.squareRoot())
            .speed(LaunchConfig.animationSpeed)
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
    /// The Lyrics page's four tools: Android ExpandedAccessoryHeight (52 pt keys + 16).
    static let lyricTools: CGFloat = 68
}

/// Android NowPlayingBudget (phone), run from the shared Kotlin
/// (`resolveStageColumnBudget`, YoinShared): nominal → minimum slot heights,
/// lower gaps squeezed first, then upper; then the inline hero, then the
/// one-line lyric. The content minimums are Android's at font scale 1, from
/// YoinTokens. `focus` is the shared Immersive pose, nil where the cover
/// can't grow by max(40 pt, 12 %) (then the cover is a plain image).
struct NowPlayingBudget: Sendable {
    struct Pose: Equatable, Sendable {
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
    /// The column's width: the width given, or (the enlarged phone) the
    /// cover, its rating column and the 24 insets when the cover is height-bound.
    var stageWidth: CGFloat

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

    private struct CacheKey: Hashable, Sendable {
        var width: Float
        var height: Float
        var forceShort: Bool
        var shortHeight: Double
        var enlargedColumn: Float?
        var enlargedRating: Float?
        var enlargedControl: Float?
    }

    private struct CachedValue: Sendable {
        var rest: Pose
        var focus: Pose?
        var inlineHero: Bool
        var stageWidth: CGFloat
    }

    private static let cache = OSAllocatedUnfairLock<[CacheKey: CachedValue]>(initialState: [:])

    /// `height` = the column's height: from below the status bar to
    /// max(home indicator, 16) above the bottom.
    /// `enlarged`: iPad Full in a Medium window (Android NowPlayingEnlargedSpec).
    init(width: CGFloat, height: CGFloat, forceShort: Bool, enlarged: EnlargedSpec? = nil) {
        var short = Self.referenceShortHeight
        #if DEBUG
        // `-npShortHeight 600`: a shorter forced column (16:9, the Fold's
        // outer screen), where the regular focus isn't offered.
        let override = UserDefaults.standard.double(forKey: "npShortHeight")
        if override > 0 { short = override }
        #endif
        let key = CacheKey(
            width: Float(width),
            height: Float(height),
            forceShort: forceShort,
            shortHeight: forceShort ? short : 0,
            enlargedColumn: enlarged?.columnWidth,
            enlargedRating: enlarged?.ratingColumn,
            enlargedControl: enlarged?.controlSize
        )
        if let hit = Self.cache.withLock({ $0[key] }) {
            inlineHero = hit.inlineHero
            stageWidth = hit.stageWidth
            rest = hit.rest
            focus = hit.focus
            return
        }
        let resolved = forceShort ? min(height, short) : height
        let budget = NowPlayingBudgetKt.resolveStageColumnBudget(
            width: Float(width), height: Float(resolved), metrics: Self.metrics, enlarged: enlarged, previous: nil)
        inlineHero = budget.heroOneLine
        stageWidth = CGFloat(budget.stageWidth)
        rest = Self.pose(budget.rest, height: height)
        focus = budget.focus.map { Self.pose($0, height: height) }
        let value = CachedValue(rest: rest, focus: focus, inlineHero: inlineHero, stageWidth: stageWidth)
        Self.cache.withLock {
            if $0.count >= 16 { $0.removeAll(keepingCapacity: true) }
            $0[key] = value
        }
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

    /// Android short focus (NowPlayingScreen.kt, owner 2026-10-30): where the
    /// regular focus can't grow the cover, a tap still focuses it. The
    /// rating column, heart and big title fold away, PREVIOUS rises into the
    /// top transport row and the progress bar takes its own 36 pt row; the
    /// freed height goes to the cover, centred. The current lyric line stays.
    struct ShortFocus: Equatable {
        var cover: CGFloat
        var transport: CGFloat
        /// What the column has left once the cover is capped by the width.
        var slack: CGFloat
    }

    /// Android ShortFocusProgressRowHeight.
    static let shortFocusProgressRow: CGFloat = 36
    /// Android ShortFocusMinGrowth: offered only where the cover grows by this.
    static let shortFocusMinGrowth: CGFloat = 24

    /// Android shortFocusControlsHeight: PAUSE's row, the gap, the bar's row.
    static var shortFocusTransport: CGFloat {
        Tokens.np.CONTROL_SIZE.cg + Tokens.np.CONTROL_ROW_GAP.cg + shortFocusProgressRow
    }

    /// nil where the regular focus is offered or the cover wouldn't grow by
    /// 24 pt. `columnWidth` = the stage minus its side padding.
    func shortFocus(columnWidth: CGFloat) -> ShortFocus? {
        guard focus == nil, rest.cover > 0 else { return nil }
        let transport = Self.shortFocusTransport
        let freed = max(0, rest.hero + rest.transport - transport)
        let cover = min(rest.cover + freed, columnWidth)
        guard cover - rest.cover >= Self.shortFocusMinGrowth else { return nil }
        return ShortFocus(cover: cover, transport: transport,
                          slack: rest.slack + freed - (cover - rest.cover))
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
        let r1 = size * 0.65
        let end1 = r1 + 70 * 2.4
        let r2 = size * 0.4
        let end2 = r2 + 60 * 2.4
        Color.clear
            .frame(width: size, height: size)
            .overlay {
                if size > 0 {
                    Circle()
                        .fill(RadialGradient(
                            stops: NowPlayingAmbient.diskGlowStops(
                                color: scheme.primaryContainer, opacity: 1, diskRadius: r1, blur: 70
                            ),
                            center: .center, startRadius: 0, endRadius: end1
                        ))
                        .frame(width: end1 * 2, height: end1 * 2)
                        .offset(x: -size * 0.08, y: size * 0.1)
                    Circle()
                        .fill(RadialGradient(
                            stops: NowPlayingAmbient.diskGlowStops(
                                color: scheme.tertiaryContainer, opacity: 0.6, diskRadius: r2, blur: 60
                            ),
                            center: .center, startRadius: 0, endRadius: end2
                        ))
                        .frame(width: end2 * 2, height: end2 * 2)
                        .offset(x: size * 0.42, y: size * 0.55)
                }
            }
            .opacity(on ? 0.8 : 0)
            .scaleEffect(on ? 1 : 0.85)
            .animation(Yoin.effects, value: on)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }
}

/// Android OneLineLyricPreview's line: bodyLarge rounded SemiBold in primary.
/// A new line lands (oneLineLyricLanding): it grows from 94 % at its leading
/// edge and rises a sixth of its height as it fades in, on the spatial
/// spring; the old line settles back to 94 % and fades out fast. Keyed by the
/// line, so a repeated line ("oh, oh") still reads as the lyric moving on.
/// Nothing scrolls, so no neighbouring line ever passes through the row.
struct OneLineLyricLine: View, Equatable {
    var text: String
    var primary: Color
    /// The line's index (-1 before the first): with the text, what makes a change.
    var key: Int = 0

    /// A sixth of the 32 pt row (Android slideInVertically { it / 6 }).
    static let rise: CGFloat = 32 / 6

    var body: some View {
        ZStack(alignment: .leading) {
            Text(text)
                .font(YoinFont.rounded(16, semibold: true))
                .tracking(0)
                .foregroundStyle(primary)
                .lineLimit(1)
                .padding(.vertical, 4)
                .frame(maxWidth: .infinity, alignment: .leading)
                .id("\(key)\u{0}\(text)")
                .transition(.asymmetric(
                    insertion: .scale(scale: 0.94, anchor: .leading)
                        .combined(with: .offset(y: Self.rise))
                        .combined(with: .opacity.animation(Yoin.effects)),
                    removal: .scale(scale: 0.94, anchor: .leading)
                        .combined(with: .opacity.animation(Yoin.spatialFast))))
        }
        .animation(Yoin.spatial, value: "\(key)\u{0}\(text)")
    }
}

/// Reports every touch that lands in the window, without taking part:
/// a recognizer that fails at once (never cancels or delays a touch), so
/// buttons, scrolls and drags underneath work exactly as before.
struct TouchWatcher: UIViewRepresentable {
    var onTouch: () -> Void
    /// Where the finger went down, in the window (SwiftUI's global space): Now Playing's lights
    /// find the lyric line a seek came from (Android recordPointerDown).
    var onTouchAt: (CGPoint) -> Void = { _ in }

    func makeUIView(context: Context) -> TouchWatcherView { TouchWatcherView() }
    func updateUIView(_ view: TouchWatcherView, context: Context) {
        view.onTouch = onTouch
        view.onTouchAt = onTouchAt
    }

    final class TouchWatcherView: UIView {
        var onTouch: () -> Void = {}
        var onTouchAt: (CGPoint) -> Void = { _ in }
        private var recognizer: Watcher?

        override init(frame: CGRect) {
            super.init(frame: frame)
            isUserInteractionEnabled = false
        }

        required init?(coder: NSCoder) { fatalError() }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            if let r = recognizer { r.view?.removeGestureRecognizer(r) }
            recognizer = nil
            guard let window else { return }
            let r = Watcher { [weak self] at in
                self?.onTouch()
                if let at { self?.onTouchAt(at) }
            }
            window.addGestureRecognizer(r)
            recognizer = r
        }
    }

    final class Watcher: UIGestureRecognizer, UIGestureRecognizerDelegate {
        let fire: (CGPoint?) -> Void
        init(fire: @escaping (CGPoint?) -> Void) {
            self.fire = fire
            super.init(target: nil, action: nil)
            cancelsTouchesInView = false
            delaysTouchesBegan = false
            delaysTouchesEnded = false
            delegate = self
        }

        override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
            fire(touches.first.map { $0.location(in: nil) })
            state = .failed
        }

        func gestureRecognizer(_ g: UIGestureRecognizer,
                               shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }
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
        // The case's own name: the title is in the app's language (`-npTab about` in Chinese too).
        return NowPlayingDetailPage.allCases.first { "\($0)" == name.lowercased() }
    }

    static var focusDemo: Bool { UserDefaults.standard.bool(forKey: "npFocusDemo") }

    /// `-npShortFocus YES`: Android's short focus (where the regular focus
    /// isn't offered, e.g. `-screen np-short`).
    static var shortFocus: Bool { UserDefaults.standard.bool(forKey: "npShortFocus") }

    static var stage: NowPlayingStage? {
        if UserDefaults.standard.bool(forKey: "npFocus") { return .focus }
        if UserDefaults.standard.string(forKey: "npTab")?.hasSuffix("-full") == true { return .expanded }
        return nil
    }
}
#endif
