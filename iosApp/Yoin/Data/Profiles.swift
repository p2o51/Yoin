import Foundation
import Observation
import SwiftUI
import YoinShared

/// One configured account (Android `data/local/Profile`): a Subsonic server +
/// user, this device's Apple Music, or a Spotify account. Kept in UserDefaults without its
/// password; a Subsonic account's sign-in is its own Keychain item
/// (`ProfileCredentialStore`), a Spotify account's tokens too (`SpotifyTokenStore`).
struct Profile: Codable, Equatable, Identifiable, Sendable {
    /// The services iOS can add.
    enum Service: String, Codable, Sendable, CaseIterable {
        case subsonic, appleMusic, spotify

        var source: ActiveSource {
            switch self {
            case .subsonic: .subsonic
            case .appleMusic: .appleMusic
            case .spotify: .spotify
            }
        }

        var name: String {
            switch self {
            case .subsonic: "Subsonic"
            case .appleMusic: "Apple Music"
            case .spotify: "Spotify"
            }
        }
    }

    var id: String
    var service: Service
    /// Subsonic only: where and who (not secret; the scope local rows are kept under).
    var baseUrl: String?
    var username: String?
    /// Epoch ms; the avatar shapes go oldest first.
    var createdAt: Int64
    /// The scope its rows were first kept under, when an edit changed only how the address is written
    /// (case, scheme, a trailing slash: the same account fingerprint). Nil: "baseUrl|username".
    var scopeKey: String? = nil
    /// Spotify only: whose account it is (its rows' scope), and the name it shows.
    var spotifyUserId: String? = nil
    var spotifyName: String? = nil
    /// Epoch ms of its last sign-in: a new sign-in (after a revoke) rebuilds the account's source.
    var spotifySignedInAt: Int64? = nil

    /// `AccountScope`'s key for this account's rows ("baseUrl|username" as first saved); nil for Apple Music.
    var subsonicScope: String? {
        guard service == .subsonic, let baseUrl, let username else { return nil }
        return scopeKey ?? "\(baseUrl)|\(username)"
    }

    /// The account's cloud identity (shared `AccountFingerprints`, as on Android): one server + user
    /// however its address is written. Nil when it can't be parsed.
    static func identity(baseUrl: String, username: String) -> String? {
        AccountFingerprints.shared.subsonic(serverUrl: baseUrl, username: username)
    }

    /// The card's name: the Subsonic username, or the service.
    var title: String {
        if service == .subsonic, let username, !username.isEmpty { return username }
        if service == .spotify, let spotifyName, !spotifyName.isEmpty { return spotifyName }
        return service.name
    }

    /// The Subsonic server's host (and port, when it names one), under the name.
    var host: String? {
        guard service == .subsonic, let baseUrl else { return nil }
        guard let url = URL(string: baseUrl), let host = url.host() else { return baseUrl }
        return url.port.map { "\(host):\($0)" } ?? host
    }
}

/// The account list on disk and each account's sign-in, without any UI or
/// network: what `ProfileManager` decides, written down. Also the one-time
/// migration from the single-server build (one Keychain item + the
/// `activeSource` setting).
@MainActor
final class ProfileStore {
    /// The account migrated from the single-server build. Also its cloud sync profile id
    /// (`SyncDomainBridge.subsonicProfileId`), so what it synced stays matched to it.
    nonisolated static let legacySubsonicId = "ios-subsonic"
    /// Apple Music has one account per device (`SyncDomainBridge.appleMusicProfileId`).
    nonisolated static let appleMusicId = "ios-applemusic"
    nonisolated static let profilesKey = "profiles.v1"
    nonisolated static let activeKey = "activeProfileId"
    /// Set while the legacy Keychain item is still unread (locked) after the list was written anyway.
    nonisolated static let legacyPendingKey = "profiles.legacyPending"
    /// Android ProfileManager.MAX_PROFILES.
    nonisolated static let maxProfiles = 5

    private(set) var profiles: [Profile] = []
    private(set) var activeId: String?

    private let defaults: UserDefaults
    private let credentials: ProfileCredentialStore
    /// Sign-ins read or saved this run. An unsigned simulator build has no Keychain: a sign-in it
    /// couldn't store lasts the run, as before.
    private var signIns: [String: ServerSession.Account] = [:]
    private var spotifyTokens: [String: SpotifyTokens] = [:]
    /// The legacy Keychain item couldn't be read at the migration (locked): looked for again before every
    /// write and on later launches, until the Keychain answers found or missing (Codex #7).
    private var legacyPending = false

