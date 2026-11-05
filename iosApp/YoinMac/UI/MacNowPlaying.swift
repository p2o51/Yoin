import SwiftUI
import YoinShared

/// Now Playing in its two sizes: the 420 side panel and the full window (two columns). Clicking the
/// cover toggles focus: the chrome fades, the cover grows, the lyrics stay.
struct MacNowPlaying: View {
    var full: Bool
    @Environment(MacModel.self) private var model
    @Environment(\.macTint) private var tint
    @State private var tab: NPTab = .lyrics
    @State private var transportHeight: CGFloat = 0
    /// iOS World preset: transportPrimary 0.70, transportSecondary 0.28.
    static let transportPrimaryTint = 0.70
    static let transportSecondaryTint = 0.28

    enum NPTab: String, CaseIterable { case lyrics = "Lyrics", about = "About" }

    var body: some View {
        GeometryReader { g in
            ZStack {
                MacRoom()
                if full { fullLayout(g.size) } else { panelLayout(g.size) }
            }
        }
        .clipShape(UnevenRoundedRectangle(topLeadingRadius: full ? 0 : 22, bottomLeadingRadius: full ? 0 : 22,
                                          bottomTrailingRadius: 0, topTrailingRadius: 0, style: .continuous))
        .shadow(color: .black.opacity(full ? 0 : 0.18), radius: 20, x: -10)
    }

    // MARK: Layouts

    private func panelLayout(_ size: CGFloat2) -> some View {
        let inner = size.width - 48
        // The cover gives way first when the window is short.
        let fixed: CGFloat = model.focus ? 400 : 520
        let side = min(inner - (model.focus ? 0 : 68), max(120, size.height - fixed))
        return VStack(alignment: .leading, spacing: 0) {
            header.padding(.top, 6).padding(.bottom, 14).chrome(model.focus)
            art(side: side, barHeight: min(220, side * 0.72))
            tabs.padding(.top, 18).padding(.bottom, 8).chrome(model.focus)
            // The lyrics run on under the glass transport; the current line stays in the clear band
            // above it (iOS lyricWindow).
            lyricsOverControls
            meta(large: false).padding(.top, 20)
            pills.padding(.top, 18).chrome(model.focus)
        }
        .padding(.horizontal, 24)
        .padding(.top, 20)
        .padding(.bottom, 22)
    }

    private func fullLayout(_ size: CGFloat2) -> some View {
        let leftW = min(model.focus ? 560 : 440, max(300, (size.width - 160) * (model.focus ? 0.5 : 0.42)))
        // Focus hides the header and the pills: the cover takes their height.
        let fixedLeft: CGFloat = (model.focus ? 0 : 54 + 62) + 24 + 110 + 160 + 34
        let side = min(leftW - (model.focus ? 0 : 68), max(160, size.height - fixedLeft))
        return HStack(alignment: .top, spacing: 40) {
            VStack(alignment: .leading, spacing: 0) {
                header.padding(.leading, 58).padding(.bottom, 14).chrome(model.focus)
                Spacer(minLength: 0)
                art(side: side, barHeight: min(220, side * 0.62))
                meta(large: true).padding(.top, 24)
                controls.padding(.top, 12)
                pills.padding(.top, 18).chrome(model.focus)
            }
            .frame(width: leftW)
            VStack(alignment: .leading, spacing: 0) {
                tabs.padding(.top, 12).padding(.bottom, 8).chrome(model.focus)
                tabContent(large: true).frame(maxHeight: .infinity)
            }
            .frame(maxWidth: .infinity)
        }
        .padding(.leading, 64).padding(.trailing, 56)
        .padding(.top, 24).padding(.bottom, 34)
    }

    // MARK: Parts

