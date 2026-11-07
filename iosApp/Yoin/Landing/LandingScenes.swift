import SwiftUI
import UIKit

// The steps' contents inside the landing's floating window (Android
// LandingScenes.kt, LandingConnect.kt). Connecting goes through the same
// session objects Settings uses (ServerSession, AppleMusicSource); only the
// faces differ, and the mascot reacts.

/// What the mascot does about a connect step.
struct LandingReactions {
    var refused: () -> Void
    var connected: (LandingService) -> Void
}

/// A service's colours: Subsonic blue, Spotify green, Apple Music rose (Android SettingsHue).
struct LandingTone {
    let scheme: CoverScheme
    var accent: Color { scheme.primary }
    var onAccent: Color { scheme.onPrimary }
    var container: Color { scheme.primaryContainer }
    var onContainer: Color { scheme.onPrimaryContainer }
    var iconContainer: Color { scheme.secondaryContainer }
    var iconContent: Color { scheme.onSecondaryContainer }

    init(_ service: LandingService, dark: Bool) {
        let seed = switch service {
        case .subsonic: 0xFF3D6FD6
        case .spotify: 0xFF1DB954
        case .appleMusic: 0xFFF0435E
        }
        scheme = CoverScheme(seedArgb: seed, dark: dark)
    }
}

extension LandingService {
    /// Yoin Symbols only (no brand logos are redrawn).
    var glyph: String {
        switch self {
        case .subsonic: "library"
        case .spotify: "equalizer"
        case .appleMusic: "music_note"
        }
    }

    var pickLine: String {
        switch self {
        case .subsonic: String(localized: "Navidrome, Gonic and other servers")
        case .spotify: String(localized: "Premium account")
        case .appleMusic: String(localized: "Subscription")
        }
    }

    var needLine: String {
        switch self {
        case .subsonic: String(localized: "A Subsonic-compatible server")
        case .spotify: String(localized: "Spotify Premium and the Spotify app")
        case .appleMusic: String(localized: "An Apple Music subscription")
        }
    }

    var tagline: String {
        switch self {
        case .subsonic: String(localized: "Navidrome, Gonic and other servers")
        case .spotify: String(localized: "Your Spotify library, played in the Spotify app")
        case .appleMusic: String(localized: "Your Apple Music library and catalog")
        }
    }
}

/// A scalloped circle whose lobes grow in (0 = a circle): Android's Circle → Cookie9Sided morph.
struct LandingCookie: Shape {
    var depth: CGFloat
    var lobes = 9

    var animatableData: CGFloat {
        get { depth }
        set { depth = newValue }
    }

    func path(in rect: CGRect) -> Path {
        CookieShape(lobes: lobes, depth: depth).path(in: rect)
    }
}

// MARK: Shared pieces

/// A step's content: 18 at the sides, 20 above and below.
struct LandingSceneColumn<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 0) { content }
            .padding(.horizontal, 18)
            .padding(.vertical, 20)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct LandingLabel: View {
    var text: String
    var scheme: CoverScheme
    var body: some View {
        Text(text)
            .yoinRole(Tokens.type.labelLarge)
            .foregroundStyle(scheme.primary)
            .padding(.horizontal, 6)
            .padding(.top, 4)
            .padding(.bottom, 8)
            .accessibilityAddTraits(.isHeader)
    }
}

/// Rows on surfaceBright, 22 corners.
struct LandingGroup<Content: View>: View {
    var scheme: CoverScheme
    @ViewBuilder var content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 0) { content }
            .padding(.vertical, 5)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(scheme.surfaceBright, in: .rect(cornerRadius: 22))
            .padding(.bottom, 8)
    }
}

private struct MarkRow: View {
    var text: String
    var yes: Bool
    var scheme: CoverScheme
    var body: some View {
        HStack(spacing: 12) {
            YoinIcon(yes ? "check" : "close", size: 16)
                .foregroundStyle(yes ? scheme.onPrimary : scheme.onSurfaceVariant)
                .frame(width: 24, height: 24)
                .background(yes ? scheme.primary : scheme.surfaceContainerHighest, in: .circle)
            Text(text)
                .yoinRole(Tokens.type.bodyLarge)
                .foregroundStyle(scheme.onSurface)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
    }
}

// MARK: Pick

struct LandingPickScene: View {
    var picked: Set<LandingService>
    var scheme: CoverScheme
    var onToggle: (LandingService) -> Void

