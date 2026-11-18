import XCTest
import YoinShared
@testable import Yoin

/// Apple Music playlist backups keep the ISRC and a restore matches by it first (D-F3, Android
/// AppleMusicSong.isrc / catalogSongsByIsrc, docs/playlist-transfer.md §4.2).
@MainActor
final class AppleMusicIsrcTests: XCTestCase {
    private func json(_ s: String) -> AppleMusicJSON.Object { AppleMusicJSON.object(Data(s.utf8))! }

    func test_should_read_the_isrc_upper_cased_when_a_catalog_song_carries_one() {
        let r = json(#"{"id":"1","type":"songs","attributes":{"name":"Lemon","artistName":"Kenshi Yonezu","albumName":"Lemon","isrc":" jpku01800123 "}}"#)
        XCTAssertEqual(AppleMusicMapping.song(r)?.isrc, "JPKU01800123")
    }

    func test_should_read_the_catalog_versions_isrc_when_a_library_song_has_none_of_its_own() {
        let r = json("""
        {"id":"i.A","type":"library-songs","attributes":{"name":"Lemon","artistName":"Kenshi Yonezu","playParams":{"id":"i.A","catalogId":"1"}},
         "relationships":{"catalog":{"data":[{"id":"1","type":"songs","attributes":{"isrc":"JPKU01800123"}}]}}}
        """)
        XCTAssertEqual(AppleMusicMapping.song(r)?.isrc, "JPKU01800123")
        let none = json(#"{"id":"i.B","type":"library-songs","attributes":{"name":"Demo","artistName":"Me"}}"#)
        XCTAssertNil(AppleMusicMapping.song(none)?.isrc)
    }

    func test_should_write_the_isrc_into_the_backup_when_reading_an_apple_playlist() {
        let song: [String: Any] = ["id": "11", "type": "songs",
                                   "attributes": ["name": "One", "artistName": "Kiri Ono", "albumName": "Harbor",
                                                  "durationInMillis": 241_000, "isrc": "USRC17607839"]]
        let plain: [String: Any] = ["id": "12", "type": "songs",
                                    "attributes": ["name": "Two", "artistName": "Kiri Ono", "albumName": "Harbor"]]
        let p = AMPlaylist(resourceId: "p.abc", isLibrary: true, name: "Mix", curator: nil, description: nil,
                           artwork: nil, trackCount: 2)
        let snap = PlaylistBackups.snapshot(AppleMusicMapping.playlistRead(p, entries: [song, plain]))
        XCTAssertEqual(snap.tracks.map(\.isrc), ["USRC17607839", nil])
    }

    func test_should_batch_valid_distinct_codes_by_25_when_looking_up_by_isrc() {
        let codes = (0..<30).map { String(format: "USRC1760%04d", $0) }
        let batches = AppleMusicMapping.isrcBatches(["bad", " usrc17600001 "] + codes + [codes[3], ""])
        XCTAssertEqual(batches.map(\.count), [25, 5])
        XCTAssertEqual(batches[0].first, "USRC17600001")
        XCTAssertFalse(batches.joined().contains("BAD"))
        XCTAssertEqual(Set(batches.joined()).count, 30)
        XCTAssertTrue(AppleMusicMapping.isrcBatches([]).isEmpty)
    }

    func test_should_keep_every_song_of_a_code_and_drop_unasked_ones_when_grouping_an_answer() {
        func song(_ id: String, _ isrc: String?) -> AMSong {
            AMSong(resourceId: id, libraryId: nil, catalogId: id, title: "T\(id)", artist: "A", album: "", isrc: isrc)
        }
        let grouped = AppleMusicMapping.groupByIsrc([song("1", "USRC17607839"), song("2", "USRC17607839"),
                                                     song("3", "USRC17609999"), song("4", nil)],
                                                    batch: ["USRC17607839"])
        XCTAssertEqual(grouped["USRC17607839"]?.map(\.rawId), ["1", "2"])
        XCTAssertNil(grouped["USRC17609999"])
        XCTAssertEqual(AppleMusicRestoreDestination.track(song("1", "USRC17607839")).isrc, "USRC17607839")
    }

    /// The restore asks the destination by ISRC first; only a song it doesn't know falls to the title search.
    func test_should_match_by_isrc_before_searching_when_restoring_into_apple_music() async throws {
        let dest = IsrcDestination()
        dest.byIsrc = ["JPKU01800123": [MatchTrack(id: "applemusic:900", title: "Lemon", artist: "Kenshi Yonezu", album: nil,
                                                   durationSec: KotlinInt(int: 255), isrc: "JPKU01800123")]]
        let tracks = [
            MatchTrack(id: "spotify:S1", title: "Lemon", artist: "Kenshi Yonezu", album: nil,
                       durationSec: KotlinInt(int: 255), isrc: "jpku01800123"),
            MatchTrack(id: "spotify:S2", title: "Unknown Song", artist: "Nobody", album: nil, durationSec: nil, isrc: "USXXX0000001"),
        ]
        let resolved = try await PlaylistRestore.resolve(tracks, on: dest)
        XCTAssertEqual(dest.asked.count, 1)
        XCTAssertEqual(Set(dest.asked[0].map { $0.uppercased() }), ["JPKU01800123", "USXXX0000001"])
        let first = resolved[0].decision as? MatchDecisionMatched
        XCTAssertEqual(first?.track.id, "applemusic:900")
        XCTAssertEqual(first?.method, .isrc)
        XCTAssertTrue(resolved[1].decision is MatchDecisionNotFound)
        XCTAssertFalse(dest.searches.contains { $0.contains("Lemon") }, "the ISRC hit needs no search")
        XCTAssertTrue(dest.searches.contains { $0.contains("Unknown Song") }, "the miss falls back to the title search")
    }
}

@MainActor
private final class IsrcDestination: RestoreDestination {
    var byIsrc: [String: [MatchTrack]] = [:]
    var asked: [[String]] = []
    var searches: [String] = []

    func search(_ query: String) async throws -> [MatchTrack] { searches.append(query); return [] }
    func createPlaylist(name: String, description: String?, trackIds: [String]) async throws -> String { "new" }
    func add(_ trackIds: [String], to playlistId: String) async throws {}
    func tracksByIsrc(_ isrcs: [String]) async -> [String: [MatchTrack]] { asked.append(isrcs); return byIsrc }
}
