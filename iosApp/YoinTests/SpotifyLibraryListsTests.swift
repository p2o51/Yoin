
import XCTest
@testable import Yoin

@MainActor
final class SpotifyLibraryListsTests: XCTestCase {
    private let palette = SpotifyMapping.palette(nil, key: "k")

    private func album(_ id: String, artist: String, artistId: String?) -> Album {
        Album(id: MediaId(provider: SpotifyMapping.provider, rawId: id), title: id, artist: artist, year: 0,
              palette: palette, tracks: [], artistId: artistId)
    }

    private func entry(_ id: String, album: Album, credits: [ArtistCredit] = []) -> PlaylistEntry {
        PlaylistEntry(album: album, track: Track(id: MediaId(provider: SpotifyMapping.provider, rawId: id), number: 1,
                                                 title: id, durationSec: 60, credits: credits))
    }

    // (`LibraryItem` is ambiguous with YoinShared's as a type name here: built by inference.)
    private lazy var artist = { [palette] (id: String, name: String) in
        LibraryItem(id: MediaId(provider: SpotifyMapping.provider, rawId: id), kind: .artist, title: name,
                    subtitle: "Artist", palette: palette)
    }

    func test_should_list_saved_album_and_liked_song_artists_when_merging_followed_ones() {
        let followed = [artist("ar1", "Followed")]
        let albums = [album("al1", artist: "Followed", artistId: "ar1"), album("al2", artist: "Saved", artistId: "ar2")]
        let songs = [entry("t1", album: album("al3", artist: "Liked", artistId: "ar3"),
                           credits: [ArtistCredit(name: "Liked", id: "ar3"), ArtistCredit(name: "Guest", id: "ar4")]),
                     entry("t2", album: album("al4", artist: "Local", artistId: nil))]
        let merged = SpotifySource.libraryArtists(followed: followed, albums: albums, songs: songs)
        XCTAssertEqual(merged.map(\.id.rawId), ["ar1", "ar2", "ar3", "ar4"])
        XCTAssertEqual(merged.map(\.title), ["Followed", "Saved", "Liked", "Guest"])
    }

    func test_should_put_a_liked_song_on_top_and_take_an_unliked_one_out_when_hearts_change() {
        let a = album("al", artist: "X", artistId: "x")
        let songs = [entry("t1", album: a), entry("t2", album: a), entry("t1", album: a)]
        let liked = LibraryStore.likedSongs(songs, songId: "t3", on: true) { self.entry("t3", album: a) }
        XCTAssertEqual(liked.map(\.track.id.rawId), ["t3", "t1", "t2", "t1"])
        XCTAssertTrue(liked[0].track.starred)
        // Already there: unchanged; not found anywhere: unchanged.
        XCTAssertEqual(LibraryStore.likedSongs(songs, songId: "t2", on: true) { nil }, songs)
        XCTAssertEqual(LibraryStore.likedSongs(songs, songId: "t9", on: true) { nil }, songs)
        let unliked = LibraryStore.likedSongs(songs, songId: "t1", on: false) { nil }
        XCTAssertEqual(unliked.map(\.track.id.rawId), ["t2"])
    }

    func test_should_put_recent_songs_first_once_each_when_building_the_pool() {
        let a = album("al", artist: "X", artistId: "x")
        let pool = SpotifySource.trackPool(recent: [entry("r1", album: a), entry("s1", album: a)],
                                           sample: [entry("s1", album: a), entry("s2", album: a)])
        XCTAssertEqual(pool.map(\.track.id.rawId), ["r1", "s1", "s2"])
    }

    func test_should_ask_to_reconnect_when_a_follow_lacks_the_scope() {
        let scope = SpotifyAPIError.http(status: 403, message: "Insufficient client scope")
        XCTAssertEqual(LibraryStore.followFailure(scope, following: true), String(localized: "Reconnect Spotify in Settings"))
        XCTAssertEqual(LibraryStore.followFailure(SpotifyAPIError.http(status: 500, message: nil), following: false),
                       String(localized: "Couldn't unfollow the artist"))
    }
}
