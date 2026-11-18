import Foundation
import Observation

/// One song's lyric card (Android `lyric_highlights` row): what the model picked (or the user did), with
/// what's needed to show and play it later without the network. `text == nil` records a song tried and found
/// unusable (no lyrics, too little sung, an odd answer), so it isn't tried again for a month.
struct LyricHighlightRow: Codable, Hashable, Sendable, Identifiable {
    var provider: String
    /// The account (`AccountScope.scope(for:)`); nil for Apple Music.
    var scope: String?
    var songId: String
    var title: String
    var artist: String
    var album: String
    var albumId: String
    var coverKey: String?
    var durationMs: Int64
    var text: String?
    var startMs: Int64?
    var fingerprint: String?
    /// The model's name, or `user` for a line the listener shared or saved.
    var model: String?
    var attemptedAt: Int64
    var lastShownAt: Int64?

    var id: String { "\(provider)|\(scope ?? "")|\(songId)" }
    var isUserPick: Bool { model == LyricHighlightStore.userPick }
}

/// The cards on disk, every account's (Application Support/Yoin/lyric-highlights.json), plus the Labs
/// switch and the intro's answer.
@MainActor
@Observable
final class LyricHighlightStore {
    static let shared = LyricHighlightStore(fileURL: LyricHighlightStore.defaultURL)
    nonisolated static let userPick = "user"
    nonisolated static let maxUserPickLines = 4

    private(set) var rows: [String: LyricHighlightRow] = [:]
    /// Bumped on each new card, so Home refreshes.
    private(set) var revision = 0
    @ObservationIgnored private let fileURL: URL?
    @ObservationIgnored private let defaults: UserDefaults

    /// The listener turned the beta on (owner 2026-11-08: the model runs only after an opt-in). Kept lines
    /// stay either way.
    var optedIn: Bool { didSet { defaults.set(optedIn, forKey: "lyricHighlightOptedIn") } }
    /// The section's first-use intro has been answered.
    var introSeen: Bool { didSet { defaults.set(introSeen, forKey: "lyricHighlightIntroSeen") } }
    var budget: LyricHighlightBudget {
        didSet { if let d = try? JSONEncoder().encode(budget) { defaults.set(d, forKey: "lyricHighlightBudget") } }
    }

    init(fileURL: URL?, defaults: UserDefaults = .standard) {
        self.fileURL = fileURL
        self.defaults = defaults
        optedIn = defaults.bool(forKey: "lyricHighlightOptedIn")
        introSeen = defaults.bool(forKey: "lyricHighlightIntroSeen")
        budget = defaults.data(forKey: "lyricHighlightBudget").flatMap { try? JSONDecoder().decode(LyricHighlightBudget.self, from: $0) } ?? .init()
        if let fileURL, let data = try? Data(contentsOf: fileURL),
           let saved = try? JSONDecoder().decode([LyricHighlightRow].self, from: data) {
            rows = Dictionary(saved.map { ($0.id, $0) }, uniquingKeysWith: { $1 })
        }
    }

    static var defaultURL: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("Yoin", isDirectory: true).appendingPathComponent("lyric-highlights.json")
    }

    static func key(provider: String, scope: String?, songId: String) -> String { "\(provider)|\(scope ?? "")|\(songId)" }

    func row(provider: String, scope: String?, songId: String) -> LyricHighlightRow? {
        rows[Self.key(provider: provider, scope: scope, songId: songId)]
    }

    /// The account's cards (rows with a passage).
    func cards(provider: String, scope: String?) -> [LyricHighlightRow] {
        rows.values.filter { $0.provider == provider && $0.scope == scope && $0.text != nil }
    }

    /// Songs not to queue again: picked under this prompt, a user pick, or tried and unusable lately.
    func settled(provider: String, scope: String?, retryAfter: Int64) -> Set<String> {
        Set(rows.values.filter { r in
            guard r.provider == provider, r.scope == scope else { return false }
            if r.isUserPick { return true }
            if r.text != nil { return r.fingerprint?.hasPrefix(LyricHighlightPrompt.promptVersion) ?? false }
            return r.attemptedAt >= retryAfter
        }.map(\.songId))
    }

    /// Saves a pick or an unusable try. A failed try never replaces a passage already there, and nothing
    /// replaces the listener's own pick.
    func save(_ row: LyricHighlightRow) {
        let previous = rows[row.id]
        if previous?.isUserPick == true, !row.isUserPick { return }
        if row.text == nil, previous?.text != nil { return }
        var row = row
        row.lastShownAt = previous?.lastShownAt
        rows[row.id] = row
        if row.text != nil { revision += 1 }
        persist()
    }

    func markShown(_ id: String, at ms: Int64) {
        guard var r = rows[id] else { return }
        r.lastShownAt = ms
        rows[id] = r
        persist()
    }

    func removeAll() {
        rows = [:]
        revision += 1
        persist()
    }

    private func persist() {
        guard let fileURL else { return }
        let all = rows.values.sorted { $0.id < $1.id }
        guard let data = try? JSONEncoder().encode(all) else { return }
        try? FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? data.write(to: fileURL, options: .atomic)
    }
}

#if DEBUG
extension AppModel {
    /// Debug builds: four Lyric Cards on the signed-in account's first albums, with placeholder lines,
    /// the intro answered, so Home's section and its count handle can be screenshotted on a simulator.
    func seedLyricHighlights() async {
        guard let account = profiles.active.flatMap(MemoryLedgerSource.account(of:)) else { return }
        await store.loadHome(force: false)
        // A sign-in's reset can drop the lists just loaded: wait for them.
        for _ in 0..<40 where store.homeLists == nil { try? await Task.sleep(for: .milliseconds(250)) }
        guard let lists = store.homeLists else { return }
        var ids: [String] = []
        for a in lists.newest + lists.recent + lists.random where !ids.contains(a.id.rawId) { ids.append(a.id.rawId) }
        let lines = [
            "Leave the porch light on,\nI'm walking home the long way",
            "We drew a map in the frost\nand followed it till morning",
            "Every station on the dial\nis playing something blue",
            "Hold the note a little longer,\nthe tide is coming in",
        ]
        let lyricStore = LyricHighlightStore.shared
        let now = LyricHighlightProcessor.nowMs
        var seeded = 0
        for id in ids.prefix(8) where seeded < lines.count {
            await store.loadAlbum(id)
            guard let album = store.album(id), let track = album.tracks.dropFirst().first ?? album.tracks.first else { continue }
            lyricStore.save(LyricHighlightRow(
                provider: account.provider, scope: account.scope, songId: track.id.rawId, title: track.title,
                artist: track.artist ?? album.artist, album: album.title, albumId: album.id.rawId, coverKey: nil,
                durationMs: Int64(track.durationSec) * 1000, text: lines[seeded], startMs: 30_000,
                fingerprint: nil, model: seeded == 1 ? LyricHighlightStore.userPick : "debug-seed",
                attemptedAt: now, lastShownAt: nil))
            seeded += 1
        }
        lyricStore.introSeen = true
    }
}
#endif
