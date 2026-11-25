import XCTest
@preconcurrency import YoinShared
@testable import Yoin

/// Library search's Memories & notes on iOS (parity p3 step B): the Swift ledger mapping against :shared's golden
/// vectors (shared/src/commonTest/resources/golden/memory-search.json: the same ledger, built from iOS's own rows,
/// must give the same hits, attachments and All pages), and the search surface's model.
@MainActor
final class SearchMemoriesTests: XCTestCase {
    // MARK: Golden

    private static func golden() throws -> [String: Any] {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("shared/src/commonTest/resources/golden/memory-search.json")
        return try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
    }

    private static func str(_ v: Any?) -> String? { v as? String }

    private static func album(_ v: Any?) -> YoinLedgerAlbum? {
        guard let o = v as? [String: Any] else { return nil }
        return YoinLedgerAlbum(profileId: o["profileId"] as! String, provider: o["provider"] as! String,
                               albumId: o["albumId"] as! String, name: o["name"] as! String,
                               artist: o["artist"] as! String, coverKey: str(o["coverKey"]))
    }

    private static func kind(_ name: String) -> YoinLedgerKind {
        YoinLedgerKind.entries.first { $0.name == name }!
    }

    private static func entry(_ o: [String: Any]) -> YoinLedgerEntry {
        YoinLedgerEntry(id: o["id"] as! String, kind: kind(o["kind"] as! String), at: (o["at"] as! NSNumber).int64Value,
                        profileId: o["profileId"] as! String, album: album(o["album"]), text: str(o["text"]),
                        trackTitle: str(o["trackTitle"]), songId: str(o["songId"]), songProvider: str(o["songProvider"]),
                        positionMs: (o["positionMs"] as? NSNumber).map { KotlinLong(value: $0.int64Value) },
                        score: (o["score"] as? NSNumber).map { KotlinFloat(value: $0.floatValue) })
    }

    /// The golden accounts as iOS keeps them: a provider and the scope its rows carry (none for Apple Music).
    private static let accounts: [String: LedgerAccount] = [
        "p-sub": LedgerAccount(provider: "subsonic", scope: "https://music.example|mira"),
        "p-apple": LedgerAccount(provider: "applemusic", scope: nil),
        "p-spot": LedgerAccount(provider: "spotify", scope: "spotify:mira"),
    ]

    /// The golden ledger as iOS's rows: ledger entries (MemoryLedger), album notes (AlbumNotesStore) and memory copy
    /// rows (AlbumMemoryStore's AI titles), then mapped by `SearchLedgerMapping`. A song note without a song (no id,
    /// no service) can't be an iOS row: it goes to the facade as it is.
    private struct Ledger {
        var mapped: SearchLedgerSnapshot
        var golden: [YoinLedgerEntry]
        var passedThrough: Set<String>
    }

    private static func ledger(_ g: [String: Any]) -> Ledger {
        let rows = g["ledger"] as! [[String: Any]]
        var entries: [LedgerEntry] = []
        var notes: [AlbumNote] = []
        var passthrough: [YoinLedgerEntry] = []
        var knownAlbums: [String: LedgerAlbum] = [:]
        for o in rows {
            let e = entry(o)
            let account = accounts[e.profileId]!
            let album = e.album.map { LedgerAlbum(account: account, albumId: $0.albumId, name: $0.name, artist: $0.artist,
                                                  coverKey: $0.coverKey) }
            if let album { knownAlbums["\(account.key)|\(album.albumId)"] = album }
            switch e.kind {
            case .albumnote:
                notes.append(AlbumNote(id: String(e.id.dropFirst("album-note:".count)), provider: account.provider,
                                       albumId: e.album!.albumId, content: e.text!, createdAt: e.at, updatedAt: e.at,
                                       albumName: e.album!.name, artist: e.album!.artist, scope: account.scope))
            case .songnote where e.songId == nil:
                passthrough.append(e)
            default:
                let k: LedgerKind = switch e.kind {
                case .songnote: .songNote
                case .review: .review
                case .title: .title
                case .albumrating: .albumRating
                default: .trackRating
                }
                entries.append(LedgerEntry(id: e.id, kind: k, at: e.at, account: account, album: album, text: e.text,
                                           trackTitle: e.trackTitle, songId: e.songId,
                                           positionMs: e.positionMs?.int64Value,
                                           score: e.score.map { Double("\($0.floatValue)")! }))
            }
        }
        let memories = (g["aiTitles"] as! [[String: Any]]).map { o in
            AlbumMemoryStore.MemoryCopy(provider: o["provider"] as! String, entityType: "album",
                                        entityId: o["albumId"] as! String, copy: "", promptHash: "", generatedAt: 0,
                                        title: o["title"] as? String, titlePromptHash: nil,
                                        scope: accounts[o["profileId"] as! String]!.scope)
        }
        let profileIds = Dictionary(uniqueKeysWithValues: accounts.map { ($1, $0) })
        // Albums only a note names are known to iOS from plays and visits (MemoryLedgerSource.lookup).
        var mapped = SearchLedgerMapping.snapshot(entries: entries, albumNotes: notes, memories: memories,
                                                  profileIds: profileIds, covers: [:]) { account, id in
            knownAlbums["\(account.key)|\(id)"]
        }
        mapped.entries += passthrough
        return Ledger(mapped: mapped, golden: rows.map(entry), passedThrough: Set(passthrough.map(\.id)))
    }

