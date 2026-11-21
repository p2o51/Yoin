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

    func test_should_read_persisted_sidecar_colors_synchronously_on_cold_slot_creation() throws {
        let urlA = URL(string: "https://music.example/rest/getCoverArt.view?id=al-sidecar-test&size=400&t=aaa&s=salt1")!
        let urlB = URL(string: "https://music.example/rest/getCoverArt.view?id=al-sidecar-test&size=400&t=bbb&s=salt2")!
        let refA = CoverRef(url: urlA)
        let refB = CoverRef(url: urlB)
        XCTAssertEqual(refA.key, refB.key)

        let sidecar = CoverArtwork.file(for: refA.key).appendingPathExtension("colors")
        let expected = CoverArtwork.Colors(seedArgb: 0xFF2855A0, lightArgb: 0xFF7FA6E8, darkArgb: 0xFF102448)
        try CoverArtwork.encodeColors(expected).write(to: sidecar, options: .atomic)
        defer { try? FileManager.default.removeItem(at: sidecar) }

        let fresh = CoverArtwork()
        let slot = fresh.slot(refB)
        XCTAssertEqual(slot.colors?.seedArgb, expected.seedArgb)
        XCTAssertEqual(slot.colors?.lightArgb, expected.lightArgb)
        XCTAssertEqual(slot.colors?.darkArgb, expected.darkArgb)
    }

    func test_should_extract_cover_colors_in_single_cgcontext_pass() throws {
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: 120, height: 120))
        let image = renderer.image { ctx in
            UIColor(red: 0.12, green: 0.35, blue: 0.78, alpha: 1.0).setFill()
            ctx.fill(CGRect(x: 0, y: 0, width: 120, height: 120))
        }
        let colors = try XCTUnwrap(CoverArtwork.colors(of: image))
        let r = (colors.seedArgb >> 16) & 0xFF
        let b = colors.seedArgb & 0xFF
        XCTAssertGreaterThan(b, r, "expected blue-dominant seed for blue cover")
    }
}
