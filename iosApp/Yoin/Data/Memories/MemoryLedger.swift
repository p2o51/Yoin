import Foundation

/*
 * The memory ledger (Android data/memory/MemoryLedger.kt, MemoryGrid.kt, MemoryLedgerList.kt; #178 #186):
 * every memory the listener has kept, on one timeline, across all their accounts. Home's Memories grid and
 * the All Memories page read it. Local only, pure: the stores' rows go in (LedgerSource gathers them).
 */

/// What kind of mark a ledger entry is. Notes, reviews and titles are writing; the scores are not.
enum LedgerKind: String, Hashable, Sendable {
    case songNote, review, title, albumRating, trackRating

    var wrote: Bool {
        switch self {
        case .songNote, .review, .title: true
        case .albumRating, .trackRating: false
        }
    }
}

/// One account the ledger reads: a provider plus the scope its rows carry (nil for Apple Music).
struct LedgerAccount: Hashable, Sendable {
    var provider: String
    var scope: String?

    var key: String { "\(provider)|\(scope ?? "")" }
}

/// The album an entry belongs to, in its own account: what the grid colours a cell with.
struct LedgerAlbum: Hashable, Sendable {
    var account: LedgerAccount
    var albumId: String
    var name: String
    var artist: String
    /// Subsonic cover id, or the artwork URL (a history snapshot's `coverKey`).
    var coverKey: String?

    /// One album in one account: two accounts on the same album are two albums here, as everywhere.
    var key: String { "\(account.key)|\(albumId)" }
}

struct LedgerEntry: Hashable, Sendable, Identifiable {
    /// Stable across reloads: the kind plus the row's own key.
    var id: String
    var kind: LedgerKind
    /// When it was written or scored (epoch ms).
    var at: Int64
    var account: LedgerAccount
    /// Nil for a song note or track score whose album Yoin never learned.
    var album: LedgerAlbum?
    /// A note's or review's text, or the title itself.
    var text: String? = nil
    var trackTitle: String? = nil
    var songId: String? = nil
    var positionMs: Int64? = nil
    /// 0–10.
    var score: Double? = nil
}

struct LedgerStats: Hashable, Sendable {
    var albums: Int
    var writings: Int
    var characters: Int
    var ratedTracks: Int
    var firstAt: Int64?
}

/// The ledger's raw rows, every account's (the stores' `everyAccount` reads).
struct LedgerRows: Sendable {
    struct Note: Sendable {
        var id: String
        var account: LedgerAccount
        var trackId: String
        var content: String
        var createdAt: Int64
        var title: String
        var positionMs: Int64?
    }
    struct TrackRating: Sendable {
        var account: LedgerAccount
        var songId: String
        var rating: Double?
        var updatedAt: Int64
    }
    struct AlbumRecord: Sendable {
        var account: LedgerAccount
        var albumId: String
        var rating: Double
        var review: String?
        var reviewUpdatedAt: Int64?
        var updatedAt: Int64
    }
    struct Title: Sendable {
        var account: LedgerAccount
        var albumId: String
        var title: String
        var updatedAt: Int64
    }
    var notes: [Note] = []
    var trackRatings: [TrackRating] = []
    var albums: [AlbumRecord] = []
    var titles: [Title] = []
}

/// What the ledger knows of albums and songs it never stores itself: from plays and loaded details.
struct LedgerLookup: Sendable {
    /// Account key + album id → the album's name, artist and cover.
    var album: @Sendable (_ account: LedgerAccount, _ albumId: String) -> LedgerAlbum? = { _, _ in nil }
    /// Account key + song id → its album id and title.
    var song: @Sendable (_ account: LedgerAccount, _ songId: String) -> (albumId: String?, title: String?)? = { _, _ in nil }
}