    var body: some View {
        LandingSceneColumn {
            VStack(spacing: 8) {
                ForEach(LandingService.allCases, id: \.self) { service in
                    ServiceTile(service: service, selected: picked.contains(service), scheme: scheme) {
                        onToggle(service)
                    }
                }
            }
        }
    }
}

private struct ServiceTile: View {
    var service: LandingService
    var selected: Bool
    var scheme: CoverScheme
    var onTap: () -> Void
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let tone = LandingTone(service, dark: colorScheme == .dark)
        let enabled = service.availableOnIOS
        Button(action: onTap) {
            HStack(spacing: 14) {
                ZStack {
                    LandingCookie(depth: selected ? 0.16 : 0)
                        .fill(selected ? tone.accent : tone.iconContainer)
                        .rotationEffect(.degrees(selected ? 22.5 : 0))
                    YoinIcon(service.glyph, size: 24)
                        .foregroundStyle(selected ? tone.onAccent : tone.iconContent)
                }
                .frame(width: 44, height: 44)
                VStack(alignment: .leading, spacing: 0) {
                    Text(service.name)
                        .yoinRole(Tokens.type.titleMedium, weight: .semibold)
                        .foregroundStyle(selected ? tone.onContainer : scheme.onSurface)
                    Text(service.pickLine)
                        .yoinRole(Tokens.type.bodyMedium)
                        .foregroundStyle((selected ? tone.onContainer : scheme.onSurface).opacity(0.78))
                }
                Spacer(minLength: 8)
                ZStack {
                    Circle().fill(selected ? tone.accent : .clear)
                    Circle().strokeBorder(selected ? tone.accent : scheme.outline, lineWidth: 2)
                    YoinIcon("check", size: 18)
                        .foregroundStyle(tone.onAccent)
                        .scaleEffect(selected ? 1 : 0.01)
                }
                .frame(width: 26, height: 26)
                .opacity(enabled ? 1 : 0)
            }
            .padding(.leading, 14)
            .padding(.trailing, 16)
            .padding(.vertical, 14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(selected ? tone.container : scheme.surfaceBright,
                        in: .rect(cornerRadius: selected ? 32 : 24))
            .contentShape(.rect(cornerRadius: 24))
        }
        .buttonStyle(PressScale())
        .disabled(!enabled)
        // A service that can't be picked: shown at half strength (Android's unavailable provider row).
        .opacity(enabled ? 1 : 0.5)
        .animation(Yoin.effects, value: selected)
        .animation(Yoin.spatialFast, value: selected)
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
    }
}

// MARK: About

struct LandingAboutScene: View {
    var picked: Set<LandingService>
    var scheme: CoverScheme
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        LandingSceneColumn {
            LandingLabel(text: String(localized: "Yoin does"), scheme: scheme)
            LandingGroup(scheme: scheme) {
                MarkRow(text: String(localized: "Play your library, with synced lyrics"), yes: true, scheme: scheme)
                MarkRow(text: String(localized: "Ratings, notes and Memories"), yes: true, scheme: scheme)
                MarkRow(text: String(localized: "Switch between your server and Apple Music"), yes: true, scheme: scheme)
                MarkRow(text: String(localized: "Keep passwords on this device"), yes: true, scheme: scheme)
            }
            LandingLabel(text: String(localized: "Yoin doesn't"), scheme: scheme)
            LandingGroup(scheme: scheme) {
                MarkRow(text: String(localized: "Sell music or subscriptions"), yes: false, scheme: scheme)
                MarkRow(text: String(localized: "Host or upload your files"), yes: false, scheme: scheme)
            }
            LandingLabel(text: String(localized: "You'll need"), scheme: scheme)
            LandingGroup(scheme: scheme) {
                let services = LandingService.allCases.filter { picked.contains($0) }
                if services.isEmpty {
                    MarkRow(text: String(localized: "One of the services above"), yes: false, scheme: scheme)
                } else {
                    ForEach(services, id: \.self) { service in
                        let tone = LandingTone(service, dark: colorScheme == .dark)
                        HStack(spacing: 12) {
                            YoinIcon(service.glyph, size: 15)
                                .foregroundStyle(tone.onAccent)
                                .frame(width: 24, height: 24)
                                .background(tone.accent, in: CookieShape(lobes: 9, depth: 0.16))
                            Text(service.needLine)
                                .yoinRole(Tokens.type.bodyLarge)
                                .foregroundStyle(scheme.onSurface)
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 8)
                    }
                }
            }
        }
    }
}

