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
            // A safe-area bar, not an overlay, so scroll views know it is
            // there: the system soft scroll-edge effect (progressive blur and
            // fade) runs under it, as under Apple's tab bars.
            .safeAreaBar(edge: .bottom) {
                ShellBar(npNamespace: npNS)
                    .padding(.horizontal, 14)
                    .padding(.bottom, 2)
                    // Slides away under the keyboard while searching.
                    .offset(y: model.searchActive ? 140 : 0)
                    .opacity(model.searchActive ? 0 : 1)
            }

            if model.searchActive {
                SearchView(npNamespace: npNS)
                    .zIndex(0.5)
                    .transition(.opacity)
            }

            if model.npExpanded {
                NowPlayingView(npNamespace: npNS)
                    .zIndex(1)
                    .transition(.asymmetric(insertion: .identity, removal: .opacity.animation(Yoin.effects)))
            }
        }
        .background(Yoin.surface)
        .overlay(alignment: .top) {
            // Soft top edge under the status bar. The system scroll-edge
            // effect only runs under a real bar, and the root pages hide the
            // navigation bar (custom headers), so this approximates it: a
            // material band that fades out downward, like Apple Music's top.
            if model.path.isEmpty && !model.npExpanded {
                GeometryReader { g in
                    Rectangle()
                        .fill(.ultraThinMaterial)
                        .mask(LinearGradient(stops: [.init(color: .black, location: 0),
                                                     .init(color: .black, location: 0.45),
                                                     .init(color: .clear, location: 1)],
                                             startPoint: .top, endPoint: .bottom))
                        .frame(height: g.safeAreaInsets.top + 22)
                        .ignoresSafeArea(edges: .top)
                }
                .allowsHitTesting(false)
            }
        }
        .task {
            // `-screen np-morph`: a deliberately slow pill → page expansion.
            guard model.slowMorph else { return }
            try? await Task.sleep(for: .seconds(1))
            withAnimation(.linear(duration: 6)) { model.npExpanded = true }
        }
    }
}

