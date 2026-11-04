import SwiftUI
import UIKit

/// Phone Now Playing presentation: the bar's glass pill morphs into the page
/// and back, iOS 26 Apple Music style.
///
/// One displacement state (`progress`, 0 = folded into the pill, 1 = the
/// full page) feeds everything: the ⌄ button and `npExpanded` spring it, the
/// drag-down moves it 1:1 and hands it to the same spring on release with
/// the finger's velocity (AGENTS.md ShellOverlayDown: one controller). The
/// spring is read every frame (not `withAnimation`), so every layer reads
/// the same value: one glass shape whose frame and corners go from the pill's
/// rect to the screen, the page laid out at full size inside it (scaled to
/// the shape's width, masked by it), and the cover and title flying from
/// the pill's frames to the page's.
@MainActor
@Observable
final class NowPlayingPresentation {
    /// 0 = the pill, 1 = the full page; above 1 only while dragged up (rubber band).
    private(set) var progress: CGFloat = 0
    /// The morph is in the tree (the bar hides its pill meanwhile).
    private(set) var mounted = false
    /// Away from rest at full size: the flying cover / title are drawn and
    /// the page's own are hidden.
    private(set) var inFlight = false
    private(set) var dragging = false

    /// Folding back into the pill on its own (not held). The bar's pill
    /// stays hidden until the spring settles, which is a while after the
    /// shape has visibly landed on it: a tap there meanwhile lands on the
    /// morph, so the morph takes it as the pill's tap and opens again.
    var closing: Bool { mounted && !dragging && target == 0 }

    /// Global frames of the bar's pill and its cover / title.
    var pillFrame: CGRect = .zero
    var pillCoverFrame: CGRect = .zero
    var pillTitleFrame: CGRect = .zero
    /// The page's cover / title in page space (= the screen at rest).
    /// Where the page's cover is drawn: the stage's own transforms (the
    /// expanded dock into the top bar, focus) sit outside the anchor, so
    /// they are in this frame, mid-animation too.
    var pageCoverFrame: CGRect = .zero
    /// The page cover's drawn corner radius (smaller while docked).
    var pageCoverRadius: CGFloat = Tokens.shape.NOW_PLAYING_COVER.cg
    var pageTitleFrame: CGRect = .zero

    /// `-screen np-morph`: a slow spring for frame-by-frame review.
    @ObservationIgnored var slow = false
    private var target: CGFloat = 0
    @ObservationIgnored private let spring = NowPlayingSpring()
    @ObservationIgnored private var dragFrom: CGFloat = 1

    /// The drag distance that folds the page all the way: the page's top
    /// edge follows the finger 1:1 down to the pill's top.
    var dismissDistance: CGFloat { max(pillFrame.minY, 300) }

    /// Spring to the page (true) or into the pill (false), from wherever the
    /// morph is, carrying `velocity` (progress per second).
    func show(_ open: Bool, velocity: CGFloat = 0) {
        let to: CGFloat = open ? 1 : 0
        if open && !mounted {
            progress = 0
            setMounted(true)
        }
        guard mounted else { return }
        if !dragging, target == to, spring.isRunning || progress == to { return }
        target = to
        dragging = false
        inFlight = true
        spring.run(from: progress, to: to, velocity: velocity, spring: springFor(open)) { [weak self] v in
            self?.progress = v
        } completion: { [weak self] in
            guard let self else { return }
            if to == 1 {
                inFlight = false
            } else {
                setMounted(false)
                progress = 0
            }
        }
    }

    /// Launched open (`-screen np`): no morph.
    func snapOpen() {
        spring.stop()
        target = 1
        progress = 1
        inFlight = false
        setMounted(true)
    }

    /// Finger moved: `ty` is the drag's vertical translation (global points).
    func dragChanged(_ ty: CGFloat) {
        guard mounted else { return }
        let d = dismissDistance
        if !dragging {
            spring.stop()
            dragging = true
            inFlight = true
            dragFrom = min(progress, 1)
        }
        // The page's top edge follows the finger 1:1 (it moves with
        // `rise`, not `progress`); past either end a rubber band.
        let raw = Self.rise(dragFrom) - ty / d
        if raw > 1 {
            progress = 1 + pow((raw - 1) * d, 0.7) / d
        } else if raw < 0 {
            progress = -pow(-raw * d, 0.7) / d
        } else {
            progress = 1 - (1 - raw).squareRoot()
        }
    }