    private var header: some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text("PLAYING FROM").font(.gsf(11)).tracking(0.6).foregroundStyle(Yoin.inkVariant)
                Text(model.currentEntry?.album.title ?? model.player.currentTrack?.album ?? "")
                    .font(.gsf(13.5, .medium)).foregroundStyle(Yoin.ink).lineLimit(1)
            }
            Spacer(minLength: 8)
            if !full {
                GlassCircleButton(icon: "unfold_more", size: 32) { model.expandNowPlaying() }.help("Full Window")
            }
            GlassCircleButton(icon: full ? "chevron_down" : "close", size: 32) { model.back() }
                .help(full ? "Back" : "Close")
        }
        .frame(height: 40)
    }

    private func art(side: CGFloat, barHeight: CGFloat) -> some View {
        HStack(alignment: .top, spacing: model.focus ? 0 : 12) {
            Group {
                if let e = model.currentEntry {
                    MacCover(palette: e.album.palette, corner: Tokens.shape.NOW_PLAYING_COVER.cg)
                } else {
                    RoundedRectangle(cornerRadius: Tokens.shape.NOW_PLAYING_COVER.cg).fill(tint.container)
                }
            }
            .frame(width: side, height: side)
            .shadow(color: tint.primary.opacity(0.3), radius: 24, y: 18)
            .onTapGesture { model.toggleFocus() }
            .help(model.focus ? "Leave Focus" : "Focus")
            VStack(spacing: 12) {
                RatingBar(height: barHeight)
                HeartButton()
            }
            .frame(width: model.focus ? 0 : 56)
            .opacity(model.focus ? 0 : 1)
            .clipped()
        }
    }

    private var tabs: some View {
        HStack(spacing: 16) {
            ForEach(NPTab.allCases, id: \.self) { t in
                Button { withAnimation(Yoin.effects) { tab = t } } label: {
                    Text(t.rawValue).font(.gsf(15, tab == t ? .semibold : .regular))
                        .foregroundStyle(tab == t ? Yoin.ink : Yoin.ink3)
                }
                .buttonStyle(.plain)
            }
        }
    }

    private var lyricsOverControls: some View {
        VStack(spacing: 0) {
            Color.clear.frame(maxHeight: .infinity)
            controls.padding(.top, 12)
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { transportHeight = $0 }
        }
        .background(alignment: .top) {
            switch tab {
            case .lyrics: MacLyricsView(large: false, under: transportHeight)
            case .about: MacAboutView().chrome(model.focus).padding(.bottom, transportHeight)
            }
        }
    }

    @ViewBuilder private func tabContent(large: Bool) -> some View {
        switch tab {
        case .lyrics: MacLyricsView(large: large, under: 0)
        case .about: MacAboutView().chrome(model.focus)
        }
    }

    private func meta(large: Bool) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(model.player.currentTrack?.artist ?? "").font(.gsf(15)).foregroundStyle(Yoin.inkVariant).lineLimit(1)
            Text(model.player.currentTrack?.title ?? "Not playing")
                .font(.gsf(large ? 40 : 34, .semibold)).foregroundStyle(Yoin.ink)
                .lineLimit(2).minimumScaleFactor(0.7)
        }
    }

    private var controls: some View {
        let p = model.player
        return VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 10) {
                Button { p.togglePlayPause() } label: {
                    Text(p.isPlaying ? "PAUSE" : "PLAY").font(.gsf(22, .bold)).tracking(0.7)
                        .foregroundStyle(tint.onPrimary)
                        .frame(minWidth: 136, minHeight: 64).padding(.horizontal, 10)
                        // macOS glass takes a tint much paler than iOS: the 0.70 primary is laid under
                        // it as a fill, so PLAY reads like iOS and the lyrics still show through.
                        .background(Capsule().fill(tint.primary.opacity(Self.transportPrimaryTint)))
                        .contentShape(Capsule())
                }
                .buttonStyle(PressDown())
                // iOS World transportPrimary: tinted glass the lyrics show through.
                .glassEffect(.regular.tint(tint.primary.opacity(Self.transportPrimaryTint)).interactive(), in: .capsule)
                .animation(Yoin.standardFastSpatial, value: p.isPlaying)
                RoundControl(icon: "skip_next_filled") { p.next() }
                Spacer(minLength: 0)
                RoundControl(icon: p.shuffle ? "shuffle" : "repeat", badge: p.repeatMode == .one ? "1" : nil,
                             dim: p.repeatMode == .off) { p.cyclePlayMode() }
                    .help("Play Mode")
            }
            HStack(spacing: 10) {
                RoundControl(icon: "skip_previous_filled") { p.previous() }
                ProgressRow()
            }
        }
    }

    private var pills: some View {
        HStack(spacing: 8) {
            QueuePill()
            NPPill(icon: "devices", title: "Devices", enabled: false) {}
            NPPill(icon: "edit_note", title: "Write", enabled: false) {}
        }
    }
}

typealias CGFloat2 = CGSize

private extension View {
    /// Chrome that fades in focus.
    func chrome(_ focus: Bool) -> some View {
        opacity(focus ? 0 : 1).allowsHitTesting(!focus)
    }
}

