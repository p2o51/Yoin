import XCTest
@testable import Yoin

/// The landing's Home step (Android LandingModels, LandingViewModel): where it sits, when it appears, and the Home layout the landing writes.
@MainActor
final class LandingHomeStepTests: XCTestCase {
    // MARK: Step order and gating

    func test_should_putHomeBeforeReady_when_everythingIsPicked() {
        XCTAssertEqual(landingSteps(picked: [.appleMusic, .subsonic], hasAccount: true),
                       [.hello, .pick, .about, .subsonic, .appleMusic, .home, .ready])
    }

    func test_should_askAboutHome_when_mascotSpeaks() {
        XCTAssertEqual(landingBubble(.home, noAccounts: false).title, loc("What goes on Home?"))
    }

    func test_should_skipHome_when_noAccountExists() {
        XCTAssertEqual(landingSteps(picked: [.subsonic], hasAccount: false), [.hello, .pick, .about, .subsonic, .ready])
        XCTAssertEqual(landingSteps(picked: [.subsonic], hasAccount: true), [.hello, .pick, .about, .subsonic, .home, .ready])
    }

    func test_should_addHomeWithoutMovingTheStep_when_firstAccountArrives() {
        var flow = LandingFlow(mode: .firstRun)
        flow.toggle(.subsonic)
        flow.goTo(.subsonic)
        XCTAssertFalse(flow.steps.contains(.home))
        flow.setHasAccount(true)
        XCTAssertEqual(flow.step, .subsonic)
        XCTAssertEqual(flow.steps, [.hello, .pick, .about, .subsonic, .home, .ready])
        flow.next()
        XCTAssertEqual(flow.step, .home)
        flow.next()
        XCTAssertEqual(flow.step, .ready)
        // Back from Ready lands on Home.
        flow.back()
        XCTAssertEqual(flow.step, .home)
    }

    func test_should_dropHome_when_accountsGoAway() {
        var flow = LandingFlow(mode: .rerun)
        flow.setHasAccount(true)
        XCTAssertEqual(flow.steps, [.hello, .pick, .about, .home, .ready])
        flow.goTo(.home)
        flow.setHasAccount(false)
        XCTAssertFalse(flow.steps.contains(.home))
        XCTAssertEqual(flow.step, .ready)
    }

    // MARK: Home layout

    func test_should_moveAmongListedRows_when_reordering() {
        let layout = LandingHomeLayout.move(.default, from: 0, to: 2)
        XCTAssertEqual(LandingHomeLayout.rows(layout).map(\.section),
                       [.jumpBackIn, .recentlyAdded, .activities, .rediscover, .recentlyPlayed, .yourPlaylists,
                        .otherProfiles, .lyricHighlight, .memoryGrid])
        XCTAssertEqual(LandingHomeLayout.move(.default, from: 0, to: 0), .default)
        XCTAssertEqual(LandingHomeLayout.move(.default, from: 0, to: 9), .default)
    }

    func test_should_keepUnlistedSectionsInTheirSlots_when_reordering() {
        // Every section is listed on iOS now: the rest keep their order around the moved one.
        let layout = LandingHomeLayout.move(.default, from: 5, to: 0)
        XCTAssertEqual(layout.sections.last?.section, .memoryGrid)
        XCTAssertEqual(layout.sections.first?.section, .yourPlaylists)
    }

    func test_should_keepHiddenRowsListed_when_sectionIsSwitchedOff() {
        let layout = HomeLayout.default.withEnabled(.rediscover, false)
        XCTAssertEqual(LandingHomeLayout.rows(layout).count, HomeSection.allCases.count)
        XCTAssertEqual(LandingHomeLayout.rows(layout).first { $0.section == .rediscover }?.enabled, false)
        XCTAssertEqual(layout.hiddenSections, [.rediscover])
    }

    func test_should_saveLayoutPerAccount_when_notDefault() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("home-layout-\(UUID().uuidString).json")
        addTeardownBlock { try? FileManager.default.removeItem(at: url) }
        let store = HomeLayoutStore(fileURL: url)
        let custom = HomeLayout.default.withEnabled(.activities, false)
        LandingHomeLayout.write(custom, scope: "subsonic|http://a|me", to: store)
        XCTAssertEqual(store.layout("subsonic|http://a|me").enabledSections.contains(.activities), false)
        // Another account is untouched.
        XCTAssertNil(store.prefs("subsonic|http://b|me"))
        // Back to the default clears the account to "never customized".
        LandingHomeLayout.write(.default, scope: "subsonic|http://a|me", to: store)
        XCTAssertNil(store.prefs("subsonic|http://a|me"))
    }

    func test_should_pickTargets_when_landingFinishes() {
        let created = Profile(id: "new", service: .subsonic, baseUrl: "http://a", username: "me", createdAt: 1)
        let active = Profile(id: "old", service: .appleMusic, createdAt: 0)
        XCTAssertEqual(LandingHomeLayout.targets(created: [created], active: active, mode: .firstRun).map(\.id), ["new"])
        XCTAssertEqual(LandingHomeLayout.targets(created: [created], active: active, mode: .rerun).map(\.id), ["new", "old"])
        XCTAssertEqual(LandingHomeLayout.targets(created: [created], active: created, mode: .rerun).map(\.id), ["new"])
    }

    func test_should_matchHomeScopes_when_accountsAreWritten() {
        let sub = Profile(id: "s", service: .subsonic, baseUrl: "http://a", username: "me", createdAt: 1)
        XCTAssertEqual(LandingHomeLayout.homeScope(of: sub), "subsonic|http://a|me")
        var spotify = Profile(id: "p", service: .spotify, createdAt: 1)
        XCTAssertNil(LandingHomeLayout.homeScope(of: spotify))
        spotify.spotifyUserId = "u1"
        XCTAssertEqual(LandingHomeLayout.homeScope(of: spotify), "spotify|spotify|u1")
    }
}
