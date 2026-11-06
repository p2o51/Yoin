import SwiftUI

/// What the service setup page opens on: a new account, or one account's page.
struct ServiceSetupRequest: Hashable, Identifiable {
    var id = UUID()
    /// nil: Add account (pick a service, connect it).
    var profileId: String?
    /// Adding: the service to open on (a cloud-only account's Add); nil starts on Subsonic.
    var service: Profile.Service?
}

/// Settings › Add account / an account's page, a pushed page (PushPage;
/// Android ui/settings/service ServiceSetupActivity): back (button or edge
/// swipe) → Settings. Adding: pick Subsonic or Apple Music, see what it does
/// (the only place that says so, Android ServiceIntro), and connect it. An
/// account's page: its connection, its sign-in (Subsonic), Remove.
///
/// Pixel style as ServerSettingsView: page surfaceContainer, primary group
/// labels, rows as surfaceBright segments (20 outer / 4 inner corners).
struct ServiceSetupView: View {
    var request: ServiceSetupRequest
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dismiss) private var dismiss
    @State private var service: Profile.Service = .subsonic
    @State private var address = ""
    @State private var username = ""
    @State private var password = ""
    @State private var working = false
    @State private var error: String?
    /// What the last Test connection found: nil error = the server answered.
    @State private var testResult: TestResult?
    @State private var confirmRemove = false
    /// Spotify's Client ID (Settings › Spotify on Android), kept as typed until it is saved.
    @State private var clientId = SpotifyConfig.clientId ?? ""
    @FocusState private var focus: Field?

    private enum TestResult: Equatable { case testing, reachable, failed(String) }

    private enum Field: Hashable { case address, username, password, clientId }

    private var profiles: ProfileManager { model.profiles }
    private var apple: AppleMusicSource { model.appleMusic }
    /// The account this page is for (nil while adding).
    private var editing: Profile? { request.profileId.flatMap(profiles.profile) }
    private var adding: Bool { request.profileId == nil }
    private var isActive: Bool { editing != nil && editing?.id == profiles.activeId }
    private var shown: Profile.Service { editing?.service ?? service }
    private var appleAdded: Bool { profiles.profile(ProfileStore.appleMusicId) != nil }

    private var app: CoverScheme {
        model.isIdle ? .brand(dark: colorScheme == .dark)
                     : CoverScheme(album: model.playback.album, dark: colorScheme == .dark)
    }
    private var row: Color { app.surfaceBright }

    private var canSubmit: Bool {
        !working && !address.trimmingCharacters(in: .whitespaces).isEmpty && !username.isEmpty && !password.isEmpty
    }

    /// The fields differ from the saved sign-in (always, while adding).
    private var edited: Bool {
        guard let p = editing, let a = profiles.signIn(p.id) else { return true }
        return ServerSession.normalized(address) != a.baseUrl || username != a.username || password != a.password
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                Text(editing?.title ?? String(localized: "Add account"))
                    .yoinRole(Tokens.type.displaySmall)
                    .foregroundStyle(app.onSurface)
                    .padding(.horizontal, 8)
                    .padding(.top, 8)
                if adding {
                    servicePicker
                    intro
                }
                switch shown {
                case .subsonic: subsonicGroups
                case .appleMusic: appleGroups
                case .spotify: spotifyGroups
                }
                if let error {
                    Text(error)
                        .yoinRole(Tokens.type.bodyMedium)
                        .foregroundStyle(app.error)
                        .padding(.horizontal, 8)
                        .padding(.top, -16)
                        .transition(.opacity)
                }
                if shown == .subsonic, edited {
                    submitButton.transition(.opacity.combined(with: .scale(scale: 0.96)))
                }
                if shown == .appleMusic, adding { appleButton }
                if shown == .spotify, ServiceSetupRouting.showsSpotifySignIn(adding: adding, hasAccount: editing != nil) { spotifyButton }
                if editing != nil { removeGroup }
                // What belongs to this service only, at the bottom of its page (51's rule).
                if shown == .subsonic, editing != nil {
                    SubsonicAccountSettings(app: app, row: row, showsDownloads: isActive)
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 40 + ShellBarMetrics.clearance)
            .animation(Yoin.spatial, value: service)
            .animation(Yoin.spatial, value: edited)
            .animation(Yoin.spatial, value: error)
            .animation(Yoin.effects, value: model.session.state)
            .animation(Yoin.effects, value: apple.authorization)
            .animation(Yoin.effects, value: apple.subscription)
        }
        .scrollDismissesKeyboard(.interactively)
        .safeAreaBar(edge: .top) { topBar }
        .background(app.surfaceContainer.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .onAppear {
            if adding, let s = request.service { service = s }
            fill()
            apple.refreshAuthorization()
        }
        .onChange(of: address) { error = nil; testResult = nil }
        .onChange(of: username) { error = nil; testResult = nil }
        .onChange(of: password) { error = nil; testResult = nil }
        .alert("Remove this account?", isPresented: $confirmRemove) {
            Button("Cancel", role: .cancel) {}
            Button("Remove", role: .destructive) {
                guard let p = editing else { return }
                dismiss()
                profiles.remove(p.id)
            }
        } message: {
            Text("“\(editing?.title ?? "")” will be removed from Yoin. Your ratings, notes and history stay.")
        }
    }

    // MARK: Adding: the service and what it does

    private var servicePicker: some View {
        group(String(localized: "Service")) {
            Profile.Service.allCases.map { AnyView(choiceRow($0)) }
        }
    }

    private func summary(_ s: Profile.Service) -> String {
        switch s {
        case .subsonic: String(localized: "Navidrome, Airsonic and other servers")
        case .appleMusic: appleAdded ? "Added" : "Your Apple Music library and catalog"
        case .spotify: String(localized: "Your Spotify library, played in the Spotify app")
        }
    }

    private func choiceRow(_ s: Profile.Service) -> some View {
        let selected = service == s
        let tone = s.tone(dark: colorScheme == .dark)
        return Button {
            focus = nil
            error = nil
            service = s
        } label: {
            HStack(spacing: 16) {
                YoinIcon(s.glyph, size: 22)
                    .foregroundStyle(tone.onAccent)
                    .frame(width: 40, height: 40)
                    .background(tone.accent, in: AvatarShape(index: 0))
                VStack(alignment: .leading, spacing: 2) {
                    Text(s.name)
                        .yoinRole(Tokens.type.titleMedium)
                        .foregroundStyle(app.onSurface)
                    Text(summary(s))
                        .yoinRole(Tokens.type.bodyMedium)
                        .foregroundStyle(app.onSurfaceVariant)
                }
                Spacer(minLength: 16)
                ZStack {
                    Circle().strokeBorder(selected ? app.primary : app.onSurfaceVariant, lineWidth: 2)
                    Circle().fill(app.primary).padding(5).scaleEffect(selected ? 1 : 0.01).opacity(selected ? 1 : 0)
                }
                .frame(width: 20, height: 20)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
    }

    private struct Highlight {
        var icon: String
        var title: String
        var body: String
    }

    /// Android ServiceIntro: a few highlights and what it takes. Not a support matrix.
    private func highlights(_ s: Profile.Service) -> [Highlight] {
        switch s {
        case .subsonic:
            [Highlight(icon: "equalizer", title: String(localized: "Playback"), body: "Stream directly from your server"),
             Highlight(icon: "favorite", title: String(localized: "Favorites and ratings"), body: "Synced with your server"),
             Highlight(icon: "playlist", title: String(localized: "Playlists"), body: "Browse and edit your playlists"),
             Highlight(icon: "download", title: String(localized: "Downloads"), body: "Keep albums and playlists on this device")]
        case .appleMusic:
            [Highlight(icon: "search", title: String(localized: "Library and catalog"),
                       body: "Browse your library and search the Apple Music catalog"),
             Highlight(icon: "play_filled", title: String(localized: "Playback"), body: "Play catalog songs directly in Yoin")]
        case .spotify:
            [Highlight(icon: "search", title: String(localized: "Library and search"),
                       body: "Your saved albums, playlists, Liked Songs and Spotify's catalog"),
             Highlight(icon: "play_filled", title: String(localized: "Playback"), body: "Plays through the Spotify app, controlled from Yoin"),
             Highlight(icon: "favorite", title: String(localized: "Liked Songs"), body: "The heart saves to your Spotify library")]
        }
    }

    private func needs(_ s: Profile.Service) -> String {
        switch s {
        case .subsonic: "Server address, username, password"
        case .appleMusic: "Apple Music subscription"
        case .spotify: "Spotify Premium, the Spotify app, and a Client ID from developer.spotify.com"
        }
    }

    private var intro: some View {
        VStack(alignment: .leading, spacing: 28) {
            group(String(localized: "What you get")) {
                highlights(service).map { h in
                    AnyView(HStack(spacing: 16) {
                        YoinIcon(h.icon, size: 20)
                            .foregroundStyle(app.onSecondaryContainer)
                            .frame(width: 40, height: 40)
                            .background(app.secondaryContainer, in: .circle)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(h.title)
                                .yoinRole(Tokens.type.bodyLarge)
                                .foregroundStyle(app.onSurface)
                            Text(h.body)
                                .yoinRole(Tokens.type.bodyMedium)
                                .foregroundStyle(app.onSurfaceVariant)
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12))
                }
            }
            group(String(localized: "You need")) { [AnyView(infoRow(needs(service), nil))] }
        }
        .id(service)
        .transition(.opacity)
    }

    // MARK: Subsonic

    @ViewBuilder private var subsonicGroups: some View {
        if isActive {
            group(String(localized: "Connection")) {
                var rows: [AnyView] = [AnyView(infoRow(String(localized: "Status"), statusText, ink: statusInk))]
                if case .ready(let server) = model.session.state {
                    let name = [server.type?.capitalized, server.version].compactMap { $0 }.joined(separator: " ")
                    if !name.isEmpty { rows.append(AnyView(infoRow(String(localized: "Server"), name))) }
                }
                return rows
            }
        }
        group(adding ? String(localized: "Sign in") : String(localized: "Account")) {
            [AnyView(field(String(localized: "Server address"), placeholder: "https://music.example.com", text: $address,
                           field: .address, keyboard: .URL)),
             AnyView(field(String(localized: "Username"), placeholder: String(localized: "Your username"), text: $username, field: .username)),
             AnyView(field(String(localized: "Password"), placeholder: String(localized: "Password"), text: $password, field: .password, secure: true)),
             AnyView(testRow)]
        }
    }

    private var filled: Bool {
        !address.trimmingCharacters(in: .whitespaces).isEmpty && !username.isEmpty && !password.isEmpty
    }

    /// Android's Test: asks the server with what is typed and saves nothing.
    private var testRow: some View {
        let busy = testResult == .testing || working
        return Button(action: test) {
            VStack(alignment: .leading, spacing: 2) {
                Text(testResult == .testing ? String(localized: "Reaching your server…") : String(localized: "Test connection"))
                    .yoinRole(Tokens.type.bodyLarge)
                    .foregroundStyle(filled && !busy ? app.primary : app.onSurface.opacity(0.38))
                switch testResult {
                case .reachable:
                    Text("Connected").yoinRole(Tokens.type.bodyMedium).foregroundStyle(app.primary)
                case .failed(let message):
                    Text(message).yoinRole(Tokens.type.bodyMedium).foregroundStyle(app.error).lineLimit(2)
                default: EmptyView()
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .disabled(!filled || busy)
    }

    private func test() {
        focus = nil
        testResult = .testing
        Task {
            let failure = await profiles.testSubsonic(baseUrl: address, username: username, password: password)
            testResult = failure.map(TestResult.failed) ?? .reachable
        }
    }

    private var statusText: String {
        switch model.session.state {
        case .signedOut: String(localized: "Signed out")
        case .connecting: String(localized: "Connecting")
        case .ready: String(localized: "Connected")
        case .failed(let m): m
        }
    }

    private var statusInk: Color {
        if case .failed = model.session.state { return app.error }
        if case .ready = model.session.state { return app.primary }
        return app.onSurfaceVariant
    }

    private var submitButton: some View {
        Button(action: submit) {
            Text(working ? String(localized: "Reaching your server…") : adding ? String(localized: "Connect") : String(localized: "Save"))
                .yoinRole(Tokens.type.titleMedium)
                // MD3 disabled filled button: onSurface 38% on onSurface 12%, legible in dark too.
                .foregroundStyle(canSubmit ? app.onPrimary : app.onSurface.opacity(0.38))
                .contentTransition(.opacity)
                .frame(maxWidth: .infinity, minHeight: 56)
                .background(canSubmit ? app.primary : app.onSurface.opacity(0.12), in: .capsule)
                .contentShape(.capsule)
        }
        .buttonStyle(PressScale())
        .disabled(!canSubmit)
        .animation(Yoin.effects, value: canSubmit)
        .animation(Yoin.effects, value: working)
    }

    private func submit() {
        focus = nil
        working = true
        error = nil
        Task {
            // A new account becomes the account in use (Android finishWithNewProfile).
            let failure = await profiles.connectSubsonic(baseUrl: address, username: username, password: password,
                                                         editing: editing?.id, activate: adding)
            working = false
            if let failure {
                error = failure
            } else if adding {
                dismiss()
            } else {
                fill()
            }
        }
    }

    // MARK: Apple Music

    private var appleGroups: some View {
        group("Apple Music") {
            var rows: [AnyView] = [AnyView(infoRow(String(localized: "Status"), appleStatusText, ink: appleStatusInk))]
            if apple.isAuthorized, let sub = apple.subscription {
                rows.append(AnyView(infoRow(String(localized: "Subscription"), ServerSettingsView.subscriptionText(sub),
                                            ink: sub.canPlayCatalogContent ? app.primary : app.onSurfaceVariant)))
            }
            if !adding, apple.authorization == .denied || apple.authorization == .restricted {
                rows.append(AnyView(actionRow(String(localized: "Open Settings"), ink: app.primary, action: openSystemSettings)))
            }
            return rows
        }
    }

    private var appleButton: some View {
        let blocked = apple.authorization == .denied || apple.authorization == .restricted
        let label = appleAdded ? String(localized: "Added") : blocked ? String(localized: "Open Settings")
            : apple.isAuthorized ? String(localized: "Add Apple Music") : working ? String(localized: "Working…") : String(localized: "Connect Apple Music")
        let enabled = !appleAdded && !working
        return Button(action: connectApple) {
            Text(label)
                .yoinRole(Tokens.type.titleMedium)
                .foregroundStyle(enabled ? app.onPrimary : app.onSurface.opacity(0.38))
                .contentTransition(.opacity)
                .frame(maxWidth: .infinity, minHeight: 56)
                .background(enabled ? app.primary : app.onSurface.opacity(0.12), in: .capsule)
                .contentShape(.capsule)
        }
        .buttonStyle(PressScale())
        .disabled(!enabled)
        .animation(Yoin.effects, value: label)
    }

    private func connectApple() {
        if apple.authorization == .denied || apple.authorization == .restricted { return openSystemSettings() }
        working = true
        error = nil
        Task {
            if !apple.isAuthorized { await apple.requestAuthorization() }
            working = false
            guard apple.isAuthorized else {
                error = "Apple Music access is off for Yoin"
                return
            }
            if profiles.addAppleMusic(activate: true) {
                dismiss()
            } else {
                error = "Up to \(ProfileStore.maxProfiles) accounts"
            }
        }
    }

    private var appleStatusText: String {
        switch apple.authorization {
        case .authorized: "Authorized"
        case .denied: "Denied"
        case .restricted: "Restricted"
        case .notDetermined: "Not connected"
        }
    }

    private var appleStatusInk: Color {
        switch apple.authorization {
        case .authorized: app.primary
        case .denied, .restricted: app.error
        case .notDetermined: app.onSurfaceVariant
        }
    }

    private func openSystemSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
    }

    // MARK: Spotify

    private var needsSpotifySignIn: Bool { editing.map(profiles.needsSignIn) ?? false }

    private var spotifyGroups: some View {
        VStack(alignment: .leading, spacing: 28) {
            if let p = editing {
                group(String(localized: "Spotify")) {
                    let tokens = profiles.store.spotify(p.id)
                    return [AnyView(infoRow(String(localized: "Account"), tokens?.displayName ?? tokens?.userId ?? String(localized: "Not signed in"))),
                            AnyView(infoRow(String(localized: "Status"), needsSpotifySignIn ? String(localized: "Sign in again") : String(localized: "Connected"),
                                            ink: needsSpotifySignIn ? app.error : app.primary))]
                }
            }
            group(String(localized: "Client ID")) {
                [AnyView(field(String(localized: "Client ID"), placeholder: String(localized: "From your app on developer.spotify.com"), text: $clientId,
                               field: .clientId)),
                 AnyView(infoRow(String(localized: "Redirect URIs to add to that app"),
                                 "\(SpotifyConfig.redirectURI)\n\(SpotifyConfig.appRemoteRedirectURI)"))]
            }
        }
        .onChange(of: clientId) { _, v in
            error = nil
            SpotifyConfig.setClientId(v)
        }
    }

    private var spotifyButton: some View {
        let ready = !clientId.trimmingCharacters(in: .whitespaces).isEmpty && !working
        return Button(action: connectSpotify) {
            Text(working ? String(localized: "Waiting for Spotify…") : adding ? String(localized: "Connect Spotify") : String(localized: "Sign in again"))
                .yoinRole(Tokens.type.titleMedium)
                .foregroundStyle(ready ? app.onPrimary : app.onSurface.opacity(0.38))
                .contentTransition(.opacity)
                .frame(maxWidth: .infinity, minHeight: 56)
                .background(ready ? app.primary : app.onSurface.opacity(0.12), in: .capsule)
                .contentShape(.capsule)
        }
        .buttonStyle(PressScale())
        .disabled(!ready)
        .animation(Yoin.effects, value: ready)
    }

    private func connectSpotify() {
        focus = nil
        working = true
        error = nil
        Task {
            let failure = await profiles.connectSpotify(activate: adding)
            working = false
            if let failure { error = failure } else if adding, profiles.profiles.contains(where: { $0.service == .spotify }) { dismiss() }
        }
    }

    // MARK: Remove

    private var removeGroup: some View {
        group(String(localized: "This device")) {
            [AnyView(actionRow(String(localized: "Remove account"), ink: app.error) { confirmRemove = true })]
        }
    }

    // MARK: Pieces (ServerSettingsView's)

    private func fill() {
        guard let p = editing, let a = profiles.signIn(p.id) ?? (p.baseUrl.map {
            ServerSession.Account(baseUrl: $0, username: p.username ?? "", password: "")
        }) else { return }
        address = a.baseUrl
        username = a.username
        password = a.password
    }

    private var topBar: some View {
        HStack {
            Button { dismiss() } label: {
                YoinIcon("back", size: 22)
                    .foregroundStyle(app.onSurface)
                    .frame(width: 40, height: 40)
                    .contentShape(.circle)
            }
            .buttonStyle(.plain)
            .glassEffect(.regular.interactive(), in: .circle)
            .frame(width: 48, height: 48)
            .accessibilityLabel("Back")
            Spacer()
        }
        .padding(.leading, 8)
        .padding(.vertical, 4)
    }

    private func group(_ title: String, rows: () -> [AnyView]) -> some View {
        let items = rows()
        return VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .yoinRole(Tokens.type.labelLarge)
                .foregroundStyle(app.primary)
                .padding(.horizontal, 8)
                .accessibilityAddTraits(.isHeader)
            VStack(spacing: 2) {
                ForEach(items.indices, id: \.self) { i in
                    items[i]
                        .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
                        .background(row, in: CloudSyncView.segment(i, items.count))
                }
            }
        }
    }

    /// Android SettingsTextField: label above, placeholder while empty.
    private func field(_ label: String, placeholder: String, text: Binding<String>, field: Field,
                       keyboard: UIKeyboardType = .default, secure: Bool = false) -> some View {
        let prompt = Text(placeholder).foregroundStyle(app.onSurfaceVariant.opacity(0.6))
        return VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .yoinRole(Tokens.type.labelMedium)
                .foregroundStyle(focus == field ? app.primary : app.onSurfaceVariant)
                .animation(Yoin.effects, value: focus)
            Group {
                if secure {
                    SecureField("", text: text, prompt: prompt)
                } else {
                    TextField("", text: text, prompt: prompt)
                        .keyboardType(keyboard)
                }
            }
            .font(.gsf(Tokens.type.bodyLarge.size.cg))
            .foregroundStyle(app.onSurface)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .focused($focus, equals: field)
            .submitLabel(field == .password ? .go : .next)
            .onSubmit {
                switch field {
                case .address: focus = .username
                case .username: focus = .password
                case .password: if canSubmit { submit() }
            case .clientId: focus = nil
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .contentShape(.rect)
        .onTapGesture { focus = field }
    }

    private func infoRow(_ label: String, _ value: String?, ink: Color? = nil) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .yoinRole(Tokens.type.bodyLarge)
                .foregroundStyle(app.onSurface)
            if let value {
                Text(value)
                    .yoinRole(Tokens.type.bodyMedium)
                    .foregroundStyle(ink ?? app.onSurfaceVariant)
                    .lineLimit(2)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
    }

    private func actionRow(_ title: String, ink: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .yoinRole(Tokens.type.bodyLarge)
                .foregroundStyle(ink)
                .padding(.horizontal, 16)
                .padding(.vertical, 14)
                .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
                .contentShape(.rect)
        }
        .buttonStyle(.plain)
    }
}