    /// The shape's vertical progress: it leads the horizontal one (ease-out
    /// of `progress`), so the glass rises out of the pill as a tall card and
    /// the page inside it is cropped as little as possible.
    nonisolated static func rise(_ q: CGFloat) -> CGFloat {
        q > 1 ? q : 1 - (1 - q) * (1 - q)
    }

    /// Finger lifted. Returns true when the page folds away (the caller then
    /// clears `npExpanded`; the spring is already running).
    func dragEnded(_ ty: CGFloat, velocity vy: CGFloat, predicted: CGFloat) -> Bool {
        guard dragging else { return false }
        let close = (ty > 140 && vy > -200) || predicted > 380
        // Finger velocity moves `rise`; in `progress` units that is divided
        // by d rise / d progress = 2 (1 - progress).
        let q = min(max(progress, 0), 1)
        let velocity = -vy / dismissDistance / max(2 * (1 - q), 0.4)
        dragging = false
        show(!close, velocity: velocity)
        return close
    }

    private func setMounted(_ value: Bool) {
        var t = Transaction()
        t.disablesAnimations = true
        withTransaction(t) { mounted = value }
    }

    /// Open keeps the Expressive bounce (clamped at the page). Close lands
    /// critically damped at the same stiffness: same pace, no swing past
    /// the pill (the geometry clamps at 0 as well, for a fast fling).
    private func springFor(_ open: Bool) -> Spring {
        if slow { return Spring(response: 3, dampingRatio: 1) }
        if GlassLab.shared.sharedMotion {
            let s = Tokens.motion.expressiveDefaultSpatial
            let k = Double(s.stiffness)
            let ratio = open ? Double(s.damping) : max(1, Double(s.damping))
            return Spring(mass: 1, stiffness: k, damping: ratio * 2 * k.squareRoot())
        }
        return Spring(response: 0.42, dampingRatio: open ? 0.78 : 1)
    }
}

/// Runs a spring on one value, frame by frame, carrying the release velocity in.
@MainActor
final class NowPlayingSpring: NSObject {
    private var link: CADisplayLink?
    private var start: CFTimeInterval = 0
    private var from: CGFloat = 0
    private var delta: CGFloat = 0
    private var velocity: CGFloat = 0
    private var duration: TimeInterval = 0
    private var spring = Spring()
    private var apply: (CGFloat) -> Void = { _ in }
    private var completion: () -> Void = {}

    var isRunning: Bool { link != nil }

    func run(from: CGFloat, to: CGFloat, velocity: CGFloat, spring: Spring,
             apply: @escaping (CGFloat) -> Void, completion: @escaping () -> Void) {
        stop()
        self.from = from
        delta = to - from
        self.velocity = velocity
        self.spring = spring
        self.apply = apply
        self.completion = completion
        guard abs(delta) > 0.0005 || abs(velocity) > 0.01 else {
            apply(to)
            completion()
            return
        }
        duration = spring.settlingDuration(target: delta, initialVelocity: velocity, epsilon: 0.0005)
        // The clock starts at the first frame, not now: mounting the page
        // can take a frame or two, and the spring must not have run on
        // without being seen (the open would start half way).
        start = 0
        let link = CADisplayLink(target: self, selector: #selector(tick(_:)))
        link.preferredFrameRateRange = CAFrameRateRange(minimum: 60, maximum: 120, preferred: 120)
        link.add(to: .main, forMode: .common)
        self.link = link
    }

    func stop() {
        link?.invalidate()
        link = nil
    }

    @objc private func tick(_ link: CADisplayLink) {
        if start == 0 { start = link.timestamp }
        let t = link.targetTimestamp - start
        if t >= duration {
            stop()
            apply(from + delta)
            completion()
            return
        }
        apply(from + spring.value(target: delta, initialVelocity: velocity, time: t))
    }
}

// MARK: - The morph

/// The page coordinate space the cover / title anchors report in (outside
/// it sit only the morph's own scale and offset).
let nowPlayingPageSpace = "np.page"

struct NowPlayingMorph: View {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme
    var npNamespace: Namespace.ID

    private var lab: GlassLab { GlassLab.shared }
    private var scheme: CoverScheme { CoverScheme(album: model.playback.album, dark: colorScheme == .dark) }
    private var brand: CoverScheme { .brand(dark: colorScheme == .dark) }