// MARK: Connect pieces

/// The service's face at the top of its step: avatar, name, tagline.
private struct ServiceHeader: View {
    var service: LandingService
    var scheme: CoverScheme
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let tone = LandingTone(service, dark: colorScheme == .dark)
        HStack(spacing: 14) {
            YoinIcon(service.glyph, size: 26)
                .foregroundStyle(tone.onAccent)
                .frame(width: 48, height: 48)
                .background(tone.accent, in: CookieShape(lobes: 9, depth: 0.16))
            VStack(alignment: .leading, spacing: 0) {
                Text(service.name)
                    .yoinRole(Tokens.type.titleLarge, weight: .semibold)
                    .foregroundStyle(scheme.onSurface)
                Text(service.tagline)
                    .yoinRole(Tokens.type.bodyMedium)
                    .foregroundStyle(scheme.onSurfaceVariant)
            }
        }
        .padding(.bottom, 16)
    }
}

/// The step's main button in the service's colour; busy while working, settles into "Connected".
private struct ServiceCta: View {
    var service: LandingService
    var label: String
    var busyLabel: String?
    var done: Bool
    var action: () -> Void
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let tone = LandingTone(service, dark: colorScheme == .dark)
        Button {
            guard !done, busyLabel == nil else { return }
            // The keyboard goes down so the answer (the mascot, an error line) is in view.
            UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
            action()
        } label: {
            HStack(spacing: 10) {
                if done {
                    YoinIcon("check", size: 20)
                    Text("Connected")
                } else if let busyLabel {
                    ProgressView().tint(tone.onAccent)
                    Text(busyLabel)
                } else {
                    Text(label)
                }
            }
            .yoinRole(Tokens.type.titleSmall)
            .foregroundStyle(done ? tone.onContainer : tone.onAccent)
            .frame(maxWidth: .infinity, minHeight: 56)
            .background(done ? tone.container : tone.accent, in: .capsule)
            .contentShape(.capsule)
            .contentTransition(.opacity)
        }
        .buttonStyle(PressScale())
        .padding(.top, 12)
        .animation(Yoin.effects, value: done)
        .animation(Yoin.effects, value: busyLabel)
        .accessibilityLabel(done ? String(localized: "Connected") : busyLabel ?? label)
    }
}

struct ErrorLine: View {
    var text: String?
    var scheme: CoverScheme
    var body: some View {
        if let text {
            Text(text)
                .yoinRole(Tokens.type.bodyMedium)
                .foregroundStyle(scheme.error)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 8)
                .padding(.top, 6)
                .transition(.opacity.combined(with: .move(edge: .top)))
        }
    }
}

/// A refused form shakes sideways (Android shake: the mascot's shake spring, 900 px/s).
private struct ShakeOnRefuse: ViewModifier {
    var trigger: Int
    func body(content: Content) -> some View {
        content.keyframeAnimator(initialValue: 0.0, trigger: trigger) { view, x in
            view.offset(x: x)
        } keyframes: { _ in
            SpringKeyframe(0.0, duration: 0.9,
                           spring: Spring(mass: 1, stiffness: 520, damping: 2 * 0.18 * 520.0.squareRoot()),
                           startVelocity: 340)
        }
    }
}

