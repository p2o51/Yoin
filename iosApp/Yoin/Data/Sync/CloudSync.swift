import Foundation
import GoogleSignIn
import Observation
import UIKit
import YoinShared

extension CloudSyncKit: @retroactive @unchecked Sendable {}
extension CloudSyncCore: @retroactive @unchecked Sendable {}
extension CloudSyncState: @retroactive @unchecked Sendable {}
extension LocationSwitchResult: @retroactive @unchecked Sendable {}

/// Cloud sync on iOS / iPadOS / macOS (docs/cloud-sync.md; iosApp/docs/cloud-sync-ios.md).
///
/// The sync itself is the shared Kotlin (`CloudSyncKit` / `CloudSyncCore`, YoinShared): the same wire
/// files, merge and conflict rules across Google Drive (`appDataFolder`) and iCloud (CloudKit Private
/// Database, `YoinSyncZone` / `YoinSyncFile` + `CKAsset`). This side supplies what is per platform:
/// iCloud CloudKit transport, Google sign-in (GoogleSignIn-iOS, scope drive.appdata only), the stores
/// (`SyncDomainBridge`), the sync store's file, the backups, and when cycles run.
@MainActor
@Observable
final class CloudSync {
    static let shared = CloudSync()

    static let driveScope = "https://www.googleapis.com/auth/drive.appdata"

    private(set) var state = CloudSyncState(phase: CloudSyncPhaseOff.shared, account: nil, lastSyncAt: nil,
                                            pendingChanges: false, accounts: [], cloudOnlyAccounts: [], devices: [],
                                            lastSummary: nil, readOnly: false, lastBackup: nil,
                                            location: SyncLocations.shared.GOOGLE_DRIVE,
                                            pendingMigrationNotice: false)
    private(set) var working = false
    /// The last action's message for the page (a failed sign-in, a failed delete).
    var message: String?
    /// Accounts whose Start fresh was put off: the old account still has changes that aren't in the
    /// cloud (`CloudSyncFreshHold`). Their row says so under "Paused".
    private(set) var freshHeld: Set<String> = []

    /// DEBUG fake Drive: `-syncDebugBaseURL http://127.0.0.1:8765/ -syncDebugToken x`.
    let debug: (base: String, token: String)?

    /// The Google client ID from Info.plist (GIDClientID ← Config/GoogleSignIn.xcconfig), when it is real.
    let clientID: String?

    var isGoogleConfigured: Bool { clientID != nil || debug != nil }
    /// iCloud (CloudKit) is built in on Apple devices, so Cloud sync is always available on iOS / iPadOS / macOS.
    var isConfigured: Bool { true }
    var isICloud: Bool { state.location == SyncLocations.shared.ICLOUD }
    var activeLocationName: String { isICloud ? String(localized: "iCloud") : String(localized: "Google Drive") }

    @ObservationIgnored private var kit: CloudSyncKit?
    /// The stores the kit syncs, for the backup / transfer facade (`withBackup`).
    @ObservationIgnored private var domain: SyncDomainBridge?
    @ObservationIgnored private var accounts: (@MainActor () -> SyncDomainBridge.Accounts)?
    @ObservationIgnored private var debounce: Task<Void, Never>?
    @ObservationIgnored private var observers: [NSObjectProtocol] = []

    private init() {
        #if DEBUG
        let d = UserDefaults.standard
        if let base = d.string(forKey: "syncDebugBaseURL"), !base.isEmpty {
            debug = (base.hasSuffix("/") ? base : base + "/", d.string(forKey: "syncDebugToken") ?? "debug-token")
        } else {
            debug = nil
        }
        #else
        debug = nil
        #endif
        let raw = Bundle.main.object(forInfoDictionaryKey: "GIDClientID") as? String ?? ""
        clientID = (raw.isEmpty || raw.contains("NOT_CONFIGURED") || raw.contains("$(")) ? nil : raw
    }

    // MARK: Start

