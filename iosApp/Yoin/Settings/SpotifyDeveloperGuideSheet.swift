import SafariServices
import SwiftUI

/// Extracts a 32-character hexadecimal Spotify Client ID from pasted text when present,
/// falling back to the trimmed text so manual values are never silently dropped.
enum SpotifyClientIdExtractor {
    private static let hex32 = try? NSRegularExpression(pattern: #"\b([0-9a-fA-F]{32})\b"#)

    static func extract(from raw: String) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "" }
        if let regex = hex32 {
            let range = NSRange(trimmed.startIndex..<trimmed.endIndex, in: trimmed)
            if let match = regex.firstMatch(in: trimmed, range: range),
               let idRange = Range(match.range(at: 1), in: trimmed) {
                return String(trimmed[idRange])
            }
        }
        return trimmed
    }
}

/// One step in the in-app Spotify for Developers dashboard walkthrough.
struct SpotifyGuideStep: Identifiable, Sendable {
    let id: Int
    let title: String
    let detail: String
    let copyLabel: String?
    let copyValue: String?

    static func iosSteps(
        redirectURI: String = SpotifyConfig.redirectURI,
        appRemoteRedirectURI: String = SpotifyConfig.appRemoteRedirectURI,
        bundleId: String? = Bundle.main.bundleIdentifier
    ) -> [SpotifyGuideStep] {
        var steps: [SpotifyGuideStep] = [
            SpotifyGuideStep(
                id: 1,
                title: String(localized: "Sign in & create app"),
                detail: String(localized: "Sign in with Spotify, then tap Create app."),
                copyLabel: nil,
                copyValue: nil
            ),
            SpotifyGuideStep(
                id: 2,
                title: String(localized: "Name your app"),
                detail: String(localized: "Enter Yoin as the app name and add a short description."),
                copyLabel: String(localized: "Copy Yoin"),
                copyValue: "Yoin"
            ),
            SpotifyGuideStep(
                id: 3,
                title: String(localized: "Add login Redirect URI"),
                detail: String(localized: "Paste the first Redirect URI and tap Add."),
                copyLabel: String(localized: "Copy login URI"),
                copyValue: redirectURI
            ),
            SpotifyGuideStep(
                id: 4,
                title: String(localized: "Add playback Redirect URI"),
                detail: String(localized: "Paste the second Redirect URI for Spotify App Remote and tap Add."),
                copyLabel: String(localized: "Copy playback URI"),
                copyValue: appRemoteRedirectURI
            ),
            SpotifyGuideStep(
                id: 5,
                title: String(localized: "Select Web API & iOS"),
                detail: String(localized: "Check Web API and iOS, then add the iOS Bundle ID below."),
                copyLabel: bundleId == nil ? nil : String(localized: "Copy Bundle ID"),
                copyValue: bundleId
            ),
            SpotifyGuideStep(
                id: 6,
                title: String(localized: "Save & copy Client ID"),
                detail: String(localized: "Agree and tap Save, open Settings, copy your Client ID, then tap Done."),
                copyLabel: nil,
                copyValue: nil
            ),
        ]
        if bundleId == nil {
            steps[4] = SpotifyGuideStep(
                id: 5,
                title: String(localized: "Select Web API & iOS"),
                detail: String(localized: "Check Web API and iOS under Which API/SDKs are you planning to use?"),
                copyLabel: nil,
                copyValue: nil
            )
        }
        return steps
    }
}

/// In-app Spotify Developer Dashboard container: a docked Yoin blue-purple step bar over an
/// embedded `SFSafariViewController` (preserving real Safari login state, Passkeys, and Google sign-in),
/// plus an escape hatch to external Safari.
struct SpotifyDeveloperGuideSheet: View {
    static let dashboardURL = URL(string: "https://developer.spotify.com/dashboard")!

    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var stepIndex = 0
    @State private var collapsed = false
    @State private var copied = false

    private let steps = SpotifyGuideStep.iosSteps()
    private var step: SpotifyGuideStep { steps[stepIndex] }

    private static let brandGradient = LinearGradient(
        colors: [
            Color(red: 0x1B / 255.0, green: 0x24 / 255.0, blue: 0x96 / 255.0),
            Color(red: 0x5A / 255.0, green: 0x16 / 255.0, blue: 0xC9 / 255.0),
        ],
        startPoint: .topLeading,
        endPoint: .bottomTrailing
    )

    var body: some View {
        VStack(spacing: 0) {
            guideBar
            SpotifyDashboardSafariView(url: Self.dashboardURL) {
                dismiss()
            }
            .ignoresSafeArea(edges: .bottom)
        }
        .background(Color(red: 0x1B / 255.0, green: 0x24 / 255.0, blue: 0x96 / 255.0))
        .animation(Yoin.spatial, value: stepIndex)
        .animation(Yoin.spatial, value: collapsed)
        .animation(Yoin.effects, value: copied)
    }

