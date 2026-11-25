import Foundation
import YoinShared

// Immutable Kotlin data classes (shared `albumabout`).
extension AlbumAiCredits: @retroactive @unchecked Sendable {}
extension AlbumAboutGrounding: @retroactive @unchecked Sendable {}
extension AlbumAboutContent: @retroactive @unchecked Sendable {}
extension AlbumAboutAsk: @retroactive @unchecked Sendable {}
extension AlbumCreditItem: @retroactive @unchecked Sendable {}

/// An album's About and the questions asked about it (Android `GeminiService.runAlbumAbout` /
/// `askAboutAlbum`). The prompts, the search queries and the reply parser are :shared's `YoinAlbumAbout`; this
/// file only runs the call through whichever AI service is chosen, with Yoin's own search when it has one.
extension GeminiClient {
    /// What one album About call came back with (stored as two canonical rows).
    struct AlbumAboutRun: Equatable, Sendable {
        var credits: AlbumAiCredits
        /// The editorial paragraph; nil when the AI wrote none, or when the service's notes are the paragraph.
        var blurb: String?
        var rawText: String
        var searchQueries: [String]

        /// Answered, but with nothing in it: a Regenerate that comes back like this keeps what was there.
        var isEmpty: Bool { blurb == nil && credits.isEmpty }
    }

    /// The album About call's temperature (Android `ALBUM_ABOUT_TEMPERATURE`): credits are facts first, and the
    /// paragraph is editorial, not lyrical.
    static let albumAboutTemperature = 0.4

    /// The credits and the editorial paragraph in one searched call; only items the model vouches for come back.
    /// With `grounding.serviceNotes` (Apple Music) it is the credits-only prompt, and no AI paragraph is kept.
    /// A reply that is no answer at all (empty, refused, off-format) throws: nothing is stored, the next visit
    /// asks again.
    func albumAbout(ai settings: AiSettings, grounding: AlbumAboutGrounding,
                    targetLanguage: String) async throws -> AlbumAboutRun {
        let about = YoinAlbumAbout.shared
        let queries = about.searchQueries(grounding: grounding, count: Int32(settings.searchQueries))
        let searched = try await searchFor(ai: settings, queries: queries)
        let lead = about.searchLead(builtInSearch: settings.searchKind == .builtIn, searchedPages: Int32(searched.pages))
        let prompt = about.aboutPrompt(grounding: grounding, targetLanguage: targetLanguage, searchLead: lead)
            + searched.appendix
        let raw = try await generateText(ai: settings, prompt: prompt, temperature: Self.albumAboutTemperature,
                                         useSearch: true, timeout: 60, requireNonEmpty: false)
        let reply = about.storedReply(rawText: raw, hasServiceNotes: grounding.serviceNotes != nil)
        guard reply.answered else { throw Failure(message: "No album About in AI response") }
        return AlbumAboutRun(credits: reply.credits, blurb: reply.blurb, rawText: raw,
                             searchQueries: settings.searchKind == .external ? queries : [])
    }

    /// Searched free-form Q&A about an album: the song Ask's `[TITLE]` / `[ANSWER]` reply.
    func askAlbum(ai settings: AiSettings, grounding: AlbumAboutGrounding, question: String,
                  targetLanguage: String) async throws -> AskAnswer {
        let about = YoinAlbumAbout.shared
        let searched = try await searchFor(ai: settings, queries: ["\(grounding.album) \(grounding.artist) \(question)"])
        let lead = about.searchLead(builtInSearch: settings.searchKind == .builtIn, searchedPages: Int32(searched.pages))
        let prompt = about.askPrompt(grounding: grounding, question: question, targetLanguage: targetLanguage,
                                     searchLead: lead) + searched.appendix
        let raw = try await generateText(ai: settings, prompt: prompt, temperature: 0.8, useSearch: true,
                                         timeout: 45, requireNonEmpty: true)
        return Self.parseAsk(raw, fallbackQuestion: question)
    }
}

extension Album {
    /// The service's facts about this release, for the prompt (Android `Album.toAboutGrounding`).
    var aboutGrounding: AlbumAboutGrounding {
        let titles = tracks.map { $0.title.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
        // The service's length as it is, else the tracks' sum when there is one (Android `durationSec ?: sum`).
        let sum = tracks.map(\.durationSec).reduce(0, +)
        let total = durationSec ?? (sum > 0 ? sum : nil)
        return AlbumAboutGrounding(album: title, artist: artist, version: versionTag, releaseDate: releaseDate,
                                   originalReleaseDate: originalReleaseDate, labels: recordLabels,
                                   releaseType: kind.rawValue, genre: genre, tracks: titles,
                                   totalDurationSec: total.map { KotlinInt(int: Int32($0)) },
                                   serviceNotes: editorialNotes)
    }

}
