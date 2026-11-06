import Foundation

/// Spotify's two search scopes and the next page of a catalog search (Android
/// LibraryViewModel searchSpotifySavedLibrary / loadMoreSearchResults).
extension LibraryStore {
    /// The saved library: Library's own lists, read first if they are not here yet.
    func spotifyLibrarySearch(_ term: String, _ spotify: SpotifySource) async -> SpotifySource.SearchResults {
        await loadLibrary(nil)
        await loadSongs()
        return SpotifySource.filterLibrary(term, artists: artists, albums: albums, songs: songs, playlists: playlists)
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
