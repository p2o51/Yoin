import SwiftUI
import YoinShared

/*
 * Home on the demo phone (Android ui/tips/TipHomeScenes.kt): the header as Home lays it out (the greeting,
 * the real "Home" title, the account in use), Jump Back In's covers, and the idle bar's Home | Library
 * halves. A scene puts its own part in the header's corner or in place of the shelf.
 */

/// Home's page: [action] in the header's corner (the account in use, on Home), [feed] under the header.
/// [editing]: the scene's own edit controller (Rearrange Home), which the title follows and the bar steps
/// away for. [wide]: an iPad on its side (a `wide` DemoPhone): two columns' margin, the dock's nav capsule in
/// the bottom-left corner.
struct DemoHomePage<Action: View, Feed: View>: View {
    var scheme: CoverScheme
    var editing: HomeEditController?
    var wide = false
    @ViewBuilder var action: () -> Action
    @ViewBuilder var feed: () -> Feed
    /// The title's own controller, never entered: the demo's Home is not being edited.
    @State private var still = HomeEditController(store: TipDemo.layoutStore())

    private var edit: HomeEditController { editing ?? still }

    init(scheme: CoverScheme, editing: HomeEditController? = nil, wide: Bool = false, @ViewBuilder action: @escaping () -> Action,
         @ViewBuilder feed: @escaping () -> Feed) {
        self.scheme = scheme
        self.editing = editing
        self.wide = wide
        self.action = action
        self.feed = feed
    }

    private var margin: CGFloat { wide ? DemoHomeMetrics.wideMargin : DemoHomeMetrics.margin }

    var body: some View {
        // Edit mode: the bar steps away on P, as Home's does (MemoriesBarRetreat).
        let hide = MemoriesBarRetreat.editHide(edit.progress)
        ZStack(alignment: .bottom) {
            // HomeView.feedScroll: 18 between the header and the sections, the feed's 16 margin.
            VStack(alignment: .leading, spacing: 18) {
                header
                // As tall as it is on Home's scrolling feed, not stretched to the screen's foot.
                feed().fixedSize(horizontal: false, vertical: true)
            }
            .padding(.horizontal, margin)
            .padding(.top, DemoPhoneMetrics.statusBar)
            .coordinateSpace(.named(HomeEditController.feedSpace))
            // The screen's size whatever the feed holds: a shelf longer than the phone runs off its foot.
            .frame(minWidth: 0, maxWidth: .infinity, minHeight: 0, maxHeight: .infinity, alignment: .topLeading)
            Group {
                if wide {
                    // iPad's dock: the nav capsule in the bottom-left corner (PadDock).
                    DemoShellBar(scheme: scheme, pad: true)
                        .fixedSize()
                        .demoTarget("bar")
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, PadDockMetrics.side)
                        .padding(.bottom, PadDockMetrics.bottom)
                } else {
                    DemoShellBar(scheme: scheme)
                        .padding(.horizontal, 14)
                        .padding(.bottom, DemoHomeMetrics.barBottom)
                        .demoTarget("bar")
                }
            }
            .offset(y: hide * 120)
            .opacity(Double(max(0, 1 - hide * 1.4)))
            .allowsHitTesting(hide < 0.5)
        }
        .background(LinearGradient(colors: [scheme.surfaceContainer, scheme.background, scheme.surfaceContainerLow],
                                   startPoint: .top, endPoint: .bottom))
        .environment(\.homeScheme, scheme)
        .environment(\.homeMargin, margin)
    }

    /// HomeView.header's row.
    private var header: some View {
        HStack(alignment: .center, spacing: 2) {
            VStack(alignment: .leading, spacing: 0) {
                Text(HomeView.greeting(at: .now))
                    .font(YoinFont.rounded(16, relativeTo: .subheadline))
                    .foregroundStyle(scheme.onSurfaceVariant)
                    .frame(height: 24)
                EditTitleSwap(edit: edit, scheme: scheme)
                    .frame(height: 36)
            }
            .lineLimit(1)
            .padding(.trailing, Tokens.home.HEADER_TITLE_BREATHING.cg)
            Spacer(minLength: 0)
            action()
        }
        .frame(minHeight: 48)
        .padding(.top, 12)
        .demoTarget("header")
    }
}

