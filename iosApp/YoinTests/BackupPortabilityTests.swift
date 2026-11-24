import XCTest
import YoinShared
@testable import Yoin

/// The backup zip as iOS writes and reads it (Data/Sync/BackupZip, Portability): the same entries, in the same
/// order, as Android's BackupWriter, from the shared golden vectors (shared/src/commonTest/resources/golden/
/// backup-export.json), and every way a file can fail to import.
@MainActor
final class BackupPortabilityTests: XCTestCase {
    private static let golden = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        .appendingPathComponent("../../shared/src/commonTest/resources/golden").standardizedFileURL

    /// The zip `IosBackupZipInteropTest` (app/, JVM) reads with Android's BackupReader.
    private static let iosZip = golden.appendingPathComponent("backup-export-ios.zip")

    private func facade() -> YoinBackup {
        YoinBackup(domain: SyncDomainBridge(accounts: { .init(subsonic: [], appleMusic: false, storefront: nil) }), replica: nil,
                   links: TrackAlbumLinkStore(fileURL: nil), deviceId: "test", deviceName: "iPhone", appVersionName: "0.6.3",
                   appVersionCode: 36)
    }

    private struct Case {
        var name: String
        var fileName: String
        var manifestJson: String
        var snapshotJson: String
        var readable: [(path: String, text: String)]
    }

    private func cases() throws -> [Case] {
        let data = try Data(contentsOf: Self.golden.appendingPathComponent("backup-export.json"))
        let root = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        return try XCTUnwrap(root["cases"] as? [[String: Any]]).map { c in
            let e = c["expected"] as! [String: Any]
            return Case(name: c["name"] as! String, fileName: e["fileName"] as! String, manifestJson: e["manifestJson"] as! String,
                        snapshotJson: e["snapshotJson"] as! String,
                        readable: (e["readable"] as! [[String: String]]).map { ($0["path"]!, $0["text"]!) })
        }
    }

    /// The golden files as `YoinBackup.exportBackup` hands them over.
    private func export(_ c: Case) throws -> YoinBackupExport {
        let bridge = SyncByteBridge.shared
        let read = facade().readBackup(manifestJson: bridge.toByteArray(data: Data(c.manifestJson.utf8)),
                                       snapshotJson: bridge.toByteArray(data: Data(c.snapshotJson.utf8)),
                                       readableFiles: Int32(c.readable.count))
        let manifest = try XCTUnwrap(read.backup?.manifest, "\(c.name): \(read.error?.message ?? "")")
        return YoinBackupExport(fileName: c.fileName, manifest: manifest, manifestJson: c.manifestJson, snapshotJson: c.snapshotJson,
                                readable: c.readable.map { BackupTextFile(path: $0.path, text: $0.text) }, readFailures: [])
    }

    // MARK: Layout

    func test_should_writeAndroidsEntries_inItsOrder_when_zippingAnExport() throws {
        for c in try cases() {
            let zip = try BackupZip.write(Portability.entries(of: try export(c)))
            let listed = try BackupZip.list(zip)
            // BackupWriter: manifest.json, data/snapshot.json, then the readable files as :shared lists them.
            XCTAssertEqual(listed.map(\.path), ["manifest.json", "data/snapshot.json"] + c.readable.map(\.path), c.name)
            XCTAssertTrue(listed.allSatisfy { $0.flags & 0x0800 != 0 && $0.flags & 0x0008 == 0 }, "UTF-8 names, no data descriptors")
            let texts = try listed.map { String(decoding: try BackupZip.extract($0, from: zip, limit: 1 << 26), as: UTF8.self) }
            XCTAssertEqual(texts, [c.manifestJson, c.snapshotJson] + c.readable.map(\.text), c.name)
        }
    }

