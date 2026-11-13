import SwiftUI
import UIKit

/// Pointer hover (an iPad with a trackpad or mouse, the iPad app on a Mac): the M3 hovered state
/// layer, the content colour at 8 % over the control (Android gets it from the ripple indication of
/// every clickable), faded in and out on the effects spring. A finger never hovers, and an iPhone has
/// no pointer: nothing is attached there.
enum PointerHover {
    /// M3 StateLayer hovered opacity (Android's ripple hover alpha).
    static let layer: Double = 0.08

    /// iPad, and the iPad app on a Mac. Fixed for the run, so a branch on it never changes a view.
    @MainActor static let available = UIDevice.current.userInterfaceIdiom != .phone

    /// `-hoverPreview YES` (debug builds, iPad): every hover state shows as if a pointer were over it,
    /// for screenshots (the simulator's UI tests deliver no pointer hover).
    @MainActor static let preview: Bool = {
        #if DEBUG
        return available && UserDefaults.standard.bool(forKey: "hoverPreview")
        #else
        return false
        #endif
    }()

    /// The Mac's hover preview on a control's own pixels: a little darker in light, lighter in dark,
    /// about what the 8 % state layer does to a surface.
    static func macHoverBrightness(dark: Bool) -> Double { dark ? 0.08 : -0.05 }

    /// The state layer's opacity: hovered shows the layer; a press keeps the layer it already wears
    /// (a row's pressed tint is the same 8 %), so pressing under the pointer doesn't flicker.
    static func opacity(hovered: Bool, pressed: Bool = false, pressedLayer: Double = layer) -> Double {
        pressed ? pressedLayer : hovered ? layer : 0
    }
}

extension View {
    /// The hovered state layer in [shape], behind the content. [enabled] false (a selected row with
    /// its own fill, say) keeps the layer off without changing the view.
    func hoverLayer(in shape: some Shape, ink: Color = .primary, enabled: Bool = true) -> some View {
        modifier(HoverLayerModifier(shape: shape, ink: ink, enabled: enabled))
    }

    /// A tappable control says so under a pointer, as a preview of the press (owner 2026-11-13). On an
    /// iPad: the system's pointer interaction ([effect]: `.highlight` by default, the pointer morphing
    /// into the control's content shape behind it; `.lift` for what PressScale lifts). On a Mac the iPad
    /// app gets no pointer effects from the system, so the control's own pixels light up a step instead
    /// (`PointerHover.macHoverBrightness`, on the effects spring). Nothing for a finger or an iPhone.
    /// Controls that wear `hoverLayer` already don't need it.
    func pointerHighlight(_ effect: HoverEffect = .highlight) -> some View {
        modifier(PointerHighlightModifier(effect: effect))
    }

    /// [action] with true when a pointer enters this view and false when it leaves; never on an
    /// iPhone, never for a finger.
    func onPointerHover(perform action: @escaping (Bool) -> Void) -> some View {
        modifier(PointerHoverTrigger(action: action))
    }
}

private struct PointerHoverTrigger: ViewModifier {
    var action: (Bool) -> Void

    func body(content: Content) -> some View {
        if PointerHover.available {
            content.onHover(perform: action)
        } else {
            content
        }
    }
}

private struct PointerHighlightModifier: ViewModifier {
    var effect: HoverEffect
    @Environment(\.colorScheme) private var colorScheme
    @State private var hovered = false

    func body(content: Content) -> some View {
        if !PointerHover.available {
            content
        } else if Platform.current.isMac {
            let on = hovered || PointerHover.preview
            content
                .brightness(on ? PointerHover.macHoverBrightness(dark: colorScheme == .dark) : 0)
                .animation(Yoin.effects, value: on)
                .onHover { hovered = $0 }
        } else {
            content.hoverEffect(effect)
        }
    }
}

private struct HoverLayerModifier<S: Shape>: ViewModifier {
    var shape: S
    var ink: Color
    var enabled: Bool
    @State private var hovered = false

    func body(content: Content) -> some View {
        let on = (hovered || PointerHover.preview) && enabled
        content
            .background {
                shape.fill(ink.opacity(PointerHover.opacity(hovered: on)))
                    .animation(Yoin.effects, value: on)
                    .allowsHitTesting(false)
            }
            .onPointerHover { hovered = $0 }
    }
}

// MARK: - Column resize pointer

/// The split handle's pointer: a vertical beam held on the handle's line with an arrow either side,
/// iPadOS's way of saying "drag sideways" (SwiftUI's `pointerStyle(.columnResize)` is macOS and
/// visionOS only). [onHover] reports the pointer entering and leaving, for the handle's own state
/// layer (the system highlight can't run under this region).
///
/// Lay it over the handle. It takes hover events only: every touch and click passes through to the
/// handle's drag beneath, so the finger and the pointer's drag work as before.
struct ColumnResizePointer: UIViewRepresentable {
    var onHover: (Bool) -> Void

    func makeUIView(context: Context) -> ColumnResizePointerView {
        let view = ColumnResizePointerView()
        view.onHover = onHover
        return view
    }

    func updateUIView(_ view: ColumnResizePointerView, context: Context) {
        view.onHover = onHover
    }
}

final class ColumnResizePointerView: UIView, UIPointerInteractionDelegate {
    var onHover: ((Bool) -> Void)?
    /// The beam's length; the arrows sit beside it.
    static let beamLength: CGFloat = 24

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        addInteraction(UIPointerInteraction(delegate: self))
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    /// Only a pointer's hover lands here; touches (a finger, a click, the pointer's drag) go on to
    /// the view below.
    nonisolated static func takes(_ type: UIEvent.EventType?) -> Bool { type == .hover }

    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        guard Self.takes(event?.type), self.point(inside: point, with: event) else { return nil }
        return self
    }

    func pointerInteraction(_ interaction: UIPointerInteraction, regionFor request: UIPointerRegionRequest,
                            defaultRegion: UIPointerRegion) -> UIPointerRegion? {
        defaultRegion
    }

    func pointerInteraction(_ interaction: UIPointerInteraction, styleFor region: UIPointerRegion) -> UIPointerStyle? {
        let style = UIPointerStyle(shape: .verticalBeam(length: Self.beamLength),
                                   constrainedAxes: .horizontal)
        style.accessories = [UIPointerAccessory.arrow(.left), UIPointerAccessory.arrow(.right)]
        return style
    }

    func pointerInteraction(_ interaction: UIPointerInteraction, willEnter region: UIPointerRegion,
                            animator: any UIPointerInteractionAnimating) {
        onHover?(true)
    }

    func pointerInteraction(_ interaction: UIPointerInteraction, willExit region: UIPointerRegion,
                            animator: any UIPointerInteractionAnimating) {
        onHover?(false)
    }
}
