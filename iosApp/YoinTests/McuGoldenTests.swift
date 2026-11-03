import XCTest
@testable import Yoin

/// The Swift MCU port (Theme/MCU) against the Kotlin it was ported from.
///
/// `Fixtures/mcu-golden.json` is written by the Android unit test
/// `McuGoldenFixtureTest` (ExpressiveColorSchemeFactory: SchemeExpressive,
/// SPEC_2025, contrast 0, phone). Every role must match exactly.
final class McuGoldenTests: XCTestCase {
    private struct Fixture: Decodable {
        struct Entry: Decodable {
            let seed: String
            let dark: Bool
            let roles: [String: String]
        }
        let schemes: [Entry]
    }

    func test_should_match_kotlin_golden_when_building_expressive_scheme_from_seed() throws {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "mcu-golden", withExtension: "json"))
        let fixture = try JSONDecoder().decode(Fixture.self, from: Data(contentsOf: url))
        XCTAssertFalse(fixture.schemes.isEmpty)

        var checked = 0
        var mismatches: [String] = []
        for entry in fixture.schemes {
            let seed = try XCTUnwrap(Int(entry.seed, radix: 16))
            let swift = CoverScheme.argbRoles(seedArgb: seed, dark: entry.dark)
            XCTAssertEqual(Set(swift.keys), Set(entry.roles.keys), "role set for \(entry.seed)")
            for (role, expectedHex) in entry.roles.sorted(by: { $0.key < $1.key }) {
                checked += 1
                let actual = swift[role].map { String(format: "%08X", $0) } ?? "missing"
                if actual != expectedHex {
                    mismatches.append("\(entry.seed) \(entry.dark ? "dark" : "light") \(role): kotlin \(expectedHex) swift \(actual)")
                }
            }
        }
        print("McuGoldenTests: \(checked) roles checked, \(mismatches.count) mismatches")
        XCTAssertTrue(mismatches.isEmpty, mismatches.joined(separator: "\n"))
    }
}
