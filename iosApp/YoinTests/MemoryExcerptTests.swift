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

    func test_should_sizeTheExcerptInAndroidsThreeSteps() {
        XCTAssertEqual(MemoryExcerptFit.size("好听。"), 22)
        XCTAssertEqual(MemoryExcerptFit.size(String(repeating: "a", count: 16)), 22)
        XCTAssertEqual(MemoryExcerptFit.size(String(repeating: "a", count: 17)), 17)
        XCTAssertEqual(MemoryExcerptFit.size(String(repeating: "a", count: 60)), 17)
        XCTAssertEqual(MemoryExcerptFit.size(String(repeating: "a", count: 61)), 16)
    }

    func test_should_handleEmptyAndZeroWidthInputs_when_pickingCachedExcerpts() {
        XCTAssertNil(MemoryExcerptFit.pick([], width: 324))
        XCTAssertNil(MemoryExcerptFit.pick(["Short."], width: 0))
        XCTAssertNil(MemoryExcerptFit.pick(["Short."], width: -12))
        XCTAssertEqual(MemoryExcerptFit.pick(["Short."], width: 324), "Short.")
        XCTAssertEqual(MemoryExcerptFit.pick(["Short."], width: 324), "Short.")
    }

    @MainActor
    func test_should_deferFarNeighbourMountUntilSettled_when_memoriesPagerSwipes() {
        let pager = MemoriesPagerModel()
        XCTAssertEqual(pager.visibleRange, [])

        pager.snap(to: 0, count: 5)
        XCTAssertEqual(pager.visibleRange, [0, 1])

        // First pixel of swipe from 0 -> 1 must NOT mount card 2 mid-swipe.
        pager.beginMove()
        pager.position = 0.001
        XCTAssertEqual(pager.visibleRange, [0, 1])
        pager.position = 0.72
        XCTAssertEqual(pager.visibleRange, [0, 1])

        // Once settled at 1.0, card 2 pre-mounts at rest for the next swipe.
        pager.position = 1.0
        pager.endMove()
        XCTAssertEqual(pager.visibleRange, [0, 1, 2])

        // Continuous flick past 1.0 into 1.05 mounts card 2 immediately if not already warm.
        pager.snap(to: 0, count: 5)
        pager.beginMove()
        pager.position = 1.05
        XCTAssertTrue(pager.visibleRange.contains(2))

        // Rubber-band past 0 stays valid.
        pager.position = -0.18
        XCTAssertEqual(pager.currentIndex, 0)
        XCTAssertTrue(pager.visibleRange.contains(0))
    }

    @MainActor
    func test_should_mountIncomingDiaryDuringHorizontalSwipe_while_skippingOffscreenNeighboursAtRest() {
        let pager = MemoriesPagerModel()
        pager.snap(to: 1, count: 4)

        // At rest on card 1: opening the diary mounts ONLY card 1, never offscreen ±1 neighbours (0 and 2).
        XCTAssertFalse(pager.mountsDiary(for: 0, diaryShown: true, warm: false, presentationOpen: true))
        XCTAssertTrue(pager.mountsDiary(for: 1, diaryShown: true, warm: false, presentationOpen: true))
        XCTAssertFalse(pager.mountsDiary(for: 2, diaryShown: true, warm: false, presentationOpen: true))

        // First pixel of a horizontal swipe in diary mode (1.0 -> 1.01) mounts incoming card 2 immediately
        // while keeping outgoing card 1 mounted across the 50% midpoint with zero blank gap or pop.
        pager.beginMove()
        pager.position = 1.01
        XCTAssertFalse(pager.mountsDiary(for: 0, diaryShown: true, warm: false, presentationOpen: true))
        XCTAssertTrue(pager.mountsDiary(for: 1, diaryShown: true, warm: false, presentationOpen: true))
        XCTAssertTrue(pager.mountsDiary(for: 2, diaryShown: true, warm: false, presentationOpen: true))

        pager.position = 1.49
        XCTAssertTrue(pager.mountsDiary(for: 1, diaryShown: true, warm: false, presentationOpen: true))
        XCTAssertTrue(pager.mountsDiary(for: 2, diaryShown: true, warm: false, presentationOpen: true))

        pager.position = 1.51
        XCTAssertTrue(pager.mountsDiary(for: 1, diaryShown: true, warm: false, presentationOpen: true))
        XCTAssertTrue(pager.mountsDiary(for: 2, diaryShown: true, warm: false, presentationOpen: true))

        // Once settled at 2.0, card 1 is wholly offscreen (>= 1.0 page away) and unmounts its diary.
        pager.position = 2.0
        pager.endMove()
        XCTAssertFalse(pager.mountsDiary(for: 1, diaryShown: true, warm: false, presentationOpen: true))
        XCTAssertTrue(pager.mountsDiary(for: 2, diaryShown: true, warm: false, presentationOpen: true))
    }
}
