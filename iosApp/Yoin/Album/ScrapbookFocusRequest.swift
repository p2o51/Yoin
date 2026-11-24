import Foundation
import Observation

/// "Manage in Scrapbook" from Now Playing's About (Android
/// ScrapbookFocusRequest): the sheet leaves this request and opens the album
/// the usual way; the album page that lists the song takes it, moves to page 2
/// and opens the song's track sheet at that row's editor. Observable, so a
/// page already open behind Now Playing takes it too. A request nobody takes
/// within `freshFor` is dropped, so it can't hijack a later visit.
///
/// A note's album mention asks for page 2 on no particular song
/// (`requestAlbum`, Android requestAlbum): the album page that is that album
/// moves there.
@MainActor
@Observable
final class ScrapbookFocusRequest {
    static let shared = ScrapbookFocusRequest()

    struct Pending: Equatable {
        var songId: MediaId
        var at: Date
        /// The About row whose editor opens (nil: just the song's sheet).
        var edit: SongAboutStore.Entry.Ref?
        /// The song's sheet opens (Now Playing's Manage); false: the page only scrolls to the song's
        /// piece and points it out (the song menu's View in Scrapbook, Android onViewInScrapbook).
        var openSheet = true
    }

    static let freshFor: TimeInterval = 10

    private(set) var pending: Pending?

    func request(songId: MediaId, edit: SongAboutStore.Entry.Ref? = nil, openSheet: Bool = true, now: Date = Date()) {
        pending = Pending(songId: songId, at: now, edit: edit, openSheet: openSheet || edit != nil)
    }

    struct PendingAlbum: Equatable {
        var albumId: MediaId
        var at: Date
    }

    /// The album-level request waiting to be taken, if any.
    private(set) var pendingAlbum: PendingAlbum?

    /// Page 2 of the album `albumId`, on no particular song.
    func requestAlbum(_ albumId: MediaId, now: Date = Date()) {
        pendingAlbum = PendingAlbum(albumId: albumId, at: now)
    }

    /// Whether the album-level request is for `albumId` and still fresh; leaves it in place (safe in a body).
    func wantsAlbum(_ albumId: MediaId, now: Date = Date()) -> Bool {
        guard let request = pendingAlbum else { return false }
        return request.albumId == albumId && now.timeIntervalSince(request.at) <= Self.freshFor
    }

    /// Takes the album-level request when it is for `albumId` and still fresh.
    func takeAlbum(_ albumId: MediaId, now: Date = Date()) -> Bool {
        guard let request = pendingAlbum else { return false }
        if now.timeIntervalSince(request.at) > Self.freshFor {
            pendingAlbum = nil
            return false
        }
        guard request.albumId == albumId else { return false }
        pendingAlbum = nil
        return true
    }

    /// Takes the request when it is for one of `songIds` and still fresh; nil otherwise.
    func take(for songIds: [MediaId], now: Date = Date()) -> Pending? {
        guard let request = pending else { return nil }
        if now.timeIntervalSince(request.at) > Self.freshFor {
            pending = nil
            return nil
        }
        guard songIds.contains(request.songId) else { return nil }
        pending = nil
        return request
    }
}
