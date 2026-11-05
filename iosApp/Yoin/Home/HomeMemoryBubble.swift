import SwiftUI
import UIKit
import YoinShared

/// The Memories entry on Home (Android HomeMemoryBubble.kt, owner 2026-10-04
/// → 10-17): a chevron in the safe area just under the Dynamic Island, and a
/// speech bubble the header's small title turns into when it has something to
/// say. Speaking: the Yoin mark blooms at the title's left and pushes it
/// right, holds a beat, then rolls a full turn to the bubble's bottom-right
/// corner while the bubble unrolls behind it from the title's place; the tail
/// rises last, its tip holding the chevron. Spoken, it flies back up into the
/// arrow and the title slides home. It speaks on news (something written
/// since it last spoke, per account) or when Home is left alone a few seconds;
/// any touch anywhere tucks it away.
@MainActor
@Observable
final class MemoryBubbleController {
    /// The two beats of speaking (0 → 1) and the flight home (0 → 1).
    private(set) var markIn: CGFloat = 0
    private(set) var unroll: CGFloat = 0
    private(set) var flyAway: CGFloat = 0
    /// Flying away, the place it left from (it flies into the arrow it left).
    private(set) var flight: BubbleGeometry?
    private(set) var turn = BubbleTurn()
    /// What it is saying, picked as it starts: kept while it tucks away.
    private(set) var say: HomeMemoryPill.Latest?
    private(set) var saidNotesOnly = false

    /// Home can be read right now (the overlay keeps it current; its timers read it here).
    @ObservationIgnored var canSpeak = false
    /// What it can say right now (kept current by the overlay).
    @ObservationIgnored var pill: HomeMemoryPill?

    /// The header's title and free span (global), written by HomeView.
    var titleFrame: CGRect = .zero
    var freeEnd: CGFloat = .infinity
    /// Taps on the arrow / the bubble, in global space (the window watcher skips them).
    @ObservationIgnored var arrowFrame: CGRect = .zero
    @ObservationIgnored var bubbleFrame: CGRect = .zero

    @ObservationIgnored private let markSpring = SpringRunner()
    @ObservationIgnored private let unrollSpring = SpringRunner()
    @ObservationIgnored private let flySpring = SpringRunner()
    @ObservationIgnored private var settleTask: Task<Void, Never>?
    @ObservationIgnored private var lastTouch = Date()
    @ObservationIgnored let seen: MemoryBubbleSeenStore

    init(seen: MemoryBubbleSeenStore = .shared) { self.seen = seen }

    var speaking: Bool { turn.reason != nil }
    /// Anything of it is drawn.
    var composed: Bool { speaking || markIn > 0.01 || unroll > 0.01 }

    // MARK: Cues

    /// Every change of what it says goes through the rules (BubbleTurn.after).
    func cue(_ what: BubbleCue, pill: HomeMemoryPill?) {
        let next = turn.after(what)
        if let starts = next.reason, starts != turn.reason {
            guard let pill, pill.hasSomethingToSay else { return }
            let t = starts == .idle && !pill.recall.isEmpty ? seen.nextRecallTurn(pill.scope) : 0
            say = HomeMemories.subject(pill, reason: starts, turn: t)
            saidNotesOnly = say == nil
        }
        let was = speaking
        turn = next
        if speaking != was {
            // Gone quiet: the idle wait starts over from now.
            if !speaking { lastTouch = Date() }
            settle(speaking: speaking)
        }
    }

    func touched(pill: HomeMemoryPill?) {
        lastTouch = Date()
        if speaking { cue(.touch, pill: pill) }
    }

    var idleSince: Date { lastTouch }

    /// The idle clock starts over (it has just spoken).
    func resetIdle() { lastTouch = Date() }

    /// The app went to the background: away at once, unseen.
    func backgrounded() {
        turn = turn.after(.backgrounded)
        settleTask?.cancel()
        [markSpring, unrollSpring, flySpring].forEach { $0.stop() }
        markIn = 0; unroll = 0; flyAway = 0; flight = nil
    }

    /// Said in full: out, at rest, not flying.
    var fullySaid: Bool { unroll >= 1 && !unrollSpring.isRunning && flyAway <= 0 && !flySpring.isRunning }

    // MARK: Motion

