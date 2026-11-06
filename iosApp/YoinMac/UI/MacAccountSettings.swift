import SwiftUI
import YoinShared

/// Settings › Accounts: every account, a click opens its page (Android / iOS account management). What
/// concerns one service only lives at the bottom of that service's account page (51, 2026-11-04 / 11-06),
/// and only what the service supports: a Subsonic server's stream quality and downloads, Spotify's Client ID.
struct MacAccountsSettings: View {
    @Environment(MacModel.self) private var model
    /// The account whose page is open (nil: the list). Not a NavigationStack: in the Settings window its back
    /// button lands in the tab strip as an extra tab; the page carries its own "< Accounts".
    @State private var open: String?

    var body: some View {
        Group {
            if let id = open {
                VStack(alignment: .leading, spacing: 0) {
                    Button { withAnimation(Yoin.spatial) { open = nil } } label: {
                        HStack(spacing: 4) {
                            YoinIcon("chevron_left", size: 16)
                            Text("Accounts")
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.link)
                    .keyboardShortcut("[", modifiers: .command)
                    .padding(.horizontal, 20).padding(.top, 12)
                    MacAccountPage(profileId: id) { withAnimation(Yoin.spatial) { open = nil } }
                }
                .transition(.move(edge: .trailing).combined(with: .opacity))
            } else {
            Form {
                Section("Accounts") {
                    ForEach(model.session.profiles) { p in
                        Button { withAnimation(Yoin.spatial) { open = p.id } } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(p.title)
                                    Text(MacAccountPage.serviceName(p.service)).font(.caption).foregroundStyle(.secondary)
                                }
                                Spacer()
                                if p.id == model.session.activeId { Text("In Use").foregroundStyle(.secondary) }
                                YoinIcon("chevron_right", size: 14).foregroundStyle(.secondary)
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                    if model.session.profiles.isEmpty {
                        Text("Not signed in").foregroundStyle(.secondary)
                    }
                    Button("Add Account…") {
                        withAnimation(Yoin.spatial) { model.addingAccount = true }
                        NSApp.mainWindow?.makeKeyAndOrderFront(nil)
                    }
                }
            }
            .formStyle(.grouped)
            .transition(.opacity)
            }
        }
        #if DEBUG
        .onReceive(NotificationCenter.default.publisher(for: MacQATour.accountPage)) { n in
            open = n.object as? String
        }
        // `-settingsAccount active` (QA screenshots): the account in use's page.
        .task {
            if UserDefaults.standard.string(forKey: "settingsAccount") == "active", let id = model.session.activeId {
                open = id
            }
        }
        #endif
    }
}

/// One account: who it is, Switch / Remove, then the settings that belong to its service.
struct MacAccountPage: View {
    var profileId: String
    /// Back to the list (after Remove).
    var close: () -> Void = {}
    @Environment(MacModel.self) private var model
    @AppStorage(MacSettings.streamQualityKey) private var quality = StreamQuality.original.key
    @State private var reconnectError: String?

    static func serviceName(_ s: MacSession.Profile.Service) -> String {
        switch s {
        case .subsonic: "Subsonic"
        case .appleMusic: "Apple Music"
        case .spotify: "Spotify"
        }
    }

    var body: some View {
        if let p = model.session.profiles.first(where: { $0.id == profileId }) {
            let active = p.id == model.session.activeId
            Form {
                Section {
                    LabeledContent("Service", value: Self.serviceName(p.service))
                    if p.account != nil {
                        MacSubsonicEditor(profile: p).id(p.id)
                    } else if let name = p.name {
                        LabeledContent("Account", value: name)
                    }
                    if active {
                        LabeledContent("Status", value: status)
                    }
                    HStack {
                        if active {
                            Text("In Use").foregroundStyle(.secondary)
                        } else {
                            Button("Switch to This Account") { model.switchAccount(p.id) }
                        }
                        Spacer()
                        Button("Remove", role: .destructive) {
                            model.removeAccount(p.id)
                            close()
                        }
                    }
                }
                serviceSettings(p, active: active)
            }
            .formStyle(.grouped)
        } else {
            Text("This account was removed.").foregroundStyle(.secondary)
        }
    }

