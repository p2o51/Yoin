import SwiftUI
import UIKit

/// Memories, the shell-owned upward overlay (AGENTS.md ShellOverlayUp),
/// ported from Android ui/memories (MemoriesScreen, showcase/MemoryCardFace,
/// MemoriesTopBar, MemoriesAuroraBackground), card state only.
///
/// Back surface: it retreats upward to Home. q (MemoriesPresentation) is the
/// one displacement: the drag up on the page, the ⌃ Home pill, the bar's
/// Home tap and VoiceOver's escape all move it; the page reads it as its
/// offset (−q·H), Home behind reads it as its scale / dim. One drag router on
/// the page decides the axis on its first movement: sideways turns the deck
/// (its own pager position, the aurora's drift), upward feeds q.
struct MemoriesView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme
    var presentation: MemoriesPresentation

    @State private var cards: [MemoryCard] = []
    /// The deck's continuous page (0 = first card).
    @State private var position: CGFloat = 0
    @State private var axis: Axis?
    @State private var dragStartPosition: CGFloat = 0
    @State private var pager = SpringRunner()
    @State private var width: CGFloat = 390

    private var scheme: CoverScheme { CoverScheme(seed: model.accent, dark: colorScheme == .dark) }

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width
            ZStack(alignment: .top) {
                MemoriesAurora(palettes: cards.map(\.album.palette), position: position, dark: colorScheme == .dark)
                    .background(scheme.background)
                    .ignoresSafeArea()
                if cards.isEmpty {
                    Text("No memories yet")
                        .homeType(Tokens.type.headlineSmall)
                        .foregroundStyle(scheme.onSurface)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    ForEach(visibleIndices, id: \.self) { i in
                        MemoryCardFace(card: cards[i], scheme: scheme, dark: colorScheme == .dark,
                                       height: geo.size.height) {
                            model.open(cards[i].album)
                        }
                        .frame(width: w)
                        // Neighbours slide past at the deck's pace; only ±1 is drawn.
                        .offset(x: (CGFloat(i) - position) * w)
                        .accessibilityHidden(i != currentIndex)
                    }
                }
                MemoriesTopBar(dots: cards.map { MemoryTones(palette: $0.album.palette, dark: colorScheme == .dark).dot },
                               position: position, scheme: scheme,
                               onHome: { presentation.close() },
                               onDot: { turn(to: $0, velocity: 0) })
            }
            .contentShape(.rect)
            .gesture(router(width: w))
            .onAppear { width = w }
            .onChange(of: w) { _, new in width = new }
        }
        .onAppear {
            rebuild()
            #if DEBUG
            MemoriesScript.swipe = { _ in
                let start = position
                Task { @MainActor in
                    await MemoriesScript.finger(0.3) { position = start + $0 * 0.4 }
                    turn(to: MemoryDeck.settlePage(position: position, velocity: 1.5, count: cards.count), velocity: 1.5)
                }
            }
            #endif
        }
        .onChange(of: presentation.openCount) { _, _ in rebuild() }
        // VoiceOver's two-finger scrub is iOS's back for an overlay: the same retreat.
        .accessibilityAction(.escape) { presentation.close() }
        .accessibilityAddTraits(.isModal)
    }

    private var currentIndex: Int { min(max(Int(position.rounded()), 0), max(cards.count - 1, 0)) }

    private var visibleIndices: [Int] {
        guard !cards.isEmpty else { return [] }
        let lo = max(0, Int(position.rounded(.down)) - 1), hi = min(cards.count - 1, Int(position.rounded(.up)) + 1)
        return lo <= hi ? Array(lo...hi) : []
    }

    /// The deck for this open: the pool rebuilt every time, stopped on the focus.
    private func rebuild() {
        cards = MemoryDeck.cards(model.store.memoryDeckInputs())
        pager.stop()
        position = CGFloat(MemoryDeck.startIndex(cards, focus: presentation.focusAlbumId))
    }

    private func turn(to page: Int, velocity: CGFloat) {
        pager.run(from: position, to: CGFloat(page), velocity: velocity, spring: .yoin(Tokens.motion.expressiveDefaultSpatial)) { v in
            position = v
        }
    }

    /// The page's one drag: its first movement picks the axis.
    private func router(width w: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 10)
            .onChanged { v in
                if axis == nil {
                    axis = abs(v.translation.width) > abs(v.translation.height) ? .horizontal : .vertical
                    dragStartPosition = position
                    pager.stop()
                }
                switch axis {
                case .horizontal:
                    // 1:1, soft past either end.
                    let raw = dragStartPosition - v.translation.width / max(w, 1)
                    let last = CGFloat(max(cards.count - 1, 0))
                    position = raw < 0 ? -rubber(-raw) : raw > last ? last + rubber(raw - last) : raw
                case .vertical:
                    presentation.dismissDrag(-v.translation.height)
                case nil: break
                }
            }
            .onEnded { v in
                defer { axis = nil }
                switch axis {
                case .horizontal:
                    let velocity = -v.velocity.width / max(w, 1)
                    turn(to: MemoryDeck.settlePage(position: position, velocity: velocity, count: cards.count), velocity: velocity)
                case .vertical:
                    presentation.dismissEnded(velocity: -v.velocity.height)
                case nil: break
                }
            }
    }

    private func rubber(_ over: CGFloat) -> CGFloat { 0.3 * (1 - 1 / (1 + over / 0.3)) }
}

