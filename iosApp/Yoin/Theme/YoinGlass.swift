import SwiftUI

/// Which glass the floating chrome uses. `.system` is plain Liquid Glass;
/// `.yoin` is the experimental "gummy" glass: tinted, grainy, soft bright rim,
/// colored glow (Google's take on glass).
enum GlassFlavor: String, Sendable {
    case system
    case yoin
}

private struct GlassFlavorKey: EnvironmentKey {
    static let defaultValue: GlassFlavor = .system
}

extension EnvironmentValues {
    var glassFlavor: GlassFlavor {
        get { self[GlassFlavorKey.self] }
        set { self[GlassFlavorKey.self] = newValue }
    }
}

extension View {
    /// Floating-control glass. `prominent` = primary action, tinted with the cover.
    func yoinGlass<S: InsettableShape>(
        _ shape: S,
        tint: Color,
        prominent: Bool = false,
        flavor: GlassFlavor,
        interactive: Bool = true
    ) -> some View {
        modifier(YoinGlassModifier(shape: shape, tint: tint, prominent: prominent, flavor: flavor, interactive: interactive))
    }
}

private struct YoinGlassModifier<S: InsettableShape>: ViewModifier {
    var shape: S
    var tint: Color
    var prominent: Bool
    var flavor: GlassFlavor
    var interactive: Bool

    private var glass: Glass {
        var g: Glass = .regular
        switch (flavor, prominent) {
        case (.system, true): g = g.tint(tint)
        case (.system, false): g = .regular
        case (.yoin, true): g = g.tint(tint)
        case (.yoin, false): g = g.tint(tint.opacity(0.32))
        }
        return interactive ? g.interactive() : g
    }

    func body(content: Content) -> some View {
        switch flavor {
        case .system:
            content.yGlassEffect(glass, in: shape)
        case .yoin:
            content
                .yGlassEffect(glass, in: shape)
                .overlay {
                    // Grain: a tiny Metal noise shader, overlay-blended.
                    Rectangle()
                        .fill(.white)
                        .colorEffect(ShaderLibrary.yoinGrain(.float(0.55)))
                        .blendMode(.overlay)
                        .opacity(0.38)
                        .clipShape(shape)
                        .allowsHitTesting(false)
                }
                .overlay {
                    // Soft bright rim, brightest on the top edge.
                    shape
                        .strokeBorder(
                            LinearGradient(
                                colors: [.white.opacity(0.75), .white.opacity(0.08), tint.opacity(0.35)],
                                startPoint: .top, endPoint: .bottom
                            ),
                            lineWidth: 1.4
                        )
                        .blur(radius: 0.4)
                        .allowsHitTesting(false)
                }
                .background {
                    // Colored glow under the gummy glass.
                    shape.fill(tint.opacity(0.45)).blur(radius: 18).offset(y: 6)
                        .allowsHitTesting(false)
                }
        }
    }
}
