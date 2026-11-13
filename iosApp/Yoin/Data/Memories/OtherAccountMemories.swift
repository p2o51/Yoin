import Foundation
import Observation
import YoinShared

/*
 * The other accounts' memories for the Memories deck (Android ui/memories/MemoryOtherProfiles.kt and
 * YoinRepository.getOtherProfileAlbumMemoryCandidates, #178, owner 2026-11-08): Memories deals every
 * account's memories, not only the account in use's. Local only: each account's own rows (the stores'
 * every-account reads) and the album pages Yoin kept on disk for it. That account's source isn't built, so
 * nothing goes to the network; an album without a stored page still qualifies by its review or notes.
 */

/// One account's local rows, as the stores keep them.
struct MemoryAccountRows: Hashable, Sendable {
    var plays: [PlayEvent] = []
    var notes: [SongNote] = []
    var ratings: [LocalRatings.Row] = []
    var albums: [AlbumMemoryStore.AlbumRecord] = []
    var titles: [AlbumMemoryStore.TitleRecord] = []
    var copies: [AlbumMemoryStore.MemoryCopy] = []
}

/// Another account's rows as the deck reads them. Pure (OtherAccountMemoriesTests).
enum OtherAccountDeck {
    /// Album pages read from disk for one account at most (Android's scan limit: the limit × 4).
    static let scanLimit = MemoryDeck.otherAccountLimit * 4

    /// [account]'s rows out of every account's.
    static func rows(of account: LedgerAccount, plays: [PlayEvent], notes: [SongNote], ratings: [LocalRatings.Row],
                     albums: [AlbumMemoryStore.AlbumRecord], titles: [AlbumMemoryStore.TitleRecord],
                     copies: [AlbumMemoryStore.MemoryCopy]) -> MemoryAccountRows {
        let history = MemoryLedgerSource.historyScope(account)
        func its(_ provider: String, _ scope: String?) -> Bool { provider == account.provider && scope == account.scope }
        return MemoryAccountRows(
            plays: plays.filter { $0.provider == account.provider && $0.scope == history },
            notes: notes.filter { its($0.provider, $0.scope) },
            ratings: ratings.filter { its($0.provider, $0.scope) },
            albums: albums.filter { its($0.provider, $0.scope) },
            titles: titles.filter { its($0.provider, $0.scope) },
            copies: copies.filter { its($0.provider, $0.scope) && $0.entityType == "album" })
    }

    /// The albums with something written on them that the rows can name (an album rating or review; a
    /// noted or rated song, through its plays), newest write first, [limit] at most: the ones whose
    /// stored page the deck reads. Plays alone never make a memory.
    static func writtenAlbumIds(_ rows: MemoryAccountRows, limit: Int = scanLimit) -> [String] {
        var songAlbum: [String: String] = [:]
        for p in rows.plays { if let a = p.albumId { songAlbum[p.songId] = a } }
        var written: [String: Int64] = [:]
        func mark(_ album: String, _ at: Int64) { written[album] = max(written[album] ?? 0, at) }
        for r in rows.albums where r.rating > 0 || isWritten(r.review) { mark(r.albumId, max(r.updatedAt, r.reviewUpdatedAt ?? 0)) }
        for n in rows.notes where isWritten(n.content) { if let a = songAlbum[n.trackId] { mark(a, n.updatedAt) } }
        for r in rows.ratings where r.rating != nil { if let a = songAlbum[r.songId] { mark(a, r.updatedAt ?? 0) } }
        return written.sorted { $0.value != $1.value ? $0.value > $1.value : $0.key < $1.key }.prefix(limit).map(\.key)
    }