    func test_should_map_iOS_ledger_rows_to_the_golden_entries() throws {
        let l = Self.ledger(try Self.golden())
        XCTAssertEqual(l.mapped.entries.count, l.golden.count)
        let byId = Dictionary(uniqueKeysWithValues: l.mapped.entries.map { ($0.id, $0) })
        for g in l.golden where !l.passedThrough.contains(g.id) {
            let m = try XCTUnwrap(byId[g.id], g.id)
            if g.songProvider == nil, g.kind == .songnote {
                // iOS always knows a note's service: its account's. The golden has one without (its album's then).
                XCTAssertEqual(m.songProvider, g.album?.provider, g.id)
                XCTAssertEqual(m.doCopy(id: m.id, kind: m.kind, at: m.at, profileId: m.profileId, album: m.album,
                                        text: m.text, trackTitle: m.trackTitle, songId: m.songId, songProvider: nil,
                                        positionMs: m.positionMs, score: m.score), g, g.id)
            } else {
                XCTAssertEqual(m, g, g.id)
            }
        }
        let titles = Set(l.mapped.aiTitles.map { "\($0.profileId)|\($0.provider)|\($0.albumId)|\($0.title)" })
        let golden = Set((try Self.golden()["aiTitles"] as! [[String: Any]]).map {
            "\($0["profileId"]!)|\($0["provider"]!)|\($0["albumId"]!)|\($0["title"]!)"
        })
        XCTAssertEqual(titles, golden)
    }

    func test_should_find_the_golden_hits_from_the_iOS_ledger() throws {
        let g = try Self.golden()
        let l = Self.ledger(g)
        let vectors = g["search"] as! [[String: Any]]
        XCTAssertFalse(vectors.isEmpty)
        for v in vectors {
            let query = v["query"] as! String, active = Self.str(v["activeProfileId"])
            let result = YoinMemorySearch.companion.searchEntries(entries: l.mapped.entries, aiTitles: l.mapped.aiTitles,
                                                                  query: query, activeProfileId: active)
            XCTAssertEqual(Self.json(result) as NSDictionary, v["result"] as! NSDictionary, "\(query) as \(active ?? "-")")
        }
    }

    func test_should_attach_the_golden_memories_from_the_iOS_ledger() throws {
        let g = try Self.golden()
        let l = Self.ledger(g)
        let albums = Self.albums(g)
        for v in g["attachments"] as! [[String: Any]] {
            let active = Self.str(v["activeProfileId"])
            let got = YoinMemorySearch.companion.attachmentsOf(albums: albums, entries: l.mapped.entries,
                                                               aiTitles: l.mapped.aiTitles, activeProfileId: active)
            XCTAssertEqual(got.map(Self.json) as NSArray, v["result"] as! NSArray, active ?? "-")
        }
    }

