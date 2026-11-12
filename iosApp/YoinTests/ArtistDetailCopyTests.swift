import XCTest
@testable import Yoin

/// The artist page's empty discography line (ArtistDetailView, Android detail_artist_no_releases, parity I05).
final class ArtistDetailCopyTests: XCTestCase {
    func test_should_sayNoReleases_inAndroidsWords() {
        let zh = Bundle.main.preferredLocalizations.first?.hasPrefix("zh") ?? false
        XCTAssertEqual(loc("No releases"), zh ? "无发行作品" : "No releases")
    }
}
