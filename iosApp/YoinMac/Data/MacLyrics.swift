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
    @ObservationIgnored private var task: Task<Void, Never>?
    @ObservationIgnored private let registry = LyricsRegistry()
    @ObservationIgnored private var cache: [String: State] = [:]

    /// Another account: its songs share ids with nothing here.
    func reset() {
        task?.cancel()
        cache = [:]
        creditsCache = [:]
        songId = nil
        state = .idle
        credits = Credits()
    }

    func load(_ track: PlayableTrack?, client: SubsonicClient?) {
        guard track?.id != songId else { return }
        task?.cancel()
        songId = track?.id
        guard let track else { state = .idle; credits = Credits(); return }
        if let hit = cache[track.id] { state = hit; credits = creditsCache[track.id] ?? Credits(); return }
        state = .loading
        credits = Credits()
        task = Task {
            let (result, found) = await Self.fetch(track, client: client, registry: registry)
            guard !Task.isCancelled, self.songId == track.id else { return }
            self.cache[track.id] = result
            self.creditsCache[track.id] = found
            self.state = result
            self.credits = found
        }
    }

    private static func fetch(_ t: PlayableTrack, client: SubsonicClient?,
                              registry: LyricsRegistry) async -> (State, Credits) {
        if let client, let l = try? await client.lyricsFor(songId: t.id, artist: t.artist, title: t.title),
           !l.lines.isEmpty, l.lines.contains(where: { !$0.text.trimmingCharacters(in: .whitespaces).isEmpty }) {
            return lines(l, source: "Server")
        }
        let ms = t.durationSec > 0 ? KotlinLong(value: Int64(t.durationSec * 1000)) : nil
        if let r = try? await registry.autoFetch(title: t.title, artist: t.artist, durationMs: ms), !r.lyrics.lines.isEmpty {
            return lines(r.lyrics, source: r.providerName)
        }
        return (.none, Credits())
    }

    /// The credit lines come out of the lyrics (Android LyricCreditsExtractor, shared) into `credits`.
    private static func lines(_ raw: Lyrics, source: String) -> (State, Credits) {
        let split = LyricCreditsExtractor.shared.extract(lyrics: raw)
        let l = split.lyrics
        let state = State.loaded(synced: l.synced,
                                 lines: l.lines.enumerated().map { Line(id: $0, timeSec: Double($1.timeMs) / 1000, text: $1.text) },
                                 source: source)
        return (state, split.credits.map(Credits.init) ?? Credits())
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