    func test_should_lay_out_the_golden_pages_from_the_iOS_ledger() throws {
        let g = try Self.golden()
        let l = Self.ledger(g)
        let albums = Self.albums(g)
        let songs = (g["songs"] as! [[String: Any]]).map {
            YoinSearchSong(provider: $0["provider"] as! String, rawId: $0["rawId"] as! String, title: Self.str($0["title"]),
                           artist: Self.str($0["artist"]), album: Self.str($0["album"]))
        }
        let artists = (g["artists"] as! [[String: Any]]).map {
            YoinSearchArtist(provider: $0["provider"] as! String, rawId: $0["rawId"] as! String, name: $0["name"] as! String)
        }
        let playlists = (g["playlists"] as! [[String: Any]]).map {
            YoinSearchPlaylist(provider: $0["provider"] as! String, rawId: $0["rawId"] as! String,
                               name: $0["name"] as! String, owner: Self.str($0["owner"]))
        }
        let vectors = g["pages"] as! [[String: Any]]
        XCTAssertFalse(vectors.isEmpty)
        for v in vectors {
            let query = v["query"] as! String, active = Self.str(v["activeProfileId"])
            let service = v["withService"] as! Bool
            let c = YoinMemorySearch.companion
            let page = c.page(
                query: query, songs: service ? songs : [], albums: service ? albums : [], artists: service ? artists : [],
                playlists: service ? playlists : [],
                result: c.searchEntries(entries: l.mapped.entries, aiTitles: l.mapped.aiTitles, query: query,
                                        activeProfileId: active),
                attachments: c.attachmentsOf(albums: albums, entries: l.mapped.entries, aiTitles: l.mapped.aiTitles,
                                             activeProfileId: active),
                activeProfileId: active, columns: (v["columns"] as! NSNumber).int32Value,
                maxRows: (v["maxRows"] as! NSNumber).int32Value, maxWide: (v["maxWide"] as! NSNumber).int32Value)
            XCTAssertEqual(Self.json(page) as NSDictionary, v["page"] as! NSDictionary,
                           "\(query) as \(active ?? "-") at \(v["columns"]!) columns, service \(service)")
        }
    }

    private static func albums(_ g: [String: Any]) -> [YoinSearchAlbum] {
        (g["albums"] as! [[String: Any]]).map {
            YoinSearchAlbum(provider: $0["provider"] as! String, rawId: $0["rawId"] as! String, name: $0["name"] as! String,
                            artist: str($0["artist"]))
        }
    }

    // The golden's JSON shapes (MemorySearchGoldenTest).

    private static func o(_ v: Any?) -> Any { v ?? NSNull() }
    /// A 32-bit score as the golden prints it (its shortest decimal).
    private static func num(_ f: KotlinFloat?) -> Any { f.map { Double("\($0.floatValue)")! as Any } ?? NSNull() }

    private static func json(_ a: YoinLedgerAlbum?) -> Any {
        guard let a else { return NSNull() }
        return ["profileId": a.profileId, "provider": a.provider, "albumId": a.albumId, "name": a.name, "artist": a.artist,
                "coverKey": o(a.coverKey)]
    }

    private static func json(_ f: YoinSearchFragment) -> Any {
        ["text": f.text, "highlightStart": f.highlightStart, "highlightEnd": f.highlightEnd]
    }

    private static func json(_ m: YoinLedgerMark?) -> Any {
        guard let m else { return NSNull() }
        return ["kind": m.kind.name, "score": num(m.score), "noteCount": m.noteCount]
    }

    private static func json(_ r: YoinMemorySearchResult) -> [String: Any] {
        ["memories": r.memories.map { h -> [String: Any] in
            ["albumKey": h.album.key, "album": json(h.album), "field": h.field.name, "entryId": o(h.entryId),
             "fragment": json(h.fragment), "tier": h.tier.name, "strength": h.strength.name, "title": o(h.title),
             "albumScore": num(h.albumScore), "lastAt": h.lastAt, "isOtherAccount": h.isOtherAccount, "mark": json(h.mark)]
        },
         "notes": r.notes.map { n -> [String: Any] in
            ["entryId": n.entryId, "profileId": n.profileId, "provider": o(n.provider), "songId": o(n.songId),
             "trackTitle": o(n.trackTitle), "album": json(n.album), "positionMs": o(n.positionMs?.int64Value),
             "fragment": json(n.fragment), "tier": n.tier.name, "strength": n.strength.name, "at": n.at,
             "isOtherAccount": n.isOtherAccount]
        }]
    }

