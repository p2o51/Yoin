import XCTest
@testable import Yoin

/// The header avatar's hold (Home/AccountQuickSwitch.swift, Android #162).
final class AccountQuickSwitchTests: XCTestCase {
    private let anchor = CGSize(width: 48, height: 48)

    private func profile(_ id: String, _ service: Profile.Service = .subsonic) -> Profile {
        Profile(id: id, service: service, baseUrl: nil, username: nil, createdAt: 0)
    }

    func test_should_placeItemsBelowTheAvatar_when_laidOut() {
        // Android: anchor height + 12 gap + half the 48 item, then 60 apart.
        XCTAssertEqual(AccountFanGeometry.itemCenterY(anchorHeight: 48, index: 0), 84)
        XCTAssertEqual(AccountFanGeometry.itemCenterY(anchorHeight: 48, index: 2), 204)
    }

    func test_should_hitTheItemUnderTheFinger_when_slidingDown() {
        XCTAssertEqual(AccountFanGeometry.hit(CGPoint(x: 24, y: 84), anchor: anchor, count: 3), 0)
        XCTAssertEqual(AccountFanGeometry.hit(CGPoint(x: 24, y: 150), anchor: anchor, count: 3), 1)
        // On a label, well to the side of the avatar: still that item.
        XCTAssertEqual(AccountFanGeometry.hit(CGPoint(x: -150, y: 204), anchor: anchor, count: 3), 2)
    }

    func test_should_hitNothing_when_theFingerIsOffTheColumn() {
        // Still on the avatar itself.
        XCTAssertEqual(AccountFanGeometry.hit(CGPoint(x: 24, y: 24), anchor: anchor, count: 3), -1)
        // Past the last item.
        XCTAssertEqual(AccountFanGeometry.hit(CGPoint(x: 24, y: 300), anchor: anchor, count: 3), -1)
        // Too far sideways.
        XCTAssertEqual(AccountFanGeometry.hit(CGPoint(x: 260, y: 84), anchor: anchor, count: 3), -1)
        XCTAssertEqual(AccountFanGeometry.hit(CGPoint(x: 24, y: 84), anchor: anchor, count: 0), -1)
    }

    func test_should_offerTheOtherAccountsInOrder_when_noSwitchRuns() {
        let all = [profile("a"), profile("b", .spotify), profile("c", .appleMusic)]
        XCTAssertEqual(AccountQuickSwitchCandidates.of(all, active: "b", switching: nil).map(\.id), ["a", "c"])
    }

    func test_should_offerNothing_when_aSwitchIsUnderWay() {
        let all = [profile("a"), profile("b")]
        XCTAssertTrue(AccountQuickSwitchCandidates.of(all, active: "a", switching: "b").isEmpty)
    }

    func test_should_listTheQuickSwitchTipFirst_when_readingTheCatalog() {
        XCTAssertEqual(Tips.all.first?.id, "AccountQuickSwitch")
        XCTAssertEqual(Tips.all.first?.textZhHans, "长按头像并滑动以切换账号")
    }
}
