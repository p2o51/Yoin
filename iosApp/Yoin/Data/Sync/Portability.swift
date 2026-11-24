import Foundation
import YoinShared

extension YoinBackup: @retroactive @unchecked Sendable {}
extension YoinBackupExport: @retroactive @unchecked Sendable {}
extension ParsedBackup: @retroactive @unchecked Sendable {}
extension YoinImportPlan: @retroactive @unchecked Sendable {}
extension YoinImportResult: @retroactive @unchecked Sendable {}
extension YoinImportChoice: @retroactive @unchecked Sendable {}
extension YoinTransferPlan: @retroactive @unchecked Sendable {}
extension YoinTransferSource: @retroactive @unchecked Sendable {}
extension TransferAccount: @retroactive @unchecked Sendable {}
extension TransferResult: @retroactive @unchecked Sendable {}
extension SourceRef: @retroactive @unchecked Sendable {}
extension MatchStatus: @retroactive @unchecked Sendable {}
extension TrackPick: @retroactive @unchecked Sendable {}
extension AlbumPick: @retroactive @unchecked Sendable {}

/// "Download all data", "Import from backup" and the zip around them (docs/cloud-sync.md「导出与导入」), the iOS
/// side of Android's `MemoriesPortability` + `BackupWriter` / `BackupReader`. Every rule lives in :shared
/// (`YoinBackup`); this writes and reads the zip and asks the stores for what only they know.
@MainActor
enum Portability {
    // MARK: Export

    struct Exported: Sendable {
        var fileName: String
        var zip: Data
        var export: YoinBackupExport
    }

    /// Everything this device would sync now, as Android's backup zip.
    static func export(now: Date = Date(), sync: CloudSync = .shared) async throws -> Exported {
        let (albums, tracks) = knownNames()
        let language = Bundle.main.preferredLocalizations.first ?? "en"
        let location = sync.backupSyncLocation
        let export = try await sync.withBackup { facade in
            try await facade.exportBackup(nowMs: Int64(now.timeIntervalSince1970 * 1000), syncLocation: location,
                                          language: language, zoneId: TimeZone.current.identifier,
                                          knownAlbums: albums, knownTracks: tracks)
        }
        return Exported(fileName: export.fileName, zip: try BackupZip.write(entries(of: export), modified: now), export: export)
    }

    /// [export]'s zip entries in Android BackupWriter's order: manifest, snapshot, then the readable files.
    nonisolated static func entries(of export: YoinBackupExport) -> [BackupZip.Entry] {
        [BackupZip.Entry(path: BackupFormat.shared.MANIFEST_PATH, data: Data(export.manifestJson.utf8)),
         BackupZip.Entry(path: BackupFormat.shared.SNAPSHOT_PATH, data: Data(export.snapshotJson.utf8))]
            + export.readable.map { BackupZip.Entry(path: $0.path, data: Data($0.text.utf8)) }
    }

    /// The song and album names this device's plays know, per account (Android DomainReadableNames), so the
    /// readable Markdown says "Rain Album" instead of an id.
    static func knownNames(profiles: [Profile] = AppModel.shared.profiles.profiles,
                           history: HistoryStore = .shared) -> ([YoinKnownAlbum], [YoinKnownTrack]) {
        var albums: [YoinKnownAlbum] = [], tracks: [YoinKnownTrack] = []
        for p in profiles {
            guard let account = MemoryLedgerSource.account(of: p) else { continue }
            let plays = history.plays(scope: MemoryLedgerSource.historyScope(account), provider: account.provider)
                .sorted { $0.startedAt > $1.startedAt }
            var seenAlbums = Set<String>(), seenTracks = Set<String>()
            for play in plays {
                if seenTracks.insert(play.songId).inserted, !play.title.isEmpty {
                    tracks.append(YoinKnownTrack(profileId: p.id, provider: play.provider, trackId: play.songId, title: play.title,
                                                 artist: play.artist.isEmpty ? nil : play.artist,
                                                 albumId: play.albumId?.isEmpty == false ? play.albumId : nil))
                }
                if let albumId = play.albumId, !albumId.isEmpty, !play.album.isEmpty, seenAlbums.insert(albumId).inserted {
                    albums.append(YoinKnownAlbum(profileId: p.id, provider: play.provider, albumId: albumId, name: play.album,
                                                 artist: play.artist.isEmpty ? nil : play.artist))
                }
            }
        }
        return (albums, tracks)
    }

