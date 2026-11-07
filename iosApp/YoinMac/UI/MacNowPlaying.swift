import AVKit
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
    @State private var coverBounds: CGRect = .zero
    /// iOS World preset: transportPrimary 0.70, transportSecondary 0.28.
    static let transportPrimaryTint = 0.70
    static let transportSecondaryTint = 0.28

    enum NPTab: String, CaseIterable { case lyrics = "Lyrics", about = "About" }

    /// Focus hides the chrome; moving the pointer brings back a way out for a moment.
    @State private var exitShown = false
    @State private var exitHide: Task<Void, Never>?
    /// docs/design.md 自动沉浸: playing, on Lyrics, synced lyrics, 3 s without the pointer, a scroll or a key →
    /// the lyric tools fade out in place; any of those, or a pause, brings them back.
    @State private var toolsIdle = false
    @State private var toolsHide: Task<Void, Never>?
    @State private var activityMonitor: Any?
    @State private var lastWake = Date.distantPast

    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .topLeading) {
                MacRoom(coverBounds: coverBounds)
                if full { fullLayout(g.size) } else { panelLayout(g.size) }
                if model.focus {
                    // Right of the traffic lights, in their band: back out of focus (Esc and the cover too).
                    GlassCircleButton(icon: "close", size: 32) { model.toggleFocus() }
                        .help("Exit Focus (Esc)")
                        .padding(.leading, full ? 86 : 16).padding(.top, full ? 6 : 16)
                        .opacity(exitShown ? 1 : 0)
                        .allowsHitTesting(exitShown)
                        .animation(Yoin.effects, value: exitShown)
                }
            }
            .coordinateSpace(name: "macNowPlaying")
        }
        .onContinuousHover { phase in
            guard case .active = phase else { return }
            wakeTools()
            if model.focus { pointerMoved() }
        }
        .onAppear {
            wakeTools(force: true)
            activityMonitor = NSEvent.addLocalMonitorForEvents(matching: [.scrollWheel, .keyDown, .leftMouseDown]) { e in
                wakeTools()
                return e
            }
        }
        .onDisappear {
            if let m = activityMonitor { NSEvent.removeMonitor(m) }
            activityMonitor = nil
            toolsHide?.cancel()
        }
        .onChange(of: model.player.isPlaying) { _, _ in wakeTools(force: true) }
        // Lyrics that load (or a song without synced ones) restart the count: shown, then hidden 3 s on.
        .onChange(of: lyricsSynced) { _, _ in wakeTools(force: true) }
        .onChange(of: model.player.currentTrack?.id) { _, _ in wakeTools(force: true) }
        .onChange(of: tab) { _, _ in wakeTools(force: true) }
        .onChange(of: model.focus) { _, on in
            if on { pointerMoved() } else { exitHide?.cancel(); exitShown = false }
            // Focus always has the lyrics' current line (docs/design.md focus rules): from About it turns to them.
            if on, tab != .lyrics { withAnimation(Yoin.effects) { tab = .lyrics } }
        }
        .clipShape(UnevenRoundedRectangle(topLeadingRadius: full ? 0 : 22, bottomLeadingRadius: full ? 0 : 22,
                                          bottomTrailingRadius: 0, topTrailingRadius: 0, style: .continuous))
        .shadow(color: .black.opacity(full ? 0 : 0.18), radius: 20, x: -10)
    }

    private var lyricsSynced: Bool {
        if case .loaded(true, _, _) = model.lyrics.state { return true }
        return false
    }

    /// The lyric tools back, and their 3 s count from now (pointer moves are taken at most a few times a second).
    private func wakeTools(force: Bool = false) {
        let now = Date()
        guard force || toolsIdle || now.timeIntervalSince(lastWake) > 0.3 else { return }
        lastWake = now
        if toolsIdle { withAnimation(Yoin.effects) { toolsIdle = false } }
        toolsHide?.cancel()
        toolsHide = Task { @MainActor in
            try? await Task.sleep(for: .seconds(3))
            guard !Task.isCancelled, model.player.isPlaying, tab == .lyrics,
                  case .loaded(true, _, _) = model.lyrics.state else { return }
            withAnimation(Yoin.effects) { toolsIdle = true }
        }
    }

    /// Shows the exit button; ~2.5 s without movement hides it again, and the cursor with it.
    private func pointerMoved() {
        exitShown = true
        exitHide?.cancel()
        exitHide = Task { @MainActor in
            try? await Task.sleep(for: .seconds(2.5))
            guard !Task.isCancelled, model.focus else { return }
            exitShown = false
            NSCursor.setHiddenUntilMouseMoves(true)
        }
    }

    // MARK: Layouts

    private func panelLayout(_ size: CGFloat2) -> some View {
        let inner = size.width - 48
        // Heights, top to bottom: header, cover, tabs, lyrics (at least two lines, in the clear above the
        // controls), controls, title, pills. The cover gives way first when the window is short; the controls
        // never sit on the lyrics' current line.
        let controlsH = transportHeight > 0 ? transportHeight : 150
        // (Focus only fades the header, tabs and pills: they keep their room.)
        let fixed: CGFloat = 70 + 58 + 64 + Self.lyricsMinHeight + controlsH + 112 + 42
        let room = size.height - fixed
        // Shorter still, the cover steps out altogether rather than push the pills out of the window.
        let side = room < 80 ? 0 : min(inner - (model.focus ? 0 : 68), room)
        return VStack(alignment: .leading, spacing: 0) {
            header.padding(.top, 6).padding(.bottom, 14).chrome(model.focus)
            if side > 0 {
                art(side: side, barHeight: min(220, side * 0.72))
                    .transition(.opacity.combined(with: .scale(scale: 0.9, anchor: .top)))
            }
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
                // The transport ends where the rating column ends (Android two-pane, PR #163; 51, 11-06/11-07):
                // the cover plus its 12 + 56 rating column, the cover alone in focus.
                controls.padding(.top, 12)
                    // (Never narrower than PLAY, next and the mode button need, in a very small window.)
                    .frame(width: max(280, model.focus ? side : side + 68), alignment: .leading)
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
            .onGeometryChange(for: CGRect.self) { $0.frame(in: .named("macNowPlaying")) } action: { coverBounds = $0 }
            .shadow(color: tint.primary.opacity(0.3), radius: 24, y: 18)
            .onTapGesture { model.toggleFocus() }
            // The song's own menu (Android's cover long-press): queue, like, rate, playlist, album / artist, share.
            .contextMenu {
                if let e = model.currentEntry {
                    SongMenu(entry: e) { model.player.seek(to: 0); if !model.player.isPlaying { model.player.resume() } }
                }
            }
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
            Spacer(minLength: 0)
            if tab == .lyrics, model.player.currentTrack != nil {
                MacLyricsTools()
                    .opacity(toolsIdle ? 0 : 1)
                    .allowsHitTesting(!toolsIdle)
            }
        }
    }

    /// Two lyric lines and their band: the least the lyrics get above the controls.
    static let lyricsMinHeight: CGFloat = 84

    /// The panel's lyrics (or About) above the controls: they take what the cover leaves, at least two lines,
    /// and never run under the transport (51, 2026-11-06: the controls sat on the lines at a short window).
    private var lyricsOverControls: some View {
        VStack(spacing: 0) {
            tabContent(large: false)
                .frame(minHeight: Self.lyricsMinHeight, maxHeight: .infinity)
                .clipped()
            controls.padding(.top, 12)
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { transportHeight = $0 }
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
            // Spotify playing on another device: where, quietly (never on the cover).
            if model.player.currentTrack?.provider == SpotifyMapping.provider, model.player.isBuffering {
                // Not playing until Spotify says so (Android np_msg_connecting_spotify).
                Text("Connecting to Spotify…").font(.gsf(12.5)).foregroundStyle(Yoin.ink3).lineLimit(1)
                    .transition(.opacity)
            } else if model.player.currentTrack?.provider == SpotifyMapping.provider, let device = model.spotifyDevice {
                Text("Playing on \(device)").font(.gsf(12.5)).foregroundStyle(Yoin.ink3).lineLimit(1)
            }
            // One line, scrolling when it's longer (51, 2026-11-07; Android's title marquee).
            MacMarqueeText(text: model.player.currentTrack?.title ?? "Not playing",
                           font: .gsf(large ? 40 : 34, .semibold), color: Yoin.ink)
        }
    }

    private var controls: some View {
        let p = model.player
        return VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 10) {
                Button { p.togglePlayPause() } label: {
                    Group {
                        if p.isBuffering && p.currentTrack?.provider == SpotifyMapping.provider {
                            ProgressView().controlSize(.regular).tint(tint.onPrimary)
                        } else {
                            Text(p.isPlaying ? "PAUSE" : "PLAY").font(.gsf(22, .bold)).tracking(0.7)
                        }
                    }
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
                if p.externalTrack?.provider == SpotifyMapping.provider {
                    // A Spotify song Yoin mirrors: Spotify's own shuffle / repeat.
                    let m = model.spotifyModes
                    RoundControl(icon: m.shuffle ? "shuffle" : "repeat", badge: m.repeatState == "track" ? "1" : nil,
                                 dim: m.repeatState == "off" && !m.shuffle) { model.cycleSpotifyModes() }
                        .help("Spotify's Play Mode")
                } else {
                    RoundControl(icon: p.shuffle ? "shuffle" : "repeat", badge: p.repeatMode == .one ? "1" : nil,
                                 dim: p.repeatMode == .off) { p.cyclePlayMode() }
                        .help("Play Mode")
                }
            }
            HStack(spacing: 10) {
                RoundControl(icon: "skip_previous_filled") { p.previous() }
                ProgressRow()
            }
        }
    }

    /// Labeled when they fit; icons only (never a wrapped label) when the column is narrow.
    private var pills: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 8) {
                QueuePill()
                DevicesPill()
                WritePill()
            }
            HStack(spacing: 8) {
                QueuePill()
                DevicesPill()
                WritePill()
            }
            .environment(\.npPillsCompact, true)
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
    var coverBounds: CGRect
    @Environment(MacModel.self) private var model
    @Environment(\.macTint) private var tint
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let prev = MacTint.of(model.previousTrack?.coverURL, dark: scheme == .dark)
        let next = MacTint.of(model.player.upNextTrack?.coverURL, dark: scheme == .dark)
        GeometryReader { g in
            ZStack {
                Yoin.surface.mix(with: tint.container, by: 0.34, in: .perceptual)
                if model.focus {
                    MacFocusField(coverBounds: coverBounds)
                        .transition(.opacity)
                } else {
                    EllipticalGradient(colors: [tint.primary.opacity(0.3), .clear], center: UnitPoint(x: 0.5, y: 0.46),
                                       endRadiusFraction: 0.6)
                    EllipticalGradient(colors: [prev.container, .clear], center: .top, endRadiusFraction: 0.5)
                        .frame(height: g.size.height * 0.6).frame(maxHeight: .infinity, alignment: .top)
                    EllipticalGradient(colors: [next.container, .clear], center: .bottom, endRadiusFraction: 0.5)
                        .frame(height: g.size.height * 0.6).frame(maxHeight: .infinity, alignment: .bottom)
                }
            }
        }
        .animation(Yoin.effects, value: model.focus)
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
        let stored = t.flatMap { model.library.rating(provider: $0.provider, songId: $0.id) }
        let value = dragValue ?? stored ?? 0
        ZStack(alignment: .bottom) {
            Capsule().fill(dragValue != nil ? Color.clear : tint.primary.opacity(0.12))
            Capsule().fill(tint.primary.mix(with: .black, by: 0.06))
                .frame(height: max(0, height * value / 10))
            Group {
                if value > 0 {
                    Text(String(format: "%.1f", value))
                        .font(.gsf(22, .medium).monospacedDigit())
                        .lineLimit(1).minimumScaleFactor(0.7)
                        .foregroundStyle(value >= 9.2 ? tint.onPrimary : Yoin.ink)
                } else {
                    // Unrated: the bar says what it's for instead of standing empty.
                    YoinIcon("star", size: 18).foregroundStyle(Yoin.ink3)
                }
            }
            .frame(maxHeight: .infinity, alignment: .top).padding(.top, 12)
        }
        .frame(width: 56, height: height)
        .glassEffect(dragValue != nil ? .regular : .identity, in: .capsule)
        .contentShape(Capsule())
        .gesture(
            DragGesture(minimumDistance: 0)
                .onChanged { g in
                    guard t != nil else { return }
                    // Yoin's 0–10 in tenths, as Android's rating bar (the server keeps its 0–5 stars).
                    let raw = (1 - g.location.y / height) * 10
                    let v = (min(max(raw, 0), 10) * 10).rounded() / 10
                    withAnimation(Yoin.spatialFast) { dragValue = v }
                }
                .onEnded { _ in
                    if let t, let v = dragValue { model.library.setRating(provider: t.provider, songId: t.id, v > 0 ? v : nil) }
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
        let pending = model.session.isAppleMusic && (model.player.currentTrack.map { model.library.pendingLibrary.contains($0.id) } ?? false)
        Button { model.toggleLikeCurrent() } label: {
            YoinIcon(on ? "favorite_filled" : "favorite", size: 22)
                .foregroundStyle(tint.primary)
                .frame(width: 46, height: 46)
                .background(Circle().fill(tint.container.opacity(0.7)))
                .contentShape(Circle())
        }
        .buttonStyle(PressDown())
        // Apple Music's heart is the library: a song joins it here, it isn't taken out (Android TrackLibraryButton);
        // an add Apple hasn't confirmed yet reads pending, and a click checks again.
        .opacity(pending ? 0.55 : 1)
        .animation(Yoin.effects, value: pending)
        .disabled(model.session.isAppleMusic && on && !pending)
        .help(pending ? "Check library addition"
              : model.session.isAppleMusic ? (on ? "In Apple Music library" : "Add to Apple Music library")
              : (on ? "Unlike" : "Like"))
    }
}

