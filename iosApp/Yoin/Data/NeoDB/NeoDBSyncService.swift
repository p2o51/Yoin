import Foundation

/// Album rating + words ↔ NeoDB Mark + Review — Android `NeoDBSyncService.kt`.
///
/// - Rating: Yoin's 0–10 in tenths becomes NeoDB's whole `rating_grade` (rounded half up).
/// - Words: Yoin's one album text goes by length (owner R2, 2026-10-06): up to 360 characters as the
///   Mark's short comment (and the Review is deleted), longer as the Review's body (and the short comment
///   cleared); cleared words clear both. A pull reads the Review first, else the short comment.
/// - The Mark is rewritten whole by a POST, so it is read first and its tags, comment, shelf and
///   visibility are kept.
/// - The album's NeoDB uuid is cached in NeoDBMappingStore; a miss searches "title artist".
@MainActor
final class NeoDBSyncService {
    static let shared = NeoDBSyncService()

    enum Failure: Error, Equatable {
        case notSignedIn
        case albumNotFound
    }

    private let api: NeoDBAPI
    private let account: NeoDBAccount
    private let albums: AlbumMemoryStore
    private let pending: NeoDBPendingStore
    private let mappings: NeoDBMappingStore
    private let now: () -> Int64

    init(api: NeoDBAPI = NeoDBAPI(), account: NeoDBAccount = .shared, albums: AlbumMemoryStore = .shared,
         pending: NeoDBPendingStore = .shared, mappings: NeoDBMappingStore = .shared,
         now: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) {
        self.api = api
        self.account = account
        self.albums = albums
        self.pending = pending
        self.mappings = mappings
        self.now = now
    }

    var isConfigured: Bool { account.isSignedIn }

    /// Sends the album's changed rating and / or words. Nothing changed (or nothing stored): no request.
    /// A flag clears only when the album still holds what was sent, so an edit made during the requests
    /// keeps its flag and goes with the next push.
    func push(_ album: Album) async throws {
        guard let session = account.session else { throw Failure.notSignedIn }
        let provider = album.id.provider, albumId = album.id.rawId
        guard let local = albums.album(provider: provider, albumId: albumId) else { return }
        let flags = pending.entry(provider: provider, albumId: albumId)
        let ratingDirty = flags?.ratingNeedsSync ?? false
        let reviewDirty = flags?.reviewNeedsSync ?? false
        guard ratingDirty || reviewDirty else { return }

        guard let itemUuid = try await resolveAlbumUuid(album, session: session) else { throw Failure.albumNotFound }

        let text = local.review ?? ""
        let short = text.neoDBHasText && isNeoDBShortComment(text)
        let existing = try await api.getShelfItem(instance: session.instance, token: session.token, itemUuid: itemUuid)
        let commentText: String? = if !reviewDirty { existing?.commentText } else if short { text } else { "" }
        let mark = NeoDBShelfMarkRequest(
            shelfType: existing?.shelfType ?? "complete",
            visibility: existing?.visibility ?? 0,
            ratingGrade: ratingDirty || existing?.ratingGrade == nil ? Self.grade(local.rating) : existing?.ratingGrade,
            // Long or cleared: the short comment goes ("" says so on purpose).
            commentText: commentText,
            tags: existing?.tags ?? []
        )
        try await api.postShelfMark(instance: session.instance, token: session.token, itemUuid: itemUuid, body: mark)

        if reviewDirty {
            let newUuid: String?
            if !text.neoDBHasText || short {
                // Cleared or now short: the Review goes. 404 = already gone; any other failure throws and
                // the flag stays for a retry.
                do {
                    try await api.deleteReview(instance: session.instance, token: session.token, itemUuid: itemUuid)
                } catch let error as NeoDBError where error.code == 404 {}
                newUuid = nil
            } else {
                let response = try await api.postReview(
                    instance: session.instance, token: session.token, itemUuid: itemUuid,
                    body: NeoDBReviewRequest(visibility: 0, title: album.title, body: text))
                newUuid = response.uuid ?? flags?.reviewUuid
            }
            if albums.album(provider: provider, albumId: albumId)?.review == local.review {
                pending.reviewPushed(provider: provider, albumId: albumId, reviewUuid: newUuid)
            }
        }
        if ratingDirty, albums.album(provider: provider, albumId: albumId)?.rating == local.rating {
            pending.ratingPushed(provider: provider, albumId: albumId)
        }
    }

