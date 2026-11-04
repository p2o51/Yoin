import Foundation

/// A plain album cover's memory mark (Android `HomeAlbumMemoryMark`): solid =
/// an album rating, a line = your track average, dashed = notes only.
enum AlbumMemoryMark: Hashable, Sendable { case solid, outline, dashed }

/// A Jump Back In 1×2 (Android `HomeWidgetCard` with `expanded = true`): the
/// memory album or the noted track.
struct JBISignal: Hashable, Sendable {
    enum Kind: Hashable, Sendable { case ratedAlbum, notedTrack }
    var kind: Kind
    /// Cover, palette and where a tap goes (an album card opens it).
    var album: Album
    var title: String
    var subtitle: String
    /// "%.1f", or "N/A".
    var score: String
    /// A date ("Nov 1") or "Based on 4/10 tracks"; nil hides it.
    var basis: String?
    /// The note (noted track); a memory title would go here (none on iOS yet).
    var comment: String?
    /// Noted track: the song a tap plays.
    var song: PlaylistEntry?
}

/// An album's local memory (Android `AlbumMemoryCandidate`, minus the
/// review / album note / Ask AI parts iOS doesn't keep yet).
struct AlbumMemory: Hashable, Sendable {
    var album: Album
    var totalTracks: Int
    var ratedTrackCount: Int
    var coverage: Double
    var averageSongRating: Double?
    /// The album's own score: the server's album rating (iOS keeps no local
    /// album rating).
    var albumRating: Double?
    var noteCount: Int
    /// Newest note written on it (ratings carry no time here).
    var lastWrittenAt: Int64?
    var playCount: Int
    var firstPlayedAt: Int64?
    var lastPlayedAt: Int64?
    var isMemoryEligible: Bool

    var albumId: String { album.id.rawId }
}

/// Home's history-driven parts, built from the local stores, ported from
/// Android (HomeViewModel, data/memory/RediscoverSelection.kt,
/// HomeAlbumMemoryMark.kt, HomeEditorialContent.formatTimeAgo). Pure.
enum HomeHistory {
    static let dayMs: Int64 = 86_400_000
    /// REDISCOVER_AWAY_MS: 90 days unplayed in Yoin.
    static let awayMs: Int64 = 90 * dayMs
    static let coverageGate = 0.6
    static let noteCountGate = 2
    static let memoryMinTrackCount = 4
    static let rediscoverLimit = 6
    static let noteShare = 1.0 / 3.0

    struct Inputs {
        var now: Int64
        /// The account's plays (any order).
        var plays: [PlayEvent]
        /// The account's notes (this provider).
        var notes: [SongNote]
        /// Loaded album details by raw id (track lists, server ratings).
        var details: [String: Album]
        /// List albums by raw id (counts, server album ratings).
        var known: [String: Album]
        /// A song's local rating (0–10).
        var localRating: (String) -> Double?
        /// The album for [id]: the app's copy, else one made from the play's snapshot.
        var resolve: (_ albumId: String, _ snapshot: PlayEvent?) -> Album?
        /// An album's own rating (Android `album_ratings`, 0–10; AlbumMemoryStore).
        var localAlbumRating: (String) -> Double? = { _ in nil }
        /// Albums with an album rating or review here (Android seeds Memories from `album_ratings`).
        var ratedAlbumIds: [String] = []
    }

    // MARK: Memories

    /// Every album with plays, or with a rated / noted track the app knows
    /// the album of, in the builder's order (coverage, notes, then newest play).
    static func memories(_ x: Inputs) -> [AlbumMemory] {
        let stats = HistoryStore.albumStats(x.plays)
        // song → album: the plays first, then loaded track lists.
        var songAlbum: [String: String] = [:]
        var songsOf: [String: Set<String>] = [:]
        for p in x.plays { if let a = p.albumId { songAlbum[p.songId] = a; songsOf[a, default: []].insert(p.songId) } }
        for (id, album) in x.details {
            for t in album.tracks {
                songAlbum[t.id.rawId] = songAlbum[t.id.rawId] ?? id
                songsOf[id, default: []].insert(t.id.rawId)
            }
        }
        var notesOf: [String: (count: Int, last: Int64)] = [:]
        for n in x.notes where !n.content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            guard let a = songAlbum[n.trackId] else { continue }
            let prev = notesOf[a] ?? (0, 0)
            notesOf[a] = (prev.count + 1, max(prev.last, n.updatedAt))
        }
        // Android AlbumMemoryCandidateBuilder: local_ratings and album_ratings only, never the server's stars
        // (the album page's Bun reads the same: AlbumBun).
        // A stored 0 is a rating (nil = never rated).
        func rating(_ song: String) -> Double? {
            x.localRating(song)
        }
        var ids = Set(stats.keys).union(notesOf.keys).union(x.ratedAlbumIds)
        for (id, songs) in songsOf where songs.contains(where: { rating($0) != nil }) { ids.insert(id) }