extension DemoHomePage where Feed == DemoJumpBackIn {
    init(scheme: CoverScheme, @ViewBuilder action: @escaping () -> Action) {
        self.init(scheme: scheme, action: action) { DemoJumpBackIn() }
    }
}

// MARK: - Rearrange Home

/// Rearranging Home (Android EditHomeDemo, 2026-11-23): long-press Home into edit mode, pinch the sections into
/// a list and back out, long-press a section and drag it up; then, on a large screen ([wide]: a tablet on its
/// side, in two columns), drop one beside another and the two share a row. Done ends it. An iPhone never lays
/// Home out in two columns, so its phone leaves the drop beside out, as its tip's line does.
///
/// The real header (the title swap, the list switch, Undo and Done, on the scene's own HomeEditController) and
/// bar; the sections drawn simply (plate, title, covers, handle), each step as the app answers it: the plates'
/// ripple, the sway, the lift, the drop slot and the kicks are Home's own.
struct EditHomeDemo: View {
    var playing: Bool
    var scheme: CoverScheme
    /// iPad and Mac: the tablet, with the drop beside.
    var wide: Bool
    @State private var edit = HomeEditController(store: TipDemo.layoutStore())
    @State private var feed = DemoEditFeed()

    var body: some View {
        DemoPhone(playing: playing, wide: wide, scheme: scheme, script: { finger in
            try await act(finger)
        }) {
            DemoHomePage(scheme: scheme, editing: edit, wide: wide) {
                HeaderActions(edit: edit, scheme: scheme) {
                    DemoAccountAvatar(profile: TipDemo.accounts[0], scheme: scheme)
                }
            } feed: {
                DemoEditFeedBody(feed: feed, edit: edit)
                    // The hold that enters edit mode is the page's (Home's own is on its scroll view).
                    .overlay {
                        Color.clear
                            .contentShape(.rect)
                            .onLongPressGesture(minimumDuration: HomeEditController.holdDuration(editing: false)) { edit.enter() }
                    }
            }
        }
        // The header's switch (or Done) folds and unfolds the list as the pinch does.
        .onChange(of: edit.list.on) { _, on in feed.follow(listOn: on) }
    }

    private func act(_ finger: DemoFinger) async throws {
        edit.exit(.programmatic)
        if feed.reset() { try await DemoTiming.delay(DemoEditFeed.settleMs) }
        try await DemoTiming.delay(400)
        // Long-press Home: edit mode.
        try await finger.moveTo(DemoEditFeed.key(.jumpBackIn), fx: 0.3, fy: 0.55)
        try await finger.hold { edit.enter() }
        try await DemoTiming.delay(700)
        // Pinch: the sections close into a list, then open back out.
        let centre = finger.at(DemoEditFeed.feedKey)
        edit.touched()
        try await finger.pinch(cx: centre.x, cy: centre.y, fromGap: 0.34, toGap: 0.12, ms: 650) { feed.pinched($0) }
        edit.list.set(true)
        try await DemoTiming.delay(900)
        edit.touched()
        try await finger.pinch(cx: centre.x, cy: centre.y, fromGap: 0.12, toGap: 0.34, ms: 650) { feed.pinched(1 - $0) }
        edit.list.set(false)
        try await DemoTiming.delay(500)
        // Long-press drag: Your Playlists up above Recently Added.
        try await finger.moveTo(DemoEditFeed.key(.yourPlaylists))
        edit.touched()
        try await finger.hold { feed.pick(.yourPlaylists) }
        let up = finger.at(DemoEditFeed.key(.recentlyAdded))
        try await finger.drag(toX: up.x, toY: up.y, ms: 700, onProgress: { p in
            feed.carry(.yourPlaylists, to: DemoPlace(slot: 2 - p))
        })
        try await drop(.yourPlaylists, at: DemoPlace(slot: 1), moved: [.recentlyAdded])
        if wide {
            try await DemoTiming.delay(600)
            // Drop beside: Recently Added onto the right of Your Playlists, and the two share a row.
            try await finger.moveTo(DemoEditFeed.key(.recentlyAdded))
            edit.touched()
            try await finger.hold { feed.pick(.recentlyAdded) }
            let beside = finger.at(DemoEditFeed.key(.yourPlaylists), fx: 0.8)
            try await finger.drag(toX: beside.x, toY: beside.y, ms: 800, onProgress: { p in
                feed.carry(.recentlyAdded, to: DemoPlace(slot: 2 - p, left: 0.5 * p, width: 1 - 0.5 * p))
                if p > 0.6 { feed.makeRoom(.yourPlaylists) }
            })
            try await drop(.recentlyAdded, at: DemoPlace(slot: 1, left: 0.5, width: 0.5), moved: [.yourPlaylists])
        }
        try await finger.lift()
        try await DemoTiming.delay(1_600)
        try await finger.moveTo("editDone")
        try await finger.tap { edit.exit(.done) }
        try await DemoTiming.delay(900)
    }