    init(defaults: UserDefaults = .standard, credentials: ProfileCredentialStore = KeychainCredentials(),
         appleMusicAuthorized: Bool, now: Int64 = ProfileStore.nowMs) {
        self.defaults = defaults
        self.credentials = credentials
        if let data = defaults.data(forKey: Self.profilesKey),
           let saved = try? JSONDecoder().decode([Profile].self, from: data) {
            profiles = saved
            activeId = defaults.string(forKey: Self.activeKey).flatMap { id in saved.contains { $0.id == id } ? id : nil }
            legacyPending = defaults.bool(forKey: Self.legacyPendingKey)
            if legacyPending, lookForLegacy() { persist() }
        } else {
            migrate(appleMusicAuthorized: appleMusicAuthorized, now: now)
        }
        for p in profiles where p.service == .subsonic {
            if case .found(let a) = credentials.load(p.id) { signIns[p.id] = a }
        }
    }

    static var nowMs: Int64 { Int64((Date().timeIntervalSince1970 * 1000).rounded()) }

    var active: Profile? { activeId.flatMap(profile) }

    func profile(_ id: String) -> Profile? { profiles.first { $0.id == id } }

    /// The account's sign-in (Subsonic), nil when there is none to read (a restore without it, an
    /// unsigned build relaunched).
    func signIn(_ id: String) -> ServerSession.Account? { signIns[id] }

    var canAdd: Bool { profiles.count < Self.maxProfiles }

    // MARK: Migration (the single-server build → the account list)

    /// Once: the old Keychain item becomes the first Subsonic account (its Keychain item and scope as
    /// they were, so its history, notes, memories, downloads and Home layout stay with it); Apple Music
    /// becomes an account when it was in use or is authorized; the old `activeSource` picks the one in
    /// use (unset: the Subsonic server when there is one, as `SourceSelection` did).
    private func migrate(appleMusicAuthorized: Bool, now: Int64) {
        var list: [Profile] = []
        switch credentials.load(Self.legacySubsonicId) {
        case .found(let a):
            list.append(Self.legacyProfile(a, now: now))
        case .missing:
            break
        case .unavailable(let status):
            // No entitlement (unsigned simulator) means nothing could have been stored; anything else may
            // be a locked Keychain, so look again before writing.
            legacyPending = status != errSecMissingEntitlement
        }
        let saved = defaults.string(forKey: SourceSelection.key).flatMap(ActiveSource.init(rawValue:))
        if saved == .appleMusic || appleMusicAuthorized {
            list.append(Profile(id: Self.appleMusicId, service: .appleMusic, createdAt: now + 1))
        }
        profiles = list
        let hasSubsonic = list.contains { $0.id == Self.legacySubsonicId }
        switch saved {
        case .subsonic: activeId = hasSubsonic ? Self.legacySubsonicId : nil
        case .appleMusic: activeId = Self.appleMusicId
        // The single-server build had no Spotify.
        case .mock, .spotify: activeId = nil
        case nil: activeId = hasSubsonic ? Self.legacySubsonicId : nil
        }
        if !legacyPending { persist() }
    }

    private static func legacyProfile(_ a: ServerSession.Account, now: Int64) -> Profile {
        Profile(id: legacySubsonicId, service: .subsonic, baseUrl: a.baseUrl, username: a.username, createdAt: now)
    }

    // MARK: Changes

    enum SaveFailure: Error, Equatable {
        case full
        case keychain(OSStatus)
    }

