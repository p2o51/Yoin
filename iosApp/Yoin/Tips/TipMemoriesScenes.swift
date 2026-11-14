import SwiftUI
import UIKit

/*
 * Memories on the demo phone (Android ui/tips/TipHomeScenes.kt: DemoMemoriesPage, PullDemo,
 * MemoriesAllAccountsDemo). A memory's card is Memories' own face (MemoryCardFace) on its aurora under its
 * top bar, with a deck model of its own: looked at, not used. Nothing is played, opened or written: the
 * card's title and buttons take no touch, and the bar's Home pill only closes the demo's Memories.
 */

/// Memories on the phone at card [page] of the demo's deck (Android DemoMemoriesPage): the card on its aurora,
/// under the bar with the deck's dots. The second card is another account's, which says whose it is under its
/// buttons ("From Okto") and has no quote, to leave the small phone room.
struct DemoMemoriesPage: View {
    var scheme: CoverScheme
    var page = 0
    var onHome: () -> Void = {}
    @Environment(\.colorScheme) private var colorScheme
    @State private var deck = MemoriesDeckModel(presentation: MemoriesPresentation())

    var body: some View {
        let dark = colorScheme == .dark
        let cards = TipDemo.memoryDeck
        let card = cards[min(max(page, 0), cards.count - 1)]
        GeometryReader { geo in
            // The screen under the phone's own status bar, as Memories lays a window out.
            let layout = MemoriesLayouts.layout(width: geo.size.width,
                                                height: geo.size.height - DemoPhoneMetrics.statusBar)
            ZStack(alignment: .top) {
                MemoriesAurora(palettes: [card.album.palette], position: 0, dark: dark)
                    .background(scheme.background)
                MemoryCardFace(card: card, deck: deck, morph: deck.morph(card), metrics: MemoryCardMetrics(layout: layout),
                               scheme: scheme, dark: dark, interactive: false, onOpenAlbum: { _ in }, onTitle: { _ in })
                    .padding(.top, DemoPhoneMetrics.statusBar)
                MemoriesTopBar(cards: cards, position: CGFloat(page), deck: deck, layout: layout, scheme: scheme, dark: dark,
                               width: geo.size.width, onHome: onHome, onDot: { _ in })
                    .demoTarget("memoriesHome")
                    .padding(.top, DemoPhoneMetrics.statusBar)
            }
            .coordinateSpace(.named(MemoryPageMorph.space))
        }
    }
}

// MARK: - Pull for Memories

/// Pull Home down and Memories comes down over it; its Home pill puts it back (Android PullDemo). The phone's
/// own pull works too: down far enough opens Memories, short of it Home springs back.
struct PullForMemoriesDemo: View {
    var playing: Bool
    var scheme: CoverScheme
    /// 0 = Home, 1 = Memories open: the one value the pull, the release and the pill drive.
    @State private var reveal: CGFloat = 0
    /// Where a pull by hand started.
    @State private var pullFrom: CGFloat?
    @Environment(\.tipContainer) private var container

    /// How far the demo's pull takes Memories before it lets go (the rest is the spring).
    static let pullTravel: CGFloat = 0.45
    /// A pull past this opens Memories; short of it, Home springs back.
    static let openAt: CGFloat = 0.25

    private func settle(open: Bool) {
        withAnimation(DemoTiming.springAnimation) { reveal = open ? 1 : 0 }
    }

    var body: some View {
        DemoPhone(playing: playing, scheme: scheme, script: { finger in
            var still = Transaction()
            still.disablesAnimations = true
            withTransaction(still) { reveal = 0 }
            try await finger.moveTo("header", fy: 1.2)
            let from = finger.at("header", fy: 1.2)
            try await finger.drag(toX: from.x, toY: 0.62, ms: 750) { reveal = Self.pullTravel }
            settle(open: true)
            try await finger.lift()
            try await DemoTiming.delay(1_800)
            try await finger.moveTo("memoriesHome", fx: 0.16)
            try await finger.tap { settle(open: false) }
            try await DemoTiming.delay(900)
        }) {
            GeometryReader { geo in
                ZStack(alignment: .top) {
                    DemoHomePage(scheme: scheme) {
                        DemoAccountAvatar(profile: TipDemo.accounts[0], scheme: scheme)
                    }
                    DemoMemoriesPage(scheme: scheme) { settle(open: false) }
                        .offset(y: -geo.size.height * (1 - reveal))
                }
                .gesture(DemoVerticalPull(
                    onChange: { dy in
                        let start = pullFrom ?? reveal
                        if pullFrom == nil { pullFrom = reveal }
                        // The pan reads the page's points; the phone is drawn scaled down on it.
                        let height = DemoPhoneMetrics(container: container).phone.height
                        reveal = min(max(start + dy / max(height, 1), 0), 1)
                    },
                    onEnd: {
                        guard pullFrom != nil else { return }
                        pullFrom = nil
                        settle(open: reveal > Self.openAt)
                    }))
            }
        }
    }
}