    // MARK: Import

    /// Why a file couldn't be read (Android BackupReadFailure).
    enum ReadFailure: Error, Equatable, Sendable { case notABackup, tooNew, checksum, unreadable }

    /// The two entries `YoinBackup.readBackup` checks, and how many readable files came along.
    struct Unzipped: Equatable, Sendable {
        var manifest: Data?
        var snapshot: Data?
        var readableFiles: Int
    }

    /// [zip]'s backup entries (Android BackupReader): no zip → not a backup; an entry over its limit or one
    /// that can't be read → unreadable.
    nonisolated static func unzip(_ zip: Data) -> Result<Unzipped, ReadFailure> {
        do {
            var out = Unzipped(manifest: nil, snapshot: nil, readableFiles: 0)
            let format = BackupFormat.shared
            for entry in try BackupZip.list(zip) where !entry.isDirectory {
                switch entry.path {
                case format.MANIFEST_PATH: out.manifest = try BackupZip.extract(entry, from: zip, limit: Int(format.MAX_MANIFEST_BYTES))
                case format.SNAPSHOT_PATH: out.snapshot = try BackupZip.extract(entry, from: zip, limit: Int(format.MAX_SNAPSHOT_BYTES))
                default: if entry.path.hasPrefix(format.READABLE_DIR) { out.readableFiles += 1 }
                }
            }
            return .success(out)
        } catch BackupZip.Failure.notAZip {
            return .failure(.notABackup)
        } catch {
            return .failure(.unreadable)
        }
    }

    /// [zip] read and checked by :shared: the backup, or why it can't be imported.
    nonisolated static func read(_ zip: Data, with facade: YoinBackup) -> Result<ParsedBackup, ReadFailure> {
        switch unzip(zip) {
        case .failure(let failure): return .failure(failure)
        case .success(let entries):
            let bridge = SyncByteBridge.shared
            let result = facade.readBackup(manifestJson: entries.manifest.map { bridge.toByteArray(data: $0) },
                                           snapshotJson: entries.snapshot.map { bridge.toByteArray(data: $0) },
                                           readableFiles: Int32(entries.readableFiles))
            if let backup = result.backup { return .success(backup) }
            return .failure(failure(of: result.error?.kind))
        }
    }

    nonisolated static func failure(of kind: YoinBackupErrorKind?) -> ReadFailure {
        switch kind {
        case .notABackup: .notABackup
        case .tooNew: .tooNew
        case .checksumMismatch: .checksum
        default: .unreadable
        }
    }

