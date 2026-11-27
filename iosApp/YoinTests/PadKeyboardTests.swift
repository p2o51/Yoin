import XCTest
@testable import Yoin

/// iPad hardware keyboard rules (App/YoinCommands.swift): Esc beside a field and an input method, ⌘3.
@MainActor
final class PadKeyboardTests: XCTestCase {
    func test_should_leaveTheCandidatesAlone_when_anInputMethodIsComposing() {
        XCTAssertEqual(KeyboardEscape.action(textInput: true, composing: true), .none)
    }

    func test_should_onlyPutTheKeyboardAway_when_aFieldHasIt() {
        XCTAssertEqual(KeyboardEscape.action(textInput: true, composing: false), .endEditing)
    }

    func test_should_goBack_when_noFieldHasTheKeyboard() {
        XCTAssertEqual(KeyboardEscape.action(textInput: false, composing: false), .back)
    }

    func test_should_openAllMemoriesOnce_when_command3IsPressedAgain() {
        let once = KeyboardEscape.allMemoriesPath([])
        XCTAssertEqual(once, [.allMemories])
        XCTAssertEqual(KeyboardEscape.allMemoriesPath(once), once)
    }

    func test_should_keepThePageUnder_andCloseWhatIsOnTop_when_allMemoriesIsInThePath() {
        let artist = Route.artist(ArtistRef("Mina Asahi"))
        XCTAssertEqual(KeyboardEscape.allMemoriesPath([artist]), [artist, .allMemories])
        XCTAssertEqual(KeyboardEscape.allMemoriesPath([.allMemories, artist]), [.allMemories])
    }

    func test_should_closeSettingsFirst_when_itIsUp() {
        XCTAssertEqual(KeyboardEscape.allMemoriesPath([.settings, .labs]), [.allMemories])
    }
}
