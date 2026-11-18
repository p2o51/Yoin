import SwiftUI
import UIKit

// How Home hosts Memories: Home's pull feeds q, and Home and the page read
// it, each in a modifier of its own (a frame of q re-renders these only).

/// Home behind Memories (Android rememberMemoriesHomeBehind): it waits at
/// scale 0.94 / alpha 0.5 under the open deck and comes up to 1 / 1 as
/// Memories retreats, scaled from its top centre. Reduce Motion keeps the
/// alpha only. Identity once Memories is closed.
struct MemoriesHomeBehind: ViewModifier {
    var presentation: MemoriesPresentation

    func body(content: Content) -> some View {
        let q = min(max(presentation.fraction, 0), 1)
        // A fade only: any scale on the feed's scroll view (scaleEffect or a visualEffect) stopped it drawing
        // under the bottom bar — cut off in a hard line above the bar, plain page below, from the pull's first
        // frame (51, build 7). The fade alone keeps the feed running to the screen's edge.
        content
            .opacity(q >= 1 ? 1 : Double(0.5 + 0.5 * q))
    }
}

/// The page's own pose (Android rememberMemoriesHostPose): −q·H with its
/// bottom corners rounding off the same q (memoriesDismissCorners), or a
/// fade in place under Reduce Motion (no corners).
///
/// The page runs over the whole window (HomeView pads it out past its own
/// frame by [bleed], and H is the window's height): it used to take Home's frame,
/// which stops at the status bar and above the bottom bar, so while it rode
/// in its lower edge sat just under Diary / Go to album and Home's feed
/// showed in full below it (51, build 9).
struct MemoriesPagePose: ViewModifier {
    var presentation: MemoriesPresentation
    /// How far the page runs past Home's frame (it lays out over the window).
    var bleed: EdgeInsets
    @Environment(\.accessibilityReduceMotion) private var reduced

    func body(content: Content) -> some View {
        // Hard at the open end (feedback: the spring's overshoot must not
        // pull the page down off its top and show Home above it).
        let q = max(presentation.fraction, 0)
        let radius = reduced ? 0 : MemoriesPresentation.cornerRadius(fraction: q, threshold: presentation.cornerThreshold,
                                                                    height: presentation.containerHeight)
        content
            .clipShape(MemoriesPageShape(bottomRadius: radius, bleed: bleed))
            .offset(y: reduced ? 0 : -q * presentation.containerHeight)
            .opacity(reduced ? Double(1 - min(max(q, 0), 1)) : 1)
    }
}

/// Home's frame grown by what the page runs past it, its bottom corners at
/// [bottomRadius] (square when 0).
private struct MemoriesPageShape: Shape {
    var bottomRadius: CGFloat
    var bleed: EdgeInsets

    func path(in rect: CGRect) -> Path {
        let full = CGRect(x: rect.minX - bleed.leading, y: rect.minY - bleed.top,
                          width: rect.width + bleed.leading + bleed.trailing,
                          height: rect.height + bleed.top + bleed.bottom)
        return UnevenRoundedRectangle(bottomLeadingRadius: bottomRadius, bottomTrailingRadius: bottomRadius,
                                      style: .continuous).path(in: full)
    }
}

/// The feed's scroll distance, read by the arrow only (a scroll frame must
/// not re-render Home).
@MainActor
@Observable
final class HomeScrollState {
    var scrolled: CGFloat = 0
}

/// Home's pull toward Memories, on the feed's own UIScrollView pan: at the
/// top, a pull down moves q 1:1 with the finger instead of overscrolling the
/// feed (the offset stays pinned at the top), and the release hands the
/// finger's velocity to q's spring. Once the pull goes back past where it
/// engaged, the feed scrolls again.
struct ScrollPullObserver: UIViewRepresentable {
    var presentation: MemoriesPresentation
    /// Pulling may engage (Home readable, not editing).
    var enabled: Bool
    var onEngage: () -> Void = {}

    func makeUIView(context: Context) -> ObserverView { ObserverView() }

    func updateUIView(_ v: ObserverView, context: Context) {
        v.presentation = presentation
        v.enabled = enabled
        v.onEngage = onEngage
    }

    final class ObserverView: UIView {
        var presentation: MemoriesPresentation?
        var enabled = true
        var onEngage: () -> Void = {}
        private weak var scrollView: UIScrollView?
        private var engaged = false
        private var engageAt: CGFloat = 0
        private var lastTranslation: CGFloat = 0

        override init(frame: CGRect) {
            super.init(frame: frame)
            isUserInteractionEnabled = false
        }

        required init?(coder: NSCoder) { fatalError() }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            guard window != nil, scrollView == nil else { return }
            var v: UIView? = superview
            while let cur = v, !(cur is UIScrollView) { v = cur.superview }
            guard let sv = v as? UIScrollView else { return }
            scrollView = sv
            sv.panGestureRecognizer.addTarget(self, action: #selector(pan(_:)))
        }