/// The NP room: the current cover's colour in the middle, the previous and next songs' colours seeping in
/// from the top and bottom edges.
struct MacRoom: View {
    @Environment(MacModel.self) private var model
    @Environment(\.macTint) private var tint
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let prev = MacTint.of(model.previousTrack?.coverURL, dark: scheme == .dark)
        let next = MacTint.of(model.player.upNextTrack?.coverURL, dark: scheme == .dark)
        GeometryReader { g in
            ZStack {
                Yoin.surface.mix(with: tint.container, by: 0.34, in: .perceptual)
                EllipticalGradient(colors: [tint.primary.opacity(0.3), .clear], center: UnitPoint(x: 0.5, y: 0.46),
                                   endRadiusFraction: 0.6)
                EllipticalGradient(colors: [prev.container, .clear], center: .top, endRadiusFraction: 0.5)
                    .frame(height: g.size.height * 0.6).frame(maxHeight: .infinity, alignment: .top)
                EllipticalGradient(colors: [next.container, .clear], center: .bottom, endRadiusFraction: 0.5)
                    .frame(height: g.size.height * 0.6).frame(maxHeight: .infinity, alignment: .bottom)
            }
        }
        .animation(Yoin.slowEffects, value: tint)
        .animation(Yoin.slowEffects, value: prev)
        .animation(Yoin.slowEffects, value: next)
        .task(id: model.previousTrack?.coverURL) { await load(model.previousTrack?.coverURL) }
        .task(id: model.player.upNextTrack?.coverURL) { await load(model.player.upNextTrack?.coverURL) }
    }

    private func load(_ url: URL?) async {
        if let url { await CoverArtwork.shared.load(CoverRef(url: url)) }
    }
}

/// A 64 pt round transport button (solid).
struct RoundControl: View {
    /// iOS GlassLab's transportSecondary default tint.
    static let fill = 0.55
    var icon: String
    var badge: String? = nil
    var dim = false
    var action: () -> Void
    @Environment(\.macTint) private var tint

    var body: some View {
        Button(action: action) {
            YoinIcon(icon, size: 26)
                .foregroundStyle(Yoin.ink.opacity(dim ? 0.45 : 1))
                .frame(width: 64, height: 64)
                // Under the glass, so it stays visibly tinted (macOS glass tints pale, greyer when inactive).
                .background(Circle().fill(tint.container.opacity(Self.fill)))
                .overlay(alignment: .topTrailing) {
                    if let badge {
                        Text(badge).font(.gsf(11, .bold)).foregroundStyle(tint.onPrimary)
                            .frame(width: 18, height: 18).background(Circle().fill(tint.primary)).offset(x: -6, y: 8)
                    }
                }
                .contentShape(Circle())
        }
        .buttonStyle(PressDown())
        .glassEffect(.regular.tint(tint.container.opacity(MacNowPlaying.transportSecondaryTint)).interactive(),
                     in: .circle)
    }
}

struct PressDown: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.92 : 1)
            .animation(Yoin.spatialFast, value: configuration.isPressed)
    }
}

/// Elapsed · wavy seek bar · remaining. Reads the 4 Hz position in its own body.
private struct ProgressRow: View {
    @Environment(MacModel.self) private var model
    @Environment(\.macTint) private var tint

    var body: some View {
        let p = model.player.progress
        HStack(spacing: 12) {
            Text(formatTime(Int(p.positionSec))).frame(width: 40, alignment: .trailing)
            WavyProgressBar(progress: p.fraction, duration: max(p.durationSec, 1), isPlaying: model.player.isPlaying,
                            tint: tint.primary, trackTint: tint.primary.opacity(0.22)) { f in
                model.player.seek(to: f * p.durationSec)
            }
            .frame(height: 24)
            Text("-" + formatTime(max(0, Int(p.durationSec - p.positionSec)))).frame(width: 44, alignment: .leading)
        }
        .font(.gsf(13).monospacedDigit())
        .foregroundStyle(Yoin.inkVariant)
    }
}

/// The vertical rating bar: drag up / down to rate (Yoin 0–10 in the server's whole stars); it turns to
/// glass while dragged.
struct RatingBar: View {
    var height: CGFloat
    @Environment(MacModel.self) private var model
    @Environment(\.macTint) private var tint
    @State private var dragValue: Double?

