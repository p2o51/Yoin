import XCTest
@testable import Yoin
import YoinShared

/// The bar of a playlist restore's Live Activity (Widgets/RestoreActivity.swift; Android RestoreLiveUpdate's
/// segments).
final class RestoreBarTests: XCTestCase {
    func test_should_giveEachSongItsOwnCell_inTheOrderTheyCameBack() {
        XCTAssertEqual(RestoreBar.encode([.matched, .toCheck, .notFound], total: 5), "mcn--")
        XCTAssertEqual(RestoreBar.encode([], total: 3), "---")
        XCTAssertEqual(RestoreBar.encode([], total: 0), "")
        XCTAssertEqual(RestoreBar.encode(Array(repeating: .matched, count: 96), total: 96), String(repeating: "m", count: 96))
        // Never past the playlist.
        XCTAssertEqual(RestoreBar.encode([.matched, .matched, .notFound], total: 2), "mm")
    }

    func test_should_showWhatMostOfACellsSongsCameBackAs_whenThePlaylistIsLonger() {
        // 960 songs, ten a cell: mostly matched; a tie, which goes to the songs to check; partly back.
        let outcomes: [RestoreBar.Cell] = Array(repeating: .matched, count: 7) + Array(repeating: .toCheck, count: 3)
            + Array(repeating: .notFound, count: 5) + Array(repeating: .toCheck, count: 5)
            + Array(repeating: .matched, count: 4)
        let bar = RestoreBar.encode(outcomes, total: 960)
        XCTAssertEqual(bar.count, RestoreBar.cells)
        XCTAssertEqual(String(bar.prefix(4)), "mcm-")
        XCTAssertEqual(bar.filter { $0 == "-" }.count, RestoreBar.cells - 3)
    }

    func test_should_drawRunsOfOneKind() {
        let runs = RestoreBar.runs("mmmcn---")
        XCTAssertEqual(runs.map(\.cell), [.matched, .toCheck, .notFound, .pending])
        XCTAssertEqual(runs.map(\.length), [3, 1, 1, 3])
        XCTAssertTrue(RestoreBar.runs("").isEmpty)
    }

    /// ActivityKit takes 4 KB for an activity's data: the longest playlist and a long name fit.
    func test_should_stayWithinActivityKitsLimit_forTheLongestPlaylist() throws {
        let state = RestoreActivityAttributes.ContentState(
            stage: .matching, done: 9_999, total: 10_000,
            bar: RestoreBar.encode(Array(repeating: .toCheck, count: 9_999), total: 10_000))
        let attributes = RestoreActivityAttributes(playlist: String(repeating: "歌", count: 200), target: "Spotify")
        let size = try JSONEncoder().encode(state).count + JSONEncoder().encode(attributes).count
        XCTAssertLessThan(size, 4096)
    }

    /// At launch only an activity still under way is ended (nothing carries it on); what came of a finished
    /// restore stays.
    func test_should_endOnlyWhatsUnderWay_atLaunch() {
        let stages: [RestoreActivityAttributes.ContentState.Stage] = [.matching, .creating, .created, .notCreated]
        XCTAssertEqual(stages.map(\.underWay), [true, true, false, false])
    }

    /// Android's colours by outcome: a song picked in the review counts as matched.
    @MainActor
    func test_should_colourEachOutcome_asAndroidDoes() {
        XCTAssertEqual([ReviewStatus.matched, .picked, .uncertain, .notfound].map(RestoreLiveActivity.cell),
                       [.matched, .matched, .toCheck, .notFound])
    }
}
