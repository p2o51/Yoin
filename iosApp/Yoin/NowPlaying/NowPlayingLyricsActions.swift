import Foundation
import Observation
import YoinShared

/// What the lyric tools do beyond the page itself: Android
/// NowPlayingViewModel's `translateLyrics`, `openLyricsSearch` /
/// `searchLyrics` / `applyLyricsSearchResult` and `applyLyrics`, with
/// `lyricsActionInFlight` and the snackbar messages (here the shell toast).
@MainActor
@Observable
final class LyricsActions {
    static let shared = LyricsActions()

    /// Android `LyricsAction`.
    enum Action: Equatable { case search, translate, apply }

    private(set) var inFlight: Action?
    /// Track key → line index → translation, and whether it is shown.
    private(set) var translations: [String: [Int: String]] = [:]
    private(set) var showTranslation: [String: Bool] = [:]

    // MARK: Search (Android LyricsSearchState)

    struct Search: Equatable {
        var isOpen = false
        var query = ""
        var loading = false
        var results: [LrclibClient.Match] = []
        var providerError: String?
        var errorMessage: String?
        var applyingId: String?
    }

    var search = Search()
    @ObservationIgnored private var searchTask: Task<Void, Never>?
    @ObservationIgnored var lrclib = LrclibClient()
    @ObservationIgnored var gemini: () -> GeminiClient = { GeminiClient() }

    private func toast(_ text: String) { PlaylistActions.shared.show(text) }

    func translationShown(_ t: PlayableTrack) -> Bool { showTranslation[NowPlayingData.key(t)] ?? false }

    /// Per line of the loaded lyrics: the translation when the layer is shown.
    func shownTranslations(_ t: PlayableTrack, count: Int) -> [String?] {
        let k = NowPlayingData.key(t)
        guard showTranslation[k] == true, let map = translations[k] else { return Array(repeating: nil, count: count) }
        return (0..<count).map { map[$0] }
    }

    /// The lyrics changed under the translations (a search / edit applied).
    private func reset(_ k: String) {
        translations[k] = nil
        showTranslation[k] = nil
    }

    // MARK: Translate

    /// Android `translateLyrics`: a second tap toggles a translation already
    /// there; otherwise the cache, then Gemini (no key → a message).
    func translate(_ t: PlayableTrack, lines: [String], settings: GeminiSettings = .shared) {
        let k = NowPlayingData.key(t)
        guard !lines.isEmpty, inFlight == nil else { return }
        if let have = translations[k], !have.isEmpty {
            showTranslation[k] = !(showTranslation[k] ?? false)
            return
        }
        // Gemini sees the sung lines only; answers map back to their rows.
        let rows = lines.indices.filter { !lines[$0].trimmingCharacters(in: .whitespaces).isEmpty }
        let source = rows.map { lines[$0].trimmingCharacters(in: .whitespaces) }
        guard !source.isEmpty else { return toast("No lyrics to translate") }
        let language = settings.targetLanguage
        if LyricsRules.alreadyTargetLanguage(source, target: language) {
            return toast("Lyrics already appear to be \(language)")
        }
        if let cached = LyricsUserStore.shared.translations(k, language: language, lines: source),
           cached.count == source.count {
            apply(cached.enumerated().reduce(into: [:]) { $0[$1.offset] = $1.element }, rows: rows, key: k)
            return
        }
        let apiKey = settings.requestKey
        guard !apiKey.isEmpty else { return toast("Gemini API key missing") }
        inFlight = .translate
        let client = gemini()
        Task {
            defer { inFlight = nil }
            do {
                let result = try await client.translateLyricLines(
                    apiKey: apiKey, title: t.title.isEmpty ? "Unknown song" : t.title,
                    artist: t.artist.isEmpty ? "Unknown artist" : t.artist, lines: source, targetLanguage: language)
                LyricsUserStore.shared.saveTranslations(source.indices.map { result[$0] ?? "" }, key: k,
                                                        language: language, lines: source)
                apply(result, rows: rows, key: k)
                toast("Lyrics translated")
            } catch is CancellationError {
            } catch {
                toast((error as? GeminiClient.Failure)?.message ?? "Failed to translate lyrics")
            }
        }
    }

    private func apply(_ bySource: [Int: String], rows: [Int], key: String) {
        var map: [Int: String] = [:]
        for (i, text) in bySource where i < rows.count && !text.isEmpty { map[rows[i]] = text }
        translations[key] = map
        showTranslation[key] = true
    }

    // MARK: Search

    /// Android `openLyricsSearch`: seeded with "title artist", searched at once.
    func openSearch(_ t: PlayableTrack) {
        let query = [t.title, t.artist].map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }.joined(separator: " ")
        guard !query.isEmpty else { return toast("Need title or artist to search lyrics") }
        search = Search(isOpen: true, query: query)
        runSearch(query)
    }

    func runSearch(_ query: String? = nil) {
        let q = (query ?? search.query).trimmingCharacters(in: .whitespaces)
        searchTask?.cancel()
        guard !q.isEmpty else {
            search.loading = false
            search.results = []
            search.errorMessage = nil
            return
        }
        search.query = q
        search.loading = true
        search.results = []
        search.providerError = nil
        search.errorMessage = nil
        let client = lrclib
        searchTask = Task {
            do {
                let found = try await client.search(q)
                guard !Task.isCancelled, search.isOpen, search.query == q else { return }
                search.results = found
            } catch is CancellationError {
                return
            } catch {
                guard !Task.isCancelled else { return }
                search.providerError = "Couldn't search lyrics"
            }
            search.loading = false
        }
    }

    func dismissSearch() {
        searchTask?.cancel()
        search.isOpen = false
        search.loading = false
        search.applyingId = nil
    }

    /// Android `applyLyricsSearchResult`: fetch the pick, store it as the
    /// user's, close the search.
    func applySearchResult(_ match: LrclibClient.Match, for t: PlayableTrack) {
        guard search.applyingId == nil, inFlight == nil else { return }
        search.applyingId = match.id
        search.errorMessage = nil
        inFlight = .search
        let client = lrclib
        Task {
            defer { inFlight = nil }
            let lrc = try? await client.fetch(id: match.id)
            if let lrc, NowPlayingData.shared.applyUserLyrics(lrc, for: t) {
                reset(NowPlayingData.key(t))
                search = Search()
                toast("Lyrics applied from \(LrclibClient.providerLabel)")
            } else {
                search.applyingId = nil
                search.errorMessage = "Couldn't apply lyrics"
                toast("Couldn't apply lyrics")
            }
        }
    }

    /// Android `applyLyrics` (the editor's Apply).
    func applyEdited(_ raw: String, for t: PlayableTrack) {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, inFlight == nil else { return }
        if NowPlayingData.shared.applyUserLyrics(trimmed, for: t) {
            reset(NowPlayingData.key(t))
            toast("Lyrics applied")
        } else {
            toast("Couldn't apply lyrics")
        }
    }
}
