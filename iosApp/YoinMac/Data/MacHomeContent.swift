import SwiftUI
import YoinShared

/// Home keeps entity kinds intact; raw ids alone are not card identities.
enum MacHomeContent: Hashable, Sendable, Identifiable {
    case album(Album)
    case artist(LibraryItem)
    case playlist(LibraryItem)
    case song(PlaylistEntry)
    case signal(JBISignal)

    var id: String {
        switch self {
        case .album(let a): "album:\(a.id.provider):\(a.id.rawId)"
        case .artist(let a): "artist:\(a.id.provider):\(a.id.rawId)"
        case .playlist(let p): "playlist:\(p.id.provider):\(p.id.rawId)"
        case .song(let s): "song:\(s.track.id.provider):\(s.track.id.rawId)"
        case .signal(let s): "signal:\(s.kind):\(s.album.id.provider):\(s.song?.track.id.rawId ?? s.album.id.rawId)"
        }
    }
}

struct MacHomeActivity: Hashable, Sendable, Identifiable {
    var content: MacHomeContent
    var at: Int64
    var id: String { content.id }
}

struct MacOtherAccountItem: Identifiable, Equatable {
    var profileId: String
    var scope: String
    var provider: String
    var profileName: String
    var record: ActivityRecord
    var palette: CoverPalette
    var id: String { "\(scope):\(provider):\(record.entity):\(record.entityId)" }
}

enum MacHomeSelection {
    static func timestamp(_ value: String?) -> Int64? {
        guard let value else { return nil }
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let date = formatter.date(from: value) ?? ISO8601DateFormatter().date(from: value)
        return date.map { Int64($0.timeIntervalSince1970 * 1000) }
    }

    static func isRecent(_ value: String?, now: Date = .now) -> Bool {
        guard let at = timestamp(value) else { return false }
        let end = Int64(now.timeIntervalSince1970 * 1000)
        return at >= end - 30 * HomeHistory.dayMs && at <= end
    }

    static func activities(_ entries: [MacHomeActivity], limit: Int = 7) -> [MacHomeActivity] {
        var seen = Set<String>()
        return Array(entries.sorted { $0.at > $1.at }.filter { seen.insert($0.id).inserted }.prefix(limit))
    }

    static func frequentActivities(_ records: [ActivityRecord], scope: String, provider: String,
                                   since: Int64, limit: Int = 6) -> [ActivityRecord] {
        let rows = records.filter {
            $0.scope == scope && $0.provider == provider && $0.action == .played
                && ($0.entity == .album || $0.entity == .playlist) && $0.timestamp >= since && !$0.title.isEmpty && !$0.entityId.isEmpty
        }
        let ranked = Dictionary(grouping: rows, by: { "\($0.entity):\($0.entityId)" }).values.sorted {
            if $0.count != $1.count { return $0.count > $1.count }
            let left = $0.map(\.timestamp).max() ?? 0, right = $1.map(\.timestamp).max() ?? 0
            if left != right { return left > right }
            return ($0.first?.entityId ?? "") < ($1.first?.entityId ?? "")
        }
        return ranked.prefix(limit).compactMap { $0.max(by: { $0.timestamp < $1.timestamp }) }
    }

    static func jumpBackIn(albums: [Album], songs: [PlaylistEntry], playlists: [LibraryItem],
                           signals: [JBISignal], limit: Int = 12) -> [MacHomeContent] {
        let signaledAlbums = Set(signals.filter { $0.kind == .ratedAlbum }.map(\.album.id))
        let signaledSongs = Set(signals.compactMap { $0.song?.track.id })
        let pools: [[MacHomeContent]] = [albums.filter { !signaledAlbums.contains($0.id) }.map(MacHomeContent.album),
                                       songs.filter { !signaledSongs.contains($0.track.id) }.map(MacHomeContent.song),
                                       playlists.map(MacHomeContent.playlist)]
        var result = signals.map(MacHomeContent.signal)
        for index in 0..<(pools.map(\.count).max() ?? 0) {
            for pool in pools where index < pool.count { result.append(pool[index]) }
        }
        var seen = Set<String>()
        return Array(result.filter { seen.insert($0.id).inserted }.prefix(limit))
    }
}

