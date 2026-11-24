import SwiftUI
import XCTest
import YoinShared
@testable import Yoin

/// iOS reads Android-written notes (notes revamp phase 1a): NoteMarkupRender draws :shared's NoteMarkup
/// (the examples are NoteMarkupTest's), NoteMentions resolves and routes mentions, and an edit keeps the
/// stored markup byte for byte.
@MainActor
final class NoteMarkupTextTests: XCTestCase {
    private let andromedaLink = "[@Andromeda](yoin:track/applemusic:1445555555?a=Weyes%20Blood&al=Titanic%20Rising)"
    private let titanicLink = "[@Titanic Rising](yoin:album/applemusic:1?a=Weyes%20Blood)"
    private let artistLink = "[@Hannah Diamond](yoin:artist/applemusic:hd)"
    private let style = NoteMarkupStyle(size: 15, mentionColor: .red, glyphColor: .blue)

    private func mentions(provider: String = "applemusic", names: [NoteMentionName] = [],
                          navigate: @escaping @MainActor (NoteMentionTarget) -> Void = { _ in }) -> NoteMentions {
        NoteMentions(account: { NoteMentionAccount(provider: provider, scope: "") }, names: { _, _ in names },
                     navigate: navigate)
    }

    private func render(_ text: String, _ m: NoteMentions? = nil, links: Bool = true) -> AttributedString {
        let m = m ?? mentions()
        return NoteMarkupRender.attributed(text, style: style) { m.draw($0, links: links) }
    }

    private func run(_ a: AttributedString, _ words: String) -> AttributedString.Runs.Run? {
        a.runs.first { String(a[$0.range].characters) == words }
    }

    // SwiftUI's attributes by key (UIKit's scope has attributes of the same names).
    private func color(_ r: AttributedString.Runs.Run?) -> Color? { r?[AttributeScopes.SwiftUIAttributes.ForegroundColorAttribute.self] }
    private func font(_ r: AttributedString.Runs.Run?) -> Font? { r?[AttributeScopes.SwiftUIAttributes.FontAttribute.self] }
    private func strike(_ r: AttributedString.Runs.Run?) -> Text.LineStyle? {
        r?[AttributeScopes.SwiftUIAttributes.StrikethroughStyleAttribute.self]
    }

    // MARK: Rendering

    func test_should_drawBlocksAsGlyphsAndStyles_when_readingThePrototypesNotes() {
        let a = render("# 现场版要记得\n- [x] 开头那段哼唱\n- [ ] 第二段副歌的和声\n- 一条\n**这一句**每次都会跟着唱 _轻_")
        XCTAssertEqual(String(a.characters), "现场版要记得\n☑\u{FE0E}  开头那段哼唱\n☐\u{FE0E}  第二段副歌的和声\n•  一条\n这一句每次都会跟着唱 轻")
        XCTAssertEqual(font(run(a, "现场版要记得")), NoteMarkupRender.font(size: 15 * 1.15, weight: .bold, italic: false))
        XCTAssertEqual(strike(run(a, "开头那段哼唱")), Text.LineStyle.single)
        XCTAssertNil(strike(run(a, "第二段副歌的和声")))
        XCTAssertEqual(color(run(a, NoteMarkupRender.done)), Color.blue)
        XCTAssertEqual(font(run(a, "这一句")), NoteMarkupRender.font(size: 15, weight: .bold, italic: false))
        XCTAssertEqual(font(run(a, "每次都会跟着唱 ")), NoteMarkupRender.font(size: 15, weight: .regular, italic: false))
        XCTAssertEqual(font(run(a, "轻")), NoteMarkupRender.font(size: 15, weight: .regular, italic: true))
    }

    func test_should_drawAResolvedMentionAsASemiBoldLink_inTheMentionColour() {
        let a = render("**像 \(andromedaLink) 一样**")
        XCTAssertEqual(String(a.characters), "像 Andromeda 一样")
        let mention = run(a, "Andromeda")
        XCTAssertEqual(mention?.link, URL(string: "yoin:track/applemusic:1445555555?a=Weyes%20Blood&al=Titanic%20Rising"))
        XCTAssertEqual(color(mention), Color.red)
        XCTAssertEqual(font(mention), NoteMarkupRender.font(size: 15, weight: .semibold, italic: false))
        XCTAssertNil(run(a, "像 ")?.link)
    }

