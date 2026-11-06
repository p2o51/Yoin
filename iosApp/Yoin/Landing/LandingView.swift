import SwiftUI
import UIKit

/// The first-run landing (Android LandingHost + LandingScreen + LandingStage):
/// a full-screen surface above the shell. A floating window holds the step's
/// scene, the mascot stands on it and talks through a bubble, and a pill at the
/// shell bar's place carries back, the step dots and the main button. Opening
/// Yoin turns the window into the pill, flies the mascot to the corner and
/// fades the surface off the shell, whose bar sits exactly where the pill was.
///
/// Back surface: a full-screen shell-owned surface with in-page steps (Android
/// Pattern E). The pill's back button and the leading-edge swipe drive the same
/// step-back; the swipe scrubs the stage's preview pose and springs on release.
/// The first step of a first run has no back; a re-run's first step closes it.
struct LandingView: View {
    let session: LandingSession
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme

    @State private var flow: LandingFlow
    /// The step whose line the bubble shows: it swaps mid-transition.
    @State private var bubbleStep: LandingStep = .hello
    @State private var bubbleScale: CGFloat
    @State private var forward = true
    @State private var sceneHeights: [LandingStep: CGFloat] = [:]
    /// The window's content height (starts at the pill's, so the first step grows out of it).
    @State private var windowHeight: CGFloat = LandingFrame.pillHeight
    @State private var bubbleSize = CGSize(width: 300, height: 100)
    @State private var helloBubbleHeight: CGFloat = 100
    @State private var flowBubbleHeight: CGFloat = 52
    @State private var keyboard: CGFloat = 0
    @State private var screenHeight: CGFloat = 0
    @State private var reveal: Double
    @State private var pillIn: CGFloat
    @State private var handoff = false
    @State private var fly = false
    @State private var finishing = false
    @State private var backProgress: CGFloat = 0
    @State private var mascot: MascotModel
    @State private var bubbleTask: Task<Void, Never>?
    @State private var started = false
    @State private var subsonicForm = LandingSubsonicForm()
    @State private var connectRequests = 0
    // Haptic triggers.
    @State private var refusals = 0
    @State private var confirms = 0
    @State private var toggles = 0
    @State private var ticks = 0

    private let reduced: Bool

    init(session: LandingSession) {
        self.session = session
        let reduced = UIAccessibility.isReduceMotionEnabled
        self.reduced = reduced
        _flow = State(initialValue: LandingFlow(mode: session.mode))
        _reveal = State(initialValue: session.fadesIn ? 0 : 1)
        // The mascot's entrance: the pill and the bubble come in once it lands.
        _pillIn = State(initialValue: reduced ? 1 : 0)
        _bubbleScale = State(initialValue: reduced ? 1 : 0)
        let mascot = MascotModel()
        mascot.reduced = reduced
        if !reduced { mascot.hideForIntro() }
        _mascot = State(initialValue: mascot)
    }

    private var dark: Bool { colorScheme == .dark }
    private var scheme: CoverScheme { .brand(dark: dark) }