    func test_should_readItsOwnZipBack_throughTheSharedChecks() throws {
        for c in try cases() {
            let zip = try BackupZip.write(Portability.entries(of: try export(c)))
            guard case .success(let backup) = Portability.read(zip, with: facade()) else { return XCTFail(c.name) }
            XCTAssertEqual(Int(backup.readableFiles), c.readable.count)
            XCTAssertEqual(backup.manifest.accounts.map(\.scope), ["s-alice", "local-p2", "local-p3", "s-cloud"])
        }
    }

    /// The fixture Android's BackupReader reads in `IosBackupZipInteropTest`: written by this writer from the
    /// en-UTC golden case. `YOIN_WRITE_IOS_ZIP=1` (TEST_RUNNER_YOIN_WRITE_IOS_ZIP) writes it again.
    func test_should_matchTheZipAndroidReads_when_writingTheGoldenExport() throws {
        let c = try XCTUnwrap(try cases().first { $0.name == "en-UTC" })
        let modified = Date(timeIntervalSince1970: 1_795_400_000)
        let zip = try BackupZip.write(Portability.entries(of: try export(c)), modified: modified)
        if ProcessInfo.processInfo.environment["YOIN_WRITE_IOS_ZIP"] == "1" || !FileManager.default.fileExists(atPath: Self.iosZip.path) {
            try zip.write(to: Self.iosZip)
        }
        let fixture = try Data(contentsOf: Self.iosZip)
        let a = try BackupZip.list(zip), b = try BackupZip.list(fixture)
        XCTAssertEqual(a.map(\.path), b.map(\.path))
        XCTAssertEqual(try a.map { try BackupZip.extract($0, from: zip, limit: 1 << 26) },
                       try b.map { try BackupZip.extract($0, from: fixture, limit: 1 << 26) })
    }

    /// Android's ZipOutputStream writes sizes after each entry (bit 3): read through the central directory.
    func test_should_readAZipWithDataDescriptors_asAndroidWritesThem() throws {
        let body = Data("{\"hello\":\"世界\"}".utf8)
        let deflated = try (body as NSData).compressed(using: .zlib) as Data
        let zip = Self.descriptorZip(name: "manifest.json", plain: body, deflated: deflated)
        let listed = try BackupZip.list(zip)
        XCTAssertEqual(listed.map(\.path), ["manifest.json"])
        XCTAssertEqual(try BackupZip.extract(listed[0], from: zip, limit: 1024), body)
        XCTAssertThrowsError(try BackupZip.extract(listed[0], from: zip, limit: 4)) { error in
            XCTAssertEqual(error as? BackupZip.Failure, .tooLarge("manifest.json"))
        }
    }

    func test_should_computeTheZipCrc() {
        XCTAssertEqual(CRC32.checksum(Data("123456789".utf8)), 0xCBF4_3926)
        XCTAssertEqual(CRC32.checksum(Data()), 0)
    }

    // MARK: Import errors

    private func read(_ entries: [BackupZip.Entry]) throws -> Portability.ReadFailure? {
        let zip = try BackupZip.write(entries)
        if case .failure(let failure) = Portability.read(zip, with: facade()) { return failure }
        return nil
    }