/// Android ExpressiveTextField: label above, the value on surfaceBright.
private struct LandingField: View {
    var label: String
    var placeholder: String
    @Binding var text: String
    var secure = false
    var keyboard: UIKeyboardType = .default
    var focused: Bool
    var scheme: CoverScheme

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .yoinRole(Tokens.type.labelMedium)
                .foregroundStyle(focused ? scheme.primary : scheme.onSurfaceVariant)
                .animation(Yoin.effects, value: focused)
            let prompt = Text(placeholder).foregroundStyle(scheme.onSurfaceVariant.opacity(0.6))
            Group {
                if secure {
                    SecureField("", text: $text, prompt: prompt)
                } else {
                    TextField("", text: $text, prompt: prompt).keyboardType(keyboard)
                }
            }
            .font(.gsf(Tokens.type.bodyLarge.size.cg))
            .foregroundStyle(scheme.onSurface)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(scheme.surfaceBright, in: .rect(cornerRadius: 18))
        .overlay {
            RoundedRectangle(cornerRadius: 18)
                .strokeBorder(scheme.primary, lineWidth: 2)
                .opacity(focused ? 1 : 0)
                .animation(Yoin.effects, value: focused)
        }
    }
}

// MARK: Subsonic

/// Connect = reach the server, then save: an unreachable server never becomes an account
/// (ProfileManager.connectSubsonic pings first; Settings › Add account takes the same path).
struct LandingSubsonicScene: View {
    @Binding var form: LandingSubsonicForm
    var connected: Bool
    var scheme: CoverScheme
    var reactions: LandingReactions
    /// Bumped by the DEBUG script driver (LandingView `-landingAuto`): Connect, as if tapped.
    var connectRequests = 0
    @Environment(AppModel.self) private var model
    @State private var working = false
    @State private var error: String?
    @State private var shakes = 0
    @FocusState private var focus: Field?

    private enum Field: Hashable { case address, username, password }

    var body: some View {
        LandingSceneColumn {
            ServiceHeader(service: .subsonic, scheme: scheme)
            VStack(spacing: 8) {
                LandingField(label: String(localized: "Server address"), placeholder: "https://music.example.com", text: $form.address,
                             keyboard: .URL, focused: focus == .address, scheme: scheme)
                    .focused($focus, equals: .address)
                    .submitLabel(.next)
                    .onSubmit { focus = .username }
                LandingField(label: String(localized: "Username"), placeholder: String(localized: "Your username"), text: $form.username,
                             focused: focus == .username, scheme: scheme)
                    .focused($focus, equals: .username)
                    .submitLabel(.next)
                    .onSubmit { focus = .password }
                LandingField(label: String(localized: "Password"), placeholder: String(localized: "Password"), text: $form.password, secure: true,
                             focused: focus == .password, scheme: scheme)
                    .focused($focus, equals: .password)
                    .submitLabel(.go)
                    .onSubmit(connect)
            }
            .modifier(ShakeOnRefuse(trigger: shakes))
            ErrorLine(text: error, scheme: scheme)
            ServiceCta(service: .subsonic, label: String(localized: "Connect"), busyLabel: working ? String(localized: "Reaching your server…") : nil,
                       done: connected, action: connect)
        }
        .animation(Yoin.spatial, value: error)
        .onChange(of: form) { if error != nil { error = nil } }
        .onChange(of: connectRequests) { connect() }
    }

    private func connect() {
        guard !working, !connected else { return }
        focus = nil
        let missing = form.address.trimmingCharacters(in: .whitespaces).isEmpty ? String(localized: "Server address is required")
            : form.username.isEmpty ? String(localized: "Username is required")
            : form.password.isEmpty ? String(localized: "Password is required") : nil
        if let missing {
            refuse(missing)
            return
        }
        working = true
        Task {
            // The first account connected becomes the account in use; another one is added beside it.
            let failure = await model.profiles.connectSubsonic(baseUrl: form.address, username: form.username,
                                                               password: form.password, activate: !model.store.isReal)
            working = false
            if let failure {
                refuse(failure)
            } else {
                reactions.connected(.subsonic)
            }
        }
    }

    private func refuse(_ message: String) {
        error = message
        shakes += 1
        reactions.refused()
    }
}

// MARK: Spotify