    private func settle(speaking: Bool) {
        settleTask?.cancel()
        let reduced = UIAccessibility.isReduceMotionEnabled
        settleTask = Task { [weak self] in
            guard let self else { return }
            let out = Spring.yoin(Tokens.motion.expressiveDefaultSpatial)
            let back = Spring.yoin(Tokens.motion.standardDefaultSpatial)
            let fly = Spring.yoin(Tokens.motion.standardSlowSpatial)
            if reduced {
                self.markIn = speaking ? 1 : 0; self.unroll = speaking ? 1 : 0; self.flyAway = 0; self.flight = nil
                return
            }
            if speaking {
                // Caught flying away: start over from the title.
                if self.flyAway > 0 {
                    self.flySpring.stop(); self.flight = nil
                    self.unroll = 0; self.markIn = 0; self.flyAway = 0
                }
                if self.unroll <= 0 {
                    guard await self.markSpring.animate(from: self.markIn, to: 1, spring: out, apply: { [weak self] in self?.markIn = $0 })
                    else { return }
                    try? await Task.sleep(for: .seconds(BubbleTiming.markHold / LaunchConfig.animationSpeed))
                    guard !Task.isCancelled else { return }
                } else {
                    self.markSpring.run(from: self.markIn, to: 1, velocity: 0, spring: out) { [weak self] in self?.markIn = $0 }
                }
                guard await self.unrollSpring.animate(from: self.unroll, to: 1, spring: out, apply: { [weak self] in self?.unroll = $0 })
                else { return }
                self.unroll = 1; self.markIn = 1
            } else if self.unroll >= BubbleMetrics.flyAwayFrom, let g = self.geometry {
                // Spoken: it flies off into the arrow and the title comes back.
                self.flight = g
                guard await self.flySpring.animate(from: self.flyAway, to: 1, spring: fly, apply: { [weak self] in self?.flyAway = $0 })
                else { self.flight = nil; return }
                self.unroll = 0; self.markIn = 0; self.flyAway = 0; self.flight = nil
            } else {
                // Only the mark beside the title: it folds away.
                guard await self.unrollSpring.animate(from: self.unroll, to: 0, spring: back, apply: { [weak self] in self?.unroll = $0 })
                else { return }
                guard await self.markSpring.animate(from: self.markIn, to: 0, spring: back, apply: { [weak self] in self?.markIn = $0 })
                else { return }
                self.unroll = 0; self.markIn = 0
            }
        }
    }

    /// Where it lands now (the overlay writes it as it lays out).
    @ObservationIgnored var geometry: BubbleGeometry?
}

// MARK: - The overlay