    private static func json(_ a: YoinMemoryAttachment) -> Any {
        ["provider": a.provider, "albumId": a.albumId, "albumKey": a.albumKey, "title": o(a.title),
         "albumScore": num(a.albumScore), "lastAt": a.lastAt, "mark": json(a.mark)]
    }

    private static func json(_ t: YoinSearchTile) -> Any {
        ["kind": t.kind.name, "key": t.key, "index": t.index, "wide": t.wide, "relevant": t.relevant, "tier": t.tier.name,
         "strength": t.strength.name, "note": o(t.note?.entryId), "memory": o(t.memory?.album.key),
         "attachment": o(t.attachment?.albumKey)]
    }

    private static func json(_ p: YoinSearchPage) -> [String: Any] {
        ["best": p.best.map { ["kind": $0.kind.name, "index": $0.index] as Any } ?? NSNull(),
         "sections": p.sections.map { s -> [String: Any] in
            ["section": s.section.name, "tier": s.tier.name, "strength": s.strength.name, "folded": s.folded,
             "more": s.more, "tiles": s.tiles.map(json),
             "rows": s.rows.map { ["keys": $0.tiles.map(\.key), "wideAtEnd": $0.wideAtEnd] }]
        },
         "memories": p.memories.map(\.key)]
    }

    // MARK: The search surface's model (the demo ledger: two accounts, notes, album notes, titles, an AI title)

    private func demoModel(_ query: String = "night") -> SearchMemories {
        let m = SearchMemories()
        m.run(query, ledger: MemorySearchLedger(snapshot: SearchMemoriesDemo.build(now: Date(timeIntervalSince1970: 1_790_000_000))),
              activeProfileId: SearchMemoriesDemo.activeProfileId)
        return m
    }

    /// The mock search for "night" (SearchView's): two songs, no album, the Night Walks playlist.
    private func nightRows() -> SearchResultSet {
        let a = MockLibrary.allAlbums
        let tracks = a.flatMap { al in al.tracks.filter { $0.title.lowercased().contains("night") }.map { (al, $0) } }
        let playlist = LibraryItem(id: MediaId(provider: "mock", rawId: "pl1"), kind: .playlist, title: "Night Walks",
                                   subtitle: "Playlist", palette: a[6].palette)
        return SearchResultSet(artists: [], albums: [], playlists: [playlist], tracks: tracks)
    }

    func test_should_find_every_accounts_notes_and_memories_when_searching() {
        let m = demoModel()
        XCTAssertEqual(m.query, "night")
        XCTAssertEqual(Set(m.result.notes.map(\.entryId)), ["song-note:n1", "song-note:n2", "song-note:o1"])
        XCTAssertEqual(Set(m.result.memories.map(\.album.albumId)), ["al2", "al7", "al12", "demo-harbour"])
        let other = m.result.notes.first { $0.entryId == "song-note:o1" }!
        XCTAssertTrue(other.isOtherAccount, "Okto's note is another account's")
        XCTAssertFalse(m.result.notes.first { $0.entryId == "song-note:n1" }!.isOtherAccount)
        // The AI's title met the query: Blue Hour Atlas.
        XCTAssertEqual(m.result.memories.first { $0.album.albumId == "al7" }?.title, "Night maps")
    }

    func test_should_forget_the_hits_when_the_query_is_cleared() {
        let m = demoModel()
        m.run("   ", ledger: MemorySearchLedger(snapshot: .empty), activeProfileId: nil)
        XCTAssertEqual(m.query, "")
        XCTAssertTrue(m.isEmpty)
    }

    func test_should_join_a_note_to_its_returned_song_and_play_from_its_second() throws {
        let m = demoModel()
        let rows = nightRows()
        let page = m.page(rows.names, query: "night", columns: 3)
        let songs = try XCTUnwrap(page.sections.first { $0.section == .songs })
        let joined = try XCTUnwrap(songs.tiles.first { $0.kind == .song && $0.note != nil })
        XCTAssertTrue(joined.wide, "a song met through a note is a 1×2")
        XCTAssertEqual(rows.tracks[Int(joined.index)].1.title, "Night Market")
        let (_, track) = rows.tracks[Int(joined.index)]
        let target = YoinMemorySearch.companion.noteTarget(note: joined.note!, returnedSongProvider: track.id.provider,
                                                            returnedSongId: track.id.rawId,
                                                            activeProfileId: SearchMemoriesDemo.activeProfileId)
        XCTAssertEqual(target.action, .playsong)
        XCTAssertEqual(target.songId, track.id.rawId)
        XCTAssertEqual(target.seekToMs?.int64Value, 72_000)
        XCTAssertEqual(page.best?.kind == .song || page.best?.kind == .playlist, true)
    }

