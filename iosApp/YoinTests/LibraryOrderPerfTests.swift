import Observation
import XCTest
@testable import Yoin

/// Library's sort keys and memo (Data/LibraryStore.epochMs, Library/
/// LibraryView LibraryOrderMemo): build 8 sorted every scroll frame with two
/// date formatters per album until the watchdog killed it.
@MainActor
final class LibraryOrderPerfTests: XCTestCase {
    private func formatter(_ iso: String) -> Int64? {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let date = f.date(from: iso) ?? {
            f.formatOptions = [.withInternetDateTime]
            return f.date(from: iso)
        }()
        return date.map { Int64($0.timeIntervalSince1970 * 1000) }
    }

    func test_should_match_the_formatter_when_reading_server_dates() {
        for iso in ["2024-03-17T08:15:30Z", "2024-03-17T08:15:30.123Z", "2024-03-17T08:15:30.1Z",
                    "2024-03-17T08:15:30.123456Z", "2024-02-29T23:59:59+09:00", "1999-12-31T00:00:00-05:30",
                    "2024-03-17T08:15:30.5+0100", "1969-07-20T20:17:40Z", "2000-01-01T00:00:00.000Z"] {
            XCTAssertNotNil(LibraryStore.fastEpochMs(iso), iso)
            XCTAssertEqual(LibraryStore.epochMs(iso), formatter(iso), iso)
        }
    }

    func test_should_fall_back_or_refuse_when_the_date_is_not_plain() {
        XCTAssertNil(LibraryStore.fastEpochMs("2024-03-17"))
        XCTAssertNil(LibraryStore.fastEpochMs("2024-03-17T08:15:30"))
        XCTAssertNil(LibraryStore.fastEpochMs("2024-13-17T08:15:30Z"))
        XCTAssertNil(LibraryStore.epochMs("not a date"))
        XCTAssertNil(LibraryStore.epochMs(nil))
    }

    func test_should_sort_thousands_of_albums_quickly_when_dated() {
        let palette = CoverPalette(a: .red, b: .green, c: .blue)
        let items = (0..<4000).map { n in
            let album = Album(id: MediaId(provider: "subsonic", rawId: "al\(n)"), title: "Album \(n)",
                              artist: "Artist \(n % 300)", year: 2020, palette: palette, tracks: [],
                              created: String(format: "20%02d-%02d-%02dT10:00:00.000Z", n % 25, n % 12 + 1, n % 28 + 1))
            return LibraryItem(id: album.id, kind: .album, title: album.title, subtitle: album.artist,
                               palette: palette, album: album)
        }
        let sorter = LibrarySorter(recents: [:], ignoresArticles: true)
        let start = Date()
        let sorted = sorter.sorted(items, by: .recentlyAdded)
        _ = LibraryScrollIndex.sections(sorted, sort: .recentlyAdded, sorter: sorter)
        XCTAssertEqual(sorted.count, 4000)
        XCTAssertLessThan(Date().timeIntervalSince(start), 1.0)
    }

    @Observable final class Source { var n = 1 }

    func test_should_build_once_and_rebuild_when_what_it_read_changes() async {
        let memo = LibraryOrderMemo()
        let source = Source()
        var builds = 0
        let key = LibraryOrderMemo.Key(filter: nil, sort: .recents, byYou: false, real: true)
        func read() -> Int {
            memo.value(for: key) {
                builds += 1
                return .init(items: Array(repeating: LibraryItem(id: MediaId(provider: "subsonic", rawId: "x"),
                                                                 kind: .album, title: "", subtitle: "",
                                                                 palette: CoverPalette(a: .red, b: .green, c: .blue)),
                                          count: source.n), sections: [])
            }.items.count
        }
        XCTAssertEqual(read(), 1)
        XCTAssertEqual(read(), 1)
        XCTAssertEqual(builds, 1)
        source.n = 2
        await Task.yield()
        try? await Task.sleep(for: .milliseconds(50))
        XCTAssertEqual(read(), 2)
        XCTAssertEqual(builds, 2)
        _ = memo.value(for: .init(filter: .albums, sort: .recents, byYou: false, real: true)) { builds += 1; return .init(items: [], sections: []) }
        XCTAssertEqual(builds, 3)
    }
}
