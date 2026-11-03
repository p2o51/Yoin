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
    @State private var model = AppModel()
    @Environment(\.horizontalSizeClass) private var hsc

    var body: some View {
        Group {
            if LaunchConfig.baseScreen == "type" {
                TypeSpecimenView()
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
        .fullScreenCover(isPresented: $model.showLanding) {
            LandingGuideView()
                .environment(model)
                .environment(\.glassFlavor, model.glassFlavor)
        }
    }
}
