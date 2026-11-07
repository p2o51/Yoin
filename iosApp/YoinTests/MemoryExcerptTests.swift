import XCTest
@testable import Yoin

/// Memories' excerpt in whole sentences (MemorySentences / MemoryExcerptFit, Android MemoryExcerpt,
/// parity A-U3): never cut mid-sentence, never an ellipsis.
final class MemoryExcerptTests: XCTestCase {
    func test_should_splitSentences_when_decimalsAndClosersAppear() {
        XCTAssertEqual(MemorySentences.split("It got an 8.5. Then “stay.” Fine!"), ["It got an 8.5.", "Then “stay.”", "Fine!"])
        XCTAssertEqual(MemorySentences.split("第一句。第二句！没有句号"), ["第一句。", "第二句！", "没有句号"])
    }

    func test_should_joinWithoutSpaces_afterChinese() {
        XCTAssertEqual(MemorySentences.join(["第一句。", "第二句。"]), "第一句。第二句。")
        XCTAssertEqual(MemorySentences.join(["One.", "Two."]), "One. Two.")
    }

    func test_should_offerTheMostSentencesFirst_when_aReviewIsLong() {
        XCTAssertEqual(MemorySentences.reviewCandidates("A one. B two. C three.\nSecond paragraph."),
                       ["A one. B two. C three.", "A one. B two.", "A one."])
    }

    func test_should_pickWholeSentencesThatFit_when_theReviewRunsLong() {
        let long = Array(repeating: "This sentence keeps the rhythm of the whole record going for a while.", count: 6)
            .joined(separator: " ")
        let picked = MemoryExcerptFit.pick(MemorySentences.reviewCandidates(long), width: 324)
        XCTAssertNotNil(picked)
        XCTAssertTrue(picked!.hasSuffix("."), "ends on a whole sentence")
        XCTAssertLessThan(picked!.count, long.count)
        XCTAssertLessThanOrEqual(MemoryExcerptFit.lines(picked!, width: 324), MemoryExcerptFit.maxLines)
    }

    func test_should_showTheAttributionAlone_when_nothingFits() {
        let wall = String(repeating: "word ", count: 200)
        XCTAssertNil(MemoryExcerptFit.pick([wall], width: 324))
        XCTAssertEqual(MemoryExcerptFit.pick(["Short."], width: 324), "Short.")
    }
}
