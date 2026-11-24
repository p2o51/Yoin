#if DEBUG
import Foundation
import YoinShared

/// `-portabilityDemo <step>` (with `-syncOpenPage YES`): the backup and transfer pages at one step each, on
/// made-up accounts, for screenshots. Plans come from the real shared core over demo profiles (nothing is
/// written: no step here imports or copies). Never in release builds.
@MainActor
enum PortabilityDemo {
    static var pendingBackup: BackupFlowModel?

    static func takeBackup() -> BackupFlowModel? {
        defer { pendingBackup = nil }
        return pendingBackup
    }

    /// Two accounts of a demo device: a Subsonic server and a Spotify listener.
    private static let accounts = SyncDomainBridge.Accounts(
        subsonic: [.init(profileId: "demo-home", url: "https://music.example.com", username: "kiri",
                         scope: "https://music.example.com|kiri")],
        appleMusic: false, storefront: nil,
        spotify: [.init(profileId: "demo-spotify", userId: "chen-demo", displayName: "Chen", createdAt: 2)])

    /// The facade over the demo profiles (the stores behind them are only read).
    private static func facade() -> YoinBackup {
        YoinBackup(domain: SyncDomainBridge(accounts: { accounts }), replica: nil, links: TrackAlbumLinkStore(fileURL: nil),
                   deviceId: "demo", deviceName: "iPhone", appVersionName: "0.6.3", appVersionCode: 36)
    }

    private static let now: Int64 = 1_795_486_400_000

    // MARK: Backup

    static func backup(_ name: String) async -> BackupFlowModel? {
        let model = BackupFlowModel()
        switch name {
        case "export-running": model.show(.exporting(fromCloud: true))
        case "exported": model.show(.exported(fileName: "yoin-backup-2026-11-24.zip", export: export(), bytes: 186_745))
        case "export-failed": model.show(.exportFailed)
        case "reading": model.show(.reading(fileName: "yoin-backup-2026-11-20.zip"))
        case "read-failed": model.show(.readFailed(fileName: "yoin-backup-2026-11-20.zip", .checksum))
        case "review", "importing", "imported", "import-failed":
            guard let review = await review() else { return nil }
            switch name {
            case "review": model.show(.review(review))
            case "importing": model.show(.importing(review))
            case "import-failed": model.show(.importFailed(review))
            default: model.show(.imported(review, imported()))
            }
        default: return nil
        }
        return model
    }

    private static func export() -> YoinBackupExport {
        let manifest = BackupManifest(
            format: "yoin-backup", formatVersion: 1, syncSchema: 1, exportedAt: now,
            source: BackupSource(location: "icloud", deviceId: "demo", deviceName: "iPhone", appVersionName: "0.6.3", appVersionCode: 36),
            accounts: [
                BackupAccount(scope: "s-kiri", provider: "subsonic", displayName: "kiri", hint: "kiri @ music.example.com", storefront: nil,
                              onDevice: true, counts: ["song_note": 128, "track_rating": 89, "album_rating": 43, "album_review": 12],
                              readableDir: "readable/kiri/"),
                BackupAccount(scope: "s-chen", provider: "spotify", displayName: "Chen", hint: nil, storefront: nil, onDevice: true,
                              counts: ["song_note": 31, "track_rating": 20], readableDir: "readable/Chen/"),
            ],
            globalCounts: ["playlist_backup": 4, "lyrics_translation": 52, "song_about": 17],
            snapshot: BackupSnapshotInfo(path: "data/snapshot.json", sha256: String(repeating: "0", count: 64), bytes: 900_000,
                                         records: 396, tombstones: 0))
        return YoinBackupExport(fileName: "yoin-backup-2026-11-24.zip", manifest: manifest, manifestJson: "{}", snapshotJson: "{}",
                                readable: [], readFailures: [])
    }

