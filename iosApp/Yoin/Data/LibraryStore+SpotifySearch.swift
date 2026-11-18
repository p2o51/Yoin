import Foundation

/// Spotify's two search scopes and the next page of a catalog search (Android
/// LibraryViewModel searchSpotifySavedLibrary / loadMoreSearchResults).
extension LibraryStore {
    /// A saved-library search whose lists couldn't be read: not "No results" (the reason instead).
    struct LibraryReadFailed: LocalizedError {
        var message: String
        var errorDescription: String? { message }
    }

    /// The saved library: Library's own lists and every liked song (not only the first read's). With lists in
    /// memory or on disk the search answers from them at once and the reads go on behind it, the search
    /// running again when they land (audit 11-15 #69: the first library search waited for every page).
    /// With nothing to search yet, it waits for the reads. Nothing found while a list failed to read throws
    /// that list's failure.
    func spotifyLibrarySearch(_ term: String, _ spotify: SpotifySource) async throws -> SpotifySource.SearchResults {
        await restoreSpotifyListsFromDisk()
        let hasLocal = !(artists.isEmpty && albums.isEmpty && songs.isEmpty && playlists.isEmpty)
        let readsDone = ["albums", "artists", "playlists", "songs"].allSatisfy { phase($0) == .loaded }
            && spotifySongsNext == nil
        if hasLocal, !readsDone, let request = searchRequest {
            let gen = generation
            Task { [weak self] in
                guard let self else { return }
                await self.readSpotifyLibraryForSearch()
                guard gen == self.generation, self.spotify === spotify else { return }
                self.refreshLibrarySearch(term, request: request)
            }
            return SpotifySource.filterLibrary(term, artists: artists, albums: albums, songs: songs, playlists: playlists)
        }
        await readSpotifyLibraryForSearch()
        return try spotifyLibraryResults(term)
    }

    /// Library's lists, then the rest of Liked Songs a read at a time; a failed read (the rate limit) stops.
    func readSpotifyLibraryForSearch() async {
        await loadLibrary(nil)
        await loadSongs()
        while spotifySongsNext != nil, songsMore == .available || songsMore == .failed, !Task.isCancelled {
            let before = spotifySongsNext
            await loadMoreSongs()
            if songsMore == .failed || spotifySongsNext == before { break }
        }
    }

    /// [term] over the lists as they are; nothing found while a list failed throws its failure.
    func spotifyLibraryResults(_ term: String) throws -> SpotifySource.SearchResults {
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
        setFailureReport("searchMore:\(kind.rawValue)", error: nil)
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
            setFailureReport("searchMore:\(kind.rawValue)", error: nil)
        } catch {
            guard current() else { return }
            // The rows stay; the foot's retry reads the same page again.
            searchPaging[kind] = .failed
            setFailureReport("searchMore:\(kind.rawValue)", error: error)
        }
    }
}
