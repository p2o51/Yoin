import SwiftUI
import XCTest
import YoinShared
@testable import Yoin

/// Share cards on iOS (Android PR #335, shared rules PR #349): the golden vectors
/// (shared/src/commonTest/resources/golden/share-cards.json) run through what iOS adds — its strings, its About
/// / note / lyric-pick adapters over Now Playing's types, the layout memory in UserDefaults, the colours on the
/// cover's scheme, the Story zoom layout — and every layout exported as an opaque PNG at the shared sizes.
@MainActor
final class ShareCardsTests: XCTestCase {
    // MARK: Golden file

    private static let repo = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()

    private static let golden: [String: Any] = {
        let url = repo.appendingPathComponent("shared/src/commonTest/resources/golden/share-cards.json")
        // The file's description and its About section share the key "about" (JSONSerialization keeps the
        // first): the description is renamed before reading.
        let raw = try! String(contentsOf: url, encoding: .utf8)
            .replacingOccurrences(of: "\"about\": \"Golden vectors", with: "\"description\": \"Golden vectors")
        return try! JSONSerialization.jsonObject(with: Data(raw.utf8)) as! [String: Any]
    }()

    private func vectors(_ section: String) -> [[String: Any]] { Self.golden[section] as! [[String: Any]] }

    private static func bundle(_ language: String) -> Bundle {
        Bundle(path: Bundle.main.path(forResource: language, ofType: "lproj")!)!
    }

    private static func opt<T>(_ o: Any?) -> T? { o is NSNull ? nil : o as? T }

    private static func line(_ o: Any) -> ShareLyricLine {
        let l = o as! [String: Any]
        return ShareContentBuilder.line(text: l["text"] as! String, startMs: opt(l["startMs"]) as Int64?,
                                        translation: opt(l["translation"]))
    }

    private static func lyrics(_ o: Any?) -> ShareLyrics? {
        guard let l = opt(o) as [String: Any]? else { return nil }
        return ShareLyrics(lines: (l["lines"] as! [Any]).map(line),
                           passageStarts: (l["passageStarts"] as! [Int]).map { KotlinInt(value: Int32($0)) },
                           showTranslation: l["showTranslation"] as! Bool)
    }

    private static func note(_ o: Any?) -> ShareNote? {
        guard let n = opt(o) as [String: Any]? else { return nil }
        return ShareNote(noteId: n["noteId"] as! String, content: n["content"] as! String,
                         positionMs: (opt(n["positionMs"]) as Int64?).map { KotlinLong(value: $0) },
                         createdAt: n["createdAt"] as! Int64)
    }

    private static func about(_ o: Any?) -> ShareAbout? {
        guard let a = opt(o) as [String: Any]? else { return nil }
        let credits = (a["credits"] as! [[String: Any]]).map {
            ShareCredit(label: $0["label"] as! String, value: $0["value"] as! String, ai: $0["ai"] as! Bool,
                        fact: $0["fact"] as! Bool)
        }
        return ShareAbout(credits: credits, heading: a["heading"] as! String, paragraph: opt(a["paragraph"]))
    }

    private static func content(_ o: Any) -> ShareCardContent {
        let c = o as! [String: Any]
        let s = c["song"] as! [String: Any]
        let song = ShareSong(songId: s["songId"] as! String, title: s["title"] as! String, artist: s["artist"] as! String,
                             coverArtUrl: opt(s["coverArtUrl"]), albumName: s["albumName"] as! String,
                             durationMs: s["durationMs"] as! Int64)
        return ShareCardContent(song: song, lyrics: lyrics(c["lyrics"]), about: about(c["about"]), note: note(c["note"]))
    }

    private static func describe(_ about: ShareAbout?) -> String? {
        guard let about else { return nil }
        let credits = about.credits.map { "\($0.label)=\($0.value)\($0.ai ? "/ai" : "")\($0.fact ? "/fact" : "")" }
        return credits.joined(separator: "|") + " #" + about.heading + " #" + (about.paragraph ?? "nil")
    }

