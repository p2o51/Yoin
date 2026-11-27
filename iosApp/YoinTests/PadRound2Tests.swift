import XCTest
import SwiftUI
@testable import Yoin

/// iPad round 2 (owner 2026-11-27): the pure rules behind popovers, the account card's place, Lyric Cards' intro
/// row and the Controls menu's Repeat item.
final class PadRound2Tests: XCTestCase {
    func test_should_usePopovers_when_iPadInRegularWidthOnly() {
        XCTAssertTrue(AnchoredPopover.enabled(isPad: true, horizontal: .regular))
        XCTAssertFalse(AnchoredPopover.enabled(isPad: true, horizontal: .compact))
        XCTAssertFalse(AnchoredPopover.enabled(isPad: false, horizontal: .regular))
        XCTAssertFalse(AnchoredPopover.enabled(isPad: true, horizontal: nil))
    }

    func test_should_anchorAMenuAtTheTouch_when_itWentDownRecently() {
        let p = CGPoint(x: 300, y: 500)
        XCTAssertEqual(AnchoredPopover.recent(point: p, at: 10, now: 10.4), p)
        XCTAssertEqual(AnchoredPopover.recent(point: p, at: 10, now: 10 + AnchoredPopover.touchMaxAge), p)
        // Too old (a menu opened from somewhere else later), or no touch at all: a sheet.
        XCTAssertNil(AnchoredPopover.recent(point: p, at: 10, now: 14))
        XCTAssertNil(AnchoredPopover.recent(point: nil, at: 10, now: 10))
        XCTAssertNil(AnchoredPopover.recent(point: p, at: 10, now: 9))
        XCTAssertEqual(AnchoredPopover.anchor(at: p), CGRect(x: 298, y: 498, width: 4, height: 4))
    }

    func test_should_placeTheAccountCardUnderTheAvatar_when_itPopsOver() {
        let size = CGSize(width: 1376, height: 1032)
        // The avatar at the header's trailing end: the card under it, its trailing edge just past the avatar's.
        let avatar = CGRect(x: 1290, y: 60, width: 35, height: 35)
        let o = AccountCardLogic.popoverOrigin(avatar: avatar, width: AccountCardLogic.popoverWidth, in: size)
        XCTAssertEqual(o.y, avatar.maxY + AccountCardLogic.popoverGap)
        XCTAssertEqual(o.x + AccountCardLogic.popoverWidth, avatar.maxX + 8)
        // Near the leading edge it stays 16 inside the window.
        let left = AccountCardLogic.popoverOrigin(avatar: CGRect(x: 20, y: 60, width: 35, height: 35),
                                                  width: AccountCardLogic.popoverWidth, in: size)
        XCTAssertEqual(left.x, 16)
        // An avatar at the window's edge: the card never runs past it.
        let right = AccountCardLogic.popoverOrigin(avatar: CGRect(x: 1360, y: 60, width: 35, height: 35),
                                                   width: AccountCardLogic.popoverWidth, in: size)
        XCTAssertEqual(right.x + AccountCardLogic.popoverWidth, size.width - 16)
    }

    func test_should_giveLyricCardsIntroRowOneUnit_when_introPending() {
        let row: [HomeSection] = [.lyricHighlight, .recentlyAdded]
        XCTAssertEqual(HomeGrid.shownUnits(2, row: row, lyricIntro: true), 1)
        XCTAssertEqual(HomeGrid.shownUnits(2, row: row, lyricIntro: false), 2)
        // Rows without Lyric Cards keep their units.
        XCTAssertEqual(HomeGrid.shownUnits(3, row: [.activities], lyricIntro: true), 3)
        // Its row's handle can't pull it taller while the intro stands there.
        XCTAssertEqual(HomeGrid.rowUnitRange(.lyricHighlight, .recentlyAdded, lyricIntro: true), 1...1)
        XCTAssertEqual(HomeGrid.rowUnitRange(.recentlyAdded, .lyricHighlight, lyricIntro: true), 1...1)
        XCTAssertNotEqual(HomeGrid.rowUnitRange(.lyricHighlight, .recentlyAdded, lyricIntro: false), 1...1)
        // Every section can take one unit, so the intro's partner always fits.
        for s in HomeSection.allCases {
            XCTAssertEqual(HomeGrid.unitRange(s).lowerBound, 1, "\(s)")
        }
    }

    func test_should_nameTheModeRepeatTurnsOn_when_cycling() {
        // ⌘R follows the play-mode button: all → one → off → all.
        XCTAssertEqual(RepeatMode.all.next, .one)
        XCTAssertEqual(RepeatMode.one.next, .off)
        XCTAssertEqual(RepeatMode.off.next, .all)
        XCTAssertEqual(KeyboardShortcutsMenu.repeatTitle(next: .one), LocalizedStringKey("Repeat One"))
        XCTAssertEqual(KeyboardShortcutsMenu.repeatTitle(next: .off), LocalizedStringKey("Repeat Off"))
        XCTAssertEqual(KeyboardShortcutsMenu.repeatTitle(next: .all), LocalizedStringKey("Repeat All"))
    }
}