    /// Called once at launch with how to read the local accounts. Does no network unless sync is on.
    func start(accounts: @escaping @MainActor () -> SyncDomainBridge.Accounts) {
        guard kit == nil else { return }
        #if DEBUG
        if startDemo() { return }
        #endif
        self.accounts = accounts
        let domain = SyncDomainBridge(accounts: accounts)
        self.domain = domain
        let kit = CloudSyncKit(
            domain: domain,
            tokenSource: TokenSource(debugToken: debug?.token),
            persistence: StoreFile(),
            deviceName: UIDevice.current.model,
            versionCode: Int32(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "1") ?? 1,
            apiBase: debug.map { $0.base + "drive/v3/" },
            uploadBase: debug.map { $0.base + "upload/drive/v3/" },
            backupSink: BackupFolder(),
            icloudTransport: CloudKitSyncTransport()
        )
        self.kit = kit
        kit.core.onStateChange = { [weak self] state in
            MainActor.assumeIsolated { self?.take(state) }
        }
        NotesStore.onLocalChange = { CloudSync.shared.localChanged() }
        // The scrapbook's album notes (Android album_note): a sticky written, edited, deleted or put back.
        AlbumNotesStore.onLocalChange = { CloudSync.shared.localChanged() }
        LocalRatings.onLocalChange = { CloudSync.shared.localChanged() }
        // Gemini's About / Ask answers are synced too (Android observes song_about_entries); sync's own
        // writes don't call this, so applying never re-triggers a capture.
        SongAboutStore.onLocalChange = { CloudSync.shared.localChanged() }
        AlbumAboutStore.onLocalChange = { CloudSync.shared.localChanged() }
        // Gemini lyric translations and the answer language (Android lyrics_translation / setting).
        LyricsUserStore.onLocalChange = { CloudSync.shared.localChanged() }
        GeminiSettings.onLocalChange = { CloudSync.shared.localChanged() }
        HomeLayoutStore.onLocalChange = { CloudSync.shared.localChanged() }
        // Playlist cloud backups (Android playlist_backup): a backup, a follow-up, a restore, a delete.
        PlaylistBackupStore.onLocalChange = { CloudSync.shared.localChanged() }
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil,
                                            queue: .main) { _ in
            MainActor.assumeIsolated { CloudSync.shared.foreground() }
        })
        observers.append(center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil,
                                            queue: .main) { _ in
            MainActor.assumeIsolated { CloudSync.shared.background() }
        })
        Task {
            if debug == nil, clientID != nil {
                // Restores the saved Google user (no UI); tokens refresh before each request.
                _ = try? await GIDSignIn.sharedInstance.restorePreviousSignIn()
            }
            try? await kit.core.initialize()
            #if DEBUG
            // `-syncDebugAutoEnable YES` with the fake Drive: turn on at launch (QA without taps).
            if debug != nil, UserDefaults.standard.bool(forKey: "syncDebugAutoEnable"), !state.phase.enabled {
                try? await Task.sleep(for: .seconds(2))
                await turnOn()
                return
            }
            // `-syncDebugGoogleSignIn YES`: start the real Google sign-in at launch (checks the consent flow
            // without tapping; never completes on its own).
            if debug == nil, UserDefaults.standard.bool(forKey: "syncDebugGoogleSignIn") {
                try? await Task.sleep(for: .seconds(2))
                await turnOn()
                return
            }
            #endif
            foreground()
        }
    }

    /// `.onOpenURL`: Google's sign-in redirect.
    static func handle(url: URL) -> Bool { GIDSignIn.sharedInstance.handle(url) }

    // MARK: Actions

    /// Turn on (or reconnect) with iCloud (CloudKit Private Database): no sign-in prompt needed.
    func turnOnWithICloud() async {
        guard let kit else { return }
        message = nil
        working = true
        defer { working = false }
        do {
            if let failure = try await kit.core.enableICloud() { message = failure }
        } catch {
            message = error.localizedDescription
        }
    }

    /// Turn on (or reconnect) Google Drive: asks only for drive.appdata, then runs the first (read-only) pull.
    func turnOn() async {
        guard let kit, isGoogleConfigured else { return }
        message = nil
        working = true
        defer { working = false }
        guard let token = await acquireGoogleAccessToken() else { return }
        do {
            if let failure = try await kit.core.enable(accessToken: token) { message = failure }
        } catch {
            message = error.localizedDescription
        }
    }

    /// Four-step guided switch between Google Drive and iCloud (export & verify -> import -> verify counts -> old copy).
    func switchLocation(to targetLocation: String, clearOldCopy: Bool = false) async -> LocationSwitchResult? {
        guard let kit else { return nil }
        message = nil
        working = true
        defer { working = false }
        var token: String?
        if targetLocation == SyncLocations.shared.GOOGLE_DRIVE {
            guard isGoogleConfigured else {
                message = String(localized: "Google Drive isn't configured in this build of Yoin.")
                return nil
            }
            guard let acquired = await acquireGoogleAccessToken() else { return nil }
            token = acquired
        }
        do {
            let result = try await kit.core.switchLocation(
                targetLocation: targetLocation,
                targetAccessToken: token,
                clearOldCopy: clearOldCopy
            )
            if !result.succeeded, let err = result.error { message = err }
            return result
        } catch {
            message = error.localizedDescription
            return nil
        }
    }

    /// Optional Step 4 cleanup after switching: backs up and clears old Yoin files at [oldLocation],
    /// preserving migration, purge, and latest reset markers. Refreshes Google token explicitly when cleaning Drive.
    func clearOldLocationCopy(oldLocation: String) async -> String? {
        guard let kit else { return String(localized: "Cloud sync isn't ready.") }
        message = nil
        working = true
        defer { working = false }
        var token: String?
        if oldLocation == SyncLocations.shared.GOOGLE_DRIVE {
            if let debug {
                token = debug.token
            } else if let refreshed = await refreshGoogleTokenIfSignedIn() {
                token = refreshed
            } else {
                guard let acquired = await acquireGoogleAccessToken() else {
                    return message ?? String(localized: "Sign in to Google to remove the old Drive copy.")
                }
                token = acquired
            }
        }
        do {
            let failure = try await kit.core.clearOldLocationCopy(oldLocation: oldLocation, accessToken: token)
            if let failure { message = failure }
            return failure
        } catch {
            message = error.localizedDescription
            return error.localizedDescription
        }
    }

    private func refreshGoogleTokenIfSignedIn() async -> String? {
        guard let user = GIDSignIn.sharedInstance.currentUser else { return nil }
        return await withCheckedContinuation { continuation in
            user.refreshTokensIfNeeded { refreshed, _ in
                continuation.resume(returning: refreshed?.accessToken.tokenString)
            }
        }
    }

    /// True once the account's data is gone from the cloud.
    @discardableResult
    func removeAccountFromCloud(_ syncProfileId: String) async -> Bool {
        guard let kit else { return false }
        message = nil
        working = true
        defer { working = false }
        do {
            if let failure = try await kit.core.removeAccountFromCloud(syncProfileId: syncProfileId) {
                message = failure
                return false
            }
            return true
        } catch {
            message = error.localizedDescription
            return false
        }
    }

    func resumeAccountSync(_ localProfileId: String) async {
        await run { try await $0.resumeAccountSync(localProfileId: localProfileId) }
    }

    func dismissMigrationNotice() async {
        await run { try await $0.dismissMigrationNotice() }
    }

    func dismissPendingMigrationNotice() async {
        await run { try await $0.dismissPendingMigrationNotice() }
    }

    private func acquireGoogleAccessToken() async -> String? {
        if let debug { return debug.token }
        guard let clientID else { return nil }
        GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: clientID)
        guard let presenter = Self.presenter() else { return nil }
        do {
            let result = try await GIDSignIn.sharedInstance.signIn(withPresenting: presenter, hint: nil,
                                                                    additionalScopes: [Self.driveScope])
            var user = result.user
            if !(user.grantedScopes ?? []).contains(Self.driveScope) {
                user = try await user.addScopes([Self.driveScope], presenting: presenter).user
            }
            guard (user.grantedScopes ?? []).contains(Self.driveScope) else {
                message = String(localized: "Google didn't allow Drive access.")
                return nil
            }
            return user.accessToken.tokenString
        } catch {
            if (error as NSError).code != GIDSignInError.canceled.rawValue { message = error.localizedDescription }
            return nil
        }
    }

    func syncNow() async { await run { try await $0.syncNow() } }

    func turnOff() async { await run { try await $0.turnOff() } }

    /// All devices: Android's flow (back up first, reset marker, turn off here, delete Yoin's files).
    func deleteCloudData() async {
        guard let kit else { return }
        let wasICloud = isICloud
        working = true
        defer { working = false }
        do {
            message = try await kit.core.deleteCloudData()
        } catch {
            message = error.localizedDescription
        }
        if message == nil, !wasICloud, debug == nil { GIDSignIn.sharedInstance.signOut() }
    }

    /// Deletes another device's copy from the Drive (never this device's: the engine only drops files it doesn't own).
    func removeDevice(_ deviceId: String) async { await run { try await $0.removeDevice(deviceId: deviceId) } }
    func resolveReview(restore: Bool) async { await run { try await $0.resolveReview(restore: restore) } }
    func uploadAgain() async { await run { try await $0.uploadAgain() } }
    func startSyncing(_ profileId: String) async { await run { try await $0.startSyncingAccount(localProfileId: profileId) } }
    func link(_ profileId: String, to scope: String) async {
        await run { try await $0.linkAccount(localProfileId: profileId, syncProfileId: scope) }
    }
    func keepAsBefore(_ profileId: String) async { await run { try await $0.keepAccountAsBefore(localProfileId: profileId) } }

    /// Start fresh as the account [profileId] is signed in as now. The core uploads what the old account
    /// still has waiting first; when that doesn't go through it changes nothing (Android
    /// CloudSyncManager.startFreshAccount) and the account's row says what it waits for.
    func startFresh(_ profileId: String) async {
        guard let kit else { return }
        working = true
        defer { working = false }
        do {
            let notUploaded = try await kit.core.startFreshAccount(localProfileId: profileId).intValue
            freshHeld = CloudSyncFreshHold.after(freshHeld, profile: profileId, notUploaded: notUploaded, state: state)
        } catch {
            message = error.localizedDescription
        }
    }

    /// Every new state from the core.
    private func take(_ new: CloudSyncState) {
        state = new
        let held = CloudSyncFreshHold.pruned(freshHeld, state: new)
        if held != freshHeld { freshHeld = held }
    }

    // MARK: Backup, import, Memories transfer

    /// Where a backup says the data came from: the sync location while Cloud sync is on, else nil
    /// ("this-device"; `YoinBackup.exportBackup`'s syncLocation).
    var backupSyncLocation: String? { state.phase.enabled ? state.location : nil }

    /// [body] with the backup / import / transfer facade (`YoinBackup`, docs/cloud-sync.md「导出与导入」) over
    /// this device's stores and the sync replica, under the sync lock (Android `CloudSyncManager.withReplica`):
    /// no cycle runs meanwhile, and the replica is written once it is done. Sync never set up here (or the
    /// DEBUG demo): the stores alone, no replica.
    func withBackup<T>(_ body: (YoinBackup) async throws -> T) async throws -> T {
        let info = Bundle.main.infoDictionary
        let versionName = info?["CFBundleShortVersionString"] as? String ?? "0"
        let versionCode = Int32(info?["CFBundleVersion"] as? String ?? "1") ?? 1
        let links = TrackAlbumLinkStore.shared
        guard let kit, let domain else {
            let domain = SyncDomainBridge(accounts: { AppModel.shared.profiles.syncAccounts })
            let facade = YoinBackup(domain: domain, replica: nil, links: links, deviceId: Self.backupDeviceId(),
                                    deviceName: UIDevice.current.model, appVersionName: versionName,
                                    appVersionCode: versionCode)
            return try await body(facade)
        }
        let deviceId = try await kit.core.beginExclusive()
        defer { kit.core.endExclusive() }
        let facade = YoinBackup(domain: domain, replica: kit.store, links: links, deviceId: deviceId,
                                deviceName: UIDevice.current.model, appVersionName: versionName, appVersionCode: versionCode)
        let result = try await body(facade)
        kit.flush()
        return result
    }

    /// An import or transfer wrote something: publish it now when sync is on (Android onLocalDataImported).
    func localDataImported() {
        guard kit != nil, state.phase.enabled else { return }
        Task { await syncNow() }
    }

    /// A backup's device id where Cloud sync never ran (Android MemoriesPortability.exportDeviceId).
    private static func backupDeviceId() -> String {
        let key = "backup.deviceId"
        if let id = UserDefaults.standard.string(forKey: key), !id.isEmpty { return id }
        let id = String(UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased().prefix(16))
        UserDefaults.standard.set(id, forKey: key)
        return id
    }

    private func run(_ body: (CloudSyncCore) async throws -> Void) async {
        guard let kit else { return }
        working = true
        defer { working = false }
        do { try await body(kit.core) } catch { message = error.localizedDescription }
    }

    // MARK: Triggers

    private func foreground() {
        guard let kit else { return }
        Task { try? await kit.core.onForeground() }
    }

    private func background() {
        guard let kit else { return }
        Task { try? await kit.core.onBackground() }
    }

    /// Android's LOCAL_DEBOUNCE_MS: capture and upload 8 s after the last local edit.
    func localChanged() {
        guard let kit, state.phase.enabled else { return }
        debounce?.cancel()
        debounce = Task {
            try? await Task.sleep(for: .milliseconds(Int(CloudSyncCore.companion.LOCAL_DEBOUNCE_MS)))
            guard !Task.isCancelled else { return }
            try? await kit.core.localChanged()
        }
    }

    #if DEBUG
    /// `-syncDemo fresh-held` (with `-screen settings -syncOpenPage YES`): the page with sync on, Google out
    /// of reach and one paused account; 4 s after launch its Start fresh comes back put off, as when the
    /// old account's changes couldn't be uploaded. Nothing syncs and no button does anything.
    private func startDemo() -> Bool {
        guard UserDefaults.standard.string(forKey: "syncDemo") == "fresh-held" else { return false }
        guard !state.phase.enabled else { return true }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let profile = "demo"
        state = CloudSyncState(
            phase: CloudSyncPhaseOffline.shared,
            account: GoogleAccountUi(email: "listener@example.com", displayName: "Listener"),
            lastSyncAt: KotlinLong(value: now - 3 * 3_600_000), pendingChanges: true,
            accounts: [SyncAccountRow(localProfileId: profile, provider: "subsonic", displayName: "demo",
                                      status: SyncAccountStatusAccountChanged.shared, syncProfileId: nil)],
            cloudOnlyAccounts: [],
            devices: [SyncDeviceRow(deviceId: "this", name: UIDevice.current.model, isThisDevice: true,
                                    lastSyncAt: KotlinLong(value: now - 3 * 3_600_000))],
            lastSummary: nil, readOnly: false, lastBackup: nil,
            location: SyncLocations.shared.GOOGLE_DRIVE,
            pendingMigrationNotice: false)
        Task {
            try? await Task.sleep(for: .seconds(4))
            freshHeld = CloudSyncFreshHold.after(freshHeld, profile: profile, notUploaded: 2, state: state)
        }
        return true
    }
    #endif

    private static func presenter() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive } ?? UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }.first
        var top = scene?.keyWindow?.rootViewController ?? scene?.windows.first?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
}