    /// The file at [url] (from the file importer: a security-scoped URL) read and planned.
    static func plan(fileAt url: URL, now: Date = Date(), sync: CloudSync = .shared) async -> Result<YoinImportPlan, ReadFailure> {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url, options: .mappedIfSafe) else { return .failure(.unreadable) }
        do {
            return try await sync.withBackup { facade -> Result<YoinImportPlan, ReadFailure> in
                switch read(data, with: facade) {
                case .failure(let failure): return .failure(failure)
                case .success(let backup):
                    return .success(try await facade.planImport(backup: backup, nowMs: Int64(now.timeIntervalSince1970 * 1000)))
                }
            }
        } catch {
            return .failure(.unreadable)
        }
    }

    /// Applies [plan] with [choices] (scope → where it goes); publishes what it wrote when sync is on.
    static func apply(_ plan: YoinImportPlan, choices: [String: YoinImportChoice], now: Date = Date(),
                      sync: CloudSync = .shared) async throws -> YoinImportResult {
        let list = plan.accounts.map { choices[$0.account.scope] ?? $0.target }
        // Android imports the global data (settings, translations, About, playlist backups) whenever there is any.
        let result = try await sync.withBackup { facade in
            try await facade.applyImport(plan: plan, choices: list, importGlobal: true,
                                         nowMs: Int64(now.timeIntervalSince1970 * 1000))
        }
        if result.changed { sync.localDataImported() }
        return result
    }

    /// The targets an account offers (Android `ImportAccountPlan.targetOptions`): the same account here → it or
    /// skip; else each profile of its service, cloud only (when possible and not removed from the cloud), skip.
    nonisolated static func targetOptions(_ account: YoinImportAccount) -> [YoinImportChoice] {
        let scope = account.account.scope
        let skip = YoinImportChoice(scope: scope, kind: .skip, profileId: nil)
        if account.sameAccount { return [account.target, skip] }
        var out = account.options.map { YoinImportChoice(scope: scope, kind: .intoProfile, profileId: $0.localProfileId) }
        if account.cloudOnlyPossible, !account.wasPurged { out.append(YoinImportChoice(scope: scope, kind: .cloudOnly, profileId: nil)) }
        out.append(skip)
        return out
    }

    /// Something would be imported: an account not skipped, or global data (Android Review.canImport).
    nonisolated static func canImport(_ plan: YoinImportPlan, choices: [String: YoinImportChoice]) -> Bool {
        plan.accounts.contains { (choices[$0.account.scope] ?? $0.target).kind != .skip }
            || plan.globalCounts.values.reduce(0) { $0 + $1.intValue } > 0
    }
}

// MARK: - Transfer Memories

/// A song on the target service as matching sees it: the matcher's fields, and the album it is on there.
struct CatalogTrack: Hashable, Sendable {
    var track: MatchTrack
    var albumProvider: String?
    var albumId: String?

    /// `provider:rawId` → its parts.
    var provider: String { String(track.id.prefix { $0 != ":" }) }
    var rawId: String { track.id.firstIndex(of: ":").map { String(track.id[track.id.index(after: $0)...]) } ?? track.id }

    var target: TrackTarget {
        TrackTarget(provider: provider, id: rawId, title: track.title, artist: track.artist,
                    albumProvider: albumId == nil ? nil : albumProvider, albumId: albumId)
    }
}

/// An album on the target service.
struct CatalogAlbum: Hashable, Sendable {
    var provider: String
    var id: String
    var name: String
    var artist: String?

    var target: AlbumTarget { AlbumTarget(provider: provider, id: id, name: name, artist: artist) }
}

/// The target account's catalog for a Memories transfer (Android MemoryCatalog / ServiceMemoryCatalog), and the
/// source's own service when it is on this device. Failures read as "nothing found".
@MainActor
protocol MemoryCatalog: AnyObject {
    func searchTracks(_ query: String) async -> [CatalogTrack]
    func tracksByIsrc(_ isrcs: [String]) async -> [String: [CatalogTrack]]
    func searchAlbums(_ query: String) async -> [CatalogAlbum]
    /// The source song as its own service describes it (ISRC included), when the source is on this device.
    func describeSourceTrack(provider: String, trackId: String) async -> MatchTrack?
    func close()
}

extension MemoryCatalog {
    func tracksByIsrc(_ isrcs: [String]) async -> [String: [CatalogTrack]] { [:] }
    func describeSourceTrack(provider: String, trackId: String) async -> MatchTrack? { nil }
    func close() {}
}

/// Where each song and album of a transfer goes, after matching and the review.
struct TransferTrackMatch: Hashable, Sendable {
    var source: SourceRef
    /// What the source knows (title, artist, album, duration); nil when nothing.
    var sourceTrack: MatchTrack?
    var status: MatchStatus
    var candidates: [CatalogTrack]
    var chosen: CatalogTrack?
    /// From an earlier run's ledger (copied without asking again).
    var reused = false
}

struct TransferAlbumMatch: Hashable, Sendable {
    var source: SourceRef
    var status: MatchStatus
    var candidates: [CatalogAlbum]
    var chosen: CatalogAlbum?
}

