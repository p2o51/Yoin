import ActivityKit
import SwiftUI
import WidgetKit

/*
 * Offline downloads' Live Activity (Widgets/DownloadActivity.swift; Android YoinDownloadService's progress
 * notification): the Lock Screen says it's downloading for offline listening, what downloads now, the count,
 * and a bar the mascot rides, as a playlist restore's does; waiting for Wi‑Fi or a network it says so in
 * place of the count and the bar fades; through, what was kept (and what wasn't). The Dynamic Island shows
 * the mascot and the count, opening to the same; at its smallest, a ring round the mascot.
 */
struct DownloadActivityWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: DownloadActivityAttributes.self) { context in
            DownloadLockScreen(state: context.state)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    RestoreMascot()
                        .frame(width: 40, height: 40)
                        .padding(.leading, 4)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    DownloadCount(state: context.state, compact: true)
                        .font(.system(size: 17, weight: .semibold).monospacedDigit())
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                        .foregroundStyle(.white)
                        .frame(maxHeight: .infinity)
                        .padding(.trailing, 4)
                }
                DynamicIslandExpandedRegion(.center) {
                    VStack(alignment: .leading, spacing: 2) {
                        DownloadHeadline(state: context.state)
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(.white)
                        DownloadSubject(state: context.state)
                            .font(.system(size: 13))
                            .foregroundStyle(.white.opacity(0.6))
                    }
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    if context.state.stage.underWay {
                        DownloadBar(state: context.state, track: .white.opacity(0.22))
                            .frame(height: 22)
                            .padding(.horizontal, 4)
                            .padding(.top, 4)
                    }
                }
            } compactLeading: {
                RestoreMascot()
                    .frame(width: 24, height: 24)
            } compactTrailing: {
                DownloadCount(state: context.state, compact: true)
                    .font(.system(size: 14, weight: .semibold).monospacedDigit())
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                    .foregroundStyle(RestoreColors.onIsland)
            } minimal: {
                DownloadRing(state: context.state)
            }
            .keylineTint(RestoreColors.matched)
        }
    }
}

/// The Lock Screen's (and a banner's): what it's doing and on what, the count, and while under way the bar.
private struct DownloadLockScreen: View {
    var state: DownloadActivityAttributes.ContentState

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                if !state.stage.underWay {
                    RestoreMascot().frame(width: 36, height: 36)
                }
                VStack(alignment: .leading, spacing: 2) {
                    DownloadHeadline(state: state)
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(.primary)
                    DownloadSubject(state: state)
                        .font(.system(size: 14))
                        .foregroundStyle(.secondary)
                }
                .lineLimit(1)
                Spacer(minLength: 8)
                DownloadCount(state: state)
                    .font(.system(size: 16, weight: .semibold).monospacedDigit())
                    .lineLimit(1)
                    .foregroundStyle(.primary)
            }
            if state.stage.underWay {
                DownloadBar(state: state, track: Color.primary.opacity(0.14))
                    .frame(height: 24)
            }
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 16)
    }
}

/// "Downloading for offline listening" (Android's notification title) / "Waiting for Wi‑Fi" / "Waiting for
/// network" / "Downloaded for offline listening" / "Some songs couldn't download" (Android's row line).
private struct DownloadHeadline: View {
    var state: DownloadActivityAttributes.ContentState

    var body: some View {
        switch state.stage {
        case .downloading: Text("Downloading for offline listening")
        case .waitingForWiFi: Text("Waiting for Wi‑Fi")
        case .waitingForNetwork: Text("Waiting for network")
        case .finished:
            if state.failed > 0 {
                Text("Some songs couldn't download")
            } else {
                Text("Downloaded for offline listening")
            }
        }
    }
}

/// The album, playlist or song, and how many more the run holds.
private struct DownloadSubject: View {
    var state: DownloadActivityAttributes.ContentState

    var body: some View {
        if state.more > 0 {
            Text("\(state.title) and \(state.more) more")
        } else {
            Text(verbatim: state.title)
        }
    }
}

/// The count: songs kept of all while under way; through, how many were kept ("3 failed" when some weren't).
private struct DownloadCount: View {
    var state: DownloadActivityAttributes.ContentState
    var compact = false

    var body: some View {
        if state.stage.underWay {
            if compact {
                Text(verbatim: "\(state.done)/\(state.total)")
            } else {
                Text("\(state.done) / \(state.total)")
            }
        } else if state.failed > 0 {
            Text("\(state.failed) failed")
        } else if compact {
            Text(state.done, format: .number)
        } else {
            Text("\(state.done) songs")
        }
    }
}

/// The bar: how far the run has got in Yoin's violet, the mascot where it has got to; faded while waiting.
private struct DownloadBar: View {
    var state: DownloadActivityAttributes.ContentState
    var track: Color

    var body: some View {
        GeometryReader { g in
            let mascot = g.size.height
            let reached = CGFloat(min(1, max(0, state.progress)))
            ZStack(alignment: .leading) {
                Capsule()
                    .fill(track)
                    .frame(width: g.size.width, height: 8)
                Capsule()
                    .fill(RestoreColors.matched)
                    .frame(width: max(8, g.size.width * reached), height: 8)
                RestoreMascot()
                    .frame(width: mascot, height: mascot)
                    .offset(x: (g.size.width - mascot) * reached)
            }
            .frame(width: g.size.width, height: g.size.height, alignment: .leading)
            .opacity(state.stage == .downloading ? 1 : 0.4)
        }
        .accessibilityHidden(true)
    }
}

/// The island at its smallest: how far the run has got round the mascot.
private struct DownloadRing: View {
    var state: DownloadActivityAttributes.ContentState

    var body: some View {
        let reached = state.stage.underWay ? min(1, max(0, state.progress)) : 1
        ZStack {
            Circle().stroke(.white.opacity(0.22), lineWidth: 2.5)
            Circle()
                .trim(from: 0, to: reached)
                .stroke(state.failed > 0 && !state.stage.underWay ? RestoreColors.toCheck : RestoreColors.matched,
                        style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
                .rotationEffect(.degrees(-90))
            RestoreMascot().padding(5)
        }
    }
}
