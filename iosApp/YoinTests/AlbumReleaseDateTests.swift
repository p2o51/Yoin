import XCTest
@testable import Yoin

/// The album's release date at the service's precision (Android #310, `AlbumReleaseDateTextTest` /
/// `AppleMusicReleaseDateTest` / `AlbumScrapbookTest`): the header's compact date, the full date under the last
/// track per language, the receipt's Released / Length, and Apple Music's library → catalog fallback.
final class AlbumReleaseDateTests: XCTestCase {
    private let en = Locale(identifier: "en")
    private let zh = Locale(identifier: "zh-Hans")
    private let ja = Locale(identifier: "ja")

    func test_should_writeTheHeaderDate_atEachPrecision() {
        XCTAssertEqual(AlbumReleaseDateText.header("2022-06-12", year: 2022), "22.6.12")
        XCTAssertEqual(AlbumReleaseDateText.header("2022-06", year: 2022), "22.6")
        XCTAssertEqual(AlbumReleaseDateText.header("2022", year: 2022), "2022")
        XCTAssertEqual(AlbumReleaseDateText.header("2005-11-03", year: 2005), "05.11.3", "two-digit year, no zero padding")
        XCTAssertEqual(AlbumReleaseDateText.header(nil, year: 1999), "1999", "no date: the year")
        XCTAssertNil(AlbumReleaseDateText.header(nil, year: 0), "neither: nothing")
        XCTAssertEqual(AlbumReleaseDateText.header("0000", year: 2020), "2020", "Spotify's 0000 is no date")
        XCTAssertEqual(AlbumReleaseDateText.header("2023-02-30", year: 2023), "23.2", "an impossible day is dropped")
    }

    func test_should_writeTheFullDate_inTheAppsLanguage() {
        XCTAssertEqual(AlbumReleaseDateText.full("2023-10-27", locale: en), "October 27, 2023")
        XCTAssertEqual(AlbumReleaseDateText.full("2023-10", locale: en), "October 2023")
        XCTAssertEqual(AlbumReleaseDateText.full("2023", locale: en), "2023")
        XCTAssertEqual(AlbumReleaseDateText.full("2023-10-27", locale: zh), "2023年10月27日")
        XCTAssertEqual(AlbumReleaseDateText.full("2023-10", locale: zh), "2023年10月")
        XCTAssertEqual(AlbumReleaseDateText.full("2023", locale: zh), "2023")
        XCTAssertEqual(AlbumReleaseDateText.full("2023-10-27", locale: ja), "2023年10月27日")
        XCTAssertNil(AlbumReleaseDateText.full(nil, locale: en))
    }

    /// The first and last day of a year stay on their day whatever the device's zone (noon UTC, formatted in UTC).
    func test_should_keepTheDay_inAnyTimeZone() {
        let saved = NSTimeZone.default
        defer { NSTimeZone.default = saved }
        for zone in ["Pacific/Kiritimati", "Pacific/Pago_Pago", "Asia/Tokyo"] {
            NSTimeZone.default = TimeZone(identifier: zone)!
            XCTAssertEqual(AlbumReleaseDateText.full("2024-01-01", locale: en), "January 1, 2024", zone)
            XCTAssertEqual(AlbumReleaseDateText.receiptDate("2023-12-31"), "Dec 31, 2023", zone)
        }
    }

    func test_should_writeTheReceiptsReleasedAndLength() {
        XCTAssertEqual(AlbumReleaseDateText.receiptDate("2022-06-12"), "Jun 12, 2022")
        XCTAssertEqual(AlbumReleaseDateText.receiptDate("2022-06"), "Jun 2022")
        XCTAssertEqual(AlbumReleaseDateText.receiptDate("2022"), "2022")
        XCTAssertNil(AlbumReleaseDateText.receiptDate(nil))
        XCTAssertEqual(AlbumReleaseDateText.receiptLength(seconds: 47 * 60 + 30), "47 min")
        XCTAssertEqual(AlbumReleaseDateText.receiptLength(seconds: 72 * 60), "1 hr 12 min")
        XCTAssertEqual(AlbumReleaseDateText.receiptLength(seconds: 120 * 60), "2 hr")
        XCTAssertEqual(AlbumReleaseDateText.receiptLength(seconds: 20), "1 min")
        XCTAssertNil(AlbumReleaseDateText.receiptLength(seconds: 0))
        XCTAssertNil(AlbumReleaseDateText.receiptLength(seconds: nil))
    }

    /// Released then Length open the receipt, each left out when unknown; the length is the album's own, else the
    /// tracks summed.
    func test_should_openTheReceiptWithReleasedAndLength() {
        var album = MockLibrary.allAlbums[0]
        album.releaseDate = "2024-03-08"
        let summed = album.tracks.map(\.durationSec).reduce(0, +)
        XCTAssertEqual(AlbumReleaseDateText.receiptRows(album).map(\.0), ["Released", "Length"])
        XCTAssertEqual(AlbumReleaseDateText.receiptRows(album).map(\.1),
                       ["Mar 8, 2024", AlbumReleaseDateText.receiptLength(seconds: summed)!])
        album.durationSec = 3_600
        XCTAssertEqual(AlbumReleaseDateText.receiptRows(album).last?.1, "1 hr", "the album's own length first")
        album.releaseDate = nil
        album.tracks = []
        album.durationSec = nil
        XCTAssertTrue(AlbumReleaseDateText.receiptRows(album).isEmpty)
    }

    /// Apple Music: a library album with no date or only the year takes its catalog album's more precise date of the
    /// same year; a different year is another version and keeps its own.
    func test_should_takeTheCatalogsDate_forAYearOnlyLibraryAlbum() {
        XCTAssertEqual(AppleMusicMapping.libraryReleaseDate("2021", catalog: "2021-05-24"), "2021-05-24")
        XCTAssertEqual(AppleMusicMapping.libraryReleaseDate(nil, catalog: "2021-05-24"), "2021-05-24")
        XCTAssertEqual(AppleMusicMapping.libraryReleaseDate("2019", catalog: "2021-05-24"), "2019")
        XCTAssertEqual(AppleMusicMapping.libraryReleaseDate("2021-05-01", catalog: "2021-05-24"), "2021-05-01")
        XCTAssertEqual(AppleMusicMapping.libraryReleaseDate("2021", catalog: nil), "2021")
        XCTAssertEqual(AppleMusicMapping.libraryReleaseDate("2021-05-24", catalog: nil), "2021-05-24")
    }
}
