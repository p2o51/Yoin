import Foundation

/// Library › Songs where the service lists no songs of its own (Subsonic),
/// ported from Android ui/library/NewestAlbumSongsPager.kt: its albums'
/// songs, the most recently added album first (`getAlbumList2` newest), each
/// album's songs in the album's own order.
///
/// Each `next()` reads one page of `albumsPerPage` albums and opens them
/// (`parallelism` at once) through the album page's cache, then hands back
/// their songs in album order. A song comes once however many albums or
/// pages hold it; an album opens once. A page starts `reread` albums before
/// where the last one ended, so albums deleted meanwhile don't let the next
/// one slip past unread; albums added meanwhile (at the top) are left for a
/// refresh. A page whose albums hold no new song reads on, so an empty
/// answer means the end. A read that fails leaves the pager where it was;
/// an album that fails on two reads in a row is left out.
@MainActor
final class NewestAlbumSongsPager {
    typealias LoadAlbums = @MainActor (_ offset: Int, _ size: Int) async throws -> [Album]
    typealias LoadAlbum = @MainActor (_ album: Album) async throws -> Album?

    static let albumsPerPage = 10
    static let albumsOpenedAtOnce = 3
    static let albumsReread = 5

    private let loadAlbums: LoadAlbums
    private let loadAlbum: LoadAlbum
    private let albumsPerPage: Int
    private let parallelism: Int
    private let reread: Int

    private var albumOffset = 0
    private var openedAlbums = Set<MediaId>()
    private var listedSongs = Set<MediaId>()
    private var failedAlbums = Set<MediaId>()

    /// Every album has been read: `next()` has nothing more to give.
    private(set) var reachedEnd = false

    init(albumsPerPage: Int = albumsPerPage, parallelism: Int = albumsOpenedAtOnce, reread: Int = albumsReread,
         loadAlbums: @escaping LoadAlbums, loadAlbum: @escaping LoadAlbum) {
        self.albumsPerPage = albumsPerPage
        self.parallelism = parallelism
        self.reread = reread
        self.loadAlbums = loadAlbums
        self.loadAlbum = loadAlbum
    }

    /// The next songs in list order, none already given; empty only at the end.
    func next() async throws -> [PlaylistEntry] {
        if reachedEnd { return [] }
        var failedNow = Set<MediaId>()
        do {
            let songs = try await read(&failedNow)
            failedAlbums.removeAll()
            return songs
        } catch {
            if !(error is CancellationError) { failedAlbums.formUnion(failedNow) }
            throw error
        }
    }

    private func read(_ failedNow: inout Set<MediaId>) async throws -> [PlaylistEntry] {
        var offset = albumOffset
        var albums = Set<MediaId>()
        var songIds = Set<MediaId>()
        var songs: [PlaylistEntry] = []
        var end = false
        while songs.isEmpty && !end {
            let from = max(0, offset - reread)
            let size = albumsPerPage + (offset - from)
            let page = try await loadAlbums(from, size)
            offset = from + page.count
            end = page.count < size
            // An unread album above the last one read was added since.
            let lastRead = page.lastIndex { openedAlbums.contains($0.id) || albums.contains($0.id) } ?? -1
            let unopened = page.dropFirst(lastRead + 1).filter { !openedAlbums.contains($0.id) && albums.insert($0.id).inserted }
            for album in try await open(Array(unopened), &failedNow) {
                for track in album.tracks where !listedSongs.contains(track.id) && songIds.insert(track.id).inserted {
                    songs.append(PlaylistEntry(album: album, track: track))
                }
            }
        }
        // Only a whole read moves the pager on.
        albumOffset = offset
        openedAlbums.formUnion(albums)
        listedSongs.formUnion(songIds)
        reachedEnd = end
        return songs
    }

    /// The albums opened, in their order, `parallelism` at a time. One that
    /// fails fails the read and is noted, unless it failed on the last read
    /// too: then it is left out, as one the service no longer has (nil) is.
    private func open(_ albums: [Album], _ failedNow: inout Set<MediaId>) async throws -> [Album] {
        var opened: [Album?] = []
        var start = 0
        while start < albums.count {
            let chunk = Array(albums[start..<min(start + parallelism, albums.count)])
            start += chunk.count
            // Started together; their network waits overlap on the main actor.
            let load = loadAlbum
            let tasks = chunk.map { album in Task { @MainActor in try await load(album) } }
            var firstError: Error?
            for (album, task) in zip(chunk, tasks) {
                do {
                    opened.append(try await withTaskCancellationHandler { try await task.value } onCancel: { task.cancel() })
                } catch is CancellationError {
                    throw CancellationError()
                } catch {
                    if failedAlbums.contains(album.id) { opened.append(nil); continue }
                    failedNow.insert(album.id)
                    firstError = firstError ?? error
                }
            }
            if let firstError { throw firstError }
        }
        return opened.compactMap { $0 }
    }
}
