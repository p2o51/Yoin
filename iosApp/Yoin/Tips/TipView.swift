import SwiftUI
import YoinShared

/*
 * The tips, one per page, starting at [start] (Android ui/tips/TipScreen.kt, owner 2026-10-18), opened from
 * Home's bubble or Settings › About › Tips. Swiping looks around; "Read" marks (or unmarks) the one on
 * screen; "Next" marks it read and moves to the next unread one, "Done" when it is the last, which closes
 * the page. Home's bubble only brings up unread tips (TipReadStore).
 *
 * A pushed page: the back circle, the edge swipe and Done all pop it. Opened as a route (Home's bubble; on a
 * wide iPad the column's root) they pop the path, which also closes the column; pushed from the Tips list
 * they dismiss.
 */
struct TipView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dismiss) private var dismiss
    var start: String
    private let store = TipReadStore.shared
    @State private var position: ScrollPosition
    /// The tip the pager rests on: its demo plays, its tile has grown in.
    @State private var settled: String
    @State private var pager = TipPagerState()
    @State private var container = CGSize.zero
    @State private var readTaps = 0
    @State private var nextTaps = 0
    /// The page's bottom safe area: on iPad it already holds the dock's clearance when the column pads it.
    @State private var bottomInset: CGFloat = 0

    init(start: String) {
        self.start = start
        let id = Tips.tip(start)?.id ?? Tips.all[0].id
        _position = State(initialValue: ScrollPosition(id: id))
        _settled = State(initialValue: id)
    }

    private var app: CoverScheme {
        model.isIdle ? .brand(dark: colorScheme == .dark)
                     : CoverScheme(album: model.playback.album, dark: colorScheme == .dark)
    }

    private var tips: [Tip] { Tips.all }
    /// The tip on screen: the one the pager is nearest.
    private var current: Tip {
        let id = position.viewID(type: String.self) ?? settled
        return Tips.tip(id) ?? tips[0]
    }

    var body: some View {
        // The header, the pager and the buttons share the page top to bottom: a tip's own scroll runs
        // between the two rows, never under them.
        VStack(spacing: 0) {
            topBar
            pages
            actions
        }
        .background(app.surface.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .onGeometryChange(for: CGSize.self) { $0.size } action: { container = $0 }
        .onGeometryChange(for: CGFloat.self) { $0.safeAreaInsets.bottom } action: { bottomInset = $0 }
        .environment(\.tipContainer, container)
    }

    private var pages: some View {
        ScrollView(.horizontal) {
            LazyHStack(spacing: 0) {
                ForEach(Array(tips.enumerated()), id: \.element.id) { i, tip in
                    TipPage(tip: tip, index: i, settled: settled == tip.id, pager: pager, scheme: app)
                        .containerRelativeFrame([.horizontal, .vertical])
                }
            }
            .scrollTargetLayout()
        }
        .scrollTargetBehavior(.paging)
        .scrollPosition($position)
        .scrollIndicators(.hidden)
        .onScrollGeometryChange(for: CGFloat.self) { g in
            g.containerSize.width > 0 ? g.contentOffset.x / g.containerSize.width : 0
        } action: { _, page in
            pager.position = page
        }
        .onScrollPhaseChange { _, phase in
            if phase == .idle, let id = position.viewID(type: String.self) { settled = id }
        }
        // A page scrolled under the header or the buttons fades out there instead of being cut.
        .mask {
            VStack(spacing: 0) {
                LinearGradient(colors: [.clear, .black], startPoint: .top, endPoint: .bottom).frame(height: 12)
                Rectangle()
                LinearGradient(colors: [.black, .clear], startPoint: .top, endPoint: .bottom).frame(height: 12)
            }
        }
    }

    private var topBar: some View {
        HStack {
            Button { close() } label: {
                YoinIcon("back", size: 22)
                    .foregroundStyle(app.onSurface)
                    .frame(width: 40, height: 40)
                    .contentShape(.circle)
            }
            .buttonStyle(.plain)
            .glassEffect(.regular.interactive(), in: .circle)
            .frame(width: 48, height: 48)
            .accessibilityLabel("Back")
            .settingsPaneBack()
            Spacer()
            Text(Tips.positionLine(index: tips.firstIndex(of: current) ?? 0, total: tips.count))
                .yoinRole(Tokens.type.labelLarge)
                .monospacedDigit()
                .foregroundStyle(app.onSurfaceVariant)
                .contentTransition(.numericText())
                .animation(Yoin.effects, value: current.id)
                .padding(.trailing, 24)
        }
        .padding(.leading, 8)
        .padding(.vertical, 4)
    }

    /// Read on the left, Next (or Done) on the right. Fixtures of the page, not over its content, so tonal
    /// rather than glass (docs/glass-layers.md R1); Next is the one filled accent (R2), and Read, once on,
    /// takes the accent's container (Android's toggle fills with primary).
    private var actions: some View {
        let tip = current
        let read = store.isRead(tip)
        let next = Tips.nextUnread(after: tip.id, read: store.read)
        return HStack(spacing: 12) {
            Button {
                readTaps += 1
                store.setRead(tip, !read)
            } label: {
                HStack(spacing: 8) {
                    if read {
                        YoinIcon("check", size: 18).transition(.scale(scale: 0.5).combined(with: .opacity))
                    }
                    Text("Read").yoinRole(Tokens.type.labelLarge)
                }
                .foregroundStyle(read ? app.onPrimaryContainer : app.onSurfaceVariant)
                .padding(.horizontal, 24)
                .frame(height: TipMetrics.buttonHeight)
                // M3's toggle: round at rest, squared off once it is on.
                .background(read ? app.primaryContainer : app.surfaceContainerHigh,
                            in: .rect(cornerRadius: read ? 16 : TipMetrics.buttonHeight / 2, style: .continuous))
                .contentShape(.rect)
            }
            .buttonStyle(PressScale())
            .animation(Yoin.spatialFast, value: read)
            .sensoryFeedback(.selection, trigger: readTaps)
            .accessibilityAddTraits(read ? .isSelected : [])
            .accessibilityHint(read ? String(localized: "Mark as unread") : String(localized: "Mark as read"))
            Spacer(minLength: 0)
            Button {
                nextTaps += 1
                store.setRead(tip, true)
                if let next {
                    withAnimation(Yoin.standardDefaultSpatial) { position.scrollTo(id: next.id) }
                } else {
                    close()
                }
            } label: {
                HStack(spacing: 8) {
                    Text(next == nil ? String(localized: "Done") : String(localized: "tip.next", defaultValue: "Next"))
                        .yoinRole(Tokens.type.labelLarge)
                    if next != nil { YoinIcon("chevron_right", size: 18) }
                }
                .foregroundStyle(app.onPrimary)
                .padding(.horizontal, 24)
                .frame(height: TipMetrics.buttonHeight)
                .background(app.primary, in: .capsule)
                .contentShape(.capsule)
            }
            .buttonStyle(PressScale())
            .animation(Yoin.spatialFast, value: next == nil)
            .sensoryFeedback(.success, trigger: nextTaps)
        }
        .frame(maxWidth: TipMetrics.prose)
        .padding(.horizontal, 24)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity)
        // The phone's bar floats over the page's foot. On iPad the dock does: a route's column pads the page
        // clear of it, a page pushed from the Tips list keeps clear itself.
        .padding(.bottom, model.isPad ? TipMetrics.padDockPadding(bottomInset: bottomInset) : ShellBarMetrics.clearance)
    }

    /// Back and Done: through the path when this page is the path's last entry (so a column it roots
    /// closes, as the other column pages do), else the plain pop of a page pushed from the Tips list.
    private func close() {
        if TipMetrics.closesThroughPath(model.path, start: start) { model.back() } else { dismiss() }
    }
}

