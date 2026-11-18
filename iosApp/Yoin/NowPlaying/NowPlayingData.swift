import Foundation
import Observation
import YoinShared

/// What Now Playing shows about the engine's song beyond the queue entry:
/// its lyrics (fetched once per song), the heart and the rating. Android
/// splits this over NowPlayingViewModel (lyricsState, favoriteFlow,
/// ratingFlow) and YoinRepository (setRating / star writes).
///
/// Lives as long as the app (`shared`), so collapsing Now Playing keeps the
/// lyrics already fetched.
@MainActor
@Observable
final class NowPlayingData {
    static let shared = NowPlayingData()

    enum LyricsState: Equatable {
        case loading
        case none
        case lines(LoadedLyrics)
    }

    /// Kotlin `Lyrics` plus its line texts, ready for the view.
    struct LoadedLyrics: Equatable {
        let lyrics: Lyrics
        let lines: [String]
        /// The credits the parse took out of the lines (Android `Lyrics.credits`), for the spine and About.
        let credits: SongCredits?
        var synced: Bool { lyrics.synced }

        init(_ lyrics: Lyrics) {
            self.lyrics = lyrics
            lines = lyrics.lines.map(\.text)
            credits = SongCredits(lyrics.credits)
        }

        /// -1 = before the first line, or unsynced.
        func activeIndex(positionSec: Double) -> Int {
            guard synced else { return -1 }
            return Int(lyrics.lineIndexAt(positionMs: Int64((positionSec * 1000).rounded())))
        }

        static func == (a: LoadedLyrics, b: LoadedLyrics) -> Bool { a.lyrics === b.lyrics }
    }

    private(set) var lyrics: [String: LyricsState] = [:]
    /// Where each song's shown lyrics came from (the translate tool asks the
    /// same provider for its own translation first).
    private(set) var lyricsSource: [String: LyricsSource] = [:]
    /// QQ → NetEase → LRCLIB, for songs whose service has no lyrics of its own.
    @ObservationIgnored let registry = LyricsRegistry()
    /// The heart lives in `FavoritesStore` (shared with the album rows);
    /// this side reports what the server says about the song.
    @ObservationIgnored private let hearts: FavoritesStore
    private(set) var serverRating: [String: Int] = [:]
    /// The rating under the finger, until it is committed on release.
    private(set) var draftRating: [String: Double] = [:]

    /// The About page's server facts per song (`getSong` on Subsonic; the
    /// queue entry elsewhere). `AboutState` (NowPlayingAbout.swift) adds Gemini.
    private(set) var about: [String: AboutState] = [:]

    struct Dependencies {
        var autoFetch: @MainActor (_ title: String, _ artist: String, _ durationMs: Int64?) async throws -> LyricsResult?
        var musicBrainzNames: @Sendable (_ isrc: String, _ durationMs: Int64?) async throws -> [(title: String, artist: String)]
        var aiHasKey: @MainActor () -> Bool
        var aiResolveReleaseNames: @Sendable (
            _ title: String, _ artist: String, _ album: String?, _ isrc: String?, _ durationMs: Int64?,
            _ otherNames: [(title: String, artist: String)]
        ) async throws -> (title: String, artist: String)?
        var acceptLanguages: @Sendable (_ isrc: String?) -> [String]
    }

    private struct NamePair: Hashable {
        var title: String
        var artist: String
    }

    private struct NonAiFallbackMeta {
        var isrc: String?
        var nonLatinShown: Bool
        var otherNames: [(title: String, artist: String)]
    }

    @ObservationIgnored private let ratings: LocalRatings
    @ObservationIgnored private let outbox: RatingOutbox
    @ObservationIgnored private let userStore: LyricsUserStore
    @ObservationIgnored private let deps: Dependencies
    @ObservationIgnored private(set) var lyricsRetry: Set<String> = []
    @ObservationIgnored private var infoRequested: Set<String> = []
    @ObservationIgnored private var inFlightProviderLoads: [String: Task<Void, Never>] = [:]
    @ObservationIgnored private var activeSongUpgradeRequested: Set<String> = []
    @ObservationIgnored private var nonAiExhausted: [String: NonAiFallbackMeta] = [:]
    @ObservationIgnored private var aiAttempted: Set<String> = []
    @ObservationIgnored private var releaseNamesCache: [String: NamePair?] = [:]
    /// Bumps when the Subsonic account changes: a song id is the server's own, so what was loaded for
    /// another account's song with the same id goes, and loads begun before land nowhere (Codex #9).
    @ObservationIgnored private var accountGeneration = 0