    var body: some View {
        let np = model.np
        // The outer reader keeps the safe area (the page lays out inside it);
        // the inner one is the whole screen.
        GeometryReader { safe in
        let insets = safe.safeAreaInsets
        GeometryReader { geo in
            let origin = geo.frame(in: .global).origin
            let screen = CGRect(origin: .zero, size: geo.size)
            let pill = pillRect(np.pillFrame.offsetBy(dx: -origin.x, dy: -origin.y), in: screen)
            // Spring overshoot past the page is clamped (the page doesn't
            // leave the screen); only a drag lifts it (rubber band). Below 0
            // the geometry holds the pill's rect: the shape never folds
            // smaller than the pill it lands in (no squish at the close's end).
            let p = np.dragging ? np.progress : min(np.progress, 1)
            let q = clamp01(p)
            let lift = max(0, p - 1) * np.dismissDistance
            let h = NowPlayingPresentation.rise(q)
            let rect = CGRect(x: lerp(pill.minX, screen.minX, q), y: lerp(pill.minY, screen.minY, h) - lift,
                              width: lerp(pill.width, screen.width, q), height: lerp(pill.height, screen.height, h))
            let radius = max(0, lerp(pill.height / 2, fullRadius, clamp01(q)))
            let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)

            ZStack(alignment: .topLeading) {
                surface(shape: shape, rect: rect, q: q)

                // The pill's own content (artist, progress wash) rides the
                // shape's top edge and fades as the page takes over.
                PillContent(showCover: false, showTitle: false)
                    .frame(width: pill.width, height: pill.height)
                    .offset(x: rect.minX, y: rect.maxY - pill.height)
                    .opacity(1 - smooth(0, 0.22, q))
                    .allowsHitTesting(false)

                page(screen: screen, rect: rect, shape: shape, q: q, insets: insets)

                if np.inFlight {
                    flights(np: np, origin: origin, q: q, rect: rect, scale: rect.width / screen.width)
                        .mask(alignment: .topLeading) {
                            shape.frame(width: rect.width, height: rect.height)
                                .offset(x: rect.minX, y: rect.minY)
                        }
                        .allowsHitTesting(false)
                }

                if np.closing {
                    // Over the page: nothing on the folding page is live.
                    Color.clear
                        .contentShape(MorphHitShape(rect: rect, radius: radius))
                        .onTapGesture { model.setNowPlaying(expanded: true) }
                        .accessibilityHidden(true)
                }
            }
            .frame(width: screen.width, height: screen.height, alignment: .topLeading)
            .contentShape(MorphHitShape(rect: rect, radius: radius))
            .gesture(dismissDrag(np))
        }
        .ignoresSafeArea()
        }
    }

    // MARK: Layers

    /// One glass shape the whole way: the pill's glass at 0, the page's
    /// (glass over the neighbouring albums' colors) at 1.
    private func surface(shape: RoundedRectangle, rect: CGRect, q: CGFloat) -> some View {
        ZStack {
            // Its blurs overflow their frame: sized and clipped to the shape.
            NowPlayingAmbient(scheme: scheme)
                .frame(width: rect.width, height: rect.height)
                .clipShape(shape)
                // World: the folded pill already carries it (pillAmbient).
                .opacity(max(lab.extra("pillAmbient"), smooth(0.04, 0.55, q)))
            Color.clear
                .morphGlass(q: q, scheme: scheme, brand: brand, in: shape)
        }
        .frame(width: rect.width, height: rect.height)
        .offset(x: rect.minX, y: rect.minY)
        .allowsHitTesting(false)
    }

