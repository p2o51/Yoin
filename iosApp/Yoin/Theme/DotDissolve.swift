import SwiftUI

/// What leaves the stage breaks up into fine dots as it goes, instead of
/// fading (Android ui/nowplaying/DotDissolve.kt, owner 2026-11-02; used by the
/// album butterfly, the artist ring and the playlist sleeves, 2026-11-06).
///
/// The content is masked by a grid of dots 5 pt apart, 3.3 pt across at most,
/// that shrink to nothing as `progress` runs 0 → ~0.74; the last 10 % also
/// fades the view, for content the dots never fully clear. At 0 nothing is
/// masked and no shader runs.
///
/// The shader only sees the view's own frame: give the view a frame that
/// holds everything it draws, or the overflow is cut.
struct DotDissolve: ViewModifier {
    var progress: CGFloat

    static let cell: CGFloat = 5
    static let maxRadius: CGFloat = 3.3

    /// The dots' radius at `progress` (0 = whole, nil = no mask yet).
    static func radius(_ progress: CGFloat) -> CGFloat? {
        guard progress > 0.001 else { return nil }
        let shrink = 1 - smoothstep(min(1, progress * 1.35))
        // Android's 20 radius steps: the first one keeps the content whole.
        return shrink >= 0.975 ? nil : maxRadius * shrink
    }

    /// The view's opacity: the last 10 % of the progress fades it out.
    static func opacity(_ progress: CGFloat) -> CGFloat {
        1 - smoothstep(min(1, max(0, (progress - 0.9) / 0.1)))
    }

    func body(content: Content) -> some View {
        let r = Self.radius(progress)
        content
            .colorEffect(ShaderLibrary.yoinDotDissolve(.float(Self.cell), .float(r ?? Self.maxRadius)),
                         isEnabled: r != nil)
            .opacity(r == 0 ? 0 : Self.opacity(progress))
    }

    private static func smoothstep(_ x: CGFloat) -> CGFloat { x * x * (3 - 2 * x) }
}

extension View {
    /// Breaks the view up into fine dots as `progress` runs 0 → 1 (see DotDissolve).
    func dotDissolve(_ progress: CGFloat) -> some View { modifier(DotDissolve(progress: progress)) }
}
