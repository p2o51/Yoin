import SwiftUI

/// The floating glass Button Group: [Home][Now Playing pill][Library]; with a detail column open in a
/// wide window it becomes [Home][Library][pill][Play ▾][Shuffle]. With the Now Playing panel open the
/// pill folds away (the panel is the song) and the bar wraps what's left.
struct MacBottomBar: View {
    var wide: Bool
    var showsPill: Bool
    @Environment(MacModel.self) private var model
    @Environment(\.macTint) private var tint

    var body: some View {
        GlassEffectContainer {
            // Apple Music's bar as the scale: room between the parts, nothing touching.
            HStack(spacing: 14) {
                tabButton(.home, label: "Home", icon: "home")
                if wide || !showsPill { tabButton(.library, label: "Library", icon: "library") }
                if showsPill { NowPlayingPill().transition(.scale(scale: 0.6).combined(with: .opacity)) }
                if wide {
                    detailPlay
                } else if showsPill {
                    tabButton(.library, label: "Library", icon: "library")
                }
            }
            .animation(Yoin.spatial, value: showsPill)
            .padding(10)
            .frame(height: 76)
            .glassEffect(.regular, in: .capsule)
        }
    }

    private func tabButton(_ t: MacModel.Tab, label: String, icon: String) -> some View {
        let on = model.tab == t && !(model.detail != nil && !model.detailAsColumn)
        return Button { model.select(t) } label: {
            HStack(spacing: 10) {
                YoinIcon(on ? "\(icon)_filled" : icon, size: 22)
                Text(label).font(.gsf(15, on ? .semibold : .regular)).lineLimit(1).fixedSize()
            }
            .foregroundStyle(on ? tint.onPrimary : Yoin.ink)
            .padding(.horizontal, 20)
            .frame(height: 56)
            .background(Capsule().fill(on ? tint.primary.opacity(0.85) : .clear))
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .animation(Yoin.effects, value: on)
    }

    /// The open detail page's Play (▾: shuffle, play next) and Shuffle.
    private var detailPlay: some View {
        HStack(spacing: 4) {
            Button { playDetail(shuffle: false) } label: {
                HStack(spacing: 6) {
                    YoinIcon("play_filled", size: 20)
                    Text("Play").font(.gsf(15, .semibold)).lineLimit(1).fixedSize()
                }
                    .foregroundStyle(tint.onPrimary)
                    .padding(.leading, 18).padding(.trailing, 14).frame(height: 56)
                    .background(UnevenRoundedRectangle(topLeadingRadius: 28, bottomLeadingRadius: 28,
                                                       bottomTrailingRadius: 8, topTrailingRadius: 8)
                        .fill(tint.primary.opacity(0.85)))
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .fixedSize()
            Menu {
                Button("Play") { playDetail(shuffle: false) }
                Button("Shuffle") { playDetail(shuffle: true) }
            } label: {
                YoinIcon("chevron_down", size: 18)
                    .foregroundStyle(tint.onPrimary)
                    .frame(width: 38, height: 56)
                    .background(UnevenRoundedRectangle(topLeadingRadius: 8, bottomLeadingRadius: 8,
                                                       bottomTrailingRadius: 28, topTrailingRadius: 28)
                        .fill(tint.primary.opacity(0.85)))
                    .contentShape(Rectangle())
            }
            .menuStyle(.button)
            .buttonStyle(.plain)
            .menuIndicator(.hidden)
            .fixedSize()
            Button { playDetail(shuffle: true) } label: {
                // An unselected bar button: ink on the glass (onPrimary here was white on white).
                YoinIcon("shuffle", size: 22).foregroundStyle(Yoin.ink)
                    .frame(width: 56, height: 56).contentShape(Circle())
            }
            .buttonStyle(.plain)
            .padding(.leading, 10)
            .help("Shuffle")
        }
    }

    private func playDetail(shuffle: Bool) {
        switch model.detail {
        case .album(let a): model.play(album: model.library.album(a.id.rawId) ?? a, shuffle: shuffle)
        case .artist(let id, _):
            if let first = model.library.artistPage(id)?.releases.first { model.play(album: first, shuffle: shuffle) }
        case .playlist(let id, _): model.play(playlist: id, shuffle: shuffle)
        case nil: break
        }
    }
}

/// The bar's Now Playing pill: cover, title, artist, and the song's progress as a fill behind them.
struct NowPlayingPill: View {
    @Environment(MacModel.self) private var model
    @Environment(\.macTint) private var tint
    /// The pill's own width: wide enough, it carries previous / play-pause / next.
    @State private var width: CGFloat = 0
    static let controlsMinWidth: CGFloat = 440

    var body: some View {
        let entry = model.currentEntry
        let track = model.player.currentTrack
        Button { if track != nil { model.toggleNowPlaying() } } label: {
            HStack(spacing: 14) {
                // A song's cover is a rounded square: in Yoin a circle means an artist.
                Group {
                    if let entry { MacCover(palette: entry.album.palette, corner: 8) }
                    else { RoundedRectangle(cornerRadius: 8, style: .continuous).fill(tint.primary.opacity(0.25)) }
                }
                .frame(width: 44, height: 44)
                VStack(alignment: .leading, spacing: 2) {
                    Text(track?.title ?? "Not playing").font(.gsf(16, .semibold)).lineLimit(1)
                    if let track { Text(track.artist).font(.gsf(13.5)).opacity(0.75).lineLimit(1) }
                }
                .foregroundStyle(tint.onContainer)
                Spacer(minLength: 16)
                if track != nil {
                    let full = width >= Self.controlsMinWidth
                    HStack(spacing: 8) {
                        if full {
                            control("skip_previous_filled") { model.player.previous() }
                                .transition(.scale(scale: 0.6).combined(with: .opacity))
                        }
                        control(model.player.isPlaying ? "pause_filled" : "play_filled") { model.player.togglePlayPause() }
                        if full {
                            control("skip_next_filled") { model.player.next() }
                                .transition(.scale(scale: 0.6).combined(with: .opacity))
                        }
                    }
                    .animation(Yoin.standardFastSpatial, value: full)
                }
            }
            // The cover sits 6 inside the capsule's curve, not against it.
            .padding(.leading, 6).padding(.trailing, 14)
            .frame(maxWidth: .infinity, minHeight: 56, maxHeight: 56)
            .background { PillProgress(color: tint.primary.opacity(0.25)) }
            .background(Capsule().fill(tint.container.opacity(0.7)))
            .clipShape(Capsule())
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .frame(minWidth: 260)
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
    }

    /// A control inside the pill: its own tap, so it doesn't open Now Playing.
    private func control(_ icon: String, action: @escaping () -> Void) -> some View {
        YoinIcon(icon, size: 21)
            .foregroundStyle(tint.onContainer)
            .frame(width: 40, height: 40)
            .contentShape(Circle())
            .onTapGesture(perform: action)
    }
}

/// Reads the 4 Hz position in its own body, so only the wash redraws. iOS PillWash: a soft tint up to the
/// position, its leading edge a slow 4 pt wave while the song plays.
private struct PillProgress: View {
    var color: Color
    var amplitude: Double = 4
    @Environment(MacModel.self) private var model

    var body: some View {
        let progress = model.player.progress.fraction
        let playing = model.player.isPlaying
        TimelineView(.animation(minimumInterval: 1 / 30, paused: !playing)) { ctx in
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

/// A playback failure the engine is holding on (iOS PlaybackFailureBanner): the message and Retry; when
/// Spotify plays on another device, Play Here moves it to this Mac.
struct MacPlaybackFailureBanner: View {
    var failure: PlaybackFailure
    @Environment(MacModel.self) private var model
    @Environment(\.macTint) private var tint

    var body: some View {
        let elsewhere = failure.track.provider == SpotifyMapping.provider && failure.message.hasPrefix("Spotify is playing on")
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 1) {
                Text("Playback unavailable").font(.gsf(13.5, .semibold)).foregroundStyle(Yoin.ink)
                Text(failure.message).font(.gsf(12.5)).foregroundStyle(Yoin.inkVariant).lineLimit(2)
            }
            Spacer(minLength: 8)
            if elsewhere {
                Button("Play Here") { model.spotifyPlayHere() }
                    .buttonStyle(.plain).font(.gsf(13.5, .semibold)).foregroundStyle(tint.onPrimary)
                    .padding(.horizontal, 14).frame(height: 32)
                    .background(Capsule().fill(tint.primary))
            }
            Button("Retry") { model.player.retry() }
                .buttonStyle(.plain).font(.gsf(13.5, .semibold)).foregroundStyle(tint.primary)
                .padding(.horizontal, 12).frame(height: 32)
        }
        .padding(.leading, 18).padding(.trailing, 8).padding(.vertical, 8)
        .frame(maxWidth: 520)
        .glassEffect(.regular, in: .capsule)
    }
}