    var body: some View {
        // Measured inside the safe area (a reader that ignores it reports no insets), drawn edge to edge.
        GeometryReader { geo in
            let insets = geo.safeAreaInsets
            let full = CGSize(width: geo.size.width + insets.leading + insets.trailing,
                              height: geo.size.height + insets.top + insets.bottom)
            let f = LandingFrame(size: full, insets: insets, keyboard: keyboard)
            ZStack(alignment: .topLeading) {
                LandingBackdrop(scheme: scheme, dark: dark, animate: !reduced)
                stage(f)
                    .modifier(StageBackPreview(progress: backProgress, width: f.width))
                pill(f)
                if flow.backEnabled && !finishing {
                    edgeSwipe(f)
                }
            }
            .frame(width: full.width, height: full.height, alignment: .topLeading)
            .ignoresSafeArea()
            .onAppear { screenHeight = full.height }
            .onChange(of: full.height) { _, h in screenHeight = h }
        }
        .ignoresSafeArea(.keyboard)
        .opacity(reveal)
        .sensoryFeedback(.error, trigger: refusals)
        .sensoryFeedback(.success, trigger: confirms)
        .sensoryFeedback(.selection, trigger: toggles)
        .sensoryFeedback(.impact(weight: .light), trigger: ticks)
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillChangeFrameNotification)) { note in
            let end = (note.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect) ?? .zero
            let h = screenHeight > 0 ? max(0, screenHeight - end.minY) : 0
            if h != keyboard { withAnimation(Yoin.spatial) { keyboard = h } }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in
            if keyboard != 0 { withAnimation(Yoin.spatial) { keyboard = 0 } }
        }
        .task { await start() }
        #if DEBUG
        .task { await runScript() }
        #endif
    }

    // MARK: Stage

    @ViewBuilder
    private func stage(_ f: LandingFrame) -> some View {
        let step = flow.step
        let hello = step == .hello
        let winH = hello ? LandingFrame.pillHeight : min(windowHeight, maxWindowHeight(f))
        let winRect = handoff ? f.barRect
            : hello ? f.pillRect
            : CGRect(x: f.colX, y: f.floor - winH, width: f.colW, height: winH)
        let mp = mascotPose(step, f, winH: winH)
        ZStack(alignment: .topLeading) {
            window(winRect)
                .animation(Yoin.effects) { $0.opacity(hello || handoff ? 0 : 1) }
                .offset(x: winRect.minX, y: winRect.minY)
            LandingMascotView(model: mascot, breathing: !reduced)
                .frame(width: mp.size, height: mp.size / LandingMascotView.aspect)
                .contentShape(.rect)
                .onTapGesture {
                    ticks += 1
                    mascot.wave()
                }
                .offset(x: mp.cx - mp.size / 2, y: mp.cy - mp.size / LandingMascotView.aspect / 2)
            bubble(f, mascot: mp, winTop: f.floor - winH)
        }
    }

    /// The window can't reach higher than the mascot's row above it.
    private func maxWindowHeight(_ f: LandingFrame) -> CGFloat {
        let row = max(88 * 0.8, flowBubbleHeight)
        return max(LandingFrame.pillHeight, f.floor - (f.top + 18 + row + 14))
    }

    private struct Pose { var cx: CGFloat; var cy: CGFloat; var size: CGFloat }

    /// Android LandingStage's poses: centred above the bubble on Hello, standing on the window's
    /// top-left corner after (larger when there is room), flown to the corner during the hand-off.
    private func mascotPose(_ step: LandingStep, _ f: LandingFrame, winH: CGFloat) -> Pose {
        if fly { return Pose(cx: 44, cy: f.top + 32, size: 56) }
        if step == .hello {
            let size = min(f.width * 0.62, 250)
            let group = size * 0.8 + 15 + helloBubbleHeight
            return Pose(cx: f.width / 2, cy: (f.top + f.pillTop) / 2 * 0.94 - group / 2 + size * 0.4, size: size)
        }
        let top = f.floor - winH
        let room = top - 14 - (f.top + 18)
        let grown = min(max(room * 0.42, 88), 132)
        let size = grown - 88 > 4 && flowBubbleHeight <= room ? grown : 88
        let base = top - 14
        return Pose(cx: f.colX + size / 2 - 6, cy: base - size * 0.4, size: size)
    }

    private func window(_ rect: CGRect) -> some View {
        ZStack(alignment: .top) {
            if flow.step != .hello {
                let step = flow.step
                ScrollView {
                    scene(step)
                        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { h in
                            sceneMeasured(step, h)
                        }
                }
                .scrollBounceBehavior(.basedOnSize)
                .scrollDismissesKeyboard(.interactively)
                .scrollIndicators(.hidden)
                .id(step)
                .transition(.asymmetric(
                    insertion: .offset(x: forward ? 28 : -28).combined(with: .opacity),
                    removal: .offset(x: forward ? -28 : 28).combined(with: .opacity)))
            }
        }
        .frame(width: rect.width, height: rect.height, alignment: .top)
        .background(scheme.surfaceContainer)
        .clipShape(.rect(cornerRadius: 28))
    }

    private func sceneMeasured(_ step: LandingStep, _ h: CGFloat) {
        sceneHeights[step] = h
        guard step == flow.step, !handoff, abs(windowHeight - h) > 0.5 else { return }
        withAnimation(Yoin.spatial) { windowHeight = h }
    }

    @ViewBuilder
    private func scene(_ step: LandingStep) -> some View {
        let reactions = LandingReactions(refused: refuse, connected: connected)
        switch step {
        case .hello: EmptyView()
        case .pick: LandingPickScene(picked: flow.picked, scheme: scheme, onToggle: toggle)
        case .about: LandingAboutScene(picked: flow.picked, scheme: scheme)
        case .subsonic:
            LandingSubsonicScene(form: $subsonicForm, connected: flow.connected.contains(.subsonic),
                                 scheme: scheme, reactions: reactions, connectRequests: connectRequests)
        case .spotify:
            LandingSpotifyScene(connected: flow.connected.contains(.spotify), scheme: scheme, reactions: reactions)
        case .appleMusic:
            LandingAppleScene(connected: flow.connected.contains(.appleMusic), scheme: scheme, reactions: reactions)
        case .ready: LandingReadyScene(accounts: accounts, scheme: scheme)
        }
    }

    // MARK: Bubble

    @ViewBuilder
    private func bubble(_ f: LandingFrame, mascot p: Pose, winTop: CGFloat) -> some View {
        let tailUp = bubbleStep == .hello
        let line = landingBubble(bubbleStep, noAccounts: accounts.isEmpty)
        let maxWidth = tailUp ? min(340, f.width - 48) : f.colW - p.size - 6
        let bw = bubbleSize.width, bh = bubbleSize.height
        let ax = tailUp ? p.cx : p.cx + p.size * 0.5 + 2
        let ay = tailUp ? p.cy + p.size * 0.42 + 4 : p.cy - p.size * 0.05
        let x = tailUp ? min(max(ax - bw / 2, 16), max(16, f.width - 16 - bw)) : ax + 11
        let y = tailUp ? ay + 11 : min(ay, winTop - 14 - bh + 26) - 26
        let tailOffset = tailUp ? ax - x : 26
        let scale = handoff ? 0 : bubbleScale
        let anchor = tailUp ? UnitPoint(x: tailOffset / max(bw, 1), y: -11 / max(bh, 1))
            : UnitPoint(x: -11 / max(bw, 1), y: 26 / max(bh, 1))
        LandingBubble(title: line.title, body: line.body, tailUp: tailUp, tailOffset: tailOffset,
                      background: dark ? CoverScheme.brand(dark: false).primaryContainer : scheme.surfaceBright,
                      content: dark ? CoverScheme.brand(dark: false).onPrimaryContainer : scheme.onSurface,
                      maxWidth: maxWidth)
            .onGeometryChange(for: CGSize.self) { $0.size } action: { size in bubbleMeasured(size) }
            .scaleEffect(max(scale, 0.001), anchor: anchor)
            .opacity(min(1, scale * 2.5))
            .offset(x: x, y: y)
            .allowsHitTesting(false)
    }

    private func bubbleMeasured(_ size: CGSize) {
        // A line's first measurement places it at once (it is still popping in); later ones move it.
        withAnimation(started ? Yoin.spatial : nil) {
            bubbleSize = size
            if bubbleStep == .hello { helloBubbleHeight = size.height } else { flowBubbleHeight = size.height }
        }
    }

    /// The bubble dips while the stage moves and pops its new line from the tail at the midpoint.
    private func swapBubble(to step: LandingStep) {
        bubbleTask?.cancel()
        bubbleTask = Task { @MainActor in
            withAnimation(Yoin.spatialFast) { bubbleScale = 0.45 }
            try? await Task.sleep(for: .milliseconds(Int(150 / LaunchConfig.animationSpeed)))
            guard !Task.isCancelled else { return }
            withAnimation(Yoin.spatial) {
                bubbleStep = step
                bubbleScale = 1
            }
        }
    }

    // MARK: Pill

    private func pill(_ f: LandingFrame) -> some View {
        let hello = flow.step == .hello
        let showBack = !hello || session.mode == .rerun
        let primary = flow.primary(hasAccount: !accounts.isEmpty)
        return LandingPill(hello: hello, showBack: showBack, backIsClose: !flow.canGoBack,
                           primary: primary, dotCount: flow.steps.count - 1,
                           dotIndex: max(flow.index - 1, 0), scheme: scheme,
                           onBack: goBack, onPrimary: onPrimary)
            .opacity(handoff ? 0 : 1)
            .animation(Yoin.effects, value: handoff)
            .frame(width: f.pillRect.width, height: f.pillRect.height)
            .background { Capsule().fill(.clear).glassEffect(.regular, in: .capsule) }
            .opacity(min(1, pillIn * 1.4))
            .offset(x: f.pillRect.minX, y: f.pillRect.minY + (1 - pillIn) * 120)
            .allowsHitTesting(!finishing)
    }

    // MARK: Back

    private func edgeSwipe(_ f: LandingFrame) -> some View {
        Color.clear
            .frame(width: 22, height: max(0, f.pillTop - 8))
            .contentShape(.rect)
            .gesture(
                DragGesture(minimumDistance: 8)
                    .onChanged { v in
                        let raw = min(max(v.translation.width / f.width, 0), 1)
                        // Eased scrub: the finger drives the preview directly; only release springs.
                        backProgress = 1 - (1 - raw) * (1 - raw)
                    }
                    .onEnded { v in
                        let commit = v.translation.width > f.width * 0.3 || v.predictedEndTranslation.width > f.width * 0.5
                        withAnimation(Yoin.spatial) { backProgress = 0 }
                        if commit { goBack() }
                    }
            )
            .accessibilityHidden(true)
    }

    private func goBack() {
        guard !finishing else { return }
        if flow.canGoBack {
            move(forward: false) { $0.back() }
        } else if session.mode == .rerun {
            close()
        }
    }

    // MARK: Actions

    private func move(forward dir: Bool, _ change: @escaping (inout LandingFlow) -> Void) {
        let apply: @MainActor () -> Void = {
            var next = flow
            change(&next)
            guard next.step != flow.step else { return }
            withAnimation(Yoin.spatial) {
                flow = next
                if let h = sceneHeights[next.step] { windowHeight = h }
            }
            swapBubble(to: next.step)
        }
        if forward != dir {
            // The direction is rendered before the step changes, so the leaving scene slides the right way.
            forward = dir
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.012) { apply() }
        } else {
            apply()
        }
    }

    private func onPrimary() {
        guard !finishing else { return }
        switch flow.step {
        case .ready: finish()
        case .pick where flow.refusesPrimary(hasAccount: !accounts.isEmpty): refuse()
        default: move(forward: true) { $0.next() }
        }
    }

    private func toggle(_ service: LandingService) {
        guard service.availableOnIOS else { return }
        if !flow.picked.contains(service) { mascot.nod() }
        toggles += 1
        withAnimation(Yoin.spatial) { flow.toggle(service) }
    }

    private func refuse() {
        refusals += 1
        mascot.shake()
    }

    private func connected(_ service: LandingService) {
        confirms += 1
        withAnimation(Yoin.spatial) { flow.markConnected(service) }
        mascot.cheer()
    }

    private func start() async {
        guard !started else { return }
        mascot.onTick = { ticks += 1 }
        if session.fadesIn { withAnimation(Yoin.effects) { reveal = 1 } }
        if session.mode == .rerun, subsonicForm.address.isEmpty, let a = model.session.account {
            subsonicForm.address = a.baseUrl
            subsonicForm.username = a.username
        }
        defer { started = true }
        guard !reduced else { return }
        try? await Task.sleep(for: .milliseconds(Int(220 / LaunchConfig.animationSpeed)))
        mascot.intro {
            withAnimation(Yoin.spatial) { pillIn = 1 }
            Task { @MainActor in
                try? await Task.sleep(for: .milliseconds(Int(150 / LaunchConfig.animationSpeed)))
                bubbleScale = 0.2
                withAnimation(Yoin.spatialFast) { bubbleScale = 1 }
            }
        }
    }

    #if DEBUG
    /// QA without touch input: `-landingAuto "wait:1800,next,pick:subsonic,…"` plays the steps through the
    /// same actions the pill, the tiles, the Connect button and the edge swipe call. Tokens: wait:<ms>,
    /// next, back, swipe (a scrubbed edge swipe that commits), peek (one that cancels), pick:<service>,
    /// fill / fillbad (the Subsonic form from -landingURL / -landingUser / -landingPass; fillbad: a dead address), connect, tapmascot.
    private func runScript() async {
        let d = UserDefaults.standard
        guard let script = d.string(forKey: "landingAuto") else { return }
        func sleep(_ ms: Double) async { try? await Task.sleep(for: .milliseconds(Int(ms / LaunchConfig.animationSpeed))) }
        for token in script.split(separator: ",").map({ $0.trimmingCharacters(in: .whitespaces) }) {
            let parts = token.split(separator: ":", maxSplits: 1).map(String.init)
            switch parts[0] {
            case "wait": await sleep(Double(parts.count > 1 ? parts[1] : "500") ?? 500)
            case "next": onPrimary()
            case "back": goBack()
            case "tapmascot": ticks += 1; mascot.wave()
            case "pick": if let s = LandingService(rawValue: parts.count > 1 ? parts[1] : "") { toggle(s) }
            case "fill", "fillbad":
                // fillbad: an address nothing answers on.
                subsonicForm.address = parts[0] == "fillbad" ? "http://127.0.0.1:9" : d.string(forKey: "landingURL") ?? ""
                subsonicForm.username = d.string(forKey: "landingUser") ?? ""
                subsonicForm.password = d.string(forKey: "landingPass") ?? ""
            case "connect": connectRequests += 1
            case "swipe", "peek":
                let commit = parts[0] == "swipe"
                for i in 1...18 {
                    let raw = Double(i) / 18 * (commit ? 0.45 : 0.2)
                    backProgress = 1 - (1 - raw) * (1 - raw)
                    await sleep(16)
                }
                withAnimation(Yoin.spatial) { backProgress = 0 }
                if commit { goBack() }
            default: break
            }
        }
    }
    #endif

    /// "Open Yoin": recorded as seen, then the hand-off — the window folds into the pill (the
    /// shell's bar is in the same place), the mascot waves and flies to the corner, the surface fades.
    private func finish() {
        guard !finishing else { return }
        finishing = true
        confirms += 1
        if session.persists { DefaultsLandingStore().markDone() }
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
        // Into Home: a re-run from Settings lands on Home, as a first run does (no animation beneath the landing).
        var quiet = Transaction()
        quiet.disablesAnimations = true
        withTransaction(quiet) {
            model.path = []
            model.section = .home
        }
        mascot.wave()
        withAnimation(Yoin.spatial) { handoff = true }
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(Int(350 / LaunchConfig.animationSpeed)))
            withAnimation(Yoin.slowSpatial) { fly = true }
            try? await Task.sleep(for: .milliseconds(Int(170 / LaunchConfig.animationSpeed)))
            withAnimation(Yoin.slowEffects) { reveal = 0 } completion: { model.landing = nil }
        }
    }

    /// A re-run closed from its first step: fades back to where it was opened.
    private func close() {
        guard !finishing else { return }
        finishing = true
        withAnimation(Yoin.slowEffects) { reveal = 0 } completion: { model.landing = nil }
    }

    // MARK: Accounts

    private var accounts: [LandingAccount] {
        // The account list (Data/Profiles.swift), the one in use first.
        let list = model.profiles.profiles.map { p in
            LandingAccount(id: p.id, name: p.title, detail: p.host ?? "Authorized",
                           service: p.service.landing,
                           inUse: p.id == model.profiles.activeId)
        }
        return list.sorted { $0.inUse && !$1.inUse }
    }
}