    private var guideBar: some View {
        VStack(alignment: .leading, spacing: collapsed ? 0 : 10) {
            HStack(spacing: 10) {
                Text("\(step.id)/\(steps.count)")
                    .yoinRole(Tokens.type.labelMedium)
                    .foregroundStyle(.white)
                    .padding(.horizontal, 9)
                    .padding(.vertical, 4)
                    .background(.white.opacity(0.2), in: .capsule)

                Text(step.title)
                    .yoinRole(Tokens.type.titleSmall)
                    .foregroundStyle(.white)
                    .lineLimit(1)

                Spacer(minLength: 4)

                Button {
                    openURL(Self.dashboardURL)
                } label: {
                    Text("Safari")
                        .yoinRole(Tokens.type.labelMedium)
                        .foregroundStyle(.white.opacity(0.92))
                        .padding(.horizontal, 10)
                        .frame(minHeight: 30)
                        .background(.white.opacity(0.16), in: .capsule)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(String(localized: "Open in Safari"))

                Button {
                    collapsed.toggle()
                } label: {
                    YoinIcon("chevron_down", size: 20)
                        .foregroundStyle(.white)
                        .rotationEffect(.degrees(collapsed ? 0 : 180))
                        .frame(width: 30, height: 30)
                        .background(.white.opacity(0.16), in: .circle)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(collapsed ? String(localized: "Expand guide") : String(localized: "Collapse guide"))

                Button {
                    dismiss()
                } label: {
                    Text("Done")
                        .yoinRole(Tokens.type.labelLarge)
                        .foregroundStyle(Color(red: 0x1B / 255.0, green: 0x24 / 255.0, blue: 0x96 / 255.0))
                        .padding(.horizontal, 12)
                        .frame(minHeight: 30)
                        .background(.white, in: .capsule)
                }
                .buttonStyle(.plain)
            }

            if !collapsed {
                Text(step.detail)
                    .yoinRole(Tokens.type.bodyMedium)
                    .foregroundStyle(.white.opacity(0.9))
                    .fixedSize(horizontal: false, vertical: true)

                HStack(spacing: 8) {
                    Button {
                        if stepIndex > 0 {
                            stepIndex -= 1
                            copied = false
                        }
                    } label: {
                        YoinIcon("back", size: 18)
                            .foregroundStyle(.white.opacity(stepIndex > 0 ? 1 : 0.35))
                            .frame(width: 36, height: 36)
                            .background(.white.opacity(0.16), in: .circle)
                    }
                    .buttonStyle(.plain)
                    .disabled(stepIndex == 0)
                    .accessibilityLabel(String(localized: "Previous step"))

                    if let value = step.copyValue, let label = step.copyLabel {
                        Button {
                            UIPasteboard.general.string = value
                            copied = true
                            Task {
                                try? await Task.sleep(for: .seconds(1.4))
                                copied = false
                            }
                        } label: {
                            HStack(spacing: 6) {
                                if copied {
                                    YoinIcon("check", size: 16)
                                }
                                Text(copied ? String(localized: "Copied") : label)
                                    .yoinRole(Tokens.type.labelLarge)
                                    .lineLimit(1)
                            }
                            .foregroundStyle(Color(red: 0x1B / 255.0, green: 0x24 / 255.0, blue: 0x96 / 255.0))
                            .padding(.horizontal, 14)
                            .frame(maxWidth: .infinity, minHeight: 36)
                            .background(.white, in: .capsule)
                        }
                        .buttonStyle(PressScale())
                    } else {
                        Spacer(minLength: 0)
                    }

                    Button {
                        if stepIndex + 1 < steps.count {
                            stepIndex += 1
                            copied = false
                        } else {
                            dismiss()
                        }
                    } label: {
                        HStack(spacing: 4) {
                            Text(stepIndex + 1 < steps.count ? String(localized: "Next") : String(localized: "Done"))
                                .yoinRole(Tokens.type.labelLarge)
                            if stepIndex + 1 < steps.count {
                                YoinIcon("chevron_right", size: 16)
                            }
                        }
                        .foregroundStyle(.white)
                        .padding(.horizontal, 14)
                        .frame(minHeight: 36)
                        .background(.white.opacity(0.22), in: .capsule)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 12)
        .padding(.bottom, 12)
        .background(Self.brandGradient)
    }
}

/// Embedded `SFSafariViewController` for `SpotifyDeveloperGuideSheet`.
private struct SpotifyDashboardSafariView: UIViewControllerRepresentable {
    let url: URL
    let onFinish: @MainActor @Sendable () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onFinish: onFinish) }

    func makeUIViewController(context: Context) -> SFSafariViewController {
        let config = SFSafariViewController.Configuration()
        config.entersReaderIfAvailable = false
        config.barCollapsingEnabled = true
        let vc = SFSafariViewController(url: url, configuration: config)
        vc.dismissButtonStyle = .close
        vc.preferredControlTintColor = UIColor(red: 0x5A / 255.0, green: 0x16 / 255.0, blue: 0xC9 / 255.0, alpha: 1)
        vc.delegate = context.coordinator
        return vc
    }

    func updateUIViewController(_ uiViewController: SFSafariViewController, context: Context) {}

    final class Coordinator: NSObject, SFSafariViewControllerDelegate, @unchecked Sendable {
        let onFinish: @MainActor @Sendable () -> Void
        init(onFinish: @escaping @MainActor @Sendable () -> Void) { self.onFinish = onFinish }
        nonisolated func safariViewControllerDidFinish(_ controller: SFSafariViewController) {
            let finish = onFinish
            Task { @MainActor in finish() }
        }
    }
}