extension MacModel {
    func recordHomeActivity(_ content: MacHomeContent, action: ActivityAction) {
        let entity: ActivityEntity
        let id: MediaId
        let title: String, subtitle: String
        let palette: CoverPalette
        var artistId: String?
        switch content {
        case .album(let a):
            entity = .album; id = a.id; title = a.title; subtitle = a.artist; palette = a.palette; artistId = a.artistId
        case .artist(let a):
            entity = .artist; id = a.id; title = a.title; subtitle = a.subtitle; palette = a.palette
        case .playlist(let p):
            entity = .playlist; id = p.id; title = p.title; subtitle = p.subtitle; palette = p.palette
        default: return
        }
        guard id.provider == currentProvider else { return }
        let cover = id.provider == MediaRef.companion.PROVIDER_SUBSONIC
            ? OfflineDownloads.coverId(palette.cover) : palette.cover?.url.absoluteString
        HistoryStore.shared.recordCollectionActivity(scope: session.accountKey, provider: id.provider,
                                                     entity: entity, action: action, entityId: id.rawId,
                                                     title: title, subtitle: subtitle, coverKey: cover, artistId: artistId)
    }

    func homeActivities() -> [MacHomeActivity] {
        let records = HistoryStore.shared.recentActivities(scope: session.accountKey, provider: currentProvider, limit: 100)
        let local = records.compactMap { record -> MacHomeActivity? in
            let palette = homePalette(record)
            let id = MediaId(provider: record.provider, rawId: record.entityId)
            let content: MacHomeContent
            switch record.entity {
            case .album:
                let album = library.album(record.entityId) ?? Album(id: id, title: record.title, artist: record.subtitle,
                                                                   year: 0, palette: palette, tracks: [], artistId: record.artistId)
                content = .album(album)
            case .artist:
                content = .artist(LibraryItem(id: id, kind: .artist, title: record.title, subtitle: record.subtitle,
                                              palette: palette))
            case .playlist:
                content = .playlist(LibraryItem(id: id, kind: .playlist, title: record.title, subtitle: record.subtitle,
                                                palette: palette))
            case .song: return nil
            }
            return MacHomeActivity(content: content, at: record.timestamp)
        }
        return MacHomeSelection.activities(local + library.home.activities)
    }

    func homeJumpBackIn() -> [MacHomeContent] {
        let inputs = historyInputs()
        var signals: [JBISignal] = []
        let memories = memoryCards()
        if var signal = HomeHistory.ratedAlbumSignal(HomeHistory.memories(inputs)) {
            if let card = memories.first(where: { $0.album.id == signal.album.id }) {
                signal.title = card.title
                signal.comment = card.excerpt?.text
            }
            signals.append(signal)
        } else if let card = memories.first {
            signals.append(JBISignal(kind: .ratedAlbum, album: card.album, title: card.title, subtitle: card.album.artist,
                                     score: HomeHistory.scoreText(card.score), comment: card.excerpt?.text))
        }
        if let signal = HomeHistory.notedTrackSignal(inputs, placeholder: { note in
            Album(id: MediaId(provider: note.provider, rawId: "song:\(note.trackId)"), title: note.title,
                  artist: note.artist, year: 0, palette: MacArtistDetail.placeholder(note.trackId), tracks: [])
        }) { signals.append(signal) }
        let home = library.home
        return MacHomeSelection.jumpBackIn(albums: home.recentlyPlayed + home.random, songs: home.randomSongs,
                                           playlists: library.playlists, signals: signals)
    }

