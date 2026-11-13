import XCTest
@testable import Yoin

/// The header avatar's account card (Home/AccountSwitcherCard.swift, Android HomeAccountSwitcher.kt).
final class AccountSwitcherCardTests: XCTestCase {
    private func profile(_ id: String, _ service: Profile.Service = .subsonic) -> Profile {
        Profile(id: id, service: service, baseUrl: nil, username: nil, createdAt: 0)
    }

    func test_should_sayTheSwitchFailed_when_thePickedAccountIsNotInUseAfterIt() {
        XCTAssertFalse(AccountCardLogic.switchFailed(picked: "b", activeAfter: "b"))
        XCTAssertTrue(AccountCardLogic.switchFailed(picked: "b", activeAfter: "a"))
        XCTAssertTrue(AccountCardLogic.switchFailed(picked: "b", activeAfter: nil))
    }

    func test_should_centreTheCardSixteenInAndCapItAt420_when_laidOut() {
        XCTAssertEqual(AccountCardLogic.cardWidth(available: 402), 370)
        XCTAssertEqual(AccountCardLogic.cardWidth(available: 1032), 420)
        XCTAssertEqual(AccountCardLogic.cardWidth(available: 20), 0)
    }

    func test_should_leadWithTheAccountInUse_when_split() {
        let all = [profile("a"), profile("b", .spotify), profile("c", .appleMusic)]
        let (active, others) = AccountCardLogic.split(all, active: "b")
        XCTAssertEqual(active?.id, "b")
        XCTAssertEqual(others.map(\.id), ["a", "c"])
    }

    func test_should_leadWithTheFirstAccount_when_noneIsInUse() {
        let (active, others) = AccountCardLogic.split([profile("a"), profile("b")], active: nil)
        XCTAssertEqual(active?.id, "a")
        XCTAssertEqual(others.map(\.id), ["b"])
        XCTAssertNil(AccountCardLogic.split([], active: nil).active)
    }

    func test_should_switch_when_anotherUsableAccountIsPicked() {
        XCTAssertEqual(AccountCardLogic.pick(profile("b"), active: "a", switching: nil, picked: nil, needsSignIn: false),
                       .switchTo("b"))
    }

    func test_should_goToSettings_when_thePickedAccountNeedsSigningIn() {
        XCTAssertEqual(AccountCardLogic.pick(profile("b"), active: "a", switching: nil, picked: nil, needsSignIn: true),
                       .settings)
    }

    func test_should_takeNothing_when_aSwitchIsUnderWayOrItIsTheAccountInUse() {
        XCTAssertEqual(AccountCardLogic.pick(profile("a"), active: "a", switching: nil, picked: nil, needsSignIn: false), .none)
        XCTAssertEqual(AccountCardLogic.pick(profile("b"), active: "a", switching: "c", picked: nil, needsSignIn: false), .none)
        XCTAssertEqual(AccountCardLogic.pick(profile("b"), active: "a", switching: nil, picked: "c", needsSignIn: false), .none)
    }

    func test_should_dimTheOtherAccounts_when_oneIsPicked() {
        XCTAssertEqual(AccountCardLogic.rowAlpha("b", picked: nil), 1)
        XCTAssertEqual(AccountCardLogic.rowAlpha("b", picked: "b"), 1)
        XCTAssertEqual(AccountCardLogic.rowAlpha("c", picked: "b"), 0.38)
    }

    func test_should_openTheAccountsPage_when_reconnectCannotFixIt() {
        XCTAssertEqual(AccountCardLogic.reconnect(active: profile("a"), stillNeeds: true), .openPage("a"))
        XCTAssertEqual(AccountCardLogic.reconnect(active: profile("a"), stillNeeds: false), .fixed)
        XCTAssertNil(AccountCardLogic.reconnect(active: nil, stillNeeds: true))
    }

    func test_should_growFromTheAvatarToTheCard_when_progressRuns() {
        let avatar = CGRect(x: 340, y: 70, width: 35, height: 35)
        let card = CGRect(x: 16, y: 200, width: 370, height: 500)
        let start = AccountCardLogic.surface(from: avatar, to: card, p: 0)
        XCTAssertEqual(start.rect, avatar)
        XCTAssertEqual(start.corner, 17.5)
        let end = AccountCardLogic.surface(from: avatar, to: card, p: 1)
        XCTAssertEqual(end.rect, card)
        XCTAssertEqual(end.corner, SheetSurface.corner)
        let mid = AccountCardLogic.surface(from: avatar, to: card, p: 0.5)
        XCTAssertEqual(mid.rect.midX, (avatar.midX + card.midX) / 2, accuracy: 0.001)
    }

    func test_should_bringTheContentsInLate_when_theCardGrows() {
        // Android smoothStep(0.45, 0.95, p).
        XCTAssertEqual(AccountCardLogic.contentAlpha(0.4), 0)
        XCTAssertEqual(AccountCardLogic.contentAlpha(0.7), 0.5, accuracy: 0.0001)
        XCTAssertEqual(AccountCardLogic.contentAlpha(1), 1)
    }

    func test_should_sayTheCardsLabelsInAndroidsWords_when_localized() throws {
        // Android strings_home.xml (values-zh-rCN) home_account_manage / _reconnect / _reconnect_needed.
        let path = try XCTUnwrap(Bundle.main.path(forResource: "zh-Hans", ofType: "lproj"))
        let zh = try XCTUnwrap(Bundle(path: path))
        XCTAssertEqual(zh.localizedString(forKey: "Manage accounts", value: nil, table: nil), "管理账号")
        XCTAssertEqual(zh.localizedString(forKey: "Reconnect", value: nil, table: nil), "重新连接")
        XCTAssertEqual(zh.localizedString(forKey: "Account needs reconnecting", value: nil, table: nil), "账号需要重新连接")
    }
}
