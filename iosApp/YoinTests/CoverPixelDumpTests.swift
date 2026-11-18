import XCTest
@testable import Yoin

/// Writes each mock cover's 200 × 200 ARGB render (the pixels CoverSeed hands to Palette) to
/// iosApp/build/cover-pixels/<id>.argb (big-endian ARGB words) for the Android Palette parity test.
@MainActor
final class CoverPixelDumpTests: XCTestCase {
    func test_should_write_mock_cover_pixels_when_dumping_the_palette_fixture() throws {
        let iosApp = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let dir = iosApp.appendingPathComponent("build/cover-pixels")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        for album in MockLibrary.allAlbums {
            let cg = try XCTUnwrap(CoverSeed.renderCover(album))
            let (px, w, h) = try XCTUnwrap(CoverSeed.argbPixels(of: cg))
            XCTAssertEqual(w, 200); XCTAssertEqual(h, 200)
            var data = Data(capacity: px.count * 4)
            for p in px { var be = p.bigEndian; withUnsafeBytes(of: &be) { data.append(contentsOf: $0) } }
            try data.write(to: dir.appendingPathComponent("\(album.id.rawId).argb"))
            let pick = CoverSeed.pick(for: album)
            print("DUMP \(album.id.rawId) seed=\(String(format: "%08X", pick?.argb ?? 0)) \(pick?.source.rawValue ?? "-")")
        }
    }
}
