import XCTest
@testable import Yoin

/// Downloads that finish while Yoin is away are kept under their key for the queue to pick up.
final class BackgroundTransfersTests: XCTestCase {
    func test_should_handOverAFinishedFile_when_itsKeyIsAsked() throws {
        let transfers = BackgroundTransfers(identifier: "test.\(UUID().uuidString)")
        let key = "subsonic|https://a|me|song-\(UUID().uuidString)"
        XCTAssertNil(transfers.takeFinished(key: key))
        let file = transfers.finishedDirectory.appendingPathComponent(OfflineDownloads.fileName(key))
        try Data("audio".utf8).write(to: file)
        defer { try? FileManager.default.removeItem(at: file) }
        XCTAssertEqual(transfers.takeFinished(key: key), file)
        XCTAssertNil(transfers.takeFinished(key: key + "x"))
    }
}
