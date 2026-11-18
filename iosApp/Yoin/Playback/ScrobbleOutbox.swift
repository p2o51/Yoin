import Foundation
import Network

/// One play the server hasn't heard yet (Android PendingScrobble): the account it was heard on, the
/// song, and when it started (epoch ms).
struct PendingScrobble: Codable, Hashable, Sendable {
    var scope: String
    var songId: String
    var timeMs: Int64
}

/// Subsonic plays waiting to be submitted (Android PendingScrobbles + FilePendingScrobbleStore): a
/// play is written here before it is sent and leaves only once the server took it (or refused it for
/// good), so a failed or offline submission is sent again later with its own time, to its own account.
@MainActor
final class ScrobbleOutbox {
    static let shared = ScrobbleOutbox(fileURL: ScrobbleOutbox.defaultURL)

    /// Months of offline listening; past this the oldest plays go first.
    nonisolated static let maxPending = 2_000

    private let fileURL: URL?
    private(set) var queue: [PendingScrobble]
    /// The network came back (set by the store that sends).
    var onReachable: (() -> Void)?
    private var monitor: NWPathMonitor?

    init(fileURL: URL?) {
        self.fileURL = fileURL
        queue = fileURL.flatMap { try? Data(contentsOf: $0) }
            .flatMap { try? JSONDecoder().decode([PendingScrobble].self, from: $0) } ?? []
    }

    static var defaultURL: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("Yoin", isDirectory: true).appendingPathComponent("pending-scrobbles.json")
    }

    func add(_ play: PendingScrobble) {
        guard !queue.contains(play) else { return }
        queue = Array((queue + [play]).suffix(Self.maxPending))
        persist()
    }

    /// The oldest play for [scope]: a play is only ever sent to the account it was heard on.
    func next(for scope: String) -> PendingScrobble? { queue.first { $0.scope == scope } }

    func remove(_ play: PendingScrobble) {
        guard let i = queue.firstIndex(of: play) else { return }
        queue.remove(at: i)
        persist()
    }

    /// Calls [onReachable] each time the network becomes usable.
    func watchNetwork() {
        guard monitor == nil else { return }
        let m = NWPathMonitor()
        m.pathUpdateHandler = { [weak self] path in
            guard path.status == .satisfied else { return }
            Task { @MainActor in self?.onReachable?() }
        }
        m.start(queue: DispatchQueue(label: "com.gpo.yoin.scrobble-network"))
        monitor = m
    }

    private func persist() {
        guard let fileURL else { return }
        do {
            try FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            if queue.isEmpty {
                try? FileManager.default.removeItem(at: fileURL)
            } else {
                try JSONEncoder().encode(queue).write(to: fileURL, options: .atomic)
            }
        } catch {
            // Losing the queue beats crashing; the plays in memory still go out this run.
        }
    }
}
