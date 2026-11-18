import XCTest
@testable import Yoin

/// The "What others say" page (Android #192): NeoDbVoicesParsingTest ported line for line, the page's
/// states (NeoDbVoicesViewModelTest), the service's signed-out fallback, and the card layout. The network is
/// a URLProtocol stub: nothing here reaches neodb.social.
@MainActor
final class NeoDBVoicesTests: XCTestCase {

    private func item(_ uuid: String, _ title: String, _ ratingCount: Int?, _ artist: [String] = []) -> NeoDBItem {
        NeoDBItem(uuid: uuid, title: title, artist: artist, ratingCount: ratingCount)
    }

    private func pick(_ album: String, _ artist: String?, _ items: [NeoDBItem]) -> String? {
        NeoDBVoicesParsing.pickAlbumItem(albumName: album, artistName: artist, items: items)?.uuid
    }

    private func decode<T: Decodable>(_ type: T.Type, _ json: String) throws -> T {
        try JSONDecoder().decode(T.self, from: Data(json.utf8))
    }

    // MARK: Picking the item

    func test_should_pickMostRatedEdition_when_severalItemsShareTheTitle() {
        let items = [item("apple", "OK Computer", 4, ["Radiohead"]),
                     item("main", "OK Computer", 510, ["Radiohead"]),
                     item("other", "OK Computer OKNOTOK 1997 2017", 80, ["Radiohead"])]
        XCTAssertEqual(pick("OK Computer", "Radiohead", items), "main")
    }

    func test_should_findNothing_when_onlyAnotherArtistsAlbumSharesTheTitle() {
        let items = [item("dean", "하루살이 (feat. 설리, Rad Museum)", 15, ["DEAN", "딘"]),
                     item("towa", "Sound Museum", 9, ["Towa Tei"]),
                     item("jazz", "Museum", 10, ["ElyonBeats"])]
        XCTAssertNil(pick("Museum", "OWIS", items))
    }

    func test_should_findNothing_when_noTitleMatches() {
        XCTAssertNil(pick("Blonde", "Frank Ocean", [item("first", "Something else", 577), item("second", "Another", 900)]))
    }

    func test_should_matchOneOfSeveralArtists_when_yoinListsFeaturedNames() {
        XCTAssertEqual(pick("Museum", "Someone, OWIS feat. Guest", [item("x", "Museum", 3, ["OWIS", "오위스"])]), "x")
    }

    func test_should_takeLongerEditionTitle_only_when_artistAgrees() {
        let items = [item("deluxe", "Blonde Deluxe Edition", 40, ["Frank Ocean"]),
                     item("cover", "Blonde Covers", 90, ["Tribute Band"])]
        XCTAssertEqual(pick("Blonde", "Frank Ocean", items), "deluxe")
    }

    func test_should_findNothing_when_onlyOtherReleasesContainTheTitle() {
        let jay = [item("plus", "范特西plus", 1, ["周杰伦"]), item("still", "依然范特西 專輯 經典復刻黑膠", 1, ["周杰伦 Jay Chou"])]
        XCTAssertNil(pick("范特西", "周杰伦", jay))
        let beatles = [item("bossa", "Down Abbey Road Bossa - Songs of the Beatles", 0, ["Various", "Beatles"]),
                       item("other", "The Other Way Of Crossing Abbey Road", 6, ["The Beatles"])]
        XCTAssertNil(pick("Abbey Road", "The Beatles", beatles))
        // A number alone is a sequel or a year, not an edition.
        XCTAssertNil(pick("Luv Is Rage", "Lil Uzi Vert", [item("two", "Luv Is Rage 2", 30, ["Lil Uzi Vert"])]))
    }

    func test_should_takeAnEdition_when_itsTitleAddsOnlyEditionWords() {
        XCTAssertEqual(pick("依然范特西", "周杰伦", [item("vinyl", "依然范特西 專輯 經典復刻黑膠", 1, ["周杰伦 Jay Chou"])]), "vinyl")
        XCTAssertEqual(pick("Abbey Road", "The Beatles", [item("remaster", "Abbey Road 2019 Remaster", 6, ["The Beatles"])]),
                       "remaster")
        // The other way round: Yoin's album carries the edition, NeoDB's item the plain title.
        XCTAssertEqual(pick("Abbey Road - 50th Anniversary Edition", "The Beatles",
                            [item("plain", "Abbey Road", 338, ["The Beatles"])]), "plain")
    }

