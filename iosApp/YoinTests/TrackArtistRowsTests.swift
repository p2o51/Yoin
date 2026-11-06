import XCTest
import YoinShared
@testable import Yoin

/// The song sheet's "Go to artist" rows follow the song's credited artists (B-F7, Android goToArtistRows).
final class TrackArtistRowsTests: XCTestCase {
    func test_should_offer_one_row_per_credited_artist_when_several_have_ids() {
        let rows = TrackArtistRows.rows(
            credits: [ArtistCredit(name: "Kiri Ono", id: "a1"), ArtistCredit(name: "Mio", id: "a2")],
            fallbackArtistId: "a1")
        XCTAssertEqual(rows, [GoToArtistRow(name: "Kiri Ono", artistId: "a1"), GoToArtistRow(name: "Mio", artistId: "a2")])
    }

    func test_should_skip_credits_without_an_id_when_listing_rows() {
        let rows = TrackArtistRows.rows(
            credits: [ArtistCredit(name: "A", id: "a1"), ArtistCredit(name: "B", id: nil), ArtistCredit(name: "C", id: "a3")],
            fallbackArtistId: "a1")
        XCTAssertEqual(rows.map(\.artistId), ["a1", "a3"])
    }

    func test_should_fall_back_to_the_one_artist_row_when_credits_are_missing_or_unnamed() {
        XCTAssertEqual(TrackArtistRows.rows(credits: [], fallbackArtistId: "alb"), [GoToArtistRow(name: nil, artistId: "alb")])
        XCTAssertEqual(TrackArtistRows.rows(credits: [ArtistCredit(name: "A", id: "a1"), ArtistCredit(name: "B", id: nil)],
                                            fallbackArtistId: "alb").map(\.artistId), ["alb"])
        XCTAssertTrue(TrackArtistRows.rows(credits: [], fallbackArtistId: nil).isEmpty)
        XCTAssertTrue(TrackArtistRows.rows(credits: [], fallbackArtistId: "").isEmpty)
    }

    func test_should_carry_credits_when_mapping_a_subsonic_song() {
        let provider = MediaRef.companion.PROVIDER_SUBSONIC
        let song = SongInfo(ref: MediaRef(provider: provider, rawId: "s1"), id: "s1", title: "Duet", artist: "Kiri Ono & Mio",
                            artistId: "ar-kiri", album: "Harbor Lights", albumId: "al", track: nil, disc: nil,
                            durationSec: KotlinInt(int: 200), coverArtId: nil, year: nil, genre: nil, starred: false,
                            starredAt: nil, userRating: nil, suffix: nil, contentType: nil, bitRate: nil, size: nil,
                            path: nil, playCount: nil, played: nil,
                            artists: [SongArtist(name: "Kiri Ono", id: "ar-kiri"), SongArtist(name: "Mio", id: "ar-mio")])
        let t = SubsonicMapping { _ in nil }.track(song)
        XCTAssertEqual(t.credits, [ArtistCredit(name: "Kiri Ono", id: "ar-kiri"), ArtistCredit(name: "Mio", id: "ar-mio")])
    }

    func test_should_carry_credits_when_a_spotify_track_has_several_artists() throws {
        let json = """
        {"id":"t1","name":"Duet","type":"track","duration_ms":200000,"track_number":1,
         "artists":[{"id":"x1","name":"Kiri Ono"},{"id":"x2","name":"Mio"}],
         "album":{"id":"al1","name":"Harbor Lights","album_type":"album","artists":[{"id":"x1","name":"Kiri Ono"}]}}
        """
        let dto = try SpotifyWebAPI.decoder.decode(SpotifyDTO.Track.self, from: Data(json.utf8))
        let t = try XCTUnwrap(SpotifyMapping.track(dto))
        XCTAssertEqual(t.credits, [ArtistCredit(name: "Kiri Ono", id: "x1"), ArtistCredit(name: "Mio", id: "x2")])
    }

    func test_should_carry_credits_when_an_apple_song_names_several_artists() throws {
        let raw = """
        {"id":"1","type":"songs","attributes":{"name":"Duet","artistName":"Kiri Ono & Mio","albumName":"HL"},
         "relationships":{"artists":{"data":[{"id":"11","type":"artists","attributes":{"name":"Kiri Ono"}},
                                              {"id":"22","type":"artists","attributes":{"name":"Mio"}}]}}}
        """
        let r = try XCTUnwrap(AppleMusicJSON.object(Data(raw.utf8)))
        let s = try XCTUnwrap(AppleMusicMapping.song(r))
        XCTAssertEqual(s.credits, [ArtistCredit(name: "Kiri Ono", id: "11"), ArtistCredit(name: "Mio", id: "22")])
        XCTAssertEqual(AppleMusicMapping.track(s).credits.count, 2)
    }
}