    /// Puts [section] down at [place]: it glides home, then it and the blocks it [moved] are kicked (HomeDragController).
    private func drop(_ section: HomeSection, at place: DemoPlace, moved: [HomeSection]) async throws {
        feed.drop(section, at: place)
        try await DemoTiming.delay(DemoEditFeed.settleMs)
        feed.landed()
        edit.kick(section, HomeEditMotionTokens.kickDrop)
        for s in moved { edit.kick(s, HomeEditMotionTokens.kickNeighbour) }
        edit.touched()
    }
}

/// Where a demo section sits: its row from the top, its left edge and its width, as fractions of the feed's
/// width (0 / 1 a full row, 0.5 / 0.5 the right half).
struct DemoPlace: Equatable {
    var slot: CGFloat
    var left: CGFloat = 0
    var width: CGFloat = 1
}

/// Rearrange Home's three sections (Android DemoEditFeed): each one's place, how far the carried one is lifted
/// and how far the feed is pinched into a list ([list]). The carried one follows the finger, frame by frame;
/// the rest spring.
@MainActor
@Observable
final class DemoEditFeed {
    static let sections: [HomeSection] = [.jumpBackIn, .recentlyAdded, .yourPlaylists]
    static let feedKey = "edit-feed"
    static func key(_ section: HomeSection) -> String { "edit-\(section.id)" }

    /// 0: the feed; 1: the list.
    private(set) var list: CGFloat = 0
    private(set) var lift: CGFloat = 0
    private(set) var carried: HomeSection?
    private(set) var places: [HomeSection: DemoPlace] = DemoEditFeed.rest
    /// Where the carried one will land (Home's drop slot), and whether it shows.
    private(set) var slot = DemoPlace(slot: 0)
    private(set) var slotShown = false

    private static var rest: [HomeSection: DemoPlace] {
        Dictionary(uniqueKeysWithValues: sections.enumerated().map { ($1, DemoPlace(slot: CGFloat($0))) })
    }

    /// Android's settle: low-bouncy, medium-low stiffness.
    private static let settleSpring = Spring(mass: 1, stiffness: 400, damping: 2 * 0.75 * 20)
    static var settle: Animation { .interpolatingSpring(settleSpring).speed(LaunchConfig.animationSpeed) }
    static var settleMs: Int { Int((settleSpring.settlingDuration * 1000).rounded()) }

    func place(_ section: HomeSection) -> DemoPlace { places[section] ?? DemoPlace(slot: 0) }

    /// Back to the feed's order, not lifted, not pinched; true when it had moved (springing back).
    @discardableResult
    func reset() -> Bool {
        let moved = places != Self.rest || list != 0 || lift != 0
        carried = nil
        slotShown = false
        withAnimation(Self.settle) {
            places = Self.rest
            list = 0
            lift = 0
        }
        return moved
    }

    /// The pinch at [p] (0 the feed … 1 the list), frame by frame.
    func pinched(_ p: CGFloat) { list = min(max(p, 0), 1) }

