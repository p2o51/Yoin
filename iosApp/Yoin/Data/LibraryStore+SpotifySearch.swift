import Foundation

/// Spotify's two search scopes and the next page of a catalog search (Android
/// LibraryViewModel searchSpotifySavedLibrary / loadMoreSearchResults).
extension LibraryStore {
    /// A saved-library search whose lists couldn't be read: not "No results" (the reason instead).
    struct LibraryReadFailed: LocalizedError {
        var message: String
        var errorDescription: String? { message }
    }

    /// The saved library: Library's own lists, read first if they are not here yet, and every liked song
    /// (not only the first read's). Nothing found while a list failed to read throws that list's failure.
    func spotifyLibrarySearch(_ term: String, _ spotify: SpotifySource) async throws -> SpotifySource.SearchResults {
        await loadLibrary(nil)
        await loadSongs()
        // The rest of Liked Songs, a read at a time; a failed read (the rate limit) stops here.
        while spotifySongsNext != nil, songsMore == .available || songsMore == .failed, !Task.isCancelled {
            let before = spotifySongsNext
            await loadMoreSongs()
            if songsMore == .failed || spotifySongsNext == before { break }
        }
        let r = SpotifySource.filterLibrary(term, artists: artists, albums: albums, songs: songs, playlists: playlists)
        let found = !(r.artists.isEmpty && r.albums.isEmpty && r.songs.isEmpty && r.playlists.isEmpty)
        if !found, let failed = ["albums", "artists", "playlists", "songs"].first(where: { phase($0) == .failed }) {
            throw LibraryReadFailed(message: failures[failed] ?? String(localized: "Couldn't load your library"))
        }
        if !found, songsMore == .failed {
            throw LibraryReadFailed(message: String(localized: "Couldn't load all of your liked songs"))
        }
        return r
    }

    /// What follows [kind]'s rows in the current search: a page being read, a failed read,
    /// more where Spotify has it, else nothing. Only the catalog continues; the library is whole.
    func searchMore(_ kind: SearchKind) -> SongsMore {
        guard let hits = search, hits.scope == .catalog, searchRequest?.query == hits.query else { return .none }
        return searchPaging[kind] ?? (hits.more.contains(kind) ? .available : .none)
    }

    /// [kind]'s next ten (Development Mode's page), appended to the rows shown. One read at a
    /// time; a new search drops it.
    func loadMoreSearch(_ kind: SearchKind) async {
        guard let spotify, let hits = search, let request = searchRequest, request.scope == .catalog,
              hits.query == request.query, searchMore(kind) == .available || searchMore(kind) == .failed else { return }
        let gen = generation
        searchPaging[kind] = .loading
        func current() -> Bool { gen == generation && searchRequest == request && search?.query == request.query }
        do {
            let page = try await spotify.search(request.query, kinds: [kind], offset: hits.offsets[kind] ?? 0)
            guard current(), var shown = search else { return }
            switch kind {
            case .artists: shown.artists += page.artists.filter { a in !shown.artists.contains { $0.id == a.id } }
            case .albums: shown.albums += page.albums.filter { a in !shown.albums.contains { $0.id == a.id } }
            case .songs: shown.songs += page.songs.filter { s in !shown.songs.contains { $0.track.id == s.track.id } }
            case .playlists: shown.playlists += page.playlists.filter { p in !shown.playlists.contains { $0.id == p.id } }
            }
            shown.offsets[kind] = (shown.offsets[kind] ?? 0) + (page.counts[kind] ?? 0)
            if page.more.contains(kind) { shown.more.insert(kind) } else { shown.more.remove(kind) }
            search = shown
            searchPaging[kind] = nil
        } catch {
            guard current() else { return }
            // The rows stay; the foot's retry reads the same page again.
            searchPaging[kind] = .failed
        }
    }
}
