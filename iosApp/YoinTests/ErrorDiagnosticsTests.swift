import XCTest
@testable import Yoin

/// What Copy details puts on the clipboard when a page fails (Android ErrorDiagnostics `diagnosticReport`).
final class ErrorDiagnosticsTests: XCTestCase {
    private let noon = Date(timeIntervalSince1970: 1_794_657_600.4)

    func test_should_listBuildDeviceFailureAndTime_inAndroidsOrder() {
        let report = ErrorDiagnostics.report(SpotifyAPIError.http(status: 502, message: nil), now: noon,
                                             build: "Yoin 0.6.0 (32)", device: "iOS 26.1, iPhone18,1")
        XCTAssertEqual(report, "Yoin 0.6.0 (32)\niOS 26.1, iPhone18,1\nSpotify HTTP 502\n2026-11-14T12:00:00Z")
    }

    func test_should_neverQuoteTheServersOwnWords_whenSpotifyRefuses() {
        let line = ErrorDiagnostics.line(SpotifyAPIError.http(status: 404, message: "Non existing id: 'spotify:album:4aawyAB9vmqN3uQ7FjRGTy'"))
        XCTAssertEqual(line, "Spotify HTTP 404")
        XCTAssertEqual(ErrorDiagnostics.line(SpotifyAPIError.unauthorized), "Spotify HTTP 401")
        XCTAssertEqual(ErrorDiagnostics.line(SpotifyAPIError.rateLimited(retryAfter: 30)), "Spotify HTTP 429, retry after 30s")
    }

    func test_should_nameTheRequestAsATemplate_whenSpotifysAnswerWontRead() {
        XCTAssertEqual(ErrorDiagnostics.line(SpotifyAPIError.decoding("/v1/albums/4aawyAB9vmqN3uQ7FjRGTy/tracks")),
                       "Spotify response unreadable: /v1/albums/{id}/tracks")
        // A user's id is all lower case: taken out by where it stands.
        XCTAssertEqual(ErrorDiagnostics.template("/v1/users/smedjan/playlists"), "/v1/users/{id}/playlists")
        XCTAssertEqual(ErrorDiagnostics.template("/v1/me/player/currently-playing"), "/v1/me/player/currently-playing")
        XCTAssertEqual(ErrorDiagnostics.template("repeated page"), "repeated page")
    }

    func test_should_sayWhereDecodingStopped_byKeysOnly() throws {
        struct Page: Decodable { var items: [Item] }
        struct Item: Decodable { var name: String }
        let json = Data(#"{"items":[{"name":"A secret title"},{"title":"Another secret"}]}"#.utf8)
        XCTAssertThrowsError(try JSONDecoder().decode(Page.self, from: json)) { error in
            let line = ErrorDiagnostics.line(error)
            XCTAssertEqual(line, "DecodingError keyNotFound name at items[1]")
            XCTAssertFalse(line.contains("secret"))
        }
    }

    func test_should_giveCodes_forNetworkAndOtherFailures_withTheCauseUnderneath() {
        XCTAssertEqual(ErrorDiagnostics.line(URLError(.timedOut)), "URLError -1001")
        XCTAssertEqual(ErrorDiagnostics.line(CancellationError()), "Cancelled")
        let underneath = NSError(domain: NSURLErrorDomain, code: -1009,
                                 userInfo: [NSLocalizedDescriptionKey: "Couldn't reach music.example.org"])
        let failure = NSError(domain: NSCocoaErrorDomain, code: 4864,
                              userInfo: [NSUnderlyingErrorKey: underneath, NSLocalizedDescriptionKey: "at https://music.example.org"])
        let report = ErrorDiagnostics.report(failure, now: noon, build: "Yoin", device: "iOS")
        XCTAssertEqual(report, "Yoin\niOS\nNSCocoaErrorDomain 4864\ncause URLError -1009\n2026-11-14T12:00:00Z")
        XCTAssertFalse(report.contains("example"))
    }
}
