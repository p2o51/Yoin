import SwiftUI

/// Root sections. Switching uses a spatial spring and a short slide.
struct SectionContent: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        ZStack {
            switch model.section {
            case .home:
                HomeView()
                    .transition(.asymmetric(insertion: .move(edge: .leading).combined(with: .opacity),
                                            removal: .move(edge: .leading).combined(with: .opacity)))
            case .library:
                LibraryView()
                    .transition(.asymmetric(insertion: .move(edge: .trailing).combined(with: .opacity),
                                            removal: .move(edge: .trailing).combined(with: .opacity)))
            }
        }
        // The bar's safeAreaBar doesn't reach the root pages' scroll views
        // inside the stack, so the last row ended under it. Reserve the bar
        // (60 + 2 bottom padding) here, as Android pads its root lists.
        .safeAreaPadding(.bottom, ShellBarMetrics.clearance)
        .background(Yoin.surface)
    }
}

/// The phone bar's height plus its bottom padding: what a scroll view under
/// it must leave free so its last row isn't covered. Pushed pages scroll
/// under the bar too (the safe-area bar doesn't reach into the stack).
enum ShellBarMetrics {
    static let clearance: CGFloat = 62
}

/// Phone: one NavigationStack, the glass bar floats on top, Now Playing is a
/// shell overlay whose glass morphs out of the bar's pill and back into it
/// (NowPlayingPresentation: one progress for ⌄, `npExpanded` and the drag).
struct PhoneShell: View {
    @Environment(AppModel.self) private var model
    @Namespace private var zoomNS
    @Namespace private var npNS
    @State private var searchResident = false
    @State private var npResident = false

    private var phoneScrollHideEnabled: Bool {
        model.section == .home
            && model.path.isEmpty
            && !model.npExpanded
            && !model.searchActive
            && !model.memories.isOpen
            && model.homeEdit?.isEditing != true
            && model.player.failure == nil
    }