    /// A backup from an Android phone: the same Subsonic account, a Spotify account and one only in Drive.
    private static func review() async -> BackupFlowModel.Review? {
        func note(_ scope: String, _ id: String, _ provider: String, _ track: String) -> String {
            #"{"k":"song_note","kv":1,"s":"\#(scope)","id":"\#(id)","ts":1795000000000,"d":"pixel","v":{"trackId":"\#(track)","provider":"\#(provider)","content":"rain","createdAt":1795000000000,"updatedAt":1795000000000,"title":"Song","artist":"Band"}}"#
        }
        func rating(_ scope: String, _ provider: String, _ track: String) -> String {
            #"{"k":"track_rating","kv":1,"s":"\#(scope)","id":"\#(provider)\u001f\#(track)","ts":1795000000000,"d":"pixel","v":{"rating":8.0}}"#
        }
        let home = AccountFingerprints.shared.subsonic(serverUrl: "https://music.example.com", username: "kiri") ?? "s-home"
        var records = (1...6).map { note(home, "n\($0)", "subsonic", "t\($0)") } + (1...4).map { rating(home, "subsonic", "t\($0)") }
        records += (1...3).map { note("s-mei", "m\($0)", "spotify", "x\($0)") }
        records += (1...2).map { note("s-drive", "d\($0)", "subsonic", "y\($0)") }
        let snapshot = #"{"format":"yoin-sync","schema":1,"epoch":0,"deviceId":"pixel","deviceName":"Pixel 9","fileClass":"backup","shard":0,"writtenAt":1795400000000,"appVersionCode":28,"records":[\#(records.joined(separator: ","))]}"#
        let bytes = Data(snapshot.utf8)
        let sha = BackupFormat.shared.sha256Hex(bytes: SyncByteBridge.shared.toByteArray(data: bytes))
        let manifest = #"{"format":"yoin-backup","formatVersion":1,"syncSchema":1,"exportedAt":1795400000000,"source":{"location":"google-drive","deviceId":"pixel","deviceName":"Pixel 9","appVersionName":"0.6.3","appVersionCode":28},"accounts":[{"scope":"\#(home)","provider":"subsonic","displayName":"kiri","hint":"kiri @ music.example.com","onDevice":true},{"scope":"s-mei","provider":"spotify","displayName":"Mei","onDevice":true},{"scope":"s-drive","provider":"subsonic","displayName":"Old server","hint":"kiri @ old.example.com","onDevice":false}],"globalCounts":{"playlist_backup":2,"lyrics_translation":9},"snapshot":{"path":"data/snapshot.json","sha256":"\#(sha)","bytes":\#(bytes.count),"records":\#(records.count),"tombstones":0}}"#
        let facade = facade()
        let bridge = SyncByteBridge.shared
        let read = facade.readBackup(manifestJson: bridge.toByteArray(data: Data(manifest.utf8)),
                                     snapshotJson: bridge.toByteArray(data: bytes), readableFiles: 3)
        guard let backup = read.backup, let plan = try? await facade.planImport(backup: backup, nowMs: now) else { return nil }
        return BackupFlowModel.Review(fileName: "yoin-backup-2026-11-20.zip", plan: plan,
                                      choices: Dictionary(plan.accounts.map { a in
                                          (a.account.scope, a.target.kind == .skip ? Portability.targetOptions(a).first ?? a.target : a.target)
                                      }, uniquingKeysWith: { a, _ in a }),
                                      intoCloud: true)
    }

    private static func imported() -> YoinImportResult {
        func counts(_ written: Int32, _ unchanged: Int32) -> ImportCounts {
            ImportCounts(written: written, unchanged: unchanged, keptLocal: 2, keptBoth: 1, tombstones: 0, skipped: 0, failedKinds: [])
        }
        return YoinImportResult(byScope: [YoinImportScopeCounts(scope: "a", counts: counts(10, 3))], global: counts(11, 0),
                                cloudOnlyMerged: 2, cloudOnlyRefused: [], changed: true)
    }

    // MARK: Transfer

    static func transfer(_ name: String) async -> TransferFlowModel? {
        guard name.hasPrefix("xfer-") else { return nil }
        let from = TransferAccount(scope: "s-kiri", provider: "subsonic", displayName: "kiri", serverKey: "music.example.com",
                                   localProfileId: "demo-home")
        let to = TransferAccount(scope: "s-chen", provider: "spotify", displayName: "Chen", serverKey: nil, localProfileId: "demo-spotify")
        let cloud = TransferAccount(scope: "s-old", provider: "subsonic", displayName: "Old server", serverKey: "old.example.com",
                                    localProfileId: nil)
        let rows = [
            TransferFlowModel.Account(id: "local:demo-home", source: YoinTransferSource(kind: .localProfile, account: from), account: from,
                                      cloudOnly: false, fromDevice: nil, hint: nil),
            TransferFlowModel.Account(id: "local:demo-spotify", source: YoinTransferSource(kind: .localProfile, account: to), account: to,
                                      cloudOnly: false, fromDevice: nil, hint: nil),
            TransferFlowModel.Account(id: "cloud:s-old", source: YoinTransferSource(kind: .cloudAccount, account: cloud), account: cloud,
                                      cloudOnly: true, fromDevice: "Pixel 9", hint: "kiri @ old.example.com"),
        ]
        let model = TransferFlowModel()
        let plan = try? await facade().planTransfer(source: from, items: items(), target: to)
        switch name {
        case "xfer-source": model.show(step: .source, accounts: rows, source: "local:demo-home", target: nil)
        case "xfer-target": model.show(step: .target, accounts: rows, source: "local:demo-home", target: "local:demo-spotify")
        case "xfer-summary": model.show(step: .summary, accounts: rows, source: "local:demo-home", target: "local:demo-spotify", draft: plan)
        case "xfer-matching":
            model.show(step: .matching, accounts: rows, source: "local:demo-home", target: "local:demo-spotify", draft: plan, progress: (3, 7))
        case "xfer-review":
            guard let plan else { return nil }
            model.show(step: .review, accounts: rows, source: "local:demo-home", target: "local:demo-spotify", draft: plan,
                       tracks: matches(plan), albums: [])
        case "xfer-copying": model.show(step: .copying, accounts: rows, source: "local:demo-home", target: "local:demo-spotify", draft: plan)
        case "xfer-done":
            model.show(step: .done, accounts: rows, source: "local:demo-home", target: "local:demo-spotify", draft: plan, result: result())
        case "xfer-failed": model.show(step: .failed, accounts: rows, source: "local:demo-home", target: "local:demo-spotify")
        default: return nil
        }
        return model
    }

