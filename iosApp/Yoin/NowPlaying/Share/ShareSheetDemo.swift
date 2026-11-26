import SwiftUI
import UIKit
import YoinShared

/// DEBUG launch hooks for screenshots (`-screen np -shareDemo lyrics|about|note`, optional `-shareFormat story`,
/// `-shareTone 0…3`, `-shareLayout <storeValue>`, `-shareTranslation YES`): the share sheet opens over Now Playing
/// on that tab with demo content (Android's PreviewShareContent, the mock album's cover, a mention pill in the
/// note). Release builds read nothing and open on Card, slot 0.
@MainActor
enum ShareSheetDemo {
    #if DEBUG
    private static var defaults: UserDefaults { .standard }

    static var tab: ShareTab? {
        switch defaults.string(forKey: "shareDemo") {
        case "lyrics": .lyrics
        case "about": .about
        case "note": .note
        default: nil
        }
    }

    static var format: ShareFormat { tab != nil && defaults.string(forKey: "shareFormat") == "story" ? .story : .card }
    static var toneSlot: Int { tab != nil ? min(max(defaults.integer(forKey: "shareTone"), 0), 3) : 0 }

    /// The demo content, with the asked layout remembered for its tab first.
    static func render(album: Album) -> ShareRender? {
        guard let tab else { return nil }
        if let stored = defaults.string(forKey: "shareLayout"),
           let layout = YoinShareCards.shared.layoutForStoreValue(tab: tab, value: stored) {
            ShareLayoutStore().set(layout, for: tab)
        }
        let zh = (Bundle.main.preferredLocalizations.first ?? "en").hasPrefix("zh")
        var render = ShareRender(content: content(zh: zh, translations: defaults.bool(forKey: "shareTranslation")),
                                 aiName: AiProviderName.current)
        let r = ImageRenderer(content: CoverArt(palette: album.palette, cornerRadius: 0, seed: album.seed)
            .frame(width: 160, height: 160))
        r.scale = 2
        render.cover = r.uiImage
        if let mention = MockLibrary.allAlbums.first {
            let m = YoinNoteEditor.shared.mention(kind: .album, provider: "mock", id: mention.id.rawId,
                                                  title: mention.title, artist: mention.artist, album: nil)
            if let m, let image = NoteMentionArtwork.shared.mockImage(m) { render.mentionCovers[m.link] = image }
        }
        return render
    }

    /// Android PreviewShareContent (他夏了夏天, 苏打绿), the About paragraph in the app's language, the note with
    /// one mention of the mock library's first album.
    static func content(zh: Bool, translations: Bool) -> ShareCardContent {
        let labels = ShareStrings.aboutLabels()
        let song = ShareSong(songId: "preview", title: "他夏了夏天", artist: "苏打绿", coverArtUrl: nil,
                             albumName: "夏/狂热", durationMs: 305_000)
        let lyrics = ShareLyrics(lines: [
            ShareContentBuilder.line(text: "帶著笑容的睡意化成了彩虹", startMs: 226_800,
                                     translation: translations ? "A smiling drowsiness turned into a rainbow" : nil),
            ShareContentBuilder.line(text: "在他夢中一口氣走上了星空", startMs: 230_700,
                                     translation: translations ? "In his dream he climbed to the stars in one breath" : nil),
        ], passageStarts: [], showTranslation: translations)
        let paragraph = zh
            ? "一段占位的背景介绍：收录在哪张专辑、写的是什么、和谁有关。两三句话，由 AI 整理。"
            : "A placeholder background note: the album it's on, what it's about, who it involves. Two or three sentences, put together by AI."
        let about = ShareAbout(credits: [
            ShareCredit(label: labels.lyricist, value: "吴青峰", ai: false, fact: false),
            ShareCredit(label: labels.composer, value: "吴青峰", ai: false, fact: false),
            ShareCredit(label: labels.producer, value: "林暐哲", ai: true, fact: false),
            ShareCredit(label: labels.year, value: "2009", ai: false, fact: true),
        ], heading: labels.review, paragraph: paragraph)
        var mention = ""
        if let first = MockLibrary.allAlbums.first {
            let editor = YoinNoteEditor.shared
            mention = editor.mention(kind: .album, provider: "mock", id: first.id.rawId, title: first.title,
                                     artist: first.artist, album: nil).flatMap(editor.serializeMention) ?? ""
        }
        let note = ShareNote(noteId: "n1",
                             content: "最后一段鼓整个打开，**一口气走上了星空**。每次下班走出地铁，刚好听到这里。" + (mention.isEmpty ? "" : " " + mention),
                             positionMs: KotlinLong(value: 226_000), createdAt: 1_784_390_400_000)
        return ShareCardContent(song: song, lyrics: lyrics, about: about, note: note)
    }
    #else
    static var format: ShareFormat { .card }
    static var toneSlot: Int { 0 }
    #endif
}
