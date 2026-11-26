#if DEBUG
import Foundation
import YoinShared

/// Screenshot aid for Album About on the mock album (no AI key, no server; pair with `-screen album-scrapbook`):
/// `-albumAboutDemo <mode>` files rows under the mock album in a scratch store and gives the mock album its
/// service facts (a label, release dates), so page 2 shows the credits line and the About piece.
///
/// - `content`: credits, the paragraph and two questions (the card with "Read all").
/// - `apple`: Apple Music's own notes as the paragraph, the AI's credits only.
/// - `empty`: asked, and the AI knew nothing (the dashed ✦ circle).
/// - `questions`: no paragraph, two questions (the Ask card).
/// - `pending`: the card while the About is first written (the AI's dots).
/// - `none`: the label and dates only (the line from the service alone, no piece: a failure leaves nothing).
///
/// `-albumAboutSheet YES` also opens the About sheet once the page settles. Words follow the app's language
/// (Chinese or English). Debug builds only.
@MainActor
enum AlbumAboutDemo {
    static var mode: String? { UserDefaults.standard.string(forKey: "albumAboutDemo") }
    static var opensSheet: Bool { UserDefaults.standard.bool(forKey: "albumAboutSheet") }

    private static var zh: Bool { Bundle.main.preferredLocalizations.first?.hasPrefix("zh") == true }
    private static var seeded: Set<String> = []

    /// The mock album as a service would describe it (labels, dates, Apple's notes in `apple`).
    static func decorate(_ album: Album) -> Album {
        guard let mode, album.id.provider == "mock" else { return album }
        var a = album
        a.recordLabels = ["Harbor Light Records"]
        a.releaseDate = "2024-03-08"
        a.originalReleaseDate = "2014-05-20"
        if mode == "apple" {
            a.editorialNotes = zh
                ? "十年前，Mina Asahi 在镰仓海边一间租来的录音室里录下了这张专辑。周年版把原来的十首歌重新混音，又补上了三首当年没收进来的小样：钢琴更近，人声更轻，像是把那年冬天重新打开。"
                : "Ten years on, Mina Asahi returns to the record she cut in a rented studio by the sea in Kamakura. The anniversary edition remixes the original ten songs and adds three demos that never made the first pressing: the piano sits closer, the voice lighter, as if that winter had been opened again."
        }
        return a
    }

    /// Files the mode's rows under the mock album once (called when its page appears, never during a body update).
    static func seed(_ album: Album) {
        guard let mode, album.id.provider == "mock" else { return }
        let key = "\(album.id.provider)/\(album.id.rawId)"
        guard seeded.insert(key).inserted else { return }
        let store = AlbumAboutStore.shared
        let language = GeminiSettings.normalizeLanguage(GeminiSettings.shared.targetLanguage)
        let credits = AlbumAiCredits(
            label: [],
            producers: zh ? ["久保廉", "林美"] : ["Ren Kudo", "Mei Lin"],
            mastering: zh ? ["佐藤秋"] : ["Aki Sato"],
            mixing: [],
            recordedTime: zh ? "2013年9月至12月" : "Sep–Dec 2013",
            recordedStudios: zh ? ["Studio Mare（镰仓）"] : ["Studio Mare, Kamakura"],
            source: nil,
            firstReleased: nil)
        let blurb = zh
            ? "《\(album.title)》是 Mina Asahi 的第二张录音室专辑，也是她第一次离开东京录音。整张唱片在镰仓海边的一间小录音室里完成，钢琴和人声大多一次录成，留下了房间里的风声和木地板的回响。它把她早期的民谣写法推向更安静、更留白的编曲，此后几年的作品几乎都从这里出发。最常被提起的是那首在末班车上写下的《余韵》：据说录音那晚停了电，钢琴是借着应急灯弹完的。"
            : "\(album.title) was Mina Asahi's second studio album and the first she made away from Tokyo. It was cut in a small studio by the sea in Kamakura, the piano and vocals mostly in one take, with the room's wind and floorboards left in. It pushed her early folk writing toward quieter, more open arrangements, and most of what she made in the following years starts here. The song people bring up most was written on a last train home; the night it was recorded the power went out, and the piano part was finished by emergency light."
        switch mode {
        case "content":
            store.saveCanonical(album: album, credits: credits, blurb: blurb, language: language,
                                now: Date(timeIntervalSinceNow: -7200))
            askTwo(album, store: store, language: language)
        case "apple":
            store.saveCanonical(album: album, credits: credits, blurb: nil, language: language,
                                now: Date(timeIntervalSinceNow: -7200))
        case "empty":
            store.saveCanonical(album: album, credits: AlbumAiCredits(label: [], producers: [], mastering: [], mixing: [],
                                                                      recordedTime: nil, recordedStudios: [],
                                                                      source: nil, firstReleased: nil),
                                blurb: nil, language: language, now: Date(timeIntervalSinceNow: -7200))
        case "questions":
            askTwo(album, store: store, language: language)
        case "pending":
            AlbumAboutLoader.shared.demoWriting(album)
        default:
            break
        }
    }

    private static func askTwo(_ album: Album, store: AlbumAboutStore, language: String) {
        store.saveAsk(album: album, question: zh ? "钢琴是谁弹的？" : "Who played the piano?",
                      reply: .init(title: zh ? "钢琴" : "The piano",
                                   answer: zh ? "是制作人**久保廉**，用的是录音室里一台旧立式钢琴。"
                                              : "The producer, **Ren Kudo**, on the studio's old upright."),
                      language: language, now: Date(timeIntervalSinceNow: -3600))
        store.saveAsk(album: album, question: zh ? "为什么去镰仓录？" : "Why record in Kamakura?",
                      reply: .init(title: zh ? "离开东京" : "Leaving Tokyo",
                                   answer: zh ? "她想要一个能听见海的房间，也想躲开城市的节奏。"
                                              : "She wanted a room where you could hear the sea, away from the city's pace."),
                      language: language, now: Date(timeIntervalSinceNow: -1800))
    }
}
#endif
