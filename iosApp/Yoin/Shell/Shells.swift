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
    /// Now Playing is in the tree: from the open tap until the close morph lands.
    @State private var npMounted = false

    var body: some View {
        @Bindable var model = model
        ZStack(alignment: .bottom) {
            NavigationStack(path: $model.path) {
                SectionContent()
                    .toolbar(.hidden, for: .navigationBar)
                    .navigationDestination(for: Route.self) { route in
                        switch route {
                        case .album(let album):
                            AlbumDetailView(album: album)
                                .navigationTransition(.zoom(sourceID: album.id, in: zoomNS))
                        case .artist(let name):
                            ArtistDetailView(artist: name)
                        case .playlist(let id):
                            let info = MockLibrary.shared.playlist(id)
                            AlbumDetailView(album: info.asAlbum, playlist: info)
                        }
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

            if npMounted {
                // Mounted and unmounted without a transition: the morph
                // (`open`) carries the whole in and out.
                NowPlayingView(npNamespace: npNS, open: model.npMorphOpen)
                    .zIndex(1)
                    .transition(.identity)
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
        .onChange(of: model.npExpanded) { _, open in morph(open) }
        .onAppear {
            if model.npExpanded {
                npMounted = true
                model.npMorphOpen = true
            }
        }
        .task {
            // `-screen np-morph` / `np-morph-close`: a deliberately slow
            // pill → page (or page → pill) morph, for frame-by-frame review.
            guard model.slowMorph else { return }
            try? await Task.sleep(for: .seconds(1))
            model.setNowPlaying(expanded: !model.npExpanded)
        }
    }

    /// Open: mount at the pill (no animation), then on the next frame
    /// spring the morph open, so the page grows out of the pill instead of
    /// appearing at full size. Close: spring the morph shut, unmount when it
    /// lands (unless it was reopened meanwhile).
    private func morph(_ open: Bool) {
        let spring = model.slowMorph ? Animation.linear(duration: 6) : Yoin.spatial
        var instant = Transaction()
        instant.disablesAnimations = true
        if open {
            withTransaction(instant) { npMounted = true }
            DispatchQueue.main.async {
                guard model.npExpanded else { return }
                withAnimation(spring) { model.npMorphOpen = true }
            }
        } else {
            withAnimation(spring) {
                model.npMorphOpen = false
            } completion: {
                guard !model.npExpanded else { return }
                withTransaction(instant) { npMounted = false }
            }
        }
    }
}