    func test_should_matchOnTheSigns_when_titleHasNoLetterOrDigit() {
        let question = [item("17", "17", 53, ["Xxxtentacion"]), item("q", "?", 41, ["XXXTENTACION"]),
                        item("sad", "Sad!", 20, ["XXXTENTACION"])]
        XCTAssertEqual(pick("?", "XXXTENTACION", question), "q")
        let brackets = [item("takk", "Takk...", 98, ["Sigur Rós"]), item("b", "( )", 77, ["Sigur Rós"])]
        XCTAssertEqual(pick("( )", "Sigur Rós", brackets), "b")
        XCTAssertEqual(pick("()", "Sigur Rós", brackets), "b")
        let divide = [item("word", "Divide", 109, ["Ed Sheeran"]), item("sign", "÷", 3, ["Ed Sheeran"]),
                      item("tour", "+-=÷× (TOUR COLLECTION)", 1, ["Ed Sheeran"])]
        XCTAssertEqual(pick("÷ (Deluxe)", "Ed Sheeran", divide), "sign")
    }

    func test_should_acceptExactTitle_when_artistIsUnknown() {
        XCTAssertEqual(pick("Blonde", nil, [item("a", "Blonde", 7)]), "a")
    }

    func test_should_keepSearchOrder_when_countsTie() {
        XCTAssertEqual(pick("Blonde", nil, [item("a", "Blonde", nil), item("b", "Blonde", nil)]), "a")
    }

    func test_should_returnNil_when_noResults() {
        XCTAssertNil(pick("Blonde", nil, []))
    }

    func test_should_ignoreCaseAndEditionSuffix_when_normalizingTitles() {
        XCTAssertEqual(NeoDBVoicesParsing.normalizeTitle("ok computer"),
                       NeoDBVoicesParsing.normalizeTitle("OK Computer (Remastered) [Deluxe]"))
        XCTAssertEqual(NeoDBVoicesParsing.normalizeTitle("醜奴兒"), NeoDBVoicesParsing.normalizeTitle("醜奴兒（全景声）"))
    }

    // MARK: Reading payloads

    func test_should_readSpreadAndLinks_when_searchItemHasThem() throws {
        let body = """
            {"data":[{"uuid":"16eojnHcEjtDUsJP2yMEKk","url":"/album/16eojnHcEjtDUsJP2yMEKk","title":"醜奴兒",
            "artist":["草東沒有派對"],"rating":8.7,"rating_count":577,"rating_distribution":[0,2,10,35,53],
            "external_resources":[{"url":"https://music.douban.com/subject/26753594/"},
            {"url":"https://www.discogs.com/master/1076839"},
            {"url":"https://musicbrainz.org/release/ad0e6676-3e55-4bd6-a9ed-7f6424fd46f6"}]}]}
            """
        let item = try XCTUnwrap(try decode(NeoDBSearchResponse.self, body).data.first)
        let album = try XCTUnwrap(NeoDBVoicesParsing.albumVoices(instance: "https://neodb.social/", item: item))
        XCTAssertEqual(album.itemUrl, "https://neodb.social/album/16eojnHcEjtDUsJP2yMEKk")
        XCTAssertEqual(album.ratingCount, 577)
        XCTAssertEqual(album.rating, 8.7)
        XCTAssertEqual(album.distribution, [0, 2, 10, 35, 53])
        XCTAssertEqual(album.doubanUrl, "https://music.douban.com/subject/26753594/")
        XCTAssertEqual(album.discogsUrl, "https://www.discogs.com/master/1076839")
        XCTAssertTrue(album.musicBrainzUrl?.contains("ad0e6676") == true)
    }