/// The Subsonic step's fields, kept while the user steps back and forth.
struct LandingSubsonicForm: Equatable {
    var address = ""
    var username = ""
    var password = ""
}

// MARK: Geometry

/// The rects the stage places things in, for one window size (Android landingFrame, phone column).
struct LandingFrame {
    static let pillHeight: CGFloat = 60

    let width: CGFloat
    let height: CGFloat
    let top: CGFloat
    let colX: CGFloat
    let colW: CGFloat
    let pillTop: CGFloat
    let pillRect: CGRect
    /// The shell bar's rect (ShellBar: 14 in, 2 above the home indicator's inset).
    let barRect: CGRect
    /// What the window stands on: the pill, or the keyboard when one is up.
    let floor: CGFloat

    init(size: CGSize, insets: EdgeInsets, keyboard: CGFloat) {
        width = size.width
        height = size.height
        top = insets.top
        let barW = size.width - 28
        colW = min(barW, 560)
        colX = (size.width - colW) / 2
        pillTop = size.height - insets.bottom - 2 - Self.pillHeight
        barRect = CGRect(x: 14, y: pillTop, width: barW, height: Self.pillHeight)
        pillRect = CGRect(x: colX, y: pillTop, width: colW, height: Self.pillHeight)
        floor = min(pillTop - 12, size.height - keyboard - 12)
    }
}

