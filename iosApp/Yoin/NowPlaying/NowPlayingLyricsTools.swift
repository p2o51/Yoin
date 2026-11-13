import SwiftUI
import UIKit

// The expanded Lyrics page's tools, ported from Android:
// - LyricsActionBar.kt: four keys — Search, Translate, Select (✓), Recenter —
//   8 pt apart, the pressed one widening into its neighbours (M3 Expressive
//   ButtonGroup press, ExpandedRatio 0.15). Here each key is the NP pills'
//   primary-tinted Liquid Glass (Glass Lab "npPills"), all four in one
//   GlassEffectContainer.
// - LyricsSelectionBar: select mode's keys in the same slot — Done (✕,
//   emphasized), Copy, Share as image, Edit lyrics.
// - LyricsFullscreenPane.kt: the expanded list (follows the playhead until
//   the user scrolls; tap a line to seek; in select mode, tap to pick).
// - LyricsSheets.kt: the lyrics search and the LRC editor.

/// One lyric key (Android `LyricsActionIcon`).
struct LyricKey: Identifiable {
    var id: String { icon }
    var icon: String
    var label: String
    var enabled = true
    /// The key that leaves a mode reads stronger than the actions beside it.
    var emphasized = false
    /// Translate while its own translation runs: lit, pulsing, taps ignored.
    var busy = false
    var action: () -> Void
}

/// Android `LyricsKeyRow`: 52 pt keys, 8 pt apart, press widening.
struct LyricKeyRow: View {
    var keys: [LyricKey]
    var scheme: CoverScheme
    var size: CGFloat = 52
    @State private var pressed: Int?
    @Environment(\.colorScheme) private var colorScheme

    static let gap: CGFloat = 8

    var body: some View {
        let widths = LyricsRules.keyWidths(count: keys.count, key: size) { $0 == pressed ? 1 : 0 }
        GlassEffectContainer(spacing: 0) {
            HStack(spacing: Self.gap) {
                ForEach(Array(keys.enumerated()), id: \.element.id) { i, key in
                    keyButton(key, index: i, width: widths[i])
                }
            }
        }
        .animation(Yoin.spatialFast, value: pressed)
    }

    private func keyButton(_ key: LyricKey, index: Int, width: CGFloat) -> some View {
        let lab = GlassLab.shared
        let shape = RoundedRectangle(cornerRadius: size * 0.31, style: .continuous)
        // The pills' ink: onPrimary in light, white in dark (neutral preset: onSurface).
        // World: the keys float over the lyrics (NP's floating chrome), neutral
        // glass with on-surface ink; only an emphasized key takes the accent.
        let neutral = lab.oneAccentPerLayer ? !key.emphasized : lab.neutralTint("npPills")
        let ink = neutral ? scheme.onSurface : colorScheme == .dark ? Color.white : scheme.onPrimary
        let live = key.enabled || key.busy
        return Button {
            if key.enabled, !key.busy { key.action() }
        } label: {
            YoinIcon(key.icon, size: 24)
                .symbolEffect(.pulse, options: .repeating, isActive: key.busy)
                .foregroundStyle(ink.opacity(live ? 1 : 0.38))
                .labContrast("transportPrimary")
                .frame(width: width, height: size)
                .contentShape(shape)
                .modifier(LyricKeyGlass(emphasized: key.emphasized, scheme: scheme, shape: shape))
                .animation(Yoin.effects, value: live)
        }
        .buttonStyle(LyricKeyPress(index: index, pressed: $pressed))
        .disabled(!key.enabled && !key.busy)
        .accessibilityLabel(key.label)
    }
}

private struct LyricKeyGlass<S: Shape>: ViewModifier {
    var emphasized: Bool
    var scheme: CoverScheme
    var shape: S
    @Environment(\.colorScheme) private var colorScheme

    func body(content: Content) -> some View {
        if GlassLab.shared.oneAccentPerLayer {
            content.npPillGlass("npFloating", tint: emphasized ? scheme.primary : scheme.surfaceContainerHighest,
                                strength: emphasized ? 1.2 : 0.6, in: shape)
        } else if emphasized {
            // Android: primary (the others primaryContainer) — here a denser primary tint.
            content.npPillGlass(tint: scheme.primary, strength: 1.5, in: shape)
        } else {
            content.labGlass("npPills", scheme: scheme, brand: .brand(dark: colorScheme == .dark),
                             auto: scheme.primary, in: shape)
        }
    }
}

