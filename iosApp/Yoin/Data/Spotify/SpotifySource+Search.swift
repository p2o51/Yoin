import Foundation

/// A kind of search result: the unit Spotify pages and the Search filters list
/// (Android SearchResultType).
enum SearchKind: String, CaseIterable, Sendable {
    case artists, albums, songs, playlists

    /// The Web API's `type` value.
    var apiType: String {
        switch self {
        case .artists: "artist"
        case .albums: "album"
        case .songs: "track"
        case .playlists: "playlist"
        }
    }
}

/// Spotify search (Android SpotifyMusicSource.search / searchMore, the saved-library
/// search of LibraryViewModel.searchSpotifySavedLibrary).
extension SpotifySource {
    /// Development Mode caps a search page at 10 per type (2026-02).
    static let searchPageSize = 10
    /// The API refuses `offset + limit` past 1000 (Search for Item): the last page starts here.
    static let searchOffsetCeiling = 1000 - searchPageSize

    /// The catalog: [kinds] from [offset] on (ten each). `more` names the kinds the
    /// answer says continue (`next`), `counts` how many items each kind's page held (nulls
    /// included), which is where its next page starts.
    func search(_ term: String, kinds: [SearchKind] = SearchKind.allCases, offset: Int = 0) async throws -> SearchResults {
        let r = try await api.get("search", [
            "q": term, "type": kinds.map(\.apiType).joined(separator: ","),
            "limit": String(Self.searchPageSize), "offset": String(offset),
        ], as: SpotifyDTO.Search.self)
        var more: Set<SearchKind> = []
        var counts: [SearchKind: Int] = [:]
        func note(_ kind: SearchKind, next: String?, count: Int?) {
            guard let count else { return }
            counts[kind] = count
            // Past the API's ceiling there is no next page, whatever `next` says.
            if next != nil, offset + count <= Self.searchOffsetCeiling { more.insert(kind) }
        }
        note(.songs, next: r.tracks?.next, count: r.tracks?.items.count)
        note(.albums, next: r.albums?.next, count: r.albums?.items.count)
        note(.artists, next: r.artists?.next, count: r.artists?.items.count)
        note(.playlists, next: r.playlists?.next, count: r.playlists?.items.count)
        return SearchResults(
            artists: (r.artists?.items ?? []).compactMap { $0.flatMap(SpotifyMapping.artistItem) },
            albums: SpotifyMapping.uniqueById((r.albums?.items ?? []).compactMap { $0.flatMap { SpotifyMapping.album($0) } }),
            songs: SpotifyMapping.entries((r.tracks?.items ?? []).compactMap { $0 }, saved: liked),
            playlists: (r.playlists?.items ?? []).compactMap { $0 }.map(SpotifyMapping.playlistItem),
            more: more, counts: counts)
    }

    // MARK: Saved library

    /// [text] as the saved-library search compares it: case and accents dropped
    /// ("thien duong" finds "Thiên Đường"; Android foldForLibrarySearch).
    nonisolated static func folded(_ text: String) -> String {
        text.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil)
            .replacingOccurrences(of: "đ", with: "d")
    }

    /// What the saved library holds that matches [term]: saved and liked
    /// songs, saved albums, playlists, followed artists plus the artists of the
    /// saved albums and songs. Every match, not a page (Android: the names a
    /// row shows only, never the opaque id).
    nonisolated static func filterLibrary(_ term: String, artists: [LibraryItem], albums: [Album],
                                          songs: [PlaylistEntry], playlists: [LibraryItem]) -> SearchResults {
        let needle = folded(term.trimmingCharacters(in: .whitespacesAndNewlines))
        guard !needle.isEmpty else { return SearchResults(artists: [], albums: [], songs: [], playlists: []) }
        func hit(_ fields: String?...) -> Bool { fields.contains { $0.map { folded($0).contains(needle) } ?? false } }

        // Artists the library holds by any road, once each.
        var known = Set<MediaId>()
        var everyArtist: [LibraryItem] = []
        func add(_ item: LibraryItem) { if known.insert(item.id).inserted { everyArtist.append(item) } }
        artists.forEach(add)
        for album in albums + songs.map(\.album) {
            guard let id = album.artistId, !id.isEmpty else { continue }
            add(LibraryItem(id: MediaId(provider: SpotifyMapping.provider, rawId: id), kind: .artist,
                            title: album.artist, subtitle: String(localized: "Artist"), palette: album.palette))
        }
        var seenAlbums = Set<MediaId>()
        let matchedAlbums = albums.filter { seenAlbums.insert($0.id).inserted && hit($0.title, $0.artist) }
        return SearchResults(
            artists: everyArtist.filter { hit($0.title) },
            albums: matchedAlbums,
            songs: songs.filter { hit($0.track.title, $0.track.artist ?? $0.album.artist, $0.album.title) },
            playlists: playlists.filter { hit($0.title, $0.owner) })
    }

    /// Liked Songs from [offset] (a page of 50 each, up to [limit] songs) and where the next read
    /// starts (nil: the end), for a Library list longer than its first read.
    func librarySongs(offset: Int, limit: Int = 300) async throws -> (songs: [PlaylistEntry], next: Int?) {
        var out: [SpotifyDTO.SavedTrack] = []
        var at = offset
        var more = true
        while more, out.count < limit {
            let page = try await api.get("me/tracks", ["limit": "50", "offset": String(at)],
                                         as: SpotifyDTO.Paging<SpotifyDTO.SavedTrack>.self)
            out += page.items
            at += page.items.count
            more = page.next != nil && !page.items.isEmpty
        }
        let tracks = out.compactMap(\.track)
        noteLiked(tracks.compactMap(\.id))
        return (SpotifyMapping.entries(tracks, saved: liked), more ? at : nil)
    }
}
