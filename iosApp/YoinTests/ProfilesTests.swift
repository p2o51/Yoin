import XCTest
import Security
import YoinShared
@testable import Yoin

/// The account list (Data/Profiles.swift): the one-time migration from the
/// single-server build, adding, removing and switching accounts.
@MainActor
final class ProfilesTests: XCTestCase {
    /// Sign-ins in memory, standing in for the Keychain.
    private final class FakeCredentials: ProfileCredentialStore {
        var items: [String: ServerSession.Account] = [:]
        /// What reading [id] answers instead of the item (a locked Keychain).
        var unavailable: [String: OSStatus] = [:]
        var saves = 0

        func load(_ profileId: String) -> CredentialRead {
            if let status = unavailable[profileId] { return .unavailable(status) }
            return items[profileId].map(CredentialRead.found) ?? .missing
        }

        func save(_ account: ServerSession.Account, for profileId: String) -> OSStatus {
            saves += 1
            items[profileId] = account
            return errSecSuccess
        }

        func delete(_ profileId: String) { items[profileId] = nil }
    }

    private var suite: String!
    private var defaults: UserDefaults!
    private var scopeBefore: String?
    private var adoptsBefore = true
    private let legacy = ServerSession.Account(baseUrl: "http://127.0.0.1:9", username: "51", password: "pw")
    private let other = ServerSession.Account(baseUrl: "http://127.0.0.1:4996", username: "demo", password: "demo")

    override func setUp() async throws {
        suite = "ProfilesTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)
        scopeBefore = AccountScope.shared.subsonic
        adoptsBefore = AccountScope.shared.adoptsLegacyRows
        AccountScope.shared.adoptsLegacyRows = false
    }

    override func tearDown() async throws {
        defaults.removePersistentDomain(forName: suite)
        AccountScope.shared.subsonic = scopeBefore
        AccountScope.shared.adoptsLegacyRows = adoptsBefore
    }

    private func legacyKeychain() -> FakeCredentials {
        let c = FakeCredentials()
        c.items[ProfileStore.legacySubsonicId] = legacy
        return c
    }

    // MARK: Migration

    func test_should_migrateSingleServer_into_oneActiveProfile_withTheSameScope() {
        defaults.set("subsonic", forKey: SourceSelection.key)
        let credentials = legacyKeychain()
        let store = ProfileStore(defaults: defaults, credentials: credentials, appleMusicAuthorized: false)

        XCTAssertEqual(store.profiles.map(\.id), [ProfileStore.legacySubsonicId])
        XCTAssertEqual(store.activeId, ProfileStore.legacySubsonicId)
        XCTAssertEqual(store.signIn(ProfileStore.legacySubsonicId), legacy)
        // The scope its history, notes, memories, downloads and Home layout are kept under, unchanged.
        XCTAssertEqual(store.active?.subsonicScope, AccountScope.subsonicScope(legacy))
        XCTAssertEqual(store.active?.subsonicScope, "http://127.0.0.1:9|51")
        // Read in place: the old Keychain item is that account's item, not copied.
        XCTAssertEqual(credentials.saves, 0)
        XCTAssertEqual(KeychainCredentials.keychainAccount(ProfileStore.legacySubsonicId), KeychainCredentials.legacyAccount)
    }

    func test_should_migrateOnce_when_launchedAgain() {
        defaults.set("subsonic", forKey: SourceSelection.key)
        let credentials = legacyKeychain()
        _ = ProfileStore(defaults: defaults, credentials: credentials, appleMusicAuthorized: false)
        let second = ProfileStore(defaults: defaults, credentials: credentials, appleMusicAuthorized: true)
        // Not migrated again: no second account, and Apple Music (authorized since) isn't added on its own.
        XCTAssertEqual(second.profiles.map(\.id), [ProfileStore.legacySubsonicId])
        XCTAssertEqual(second.activeId, ProfileStore.legacySubsonicId)
        // The setting changing afterwards doesn't move the account in use.
        defaults.set("mock", forKey: SourceSelection.key)
        XCTAssertEqual(ProfileStore(defaults: defaults, credentials: credentials, appleMusicAuthorized: false).activeId,
                       ProfileStore.legacySubsonicId)
    }