    var body: some View {
        @Bindable var model = model
        ZStack(alignment: .bottom) {
            NavigationStack(path: $model.path) {
                SectionContent()
                    .toolbar(.hidden, for: .navigationBar)
                    .navigationDestination(for: Route.self) { route in
                        switch route {
                        case .album(let album):
                            AlbumDetailView(album: album)
                                .zoomTransition(model.zoomSourceID(album.id), zoomNS)
                        case .artist(let ref):
                            ArtistDetailView(ref: ref)
                        case .playlist(let id):
                            let info = model.playlistInfo(id)
                            AlbumDetailView(album: info.asAlbum, playlist: info)
                                .task(id: "\(model.store.generation)|\(id)") { await model.store.loadPlaylist(id) }
                        case .settings:
                            ServerSettingsView()
                        case .tips(let focus):
                            TipsView(focus: focus)
                        case .tip(let id):
                            TipView(start: id)
                        case .allMemories:
                            AllMemoriesView()
                        case .labs:
                            LabsView()
                        case .neoDBVoices(let album):
                            NeoDBVoicesView(album: album)
                        }
                    }
            }
            .environment(\.zoomNamespace, zoomNS)
            // A safe-area bar, not an overlay, so scroll views know it is
            // there: the system soft scroll-edge effect (progressive blur and
            // fade) runs under it, as under Apple's tab bars.
            .safeAreaBar(edge: .bottom) {
                ShellBar(npNamespace: npNS)
                    .padding(.horizontal, 14)
                    .padding(.bottom, 2)
                    // The band spans the screen however narrow the bar is. With nothing playing, a
                    // detail page's bar is the Play split alone, and a band sized to the bar was a
                    // light box with hard sides. The bar itself still gets the same proposal.
                    .frame(maxWidth: .infinity)
                    .background {
                        if GlassLab.solidSurfaces {
                            SolidBarEdgeBlur()
                                // The bar region adapts to dark content under it; the band keeps the app's look.
                                .environment(\.colorScheme, GlassLab.solidColorScheme(.light))
                                .padding(.top, -36)
                                .ignoresSafeArea(edges: [.bottom, .horizontal])
                        }
                    }
                    // Slides away under the keyboard while searching.
                    .offset(y: model.searchActive ? 140 : 0)
                    .opacity(model.searchActive ? 0 : 1)
                    // Memories (pulled down over Home) takes the whole screen; scrolling Home hides it smoothly.
                    .modifier(MemoriesBarRetreat(presentation: model.memories,
                                                 active: model.section == .home && model.path.isEmpty,
                                                 scrollHide: model.section == .home && model.path.isEmpty ? model.barScrollHide.fraction : 0))
                    .onChange(of: phoneScrollHideEnabled, initial: true) { _, on in
                        model.barScrollHide.enabled = on
                    }
            }
            // Root Home / Library have no inline keyboard inputs (Memories ignores keyboard itself),
            // and while Search is active its keyboard belongs to SearchView rather than the stack behind it.
            .ignoresSafeArea(model.searchActive || model.path.isEmpty ? .keyboard : [], edges: .bottom)
            // World: the shell steps back (scale, dim, blur) as Now Playing
            // unfolds over it or a sheet comes up, off the same progress.
            .modifier(WorldRecede(layer: .shell))

            if searchResident || model.searchActive || model.searchWarming {
                SearchView(npNamespace: npNS)
                    // In: SearchView stages itself immediately (circle stand-in at 1, surface + chips fade in).
                    // Out: one fade while the field folds back, keeping the view warm in the hierarchy.
                    .opacity(model.searchActive ? 1 : 0)
                    .animation(model.searchActive ? nil : Yoin.effects, value: model.searchActive)
                    .allowsHitTesting(model.searchActive)
                    .accessibilityHidden(!model.searchActive)
                    .zIndex(0.5)
                    .transition(.identity)
            }

            if (npResident && !model.isIdle) || model.np.mounted {
                // Kept resident once warmed so subsequent opens skip rebuilding the view hierarchy;
                // hidden and non-interactive whenever `np.mounted` is false.
                NowPlayingMorph(npNamespace: npNS)
                    .modifier(WorldRecede(layer: .nowPlaying))
                    .opacity(model.np.mounted ? 1 : 0)
                    .allowsHitTesting(model.np.mounted)
                    .accessibilityHidden(!model.np.mounted)
                    .zIndex(model.np.mounted ? 1 : -1)
                    .transition(.identity)
            }
        }
        .onChange(of: model.searchActive || model.searchWarming, initial: true) { _, on in
            if on && !searchResident { searchResident = true }
        }
        .onChange(of: model.np.mounted, initial: true) { _, on in
            if on && !npResident { npResident = true }
        }
        .onChange(of: model.isIdle) { _, idle in
            if idle {
                npResident = false
                if model.npExpanded { model.setNowPlaying(expanded: false) }
            }
        }
        // The plane behind a receding layer is the app surface, never black
        // (labRecede draws it too): black read as a flash at the corners.
        .background(Yoin.surface.ignoresSafeArea())
        .overlay(alignment: .top) {
            // Home's top under the status bar (Library's and Search's headers
            // carry their own). The system scroll-edge effect only runs under
            // a real bar, and Home has none (its header scrolls), so this is
            // the album page's top over the status bar (StatusEdgeBand).
            // Off the morph's own progress (a frame of the fold fades it in
            // step), not an insert on `npExpanded`, which landed in one frame
            // at the end of the close.
            if model.path.isEmpty {
                StatusEdgeBand()
                    .opacity(model.islandOut ? 0 : 1)
                    .animation(Yoin.effects, value: model.islandOut)
            }
        }
        .onChange(of: model.npExpanded) { _, open in model.np.show(open) }
        .onAppear {
            if model.npExpanded { model.np.snapOpen() }
        }
        .task {
            // `-screen np-morph` / `np-morph-close`: a deliberately slow
            // pill → page (or page → pill) morph, for frame-by-frame review.
            guard model.slowMorph else { return }
            try? await Task.sleep(for: .seconds(1))
            model.setNowPlaying(expanded: !model.npExpanded)
        }
        .task { QAProbe.start(model) }
        #if DEBUG
        .task { await NowPlayingMorphDebug.run(model) }
        #endif
    }
}

/// Android YoinNavHost: the bar group slides down and fades out with the
/// Memories reveal (1 - q), so it leaves on the pull itself rather than when
/// Memories settles, and comes back the same way. A page pushed from Memories
/// gets its bar back (Android's detail Activity brings its own).
/// A modifier of its own so a frame of q re-renders only this, not the shell.
struct MemoriesBarRetreat: ViewModifier {
    let presentation: MemoriesPresentation
    var active: Bool
    var scrollHide: CGFloat = 0