// MARK: - Kotlin-facing pieces

/// Google access tokens for the signed-in user, refreshed before they expire.
private final class TokenSource: NSObject, SyncTokenSource, @unchecked Sendable {
    let debugToken: String?

    init(debugToken: String?) { self.debugToken = debugToken }

    func fetchToken(forceRefresh: Bool, completion: @escaping (String?, String?) -> Void) {
        if let debugToken {
            completion(debugToken, nil)
            return
        }
        nonisolated(unsafe) let completion = completion
        DispatchQueue.main.async {
            guard let user = GIDSignIn.sharedInstance.currentUser else {
                completion(nil, "Not signed in to Google")
                return
            }
            user.refreshTokensIfNeeded { user, error in
                if let token = user?.accessToken.tokenString {
                    completion(token, nil)
                } else {
                    completion(nil, error?.localizedDescription ?? "No Google access token")
                }
            }
        }
    }
}

/// The sync store's JSON (replica + bookkeeping): Application Support/Yoin/sync/yoin-sync.json, kept out of
/// device backups so a restore never clones a second "same device" (Android: noBackupFilesDir).
private final class StoreFile: NSObject, SyncPersistence, @unchecked Sendable {
    private let url: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("Yoin/sync", isDirectory: true).appendingPathComponent("yoin-sync.json")
    }()

    func load() -> String? { try? String(contentsOf: url, encoding: .utf8) }

    /// True only once the document is on disk; the shared kit keeps the store dirty otherwise (Codex C15).
    func save(text: String) -> Bool {
        let dir = url.deletingLastPathComponent()
        do {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            var folder = dir
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try? folder.setResourceValues(values)
            try text.write(to: url, atomically: true, encoding: .utf8)
            return true
        } catch {
            return false
        }
    }
}

/// Application Support/Yoin/sync-backups/<yyyyMMdd-HHmmss>/: every appDataFolder file's raw bytes under its
/// Drive name (id appended when names repeat) plus manifest.json with ids, versions and appProperties.
final class BackupFolder: NSObject, SyncBackupSink, @unchecked Sendable {
    static var root: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Yoin/sync-backups", isDirectory: true)
    }

    func save(files: [SyncBackupFile], manifestJson: String) -> String? {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyyMMdd-HHmmss-SSS"
        let name = f.string(from: Date())
        let dir = Self.root.appendingPathComponent(name, isDirectory: true)
        do {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            var used = Set<String>()
            for file in files {
                guard let data = file.nsData() else { continue }
                var fileName = file.name.isEmpty ? file.id : file.name
                fileName = fileName.replacingOccurrences(of: "/", with: "_")
                if !used.insert(fileName).inserted { fileName += "." + file.id }
                try (data as Data).write(to: dir.appendingPathComponent(fileName), options: .atomic)
            }
            try manifestJson.write(to: dir.appendingPathComponent("manifest.json"), atomically: true, encoding: .utf8)
            return name
        } catch {
            return nil
        }
    }

}
