import Foundation

/*
 * Lyric Cards (Android data/highlight/, #180 #189, owner 2026-11-08): a passage of two to four lines the
 * on-device model picked from a song's lyrics. On iOS the model is Apple Intelligence's on-device model
 * (FoundationModels) where Android uses Gemini Nano; the question, the answer's check and which songs get
 * a card are the same, ported here as pure Swift so the contract is unit-tested.
 */

/// The question put to the model, and the check on its answer (Android LyricHighlightPrompt). The model
/// only names a line range; what Yoin shows is always the song's own lines.
enum LyricHighlightPrompt {
    /// One numbered line as sent; [source] is its place in the song (repeats are left out).
    struct Line: Hashable, Sendable {
        var number: Int
        var text: String
        var startMs: Int64?
        var source: Int
    }

    struct Prepared: Hashable, Sendable {
        var lines: [Line]
        var fingerprint: String
        func line(_ n: Int) -> Line? { lines.indices.contains(n - 1) && lines[n - 1].number == n ? lines[n - 1] : nil }
    }

    struct Pick: Hashable, Sendable {
        var first: Line
        var last: Line
        var text: String
        var startMs: Int64? { first.startMs }
    }

    static let minLines = 4
    static let maxLines = 90
    static let minPickLines = 2
    static let maxPickLines = 4
    static let maxPickChars = 110
    /// Part of every fingerprint (Android v4 plus the iOS model's own mark).
    static let promptVersion = "v4i:"
    static let maxOutputTokens = 24

    /// Numbered, de-duplicated sung lines; nil when too little is sung to choose from.
    static func prepare(_ raw: [(text: String, startMs: Int64?)]) -> Prepared? {
        var seen = Set<String>()
        var lines: [Line] = []
        var sung = 0
        for (text, startMs) in raw {
            let clean = text.trimmingCharacters(in: .whitespacesAndNewlines)
            guard isSung(clean) else { continue }
            let source = sung
            sung += 1
            guard seen.insert(normalize(clean)).inserted else { continue }
            lines.append(Line(number: lines.count + 1, text: clean, startMs: startMs, source: source))
            if lines.count == maxLines { break }
        }
        guard lines.count >= minLines else { return nil }
        return Prepared(lines: lines, fingerprint: promptVersion + fingerprint(lines))
    }

    static let instructions =
        "You pick the most memorable passage in a song's lyrics: two to four consecutive lines that read "
        + "as one complete thought, the sentence or image a listener would quote or remember the song by. "
        + "The lines must belong together: never join the end of one thought to the start of another. "
        + "Prefer vivid images and emotional turns over titles repeated, filler (\"oh\", \"yeah\", "
        + "\"la la\"), or plain narration. "
        + "Answer with JSON only, in the form {\"start\": N, \"end\": M}, where N and M are line numbers "
        + "and M is from N+1 to N+3. No other text."

    static func userPrompt(title: String, artist: String, _ p: Prepared) -> String {
        var s = "Song: \(title.trimmingCharacters(in: .whitespaces)) — \(artist.trimmingCharacters(in: .whitespaces))\nLyrics:\n"
        for l in p.lines { s += "\(l.number). \(l.text)\n" }
        return s
    }

