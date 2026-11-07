import SwiftUI

/// One line that scrolls when it doesn't fit (Compose basicMarquee, as Android's Now Playing pill sets
/// it): still for [initialDelay], then it travels left at [velocity] with a copy following a third of
/// the box behind, rests [repeatDelay] each time the copy arrives, and goes on — forever. Text that fits
/// sits still, and so does everything under Reduce Motion (cut at the end instead).
struct MarqueeText: View {
    var text: String
    var font: Font
    var color: Color
    var velocity: CGFloat = 30
    var initialDelay: Double = 2.5
    var repeatDelay: Double = 2
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var box: CGFloat = 0
    @State private var natural: CGFloat = 0
    @State private var start = Date()

    var body: some View {
        let overflow = natural > box + 0.5 && box > 0
        // Sized as a one-line Text is (its own width, no more than the room), so a frame read from it is
        // the words' (Now Playing's title morph); the full width is measured behind it.
        line
            .lineLimit(1)
            .hidden()
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { box = $0 }
            .background(alignment: .leading) {
                line.lineLimit(1).fixedSize().hidden()
                    .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { natural = $0 }
            }
            .overlay(alignment: .leading) {
                if overflow && !reduceMotion {
                    TimelineView(.animation(minimumInterval: 1 / 60)) { ctx in
                        let x = Self.offset(at: ctx.date.timeIntervalSince(start), natural: natural, box: box,
                                            velocity: velocity, initialDelay: initialDelay, repeatDelay: repeatDelay)
                        HStack(spacing: Self.spacing(box)) { line.fixedSize(); line.fixedSize() }
                            .offset(x: x)
                    }
                    .frame(width: box, alignment: .leading)
                    .clipped()
                } else {
                    line.lineLimit(1).frame(width: box, alignment: .leading)
                }
            }
            .onChange(of: text) { _, _ in start = Date() }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(text)
    }

    private var line: some View { Text(text).font(font).foregroundStyle(color) }

    /// The gap before the copy: a third of the box (MarqueeSpacing.fractionOfContainer(1/3)).
    static func spacing(_ box: CGFloat) -> CGFloat { box / 3 }

    /// Where the line is [t] seconds after it was set: 0 through the first delay, then one travel of
    /// width + spacing at [velocity], a rest, again.
    static func offset(at t: Double, natural: CGFloat, box: CGFloat, velocity: CGFloat,
                       initialDelay: Double, repeatDelay: Double) -> CGFloat {
        let travel = natural + spacing(box)
        guard travel > 0, velocity > 0, t > initialDelay else { return 0 }
        let moving = Double(travel / velocity)
        let phase = (t - initialDelay).truncatingRemainder(dividingBy: moving + repeatDelay)
        return phase >= moving ? 0 : -CGFloat(phase) * velocity
    }
}