/// The arrow, the bubble and its mark, laid over Home (full screen, taking
/// taps only on the arrow and the speaking bubble). Its timers live here:
/// news, the idle reminder, the dwell.
struct MemoryBubbleOverlay: View {
    @Environment(AppModel.self) private var model
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.colorScheme) private var colorScheme
    var bubble: MemoryBubbleController
    var memories: MemoriesPresentation
    var pill: HomeMemoryPill?
    var scheme: CoverScheme
    /// The feed's scroll distance (the arrow fades out over the first 48 pt).
    var scroll: HomeScrollState
    /// Something above Home owns the screen (Now Playing, a pushed page, search).
    var covered: Bool
    /// Home being edited: the bubble keeps quiet and the arrow fades over P's first half.
    var edit: HomeEditController

    private var scrolled: CGFloat { scroll.scrolled }
    private var editing: Bool { edit.isEditing }
    private var editProgress: CGFloat { edit.progress }

    private var hint: CGFloat { min(max(1 - memories.fraction, 0), 1) }

    /// It may only speak where it can be read.
    private var canSpeak: Bool {
        !covered && !editing && scrolled <= BubbleMetrics.arrowScrollFade && hint <= 0.01 && scenePhase == .active
    }

    var body: some View {
        // The outer reader keeps the safe area (the status bar's height); the
        // inner one covers the screen, as the arrow sits in the safe area.
        GeometryReader { outer in
            let safeTop = outer.safeAreaInsets.top
            GeometryReader { geo in
                content(geo, safeTop: safeTop)
            }
            .ignoresSafeArea()
        }
        // News: told once Home is up and readable, then remembered for this account.
        .task(id: "\(pill?.scope ?? "")#\(pill?.newsKey ?? "")") { await tellNews() }
        // "不动就出现": quiet, readable and untouched for a fresh 4–5 s pick, it speaks.
        .task(id: pill?.scope ?? "") { await idleLoop() }
        // Said in full, it holds, then flies home on its own.
        .task(id: "\(bubble.turn.reason.map { "\($0)" } ?? "-")#\(bubble.say?.albumId ?? "")") { await dwell() }
        .onChange(of: pill, initial: true) { _, p in bubble.pill = p }
        .onChange(of: canSpeak, initial: true) { _, can in
            bubble.canSpeak = can
            if !can { bubble.cue(.outOfReach, pill: pill) }
        }
        .onChange(of: scenePhase) { _, phase in if phase == .background { bubble.backgrounded() } }
        #if DEBUG
        // `-bubbleReset YES`: forget what it has said (QA of the news path).
        .onAppear { if UserDefaults.standard.bool(forKey: "bubbleReset"), let scope = pill?.scope { bubble.seen.markSeen(scope, "") } }
        #endif
        .background(WindowTouchWatcher { point in
            guard !bubble.arrowFrame.contains(point), !bubble.bubbleFrame.contains(point) else { return }
            bubble.touched(pill: pill)
        })
    }

    private func content(_ geo: GeometryProxy, safeTop: CGFloat) -> some View {
        let origin = geo.frame(in: .global).origin
        let layout = place(geo.size, safeTop: safeTop, origin: origin)
        return ZStack(alignment: .topLeading) {
            if bubble.composed { bubbleLayer(layout, size: geo.size) }
            arrow(layout)
        }
        .frame(width: geo.size.width, height: geo.size.height, alignment: .topLeading)
        .onAppear { bubble.geometry = layout.geometry }
        .onChange(of: layout.geometry) { _, g in bubble.geometry = g }
    }

    // MARK: Timers

    private func tellNews() async {
        guard let pill else { return }
        let last = bubble.seen.lastSeen(pill.scope)

        guard HomeMemories.isNews(pill, lastSeen: last) else {
            // A deletion moved the key back: remember the lower one quietly.
            if !pill.newsKey.isEmpty, pill.newsKey != last { bubble.seen.markSeen(pill.scope, pill.newsKey) }
            return
        }
        try? await Task.sleep(for: .seconds(BubbleTiming.newsDelay))
        // Waiting to be readable, it must stay so, untouched, a beat first.
        while !Task.isCancelled {
            if bubble.canSpeak && Date().timeIntervalSince(bubble.idleSince) > 0.3 { break }
            try? await Task.sleep(for: .milliseconds(100))
        }
        guard !Task.isCancelled else { return }
        bubble.cue(.news, pill: pill)
        bubble.seen.markSeen(pill.scope, pill.newsKey)
    }

    private func idleLoop() async {
        let pinned = UserDefaults.standard.double(forKey: "bubbleIdleSeconds")
        while !Task.isCancelled {
            let wait = pinned > 0 ? pinned : Double.random(in: BubbleTiming.idleRange)
            try? await Task.sleep(for: .milliseconds(200))
            guard !bubble.speaking, bubble.canSpeak, Date().timeIntervalSince(bubble.idleSince) >= wait else { continue }
            bubble.cue(.idleElapsed, pill: bubble.pill)
            // Gone again, the clock starts over.
            bubble.resetIdle()
        }
    }

    private func dwell() async {
        guard let reason = bubble.turn.reason else { return }
        while !Task.isCancelled && !bubble.fullySaid { try? await Task.sleep(for: .milliseconds(50)) }
        let hold = reason == .news ? BubbleTiming.newsDwell : Double.random(in: BubbleTiming.chatterDwellRange)
        try? await Task.sleep(for: .seconds(hold))
        guard !Task.isCancelled else { return }
        bubble.cue(.dwellOver, pill: bubble.pill)
    }

    // MARK: Layout

    private struct Placed: Equatable {
        var arrow: CGPoint
        var geometry: BubbleGeometry
    }

    /// The arrow under the island (or the status bar), the bubble from the title to just past it.
    private func place(_ size: CGSize, safeTop: CGFloat, origin: CGPoint) -> Placed {
        let title = bubble.titleFrame == .zero
            ? CGRect(x: 16, y: safeTop + 12, width: 120, height: 60)
            : bubble.titleFrame.offsetBy(dx: -origin.x, dy: -origin.y)
        let freeEnd = min(bubble.freeEnd - origin.x, size.width - BubbleMetrics.edgeMargin)
        // The Dynamic Island (or a notch) centred on a full-width page: its bottom, approximately.
        let screen = UIScreen.main.bounds.width
        let fullWidth = abs(origin.x) < 1 && abs(size.width - screen) < 1
        let cutoutBottom: CGFloat? = !fullWidth ? nil : safeTop >= 55 ? 48 : safeTop >= 40 ? 31 : nil
        let (x, under) = BubbleLayout.arrowCenterX(cutoutCenterX: cutoutBottom == nil ? nil : size.width / 2,
                                                    pageCentre: size.width / 2, titleLeft: title.minX, freeEnd: freeEnd)
        let y = (under ? cutoutBottom! : safeTop) + BubbleMetrics.arrowBelowCutout + BubbleMetrics.arrowIcon / 2
        return Placed(arrow: CGPoint(x: x, y: y), geometry: BubbleLayout.geometry(titleFrame: title, arrowX: x, arrowCenterY: y))
    }

    // MARK: The arrow

    private func arrow(_ p: Placed) -> some View {
        let fade = min(max(1 - scrolled / BubbleMetrics.arrowScrollFade, 0), 1)
        let speakingInk = bubble.unroll * (1 - BubbleLayout.arrowHomecoming(bubble.flyAway))
        let rest = 0.62 + 0.38 * max(hint, min(max(speakingInk, 0), 1))
        // Flying, it stays in the tail it sat in, then comes home as the flight lands.
        var at = p.arrow
        if let f = bubble.flight {
            let left = CGPoint(x: f.tailX, y: f.tailTop + BubbleMetrics.tipAboveChevron)
            let h = BubbleLayout.arrowHomecoming(bubble.flyAway)
            at = CGPoint(x: lerpf(left.x, p.arrow.x, h), y: lerpf(left.y, p.arrow.y, h))
        }
        let enabled = fade > 0 && !editing
        let t = BubbleMetrics.arrowTouch
        let frame = CGRect(x: at.x - t / 2, y: at.y - t / 2, width: t, height: t)
        return YoinIcon("chevron_down", size: BubbleMetrics.arrowIcon)
            .foregroundStyle(scheme.onSurfaceVariant)
            .offset(y: hint * BubbleMetrics.arrowPullNudge)
            .opacity(Double(rest * fade * (1 - smoothstep(0, 0.5, editProgress))))
            .frame(width: t, height: t)
            .contentShape(.rect)
            .onTapGesture {
                guard enabled else { return }
                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                bubble.cue(.touch, pill: pill)
                memories.open()
            }
            .allowsHitTesting(enabled)
            .accessibilityLabel("Memories")
            .accessibilityAddTraits(.isButton)
            .accessibilityHidden(!enabled)
            .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { bubble.arrowFrame = $0 }
            .position(x: frame.midX, y: frame.midY)
    }

    // MARK: The bubble

    private func bubbleLayer(_ p: Placed, size: CGSize) -> some View {
        let g = bubble.flight ?? p.geometry
        let s = bubble.unroll
        let body = g.body(at: s)
        let fill = bubble.say.map { scheme.surfaceContainerHigh.mix(with: BackdropColors($0.album.palette).base, by: 0.30, in: .perceptual) }
            ?? scheme.surfaceContainerHigh
        let rise = smoothstep(BubbleMetrics.tailRisesFrom, 1, s)
        let tailX = min(g.tailX, body.maxX - BubbleMetrics.reach)
        let fly = bubble.flyAway
        let markHalf = BubbleMetrics.markSize / 2
        let markAt = CGPoint(x: lerpf(g.markStart.x, g.markEnd.x, s) - (1 - bubble.markIn) * BubbleMetrics.markSqueeze,
                             y: lerpf(g.markStart.y, g.markEnd.y, s))
        return ZStack(alignment: .topLeading) {
            // The surface, drawn in one Canvas: the body at the unroll, and
            // the tail rising last out of its top, its point over the chevron.
            Canvas { gc, _ in
                gc.opacity = Double(smoothstep(0.04, 0.3, s))
                if rise >= 1 {
                    gc.fill(BubbleOutline(body: body, radius: BubbleMetrics.corner, tailX: tailX, tailTop: g.tailTop)
                        .path(in: .zero), with: .color(fill))
                } else {
                    gc.fill(BubbleOutline(body: body, radius: BubbleMetrics.corner, tailX: nil).path(in: .zero), with: .color(fill))
                    if rise > 0 {
                        var t = gc
                        t.clip(to: Path(CGRect(x: 0, y: 0, width: size.width, height: max(0, body.minY + BubbleMetrics.corner))))
                        t.translateBy(x: 0, y: (1 - rise) * (body.minY - g.tailTop))
                        t.fill(BubbleOutline(body: body, radius: BubbleMetrics.corner, tailX: tailX, tailTop: g.tailTop)
                            .path(in: .zero), with: .color(fill))
                    }
                }
            }
            .frame(width: size.width, height: size.height)
            BubbleLine(latest: bubble.say, notesOnly: bubble.saidNotesOnly, scheme: scheme,
                       room: max(0, g.body.width - BubbleMetrics.coverInset - BubbleMetrics.trail))
                .frame(width: max(0, g.body.width - BubbleMetrics.coverInset - BubbleMetrics.trail), alignment: .leading)
                .frame(height: g.body.height)
                .offset(x: g.body.minX + BubbleMetrics.coverInset, y: g.body.minY)
                .opacity(Double(smoothstep(0.55, 0.95, s)))
                // Never past the body's right end as it unrolls.
                .mask(alignment: .topLeading) {
                    Rectangle().frame(width: max(0, body.maxX), height: size.height)
                }
            BubbleMark(markIn: bubble.markIn, scheme: scheme)
                .frame(width: BubbleMetrics.markSize, height: BubbleMetrics.markSize)
                .rotationEffect(.degrees(360 * Double(s)))
                .opacity(Double(smoothstep(0.1, 0.35, bubble.markIn)))
                .offset(x: markAt.x - markHalf, y: markAt.y - markHalf)
        }
        .frame(width: size.width, height: size.height, alignment: .topLeading)
        // Flying away: shrinks about the tail's tip, lifts and fades.
        .scaleEffect(1 - BubbleMetrics.flyShrink * fly, anchor: UnitPoint(x: g.tailX / max(size.width, 1), y: g.tailTop / max(size.height, 1)))
        .offset(y: -BubbleMetrics.flyLift * fly)
        .opacity(Double(BubbleLayout.flightAlpha(fly)))
        .allowsHitTesting(false)
        .overlay(alignment: .topLeading) {
            // The tap target: the speaking body (and the mark on its corner).
            if bubble.speaking && !editing {
                Color.clear
                    .contentShape(.rect)
                    .frame(width: g.body.width, height: g.body.maxY - g.tailTop)
                    .offset(x: g.body.minX, y: g.tailTop)
                    .onTapGesture { tapBubble() }
                    .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { bubble.bubbleFrame = $0 }
                    .onDisappear { bubble.bubbleFrame = .zero }
                    .accessibilityLabel(bubble.say.map { "Latest memory: \($0.albumName)" } ?? "Memories")
                    .accessibilityAddTraits(.isButton)
            }
        }
    }

    private func tapBubble() {
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
        let focus = bubble.say?.albumId ?? pill?.latest?.albumId
        bubble.cue(.touch, pill: pill)
        memories.open(focus: focus)
    }
}

