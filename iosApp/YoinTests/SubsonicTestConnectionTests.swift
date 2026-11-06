import XCTest
import YoinShared
@testable import Yoin

/// Test connection saves nothing (D-F1).
@MainActor
final class SubsonicTestConnectionTests: XCTestCase {
    private final class Keychain: ProfileCredentialStore {
        var items: [String: ServerSession.Account] = [:]
        var saves = 0
        func load(_ profileId: String) -> CredentialRead { items[profileId].map(CredentialRead.found) ?? .missing }
        func save(_ account: ServerSession.Account, for profileId: String) -> OSStatus {
            saves += 1
            items[profileId] = account
            return errSecSuccess
        }
        func delete(_ profileId: String) { items[profileId] = nil }
    }

    private var suite: String!
    private var defaults: UserDefaults!

    override func setUp() async throws {
        suite = "SubsonicTestConnectionTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suite)
    }

    override func tearDown() async throws { defaults.removePersistentDomain(forName: suite) }

    private func make(_ keychain: Keychain, answers: @escaping (ServerSession.Account) throws -> Void)
        -> (ProfileManager, ServerSession) {
        let store = ProfileStore(defaults: defaults, credentials: keychain, appleMusicAuthorized: false)
        let session = ServerSession()
        let m = ProfileManager(store: store, session: session,
                               sources: SourceSelection(subsonicSignedIn: false, defaults: defaults),
                               appleAuthorized: { false })
        m.probe = { account in
            try answers(account)
            return ServerSession.Probe(client: SubsonicClient(baseUrl: account.baseUrl, username: account.username,
                                                              password: account.password),
                                       server: .init(type: "navidrome", version: "0.64"), openSubsonic: false)
        }
        return (m, session)
    }

    private struct Down: Error {}

    func test_should_reportSuccess_without_savingOrSwitching_when_theServerAnswers() async {
        let keychain = Keychain()
        var asked: [ServerSession.Account] = []
        let (m, session) = make(keychain) { asked.append($0) }
        let before = m.profiles
        let result = await m.testSubsonic(baseUrl: "demo.example.com", username: "u", password: "p")
        XCTAssertNil(result)
        XCTAssertEqual(asked.count, 1)
        XCTAssertEqual(asked.first?.username, "u")
        XCTAssertEqual(asked.first?.baseUrl, ServerSession.normalized("demo.example.com"))
        XCTAssertEqual(m.profiles, before, "no profile is added")
        XCTAssertEqual(keychain.saves, 0, "no sign-in is stored")
        XCTAssertNil(session.account, "the account in use is untouched")
        XCTAssertNil(m.activeId)
    }

    func test_should_reportAFailure_and_saveNothing_when_theServerDoesNotAnswer() async {
        let keychain = Keychain()
        let (m, _) = make(keychain) { _ in throw Down() }
        let result = await m.testSubsonic(baseUrl: "http://127.0.0.1:9", username: "u", password: "p")
        XCTAssertNotNil(result)
        XCTAssertTrue(m.profiles.isEmpty)
        XCTAssertEqual(keychain.saves, 0)
    }
}
