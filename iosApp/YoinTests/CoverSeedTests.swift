import XCTest
@testable import Yoin

/// The cover → seed pipeline on the mock covers. Also writes
/// `iosApp/build/cover-colors.json` (seed, winning swatch and a few light
/// roles per mock album) for the design gallery.
@MainActor
final class CoverSeedTests: XCTestCase {
    func test_should_pick_a_seed_when_reading_each_mock_cover() throws {
        var rows: [String] = []
        for album in MockLibrary.allAlbums {
            let pick = try XCTUnwrap(CoverSeed.pick(for: album), "no swatch for \(album.title)")
            let light = CoverScheme.argbRoles(seedArgb: pick.argb, dark: false)
            func hex(_ v: Int) -> String { String(format: "\"#%06X\"", v & 0xFFFFFF) }
            rows.append(
                "    {\"id\": \"\(album.id.rawId)\", \"title\": \"\(album.title)\", "
                    + "\"paletteA\": \(hex(CoverScheme.argb(of: album.palette.a))), "
                    + "\"seed\": \(hex(pick.argb)), \"swatch\": \"\(pick.source.rawValue)\", "
                    + "\"light\": {\"primary\": \(hex(light["primary"]!)), "
                    + "\"primaryContainer\": \(hex(light["primaryContainer"]!)), "
                    + "\"tertiary\": \(hex(light["tertiary"]!)), "
                    + "\"surface\": \(hex(light["surface"]!))}}"
            )
        }
        let json = "{\n  \"albums\": [\n" + rows.joined(separator: ",\n") + "\n  ]\n}\n"
        print(json)
        // Simulator tests can write to the host: iosApp/build/cover-colors.json.
        let iosApp = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let out = iosApp.appendingPathComponent("build/cover-colors.json")
        try? FileManager.default.createDirectory(at: out.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? json.write(to: out, atomically: true, encoding: .utf8)
    }
}
