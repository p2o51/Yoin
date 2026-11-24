import SwiftUI
import UniformTypeIdentifiers

/// The clipboard card (Android OpenInCard): small, non-modal, at the bottom of the shell over the bar, on
/// glass like the shell's toasts. Three looks, kept visibly apart:
///
/// - **Paste** (iOS only): the clipboard probably holds a web link, not read yet. The system Paste button
///   reads it without the "pasted from" banner; nothing is read before the tap.
/// - **Hit** (an account on the link's service): cover, the service's name, title, artist, and Listen (a
///   song) or Open (anything else). It opens in that account.
/// - **Found** (no such account): text only, "title · artist", and Search: searched on the account in use.
///
/// Until the link's title is read the card says "<service> link in clipboard". It leaves on its own after
/// 12 s, as Android's does (not while VoiceOver runs), and hides while Now Playing covers the shell.
struct OpenInCardLayer: View {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme

    private var coordinator: OpenInCoordinator { model.openIn }

    private var app: CoverScheme {
        model.isIdle ? .brand(dark: colorScheme == .dark) : CoverScheme(album: model.playback.album, dark: colorScheme == .dark)
    }

    private var covered: Bool { model.landing != nil || (!model.isPad && model.npExpanded) || model.searchActive }

    var body: some View {
        ZStack(alignment: .bottom) {
            if let prompt = coordinator.prompt, !covered {
                OpenInCard(prompt: prompt, app: app, currentService: OpenInResultsModel.serviceName(model.store),
                           onAccept: { coordinator.accept() }, onPaste: { coordinator.pasted($0) },
                           onDismiss: { withAnimation(Yoin.spatial) { coordinator.dismiss() } })
                    .frame(maxWidth: 420)
                    .padding(.horizontal, 16)
                    .padding(.bottom, model.isPad ? 24 : 96)
                    .transition(.asymmetric(insertion: .move(edge: .bottom).combined(with: .scale(scale: 0.92)).combined(with: .opacity),
                                            removal: .move(edge: .bottom).combined(with: .opacity)))
                    .id(prompt.serial)
                    .task(id: prompt.serial) {
                        try? await Task.sleep(for: .seconds(12))
                        guard !Task.isCancelled, !UIAccessibility.isVoiceOverRunning,
                              coordinator.prompt?.serial == prompt.serial else { return }
                        withAnimation(Yoin.spatial) { coordinator.dismiss() }
                    }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
        .animation(Yoin.spatial, value: coordinator.prompt?.serial)
        .animation(Yoin.spatial, value: covered)
        .animation(Yoin.effects, value: coordinator.prompt)
    }
}

struct OpenInCard: View {
    var prompt: OpenInPrompt
    var app: CoverScheme
    /// The account in use's service: a found link's Search names it to VoiceOver ("Search on Subsonic").
    var currentService: String
    var onAccept: () -> Void
    var onPaste: (String) -> Void
    var onDismiss: () -> Void

    private var hit: Bool { prompt.target.isHit }

    var body: some View {
        HStack(spacing: 12) {
            if prompt.paste {
                // Compact: the glyph, the system's Paste and ✕ (no line of its own yet, see docs/open-in-yoin.md).
                linkMark
                pasteButton
            } else {
                if hit { cover } else { linkMark }
                lines
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentTransition(.opacity)
                acceptButton
            }
            Button(action: onDismiss) {
                YoinIcon("close", size: 20)
                    .foregroundStyle(app.onSurfaceVariant)
                    .frame(width: 40, height: 40)
                    .contentShape(.circle)
            }
            .buttonStyle(.plain).pointerHighlight()
            .accessibilityLabel(String(localized: "Close"))
        }
        .padding(.leading, 12)
        .padding(.trailing, 6)
        .padding(.vertical, 12)
        // Tinted nearly opaque: the card holds words over whatever the shell scrolls under it.
        .yGlassEffect(.regular.tint(app.surfaceContainerHigh.opacity(0.9)), in: .rect(cornerRadius: 28, style: .continuous),
                      solid: app.surfaceContainerHigh)
        .accessibilityElement(children: .contain)
        .sensoryFeedback(.impact(weight: .light), trigger: prompt.serial)
    }

    // MARK: Pieces

    private var serviceName: String { prompt.link?.serviceName ?? "" }

    private var title: String? { prompt.metadata?.title.flatMap { $0.isEmpty ? nil : $0 } }
    private var artist: String? { prompt.metadata?.artist.flatMap { $0.isEmpty ? nil : $0 } }
    private var fallback: String { String(localized: "\(serviceName) link in clipboard") }

    @ViewBuilder
    private var lines: some View {
        if hit {
            VStack(alignment: .leading, spacing: 2) {
                Text(serviceName)
                    .yoinRole(Tokens.type.labelMedium)
                    .foregroundStyle(app.primary)
                    .lineLimit(1)
                Text(title ?? fallback)
                    .yoinRole(Tokens.type.titleMedium)
                    .foregroundStyle(app.onSurface)
                    .lineLimit(1)
                if title != nil, let artist {
                    Text(artist)
                        .yoinRole(Tokens.type.bodyMedium)
                        .foregroundStyle(app.onSurfaceVariant)
                        .lineLimit(1)
                }
            }
        } else {
            // Found: text only, "title · artist".
            Text(title.map { t in artist.map { "\(t) · \($0)" } ?? t } ?? fallback)
                .yoinRole(Tokens.type.bodyLarge)
                .foregroundStyle(app.onSurface)
                .lineLimit(2)
        }
    }

    /// A hit's cover (the item on the user's own service); an artist's is round.
    private var cover: some View {
        let round = prompt.link?.kind == .artist
        let shape = round ? AnyShape(.circle) : AnyShape(.rect(cornerRadius: Tokens.shape.THUMB.cg, style: .continuous))
        return ZStack {
            shape.fill(app.surfaceContainerHighest)
            YoinIcon(glyph, size: 24).foregroundStyle(app.onSurfaceVariant)
            if let url = prompt.metadata?.artworkUrl {
                RemoteCover(ref: CoverRef(url: url), placeholder: .clear)
                    .transition(.opacity)
            }
        }
        .frame(width: 56, height: 56)
        .clipShape(shape)
        .accessibilityHidden(true)
    }

    private var glyph: String {
        switch prompt.link?.kind {
        case .artist: "artist"
        case .playlist: "playlist"
        case .track: "music_note"
        default: "album"
        }
    }

    /// A found link's lead (and the Paste card's): the link glyph, no cover.
    private var linkMark: some View {
        YoinIcon("link", size: 20)
            .foregroundStyle(app.onSurfaceVariant)
            .frame(width: 40, height: 40)
            .background(app.surfaceContainerHighest, in: .circle)
            .accessibilityHidden(true)
    }

    private var acceptButton: some View {
        Button(action: onAccept) {
            Text(!hit ? String(localized: "Search") : prompt.listens ? String(localized: "Listen") : String(localized: "Open"))
                .yoinRole(Tokens.type.labelLarge, weight: .semibold)
                .padding(.horizontal, 4)
        }
        .yGlassButtonStyle(prominent: true)
        .tint(app.primary)
        .pointerHighlight()
        .accessibilityLabel(hit ? Text(prompt.listens ? String(localized: "Listen") : String(localized: "Open"))
                                : Text(String(localized: "Search on \(currentService)")))
    }

    /// The system's Paste control: reads the clipboard on the user's tap, without the paste banner.
    private var pasteButton: some View {
        PasteButton(payloadType: PastedLink.self) { items in
            Task { @MainActor in
                if let text = items.first?.text { onPaste(text) } else { onDismiss() }
            }
        }
        .buttonBorderShape(.capsule)
        .labelStyle(.titleAndIcon)
        .tint(app.primary)
    }
}

/// What Paste hands over: a URL (a link copied from Spotify or Apple Music) or text (a chat), as text.
struct PastedLink: Transferable {
    var text: String

    static var transferRepresentation: some TransferRepresentation {
        ProxyRepresentation(importing: { (url: URL) in PastedLink(text: url.absoluteString) })
        ProxyRepresentation(importing: { (text: String) in PastedLink(text: text) })
    }
}
