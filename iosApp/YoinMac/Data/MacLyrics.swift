import CryptoKit
import Foundation
import Observation
import YoinShared

/// The current song's lyrics: the server's first (OpenSubsonic structured,
/// then plain `getLyrics`), else the shared automatic chain (QQ → NetEase →
/// LRCLIB, YoinShared `LyricsRegistry`).
@MainActor
@Observable
final class MacLyrics {
    struct Line: Identifiable, Hashable, Sendable, Codable {
        var id: Int
        var timeSec: Double
        var text: String
        /// The source's own translation of this line (shown under it when translation is on).
        var translation: String? = nil
    }

    /// Where the lyrics came from: a lyrics provider and its song id (nil: the server's own).
    struct Origin: Equatable, Sendable, Codable {
        var provider: String
        var songId: String
    }

    enum State: Equatable {
        case idle, loading, none
        case loaded(synced: Bool, lines: [Line], source: String)
    }

    /// The production credits taken out of the lyrics (they belong in About), as plain values.
    struct Credits: Equatable, Sendable, Codable {
        struct Credit: Equatable, Sendable, Codable {
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
    /// A word on the last Translate (iOS's toasts): already in that language, Gemini's error, no key.
    private(set) var notice: String?
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
        notice = nil
        offer = nil
        translating = false
        guard let track else { state = .idle; credits = Credits(); origin = nil; return }
        if let hit = cache[track.id] {
            state = hit
            credits = creditsCache[track.id] ?? Credits()
            origin = originCache[track.id]
            return
        }
        let chosen = Self.savedChoice(scope: choiceScope, songId: track.id)
        // Kept on disk (Android lyrics_cache): a pick of the listener's any age, an automatic match a month.
        if let kept = MacLyricsDisk.read(scope: choiceScope, songId: track.id, chosen: chosen) {
            apply(.loaded(synced: kept.synced, lines: kept.lines, source: kept.source), kept.credits, kept.origin,
                  for: track.id, keep: false)
            return
        }
        state = .loading
        credits = Credits()
        origin = nil
        task = Task {
            let (result, found, from) = await Self.fetch(track, chosen: chosen, client: client, registry: registry)
            guard !Task.isCancelled, gen == self.generation, self.songId == track.id else { return }
            self.apply(result, found, from, for: track.id)
        }
    }

    private func apply(_ result: State, _ found: Credits, _ from: Origin?, for id: String, keep: Bool = true) {
        if keep, case .loaded(let synced, let lines, let source) = result {
            let chosen = from != nil && from == Self.savedChoice(scope: choiceScope, songId: id)
            // The lyrics as sung; translations stay with their own stores (sources fetch them again, Gemini's
            // are kept per language).
            let originals = lines.map { var l = $0; l.translation = nil; return l }
            MacLyricsDisk.write(.init(synced: synced, lines: originals, source: source, credits: found, origin: from,
                                      chosen: chosen, at: Date()),
                                scope: choiceScope, songId: id)
        }
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
        Self.saveChoice(from, scope: choiceScope, songId: id)
        apply(s, credits, from, for: id)
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
        notice = nil
        defer { if songId == id { translating = false } }
        // iOS NowPlayingLyricsActions: one target language for the sources and Gemini (Settings › Gemini).
        let language = GeminiSettings.shared.targetLanguage
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
            // The target changed meanwhile (Settings, or synced): this text is for a language nobody asked for now.
            guard gen == generation, songId == id, GeminiSettings.shared.targetLanguage == language else { return }
            var map: [Int: String] = [:]
            for (k, v) in r.translations { map[k.intValue] = v }
            let (s, credits) = Self.lines(r.lyrics, source: r.providerName, translations: map)
            let from = Origin(provider: r.providerName, songId: r.providerSongId)
            if r.providerName == origin?.provider {
                apply(s, credits, from, for: id, keep: false)
            } else {
                // Another source's lyrics and timing: offered, never swapped in unasked (Android / iOS ask too).
                offer = Offer(songId: id, state: s, credits: credits, origin: from,
                              label: LyricsSource.label(r.providerName))
            }
            return
        }
        await translateWithGemini(id: id, track: t, gen: gen, language: language)
    }

    /// Another source's lyrics with a translation, waiting for the listener's Switch.
    struct Offer: Identifiable {
        var id: String { "\(songId)|\(origin.provider)" }
        var songId: String
        var state: State
        var credits: Credits
        var origin: Origin
        var label: String
    }
    var offer: Offer?

    /// Switch: the offered source's lyrics (with their translation) replace these, and stay chosen for the song.
    func acceptOffer() {
        guard let o = offer, o.songId == songId else { offer = nil; return }
        offer = nil
        Self.saveChoice(o.origin, scope: choiceScope, songId: o.songId)
        apply(o.state, o.credits, o.origin, for: o.songId)
    }

