import XCTest
@testable import Yoin

/// The exported playback log names no server (App/PlayTrace.swift; Android PlayTraceTest, f7db9708): every
/// line loses tokens, Subsonic credentials and URL addresses, and an error message logged loses its hosts.
final class PlayTraceRedactTests: XCTestCase {
    func test_should_cutTokensCredentialsAndAddresses_when_aLineIsLogged() {
        let line = PlayTrace.redact(
            "AV load https://alice:pw@music.example.com:4533/rest/stream.view?u=alice&t=deadbeef&s=salt&id=42 Bearer abc.def")
        for secret in ["alice", "pw", "music.example.com", "4533", "deadbeef", "salt", "abc.def"] {
            XCTAssertFalse(line.contains(secret), line)
        }
        XCTAssertTrue(line.contains("id=42"), line)
        XCTAssertTrue(line.hasPrefix("AV load https://…/rest/stream.view?"), line)
    }

    func test_should_cleanAStreamLine_when_anOlderBuildWroteItsServer() {
        // Build 32 and before: "AV load <scheme>://<host>/…/<endpoint>".
        let old = "11-13 21:04:05.123 AV load http://192.168.1.20:4533/…/stream?id=s1&maxBitRate=320"
        XCTAssertEqual(PlayTrace.redact(old), "11-13 21:04:05.123 AV load http://…/…/stream?id=s1&maxBitRate=320")
        // Today's lines are left as they are.
        let new = "AV load https://…/stream?id=s1"
        XCTAssertEqual(PlayTrace.redact(new), new)
    }

    func test_should_leaveSpotifyAndEngineLinesReadable_when_theyNameNoServer() {
        for line in ["API GET me/player? → 200 in 0.21s retry-after=-", "SR wake spotify:track:4uLU6hMCjMI75M1A2tKUQC",
                     "SP state spotify:track:4uLU6hMCjMI75M1A2tKUQC paused=false pos=1200 starting=false wantsPlay=true"] {
            XCTAssertEqual(PlayTrace.redact(line), line)
        }
    }

    func test_should_maskHostAndIp_when_anErrorMessageNamesTheServer() {
        XCTAssertEqual(PlayTrace.maskHosts("Unable to resolve host \"music.example.com\": No address associated with hostname"),
                       "Unable to resolve host \"…\": No address associated with hostname")
        XCTAssertEqual(PlayTrace.maskHosts("failed to connect to music.example.com/203.0.113.4 (port 443) after 10000ms"),
                       "failed to connect to …/… (port 443) after 10000ms")
        let v6 = PlayTrace.maskHosts("failed to connect to /[2001:db8::1]:4533 and http://navidrome:4533/rest/ping")
        XCTAssertFalse(v6.contains("2001:db8") || v6.contains("navidrome"), v6)
        XCTAssertEqual(PlayTrace.maskHosts("A server with the specified hostname could not be found."),
                       "A server with the specified hostname could not be found.")
        XCTAssertEqual(PlayTrace.maskHosts(nil), "")
    }
}