/// Android MemoryTransfer's matching on iOS: every song that needs a counterpart is looked up on the target
/// (ISRC, then title + artist + duration through the shared TrackMatcher, then the title alone, then the
/// source service's ISRC), every album from the target album most of its matched songs are on, else by an
/// album search. What is copied where is :shared's (`YoinBackup.applyTransfer`).
@MainActor
enum TransferMatching {
    static var maxCandidates: Int { Int(TransferRules.shared.MAX_CANDIDATES) }

    static func match(_ plan: YoinTransferPlan, catalog: MemoryCatalog?,
                      onProgress: (Int, Int) -> Void = { _, _ in }) async -> (tracks: [TransferTrackMatch], albums: [TransferAlbumMatch]) {
        let total = plan.tracksToMatch.count + plan.albumsToMatch.count
        var done = 0
        var tracks: [TransferTrackMatch] = []
        let matcher = TrackMatcher.shared
        var searches: [String: [CatalogTrack]] = [:]
        func search(_ query: String) async -> [CatalogTrack] {
            if let hit = searches[query] { return hit }
            let found = await catalog?.searchTracks(query) ?? []
            searches[query] = found
            return found
        }
        func decide(_ source: MatchTrack, among found: [CatalogTrack], byIsrc: Bool) -> (any MatchDecision, [String: CatalogTrack]) {
            let byId = Dictionary(found.map { ($0.track.id, $0) }, uniquingKeysWith: { a, _ in a })
            let tracks = found.map(\.track)
            return (byIsrc ? matcher.chooseByIsrc(source: source, candidates: tracks) : matcher.choose(source: source, candidates: tracks), byId)
        }
        for wanted in plan.tracksToMatch {
            let ref = wanted.source
            var source: MatchTrack? = wanted.title.map {
                MatchTrack(id: "\(ref.provider):\(ref.id)", title: $0, artist: wanted.artist, album: wanted.album,
                           durationSec: wanted.durationSec, isrc: nil)
            }
            if source == nil, let catalog { source = await catalog.describeSourceTrack(provider: ref.provider, trackId: ref.id) }
            guard let catalog, let source, let title = source.title, !title.trimmingCharacters(in: .whitespaces).isEmpty else {
                tracks.append(TransferTrackMatch(source: ref, sourceTrack: source, status: .unmatched, candidates: [], chosen: nil))
                done += 1; onProgress(done, total)
                continue
            }
            var decision: any MatchDecision = MatchDecisionNotFound.shared
            var known: [String: CatalogTrack] = [:]
            if let isrc = source.isrc, let found = await catalog.tracksByIsrc([isrc])[isrc] {
                let (d, ids) = decide(source, among: found, byIsrc: true)
                if d is MatchDecisionMatched { decision = d; known = ids }
            }
            if !(decision is MatchDecisionMatched) {
                let query = matcher.searchQuery(track: source)
                if let query { (decision, known) = decide(source, among: await search(query), byIsrc: false) }
                if decision is MatchDecisionNotFound, let titleOnly = matcher.titleQuery(track: source), titleOnly != query {
                    (decision, known) = decide(source, among: await search(titleOnly), byIsrc: false)
                }
            }
            // Searching didn't settle it and nothing named an ISRC: ask the source service for one, only now.
            if !(decision is MatchDecisionMatched), source.isrc == nil,
               let isrc = await catalog.describeSourceTrack(provider: ref.provider, trackId: ref.id)?.isrc,
               let found = await catalog.tracksByIsrc([isrc])[isrc] {
                let (d, ids) = decide(source, among: found, byIsrc: true)
                if d is MatchDecisionMatched { decision = d; known = ids }
            }
            switch decision {
            case let m as MatchDecisionMatched:
                let chosen = known[m.track.id] ?? CatalogTrack(track: m.track)
                tracks.append(TransferTrackMatch(source: ref, sourceTrack: source, status: .matched, candidates: [chosen], chosen: chosen))
            case let a as MatchDecisionAmbiguous where !a.candidates.isEmpty:
                let candidates = a.candidates.prefix(maxCandidates).map { known[$0.id] ?? CatalogTrack(track: $0) }
                tracks.append(TransferTrackMatch(source: ref, sourceTrack: source, status: .uncertain, candidates: Array(candidates), chosen: nil))
            default:
                tracks.append(TransferTrackMatch(source: ref, sourceTrack: source, status: .unmatched, candidates: [], chosen: nil))
            }
            done += 1; onProgress(done, total)
        }
        var albums: [TransferAlbumMatch] = []
        for wanted in plan.albumsToMatch {
            albums.append(await matchAlbum(wanted, tracks: tracks, wanted: plan.tracksToMatch, catalog: catalog))
            done += 1; onProgress(done, total)
        }
        return (tracks + reusedTracks(plan), albums)
    }