struct NPPill: View {
    var icon: String
    var title: String
    var enabled: Bool
    var action: () -> Void
    @Environment(\.macTint) private var tint
    /// Icons only (their titles as tips): a column too narrow for the labels.
    @Environment(\.npPillsCompact) private var compact

    var body: some View {
        Button(action: action) {
            HStack(spacing: 7) {
                YoinIcon(icon, size: 17)
                if !compact { Text(title).font(.gsf(14)).lineLimit(1).fixedSize() }
            }
                .foregroundStyle(tint.onPrimary)
                .padding(.horizontal, compact ? 0 : 16).frame(width: compact ? 44 : nil, height: 44)
                .background(Capsule().fill(tint.primary.opacity(0.78)))
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .glassEffect(.regular.tint(tint.primary.opacity(0.5)).interactive(), in: .capsule)
        .opacity(enabled ? 1 : 0.5)
        .disabled(!enabled)
        .help(title)
    }
}

extension EnvironmentValues {
    @Entry var npPillsCompact = false
}

/// Queue: the songs after the current one. Click one to play it, drag to reorder, ✕ or right-click to
/// remove, Clear to empty what's left.
struct QueuePill: View {
    @Environment(MacModel.self) private var model
    @State private var open = false

    var body: some View {
        NPPill(icon: "queue", title: "Queue", enabled: true) { open.toggle() }
            .popover(isPresented: $open, arrowEdge: .top) { QueuePopover() }
            #if DEBUG
            .onReceive(NotificationCenter.default.publisher(for: Notification.Name("YoinQAQueue"))) { _ in open = true }
            #endif
            // Songs, albums or playlists dropped on the pill go to the end of what's queued.
            .dropDestination(for: YoinDragItem.self) { items, _ in
                MacDrag.queue(items, before: nil, model: model)
                return true
            }
    }
}

private struct QueuePopover: View {
    @Environment(MacModel.self) private var model

