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
    @State private var askClientId = false
    @State private var clientId = ""

    private var current: LandingService? { model.connectQueue.first }

    /// Signed in: an added account starts the app over on it; then the next picked service, or the app.
    private func connected(adding: Bool) {
        if adding { model.signedOut() }
        error = nil
        askClientId = false
        withAnimation(Yoin.spatial) {
            if model.connectQueue.isEmpty { model.addingAccount = false } else { model.connectNext() }
        }
    }

    var body: some View {
        ZStack {
            Yoin.surface
            EllipticalGradient(colors: [Yoin.violet.opacity(0.16), .clear], center: .top, endRadiusFraction: 0.7)
            VStack(alignment: .leading, spacing: 0) {
                header
                if current == nil || current == .subsonic { subsonicForm }
                if current == .appleMusic || current == .spotify { serviceButton.padding(.top, 28) }
                if current != .subsonic, let error {
                    Text(error).font(.gsf(13)).foregroundStyle(.red).padding(.top, 12)
                }
                if current == nil { otherSources }
                // Spotify's Client ID is the account's own setting (its page in Settings); a first Spotify
                // account asks for it here.
                if askClientId {
                    field("Spotify Client ID", text: $clientId, prompt: "From developer.spotify.com")
                        .onSubmit(useSpotify)
                        .transition(.opacity)
                }
                footer
            }
            .frame(width: 360)
            .padding(32)
            .background(RoundedRectangle(cornerRadius: Tokens.shape.PANEL.cg, style: .continuous).fill(Yoin.surfaceContainer))
        }
        .ignoresSafeArea()
        .task { await MacQA.autoSignIn(model) }
    }

    @ViewBuilder private var header: some View {
        if let current {
            // The welcome guide's picks, one at a time (Android LandingScreen.kt:545).
            Text("Step \(model.connectTotal - model.connectQueue.count + 1) of \(model.connectTotal)")
                .font(.gsf(13, .medium)).foregroundStyle(Yoin.inkVariant)
            Text(current.name).font(.gsf(40, .semibold)).foregroundStyle(Yoin.ink)
        } else {
            Text("Yoin").font(.gsf(40, .semibold)).foregroundStyle(Yoin.ink)
        }
    }

    @ViewBuilder private var subsonicForm: some View {
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
    }

    private func primaryButton(_ action: @escaping () -> Void) -> some View {
        Button(action: action) {
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
    }

    private var serviceButton: some View {
        primaryButton(current == .appleMusic ? useAppleMusic : useSpotify).disabled(busy)
    }

    @ViewBuilder private var otherSources: some View {
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
    }

    @ViewBuilder private var footer: some View {
        if current != nil {
            Button("Skip") {
                model.supersedeSignIn()
                busy = false
                error = nil
                askClientId = false
                withAnimation(Yoin.spatial) { model.connectNext() }
            }
                .font(.gsf(14)).buttonStyle(.plain).foregroundStyle(Yoin.inkVariant)
                .frame(maxWidth: .infinity).padding(.top, 4)
                .keyboardShortcut(.cancelAction)
        } else if model.addingAccount {
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
                connected(adding: adding)
            } catch is MacSession.Superseded {
            } catch {
                self.error = AppleMusicSource.message(for: error)
            }
        }
    }

    private func useSpotify() {
        guard !busy else { return }
        // The field's Client ID applies (a retry after a wrong one included); none set and none typed: ask.
        let id = clientId.trimmingCharacters(in: .whitespacesAndNewlines)
        if askClientId, !id.isEmpty {
            SpotifyConfig.setClientId(id)
        } else if SpotifyConfig.clientId == nil {
            withAnimation(Yoin.spatial) { askClientId = true }
            return
        }
        busy = true
        error = nil
        Task {
            defer { busy = false }
            do {
                let adding = model.addingAccount
                let token = model.authToken
                try await model.session.useSpotify { token == model.authToken }
                connected(adding: adding)
            } catch is MacSession.Superseded {
            } catch {
                // A wrong Client ID fails here: it can be corrected and tried again.
                if clientId.isEmpty { clientId = SpotifyConfig.clientId ?? "" }
                withAnimation(Yoin.spatial) { askClientId = true }
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
                connected(adding: adding)
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
    @AppStorage(MacMiniPlayer.floatsKey) private var miniFloats = true
    @AppStorage("lyricsShowTranslation") private var showTranslation = true
    @AppStorage(MacSettings.appearanceKey) private var appearance = "system"
    /// The tab shown (`-settingsTab <name>` opens on one, debug).
    @State private var tab = UserDefaults.standard.string(forKey: "settingsTab") ?? "accounts"

    var body: some View {
        TabView(selection: $tab) {
            MacAccountsSettings()
                .tabItem { Label("Accounts", image: "yoin.artist") }.tag("accounts")

            Form {
                MacPlaybackCacheSection()
                // Downloads are a Subsonic account's (its page manages them); here only what they take up.
                let offline = OfflineDownloads.shared
                Section("Downloads") {
                    LabeledContent("On this Mac",
                                   value: ByteCountFormatter.string(fromByteCount: offline.bytes, countStyle: .file))
                    Text("Download quality and removal are on the Subsonic account's page in Accounts.")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            .formStyle(.grouped)
            .tabItem { Label("Storage", image: "yoin.download") }.tag("storage")

            Form {
                Section("Lyrics") {
                    Toggle("Show translations under the lyrics", isOn: $showTranslation)
                    Text("The server's lyrics come first, then QQ Music, NetEase and LRCLIB. Search in Now Playing picks another source for a song.")
                        .font(.caption).foregroundStyle(.secondary)
                }
                MacGeminiSettings()
                Section("Mini Player") {
                    Toggle("Float on top of other windows", isOn: $miniFloats)
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
            .tabItem { Label("General", image: "yoin.settings") }.tag("general")

            MacSyncSettings()
                .tabItem { Label("Sync", image: "yoin.cloud") }.tag("sync")

            MacBackupsSettings()
                .tabItem { Label("Backups", image: "yoin.playlist") }.tag("backups")

            MacNeoDBSettings()
                .tabItem { Label("NeoDB", image: "yoin.star") }.tag("neodb")

            MacAboutSettings()
                .tabItem { Label("About", image: "yoin.info") }.tag("about")
        }
        .frame(width: 520, height: 440)
        #if DEBUG
        .onReceive(NotificationCenter.default.publisher(for: MacQATour.settingsTab)) { n in
            if let t = n.object as? String { tab = t }
        }
        // `-settingsTour YES` (QA screenshots): the account page first, then General.
        .task {
            guard UserDefaults.standard.bool(forKey: "settingsTour") else { return }
            try? await Task.sleep(for: .seconds(20))
            tab = "general"
        }
        #endif
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
            Picker("Answer and translate in", selection: Binding(
                get: { settings.targetLanguage },
                set: { settings.saveLanguage($0) }
            )) {
                ForEach(GeminiSettings.languages, id: \.self) { language in
                    Text(language).tag(language)
                }
            }
            .font(.gsf(13))
            .foregroundStyle(Yoin.ink)
            if failed { Text("The key couldn't be saved").foregroundStyle(.red) }
            Text("From aistudio.google.com. Gemini writes the About for a song (when and where it was made, who wrote it, a review) and translates lyrics a source doesn't.")
                .font(.caption).foregroundStyle(.secondary)
        }
    }
}

/// Settings › Sync: Cloud sync on the Mac (the shared Kotlin core, Drive appDataFolder, the same files as
/// Android and iOS). Turning on signs in with Google (drive.appdata only); the first cycle only pulls.
private struct MacSyncSettings: View {
    @Environment(MacModel.self) private var model

    var body: some View {
        let sync = CloudSync.shared
        let state = sync.state
        Form {
            Section("Cloud sync") {
                LabeledContent("Status", value: Self.status(state))
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
                // Google's sign-in expired or was revoked (Android / iOS: Reconnect Google).
                if state.phase is CloudSyncPhaseNeedsReauth {
                    Button("Reconnect Google") { Task { await sync.turnOn() } }.disabled(sync.working)
                }
                if state.phase is CloudSyncPhaseCloudCopyRemoved {
                    Text("Yoin's copy in your Drive was removed.").foregroundStyle(.secondary)
                    Button("Upload Again") { Task { await sync.uploadAgain() } }.disabled(sync.working)
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
            if state.phase.enabled, !state.cloudOnlyAccounts.isEmpty {
                // Android CloudSyncScreen: accounts your Drive has from another device; adding the same account
                // here links it (its ratings and notes come down).
                Section("In your Drive, not on this Mac") {
                    ForEach(state.cloudOnlyAccounts, id: \.syncProfileId) { a in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(a.displayName.isEmpty ? a.provider : a.displayName)
                                let detail = [a.hint, a.fromDeviceName.map { "from \($0)" }].compactMap { $0 }.joined(separator: "  ")
                                if !detail.isEmpty { Text(detail).font(.caption).foregroundStyle(.secondary) }
                            }
                            Spacer()
                            Button("Add on This Mac…") {
                                withAnimation(Yoin.spatial) { model.addingAccount = true }
                                NSApp.windows.first { $0.title == "Yoin" }?.makeKeyAndOrderFront(nil)
                            }
                        }
                    }
                }
            }
        }
        .formStyle(.grouped)
    }
}

extension MacSyncSettings {
    /// iOS CloudSyncView's status line.
    static func status(_ state: CloudSyncState) -> String {
        if state.readOnly { return "Reading your Drive" }
        switch state.phase {
        case is CloudSyncPhaseOff: return "Off"
        case is CloudSyncPhaseSyncing: return "Syncing…"
        case is CloudSyncPhaseOffline: return "Can't reach Google"
        case is CloudSyncPhaseNeedsReauth: return "Google needs you to sign in again"
        case is CloudSyncPhaseStorageFull: return "Your Drive is full"
        case is CloudSyncPhaseCloudCopyRemoved: return "Drive copy removed"
        case let e as CloudSyncPhaseError: return e.message
        default: return state.pendingChanges ? "Changes waiting to upload" : (state.phase.enabled ? "Up to date" : "Off")
        }
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