    /// The page at full size, scaled to the shape's width from its top
    /// leading corner and masked by it.
    private func page(screen: CGRect, rect: CGRect, shape: RoundedRectangle, q: CGFloat,
                      insets: EdgeInsets) -> some View {
        let s = max(0.01, rect.width / screen.width)
        return NowPlayingView(npNamespace: npNamespace)
            .environment(\.npMorph, model.np)
            .safeAreaPadding(insets)
            .frame(width: screen.width, height: screen.height)
            .coordinateSpace(.named(nowPlayingPageSpace))
            .scaleEffect(s, anchor: .topLeading)
            .offset(x: rect.minX, y: rect.minY)
            // Never quite 0: the page's first real render lands with the
            // mount, not as a hitch half way through the open.
            // Read off the rise (the shape's height). Objects transform (R5):
            // the page shrinks with the card and stays on it until the card
            // is nearly the pill (rise 0.2 ≈ progress 0.1), then hands over
            // to the pill's own content; fading from rise 0.75 down left an
            // empty frosted slab for a third of the fold.
            .opacity(max(0.002, smooth(0.04, 0.2, NowPlayingPresentation.rise(q))))
            .mask(alignment: .topLeading) {
                shape.frame(width: rect.width, height: rect.height)
                    .offset(x: rect.minX, y: rect.minY)
            }
    }

    /// Cover and title: from the pill's frames into the page, where they
    /// ride the scaled page from `attach` on (so they stay with the rating
    /// bar, the transport and the artist line).
    @ViewBuilder
    private func flights(np: NowPlayingPresentation, origin: CGPoint, q: CGFloat, rect: CGRect,
                         scale s: CGFloat) -> some View {
        let p = model.playback
        let w = smooth(0, Self.attach, q)
        let inPage = { (r: CGRect) in
            CGRect(x: rect.minX + r.minX * s, y: rect.minY + r.minY * s, width: r.width * s, height: r.height * s)
        }
        let fromCover = np.pillCoverFrame.offsetBy(dx: -origin.x, dy: -origin.y)
        if fromCover != .zero, np.pageCoverFrame != .zero {
            let r = lerp(fromCover, inPage(np.pageCoverFrame), w)
            CoverArt(palette: p.album.palette, cornerRadius: 0, seed: p.album.seed)
                .frame(width: max(0, r.width), height: max(0, r.height))
                .clipShape(.rect(cornerRadius: max(0, lerp(6, np.pageCoverRadius * s, w)),
                                 style: .continuous))
                .offset(x: r.minX, y: r.minY)
        }
        let fromTitle = np.pillTitleFrame.offsetBy(dx: -origin.x, dy: -origin.y)
        let toTitle = np.pageTitleFrame
        if fromTitle != .zero, toTitle != .zero {
            // The hero title is 36 pt, the pill's 15 pt: one size path for
            // both, crossfading the pill's semibold into the hero's regular.
            // It holds the pill's place until the page under it is showing,
            // then joins the page's title slot; kept inside the glass while
            // that slot is still below the shape's bottom edge.
            let fold: CGFloat = 15 / 36
            let wt = smooth(0.15, 0.55, q)
            let scale = lerp(fold, s, wt)
            let target = inPage(toTitle)
            let x = lerp(fromTitle.minX, target.minX, wt)
            let y = lerp(fromTitle.midY, min(target.midY, rect.maxY - toTitle.height * s), wt)
            Text(p.track.title)
                .font(.gsf(15, .semibold))
                .foregroundStyle(Yoin.ink)
                .labContrast("miniPlayer")
                .lineLimit(1)
                .frame(width: fromTitle.width, height: fromTitle.height, alignment: .leading)
                .scaleEffect(scale / fold, anchor: .leading)
                .offset(x: x, y: y - fromTitle.height / 2)
                .opacity(1 - smooth(0, Self.attach, q))
            Text(p.track.title)
                .font(.gsf(36))
                .foregroundStyle(scheme.onSurface)
                .scaleEffect(x: 1.03, y: 1, anchor: .leading)
                .lineLimit(1)
                .frame(width: toTitle.width, height: toTitle.height, alignment: .leading)
                .scaleEffect(scale, anchor: .leading)
                .offset(x: x, y: y - toTitle.height / 2)
                .opacity(smooth(0.05, Self.attach, q))
        }
    }

    /// Progress by which the cover and title have joined the page.
    private static let attach: CGFloat = 0.4

    // MARK: Drag

    private func dismissDrag(_ np: NowPlayingPresentation) -> some Gesture {
        DragGesture(minimumDistance: 12, coordinateSpace: .global)
            .onChanged { v in np.dragChanged(v.translation.height) }
            .onEnded { v in
                if np.dragEnded(v.translation.height, velocity: v.velocity.height,
                                predicted: v.predictedEndTranslation.height) {
                    model.setNowPlaying(expanded: false)
                }
            }
    }

    // MARK: Geometry

