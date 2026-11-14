import XCTest
@testable import Yoin

/// ⌘[ undoes the last step first (Shell/ShellBackOrder, Android ShellBackResolver #200).
@MainActor
final class ShellBackOrderTests: XCTestCase {
    private func step(search: Bool = false, full: Bool = false, page: Bool = false,
                      panel: Bool = false, column: Bool = false, memories: Bool = false, editing: Bool = false,
                      stale: Bool = false, recency: [ShellBackSurface] = []) -> ShellBackStep {
        ShellBackOrder.step(searchActive: search, fullNowPlaying: full, pageCoversShell: page,
                            panelOpen: panel, columnOpen: column, memoriesOpen: memories, homeEditing: editing,
                            staleSelection: stale, recency: recency)
    }

    /// I06, Android resolveShellBackOwner: Home's edit mode answers before the column and Memories, after
    /// an open Now Playing.
    func test_should_endHomeEdit_beforeTheColumn_butAfterNowPlaying() {
        XCTAssertEqual(step(column: true, memories: true, editing: true, recency: [.memories, .column]), .exitHomeEdit)
        XCTAssertEqual(step(panel: true, column: true, editing: true, recency: [.nowPlaying, .column]), .closePanel)
        XCTAssertEqual(step(full: true, editing: true), .closeFullNowPlaying)
        XCTAssertEqual(step(search: true, editing: true), .closeSearch)
    }

    /// Android stepBackStage: lyrics opened from focus go back to focus, focus to the resting player; two
    /// columns rest on the expanded page.
    func test_should_stepBackOneStage_insideNowPlaying() {
        XCTAssertEqual(NowPlayingStageBackRule.previous(.expanded, fromFocus: true, wide: false), .focus)
        XCTAssertEqual(NowPlayingStageBackRule.previous(.expanded, fromFocus: false, wide: false), .compact)
        XCTAssertEqual(NowPlayingStageBackRule.previous(.focus, fromFocus: false, wide: false), .compact)
        XCTAssertNil(NowPlayingStageBackRule.previous(.compact, fromFocus: false, wide: false))
        XCTAssertEqual(NowPlayingStageBackRule.previous(.focus, fromFocus: false, wide: true), .expanded)
        XCTAssertNil(NowPlayingStageBackRule.previous(.expanded, fromFocus: true, wide: true))
    }

    func test_should_closePanelFirst_when_panelOpenedAfterColumn() {
        XCTAssertEqual(step(panel: true, column: true, recency: [.column, .nowPlaying]), .closePanel)
    }

    func test_should_closeColumnFirst_when_columnOpenedAfterPanel() {
        XCTAssertEqual(step(panel: true, column: true, recency: [.nowPlaying, .column]), .column)
    }

    func test_should_closeMemoriesFirst_when_memoriesOpenedLast() {
        XCTAssertEqual(step(panel: true, column: true, memories: true,
                            recency: [.nowPlaying, .column, .memories]), .closeMemories)
    }

    func test_should_coverEverything_when_searchFullOrPageIsUp() {
        let r: [ShellBackSurface] = [.nowPlaying, .column]
        XCTAssertEqual(step(search: true, full: true, page: true, panel: true, column: true, recency: r), .closeSearch)
        XCTAssertEqual(step(full: true, page: true, panel: true, column: true, recency: r), .closeFullNowPlaying)
        XCTAssertEqual(step(page: true, panel: true, memories: true, recency: [.memories]), .popPage)
    }

    func test_should_ignoreClosedSurfaces_when_theyAreNewestInRecency() {
        XCTAssertEqual(step(column: true, recency: [.column, .nowPlaying]), .column)
    }

    func test_should_rankUnrecordedOldest_andTieInAndroidOrder_when_recencyIsMissing() {
        XCTAssertEqual(step(panel: true, column: true, memories: true), .closePanel)
        XCTAssertEqual(step(column: true, memories: true), .column)
        XCTAssertEqual(step(panel: true, column: true, recency: [.column]), .column)
    }

    func test_should_clearStaleSelection_onlyWhenNothingElseIsOpen() {
        XCTAssertEqual(step(stale: true), .clearSelection)
        XCTAssertEqual(step(), .none)
        XCTAssertEqual(step(panel: true, stale: true), .closePanel)
    }

    func test_should_moveToEnd_when_enteredAgain_andLeave_when_closed() {
        var r: [ShellBackSurface] = []
        r = ShellBackOrder.noted(r, .column, open: true)
        r = ShellBackOrder.noted(r, .nowPlaying, open: true)
        XCTAssertEqual(r, [.column, .nowPlaying])
        r = ShellBackOrder.noted(r, .column, open: true)
        XCTAssertEqual(r, [.nowPlaying, .column])
        r = ShellBackOrder.noted(r, .nowPlaying, open: false)
        XCTAssertEqual(r, [.column])
    }

    /// The model records the order as the surfaces open: the panel then an album → the album goes first;
    /// another album afterwards, or the panel reopened, moves that one to the end.
    func test_should_recordOpeningOrder_when_modelSurfacesChange() {
        let model = AppModel()
        model.isPad = true
        model.padWidth = 1366
        model.npPanel = true
        model.padSelection = MockLibrary.allAlbums[0]
        XCTAssertEqual(model.backRecency, [.nowPlaying, .column])
        model.npPanel = false
        model.npPanel = true
        XCTAssertEqual(model.backRecency, [.column, .nowPlaying])
        model.path.append(.album(MockLibrary.allAlbums[1]))
        XCTAssertEqual(model.backRecency, [.nowPlaying, .column])
        model.backFromKeyboard()
        XCTAssertEqual(model.path, [])
        XCTAssertTrue(model.npPanel)
        model.memories.open()
        XCTAssertEqual(model.backRecency.last, .memories)
    }
}
