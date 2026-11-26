import Foundation
import Observation
import YoinShared

/// Writes an album's About once and keeps it in `AlbumAboutStore`: Android `YoinRepository.ensureAlbumAbout` /
/// `regenerateAlbumAbout` / `restoreAlbumAbout` / `askAboutAlbum` plus `AlbumDetailViewModel`'s
/// `scrapGemini.albumAbout` / `albumAsking` (one call in flight per album).
///
/// - A failure (no answer at all) stores nothing: the next visit asks again.
/// - An answer with nothing in it is stored (two canonical rows), so the page doesn't ask on every visit; the
///   page then shows the quiet ✦ circle.
/// - Regenerate keeps the old rows when the answer is empty ("nothing new") and hands them back for Undo.
@MainActor
@Observable
final class AlbumAboutLoader {
    static let shared = AlbumAboutLoader()

    /// The calls the loader makes (`GeminiClient` by default; tests answer them in place).
    struct Calls: Sendable {
        var about: @Sendable (AiSettings, AlbumAboutGrounding, String) async throws -> GeminiClient.AlbumAboutRun
        var ask: @Sendable (AiSettings, AlbumAboutGrounding, String, String) async throws -> GeminiClient.AskAnswer

        static let live = Calls(
            about: { ai, grounding, language in
                try await GeminiClient(ai: ai).albumAbout(ai: ai, grounding: grounding, targetLanguage: language)
            },
            ask: { ai, grounding, question, language in
                try await GeminiClient(ai: ai).askAlbum(ai: ai, grounding: grounding, question: question,
                                                        targetLanguage: language)
            })
    }

    /// How Regenerate went (Android `AlbumAboutRegenerateResult`).
    enum RegenerateResult: Equatable {
        /// Written again; `before` is what was there (Undo writes it back).
        case success(before: [AlbumAboutStore.Entry])
        /// Answered with nothing: what was there stays.
        case nothingNew
        case apiKeyMissing
        case error(String)
    }

    /// A question asked from the About sheet that the AI has not answered yet.
    struct Asking: Hashable {
        var album: AlbumAboutStore.AlbumKey
        var question: String
    }

    /// Albums whose About is being written (the first time, or Regenerate): the card shows the AI's dots.
    private(set) var writing: Set<AlbumAboutStore.AlbumKey> = []
    private(set) var asking: [Asking] = []

    @ObservationIgnored let store: AlbumAboutStore
    @ObservationIgnored let settings: GeminiSettings
    @ObservationIgnored private let calls: Calls

    init(store: AlbumAboutStore? = nil, settings: GeminiSettings? = nil, calls: Calls = .live) {
        self.store = store ?? .shared
        self.settings = settings ?? .shared
        self.calls = calls
        // The store shows rows in this loader's language (a cache is per language, as for the song rows).
        let s = self.settings
        self.store.language = { s.targetLanguage }
    }

    /// The chosen AI service has what a call needs (no key: the page never shows a writing card).
    var isAiReady: Bool { settings.effectiveAiSettings.isReady }

    func isWriting(_ album: Album) -> Bool { writing.contains(AlbumAboutStore.key(album)) }

    /// Android `ensureAlbumAbout`: one searched call when the album has no About in this language yet (or
    /// `retry`). Without a key, or while a call runs, nothing happens. A failure stores nothing.
    func ensure(_ album: Album, retry: Bool = false) async {
        let key = AlbumAboutStore.key(album)
        guard !writing.contains(key) else { return }
        if !retry, !store.canonical(key).isEmpty { return }
        let ai = settings.effectiveAiSettings
        guard ai.isReady else { return }
        let language = settings.targetLanguage
        writing.insert(key)
        defer { writing.remove(key) }
        // Its own task: leaving the page mustn't throw away a paid call.
        let grounding = album.aboutGrounding
        let calls = calls
        let request = Task { try await calls.about(ai, grounding, language) }
        do {
            let run = try await request.value
            store.saveCanonical(album: album, credits: run.credits, blurb: run.blurb, language: language)
        } catch {
            // Not stored: the next visit asks again.
        }
    }