    /// Full page corners (Glass Lab; under the display's own radius).
    private var fullRadius: CGFloat { lab.style("npBackground").cornerRadius ?? 44 }

    /// The pill's rect; before it has reported one (or with the bar idle),
    /// a pill-sized capsule at the bottom center.
    private func pillRect(_ r: CGRect, in screen: CGRect) -> CGRect {
        if r.width > 1 { return r }
        return CGRect(x: screen.midX - 110, y: screen.maxY - 96, width: 220, height: 60)
    }
}

/// Hit area: the morph's current shape, so taps outside it reach the shell.
private struct MorphHitShape: Shape {
    var rect: CGRect
    var radius: CGFloat
    func path(in _: CGRect) -> Path {
        RoundedRectangle(cornerRadius: radius, style: .continuous).path(in: rect)
    }
}

private func lerp(_ a: CGFloat, _ b: CGFloat, _ t: CGFloat) -> CGFloat { a + (b - a) * t }

private func lerp(_ a: CGRect, _ b: CGRect, _ t: CGFloat) -> CGRect {
    CGRect(x: lerp(a.minX, b.minX, t), y: lerp(a.minY, b.minY, t),
           width: lerp(a.width, b.width, t), height: lerp(a.height, b.height, t))
}

private func clamp01(_ x: CGFloat) -> CGFloat { min(max(x, 0), 1) }

/// Smoothstep of `x` between `a` and `b`.
private func smooth(_ a: CGFloat, _ b: CGFloat, _ x: CGFloat) -> CGFloat {
    let t = clamp01((x - a) / (b - a))
    return t * t * (3 - 2 * t)
}

extension View {
    /// The morph's glass: the mini player's Glass Lab style at 0, the page
    /// background's at 1, one `glassEffect` throughout (only its tint moves).
    @ViewBuilder
    fileprivate func morphGlass(q: CGFloat, scheme: CoverScheme, brand: CoverScheme,
                                in shape: RoundedRectangle) -> some View {
        let lab = GlassLab.shared
        let a = lab.style("miniPlayer"), b = lab.style("npBackground")
        let ca = a.role.color(in: scheme, brand: brand, auto: scheme.primaryContainer)
        let cb = b.role.color(in: scheme, brand: brand, auto: scheme.background)
        let oa = a.tintOn ? a.tintOpacity : 0, ob = b.tintOn ? b.tintOpacity : 0
        let t = Double(clamp01(q))
        let tint = ca.mix(with: cb, by: t).opacity(oa + (ob - oa) * t)
        switch b.variant {
        case .regular, .clear:
            glassEffect((b.variant == .clear && t > 0.5 ? Glass.clear : Glass.regular).tint(tint), in: shape)
        case .none, .material:
            background {
                ZStack {
                    if b.variant == .material { shape.fill(.regularMaterial) }
                    shape.fill(tint)
                }
            }
        }
    }
}

// MARK: - Page anchors

private struct NowPlayingMorphKey: EnvironmentKey {
    static let defaultValue: NowPlayingPresentation? = nil
}

extension EnvironmentValues {
    /// Set on the page inside the phone morph (nil on iPad and in the panel).
    var npMorph: NowPlayingPresentation? {
        get { self[NowPlayingMorphKey.self] }
        set { self[NowPlayingMorphKey.self] = newValue }
    }
}

enum NowPlayingMorphAnchor { case cover, title }

extension View {
    /// The page's cover / title: reports where it is drawn to the morph (the
    /// flying copy lands on it, or leaves from it) and hides while the copy flies.
    func npMorphAnchor(_ anchor: NowPlayingMorphAnchor) -> some View {
        modifier(NowPlayingMorphAnchorModifier(anchor: anchor))
    }
}

private struct NowPlayingMorphAnchorModifier: ViewModifier {
    @Environment(\.npMorph) private var np
    var anchor: NowPlayingMorphAnchor

    func body(content: Content) -> some View {
        if let np {
            content
                .opacity(np.inFlight ? 0 : 1)
                .onGeometryChange(for: [CGFloat].self) { g in
                    let r = g.frame(in: .named(nowPlayingPageSpace))
                    return [r.minX, r.minY, r.width, r.height, g.size.width]
                } action: { v in
                    let r = CGRect(x: v[0], y: v[1], width: v[2], height: v[3])
                    switch anchor {
                    case .cover:
                        np.pageCoverFrame = r
                        np.pageCoverRadius = Tokens.shape.NOW_PLAYING_COVER.cg * r.width / max(v[4], 1)
                    case .title:
                        np.pageTitleFrame = r
                    }
                }
        } else {
            content
        }
    }
}

