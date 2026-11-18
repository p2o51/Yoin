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

    // MARK: Android lyrics_translation_cache identity (cloud sync)

    @MainActor
    private func tempStore() -> (LyricsUserStore, URL) {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("lyrics-user-\(UUID()).json")
        addTeardownBlock { try? FileManager.default.removeItem(at: url) }
        return (LyricsUserStore(fileURL: url), url)
    }

    func test_should_matchAndroidSourceHash_when_hashingFromSwift() {
        // Android YoinRepository.buildLyricsTranslationSourceHash on the JVM for the same input (UTF-16 lengths).
        XCTAssertEqual(LyricsTranslationIdentity.shared.geminiSourceHash(
            title: "夜に駆ける", artist: "YOASOBI", lines: ["沈むように溶けてゆくように", "Hello 🌙 world", "a:b"]),
                       "7683870ed73d9ab40dd19a98fe5a28a629f7ef22452ef10fb253b285b599a05e")
        XCTAssertEqual(GeminiClient.model, "gemini-3.1-flash-lite", "Android GeminiService.MODEL")
    }

    @MainActor
    func test_should_writeAndroidRows_when_savingGeminiTranslations() {
        let (store, url) = tempStore()
        var changes = 0
        LyricsUserStore.onLocalChange = { changes += 1 }
        defer { LyricsUserStore.onLocalChange = nil }
        let lines = ["沈むように溶けてゆくように", "Hello 🌙 world", "a:b"]
        store.saveGeminiTranslations(["像沉没一样", "", "say \"hi\""], provider: "subsonic", rawId: "s1",
                                     title: "夜に駆ける", artist: "YOASOBI", language: "Simplified Chinese",
                                     model: GeminiClient.model, lines: lines, now: Date(timeIntervalSince1970: 1_793_000_000))

        let row = LyricsUserStore(fileURL: url).paidTranslations.first
        XCTAssertEqual(row, .init(trackProvider: "subsonic", trackRawId: "s1",
                                  sourceHash: "7683870ed73d9ab40dd19a98fe5a28a629f7ef22452ef10fb253b285b599a05e",
                                  targetLanguage: "Simplified Chinese", model: "gemini-3.1-flash-lite",
                                  translationsJson: #"["像沉没一样","","say \"hi\""]"#, cachedAt: 1_793_000_000_000))
        XCTAssertEqual(changes, 1)
        XCTAssertEqual(store.geminiTranslations(provider: "subsonic", rawId: "s1", title: "夜に駆ける", artist: "YOASOBI",
                                                language: "Simplified Chinese", model: GeminiClient.model, lines: lines),
                       ["像沉没一样", "", "say \"hi\""])
        XCTAssertNil(store.geminiTranslations(provider: "subsonic", rawId: "s1", title: "夜に駆ける", artist: "YOASOBI",
                                              language: "Japanese", model: GeminiClient.model, lines: lines))
        // A new text of the song keeps the rows (Android keys them by source hash and never drops them).
        store.choose("[00:01.00]x", for: "subsonic:s1")
        XCTAssertEqual(store.paidTranslations.count, 1)
    }

    @MainActor
    func test_should_moveOldTranslationToAndroidRow_when_itsLinesAreSeenAgain() {
        let (store, url) = tempStore()
        var changes = 0
        LyricsUserStore.onLocalChange = { changes += 1 }
        defer { LyricsUserStore.onLocalChange = nil }
        store.saveTranslations(["一", "二"], key: "subsonic:s1", language: "Japanese", lines: ["one", "two"])

        let got = store.geminiTranslations(provider: "subsonic", rawId: "s1", title: "T", artist: "A",
                                           language: "Japanese", model: GeminiClient.model, lines: ["one", "two"])
        XCTAssertEqual(got, ["一", "二"])
        let reopened = LyricsUserStore(fileURL: url)
        XCTAssertNil(reopened.translations("subsonic:s1", language: "Japanese", lines: ["one", "two"]))
        let row = reopened.paidTranslations.first
        XCTAssertEqual(row?.sourceHash, LyricsTranslationIdentity.shared.sourceHash(title: "T", artist: "A", lines: ["one", "two"]))
        XCTAssertEqual(row?.translationsJson, #"["一","二"]"#)
        XCTAssertEqual(changes, 1)
    }

    @MainActor
    func test_should_readAndSeedLyricsAsAndroidCacheRows_when_syncing() {
        let (store, _) = tempStore()
        store.choose("[00:01.00]typed", for: "subsonic:typed")
        store.choose("[00:01.00]picked", for: "subsonic:picked", source: LyricsSource(provider: "qq", songId: "9"))
        store.saveAuto("[00:01.00]auto", source: LyricsSource(provider: "lrclib", songId: "7"), for: "subsonic:auto",
                       now: Date(timeIntervalSince1970: 1_000))
        XCTAssertEqual(store.cachedLyrics("subsonic:typed")?.lyricsProvider, "manual")
        XCTAssertEqual(store.cachedLyrics("subsonic:picked")?.lyricsProviderSongId, "user|9")
        XCTAssertEqual(store.cachedLyrics("subsonic:auto"),
                       .init(lyricsProvider: "lrclib", lyricsProviderSongId: "7", lrc: "[00:01.00]auto", cachedAt: 1_000_000))

        XCTAssertTrue(store.syncWriteLyrics(.init(lyricsProvider: "netease", lyricsProviderSongId: "user|5",
                                                  lrc: "[00:01.00]n", cachedAt: 2), for: "subsonic:new"))
        XCTAssertEqual(store.chosen("subsonic:new"), "[00:01.00]n")
        XCTAssertEqual(store.chosenSource("subsonic:new"), LyricsSource(provider: "netease", songId: "5"))
        XCTAssertTrue(store.syncWriteLyrics(.init(lyricsProvider: "qq", lyricsProviderSongId: "3",
                                                  lrc: "[00:01.00]q", cachedAt: 2_000), for: "subsonic:auto"))
        XCTAssertEqual(store.cachedLyrics("subsonic:auto")?.lrc, "[00:01.00]q")
    }

    @MainActor
    func test_should_reportOnlyTheUsersLanguageChange_when_saving() {
        let defaults = UserDefaults(suiteName: "lyrics-tools-\(UUID())")!
        let settings = GeminiSettings(keychain: GeminiKeychain(service: "test.\(UUID())"), defaults: defaults)
        var changes = 0
        GeminiSettings.onLocalChange = { changes += 1 }
        defer { GeminiSettings.onLocalChange = nil }
        settings.saveLanguage("Korean")
        settings.saveLanguage("Korean")
        XCTAssertTrue(settings.syncSetLanguage("French"))
        XCTAssertFalse(settings.syncSetLanguage("Klingon"))
        XCTAssertEqual(settings.targetLanguage, "French")
        XCTAssertEqual(changes, 1)
    }

    /// #185: the sung line's centre at 40 % of the window, wherever the lyric page sits.
    @MainActor
    func test_should_seatTheLineAtFortyPercentOfTheScreen() {
        // A full-screen page (top 0, 800 tall in an 800 window): 0.4·800 − 16 = 304 of a 720 band ≈ 0.42.
        XCTAssertEqual(ExpandedLyricsList.screenSeatFraction(windowTop: 0, window: 800, screen: 800), 0.42, accuracy: 0.001)
        // A page starting below the seat keeps a few lines above the row (held at 0.2).
        XCTAssertEqual(ExpandedLyricsList.screenSeatFraction(windowTop: 500, window: 300, screen: 800), 0.2, accuracy: 0.001)
        // One ending above it never sinks past the band's middle.
        XCTAssertEqual(ExpandedLyricsList.screenSeatFraction(windowTop: -400, window: 400, screen: 800), 0.5, accuracy: 0.001)
        // Nothing known: the middle, as before.
        XCTAssertEqual(ExpandedLyricsList.screenSeatFraction(windowTop: 0, window: 400, screen: 0), 0.5, accuracy: 0.001)
    }
}