    func test_should_readCommentAndRating_when_postIsAShortComment() throws {
        let page = try decode(NeoDBPostPage.self, """
            {"pages":9,"count":173,"data":[{"id":"1","url":"https://neodb.social/@a/posts/1/",
            "created_at":"2026-08-15T17:30:11.376Z","sensitive":false,"spoiler_text":"",
            "account":{"acct":"everglow@neodb.social","username":"everglow","display_name":"Everglow",
            "avatar":"https://neodb.social/s/img/avatar.png"},
            "ext_neodb":{"relatedWith":[
              {"type":"Status","status":"complete"},
              {"type":"Comment","content":"很情绪，很动人"},
              {"type":"Rating","value":9,"best":10,"worst":1}]}}]}
            """)
        let voices = NeoDBVoicesParsing.page(1, page)
        XCTAssertEqual(voices.count, 173)
        XCTAssertEqual(voices.pages, 9)
        let voice = try XCTUnwrap(voices.voices.first)
        XCTAssertEqual(voices.voices.count, 1)
        XCTAssertEqual(voice.text, "很情绪，很动人")
        XCTAssertEqual(voice.rating, 9)
        XCTAssertEqual(voice.author, "Everglow")
        XCTAssertEqual(voice.handle, "everglow@neodb.social")
        XCTAssertNil(voice.host)
        XCTAssertNil(voice.reviewTitle)
        XCTAssertFalse(voice.folded)
        XCTAssertEqual(voice.createdAtMillis, 1_786_815_011_376)
    }

    func test_should_useTitleAndOpening_when_postIsAReview() throws {
        let post = try decode(NeoDBPost.self, """
            {"id":"2","account":{"acct":"liz@neodb.social","display_name":""},
            "ext_neodb":{"relatedWith":[{"type":"Review","name":"爱人同质",
            "href":"https://neodb.social/review/1RLz","content":"\\n B+ \\n\\n这绝对不是一张**完美**的专辑。"}]}}
            """)
        let voice = try XCTUnwrap(NeoDBVoicesParsing.voice(post))
        XCTAssertEqual(voice.reviewTitle, "爱人同质")
        XCTAssertEqual(voice.text, "B+ 这绝对不是一张完美的专辑。")
        XCTAssertEqual(voice.url, "https://neodb.social/review/1RLz")
        XCTAssertEqual(voice.author, "liz")
        XCTAssertTrue(voice.isLong)
    }

    func test_should_foldVoice_when_writerAddedAContentWarning() throws {
        let post = try decode(NeoDBPost.self, """
            {"id":"3","sensitive":true,"spoiler_text":"剧透",
            "ext_neodb":{"relatedWith":[{"type":"Comment","content":"结尾那首……"}]}}
            """)
        let voice = try XCTUnwrap(NeoDBVoicesParsing.voice(post))
        XCTAssertTrue(voice.folded)
        XCTAssertEqual(voice.foldNote, "剧透")
    }