    /// The list switched on or off by itself (the header's switch, Done): the feed follows on the spring.
    func follow(listOn on: Bool) {
        let to: CGFloat = on ? 1 : 0
        guard list != to else { return }
        withAnimation(DemoTiming.springAnimation) { list = to }
    }

    func pick(_ section: HomeSection) {
        carried = section
        slot = place(section)
        withAnimation(Yoin.effects) { slotShown = true }
        withAnimation(.interpolatingSpring(HomeEditController.stageSpring).speed(LaunchConfig.animationSpeed)) { lift = 1 }
    }

    /// The carried one under the finger. Carried straight up past the middle of the row above, that one moves down.
    func carry(_ section: HomeSection, to place: DemoPlace) {
        places[section] = place
        if place.left == 0, place.slot < 1.5,
           let above = Self.sections.first(where: { $0 != section && self.place($0).slot == 1 }) {
            withAnimation(Self.settle) {
                places[above] = DemoPlace(slot: 2)
                slot = DemoPlace(slot: 1)
            }
        }
    }

    /// A pair forming: [section] narrows to the left half for the one coming beside it.
    func makeRoom(_ section: HomeSection) {
        let p = place(section)
        guard p.width != 0.5 else { return }
        withAnimation(Self.settle) {
            places[section] = DemoPlace(slot: p.slot, left: 0, width: 0.5)
            slot = DemoPlace(slot: p.slot, left: 0.5, width: 0.5)
        }
    }

    /// Lets the carried one go at [place]: it glides there and comes down.
    func drop(_ section: HomeSection, at place: DemoPlace) {
        withAnimation(Self.settle) {
            places[section] = place
            lift = 0
        }
    }

    /// The glide is over: the slot goes, the block is one of the rows again.
    func landed() {
        carried = nil
        withAnimation(Yoin.effects) { slotShown = false }
    }
}

/// Numbers of the demo's sections (Android TipHomeScenes' DemoFeedRow …), the list's from Home's own list.
private enum DemoEditFeedMetrics {
    static let plateCorner: CGFloat = 20
    static let feedRow: CGFloat = 116
    static let listRow: CGFloat = HomeEditListTokens.rowHeight
    static let feedGap: CGFloat = 16
    static let listGap: CGFloat = HomeEditListTokens.rowGap
    /// Between the halves of a row (HomeFeedRows).
    static let columnGap: CGFloat = 20
    static let cover: CGFloat = 60
    static let coversPerRow = 10

    static func lerp(_ a: CGFloat, _ b: CGFloat, _ t: CGFloat) -> CGFloat { a + (b - a) * min(max(t, 0), 1) }
}

/// The sections as edit mode draws them: a plate (with edit mode), title, covers (gone in the list) and the
/// drag handle, each at its place; the drop slot under the carried one.
private struct DemoEditFeedBody: View {
    @Environment(\.homeScheme) private var scheme
    var feed: DemoEditFeed
    var edit: HomeEditController

    var body: some View {
        let n = CGFloat(DemoEditFeed.sections.count)
        GeometryReader { geo in
            let full = geo.size.width
            ZStack(alignment: .topLeading) {
                slotMark(rect(feed.slot, full: full))
                ForEach(Array(DemoEditFeed.sections.enumerated()), id: \.element) { i, section in
                    let r = rect(feed.place(section), full: full)
                    block(section, index: i, size: r.size)
                        .frame(width: r.width, height: r.height)
                        .demoTarget(DemoEditFeed.key(section))
                        .position(x: r.midX, y: r.midY)
                        .zIndex(feed.carried == section ? 1 : 0)
                }
            }
        }
        .frame(height: DemoEditFeedMetrics.feedRow * n + DemoEditFeedMetrics.feedGap * (n - 1))
        .demoTarget(DemoEditFeed.feedKey)
    }

