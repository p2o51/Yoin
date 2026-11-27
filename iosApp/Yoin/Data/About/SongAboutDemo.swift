#if DEBUG
import Foundation

/// Screenshot aid for About record management on the mock song (no Gemini
/// key, no server): `-aboutDemo rows` files six facts, a question and an own
/// entry under the mock song in a scratch store, and Now Playing's About shows
/// them in place of the mock's server facts. `sheet` also opens the long-press
/// sheet on the question; `thinking` shows it being regenerated; `focus`
/// leaves a Manage in Scrapbook request for the question (pair with
/// `-screen album-scrapbook`). Debug builds only.
@MainActor
enum SongAboutDemo {
    static var mode: String? { UserDefaults.standard.string(forKey: "aboutDemo") }

    private static var key: SongAboutStore.Entry.SongKey?

    static func launch(album: Album, track: Track) {
        guard let mode else { return }
        let title = track.title, artist = track.artist ?? album.artist, albumTitle = album.title
        let store = SongAboutStore.shared
        let language = GeminiSettings.normalizeLanguage(GeminiSettings.shared.targetLanguage)
        // The app in Chinese gets the rows in Chinese, so the on-device follow-ups (which must be Chinese there)
        // have Chinese notes to read.
        let zh = (Bundle.main.preferredLocalizations.first ?? "en").hasPrefix("zh")
        store.saveCanonical(title: title, artist: artist, album: albumTitle, values: [
            .init(key: SongAboutStore.Canon.creationTime, answer: zh ? "2019 年春" : "Spring 2019"),
            .init(key: SongAboutStore.Canon.creationLocation, answer: zh ? "下北泽一间租来的小屋" : "A rented room in Shimokitazawa"),
            .init(key: SongAboutStore.Canon.lyricist, answer: "Mina Asahi"),
            .init(key: SongAboutStore.Canon.composer, answer: "Mina Asahi, Ren Kudo"),
            .init(key: SongAboutStore.Canon.producer, answer: "Ren Kudo"),
            .init(key: SongAboutStore.Canon.review, answer: zh
                ? "写于回家的末班电车上；钢琴是在夜里**一遍录成**的。"
                : "Written on the last train home; the piano was recorded **in one take** at night."),
        ], language: language, now: Date(timeIntervalSinceNow: -3600))
        store.saveAsk(title: title, artist: artist, album: albumTitle, question: "Who plays the piano?",
                      reply: .init(title: zh ? "钢琴" : "The piano",
                                   answer: zh ? "Ren Kudo，弹的是向朋友借来的立式钢琴。" : "Ren Kudo, on an upright borrowed from a friend."),
                      language: language, now: Date(timeIntervalSinceNow: -1800))
        store.writeOwn(title: title, artist: artist, album: albumTitle, heading: zh ? "第一次听到" : "Where I first heard it",
                       text: zh ? "下雨天，出租车的电台里。" : "On the radio in a taxi, raining.")
        let k = SongAboutStore.key(title: title, artist: artist, album: albumTitle)
        key = k
        let ask = SongAboutStore.Entry.Ref(song: k, kind: SongAboutStore.Kind.ask, entryKey: "who plays the piano?")
        switch mode {
        case "thinking": SongAboutLoader.shared.demoRegenerating(ask)
        case "focus": ScrapbookFocusRequest.shared.request(songId: track.id, edit: ask, now: Date(timeIntervalSinceNow: 20))
        default: break
        }
    }

    /// The mock song's rows, for Now Playing's About (nil when the demo is off).
    static func rows() -> [SongAboutStore.Entry]? {
        guard mode != nil, let key else { return nil }
        return SongAboutStore.shared.entries(key).filter { !$0.isBlank }
    }
}
#endif
