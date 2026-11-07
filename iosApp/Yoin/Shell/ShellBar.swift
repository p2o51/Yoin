import SwiftUI

/// Yoin's 3-part button group in Liquid Glass:
/// Home · Now Playing pill · Library, plus a separate Search circle.
/// On a pushed album page the same glass pieces morph into
/// [Play | ▾] · mini pill.
///
/// Colors (51, 2026-11-03):
/// - Unselected buttons: plain glass that picks up the content underneath.
/// - Selected button, default (Apple Music iOS 26): the icon and label take
///   the accent color, on a subtle lighter selection pill inside the glass.
///   Glass Lab option: the accent tinted into the glass instead.
/// - Accent: Home = primary, Library = secondary. Nothing playing → Yoin's own
///   scheme (seed 0xFF7B3FE4); playing → the cover's scheme.
/// - Nothing playing: the pill folds away and Home / Library split the bar
///   with labels (Android idle bar).
/// - Pill progress: Android NowPlayingPill's wash, primary at 25 % from the
///   leading edge with a moving sine edge (4 pt).
struct ShellBar: View {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme
    var npNamespace: Namespace.ID
    @Namespace private var glassNS

    private let height: CGFloat = 60
    private var lab: GlassLab { GlassLab.shared }
    /// A long-press fired during the current touch on the pill / Library:
    /// the release that ends it must not also tap. Cleared when the next
    /// touch goes down, so it only ever guards its own touch's release and
    /// never eats a later, real tap (a time window did: a tap soon after the
    /// song sheet closed was swallowed).
    @State private var longPressed: ShellLongPress?
    @State private var longPresses = 0
    /// The detail page's public link, for the ▾ menu's Share and Open in.
    @State private var webLink = DetailWebLinkState()

    private enum ShellLongPress { case pill, library }

    /// Android YoinButtonGroup: Library long-press, the pill's long-press.
    private func longPress(_ which: ShellLongPress, _ action: @escaping () -> Void) -> some Gesture {
        LongPressGesture(minimumDuration: 0.45)
            // Touch down: a new touch, whatever the last one did.
            .onChanged { _ in
                if longPressed == which { longPressed = nil }
                // The pill: Now Playing builds while the finger is down.
                if which == .pill, !model.isPad, !model.isIdle { model.np.prewarm() }
            }
            .onEnded { _ in
                longPressed = which
                longPresses += 1
                action()
            }
    }

    /// The tap, unless it is the release of this touch's long-press.
    private func tap(_ which: ShellLongPress, _ action: () -> Void) {
        if longPressed == which {
            longPressed = nil
            return
        }
        action()
    }

    /// Android onLibraryLongClick: straight to Library's search, empty, the
    /// keyboard up (Search focuses its field as it opens).
    private func openLibrarySearch() {
        model.select(.library)
        // Apple Music: the whole catalog (Android LibrarySearchScope.AppleMusicGlobal).
        model.openSearch(from: .library, catalog: model.store.searchesCatalog)
    }

    /// Android onNowPlayingLongClick (owner 2026-11-03, as Apple Music's mini
    /// player): the playing song's sheet — the one a row's long-press opens,
    /// in Now Playing context (Favorite / Add to playlist / Share on top).
    private func openPlayingSongMenu() {
        let p = model.playback
        PlaylistActions.shared.openMenu(PlaylistEntry(album: p.album, track: p.track), context: .nowPlaying)
    }

    private var brand: CoverScheme { .brand(dark: colorScheme == .dark) }
    private var scheme: CoverScheme {
        model.isIdle ? brand : CoverScheme(album: model.playback.album, dark: colorScheme == .dark)
    }

