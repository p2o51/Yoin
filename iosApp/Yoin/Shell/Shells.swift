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
                                .navigationTransition(.zoom(sourceID: album.id, in: zoomNS))
                        case .artist(let ref):
                            ArtistDetailView(ref: ref)
                        case .playlist(let id):
                            let info = model.playlistInfo(id)
                            AlbumDetailView(album: info.asAlbum, playlist: info)
                                .task(id: "\(model.store.generation)|\(id)") { await model.store.loadPlaylist(id) }
                        case .settings:
                            ServerSettingsView()
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
                    // Slides away under the keyboard while searching.
                    .offset(y: model.searchActive ? 140 : 0)
                    .opacity(model.searchActive ? 0 : 1)
                    // Memories (pulled down over Home) takes the whole screen.
                    .modifier(MemoriesBarRetreat(presentation: model.memories,
                                                 active: model.section == .home && model.path.isEmpty))
            }
            // World: the shell steps back (scale, dim, blur) as Now Playing
            // unfolds over it or a sheet comes up, off the same progress.
            .modifier(WorldRecede(layer: .shell))

            if model.searchActive || model.searchWarming {
                SearchView(npNamespace: npNS)
                    // Warming: built, unseen and untouchable; it shows at once on the tap.
                    .opacity(model.searchActive ? 1 : 0)
                    .animation(nil, value: model.searchWarming)
                    .allowsHitTesting(model.searchActive)
                    .zIndex(0.5)
                    // In: SearchView stages itself (the field grows out of
                    // the bar's circle opaque; surface, then chips, fade in).
                    // Out: one fade while the field folds back.
                    .transition(.asymmetric(insertion: .identity, removal: .opacity))
            }

            if model.np.mounted {
                // Mounted and unmounted without a transition: the morph's
                // progress carries the whole in and out.
                NowPlayingMorph(npNamespace: npNS)
                    .modifier(WorldRecede(layer: .nowPlaying))
                    .zIndex(1)
                    .transition(.identity)
            }
        }
        // The plane behind a receding layer is the app surface, never black
        // (labRecede draws it too): black read as a flash at the corners.
        .background(Yoin.surface.ignoresSafeArea())
        .overlay(alignment: .top) {
            // Soft top edge under the status bar. The system scroll-edge
            // effect only runs under a real bar, and the root pages hide the
            // navigation bar (custom headers), so this approximates it: a
            // material band that fades out downward, like Apple Music's top.
            // Off the morph's own progress (a frame of the fold fades it in
            // step), not an insert on `npExpanded`, which landed in one frame
            // at the end of the close.
            if model.path.isEmpty {
                StatusEdgeBand()
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

    func body(content: Content) -> some View {
        let hide = active ? min(1, max(0, 1 - presentation.fraction)) : 0
        content
            .offset(y: hide * 120)
            .opacity(Double(max(0, 1 - hide * 1.4)))
            .allowsHitTesting(hide < 0.5)
            .animation(Yoin.standardDefaultSpatial, value: active)
    }
}

/// Soft top edge under the status bar. The system scroll-edge effect only
/// runs under a real bar, and the root pages hide the navigation bar (custom
/// headers), so this approximates it: a material band that fades out
/// downward, like Apple Music's top. Hidden under Now Playing, by the morph's
/// progress (read here, so its frames re-render the band only).
private struct StatusEdgeBand: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let shown = model.np.mounted ? 1 - min(max(model.np.progress, 0), 1) : (model.npExpanded ? 0 : 1)
        GeometryReader { g in
            Rectangle()
                .fill(.ultraThinMaterial)
                .mask(LinearGradient(stops: [.init(color: .black, location: 0),
                                             .init(color: .black, location: 0.45),
                                             .init(color: .clear, location: 1)],
                                     startPoint: .top, endPoint: .bottom))
                .frame(height: g.safeAreaInsets.top + 22)
                .ignoresSafeArea(edges: .top)
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
    /// `padShell`: the iPad shell, which has no morph progress. It recedes
    /// under a sheet (the sheet's rise, as on the phone) and under the Full
    /// Now Playing overlay (on the spatial spring that brings that in).
    enum Layer { case shell, nowPlaying, padShell }
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
            let rise = SheetRise.shared.progress
            let underNP = layer == .shell && model.np.mounted
            let depth: CGFloat = switch layer {
            case .shell: underNP ? min(max(model.np.progress, 0), 1) : rise
            case .nowPlaying: model.npExpanded ? rise : 0
            case .padShell: model.npExpanded ? 0 : rise
            }
            // Unanimated only for the recede itself: a whole-layer
            // `.transaction { $0.animation = nil }` also cut every animation
            // inside the shell (the bar's slide, the search morph).
            if layer == .padShell {
                content
                    .animation(nil) { $0.labRecede(depth, sheet: true) }
                    .animation(Yoin.spatial) { $0.labRecede(model.npExpanded ? 1 : 0) }
            } else {
                content
                    .animation(nil) { $0.labRecede(depth, sheet: !underNP) }
            }
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
        // Ends are always written; in between, sub-pixel steps are skipped.
        if p != progress && (p == 0 || p == 1 || abs(p - progress) > 0.002) { progress = p }
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
    private var link: CADisplayLink?
    private var rest: CGFloat?
    private weak var sheet: UIView?
    private weak var controller: UIViewController?
    /// The sheet's rise has been seen under way (or it had none to show):
    /// until then a frame read before UIKit attaches the presentation
    /// animation (model = drawn, at rest) would report a full rise for one
    /// frame.
    private var armed = false
    private var attachedAt: CFTimeInterval = 0

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
        if window != nil {
            let l = CADisplayLink(target: self, selector: #selector(tick))
            l.preferredFrameRateRange = CAFrameRateRange(minimum: 60, maximum: 120, preferred: 120)
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
        // A scroll view inside the sheet hands its pan to the sheet at the top.
        return v.firstScrollViewPanActive()
    }

    @objc private func tick() {
        guard let window, let sheet = findSheet() else { return }
        let model = sheet.convert(sheet.bounds, to: window).minY
        let wl = window.layer.presentation() ?? window.layer
        let pl = sheet.layer.presentation() ?? sheet.layer
        let now = pl.convert(pl.bounds, to: wl).minY
        // Only when the frame moved for a detent change: not under a drag,
        // and not as it leaves (UIKit sets the frame off screen at once).
        if let r = rest, abs(model - r) > 0.5, controller?.isBeingDismissed != true,
           model < window.bounds.height - 1, !dragged(sheet) { rest = model }
        if rest == nil { rest = model }
        let bottom = window.bounds.height
        guard let rest, bottom - rest > 1 else { return }
        let p = min(max((bottom - now) / (bottom - rest), 0), 1)
        if !armed {
            guard p < 0.98 || CACurrentMediaTime() - attachedAt > 0.3 else { return }
            armed = true
        }
        SheetRise.shared.report(ObjectIdentifier(self), p)
    }
}

private extension UIView {
    func firstScrollViewPanActive() -> Bool {
        for sub in subviews {
            if let s = sub as? UIScrollView, s.panGestureRecognizer.state == .began || s.panGestureRecognizer.state == .changed {
                return true
            }
            if sub.firstScrollViewPanActive() { return true }
        }
        return false
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
