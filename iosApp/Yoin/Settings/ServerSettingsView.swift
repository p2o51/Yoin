import SwiftUI
import YoinShared

/// Server sign-in, a pushed page (PushPage): Home's account avatar → here,
/// back (button or edge swipe) → Home.
///
/// Styled after Android's Settings visual system (ui/settings
/// SettingsComponents.kt, Pixel Settings): a plain page on surfaceContainer,
/// a large title, small primary group labels 8 inside the rows' edge, and
/// each row its own segment on surfaceBright (20 outer / 4 inner corners,
/// 2 apart). Labels only, no instructions.
struct ServerSettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme
    @State private var address = ""
    @State private var username = ""
    @State private var password = ""
    @State private var working = false
    @State private var quality = StreamQualityStore.load()
    /// The quality row whose choices are open under it, if any.
    @State private var qualityOpen: QualityNetwork?
    @FocusState private var focus: Field?

    private enum Field: Hashable { case address, username, password }

    /// Android Settings › Playback quality: one choice per network.
    private enum QualityNetwork: Hashable {
        case wifi, mobile
        var title: String {
            switch self {
            case .wifi: "Wi\u{2011}Fi"
            case .mobile: "Mobile data"
            }
        }
    }

    private var session: ServerSession { model.session }
    private var apple: AppleMusicSource { model.appleMusic }
    private var active: ActiveSource { model.sources.active }
    private var app: CoverScheme {
        model.isIdle ? .brand(dark: colorScheme == .dark)
                     : CoverScheme(album: model.playback.album, dark: colorScheme == .dark)
    }
    /// Pixel's rule: page surfaceContainer, rows surfaceBright.
    private var page: Color { app.surfaceContainer }
    private var row: Color { app.surfaceBright }

    private var canSubmit: Bool {
        !working && !address.trimmingCharacters(in: .whitespaces).isEmpty && !username.isEmpty && !password.isEmpty
    }

    /// The fields differ from the saved account (or nothing is saved).
    private var edited: Bool {
        guard let a = session.account else { return true }
        return ServerSession.normalized(address) != a.baseUrl || username != a.username || password != a.password
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                Text("Server")
                    .yoinRole(Tokens.type.displaySmall)
                    .foregroundStyle(app.onSurface)
                    .padding(.horizontal, 8)
                    .padding(.top, 8)
                if session.account != nil {
                    connectionGroup
                    qualityGroup
                }
                group("Account") {
                    [AnyView(field("Server address", placeholder: "https://music.example.com", text: $address, field: .address, keyboard: .URL)),
                     AnyView(field("Username", placeholder: "Your username", text: $username, field: .username)),
                     AnyView(field("Password", placeholder: "Password", text: $password, field: .password, secure: true))]
                }
                if case .failed(let message) = session.state, edited || !session.isSignedIn {
                    Text(message)
                        .yoinRole(Tokens.type.bodyMedium)
                        .foregroundStyle(app.error)
                        .padding(.horizontal, 8)
                        .padding(.top, -16)
                        .transition(.opacity)
                }
                if edited { signInButton.transition(.opacity.combined(with: .scale(scale: 0.96))) }
                appleMusicGroup
                GeminiSettingsSection(app: app, row: row)
                StorageSettingsSection(app: app, row: row)
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 40)
            .animation(Yoin.spatial, value: edited)
            .animation(Yoin.effects, value: session.state)
            .animation(Yoin.spatial, value: active)
            .animation(Yoin.spatial, value: qualityOpen)
            .animation(Yoin.effects, value: quality)
            .animation(Yoin.effects, value: apple.authorization)
            .animation(Yoin.effects, value: apple.subscription)
        }
        .scrollDismissesKeyboard(.interactively)
        .safeAreaBar(edge: .top) { topBar }
        .background(page.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .onAppear {
            fillFromAccount()
            apple.refreshAuthorization()
        }
    }

    // MARK: Pieces

    private var topBar: some View {
        HStack {
            Button { model.back() } label: {
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

    private var connectionGroup: some View {
        group("Connection") {
            var rows: [AnyView] = [AnyView(infoRow("Status", statusText, ink: statusInk))]
            if case .ready(let server) = session.state {
                let name = [server.type?.capitalized, server.version].compactMap { $0 }.joined(separator: " ")
                if !name.isEmpty { rows.append(AnyView(infoRow("Server", name))) }
            }
            if let a = session.account { rows.append(AnyView(infoRow("Signed in as", a.username))) }
            if session.isSignedIn, active != .subsonic {
                rows.append(AnyView(actionRow("Use Subsonic", ink: app.primary) { use(.subsonic) }))
            }
            rows.append(AnyView(signOutRow))
            return rows
        }
    }

    private var qualityGroup: some View {
        group("Playback") {
            var rows: [AnyView] = []
            for network in [QualityNetwork.wifi, .mobile] {
                rows.append(AnyView(qualityRow(network)))
                if qualityOpen == network {
                    rows += StreamQuality.companion.all.map { AnyView(qualityChoice($0, for: network)) }
                }
            }
            return rows
        }
    }

    private func selected(_ network: QualityNetwork) -> StreamQuality {
        network == .wifi ? quality.wifi : quality.mobile
    }

    /// Android Settings › Playback quality, as two rows (Wi‑Fi, Mobile data):
    /// each shows its choice; a tap opens Android's five choices right under
    /// it. Auto follows the output (wired Original, Bluetooth 320 kbps,
    /// speaker 192 kbps). The next song opened plays at the new choice.
    private func qualityRow(_ network: QualityNetwork) -> some View {
        let open = qualityOpen == network
        return Button { qualityOpen = open ? nil : network } label: {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(network.title)
                        .yoinRole(Tokens.type.bodyLarge)
                        .foregroundStyle(app.onSurface)
                    Text(selected(network).label)
                        .yoinRole(Tokens.type.bodyMedium)
                        .foregroundStyle(app.onSurfaceVariant)
                        .contentTransition(.opacity)
                }
                Spacer(minLength: 16)
                YoinIcon("chevron_down", size: 20)
                    .foregroundStyle(app.onSurfaceVariant)
                    .rotationEffect(.degrees(open ? 180 : 0))
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(network.title)
        .accessibilityValue(selected(network).label)
    }

    private func qualityChoice(_ choice: StreamQuality, for network: QualityNetwork) -> some View {
        let isSelected = choice == selected(network)
        return Button {
            guard !isSelected else { return }
            switch network {
            case .wifi: StreamQualityStore.setWifi(choice)
            case .mobile: StreamQualityStore.setMobile(choice)
            }
            quality = StreamQualityStore.load()
            model.player.streamQualityDidChange()
        } label: {
            HStack(spacing: 16) {
                ZStack {
                    Circle().strokeBorder(isSelected ? app.primary : app.onSurfaceVariant, lineWidth: 2)
                    Circle().fill(app.primary).padding(5).scaleEffect(isSelected ? 1 : 0.01).opacity(isSelected ? 1 : 0)
                }
                .frame(width: 20, height: 20)
                .padding(.horizontal, 2)
                VStack(alignment: .leading, spacing: 2) {
                    Text(choice.label)
                        .yoinRole(Tokens.type.titleMedium)
                        .foregroundStyle(app.onSurface)
                    if choice == .auto_ {
                        Text(AudioOutputKind.companion.autoDetail)
                            .yoinRole(Tokens.type.bodyMedium)
                            .foregroundStyle(app.onSurfaceVariant)
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .transition(.opacity.combined(with: .move(edge: .top)))
        .accessibilityAddTraits(isSelected ? [.isButton, .isSelected] : .isButton)
    }

    private var statusText: String {
        switch session.state {
        case .signedOut: "Signed out"
        case .connecting: "Connecting"
        case .ready: "Connected"
        case .failed(let m): m
        }
    }

    private var statusInk: Color {
        if case .failed = session.state { return app.error }
        if case .ready = session.state { return app.primary }
        return app.onSurfaceVariant
    }

    /// Group label (labelLarge primary, 8 in) over its rows as segments.
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
                        .background(row, in: segment(i, items.count))
                }
            }
        }
    }

    private func segment(_ i: Int, _ count: Int) -> UnevenRoundedRectangle {
        let top: CGFloat = i == 0 ? 20 : 4
        let bottom: CGFloat = i == count - 1 ? 20 : 4
        return UnevenRoundedRectangle(topLeadingRadius: top, bottomLeadingRadius: bottom,
                                      bottomTrailingRadius: bottom, topTrailingRadius: top, style: .circular)
    }

    /// Android SettingsTextField: label above, placeholder (settings_setup_*_placeholder) while empty.
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
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .contentShape(.rect)
        .onTapGesture { focus = field }
    }

    private func infoRow(_ label: String, _ value: String, ink: Color? = nil) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .yoinRole(Tokens.type.bodyLarge)
                .foregroundStyle(app.onSurface)
            Text(value)
                .yoinRole(Tokens.type.bodyMedium)
                .foregroundStyle(ink ?? app.onSurfaceVariant)
                .lineLimit(2)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
    }

    // MARK: Apple Music

    private var appleMusicGroup: some View {
        group("Apple Music") {
            var rows: [AnyView] = [AnyView(infoRow("Status", appleStatusText, ink: appleStatusInk))]
            if apple.isAuthorized, let sub = apple.subscription {
                rows.append(AnyView(infoRow("Subscription", Self.subscriptionText(sub),
                                            ink: sub.canPlayCatalogContent ? app.primary : app.onSurfaceVariant)))
            }
            if active == .appleMusic {
                rows.append(AnyView(infoRow("Source", "In use", ink: app.primary)))
                rows.append(AnyView(actionRow("Disconnect", ink: app.error) {
                    use(session.isSignedIn ? .subsonic : .mock)
                }))
            } else {
                switch apple.authorization {
                case .authorized:
                    rows.append(AnyView(actionRow("Use Apple Music", ink: app.primary) { use(.appleMusic) }))
                case .notDetermined:
                    rows.append(AnyView(actionRow("Connect", ink: app.primary) {
                        Task {
                            await apple.requestAuthorization()
                            if apple.isAuthorized { use(.appleMusic) }
                        }
                    }))
                case .denied, .restricted:
                    rows.append(AnyView(actionRow("Open Settings", ink: app.primary) {
                        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                    }))
                }
            }
            return rows
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

    static func subscriptionText(_ s: AppleMusicSource.Subscription) -> String {
        if s.canPlayCatalogContent { return "Active" }
        return s.canBecomeSubscriber ? "Not subscribed" : "Unavailable"
    }

    /// Another source: the queue belongs to the old one, so playback stops.
    private func use(_ source: ActiveSource) {
        withAnimation(Yoin.spatial) {
            if source != active { model.player.stop() }
            model.sources.use(source)
        }
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

    private var signOutRow: some View {
        Button {
            withAnimation(Yoin.spatial) {
                if active == .subsonic {
                    model.player.stop()
                    model.sources.use(.mock)
                }
                session.signOut()
                password = ""
            }
        } label: {
            Text("Sign out")
                .yoinRole(Tokens.type.bodyLarge)
                .foregroundStyle(app.error)
                .padding(.horizontal, 16)
                .padding(.vertical, 14)
                .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
                .contentShape(.rect)
        }
        .buttonStyle(.plain)
    }

    private var signInButton: some View {
        Button(action: submit) {
            Text(session.isSignedIn ? "Save" : "Sign in")
                .yoinRole(Tokens.type.titleMedium)
                // MD3 disabled filled button: onSurface 38% on onSurface 12%, legible in dark too.
                .foregroundStyle(canSubmit ? app.onPrimary : app.onSurface.opacity(0.38))
                .frame(maxWidth: .infinity, minHeight: 56)
                .background(canSubmit ? app.primary : app.onSurface.opacity(0.12), in: .capsule)
                .contentShape(.capsule)
        }
        .buttonStyle(PressScale())
        .disabled(!canSubmit)
        .animation(Yoin.effects, value: canSubmit)
    }

    // MARK: Actions

    private func fillFromAccount() {
        guard let a = session.account else { return }
        address = a.baseUrl
        username = a.username
        password = a.password
    }

    private func submit() {
        focus = nil
        working = true
        Task {
            let ok = await session.signIn(baseUrl: address, username: username, password: password)
            working = false
            if ok {
                fillFromAccount()
                use(.subsonic)
            }
        }
    }
}
