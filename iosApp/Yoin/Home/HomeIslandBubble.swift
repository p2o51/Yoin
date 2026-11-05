import SwiftUI
import UIKit

/// Where the screen's camera cutout is, for the Memories bubble to come out of it (51 2026-11-05:
/// "做成黑色的 从 dynamic island 出来（或者刘海），其他机型可以用现在这个方法（形状只要一个胶囊就行）").
/// Derived, not looked up per model: every Dynamic Island so far is 125.33 × 36.67 pt, centred,
/// its top 48 pt above the safe area's (iPhone 17 Pro 62 → 14, iPhone Air 68 → 20, 14 Pro 59 → 11,
/// measured on the simulators' screenshots); a notch hangs from the screen's top edge, ~162 pt
/// wide, as deep as the status bar's safe area less its 14 pt of air.
struct IslandCutout: Equatable {
    enum Kind { case island, notch }
    let kind: Kind
    let rect: CGRect

    static let islandSize = CGSize(width: 125.33, height: 36.67)
    static let islandAboveSafeTop: CGFloat = 48
    static let notchWidth: CGFloat = 162

    /// The cutout of a full-width phone page with this safe-area top; nil for an iPad, a split
    /// window, a landscape page or a phone without one.
    static func detect(pageWidth: CGFloat, fullWidth: Bool, safeTop: CGFloat) -> IslandCutout? {
        guard fullWidth, UIDevice.current.userInterfaceIdiom == .phone, pageWidth < 500 else { return nil }
        if safeTop >= 59 {
            let s = islandSize
            return IslandCutout(kind: .island, rect: CGRect(x: (pageWidth - s.width) / 2, y: safeTop - islandAboveSafeTop,
                                                            width: s.width, height: s.height))
        }
        if safeTop >= 40 {
            let h = max(26, safeTop - 14)
            return IslandCutout(kind: .notch, rect: CGRect(x: (pageWidth - notchWidth) / 2, y: 0, width: notchWidth, height: h))
        }
        return nil
    }

    /// The grown window the bubble speaks from: the cutout's own top, centred, wide enough
    /// for the cover and the album, tall enough for two lines.
    func grown(pageWidth: CGFloat) -> CGRect {
        let w = min(pageWidth - 24, 340)
        let top = kind == .island ? rect.minY : 0
        // The cover and album sit under the camera, clear of it.
        let h = contentTop - top + Self.contentHeight + 10
        return CGRect(x: (pageWidth - w) / 2, y: top, width: w, height: h)
    }

    /// The speaking line's band: under the island (or the notch), never beside the camera.
    static let contentHeight: CGFloat = 56
    var contentTop: CGFloat { rect.maxY + 6 }

    /// The shape at [t] of the morph (0 = the cutout, 1 = the window): an island stays a capsule
    /// growing into a rounded rectangle; a notch stays square to the screen's edge.
    func shape(at t: CGFloat, pageWidth: CGFloat) -> (rect: CGRect, top: CGFloat, bottom: CGFloat) {
        let g = grown(pageWidth: pageWidth)
        let r = CGRect(x: lerpf(rect.minX, g.minX, t), y: lerpf(rect.minY, g.minY, t),
                       width: lerpf(rect.width, g.width, t), height: lerpf(rect.height, g.height, t))
        switch kind {
        case .island:
            let radius = lerpf(rect.height / 2, 30, t)
            return (r, radius, radius)
        case .notch:
            return (r, 0, lerpf(rect.height * 0.6, 30, t))
        }
    }
}

/// The bubble as it speaks out of the island: one black surface, the island's own black, grown
/// out of it on the bubble's unroll and taken back on its flight home; the cover and album
/// come in once it has room and leave before it shrinks. Light on black.
struct IslandBubbleLayer<Line: View, Mark: View>: View {
    var cutout: IslandCutout
    /// The morph: the unroll, less the flight home.
    var t: CGFloat
    var size: CGSize
    var reduceMotion: Bool
    @ViewBuilder var line: (CGFloat) -> Line
    @ViewBuilder var mark: () -> Mark

    var body: some View {
        let grown = cutout.grown(pageWidth: size.width)
        let shapeT = reduceMotion ? 1 : t
        let s = cutout.shape(at: shapeT, pageWidth: size.width)
        let content = smoothstep(0.62, 0.95, t)
        let markSize: CGFloat = 40
        let inset: CGFloat = 18
        let lineTop = cutout.contentTop
        let lineHeight = IslandCutout.contentHeight
        ZStack(alignment: .topLeading) {
            UnevenRoundedRectangle(topLeadingRadius: s.top, bottomLeadingRadius: s.bottom,
                                   bottomTrailingRadius: s.bottom, topTrailingRadius: s.top, style: .continuous)
                .fill(.black)
                .frame(width: s.rect.width, height: s.rect.height)
                .offset(x: s.rect.minX, y: s.rect.minY)
                // Reduce Motion: no morph, the grown window fades in and out where it stands.
                .opacity(reduceMotion ? Double(smoothstep(0, 0.4, t)) : 1)
            HStack(spacing: 12) {
                mark().frame(width: markSize, height: markSize)
                line(max(0, grown.width - inset * 2 - markSize - 12))
            }
            .padding(.horizontal, inset)
            .frame(width: grown.width, height: lineHeight, alignment: .leading)
            .offset(x: grown.minX, y: lineTop)
            .opacity(Double(content))
            .blur(radius: (1 - content) * 6)
        }
        .frame(width: size.width, height: size.height, alignment: .topLeading)
        .allowsHitTesting(false)
    }
}

/// Home steps down as the island grows over it, and back as it goes home (its own modifier, so a
/// frame of the morph re-renders this offset only).
struct IslandPush: ViewModifier {
    @Environment(AppModel.self) private var model
    var bubble: MemoryBubbleController

    func body(content: Content) -> some View {
        content
            .offset(y: bubble.islandPush)
            // The grown window covers the status bar's corners: its clock and battery step aside
            // while it is out, as the system's own island activities do.
            .statusBarHidden(bubble.islandT > 0.02)
            .animation(Yoin.effects, value: bubble.islandT > 0.02)
            .onChange(of: bubble.islandT > 0.02) { _, out in model.islandOut = out }
    }
}
