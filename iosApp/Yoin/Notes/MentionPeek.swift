import SwiftUI
import UIKit
import YoinShared

/**
 * A long press on a mention (Android NoteMentionPress / MentionPeek, owner 2026-11-24): a small card — cover,
 * name, artist, album — while the finger stays down; it folds away on release and opens nothing. Only a press
 * that starts on a pill begins (anywhere else the host's own long press and taps act as before); once it has
 * begun, UIKit's exclusivity keeps the text's link and the host's tap from acting on the same touch.
 */
struct MentionLongPress: UIGestureRecognizerRepresentable {
    var enabled: Bool
    var hits: MentionHitBox
    /// The pill pressed and its rect in window coordinates; nil, nil when the press ends.
    var onChange: @MainActor (Int?, CGRect?) -> Void

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        var hits: MentionHitBox
        var converter: CoordinateSpaceConverter
        var enabled = true

        init(hits: MentionHitBox, converter: CoordinateSpaceConverter) {
            self.hits = hits
            self.converter = converter
        }

        func gestureRecognizerShouldBegin(_ g: UIGestureRecognizer) -> Bool {
            enabled && hits.hit(converter.localLocation) != nil
        }
    }

    func makeCoordinator(converter: CoordinateSpaceConverter) -> Coordinator {
        Coordinator(hits: hits, converter: converter)
    }

    func makeUIGestureRecognizer(context: Context) -> UILongPressGestureRecognizer {
        let g = UILongPressGestureRecognizer()
        g.minimumPressDuration = 0.4
        g.delegate = context.coordinator
        return g
    }

    func updateUIGestureRecognizer(_ g: UILongPressGestureRecognizer, context: Context) {
        context.coordinator.hits = hits
        context.coordinator.enabled = enabled
    }

    func handleUIGestureRecognizerAction(_ g: UILongPressGestureRecognizer, context: Context) {
        switch g.state {
        case .began:
            let local = context.converter.localLocation
            guard let hit = hits.hit(local) else { return onChange(nil, nil) }
            // The text's origin in the window: the finger in both spaces.
            let window = g.location(in: nil)
            let rect = hit.rect.offsetBy(dx: window.x - local.x, dy: window.y - local.y)
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            onChange(hit.ordinal, rect)
        case .ended, .cancelled, .failed:
            onChange(nil, nil)
        default:
            break
        }
    }
}

/// Shows the peek card over everything (sheets too) in a window of its own that takes no touches.
@MainActor
final class MentionPeekPresenter {
    static let shared = MentionPeekPresenter()

    private var window: UIWindow?
    private let model = MentionPeekModel()

    func show(_ mention: YoinNoteMention, cover: UIImage?, from rect: CGRect) {
        let window = window ?? makeWindow()
        guard let window else { return }
        window.isHidden = false
        withAnimation(Yoin.spatial) {
            model.peek = MentionPeekModel.Peek(title: mention.title, artist: mention.artist, album: mention.album,
                                               kind: MentionKindToken(mention.kind), cover: cover, anchor: rect)
        }
    }

    func hide() {
        withAnimation(Yoin.effects) { model.peek = nil }
    }

    private func makeWindow() -> UIWindow? {
        let scene = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive } ?? UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }.first
        guard let scene else { return nil }
        let w = UIWindow(windowScene: scene)
        w.windowLevel = .alert + 1
        w.isUserInteractionEnabled = false
        w.backgroundColor = .clear
        let host = UIHostingController(rootView: MentionPeekLayer(model: model))
        host.view.backgroundColor = .clear
        w.rootViewController = host
        window = w
        return w
    }
}

@MainActor
@Observable
final class MentionPeekModel {
    struct Peek: Equatable {
        var title: String
        var artist: String?
        var album: String?
        var kind: MentionKindToken
        var cover: UIImage?
        var anchor: CGRect
    }

    var peek: Peek?
}

/// The card, centred over the pill: above it when there is room, else below (Android MentionPeekPosition).
private struct MentionPeekLayer: View {
    var model: MentionPeekModel
    @State private var cardSize: CGSize = .zero

    var body: some View {
        GeometryReader { geo in
            if let peek = model.peek {
                let frame = geo.frame(in: .global)
                let anchor = peek.anchor.offsetBy(dx: -frame.minX, dy: -frame.minY)
                let margin: CGFloat = 12
                let x = min(max(anchor.midX - cardSize.width / 2, margin), max(margin, geo.size.width - cardSize.width - margin))
                let above = anchor.minY - cardSize.height - 8
                let y = above >= geo.safeAreaInsets.top + margin ? above : anchor.maxY + 8
                MentionPeekCard(peek: peek)
                    .onGeometryChange(for: CGSize.self) { $0.size } action: { cardSize = $0 }
                    .offset(x: x, y: y)
                    .transition(.scale(scale: 0.86, anchor: .bottom).combined(with: .opacity))
            }
        }
        .ignoresSafeArea()
    }
}

/// Android MentionPeekCard: 56 pt cover, title (2 lines), artist, album (muted).
struct MentionPeekCard: View {
    var peek: MentionPeekModel.Peek
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let model = AppModel.shared
        let s: CoverScheme = model.isIdle ? .brand(dark: colorScheme == .dark)
            : CoverScheme(album: model.playback.album, dark: colorScheme == .dark)
        HStack(spacing: 12) {
            Group {
                if let cover = peek.cover {
                    Image(uiImage: cover).resizable().scaledToFill()
                } else {
                    s.surfaceContainerHighest.overlay {
                        YoinIcon(peek.kind.symbol, size: 28).foregroundStyle(s.onSurfaceVariant)
                    }
                }
            }
            .frame(width: 56, height: 56)
            .clipShape(peek.kind == .artist ? AnyShape(Circle()) : AnyShape(.rect(cornerRadius: Tokens.shape.THUMB.cg)))
            VStack(alignment: .leading, spacing: 2) {
                Text(peek.title)
                    .yoinRole(Tokens.type.titleMedium)
                    .foregroundStyle(s.onSurface)
                    .lineLimit(2)
                if let artist = peek.artist, !artist.isEmpty {
                    Text(artist)
                        .yoinRole(Tokens.type.bodyMedium)
                        .foregroundStyle(s.onSurfaceVariant)
                        .lineLimit(1)
                }
                if let album = peek.album, !album.isEmpty {
                    Text(album)
                        .yoinRole(Tokens.type.bodySmall)
                        .foregroundStyle(s.onSurfaceVariant.opacity(0.72))
                        .lineLimit(1)
                }
            }
        }
        .padding(12)
        .frame(maxWidth: 280, alignment: .leading)
        .fixedSize(horizontal: true, vertical: false)
        .background(s.surfaceContainerHigh, in: .rect(cornerRadius: 20, style: .continuous))
        .shadow(color: .black.opacity(0.18), radius: 12, y: 4)
    }
}
