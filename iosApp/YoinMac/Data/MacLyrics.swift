import Foundation
import Observation
import YoinShared

/// The current song's lyrics: the server's first (OpenSubsonic structured,
/// then plain `getLyrics`), else the shared automatic chain (QQ → NetEase →
/// LRCLIB, YoinShared `LyricsRegistry`).
@MainActor
@Observable
final class MacLyrics {
    struct Line: Identifiable, Hashable, Sendable {
        var id: Int
        var timeSec: Double
        var text: String
        /// The source's own translation of this line (shown under it when translation is on).
        var translation: String? = nil
    }

    /// Where the lyrics came from: a lyrics provider and its song id (nil: the server's own).
    struct Origin: Equatable, Sendable {
        var provider: String
        var songId: String
    }

    enum State: Equatable {
        case idle, loading, none
        case loaded(synced: Bool, lines: [Line], source: String)
    }

    /// The production credits taken out of the lyrics (they belong in About), as plain values.
    struct Credits: Equatable, Sendable {
        struct Credit: Equatable, Sendable {
            var role: String
            var names: String
        }

        /// Lyricist, composer, arranger, producer, in that order, where present.
        var headline: [Credit] = []
        var others: [Credit] = []
        var notices: [String] = []

        var isEmpty: Bool { headline.isEmpty && others.isEmpty && notices.isEmpty }

        init() {}

        init(_ c: LyricCredits) {
            let labels: [(LyricCreditRole, String)] = [(.lyricist, "Lyricist"), (.composer, "Composer"),
                                                       (.arranger, "Arranger"), (.producer, "Producer")]
            headline = labels.compactMap { role, label in c.namesFor(role: role).map { Credit(role: label, names: $0) } }
            others = c.others.map { Credit(role: $0.role, names: $0.names) }
            notices = c.notices
        }
    }

    /// The current song's credits (empty while loading or when none were found).
    private(set) var credits = Credits()
    @ObservationIgnored private var creditsCache: [String: Credits] = [:]

    private(set) var state: State = .idle
    private(set) var songId: String?
    private(set) var origin: Origin?
    /// The current lyrics carry a translation.
    var hasTranslation: Bool {
        if case .loaded(_, let lines, _) = state { return lines.contains { $0.translation != nil } }
        return false
    }
    private(set) var translating = false
    /// The account the per-song lyrics choices belong to (set by MacModel).
    @ObservationIgnored var choiceScope = ""
    @ObservationIgnored private var track: PlayableTrack?
    /// Every writer (a load, a pick, a translation) takes a number; only the newest may write.
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var originCache: [String: Origin] = [:]
    @ObservationIgnored private var task: Task<Void, Never>?
    @ObservationIgnored private let registry = LyricsRegistry()
    @ObservationIgnored private var cache: [String: State] = [:]

    /// Another account: its songs share ids with nothing here.
    func reset() {
        generation += 1
        task?.cancel()
        cache = [:]
        creditsCache = [:]
        originCache = [:]
        songId = nil
        origin = nil
        track = nil
        state = .idle
        credits = Credits()
    }

    func load(_ track: PlayableTrack?, client: SubsonicClient?) {
        guard track?.id != songId else { return }
        generation += 1
        let gen = generation
        task?.cancel()
        songId = track?.id
        self.track = track
        translating = false
        guard let track else { state = .idle; credits = Credits(); origin = nil; return }
        if let hit = cache[track.id] {
            state = hit
            credits = creditsCache[track.id] ?? Credits()
            origin = originCache[track.id]
            return
        }
        state = .loading
        credits = Credits()
        origin = nil
        let chosen = Self.savedChoice(scope: choiceScope, songId: track.id)
        task = Task {
            let (result, found, from) = await Self.fetch(track, chosen: chosen, client: client, registry: registry)
            guard !Task.isCancelled, gen == self.generation, self.songId == track.id else { return }
            self.apply(result, found, from, for: track.id)
        }
    }

    private func apply(_ result: State, _ found: Credits, _ from: Origin?, for id: String) {
        cache[id] = result
        creditsCache[id] = found
        originCache[id] = from
        state = result
        credits = found
        origin = from
    }

    private static func fetch(_ t: PlayableTrack, chosen: Origin?, client: SubsonicClient?,
                              registry: LyricsRegistry) async -> (State, Credits, Origin?) {
        // A source the listener picked for this song comes first.
        if let chosen, let r = try? await registry.fetchSelected(providerName: chosen.provider, songId: chosen.songId),
           !r.lyrics.lines.isEmpty {
            let (s, c) = lines(r.lyrics, source: r.providerName)
            return (s, c, chosen)
        }
        if let client, let l = try? await client.lyricsFor(songId: t.id, artist: t.artist, title: t.title),
           !l.lines.isEmpty, l.lines.contains(where: { !$0.text.trimmingCharacters(in: .whitespaces).isEmpty }) {
            let (s, c) = lines(l, source: "Server")
            return (s, c, nil)
        }
        let ms = t.durationSec > 0 ? KotlinLong(value: Int64(t.durationSec * 1000)) : nil
        if let r = try? await registry.autoFetch(title: t.title, artist: t.artist, durationMs: ms), !r.lyrics.lines.isEmpty {
            let (s, c) = lines(r.lyrics, source: r.providerName)
            return (s, c, Origin(provider: r.providerName, songId: r.providerSongId))
        }
        return (.none, Credits(), nil)
    }

