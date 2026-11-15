import ActivityKit
import Foundation

/*
 * A playlist restore on the Lock Screen and in the Dynamic Island (Android RestoreLiveUpdate's Live Update,
 * 51 2026-10-19: "灵动岛的感觉"): while the backup is matched on the target, each song's part of the bar
 * taking its outcome's colour as it comes back and the Yoin mascot riding it; while the playlist is written;
 * and, written, what came of it. Matching done, it goes: the review waits in the app.
 *
 * This file is compiled into both the app (Data/Backup/RestoreLiveActivity.swift starts and updates it) and
 * the widget extension (YoinWidgets/RestoreActivityViews.swift draws it).
 */
struct RestoreActivityAttributes: ActivityAttributes, Hashable, Sendable {
    struct ContentState: Codable, Hashable, Sendable {
        enum Stage: String, Codable, Hashable, Sendable {
            /// Each song looked up on the target: [done] of [total], the [bar] filling.
            case matching
            /// The playlist being written: [total] songs.
            case creating
            /// Written: [done] songs added.
            case created
            /// No playlist was made.
            case notCreated

            /// Still going (an activity left from an earlier launch is over).
            var underWay: Bool { self == .matching || self == .creating }
        }

        var stage: Stage
        var done: Int
        var total: Int
        /// The bar while matching (RestoreBar), else empty.
        var bar: String
    }

    /// The backup's name.
    var playlist: String
    /// The account it goes to ("Spotify", a server's name).
    var target: String
}

/// The bar of a restore's Live Activity: what each song came back as, in the order they came back, as at
/// most [cells] cells (one per song up to that many; a longer playlist's cell shows what most of its songs
/// came back as). A letter a cell keeps the activity small (ActivityKit takes 4 KB).
enum RestoreBar {
    enum Cell: Character, Sendable, CaseIterable {
        /// Matched, or picked in the review.
        case matched = "m"
        case toCheck = "c"
        case notFound = "n"
        /// Not looked up yet.
        case pending = "-"
    }

    static let cells = 96

    /// [outcomes]: the songs looked up so far, in the order they came back, of [total].
    static func encode(_ outcomes: [Cell], total: Int) -> String {
        guard total > 0 else { return "" }
        let seen = Array(outcomes.prefix(total))
        let count = min(total, cells)
        return String((0..<count).map { i -> Character in
            let lo = min(i * total / count, seen.count), hi = min((i + 1) * total / count, seen.count)
            guard lo < hi else { return Cell.pending.rawValue }
            return majority(seen[lo..<hi]).rawValue
        })
    }

    /// The outcome most of [cells] came back as; a tie goes to the one the listener has to look at (to check,
    /// then not found).
    static func majority(_ cells: ArraySlice<Cell>) -> Cell {
        var best = Cell.pending, most = 0
        for kind in [Cell.toCheck, .notFound, .matched] {
            let n = cells.lazy.filter { $0 == kind }.count
            if n > most { best = kind; most = n }
        }
        return best
    }

    /// The bar as runs of one kind, left to right: what's drawn.
    static func runs(_ bar: String) -> [(cell: Cell, length: Int)] {
        var runs: [(cell: Cell, length: Int)] = []
        for letter in bar {
            let cell = Cell(rawValue: letter) ?? .pending
            if let last = runs.last, last.cell == cell {
                runs[runs.count - 1].length += 1
            } else {
                runs.append((cell: cell, length: 1))
            }
        }
        return runs
    }
}
