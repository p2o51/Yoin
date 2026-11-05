import Foundation
import YoinShared

// Immutable Kotlin data classes (shared sync rows).
extension TrackRatingRow: @retroactive @unchecked Sendable {}
extension AlbumRatingRow: @retroactive @unchecked Sendable {}
extension SongNoteRow: @retroactive @unchecked Sendable {}
extension AlbumTitleRow: @retroactive @unchecked Sendable {}
extension MemoryCopyRow: @retroactive @unchecked Sendable {}
extension SongAboutRow: @retroactive @unchecked Sendable {}
extension SyncProfile: @retroactive @unchecked Sendable {}
extension LyricsTranslationRow: @retroactive @unchecked Sendable {}
extension LyricsCacheRow: @retroactive @unchecked Sendable {}

/// The iOS stores as cloud sync's `SyncDomain` (shared Kotlin,
/// `com.gpo.yoin.shared.sync.adapters.SyncDomain`): the shared adapters read
/// and write exactly the fields Android's adapters do, through here.
///
/// iOS has one account per provider at a time (ServerSession's Subsonic
/// server, Apple Music), so a local profile id names a provider. The stores
/// keep each Subsonic row under the server + user it was written for
/// (`AccountScope`) and show only the signed-in account's, so what this bridge
/// reads and writes is that account's alone. A different Subsonic server under
/// the same id changes the fingerprint, which pauses its binding (Android's
/// "Account changed": keep as before, or start fresh) while the old server's
/// rows stay on disk, out of sight, under their own scope.
///
/// Writes return true only once the store has them on disk (Codex C15).
///
/// The Kotlin coordinator calls in on the main dispatcher only.
final class SyncDomainBridge: NSObject, SyncDomain, @unchecked Sendable {
    static let subsonicProfileId = "ios-subsonic"
    static let appleMusicProfileId = "ios-applemusic"

    struct Accounts: Sendable {
        var subsonic: (url: String, username: String)?
        var appleMusic: Bool
        var storefront: String?
    }

    private let accounts: @MainActor () -> Accounts

    init(accounts: @escaping @MainActor () -> Accounts) {
        self.accounts = accounts
    }

    static func provider(of profileId: String) -> String? {
        switch profileId {
        case subsonicProfileId: "subsonic"
        case appleMusicProfileId: AppleMusicMapping.provider
        default: nil
        }
    }

    static func profileId(of provider: String) -> String? {
        switch provider {
        case "subsonic": subsonicProfileId
        case AppleMusicMapping.provider: appleMusicProfileId
        default: nil
        }
    }

    /// Kotlin calls in on the main thread (Dispatchers.Main); Kotlin values aren't Sendable, so the
    /// result is carried out of the isolated closure by hand.
    private func main<T>(_ body: @MainActor () -> T) -> T {
        nonisolated(unsafe) var out: T?
        MainActor.assumeIsolated { out = body() }
        return out!
    }

    /// When this device first saw the profile (Android's Profile.createdAt), kept in UserDefaults.
    private static func createdAt(_ id: String) -> Int64 {
        let key = "syncProfileCreatedAt.\(id)"
        if let v = UserDefaults.standard.object(forKey: key) as? NSNumber { return v.int64Value }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        UserDefaults.standard.set(NSNumber(value: now), forKey: key)
        return now
    }

    // MARK: Profiles

    func profiles() -> [SyncProfile] {
        main {
            let a = accounts()
            var out: [SyncProfile] = []
            if let s = a.subsonic {
                out.append(SyncProfile(id: Self.subsonicProfileId, provider: "subsonic", displayName: s.username,
                                       createdAt: Self.createdAt(Self.subsonicProfileId), credentialsReadable: true,
                                       subsonicServerUrl: s.url, subsonicUsername: s.username, spotifyUserId: nil,
                                       storefront: nil))
            }
            if a.appleMusic {
                out.append(SyncProfile(id: Self.appleMusicProfileId, provider: AppleMusicMapping.provider,
                                       displayName: "Apple Music", createdAt: Self.createdAt(Self.appleMusicProfileId),
                                       credentialsReadable: true, subsonicServerUrl: nil, subsonicUsername: nil,
                                       spotifyUserId: nil, storefront: a.storefront))
            }
            return out
        }
    }

    // MARK: Track ratings

