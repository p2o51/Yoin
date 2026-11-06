import XCTest
@testable import Yoin

/// Page 2's About pieces and notes overflow (parity B-F9, B-F10), as Android buildAlbumScrapbook builds them.
@MainActor
final class ScrapbookContentTests: XCTestCase {
    private func entry(_ kind: String, _ key: String, answer: String, prompt: String? = nil, title: String? = nil) -> SongAboutStore.Entry {
        SongAboutStore.Entry(titleKey: "t", artistKey: "a", albumKey: "al", titleDisplay: "T", artistDisplay: "A",
                             albumDisplay: "Al", kind: kind, entryKey: key, promptText: prompt, titleText: title,
                             answerText: answer, createdAt: 0, updatedAt: 0, language: "en")
    }

    func test_should_show_the_newest_question_and_count_the_rest() {
        let pieces = ScrapAboutPieces.of([
            entry("ask", "why", answer: "Because. It works.", prompt: "Why the echo?", title: "The echo"),
            entry("ask", "who", answer: "Them.", prompt: "Who plays?", title: "Players"),
        ])
        XCTAssertEqual(pieces.ask, .init(question: "Why the echo?", title: "The echo", answer: "Because. It works."))
        XCTAssertEqual(pieces.moreAsks, 1)
        XCTAssertNil(pieces.aboutLine)
        XCTAssertTrue(pieces.needsCluster)
    }

    func test_should_show_the_paragraphs_first_sentence_when_there_is_no_question() {
        let pieces = ScrapAboutPieces.of([
            entry("canonical", SongAboutStore.Canon.review, answer: "Written in a week. Recorded live."),
        ])
        XCTAssertEqual(pieces.aboutLine, "Written in a week.")
        XCTAssertTrue(pieces.tags.isEmpty)
    }

    func test_should_tag_short_facts_in_order_and_count_the_hidden() {
        let pieces = ScrapAboutPieces.of([
            entry("canonical", SongAboutStore.Canon.producer, answer: "P"),
            entry("canonical", SongAboutStore.Canon.creationTime, answer: "2019"),
            entry("canonical", SongAboutStore.Canon.composer, answer: "C"),
            entry("canonical", SongAboutStore.Canon.lyricist, answer: "L"),
            entry("canonical", SongAboutStore.Canon.creationLocation, answer: ""),
        ])
        XCTAssertEqual(pieces.tags.map(\.label), ["Created", "Lyricist", "Composer"])
        XCTAssertEqual(pieces.hiddenTags, 1)
        XCTAssertTrue(pieces.touches)
    }

    func test_should_leave_the_track_alone_when_it_has_no_about() {
        XCTAssertEqual(ScrapAboutPieces.of([]), .none)
        XCTAssertFalse(ScrapAboutPieces.of([entry("canonical", "review", answer: " ")]).touches)
    }

    func test_should_cut_answers_at_whole_sentences_within_the_budget() {
        let long = String(repeating: "word ", count: 30) + "end. Second sentence here."
        let e = ScrapbookText.answerExcerpt(long, budget: 130)
        XCTAssertTrue(e.text.hasSuffix("end."))
        XCTAssertTrue(e.truncated)
        XCTAssertEqual(ScrapbookText.answerExcerpt("One. Two. Three.", budget: 130),
                       ScrapExcerpt(text: "One. Two.", truncated: true))
        XCTAssertEqual(ScrapbookText.weightedLength("**你好**ab"), 6)
    }

    func test_should_say_how_many_notes_wait_in_the_sheet() {
        XCTAssertEqual(ScrapbookText.hiddenNotes(1), "+1 note")
        XCTAssertEqual(ScrapbookText.hiddenNotes(4), "+4 notes")
    }

    func test_should_scroll_without_a_sheet_when_viewing_in_scrapbook() {
        let request = ScrapbookFocusRequest()
        let id = MediaId(provider: "subsonic", rawId: "s1")
        request.request(songId: id, openSheet: false)
        XCTAssertEqual(request.take(for: [id])?.openSheet, false)
        request.request(songId: id)
        XCTAssertEqual(request.take(for: [id])?.openSheet, true)
    }
}
