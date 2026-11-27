import Foundation
import YoinShared

/// The three listening cues Home's greetings are written around, read from this device's history (Android
/// HomeGreetingStore.loadSlotCues over Room): the latest play, the week's most-played other artist, and a rated or
/// noted song not heard for 14+ days (else an album not played for 3+ days). The picks are `:shared`'s
/// `YoinOnDeviceAi.pickCues`; this gathers its rows the way Android's queries do.
enum HomeGreetingCues {
    @MainActor
    static func load(account: LedgerAccount, now: Int64) -> HomeGreetingPrompt.SlotCues {
        let scope = MemoryLedgerSource.historyScope(account)
        let history = HistoryStore.shared
        let notes = NotesStore.shared.notes.filter {
            $0.provider == account.provider && !$0.content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
        let ratings = LocalRatings.shared
        return cues(recent: history.recentPlays(scope: scope, provider: account.provider,
                                                limit: Int(YoinOnDeviceAi.shared.recentHistoryLimit)),
                    plays: history.plays(scope: scope, provider: account.provider),
                    rating: { ratings.rating(provider: account.provider, songId: $0) },
                    noteCount: Dictionary(grouping: notes, by: \.trackId).mapValues(\.count), now: now)
    }

    /// [recent] newest first (`getRecentHistory`), [plays] every play of the account.
    static func cues(recent: [PlayEvent], plays: [PlayEvent], rating: (String) -> Double?, noteCount: [String: Int],
                     now: Int64) -> HomeGreetingPrompt.SlotCues {
        let ai = YoinOnDeviceAi.shared
        return ai.pickCues(recent: recent.map { ai.cuePlay(artist: $0.artist, album: $0.album, playedAt: $0.startedAt) },
                           rediscover: rediscover(plays: plays, rating: rating, noteCount: noteCount, now: now),
                           albums: albums(plays: plays), now: now)
    }

    /// Android `getRediscoverSongs`: songs with a rating or a note, last played at least 14 days ago; rated first
    /// (best first), then the longest unplayed; each as its newest play names it.
    static func rediscover(plays: [PlayEvent], rating: (String) -> Double?, noteCount: [String: Int],
                           now: Int64) -> [HomeGreetingPrompt.RecentPlay] {
        let ai = YoinOnDeviceAi.shared
        let cutoff = now - ai.rediscoverAgeMs
        var latest: [String: PlayEvent] = [:]
        for p in plays where (latest[p.songId]?.startedAt ?? .min) < p.startedAt { latest[p.songId] = p }
        let picked = latest.values.compactMap { p -> (PlayEvent, Double?)? in
            let r = rating(p.songId)
            guard r != nil || (noteCount[p.songId] ?? 0) > 0, p.startedAt <= cutoff else { return nil }
            return (p, r)
        }
        .sorted { a, b in
            switch (a.1, b.1) {
            case let (x?, y?) where x != y: return x > y
            case (.some, nil): return true
            case (nil, .some): return false
            default: return a.0.startedAt != b.0.startedAt ? a.0.startedAt < b.0.startedAt : a.0.songId < b.0.songId
            }
        }
        return picked.prefix(Int(ai.rediscoverLimit)).map { ai.recentPlay(artist: $0.0.artist, album: $0.0.album) }
    }

    /// Android `getAlbumAggregates`: albums by their newest play, newest first, named by that play.
    static func albums(plays: [PlayEvent]) -> [HomeGreetingSchedule.CueAlbum] {
        let ai = YoinOnDeviceAi.shared
        var latest: [String: PlayEvent] = [:]
        for p in plays {
            guard let id = p.albumId, !id.isEmpty else { continue }
            if (latest[id]?.startedAt ?? .min) < p.startedAt { latest[id] = p }
        }
        return latest.values
            .sorted { $0.startedAt != $1.startedAt ? $0.startedAt > $1.startedAt : ($0.albumId ?? "") < ($1.albumId ?? "") }
            .prefix(Int(ai.albumAggregateLimit))
            .map { ai.cueAlbum(artist: $0.artist, album: $0.album, lastPlayedAt: $0.startedAt) }
    }
}