    var body: some View {
        if model.player.externalTrack?.provider == SpotifyMapping.provider {
            spotifyUpNext
        } else {
            yoinUpNext
        }
    }

    /// Mirroring a Spotify song Yoin didn't start: Spotify's own Up Next (read there; changed in Spotify).
    private var spotifyUpNext: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Up Next in Spotify").font(.gsf(15, .semibold)).padding(.horizontal, 10)
            if model.spotifyQueue.isEmpty {
                Text("Nothing queued in Spotify").font(.gsf(13)).foregroundStyle(Yoin.inkVariant).padding(10)
            } else {
                List {
                    ForEach(Array(model.spotifyQueue.enumerated()), id: \.offset) { _, t in
                        HStack(spacing: 10) {
                            VStack(alignment: .leading, spacing: 1) {
                                Text(t.title).font(.gsf(13.5)).lineLimit(1)
                                Text(t.artist).font(.gsf(12)).foregroundStyle(Yoin.inkVariant).lineLimit(1)
                            }
                            Spacer(minLength: 8)
                            Text(formatTime(Int(t.durationSec))).font(.gsf(12).monospacedDigit()).foregroundStyle(Yoin.ink3)
                        }
                        .padding(.horizontal, 10).padding(.vertical, 6)
                        .listRowInsets(EdgeInsets(top: 0, leading: 0, bottom: 0, trailing: 0))
                        .listRowSeparator(.hidden)
                        .listRowBackground(Color.clear)
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                .frame(height: min(400, CGFloat(model.spotifyQueue.count) * 46 + 8))
            }
        }
        .padding(12)
        .frame(width: 340)
        .onAppear { model.spotifyOutput.refreshQueue() }
    }