/// System back's preview pose on the stage (scale toward 0.9, shift toward the swipe edge within an 8 margin).
private struct StageBackPreview: ViewModifier {
    var progress: CGFloat
    var width: CGFloat
    func body(content: Content) -> some View {
        let scale = 1 - 0.1 * progress
        content
            .scaleEffect(scale)
            .offset(x: max(0, width * (1 - scale) / 2 - 8) * progress)
    }
}

// MARK: Bubble

/// The mascot's speech bubble: a rounded card whose tail points at the mascot.
struct LandingBubble: View {
    var title: String
    var body_: String?
    var tailUp: Bool
    var tailOffset: CGFloat
    var background: Color
    var content: Color
    var maxWidth: CGFloat

    init(title: String, body: String?, tailUp: Bool, tailOffset: CGFloat, background: Color, content: Color,
         maxWidth: CGFloat) {
        self.title = title
        self.body_ = body
        self.tailUp = tailUp
        self.tailOffset = tailOffset
        self.background = background
        self.content = content
        self.maxWidth = maxWidth
    }

    var body: some View {
        CapWidth(maxWidth: maxWidth) {
            VStack(alignment: .leading, spacing: 4) {
                // Lines swap whole at the bubble's dip (no cross-fade of two sentences).
                Text(title)
                    .contentTransition(.identity)
                    .font(YoinFont.rounded(19, semibold: true, relativeTo: .title3))
                    .lineSpacing(YoinType.lineSpacing(size: 19, lineHeight: 25))
                    .foregroundStyle(content)
                if let body_, !body_.isEmpty {
                    Text(body_)
                        .contentTransition(.identity)
                        .font(.gsf(15))
                        .lineSpacing(YoinType.lineSpacing(size: 15, lineHeight: 21))
                        .foregroundStyle(content.opacity(0.82))
                }
            }
            .fixedSize(horizontal: false, vertical: true)
            .padding(.leading, 18)
            .padding(.trailing, 18)
            .padding(.top, 13)
            .padding(.bottom, 14)
        }
        .background {
            ZStack(alignment: .topLeading) {
                RoundedRectangle(cornerRadius: 22, style: .continuous).fill(background)
                BubbleTail(up: tailUp, offset: tailOffset).fill(background)
            }
        }
        // A new line replaces the old one whole, at the bubble's dip: its text, size and tail never
        // cross-fade (the stage still moves and scales the bubble on its springs).
        .transaction { $0.animation = nil }
        .accessibilityElement(children: .combine)
    }
}