// MARK: - Tones (Android MemoryPalette)

/// One album's card tones (Android `MemoryPalette.fromBackdrop(...).tones(dark)`):
/// a direct sRGB lerp of the backdrop base, held in the ink lightness window.
struct MemoryTones {
    var ink: Color
    var button: Color
    var onButton: Color
    var dot: Color

    init(palette: CoverPalette, dark: Bool) {
        let base = Self.hold(UIColor(BackdropColors(palette).base), min: 0.22, max: 0.46)
        let deep = Self.mix(base, .black, 0.55), soft = Self.mix(base, .white, 0.8)
        if dark {
            let lift = Self.mix(base, soft, 0.6)
            ink = Color(lift); button = Color(lift); onButton = Color(deep); dot = Color(lift)
        } else {
            ink = Color(base); button = Color(base); onButton = .white; dot = Color(base)
        }
    }

    static func rgb(_ c: UIColor) -> (CGFloat, CGFloat, CGFloat) {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        c.getRed(&r, green: &g, blue: &b, alpha: &a)
        return (r, g, b)
    }

    static func mix(_ a: UIColor, _ b: UIColor, _ t: CGFloat) -> UIColor {
        let x = rgb(a), y = rgb(b)
        return UIColor(red: x.0 + (y.0 - x.0) * t, green: x.1 + (y.1 - x.1) * t, blue: x.2 + (y.2 - x.2) * t, alpha: 1)
    }

    /// `holdLightness`: moved straight toward black or white into [min, max] (HSL L).
    static func hold(_ c: UIColor, min lo: CGFloat, max hi: CGFloat) -> UIColor {
        let (r, g, b) = rgb(c)
        let l = (Swift.max(r, g, b) + Swift.min(r, g, b)) / 2
        if l > hi { return mix(c, .black, 1 - hi / l) }
        if l < lo { return mix(c, .white, (lo - l) / (1 - l)) }
        return c
    }
}

// MARK: - The card

/// The card state of one memory (Android MemoryCardFace, phone tier): the
/// exhibit at a fixed height — air, the cover with the groove emblem pinned to
/// its lower-right corner, the title, the album row — and the teaser anchored
/// to the bottom: the excerpt, then Go to album. Flat, no cover border.
private struct MemoryCardFace: View {
    var card: MemoryCard
    var scheme: CoverScheme
    var dark: Bool
    var height: CGFloat
    var openAlbum: () -> Void

    /// MemoriesLayoutTokens: phone, and the short (< 760) rhythm.
    private var short: Bool { height < 760 }
    private var cover: CGFloat { short ? 168 : 256 }
    private var seal: CGFloat { short ? 72 : 96 }
    private var air1: CGFloat { short ? 24 : 59 }

