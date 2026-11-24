import SwiftUI
import XCTest
import YoinShared
@testable import Yoin

/// Notes phase 1c, display half (Android 0.6.4): mention pills, to-do ticking, Mentioned in.
@MainActor
final class NoteDisplayParityTests: XCTestCase {
    private let style = NoteMarkupStyle(size: 15, mentionColor: .red, glyphColor: .blue)
    private let andromedaLink = "[@Andromeda](yoin:track/applemusic:1445555555?a=Weyes%20Blood&al=Titanic%20Rising)"

    private func temp(_ name: String) throws -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: dir) }
        return dir.appendingPathComponent(name)
    }

    // MARK: Pills

    func test_should_glueAPillsName_soNoLineBreaksInsideIt() {
        let glued = NoteMentionPill.glued("林间 Live")
        XCTAssertFalse(glued.contains(" "), "spaces become no-break spaces")
        XCTAssertEqual(glued.replacingOccurrences(of: "\u{2060}", with: ""), "林间\u{00A0}Live")
        XCTAssertEqual(glued.filter { $0 == "\u{2060}" }.count, 6, "a word joiner between every two characters")
        XCTAssertEqual(NoteMentionPill.glued("A"), "A")
    }

    func test_should_cutALongNameWithAnEllipsis_toFitTheLine_andKeepAShortOneWhole() {
        let measure: (String) -> CGFloat = { CGFloat($0.count) * 10 }
        // chrome = 1.97 em at 10 pt ≈ 20 pt, 2 pt slack: 100 pt of line leaves 78 pt: six characters and the ellipsis.
        let cut = NoteMentionPill.fit("Blue Hour Atlas (Deluxe)", scaled: 10, lineWidth: 100, measure: measure)
        XCTAssertTrue(cut.hasSuffix("…"))
        XCTAssertLessThanOrEqual(measure(cut), 78)
        XCTAssertEqual(cut, "Blue H…")
        XCTAssertEqual(NoteMentionPill.fit("Lumen", scaled: 10, lineWidth: 100, measure: measure), "Lumen")
        XCTAssertEqual(NoteMentionPill.fit("Blue Hour Atlas (Deluxe)", scaled: 10, lineWidth: nil, measure: measure),
                       "Blue Hour Atlas (Deluxe)", "an unknown line cuts nothing")
        XCTAssertEqual(NoteMentionPill.fit("Blue", scaled: 10, lineWidth: 5, measure: measure), "B…",
                       "at least one character stays")
    }

    func test_should_makeAResolvedMentionAPill_andLeaveAnUnresolvedOneAsWords() {
        let text = "像 \(andromedaLink) 和 [@X](yoin:album/spotify:x)"
        let pieces = NoteMarkupRender.pieces(text, style: style) { $0.provider == "applemusic" ? .linked(URL(string: $0.link)!) : .plain }
        let pills = pieces.compactMap { if case .pill(let p) = $0 { p } else { nil } }
        XCTAssertEqual(pills.map(\.mention.title), ["Andromeda"])
        XCTAssertEqual(pills.first?.link?.absoluteString, "yoin:track/applemusic:1445555555?a=Weyes%20Blood&al=Titanic%20Rising")
        let words = pieces.compactMap { if case .text(let a) = $0 { String(a.characters) } else { nil } }
        XCTAssertEqual(words, ["像 ", " 和 X"])
    }

    // MARK: To-dos

    func test_should_linkEachToDosBox_toItsLine_whenTicking() throws {
        let a = NoteMarkupRender.attributed("# h\n- [ ] one\n- [x] two", style: style, todoLinks: true) { _ in .plain }
        let links = a.runs.compactMap(\.link)
        XCTAssertEqual(links.map(NoteMarkupRender.todoLine), [1, 2])
        XCTAssertNil(NoteMarkupRender.todoLine(URL(string: "yoin:album/x:1")!))
        XCTAssertTrue(NoteMarkupRender.attributed("- [ ] one", style: style) { _ in .plain }.runs.allSatisfy { $0.link == nil })
    }

    func test_should_tickASongNotesToDo_andSaveItKeepingEveryOtherCharacter() throws {
        let url = try temp("notes.json")
        let store = NotesStore(fileURL: url)
        let note = try XCTUnwrap(store.add(provider: "applemusic", trackId: "t1",
                                           content: "# 现场\n- [ ] 开头 \(andromedaLink)\n- [x] 副歌",
                                           positionMs: 1000, title: "T", artist: "A"))
        XCTAssertEqual(NoteChecklist.toggleSongNote(note.id, line: 1, store: store)?.content,
                       "# 现场\n- [x] 开头 \(andromedaLink)\n- [x] 副歌")
        XCTAssertEqual(NoteChecklist.toggleSongNote(note.id, line: 2, store: store)?.content,
                       "# 现场\n- [x] 开头 \(andromedaLink)\n- [ ] 副歌")
        XCTAssertNil(NoteChecklist.toggleSongNote(note.id, line: 0, store: store), "a heading isn't ticked")
        store.flush()
        XCTAssertEqual(NotesStore(fileURL: url).note(id: note.id)?.content,
                       "# 现场\n- [x] 开头 \(andromedaLink)\n- [ ] 副歌", "written to disk")
    }

    func test_should_tickAnAlbumNotesToDo() throws {
        let store = AlbumNotesStore(fileURL: try temp("album_notes.json"))
        let note = try XCTUnwrap(store.add(provider: "applemusic", albumId: "al", content: "- [ ] buy the vinyl",
                                           albumName: "Titanic Rising", artist: "Weyes Blood"))
        NoteChecklist.toggleAlbumNote(note.id, line: 0, store: store)
        XCTAssertEqual(store.note(id: note.id)?.content, "- [x] buy the vinyl")
    }

    // MARK: Mentioned in

    private let titanic = BacklinkAlbum(provider: "applemusic", id: "1", title: "Titanic Rising", artist: "Weyes Blood",
                                        tracks: [.init(id: "1445555555", title: "Andromeda", artist: "Weyes Blood")])

    private func songNote(_ id: String, track: String = "t9", album: String? = "other") -> BacklinkSource {
        .songNote(noteId: id, trackId: track, title: "Song \(id)", artist: "A", positionMs: 83_000, albumId: album, coverKey: nil)
    }

    func test_should_findWritingsMentioningTheAlbumOrItsSongs_newestFirst() {
        let writings = [
            BacklinkWriting(source: songNote("n1"), text: "listen to \(andromedaLink)", at: 10),
            BacklinkWriting(source: .albumNote(noteId: "a1", albumId: "al2", albumName: "Other", artist: "B"),
                            text: "after [@Titanic Rising](yoin:album/applemusic:1?a=Weyes%20Blood)", at: 30),
            BacklinkWriting(source: .review(albumId: "al3", albumName: "Third", artist: nil),
                            text: "nothing here [@Else](yoin:album/applemusic:9)", at: 50),
        ]
        let links = NoteBacklinks.find(writings, album: titanic)
        XCTAssertEqual(links.map(\.source.sortKey), ["a:a1", "n:n1"])
        let first = links[0]
        XCTAssertEqual(first.snippet, "after Titanic Rising")
        XCTAssertEqual(first.marks.map { (first.snippet as NSString).substring(with: NSRange(location: $0.lowerBound, length: $0.count)) },
                       ["Titanic Rising"])
    }

    func test_should_leaveOutTheAlbumsOwnWritings() {
        let writings = [
            BacklinkWriting(source: .review(albumId: "1", albumName: nil, artist: nil), text: andromedaLink, at: 1),
            BacklinkWriting(source: .albumNote(noteId: "a", albumId: "1", albumName: "T", artist: "W"), text: andromedaLink, at: 2),
            BacklinkWriting(source: songNote("own", track: "1445555555", album: nil), text: andromedaLink, at: 3),
            BacklinkWriting(source: songNote("onAlbum", album: "1"), text: andromedaLink, at: 4),
        ]
        XCTAssertTrue(NoteBacklinks.find(writings, album: titanic).isEmpty)
    }

    func test_should_matchByName_acrossServices_andOnAppleMusic_butSubsonicStaysIdOnly() {
        let spotify = "[@Andromeda](yoin:track/spotify:sp1?a=Weyes%20Blood&al=Titanic%20Rising)"
        let byName = [BacklinkWriting(source: songNote("n"), text: spotify, at: 1)]
        XCTAssertEqual(NoteBacklinks.find(byName, album: titanic).count, 1, "another service: by name")
        let sameServiceOtherId = [BacklinkWriting(source: songNote("n"),
                                                  text: "[@Titanic Rising](yoin:album/applemusic:l.999?a=Weyes%20Blood)", at: 1)]
        XCTAssertEqual(NoteBacklinks.find(sameServiceOtherId, album: titanic).count, 1, "Apple Music library id: by name")
        var subsonic = titanic
        subsonic.provider = "subsonic"
        let subsonicOther = [BacklinkWriting(source: songNote("n"),
                                             text: "[@Titanic Rising](yoin:album/subsonic:2?a=Weyes%20Blood)", at: 1)]
        XCTAssertTrue(NoteBacklinks.find(subsonicOther, album: subsonic).isEmpty, "a Subsonic id is the server's own")
    }

    func test_should_describeAClipping_asKindMomentAndDate() {
        let link = NoteBacklink(source: songNote("n"), text: "", snippet: "", at: 0, marks: [])
        let meta = ScrapBacklinkCard.meta(link, nowMs: 3 * 86_400_000)
        XCTAssertEqual(meta.count, 3)
        XCTAssertEqual(meta[1], "1:23")
        XCTAssertEqual(ScrapBacklinkCard.target(songNote("n"), provider: "applemusic"),
                       .track(provider: "applemusic", id: "t9", title: "Song n", artist: "A"))
        let marked = ScrapBacklinkCard.highlighted("after Titanic Rising", marks: [6..<20], marker: .yellow)
        XCTAssertEqual(marked.runs.filter { $0.backgroundColor != nil }.map { String(marked[$0.range].characters) },
                       ["Titanic Rising"])
    }
}
