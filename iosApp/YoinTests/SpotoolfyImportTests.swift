import XCTest
@testable import Yoin

/// The Spotoolfy export migration (Android data/migration/spotoolfy): parser, mapper, recap and the write
/// into one Spotify account's notes and ratings. Fixtures/spotoolfy-export.json holds two songs' notes and
/// ratings, a sample row, an empty record, rows that can't be read and a repeated record.
@MainActor
final class SpotoolfyImportTests: XCTestCase {
    private let trackA = "4uLU6hMCjMI75M1A2tKUQC"
    private let trackB = "7ouMYWpwJ422jRcDASZB7P"
    private let accountOne = "spotify|spotify-user-1"
    private let accountTwo = "spotify|spotify-user-2"

    private var fixture: Data {
        get throws {
            try Data(contentsOf: URL(fileURLWithPath: #filePath).deletingLastPathComponent()
                .appendingPathComponent("Fixtures/spotoolfy-export.json"))
        }
    }

    private func parsed(_ data: Data) throws -> SpotoolfyExport {
        guard case .parsed(let export) = SpotoolfyExportParser.parse(data) else {
            XCTFail("not parsed")
            throw CocoaError(.fileReadCorruptFile)
        }
        return export
    }

    private func plan() throws -> SpotoolfyImportPlan { SpotoolfyImportMapper.map(try parsed(fixture)) }

    // MARK: Parser

    func test_should_readTracksAndRecords_when_exportIsWellFormed() throws {
        let export = try parsed(fixture)
        // The track without an id is dropped; the cover over plain http is not kept.
        XCTAssertEqual(export.tracks.map(\.trackId), [trackA, trackB, "1301WleyT98MSxVHPZCA6M"])
        XCTAssertEqual(export.tracks[0].artist, "Art One, Art Two")
        XCTAssertEqual(export.tracks[0].coverUrl, "https://example.com/a.jpg")
        XCTAssertNil(export.tracks[1].coverUrl)
        // The record with no time and the row that is not an object are counted, not read.
        XCTAssertEqual(export.records.count, 6)
        XCTAssertEqual(export.unreadableRecords, 2)
    }

    func test_should_readLooselyWrittenValues_when_ratingOrTimeIsText() throws {
        let export = try parsed(fixture)
        let neutral = try XCTUnwrap(export.records.first { $0.recordedAt == 1_700_000_100_000 })
        // A rating written as text counts as neutral; a time written as text still reads.
        XCTAssertEqual(neutral.rating, 3)
    }

    func test_should_refuseTheFile_when_itIsNotASpotoolfyExport() throws {
        XCTAssertTrue(isCase(SpotoolfyExportParser.parse(Data("not json".utf8)), .corrupt))
        XCTAssertTrue(isCase(SpotoolfyExportParser.parse(Data(#"{"a":1}"#.utf8)), .notSpotoolfy))
        XCTAssertTrue(isCase(SpotoolfyExportParser.parse(Data("[1,2]".utf8)), .notSpotoolfy))
        // One of the four tables missing.
        XCTAssertTrue(isCase(SpotoolfyExportParser.parse(Data(#"{"tracks":[],"records":[],"translations":[]}"#.utf8)), .notSpotoolfy))
        // A table that is not a list.
        XCTAssertTrue(isCase(SpotoolfyExportParser.parse(Data(#"{"tracks":{},"records":[],"translations":[],"play_contexts":[]}"#.utf8)), .notSpotoolfy))
        XCTAssertTrue(isCase(SpotoolfyExportParser.parse(Data(count: SpotoolfyExportParser.maxBytes + 1)), .tooLarge))
    }

    func test_should_skipByteOrderMark_when_fileStartsWithOne() throws {
        let data = Data([0xEF, 0xBB, 0xBF]) + (try fixture)
        XCTAssertEqual(try parsed(data).records.count, 6)
    }

    private enum Kind { case notSpotoolfy, corrupt, tooLarge }

    private func isCase(_ r: SpotoolfyParseResult, _ k: Kind) -> Bool {
        switch (r, k) {
        case (.notSpotoolfy, .notSpotoolfy), (.corrupt, .corrupt), (.tooLarge, .tooLarge): true
        default: false
        }
    }

    // MARK: Mapper

    func test_should_mapNotesAndLatestRatings_when_planIsBuilt() throws {
        let plan = try plan()
        XCTAssertEqual(plan.notes.map(\.trackId), [trackA, trackB])
        let a = plan.notes[0]
        // The user's text trimmed, then the lyric snippet in corner quotes with the LRC tags gone.
        XCTAssertEqual(a.content, "Love this line\n\n「first line\nsecond line」")
        XCTAssertEqual(a.text, "Love this line")
        XCTAssertEqual(a.positionMs, 12_000)
        XCTAssertEqual(a.title, "Song A")
        XCTAssertEqual(a.artist, "Art One, Art Two")
        // A zero playhead is no moment.
        XCTAssertNil(plan.notes[1].positionMs)
        // Latest record wins: A went 5 then neutral; B's thumbs-down stays above 0.
        XCTAssertEqual(plan.ratings.map(\.trackId), [trackA, trackB])
        XCTAssertEqual(plan.ratings.map(\.rating), [5, 1])
    }

    func test_should_leaveOutRecords_when_idIsASampleOrNothingWasWritten() throws {
        let plan = try plan()
        // sample1 (not a Spotify id), the blank note with a rating Yoin has no value for, and the two unreadable rows.
        XCTAssertEqual(plan.skipped, 4)
        XCTAssertEqual(plan.firstRecordAt, 1_700_000_000_000)
        XCTAssertEqual(plan.lastRecordAt, 1_700_000_200_000)
        XCTAssertEqual(Set(plan.tracks.keys), [trackA, trackB])
        XCTAssertFalse(plan.isEmpty)
    }

    func test_should_mapRatingsToYoinScale_when_spotoolfyScoresArrive() {
        XCTAssertEqual(SpotoolfyImportMapper.mapRating(5), 10)
        XCTAssertEqual(SpotoolfyImportMapper.mapRating(3), 5)
        XCTAssertEqual(SpotoolfyImportMapper.mapRating(0), 1)
        XCTAssertNil(SpotoolfyImportMapper.mapRating(4))
    }

    func test_should_normalizeTrackIds_when_prefixedOrNot() {
        XCTAssertEqual(SpotoolfyImportMapper.normalizeTrackId(" spotify:track:\(trackA) "), trackA)
        XCTAssertNil(SpotoolfyImportMapper.normalizeTrackId("sample1"))
        XCTAssertNil(SpotoolfyImportMapper.normalizeTrackId(trackA + "x"))
    }

    func test_should_giveTheSameNoteIdAndroidDoes_when_sameNote() throws {
        // Computed with Java's UUID.nameUUIDFromBytes over the same text (MD5, version 3).
        XCTAssertEqual(SpotoolfyImportMapper.noteId(trackId: trackA, recordedAt: 1_700_000_000_000, note: "  Love this line  "),
                       "c5b3ae36-fc9e-3b63-aa78-222268b21eaa")
        XCTAssertEqual(try plan().notes.map(\.id), ["c5b3ae36-fc9e-3b63-aa78-222268b21eaa",
                                                    "47d0cee1-bb25-3934-9aef-d9b23204f615"])
    }

    func test_should_keepNoteOnce_when_recordIsRepeated() throws {
        // The fixture repeats its first record; the plan holds one note for it.
        XCTAssertEqual(try plan().notes.filter { $0.trackId == trackA }.count, 1)
    }

    // MARK: Recap

    func test_should_countSongsArtistsAndRatings_when_recapIsComputed() throws {
        let recap = SpotoolfyRecapCalculator(segmenter: { $0.split(separator: " ").map(String.init) }).compute(try plan())
        XCTAssertEqual(recap.noteCount, 2)
        XCTAssertEqual(recap.trackCount, 2)
        // "Art One, Art Two" and "Art One": two artists.
        XCTAssertEqual(recap.artistCount, 2)
        XCTAssertEqual(recap.ratedTrackCount, 2)
        // Two notes are too few for a keyword burst: the notes themselves stand in.
        XCTAssertTrue(recap.keywords.isEmpty)
        XCTAssertEqual(recap.fallbackNotes.map(\.text), ["Love this line", "Again!"])
        XCTAssertEqual(recap.coverUrls, ["https://example.com/a.jpg"])
        XCTAssertEqual(recap.lyricSnippets.first?.lyric, "first line")
    }

    func test_should_rankWordsByCount_when_notesRepeatThem() {
        func note(_ i: Int, _ text: String) -> ImportedNote {
            ImportedNote(id: "n\(i)", trackId: "t", content: text, text: text, lyricLines: [], recordedAt: Int64(i),
                         positionMs: nil, title: nil, artist: nil)
        }
        let calc = SpotoolfyRecapCalculator(segmenter: { $0.split(separator: " ").map(String.init) })
        let words = calc.keywords([note(1, "rain rain city"), note(2, "city nights rain"), note(3, "the 2024 nights")])
        XCTAssertEqual(words.map(\.text), ["rain", "city", "nights"])
        XCTAssertEqual(words.map(\.count), [3, 2, 2])
        // Notes using a word, newest first.
        XCTAssertEqual(words[0].notes.map(\.writtenAt), [2, 1])
    }

    func test_should_cutLongNotes_when_makingExcerpts() {
        let long = String(repeating: "a", count: 200)
        let excerpt = SpotoolfyRecapCalculator.excerpt(long)
        XCTAssertEqual(excerpt.unicodeScalars.count, SpotoolfyRecapCalculator.maxExcerptChars + 1)
        XCTAssertTrue(excerpt.hasSuffix("…"))
        XCTAssertEqual(SpotoolfyRecapCalculator.excerpt("a\n  b"), "a b")
    }

    // MARK: Import into an account

    private struct Env {
        var notes: NotesStore
        var ratings: LocalRatings
        var scopes: AccountScope
        var importer: SpotoolfyImporter
        var defaults: UserDefaults
    }

    private func makeEnv() throws -> Env {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("spotoolfy-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: dir) }
        let suite = "spotoolfy-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        addTeardownBlock { defaults.removePersistentDomain(forName: suite) }
        let scopes = AccountScope()
        let notes = NotesStore(fileURL: dir.appendingPathComponent("notes.json"), scopes: scopes)
        let ratings = LocalRatings(fileURL: dir.appendingPathComponent("ratings.json"), scopes: scopes)
        let importer = SpotoolfyImporter(notes: notes, ratings: ratings, scopes: scopes, defaults: defaults,
                                         segmenter: { $0.split(separator: " ").map(String.init) })
        return Env(notes: notes, ratings: ratings, scopes: scopes, importer: importer, defaults: defaults)
    }

    private func staged(_ env: Env) throws -> SpotoolfyImporter.Staged {
        guard case .staged(let s) = env.importer.stage(try fixture) else {
            XCTFail("not staged")
            throw CocoaError(.fileReadCorruptFile)
        }
        return s
    }

    func test_should_writeNotesAndRatingsIntoTheAccount_when_committed() throws {
        let env = try makeEnv()
        let result = env.importer.commit(try staged(env), scope: accountOne, unknownTitle: "Unknown")
        guard case .committed(let noteCount, let ratingCount, _) = result else { return XCTFail("\(result)") }
        XCTAssertEqual(noteCount, 2)
        XCTAssertEqual(ratingCount, 2)
        env.scopes.reading(asSubsonic: nil, asSpotify: accountOne) {
            XCTAssertEqual(env.notes.notes.count, 2)
            let note = env.notes.notes.first { $0.trackId == trackA }
            XCTAssertEqual(note?.provider, "spotify")
            XCTAssertEqual(note?.scope, accountOne)
            XCTAssertEqual(note?.createdAt, 1_700_000_000_000)
            XCTAssertEqual(note?.positionMs, 12_000)
            XCTAssertEqual(env.ratings.rating(provider: "spotify", songId: trackA), 5)
            XCTAssertEqual(env.ratings.rating(provider: "spotify", songId: trackB), 1)
            XCTAssertEqual(env.ratings.updatedAt["spotify:\(trackB)"], 1_700_000_200_000)
        }
        // Scoped to the account: another Spotify account sees none of it.
        env.scopes.reading(asSubsonic: nil, asSpotify: accountTwo) {
            XCTAssertTrue(env.notes.notes.isEmpty)
            XCTAssertTrue(env.ratings.ratings.isEmpty)
        }
    }

    func test_should_notWrite_when_accountAlreadyHoldsNotesOrRatings() throws {
        let env = try makeEnv()
        let staged = try staged(env)
        env.scopes.reading(asSubsonic: nil, asSpotify: accountOne) {
            env.ratings.syncPut(8, provider: "spotify", songId: "someSong", updatedAt: 1)
        }
        XCTAssertEqual(env.importer.commit(staged, scope: accountOne, unknownTitle: "Unknown"), .notEligible)
        env.scopes.reading(asSubsonic: nil, asSpotify: accountOne) { XCTAssertTrue(env.notes.notes.isEmpty) }
    }

    func test_should_keepNotesWhereTheyAre_when_sameFileGoesIntoAnotherAccount() throws {
        let env = try makeEnv()
        let staged = try staged(env)
        _ = env.importer.commit(staged, scope: accountOne, unknownTitle: "Unknown")
        // Note ids are global: the notes stay with the first account; the ratings are still the new account's.
        let again = env.importer.commit(staged, scope: accountTwo, unknownTitle: "Unknown")
        guard case .committed(let noteCount, let ratingCount, _) = again else { return XCTFail("\(again)") }
        XCTAssertEqual(noteCount, 0)
        XCTAssertEqual(ratingCount, 2)
        env.scopes.reading(asSubsonic: nil, asSpotify: accountTwo) { XCTAssertTrue(env.notes.notes.isEmpty) }
    }

    func test_should_takeTheRowsBack_when_committedFileIsDiscarded() throws {
        let env = try makeEnv()
        let staged = try staged(env)
        guard case .committed(_, _, let receipt) = env.importer.commit(staged, scope: accountOne, unknownTitle: "Unknown")
        else { return XCTFail("not committed") }
        XCTAssertTrue(env.importer.wasImported(sha256: staged.fileSha256))
        env.importer.discard(receipt)
        env.scopes.reading(asSubsonic: nil, asSpotify: accountOne) {
            XCTAssertTrue(env.notes.notes.isEmpty)
            XCTAssertTrue(env.ratings.ratings.isEmpty)
        }
        XCTAssertFalse(env.importer.wasImported(sha256: staged.fileSha256))
        // And the same file can be read again.
        if case .staged = env.importer.stage(try fixture) {} else { XCTFail("not staged again") }
    }

    func test_should_refuseTheFile_when_itWasImportedBefore() throws {
        let env = try makeEnv()
        _ = env.importer.commit(try staged(env), scope: accountOne, unknownTitle: "Unknown")
        if case .alreadyImported = env.importer.stage(try fixture) {} else { XCTFail("not refused") }
    }

    func test_should_reportEmpty_when_fileHasNothingOfTheUsersOwn() {
        let json = #"{"tracks":[],"records":[{"trackId":"sample1","rating":5,"recordedAt":1}],"translations":[],"play_contexts":[]}"#
        if case .empty = SpotoolfyImporter.analyze(Data(json.utf8)) {} else { XCTFail("not empty") }
    }
}
