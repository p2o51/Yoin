import Foundation
import YoinShared

extension BackupTrackAlbumLink: @retroactive @unchecked Sendable {}

/// Which album a song is on, per account (Android `track_album_links`, docs/cloud-sync.md「导出与导入」): the
/// links a backup carries (`track_album_link` records) and a Memories transfer writes for the songs it
/// matched. Android also learns them from plays, album pages and lookups; iOS keeps only what imports and
/// transfers bring for now, so a backup made here hands them on instead of dropping them.
///
/// Not synced, on Android either: a link never enters the sync replica or the cloud, only backups.
/// One JSON file in Application Support (`Yoin/track-album-links.json`), keyed by (profile, provider, track),
/// Android's primary key. `YoinBackup` reads and writes it through `YoinTrackAlbumLinkStore`; a write returns
/// true only once it is on disk.
final class TrackAlbumLinkStore: NSObject, YoinTrackAlbumLinkStore, @unchecked Sendable {
    static let shared = TrackAlbumLinkStore(fileURL: TrackAlbumLinkStore.defaultURL)

    struct Link: Codable, Hashable, Sendable {
        var profileId: String
        var provider: String
        var trackId: String
        var albumId: String
        /// `play`, `detail` or `import` (BackupTrackAlbumLink.SOURCE_*).
        var source: String
        var updatedAt: Int64
    }

    private struct File: Codable { var links: [Link] = [] }

    static var defaultURL: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("Yoin", isDirectory: true).appendingPathComponent("track-album-links.json")
    }

    /// nil keeps everything in memory (tests).
    private let fileURL: URL?
    private let lock = NSLock()
    private var all: [String: Link] = [:]

    init(fileURL: URL?) {
        self.fileURL = fileURL
        super.init()
        if let fileURL, let data = try? Data(contentsOf: fileURL),
           let saved = try? JSONDecoder().decode(File.self, from: data) {
            all = Dictionary(saved.links.map { (Self.key($0.profileId, $0.provider, $0.trackId), $0) }, uniquingKeysWith: { $1 })
        }
    }

    static func key(_ profileId: String, _ provider: String, _ trackId: String) -> String {
        [profileId, provider, trackId].joined(separator: "\u{1F}")
    }

    // MARK: Swift

    func all(profileId: String) -> [Link] {
        lock.withLock { all.values.filter { $0.profileId == profileId } }
            .sorted { ($0.provider, $0.trackId) < ($1.provider, $1.trackId) }
    }

    func get(profileId: String, provider: String, trackId: String) -> Link? {
        lock.withLock { all[Self.key(profileId, provider, trackId)] }
    }

    /// Saves [link] (replacing the one for its song); false, and nothing changed, when it can't be written.
    @discardableResult
    func put(_ link: Link) -> Bool {
        lock.withLock {
            var next = all
            next[Self.key(link.profileId, link.provider, link.trackId)] = link
            guard save(next) else { return false }
            all = next
            return true
        }
    }

    /// Drops [profileId]'s links (its account was removed).
    func removeAll(profileId: String) {
        lock.withLock {
            let next = all.filter { $0.value.profileId != profileId }
            guard next.count != all.count, save(next) else { return }
            all = next
        }
    }

    private func save(_ links: [String: Link]) -> Bool {
        guard let fileURL else { return true }
        do {
            try FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            let sorted = links.values.sorted { ($0.profileId, $0.provider, $0.trackId) < ($1.profileId, $1.provider, $1.trackId) }
            try JSONEncoder().encode(File(links: sorted)).write(to: fileURL, options: .atomic)
            return true
        } catch {
            return false
        }
    }

    // MARK: YoinTrackAlbumLinkStore (the shared backup / transfer core)

    func links(profileId: String) -> [BackupTrackAlbumLink] {
        all(profileId: profileId).map(Self.backup)
    }

    func link(profileId: String, provider: String, trackId: String) -> BackupTrackAlbumLink? {
        get(profileId: profileId, provider: provider, trackId: trackId).map(Self.backup)
    }

    func putLink(profileId: String, link: BackupTrackAlbumLink) -> Bool {
        put(Link(profileId: profileId, provider: link.provider, trackId: link.trackId, albumId: link.albumId,
                 source: link.source, updatedAt: link.updatedAt))
    }

    private static func backup(_ l: Link) -> BackupTrackAlbumLink {
        BackupTrackAlbumLink(provider: l.provider, trackId: l.trackId, albumId: l.albumId, source: l.source, updatedAt: l.updatedAt)
    }
}
