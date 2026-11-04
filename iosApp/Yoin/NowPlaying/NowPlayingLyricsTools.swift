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
    var synced: Bool
    /// -1 = before the first line, or unsynced.
    var active: Int
    /// Follows the playhead (Android `autoScrollEnabled && !selecting`).
    var follow: Bool
    var recenterTick: Int
    var selecting: Bool
    var selected: Set<Int>
    var limitNudge: Bool
    var scheme: CoverScheme
    var onUserScroll: () -> Void
    var onTap: (Int) -> Void

    /// Android LyricsRowInset: rows reach this far past the page edge.
    static let rowInset: CGFloat = 12

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
            ScrollViewReader { proxy in
                ScrollView(.vertical, showsIndicators: false) {
                    VStack(alignment: .leading, spacing: 4) {
                        ForEach(lines.indices, id: \.self) { i in
                            row(i).id(i)
                        }
                        Color.clear.frame(height: 96)
                    }
                    .padding(.top, 48)
                    .padding(.bottom, 48)
                }
                .onScrollPhaseChange { _, phase in
                    if phase == .interacting { onUserScroll() }
                }
                .mask(NowPlayingAboutView.fade(top: 16, bottom: 64))
                .onAppear { seat(proxy, animated: false) }
                .onChange(of: active) { seat(proxy, animated: true) }
                .onChange(of: recenterTick) { seat(proxy, animated: true) }
                .onChange(of: follow) { seat(proxy, animated: true) }
            }
        }
        .padding(.horizontal, -Self.rowInset)
        .animation(Yoin.effects, value: selecting)
        .animation(Yoin.effects, value: limitNudge)
    }

    /// The active row's centre in the middle of the window's clean band.
    private func seat(_ proxy: ScrollViewProxy, animated: Bool) {
        guard follow, active >= 0, active < lines.count else { return }
        let anchor = UnitPoint(x: 0, y: 0.45)
        if animated {
            withAnimation(Yoin.spatial) { proxy.scrollTo(active, anchor: anchor) }
        } else {
            proxy.scrollTo(active, anchor: anchor)
        }
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
    var onSelect: (LrclibClient.Match) -> Void
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
                    Text(LrclibClient.providerLabel)
                        .font(.gsf(12, .semibold)).tracking(0.1)
                        .foregroundStyle(scheme.primary)
                        .padding(8)
                    if let e = s.providerError {
                        status(e, error: true)
                    } else if s.loading && s.results.isEmpty {
                        status("Searching...", loading: true)
                    } else if s.results.isEmpty {
                        status(s.query.isEmpty ? "" : "No results")
                    } else {
                        ForEach(s.results) { r in resultRow(r, applying: s.applyingId == r.id, enabled: s.applyingId == nil) }
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

    private func resultRow(_ r: LrclibClient.Match, applying: Bool, enabled: Bool) -> some View {
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

struct LyricsShareCard: View {
    var snapshot: LyricsShareSnapshot
    var scheme: CoverScheme

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(snapshot.lines.indices, id: \.self) { i in
                VStack(alignment: .leading, spacing: 2) {
                    Text(snapshot.lines[i])
                        .font(YoinFont.rounded(22, semibold: true))
                        .foregroundStyle(scheme.onPrimaryContainer)
                    if let t = snapshot.translations[i], !t.isEmpty {
                        Text(t).font(.gsf(15)).foregroundStyle(scheme.onPrimaryContainer.opacity(0.75))
                    }
                }
                .padding(.top, snapshot.passageStarts.contains(i) ? 14 : 0)
            }
            if let credit = LyricsRules.credit(title: snapshot.title, artist: snapshot.artist) {
                Text("— \(credit)")
                    .font(.gsf(13, .medium)).tracking(0.1)
                    .foregroundStyle(scheme.onPrimaryContainer.opacity(0.7))
                    .padding(.top, 18)
            }
        }
        .frame(width: 320, alignment: .leading)
        .padding(28)
        .background(scheme.primaryContainer, in: RoundedRectangle(cornerRadius: 28, style: .continuous))
    }
}

struct LyricsShareSheet: View {
    var snapshot: LyricsShareSnapshot
    var scheme: CoverScheme
    var onClose: () -> Void
    @State private var image: UIImage?

    var body: some View {
        VStack(spacing: 20) {
            ScrollView {
                LyricsShareCard(snapshot: snapshot, scheme: scheme)
                    .padding(.top, 24)
                    .frame(maxWidth: .infinity)
            }
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
        .task {
            let r = ImageRenderer(content: LyricsShareCard(snapshot: snapshot, scheme: scheme))
            r.scale = 3
            image = r.uiImage
        }
    }
}
