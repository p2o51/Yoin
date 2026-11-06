import SwiftUI
import YoinShared

/// First launch: a Subsonic / Navidrome server and its sign-in.
struct MacSignInView: View {
    @Environment(MacModel.self) private var model
    @State private var url = ""
    @State private var username = ""
    @State private var password = ""
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        ZStack {
            Yoin.surface
            EllipticalGradient(colors: [Yoin.violet.opacity(0.16), .clear], center: .top, endRadiusFraction: 0.7)
            VStack(alignment: .leading, spacing: 0) {
                Text("Yoin").font(.gsf(40, .semibold)).foregroundStyle(Yoin.ink)
                Text("Sign in to your Subsonic or Navidrome server.")
                    .font(.gsf(15)).foregroundStyle(Yoin.inkVariant).padding(.top, 4)
                VStack(spacing: 10) {
                    field("Server", text: $url, prompt: "music.example.com")
                    field("Username", text: $username, prompt: "")
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Password").font(.gsf(12, .medium)).foregroundStyle(Yoin.inkVariant)
                        SecureField("", text: $password).textFieldStyle(.plain).font(.gsf(15))
                            .padding(.horizontal, 14).frame(height: 40)
                            .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Yoin.surfaceContainerHigh))
                            .onSubmit(submit)
                    }
                }
                .padding(.top, 28)
                if let error {
                    Text(error).font(.gsf(13)).foregroundStyle(.red).padding(.top, 12)
                }
                Button(action: submit) {
                    HStack(spacing: 8) {
                        if busy { ProgressView().controlSize(.small) }
                        Text(busy ? "Connecting" : "Sign In").font(.gsf(15, .semibold))
                    }
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity).frame(height: 44)
                    .background(Capsule().fill(Yoin.violet))
                    .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .disabled(busy || url.isEmpty || username.isEmpty)
                .opacity(url.isEmpty || username.isEmpty ? 0.6 : 1)
                .padding(.top, 22)
                // The other source: the system asks for Apple Music access (the listener answers).
                Button(action: useAppleMusic) {
                    Text("Use Apple Music instead").font(.gsf(14, .medium)).foregroundStyle(Yoin.violet)
                        .frame(maxWidth: .infinity).frame(height: 36)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(busy)
                .padding(.top, 10)
                Button(action: useSpotify) {
                    Text("Use Spotify instead").font(.gsf(14, .medium)).foregroundStyle(Yoin.violet)
                        .frame(maxWidth: .infinity).frame(height: 36)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(busy)
                if model.addingAccount {
                    Button("Cancel") {
                        model.supersedeSignIn()
                        busy = false
                        withAnimation(Yoin.spatial) { model.addingAccount = false }
                    }
                        .font(.gsf(14)).buttonStyle(.plain).foregroundStyle(Yoin.inkVariant)
                        .frame(maxWidth: .infinity).padding(.top, 4)
                        .keyboardShortcut(.cancelAction)
                }
            }
            .frame(width: 360)
            .padding(32)
            .background(RoundedRectangle(cornerRadius: Tokens.shape.PANEL.cg, style: .continuous).fill(Yoin.surfaceContainer))
        }
        .ignoresSafeArea()
        .task { await MacQA.autoSignIn(model) }
    }

    private func field(_ label: String, text: Binding<String>, prompt: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).font(.gsf(12, .medium)).foregroundStyle(Yoin.inkVariant)
            TextField("", text: text, prompt: Text(prompt).foregroundStyle(Yoin.ink3)).textFieldStyle(.plain)
                .font(.gsf(15))
                .padding(.horizontal, 14).frame(height: 40)
                .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Yoin.surfaceContainerHigh))
                .onSubmit(submit)
        }
    }

    private func useAppleMusic() {
        guard !busy else { return }
        busy = true
        error = nil
        Task {
            defer { busy = false }
            do {
                let adding = model.addingAccount
                let token = model.authToken
                try await model.session.useAppleMusic { token == model.authToken }
                if adding { model.signedOut(); model.addingAccount = false }
            } catch is MacSession.Superseded {
            } catch {
                self.error = AppleMusicSource.message(for: error)
            }
        }
    }

    private func useSpotify() {
        guard !busy else { return }
        busy = true
        error = nil
        Task {
            defer { busy = false }
            do {
                let adding = model.addingAccount
                let token = model.authToken
                try await model.session.useSpotify { token == model.authToken }
                if adding { model.signedOut(); model.addingAccount = false }
            } catch is MacSession.Superseded {
            } catch {
                self.error = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            }
        }
    }

    private func submit() {
        guard !busy, !url.isEmpty, !username.isEmpty else { return }
        busy = true
        error = nil
        Task {
            defer { busy = false }
            do {
                let adding = model.addingAccount
                let token = model.authToken
                try await model.session.signIn(url: url, username: username, password: password) {
                    token == model.authToken
                }
                if adding { model.signedOut(); model.addingAccount = false }
            } catch is MacSession.Superseded {
            } catch {
                self.error = MacSession.message(for: error)
            }
        }
    }
}

