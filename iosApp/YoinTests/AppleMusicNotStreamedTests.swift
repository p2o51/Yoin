import XCTest
@testable import Yoin

/// Apple Music songs Apple lists but won't stream (Android AppleMusicSong.catalogUnavailable,
/// player/PlayableQueue.kt): the mapping's flag, the detail cache, and what a start or a queue action keeps.
@MainActor
final class AppleMusicNotStreamedTests: XCTestCase {
    private func json(_ s: String) -> AppleMusicJSON.Object {
        AppleMusicJSON.object(Data(s.utf8))!
    }

    /// A catalog song; without [playParams] it is one Apple lists but won't stream (no length either).
    private func catalogSong(id: String, playParams: Bool = true) -> String {
        let play = playParams ? #","durationInMillis":245000,"playParams":{"id":"\#(id)","kind":"song"}"# : ""
        return """
        {"id":"\(id)","type":"songs","attributes":{"name":"Song \(id)","artistName":"Kiri Ono",
         "albumName":"Harbor Lights","trackNumber":1,
         "url":"https://music.apple.com/jp/album/harbor-lights/1440857781?i=\(id)"\(play)}}
        """
    }

    // MARK: Mapping

    func test_should_markNotStreamed_when_aCatalogSongHasNoPlayParams() {
        let s = AppleMusicMapping.song(json(catalogSong(id: "1440857790", playParams: false)))!
        XCTAssertTrue(s.catalogUnavailable)
        XCTAssertTrue(AppleMusicMapping.track(s).notStreamed)
        XCTAssertEqual(s.rawId, "1440857790")
    }

    func test_should_notMarkNotStreamed_when_aCatalogSongHasPlayParams() {
        let s = AppleMusicMapping.song(json(catalogSong(id: "1440857786")))!
        XCTAssertFalse(s.catalogUnavailable)
        XCTAssertFalse(AppleMusicMapping.track(s).notStreamed)
    }

