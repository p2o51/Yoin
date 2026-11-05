import XCTest
import YoinShared
@testable import Yoin

/// Spotify playlist writes, backups and restores (Codex N15, N16) against recorded Web API answers
/// (Android SpotifyApiClient's endpoints and bodies).
@MainActor
final class SpotifyPlaylistsTests: XCTestCase {
    override func setUp() { PlaylistStub.reset() }

    private func source() -> SpotifySource {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.protocolClasses = [PlaylistStub.self]
        let api = SpotifyWebAPI(token: { "tok" }, session: URLSession(configuration: cfg), sleep: { _ in })
        let s = SpotifySource(api: api)
        s.userId = "me1"
        return s
    }

    private func body(_ i: Int) -> [String: Any] {
        let r = PlaylistStub.requests[i]
        return (try? JSONSerialization.jsonObject(with: r.body) as? [String: Any]) ?? [:]
    }

    nonisolated private static func track(_ id: String, _ name: String, isrc: String? = nil, artists: [String] = ["Kiri Ono"]) -> String {
        let a = artists.map { #"{"id":"ar-\#($0.count)","name":"\#($0)"}"# }.joined(separator: ",")
        let ext = isrc.map { #","external_ids":{"isrc":"\#($0)"}"# } ?? ""
        return #"{"id":"\#(id)","name":"\#(name)","type":"track","duration_ms":201500,"artists":[\#(a)],"album":{"id":"al1","name":"Harbor Lights"}\#(ext)}"#
    }

    // MARK: Writes

    func test_should_createAPrivatePlaylistOnMe_when_creating() async throws {
        PlaylistStub.route = { _ in .init(status: 201, body: #"{"id":"new1","name":"Demo Mix","owner":{"id":"me1"},"snapshot_id":"s0"}"#) }
        let made = try await source().createPlaylist(name: "Demo Mix", description: "  Windows down ")
        XCTAssertEqual(made.id, "new1")
        let r = PlaylistStub.requests[0]
        XCTAssertEqual(r.method, "POST")
        XCTAssertEqual(r.url.path, "/v1/me/playlists")
        XCTAssertEqual(body(0)["name"] as? String, "Demo Mix")
        XCTAssertEqual(body(0)["public"] as? Bool, false)
        XCTAssertEqual(body(0)["description"] as? String, "Windows down")
        XCTAssertEqual(SpotifySource.owned(made, by: "me1"), true)
    }

    func test_should_postTrackURIsInBatchesAndAnswerTheSnapshot_when_adding() async throws {
        PlaylistStub.route = { _ in .init(status: 201, body: #"{"snapshot_id":"s\#(PlaylistStub.requests.count)"}"#) }
        let ids = (0..<130).map { "t\($0)" }
        let snapshot = try await source().addTracks(ids, to: "p1")
        XCTAssertEqual(PlaylistStub.requests.map(\.method), ["POST", "POST"])
        XCTAssertEqual(PlaylistStub.requests[0].url.path, "/v1/playlists/p1/items")
        let first = body(0)["uris"] as? [String]
        XCTAssertEqual(first?.count, 100)
        XCTAssertEqual(first?.first, "spotify:track:t0")
        XCTAssertEqual((body(1)["uris"] as? [String])?.last, "spotify:track:t129")
        XCTAssertEqual(snapshot, "s2")
    }

    func test_should_putTheNewName_when_renaming() async throws {
        PlaylistStub.route = { _ in .init(status: 200, body: "") }
        try await source().renamePlaylist("p1", name: "Rainy Commute")
        XCTAssertEqual(PlaylistStub.requests[0].method, "PUT")
        XCTAssertEqual(PlaylistStub.requests[0].url.path, "/v1/playlists/p1")
        XCTAssertEqual(body(0) as NSDictionary, ["name": "Rainy Commute"] as NSDictionary)
    }

    func test_should_unfollowFromTheLibrary_when_deleting() async throws {
        PlaylistStub.route = { _ in .init(status: 200, body: "") }
        try await source().unfollowPlaylist("p1")
        let r = PlaylistStub.requests[0]
        XCTAssertEqual(r.method, "DELETE")
        XCTAssertEqual(r.url.path, "/v1/me/library")
        XCTAssertEqual(URLComponents(url: r.url, resolvingAgainstBaseURL: false)?.queryItems?.first?.value,
                       "spotify:playlist:p1")
    }

    /// A playlist holding [A, B, local file, A]: each row keeps its own position, so removing the second A
    /// names position 3 only, against the snapshot it was read at.
    func test_should_removeTheOneOccurrenceByPositionAndSnapshot_when_aSongIsTwice() async throws {
        let items = [Self.track("A", "Neon Saints"), Self.track("B", "Undertow"),
                     #"{"id":null,"name":"Voice memo","is_local":true,"type":"track","artists":[]}"#,
                     Self.track("A", "Neon Saints")].map { #"{"item":\#($0)}"# }.joined(separator: ",")
        PlaylistStub.route = { r in
            if r.method == "DELETE" { return .init(status: 200, body: #"{"snapshot_id":"s2"}"#) }
            if r.url.path.hasSuffix("/library/contains") {
                let n = (URLComponents(url: r.url, resolvingAgainstBaseURL: false)?.percentEncodedQuery ?? "")
                    .components(separatedBy: ",").count
                return .init(status: 200, body: "[" + Array(repeating: "false", count: n).joined(separator: ",") + "]")
            }
            return .init(status: 200, body: #"{"id":"p1","name":"Mix","owner":{"id":"me1"},"collaborative":false,"snapshot_id":"s1","items":{"items":[\#(items)],"next":null,"total":4}}"#)
        }
        let s = source()
        let read = try await s.playlist("p1")
        let page = try XCTUnwrap(read)
        XCTAssertEqual(page.entries.map(\.track.id.rawId), ["A", "B", "A"])
        XCTAssertEqual(page.positions, [0, 1, 3])
        XCTAssertEqual(page.snapshotId, "s1")
        XCTAssertEqual(page.ownedByMe, true)
        XCTAssertFalse(page.collaborative)

        PlaylistStub.reset()
        let next = try await s.removeTracks([.init(trackRawId: "A", position: 3)], from: "p1", snapshotId: page.snapshotId)
        XCTAssertEqual(next, "s2")
        let r = PlaylistStub.requests[0]
        XCTAssertEqual(r.method, "DELETE")
        XCTAssertEqual(r.url.path, "/v1/playlists/p1/items")
        XCTAssertEqual(body(0)["snapshot_id"] as? String, "s1")
        let tracks = try XCTUnwrap(body(0)["tracks"] as? [[String: Any]])
        XCTAssertEqual(tracks.count, 1)
        XCTAssertEqual(tracks[0]["uri"] as? String, "spotify:track:A")
        XCTAssertEqual(tracks[0]["positions"] as? [Int], [3])

        // One item per song, its positions sorted (Android groups by URI).
        let grouped = SpotifySource.removalBody([.init(trackRawId: "A", position: 3), .init(trackRawId: "B", position: 1),
                                                 .init(trackRawId: "A", position: 0)], snapshotId: nil)
        let rows = try XCTUnwrap(grouped["tracks"] as? [[String: Any]])
        XCTAssertEqual(rows.map { $0["uri"] as? String }, ["spotify:track:A", "spotify:track:B"])
        XCTAssertEqual(rows[0]["positions"] as? [Int], [0, 3])
        XCTAssertNil(grouped["snapshot_id"])

        // After the first A goes, the server numbers the later items one up.
        var state = PlaylistActions.SpotifyServerState(entries: page.entries, positions: [0, 1, 3], snapshot: "s1")
        state.removeRow(0)
        XCTAssertEqual(state.positions, [0, 2])
        XCTAssertEqual(state.entries.map(\.track.id.rawId), ["B", "A"])
    }

    /// Only the user's own playlists are renamed or deleted; a collaborative one also takes songs.
    func test_should_editOnlyOwnedOrCollaborative_when_checkingOwnership() throws {
        func dto(_ json: String) throws -> SpotifyDTO.Playlist {
            try SpotifyWebAPI.decoder.decode(SpotifyDTO.Playlist.self, from: Data(json.utf8))
        }
        let mine = try dto(#"{"id":"1","owner":{"id":"me1"}}"#)
        let shared = try dto(#"{"id":"2","owner":{"id":"them"},"collaborative":true}"#)
        let theirs = try dto(#"{"id":"3","owner":{"id":"them"},"collaborative":false}"#)
        XCTAssertEqual(SpotifySource.owned(mine, by: "me1"), true)
        XCTAssertEqual(SpotifySource.owned(shared, by: "me1"), false)
        XCTAssertNil(SpotifySource.owned(mine, by: nil))
        XCTAssertEqual([mine, shared, theirs].map { SpotifySource.takesSongs($0, me: "me1") }, [true, true, false])
    }

    // MARK: Backup

    /// Every song in order, repeats kept; a local file, an episode and a removed entry are kept as
    /// unreadable at their places; the ISRC goes into the backup upper-cased.
    func test_should_keepUnreadableEntriesAtTheirPlaces_when_backingUpASpotifyPlaylist() async throws {
        let items = [Self.track("A", "Neon Saints", isrc: "usrc17607839", artists: ["Kiri Ono", "The Meridians"]),
                     #"{"item":{"id":null,"name":"Voice memo","is_local":true,"type":"track","artists":[{"name":"Me"}]}}"#,
                     #"{"item":null,"track":null}"#,
                     #"{"item":{"id":"e1","name":"Episode 4","type":"episode"}}"#,
                     Self.track("A", "Neon Saints", isrc: "usrc17607839", artists: ["Kiri Ono", "The Meridians"])]
            .map { $0.hasPrefix(#"{"id""#) ? #"{"item":\#($0)}"# : $0 }
        PlaylistStub.route = { r in
            if r.url.path == "/v1/playlists/p1" {
                return .init(status: 200, body: #"{"id":"p1","name":"Night drive","description":"Windows &amp; down","owner":{"id":"me1"},"items":{"href":"x","total":5}}"#)
            }
            return .init(status: 200, body: #"{"items":[\#(items.joined(separator: ","))],"next":null,"total":5}"#)
        }
        let read = try await source().playlistForBackup("p1")
        let snap = try XCTUnwrap(read)
        XCTAssertEqual(PlaylistStub.requests.last?.url.path, "/v1/playlists/p1/items")
        XCTAssertEqual(snap.id, "spotify:p1")
        XCTAssertEqual(snap.comment, "Windows & down")
        XCTAssertEqual(snap.tracks.map(\.id), ["spotify:A", "spotify:A"])
        XCTAssertEqual(snap.tracks[0].artists, ["Kiri Ono", "The Meridians"])
        XCTAssertEqual(snap.tracks[0].isrc, "USRC17607839")
        XCTAssertEqual(snap.tracks[0].durationSec?.intValue, 201)
        XCTAssertEqual(snap.unreadable.map(\.position), [1, 2, 3])
        XCTAssertEqual(snap.unreadable.map(\.reason), [.localfile, .unavailable, .episode])
        XCTAssertEqual(snap.unreadable[0].title, "Voice memo")
        XCTAssertEqual(snap.unreadable[0].artist, "Me")

        // As the backup file keeps it: the wire names Android writes.
        let content = PlaylistSnapshots.shared.content(playlist: snap)
        XCTAssertEqual(content.tracks.map(\.sourceTrackId), ["spotify:A", "spotify:A"])
        XCTAssertEqual(content.unexported.map(\.reason), ["local_file", "unavailable", "episode"])
        XCTAssertEqual(content.description_, "Windows & down")
    }

    /// Someone else's playlist (403 for its items) isn't backed up as an empty one.
    func test_should_refuse_when_spotifyRefusesTheSongs() async {
        PlaylistStub.route = { r in
            r.url.path == "/v1/playlists/p9"
                ? .init(status: 200, body: #"{"id":"p9","name":"Theirs","owner":{"id":"them"}}"#)
                : .init(status: 403, body: #"{"error":{"status":403,"message":"Forbidden"}}"#)
        }
        do {
            _ = try await source().playlistForBackup("p9")
            XCTFail("expected a refusal")
        } catch {
            XCTAssertEqual(error as? SpotifySource.PlaylistError, .tracksUnavailable)
        }
    }

    // MARK: Restore

    /// A Spotify backup goes back into Spotify by its own ids (no search); another service's song is found
    /// by its ISRC; then a private playlist is made and the songs added, in order.
    func test_should_reuseSpotifyIdsAndMatchTheRest_when_restoringIntoSpotify() async throws {
        PlaylistStub.route = { r in
            switch (r.method, r.url.path) {
            case ("GET", "/v1/search"):
                let q = URLComponents(url: r.url, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "q" }?.value
                if q == "isrc:JPU901800123" {
                    return .init(status: 200, body: #"{"tracks":{"items":[\#(Self.track("Y7", "夜に駆ける", isrc: "JPU901800123", artists: ["YOASOBI"]))]}}"#)
                }
                return .init(status: 200, body: #"{"tracks":{"items":[]}}"#)
            case ("POST", "/v1/me/playlists"):
                return .init(status: 201, body: #"{"id":"made1","name":"Night drive","owner":{"id":"me1"}}"#)
            default:
                return .init(status: 201, body: #"{"snapshot_id":"s1"}"#)
            }
        }
        let dest = SpotifyRestoreDestination(spotify: source())
        let tracks = [
            MatchTrack(id: "spotify:A1", title: "Neon Saints", artist: "Kiri Ono", album: nil, durationSec: nil, isrc: nil),
            MatchTrack(id: "subsonic:77", title: "夜に駆ける", artist: "YOASOBI", album: "THE BOOK",
                       durationSec: KotlinInt(int: 201), isrc: "JPU901800123"),
        ]
        let resolved = try await PlaylistRestore.resolve(tracks, on: dest)
        let matched = resolved.map { ($0.decision as? MatchDecisionMatched) }
        XCTAssertEqual(matched.map { $0?.track.id }, ["spotify:A1", "spotify:Y7"])
        XCTAssertEqual(matched.map { $0?.method }, [.sourceid, .isrc])
        // Only the other service's song was looked up.
        XCTAssertEqual(PlaylistStub.requests.count, 1)

        PlaylistStub.reset()
        let ids = matched.compactMap { $0?.track.id } + ["applemusic:999"]
        let write = await PlaylistRestore.write(name: "Night drive", description: nil, trackIds: ids, to: dest)
        XCTAssertTrue(write.created)
        XCTAssertEqual(write.added, 3)
        XCTAssertEqual(PlaylistStub.requests.map(\.url.path), ["/v1/me/playlists", "/v1/playlists/made1/items"])
        XCTAssertEqual(body(0)["public"] as? Bool, false)
        XCTAssertEqual(body(1)["uris"] as? [String], ["spotify:track:A1", "spotify:track:Y7"])
    }

    /// A Spotify account is a restore destination now, not "Not on iPhone yet".
    func test_should_offerSpotify_when_choosingWhereToRestore() {
        let accounts = SpotifyAccounts([Profile(id: "sp1", service: .spotify, createdAt: 1),
                                        Profile(id: "sp2", service: .spotify, createdAt: 2)])
        accounts.unreadable = ["sp2"]
        let targets = BackupTexts.targets(accounts)
        XCTAssertEqual(targets.map(\.provider), ["spotify", "spotify"])
        XCTAssertEqual(targets.map(\.available), [true, false])
        XCTAssertEqual(targets[1].note, "Sign in again")
    }
}

@MainActor
private final class SpotifyAccounts: BackupAccounts {
    var profiles: [Profile]
    var activeId: String? { profiles.first?.id }
    var unreadable: Set<String> = []

    init(_ profiles: [Profile]) { self.profiles = profiles }

    func canRead(_ profileId: String) -> Bool { !unreadable.contains(profileId) }
    func source(_ profileId: String) -> PlaylistBackups.Source? { nil }
    func destination(_ profileId: String) -> RestoreDestination? { nil }
}

/// Answers each request from [route] and records it with its body (URLSession hands bodies over as a stream).
private final class PlaylistStub: URLProtocol {
    struct Seen {
        var method: String
        var url: URL
        var body: Data
    }

    struct Reply {
        var status: Int
        var body: String
    }

    private static let lock = NSLock()
    nonisolated(unsafe) static var route: @Sendable (Seen) -> Reply = { _ in Reply(status: 500, body: "") }
    nonisolated(unsafe) private static var seen: [Seen] = []

    static func reset() { lock.withLock { seen = [] } }
    static var requests: [Seen] { lock.withLock { seen } }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    private static func body(_ r: URLRequest) -> Data {
        if let b = r.httpBody { return b }
        guard let stream = r.httpBodyStream else { return Data() }
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

    override func startLoading() {
        let s = Seen(method: request.httpMethod ?? "GET", url: request.url!, body: Self.body(request))
        Self.lock.withLock { Self.seen.append(s) }
        let reply = Self.route(s)
        let response = HTTPURLResponse(url: request.url!, statusCode: reply.status, httpVersion: "HTTP/1.1",
                                       headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(reply.body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