    /// Android QueueSheet: what the listener queued (Next in queue, which Clear empties) above the rest of the
    /// list (Next up); each section reorders within itself.
    private var yoinUpNext: some View {
        let p = model.player
        let start = (p.currentIndex ?? 0) + 1
        // Every song after the current one (the List draws only the rows on screen).
        let upcoming = Array(p.upcoming)
        let queued = Array(upcoming.prefix { $0.userQueued })
        let rest = Array(upcoming.dropFirst(queued.count))
        return VStack(alignment: .leading, spacing: 6) {
            if upcoming.isEmpty {
                Text("Up Next").font(.gsf(15, .semibold)).padding(.horizontal, 10)
                Text("Nothing after this song").font(.gsf(13)).foregroundStyle(Yoin.inkVariant).padding(10)
            } else {
                List {
                    if !queued.isEmpty {
                        Section {
                            rows(queued, offset: start)
                        } header: {
                            HStack {
                                Text("Next in queue").font(.gsf(13, .semibold)).foregroundStyle(Yoin.ink)
                                Spacer()
                                Button("Clear") { model.clearQueued() }
                                    .font(.gsf(13)).buttonStyle(.plain).foregroundStyle(Yoin.inkVariant)
                            }
                        }
                    }
                    if !rest.isEmpty {
                        Section {
                            rows(rest, offset: start + queued.count)
                        } header: {
                            Text("Next up").font(.gsf(13, .semibold)).foregroundStyle(Yoin.ink)
                        }
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                .frame(height: min(420, CGFloat(upcoming.count) * 46 + CGFloat((queued.isEmpty ? 0 : 1) + (rest.isEmpty ? 0 : 1)) * 30 + 8))
                .animation(Yoin.spatial, value: upcoming.map(\.id))
            }
        }
        .padding(12)
        .frame(width: 340)
        .dropDestination(for: YoinDragItem.self) { items, _ in
            MacDrag.queue(items, before: nil, model: model)
            return true
        }
    }

    /// One section's rows; [offset] is its first row's place in the queue.
    private func rows(_ items: [QueueItem], offset: Int) -> some View {
        let p = model.player
        return ForEach(Array(items.enumerated()), id: \.element.id) { i, item in
            QueueRow(item: item) { p.jump(to: offset + i) } remove: { p.remove(id: item.id) }
                // Dropped on a row: in before it.
                .dropDestination(for: YoinDragItem.self) { dropped, _ in
                    MacDrag.queue(dropped, before: item.id, model: model)
                    return true
                }
                .listRowInsets(EdgeInsets(top: 0, leading: 0, bottom: 0, trailing: 0))
                .listRowSeparator(.hidden)
                .listRowBackground(Color.clear)
        }
        .onMove { from, to in
            p.move(fromOffsets: IndexSet(from.map { $0 + offset }), toOffset: to + offset)
        }
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
    @AppStorage("lyricsShowTranslation") private var showTranslation = true
    var lines: [MacLyrics.Line]
    var synced: Bool
    var large: Bool
    var under: CGFloat
    @State private var total: CGFloat = 0
    /// Following the playhead; scrolling by hand stops it until Back to Current (Android Recenter) or the next song.
    @State private var following = true
    @Environment(MacModel.self) private var model
    @Environment(\.macTint) private var tint

    var body: some View {
        let current = synced ? MacLyrics.index(in: lines, at: model.player.progress.positionSec) : -1
        ScrollViewReader { proxy in
          ZStack(alignment: .bottom) {
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
                        VStack(alignment: .leading, spacing: large ? 4 : 2) {
                            Text(l.text.isEmpty ? "♪" : l.text)
                                .modifier(LyricLineFont(size: size, weight: lit || !synced ? .semibold : (large ? .medium : .regular)))
                                // Full window: every line nearly one size, wrapping in the column (Apple Music's scale).
                                .lineSpacing(large ? size * 0.15 : 0)
                                .fixedSize(horizontal: false, vertical: true)
                            // The source's translation under its line.
                            if showTranslation, let tr = l.translation, !tr.isEmpty {
                                Text(tr).font(.gsf(large ? 20 : 13, .regular))
                                    .fixedSize(horizontal: false, vertical: true)
                                    .opacity(0.75)
                            }
                        }
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
            .onScrollPhaseChange { _, phase in
                if phase == .interacting, synced { withAnimation(Yoin.effects) { following = false } }
            }
            .onChange(of: current, initial: true) { _, i in
                guard i >= 0, following else { return }
                withAnimation(Yoin.spatial) {
                    proxy.scrollTo(i, anchor: UnitPoint(x: 0, y: total > 0 ? anchor / total : 0.2))
                }
            }
            .onChange(of: lines.first?.id) { _, _ in following = true }
            // A floating control: back to the line playing, and following again.
            if !following, current >= 0 {
                Button {
                    following = true
                    withAnimation(Yoin.spatial) {
                        proxy.scrollTo(current, anchor: UnitPoint(x: 0, y: total > 0 ? anchor / total : 0.2))
                    }
                } label: {
                    HStack(spacing: 6) {
                        YoinIcon("chevron_down", size: 14)
                        Text("Back to Current").font(.gsf(12.5, .semibold)).lineLimit(1)
                    }
                    .foregroundStyle(Yoin.ink)
                    .padding(.horizontal, 14).frame(height: 32)
                    .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .glassEffect(.regular.interactive(), in: .capsule)
                .padding(.bottom, under + 8)
                .transition(.opacity.combined(with: .scale(scale: 0.9)))
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
/// About as a receipt (Android AboutReceipt, owner 2026-11-05): one fact per line, the label small on the
/// left and the value on the right, a dashed rule between lines; every fact the server gives, then where
/// the lyrics came from.
struct MacAboutView: View {
    @Environment(MacModel.self) private var model

    var body: some View {
        let t = model.player.currentTrack
        MacSongAboutView(track: t)
            // Keep drafts, errors and server facts with this account, song and language.
            .id([model.session.activeId ?? "", t?.provider ?? "", t?.id ?? "",
                 t?.title ?? "", t?.artist ?? "", t?.album ?? "", GeminiSettings.shared.targetLanguage])
    }
}

private struct MacSongAboutView: View {
    var track: PlayableTrack?
    @Environment(MacModel.self) private var model
    @Environment(\.macTint) private var tint
    @Environment(\.openSettings) private var openSettings
    @State private var song: SongInfo?
    @State private var showOthers = false
    @State private var question = ""
    @State private var lastQuestion = ""
    @State private var entryMessage: String?
    @State private var retryEntry: SongAboutStore.Entry.Ref?
    @State private var undoEntry: SongAboutStore.Entry?
    @FocusState private var questionFocused: Bool

    private var loader: SongAboutLoader { .shared }
    private var settings: GeminiSettings { .shared }
    private var askable: Bool {
        guard let track else { return false }
        return !track.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && !track.artist.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
    private var asking: Bool { if case .loading = loader.askState { true } else { false } }
    private var canonicalPending: Bool {
        guard let track, askable, settings.hasKey else { return false }
        let key = SongAboutStore.key(title: track.title, artist: track.artist, album: track.album)
        return loader.phase(key) == .idle && !SongAboutStore.shared.hasCanonical(key)
    }

    var body: some View {
        let t = track
        let credits = model.lyrics.credits
        let songKey = t.map { SongAboutStore.key(title: $0.title, artist: $0.artist, album: $0.album) }
        let gemini = songKey.map { SongAboutStore.shared.entries($0) } ?? []
        let rows = MacReceipt.rows(song: song, track: t, credits: credits, gemini: gemini)
            + lyricsRow
        let paragraphs = MacReceipt.paragraphs(gemini)
        ScrollView {
            if askable, let songKey, !settings.hasKey || loader.phase(songKey) != .idle {
                recovery(songKey)
            }
            VStack(alignment: .leading, spacing: 0) {
                ForEach(Array(rows.enumerated()), id: \.offset) { i, r in
                    if i > 0 { DashedRule() }
                    ReceiptLine(label: r.label, value: r.value)
                        .modifier(MacAboutEntryMenu(entry: r.entry, regenerate: regenerate, delete: delete))
                    if let entry = r.entry, loader.regenerating.contains(entry.ref) {
                        regeneratingLine
                    }
                }
                // The lyrics' other credits behind "More"; the notices small at the foot.
                if !credits.others.isEmpty {
                    if !rows.isEmpty { DashedRule() }
                    if showOthers {
                        ForEach(Array(credits.others.enumerated()), id: \.offset) { _, c in
                            if c.role.isEmpty {
                                Text(c.names).font(.gsf(13)).foregroundStyle(Yoin.ink)
                                    .fixedSize(horizontal: false, vertical: true)
                                    .padding(.vertical, 8)
                            } else {
                                ReceiptLine(label: c.role, value: c.names)
                            }
                            DashedRule()
                        }
                    }
                    Button {
                        withAnimation(Yoin.spatial) { showOthers.toggle() }
                    } label: {
                        Text(showOthers ? "Less" : "\(credits.others.count) more: "
                             + credits.others.prefix(3).map { $0.role.isEmpty ? "Note" : $0.role }.joined(separator: ", ") + "…")
                            .font(.gsf(12.5, .medium)).foregroundStyle(tint.primary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.vertical, 10)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
                if !credits.notices.isEmpty {
                    DashedRule()
                    VStack(spacing: 2) {
                        ForEach(credits.notices, id: \.self) { n in
                            Text(n).font(.gsf(11)).foregroundStyle(Yoin.inkVariant)
                                .multilineTextAlignment(.center)
                                .frame(maxWidth: .infinity)
                        }
                    }
                    .padding(.vertical, 10)
                }
            }
            .padding(.horizontal, 16).padding(.vertical, 6)
            .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(tint.container.opacity(0.35)))
            .padding(.top, 8)
            // Gemini's review and answers as paragraphs under the receipt (Android AboutParagraphSection).
            ForEach(Array(paragraphs.enumerated()), id: \.offset) { _, para in
                VStack(alignment: .leading, spacing: 6) {
                    Text(para.label).font(.gsf(13.5, .semibold)).foregroundStyle(tint.primary)
                    Text(.init(para.text)).font(.gsf(14)).foregroundStyle(Yoin.ink)
                        .fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, 12)
                .contentShape(Rectangle())
                .modifier(MacAboutEntryMenu(entry: para.entry, regenerate: regenerate, delete: delete))
                if let entry = para.entry, loader.regenerating.contains(entry.ref) {
                    regeneratingLine
                }
            }
            if askable {
                entryFeedback
                composer
            }
            Color.clear.frame(height: 48)
        }
        .scrollIndicators(.never)
        .mask(LinearGradient(stops: [.init(color: .black, location: 0), .init(color: .black, location: 0.85),
                                     .init(color: .clear, location: 1)], startPoint: .top, endPoint: .bottom))
        .animation(Yoin.spatial, value: gemini)
        .animation(Yoin.effects, value: songKey.map { loader.phase($0) })
        .animation(Yoin.effects, value: loader.askState)
        .animation(Yoin.effects, value: loader.regenerating)
        .animation(Yoin.effects, value: entryMessage)
        .onAppear { loader.resetAsk() }
        .onDisappear { loader.resetAsk() }
        // A language change may have to wait for the previous language's call to finish.
        .task(id: canonicalPending) {
            guard let t, canonicalPending else { return }
            await loader.ensure(title: t.title, artist: t.artist, album: t.album)
        }
        .task {
            let result = await load(t)
            guard !Task.isCancelled else { return }
            song = result
        }
    }

    @ViewBuilder private func recovery(_ key: SongAboutStore.Entry.SongKey) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            if !settings.hasKey {
                Text("No Gemini API key").font(.gsf(12.5)).foregroundStyle(Yoin.inkVariant)
                Button { openSettings() } label: {
                    Label { Text("Settings") } icon: { YoinIcon("settings", size: 16) }
                }
                .buttonStyle(.link)
            } else {
                switch loader.phase(key) {
                case .loading:
                    HStack(spacing: 8) {
                        ProgressView().controlSize(.small)
                        Text("Asking Gemini…").foregroundStyle(Yoin.inkVariant)
                    }
                case .failed(let message):
                    Text(message).foregroundStyle(Yoin.inkVariant)
                        .fixedSize(horizontal: false, vertical: true)
                    Button("Retry") { retryCanonical() }.buttonStyle(.link)
                case .idle, .noKey: EmptyView()
                }
            }
        }
        .font(.gsf(12.5))
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 10)
    }

    private var composer: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Ask Gemini").font(.gsf(13.5, .semibold)).foregroundStyle(tint.primary)
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                TextField("Ask about this song", text: $question)
                    .textFieldStyle(.plain)
                    .font(.gsf(14)).foregroundStyle(Yoin.ink)
                    .focused($questionFocused)
                    .onSubmit { submit(question) }
                    .disabled(asking || !settings.hasKey)
                Button { submit(question) } label: {
                    Label { Text("Ask") } icon: { YoinIcon("sparkle", size: 16) }
                }
                .buttonStyle(.plain)
                .font(.gsf(13, .medium)).foregroundStyle(tint.primary)
                .disabled(asking || !settings.hasKey || question.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            .padding(12)
            .background(Yoin.surfaceContainer, in: RoundedRectangle(cornerRadius: 12))
            switch loader.askState {
            case .loading(let title, _):
                HStack(spacing: 8) {
                    ProgressView().controlSize(.small)
                    Text(title).fixedSize(horizontal: false, vertical: true)
                }
                .font(.gsf(12.5)).foregroundStyle(Yoin.inkVariant)
            case .error(let message):
                Text(message).font(.gsf(12.5)).foregroundStyle(Yoin.inkVariant)
                    .fixedSize(horizontal: false, vertical: true)
                if settings.hasKey, !lastQuestion.isEmpty {
                    Button("Retry") { submit(lastQuestion) }.buttonStyle(.link).font(.gsf(12.5))
                }
            case .idle, .focused: EmptyView()
            }
        }
        .padding(.vertical, 12)
        .onChange(of: questionFocused) { _, focused in
            if focused { loader.askFocused() } else { loader.askCollapse() }
        }
    }

    private var regeneratingLine: some View {
        HStack(spacing: 8) {
            ProgressView().controlSize(.small)
            Text("Regenerating…").font(.gsf(12.5)).foregroundStyle(Yoin.inkVariant)
        }
        .padding(.bottom, 8)
    }

    @ViewBuilder private var entryFeedback: some View {
        if let entryMessage {
            VStack(alignment: .leading, spacing: 6) {
                Text(entryMessage).font(.gsf(12.5)).foregroundStyle(Yoin.inkVariant)
                    .fixedSize(horizontal: false, vertical: true)
                if let retryEntry {
                    Button("Retry") { regenerate(retryEntry) }.buttonStyle(.link)
                }
                if let undoEntry {
                    Button("Undo") {
                        SongAboutStore.shared.edit(undoEntry.ref, title: undoEntry.titleText, answer: undoEntry.answerText)
                        clearEntryFeedback()
                    }
                    .buttonStyle(.link)
                }
                Button("Dismiss") { clearEntryFeedback() }.buttonStyle(.link)
            }
            .font(.gsf(12.5))
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.vertical, 10)
        }
    }

    private func submit(_ text: String) {
        let asked = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let track, askable, settings.hasKey, !asking, !asked.isEmpty else { return }
        lastQuestion = asked
        question = ""
        questionFocused = false
        loader.ask(title: track.title, artist: track.artist, album: track.album, question: asked)
    }

    private func retryCanonical() {
        guard let track else { return }
        Task { await loader.ensure(title: track.title, artist: track.artist, album: track.album, retry: true) }
    }

    private func clearEntryFeedback() {
        entryMessage = nil
        retryEntry = nil
        undoEntry = nil
    }

    private func delete(_ entry: SongAboutStore.Entry) {
        clearEntryFeedback()
        SongAboutStore.shared.edit(entry.ref, title: entry.titleText, answer: "")
        undoEntry = entry
        entryMessage = "Deleted"
    }

    private func regenerate(_ ref: SongAboutStore.Entry.Ref) {
        clearEntryFeedback()
        Task {
            guard let result = await loader.regenerate(ref) else { return }
            switch result {
            case .success(let title, let answer):
                if var before = SongAboutStore.shared.entry(ref) {
                    before.titleText = title
                    before.answerText = answer
                    undoEntry = before
                }
                entryMessage = "Regenerated"
            case .nothingNew: entryMessage = "Gemini had nothing new"
            case .apiKeyMissing: entryMessage = "No Gemini API key"
            case .error(let message):
                entryMessage = message
                retryEntry = ref
            }
        }
    }

    private var lyricsRow: [MacReceipt.Row] {
        if case .loaded(_, _, let source) = model.lyrics.state, !source.isEmpty {
            return [MacReceipt.Row(label: "Lyrics", value: source)]
        }
        return []
    }

    private func load(_ t: PlayableTrack?) async -> SongInfo? {
        guard let t, t.provider == MediaRef.companion.PROVIDER_SUBSONIC, let client = model.session.client else { return nil }
        return try? await client.getSong(id: t.id)
    }
}

private struct MacAboutEntryMenu: ViewModifier {
    var entry: SongAboutStore.Entry?
    var regenerate: (SongAboutStore.Entry.Ref) -> Void
    var delete: (SongAboutStore.Entry) -> Void

    func body(content: Content) -> some View {
        if let entry {
            content.contentShape(Rectangle()).contextMenu {
                if entry.kind != SongAboutStore.Kind.own {
                    Button { regenerate(entry.ref) } label: {
                        Label { Text("Regenerate with Gemini") } icon: { YoinIcon("refresh", size: 16) }
                    }
                    .disabled(SongAboutLoader.shared.regenerating.contains(entry.ref))
                }
                Button(role: .destructive) { delete(entry) } label: {
                    Label { Text("Delete") } icon: { YoinIcon("delete", size: 16) }
                }
                .disabled(SongAboutLoader.shared.regenerating.contains(entry.ref))
            }
        } else {
            content
        }
    }
}

/// The receipt's lines, in Android's order (who wrote it, then everything the server says).
enum MacReceipt {
    struct Row: Equatable {
        var label: String
        var value: String
        var entry: SongAboutStore.Entry? = nil
    }

    struct Paragraph: Equatable {
        var label: String
        var text: String
        var entry: SongAboutStore.Entry? = nil
    }

    static func rows(song s: SongInfo?, track t: PlayableTrack?, credits: MacLyrics.Credits = .init(),
                     gemini: [SongAboutStore.Entry] = []) -> [Row] {
        var rows: [Row] = []
        func add(_ label: String, _ value: String?, entry: SongAboutStore.Entry? = nil) {
            guard let v = value?.split(whereSeparator: \.isWhitespace).joined(separator: " "), !v.isEmpty else { return }
            rows.append(Row(label: label, value: v, entry: entry))
        }
        let canon = Dictionary(gemini.filter { $0.kind == SongAboutStore.Kind.canonical }.map { ($0.entryKey, $0) },
                               uniquingKeysWith: { a, _ in a })
        func addCanonical(_ label: String, _ key: String) {
            guard let entry = canon[key] else { return }
            add(label, entry.answerText, entry: entry)
        }
        func named(_ role: String) -> Bool { credits.headline.contains { $0.role == role } }
        // Who wrote it (Android's receipt): the lyrics' credits, then the server's composer, then Gemini.
        for c in credits.headline { add(c.role, c.names) }
        if !named("Lyricist") { addCanonical("Lyricist", SongAboutStore.Canon.lyricist) }
        if !named("Composer") {
            if let composer = s?.composer, !composer.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                add("Composer", composer)
            } else {
                addCanonical("Composer", SongAboutStore.Canon.composer)
            }
        }
        if !named("Producer") { addCanonical("Producer", SongAboutStore.Canon.producer) }
        addCanonical("Written", SongAboutStore.Canon.creationTime)
        addCanonical("Made in", SongAboutStore.Canon.creationLocation)
        guard let s else {
            if let t, t.durationSec > 0 { add("Duration", SongFacts.duration(Int(t.durationSec.rounded()))) }
            if let k = t?.sourceKbps { add("Quality", "\(k) kbps") }
            return rows
        }
        add("Genre", s.genre)
        if let y = s.year?.intValue, y > 0 { add("Year", String(y)) }
        if let n = s.track?.intValue, n > 0 {
            let disc = s.disc?.intValue ?? 1
            add("Track", disc > 1 ? "Disc \(disc), track \(n)" : String(n))
        }
        let sec = s.durationSec?.intValue ?? Int((t?.durationSec ?? 0).rounded())
        if sec > 0 { add("Duration", SongFacts.duration(sec)) }
        // Two spaces between parts, as on the spine.
        let kind = s.suffix.flatMap { $0.isEmpty ? nil : $0.uppercased() }
            ?? s.contentType.flatMap { $0.split(separator: "/").last.map { $0.uppercased() } }
        let quality = [kind, s.bitRate.flatMap { $0.intValue > 0 ? "\($0.intValue) kbps" : nil }].compactMap { $0 }
        if !quality.isEmpty { rows.append(Row(label: "Quality", value: quality.joined(separator: "  "))) }
        let bitsAndRate = [s.bitDepth.map { "\($0.intValue)-bit" },
                           s.samplingRate.map { String(format: "%g kHz", Double($0.intValue) / 1000) }].compactMap { $0 }
        if !bitsAndRate.isEmpty { add("Resolution", bitsAndRate.joined(separator: "  ")) }
        if let ch = s.channelCount?.intValue {
            add("Channels", ch == 1 ? "Mono" : ch == 2 ? "Stereo" : "\(ch) channels")
        }
        if let bytes = s.size?.int64Value, bytes > 0 {
            add("Size", String(format: "%.1f MB", Double(bytes) / 1_048_576))
        }
        if let bpm = s.bpm?.intValue { add("BPM", String(bpm)) }
        if let n = s.playCount?.intValue { add("Plays", String(n)) }
        add("Last played", s.played.flatMap(SongFacts.date))
        if let r = s.userRating?.intValue, r > 0 { add("Server rating", String(format: "%.1f", Double(r * 2))) }
        add("Liked", s.starredAt.flatMap(SongFacts.date))
        add("Added", s.created.map { String($0.prefix(10)) })
        add("Comment", s.comment)
        add("File", s.path)
        return rows
    }
}

private struct ReceiptLine: View {
    var label: String
    var value: String

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text(label).font(.gsf(12.5, .medium)).foregroundStyle(Yoin.inkVariant)
                .frame(width: 96, alignment: .leading)
            Text(value).font(.gsf(14)).foregroundStyle(Yoin.ink)
                .multilineTextAlignment(.trailing)
                .frame(maxWidth: .infinity, alignment: .trailing)
                .fixedSize(horizontal: false, vertical: true)
                .textSelection(.enabled)
        }
        .padding(.vertical, 8)
    }
}

extension MacReceipt {
    /// Gemini's review first, then its answers and the listener's own entries.
    static func paragraphs(_ entries: [SongAboutStore.Entry]) -> [Paragraph] {
        var out: [Paragraph] = []
        if let r = entries.first(where: { $0.kind == SongAboutStore.Kind.canonical
            && $0.entryKey == SongAboutStore.Canon.review && !$0.answerText.isEmpty }) {
            out.append(Paragraph(label: "Review", text: r.answerText, entry: r))
        }
        for e in entries where (e.kind == SongAboutStore.Kind.ask || e.kind == SongAboutStore.Kind.own)
            && !e.answerText.isEmpty {
            out.append(Paragraph(label: e.heading ?? "", text: e.answerText, entry: e))
        }
        return out
    }
}

/// The receipt's dashed rule between lines.
private struct DashedRule: View {
    var body: some View {
        Line()
            .stroke(Yoin.ink3.opacity(0.45), style: StrokeStyle(lineWidth: 1, dash: [4, 3]))
            .frame(height: 1)
    }

    private struct Line: Shape {
        func path(in rect: CGRect) -> Path {
            Path { p in
                p.move(to: CGPoint(x: 0, y: rect.midY))
                p.addLine(to: CGPoint(x: rect.maxX, y: rect.midY))
            }
        }
    }
}

/// The lyrics' two tools (Android LyricsActionBar): Translate (the source's own translation, on / off) and
/// Search (pick another source for this song; remembered).
private struct MacLyricsTools: View {
    @Environment(MacModel.self) private var model
    @Environment(\.macTint) private var tint
    @AppStorage("lyricsShowTranslation") private var showTranslation = true
    @State private var searching = false

    var body: some View {
        let lyrics = model.lyrics
        HStack(spacing: 6) {
            Button {
                if lyrics.hasTranslation { withAnimation(Yoin.effects) { showTranslation.toggle() } }
                else { showTranslation = true; Task { await lyrics.translate() } }
            } label: {
                Group {
                    if lyrics.translating { ProgressView().controlSize(.small) }
                    else { YoinIcon("translate", size: 18) }
                }
                .foregroundStyle(lyrics.hasTranslation && showTranslation ? tint.primary : Yoin.inkVariant)
                .frame(width: 30, height: 30).contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .help(lyrics.hasTranslation ? (showTranslation ? "Hide Translation" : "Show Translation") : "Translate")
            Button { searching = true } label: {
                YoinIcon("search", size: 18).foregroundStyle(Yoin.inkVariant)
                    .frame(width: 30, height: 30).contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .help("Search Lyrics")
            .popover(isPresented: $searching, arrowEdge: .bottom) { MacLyricsSearch(open: $searching) }
        }
        .overlay(alignment: .topTrailing) {
            // Translate's word (iOS toast): already that language, no key, Gemini's error.
            if let n = lyrics.notice {
                Text(n).font(.gsf(12)).foregroundStyle(Yoin.inkVariant)
                    .multilineTextAlignment(.trailing)
                    .frame(width: 240, alignment: .trailing)
                    .offset(y: 34)
                    .transition(.opacity)
            }
        }
        .animation(Yoin.effects, value: lyrics.notice)
        // Another source has a translation: its lyrics and timing replace these only if the listener says so.
        .confirmationDialog(lyrics.offer.map { "\($0.label) has a translation" } ?? "",
                            isPresented: Binding(get: { lyrics.offer != nil },
                                                 set: { if !$0, lyrics.offer != nil { Task { await lyrics.declineOffer() } } })) {
            Button("Switch to \(lyrics.offer?.label ?? "It")") {
                withAnimation(Yoin.effects) { lyrics.acceptOffer() }
                showTranslation = true
            }
            // (Keep: the dialog closing declines, once.)
            Button("Keep These Lyrics", role: .cancel) {}
        } message: {
            Text("Its lyrics and timing will replace the ones shown for this song.")
        }
    }
}

/// Search every lyrics source and pick one for the song playing.
private struct MacLyricsSearch: View {
    @Binding var open: Bool
    @Environment(MacModel.self) private var model
    @Environment(\.macTint) private var tint
    @State private var query = ""
    @State private var sections: [ProviderSection] = []
    @State private var busy = false
    @State private var applying: String?
    @State private var applyFailed = false
    /// The song the search is for: a pick lands on it only.
    @State private var forSong: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            TextField("Song title or artist", text: $query)
                .textFieldStyle(.plain).font(.gsf(14))
                .padding(.horizontal, 12).frame(height: 34)
                .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Yoin.surfaceContainerHigh))
                .onSubmit(run)
            if busy { ProgressView().controlSize(.small).frame(maxWidth: .infinity) }
            // A pick that didn't load (Android np_msg_couldnt_apply): the search stays open.
            if applyFailed {
                Text("Couldn't apply lyrics").font(.gsf(12.5)).foregroundStyle(.red).transition(.opacity)
            }
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    ForEach(sections, id: \.providerName) { s in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(label(s.providerName)).font(.gsf(12, .medium)).foregroundStyle(tint.primary)
                            if s.candidates.isEmpty {
                                Text(s.errorMessage == nil ? "No results" : "Unavailable")
                                    .font(.gsf(12.5)).foregroundStyle(Yoin.ink3)
                            }
                            ForEach(s.candidates, id: \.songId) { c in
                                Button { pick(c) } label: {
                                    HStack {
                                        VStack(alignment: .leading, spacing: 1) {
                                            Text(c.title).font(.gsf(13.5)).foregroundStyle(Yoin.ink).lineLimit(1)
                                            Text(c.artist).font(.gsf(12)).foregroundStyle(Yoin.inkVariant).lineLimit(1)
                                        }
                                        Spacer()
                                        if applying == c.providerName + c.songId { ProgressView().controlSize(.small) }
                                    }
                                    .padding(.vertical, 5).contentShape(Rectangle())
                                }
                                .buttonStyle(.plain)
                                .disabled(applying != nil)
                            }
                        }
                    }
                }
            }
            .frame(maxHeight: 360)
        }
        .padding(14)
        .frame(width: 340)
        .onAppear {
            forSong = model.player.currentTrack?.id
            if let t = model.player.currentTrack { query = "\(t.title) \(t.artist)" }
            run()
        }
        .onChange(of: model.player.currentTrack?.id) { open = false }
    }

    private func label(_ provider: String) -> String {
        switch provider {
        case "qq": "QQ Music"
        case "netease": "NetEase"
        case "huawei": "Huawei Music"
        case "lrclib": "LRCLIB"
        default: provider
        }
    }

    private func run() {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty, !busy else { return }
        busy = true
        Task {
            sections = await model.lyrics.search(q)
            busy = false
        }
    }

    private func pick(_ c: LyricsCandidate) {
        applying = c.providerName + c.songId
        withAnimation(Yoin.effects) { applyFailed = false }
        Task {
            guard let song = forSong else { applying = nil; return }
            let ok = await model.lyrics.choose(c, for: song)
            applying = nil
            if ok { open = false } else { withAnimation(Yoin.effects) { applyFailed = true } }
        }
    }
}

