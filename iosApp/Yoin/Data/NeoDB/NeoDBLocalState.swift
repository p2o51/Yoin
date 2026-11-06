import Foundation
import Observation

/// What NeoDB still needs to hear about each album: Android's `album_ratings.ratingNeedsSync /
/// reviewNeedsSync / neoDbReviewUuid`, kept beside AlbumMemoryStore (which holds the rating and the
/// words) rather than in it, so cloud sync's album rows never carry or overwrite them (Android's sync
/// adapters never touch these columns either).
///
/// The flags belong to the device that made the edit (Android NeoDbRestoreGuard): the file is excluded
/// from iCloud / device backups, so a restored phone starts with none and never pushes the old phone's
/// edits over anything newer on NeoDB. Rows are scoped per account like AlbumMemoryStore's.
@MainActor
@Observable
final class NeoDBPendingStore {
    static let shared = NeoDBPendingStore(fileURL: NeoDBPendingStore.defaultURL)

    struct Entry: Codable, Hashable, Sendable {
        var scope: String?
        var provider: String
        var albumId: String
        var ratingNeedsSync = false
        var reviewNeedsSync = false
        /// The NeoDB review's uuid once known (the current item-centred API rarely returns one).
        var reviewUuid: String?

        var isDirty: Bool { ratingNeedsSync || reviewNeedsSync }
    }

    private var all: [String: Entry] = [:]
    @ObservationIgnored let fileURL: URL
    @ObservationIgnored private let scopes: AccountScope

    static var defaultURL: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("Yoin", isDirectory: true).appendingPathComponent("neodb-pending.json")
    }

    init(fileURL: URL, scopes: AccountScope = .shared) {
        self.fileURL = fileURL
        self.scopes = scopes
        if let data = try? Data(contentsOf: fileURL), let rows = try? JSONDecoder().decode([Entry].self, from: data) {
            all = Dictionary(rows.map { (Self.key($0.scope, $0.provider, $0.albumId), $0) }, uniquingKeysWith: { $1 })
        }
    }

    private static func key(_ scope: String?, _ provider: String, _ albumId: String) -> String {
        [scope ?? "", provider, albumId].joined(separator: "\u{1F}")
    }

    private func currentKey(_ provider: String, _ albumId: String) -> String {
        Self.key(scopes.scope(for: provider), provider, albumId)
    }

    func entry(provider: String, albumId: String) -> Entry? { all[currentKey(provider, albumId)] }

    /// Android `setAlbumRating`: the score changed here; NeoDB hears it on the next push.
    func markRatingChanged(provider: String, albumId: String) {
        update(provider, albumId) { $0.ratingNeedsSync = true }
    }

    /// Android `setAlbumReview`: the words changed here.
    func markReviewChanged(provider: String, albumId: String) {
        update(provider, albumId) { $0.reviewNeedsSync = true }
    }

    /// A push landed the rating: the flag clears (the caller checked the album still holds what was sent).
    func ratingPushed(provider: String, albumId: String) {
        update(provider, albumId) { $0.ratingNeedsSync = false }
    }

    /// A push landed the words: the flag clears and the review's uuid is kept.
    func reviewPushed(provider: String, albumId: String, reviewUuid: String?) {
        update(provider, albumId) {
            $0.reviewNeedsSync = false
            $0.reviewUuid = reviewUuid
        }
    }

    /// A pull learned the review's uuid; the flags stay as they are.
    func setReviewUuid(_ uuid: String?, provider: String, albumId: String) {
        guard entry(provider: provider, albumId: albumId)?.reviewUuid != uuid else { return }
        update(provider, albumId) { $0.reviewUuid = uuid }
    }

    private func update(_ provider: String, _ albumId: String, _ change: (inout Entry) -> Void) {
        let k = currentKey(provider, albumId)
        var e = all[k] ?? Entry(scope: scopes.scope(for: provider), provider: provider, albumId: albumId)
        change(&e)
        var next = all
        // A clean row without a uuid says nothing: it goes.
        next[k] = e.isDirty || e.reviewUuid != nil ? e : nil
        all = next
        write()
    }

    /// Kept in memory even when the disk fails (a dirty flag lost on a crash only means one push fewer).
    private func write() {
        do {
            try FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            let rows = all.values.sorted { Self.key($0.scope, $0.provider, $0.albumId) < Self.key($1.scope, $1.provider, $1.albumId) }
            try JSONEncoder().encode(rows).write(to: fileURL, options: .atomic)
            var url = fileURL
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try url.setResourceValues(values)
        } catch {}
    }
}