/// Reports the held key to the row (its width springs open) and scales a touch.
private struct LyricKeyPress: ButtonStyle {
    var index: Int
    @Binding var pressed: Int?

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .onChange(of: configuration.isPressed) { _, down in
                if down { pressed = index } else if pressed == index { pressed = nil }
            }
    }
}

// MARK: - Expanded lyrics list (Android LyricsFullscreenPane)

struct ExpandedLyricsList: View {
    var lines: [String]
    var times: [Int64]
    var translations: [String?]
    var loaded: NowPlayingData.LoadedLyrics
    /// Follows the playhead (Android `autoScrollEnabled && !selecting`).
    var follow: Bool
    var recenterTick: Int
    var selecting: Bool
    var selected: Set<Int>
    var limitNudge: Bool
    var scheme: CoverScheme
    var onUserScroll: () -> Void
    var onTap: (Int) -> Void
    /// Two-column focus (Android LyricsFullscreenPane halftoneFocus): every
    /// line prints through fine dots; the current line is solid, the next
    /// two stay readable.
    var halftone: Bool = false
    /// Two columns (Android LyricsFullscreenPane): the seat is read again from
    /// the window once it holds still after a resize — focus folds the tools
    /// away and the page grows — and the line glides to it.
    var reseatOnResize: Bool = false

    /// Android LyricsRowInset: rows reach this far past the page edge.
    static let rowInset: CGFloat = 12

    @Environment(AppModel.self) private var model

    var synced: Bool { loaded.synced }
    /// The playhead's line (-1 = before the first line, or unsynced), as PlaybackPosition reads it.
    private var active: Int {
        let sec = model.player.currentTrack != nil ? model.player.progress.positionSec : Double(model.mockPlayback.positionSec)
        return loaded.activeIndex(positionSec: sec)
    }

