#if DEBUG
import Foundation
import YoinShared

/// Screenshots (`-noteMentionsDemo YES|peek`): notes with mentions on the mock library, written once into the
/// stores — the song playing (al1-t3) mentions an album, a song and an artist; songs and a sticky on other
/// albums mention Afterglow Letters, so its scrapbook has a Mentioned in section. `peek` also holds a peek
/// card open over the first pill drawn.
@MainActor
enum NoteMentionsDemo {
    static var mode: String? { UserDefaults.standard.string(forKey: "noteMentionsDemo") }

    static func seed() {
        guard mode != nil else { return }
        let notes = NotesStore.shared
        guard !notes.notes.contains(where: { $0.provider == "mock" && $0.content.contains("](yoin:") }) else { return }
        let editor = YoinNoteEditor.shared
        func link(_ kind: MentionKind, _ id: String, _ title: String, _ artist: String?, _ album: String? = nil) -> String {
            editor.mention(kind: kind, provider: "mock", id: id, title: title, artist: artist, album: album)
                .flatMap(editor.serializeMention) ?? title
        }
        let neon = link(.album, "al4", "Neon Garden", "Kaito Mori")
        let lumen = link(.track, "al12-t1", "Lumen", "Aster Bloom", "Lumen Field")
        let mina = link(.artist, "Mina Asahi", "Mina Asahi", nil)
        let long = link(.album, "al7", "Blue Hour Atlas (Deluxe Anniversary Edition, Remastered)", "Selene Park")
        let afterglow = link(.album, "al1", "Afterglow Letters", "Mina Asahi")
        let lateBus = link(.track, "al1-t2", "Late Bus", "Mina Asahi", "Afterglow Letters")
        notes.add(provider: "mock", trackId: "al1-t3", content: "Same hush as \(neon), right before the chorus", positionMs: 31_000,
                  title: "余韻", artist: "Mina Asahi")
        notes.add(provider: "mock", trackId: "al1-t3", content: "- [x] Find the live take\n- [ ] Ask about \(lumen)",
                  positionMs: 62_000, title: "余韻", artist: "Mina Asahi")
        notes.add(provider: "mock", trackId: "al1-t3", content: "**\(mina)** at her quietest. Pairs with \(long)",
                  positionMs: 95_000, title: "余韻", artist: "Mina Asahi")
        notes.add(provider: "mock", trackId: "al4-t2", content: "The synth line is lifted from \(lateBus)", positionMs: 48_000,
                  title: "Night Market", artist: "Kaito Mori")
        AlbumNotesStore.shared.add(provider: "mock", albumId: "al12", content: "Play it after \(afterglow) on a long drive",
                                   albumName: "Lumen Field", artist: "Aster Bloom")
    }
}
#endif