    func test_should_drawAnUnresolvedMentionAsPlainWords_withNoLinkAndNoColour() {
        // A Spotify mention read on Apple Music, with nothing local to match it by name.
        let a = render("**看 [@Hannah Diamond](yoin:artist/spotify:hd) 的现场**")
        XCTAssertEqual(String(a.characters), "看 Hannah Diamond 的现场")
        let words = run(a, "看 Hannah Diamond 的现场")
        XCTAssertNotNil(words, "an unresolved mention is one run with the words around it")
        XCTAssertNil(words?.link)
        XCTAssertNil(color(words))
        XCTAssertEqual(font(words), NoteMarkupRender.font(size: 15, weight: .bold, italic: false))
    }

    func test_should_styleButNotLink_when_linksAreOff() {
        let mention = run(render("listen to \(titanicLink)", links: false), "Titanic Rising")
        XCTAssertNil(mention?.link)
        XCTAssertEqual(color(mention), Color.red)
    }

    func test_should_keepMalformedMarkupAndMarkdownLinksAsLiteralText() {
        for text in ["[@Title](yoin:movie/spotify:1)", "[@Title](yoin:track/spotify:", "snake_case_name", "2*3*4",
                     "#nospace", "**unclosed", "[x](https://example.com)", "<b>tag</b> `code` ~~gone~~"] {
            let a = render(text)
            XCTAssertEqual(String(a.characters), text, text)
            XCTAssertTrue(a.runs.allSatisfy { $0.link == nil }, text)
        }
    }

    func test_should_italiciseCjkWithoutSpaces_and_keepEmptyLines() {
        let a = render("听到_副歌_的时候\n\n• 点")
        XCTAssertEqual(String(a.characters), "听到副歌的时候\n\n•  点")
        XCTAssertEqual(font(run(a, "副歌")), NoteMarkupRender.font(size: 15, weight: .regular, italic: true))
    }

    func test_should_projectWordsAlone_forLabelsAndExcerpts() {
        XCTAssertEqual(NoteMarkupRender.words("# 标题\n- [x] **做完** \(andromedaLink)\n_斜_"), "标题\n做完 Andromeda\n斜")
        XCTAssertEqual(NoteMarkupRender.words("plain - words * here"), "plain - words * here")
    }

    // MARK: Routing

    func test_should_routeEachKindToItsPage_when_aResolvedLinkIsTapped() async throws {
        var opened: [NoteMentionTarget] = []
        let text = "\(titanicLink) · \(andromedaLink) · \(artistLink)"
        let links = render(text).runs.compactMap(\.link)
        XCTAssertEqual(links.count, 3)
        for url in links {
            let m = mentions { opened.append($0) }
            XCTAssertTrue(m.take(url, in: text))
        }
        XCTAssertEqual(opened, [
            .albumScrapbook(provider: "applemusic", id: "1", title: "Titanic Rising", artist: "Weyes Blood"),
            .track(provider: "applemusic", id: "1445555555", title: "Andromeda", artist: "Weyes Blood"),
            .artist(provider: "applemusic", id: "hd", name: "Hannah Diamond"),
        ])
    }

    func test_should_moveOnlyThatAlbumToItsScrapbook_when_anAlbumMentionAsks() {
        let r = ScrapbookFocusRequest()
        let titanic = MediaId(provider: "applemusic", rawId: "1")
        let other = MediaId(provider: "applemusic", rawId: "2")
        let now = Date()
        r.requestAlbum(titanic, now: now)
        XCTAssertFalse(r.wantsAlbum(other, now: now))
        XCTAssertFalse(r.takeAlbum(other, now: now), "another album leaves it for the right one")
        XCTAssertTrue(r.wantsAlbum(titanic, now: now))
        XCTAssertTrue(r.wantsAlbum(titanic, now: now), "asking doesn't take it")
        XCTAssertNil(r.take(for: [titanic], now: now), "no song is asked for")
        XCTAssertTrue(r.takeAlbum(titanic, now: now))
        XCTAssertFalse(r.takeAlbum(titanic, now: now), "taken once")
        r.requestAlbum(titanic, now: now)
        XCTAssertFalse(r.wantsAlbum(titanic, now: now.addingTimeInterval(11)))
        XCTAssertFalse(r.takeAlbum(titanic, now: now.addingTimeInterval(11)), "a stale request is dropped")
        XCTAssertNil(r.pendingAlbum)
    }

    func test_should_swallowUnknownYoinLinks_and_passOtherLinksToTheSystem() {
        var opened: [NoteMentionTarget] = []
        let m = mentions { opened.append($0) }
        XCTAssertTrue(m.take(URL(string: "yoin:album/applemusic:999")!, in: titanicLink))
        XCTAssertTrue(m.take(URL(string: "yoin://auth/spotify")!, in: titanicLink))
        XCTAssertFalse(m.take(URL(string: "https://example.com")!, in: titanicLink))
        XCTAssertTrue(opened.isEmpty)
    }