        private func atTop(_ sv: UIScrollView) -> Bool { sv.contentOffset.y <= -sv.adjustedContentInset.top + 0.5 }

        @objc private func pan(_ g: UIPanGestureRecognizer) {
            guard let sv = scrollView, let presentation else { return }
            // In the window's space, not the scroll view's: Home itself moves and shrinks with q
            // (MemoriesHomeBehind), so a finger measured in Home's own space read a pull that
            // changed q that moved Home that changed the pull, and the page shook while held.
            let ty = g.translation(in: nil).y
            switch g.state {
            case .began:
                engaged = false
                lastTranslation = ty
            case .changed:
                if !engaged, enabled, atTop(sv), ty > lastTranslation {
                    engaged = true
                    engageAt = lastTranslation
                    onEngage()
                }
                lastTranslation = ty
                guard engaged else { return }
                let pull = ty - engageAt
                if pull < 0 && presentation.fraction >= 1 {
                    // Back past where it engaged: the feed's again.
                    presentation.pull(0)
                    presentation.pullEnded(velocity: 0)
                    engaged = false
                    return
                }
                sv.contentOffset.y = -sv.adjustedContentInset.top
                presentation.pull(max(pull, 0))
            case .ended, .cancelled, .failed:
                guard engaged else { return }
                engaged = false
                sv.contentOffset.y = -sv.adjustedContentInset.top
                presentation.pullEnded(velocity: g.state == .ended ? g.velocity(in: nil).y : 0)
            default: break
            }
        }
    }
}

#if DEBUG
private extension Double {
    var nonZero: Double? { self == 0 ? nil : self }
}

/// Recording aids for the Memories path, driving the same controller a
/// finger does (QA can't inject touches on every simulator):
/// `-memoriesScripted YES`: Home's pull 55 % and let go (it opens), a swipe
/// to the next memory, a drag up past the commit line (it retreats); then the
/// arrow's open, and a short drag up that springs back; then a memory focus
/// on the third card and the ⌃ Home pill.
@MainActor
enum MemoriesScript {
    private static var started = false

    static func run(_ memories: MemoriesPresentation, focus: String?) async {
        await runShot(memories)
        await runDiary(memories, focus: focus)
        guard UserDefaults.standard.bool(forKey: "memoriesScripted"), !started else { return }
        started = true
        try? await Task.sleep(for: .seconds(2.5))
        // A pull past half way, let go at a walking pace: it opens.
        await finger(0.6) { memories.pull($0 * memories.containerHeight * 0.55) }
        try? await Task.sleep(for: .milliseconds(80))
        memories.pullEnded(velocity: 400)
        try? await Task.sleep(for: .seconds(2.5))
        MemoriesScript.swipe?(-1)
        try? await Task.sleep(for: .seconds(2))
        await finger(0.45) { memories.dismissDrag($0 * 180) }
        memories.dismissEnded(velocity: 300)
        try? await Task.sleep(for: .seconds(2))
        memories.open()
        try? await Task.sleep(for: .seconds(2))
        await finger(0.3) { memories.dismissDrag($0 * 60) }
        memories.dismissEnded(velocity: 0)
        try? await Task.sleep(for: .seconds(2))
        memories.close()
        try? await Task.sleep(for: .seconds(2))
        memories.open(focus: focus)
        try? await Task.sleep(for: .seconds(2.5))
        memories.close()
    }

    /// `-memoriesShot <album id>`: open on that card and stay (screenshots); with
    /// `-memoriesShotDiary YES`, its diary too. `-memoriesShotPlay YES` then plays the diary's first
    /// liner row as its tap does (pair with `-silentAudio YES`), and `-memoriesShotNowPlaying YES` /
    /// `-memoriesShotQueue YES` open Now Playing / its queue sheet over it (the Playing-from line).
    static func runShot(_ memories: MemoriesPresentation) async {
        let d = UserDefaults.standard
        guard let target = d.string(forKey: "memoriesShot"), !started else { return }
        started = true
        try? await Task.sleep(for: .seconds(4))
        memories.open(focus: target)
        guard d.bool(forKey: "memoriesShotDiary") else { return }
        try? await Task.sleep(for: .seconds(2.5))
        guard let deck, let card = current?() else { return }
        deck.openDiary(card)
        guard d.bool(forKey: "memoriesShotPlay"), let row = card.diaryTracks.first else { return }
        try? await Task.sleep(for: .seconds(2))
        let model = AppModel.shared
        model.play(card, track: row)
        let queue = d.bool(forKey: "memoriesShotQueue")
        guard queue || d.bool(forKey: "memoriesShotNowPlaying") else { return }
        try? await Task.sleep(for: .seconds(1.5))
        model.setNowPlaying(expanded: true)
        guard queue else { return }
        try? await Task.sleep(for: .seconds(1.5))
        model.queueSheetOpen = true
    }

