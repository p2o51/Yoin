import XCTest
@testable import Yoin

/// The Mac window's smallest size (App/WindowFloor.swift): which platforms get one, that the phone shell
/// is laid out at it, and that a scene takes it.
@MainActor
final class WindowFloorTests: XCTestCase {
    func test_should_stopAtOnePhoneColumn_when_onAMac() {
        XCTAssertEqual(WindowFloor.minimumSize(on: .mac), CGSize(width: 360, height: 480))
    }

    func test_should_keepTheSystemMinimum_when_onIPhoneOrIPad() {
        XCTAssertNil(WindowFloor.minimumSize(on: .device))
    }

    func test_should_followTheFlag_when_noPlatformIsGiven() {
        XCTAssertEqual(WindowFloor.minimumSize() != nil, Platform.current.isMac)
        Platform.$standIn.withValue(.mac) { XCTAssertNotNil(WindowFloor.minimumSize()) }
        Platform.$standIn.withValue(.device) { XCTAssertNil(WindowFloor.minimumSize()) }
    }

    /// The floor is the app's own lines: a phone column wide (the narrowest the side panel and the
    /// enlarged player's column get), and the height from which a window is no longer a phone on its side.
    func test_should_beTheLayoutsOwnLines_when_compared() {
        XCTAssertEqual(WindowFloor.width, AppModel.padPanelWidth(WindowFloor.width))
        XCTAssertEqual(WindowFloor.width, NowPlayingPadFrame.enlargedColumnWidth(windowWidth: WindowFloor.width))
        XCTAssertEqual(WindowFloor.height, NowPlayingPadFrame.mediumMinHeight)
    }

    /// Now Playing at the floor: under a 32 pt title bar and over the 16 pt bottom inset the column
    /// still shows its cover (not below Android's COVER_FLOOR) and every slot fits.
    func test_should_keepNowPlayingsCover_when_windowIsAtTheFloor() {
        let column = WindowFloor.height - 32 - 16
        let pose = NowPlayingBudget(width: WindowFloor.width, height: column, forceShort: false).rest
        XCTAssertGreaterThanOrEqual(pose.cover, Tokens.np.COVER_FLOOR.cg)
        let slots = pose.topBar + pose.cover + pose.coverGap + pose.tabs + pose.tabGap
            + pose.lyricWindow + pose.transport + pose.hero + pose.pills
        XCTAssertLessThanOrEqual(slots, column + 0.5)
    }

    /// A short step under the floor and the cover is gone: the floor is not far above what the column needs.
    func test_should_loseTheCover_when_columnIsWellUnderTheFloor() {
        let pose = NowPlayingBudget(width: WindowFloor.width, height: WindowFloor.height - 32 - 16 - 48, forceShort: false).rest
        XCTAssertEqual(pose.cover, 0)
    }

    func test_should_giveTheSceneItsMinimum_when_sceneCanBeResized() throws {
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        guard let restrictions = scene.sizeRestrictions else {
            // No size restrictions on this device: nothing to set, and nothing breaks.
            WindowFloor.apply(to: scene, on: .mac)
            return XCTAssertNil(scene.sizeRestrictions)
        }
        let before = restrictions.minimumSize
        defer { restrictions.minimumSize = before }

        WindowFloor.apply(to: scene, on: .device)
        XCTAssertEqual(restrictions.minimumSize, before)
        WindowFloor.apply(to: scene, on: .mac)
        XCTAssertEqual(scene.sizeRestrictions?.minimumSize, CGSize(width: 360, height: 480))
        WindowFloor.apply(to: nil, on: .mac)
    }
}