    init(
        ratings: LocalRatings? = nil,
        hearts: FavoritesStore? = nil,
        outbox: RatingOutbox? = nil,
        userStore: LyricsUserStore? = nil,
        dependencies: Dependencies? = nil
    ) {
        self.ratings = ratings ?? .shared
        self.outbox = outbox ?? .shared
        self.hearts = hearts ?? .shared
        self.userStore = userStore ?? .shared
        let registry = self.registry
        self.deps = dependencies ?? Dependencies(
            autoFetch: { title, artist, durationMs in
                try await registry.autoFetch(
                    title: title,
                    artist: artist,
                    durationMs: durationMs.map { KotlinLong(value: $0) }
                )
            },
            musicBrainzNames: { isrc, durationMs in
                try await MusicBrainzIsrcClient.shared.names(isrc: isrc, durationMs: durationMs)
            },
            aiHasKey: { GeminiSettings.shared.hasKey },
            aiResolveReleaseNames: { title, artist, album, isrc, durationMs, otherNames in
                try await GeminiClient().resolveReleaseNames(
                    title: title,
                    artist: artist,
                    album: album,
                    isrc: isrc,
                    durationMs: durationMs,
                    otherNames: otherNames
                )
            },
            acceptLanguages: { isrc in
                SpotifyNameLanguages.acceptLanguages(isrc: isrc)
            }
        )
    }

    static func key(_ t: PlayableTrack) -> String { LocalRatings.key(provider: t.provider, songId: t.id) }

    /// Another Subsonic account (AccountScope): everything per song starts again.
    func accountChanged() {
        accountGeneration += 1
        inFlightProviderLoads.values.forEach { $0.cancel() }
        inFlightProviderLoads = [:]
        activeSongUpgradeRequested = []
        nonAiExhausted = [:]
        aiAttempted = []
        releaseNamesCache = [:]
        lyrics = [:]
        lyricsSource = [:]
        serverRating = [:]
        draftRating = [:]
        about = [:]
        lyricsRetry = []
        infoRequested = []
    }

    // MARK: Loading

    /// Fetches what this song needs and hasn't got yet.
    func prepare(_ t: PlayableTrack, client: SubsonicClient?, apple: AppleMusicSource? = nil,
                 spotify: SpotifySource? = nil) async {
        let k = Self.key(t)
        if let s = t.starred { hearts.report(s, provider: t.provider, songId: t.id) }
        if let r = t.userRating, serverRating[k] == nil { serverRating[k] = r }
        #if DEBUG
        // `-lyricsCreditsDemo YES`: the song's lyrics carry the credits a lyrics source writes into them, for
        // screenshots of the credits on the spine and in About and of the lyrics without them.
        if lyrics[k] == nil, UserDefaults.standard.bool(forKey: "lyricsCreditsDemo") {
            lyrics[k] = .lines(LoadedLyrics(LrcParser.shared.parse(raw: Self.creditsDemoLRC)))
        }
        #endif
        // Lyrics the user chose (search / editor) win over every provider's.
        if lyrics[k] == nil, let lrc = userStore.chosen(k) {
            let parsed = LrcParser.shared.parse(raw: lrc)
            if !parsed.lines.isEmpty {
                lyrics[k] = .lines(LoadedLyrics(parsed))
                lyricsSource[k] = userStore.chosenSource(k)
            }
        }
        if t.provider == AppleMusicMapping.provider {
            // No lyrics through MusicKit: the providers find them (Android
            // getLoadedLyrics for a source without lyrics). The "heart" is
            // library membership (Android's add-to-library button), the
            // rating stays local.
            if let apple, !hearts.hasReport(provider: t.provider, songId: t.id), !infoRequested.contains(k) {
                infoRequested.insert(k)
                let gen = accountGeneration
                let added = await apple.isInLibrary(t.id)
                guard gen == accountGeneration else { return }
                if let added {
                    hearts.report(added, provider: t.provider, songId: t.id)
                } else {
                    infoRequested.remove(k)
                }
            }
            await loadProviderLyrics(t, k, apple: apple, spotify: spotify, forActiveSong: true)
            return
        }
        if t.provider == SpotifyMapping.provider {
            // A podcast episode is no song: no lyrics to look for, no heart.
            if SpotifyEpisodes.isEpisode(t) {
                if lyrics[k] == nil { lyrics[k] = LyricsState.none }
                return
            }
            // Spotify has no lyrics to read (the providers find them); the heart is Liked Songs, asked
            // fresh on every song and rechecked after (SpotifyHeartRefresh).
            SpotifyHeartRefresh.shared.observe(t)
            await loadProviderLyrics(t, k, apple: apple, spotify: spotify, forActiveSong: true)
            return
        }
        guard let client, t.provider == MediaRef.companion.PROVIDER_SUBSONIC else {
            if lyrics[k] == nil { lyrics[k] = LyricsState.none }
            return
        }
        await loadLyrics(t, k, client)
        await loadInfo(t, k, client)
    }

