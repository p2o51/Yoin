import Foundation

/*
 * A song note or score whose song Yoin never played, nor opened its album, has nothing local to name its album
 * (Android ui/memories/ledger/LedgerTrackResolver.kt, #202, owner 2026-11-12: a row of "Song score 7.9, Album
 * unknown" with a blank cover). Scores pulled from a Subsonic server, or synced from another device, are like
 * that. The ledger asks the song's own server once (any Subsonic account, not only the one in use), a few songs
 * at a time. Answers live for the process; a song the server doesn't know is not asked again. An account whose
 * lookup fails (offline, signed out) is left alone until the ledger next asks.
 *
 * Streaming services are not asked here (Android asks the account in use, one song at a time on a budget):
 * their songs keep whatever the device knows.
 */
@MainActor
final class LedgerTrackResolver {
    static let shared = LedgerTrackResolver()

    /// What a lookup needs: [key] names the song in its own account.
    struct Wanted: Hashable, Sendable {
        var account: LedgerAccount
        var songId: String
        var key: String { Self.key(account, songId) }
        static func key(_ account: LedgerAccount, _ songId: String) -> String { "\(account.key)|\(songId)" }
    }

    /// What a pass brought: [answered] songs have an answer now, and [found] says one named an album or a song.
    struct Pass: Equatable, Sendable {
        var answered: Int
        var found: Bool
    }

    /// Lookups in flight on Subsonic servers.
    static let parallel = 4
    /// The server doesn't know the song.
    static let unknown = LedgerTrack(title: nil, albumId: nil, albumName: nil, artist: nil, coverKey: nil)

    /// Every song asked about while the process lives (`unknown` when the server doesn't know it).
    private(set) var answers: [String: LedgerTrack] = [:]
    /// Songs being asked about now: a second pass (the page left and opened again) doesn't ask them twice.
    private(set) var inFlight: Set<String> = []

    /// What a new pass mustn't ask: answered, or being asked.
    var asked: Set<String> { Set(answers.keys).union(inFlight) }

    /// The answers that name something, for the ledger's lookup (a copy: the lookup runs off the main actor).
    var known: [String: LedgerTrack] { answers.filter { $0.value != Self.unknown } }

    /// The songs in [entries] whose album isn't known, on a Subsonic account, not yet asked about (newest first).
    nonisolated static func pending(_ entries: [LedgerEntry], asked: Set<String>) -> [Wanted] {
        var seen = Set<String>()
        return entries.compactMap { e -> Wanted? in
            guard e.album == nil, let id = e.songId, e.kind == .trackRating || e.kind == .songNote,
                  e.account.provider == "subsonic" else { return nil }  // AccountScope.subsonicProvider
            let w = Wanted(account: e.account, songId: id)
            return !asked.contains(w.key) && seen.insert(w.key).inserted ? w : nil
        }
    }

    /// Asks for [wanted], [parallel] at a time. [ask] returns the song, nil when the server doesn't know it, or
    /// throws when the account can't answer now (its other songs are then skipped this pass).
    func resolve(_ wanted: [Wanted], ask: @escaping @MainActor @Sendable (Wanted) async throws -> LedgerTrack?) async -> Pass {
        var failed = Set<LedgerAccount>()
        var answered = 0, found = false
        var queue = wanted[...]
        // The page gone (its task cancelled): no further batch, and the lookups in flight are cancelled too.
        while !queue.isEmpty, !Task.isCancelled {
            let batch = queue.prefix(Self.parallel).filter { !failed.contains($0.account) && !inFlight.contains($0.key) }
            queue = queue.dropFirst(min(Self.parallel, queue.count))
            for w in batch { inFlight.insert(w.key) }
            // Each lookup its own task, so a batch's songs are asked at once.
            let tasks = batch.map { w in
                Task { @MainActor () -> (Wanted, LedgerTrack??) in
                    do { return (w, .some(try await ask(w))) } catch { return (w, nil) }
                }
            }
            let results = await withTaskCancellationHandler {
                var results: [(Wanted, LedgerTrack??)] = []
                for t in tasks { results.append(await t.value) }
                return results
            } onCancel: {
                for t in tasks { t.cancel() }
            }
            for w in batch { inFlight.remove(w.key) }
            // A cancelled lookup isn't the account failing: left unanswered for the next pass.
            if Task.isCancelled { break }
            for (w, result) in results {
                guard let answer = result else { failed.insert(w.account); continue }
                let track = answer ?? Self.unknown
                answers[w.key] = track
                answered += 1
                if track.isNews { found = true }
            }
        }
        return Pass(answered: answered, found: found)
    }

    #if DEBUG
    func forgetAll() { answers = [:]; inFlight = [] }
    #endif
}
