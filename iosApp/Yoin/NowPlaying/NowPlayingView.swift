import SwiftUI

/// Now Playing: a base layer of the cover color (A: flat, B: frosted glass)
/// with a glass sheet on top that grows out of the bar's pill. Content stays
/// solid; glass is only on the sheet and the floating controls.
/// Drag down (or tap the grabber) to collapse back into the pill.
/// Debug: triple-tap the grabber to switch base A/B.
struct NowPlayingView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.glassFlavor) private var flavor
    var npNamespace: Namespace.ID
    /// iPad inspector panel: no sheet morph, no drag-to-dismiss.
    var panel = false

    @State private var dragY: CGFloat = 0
    @State private var tab = "Lyrics"
    @State private var rating: Double = 9.6
    @State private var favorite = true

    private var palette: CoverPalette { model.playback.album.palette }
    private let ink = Color.white

    var body: some View {
        GeometryReader { geo in
            ZStack {
                if panel {
                    Rectangle().fill(palette.deep).ignoresSafeArea()
                } else {
                    base
                    sheet
                }
                content(geo)
                    .frame(maxWidth: model.isPad ? 520 : .infinity)
            }
            .frame(width: geo.size.width, height: geo.size.height)
            .offset(y: max(0, dragY))
            .gesture(dismissDrag, isEnabled: !panel)
        }
        // The page is always dark-on-cover: glass renders its dark variant,
        // so white ink stays legible on any cover.
        .environment(\.colorScheme, .dark)
    }

    // MARK: Layers

    @ViewBuilder
    private var base: some View {
        switch model.npBase {
        case .flat:
            Rectangle()
                .fill(palette.deep)
                .ignoresSafeArea()
                .transition(.opacity)
        case .frost:
            Rectangle()
                .fill(.clear)
                .glassEffect(.regular.tint(palette.deep.opacity(0.22)), in: .rect)
                .ignoresSafeArea()
                .transition(.opacity)
        }
    }

    private var sheet: some View {
        RoundedRectangle(cornerRadius: 50, style: .continuous)
            .fill(.clear)
            .glassEffect(.regular.tint(palette.deep.opacity(model.npBase == .flat ? 0.35 : 0.5)),
                         in: .rect(cornerRadius: 50, style: .continuous))
            .matchedGeometryEffect(id: "np.surface", in: npNamespace, isSource: model.npExpanded)
            .padding(6)
            .ignoresSafeArea()
    }

    // MARK: Content

    private func content(_ geo: GeometryProxy) -> some View {
        let p = model.playback
        let railW: CGFloat = 58
        let horizontal: CGFloat = panel ? 20 : 26
        let width = min(geo.size.width, model.isPad ? 520 : geo.size.width)
        let cover = min(width - horizontal * 2 - railW - 12, geo.size.height * 0.36)
        return VStack(spacing: 0) {
            grabber
            HStack(alignment: .top, spacing: 12) {
                CoverArt(palette: p.album.palette, cornerRadius: 10, seed: p.album.seed)
                    .matchedGeometryEffect(id: "np.cover", in: npNamespace,
                                           isSource: model.npExpanded && !panel)
                    .frame(width: cover, height: cover)
                VStack(spacing: 10) {
                    RatingRail(value: $rating, tint: palette.b)
                        .frame(width: railW, height: cover - railW - 10)
                    Button { withAnimation(Yoin.spatialFast) { favorite.toggle() } } label: {
                        YoinIcon(favorite ? "favorite_filled" : "favorite", size: 26)
                            .foregroundStyle(favorite ? palette.b : ink)
                            .frame(width: railW, height: railW)
                            .background(.white.opacity(0.14), in: .circle)
                    }
                    .buttonStyle(PressScale())
                    .accessibilityLabel("Favorite")
                }
            }
            .padding(.top, 14)

            VStack(alignment: .leading, spacing: 3) {
                Text(p.track.title)
                    .font(.gsf(26, .semibold))
                Text(p.album.artist)
                    .font(.gsf(17))
                    .opacity(0.72)
            }
            .foregroundStyle(ink)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 18)

            lyricBlock(p)
                .padding(.top, 14)

            progress(p)
                .padding(.top, 14)

            controls
                .padding(.top, 14)

            Spacer(minLength: 8)

            bottomToolbar
                .padding(.bottom, 4)
        }
        .padding(.horizontal, horizontal)
        .padding(.bottom, 6)
    }

    private var grabber: some View {
        Capsule()
            .fill(.white.opacity(0.45))
            .frame(width: 40, height: 5)
            .frame(maxWidth: .infinity, minHeight: 24)
            .contentShape(.rect)
            .onTapGesture(count: 3) {
                withAnimation(Yoin.effects) { model.npBase = model.npBase == .flat ? .frost : .flat }
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
        VStack(alignment: .leading, spacing: 10) {
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
            Text(p.lyricLine)
                .font(.gsf(20, .semibold))
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

    private var controls: some View {
        GlassEffectContainer(spacing: 4) {
            HStack(spacing: 10) {
                circleButton("skip_previous_filled", size: 60, label: "Previous")
                Button {} label: {
                    HStack(spacing: 8) {
                        YoinIcon("pause_filled", size: 24)
                        Text("PAUSE").font(.gsf(18, .bold)).tracking(1)
                    }
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity, minHeight: 68)
                    .contentShape(.capsule)
                }
                .buttonStyle(.plain)
                .yoinGlass(Capsule(), tint: palette.a, prominent: true, flavor: flavor)
                .accessibilityLabel("Pause")
                circleButton("skip_next_filled", size: 60, label: "Next")
                circleButton("repeat", size: 48, label: "Repeat")
            }
        }
    }

    private func circleButton(_ icon: String, size: CGFloat, label: String) -> some View {
        Button {} label: {
            YoinIcon(icon, size: size * 0.42)
                .foregroundStyle(ink)
                .frame(width: size, height: size)
                .contentShape(.circle)
        }
        .buttonStyle(.plain)
        .yoinGlass(Circle(), tint: palette.a, flavor: flavor)
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
                        Text(String(format: "%.1f", value))
                            .font(.gsf(19, .bold))
                            .monospacedDigit()
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