/// Yoin album ↔ NeoDB item uuid (Android `external_mappings`, service "neodb", entity "album"): several
/// Yoin albums (a Subsonic and an Apple Music copy) may point at one NeoDB item; each Yoin album has at
/// most one uuid. Backed up, as Android's table is. Rows are scoped per account like AlbumMemoryStore's
/// (Codex, 2026-11-06): two Subsonic servers can both have an album "1". Rows written before that have
/// no scope; the first account to read its provider's rows claims them (AccountScope.adoptLegacy's rule).
@MainActor
final class NeoDBMappingStore {
    static let shared = NeoDBMappingStore(fileURL: NeoDBMappingStore.defaultURL)

    struct Mapping: Codable, Hashable, Sendable {
        var externalId: String
        var provider: String
        var entityType: String
        var entityId: String
        var syncedAt: Int64
        /// The account; nil for providers without one (Apple Music) and for rows from before scoping.
        var scope: String?
    }

    static let entityAlbum = "album"

    private var rows: [String: Mapping] = [:]
    private let fileURL: URL
    private let scopes: AccountScope

    static var defaultURL: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("Yoin", isDirectory: true).appendingPathComponent("neodb-mappings.json")
    }

    init(fileURL: URL, scopes: AccountScope = .shared) {
        self.fileURL = fileURL
        self.scopes = scopes
        if let data = try? Data(contentsOf: fileURL), let saved = try? JSONDecoder().decode([Mapping].self, from: data) {
            rows = Dictionary(saved.map { (Self.key($0.scope, $0.provider, $0.entityType, $0.entityId), $0) },
                              uniquingKeysWith: { $1 })
        }
    }

    private static func key(_ scope: String?, _ provider: String, _ type: String, _ id: String) -> String {
        [scope ?? "", provider, type, id].joined(separator: "\u{1F}")
    }

    /// The account [provider]'s rows are read and written for now.
    func scope(for provider: String) -> String? { scopes.scope(for: provider) }

    func externalId(provider: String, entityType: String = entityAlbum, entityId: String,
                    scope: String?? = nil) -> String? {
        let scope = scope ?? self.scope(for: provider)
        claimLegacy(provider: provider, scope: scope)
        return rows[Self.key(scope, provider, entityType, entityId)]?.externalId
    }

    func upsert(externalId: String, provider: String, entityType: String = entityAlbum, entityId: String, now: Int64,
                scope: String?? = nil) {
        let scope = scope ?? self.scope(for: provider)
        claimLegacy(provider: provider, scope: scope)
        rows[Self.key(scope, provider, entityType, entityId)] = Mapping(
            externalId: externalId, provider: provider, entityType: entityType, entityId: entityId,
            syncedAt: now, scope: scope)
        write()
    }

    /// Unscoped rows of a scoped provider go to the first account that reads that provider's rows.
    private func claimLegacy(provider: String, scope: String?) {
        guard let scope else { return }
        let legacy = rows.filter { $0.value.provider == provider && $0.value.scope == nil }
        guard !legacy.isEmpty else { return }
        for (k, var m) in legacy {
            rows[k] = nil
            m.scope = scope
            let scoped = Self.key(scope, m.provider, m.entityType, m.entityId)
            if rows[scoped] == nil { rows[scoped] = m }
        }
        write()
    }

    private func write() {
        do {
            try FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            let sorted = rows.values.sorted {
                Self.key($0.scope, $0.provider, $0.entityType, $0.entityId) < Self.key($1.scope, $1.provider, $1.entityType, $1.entityId)
            }
            try JSONEncoder().encode(sorted).write(to: fileURL, options: .atomic)
        } catch {}
    }
}