/// Proposes [maxWidth] to its content and takes the content's own size (a bubble hugs its text).
private struct CapWidth: Layout {
    var maxWidth: CGFloat
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        subviews.first?.sizeThatFits(ProposedViewSize(width: max(maxWidth, 1), height: nil)) ?? .zero
    }
    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        subviews.first?.place(at: bounds.origin, proposal: ProposedViewSize(width: bounds.width, height: bounds.height))
    }
}

/// A 22 × 11 tail with a rounded tip; [offset] is its tip along the edge.
private struct BubbleTail: Shape {
    var up: Bool
    var offset: CGFloat

    var animatableData: CGFloat {
        get { offset }
        set { offset = newValue }
    }

    func path(in rect: CGRect) -> Path {
        let half: CGFloat = 11, depth: CGFloat = 11
        var p = Path()
        if up {
            let x = min(max(offset, half + 8), max(half + 8, rect.width - half - 8))
            p.move(to: CGPoint(x: x - half, y: 1))
            p.addCurve(to: CGPoint(x: x, y: -depth), control1: CGPoint(x: x - half * 0.36, y: 1),
                       control2: CGPoint(x: x - half * 0.24, y: -depth))
            p.addCurve(to: CGPoint(x: x + half, y: 1), control1: CGPoint(x: x + half * 0.24, y: -depth),
                       control2: CGPoint(x: x + half * 0.36, y: 1))
        } else {
            let y = min(max(offset, half + 8), max(half + 8, rect.height - half - 8))
            p.move(to: CGPoint(x: 1, y: y - half))
            p.addCurve(to: CGPoint(x: -depth, y: y), control1: CGPoint(x: 1, y: y - half * 0.36),
                       control2: CGPoint(x: -depth, y: y - half * 0.24))
            p.addCurve(to: CGPoint(x: 1, y: y + half), control1: CGPoint(x: -depth, y: y + half * 0.24),
                       control2: CGPoint(x: 1, y: y + half * 0.36))
        }
        p.closeSubpath()
        return p
    }
}

