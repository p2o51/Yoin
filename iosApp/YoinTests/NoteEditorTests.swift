import XCTest
import YoinShared
@testable import Yoin

/// The @ editor (notes phase 1c): the shared ranking with the system's pinyin, the editor's mention state machine
/// (typing, filtering, Return picking, leaving mention mode), the formatting row, and the text view's chips
/// round-tripping the stored markup byte for byte.
@MainActor
final class NoteEditorTests: XCTestCase {

    // MARK: Fixtures

    /// Rows as the account would offer them: the song playing first, then recent plays; a query searches.
    private struct StubSource: MentionRowSource {
        var known: MentionRows
        var search: [String: MentionRows] = [:]

        func rows(query: String, limit: Int) async -> MentionRows {
            // As the account's source: a query gets the recent plays back with the service's hits.
            query.isEmpty ? known : known.merge(search[query] ?? .empty)
        }
    }

    private static func track(_ title: String, artist: String? = nil, source: YoinMentionSource = .search,
                              id: String? = nil) -> MentionRow {
        MentionRow(kind: .track, provider: "subsonic", rawId: id ?? title, title: title, artist: artist, album: nil,
                   cover: nil, source: source)
    }

    private static func album(_ title: String, source: YoinMentionSource = .search) -> MentionRow {
        MentionRow(kind: .album, provider: "subsonic", rawId: "al-\(title)", title: title, artist: nil, album: nil,
                   cover: nil, source: source)
    }

    private let known = MentionRows(tracks: [track("Nothing", source: .current), track("Animal", source: .recent),
                                             track("纽约", artist: "周杰伦", source: .recent)])

    private func editor(_ text: String = "", search: [String: MentionRows] = [:]) -> NoteEditorModel {
        let m = NoteEditorModel(text: text, source: StubSource(known: known, search: search), debounce: .zero)
        m.focusChanged(true)
        return m
    }

    /// Types [s] at the caret the way the text view reports it: each character an edit the model may take.
    private func type(_ s: String, into m: NoteEditorModel) {
        for c in s {
            let at = m.selection
            if m.intercept(range: at, replacement: String(c)) == nil {
                let next = (m.text as NSString).replacingCharacters(in: at, with: String(c))
                m.textChanged(next, selection: NSRange(location: at.location + String(c).utf16.count, length: 0), composing: nil)
            }
        }
    }

    private func settle() async {
        for _ in 0..<20 { await Task.yield() }
        try? await Task.sleep(for: .milliseconds(20))
        for _ in 0..<20 { await Task.yield() }
    }

    // MARK: Ranking and pinyin

    func test_should_spellChineseNamesWithTheSystemsPinyin_andNothingElse() {
        let p = PinyinRomanizer.shared
        XCTAssertEqual(p.romanize(text: "纽约"), "niu yue")
        XCTAssertEqual(p.romanize(text: "晴天"), "qing tian")
        XCTAssertNil(p.romanize(text: "New York"))
    }

    func test_should_rankPrefixThenInitialsThenSubstringThenArtist_withPinyin() {
        let rows = MentionRows(tracks: [Self.track("Holy York", artist: "Ny Band"), Self.track("Pony"),
                                        Self.track("Night Yard"), Self.track("Nyx"), Self.track("纽约")])
        let ranked = MentionRanking.rank("ny", rows, romanizer: PinyinRomanizer.shared)
        XCTAssertEqual(ranked.tracks.map(\.title), ["Nyx", "Night Yard", "纽约", "Pony", "Holy York"])
        // a pinyin keyboard still composing "niu'yue"
        XCTAssertEqual(MentionRanking.rank("niu'yue", rows, romanizer: PinyinRomanizer.shared).tracks.first?.title, "纽约")
        let tier = YoinMentionRanking.shared.tier(query: "zjl", title: "晴天", artist: "周杰伦", romanizer: PinyinRomanizer.shared)
        XCTAssertEqual(tier, .artist)
    }

    func test_should_listTheBestMatchsKindFirst() {
        let rows = MentionRows(tracks: [Self.track("Hello Yorktown")], albums: [Self.album("New York")])
        let ranked = MentionRanking.rank("new", rows, romanizer: PinyinRomanizer.shared)
        XCTAssertEqual(ranked.all.first?.title, "New York")
        XCTAssertEqual(ranked.order.first, .album)
    }

    // MARK: The mention state machine

    func test_should_openThePopupOnAt_offeringWhatIsPlayingAndRecentPlays() async {
        let m = editor("Sounds like ")
        XCTAssertFalse(m.popupOpen)
        type("@", into: m)
        XCTAssertTrue(m.popupOpen)
        XCTAssertEqual(m.trigger?.query, "")
        await settle()
        XCTAssertEqual(m.rows.all.map(\.title), ["Nothing", "Animal", "纽约"])
    }