    func body(content: Content) -> some View {
        let memoriesHide = active ? min(1, max(0, 1 - presentation.fraction)) : 0
        let hide = min(1, max(memoriesHide, max(0, scrollHide)))
        // Gone for good once Memories is all the way open: an empty bar slot. The safe-area bar's own
        // container keeps taking touches in its band even with its content's hit testing off, and Memories'
        // Diary / Go to album sit in that band, so with the bar merely hidden they never got a tap
        // (51, 2026-11-16). It comes back on the first frame of the retreat.
        if memoriesHide >= 1 {
            Color.clear.frame(height: 0)
        } else {
            content
                .offset(y: hide * 120)
                .opacity(Double(max(0, 1 - hide * 1.4)))
                .allowsHitTesting(hide < 0.5)
                .animation(Yoin.standardDefaultSpatial, value: active)
        }
    }
}

/// Home's top under the status bar. The system scroll-edge effect only runs
/// under a real bar, and Home has none (its header scrolls with the feed), so
/// this is the album page's top there (TopEdgeBlur, 51 2026-11-14): its strong
/// progressive blur in Home's top colour, solid over the status bar and easing
/// out over [tail] below it. It comes in once the feed has scrolled up under the status
/// bar (at rest it would frost the greeting) and steps aside for Library and
/// Search, whose headers carry their own. Hidden under Now Playing, by the
/// morph's progress (read here, so its frames re-render the band only).
struct StatusEdgeBand: View {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme

    /// How far below the status bar it falls off: long enough to read as soft (51, 2026-11-15: 22 was short
    /// and showed as an edge in dark).
    static let tail: CGFloat = 44

    var body: some View {
        let shown = model.np.mounted ? 1 - min(max(model.np.progress, 0), 1) : (model.npExpanded ? 0 : 1)
        let home = model.section == .home && !model.searchActive && model.homeScrolledUnder
        GeometryReader { g in
            let top = g.safeAreaInsets.top
            // Home's own colour (HomeView's scheme and page wash).
            ProgressiveEdgeBlur(tint: CoverScheme(seed: model.accent, dark: colorScheme == .dark).surfaceContainer,
                                strong: true, fadeFrom: Double(top / (top + Self.tail)))
                .frame(height: top + Self.tail)
                .ignoresSafeArea(edges: .top)
                .opacity(home ? 1 : 0)
                .animation(Yoin.effects, value: home)
        }
        .opacity(shown)
        .allowsHitTesting(false)
    }
}

/// World depth cue (Glass Lab recedeScale / recedeDim / recedeBlur): the
/// layer behind whatever sits on top steps back. The shell recedes with Now
/// Playing's morph progress (one controller: ⌄, drag and back all move it)
/// and under a sheet; Now Playing recedes under a sheet opened over it.
/// Reads the progress here, so a frame of the morph re-renders this modifier
/// only, not the shell.
struct WorldRecede: ViewModifier {
    /// `shell`: the phone's shell and the iPad's (both under the pill ⇄ page morph).
    enum Layer { case shell, nowPlaying }
    @Environment(AppModel.self) private var model
    var layer: Layer

    func body(content: Content) -> some View {
        let lab = GlassLab.shared
        if lab.extra("recedeScale") > 0 || lab.extra("recedeDim") > 0 || lab.extra("recedeBlur") > 0 {
            // Both depths are read straight off what sits on top, frame by
            // frame, with no animation of their own: the morph's progress, and
            // the sheet's rise (SheetRise: its own presentation, dismissal and
            // drag). A separate spring here ran out of step with the sheet and
            // landed in one frame.
            let underNP = layer == .shell && model.np.mounted
            let depth: CGFloat = switch layer {
            case .shell: underNP ? min(max(model.np.progress, 0), 1) : SheetRise.shared.progress
            case .nowPlaying: model.npExpanded ? SheetRise.shared.progress : 0
            }
            // Unanimated only for the recede itself: a whole-layer
            // `.transaction { $0.animation = nil }` also cut every animation
            // inside the shell (the bar's slide, the search morph).
            content
                .animation(nil) { $0.labRecede(depth, sheet: !underNP) }
        } else {
            content
        }
    }
}

/// How far the song sheet, Add to playlist or the Queue is up: 0 down, 1 at
/// its detent. Read off the sheet's own presentation layer every frame by a
/// SheetRiseReporter inside it, so the world behind steps back exactly with
/// the system sheet: as it presents, as it dismisses, under a finger.
@MainActor @Observable
final class SheetRise {
    static let shared = SheetRise()
    private(set) var progress: CGFloat = 0
    @ObservationIgnored private var sources: [ObjectIdentifier: CGFloat] = [:]

