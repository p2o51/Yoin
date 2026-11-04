import Foundation
import GoogleSignIn
import Observation
import UIKit
import YoinShared

extension CloudSyncKit: @retroactive @unchecked Sendable {}
extension CloudSyncCore: @retroactive @unchecked Sendable {}
extension CloudSyncState: @retroactive @unchecked Sendable {}

/// Cloud sync on iOS (docs/cloud-sync.md; iosApp/docs/cloud-sync-ios.md).
///
/// The sync itself is the shared Kotlin (`CloudSyncKit` / `CloudSyncCore`,
/// YoinShared): the same files in the user's Drive appDataFolder as Android,
/// the same merge and conflict rules. This side supplies what is per platform:
/// the Google sign-in (GoogleSignIn-iOS, scope drive.appdata only), the stores
/// (`SyncDomainBridge`), the sync store's file, the backups, and when cycles
/// run (foreground at most once a minute, 8 s after a local change, on
/// background with pending changes, Sync now).
///
/// Safety for the first real account: the first cycle only pulls (Android's
/// data lands in the iOS stores, nothing is written to Drive) and saves every
/// appDataFolder file under Application Support/Yoin/sync-backups/<time>/.
/// Two-way sync starts with the next cycle.
@MainActor
@Observable
final class CloudSync {
    static let shared = CloudSync()

    static let driveScope = "https://www.googleapis.com/auth/drive.appdata"

    private(set) var state = CloudSyncState(phase: CloudSyncPhaseOff.shared, account: nil, lastSyncAt: nil,
                                            pendingChanges: false, accounts: [], cloudOnlyAccounts: [], devices: [],
                                            lastSummary: nil, readOnly: false, lastBackup: nil)
    private(set) var working = false
    /// The last action's message for the page (a failed sign-in, a failed delete).
    var message: String?

    /// DEBUG fake Drive: `-syncDebugBaseURL http://127.0.0.1:8765/ -syncDebugToken x`.
    let debug: (base: String, token: String)?

    /// The Google client ID from Info.plist (GIDClientID ← Config/GoogleSignIn.xcconfig), when it is real.
    let clientID: String?

    var isConfigured: Bool { clientID != nil || debug != nil }

    @ObservationIgnored private var kit: CloudSyncKit?
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
        self.accounts = accounts
        let domain = SyncDomainBridge(accounts: accounts)
        let kit = CloudSyncKit(
            domain: domain,
            tokenSource: TokenSource(debugToken: debug?.token),
            persistence: StoreFile(),
            deviceName: UIDevice.current.model,
            versionCode: Int32(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "1") ?? 1,
            apiBase: debug.map { $0.base + "drive/v3/" },
            uploadBase: debug.map { $0.base + "upload/drive/v3/" },
            backupSink: BackupFolder()
        )
        self.kit = kit
        kit.core.onStateChange = { [weak self] state in
            MainActor.assumeIsolated { self?.state = state }
        }
        NotesStore.onLocalChange = { CloudSync.shared.localChanged() }
        LocalRatings.onLocalChange = { CloudSync.shared.localChanged() }
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
            foreground()
        }
    }

    /// `.onOpenURL`: Google's sign-in redirect.
    static func handle(url: URL) -> Bool { GIDSignIn.sharedInstance.handle(url) }

    // MARK: Actions

    /// Turn on (or reconnect): Google sign-in asking only for drive.appdata, then the first (read-only) pull.
    func turnOn() async {
        guard let kit, isConfigured else { return }
        message = nil
        working = true
        defer { working = false }
        let token: String
        if let debug {
            token = debug.token
        } else {
            guard let clientID else { return }
            GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: clientID)
            guard let presenter = Self.presenter() else { return }
            do {
                let result = try await GIDSignIn.sharedInstance.signIn(withPresenting: presenter, hint: nil,
                                                                        additionalScopes: [Self.driveScope])
                var user = result.user
                if !(user.grantedScopes ?? []).contains(Self.driveScope) {
                    user = try await user.addScopes([Self.driveScope], presenting: presenter).user
                }
                guard (user.grantedScopes ?? []).contains(Self.driveScope) else {
                    message = "Google didn't allow Drive access."
                    return
                }
                token = user.accessToken.tokenString
            } catch {
                if (error as NSError).code != GIDSignInError.canceled.rawValue { message = error.localizedDescription }
                return
            }
        }
        do {
            if let failure = try await kit.core.enable(accessToken: token) { message = failure }
        } catch {
            message = error.localizedDescription
        }
    }

    func syncNow() async { await run { try await $0.syncNow() } }

    func turnOff() async { await run { try await $0.turnOff() } }

    /// All devices: Android's flow (back up first, reset marker, turn off here, delete Yoin's files).
    func deleteCloudData() async {
        guard let kit else { return }
        working = true
        defer { working = false }
        do {
            message = try await kit.core.deleteCloudData()
        } catch {
            message = error.localizedDescription
        }
        if message == nil, debug == nil { GIDSignIn.sharedInstance.signOut() }
    }

    func resolveReview(restore: Bool) async { await run { try await $0.resolveReview(restore: restore) } }
    func uploadAgain() async { await run { try await $0.uploadAgain() } }
    func startSyncing(_ profileId: String) async { await run { try await $0.startSyncingAccount(localProfileId: profileId) } }
    func link(_ profileId: String, to scope: String) async {
        await run { try await $0.linkAccount(localProfileId: profileId, syncProfileId: scope) }
    }
    func keepAsBefore(_ profileId: String) async { await run { try await $0.keepAccountAsBefore(localProfileId: profileId) } }
    func startFresh(_ profileId: String) async { await run { try await $0.startFreshAccount(localProfileId: profileId) } }

    /// Backs the whole appDataFolder up now; returns the folder for the share sheet.
    func backupNow() async -> URL? {
        guard let kit else { return nil }
        working = true
        defer { working = false }
        guard let name = try? await kit.core.backupNow() else {
            message = "Couldn't back up your Drive data."
            return nil
        }
        return BackupFolder.root.appendingPathComponent(name, isDirectory: true)
    }

    /// The newest backup as one .zip (NSFileCoordinator zips a folder for sharing).
    func exportLatestBackup() -> URL? {
        guard let name = state.lastBackup else { return nil }
        return BackupFolder.zip(BackupFolder.root.appendingPathComponent(name, isDirectory: true))
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

    func save(text: String) {
        let dir = url.deletingLastPathComponent()
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var folder = dir
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? folder.setResourceValues(values)
        try? text.write(to: url, atomically: true, encoding: .utf8)
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

    /// A shareable .zip of [folder] (the system zips it for "uploading").
    static func zip(_ folder: URL) -> URL? {
        var out: URL?
        var error: NSError?
        NSFileCoordinator().coordinate(readingItemAt: folder, options: .forUploading, error: &error) { zipped in
            let target = FileManager.default.temporaryDirectory.appendingPathComponent("yoin-drive-\(folder.lastPathComponent).zip")
            try? FileManager.default.removeItem(at: target)
            if (try? FileManager.default.copyItem(at: zipped, to: target)) != nil { out = target }
        }
        return out
    }
}
