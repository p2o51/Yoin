import XCTest
import YoinShared
@testable import Yoin

/// Kotlin Subsonic models → Swift UI models (Data/SubsonicMapping.swift),
/// the cover cache key, and the Home shelves built from album lists.
final class SubsonicMappingTests: XCTestCase {
    private let mapping = SubsonicMapping { id in URL(string: "http://h:4979/rest/getCoverArt?id=\(id)&size=600&u=a&t=x&s=y") }
    private let provider = MediaRef.companion.PROVIDER_SUBSONIC

    private func song(_ id: String, title: String = "Song", artist: String? = "Kiri Ono", track: Int32? = nil,
                      rating: Int32? = nil, starred: Bool = false, plays: Int32? = nil) -> SongInfo {
        SongInfo(ref: MediaRef(provider: provider, rawId: id), id: id, title: title, artist: artist, artistId: "ar-kiri",
                 album: "Harbor Lights", albumId: "al-harbor", track: track.map { KotlinInt(int: $0) }, disc: nil,
                 durationSec: KotlinInt(int: 245), coverArtId: "al-harbor", year: KotlinInt(int: 2021), genre: "Dream Pop",
                 starred: starred, starredAt: starred ? "2026-01-01T00:00:00Z" : nil,
                 userRating: rating.map { KotlinInt(int: $0) }, suffix: "mp3", contentType: "audio/mpeg",
                 bitRate: nil, size: nil, path: nil, playCount: plays.map { KotlinInt(int: $0) })
    }

    private func albumInfo(songs: [SongInfo], rating: Int32? = nil, type: String? = nil) -> AlbumInfo {
        AlbumInfo(ref: MediaRef(provider: provider, rawId: "al-harbor"), id: "al-harbor", name: "Harbor Lights",
                  artist: "Kiri Ono", artistId: "ar-kiri", year: KotlinInt(int: 2021), songCount: KotlinInt(int: 10),
                  durationSec: KotlinInt(int: 2300), coverArtId: "al-harbor", starred: true, starredAt: nil,
                  userRating: rating.map { KotlinInt(int: $0) }, playCount: KotlinInt(int: 214),
                  created: "2024-03-17T10:00:00Z", genre: "Dream Pop", releaseType: type, songs: songs)
    }

    func test_should_double_server_stars_when_mapping_a_rating() {
        XCTAssertEqual(SubsonicMapping.rating(KotlinInt(int: 4)), 8)
        XCTAssertNil(SubsonicMapping.rating(KotlinInt(int: 0)))
        XCTAssertNil(SubsonicMapping.rating(nil))
        XCTAssertEqual(SubsonicMapping.serverRating(8.6), 4)
        XCTAssertEqual(SubsonicMapping.serverRating(9), 5)
        XCTAssertEqual(SubsonicMapping.serverRating(nil), 0)
    }

    func test_should_keep_server_fields_when_mapping_a_track() {
        let t = mapping.track(song("s1", title: "Morning Static", track: 3, rating: 5, starred: true, plays: 12),
                              index: 0, albumArtist: "Kiri Ono")
        XCTAssertEqual(t.id, MediaId(provider: provider, rawId: "s1"))
        XCTAssertEqual(t.number, 3)
        XCTAssertEqual(t.title, "Morning Static")
        XCTAssertEqual(t.durationSec, 245)
        XCTAssertEqual(t.rating, 10)
        XCTAssertTrue(t.starred)
        XCTAssertEqual(t.playCount, 12)
        XCTAssertNil(t.artist, "same as the album artist")
    }

    func test_should_number_by_position_and_keep_guest_artist_when_track_lacks_them() {
        let t = mapping.track(song("s2", artist: "Guest"), index: 4, albumArtist: "Kiri Ono")
        XCTAssertEqual(t.number, 5)
        XCTAssertEqual(t.artist, "Guest")
    }

    func test_should_map_album_with_tracks_cover_and_counts() {
        let a = mapping.album(albumInfo(songs: [song("s1", track: 1), song("s2", track: 2)], rating: 3, type: "ep"))
        XCTAssertEqual(a.id.rawId, "al-harbor")
        XCTAssertEqual(a.title, "Harbor Lights")
        XCTAssertEqual(a.year, 2021)
        XCTAssertEqual(a.tracks.count, 2)
        XCTAssertEqual(a.trackCount, 2)
        XCTAssertEqual(a.rating, 6)
        XCTAssertEqual(a.kind, .ep)
        XCTAssertTrue(a.starred)
        XCTAssertEqual(a.playCount, 214)
        XCTAssertEqual(a.artistId, "ar-kiri")
        XCTAssertNotNil(a.coverURL)
    }

    func test_should_fall_back_to_server_counts_when_album_has_no_songs() {
        let a = mapping.album(albumInfo(songs: []))
        XCTAssertEqual(a.trackCount, 10)
        XCTAssertEqual(a.totalSec, 2300)
        XCTAssertEqual(a.kind, .album)
    }

    func test_should_key_covers_without_auth_params_when_building_a_cover_ref() {
        let a = CoverRef(url: URL(string: "http://h:4979/rest/getCoverArt?id=al-1&size=600&u=a&t=x1&s=y1")!)
        let b = CoverRef(url: URL(string: "http://h:4979/rest/getCoverArt?s=y2&t=x2&size=600&u=a&id=al-1")!)
        XCTAssertEqual(a.key, b.key)
        XCTAssertFalse(a.key.contains("x1"))
        XCTAssertNotEqual(a.key, CoverRef(url: URL(string: "http://h:4979/rest/getCoverArt?id=al-2&size=600")!).key)
    }