    /// The deck's inputs for [account] (not the one in use): its memories from [rows] and the album pages
    /// in [details] (raw id → the stored page), Android's builder order kept and its limit applied; its
    /// reviews, titles, ratings and AI titles read from the same rows. [snapshot] stands in for an album
    /// without a stored page (its newest play's names and cover).
    static func inputs(account: MemoryCard.Account, rows: MemoryAccountRows, details: [String: Album], now: Int64,
                       snapshot: @escaping (String, PlayEvent?) -> Album? = { _, _ in nil }) -> MemoryDeck.Inputs {
        var rated: [String: Double] = [:], ratedAt: [String: Int64] = [:]
        for r in rows.ratings {
            if let value = r.rating { rated[r.songId] = value }
            if let at = r.updatedAt { ratedAt[r.songId] = at }
        }
        let albumRows = Dictionary(rows.albums.map { ($0.albumId, $0) }, uniquingKeysWith: { $1 })
        let history = HomeHistory.Inputs(
            now: now, plays: rows.plays, notes: rows.notes, details: details, known: [:],
            localRating: { rated[$0] },
            resolve: { id, snap in details[id] ?? snapshot(id, snap) },
            localAlbumRating: { albumRows[$0]?.rating },
            ratedAlbumIds: rows.albums.filter { $0.rating > 0 || isWritten($0.review) }.map(\.albumId),
            ratingWrittenAt: { ratedAt[$0] },
            albumWrittenAt: { albumRows[$0].map { max($0.updatedAt, $0.reviewUpdatedAt ?? 0) } },
            albumReviewed: { isWritten(albumRows[$0]?.review) })
        let memories = HomeHistory.memories(history).filter(\.isMemoryEligible).prefix(MemoryDeck.otherAccountLimit)

        var songAlbum: [String: String] = [:]
        for p in rows.plays { if let a = p.albumId { songAlbum[p.songId] = a } }
        for (id, album) in details { for t in album.tracks where songAlbum[t.id.rawId] == nil { songAlbum[t.id.rawId] = id } }
        var byAlbum: [String: [SongNote]] = [:]
        for n in rows.notes { if let a = songAlbum[n.trackId] { byAlbum[a, default: []].append(n) } }
        var titles: [String: String] = [:]
        for t in rows.titles { if let words = words(t.title) { titles[t.albumId] = words } }
        let copies = Dictionary(rows.copies.map { ($0.entityId, $0) }, uniquingKeysWith: { $1 })
        return MemoryDeck.Inputs(
            memories: Array(memories), notes: byAlbum,
            review: { id in
                guard let r = albumRows[id], let text = r.review else { return nil }
                return (text, r.reviewUpdatedAt ?? r.updatedAt)
            },
            title: { id in
                if let own = titles[id] { return (own, true) }
                return words(copies[id]?.title).map { ($0, false) }
            },
            rating: { rated[$0] },
            yoinTitle: { copies[$0]?.title },
            copy: { copies[$0]?.copy },
            account: account, isOther: true,
            memoryCopy: { copies[$0] })
    }

    private static func words(_ text: String?) -> String? {
        guard let trimmed = text?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else { return nil }
        return trimmed
    }

    private static func isWritten(_ text: String?) -> Bool { words(text) != nil }
}

/// Reads the other accounts' memories and keeps them for the deck (Android `OtherMemoryProfiles`): read when
/// Home shows, after an account change and when Memories opens. Reads only.
@MainActor
@Observable
final class OtherAccountMemories {
    static let shared = OtherAccountMemories()

    /// Bumps when a read brought something new: an open deck takes it in place.
    private(set) var revision = 0
    @ObservationIgnored private var inputs: [MemoryDeck.Inputs] = []
    /// The account in use the others were read beside.
    @ObservationIgnored private var loadedFor: String?
    @ObservationIgnored private var fingerprint = 0
    @ObservationIgnored private var generation = 0
    /// One client per Subsonic account, for its cover URLs (not one per read).
    @ObservationIgnored private var mappings: [String: (signIn: ServerSession.Account, mapping: SubsonicMapping)] = [:]

    /// The other accounts' memories beside [activeId]; none until they were read for it, and never an
    /// account that has been removed since.
    func inputs(beside activeId: String?, profiles: [Profile]) -> [MemoryDeck.Inputs] {
        guard loadedFor == activeId else { return [] }
        let ids = Set(profiles.map(\.id))
        return inputs.filter { $0.account.map { ids.contains($0.profileId) } ?? false }
    }

