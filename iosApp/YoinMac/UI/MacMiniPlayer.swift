import AppKit
import SwiftUI

/// The mini player window (⌥⌘M): the cover, the spine beside it, the song, transport and progress.
/// Floats over other apps unless "Float Mini Player on Top" is turned off in the Controls menu.
struct MacMiniPlayer: View {
    @Environment(MacModel.self) private var model
    @Environment(\.colorScheme) private var scheme
    @AppStorage(MacMiniPlayer.floatsKey) private var floats = true

    static let windowID = "mini"
    static let floatsKey = "miniPlayerFloats"
    private static let cover: CGFloat = 132

    var body: some View {
        let tint = MacTint.of(model.player.currentTrack?.coverURL, dark: scheme == .dark)
        let track = model.player.currentTrack
        HStack(spacing: 0) {
            Group {
                if let e = model.currentEntry {
                    MacCover(palette: e.album.palette, corner: Tokens.shape.COVER.cg)
                } else {
                    RoundedRectangle(cornerRadius: Tokens.shape.COVER.cg, style: .continuous)
                        .fill(tint.container)
                }
            }
            .frame(width: Self.cover, height: Self.cover)
            MacSpine(items: spineItems, length: Self.cover)
                .frame(width: 30, height: Self.cover)
            VStack(alignment: .leading, spacing: 0) {
                Text(track?.title ?? "Not playing").font(.gsf(16, .semibold)).foregroundStyle(Yoin.ink)
                    .lineLimit(1)
                Text(track?.artist ?? "").font(.gsf(13.5)).foregroundStyle(Yoin.inkVariant).lineLimit(1)
                    .padding(.top, 2)
                Spacer(minLength: 8)
                MiniTransport()
                MiniProgress().padding(.top, 10)
            }
            .padding(.vertical, 4)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(14)
        .frame(width: 420, height: Self.cover + 28)
        .background {
            ZStack {
                Yoin.surface.mix(with: tint.container, by: 0.34, in: .perceptual)
                LinearGradient(colors: [tint.primary.opacity(0.14), .clear], startPoint: .leading, endPoint: .trailing)
            }
            .ignoresSafeArea()
        }
        .environment(\.macTint, tint)
        .animation(Yoin.effects, value: tint)
        .background(WindowLevel(floating: floats))
        .onAppear { model.miniOpen = true }
        .onDisappear { model.miniOpen = false }
    }

    /// What the spine shows, one at a time: the song, the artist, the album, the year.
    private var spineItems: [String] {
        guard let t = model.player.currentTrack else { return [] }
        let album = model.currentEntry?.album
        return [t.title, t.artist, album?.title ?? t.album, album.flatMap { $0.year > 0 ? String($0.year) : nil }]
            .compactMap { $0 }
            .filter { !$0.isEmpty }
    }
}

/// The spine: one item at a time running up beside the cover. Every few seconds (or a scroll) it turns to
/// the next; an item longer than the spine keeps moving instead of being cut.
private struct MacSpine: View {
    var items: [String]
    var length: CGFloat
    @State private var index = 0
    @State private var scrollDebt: CGFloat = 0

    var body: some View {
        let text = items.isEmpty ? "" : items[index % items.count]
        ZStack {
            SpineText(text: text, length: length)
                .id(text)
                .transition(.asymmetric(insertion: .move(edge: .bottom).combined(with: .opacity),
                                        removal: .move(edge: .top).combined(with: .opacity)))
        }
        .frame(width: 30, height: length)
        .clipped()
        .contentShape(Rectangle())
        .onScrollWheel { dy in
            scrollDebt += dy
            if abs(scrollDebt) > 12 { step(scrollDebt > 0 ? -1 : 1); scrollDebt = 0 }
        }
        .task(id: items) {
            index = 0
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(5))
                step(1)
            }
        }
    }

    private func step(_ d: Int) {
        guard items.count > 1 else { return }
        withAnimation(Yoin.spatial) { index = (index + d + items.count) % items.count }
    }
}

/// One spine item turned a quarter round; it marquees when it's longer than the spine.
private struct SpineText: View {
    var text: String
    var length: CGFloat
    @State private var textWidth: CGFloat = 0