    func test_should_filterAtEveryLetter_thenRankTheSearchIn() async {
        let m = editor(search: ["n": MentionRows(tracks: [Self.track("New York"), Self.track("Elsewhere")])])
        type("@", into: m)
        await settle()
        type("n", into: m)
        // at once, before the search answers: the rows already offered that match
        XCTAssertEqual(m.rows.all.map(\.title), ["Nothing", "纽约", "Animal"])
        await settle()
        // the search's rows ranked in (within a tier: playing, recent, then the service's); its own "Elsewhere" stays, last
        XCTAssertEqual(m.rows.all.map(\.title), ["Nothing", "纽约", "New York", "Animal", "Elsewhere"])
        XCTAssertEqual(m.highlighted, 0)
    }

    func test_should_pickTheTopRowOnReturn_asAMentionLinkAndASpace() async {
        let m = editor("Sounds like ")
        type("@no", into: m)
        await settle()
        XCTAssertEqual(m.rows.all.first?.title, "Nothing")
        type("\n", into: m)
        let link = "[@Nothing](yoin:track/subsonic:Nothing)"
        XCTAssertEqual(m.text, "Sounds like \(link) ")
        XCTAssertEqual(m.selection.location, ("Sounds like \(link) " as NSString).length)
        XCTAssertFalse(m.popupOpen)
    }

    func test_should_pickTheHighlightedRow_whenMoved() async {
        let m = editor()
        type("@", into: m)
        await settle()
        m.moveHighlight(1)
        type("\n", into: m)
        XCTAssertEqual(m.text, "[@Animal](yoin:track/subsonic:Animal) ")
    }

    func test_should_endMentionMode_onASpaceADismissalOrFocusLoss() async {
        let m = editor()
        type("@ab", into: m)
        XCTAssertTrue(m.popupOpen)
        type(" ", into: m)
        XCTAssertFalse(m.popupOpen, "a committed space ends it")
        type("@x", into: m)
        XCTAssertTrue(m.popupOpen)
        m.dismissPopup()
        XCTAssertFalse(m.popupOpen)
        type("y", into: m)
        XCTAssertFalse(m.popupOpen, "the dismissed @ stays shut while the caret is on it")
        type(" @", into: m)
        XCTAssertTrue(m.popupOpen, "another @ opens it again")
        m.focusChanged(false)
        XCTAssertFalse(m.popupOpen)
        // Return with the popup shut is a newline
        m.focusChanged(true)
        m.dismissPopup()
        type("\n", into: m)
        XCTAssertTrue(m.text.hasSuffix("\n"))
    }

    func test_should_keepAComposingPinyinQueryOpen_acrossTheKeyboardsSpaces() {
        let m = editor()
        type("@", into: m)
        m.textChanged("@niu yue", selection: NSRange(location: 8, length: 0), composing: NSRange(location: 1, length: 7))
        XCTAssertEqual(m.trigger?.query, "niu yue")
        m.textChanged("@niu yue", selection: NSRange(location: 8, length: 0), composing: nil)
        XCTAssertNil(m.trigger)
    }

    func test_should_takeAMentionWhole_onBackspace() {
        let link = "[@Nothing](yoin:track/subsonic:Nothing)"
        let m = editor("a \(link) b")
        // the text view's backspace on the chip: in stored offsets, the whole link
        let r = NSRange(location: 2, length: (link as NSString).length)
        XCTAssertNil(m.intercept(range: r, replacement: ""), "a whole link removed stands as typed")
        // part of it (another editor's selection) takes the rest along
        let fix = m.intercept(range: NSRange(location: 5, length: 3), replacement: "")
        XCTAssertEqual(fix?.text, "a  b")
    }

    func test_should_applyTheFormattingRow_andReadItsPressedState() {
        let m = editor("words")
        m.textChanged("words", selection: NSRange(location: 0, length: 5), composing: nil)
        m.apply(.bold)
        XCTAssertEqual(m.text, "**words**")
        XCTAssertTrue(m.activeTools.contains(.bold))
        m.apply(.checklist)
        XCTAssertEqual(m.text, "- [ ] **words**")
        XCTAssertTrue(m.activeTools.contains(.checklist))
        m.toggleChecklist(line: 0)
        XCTAssertEqual(m.text, "- [x] **words**")
        m.selectionChanged(NSRange(location: (m.text as NSString).length, length: 0), composing: nil)
        m.apply(.mention)
        XCTAssertTrue(m.text.hasSuffix("@"))
        XCTAssertTrue(m.popupOpen)
        // Enter on a to-do continues the list
        let n = editor("- [ ] one")
        type("\n", into: n)
        XCTAssertEqual(n.text, "- [ ] one\n- [ ] ")
    }

    func test_should_tellTheHostEveryNewText() {
        let m = editor()
        var told: [String] = []
        m.onChange = { told.append($0) }
        type("hi", into: m)
        m.apply(.heading)
        XCTAssertEqual(told, ["h", "hi", "# hi"])
    }

    // MARK: Chips round-trip

