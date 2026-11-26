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
/// the pager follows the finger 1:1. There is only ever one cover (51,
/// 2026-11-06): it flies from where it is, the hero's middle or the pulled-up
/// stamp, to the scrapbook's cover (taking its tilt) and back, while the
/// scrapbook's own cover waits hidden. The hero text is gone a third of the
/// way in, and each scrapbook piece trails the drag by its own depth and
/// leans a little further. The butterfly is drawn whole across the page
/// edge, so its right wing runs on into the scrapbook, and breaks up into
/// fine dots as the scrapbook settles; the pull-up dissolves it the same way
/// (DotDissolve), as it does the playlist's sleeves.
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
    /// spectrum, but owner + "Playlist" meta, a ⋮ menu, two of its albums'
    /// covers fanned behind its own (the sleeves), a Length / Owner / Description block instead of the emblem,
    /// thumb-led track rows, and no scrapbook page.
    private var routePlaylist: PlaylistInfo?
    /// The tip page's demo phone (Tips/TipAlbumScenes.swift): [album] as given, its track list pulled up and
    /// staying there. Nothing is read from the account, played, opened or written; a row's hold is the demo's.
    private var demo: AlbumPageDemo?

    init(album: Album, padColumn: Bool = false, playlist: PlaylistInfo? = nil, demo: AlbumPageDemo? = nil) {
        routeAlbum = album
        self.padColumn = padColumn
        routePlaylist = playlist
        self.demo = demo
        let initialP: CGFloat = demo != nil ? 1
            : (["album-list", "playlist-list"].contains(LaunchConfig.screen) ? 1
            : LaunchConfig.screen == "album-mid" ? 0.42 : 0)
        let initialPage: CGFloat = LaunchConfig.screen.hasPrefix("album-scrapbook") ? 1 : 0
        _motion = State(initialValue: AlbumDetailMotionState(p: initialP, page: initialPage, forcedPageFrac: Self.forcedPageFrac))
        let ready = demo != nil || initialP > 0 || initialPage > 0
            || LaunchConfig.screen != "home" || ScrapbookFocusRequest.shared.pending != nil
            || ScrapbookFocusRequest.shared.wantsAlbum(album.id)
        _deferredReady = State(initialValue: ready)
    }

    /// The page's album / playlist as the store has it now (a server album's
    /// tracks arrive after the push; the mock is the route's own).
    private var playlist: PlaylistInfo? { routePlaylist.map { model.playlistInfo($0.id.rawId) } }
    private var album: Album { demo != nil ? routeAlbum : playlist?.asAlbum ?? model.resolved(routeAlbum) }
    private var isMock: Bool { routeAlbum.id.provider == "mock" || demo != nil }

    /// Shown in iPad's detail column (any page there, the column's root or pushed on it).
    @Environment(\.inPadColumn) private var inPadColumn
    @State private var motion: AlbumDetailMotionState
    /// Deferred heavy content (track list rows and page 2 scrapbook): skipped on Frame 1
    /// of zoom-open and enabled after ~420ms or immediately on first drag.
    @State private var deferredReady: Bool
    /// Pull-up drag: `p` when the drag was claimed.
    @State private var dragBase: CGFloat?
    @State private var pageWidth: CGFloat = 402
    @State private var pageSpring = AlbumSpring()
    @State private var pullSpring = AlbumSpring()
    private var offFirstPage: Bool { motion.offFirstPage }
    /// Pull-up haptics: whether the drag is past its snap point (release would
    /// form the band / fold it back); nil until the drag has moved.
    @State private var pullArmed: Bool?
    /// The settle under way is forming the band; its landing gets one beat.
    @State private var pullLanding = false
    /// This page's claim on the edge swipe (AlbumPopGate).
    @State private var gateID = UUID()
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
    /// The rows (by index) whose "Apple Music doesn't stream this song" line is open.
    @State private var notStreamedReasons: Set<Int> = Self.demoNotStreamedReasons

    /// `-notStreamedDemo open` (DEBUG): the demo's third row opens with its reason.
    private static var demoNotStreamedReasons: Set<Int> {
        #if DEBUG
        if UserDefaults.standard.string(forKey: "notStreamedDemo") == "open" { return [2] }
        #endif
        return []
    }
    /// The playlist's sleeves have opened from behind its cover (once).
    /// Screenshots of a playlist open with them settled.
    @State private var sleevesOpen = LaunchConfig.screen.hasPrefix("playlist")

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
    /// The strip's feathered tail below that (Glass Lab slider).
    private var stripFeather: CGFloat {
        let v = GlassLab.shared.extras["albumStripFeather"] ?? 0
        return v > 0 ? v : 56
    }
    /// The Strip's progressive blur radius at the feather's end (Glass Lab slider).
    private var stripBlur: CGFloat {
        let v = GlassLab.shared.extras["albumStripBlur"] ?? 0
        return v > 0 ? v : 22
    }
    /// How far the strip reaches below the safe area (Glass Lab slider).
    private var stripExtend: CGFloat {
        let v = GlassLab.shared.extras["albumStripExtend"] ?? 0
        return v > 0 ? v : 6
    }
    fileprivate static let bandBlurRadius: CGFloat = 10
    /// The page's own space (the header's top edge is y = 0), where the list
    /// rows find the band.
    static let pageSpace = "albumPage"
    /// The cover flies to page 2 (an album, motion allowed), from the hero at
    /// rest or from the pulled-up stamp alike.
    private var coverFlies: Bool { playlist == nil && !reduceMotion && scrapCover != nil }

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

    /// The pulled-up list's room under the header, and its blur's: the album's for playlists too (their
    /// header has no dots row), so a song scrolling up is under the full blur before it reaches the title,
    /// and the blur falls off over empty room, not over the first song (51, 2026-11-14: every page's top
    /// as the album's).
    private var listTop: CGFloat { 4 + 52 + 25 + 6 }

    /// Android's Medium threshold for a page's own width.
    static func opensOnList(width: CGFloat) -> Bool { width >= 600 }

    /// Android's ≥ Medium album page (AlbumMediumOverview): a tablet column, or a page at least 600 wide,
    /// is one plain scrolling list whose first row is the cover (240) beside the hero's blocks (Last Play,
    /// the emblem, the review); no hero ⇄ list pull. Albums only; playlists keep their sleeves.
    private func isOverview(_ w: CGFloat) -> Bool {
        demo == nil && playlist == nil && (Self.opensOnList(width: w) || padColumn || inPadColumn)
    }

    /// The overview's content width: the page less its 16 margins, capped at Android's feed width (720).
    static func overviewContent(_ w: CGFloat) -> CGFloat { min(w - 32, 720) }

    /// The overview cover: Android's 240, or less in a column too narrow to keep the blocks beside it
    /// at least as wide.
    static func overviewCoverSide(_ w: CGFloat) -> CGFloat { min(240, (overviewContent(w) - 24) / 2) }

    /// Hero ⇄ list: how far each travels on the pull (MD3 shared axis, 30).
    static let axisTravel: CGFloat = 30
    /// The hero is gone by this p; the list starts to show at this one (fade through).
    static let axisOutEnd: CGFloat = 0.4
    static let axisInStart: CGFloat = 0.3

    var body: some View {
        if demo != nil {
            // The demo phone: the page as drawn, without the app's sheet, pushes, loads and edge-swipe gate.
            pageView.background(Yoin.surface)
        } else {
            routed
        }
    }

    private var pageView: some View {
        let currentAlbum = album
        let currentPlaylist = playlist
        let currentPlays = plays
        let currentBun = bun
        let currentReview = review
        let currentTopBar = topBar
        let currentBandFade = bandFade
        let currentStripBar = stripBar
        let currentSpectrumBar = spectrumBar
        let currentStripExtend = stripExtend
        let currentStripFeather = stripFeather
        let currentStripBlur = stripBlur
        let canFlyCover = currentPlaylist == nil && !reduceMotion && scrapCover != nil
        let prepKey: String = {
            guard deferredReady else { return "pending" }
            if let cover = currentAlbum.palette.cover {
                return "\(cover.key)|\(CoverArtwork.shared.image(cover) != nil)"
            }
            return "mock:\(currentAlbum.id.rawId)"
        }()
        return GeometryReader { geo in
            let w = geo.size.width
            // A short container (a tablet column in landscape) also caps the cover by height, so the
            // header, the review and the titles under it still fit above the column's Play split.
            let side = min(Tokens.detail.ALBUM_COVER_WIDTH_FRACTION.cg * w, Tokens.detail.ALBUM_COVER_MAX.cg,
                           max(160, geo.size.height - 470))
            let band = side + 56
            let overview = isOverview(w)
            ZStack(alignment: .topLeading) {
                background
                // Page 0: the bar, the stamp and the hero slide away with it.
                ZStack(alignment: .topLeading) {
                    if let currentPlaylist {
                        AlbumSleevesView(motion: motion,
                                         covers: Self.sleeveCovers(currentPlaylist),
                                         seed: currentAlbum.seed,
                                         w: w, side: side,
                                         headerContent: headerContent,
                                         sleevesOpen: $sleevesOpen)
                    } else if demo == nil {
                        AlbumButterflyView(motion: motion,
                                           primary: s.primary,
                                           secondary: s.secondary,
                                           surface: app.surface,
                                           headerContent: headerContent,
                                           w: w, band: band)
                    }
                    // Shared axis Y (51, 2026-11-07): the dense titles rise out
                    // and are gone before the list rises in after them, one
                    // short travel each, never a cross-fade in place.
                    if demo == nil {
                        hero(album: currentAlbum, playlist: currentPlaylist, bun: currentBun,
                             review: currentReview, lastPlay: isMock ? ("Yesterday", "16:04") : currentPlays?.lastPlay,
                             w: w, side: side, band: band)
                            .modifier(AlbumHeroPullModifier(motion: motion, coverFlies: canFlyCover))
                    }
                    trackList(album: currentAlbum, playlist: currentPlaylist, plays: currentPlays,
                              bun: currentBun, review: currentReview,
                              lastPlay: isMock ? ("Yesterday", "16:04") : currentPlays?.lastPlay,
                              w: w, topBar: currentTopBar, bandFade: currentBandFade)
                        .modifier(AlbumTrackListPullModifier(motion: motion))
                    AlbumTopBarEffectOverlay(
                        motion: motion,
                        album: currentAlbum,
                        topBar: currentTopBar,
                        topTint: topTint,
                        w: w,
                        side: side,
                        top: geo.safeAreaInsets.top,
                        headerContent: headerContent,
                        listTop: listTop,
                        bandFade: currentBandFade,
                        stripBar: currentStripBar,
                        stripExtend: currentStripExtend,
                        stripFeather: currentStripFeather,
                        stripBlur: currentStripBlur,
                        allowSyncMock: demo != nil || LaunchConfig.screen != "home"
                    )
                }
                .modifier(AlbumPage0OffsetModifier(motion: motion, width: w))
                // Android z-order: the flying cover under the scrapbook page (its
                // emblem and dots on top), the stamp over it; the header on top.
                AlbumFlyingCover(motion: motion,
                                 album: currentAlbum,
                                 w: w,
                                 side: side,
                                 headerContent: headerContent,
                                 isPlaylist: currentPlaylist != nil,
                                 isOverview: overview,
                                 overviewSide: Self.overviewCoverSide(w),
                                 reduceMotion: reduceMotion,
                                 scrapCover: scrapCover,
                                 scrapCoverTilt: scrapCoverTilt) {
                    if demo == nil {
                        AlbumPullHaptics.armed(expanding: false)
                        settlePull(to: 0, velocity: 0)
                    }
                }
                .zIndex(canFlyCover ? 0.5 : 1.5)
                // Page 1: the scrapbook, scrolling under the header (albums only).
                if currentPlaylist == nil, demo == nil,
                   deferredReady || motion.isScrapbookVisible || ScrapbookFocusRequest.shared.pending != nil
                    || ScrapbookFocusRequest.shared.wantsAlbum(routeAlbum.id) {
                    Group {
                        AlbumScrapbookView(album: currentAlbum, plays: currentPlays,
                                           settled: motion.isScrapbookSettled,
                                           topInset: headerContent, pageOffset: 0, pageWidth: w,
                                           coverInFlight: false,
                                           coverFlies: canFlyCover,
                                           motion: motion,
                                           onEditReview: { rating = true }) { rect, tilt in
                            scrapCover = rect
                            scrapCoverTilt = tilt
                        }
                        // Its own frame is the space its cover reports in (= this ZStack's).
                        .coordinateSpace(.named(AlbumScrapbookView.space))
                        .frame(width: w)
                        // Clip the sides only: it scrolls under the status bar and header.
                        .mask { Rectangle().padding(.vertical, -2000) }
                        // Its top edge: a progressive blur under the header, not a hard cut.
                        ProgressiveEdgeBlur(tint: topTint)
                            .frame(width: w, height: geo.safeAreaInsets.top + headerContent + 4)
                            .offset(y: -geo.safeAreaInsets.top)
                    }
                    .modifier(AlbumScrapbookOffsetModifier(motion: motion, width: w))
                    .zIndex(1)
                }
                ZStack(alignment: .topLeading) {
                    header(album: currentAlbum, playlist: currentPlaylist, white: false, w: w,
                           stripBar: currentStripBar, dark: colorScheme == .dark)
                        .modifier(AlbumHeaderTwinOpacityModifier(motion: motion, spectrumBar: currentSpectrumBar, isWhite: false))
                    header(album: currentAlbum, playlist: currentPlaylist, white: true, w: w,
                           stripBar: currentStripBar, dark: colorScheme == .dark)
                        .modifier(AlbumHeaderTwinOpacityModifier(motion: motion, spectrumBar: currentSpectrumBar, isWhite: true))
                    // One ← over both twins: crossfading two glass circles
                    // left ~50 % grey ink on half-faded glass mid pull-up.
                    AlbumBackButton(motion: motion,
                                    spectrumBar: currentSpectrumBar,
                                    tinted: currentPlaylist != nil,
                                    dark: colorScheme == .dark,
                                    s: s,
                                    app: app) {
                        if demo == nil { model.back() }
                    }
                    .frame(height: 52)
                    .padding(.leading, 8)
                    .padding(.top, 4)
                }
                // A playlist's header takes songs, albums and playlists dropped on it, as its Library row does.
                .modifier(PlaylistHeaderDrop(playlist: demo == nil ? currentPlaylist : nil, ink: s.primary))
                .zIndex(3)
            }
            .coordinateSpace(.named(Self.pageSpace))
            .contentShape(.rect)
            .gesture(axisPan(width: w, travel: pullTravel))
            .background(AlbumBandTickObserver(motion: motion, album: currentAlbum, topBar: currentTopBar))
            .onAppear {
                pageWidth = w
                guard demo == nil else { return }
                // A tablet column, or a page at least 600 wide, opens on the track list: on an Android tablet
                // every album page shows its songs (Medium page / Wide column: AlbumMediumOverview,
                // AlbumWideOverview), never the flowing titles alone. A pull down still shows the hero.
                if Self.opensOnList(width: w) || padColumn || inPadColumn, currentPlaylist == nil, motion.p == 0 {
                    motion.p = 1
                    deferredReady = true
                }
                // Screenshots: `-pageHold "1,0.75"` = a drag from page 2 held at 0.75
                // (the same state a real drag leaves: pageBase set, page moved 1:1).
                if let hold = Self.pageHold {
                    motion.pageBase = hold.from
                    motion.page = hold.to
                    deferredReady = true
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
            .onChange(of: offFirstPage) { _, off in if demo == nil { AlbumPopGate.set(off, by: gateID) } }
            .onChange(of: w) { _, now in pageWidth = now }
            .task {
                guard !deferredReady else { return }
                try? await Task.sleep(for: .milliseconds(Int(420 / LaunchConfig.animationSpeed)))
                if !Task.isCancelled && !deferredReady {
                    deferredReady = true
                }
            }
            .task(id: prepKey) {
                guard deferredReady else { return }
                await AlbumSpectrum.prepare(for: currentAlbum)
            }
        }
    }

    /// The page in the app: its bar hidden, its rate sheet, its pushes, its loads.
    private var routed: some View {
        pageView
        .toolbar(.hidden, for: .navigationBar)
        .background(Yoin.surface)
        .statusBarLight(barLight)
        .sheet(isPresented: $rating) {
            AlbumRateSheet(album: album, scheme: s)
                .ySolidSheet(s.surfaceContainerLow)
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
        // Cloud backup's View (the toast): this playlist's backup, pushed on this page's stack.
        .navigationDestination(item: backupRequest) { PlaylistBackupView(backupId: $0.backupId) }
        // Now Playing's "Manage in Scrapbook" and a note's mention (ScrapbookFocusRequest):
        // page 2; the scrapbook takes the request there. The tracks may land after the push.
        .onChange(of: ScrapbookFocusRequest.shared.pending, initial: true) { _, _ in followScrapFocus() }
        .onChange(of: ScrapbookFocusRequest.shared.pendingAlbum, initial: true) { _, _ in followScrapFocus() }
        .onChange(of: album.tracks.map(\.id)) { _, _ in followScrapFocus() }
        .task(id: "\(model.store.generation)|\(routeAlbum.id.rawId)|\(playlist?.title ?? "")") {
            guard !isMock else { return }
            if let playlist {
                // The activity log's visit (Android recordPlaylistVisit), once its name is known.
                model.store.recordPlaylistVisit(playlist)
                return
            }
            await model.store.loadAlbum(routeAlbum.id.rawId)
            // The activity log's visit (Android recordAlbumVisit).
            model.store.recordAlbumVisit(model.store.album(routeAlbum.id.rawId) ?? routeAlbum)
        }
    }

    /// Light status bar icons over the formed spectrum or strip (Android AlbumBarStatusBarEffect: docked past
    /// half the pull-up, on the first page), in the light scheme (the locked spectrum keeps white at 7:1),
    /// and only in a full window: a detail column, the iPad's Now Playing panel beside the page or an open
    /// Now Playing share the status bar, so they leave it alone.
    private var barLight: Bool {
        (spectrumBar || stripBar) && motion.isPullHalf && motion.pageLessHalf && colorScheme == .light
            && !padColumn && !inPadColumn && !model.npExpanded && !(model.isPad && model.npPanel)
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
        let focus = ScrapbookFocusRequest.shared
        let forSong = focus.pending.map { r in album.tracks.contains { $0.id == r.songId } } ?? false
        guard playlist == nil, forSong || focus.wantsAlbum(routeAlbum.id), motion.pageTarget < 1 else { return }
        if !deferredReady { deferredReady = true }
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
        return model.store.phase(loadKey)
    }

    /// The store's key for this page's load.
    private var loadKey: String {
        playlist.map { "playlist:\($0.id.rawId)" } ?? "album:\(routeAlbum.id.rawId)"
    }

    /// The failed load as a tester's report (Copy details beside Retry, Android DetailErrorState).
    private var failureReport: String? { isMock ? nil : model.store.failureReports[loadKey] }

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

    private func header(album: Album, playlist: PlaylistInfo?, white: Bool, w: CGFloat, stripBar: Bool, dark: Bool) -> some View {
        // Playlists: the header glass takes the playlist's own container color,
        // so it reads clean over the colorful page instead of a grey film.
        // Light: primaryContainer + its ink; dark: a high container (the
        // expressive dark primaryContainer is mid-tone) + the primary ink.
        let tinted = playlist != nil && !white
        let ink: Color = white ? .white : !tinted ? app.onSurface : dark ? s.primary : s.onPrimaryContainer
        let glass: Glass = white ? .regular.tint(.white.opacity(0.18)).interactive()
            : !tinted ? .regular.interactive()
            : .regular.tint(dark ? s.surfaceContainerHighest.opacity(0.7) : s.primaryContainer.opacity(0.6)).interactive()
        return VStack(spacing: 0) {
            HStack(spacing: Tokens.detail.HEADER_TITLE_GAP.cg) {
                // The ← itself is drawn once above both twins (AlbumBackButton).
                Color.clear.frame(width: 40, height: 40)
                VStack(alignment: .leading, spacing: 0) {
                    // The version as a tag after the name (Android AlbumHeader, #154).
                    AlbumVersionTitle(album: album, scheme: s, onBar: white,
                                      explicitSize: Tokens.type.headlineSmall.size.cg) { name in
                        Text(name)
                            .lineLimit(1)
                            .yoinRole(Tokens.type.headlineSmall)
                            // Light Strip: over the feathered spectrum the cover's
                            // primary is the strip's own hue, so it turns to body ink.
                            .modifier(AlbumStripTitleInkModifier(motion: motion,
                                                                 enabled: !white && stripBar && !dark,
                                                                 primary: white ? .white : s.primary,
                                                                 onSurface: app.onSurface))
                    }
                    // DetailMetaLine in bodyMedium, groups 12 apart, no separators:
                    // album [artist] [Album] [date]; playlist [owner] [Playlist].
                    // The kind is Medium in primary. The white twin over the bar:
                    // onSurface white, primary white @ 0.88.
                    let plain: Color = white ? .white : app.onSurface
                    let kind: Color = white ? .white.opacity(0.88) : app.primary
                    // On one baseline, not centred (Android MetaLine, 2026-11-24): GSF has no Han glyphs, so a
                    // group in Chinese takes the CJK fallback's taller line and, centred in the role's line box,
                    // its baseline sat apart from a digits-only group's ("专辑" vs "22.6.12").
                    HStack(alignment: .firstTextBaseline, spacing: Tokens.metaLine.GROUP_GAP.cg) {
                        if let playlist {
                            Text(playlist.owner).yoinRole(Tokens.type.bodyMedium).foregroundStyle(plain)
                            Text("Playlist").yoinRole(Tokens.type.bodyMedium, weight: .medium).foregroundStyle(kind)
                        } else {
                            Button { if demo == nil { model.openArtist(album.artist, id: album.artistId) } } label: {
                                Text(album.artist).yoinRole(Tokens.type.bodyMedium).foregroundStyle(plain)
                            }
                            .buttonStyle(.plain).pointerHighlight()
                            // The real kind (EP / Single / Compilation; Album when the service doesn't say),
                            // Android PR #343.
                            Text(ReleaseMarks.headerKind(album.releaseType).label)
                                .yoinRole(Tokens.type.bodyMedium, weight: .medium).foregroundStyle(kind)
                            // The release date as precisely as the service dates it: "22.6.12" / "22.6" / "2022".
                            if let date = AlbumReleaseDateText.header(album.releaseDate, year: album.year) {
                                Text(verbatim: date).yoinRole(Tokens.type.bodyMedium).foregroundStyle(plain)
                            }
                        }
                    }
                    .lineLimit(1)
                }
                // Pulled up, the title gives up the stamp + 12 (+ 4 before a playlist's ⋮).
                .modifier(AlbumTitlePullPaddingModifier(motion: motion, hasPlaylistMenu: playlist != nil))
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
                    .buttonStyle(.plain).pointerHighlight()
                    .yGlassEffect(glass, in: .circle)
                    .frame(width: 48, height: 48)
                    .accessibilityLabel("More actions")
                    .padding(.trailing, -12)
                }
            }
            .frame(height: 52)
            if playlist == nil {
                AlbumPageDots(motion: motion,
                              onColor: white ? .white : s.secondary,
                              offColor: white ? .white.opacity(0.4) : app.onSurfaceVariant.opacity(0.3)) { i in
                    if demo == nil {
                        if !deferredReady { deferredReady = true }
                        settlePage(to: CGFloat(i), velocity: 0)
                    }
                }
            }
        }
        .padding(.leading, 8)
        .padding(.trailing, 16)
        .padding(.top, 4)
        .padding(.bottom, 6)
        .frame(width: w)
    }

    private func hero(album: Album, playlist: PlaylistInfo?, bun: AlbumBun,
                      review: (text: String, at: String?)?, lastPlay: (day: String, time: String)?,
                      w: CGFloat, side: CGFloat, band: CGFloat) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Spacer().frame(height: headerContent + band + 8)
            if bodyPending {
                pendingBody(side: side)
                    .transition(.opacity)
            } else if let playlist {
                playlistMeta(playlist).frame(width: side).frame(maxWidth: .infinity)
                Spacer().frame(height: 28)
                flowingTitles(album: album, playlist: playlist)
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
                    if bun.kind == .average { ratedCoverage(bun: bun, trackCount: album.tracks.count) }
                }
                comment(review)
            }
            .frame(width: side)
            .frame(maxWidth: .infinity)
            Spacer().frame(height: 28)
            HStack(spacing: 12) {
                countLabel(album: album, playlist: playlist)
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
            flowingTitles(album: album, playlist: playlist)
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
            RetryRow(scheme: app, details: failureReport, action: retry)
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
    private func countLabel(album: Album, playlist: PlaylistInfo?) -> some View {
        let count = playlist?.entries.count ?? album.trackCount
        let secs = playlist.map { $0.entries.map(\.track.durationSec).reduce(0, +) } ?? album.totalSec
        let mins = secs / 60
        // Units through the catalog (a plain String in Text isn't looked up): 首 / 小时 / 分钟.
        var groups: [(String, String)] = [("\(count)", count == 1 ? String(localized: "track") : String(localized: "tracks"))]
        if mins >= 60 {
            groups.append(("\(mins / 60)", String(localized: "h")))
            if mins % 60 > 0 { groups.append(("\(mins % 60)", String(localized: "min"))) }
        } else if mins > 0 {
            groups.append(("\(mins)", String(localized: "min")))
        }
        // Numbers and units on one baseline (Android Stat / MetaLine: "29" sat above "分钟" when centred).
        return HStack(alignment: .firstTextBaseline, spacing: Tokens.metaLine.GROUP_GAP.cg) {
            ForEach(groups, id: \.1) { value, unit in
                HStack(alignment: .firstTextBaseline, spacing: Tokens.metaLine.INNER_GAP.cg) {
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
    private func ratedCoverage(bun: AlbumBun, trackCount: Int) -> some View {
        Text("\(bun.ratedCount)/\(trackCount) rated")
            .monospacedDigit()
            .lineLimit(1)
            .yoinRole(Tokens.type.labelSmall)
            .foregroundStyle(app.onSurfaceVariant)
    }

    /// AlbumHeroComment: written, bodyMedium (3 lines) over its date in
    /// labelMedium onSurfaceVariant, 4 apart; not written, a 40 tonal pen.
    /// Tapping either opens the rate + review sheet (a server album).
    @ViewBuilder
    private func comment(_ comment: (text: String, at: String?)?) -> some View {
        if let comment {
            Button { rating = true } label: {
                VStack(alignment: .leading, spacing: 4) {
                    NoteMarkupText(text: comment.text, size: Tokens.type.bodyMedium.size.cg,
                                   mentionColor: app.primary, glyphColor: app.primary)
                        .lineLimit(3)
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
            .buttonStyle(.plain).pointerHighlight()
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
    @ViewBuilder
    private func flowingTitles(album: Album, playlist: PlaylistInfo?) -> some View {
        if playlistIsEmpty {
            // Android's quiet empty state: the bottom bar stays armed, Play just does nothing.
            emptyPlaylistLine
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
        } else {
            FlowingTrackTitles(titles: album.tracks.map(\.title), primary: app.onSurface, variant: app.onSurfaceVariant,
                               onPick: motion.isPullLessHalf ? { i in
                                   // A song Apple won't stream never starts from here either (MusicKit would
                                   // drop it and play the next one without a word): the reason, as a toast,
                                   // since its row may be out of sight.
                                   let t = playlist.flatMap { $0.entries.indices.contains(i) ? $0.entries[i].track : nil }
                                       ?? (album.tracks.indices.contains(i) ? album.tracks[i] : nil)
                                   if let t, NotStreamedRow.tap(t) == .explain {
                                       PlaylistActions.shared.show(String(localized: "Apple Music doesn't stream this song in your region yet."))
                                   } else if let playlist {
                                       model.play(playlist.entries, startAt: i)
                                   } else {
                                       playAlbum(from: i)
                                   }
                               } : nil)
        }
    }

    /// A playlist that loaded with nothing in it (Android PlaylistDetailScreen empty states, parity B-U15).
    private var playlistIsEmpty: Bool {
        guard let playlist, !isMock, !bodyPending else { return false }
        return playlist.entries.isEmpty
    }

    private var emptyPlaylistLine: some View {
        Text("This playlist is empty.")
            .yoinRole(Tokens.type.bodyMedium)
            .foregroundStyle(app.onSurfaceVariant)
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

    private func trackList(album: Album, playlist: PlaylistInfo?, plays: LibraryStore.AlbumHistory?,
                           bun: AlbumBun, review: (text: String, at: String?)?,
                           lastPlay: (day: String, time: String)?,
                           w: CGFloat, topBar: Int, bandFade: CGFloat) -> some View {
        let overview = isOverview(w)
        let deferRows = !deferredReady && !motion.isPullActive && !overview
        let rowBlur = AlbumBandBlurMotion(motion: motion, topBar: topBar, zoneTop: headerContent, zoneHeight: bandFade)
        return ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                if overview {
                    overviewRow(album: album, plays: plays, bun: bun, review: review, lastPlay: lastPlay, w: w)
                        .padding(.bottom, 24)
                }
                // An empty playlist has no count; its empty line stands in (Android PlaylistTrackList).
                if !playlistIsEmpty {
                    countLabel(album: album, playlist: playlist)
                        .padding(.leading, 8)
                        .padding(.top, 2)
                        .padding(.bottom, 10)
                        .modifier(rowBlur)
                }
                if deferRows {
                    EmptyView()
                } else {
                    if bodyPending {
                        Group {
                            if loadFailed { RetryRow(scheme: app, details: failureReport, action: retry) } else { RowSkeleton(scheme: app) }
                        }
                        .transition(.opacity)
                    } else if loadFailed {
                        retryButton.padding(.leading, 4)
                    }
                    if bodyPending {
                        EmptyView()
                    } else if let playlist {
                        if playlistIsEmpty {
                            emptyPlaylistLine
                                .padding(.leading, 8)
                                .padding(.top, 8)
                                .modifier(rowBlur)
                        }
                        ForEach(Array(playlist.entries.enumerated()), id: \.offset) { i, e in
                            playlistRow(e, index: i)
                                .modifier(rowBlur)
                                .trackRow(for: e, context: .playlist(playlist.id.rawId, index: i)) {
                                    // A song Apple won't stream doesn't play here either: its tap says why.
                                    switch NotStreamedRow.tap(e.track) {
                                    case .play: model.play(playlist.entries, startAt: i)
                                    case .explain: toggleNotStreamedReason(i)
                                    }
                                }
                        }
                        playlistMeta(playlist)
                            .padding(.horizontal, 8)
                            .padding(.top, 28)
                            .modifier(AlbumPullRevealFadeModifier(motion: motion))
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
                                    .modifier(rowBlur)
                            }
                            if let demo {
                                // The demo's row: its own tint under a finger, its hold the demo's, no play.
                                trackRow(t, index: i, albumArtist: album.artist)
                                    .trackRow(for: PlaylistEntry(album: album, track: t), context: .album(), play: {},
                                              menu: { demo.onLongPress(i) })
                                    .demoTarget("row\(i)")
                            } else {
                                trackRow(t, index: i, albumArtist: album.artist).modifier(rowBlur)
                                    .trackRow(for: PlaylistEntry(album: album, track: t), context: .album(viewInScrapbook: {
                                        // Page 2, scrolled to the song's piece (Android onViewInScrapbook).
                                        ScrapbookFocusRequest.shared.request(songId: t.id, openSheet: false)
                                        settlePage(to: 1, velocity: 0)
                                    })) {
                                        // A song Apple won't stream doesn't play: its tap says why (Android AlbumTrackRow).
                                        if t.notStreamed { toggleNotStreamedReason(i) } else { playAlbum(from: i) }
                                    }
                            }
                        }
                        // The release date closes the list, as precisely as the service dates it (Android
                        // AlbumReleaseDateLine, like Spotify): just the date in the app's language, no label.
                        if !tracks.isEmpty,
                           let date = AlbumReleaseDateText.full(album.releaseDate, locale: AlbumCredits.appLocale) {
                            Text(verbatim: date)
                                .lineLimit(1)
                                .yoinRole(Tokens.type.labelMedium)
                                .foregroundStyle(app.onSurfaceVariant)
                                .padding(.horizontal, 8)
                                .padding(.top, 14)
                                .padding(.bottom, 6)
                                .modifier(rowBlur)
                                .accessibilityIdentifier("album.releaseDate")
                        }
                        if !overview, demo == nil {
                            linerNotes(album: album, plays: plays, bun: bun, review: review, lastPlay: lastPlay)
                                .padding(.horizontal, 8)
                                .padding(.top, 28)
                                .modifier(AlbumPullRevealFadeModifier(motion: motion))
                        }
                    }
                }
            }
            .frame(maxWidth: overview ? Self.overviewContent(w) : nil)
            .frame(maxWidth: .infinity)
            .padding(.horizontal, overview ? 0 : 16)
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
        // (Not on the demo phone: the system draws the edge at the window's scale, over the phone's rim.)
        .scrollEdgeEffectHidden((topBar != 2 && topBar != 4) || demo != nil, for: .top)
        .safeAreaBar(edge: .top) { AlbumScrapbookView.edgeBar(height: listTop + bandFade) }
        .onScrollGeometryChange(for: Bool.self) { $0.contentOffset.y + $0.contentInsets.top <= 1 } action: { _, top in
            motion.listAtTop = top
        }
        .onScrollGeometryChange(for: CGFloat.self) { max(0, $0.contentOffset.y + $0.contentInsets.top) } action: { _, y in
            if overview { motion.listScroll = y }
        }
        .scrollDisabled(!motion.listInteractive)
        .frame(width: w)
    }

    /// Shows or hides the reason under row [index] (a song Apple won't stream).
    private func toggleNotStreamedReason(_ index: Int) {
        withAnimation(Yoin.spatial) {
            NotStreamedRow.toggle(index, in: &notStreamedReasons)
        }
    }

    /// Android UnavailableTrackAlpha.
    static let notStreamedAlpha = NotStreamedRow.alpha

    private func trackRow(_ t: Track, index: Int, albumArtist: String) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            trackRowLine(t, index: index, albumArtist: albumArtist)
            // Android UnavailableTrackReason under an album row: bodySmall onSurfaceVariant, 38 / 14 / 10.
            if t.notStreamed && notStreamedReasons.contains(index) {
                Text("Apple Music doesn't stream this song in your region yet.")
                    .yoinRole(Tokens.type.bodySmall)
                    .lineSpacing(YoinType.lineSpacing(Tokens.type.bodySmall))
                    .foregroundStyle(app.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.leading, 38)
                    .padding(.trailing, 14)
                    .padding(.bottom, 10)
                    .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
        .clipped()
    }

    private func trackRowLine(_ t: Track, index: Int, albumArtist: String) -> some View {
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
                    ExplicitTitle(text: t.title, explicit: t.isExplicit, fontSize: Tokens.type.titleMedium.size.cg,
                                  color: app.onSurfaceVariant) {
                        Text(t.title)
                            .lineLimit(1)
                            .yoinRole(Tokens.type.titleMedium)
                            .foregroundStyle(playing ? s.primary : app.onSurface)
                    }
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
                if let artist = t.artist?.trimmingCharacters(in: .whitespaces), !artist.isEmpty, artist != albumArtist {
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
            if t.notStreamed {
                notStreamedBadge(index)
            } else if t.id.provider == AppleMusicMapping.provider {
                libraryButton(t)
            } else {
                heartButton(t)
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 10)
        .frame(minHeight: 56)
        .opacity(t.notStreamed ? Self.notStreamedAlpha : 1)
        .contentShape(.rect(cornerRadius: Tokens.shape.LIST_ROW.cg))
    }

    /// Android UnavailableTrackBadge: an 18 "?" circle in the cover's secondaryContainer, labelMedium onSecondaryContainer;
    /// a tap shows or hides the reason under the row. Kept in the heart's 36 slot so the row doesn't shift.
    /// In dark, secondary / onSecondary (NotStreamedBadge.tone), which read on the dimmed row.
    private func notStreamedBadge(_ index: Int) -> some View {
        let tone = NotStreamedBadge.tone(s, dark: colorScheme == .dark)
        return Button { toggleNotStreamedReason(index) } label: {
            Text(verbatim: "?")
                .yoinRole(Tokens.type.labelMedium)
                .foregroundStyle(tone.ink)
                .frame(width: 18, height: 18)
                .background(tone.fill, in: .circle)
                .frame(width: 36, height: 36)
                .contentShape(.circle)
        }
        .buttonStyle(PressScale())
        .accessibilityLabel("Why can't this play")
    }

    private func hasNote(_ t: Track) -> Bool {
        if let demo { return demo.notedTrack == t.number }
        return !isMock && !NotesStore.shared.notes(provider: t.id.provider, trackId: t.id.rawId).isEmpty
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
        .accessibilityLabel(added ? String(localized: "In library") : String(localized: "Add to library"))
    }

    /// The heart ring (Android AlbumCircleToggle).
    private func heartButton(_ t: Track) -> some View {
        let liked = demo != nil ? t.starred : model.store.isStarred(t)
        return Button { if demo == nil { withAnimation(Yoin.effects) { model.store.toggleStar(t) } } } label: {
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
        .accessibilityLabel(liked ? String(localized: "Remove from favorites") : String(localized: "Add to favorites"))
    }

    // MARK: Playlist pieces

    /// The covers fanned behind a playlist's own: its songs' album covers in
    /// playlist order, distinct, none the playlist's cover (which a server
    /// often takes from the first song), at most two.
    static func sleeveCovers(_ pl: PlaylistInfo) -> [CoverPalette] {
        // The same cover at another size is the same cover: compare by key.
        func same(_ a: CoverPalette, _ b: CoverPalette) -> Bool {
            if let ka = a.cover?.key, let kb = b.cover?.key { return ka == kb }
            return a == b
        }
        var picked: [CoverPalette] = []
        for e in pl.entries {
            let c = e.album.palette
            if same(c, pl.palette) || picked.contains(where: { same($0, c) }) { continue }
            picked.append(c)
            if picked.count == 2 { break }
        }
        return picked
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
                    sectionLabel(String(localized: "Length"))
                    // The service's count when the songs weren't handed over (Android songCount), and
                    // "—" for a length nobody knows rather than "0 min".
                    Text("\(pl.tracksUnavailable ? pl.songCount ?? 0 : pl.entries.count) tracks").monospacedDigit()
                        .yoinRole(Tokens.type.bodyLarge).foregroundStyle(app.onSurface)
                    Text(pl.entries.isEmpty ? "—" : mins >= 60 ? String(localized: "\(mins / 60) h \(mins % 60) min") : String(localized: "\(mins) min")).monospacedDigit()
                        .yoinRole(Tokens.type.bodyMedium).foregroundStyle(app.onSurfaceVariant)
                }
                Spacer()
                VStack(alignment: .leading, spacing: 4) {
                    sectionLabel(String(localized: "Owner"))
                    Text(pl.owner).lineLimit(1)
                        .yoinRole(Tokens.type.bodyLarge).foregroundStyle(app.onSurface)
                    if let isPublic = pl.isPublic {
                        Text(isPublic ? String(localized: "Public") : String(localized: "Private"))
                            .yoinRole(Tokens.type.bodyMedium).foregroundStyle(app.onSurfaceVariant)
                    }
                }
            }
            if let d = pl.description {
                VStack(alignment: .leading, spacing: 6) {
                    sectionLabel(String(localized: "Description"))
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
    private func playlistRow(_ e: PlaylistEntry, index: Int) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            playlistRowLine(e, index: index)
            // A song Apple won't stream: the reason under the row, indented to the title (8 + 44 + 12).
            if e.track.notStreamed && notStreamedReasons.contains(index) {
                NotStreamedReason(scheme: app, leading: 64, trailing: 8)
            }
        }
        .clipped()
    }

    private func playlistRowLine(_ e: PlaylistEntry, index: Int) -> some View {
        HStack(spacing: 12) {
            CoverArt(palette: e.album.palette, cornerRadius: 0, seed: e.album.seed)
                .frame(width: 44, height: 44)
                .clipShape(.rect(cornerRadius: Tokens.shape.THUMB.cg, style: .continuous))
                // Android SongListItem: the "?" on the cover's bottom-left corner.
                .overlay(alignment: .bottomLeading) {
                    if e.track.notStreamed { NotStreamedBadge(scheme: s) { toggleNotStreamedReason(index) } }
                }
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    ExplicitTitle(text: e.track.title, explicit: e.track.isExplicit,
                                  fontSize: Tokens.type.titleMedium.size.cg, color: app.onSurfaceVariant) {
                        Text(e.track.title).lineLimit(1)
                            .yoinRole(Tokens.type.titleMedium)
                            .foregroundStyle(app.onSurface)
                    }
                    // The album row's note marker (Android PlaylistNoteMarker, parity B-U14).
                    if hasNote(e.track) {
                        YoinIcon("note", size: 12)
                            .foregroundStyle(s.primary.opacity(0.8))
                            .accessibilityLabel("Has note")
                            .transition(.opacity.combined(with: .scale(scale: 0.6, anchor: .leading)))
                    }
                }
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
        .opacity(e.track.notStreamed ? NotStreamedRow.alpha : 1)
        .contentShape(.rect(cornerRadius: Tokens.shape.LIST_ROW.cg))
    }

    /// The list's liner notes (AlbumHeroMetaBlocks, default arrangement):
    /// Last Play (label, 4, day bodyLarge, 4, time bodyMedium tabular) and the
    /// emblem (+ "X/N rated") across one row, centred; the comment 14 below.
    /// The overview's first row (Android AlbumMediumHeroRow): the cover beside the hero's blocks.
    private func overviewRow(album: Album, plays: LibraryStore.AlbumHistory?, bun: AlbumBun,
                             review: (text: String, at: String?)?, lastPlay: (day: String, time: String)?,
                             w: CGFloat) -> some View {
        let side = Self.overviewCoverSide(w)
        return HStack(alignment: .top, spacing: 24) {
            CoverArt(palette: album.palette, cornerRadius: 0, seed: album.seed)
                .frame(width: side, height: side)
                .clipShape(.rect(cornerRadius: Tokens.shape.HERO.cg, style: .continuous))
                .labCoverShadow()
                .accessibilityHidden(true)
            linerNotes(album: album, plays: plays, bun: bun, review: review, lastPlay: lastPlay)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 8)
    }

    private func linerNotes(album: Album, plays: LibraryStore.AlbumHistory?, bun: AlbumBun,
                            review: (text: String, at: String?)?, lastPlay: (day: String, time: String)?) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .center) {
                if isMock || plays != nil {
                    VStack(alignment: .leading, spacing: 0) {
                        sectionLabel(String(localized: "Last Play")).padding(.bottom, 4)
                        Text(lastPlay?.day ?? String(localized: "Never")).yoinRole(Tokens.type.bodyLarge).foregroundStyle(app.onSurface)
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
                    if bun.kind == .average { ratedCoverage(bun: bun, trackCount: album.tracks.count) }
                }
            }
            comment(review)
        }
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
                // The demo phone's page stays as it is (and leaves a sideways drag to the tips pager).
                guard demo == nil else { return false }
                switch axis {
                case .horizontal:
                    guard playlist == nil else { return false }
                    return !(motion.page <= 0.001 && d.x > 0)
                case .vertical:
                    // The overview is one list: nothing to pull (Android composes no RevealState there).
                    guard motion.page <= 0.001, !isOverview(width) else { return false }
                    return motion.p < 0.999 || (d.y > 0 && motion.listAtTop)
                }
            },
            changed: { axis, t in
                if !deferredReady { deferredReady = true }
                switch axis {
                case .horizontal:
                    pagerDragged(t.x, width: width)
                case .vertical:
                    if dragBase == nil {
                        pullSpring.stop()
                        pullLanding = false
                        dragBase = motion.p
                        pullArmed = nil
                        AlbumPullHaptics.prepare()
                    }
                    motion.p = min(1, max(0, (dragBase ?? 0) - t.y / travel))
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
        if !deferredReady { deferredReady = true }
        if motion.pageBase == nil {
            pageSpring.stop()
            motion.pageMoving = false
            motion.pageBase = motion.page
        }
        motion.page = min(1, max(0, (motion.pageBase ?? 0) - dx / max(width, 1)))
    }

    private func pagerReleased(_ vx: CGFloat, cancelled: Bool, width: CGFloat) {
        guard motion.pageBase != nil else { return }
        motion.pageBase = nil
        // Pages per second; a fling (Compose pager: > 400 dp/s) turns the
        // page its way, a slow release snaps to the nearer one.
        let velocity = cancelled ? 0 : -vx / max(width, 1)
        let target: CGFloat = abs(vx) > 400 && !cancelled ? (vx < 0 ? 1 : 0) : (motion.page > 0.5 ? 1 : 0)
        settlePage(to: target, velocity: velocity)
    }

    /// The pager's release (and the page dots): Yoin's spatial spring from
    /// here, the drag's velocity carried in, on the same progress the drag moved.
    private func settlePage(to target: CGFloat, velocity: CGFloat) {
        if !deferredReady { deferredReady = true }
        motion.pageMoving = true
        motion.pageTarget = target
        pageSpring.run(from: motion.page, to: target, velocity: velocity, apply: { v in
            motion.page = min(1, max(0, v))
        }, completion: { motion.pageMoving = false })
    }

    /// Where a release here would settle, by position alone: past `pullSnap`
    /// of the travel from where the drag started.
    private var pullWouldExpand: Bool {
        (dragBase ?? 0) > 0.5 ? motion.p > 1 - pullSnap : motion.p > pullSnap
    }

    private func settlePull(to target: CGFloat, velocity: CGFloat) {
        if !deferredReady { deferredReady = true }
        // The band lands once (the spring's first touch of the top, not every
        // frame of its settle), and only when it is forming, not already formed.
        pullLanding = target >= 1 && motion.p < 0.98
        pullSpring.run(from: motion.p, to: target, velocity: velocity) { v in
            motion.p = min(1, max(0, v))
            if pullLanding && v >= 0.995 {
                pullLanding = false
                AlbumPullHaptics.landed()
            }
        }
    }

    // MARK: Math

    nonisolated static func smooth(_ x: CGFloat, _ a: CGFloat = 0, _ b: CGFloat = 1) -> CGFloat {
        let t = min(1, max(0, (x - a) / (b - a)))
        return t * t * (3 - 2 * t)
    }

    nonisolated static func lerp(_ a: CGFloat, _ b: CGFloat, _ t: CGFloat) -> CGFloat { a + (b - a) * t }

    nonisolated static func lerpRect(_ a: CGRect, _ b: CGRect, _ t: CGFloat) -> CGRect {
        CGRect(x: lerp(a.minX, b.minX, t), y: lerp(a.minY, b.minY, t),
               width: lerp(a.width, b.width, t), height: lerp(a.height, b.height, t))
    }
}

// MARK: - Isolated motion subviews and modifiers

private struct AlbumPage0OffsetModifier: ViewModifier {
    var motion: AlbumDetailMotionState
    var width: CGFloat
    func body(content: Content) -> some View {
        content.offset(x: -motion.pageFrac * width)
    }
}

private struct AlbumScrapbookOffsetModifier: ViewModifier {
    var motion: AlbumDetailMotionState
    var width: CGFloat
    func body(content: Content) -> some View {
        content.offset(x: (1 - motion.pageFrac) * width)
    }
}

private struct AlbumHeroPullModifier: ViewModifier {
    var motion: AlbumDetailMotionState
    var coverFlies: Bool
    func body(content: Content) -> some View {
        let p = motion.p
        let e = AlbumDetailView.smooth(p)
        let f = motion.pageFrac
        let heroFade = coverFlies ? min(1, max(0, 1 - f * 3.5)) : 1
        content
            .opacity((1 - AlbumDetailView.smooth(p, 0, AlbumDetailView.axisOutEnd)) * heroFade)
            .offset(y: -e * AlbumDetailView.axisTravel)
            .allowsHitTesting(e < 0.5)
    }
}

private struct AlbumTrackListPullModifier: ViewModifier {
    var motion: AlbumDetailMotionState
    func body(content: Content) -> some View {
        let p = motion.p
        let e = AlbumDetailView.smooth(p)
        content
            .opacity(AlbumDetailView.smooth(p, AlbumDetailView.axisInStart, 1))
            .offset(y: (1 - e) * AlbumDetailView.axisTravel)
            .allowsHitTesting(e >= 0.5)
    }
}

private struct AlbumPullRevealFadeModifier: ViewModifier {
    var motion: AlbumDetailMotionState
    func body(content: Content) -> some View {
        content.opacity(max(0, (AlbumDetailView.smooth(motion.p) - 0.6) / 0.4))
    }
}

private struct AlbumBandBlurMotion: ViewModifier {
    var motion: AlbumDetailMotionState
    var topBar: Int
    var zoneTop: CGFloat
    var zoneHeight: CGFloat

    @ViewBuilder
    func body(content: Content) -> some View {
        if topBar != 0 || zoneHeight <= 0 {
            content
        } else {
            let bandSoft = AlbumDetailView.smooth(motion.p, 0.7, 1)
            if bandSoft <= 0.001 {
                content
            } else {
                content.modifier(AlbumBandBlur(zoneTop: zoneTop, zoneHeight: zoneHeight,
                                               radius: AlbumDetailView.bandBlurRadius * bandSoft))
            }
        }
    }
}

private struct AlbumHeaderTwinOpacityModifier: ViewModifier {
    var motion: AlbumDetailMotionState
    var spectrumBar: Bool
    var isWhite: Bool
    func body(content: Content) -> some View {
        let white = spectrumBar ? AlbumDetailView.smooth(motion.p, 0.25, 0.7) : 0
        let f = motion.pageFrac
        content.opacity(isWhite ? white * (1 - f) : 1 - white * (1 - f))
    }
}

private struct AlbumTitlePullPaddingModifier: ViewModifier {
    var motion: AlbumDetailMotionState
    var hasPlaylistMenu: Bool
    func body(content: Content) -> some View {
        let pad = (Tokens.detail.ALBUM_STAMP.cg + Tokens.detail.ALBUM_BAR_TITLE_GAP.cg
                   + (hasPlaylistMenu ? Tokens.detail.PLAYLIST_MENU_GAP.cg : 0))
            * AlbumDetailView.smooth(motion.p)
        content.padding(.trailing, pad)
    }
}

private struct AlbumStripTitleInkModifier: ViewModifier {
    var motion: AlbumDetailMotionState
    var enabled: Bool
    var primary: Color
    var onSurface: Color
    @ViewBuilder
    func body(content: Content) -> some View {
        if enabled {
            let ink = primary.mix(with: onSurface, by: AlbumDetailView.smooth(motion.p, 0.4, 0.8), in: .perceptual)
            content.foregroundStyle(ink)
        } else {
            content.foregroundStyle(primary)
        }
    }
}

private struct AlbumPageDots: View {
    var motion: AlbumDetailMotionState
    var onColor: Color
    var offColor: Color
    var onTap: (Int) -> Void

    var body: some View {
        let f = motion.pageFrac
        HStack(spacing: 0) {
            ForEach(0..<2, id: \.self) { i in
                let active = 1 - min(1, abs(f - CGFloat(i)))
                Button { onTap(i) } label: {
                    Circle()
                        .fill(offColor.mix(with: onColor, by: active, in: .perceptual))
                        .frame(width: 8, height: 8)
                        .frame(width: 24, height: 20)
                        .contentShape(.rect)
                }
                .buttonStyle(.plain).pointerHighlight()
                .accessibilityLabel(i == 0 ? String(localized: "Overview") : String(localized: "Scrapbook"))
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 5)
    }
}

private struct AlbumBackButton: View {
    var motion: AlbumDetailMotionState
    var spectrumBar: Bool
    var tinted: Bool
    var dark: Bool
    var s: CoverScheme
    var app: CoverScheme
    var onBack: () -> Void

    var body: some View {
        let white = spectrumBar ? AlbumDetailView.smooth(motion.p, 0.25, 0.7) : 0
        let mix = white * (1 - motion.pageFrac)
        let base: Color = !tinted ? app.onSurface : dark ? s.primary : s.onPrimaryContainer
        let onSpectrum = mix > 0.5
        let glass: Glass = tinted
            ? .regular.tint(dark ? s.surfaceContainerHighest.opacity(0.7) : s.primaryContainer.opacity(0.6)).interactive()
            : .regular.tint(.white.opacity(0.18 * mix)).interactive()
        Button(action: onBack) {
            YoinIcon("back", size: 22)
                .foregroundStyle(onSpectrum ? .white : base)
                .animation(Yoin.effects, value: onSpectrum)
                .frame(width: 40, height: 40)
                .contentShape(.circle)
        }
        .buttonStyle(.plain).pointerHighlight()
        .yGlassEffect(glass, in: .circle)
        .accessibilityLabel("Back")
    }
}

private struct AlbumButterflyView: View {
    var motion: AlbumDetailMotionState
    var primary: Color
    var secondary: Color
    var surface: Color
    var headerContent: CGFloat
    var w: CGFloat
    var band: CGFloat
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let leaving = max(AlbumDetailView.smooth(motion.p, 0, 0.7),
                          AlbumDetailView.smooth(motion.pageFrac, 0.45, 1))
        let height = headerContent + band * 1.9
        YoinArmsCanvas(arms: [(0, primary), (2, secondary)],
                       lineColor: surface.opacity(0.65),
                       scale: 1.8, translationY: -0.1 * band, squashX: 1.35,
                       box: CGRect(x: w, y: headerContent, width: w, height: band))
            .frame(width: w * 3, height: height)
            .mask(alignment: .top) {
                Rectangle().padding(.top, headerContent)
            }
            .dotDissolve(reduceMotion ? 0 : leaving)
            .opacity(reduceMotion ? 1 - leaving : 1)
            .offset(x: -w)
            .frame(width: w, height: height, alignment: .topLeading)
            .allowsHitTesting(false)
    }
}

private struct AlbumSleevesView: View {
    var motion: AlbumDetailMotionState
    var covers: [CoverPalette]
    var seed: Int
    var w: CGFloat
    var side: CGFloat
    var headerContent: CGFloat
    @Binding var sleevesOpen: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        if !covers.isEmpty {
            let cover = CGRect(x: (w - side) / 2, y: headerContent + 28, width: side, height: side)
            let open: CGFloat = reduceMotion || sleevesOpen ? 1 : 0
            let grow = 0.6 + 0.4 * open
            let halfExtent: CGFloat = 0.43 * (cos(6.3 * CGFloat.pi / 180) + sin(6.3 * CGFloat.pi / 180))
            let spread = min(0.2, max(0, ((w - side) / 2 - 8) / side + 0.5 - halfExtent))
            let gap = max(3, side * 0.025)
            let corner = Tokens.shape.HERO.cg
            let p = motion.p
            let leaving = AlbumDetailView.smooth(p, 0, 0.7)
            let coverRing = 1 - AlbumDetailView.smooth(p, 0, 0.2)
            let pose = { (i: Int) -> (center: CGPoint, side: CGFloat, degrees: Double) in
                let dir: CGFloat = i == 0 ? -1 : 1
                return (CGPoint(x: cover.midX + dir * spread * side * open, y: cover.midY + 0.07 * side * open),
                        side * 0.86 * grow, Double(dir * 6.3 * open))
            }
            let height = cover.maxY + side * 0.3
            ZStack(alignment: .topLeading) {
                ForEach(Array(covers.enumerated()), id: \.offset) { i, palette in
                    let me = pose(i)
                    CoverArt(palette: palette, cornerRadius: 0, seed: seed + 7 + i)
                        .frame(width: me.side, height: me.side)
                        .clipShape(.rect(cornerRadius: corner, style: .continuous))
                        .position(me.center)
                        .frame(width: w, height: height, alignment: .topLeading)
                        .rotationEffect(.degrees(me.degrees),
                                        anchor: UnitPoint(x: me.center.x / w, y: me.center.y / height))
                        .mask {
                            ZStack(alignment: .topLeading) {
                                Rectangle()
                                RoundedRectangle(cornerRadius: corner + gap, style: .continuous)
                                    .frame(width: cover.width + gap * 2, height: cover.height + gap * 2)
                                    .position(x: cover.midX, y: cover.midY)
                                    .opacity(coverRing)
                                    .blendMode(.destinationOut)
                                if i == 0 && covers.count > 1 {
                                    let front = pose(1)
                                    RoundedRectangle(cornerRadius: corner + gap, style: .continuous)
                                        .frame(width: front.side + gap * 2, height: front.side + gap * 2)
                                        .rotationEffect(.degrees(front.degrees))
                                        .position(front.center)
                                        .blendMode(.destinationOut)
                                }
                            }
                            .compositingGroup()
                        }
                        .opacity(min(1, open * 1.4))
                }
            }
            .frame(width: w, height: height, alignment: .topLeading)
            .drawingGroup()
            .dotDissolve(reduceMotion ? 0 : leaving)
            .opacity(reduceMotion ? 1 - AlbumDetailView.smooth(p, 0, 0.6) : 1)
            .allowsHitTesting(false)
            .onAppear {
                guard !sleevesOpen else { return }
                withAnimation(Yoin.spatial.delay(0.05)) { sleevesOpen = true }
            }
        }
    }
}

private struct AlbumFlyingCover: View {
    var motion: AlbumDetailMotionState
    var album: Album
    var w: CGFloat
    var side: CGFloat
    var headerContent: CGFloat
    var isPlaylist: Bool
    var isOverview: Bool
    var overviewSide: CGFloat
    var reduceMotion: Bool
    var scrapCover: CGRect?
    var scrapCoverTilt: Double
    var onTapStamp: () -> Void

    var body: some View {
        let e = AlbumDetailView.smooth(motion.p)
        let f = motion.pageFrac
        let heroRect = CGRect(x: (w - side) / 2, y: headerContent + 28, width: side, height: side)
        let stamp = Tokens.detail.ALBUM_STAMP.cg
        let stampEnd = Tokens.detail.ALBUM_STAMP_END_INSET.cg + (isPlaylist ? Tokens.detail.PLAYLIST_MENU_RESERVE.cg : 0)
        let stampRect = CGRect(x: w - stampEnd - stamp, y: 4 + 2, width: stamp, height: stamp)
        let r = CGRect(x: AlbumDetailView.lerp(heroRect.minX, stampRect.minX, e),
                       y: AlbumDetailView.lerp(heroRect.minY, stampRect.minY, e),
                       width: AlbumDetailView.lerp(side, stamp, e),
                       height: AlbumDetailView.lerp(side, stamp, e))
        let target = !isPlaylist && !reduceMotion ? scrapCover : nil
        let toPage = target == nil ? 0 : f
        let flown = if let target, toPage > 0 {
            AlbumDetailView.lerpRect(r, target, toPage)
        } else {
            r.offsetBy(dx: -f * w, dy: 0)
        }
        let coverFlies = !isPlaylist && !reduceMotion && scrapCover != nil
        Button {
            if e > 0.5 { onTapStamp() }
        } label: {
            CoverArt(palette: album.palette, cornerRadius: 0, seed: album.seed)
                .frame(width: flown.width, height: flown.height)
                .clipShape(.rect(cornerRadius: AlbumDetailView.lerp(Tokens.shape.HERO.cg, Tokens.shape.COVER.cg, e),
                                 style: .continuous))
                .labCoverShadow()
        }
        .buttonStyle(.plain).pointerHighlight()
        .allowsHitTesting(e > 0.5 && f < 0.5)
        .accessibilityLabel("Show cover")
        .rotationEffect(.degrees(scrapCoverTilt * Double(toPage)))
        .opacity(coverFlies && f >= 0.999 ? 0 : 1)
        .opacity(isOverview && f < 0.001 ? Double(AlbumDetailView.smooth(motion.listScroll / overviewSide, 0.4, 0.95)) : 1)
        .offset(x: flown.minX, y: flown.minY)
    }
}

private struct AlbumBandTickObserver: View {
    var motion: AlbumDetailMotionState
    var album: Album
    var topBar: Int

    var body: some View {
        Color.clear
            .onChange(of: motion.p) { was, now in
                guard topBar != 1 && topBar != 2, now != was, motion.page <= 0.001 else { return }
                let landings = AlbumSpectrum.entry(for: album).bandLandings
                for t in landings where (was < t) != (now < t) {
                    AlbumPullHaptics.band()
                }
            }
    }
}

private struct AlbumTopBarEffectOverlay: View {
    var motion: AlbumDetailMotionState
    var album: Album
    var topBar: Int
    var topTint: Color
    var w: CGFloat
    var side: CGFloat
    var top: CGFloat
    var headerContent: CGFloat
    var listTop: CGFloat
    var bandFade: CGFloat
    var stripBar: Bool
    var stripExtend: CGFloat
    var stripFeather: CGFloat
    var stripBlur: CGFloat
    var allowSyncMock: Bool
    @Environment(\.colorScheme) private var colorScheme

    private var s: CoverScheme { CoverScheme(album: album, dark: colorScheme == .dark) }

    var body: some View {
        let p = motion.p
        switch topBar {
        case 1:
            Color.clear
                .frame(width: w, height: top + headerContent)
                .yGlassEffect(.regular.tint(s.surfaceContainer.opacity(0.35)),
                             in: UnevenRoundedRectangle(bottomLeadingRadius: 28, bottomTrailingRadius: 28,
                                                        style: .continuous))
                .opacity(AlbumDetailView.smooth(p, 0.3, 0.85))
                .offset(y: -top)
                .allowsHitTesting(false)
        case 2:
            ProgressiveEdgeBlur(tint: topTint)
                .frame(width: w, height: top + listTop + 4)
                .offset(y: -top)
                .opacity(AlbumDetailView.smooth(p, 0.3, 0.85))
        case 4:
            ProgressiveEdgeBlur(tint: topTint, strong: true)
                .frame(width: w, height: top + listTop + 4)
                .offset(y: -top)
                .opacity(AlbumDetailView.smooth(p, 0.3, 0.85))
            spectrum(p: p)
        default:
            spectrum(p: p)
        }
    }

    @ViewBuilder
    private func spectrum(p: CGFloat) -> some View {
        if p > 0.001 {
            AlbumSpectrumCanvas(
                p: p,
                album: album,
                w: w,
                side: side,
                top: top,
                headerContent: headerContent,
                bandFade: bandFade,
                stripBar: stripBar,
                stripExtend: stripExtend,
                stripFeather: stripFeather,
                stripBlur: stripBlur,
                allowSyncMock: allowSyncMock
            )
        }
    }
}

private struct AlbumSpectrumCanvas: View {
    var p: CGFloat
    var album: Album
    var w: CGFloat
    var side: CGFloat
    var top: CGFloat
    var headerContent: CGFloat
    var bandFade: CGFloat
    var stripBar: Bool
    var stripExtend: CGFloat
    var stripFeather: CGFloat
    var stripBlur: CGFloat
    var allowSyncMock: Bool

    var body: some View {
        let entry = AlbumSpectrum.entry(for: album, allowSyncMock: allowSyncMock)
        let n = AlbumSpectrum.strips
        let slice = side / CGFloat(n)
        let heroX = (w - side) / 2
        let heroY = headerContent + 28
        let barH = stripBar ? top + stripExtend + stripFeather : top + headerContent + bandFade
        let blurR = stripBar ? stripBlur * AlbumDetailView.smooth(p, 0.3, 0.85) : 0
        let coverImage = album.palette.cover.flatMap { CoverArtwork.shared.image($0) }
        let canvasH = top + heroY + max(side, barH + 24) + 8
        let maskNeeded = stripBar || bandFade > 0

        Canvas { gc, _ in
            let resolvedCover = coverImage.map { gc.resolve(Image(uiImage: $0)) }
            let imgSize = coverImage?.size ?? .zero
            let isServerCover = album.palette.cover != nil
            let placeholderColor = isServerCover
                ? album.palette.b.mix(with: album.palette.a, by: 0.25, in: .perceptual)
                : .clear

            for i in 0..<n {
                let st = entry.strips[i]
                let targetColor = entry.isFallback ? album.palette.a : st.color
                let lag = 0.22 * CGFloat(st.slot) / CGFloat(n)
                let q = AlbumDetailView.smooth(p, 0.06 + lag, 0.6 + lag)
                let flat = AlbumDetailView.smooth(q, 0, 0.55)
                let x = AlbumDetailView.lerp(heroX + CGFloat(i) * slice, CGFloat(st.slot) * w / CGFloat(n), q)
                let y = AlbumDetailView.lerp(heroY, -top, q) + top
                let sw = AlbumDetailView.lerp(slice, w / CGFloat(n), q) + (stripBar ? 6 : 1.5)
                let sh = AlbumDetailView.lerp(side, stripBar ? barH + 24 : barH, q)
                let rect = CGRect(x: x, y: y, width: sw, height: sh)
                let soft = stripBar ? max(1, min(48, sh * 0.3) * AlbumDetailView.smooth(q, 0.35, 0.75)) : 0

                if flat >= 0.999 {
                    if stripBar && soft > 1.5 {
                        Self.fillSoftTailColor(targetColor, in: rect, soft: soft, gc: &gc)
                    } else {
                        gc.fill(Path(rect), with: .color(targetColor))
                    }
                } else if stripBar && soft > 1.5 {
                    gc.drawLayer { layer in
                        Self.drawCoverSlice(
                            i: i, n: n, side: side, slice: slice, rect: rect,
                            resolvedCover: resolvedCover, imgSize: imgSize,
                            isServerCover: isServerCover, placeholder: placeholderColor,
                            palette: album.palette, opacity: 1 - flat, gc: &layer
                        )
                        if flat > 0.001 {
                            var colorLayer = layer
                            colorLayer.opacity = Double(flat)
                            colorLayer.fill(Path(rect), with: .color(targetColor))
                        }
                        layer.blendMode = .destinationOut
                        Self.fillTailAlpha(in: rect, soft: soft, gc: &layer)
                    }
                } else {
                    Self.drawCoverSlice(
                        i: i, n: n, side: side, slice: slice, rect: rect,
                        resolvedCover: resolvedCover, imgSize: imgSize,
                        isServerCover: isServerCover, placeholder: placeholderColor,
                        palette: album.palette, opacity: 1 - flat, gc: &gc
                    )
                    if flat > 0.001 {
                        var colorCtx = gc
                        colorCtx.opacity = Double(flat)
                        colorCtx.fill(Path(rect), with: .color(targetColor))
                    }
                }
            }
        }
        .frame(width: w, height: canvasH, alignment: .topLeading)
        .compositingGroup()
        .layerEffect(ShaderLibrary.albumStripBlur(.float(max(1, barH)), .float(blurR), .float(w)),
                     maxSampleOffset: CGSize(width: blurR * 1.5, height: 0), isEnabled: blurR > 0.3)
        .mask(alignment: .topLeading) {
            if maskNeeded {
                bandMask(barH: barH, canvasH: canvasH)
            } else {
                Rectangle().frame(width: w + 40, height: canvasH).offset(x: -20)
            }
        }
        .offset(y: -top)
        .allowsHitTesting(false)
    }

    @ViewBuilder
    private func bandMask(barH: CGFloat, canvasH: CGFloat) -> some View {
        if stripBar {
            let fade = top + stripExtend + stripFeather
            let k = AlbumDetailView.smooth(p, 0.3, 0.95)
            let far = top + headerContent + 28 + Tokens.detail.ALBUM_COVER_MAX.cg + 40
            let end = AlbumDetailView.lerp(far, fade, k)
            let tailH = max(barH + 40, canvasH - end + 16)
            VStack(spacing: 0) {
                LinearGradient(stops: (0...12).map { i in
                    let t = CGFloat(i) / 12
                    let ease = t * t * (3 - 2 * t)
                    return .init(color: .black.opacity(1 - ease * k), location: t)
                }, startPoint: .top, endPoint: .bottom)
                .frame(height: end)
                Rectangle().opacity(1 - k).frame(height: tailH)
            }
            .frame(width: w + 40)
            .offset(x: -20)
        } else {
            let k = AlbumDetailView.smooth(p, 0.7, 1)
            let tailH = max(barH + 40, canvasH - (top + headerContent + bandFade) + 16)
            VStack(spacing: 0) {
                Rectangle().frame(height: top + headerContent)
                LinearGradient(stops: (0...8).map { i in
                    let t = CGFloat(i) / 8
                    let ease = t * t * (3 - 2 * t)
                    return .init(color: .black.opacity(1 - ease * k), location: t)
                }, startPoint: .top, endPoint: .bottom)
                .frame(height: bandFade)
                Rectangle().opacity(1 - k).frame(height: tailH)
            }
            .frame(width: w + 40)
            .offset(x: -20)
        }
    }

    private static func fillSoftTailColor(_ color: Color, in rect: CGRect, soft: CGFloat, gc: inout GraphicsContext) {
        let h = max(rect.height, 1)
        let solidFrac = max(0, (h - soft) / h)
        var stops: [Gradient.Stop] = [
            .init(color: color, location: 0),
            .init(color: color, location: solidFrac),
        ]
        for j in 1...8 {
            let t = CGFloat(j) / 8
            let alpha = 1 - t * t * (3 - 2 * t)
            stops.append(.init(color: color.opacity(Double(alpha)), location: solidFrac + (1 - solidFrac) * t))
        }
        gc.fill(Path(rect),
                with: .linearGradient(Gradient(stops: stops),
                                      startPoint: CGPoint(x: rect.minX, y: rect.minY),
                                      endPoint: CGPoint(x: rect.minX, y: rect.maxY)))
    }

    private static func fillTailAlpha(in rect: CGRect, soft: CGFloat, gc: inout GraphicsContext) {
        let startY = max(rect.minY, rect.maxY - soft)
        let stops: [Gradient.Stop] = (0...8).map { j in
            let t = CGFloat(j) / 8
            let eraseAlpha = t * t * (3 - 2 * t)
            return .init(color: .black.opacity(Double(eraseAlpha)), location: t)
        }
        gc.fill(Path(CGRect(x: rect.minX, y: startY, width: rect.width, height: rect.maxY - startY)),
                with: .linearGradient(Gradient(stops: stops),
                                      startPoint: CGPoint(x: rect.minX, y: startY),
                                      endPoint: CGPoint(x: rect.minX, y: rect.maxY)))
    }

    private static func drawCoverSlice(
        i: Int, n: Int, side: CGFloat, slice: CGFloat, rect: CGRect,
        resolvedCover: GraphicsContext.ResolvedImage?, imgSize: CGSize,
        isServerCover: Bool, placeholder: Color, palette: CoverPalette,
        opacity: CGFloat, gc: inout GraphicsContext
    ) {
        guard opacity > 0.001 else { return }
        var ctx = gc
        ctx.opacity = Double(opacity)
        if let resolvedCover, imgSize.width > 0, imgSize.height > 0 {
            let aspect = imgSize.width / imgSize.height
            let baseW = aspect >= 1 ? side * aspect : side
            let baseH = aspect >= 1 ? side : side / aspect
            let baseX = (side - baseW) / 2
            let baseY = (side - baseH) / 2
            let sx = rect.width / max(slice, 0.001)
            let sy = rect.height / max(side, 0.001)
            let drawRect = CGRect(
                x: rect.minX + (baseX - CGFloat(i) * slice) * sx,
                y: rect.minY + baseY * sy,
                width: baseW * sx,
                height: baseH * sy
            )
            ctx.clip(to: Path(rect))
            ctx.draw(resolvedCover, in: drawRect)
        } else if isServerCover {
            ctx.fill(Path(rect), with: .color(placeholder))
        } else {
            let u = (CGFloat(i) + 0.5) / CGFloat(n)
            let topColor: Color
            let midColor: Color
            let botColor: Color
            if u <= 0.5 {
                let t = u * 2
                topColor = palette.a
                midColor = palette.c.mix(with: palette.a, by: t, in: .perceptual)
                botColor = palette.c
            } else {
                let t = (u - 0.5) * 2
                topColor = palette.a.mix(with: palette.b, by: t, in: .perceptual)
                midColor = palette.a.mix(with: palette.b, by: t, in: .perceptual)
                botColor = palette.c.mix(with: palette.a, by: t, in: .perceptual)
            }
            ctx.fill(Path(rect),
                     with: .linearGradient(Gradient(colors: [topColor, midColor, botColor]),
                                           startPoint: CGPoint(x: rect.midX, y: rect.minY),
                                           endPoint: CGPoint(x: rect.midX, y: rect.maxY)))
        }
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
/// What the tip page's demo phone asks of an album page (`AlbumDetailView(album:demo:)`).
struct AlbumPageDemo {
    /// The track (by its number) drawn with the note mark; nil: none.
    var notedTrack: Int? = nil
    /// A row was held (its index).
    var onLongPress: (Int) -> Void
}

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

    @ViewBuilder
    func body(content: Content) -> some View {
        if radius <= 0.3 || zoneHeight <= 0 {
            content
        } else {
            let zoneTop = zoneTop, zoneHeight = zoneHeight, radius = radius
            content.visualEffect { c, proxy in
                let f = proxy.frame(in: .named(AlbumDetailView.pageSpace))
                let on = f.maxY > zoneTop - radius && f.minY < zoneTop + zoneHeight
                return c.layerEffect(ShaderLibrary.albumBandBlur(.float(zoneTop - f.minY), .float(max(1, zoneHeight)),
                                                                 .float(radius)),
                                     maxSampleOffset: CGSize(width: radius, height: radius), isEnabled: on)
            }
        }
    }
}

/// The album page's pull-up beats (51, 2026-11-06): a firm one when the drag
/// passes the point where a release forms the spectrum band, a lighter one
/// when it passes back (a release folds the band), and a soft one when the
/// band lands at the top. Folding back down lands silently (the bands still
/// tick as they leave, see `bandTicks`).
@MainActor
enum AlbumPullHaptics {
    private static let form = UIImpactFeedbackGenerator(style: .medium)
    private static let fold = UIImpactFeedbackGenerator(style: .light)
    private static let land = UIImpactFeedbackGenerator(style: .soft)

    static func prepare() {
        form.prepare()
        fold.prepare()
        tick.prepare()
        land.prepare()
    }

    static func armed(expanding: Bool) {
        if expanding { form.impactOccurred(intensity: 0.8) } else { fold.impactOccurred(intensity: 0.6) }
    }

    static func landed() { land.impactOccurred(intensity: 0.6) }

    /// One band of the spectrum landing: faint, several in a row.
    static func band() { tick.impactOccurred(intensity: 0.35) }
    private static let tick = UIImpactFeedbackGenerator(style: .rigid)
}

extension EnvironmentValues {
    /// The page sits in iPad's detail column (PadShell).
    @Entry var inPadColumn = false
}

/// A playlist page's header as a drop target (YoinDrag): what's dropped on it goes into the playlist, lit
/// behind the header while a drag is over it. An album's header, or a playlist the user can't write, takes
/// nothing.
private struct PlaylistHeaderDrop: ViewModifier {
    var playlist: PlaylistInfo?
    var ink: Color
    @Environment(AppModel.self) private var model

    func body(content: Content) -> some View {
        if let playlist, YoinDrag.available {
            // The whole bar takes the drop, not only its title and buttons: the empty stretch between them
            // (lit while a drag is over it) had nothing to hit, so a drop there fell through.
            content
                .contentShape(.rect)
                .yoinDropTarget(in: RoundedRectangle(cornerRadius: Tokens.shape.LIST_ROW.cg, style: .continuous).inset(by: 4),
                                ink: ink, accepts: YoinDrag.accepts(playlist, model: model)) {
                    YoinDrag.add($0, to: playlist, model: model)
                }
        } else {
            content
        }
    }
}