/// Spotify needs the user's own app on Spotify for Developers (Android SpotifyConnectScene, minus its
/// clipboard read and the Spotoolfy line): open the dashboard, paste the Client ID, connect. The sign-in
/// itself is ProfileManager.connectSpotify, the call Settings › Add account makes. A Client ID that is
/// already there (built into this build, or set earlier in Settings) skips the two guide steps.
struct LandingSpotifyScene: View {
    var connected: Bool
    var scheme: CoverScheme
    var reactions: LandingReactions
    /// "12 notes are now in <account>": the Spotoolfy file went into the account this step connected.
    var importedLine: String? = nil
    @Environment(AppModel.self) private var model
    @Environment(\.openURL) private var openURL
    @State private var clientId = LandingSpotifyScene.savedClientId
    @State private var urisOpen = false
    @State private var working = false
    @State private var error: String?
    @State private var shakes = 0
    @FocusState private var focused: Bool

    /// The ID set in Settings (empty when there is none).
    private static var savedClientId: String {
        UserDefaults.standard.string(forKey: SpotifyConfig.clientIdKey)?.trimmingCharacters(in: .whitespaces) ?? ""
    }

    /// A Client ID this build ships with and nothing overrides: nothing to ask for.
    private var builtIn: Bool { Self.savedClientId.isEmpty && clientId.isEmpty && SpotifyConfig.clientId != nil }

    var body: some View {
        LandingSceneColumn {
            ServiceHeader(service: .spotify, scheme: scheme)
            if builtIn {
                HStack(spacing: 10) {
                    YoinIcon("info", size: 18)
                        .foregroundStyle(scheme.onSurfaceVariant)
                    Text("Needs Spotify Premium and the Spotify app")
                        .yoinRole(Tokens.type.bodyMedium)
                        .foregroundStyle(scheme.onSurfaceVariant)
                }
                .padding(.leading, 6)
            } else {
                GuideStep(number: 1, title: String(localized: "Create an app"), scheme: scheme) {
                    Text("Free with a Premium account.")
                        .yoinRole(Tokens.type.bodyMedium)
                        .foregroundStyle(scheme.onSurfaceVariant)
                    Button {
                        focused = false
                        if let url = URL(string: "https://developer.spotify.com/dashboard") { openURL(url) }
                    } label: {
                        Text("Open Spotify for Developers")
                            .yoinRole(Tokens.type.labelLarge)
                            .foregroundStyle(scheme.onSecondaryContainer)
                            .padding(.horizontal, 18)
                            .frame(minHeight: 40)
                            .background(scheme.secondaryContainer, in: .capsule)
                            .contentShape(.capsule)
                    }
                    .buttonStyle(PressScale())
                    .padding(.top, 8)
                }
                GuideStep(number: 2, title: String(localized: "Paste the Client ID"), scheme: scheme) {
                    LandingField(label: String(localized: "Client ID"), placeholder: String(localized: "From your app on developer.spotify.com"),
                                 text: $clientId, focused: focused, scheme: scheme)
                        .focused($focused)
                        .submitLabel(.go)
                        .onSubmit(connect)
                        .modifier(ShakeOnRefuse(trigger: shakes))
                        .padding(.top, 6)
                }
                redirectUris
            }
            ErrorLine(text: error, scheme: scheme)
            ServiceCta(service: .spotify, label: String(localized: "Continue"), busyLabel: working ? String(localized: "Waiting for Spotify…") : nil,
                       done: connected, action: connect)
            if let importedLine {
                Text(importedLine)
                    .yoinRole(Tokens.type.bodyMedium)
                    .foregroundStyle(scheme.onSurfaceVariant)
                    .padding(.horizontal, 8)
                    .padding(.top, 10)
            }
        }
        .animation(Yoin.spatial, value: error)
        .animation(Yoin.spatial, value: urisOpen)
        .animation(Yoin.spatial, value: importedLine)
        .onChange(of: clientId) { if error != nil { error = nil } }
    }

