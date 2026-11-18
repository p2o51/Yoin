import CryptoKit
import Foundation

/// Brings a Spotoolfy export into a new user's Spotify account (Android `SpotoolfyImporter`). Two steps:
/// - `stage`: read the file, map it, work out the recap. Nothing reaches an account yet; the landing shows
///   the recap before Spotify is even connected.
/// - `commit`: once Spotify is connected, write the notes and ratings into that account's scope, only if
///   the account holds no notes or ratings of the user's yet.
///
/// Notes go through `NotesStore.syncPut` and ratings through `LocalRatings.syncPut` under
/// `AccountScope.reading(asSpotify:)`, so each row carries the Spotify account's scope without that account
/// having to be the one in use. Ids are deterministic (`SpotoolfyImportMapper.noteId`), so a second device
/// importing the same file writes the same notes and cloud sync never doubles them.
@MainActor
final class SpotoolfyImporter {
    struct Staged: Sendable {
        var plan: SpotoolfyImportPlan
        var recap: SpotoolfyRecap
        /// SHA-256 of the file, to tell the same file again.
        var fileSha256: String
    }

    enum StageResult: Sendable {
        case staged(Staged)
        /// This very file is already in an account.
        case alreadyImported
        case notSpotoolfy, corrupt, tooLarge
        /// A Spotoolfy file with no note or rating of the user's own (a fresh install has only sample rows).
        case empty
    }

    /// What a commit wrote, to take back out ("use another file").
    struct Receipt: Equatable {
        var scope: String
        var noteIds: [String]
        var ratingSongIds: [String]
        var fileSha256: String
    }

    enum CommitResult: Equatable {
        case committed(noteCount: Int, ratingCount: Int, receipt: Receipt)
        /// The account already holds notes or ratings: imports only go into a new user's account.
        case notEligible
        /// Everything in the file is in Yoin already, under another account: nothing was written.
        case alreadyInYoin
        /// The disk refused the write; whatever went in was taken back.
        case failed
    }

    static let importedKey = "spotoolfy_imported_sha256_v1"

    private let notes: NotesStore
    private let ratings: LocalRatings
    private let scopes: AccountScope
    private let defaults: UserDefaults
    private let segmenter: WordSegmenter

    init(notes: NotesStore = .shared, ratings: LocalRatings = .shared, scopes: AccountScope = .shared,
         defaults: UserDefaults = .standard, segmenter: @escaping WordSegmenter = SystemWordSegmenter.words) {
        self.notes = notes
        self.ratings = ratings
        self.scopes = scopes
        self.defaults = defaults
        self.segmenter = segmenter
    }

    private var importedFiles: Set<String> {
        get { Set(defaults.stringArray(forKey: Self.importedKey) ?? []) }
        set { defaults.set(newValue.sorted(), forKey: Self.importedKey) }
    }

    nonisolated static func sha256(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    /// The same file went into an account before.
    func wasImported(sha256 sha: String) -> Bool { importedFiles.contains(sha) }

    /// Reads [data] and maps it (the "already imported" check included).
    func stage(_ data: Data) -> StageResult {
        if wasImported(sha256: Self.sha256(data)) { return .alreadyImported }
        return Self.analyze(data, segmenter: segmenter)
    }

    /// The reading itself: parse, map, recap. Pure, so a big file can be read off the main actor.
    nonisolated static func analyze(_ data: Data, segmenter: @escaping WordSegmenter = SystemWordSegmenter.words) -> StageResult {
        let export: SpotoolfyExport
        switch SpotoolfyExportParser.parse(data) {
        case .parsed(let e): export = e
        case .notSpotoolfy: return .notSpotoolfy
        case .corrupt: return .corrupt
        case .tooLarge: return .tooLarge
        }
        let plan = SpotoolfyImportMapper.map(export)
        if plan.isEmpty { return .empty }
        return .staged(Staged(plan: plan, recap: SpotoolfyRecapCalculator(segmenter: segmenter).compute(plan),
                              fileSha256: sha256(data)))
    }

    /// Whether the Spotify account under [scope] can still take an import: no Spotify notes or ratings in it yet.
    func isEligible(scope: String) -> Bool {
        scopes.reading(asSubsonic: nil, asSpotify: scope) {
            !notes.notes.contains { $0.provider == AccountScope.spotifyProvider }
                && !ratings.ratings.keys.contains { $0.hasPrefix(AccountScope.spotifyProvider + ":") }
        }
    }

    /// Writes [staged] into the Spotify account under [scope]: every note (titled [unknownTitle] when Spotoolfy
    /// had no title) and every track's latest rating, stamped with the time it was written in Spotoolfy. A note
    /// whose id another account already holds stays there and is not written.
    func commit(_ staged: Staged, scope: String, unknownTitle: String) -> CommitResult {
        guard isEligible(scope: scope) else { return .notEligible }
        let provider = AccountScope.spotifyProvider
        var writtenNotes: [String] = []
        var writtenRatings: [String] = []
        func rollback() {
            scopes.reading(asSubsonic: nil, asSpotify: scope) {
                for id in writtenNotes { notes.syncRemove(id: id) }
                for song in writtenRatings { ratings.syncRemove(provider: provider, songId: song) }
            }
        }
        let ok: Bool = scopes.reading(asSubsonic: nil, asSpotify: scope) {
            for note in staged.plan.notes where notes.syncNote(id: note.id) == nil {
                let row = SongNote(id: note.id, provider: provider, trackId: note.trackId, content: note.content,
                                   createdAt: note.recordedAt, updatedAt: note.recordedAt,
                                   title: note.title ?? unknownTitle, artist: note.artist ?? "",
                                   positionMs: note.positionMs)
                guard notes.syncPut(row) else { return false }
                writtenNotes.append(note.id)
            }
            if writtenNotes.isEmpty && staged.plan.ratings.isEmpty { return true }
            for rating in staged.plan.ratings {
                guard ratings.syncPut(rating.rating, provider: provider, songId: rating.trackId,
                                      updatedAt: rating.recordedAt) else { return false }
                writtenRatings.append(rating.trackId)
            }
            return true
        }
        guard ok else {
            rollback()
            return .failed
        }
        if writtenNotes.isEmpty && writtenRatings.isEmpty { return .alreadyInYoin }
        importedFiles.insert(staged.fileSha256)
        // These rows came from a file, not an edit, but other devices have never seen them: upload.
        NotesStore.onLocalChange?()
        LocalRatings.onLocalChange?()
        return .committed(noteCount: writtenNotes.count, ratingCount: writtenRatings.count,
                          receipt: Receipt(scope: scope, noteIds: writtenNotes, ratingSongIds: writtenRatings,
                                           fileSha256: staged.fileSha256))
    }

    /// Takes a committed import back out ("Use another file": in a new account those rows are all there is).
    func discard(_ receipt: Receipt) {
        scopes.reading(asSubsonic: nil, asSpotify: receipt.scope) {
            for id in receipt.noteIds { notes.syncRemove(id: id) }
            for song in receipt.ratingSongIds { ratings.syncRemove(provider: AccountScope.spotifyProvider, songId: song) }
        }
        importedFiles.remove(receipt.fileSha256)
        NotesStore.onLocalChange?()
        LocalRatings.onLocalChange?()
    }
}
