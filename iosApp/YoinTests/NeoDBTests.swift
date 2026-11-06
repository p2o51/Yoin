import XCTest
@testable import Yoin

/// NeoDB, ported from Android's NeoDBModelsTest, NeoDBCommentRoutingTest, AlbumRatingPushMarkTest,
/// AlbumNeoDbSyncTest and NeoDbRestoreGuardTest, plus the iOS OAuth steps. The network is a URLProtocol
/// stub: nothing here reaches a real NeoDB instance.
@MainActor
final class NeoDBTests: XCTestCase {
    private var dir: URL!
    private var albums: AlbumMemoryStore!
    private var pending: NeoDBPendingStore!
    private var mappings: NeoDBMappingStore!
    private var account: NeoDBAccount!
    private var defaults: UserDefaults!
    private var suite: String!

    private let instance = "https://neodb.test"
    private let album = Album(id: MediaId(provider: "subsonic", rawId: "al-1"), title: "Lantern Letters",
                              artist: "月光邮局", year: 2011, palette: CoverPalette(a: .red, b: .green, c: .blue),
                              tracks: [], rating: nil)

    override func setUp() async throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        albums = AlbumMemoryStore(fileURL: dir.appendingPathComponent("album-memories.json"))
        pending = NeoDBPendingStore(fileURL: dir.appendingPathComponent("neodb-pending.json"))
        mappings = NeoDBMappingStore(fileURL: dir.appendingPathComponent("neodb-mappings.json"))
        suite = "neodb-tests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)
        account = NeoDBAccount(tokens: NeoDBMemoryTokens(), defaults: defaults)
        Stub.reset()
    }

    override func tearDown() async throws {
        Stub.reset()
        defaults.removePersistentDomain(forName: suite)
        try? FileManager.default.removeItem(at: dir)
    }

    private var api: NeoDBAPI { NeoDBAPI(session: Stub.session) }

    private func service() -> NeoDBSyncService {
        NeoDBSyncService(api: api, account: account, albums: albums, pending: pending, mappings: mappings, now: { 100 })
    }

    private func signIn() { XCTAssertTrue(account.save(instance: instance, token: "token")) }

    /// A local row and its flags; the album maps to uuid-1.
    private func given(rating: Double, review: String?, ratingDirty: Bool, reviewDirty: Bool,
                       remote: String? = #"{"shelf_type":"complete","visibility":1,"rating_grade":7,"comment_text":"from the web","tags":["jazz"]}"#) {
        signIn()
        albums.putAlbum(.init(provider: "subsonic", albumId: "al-1", rating: rating, review: review,
                              reviewUpdatedAt: review == nil ? nil : 1, updatedAt: 1))
        if ratingDirty { pending.markRatingChanged(provider: "subsonic", albumId: "al-1") }
        if reviewDirty { pending.markReviewChanged(provider: "subsonic", albumId: "al-1") }
        mappings.upsert(externalId: "uuid-1", provider: "subsonic", entityId: "al-1", now: 1)
        Stub.route("GET /api/me/shelf/item/uuid-1", status: remote == nil ? 404 : 200, body: remote ?? "")
        Stub.route("POST /api/me/shelf/item/uuid-1", status: 200, body: #"{"message":"OK"}"#)
        Stub.route("DELETE /api/me/review/item/uuid-1", status: 204, body: "")
        Stub.route("POST /api/me/review/item/uuid-1", status: 200, body: #"{"uuid":"r-1"}"#)
    }

    private func markBody() throws -> [String: Any] {
        let request = try XCTUnwrap(Stub.requests.last { $0.method == "POST" && $0.path == "/api/me/shelf/item/uuid-1" })
        return try XCTUnwrap(JSONSerialization.jsonObject(with: request.body) as? [String: Any])
    }

    private func count(_ method: String, _ path: String) -> Int {
        Stub.requests.filter { $0.method == method && $0.path == path }.count
    }

    // MARK: Models (Android NeoDBModelsTest)

    func test_should_encodeVisibility_when_markIsPublic() throws {
        let data = try JSONEncoder().encode(NeoDBShelfMarkRequest(shelfType: "complete", visibility: 0, ratingGrade: 8))
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(json["visibility"] as? Int, 0)
        XCTAssertEqual(json["shelf_type"] as? String, "complete")
        XCTAssertEqual(json["rating_grade"] as? Int, 8)
        // Android's encodeDefaults=false: nil, empty tags and post_to_fediverse=false stay out.
        XCTAssertNil(json["comment_text"])
        XCTAssertNil(json["tags"])
        XCTAssertNil(json["post_to_fediverse"])
    }

    func test_should_sendAnEmptyComment_when_itClearsTheShortComment() throws {
        let data = try JSONEncoder().encode(NeoDBShelfMarkRequest(shelfType: "complete", visibility: 0, commentText: ""))
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(json["comment_text"] as? String, "")
    }

    func test_should_encodeVisibility_when_reviewIsPublic() throws {
        let data = try JSONEncoder().encode(NeoDBReviewRequest(visibility: 0, title: "bomb", body: "review body"))
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(json["visibility"] as? Int, 0)
        XCTAssertEqual(json["title"] as? String, "bomb")
        XCTAssertEqual(json["body"] as? String, "review body")
    }

    func test_should_decodeLeniently_when_fieldsAreMissing() throws {
        let item = try JSONDecoder().decode(NeoDBShelfItem.self, from: Data(#"{"rating_grade":6}"#.utf8))
        XCTAssertEqual(item.ratingGrade, 6)
        XCTAssertEqual(item.visibility, 0)
        XCTAssertEqual(item.tags, [])
    }

    // MARK: API

    func test_should_searchMusicWithBearer_when_lookingUpAnAlbum() async throws {
        Stub.route("GET /api/catalog/search", status: 200,
                   body: #"{"data":[{"uuid":"u-9","display_title":"Lantern Letters","category":"music"}]}"#)
        let hits = try await api.searchAlbum(instance: instance + "/", token: "t0k", query: "Lantern Letters 月光")
        XCTAssertEqual(hits.first?.uuid, "u-9")
        let request = try XCTUnwrap(Stub.requests.first)
        XCTAssertEqual(request.authorization, "Bearer t0k")
        XCTAssertEqual(request.query, "category=music&query=Lantern+Letters+%E6%9C%88%E5%85%89")
    }

    func test_should_retryLegacyCategory_when_musicIsRejected() async throws {
        Stub.handler = { request in
            if request.query?.hasPrefix("category=music") == true {
                return (422, #"{"detail":[{"loc":["query","category"],"msg":"value is not a valid enumeration member"}]}"#)
            }
            return (200, #"{"data":[{"uuid":"u-legacy"}]}"#)
        }
        let hits = try await api.searchAlbum(instance: instance, token: "t", query: "x")
        XCTAssertEqual(hits.map(\.uuid), ["u-legacy"])
        XCTAssertEqual(Stub.requests.count, 2)
        XCTAssertTrue(Stub.requests[1].query?.hasPrefix("category=album") == true)
    }

    func test_should_readTheServersMessage_when_aRequestFails() async {
        Stub.route("POST /api/me/shelf/item/u", status: 422,
                   body: #"{"detail":[{"loc":["body","mark","visibility"],"msg":"Field required"}]}"#)
        do {
            try await api.postShelfMark(instance: instance, token: "t", itemUuid: "u",
                                        body: NeoDBShelfMarkRequest(shelfType: "complete", visibility: 0))
            XCTFail("expected a failure")
        } catch let error as NeoDBError {
            XCTAssertEqual(error.code, 422)
            XCTAssertEqual(error.message, "body.mark.visibility: Field required")
        } catch {
            XCTFail("\(error)")
        }
        XCTAssertEqual(NeoDBAPI.parseErrorMessage(Data(#"{"detail":"Not authorized"}"#.utf8)), "Not authorized")
        XCTAssertEqual(NeoDBAPI.parseErrorMessage(Data(#"{"error":"invalid_grant"}"#.utf8)), "invalid_grant")
        XCTAssertNil(NeoDBAPI.parseErrorMessage(Data("<html>".utf8)))
    }

    func test_should_readNoMark_when_theItemIsNotOnTheShelf() async throws {
        Stub.route("GET /api/me/shelf/item/u", status: 404, body: #"{"detail":"Not Found"}"#)
        Stub.route("GET /api/me/review/item/u", status: 404, body: "")
        let mark = try await api.getShelfItem(instance: instance, token: "t", itemUuid: "u")
        let review = try await api.getMyReviewForItem(instance: instance, token: "t", itemUuid: "u")
        XCTAssertNil(mark)
        XCTAssertNil(review)
    }

    func test_should_registerWithAForm_when_signingIn() async throws {
        Stub.route("POST /api/v1/apps", status: 200, body: #"{"client_id":"cid","client_secret":"sec","id":"1"}"#)
        let r = try await api.registerOAuthApp(instance: instance, redirectURI: NeoDBOAuth.callbackURI,
                                               clientName: "Yoin", website: NeoDBOAuth.website)
        XCTAssertEqual(r, NeoDBClientRegistration(clientId: "cid", clientSecret: "sec"))
        let request = try XCTUnwrap(Stub.requests.first)
        XCTAssertEqual(request.contentType, "application/x-www-form-urlencoded")
        XCTAssertEqual(String(decoding: request.body, as: UTF8.self),
                       "client_name=Yoin&redirect_uris=yoin%3A%2F%2Fauth%2Fneodb%2Fcallback&website=https%3A%2F%2Fgithub.com%2Fp2o51%2FYoin")
        XCTAssertNil(request.authorization)
    }

    // MARK: Comment routing (Android NeoDBCommentRoutingTest)

    func test_should_sendAShortTextAsTheMarksComment_when_itFits() async throws {
        given(rating: 8.4, review: "副歌那句一出来，整个下午都亮了。", ratingDirty: true, reviewDirty: true)

        try await service().push(album)

        let mark = try markBody()
        XCTAssertEqual(mark["comment_text"] as? String, "副歌那句一出来，整个下午都亮了。")
        XCTAssertEqual(mark["rating_grade"] as? Int, 8)
        // The rest of the web's Mark stays.
        XCTAssertEqual(mark["shelf_type"] as? String, "complete")
        XCTAssertEqual(mark["visibility"] as? Int, 1)
        XCTAssertEqual(mark["tags"] as? [String], ["jazz"])
        XCTAssertEqual(count("DELETE", "/api/me/review/item/uuid-1"), 1)
        XCTAssertEqual(count("POST", "/api/me/review/item/uuid-1"), 0)
        let flags = pending.entry(provider: "subsonic", albumId: "al-1")
        XCTAssertFalse(flags?.isDirty ?? false)
    }

    func test_should_postAReviewAndClearTheComment_when_theTextIsLong() async throws {
        let long = String(repeating: "长", count: neoDBShortCommentMax + 1)
        given(rating: 0, review: long, ratingDirty: false, reviewDirty: true)

        try await service().push(album)

        let mark = try markBody()
        XCTAssertEqual(mark["comment_text"] as? String, "")
        // The rating was not touched here: NeoDB's own grade stays.
        XCTAssertEqual(mark["rating_grade"] as? Int, 7)
        let review = try XCTUnwrap(Stub.requests.last { $0.method == "POST" && $0.path == "/api/me/review/item/uuid-1" })
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: review.body) as? [String: Any])
        XCTAssertEqual(body["body"] as? String, long)
        XCTAssertEqual(body["title"] as? String, "Lantern Letters")
        XCTAssertEqual(count("DELETE", "/api/me/review/item/uuid-1"), 0)
        XCTAssertEqual(pending.entry(provider: "subsonic", albumId: "al-1")?.reviewUuid, "r-1")
        XCTAssertFalse(pending.entry(provider: "subsonic", albumId: "al-1")?.isDirty ?? false)
    }

    func test_should_keepTheWebsComment_when_onlyTheRatingChanged() async throws {
        given(rating: 9, review: "anything", ratingDirty: true, reviewDirty: false)

        try await service().push(album)

        let mark = try markBody()
        XCTAssertEqual(mark["comment_text"] as? String, "from the web")
        XCTAssertEqual(mark["rating_grade"] as? Int, 9)
        XCTAssertEqual(count("POST", "/api/me/review/item/uuid-1"), 0)
        XCTAssertEqual(count("DELETE", "/api/me/review/item/uuid-1"), 0)
    }

    func test_should_treatAMissingReviewAsDeleted_when_clearingTheWords() async throws {
        given(rating: 6, review: nil, ratingDirty: false, reviewDirty: true, remote: nil)
        Stub.route("DELETE /api/me/review/item/uuid-1", status: 404, body: "")

        try await service().push(album)

        let mark = try markBody()
        XCTAssertEqual(mark["shelf_type"] as? String, "complete")
        XCTAssertEqual(mark["visibility"] as? Int, 0)
        // No grade on NeoDB yet: the local one goes.
        XCTAssertEqual(mark["rating_grade"] as? Int, 6)
        XCTAssertNil(pending.entry(provider: "subsonic", albumId: "al-1"))
    }

    func test_should_keepTheFlag_when_deletingTheReviewFails() async throws {
        given(rating: 6, review: "short", ratingDirty: false, reviewDirty: true)
        Stub.route("DELETE /api/me/review/item/uuid-1", status: 503, body: "")

        do {
            try await service().push(album)
            XCTFail("expected a failure")
        } catch {}
        XCTAssertTrue(pending.entry(provider: "subsonic", albumId: "al-1")?.reviewNeedsSync ?? false)
    }

    func test_should_sendNothing_when_nothingChanged() async throws {
        given(rating: 6, review: "short", ratingDirty: false, reviewDirty: false)
        try await service().push(album)
        XCTAssertTrue(Stub.requests.isEmpty)
    }

    func test_should_searchAndRemember_when_theAlbumIsNotMappedYet() async throws {
        signIn()
        albums.putAlbum(.init(provider: "subsonic", albumId: "al-1", rating: 7, review: nil, reviewUpdatedAt: nil, updatedAt: 1))
        pending.markRatingChanged(provider: "subsonic", albumId: "al-1")
        Stub.route("GET /api/catalog/search", status: 200, body: #"{"data":[{"uuid":"found"},{"uuid":"second"}]}"#)
        Stub.route("GET /api/me/shelf/item/found", status: 404, body: "")
        Stub.route("POST /api/me/shelf/item/found", status: 200, body: "{}")

        try await service().push(album)

        XCTAssertEqual(mappings.externalId(provider: "subsonic", entityId: "al-1"), "found")
        XCTAssertEqual(NeoDBMappingStore(fileURL: dir.appendingPathComponent("neodb-mappings.json"))
            .externalId(provider: "subsonic", entityId: "al-1"), "found")
        XCTAssertEqual(Stub.requests.first?.query, "category=music&query=Lantern+Letters+%E6%9C%88%E5%85%89%E9%82%AE%E5%B1%80")
    }

    func test_should_fail_when_notSignedIn() async {
        albums.putAlbum(.init(provider: "subsonic", albumId: "al-1", rating: 7, review: nil, reviewUpdatedAt: nil, updatedAt: 1))
        pending.markRatingChanged(provider: "subsonic", albumId: "al-1")
        do {
            try await service().push(album)
            XCTFail("expected a failure")
        } catch {
            XCTAssertEqual(error as? NeoDBSyncService.Failure, .notSignedIn)
        }
        XCTAssertTrue(Stub.requests.isEmpty)
    }

    func test_should_pullTheShortComment_when_thereIsNoReview() async throws {
        given(rating: 0, review: nil, ratingDirty: false, reviewDirty: false)
        Stub.route("GET /api/me/review/item/uuid-1", status: 404, body: "")

        let pulled = try await service().pull(album)

        XCTAssertEqual(pulled?.review, "from the web")
        XCTAssertEqual(pulled?.rating, 7)
        XCTAssertEqual(albums.album(provider: "subsonic", albumId: "al-1")?.review, "from the web")
    }

    func test_should_preferTheReviewAndKeepLocalDrafts_when_pulling() async throws {
        given(rating: 8.5, review: "draft", ratingDirty: true, reviewDirty: true)
        Stub.route("GET /api/me/review/item/uuid-1", status: 200, body: #"{"uuid":"r-9","body":"long words"}"#)

        let pulled = try await service().pull(album)

        // Unpushed local edits win over NeoDB.
        XCTAssertEqual(pulled?.rating, 8.5)
        XCTAssertEqual(pulled?.review, "draft")
        XCTAssertTrue(pending.entry(provider: "subsonic", albumId: "al-1")?.isDirty ?? false)

        pending.ratingPushed(provider: "subsonic", albumId: "al-1")
        pending.reviewPushed(provider: "subsonic", albumId: "al-1", reviewUuid: nil)
        let again = try await service().pull(album)
        XCTAssertEqual(again?.rating, 7)
        XCTAssertEqual(again?.review, "long words")
        XCTAssertEqual(pending.entry(provider: "subsonic", albumId: "al-1")?.reviewUuid, "r-9")
    }

    func test_should_countCharactersNotBytes_when_judgingAShortComment() {
        XCTAssertTrue(isNeoDBShortComment(String(repeating: "音", count: neoDBShortCommentMax)))
        XCTAssertFalse(isNeoDBShortComment(String(repeating: "音", count: neoDBShortCommentMax + 1)))
        // An emoji is one character, two UTF-16 units.
        XCTAssertTrue(isNeoDBShortComment(String(repeating: "🎧", count: neoDBShortCommentMax)))
    }

    func test_should_roundHalfUp_when_gradingARating() {
        XCTAssertEqual(NeoDBSyncService.grade(8.4), 8)
        XCTAssertEqual(NeoDBSyncService.grade(8.5), 9)
        XCTAssertEqual(NeoDBSyncService.grade(10), 10)
        XCTAssertEqual(NeoDBSyncService.grade(0), 0)
    }

    // MARK: Push marks (Android AlbumRatingPushMarkTest)

    func test_should_keepTheNewerEditDirty_when_itLandedDuringThePush() async throws {
        given(rating: 7, review: "first words", ratingDirty: true, reviewDirty: true)
        let albums = self.albums!
        // While the Mark is in flight the user sets 9.0 and rewrites the words.
        Stub.onRequest = { request in
            guard request.method == "POST", request.path == "/api/me/shelf/item/uuid-1" else { return }
            _ = DispatchQueue.main.sync {
                MainActor.assumeIsolated {
                    albums.putAlbum(.init(provider: "subsonic", albumId: "al-1", rating: 9, review: "second words",
                                          reviewUpdatedAt: 2, updatedAt: 2))
                }
            }
        }

        try await service().push(album)

        let flags = try XCTUnwrap(pending.entry(provider: "subsonic", albumId: "al-1"))
        XCTAssertTrue(flags.ratingNeedsSync)
        XCTAssertTrue(flags.reviewNeedsSync)
        XCTAssertEqual(albums.album(provider: "subsonic", albumId: "al-1")?.rating, 9)
        XCTAssertEqual(albums.album(provider: "subsonic", albumId: "al-1")?.review, "second words")
    }

    // MARK: Restore (Android NeoDbRestoreGuardTest)

    /// Android drops the old phone's flags after a restore; here they never travel: the file is
    /// excluded from backup.
    func test_should_keepTheFlagsOutOfBackups_when_written() throws {
        pending.markRatingChanged(provider: "subsonic", albumId: "al-1")
        let values = try pending.fileURL.resourceValues(forKeys: [.isExcludedFromBackupKey])
        XCTAssertEqual(values.isExcludedFromBackup, true)
        let reread = NeoDBPendingStore(fileURL: pending.fileURL)
        XCTAssertTrue(reread.entry(provider: "subsonic", albumId: "al-1")?.ratingNeedsSync ?? false)
    }

    // MARK: The rate sheet's line (Android AlbumNeoDbSyncTest)

    private func record(rating: Double = 0, review: String? = nil) -> AlbumMemoryStore.AlbumRecord {
        .init(provider: "subsonic", albumId: "al-1", rating: rating, review: review, reviewUpdatedAt: nil, updatedAt: 1)
    }

    private func flags(rating: Bool = false, review: Bool = false) -> NeoDBPendingStore.Entry {
        .init(scope: nil, provider: "subsonic", albumId: "al-1", ratingNeedsSync: rating, reviewNeedsSync: review)
    }

    func test_should_offerSignIn_when_noNeoDBAccount() {
        XCTAssertEqual(AlbumNeoDBState.of(configured: false, record: record(rating: 8), pending: flags(rating: true)), .signedOut)
        XCTAssertEqual(AlbumNeoDBState.signedOut.label, loc("Sign in to sync"))
    }

    func test_should_waitForTheClose_when_aChangeIsNotSentYet() {
        // Rated before signing in counts too: it goes on the next close.
        XCTAssertEqual(AlbumNeoDBState.of(configured: true, record: record(rating: 8), pending: flags(rating: true)), .pending)
        XCTAssertEqual(AlbumNeoDBState.of(configured: true, record: record(review: ""), pending: flags(review: true)), .pending)
        XCTAssertEqual(AlbumNeoDBState.pending.label, "")
        XCTAssertFalse(AlbumNeoDBState.pending.isShown)
    }

    func test_should_readSynced_when_theRowIsCleanAndHasSomething() {
        XCTAssertEqual(AlbumNeoDBState.of(configured: true, record: record(rating: 7), pending: nil), .synced)
        XCTAssertEqual(AlbumNeoDBState.of(configured: true, record: record(review: "good"), pending: flags()), .synced)
    }

    func test_should_readIdle_when_nothingIsWrittenYet() {
        XCTAssertEqual(AlbumNeoDBState.of(configured: true, record: nil, pending: nil), .idle)
        XCTAssertEqual(AlbumNeoDBState.of(configured: true, record: record(), pending: nil), .idle)
        XCTAssertEqual(AlbumNeoDBState.idle.label, "")
    }

    func test_should_nameTheFailure_when_syncFails() {
        XCTAssertEqual(AlbumNeoDBState.failed.label, loc("Couldn't sync"))
    }

    func test_should_pushOnClose_when_signedInAndSomethingWaits() async {
        given(rating: 8, review: nil, ratingDirty: true, reviewDirty: false)
        var toasts: [String] = []
        let sync = AlbumNeoDBSync(service: { [unowned self] in service() }, account: account, albums: albums,
                                  pending: pending, onFailure: { toasts.append($0) })
        sync.sheetOpened(album)
        XCTAssertEqual(sync.state(album), .pending)

        await sync.sheetClosed(album)

        XCTAssertEqual(sync.state(album), .synced)
        XCTAssertEqual(count("POST", "/api/me/shelf/item/uuid-1"), 1)
        XCTAssertTrue(toasts.isEmpty)
        // Words typed after a sync: pending again, not a stale Synced.
        sync.edited(album, unsavedWords: true)
        XCTAssertEqual(sync.state(album), .pending)
    }

    func test_should_offerRetryAndSaySo_when_thePushFails() async {
        given(rating: 8, review: nil, ratingDirty: true, reviewDirty: false)
        Stub.route("POST /api/me/shelf/item/uuid-1", status: 500, body: "")
        var toasts: [String] = []
        let sync = AlbumNeoDBSync(service: { [unowned self] in service() }, account: account, albums: albums,
                                  pending: pending, onFailure: { toasts.append($0) })

        await sync.sheetClosed(album)

        XCTAssertEqual(sync.state(album), .failed)
        XCTAssertEqual(toasts, [loc("Couldn't sync to NeoDB")])
        // A failure keeps its own line while the sheet is edited.
        sync.edited(album, unsavedWords: true)
        XCTAssertEqual(sync.state(album), .failed)

        Stub.route("POST /api/me/shelf/item/uuid-1", status: 200, body: "{}")
        await sync.retry(album)
        XCTAssertEqual(sync.state(album), .synced)
    }

    func test_should_sendNothing_when_signedOut() async {
        albums.putAlbum(record(rating: 8))
        pending.markRatingChanged(provider: "subsonic", albumId: "al-1")
        let sync = AlbumNeoDBSync(service: { [unowned self] in service() }, account: account, albums: albums,
                                  pending: pending, onFailure: { _ in })
        await sync.sheetClosed(album)
        XCTAssertEqual(sync.state(album), .signedOut)
        XCTAssertTrue(Stub.requests.isEmpty)
    }

    // MARK: Account

    func test_should_normalizeTheInstance_when_saved() {
        XCTAssertEqual(NeoDBAccount.normalizeInstance("neodb.social/"), "https://neodb.social")
        XCTAssertEqual(NeoDBAccount.normalizeInstance("  https://eggplant.place/users/me "), "https://eggplant.place")
        XCTAssertEqual(NeoDBAccount.normalizeInstance("http://localhost:8765"), "http://localhost:8765")
        XCTAssertEqual(NeoDBAccount.normalizeInstance(""), NeoDBAccount.defaultInstance)
        XCTAssertNil(NeoDBAccount.normalizeInstance("", blankIsDefault: false))
        XCTAssertNil(NeoDBAccount.normalizeInstance("https://not a host"))
    }

    func test_should_keepTheInstance_when_signingOut() {
        let tokens = NeoDBMemoryTokens()
        let a = NeoDBAccount(tokens: tokens, defaults: defaults)
        XCTAssertEqual(a.instance, NeoDBAccount.defaultInstance)
        XCTAssertFalse(a.isSignedIn)
        XCTAssertTrue(a.save(instance: "eggplant.place", token: "  tok  "))
        XCTAssertTrue(a.isSignedIn)
        XCTAssertEqual(tokens.load(), "tok")
        // A second launch reads both back.
        let reread = NeoDBAccount(tokens: tokens, defaults: defaults)
        XCTAssertEqual(reread.instance, "https://eggplant.place")
        XCTAssertEqual(reread.token, "tok")
        a.signOut()
        XCTAssertFalse(a.isSignedIn)
        XCTAssertNil(tokens.load())
        XCTAssertEqual(a.instance, "https://eggplant.place")
        XCTAssertFalse(a.save(instance: "https://not a host", token: "x"))
    }

    // MARK: OAuth

    func test_should_buildTheAuthorizeURL_when_signingIn() throws {
        let url = try XCTUnwrap(NeoDBOAuth.authorizationURL(instance: "https://neodb.social", clientId: "c i d", state: "s-1"))
        let c = try XCTUnwrap(URLComponents(url: url, resolvingAgainstBaseURL: false))
        XCTAssertEqual(c.scheme, "https")
        XCTAssertEqual(c.host, "neodb.social")
        XCTAssertEqual(c.path, "/oauth/authorize")
        let q = Dictionary(uniqueKeysWithValues: (c.queryItems ?? []).map { ($0.name, $0.value ?? "") })
        XCTAssertEqual(q, ["response_type": "code", "client_id": "c i d", "redirect_uri": "yoin://auth/neodb/callback",
                           "scope": "read write", "state": "s-1"])
    }

    func test_should_readTheCallback_when_neoDBRedirects() throws {
        func parse(_ s: String) throws -> NeoDBOAuth.Callback {
            NeoDBOAuth.parseCallback(try XCTUnwrap(URL(string: s)), expectedState: "s-1")
        }
        XCTAssertEqual(try parse("yoin://auth/neodb/callback?code=abc&state=s-1"), .success("abc"))
        XCTAssertEqual(try parse("yoin://auth/neodb/callback?code=abc&state=other"), .failure(loc("NeoDB state nonce mismatch")))
        XCTAssertEqual(try parse("yoin://auth/neodb/callback?error=access_denied"), .failure(loc("NeoDB auth error: \("access_denied")")))
        XCTAssertEqual(try parse("yoin://auth/neodb/callback?state=s-1"), .failure(loc("NeoDB redirect missing required parameters")))
        XCTAssertEqual(try parse("yoin://auth/spotify/callback?code=abc&state=s-1"), .failure(loc("NeoDB redirect missing required parameters")))
    }

    func test_should_registerAuthorizeAndExchange_when_signingIn() async throws {
        Stub.route("POST /api/v1/apps", status: 200, body: #"{"client_id":"cid","client_secret":"sec"}"#)
        Stub.route("POST /oauth/token", status: 200, body: #"{"access_token":"tok-1","token_type":"Bearer","scope":"read write"}"#)
        let web = FakeWeb(answer: .success(URL(string: "yoin://auth/neodb/callback?code=the-code&state=s-1")!))
        let oauth = NeoDBOAuth(api: api, authenticator: web, makeState: { "s-1" })

        let result = await oauth.signIn(instance: "neodb.test/")

        XCTAssertEqual(result, .success(instance: "https://neodb.test", accessToken: "tok-1"))
        XCTAssertEqual(web.scheme, "yoin")
        XCTAssertEqual(web.url?.absoluteString,
                       "https://neodb.test/oauth/authorize?response_type=code&client_id=cid&redirect_uri=yoin://auth/neodb/callback&scope=read%20write&state=s-1")
        let exchange = try XCTUnwrap(Stub.requests.last)
        XCTAssertEqual(exchange.path, "/oauth/token")
        XCTAssertEqual(String(decoding: exchange.body, as: UTF8.self),
                       "client_id=cid&client_secret=sec&code=the-code&redirect_uri=yoin%3A%2F%2Fauth%2Fneodb%2Fcallback&grant_type=authorization_code")
    }

    func test_should_stopQuietly_when_thePageIsClosed() async {
        Stub.route("POST /api/v1/apps", status: 200, body: #"{"client_id":"cid","client_secret":"sec"}"#)
        let oauth = NeoDBOAuth(api: api, authenticator: FakeWeb(answer: .failure(NeoDBOAuth.Cancelled())), makeState: { "s" })
        let result = await oauth.signIn(instance: "neodb.test")
        XCTAssertEqual(result, .cancelled)
        XCTAssertEqual(Stub.requests.count, 1)
    }

    func test_should_sayWhy_when_registrationOrStateFails() async {
        Stub.route("POST /api/v1/apps", status: 422, body: #"{"error":"Validation failed: Redirect URI must be an absolute URI."}"#)
        let failing = NeoDBOAuth(api: api, authenticator: FakeWeb(answer: .failure(NeoDBOAuth.Cancelled())), makeState: { "s" })
        let registration = await failing.signIn(instance: "neodb.test")
        XCTAssertEqual(registration, .failure("Validation failed: Redirect URI must be an absolute URI."))

        let invalid = await failing.signIn(instance: "  ")
        XCTAssertEqual(invalid, .failure(loc("Invalid NeoDB URL. Include a valid host.")))

        Stub.route("POST /api/v1/apps", status: 200, body: #"{"client_id":"cid","client_secret":"sec"}"#)
        let forged = NeoDBOAuth(api: api, authenticator: FakeWeb(answer: .success(URL(string: "yoin://auth/neodb/callback?code=c&state=forged")!)),
                                makeState: { "s" })
        let mismatch = await forged.signIn(instance: "neodb.test")
        XCTAssertEqual(mismatch, .failure(loc("NeoDB state nonce mismatch")))
        XCTAssertFalse(Stub.requests.contains { $0.path == "/oauth/token" })
    }
}

// MARK: - Fakes

@MainActor
private final class FakeWeb: NeoDBWebAuthenticating {
    let answer: Result<URL, Error>
    var url: URL?
    var scheme: String?

    init(answer: Result<URL, Error>) { self.answer = answer }

    func authenticate(url: URL, callbackScheme: String) async throws -> URL {
        self.url = url
        scheme = callbackScheme
        return try answer.get()
    }
}

/// Answers every request of a URLSession built with it; records what was sent.
private final class Stub: URLProtocol {
    struct Sent {
        var method: String
        var path: String
        var query: String?
        var authorization: String?
        var contentType: String?
        var body: Data
    }

    nonisolated(unsafe) static var routes: [String: (Int, String)] = [:]
    nonisolated(unsafe) static var handler: ((Sent) -> (Int, String))?
    nonisolated(unsafe) static var onRequest: ((Sent) -> Void)?
    nonisolated(unsafe) private static var sent: [Sent] = []
    private static let lock = NSLock()

    static var requests: [Sent] { lock.withLock { sent } }

    static func route(_ key: String, status: Int, body: String) {
        lock.withLock { routes[key] = (status, body) }
    }

    static func reset() {
        lock.withLock {
            routes = [:]
            sent = []
            handler = nil
            onRequest = nil
        }
    }

    static var session: URLSession {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [Stub.self]
        return URLSession(configuration: config)
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let url = request.url!
        let c = URLComponents(url: url, resolvingAgainstBaseURL: false)
        let entry = Sent(method: request.httpMethod ?? "GET", path: url.path, query: c?.percentEncodedQuery,
                         authorization: request.value(forHTTPHeaderField: "Authorization"),
                         contentType: request.value(forHTTPHeaderField: "Content-Type"), body: Self.body(of: request))
        Self.lock.withLock { Self.sent.append(entry) }
        Self.onRequest?(entry)
        let answer = Self.lock.withLock { Self.handler?(entry) ?? Self.routes["\(entry.method) \(entry.path)"] } ?? (599, "no route")
        let response = HTTPURLResponse(url: url, statusCode: answer.0, httpVersion: "HTTP/1.1", headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(answer.1.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}

    private static func body(of request: URLRequest) -> Data {
        if let body = request.httpBody { return body }
        guard let stream = request.httpBodyStream else { return Data() }
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 4096)
        while stream.hasBytesAvailable {
            let n = stream.read(&buffer, maxLength: buffer.count)
            if n <= 0 { break }
            data.append(buffer, count: n)
        }
        return data
    }
}
