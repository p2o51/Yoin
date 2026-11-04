import XCTest
import YoinShared
@testable import Yoin

final class LyricsToolsTests: XCTestCase {
    // MARK: Select mode (Android LyricsSelectionTest)

    func test_should_toggleAndRefuseAtCap_when_tappingLines() {
        var pick: Set<Int> = []
        pick = LyricsRules.nextSelection(pick, tapped: 3).selected
        pick = LyricsRules.nextSelection(pick, tapped: 5).selected
        XCTAssertEqual(pick, [3, 5])
        XCTAssertEqual(LyricsRules.nextSelection(pick, tapped: 3).selected, [5])
        let full = Set(0..<15)
        let refused = LyricsRules.nextSelection(full, tapped: 20)
        XCTAssertTrue(refused.hitLimit)
        XCTAssertEqual(refused.selected, full)
        XCTAssertFalse(LyricsRules.nextSelection(full, tapped: 2).hitLimit, "letting go is never refused")
    }

    func test_should_labelPick_when_counting() {
        XCTAssertEqual(LyricsRules.selectionLabel(0), "Select lines")
        XCTAssertEqual(LyricsRules.selectionLabel(2), "2 / 15")
        XCTAssertEqual(LyricsRules.selectionLabel(3, limitNudge: true), "15 / 15")
    }

    func test_should_formatClipboardWithPassagesAndCredit_when_copying() {
        let starts = LyricsRules.passageStarts([1, 2, 6], lineCount: 10)
        XCTAssertEqual(starts, [2])
        let text = LyricsRules.clipboardText(lines: ["a", "b", "c"], translations: [nil, "B", nil],
                                             passageStarts: starts, title: "Song", artist: "Artist")
        XCTAssertEqual(text, "a\nb\nB\n\nc\n\n— Song · Artist")
        XCTAssertEqual(LyricsRules.clipboardText(lines: ["a"], translations: [nil], passageStarts: [],
                                                 title: " ", artist: "x"), "a")
    }

    // MARK: Key row (Android lyricsKeyWidths)

    func test_should_widenPressedKeyFromNeighbours_when_pressed() {
        let rest = LyricsRules.keyWidths(count: 4, key: 52) { _ in 0 }
        XCTAssertEqual(rest, [52, 52, 52, 52])
        let middle = LyricsRules.keyWidths(count: 4, key: 52) { $0 == 1 ? 1 : 0 }
        XCTAssertEqual(middle[1], 52 * 1.15, accuracy: 0.001)
        XCTAssertEqual(middle[0], 52 - 52 * 0.075, accuracy: 0.001)
        XCTAssertEqual(middle[2], 52 - 52 * 0.075, accuracy: 0.001)
        XCTAssertEqual(middle.reduce(0, +), 208, accuracy: 0.001)
        let end = LyricsRules.keyWidths(count: 4, key: 52) { $0 == 3 ? 1 : 0 }
        XCTAssertEqual(end[2], 52 - 52 * 0.15, accuracy: 0.001)
    }

    // MARK: Translation (Android GeminiService / YoinRepository)

    func test_should_parseTaggedTranslations_when_geminiAnswers() {
        let raw = "[L0]你好[/L0]\n[L1] 1. 世界 [/L1]\n[L7]out of range[/L7]"
        XCTAssertEqual(GeminiClient.parseLineTranslations(raw, lineCount: 2), [0: "你好", 1: "世界"])
        XCTAssertEqual(GeminiClient.parseLineTranslations("一\n二", lineCount: 2), [0: "一", 1: "二"])
        XCTAssertEqual(GeminiClient.parseLineTranslations("一", lineCount: 2), [:])
    }

    func test_should_buildAndroidPrompt_when_translating() {
        let p = GeminiClient.lyricsTranslationPrompt(title: "T", artist: "A", lines: ["x", "y"],
                                                     targetLanguage: "Japanese")
        XCTAssertTrue(p.hasPrefix("Translate these lyric lines into Japanese.\n\nSong: T\nArtist: A\n"))
        XCTAssertTrue(p.hasSuffix("Lines:\n[0] x\n[1] y"))
    }

    func test_should_skipChineseTarget_when_lyricsAlreadyChinese() {
        let zh = ["我把黄昏折成纸灯笼", "让它们漂向海港与天空相接的地方"]
        XCTAssertTrue(LyricsRules.alreadyTargetLanguage(zh, target: "Simplified Chinese"))
        XCTAssertFalse(LyricsRules.alreadyTargetLanguage(zh, target: "English"))
        XCTAssertFalse(LyricsRules.alreadyTargetLanguage(["午後のソーダが好きです", "夏の海岸通り"], target: "Simplified Chinese"))
    }

    // MARK: LRCLIB and the editor

    func test_should_parseLrclib_when_searchingAndFetching() {
        let search = Data(#"[{"id":3204719,"trackName":"Bohemian Rhapsody","artistName":"Queen"},{"name":"no id"}]"#.utf8)
        XCTAssertEqual(LrclibClient.parseSearch(search, fallbackTitle: "q"),
                       [.init(id: "3204719", title: "Bohemian Rhapsody", artist: "Queen")])
        XCTAssertEqual(LrclibClient.parseLyric(Data(#"{"syncedLyrics":"[00:01.00]a","plainLyrics":"a"}"#.utf8)), "[00:01.00]a")
        XCTAssertEqual(LrclibClient.parseLyric(Data(#"{"syncedLyrics":null,"plainLyrics":"a"}"#.utf8)), "a")
        XCTAssertNil(LrclibClient.parseLyric(Data(#"{"syncedLyrics":"","plainLyrics":" "}"#.utf8)))
    }

    func test_should_roundTripLrc_when_editing() {
        let lyrics = LrcParser.shared.parse(raw: "[01:02.50]Hello\n[00:05.00]World")
        let text = LyricsRules.editableText(lyrics)
        XCTAssertEqual(text, "[00:05.00]World\n[01:02.50]Hello")
        XCTAssertEqual(LrcParser.shared.parse(raw: text).lines.map(\.text), ["World", "Hello"])
    }

    @MainActor
    func test_should_keepChosenLyricsAndDropStaleTranslations_when_choosing() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("lyrics-user-\(UUID()).json")
        defer { try? FileManager.default.removeItem(at: url) }
        let store = LyricsUserStore(fileURL: url)
        store.saveTranslations(["x"], key: "subsonic:1", language: "English", lines: ["a"])
        store.choose("[00:01.00]a", for: "subsonic:1")
        let reopened = LyricsUserStore(fileURL: url)
        XCTAssertEqual(reopened.chosen("subsonic:1"), "[00:01.00]a")
        XCTAssertNil(reopened.translations("subsonic:1", language: "English", lines: ["a"]))
        reopened.saveTranslations(["y"], key: "subsonic:1", language: "English", lines: ["a"])
        XCTAssertEqual(LyricsUserStore(fileURL: url).translations("subsonic:1", language: "English", lines: ["a"]), ["y"])
    }
}