    private static func items() -> MemoryItems {
        let songs: [(String, String, String, Int32)] = [
            ("t1", "Paper Lanterns", "Kiri Ono", 214), ("t2", "Harbor Lights", "Kiri Ono", 251), ("t3", "Undertow (Live)", "Kiri Ono", 302),
            ("t4", "Low Tide Static", "The Meridians", 188), ("t5", "Glass Orchard", "The Meridians", 240),
        ]
        func ref(_ id: String) -> SourceRef { SourceRef(provider: "subsonic", id: id) }
        let notes = songs.map { SourceNote(id: "n-\($0.0)", track: ref($0.0), content: "rain outside", createdAt: now, updatedAt: now,
                                           title: $0.1, artist: $0.2, positionMs: nil) }
        let ratings = songs.prefix(3).map { SourceTrackRating(track: ref($0.0), rating: 8, updatedAt: now) }
        var facts: [SourceRef: SourceTrackFacts] = [:]
        for s in songs {
            facts[ref(s.0)] = SourceTrackFacts(title: s.1, artist: s.2, album: "Harbor Lights", albumId: "al1",
                                               durationSec: KotlinInt(int: s.3))
        }
        return MemoryItems(songNotes: notes, trackRatings: Array(ratings),
                           albumRatings: [SourceAlbumRating(album: ref("al1"), rating: 9, updatedAt: now)],
                           albumReviews: [SourceAlbumReview(album: ref("al1"), review: "The best one.", updatedAt: now, reviewUpdatedAt: nil)],
                           albumNotes: [], albumTitles: [SourceAlbumTitle(album: ref("al1"), title: "Winter, 2024", updatedAt: now)],
                           memoryTitles: [], memoryCopies: [], trackAlbumLinks: [], trackFacts: facts,
                           albumFacts: [ref("al1"): SourceAlbumFacts(name: "Harbor Lights", artist: "Kiri Ono")])
    }

    private static func matches(_ plan: YoinTransferPlan) -> [TransferTrackMatch] {
        plan.tracksToMatch.enumerated().map { i, t in
            let source = MatchTrack(id: "subsonic:\(t.source.id)", title: t.title, artist: t.artist, album: t.album,
                                    durationSec: t.durationSec, isrc: nil)
            func candidate(_ title: String?, _ suffix: String) -> CatalogTrack {
                CatalogTrack(track: MatchTrack(id: "spotify:\(t.source.id)\(suffix)", title: title, artist: t.artist, album: "Harbor Lights",
                                               durationSec: t.durationSec, isrc: nil), albumProvider: "spotify", albumId: "sp-al1")
            }
            switch i {
            case 2:
                let live = candidate(t.title, "a"), studio = candidate("Undertow", "b")
                return TransferTrackMatch(source: t.source, sourceTrack: source, status: .uncertain, candidates: [live, studio], chosen: nil)
            case 4:
                return TransferTrackMatch(source: t.source, sourceTrack: source, status: .unmatched, candidates: [], chosen: nil)
            default:
                let hit = candidate(t.title, "")
                return TransferTrackMatch(source: t.source, sourceTrack: source, status: .matched, candidates: [hit], chosen: hit)
            }
        }
    }

    private static func result() -> TransferResult {
        func c(_ notes: Int32, _ ratings: Int32) -> TransferCounts {
            TransferCounts(songNotes: notes, trackRatings: ratings, albumRatings: 0, albumReviews: 0, albumNotes: 0, albumTitles: 0,
                           memoryTitles: 0, memoryCopies: 0, trackAlbumLinks: 0)
        }
        return TransferResult(copied: c(4, 2), updated: c(0, 1), kept: c(0, 0), reviewsJoined: 0, unmatched: c(1, 0), skippedPreviously: 0)
    }
}
#endif