/// Android TipScreen.kt's numbers.
enum TipMetrics {
    /// A reading page's widest column (Android YoinPageWidths.Prose).
    static let prose: CGFloat = 640
    static let buttonHeight: CGFloat = 56
    static let tile: CGFloat = 64
    static let symbol: CGFloat = 30
    /// A tile turns and shrinks this much by the time its page has slid a whole page away.
    static let tileTurnDegrees: CGFloat = 40
    static let tileShrink: CGFloat = 0.2

    /// iPad: what the buttons' row adds under itself so it ends above the dock's glass (the nav capsule
    /// and the mini player, `PadDockMetrics.bottom + height` from the window's edge), given the page's
    /// bottom safe area. A column that already pads the page past the dock needs nothing more.
    static func padDockPadding(bottomInset: CGFloat) -> CGFloat {
        max(0, PadDockMetrics.bottom + PadDockMetrics.height - bottomInset)
    }

    /// Whether the page was opened as a route (it is the path's last entry), so back pops the path.
    static func closesThroughPath(_ path: [Route], start: String) -> Bool {
        path.last == .tip(start)
    }

    /// How far page [index] is from where the pager stands, −1…1 (0: on screen; positive once the pager
    /// has moved on past it).
    static func pageOffset(position: CGFloat, index: Int) -> CGFloat {
        min(max(position - CGFloat(index), -1), 1)
    }
}

