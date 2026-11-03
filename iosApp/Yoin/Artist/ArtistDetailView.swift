import SwiftUI

/// Artist page, ported from Android ui/detail/ArtistDetailScreen.kt
/// (Compact): fixed header (back, name, year span, follow star), the pinwheel
/// hero (the whole Yoin mark rotated 30° around the round portrait, turning
/// with scroll), Most Played, and the discography with kind chips and a year
/// column. One plain scroll: no pull-up, no ratings on this page.
/// Colors: the newest release's cover scheme for the name, star, arms and
/// "Show all"; the app scheme for everything else.
struct ArtistDetailView: View {
    var ref: ArtistRef
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme
    @State private var kindFilter: ReleaseKind?
    @State private var showAll = false
    @State private var scrollY: CGFloat = 0
    @State private var scrollPosition = ScrollPosition(edge: .top)

    /// The server's page when signed in (releases, Most Played by play
    /// count), else the mock artist.
    private var page: ArtistPage { model.artistPage(ref) }
    private var isMock: Bool { page.id.provider == "mock" }
    private var artist: String { page.name }
    private var following: Bool { model.store.isStarred(artist: page) }
    private var releases: [Album] { page.releases }
    private var hero: Album {
        releases.first ?? (isMock ? MockLibrary.allAlbums[0]
            : Album(id: page.id, title: "", artist: page.name, year: 0, palette: page.palette, tracks: []))
    }
    private var s: CoverScheme { CoverScheme(album: hero, dark: colorScheme == .dark) }
    private var app: CoverScheme {
        model.isIdle ? .brand(dark: colorScheme == .dark)
                     : CoverScheme(album: model.playback.album, dark: colorScheme == .dark)
    }

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width
            let portrait = min(Tokens.detail.ARTIST_PORTRAIT_WIDTH_FRACTION.cg * w, Tokens.detail.ARTIST_PORTRAIT_MAX.cg)
            ZStack(alignment: .top) {
                background
                ScrollView {
                    VStack(spacing: 0) {
                        pinwheel(w: w, portrait: portrait)
                            .padding(.top, headerHeight)
                        Spacer().frame(height: 32)
                        VStack(alignment: .leading, spacing: 0) {
                            mostPlayed.padding(.bottom, 32)
                            discography
                        }
                        .padding(.horizontal, 16)
                        .frame(maxWidth: 720)
                        // Android pads the column 120 above the nav bar
                        // (ArtistDetailScreen.kt:112): the last release clears the bar.
                        Spacer().frame(height: Tokens.detail.ARTIST_BOTTOM_PADDING.cg)
                    }
                }
                .onScrollGeometryChange(for: CGFloat.self) { $0.contentOffset.y } action: { _, y in scrollY = y }
                // Content starts under the header and dissolves at its bottom
                // edge (Android: the column sits below the header; tide line).
                .mask {
                    VStack(spacing: 0) {
                        Color.clear.frame(height: headerHeight - 8)
                        LinearGradient(colors: [.clear, .black], startPoint: .top, endPoint: .bottom).frame(height: 16)
                        Color.black
                    }
                    .ignoresSafeArea(edges: .bottom)
                }
                .scrollPosition($scrollPosition)
                .task {
                    // `-screen artist-bottom`: the default anchor settled short of
                    // the end, so scroll there once the page has laid out.
                    guard LaunchConfig.screen == "artist-bottom" else { return }
                    try? await Task.sleep(for: .milliseconds(300))
                    scrollPosition.scrollTo(edge: .bottom)
                }
                .scrollEdgeEffectStyle(.soft, for: .bottom)
                header
                    .background(alignment: .top) {
                        // Tide line under the fixed header: a soft material
                        // band that fades out downward once content scrolls under.
                        Rectangle()
                            .fill(.ultraThinMaterial)
                            .mask(LinearGradient(stops: [.init(color: .black, location: 0),
                                                         .init(color: .black, location: 0.6),
                                                         .init(color: .clear, location: 1)],
                                                 startPoint: .top, endPoint: .bottom))
                            .frame(height: geo.safeAreaInsets.top + headerHeight + 24)
                            .offset(y: -geo.safeAreaInsets.top)
                            .opacity(min(1, max(0, scrollY / 40)))
                            .allowsHitTesting(false)
                    }
            }
        }
        .toolbar(.hidden, for: .navigationBar)
        .task(id: "\(model.store.generation)|\(ref.id ?? ref.name)") {
            guard let id = ref.id, model.store.isReal else { return }
            await model.store.loadArtist(id)
        }
    }

    /// 4 top + the title column (headlineSmall 32 + bodyMedium 20 line boxes,
    /// taller than the 48 buttons) + 6 bottom.
    private var headerHeight: CGFloat {
        4 + max(48, Tokens.type.headlineSmall.lineHeight.cg + (yearSpan == nil ? 0 : Tokens.type.bodyMedium.lineHeight.cg)) + 6
    }

    private var background: some View {
        LinearGradient(colors: [app.surfaceContainer.mix(with: s.primary, by: 0.18, in: .perceptual),
                                app.background, app.surfaceContainerLow],
                       startPoint: .top, endPoint: .bottom)
            .ignoresSafeArea()
    }

    // MARK: Header

    private var yearSpan: String? {
        let years = releases.map(\.year).filter { $0 > 0 }
        guard let lo = years.min(), let hi = years.max() else { return nil }
        return lo == hi ? "\(lo)" : "\(lo) – \(hi)"
    }

    private var header: some View {
        HStack(spacing: 0) {
            Button { model.back() } label: {
                YoinIcon("back", size: 22)
                    .foregroundStyle(app.onSurface)
                    .frame(width: 40, height: 40)
                    .contentShape(.circle)
            }
            .buttonStyle(.plain)
            .glassEffect(.regular.interactive(), in: .circle)
            .frame(width: 48, height: 48)
            .accessibilityLabel("Back")
            Spacer().frame(width: Tokens.detail.HEADER_TITLE_GAP.cg)
            VStack(alignment: .leading, spacing: 0) {
                Text(artist).lineLimit(1)
                    .yoinRole(Tokens.type.headlineSmall)
                    .foregroundStyle(s.primary)
                if let yearSpan {
                    Text(yearSpan).lineLimit(1)
                        .yoinRole(Tokens.type.bodyMedium)
                        .foregroundStyle(app.onSurface)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Button {
                withAnimation(Yoin.effects) { model.store.toggleStar(artist: page) }
            } label: {
                // M3 IconButton: 24 icon in a 40 box; tint primary-of-cover on,
                // onSurfaceVariant off. The tap pops it (tween 90 ms up, spring back).
                YoinIcon(following ? "star_filled" : "star", size: 24)
                    .foregroundStyle(following ? s.primary : app.onSurfaceVariant)
                    .keyframeAnimator(initialValue: 1.0, trigger: following) { v, k in
                        v.scaleEffect(k)
                    } keyframes: { _ in
                        LinearKeyframe(following ? 1.25 : 1.15, duration: 0.09)
                        SpringKeyframe(1.0, duration: 0.4, spring: .init(response: 0.35, dampingRatio: 0.6))
                    }
                    .frame(width: 40, height: 40)
                    .contentShape(.circle)
            }
            .buttonStyle(.plain)
            // Pinned header button over scrolling content: a glass circle.
            .glassEffect(.regular.interactive(), in: .circle)
            .frame(width: 48, height: 48)
            .accessibilityLabel(following ? "Favorited" : "Favorite")
        }
        .padding(.leading, 8).padding(.trailing, 4).padding(.top, 4).padding(.bottom, 6)
    }

    // MARK: Pinwheel hero

    /// Band = width × (portrait + 150). Canvas: translate to the band centre,
    /// rotate 30° + scroll × 0.06 (px), scale portrait × 2.1 / 1024, translate
    /// (−512, −500); arms 0, 1, 2 in tertiary / primary / secondary; centre
    /// lines in surface @ 0.65. The round portrait sits on the hub.
    private func pinwheel(w: CGFloat, portrait: CGFloat) -> some View {
        let degrees = Tokens.detail.ARTIST_MARK_REST_DEGREES.cg + scrollY * 3 * Tokens.detail.ARTIST_MARK_DEGREES_PER_PX.cg
        return ZStack {
            Canvas { gc, size in
                gc.translateBy(x: size.width / 2, y: size.height / 2)
                gc.rotate(by: .degrees(degrees))
                let k = portrait * Tokens.detail.ARTIST_MARK_SCALE.cg / YoinMarkPaths.viewBox
                gc.scaleBy(x: k, y: k)
                gc.translateBy(x: -512, y: -500)
                let colors = [s.tertiary, s.primary, s.secondary]
                for i in 0..<3 {
                    let a = YoinMarkPaths.arms[i]
                    gc.fill(a.fill, with: .color(colors[i]))
                    let line = app.surface.opacity(0.65)
                    if a.lineWidth > 0 {
                        gc.stroke(a.line, with: .color(line), style: StrokeStyle(lineWidth: a.lineWidth, lineCap: .round))
                    } else {
                        gc.fill(a.line, with: .color(line))
                    }
                }
            }
            // Mock: the newest cover; server: the artist's image (else that cover).
            CoverArt(palette: isMock ? hero.palette : page.palette, cornerRadius: portrait / 2, seed: hero.seed + 3)
                .frame(width: portrait, height: portrait)
                .clipShape(.circle)
        }
        .frame(width: w, height: portrait + Tokens.detail.ARTIST_BAND_EXTRA.cg)
    }

    // MARK: Most Played

    private func sectionHeader(_ title: String, _ trailing: String) -> some View {
        HStack(spacing: 0) {
            Text(title).underline()
                .yoinRole(Tokens.type.labelLarge)
                .foregroundStyle(app.onSurface)
            Spacer(minLength: 8)
            Text(trailing)
                .yoinRole(Tokens.type.labelMedium)
                .foregroundStyle(app.onSurfaceVariant)
        }
        .padding(.horizontal, 8)
    }

    private var mostPlayed: some View {
        let picks: [(Album, Track, Int)] = page.mostPlayed.map { ($0.album, $0.track, $0.track.playCount ?? 0) }
        return VStack(alignment: .leading, spacing: 0) {
            if !picks.isEmpty {
            sectionHeader("Most Played", "\(picks.map(\.2).reduce(0, +)) plays")
            Spacer().frame(height: 6)
            }
            ForEach(Array(picks.enumerated()), id: \.offset) { i, item in
                let (a, t, plays) = item
                Button {
                    if isMock {
                        withAnimation(Yoin.effects) {
                            model.playback.album = a
                            model.playback.track = t
                            model.playback.positionSec = 0
                        }
                    } else {
                        model.play(page.mostPlayed, startAt: i)
                    }
                } label: {
                    HStack(spacing: 12) {
                        Text("\(i + 1)").monospacedDigit()
                            .yoinRole(Tokens.type.labelLarge)
                            .foregroundStyle(app.onSurfaceVariant)
                            .frame(minWidth: 16, alignment: .trailing)
                        CoverArt(palette: a.palette, cornerRadius: 0, seed: a.seed)
                            .frame(width: 44, height: 44)
                            .clipShape(.rect(cornerRadius: Tokens.shape.THUMB.cg, style: .continuous))
                        VStack(alignment: .leading, spacing: 2) {
                            Text(t.title).lineLimit(1)
                                .yoinRole(Tokens.type.titleMedium)
                                .foregroundStyle(app.onSurface)
                            LibraryMetaLine(parts: [(a.title, false), (formatTime(t.durationSec), true)], scheme: app)
                                .monospacedDigit()
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        Text(plays == 1 ? "1 play" : "\(plays) plays").monospacedDigit()
                            .yoinRole(Tokens.type.labelLarge)
                            .foregroundStyle(app.onSurfaceVariant)
                    }
                    .padding(8)
                    .contentShape(.rect(cornerRadius: Tokens.shape.LIST_ROW.cg))
                }
                .buttonStyle(PressScale())
            }
        }
    }

    // MARK: Discography

    private var discography: some View {
        let kinds = [ReleaseKind.album, .ep, .compilation].filter { k in
            releases.contains { k == .ep ? ($0.kind == .ep || $0.kind == .single) : $0.kind == k }
        }
        let shown = releases.filter { r in
            switch kindFilter {
            case nil: true
            case .ep, .single: r.kind == .ep || r.kind == .single
            case let k?: r.kind == k
            }
        }
        let collapsed = Int(Tokens.detail.ARTIST_COLLAPSED_COUNT)
        let visible = showAll ? shown : Array(shown.prefix(collapsed))
        return VStack(alignment: .leading, spacing: 0) {
            sectionHeader("Discography", releases.count == 1 ? "1 release" : "\(releases.count) releases")
            if kinds.count >= 2 {
                // Full-bleed with the 16 page margin inside the scroll, so the
                // chips run off the screen edge instead of stopping mid-page
                // (owner rule; Android clips at the section padding with a fade).
                ScrollView(.horizontal) {
                    HStack(spacing: 8) {
                        chip("All", on: kindFilter == nil) { kindFilter = nil }
                        ForEach(kinds, id: \.self) { k in
                            chip(k == .album ? "Albums" : k == .ep ? "Singles & EPs" : "Compilations",
                                 on: kindFilter == k) { kindFilter = k }
                        }
                    }
                }
                .scrollIndicators(.hidden)
                .contentMargins(.horizontal, 16, for: .scrollContent)
                .padding(.horizontal, -16)
                .padding(.top, 12)
            }
            Spacer().frame(height: 8)
            ForEach(Array(visible.enumerated()), id: \.element.id) { i, r in
                releaseRow(r, firstOfYear: i == 0 || visible[i - 1].year != r.year, latest: r.id == releases.first?.id)
            }
            if shown.count > collapsed {
                // M3 TextButton: 12 × 8 content padding, min height 40.
                Button {
                    withAnimation(Yoin.spatial) { showAll.toggle() }
                } label: {
                    Text(showAll ? "Show fewer" : "Show all \(shown.count)")
                        .yoinRole(Tokens.type.labelLarge)
                        .foregroundStyle(s.primary)
                        .padding(.horizontal, 12).padding(.vertical, 8)
                        .frame(minHeight: 40)
                        .contentShape(.capsule)
                }
                .buttonStyle(PressScale())
                .padding(.leading, Tokens.detail.ARTIST_YEAR_COLUMN.cg)
            }
        }
        .animation(Yoin.spatial, value: kindFilter)
    }

    private func chip(_ title: String, on: Bool, action: @escaping () -> Void) -> some View {
        Button { withAnimation(Yoin.effects) { action() } } label: {
            Text(title)
                .lineLimit(1)
                .yoinRole(Tokens.type.labelLarge)
                .foregroundStyle(on ? app.onSecondaryContainer : app.onSurfaceVariant)
                .padding(.horizontal, 16).padding(.vertical, 10)
                .frame(minHeight: 44)
                // These chips scroll with the page, so they stay solid (not glass).
                // RoundedCornerShape(18): circular corners, not continuous.
                .background(on ? app.secondaryContainer : app.surfaceContainerHigh,
                            in: .rect(cornerRadius: 18, style: .circular))
        }
        .buttonStyle(PressScale())
    }

    /// Year column (48, first row of each year), 56 bare cover, title over
    /// MetaLine [Latest] [kind] [N songs].
    private func releaseRow(_ r: Album, firstOfYear: Bool, latest: Bool) -> some View {
        Button { model.open(r) } label: {
            HStack(spacing: 0) {
                Text(firstOfYear ? String(r.year) : "")
                    .monospacedDigit()
                    .yoinRole(Tokens.type.labelLarge)
                    .foregroundStyle(app.onSurfaceVariant)
                    .padding(.leading, 8)
                    .frame(width: Tokens.detail.ARTIST_YEAR_COLUMN.cg, alignment: .leading)
                EntityBackdrop(entity: .album, palette: r.palette, seed: r.seed, size: 56, stroke: app.primary)
                Spacer().frame(width: 12)
                VStack(alignment: .leading, spacing: 2) {
                    Text(r.title).lineLimit(1)
                        .yoinRole(Tokens.type.titleMedium)
                        .foregroundStyle(app.onSurface)
                    // MetaLine (bodySmall): Kind groups in Medium (Latest in
                    // primary, the kind in onSurfaceVariant), then a Stat:
                    // the count Medium tabular onSurface, the unit at 60 %.
                    HStack(spacing: Tokens.metaLine.GROUP_GAP.cg) {
                        if latest {
                            Text("Latest").yoinRole(Tokens.type.bodySmall, weight: .medium).foregroundStyle(app.primary)
                        }
                        Text(r.kind.rawValue).yoinRole(Tokens.type.bodySmall, weight: .medium)
                            .foregroundStyle(app.onSurfaceVariant)
                        HStack(spacing: Tokens.metaLine.INNER_GAP.cg) {
                            Text("\(r.trackCount)").monospacedDigit()
                                .yoinRole(Tokens.type.bodySmall, weight: .medium)
                                .foregroundStyle(app.onSurface)
                            Text(r.trackCount == 1 ? "song" : "songs")
                                .yoinRole(Tokens.type.bodySmall)
                                .foregroundStyle(app.onSurface.opacity(Tokens.metaLine.MUTED_ALPHA))
                        }
                    }
                    .lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.trailing, 8).padding(.vertical, 8)
            .contentShape(.rect(cornerRadius: Tokens.shape.LIST_ROW.cg))
        }
        .buttonStyle(PressScale())
    }
}
