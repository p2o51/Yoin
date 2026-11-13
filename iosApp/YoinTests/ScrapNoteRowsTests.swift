import XCTest
@testable import Yoin

/// The scrapbook sheet's Notes tab rows (Android AlbumScrapbookSheetModelTest, scrapNoteRows).
final class ScrapNoteRowsTests: XCTestCase {
    /// "L0" at 0:00 … "L19" at 3:10, ten seconds apart.
    private let lines = (0...19).map { ScrapLyricLine(startMs: Int64($0) * 10_000, text: "L\($0)") }

    func test_should_findTheLinePlaying_when_aMomentIsGiven() {
        XCTAssertEqual(ScrapNoteRows.lyricIndexAt([ScrapLyricLine(startMs: 5_000, text: "first")], 1_000), -1)
        XCTAssertEqual(ScrapNoteRows.lyricIndexAt(lines, 0), 0)
        XCTAssertEqual(ScrapNoteRows.lyricIndexAt(lines, 61_000), 6)
        XCTAssertEqual(ScrapNoteRows.lyricIndexAt(lines, 999_000), 19)
        XCTAssertEqual(ScrapNoteRows.lyricIndexAt([], 1_000), -1)
    }

    func test_should_showThreeLinesEachSide_when_aNoteSitsMidSong() {
        let rows = ScrapNoteRows.rows(lines: lines, notes: [note("a", 61_000)], expanded: false)

        XCTAssertEqual(rows.map(label), ["L3", "L4", "L5", "L6", "note a", "L7", "L8", "L9"])
        let noted = rows.compactMap { row -> Int? in
            if case .lyric(let index, _, true) = row { return index }
            return nil
        }
        XCTAssertEqual(noted, [6])
    }

    func test_should_mergePassages_when_notesAreClose_andGapThemOtherwise() {
        let rows = ScrapNoteRows.rows(lines: lines, notes: [note("a", 21_000), note("b", 81_000), note("c", 181_000)],
                                      expanded: false)

        // a (line 2) → 0...5 and b (line 8) → 5...11 touch: one passage; c (line 18) → 15...19 after a gap.
        XCTAssertEqual(rows.map(label).joined(separator: "|"),
                       "L0|L1|L2|note a|L3|L4|L5|L6|L7|L8|note b|L9|L10|L11|gap|L15|L16|L17|L18|note c|L19")
    }

    func test_should_putEveryLineInPlace_when_expanded() {
        let rows = ScrapNoteRows.rows(lines: lines, notes: [note("a", 61_000)], expanded: true)

        XCTAssertEqual(rows.count, lines.count + 1)
        XCTAssertEqual(label(rows[7]), "note a")
    }

    func test_should_listNotesAlone_when_theSongHasNoTimedLyrics() {
        let rows = ScrapNoteRows.rows(lines: [], notes: [note("a", 61_000), note("b", nil)], expanded: false,
                                      draft: .init(positionMs: nil))

        XCTAssertEqual(rows.map(label), ["note a", "note b", "draft"])
    }

    func test_should_keepUnanchoredNotesLast_afterAGap() {
        let rows = ScrapNoteRows.rows(lines: lines, notes: [note("a", 1_000), note("b", nil)], expanded: false)

        XCTAssertEqual(rows.map(label), ["L0", "note a", "L1", "L2", "L3", "gap", "note b"])
    }

    func test_should_placeTheDraftUnderItsLine_withItsOwnPassage() {
        let rows = ScrapNoteRows.rows(lines: lines, notes: [], expanded: false, draft: .init(positionMs: 150_000))

        XCTAssertEqual(rows.map(label), ["L12", "L13", "L14", "L15", "draft", "L16", "L17", "L18"])
    }

    func test_should_putANoteBeforeTheFirstLine_when_itWasTakenInTheIntro() {
        let late = [ScrapLyricLine(startMs: 20_000, text: "first"), ScrapLyricLine(startMs: 30_000, text: "second")]

        let rows = ScrapNoteRows.rows(lines: late, notes: [note("intro", 5_000)], expanded: false)

        XCTAssertEqual(rows.map(label), ["note intro", "first", "second"])
    }

    func test_should_giveEveryRowItsOwnId_when_passagesAreGapped() {
        let rows = ScrapNoteRows.rows(lines: lines, notes: [note("a", 1_000), note("b", 181_000), note("c", nil)],
                                      expanded: false, draft: .init(positionMs: 100_000))

        XCTAssertEqual(Set(rows.map(\.id)).count, rows.count)
    }

    func test_should_dropBlankAndUntimedLyrics_when_readForTheSheet() {
        let read = ScrapLyrics.from(synced: true, lines: [(2_000, " two "), (1_000, "one"), (3_000, "  ")])

        XCTAssertEqual(read.lines, [ScrapLyricLine(startMs: 1_000, text: "one"), ScrapLyricLine(startMs: 2_000, text: "two")])
        XCTAssertEqual(ScrapLyrics.from(synced: false, lines: [(0, "words")]), .none)
    }

    private func note(_ id: String, _ at: Int64?) -> SongNote {
        SongNote(id: id, provider: "subsonic", trackId: "s", content: id, createdAt: 0, updatedAt: 0, title: "t",
                 artist: "a", positionMs: at)
    }

    private func label(_ row: ScrapNoteRow) -> String {
        switch row {
        case .lyric(_, let line, _): line.text
        case .note(let n): "note \(n.id)"
        case .draft: "draft"
        case .gap: "gap"
        }
    }
}