    /// The accounts Yoin could switch to (Android `usableOtherProfiles`, the ones Home's other-accounts
    /// shelf offers): not in use, nothing standing in the way.
    static func usable(_ profiles: ProfileManager) -> [Profile] {
        profiles.profiles.filter { $0.id != profiles.activeId && !profiles.needsSignIn($0) }
    }

    func reload(model: AppModel = .shared) async {
        generation += 1
        let gen = generation
        let profiles = model.profiles
        guard model.store.isReal, let active = profiles.active else { return land([], hash: 0, for: nil) }
        let shapes = profiles.avatarShapes
        let history = HistoryStore.shared, memoryStore = AlbumMemoryStore.shared
        let disk = model.store.disk
        var built: [MemoryDeck.Inputs] = []
        var hasher = Hasher()
        for p in Self.usable(profiles) {
            guard let ledger = MemoryLedgerSource.account(of: p) else { continue }
            let rows = OtherAccountDeck.rows(of: ledger, plays: history.plays, notes: NotesStore.shared.everyAccount,
                                             ratings: LocalRatings.shared.everyAccount, albums: memoryStore.everyAlbum,
                                             titles: memoryStore.everyTitle, copies: memoryStore.everyMemory)
            let ids = OtherAccountDeck.writtenAlbumIds(rows)
            guard !ids.isEmpty else { continue }
            let mapping = coverMapping(p, profiles)
            let codec = mapping.map(DetailCacheCodec.subsonic) ?? .appleMusic
            let scope = MemoryLedgerSource.historyScope(ledger)
            var details: [String: Album] = [:]
            for id in ids {
                if let hit = await disk.read(DetailCacheDTO.Album.self, scope: scope, kind: .album, id: id) {
                    details[id] = codec.album(hit.value)
                }
            }
            // A newer read is on its way (an account change while this one was at the disk).
            guard gen == generation else { return }
            let account = MemoryCard.Account(profileId: p.id, name: p.title, service: p.service, shape: shapes[p.id] ?? 0)
            let x = OtherAccountDeck.inputs(account: account, rows: rows, details: details, now: history.nowMs) { id, snap in
                LibraryStore.snapshotAlbum(id, snap, mapping: mapping)
            }
            guard !x.memories.isEmpty else { continue }
            hasher.combine(account)
            hasher.combine(rows)
            hasher.combine(x.memories)
            built.append(x)
        }
        land(built, hash: hasher.finalize(), for: active.id)
    }

    private func land(_ built: [MemoryDeck.Inputs], hash: Int, for activeId: String?) {
        let changed = loadedFor != activeId || fingerprint != hash
        inputs = built
        loadedFor = activeId
        fingerprint = hash
        if changed { revision &+= 1 }
    }

    private func coverMapping(_ p: Profile, _ profiles: ProfileManager) -> SubsonicMapping? {
        guard p.service == .subsonic, let signIn = profiles.signIn(p.id) else { return nil }
        if let kept = mappings[p.id], kept.signIn == signIn { return kept.mapping }
        let made = SubsonicMapping(client: SubsonicClient(baseUrl: signIn.baseUrl, username: signIn.username,
                                                          password: signIn.password))
        mappings[p.id] = (signIn, made)
        return made
    }
}

extension AppModel {
    /// What the Memories deck reads: the account in use's memories and every other account's (#178).
    /// Signed out: the mock deck, as it was.
    func memoryDeckInputs() -> MemoryDeck.Inputs {
        var x = store.memoryDeckInputs()
        guard store.isReal, let active = profiles.active else { return x }
        x.account = MemoryCard.Account(profileId: active.id, name: active.title, service: active.service,
                                       shape: profiles.avatarShapes[active.id] ?? 0)
        x.others = OtherAccountMemories.shared.inputs(beside: active.id, profiles: profiles.profiles)
        return x
    }
}