    func trackRatings(profileId: String) -> [TrackRatingRow] {
        main {
            guard let provider = Self.provider(of: profileId) else { return [] }
            let store = LocalRatings.shared
            // Cleared rows go too (rating nil → the wire's bare `rating: 0`).
            return store.ratings.keys.compactMap { key in
                guard let p = LocalRatings.parts(of: key), p.provider == provider else { return nil }
                return TrackRatingRow(provider: p.provider, songId: p.songId,
                                      rating: store.rating(key: key).map { KotlinDouble(value: $0) },
                                      updatedAt: store.updatedAt[key] ?? 0)
            }
        }
    }

    func trackRating(profileId: String, provider: String, songId: String) -> TrackRatingRow? {
        main {
            let store = LocalRatings.shared
            let key = LocalRatings.key(provider: provider, songId: songId)
            guard Self.provider(of: profileId) == provider, store.hasRow(key: key) else { return nil }
            return TrackRatingRow(provider: provider, songId: songId,
                                  rating: store.rating(key: key).map { KotlinDouble(value: $0) },
                                  updatedAt: store.updatedAt[key] ?? 0)
        }
    }

    // Writes: true only once on disk (the stores change nothing otherwise), so sync retries a failure.

    func putTrackRating(profileId: String, row: TrackRatingRow) -> Bool {
        main {
            LocalRatings.shared.syncPut(row.rating?.doubleValue, provider: row.provider, songId: row.songId,
                                        updatedAt: row.updatedAt)
        }
    }

    func deleteTrackRating(profileId: String, provider: String, songId: String) -> Bool {
        main { LocalRatings.shared.syncRemove(provider: provider, songId: songId) }
    }

    // MARK: Album rating + review

    private static func row(_ r: AlbumMemoryStore.AlbumRecord) -> AlbumRatingRow {
        AlbumRatingRow(provider: r.provider, albumId: r.albumId, rating: r.rating, review: r.review,
                       reviewUpdatedAt: r.reviewUpdatedAt.map { KotlinLong(value: $0) }, updatedAt: r.updatedAt)
    }

    func albumRatings(profileId: String) -> [AlbumRatingRow] {
        main {
            guard let provider = Self.provider(of: profileId) else { return [] }
            return AlbumMemoryStore.shared.albums.values.filter { $0.provider == provider }.map(Self.row)
        }
    }

    func albumRating(profileId: String, provider: String, albumId: String) -> AlbumRatingRow? {
        main {
            guard Self.provider(of: profileId) == provider else { return nil }
            return AlbumMemoryStore.shared.album(provider: provider, albumId: albumId).map(Self.row)
        }
    }

    func putAlbumRating(profileId: String, row: AlbumRatingRow) -> Bool {
        main {
            AlbumMemoryStore.shared.putAlbum(.init(provider: row.provider, albumId: row.albumId, rating: row.rating,
                                                   review: row.review, reviewUpdatedAt: row.reviewUpdatedAt?.int64Value,
                                                   updatedAt: row.updatedAt))
        }
    }

    func deleteAlbumRating(profileId: String, provider: String, albumId: String) -> Bool {
        main { AlbumMemoryStore.shared.removeAlbum(provider: provider, albumId: albumId) }
    }

    // MARK: Notes

    private static func row(_ n: SongNote) -> SongNoteRow? {
        guard let profile = profileId(of: n.provider) else { return nil }
        return SongNoteRow(id: n.id, profileId: profile, trackId: n.trackId, provider: n.provider, content: n.content,
                           createdAt: n.createdAt, updatedAt: n.updatedAt, title: n.title, artist: n.artist,
                           positionMs: n.positionMs.map { KotlinLong(value: $0) })
    }

    func notes(profileId: String) -> [SongNoteRow] {
        main { NotesStore.shared.notes.compactMap(Self.row).filter { $0.profileId == profileId } }
    }

    func note(id: String) -> SongNoteRow? {
        main { NotesStore.shared.note(id: id).flatMap(Self.row) }
    }

    func putNote(row: SongNoteRow) -> Bool {
        main {
            NotesStore.shared.syncPut(SongNote(id: row.id, provider: row.provider, trackId: row.trackId,
                                               content: row.content, createdAt: row.createdAt, updatedAt: row.updatedAt,
                                               title: row.title, artist: row.artist,
                                               positionMs: row.positionMs?.int64Value))
        }
    }

    func deleteNote(id: String, profileId: String) -> Bool {
        main {
            guard let n = NotesStore.shared.note(id: id), Self.profileId(of: n.provider) == profileId else { return true }
            return NotesStore.shared.syncRemove(id: id)
        }
    }

    // MARK: Album titles