    /// A place's frame in the feed, as wide as [full], the rows as tall as the pinch leaves them.
    private func rect(_ place: DemoPlace, full: CGFloat) -> CGRect {
        let gap = DemoEditFeedMetrics.lerp(DemoEditFeedMetrics.feedGap, DemoEditFeedMetrics.listGap, feed.list)
        let rowH = DemoEditFeedMetrics.lerp(DemoEditFeedMetrics.feedRow, DemoEditFeedMetrics.listRow, feed.list)
        let w = max(1, place.width * (full + DemoEditFeedMetrics.columnGap) - DemoEditFeedMetrics.columnGap)
        return CGRect(x: place.left * (full + DemoEditFeedMetrics.columnGap), y: place.slot * (rowH + gap), width: w, height: rowH)
    }

    private func block(_ section: HomeSection, index: Int, size: CGSize) -> some View {
        let s = scheme!
        let p = min(max(edit.progress, 0), 1)
        let ripple = HomeEditController.ripple(p, index: index)
        let carried = feed.carried == section
        let lift = carried ? min(max(feed.lift, 0), 1.2) : 0
        let list = min(max(feed.list, 0), 1)
        let albums = TipDemo.albums
        let shape = RoundedRectangle(cornerRadius: DemoEditFeedMetrics.plateCorner, style: .continuous)
        return VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                HomeSectionTitle(section.title)
                    .frame(maxWidth: .infinity, alignment: .leading)
                YoinIcon("drag_handle", size: 24)
                    .foregroundStyle(s.onSurfaceVariant)
                    .opacity(Double(p))
            }
            HStack(spacing: 10) {
                ForEach(0..<DemoEditFeedMetrics.coversPerRow, id: \.self) { k in
                    CoverArt(palette: albums[(index * 3 + k) % albums.count].palette, cornerRadius: Tokens.shape.THUMB.cg)
                        .frame(width: DemoEditFeedMetrics.cover, height: DemoEditFeedMetrics.cover)
                }
            }
            .fixedSize()
            .opacity(Double(1 - list))
        }
        // In the list the title sits in the strip's middle, as Home's strips have it.
        .padding(.top, 10 + 10 * list)
        .padding(.horizontal, 16)
        .frame(width: size.width, height: size.height, alignment: .topLeading)
        .clipShape(shape)
        .background {
            // The plate comes up under it through the ripple; lifted, it tints toward the highest container
            // and casts its shadow (HomeBlockChrome).
            shape
                .fill(s.surfaceContainer.mix(with: s.surfaceContainerHighest, by: Double(min(lift, 1)), in: .perceptual)
                    .opacity(max(Double(ripple), Double(min(lift, 1)))))
                .shadow(color: .black.opacity(0.2 * Double(lift)), radius: HomeEditMotionTokens.liftShadow * lift, y: 3 * lift)
        }
        .scaleEffect(1 + HomeEditMotionTokens.liftScale * lift)
        .modifier(DemoSway(edit: edit, section: section, still: carried))
    }

    /// Where the carried one will land (HomeDropSlot): the lifted plate colour at 55 % with a 2 pt outline.
    private func slotMark(_ r: CGRect) -> some View {
        let s = scheme!
        let shape = RoundedRectangle(cornerRadius: DemoEditFeedMetrics.plateCorner, style: .continuous)
        return shape
            .fill(s.surfaceContainerHighest.opacity(HomeEditMotionTokens.slotFillAlpha))
            .overlay { shape.strokeBorder(s.outlineVariant, lineWidth: HomeEditMotionTokens.slotStroke) }
            .frame(width: r.width, height: r.height)
            .position(x: r.midX, y: r.midY)
            .opacity(feed.slotShown ? 1 : 0)
            .allowsHitTesting(false)
    }
}

/// Edit mode's sway and kicks on a demo section (Home's SwayModifier, on the scene's controller): still while it
/// is carried.
private struct DemoSway: ViewModifier {
    var edit: HomeEditController
    var section: HomeSection
    var still: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// A kick's first peak, in degrees per unit of amplitude (SwayModifier).
    private static let kickDegrees = 0.6