    var body: some View {
        let t = model.player.currentTrack
        let stored = t.map { model.library.rating(songId: $0.id, fallback: model.currentEntry?.track.rating ?? $0.userRating.map { Double($0 * 2) }) } ?? nil
        let value = dragValue ?? stored ?? 0
        ZStack(alignment: .bottom) {
            Capsule().fill(dragValue != nil ? Color.clear : tint.primary.opacity(0.12))
            Capsule().fill(tint.primary.mix(with: .black, by: 0.06))
                .frame(height: max(0, height * value / 10))
            Text(value > 0 ? String(format: "%.1f", value) : "")
                .font(.gsf(22, .medium).monospacedDigit())
                .lineLimit(1).minimumScaleFactor(0.7)
                .foregroundStyle(value >= 9.2 ? tint.onPrimary : Yoin.ink)
                .frame(maxHeight: .infinity, alignment: .top).padding(.top, 10)
        }
        .frame(width: 56, height: height)
        .glassEffect(dragValue != nil ? .regular : .identity, in: .capsule)
        .contentShape(Capsule())
        .gesture(
            DragGesture(minimumDistance: 0)
                .onChanged { g in
                    guard t != nil else { return }
                    let raw = (1 - g.location.y / height) * 10
                    let stars = (min(max(raw, 0), 10) / 2).rounded()
                    withAnimation(Yoin.spatialFast) { dragValue = stars * 2 }
                }
                .onEnded { _ in
                    if let t, let v = dragValue { model.library.setRating(songId: t.id, v > 0 ? v : nil) }
                    withAnimation(Yoin.effects) { dragValue = nil }
                }
        )
        .help("Rating")
    }
}

struct HeartButton: View {
    @Environment(MacModel.self) private var model
    @Environment(\.macTint) private var tint

    var body: some View {
        let on = model.isLikedCurrent()
        Button { model.toggleLikeCurrent() } label: {
            YoinIcon(on ? "favorite_filled" : "favorite", size: 22)
                .foregroundStyle(tint.primary)
                .frame(width: 46, height: 46)
                .background(Circle().fill(tint.container.opacity(0.7)))
                .contentShape(Circle())
        }
        .buttonStyle(PressDown())
        .help(on ? "Unlike" : "Like")
    }
}

struct NPPill: View {
    var icon: String
    var title: String
    var enabled: Bool
    var action: () -> Void
    @Environment(\.macTint) private var tint

    var body: some View {
        Button(action: action) {
            HStack(spacing: 7) { YoinIcon(icon, size: 17); Text(title).font(.gsf(14)) }
                .foregroundStyle(tint.onPrimary)
                .padding(.horizontal, 16).frame(height: 44)
                .background(Capsule().fill(tint.primary.opacity(0.78)))
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .glassEffect(.regular.tint(tint.primary.opacity(0.5)).interactive(), in: .capsule)
        .opacity(enabled ? 1 : 0.5)
        .disabled(!enabled)
    }
}

/// Queue: the songs after the current one. Click one to play it, drag to reorder, ✕ or right-click to
/// remove, Clear to empty what's left.
struct QueuePill: View {
    @Environment(MacModel.self) private var model
    @State private var open = false

    var body: some View {
        NPPill(icon: "queue", title: "Queue", enabled: true) { open.toggle() }
            .popover(isPresented: $open, arrowEdge: .top) { QueuePopover() }
    }
}

private struct QueuePopover: View {
    @Environment(MacModel.self) private var model

    var body: some View {
        let p = model.player
        let start = (p.currentIndex ?? 0) + 1
        let upcoming = Array(p.upcoming.prefix(80))
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text("Up Next").font(.gsf(15, .semibold))
                Spacer()
                Button("Clear") { model.clearUpcoming() }
                    .font(.gsf(13))
                    .buttonStyle(.plain)
                    .foregroundStyle(Yoin.inkVariant)
                    .disabled(upcoming.isEmpty)
            }
            .padding(.horizontal, 10)
            if upcoming.isEmpty {
                Text("Nothing after this song").font(.gsf(13)).foregroundStyle(Yoin.inkVariant).padding(10)
            } else {
                List {
                    ForEach(Array(upcoming.enumerated()), id: \.element.id) { i, item in
                        QueueRow(item: item) { p.jump(to: start + i) } remove: { p.remove(id: item.id) }
                            .listRowInsets(EdgeInsets(top: 0, leading: 0, bottom: 0, trailing: 0))
                            .listRowSeparator(.hidden)
                            .listRowBackground(Color.clear)
                    }
                    .onMove { from, to in
                        p.move(fromOffsets: IndexSet(from.map { $0 + start }), toOffset: to + start)
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                .frame(height: min(400, CGFloat(upcoming.count) * 46 + 8))
            }
        }
        .padding(12)
        .frame(width: 340)
    }
}

private struct QueueRow: View {
    var item: QueueItem
    var play: () -> Void
    var remove: () -> Void
    @Environment(\.macTint) private var tint
    @State private var hover = false

