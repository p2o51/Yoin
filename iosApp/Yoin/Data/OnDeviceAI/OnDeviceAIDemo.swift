#if DEBUG
import Foundation
import YoinShared

/// Screenshot aid for Home's on-device greeting on the mock library (no account, no history):
/// `-greetingDemo YES` turns the Labs switch on and asks the real Apple Intelligence model once, with the mock
/// albums standing in for the three listening cues, under the mock's account (""). Nothing is faked: with the
/// model unavailable (or the language unsupported) Home keeps its static greeting. Debug builds only.
@MainActor
enum OnDeviceAIDemo {
    static var greeting: Bool { UserDefaults.standard.bool(forKey: "greetingDemo") }

    static func runGreetingIfRequested(_ model: AppModel) async {
        guard greeting, !model.store.isReal else { return }
        LyricHighlightStore.shared.greetingsEnabled = true
        let albums = model.library.albums()
        guard albums.count >= 3 else { return }
        let ai = YoinOnDeviceAi.shared
        let cues = ai.slotCues(morning: ai.recentPlay(artist: albums[0].artist, album: albums[0].title),
                               afternoon: ai.recentPlay(artist: albums[1].artist, album: albums[1].title),
                               evening: ai.recentPlay(artist: albums[2].artist, album: albums[2].title))
        let text = AppleOnDeviceTextModel()
        guard text.status() == .available, text.supportsLanguage(OnDeviceLanguage.app) else {
            print("[greetingDemo] model \(text.status()) language \(OnDeviceLanguage.app) — static greeting stays")
            return
        }
        let outcome = await HomeGreetingStore.shared.generateIfNeeded(profileId: "", cues: cues, model: text)
        print("[greetingDemo] \(outcome): \(HomeGreetingStore.shared.greetings[""].map { "\($0)" } ?? "none")")
    }
}
#endif
