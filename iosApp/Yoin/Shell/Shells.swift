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
        // The bar's safeAreaBar doesn't reach the root pages' scroll views
        // inside the stack, so the last row ended under it. Reserve the bar
        // (60 + 2 bottom padding) here, as Android pads its root lists.
        .safeAreaPadding(.bottom, 62)
        .background(Yoin.surface)
    }
}

/// Phone: one NavigationStack, the glass bar floats on top, Now Playing is a
/// shell overlay whose glass morphs out of the bar's pill and back into it
/// (NowPlayingPresentation: one progress for ⌄, `npExpanded` and the drag).
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
                    .navigationDestination(for: Route.self) { route in
                        switch route {
                        case .album(let album):
                            AlbumDetailView(album: album)
                                .navigationTransition(.zoom(sourceID: album.id, in: zoomNS))
                        case .artist(let ref):
                            ArtistDetailView(ref: ref)
                        case .playlist(let id):
                            let info = model.playlistInfo(id)
                            AlbumDetailView(album: info.asAlbum, playlist: info)
                                .task(id: "\(model.store.generation)|\(id)") { await model.store.loadPlaylist(id) }
                        case .settings:
                            ServerSettingsView()
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

            if model.np.mounted {
                // Mounted and unmounted without a transition: the morph's
                // progress carries the whole in and out.
                NowPlayingMorph(npNamespace: npNS)
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
        .onChange(of: model.npExpanded) { _, open in model.np.show(open) }
        .onAppear {
            if model.npExpanded { model.np.snapOpen() }
        }
        .task {
            // `-screen np-morph` / `np-morph-close`: a deliberately slow
            // pill → page (or page → pill) morph, for frame-by-frame review.
            guard model.slowMorph else { return }
            try? await Task.sleep(for: .seconds(1))
            model.setNowPlaying(expanded: !model.npExpanded)
        }
        #if DEBUG
        .task { await NowPlayingMorphDebug.run(model) }
        #endif
    }
}

#if DEBUG
/// Recording aids. `-npAutoCycle YES`: open after 1 s, close after 3 s
/// (`-npAutoCycleHold`), twice.
/// `-npScriptedDrag YES`: open, then a scripted finger drags the page down
/// 40 % of the way and lets go (it folds into the pill), then opens again
/// and a short drag is let go (it springs back).
@MainActor
enum NowPlayingMorphDebug {
    static func run(_ model: AppModel) async {
        let d = UserDefaults.standard
        if d.bool(forKey: "npAutoCycle") {
            // `-npAutoCycleHold 6`: stay open longer (e.g. for `-npTab about-full`).
            let hold = d.double(forKey: "npAutoCycleHold") > 0 ? d.double(forKey: "npAutoCycleHold") : 3
            for _ in 0..<2 {
                try? await Task.sleep(for: .seconds(1))
                model.setNowPlaying(expanded: true)
                try? await Task.sleep(for: .seconds(hold))
                model.setNowPlaying(expanded: false)
                try? await Task.sleep(for: .seconds(1))
            }
        }
        if d.bool(forKey: "npScriptedDrag") {
            try? await Task.sleep(for: .seconds(1))
            model.setNowPlaying(expanded: true)
            try? await Task.sleep(for: .seconds(2))
            await drag(model, to: 0.4, release: 900)
            try? await Task.sleep(for: .seconds(2))
            model.setNowPlaying(expanded: true)
            try? await Task.sleep(for: .seconds(2))
            await drag(model, to: 0.12, release: 0)
        }
    }

    /// A finger dragging down `fraction` of the fold distance over 0.5 s
    /// (ease-out), holding briefly, then lifting at `release` pt/s.
    private static func drag(_ model: AppModel, to fraction: CGFloat, release: CGFloat) async {
        let np = model.np
        let total = fraction * np.dismissDistance
        let start = Date()
        while true {
            let t = min(1, CGFloat(Date().timeIntervalSince(start) / 0.5))
            np.dragChanged(12 + (total - 12) * (1 - pow(1 - t, 2)))
            if t >= 1 { break }
            try? await Task.sleep(for: .milliseconds(8))
        }
        try? await Task.sleep(for: .milliseconds(120))
        if np.dragEnded(total, velocity: release, predicted: total + release * 0.25) {
            model.setNowPlaying(expanded: false)
        }
    }
}
#endif