    func report(_ source: ObjectIdentifier, _ value: CGFloat?) {
        sources[source] = value
        let p = sources.values.max() ?? 0
        // Ends are always written; in between, sub-pixel steps are skipped so UIKit's
        // sheet backdrop and WorldRecede don't thrash Gaussian blur passes at 120 Hz.
        if p != progress && (p == 0 || p == 1 || abs(p - progress) > 0.008) { progress = p }
    }
}

extension View {
    /// Put inside a sheet's content: reports the sheet's rise (SheetRise).
    func reportsSheetRise() -> some View { background(SheetRiseReporter()) }
}

private struct SheetRiseReporter: UIViewRepresentable {
    func makeUIView(context: Context) -> SheetRiseView { SheetRiseView() }
    func updateUIView(_ uiView: SheetRiseView, context: Context) {}
}

/// The sheet's top as drawn this frame (its presentation layer) against
/// where it rests. The rest is the sheet's own frame, except while a finger
/// is on it: UIKit moves the frame itself under a drag, which would read as
/// "at rest" all the way down.
final class SheetRiseView: UIView {
    private static let activeFrameRate = CAFrameRateRange(minimum: 60, maximum: 120, preferred: 120)
    private static let idleFrameRate = CAFrameRateRange(minimum: 10, maximum: 15, preferred: 15)

    private var link: CADisplayLink?
    private var rest: CGFloat?
    private weak var sheet: UIView?
    private weak var controller: UIViewController?
    private var sheetScrollViews: [WeakScrollViewRef] = []
    private var lastScrollScanAt: CFTimeInterval = 0
    /// The sheet's rise has been seen under way (or it had none to show):
    /// until then a frame read before UIKit attaches the presentation
    /// animation (model = drawn, at rest) would report a full rise for one
    /// frame.
    private var armed = false
    private var attachedAt: CFTimeInterval = 0
    private var settledSince: CFTimeInterval?
    private var isIdleFrameRate = false

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        backgroundColor = .clear
    }

    required init?(coder: NSCoder) { fatalError() }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        link?.invalidate()
        link = nil
        settledSince = nil
        isIdleFrameRate = false
        if window != nil {
            let l = CADisplayLink(target: self, selector: #selector(tick))
            l.preferredFrameRateRange = Self.activeFrameRate
            l.add(to: .main, forMode: .common)
            link = l
            armed = false
            attachedAt = CACurrentMediaTime()
        } else {
            rest = nil
            SheetRise.shared.report(ObjectIdentifier(self), nil)
        }
    }

    /// The presented view the sheet controller moves.
    private func findSheet() -> UIView? {
        if let sheet { return sheet }
        var r: UIResponder? = self
        while let next = r?.next {
            if let vc = next as? UIViewController, vc.presentingViewController != nil,
               let v = vc.presentationController?.presentedView {
                sheet = v
                controller = vc
                return v
            }
            r = next
        }
        return nil
    }

    private func dragged(_ v: UIView) -> Bool {
        var view: UIView? = v
        while let cur = view {
            if cur.gestureRecognizers?.contains(where: { $0 is UIPanGestureRecognizer
                && ($0.state == .began || $0.state == .changed) }) == true { return true }
            view = cur.superview
        }
        // Any scroll view inside the sheet may hand its pan to the sheet at the top.
        let now = CACurrentMediaTime()
        if sheetScrollViews.isEmpty || now - lastScrollScanAt > 0.5 || sheetScrollViews.contains(where: { $0.view?.window == nil }) {
            var found: [WeakScrollViewRef] = []
            v.collectDescendantScrollViews(into: &found)
            sheetScrollViews = found
            lastScrollScanAt = now
        }
        return sheetScrollViews.contains { ref in
            guard let st = ref.view?.panGestureRecognizer.state else { return false }
            return st == .began || st == .changed
        }
    }

    @objc private func tick() {
        guard let window, let sheet = findSheet() else { return }
        let nowTime = CACurrentMediaTime()
        let isDismissing = controller?.isBeingDismissed == true
        let model = sheet.convert(sheet.bounds, to: window).minY
        let wl = window.layer.presentation() ?? window.layer
        let pl = sheet.layer.presentation() ?? sheet.layer
        let now = pl.convert(pl.bounds, to: wl).minY
        let needsDragCheck = !isDismissing && ((rest.map { abs(model - $0) > 0.5 } ?? false) || armed)
        let isDragged = needsDragCheck ? dragged(sheet) : false
        var detentChanged = false
        // Only when the frame moved for a detent change: not under a drag,
        // and not as it leaves (UIKit sets the frame off screen at once).
        if let r = rest, abs(model - r) > 0.5, !isDismissing,
           model < window.bounds.height - 1, !isDragged {
            rest = model
            detentChanged = true
        }
        if rest == nil { rest = model }
        let bottom = window.bounds.height
        guard let rest, bottom - rest > 1 else { return }
        let p = min(max((bottom - now) / (bottom - rest), 0), 1)
        if !armed {
            guard p < 0.98 || nowTime - attachedAt > 0.3 else { return }
            armed = true
        }
        SheetRise.shared.report(ObjectIdentifier(self), p)

        // Drop display-link polling to 10-15 Hz once settled at rest so an idle
        // sheet does not pin ProMotion at 120 Hz; restore 120 Hz immediately on motion.
        if armed && p >= 0.999 && !isDragged && !isDismissing && !detentChanged {
            if let since = settledSince {
                if !isIdleFrameRate && nowTime - since > 0.25 {
                    link?.preferredFrameRateRange = Self.idleFrameRate
                    isIdleFrameRate = true
                }
            } else {
                settledSince = nowTime
            }
        } else {
            settledSince = nil
            if isIdleFrameRate {
                link?.preferredFrameRateRange = Self.activeFrameRate
                isIdleFrameRate = false
            }
        }
    }
}