    func test_should_keepAppleMusicInUse_when_itWasTheActiveSource() {
        defaults.set("appleMusic", forKey: SourceSelection.key)
        let store = ProfileStore(defaults: defaults, credentials: legacyKeychain(), appleMusicAuthorized: true)
        XCTAssertEqual(store.profiles.map(\.id), [ProfileStore.legacySubsonicId, ProfileStore.appleMusicId])
        XCTAssertEqual(store.activeId, ProfileStore.appleMusicId)
    }

    func test_should_useTheServer_when_noSourceWasSaved() {
        let store = ProfileStore(defaults: defaults, credentials: legacyKeychain(), appleMusicAuthorized: false)
        XCTAssertEqual(store.activeId, ProfileStore.legacySubsonicId)
    }

    func test_should_startEmpty_when_nothingWasSignedIn() {
        defaults.set("mock", forKey: SourceSelection.key)
        let store = ProfileStore(defaults: defaults, credentials: FakeCredentials(), appleMusicAuthorized: false)
        XCTAssertTrue(store.profiles.isEmpty)
        XCTAssertNil(store.activeId)
    }

    func test_should_notLoseTheServer_when_theKeychainIsLockedAtMigration() {
        let credentials = legacyKeychain()
        credentials.unavailable[ProfileStore.legacySubsonicId] = errSecInteractionNotAllowed
        let locked = ProfileStore(defaults: defaults, credentials: credentials, appleMusicAuthorized: false)
        XCTAssertTrue(locked.profiles.isEmpty)
        XCTAssertNil(defaults.data(forKey: ProfileStore.profilesKey), "nothing written without it")

        // Unlocked: the next launch migrates it after all.
        credentials.unavailable = [:]
        let later = ProfileStore(defaults: defaults, credentials: credentials, appleMusicAuthorized: false)
        XCTAssertEqual(later.profiles.map(\.id), [ProfileStore.legacySubsonicId])

        // Or, the same run, before anything else is written.
        let d2 = UserDefaults(suiteName: suite + ".2")!
        defer { d2.removePersistentDomain(forName: suite + ".2") }
        credentials.unavailable[ProfileStore.legacySubsonicId] = errSecInteractionNotAllowed
        let same = ProfileStore(defaults: d2, credentials: credentials, appleMusicAuthorized: false)
        credentials.unavailable = [:]
        _ = same.saveSubsonic(other)
        XCTAssertEqual(same.profiles.first?.id, ProfileStore.legacySubsonicId)
        XCTAssertEqual(same.profiles.count, 2)
    }

    // MARK: Adding and removing

    func test_should_addAndDedupe_subsonicAccounts() throws {
        let credentials = legacyKeychain()
        let store = ProfileStore(defaults: defaults, credentials: credentials, appleMusicAuthorized: false)
        let added = try store.saveSubsonic(other).get()
        XCTAssertNotEqual(added.id, ProfileStore.legacySubsonicId)
        XCTAssertEqual(credentials.items[added.id], other)
        XCTAssertEqual(store.activeId, ProfileStore.legacySubsonicId, "adding alone doesn't switch")

        // The same server + user again: that account, with the new password.
        var again = other
        again.password = "new"
        let same = try store.saveSubsonic(again).get()
        XCTAssertEqual(same.id, added.id)
        XCTAssertEqual(store.profiles.count, 2)
        XCTAssertEqual(store.signIn(added.id)?.password, "new")

        // Persisted without passwords.
        let raw = String(data: try XCTUnwrap(defaults.data(forKey: ProfileStore.profilesKey)), encoding: .utf8) ?? ""
        XCTAssertFalse(raw.contains("pw"))
        XCTAssertFalse(raw.contains("\"new\""))
        XCTAssertEqual(ProfileStore(defaults: defaults, credentials: credentials, appleMusicAuthorized: false)
            .profiles.map(\.id), [ProfileStore.legacySubsonicId, added.id])
    }

