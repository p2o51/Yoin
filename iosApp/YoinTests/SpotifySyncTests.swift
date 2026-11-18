import XCTest
@preconcurrency import YoinShared
@testable import Yoin

/// Codex N08: a Spotify account is a first-class cloud sync profile, its records the bytes Android
/// writes. Fixtures/sync-spotify-golden.json is an Android-shaped state file for one Spotify user,
/// derived from origin/main's adapters (projection fields in Android's order) and its binding id
/// (`"s-" + sha256("spotify:v1:<userId>")[0:24]`, computed outside Kotlin).
@MainActor
final class SpotifySyncTests: XCTestCase {
    private struct Golden {
        var userId: String
        var displayName: String
        var createdAt: Int64
        var scope: String
        var records: [WireRecord]
    }

    private var dir: URL!
    private var spotifyBefore: String?

    override func setUp() async throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        spotifyBefore = AccountScope.shared.spotify
    }

    override func tearDown() async throws {
        AccountScope.shared.spotify = spotifyBefore
        try? FileManager.default.removeItem(at: dir)
    }

    private func golden() throws -> Golden {
        let url = Bundle(for: Self.self).url(forResource: "sync-spotify-golden", withExtension: "json")
            ?? URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .appendingPathComponent("Fixtures/sync-spotify-golden.json")
        let root = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
        return Golden(userId: root["spotifyUserId"] as! String, displayName: root["displayName"] as! String,
                      createdAt: (root["createdAt"] as! NSNumber).int64Value, scope: root["scope"] as! String,
                      records: try golden(envelope: true).records)
    }

    private func golden(envelope: Bool) throws -> SyncEnvelope {
        let url = Bundle(for: Self.self).url(forResource: "sync-spotify-golden", withExtension: "json")
            ?? URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .appendingPathComponent("Fixtures/sync-spotify-golden.json")
        let root = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
        let json = ((root["files"] as! [[String: Any]])[0])["json"] as! String
        let bytes = KotlinByteArray(size: Int32(json.utf8.count))
        for (i, b) in json.utf8.enumerated() { bytes.set(index: Int32(i), value: Int8(bitPattern: b)) }
        return SnapshotCodec.shared.decode(bytes: bytes)
    }

    /// Kotlin calls these on whatever thread it runs on: made outside the main actor, so they aren't
    /// checked for it.
    nonisolated private static func clock(_ now: Int64) -> () -> KotlinLong { { KotlinLong(value: now) } }
    nonisolated private static func constant(_ text: String) -> () -> String { { text } }

    /// The Android identity of a Spotify user binds to the same cloud scope on iOS.
    func test_should_bindToAndroidsScope_when_spotifyUserIsTheSame() throws {
        let g = try golden()
        let fp = AccountFingerprints.shared.spotify(userId: g.userId)
        XCTAssertEqual(fp, "spotify:v1:\(g.userId)")
        XCTAssertEqual(AccountFingerprints.shared.deterministicId(fingerprint: fp), g.scope)
    }

    func test_should_listSpotifyProfiles_withTheirUserIdentity() {
        let bridge = SyncDomainBridge(accounts: {
            .init(subsonic: [], appleMusic: false, storefront: nil,
                  spotify: [.init(profileId: "p-ada", userId: "u-ada", displayName: "Ada", createdAt: 42),
                            .init(profileId: "p-bob", userId: "u-bob", displayName: "Bob", createdAt: 43,
                                  credentialsReadable: false)])
        })
        let profiles = bridge.profiles()
        XCTAssertEqual(profiles.map(\.id), ["p-ada", "p-bob"])
        XCTAssertEqual(profiles.map(\.provider), ["spotify", "spotify"])
        XCTAssertEqual(profiles.map(\.spotifyUserId), ["u-ada", "u-bob"])
        XCTAssertEqual(profiles.map(\.displayName), ["Ada", "Bob"])
        XCTAssertEqual(profiles.map(\.createdAt), [42, 43])
        XCTAssertEqual(profiles.map(\.credentialsReadable), [true, false])
        XCTAssertNil(profiles[0].subsonicServerUrl)
        XCTAssertNil(profiles[0].storefront)
    }

    /// Android's state file for a Spotify account: the binder binds the same user to Android's scope, the
    /// records land in that account's rows (not the account in use's), and this device republishes them
    /// byte for byte; a rating written here goes out in Android's shape under that scope.
    func test_should_roundTripAndroidSpotifyRecords_when_theAccountIsNotInUse() async throws {
        let g = try golden()
        let ada = SyncDomainBridge.Accounts.Spotify(profileId: "p-spotify-ada", userId: g.userId,
                                                    displayName: g.displayName, createdAt: g.createdAt)
        let bob = SyncDomainBridge.Accounts.Spotify(profileId: "p-spotify-bob", userId: "bob-user",
                                                    displayName: "Bob", createdAt: g.createdAt + 6)
        AccountScope.shared.spotify = bob.scope
        let notes = NotesStore(fileURL: dir.appendingPathComponent("notes.json"), scopes: .shared)
        let ratings = LocalRatings(fileURL: dir.appendingPathComponent("ratings.json"), scopes: .shared)
        let albums = AlbumMemoryStore(fileURL: dir.appendingPathComponent("albums.json"), scopes: .shared)
        let bridge = SyncDomainBridge(accounts: {
            .init(subsonic: [], appleMusic: false, storefront: nil, spotify: [ada, bob])
        }, notes: { notes }, ratings: { ratings }, albums: { albums })

        let store = InMemorySyncStore()
        let now: Int64 = 1_793_000_060_000
        let engine = SyncEngine(db: store, adapters: SharedSyncAdapters.shared.create(domain: bridge, syncDb: store),
                                deviceId: "ios0000000000001", deviceName: Self.constant("iPhone"), clock: Self.clock(now))
        let android = try golden(envelope: true)
        _ = try await engine.mergeRemote(
            file: RemoteFile(id: "android-state", name: "yoin-state-a1b2c3d4e5f60718.json.gz", version: 1,
                             modifiedTime: nil, size: nil,
                             appProperties: [SyncFileProps.shared.KIND: "state",
                                             SyncFileProps.shared.DEVICE_ID: android.deviceId]),
            envelope: android)

        let binder = AccountBinder(syncDb: store, profiles: Profiles(bridge), spotifyUserId: SpotifyUser(),
                                   clock: Self.clock(now))
        let snapshot = try await binder.refresh(cloudAccounts: [])
        XCTAssertEqual(snapshot.active.map(\.localProfileId).sorted(), [ada.profileId, bob.profileId])
        let adaBinding = try XCTUnwrap(snapshot.active.first { $0.localProfileId == ada.profileId })
        XCTAssertEqual(adaBinding.syncProfileId, g.scope, "the same Spotify user binds to Android's scope")
        XCTAssertEqual(adaBinding.provider, "spotify")

        _ = try await engine.capture(bindings: snapshot.active)
        let applied = try await engine.apply(bindings: snapshot.active)
        XCTAssertGreaterThanOrEqual(applied.totalApplied, 5)

        // Under Ada's Spotify user: the account in use (Bob) doesn't see them.
        XCTAssertNil(ratings.rating(provider: "spotify", songId: "4uLU6hMCjMI75M1A2tKUQC"))
        XCTAssertTrue(notes.notes.isEmpty)
        AccountScope.shared.reading(asSubsonic: nil, asSpotify: ada.scope) {
            XCTAssertEqual(ratings.rating(provider: "spotify", songId: "4uLU6hMCjMI75M1A2tKUQC"), 7.5)
            XCTAssertEqual(notes.notes.map(\.id), ["note-spotify-01"])
            XCTAssertEqual(notes.notes.first?.positionMs, 61500)
            XCTAssertEqual(albums.album(provider: "spotify", albumId: "6dVIqQ8qmQ5GBnJ9shOYGE")?.rating, 8.4)
            XCTAssertEqual(albums.userTitle(provider: "spotify", albumId: "6dVIqQ8qmQ5GBnJ9shOYGE"),
                           "Summer on the night bus")
            XCTAssertNotNil(albums.memory(provider: "spotify", entityId: "6dVIqQ8qmQ5GBnJ9shOYGE"))
        }
        XCTAssertEqual(bridge.note(id: "note-spotify-01")?.profileId, ada.profileId)
        XCTAssertTrue(bridge.trackRatings(profileId: bob.profileId).isEmpty)
        XCTAssertTrue(bridge.notes(profileId: bob.profileId).isEmpty)

        // Nothing new locally: what goes out is Android's records, untouched; the descriptor is Android's too.
        _ = try await engine.capture(bindings: snapshot.active)
        let out = try await engine.outgoingFiles(epoch: 0, writtenAt: now, appVersionCode: 1)
            .flatMap { SnapshotCodec.shared.decode(bytes: $0.bytes).records }
        func lines(_ rs: [WireRecord]) -> [String] { rs.map { $0.description() }.sorted() }
        XCTAssertEqual(lines(out.filter { $0.kind != "account" && $0.scope == g.scope }),
                       lines(g.records.filter { $0.kind != "account" }))
        let descriptor = try XCTUnwrap(out.first { $0.kind == "account" && $0.key == g.scope })
        let androidDescriptor = try XCTUnwrap(g.records.first { $0.kind == "account" })
        // This device describes the account exactly as Android does, so Android's descriptor stands as written.
        XCTAssertEqual(descriptor.description(), androidDescriptor.description())

        // A rating made here for Ada goes out under her scope, in Android's shape.
        AccountScope.shared.reading(asSubsonic: nil, asSpotify: ada.scope) {
            ratings.set(6, provider: "spotify", songId: "1dGr1c8CrMLDpV6mPbImSI")
        }
        _ = try await engine.capture(bindings: snapshot.active)
        let next = try await engine.outgoingFiles(epoch: 0, writtenAt: now, appVersionCode: 1)
            .flatMap { SnapshotCodec.shared.decode(bytes: $0.bytes).records }
        let mine = try XCTUnwrap(next.first { $0.kind == "track_rating" && $0.key == "spotify\u{1F}1dGr1c8CrMLDpV6mPbImSI" })
        XCTAssertEqual(mine.scope, g.scope)
        XCTAssertEqual(mine.device, "ios0000000000001")
        XCTAssertEqual(mine.description().components(separatedBy: "payload=").last,
                       #"{"songId":"1dGr1c8CrMLDpV6mPbImSI","provider":"spotify","rating":6.0})"#)
        XCTAssertEqual(AccountScope.shared.spotify, bob.scope, "no switch")
    }
}

/// AccountBinder's `profiles` (a Kotlin suspend lambda): the bridge's list, read on the main actor.
/// Main-actor isolated: Kotlin resumes on the main thread, where the bridge is read.
@MainActor
private final class Profiles: NSObject, @preconcurrency KotlinSuspendFunction0 {
    private let bridge: SyncDomainBridge
    init(_ bridge: SyncDomainBridge) { self.bridge = bridge }
    func invoke() async throws -> Any? { bridge.profiles() }
}

/// AccountBinder's `spotifyUserId`: the profile's own (no /v1/me), as the app's default.
@MainActor
private final class SpotifyUser: NSObject, @preconcurrency KotlinSuspendFunction1 {
    func invoke(p1: Any?) async throws -> Any? { (p1 as? SyncProfile)?.spotifyUserId }
}
