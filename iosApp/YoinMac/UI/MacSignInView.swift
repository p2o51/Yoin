import SwiftUI

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
            do { try await model.session.useAppleMusic() } catch {
                self.error = AppleMusicSource.message(for: error)
            }
        }
    }

    private func submit() {
        guard !busy, !url.isEmpty, !username.isEmpty else { return }
        busy = true
        error = nil
        Task {
            defer { busy = false }
            do { try await model.session.signIn(url: url, username: username, password: password) } catch {
                self.error = MacSession.message(for: error)
            }
        }
    }
}

/// ⌘, : the account and Sign Out.
struct MacSettingsView: View {
    @Environment(MacModel.self) private var model

    var body: some View {
        Form {
            Section("Account") {
                if let a = model.session.account {
                    LabeledContent("Server", value: a.baseUrl)
                    LabeledContent("Username", value: a.username)
                    LabeledContent("Status", value: status)
                    Button("Sign Out", role: .destructive) {
                        model.signedOut()
                        model.session.signOut()
                    }
                } else if model.session.isAppleMusic {
                    LabeledContent("Service", value: "Apple Music")
                    LabeledContent("Status", value: status)
                    Button("Sign Out", role: .destructive) {
                        model.signedOut()
                        model.session.signOut()
                    }
                } else {
                    Text("Not signed in").foregroundStyle(.secondary)
                }
            }
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
        }
        .formStyle(.grouped)
        .frame(width: 460)
        .padding(.vertical, 8)
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