    /// NeoDB's Mark + Review into the album's local row. Local changes not pushed yet win; nothing on
    /// either side writes no row. Returns the row as stored.
    @discardableResult
    func pull(_ album: Album) async throws -> AlbumMemoryStore.AlbumRecord? {
        guard let session = account.session else { throw Failure.notSignedIn }
        let provider = album.id.provider, albumId = album.id.rawId
        guard let itemUuid = try await resolveAlbumUuid(album, session: session) else { return nil }
        let remote = try await api.getShelfItem(instance: session.instance, token: session.token, itemUuid: itemUuid)
        let existing = albums.album(provider: provider, albumId: albumId)
        let flags = pending.entry(provider: provider, albumId: albumId)
        let ratingDirty = flags?.ratingNeedsSync ?? false
        let reviewDirty = flags?.reviewNeedsSync ?? false

        let mergedRating: Double = if let existing, ratingDirty {
            existing.rating
        } else if let grade = remote?.ratingGrade {
            Double(grade)
        } else {
            existing?.rating ?? 0
        }

        // Any failure reading the Review: keep what is here.
        let review = try? await api.getMyReviewForItem(instance: session.instance, token: session.token, itemUuid: itemUuid)
        let remoteText = review?.body.flatMap { $0.neoDBHasText ? $0 : nil }
            ?? remote?.commentText.flatMap { $0.neoDBHasText ? $0 : nil }
        let mergedReview: String? = if reviewDirty {
            existing?.review
        } else if let remoteText {
            remoteText
        } else if let review {
            review.body
        } else {
            existing?.review
        }
        let mergedUuid: String? = if reviewDirty {
            flags?.reviewUuid
        } else if let review {
            review.uuid ?? flags?.reviewUuid
        } else {
            flags?.reviewUuid
        }

        guard mergedRating > 0 || (mergedReview?.neoDBHasText ?? false) || existing != nil else { return nil }

        let t = now()
        let reviewAt: Int64? = if !(mergedReview?.neoDBHasText ?? false) {
            nil
        } else if mergedReview == existing?.review {
            existing?.reviewUpdatedAt ?? t
        } else {
            t
        }
        let record = AlbumMemoryStore.AlbumRecord(provider: provider, albumId: albumId, rating: mergedRating,
                                                  review: mergedReview, reviewUpdatedAt: reviewAt, updatedAt: t)
        albums.putAlbum(record)
        pending.setReviewUuid(mergedUuid, provider: provider, albumId: albumId)
        return albums.album(provider: provider, albumId: albumId) ?? record
    }

    /// Kotlin `roundToInt().coerceIn(0, 10)`.
    static func grade(_ rating: Double) -> Int {
        min(max(Int((rating).rounded(.toNearestOrAwayFromZero)), 0), 10)
    }

    /// The cached uuid, else the first search hit for "title artist" (then cached).
    private func resolveAlbumUuid(_ album: Album, session: (instance: String, token: String)) async throws -> String? {
        if let cached = mappings.externalId(provider: album.id.provider, entityId: album.id.rawId) { return cached }
        let query = (!album.artist.neoDBHasText ? album.title : "\(album.title) \(album.artist)")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return nil }
        let hits = try await api.searchAlbum(instance: session.instance, token: session.token, query: query)
        guard let uuid = hits.first?.uuid else { return nil }
        mappings.upsert(externalId: uuid, provider: album.id.provider, entityId: album.id.rawId, now: now())
        return uuid
    }
}
