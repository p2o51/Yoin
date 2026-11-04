import Foundation

/// How a Library view orders what it lists (Android ui/library/LibrarySort.kt).
enum LibrarySortOrder: String, Hashable, Sendable {
    /// Last opened or played in Yoin first; what has no record follows in
    /// Recently added order.
    case recents
    /// Newest in the library first, by the source's date; undated last, by name.
    case recentlyAdded
    /// By name, articles skipped where the source sorts past them (Subsonic).
    case alphabetical
    /// Albums by artist, playlists by owner (an artist is its own); then by name.
    case creator

    var label: String {
        switch self {
        case .recents: "Recents"
        case .recentlyAdded: "Recently added"
        case .alphabetical: "Alphabetical"
        case .creator: "Creator"
        }
    }
}

/// Android LibrarySorter: orders the lists Library has loaded, nothing fetched.
struct LibrarySorter {
    /// "album:<id>" / "artist:<id>" / "playlist:<id>" → last seen (ms).
    var recents: [String: Int64]
    var ignoresArticles: Bool

    struct Entry {
        var item: LibraryItem
        var position: Int
        var name: String
        var creator: String?
        var addedAt: Int64?
        var lastSeen: Int64?
    }

    func sorted(_ items: [LibraryItem], by sort: LibrarySortOrder) -> [LibraryItem] {
        entries(items).sorted { less($0, $1, sort) }.map(\.item)
    }

    /// The entries in list order, each with its sort keys. An artist takes
    /// its newest album's date (no source dates its artists).
    func entries(_ items: [LibraryItem]) -> [Entry] {
        var artistDates: [String: Int64] = [:]
        for item in items {
            guard let album = item.album, let at = LibraryStore.epochMs(album.created) else { continue }
            for key in [album.artistId, album.artist].compactMap({ $0 }) where at > artistDates[key] ?? .min {
                artistDates[key] = at
            }
        }
        var seen = Set<String>()
        return items.enumerated().compactMap { position, item in
            let key = "\(item.kind):\(item.id.rawId)"
            guard seen.insert(key).inserted else { return nil }
            switch item.kind {
            case .artist:
                return Entry(item: item, position: position, name: sortName(item.title), creator: sortName(item.title),
                             addedAt: artistDates[item.id.rawId] ?? artistDates[item.title],
                             lastSeen: recents["artist:\(item.id.rawId)"])
            case .album:
                return Entry(item: item, position: position, name: sortName(item.title),
                             creator: item.album.map(\.artist).flatMap { $0.isEmpty ? nil : sortName($0) },
                             addedAt: LibraryStore.epochMs(item.album?.created),
                             lastSeen: recents["album:\(item.id.rawId)"])
            case .playlist:
                return Entry(item: item, position: position, name: sortName(item.title),
                             creator: item.owner.flatMap { $0.isEmpty ? nil : sortName($0) },
                             addedAt: nil, lastSeen: recents["playlist:\(item.id.rawId)"])
            }
        }
    }

    func sortName(_ s: String) -> String {
        guard ignoresArticles else { return s }
        for article in ["The ", "A ", "An "] where s.count > article.count && s.lowercased().hasPrefix(article.lowercased()) {
            return String(s.dropFirst(article.count))
        }
        return s
    }

    private func byName(_ a: Entry, _ b: Entry) -> Bool {
        let c = a.name.localizedStandardCompare(b.name)
        return c == .orderedSame ? a.position < b.position : c == .orderedAscending
    }

    /// Newest first, nil last; nil when they tie.
    private func newer(_ a: Int64?, _ b: Int64?) -> Bool? {
        switch (a, b) {
        case let (x?, y?): x == y ? nil : x > y
        case (_?, nil): true
        case (nil, _?): false
        case (nil, nil): nil
        }
    }

    private func less(_ a: Entry, _ b: Entry, _ sort: LibrarySortOrder) -> Bool {
        switch sort {
        case .alphabetical:
            return byName(a, b)
        case .recentlyAdded:
            return newer(a.addedAt, b.addedAt) ?? byName(a, b)
        case .recents:
            return newer(a.lastSeen, b.lastSeen) ?? newer(a.addedAt, b.addedAt) ?? byName(a, b)
        case .creator:
            switch (a.creator, b.creator) {
            case let (x?, y?):
                let c = x.localizedStandardCompare(y)
                return c == .orderedSame ? byName(a, b) : c == .orderedAscending
            case (_?, nil): return true
            case (nil, _?): return false
            case (nil, nil): return byName(a, b)
            }
        }
    }
}

/// Android LibraryIndex / LibraryScrollIndex: the fast scroller's sections
/// for one sort, built from the same keys the sort used.
enum LibraryScrollIndex {
    static func sections(_ items: [LibraryItem], sort: LibrarySortOrder, sorter: LibrarySorter,
                         now: Date = Date(), calendar: Calendar = .current) -> [LibraryFastScroller.Section] {
        let entries = sorter.entries(items)
        var out: [LibraryFastScroller.Section] = []
        func add(_ label: String, _ i: Int) {
            if out.last?.label != label { out.append(.init(label: label, start: out.isEmpty ? 0 : i)) }
        }
        switch sort {
        case .alphabetical, .creator:
            for (i, e) in entries.enumerated() { add(letter(sort == .creator ? e.creator ?? "" : e.name), i) }
        case .recentlyAdded:
            // Year by year, months in the current year; undated items join
            // the run they sit in.
            let year = calendar.component(.year, from: now)
            for (i, e) in entries.enumerated() {
                guard let ms = e.addedAt else { continue }
                let date = Date(timeIntervalSince1970: Double(ms) / 1000)
                let y = calendar.component(.year, from: date)
                add(y == year ? date.formatted(.dateTime.month(.abbreviated)) : String(y), i)
            }
        case .recents:
            // Nothing recorded (a new device): the handle alone.
            guard entries.contains(where: { $0.lastSeen != nil }) else { return [] }
            let year = calendar.component(.year, from: now)
            for (i, e) in entries.enumerated() {
                guard let ms = e.lastSeen else { add("Not opened", i); continue }
                let date = Date(timeIntervalSince1970: Double(ms) / 1000)
                if calendar.isDate(date, inSameDayAs: now) { add("Today", i) }
                else if calendar.isDate(date, equalTo: now, toGranularity: .weekOfYear) { add("This week", i) }
                else if calendar.isDate(date, equalTo: now, toGranularity: .month) { add("This month", i) }
                else {
                    let y = calendar.component(.year, from: date)
                    add(y == year ? date.formatted(.dateTime.month(.abbreviated)) : String(y), i)
                }
            }
        }
        return out
    }

    /// The scroller's alphabet: a letter, else "#" (digits and symbols).
    static func letter(_ name: String) -> String {
        guard let c = name.first else { return "#" }
        return c.isLetter ? String(c).uppercased() : "#"
    }
}