    /// Saves a Subsonic sign-in: into [id]'s account when editing, else into the account already
    /// added for that server + user, else a new account. The Keychain first, so a failure keeps the list
    /// (and the old sign-in) as it was. An edit that keeps the account's identity (only the address's
    /// spelling changed) keeps its scope, so its rows stay with it (Codex #1).
    func saveSubsonic(_ account: ServerSession.Account, editing id: String? = nil,
                      now: Int64 = ProfileStore.nowMs) -> Result<Profile, SaveFailure> {
        let scope = AccountScope.subsonicScope(account)
        let identity = Profile.identity(baseUrl: account.baseUrl, username: account.username)
        let existing = id.flatMap(profile) ?? profiles.first { p in
            guard p.service == .subsonic else { return false }
            if p.subsonicScope == scope { return true }
            guard let identity, let url = p.baseUrl, let user = p.username else { return false }
            return Profile.identity(baseUrl: url, username: user) == identity
        }
        let sameIdentity = existing.map { e in
            guard e.service == .subsonic, let url = e.baseUrl, let user = e.username else { return false }
            return identity != nil && Profile.identity(baseUrl: url, username: user) == identity
        } ?? false
        let keptScope = sameIdentity ? existing?.subsonicScope.flatMap { $0 == scope ? nil : $0 } : nil
        if existing == nil, !canAdd { return .failure(.full) }
        let profileId = existing?.id ?? "subsonic-\(UUID().uuidString.lowercased())"
        let status = credentials.save(account, for: profileId)
        // An unsigned simulator build has no Keychain: the sign-in lasts this run only, as before.
        guard status == errSecSuccess || status == errSecMissingEntitlement else { return .failure(.keychain(status)) }
        signIns[profileId] = account
        let updated = Profile(id: profileId, service: .subsonic, baseUrl: account.baseUrl,
                              username: account.username, createdAt: existing?.createdAt ?? now, scopeKey: keptScope)
        if let i = profiles.firstIndex(where: { $0.id == profileId }) { profiles[i] = updated } else { profiles.append(updated) }
        persist()
        return .success(updated)
    }

    /// Saves a Spotify sign-in: into the account already added for that Spotify user, else a new
    /// one. The Keychain first, as for Subsonic.
    func saveSpotify(_ tokens: SpotifyTokens, now: Int64 = ProfileStore.nowMs) -> Result<Profile, SaveFailure> {
        let existing = profiles.first { $0.service == .spotify && $0.spotifyUserId == tokens.userId }
        if existing == nil, !canAdd { return .failure(.full) }
        let profileId = existing?.id ?? "spotify-\(tokens.userId)"
        let status = SpotifyTokenStore.save(tokens, for: profileId)
        guard status == errSecSuccess || status == errSecMissingEntitlement else { return .failure(.keychain(status)) }
        spotifyTokens[profileId] = tokens
        let updated = Profile(id: profileId, service: .spotify, createdAt: existing?.createdAt ?? now,
                              spotifyUserId: tokens.userId, spotifyName: tokens.displayName, spotifySignedInAt: now)
        if let i = profiles.firstIndex(where: { $0.id == profileId }) { profiles[i] = updated } else { profiles.append(updated) }
        persist()
        return .success(updated)
    }

    /// The tokens a refresh wrote to the Keychain, here too (the in-memory copy a switch reuses).
    func updateSpotify(_ tokens: SpotifyTokens, for id: String) {
        guard profile(id) != nil else { return }
        spotifyTokens[id] = tokens
    }

    /// A Spotify account's tokens (read once a run; an unsigned build keeps them for the run).
    func spotify(_ id: String) -> SpotifyTokens? {
        if let t = spotifyTokens[id] { return t }
        let t = SpotifyTokenStore.load(id)
        spotifyTokens[id] = t
        return t
    }

    /// The Apple Music account (one per device), added if it isn't yet.
    func addAppleMusic(now: Int64 = ProfileStore.nowMs) -> Profile? {
        if let p = profile(Self.appleMusicId) { return p }
        guard canAdd else { return nil }
        let p = Profile(id: Self.appleMusicId, service: .appleMusic, createdAt: now)
        profiles.append(p)
        persist()
        return p
    }

    func setActive(_ id: String?) {
        activeId = id.flatMap { profile($0) != nil ? $0 : nil }
        persist()
    }

    /// Removes [id] and its sign-in; its local rows (history, notes, ratings, memories) stay under its
    /// scope, as on Android. Removing the account in use makes the first one left the account in use
    /// (none left: no account). Returns whether the account in use changed.
    @discardableResult
    func remove(_ id: String) -> Bool {
        guard let p = profile(id) else { return false }
        profiles.removeAll { $0.id == id }
        signIns[id] = nil
        if p.service == .subsonic { credentials.delete(id) }
        if p.service == .spotify {
            SpotifyTokenStore.delete(id)
            spotifyTokens[id] = nil
        }
        let wasActive = activeId == id
        if wasActive { activeId = profiles.first?.id }
        persist()
        return wasActive
    }