    /// Reads the answer: the asked-for JSON and its near-misses (code fences, "7-8"); nil for anything that
    /// isn't a valid range of this song's lines or wouldn't fit a card.
    static func parse(_ answer: String?, _ p: Prepared) -> Pick? {
        guard var body = answer?.trimmingCharacters(in: .whitespacesAndNewlines), !body.isEmpty else { return nil }
        for prefix in ["```json", "```"] where body.hasPrefix(prefix) { body.removeFirst(prefix.count) }
        if body.hasSuffix("```") { body.removeLast(3) }
        body = body.trimmingCharacters(in: .whitespacesAndNewlines)
        func number(after key: String) -> Int? {
            guard let r = body.range(of: "\"?\(key)\"?\\s*[:=]\\s*(\\d{1,3})", options: [.regularExpression, .caseInsensitive])
            else { return nil }
            return Int(body[r].filter(\.isNumber).suffix(3))
        }
        var start = number(after: "start")
        var end = number(after: "end")
        if start == nil {
            guard let r = body.range(of: "^\\s*(\\d{1,3})\\s*([-–,]\\s*(\\d{1,3}))?\\s*$", options: .regularExpression) else { return nil }
            let nums = body[r].split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }
            start = nums.first
            end = nums.count > 1 ? nums[1] : nil
        }
        guard let start else { return nil }
        let asked = end ?? start
        guard asked >= start, let first = p.line(start) else { return nil }
        var picked = [first]
        func echoes(_ l: Line) -> Bool {
            let w = normalize(l.text)
            return w.isEmpty || picked.contains { normalize($0.text).contains(w) }
        }
        var lastSource = first.source
        var n = start + 1
        while picked.count < maxPickLines, n <= asked || picked.count < minPickLines {
            guard let l = p.line(n) else { break }
            n += 1
            guard l.source == lastSource + 1 else { break }
            lastSource = l.source
            if !echoes(l) { picked.append(l) }
        }
        if picked.count < minPickLines, let before = p.line(start - 1), before.source == first.source - 1, !echoes(before) {
            picked.insert(before, at: 0)
        }
        while picked.count > 1, picked.reduce(0, { $0 + $1.text.count }) > maxPickChars { picked.removeLast() }
        if picked.count == 1, picked[0].text.count > maxPickChars { return nil }
        return Pick(first: picked.first!, last: picked.last!, text: picked.map(\.text).joined(separator: "\n"))
    }

    /// Something sung: has a letter and isn't only a bracketed stage note like "(Instrumental)".
    static func isSung(_ text: String) -> Bool {
        guard !text.isEmpty, text.contains(where: \.isLetter) else { return false }
        for (o, c) in [("(", ")"), ("[", "]"), ("（", "）")] where text.hasPrefix(o) && text.hasSuffix(c) { return false }
        return true
    }

    static func normalize(_ text: String) -> String { String(text.lowercased().filter { $0.isLetter || $0.isNumber }) }

    /// FNV-1a over the sent lines: stable across runs, changes whenever the lyric does.
    static func fingerprint(_ lines: [Line]) -> String {
        var h: UInt64 = 0xcbf29ce484222325
        for l in lines {
            for u in l.text.unicodeScalars { h = (h ^ UInt64(u.value)) &* 0x100000001b3 }
            h = (h ^ 10) &* 0x100000001b3
        }
        return String(h, radix: 16)
    }
}

/// Which songs get a card, and which card shows next (Android LyricHighlightSelector): heavy rotation,
/// rediscover and fresh, mixed 5:3:2, plus the user's own picks.
enum LyricHighlightSelector {
    enum Bucket: String, CaseIterable, Codable, Sendable { case heavy, rediscover, fresh, user }

    struct SongSignal: Hashable, Sendable {
        var songId: String
        var playCount: Int
        var lastPlayedAt: Int64
        var heat: Double
        var rating: Double?
        var noteCount: Int
    }

    struct FreshTrack: Hashable, Sendable { var songId: String; var albumRating: Double }
    struct Candidate: Hashable, Sendable { var songId: String; var bucket: Bucket; var score: Double }
    struct Shown: Hashable, Sendable { var songId: String; var lastShownAt: Int64? }

    static let dayMs: Int64 = 86_400_000
    static let heatHalfLifeMs: Int64 = 21 * dayMs
    static let heardFraction = 0.5
    static let heavyMinHeat = 1.5
    static let heavyPool = 40
    static let rediscoverGapMs: Int64 = 60 * dayMs
    static let rediscoverFullGapMs: Int64 = 180 * dayMs
    static let rediscoverMinPlays = 3
    static let rediscoverMinRating = 7.0
    static let reshowCooldownMs: Int64 = 7 * dayMs
    static let mix: [Bucket: Int] = [.heavy: 5, .rediscover: 3, .fresh: 2, .user: 3]

    static func heat(playedAt: Int64, completed: Double, now: Int64) -> Double {
        guard completed >= heardFraction else { return 0 }
        return pow(0.5, Double(max(0, now - playedAt)) / Double(heatHalfLifeMs))
    }

    static func affinity(_ s: SongSignal) -> Double {
        log(1 + Double(s.playCount)) + (s.rating ?? 0) / 2.5 + (s.noteCount > 0 ? 1 : 0)
    }

    static func isRediscover(_ s: SongSignal, now: Int64) -> Bool {
        guard now - s.lastPlayedAt >= rediscoverGapMs else { return false }
        return s.playCount >= rediscoverMinPlays || (s.rating ?? 0) >= rediscoverMinRating || s.noteCount > 0
    }

    static func bucket(of s: SongSignal?, now: Int64) -> Bucket? {
        guard let s, s.playCount > 0 else { return .fresh }
        if s.heat >= heavyMinHeat { return .heavy }
        return isRediscover(s, now: now) ? .rediscover : nil
    }