    var body: some View {
        let tones = MemoryTones(palette: card.album.palette, dark: dark)
        VStack(spacing: 0) {
            Color.clear.frame(height: MemoriesTopBar.height + air1)
            exhibit(tones)
            Spacer(minLength: 24)
            if let e = card.excerpt { excerpt(e, tones).padding(.bottom, 16) }
            Button(action: openAlbum) {
                Text("Go to album")
                    .homeType(Tokens.type.labelLarge, .semibold)
                    .foregroundStyle(tones.onButton)
                    .padding(.horizontal, 24)
                    .frame(height: 48)
                    .background(tones.button, in: .capsule)
            }
            .buttonStyle(PressScale())
            .padding(.bottom, short ? 28 : 40)
        }
        .padding(.horizontal, 28)
        .frame(maxWidth: .infinity)
    }

    private func exhibit(_ tones: MemoryTones) -> some View {
        VStack(spacing: 0) {
            CoverArt(palette: card.album.palette, cornerRadius: Tokens.shape.HERO.cg, seed: card.album.seed)
                .frame(width: cover, height: cover)
                .overlay(alignment: .topLeading) {
                    // The emblem hangs off the lower-right corner by 0.3 / 0.24 of its size.
                    ScoreEmblem(score: card.score ?? 0, kind: card.emblemKind, rated: card.rated,
                                palette: card.album.palette, size: seal, neutrals: scheme, onCover: true)
                        .offset(x: cover - seal * 0.7, y: cover - seal * 0.76)
                }
                .padding(.bottom, seal * 0.24)
            Text(card.title)
                .font(card.titleKind == .album ? .gsf(24, .semibold) : .system(size: 26, weight: .semibold, design: .serif))
                .lineSpacing(card.titleKind == .album ? 24 * 0.3 - 6 : 26 * 0.36 - 7)
                .multilineTextAlignment(.center)
                .lineLimit(card.titleKind == .album ? 2 : 3)
                .foregroundStyle(scheme.onSurface)
                .frame(maxWidth: 340)
                .padding(.top, short ? 22 : 30)
            VStack(spacing: 2) {
                if card.titleKind != .album {
                    Text(card.album.title)
                        .font(.gsf(17, .semibold))
                        .foregroundStyle(tones.ink)
                        .lineLimit(2)
                        .multilineTextAlignment(.center)
                }
                HStack(spacing: Tokens.metaLine.GROUP_GAP.cg) {
                    if !card.album.artist.isEmpty { Text(card.album.artist) }
                    if card.album.year > 0 { Text(String(card.album.year)).monospacedDigit() }
                }
                .font(.gsf(13, .medium))
                .foregroundStyle(scheme.onSurfaceVariant)
                .lineLimit(1)
            }
            .padding(.top, card.titleKind == .album ? 6 : (short ? 16 : 20))
            .frame(maxWidth: 340)
        }
    }

    private func excerpt(_ e: MemoryCard.Excerpt, _ tones: MemoryTones) -> some View {
        VStack(spacing: 6) {
            Text("“\(e.text)”")
                .font(.gsf(e.text.count < 60 ? 22 : 17, e.text.count < 60 ? .medium : .regular))
                .lineSpacing(e.text.count < 60 ? 22 * 0.4 - 6 : 17 * 0.65 - 5)
                .multilineTextAlignment(.center)
                .foregroundStyle(scheme.onSurface)
                .lineLimit(4)
                .frame(maxWidth: 324)
            HStack(spacing: 8) {
                Text(e.kind == .review ? "Your review" : "Your note")
                    .homeType(Tokens.type.labelSmall)
                    .foregroundStyle(tones.ink)
                if let d = e.detail {
                    Text(d).font(.gsf(12, .medium)).monospacedDigit().foregroundStyle(scheme.onSurface.opacity(0.6))
                }
            }
            .lineLimit(1)
        }
    }
}

// MARK: - Top bar

/// The bar (Android MemoriesTopBar): the "⌃ Home" pill and the page dots,
/// each dot a tap target, the current one larger.
private struct MemoriesTopBar: View {
    static let height: CGFloat = 64
    var dots: [Color]
    var position: CGFloat
    var scheme: CoverScheme
    var onHome: () -> Void
    var onDot: (Int) -> Void