/// Where the pager stands, in pages (2.5: halfway from the third tip to the fourth). The tiles read it.
@MainActor
@Observable
final class TipPagerState {
    var position: CGFloat = 0
}

/// One tip: how it is done (the demo phone, where the tip has one), its symbol on an Expressive shape beside
/// the title, then the text.
private struct TipPage: View {
    var tip: Tip
    var index: Int
    var settled: Bool
    var pager: TipPagerState
    var scheme: CoverScheme

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                if TipIllustration.has(tip.id) {
                    TipIllustration(tip: tip, playing: settled, scheme: scheme)
                        .padding(.bottom, 28)
                }
                HStack(spacing: 16) {
                    TipTile(tip: tip, index: index, settled: settled, pager: pager, scheme: scheme)
                    Text(tip.shownTitle)
                        .homeType(Tokens.type.headlineMedium)
                        .foregroundStyle(scheme.onSurface)
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .accessibilityAddTraits(.isHeader)
                }
                Text(tip.shownText)
                    .homeType(Tokens.type.bodyLarge, .regular)
                    .foregroundStyle(scheme.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 16)
                Text(Tips.updatedLine(tip))
                    .yoinRole(Tokens.type.labelLarge)
                    .foregroundStyle(scheme.outline)
                    .padding(.top, 12)
            }
            .frame(maxWidth: TipMetrics.prose, alignment: .leading)
            .padding(24)
            .frame(maxWidth: .infinity)
        }
        .scrollBounceBehavior(.basedOnSize)
        // A tip taller than its page (a long line, a large text size) scrolls on until the date clears the
        // buttons; its bar shows (the pager's is hidden) and flashes when the page comes to rest, so a line
        // cut at the foot reads as more to scroll, not as text behind Read / Next.
        .scrollIndicators(.automatic, axes: .vertical)
        .scrollIndicatorsFlash(onAppear: true)
        .scrollIndicatorsFlash(trigger: settled)
    }
}

/// The tip's symbol on its shape. Swiping drives it straight from the pager: it turns and shrinks a little
/// as its page slides away; arriving, the shape grows in from the previous tip's on the spatial spring.
private struct TipTile: View {
    var tip: Tip
    var index: Int
    var settled: Bool
    var pager: TipPagerState
    var scheme: CoverScheme
    @State private var grown: Bool

    init(tip: Tip, index: Int, settled: Bool, pager: TipPagerState, scheme: CoverScheme) {
        self.tip = tip
        self.index = index
        self.settled = settled
        self.pager = pager
        self.scheme = scheme
        _grown = State(initialValue: settled)
    }

    var body: some View {
        let offset = TipMetrics.pageOffset(position: pager.position, index: index)
        YoinIcon(tip.icon, size: TipMetrics.symbol)
            .foregroundStyle(scheme.onPrimaryContainer)
            .frame(width: TipMetrics.tile, height: TipMetrics.tile)
            .background(scheme.primaryContainer, in: TipTileShape.shape(grown ? index : index - 1))
            .rotationEffect(.degrees(offset * TipMetrics.tileTurnDegrees))
            .scaleEffect(1 - abs(offset) * TipMetrics.tileShrink)
            .onChange(of: settled) { _, here in
                if here {
                    withAnimation(Yoin.spatial) { grown = true }
                } else {
                    var t = Transaction()
                    t.disablesAnimations = true
                    withTransaction(t) { grown = false }
                }
            }
            .accessibilityHidden(true)
    }
}

/// A different silhouette per tip, so paging reads as moving on (Android tipPolygon: Cookie9Sided,
/// Clover4Leaf, Sunny, Cookie6Sided, Gem, Pentagon), as the scalloped circle the account avatars are cut
/// from, so one morphs into the next.
enum TipTileShape {
    static let count = 6

    /// (lobes, depth) of tip [index]'s silhouette.
    static func lobes(_ index: Int) -> (lobes: CGFloat, depth: CGFloat) {
        switch ((index % count) + count) % count {
        case 0: (9, 0.14)
        case 1: (4, 0.30)
        case 2: (8, 0.22)
        case 3: (6, 0.16)
        case 4: (6, 0.07)
        default: (5, 0.12)
        }
    }

    static func shape(_ index: Int) -> AvatarShape {
        var s = AvatarShape(index: 0)
        (s.lobes, s.depth) = lobes(index)
        return s
    }
}
