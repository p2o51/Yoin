import Foundation

/// iOS LibrarySort.swift compiles into the Mac; these are the two names it reads from iOS files that
/// aren't on the Mac (LibraryStore's date parser, the fast scroller's section).
enum LibraryStore {
    /// An ISO-8601 date (the server's `created`) in epoch ms (iOS LibraryStore.epochMs).
    nonisolated static func epochMs(_ iso: String?) -> Int64? {
        guard let iso else { return nil }
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let date = f.date(from: iso) ?? {
            f.formatOptions = [.withInternetDateTime]
            return f.date(from: iso)
        }()
        return date.map { Int64($0.timeIntervalSince1970 * 1000) }
    }
}

enum LibraryFastScroller {
    /// One run of the index (a letter, a year, "This week"), from the item it starts at.
    struct Section: Equatable { var label: String; var start: Int }
}