    var body: some View {
        let overflow = max(0, textWidth - length)
        TimelineView(.animation(minimumInterval: 1 / 30, paused: overflow == 0)) { ctx in
            let gap: CGFloat = 40
            let speed: CGFloat = 24
            let cycle = (textWidth + gap) / speed + 2
            let t = ctx.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: cycle)
            let x = overflow == 0 ? 0 : -max(0, CGFloat(t) - 2) * speed
            HStack(spacing: gap) {
                label
                if overflow > 0 { label }
            }
            .offset(x: x)
            .frame(width: length, alignment: overflow > 0 ? .leading : .center)
            .clipped()
            .mask(LinearGradient(stops: overflow > 0
                                    ? [.init(color: .clear, location: 0), .init(color: .black, location: 0.12),
                                       .init(color: .black, location: 0.88), .init(color: .clear, location: 1)]
                                    : [.init(color: .black, location: 0), .init(color: .black, location: 1)],
                                 startPoint: .leading, endPoint: .trailing))
        }
        .frame(width: length, height: 30)
        .rotationEffect(.degrees(-90))
        .frame(width: 30, height: length)
    }

    private var label: some View {
        Text(text).font(.gsf(13, .medium)).foregroundStyle(Yoin.inkVariant)
            .fixedSize()
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { textWidth = $0 }
    }
}

private struct MiniTransport: View {
    @Environment(MacModel.self) private var model
    @Environment(\.macTint) private var tint

    var body: some View {
        let p = model.player
        HStack(spacing: 10) {
            button("skip_previous_filled", size: 40, prominent: false) { p.previous() }
            button(p.isPlaying ? "pause_filled" : "play_filled", size: 48, prominent: true) { p.togglePlayPause() }
            button("skip_next_filled", size: 40, prominent: false) { p.next() }
        }
        .disabled(p.currentTrack == nil)
    }

    private func button(_ icon: String, size: CGFloat, prominent: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            YoinIcon(icon, size: prominent ? 24 : 20)
                .foregroundStyle(prominent ? tint.onPrimary : Yoin.ink)
                .frame(width: size, height: size)
                .background(Circle().fill(prominent ? tint.primary : tint.container.opacity(0.7)))
                .contentShape(Circle())
        }
        .buttonStyle(PressDown())
    }
}

/// Reads the position in its own body, so only the bar redraws.
private struct MiniProgress: View {
    @Environment(MacModel.self) private var model
    @Environment(\.macTint) private var tint

    var body: some View {
        let p = model.player.progress
        HStack(spacing: 8) {
            Text(formatTime(Int(p.positionSec))).frame(width: 34, alignment: .trailing)
            WavyProgressBar(progress: p.fraction, duration: max(p.durationSec, 1), isPlaying: model.player.isPlaying,
                            tint: tint.primary, trackTint: tint.primary.opacity(0.22)) { f in
                model.player.seek(to: f * p.durationSec)
            }
            .frame(height: 18)
            Text("-" + formatTime(max(0, Int(p.durationSec - p.positionSec)))).frame(width: 38, alignment: .leading)
        }
        .font(.gsf(11.5).monospacedDigit())
        .foregroundStyle(Yoin.inkVariant)
    }
}

/// Sets the hosting window's level (floating over other apps, or normal).
private struct WindowLevel: NSViewRepresentable {
    var floating: Bool

    func makeNSView(context: Context) -> NSView { NSView() }

    func updateNSView(_ view: NSView, context: Context) {
        DispatchQueue.main.async {
            view.window?.level = floating ? .floating : .normal
            view.window?.collectionBehavior.insert(.fullScreenAuxiliary)
        }
    }
}

private extension View {
    /// Vertical scroll-wheel deltas over this view.
    func onScrollWheel(_ action: @escaping (CGFloat) -> Void) -> some View {
        overlay(ScrollWheelCatcher(action: action))
    }
}

private struct ScrollWheelCatcher: NSViewRepresentable {
    var action: (CGFloat) -> Void

    func makeNSView(context: Context) -> CatcherView {
        let v = CatcherView()
        v.action = action
        return v
    }

    func updateNSView(_ v: CatcherView, context: Context) { v.action = action }

    final class CatcherView: NSView {
        var action: ((CGFloat) -> Void)?
        override func scrollWheel(with event: NSEvent) { action?(event.scrollingDeltaY) }
    }
}