    var body: some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 1) {
                Text(item.track.title).font(.gsf(13.5)).lineLimit(1)
                Text(item.track.artist).font(.gsf(12)).foregroundStyle(Yoin.inkVariant).lineLimit(1)
            }
            Spacer(minLength: 8)
            if hover {
                Button(action: remove) {
                    YoinIcon("close", size: 16).foregroundStyle(Yoin.inkVariant).frame(width: 24, height: 24)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .help("Remove")
            } else {
                Text(formatTime(Int(item.track.durationSec))).font(.gsf(12).monospacedDigit())
                    .foregroundStyle(Yoin.ink3)
            }
        }
        .padding(.horizontal, 10).padding(.vertical, 6)
        .background(RoundedRectangle(cornerRadius: Tokens.shape.LIST_ROW.cg, style: .continuous)
            .fill(tint.primary.opacity(hover ? 0.08 : 0)))
        .contentShape(Rectangle())
        .onHover { hover = $0 }
        .onTapGesture(perform: play)
        .contextMenu {
            Button("Play") { play() }
            Button("Remove from Queue") { remove() }
        }
    }
}

/// Synced lyrics: the current line in the tint, the list scrolling to keep it in view; a click seeks.
struct MacLyricsView: View {
    var large: Bool
    /// How much of the box runs on under the transport (0: nothing under it).
    var under: CGFloat
    @Environment(MacModel.self) private var model
    @Environment(\.macTint) private var tint

    var body: some View {
        switch model.lyrics.state {
        case .idle, .loading:
            note(model.player.currentTrack == nil ? "" : "Loading lyrics")
        case .none:
            note("No lyrics for this song")
        case .loaded(let synced, let lines, _):
            LyricsList(lines: lines, synced: synced, large: large, under: under)
        }
    }

    private func note(_ s: String) -> some View {
        Text(s).font(.gsf(large ? 20 : 15)).foregroundStyle(Yoin.ink3)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading).padding(.top, 8)
            .padding(.bottom, under)
    }
}

private struct LyricsList: View {
    var lines: [MacLyrics.Line]
    var synced: Bool
    var large: Bool
    var under: CGFloat
    @State private var total: CGFloat = 0
    @Environment(MacModel.self) private var model
    @Environment(\.macTint) private var tint

    var body: some View {
        let current = synced ? MacLyrics.index(in: lines, at: model.player.progress.positionSec) : -1
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: large ? 4 : 3) {
                    Color.clear.frame(height: anchor)
                    ForEach(lines) { l in
                        let on = l.id == current
                        // The point size itself animates (a scaled layer blurs the glyphs, a font swap
                        // would crossfade two layouts): every frame is real text at its real size.
                        // iOS LyricLinesView's proportions: regular neighbours, the current line semibold
                        // and ~1.14× larger; row pitch about 2.1× the neighbour size.
                        let lit = on && synced
                        let size: CGFloat = large ? (lit ? 32 : 30) : (lit ? 17 : 15)
                        Text(l.text.isEmpty ? "♪" : l.text)
                            .modifier(LyricLineFont(size: size, weight: lit || !synced ? .semibold : (large ? .medium : .regular)))
                            // Full window: every line nearly one size, wrapping in the column (Apple Music's scale).
                            .lineSpacing(large ? size * 0.15 : 0)
                            .fixedSize(horizontal: false, vertical: true)
                            .foregroundStyle(on ? tint.primary : (synced ? Yoin.ink3 : Yoin.ink))
                            .opacity(model.focus && !on && synced ? 0.35 : 1)
                            .padding(.vertical, large ? 9 : 5)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .contentShape(Rectangle())
                            .onTapGesture { if synced { model.player.seek(to: l.timeSec) } }
                            .id(l.id)
                            .animation(Yoin.effects, value: on)
                    }
                    Color.clear.frame(height: max(200, total))
                }
            }
            .scrollIndicators(.never)
            .mask { LyricsWindowMask(clear: clear, under: under, scale: (large ? 30 : 15) / 14) }
            .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { total = $0 }
            .onChange(of: current, initial: true) { _, i in
                guard i >= 0 else { return }
                withAnimation(Yoin.spatial) {
                    proxy.scrollTo(i, anchor: UnitPoint(x: 0, y: total > 0 ? anchor / total : 0.2))
                }
            }
        }
    }

    /// The band above the transport, where the current line lives.
    private var clear: CGFloat { max(0, total - under) }
    /// iOS: the current line at LYRICS_ANCHOR_FRACTION of the clear band.
    private var anchor: CGFloat { clear * Tokens.np.LYRICS_ANCHOR_FRACTION.cg }
}