    /// Keep: these lyrics stay; Gemini translates them when there's a key.
    func declineOffer() async {
        guard let o = offer, let t = track, o.songId == songId else { offer = nil; return }
        offer = nil
        generation += 1
        let gen = generation
        translating = true
        defer { if songId == o.songId { translating = false } }
        await translateWithGemini(id: o.songId, track: t, gen: gen, language: GeminiSettings.shared.targetLanguage)
    }

    /// No source of these lyrics has one: Gemini translates the lines shown (the listener's own key, when
    /// there is one) — only for the song this began for.
    private func translateWithGemini(id: String, track t: PlayableTrack, gen: Int, language: String) async {
        guard gen == generation, songId == id, !Task.isCancelled else { return }
        let gemini = GeminiSettings.shared
        guard case .loaded(let synced, let lines, let source) = state, !lines.isEmpty else { return }
        // Gemini sees the sung lines only; answers map back to their rows (iOS).
        let rows = lines.indices.filter { !lines[$0].text.trimmingCharacters(in: .whitespaces).isEmpty }
        let texts = rows.map { lines[$0].text.trimmingCharacters(in: .whitespaces) }
        guard !texts.isEmpty else { notice = "No lyrics to translate"; return }
        if LyricsRules.alreadyTargetLanguage(texts, target: language) {
            notice = "Lyrics already appear to be \(language)"
            return
        }
        // The cache first (iOS LyricsUserStore, per song, language, model and the lines themselves).
        var byText: [Int: String]
        if let cached = LyricsUserStore.shared.geminiTranslations(provider: t.provider, rawId: t.id, title: t.title,
                                                                  artist: t.artist, language: language,
                                                                  model: GeminiClient.model, lines: texts) {
            byText = cached.enumerated().reduce(into: [:]) { $0[$1.offset] = $1.element }
        } else {
            guard gemini.hasKey else { notice = "No translation from the lyrics sources. Add a Gemini key in Settings to translate."; return }
            do {
                byText = try await GeminiClient().translateLyricLines(
                    apiKey: gemini.requestKey, title: t.title.isEmpty ? "Unknown song" : t.title,
                    artist: t.artist.isEmpty ? "Unknown artist" : t.artist, lines: texts, targetLanguage: language)
            } catch is CancellationError {
                return
            } catch {
                if gen == generation, songId == id {
                    notice = (error as? GeminiClient.Failure)?.message ?? "Failed to translate lyrics"
                }
                return
            }
            LyricsUserStore.shared.saveGeminiTranslations(texts.indices.map { byText[$0] ?? "" },
                                                          provider: t.provider, rawId: t.id, title: t.title,
                                                          artist: t.artist, language: language,
                                                          model: GeminiClient.model, lines: texts)
        }
        guard gen == generation, songId == id, gemini.targetLanguage == language else { return }
        var map: [Int: String] = [:]
        for (i, text) in byText where i < rows.count && !text.isEmpty { map[rows[i]] = text }
        let translated = lines.enumerated().map { i, l in
            var l = l
            l.translation = map[i]
            return l
        }
        let s = State.loaded(synced: synced, lines: translated, source: source)
        apply(s, credits, origin, for: id, keep: false)
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

/// Lyrics kept on disk per account and song (Android lyrics_cache, LyricsCachePolicy): the listener's pick
/// never expires and still shows offline after a restart; an automatic match is matched again after 30 days.
/// Only lyrics found are kept (none found is asked again next time).
enum MacLyricsDisk {
    struct Entry: Codable {
        var synced: Bool
        var lines: [MacLyrics.Line]
        var source: String
        var credits: MacLyrics.Credits
        var origin: MacLyrics.Origin?
        /// The listener picked these (search, or Switch to another source's translation).
        var chosen: Bool
        var at: Date
    }

    static let automaticLife: TimeInterval = 30 * 24 * 3600

    static func read(scope: String, songId: String, chosen: MacLyrics.Origin?) -> Entry? {
        guard !scope.isEmpty, let url = file(scope, songId), let data = try? Data(contentsOf: url),
              let e = try? JSONDecoder().decode(Entry.self, from: data) else { return nil }
        // A pick made since (on another device, synced) wins over what was kept.
        if let chosen, e.origin != chosen { return nil }
        guard e.chosen || Date().timeIntervalSince(e.at) < automaticLife else { return nil }
        return e
    }

    static func write(_ e: Entry, scope: String, songId: String) {
        guard !scope.isEmpty, let url = file(scope, songId), let data = try? JSONEncoder().encode(e) else { return }
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? data.write(to: url, options: .atomic)
    }

    private static func file(_ scope: String, _ songId: String) -> URL? {
        guard let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else { return nil }
        let name = SHA256.hash(data: Data("\(scope)|\(songId)".utf8)).map { String(format: "%02x", $0) }.joined()
        return base.appendingPathComponent(Bundle.main.bundleIdentifier ?? "Yoin")
            .appendingPathComponent("lyrics", isDirectory: true).appendingPathComponent(name + ".json")
    }
}