/// What the bubble says: the memory's cover and album; with room, when it was
/// written, and its own title over the album (Android BubbleMemoryLine).
private struct BubbleLine: View {
    var latest: HomeMemoryPill.Latest?
    var notesOnly: Bool
    var scheme: CoverScheme
    var room: CGFloat

    var body: some View {
        HStack(spacing: BubbleMetrics.coverTitleGap) {
            if let latest {
                CoverArt(palette: latest.album.palette, cornerRadius: Tokens.shape.THUMB.cg, seed: latest.album.seed)
                    .frame(width: BubbleMetrics.coverSize, height: BubbleMetrics.coverSize)
                let wide = room - BubbleMetrics.coverSize - BubbleMetrics.coverTitleGap >= BubbleMetrics.saysMoreFrom
                let ago = latest.writtenAt.map { HomeHistory.timeAgo($0, now: Int64(Date().timeIntervalSince1970 * 1000)) }
                ViewThatFits(in: .horizontal) {
                    if wide, let title = latest.memoryTitle {
                        VStack(alignment: .leading, spacing: 0) {
                            Text(title).homeType(Tokens.type.titleMedium, .semibold).foregroundStyle(scheme.onSurface)
                            HStack(spacing: BubbleMetrics.agoGap) {
                                Text(latest.albumName).homeType(Tokens.type.bodyMedium).foregroundStyle(scheme.onSurfaceVariant)
                                if let ago { Text(ago).homeType(Tokens.type.bodySmall).foregroundStyle(scheme.onSurfaceVariant) }
                            }
                        }
                        .fixedSize()
                    }
                    if wide, let ago {
                        VStack(alignment: .leading, spacing: 0) {
                            Text(latest.albumName).homeType(Tokens.type.titleMedium, .semibold).foregroundStyle(scheme.onSurface)
                                .lineLimit(1)
                            Text(ago).homeType(Tokens.type.bodySmall).foregroundStyle(scheme.onSurfaceVariant)
                        }
                    }
                    Text(latest.albumName).homeType(Tokens.type.titleMedium, .semibold).foregroundStyle(scheme.onSurface)
                        .lineLimit(1)
                }
            } else if notesOnly {
                Text("Memories").homeType(Tokens.type.titleMedium, .semibold).foregroundStyle(scheme.onSurfaceVariant)
                    .padding(.leading, BubbleMetrics.coverTitleGap)
            }
        }
        .frame(maxHeight: .infinity)
    }
}

