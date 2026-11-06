import SwiftUI

/// Settings › NeoDB: Android `SettingsScreen` `NeoDbItem`. The instance, Sign in (OAuth in
/// ASWebAuthenticationSession: NeoDBOAuth), an access token as the escape hatch (hidden until asked
/// for), and Sign out, which keeps the instance. The token goes to the Keychain (NeoDBAccount).
///
/// Drawn in ServerSettingsView's Pixel style: primary group label 8 in, each row its own surfaceBright
/// segment (20 outer / 4 inner corners).
struct NeoDBSettingsSection: View {
    var app: CoverScheme
    var row: Color

    @State private var account = NeoDBAccount.shared
    @State private var instance = NeoDBAccount.shared.instance
    @State private var token = ""
    @State private var manualToken = false
    @State private var working = false
    @State private var message: String?
    @FocusState private var focus: Field?

    private enum Field: Hashable { case instance, token }

    private var canSaveToken: Bool {
        let t = token.trimmingCharacters(in: .whitespacesAndNewlines)
        return !working && !t.isEmpty && t != account.token
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("NeoDB")
                .yoinRole(Tokens.type.labelLarge)
                .foregroundStyle(app.primary)
                .padding(.horizontal, 8)
                .accessibilityAddTraits(.isHeader)
            let items = rows
            VStack(spacing: 2) {
                ForEach(Array(items.enumerated()), id: \.element.id) { i, item in
                    item.view
                        .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
                        .background(row, in: CloudSyncView.segment(i, items.count))
                        .transition(.opacity.combined(with: .move(edge: .top)))
                }
            }
            if let message {
                Text(message)
                    .yoinRole(Tokens.type.bodyMedium)
                    .foregroundStyle(app.error)
                    .padding(.horizontal, 8)
                    .padding(.top, 4)
                    .transition(.opacity)
            }
        }
        .animation(Yoin.spatial, value: manualToken)
        .animation(Yoin.spatial, value: account.isSignedIn)
        .animation(Yoin.effects, value: message)
        .animation(Yoin.effects, value: working)
        .onChange(of: account.instance) { _, now in instance = now }
    }

    // MARK: Rows

    private struct Row { var id: String; var view: AnyView }

    private var rows: [Row] {
        var out: [Row] = []
        if account.isSignedIn {
            out.append(Row(id: "status", view: AnyView(info(String(localized: "Status"), String(localized: "Signed in"), ink: app.primary))))
        }
        out.append(Row(id: "instance", view: AnyView(field(.instance))))
        out.append(Row(id: "signin", view: AnyView(
            action(account.isSignedIn ? String(localized: "Sign in again") : String(localized: "Sign in"), ink: app.primary, disabled: working, signIn))))
        if manualToken {
            out.append(Row(id: "token", view: AnyView(field(.token))))
            out.append(Row(id: "savetoken", view: AnyView(
                action(String(localized: "Save token"), ink: app.primary, disabled: !canSaveToken, saveToken))))
        } else {
            out.append(Row(id: "usetoken", view: AnyView(
                action(String(localized: "Use an access token instead"), ink: app.onSurfaceVariant) {
                    manualToken = true
                    token = account.token
                    focus = .token
                })))
        }
        if account.isSignedIn {
            out.append(Row(id: "signout", view: AnyView(action(String(localized: "Sign out"), ink: app.error) {
                account.signOut()
                token = ""
                message = nil
            })))
        }
        return out
    }

    private func info(_ title: String, _ value: String, ink: Color) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).yoinRole(Tokens.type.bodyLarge).foregroundStyle(app.onSurface)
            Text(value).yoinRole(Tokens.type.bodyMedium).foregroundStyle(ink)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
    }

    /// Android SettingsTextField: label above, placeholder while empty.
    private func field(_ which: Field) -> some View {
        let label = which == .instance ? String(localized: "Instance") : String(localized: "Access token")
        let placeholder = which == .instance ? NeoDBAccount.defaultInstance : String(localized: "Paste token")
        let prompt = Text(placeholder).foregroundStyle(app.onSurfaceVariant.opacity(0.6))
        return VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .yoinRole(Tokens.type.labelMedium)
                .foregroundStyle(focus == which ? app.primary : app.onSurfaceVariant)
                .animation(Yoin.effects, value: focus)
            Group {
                if which == .instance {
                    TextField("", text: $instance, prompt: prompt)
                        .keyboardType(.URL)
                        .submitLabel(.go)
                        .onSubmit(signIn)
                } else {
                    SecureField("", text: $token, prompt: prompt)
                        .textContentType(.password)
                        .submitLabel(.done)
                        .onSubmit { if canSaveToken { saveToken() } }
                }
            }
            .font(.gsf(Tokens.type.bodyLarge.size.cg))
            .foregroundStyle(app.onSurface)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .focused($focus, equals: which)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .contentShape(.rect)
        .onTapGesture { focus = which }
    }

    private func action(_ title: String, ink: Color, disabled: Bool = false, _ run: @escaping () -> Void) -> some View {
        Button(action: run) {
            Text(title)
                .yoinRole(Tokens.type.bodyLarge)
                .foregroundStyle(disabled ? app.onSurface.opacity(0.38) : ink)
                .padding(.horizontal, 16)
                .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
                .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .disabled(disabled)
    }

    // MARK: Actions

    /// Android `openNeoDbSignIn`: remember the instance, then NeoDB's own sign-in page.
    private func signIn() {
        guard !working else { return }
        focus = nil
        message = nil
        guard let normalized = NeoDBAccount.normalizeInstance(instance) else {
            message = String(localized: "Invalid NeoDB URL. Include a valid host.")
            return
        }
        account.setInstance(normalized)
        instance = normalized
        working = true
        Task {
            let result = await NeoDBOAuth().signIn(instance: normalized)
            working = false
            switch result {
            case .success(let i, let t):
                if !account.save(instance: i, token: t) { message = String(localized: "Couldn't save the token") }
                manualToken = false
                token = ""
            case .failure(let m):
                message = m
            case .cancelled:
                break
            }
        }
    }

    /// Android `saveNeoDbConfig`.
    private func saveToken() {
        focus = nil
        message = nil
        guard NeoDBAccount.normalizeInstance(instance) != nil else {
            message = String(localized: "Invalid NeoDB URL. Include a valid host.")
            return
        }
        if account.save(instance: instance, token: token) {
            manualToken = false
            token = ""
        } else {
            message = String(localized: "Couldn't save the token")
        }
    }
}