// MARK: Pill

/// The landing's floating navigation, a twin of the shell's bar (same place and height) so the
/// hand-off can hand it to the bar. On Hello the whole pill is the main button.
private struct LandingPill: View {
    var hello: Bool
    var showBack: Bool
    var backIsClose: Bool
    var primary: LandingPrimary
    var dotCount: Int
    var dotIndex: Int
    var scheme: CoverScheme
    var onBack: () -> Void
    var onPrimary: () -> Void

    var body: some View {
        HStack(spacing: 0) {
            if showBack {
                Button(action: onBack) {
                    YoinIcon(backIsClose ? "close" : "back", size: 22)
                        .foregroundStyle(scheme.onSurface)
                        .frame(width: 48, height: 48)
                        .background(scheme.surfaceBright, in: .circle)
                        .contentShape(.circle)
                }
                .buttonStyle(PressScale())
                .accessibilityLabel(backIsClose ? "Close" : "Back")
                .transition(.scale(scale: 0.01).combined(with: .opacity))
            }
            ZStack {
                if !hello && dotCount > 1 {
                    LandingDots(count: dotCount, index: dotIndex, scheme: scheme)
                        .transition(.opacity)
                }
            }
            .frame(maxWidth: hello ? 0 : .infinity)
            primaryButton
        }
        .padding(.horizontal, 6)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .animation(Yoin.spatial, value: hello)
        .animation(Yoin.spatial, value: showBack)
    }

