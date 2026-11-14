import SwiftUI
import UIKit

// Solid surfaces (prototype, 2026-11-14): one switch that takes every Liquid
// Glass surface in the app back to Android's tonal, opaque Material fills.
//
// Every `glassEffect` in the app goes through `yGlassEffect`. With the switch
// off it is exactly `glassEffect`; on, the shape is filled with a solid colour
// instead: the call site's `solid:` colour if it gave one (tinted controls such
// as PAUSE or the selected tab), else the environment's `solidChromeFill`
// (surfaceContainerHigh, Android's default for floating chrome).

extension GlassLab {
    /// `-solidSurfaces YES` (launch argument) or Settings › Labs.
    static var solidSurfaces: Bool { shared.solidSurfaces }
}

extension GlassLab {
    /// With solid surfaces on, the app's own appearance: Liquid Glass flips
    /// the colour scheme of what sits in it over dark content, and a solid
    /// bar must not follow that. Off: the environment's, untouched.
    static func solidColorScheme(_ env: ColorScheme) -> ColorScheme {
        guard solidSurfaces else { return env }
        switch shared.appearance {
        case 1: return .light
        case 2: return .dark
        default:
            let style = (UIApplication.shared.connectedScenes.first as? UIWindowScene)?
                .traitCollection.userInterfaceStyle
            return style == .dark ? .dark : .light
        }
    }
}

extension EnvironmentValues {
    @Entry var solidChromeFill: Color = Yoin.surfaceContainerHigh
}

extension View {
    /// `glassEffect`, or a solid tonal fill when solid surfaces are on.
    /// `enabled: false` = `.identity` glass (and no fill).
    func yGlassEffect<S: Shape>(_ glass: Glass = .regular, in shape: S, solid: Color? = nil,
                                enabled: Bool = true) -> some View {
        modifier(YGlassEffect(glass: glass, shape: shape, solid: solid, enabled: enabled))
    }

    /// The same, with the solid colour named first (tinted call sites).
    func yGlassEffect<S: Shape>(_ glass: Glass, solid: Color, in shape: S) -> some View {
        yGlassEffect(glass, in: shape, solid: solid)
    }

    /// `glassEffect` with the default capsule shape.
    func yGlassEffect(_ glass: Glass = .regular, solid: Color? = nil) -> some View {
        yGlassEffect(glass, in: Capsule(), solid: solid)
    }

    /// `.buttonStyle(.glass)` / `.glassProminent`, or Material's tonal /
    /// filled button when solid surfaces are on.
    @ViewBuilder
    func yGlassButtonStyle(prominent: Bool = false) -> some View {
        if GlassLab.solidSurfaces {
            if prominent { buttonStyle(.borderedProminent) } else { buttonStyle(.bordered) }
        } else {
            if prominent { buttonStyle(.glassProminent) } else { buttonStyle(.glass) }
        }
    }

    /// A blur material, or the solid fill when solid surfaces are on.
    func ySolidOrMaterial<S: Shape>(_ material: Material, in shape: S) -> some View {
        modifier(YSolidOrMaterial(material: material, shape: shape))
    }
}

private struct YGlassEffect<S: Shape>: ViewModifier {
    let glass: Glass
    let shape: S
    let solid: Color?
    let enabled: Bool
    @Environment(\.solidChromeFill) private var fill

    func body(content: Content) -> some View {
        if GlassLab.solidSurfaces {
            content.background {
                shape.fill(solid ?? fill).opacity(enabled ? 1 : 0)
            }
        } else {
            content.glassEffect(enabled ? glass : .identity, in: shape)
        }
    }
}

private struct YSolidOrMaterial<S: Shape>: ViewModifier {
    let material: Material
    let shape: S
    @Environment(\.solidChromeFill) private var fill

    func body(content: Content) -> some View {
        if GlassLab.solidSurfaces {
            content.background(shape.fill(fill))
        } else {
            content.background(shape.fill(material))
        }
    }
}
