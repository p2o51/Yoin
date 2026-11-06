import SwiftUI

/// iOS Components' press style, which the landing's scenes use (Components.swift isn't on the Mac).
struct PressScale: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.96 : 1)
            .animation(Yoin.spatialFast, value: configuration.isPressed)
    }
}

/// The names iOS's landing files read from iOS LaunchConfig (Theme.swift isn't on the Mac).
enum LaunchConfig {
    static var animationSpeed: Double { 1 }
    static var baseScreen: String { UserDefaults.standard.string(forKey: "screen") ?? "" }
}

/// The first-run landing on the Mac (iOS LandingView, Android LandingHost): the mascot flies in and says hello;
/// then What do you listen with? (iOS LandingPickScene) and Here's my part (LandingAboutScene); the Mac's
/// sign-in page follows (its connect steps are the sign-in page's). Shown once per install, before any account
/// (LandingGate); Settings › About › Welcome Guide runs it again over the app (a re-run: Back on the first
/// step closes it, and it ends back in the app).
struct MacLandingView: View {
    var mode: LandingMode = .firstRun
    var onFinish: () -> Void
    @Environment(\.colorScheme) private var colorScheme
    @State private var mascot: MascotModel = {
        let m = MascotModel()
        m.hideForIntro()
        return m
    }()
    @State private var bubbleIn = false
    @State private var buttonIn = false
    @State private var leaving = false
    @State private var step: LandingStep = .hello
    @State private var picked: Set<LandingService> = []
    @State private var forward = true

    private var scheme: CoverScheme { .brand(dark: colorScheme == .dark) }
    private static let steps: [LandingStep] = [.hello, .pick, .about]

    var body: some View {
        let words = landingBubble(step, noAccounts: true)
        ZStack {
            scheme.surface
            EllipticalGradient(colors: [scheme.primaryContainer.opacity(0.9), scheme.surface],
                               center: .bottom, startRadiusFraction: 0, endRadiusFraction: 0.85)
            VStack(spacing: 18) {
                Spacer(minLength: 0)
                HStack(alignment: .bottom, spacing: 16) {
                    LandingMascotView(model: mascot)
                        .frame(width: step == .hello ? 260 : 120, height: (step == .hello ? 260 : 120) / LandingMascotView.aspect)
                    // The bubble: what the mascot says on this step.
                    VStack(alignment: .leading, spacing: 6) {
                        Text(words.title).font(.gsf(step == .hello ? 24 : 20, .semibold)).foregroundStyle(scheme.onSurface)
                        if let body = words.body {
                            Text(body).font(.gsf(16)).foregroundStyle(scheme.onSurfaceVariant)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    .padding(.horizontal, 22).padding(.vertical, 18)
                    .frame(maxWidth: 420, alignment: .leading)
                    .background(scheme.surfaceContainerHigh, in: .rect(cornerRadius: 28, style: .continuous))
                    .scaleEffect(bubbleIn ? 1 : 0.6, anchor: .bottomLeading)
                    .opacity(bubbleIn ? 1 : 0)
                    .id(step)
                    .transition(.opacity)
                }
                // The step's scene (iOS's own).
                Group {
                    switch step {
                    case .pick:
                        LandingPickScene(picked: picked, scheme: scheme) { s in
                            withAnimation(Yoin.spatial) {
                                if picked.contains(s) { picked.remove(s) } else { picked.insert(s) }
                            }
                            mascot.nod()
                        }
                    case .about:
                        LandingAboutScene(picked: picked, scheme: scheme)
                    default:
                        EmptyView()
                    }
                }
                .frame(maxWidth: 460)
                .transition(.asymmetric(insertion: .move(edge: forward ? .trailing : .leading).combined(with: .opacity),
                                        removal: .move(edge: forward ? .leading : .trailing).combined(with: .opacity)))
                .id("scene-\(step.rawValue)")
                HStack(spacing: 12) {
                    if step != .hello || mode == .rerun {
                        Button(action: back) {
                            YoinIcon("chevron_left", size: 18).foregroundStyle(scheme.onSurface)
                                .frame(width: 48, height: 48)
                                .background(Circle().fill(scheme.surfaceContainerHigh))
                                .contentShape(Circle())
                        }
                        .buttonStyle(PressDown())
                        .keyboardShortcut(.cancelAction)
                        .help(step == .hello ? "Close" : "Back")
                    }
                    Button(action: primary) {
                        HStack(spacing: 8) {
                            Text(primaryLabel).font(.gsf(16, .semibold))
                            YoinIcon("chevron_right", size: 18)
                        }
                        .foregroundStyle(scheme.onPrimary)
                        .padding(.horizontal, 26).frame(height: 48)
                        .background(Capsule().fill(scheme.primary.opacity(primaryEnabled ? 1 : 0.38)))
                        .contentShape(Capsule())
                    }
                    .buttonStyle(PressDown())
                    .keyboardShortcut(.defaultAction)
                }
                .opacity(buttonIn ? 1 : 0)
                .offset(y: buttonIn ? 0 : 16)
                Spacer(minLength: 0)
            }
            .padding(32)
        }
        .ignoresSafeArea()
        .onAppear {
            mascot.intro {
                withAnimation(Yoin.spatial) { bubbleIn = true }
                withAnimation(Yoin.spatial.delay(0.15)) { buttonIn = true }
                mascot.wave(times: 2)
            }
        }
    }

    private var primaryEnabled: Bool { step != .pick || !picked.isEmpty || mode == .rerun }

    private var primaryLabel: String {
        switch step {
        case .hello: "Get started"
        case .pick: picked.isEmpty && mode == .rerun ? "Skip" : "Next"
        default: mode == .rerun ? "Done" : "Sign in"
        }
    }

    private func primary() {
        guard !leaving else { return }
        guard primaryEnabled else { mascot.shake(); return }
        guard let i = Self.steps.firstIndex(of: step), i + 1 < Self.steps.count else {
            leaving = true
            mascot.cheer()
            Task {
                try? await Task.sleep(for: .milliseconds(650))
                onFinish()
            }
            return
        }
        forward = true
        withAnimation(Yoin.spatial) { step = Self.steps[i + 1] }
    }

    private func back() {
        guard let i = Self.steps.firstIndex(of: step), i > 0 else {
            if mode == .rerun { onFinish() }
            return
        }
        forward = false
        withAnimation(Yoin.spatial) { step = Self.steps[i - 1] }
    }
}
