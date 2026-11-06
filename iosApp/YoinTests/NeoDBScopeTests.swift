import XCTest
@testable import Yoin

/// NeoDB rows per account (Codex, 2026-11-06): two Subsonic servers can both have an album "1".
@MainActor
final class NeoDBScopeTests: XCTestCase {
    private var dir: URL!
    private var scopes: AccountScope!

    override func setUp() async throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        scopes = AccountScope()
        scopes.adoptsLegacyRows = false
        scopes.subsonic = "https://a|me"
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: dir)
    }

    private var file: URL { dir.appendingPathComponent("neodb-mappings.json") }

    func test_should_keepServersApart_when_twoServersShareAnAlbumId() {
        let mappings = NeoDBMappingStore(fileURL: file, scopes: scopes)
        mappings.upsert(externalId: "uuid-a", provider: "subsonic", entityId: "1", now: 1)
        scopes.subsonic = "https://b|me"
        XCTAssertNil(mappings.externalId(provider: "subsonic", entityId: "1"))
        mappings.upsert(externalId: "uuid-b", provider: "subsonic", entityId: "1", now: 2)
        scopes.subsonic = "https://a|me"
        XCTAssertEqual(NeoDBMappingStore(fileURL: file, scopes: scopes).externalId(provider: "subsonic", entityId: "1"), "uuid-a")
    }

    func test_should_keepOldMappings_when_theFirstAccountReadsThem() throws {
        // A file from before scoping: no "scope" field.
        let old = #"[{"externalId":"uuid-old","provider":"subsonic","entityType":"album","entityId":"1","syncedAt":1},"#
            + #"{"externalId":"uuid-apple","provider":"applemusic","entityType":"album","entityId":"9","syncedAt":1}]"#
        try Data(old.utf8).write(to: file)
        let mappings = NeoDBMappingStore(fileURL: file, scopes: scopes)
        XCTAssertEqual(mappings.externalId(provider: "subsonic", entityId: "1"), "uuid-old")
        XCTAssertEqual(mappings.externalId(provider: "applemusic", entityId: "9"), "uuid-apple")
        // Claimed by that account: another server doesn't see it.
        scopes.subsonic = "https://b|me"
        XCTAssertNil(NeoDBMappingStore(fileURL: file, scopes: scopes).externalId(provider: "subsonic", entityId: "1"))
    }
}
