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

    // MARK: Regenerate one row

    /// How "Regenerate with Gemini" on one row went (Android `AboutRegenerateResult`).
    enum RegenerateResult: Equatable {
        /// Gemini wrote the row again; `beforeTitle` / `beforeAnswer` put it back (Undo).
        case success(beforeTitle: String?, beforeAnswer: String)
        /// Gemini came back with nothing for this row: it was left as it was.
        case nothingNew
        case apiKeyMissing
        case error(String)
    }

    /// The rows Gemini is writing again right now (their text shows thinking dots).
    private(set) var regenerating: Set<SongAboutStore.Entry.Ref> = []

    /// Android `regenerateAboutEntry` (owner 2026-10-29: what Gemini said can go
    /// out of date): a question is asked again in its own words; a canonical
    /// field is fetched again with the rest of the song's About and only that
    /// field is written. The row keeps its place and its text until the new
    /// one lands. The listener's own entries are never regenerated. Nil when
    /// the row is already being regenerated.
    func regenerate(_ ref: SongAboutStore.Entry.Ref) async -> RegenerateResult? {
        guard !regenerating.contains(ref) else { return nil }
        guard let row = store.entry(ref), row.kind != SongAboutStore.Kind.own else { return .nothingNew }
        let apiKey = settings.requestKey
        guard !apiKey.isEmpty else { return .apiKeyMissing }
        let language = settings.targetLanguage
        regenerating.insert(ref)
        defer { regenerating.remove(ref) }
        let call = client()
        // Its own task: leaving the page mustn't throw away a paid call.
        let request = Task { () throws -> (String?, String) in
            if row.kind == SongAboutStore.Kind.ask {
                let question = row.promptText.flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 }
                    ?? row.entryKey
                let reply = try await call.ask(apiKey: apiKey, title: row.titleDisplay, artist: row.artistDisplay,
                                               album: row.albumDisplay, question: question, targetLanguage: language)
                return (reply.title, reply.answer)
            }
            let values = try await call.canonicalAbout(apiKey: apiKey, title: row.titleDisplay,
                                                       artist: row.artistDisplay, album: row.albumDisplay,
                                                       targetLanguage: language)
            return (row.titleText, values.first { $0.key == row.entryKey }?.answer ?? "")
        }
        do {
            let (title, answer) = try await request.value
            let text = answer.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !text.isEmpty, var now = store.entry(ref) else { return .nothingNew }
            now.titleText = title
            now.answerText = text
            now.updatedAt = Int64((Date().timeIntervalSince1970 * 1000).rounded())
            store.upsert([now])
            SongAboutStore.onLocalChange?()
            return .success(beforeTitle: row.titleText, beforeAnswer: row.answerText)
        } catch is CancellationError {
            return .nothingNew
        } catch {
            return .error((error as? GeminiClient.Failure)?.message ?? "Failed to ask Gemini")
        }
    }

    #if DEBUG
    /// `-aboutDemo thinking`: a row shown as being written again.
    func demoRegenerating(_ ref: SongAboutStore.Entry.Ref) { regenerating.insert(ref) }
    #endif

    /// Android `undoAboutRegenerate`: the row says what it said before.
    func undoRegenerate(_ ref: SongAboutStore.Entry.Ref, title: String?, answer: String) {
        store.edit(ref, title: title, answer: answer)
    }

    #if os(iOS)
    /// Regenerate from Now Playing or the scrapbook, told in the shell's toast:
    /// "Regenerated" with Undo, or why nothing changed (Android
    /// `np_about_regenerate_*` / `detail_scrap_gemini_*`; one wording for both).
    func regenerateAndReport(_ ref: SongAboutStore.Entry.Ref, toast: PlaylistActions = .shared) {
        Task {
            guard let result = await regenerate(ref) else { return }
            switch result {
            case .success(let title, let answer):
                toast.show("Regenerated", action: .init(label: "Undo") { [weak self] in
                    self?.undoRegenerate(ref, title: title, answer: answer)
                })
            case .nothingNew: toast.show("Gemini had nothing new")
            case .apiKeyMissing: toast.show("Gemini API key missing")
            case .error: toast.show("Gemini couldn't answer. Try again.")
            }
        }
    }
    #endif

    // MARK: Ask from the scrapbook

    /// A question asked from the scrapbook's track sheet that Gemini has not answered yet.
    struct Asking: Hashable {
        var song: SongAboutStore.Entry.SongKey
        var question: String
    }

    private(set) var asking: [Asking] = []

    #if os(iOS)
    /// Android `askFromScrapbook`: the answer joins the song's About like one
    /// asked in Now Playing; Now Playing's Ask bar isn't involved. A failure is
    /// told in the toast.
    func askFromScrapbook(title: String, artist: String, album: String, question: String,
                          toast: PlaylistActions = .shared) {
        let asked = question.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !asked.isEmpty else { return }
        let apiKey = settings.requestKey
        guard !apiKey.isEmpty else {
            toast.show("Gemini API key missing")
            return
        }
        let language = settings.targetLanguage
        let pending = Asking(song: SongAboutStore.key(title: title, artist: artist, album: album), question: asked)
        asking.append(pending)
        let call = client()
        Task {
            do {
                let reply = try await call.ask(apiKey: apiKey, title: title, artist: artist, album: album,
                                               question: asked, targetLanguage: language)
                store.saveAsk(title: title, artist: artist, album: album, question: asked, reply: reply,
                              language: language)
            } catch is CancellationError {
            } catch {
                toast.show("Gemini couldn't answer. Try again.")
            }
            if let i = asking.firstIndex(of: pending) { asking.remove(at: i) }
        }
    }
    #endif

    /// A key was just saved: songs that said "no key" may ask now.
    func keyChanged() {
        for (k, p) in phases where p == .noKey { phases[k] = .idle }
    }
}