    func test_should_showEachMentionAsOneChip_andGiveTheStoredTextBackByteForByte() {
        let link = "[@Andromeda \\[Live\\]](yoin:track/applemusic:1445?a=Weyes%20Blood&al=Titanic%20Rising)"
        let stored = "# 现场\n- [x] 听 \(link)\(link) **好** _轻_ 😀"
        let style = NoteEditorStyle(size: 16, textColor: .black, mentionColor: .red)
        let (display, map) = NoteEditorDisplay.build(stored, style: style)
        XCTAssertEqual(display.string.filter { $0 == "\u{FFFC}" }.count, 2)
        XCTAssertEqual(NoteEditorDisplay.stored(from: display), stored)
        XCTAssertEqual(NoteEditorDisplay.map(of: display), map)
        // offsets both ways: before a chip is the link's start, after it the link's end
        let start = (stored as NSString).range(of: link).location
        let chip = map.display(start)
        XCTAssertEqual(map.stored(chip), start)
        XCTAssertEqual(map.stored(chip + 1), start + (link as NSString).length)
        XCTAssertEqual(map.display(start + 3), chip + 1, "inside a link reads as after its chip")
        XCTAssertEqual(map.stored(display.length), (stored as NSString).length)
        // a typed character beside a chip that picked up its attribute is itself
        let typed = NSMutableAttributedString(attributedString: display)
        typed.insert(NSAttributedString(string: "x", attributes: [.yoinNoteMentionLink: link]), at: chip + 1)
        XCTAssertEqual(NoteEditorDisplay.stored(from: typed),
                       (stored as NSString).replacingCharacters(in: NSRange(location: start + (link as NSString).length, length: 0), with: "x"))
    }

    func test_should_leaveTextWithoutMarkupAsItIs() {
        let style = NoteEditorStyle(size: 16, textColor: .black, mentionColor: .red)
        for text in ["", "plain", "snake_case_name", "2*3*4", "[@Title](yoin:movie/spotify:1)", "line\n\nline"] {
            let (display, map) = NoteEditorDisplay.build(text, style: style)
            XCTAssertEqual(display.string, text)
            XCTAssertTrue(map.spans.isEmpty)
            XCTAssertEqual(NoteEditorDisplay.stored(from: display), text)
        }
    }
    // MARK: The scrapbook track sheet's Notes page (Android #310)

    /// The Notes page writes with the @ editor: a mention picked there is saved as its link, comes back from disk as
    /// the same text, reopens as a chip, and survives an edit around it.
    func test_should_saveAndRestoreMentions_when_writingInTheTrackSheetsNotes() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let url = dir.appendingPathComponent("notes.json")
        let store = NotesStore(fileURL: url)
        let album = MockLibrary.allAlbums[0]
        let track = album.tracks[0]

        // Written: "@no" + Return picks the top row; the sheet saves the editor's stored text.
        let m = editor("Sounds like ")
        type("@no", into: m)
        await settle()
        type("\n", into: m)
        type("here", into: m)
        let link = "[@Nothing](yoin:track/subsonic:Nothing)"
        XCTAssertEqual(m.text, "Sounds like \(link) here")
        XCTAssertEqual(ScrapNoteSave.draft(m.text + "\n", track: track, album: album, positionMs: 36_000, store: store), .saved)

        // Restored: read back from disk byte for byte, then shown with the mention as one chip.
        let saved = try XCTUnwrap(NotesStore(fileURL: url).notes(provider: track.id.provider, trackId: track.id.rawId).first)
        XCTAssertEqual(saved.content, "Sounds like \(link) here")
        XCTAssertEqual(saved.positionMs, 36_000)
        let reopened = NoteEditorModel(text: saved.content, source: StubSource(known: known), debounce: .zero)
        let style = NoteEditorStyle(size: 15, textColor: .black, mentionColor: .red)
        let (display, _) = NoteEditorDisplay.build(reopened.text, style: style)
        XCTAssertEqual(display.string.filter { $0 == "\u{FFFC}" }.count, 1, "the mention reopens as a chip")
        XCTAssertEqual(NoteEditorDisplay.stored(from: display), saved.content)

        // Edited around the chip: the link stays whole; unchanged words write nothing; emptied asks to delete.
        reopened.focusChanged(true)
        type("!", into: reopened)
        XCTAssertEqual(ScrapNoteSave.edit(saved, reopened.text, store: store), .saved)
        let edited = try XCTUnwrap(NotesStore(fileURL: url).notes(provider: track.id.provider, trackId: track.id.rawId).first)
        XCTAssertEqual(edited.content, "Sounds like \(link) here!")
        XCTAssertEqual(ScrapNoteSave.edit(edited, edited.content, store: store), .unchanged)
        XCTAssertEqual(ScrapNoteSave.edit(edited, "  \n", store: store), .emptied)
        XCTAssertEqual(ScrapNoteSave.draft("   ", track: track, album: album, positionMs: nil, store: store), .unchanged)
        XCTAssertEqual(store.notes(provider: track.id.provider, trackId: track.id.rawId).count, 1)
    }
}