    /// The credit lines come out of the lyrics (Android LyricCreditsExtractor, shared) into `credits`;
    /// [translations] (by index into [raw]) follow their lines through that.
    private static func lines(_ raw: Lyrics, source: String, translations: [Int: String] = [:]) -> (State, Credits) {
        var byLine: [String: String] = [:]
        for (i, t) in translations where raw.lines.indices.contains(i) {
            byLine["\(raw.lines[i].timeMs)|\(raw.lines[i].text)"] = t
        }
        let split = LyricCreditsExtractor.shared.extract(lyrics: raw)
        let l = split.lyrics
        let state = State.loaded(synced: l.synced,
                                 lines: l.lines.enumerated().map {
                                     Line(id: $0, timeSec: Double($1.timeMs) / 1000, text: $1.text,
                                          translation: byLine["\($1.timeMs)|\($1.text)"])
                                 },
                                 source: source)
        return (state, split.credits.map(Credits.init) ?? Credits())
    }

    // MARK: Search and translation (Android LyricsActionBar's Search and Translate)

    /// Every provider's hits for [query] (QQ, NetEase, Huawei, LRCLIB).
    func search(_ query: String) async -> [ProviderSection] {
        (try? await registry.search(query: query)) ?? []
    }

    /// The listener's pick: fetched, shown, and remembered for this song.
    func choose(_ c: LyricsCandidate, for expected: String) async -> Bool {
        guard let id = songId, id == expected else { return false }
        generation += 1
        let gen = generation
        let scope = choiceScope
        task?.cancel()
        guard let r = try? await registry.fetchSelected(providerName: c.providerName, songId: c.songId),
              !r.lyrics.lines.isEmpty, gen == generation, songId == id, scope == choiceScope else { return false }
        let (s, credits) = Self.lines(r.lyrics, source: r.providerName)
        let from = Origin(provider: c.providerName, songId: c.songId)
        apply(s, credits, from, for: id)
        Self.saveChoice(from, scope: choiceScope, songId: id)
        return true
    }

    /// The source's own translation: the current source's, else the next source that has one (its lyrics
    /// come with it, so the lines and translations agree).
    func translate() async {
        guard let id = songId, let t = track, !translating else { return }
        generation += 1
        let gen = generation
        task?.cancel()
        translating = true
        defer { if songId == id { translating = false } }
        let language = Locale.preferredLanguages.first ?? "en"
        var order: [String] = []
        if let o = origin, registry.servesNativeTranslation(providerName: o.provider, targetLanguage: language) {
            order.append(o.provider)
            order += registry.translationSwitchCandidates(currentProviderName: o.provider, targetLanguage: language)
        } else {
            order = ["qq", "netease", "huawei"].filter {
                registry.servesNativeTranslation(providerName: $0, targetLanguage: language)
            }
        }
        for name in order {
            let same = origin?.provider == name ? origin?.songId : nil
            guard let r = try? await registry.fetchTranslation(providerName: name, providerSongId: same,
                                                               title: t.title, artist: t.artist) else { continue }
            guard gen == generation, songId == id else { return }
            var map: [Int: String] = [:]
            for (k, v) in r.translations { map[k.intValue] = v }
            let (s, credits) = Self.lines(r.lyrics, source: r.providerName, translations: map)
            apply(s, credits, Origin(provider: r.providerName, songId: r.providerSongId), for: id)
            return
        }
    }

    private static func choiceKey(_ scope: String, _ songId: String) -> String { "lyricsChoice.\(scope).\(songId)" }

    private static func savedChoice(scope: String, songId: String) -> Origin? {
        guard let raw = UserDefaults.standard.string(forKey: choiceKey(scope, songId)),
              let bar = raw.firstIndex(of: "|") else { return nil }
        return Origin(provider: String(raw[..<bar]), songId: String(raw[raw.index(after: bar)...]))
    }

    private static func saveChoice(_ o: Origin, scope: String, songId: String) {
        UserDefaults.standard.set("\(o.provider)|\(o.songId)", forKey: choiceKey(scope, songId))
    }

    /// The line playing at [sec] (−1 before the first or unsynced).
    static func index(in lines: [Line], at sec: Double) -> Int {
        var lo = 0, hi = lines.count - 1, found = -1
        while lo <= hi {
            let mid = (lo + hi) / 2
            if lines[mid].timeSec <= sec + 0.15 { found = mid; lo = mid + 1 } else { hi = mid - 1 }
        }
        return found
    }
}
