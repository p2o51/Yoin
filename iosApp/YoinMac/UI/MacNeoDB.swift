import SwiftUI

/// Settings › NeoDB (iOS NeoDBSettingsSection): the instance, Sign in (OAuth in ASWebAuthenticationSession),
/// an access token as the escape hatch (hidden until asked for), and Sign out, which keeps the instance.
struct MacNeoDBSettings: View {
    @State private var account = NeoDBAccount.shared
    @State private var instance = NeoDBAccount.shared.instance
    @State private var token = ""
    @State private var manualToken = false
    @State private var working = false
    @State private var message: String?

    private var canSaveToken: Bool {
        let t = token.trimmingCharacters(in: .whitespacesAndNewlines)
        return !working && !t.isEmpty && t != account.token
    }

    var body: some View {
        Form {
            Section {
                if account.isSignedIn {
                    LabeledContent("Status", value: "Signed in")
                }
                TextField("Instance", text: $instance, prompt: Text(NeoDBAccount.defaultInstance))
                    .autocorrectionDisabled()
                    .onSubmit(signIn)
                HStack {
                    Button(account.isSignedIn ? "Sign In Again" : "Sign In", action: signIn).disabled(working)
                    if working { ProgressView().controlSize(.small) }
                    Spacer()
                    if account.isSignedIn {
                        Button("Sign Out", role: .destructive) {
                            account.signOut()
                            token = ""
                            message = nil
                        }
                    }
                }
            } footer: {
                Text("Album scores and words go to NeoDB as marks and reviews.")
            }
            Section {
                if manualToken {
                    SecureField("Access token", text: $token, prompt: Text("Paste token"))
                        .onSubmit { if canSaveToken { saveToken() } }
                    Button("Save Token", action: saveToken).disabled(!canSaveToken)
                } else {
                    Button("Use an Access Token Instead") { manualToken = true }
                }
            }
            if let message {
                Text(message).foregroundStyle(.red)
            }
        }
        .formStyle(.grouped)
        .onChange(of: account.instance) { _, now in instance = now }
    }

    /// iOS / Android `openNeoDbSignIn`: remember the instance, then NeoDB's own sign-in page.
    private func signIn() {
        guard !working else { return }
        message = nil
        guard let normalized = NeoDBAccount.normalizeInstance(instance) else {
            message = "Invalid NeoDB URL. Include a valid host."
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
                if !account.save(instance: i, token: t) { message = "Couldn't save the token" }
                manualToken = false
                token = ""
            case .failure(let m):
                message = m
            case .cancelled:
                break
            }
        }
    }

    private func saveToken() {
        message = nil
        guard NeoDBAccount.normalizeInstance(instance) != nil else {
            message = "Invalid NeoDB URL. Include a valid host."
            return
        }
        if account.save(instance: instance, token: token) {
            manualToken = false
            token = ""
        } else {
            message = "Couldn't save the token"
        }
    }
}

/// An album's score and words (iOS AlbumScrapbookView's rate sheet): 0–10 in tenths, the words under it
/// (a short comment up to 360 characters on NeoDB, a review beyond), and where NeoDB stands. Saved to
/// AlbumMemoryStore (synced); NeoDB hears of it when the sheet closes.
struct MacAlbumRateSheet: View {
    var album: Album
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openSettings) private var openSettings
    @Environment(\.macTint) private var tint
    @State private var score: Double = 0
    @State private var text = ""
    @State private var opened: (score: Double, text: String) = (0, "")
    private let neo = AlbumNeoDBSync.shared

    private var record: AlbumMemoryStore.AlbumRecord? {
        AlbumMemoryStore.shared.album(provider: album.id.provider, albumId: album.id.rawId)
    }

    private var normalizedText: String? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : t
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(album.title).font(.gsf(17, .semibold)).lineLimit(1)
            HStack(spacing: 12) {
                Slider(value: $score, in: 0...10, step: 0.1)
                Text(score > 0 ? String(format: "%.1f", score) : "–")
                    .font(.gsf(22, .semibold).monospacedDigit())
                    .frame(width: 52, alignment: .trailing)
            }
            VStack(alignment: .leading, spacing: 6) {
                TextEditor(text: $text)
                    .font(.gsf(14))
                    .scrollContentBackground(.hidden)
                    .padding(8)
                    .frame(height: 140)
                    .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(tint.container.opacity(0.5)))
                if !text.isEmpty {
                    let count = text.unicodeScalars.count
                    let short = isNeoDBShortComment(text)
                    HStack {
                        Text(short ? "Short comment" : "Review")
                        Spacer()
                        Text(short ? "\(count) / \(neoDBShortCommentMax)" : "\(count)").monospacedDigit()
                    }
                    .font(.gsf(12)).foregroundStyle(Yoin.inkVariant)
                }
            }
            let state = neo.state(album)
            if state.isShown {
                HStack(spacing: 8) {
                    Text("NeoDB").font(.gsf(13, .semibold))
                    Text(state.label).font(.gsf(13)).foregroundStyle(Yoin.inkVariant)
                    if state == .signedOut {
                        Button("Settings…") { dismiss(); openSettings() }.buttonStyle(.link)
                    }
                    if state == .failed {
                        Button("Retry") { Task { await neo.retry(album) } }.buttonStyle(.link)
                    }
                }
            }
            HStack {
                Spacer()
                Button("Cancel", role: .cancel) { dismiss() }
                Button("Save") { save(); dismiss() }.keyboardShortcut(.defaultAction)
            }
        }
        .padding(22)
        .frame(width: 440)
        .onAppear {
            score = record?.rating ?? 0
            text = record?.review ?? ""
            opened = (score, text)
            neo.sheetOpened(album)
        }
        .onChange(of: text) { _, _ in neo.edited(album, unsavedWords: normalizedText != record?.review) }
        .onChange(of: score) { _, _ in neo.edited(album, unsavedWords: true) }
    }

    /// iOS commit() + saveReview(): only what changed here is written, each with its own NeoDB flag.
    private func save() {
        let store = AlbumMemoryStore.shared
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let provider = album.id.provider, albumId = album.id.rawId
        var changed = false
        if abs(score - opened.score) > 0.001 {
            let was = record
            if store.putAlbum(.init(provider: provider, albumId: albumId, rating: min(10, max(0, score)),
                                    review: was?.review, reviewUpdatedAt: was?.reviewUpdatedAt, updatedAt: now)) {
                NeoDBPendingStore.shared.markRatingChanged(provider: provider, albumId: albumId)
                changed = true
            }
        }
        if text != opened.text {
            let was = record
            if normalizedText != was?.review, was != nil || normalizedText != nil,
               store.putAlbum(.init(provider: provider, albumId: albumId, rating: was?.rating ?? 0,
                                    review: normalizedText, reviewUpdatedAt: normalizedText == nil ? nil : now,
                                    updatedAt: now)) {
                NeoDBPendingStore.shared.markReviewChanged(provider: provider, albumId: albumId)
                changed = true
            }
        }
        if changed { CloudSync.shared.localChanged() }
        let album = album
        Task { await AlbumNeoDBSync.shared.sheetClosed(album) }
    }
}