    /// True when [text] contains any Unicode letter outside the Latin script (matching Android/shared).
    nonisolated static func hasNonLatinLetter(_ text: String) -> Bool {
        text.range(of: #"[\p{L}&&[^\p{Latin}]]"#, options: .regularExpression) != nil
    }

    private static func isCancellation(_ error: Error) -> Bool {
        if error is CancellationError { return true }
        return (error as NSError).kotlinException is KotlinCancellationException
    }

    /// Android `getLoadedLyrics` + PR #226 localized-name fallback chain:
    /// 1. `shown` (`autoFetch`)
    /// 2. Apple Music `alternateNames` (when shown carries non-Latin letters)
    /// 3. Spotify `nativeNames` (`Accept-Language` list from `SpotifyNameLanguages`)
    /// 4. MusicBrainz ISRC recordings (`MusicBrainzIsrcClient`)
    /// 5. Online AI `resolveReleaseNames` (only when `forActiveSong == true` and AI is configured)
    private func loadProviderLyrics(
        _ t: PlayableTrack,
        _ k: String,
        apple: AppleMusicSource? = nil,
        spotify: SpotifySource? = nil,
        forActiveSong: Bool = true
    ) async {
        if forActiveSong { activeSongUpgradeRequested.insert(k) }
        if let existing = inFlightProviderLoads[k] {
            await existing.value
            if !forActiveSong { return }
        }
        let allowAiOnlyRetry = forActiveSong
            && lyrics[k] == LyricsState.none
            && !lyricsRetry.contains(k)
            && !aiAttempted.contains(k)
            && nonAiExhausted[k] != nil
            && deps.aiHasKey()
        guard lyrics[k] == nil || lyricsRetry.contains(k) || allowAiOnlyRetry else { return }
        // A retry after a failure starts from `.none`: back to loading, or
        // its answer would be thrown away below (Codex review #17).
        if lyricsRetry.remove(k) != nil, lyrics[k] == LyricsState.none { lyrics[k] = .loading }
        if allowAiOnlyRetry, lyrics[k] == LyricsState.none { lyrics[k] = .loading }
        if let a = userStore.auto(k) {
            let parsed = LrcParser.shared.parse(raw: a.lrc)
            if !parsed.lines.isEmpty {
                lyrics[k] = .lines(LoadedLyrics(parsed))
                lyricsSource[k] = a.source
                return
            }
        }
        if lyrics[k] == nil { lyrics[k] = .loading }
        let gen = accountGeneration
        let task = Task { @MainActor [weak self] in
            guard let self else { return }
            defer {
                self.inFlightProviderLoads.removeValue(forKey: k)
                self.activeSongUpgradeRequested.remove(k)
            }
            do {
                let hit = try await self.resolveProviderLyrics(
                    t, key: k, apple: apple, spotify: spotify,
                    forActiveSong: forActiveSong, aiOnlyMeta: allowAiOnlyRetry ? self.nonAiExhausted[k] : nil
                )
                guard gen == self.accountGeneration,
                      self.lyrics[k] == .loading || self.lyrics[k] == nil else { return }
                if let hit, !hit.lyrics.lines.isEmpty {
                    let source = LyricsSource(provider: hit.providerName, songId: hit.providerSongId)
                    self.userStore.saveAuto(hit.lrc, source: source, for: k)
                    self.lyrics[k] = .lines(LoadedLyrics(hit.lyrics))
                    self.lyricsSource[k] = source
                    self.nonAiExhausted.removeValue(forKey: k)
                } else {
                    self.lyrics[k] = LyricsState.none
                }
            } catch {
                if Self.isCancellation(error) || Task.isCancelled {
                    if gen == self.accountGeneration, self.lyrics[k] == .loading {
                        self.lyrics[k] = nil
                    }
                    return
                }
                guard gen == self.accountGeneration else { return }
                self.lyrics[k] = LyricsState.none
                self.lyricsRetry.insert(k)
            }
        }
        inFlightProviderLoads[k] = task
        await task.value
    }

    private func resolveProviderLyrics(
        _ t: PlayableTrack,
        key k: String,
        apple: AppleMusicSource?,
        spotify: SpotifySource?,
        forActiveSong: Bool,
        aiOnlyMeta: NonAiFallbackMeta?
    ) async throws -> LyricsResult? {
        let ms: Int64? = t.durationSec > 0 ? Int64(t.durationSec * 1000) : nil
        let shownTitle = t.title.trimmingCharacters(in: .whitespacesAndNewlines)
        let shownArtist = t.artist.trimmingCharacters(in: .whitespacesAndNewlines)
        var tried = Set<NamePair>()
        var otherNames: [(title: String, artist: String)] = []
        var seenOther = Set<NamePair>()
        var transientFailure: Error?

        func recordOther(_ title: String, _ artist: String) {
            let pair = NamePair(title: title.trimmingCharacters(in: .whitespacesAndNewlines),
                                artist: artist.trimmingCharacters(in: .whitespacesAndNewlines))
            guard !pair.title.isEmpty, !pair.artist.isEmpty,
                  pair != NamePair(title: shownTitle, artist: shownArtist),
                  seenOther.insert(pair).inserted else { return }
            otherNames.append((pair.title, pair.artist))
        }

        func tryFetch(_ title: String, _ artist: String) async throws -> LyricsResult? {
            let cleanTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
            let cleanArtist = artist.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !cleanTitle.isEmpty, !cleanArtist.isEmpty else { return nil }
            let pair = NamePair(title: cleanTitle, artist: cleanArtist)
            guard tried.insert(pair).inserted else { return nil }
            do {
                if let result = try await deps.autoFetch(cleanTitle, cleanArtist, ms),
                   !result.lyrics.lines.isEmpty {
                    return result
                }
                return nil
            } catch {
                if Self.isCancellation(error) || Task.isCancelled { throw CancellationError() }
                transientFailure = transientFailure ?? error
                return nil
            }
        }

        let isrc: String?
        let nonLatinShown: Bool

        if let aiOnlyMeta {
            isrc = aiOnlyMeta.isrc
            nonLatinShown = aiOnlyMeta.nonLatinShown
            otherNames = aiOnlyMeta.otherNames
            tried.insert(NamePair(title: shownTitle, artist: shownArtist))
            for o in otherNames {
                tried.insert(NamePair(title: o.title, artist: o.artist))
            }
        } else {
            // 1. Shown names
            if let hit = try await tryFetch(shownTitle, shownArtist) { return hit }
            try Task.checkCancellation()

            nonLatinShown = Self.hasNonLatinLetter(shownTitle) || Self.hasNonLatinLetter(shownArtist)
            var resolvedIsrc: String? = nil

            // 2. Apple Music storefront alternate names (English names when shown carries non-Latin)
            if t.provider == AppleMusicMapping.provider, nonLatinShown, let apple {
                let pairs = await apple.alternateNames(trackId: t.id)
                for p in pairs {
                    recordOther(p.title, p.artist)
                    if let hit = try await tryFetch(p.title, p.artist) { return hit }
                }
                try Task.checkCancellation()
            }

            // 3. Spotify native names (`Accept-Language` based on device language + ISRC country)
            if t.provider == SpotifyMapping.provider, let spotify {
                resolvedIsrc = await spotify.isrc(trackId: t.id)
                try Task.checkCancellation()
                let langs = deps.acceptLanguages(resolvedIsrc)
                let native = await spotify.nativeNames(trackId: t.id, acceptLanguages: langs)
                for p in native {
                    recordOther(p.title, p.artist)
                    if let hit = try await tryFetch(p.title, p.artist) { return hit }
                }
                try Task.checkCancellation()
            }

            // 4. MusicBrainz ISRC recordings
            if resolvedIsrc == nil {
                if t.provider == SpotifyMapping.provider {
                    resolvedIsrc = await spotify?.isrc(trackId: t.id)
                } else if t.provider == AppleMusicMapping.provider {
                    resolvedIsrc = await apple?.isrc(trackId: t.id)
                }
                try Task.checkCancellation()
            }
            if let code = resolvedIsrc, !code.isEmpty {
                do {
                    let mbNames = try await deps.musicBrainzNames(code, ms)
                    for p in mbNames {
                        recordOther(p.title, p.artist)
                        if let hit = try await tryFetch(p.title, p.artist) { return hit }
                    }
                } catch {
                    if Self.isCancellation(error) || Task.isCancelled { throw CancellationError() }
                    transientFailure = transientFailure ?? error
                }
                try Task.checkCancellation()
            }
            isrc = resolvedIsrc
        }

        // 5. Online AI release-names lookup (active song only; AI failures must not fail lyrics)
        let allowAi = forActiveSong || activeSongUpgradeRequested.contains(k)
        if allowAi, deps.aiHasKey(), (isrc != nil || nonLatinShown || !otherNames.isEmpty) {
            let aiPair: NamePair?
            if let cached = releaseNamesCache[k] {
                aiPair = cached
            } else {
                do {
                    let album = t.album.trimmingCharacters(in: .whitespacesAndNewlines)
                    let resolved = try await deps.aiResolveReleaseNames(
                        t.title,
                        t.artist,
                        album.isEmpty ? nil : album,
                        isrc,
                        ms,
                        otherNames
                    )
                    let mapped = resolved.map { NamePair(title: $0.title, artist: $0.artist) }
                    if releaseNamesCache.count >= 64, let drop = releaseNamesCache.keys.first {
                        releaseNamesCache.removeValue(forKey: drop)
                    }
                    releaseNamesCache[k] = mapped
                    aiAttempted.insert(k)
                    aiPair = mapped
                } catch {
                    if Self.isCancellation(error) || Task.isCancelled { throw CancellationError() }
                    aiPair = nil
                }
            }
            if let aiPair, let hit = try await tryFetch(aiPair.title, aiPair.artist) {
                return hit
            }
        } else if !allowAi, transientFailure == nil {
            nonAiExhausted[k] = NonAiFallbackMeta(isrc: isrc, nonLatinShown: nonLatinShown, otherNames: otherNames)
        }

        if let transientFailure {
            throw transientFailure
        }
        return nil
    }

    private func loadLyrics(_ t: PlayableTrack, _ k: String, _ client: SubsonicClient) async {
        guard lyrics[k] == nil || lyricsRetry.contains(k) else { return }
        // A retry after a failure starts from `.none`: back to loading, or
        // its answer would be thrown away below (Codex review #17).
        if lyricsRetry.remove(k) != nil, lyrics[k] == LyricsState.none { lyrics[k] = .loading }
        if lyrics[k] == nil { lyrics[k] = .loading }
        let gen = accountGeneration
        do {
            let result = try await client.lyricsFor(songId: t.id, artist: t.artist, title: t.title)
            guard gen == accountGeneration else { return }
            if let result, !result.lines.isEmpty {
                lyrics[k] = .lines(LoadedLyrics(result))
                lyricsSource[k] = LyricsSource(provider: LyricsSource.server, songId: t.id)
            } else {
                lyrics[k] = LyricsState.none
            }
        } catch {
            if Self.isCancellation(error) || Task.isCancelled {
                if gen == accountGeneration, lyrics[k] == .loading { lyrics[k] = nil }
                return
            }
            guard gen == accountGeneration else { return }
            // Offline / server error: show none for now, ask again next time.
            lyrics[k] = LyricsState.none
            lyricsRetry.insert(k)
        }
    }

    /// The queue entry didn't say whether the song is starred or rated:
    /// the album lists both for each song (the client has no getSong).
    private func loadInfo(_ t: PlayableTrack, _ k: String, _ client: SubsonicClient) async {
        guard t.starred == nil || t.userRating == nil, let albumId = t.albumId,
              !infoRequested.contains(k) else { return }
        infoRequested.insert(k)
        let gen = accountGeneration
        let album = try? await client.getAlbum(id: albumId)
        guard gen == accountGeneration else { return }
        guard let album, let song = album.songs.first(where: { $0.id == t.id }) else {
            infoRequested.remove(k)
            return
        }
        if t.starred == nil { hearts.report(song.starred, provider: t.provider, songId: t.id) }
        if t.userRating == nil, let r = song.userRating { serverRating[k] = Int(r.int32Value) }
    }

    func lyricsState(_ t: PlayableTrack) -> LyricsState { lyrics[Self.key(t)] ?? .loading }

    #if DEBUG
    /// `-lyricsCreditsDemo`'s lyrics: credits at the head and a notice at the foot, as NetEase writes them.
    static let creditsDemoLRC = """
    [00:00.00]作词 : Mina Asahi
    [00:00.40]作曲 : Mina Asahi / Ren Kudo
    [00:00.80]编曲 : Ren Kudo
    [00:01.20]制作人 : Ren Kudo
    [00:01.60]吉他 : Sora Ito
    [00:02.00]混音 : Kei Mori
    [00:02.40]录音 : Studio Lantern
    [00:06.00]The last train hums below the bridge
    [00:12.00]I count the windows going dark
    [00:18.00]Your coat still smells of rain and smoke
    [00:24.00]We never said the station's name
    [00:30.00]So let it keep us here a while
    [00:36.00]℗ 2026 Yoin Demo Records
    """
    #endif

    /// The credits the song's lyrics carried (Android NowPlayingViewModel `lyricCredits`): they answer
    /// lyricist, composer and producer on the spine and in About before Gemini does. Nil until the
    /// lyrics are here, and when they had none.
    func lyricCredits(_ t: PlayableTrack) -> SongCredits? {
        if case .lines(let l)? = lyrics[Self.key(t)] { return l.credits }
        return nil
    }

    /// The next song's lyrics ahead of its turn (Android prefetches during the
    /// outro): its title card and first line can rise under this song's end,
    /// and they are there the moment it starts.
    func prefetchLyrics(_ t: PlayableTrack, client: SubsonicClient?, apple: AppleMusicSource? = nil,
                        spotify: SpotifySource? = nil) async {
        let k = Self.key(t)
        guard lyrics[k] == nil || lyricsRetry.contains(k) else { return }
        if lyrics[k] == nil, let lrc = userStore.chosen(k) {
            let parsed = LrcParser.shared.parse(raw: lrc)
            if !parsed.lines.isEmpty {
                lyrics[k] = .lines(LoadedLyrics(parsed))
                lyricsSource[k] = userStore.chosenSource(k)
                lyricsRetry.remove(k)
                return
            }
        }
        // Spotify's lyrics come from the providers too (Codex N24): the next song's are ready for the outro.
        if SpotifyEpisodes.isEpisode(t) { return }
        if t.provider == AppleMusicMapping.provider || t.provider == SpotifyMapping.provider {
            await loadProviderLyrics(t, k, apple: apple, spotify: spotify, forActiveSong: false)
        } else if let client, t.provider == MediaRef.companion.PROVIDER_SUBSONIC {
            await loadLyrics(t, k, client)
        }
    }

    /// Retries a track currently marked in `lyricsRetry` (e.g., when playback resumes).
    func retryPendingIfNeeded(_ t: PlayableTrack, client: SubsonicClient?, apple: AppleMusicSource? = nil,
                              spotify: SpotifySource? = nil) async {
        let k = Self.key(t)
        guard lyricsRetry.contains(k), lyrics[k] != .loading else { return }
        await prefetchLyrics(t, client: client, apple: apple, spotify: spotify)
    }

    /// Key for root `LyricsPrefetchHost` next-song preload: queue's next song, or Spotify's own queue.
    static func nextPrefetchKey(_ model: AppModel) -> String? {
        if let next = model.player.upNextTrack { return key(next) }
        guard let own = model.player.externalTrack, own.provider == SpotifyMapping.provider else { return nil }
        let active = model.store.spotify != nil ? 1 : 0
        return "after:\(key(own))|\(model.spotifyQueueRevision)|\(active)"
    }

    /// Prefetches the next song's lyrics at shell scope (2 s settle + Spotify `me/player/queue` fallback).
    func prefetchNext(model: AppModel) async {
        try? await Task.sleep(for: .seconds(2))
        guard !Task.isCancelled else { return }
        if let next = model.player.upNextTrack {
            await prefetchWithRetry(next, client: model.session.client, apple: model.store.apple,
                                    spotify: model.store.spotify)
            return
        }
        guard let own = model.player.externalTrack, own.provider == SpotifyMapping.provider,
              !SpotifyEpisodes.isEpisode(own), let spotify = model.store.spotify,
              let entries = try? await spotify.playerQueue(), !Task.isCancelled else { return }
        // A read taken a beat before Spotify moved on still lists this song first: what follows it is next.
        guard let next = entries.drop(while: { $0.trackId == own.id }).first?.playable else { return }
        await prefetchWithRetry(next, client: nil, apple: nil, spotify: spotify)
    }

    /// Runs `prefetchLyrics` with `15 s` and `30 s` backoff retries while transient errors leave `lyricsRetry` set.
    func prefetchWithRetry(_ t: PlayableTrack, client: SubsonicClient?, apple: AppleMusicSource? = nil,
                           spotify: SpotifySource? = nil, retryDelays: [Int] = [15, 30]) async {
        await prefetchLyrics(t, client: client, apple: apple, spotify: spotify)
        let k = Self.key(t)
        for wait in retryDelays {
            guard lyricsRetry.contains(k), !Task.isCancelled else { return }
            if wait > 0 {
                try? await Task.sleep(for: .seconds(wait))
            }
            guard !Task.isCancelled, lyricsRetry.contains(k) else { return }
            await prefetchLyrics(t, client: client, apple: apple, spotify: spotify)
        }
    }

    /// The outro's next song: its title and first timed line, when its
    /// lyrics are here and timed.
    func upNext(_ t: PlayableTrack?) -> UpNextTiming.Next? {
        guard let t, case .lines(let l)? = lyrics[Self.key(t)], l.synced,
              let first = l.lyrics.lines.first(where: { !$0.text.trimmingCharacters(in: .whitespaces).isEmpty })
        else { return nil }
        return UpNextTiming.Next(key: Self.key(t), title: t.title, firstLine: first.text)
    }

    /// The user applied lyrics (search result or the editor): stored so they
    /// win on every later load, shown at once.
    /// The same provider's lyrics fetched again with its translation (Android
    /// fetchProviderTranslation persistLyrics): kept as the user's when they
    /// were, else as the automatic hit.
    func refreshProviderLyrics(_ lrc: String, source: LyricsSource, for t: PlayableTrack) {
        let parsed = LrcParser.shared.parse(raw: lrc)
        guard !parsed.lines.isEmpty else { return }
        let k = Self.key(t)
        if userStore.chosen(k) != nil {
            userStore.choose(lrc, for: k, source: source)
        } else {
            userStore.saveAuto(lrc, source: source, for: k)
        }
        lyricsSource[k] = source
        lyrics[k] = .lines(LoadedLyrics(parsed))
    }

    func applyUserLyrics(_ lrc: String, for t: PlayableTrack, source: LyricsSource? = nil) -> Bool {
        let parsed = LrcParser.shared.parse(raw: lrc)
        guard !parsed.lines.isEmpty else { return false }
        let k = Self.key(t)
        userStore.choose(lrc, for: k, source: source)
        lyricsSource[k] = source
        lyricsRetry.remove(k)
        nonAiExhausted.removeValue(forKey: k)
        aiAttempted.remove(k)
        lyrics[k] = .lines(LoadedLyrics(parsed))
        return true
    }

    // MARK: About

    func aboutState(_ t: PlayableTrack) -> AboutState {
        AboutState.compose(server: about[Self.key(t)] ?? .loading, song: AboutSong(t))
    }

    /// The server's facts the Now Playing spine reads (Subsonic `getSong`); nil until they arrive and
    /// for every other provider.
    func spineFacts(_ t: PlayableTrack) -> SpineSourceFacts? {
        if case .ready(let f)? = about[Self.key(t)] { return f.spine }
        return nil
    }

    /// Gemini's About (when a key is saved; once per song, cached) alongside
    /// the server's facts.
    func loadAbout(_ t: PlayableTrack, client: SubsonicClient?) async {
        // A podcast episode: the entry's facts only, no song lookup.
        if SpotifyEpisodes.isEpisode(t) { return await loadFacts(t, client: client) }
        let (title, artist, album) = (t.title, t.artist, t.album)
        let gemini = Task { await SongAboutLoader.shared.ensure(title: title, artist: artist, album: album) }
        await loadFacts(t, client: client)
        await gemini.value
    }

    /// The server's facts alone, for the spine: never a Gemini request.
    func loadServerFacts(_ t: PlayableTrack, client: SubsonicClient?) async {
        await loadFacts(t, client: client)
    }

    /// The song's facts: the server's full record where there is one, else
    /// what the queue entry knows. A failed fetch shows the entry's facts and
    /// asks again next time.
    private func loadFacts(_ t: PlayableTrack, client: SubsonicClient?) async {
        let k = Self.key(t)
        if case .ready(let f)? = about[k], f.complete { return }
        guard let client, t.provider == MediaRef.companion.PROVIDER_SUBSONIC else {
            about[k] = .ready(SongFacts(t))
            return
        }
        if about[k] == nil { about[k] = .loading }
        let gen = accountGeneration
        do {
            let song = try await client.getSong(id: t.id)
            guard gen == accountGeneration else { return }
            if let song {
                about[k] = .ready(SongFacts(song, fallback: t))
                if t.starred == nil { hearts.report(song.starred, provider: t.provider, songId: t.id) }
                if t.userRating == nil, serverRating[k] == nil, let r = song.userRating { serverRating[k] = Int(r.int32Value) }
            } else {
                about[k] = .ready(SongFacts(t))
            }
        } catch {
            guard gen == accountGeneration else { return }
            about[k] = .ready(SongFacts(t, complete: false))
        }
    }

    // MARK: Heart

    func isFavorite(_ t: PlayableTrack) -> Bool {
        hearts.isFavorite(provider: t.provider, songId: t.id, known: known(t))
    }

    /// Whether the heart can be shown as it is: false for a Spotify song whose Liked Songs read hasn't
    /// answered (or failed), where an empty heart would be a guess.
    func isHeartKnown(_ t: PlayableTrack) -> Bool {
        t.provider != SpotifyMapping.provider || hearts.isKnown(provider: t.provider, songId: t.id, known: known(t))
    }

    /// The song's own copy of its heart; on Spotify a fresh read (SpotifyHeartRefresh) outranks it.
    private func known(_ t: PlayableTrack) -> Bool? {
        t.provider == SpotifyMapping.provider && hearts.hasReport(provider: t.provider, songId: t.id) ? nil : t.starred
    }

    /// Optimistic: the heart flips at once and flips back if the server says
    /// no (`FavoritesStore`; Apple Music adds to the library only).
    func toggleFavorite(_ t: PlayableTrack, client: SubsonicClient?, apple: AppleMusicSource? = nil) {
        hearts.toggle(provider: t.provider, songId: t.id, known: known(t), client: client, apple: apple)
    }

    // MARK: Rating

    /// 0–10: the finger's value while held, else this device's (or a synced)
    /// rating; nil = never rated. The server's stars alone don't count: Android
    /// NP reads local_ratings only, so a song is unrated until it is rated here
    /// (or on a synced device). 0 is a rating, distinct from nil.
    func rating(_ t: PlayableTrack) -> Double? {
        let k = Self.key(t)
        if let d = draftRating[k] { return d }
        return ratings.rating(provider: t.provider, songId: t.id)
    }

    func dragRating(_ value: Double?, _ t: PlayableTrack) {
        guard let value else { return }
        draftRating[Self.key(t)] = value
    }

    /// A held rating whose song was replaced: dropped, never committed.
    func discardDraftRating(_ t: PlayableTrack) {
        draftRating[Self.key(t)] = nil
    }

    /// Release: stored locally whatever happens (Android rule), then sent as
    /// 0–5 stars. The song stays owed to its account's server (`RatingOutbox`)
    /// until a push lands, so one given offline goes out again later.
    func commitRating(_ t: PlayableTrack, client: SubsonicClient?) {
        let k = Self.key(t)
        guard let value = draftRating.removeValue(forKey: k) else { return }
        ratings.set(value, provider: t.provider, songId: t.id)
        guard t.provider == MediaRef.companion.PROVIDER_SUBSONIC, let scope = AccountScope.shared.subsonic else { return }
        let entry = RatingOutbox.Entry(scope: scope, songId: t.id)
        outbox.mark(entry)
        guard let client else { return }
        let gen = accountGeneration
        let ratings = ratings
        Task {
            var sent: Int32?
            let outcome = await outbox.push(entry, next: {
                let next = RatingOutbox.next(entry, ratings: ratings, activeScope: AccountScope.shared.subsonic)
                if case .send(let stars) = next { sent = stars }
                return next
            }, send: { stars in
                try await client.setRating(id: t.id, rating: stars)
            })
            guard outcome == .sent, gen == accountGeneration, let sent else { return }
            serverRating[k] = Int(sent)
        }
    }
}

/// The song a note is written FOR (Android NoteTarget), captured with its
/// moment when writing begins; title and artist travel with it so a draft
/// can still be filed and named after the player has moved on.
struct NoteTarget: Equatable {
    var provider: String
    var rawId: String
    var title: String
    var artist: String

    func sameSong(_ other: NoteTarget) -> Bool { provider == other.provider && rawId == other.rawId }
}