enum MemoryLedger {
    /// Turns [rows] into entries, newest first. Only [accounts] count (a removed account's rows don't);
    /// nil counts every row. An album entry whose album can't be named is left out; a song note or score
    /// keeps a nil album.
    static func build(_ rows: LedgerRows, accounts: Set<LedgerAccount>?, lookup: LedgerLookup) -> [LedgerEntry] {
        func counts(_ a: LedgerAccount) -> Bool { accounts?.contains(a) ?? true }
        var albumCache: [String: LedgerAlbum?] = [:]
        func album(_ a: LedgerAccount, _ id: String?) -> LedgerAlbum? {
            guard let id, !id.isEmpty else { return nil }
            let key = "\(a.key)|\(id)"
            if let cached = albumCache[key] { return cached }
            let found = lookup.album(a, id)
            albumCache[key] = found
            return found
        }
        var out: [LedgerEntry] = []
        for n in rows.notes where counts(n.account) {
            let text = n.content.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !text.isEmpty else { continue }
            let song = lookup.song(n.account, n.trackId)
            out.append(LedgerEntry(id: "song-note:\(n.id)", kind: .songNote, at: n.createdAt, account: n.account,
                                   album: album(n.account, song?.albumId), text: text,
                                   trackTitle: n.title.nonBlank ?? song?.title?.nonBlank, songId: n.trackId,
                                   positionMs: n.positionMs))
        }
        for r in rows.albums where counts(r.account) {
            guard let a = album(r.account, r.albumId) else { continue }
            let base = "\(r.account.key)|\(r.albumId)"
            if r.rating > 0 {
                out.append(LedgerEntry(id: "album-rating:\(base)", kind: .albumRating, at: r.updatedAt,
                                       account: r.account, album: a, score: r.rating))
            }
            if let review = r.review?.trimmingCharacters(in: .whitespacesAndNewlines), !review.isEmpty {
                out.append(LedgerEntry(id: "review:\(base)", kind: .review, at: r.reviewUpdatedAt ?? r.updatedAt,
                                       account: r.account, album: a, text: review))
            }
        }
        for r in rows.trackRatings where counts(r.account) {
            guard let score = r.rating else { continue }
            let song = lookup.song(r.account, r.songId)
            out.append(LedgerEntry(id: "track-rating:\(r.account.key)|\(r.songId)", kind: .trackRating, at: r.updatedAt,
                                   account: r.account, album: album(r.account, song?.albumId),
                                   trackTitle: song?.title?.nonBlank, songId: r.songId, score: score))
        }
        for t in rows.titles where counts(t.account) {
            guard let a = album(t.account, t.albumId), let text = t.title.nonBlank else { continue }
            out.append(LedgerEntry(id: "title:\(t.account.key)|\(t.albumId)", kind: .title, at: t.updatedAt,
                                   account: t.account, album: a, text: text))
        }
        return out.sorted(by: newestFirst)
    }

    static func newestFirst(_ a: LedgerEntry, _ b: LedgerEntry) -> Bool {
        a.at != b.at ? a.at > b.at : a.id < b.id
    }

    static func stats(_ entries: [LedgerEntry]) -> LedgerStats {
        let writings = entries.filter { $0.kind == .songNote || $0.kind == .review }
        return LedgerStats(
            albums: Set(entries.compactMap { $0.album?.key }).count,
            writings: writings.count,
            characters: writings.reduce(0) { $0 + ($1.text?.count ?? 0) },
            ratedTracks: entries.filter { $0.kind == .trackRating }.count,
            firstAt: entries.map(\.at).min())
    }
}

// MARK: Grid (B3: a calendar wall whose cells grow when you write less)

enum MemoryGridScale: String, Hashable, Sendable { case month, week, day }

struct MemoryGridMark: Hashable, Sendable {
    var album: LedgerAlbum
    var wrote: Bool
    var count: Int
}

struct MemoryGridCell: Hashable, Sendable {
    /// First and last day the cell covers (start of day, local).
    var start: Date
    var end: Date
    var marks: [MemoryGridMark]
    var writings: Int
    var entries: Int
    var future: Bool
    var isEmpty: Bool { entries == 0 }
}

struct MemoryGrid: Hashable, Sendable {
    var scale: MemoryGridScale
    /// Oldest first.
    var cells: [MemoryGridCell]
    var capacity: Int { Self.capacity(scale) }

    static let lookbackDays = 119
    static let weekThreshold = 10
    static let dayThreshold = 30
    static let weeks = 26
    static let dayWeeks = 17
    static let minMonths = 6
    static let maxMonths = 12

