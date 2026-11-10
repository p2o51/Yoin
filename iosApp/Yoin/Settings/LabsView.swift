import SwiftUI

/// Yoin Labs' and the Lyric Cards intro's words for iOS (Argon, 2026-11-10: Apple Intelligence's on-device
/// model where Android has Gemini Nano).
enum LabsCopy {
    static var introBody: String {
        String(localized: "Apple Intelligence's on-device model reads lyrics right here, offline, and picks a passage from songs you play a lot, songs you haven't played in a while, and songs you haven't heard yet. Tap a card to play from that line.")
    }

    static func introStatus(_ s: OnDeviceModelStatus) -> String {
        switch s {
        case .available: String(localized: "Your device can run it now.")
        case .notEnabled, .notReady: String(localized: "Your device supports it. Turn on Apple Intelligence in Settings first.")
        case .unsupported: String(localized: "This device doesn't support Apple Intelligence, so Yoin can't pick lines for you here.")
        }
    }

    static func status(_ s: OnDeviceModelStatus?) -> String {
        switch s {
        case nil: String(localized: "Checking…")
        case .available: String(localized: "Ready on this device")
        case .notEnabled: String(localized: "Turn on Apple Intelligence in Settings first")
        case .notReady: String(localized: "Preparing model, downloading in the background…")
        case .unsupported: String(localized: "Not supported on this device")
        }
    }
}

/// Settings › Features › Yoin Labs (Android LabsPage, #189): what's experimental, the on-device model's
/// state, and the Lyric Cards switch. Off stops the model at once; kept lines stay.
struct LabsView: View {
    private let processor = LyricHighlightProcessor.shared
    private let store = LyricHighlightStore.shared
    @Environment(\.openURL) private var openURL

    var body: some View {
        SettingsPage(title: "Yoin Labs") { app, row in
            Text("Experimental features we're trying out. They may change or be removed.")
                .yoinRole(Tokens.type.bodyMedium)
                .foregroundStyle(app.onSurfaceVariant)
                .padding(.horizontal, 8)
            section(app, String(localized: "On-device model")) { modelCard(app, row) }
            section(app, String(localized: "Experimental features")) { featuresCard(app, row) }
        }
        .onAppear { processor.checkNow() }
    }

    private func modelCard(_ app: CoverScheme, _ row: Color) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Apple Intelligence").yoinRole(Tokens.type.bodyLarge).foregroundStyle(app.onSurface)
                    Text(LabsCopy.status(processor.status)).yoinRole(Tokens.type.bodyMedium)
                        .foregroundStyle(app.onSurfaceVariant).contentTransition(.opacity)
                }
                Spacer()
            }
            HStack(spacing: 10) {
                if processor.status == .notEnabled || processor.status == .notReady {
                    Button("Open Settings") {
                        if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                    }
                    .buttonStyle(.glassProminent)
                }
                Button("Check again") { withAnimation(Yoin.effects) { _ = processor.checkNow() } }
                    .buttonStyle(.glass)
            }
            Text("Managed and downloaded by the system. Yoin reads lyrics on this device only while open, offline.")
                .yoinRole(Tokens.type.bodySmall).foregroundStyle(app.onSurfaceVariant)
    }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(row, in: RoundedRectangle(cornerRadius: 20))
                }

    private func featuresCard(_ app: CoverScheme, _ row: Color) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Toggle(isOn: Binding(get: { store.optedIn }, set: { on in withAnimation(Yoin.effects) { processor.setOptedIn(on) } })) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Lyric Cards").yoinRole(Tokens.type.bodyLarge).foregroundStyle(app.onSurface)
                    Text("Show lyrics picked on this device on Home, and tap to play from that line")
                        .yoinRole(Tokens.type.bodyMedium).foregroundStyle(app.onSurfaceVariant)
                }
            }
            .tint(app.primary)
            .disabled(processor.status == .unsupported)
            if processor.status != .available {
                Text("Lines you share or save on the lyrics page still show up on Home.")
                    .yoinRole(Tokens.type.bodySmall).foregroundStyle(app.onSurfaceVariant)
            }
    }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(row, in: RoundedRectangle(cornerRadius: 20))
                }

    private func section<C: View>(_ app: CoverScheme, _ title: String, @ViewBuilder _ content: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).yoinRole(Tokens.type.labelLarge).foregroundStyle(app.primary).padding(.horizontal, 8)
                .accessibilityAddTraits(.isHeader)
            content()
        }
    }
}
