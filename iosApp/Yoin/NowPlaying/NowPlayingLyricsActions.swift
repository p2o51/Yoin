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
    /// A translation belongs to a song and the language it was made for: a changed target must not toggle the
    /// old language's text on (Android keys its translation state by the language too).
    struct Slot: Hashable {
        var track: String
        var language: String
    }

    /// Slot → line index → translation, and whether it is shown.
    private(set) var translations: [Slot: [Int: String]] = [:]
    private(set) var showTranslation: [Slot: Bool] = [:]

    // MARK: Search (Android LyricsSearchState)

    struct Search: Equatable {
        var isOpen = false
        var query = ""
        var loading = false
        /// One per provider, in the registry's order (Android LyricsSearchState.sections).
        var sections: [Section] = []
        var errorMessage: String?
        var applyingId: String?
        /// The song the search was opened for: a pick applies to it.
        var target: PlayableTrack?
    }

    struct Section: Equatable, Identifiable {
        var provider: String
        var candidates: [Candidate]
        var error: String?
        var id: String { provider }
        var label: String { LyricsSource.label(provider) }
    }

    struct Candidate: Equatable, Identifiable {
        var provider: String
        var songId: String
        var title: String
        var artist: String
        var id: String { provider + "/" + songId }
    }

    var search = Search()
    @ObservationIgnored private var searchTask: Task<Void, Never>?
    @ObservationIgnored var registry: LyricsRegistry { NowPlayingData.shared.registry }
    @ObservationIgnored var gemini: () -> GeminiClient = { GeminiClient() }

    private func toast(_ text: String) { PlaylistActions.shared.show(text) }

    /// [language] is the target the layer would be shown in: the current setting unless a caller says otherwise.
    func translationShown(_ t: PlayableTrack, language: String = GeminiSettings.shared.targetLanguage) -> Bool {
        showTranslation[Slot(track: NowPlayingData.key(t), language: language)] ?? false
    }

    /// Per line of the loaded lyrics: the translation when the layer is shown.
    func shownTranslations(_ t: PlayableTrack, count: Int,
                           language: String = GeminiSettings.shared.targetLanguage) -> [String?] {
        let slot = Slot(track: NowPlayingData.key(t), language: language)
        guard showTranslation[slot] == true, let map = translations[slot] else {
            return Array(repeating: nil, count: count)
        }
        return (0..<count).map { map[$0] }
    }

    /// The lyrics changed under the translations (a search / edit applied): every language's text is stale.
    private func reset(_ k: String) {
        translations = translations.filter { $0.key.track != k }
        showTranslation = showTranslation.filter { $0.key.track != k }
    }

    // MARK: Translate

    /// Android `translateLyrics`: a second tap toggles a translation already
    /// there; otherwise the cache, then Gemini (no key → a message).
    func translate(_ t: PlayableTrack, lines: [String], settings: GeminiSettings = .shared) {
        let k = NowPlayingData.key(t)
        let language = settings.targetLanguage
        let slot = Slot(track: k, language: language)
        guard !lines.isEmpty, inFlight == nil else { return }
        if let have = translations[slot], !have.isEmpty {
            showTranslation[slot] = !(showTranslation[slot] ?? false)
            return
        }
        // Gemini sees the sung lines only; answers map back to their rows.
        let rows = lines.indices.filter { !lines[$0].trimmingCharacters(in: .whitespaces).isEmpty }
        let source = rows.map { lines[$0].trimmingCharacters(in: .whitespaces) }
        guard !source.isEmpty else { return toast(String(localized: "No lyrics to translate")) }
        if LyricsRules.alreadyTargetLanguage(source, target: language) {
            return toast(String(localized: "Lyrics already appear to be \(language)"))
        }
        // Android translateLyricsWithProvider: lyrics from a provider that
        // carries its own translation (QQ, NetEase) take it from there, the
        // text refetched with it; failing that, another provider's full set
        // is offered (never mixed line by line); else Gemini.
        if let from = NowPlayingData.shared.lyricsSource[k],
           registry.servesNativeTranslation(providerName: from.provider, targetLanguage: language) {
            inFlight = .translate
            Task {
                if await translateWithProvider(t, from: from, language: language, settings: settings) {
                    inFlight = nil
                    return
                }
                inFlight = nil
                translateWithGemini(t, rows: rows, source: source, language: language, settings: settings)
            }
            return
        }
        translateWithGemini(t, rows: rows, source: source, language: language, settings: settings)
    }

    /// True when a provider's translation was shown or offered.
    private func translateWithProvider(_ t: PlayableTrack, from: LyricsSource, language: String,
                                       settings: GeminiSettings) async -> Bool {
        let k = NowPlayingData.key(t)
        let registry = registry
        if let r = try? await registry.fetchTranslation(providerName: from.provider, providerSongId: from.songId,
                                                       title: t.title, artist: t.artist),
           !r.translations.isEmpty {
            // The target changed while this was fetched: its text is for the old language, so it's dropped
            // (true = handled, no Gemini fallback for a language nobody asked for now).
            guard settings.targetLanguage == language else { return true }
            NowPlayingData.shared.refreshProviderLyrics(r.lrc, source: LyricsSource(provider: r.providerName,
                                                                                    songId: r.providerSongId), for: t)
            show(r, key: k, language: language)
            toast(String(localized: "Lyrics translated"))
            return true
        }
        for candidate in registry.translationSwitchCandidates(currentProviderName: from.provider, targetLanguage: language) {
            guard let r = try? await registry.searchAndFetchWithTranslation(providerName: candidate, title: t.title,
                                                                            artist: t.artist),
                  !r.translations.isEmpty else { continue }
            let label = LyricsSource.label(r.providerName)
            // Android: a snackbar with Switch; the switched set is the user's choice.
            PlaylistActions.shared.show(String(localized: "\(label) translation is available"),
                                        action: .init(label: String(localized: "Switch")) { [weak self] in
                let source = LyricsSource(provider: r.providerName, songId: r.providerSongId)
                guard NowPlayingData.shared.applyUserLyrics(r.lrc, for: t, source: source) else { return }
                self?.show(r, key: k, language: language)
                self?.toast(String(localized: "Lyrics translated from \(label)"))
            })
            return true
        }
        return false
    }

    /// A provider's translation over its own lines (indices into `r.lyrics`).
    private func show(_ r: LyricsTranslationResult, key: String, language: String) {
        var map: [Int: String] = [:]
        for (i, text) in r.translations where !text.isEmpty { map[Int(i.int32Value)] = text }
        let slot = Slot(track: key, language: language)
        translations[slot] = map
        showTranslation[slot] = true
    }

    private func translateWithGemini(_ t: PlayableTrack, rows: [Int], source: [String], language: String,
                                     settings: GeminiSettings) {
        let k = NowPlayingData.key(t)
        if let cached = LyricsUserStore.shared.geminiTranslations(provider: t.provider, rawId: t.id, title: t.title,
                                                                  artist: t.artist, language: language,
                                                                  model: GeminiClient.model, lines: source) {
            apply(cached.enumerated().reduce(into: [:]) { $0[$1.offset] = $1.element }, rows: rows, key: k,
                  language: language)
            return
        }
        let apiKey = settings.requestKey
        guard !apiKey.isEmpty else { return toast(String(localized: "Gemini API key missing")) }
        inFlight = .translate
        let client = gemini()
        Task {
            defer { inFlight = nil }
            do {
                let result = try await client.translateLyricLines(
                    apiKey: apiKey, title: t.title.isEmpty ? String(localized: "Unknown song") : t.title,
                    artist: t.artist.isEmpty ? String(localized: "Unknown artist") : t.artist, lines: source, targetLanguage: language)
                LyricsUserStore.shared.saveGeminiTranslations(source.indices.map { result[$0] ?? "" },
                                                              provider: t.provider, rawId: t.id, title: t.title,
                                                              artist: t.artist, language: language,
                                                              model: GeminiClient.model, lines: source)
                // Saved under its own language above; shown only if that is still the target.
                guard settings.targetLanguage == language else { return }
                apply(result, rows: rows, key: k, language: language)
                toast(String(localized: "Lyrics translated"))
            } catch is CancellationError {
            } catch {
                toast((error as? GeminiClient.Failure)?.message ?? String(localized: "Failed to translate lyrics"))
            }
        }
    }

    private func apply(_ bySource: [Int: String], rows: [Int], key: String, language: String) {
        var map: [Int: String] = [:]
        for (i, text) in bySource where i < rows.count && !text.isEmpty { map[rows[i]] = text }
        let slot = Slot(track: key, language: language)
        translations[slot] = map
        showTranslation[slot] = true
    }

    // MARK: Search

    /// Android `openLyricsSearch`: seeded with "title artist", searched at once.
    func openSearch(_ t: PlayableTrack) {
        let query = [t.title, t.artist].map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }.joined(separator: " ")
        guard !query.isEmpty else { return toast(String(localized: "Need title or artist to search lyrics")) }
        search = Search(isOpen: true, query: query, target: t)
        runSearch(query)
    }

    func runSearch(_ query: String? = nil) {
        let q = (query ?? search.query).trimmingCharacters(in: .whitespaces)
        searchTask?.cancel()
        guard !q.isEmpty else {
            search.loading = false
            search.sections = []
            search.errorMessage = nil
            return
        }
        search.query = q
        search.loading = true
        search.sections = []
        search.errorMessage = nil
        let registry = registry
        searchTask = Task {
            do {
                let found = try await registry.search(query: q)
                guard !Task.isCancelled, search.isOpen, search.query == q else { return }
                search.sections = found.map { s in
                    Section(provider: s.providerName,
                            candidates: s.candidates.map {
                                Candidate(provider: $0.providerName, songId: $0.songId, title: $0.title, artist: $0.artist)
                            },
                            error: s.errorMessage == nil ? nil : String(localized: "Couldn't search \(LyricsSource.label(s.providerName))"))
                }
            } catch is CancellationError {
                return
            } catch {
                guard !Task.isCancelled else { return }
                search.errorMessage = "Couldn't search lyrics"
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
    func applySearchResult(_ match: Candidate, for t: PlayableTrack) {
        guard search.applyingId == nil, inFlight == nil else { return }
        search.applyingId = match.id
        search.errorMessage = nil
        inFlight = .search
        let registry = registry
        Task {
            defer { inFlight = nil }
            let hit = try? await registry.fetchSelected(providerName: match.provider, songId: match.songId)
            let source = LyricsSource(provider: hit?.providerName ?? match.provider,
                                      songId: hit?.providerSongId ?? match.songId)
            if let hit, NowPlayingData.shared.applyUserLyrics(hit.lrc, for: t, source: source) {
                reset(NowPlayingData.key(t))
                search = Search()
                toast(String(localized: "Lyrics applied from \(LyricsSource.label(source.provider))"))
            } else {
                search.applyingId = nil
                search.errorMessage = "Couldn't apply lyrics"
                toast(String(localized: "Couldn't apply lyrics"))
            }
        }
    }

    /// Android `applyLyrics` (the editor's Apply).
    func applyEdited(_ raw: String, for t: PlayableTrack) {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, inFlight == nil else { return }
        if NowPlayingData.shared.applyUserLyrics(trimmed, for: t) {
            reset(NowPlayingData.key(t))
            toast(String(localized: "Lyrics applied"))
        } else {
            toast(String(localized: "Couldn't apply lyrics"))
        }
    }
}