    static func capacity(_ s: MemoryGridScale) -> Int { s == .month ? 4 : 2 }

    static func scale(activeDays: Int) -> MemoryGridScale {
        activeDays < weekThreshold ? .month : activeDays < dayThreshold ? .week : .day
    }

    /// Lays [entries] out as of [today] in [calendar] (its first weekday starts a week).
    static func build(_ entries: [LedgerEntry], today: Date, calendar: Calendar, scale forced: MemoryGridScale? = nil) -> MemoryGrid {
        let cal = calendar
        let todayStart = cal.startOfDay(for: today)
        let dated = entries.map { (cal.startOfDay(for: Date(timeIntervalSince1970: Double($0.at) / 1000)), $0) }
        let lookbackStart = cal.date(byAdding: .day, value: -(lookbackDays - 1), to: todayStart)!
        let active = Set(dated.map(\.0).filter { $0 >= lookbackStart && $0 <= todayStart }).count
        let chosen = forced ?? scale(activeDays: active)
        var ranges: [(Date, Date)] = []
        switch chosen {
        case .month:
            let thisMonth = cal.date(from: cal.dateComponents([.year, .month], from: todayStart))!
            let oldest = dated.map(\.0).min().map { cal.date(from: cal.dateComponents([.year, .month], from: $0))! } ?? thisMonth
            let span = (cal.dateComponents([.month], from: oldest, to: thisMonth).month ?? 0) + 1
            let back = min(max(span, minMonths), maxMonths)
            for ago in stride(from: back - 1, through: 0, by: -1) {
                let start = cal.date(byAdding: .month, value: -ago, to: thisMonth)!
                let end = cal.date(byAdding: .day, value: -1, to: cal.date(byAdding: .month, value: 1, to: start)!)!
                ranges.append((start, end))
            }
        case .week, .day:
            let count = chosen == .week ? weeks : dayWeeks
            let weekday = cal.component(.weekday, from: todayStart)
            let back = (weekday - cal.firstWeekday + 7) % 7
            let thisWeek = cal.date(byAdding: .day, value: -back, to: todayStart)!
            let first = cal.date(byAdding: .day, value: -7 * (count - 1), to: thisWeek)!
            if chosen == .week {
                for w in 0..<count {
                    let s = cal.date(byAdding: .day, value: 7 * w, to: first)!
                    ranges.append((s, cal.date(byAdding: .day, value: 6, to: s)!))
                }
            } else {
                for d in 0..<(count * 7) {
                    let s = cal.date(byAdding: .day, value: d, to: first)!
                    ranges.append((s, s))
                }
            }
        }
        let cap = capacity(chosen)
        let cells = ranges.map { range in
            let inCell = dated.filter { $0.0 >= range.0 && $0.0 <= range.1 }.map(\.1)
            return MemoryGridCell(start: range.0, end: range.1, marks: rankMarks(inCell, capacity: cap),
                                  writings: inCell.filter { $0.kind.wrote && $0.kind != .title }.count,
                                  entries: inCell.count, future: range.0 > todayStart)
        }
        return MemoryGrid(scale: chosen, cells: cells)
    }

    /// An album written about ranks ahead of one only scored, then the one with more marks, then newest.
    static func rankMarks(_ entries: [LedgerEntry], capacity: Int) -> [MemoryGridMark] {
        struct Tally { var album: LedgerAlbum; var wrote: Bool; var count: Int; var latest: Int64 }
        var tallies: [String: Tally] = [:]
        for e in entries {
            guard let a = e.album else { continue }
            var t = tallies[a.key] ?? Tally(album: a, wrote: false, count: 0, latest: e.at)
            t.wrote = t.wrote || e.kind.wrote
            t.count += 1
            t.latest = max(t.latest, e.at)
            tallies[a.key] = t
        }
        return tallies.values.sorted { a, b in
            if a.wrote != b.wrote { return a.wrote }
            if a.count != b.count { return a.count > b.count }
            if a.latest != b.latest { return a.latest > b.latest }
            return a.album.key < b.album.key
        }.prefix(capacity).map { MemoryGridMark(album: $0.album, wrote: $0.wrote, count: $0.count) }
    }
}