    func body(content: Content) -> some View {
        let quiet = still || reduceMotion
        let envelope = quiet ? 0 : edit.sway
        let k = quiet ? 0 : Double(edit.kickState(section).value) * Self.kickDegrees
        if envelope > 0.0005 {
            TimelineView(.animation) { t in
                content.rotationEffect(.degrees(HomeEditController.swayAngle(section, time: t.date.timeIntervalSinceReferenceDate,
                                                                           envelope: envelope) + k))
            }
        } else {
            content.rotationEffect(.degrees(abs(k) < 0.0005 ? 0 : k))
        }
    }
}

// MARK: - Switch accounts

/// Hold the header's avatar: the other accounts drop out of it in a column; slide down onto one and let go,
/// and it is the account in use. Home's avatar, its hold and its column, laid over the phone's own screen.
struct AccountQuickSwitchDemo: View {
    var playing: Bool
    var scheme: CoverScheme
    @State private var activeId = TipDemo.accounts[0].id
    @State private var fan = AccountFan(haptics: false)

    private var accounts: [Profile] { TipDemo.accounts }
    private var active: Profile { accounts.first { $0.id == activeId } ?? accounts[0] }
    private var shapes: [String: Int] {
        Dictionary(uniqueKeysWithValues: accounts.map { ($0.id, TipDemo.shape(of: $0)) })
    }

    /// The column's items the finger slides onto, in order; it lets go on the last.
    static let slides = [0, 1]

    private func pick(_ p: Profile) {
        withAnimation(Yoin.spatial) { activeId = p.id }
    }

    private func act(_ finger: DemoFinger) async throws {
        fan.close()
        withAnimation(Yoin.spatial) { activeId = accounts[0].id }
        try await DemoTiming.delay(400)
        try await finger.moveTo("account")
        // Held for the long-press time (the ring fills), then the column drops out.
        try await finger.pressAndHold()
        guard let anchor = finger.targets.rect("account") else { return try await finger.release() }
        fan.begin(anchor: anchor, accounts: Array(accounts.dropFirst()), shapes: shapes) { pick($0) }
        try await DemoTiming.delay(450)
        // Down the column, onto the first account, then the second.
        for i in Self.slides {
            let y = AccountFanGeometry.itemCenterY(anchorHeight: anchor.height, index: i)
            try await finger.moveTo("account", fy: y / anchor.height)
            fan.move(to: CGPoint(x: anchor.midX, y: anchor.minY + y))
            try await DemoTiming.delay(380)
        }
        // Let go: the one under the finger is the account in use.
        fan.end()
        try await finger.release()
        try await finger.lift()
        try await DemoTiming.delay(1_800)
    }

    var body: some View {
        DemoPhone(playing: playing, scheme: scheme, script: { finger in
            do {
                try await act(finger)
            } catch {
                // Stopped with the column out: it goes back in.
                fan.close()
                throw error
            }
        }) {
            ZStack {
                DemoHomePage(scheme: scheme) {
                    DemoAccountAvatar(profile: active, scheme: scheme)
                        .demoTarget("account")
                        .accountQuickSwitch(accounts: accounts.filter { $0.id != activeId }, shapes: shapes, fan: fan,
                                            space: .named(DemoTargets.space)) { pick($0) }
                }
                AccountFanColumn(fan: fan, scheme: scheme, space: .named(DemoTargets.space))
            }
        }
    }
}

enum DemoHomeMetrics {
    static let margin: CGFloat = 16
    /// Two columns' page margin (HomeDensity, wide).
    static let wideMargin: CGFloat = 32
    /// Under the bar: the phone's home indicator band, cut down to the demo's size.
    static let barBottom: CGFloat = 14
}

/// Jump Back In's covers: every album, a 1×1 each, three to a row as the phone's shelf lays them
/// (HomeView.serverJumpBackIn).
struct DemoJumpBackIn: View {
    private let albums = TipDemo.albums + TipDemo.albums.reversed()

