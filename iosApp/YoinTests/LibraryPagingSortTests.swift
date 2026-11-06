import SwiftUI
import XCTest
@testable import Yoin

/// Library › Songs paging (Library/NewestAlbumSongsPager.swift, Android
/// NewestAlbumSongsPagerTest) and the sorts / rail (Library/LibrarySort.swift).
@MainActor
final class LibraryPagingSortTests: XCTestCase {
    private let palette = CoverPalette(a: .red, b: .green, c: .blue)

    private func album(_ n: Int, songs: [Int], created: String? = nil, artist: String = "A") -> Album {
        Album(id: MediaId(provider: "subsonic", rawId: "al\(n)"), title: "Album \(n)", artist: artist, year: 2020,
              palette: palette,
              tracks: songs.map { Track(id: MediaId(provider: "subsonic", rawId: "s\($0)"), number: $0, title: "S\($0)",
                                        durationSec: 60) },
              created: created)
    }

    func test_should_page_songs_newest_album_first_when_more_follow() async throws {
        let albums = (0..<12).map { album($0, songs: [$0 * 10, $0 * 10 + 1]) }
        let pager = NewestAlbumSongsPager(albumsPerPage: 5, parallelism: 2, reread: 2,
            loadAlbums: { offset, size in Array(albums.dropFirst(offset).prefix(size)) },
            loadAlbum: { $0 })
        let first = try await pager.next()
        XCTAssertEqual(first.map(\.track.id.rawId), ["s0", "s1", "s10", "s11", "s20", "s21", "s30", "s31", "s40", "s41"])
        XCTAssertFalse(pager.reachedEnd)
        let second = try await pager.next()
        XCTAssertEqual(second.first?.track.id.rawId, "s50")
        XCTAssertEqual(second.count, 10)
        let third = try await pager.next()
        XCTAssertEqual(third.map(\.track.id.rawId), ["s100", "s101", "s110", "s111"])
        XCTAssertTrue(pager.reachedEnd)
        let none = try await pager.next()
        XCTAssertTrue(none.isEmpty)
    }

    func test_should_read_the_same_page_again_when_a_read_failed() async throws {
        struct Boom: Error {}
        var fail = true
        let albums = (0..<3).map { album($0, songs: [$0]) }
        let pager = NewestAlbumSongsPager(albumsPerPage: 5, parallelism: 3, reread: 0,
            loadAlbums: { offset, size in Array(albums.dropFirst(offset).prefix(size)) },
            loadAlbum: { a in if a.id.rawId == "al1" && fail { throw Boom() }; return a })
        do { _ = try await pager.next(); XCTFail("expected a failure") } catch {}
        fail = false
        let songs = try await pager.next()
        XCTAssertEqual(songs.map(\.track.id.rawId), ["s0", "s1", "s2"])
    }

    func test_should_leave_out_an_album_when_it_fails_twice() async throws {
        struct Boom: Error {}
        let albums = (0..<3).map { album($0, songs: [$0]) }
        let pager = NewestAlbumSongsPager(albumsPerPage: 5, parallelism: 3, reread: 0,
            loadAlbums: { offset, size in Array(albums.dropFirst(offset).prefix(size)) },
            loadAlbum: { a in if a.id.rawId == "al1" { throw Boom() }; return a })
        do { _ = try await pager.next(); XCTFail("expected a failure") } catch {}
        let songs = try await pager.next()
        XCTAssertEqual(songs.map(\.track.id.rawId), ["s0", "s2"])
    }

    func test_should_list_a_song_once_when_two_albums_hold_it() async throws {
        let albums = [album(0, songs: [1, 2]), album(1, songs: [2, 3])]
        let pager = NewestAlbumSongsPager(loadAlbums: { offset, size in Array(albums.dropFirst(offset).prefix(size)) },
                                          loadAlbum: { $0 })
        let songs = try await pager.next()
        XCTAssertEqual(songs.map(\.track.id.rawId), ["s1", "s2", "s3"])
    }

    // (`LibraryItem` is ambiguous with YoinShared's here: built through the mapping.)
    private let item = { (a: Album) in AppleMusicMapping.albumItem(a) }

    func test_should_order_recents_by_last_seen_then_added_when_sorting() {
        let a = item(album(1, songs: [], created: "2024-01-01T00:00:00Z"))
        let b = item(album(2, songs: [], created: "2025-01-01T00:00:00Z"))
        let c = item(album(3, songs: [], created: nil))
        let sorter = LibrarySorter(recents: ["album:al3": 1_000], ignoresArticles: false)
        XCTAssertEqual(sorter.sorted([a, b, c], by: .recents).map(\.id.rawId), ["al3", "al2", "al1"])
        XCTAssertEqual(sorter.sorted([a, b, c], by: .recentlyAdded).map(\.id.rawId), ["al2", "al1", "al3"])
    }

    func test_should_skip_leading_articles_when_the_source_sorts_past_them() {
        var the = item(album(8, songs: [])), abba = item(album(9, songs: []))
        the.kind = .artist; the.title = "The Apples"
        abba.kind = .artist; abba.title = "Mono"
        XCTAssertEqual(LibrarySorter(recents: [:], ignoresArticles: true).sorted([abba, the], by: .alphabetical)
            .map(\.title), ["The Apples", "Mono"])
        XCTAssertEqual(LibrarySorter(recents: [:], ignoresArticles: false).sorted([the, abba], by: .alphabetical)
            .map(\.title), ["Mono", "The Apples"])
    }

    func test_should_label_recents_rail_from_dates_when_records_exist() {
        let now = Date(timeIntervalSince1970: 1_780_000_000)
        let nowMs = Int64(now.timeIntervalSince1970 * 1000)
        let items = (1...3).map { item(album($0, songs: [])) }
        let sorter = LibrarySorter(recents: ["album:al1": nowMs - 60_000], ignoresArticles: false)
        let sorted = sorter.sorted(items, by: .recents)
        let sections = LibraryScrollIndex.sections(sorted, sort: .recents, sorter: sorter, now: now)
        XCTAssertEqual(sections.map(\.label), [String(localized: "Today"), String(localized: "Not opened")])
        XCTAssertEqual(sections.map(\.start), [0, 1])
        // Nothing recorded: no ticks, never invented buckets.
        let none = LibrarySorter(recents: [:], ignoresArticles: false)
        XCTAssertTrue(LibraryScrollIndex.sections(items, sort: .recents, sorter: none, now: now).isEmpty)
    }

    func test_should_key_repeats_apart_when_a_song_is_listed_twice() {
        XCTAssertEqual(LibraryGrid.occurrenceKeys(["a", "b", "a"]), ["a", "b", "a#2"])
    }

    func test_should_add_columns_when_the_pane_is_wider_than_a_phone() {
        XCTAssertEqual(LibraryGrid.columns(width: 402, regularWidth: false, compactHeight: false), 3)
        XCTAssertEqual(LibraryGrid.columns(width: 800, regularWidth: true, compactHeight: false), 7)
        // An iPad shell column (600) is still a wide window's pane: 5, not 3.
        XCTAssertEqual(LibraryGrid.columns(width: 600, regularWidth: true, compactHeight: false), 5)
        XCTAssertEqual(LibraryGrid.columns(width: 874, regularWidth: false, compactHeight: true), 7)
    }
}
