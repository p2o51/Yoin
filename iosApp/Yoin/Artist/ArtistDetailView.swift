import SwiftUI

/// Artist page, ported from Android ui/detail/ArtistDetailScreen.kt
/// (Compact): fixed header (back, name, year span, follow star), the pinwheel
/// hero (the whole Yoin mark rotated 30° around the round portrait, turning
/// with scroll), Most Played, and the discography with kind chips and a year
/// column. One plain scroll: no pull-up, no ratings on this page.
/// Colors: the newest release's cover scheme for the name, star, arms and
/// "Show all"; the app scheme for everything else.
struct ArtistDetailView: View {
    var artist: String
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dismiss) private var dismiss
    @State private var following = true
    @State private var kindFilter: ReleaseKind?
    @State private var showAll = false
    @State private var scrollY: CGFloat = 0

    private var releases: [Album] { MockLibrary.shared.releases(by: artist) }
    private var hero: Album { releases.first ?? MockLibrary.allAlbums[0] }
    private var s: CoverScheme { CoverScheme(album: hero, dark: colorScheme == .dark) }
    private var app: CoverScheme {
        model.isIdle ? .brand(dark: colorScheme == .dark)
                     : CoverScheme(album: model.playback.album, dark: colorScheme == .dark)
    }

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width
            let portrait = min(0.52 * w, 216)
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
                    }
                }
                .onScrollGeometryChange(for: CGFloat.self) { $0.contentOffset.y } action: { _, y in scrollY = y }
                .contentMargins(.bottom, 24, for: .scrollContent)
                .defaultScrollAnchor(LaunchConfig.screen == "artist-bottom" ? .bottom : .top)
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
    }

    private var headerHeight: CGFloat { 4 + 48 + 6 }

    private var background: some View {
        LinearGradient(colors: [app.surfaceContainer.mix(with: s.primary, by: 0.18, in: .perceptual),
                                app.background, app.surfaceContainerLow],
                       startPoint: .top, endPoint: .bottom)
            .ignoresSafeArea()
    }

    // MARK: Header

    private var yearSpan: String? {
        let years = releases.map(\.year)
        guard let lo = years.min(), let hi = years.max() else { return nil }
        return lo == hi ? "\(lo)" : "\(lo) – \(hi)"
    }

    private var header: some View {
        HStack(spacing: 0) {
            Button { dismiss() } label: {
                YoinIcon("back", size: 22)
                    .foregroundStyle(app.onSurface)
                    .frame(width: 40, height: 40)
                    .contentShape(.circle)
            }
            .buttonStyle(.plain)
            .glassEffect(.regular.interactive(), in: .circle)
            .frame(width: 48, height: 48)
            .accessibilityLabel("Back")
            Spacer().frame(width: 14)
            VStack(alignment: .leading, spacing: 0) {
                Text(artist).font(.gsf(24)).foregroundStyle(s.primary).lineLimit(1)
                if let yearSpan {
                    Text(yearSpan).font(.gsf(14)).foregroundStyle(app.onSurface)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Button {
                withAnimation(.spring(duration: 0.09)) { following.toggle() }
            } label: {
                YoinIcon(following ? "star_filled" : "star", size: 24)
                    .foregroundStyle(following ? s.primary : app.onSurfaceVariant)
                    .frame(width: 48, height: 48)
                    .contentShape(.rect)
            }
            .buttonStyle(PressScale())
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
        let degrees = 30 + scrollY * 3 * 0.06
        return ZStack {
            Canvas { gc, size in
                gc.translateBy(x: size.width / 2, y: size.height / 2)
                gc.rotate(by: .degrees(degrees))
                let k = portrait * 2.1 / YoinMarkPaths.viewBox
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
            CoverArt(palette: hero.palette, cornerRadius: portrait / 2, seed: hero.seed + 3)
                .frame(width: portrait, height: portrait)
                .clipShape(.circle)
        }
        .frame(width: w, height: portrait + 150)
    }

    // MARK: Most Played

    private func sectionHeader(_ title: String, _ trailing: String) -> some View {
        HStack {
            Text(title).font(.gsf(14, .medium)).underline().foregroundStyle(app.onSurface)
            Spacer()
            Text(trailing).font(.gsf(12, .medium)).tracking(0.1).foregroundStyle(app.onSurfaceVariant)
        }
        .padding(.horizontal, 8)
    }

    private var mostPlayed: some View {
        let picks: [(Album, Track, Int)] = releases.flatMap { a in a.tracks.prefix(2).map { (a, $0) } }
            .prefix(5).enumerated().map { ($1.0, $1.1, 30 - $0 * 5) }
        return VStack(alignment: .leading, spacing: 0) {
            sectionHeader("Most Played", "\(picks.map(\.2).reduce(0, +)) plays")
            Spacer().frame(height: 6)
            ForEach(Array(picks.enumerated()), id: \.offset) { i, item in
                let (a, t, plays) = item
                HStack(spacing: 12) {
                    Text("\(i + 1)").font(.gsf(14, .medium)).monospacedDigit()
                        .foregroundStyle(app.onSurfaceVariant).frame(minWidth: 16, alignment: .trailing)
                    CoverArt(palette: a.palette, cornerRadius: 0, seed: a.seed)
                        .frame(width: 44, height: 44)
                        .clipShape(.rect(cornerRadius: 4, style: .continuous))
                    VStack(alignment: .leading, spacing: 2) {
                        Text(t.title).font(.gsf(16, .medium)).foregroundStyle(app.onSurface)
                        HStack(spacing: 12) {
                            Text(a.title).foregroundStyle(app.onSurface)
                            Text(formatTime(t.durationSec)).foregroundStyle(app.onSurface.opacity(0.6))
                        }
                        .font(.gsf(12)).monospacedDigit()
                    }
                    .lineLimit(1)
                    Spacer(minLength: 8)
                    Text(plays == 1 ? "1 play" : "\(plays) plays")
                        .font(.gsf(14, .medium)).monospacedDigit()
                        .foregroundStyle(app.onSurfaceVariant)
                }
                .padding(8)
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
        let visible = showAll ? shown : Array(shown.prefix(8))
        return VStack(alignment: .leading, spacing: 0) {
            sectionHeader("Discography", releases.count == 1 ? "1 release" : "\(releases.count) releases")
            if kinds.count >= 2 {
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
                .padding(.top, 12)
            }
            Spacer().frame(height: 8)
            ForEach(Array(visible.enumerated()), id: \.element.id) { i, r in
                releaseRow(r, firstOfYear: i == 0 || visible[i - 1].year != r.year, latest: r.id == releases.first?.id)
            }
            if shown.count > 8 {
                Button(showAll ? "Show fewer" : "Show all \(shown.count)") {
                    withAnimation(Yoin.spatial) { showAll.toggle() }
                }
                .font(.gsf(14, .medium))
                .foregroundStyle(s.primary)
                .padding(.leading, 48)
            }
        }
        .animation(Yoin.spatial, value: kindFilter)
    }

    private func chip(_ title: String, on: Bool, action: @escaping () -> Void) -> some View {
        Button { withAnimation(Yoin.effects) { action() } } label: {
            Text(title)
                .font(.gsf(14, .medium))
                .foregroundStyle(on ? app.onSecondaryContainer : app.onSurfaceVariant)
                .lineLimit(1)
                .padding(.horizontal, 16).padding(.vertical, 10)
                .frame(minHeight: 44)
                .background(on ? app.secondaryContainer : app.surfaceContainerHigh, in: .rect(cornerRadius: 18))
        }
        .buttonStyle(PressScale())
    }

    /// Year column (48, first row of each year), 56 bare cover, title over
    /// MetaLine [Latest] [kind] [N songs].
    private func releaseRow(_ r: Album, firstOfYear: Bool, latest: Bool) -> some View {
        Button { model.open(r) } label: {
            HStack(spacing: 0) {
                Text(firstOfYear ? String(r.year) : "")
                    .font(.gsf(14, .medium)).monospacedDigit()
                    .foregroundStyle(app.onSurfaceVariant)
                    .padding(.leading, 8)
                    .frame(width: 48, alignment: .leading)
                EntityBackdrop(entity: .album, palette: r.palette, seed: r.seed, size: 56, stroke: app.primary)
                Spacer().frame(width: 12)
                VStack(alignment: .leading, spacing: 2) {
                    Text(r.title).font(.gsf(16, .medium)).foregroundStyle(app.onSurface).lineLimit(1)
                    HStack(spacing: 12) {
                        if latest { Text("Latest").fontWeight(.medium).foregroundStyle(app.primary) }
                        Text(r.kind.rawValue).fontWeight(.medium).foregroundStyle(app.onSurfaceVariant)
                        HStack(spacing: 4) {
                            Text("\(r.tracks.count)").fontWeight(.medium).monospacedDigit().foregroundStyle(app.onSurface)
                            Text(r.tracks.count == 1 ? "song" : "songs").foregroundStyle(app.onSurface.opacity(0.6))
                        }
                    }
                    .font(.gsf(12))
                }
                Spacer(minLength: 0)
            }
            .padding(.trailing, 8).padding(.vertical, 8)
            .contentShape(.rect(cornerRadius: 12))
        }
        .buttonStyle(PressScale())
    }
}
