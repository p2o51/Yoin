import XCTest
@testable import Yoin

/// Apple Music API JSON → plain values → Swift UI models
/// (Data/AppleMusicMapping.swift), with no MusicKit runtime.
final class AppleMusicMappingTests: XCTestCase {
    private let art = "https://is1-ssl.mzstatic.com/image/thumb/Music/ab/cd/{w}x{h}bb.jpg"

    private func json(_ s: String) -> AppleMusicJSON.Object {
        AppleMusicJSON.object(Data(s.utf8))!
    }

    private func catalogSong(id: String = "1440857786", track: Int = 3) -> AppleMusicJSON.Object {
        json("""
        {"id":"\(id)","type":"songs","attributes":{"name":"Morning Static","artistName":"Kiri Ono",
         "albumName":"Harbor Lights","durationInMillis":245000,"trackNumber":\(track),"discNumber":1,
         "genreNames":["Dream Pop","Music"],"releaseDate":"2021-05-24",
         "url":"https://music.apple.com/jp/album/harbor-lights/1440857781?i=\(id)",
         "artwork":{"url":"\(art)","width":3000,"height":3000},"playParams":{"id":"\(id)","kind":"song"}}}
        """)
    }

    func test_should_use_catalog_id_when_a_library_song_names_one() {
        let r = json("""
        {"id":"i.ABC","type":"library-songs","attributes":{"name":"Morning Static","artistName":"Kiri Ono",
         "albumName":"Harbor Lights","playParams":{"id":"i.ABC","kind":"song","isLibrary":true,"catalogId":"1440857786"}},
         "relationships":{"albums":{"data":[{"id":"l.XYZ","type":"library-albums"}]}}}
        """)
        let s = AppleMusicMapping.song(r)!
        XCTAssertEqual(s.rawId, "1440857786")
        XCTAssertEqual(s.libraryId, "i.ABC")
        XCTAssertEqual(s.albumId, "library:l.XYZ")
        XCTAssertFalse(s.isImport)
    }

    func test_should_prefix_library_id_when_a_library_song_has_no_catalog_song() {
        let r = json("""
        {"id":"i.IMPORT","type":"library-songs","attributes":{"name":"Demo","artistName":"Me",
         "playParams":{"id":"i.IMPORT","kind":"song","isLibrary":true}},
         "relationships":{"catalog":{"data":[]}}}
        """)
        let s = AppleMusicMapping.song(r)!
        XCTAssertEqual(s.rawId, "library:i.IMPORT")
        XCTAssertTrue(s.isImport)
        XCTAssertEqual(AppleMusicMapping.resourceId(s.rawId), "i.IMPORT")
        XCTAssertTrue(AppleMusicMapping.isLibraryId(s.rawId))
    }

    func test_should_keep_catalog_fields_when_mapping_a_track() {
        let s = AppleMusicMapping.song(catalogSong())!
        XCTAssertEqual(s.albumId, "1440857781", "album id read off the song's web URL")
        let t = AppleMusicMapping.track(s, index: 0, albumArtist: "Kiri Ono")
        XCTAssertEqual(t.id, MediaId(provider: "applemusic", rawId: "1440857786"))
        XCTAssertEqual(t.number, 3)
        XCTAssertEqual(t.durationSec, 245)
        XCTAssertNil(t.artist, "same as the album artist")
        XCTAssertNil(t.rating)
        XCTAssertFalse(t.starred, "library membership is not a heart")
        let entry = AppleMusicMapping.entry(s)
        XCTAssertEqual(entry.album.id.rawId, "1440857781")
        XCTAssertEqual(entry.album.year, 2021)
        XCTAssertEqual(entry.album.genre, "Dream Pop")
        XCTAssertEqual(entry.album.coverURL?.absoluteString,
                       "https://is1-ssl.mzstatic.com/image/thumb/Music/ab/cd/600x600bb.jpg")
    }

    func test_should_fill_artwork_template_and_drop_non_http_urls() {
        XCTAssertEqual(AppleMusicMapping.artworkURL(art, size: 300)?.absoluteString,
                       "https://is1-ssl.mzstatic.com/image/thumb/Music/ab/cd/300x300bb.jpg")
        XCTAssertNil(AppleMusicMapping.artworkURL("musicKit://artwork/transient/{w}x{h}"))
        XCTAssertNil(AppleMusicMapping.artworkURL(nil))
    }

    func test_should_strip_suffix_when_title_names_an_ep_or_single() {
        XCTAssertEqual(AppleMusicMapping.release(title: "Tides - EP", isSingle: false, isCompilation: false).0, .ep)
        XCTAssertEqual(AppleMusicMapping.release(title: "Tides - EP", isSingle: false, isCompilation: false).1, "Tides")
        XCTAssertEqual(AppleMusicMapping.release(title: "Glow - Single", isSingle: true, isCompilation: false).1, "Glow")
        XCTAssertEqual(AppleMusicMapping.release(title: "Glow", isSingle: true, isCompilation: false).0, .single)
        XCTAssertEqual(AppleMusicMapping.release(title: "Best Of", isSingle: false, isCompilation: true).0, .compilation)
        XCTAssertEqual(AppleMusicMapping.release(title: "Harbor", isSingle: false, isCompilation: false).0, .album)
    }

    func test_should_read_year_from_release_date() {
        XCTAssertEqual(AppleMusicMapping.year("2019-05-24"), 2019)
        XCTAssertEqual(AppleMusicMapping.year("1999"), 1999)
        XCTAssertEqual(AppleMusicMapping.year(nil), 0)
        XCTAssertEqual(AppleMusicMapping.year("x"), 0)
    }