    func test_should_tellEachReadFailureApart_when_importingAFile() throws {
        let c = try XCTUnwrap(try cases().first)
        let manifest = Data(c.manifestJson.utf8), snapshot = Data(c.snapshotJson.utf8)
        func entry(_ path: String, _ data: Data) -> BackupZip.Entry { BackupZip.Entry(path: path, data: data) }

        XCTAssertNil(try read([entry("manifest.json", manifest), entry("data/snapshot.json", snapshot)]))
        // Not a zip at all, or a zip without a Yoin manifest.
        if case .failure(let f) = Portability.read(Data("PK not really".utf8), with: facade()) { XCTAssertEqual(f, .notABackup) } else { XCTFail() }
        XCTAssertEqual(try read([entry("notes.txt", Data("hi".utf8))]), .notABackup)
        XCTAssertEqual(try read([entry("manifest.json", Data("{\"format\":\"something-else\"}".utf8))]), .notABackup)
        // Written by a newer Yoin.
        let newer = c.manifestJson.replacingOccurrences(of: "\"formatVersion\": 1", with: "\"formatVersion\": 99")
        XCTAssertNotEqual(newer, c.manifestJson)
        XCTAssertEqual(try read([entry("manifest.json", Data(newer.utf8)), entry("data/snapshot.json", snapshot)]), .tooNew)
        // The snapshot was changed after export.
        let edited = Data(c.snapshotJson.replacingOccurrences(of: "Pixel 9", with: "Pixel 8").utf8)
        XCTAssertEqual(try read([entry("manifest.json", manifest), entry("data/snapshot.json", edited)]), .checksum)
        // The snapshot is missing.
        XCTAssertEqual(try read([entry("manifest.json", manifest)]), .unreadable)
        // An entry whose bytes don't match its CRC.
        var zip = try BackupZip.write([entry("manifest.json", manifest), entry("data/snapshot.json", snapshot)])
        let at = try XCTUnwrap(try BackupZip.list(zip).last).localHeaderOffset + 30 + "data/snapshot.json".utf8.count + 4
        zip[at] ^= 0xFF
        if case .failure(let f) = Portability.read(zip, with: facade()) { XCTAssertEqual(f, .unreadable) } else { XCTFail() }
    }

    func test_should_mapTheSharedErrorKinds_toWhatThePageSays() {
        XCTAssertEqual(Portability.failure(of: .notABackup), .notABackup)
        XCTAssertEqual(Portability.failure(of: .tooNew), .tooNew)
        XCTAssertEqual(Portability.failure(of: .checksumMismatch), .checksum)
        XCTAssertEqual(Portability.failure(of: .corrupt), .unreadable)
        XCTAssertEqual(Portability.failure(of: .other), .unreadable)
        XCTAssertNotEqual(BackupFlowView.readFailureTitle(.checksum), "settings_sync_read_checksum")
    }

    // MARK: Import choices

    func test_should_offerAndroidsTargets_forEachBackupAccount() {
        let account = BackupAccount(scope: "s-a", provider: "subsonic", displayName: "Alice", hint: nil, storefront: nil, onDevice: true,
                                    counts: [:], readableDir: nil)
        let options = [ImportProfileOption(localProfileId: "p1", provider: "subsonic", displayName: "Home"),
                       ImportProfileOption(localProfileId: "p2", provider: "subsonic", displayName: "Work")]
        func plan(same: Bool, cloud: Bool, purged: Bool) -> YoinImportAccount {
            YoinImportAccount(account: account, counts: [:],
                              target: YoinImportChoice(scope: "s-a", kind: same ? .intoProfile : .skip, profileId: same ? "p1" : nil),
                              sameAccount: same, options: options, cloudOnlyPossible: cloud, wasPurged: purged)
        }
        // The same account here: it or skip.
        XCTAssertEqual(Portability.targetOptions(plan(same: true, cloud: true, purged: false)).map(\.kind), [.intoProfile, .skip])
        XCTAssertEqual(Portability.targetOptions(plan(same: false, cloud: true, purged: false)).map { $0.profileId ?? "\($0.kind)" },
                       ["p1", "p2", "\(YoinImportTargetKind.cloudOnly)", "\(YoinImportTargetKind.skip)"])
        // Removed from the cloud on this device: never "keep in the cloud".
        XCTAssertFalse(Portability.targetOptions(plan(same: false, cloud: true, purged: true)).contains { $0.kind == .cloudOnly })
    }

    // MARK: Pieces

