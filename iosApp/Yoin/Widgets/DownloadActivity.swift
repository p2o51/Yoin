import ActivityKit
import Foundation

/*
 * Offline downloads on the Lock Screen and in the Dynamic Island (Android YoinDownloadService's progress
 * notification, "Downloading for offline listening"): while songs download, how many of how many and a bar,
 * the Yoin mascot riding it as on a playlist restore's; while the downloads wait for Wi‑Fi (mobile data not
 * allowed) or for any network, saying so; and, all through, what came of it until it's swiped away or a few
 * minutes pass.
 *
 * This file is compiled into both the app (Data/Cache/DownloadLiveActivity.swift starts and updates it) and
 * the widget extension (YoinWidgets/DownloadActivityViews.swift draws it).
 */
struct DownloadActivityAttributes: ActivityAttributes, Hashable, Sendable {
    struct ContentState: Codable, Hashable, Sendable {
        enum Stage: String, Codable, Hashable, Sendable {
            /// Songs downloading: [done] of [total], [progress] of the way.
            case downloading
            /// Waiting for Wi‑Fi: on mobile data (or Low Data Mode), which Settings › Downloads doesn't allow.
            case waitingForWiFi
            /// Waiting for any network.
            case waitingForNetwork
            /// All through: [done] kept, [failed] not.
            case finished

            /// Still going (an activity left from an earlier launch is over).
            var underWay: Bool { self != .finished }
        }

        var stage: Stage
        /// Songs whole on the device, of this run's [total].
        var done: Int
        var total: Int
        /// Songs that couldn't be downloaded.
        var failed: Int
        /// 0...1: the songs done and the part of the one downloading now.
        var progress: Double
        /// The album, playlist or song downloading now (the first of the run's when it's over).
        var title: String
        /// The run's other albums, playlists and songs ("and 2 more").
        var more: Int
    }
}