    private var primaryButton: some View {
        let (container, ink): (Color, Color) = switch primary.style {
        case .filled: (scheme.primary, scheme.onPrimary)
        case .tonal: (scheme.secondaryContainer, scheme.onSecondaryContainer)
        case .unavailable: (scheme.surfaceContainerHighest, scheme.onSurfaceVariant)
        }
        return Button(action: onPrimary) {
            HStack(spacing: 8) {
                ZStack {
                    Text(primary.label)
                        .id(primary.label)
                        .transition(.asymmetric(insertion: .offset(y: 14).combined(with: .opacity),
                                                removal: .offset(y: -14).combined(with: .opacity)))
                }
                if primary.arrow {
                    YoinIcon("chevron_right", size: 20)
                        .transition(.scale(scale: 0.01).combined(with: .opacity))
                }
            }
            .font(.gsf(14, .medium))
            .foregroundStyle(ink)
            .padding(.horizontal, 22)
            .frame(height: 48)
            .frame(maxWidth: hello ? .infinity : nil)
            .animation(Yoin.effects) { $0.background(container, in: .capsule) }
            .contentShape(.capsule)
        }
        .buttonStyle(PressScale())
        .animation(Yoin.spatial, value: primary)
        .accessibilityLabel(primary.label)
    }
}