    /// Reads the legacy item again; true once the Keychain answered (found: the account joins the list).
    /// Locked again: still pending.
    private func lookForLegacy() -> Bool {
        switch credentials.load(Self.legacySubsonicId) {
        case .found(let a):
            if profile(Self.legacySubsonicId) == nil {
                profiles.insert(Self.legacyProfile(a, now: Self.nowMs), at: 0)
            }
            signIns[Self.legacySubsonicId] = a
        case .missing:
            break
        case .unavailable(let status):
            guard status == errSecMissingEntitlement else { return false }
        }
        legacyPending = false
        return true
    }

    private func persist() {
        if legacyPending { _ = lookForLegacy() }
        if let data = try? JSONEncoder().encode(profiles) { defaults.set(data, forKey: Self.profilesKey) }
        if let activeId { defaults.set(activeId, forKey: Self.activeKey) } else { defaults.removeObject(forKey: Self.activeKey) }
        if legacyPending { defaults.set(true, forKey: Self.legacyPendingKey) } else { defaults.removeObject(forKey: Self.legacyPendingKey) }
    }
}

/// Owns the account list and the account in use (Android
/// `data/profile/ProfileManager`). A switch asks the target server once and
/// commits as soon as it answers (Android #124: the warm-up never holds the
/// switch); the old account's playback and queue end with it, the Subsonic
/// client is swapped, and `LibraryStore` (which watches the client and the
/// source) starts from scratch, so Home and Library read the new account.
/// `AccountScope` follows the client (`ServerSession.account`), and with it
/// each account's downloads, notes and ratings.
@MainActor
@Observable
final class ProfileManager {
    private(set) var profiles: [Profile] = []
    private(set) var activeId: String?
    /// The account a switch is asking right now.
    private(set) var switchingTo: String?
    /// The last switch or connect that failed, for its card.
    private(set) var failure: Failure?

    struct Failure: Equatable {
        var profileId: String?
        var message: String
    }

    @ObservationIgnored let store: ProfileStore
    @ObservationIgnored private let session: ServerSession
    @ObservationIgnored private let sources: SourceSelection
    @ObservationIgnored private let appleAuthorized: () -> Bool
    /// The old account's playback, queue and callbacks end before its client goes (AppModel stops the engine).
    @ObservationIgnored var endPlayback: () -> Void = {}
    /// Asks a server once (tests answer without a network).
    @ObservationIgnored var probe: (ServerSession.Account) async throws -> ServerSession.Probe = { try await ServerSession.probe($0) }
    /// Bumps on every switch, connect and removal: an answer for an older one never commits.
    @ObservationIgnored private var generation = 0
    /// A launch argument's source (`-activeSource mock` for screenshots) wins over the account in use.
    @ObservationIgnored private let forcedSource: ActiveSource?

    init(store: ProfileStore, session: ServerSession, sources: SourceSelection,
         appleAuthorized: @escaping () -> Bool, forcedSource: ActiveSource? = nil) {
        self.store = store
        self.session = session
        self.sources = sources
        self.appleAuthorized = appleAuthorized
        self.forcedSource = forcedSource
        settle(store.active)
        refresh()
    }

    var active: Profile? { activeId.flatMap(profile) }

    func profile(_ id: String) -> Profile? { profiles.first { $0.id == id } }

    var canAdd: Bool { store.canAdd }

    /// The account can't be used until its owner signs in again (no readable sign-in).
    func needsSignIn(_ p: Profile) -> Bool {
        switch p.service {
        case .subsonic: store.signIn(p.id) == nil
        case .appleMusic: false
        // No tokens, signed in before a required scope, or revoked (Android ProfileState.NeedsReconnect).
        case .spotify: spotifyRevoked.contains(p.id) || (store.spotify(p.id).map(\.missingRequiredScopes) ?? true)
        }
    }

    func signIn(_ id: String) -> ServerSession.Account? { store.signIn(id) }

    /// Oldest first, each account its own avatar shape (Android assignAvatarShapes).
    var avatarShapes: [String: Int] {
        AccountIdentity.assignShapes(profiles.map { (id: $0.id, createdAt: $0.createdAt) })
    }

    func clearFailure() { failure = nil }

    /// Spotify accounts whose sign-in was revoked this run (invalid_grant): they ask to sign in again.
    private(set) var spotifyRevoked: Set<String> = []

    func markSpotifyRevoked(_ id: String) { spotifyRevoked.insert(id) }

    // MARK: Switching

