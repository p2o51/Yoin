import SwiftUI
import UIKit
import YoinShared

/// Album page, page 1 of Android's two-page album detail (AlbumDetailScreen,
/// AlbumSpectrumBar, DetailPullUpReshape, AlbumDetailComponents), phone
/// portrait. Two states on one progress `p`:
/// - Hero (p = 0): header, cover with the Yoin "butterfly" blocks behind it,
///   the score emblem, and the track titles flowing as one paragraph.
/// - Track list (p = 1): pulled up. The cover flies into a 48 pt stamp at the
///   end of the title row, the header turns into the cover's color spectrum
///   with white text, and the list fades in.
///
/// Page 1 ↔ page 2 (Android AlbumPageFlight, AlbumScrapbookPage scrapLayer):
/// the pager follows the finger 1:1. With the hero at rest the cover flies to
/// the scrapbook's cover (taking its tilt) instead of sliding off, the hero
/// text is gone a third of the way in, and each scrapbook piece trails the
/// drag by its own depth and leans a little further.
///
/// iOS adaptations: the back button is a glass circle (Android: a tonal
/// circle), and edge-swipe back still works with the navigation bar hidden
/// and always wins over the pager. The pulled-up top bar is a Glass Lab
/// choice (`detailTopBar`): the spectrum with a soft lower edge (default: its
/// colors fade out over `bandFade` while the list under them blurs
/// progressively, one curve for both), Liquid Glass, the system's progressive
/// scroll-edge blur, or Android's hard-edged spectrum.
struct AlbumDetailView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    private var routeAlbum: Album
    var padColumn = false
    /// Playlist mode (Android PlaylistDetailScreen): same hero / pull-up /
    /// spectrum, but owner + "Playlist" meta, a ⋮ menu, the stacked-V
    /// backdrop, a Length / Owner / Description block instead of the emblem,
    /// thumb-led track rows, and no scrapbook page.
    private var routePlaylist: PlaylistInfo?

    init(album: Album, padColumn: Bool = false, playlist: PlaylistInfo? = nil) {
        routeAlbum = album
        self.padColumn = padColumn
        routePlaylist = playlist
    }

    /// The page's album / playlist as the store has it now (a server album's
    /// tracks arrive after the push; the mock is the route's own).
    private var playlist: PlaylistInfo? { routePlaylist.map { model.playlistInfo($0.id.rawId) } }
    private var album: Album { playlist?.asAlbum ?? model.resolved(routeAlbum) }
    private var isMock: Bool { routeAlbum.id.provider == "mock" }

    @State private var p: CGFloat = ["album-list", "playlist-list"].contains(LaunchConfig.screen) ? 1
        : LaunchConfig.screen == "album-mid" ? 0.42 : 0
    /// Pull-up drag: `p` when the drag was claimed.
    @State private var dragBase: CGFloat?
    /// Pager progress, 0 = overview, 1 = scrapbook: the finger moves it 1:1,
    /// a spring takes it on release; everything on both pages reads it.
    @State private var page: CGFloat = LaunchConfig.screen.hasPrefix("album-scrapbook") ? 1 : 0
    /// Pager drag: `page` when the drag was claimed (nil = not dragging).
    @State private var pageBase: CGFloat?
    @State private var pageWidth: CGFloat = 402
    @State private var pageSpring = AlbumSpring()
    @State private var pullSpring = AlbumSpring()
    /// The pager's spring is running (the scrapbook isn't settled yet).
    @State private var pageMoving = false
    /// The page the pager is on or settling to (a drag counts as off page 1).
    /// The edge swipe follows this, not the live progress, so a spring's
    /// bounce around page 1 never flips the back swipe mid-gesture.
    @State private var pageTarget: CGFloat = LaunchConfig.screen.hasPrefix("album-scrapbook") ? 1 : 0
    private var offFirstPage: Bool { pageBase != nil || pageTarget > 0 }
    /// Pull-up haptics: whether the drag is past its snap point (release would
    /// form the band / fold it back); nil until the drag has moved.
    @State private var pullArmed: Bool?
    /// The settle under way is forming the band; its landing gets one beat.
    @State private var pullLanding = false
    /// The pulled-up list sits at its top (a pull-down there collapses it).
    @State private var listAtTop = true
    /// This page's claim on the edge swipe (AlbumPopGate).
    @State private var gateID = UUID()
    private var pageFrac: CGFloat {
        if let f = Self.forcedPageFrac, page == 0, pageBase == nil { return f }
        return min(1, max(0, page))
    }
    private static let pageHold: (from: CGFloat, to: CGFloat)? = {
        let parts = (UserDefaults.standard.string(forKey: "pageHold") ?? "").split(separator: ",")
            .compactMap { Double($0) }
        return parts.count == 2 ? (CGFloat(parts[0]), CGFloat(parts[1])) : nil
    }()
    /// Screenshots: `-pageFrac 0.5` holds the pager mid-swipe.
    private static let forcedPageFrac = UserDefaults.standard.string(forKey: "pageFrac")
        .flatMap(Double.init).map { CGFloat($0) }
    /// The scrapbook cover's resting rect (this page's coordinates) and tilt:
    /// where the hero cover lands (Android AlbumPageFlight.coverTarget).
    @State private var scrapCover: CGRect?
    @State private var scrapCoverTilt: Double = 0
    /// Screenshots: `-listScroll 160` opens the pulled-up list scrolled.
    @State private var listPosition = ScrollPosition(y: CGFloat(UserDefaults.standard.double(forKey: "listScroll")))
    /// The album's rate + review sheet (Android AlbumRateSheet): the pen, the
    /// comment, the emblem and the scrapbook's review all open it.
    @State private var rating = LaunchConfig.screen == "album-rate"

    /// Pulled-up top bar (Glass Lab): 4 the safe-area strip (default), 0
    /// spectrum with a soft blurred edge, 1 Liquid Glass, 2 progressive blur,
    /// 3 Android's hard-edged spectrum.
    ///
    /// The strip (51, 2026-11-06): on iOS the progressive blur under the
    /// header carries the layering, so the spectrum is decoration only. It
    /// runs from the top of the screen to just below the safe area
    /// (`stripExtend`) and fades out; the header keeps its own ink.
    private var topBar: Int { Int(GlassLab.shared.extra("detailTopBar").rounded()) }
    /// The spectrum is drawn (with or without its soft edge).
    private var spectrumBar: Bool { topBar == 0 || topBar == 3 }
    /// The spectrum's soft edge: how far below the header its colors fade
    /// out, and the list's blur radius at the header's edge. The list's
    /// content starts below the fade, so at rest nothing sits under it.
    private var bandFade: CGFloat { topBar == 0 ? 28 : 0 }
    /// The strip: the spectrum only, the header on the progressive blur.
    private var stripBar: Bool { topBar == 4 }
    /// How far the strip reaches below the safe area (Glass Lab slider).
    private var stripExtend: CGFloat {
        let v = GlassLab.shared.extras["albumStripExtend"] ?? 0
        return v > 0 ? v : 14
    }
    private static let bandBlurRadius: CGFloat = 10
    /// The page's own space (the header's top edge is y = 0), where the list
    /// rows find the band.
    static let pageSpace = "albumPage"
    /// The soft edge's strength: 0 until the list is nearly pulled up.
    private var bandSoft: CGFloat { topBar == 0 ? smooth(p, 0.7, 1) : 0 }
    /// The hero's cover flies to page 2 (hero at rest, an album, motion allowed).
    private var coverFlies: Bool { playlist == nil && !reduceMotion && scrapCover != nil && p < 0.01 }

    /// The cover's own scheme (title, blocks, dots, Play) and the app's
    /// (everything else), as on Android.
    private var s: CoverScheme { CoverScheme(album: album, dark: colorScheme == .dark) }
    private var app: CoverScheme {
        model.isIdle ? .brand(dark: colorScheme == .dark)
                     : CoverScheme(album: model.playback.album, dark: colorScheme == .dark)
    }

    /// The header below the status bar: 4 top + the 52 title row (headlineSmall
    /// 32 + bodyMedium 20) + the page dots (5 + 20) + 6 bottom (AlbumTopHeader).
    /// Playlists have no dots row: 4 + 52 + 6 (PlaylistTopHeader).
    private var headerContent: CGFloat { playlist == nil ? 4 + 52 + 25 + 6 : 4 + 52 + 6 }

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width
            let side = min(Tokens.detail.ALBUM_COVER_WIDTH_FRACTION.cg * w, Tokens.detail.ALBUM_COVER_MAX.cg)
            let band = side + 56
            let e = smooth(p)
            let f = pageFrac
            // The spectrum turns the header white; glass and blur keep its ink.
            let white = spectrumBar ? smooth(p, 0.25, 0.7) : 0
            // Hero text while its cover flies: gone a third of the way in (albumHeroPageFade).
            let heroFade = coverFlies ? min(1, max(0, 1 - f * 3.5)) : 1
            ZStack(alignment: .topLeading) {
                background
                // Page 0: the bar, the stamp and the hero slide away with it.
                ZStack(alignment: .topLeading) {
                    if playlist != nil {
                        stackedV(w: w, side: side, band: band)
                            .opacity(1 - smooth(p, 0, 0.6))
                    } else {
                        butterfly(w: w, side: side, band: band)
                            .opacity(1 - smooth(p, 0.62, 0.98))
                    }
                    hero(w: w, side: side, band: band)
                        .opacity((1 - e) * heroFade)
                        .offset(y: -e * 15) // Android: −40 px
                        .allowsHitTesting(e < 0.5)
                    trackList(w: w)
                        .opacity(e)
                        .allowsHitTesting(e >= 0.5)
                    switch topBar {
                    case 1: glassBar(w: w, top: geo.safeAreaInsets.top)
                    case 2:
                        // With the track list's own soft scroll edge.
                        ProgressiveEdgeBlur(tint: topTint)
                            .frame(width: w, height: geo.safeAreaInsets.top + headerContent + 4)
                            .offset(y: -geo.safeAreaInsets.top)
                            .opacity(smooth(p, 0.3, 0.85))
                    case 4:
                        // The layering: the progressive blur, as the blur bar.
                        ProgressiveEdgeBlur(tint: topTint)
                            .frame(width: w, height: geo.safeAreaInsets.top + headerContent + 4)
                            .offset(y: -geo.safeAreaInsets.top)
                            .opacity(smooth(p, 0.3, 0.85))
                        // The decoration: the cover's spectrum, safe area + a little.
                        spectrum(w: w, side: side, top: geo.safeAreaInsets.top)
                    default: spectrum(w: w, side: side, top: geo.safeAreaInsets.top)
                    }
                }
                .offset(x: -f * w)
                // Android z-order: the flying cover under the scrapbook page (its
                // emblem and dots on top), the stamp over it; the header on top.
                cover(w: w, side: side, band: band)
                    .zIndex(coverFlies ? 0.5 : 1.5)
                // Page 1: the scrapbook, scrolling under the header (albums only).
                if playlist == nil {
                    Group {
                    AlbumScrapbookView(album: album, plays: plays,
                                       settled: page >= 1 && pageBase == nil && !pageMoving,
                                       topInset: headerContent, pageOffset: 1 - f, pageWidth: w,
                                       coverInFlight: coverFlies && f < 0.999,
                                       onEditReview: { rating = true }) { rect, tilt in
                        scrapCover = rect
                        scrapCoverTilt = tilt
                    }
                    // Its own frame is the space its cover reports in (= this ZStack's).
                    .coordinateSpace(.named(AlbumScrapbookView.space))
                    .frame(width: w)
                    // Clip the sides only: it scrolls under the status bar and header.
                    .mask { Rectangle().padding(.vertical, -2000) }
                    .offset(x: (1 - f) * w)
                    // Its top edge: a progressive blur under the header, not a hard cut.
                    ProgressiveEdgeBlur(tint: topTint)
                        .frame(width: w, height: geo.safeAreaInsets.top + headerContent + 4)
                        .offset(x: (1 - f) * w, y: -geo.safeAreaInsets.top)
                    }
                    .zIndex(1)
                }
                Group {
                    header(white: false, w: w)
                        .opacity(1 - white * (1 - f))
                    header(white: true, w: w)
                        .opacity(white * (1 - f))
                    // One ← over both twins: crossfading two glass circles
                    // left ~50 % grey ink on half-faded glass mid pull-up.
                    backButton(white: white * (1 - f))
                        .frame(height: 52)
                        .padding(.leading, 8)
                        .padding(.top, 4)
                }
                .zIndex(3)
            }
            .coordinateSpace(.named(Self.pageSpace))
            .contentShape(.rect)
            .gesture(axisPan(width: w, travel: pullTravel))
            .onAppear {
                pageWidth = w
                // Screenshots: `-pageHold "1,0.75"` = a drag from page 2 held at 0.75
                // (the same state a real drag leaves: pageBase set, page moved 1:1).
                if let hold = Self.pageHold {
                    pageBase = hold.from
                    page = hold.to
                }
                AlbumPopGate.set(offFirstPage, by: gateID)
                AlbumPopGate.edgeDrag = { state, dx, vx in
                    switch state {
                    case .began, .changed: pagerDragged(dx, width: pageWidth)
                    case .ended: pagerReleased(vx, cancelled: false, width: pageWidth)
                    default: pagerReleased(0, cancelled: true, width: pageWidth)
                    }
                }
            }
            .onDisappear {
                pageSpring.stop()
                pullSpring.stop()
                AlbumPopGate.release(by: gateID)
            }
            .onChange(of: offFirstPage) { _, off in AlbumPopGate.set(off, by: gateID) }
            .onChange(of: w) { _, now in pageWidth = now }
        }
        .toolbar(.hidden, for: .navigationBar)
        .background(Yoin.surface)
        .sheet(isPresented: $rating) {
            AlbumRateSheet(album: album, scheme: s)
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
        // Cloud backup's View (the toast): this playlist's backup, pushed on this page's stack.
        .navigationDestination(item: backupRequest) { PlaylistBackupView(backupId: $0.backupId) }
        // Now Playing's "Manage in Scrapbook" (ScrapbookFocusRequest): page 2; the
        // scrapbook takes the request there. The tracks may land after the push.
        .onChange(of: ScrapbookFocusRequest.shared.pending, initial: true) { _, _ in followScrapFocus() }
        .onChange(of: album.tracks.map(\.id)) { _, _ in followScrapFocus() }
        .task(id: "\(model.store.generation)|\(routeAlbum.id.rawId)") {
            guard !isMock, playlist == nil else { return }
            await model.store.loadAlbum(routeAlbum.id.rawId)
            // The activity log's visit (Android recordAlbumVisit).
            model.store.recordAlbumVisit(model.store.album(routeAlbum.id.rawId) ?? routeAlbum)
        }
    }

    /// The backup page the toast's View asked for, when it was this playlist's.
    private var backupRequest: Binding<PlaylistBackups.ViewRequest?> {
        let id = playlist?.id.rawId
        return Binding(
            get: { PlaylistBackups.shared.viewRequest.flatMap { $0.playlistId == id ? $0 : nil } },
            set: { if $0 == nil, PlaylistBackups.shared.viewRequest?.playlistId == id { PlaylistBackups.shared.viewRequest = nil } }
        )
    }

    private func followScrapFocus() {
        guard playlist == nil, let request = ScrapbookFocusRequest.shared.pending,
              album.tracks.contains(where: { $0.id == request.songId }), pageTarget < 1 else { return }
        settlePage(to: 1, velocity: 0)
    }

    /// The album's plays in Yoin (Data/History); nil for the mock and playlists.
    private var plays: LibraryStore.AlbumHistory? {
        isMock || playlist != nil ? nil : model.store.albumHistory(routeAlbum)
    }

    /// Last Play's day and time: the mock's hand-set one, else the newest play here.
    private var lastPlay: (day: String, time: String)? {
        isMock ? ("Yesterday", "16:04") : plays?.lastPlay
    }

    private var loadPhase: LibraryStore.Phase {
        guard !isMock else { return .loaded }
        let key = playlist.map { "playlist:\($0.id.rawId)" } ?? "album:\(routeAlbum.id.rawId)"
        return model.store.phase(key)
    }

    /// Server page whose tracks didn't load: a retry under the header.
    private var loadFailed: Bool { loadPhase == .failed }

    /// Android's Loading / Error vs Content: no entries yet and the request
    /// isn't done. The header, cover and backdrop stay (the route knows
    /// them); the body is quiet blocks, or Retry once it failed. A page with
    /// its entries (cached or loaded) is content, also while it revalidates.
    private var bodyPending: Bool {
        guard !isMock else { return false }
        let empty = playlist.map { $0.entries.isEmpty } ?? album.tracks.isEmpty
        return empty && loadPhase != .loaded
    }

    /// Opens the rate + review sheet (server albums; the mock's are hand-set).
    private var canRate: Bool { !isMock && playlist == nil && !bodyPending }

    private func retry() {
        Task {
            if let playlist { await model.store.loadPlaylist(playlist.id.rawId, force: true) }
            else { await model.store.loadAlbum(routeAlbum.id.rawId, force: true) }
        }
    }

    // MARK: Playing

    /// Plays the album from row [index] (the row's own occurrence: an album
    /// can list one track twice, Android's index-qualified rows).
    private func playAlbum(from index: Int) {
        let a = album
        let entries = a.tracks.map { PlaylistEntry(album: a, track: $0) }
        model.play(entries, startAt: entries.indices.contains(index) ? index : 0)
    }

    // MARK: Pieces

    /// The page background's top color.
    private var topTint: Color { app.surfaceContainer.mix(with: s.primary, by: 0.18, in: .perceptual) }

    /// ExpressivePageBackground: [lerp(surfaceContainer, accent, 0.18), background, surfaceContainerLow].
    private var background: some View {
        LinearGradient(colors: [topTint,
                                app.background, app.surfaceContainerLow],
                       startPoint: .top, endPoint: .bottom)
            .ignoresSafeArea()
    }

    /// The ← on its glass circle, legible the whole pull-up: the glass takes
    /// the white twin's tint continuously, the ink flips to white once the
    /// spectrum is mostly under it (an effects-spring color change).
    private func backButton(white mix: CGFloat) -> some View {
        let tinted = playlist != nil
        let dark = colorScheme == .dark
        let base: Color = !tinted ? app.onSurface : dark ? s.primary : s.onPrimaryContainer
        let onSpectrum = mix > 0.5
        let glass: Glass = tinted
            ? .regular.tint(dark ? s.surfaceContainerHighest.opacity(0.7) : s.primaryContainer.opacity(0.6)).interactive()
            : .regular.tint(.white.opacity(0.18 * mix)).interactive()
        return Button { model.back() } label: {
            YoinIcon("back", size: 22)
                .foregroundStyle(onSpectrum ? .white : base)
                .animation(Yoin.effects, value: onSpectrum)
                .frame(width: 40, height: 40)
                .contentShape(.circle)
        }
        .buttonStyle(.plain)
        .glassEffect(glass, in: .circle)
        .accessibilityLabel("Back")
    }

    private func header(white: Bool, w: CGFloat) -> some View {
        // Playlists: the header glass takes the playlist's own container color,
        // so it reads clean over the colorful page instead of a grey film.
        // Light: primaryContainer + its ink; dark: a high container (the
        // expressive dark primaryContainer is mid-tone) + the primary ink.
        let tinted = playlist != nil && !white
        let dark = colorScheme == .dark
        let ink: Color = white ? .white : !tinted ? app.onSurface : dark ? s.primary : s.onPrimaryContainer
        let glass: Glass = white ? .regular.tint(.white.opacity(0.18)).interactive()
            : !tinted ? .regular.interactive()
            : .regular.tint(dark ? s.surfaceContainerHighest.opacity(0.7) : s.primaryContainer.opacity(0.6)).interactive()
        return VStack(spacing: 0) {
            HStack(spacing: Tokens.detail.HEADER_TITLE_GAP.cg) {
                // The ← itself is drawn once above both twins (backButton).
                Color.clear.frame(width: 40, height: 40)
                VStack(alignment: .leading, spacing: 0) {
                    Text(album.title)
                        .lineLimit(1)
                        .yoinRole(Tokens.type.headlineSmall)
                        .foregroundStyle(white ? .white : s.primary)
                    // DetailMetaLine in bodyMedium, groups 12 apart, no separators:
                    // album [artist] [Album] [year]; playlist [owner] [Playlist].
                    // The kind is Medium in primary. The white twin over the bar:
                    // onSurface white, primary white @ 0.88.
                    let plain: Color = white ? .white : app.onSurface
                    let kind: Color = white ? .white.opacity(0.88) : app.primary
                    HStack(spacing: Tokens.metaLine.GROUP_GAP.cg) {
                        if let playlist {
                            Text(playlist.owner).yoinRole(Tokens.type.bodyMedium).foregroundStyle(plain)
                            Text("Playlist").yoinRole(Tokens.type.bodyMedium, weight: .medium).foregroundStyle(kind)
                        } else {
                            Button { model.openArtist(album.artist, id: album.artistId) } label: {
                                Text(album.artist).yoinRole(Tokens.type.bodyMedium).foregroundStyle(plain)
                            }
                            .buttonStyle(.plain)
                            Text("Album").yoinRole(Tokens.type.bodyMedium, weight: .medium).foregroundStyle(kind)
                            Text(String(album.year)).yoinRole(Tokens.type.bodyMedium).foregroundStyle(plain)
                        }
                    }
                    .lineLimit(1)
                }
                // Pulled up, the title gives up the stamp + 12 (+ 4 before a playlist's ⋮).
                .padding(.trailing, (Tokens.detail.ALBUM_STAMP.cg + Tokens.detail.ALBUM_BAR_TITLE_GAP.cg
                                     + (playlist != nil ? Tokens.detail.PLAYLIST_MENU_GAP.cg : 0)) * smooth(p))
                Spacer(minLength: 0)
                let editable = playlist.map { model.store.isReal ? PlaylistActions.shared.canEdit($0, store: model.store) : true } ?? false
                // Any playlist of the account in use can be backed up, followed or not (Android onBackUp),
                // except one whose songs Spotify refused (nothing to back up).
                let canBackUp = playlist != nil && playlist?.tracksUnavailable != true
                    && (!model.store.isReal || PlaylistBackups.shared.source(model.store) != nil)
                if let playlist, editable || canBackUp || model.detailOfflineCollection != nil {
                    // ⋮: Cloud backup (Data/Backup: read whole and saved; the toast's View opens the
                    // backup), then Rename and Delete when the user can write it (Android canWrite;
                    // followed playlists and Apple Music show only Cloud backup).
                    Menu {
                        if canBackUp {
                            Button {
                                if model.store.isReal { PlaylistBackups.shared.backUp(playlist, library: model.store) }
                            } label: {
                                Label { Text("Cloud backup") } icon: { Image("yoin.cloud") }
                            }
                            .disabled(PlaylistBackups.shared.backingUp.contains(playlist.id.rawId))
                        }
                        if let c = model.detailOfflineCollection { OfflineMenuButton(collection: c) }
                        // A followed playlist can still be downloaded, not renamed or deleted.
                        if editable {
                            Button { PlaylistActions.shared.renaming = playlist } label: {
                                Label { Text("Rename") } icon: { Image("yoin.edit") }
                            }
                            Button(role: .destructive) { PlaylistActions.shared.deleting = playlist } label: {
                                Label { Text("Delete") } icon: { Image("yoin.delete") }
                            }
                        }
                    } label: {
                        YoinIcon("more_vertical", size: 22)
                            .foregroundStyle(ink)
                            .frame(width: 40, height: 40)
                            .contentShape(.circle)
                    }
                    .buttonStyle(.plain)
                    .glassEffect(glass, in: .circle)
                    .frame(width: 48, height: 48)
                    .accessibilityLabel("More actions")
                    .padding(.trailing, -12)
                }
            }
            .frame(height: 52)
            if playlist == nil {
            HStack(spacing: 0) {
                ForEach(0..<2, id: \.self) { i in
                    // Active by distance from the pager fraction.
                    let active = 1 - min(1, abs(pageFrac - CGFloat(i)))
                    let on: Color = white ? .white : s.secondary
                    let off: Color = white ? .white.opacity(0.4) : app.onSurfaceVariant.opacity(0.3)
                    Button { settlePage(to: CGFloat(i), velocity: 0) } label: {
                        Circle()
                            .fill(off.mix(with: on, by: active, in: .perceptual))
                            .frame(width: 8, height: 8)
                            .frame(width: 24, height: 20)
                            .contentShape(.rect)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(i == 0 ? "Overview" : "Scrapbook")
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.top, 5)
            }
        }
        .padding(.leading, 8)
        .padding(.trailing, 16)
        .padding(.top, 4)
        .padding(.bottom, 6)
        .frame(width: w)
    }

    /// The real Yoin mark's arms 0 and 2 behind the cover (AlbumSpectrumBar
    /// drawButterfly, Compact pose): box = (0, header bottom, W, band),
    /// scale 1.8 about its centre, translationY −0.1 × band, squash 1.8 about
    /// the hub; arm 0 in the cover's primary, arm 2 in its secondary, centre
    /// lines in surface @ 0.65; clipped above at the header.
    private func butterfly(w: CGFloat, side: CGFloat, band: CGFloat) -> some View {
        YoinArmsCanvas(arms: [(0, s.primary), (2, s.secondary)],
                       lineColor: app.surface.opacity(0.65),
                       scale: 1.8, translationY: -0.1 * band, squashX: 1.8)
            .frame(width: w, height: band)
            .offset(y: headerContent)
            .frame(width: w, height: headerContent + band * 1.8, alignment: .topLeading)
            .mask(alignment: .top) {
                Rectangle().padding(.top, headerContent)
            }
            .allowsHitTesting(false)
    }

    /// Hero cover ↔ 48 pt stamp at the end of the title row, and, with the
    /// hero at rest, hero cover → the scrapbook's cover on the swipe to page 2
    /// (Android AlbumFlyingCover + AlbumPageFlight): toward the page's cover
    /// as much as the hero is at rest (toPage = f × (1 − p)), taking its tilt;
    /// pulled up into the bar, the stamp slides away with page 0.
    private func cover(w: CGFloat, side: CGFloat, band: CGFloat) -> some View {
        let e = smooth(p)
        let f = pageFrac
        let heroRect = CGRect(x: (w - side) / 2, y: headerContent + 28, width: side, height: side)
        // Playlists: the stamp sits 4 pt before the ⋮ (end inset 40 + 16 page).
        let stamp = Tokens.detail.ALBUM_STAMP.cg
        let stampEnd = Tokens.detail.ALBUM_STAMP_END_INSET.cg + (playlist != nil ? Tokens.detail.PLAYLIST_MENU_RESERVE.cg : 0)
        let stampRect = CGRect(x: w - stampEnd - stamp, y: 4 + 2, width: stamp, height: stamp)
        let r = CGRect(x: lerp(heroRect.minX, stampRect.minX, e), y: lerp(heroRect.minY, stampRect.minY, e),
                       width: lerp(side, stamp, e), height: lerp(side, stamp, e))
        let target = playlist == nil && !reduceMotion ? scrapCover : nil
        let toPage = target == nil ? 0 : f * (1 - p)
        let flown = if let target, toPage > 0 {
            lerpRect(r.offsetBy(dx: -f * w * p, dy: 0), target, toPage)
        } else {
            r.offsetBy(dx: -f * w, dy: 0)
        }
        return Button {
            if e > 0.5 {
                AlbumPullHaptics.armed(expanding: false)
                settlePull(to: 0, velocity: 0)
            }
        } label: {
            CoverArt(palette: album.palette, cornerRadius: 0, seed: album.seed)
                .frame(width: flown.width, height: flown.height)
                // Hero 8 → Cover 4 (Android restingCorner / stampCorner).
                .clipShape(.rect(cornerRadius: lerp(Tokens.shape.HERO.cg, Tokens.shape.COVER.cg, e), style: .continuous))
                // World: the cover is a physical object (contact shadow).
                .labCoverShadow()
        }
        .buttonStyle(.plain)
        .allowsHitTesting(e > 0.5 && f < 0.5)
        .accessibilityLabel("Show cover")
        .rotationEffect(.degrees(scrapCoverTilt * Double(toPage)))
        // Landed: the scrapbook's own cover (tilted, under its emblem) takes
        // over, on the same threshold it waits on, so exactly one is showing.
        .opacity(coverFlies && f >= 0.999 ? 0 : 1)
        .offset(x: flown.minX, y: flown.minY)
    }

    // MARK: Top bar variants (Glass Lab `detailTopBar`)

    /// Liquid Glass over the header once the list is pulled up, lightly
    /// tinted with the cover's container color; slides away with page 0.
    private func glassBar(w: CGFloat, top: CGFloat) -> some View {
        Color.clear
            .frame(width: w, height: top + headerContent)
            .glassEffect(.regular.tint(s.surfaceContainer.opacity(0.35)),
                         in: UnevenRoundedRectangle(bottomLeadingRadius: 28, bottomTrailingRadius: 28,
                                                    style: .continuous))
            .opacity(smooth(p, 0.3, 0.85))
            .offset(y: -top)
            .allowsHitTesting(false)
    }

    private func hero(w: CGFloat, side: CGFloat, band: CGFloat) -> some View {
        let bun = self.bun
        return VStack(alignment: .leading, spacing: 0) {
            Spacer().frame(height: headerContent + band + 8)
            if bodyPending {
                pendingBody(side: side)
                    .transition(.opacity)
            } else if let playlist {
                playlistMeta(playlist).frame(width: side).frame(maxWidth: .infinity)
                Spacer().frame(height: 28)
                flowingTitles
                    .frame(maxWidth: .infinity, alignment: .leading)
            } else {
            // Emblem + comment, as wide as the cover.
            HStack(spacing: 20) {
                HStack(spacing: 10) {
                    // Android onTapBun: the emblem opens the rate sheet too.
                    Button { rating = true } label: {
                        ScoreEmblem(score: bun.score, kind: bun.kind, rated: bun.rated, palette: album.palette, size: 64,
                                    neutrals: app)
                    }
                    .buttonStyle(PressScale())
                    .disabled(!canRate)
                    .accessibilityLabel("Rate")
                    if bun.kind == .average { ratedCoverage }
                }
                comment
            }
            .frame(width: side)
            .frame(maxWidth: .infinity)
            Spacer().frame(height: 28)
            HStack(spacing: 12) {
                countLabel
                // AlbumHeroLastPlay: labelMedium Medium tabular, onSurface;
                // only once the album has played here.
                if let lastPlay {
                    Text("\(lastPlay.day) \(lastPlay.time)")
                        .monospacedDigit()
                        .yoinRole(Tokens.type.labelMedium, weight: .medium)
                        .foregroundStyle(app.onSurface)
                }
                if loadFailed { retryButton }
            }
            Spacer().frame(height: 6)
            flowingTitles
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(.horizontal, 16)
        .frame(width: w, alignment: .topLeading)
        // Loading → content (or → Retry) is an alpha change: an effects spring.
        .animation(Yoin.effects, value: bodyPending)
        .animation(Yoin.effects, value: loadFailed)
    }

    /// The hero's body while the page loads (Android AlbumLoadingState /
    /// DetailErrorState, in iOS's quiet-block language, LoadStates.swift):
    /// the emblem's round, the stat line and the title paragraph as blocks,
    /// or one Retry once the request failed. The cover above already stands.
    @ViewBuilder
    private func pendingBody(side: CGFloat) -> some View {
        if loadFailed {
            RetryRow(scheme: app, action: retry)
        } else {
            let fill = app.surfaceContainerHigh
            VStack(alignment: .leading, spacing: 0) {
                if playlist == nil {
                    HStack(spacing: 20) {
                        SkeletonBlock(color: fill, cornerRadius: 32).frame(width: 64, height: 64)
                        VStack(alignment: .leading, spacing: 8) {
                            SkeletonBlock(color: fill, cornerRadius: 4).frame(height: 12)
                            SkeletonBlock(color: fill, cornerRadius: 4).frame(width: side * 0.4, height: 12)
                        }
                    }
                    .frame(width: side)
                    .frame(maxWidth: .infinity)
                    Spacer().frame(height: 28)
                }
                SkeletonBlock(color: fill, cornerRadius: 4).frame(width: 120, height: 12)
                Spacer().frame(height: 14)
                VStack(alignment: .leading, spacing: 12) {
                    ForEach([1.0, 0.86, 0.94, 0.6], id: \.self) { k in
                        SkeletonBlock(color: fill, cornerRadius: 6).frame(height: 24)
                            .padding(.trailing, (1 - k) * side)
                    }
                }
            }
        }
    }

    /// AlbumTrackCountLabel: a MetaLine in labelMedium of Stat groups 12
    /// apart ("12 tracks", "1 h", "12 min"); each number Medium tabular, its
    /// unit Regular at 60 %, 4 apart. Hours and minutes are separate groups.
    private var countLabel: some View {
        let count = playlist?.entries.count ?? album.trackCount
        let secs = playlist.map { $0.entries.map(\.track.durationSec).reduce(0, +) } ?? album.totalSec
        let mins = secs / 60
        var groups: [(String, String)] = [("\(count)", count == 1 ? "track" : "tracks")]
        if mins >= 60 {
            groups.append(("\(mins / 60)", "h"))
            if mins % 60 > 0 { groups.append(("\(mins % 60)", "min")) }
        } else if mins > 0 {
            groups.append(("\(mins)", "min"))
        }
        return HStack(spacing: Tokens.metaLine.GROUP_GAP.cg) {
            ForEach(groups, id: \.1) { value, unit in
                HStack(spacing: Tokens.metaLine.INNER_GAP.cg) {
                    Text(value).monospacedDigit().yoinRole(Tokens.type.labelMedium, weight: .medium)
                    Text(unit).yoinRole(Tokens.type.labelMedium, weight: .regular).opacity(Tokens.metaLine.MUTED_ALPHA)
                }
            }
        }
        .foregroundStyle(app.onSurface)
    }

    /// The Bun from the local stores (album rating > local track average > N/A), as page 2 reads it.
    private var bun: AlbumBun { AlbumBun.of(album) }

    /// AlbumRatedCoverage: "2/4 rated" in labelSmall tabular, onSurfaceVariant.
    private var ratedCoverage: some View {
        Text("\(bun.ratedCount)/\(album.tracks.count) rated")
            .monospacedDigit()
            .lineLimit(1)
            .yoinRole(Tokens.type.labelSmall)
            .foregroundStyle(app.onSurfaceVariant)
    }

    /// AlbumHeroComment: written, bodyMedium (3 lines) over its date in
    /// labelMedium onSurfaceVariant, 4 apart; not written, a 40 tonal pen.
    /// Tapping either opens the rate + review sheet (a server album).
    @ViewBuilder
    private var comment: some View {
        if let comment = review {
            Button { rating = true } label: {
                VStack(alignment: .leading, spacing: 4) {
                    Text(comment.text).lineLimit(3)
                        .multilineTextAlignment(.leading)
                        .yoinRole(Tokens.type.bodyMedium)
                        .foregroundStyle(app.onSurface)
                    // When it was written (owner 2026-10-05).
                    if let at = comment.at {
                        Text(at)
                            .yoinRole(Tokens.type.labelMedium)
                            .foregroundStyle(app.onSurfaceVariant)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .disabled(!canRate)
            .accessibilityLabel("Edit comment")
        } else {
            Button { rating = true } label: {
                YoinIcon("edit", size: 18)
                    .foregroundStyle(app.onSecondaryContainer)
                    .frame(width: 40, height: 40)
                    .background(app.secondaryContainer, in: .circle)
            }
            .buttonStyle(PressScale())
            .disabled(!canRate)
            .accessibilityLabel("Write a comment")
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    /// The track titles as one paragraph; a title plays from that song while
    /// the hero is up (Android: links only below half the pull).
    private var flowingTitles: some View {
        FlowingTrackTitles(titles: album.tracks.map(\.title), primary: app.onSurface, variant: app.onSurfaceVariant,
                           onPick: p < 0.5 ? { i in
                               if let playlist { model.play(playlist.entries, startAt: i) } else { playAlbum(from: i) }
                           } : nil)
    }

    /// The listener's review and when it was written (AlbumMemoryStore, as
    /// page 2 reads it: written here or restored by sync); the mock's stand-in.
    private var review: (text: String, at: String?)? {
        if isMock {
            return album.rating != nil ? ("A record for the walk home, warm even on the cold tracks", "Yesterday") : nil
        }
        guard playlist == nil,
              let record = AlbumMemoryStore.shared.album(provider: album.id.provider, albumId: album.id.rawId),
              let text = record.review?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty else { return nil }
        let at = record.reviewUpdatedAt.map {
            HomeHistory.relativeDay($0, now: Int64(Date().timeIntervalSince1970 * 1000))
        }
        return (text, at)
    }

    private var retryButton: some View {
        Button { retry() } label: {
            Text("Retry")
                .yoinRole(Tokens.type.labelLarge)
                .foregroundStyle(app.primary)
                .padding(.horizontal, 12)
                .frame(minHeight: 32)
                .contentShape(.capsule)
        }
        .buttonStyle(PressScale())
    }

    // MARK: Track list (pulled up)

    private func trackList(w: CGFloat) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                countLabel
                    .padding(.leading, 8)
                    .padding(.top, 2)
                    .padding(.bottom, 10)
                    .modifier(bandBlur)
                if bodyPending {
                    Group {
                        if loadFailed { RetryRow(scheme: app, action: retry) } else { RowSkeleton(scheme: app) }
                    }
                    .transition(.opacity)
                } else if loadFailed { retryButton.padding(.leading, 4) }
                if bodyPending {
                    EmptyView()
                } else if let playlist {
                    ForEach(Array(playlist.entries.enumerated()), id: \.offset) { i, e in
                        playlistRow(e)
                            .modifier(bandBlur)
                            .trackRow(for: e, context: .playlist(playlist.id.rawId, index: i)) {
                                model.play(playlist.entries, startAt: i)
                            }
                    }
                    playlistMeta(playlist)
                        .padding(.horizontal, 8)
                        .padding(.top, 28)
                        .opacity(max(0, (smooth(p) - 0.6) / 0.4))
                } else {
                // Index-qualified (Android "${song.id}#$index"): a provider can
                // list the same track twice on one album.
                let tracks = album.tracks
                let multiDisc = Set(tracks.compactMap(\.disc)).count > 1
                ForEach(Array(tracks.enumerated()), id: \.offset) { i, t in
                    // A multi-disc album: each disc opens with its number.
                    if multiDisc, let disc = t.disc, i == 0 || tracks[i - 1].disc != disc {
                        Text("Disc \(disc)")
                            .yoinRole(Tokens.type.labelLarge)
                            .foregroundStyle(app.onSurfaceVariant)
                            .accessibilityAddTraits(.isHeader)
                            .padding(.leading, 8)
                            .padding(.top, i == 0 ? 0 : 16)
                            .padding(.bottom, 6)
                            .modifier(bandBlur)
                    }
                    trackRow(t).modifier(bandBlur)
                        .trackRow(for: PlaylistEntry(album: album, track: t), context: .album(viewInScrapbook: {
                            settlePage(to: 1, velocity: 0)
                        })) { playAlbum(from: i) }
                }
                linerNotes
                    .padding(.horizontal, 8)
                    .padding(.top, 28)
                    .opacity(max(0, (smooth(p) - 0.6) / 0.4))
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .animation(Yoin.effects, value: bodyPending)
        }
        .scrollPosition($listPosition)
        #if DEBUG
        // Recordings: `-listAutoScroll YES` sweeps the pulled-up list up and down.
        .task {
            guard UserDefaults.standard.bool(forKey: "listAutoScroll") else { return }
            try? await Task.sleep(for: .seconds(2))
            var down = true
            while !Task.isCancelled {
                withAnimation(.easeInOut(duration: 2.4)) { listPosition.scrollTo(y: down ? 420 : 0) }
                down.toggle()
                try? await Task.sleep(for: .seconds(2.8))
            }
        }
        #endif
        .contentMargins(.bottom, 112, for: .scrollContent)
        // The header's room as a bar, so the blur variant's soft scroll edge
        // runs under it (spectrum and glass cover it themselves).
        .scrollEdgeEffectStyle(.soft, for: .top)
        .scrollEdgeEffectHidden(topBar != 2 && topBar != 4, for: .top)
        .safeAreaBar(edge: .top) { AlbumScrapbookView.edgeBar(height: headerContent + bandFade) }
        .onScrollGeometryChange(for: Bool.self) { $0.contentOffset.y + $0.contentInsets.top <= 1 } action: { _, top in
            listAtTop = top
        }
        .scrollDisabled(p < 1)
        .frame(width: w)
    }

    private func trackRow(_ t: Track) -> some View {
        let playing = model.playback.track.id == t.id
        return HStack(spacing: 12) {
            Group {
                if playing {
                    YoinIcon("equalizer", size: 16).foregroundStyle(s.primary)
                } else {
                    Text("\(t.number)").monospacedDigit()
                        .yoinRole(Tokens.type.labelMedium)
                        .foregroundStyle(app.onSurfaceVariant)
                }
            }
            .frame(minWidth: 18, alignment: .trailing)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(t.title)
                        .lineLimit(1)
                        .yoinRole(Tokens.type.titleMedium)
                        .foregroundStyle(playing ? s.primary : app.onSurface)
                    // The song has notes (they live on the scrapbook page).
                    if hasNote(t) {
                        YoinIcon("note", size: 12)
                            .foregroundStyle(s.primary.opacity(0.8))
                            .accessibilityLabel("Has note")
                            .accessibilityHidden(false)
                    }
                }
                // The performer when it isn't the album artist again
                // (compilations, features); else one 56 pt line.
                if let artist = t.artist?.trimmingCharacters(in: .whitespaces), !artist.isEmpty, artist != album.artist {
                    Text(artist)
                        .lineLimit(1)
                        .yoinRole(Tokens.type.bodySmall)
                        .foregroundStyle(app.onSurfaceVariant)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Text(formatTime(t.durationSec))
                .monospacedDigit()
                .yoinRole(Tokens.type.labelLarge)
                .foregroundStyle(app.onSurfaceVariant)
            if t.id.provider == AppleMusicMapping.provider {
                libraryButton(t)
            } else {
                heartButton(t)
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 10)
        .frame(minHeight: 56)
        .contentShape(.rect(cornerRadius: Tokens.shape.LIST_ROW.cg))
    }

    private func hasNote(_ t: Track) -> Bool {
        !isMock && !NotesStore.shared.notes(provider: t.id.provider, trackId: t.id.rawId).isEmpty
    }

    /// Apple Music has no heart: its row button is library membership
    /// (Android TrackLibraryButton): + adds, ✓ once added (MusicKit has no remove).
    private func libraryButton(_ t: Track) -> some View {
        let hearts = FavoritesStore.shared
        let added = AppleMusicMapping.isLibraryId(t.id.rawId)
            || hearts.isFavorite(provider: t.id.provider, songId: t.id.rawId, known: t.starred ? true : nil)
        return Button {
            withAnimation(Yoin.effects) {
                hearts.toggle(provider: t.id.provider, songId: t.id.rawId, known: added,
                              client: nil, apple: model.store.apple)
            }
        } label: {
            YoinIcon(added ? "check" : "add", size: 20)
                .foregroundStyle(added ? s.primary : app.onSurfaceVariant)
                .contentTransition(.opacity)
                .frame(width: 36, height: 36)
                .contentShape(.circle)
        }
        .buttonStyle(PressScale())
        .disabled(added)
        .accessibilityLabel(added ? "In library" : "Add to library")
    }

    /// The heart ring (Android AlbumCircleToggle).
    private func heartButton(_ t: Track) -> some View {
        let liked = model.store.isStarred(t)
        return Button { withAnimation(Yoin.effects) { model.store.toggleStar(t) } } label: {
            YoinIcon(liked ? "favorite_filled" : "favorite", size: 16)
                .foregroundStyle(liked ? s.onPrimary : app.onSurfaceVariant)
                .frame(width: 28, height: 28)
                .background {
                    if liked { Circle().fill(s.primary) }
                    else { Circle().strokeBorder(app.outline.opacity(0.5), lineWidth: 1.5) }
                }
                .frame(width: 36, height: 36)
                .contentShape(.circle)
        }
        .buttonStyle(PressScale())
        .accessibilityLabel(liked ? "Remove from favorites" : "Add to favorites")
    }

    // MARK: Playlist pieces

    /// Android DetailBackdrops stacked V: arm 1 (the upper V) three times,
    /// back to front tertiary / secondary / primary, scale = W × 1.3 / 674
    /// about the arm centre (505, 350), dy = side × (−0.06 + 0.14 i).
    private func stackedV(w: CGFloat, side: CGFloat, band: CGFloat) -> some View {
        Canvas { gc, size in
            let k = size.width * 1.3 / 674
            let colors = [s.tertiary, s.secondary, s.primary]
            for i in 0..<3 {
                var g = gc
                g.translateBy(x: size.width / 2, y: band / 2 + side * (-0.06 + 0.14 * CGFloat(i)))
                g.scaleBy(x: k, y: k)
                g.translateBy(x: -505, y: -350)
                let arm = YoinMarkPaths.arms[1]
                g.fill(arm.fill, with: .color(colors[i]))
                g.fill(arm.line, with: .color(app.surface.opacity(0.65)))
            }
        }
        // Laid out on the band, drawn past it: the front V's lip dips below
        // the band, as on Android (only the Box under the header clips).
        .frame(width: w, height: band * 1.6)
        .offset(y: headerContent)
        .frame(width: w, height: headerContent + band * 1.6, alignment: .topLeading)
        .mask(alignment: .top) { Rectangle().padding(.top, headerContent) }
        .allowsHitTesting(false)
    }

    /// PlaylistHeroMeta: Length (tracks bodyLarge, duration bodyMedium
    /// onSurfaceVariant) | Owner (bodyLarge, + Public / Private bodyMedium),
    /// 4 apart; then the description (bodyMedium, 3 lines) 6 under its label.
    /// Labels: AlbumSectionLabel (labelLarge Medium underlined).
    private func playlistMeta(_ pl: PlaylistInfo) -> some View {
        let mins = pl.entries.map(\.track.durationSec).reduce(0, +) / 60
        return VStack(alignment: .leading, spacing: 14) {
            if pl.tracksUnavailable {
                // Android detail_playlist_tracks_unavailable: why the list is empty (Codex N18).
                Text("Spotify only shows this app the songs of playlists you made or collaborate on. To play this one, open it in Spotify.")
                    .yoinRole(Tokens.type.bodyMedium)
                    .foregroundStyle(app.onSurfaceVariant)
            }
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 4) {
                    sectionLabel("Length")
                    // The service's count when the songs weren't handed over (Android songCount), and
                    // "—" for a length nobody knows rather than "0 min".
                    Text("\(pl.tracksUnavailable ? pl.songCount ?? 0 : pl.entries.count) tracks").monospacedDigit()
                        .yoinRole(Tokens.type.bodyLarge).foregroundStyle(app.onSurface)
                    Text(pl.entries.isEmpty ? "—" : mins >= 60 ? "\(mins / 60) h \(mins % 60) min" : "\(mins) min").monospacedDigit()
                        .yoinRole(Tokens.type.bodyMedium).foregroundStyle(app.onSurfaceVariant)
                }
                Spacer()
                VStack(alignment: .leading, spacing: 4) {
                    sectionLabel("Owner")
                    Text(pl.owner).lineLimit(1)
                        .yoinRole(Tokens.type.bodyLarge).foregroundStyle(app.onSurface)
                    if let isPublic = pl.isPublic {
                        Text(isPublic ? "Public" : "Private")
                            .yoinRole(Tokens.type.bodyMedium).foregroundStyle(app.onSurfaceVariant)
                    }
                }
            }
            if let d = pl.description {
                VStack(alignment: .leading, spacing: 6) {
                    sectionLabel("Description")
                    Text(d).lineLimit(3)
                        .lineSpacing(YoinType.lineSpacing(Tokens.type.bodyMedium))
                        .font(.gsf(Tokens.type.bodyMedium.size.cg))
                        .foregroundStyle(app.onSurface)
                }
            }
        }
    }

    /// AlbumSectionLabel: labelLarge Medium, underlined, onSurface.
    private func sectionLabel(_ t: String) -> some View {
        Text(t).underline().yoinRole(Tokens.type.labelLarge).foregroundStyle(app.onSurface)
    }

    /// PlaylistTrackRow: 44 Thumb (corner 4), 12 gap; titleMedium onSurface
    /// over PlaylistTrackCredit (a bodySmall MetaLine: artist, then the album
    /// muted to 60 %, 12 apart, no separator), 2 apart; the duration in
    /// labelLarge tabular. Padding 8 × 8, no number, no heart.
    private func playlistRow(_ e: PlaylistEntry) -> some View {
        HStack(spacing: 12) {
            CoverArt(palette: e.album.palette, cornerRadius: 0, seed: e.album.seed)
                .frame(width: 44, height: 44)
                .clipShape(.rect(cornerRadius: Tokens.shape.THUMB.cg, style: .continuous))
            VStack(alignment: .leading, spacing: 2) {
                Text(e.track.title).lineLimit(1)
                    .yoinRole(Tokens.type.titleMedium)
                    .foregroundStyle(app.onSurface)
                LibraryMetaLine(parts: [(e.album.artist, false), (e.album.title, true)], scheme: app)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Text(formatTime(e.track.durationSec))
                .monospacedDigit()
                .yoinRole(Tokens.type.labelLarge)
                .foregroundStyle(app.onSurfaceVariant)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 8)
        .contentShape(.rect(cornerRadius: Tokens.shape.LIST_ROW.cg))
    }

    /// The list's liner notes (AlbumHeroMetaBlocks, default arrangement):
    /// Last Play (label, 4, day bodyLarge, 4, time bodyMedium tabular) and the
    /// emblem (+ "X/N rated") across one row, centred; the comment 14 below.
    private var linerNotes: some View {
        let bun = self.bun
        return VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .center) {
                if isMock || plays != nil {
                    VStack(alignment: .leading, spacing: 0) {
                        sectionLabel("Last Play").padding(.bottom, 4)
                        Text(lastPlay?.day ?? "Never").yoinRole(Tokens.type.bodyLarge).foregroundStyle(app.onSurface)
                        if let time = lastPlay?.time {
                            Text(time).monospacedDigit()
                                .yoinRole(Tokens.type.bodyMedium).foregroundStyle(app.onSurfaceVariant)
                                .padding(.top, 4)
                        }
                    }
                }
                Spacer()
                HStack(spacing: 10) {
                    Button { rating = true } label: {
                        ScoreEmblem(score: bun.score, kind: bun.kind, rated: bun.rated, palette: album.palette, size: 64,
                                    neutrals: app)
                    }
                    .buttonStyle(PressScale())
                    .disabled(!canRate)
                    .accessibilityLabel("Rate")
                    if bun.kind == .average { ratedCoverage }
                }
            }
            comment
        }
    }

    // MARK: Spectrum bar

    /// AlbumSpectrumBar drawSpectrum: each of the 28 strips starts as a slice
    /// of the cover and flies to its slot in the header bar (the shared
    /// spectrum's assignment: strips of one color land side by side)
    /// (q = smoothstep(0.06 + lag, 0.6 + lag, p), lag = 0.22 × slot / 28); the
    /// cover slice fades out as the flat locked color fades in over
    /// smoothstep(0, 0.55, q).
    private func spectrum(w: CGFloat, side: CGFloat, top: CGFloat) -> some View {
        let strips = AlbumSpectrum.strips(for: album)
        let n = AlbumSpectrum.strips
        let slice = side / CGFloat(n)
        let heroX = (w - side) / 2, heroY = headerContent + 28
        let barH = stripBar ? top + stripExtend : top + headerContent + bandFade
        return ZStack(alignment: .topLeading) {
            ForEach(0..<n, id: \.self) { i in
                let lag = 0.22 * CGFloat(strips[i].slot) / CGFloat(n)
                let q = smooth(p, 0.06 + lag, 0.6 + lag)
                let flat = smooth(q, 0, 0.55)
                let x = lerp(heroX + CGFloat(i) * slice, CGFloat(strips[i].slot) * w / CGFloat(n), q)
                let y = lerp(heroY, -top, q)
                let sw = lerp(slice, w / CGFloat(n), q) + 1.5
                let sh = lerp(side, barH, q)
                ZStack(alignment: .topLeading) {
                    CoverArt(palette: album.palette, cornerRadius: 0, seed: album.seed)
                        .frame(width: side, height: side)
                        .offset(x: -CGFloat(i) * slice)
                        .frame(width: slice, height: side, alignment: .topLeading)
                        .clipped()
                        .scaleEffect(x: sw / slice, y: sh / side, anchor: .topLeading)
                        .frame(width: sw, height: sh, alignment: .topLeading)
                        .opacity(1 - flat)
                    Rectangle()
                        .fill(strips[i].color)
                        .frame(width: sw, height: sh)
                        .opacity(flat)
                }
                .frame(width: sw, height: sh, alignment: .topLeading)
                .clipped()
                .offset(x: x, y: y)
            }
        }
        .frame(width: w, alignment: .topLeading)
        .mask(alignment: .topLeading) { bandMask(w: w, top: top) }
        .opacity(p > 0.001 ? 1 : 0)
        .allowsHitTesting(false)
    }

    /// The spectrum's soft edge: solid down to the header's edge, then the
    /// band's colors fade out over `bandFade` (the list's blur reads the same
    /// curve, `albumBandBlur`); it comes in as the list settles, so the strips
    /// in flight are never cut.
    @ViewBuilder
    private func bandMask(w: CGFloat, top: CGFloat) -> some View {
        if stripBar {
            // Solid through the status bar, then a short fade (the strips in
            // flight are taller; only the part past the strip's end fades).
            let fade = min(stripExtend + 6, 24)
            let k = smooth(p, 0.55, 0.95)
            VStack(spacing: 0) {
                Rectangle().frame(height: max(0, top + stripExtend - fade + 6))
                LinearGradient(stops: (0...8).map { i in
                    let t = CGFloat(i) / 8
                    let ease = t * t * (3 - 2 * t)
                    return .init(color: .black.opacity(1 - ease * k), location: t)
                }, startPoint: .top, endPoint: .bottom)
                .frame(height: fade)
                Rectangle().opacity(1 - k).frame(height: 4000)
            }
            .frame(width: w + 40)
            .offset(x: -20, y: -top)
        } else if bandFade > 0 {
            let k = bandSoft
            VStack(spacing: 0) {
                Rectangle().frame(height: top + headerContent)
                LinearGradient(stops: (0...8).map { i in
                    let t = CGFloat(i) / 8
                    let ease = t * t * (3 - 2 * t)
                    return .init(color: .black.opacity(1 - ease * k), location: t)
                }, startPoint: .top, endPoint: .bottom)
                .frame(height: bandFade)
                Rectangle().opacity(1 - k).frame(height: 4000)
            }
            .frame(width: w + 40)
            .offset(x: -20, y: -top)
        } else {
            Rectangle().frame(width: w + 40, height: 8000).offset(x: -20, y: -4000)
        }
    }

    /// The soft edge's blur on one piece of the list: only a piece under the
    /// fade runs the shader (one or two rows at a time), and the curve is the
    /// band mask's.
    private var bandBlur: AlbumBandBlur {
        AlbumBandBlur(zoneTop: headerContent, zoneHeight: bandFade, radius: Self.bandBlurRadius * bandSoft)
    }

    // MARK: Pager and pull-up (one axis-locked pan)

    /// The page's one drag (AlbumAxisPan): its axis is decided from the first
    /// movement, and only that axis moves for the whole drag.
    /// - Horizontal: the pager (albums only), following the finger 1:1; not a
    ///   right swipe on page 0 (that is the edge swipe's, which always wins
    ///   there; off page 0 the edge swipe is the pager's, see AlbumPopGate).
    /// - Vertical, on page 0: the pull-up (DetailPullUpReshape); pulled up, only
    ///   a pull-down from the list's top. Anything else is the scroll views'.
    /// Pull-up distance (51, 2026-11-06: the old 0.55 × screen height, ≈ 500 pt
    /// on an iPhone Air, with the band forming only past half of it, was too
    /// far). The finger moves the progress over `pullTravel` points and a
    /// release past `pullSnap` of it (counted from where the drag started)
    /// settles the other way. Both are Glass Lab sliders.
    private var pullTravel: CGFloat {
        let v = GlassLab.shared.extras["albumPullTravel"] ?? 0
        return v > 0 ? v : 320
    }
    private var pullSnap: CGFloat {
        let v = GlassLab.shared.extras["albumPullSnap"] ?? 0
        return v > 0 ? v : 0.3
    }

    private func axisPan(width: CGFloat, travel: CGFloat) -> AlbumAxisPan {
        AlbumAxisPan(
            claims: { axis, d in
                switch axis {
                case .horizontal:
                    guard playlist == nil else { return false }
                    return !(page <= 0.001 && d.x > 0)
                case .vertical:
                    guard page <= 0.001 else { return false }
                    return p < 0.999 || (d.y > 0 && listAtTop)
                }
            },
            changed: { axis, t in
                switch axis {
                case .horizontal:
                    pagerDragged(t.x, width: width)
                case .vertical:
                    if dragBase == nil {
                        pullSpring.stop()
                        pullLanding = false
                        dragBase = p
                        pullArmed = nil
                        AlbumPullHaptics.prepare()
                    }
                    p = min(1, max(0, (dragBase ?? 0) - t.y / travel))
                    // One beat each time the drag passes the snap point.
                    let armed = pullWouldExpand
                    if let was = pullArmed, was != armed { AlbumPullHaptics.armed(expanding: armed) }
                    pullArmed = armed
                }
            },
            ended: { axis, v, cancelled in
                switch axis {
                case .horizontal:
                    pagerReleased(v.x, cancelled: cancelled, width: width)
                case .vertical:
                    guard let base = dragBase else { return }
                    let positional = pullWouldExpand
                    dragBase = nil
                    let velocity = cancelled ? 0 : -v.y / travel
                    let expand = velocity >= 1.6 ? true : velocity <= -1.6 ? false : positional
                    // A fling that changes state without crossing the snap
                    // point gets its beat on release.
                    if expand != (base > 0.5) && pullArmed != expand { AlbumPullHaptics.armed(expanding: expand) }
                    pullArmed = nil
                    settlePull(to: expand ? 1 : 0, velocity: velocity)
                }
            })
    }

    /// The pager follows the finger 1:1 (from the page pan or the edge swipe).
    private func pagerDragged(_ dx: CGFloat, width: CGFloat) {
        if pageBase == nil {
            pageSpring.stop()
            pageMoving = false
            pageBase = page
        }
        page = min(1, max(0, (pageBase ?? 0) - dx / max(width, 1)))
    }

    private func pagerReleased(_ vx: CGFloat, cancelled: Bool, width: CGFloat) {
        guard pageBase != nil else { return }
        pageBase = nil
        // Pages per second; a fling (Compose pager: > 400 dp/s) turns the
        // page its way, a slow release snaps to the nearer one.
        let velocity = cancelled ? 0 : -vx / max(width, 1)
        let target: CGFloat = abs(vx) > 400 && !cancelled ? (vx < 0 ? 1 : 0) : (page > 0.5 ? 1 : 0)
        settlePage(to: target, velocity: velocity)
    }

    /// The pager's release (and the page dots): Yoin's spatial spring from
    /// here, the drag's velocity carried in, on the same progress the drag moved.
    private func settlePage(to target: CGFloat, velocity: CGFloat) {
        pageMoving = true
        pageTarget = target
        pageSpring.run(from: page, to: target, velocity: velocity, apply: { v in
            page = min(1, max(0, v))
        }, completion: { pageMoving = false })
    }

    /// Where a release here would settle, by position alone: past `pullSnap`
    /// of the travel from where the drag started.
    private var pullWouldExpand: Bool {
        (dragBase ?? 0) > 0.5 ? p > 1 - pullSnap : p > pullSnap
    }

    private func settlePull(to target: CGFloat, velocity: CGFloat) {
        // The band lands once (the spring's first touch of the top, not every
        // frame of its settle), and only when it is forming, not already formed.
        pullLanding = target >= 1 && p < 0.98
        pullSpring.run(from: p, to: target, velocity: velocity) { v in
            p = min(1, max(0, v))
            if pullLanding && v >= 0.995 {
                pullLanding = false
                AlbumPullHaptics.landed()
            }
        }
    }

    // MARK: Math

    private func smooth(_ x: CGFloat, _ a: CGFloat = 0, _ b: CGFloat = 1) -> CGFloat {
        let t = min(1, max(0, (x - a) / (b - a)))
        return t * t * (3 - 2 * t)
    }

    private func lerp(_ a: CGFloat, _ b: CGFloat, _ t: CGFloat) -> CGFloat { a + (b - a) * t }

    private func lerpRect(_ a: CGRect, _ b: CGRect, _ t: CGFloat) -> CGRect {
        CGRect(x: lerp(a.minX, b.minX, t), y: lerp(a.minY, b.minY, t),
               width: lerp(a.width, b.width, t), height: lerp(a.height, b.height, t))
    }
}

/// Edge-swipe back keeps working on pushed pages that hide the navigation
/// bar, and always wins: the page's own drags (the album pager, SwiftUI drag
/// gestures, scroll views) wait for it to fail, which it does at once for any
/// touch that doesn't start at the edge. Except off the album's first page
/// (AlbumPopGate): there the back swipes never begin, and a twin edge pan on
/// the navigation controller's view (the same place as its own, so the very
/// edge reaches it as it reaches the back swipe) drives the album's pager
/// instead: a right swipe from the edge turns the album back to page 1.
extension UINavigationController: @retroactive UIGestureRecognizerDelegate {
    private static let albumEdgeName = "yoin.albumEdgePager"

    override open func viewDidLoad() {
        super.viewDidLoad()
        interactivePopGestureRecognizer?.delegate = self
        interactiveContentPopGestureRecognizer?.delegate = self
        let edge = UIScreenEdgePanGestureRecognizer(target: self, action: #selector(albumEdgePan(_:)))
        edge.edges = .left
        edge.name = Self.albumEdgeName
        edge.delegate = self
        view.addGestureRecognizer(edge)
    }

    @objc private func albumEdgePan(_ g: UIScreenEdgePanGestureRecognizer) {
        // Measured from where it was recognised, as the page's own pan is, so
        // a drag from the edge and one from mid-screen map to the same progress.
        let x = g.translation(in: view).x
        if g.state == .began { AlbumPopGate.edgeOrigin = x }
        AlbumPopGate.edgeDrag?(g.state, x - AlbumPopGate.edgeOrigin, g.velocity(in: view).x)
    }

    private func isAlbumEdge(_ g: UIGestureRecognizer) -> Bool { g.name == Self.albumEdgeName }

    public func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        if isAlbumEdge(gestureRecognizer) {
            return viewControllers.count > 1 && AlbumPopGate.blocksPop && AlbumPopGate.edgeDrag != nil
        }
        return viewControllers.count > 1 && !AlbumPopGate.blocksPop
    }

    public func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                                  shouldBeRequiredToFailBy other: UIGestureRecognizer) -> Bool {
        // Whichever edge swipe is live wins over the page's own drags.
        let live = isAlbumEdge(gestureRecognizer) ? AlbumPopGate.blocksPop
            : gestureRecognizer === interactivePopGestureRecognizer && !AlbumPopGate.blocksPop
        return live && viewControllers.count > 1 && other.view?.isDescendant(of: view) == true
    }
}

/// A list piece's progressive blur under the album spectrum's soft edge
/// (`AlbumBandBlur.metal`): full `radius` at `zoneTop` (the header's edge, in
/// `AlbumDetailView.pageSpace`), none `zoneHeight` below it. Off the zone the
/// effect is disabled, so a scroll costs nothing for the rest of the list.
struct AlbumBandBlur: ViewModifier {
    var zoneTop: CGFloat
    var zoneHeight: CGFloat
    var radius: CGFloat

    func body(content: Content) -> some View {
        let zoneTop = zoneTop, zoneHeight = zoneHeight, radius = radius
        return content.visualEffect { c, proxy in
            let f = proxy.frame(in: .named(AlbumDetailView.pageSpace))
            let on = radius > 0.3 && zoneHeight > 0 && f.maxY > zoneTop - radius && f.minY < zoneTop + zoneHeight
            return c.layerEffect(ShaderLibrary.albumBandBlur(.float(zoneTop - f.minY), .float(max(1, zoneHeight)),
                                                             .float(radius)),
                                 maxSampleOffset: CGSize(width: radius, height: radius), isEnabled: on)
        }
    }
}

/// The album page's pull-up beats (51, 2026-11-06): a firm one when the drag
/// passes the point where a release forms the spectrum band, a lighter one
/// when it passes back (a release folds the band), and a soft one when the
/// band lands at the top. Folding back down lands silently.
@MainActor
enum AlbumPullHaptics {
    private static let form = UIImpactFeedbackGenerator(style: .medium)
    private static let fold = UIImpactFeedbackGenerator(style: .light)
    private static let land = UIImpactFeedbackGenerator(style: .soft)

    static func prepare() {
        form.prepare()
        fold.prepare()
        land.prepare()
    }

    static func armed(expanding: Bool) {
        if expanding { form.impactOccurred(intensity: 0.8) } else { fold.impactOccurred(intensity: 0.6) }
    }

    static func landed() { land.impactOccurred(intensity: 0.6) }
}
