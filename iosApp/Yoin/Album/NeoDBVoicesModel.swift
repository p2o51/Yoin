import Foundation
import Observation

/// The "What others say" page's states (Android `NeoDbVoicesUiState`).
enum NeoDBVoicesState: Equatable {
    case loading
    /// NeoDB has no item like this album: the page offers a search there.
    case notFound(searchUrl: String)
    case failed
    case content(Content)

    struct Content: Equatable {
        var album: NeoDBAlbumVoices
        var voices: [NeoDBVoice]
        /// Every comment and review on the item, not only the loaded ones.
        var count: Int
        var references: [NeoDBReferenceScore]
        var loadingMore = false
        var endReached = false
        /// The next page failed: the list ends in a retry.
        var moreFailed = false
    }

    /// One identity per kind, for the cross-fade between kinds (Android `contentKey = { it::class }`).
    var kind: Int {
        switch self {
        case .loading: 0
        case .notFound: 1
        case .failed: 2
        case .content: 3
        }
    }
}

/// Android `NeoDbVoicesViewModel`: find the item, read the first 20 voices, then the other catalogues'
/// scores (they never hold the page back); the next 20 when the list nears its end.
@MainActor
@Observable
final class NeoDBVoicesModel {
    let albumName: String
    let artistName: String?
    private(set) var state: NeoDBVoicesState = .loading

    @ObservationIgnored private let service: any NeoDBVoicesReading
    @ObservationIgnored private var nextPage = 1
    @ObservationIgnored private var loadTask: Task<Void, Never>?
    @ObservationIgnored private var pageTask: Task<Void, Never>?
    /// Bumped by every load: a page from an earlier load never lands on a later one.
    @ObservationIgnored private var generation = 0

    init(albumName: String, artistName: String?, service: any NeoDBVoicesReading) {
        self.albumName = albumName
        let artist = artistName?.trimmingCharacters(in: .whitespacesAndNewlines)
        self.artistName = artist?.isEmpty == false ? artist : nil
        self.service = service
    }

    var content: NeoDBVoicesState.Content? {
        if case .content(let c) = state { return c }
        return nil
    }

    /// One load at a time: Retry stays tappable while its message fades out, and a second tap must not run a
    /// second search beside the first.
    func load() {
        loadTask?.cancel()
        pageTask?.cancel()
        pageTask = nil
        generation += 1
        let gen = generation
        state = .loading
        nextPage = 1
        loadTask = Task { [service, albumName, artistName] in
            let album: NeoDBAlbumVoices?
            do {
                album = try await service.findAlbum(albumName: albumName, artist: artistName)
            } catch {
                guard self.generation == gen, !Task.isCancelled else { return }
                self.state = .failed
                return
            }
            guard self.generation == gen, !Task.isCancelled else { return }
            guard let album else {
                let url = await service.searchUrl(albumName: albumName, artist: artistName)
                guard self.generation == gen, !Task.isCancelled else { return }
                self.state = .notFound(searchUrl: url)
                return
            }
            let first: NeoDBVoicePage
            do {
                first = try await service.voices(itemUuid: album.itemUuid, page: 1)
            } catch {
                guard self.generation == gen, !Task.isCancelled else { return }
                self.state = .failed
                return
            }
            guard self.generation == gen, !Task.isCancelled else { return }
            self.nextPage = 2
            self.state = .content(.init(album: album, voices: first.voices, count: first.count, references: [],
                                        endReached: first.page >= first.pages))
            let references = await service.referenceScores(album)
            guard self.generation == gen, !Task.isCancelled, var c = self.content else { return }
            c.references = references
            self.state = .content(c)
        }
    }

    /// The next 20, when the list nears its end (or Retry after a failed page).
    func loadMore() {
        guard var c = content, !c.loadingMore, !c.endReached, pageTask == nil else { return }
        c.loadingMore = true
        c.moreFailed = false
        state = .content(c)
        let page = nextPage
        let gen = generation
        let uuid = c.album.itemUuid
        pageTask = Task { [service] in
            defer { if self.generation == gen { self.pageTask = nil } }
            let result: Result<NeoDBVoicePage, Error>
            do { result = .success(try await service.voices(itemUuid: uuid, page: page)) } catch { result = .failure(error) }
            guard self.generation == gen, !Task.isCancelled, var current = self.content else { return }
            switch result {
            case .success(let more):
                self.nextPage = page + 1
                let known = Set(current.voices.map(\.id))
                current.voices += more.voices.filter { !known.contains($0.id) }
                current.loadingMore = false
                current.endReached = more.voices.isEmpty || more.page >= more.pages
            case .failure:
                current.loadingMore = false
                current.moreFailed = true
            }
            self.state = .content(current)
        }
    }

    /// Waits for the work in flight (tests).
    func settle() async {
        await loadTask?.value
        await pageTask?.value
    }
}
