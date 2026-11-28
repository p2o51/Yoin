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
    var edgeFade: CGFloat = 28
    /// Small tags after the text that travel with it as one unit (Now Playing's artist line on iOS, owner
    /// 2026-11-26 option B: the release's EP / Single and its version, `AlbumVersionTag`s in [tagScheme]).
    var tags: [String] = []
    var tagScheme: CoverScheme? = nil
    /// Pauses the timeline while the view is obscured (e.g. pill under Full Now Playing or a sheet).
    var paused: Bool = false
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
                if overflow && !reduceMotion && !paused {
                    TimelineView(.animation(minimumInterval: 1.0 / 30.0)) { ctx in
                        let x = Self.offset(at: ctx.date.timeIntervalSince(start), natural: natural, box: box,
                                            velocity: velocity, initialDelay: initialDelay, repeatDelay: repeatDelay)
                        HStack(spacing: Self.spacing(box)) { line.fixedSize(); line.fixedSize() }
                            .offset(x: x)
                            .frame(width: box, alignment: .leading)
                            .clipped()
                            .mask {
                                if edgeFade > 0 {
                                    LinearGradient(
                                        stops: Self.edgeMaskStops(width: box, edgeFade: edgeFade, scrollOffset: x),
                                        startPoint: .leading,
                                        endPoint: .trailing
                                    )
                                } else {
                                    Color.white
                                }
                            }
                    }
                } else {
                    line.lineLimit(1).frame(width: box, alignment: .leading)
                }
            }
            .onChange(of: text) { _, _ in start = Date() }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(([text] + tags).joined(separator: ", "))
    }

    @ViewBuilder private var line: some View {
        if let tagScheme, !tags.isEmpty {
            HStack(spacing: 6) {
                Text(text).font(font).foregroundStyle(color)
                ForEach(Array(tags.enumerated()), id: \.offset) { _, tag in
                    AlbumVersionTag(version: tag, scheme: tagScheme).fixedSize()
                }
            }
        } else {
            Text(text).font(font).foregroundStyle(color)
        }
    }

    /// Soft leading/trailing fade mask stops matching Android's 28dp edge fade (`horizontalFadeMask`).
    static func edgeMaskStops(width: CGFloat, edgeFade: CGFloat, scrollOffset: CGFloat) -> [Gradient.Stop] {
        guard width > 1, edgeFade > 0 else {
            return [.init(color: .white, location: 0), .init(color: .white, location: 1)]
        }
        let fadeFraction = min(0.35, edgeFade / width)
        let leadingTransparent = scrollOffset < -0.5
        if leadingTransparent {
            return [
                .init(color: .clear, location: 0),
                .init(color: .white, location: fadeFraction),
                .init(color: .white, location: 1 - fadeFraction),
                .init(color: .clear, location: 1),
            ]
        }
        return [
            .init(color: .white, location: 0),
            .init(color: .white, location: 1 - fadeFraction),
            .init(color: .clear, location: 1),
        ]
    }

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