    static func pools(_ signals: [SongSignal], fresh: [FreshTrack], now: Int64) -> [Bucket: [Candidate]] {
        let heavy = signals.filter { $0.heat >= heavyMinHeat }.sorted { $0.heat > $1.heat }.prefix(heavyPool)
            .map { Candidate(songId: $0.songId, bucket: .heavy, score: $0.heat) }
        let heavyIds = Set(heavy.map(\.songId))
        let rediscover = signals.filter { !heavyIds.contains($0.songId) && isRediscover($0, now: now) }
            .map { s in
                let gap = Double(min(now - s.lastPlayedAt, rediscoverFullGapMs)) / Double(rediscoverFullGapMs)
                return Candidate(songId: s.songId, bucket: .rediscover, score: affinity(s) * gap)
            }
            .sorted { $0.score > $1.score }
        let played = Set(signals.filter { $0.playCount > 0 }.map(\.songId))
        var seen = Set<String>()
        let freshC = fresh.filter { !played.contains($0.songId) && seen.insert($0.songId).inserted }
            .sorted { $0.albumRating > $1.albumRating }
            .map { Candidate(songId: $0.songId, bucket: .fresh, score: $0.albumRating) }
        return [.heavy: Array(heavy), .rediscover: rediscover, .fresh: freshC]
    }

    /// The next songs for the model, interleaved to the mix (a kind that runs dry gives its turns away).
    static func processingQueue(_ pools: [Bucket: [Candidate]], skip: Set<String>, limit: Int) -> [Candidate] {
        var remaining = pools.mapValues { $0.filter { !skip.contains($0.songId) }[...] }
        var credit: [Bucket: Double] = [:]
        var taken = Set<String>()
        var out: [Candidate] = []
        while out.count < limit {
            let live = Bucket.allCases.filter { !(remaining[$0]?.isEmpty ?? true) }
            guard !live.isEmpty else { break }
            let total = Double(live.reduce(0) { $0 + mix[$1]! })
            for b in live { credit[b, default: 0] += Double(mix[b]!) }
            let b = live.max { credit[$0, default: 0] < credit[$1, default: 0] }!
            credit[b, default: 0] -= total
            let next = remaining[b]!.removeFirst()
            if taken.insert(next.songId).inserted { out.append(next) }
        }
        return out
    }

    /// The card to show next: a kind drawn by the mix, then the least recently shown of it; anything shown
    /// in the last week waits while something else is there. [exclude] is the song playing now.
    static func nextToShow(_ shown: [Shown], bucketOf: (String) -> Bucket?, now: Int64, exclude: String? = nil,
                           using rng: inout some RandomNumberGenerator) -> Shown? {
        let pool = shown.filter { $0.songId != exclude }
        guard !pool.isEmpty else { return nil }
        let rested = pool.filter { $0.lastShownAt == nil || now - $0.lastShownAt! >= reshowCooldownMs }
        let eligible = rested.isEmpty ? pool : rested
        let byBucket = Dictionary(grouping: eligible) { bucketOf($0.songId) }
        let kinds = Bucket.allCases.filter { !(byBucket[$0]?.isEmpty ?? true) }
        let group: [Shown]
        if kinds.isEmpty {
            group = eligible
        } else {
            var roll = Int.random(in: 0..<kinds.reduce(0) { $0 + mix[$1]! }, using: &rng)
            let kind = kinds.first { roll -= mix[$0]!; return roll < 0 }!
            group = byBucket[kind]!
        }
        let oldest = group.map { $0.lastShownAt ?? .min }.min()!
        let ties = group.filter { ($0.lastShownAt ?? .min) == oldest }
        return ties.randomElement(using: &rng)
    }
}

/// How much on-device inference the cards may spend (Android NanoBudget): 40 songs a day, 5 a visit, 8 s
/// apart; the model saying it's rate limited backs off 30 s, 2 min, 10 min.
struct LyricHighlightBudget: Codable, Hashable, Sendable {
    var day: Int64 = 0
    var usedToday = 0
    var pausedUntil: Int64 = 0
    var busyStreak = 0

    static let dailyLimit = 40
    static let perVisitLimit = 5
    static let gapMs: Int64 = 8_000
    static let busyBackoffMs: [Int64] = [30_000, 120_000, 600_000]

    func rolled(_ today: Int64) -> Self {
        today == day ? self : Self(day: today, usedToday: 0, pausedUntil: pausedUntil, busyStreak: 0)
    }

    func allows(now: Int64, today: Int64) -> Bool {
        let c = rolled(today)
        return c.usedToday < Self.dailyLimit && now >= c.pausedUntil
    }

    func afterSuccess(today: Int64) -> Self {
        var c = rolled(today)
        c.usedToday += 1
        c.busyStreak = 0
        return c
    }

    func afterBusy(now: Int64, today: Int64) -> Self {
        var c = rolled(today)
        c.pausedUntil = now + Self.busyBackoffMs[min(c.busyStreak, Self.busyBackoffMs.count - 1)]
        c.busyStreak += 1
        return c
    }

    static func dayOf(_ epochMs: Int64, utcOffsetSec: Int) -> Int64 {
        let local = epochMs + Int64(utcOffsetSec) * 1000
        return local >= 0 ? local / 86_400_000 : (local - 86_399_999) / 86_400_000
    }
}