    /// What the dashboard asks for, folded away, one value per row.
    private var redirectUris: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button { urisOpen.toggle() } label: {
                HStack {
                    Text("For the dashboard")
                        .yoinRole(Tokens.type.labelLarge)
                        .foregroundStyle(scheme.onSurfaceVariant)
                    Spacer()
                    YoinIcon("chevron_down", size: 22)
                        .foregroundStyle(scheme.onSurfaceVariant)
                        .rotationEffect(.degrees(urisOpen ? 180 : 0))
                }
                .padding(.horizontal, 14)
                .frame(minHeight: 48)
                .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .accessibilityAddTraits(.isButton)
            if urisOpen {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Redirect URIs")
                        .yoinRole(Tokens.type.labelMedium)
                        .foregroundStyle(scheme.onSurfaceVariant)
                    CopyRow(value: SpotifyConfig.redirectURI, scheme: scheme)
                    CopyRow(value: SpotifyConfig.appRemoteRedirectURI, scheme: scheme)
                    if let bundle = Bundle.main.bundleIdentifier {
                        Text("iOS bundle ID")
                            .yoinRole(Tokens.type.labelMedium)
                            .foregroundStyle(scheme.onSurfaceVariant)
                            .padding(.top, 4)
                        CopyRow(value: bundle, scheme: scheme)
                    }
                }
                .padding(.horizontal, 10)
                .padding(.bottom, 10)
                .transition(.opacity.combined(with: .scale(scale: 0.96, anchor: .top)))
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(scheme.surfaceBright, in: .rect(cornerRadius: 18))
        .clipShape(.rect(cornerRadius: 18))
    }

    private func connect() {
        guard !working, !connected else { return }
        focused = false
        let id = clientId.trimmingCharacters(in: .whitespacesAndNewlines)
        if !builtIn {
            guard !id.isEmpty else {
                error = String(localized: "Paste the Client ID first")
                shakes += 1
                reactions.refused()
                return
            }
            SpotifyConfig.setClientId(id)
        }
        working = true
        error = nil
        Task {
            let before = Set(model.profiles.profiles.map(\.id))
            let failure = await model.profiles.connectSpotify(activate: !model.store.isReal)
            working = false
            if let failure {
                error = failure
                shakes += 1
                reactions.refused()
            } else if model.profiles.profiles.contains(where: { $0.service == .spotify && !before.contains($0.id) }) {
                reactions.connected(.spotify)
            }
            // Cancelled in the sign-in sheet: no account, nothing to say.
        }
    }
}

/// A numbered guide step: the number in a circle, the title, then its content.
private struct GuideStep<Content: View>: View {
    var number: Int
    var title: String
    var scheme: CoverScheme
    @ViewBuilder var content: Content

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Text("\(number)")
                .yoinRole(Tokens.type.labelLarge)
                .foregroundStyle(scheme.onSecondaryContainer)
                .frame(width: 28, height: 28)
                .background(scheme.secondaryContainer, in: .circle)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .yoinRole(Tokens.type.titleMedium, weight: .semibold)
                    .foregroundStyle(scheme.onSurface)
                    .padding(.top, 2)
                content
            }
            Spacer(minLength: 0)
        }
        .padding(.bottom, 14)
    }
}

/// A value to copy: tap, and it says "Copied" for a moment.
/// A value to paste elsewhere (a redirect URI, the bundle ID): one line, tap to copy. Also Settings' Spotify setup.
struct CopyRow: View {
    var value: String
    var scheme: CoverScheme
    @State private var copied = false

    var body: some View {
        Button {
            UIPasteboard.general.string = value
            copied = true
            Task {
                try? await Task.sleep(for: .seconds(1.4))
                copied = false
            }
        } label: {
            HStack(spacing: 8) {
                Text(value)
                    .yoinRole(Tokens.type.bodyMedium)
                    .foregroundStyle(scheme.onSurface)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                Spacer(minLength: 4)
                if copied {
                    Text("Copied")
                        .yoinRole(Tokens.type.labelMedium)
                        .foregroundStyle(scheme.primary)
                        .transition(.opacity)
                } else {
                    YoinIcon("copy", size: 18).foregroundStyle(scheme.onSurfaceVariant)
                }
            }
            .padding(.horizontal, 12)
            .frame(minHeight: 40)
            .background(scheme.surfaceContainerHighest, in: .rect(cornerRadius: 12))
            .contentShape(.rect(cornerRadius: 12))
        }
        .buttonStyle(.plain)
        .animation(Yoin.effects, value: copied)
        .accessibilityLabel("Copy \(value)")
    }
}

// MARK: Apple Music

/// MusicKit on iOS needs no developer token (Android's token-service choice has
/// nothing to choose here): connecting is Apple's own permission prompt.
struct LandingAppleScene: View {
    var connected: Bool
    var scheme: CoverScheme
    var reactions: LandingReactions
    @Environment(AppModel.self) private var model
    @State private var working = false
    @State private var shakes = 0

