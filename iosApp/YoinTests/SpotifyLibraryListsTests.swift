
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

    func test_should_ask_the_other_release_pages_by_offset_up_to_the_cap_when_an_artist_has_many() {
        XCTAssertEqual(SpotifySource.releaseOffsets(total: 35, firstCount: 10, hasNext: true), [10, 20, 30])
        XCTAssertEqual(SpotifySource.releaseOffsets(total: 400, firstCount: 10, hasNext: true).last, 90)
        XCTAssertEqual(SpotifySource.releaseOffsets(total: 8, firstCount: 8, hasNext: false), [])
        XCTAssertEqual(SpotifySource.releaseOffsets(total: nil, firstCount: 10, hasNext: true).count, 9)
    }

    func test_should_point_to_user_management_when_me_answers_403_at_sign_in() {
        XCTAssertTrue(SpotifyAuth.meFailure(403).contains("developer.spotify.com"))
        XCTAssertTrue(SpotifyAuth.meFailure(500).contains("500"))
    }

    func test_should_say_how_many_songs_without_a_dot_chain_when_labelling_a_playlist() {
        XCTAssertEqual(SpotifyMapping.playlistSubtitle(nil), String(localized: "Playlist"))
        XCTAssertFalse(SpotifyMapping.playlistSubtitle(12).contains("·"))
    }

    func test_should_mark_a_spotify_made_playlist_unavailable_when_spotify_answers_404() {
        XCTAssertTrue(SpotifySource.isSpotifyMade("37i9dQZEVXcJZyENOWUFo7"))
        XCTAssertFalse(SpotifySource.isSpotifyMade("3cEYpjA9oz9GiPac4AsH4n"))
        var row = LibraryItem(id: MediaId(provider: SpotifyMapping.provider, rawId: "37i9dQZEVXcJZyENOWUFo7"),
                              kind: .playlist, title: "Discover Weekly", subtitle: "", palette: palette)
        row.count = 30
        let info = LibraryStore.spotifyMadeUnavailable("37i9dQZEVXcJZyENOWUFo7", row: row, palette: palette)
        XCTAssertTrue(info.tracksUnavailable)
        XCTAssertEqual(info.title, "Discover Weekly")
        XCTAssertEqual(info.songCount, 30)
        XCTAssertEqual(info.ownedByMe, false)
        XCTAssertTrue(info.entries.isEmpty)
    }

    func test_should_count_a_list_stale_only_when_older_than_its_age() {
        let now = Date()
        XCTAssertFalse(LibraryStore.isStale(nil, maxAge: 3600, now: now))
        XCTAssertFalse(LibraryStore.isStale(now.addingTimeInterval(-3599), maxAge: 3600, now: now))
        XCTAssertTrue(LibraryStore.isStale(now.addingTimeInterval(-3601), maxAge: 3600, now: now))
        XCTAssertTrue(LibraryStore.isStale(.distantPast, maxAge: 3600, now: now))
    }

    func test_should_keep_the_rows_past_the_first_read_when_liked_songs_refresh() {
        let a = album("al", artist: "X", artistId: "x")
        let shown = ["t1", "t2", "t3", "t4", "t5"].map { entry($0, album: a) }
        // t0 liked elsewhere, t2 unliked elsewhere: the fresh first read is two rows.
        let fresh = ["t0", "t1"].map { entry($0, album: a) }
        XCTAssertEqual(LibraryStore.refreshedLikedSongs(fresh, over: shown).map(\.track.id.rawId),
                       ["t0", "t1", "t3", "t4", "t5"])
        // A fresh read as long as what shows replaces it.
        XCTAssertEqual(LibraryStore.refreshedLikedSongs(shown, over: fresh).map(\.track.id.rawId),
                       ["t1", "t2", "t3", "t4", "t5"])
    }

    func test_should_keep_a_save_or_removal_over_the_read_when_younger_than_a_minute() {
        let now = Date()
        let kept = album("al1", artist: "X", artistId: "x"), removed = album("al2", artist: "Y", artistId: "y")
        let saved = album("al3", artist: "Z", artistId: "z")
        let edits = [LibraryStore.SavedAlbumEdit(album: removed, saved: false, at: now.addingTimeInterval(-10)),
                     LibraryStore.SavedAlbumEdit(album: saved, saved: true, at: now.addingTimeInterval(-5))]
        // Spotify still lists the removed album and not yet the saved one.
        let read = [kept, removed]
        XCTAssertEqual(LibraryStore.applyingSavedAlbumEdits(read, edits, now: now).map(\.id.rawId), ["al3", "al1"])
        // An older shelf only loses the removed one.
        XCTAssertEqual(LibraryStore.applyingSavedAlbumEdits(read, edits, now: now, top: false).map(\.id.rawId), ["al1"])
        // Past the grace Spotify's list stands.
        XCTAssertEqual(LibraryStore.applyingSavedAlbumEdits(read, edits, now: now.addingTimeInterval(120)).map(\.id.rawId),
                       ["al1", "al2"])
    }
}
