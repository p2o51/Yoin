import SwiftUI

/// The menu bar extra's card: cover, the song, transport and progress, and a way back to the window.
struct MacMenuBarCard: View {
    @Environment(MacModel.self) private var model
    @Environment(\.colorScheme) private var scheme
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        let tint = MacTint.of(model.player.currentTrack?.coverURL, dark: scheme == .dark)
        let p = model.player
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                Group {
                    if let e = model.currentEntry {
                        MacCover(palette: e.album.palette, corner: Tokens.shape.THUMB.cg)
                    } else {
                        RoundedRectangle(cornerRadius: Tokens.shape.THUMB.cg, style: .continuous).fill(tint.container)
                    }
                }
                .frame(width: 56, height: 56)
                VStack(alignment: .leading, spacing: 2) {
                    Text(p.currentTrack?.title ?? "Not playing").font(.gsf(15, .semibold)).foregroundStyle(Yoin.ink)
                        .lineLimit(1)
                    if let t = p.currentTrack {
                        Text(t.artist).font(.gsf(13)).foregroundStyle(Yoin.inkVariant).lineLimit(1)
                    }
                }
                Spacer(minLength: 0)
            }
            CardProgress()
            HStack(spacing: 10) {
                control("skip_previous_filled", prominent: false) { p.previous() }
                control(p.isPlaying ? "pause_filled" : "play_filled", prominent: true) { p.togglePlayPause() }
                control("skip_next_filled", prominent: false) { p.next() }
                Spacer(minLength: 0)
                Button("Open Yoin") {
                    openWindow(id: "main")
                    NSApp.activate()
                }
                .font(.gsf(13, .medium))
                .buttonStyle(.plain)
                .foregroundStyle(tint.primary)
            }
            .disabled(p.currentTrack == nil)
        }
        .padding(14)
        .frame(width: 300)
        .environment(\.macTint, tint)
    }

    private func control(_ icon: String, prominent: Bool, action: @escaping () -> Void) -> some View {
        let tint = MacTint.of(model.player.currentTrack?.coverURL, dark: scheme == .dark)
        return Button(action: action) {
            YoinIcon(icon, size: prominent ? 20 : 18)
                .foregroundStyle(prominent ? tint.onPrimary : Yoin.ink)
                .frame(width: prominent ? 40 : 34, height: prominent ? 40 : 34)
                .background(Circle().fill(prominent ? tint.primary : tint.container.opacity(0.7)))
                .contentShape(Circle())
        }
        .buttonStyle(PressDown())
    }
}

/// Reads the position in its own body, so only the bar redraws.
private struct CardProgress: View {
    @Environment(MacModel.self) private var model
    @Environment(\.macTint) private var tint

    var body: some View {
        let p = model.player.progress
        VStack(spacing: 4) {
            GeometryReader { g in
                ZStack(alignment: .leading) {
                    Capsule().fill(tint.primary.opacity(0.18))
                    Capsule().fill(tint.primary).frame(width: g.size.width * p.fraction)
                }
            }
            .frame(height: 4)
            HStack {
                Text(formatTime(Int(p.positionSec)))
                Spacer()
                Text("-" + formatTime(max(0, Int(p.durationSec - p.positionSec))))
            }
            .font(.gsf(11).monospacedDigit())
            .foregroundStyle(Yoin.inkVariant)
        }
    }
}