    func test_should_open_library_album_as_catalog_id_and_dedupe_tracks() {
        let r = json("""
        {"id":"l.XYZ","type":"library-albums","attributes":{"name":"Harbor Lights - EP","artistName":"Kiri Ono",
         "trackCount":2,"dateAdded":"2024-03-17T10:00:00Z","artwork":{"url":"\(art)"}},
         "relationships":{"catalog":{"data":[{"id":"1440857781","type":"albums","attributes":{"releaseDate":"2021-05-24"}}]}}}
        """)
        var am = AppleMusicMapping.album(r)!
        XCTAssertEqual(am.rawId, "1440857781")
        let s = AppleMusicMapping.song(catalogSong())!
        am.tracks = [s, s]
        let album = AppleMusicMapping.album(am)
        XCTAssertEqual(album.id, MediaId(provider: "applemusic", rawId: "1440857781"))
        XCTAssertEqual(album.title, "Harbor Lights")
        XCTAssertEqual(album.kind, .ep)
        XCTAssertEqual(album.year, 2021)
        XCTAssertEqual(album.created, "2024-03-17T10:00:00Z")
        XCTAssertEqual(album.tracks.count, 1, "two library copies of one catalog song are one row")
    }

    func test_should_keep_only_albums_when_a_list_mixes_types() {
        let list = [
            json(#"{"id":"1","type":"albums","attributes":{"name":"A","artistName":"X"}}"#),
            json(#"{"id":"pl.1","type":"playlists","attributes":{"name":"P"}}"#),
            json(#"{"id":"ra.1","type":"stations","attributes":{"name":"S"}}"#),
            json(#"{"id":"1","type":"albums","attributes":{"name":"A","artistName":"X"}}"#),
        ]
        XCTAssertEqual(AppleMusicMapping.albums(in: list).map(\.id.rawId), ["1"])
    }

    func test_should_prefix_library_playlists_and_read_description() {
        let p = AppleMusicMapping.playlist(json("""
        {"id":"p.ABC","type":"library-playlists","attributes":{"name":"Late","description":{"standard":"For night"}}}
        """))!
        XCTAssertEqual(p.rawId, "library:p.ABC")
        let info = AppleMusicMapping.playlist(p, songs: [AppleMusicMapping.song(catalogSong())!])
        XCTAssertEqual(info.description, "For night")
        XCTAssertEqual(info.entries.count, 1)
        XCTAssertEqual(AppleMusicMapping.playlistItem(p).subtitle, "Playlist")
        let catalog = AppleMusicMapping.playlist(json(#"{"id":"pl.u-1","type":"playlists","attributes":{"name":"Top"}}"#))!
        XCTAssertEqual(catalog.rawId, "pl.u-1")
    }

    func test_should_sort_artist_releases_newest_first_and_take_five_top_songs() {
        let artist = AMArtist(resourceId: "123", isLibrary: false, catalogId: "123", name: "Kiri Ono", artwork: nil)
        func album(_ id: String, _ year: String) -> Album {
            AppleMusicMapping.album(AMAlbum(resourceId: id, isLibrary: false, catalogId: id, title: id, artist: "Kiri Ono",
                                            releaseDate: year, isSingle: false, isCompilation: false, tracks: []))
        }
        let songs = (1...7).map { AppleMusicMapping.song(catalogSong(id: "\($0)", track: $0))! }
        let page = AppleMusicMapping.artistPage(artist, albums: [album("a", "2018"), album("b", "2023"), album("a", "2018")],
                                                topSongs: songs)
        XCTAssertEqual(page.releases.map(\.id.rawId), ["b", "a"])
        XCTAssertEqual(page.mostPlayed.count, 5)
        XCTAssertEqual(page.palette, page.releases[0].palette, "no portrait: the newest release's cover")
    }

    func test_should_round_trip_raw_id_through_stream_url() {
        for id in ["1440857786", "library:i.ABC"] {
            let url = AppleMusicMapping.streamURL(rawId: id)
            XCTAssertEqual(url.scheme, "applemusic")
            XCTAssertEqual(AppleMusicMapping.rawId(fromStreamURL: url), id)
            XCTAssertTrue(RoutingAudioOutput.isApple(url))
        }
        XCTAssertNil(AppleMusicMapping.rawId(fromStreamURL: URL(string: "https://h/rest/stream?id=1")!))
        XCTAssertFalse(RoutingAudioOutput.isApple(URL(string: "https://h/rest/stream?id=1")!))
    }

    func test_should_default_to_subsonic_when_signed_in_before_any_choice() {
        let d = UserDefaults(suiteName: "AppleMusicMappingTests")!
        d.removePersistentDomain(forName: "AppleMusicMappingTests")
        XCTAssertEqual(MainActor.assumeIsolated { SourceSelection(subsonicSignedIn: true, defaults: d).active }, .subsonic)
        XCTAssertEqual(MainActor.assumeIsolated { SourceSelection(subsonicSignedIn: false, defaults: d).active }, .mock)
        MainActor.assumeIsolated { SourceSelection(subsonicSignedIn: false, defaults: d).use(.appleMusic) }
        XCTAssertEqual(MainActor.assumeIsolated { SourceSelection(subsonicSignedIn: true, defaults: d).active }, .appleMusic)
        d.removePersistentDomain(forName: "AppleMusicMappingTests")
    }
}