    /// A sideways swipe on the deck (set by MemoriesView): −1 = to the next card.
    static var swipe: ((CGFloat) -> Void)?
    /// The open deck and its actions (set by MemoriesView), for the diary script.
    static var deck: MemoriesDeckModel?
    static var current: (() -> MemoryCard?)?
    static var title: ((MemoryCard, MemoryTitles.Outcome) -> Void)?
    static var review: ((MemoryCard, String) -> Bool)?
    static var back: (() -> Void)?
    /// Turns the deck to a card from another account (true) or of the one in use: that album's when the
    /// deck holds it, else the first (set by MemoriesView).
    static var show: ((Bool, String?) -> Void)?
    /// The current card's Go to album, as its button's tap (set by MemoriesView).
    static var openAlbum: (() -> Void)?

    /// `-profilesScript "…;memories:open;wait:3000;memories:other;wait:1500;memories:album"`: the deck
    /// driven as taps do, between the script's account steps (two accounts' memories in one deck):
    /// `open` / `close`; `other` / `own` turn to the first card from another account / of the account in
    /// use (`other=<album id>`: that album's card when the deck holds it); `diary` opens the current
    /// card's diary; `album` is its Go to album; `play` its diary's first liner row (pair with
    /// `-silentAudio YES`). On another account's card the last two ask through the switch notice.
    static func step(_ verb: String, _ model: AppModel) {
        let parts = verb.split(separator: "=", maxSplits: 1).map(String.init)
        switch parts.first ?? verb {
        case "open": model.memories.open()
        case "close": model.memories.close()
        case "other": show?(true, parts.count > 1 ? parts[1] : nil)
        case "own": show?(false, parts.count > 1 ? parts[1] : nil)
        case "diary": if let deck, let card = current?() { deck.openDiary(card) }
        case "album": openAlbum?()
        case "play":
            guard let deck, let card = current?(), let row = card.diaryTracks.first else { return }
            deck.play(card, track: row, at: nil, from: MemorySwitchAnchor.track(card, row), model: model)
        default: break
        }
    }

    /// `-memoriesDiaryScripted YES`: open on the focus; the Diary button; a
    /// pull past the diary's top that closes it; the button again and the
    /// bar as the handle (a short pull springs back, a long one closes); a
    /// draft typed on the blank page; the title renamed in the diary and
    /// restored; ⌄; escape at the diary level, then at the card (Home).
    static func runDiary(_ memories: MemoriesPresentation, focus: String?) async {
        guard UserDefaults.standard.bool(forKey: "memoriesDiaryScripted"), !started else { return }
        started = true
        let step = UserDefaults.standard.double(forKey: "memoriesStepPause").nonZero ?? 2
        func pause(_ s: Double = step) async { try? await Task.sleep(for: .seconds(s / LaunchConfig.animationSpeed)) }
        try? await Task.sleep(for: .seconds(2.5))
        let target = UserDefaults.standard.string(forKey: "memoriesFocus") ?? focus
        memories.open(focus: target)
        await pause(3)
        if UserDefaults.standard.bool(forKey: "memoriesNoColumn") {
            // The column closing rebuilt the shell (Memories went with Home): open again, full width.
            memories.open(focus: target)
            await pause(3)
        }
        guard let deck, let card = current?() else { return }
        let diary = deck.diary
        deck.openDiary(card)
        await pause()
        // A pull past the top of the text, 220 pt, let go: it closes.
        diary.startPull(banded: true, fromScrolled: false)
        var last: CGFloat = 0
        await finger(0.6) { t in diary.pullBy(t * 220 - last); last = t * 220 }
        diary.releasePull(velocity: 0)
        await pause()
        deck.openDiary(card)
        await pause()
        // The bar as the handle: 90 pt springs back, 200 pt closes.
        for travel in [CGFloat(90), 200] {
            diary.startPull(banded: false, fromScrolled: false)
            last = 0
            await finger(0.5) { t in diary.pullBy(t * travel - last); last = t * travel }
            diary.releasePull(velocity: 0)
            await pause()
            if diary.target == 0 { deck.openDiary(card); await pause() }
        }
        deck.drafts.texts[card.id] = "Late bus, the window fogged, this on repeat."
        await pause(3)
        deck.editor.begin(.diary, card)
        await pause(1.5)
        deck.editor.draft = "Night buses"
        await pause(1.5)
        title?(card, .save(String(localized: "Night buses")))
        deck.editor.end()
        await pause(3)
        if let renamed = current?() { title?(renamed, .restore) }
        await pause(2)
        deck.closeDiary()
        await pause()
        deck.openDiary(card)
        await pause()
        back?()
        await pause()
        back?()
    }

    /// A finger moving 0 → 1 over [seconds], ease-out, at ~120 Hz.
    static func finger(_ seconds: Double, _ step: (CGFloat) -> Void) async {
        let start = Date()
        while true {
            let t = min(1, CGFloat(Date().timeIntervalSince(start) / (seconds / LaunchConfig.animationSpeed)))
            step(1 - pow(1 - t, 2))
            if t >= 1 { break }
            try? await Task.sleep(for: .milliseconds(8))
        }
    }
}
#endif
