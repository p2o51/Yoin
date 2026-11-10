import XCTest
@testable import Yoin

/// Home's "On Your Other Accounts" and the new Home sections (HomeOtherAccounts.swift, HomeLayout.swift).
final class HomeOtherAccountsTests: XCTestCase {
    private func record(_ id: String, _ entity: ActivityEntity = .album, action: ActivityAction = .played,
                        scope: String = "s2", at: Int64) -> ActivityRecord {
        ActivityRecord(id: UUID().uuidString, scope: scope, provider: "subsonic", entity: entity, action: action,
                       entityId: id, title: "T \(id)", subtitle: "A", coverKey: nil, songId: nil, albumId: id,
                       artistId: nil, timestamp: at)
    }

    func test_should_rankByPlaysThenLatest_when_anAccountsLogIsRead() {
        let rows = [record("a", at: 10), record("b", at: 20), record("b", at: 5), record("c", .playlist, at: 30),
                    record("d", action: .visited, at: 40), record("e", .song, at: 50), record("f", scope: "s1", at: 60),
                    record("old", at: -1)]
        let ranked = HomeOtherAccounts.frequent(rows, scope: "s2", provider: "subsonic", since: 0)
        XCTAssertEqual(ranked.map(\.entityId), ["b", "c", "a"])
        XCTAssertEqual(ranked.first?.timestamp, 20, "the newest record stands for the item")
    }

    func test_should_takeTurns_when_accountsInterleave() {
        XCTAssertEqual(HomeOtherAccounts.interleave([[1, 2, 3], [10], [20, 21]], total: 5), [1, 10, 20, 2, 21])
        XCTAssertEqual(HomeOtherAccounts.interleave([[Int]]()), [])
    }

    func test_should_appendTheNewSectionsShown_when_aSavedLayoutPredatesThem() {
        let saved = HomeLayout.reconcile([HomeSectionPref(id: "activities", enabled: true),
                                          HomeSectionPref(id: "rediscover", enabled: false)])
        XCTAssertTrue(saved.shownSections.contains(.memoryGrid))
        XCTAssertTrue(saved.shownSections.contains(.lyricHighlight))
        XCTAssertTrue(saved.shownSections.contains(.otherProfiles))
        XCTAssertEqual(HomeSection.memoryGrid.id, "memory_grid")
        XCTAssertEqual(HomeSection.lyricHighlight.id, "lyric_highlight")
        XCTAssertTrue(HomeSection.allCases.allSatisfy(\.supportedOnIOS))
    }
}