    func test_should_give_each_song_its_album_when_mapping_loose_songs() {
        let e = mapping.entry(song("s9", title: "Slow Tide"))
        XCTAssertEqual(e.album.id.rawId, "al-harbor")
        XCTAssertEqual(e.album.title, "Harbor Lights")
        XCTAssertEqual(e.track.title, "Slow Tide")
        XCTAssertTrue(e.album.tracks.isEmpty)
    }

    func test_should_map_playlist_summary_and_detail() {
        let summary = PlaylistSummary(ref: MediaRef(provider: provider, rawId: "pl-1"), id: "pl-1", name: "Drive",
                                      owner: "demo", isPublic: KotlinBoolean(bool: true), comment: "  ",
                                      coverArtId: "pl-1", songCount: KotlinInt(int: 2), durationSec: nil, created: nil,
                                      changed: nil, canWrite: true, ownedByMe: KotlinBoolean(bool: true))
        let item = mapping.playlistItem(summary)
        XCTAssertEqual(item.kind, .playlist)
        XCTAssertEqual(item.count, 2)
        let info = mapping.playlist(PlaylistDetail(summary: summary, entries: [song("a"), song("b")]))
        XCTAssertEqual(info.entries.map(\.track.number), [1, 2])
        XCTAssertEqual(info.isPublic, true)
        XCTAssertNil(info.description, "blank comment")
    }

    func test_should_sort_releases_and_rank_most_played_when_building_artist_page() {
        let old = AlbumInfo(ref: MediaRef(provider: provider, rawId: "al-old"), id: "al-old", name: "Old", artist: "Kiri Ono",
                            artistId: "ar-kiri", year: KotlinInt(int: 2019), songCount: nil, durationSec: nil,
                            coverArtId: "al-old", starred: false, starredAt: nil, userRating: nil, playCount: nil,
                            created: nil, genre: nil, releaseType: nil,
                            songs: [song("o1", plays: 3), song("o2", plays: 0)])
        let new = albumInfo(songs: [song("n1", plays: 9)])
        let artist = ArtistInfo(ref: MediaRef(provider: provider, rawId: "ar-kiri"), id: "ar-kiri", name: "Kiri Ono",
                                albumCount: KotlinInt(int: 2), coverArtId: nil, starred: true, starredAt: nil,
                                albums: [old, new])
        let page = mapping.artistPage(artist, loadedAlbums: [mapping.album(old), mapping.album(new)])
        XCTAssertEqual(page.releases.map(\.id.rawId), ["al-harbor", "al-old"])
        XCTAssertEqual(page.mostPlayed.map(\.track.id.rawId), ["n1", "o1"])
        XCTAssertTrue(page.starred)
    }

    func test_should_fill_shelves_without_repeats_when_building_home() {
        func al(_ i: Int, plays: Int? = nil, rating: Double? = nil) -> Album {
            Album(id: MediaId(provider: "subsonic", rawId: "a\(i)"), title: "A\(i)", artist: "X", year: 2020,
                  palette: CoverPalette(a: .red, b: .blue, c: .black), tracks: [], rating: rating, playCount: plays)
        }
        let recent = (0..<6).map { al($0, plays: 10 - $0) }
        let feed = LibraryStore.buildHome(recent: recent, frequent: [al(0), al(7)], newest: [al(8), al(9)],
                                          highest: [al(10, rating: 8)], random: (11..<20).map { al($0) },
                                          recentTracks: [])
        XCTAssertEqual(feed.layout, .server)
        XCTAssertEqual(feed.activities.map(\.album.id.rawId), ["a0", "a1", "a2", "a3"])
        XCTAssertEqual(feed.activities[0].eyebrow, loc("\(10) plays"))
        XCTAssertEqual(feed.activities[1].eyebrow, "")
        // Android's pool: the random albums first, topped up to 14 from the recent / frequent mix; Activities'
        // albums are not left out (the shelf's own rule is the signal cards').
        let shelf = feed.jumpBackIn.compactMap(\.album?.id.rawId)
        XCTAssertEqual(shelf.count, 14)
        XCTAssertEqual(Set(shelf).count, 14)
        XCTAssertEqual(Array(shelf.prefix(9)), (11..<20).map { "a\($0)" })
        XCTAssertEqual(feed.recentAlbums.map(\.id.rawId), ["a8", "a9"])
        XCTAssertEqual(feed.rediscover.first?.album.id.rawId, "a10")
        XCTAssertEqual(feed.rediscover.first?.score, 8)
        XCTAssertEqual(feed.rediscover.count, 2)
    }

    func test_should_format_year_month_when_given_iso_date() {
        XCTAssertEqual(LibraryStore.yearMonth("2024-03-17T10:00:00Z"), "2024.03")
        XCTAssertEqual(LibraryStore.yearMonth(nil), "")
    }

    /// #154: the version shows as a tag, so the " (version)" the server appended to the name goes.
    func test_should_dropTheAppendedVersion_onlyWhenTheNameEndsWithIt() {
        XCTAssertEqual(Album.nameWithoutVersion("Midnights (Deluxe)", "Deluxe"), "Midnights")
        XCTAssertEqual(Album.nameWithoutVersion("Midnights (Deluxe)", " Deluxe "), "Midnights")
        XCTAssertEqual(Album.nameWithoutVersion("Midnights", "Deluxe"), "Midnights")
        XCTAssertEqual(Album.nameWithoutVersion("Midnights (Deluxe) Live", "Deluxe"), "Midnights (Deluxe) Live")
        XCTAssertEqual(Album.nameWithoutVersion("(全景声)", "全景声"), "(全景声)")
        XCTAssertEqual(Album.nameWithoutVersion("Midnights (Deluxe)", nil), "Midnights (Deluxe)")
    }
}
