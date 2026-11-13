import Foundation

/// Subsonic ratings the server hasn't taken yet (Android `local_ratings.needsSync`): a song is marked
/// here when it is rated, before its push, and leaves only once the server took its newest value, so a
/// rating given offline or while the server was down goes out again later, to the account it was given
/// under. Kept apart from the rating rows (`LocalRatings`), which cloud sync carries: "not on this
/// account's server yet" is this device's business, not another device's.
///
/// Only the song is kept, never a value: what goes out is read from `LocalRatings` right before each
/// send, so it is always the newest rating (Android `syncPendingRatings` reads each row again).
@MainActor
final class RatingOutbox {
    static let shared = RatingOutbox(fileURL: RatingOutbox.defaultURL)

    /// One song whose rating its account's server is still owed.
    struct Entry: Codable, Hashable, Sendable {
        /// `AccountScope` of the Subsonic account the rating was given under.
        var scope: String
        var songId: String
    }

    /// What a push sends next, read right before each send.
    enum Next {
        /// This many stars (0 removes the server's rating).
        case send(Int32)
        /// Nothing to send any more (the row is gone): the entry leaves.
        case drop
        /// Not now (another account is in use): the entry stays for its own account.
        case hold
    }

    enum Outcome: Equatable {
        /// The server has the newest value, or there was nothing to send.
        case sent
        /// A push of this song was already out; it sends this newest value too.
        case handedOff
        /// The send failed or was held: the entry stays.
        case kept
    }

    private let fileURL: URL?
    private(set) var pending: [Entry]
    /// Songs whose push is out, and those rated again meanwhile: one push per song at a time, and the
    /// newest value always last, so a quick 2★ then 5★ can't leave 2★ on the server (Codex review #19).
    private var sending: Set<Entry> = []
    private var ratedAgain: Set<Entry> = []

    init(fileURL: URL?) {
        self.fileURL = fileURL
        pending = fileURL.flatMap { try? Data(contentsOf: $0) }
            .flatMap { try? JSONDecoder().decode([Entry].self, from: $0) } ?? []
    }

    static var defaultURL: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("Yoin", isDirectory: true).appendingPathComponent("pending-ratings.json")
    }

    /// The song was rated: its server is owed the value until a push lands.
    func mark(_ entry: Entry) {
        if sending.contains(entry) { ratedAgain.insert(entry) }
        guard !pending.contains(entry) else { return }
        pending.append(entry)
        persist()
    }

    /// The account's songs still owed, oldest first, without those whose push is out.
    func waiting(for scope: String) -> [Entry] {
        pending.filter { $0.scope == scope && !sending.contains($0) }
    }

    func isPending(_ entry: Entry) -> Bool { pending.contains(entry) }

    /// Sends [entry]'s newest value until the server has it. A rating given while the send was out is
    /// sent after it; a failure keeps the entry for the next try (the account again, or the network).
    func push(_ entry: Entry, next: () -> Next, send: (Int32) async throws -> Void) async -> Outcome {
        guard sending.insert(entry).inserted else {
            ratedAgain.insert(entry)
            return .handedOff
        }
        defer { sending.remove(entry) }
        while true {
            ratedAgain.remove(entry)
            switch next() {
            case .hold:
                return .kept
            case .drop:
                remove(entry)
                return .sent
            case .send(let stars):
                do {
                    try await send(stars)
                } catch {
                    return .kept
                }
                guard ratedAgain.contains(entry) else {
                    remove(entry)
                    return .sent
                }
            }
        }
    }

    /// What a Subsonic push of [entry] sends next: the account's newest rating as 0–5 stars (a cleared
    /// rating sends 0, which removes the server's), only while that account is in use ([activeScope]).
    static func next(_ entry: Entry, ratings: LocalRatings, activeScope: String?) -> Next {
        guard entry.scope == activeScope else { return .hold }
        guard let row = ratings.row(scope: entry.scope, provider: AccountScope.subsonicProvider, songId: entry.songId)
        else { return .drop }
        return .send(RatingScale.serverRating(local: row.rating ?? 0))
    }

    private func remove(_ entry: Entry) {
        guard let i = pending.firstIndex(of: entry) else { return }
        pending.remove(at: i)
        persist()
    }

    private func persist() {
        guard let fileURL else { return }
        do {
            try FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            if pending.isEmpty {
                try? FileManager.default.removeItem(at: fileURL)
            } else {
                try JSONEncoder().encode(pending).write(to: fileURL, options: .atomic)
            }
        } catch {
            // Losing the list beats crashing; the songs in memory still go out this run.
        }
    }
}
