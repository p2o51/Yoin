import SwiftUI

/// Every page's top, the album page's way (51, 2026-11-14: the top's gradient blur wasn't on every page; line
/// them all up with the album page). The album's strong progressive blur (`ProgressiveEdgeBlur(strong: true)`)
/// in the colour the page's background starts with, from the very top of the screen through the page's header
/// and a little past it: what scrolls up goes soft under it, with no hard edge, and over the page at rest it
/// reads as the page's own colour.
struct TopEdgeBlur: View {
    var tint: Color

    /// How far past the header's foot it runs out (the album's `headerContent + 4`).
    static let overhang: CGFloat = 4

    var body: some View {
        ProgressiveEdgeBlur(tint: tint, strong: true)
            .padding(.bottom, -Self.overhang)
            .ignoresSafeArea(edges: .top)
    }
}

extension View {
    /// On the content of a page's top safe-area bar: the album's top behind it, up through the status bar and
    /// `TopEdgeBlur.overhang` past the bar's foot. Off ([on] false) on a tip's demo phone, where a material
    /// would blur at the window's scale, over the phone's rim and the page around it.
    func topEdgeBlur(_ tint: Color, on: Bool = true) -> some View {
        background(alignment: .top) {
            if on { TopEdgeBlur(tint: tint) }
        }
    }
}