    /// The songs an earlier run matched or picked (the ledger), as review rows that are already checked.
    static func reusedTracks(_ plan: YoinTransferPlan) -> [TransferTrackMatch] {
        plan.reusedTracks.compactMap { pick in
            guard let to = pick.chosen else { return nil }
            let facts = plan.items.trackFacts[pick.source]
            let source = facts.map {
                MatchTrack(id: "\(pick.source.provider):\(pick.source.id)", title: $0.title, artist: $0.artist, album: $0.album,
                           durationSec: $0.durationSec, isrc: nil)
            }
            let chosen = CatalogTrack(track: MatchTrack(id: "\(to.provider):\(to.id)", title: facts?.title, artist: facts?.artist,
                                                        album: nil, durationSec: facts?.durationSec, isrc: nil))
            return TransferTrackMatch(source: pick.source, sourceTrack: source, status: pick.status, candidates: [chosen],
                                      chosen: chosen, reused: true)
        }
    }

    /// Android MemoryTransfer.matchAlbum: the target album most of its matched songs are on, else an album
    /// search on name + artist scored like a song title and credit.
    static func matchAlbum(_ wanted: YoinAlbumToMatch, tracks: [TransferTrackMatch], wanted songs: [YoinTrackToMatch],
                           catalog: MemoryCatalog?) async -> TransferAlbumMatch {
        let ref = wanted.source
        let sourceAlbumOf = Dictionary(songs.map { ($0.source, $0.albumId) }, uniquingKeysWith: { a, _ in a })
        // 1. Votes: where its matched songs are on the target.
        var votes: [(album: CatalogAlbum, count: Int)] = []
        for match in tracks where match.status == .matched || match.status == .picked {
            guard let chosen = match.chosen, let albumId = chosen.albumId,
                  match.source.provider == ref.provider, sourceAlbumOf[match.source] == ref.id else { continue }
            let album = CatalogAlbum(provider: chosen.albumProvider ?? chosen.provider, id: albumId,
                                     name: chosen.track.album ?? "", artist: chosen.track.artist)
            if let i = votes.firstIndex(where: { $0.album.provider == album.provider && $0.album.id == album.id }) {
                votes[i].count += 1
            } else {
                votes.append((album, 1))
            }
        }
        if let top = votes.map(\.count).max() {
            let leaders = votes.filter { $0.count == top }.map(\.album)
            let candidates = Array(leaders.prefix(maxCandidates))
            return leaders.count == 1
                ? TransferAlbumMatch(source: ref, status: .matched, candidates: candidates, chosen: candidates[0])
                : TransferAlbumMatch(source: ref, status: .uncertain, candidates: candidates, chosen: nil)
        }
        // 2. Album search.
        guard let catalog, let name = wanted.name, !name.trimmingCharacters(in: .whitespaces).isEmpty else {
            return TransferAlbumMatch(source: ref, status: .unmatched, candidates: [], chosen: nil)
        }
        let matcher = TrackMatcher.shared
        let scored = await catalog.searchAlbums(matcher.albumQuery(name: name, artist: wanted.artist))
            .map { ($0, albumScore(name, wanted.artist, $0)) }
            .filter { $0.1 > 0 }
            .sorted { $0.1 > $1.1 }
        guard let best = scored.first else { return TransferAlbumMatch(source: ref, status: .unmatched, candidates: [], chosen: nil) }
        if best.1 >= TrackMatcher.shared.MATCH_SCORE, matcher.sameVersion(a: name, b: best.0.name) {
            return TransferAlbumMatch(source: ref, status: .matched, candidates: [best.0], chosen: best.0)
        }
        if best.1 >= TrackMatcher.shared.AMBIGUOUS_SCORE {
            let close = scored.prefix { $0.1 >= TrackMatcher.shared.AMBIGUOUS_SCORE }.map(\.0).prefix(maxCandidates)
            return TransferAlbumMatch(source: ref, status: .uncertain, candidates: Array(close), chosen: nil)
        }
        return TransferAlbumMatch(source: ref, status: .unmatched, candidates: [], chosen: nil)
    }

