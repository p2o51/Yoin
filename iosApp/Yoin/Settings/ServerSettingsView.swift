import SwiftUI

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
    @FocusState private var focus: Field?

    private enum Field: Hashable { case address, username, password }

    private var session: ServerSession { model.session }
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
                if session.account != nil { connectionGroup }
                group("Account") {
                    [AnyView(field("Server address", text: $address, field: .address, keyboard: .URL)),
                     AnyView(field("Username", text: $username, field: .username)),
                     AnyView(field("Password", text: $password, field: .password, secure: true))]
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
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 40)
            .animation(Yoin.spatial, value: edited)
            .animation(Yoin.effects, value: session.state)
        }
        .scrollDismissesKeyboard(.interactively)
        .safeAreaBar(edge: .top) { topBar }
        .background(page.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .onAppear(perform: fillFromAccount)
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
            rows.append(AnyView(signOutRow))
            return rows
        }
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

    private func field(_ label: String, text: Binding<String>, field: Field, keyboard: UIKeyboardType = .default,
                       secure: Bool = false) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .yoinRole(Tokens.type.labelMedium)
                .foregroundStyle(focus == field ? app.primary : app.onSurfaceVariant)
                .animation(Yoin.effects, value: focus)
            Group {
                if secure {
                    SecureField("", text: text)
                } else {
                    TextField("", text: text)
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

    private var signOutRow: some View {
        Button {
            withAnimation(Yoin.spatial) {
                model.player.stop()
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
                .foregroundStyle(app.onPrimary)
                .frame(maxWidth: .infinity, minHeight: 56)
                .background(app.primary, in: .capsule)
                .opacity(canSubmit ? 1 : 0.38)
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
            if ok { fillFromAccount() }
        }
    }
}