// MARK: All Memories' views (Timeline, Cards, List)

enum LedgerView: String, CaseIterable, Hashable, Sendable { case timeline, cards, list }
enum LedgerSort: String, CaseIterable, Hashable, Sendable { case recent, score, name }

struct LedgerSongScore: Hashable, Sendable {
    var title: String?
    var score: Double
    var at: Int64
}

/// One album or one song, with everything kept on it.
enum LedgerItem: Hashable, Sendable, Identifiable {
    case album(album: LedgerAlbum, title: String?, score: Double?, review: String?, songs: [LedgerSongScore], at: Int64)
    case song(key: String, account: LedgerAccount, title: String?, album: LedgerAlbum?, score: Double?,
              note: LedgerEntry?, notes: Int, at: Int64)

    var id: String {
        switch self {
        case .album(let a, _, _, _, _, _): "album:\(a.key)"
        case .song(let key, _, _, _, _, _, _, _): key
        }
    }
    var at: Int64 {
        switch self {
        case .album(_, _, _, _, _, let at), .song(_, _, _, _, _, _, _, let at): at
        }
    }
    var account: LedgerAccount {
        switch self {
        case .album(let a, _, _, _, _, _): a.account
        case .song(_, let account, _, _, _, _, _, _): account
        }
    }
    var albumRef: LedgerAlbum? {
        switch self {
        case .album(let a, _, _, _, _, _): a
        case .song(_, _, _, let a, _, _, _, _): a
        }
    }
    var score: Double? {
        switch self {
        case .album(_, _, let s, _, _, _), .song(_, _, _, _, let s, _, _, _): s
        }
    }
    var name: String {
        switch self {
        case .album(let a, _, _, _, _, _): a.name
        case .song(_, _, let t, _, _, _, _, _): t ?? ""
        }
    }
}

/// One timeline row.
enum LedgerTimelineRow: Hashable, Sendable, Identifiable {
    /// A month with marks: its first day and how many.
    case month(Date, count: Int)
    case album(month: Date, item: LedgerItem, end: Bool)
    case note(LedgerEntry)
    /// Empty months folded: [from] the later, [to] the earlier.
    case quiet(from: Date, to: Date)

    var id: String {
        switch self {
        case .month(let d, _): "month:\(d.timeIntervalSince1970)"
        case .album(let m, let item, _): "album:\(m.timeIntervalSince1970):\(item.id)"
        case .note(let e): "note:\(e.id)"
        case .quiet(let f, let t): "quiet:\(f.timeIntervalSince1970):\(t.timeIntervalSince1970)"
        }
    }
}

extension MemoryLedger {
    private static func songKey(_ e: LedgerEntry) -> String {
        "song:\(e.account.key)|" + (e.songId ?? "title:\(e.trackTitle ?? e.id)")
    }

    private static func albumItem(_ album: LedgerAlbum, _ group: [LedgerEntry]) -> LedgerItem {
        let sorted = group.sorted(by: newestFirst)
        func newest(_ k: LedgerKind) -> LedgerEntry? { sorted.first { $0.kind == k } }
        var seen = Set<String>()
        let songs = sorted.filter { $0.kind == .trackRating && $0.score != nil && seen.insert(songKey($0)).inserted }
            .map { LedgerSongScore(title: $0.trackTitle, score: $0.score!, at: $0.at) }
            .sorted { $0.score != $1.score ? $0.score > $1.score : $0.at > $1.at }
        return .album(album: album, title: newest(.title)?.text?.nonBlank, score: newest(.albumRating)?.score,
                      review: newest(.review)?.text?.nonBlank, songs: songs, at: sorted.first?.at ?? 0)
    }