// MARK: - Background

/// Which colours frame Now Playing's background (shared rule with Android):
/// the top edge is the album of the queue item before the playing one, the
/// bottom edge the album of the item after it, in PLAY order (shuffled when
/// shuffle is on). Repeat-all wraps around the ends; otherwise an end with no
/// neighbour falls back to the playing cover's own scheme (top
/// secondaryContainer, bottom tertiaryContainer). A neighbour on the same
/// album is still a neighbour (its primaryContainer).
enum NowPlayingNeighbours {
    enum Edge: Equatable {
        /// The queue item at this index (play order): its album's primaryContainer.
        case item(Int)
        /// No neighbour: the playing scheme's secondaryContainer (top) / tertiaryContainer (bottom).
        case own
    }

    static func edges(count: Int, current: Int?, repeatAll: Bool) -> (top: Edge, bottom: Edge) {
        guard let i = current, count > 0, (0..<count).contains(i) else { return (.own, .own) }
        let before = i - 1, after = i + 1
        let top: Edge = before >= 0 ? .item(before) : (repeatAll ? .item(count - 1) : .own)
        let bottom: Edge = after < count ? .item(after) : (repeatAll ? .item(0) : .own)
        return (top, bottom)
    }
}

/// Now Playing's ambient colors under its glass: this album's primary in the
/// middle, the previous album's at the top edge, the next album's at the
/// bottom (NowPlayingNeighbours). Mock / no queue: the stand-in albums.
struct NowPlayingAmbient: View {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme
    var scheme: CoverScheme
    private var lab: GlassLab { GlassLab.shared }

    /// The two edge colours.
    private func neighbourColors(dark: Bool) -> (top: Color, bottom: Color) {
        let engine = model.player
        guard engine.currentTrack != nil, !engine.queue.isEmpty else {
            let albums = model.library.albums()
            return (CoverScheme(album: albums[11], dark: dark).primaryContainer,
                    CoverScheme(album: albums[1], dark: dark).primaryContainer)
        }
        let edges = NowPlayingNeighbours.edges(count: engine.queue.count, current: engine.currentIndex,
                                               repeatAll: engine.repeatMode == .all)
        func color(_ edge: NowPlayingNeighbours.Edge, own: Color) -> Color {
            guard case .item(let i) = edge else { return own }
            let t = engine.queue[i].track
            let album = model.store.albumForPlaying(t) ?? AppModel.album(for: t, library: model.library)
            return CoverScheme(album: album, dark: dark).primaryContainer
        }
        return (color(edges.top, own: scheme.secondaryContainer),
                color(edges.bottom, own: scheme.tertiaryContainer))
    }

    var body: some View {
        let dark = colorScheme == .dark
        let edge = neighbourColors(dark: dark)
        // World: dark mode has its own strengths (0 = as in light).
        let strength = dark && lab.extra("darkAmbientStrength") > 0
            ? lab.extra("darkAmbientStrength") : lab.extra("ambientStrength")
        let neighbours = dark && lab.extra("darkNeighbourStrength") > 0
            ? lab.extra("darkNeighbourStrength") : lab.extra("neighbourStrength")
        let aura = Circle().fill(scheme.primary.opacity(strength))
            .frame(width: 360).blur(radius: lab.extra("ambientBlur"))
        let onTop = lab.extra("auraOnTop") > 0.5
        ZStack {
            scheme.background
            if !onTop { aura }
            VStack {
                Ellipse().fill(edge.top.opacity(neighbours))
                    .frame(height: 300).offset(y: -90).blur(radius: 50)
                Spacer()
                Ellipse().fill(edge.bottom.opacity(neighbours))
                    .frame(height: 320).offset(y: 110).blur(radius: 50)
            }
            // World: the room's own cover leads; neighbours only frame it.
            if onTop { aura }
        }
        // A new song (or a reordered queue) re-colours the edges on the
        // effects spring, never a snap.
        .animation(Yoin.effects, value: model.player.currentItem?.id)
        .animation(Yoin.effects, value: model.player.queue.count)
    }
}
