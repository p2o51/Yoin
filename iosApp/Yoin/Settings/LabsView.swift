import Observation
import SwiftUI

/// Coordinates Yoin Labs inspection, proactive ODR download, and removal for `LyraSingLite` on iOS.
@MainActor
@Observable
final class SingModelLabsController {
    static let shared = SingModelLabsController()

    enum Status: Equatable {
        case checking
        case notDownloaded
        case downloading(Double)
        case ready(Int64)
        case failed(String)
    }

    private(set) var status: Status = .checking
    @ObservationIgnored private let modelStore: SingModelStore
    @ObservationIgnored private let odrProvider: SingModelODRProvider
    @ObservationIgnored private var downloadTask: Task<Void, Never>?

    init(
        modelStore: SingModelStore = SingModelStore(),
        odrProvider: SingModelODRProvider? = nil
    ) {
        self.modelStore = modelStore
        self.odrProvider = odrProvider ?? SingModelODRProvider(modelStore: modelStore)
    }

    var optimizationLine: String {
        String(localized: "Optimized for Apple Neural Engine")
    }

    static func formatBytes(_ bytes: Int64) -> String {
        guard bytes > 0 else { return "0 MB" }
        let mb = Double(bytes) / (1024.0 * 1024.0)
        if mb >= 1.0 {
            return String(format: "%.1f MB", mb)
        }
        return String(format: "%.0f KB", Double(bytes) / 1024.0)
    }

    func refresh() {
        if case .downloading = status { return }
        Task { @MainActor [weak self] in
            guard let self else { return }
            if let bytes = await self.modelStore.installedSizeBytes() {
                self.status = .ready(bytes)
            } else {
                self.status = .notDownloaded
            }
        }
    }

    func downloadNow() {
        if case .downloading = status { return }
        downloadTask?.cancel()
        status = .downloading(0)
        downloadTask = Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                let lease = try await self.odrProvider.loadModel { progress in
                    Task { @MainActor [weak self] in
                        guard let self, case .downloading = self.status else { return }
                        self.status = .downloading(max(0, min(1, progress)))
                    }
                }
                // Release MLModel immediately so idle memory stays at 0% (Race-to-Idle).
                lease.release()
                let bytes = await self.modelStore.installedSizeBytes() ?? SingModelAsset.expectedZipBytes
                self.status = .ready(bytes)
            } catch is CancellationError {
                self.refresh()
            } catch {
                self.status = .failed(error.localizedDescription)
            }
        }
    }

    func removeModel() {
        downloadTask?.cancel()
        downloadTask = nil
        Task { @MainActor [weak self] in
            guard let self else { return }
            try? await self.modelStore.removeInstalledModel()
            Bundle.main.setPreservationPriority(0.0, forTags: [SingModelAsset.odrTag])
            self.status = .notDownloaded
        }
    }
}

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
    var showSingModel = true
    var app: CoverScheme
    var row: Color
    var onOpenSettings: () -> Void
    var onCheckAgain: () -> Void
    private var singController: SingModelLabsController { SingModelLabsController.shared }

    var body: some View {
        if showIntro {
            Text("Experimental features we're trying out. They may change or be removed.")
                .yoinRole(Tokens.type.bodyMedium)
                .foregroundStyle(app.onSurfaceVariant)
                .padding(.horizontal, 8)
        }
        section(String(localized: "Experimental features")) {
            let count = 2 + (showSingModel ? 1 : 0) + (solidSurfaces == nil ? 0 : 1)
            let lyricsIndex = showSingModel ? 2 : 1
            let solidIndex = lyricsIndex + 1
            VStack(spacing: 2) {
                modelSegment
                    .background(row, in: CloudSyncView.segment(0, count))
                if showSingModel {
                    singModelSegment
                        .background(row, in: CloudSyncView.segment(1, count))
                        .onAppear { singController.refresh() }
                }
                lyricsSegment
                    .background(row, in: CloudSyncView.segment(lyricsIndex, count))
                if let solidSurfaces {
                    solidSurfacesSegment(solidSurfaces)
                        .background(row, in: CloudSyncView.segment(solidIndex, count))
                }
            }
        }
    }

    private var singModelSegment: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Lyra Sing").yoinRole(Tokens.type.bodyLarge).foregroundStyle(app.onSurface)
                    Text(singStatusText)
                        .yoinRole(Tokens.type.bodyMedium)
                        .foregroundStyle(singStatusIsError ? app.error : app.onSurfaceVariant)
                        .contentTransition(.opacity)
                }
                Spacer()
            }
            if case .downloading(let progress) = singController.status {
                ProgressView(value: progress)
                    .tint(app.primary)
            }
            HStack(spacing: 10) {
                switch singController.status {
                case .notDownloaded, .failed:
                    actionButton(String(localized: "Download model"), prominent: true) {
                        singController.downloadNow()
                    }
                case .ready:
                    actionButton(String(localized: "Remove model"), prominent: false) {
                        singController.removeModel()
                    }
                case .checking, .downloading:
                    ProgressView()
                }
            }
            Text(singController.optimizationLine)
                .yoinRole(Tokens.type.bodySmall)
                .foregroundStyle(app.onSurfaceVariant)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var singStatusIsError: Bool {
        if case .failed = singController.status { return true }
        return false
    }

    private var singStatusText: String {
        switch singController.status {
        case .checking:
            return String(localized: "Checking…")
        case .notDownloaded:
            let est = SingModelLabsController.formatBytes(SingModelAsset.expectedZipBytes)
            return String(localized: "Not downloaded (\(est))")
        case .downloading(let p):
            let pct = Int((p * 100).rounded())
            return String(localized: "Downloading… \(pct)%")
        case .ready(let bytes):
            let size = SingModelLabsController.formatBytes(bytes)
            return String(localized: "Ready on this device (\(size))")
        case .failed(let msg):
            return String(localized: "Download failed: \(msg)")
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