/// A pull up or down on the phone that leaves sideways swipes to the tip pager around it: a UIKit pan that
/// begins only when the finger first moves more up or down than across. (A SwiftUI drag watching from the
/// first point would keep the pager from turning under the phone; DemoTouchWatch.)
private struct DemoVerticalPull: UIGestureRecognizerRepresentable {
    /// How far the finger has moved down since the pull began, in the page's points.
    var onChange: (CGFloat) -> Void
    var onEnd: () -> Void

    func makeUIGestureRecognizer(context: Context) -> UIPanGestureRecognizer {
        let pan = UIPanGestureRecognizer()
        pan.maximumNumberOfTouches = 1
        pan.delegate = context.coordinator
        return pan
    }

    func handleUIGestureRecognizerAction(_ recognizer: UIPanGestureRecognizer, context: Context) {
        switch recognizer.state {
        case .changed: onChange(recognizer.translation(in: nil).y)
        case .ended, .cancelled: onEnd()
        default: break
        }
    }

    func makeCoordinator(converter: CoordinateSpaceConverter) -> Coordinator { Coordinator() }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        /// The first movement decides (AlbumAxisPan); a barely-moved touch goes by its velocity.
        func gestureRecognizerShouldBegin(_ recognizer: UIGestureRecognizer) -> Bool {
            guard let pan = recognizer as? UIPanGestureRecognizer else { return false }
            let t = pan.translation(in: nil)
            let d = hypot(t.x, t.y) >= 4 ? t : pan.velocity(in: nil)
            return abs(d.y) > abs(d.x)
        }

        /// The tip page's scroll and the pager wait for the axis: a pull on the phone is the phone's, a swipe
        /// across it still turns the tips.
        func gestureRecognizer(_ g: UIGestureRecognizer, shouldBeRequiredToFailBy other: UIGestureRecognizer) -> Bool {
            guard let scroll = other.view as? UIScrollView, other === scroll.panGestureRecognizer,
                  let mine = g.view else { return false }
            return scroll.isDescendant(of: mine) || mine.isDescendant(of: scroll)
        }
    }
}

// MARK: - Memories across accounts

/// Memories deals every account's memories: the finger swipes on to the next card, one from another account,
/// which says whose it is under Go to album (Android MemoriesAllAccountsDemo: the real card face and its "From …"
/// line). A swipe by hand turns the tips, as on every phone.
struct MemoriesAllAccountsDemo: View {
    var playing: Bool
    var scheme: CoverScheme
    /// 0 = this account's card, 1 = the next one, from another account.
    @State private var shift: CGFloat = 0

    /// How far the demo's swipe carries the deck before it lets go (the rest is the spring).
    static let swipeTravel: CGFloat = 0.6

    var body: some View {
        DemoPhone(playing: playing, scheme: scheme, script: { finger in
            var still = Transaction()
            still.disablesAnimations = true
            withTransaction(still) { shift = 0 }
            try await DemoTiming.delay(900)
            try await finger.moveTo(x: 0.8, y: 0.45)
            try await finger.drag(toX: 0.25, toY: 0.45, ms: 600) { shift = Self.swipeTravel }
            withAnimation(DemoTiming.springAnimation) { shift = 1 }
            try await finger.lift()
            try await DemoTiming.delay(2_800)
        }) {
            GeometryReader { geo in
                ZStack {
                    DemoMemoriesPage(scheme: scheme, page: 0)
                        .offset(x: -geo.size.width * shift)
                    DemoMemoriesPage(scheme: scheme, page: 1)
                        .offset(x: geo.size.width * (1 - shift))
                }
            }
        }
    }
}
