import ActivityKit
import SwiftUI
import WidgetKit

/*
 * A playlist restore's Live Activity (Widgets/RestoreActivity.swift; Android RestoreLiveUpdate): the Lock
 * Screen shows where it's going, the playlist and the count, and while matching the bar, each song's part in
 * its outcome's colour, the mascot riding it; the Dynamic Island the mascot and the count, opening to the
 * same. Yoin's own colours, one per outcome (the mascot's arms): matched violet, to check pink, not found
 * grey.
 */
struct RestoreActivityWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: RestoreActivityAttributes.self) { context in
            RestoreLockScreen(attributes: context.attributes, state: context.state, stale: context.isStale)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    RestoreMascot()
                        .frame(width: 40, height: 40)
                        .padding(.leading, 4)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    // One line: the region is about the mascot's width, so "28/45" shrinks rather than wraps.
                    RestoreCount(state: context.state, compact: true, paused: context.isStale)
                        .font(.system(size: 17, weight: .semibold).monospacedDigit())
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                        .foregroundStyle(.white)
                        .frame(maxHeight: .infinity)
                        .padding(.trailing, 4)
                }
                DynamicIslandExpandedRegion(.center) {
                    VStack(alignment: .leading, spacing: 2) {
                        RestoreTitle(stage: context.state.stage, target: context.attributes.target)
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(.white)
                        Text(context.attributes.playlist)
                            .font(.system(size: 13))
                            .foregroundStyle(.white.opacity(0.6))
                    }
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    if context.state.stage == .matching {
                        RestoreBarView(state: context.state, track: .white.opacity(0.22), paused: context.isStale)
                            .frame(height: 22)
                            .padding(.horizontal, 4)
                            .padding(.top, 4)
                    }
                }
            } compactLeading: {
                RestoreMascot()
                    .frame(width: 24, height: 24)
            } compactTrailing: {
                RestoreCount(state: context.state, compact: true, paused: context.isStale)
                    .font(.system(size: 14, weight: .semibold).monospacedDigit())
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                    .foregroundStyle(RestoreColors.onIsland)
            } minimal: {
                RestoreRing(state: context.state)
            }
            .keylineTint(RestoreColors.matched)
        }
    }
}

/// The Lock Screen's (and a banner's): the stage and the playlist, the count, and while matching the bar.
private struct RestoreLockScreen: View {
    var attributes: RestoreActivityAttributes
    var state: RestoreActivityAttributes.ContentState
    var stale: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                if state.stage != .matching {
                    RestoreMascot().frame(width: 36, height: 36)
                }
                VStack(alignment: .leading, spacing: 2) {
                    RestoreTitle(stage: state.stage, target: attributes.target)
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(.primary)
                    Text(attributes.playlist)
                        .font(.system(size: 14))
                        .foregroundStyle(.secondary)
                }
                .lineLimit(1)
                Spacer(minLength: 8)
                RestoreCount(state: state, paused: stale)
                    .font(.system(size: 16, weight: .semibold).monospacedDigit())
                    .lineLimit(1)
                    .foregroundStyle(.primary)
            }
            if state.stage == .matching {
                RestoreBarView(state: state, track: Color.primary.opacity(0.14), paused: stale)
                    .frame(height: 24)
            }
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 16)
    }
}

/// "Matching on Spotify" / "Creating on …" / "Created on …" / "Not created on …" (Android's titles).
private struct RestoreTitle: View {
    var stage: RestoreActivityAttributes.ContentState.Stage
    var target: String

    var body: some View {
        switch stage {
        case .matching: Text("Matching on \(target)")
        case .creating: Text("Creating on \(target)")
        case .created: Text("Created on \(target)")
        case .notCreated: Text("Not created on \(target)")
        }
    }
}

/// The count (Android's status-bar chip): looked up of all while matching, the songs being written, and
/// what was added. [paused]: no word for two minutes while under way (Yoin was stopped mid-restore), said
/// in place of the count; the text stays readable.
private struct RestoreCount: View {
    var state: RestoreActivityAttributes.ContentState
    var compact = false
    var paused = false

    var body: some View {
        if paused && state.stage.underWay {
            Text("Paused")
        } else {
            count
        }
    }

    @ViewBuilder private var count: some View {
        switch state.stage {
        case .matching:
            if compact {
                Text(verbatim: "\(state.done)/\(state.total)")
            } else {
                Text("\(state.done) / \(state.total)")
            }
        case .creating: Text(state.total, format: .number)
        case .created: Text("\(state.done) added")
        case .notCreated: EmptyView()
        }
    }
}

/// The bar: each run of songs in what they came back as, the rest in [track], the mascot where matching has
/// got to; faded while [paused].
private struct RestoreBarView: View {
    var state: RestoreActivityAttributes.ContentState
    var track: Color
    var paused = false

    var body: some View {
        GeometryReader { g in
            let runs = RestoreBar.runs(state.bar)
            let cells = CGFloat(max(1, state.bar.count))
            let mascot = g.size.height
            let reached = state.total > 0 ? min(1, CGFloat(state.done) / CGFloat(state.total)) : 0
            ZStack(alignment: .leading) {
                HStack(spacing: 0) {
                    ForEach(Array(runs.enumerated()), id: \.offset) { _, run in
                        Rectangle()
                            .fill(RestoreColors.color(run.cell) ?? .clear)
                            .frame(width: g.size.width * CGFloat(run.length) / cells)
                    }
                }
                .frame(width: g.size.width, height: 8, alignment: .leading)
                .background(track)
                .clipShape(.capsule)
                RestoreMascot()
                    .frame(width: mascot, height: mascot)
                    .offset(x: (g.size.width - mascot) * reached)
            }
            .frame(width: g.size.width, height: g.size.height, alignment: .leading)
            .opacity(paused ? 0.4 : 1)
        }
        .accessibilityHidden(true)
    }
}

/// The island at its smallest: how far matching has got round the mascot.
private struct RestoreRing: View {
    var state: RestoreActivityAttributes.ContentState

    var body: some View {
        let reached = state.stage == .matching && state.total > 0 ? Double(state.done) / Double(state.total) : 1
        ZStack {
            Circle().stroke(.white.opacity(0.22), lineWidth: 2.5)
            Circle()
                .trim(from: 0, to: reached)
                .stroke(RestoreColors.matched, style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
                .rotationEffect(.degrees(-90))
            RestoreMascot().padding(5)
        }
    }
}

/// The Yoin mascot in its own colours, as Android's Live Update tracker draws it.
private struct RestoreMascot: View {
    var body: some View {
        YoinArmsCanvas(arms: [(index: 0, color: RestoreColors.toCheck), (index: 1, color: RestoreColors.matched),
                              (index: 2, color: RestoreColors.navy)],
                       lineColor: .white, scale: 0.8)
            .accessibilityHidden(true)
    }
}

/// Android RestoreLiveUpdate's colours: the mascot's arms.
enum RestoreColors {
    static let matched = Color(argb: 0xFF91_13FF)
    static let toCheck = Color(argb: 0xFFFF_59CD)
    static let notFound = Color(argb: 0xFF8E_8A99)
    static let navy = Color(argb: 0xFF19_2396)
    /// The count beside the island's camera: the violet lifted to read on black.
    static let onIsland = Color(argb: 0xFFC6_9BFF)

    static func color(_ cell: RestoreBar.Cell) -> Color? {
        switch cell {
        case .matched: matched
        case .toCheck: toCheck
        case .notFound: notFound
        case .pending: nil
        }
    }
}
