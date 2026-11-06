import SwiftUI

/// The names iOS's landing files read from iOS LaunchConfig (Theme.swift isn't on the Mac).
enum LaunchConfig {
    static var animationSpeed: Double { 1 }
    static var baseScreen: String { UserDefaults.standard.string(forKey: "screen") ?? "" }
}

/// The first-run landing on the Mac (iOS LandingView's first step, Android LandingHost): the mascot flies in
/// and lands as the mark, waves, and says hello in its bubble; Get started has it cheer and the Mac's sign-in
/// follows. Shown once per install, before any account (LandingGate: an install that already has one never
/// sees it).
struct MacLandingView: View {
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

    private var scheme: CoverScheme { .brand(dark: colorScheme == .dark) }

    var body: some View {
        let words = landingBubble(.hello, noAccounts: true)
        ZStack {
            scheme.surface
            EllipticalGradient(colors: [scheme.primaryContainer.opacity(0.9), scheme.surface],
                               center: .bottom, startRadiusFraction: 0, endRadiusFraction: 0.85)
            VStack(spacing: 22) {
                Spacer(minLength: 0)
                // The bubble: what the mascot says.
                VStack(alignment: .leading, spacing: 6) {
                    Text(words.title).font(.gsf(24, .semibold)).foregroundStyle(scheme.onSurface)
                    if let body = words.body {
                        Text(body).font(.gsf(16)).foregroundStyle(scheme.onSurfaceVariant)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .padding(.horizontal, 22).padding(.vertical, 18)
                .frame(maxWidth: 420, alignment: .leading)
                .background(scheme.surfaceContainerHigh, in: .rect(cornerRadius: 28, style: .continuous))
                .scaleEffect(bubbleIn ? 1 : 0.6, anchor: .bottom)
                .opacity(bubbleIn ? 1 : 0)
                LandingMascotView(model: mascot)
                    .frame(width: 260, height: 260 / LandingMascotView.aspect)
                Button {
                    guard !leaving else { return }
                    leaving = true
                    mascot.cheer()
                    Task {
                        try? await Task.sleep(for: .milliseconds(650))
                        onFinish()
                    }
                } label: {
                    HStack(spacing: 8) {
                        Text("Get started").font(.gsf(16, .semibold))
                        YoinIcon("chevron_right", size: 18)
                    }
                    .foregroundStyle(scheme.onPrimary)
                    .padding(.horizontal, 26).frame(height: 48)
                    .background(Capsule().fill(scheme.primary))
                    .contentShape(Capsule())
                }
                .buttonStyle(PressDown())
                .keyboardShortcut(.defaultAction)
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
}
