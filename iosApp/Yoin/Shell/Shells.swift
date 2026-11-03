import SwiftUI

/// Root sections. Switching uses a spatial spring and a short slide.
struct SectionContent: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        ZStack {
            switch model.section {
            case .home:
                HomeView()
                    .transition(.asymmetric(insertion: .move(edge: .leading).combined(with: .opacity),
                                            removal: .move(edge: .leading).combined(with: .opacity)))
            case .library:
                LibraryView()
                    .transition(.asymmetric(insertion: .move(edge: .trailing).combined(with: .opacity),
                                            removal: .move(edge: .trailing).combined(with: .opacity)))
            }
        }
        .background(Yoin.surface)
    }
}

/// Phone: one NavigationStack, the glass bar floats on top, Now Playing is a
/// shell overlay that grows out of the bar's pill.
struct PhoneShell: View {
    @Environment(AppModel.self) private var model
    @Namespace private var zoomNS
    @Namespace private var npNS

    var body: some View {
        @Bindable var model = model
        ZStack(alignment: .bottom) {
            NavigationStack(path: $model.path) {
                SectionContent()
                    .toolbar(.hidden, for: .navigationBar)
                    .navigationDestination(for: Album.self) { album in
                        AlbumDetailView(album: album)
                            .navigationTransition(.zoom(sourceID: album.id, in: zoomNS))
                    }
            }
            .environment(\.zoomNamespace, zoomNS)

            ShellBar(npNamespace: npNS)
                .padding(.horizontal, 14)
                .padding(.bottom, 2)

            if model.npExpanded {
                NowPlayingView(npNamespace: npNS)
                    .zIndex(1)
                    .transition(.asymmetric(insertion: .identity, removal: .opacity.animation(Yoin.effects)))
            }
        }
        .background(Yoin.surface)
    }
}

