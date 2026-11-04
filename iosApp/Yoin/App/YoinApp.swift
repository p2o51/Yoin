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
            } else if LaunchConfig.baseScreen.hasPrefix("emblem-award") {
                EmblemAwardLab()
            } else if hsc == .regular {
                PadShell()
            } else {
                PhoneShell()
            }
        }
        // Song sheet, Add to playlist, playlist dialogs, confirmations (Library/TrackMenuSheet.swift).
        .playlistActionsHost()
        .environment(model)
        .environment(\.glassFlavor, model.glassFlavor)
        .tint(model.accent)
        .fontDesign(.default)
        // Text without its own font (sheets, menus, fields) is Google Sans
        // Flex too, not SF.
        .font(.gsf(17))
        .onChange(of: hsc, initial: true) { _, new in model.isPad = new == .regular }
        // Cloud sync (Data/Sync): Google's sign-in redirect, and the start (no network unless sync is on).
        .onOpenURL { _ = CloudSync.handle(url: $0) }
        .task {
            let session = model.session, apple = model.appleMusic
            CloudSync.shared.start {
                SyncDomainBridge.Accounts(subsonic: session.account.map { ($0.baseUrl, $0.username) },
                                          appleMusic: apple.isAuthorized, storefront: nil)
            }
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
        .fullScreenCover(isPresented: $model.showLanding) {
            LandingGuideView()
                .environment(model)
                .environment(\.glassFlavor, model.glassFlavor)
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