    func test_should_notMarkNotStreamed_when_theResourceHasNoAttributes() {
        let s = AppleMusicMapping.song(json(#"{"id":"1440857786","type":"songs"}"#))!
        XCTAssertFalse(s.catalogUnavailable)
    }

    func test_should_markNotStreamed_when_aLibrarySongsCatalogSongIsGone() {
        let r = json("""
        {"id":"i.PULLED","type":"library-songs","attributes":{"name":"Pulled","artistName":"Kiri Ono",
         "playParams":{"id":"i.PULLED","kind":"song","isLibrary":true,"catalogId":"1440857799"}},
         "relationships":{"catalog":{"data":[]}}}
        """)
        let s = AppleMusicMapping.song(r)!
        XCTAssertTrue(s.catalogUnavailable)
        XCTAssertEqual(s.rawId, "1440857799")
        XCTAssertFalse(s.isImport)
    }

    func test_should_notMarkNotStreamed_when_aLibrarySongIsAnImport() {
        let r = json("""
        {"id":"i.IMPORT","type":"library-songs","attributes":{"name":"Demo","artistName":"Me",
         "playParams":{"id":"i.IMPORT","kind":"song","isLibrary":true}},
         "relationships":{"catalog":{"data":[]}}}
        """)
        let s = AppleMusicMapping.song(r)!
        XCTAssertFalse(s.catalogUnavailable)
        XCTAssertTrue(s.isImport)
    }

    func test_should_notMarkNotStreamed_when_aLibrarySongWasLoadedWithoutItsCatalog() {
        let r = json("""
        {"id":"i.ABC","type":"library-songs","attributes":{"name":"Morning Static","artistName":"Kiri Ono",
         "playParams":{"id":"i.ABC","kind":"song","isLibrary":true,"catalogId":"1440857786"}}}
        """)
        XCTAssertFalse(AppleMusicMapping.song(r)!.catalogUnavailable)
    }

    func test_should_markNotStreamed_when_aLibrarySongsCatalogSongIsUnreleased() {
        let r = json("""
        {"id":"i.SOON","type":"library-songs","attributes":{"name":"Soon","artistName":"Kiri Ono",
         "playParams":{"id":"i.SOON","kind":"song","isLibrary":true,"catalogId":"1440857791"}},
         "relationships":{"catalog":{"data":[\(catalogSong(id: "1440857791", playParams: false))]}}}
        """)
        XCTAssertTrue(AppleMusicMapping.song(r)!.catalogUnavailable)
    }

    func test_should_carryNotStreamed_when_mappingAnAlbumsTracks() {
        let r = json("""
        {"id":"1440857781","type":"albums","attributes":{"name":"Harbor Lights","artistName":"Kiri Ono"},
         "relationships":{"tracks":{"data":[\(catalogSong(id: "1440857786")),\(catalogSong(id: "1440857790", playParams: false))]}}}
        """)
        let album = AppleMusicMapping.album(AppleMusicMapping.album(r)!)
        XCTAssertEqual(album.tracks.map(\.notStreamed), [false, true])
    }

    // MARK: Detail cache

    func test_should_keepNotStreamed_when_aTrackGoesThroughTheDetailCache() {
        let codec = DetailCacheCodec.appleMusic
        var t = Track(id: MediaId(provider: AppleMusicMapping.provider, rawId: "1440857790"), number: 2,
                      title: "Soon", durationSec: 0, rating: nil)
        XCTAssertNil(codec.dto(t).notStreamed)
        XCTAssertFalse(codec.track(codec.dto(t)).notStreamed)
        t.notStreamed = true
        XCTAssertEqual(codec.dto(t).notStreamed, true)
        XCTAssertTrue(codec.track(codec.dto(t)).notStreamed)
    }

    // MARK: Queues

    func test_should_dropHeldBackSongs_and_moveTheStart_when_aRowStartsAPlayableSong() {
        let r = PlayableQueue.select([false, true, false, true, false], startAt: 2, explicitStart: true)
        XCTAssertEqual(r.indices, [0, 2, 4])
        XCTAssertEqual(r.startAt, 1)
    }

    func test_should_leaveTheListAsGiven_when_aRowStartsAHeldBackSong() {
        let r = PlayableQueue.select([false, true, false], startAt: 1, explicitStart: true)
        XCTAssertEqual(r.indices, [0, 1, 2])
        XCTAssertEqual(r.startAt, 1)
    }

    func test_should_startAtTheNextPlayableSong_when_PlayLandsOnAHeldBackSong() {
        let r = PlayableQueue.select([true, true, false, false], startAt: 0, explicitStart: false)
        XCTAssertEqual(r.indices, [2, 3])
        XCTAssertEqual(r.startAt, 0)
    }

    func test_should_startAtTheLastPlayableSong_when_theHeldBackStartIsAtTheEnd() {
        let r = PlayableQueue.select([false, false, true], startAt: 2, explicitStart: false)
        XCTAssertEqual(r.indices, [0, 1])
        XCTAssertEqual(r.startAt, 1)
    }

    func test_should_leaveTheListAsGiven_when_nothingOrEverythingIsHeldBack() {
        XCTAssertEqual(PlayableQueue.select([false, false], startAt: 1, explicitStart: false).indices, [0, 1])
        let all = PlayableQueue.select([true, true], startAt: 0, explicitStart: false)
        XCTAssertEqual(all.indices, [0, 1])
        XCTAssertEqual(all.startAt, 0)
    }

    func test_should_keepOnlyStreamedSongs_when_startingAndQueueingEntries() {
        let album = AppleMusicMapping.album(AppleMusicMapping.album(json("""
        {"id":"1440857781","type":"albums","attributes":{"name":"Harbor Lights","artistName":"Kiri Ono"},
         "relationships":{"tracks":{"data":[\(catalogSong(id: "1440857790", playParams: false)),\(catalogSong(id: "1440857786")),\(catalogSong(id: "1440857787"))]}}}
        """))!)
        let entries = album.tracks.map { PlaylistEntry(album: album, track: $0) }
        let start = PlayableQueue.start(entries, startAt: 2, explicitStart: true)
        XCTAssertEqual(start.entries.map(\.track.id.rawId), ["1440857786", "1440857787"])
        XCTAssertEqual(start.startAt, 1)
        XCTAssertEqual(PlayableQueue.queueable(entries).map(\.track.id.rawId), ["1440857786", "1440857787"])
    }

    // MARK: List rows (Library › Songs, search, playlists)

    func test_should_explainInsteadOfPlaying_when_aRowsSongIsNotStreamed() {
        var t = Track(id: MediaId(provider: AppleMusicMapping.provider, rawId: "1440857790"), number: 1,
                      title: "Song", durationSec: 0, rating: nil)
        XCTAssertEqual(NotStreamedRow.tap(t), .play)
        t.notStreamed = true
        XCTAssertEqual(NotStreamedRow.tap(t), .explain)
    }

    func test_should_openAndCloseOnlyThatRowsReason_when_toggled() {
        var shown: Set<String> = []
        NotStreamedRow.toggle("a", in: &shown)
        NotStreamedRow.toggle("b", in: &shown)
        XCTAssertEqual(shown, ["a", "b"])
        NotStreamedRow.toggle("a", in: &shown)
        XCTAssertEqual(shown, ["b"])
    }
}
