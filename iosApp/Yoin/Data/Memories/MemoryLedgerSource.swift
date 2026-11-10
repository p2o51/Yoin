import Foundation
import Observation
import SwiftUI
import YoinShared

/// An account the ledger has memories from: what an entry's "From …" line and the account chips show.
struct LedgerProfile: Hashable, Sendable, Identifiable {
    var id: String
    var account: LedgerAccount
    var name: String
    var service: Profile.Service
    var isActive: Bool
}

/// Everything Home's Memories grid and the All Memories page show (Android MemoryLedgerState).
struct LedgerState: Sendable {
    /// Newest first.
    var entries: [LedgerEntry]
    var stats: LedgerStats
    var grid: MemoryGrid
    /// Album key → its cover (palette and server image).
    var covers: [String: CoverPalette]
    /// The accounts with any memory, the one in use first.
    var profiles: [LedgerProfile]
    var activeAccount: LedgerAccount?
    var today: Date

    var isEmpty: Bool { entries.isEmpty }

    func profile(_ account: LedgerAccount) -> LedgerProfile? { profiles.first { $0.account == account } }
    /// A mark from an account other than the one in use (shown "From …", opens nothing).
    func isOther(_ account: LedgerAccount) -> Bool { activeAccount != nil && account != activeAccount }
}

/// Gathers the ledger from the local stores, every account's (Android MemoryLedgerViewModel). Reloaded when
/// Home or All Memories appears and after a write; reads only.
@MainActor
@Observable
final class MemoryLedgerSource {
    static let shared = MemoryLedgerSource()

    private(set) var state: LedgerState?

    /// The ledger account a profile's rows are kept under.
    static func account(of p: Profile) -> LedgerAccount? {
        switch p.service {
        case .subsonic: p.subsonicScope.map { LedgerAccount(provider: AccountScope.subsonicProvider, scope: $0) }
        case .spotify: p.spotifyUserId.map { LedgerAccount(provider: AccountScope.spotifyProvider, scope: AccountScope.spotifyScope(userId: $0)) }
        case .appleMusic: LedgerAccount(provider: AppleMusicMapping.provider, scope: nil)
        }
    }

    /// The history scope a ledger account's plays are kept under (`LibraryStore.historyScope`).
    nonisolated static func historyScope(_ a: LedgerAccount) -> String { a.scope ?? a.provider }

    func reload(model: AppModel = .shared, now: Date = Date(), calendar: Calendar = .current) {
        let profiles = model.profiles.profiles
        let active = model.profiles.active
        var accounts: [LedgerAccount: Profile] = [:]
        for p in profiles { if let a = Self.account(of: p) { accounts[a] = p } }
        let rows = Self.rows(notes: NotesStore.shared, ratings: LocalRatings.shared, albums: AlbumMemoryStore.shared)
        let lookup = Self.lookup(history: HistoryStore.shared, active: active.flatMap(Self.account(of:)),
                                 details: model.store.albumDetails)
        // Signed out entirely: nothing counts (a removed account's rows aren't anyone's memories now).
        let entries = MemoryLedger.build(rows, accounts: Set(accounts.keys), lookup: lookup)
        var covers: [String: CoverPalette] = [:]
        var mappings: [String: SubsonicMapping] = [:]
        for album in Set(entries.compactMap(\.album)) {
            covers[album.key] = cover(album, profile: accounts[album.account], mappings: &mappings, model: model)
        }
        let withMemories = Set(entries.map(\.account))
        let activeAccount = active.flatMap(Self.account(of:))
        let ledgerProfiles = accounts.compactMap { a, p -> LedgerProfile? in
            guard withMemories.contains(a) else { return nil }
            return LedgerProfile(id: p.id, account: a, name: p.title, service: p.service, isActive: a == activeAccount)
        }.sorted { $0.isActive != $1.isActive ? $0.isActive : $0.name < $1.name }
        state = LedgerState(entries: entries, stats: MemoryLedger.stats(entries),
                            grid: MemoryGrid.build(entries, today: now, calendar: calendar),
                            covers: covers, profiles: ledgerProfiles, activeAccount: activeAccount, today: now)
    }

    nonisolated static func rows(notes: [SongNote], ratings: [LocalRatings.Row],
                                 albums: [AlbumMemoryStore.AlbumRecord], titles: [AlbumMemoryStore.TitleRecord]) -> LedgerRows {
        LedgerRows(
            notes: notes.map { .init(id: $0.id, account: .init(provider: $0.provider, scope: $0.scope), trackId: $0.trackId,
                                     content: $0.content, createdAt: $0.createdAt, title: $0.title, positionMs: $0.positionMs) },
            trackRatings: ratings.map { .init(account: .init(provider: $0.provider, scope: $0.scope), songId: $0.songId,
                                              rating: $0.rating, updatedAt: $0.updatedAt ?? 0) },
            albums: albums.map { .init(account: .init(provider: $0.provider, scope: $0.scope), albumId: $0.albumId,
                                       rating: $0.rating, review: $0.review, reviewUpdatedAt: $0.reviewUpdatedAt,
                                       updatedAt: $0.updatedAt) },
            titles: titles.map { .init(account: .init(provider: $0.provider, scope: $0.scope), albumId: $0.albumId,
                                       title: $0.title, updatedAt: $0.updatedAt) })
    }