    func test_should_open_a_loose_notes_album_and_switch_for_another_accounts() throws {
        let m = demoModel()
        let page = m.page(nightRows().names, query: "night", columns: 3)
        let loose = page.sections.flatMap(\.tiles).filter { $0.kind == .note }.compactMap(\.note)
        let mine = try XCTUnwrap(loose.first { $0.entryId == "song-note:n2" })
        let other = try XCTUnwrap(loose.first { $0.entryId == "song-note:o1" })
        let c = YoinMemorySearch.companion
        let open = c.noteTarget(note: mine, returnedSongProvider: nil, returnedSongId: nil,
                                activeProfileId: SearchMemoriesDemo.activeProfileId)
        XCTAssertEqual(open.action, .openalbum)
        XCTAssertEqual(open.albumId, "al1")
        XCTAssertFalse(open.switchAccount)
        let switching = c.noteTarget(note: other, returnedSongProvider: nil, returnedSongId: nil,
                                     activeProfileId: SearchMemoriesDemo.activeProfileId)
        XCTAssertEqual(switching.action, .openalbum)
        XCTAssertTrue(switching.switchAccount)
        XCTAssertEqual(switching.profileId, SearchMemoriesDemo.otherProfileId)
        XCTAssertEqual(switching.subject, "Coast Road")
    }

    func test_should_list_every_hit_under_the_memories_chip() {
        let m = demoModel()
        let page = m.page(nightRows().names, query: "night", columns: 3)
        XCTAssertEqual(page.memories.count, m.result.notes.count + m.result.memories.count)
        XCTAssertTrue(page.memories.allSatisfy { $0.kind == .song || $0.kind == .note || $0.kind == .album || $0.kind == .memory })
        XCTAssertEqual(page.memories.first.map { $0.wide || $0.kind == .memory }, true)
    }

    func test_should_not_mix_hits_for_another_query_into_the_page() {
        let m = demoModel("night")
        // The service has answered "nigh" (typing on) while the ledger still holds "night"'s hits.
        let page = m.page(nightRows().names, query: "nigh", columns: 3)
        XCTAssertTrue(page.memories.isEmpty)
        XCTAssertFalse(page.sections.flatMap(\.tiles).contains { $0.note != nil || $0.memory != nil })
    }

    func test_should_keep_the_page_while_nothing_changes() {
        let m = demoModel()
        let rows = nightRows().names
        XCTAssertTrue(m.page(rows, query: "night", columns: 3) === m.page(rows, query: "night", columns: 3))
        XCTAssertFalse(m.page(rows, query: "night", columns: 3) === m.page(rows, query: "night", columns: 6))
    }

    func test_should_attach_a_returned_albums_memory_in_the_account_in_use() {
        let m = demoModel("tidal")
        let tidal = MockLibrary.allAlbums[1]
        let rows = SearchResultSet(albums: [tidal])
        let attachments = m.attachments(rows.names.albums)
        XCTAssertEqual(attachments.map(\.albumId), [tidal.id.rawId])
        XCTAssertEqual(attachments.first?.title, "Night swims")
        XCTAssertEqual(attachments.first?.mark?.kind, .albumrating)
        let tile = m.page(rows.names, query: "tidal", columns: 3).sections.first?.tiles.first
        XCTAssertEqual(tile?.attachment?.albumId, tidal.id.rawId)
    }

    func test_should_take_columns_from_the_width() {
        // A phone's window: Home's three.
        XCTAssertEqual(SearchMemories.columns(width: 402, regularWidth: false, compactHeight: false), 3)
        // A phone on its side and iPad / Mac panes: the Library grid's count, at most eight.
        XCTAssertGreaterThan(SearchMemories.columns(width: 874, regularWidth: false, compactHeight: true), 3)
        XCTAssertGreaterThan(SearchMemories.columns(width: 834, regularWidth: true, compactHeight: false), 3)
        XCTAssertEqual(SearchMemories.columns(width: 2400, regularWidth: true, compactHeight: false), 8)
        XCTAssertEqual(SearchMemories.columns(width: 600, regularWidth: true, compactHeight: false),
                       LibraryGrid.columns(width: 600, regularWidth: true, compactHeight: false))
    }