    private static func describe(json o: Any?) -> String? {
        guard let a = opt(o) as [String: Any]? else { return nil }
        let credits = (a["credits"] as! [[String: Any]]).map {
            "\($0["label"]!)=\($0["value"]!)\(($0["ai"] as! Bool) ? "/ai" : "")\(($0["fact"] as! Bool) ? "/fact" : "")"
        }
        return credits.joined(separator: "|") + " #" + (a["heading"] as! String) + " #" + ((opt(a["paragraph"]) as String?) ?? "nil")
    }

    private func defaults() -> UserDefaults {
        let name = "share-cards-\(UUID().uuidString)"
        let d = UserDefaults(suiteName: name)!
        addTeardownBlock { d.removePersistentDomain(forName: name) }
        return d
    }

    // MARK: Strings

    /// Localizable's About labels are the golden ones (作词 / Lyricist …), word for word.
    func test_should_matchGoldenLabels_when_readingLocalizable() {
        let sets = Self.golden["labels"] as! [String: [String: String]]
        for (language, bundle) in [("zh", "zh-Hans"), ("en", "en")] {
            let l = ShareStrings.aboutLabels(bundle: Self.bundle(bundle))
            let g = sets[language]!
            XCTAssertEqual(l.lyricist, g["lyricist"], language)
            XCTAssertEqual(l.composer, g["composer"], language)
            XCTAssertEqual(l.arranger, g["arranger"], language)
            XCTAssertEqual(l.producer, g["producer"], language)
            XCTAssertEqual(l.creationTime, g["creationTime"], language)
            XCTAssertEqual(l.creationLocation, g["creationLocation"], language)
            XCTAssertEqual(l.year, g["year"], language)
            XCTAssertEqual(l.review, g["review"], language)
        }
    }