    /// Android `regenerateAlbumAbout` (the sheet's Regenerate): asked again; an empty answer keeps what was
    /// there. With Apple Music's notes only the credits are the AI's, so only they are redone. Nil while a call
    /// for this album is already running.
    func regenerate(_ album: Album) async -> RegenerateResult? {
        let key = AlbumAboutStore.key(album)
        guard !writing.contains(key) else { return nil }
        let before = store.canonical(key)
        let ai = settings.effectiveAiSettings
        guard ai.isReady else { return .apiKeyMissing }
        let language = settings.targetLanguage
        writing.insert(key)
        defer { writing.remove(key) }
        let grounding = album.aboutGrounding
        let calls = calls
        let request = Task { try await calls.about(ai, grounding, language) }
        do {
            let run = try await request.value
            if run.isEmpty { return .nothingNew }
            store.saveCanonical(album: album, credits: run.credits, blurb: run.blurb, language: language,
                                createdAt: before.map(\.createdAt).min())
            return .success(before: before)
        } catch is CancellationError {
            return .nothingNew
        } catch {
            return .error((error as? GeminiClient.Failure)?.message ?? "Failed to ask the AI")
        }
    }

    /// Undo of Regenerate: the rows as they were.
    func restore(_ rows: [AlbumAboutStore.Entry]) {
        store.restore(rows)
    }

    /// Android `askAboutAlbum`: the answer is kept as an ask row keyed by the normalized question; asking again
    /// refreshes it and keeps its place. Returns false (and asks nothing) without a key.
    @discardableResult
    func ask(_ album: Album, question: String, onFailure: @escaping @MainActor (Bool) -> Void = { _ in }) -> Bool {
        let asked = question.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !asked.isEmpty else { return false }
        let ai = settings.effectiveAiSettings
        guard ai.isReady else {
            onFailure(true)
            return false
        }
        let language = settings.targetLanguage
        let pending = Asking(album: AlbumAboutStore.key(album), question: asked)
        asking.append(pending)
        let grounding = album.aboutGrounding
        let calls = calls
        let store = store
        Task {
            do {
                let reply = try await calls.ask(ai, grounding, asked, language)
                store.saveAsk(album: album, question: asked, reply: reply, language: language)
            } catch is CancellationError {
            } catch {
                onFailure(false)
            }
            if let i = asking.firstIndex(of: pending) { asking.remove(at: i) }
        }
        return true
    }

    func pendingQuestions(_ album: Album) -> [String] {
        let key = AlbumAboutStore.key(album)
        return asking.filter { $0.album == key }.map(\.question)
    }

    #if DEBUG
    /// `-albumAboutDemo pending`: the card as it is while the About is first written.
    func demoWriting(_ album: Album) { writing.insert(AlbumAboutStore.key(album)) }
    #endif
}

/// What the scrapbook shows for the album's About (Android `ScrapPiece.AlbumAbout` and the card's branches):
/// the paragraph card, the AI's dots while it is first written, the newest question when there is no paragraph,
/// the quiet ✦ circle when the AI was asked and knew nothing, or nothing at all (never asked, or it failed).
enum AlbumAboutPiece: Equatable {
    /// The paragraph (Apple Music's notes when `fromService`), at most 4 lines on the paper.
    case paragraph(text: String, fromService: Bool, asks: [AlbumAboutAsk])
    /// Being written for the first time.
    case pending
    /// No paragraph, but questions about the album: the newest as an Ask card.
    case questions([AlbumAboutAsk])
    /// Asked, and the AI knew nothing: the dashed ✦ circle that opens the sheet.
    case empty

    /// Android `buildAlbumScrapbook`'s About piece. [serviceNotes] (Apple Music) come before the AI's paragraph.
    static func of(_ content: AlbumAboutContent, serviceNotes: String?, writing: Bool) -> AlbumAboutPiece? {
        let notes = serviceNotes?.trimmingCharacters(in: .whitespacesAndNewlines)
        let service = notes?.isEmpty == false ? notes : nil
        let blurb = service ?? content.blurb
        if let blurb { return .paragraph(text: blurb, fromService: service != nil, asks: content.asks) }
        if writing { return .pending }
        if !content.asks.isEmpty { return .questions(content.asks) }
        if content.generated { return .empty }
        return nil
    }

    /// The paragraph is Apple Music's (credited to Apple Music; Regenerate only redoes the credits).
    var fromService: Bool {
        if case .paragraph(_, let fromService, _) = self { return fromService }
        return false
    }
}
