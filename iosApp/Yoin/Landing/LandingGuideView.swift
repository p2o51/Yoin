import SwiftUI
import UIKit
import WebKit

/// iOS take on the Android picture-in-picture connect guide: the Spotify
/// developer dashboard opens in an in-app web view and Yoin's floating glass
/// guide card sits on top, one step at a time. (Placeholder page; no network.)
struct LandingGuideView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.glassFlavor) private var flavor
    @State private var step = 2
    @State private var copied = false

    private struct Step {
        var text: String
        var copy: String?
    }

    private let steps: [Step] = [
        Step(text: "Tap Create app", copy: nil),
        Step(text: "Name it anything, like Yoin", copy: "Yoin"),
        Step(text: "Add this Redirect URI, then tap Add", copy: "yoin://spotify-callback"),
        Step(text: "Tick Web API and iOS", copy: nil),
        Step(text: "Paste the bundle ID", copy: "com.gpo.yoin"),
        Step(text: "Agree to the terms and Save", copy: nil),
        Step(text: "Copy the Client ID", copy: nil),
    ]

    private var tint: Color { Color(red: 0.12, green: 0.73, blue: 0.33) }

    var body: some View {
        ZStack(alignment: .bottom) {
            GuideWebView(html: Self.placeholderHTML)
                .ignoresSafeArea()
            card
                .padding(.horizontal, 12)
                .padding(.bottom, 6)
                .frame(maxWidth: 520)
        }
        .overlay(alignment: .topLeading) {
            Button { model.showLanding = false } label: {
                YoinIcon("close", size: 20)
                    .foregroundStyle(Yoin.ink)
                    .frame(width: 44, height: 44)
                    .contentShape(.circle)
            }
            .buttonStyle(.plain)
            .yoinGlass(Circle(), tint: tint, flavor: flavor)
            .padding(.leading, 16)
            .accessibilityLabel("Close guide")
        }
    }

    private var card: some View {
        let s = steps[step]
        return VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 10) {
                YoinMark()
                    .frame(width: 26, height: 26)
                Text("Step \(step + 1) of \(steps.count)")
                    .font(.gsf(14, .medium))
                    .foregroundStyle(Yoin.inkVariant)
                Spacer()
            }
            Text(s.text)
                .font(.gsf(22, .semibold))
                .foregroundStyle(Yoin.ink)
                .contentTransition(.opacity)
            if let value = s.copy {
                Text(value)
                    .font(.system(size: 15, weight: .medium, design: .monospaced))
                    .foregroundStyle(Yoin.ink)
                    .padding(.horizontal, 14)
                    .frame(maxWidth: .infinity, minHeight: 40, alignment: .leading)
                    .background(Yoin.surfaceContainerHigh, in: .rect(cornerRadius: 12))
            }
            GlassEffectContainer(spacing: 4) {
                HStack(spacing: 8) {
                    slot("chevron_left", "Previous", enabled: step > 0, prominent: false) {
                        step -= 1
                    }
                    slot("copy", copied ? "Copied" : "Copy", enabled: s.copy != nil, prominent: true) {
                        UIPasteboard.general.string = s.copy
                        copied = true
                    }
                    slot("chevron_right", "Next", enabled: step < steps.count - 1, prominent: false) {
                        step += 1
                    }
                }
            }
            HStack(spacing: 6) {
                ForEach(steps.indices, id: \.self) { i in
                    Capsule()
                        .fill(i == step ? tint : Yoin.outline)
                        .frame(width: i == step ? 22 : 7, height: 7)
                }
            }
            .frame(maxWidth: .infinity)
            .animation(Yoin.spatial, value: step)
        }
        .padding(20)
        .background(Yoin.surface.opacity(0.55), in: .rect(cornerRadius: 34))
        .glassEffect(.regular.tint(tint.opacity(0.08)), in: .rect(cornerRadius: 34))
        .animation(Yoin.spatial, value: step)
        .onChange(of: step) { copied = false }
    }

    private func slot(_ icon: String, _ title: String, enabled: Bool, prominent: Bool,
                      action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if icon != "chevron_right" { YoinIcon(icon, size: 18) }
                Text(title).font(.gsf(15, .semibold))
                if icon == "chevron_right" { YoinIcon(icon, size: 18) }
            }
            .foregroundStyle(prominent ? .white : Yoin.ink)
            .frame(maxWidth: .infinity, minHeight: 48)
            .contentShape(.capsule)
        }
        .buttonStyle(.plain)
        .yoinGlass(Capsule(), tint: tint, prominent: prominent, flavor: flavor)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.4)
    }

    static let placeholderHTML = """
    <html><head><meta name=viewport content="width=device-width,initial-scale=1">
    <style>
    body{margin:0;font-family:-apple-system,sans-serif;background:#121212;color:#fff}
    header{padding:66px 20px 16px 76px;background:#1e1e1e;font-weight:700;font-size:20px}
    .row{margin:16px 20px;padding:16px;border-radius:12px;background:#1e1e1e}
    .label{color:#9a9a9a;font-size:13px;margin-bottom:8px}
    .field{background:#2a2a2a;border-radius:8px;height:40px}
    .btn{display:inline-block;margin:8px 20px;padding:12px 22px;border-radius:24px;background:#1ed760;color:#000;font-weight:700}
    </style></head><body>
    <header>Developer Dashboard (placeholder)</header>
    <div class=row><div class=label>App name</div><div class=field></div></div>
    <div class=row><div class=label>App description</div><div class=field></div></div>
    <div class=row><div class=label>Redirect URIs</div><div class=field></div></div>
    <div class=row><div class=label>Which API/SDKs are you planning to use?</div><div class=field></div></div>
    <div class=row><div class=label>Bundle IDs</div><div class=field></div></div>
    <span class=btn>Save</span>
    </body></html>
    """
}

/// The Yoin mark: three arms (pink / violet / navy).
struct YoinMark: View {
    var body: some View {
        GeometryReader { geo in
            let s = geo.size.width
            ZStack {
                Capsule().fill(Color(red: 1, green: 0.35, blue: 0.80))
                    .frame(width: s * 0.3, height: s * 0.62)
                    .rotationEffect(.degrees(-35))
                    .offset(x: -s * 0.18, y: -s * 0.12)
                Capsule().fill(Color(red: 0.42, green: 0.31, blue: 0.85))
                    .frame(width: s * 0.3, height: s * 0.62)
                    .rotationEffect(.degrees(35))
                    .offset(x: s * 0.18, y: -s * 0.12)
                Capsule().fill(Color(red: 0.12, green: 0.16, blue: 0.38))
                    .frame(width: s * 0.3, height: s * 0.5)
                    .offset(y: s * 0.22)
            }
            .frame(width: s, height: s)
        }
        .accessibilityHidden(true)
    }
}

private struct GuideWebView: UIViewRepresentable {
    var html: String

    func makeUIView(context: Context) -> WKWebView {
        let view = WKWebView()
        view.isOpaque = false
        view.backgroundColor = .black
        view.scrollView.contentInsetAdjustmentBehavior = .never
        view.loadHTMLString(html, baseURL: nil)
        return view
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}
}
