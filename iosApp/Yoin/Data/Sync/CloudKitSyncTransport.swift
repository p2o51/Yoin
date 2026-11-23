import CloudKit
import Foundation
import YoinShared

/// Metadata for one `YoinSyncFile` record in the user's CloudKit Private Database (`YoinSyncZone`).
/// Kept payload-free so `list()` never downloads `CKAsset` bytes for unchanged peer files.
struct CloudKitRecordMetadata: Hashable, Sendable {
    var id: String
    var name: String
    var version: Int64
    var modifiedTimeMs: Int64?
    var size: Int64?
    var appProperties: [String: String]
}

/// Typed CloudKit transport failures mapped directly onto `PlatformTransportError` for `:shared`.
enum CloudKitStoreError: Error, Sendable {
    case unauthorized(String)
    case misconfigured(String)
    case storageFull(String)
    case rateLimited(String, retryAfterMs: Int64?)
    case network(String)
    case alreadyExists(String)
    case server(code: Int32, message: String)

    func toPlatformError() -> PlatformTransportError {
        switch self {
        case .unauthorized(let msg):
            PlatformTransportError(kind: .unauthorized, message: msg, code: 401, retryAfterMs: nil)
        case .misconfigured(let msg):
            PlatformTransportError(kind: .misconfigured, message: msg, code: 403, retryAfterMs: nil)
        case .storageFull(let msg):
            PlatformTransportError(kind: .storageFull, message: msg, code: 507, retryAfterMs: nil)
        case .rateLimited(let msg, let retry):
            PlatformTransportError(kind: .rateLimited, message: msg, code: 429, retryAfterMs: retry.map(KotlinLong.init(value:)))
        case .network(let msg):
            PlatformTransportError(kind: .network, message: msg, code: 0, retryAfterMs: nil)
        case .alreadyExists(let msg):
            PlatformTransportError(kind: .alreadyExists, message: msg, code: 409, retryAfterMs: nil)
        case .server(let code, let msg):
            PlatformTransportError(kind: .server, message: msg, code: code, retryAfterMs: nil)
        }
    }
}

/// Abstracted CloudKit Private Database operations so unit tests can inject an in-memory store.
protocol CloudKitStore: Sendable {
    func accountIdentity() async throws -> (permissionId: String, displayName: String)
    func listMetadata() async throws -> [CloudKitRecordMetadata]
    func downloadPayload(id: String) async throws -> Data?
    func createRecord(
        id: String,
        name: String,
        appProperties: [String: String],
        mimeType: String,
        data: Data
    ) async throws -> CloudKitRecordMetadata
    func updateRecord(id: String, data: Data) async throws -> CloudKitRecordMetadata?
    func deleteRecord(id: String) async throws -> Bool
}

/// Native CloudKit Private Database implementation of `SyncTransport` (via `PlatformSyncTransport`).
///
/// Storage layout (docs/cloud-sync.md):
/// - Container: `iCloud.com.gpo.yoin`, Private Database, custom zone `YoinSyncZone`.
/// - Record type: `YoinSyncFile`, keyed by `recordName = fileId`.
/// - Metadata fields: `name`, `mimeType`, `size`, `version`, `modifiedAt`, `appPropertiesJson`.
/// - Payload field: `payload` (`CKAsset`, avoiding the 1 MB `CKRecord` field limit).
/// - `list()` uses `CKFetchRecordZoneChangesOperation` with `desiredKeys` (omitting `payload`), which is
///   strongly consistent and needs zero `CKQuery` dashboard index configuration.
final class CloudKitSyncTransport: NSObject, PlatformSyncTransport, @unchecked Sendable {
    static let containerIdentifier = "iCloud.com.gpo.yoin"
    static let zoneName = "YoinSyncZone"
    static let recordType = "YoinSyncFile"

    private let store: any CloudKitStore

    init(store: any CloudKitStore = LiveCloudKitStore(containerIdentifier: CloudKitSyncTransport.containerIdentifier)) {
        self.store = store
    }