    func albumTitles(profileId: String) -> [AlbumTitleRow] {
        main {
            guard let provider = Self.provider(of: profileId) else { return [] }
            return AlbumMemoryStore.shared.titles.values.filter { $0.provider == provider }
                .map { AlbumTitleRow(provider: $0.provider, albumId: $0.albumId, title: $0.title, updatedAt: $0.updatedAt) }
        }
    }

    func albumTitle(profileId: String, provider: String, albumId: String) -> AlbumTitleRow? {
        main {
            guard Self.provider(of: profileId) == provider,
                  let t = AlbumMemoryStore.shared.title(provider: provider, albumId: albumId) else { return nil }
            return AlbumTitleRow(provider: t.provider, albumId: t.albumId, title: t.title, updatedAt: t.updatedAt)
        }
    }

    func putAlbumTitle(profileId: String, row: AlbumTitleRow) -> Bool {
        main {
            AlbumMemoryStore.shared.putTitle(.init(provider: row.provider, albumId: row.albumId, title: row.title,
                                                   updatedAt: row.updatedAt))
        }
    }

    func deleteAlbumTitle(profileId: String, provider: String, albumId: String) -> Bool {
        main { AlbumMemoryStore.shared.removeTitle(provider: provider, albumId: albumId) }
    }

    // MARK: Memory copy / AI title

    private static func row(_ m: AlbumMemoryStore.MemoryCopy) -> MemoryCopyRow {
        MemoryCopyRow(provider: m.provider, entityType: m.entityType, entityId: m.entityId, copyText: m.copy,
                      promptHash: m.promptHash, generatedAt: m.generatedAt, title: m.title,
                      titlePromptHash: m.titlePromptHash)
    }

    func memoryCopies(profileId: String) -> [MemoryCopyRow] {
        main {
            guard let provider = Self.provider(of: profileId) else { return [] }
            return AlbumMemoryStore.shared.memories.values.filter { $0.provider == provider }.map(Self.row)
        }
    }

    func memoryCopy(profileId: String, provider: String, entityType: String, entityId: String) -> MemoryCopyRow? {
        main {
            guard Self.provider(of: profileId) == provider else { return nil }
            return AlbumMemoryStore.shared.memory(provider: provider, entityType: entityType, entityId: entityId).map(Self.row)
        }
    }

    func putMemoryCopy(profileId: String, row: MemoryCopyRow) -> Bool {
        main {
            AlbumMemoryStore.shared.putMemory(.init(provider: row.provider, entityType: row.entityType,
                                                    entityId: row.entityId, copy: row.copyText, promptHash: row.promptHash,
                                                    generatedAt: row.generatedAt, title: row.title,
                                                    titlePromptHash: row.titlePromptHash))
        }
    }

    // MARK: About / Ask (Data/About/SongAboutStore, the Gemini page's cache)

    /// Android normalizeTargetLanguage: rows sync per language; this device applies its own.
    func aboutLanguage() -> String {
        main { () -> String in GeminiSettings.normalizeLanguage(GeminiSettings.shared.targetLanguage) }
    }

    private static func row(_ e: SongAboutStore.Entry) -> SongAboutRow {
        SongAboutRow(titleKey: e.titleKey, artistKey: e.artistKey, albumKey: e.albumKey, titleDisplay: e.titleDisplay,
                     artistDisplay: e.artistDisplay, albumDisplay: e.albumDisplay, kind: e.kind, entryKey: e.entryKey,
                     promptText: e.promptText, titleText: e.titleText, answerText: e.answerText,
                     createdAt: e.createdAt, updatedAt: e.updatedAt)
    }

    /// Rows in this language, plus the listener's own entries (filed under "*", any language, as
    /// on Android; the shared adapter stamps them so). Unstamped rows from before stay out of sync
    /// rather than be published under a language they may not be in (Codex C17).
    private static func current(_ e: SongAboutStore.Entry, _ language: String) -> Bool {
        e.kind == SongAboutStore.Kind.own || e.language == language
    }

    func songAbouts() -> [SongAboutRow] {
        main {
            let language = GeminiSettings.normalizeLanguage(GeminiSettings.shared.targetLanguage)
            return SongAboutStore.shared.all.filter { Self.current($0, language) }.map(Self.row)
        }
    }

