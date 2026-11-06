import XCTest

/// Every translation in Yoin/Localizable.xcstrings must take the same format arguments as its English key:
/// the same count, types and (by position) order. A Chinese "%1$s" where the key has "%@" read a Swift string
/// as a C string and crashed Chinese phones (build 14, the scrapbook page).
final class StringCatalogFormatTests: XCTestCase {
    private struct Catalog: Decodable {
        struct Unit: Decodable { var value: String }
        struct Loc: Decodable { var stringUnit: Unit? }
        struct Entry: Decodable { var localizations: [String: Loc]? }
        var strings: [String: Entry]
    }

    /// The catalog next to the app's sources.
    private func catalog() throws -> Catalog {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Yoin/Localizable.xcstrings")
        return try JSONDecoder().decode(Catalog.self, from: Data(contentsOf: url))
    }

    /// The arguments a format takes, by position: "%1$@ / %2$lld" → [1: "@", 2: "lld"]. A stray "%" (not
    /// "%%" and not a known specifier) is reported as "?".
    static func arguments(_ format: String) -> [Int: String] {
        let regex = try! NSRegularExpression(pattern: #"%(%|(\d+)\$)?(lld|ld|llu|lu|lf|d|u|f|@|s|)"#)
        let ns = format as NSString
        var out: [Int: String] = [:]
        var next = 1
        for m in regex.matches(in: format, range: NSRange(location: 0, length: ns.length)) {
            let all = ns.substring(with: m.range)
            if all == "%%" { continue }
            let type = m.range(at: 3).location != NSNotFound ? ns.substring(with: m.range(at: 3)) : ""
            let position = m.range(at: 2).location != NSNotFound ? Int(ns.substring(with: m.range(at: 2)))! : next
            next += 1
            out[position] = type.isEmpty || type == "s" ? "?" : type
        }
        return out
    }

    func test_should_take_the_keys_arguments_when_translated() throws {
        var bad: [String] = []
        for (key, entry) in try catalog().strings {
            for (language, loc) in entry.localizations ?? [:] {
                guard let value = loc.stringUnit?.value else { continue }
                if Self.arguments(value) != Self.arguments(key) { bad.append("\(language): \(key) → \(value)") }
            }
        }
        XCTAssertEqual(bad, [], "translations whose format arguments differ from their key")
    }

    func test_should_read_positions_and_types_when_parsing_a_format() {
        XCTAssertEqual(Self.arguments("%1$@ / %2$lld"), [1: "@", 2: "lld"])
        XCTAssertEqual(Self.arguments("第 %lld 首，%@"), [1: "lld", 2: "@"])
        XCTAssertEqual(Self.arguments("%2$@ 的 %1$@"), [2: "@", 1: "@"])
        XCTAssertEqual(Self.arguments("已播放 %lld%%"), [1: "lld"])
        XCTAssertEqual(Self.arguments("%1$s"), [1: "?"])
        XCTAssertNotEqual(Self.arguments("「%1$s」"), Self.arguments("“%@”"))
    }
}
