import XCTest
@testable import Yoin

/// Home's cold-start bloom (Home/HomeIntro, Android HomeGreetingTitle, parity A-U4).
@MainActor
final class HomeIntroTests: XCTestCase {
    func test_should_restAtOnce_when_reduceMotionIsOn() {
        let intro = HomeIntro()
        intro.play(reduceMotion: true)
        XCTAssertEqual(intro.arms, [1, 1, 1])
        XCTAssertEqual(intro.greeting, 1)
        XCTAssertEqual(intro.title, 1)
        XCTAssertFalse(intro.markShown)
        XCTAssertFalse(intro.running)
    }

    func test_should_playOncePerProcess_when_homeShowsAgain() async throws {
        let intro = HomeIntro()
        intro.play(reduceMotion: false)
        XCTAssertTrue(intro.markShown)
        XCTAssertTrue(intro.running, "the Memories bubble waits")
        try await Task.sleep(for: .seconds(2))
        XCTAssertFalse(intro.running)
        XCTAssertFalse(intro.markShown)
        XCTAssertEqual(intro.title, 1)
        intro.play(reduceMotion: false)
        XCTAssertFalse(intro.markShown, "a return to Home doesn't replay it")
    }
}