    func test_should_refuse_when_theListIsFull() {
        let store = ProfileStore(defaults: defaults, credentials: FakeCredentials(), appleMusicAuthorized: false)
        for i in 0..<ProfileStore.maxProfiles {
            _ = store.saveSubsonic(.init(baseUrl: "https://s\(i)", username: "u", password: "p"))
        }
        XCTAssertEqual(store.saveSubsonic(.init(baseUrl: "https://more", username: "u", password: "p")), .failure(.full))
        XCTAssertNil(store.addAppleMusic())
    }

    func test_should_handOverToTheFirstLeft_when_theActiveAccountIsRemoved() throws {
        let credentials = legacyKeychain()
        let store = ProfileStore(defaults: defaults, credentials: credentials, appleMusicAuthorized: true)
        let second = try store.saveSubsonic(other).get()
        store.setActive(second.id)

        XCTAssertFalse(store.remove(ProfileStore.legacySubsonicId), "not the one in use")
        XCTAssertEqual(store.activeId, second.id)
        XCTAssertNil(credentials.items[ProfileStore.legacySubsonicId], "its sign-in goes with it")

        XCTAssertTrue(store.remove(second.id))
        XCTAssertEqual(store.activeId, ProfileStore.appleMusicId)
        XCTAssertTrue(store.remove(ProfileStore.appleMusicId))
        XCTAssertNil(store.activeId)
        XCTAssertTrue(store.profiles.isEmpty)
        XCTAssertNil(ProfileStore(defaults: defaults, credentials: credentials, appleMusicAuthorized: true).activeId)
    }

    func test_should_giveEachAccountItsOwnAvatarShape() {
        let ids = (0..<5).map { (id: "p\($0)", createdAt: Int64($0)) }
        let shapes = AccountIdentity.assignShapes(ids)
        XCTAssertEqual(Set(shapes.values).count, 5)
        XCTAssertEqual(AccountIdentity.assignShapes(ids), shapes, "stable")
        XCTAssertEqual(AccountIdentity.javaHash("abc"), 96354, "Java's String.hashCode")
        XCTAssertEqual(AccountIdentity.monogram("demo"), "D")
        XCTAssertEqual(AccountIdentity.monogram("Apple Music", service: "Apple Music"), "A")
    }

    // MARK: Switching

    private func manager(_ store: ProfileStore, session: ServerSession,
                         answers: @escaping (ServerSession.Account) throws -> Void = { _ in }) -> (ProfileManager, SourceSelection) {
        let sources = SourceSelection(subsonicSignedIn: false, defaults: defaults)
        let m = ProfileManager(store: store, session: session, sources: sources, appleAuthorized: { true })
        m.probe = { account in
            try answers(account)
            return ServerSession.Probe(client: SubsonicClient(baseUrl: account.baseUrl, username: account.username,
                                                              password: account.password),
                                       server: .init(type: "navidrome", version: "0.64"), openSubsonic: false)
        }
        return (m, sources)
    }

    func test_should_switchAccounts_and_endTheOldPlayback() async throws {
        let store = ProfileStore(defaults: defaults, credentials: legacyKeychain(), appleMusicAuthorized: false)
        let second = try store.saveSubsonic(other).get()
        let session = ServerSession()
        let (m, sources) = manager(store, session: session)
        defer { session.signOut() }
        var ended = 0
        m.endPlayback = { ended += 1 }
        // Launch: the account in use at once, no ping awaited.
        XCTAssertEqual(session.account, legacy)
        XCTAssertEqual(sources.active, .subsonic)

        let oldClient = session.client
        await m.switchTo(second.id)
        XCTAssertEqual(m.activeId, second.id)
        XCTAssertEqual(session.account, other)
        XCTAssertFalse(session.client === oldClient)
        XCTAssertEqual(session.state, .ready(.init(type: "navidrome", version: "0.64")))
        XCTAssertEqual(AccountScope.shared.subsonic, AccountScope.subsonicScope(other))
        XCTAssertEqual(ended, 1)
        XCTAssertNil(m.switchingTo)

        // To Apple Music: no Subsonic server in use, and the source follows.
        _ = m.addAppleMusic(activate: true)
        XCTAssertEqual(m.activeId, ProfileStore.appleMusicId)
        XCTAssertEqual(sources.active, .appleMusic)
        XCTAssertNil(session.client)
        XCTAssertEqual(ended, 2)
        XCTAssertEqual(defaults.string(forKey: ProfileStore.activeKey), ProfileStore.appleMusicId)
    }

