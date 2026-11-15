import XCTest
@testable import Yoin

/// The quick questions under an empty Ask field (Android GeminiPrompts: `remainingQuickPrompts`,
/// `askedQuestions` and the scrapbook's asks).
@MainActor
final class QuickPromptsTests: XCTestCase {
    private func row(_ kind: String, key: String, prompt: String? = nil, title: String? = nil,
                     answer: String = "An answer") -> SongAboutStore.Entry {
        SongAboutStore.Entry(titleKey: "t", artistKey: "a", albumKey: "al", titleDisplay: "T", artistDisplay: "A",
                             albumDisplay: "Al", kind: kind, entryKey: key, promptText: prompt, titleText: title,
                             answerText: answer, createdAt: 0, updatedAt: 0, language: "en")
    }

    func test_should_offerAndroidsSixQuestions_inItsOrder() {
        XCTAssertEqual(QuickPrompts.all, [loc("What does this song sample?"), loc("Who sampled this song?"),
                                          loc("What are the lyrics about?"), loc("How was it written?"),
                                          loc("What covers are there?"), loc("How was it received?")])
        XCTAssertEqual(QuickPrompts.remaining(asked: []), QuickPrompts.all)
    }

    func test_should_leaveOutQuestions_askedInAnyCaseOrSpacing() {
        let prompts = ["What does this song sample?", "Who sampled this song?", "How was it written?"]
        XCTAssertEqual(QuickPrompts.remaining(prompts, asked: ["  who SAMPLED   this song? "]),
                       ["What does this song sample?", "How was it written?"])
    }

    func test_should_countAsksAndOwnHeadings_notFactsOrRemovedRows() {
        let rows = [
            row(SongAboutStore.Kind.canonical, key: SongAboutStore.Canon.review, answer: "A review"),
            // An Ask row: the question as typed, not Gemini's heading for it.
            row(SongAboutStore.Kind.ask, key: "how was it written?", prompt: "How was it written?",
                title: "Writing the song"),
            // The listener's own entry, renamed since: its heading now.
            row(SongAboutStore.Kind.own, key: "notes", title: "What covers are there?"),
            // Removed in the scrapbook: offered again.
            row(SongAboutStore.Kind.ask, key: "who sampled this song?", prompt: "Who sampled this song?", answer: " "),
            // From before questions were kept: its key.
            row(SongAboutStore.Kind.ask, key: "how was it received?"),
        ]
        let asked = QuickPrompts.asked(in: rows)
        XCTAssertEqual(asked, ["How was it written?", "What covers are there?", "how was it received?"])
        XCTAssertEqual(QuickPrompts.remaining(["Who sampled this song?", "How was it written?", "What covers are there?",
                                               "How was it received?"], asked: asked),
                       ["Who sampled this song?"])
    }
}
