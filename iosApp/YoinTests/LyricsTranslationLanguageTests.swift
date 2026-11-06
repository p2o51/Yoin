import XCTest
@testable import Yoin

/// Codex N23: the in-memory lyric translations are per song and per target language.
@MainActor
final class LyricsTranslationLanguageTests: XCTestCase {
    private var defaults: UserDefaults!
    private var keychain: GeminiKeychain!

    override func setUp() async throws {
        defaults = UserDefaults(suiteName: "lyrics-translation-\(UUID().uuidString)")
        keychain = GeminiKeychain(service: "com.gpo.yoin.gemini.tests.\(UUID().uuidString)")
    }

    override func tearDown() async throws { keychain.delete() }

    private func track(_ title: String) -> PlayableTrack {
        PlayableTrack(id: "n23-\(title)", provider: "subsonic", title: title, artist: "Artist", album: "Album",
                      durationSec: 200, streamURL: URL(string: "https://example.invalid/s")!)
    }

    func test_should_notToggleOldTranslation_when_targetLanguageChanged() {
        let settings = GeminiSettings(keychain: keychain, defaults: defaults)
        let actions = LyricsActions()
        let t = track("Lantern \(UUID().uuidString)")
        let lines = ["la la", "mi mi"]
        // English is already translated (a saved Gemini answer makes the tap apply at once).
        LyricsUserStore.shared.saveGeminiTranslations(["one", "two"], provider: t.provider, rawId: t.id, title: t.title,
                                                      artist: t.artist, language: "English", model: GeminiClient.model,
                                                      lines: lines)
        actions.translate(t, lines: lines, settings: settings)
        XCTAssertTrue(actions.translationShown(t, language: "English"))
        XCTAssertEqual(actions.shownTranslations(t, count: 2, language: "English"), ["one", "two"])

        settings.saveLanguage("Japanese")
        actions.translate(t, lines: lines, settings: settings)

        // No Japanese text and no key: nothing shows, and the English layer is left as it was.
        XCTAssertFalse(actions.translationShown(t, language: "Japanese"))
        XCTAssertEqual(actions.shownTranslations(t, count: 2, language: "Japanese"), [nil, nil])
        XCTAssertTrue(actions.translationShown(t, language: "English"))
        XCTAssertNil(actions.inFlight)

        // Back to English: the second tap toggles that language's layer, as before.
        settings.saveLanguage("English")
        actions.translate(t, lines: lines, settings: settings)
        XCTAssertFalse(actions.translationShown(t, language: "English"))
    }
}