/// The Yoin mark that says it: its arms bloom open on [markIn], one beat apart
/// (scale about the hub, a −80° turn unwinding), launcher colours.
private struct BubbleMark: View {
    var markIn: CGFloat
    var scheme: CoverScheme

    var body: some View {
        let colors = [scheme.tertiary, scheme.primary, scheme.secondary]
        Canvas { gc, size in
            let s = min(size.width, size.height) / YoinMarkPaths.viewBox
            gc.scaleBy(x: s, y: s)
            let hub = YoinMarkPaths.hub
            for i in 0..<3 {
                let p = BubbleLayout.armProgress(markIn, arm: i)
                guard p > 0.001 else { continue }
                var g = gc
                g.translateBy(x: hub.x, y: hub.y)
                g.rotate(by: .degrees(Double((1 - p) * -80)))
                g.scaleBy(x: p, y: p)
                g.translateBy(x: -hub.x, y: -hub.y)
                let a = YoinMarkPaths.arms[i]
                g.fill(a.fill, with: .color(colors[i]))
                if a.lineWidth > 0 {
                    g.stroke(a.line, with: .color(.white), style: StrokeStyle(lineWidth: a.lineWidth, lineCap: .round))
                } else {
                    g.fill(a.line, with: .color(.white))
                }
            }
        }
    }
}

