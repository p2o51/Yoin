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
            HStack(spacing: 6) {
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
            .padding(8)
            .frame(height: 60)
            .glassEffect(.regular, in: .capsule)
        }
    }

    private func tabButton(_ t: MacModel.Tab, label: String, icon: String) -> some View {
        let on = model.tab == t && !(model.detail != nil && !model.detailAsColumn)
        return Button { model.select(t) } label: {
            HStack(spacing: 8) {
                YoinIcon(on ? "\(icon)_filled" : icon, size: 21)
                Text(label).font(.gsf(14, on ? .semibold : .regular))
            }
            .foregroundStyle(on ? tint.onPrimary : Yoin.ink)
            .padding(.horizontal, 14)
            .frame(height: 44)
            .background(Capsule().fill(on ? tint.primary.opacity(0.85) : .clear))
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .animation(Yoin.effects, value: on)
    }

    /// The open detail page's Play (▾: shuffle, play next) and Shuffle.
    private var detailPlay: some View {
        HStack(spacing: 2) {
            Button { playDetail(shuffle: false) } label: {
                HStack(spacing: 6) { YoinIcon("play_filled", size: 18); Text("Play").font(.gsf(14, .semibold)) }
                    .padding(.leading, 14).padding(.trailing, 10).frame(height: 44)
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
                YoinIcon("chevron_down", size: 16)
                    .frame(width: 34, height: 44)
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
                YoinIcon("shuffle", size: 20).frame(width: 44, height: 44).contentShape(Circle())
            }
            .buttonStyle(.plain)
            .padding(.leading, 4)
            .help("Shuffle")
        }
        .foregroundStyle(tint.onPrimary)
    }

    private func playDetail(shuffle: Bool) {
        switch model.detail {
        case .album(let a): model.play(album: model.library.album(a.id.rawId) ?? a, shuffle: shuffle)
        case .artist(let id, _):
            if let first = model.library.artistPage(id)?.releases.first { model.play(album: first, shuffle: shuffle) }
        case nil: break
        }
    }
}

/// The bar's Now Playing pill: cover, title, artist, and the song's progress as a fill behind them.
struct NowPlayingPill: View {
    @Environment(MacModel.self) private var model
    @Environment(\.macTint) private var tint

    var body: some View {
        let entry = model.currentEntry
        let track = model.player.currentTrack
        Button { if track != nil { model.toggleNowPlaying() } } label: {
            HStack(spacing: 10) {
                Group {
                    if let entry { MacCover(palette: entry.album.palette, circle: true) }
                    else { Circle().fill(tint.primary.opacity(0.25)) }
                }
                .frame(width: 36, height: 36)
                VStack(alignment: .leading, spacing: 1) {
                    Text(track?.title ?? "Not playing").font(.gsf(14, .semibold)).lineLimit(1)
                    if let track { Text(track.artist).font(.gsf(12)).opacity(0.75).lineLimit(1) }
                }
                .foregroundStyle(tint.onContainer)
                Spacer(minLength: 0)
                if track != nil {
                    YoinIcon(model.player.isPlaying ? "pause_filled" : "play_filled", size: 18)
                        .foregroundStyle(tint.onContainer)
                        .frame(width: 32, height: 32)
                        .contentShape(Circle())
                        .onTapGesture { model.player.togglePlayPause() }
                }
            }
            .padding(.leading, 4).padding(.trailing, 8)
            .frame(maxWidth: .infinity, minHeight: 44, maxHeight: 44)
            .background { PillProgress(color: tint.primary.opacity(0.22)) }
            .background(Capsule().fill(tint.container.opacity(0.7)))
            .clipShape(Capsule())
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .frame(minWidth: 180)
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
