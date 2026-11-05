import Foundation
import Observation

/// "Manage in Scrapbook" from Now Playing's About (Android
/// ScrapbookFocusRequest): the sheet leaves this request and opens the album
/// the usual way; the album page that lists the song takes it, moves to page 2
/// and opens the song's track sheet at that row's editor. Observable, so a
/// page already open behind Now Playing takes it too. A request nobody takes
/// within `freshFor` is dropped, so it can't hijack a later visit.
@MainActor
@Observable
final class ScrapbookFocusRequest {
    static let shared = ScrapbookFocusRequest()

    struct Pending: Equatable {
        var songId: MediaId
        var at: Date
        /// The About row whose editor opens (nil: just the song's sheet).
        var edit: SongAboutStore.Entry.Ref?
    }

    static let freshFor: TimeInterval = 10

    private(set) var pending: Pending?

    func request(songId: MediaId, edit: SongAboutStore.Entry.Ref? = nil, now: Date = Date()) {
        pending = Pending(songId: songId, at: now, edit: edit)
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