    func test_should_skipPost_when_itCarriesNoWords() throws {
        let post = try decode(NeoDBPost.self, #"{"id":"4","ext_neodb":{"relatedWith":[{"type":"Status"},{"type":"Rating","value":8}]}}"#)
        XCTAssertNil(NeoDBVoicesParsing.voice(post))
    }

    func test_should_keepOneVoice_when_pageRepeatsAPost() throws {
        let page = try decode(NeoDBPostPage.self, """
            {"pages":1,"count":2,"data":[
            {"id":"7","ext_neodb":{"relatedWith":[{"type":"Comment","content":"好听"}]}},
            {"id":"7","ext_neodb":{"relatedWith":[{"type":"Comment","content":"好听"}]}},
            {"id":"8","ext_neodb":{"relatedWith":[{"type":"Comment","content":"一般"}]}}]}
            """)
        XCTAssertEqual(NeoDBVoicesParsing.page(1, page).voices.map(\.id), ["7", "8"])
    }

    func test_should_nameTheInstance_when_writerLivesElsewhere() {
        var voice = NeoDBVoice(id: "1", author: "Mori", handle: "mori@mastodon.social", avatarUrl: nil, rating: nil,
                               text: "x", reviewTitle: nil, folded: false, foldNote: nil, url: nil, createdAtMillis: nil)
        XCTAssertEqual(voice.host, "mastodon.social")
        voice.handle = "mori"
        XCTAssertNil(voice.host)
    }

    func test_should_openOnlyWebLinks_when_linkComesFromAPost() {
        XCTAssertTrue(NeoDBVoicesParsing.isWebLink("https://neodb.social/@a/posts/1/"))
        XCTAssertTrue(NeoDBVoicesParsing.isWebLink("HTTP://example.org/review/1"))
        XCTAssertFalse(NeoDBVoicesParsing.isWebLink("intent://scan/#Intent;scheme=zxing;end"))
        XCTAssertFalse(NeoDBVoicesParsing.isWebLink("file:///sdcard/Download/a.html"))
        XCTAssertFalse(NeoDBVoicesParsing.isWebLink(""))
    }

    func test_should_cutLongReviewAtASentence_when_openingIsTooLong() {
        let opening = NeoDBVoicesParsing.reviewOpening(String(repeating: "这是一句不短不长的话。", count: 40))
        XCTAssertLessThanOrEqual(opening.count, 180)
        XCTAssertTrue(opening.hasSuffix("。"))
    }

    func test_should_readCatalogueIds_when_linksComeFromNeoDb() {
        let master = NeoDBVoicesParsing.discogsId("https://www.discogs.com/master/21491")
        XCTAssertEqual(master?.kind, "master")
        XCTAssertEqual(master?.id, 21491)
        let release = NeoDBVoicesParsing.discogsId("https://www.discogs.com/release/4950798-X")
        XCTAssertEqual(release?.kind, "release")
        XCTAssertEqual(release?.id, 4_950_798)
        let mb = NeoDBVoicesParsing.musicBrainzId("https://musicbrainz.org/release/ad0e6676-3e55-4bd6-a9ed-7f6424fd46f6")
        XCTAssertEqual(mb?.kind, "release")
        XCTAssertEqual(mb?.id, "ad0e6676-3e55-4bd6-a9ed-7f6424fd46f6")
        XCTAssertNil(NeoDBVoicesParsing.discogsId("https://music.douban.com/subject/1/"))
    }

    func test_should_buildSearchLinks_when_artistIsKnown() {
        XCTAssertEqual(NeoDBVoicesParsing.rymSearchUrl(albumName: "OK Computer", artist: "Radiohead"),
                       "https://rateyourmusic.com/search?searchtype=l&searchterm=OK+Computer+Radiohead")
        XCTAssertEqual(NeoDBVoicesParsing.aotySearchUrl(albumName: "Blonde", artist: " "),
                       "https://www.albumoftheyear.org/search/albums/?q=Blonde")
        XCTAssertEqual(NeoDBVoicesParsing.neoDBSearchUrl(instance: "https://neodb.social/", albumName: "醜奴兒", artist: nil),
                       "https://neodb.social/search?c=music&q=%E9%86%9C%E5%A5%B4%E5%85%92")
    }

    func test_should_showHalfStars_when_gradeIsOdd() {
        XCTAssertEqual(NeoDBGrade.text(8), "4")
        XCTAssertEqual(NeoDBGrade.text(7), "3.5")
    }

    // MARK: Layout

    private func voice(_ id: String, _ text: String, review: Bool = false) -> NeoDBVoice {
        NeoDBVoice(id: id, author: "a", handle: "a@neodb.social", avatarUrl: nil, rating: nil, text: text,
                   reviewTitle: review ? "t" : nil, folded: false, foldNote: nil, url: nil, createdAtMillis: nil)
    }

    func test_should_spanTheWidth_when_aVoiceIsLong_and_pairTheShortOnes() {
        let voices = [voice("1", "short"), voice("2", "short too"), voice("3", "r", review: true),
                      voice("4", String(repeating: "x", count: 41)), voice("5", "s"), voice("6", "t"), voice("7", "u")]
        let rows = NeoDBVoiceRows.rows(voices)
        XCTAssertEqual(rows.map(\.kind), [.lanes([0], [1]), .full(2), .full(3), .lanes([4, 6], [5])])
        XCTAssertEqual(Set(rows.map(\.id)).count, rows.count)
    }

    func test_should_keepCardsInTheirLanes_when_aPageIsAppended() {
        let first = [voice("1", "short"), voice("2", "a little longer than that one"), voice("3", "x")]
        let more = first + [voice("4", "y"), voice("5", "z")]
        guard case .lanes(let l1, let r1) = NeoDBVoiceRows.rows(first)[0].kind,
              case .lanes(let l2, let r2) = NeoDBVoiceRows.rows(more)[0].kind else { return XCTFail("one run") }
        XCTAssertEqual(Array(l2.prefix(l1.count)), l1)
        XCTAssertEqual(Array(r2.prefix(r1.count)), r1)
        XCTAssertEqual(NeoDBVoiceRows.rows(first)[0].id, NeoDBVoiceRows.rows(more)[0].id)
    }

    // MARK: The page's states

    private final class FakeReader: NeoDBVoicesReading, @unchecked Sendable {
        var album: Result<NeoDBAlbumVoices?, Error> = .success(nil)
        var pages: [Int: Result<NeoDBVoicePage, Error>] = [:]
        var references: [NeoDBReferenceScore] = []
        var finds = 0
        var pageCalls: [Int] = []
        /// Holds findAlbum until released (the cancelled-load test).
        var gate: AsyncStream<Void>?

        func findAlbum(albumName: String, artist: String?) async throws -> NeoDBAlbumVoices? {
            finds += 1
            if let gate { for await _ in gate { break } }
            return try album.get()
        }

        func voices(itemUuid: String, page: Int) async throws -> NeoDBVoicePage {
            pageCalls.append(page)
            return try (pages[page] ?? .failure(URLError(.badServerResponse))).get()
        }

        func searchUrl(albumName: String, artist: String?) async -> String { "https://neodb.social/search?q=\(albumName)" }
        func referenceScores(_ album: NeoDBAlbumVoices) async -> [NeoDBReferenceScore] { references }
    }

    private let album = NeoDBAlbumVoices(itemUuid: "u", itemUrl: "https://neodb.social/album/u", title: "Blonde",
                                         rating: 8.1, ratingCount: 40, distribution: [1, 2, 3, 4, 90], doubanUrl: nil,
                                         discogsUrl: nil, musicBrainzUrl: nil)

    private func page(_ n: Int, of pages: Int, ids: [String]) -> NeoDBVoicePage {
        NeoDBVoicePage(voices: ids.map { voice($0, "w\($0)") }, page: n, pages: pages, count: 30)
    }

    func test_should_showVoices_when_neoDbHasTheAlbum() async {
        let reader = FakeReader()
        reader.album = .success(album)
        reader.pages[1] = .success(page(1, of: 2, ids: ["a", "b"]))
        reader.references = [NeoDBReferenceScore(source: "Discogs", value: 4.2, scale: 5, votes: 9, url: "https://d")]
        let model = NeoDBVoicesModel(albumName: "Blonde", artistName: "  ", service: reader)
        XCTAssertNil(model.artistName)
        model.load()
        await model.settle()
        let c = try? XCTUnwrap(model.content)
        XCTAssertEqual(c?.voices.map(\.id), ["a", "b"])
        XCTAssertEqual(c?.count, 30)
        XCTAssertEqual(c?.endReached, false)
        XCTAssertEqual(c?.references.map(\.source), ["Discogs"])
    }

    func test_should_offerASearch_when_neoDbHasNoSuchAlbum() async {
        let reader = FakeReader()
        let model = NeoDBVoicesModel(albumName: "Museum", artistName: "OWIS", service: reader)
        model.load()
        await model.settle()
        XCTAssertEqual(model.state, .notFound(searchUrl: "https://neodb.social/search?q=Museum"))
    }

    func test_should_loadAgain_when_retryFollowsAFailure() async {
        let reader = FakeReader()
        reader.album = .failure(URLError(.notConnectedToInternet))
        let model = NeoDBVoicesModel(albumName: "Blonde", artistName: nil, service: reader)
        model.load()
        await model.settle()
        XCTAssertEqual(model.state, .failed)
        reader.album = .success(album)
        reader.pages[1] = .success(page(1, of: 1, ids: ["a"]))
        model.load()
        await model.settle()
        XCTAssertEqual(model.content?.voices.map(\.id), ["a"])
        XCTAssertEqual(model.content?.endReached, true)
        XCTAssertEqual(reader.finds, 2)
    }

    func test_should_appendTheNextPage_when_listNearsItsEnd() async {
        let reader = FakeReader()
        reader.album = .success(album)
        reader.pages[1] = .success(page(1, of: 2, ids: ["a", "b"]))
        reader.pages[2] = .success(page(2, of: 2, ids: ["b", "c"]))
        let model = NeoDBVoicesModel(albumName: "Blonde", artistName: nil, service: reader)
        model.load()
        await model.settle()
        model.loadMore()
        XCTAssertEqual(model.content?.loadingMore, true)
        model.loadMore() // a second ask while the page is out does nothing
        await model.settle()
        XCTAssertEqual(model.content?.voices.map(\.id), ["a", "b", "c"])
        XCTAssertEqual(model.content?.endReached, true)
        XCTAssertEqual(reader.pageCalls, [1, 2])
        model.loadMore() // the end: no third page
        await model.settle()
        XCTAssertEqual(reader.pageCalls, [1, 2])
    }

    func test_should_endInARetry_when_theNextPageFails() async {
        let reader = FakeReader()
        reader.album = .success(album)
        reader.pages[1] = .success(page(1, of: 3, ids: ["a"]))
        let model = NeoDBVoicesModel(albumName: "Blonde", artistName: nil, service: reader)
        model.load()
        await model.settle()
        model.loadMore()
        await model.settle()
        XCTAssertEqual(model.content?.moreFailed, true)
        XCTAssertEqual(model.content?.loadingMore, false)
        reader.pages[2] = .success(page(2, of: 3, ids: ["b"]))
        model.loadMore()
        await model.settle()
        XCTAssertEqual(model.content?.moreFailed, false)
        XCTAssertEqual(model.content?.voices.map(\.id), ["a", "b"])
    }

    func test_should_keepTheNewLoad_when_anEarlierLoadIsCancelled() async {
        let reader = FakeReader()
        let (gate, open) = AsyncStream<Void>.makeStream()
        reader.gate = gate
        reader.album = .failure(URLError(.timedOut))
        let model = NeoDBVoicesModel(albumName: "Blonde", artistName: nil, service: reader)
        model.load()
        await Task.yield()
        // The second load replaces the first while it still waits.
        reader.gate = nil
        reader.album = .success(album)
        reader.pages[1] = .success(page(1, of: 1, ids: ["a"]))
        model.load()
        await model.settle()
        open.yield()
        open.finish()
        try? await Task.sleep(for: .milliseconds(50))
        XCTAssertEqual(model.content?.voices.map(\.id), ["a"])
    }

    // MARK: The service

    func test_should_readSignedOut_when_neoDbNoLongerTakesTheToken() async throws {
        VoicesStub.reset()
        VoicesStub.handler = { request in
            if request.authorization != nil { return (401, #"{"detail":"Invalid token"}"#) }
            if request.path == "/api/catalog/search" {
                return (200, #"{"data":[{"uuid":"u1","title":"Blonde","artist":["Frank Ocean"],"rating_count":3}]}"#)
            }
            return (200, #"{"pages":1,"count":1,"data":[{"id":"9","ext_neodb":{"relatedWith":[{"type":"Comment","content":"好"}]}}]}"#)
        }
        let service = NeoDBVoicesService(api: NeoDBAPI(session: VoicesStub.session), urlSession: VoicesStub.session,
                                         session: { (instance: "https://mine.example", token: "revoked") })
        let found = try await service.findAlbum(albumName: "Blonde", artist: "Frank Ocean")
        XCTAssertEqual(found?.itemUrl, "https://mine.example/album/u1")
        let page = try await service.voices(itemUuid: "u1", page: 1)
        XCTAssertEqual(page.voices.map(\.text), ["好"])
        let sent = VoicesStub.requests
        XCTAssertEqual(sent.map(\.host), Array(repeating: "mine.example", count: 4))
        XCTAssertEqual(sent.map { $0.authorization != nil }, [true, false, true, false])
        XCTAssertEqual(sent.last?.query, "type=comment%2Creview&page=1")
    }

    func test_should_readNeoDBSocialAnonymously_when_signedOut() async throws {
        VoicesStub.reset()
        VoicesStub.handler = { _ in (200, #"{"data":[]}"#) }
        let service = NeoDBVoicesService(api: NeoDBAPI(session: VoicesStub.session), urlSession: VoicesStub.session,
                                         session: { nil })
        let found = try await service.findAlbum(albumName: "Blonde", artist: nil)
        XCTAssertNil(found)
        XCTAssertEqual(VoicesStub.requests.first?.host, "neodb.social")
        XCTAssertNil(VoicesStub.requests.first?.authorization)
        let search = await service.searchUrl(albumName: "Blonde", artist: nil)
        XCTAssertEqual(search, "https://neodb.social/search?c=music&q=Blonde")
    }

    func test_should_fail_when_neoDbTurnsAwayTheAnonymousReadToo() async {
        VoicesStub.reset()
        VoicesStub.handler = { _ in (401, #"{"detail":"no"}"#) }
        let service = NeoDBVoicesService(api: NeoDBAPI(session: VoicesStub.session), urlSession: VoicesStub.session,
                                         session: { (instance: "https://mine.example", token: "t") })
        do {
            _ = try await service.voices(itemUuid: "u1", page: 1)
            XCTFail("expected a failure")
        } catch {
            XCTAssertEqual((error as? NeoDBError)?.code, 401)
        }
        XCTAssertEqual(VoicesStub.requests.count, 2)
    }

    func test_should_readOtherCataloguesScores_when_neoDbLinksThem() async {
        VoicesStub.reset()
        VoicesStub.handler = { request in
            switch request.path {
            case "/masters/1076839": return (200, #"{"main_release":42}"#)
            case "/releases/42": return (200, #"{"community":{"rating":{"average":4.31,"count":900}}}"#)
            default: return (200, #"{"rating":{"value":4.5,"votes-count":0}}"#) // no votes: no card
            }
        }
        let service = NeoDBVoicesService(api: NeoDBAPI(session: VoicesStub.session), urlSession: VoicesStub.session,
                                         session: { nil })
        var linked = album
        linked.discogsUrl = "https://www.discogs.com/master/1076839"
        linked.musicBrainzUrl = "https://musicbrainz.org/release-group/ad0e6676-3e55-4bd6-a9ed-7f6424fd46f6"
        let scores = await service.referenceScores(linked)
        XCTAssertEqual(scores, [NeoDBReferenceScore(source: "Discogs", value: 4.31, scale: 5, votes: 900,
                                                    url: "https://www.discogs.com/master/1076839")])
        XCTAssertTrue(VoicesStub.requests.allSatisfy { $0.userAgent == NeoDBVoicesService.userAgent })
    }
}

/// Answers every request of a URLSession built with it; records what was sent.
private final class VoicesStub: URLProtocol {
    struct Sent {
        var host: String
        var path: String
        var query: String?
        var authorization: String?
        var userAgent: String?
    }

    nonisolated(unsafe) static var handler: ((Sent) -> (Int, String))?
    nonisolated(unsafe) private static var sent: [Sent] = []
    private static let lock = NSLock()

    static var requests: [Sent] { lock.withLock { sent } }

    static func reset() {
        lock.withLock {
            sent = []
            handler = nil
        }
    }

    static var session: URLSession {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [VoicesStub.self]
        return URLSession(configuration: config)
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let url = request.url!
        let c = URLComponents(url: url, resolvingAgainstBaseURL: false)
        let entry = Sent(host: url.host ?? "", path: url.path, query: c?.percentEncodedQuery,
                         authorization: request.value(forHTTPHeaderField: "Authorization"),
                         userAgent: request.value(forHTTPHeaderField: "User-Agent"))
        Self.lock.withLock { Self.sent.append(entry) }
        let answer = Self.lock.withLock { Self.handler?(entry) } ?? (599, "no route")
        let response = HTTPURLResponse(url: url, statusCode: answer.0, httpVersion: "HTTP/1.1", headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(answer.1.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
