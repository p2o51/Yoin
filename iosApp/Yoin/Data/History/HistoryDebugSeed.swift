#if DEBUG
import Foundation

extension LibraryStore {
    /// Once per process: a debug launch can build more than one AppModel.
    private static var didSeed = false

    /// Debug builds: `-historySeed demo` replaces the signed-in account's
    /// history with backdated plays over its first albums, plus local track
    /// ratings and one note, so Home's history parts can be screenshotted
    /// without months of listening. Never in release builds.
    ///
    /// Albums (server order: newest, recent, random): 0–3 played in the last
    /// days (Activities); 4 rated on every track, 5 months away (the rated
    /// 1×2); 5 two tracks rated, 14 months away; 6 rated on 70 %, 4 months
    /// away; 7 one note, 6 months away (Rediscover; the note is the noted 1×2).
    func seedHistory(_ mode: String) async {
        guard mode == "demo", isReal, !Self.didSeed else { return }
        Self.didSeed = true
        // Let the sign-in's reset land first (it would drop the lists loaded here).
        try? await Task.sleep(for: .milliseconds(300))
        await loadHome(force: true)
        guard let l = homeLists else { return }
        var ids: [String] = []
        for a in l.newest + l.recent + l.random where !ids.contains(a.id.rawId) { ids.append(a.id.rawId) }
        var albums: [Album] = []
        for id in ids.prefix(8) {
            await loadAlbum(id)
            if let a = album(id), !a.tracks.isEmpty { albums.append(a) }
        }
        guard albums.count >= 4 else { return }
        history.removeAll(scope: historyScope, provider: provider)
        let now = history.nowMs
        let minute: Int64 = 60_000, day = HomeHistory.dayMs

        func play(_ a: Album, _ track: Int, at ms: Int64) {
            let t = a.tracks[track % a.tracks.count]
            let id = history.recordPlay(scope: historyScope, provider: provider, songId: t.id.rawId, albumId: a.id.rawId,
                                        artistId: a.artistId, title: t.title, album: a.title,
                                        artist: t.artist ?? a.artist,
                                        coverKey: Self.coverKey(a.coverURL, provider: provider),
                                        durationSec: Double(t.durationSec), at: ms)
            history.markListened(playId: id, listenedSec: Double(t.durationSec), completed: true)
        }
        func rate(_ a: Album, fraction: Double, base: Double) {
            let n = Int((Double(a.tracks.count) * fraction).rounded(.up))
            for (i, t) in a.tracks.prefix(n).enumerated() {
                ratings.set(min(10, base + Double(i % 3) * 0.5), provider: provider, songId: t.id.rawId)
            }
        }

        // Activities: newest first is album 0.
        let recent: [Int64] = [12 * minute, 2 * 60 * minute, day + 3 * 60 * minute, 3 * day + 5 * 60 * minute]
        for (i, ago) in recent.enumerated() where i < albums.count {
            play(albums[i], 1, at: now - ago - 4 * minute)
            play(albums[i], 2, at: now - ago)
        }
        if albums.count > 4 {
            let a = albums[4]
            for k in 0..<3 { play(a, k, at: now - (240 - Int64(k) * 30) * day) }
            play(a, 3, at: now - 150 * day)
            rate(a, fraction: 1, base: 8)
        }
        if albums.count > 5 {
            let a = albums[5]
            play(a, 0, at: now - 520 * day)
            play(a, 1, at: now - 430 * day)
            rate(a, fraction: 2 / Double(max(a.tracks.count, 2)) - 0.01, base: 7)
        }
        if albums.count > 6 {
            let a = albums[6]
            for k in 0..<4 { play(a, k, at: now - (200 - Int64(k) * 20) * day) }
            rate(a, fraction: 0.7, base: 8.5)
        }
        if albums.count > 7 {
            let a = albums[7]
            play(a, 0, at: now - 260 * day)
            play(a, 2, at: now - 185 * day)
            let t = a.tracks[2 % a.tracks.count]
            if notes.notes(provider: provider, trackId: t.id.rawId).isEmpty {
                notes.add(provider: provider, trackId: t.id.rawId,
                          content: "The strings come in under the second verse and never leave",
                          positionMs: 64_000, title: t.title, artist: t.artist ?? a.artist)
            }
            ratings.set(8, provider: provider, songId: t.id.rawId)
        }
    }
}
#endif
