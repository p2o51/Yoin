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
    /// [again]: a second account in the same run (`-profilesScript seed:<mode>`).
    func seedHistory(_ mode: String, again: Bool = false) async {
        if mode == "promo" { return await seedPromo(again: again) }
        guard mode == "demo", isReal, again || !Self.didSeed else { return }
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

    /// `-historySeed promo`: the promo demo server's memories, as Android's promo kit seeds them
    /// (Yoin-archive/promo-demo-2026-11-02/seed.py): plays, track and album ratings, reviews, titles
    /// and timed notes on the same albums, so the two platforms' Memories can be shot side by side.
    private func seedPromo(again: Bool = false) async {
        guard isReal, again || !Self.didSeed else { return }
        Self.didSeed = true
        try? await Task.sleep(for: .milliseconds(300))
        let now = history.nowMs, day = HomeHistory.dayMs, hour: Int64 = 3_600_000
        // album id, plays, days ago of the newest, track ratings, album rating, review, title
        let albums: [(String, Int, Int64, [Double], Double, String?, String)] = [
            ("al-harbor", 9, 0, [8.2, 9.4, 7.6, 8.8, 8.1, 7.9, 6.8, 8.5, 9.1, 8.3], 8.7,
             "Sounds like a warm evening by the water. \"Paper Lanterns\" is the one I keep coming back to; the second chorus gets me every time.",
             "港口的灯一盏盏亮起"),
            ("al-yehang", 8, 1, [9.6, 8.7, 9.0, 7.4, 8.2, 7.8, 8.9], 9.2,
             "适合夜里一个人坐车的时候听。《夜航》最后那句“晚安，我的远方”太温柔了，整张专辑像一封写给故乡的长信。",
             "写给远方的晚安"),
            ("al-soda", 5, 3, [9.0, 8.4, 7.7, 8.0], 8.4, "夏天的汽水味。Summer Line 一响就想去海边。", "汽水味的夏天"),
            ("al-glass", 4, 6, [7.5, 8.6, 7.1, 6.9, 8.0], 7.8, nil, "Neon in the orchard"),
            ("al-velvet", 3, 9, [7.8, 7.0, 6.5, 7.6, 8.3], 7.3, "Slow Dance in a Fast Car is a perfect title.",
             "Slow dances, fast cars"),
        ]
        // track index, position, words (Android's song notes on these albums)
        let timed: [String: [(Int, Int64, String)]] = [
            "al-harbor": [(1, 21_000, "The opening synth sounds like a lighthouse blinking."),
                          (1, 68_000, "这句歌词写得太好了：every light a word I never said"),
                          (1, 142_000, "第二段副歌的和声，耳机里会从左边绕到右边。"),
                          (8, 120_000, "Title track, saved for the end.")],
            "al-yehang": [(0, 30_000, "前奏的口琴，像远处的船笛。"), (0, 96_000, "“数到第七颗 就天亮”"),
                          (0, 171_000, "最后一句 live 版唱得更轻。"), (2, 60_000, "北方的信，写给一个再也没见过的人。")],
            "al-soda": [(0, 45_000, "ベースライン最高")],
            "al-glass": [(1, 80_000, "Guitar solo at 1:20 — turn it up.")],
        ]
        history.removeAll(scope: historyScope, provider: provider)
        let store = AlbumMemoryStore.shared
        for (id, plays, ago, rated, rating, review, title) in albums {
            await loadAlbum(id)
            guard let a = album(id), !a.tracks.isEmpty else { continue }
            for k in 0..<plays {
                let t = a.tracks[k % a.tracks.count]
                let at = now - ago * day - Int64(k) * 7 * day - Int64(k % 3 + 1) * hour
                let p = history.recordPlay(scope: historyScope, provider: provider, songId: t.id.rawId, albumId: id,
                                           artistId: a.artistId, title: t.title, album: a.title, artist: t.artist ?? a.artist,
                                           coverKey: Self.coverKey(a.coverURL, provider: provider),
                                           durationSec: Double(t.durationSec), at: at)
                history.markListened(playId: p, listenedSec: Double(t.durationSec), completed: true)
            }
            for (t, r) in zip(a.tracks, rated) { ratings.set(r, provider: provider, songId: t.id.rawId) }
            store.putAlbum(.init(provider: provider, albumId: id, rating: rating, review: review,
                                 reviewUpdatedAt: review == nil ? nil : now - 4 * day, updatedAt: now - 4 * day))
            store.putTitle(.init(provider: provider, albumId: id, title: title, updatedAt: now - 3 * day))
            for (track, pos, words) in timed[id] ?? [] where track < a.tracks.count {
                let t = a.tracks[track]
                if notes.notes(provider: provider, trackId: t.id.rawId).contains(where: { $0.content == words }) { continue }
                notes.add(provider: provider, trackId: t.id.rawId, content: words, positionMs: pos,
                          title: t.title, artist: t.artist ?? a.artist)
            }
        }
        // `-historySeedSongLookups YES`: song scores on songs Yoin never played nor opened the album of (All
        // Memories asks their server, #202): three on Northern Field Recordings (the server names the album),
        // two on songs the server doesn't have (they share one Song score card).
        if UserDefaults.standard.bool(forKey: "historySeedSongLookups") {
            for (i, id) in ["s-field-01", "s-field-02", "s-field-03"].enumerated() {
                _ = ratings.syncPut(8.6 - Double(i) * 0.7, provider: provider, songId: id, updatedAt: now - 2 * day - Int64(i) * hour)
            }
            for (i, id) in ["s-gone-01", "s-gone-02"].enumerated() {
                _ = ratings.syncPut(7.2 + Double(i) * 0.9, provider: provider, songId: id, updatedAt: now - 5 * day - Int64(i) * hour)
            }
        }
    }
}
#endif