    var body: some View {
        VStack(alignment: .leading, spacing: Tokens.home.JBI_ROW_GAP.cg) {
            HomeSectionTitle(String(localized: "Jump Back In"))
            ForEach(Array(stride(from: 0, to: albums.count, by: 3)), id: \.self) { row in
                HStack(alignment: .top, spacing: Tokens.home.JBI_COLUMN_GAP.cg) {
                    ForEach(row..<min(row + 3, albums.count), id: \.self) { i in
                        AlbumItem(album: albums[i], size: Tokens.home.JBI_COVER.cg)
                            .demoTarget("jbi.\(i)")
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            }
        }
        .demoTarget("jbi")
    }
}

/// The phone's bar (ShellBar): idle, Home | Library labelled; with [nowPlaying], Home, the mini player and
/// Library; then the Search circle; in the bar's own faces and glass. [selected] is the section on screen.
/// [onLibraryHold] and [onPillHold] are the bar's long-presses (Library's search, the playing song's sheet).
/// [pad]: iPad's nav capsule (ShellBar `.nav`): the halves always labelled and as wide as their labels.
struct DemoShellBar: View {
    var scheme: CoverScheme
    var pad = false
    var selected: ShellSection = .home
    var nowPlaying: NowPlayingDemo? = nil
    var onLibraryHold: (() -> Void)? = nil
    var onPillHold: (() -> Void)? = nil
    @Environment(\.colorScheme) private var colorScheme

    /// ShellBar's long-press time.
    static let holdSeconds = 0.45

    var body: some View {
        let brand = CoverScheme.brand(dark: colorScheme == .dark)
        // Playing, the bar takes the song's colours (ShellBar.scheme).
        let bar = nowPlaying.map { CoverScheme(album: $0.album, dark: colorScheme == .dark) } ?? scheme
        GlassEffectContainer(spacing: 8) {
            HStack(spacing: 6) {
                half(.home, scheme: bar, brand: brand)
                if let nowPlaying { pill(nowPlaying, scheme: bar, brand: brand) }
                half(.library, scheme: bar, brand: brand)
                    .demoTarget("library")
                    .onLongPressGesture(minimumDuration: Self.holdSeconds) { onLibraryHold?() }
                YoinIcon("search", size: 24)
                    .foregroundStyle(Yoin.ink)
                    .labContrast("barUnselected")
                    .frame(width: 60, height: 60)
                    .labGlass("barUnselected", scheme: bar, brand: brand, auto: bar.tertiary, in: Circle())
                    .padding(.leading, pad ? 0 : 6)
            }
        }
        .frame(height: 60)
    }

    /// One half in the bar's glass (ShellBar.navButton: the accent tinted into the selected one's glass, or
    /// the Glass Lab surface).
    @ViewBuilder
    private func half(_ section: ShellSection, scheme: CoverScheme, brand: CoverScheme) -> some View {
        let lab = GlassLab.shared
        let on = section == selected
        let idle = nowPlaying == nil
        let accent = section == .home ? scheme.primary : scheme.secondary
        let face = ShellNavFace(section: section, selected: on, labelled: idle || pad, scheme: scheme,
                                sidePadding: pad ? 20 : 0, fills: idle && !pad)
        if on && lab.extra("barSelectedStyle") >= 0.5 {
            face.yGlassEffect(.regular.tint(accent.opacity(lab.style("barSelected").tintOpacity)).interactive(), solid: accent, in: .capsule)
        } else {
            face.labGlass(on ? "barSelected" : "barUnselected", scheme: scheme, brand: brand, auto: accent, in: Capsule())
        }
    }

    /// The mini player (ShellBar.pill): the song's cover, title and artist over its progress wash.
    private func pill(_ demo: NowPlayingDemo, scheme: CoverScheme, brand: CoverScheme) -> some View {
        PillContent(demo: demo)
            .contentShape(.capsule)
            .environment(\.demoPlayhead, demo.playhead)
            .labGlass("miniPlayer", scheme: scheme, brand: brand, auto: scheme.primaryContainer, in: Capsule())
            .demoTarget("pill")
            .onLongPressGesture(minimumDuration: Self.holdSeconds) { onPillHold?() }
    }
}

/// The account in use, as Home's header shows it.
struct DemoAccountAvatar: View {
    var profile: Profile
    var scheme: CoverScheme

    var body: some View {
        HeaderAccountAvatar(profile: profile, shape: TipDemo.shape(of: profile), fallback: scheme.primary,
                            onFallback: scheme.onPrimary, ring: scheme.surfaceContainer)
    }
}

// MARK: - Switch to Spotify

/// Opened in a non-Spotify account while the signed-in Spotify one plays: the header's pill (Home's own
/// SpotifyElsewherePill) shows the song beside the avatar; the finger taps it and Spotify's account is the
/// one in use.
struct SpotifyElsewhereDemo: View {
    var playing: Bool
    var scheme: CoverScheme
    @State private var activeId = TipDemo.accounts[0].id
    @State private var shown = false

    private var accounts: [Profile] { TipDemo.accounts }
    private var spotify: Profile { accounts.first { $0.service == .spotify } ?? accounts[0] }
    private var active: Profile { accounts.first { $0.id == activeId } ?? accounts[0] }

    private var elsewhere: SpotifyElsewhere {
        SpotifyElsewhere(profileId: spotify.id, title: TipDemo.trackTitles[0], artist: TipDemo.albums[3].artist,
                         coverURL: nil, isPlaying: true)
    }

    private func switchToSpotify() {
        withAnimation(Yoin.spatial) {
            shown = false
            activeId = spotify.id
        }
    }

    var body: some View {
        DemoPhone(playing: playing, scheme: scheme, script: { finger in
            withAnimation(Yoin.spatial) {
                activeId = accounts[0].id
                shown = false
            }
            try await DemoTiming.delay(700)
            // The app comes to the front: Spotify's song shows up top.
            withAnimation(Yoin.spatial) { shown = true }
            try await DemoTiming.delay(1_600)
            try await finger.moveTo("spotify-pill")
            try await finger.tap { switchToSpotify() }
            try await finger.lift()
            try await DemoTiming.delay(2_000)
        }) {
            DemoHomePage(scheme: scheme) {
                HStack(spacing: 2) {
                    if shown {
                        SpotifyElsewherePill(elsewhere: elsewhere, scheme: scheme, demoCover: TipDemo.albums[3].palette) {
                            switchToSpotify()
                        }
                        .demoTarget("spotify-pill")
                    }
                    DemoAccountAvatar(profile: active, scheme: scheme)
                }
            }
        }
    }
}

// MARK: - Lyric Cards

/// Lyric Cards: Home's own shelf under the header. The finger swipes on to the next song's line and back,
/// then taps a card to play from it.
struct LyricHighlightDemo: View {
    var playing: Bool
    var scheme: CoverScheme
    @State private var position: String? = TipDemo.lyricCards[0].id

    private var cards: [LyricHighlightRow] { TipDemo.lyricCards }

    /// The pages the finger turns to, in order: on, on again, and one back.
    static let turns = [1, 2, 1]

    var body: some View {
        DemoPhone(playing: playing, scheme: scheme, script: { finger in
            withAnimation(Yoin.spatial) { position = cards[0].id }
            try await DemoTiming.delay(900)
            var page = 0
            for target in Self.turns {
                // The finger goes the way the cards do: leftwards on to the next, rightwards back.
                let forward = target > page
                try await finger.moveTo("lyric", fx: forward ? 0.8 : 0.3, fy: 0.6)
                try await DemoTiming.delay(200)
                let end = finger.at("lyric", fx: forward ? 0.3 : 0.8, fy: 0.6)
                withAnimation(Yoin.spatial) { position = cards[target].id }
                try await finger.drag(toX: end.x, toY: end.y, ms: 420)
                page = target
                try await DemoTiming.delay(900)
            }
            try await finger.moveTo("lyric", fx: 0.4, fy: 0.5)
            try await DemoTiming.delay(250)
            try await finger.tap {}
            try await DemoTiming.delay(900)
            try await finger.lift()
            try await DemoTiming.delay(600)
        }) {
            DemoHomePage(scheme: scheme) {
                DemoAccountAvatar(profile: TipDemo.accounts[0], scheme: scheme)
            } feed: {
                HomeLyricCardsSection(cards: cards,
                                      demo: LyricCardsDemo(palettes: TipDemo.lyricPalettes, position: $position) { _ in })
                    .demoTarget("lyric")
            }
        }
    }
}