/// Write: this song's notes (iOS NotesStore, synced), each at the moment it was written; a click on the time
/// jumps there. A new note keeps the playhead it was written at.
struct WritePill: View {
    @Environment(MacModel.self) private var model
    @State private var open = false

    var body: some View {
        NPPill(icon: "edit_note", title: "Write", enabled: model.player.currentTrack != nil) { open.toggle() }
            .popover(isPresented: $open, arrowEdge: .top) { NotesPopover() }
    }
}

private struct NotesPopover: View {
    @Environment(MacModel.self) private var model
    @Environment(\.macTint) private var tint
    @State private var draft = ""
    /// The song and moment the note is for: taken when writing began (a song change meanwhile doesn't move it).
    @State private var at: Double?
    @State private var forTrack: NoteDraftStore.Draft.Target?
    @State private var loaded = false

    var body: some View {
        let t = model.player.currentTrack
        let notes = t.map { NotesStore.shared.notes(provider: $0.provider, trackId: $0.id) } ?? []
        VStack(alignment: .leading, spacing: 10) {
            Text("Notes").font(.gsf(15, .semibold))
            if notes.isEmpty {
                Text("Nothing written for this song yet.").font(.gsf(13)).foregroundStyle(Yoin.inkVariant)
            }
            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    ForEach(notes) { n in
                        HStack(alignment: .firstTextBaseline, spacing: 10) {
                            if let ms = n.positionMs {
                                Button(formatTime(Int(ms / 1000))) { model.player.seek(to: Double(ms) / 1000) }
                                    .buttonStyle(.plain).font(.gsf(12.5, .medium).monospacedDigit())
                                    .foregroundStyle(tint.primary)
                            }
                            Text(n.content).font(.gsf(14)).foregroundStyle(Yoin.ink)
                                .fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
                            Spacer(minLength: 0)
                        }
                        .contextMenu {
                            Button("Delete Note", role: .destructive) {
                                if NotesStore.shared.delete(id: n.id) != nil { CloudSync.shared.localChanged() }
                            }
                        }
                    }
                }
            }
            .frame(maxHeight: 280)
            HStack(spacing: 8) {
                TextField("Write a note", text: $draft, axis: .vertical)
                    .textFieldStyle(.plain).font(.gsf(14)).lineLimit(1...4)
                    .padding(.horizontal, 12).padding(.vertical, 8)
                    .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Yoin.surfaceContainerHigh))
                    .onChange(of: draft) { _, d in
                        if at == nil, !d.isEmpty, let c = model.player.currentTrack {
                            at = model.player.progress.positionSec
                            forTrack = .init(provider: c.provider, rawId: c.id, title: c.title, artist: c.artist)
                        }
                        keepDraft()
                    }
                    .onSubmit(save)
                Button("Save", action: save).buttonStyle(.plain).font(.gsf(13.5, .semibold))
                    .foregroundStyle(tint.primary)
                    .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            if let at {
                let other = forTrack.map { $0.rawId != t?.id } ?? false
                Text(other ? "For \(forTrack?.title ?? "") at \(formatTime(Int(at)))" : "At \(formatTime(Int(at)))")
                    .font(.gsf(12)).foregroundStyle(Yoin.ink3)
            }
        }
        .padding(14)
        .frame(width: 340)
        .onAppear {
            // The unsaved note comes back (iOS NoteDraftStore: per account, kept across closing and relaunch).
            guard !loaded else { return }
            loaded = true
            if let d = NoteDraftStore.shared.draft(scope: model.session.accountKey) {
                draft = d.words
                at = d.anchorMs.map { Double($0) / 1000 }
                forTrack = d.target
            }
        }
    }

    private func keepDraft() {
        guard loaded else { return }
        NoteDraftStore.shared.save(.init(words: draft, anchorMs: at.map { Int64($0 * 1000) }, target: forTrack),
                                   scope: model.session.accountKey)
    }

    private func save() {
        let c = model.player.currentTrack
        guard let t = forTrack ?? c.map({ .init(provider: $0.provider, rawId: $0.id, title: $0.title, artist: $0.artist) })
        else { return }
        let ms = Int64((at ?? model.player.progress.positionSec) * 1000)
        // A note that couldn't be written keeps its words in the editor.
        guard NotesStore.shared.add(provider: t.provider, trackId: t.rawId, content: draft, positionMs: ms,
                                    title: t.title, artist: t.artist) != nil else { return }
        CloudSync.shared.localChanged()
        draft = ""
        at = nil
        forTrack = nil
        NoteDraftStore.shared.save(nil, scope: model.session.accountKey)
    }
}