    /// The Cards and List items, newest first: every album with a title, score, review or scored song; every
    /// song with a note (and its score); a score whose album is unknown stands as a song of its own.
    static func items(_ entries: [LedgerEntry]) -> [LedgerItem] {
        var out: [LedgerItem] = []
        let byAlbum = Dictionary(grouping: entries.filter { $0.kind != .songNote && $0.album != nil }) { $0.album!.key }
        for (_, group) in byAlbum { out.append(albumItem(group[0].album!, group)) }
        var scores: [String: LedgerEntry] = [:]
        for s in entries.filter({ $0.kind == .trackRating && $0.score != nil }).sorted(by: newestFirst)
        where scores[songKey(s)] == nil { scores[songKey(s)] = s }
        var noted = Set<String>()
        for (key, notes) in Dictionary(grouping: entries.filter { $0.kind == .songNote }, by: songKey) {
            noted.insert(key)
            let newest = notes.sorted(by: newestFirst)[0]
            let score = scores[key]
            out.append(.song(key: key, account: newest.account, title: newest.trackTitle ?? score?.trackTitle,
                             album: newest.album ?? score?.album, score: score?.score, note: newest, notes: notes.count,
                             at: max(newest.at, score?.at ?? .min)))
        }
        for s in scores.values where s.album == nil && !noted.contains(songKey(s)) {
            out.append(.song(key: songKey(s), account: s.account, title: s.trackTitle, album: nil, score: s.score,
                             note: nil, notes: 0, at: s.at))
        }
        return sorted(out, by: .recent)
    }

    static func sorted(_ items: [LedgerItem], by sort: LedgerSort) -> [LedgerItem] {
        func recent(_ a: LedgerItem, _ b: LedgerItem) -> Bool { a.at != b.at ? a.at > b.at : a.id < b.id }
        switch sort {
        case .recent: return items.sorted(by: recent)
        case .score:
            return items.sorted { a, b in
                if (a.score == nil) != (b.score == nil) { return a.score != nil }
                if (a.score ?? 0) != (b.score ?? 0) { return (a.score ?? 0) > (b.score ?? 0) }
                return recent(a, b)
            }
        case .name:
            return items.sorted { a, b in
                let ab = a.name.trimmingCharacters(in: .whitespaces).isEmpty, bb = b.name.trimmingCharacters(in: .whitespaces).isEmpty
                if ab != bb { return !ab }
                let c = a.name.localizedStandardCompare(b.name)
                return c != .orderedSame ? c == .orderedAscending : recent(a, b)
            }
        }
    }

    /// The timeline from [today]'s month back to the oldest entry's. Empty months fold into one quiet row.
    static func timeline(_ entries: [LedgerEntry], today: Date, calendar cal: Calendar) -> [LedgerTimelineRow] {
        guard !entries.isEmpty else { return [] }
        func month(_ ms: Int64) -> Date {
            cal.date(from: cal.dateComponents([.year, .month], from: Date(timeIntervalSince1970: Double(ms) / 1000)))!
        }
        let byMonth = Dictionary(grouping: entries) { month($0.at) }
        let todayMonth = cal.date(from: cal.dateComponents([.year, .month], from: today))!
        var m = max(todayMonth, byMonth.keys.max()!)
        let oldest = byMonth.keys.min()!
        var rows: [LedgerTimelineRow] = []
        var quiet: (Date, Date)?
        var cards = 0
        while m >= oldest {
            let inMonth = (byMonth[m] ?? []).sorted(by: newestFirst)
            if inMonth.isEmpty {
                quiet = (quiet?.0 ?? m, m)
            } else {
                if let q = quiet { rows.append(.quiet(from: q.0, to: q.1)); quiet = nil }
                rows.append(.month(m, count: inMonth.count))
                let albums = Dictionary(grouping: inMonth.filter { $0.kind != .songNote && $0.album != nil }) { $0.album!.key }
                var shown = Set<String>()
                for e in inMonth {
                    if e.kind == .songNote || e.album == nil {
                        rows.append(.note(e))
                    } else if shown.insert(e.album!.key).inserted {
                        rows.append(.album(month: m, item: albumItem(e.album!, albums[e.album!.key]!), end: cards % 2 == 1))
                        cards += 1
                    }
                }
            }
            m = cal.date(byAdding: .month, value: -1, to: m)!
        }
        if let q = quiet { rows.append(.quiet(from: q.0, to: q.1)) }
        return rows
    }
}

extension String {
    /// Self, unless it is only whitespace.
    var nonBlank: String? { trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : self }
}
