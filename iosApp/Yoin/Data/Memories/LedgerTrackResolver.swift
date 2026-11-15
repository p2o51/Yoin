import Foundation

/*
 * A song note or score whose song Yoin never played, nor opened its album, has nothing local to name its album
 * (Android ui/memories/ledger/LedgerTrackResolver.kt, #202, owner 2026-11-12: a row of "Song score 7.9, Album
 * unknown" with a blank cover). Scores pulled from a Subsonic server, or synced from another device, are like
 * that. The ledger asks the song's own server once (any Subsonic account, not only the one in use), a few songs
 * at a time. Answers live for the process; a song the server doesn't know is not asked again. An account whose
 * lookup fails (offline, signed out) is left alone until the ledger next asks.
 *
 * The streaming account in use is asked too (Android: the account in use for the others), as sparingly as
 * Android asks it: a streaming service counts every request against the account (Spotify: one `GET /tracks/{id}`
 * a song), so one song at a time with a breath between, the newest [servicePerPass] a pass, and no more than
 * [serviceBudget] songs an account while the process lives (a big import must not cost hundreds of requests
 * every launch, and a 429 would close the account's Web API to Home, search and playback alike). One such
 * lane at a time, whoever asks (Home's grid and the page read one ledger).
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
    /// A streaming account's songs asked in one pass, newest first.
    static let servicePerPass = 20
    /// Songs a streaming account is asked about while the process lives: one pass's worth.
    static let serviceBudget = 20
    /// The breath between a streaming account's lookups.
    static let serviceSpacing: Duration = .milliseconds(250)
    /// The server doesn't know the song.
    static let unknown = LedgerTrack(title: nil, albumId: nil, albumName: nil, artist: nil, coverKey: nil)

    /// Every song asked about while the process lives (`unknown` when the server doesn't know it).
    private(set) var answers: [String: LedgerTrack] = [:]
    /// Songs being asked about now: a second pass (the page left and opened again) doesn't ask them twice.
    private(set) var inFlight: Set<String> = []
    /// How many songs each streaming account has answered for this process ([serviceBudget]).
    private(set) var serviceAnswered: [LedgerAccount: Int] = [:]
    /// A streaming lane is running: another pass leaves the service to it.
    private var serviceLaneBusy = false

    /// What a new pass mustn't ask: answered, or being asked.
    var asked: Set<String> { Set(answers.keys).union(inFlight) }

    /// The answers that name something, for the ledger's lookup (a copy: the lookup runs off the main actor).
    var known: [String: LedgerTrack] { answers.filter { $0.value != Self.unknown } }

    /// The songs in [entries] whose album isn't known, on a Subsonic account or the streaming account in use
    /// ([service]), not yet asked about (newest first).
    nonisolated static func pending(_ entries: [LedgerEntry], asked: Set<String>, service: LedgerAccount? = nil) -> [Wanted] {
        var seen = Set<String>()
        return entries.compactMap { e -> Wanted? in
            guard e.album == nil, let id = e.songId, e.kind == .trackRating || e.kind == .songNote,
                  e.account.provider == "subsonic" || e.account == service else { return nil }  // AccountScope.subsonicProvider
            let w = Wanted(account: e.account, songId: id)
            return !asked.contains(w.key) && seen.insert(w.key).inserted ? w : nil
        }
    }

    /// Asks for [wanted]: a Subsonic server's songs [parallel] at a time, then a streaming account's one at a
    /// time ([serviceSpacing] apart, within its pass and budget). [ask] returns the song, nil when the service
    /// doesn't know it, or throws when the account can't answer now (its other songs are then skipped this pass).
    func resolve(_ wanted: [Wanted], spacing: Duration = LedgerTrackResolver.serviceSpacing,
                 ask: @escaping @MainActor @Sendable (Wanted) async throws -> LedgerTrack?) async -> Pass {
        let servers = wanted.filter { $0.account.provider == "subsonic" }
        let services = wanted.filter { $0.account.provider != "subsonic" }
        var pass = await resolveServers(servers, ask: ask)
        if !services.isEmpty, !Task.isCancelled, !serviceLaneBusy {
            serviceLaneBusy = true
            let more = await resolveService(services, spacing: spacing, ask: ask)
            serviceLaneBusy = false
            pass = Pass(answered: pass.answered + more.answered, found: pass.found || more.found)
        }
        return pass
    }

    /// A streaming account's songs, one at a time: the newest of this pass, within the account's budget.
    private func resolveService(_ wanted: [Wanted], spacing: Duration,
                                ask: @escaping @MainActor @Sendable (Wanted) async throws -> LedgerTrack?) async -> Pass {
        var failed = Set<LedgerAccount>()
        var answered = 0, found = false, requests = 0
        for w in wanted.prefix(Self.servicePerPass) {
            guard !Task.isCancelled else { break }
            if failed.contains(w.account) || answers[w.key] != nil || inFlight.contains(w.key)
                || serviceAnswered[w.account, default: 0] >= Self.serviceBudget { continue }
            if requests > 0 { try? await Task.sleep(for: spacing) }
            guard !Task.isCancelled else { break }
            requests += 1
            inFlight.insert(w.key)
            let result: LedgerTrack??
            do { result = .some(try await ask(w)) } catch { result = nil }
            inFlight.remove(w.key)
            if Task.isCancelled { break }
            // Offline, rate limited or signed out: the account's other songs would fail the same way.
            guard let answer = result else { failed.insert(w.account); continue }
            let track = answer ?? Self.unknown
            answers[w.key] = track
            serviceAnswered[w.account, default: 0] += 1
            answered += 1
            if track.isNews { found = true }
        }
        return Pass(answered: answered, found: found)
    }

    /// Subsonic servers' songs, [parallel] at a time.
    private func resolveServers(_ wanted: [Wanted],
                                ask: @escaping @MainActor @Sendable (Wanted) async throws -> LedgerTrack?) async -> Pass {
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
    func forgetAll() { answers = [:]; inFlight = []; serviceAnswered = [:] }
    #endif
}
