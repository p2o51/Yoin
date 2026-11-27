import SwiftUI

/// iPad's bottom band (owner 2026-11-27, iPad round 2, option D1): a soft band at the foot of each column, the
/// status bar's band (StatusEdgeBand) mirrored, so the floating dock, the mini player and a detail column's Play
/// split sit on a quiet edge and the list fades out before it runs under their glass instead of fighting it. The
/// "two corners" dock (51 2026-11-11) stays as it is. Under the Play split (it is laid in the column before the
/// split), never over it; it steps away with the dock when Home's scroll hides the dock.
struct PadDockBand: View {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme

    /// From the window's bottom edge: the dock's glass and its gap, and a falloff above them.
    static let height: CGFloat = PadDockMetrics.bottom + PadDockMetrics.height + 44
    /// How much of it, from the foot up, is solid before it eases to clear at its top.
    static let solid: Double = 0.45

    var body: some View {
        ZStack {
            Rectangle().fill(.ultraThinMaterial)
            Rectangle().fill(Yoin.surface.opacity(colorScheme == .dark ? 0.5 : 0.42))
        }
        // Solid at the foot, easing to clear at the top (the top band's falloff, upside down).
        .mask {
            LinearGradient(stops: ProgressiveEdgeBlur.easedFalloff(from: Self.solid),
                           startPoint: .bottom, endPoint: .top)
        }
        .frame(height: Self.height)
        .frame(maxHeight: .infinity, alignment: .bottom)
        .opacity(1 - Double(min(max(model.barScrollHide.fraction, 0), 1)))
        .ignoresSafeArea(.container, edges: .bottom)
        .ignoresSafeArea(.keyboard)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}