    /// Makes [id] the account in use. Subsonic: its server is asked once and the switch commits when
    /// it answers; a server that doesn't keeps the old account (its card says why).
    func switchTo(_ id: String) async {
        guard switchingTo == nil, id != activeId, let target = profile(id) else { return }
        generation += 1
        let gen = generation
        failure = nil
        switch target.service {
        case .subsonic:
            guard let account = store.signIn(id) else {
                failure = Failure(profileId: id, message: "Sign in again")
                return
            }
            withAnimation(Yoin.effects) { switchingTo = id }
            do {
                #if DEBUG
                // `-profileSwitchDelayMs 1500`: a slow server, for the switch-in-progress screenshot.
                let delay = UserDefaults.standard.integer(forKey: "profileSwitchDelayMs")
                if delay > 0 { try? await Task.sleep(for: .milliseconds(delay)) }
                #endif
                let answered = try await probe(account)
                guard gen == generation else {
                    answered.client.close()
                    if switchingTo == id { switchingTo = nil }
                    return
                }
                commit(target, probe: answered, account: account)
            } catch {
                guard gen == generation else {
                    if switchingTo == id { switchingTo = nil }
                    return
                }
                withAnimation(Yoin.effects) {
                    switchingTo = nil
                    failure = Failure(profileId: id, message: Self.short(error))
                }
            }
        case .appleMusic:
            guard appleAuthorized() else {
                failure = Failure(profileId: id, message: "Apple Music access is off for Yoin")
                return
            }
            commit(target, probe: nil, account: nil)
        case .spotify:
            guard !needsSignIn(target) else {
                failure = Failure(profileId: id, message: "Sign in again")
                return
            }
            commit(target, probe: nil, account: nil)
        }
    }

    /// The switch itself, in one animated step.
    private func commit(_ target: Profile, probe: ServerSession.Probe?, account: ServerSession.Account?) {
        withAnimation(Yoin.spatial) {
            if target.id != activeId || session.scope != target.subsonicScope {
                endPlayback()
            }
            if let probe, let account {
                session.install(probe, account: account, scope: target.subsonicScope)
            } else {
                session.signOut()
            }
            use(target.service.source)
            store.setActive(target.id)
            switchingTo = nil
            failure = nil
            refresh()
        }
    }

    // MARK: Adding and editing

    /// Connects a Subsonic account: the server is asked first, and only one that answers is saved
    /// (Android ServiceSetup). [editing] saves into that account; otherwise it is a new account, or the
    /// one already added for the same server + user. It becomes the account in use when [activate],
    /// when it already was, or when there is none. Returns the error to show, nil when it is saved.
    func connectSubsonic(baseUrl: String, username: String, password: String, editing: String? = nil,
                         activate: Bool) async -> String? {
        let account = ServerSession.Account(baseUrl: ServerSession.normalized(baseUrl), username: username,
                                            password: password)
        generation += 1
        let gen = generation
        failure = nil
        let answered: ServerSession.Probe
        do {
            answered = try await probe(account)
        } catch {
            return gen == generation ? Self.short(error) : "Cancelled"
        }
        guard gen == generation else {
            answered.client.close()
            return "Cancelled"
        }
        switch store.saveSubsonic(account, editing: editing) {
        case .failure(.full):
            answered.client.close()
            return "Up to \(ProfileStore.maxProfiles) accounts"
        case .failure(.keychain(let status)):
            answered.client.close()
            return "Couldn't save the sign-in (Keychain error \(status))"
        case .success(let saved):
            if activate || saved.id == activeId || activeId == nil {
                commit(saved, probe: answered, account: account)
            } else {
                answered.client.close()
                withAnimation(Yoin.spatial) { refresh() }
            }
            return nil
        }
    }

    /// Signs in to Spotify (its consent page) and saves the account; it becomes the account in use
    /// with [activate] or when there is none. Returns the error to show, nil when it is saved (or
    /// the sign-in was cancelled: nothing to say).
    func connectSpotify(activate: Bool) async -> String? {
        generation += 1
        let gen = generation
        failure = nil
        let tokens: SpotifyTokens
        do {
            tokens = try await SpotifyAuth.signIn()
        } catch SpotifyAuthError.cancelled {
            return nil
        } catch {
            return error.localizedDescription
        }
        guard gen == generation else { return "Cancelled" }
        switch store.saveSpotify(tokens) {
        case .failure(.full):
            return "Up to \(ProfileStore.maxProfiles) accounts"
        case .failure(.keychain(let status)):
            return "Couldn't save the sign-in (Keychain error \(status))"
        case .success(let saved):
            spotifyRevoked.remove(saved.id)
            if activate || saved.id == activeId || activeId == nil {
                commit(saved, probe: nil, account: nil)
            } else {
                withAnimation(Yoin.spatial) { refresh() }
            }
            return nil
        }
    }

