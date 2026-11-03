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
            model.select(s)
        } label: {
            HStack(spacing: 8) {
                YoinIcon(icon, size: 24)
                if model.isIdle {
                    Text(s == .home ? "Home" : "Library").font(.gsf(14, .semibold))
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
        if selected && tintedStyle {
            // Tinted-glass style: force the tint on, in the accent role.
            button
                .glassEffect(.regular.tint(accent(s).opacity(lab.style("barSelected").tintOpacity)).interactive(),
                             in: .capsule)
                .glassEffectID(s == .home ? "home" : "library", in: glassNS)
                .accessibilityLabel(s == .home ? "Home" : "Library")
        } else {
            button
                .labGlass(selected ? "barSelected" : "barUnselected", scheme: scheme, brand: brand,
                          auto: accent(s), in: Capsule())
                .glassEffectID(s == .home ? "home" : "library", in: glassNS)
                .accessibilityLabel(s == .home ? "Home" : "Library")
        }
    }

    private var searchButton: some View {
        Button { withAnimation(Yoin.spatial) { model.searchActive = true } } label: {
            YoinIcon("search", size: 24)
                .foregroundStyle(Yoin.ink)
                .labContrast("barUnselected")
                .frame(width: height, height: height)
                .contentShape(.circle)
        }
        .buttonStyle(.plain)
        .labGlass("barUnselected", scheme: scheme, brand: brand, auto: scheme.tertiary, in: Circle())
        .glassEffectID("search", in: glassNS)
        .background {
            // The search field grows out of this circle.
            if !model.searchActive {
                Circle().fill(.clear).matchedGeometryEffect(id: "search.surface", in: npNamespace)
            }
        }
        .accessibilityLabel("Search")
    }

    // MARK: Now Playing pill

    private var pill: some View {
        let p = model.playback
        let progress = Double(p.positionSec) / Double(p.track.durationSec)
        return Button {
            if model.isPad { model.openNowPlayingOnPad() } else { model.setNowPlaying(expanded: true) }
        } label: {
            HStack(spacing: 10) {
                // Matched only while closed, so on open the page's cover and
                // surface start from these frames and grow (and shrink back).
                if model.npExpanded {
                    Color.clear.frame(width: 42, height: 42)
                } else {
                    CoverArt(palette: p.album.palette, cornerRadius: 6, seed: p.album.seed)
                        .frame(width: 42, height: 42)
                        .matchedGeometryEffect(id: "np.cover", in: npNamespace)
                }
                VStack(alignment: .leading, spacing: 1) {
                    Text(p.track.title)
                        .font(.gsf(15, .semibold))
                        .foregroundStyle(Yoin.ink)
                    Text(p.album.artist)
                        .font(.gsf(13))
                        .foregroundStyle(Yoin.inkVariant)
                }
                .lineLimit(1)
                .labContrast("miniPlayer")
                Spacer(minLength: 0)
            }
            .padding(.leading, 9)
            .padding(.trailing, 12)
            .frame(maxWidth: .infinity, minHeight: height, maxHeight: height)
            .background {
                PillWash(progress: progress, playing: p.isPlaying,
                         color: scheme.primary.opacity(lab.extra("washOpacity")),
                         amplitude: lab.extra("washAmplitude"))
                    .clipShape(.capsule)
            }
            .contentShape(.capsule)
        }
        .buttonStyle(.plain)
        .labGlass("miniPlayer", scheme: scheme, brand: brand, auto: scheme.primaryContainer, in: Capsule())
        .glassEffectID("np", in: glassNS)
        .background {
            if !model.npExpanded {
                Capsule().fill(.clear)
                    .matchedGeometryEffect(id: "np.surface", in: npNamespace)
            }
        }
        .accessibilityLabel("Now playing, \(p.track.title) by \(p.album.artist)")
        .accessibilityValue("\(Int(progress * 100)) percent played")
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
            Button {} label: {
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
                Button("Shuffle play") {}
                Button("Play next") {}
                Button("Add to queue") {}
                Button("Add to playlist") {}
                Button("Open in Apple Music") {}
                Divider()
                Button("Go to artist") {}
                Button("Share") {}
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