    func test_should_keepTheAccountInUse_when_theTargetDoesNotAnswer() async throws {
        let store = ProfileStore(defaults: defaults, credentials: legacyKeychain(), appleMusicAuthorized: false)
        let second = try store.saveSubsonic(other).get()
        let session = ServerSession()
        let (m, _) = manager(store, session: session) { _ in throw URLError(.cannotConnectToHost) }
        defer { session.signOut() }
        var ended = 0
        m.endPlayback = { ended += 1 }

        await m.switchTo(second.id)
        XCTAssertEqual(m.activeId, ProfileStore.legacySubsonicId)
        XCTAssertEqual(session.account, legacy)
        XCTAssertEqual(m.failure?.profileId, second.id)
        XCTAssertEqual(ended, 0, "nothing stops for a switch that didn't happen")
    }

    func test_should_connectANewAccount_and_useIt() async {
        let store = ProfileStore(defaults: defaults, credentials: legacyKeychain(), appleMusicAuthorized: false)
        let session = ServerSession()
        let (m, _) = manager(store, session: session)
        defer { session.signOut() }
        let failure = await m.connectSubsonic(baseUrl: "127.0.0.1:4996/", username: "demo", password: "demo", activate: true)
        XCTAssertNil(failure)
        XCTAssertEqual(m.profiles.count, 2)
        XCTAssertEqual(m.active?.baseUrl, "https://127.0.0.1:4996")
        XCTAssertEqual(session.account?.username, "demo")
    }

    func test_should_useTheNextAccount_when_theActiveOneIsRemoved() throws {
        let store = ProfileStore(defaults: defaults, credentials: legacyKeychain(), appleMusicAuthorized: false)
        let second = try store.saveSubsonic(other).get()
        let session = ServerSession()
        let (m, sources) = manager(store, session: session)
        defer { session.signOut() }
        var ended = 0
        m.endPlayback = { ended += 1 }

        m.remove(ProfileStore.legacySubsonicId)
        XCTAssertEqual(m.activeId, second.id)
        XCTAssertEqual(session.account, other, "restored at once, without waiting for its server")
        XCTAssertEqual(ended, 1)

        m.remove(second.id)
        XCTAssertNil(m.activeId)
        XCTAssertNil(session.client)
        XCTAssertEqual(sources.active, .mock)
        XCTAssertEqual(ended, 2)
    }

    // MARK: Cloud sync reads each account

    func test_should_readAndWriteAnotherAccountsRows_inside_reading() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let scopes = AccountScope()
        scopes.adoptsLegacyRows = false
        scopes.subsonic = "https://a|me"
        let ratings = LocalRatings(fileURL: dir.appendingPathComponent("ratings.json"), scopes: scopes)
        ratings.set(8, provider: "subsonic", songId: "t1")
        scopes.reading(asSubsonic: "https://b|me") {
            XCTAssertNil(ratings.rating(provider: "subsonic", songId: "t1"))
            ratings.syncPut(4, provider: "subsonic", songId: "t1", updatedAt: 5)
        }
        XCTAssertEqual(scopes.subsonic, "https://a|me", "no switch")
        XCTAssertEqual(ratings.rating(provider: "subsonic", songId: "t1"), 8)
        scopes.subsonic = "https://b|me"
        XCTAssertEqual(ratings.rating(provider: "subsonic", songId: "t1"), 4)
    }
}
