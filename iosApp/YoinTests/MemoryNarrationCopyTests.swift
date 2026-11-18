import XCTest
@testable import Yoin

/// Yoin's paragraph from a synced memory copy (A-U1): Android's tagged narration is read, never shown raw.
final class MemoryNarrationCopyTests: XCTestCase {
    func test_should_join_narration_and_question_when_copy_is_tagged() {
        let copy = "[NARRATION]37 plays since March, the last one two days ago.[/NARRATION]\n[QUESTION]You gave it a 9.5. What earned it?[/QUESTION]"
        XCTAssertEqual(MemoryNarrationCopy.paragraph(copy),
                       "37 plays since March, the last one two days ago. You gave it a 9.5. What earned it?")
    }

    func test_should_read_chinese_question_mark_and_loose_tags() {
        let copy = "[ narration ]你跨了三个季节回来听。[/ NARRATION ]\n[QUESTION]“那整张专辑呢？”[/QUESTION]"
        XCTAssertEqual(MemoryNarrationCopy.paragraph(copy), "你跨了三个季节回来听。 那整张专辑呢？")
    }

    func test_should_strip_markers_when_tagged_copy_does_not_parse() {
        let copy = "[NARRATION]Three seasons of plays.[/NARRATION]"
        XCTAssertEqual(MemoryNarrationCopy.paragraph(copy), "Three seasons of plays.")
        XCTAssertNil(MemoryNarrationCopy.paragraph("[NARRATION][/NARRATION]"))
    }

    func test_should_keep_plain_prose_with_brackets_as_it_is() {
        XCTAssertEqual(MemoryNarrationCopy.paragraph("  Back to [Remastered] again. Why now? "),
                       "Back to [Remastered] again. Why now?")
        XCTAssertNil(MemoryNarrationCopy.paragraph("   "))
    }
}