private extension UIView {
    func collectDescendantScrollViews(into out: inout [SheetRiseView.WeakScrollViewRef]) {
        for sub in subviews {
            if let s = sub as? UIScrollView { out.append(.init(view: s)) }
            sub.collectDescendantScrollViews(into: &out)
        }
    }
}

extension SheetRiseView {
    struct WeakScrollViewRef { weak var view: UIScrollView? }

    static func descendantScrollViews(in root: UIView) -> [UIScrollView] {
        var found: [WeakScrollViewRef] = []
        root.collectDescendantScrollViews(into: &found)
        return found.compactMap(\.view)
    }
}

#if DEBUG
/// Recording aids. `-npAutoCycle YES`: open after 1 s, close after 3 s
/// (`-npAutoCycleHold`), twice.
/// `-npScriptedDrag YES`: open, then a scripted finger drags the page down
/// 40 % of the way and lets go (it folds into the pill), then opens again
/// and a short drag is let go (it springs back).
@MainActor
enum NowPlayingMorphDebug {
    static func run(_ model: AppModel) async {
        let d = UserDefaults.standard
        if d.bool(forKey: "npAutoCycle") {
            // `-npAutoCycleHold 6`: stay open longer (e.g. for `-npTab about-full`).
            let hold = d.double(forKey: "npAutoCycleHold") > 0 ? d.double(forKey: "npAutoCycleHold") : 3
            for _ in 0..<2 {
                try? await Task.sleep(for: .seconds(1))
                model.setNowPlaying(expanded: true)
                try? await Task.sleep(for: .seconds(hold))
                model.setNowPlaying(expanded: false)
                try? await Task.sleep(for: .seconds(1))
            }
        }
        if d.bool(forKey: "npScriptedDrag") {
            try? await Task.sleep(for: .seconds(1))
            model.setNowPlaying(expanded: true)
            try? await Task.sleep(for: .seconds(2))
            await drag(model, to: 0.4, release: 900)
            try? await Task.sleep(for: .seconds(2))
            model.setNowPlaying(expanded: true)
            try? await Task.sleep(for: .seconds(2))
            await drag(model, to: 0.12, release: 0)
        }
    }

    /// A finger dragging down `fraction` of the fold distance over 0.5 s
    /// (ease-out), holding briefly, then lifting at `release` pt/s.
    private static func drag(_ model: AppModel, to fraction: CGFloat, release: CGFloat) async {
        let np = model.np
        let total = fraction * np.dismissDistance
        let start = Date()
        while true {
            let t = min(1, CGFloat(Date().timeIntervalSince(start) / 0.5))
            np.dragChanged(12 + (total - 12) * (1 - pow(1 - t, 2)))
            if t >= 1 { break }
            try? await Task.sleep(for: .milliseconds(8))
        }
        try? await Task.sleep(for: .milliseconds(120))
        if np.dragEnded(total, velocity: release, predicted: total + release * 0.25) {
            model.setNowPlaying(expanded: false)
        }
    }
}
#endif
