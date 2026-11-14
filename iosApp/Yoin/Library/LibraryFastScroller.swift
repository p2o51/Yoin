import SwiftUI
import YoinShared

/// Port of Android component/YoinFastScroller.kt as Library uses it
/// (LibraryFastScroller.kt), Compact:
/// - Shows only when the content is longer than 3 screens, and only while
///   the list scrolls or the thumb is held; hides 1.6 s after both stop,
///   sliding 32 pt toward the edge as it fades.
/// - Thumb: 24 × 48 half-pill (round inner side, 4 pt edge corners) in a
///   32 × 64 touch box, hugging the end edge; secondaryContainer idle,
///   primary held; up / down chevrons. Drag follows the finger 1:1.
/// - While held: the tick column (opaque surfaceContainerHigh, 28 corners,
///   labelMedium, current tick SemiBold primary, ≥ 18 pt apart) 4 pt
///   inside the thumb, and the bubble (primaryContainer, ≥ 56, titleLarge)
///   8 pt inside the column, centred on the thumb.
/// - The mappings are the shared Kotlin (FastScrollMath, YoinShared): ticks
///   one per run and per row at line(start) / lastLine, labels placed
///   top-down ≥ 18 apart, the bubble naming the last item of the row under
///   the thumb; the list ↔ thumb mapping is in LibraryView.
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
            let ticks = placedTicks(extent: extent)
            let current = currentSection
            ZStack(alignment: .topTrailing) {
                if held && !sections.isEmpty {
                    // Tick column + bubble, only while held.
                    HStack(alignment: .top, spacing: 8) {
                        Text(current?.label ?? "")
                            .font(.gsf(22)).monospacedDigit()
                            .foregroundStyle(scheme.onPrimaryContainer)
                            .lineLimit(1)
                            .padding(.horizontal, 16)
                            .frame(minWidth: 56, minHeight: 56)
                            // Android: a filled primaryContainer bubble, not glass.
                            .background(scheme.primaryContainer, in: .rect(cornerRadius: 28))
                            .offset(y: min(max(0, thumbY + 32 - 28), g.size.height - 56))
                            .transition(.scale(scale: 0.6, anchor: .trailing).combined(with: .opacity))
                        ZStack(alignment: .top) {
                            ForEach(Array(ticks.enumerated()), id: \.offset) { _, t in
                                let on = t.0 == current?.label
                                Text(t.0)
                                    .font(.gsf(12, on ? .semibold : .medium)).monospacedDigit()
                                    .foregroundStyle(on ? scheme.primary : scheme.onSurfaceVariant)
                                    .lineLimit(1)
                                    .fixedSize()
                                    .position(x: tickWidth / 2, y: t.1)
                            }
                        }
                        .frame(width: tickWidth, height: g.size.height)
                        // Android: an opaque surfaceContainerHigh column, so the
                        // grid's titles never read through under the ticks.
                        .background(scheme.surfaceContainerHigh, in: .rect(cornerRadius: 28))
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

    private var starts: KotlinIntArray {
        let a = KotlinIntArray(size: Int32(sections.count))
        for (i, s) in sections.enumerated() { a.set(index: Int32(i), value: Int32(s.start)) }
        return a
    }

    /// The section the bubble names: the one holding the last item of the thumb's row.
    private var currentSection: Section? {
        guard !sections.isEmpty else { return nil }
        let math = FastScrollMath.shared
        let index = math.labelIndex(fraction: Float(fraction), itemCount: Int32(itemCount), itemsPerLine: Int32(columns))
        let at = Int(math.sectionAt(starts: starts, index: index))
        return at >= 0 ? sections[at] : nil
    }

    /// One tick per run and per row, centred where the bubble first names it
    /// (32 down = the thumb's centre at the track top), labels ≥ 18 apart.
    private func placedTicks(extent: CGFloat) -> [(String, CGFloat)] {
        let math = FastScrollMath.shared
        let picked = math.tickSections(starts: starts, tickLabels: sections.map(\.label), itemsPerLine: Int32(columns))
        let tickStarts = KotlinIntArray(size: picked.size)
        for i in 0..<Int(picked.size) {
            tickStarts.set(index: Int32(i), value: Int32(sections[Int(picked.get(index: Int32(i)))].start))
        }
        let centers = math.tickCenters(tickStarts: tickStarts, itemCount: Int32(itemCount), itemsPerLine: Int32(columns),
                                       extent: Float(extent), offset: 32)
        let places = math.placeTicks(centers: centers, minGap: 18)
        return (0..<Int(picked.size)).compactMap { i in
            let y = places.get(index: Int32(i))
            return y.isNaN ? nil : (sections[Int(picked.get(index: Int32(i)))].label, CGFloat(y))
        }
    }

    private func thumb(held: Bool) -> some View {
        // Untinted glass; held = the bar's accent style (accent chevrons on a pill).
        let ink: AnyShapeStyle = held ? AnyShapeStyle(scheme.primary) : AnyShapeStyle(.primary)
        let shape = UnevenRoundedRectangle(topLeadingRadius: 12, bottomLeadingRadius: 12,
                                           bottomTrailingRadius: 4, topTrailingRadius: 4)
        return ZStack {
            shape.fill(.clear).yGlassEffect(.regular.interactive(), in: shape)
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
