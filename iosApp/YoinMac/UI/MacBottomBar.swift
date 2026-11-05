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
            .frame(height: 64)
            .glassEffect(.regular, in: .capsule)
        }
    }

    private func tabButton(_ t: MacModel.Tab, label: String, icon: String) -> some View {
        let on = model.tab == t && !(model.detail != nil && !model.detailAsColumn)
        return Button { model.select(t) } label: {
            HStack(spacing: 10) {
                YoinIcon(on ? "\(icon)_filled" : icon, size: 22)
                Text(label).font(.gsf(15, on ? .semibold : .regular))
            }
            .foregroundStyle(on ? tint.onPrimary : Yoin.ink)
            .padding(.horizontal, 20)
            .frame(height: 48)
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
                HStack(spacing: 6) { YoinIcon("play_filled", size: 20); Text("Play").font(.gsf(15, .semibold)) }
                    .padding(.leading, 18).padding(.trailing, 14).frame(height: 48)
                    .background(UnevenRoundedRectangle(topLeadingRadius: 22, bottomLeadingRadius: 22,
                                                       bottomTrailingRadius: 8, topTrailingRadius: 8)
                        .fill(tint.primary.opacity(0.85)))
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            Menu {
                Button("Play") { playDetail(shuffle: false) }
                Button("Shuffle") { playDetail(shuffle: true) }
            } label: {
                YoinIcon("chevron_down", size: 18)
                    .frame(width: 38, height: 48)
                    .background(UnevenRoundedRectangle(topLeadingRadius: 8, bottomLeadingRadius: 8,
                                                       bottomTrailingRadius: 22, topTrailingRadius: 22)
                        .fill(tint.primary.opacity(0.85)))
                    .contentShape(Rectangle())
            }
            .menuStyle(.button)
            .buttonStyle(.plain)
            .menuIndicator(.hidden)
            .fixedSize()
            Button { playDetail(shuffle: true) } label: {
                YoinIcon("shuffle", size: 22).frame(width: 48, height: 48).contentShape(Circle())
            }
            .buttonStyle(.plain)
            .padding(.leading, 10)
            .help("Shuffle")
        }
        .foregroundStyle(tint.onPrimary)
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
                    if let entry { MacCover(palette: entry.album.palette, corner: 7) }
                    else { RoundedRectangle(cornerRadius: 7, style: .continuous).fill(tint.primary.opacity(0.25)) }
                }
                .frame(width: 40, height: 40)
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
            .padding(.leading, 4).padding(.trailing, 14)
            .frame(maxWidth: .infinity, minHeight: 48, maxHeight: 48)
            .background { PillProgress(color: tint.primary.opacity(0.22)) }
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

/// Reads the 4 Hz position in its own body, so only the fill redraws.
private struct PillProgress: View {
    var color: Color
    @Environment(MacModel.self) private var model

    var body: some View {
        GeometryReader { g in
            Rectangle().fill(color).frame(width: g.size.width * model.player.progress.fraction)
        }
    }
}