    private var apple: AppleMusicSource { model.appleMusic }
    private var blocked: Bool { apple.authorization == .denied || apple.authorization == .restricted }

    var body: some View {
        LandingSceneColumn {
            ServiceHeader(service: .appleMusic, scheme: scheme)
            HStack(spacing: 10) {
                YoinIcon("info", size: 18)
                    .foregroundStyle(scheme.onSurfaceVariant)
                Text("Needs an Apple Music subscription")
                    .yoinRole(Tokens.type.bodyMedium)
                    .foregroundStyle(scheme.onSurfaceVariant)
            }
            .padding(.leading, 6)
            .modifier(ShakeOnRefuse(trigger: shakes))
            ErrorLine(text: blocked && !connected ? String(localized: "Apple Music access is off for Yoin") : nil, scheme: scheme)
            ServiceCta(service: .appleMusic, label: blocked ? String(localized: "Open Settings") : String(localized: "Connect Apple Music"),
                       busyLabel: working ? String(localized: "Working…") : nil, done: connected, action: connect)
        }
        .animation(Yoin.spatial, value: blocked)
        .onAppear { apple.refreshAuthorization() }
    }

    private func connect() {
        if blocked {
            if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
            return
        }
        working = true
        Task {
            await apple.requestAuthorization()
            working = false
            if apple.isAuthorized, model.profiles.addAppleMusic(activate: !model.store.isReal) {
                reactions.connected(.appleMusic)
            } else {
                shakes += 1
                reactions.refused()
            }
        }
    }
}

// MARK: Ready

/// An account the Ready step lists.
struct LandingAccount: Identifiable, Equatable {
    var id: String
    var name: String
    var detail: String
    var service: LandingService
    var inUse: Bool
}

struct LandingReadyScene: View {
    var accounts: [LandingAccount]
    var scheme: CoverScheme
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        LandingSceneColumn {
            LandingLabel(text: String(localized: "Accounts"), scheme: scheme)
            if accounts.isEmpty {
                Text("No accounts connected")
                    .yoinRole(Tokens.type.bodyLarge)
                    .foregroundStyle(scheme.onSurfaceVariant)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 16)
            } else {
                VStack(spacing: 2) {
                    ForEach(Array(accounts.enumerated()), id: \.element.id) { i, account in
                        row(account, first: i == 0, last: i == accounts.count - 1)
                    }
                }
            }
        }
    }

    private func row(_ account: LandingAccount, first: Bool, last: Bool) -> some View {
        let tone = LandingTone(account.service, dark: colorScheme == .dark)
        let top: CGFloat = first ? 20 : 4, bottom: CGFloat = last ? 20 : 4
        return HStack(spacing: 14) {
            Text(String(account.name.first(where: { $0.isLetter || $0.isNumber }) ?? "Y").uppercased())
                .font(YoinFont.rounded(17, semibold: true))
                .foregroundStyle(tone.onContainer)
                .frame(width: 40, height: 40)
                .background(tone.container, in: CookieShape(lobes: 9, depth: 0.16))
            VStack(alignment: .leading, spacing: 0) {
                Text(account.name)
                    .yoinRole(Tokens.type.titleMedium)
                    .foregroundStyle(scheme.onSurface)
                    .lineLimit(1)
                Text(account.detail)
                    .yoinRole(Tokens.type.bodyMedium)
                    .foregroundStyle(scheme.onSurfaceVariant)
            }
            Spacer(minLength: 8)
            if account.inUse {
                Text("In use")
                    .yoinRole(Tokens.type.labelMedium)
                    .foregroundStyle(scheme.onPrimary)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 3)
                    .background(scheme.primary, in: .rect(cornerRadius: 12))
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(scheme.surfaceBright,
                    in: UnevenRoundedRectangle(topLeadingRadius: top, bottomLeadingRadius: bottom,
                                               bottomTrailingRadius: bottom, topTrailingRadius: top))
    }
}

#Preview("Pick") {
    LandingPickScene(picked: [.subsonic], scheme: .brand(dark: false)) { _ in }
        .background(CoverScheme.brand(dark: false).surfaceContainer)
}
