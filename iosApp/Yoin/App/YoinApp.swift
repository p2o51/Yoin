import SwiftUI

@main
struct YoinApp: App {
    init() {
        YoinFont.selfCheck()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }
}

struct RootView: View {
    // Created once. A `@State` initial value is re-evaluated every time
    // RootView is re-created, and each throwaway AppModel signed in and built
    // its own player (hundreds per launch).
    @State private var model = AppModel.shared
    @Environment(\.horizontalSizeClass) private var hsc

    var body: some View {
        Group {
            if LaunchConfig.baseScreen == "type" {
                TypeSpecimenView()
            } else if LaunchConfig.baseScreen == "emblem-glass" {
                EmblemGlassLab()
            } else if LaunchConfig.baseScreen.hasPrefix("emblem-award") {
                EmblemAwardLab()
            } else if hsc == .regular {
                PadShell()
            } else {
                PhoneShell()
            }
        }
        // Covered by the landing: out of VoiceOver's reach until it hands off.
        .accessibilityHidden(model.landing != nil)
        // Song sheet, Add to playlist, playlist dialogs, confirmations (Library/TrackMenuSheet.swift).
        .playlistActionsHost()
        .environment(model)
        .environment(\.glassFlavor, model.glassFlavor)
        .tint(model.accent)
        // No .fontDesign here: on iOS 27 a design in the environment replaces a
        // custom font with the system face of that design, so every Google Sans
        // Flex text in the app drew in SF on the iPhone (the iOS 26 simulator
        // ignored it). Text without its own font (sheets, menus, fields) is
        // Google Sans Flex too.
        .font(.gsf(17))
        .onChange(of: hsc, initial: true) { _, new in model.isPad = new == .regular }
        // Cloud sync (Data/Sync): Google's sign-in redirect, and the start (no network unless sync is on).
        .onOpenURL { _ = CloudSync.handle(url: $0) }
        .task {
            let profiles = model.profiles
            CloudSync.shared.start { profiles.syncAccounts }
            // Playlist cloud backups follow their playlists on each return to the app (Data/Backup).
            PlaylistBackups.shared.start(library: model.store)
        }
        .preferredColorScheme(GlassLab.shared.colorScheme)
        .overlay {
            if GlassLab.shared.panelOpen {
                GlassLabPanel()
                    .environment(model)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(Yoin.spatial, value: GlassLab.shared.panelOpen)
        .background(GlassLabGestureInstaller())
        .background(SlowMotionInstaller())
        // The first-run landing / Welcome guide: above the shell (bar included), like Android's
        // LandingHost. Mounted and removed without a transition; it fades itself in and out.
        .overlay {
            if let landing = model.landing {
                LandingView(session: landing)
                    .id(landing.id)
                    .environment(model)
                    .environment(\.glassFlavor, model.glassFlavor)
                    .font(.gsf(17))
                    .transition(.identity)
            }
        }
    }
}

/// `-animationSpeed` (debug): slows the window's layer tree too, so Core
/// Animation and the glass's own transitions run at the springs' speed.
private struct SlowMotionInstaller: UIViewRepresentable {
    func makeUIView(context: Context) -> UIView {
        let v = UIView()
        if LaunchConfig.animationSpeed != 1 {
            DispatchQueue.main.async { v.window?.layer.speed = Float(LaunchConfig.animationSpeed) }
        }
        return v
    }

    func updateUIView(_ uiView: UIView, context: Context) {}
}
