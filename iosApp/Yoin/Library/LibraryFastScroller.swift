import SwiftUI

/// Port of Android component/YoinFastScroller.kt as Library uses it
/// (LibraryFastScroller.kt), Compact:
/// - Shows only when the content is longer than 3 screens, and only while
///   the list scrolls or the thumb is held; hides 1.6 s after both stop,
///   sliding 32 pt toward the edge as it fades.
/// - Thumb: 24 × 48 half-pill (round inner side, 4 pt edge corners) in a
///   32 × 64 touch box, hugging the end edge; secondaryContainer idle,
///   primary held; up / down chevrons. Drag follows the finger 1:1.
/// - While held: the tick column (surfaceContainerHigh, 28 corners,
///   labelMedium, current tick SemiBold primary, ≥ 18 pt apart) 4 pt
///   inside the thumb, and the bubble (primaryContainer, ≥ 56, titleLarge)
///   8 pt inside the column, centred on the thumb.
struct LibraryFastScroller: View {
    struct Section { var label: String; var start: Int }

    var fraction: CGFloat
    var worthShowing: Bool
    var awake: Bool
    var sections: [Section]
    var itemCount: Int
    var columns: Int
    var scheme: CoverScheme
    var onDrag: (CGFloat) -> Void
    @Binding var held: Bool

    @State private var grab: CGFloat?

    var body: some View {
        GeometryReader { g in
            let extent = max(1, g.size.height - 64)
            let thumbY = fraction * extent
            let visible = worthShowing && (awake || held)
            let lastRow = max(1, CGFloat((itemCount - 1) / max(columns, 1)))
            let ticks = thinned(sections.map { s in
                (s.label, CGFloat(s.start / max(columns, 1)) / lastRow * extent + 32)
            })
            let current = currentSection
            ZStack(alignment: .topTrailing) {
                if held && !sections.isEmpty {
                    // Tick column + bubble, only while held.
                    HStack(alignment: .top, spacing: 8) {
                        Text(current?.label ?? "")
                            .font(.gsf(22)).monospacedDigit()
                            .foregroundStyle(scheme.primary)
                            .lineLimit(1)
                            .padding(.horizontal, 16)
                            .frame(minWidth: 56, minHeight: 56)
                            .glassEffect(.regular, in: .rect(cornerRadius: 28))
                            .offset(y: min(max(0, thumbY + 32 - 28), g.size.height - 56))
                            .transition(.scale(scale: 0.6, anchor: .trailing).combined(with: .opacity))
                        ZStack(alignment: .top) {
                            ForEach(Array(ticks.enumerated()), id: \.offset) { _, t in
                                let on = t.0 == current?.label
                                Text(t.0)
                                    .font(.gsf(12, on ? .semibold : .medium)).monospacedDigit()
                                    .foregroundStyle(on ? AnyShapeStyle(scheme.primary) : AnyShapeStyle(.secondary))
                                    .lineLimit(1)
                                    .fixedSize()
                                    .position(x: tickWidth / 2, y: t.1)
                            }
                        }
                        .frame(width: tickWidth, height: g.size.height)
                        .glassEffect(.regular, in: .rect(cornerRadius: 28))
                        .transition(.scale(scale: 0.92, anchor: .trailing).combined(with: .opacity))
                    }
                    .padding(.trailing, 24 + 4)
                }
                thumb(held: held)
                    .frame(width: 32, height: 64, alignment: .trailing)
                    .contentShape(.rect)
                    .offset(y: thumbY)
                    .gesture(
                        DragGesture(minimumDistance: 0)
                            .onChanged { v in
                                if grab == nil { grab = fraction; held = true }
                                let f = min(1, max(0, (grab ?? 0) + v.translation.height / extent))
                                onDrag(f)
                            }
                            .onEnded { _ in
                                grab = nil
                                withAnimation(Yoin.effects) { held = false }
                            }
                    )
                    .allowsHitTesting(visible)
            }
            .frame(width: g.size.width, height: g.size.height, alignment: .topTrailing)
            .opacity(visible ? 1 : 0)
            .offset(x: visible ? 0 : 32)
            .animation(Yoin.spatial, value: visible)
            .animation(Yoin.spatial, value: held)
        }
    }

    private var tickWidth: CGFloat {
        let longest = sections.map(\.label.count).max() ?? 1
        return CGFloat(longest) * 7.5 + 16
    }

    private var currentSection: Section? {
        let index = Int(fraction * CGFloat(max(itemCount - 1, 0)))
        return sections.last { $0.start <= index }
    }

    /// Top-down: first and last ticks stay; a crowded one moves down to
    /// previous + 18, and is dropped if that would crowd the next or the last.
    private func thinned(_ raw: [(String, CGFloat)]) -> [(String, CGFloat)] {
        guard raw.count > 2 else { return raw }
        var out = [raw[0]]
        let last = raw[raw.count - 1]
        for i in 1..<(raw.count - 1) {
            let y = max(raw[i].1, out.last!.1 + 18)
            if y < raw[i + 1].1 && y <= last.1 - 18 { out.append((raw[i].0, y)) }
        }
        out.append(last)
        return out
    }

    private func thumb(held: Bool) -> some View {
        // Untinted glass; held = the bar's accent style (accent chevrons on a pill).
        let ink: AnyShapeStyle = held ? AnyShapeStyle(scheme.primary) : AnyShapeStyle(.primary)
        let shape = UnevenRoundedRectangle(topLeadingRadius: 12, bottomLeadingRadius: 12,
                                           bottomTrailingRadius: 4, topTrailingRadius: 4)
        return ZStack {
            shape.fill(.clear).glassEffect(.regular.interactive(), in: shape)
            if held { SelectionPill(cornerRadius: 9) }
            Path { p in
                // Up and down chevrons: tips at ±6.5, half-width 4, rise 3.
                p.move(to: CGPoint(x: 8, y: 24 - 6.5 + 3)); p.addLine(to: CGPoint(x: 12, y: 24 - 6.5))
                p.addLine(to: CGPoint(x: 16, y: 24 - 6.5 + 3))
                p.move(to: CGPoint(x: 8, y: 24 + 6.5 - 3)); p.addLine(to: CGPoint(x: 12, y: 24 + 6.5))
                p.addLine(to: CGPoint(x: 16, y: 24 + 6.5 - 3))
            }
            .stroke(ink, style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
        }
        .frame(width: 24, height: 48)
        .animation(Yoin.effects, value: held)
    }
}
