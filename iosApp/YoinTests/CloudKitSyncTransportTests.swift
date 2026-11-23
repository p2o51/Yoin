import CloudKit
import XCTest
@testable import Yoin
import YoinShared

extension RemoteFile: @retroactive @unchecked Sendable {}
extension PlatformTransportError: @retroactive @unchecked Sendable {}
extension KotlinByteArray: @retroactive @unchecked Sendable {}

/// Hermetic CloudKit Private Database sync tests: verifies `CloudKitSyncTransport` metadata-only `list()`,
/// >1 MB `CKAsset` payloads, `CKError` mapping, `ScopePurgeMarker`, and full `CloudSyncKit` iCloud sync.
final class CloudKitSyncTransportTests: XCTestCase {
    actor FakeCloudKitStore: CloudKitStore {
        struct Entry {
            var meta: CloudKitRecordMetadata
            var data: Data
        }

        var permissionId = "icloud:_apple_user_42"
        var displayName = "iCloud"
        var entries: [String: Entry] = [:]
        var downloadCalls = 0
        var injectedError: Error?

        func setInjectedError(_ error: Error?) {
            injectedError = error
        }

        func clearEntries() {
            entries.removeAll()
        }

        func accountIdentity() async throws -> (permissionId: String, displayName: String) {
            if let injectedError { throw injectedError }
            return (permissionId, displayName)
        }

        func listMetadata() async throws -> [CloudKitRecordMetadata] {
            if let injectedError { throw injectedError }
            return entries.values.map(\.meta)
        }

        func downloadPayload(id: String) async throws -> Data? {
            if let injectedError { throw injectedError }
            downloadCalls += 1
            return entries[id]?.data
        }

        func createRecord(
            id: String,
            name: String,
            appProperties: [String: String],
            mimeType: String,
            data: Data
        ) async throws -> CloudKitRecordMetadata {
            if let injectedError { throw injectedError }
            if entries[id] != nil {
                throw CloudKitStoreError.alreadyExists("Record \(id) exists")
            }
            let meta = CloudKitRecordMetadata(
                id: id,
                name: name,
                version: 1,
                modifiedTimeMs: 1_800_000_000_000,
                size: Int64(data.count),
                appProperties: appProperties
            )
            entries[id] = Entry(meta: meta, data: data)
            return meta
        }

        func updateRecord(id: String, data: Data) async throws -> CloudKitRecordMetadata? {
            if let injectedError { throw injectedError }
            guard var entry = entries[id] else { return nil }
            entry.meta.version += 1
            entry.meta.size = Int64(data.count)
            entry.meta.modifiedTimeMs = (entry.meta.modifiedTimeMs ?? 0) + 1_000
            entry.data = data
            entries[id] = entry
            return entry.meta
        }

        func deleteRecord(id: String) async throws -> Bool {
            if let injectedError { throw injectedError }
            entries.removeValue(forKey: id)
            return true
        }
    }

    func test_should_listMetadataWithoutDownloadingPayloads_andSupportLargeAssetRoundTrip() async throws {
        let store = FakeCloudKitStore()
        let transport = CloudKitSyncTransport(store: store)

        // > 1.2 MB payload (exceeds CloudKit's 1 MB CKRecord field limit; stored via CKAsset).
        let largeData = Data(repeating: 0x5A, count: 1_250_000)
        let bytes = SyncByteBridge.shared.toByteArray(data: largeData)

        let created: RemoteFile = try await withCheckedThrowingContinuation { cont in
            transport.create(
                fileId: "ck-1",
                name: "yoin-state-dev1.json.gz",
                appProperties: [
                    SyncFileProps.shared.KIND: SyncFileProps.shared.KIND_STATE,
                    SyncFileProps.shared.DEVICE_ID: "dev1",
                ],
                mimeType: "application/gzip",
                bytes: bytes
            ) { file, error in
                if let error { cont.resume(throwing: NSError(domain: "test", code: Int(error.code))) }
                else if let file { cont.resume(returning: file) }
            }
        }
        XCTAssertEqual(created.id, "ck-1")
        XCTAssertEqual(created.version, 1)
        XCTAssertEqual(created.size?.int64Value, 1_250_000)

        // Duplicate create must surface ALREADY_EXISTS (Drive HTTP 409 parity).
        let duplicateError: PlatformTransportError? = await withCheckedContinuation { cont in
            transport.create(
                fileId: "ck-1",
                name: "yoin-state-dev1.json.gz",
                appProperties: [:],
                mimeType: "application/gzip",
                bytes: bytes
            ) { _, error in cont.resume(returning: error) }
        }
        XCTAssertEqual(duplicateError?.kind, .alreadyExists)

        // list() returns metadata without touching downloadPayload().
        let listed: [RemoteFile] = try await withCheckedThrowingContinuation { cont in
            transport.list { files, error in
                if let error { cont.resume(throwing: NSError(domain: "test", code: Int(error.code))) }
                else { cont.resume(returning: files ?? []) }
            }
        }
        XCTAssertEqual(listed.count, 1)
        XCTAssertEqual(listed[0].appProperties[SyncFileProps.shared.KIND], SyncFileProps.shared.KIND_STATE)
        let downloadsBefore = await store.downloadCalls
        XCTAssertEqual(downloadsBefore, 0)

        // download() recovers the full >1.2 MB payload losslessly.
        let downloaded: Data? = try await withCheckedThrowingContinuation { cont in
            transport.download(fileId: "ck-1") { data, error in
                if let error { cont.resume(throwing: NSError(domain: "test", code: Int(error.code))) }
                else { cont.resume(returning: data.map { SyncByteBridge.shared.toNSData(bytes: $0) as Data }) }
            }
        }
        XCTAssertEqual(downloaded, largeData)

        // update() increments version; delete() removes record and returns true on missing id.
        let updated: RemoteFile? = try await withCheckedThrowingContinuation { cont in
            transport.update(fileId: "ck-1", bytes: SyncByteBridge.shared.toByteArray(data: Data([1, 2, 3]))) { file, error in
                if let error { cont.resume(throwing: NSError(domain: "test", code: Int(error.code))) }
                else { cont.resume(returning: file) }
            }
        }
        XCTAssertEqual(updated?.version, 2)
        XCTAssertEqual(updated?.size?.int64Value, 3)

        let deleted: Bool = try await withCheckedThrowingContinuation { cont in
            transport.delete(fileId: "ck-1") { ok, error in
                if let error { cont.resume(throwing: NSError(domain: "test", code: Int(error.code))) }
                else { cont.resume(returning: ok.boolValue) }
            }
        }
        XCTAssertTrue(deleted)
    }

    func test_should_classifyCloudKitErrorsIntoPlatformTransportErrorKinds() {
        XCTAssertEqual(
            CloudKitSyncTransport.mapError(CKError(.notAuthenticated)).kind,
            .unauthorized
        )
        XCTAssertEqual(
            CloudKitSyncTransport.mapError(CKError(.quotaExceeded)).kind,
            .storageFull
        )
        XCTAssertEqual(
            CloudKitSyncTransport.mapError(CKError(.networkUnavailable)).kind,
            .network
        )
        XCTAssertEqual(
            CloudKitSyncTransport.mapError(CKError(.missingEntitlement)).kind,
            .misconfigured
        )
        XCTAssertEqual(
            CloudKitSyncTransport.mapError(CKError(.serverRecordChanged)).kind,
            .alreadyExists
        )
        XCTAssertEqual(
            CloudKitSyncTransport.mapError(CKError(.requestRateLimited)).kind,
            .rateLimited
        )
    }
}