    func list(completion: @escaping ([RemoteFile]?, PlatformTransportError?) -> Void) {
        nonisolated(unsafe) let done = completion
        let store = self.store
        Task.detached(priority: .userInitiated) {
            do {
                let items = try await store.listMetadata().map(Self.toRemoteFile)
                done(items, nil)
            } catch {
                done(nil, Self.mapError(error))
            }
        }
    }

    func download(fileId: String, completion: @escaping (KotlinByteArray?, PlatformTransportError?) -> Void) {
        nonisolated(unsafe) let done = completion
        let store = self.store
        Task.detached(priority: .userInitiated) {
            do {
                guard let data = try await store.downloadPayload(id: fileId) else {
                    done(nil, nil)
                    return
                }
                done(SyncByteBridge.shared.toByteArray(data: data), nil)
            } catch {
                done(nil, Self.mapError(error))
            }
        }
    }

    func create(
        fileId: String,
        name: String,
        appProperties: [String: String],
        mimeType: String,
        bytes: KotlinByteArray,
        completion: @escaping (RemoteFile?, PlatformTransportError?) -> Void
    ) {
        let data = SyncByteBridge.shared.toNSData(bytes: bytes) as Data
        nonisolated(unsafe) let done = completion
        let store = self.store
        Task.detached(priority: .userInitiated) {
            do {
                let meta = try await store.createRecord(
                    id: fileId,
                    name: name,
                    appProperties: appProperties,
                    mimeType: mimeType,
                    data: data
                )
                done(Self.toRemoteFile(meta), nil)
            } catch {
                done(nil, Self.mapError(error))
            }
        }
    }

    func update(
        fileId: String,
        bytes: KotlinByteArray,
        completion: @escaping (RemoteFile?, PlatformTransportError?) -> Void
    ) {
        let data = SyncByteBridge.shared.toNSData(bytes: bytes) as Data
        nonisolated(unsafe) let done = completion
        let store = self.store
        Task.detached(priority: .userInitiated) {
            do {
                guard let meta = try await store.updateRecord(id: fileId, data: data) else {
                    done(nil, nil)
                    return
                }
                done(Self.toRemoteFile(meta), nil)
            } catch {
                done(nil, Self.mapError(error))
            }
        }
    }

    func delete(fileId: String, completion: @escaping (KotlinBoolean, PlatformTransportError?) -> Void) {
        nonisolated(unsafe) let done = completion
        let store = self.store
        Task.detached(priority: .userInitiated) {
            do {
                let removed = try await store.deleteRecord(id: fileId)
                done(KotlinBoolean(value: removed), nil)
            } catch {
                done(KotlinBoolean(value: false), Self.mapError(error))
            }
        }
    }

    func generateIds(count: Int32, completion: @escaping ([String]?, PlatformTransportError?) -> Void) {
        let n = max(0, Int(count))
        let ids = (0..<n).map { _ in "ck-\(UUID().uuidString.lowercased())" }
        completion(ids, nil)
    }

    func about(completion: @escaping (GoogleAccountInfo?, PlatformTransportError?) -> Void) {
        nonisolated(unsafe) let done = completion
        let store = self.store
        Task.detached(priority: .userInitiated) {
            do {
                let id = try await store.accountIdentity()
                done(GoogleAccountInfo(permissionId: id.permissionId, email: nil, displayName: id.displayName), nil)
            } catch {
                done(nil, Self.mapError(error))
            }
        }
    }

    private static func toRemoteFile(_ meta: CloudKitRecordMetadata) -> RemoteFile {
        RemoteFile(
            id: meta.id,
            name: meta.name,
            version: meta.version,
            modifiedTime: meta.modifiedTimeMs.map(KotlinLong.init(value:)),
            size: meta.size.map(KotlinLong.init(value:)),
            appProperties: meta.appProperties
        )
    }

    static func mapError(_ error: Error) -> PlatformTransportError {
        if let storeError = error as? CloudKitStoreError {
            return storeError.toPlatformError()
        }
        return classifyCKError(error).toPlatformError()
    }

