import Foundation

/// Android PlayHistoryDao.getArtistTopSongs and YoinRepository.getArtistListening.
enum MacArtistListening {
    struct Song {
        var latest: PlayEvent
        var playCount: Int
        var durationSec: Double
    }

    static func topSongs(plays: [PlayEvent], account: String, provider: String,
                         albumIds: Set<String>, artistName: String) -> [Song] {
        guard !account.isEmpty else { return [] }
        let mine = plays.filter {
            $0.scope == account && $0.provider == provider
                && (albumIds.contains($0.albumId ?? "") || $0.artist == artistName)
        }
        return Dictionary(grouping: mine, by: \.songId).values.compactMap { rows -> Song? in
            guard let latest = rows.max(by: { $0.startedAt < $1.startedAt }) else { return nil }
            return Song(latest: latest, playCount: rows.count, durationSec: rows.map(\.durationSec).max() ?? 0)
        }.sorted {
            if $0.playCount != $1.playCount { return $0.playCount > $1.playCount }
            if $0.latest.startedAt != $1.latest.startedAt { return $0.latest.startedAt > $1.latest.startedAt }
            return $0.latest.songId < $1.latest.songId
        }.prefix(5).map { $0 }
    }
}
