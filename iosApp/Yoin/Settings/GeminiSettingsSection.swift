import SwiftUI

/// Settings › Features › AI features: Android `SettingsScreen` `GeminiItem`.
/// The row says what the key is for until one is saved, then the answer
/// language; a tap opens the key field, "Answer in …" and Save key under it.
/// The key goes to the Keychain (`GeminiSettings`).
///
/// Drawn in ServerSettingsView's Pixel style: primary group label 8 in,
/// each row its own surfaceBright segment (20 outer / 4 inner corners).
struct GeminiSettingsSection: View {
    var app: CoverScheme
    var row: Color

    @State private var settings = GeminiSettings.shared
    @State private var open = false
    @State private var draft = GeminiSettings.shared.apiKey
    @FocusState private var keyFocused: Bool

    private var canSave: Bool {
        let d = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        return !d.isEmpty && d != settings.apiKey
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Features")
                .yoinRole(Tokens.type.labelLarge)
                .foregroundStyle(app.primary)
                .padding(.horizontal, 8)
                .accessibilityAddTraits(.isHeader)
            VStack(spacing: 2) {
                let rows = open ? 3 : 1
                headerRow.background(row, in: segment(0, rows))
                if open {
                    keyField.background(row, in: segment(1, rows))
                        .transition(.opacity.combined(with: .move(edge: .top)))
                    languageRow.background(row, in: segment(2, rows))
                        .transition(.opacity.combined(with: .move(edge: .top)))
                }
            }
            if open {
                saveButton
                    .padding(.top, 8)
                    .transition(.opacity.combined(with: .scale(scale: 0.96)))
            }
        }
        .animation(Yoin.spatial, value: open)
        .animation(Yoin.effects, value: settings.apiKey)
        .animation(Yoin.effects, value: settings.targetLanguage)
    }

    // MARK: Rows

    private var summary: String {
        settings.apiKey.isEmpty ? "Song info, Ask Gemini, translation and Memory titles" : settings.targetLanguage
    }

    private var headerRow: some View {
        Button {
            open.toggle()
            if !open { keyFocused = false }
        } label: {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("AI features")
                        .yoinRole(Tokens.type.bodyLarge)
                        .foregroundStyle(app.onSurface)
                    Text(summary)
                        .yoinRole(Tokens.type.bodyMedium)
                        .foregroundStyle(app.onSurfaceVariant)
                        .contentTransition(.opacity)
                        .lineLimit(2)
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
    }

    private var keyField: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("Gemini API key")
                .yoinRole(Tokens.type.labelMedium)
                .foregroundStyle(keyFocused ? app.primary : app.onSurfaceVariant)
                .animation(Yoin.effects, value: keyFocused)
            SecureField("", text: $draft, prompt: Text("AIza…").foregroundStyle(app.onSurfaceVariant.opacity(0.6)))
                .font(.gsf(Tokens.type.bodyLarge.size.cg))
                .foregroundStyle(app.onSurface)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .textContentType(.password)
                .focused($keyFocused)
                .submitLabel(.done)
                .onSubmit { if canSave { save() } }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .frame(maxWidth: .infinity, minHeight: 72, alignment: .leading)
        .contentShape(.rect)
        .onTapGesture { keyFocused = true }
    }

    /// "Answer in English": a menu of Android's eight languages.
    private var languageRow: some View {
        Menu {
            ForEach(GeminiSettings.languages, id: \.self) { language in
                Button {
                    settings.saveLanguage(language)
                } label: {
                    if language == settings.targetLanguage {
                        Label { Text(language) } icon: { Image("yoin.check") }
                    } else {
                        Text(language)
                    }
                }
            }
        } label: {
            HStack {
                Text("Answer in \(settings.targetLanguage)")
                    .yoinRole(Tokens.type.bodyLarge)
                    .foregroundStyle(app.onSurface)
                    .contentTransition(.opacity)
                Spacer(minLength: 16)
                YoinIcon("unfold_more", size: 20)
                    .foregroundStyle(app.onSurfaceVariant)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
    }

    private var saveButton: some View {
        Button(action: save) {
            Text("Save key")
                .yoinRole(Tokens.type.titleMedium)
                // MD3 disabled filled button: onSurface 38% on onSurface 12%.
                .foregroundStyle(canSave ? app.onPrimary : app.onSurface.opacity(0.38))
                .frame(maxWidth: .infinity, minHeight: 56)
                .background(canSave ? app.primary : app.onSurface.opacity(0.12), in: .capsule)
                .contentShape(.capsule)
        }
        .buttonStyle(PressScale())
        .disabled(!canSave)
        .animation(Yoin.effects, value: canSave)
    }

    private func save() {
        keyFocused = false
        settings.saveKey(draft)
        draft = settings.apiKey
        SongAboutLoader.shared.keyChanged()
    }

    private func segment(_ i: Int, _ count: Int) -> UnevenRoundedRectangle {
        let top: CGFloat = i == 0 ? 20 : 4
        let bottom: CGFloat = i == count - 1 ? 20 : 4
        return UnevenRoundedRectangle(topLeadingRadius: top, bottomLeadingRadius: bottom,
                                      bottomTrailingRadius: bottom, topTrailingRadius: top, style: .circular)
    }
}