    /// Where the list is right now (a box, so scrolling doesn't re-render the rows).
    @State private var offset = OffsetBox()
    /// The list's live geometry, kept off the render path: the offset, the window's height and
    /// each row's frame in the content (the seat is computed from them, as Android's
    /// lyricsFocusScrollOffsetPx is from the list's layout info).
    final class OffsetBox {
        var y: CGFloat = 0
        var viewport: CGFloat = 0
        var contentHeight: CGFloat = 0
        var rows: [Int: CGRect] = [:]
        /// The window's top on the screen (global), for the screen seat.
        var windowTop: CGFloat = 0
        /// The seat waiting out its frame: cancelled by the reader's scroll and by any newer seat.
        var seating: Task<Void, Never>?
    }
    /// The window's top fade (the mask) and bottom fade (LyricsEdgeFade).
    static let fadeTop: CGFloat = 16
    static let fadeBottom: CGFloat = 64
    static let contentSpace = "expandedLyricsContent"
    /// Scrolled through its binding: a ScrollViewReader scroll asked for after the user's own
    /// scroll was dropped (the key greyed out, the list stayed put).
    @State private var position = ScrollPosition(idType: Int.self)

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if selecting {
                // Select mode's caption, in its own band above the list.
                Text(LyricsRules.selectionLabel(selected.count, limitNudge: limitNudge))
                    .yoinRole(Tokens.type.labelLarge)
                    .foregroundStyle(limitNudge ? scheme.primary : scheme.onSurfaceVariant)
                    .contentTransition(.opacity)
                    .padding(.horizontal, Self.rowInset)
                    .padding(.vertical, 8)
                    .transition(.opacity)
            }
                ScrollView(.vertical, showsIndicators: false) {
                    VStack(alignment: .leading, spacing: 4) {
                        ForEach(lines.indices, id: \.self) { i in
                            row(i).id(i)
                                .onGeometryChange(for: CGRect.self) { $0.frame(in: .named(Self.contentSpace)) } action: { offset.rows[i] = $0 }
                        }
                        Color.clear.frame(height: 96)
                    }
                    .scrollTargetLayout()
                    .padding(.top, 48)
                    .padding(.bottom, 48)
                    // The scroll content's own space: a row's minY here is its content offset.
                    .coordinateSpace(.named(Self.contentSpace))
                }
                .scrollPosition($position)
                .onGeometryChange(for: CGFloat.self) { $0.frame(in: .global).minY } action: { offset.windowTop = $0 }
                .onScrollGeometryChange(for: ScrollGeometry.self) { $0 } action: { _, g in
                    let resized = offset.viewport > 0 && abs(offset.viewport - g.containerSize.height) > 1
                    offset.y = g.contentOffset.y
                    offset.viewport = g.containerSize.height
                    offset.contentHeight = g.contentSize.height
                    if resized && reseatOnResize { reseatWhenSettled() }
                }
                // A quick flick can go from tracking straight to decelerating, never reporting
                // .interacting: the list left the line while still "following", so Recenter
                // stayed greyed out and could not bring it back.
                .onScrollPhaseChange { _, phase in
                    if phase == .interacting || phase == .decelerating {
                        // The reader has the list: a seat still waiting doesn't pull it back (Codex M03).
                        offset.seating?.cancel()
                        onUserScroll()
                    }
                }
                .mask(NowPlayingAboutView.fade(top: Self.fadeTop, bottom: Self.fadeBottom))
                .onAppear { seat(animated: false) }
                .onChange(of: active) { seat(animated: true) }
                // Recenter (and a tapped line) also catches a list still coasting from a fling.
                .onChange(of: recenterTick) { seat(animated: true, halt: true) }
                .onChange(of: follow) { seat(animated: true, halt: true) }
        }
        .padding(.horizontal, -Self.rowInset)
        .animation(Yoin.effects, value: selecting)
        .animation(Yoin.effects, value: limitNudge)
    }

    /// The active row's centre in the middle of the window's clean band, between the two edge
    /// fades (Android LyricsFocusFraction 0.5 / lyricsFocusScrollOffsetPx); a row taller than the
    /// band starts at its top. An id-anchored scroll left the line near the top of the window.
    /// [halt]: a list still coasting from a fling kept its momentum and threw the animated
    /// scroll away, so Recenter did nothing and, following again, stayed greyed out. Pinning
    /// the offset where it is first stops the coast (Android: stopScroll, then animate).
    private func seat(animated: Bool, halt: Bool = false) {
        offset.seating?.cancel()
        offset.seating = nil
        guard follow, active >= 0, active < lines.count else { return }
        let target = active
        guard animated else {
            if let y = seatY(target) { position.scrollTo(y: y) } else { position.scrollTo(id: target, anchor: .center) }
            return
        }
        if halt { position.scrollTo(y: offset.y) }
        offset.seating = Task { @MainActor in
            // A frame for the row to take its active style (its height is the seat's input).
            try? await Task.sleep(for: .milliseconds(34))
            guard !Task.isCancelled else { return }
            withAnimation(Yoin.spatial) {
                if let y = seatY(target) { position.scrollTo(y: y) } else { position.scrollTo(id: target, anchor: .center) }
            }
        }
    }

    /// Android LyricsSeatSettleMs: a window that moves every frame (a stage
    /// reshape) is read once it has held still this long.
    static let seatSettle: Duration = .milliseconds(250)

    /// Android's re-seat after the window's geometry changes: wait for it to
    /// hold still, then glide the line to the seat the new window gives
    /// (Android seatSettleSpec: the Standard default spatial spring).
    private func reseatWhenSettled() {
        guard follow, active >= 0, active < lines.count else { return }
        offset.seating?.cancel()
        offset.seating = Task { @MainActor in
            try? await Task.sleep(for: Self.seatSettle)
            guard !Task.isCancelled, follow, let y = seatY(active) else { return }
            withAnimation(Yoin.standardDefaultSpatial) { position.scrollTo(y: y) }
        }
    }

    /// Where on the SCREEN the sung line sits, wherever the lyric page is laid out (51 2026-11-08:
    /// 「不管在什么地方播放，歌词高亮都应该在屏幕中间靠上的位置」; Android LyricsScreenSeat, #185).
    static let screenSeat: CGFloat = 0.4
    /// The seat never climbs above this share of the clean band, so sung lines still show above it.
    static let seatMinFraction: CGFloat = 0.2

    /// The share of the clean band (between the fades) that puts the row's centre at [screenSeat]
    /// of a [screen]-tall window, for a lyric window [window] tall whose top is [windowTop] below the
    /// screen's top. Held between [seatMinFraction] and the band's middle; stepped to 2 % so a point
    /// of drift never re-seats the list (Android lyricsScreenSeatFraction).
    static func screenSeatFraction(windowTop: CGFloat, window: CGFloat, screen: CGFloat) -> CGFloat {
        let band = window - fadeTop - fadeBottom
        guard band > 0, screen > 0 else { return 0.5 }
        let raw = (screen * screenSeat - windowTop - fadeTop) / band
        let held = min(max(raw, seatMinFraction), 0.5)
        return (held * 50).rounded() / 50
    }

    /// The height of the window the app is drawn in (a split iPad window, not the whole screen).
    static var screenHeight: CGFloat {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let window = scenes.flatMap(\.windows).first { $0.isKeyWindow } ?? scenes.first?.windows.first
        return window?.bounds.height ?? 0
    }

    /// The content offset that seats row [i]; nil before it has been laid out.
    private func seatY(_ i: Int) -> CGFloat? {
        guard let row = offset.rows[i], offset.viewport > 0 else { return nil }
        let bandTop = Self.fadeTop
        let bandBottom = max(bandTop, offset.viewport - Self.fadeBottom)
        let share = Self.screenSeatFraction(windowTop: offset.windowTop, window: offset.viewport,
                                            screen: Self.screenHeight)
        let rowTop = row.height >= bandBottom - bandTop
            ? bandTop
            : min(max(bandTop + (bandBottom - bandTop) * share - row.height / 2, bandTop), bandBottom - row.height)
        let maxY = max(0, offset.contentHeight - offset.viewport)
        return min(max(row.minY - rowTop, 0), maxY)
    }

    @ViewBuilder
    private func row(_ i: Int) -> some View {
        let picked = selecting && selected.contains(i)
        let isActive = !selecting && i == active
        let playingInSelect = selecting && i == active
        let emphasized = isActive || picked
        let distance = active >= 0 ? abs(i - active) : 1
        let full = selected.count >= LyricsRules.maxSelectedLines
        let alpha: Double = emphasized ? 1
            : selecting ? (full && !picked ? 0.42 : 0.7)
            : distance <= 1 ? 0.62 : distance == 2 ? 0.5 : 0.4
        let ink = picked ? scheme.onPrimaryContainer
            : (isActive || playingInSelect) ? scheme.primary : scheme.onSurfaceVariant
        let runTop = !(picked && selected.contains(i - 1))
        let runBottom = !(picked && selected.contains(i + 1))
        let translation = i < translations.count ? translations[i] : nil
        let text = lines[i]
        let dots = halftone && !selecting ? LyricHalftone.radius(line: i, active: active) : LyricHalftone.solid
        VStack(alignment: .leading, spacing: 0) {
            Text(text.isEmpty ? " " : text)
                .font(emphasized ? YoinFont.rounded(24, semibold: true) : .gsf(24, .medium))
                .tracking(0)
                .lineSpacing(4)
                .foregroundStyle(ink)
            if let translation, !translation.isEmpty {
                Text(translation)
                    .font(.gsf(16, emphasized ? .medium : .regular))
                    .tracking(0)
                    .foregroundStyle(ink.opacity(emphasized ? 0.82 : 0.7))
                    .padding(.top, 4)
                    .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
        .scaleEffect(emphasized ? 1 : 0.96, anchor: .leading)
        // Focus's halftone (Android lyricHalftone): solid → this line's dots.
        .modifier(LyricHalftone(radius: dots, space: Self.contentSpace))
        .animation(Yoin.effects, value: dots)
        .opacity(alpha)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, Self.rowInset)
        .padding(.vertical, 6)
        .background {
            UnevenRoundedRectangle(topLeadingRadius: runTop ? 16 : 4, bottomLeadingRadius: runBottom ? 16 : 4,
                                   bottomTrailingRadius: runBottom ? 16 : 4, topTrailingRadius: runTop ? 16 : 4,
                                   style: .continuous)
                .fill(scheme.primaryContainer.opacity(picked ? 1 : 0))
        }
        // A pointer over a line (it seeks, or picks in Select): the hovered state layer, off on a picked line.
        .hoverLayer(in: RoundedRectangle(cornerRadius: 16, style: .continuous), ink: scheme.onSurface, enabled: !picked)
        .contentShape(.rect)
        .onTapGesture { onTap(i) }
        .animation(Yoin.effects, value: picked)
        .animation(Yoin.effects, value: isActive)
        .animation(Yoin.spatial, value: emphasized)
        .animation(Yoin.spatial, value: translation)
        .accessibilityAddTraits(selecting ? .isButton : [])
        .accessibilityAddTraits(picked ? .isSelected : [])
    }
}