/// iOS `lyricWindow`'s mask: a 16 pt top fade, opaque to 24 pt above the transport, then the lyrics run on
/// under the glass buttons at `lyricsUnder` and dissolve 100 pt in. Nothing under it: the expanded
/// window's 16 / 64 fades.
private struct LyricsWindowMask: View {
    var clear: CGFloat
    var under: CGFloat
    /// iOS's distances are for 14 pt lines; they grow with the Mac's neighbour-line size.
    var scale: CGFloat
    /// iOS World preset's lyricsUnder.
    static let underStrength = 0.55

    var body: some View {
        if under <= 0 {
            VStack(spacing: 0) {
                LinearGradient(colors: [.clear, .black], startPoint: .top, endPoint: .bottom).frame(height: 16 * scale)
                Color.black
                LinearGradient(colors: [.black, .clear], startPoint: .top, endPoint: .bottom).frame(height: 64 * scale)
            }
        } else {
            let total = max(clear + under, 1)
            let u = Self.underStrength
            LinearGradient(stops: [
                .init(color: .clear, location: 0),
                .init(color: .black, location: min(0.12, 16 * scale / total)),
                .init(color: .black, location: max(0, clear - 24 * scale) / total),
                .init(color: .black.opacity(min(1, u / 0.7)), location: clear / total),
                .init(color: .black.opacity(u), location: min(1, (clear + 56 * scale) / total)),
                .init(color: .clear, location: min(1, (clear + 100 * scale) / total)),
            ], startPoint: .top, endPoint: .bottom)
        }
    }
}

/// A lyric line's font with an animatable point size.
private struct LyricLineFont: ViewModifier, @MainActor Animatable {
    var size: CGFloat
    var weight: Font.Weight
    var animatableData: CGFloat {
        get { size }
        set { size = newValue }
    }

    func body(content: Content) -> some View {
        content.font(.gsf(size, weight))
    }
}

/// About: the song's facts.
struct MacAboutView: View {
    @Environment(MacModel.self) private var model
    @Environment(\.macTint) private var tint
    @State private var facts: SongFacts?

    var body: some View {
        let t = model.player.currentTrack
        // iOS About: the server's record of the song, label over value, the label in the tint.
        let rows = (facts?.facts ?? t.map { SongFacts($0).facts } ?? []) + lyricsRow
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                ForEach(rows) { f in
                    VStack(alignment: .leading, spacing: 1) {
                        Text(f.label).font(.gsf(12.5, .medium)).foregroundStyle(tint.primary)
                        Text(f.value).font(.gsf(15)).foregroundStyle(Yoin.ink)
                            .fixedSize(horizontal: false, vertical: true)
                            .textSelection(.enabled)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.vertical, 4)
                }
            }
            .padding(.top, 8)
            .padding(.bottom, 48)
        }
        .scrollIndicators(.never)
        .mask(LinearGradient(stops: [.init(color: .black, location: 0), .init(color: .black, location: 0.85),
                                     .init(color: .clear, location: 1)], startPoint: .top, endPoint: .bottom))
        .task(id: t?.id) { facts = await load(t) }
    }

    /// Where the lyrics came from (the lyric credit).
    private var lyricsRow: [SongFacts.Fact] {
        if case .loaded(_, _, let source) = model.lyrics.state, !source.isEmpty {
            return [SongFacts.Fact(label: "Lyrics", value: source)]
        }
        return []
    }

    private func load(_ t: PlayableTrack?) async -> SongFacts? {
        guard let t else { return nil }
        guard let client = model.session.client else { return SongFacts(t) }
        do {
            if let song = try await client.getSong(id: t.id) { return SongFacts(song, fallback: t) }
        } catch {}
        return SongFacts(t, complete: false)
    }
}