    /// Adds this device's Apple Music (authorized already) and, with [activate] or no account in use,
    /// uses it. False when the list is full.
    @discardableResult
    func addAppleMusic(activate: Bool) -> Bool {
        guard let p = store.addAppleMusic() else { return false }
        if (activate || activeId == nil), p.id != activeId, appleAuthorized() {
            commit(p, probe: nil, account: nil)
        } else {
            withAnimation(Yoin.spatial) { refresh() }
        }
        return true
    }

    /// Removes the account (Settings asks first). Removing the one in use ends its playback and uses
    /// the first account left, without waiting for its server (as a launch does); none left, the mock.
    func remove(_ id: String) {
        generation += 1
        withAnimation(Yoin.spatial) {
            if switchingTo == id { switchingTo = nil }
            if failure?.profileId == id { failure = nil }
            if store.remove(id) {
                endPlayback()
                settle(store.active)
            }
            refresh()
        }
    }

    #if DEBUG
    /// `-subsonicSignOut YES`: every Subsonic account goes (the single-server build's sign-out).
    func removeAllSubsonic() {
        for p in profiles where p.service == .subsonic { remove(p.id) }
    }

    /// `-spotifyFake <url>`: the stand-in Spotify account (SpotifyFake) is saved and put in use.
    func useFakeSpotify() {
        guard SpotifyFake.isOn, case .success(let saved) = store.saveSpotify(SpotifyFake.tokens) else { return }
        spotifyRevoked.remove(saved.id)
        commit(saved, probe: nil, account: nil)
    }
    #endif

    // MARK: Cloud sync

    /// Every account cloud sync can bind (Data/Sync/SyncDomainBridge).
    var syncAccounts: SyncDomainBridge.Accounts {
        let subsonic = profiles.compactMap { p -> SyncDomainBridge.Accounts.Subsonic? in
            guard p.service == .subsonic, let url = p.baseUrl, let user = p.username else { return nil }
            return .init(profileId: p.id, url: url, username: user, scope: p.subsonicScope ?? "\(url)|\(user)")
        }
        let apple = profiles.contains { $0.service == .appleMusic } && appleAuthorized()
        // Spotify accounts sync as their Spotify user (Codex N08); rows already carry "spotify|<user id>".
        let spotify = profiles.compactMap { p -> SyncDomainBridge.Accounts.Spotify? in
            guard p.service == .spotify else { return nil }
            let tokens = store.spotify(p.id)
            guard let user = p.spotifyUserId ?? tokens?.userId, !user.isEmpty else { return nil }
            return .init(profileId: p.id, userId: user, displayName: p.title, createdAt: p.createdAt,
                         credentialsReadable: tokens != nil)
        }
        return SyncDomainBridge.Accounts(subsonic: subsonic, appleMusic: apple, storefront: nil, spotify: spotify)
    }

    // MARK: Pieces

    /// The account in use, at rest (launch, or after a removal): its client at once, no waiting.
    private func settle(_ p: Profile?) {
        switch p?.service {
        case .subsonic:
            if let p, let account = store.signIn(p.id) { session.restore(account, scope: p.subsonicScope) } else { session.signOut() }
            use(.subsonic)
        case .appleMusic:
            session.signOut()
            use(.appleMusic)
        case .spotify:
            session.signOut()
            use(.spotify)
        case nil:
            session.signOut()
            use(.mock)
        }
    }

    private func use(_ source: ActiveSource) {
        sources.use(forcedSource ?? source)
    }

    private func refresh() {
        if profiles != store.profiles { profiles = store.profiles }
        if activeId != store.activeId { activeId = store.activeId }
    }

    /// A transport error arrives as the whole NSError dump: one line is enough on a card.
    static func short(_ error: Error) -> String {
        let message = ServerSession.message(for: error)
        return message.contains("Domain=") || message.count > 90 ? "Couldn't reach the server" : message
    }
}