    func songAbout(titleKey: String, artistKey: String, albumKey: String, kind: String, entryKey: String) -> SongAboutRow? {
        main {
            let language = GeminiSettings.normalizeLanguage(GeminiSettings.shared.targetLanguage)
            return SongAboutStore.shared.all.first {
                $0.titleKey == titleKey && $0.artistKey == artistKey && $0.albumKey == albumKey &&
                    $0.kind == kind && $0.entryKey == entryKey && Self.current($0, language)
            }.map(Self.row)
        }
    }

    /// The shared adapter applies a record only when its language is this device's (`aboutLanguage`), so
    /// the row is stamped with it; an own entry is the listener's, shown in every language.
    func putSongAbout(row: SongAboutRow) -> Bool {
        main {
            let language = row.kind == SongAboutStore.Kind.own ? SongAboutStore.anyLanguage
                : GeminiSettings.normalizeLanguage(GeminiSettings.shared.targetLanguage)
            return SongAboutStore.shared.syncUpsert(.init(titleKey: row.titleKey, artistKey: row.artistKey,
                                                          albumKey: row.albumKey, titleDisplay: row.titleDisplay,
                                                          artistDisplay: row.artistDisplay, albumDisplay: row.albumDisplay,
                                                          kind: row.kind, entryKey: row.entryKey,
                                                          promptText: row.promptText, titleText: row.titleText,
                                                          answerText: row.answerText, createdAt: row.createdAt,
                                                          updatedAt: row.updatedAt, language: language))
        }
    }

    func songAboutEntryCount() -> Int32 {
        main { Int32(SongAboutStore.shared.all.count) }
    }

    // MARK: Settings (Android `setting`: iOS has translation_language only)

    func putTranslationLanguage(language: String) -> Bool {
        main { GeminiSettings.shared.syncSetLanguage(language) }
    }

    // MARK: Lyric translations (Data/Lyrics LyricsUserStore, Android lyrics_translation_cache)

    private static func row(_ r: LyricsUserStore.TranslationRow) -> LyricsTranslationRow {
        LyricsTranslationRow(trackProvider: r.trackProvider, trackRawId: r.trackRawId, sourceHash: r.sourceHash,
                             targetLanguage: r.targetLanguage, model: r.model, translationsJson: r.translationsJson,
                             cachedAt: r.cachedAt)
    }

    func paidLyricsTranslations() -> [LyricsTranslationRow] {
        main { LyricsUserStore.shared.paidTranslations.map(Self.row) }
    }

    func lyricsTranslation(trackProvider: String, trackRawId: String, sourceHash: String, targetLanguage: String,
                           model: String) -> LyricsTranslationRow? {
        main {
            LyricsUserStore.shared.translation(trackProvider: trackProvider, trackRawId: trackRawId,
                                               sourceHash: sourceHash, targetLanguage: targetLanguage,
                                               model: model).map(Self.row)
        }
    }

    func insertLyricsTranslationIfAbsent(row: LyricsTranslationRow) -> Bool {
        main {
            LyricsUserStore.shared.syncInsertIfAbsent(.init(trackProvider: row.trackProvider, trackRawId: row.trackRawId,
                                                            sourceHash: row.sourceHash,
                                                            targetLanguage: row.targetLanguage, model: row.model,
                                                            translationsJson: row.translationsJson,
                                                            cachedAt: row.cachedAt))
        }
    }

    func deleteLyricsTranslation(trackProvider: String, trackRawId: String, sourceHash: String,
                                 targetLanguage: String, model: String) -> Bool {
        main {
            LyricsUserStore.shared.syncRemoveTranslation(
                key: LyricsUserStore.rowKey(trackProvider, trackRawId, sourceHash, targetLanguage, model))
        }
    }

    func lyricsCache(trackProvider: String, trackRawId: String) -> LyricsCacheRow? {
        main {
            LyricsUserStore.shared.cachedLyrics(LocalRatings.key(provider: trackProvider, songId: trackRawId)).map {
                LyricsCacheRow(trackProvider: trackProvider, trackRawId: trackRawId, lyricsProvider: $0.lyricsProvider,
                               lyricsProviderSongId: $0.lyricsProviderSongId, lrc: $0.lrc, cachedAt: $0.cachedAt)
            }
        }
    }

    func writeLyricsCache(row: LyricsCacheRow) -> Bool {
        main {
            LyricsUserStore.shared.syncWriteLyrics(
                .init(lyricsProvider: row.lyricsProvider, lyricsProviderSongId: row.lyricsProviderSongId, lrc: row.lrc,
                      cachedAt: row.cachedAt),
                for: LocalRatings.key(provider: row.trackProvider, songId: row.trackRawId))
        }
    }
}