        var out: [AlbumMemory] = []
        for id in ids {
            guard let album = x.details[id] ?? x.resolve(id, stats[id]?.latest) else { continue }
            let songs = songsOf[id] ?? []
            let rated = songs.compactMap(rating)
            let detail = x.details[id]
            let total = detail.map { max($0.tracks.count, $0.songCount ?? 0) }
                ?? x.known[id]?.songCount ?? album.songCount ?? 0
            let coverage = total > 0 ? Double(rated.count) / Double(total) : 0
            let notes = notesOf[id]
            let albumRating = x.localAlbumRating(id).flatMap { $0 > 0 ? $0 : nil }
            let noteCount = notes?.count ?? 0
            let eligible = (coverage >= coverageGate || noteCount >= noteCountGate)
                && (total == 0 || total >= memoryMinTrackCount)
            let s = stats[id]
            out.append(AlbumMemory(album: album, totalTracks: total, ratedTrackCount: rated.count, coverage: coverage,
                                   averageSongRating: rated.isEmpty ? nil : rated.reduce(0, +) / Double(rated.count),
                                   albumRating: albumRating, noteCount: noteCount, lastWrittenAt: notes?.last,
                                   playCount: s?.playCount ?? 0, firstPlayedAt: s?.firstPlayedAt,
                                   lastPlayedAt: s?.lastPlayedAt, isMemoryEligible: eligible))
        }
        return out.sorted { a, b in
            if a.coverage != b.coverage { return a.coverage > b.coverage }
            if a.noteCount != b.noteCount { return a.noteCount > b.noteCount }
            let ta = a.lastPlayedAt ?? a.firstPlayedAt ?? 0, tb = b.lastPlayedAt ?? b.firstPlayedAt ?? 0
            if ta != tb { return ta > tb }
            return a.albumId < b.albumId
        }
    }

    /// `albumMemoryMarkOf`: album rating, else the track average, else notes.
    static func mark(_ m: AlbumMemory) -> AlbumMemoryMark? {
        if m.albumRating != nil { return .solid }
        if let avg = m.averageSongRating, avg > 0, m.ratedTrackCount > 0 { return .outline }
        if m.noteCount > 0 { return .dashed }
        return nil
    }

    static func marks(_ memories: [AlbumMemory]) -> [String: AlbumMemoryMark] {
        var out: [String: AlbumMemoryMark] = [:]
        for m in memories { if let k = mark(m) { out[m.albumId] = k } }
        return out
    }

    // MARK: Activities

    /// The activity log as album cards, newest first, one per album: album
    /// rows by their id, song rows by their song's album. Artist and playlist
    /// rows have no card on the phone preset yet.
    static func activityAlbums(_ records: [ActivityRecord], resolve: (String, PlayEvent?) -> Album?,
                               limit: Int = 4) -> [(album: Album, at: Int64)] {
        var seen = Set<String>()
        var out: [(Album, Int64)] = []
        for r in records.sorted(by: { $0.timestamp > $1.timestamp }) where out.count < limit {
            let id: String? = r.entity == .album ? r.entityId : r.entity == .song ? r.albumId : nil
            guard let id, seen.insert(id).inserted else { continue }
            let snap = PlayEvent(id: r.id, scope: r.scope, provider: r.provider, songId: r.songId ?? "", albumId: id,
                                 artistId: r.artistId, title: r.title,
                                 album: r.entity == .album ? r.title : "", artist: r.subtitle,
                                 coverKey: r.coverKey, startedAt: r.timestamp, durationSec: 0)
            if let album = resolve(id, snap) { out.append((album, r.timestamp)) }
        }
        return out
    }

    // MARK: Jump Back In 1×2s

    /// `pickJbiMemoryCandidate` (no reviews on iOS): the first eligible album
    /// with an album rating or a track average.
    static func ratedAlbumSignal(_ memories: [AlbumMemory]) -> JBISignal? {
        guard let m = memories.first(where: { $0.isMemoryEligible && ($0.albumRating != nil || $0.averageSongRating != nil) })
        else { return nil }
        let trackBasis = m.ratedTrackCount > 0 && m.totalTracks > 0
        return JBISignal(kind: .ratedAlbum, album: m.album, title: m.album.title, subtitle: m.album.artist,
                         score: scoreText(m.albumRating ?? m.averageSongRating),
                         basis: trackBasis ? "Based on \(m.ratedTrackCount)/\(m.totalTracks) tracks" : nil,
                         comment: nil, song: nil)
    }

    /// `loadNotedTrackCards`' primary: the most recently touched note, with
    /// the song's rating and its newest play (cover, album, duration).
    static func notedTrackSignal(_ x: Inputs, placeholder: (SongNote) -> Album) -> JBISignal? {
        let words = x.notes.filter { !$0.content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        guard let note = words.max(by: { ($0.updatedAt, $0.createdAt) < ($1.updatedAt, $1.createdAt) }) else { return nil }
        let play = x.plays.filter { $0.songId == note.trackId }.max { $0.startedAt < $1.startedAt }
        let album = play?.albumId.flatMap { x.details[$0] ?? x.resolve($0, play) } ?? placeholder(note)
        let rating = x.localRating(note.trackId)
        var track = album.tracks.first { $0.id.rawId == note.trackId }
            ?? Track(id: MediaId(provider: note.provider, rawId: note.trackId), number: 1, title: note.title,
                     durationSec: max(1, Int((play?.durationSec ?? 0).rounded())), rating: rating,
                     artist: note.artist.isEmpty ? nil : note.artist)
        track.title = track.title.isEmpty ? note.title : track.title
        return JBISignal(kind: .notedTrack, album: album, title: note.title,
                         subtitle: note.artist.isEmpty ? album.artist : note.artist,
                         score: scoreText(rating), basis: memoryDate(note.updatedAt), comment: note.content,
                         song: PlaylistEntry(album: album, track: track))
    }

    // MARK: Rediscover (RediscoverSelection.kt, album cards)

    struct RediscoverPick: Hashable {
        var memory: AlbumMemory
        var score: Double?
        var lastPlayedAt: Int64
        var fromNotes: Bool
    }

    static func hasMemory(_ m: AlbumMemory) -> Bool {
        (m.albumRating ?? 0) > 0 || m.noteCount > 0 || m.ratedTrackCount > 0
    }

    static func meetsTrackCount(_ m: AlbumMemory) -> Bool {
        m.isMemoryEligible || m.totalTracks == 0 || m.totalTracks >= memoryMinTrackCount
    }

    static func rediscoverScore(_ m: AlbumMemory) -> Double? {
        if let r = m.albumRating, r > 0 { return r }
        return m.coverage >= coverageGate ? m.averageSongRating : nil
    }

    private static func ordered(_ a: RediscoverPick, _ b: RediscoverPick) -> Bool {
        if (a.score == nil) != (b.score == nil) { return a.score != nil }
        if (a.score ?? 0) != (b.score ?? 0) { return (a.score ?? 0) > (b.score ?? 0) }
        return a.lastPlayedAt < b.lastPlayedAt
    }

    /// `selectRediscover`: a memory, enough tracks, 90+ days since its last play.
    static func selectRediscover(_ memories: [AlbumMemory], now: Int64, exclude: Set<String>) -> [RediscoverPick] {
        memories.compactMap { m -> RediscoverPick? in
            guard let last = m.lastPlayedAt, hasMemory(m), meetsTrackCount(m), now - last >= awayMs else { return nil }
            return RediscoverPick(memory: m, score: rediscoverScore(m), lastPlayedAt: last, fromNotes: false)
        }
        .sorted(by: ordered)
        .filter { !exclude.contains($0.memory.albumId) }
    }

    /// `selectRediscoverNotes`: written about, never played in Yoin, last
    /// written 90+ days ago.
    static func selectRediscoverNotes(_ memories: [AlbumMemory], now: Int64, exclude: Set<String>) -> [RediscoverPick] {
        memories.filter { m in
            m.lastPlayedAt == nil && hasMemory(m) && meetsTrackCount(m) && now - (m.lastWrittenAt ?? 0) >= awayMs
        }
        .map { RediscoverPick(memory: $0, score: rediscoverScore($0), lastPlayedAt: 0, fromNotes: true) }
        .sorted { a, b in
            if (a.score == nil) != (b.score == nil) { return a.score != nil }
            if (a.score ?? 0) != (b.score ?? 0) { return (a.score ?? 0) > (b.score ?? 0) }
            return a.memory.noteCount > b.memory.noteCount
        }
        .filter { !exclude.contains($0.memory.albumId) }
    }

    /// `mixRediscoverShelf`: every third card a note card, either kind
    /// filling the other's places when it runs short.
    static func mix(_ listening: [RediscoverPick], _ notes: [RediscoverPick], limit: Int = rediscoverLimit) -> [RediscoverPick] {
        guard !notes.isEmpty, limit > 0 else { return Array(listening.prefix(limit)) }
        let share = max(1, Int((Double(limit) * noteShare).rounded()))
        let listeningCount = min(listening.count, limit - min(share, notes.count))
        let noteCount = min(notes.count, limit - listeningCount)
        var shelf: [RediscoverPick] = []
        var l = 0, n = 0
        while l < listeningCount || n < noteCount {
            let noteTurn = n < noteCount && (l >= listeningCount || shelf.count % 3 == 2)
            if noteTurn { shelf.append(notes[n]); n += 1 } else { shelf.append(listening[l]); l += 1 }
        }
        return shelf
    }

    /// The shelf as cards: the reason line only with no score (or on a note
    /// card), the away line except on a note card, then first-play month and
    /// plays in Yoin.
    static func rediscover(_ memories: [AlbumMemory], now: Int64, exclude: Set<String>) -> [RediscoverEntry] {
        let shelf = mix(selectRediscover(memories, now: now, exclude: exclude),
                        selectRediscoverNotes(memories, now: now, exclude: exclude))
        return shelf.map { p in
            let m = p.memory
            return RediscoverEntry(album: m.album, reason: rediscoverReason(p) ?? "",
                                   away: p.fromNotes ? "" : awayEyebrow(lastPlayedAt: p.lastPlayedAt, now: now),
                                   score: p.score, footnoteDate: m.firstPlayedAt.map(yearMonth) ?? "",
                                   footnotePlays: m.playCount > 0 ? "\(m.playCount)" : "")
        }
    }

    /// `rediscoverReason`: what was kept, when no badge says it.
    static func rediscoverReason(_ p: RediscoverPick) -> String? {
        if p.score != nil && !p.fromNotes { return nil }
        let m = p.memory
        if m.noteCount > 0 { return m.noteCount == 1 ? "1 note" : "\(m.noteCount) notes" }
        if m.ratedTrackCount > 0 { return m.ratedTrackCount == 1 ? "1 track rated" : "\(m.ratedTrackCount) tracks rated" }
        return nil
    }

    // MARK: Copy (Android strings, English)

    /// `formatTimeAgo`: "just now", "12m ago", "2h ago", "3d ago", "2w ago".
    static func timeAgo(_ ms: Int64, now: Int64) -> String {
        let minutes = max(0, now - ms) / 60_000
        let hours = minutes / 60, days = hours / 24
        if minutes < 1 { return "just now" }
        if minutes < 60 { return "\(minutes)m ago" }
        if hours < 24 { return "\(hours)h ago" }
        if days < 7 { return "\(days)d ago" }
        return "\(days / 7)w ago"
    }

    /// "7 months away" / "1 year away" (months = days / 30, years from 365 days).
    static func awayEyebrow(lastPlayedAt: Int64, now: Int64) -> String {
        let days = max(0, now - lastPlayedAt) / dayMs
        if days >= 365 {
            let y = days / 365
            return y == 1 ? "1 year away" : "\(y) years away"
        }
        let m = max(1, days / 30)
        return m == 1 ? "1 month away" : "\(m) months away"
    }

    /// The seal's "%.1f", "N/A" without a score (0 is a score: "0.0").
    static func scoreText(_ r: Double?) -> String {
        guard let r else { return "N/A" }
        return String(format: "%.1f", locale: Locale(identifier: "en_US_POSIX"), r)
    }

    private static func formatter(_ pattern: String) -> DateFormatter {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = pattern
        return f
    }

    static func date(_ ms: Int64) -> Date { Date(timeIntervalSince1970: Double(ms) / 1000) }

    /// "Nov 1" (MemoryDateFormatter).
    static func memoryDate(_ ms: Int64) -> String { formatter("MMM d").string(from: date(ms)) }

    /// "2024.03".
    static func yearMonth(_ ms: Int64) -> String { formatter("yyyy.MM").string(from: date(ms)) }

    /// Whole calendar days between two moments (0 on the same day).
    static func calendarDays(from: Int64, to: Int64, calendar: Calendar = .current) -> Int {
        let a = calendar.startOfDay(for: date(from)), b = calendar.startOfDay(for: date(to))
        return max(0, calendar.dateComponents([.day], from: a, to: b).day ?? 0)
    }

    /// The album page's Last Play (`albumLastPlayLabels`): ("Yesterday", "16:04").
    static func lastPlayLabels(_ ms: Int64, now: Int64, calendar: Calendar = .current) -> (day: String, time: String) {
        (relativeDay(ms, now: now, calendar: calendar), {
            let f = formatter("HH:mm")
            f.timeZone = calendar.timeZone
            return f.string(from: date(ms))
        }())
    }

    /// "Today" / "Yesterday" / "3 days ago" / "Mar 14" / "Mar 14, 2024".
    static func relativeDay(_ ms: Int64, now: Int64, calendar: Calendar = .current) -> String {
        let days = calendarDays(from: ms, to: now, calendar: calendar)
        if days == 0 { return "Today" }
        if days == 1 { return "Yesterday" }
        if days < 7 { return "\(days) days ago" }
        let sameYear = calendar.component(.year, from: date(ms)) == calendar.component(.year, from: date(now))
        let f = formatter(sameYear ? "MMM d" : "MMM d, yyyy")
        f.timeZone = calendar.timeZone
        return f.string(from: date(ms))
    }
}