    /// Only what this service supports.
    @ViewBuilder
    private func serviceSettings(_ p: MacSession.Profile, active: Bool) -> some View {
        switch p.service {
        case .subsonic:
            Section("Streaming") {
                Picker("Quality", selection: $quality) {
                    ForEach(MacSettings.qualities, id: \.key) { Text($0.label).tag($0.key) }
                }
                .onChange(of: quality) { model.player.streamQualityDidChange() }
                Text("Takes effect from the next song. Lower qualities transcode to MP3 on the server.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            @Bindable var offline = OfflineDownloads.shared
            Section("Downloads") {
                Picker("Quality", selection: $offline.quality) {
                    ForEach(OfflineDownloads.Quality.allCases, id: \.self) { Text($0.label).tag($0) }
                }
                Toggle("Download on metered networks", isOn: $offline.overMobileData)
                if active {
                    LabeledContent("On this Mac",
                                   value: ByteCountFormatter.string(fromByteCount: offline.bytes, countStyle: .file))
                    Button("Remove All Downloads", role: .destructive) { offline.removeAll() }
                        .disabled(offline.entries.isEmpty)
                } else {
                    Text("Switch to this account to see or remove its downloads.").font(.caption).foregroundStyle(.secondary)
                }
            }
        case .spotify:
            // The sign-in expired or was revoked: Reconnect signs in again and keeps this account (same
            // Spotify user), its settings and its local data.
            if SpotifyTokenStore.load(p.id) == nil || (active && model.session.state.isFailed) {
                Section {
                    Text("Spotify needs you to sign in again.").foregroundStyle(.secondary)
                    HStack {
                        Button("Reconnect Spotify") {
                            reconnectError = nil
                            let token = model.authToken
                            Task {
                                do {
                                    try await model.session.reconnectSpotify(profileId: p.id) { token == model.authToken }
                                } catch is MacSession.Superseded {
                                } catch {
                                    reconnectError = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
                                }
                            }
                        }
                        if let reconnectError { Text(reconnectError).font(.caption).foregroundStyle(.red) }
                    }
                }
            }
            Section("Spotify") {
                TextField("Client ID", text: Binding(
                    get: { SpotifyConfig.clientId ?? "" },
                    set: { SpotifyConfig.setClientId($0) }))
                Text("Your app's Client ID from developer.spotify.com, with the redirect URI yoin://auth/spotify/callback. Spotify plays through the Spotify app on this Mac or the device it's playing on.")
                    .font(.caption).foregroundStyle(.secondary)
            }
        case .appleMusic:
            Section("Apple Music") {
                Text("Apple Music plays in its own quality, and its songs can't be downloaded into Yoin.")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    private var status: String {
        switch model.session.state {
        case .signedOut: "Signed out"
        case .connecting: "Connecting"
        case .ready(let s): s.isEmpty ? "Connected" : "Connected to \(s)"
        case .failed(let m): m
        }
    }
}

/// A Subsonic account's sign-in, changed in place (Android ServiceSetupScreen.kt:505): server, username and a
/// new password (left empty, the saved one stays); Test asks the server, Save keeps them once it answers.
private struct MacSubsonicEditor: View {
    var profile: MacSession.Profile
    @Environment(MacModel.self) private var model
    @State private var url = ""
    @State private var username = ""
    @State private var password = ""
    @State private var busy = false
    @State private var result: (ok: Bool, text: String)?

    private var changed: Bool {
        guard let a = profile.account else { return false }
        return MacSession.normalized(url) != a.baseUrl
            || username.trimmingCharacters(in: .whitespaces) != a.username || !password.isEmpty
    }
    private var filled: Bool {
        !url.trimmingCharacters(in: .whitespaces).isEmpty && !username.trimmingCharacters(in: .whitespaces).isEmpty
    }

    var body: some View {
        Group {
            TextField("Server", text: $url).textContentType(.URL)
            TextField("Username", text: $username).textContentType(.username)
            SecureField("Password", text: $password, prompt: Text("Unchanged"))
            HStack {
                if busy { ProgressView().controlSize(.small) }
                if let result {
                    Text(result.text).font(.caption).foregroundStyle(result.ok ? Color.secondary : Color.red)
                        .lineLimit(2)
                }
                Spacer()
                Button("Test") { test() }.disabled(busy || !filled)
                Button("Save") { save() }.disabled(busy || !filled || !changed)
            }
        }
        .disabled(busy)
        .onAppear {
            url = profile.account?.baseUrl ?? ""
            username = profile.account?.username ?? ""
        }
    }

    private func test() {
        let pw = password.isEmpty ? (profile.account?.password ?? "") : password
        busy = true
        result = nil
        Task {
            defer { busy = false }
            do {
                let name = try await MacSession.test(url: url, username: username, password: pw)
                result = (true, "Connected to \(name)")
            } catch {
                result = (false, MacSession.message(for: error))
            }
        }
    }

    private func save() {
        busy = true
        result = nil
        Task {
            defer { busy = false }
            do {
                try await model.updateSubsonic(profile.id, url: url, username: username, password: password)
                password = ""
                result = (true, "Saved")
            } catch {
                result = (false, MacSession.message(for: error))
            }
        }
    }
}
