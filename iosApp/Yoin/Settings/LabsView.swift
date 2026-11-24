import SwiftUI

/// Yoin Labs' and the Lyric Cards intro's words for iOS (Argon, 2026-11-10: Apple Intelligence's on-device
/// model where Android has Gemini Nano).
enum LabsCopy {
    static var introBody: String {
        String(localized: "Apple Intelligence's on-device model reads lyrics right here, offline, and picks a passage from songs you play a lot, songs you haven't played in a while, and songs you haven't heard yet. Tap a card to play.")
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
            LabsBody(status: processor.status,
                     optedIn: Binding(get: { store.optedIn },
                                      set: { on in withAnimation(Yoin.effects) { processor.setOptedIn(on) } }),
                     solidSurfaces: Binding(get: { GlassLab.shared.solidSurfaces },
                                            set: { on in withAnimation(Yoin.effects) { GlassLab.shared.solidSurfaces = on } }),
                     app: app, row: row,
                     onOpenSettings: {
                         if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                     },
                     onCheckAgain: { withAnimation(Yoin.effects) { _ = processor.checkNow() } })
        }
        .onAppear { processor.checkNow() }
    }
}

/// Merged single-group Experimental features section at the bottom of Settings › AI features (v4).
struct LabsSection: View {
    var app: CoverScheme
    var row: Color

    private let processor = LyricHighlightProcessor.shared
    private let store = LyricHighlightStore.shared
    @Environment(\.openURL) private var openURL

    var body: some View {
        LabsBody(status: processor.status,
                 optedIn: Binding(get: { store.optedIn },
                                  set: { on in withAnimation(Yoin.effects) { processor.setOptedIn(on) } }),
                 solidSurfaces: nil,
                 showIntro: false,
                 app: app, row: row,
                 onOpenSettings: {
                     if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                 },
                 onCheckAgain: { withAnimation(Yoin.effects) { _ = processor.checkNow() } })
            .onAppear { processor.checkNow() }
    }
}

/// The Labs page's body (Android LabsPageBody), over what it is given: the tip page's demo phone shows it
/// with a model that is ready and a switch of its own.
struct LabsBody: View {
    var status: OnDeviceModelStatus?
    @Binding var optedIn: Bool
    /// The Solid Surfaces switch; nil leaves it out (shown on Appearance, or omitted in the tip demo).
    var solidSurfaces: Binding<Bool>? = nil
    var showIntro = true
    var app: CoverScheme
    var row: Color
    var onOpenSettings: () -> Void
    var onCheckAgain: () -> Void

    var body: some View {
        if showIntro {
            Text("Experimental features we're trying out. They may change or be removed.")
                .yoinRole(Tokens.type.bodyMedium)
                .foregroundStyle(app.onSurfaceVariant)
                .padding(.horizontal, 8)
        }
        section(String(localized: "Experimental features")) {
            let count = solidSurfaces == nil ? 2 : 3
            VStack(spacing: 2) {
                modelSegment
                    .background(row, in: CloudSyncView.segment(0, count))
                lyricsSegment
                    .background(row, in: CloudSyncView.segment(1, count))
                if let solidSurfaces {
                    solidSurfacesSegment(solidSurfaces)
                        .background(row, in: CloudSyncView.segment(2, count))
                }
            }
        }
    }

    private var modelSegment: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Apple Intelligence").yoinRole(Tokens.type.bodyLarge).foregroundStyle(app.onSurface)
                    Text(LabsCopy.status(status)).yoinRole(Tokens.type.bodyMedium)
                        .foregroundStyle(app.onSurfaceVariant).contentTransition(.opacity)
                }
                Spacer()
            }
            HStack(spacing: 10) {
                if status == .notEnabled || status == .notReady {
                    actionButton(String(localized: "Open Settings"), prominent: true, action: onOpenSettings)
                }
                actionButton(String(localized: "Check again"), prominent: false, action: onCheckAgain)
            }
            Text("Managed and downloaded by the system. Yoin reads lyrics on this device only while open, offline.")
                .yoinRole(Tokens.type.bodySmall).foregroundStyle(app.onSurfaceVariant)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var lyricsSegment: some View {
        VStack(alignment: .leading, spacing: 6) {
            Toggle(isOn: $optedIn) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Lyric Cards").yoinRole(Tokens.type.bodyLarge).foregroundStyle(app.onSurface)
                    Text("Show lyrics picked on this device on Home, and tap to play")
                        .yoinRole(Tokens.type.bodyMedium).foregroundStyle(app.onSurfaceVariant)
                }
            }
            .tint(app.primary)
            .disabled(status == .unsupported)
            .demoTarget("labs-switch")
            if status != .available {
                Text("Lines you share or save on the lyrics page still show up on Home.")
                    .yoinRole(Tokens.type.bodySmall).foregroundStyle(app.onSurfaceVariant)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func solidSurfacesSegment(_ binding: Binding<Bool>) -> some View {
        Toggle(isOn: binding) {
            VStack(alignment: .leading, spacing: 2) {
                Text("Solid Surfaces").yoinRole(Tokens.type.bodyLarge).foregroundStyle(app.onSurface)
                Text("Replace in-app Liquid Glass with opaque bars, panels, and filled buttons")
                    .yoinRole(Tokens.type.bodyMedium).foregroundStyle(app.onSurfaceVariant)
            }
        }
        .tint(app.primary)
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// Crisp Material capsule inside the opaque `row` card: Liquid Glass `.buttonStyle(.glass)`
    /// is meant for floating chrome, and on an opaque light card its refraction/vibrancy lens
    /// smears small CJK labels ("重新检查").
    private func actionButton(_ title: String, prominent: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .yoinRole(Tokens.type.labelLarge)
                .foregroundStyle(prominent ? app.onPrimary : app.onSecondaryContainer)
                .padding(.horizontal, 16)
                .frame(minHeight: 36)
                .background(prominent ? app.primary : app.secondaryContainer, in: Capsule())
                .contentShape(.capsule)
        }
        .buttonStyle(PressScale())
    }

    private func section<C: View>(_ title: String, @ViewBuilder _ content: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).yoinRole(Tokens.type.labelLarge).foregroundStyle(app.primary).padding(.horizontal, 8)
                .accessibilityAddTraits(.isHeader)
            content()
        }
    }
}