    static func classifyCKError(_ error: Error) -> CloudKitStoreError {
        guard let ck = error as? CKError else {
            return .network(error.localizedDescription)
        }
        switch ck.code {
        case .notAuthenticated, .permissionFailure, .managedAccountRestricted:
            return .unauthorized("Sign in to iCloud in Settings to use iCloud sync.")
        case .badContainer, .missingEntitlement:
            return .misconfigured("iCloud container is not configured for this build.")
        case .quotaExceeded:
            return .storageFull("Your iCloud storage is full.")
        case .requestRateLimited, .serviceUnavailable, .zoneBusy:
            let retryMs = ck.retryAfterSeconds.map { Int64($0 * 1000.0) }
            return .rateLimited("iCloud asked Yoin to slow down.", retryAfterMs: retryMs)
        case .networkUnavailable, .networkFailure:
            return .network("Couldn't reach iCloud. Check your connection.")
        case .serverRecordChanged:
            return .alreadyExists("CloudKit record already exists.")
        default:
            return .server(code: Int32(ck.code.rawValue), message: ck.localizedDescription)
        }
    }
}

/// Live CloudKit Private Database store (`YoinSyncZone` / `YoinSyncFile` + `CKAsset`).
actor LiveCloudKitStore: CloudKitStore {
    private enum Field {
        static let name = "name"
        static let mimeType = "mimeType"
        static let size = "size"
        static let version = "version"
        static let modifiedAt = "modifiedAt"
        static let appPropertiesJson = "appPropertiesJson"
        static let payload = "payload"
    }

    private let containerIdentifier: String
    private let zoneID = CKRecordZone.ID(zoneName: CloudKitSyncTransport.zoneName, ownerName: CKCurrentUserDefaultName)
    private var zoneReady = false

    init(containerIdentifier: String) {
        self.containerIdentifier = containerIdentifier
    }

    private var container: CKContainer { CKContainer(identifier: containerIdentifier) }
    private var database: CKDatabase { container.privateCloudDatabase }

    func accountIdentity() async throws -> (permissionId: String, displayName: String) {
        let status = try await container.accountStatus()
        switch status {
        case .available:
            let userRecord = try await container.userRecordID()
            return ("icloud:\(userRecord.recordName)", "iCloud")
        case .noAccount, .restricted:
            throw CloudKitStoreError.unauthorized("Sign in to iCloud in Settings to use iCloud sync.")
        case .couldNotDetermine, .temporarilyUnavailable:
            throw CloudKitStoreError.network("iCloud is temporarily unavailable.")
        @unknown default:
            throw CloudKitStoreError.network("Couldn't verify iCloud account status.")
        }
    }

    func listMetadata() async throws -> [CloudKitRecordMetadata] {
        do {
            try await ensureZone()
            return try await fetchAllZoneMetadata()
        } catch let ck as CKError where ck.code == .zoneNotFound || ck.code == .userDeletedZone {
            zoneReady = false
            try await ensureZone()
            return []
        }
    }

    func downloadPayload(id: String) async throws -> Data? {
        try await ensureZone()
        let recordID = CKRecord.ID(recordName: id, zoneID: zoneID)
        do {
            let record = try await database.record(for: recordID)
            guard let asset = record[Field.payload] as? CKAsset, let url = asset.fileURL else {
                return Data()
            }
            return try Data(contentsOf: url)
        } catch let ck as CKError where ck.code == .unknownItem || ck.code == .zoneNotFound || ck.code == .userDeletedZone {
            if ck.code == .zoneNotFound || ck.code == .userDeletedZone { zoneReady = false }
            return nil
        }
    }

    func createRecord(
        id: String,
        name: String,
        appProperties: [String: String],
        mimeType: String,
        data: Data
    ) async throws -> CloudKitRecordMetadata {
        try await ensureZone()
        let tempURL = try writeTempAsset(data)
        defer { try? FileManager.default.removeItem(at: tempURL) }

        let nowMs = Int64(Date().timeIntervalSince1970 * 1000.0)
        let recordID = CKRecord.ID(recordName: id, zoneID: zoneID)
        let record = CKRecord(recordType: CloudKitSyncTransport.recordType, recordID: recordID)
        record[Field.name] = name as CKRecordValue
        record[Field.mimeType] = mimeType as CKRecordValue
        record[Field.size] = NSNumber(value: Int64(data.count))
        record[Field.version] = NSNumber(value: Int64(1))
        record[Field.modifiedAt] = NSNumber(value: nowMs)
        record[Field.appPropertiesJson] = encodeProperties(appProperties) as CKRecordValue
        record[Field.payload] = CKAsset(fileURL: tempURL)

        let saved = try await saveRecord(record, policy: .ifServerRecordUnchanged)
        return metadata(from: saved, fallbackName: name, fallbackProperties: appProperties, fallbackSize: Int64(data.count))
    }

    func updateRecord(id: String, data: Data) async throws -> CloudKitRecordMetadata? {
        try await ensureZone()
        let recordID = CKRecord.ID(recordName: id, zoneID: zoneID)
        let existing: CKRecord
        do {
            existing = try await database.record(for: recordID)
        } catch let ck as CKError where ck.code == .unknownItem || ck.code == .zoneNotFound || ck.code == .userDeletedZone {
            if ck.code == .zoneNotFound || ck.code == .userDeletedZone { zoneReady = false }
            return nil
        }

        let tempURL = try writeTempAsset(data)
        defer { try? FileManager.default.removeItem(at: tempURL) }

        let nextVersion = ((existing[Field.version] as? NSNumber)?.int64Value ?? 0) + 1
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000.0)
        existing[Field.size] = NSNumber(value: Int64(data.count))
        existing[Field.version] = NSNumber(value: nextVersion)
        existing[Field.modifiedAt] = NSNumber(value: nowMs)
        existing[Field.payload] = CKAsset(fileURL: tempURL)

        let saved = try await saveRecord(existing, policy: .changedKeys)
        let props = decodeProperties(saved[Field.appPropertiesJson] as? String)
        let name = (saved[Field.name] as? String) ?? id
        return metadata(from: saved, fallbackName: name, fallbackProperties: props, fallbackSize: Int64(data.count))
    }

    func deleteRecord(id: String) async throws -> Bool {
        let recordID = CKRecord.ID(recordName: id, zoneID: zoneID)
        do {
            _ = try await database.deleteRecord(withID: recordID)
            return true
        } catch let ck as CKError where ck.code == .unknownItem || ck.code == .zoneNotFound || ck.code == .userDeletedZone {
            if ck.code == .zoneNotFound || ck.code == .userDeletedZone { zoneReady = false }
            return true
        }
    }

    // MARK: - Private CloudKit helpers

    private func ensureZone() async throws {
        if zoneReady { return }
        _ = try await database.save(CKRecordZone(zoneID: zoneID))
        zoneReady = true
    }

    private func fetchAllZoneMetadata() async throws -> [CloudKitRecordMetadata] {
        let db = self.database
        let zoneID = self.zoneID
        return try await withCheckedThrowingContinuation { continuation in
            let config = CKFetchRecordZoneChangesOperation.ZoneConfiguration()
            config.previousServerChangeToken = nil
            config.desiredKeys = [
                Field.name,
                Field.size,
                Field.version,
                Field.modifiedAt,
                Field.appPropertiesJson,
            ]
            let op = CKFetchRecordZoneChangesOperation(
                recordZoneIDs: [zoneID],
                configurationsByRecordZoneID: [zoneID: config]
            )
            op.fetchAllChanges = true
            let box = MetadataCollector()
            op.recordWasChangedBlock = { _, result in
                if case .success(let record) = result, record.recordType == CloudKitSyncTransport.recordType {
                    box.append(Self.metadata(from: record))
                }
            }
            op.recordZoneFetchResultBlock = { _, result in
                if case .failure(let error) = result {
                    box.setZoneError(error)
                }
            }
            op.fetchRecordZoneChangesResultBlock = { result in
                switch result {
                case .success:
                    if let zoneError = box.zoneError {
                        continuation.resume(throwing: zoneError)
                    } else {
                        continuation.resume(returning: box.items)
                    }
                case .failure(let error):
                    continuation.resume(throwing: error)
                }
            }
            db.add(op)
        }
    }

    private func saveRecord(_ record: CKRecord, policy: CKModifyRecordsOperation.RecordSavePolicy) async throws -> CKRecord {
        let db = self.database
        return try await withCheckedThrowingContinuation { continuation in
            let op = CKModifyRecordsOperation(recordsToSave: [record], recordIDsToDelete: nil)
            op.savePolicy = policy
            op.isAtomic = true
            let box = SavedRecordBox()
            op.perRecordSaveBlock = { _, result in
                switch result {
                case .success(let saved):
                    box.set(saved)
                case .failure(let error):
                    box.setError(error)
                }
            }
            op.modifyRecordsResultBlock = { result in
                if let perRecordError = box.error {
                    continuation.resume(throwing: perRecordError)
                    return
                }
                switch result {
                case .success:
                    continuation.resume(returning: box.saved ?? record)
                case .failure(let error):
                    continuation.resume(throwing: error)
                }
            }
            db.add(op)
        }
    }

    private func writeTempAsset(_ data: Data) throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("yoin-ck-\(UUID().uuidString).bin")
        try data.write(to: url, options: .atomic)
        return url
    }

    private static func metadata(from record: CKRecord) -> CloudKitRecordMetadata {
        let props = decodePropertiesStatic(record[Field.appPropertiesJson] as? String)
        let name = (record[Field.name] as? String) ?? record.recordID.recordName
        let size = (record[Field.size] as? NSNumber)?.int64Value
        let version = (record[Field.version] as? NSNumber)?.int64Value ?? 1
        let modified = (record[Field.modifiedAt] as? NSNumber)?.int64Value
            ?? record.modificationDate.map { Int64($0.timeIntervalSince1970 * 1000.0) }
        return CloudKitRecordMetadata(
            id: record.recordID.recordName,
            name: name,
            version: version,
            modifiedTimeMs: modified,
            size: size,
            appProperties: props
        )
    }

    private func metadata(
        from record: CKRecord,
        fallbackName: String,
        fallbackProperties: [String: String],
        fallbackSize: Int64
    ) -> CloudKitRecordMetadata {
        let parsed = Self.metadata(from: record)
        return CloudKitRecordMetadata(
            id: parsed.id,
            name: parsed.name.isEmpty ? fallbackName : parsed.name,
            version: parsed.version,
            modifiedTimeMs: parsed.modifiedTimeMs,
            size: parsed.size ?? fallbackSize,
            appProperties: parsed.appProperties.isEmpty ? fallbackProperties : parsed.appProperties
        )
    }

    private func encodeProperties(_ props: [String: String]) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: props, options: [.sortedKeys]),
              let text = String(data: data, encoding: .utf8) else { return "{}" }
        return text
    }

    private func decodeProperties(_ json: String?) -> [String: String] {
        Self.decodePropertiesStatic(json)
    }

    private static func decodePropertiesStatic(_ json: String?) -> [String: String] {
        guard let json, let data = json.data(using: .utf8),
              let dict = try? JSONSerialization.jsonObject(with: data) as? [String: String] else { return [:] }
        return dict
    }
}

private final class MetadataCollector: @unchecked Sendable {
    private let lock = NSLock()
    private var records: [CloudKitRecordMetadata] = []
    private var error: Error?

    func append(_ meta: CloudKitRecordMetadata) {
        lock.lock()
        records.append(meta)
        lock.unlock()
    }

    func setZoneError(_ err: Error) {
        lock.lock()
        if error == nil { error = err }
        lock.unlock()
    }

    var items: [CloudKitRecordMetadata] {
        lock.lock()
        defer { lock.unlock() }
        return records
    }

    var zoneError: Error? {
        lock.lock()
        defer { lock.unlock() }
        return error
    }
}

private final class SavedRecordBox: @unchecked Sendable {
    private let lock = NSLock()
    private var record: CKRecord?
    private var saveError: Error?

    func set(_ r: CKRecord) {
        lock.lock()
        record = r
        lock.unlock()
    }

    func setError(_ e: Error) {
        lock.lock()
        if saveError == nil { saveError = e }
        lock.unlock()
    }

    var saved: CKRecord? {
        lock.lock()
        defer { lock.unlock() }
        return record
    }

    var error: Error? {
        lock.lock()
        defer { lock.unlock() }
        return saveError
    }
}