    /// One DEFLATEd entry with bit 3 set: zero sizes and CRC in the local header, a data descriptor after it.
    static func descriptorZip(name: String, plain: Data, deflated: Data) -> Data {
        var out = Data()
        func le16(_ v: Int) { out.append(contentsOf: [UInt8(v & 0xFF), UInt8((v >> 8) & 0xFF)]) }
        func le32(_ v: UInt32) { out.append(contentsOf: [UInt8(v & 0xFF), UInt8((v >> 8) & 0xFF), UInt8((v >> 16) & 0xFF), UInt8(v >> 24)]) }
        let n = Data(name.utf8), crc = CRC32.checksum(plain)
        le32(0x0403_4B50); le16(20); le16(0x0808); le16(8); le16(0); le16(0x21); le32(0); le32(0); le32(0)
        le16(n.count); le16(0); out.append(n); out.append(deflated)
        le32(0x0807_4B50); le32(crc); le32(UInt32(deflated.count)); le32(UInt32(plain.count))
        let cd = out.count
        le32(0x0201_4B50); le16(20); le16(20); le16(0x0808); le16(8); le16(0); le16(0x21); le32(crc)
        le32(UInt32(deflated.count)); le32(UInt32(plain.count)); le16(n.count); le16(0); le16(0); le16(0); le16(0); le32(0); le32(0)
        out.append(n)
        let size = out.count - cd
        le32(0x0605_4B50); le16(0); le16(0); le16(1); le16(1); le32(UInt32(size)); le32(UInt32(cd)); le16(0)
        return out
    }
}

/// `TrackAlbumLinkStore`: Android's `track_album_links` on iOS (the shared core's `YoinTrackAlbumLinkStore`).
final class TrackAlbumLinkStoreTests: XCTestCase {
    private var url: URL!

    override func setUp() {
        url = FileManager.default.temporaryDirectory.appendingPathComponent("links-\(UUID().uuidString).json")
    }

    override func tearDown() { try? FileManager.default.removeItem(at: url) }

    func test_should_keepOneLinkPerSong_perAccount_acrossLaunches() {
        let store = TrackAlbumLinkStore(fileURL: url)
        XCTAssertTrue(store.putLink(profileId: "p1", link: BackupTrackAlbumLink(provider: "subsonic", trackId: "t1", albumId: "a1",
                                                                                source: "import", updatedAt: 1)))
        XCTAssertTrue(store.putLink(profileId: "p1", link: BackupTrackAlbumLink(provider: "subsonic", trackId: "t1", albumId: "a2",
                                                                                source: "detail", updatedAt: 2)))
        XCTAssertTrue(store.putLink(profileId: "p2", link: BackupTrackAlbumLink(provider: "subsonic", trackId: "t1", albumId: "b1",
                                                                                source: "import", updatedAt: 3)))

        let again = TrackAlbumLinkStore(fileURL: url)
        XCTAssertEqual(again.links(profileId: "p1").map(\.albumId), ["a2"])
        XCTAssertEqual(again.link(profileId: "p1", provider: "subsonic", trackId: "t1")?.source, "detail")
        XCTAssertEqual(again.link(profileId: "p2", provider: "subsonic", trackId: "t1")?.albumId, "b1")
        XCTAssertNil(again.link(profileId: "p1", provider: "spotify", trackId: "t1"))

        again.removeAll(profileId: "p1")
        XCTAssertTrue(TrackAlbumLinkStore(fileURL: url).links(profileId: "p1").isEmpty)
        XCTAssertEqual(TrackAlbumLinkStore(fileURL: url).links(profileId: "p2").count, 1)
    }

    func test_should_reportFalse_andChangeNothing_when_theFileCantBeWritten() {
        let blocked = URL(fileURLWithPath: "/dev/null/links.json")
        let store = TrackAlbumLinkStore(fileURL: blocked)
        XCTAssertFalse(store.putLink(profileId: "p1", link: BackupTrackAlbumLink(provider: "subsonic", trackId: "t1", albumId: "a1",
                                                                                 source: "import", updatedAt: 1)))
        XCTAssertNil(store.link(profileId: "p1", provider: "subsonic", trackId: "t1"))
    }
}