    /// Every word the sheet and the images use is Android's (strings_nowplaying.xml, en and zh-CN), read from the
    /// Android resources themselves.
    func test_should_copyAndroidsStrings_when_localizingTheSheet() throws {
        func android(_ folder: String) throws -> [String: String] {
            let url = Self.repo.appendingPathComponent("app/src/main/res/\(folder)/strings_nowplaying.xml")
            let xml = try String(contentsOf: url, encoding: .utf8)
            var out: [String: String] = [:]
            let re = try NSRegularExpression(pattern: #"<string name="([^"]+)"[^>]*>([^<]*)</string>"#)
            for m in re.matches(in: xml, range: NSRange(xml.startIndex..., in: xml)) {
                let name = String(xml[Range(m.range(at: 1), in: xml)!])
                let value = String(xml[Range(m.range(at: 2), in: xml)!])
                out[name] = value.replacingOccurrences(of: "\\'", with: "'").replacingOccurrences(of: "%1$s", with: "%@")
            }
            return out
        }
        let en = try android("values"), zh = try android("values-zh-rCN")
        let keys: [String: String] = [
            "np_share_sheet_title": "Share as image", "np_share_layout": "Layout",
            "np_share_layout_classic": "Classic", "np_share_layout_poster": "Poster", "np_share_layout_player": "Player",
            "np_share_layout_vertical": "Vertical", "np_share_layout_receipt": "Receipt", "np_share_layout_quote": "Quote",
            "np_share_layout_liner": "Liner notes", "np_share_layout_big": "Big type", "np_share_layout_clipping": "Clipping",
            "np_share_layout_timeline": "Timeline", "np_tab_lyrics": "Lyrics", "np_tab_about": "About", "np_tab_note": "Note",
            "np_share_format_card": "Card", "np_share_format_story": "Story", "np_share_save": "Save",
            "np_share_saving": "Saving…", "np_share_saved": "Saved", "np_share_try_again": "Try again",
            "np_share_action": "Share", "np_share_failed": "Couldn't share the image",
            "np_cd_tone_soft": "Soft colours", "np_cd_tone_deep": "Deep colours", "np_cd_tone_vivid": "Vivid colours",
            "np_cd_tone_mist": "Mist colours", "np_cd_tone_paper": "Paper colours",
            "np_share_lyrics_gist": "Gist: %@", "np_share_ai_sign": "By %@", "np_share_note_written": "Written %@",
            "np_about_review": "About",
        ]
        let zhBundle = Self.bundle("zh-Hans"), enBundle = Self.bundle("en")
        for (name, key) in keys {
            XCTAssertEqual(enBundle.localizedString(forKey: key, value: nil, table: nil), en[name], name)
            XCTAssertEqual(zhBundle.localizedString(forKey: key, value: nil, table: nil), zh[name], name)
        }
        XCTAssertEqual(en["np_share_brand"], ShareStrings.brand)
        // The layout and tab names under the thumbnails and on the tabs come from those keys.
        XCTAssertEqual(ShareStrings.layout(.aboutliner, bundle: zhBundle), "内页")
        XCTAssertEqual(ShareStrings.tab(.note, bundle: zhBundle), "笔记")
        XCTAssertEqual(ShareStrings.gist(["A", "B"], bundle: zhBundle), "歌词大意：A；B")
        XCTAssertEqual(ShareStrings.aiSign("Gemini", bundle: enBundle), "By Gemini")
    }

    /// About's sign through iOS's Localizable template: the golden `aiSign` vectors (shared PR #350; skipped while
    /// the golden file has none) and both languages' own strings.
    func test_should_signAboutAsTheGoldenSays() {
        for v in (Self.golden["aiSign"] as? [[String: Any]]) ?? [] {
            let template = (v["template"] as! String).replacingOccurrences(of: "%1$s", with: "%@")
            XCTAssertEqual(ShareStrings.aiSign(template: template, name: v["provider"] as! String), v["sign"] as? String,
                           v["name"] as! String)
        }
        XCTAssertEqual(ShareStrings.aiSign("千问", bundle: Self.bundle("zh-Hans")), "由千问整理")
        XCTAssertEqual(ShareStrings.aiSign("Gemini", bundle: Self.bundle("zh-Hans")), "由 Gemini 整理")
        XCTAssertEqual(ShareStrings.aiSign("Qwen", bundle: Self.bundle("en")), "By Qwen")
    }

    /// The note's date is the long date in the app's language, as Android's DateFormat.LONG writes it.
    func test_should_writeTheLongDate_when_signingANote() {
        let utc = TimeZone(identifier: "UTC")!
        XCTAssertEqual(ShareStrings.noteDate(1_784_390_400_000, locale: Locale(identifier: "zh-Hans"), timeZone: utc),
                       "2026年7月18日")
        XCTAssertEqual(ShareStrings.noteDate(1_784_390_400_000, locale: Locale(identifier: "en"), timeZone: utc),
                       "July 18, 2026")
    }

    // MARK: Golden vectors through iOS's adapters

    /// About from SongAboutStore's rows, SongCredits and the server's SpineSourceFacts (ShareContentBuilder.about),
    /// with iOS's labels: every golden About.
    func test_should_buildTheGoldenAbout_from_nowPlayingsRows() {
        let all = vectors("about")
        XCTAssertEqual(all.count, 22)
        for v in all {
            let name = v["name"] as! String
            let entries = (v["entries"] as! [[String: Any]]).map { e in
                SongAboutStore.Entry(titleKey: "t", artistKey: "a", albumKey: "al", titleDisplay: "T", artistDisplay: "A",
                                     albumDisplay: "AL", kind: e["kind"] as! String, entryKey: e["entryKey"] as! String,
                                     promptText: Self.opt(e["promptText"]), titleText: Self.opt(e["titleText"]),
                                     answerText: e["answerText"] as! String, createdAt: 0, updatedAt: 0, language: "en")
            }
            let s = v["sources"] as! [String: Any]
            let credits = SongCredits(lyricist: Self.opt(s["lyricist"]), composer: Self.opt(s["composer"]),
                                      arranger: Self.opt(s["arranger"]), producer: Self.opt(s["producer"]))
            let spine = SpineSourceFacts(year: Self.opt(s["year"]), composer: Self.opt(s["serverComposer"]))
            let labels = ShareStrings.aboutLabels(bundle: Self.bundle((v["labels"] as! String) == "zh" ? "zh-Hans" : "en"))
            let focusKind: String? = Self.opt(v["focusKind"]), focusKey: String? = Self.opt(v["focusKey"])
            let about = ShareContentBuilder.about(entries: entries, credits: credits, spine: spine,
                                                  focus: focusKind.map { ($0, focusKey ?? "") }, labels: labels)
            XCTAssertEqual(Self.describe(about), Self.describe(json: v["about"]), name)
            XCTAssertEqual(about?.aiContent ?? false, v["aiContent"] as! Bool, name)
            let content = ShareCardContent(song: ShareSong(songId: "s", title: "T", artist: "A", coverArtUrl: nil,
                                                           albumName: "", durationMs: 0),
                                           lyrics: nil, about: about, note: nil)
            XCTAssertEqual(ShareRender(content: content, aiName: "AI").tabs.contains(.about), v["hasTab"] as! Bool, name)
        }
    }

    /// The note picked from NotesStore's SongNotes (ShareContentBuilder.note): every golden note vector.
    func test_should_pickTheGoldenNote_from_songNotes() {
        for v in vectors("note") {
            let name = v["name"] as! String
            let notes = (v["notes"] as! [[String: Any]]).map { n in
                SongNote(id: n["noteId"] as! String, provider: "p", trackId: "t", content: n["content"] as! String,
                         createdAt: n["createdAt"] as! Int64, updatedAt: 0, title: "T", artist: "A",
                         positionMs: Self.opt(n["positionMs"]))
            }
            let picked = YoinShareCards.shared.notePick(notes: notes.map(ShareContentBuilder.note),
                                                        noteId: Self.opt(v["noteId"]),
                                                        positionMs: v["positionMs"] as! Int64)
            let expected = Self.note(v["note"])
            XCTAssertEqual(picked?.noteId, expected?.noteId, name)
            XCTAssertEqual(picked?.content, expected?.content, name)
            XCTAssertEqual(picked?.positionMs?.int64Value, expected?.positionMs?.int64Value, name)
        }
    }

    /// The select-mode pick (ShareContentBuilder.pick) keeps the golden passages; Now Playing's current line too.
    func test_should_pickLinesAndPassages_as_theGoldenVectorsSay() {
        for v in vectors("passageStarts") {
            let count = v["lineCount"] as! Int
            let lines = (0..<count).map { ShareContentBuilder.line(text: "l\($0)", startMs: nil, translation: nil) }
            let pick = ShareContentBuilder.pick(lines: lines, selected: v["selected"] as! [Int], showTranslation: false)
            let starts = v["starts"] as! [Int]
            if (v["selected"] as! [Int]).filter({ $0 >= 0 && $0 < count }).isEmpty {
                XCTAssertNil(pick, v["name"] as! String)
            } else {
                XCTAssertEqual(pick?.passageStarts.map(\.intValue), starts, v["name"] as! String)
            }
        }
        for v in vectors("currentLine") {
            let lines = (v["lines"] as! [Any]).map(Self.line)
            let pick = YoinShareCards.shared.currentLine(lyrics: lines, positionMs: v["positionMs"] as! Int64,
                                                         showTranslation: v["showTranslation"] as! Bool)
            let expected = Self.lyrics(v["pick"])
            XCTAssertEqual(pick?.lines.map(\.text), expected?.lines.map(\.text), v["name"] as! String)
            XCTAssertEqual(pick?.lines.map { $0.startMs?.int64Value }, expected?.lines.map { $0.startMs?.int64Value })
            XCTAssertEqual(pick?.showTranslation, expected?.showTranslation)
        }
    }

    /// The layout memory in UserDefaults: what Android's prefs hold reads back as the golden layout.
    func test_should_rememberLayouts_in_userDefaults() {
        for v in vectors("layoutStore") {
            let d = defaults()
            let tab = ShareTab.entries.first { $0.name == v["tab"] as? String }!
            d.set(Self.opt(v["value"]) as String?, forKey: ShareLayoutStore.prefix + tab.storeKey)
            let read = ShareLayoutStore(defaults: d).layout(for: tab)
            XCTAssertEqual(read?.name, Self.opt(v["layout"]) as String?, v["name"] as! String)
        }
        let d = defaults()
        let store = ShareLayoutStore(defaults: d)
        store.set(.notetimeline, for: .note)
        store.set(.aboutquote, for: .lyrics)  // another tab's layout is never filed
        XCTAssertEqual(d.string(forKey: "yoin_share_layouts.Note"), "NoteTimeline")
        XCTAssertNil(d.string(forKey: "yoin_share_layouts.Lyrics"))
        XCTAssertEqual(store.layout(for: .note), .notetimeline)
    }

    /// Every content vector: the tabs, the layouts and the layout each tab opens on (through the store).
    func test_should_openTheGoldenTabsAndLayouts_through_theStore() {
        for v in vectors("content") {
            let name = v["name"] as! String
            let render = ShareRender(content: Self.content(v["content"]!), aiName: "AI")
            XCTAssertEqual(render.tabs.map(\.name), v["tabs"] as? [String], name)
            let d = defaults()
            let remembered = v["remembered"] as! [String: Any]
            for (tab, value) in remembered {
                if let value = value as? String { d.set(value, forKey: ShareLayoutStore.prefix + tab) }
            }
            let store = ShareLayoutStore(defaults: d)
            let layouts = v["layouts"] as! [String: [String]]
            let opens = v["opens"] as! [String: Any]
            for tab in ShareTab.entries {
                XCTAssertEqual(render.layouts(tab).map(\.name), layouts[tab.name] ?? [], "\(name) \(tab.name)")
                let open = YoinShareCards.shared.resolveLayout(content: render.content, tab: tab,
                                                               remembered: store.layout(for: tab))
                XCTAssertEqual(open?.name, Self.opt(opens[tab.name]) as String?, "\(name) \(tab.name)")
            }
        }
    }

    /// The tones resolve to the golden scheme roles on the cover's scheme (Paper re-toned through the shared HCT).
    func test_should_resolveTheGoldenTones_on_theCoverScheme() {
        for dark in [false, true] {
            let s = CoverScheme(seed: Color(red: 0.4, green: 0.31, blue: 0.64), dark: dark)
            let roles: [String: Color] = [
                "Primary": s.primary, "OnPrimary": s.onPrimary, "PrimaryContainer": s.primaryContainer,
                "OnPrimaryContainer": s.onPrimaryContainer, "Tertiary": s.tertiary, "OnTertiary": s.onTertiary,
                "TertiaryContainer": s.tertiaryContainer, "OnTertiaryContainer": s.onTertiaryContainer, "White": .white,
            ]
            for v in vectors("tones") {
                let tone = ShareTone.entries.first { $0.name == v["tone"] as? String }!
                let role = ShareRole.entries.first { $0.name == v["role"] as? String }!
                let colors = tone.colors(s, role: role)
                for (slot, got) in [("card", colors.card), ("content", colors.content), ("backdrop", colors.backdrop)] {
                    let ink = v[slot] as! [String: Any]
                    var want = CoverScheme.argb(of: roles[ink["source"] as! String]!)
                    if let t = Self.opt(ink["tone"]) as Int? {
                        want = Int(UInt32(bitPattern: YoinShareCards.shared.retone(argb: Int32(truncatingIfNeeded: want),
                                                                                    tone: Int32(t))))
                    }
                    XCTAssertEqual(CoverScheme.argb(of: got), want, "\(v["name"]!) \(slot) dark=\(dark)")
                }
            }
        }
    }

    /// iOS's colour round trip (argb → Color → argb → re-tone → Color) gives the golden re-toned colours.
    func test_should_retoneAsTheGoldenSays_through_iosColours() {
        for v in vectors("retone") {
            func argb(_ hex: String) -> Int { Int(UInt32(hex.dropFirst(), radix: 16)!) }
            let ink = ShareInk(source: .primary, tone: KotlinInt(value: Int32(v["tone"] as! Int)))
            let scheme = CoverScheme(seed: CoverScheme.color(argb(v["argb"] as! String)), dark: false)
            // The scheme's primary is only a carrier here: resolve re-tones whatever colour the role holds.
            let base = CoverScheme.argb(of: scheme.primary)
            let viaIos = CoverScheme.argb(of: ShareTone.resolve(ink, scheme))
            let want = Int(UInt32(bitPattern: YoinShareCards.shared.retone(argb: Int32(truncatingIfNeeded: base),
                                                                            tone: Int32(v["tone"] as! Int))))
            XCTAssertEqual(viaIos, want, v["name"] as! String)
            let direct = Int(UInt32(bitPattern: YoinShareCards.shared.retone(
                argb: Int32(truncatingIfNeeded: CoverScheme.argb(of: CoverScheme.color(argb(v["argb"] as! String)))),
                tone: Int32(v["tone"] as! Int))))
            XCTAssertEqual(direct, argb(v["retoned"] as! String), v["name"] as! String)
        }
    }

    /// The Story zoom layout settles where the golden search does (content that fits up to a zoom).
    func test_should_settleOnTheGoldenZoom_in_theStoryLayout() {
        for v in vectors("storyZoom") {
            let fitsUpTo = CGFloat(v["fitsUpTo"] as! Double)
            let frame = CGSize(width: 304, height: 1_000_000)
            // Content as tall as the frame over its last fitting zoom, whatever the width.
            let result = StoryZoomLayout.resolve(frame: frame, maxZoom: CGFloat(v["maxZoom"] as! Double),
                                                 fillHeight: false) { _ in
                // Never fits: taller than the frame at any zoom the search may try.
                fitsUpTo > 0 ? (frame.height / fitsUpTo).rounded(.down) : frame.height * 100
            }
            XCTAssertEqual(result.zoom, CGFloat(v["zoom"] as! Double), accuracy: 1e-5, v["name"] as! String)
            XCTAssertEqual(result.size.width, frame.width / result.zoom, accuracy: 1e-3)
        }
        // A poster fills the frame's height at its zoom.
        let poster = StoryZoomLayout.resolve(frame: CGSize(width: 304, height: 496), maxZoom: 1, fillHeight: true) { _ in 100 }
        XCTAssertEqual(poster.zoom, 1)
        XCTAssertEqual(poster.size.height, 496)
    }

    /// The saved file's name.
    func test_should_nameTheFileAsAndroidDoes() throws {
        for v in vectors("fileName") {
            let url = ShareExport.file(Self.image(.lyricsclassic, .card), title: (Self.opt(v["title"]) as String?) ?? "",
                                       now: Date(timeIntervalSince1970: TimeInterval(v["nowMs"] as! Int64) / 1000))
            XCTAssertEqual(url?.lastPathComponent, v["fileName"] as? String, v["name"] as! String)
        }
    }

    // MARK: Export

    private static func render(translations: Bool = true) -> ShareRender {
        let labels = ShareStrings.aboutLabels(bundle: bundle("zh-Hans"))
        let song = ShareSong(songId: "s", title: "他夏了夏天", artist: "苏打绿", coverArtUrl: nil, albumName: "夏/狂热",
                             durationMs: 305_000)
        let lyrics = ShareLyrics(lines: [
            ShareContentBuilder.line(text: "帶著笑容的睡意化成了彩虹", startMs: 226_800,
                                     translation: translations ? "A smiling drowsiness turned into a rainbow" : nil),
            ShareContentBuilder.line(text: "在他夢中一口氣走上了星空", startMs: 230_700,
                                     translation: translations ? "In his dream he climbed to the stars" : nil),
        ], passageStarts: [], showTranslation: translations)
        let about = ShareAbout(credits: [
            ShareCredit(label: labels.lyricist, value: "吴青峰", ai: false, fact: false),
            ShareCredit(label: labels.composer, value: "吴青峰", ai: false, fact: false),
            ShareCredit(label: labels.producer, value: "林暐哲", ai: true, fact: false),
            ShareCredit(label: labels.year, value: "2009", ai: false, fact: true),
        ], heading: labels.review, paragraph: "一段占位的背景介绍：收录在哪张专辑、写的是什么、和谁有关。两三句话，由 AI 整理。")
        let note = ShareNote(noteId: "n1", content: "最后一段鼓整个打开，**一口气走上了星空**。\n- 每次下班走出地铁\n- [x] 刚好听到这里",
                             positionMs: KotlinLong(value: 226_000), createdAt: 1_784_390_400_000)
        let cover = ImageRenderer(content: LinearGradient(colors: [.purple, .orange], startPoint: .top, endPoint: .bottom)
            .frame(width: 96, height: 96)).uiImage
        return ShareRender(content: ShareCardContent(song: song, lyrics: lyrics, about: about, note: note), aiName: "Gemini",
                           cover: cover)
    }

    private static func image(_ layout: ShareLayout, _ format: ShareFormat, tone: ShareTone = .soft,
                              dark: Bool = false, render: ShareRender = render()) -> ShareImage {
        ShareImage(render: render, layout: layout, format: format, tone: tone,
                   scheme: CoverScheme(seed: Color(red: 0.36, green: 0.42, blue: 0.75), dark: dark))
    }

    /// The demo content offers every layout of every tab.
    func test_should_offerEveryLayout_for_theExportContent() {
        let r = Self.render()
        XCTAssertEqual(ShareTab.entries.flatMap { r.layouts($0) }.count, 10)
    }

    /// Every layout × Card / Story exports at the shared size, opaque, its colour to every edge: drawn on a
    /// see-through context, not one pixel of the border is see-through.
    func test_should_exportOpaqueEdges_for_everyLayoutAndFormat() throws {
        for dark in [false, true] {
            for layout in ShareLayout.entries {
                for format in [ShareFormat.card, ShareFormat.story] {
                    // Paper on About and Note (a white card), the fourth slot elsewhere.
                    let tone = YoinShareCards.shared.tone(role: layout.tab.role, slot: dark ? 3 : 0)
                    let image = Self.image(layout, format, tone: tone, dark: dark)
                    let label = "\(layout.name) \(format.name) dark=\(dark)"
                    let height = ShareExport.logicalHeight(image)
                    let size = YoinShareCards.shared.exportSize(format: format, laidOutWidth: 360, laidOutHeight: Float(height))
                    let clear = try XCTUnwrap(ShareExport.render(image, opaque: false)?.cgImage, label)
                    XCTAssertEqual(clear.width, Int(size.width), label)
                    XCTAssertEqual(clear.height, Int(size.height), label)
                    XCTAssertEqual(Self.seeThroughEdgePixels(clear), 0, label)
                    let png = try XCTUnwrap(ShareExport.render(image)?.cgImage, label)
                    XCTAssertEqual(png.width, Int(size.width), label)
                    XCTAssertEqual(png.height, Int(size.height), label)
                    XCTAssertTrue([.none, .noneSkipFirst, .noneSkipLast].contains(png.alphaInfo), label)
                    if format == .story {
                        XCTAssertEqual(png.width, 1080)
                        XCTAssertEqual(png.height, 1920)
                    } else {
                        XCTAssertEqual(png.width, 1440)
                        XCTAssertGreaterThan(height, 200, label)
                    }
                }
            }
        }
    }

    /// A long pick in a Story zooms out rather than losing a line, and still fills the frame edge to edge.
    func test_should_zoomOut_when_aStoryPickIsLong() throws {
        let long = (1...14).map {
            ShareContentBuilder.line(text: "Line \($0) of a long chorus that wraps on a phone", startMs: Int64($0) * 4000,
                                     translation: nil)
        }
        var r = Self.render()
        r.content = ShareCardContent(song: r.content.song, lyrics: ShareLyrics(lines: long, passageStarts: [], showTranslation: false),
                                     about: nil, note: nil)
        for layout in [ShareLayout.lyricsclassic, .lyricsplayer, .lyricsposter] {
            let clear = try XCTUnwrap(ShareExport.render(Self.image(layout, .story, render: r), opaque: false)?.cgImage)
            XCTAssertEqual(Self.seeThroughEdgePixels(clear), 0, layout.name)
        }
        let zoom = StoryZoomLayout.resolve(frame: ShareMetrics.storyFrame, maxZoom: 1.4, fillHeight: false) { width in
            ImageRenderer(content: Self.image(.lyricsclassic, .card, render: r).frame(width: width)).uiImage?.size.height ?? 0
        }
        XCTAssertLessThan(zoom.zoom, 1)
        XCTAssertGreaterThanOrEqual(zoom.zoom, CGFloat(YoinShareCards.shared.storyMinZoom))
    }

    /// Snapshot renders of every layout (Card and Story, light and dark, two tones) for review: attached to the
    /// test, and written to `SHARE_SNAPSHOT_DIR` when the runner sets it.
    func test_should_renderSnapshots_of_everyLayout() throws {
        let dir = ProcessInfo.processInfo.environment["SHARE_SNAPSHOT_DIR"].map { URL(fileURLWithPath: $0) }
        if let dir { try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true) }
        for dark in [false, true] {
            for layout in ShareLayout.entries {
                for format in [ShareFormat.card, ShareFormat.story] {
                    for slot in [0, 2, 3] {
                        let tone = YoinShareCards.shared.tone(role: layout.tab.role, slot: Int32(slot))
                        let data = try XCTUnwrap(ShareExport.png(Self.image(layout, format, tone: tone, dark: dark)))
                        let name = "\(layout.name)-\(format.name)-\(tone.name)-\(dark ? "dark" : "light").png"
                        if let dir { try data.write(to: dir.appendingPathComponent(name)) }
                        if slot == 0 {
                            let a = XCTAttachment(data: data, uniformTypeIdentifier: "public.png")
                            a.name = name
                            a.lifetime = .keepAlways
                            add(a)
                        }
                    }
                }
            }
        }
    }

    /// Pixels on the image's border whose alpha isn't full.
    private static func seeThroughEdgePixels(_ image: CGImage) -> Int {
        let w = image.width, h = image.height
        var pixels = [UInt8](repeating: 0, count: w * h * 4)
        let ctx = CGContext(data: &pixels, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        ctx.clear(CGRect(x: 0, y: 0, width: w, height: h))
        ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
        var bad = 0
        func check(_ x: Int, _ y: Int) { if pixels[(y * w + x) * 4 + 3] != 255 { bad += 1 } }
        for x in 0..<w { check(x, 0); check(x, h - 1) }
        for y in 0..<h { check(0, y); check(w - 1, y) }
        return bad
    }
}