    var body: some View {
        GlassEffectContainer(spacing: 8) {
            HStack(spacing: 6) {
                if model.isDetail, let album = model.detailAlbum {
                    playSplit(album)
                } else {
                    navButton(.home)
                }
                if !model.isIdle {
                    pill
                }
                if !model.isDetail {
                    navButton(.library)
                    searchButton
                        .padding(.leading, 6)
                }
            }
        }
        .animation(Yoin.spatial, value: model.isDetail)
        .animation(Yoin.spatial, value: model.section)
        .animation(Yoin.spatial, value: model.isIdle)
        .frame(height: height)
        // A song that couldn't be played holds playback on it (Codex A39):
        // say so over the bar, with Retry (the pill's play does the same).
        .overlay(alignment: .bottom) {
            if let failure = model.player.failure {
                PlaybackFailureBanner(failure: failure, scheme: scheme) { model.player.retry() }
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.bottom, height + 8)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(Yoin.spatial, value: model.player.failure)
        .sensoryFeedback(.impact(weight: .medium), trigger: longPresses)
    }

    // MARK: Nav buttons

    private func accent(_ s: ShellSection) -> Color {
        s == .home ? scheme.primary : scheme.secondary
    }

    @ViewBuilder
    private func navButton(_ s: ShellSection) -> some View {
        let selected = model.section == s
        let tintedStyle = lab.extra("barSelectedStyle") >= 0.5
        let icon = switch s {
        case .home: selected ? "home_filled" : "home"
        case .library: selected ? "library_filled" : "library"
        }
        let ink: Color = !selected ? Yoin.ink
            : tintedStyle ? (s == .home ? scheme.onPrimary : scheme.onSecondary)
            : accent(s)
        let button = Button {
            tap(.library) { model.select(s) }
        } label: {
            HStack(spacing: 8) {
                YoinIcon(icon, size: 24)
                if model.isIdle {
                    Text(s == .home ? String(localized: "Home") : String(localized: "Library")).font(.gsf(14, .semibold))
                }
            }
            .foregroundStyle(ink)
            .labContrast(selected ? "barSelected" : "barUnselected")
            .frame(width: model.isIdle ? nil : (selected ? 68 : 54), height: height)
            .frame(maxWidth: model.isIdle ? .infinity : nil)
            .background {
                if selected && !tintedStyle {
                    // Apple-style selection highlight inside the glass.
                    // Grey on light glass (white vanished on it), light on dark.
                    Capsule()
                        .fill((colorScheme == .dark ? Color.white.opacity(0.16) : Color.black.opacity(0.07))
                            .opacity(lab.extra("barSelectionPill") / 0.55))
                        .padding(5)
                }
            }
            .contentShape(.capsule)
        }
        .buttonStyle(.plain)
        .simultaneousGesture(longPress(.library, openLibrarySearch), including: s == .library ? .all : .subviews)
        .accessibilityActions {
            if s == .library { Button("Search library", action: openLibrarySearch) }
        }
        if selected && tintedStyle {
            // Tinted-glass style: force the tint on, in the accent role.
            button
                .glassEffect(.regular.tint(accent(s).opacity(lab.style("barSelected").tintOpacity)).interactive(),
                             in: .capsule)
                .glassEffectID(s == .home ? "home" : "library", in: glassNS)
                .accessibilityLabel(s == .home ? String(localized: "Home") : String(localized: "Library"))
        } else {
            button
                .labGlass(selected ? "barSelected" : "barUnselected", scheme: scheme, brand: brand,
                          auto: accent(s), in: Capsule())
                .glassEffectID(s == .home ? "home" : "library", in: glassNS)
                .accessibilityLabel(s == .home ? String(localized: "Home") : String(localized: "Library"))
        }
    }

    private var searchButton: some View {
        Button { model.openSearch(from: .bar) } label: {
            YoinIcon("search", size: 24)
                .foregroundStyle(Yoin.ink)
                .labContrast("barUnselected")
                .frame(width: height, height: height)
                .contentShape(.circle)
        }
        .buttonStyle(.plain)
        .labGlass("barUnselected", scheme: scheme, brand: brand, auto: scheme.tertiary, in: Circle())
        // Touch-down: Search builds while the finger is down.
        .simultaneousGesture(DragGesture(minimumDistance: 0).onChanged { _ in model.prewarmSearch() })
        .glassEffectID("search", in: glassNS)
        // The search field grows out of this circle: Search draws a stand-in
        // here (where the circle last rested, frozen at the tap) and morphs it
        // into the field, so this one steps out at once. A matched source
        // inside the bar moved with the bar (keyboard, slide-away) and the
        // field chased it mid-screen.
        .opacity(model.searchActive && model.searchOrigin == .bar ? 0 : 1)
        .animation(model.searchActive ? nil : SearchView.fadeThrough, value: model.searchActive)
        .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { frame in
            if !model.searchActive && model.searchCircleFrame != frame { model.searchCircleFrame = frame }
        }
        .accessibilityLabel("Search")
    }

    // MARK: Now Playing pill

    private var pill: some View {
        let p = model.playback
        let progress = Double(p.positionSec) / Double(p.track.durationSec)
        return Button {
            tap(.pill) {
                if model.isPad { model.openNowPlayingOnPad() } else { model.setNowPlaying(expanded: true) }
            }
        } label: {
            PillContent(np: model.isPad ? nil : model.np)
                .contentShape(.capsule)
        }
        .buttonStyle(.plain)
        .simultaneousGesture(longPress(.pill, openPlayingSongMenu))
        .accessibilityAction(named: "Song options", openPlayingSongMenu)
        .labGlass("miniPlayer", scheme: scheme, brand: brand, auto: scheme.primaryContainer, in: Capsule())
        .background {
            // World (R5): the folded room's ambient already breathes in the pill.
            if lab.extra("pillAmbient") > 0, !model.isIdle {
                // Sized by the pill (the ambient's own blobs are page-sized).
                Color.clear
                    .overlay { NowPlayingAmbient(scheme: scheme) }
                    .clipShape(.capsule)
                    .opacity(lab.extra("pillAmbient"))
                    .allowsHitTesting(false)
            }
        }
        .glassEffectID("np", in: glassNS)
        .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { r in
            if !model.isPad { model.np.pillFrame = r }
        }
        // While Now Playing is up (or morphing), the morph's glass shape is
        // the pill: it starts and ends exactly on this frame.
        .opacity(model.np.mounted ? 0 : 1)
        .accessibilityLabel("Now playing, \(p.track.title) by \(p.album.artist)")
        .accessibilityValue(model.player.currentTrack != nil
                            ? (p.isPlaying ? String(localized: "Playing") : String(localized: "Paused"))
                            : "\(Int(progress * 100)) percent played")
    }

    // MARK: Album page form

    /// Android detail pose: [Play split, 156 pt][8][now-playing pill]. Both
    /// halves in the cover's primary / onPrimary; ▾ opens the detail menu.
    /// M3 SplitButton: 2 pt gap, 4 pt inner corners, full outer corners. Own
    /// container with 0 spacing, so the halves don't melt into one blob.
    private func playSplit(_ album: Album) -> some View {
        let s = CoverScheme(album: album, dark: colorScheme == .dark)
        let tint = s.primary.opacity(0.85)
        let inner = Tokens.bar.SPLIT_INNER_CORNER.cg
        return GlassEffectContainer(spacing: 0) { HStack(spacing: Tokens.bar.SPLIT_GAP.cg) {
            Button { playDetail(shuffle: false) } label: {
                HStack(spacing: 8) {
                    YoinIcon("play_filled", size: 20)
                    Text("Play").font(.gsf(16, .medium))
                }
                .foregroundStyle(s.onPrimary)
                .padding(.horizontal, 16)
                .frame(maxWidth: .infinity)
                .frame(height: height)
                .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .glassEffect(.regular.tint(tint).interactive(),
                         in: UnevenRoundedRectangle(topLeadingRadius: 30, bottomLeadingRadius: 30,
                                                    bottomTrailingRadius: inner, topTrailingRadius: inner))
            .glassEffectID("split.play", in: glassNS)
            .glassEffectTransition(.materialize)

            Menu {
                Button("Shuffle play") { playDetail(shuffle: true) }
                Button("Play next") { enqueueDetail(next: true) }
                Button("Add to queue") { enqueueDetail(next: false) }
                // Android: needs playlist writes (Subsonic; not Apple Music).
                if PlaylistActions.shared.canAddToPlaylist(model.store) {
                    Button("Add to playlist") { addDetailToPlaylist() }
                }
                // Android DetailMenu: Save to / Remove from library (Spotify albums).
                if case .album = model.path.last { AlbumLibraryMenuButton(album: album) }
                // Android OfflineToggle: Download, Downloading n/m, Remove download.
                if let c = model.detailOfflineCollection, case .album = model.path.last {
                    OfflineMenuButton(collection: c)
                }
                // The mock's stand-ins; a real account's links come from its service (none on a Subsonic
                // server: no public page).
                if !model.store.isReal { Button("Open in Apple Music") {} }
                DetailLinkMenuItems(target: detailTarget, link: webLink)
                Divider()
                if case .album = model.path.last {
                    Button("Go to artist") { model.openArtist(album.artist, id: album.artistId) }
                }
                if !model.store.isReal { Button("Share") {} }
            } label: {
                YoinIcon("chevron_down", size: 22)
                    .foregroundStyle(s.onPrimary)
                    .padding(.horizontal, 14)
                    .frame(height: height)
                    .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .glassEffect(.regular.tint(tint).interactive(),
                         in: UnevenRoundedRectangle(topLeadingRadius: inner, bottomLeadingRadius: inner,
                                                    bottomTrailingRadius: 30, topTrailingRadius: 30))
            .glassEffectID("split.more", in: glassNS)
            .glassEffectTransition(.materialize)
            .accessibilityLabel("More play options")
        } }
        .frame(width: Tokens.bar.SPLIT_WIDTH.cg, height: height)
        .fixedSize(horizontal: false, vertical: true)
        .task(id: album.id) { await AlbumLibraryState.shared.load(album, store: model.store) }
        .task(id: detailTarget) { await webLink.load(detailTarget, apple: model.store.apple) }
    }

    /// The page the ▾ menu's Share and Open in are about.
    private var detailTarget: DetailLinkTarget? { model.detailLinkTarget() }

    /// The page's songs (album tracks, playlist entries, the artist's Most
    /// Played) into the engine.
    private func playDetail(shuffle: Bool) {
        model.play(loading: { await model.detailEntries() }, shuffle: shuffle)
    }

    private func enqueueDetail(next: Bool) {
        Task { model.enqueue(await model.detailEntries(), next: next) }
    }

    private func addDetailToPlaylist() {
        Task { PlaylistActions.shared.requestAdd(await model.detailEntries()) }
    }
}

/// The mini player's content: cover, title over artist, progress wash.
/// The bar's pill reports its cover / title frames to the Now Playing
/// morph (`np`); the morph draws a copy without cover and title (those fly).
struct PillContent: View {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme
    var np: NowPlayingPresentation? = nil
    var showCover = true
    var showTitle = true
    private let height: CGFloat = 60
    private var lab: GlassLab { GlassLab.shared }

    /// What the pill shows (Android NowPlayingPill `shown`): held through a
    /// song change's exit so the old song leaves as itself.
    struct Shown: Equatable {
        var key: MediaId
        var album: Album
        var title: String
        var artist: String
    }

    @State private var shown: Shown?
    /// Android track-change PUSH: 0 at rest; 0 → 1 carries the old song out
    /// left, then −1 → 0 brings the new one in from the right.
    @State private var push: CGFloat = 0
    @State private var pushGeneration = 0

    /// Android PillPushTravel.
    private static let pushTravel: CGFloat = 30

    private var live: Shown {
        let p = model.playback
        return Shown(key: p.track.id, album: p.album, title: p.track.title, artist: p.album.artist)
    }

    var body: some View {
        let p = model.playback
        let s = shown ?? live
        let scheme = model.isIdle ? CoverScheme.brand(dark: colorScheme == .dark)
            : CoverScheme(album: p.album, dark: colorScheme == .dark)
        HStack(spacing: 10) {
            CoverArt(palette: s.album.palette, cornerRadius: 6, seed: s.album.seed)
                .frame(width: 42, height: 42)
                .opacity(showCover ? 1 : 0)
                .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { r in
                    np?.pillCoverFrame = r
                }
            VStack(alignment: .leading, spacing: 1) {
                // A long title or artist scrolls (Android pill basicMarquee, parity C-U9).
                MarqueeText(text: s.title, font: .gsf(15, .semibold), color: Yoin.ink)
                    .opacity(showTitle ? 1 : 0)
                    .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { r in
                        np?.pillTitleFrame = r
                    }
                MarqueeText(text: s.artist, font: .gsf(13), color: Yoin.inkVariant)
            }
            .lineLimit(1)
            .labContrast("miniPlayer")
            Spacer(minLength: 0)
        }
        .offset(x: -Self.pushTravel * push)
        .opacity(1 - 0.9 * abs(push))
        .padding(.leading, 9)
        .padding(.trailing, 12)
        .frame(maxWidth: .infinity, minHeight: height, maxHeight: height)
        .background {
            // The engine's position is read inside PlaybackPosition, so
            // its ticks redraw the wash only, not the bar. Not while Now
            // Playing is up: the pill is hidden then, and a wash moving in
            // the shell redrew the whole blurred world behind the page every
            // frame.
            if np?.mounted != true {
                PlaybackPosition { position, duration in
                    PillWash(progress: position / duration, playing: p.isPlaying,
                             color: scheme.primary.opacity(lab.extra("washOpacity")),
                             amplitude: lab.extra("washAmplitude"))
                }
                .clipShape(.capsule)
            }
        }
        .onAppear { shown = live }
        .onChange(of: live) { old, new in songChanged(from: old, to: new) }
    }

    /// Every hand-off (skip, auto-advance, another device) pushes; the same
    /// song (metadata or cover refresh) and idle → first song sync silently.
    private func songChanged(from old: Shown, to new: Shown) {
        let instant = Transaction(animation: nil)
        guard old.key != new.key, !model.isIdle, showCover, showTitle else {
            if pushGeneration == 0 || push == 0 { withTransaction(instant) { shown = new } }
            return
        }
        pushGeneration += 1
        let generation = pushGeneration
        // A skip mid-push lands the interrupted frame first, so two pushes
        // never compound; the old song is whatever is on screen now.
        if push != 0 { withTransaction(instant) { push = 0 } }
        // Out: a fast, critically damped exit (Android: 90 ms).
        withAnimation(Self.exitSpring) { push = 1 } completion: {
            guard generation == pushGeneration else { return }
            withTransaction(instant) {
                shown = live
                push = -1
            }
            // In: the Expressive spatial spring.
            withAnimation(Yoin.spatial) { push = 0 } completion: {
                if generation == pushGeneration { pushGeneration = 0 }
            }
        }
    }

    /// Expressive fast effects (critically damped, ~0.1 s): the exit.
    private static var exitSpring: Animation {
        let s = Tokens.motion.expressiveFastEffects
        let k = s.stiffness
        return Animation.interpolatingSpring(mass: 1, stiffness: k, damping: s.damping * 2 * k.squareRoot())
            .speed(LaunchConfig.animationSpeed)
    }
}

/// Android NowPlayingPill progress wash: fill from the leading edge to the
/// progress point, whose edge is a vertical sine (one wave over the pill's
/// height) that drifts while playing.
struct PillWash: View {
    var progress: Double
    var playing: Bool
    var color: Color
    var amplitude: Double

    var body: some View {
        TimelineView(.animation(paused: !playing)) { ctx in
            let phase = ctx.date.timeIntervalSinceReferenceDate * 2 * Double.pi
            Canvas { gc, size in
                guard progress > 0 else { return }
                let x = size.width * progress
                var path = Path()
                path.move(to: .zero)
                path.addLine(to: CGPoint(x: x, y: 0))
                for i in 0...20 {
                    let f = Double(i) / 20
                    let dx = sin(phase + f * 2 * Double.pi) * amplitude * (playing ? 1 : 0)
                    path.addLine(to: CGPoint(x: x + dx, y: f * size.height))
                }
                path.addLine(to: CGPoint(x: 0, y: size.height))
                path.closeSubpath()
                gc.fill(path, with: .color(color))
            }
        }
        .allowsHitTesting(false)
    }
}

/// A playback failure the engine is holding on (PlayerEngine.failure, Android
/// "Playback unavailable" + its message): errorContainer, the message, and an
/// explicit Retry that plays the song again from where it stopped.
struct PlaybackFailureBanner: View {
    var failure: PlaybackFailure
    var scheme: CoverScheme
    var retry: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 0) {
                Text("Playback unavailable")
                    .yoinRole(Tokens.type.titleSmall)
                Text(failure.message)
                    .yoinRole(Tokens.type.bodySmall)
                    .lineLimit(2)
            }
            .foregroundStyle(scheme.onErrorContainer)
            .frame(maxWidth: .infinity, alignment: .leading)
            Button(action: retry) {
                Text("Retry")
                    .yoinRole(Tokens.type.labelLarge)
                    .foregroundStyle(scheme.onError)
                    .padding(.horizontal, 16)
                    .frame(minHeight: 40)
                    .background(scheme.error, in: .capsule)
                    .contentShape(.capsule)
            }
            .buttonStyle(PressScale())
        }
        .padding(.leading, 16)
        .padding(.trailing, 8)
        .padding(.vertical, 8)
        .background(scheme.errorContainer, in: .rect(cornerRadius: Tokens.shape.CARD.cg, style: .continuous))
        .accessibilityElement(children: .combine)
        .accessibilityAction(named: "Retry", retry)
    }
}
