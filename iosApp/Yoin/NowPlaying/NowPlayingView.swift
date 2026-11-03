import SwiftUI

/// Now Playing in two glass layers, growing out of the bar's pill:
/// the **base** is the page itself (a sheet slightly inset from the screen, so
/// its rim shows against what is behind it), the **top card** is a compact
/// glass card around title, lyric, progress and transport. Cover and rating
/// rail sit solid on the base. Drag down (or tap the grabber) to collapse.
/// Debug: triple-tap the grabber to cycle the variants (see `NPVariant`).
struct NowPlayingView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.glassFlavor) private var flavor
    var npNamespace: Namespace.ID
    /// iPad side panel: no sheet morph, no drag-to-dismiss.
    var panel = false

    @State private var dragY: CGFloat = 0
    @State private var tab = "Lyrics"
    @State private var rating: Double = 9.6
    @State private var favorite = true
    @State private var shown = false

    private var palette: CoverPalette { model.playback.album.palette }
    private var variant: NPVariant { model.npVariant }
    private let ink = Color.white
    private let baseShape = RoundedRectangle(cornerRadius: 44, style: .continuous)
    private let cardShape = RoundedRectangle(cornerRadius: 34, style: .continuous)

    var body: some View {
        GeometryReader { geo in
            ZStack {
                if panel {
                    Rectangle().fill(palette.deep).ignoresSafeArea()
                } else {
                    base(geo)
                }
                content(geo)
                    .frame(maxWidth: model.isPad ? 520 : .infinity)
                    .opacity(shown ? 1 : 0)
                    .scaleEffect(shown ? 1 : 0.94, anchor: .bottom)
            }
            .frame(width: geo.size.width, height: geo.size.height)
            .offset(y: max(0, dragY))
            .gesture(dismissDrag, isEnabled: !panel)
        }
        // The page is always dark-on-cover: glass renders its dark variant,
        // so white ink stays legible on any cover.
        .environment(\.colorScheme, .dark)
        .onAppear {
            let anim: Animation = model.slowMorph ? .easeIn(duration: 5) : Yoin.effects.delay(0.08)
            withAnimation(anim) { shown = true }
        }
    }

    // MARK: Base layer

    private func base(_ geo: GeometryProxy) -> some View {
        ZStack {
            switch variant {
            case .v1:
                // Cover-tinted clear glass straight over the shell content.
                baseShape.fill(.clear)
                    .glassEffect(.clear.tint(palette.deep.opacity(0.6)), in: baseShape)
            case .v2:
                // Heavier frosted glass with grain over a blown-up cover.
                ambientCover.clipShape(baseShape)
                baseShape.fill(.clear)
                    .glassEffect(.regular.tint(palette.deep.opacity(0.35)), in: baseShape)
                grain(0.32).clipShape(baseShape)
            case .v3:
                // Flat cover color, for comparison.
                baseShape.fill(palette.deep)
            case .v4:
                // Glass over the neighbours' colors: previous album above,
                // next album below, the current cover in the middle.
                neighbourAmbient.clipShape(baseShape)
                baseShape.fill(.clear)
                    .glassEffect(.regular.tint(palette.deep.opacity(0.12)), in: baseShape)
            }
        }
        .matchedGeometryEffect(id: "np.surface", in: npNamespace)
        .padding(.horizontal, 8)
        .padding(.top, geo.safeAreaInsets.top + 2)
        .padding(.bottom, 8)
        .ignoresSafeArea()
    }

    private var ambientCover: some View {
        CoverArt(palette: palette, cornerRadius: 0, seed: model.playback.album.seed)
            .scaleEffect(1.8)
            .blur(radius: 50)
            .saturation(1.3)
    }

    private var neighbourAmbient: some View {
        let albums = model.library.albums()
        let prev = albums[11].palette
        let next = albums[1].palette
        return ZStack {
            palette.deep
            Circle().fill(palette.a).frame(width: 360).blur(radius: 90)
            VStack {
                Ellipse().fill(prev.a).frame(height: 300).offset(y: -90).blur(radius: 50)
                Spacer()
                Ellipse().fill(next.a).frame(height: 320).offset(y: 110).blur(radius: 50)
            }
        }
    }

    private func grain(_ opacity: Double) -> some View {
        Rectangle()
            .fill(.white)
            .colorEffect(ShaderLibrary.yoinGrain(.float(0.6)))
            .blendMode(.overlay)
            .opacity(opacity)
            .allowsHitTesting(false)
    }

    // MARK: Content

    private func content(_ geo: GeometryProxy) -> some View {
        let p = model.playback
        let railW: CGFloat = 58
        let horizontal: CGFloat = panel ? 20 : 24
        let width = min(geo.size.width, model.isPad ? 520 : geo.size.width)
        let cover = min(width - horizontal * 2 - railW - 12, geo.size.height * 0.34)
        return VStack(spacing: 0) {
            grabber
            HStack(alignment: .top, spacing: 12) {
                CoverArt(palette: p.album.palette, cornerRadius: 10, seed: p.album.seed)
                    .matchedGeometryEffect(id: panel ? "np.cover.panel" : "np.cover", in: npNamespace)
                    .frame(width: cover, height: cover)
                VStack(spacing: 10) {
                    RatingRail(value: $rating, tint: palette.b)
                        .frame(width: railW, height: cover - railW - 10)
                    Button { withAnimation(Yoin.spatialFast) { favorite.toggle() } } label: {
                        YoinIcon(favorite ? "favorite_filled" : "favorite", size: 26)
                            .foregroundStyle(favorite ? palette.b : ink)
                            .frame(width: railW, height: railW)
                            .background(.white.opacity(0.16), in: .circle)
                    }
                    .buttonStyle(PressScale())
                    .accessibilityLabel("Favorite")
                }
            }
            .padding(.top, 8)

            topCard(p)
                .padding(.top, 14)

            Spacer(minLength: 8)

            bottomToolbar
                .padding(.bottom, 2)
        }
        .padding(.horizontal, horizontal)
        .padding(.bottom, 12)
    }

    /// The top glass layer: title, lyric, progress and transport.
    private func topCard(_ p: PlaybackSnapshot) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(p.track.title)
                    .font(.gsf(24, .semibold))
                Text(p.album.artist)
                    .font(.gsf(16))
                    .opacity(0.72)
            }
            .foregroundStyle(ink)
            lyricBlock(p)
            progress(p)
            controls
        }
        .padding(18)
        .background {
            switch variant {
            case .v1, .v3:
                cardShape.fill(.clear)
                    .glassEffect(.regular.tint(palette.deep.opacity(0.35)), in: cardShape)
            case .v2:
                cardShape.fill(.clear)
                    .glassEffect(.clear.tint(palette.deep.opacity(0.25)), in: cardShape)
            case .v4:
                cardShape.fill(.clear)
                    .glassEffect(.regular.tint(palette.deep.opacity(0.4)), in: cardShape)
                    .background {
                        // The card lifts off the base: a soft cover-colored shadow.
                        cardShape.fill(palette.b.opacity(0.9)).blur(radius: 30).offset(y: 22)
                    }
            }
        }
    }

    private var grabber: some View {
        Capsule()
            .fill(.white.opacity(0.45))
            .frame(width: 40, height: 5)
            .frame(maxWidth: .infinity, minHeight: 24)
            .contentShape(.rect)
            .onTapGesture(count: 3) {
                withAnimation(Yoin.effects) { model.npVariant = model.npVariant.next }
            }
            .onTapGesture {
                if panel {
                    withAnimation(Yoin.spatial) { model.npPanel = false }
                } else {
                    model.setNowPlaying(expanded: false)
                }
            }
            .accessibilityLabel("Collapse Now Playing")
    }

    private func lyricBlock(_ p: PlaybackSnapshot) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                ForEach(["Lyrics", "About", "Note"], id: \.self) { t in
                    let on = tab == t
                    Button { withAnimation(Yoin.spatialFast) { tab = t } } label: {
                        Text(t)
                            .font(.gsf(14, .medium))
                            .foregroundStyle(on ? palette.deep : ink.opacity(0.85))
                            .padding(.horizontal, 14)
                            .frame(height: 32)
                            .background(on ? Color.white : Color.white.opacity(0.12), in: .capsule)
                    }
                    .buttonStyle(PressScale())
                }
            }
            // Android LyricsDisplay: the active line uses the rounded cut, SemiBold.
            Text(p.lyricLine)
                .font(YoinFont.rounded(20, semibold: true, relativeTo: .title3))
                .foregroundStyle(ink)
                .lineLimit(2)
            Text(p.nextLyricLine)
                .font(.gsf(16, .medium))
                .foregroundStyle(ink.opacity(0.45))
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func progress(_ p: PlaybackSnapshot) -> some View {
        let fraction = Double(p.positionSec) / Double(p.track.durationSec)
        return VStack(spacing: 6) {
            WavyProgressBar(progress: fraction, duration: TimeInterval(p.track.durationSec),
                            isPlaying: p.isPlaying, tint: ink, trackTint: ink.opacity(0.3)) { _ in }
            HStack {
                Text(formatTime(p.positionSec))
                Spacer()
                Text("-" + formatTime(p.track.durationSec - p.positionSec))
            }
            .font(.gsf(12, .medium))
            .monospacedDigit()
            .foregroundStyle(ink.opacity(0.65))
        }
    }

    /// Transport inside the glass card. Solid fills here: a glass button
    /// inside the glass card would merge into it or read as glass-on-glass mush.
    private var controls: some View {
        HStack(spacing: 10) {
            circleButton("skip_previous_filled", size: 56, label: "Previous")
            Button {} label: {
                HStack(spacing: 8) {
                    YoinIcon("pause_filled", size: 24)
                    Text("PAUSE").font(.gsf(18, .bold)).tracking(1)
                }
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity, minHeight: 64)
                .background(palette.a, in: .capsule)
                .contentShape(.capsule)
            }
            .buttonStyle(PressScale())
            .accessibilityLabel("Pause")
            circleButton("skip_next_filled", size: 56, label: "Next")
            circleButton("repeat", size: 44, label: "Repeat")
        }
    }

    private func circleButton(_ icon: String, size: CGFloat, label: String) -> some View {
        Button {} label: {
            YoinIcon(icon, size: size * 0.42)
                .foregroundStyle(ink)
                .frame(width: size, height: size)
                .background(.white.opacity(0.14), in: .circle)
                .contentShape(.circle)
        }
        .buttonStyle(PressScale())
        .accessibilityLabel(label)
    }

    private var bottomToolbar: some View {
        GlassEffectContainer(spacing: 4) {
            HStack(spacing: 8) {
                toolbarButton("queue", "Queue")
                toolbarButton("devices", "Devices")
                toolbarButton("edit_note", "Write note")
            }
        }
    }

    private func toolbarButton(_ icon: String, _ title: String) -> some View {
        Button {} label: {
            HStack(spacing: 6) {
                YoinIcon(icon, size: 20)
                Text(title).font(.gsf(14, .medium)).lineLimit(1)
            }
            .foregroundStyle(ink)
            .frame(maxWidth: .infinity, minHeight: 48)
            .contentShape(.capsule)
        }
        .buttonStyle(.plain)
        .yoinGlass(Capsule(), tint: palette.a, flavor: flavor)
    }

    // MARK: Gesture

    private var dismissDrag: some Gesture {
        DragGesture(minimumDistance: 12)
            .onChanged { v in
                // 1:1 down, rubber-band up.
                let t = v.translation.height
                dragY = t > 0 ? t : -pow(-t, 0.7)
            }
            .onEnded { v in
                if v.translation.height > 140 || v.predictedEndTranslation.height > 380 {
                    model.setNowPlaying(expanded: false)
                    dragY = 0
                } else {
                    withAnimation(Yoin.spatial) { dragY = 0 }
                }
            }
    }
}

/// Vertical thick rating rail: continuous 0–10 with 0.1 steps.
struct RatingRail: View {
    @Binding var value: Double
    var tint: Color

    var body: some View {
        GeometryReader { geo in
            let h = geo.size.height
            let fill = max(56, h * value / 10)
            ZStack(alignment: .bottom) {
                Capsule().fill(.white.opacity(0.14))
                Capsule()
                    .fill(tint)
                    .frame(height: fill)
                    .overlay(alignment: .top) {
                        let label = String(format: "%.1f", value)
                        Text(label)
                            .font(YoinFont.score(label, size: 20))
                            .foregroundStyle(.black.opacity(0.78))
                            .padding(.top, 16)
                    }
            }
            .contentShape(.capsule)
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { v in
                        let raw = Double((1 - v.location.y / h) * 10)
                        value = (min(10.0, max(0.0, raw)) * 10).rounded() / 10
                    }
            )
            .animation(Yoin.spatialFast, value: value)
        }
        .accessibilityElement()
        .accessibilityLabel("Rating")
        .accessibilityValue(String(format: "%.1f", value))
    }
}