/// ⌘, : the Mac's settings, one tab each (iOS Settings, the parts the Mac has).
struct MacSettingsView: View {
    @Environment(MacModel.self) private var model
    @AppStorage(MacSettings.streamQualityKey) private var quality = StreamQuality.original.key
    @AppStorage(MacMiniPlayer.floatsKey) private var miniFloats = true
    @AppStorage("lyricsShowTranslation") private var showTranslation = true
    @AppStorage(MacSettings.appearanceKey) private var appearance = "system"

    var body: some View {
        TabView {
            Form {
            Section("Accounts") {
                ForEach(model.session.profiles) { p in
                    let active = p.id == model.session.activeId
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(p.title)
                            if active { Text(status).font(.caption).foregroundStyle(.secondary) }
                        }
                        Spacer()
                        if active {
                            Text("In Use").foregroundStyle(.secondary)
                        } else {
                            Button("Switch") { model.switchAccount(p.id) }
                        }
                        Button("Remove", role: .destructive) {
                            if active { model.signedOut() }
                            model.session.remove(p.id)
                        }
                    }
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
            .tabItem { Label("Accounts", image: "yoin.artist") }

            Form {
                Section("Streaming") {
                    Picker("Quality", selection: $quality) {
                        ForEach(MacSettings.qualities, id: \.key) { Text($0.label).tag($0.key) }
                    }
                    .onChange(of: quality) { model.player.streamQualityDidChange() }
                    Text("Takes effect from the next song. Lower qualities transcode to MP3 on the server.")
                        .font(.caption).foregroundStyle(.secondary)
                }
                Section("Mini Player") {
                    Toggle("Float on top of other windows", isOn: $miniFloats)
                }
            }
            .formStyle(.grouped)
            .tabItem { Label("Playback", image: "yoin.play_filled") }

            Form {
            if model.session.client != nil {
                @Bindable var offline = OfflineDownloads.shared
                Section("Downloads") {
                    Picker("Quality", selection: $offline.quality) {
                        ForEach(OfflineDownloads.Quality.allCases, id: \.self) { Text($0.label).tag($0) }
                    }
                    Toggle("Download on metered networks", isOn: $offline.overMobileData)
                    LabeledContent("On this Mac",
                                   value: ByteCountFormatter.string(fromByteCount: offline.bytes, countStyle: .file))
                    Button("Remove All Downloads", role: .destructive) { offline.removeAll() }
                        .disabled(offline.entries.isEmpty)
                }
            }
                if model.session.client == nil {
                    Text("Downloads are for Subsonic and Navidrome accounts.").foregroundStyle(.secondary)
                }
            }
            .formStyle(.grouped)
            .tabItem { Label("Downloads", image: "yoin.download") }

            Form {
                Section("Lyrics") {
                    Toggle("Show translations under the lyrics", isOn: $showTranslation)
                    Text("The server's lyrics come first, then QQ Music, NetEase and LRCLIB. Search in Now Playing picks another source for a song.")
                        .font(.caption).foregroundStyle(.secondary)
                }
                MacGeminiSettings()
                Section("Spotify") {
                    TextField("Client ID", text: Binding(
                        get: { SpotifyConfig.clientId ?? "" },
                        set: { SpotifyConfig.setClientId($0) }))
                    Text("Your app's Client ID from developer.spotify.com, with the redirect URI yoin://auth/spotify/callback. Spotify plays through the Spotify app on this Mac.")
                        .font(.caption).foregroundStyle(.secondary)
                }
                Section("Appearance") {
                    Picker("Appearance", selection: $appearance) {
                        Text("System").tag("system")
                        Text("Light").tag("light")
                        Text("Dark").tag("dark")
                    }
                    .pickerStyle(.segmented)
                    .onChange(of: appearance) { MacSettings.applyAppearance() }
                }
            }
            .formStyle(.grouped)
            .tabItem { Label("General", image: "yoin.settings") }

            MacSyncSettings()
                .tabItem { Label("Sync", image: "yoin.cloud") }

            MacBackupsSettings()
                .tabItem { Label("Backups", image: "yoin.playlist") }

            MacNeoDBSettings()
                .tabItem { Label("NeoDB", image: "yoin.star") }
        }
        .frame(width: 520, height: 440)
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

/// Settings › General › Gemini: the listener's own API key (Keychain, this Mac), which turns on the song's
/// About from Gemini and Gemini lyric translations.
private struct MacGeminiSettings: View {
    @State private var draft = ""
    @State private var failed = false

    var body: some View {
        let settings = GeminiSettings.shared
        Section("Gemini") {
            if settings.hasKey {
                LabeledContent("API key", value: "Saved on this Mac")
            }
            SecureField(settings.hasKey ? "Replace the key" : "API key", text: $draft)
            Button("Save Key") {
                failed = !settings.saveKey(draft)
                if !failed {
                    draft = ""
                    SongAboutLoader.shared.keyChanged()
                }
            }
            .disabled(draft.trimmingCharacters(in: .whitespaces).isEmpty)
            if failed { Text("The key couldn't be saved").foregroundStyle(.red) }
            Text("From aistudio.google.com. Gemini writes the About for a song (when and where it was made, who wrote it, a review) and translates lyrics a source doesn't.")
                .font(.caption).foregroundStyle(.secondary)
        }
    }
}

/// Settings › Sync: Cloud sync on the Mac (the shared Kotlin core, Drive appDataFolder, the same files as
/// Android and iOS). Turning on signs in with Google (drive.appdata only); the first cycle only pulls.
private struct MacSyncSettings: View {
    var body: some View {
        let sync = CloudSync.shared
        let state = sync.state
        Form {
            Section("Cloud sync") {
                LabeledContent("Status", value: state.phase.enabled ? "On" : "Off")
                if let ms = state.lastSyncAt?.int64Value {
                    LabeledContent("Last synced",
                                   value: Date(timeIntervalSince1970: Double(ms) / 1000).formatted(date: .abbreviated, time: .shortened))
                }
                if !sync.isConfigured {
                    Text("Google sign-in isn't set up in this build.").foregroundStyle(.secondary)
                } else if state.phase.enabled {
                    HStack {
                        Button("Sync Now") { Task { await sync.syncNow() } }
                        Button("Turn Off") { Task { await sync.turnOff() } }
                    }
                    .disabled(sync.working)
                } else {
                    Button("Turn On") { Task { await sync.turnOn() } }
                        .disabled(sync.working)
                }
                if let review = state.phase as? CloudSyncPhaseNeedsReview {
                    // iOS CloudSyncView: rows missing here that the cloud still has — bring back, or delete everywhere.
                    Text("\(review.count) notes disappeared from \(review.accountName)")
                    HStack {
                        Button("Restore here") { Task { await sync.resolveReview(restore: true) } }
                        Button("Delete on all devices", role: .destructive) { Task { await sync.resolveReview(restore: false) } }
                    }
                    .disabled(sync.working)
                }
                if sync.working { ProgressView().controlSize(.small) }
                if let m = sync.message { Text(m).foregroundStyle(.red) }
                Text("Ratings, notes, album memories, lyric choices and playlist backups follow you between this Mac, your phone and your other devices. The first sync only reads; nothing is changed in the cloud until the next one.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            if state.phase.enabled, !state.accounts.isEmpty {
                // iOS CloudSyncView's account rows: Apple Music syncs only after an explicit choice.
                Section("Accounts") {
                    ForEach(state.accounts, id: \.localProfileId) { a in SyncAccountLine(account: a) }
                }
            }
        }
        .formStyle(.grouped)
    }
}

private struct SyncAccountLine: View {
    var account: SyncAccountRow
    @State private var confirmFresh = false

    var body: some View {
        let sync = CloudSync.shared
        let a = account
        VStack(alignment: .leading, spacing: 6) {
            LabeledContent(a.displayName, value: label)
            HStack(spacing: 12) {
                if let n = a.status as? SyncAccountStatusNotSynced {
                    Button("Start syncing") { Task { await sync.startSyncing(a.localProfileId) } }
                    ForEach(n.linkCandidates, id: \.syncProfileId) { c in
                        Button("Link to \(c.displayName)") { Task { await sync.link(a.localProfileId, to: c.syncProfileId) } }
                    }
                } else if a.status is SyncAccountStatusAccountChanged {
                    Button("Keep syncing as before") { Task { await sync.keepAsBefore(a.localProfileId) } }
                    Button("Start fresh") { confirmFresh = true }
                }
            }
            .disabled(sync.working)
        }
        .confirmationDialog("Start fresh for \(a.displayName)?", isPresented: $confirmFresh) {
            Button("Start fresh", role: .destructive) { Task { await sync.startFresh(a.localProfileId) } }
        }
    }

    private var label: String {
        switch account.status {
        case is SyncAccountStatusSyncing: "Syncing"
        case is SyncAccountStatusNeedsIdentity: "Not synced"
        case is SyncAccountStatusNeedsReconnect: "Reconnect to sync"
        case let d as SyncAccountStatusDuplicateOf: "Same as \(d.otherDisplayName)"
        case is SyncAccountStatusNotSynced: "Not synced"
        case is SyncAccountStatusAccountChanged: "Paused"
        default: ""
        }
    }
}