/// The step dots: the current one long, the done ones half strength.
private struct LandingDots: View {
    var count: Int
    var index: Int
    var scheme: CoverScheme

    var body: some View {
        HStack(spacing: 6) {
            ForEach(0..<count, id: \.self) { i in
                Capsule()
                    .fill(i == index ? scheme.primary : i < index ? scheme.primary.opacity(0.55) : scheme.outlineVariant)
                    .frame(width: i == index ? 22 : 8, height: 8)
            }
        }
        .animation(Yoin.spatial, value: index)
        .animation(Yoin.spatial, value: count)
        .accessibilityElement()
        .accessibilityLabel("\(index + 1) / \(count)")
    }
}

// MARK: Backdrop

/// The landing's ground: the page colour with the launcher icon's navy glow and two slow brand
/// blooms (violet, pink). The drift runs only when motion is allowed.
private struct LandingBackdrop: View {
    var scheme: CoverScheme
    var dark: Bool
    var animate: Bool
    @State private var phase: CGFloat = 0.5

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width, h = geo.size.height, p = phase
            ZStack(alignment: .topLeading) {
                scheme.surface
                bloom(Color(hex: 0x092163, alpha: dark ? 0.95 : 0), light: Color(hex: 0x192396, alpha: 0.10),
                      at: CGPoint(x: w * (0.5 + 0.04 * p), y: h * (0.46 + 0.05 * p)), radius: max(w, h) * 0.62)
                bloom(Color(hex: 0x9113FF, alpha: dark ? 0.30 : 0), light: Color(hex: 0x9113FF, alpha: 0.16),
                      at: CGPoint(x: w * (0.05 + 0.18 * p), y: h * (0.12 + 0.1 * p)), radius: w * 0.62)
                bloom(Color(hex: 0xFF59CD, alpha: dark ? 0.17 : 0), light: Color(hex: 0xFF59CD, alpha: 0.15),
                      at: CGPoint(x: w * (1 - 0.14 * p), y: h * (0.5 + 0.16 * p)), radius: w * 0.55)
            }
        }
        .onAppear {
            guard animate else { return }
            phase = 0
            withAnimation(.linear(duration: 24).repeatForever(autoreverses: true)) { phase = 1 }
        }
        .accessibilityHidden(true)
    }

    private func bloom(_ darkColor: Color, light: Color, at c: CGPoint, radius r: CGFloat) -> some View {
        let color = dark ? darkColor : light
        return Circle()
            .fill(RadialGradient(colors: [color, color.opacity(0)], center: .center, startRadius: 0, endRadius: r))
            .frame(width: r * 2, height: r * 2)
            .offset(x: c.x - r, y: c.y - r)
    }
}

private extension Color {
    init(hex: UInt32, alpha: Double) {
        self.init(.sRGB, red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255, opacity: alpha)
    }
}

#Preview {
    LandingView(session: LandingSession(mode: .firstRun, persists: false, fadesIn: false))
        .environment(AppModel.shared)
}