/// The header title through the bubble: it steps right for the mark and
/// gives way as the bubble unrolls (Android `memoryBubbleTitle`). Its own
/// view, so a frame of the bubble re-renders the title only.
struct BubbleTitleShift: ViewModifier {
    var bubble: MemoryBubbleController

    func body(content: Content) -> some View {
        let stay = 1 - bubble.flyAway
        content
            .offset(x: (BubbleMetrics.markSize + BubbleMetrics.markTitleGap) * bubble.markIn * stay)
            .opacity(Double(1 - smoothstep(0, 0.45, bubble.unroll) * stay))
    }
}

/// Every touch that starts anywhere in the window (the bar's too), reported
/// without taking it: a recognizer that never claims a touch.
struct WindowTouchWatcher: UIViewRepresentable {
    var onTouch: (CGPoint) -> Void

    func makeUIView(context: Context) -> WatcherView { WatcherView(onTouch: onTouch) }
    func updateUIView(_ uiView: WatcherView, context: Context) { uiView.onTouch = onTouch }

    final class WatcherView: UIView {
        var onTouch: (CGPoint) -> Void
        private var recognizer: TouchDownRecognizer?

        init(onTouch: @escaping (CGPoint) -> Void) {
            self.onTouch = onTouch
            super.init(frame: .zero)
            isUserInteractionEnabled = false
        }

        required init?(coder: NSCoder) { fatalError() }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            if let r = recognizer { r.view?.removeGestureRecognizer(r) }
            recognizer = nil
            guard let window else { return }
            let r = TouchDownRecognizer { [weak self] p in self?.onTouch(p) }
            window.addGestureRecognizer(r)
            recognizer = r
        }
    }

    final class TouchDownRecognizer: UIGestureRecognizer, UIGestureRecognizerDelegate {
        let report: (CGPoint) -> Void

        init(report: @escaping (CGPoint) -> Void) {
            self.report = report
            super.init(target: nil, action: nil)
            cancelsTouchesInView = false
            delaysTouchesBegan = false
            delaysTouchesEnded = false
            delegate = self
        }

        override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
            if let t = touches.first, let w = view { report(t.location(in: w)) }
            state = .failed
        }

        func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }
    }
}