    func homePalette(_ record: ActivityRecord) -> CoverPalette {
        var palette = MacArtistDetail.placeholder(record.entityId)
        let url: URL? = if record.provider == MediaRef.companion.PROVIDER_SUBSONIC {
            record.coverKey.flatMap { key in session.client.flatMap { URL(string: $0.coverArtUrl(id: key, size: nil)) } }
        } else { record.coverKey.flatMap(URL.init(string:)) }
        palette.cover = url.map(CoverRef.init(url:))
        return palette
    }

    /// Each account takes a turn; visits do not contribute to play frequency.
    func homeOtherAccounts() -> [MacOtherAccountItem] {
        let history = HistoryStore.shared
        let cutoff = history.nowMs - 90 * HomeHistory.dayMs
        var groups: [[MacOtherAccountItem]] = []
        for profile in session.profiles where profile.id != session.activeId {
            guard let identity = homeIdentity(profile), identity.scope != session.accountKey else { continue }
            let ranked = MacHomeSelection.frequentActivities(history.activities, scope: identity.scope,
                                                            provider: identity.provider, since: cutoff)
            // Relative cover ids are signed by their own server, never the active account's client.
            let client = profile.account.map { SubsonicClient(baseUrl: $0.baseUrl, username: $0.username, password: $0.password) }
            defer { client?.close() }
            let items = ranked.map { record in
                let url: URL? = if identity.provider == MediaRef.companion.PROVIDER_SUBSONIC {
                    record.coverKey.flatMap { key in client.flatMap { URL(string: $0.coverArtUrl(id: key, size: nil)) } }
                } else { record.coverKey.flatMap(URL.init(string:)) }
                var palette = MacArtistDetail.placeholder(record.entityId)
                palette.cover = url.map(CoverRef.init(url:))
                return MacOtherAccountItem(profileId: profile.id, scope: identity.scope, provider: identity.provider,
                                           profileName: identity.name, record: record, palette: palette)
            }
            if !items.isEmpty { groups.append(items) }
        }
        var items: [MacOtherAccountItem] = []
        for index in 0..<6 {
            for group in groups where index < group.count { items.append(group[index]) }
        }
        return Array(items.prefix(12))
    }

    private func homeIdentity(_ profile: MacSession.Profile) -> (scope: String, provider: String, name: String)? {
        switch profile.service {
        case .subsonic:
            guard let account = profile.account else { return nil }
            return ("\(account.baseUrl)|\(account.username)", MediaRef.companion.PROVIDER_SUBSONIC, account.username)
        case .appleMusic:
            guard MusicAuthorizationStatus.isAuthorized else { return nil }
            return (AppleMusicMapping.provider, AppleMusicMapping.provider, "Apple Music")
        case .spotify:
            guard let tokens = SpotifyTokenStore.load(profile.id) else { return nil }
            let user = profile.spotifyUserId ?? tokens.userId
            return ("\(SpotifyMapping.provider)|\(user)", SpotifyMapping.provider, profile.name ?? "Spotify")
        }
    }

    func play(otherAccount item: MacOtherAccountItem) {
        guard let profile = session.profiles.first(where: { $0.id == item.profileId }),
              let identity = homeIdentity(profile), identity.scope == item.scope, identity.provider == item.provider else { return }
        withAnimation(Yoin.spatial) { switchAccount(item.profileId) }
        guard session.isSignedIn, session.activeId == item.profileId, session.accountKey == item.scope,
              currentProvider == item.provider else { return }
        let generation = library.accountGeneration
        Task {
            guard generation == library.accountGeneration, session.activeId == item.profileId,
                  session.accountKey == item.scope, currentProvider == item.provider else { return }
            switch item.record.entity {
            case .album:
                let album = Album(id: MediaId(provider: item.provider, rawId: item.record.entityId), title: item.record.title,
                                  artist: item.record.subtitle, year: 0, palette: item.palette, tracks: [])
                await playAlbum(album)
            case .playlist: await playPlaylist(item.record.entityId)
            default: break
            }
        }
    }
}
