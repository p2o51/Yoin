import Foundation
import Observation

/// Fetches a song's About from Gemini once and keeps it in `SongAboutStore`:
/// Android `YoinRepository.ensureCanonicalAbout` plus NowPlayingViewModel's
/// `_aboutLoading` / `_aboutError` (one fetch in flight per song).
@MainActor
@Observable
final class SongAboutLoader {
    static let shared = SongAboutLoader()

    enum Phase: Equatable {
        case idle
        case loading
        /// No key, and nothing cached (Android `ApiKeyMissing`).
        case noKey
        case failed(String)
    }

    private(set) var phases: [SongAboutStore.Entry.SongKey: Phase] = [:]

    @ObservationIgnored let store: SongAboutStore
    @ObservationIgnored let settings: GeminiSettings
    @ObservationIgnored private let client: () -> GeminiClient
    @ObservationIgnored private var inFlight: Set<SongAboutStore.Entry.SongKey> = []

    init(store: SongAboutStore? = nil, settings: GeminiSettings? = nil,
         client: @escaping () -> GeminiClient = { GeminiClient() }) {
        self.store = store ?? .shared
        self.settings = settings ?? .shared
        self.client = client
        // The store shows rows in this loader's language (Codex C17: a cache is per language).
        let s = self.settings
        self.store.language = { s.targetLanguage }
    }

    func phase(_ key: SongAboutStore.Entry.SongKey) -> Phase { phases[key] ?? .idle }

    /// Cached answers stand; otherwise one grounded call, unless one is
    /// already running. `retry` asks again over the cache. A song without a
    /// title or artist isn't asked (Android).
    func ensure(title: String, artist: String, album: String, retry: Bool = false) async {
        guard !title.trimmingCharacters(in: .whitespaces).isEmpty,
              !artist.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        let key = SongAboutStore.key(title: title, artist: artist, album: album)
        guard !inFlight.contains(key) else { return }
        if !retry, store.hasCanonical(key) {
            phases[key] = .idle
            return
        }
        let apiKey = settings.requestKey
        guard !apiKey.isEmpty else {
            phases[key] = store.entries(key).isEmpty ? .noKey : .idle
            return
        }
        let language = settings.targetLanguage
        inFlight.insert(key)
        phases[key] = .loading
        defer { inFlight.remove(key) }
        // Its own task: leaving the About page mustn't throw away a paid call.
        let call = client()
        let request = Task {
            try await call.canonicalAbout(apiKey: apiKey, title: title, artist: artist, album: album,
                                          targetLanguage: language)
        }
        do {
            let values = try await request.value
            store.saveCanonical(title: title, artist: artist, album: album, values: values, language: language)
            phases[key] = .idle
        } catch is CancellationError {
            phases[key] = .idle
        } catch {
            phases[key] = .failed((error as? GeminiClient.Failure)?.message ?? "Failed to load song about info")
        }
    }

    // MARK: Ask Gemini

    /// The Ask Gemini bar (Android `AskBarState`): transient, never stored.
    /// While loading nothing is written; the answer lands as one Ask row.
    enum AskBarState: Equatable {
        case idle
        case focused
        case loading(title: String, requestId: Int)
        case error(String)
    }

    private(set) var askState: AskBarState = .idle
    @ObservationIgnored private var askRequestId = 0

    /// Android `onAskBarFocused`.
    func askFocused() {
        if case .loading = askState { return }
        askState = .focused
    }

    /// Android `onAskBarCollapseRequested`: the keyboard went away unsent.
    func askCollapse() {
        if askState == .focused { askState = .idle }
    }

    /// Android `dismissAskError`.
    func dismissAskError() {
        if case .error = askState { askState = .idle }
    }

    /// Another song: the bar starts over (an answer still on the way is
    /// still saved to its own song, it just no longer drives the bar).
    func resetAsk() {
        askRequestId += 1
        askState = .idle
    }

    /// Android `NowPlayingViewModel.askQuestion`: the bar shows the question,
    /// then Gemini's short heading for it, while the grounded answer comes;
    /// the answer is saved as an Ask row (`SongAboutStore.saveAsk`).
    func ask(title: String, artist: String, album: String, question: String) {
        let asked = question.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !asked.isEmpty else { return }
        askRequestId += 1
        let id = askRequestId
        let apiKey = settings.requestKey
        guard !apiKey.isEmpty else {
            askState = .error("Gemini API key missing")
            return
        }
        let language = settings.targetLanguage
        askState = .loading(title: asked, requestId: id)
        let call = client()
        // Their own tasks: leaving the page mustn't throw away a paid call.
        Task {
            guard let heading = try? await call.askTitle(apiKey: apiKey, title: title, artist: artist, album: album,
                                                         question: asked, targetLanguage: language),
                  case .loading(_, id) = askState else { return }
            askState = .loading(title: heading, requestId: id)
        }
        Task {
            let next: AskBarState
            do {
                let reply = try await call.ask(apiKey: apiKey, title: title, artist: artist, album: album,
                                               question: asked, targetLanguage: language)
                store.saveAsk(title: title, artist: artist, album: album, question: asked, reply: reply,
                              language: language)
                next = .idle
            } catch is CancellationError {
                next = .idle
            } catch {
                next = .error((error as? GeminiClient.Failure)?.message ?? "Failed to ask Gemini")
            }
            guard id == askRequestId else { return }
            askState = next
        }
    }

    /// A key was just saved: songs that said "no key" may ask now.
    func keyChanged() {
        for (k, p) in phases where p == .noKey { phases[k] = .idle }
    }
}