    /// Android albumScore: title 0.6, credit 0.4 (0.6 when either side names no artist); floors 0.6 / 0.5.
    static func albumScore(_ name: String, _ artist: String?, _ candidate: CatalogAlbum) -> Double {
        let matcher = TrackMatcher.shared
        let title = matcher.titleSimilarity(a: name, b: candidate.name)
        if title < 0.6 { return 0 }
        let credit: Double
        if let artist, !artist.isEmpty, let other = candidate.artist, !other.isEmpty {
            credit = matcher.artistSimilarity(a: artist, b: other)
        } else {
            credit = 0.6
        }
        if credit < 0.5 { return 0 }
        return 0.6 * title + 0.4 * credit
    }

    // MARK: Review → picks

    /// The review's rows (Android reviewRowsOf): matched checked, unsure unchecked with its candidates,
    /// not found empty. Positions index into [tracks].
    static func reviewRows(_ tracks: [TransferTrackMatch]) -> [ReviewRow] {
        tracks.enumerated().map { i, match in
            let candidates = match.candidates.map(\.track)
            switch match.status {
            case .matched, .picked, .direct:
                let shown = match.chosen.map { [$0.track] } ?? Array(candidates.prefix(1))
                return ReviewRow(position: Int32(i), status: match.status == .picked ? .picked : .matched, candidates: shown,
                                 chosen: 0, checked: match.chosen != nil || !candidates.isEmpty)
            case .uncertain:
                return ReviewRow(position: Int32(i), status: .uncertain, candidates: candidates, chosen: 0, checked: false)
            default:
                return ReviewRow(position: Int32(i), status: .notfound, candidates: [], chosen: 0, checked: false)
            }
        }
    }

    /// The picks `YoinBackup.applyTransfer` takes, with the review's decisions (Android applyReview + execute):
    /// a checked row takes its chosen candidate (Picked when that isn't what matching settled on), an unchecked
    /// one copies nothing and is retried next time.
    static func trackPicks(_ tracks: [TransferTrackMatch], rows: [ReviewRow]) -> [TrackPick] {
        let byPosition = Dictionary(rows.map { (Int($0.position), $0) }, uniquingKeysWith: { a, _ in a })
        return tracks.enumerated().map { i, match in
            let settled = match.status == .matched || match.status == .picked
            guard let row = byPosition[i] else {
                return TrackPick(source: match.source, status: match.status, chosen: settled ? match.chosen?.target : nil)
            }
            if row.checked, let choice = row.choice {
                if settled, let chosen = match.chosen, chosen.track.id == choice.id {
                    return TrackPick(source: match.source, status: match.status, chosen: chosen.target)
                }
                let picked = match.candidates.first { $0.track.id == choice.id } ?? CatalogTrack(track: choice)
                return TrackPick(source: match.source, status: .picked, chosen: picked.target)
            }
            let status: MatchStatus = match.chosen != nil ? (match.candidates.isEmpty ? .unmatched : .uncertain) : match.status
            return TrackPick(source: match.source, status: status == .matched || status == .picked ? .uncertain : status, chosen: nil)
        }
    }

    /// The albums' picks: matched ones with their album, the rest retried next time; earlier runs' as they were.
    static func albumPicks(_ albums: [TransferAlbumMatch], plan: YoinTransferPlan) -> [AlbumPick] {
        albums.map { AlbumPick(source: $0.source, status: $0.status, chosen: $0.chosen?.target) } + plan.reusedAlbums
    }

}

extension CatalogTrack {
    init(track: MatchTrack) { self.init(track: track, albumProvider: nil, albumId: nil) }
}