    func test_should_offer_the_shared_chips_in_order() {
        let c = YoinMemorySearch.companion
        XCTAssertEqual(c.filters(canSearchPlaylists: true, canReadPlaylists: true, savedLibrary: false).map(SearchView.Filter.init),
                       [.all, .songs, .albums, .memories, .artists, .playlists])
        XCTAssertEqual(c.filters(canSearchPlaylists: false, canReadPlaylists: true, savedLibrary: true).map(SearchView.Filter.init),
                       [.all, .songs, .albums, .memories, .artists])
        for f in SearchView.Filter.allCases { XCTAssertEqual(SearchView.Filter(f.shared), f) }
        XCTAssertEqual(SearchView.Filter(YoinSearchSection.albums), .albums)
        XCTAssertNil(SearchView.Filter.memories.kind, "Memories reads the ledger, never the service's next page")
    }

    func test_should_cut_a_highlight_at_utf16_offsets() throws {
        let text = "🎧 night ride"
        let m = YoinMemorySearch.companion.match(query: "night", text: text)
        let (before, hit, after) = try XCTUnwrap(MarkedText.split(text, start: Int(m.start), end: Int(m.end)))
        XCTAssertEqual(before, "🎧 ")
        XCTAssertEqual(hit, "night")
        XCTAssertEqual(after, " ride")
        XCTAssertNil(MarkedText.split(text, start: -1, end: -1))
        XCTAssertNil(MarkedText.split(text, start: 3, end: 99))
    }

    func test_should_give_up_the_seek_when_the_song_never_becomes_current() async {
        let model = AppModel.shared
        let started = ContinuousClock.now
        await model.seekWhenCurrent(rawId: "never-current", positionMs: 12_000, timeoutMs: 150)
        XCTAssertLessThan(ContinuousClock.now - started, .seconds(2))
        XCTAssertNotEqual(model.player.currentTrack?.id, "never-current")
    }

    func test_should_read_album_notes_and_ai_titles_of_counted_accounts_only() {
        let mine = LedgerAccount(provider: "subsonic", scope: "s1"), gone = LedgerAccount(provider: "subsonic", scope: "s2")
        let notes = [
            AlbumNote(id: "a", provider: "subsonic", albumId: "al-1", content: " Bought it in Kyoto ", createdAt: 5,
                      updatedAt: 5, albumName: "Paper Moons", artist: "Mira Sol", scope: "s1"),
            AlbumNote(id: "b", provider: "subsonic", albumId: "al-1", content: "gone", createdAt: 6, updatedAt: 6,
                      albumName: "Paper Moons", artist: "Mira Sol", scope: "s2"),
            AlbumNote(id: "c", provider: "subsonic", albumId: "al-2", content: "   ", createdAt: 7, updatedAt: 7,
                      albumName: "Blank", artist: "", scope: "s1"),
        ]
        let memories = [
            AlbumMemoryStore.MemoryCopy(provider: "subsonic", entityType: "album", entityId: "al-1", copy: "", promptHash: "",
                                        generatedAt: 0, title: " Kyoto rain ", titlePromptHash: nil, scope: "s1"),
            AlbumMemoryStore.MemoryCopy(provider: "subsonic", entityType: "album", entityId: "al-1", copy: "", promptHash: "",
                                        generatedAt: 0, title: "Gone", titlePromptHash: nil, scope: "s2"),
        ]
        let s = SearchLedgerMapping.snapshot(entries: [], albumNotes: notes, memories: memories, profileIds: [mine: "p1"],
                                             covers: [:])
        XCTAssertEqual(s.entries.map(\.id), ["album-note:a"])
        XCTAssertEqual(s.entries.first?.kind, .albumnote)
        XCTAssertEqual(s.entries.first?.album?.name, "Paper Moons")
        XCTAssertEqual(s.aiTitles.map(\.title), ["Kyoto rain"])
        _ = gone
    }
}
