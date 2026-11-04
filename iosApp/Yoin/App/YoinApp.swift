import SwiftUI

@main
struct YoinApp: App {
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
        .environment(model)
        .environment(\.glassFlavor, model.glassFlavor)
        .tint(model.accent)
        .fontDesign(.default)
        .onChange(of: hsc, initial: true) { _, new in model.isPad = new == .regular }
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
        .fullScreenCover(isPresented: $model.showLanding) {
            LandingGuideView()
                .environment(model)
                .environment(\.glassFlavor, model.glassFlavor)
        }
    }
}
