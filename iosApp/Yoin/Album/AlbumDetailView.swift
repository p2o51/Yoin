import SwiftUI
import UIKit

/// Album page, page 1 of Android's two-page album detail (AlbumDetailScreen,
/// AlbumSpectrumBar, DetailPullUpReshape, AlbumDetailComponents), phone
/// portrait. Two states on one progress `p`:
/// - Hero (p = 0): header, cover with the Yoin "butterfly" blocks behind it,
///   the score emblem, and the track titles flowing as one paragraph.
/// - Track list (p = 1): pulled up. The cover flies into a 48 pt stamp at the
///   end of the title row, the header turns into the cover's color spectrum
///   with white text, and the list fades in.
///
/// iOS adaptations: the back button is a glass circle (Android: a tonal
/// circle), and edge-swipe back still works with the navigation bar hidden.
/// Page 2 (scrapbook) is not built yet; the dots show it exists.
struct AlbumDetailView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dismiss) private var dismiss
    var album: Album
    var padColumn = false

    @State private var p: CGFloat = LaunchConfig.screen == "album-list" ? 1 : LaunchConfig.screen == "album-mid" ? 0.42 : 0
    @State private var dragBase: CGFloat?

    /// The cover's own scheme (title, blocks, dots, Play) and the app's
    /// (everything else), as on Android.
    private var s: CoverScheme { CoverScheme(album: album, dark: colorScheme == .dark) }
    private var app: CoverScheme {
        model.isIdle ? .brand(dark: colorScheme == .dark)
                     : CoverScheme(album: model.playback.album, dark: colorScheme == .dark)
    }

    private let headerContent: CGFloat = 4 + 52 + 25 + 6

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width
            let side = min(0.74 * w, 300)
            let band = side + 56
            let e = smooth(p)
            ZStack(alignment: .topLeading) {
                background
                butterfly(w: w, side: side, band: band)
                    .opacity(1 - smooth(p, 0.62, 0.98))
                hero(w: w, side: side, band: band)
                    .opacity(1 - e)
                    .offset(y: -e * 15) // Android: −40 px
                    .allowsHitTesting(e < 0.5)
                trackList(w: w)
                    .opacity(e)
                    .allowsHitTesting(e >= 0.5)
                spectrum(w: w, side: side, top: geo.safeAreaInsets.top)
                header(white: false, w: w)
                    .opacity(1 - smooth(p, 0.25, 0.7))
                header(white: true, w: w)
                    .opacity(smooth(p, 0.25, 0.7))
                cover(w: w, side: side, band: band)
            }
            .contentShape(.rect)
            .simultaneousGesture(pullUp(travel: geo.size.height * 0.55))
        }
        .toolbar(.hidden, for: .navigationBar)
        .background(Yoin.surface)
    }

    // MARK: Pieces

    /// ExpressivePageBackground: [lerp(surfaceContainer, accent, 0.18), background, surfaceContainerLow].
    private var background: some View {
        LinearGradient(colors: [app.surfaceContainer.mix(with: s.primary, by: 0.18, in: .perceptual),
                                app.background, app.surfaceContainerLow],
                       startPoint: .top, endPoint: .bottom)
            .ignoresSafeArea()
    }

    private func header(white: Bool, w: CGFloat) -> some View {
        let ink: Color = white ? .white : app.onSurface
        return VStack(spacing: 0) {
            HStack(spacing: 14) {
                Button { dismiss() } label: {
                    YoinIcon("back", size: 22)
                        .foregroundStyle(ink)
                        .frame(width: 40, height: 40)
                        .contentShape(.circle)
                }
                .buttonStyle(.plain)
                .glassEffect(white ? .regular.tint(.white.opacity(0.18)).interactive() : .regular.interactive(),
                             in: .circle)
                .accessibilityLabel("Back")
                VStack(alignment: .leading, spacing: 0) {
                    Text(album.title)
                        .font(.gsf(24))
                        .foregroundStyle(white ? .white : s.primary)
                        .lineLimit(1)
                    // MetaLine: [artist] [Album] [year], 12 pt apart, no separators.
                    HStack(spacing: 12) {
                        Text(album.artist).foregroundStyle(white ? .white.opacity(0.82) : app.onSurface)
                        Text("Album").fontWeight(.medium).foregroundStyle(white ? .white.opacity(0.88) : app.primary)
                        Text(String(album.year)).foregroundStyle(white ? .white.opacity(0.82) : app.onSurface)
                    }
                    .font(.gsf(14))
                    .lineLimit(1)
                }
                .padding(.trailing, 60 * smooth(p))
                Spacer(minLength: 0)
            }
            .frame(height: 52)
            HStack(spacing: 0) {
                ForEach(0..<2, id: \.self) { i in
                    Circle()
                        .fill(i == 0 ? (white ? Color.white : s.secondary)
                                     : (white ? Color.white.opacity(0.4) : app.onSurfaceVariant.opacity(0.3)))
                        .frame(width: 8, height: 8)
                        .frame(width: 24, height: 20)
                        .accessibilityLabel(i == 0 ? "Overview" : "Scrapbook")
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.top, 5)
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

    /// Hero cover ↔ 48 pt stamp at the end of the title row.
    private func cover(w: CGFloat, side: CGFloat, band: CGFloat) -> some View {
        let e = smooth(p)
        let heroRect = CGRect(x: (w - side) / 2, y: headerContent + 28, width: side, height: side)
        let stampRect = CGRect(x: w - 16 - 48, y: 4 + 2, width: 48, height: 48)
        let r = CGRect(x: lerp(heroRect.minX, stampRect.minX, e), y: lerp(heroRect.minY, stampRect.minY, e),
                       width: lerp(side, 48, e), height: lerp(side, 48, e))
        return Button {
            if e > 0.5 { withAnimation(Yoin.spatial) { p = 0 } }
        } label: {
            CoverArt(palette: album.palette, cornerRadius: 0, seed: album.seed)
                .frame(width: r.width, height: r.height)
                .clipShape(.rect(cornerRadius: lerp(8, 4, e), style: .continuous))
        }
        .buttonStyle(.plain)
        .allowsHitTesting(e > 0.5)
        .accessibilityLabel("Show cover")
        .offset(x: r.minX, y: r.minY)
    }

    private func hero(w: CGFloat, side: CGFloat, band: CGFloat) -> some View {
        let ratedFlags = album.tracks.map { $0.rating != nil }
        let kind: ScoreEmblem.Kind = album.rating != nil ? .album : .average
        let score = album.rating ?? album.averageRating ?? 0
        return VStack(alignment: .leading, spacing: 0) {
            Spacer().frame(height: headerContent + band + 8)
            // Emblem + comment, as wide as the cover.
            HStack(spacing: 20) {
                HStack(spacing: 10) {
                    ScoreEmblem(score: score, kind: kind, rated: ratedFlags, palette: album.palette, size: 64)
                    if kind == .average {
                        Text("\(album.ratedCount)/\(album.tracks.count) rated")
                            .font(.gsf(11, .medium)).monospacedDigit()
                            .foregroundStyle(app.onSurfaceVariant)
                    }
                }
                if let comment = mockComment {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(comment).font(.gsf(14)).foregroundStyle(app.onSurface).lineLimit(3)
                        Text("Yesterday").font(.gsf(12, .medium)).foregroundStyle(app.onSurfaceVariant)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                } else {
                    Button {} label: {
                        YoinIcon("edit", size: 18)
                            .foregroundStyle(app.onSecondaryContainer)
                            .frame(width: 40, height: 40)
                            .background(app.secondaryContainer, in: .circle)
                    }
                    .buttonStyle(PressScale())
                    .accessibilityLabel("Write a comment")
                    Spacer(minLength: 0)
                }
            }
            .frame(width: side)
            .frame(maxWidth: .infinity)
            Spacer().frame(height: 28)
            HStack(spacing: 12) {
                countLabel
                Text("Yesterday 16:04")
                    .font(.gsf(12, .medium)).monospacedDigit()
                    .foregroundStyle(app.onSurface)
            }
            Spacer().frame(height: 6)
            Text(flowingTitles)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 16)
        .frame(width: w, alignment: .topLeading)
    }

    /// "12 tracks  1 h 12 min": numbers Medium tabular, units at 60 %.
    private var countLabel: some View {
        let mins = album.tracks.map(\.durationSec).reduce(0, +) / 60
        return HStack(spacing: 12) {
            HStack(spacing: 4) {
                Text("\(album.tracks.count)").fontWeight(.medium)
                Text(album.tracks.count == 1 ? "track" : "tracks").opacity(0.6)
            }
            HStack(spacing: 4) {
                if mins >= 60 { Text("\(mins / 60)").fontWeight(.medium); Text("h").opacity(0.6) }
                if mins % 60 > 0 { Text("\(mins % 60)").fontWeight(.medium); Text("min").opacity(0.6) }
            }
        }
        .font(.gsf(12, .medium))
        .monospacedDigit()
        .foregroundStyle(app.onSurface)
    }

    /// Titles in headlineMedium joined by a space, even = onSurface,
    /// odd = onSurfaceVariant; short titles (≤ 16 units, CJK = 2) are glued
    /// so they wrap as one unit.
    private var flowingTitles: AttributedString {
        var out = AttributedString()
        for (i, t) in album.tracks.enumerated() {
            let units = t.title.unicodeScalars.reduce(0) { $0 + ($1.value > 0x2E80 ? 2 : 1) }
            let title = units <= 16 ? t.title.replacingOccurrences(of: " ", with: "\u{00A0}") : t.title
            var piece = AttributedString(title)
            piece.font = .gsf(28)
            piece.foregroundColor = i.isMultiple(of: 2) ? app.onSurface : app.onSurfaceVariant
            out += piece
            if i < album.tracks.count - 1 {
                var gap = AttributedString(" ")
                gap.font = .gsf(28)
                out += gap
            }
        }
        return out
    }

    private var mockComment: String? {
        album.rating != nil ? "A record for the walk home, warm even on the cold tracks" : nil
    }

    // MARK: Track list (pulled up)

    private func trackList(w: CGFloat) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                countLabel
                    .padding(.leading, 8)
                    .padding(.top, 2)
                    .padding(.bottom, 10)
                ForEach(album.tracks) { t in trackRow(t) }
                linerNotes
                    .padding(.horizontal, 8)
                    .padding(.top, 28)
                    .opacity(max(0, (smooth(p) - 0.6) / 0.4))
            }
            .padding(.horizontal, 16)
            .padding(.top, headerContent + 4)
        }
        .contentMargins(.bottom, 112, for: .scrollContent)
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
                    Text("\(t.number)").font(.gsf(12, .medium)).monospacedDigit()
                        .foregroundStyle(app.onSurfaceVariant)
                }
            }
            .frame(minWidth: 18, alignment: .trailing)
            Text(t.title)
                .font(.gsf(16, .medium))
                .foregroundStyle(playing ? s.primary : app.onSurface)
                .lineLimit(1)
            Spacer(minLength: 0)
            Text(formatTime(t.durationSec))
                .font(.gsf(14, .medium)).monospacedDigit()
                .foregroundStyle(app.onSurfaceVariant)
            let liked = t.rating.map { $0 >= 9 } ?? false
            YoinIcon(liked ? "favorite_filled" : "favorite", size: 16)
                .foregroundStyle(liked ? s.onPrimary : app.onSurfaceVariant)
                .frame(width: 28, height: 28)
                .background {
                    if liked { Circle().fill(s.primary) }
                    else { Circle().strokeBorder(app.outline.opacity(0.5), lineWidth: 1.5) }
                }
                .frame(width: 36, height: 36)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 10)
        .frame(minHeight: 56)
        .contentShape(.rect(cornerRadius: 12))
    }

    private var linerNotes: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 4) {
                Text("Last Play").font(.gsf(14, .medium)).underline().foregroundStyle(app.onSurface)
                Text("Yesterday").font(.gsf(16)).foregroundStyle(app.onSurface)
                Text("16:04").font(.gsf(14)).monospacedDigit().foregroundStyle(app.onSurfaceVariant).padding(.top, 4)
            }
            Spacer()
            ScoreEmblem(score: album.rating ?? album.averageRating ?? 0,
                        kind: album.rating != nil ? .album : .average,
                        rated: album.tracks.map { $0.rating != nil }, palette: album.palette, size: 64)
        }
    }

    // MARK: Spectrum bar

    /// AlbumSpectrumBar drawSpectrum: each of the 28 strips starts as a slice
    /// of the cover and flies to its slot in the header bar
    /// (q = smoothstep(0.06 + lag, 0.6 + lag, p), lag = 0.22 × i / 28); the
    /// cover slice fades out as the flat locked color fades in over
    /// smoothstep(0, 0.55, q).
    private func spectrum(w: CGFloat, side: CGFloat, top: CGFloat) -> some View {
        let colors = AlbumSpectrum.colors(for: album)
        let n = AlbumSpectrum.strips
        let slice = side / CGFloat(n)
        let heroX = (w - side) / 2, heroY = headerContent + 28
        let barH = top + headerContent
        return ZStack(alignment: .topLeading) {
            ForEach(0..<n, id: \.self) { i in
                let lag = 0.22 * CGFloat(i) / CGFloat(n)
                let q = smooth(p, 0.06 + lag, 0.6 + lag)
                let flat = smooth(q, 0, 0.55)
                let x = lerp(heroX + CGFloat(i) * slice, CGFloat(i) * w / CGFloat(n), q)
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
                        .fill(colors[min(i, colors.count - 1)])
                        .frame(width: sw, height: sh)
                        .opacity(flat)
                }
                .frame(width: sw, height: sh, alignment: .topLeading)
                .clipped()
                .offset(x: x, y: y)
            }
        }
        .frame(width: w, alignment: .topLeading)
        .opacity(p > 0.001 ? 1 : 0)
        .allowsHitTesting(false)
    }

    // MARK: Pull-up gesture (DetailPullUpReshape)

    private func pullUp(travel: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 14)
            .onChanged { v in
                guard abs(v.translation.height) > abs(v.translation.width) else { return }
                if dragBase == nil {
                    // Collapsed: the hero takes the drag. Expanded: only a pull-down.
                    if p >= 1 && v.translation.height < 0 { return }
                    dragBase = p
                }
                p = min(1, max(0, (dragBase ?? 0) - v.translation.height / travel))
            }
            .onEnded { v in
                guard let base = dragBase else { return }
                _ = base
                dragBase = nil
                let velocity = -(v.predictedEndTranslation.height - v.translation.height) / travel / 0.25
                let expand = velocity >= 1.6 ? true : velocity <= -1.6 ? false : p > 0.5
                withAnimation(Yoin.spatial) { p = expand ? 1 : 0 }
            }
    }

    // MARK: Math

    private func smooth(_ x: CGFloat, _ a: CGFloat = 0, _ b: CGFloat = 1) -> CGFloat {
        let t = min(1, max(0, (x - a) / (b - a)))
        return t * t * (3 - 2 * t)
    }

    private func lerp(_ a: CGFloat, _ b: CGFloat, _ t: CGFloat) -> CGFloat { a + (b - a) * t }
}

/// Edge-swipe back keeps working on pushed pages that hide the navigation bar.
extension UINavigationController: @retroactive UIGestureRecognizerDelegate {
    override open func viewDidLoad() {
        super.viewDidLoad()
        interactivePopGestureRecognizer?.delegate = self
    }

    public func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        viewControllers.count > 1
    }
}