    func test_should_openNothing_when_theMentionIsUnresolved_or_tappedTwiceAtOnce() {
        var opened: [NoteMentionTarget] = []
        let spotify = "[@Charm](yoin:album/spotify:sp1?a=Clairo)"
        let m = mentions { opened.append($0) }
        XCTAssertTrue(m.take(URL(string: "yoin:album/spotify:sp1?a=Clairo")!, in: spotify))
        XCTAssertTrue(opened.isEmpty)
        let link = URL(string: "yoin:album/applemusic:1?a=Weyes%20Blood")!
        _ = m.take(link, in: titanicLink)
        _ = m.take(link, in: titanicLink)
        XCTAssertEqual(opened.count, 1, "two taps never stack two pages")
    }

    func test_should_resolveAnotherProvidersMentionByName_inTheBackground() async throws {
        var opened: [NoteMentionTarget] = []
        let rows = [NoteMentionName(id: "s-1", title: "Andromeda", artist: "Someone Else", album: nil),
                    NoteMentionName(id: "s-9", title: "ANDROMEDA", artist: "Weyes Blood", album: "Titanic Rising")]
        let m = mentions(provider: "subsonic", names: rows) { opened.append($0) }
        let mention = try XCTUnwrap(YoinNoteReader.shared.mentions(text: andromedaLink).first)
        XCTAssertNil(m.resolvedId(mention), "drawn plain until the match lands")
        for _ in 0..<100 where m.resolvedId(mention) == nil { try await Task.sleep(for: .milliseconds(20)) }
        XCTAssertEqual(m.resolvedId(mention), "s-9")
        XCTAssertNotNil(run(render(andromedaLink, m), "Andromeda")?.link)
        m.open(mention)
        XCTAssertEqual(opened, [.track(provider: "subsonic", id: "s-9", title: "Andromeda", artist: "Weyes Blood")])
        // An artist has nothing local to match: it stays plain.
        let artist = try XCTUnwrap(YoinNoteReader.shared.mentions(text: "[@Weyes Blood](yoin:artist/spotify:w)").first)
        XCTAssertNil(m.resolvedId(artist))
        XCTAssertNil(NoteMentions.match(artist, in: rows))
    }

    // MARK: Editing keeps the markup

    func test_should_keepAnAndroidNoteByteForByte_when_editedWithoutChangingItsWords() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent("notes.json")
        let written = "# 现场版要记得\n- [x] 开头那段哼唱 \(andromedaLink)\n**这一句** _轻_ \(titanicLink)\n- [ ] "
        let android = SongNote(id: "n1", provider: "applemusic", trackId: "t1", content: written, createdAt: 1, updatedAt: 2,
                               title: "Andromeda", artist: "Weyes Blood", positionMs: 1000)
        try JSONEncoder().encode([android]).write(to: url)
        let store = NotesStore(fileURL: url)
        let before = try Data(contentsOf: url)

        // The editor hands back the same words (a field may add or drop whitespace at the ends): nothing written.
        XCTAssertEqual(store.update(id: "n1", content: written.trimmingCharacters(in: .whitespaces) + "\n")?.content, written)
        XCTAssertEqual(store.notes.first?.updatedAt, 2)
        XCTAssertEqual(try Data(contentsOf: url), before)

        // A real edit: the words added, every mention and mark exactly as Android wrote them.
        let edited = written.replacingOccurrences(of: "- [ ] ", with: "- [ ] 第二段副歌的和声")
        XCTAssertEqual(store.update(id: "n1", content: edited)?.content, edited)
        let reloaded = try XCTUnwrap(NotesStore(fileURL: url).notes.first?.content)
        XCTAssertEqual(reloaded, edited)
        XCTAssertTrue(reloaded.contains(andromedaLink) && reloaded.contains(titanicLink))
        XCTAssertEqual(YoinNoteReader.shared.mentions(text: reloaded).map(\.link),
                       YoinNoteReader.shared.mentions(text: written).map(\.link))
    }

    func test_should_treatOnlyWhitespaceAroundTheWordsAsUnchanged() {
        XCTAssertTrue(NotesStore.sameWords("  a \(andromedaLink)\n", "a \(andromedaLink)"))
        XCTAssertTrue(NotesStore.sameWords("- [ ] ", "- [ ] "))
        XCTAssertFalse(NotesStore.sameWords("a [@Andromeda](yoin:track/applemusic:2)", "a \(andromedaLink)"))
        XCTAssertFalse(NotesStore.sameWords("a", nil))
    }
}