// MARK: - Lyrics search (Android LyricsSearchSheet)

struct LyricsSearchSheet: View {
    var scheme: CoverScheme
    var onSelect: (LyricsActions.Candidate) -> Void
    var onClose: () -> Void
    @State private var text = ""
    @FocusState private var focused: Bool
    private var actions: LyricsActions { .shared }

    var body: some View {
        let s = actions.search
        VStack(spacing: 0) {
            HStack(spacing: 4) {
                Button(action: onClose) {
                    YoinIcon("back", size: 24).frame(width: 44, height: 44).contentShape(.circle)
                }
                .buttonStyle(PressScale())
                .accessibilityLabel("Close lyrics search")
                TextField("", text: $text, prompt: Text("Song or artist").foregroundStyle(scheme.onSurfaceVariant))
                    .font(.gsf(16))
                    .tracking(0)
                    .foregroundStyle(scheme.onSurface)
                    .tint(scheme.primary)
                    .focused($focused)
                    .submitLabel(.search)
                    .onSubmit { actions.runSearch(text) }
                    .autocorrectionDisabled()
                if s.loading {
                    ProgressView().controlSize(.small).tint(scheme.primary).frame(width: 44, height: 44)
                } else if !text.isEmpty {
                    Button { text = "" } label: {
                        YoinIcon("close", size: 20).frame(width: 44, height: 44).contentShape(.circle)
                    }
                    .buttonStyle(PressScale())
                    .accessibilityLabel("Clear")
                }
            }
            .foregroundStyle(scheme.onSurfaceVariant)
            .padding(.horizontal, 6)
            .frame(height: 56)
            .labSearchGlass(scheme: scheme, in: Capsule())
            .padding(.horizontal, 16)
            .padding(.top, 16)

            ScrollView {
                LazyVStack(alignment: .leading, spacing: 4) {
                    if let e = s.errorMessage {
                        Text(e).yoinRole(Tokens.type.bodySmall).foregroundStyle(scheme.error)
                            .padding(.horizontal, 8).padding(.vertical, 4)
                    }
                    // Android LyricsSearchSheet: a section per provider, in the registry's order.
                    if s.loading && s.sections.isEmpty {
                        status(String(localized: "Searching..."), loading: true)
                    } else if s.sections.isEmpty {
                        status(s.query.isEmpty ? "" : String(localized: "No results"))
                    }
                    ForEach(s.sections) { section in
                        Text(section.label)
                            .font(.gsf(12, .semibold)).tracking(0.1)
                            .foregroundStyle(scheme.primary)
                            .padding(8)
                            .padding(.top, 8)
                        if let e = section.error {
                            status(e, error: true)
                        } else if section.candidates.isEmpty {
                            status(String(localized: "No results"))
                        } else {
                            ForEach(section.candidates) { r in
                                resultRow(r, applying: s.applyingId == r.id, enabled: s.applyingId == nil)
                            }
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
            }
        }
        .background(scheme.surfaceContainerHigh.ignoresSafeArea())
        .onAppear {
            text = actions.search.query
            focused = false
        }
        .onChange(of: text) { _, t in actions.search.query = t }
    }

    private func status(_ text: String, loading: Bool = false, error: Bool = false) -> some View {
        HStack(spacing: 10) {
            if loading { ProgressView().controlSize(.small).tint(scheme.primary) }
            Text(text).yoinRole(Tokens.type.bodyMedium)
                .foregroundStyle(error ? scheme.error : scheme.onSurfaceVariant)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 10)
    }

    private func resultRow(_ r: LyricsActions.Candidate, applying: Bool, enabled: Bool) -> some View {
        Button { onSelect(r) } label: {
            HStack {
                VStack(alignment: .leading, spacing: 0) {
                    Text(r.title).yoinRole(Tokens.type.bodyLarge).foregroundStyle(scheme.onSurface).lineLimit(1)
                    Text(r.artist).yoinRole(Tokens.type.bodyMedium).foregroundStyle(scheme.onSurfaceVariant).lineLimit(1)
                }
                Spacer(minLength: 8)
                if applying {
                    ProgressView().controlSize(.small).tint(scheme.primary)
                } else {
                    YoinIcon("chevron_right", size: 24).foregroundStyle(scheme.onSurfaceVariant)
                }
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 10)
            .contentShape(RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(PressScale())
        .disabled(!enabled)
    }
}

// MARK: - LRC editor (Android LyricsApplyDialog)

struct LyricsEditSheet: View {
    var scheme: CoverScheme
    var initialText: String
    var onApply: (String) -> Void
    var onCancel: () -> Void
    @State private var draft = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Button("Cancel", action: onCancel)
                    .font(.gsf(14, .medium)).foregroundStyle(scheme.primary)
                Spacer()
                Text("Apply lyrics").yoinRole(Tokens.type.titleMedium).foregroundStyle(scheme.onSurface)
                Spacer()
                Button("Apply") { onApply(draft) }
                    .font(.gsf(14, .medium)).foregroundStyle(scheme.primary)
                    .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            .frame(height: 44)
            TextEditor(text: $draft)
                .font(.gsf(14).monospacedDigit())
                .scrollContentBackground(.hidden)
                .foregroundStyle(scheme.onSurface)
                .tint(scheme.primary)
                .overlay(alignment: .topLeading) {
                    if draft.isEmpty {
                        Text("Lyrics").font(.gsf(14)).foregroundStyle(scheme.onSurfaceVariant)
                            .padding(.top, 8).padding(.leading, 5).allowsHitTesting(false)
                    }
                }
        }
        .padding(16)
        .background(scheme.surfaceContainerHigh.ignoresSafeArea())
        .onAppear { draft = initialText }
    }
}

// MARK: - Share as image (Android LyricsShareSheet, the Card)

struct LyricsShareSnapshot: Identifiable {
    let id = UUID()
    var lines: [String]
    var translations: [String?]
    var passageStarts: Set<Int>
    var title: String
    var artist: String
}

/// What a pick is shared as (Android LyricsShareFormat): the quote card on its own, or a 9:16 story
/// with the card centred on the tone's backdrop.
enum LyricsShareFormat: Hashable, CaseIterable { case card, story }

/// The card's colour pairings (Android LyricsCardTone, spotoolfy's four poster styles), from the
/// playing palette.
enum LyricsCardTone: Hashable, CaseIterable {
    case soft, deep, vivid, mist

    struct Colors {
        var card: Color
        var content: Color
        var backdrop: Color
    }

    func colors(_ s: CoverScheme) -> Colors {
        switch self {
        case .soft: Colors(card: s.primaryContainer, content: s.onPrimaryContainer, backdrop: s.primary)
        case .deep: Colors(card: s.onPrimaryContainer, content: s.primaryContainer, backdrop: s.primaryContainer)
        case .vivid: Colors(card: s.tertiary, content: s.onTertiary, backdrop: s.tertiaryContainer)
        case .mist: Colors(card: s.tertiaryContainer, content: s.onTertiaryContainer, backdrop: s.tertiary)
        }
    }

    var label: String {
        switch self {
        case .soft: String(localized: "Soft colours")
        case .deep: String(localized: "Deep colours")
        case .vivid: String(localized: "Vivid colours")
        case .mist: String(localized: "Mist colours")
        }
    }
}

enum LyricsShareLayout {
    /// The card's logical width (with its padding).
    static let cardWidth: CGFloat = 376
    /// The story's logical size: 1080 × 1920 at 3×.
    static let story = CGSize(width: 360, height: 640)
    /// The story's card stays clear of the chrome stories draw over the top and bottom.
    static let storyMargin = CGSize(width: 28, height: 72)
    static let swatch: CGFloat = 36
    static let swatchTouch: CGFloat = 48
    static let swatchSelectedCorner: CGFloat = 10

    /// The card's scale inside the story: as is when it fits, smaller (never larger) when it doesn't.
    static func storyScale(card: CGSize) -> CGFloat {
        guard card.width > 0, card.height > 0 else { return 1 }
        let room = CGSize(width: story.width - 2 * storyMargin.width, height: story.height - 2 * storyMargin.height)
        return min(1, room.width / card.width, room.height / card.height)
    }
}

struct LyricsShareCard: View {
    var snapshot: LyricsShareSnapshot
    var scheme: CoverScheme
    var tone: LyricsCardTone = .soft

    var body: some View {
        let c = tone.colors(scheme)
        VStack(alignment: .leading, spacing: 6) {
            ForEach(snapshot.lines.indices, id: \.self) { i in
                VStack(alignment: .leading, spacing: 2) {
                    Text(snapshot.lines[i])
                        .font(YoinFont.rounded(22, semibold: true))
                        .foregroundStyle(c.content)
                    if let t = snapshot.translations[i], !t.isEmpty {
                        Text(t).font(.gsf(15)).foregroundStyle(c.content.opacity(0.75))
                    }
                }
                .padding(.top, snapshot.passageStarts.contains(i) ? 14 : 0)
            }
            if let credit = LyricsRules.credit(title: snapshot.title, artist: snapshot.artist) {
                Text("— \(credit)")
                    .font(.gsf(13, .medium)).tracking(0.1)
                    .foregroundStyle(c.content.opacity(0.7))
                    .padding(.top, 18)
            }
        }
        .frame(width: LyricsShareLayout.cardWidth - 56, alignment: .leading)
        .padding(28)
        .background(c.card, in: RoundedRectangle(cornerRadius: 28, style: .continuous))
    }
}

/// The 9:16 story (Android LyricsShareStory): the tone's backdrop, full bleed, the card centred and
/// scaled down to fit rather than ever cutting a line.
struct LyricsShareStory: View {
    var snapshot: LyricsShareSnapshot
    var scheme: CoverScheme
    var tone: LyricsCardTone
    /// LyricsShareLayout.storyScale of the card's measured size (ImageRenderer draws in one pass).
    var cardScale: CGFloat

    var body: some View {
        ZStack {
            tone.colors(scheme).backdrop
            LyricsShareCard(snapshot: snapshot, scheme: scheme, tone: tone)
                .fixedSize()
                .scaleEffect(cardScale)
        }
        .frame(width: LyricsShareLayout.story.width, height: LyricsShareLayout.story.height)
        .clipped()
    }
}

/// The exported image in the chosen format and tone.
struct LyricsShareImage: View {
    var snapshot: LyricsShareSnapshot
    var scheme: CoverScheme
    var format: LyricsShareFormat
    var tone: LyricsCardTone
    var cardScale: CGFloat = 1

    var body: some View {
        switch format {
        case .card: LyricsShareCard(snapshot: snapshot, scheme: scheme, tone: tone)
        case .story: LyricsShareStory(snapshot: snapshot, scheme: scheme, tone: tone, cardScale: cardScale)
        }
    }
}

/// Android LyricsShareSheet: the preview, Card / Story, four tones, then Close and Share.
struct LyricsShareSheet: View {
    var snapshot: LyricsShareSnapshot
    var scheme: CoverScheme
    var onClose: () -> Void
    @State private var format: LyricsShareFormat = .card
    @State private var tone: LyricsCardTone = .soft
    @State private var image: UIImage?
    @State private var cardScale: CGFloat = 1

    var body: some View {
        VStack(spacing: 16) {
            GeometryReader { geo in
                ScrollView {
                    preview(room: geo.size)
                        .padding(.top, 24)
                        .frame(maxWidth: .infinity)
                }
            }
            Picker("Format", selection: $format.animation(Yoin.spatial)) {
                Text("Card").tag(LyricsShareFormat.card)
                Text("Story").tag(LyricsShareFormat.story)
            }
            .pickerStyle(.segmented)
            .frame(maxWidth: 280)
            swatches
            HStack(spacing: 12) {
                Button(action: onClose) {
                    Text("Close").yoinRole(Tokens.type.labelLarge).foregroundStyle(scheme.primary)
                        .padding(.horizontal, 20).frame(height: 48)
                }
                .buttonStyle(PressScale())
                if let image {
                    ShareLink(item: Image(uiImage: image),
                              preview: SharePreview(LyricsRules.credit(title: snapshot.title, artist: snapshot.artist) ?? "Lyrics",
                                                    image: Image(uiImage: image))) {
                        HStack(spacing: 6) {
                            YoinIcon("share", size: 20)
                            Text("Share").yoinRole(Tokens.type.labelLarge)
                        }
                        .foregroundStyle(scheme.onPrimary)
                        .padding(.horizontal, 24).frame(height: 48)
                        .background(scheme.primary, in: Capsule())
                    }
                    .buttonStyle(PressScale())
                }
            }
            .padding(.bottom, 12)
        }
        .background(scheme.surfaceContainerHigh.ignoresSafeArea())
        .task(id: [format.hashValue, tone.hashValue]) { render() }
    }

    /// The image as it will be shared; a story is shown scaled down to the room it has.
    @ViewBuilder
    private func preview(room: CGSize) -> some View {
        let img = LyricsShareImage(snapshot: snapshot, scheme: scheme, format: format, tone: tone, cardScale: cardScale)
        switch format {
        case .card:
            img
        case .story:
            let s = min(1, (room.width - 48) / LyricsShareLayout.story.width,
                        max(0.3, room.height - 48) / LyricsShareLayout.story.height)
            img.scaleEffect(s)
                .frame(width: LyricsShareLayout.story.width * s, height: LyricsShareLayout.story.height * s)
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
    }

    private var swatches: some View {
        HStack(spacing: 4) {
            ForEach(LyricsCardTone.allCases, id: \.self) { t in
                let c = t.colors(scheme)
                let on = t == tone
                Button { withAnimation(Yoin.spatial) { tone = t } } label: {
                    RoundedRectangle(cornerRadius: on ? LyricsShareLayout.swatchSelectedCorner : LyricsShareLayout.swatch / 2,
                                     style: .continuous)
                        .fill(c.card)
                        .frame(width: LyricsShareLayout.swatch, height: LyricsShareLayout.swatch)
                        .overlay {
                            if on {
                                YoinIcon("check", size: 20).foregroundStyle(c.content)
                                    .transition(.scale(scale: 0.6).combined(with: .opacity))
                            }
                        }
                        .frame(width: LyricsShareLayout.swatchTouch, height: LyricsShareLayout.swatchTouch)
                        .contentShape(Rectangle())
                }
                .buttonStyle(PressScale())
                .accessibilityLabel(t.label)
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
    }

    private func render() {
        // The card's own size, the same in every tone: what the story scales it by.
        let card = ImageRenderer(content: LyricsShareCard(snapshot: snapshot, scheme: scheme).fixedSize())
        if let size = card.uiImage?.size { cardScale = LyricsShareLayout.storyScale(card: size) }
        let r = ImageRenderer(content: LyricsShareImage(snapshot: snapshot, scheme: scheme, format: format, tone: tone,
                                                        cardScale: cardScale))
        r.scale = 3
        image = r.uiImage
    }
}

/// Android LyricHalftone.kt: in focus a lyric line is printed through a grid
/// of dots `cell` apart, `radius` across; at `solid` and above the 4 pt cell is
/// covered and no mask runs. Uses the DotDissolve shader (the same dot mask).
nonisolated struct LyricHalftone: ViewModifier, Animatable {
    var radius: CGFloat
    /// The list's content coordinate space: the grid's shared origin.
    var space: String? = nil

    /// LyricDotsCell / LyricDotsSolid.
    static let cell: CGFloat = 4
    static let solid: CGFloat = 3
    /// LyricDotsRestLarge: tablets (medium width and height) — the two-column player.
    static let restLarge: CGFloat = 1.8
    /// LyricDotsReadable, for LyricDotsReadableLines after the current one.
    static let readable: CGFloat = 2.2
    static let readableLines = 2

    /// A line's dots in two-column focus: the current line solid, the next
    /// two readable, the rest (sung lines included) at the tablet rest.
    static func radius(line: Int, active: Int) -> CGFloat {
        if line == active { return solid }
        if active >= 0, line > active, line - active <= readableLines { return readable }
        return restLarge
    }

    var animatableData: CGFloat {
        get { radius }
        set { radius = newValue }
    }

    func body(content: Content) -> some View {
        let r = radius, on = radius < Self.solid - 0.01, space = self.space
        content
            .padding(2)
            // One grid for the whole list: each line's dots are phased from
            // its place in the list's content space (Android tiles the dots
            // with the content, so they ride along as it scrolls).
            .visualEffect { view, proxy in
                let o = space.map { proxy.frame(in: .named($0)).origin } ?? .zero
                return view.colorEffect(ShaderLibrary.yoinDotGrid(.float(Self.cell), .float(r),
                                                                  .float2(Float(o.x), Float(o.y))),
                                        isEnabled: on)
            }
            .padding(-2)
    }
}