    var body: some View {
        HStack(spacing: 0) {
            Button(action: onHome) {
                HStack(spacing: 4) {
                    YoinIcon("chevron_up", size: 18)
                    Text("Home").homeType(Tokens.type.labelLarge, .semibold)
                }
                .foregroundStyle(scheme.onSurface)
                .frame(width: 88, height: 36)
                .background(scheme.surfaceContainerHigh, in: .capsule)
                .frame(height: 48)
                .contentShape(.rect)
            }
            .buttonStyle(PressScale())
            .accessibilityLabel("Close Memories, back to Home")
            Spacer(minLength: 12)
            HStack(spacing: 0) {
                ForEach(Array(dots.enumerated()), id: \.offset) { i, color in
                    // 7 pt, the current one 12, by how near the deck is to it.
                    let near = max(0, 1 - abs(CGFloat(i) - position))
                    Circle()
                        .fill(color.opacity(0.35 + 0.65 * near))
                        .frame(width: 7 + 5 * near, height: 7 + 5 * near)
                        .frame(width: 18, height: 48)
                        .contentShape(.rect)
                        .onTapGesture { onDot(i) }
                        .accessibilityLabel("Memory \(i + 1)")
                        .accessibilityAddTraits(.isButton)
                }
            }
        }
        .padding(.leading, 16)
        .padding(.trailing, 12)
        .frame(height: Self.height)
    }
}

// MARK: - Aurora

/// Android memoriesAuroraBackground: three soft radial blooms in the current
/// memory's palette, turned by the deck's position (not the clock): a swipe
/// re-tints and moves the field, a deck at rest draws nothing new.
private struct MemoriesAurora: View {
    var palettes: [CoverPalette]
    var position: CGFloat
    var dark: Bool

    var body: some View {
        Canvas { gc, size in
            guard !palettes.isEmpty, size.width > 0 else { return }
            let at = min(max(position, 0), CGFloat(palettes.count - 1))
            let from = Int(at.rounded(.down)), to = min(from + 1, palettes.count - 1)
            let f = at - CGFloat(from)
            let a = BackdropColors(palettes[from]), b = BackdropColors(palettes[to])
            let base = a.base.mix(with: b.base, by: f), accent = a.accent.mix(with: b.accent, by: f)
            let blooms = [base, accent, base.mix(with: accent, by: 0.5)]
            let w = size.width, h = size.height, maxDim = max(w, h)
            let cap: CGFloat = dark ? 0.26 : 0.2
            let flow = position * 0.18
            let breath: CGFloat = 0.5 + 0.5 * CGFloat(sin(Double(position) * 1.9))
            let anchors = [CGPoint(x: w * 0.24, y: h * 0.22), CGPoint(x: w * 0.78, y: h * 0.44), CGPoint(x: w * 0.34, y: h * 0.80)]
            if dark { gc.blendMode = .screen }
            for i in 0..<3 {
                let angle = 2 * .pi * flow * (0.6 + 0.4 * CGFloat(i)) + CGFloat(i) * 2 * .pi / 3
                let center = CGPoint(x: anchors[i].x + cos(angle) * w * 0.2, y: anchors[i].y + sin(angle * 1.3) * h * 0.16)
                let wobble = sin(2 * .pi * breath + CGFloat(i))
                let depth = 1 - CGFloat(i) * 0.18
                let color = blooms[i].mix(with: blooms[(i + 1) % 3], by: Double(0.5 + 0.5 * sin(2 * CGFloat.pi * flow + CGFloat(i))))
                let radius = maxDim * (0.5 + 0.12 * wobble) * depth
                let alpha = cap * (0.8 + 0.2 * wobble) * (0.72 + 0.28 * depth)
                let rect = CGRect(x: center.x - radius, y: center.y - radius, width: radius * 2, height: radius * 2)
                gc.fill(Path(ellipseIn: rect), with: .radialGradient(
                    Gradient(colors: [color.opacity(alpha), color.opacity(0)]),
                    center: center, startRadius: 0, endRadius: radius))
            }
        }
        .allowsHitTesting(false)
    }
}