/// Devices: for a Spotify song, the account's Spotify Connect devices (iOS SpotifyDevicesSheet; the one
/// playing marked, a click moves playback there and Yoin drives it); for Yoin's own audio, the system's
/// output picker (AirPlay and the Mac's outputs).
struct DevicesPill: View {
    @Environment(MacModel.self) private var model
    @State private var open = false

    var body: some View {
        NPPill(icon: "devices", title: "Devices", enabled: model.player.currentTrack != nil) { open.toggle() }
            .popover(isPresented: $open, arrowEdge: .top) {
                if model.player.currentTrack?.provider == SpotifyMapping.provider, let s = model.session.spotify {
                    SpotifyDevicesPopover(spotify: s, open: $open)
                } else {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Play to").font(.gsf(15, .semibold))
                        HStack(spacing: 10) {
                            RoutePicker().frame(width: 28, height: 28)
                            Text("AirPlay and this Mac's outputs").font(.gsf(13)).foregroundStyle(Yoin.inkVariant)
                        }
                    }
                    .padding(14)
                    .frame(width: 280)
                }
            }
    }
}

private struct SpotifyDevicesPopover: View {
    var spotify: SpotifySource
    @Binding var open: Bool
    @Environment(MacModel.self) private var model
    @Environment(\.macTint) private var tint
    @State private var devices: [SpotifySource.Device]?
    @State private var failure: String?
    @State private var moving: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Devices").font(.gsf(15, .semibold))
            if let devices, !devices.isEmpty {
                ForEach(devices) { d in
                    Button { Task { await move(to: d) } } label: {
                        HStack(spacing: 12) {
                            YoinIcon(d.isActive ? "equalizer" : "devices", size: 20)
                                .foregroundStyle(d.isActive ? tint.primary : Yoin.inkVariant)
                            VStack(alignment: .leading, spacing: 1) {
                                Text(d.name).font(.gsf(14, d.isActive ? .semibold : .regular))
                                    .foregroundStyle(d.isActive ? tint.primary : Yoin.ink)
                                Text(moving == d.id ? "Moving…" : d.isActive ? "Playing here" : d.type)
                                    .font(.gsf(12)).foregroundStyle(Yoin.inkVariant)
                            }
                            Spacer(minLength: 0)
                        }
                        .padding(.vertical, 6).contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .disabled(d.isActive || moving != nil)
                }
            } else {
                Text(failure ?? (devices == nil ? "Looking for devices…"
                                 : "No Spotify devices found. Open Spotify on the device you want to play on."))
                    .font(.gsf(13)).foregroundStyle(Yoin.inkVariant).fixedSize(horizontal: false, vertical: true)
            }
            if let failure, devices?.isEmpty == false {
                Text(failure).font(.gsf(12)).foregroundStyle(.red)
            }
        }
        .padding(14)
        .frame(width: 300)
        .task { await load() }
    }

    private func load() async {
        do { devices = try await spotify.devices() } catch {
            devices = []
            failure = SpotifySource.message(for: error)
        }
    }

    /// The listener's pick: Spotify moves there and Yoin drives that device from now on.
    private func move(to d: SpotifySource.Device) async {
        moving = d.id
        defer { moving = nil }
        do {
            try await spotify.transfer(to: d.id)
            model.spotifyOutput.adopt(deviceId: d.id, name: d.name, on: spotify)
            open = false
        } catch {
            failure = SpotifySource.message(for: error)
            await load()
        }
    }
}

/// The system's output picker (AVKit's AVRoutePickerView on the Mac).
private struct RoutePicker: NSViewRepresentable {
    func makeNSView(context: Context) -> AVRoutePickerView {
        let v = AVRoutePickerView()
        v.isRoutePickerButtonBordered = false
        return v
    }

    func updateNSView(_ v: AVRoutePickerView, context: Context) {}
}