    static func rows(notes: NotesStore, ratings: LocalRatings, albums: AlbumMemoryStore) -> LedgerRows {
        rows(notes: notes.everyAccount, ratings: ratings.everyAccount, albums: albums.everyAlbum, titles: albums.everyTitle)
    }

    /// Albums and songs as plays, album visits and (for the account in use) loaded track lists know them.
    static func lookup(history: HistoryStore, active: LedgerAccount?, details: [String: Album]) -> LedgerLookup {
        lookup(plays: history.plays, activities: history.activities, active: active, details: details)
    }

    nonisolated static func lookup(plays: [PlayEvent], activities: [ActivityRecord], active: LedgerAccount?,
                                   details: [String: Album]) -> LedgerLookup {
        struct Key: Hashable { var scope: String; var provider: String; var id: String }
        var albums: [Key: LedgerAlbum] = [:]
        var songs: [Key: (String?, String?)] = [:]
        // Oldest first, so the newest snapshot of a name wins.
        for p in plays.sorted(by: { $0.startedAt < $1.startedAt }) {
            let k = Key(scope: p.scope, provider: p.provider, id: p.songId)
            songs[k] = (p.albumId ?? songs[k]?.0, p.title.nonBlank ?? songs[k]?.1)
            guard let albumId = p.albumId, let name = (p.album.nonBlank ?? albums[.init(scope: p.scope, provider: p.provider, id: albumId)]?.name) else { continue }
            albums[.init(scope: p.scope, provider: p.provider, id: albumId)] = LedgerAlbum(
                account: .init(provider: p.provider, scope: nil), albumId: albumId, name: name, artist: p.artist,
                coverKey: p.coverKey)
        }
        for a in activities.sorted(by: { $0.timestamp < $1.timestamp }) where a.entity == .album {
            guard let name = a.title.nonBlank else { continue }
            let k = Key(scope: a.scope, provider: a.provider, id: a.entityId)
            let known = albums[k]
            albums[k] = LedgerAlbum(account: .init(provider: a.provider, scope: nil), albumId: a.entityId, name: name,
                                    artist: a.subtitle, coverKey: a.coverKey ?? known?.coverKey)
        }
        let activeScope = active.map(historyScope)
        let activeDetails = details
        let albumIndex = albums
        let songIndex = songs
        @Sendable func coverKey(_ url: URL?, _ provider: String) -> String? {
            guard let url else { return nil }
            if provider == MediaRef.companion.PROVIDER_SUBSONIC {
                return URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "id" }?.value
            }
            return url.absoluteString
        }
        return LedgerLookup(
            album: { account, id in
                if account == active, let d = activeDetails[id] {
                    return LedgerAlbum(account: account, albumId: id, name: d.title, artist: d.artist,
                                       coverKey: coverKey(d.palette.cover?.url, account.provider))
                }
                guard var a = albumIndex[.init(scope: historyScope(account), provider: account.provider, id: id)] else { return nil }
                a.account = account
                return a
            },
            song: { account, id in
                let scope = historyScope(account)
                if let s = songIndex[.init(scope: scope, provider: account.provider, id: id)] { return (s.0, s.1) }
                if scope == activeScope {
                    for (albumId, d) in activeDetails {
                        if let t = d.tracks.first(where: { $0.id.rawId == id }) { return (albumId, t.title) }
                    }
                }
                return nil
            })
    }

    /// The cover an album is drawn with: the account in use's own copy when loaded, else its snapshot's cover
    /// key read through that account's server (Subsonic) or as the artwork URL.
    private func cover(_ album: LedgerAlbum, profile: Profile?, mappings: inout [String: SubsonicMapping],
                       model: AppModel) -> CoverPalette {
        if album.account == model.profiles.active.flatMap(Self.account(of:)), let d = model.store.albumDetails[album.albumId] {
            return d.palette
        }
        if album.account.provider == AccountScope.subsonicProvider, let profile {
            if mappings[profile.id] == nil, let signIn = model.profiles.signIn(profile.id) {
                mappings[profile.id] = SubsonicMapping(client: SubsonicClient(baseUrl: signIn.baseUrl, username: signIn.username,
                                                                              password: signIn.password))
            }
            if let m = mappings[profile.id] { return m.palette(coverId: album.coverKey ?? album.albumId) }
        }
        return AppleMusicMapping.palette(album.coverKey.flatMap(URL.init(string:)), key: album.key)
    }
}
