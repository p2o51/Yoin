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
        var synced: Bool { lyrics.synced }

        init(_ lyrics: Lyrics) {
            self.lyrics = lyrics
            lines = lyrics.lines.map(\.text)
        }

        /// -1 = before the first line, or unsynced.
        func activeIndex(positionSec: Double) -> Int {
            guard synced else { return -1 }
            return Int(lyrics.lineIndexAt(positionMs: Int64((positionSec * 1000).rounded())))
        }

        static func == (a: LoadedLyrics, b: LoadedLyrics) -> Bool { a.lyrics === b.lyrics }
    }

    private(set) var lyrics: [String: LyricsState] = [:]
    /// The heart lives in `FavoritesStore` (shared with the album rows);
    /// this side reports what the server says about the song.
    @ObservationIgnored private let hearts: FavoritesStore
    private(set) var serverRating: [String: Int] = [:]
    /// The rating under the finger, until it is committed on release.
    private(set) var draftRating: [String: Double] = [:]

    /// The About page's server facts per song (`getSong` on Subsonic; the
    /// queue entry elsewhere). `AboutState` (NowPlayingAbout.swift) adds Gemini.
    private(set) var about: [String: AboutState] = [:]

    @ObservationIgnored private let ratings: LocalRatings
    @ObservationIgnored private var lyricsRetry: Set<String> = []
    @ObservationIgnored private var infoRequested: Set<String> = []

    init(ratings: LocalRatings? = nil, hearts: FavoritesStore? = nil) {
        self.ratings = ratings ?? .shared
        self.hearts = hearts ?? .shared
    }

    static func key(_ t: PlayableTrack) -> String { LocalRatings.key(provider: t.provider, songId: t.id) }

    // MARK: Loading

    /// Fetches what this song needs and hasn't got yet.
    func prepare(_ t: PlayableTrack, client: SubsonicClient?, apple: AppleMusicSource? = nil) async {
        let k = Self.key(t)
        if let s = t.starred { hearts.report(s, provider: t.provider, songId: t.id) }
        if let r = t.userRating, serverRating[k] == nil { serverRating[k] = r }
        if t.provider == AppleMusicMapping.provider {
            // No lyrics through MusicKit; the "heart" is library membership
            // (Android's add-to-library button), the rating stays local.
            if lyrics[k] == nil { lyrics[k] = LyricsState.none }
            if let apple, !hearts.hasReport(provider: t.provider, songId: t.id), !infoRequested.contains(k) {
                infoRequested.insert(k)
                if let added = await apple.isInLibrary(t.id) {
                    hearts.report(added, provider: t.provider, songId: t.id)
                } else {
                    infoRequested.remove(k)
                }
            }
            return
        }
        guard let client, t.provider == MediaRef.companion.PROVIDER_SUBSONIC else {
            if lyrics[k] == nil { lyrics[k] = LyricsState.none }
            return
        }
        await loadLyrics(t, k, client)
        await loadInfo(t, k, client)
    }

    private func loadLyrics(_ t: PlayableTrack, _ k: String, _ client: SubsonicClient) async {
        guard lyrics[k] == nil || lyricsRetry.contains(k) else { return }
        lyricsRetry.remove(k)
        if lyrics[k] == nil { lyrics[k] = .loading }
        do {
            let result = try await client.lyricsFor(songId: t.id, artist: t.artist, title: t.title)
            if let result, !result.lines.isEmpty {
                lyrics[k] = .lines(LoadedLyrics(result))
            } else {
                lyrics[k] = LyricsState.none
            }
        } catch {
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
        guard let album = try? await client.getAlbum(id: albumId),
              let song = album.songs.first(where: { $0.id == t.id }) else {
            infoRequested.remove(k)
            return
        }
        if t.starred == nil { hearts.report(song.starred, provider: t.provider, songId: t.id) }
        if t.userRating == nil, let r = song.userRating { serverRating[k] = Int(r.int32Value) }
    }

    func lyricsState(_ t: PlayableTrack) -> LyricsState { lyrics[Self.key(t)] ?? .loading }

    // MARK: About

    func aboutState(_ t: PlayableTrack) -> AboutState {
        AboutState.compose(server: about[Self.key(t)] ?? .loading, song: AboutSong(t))
    }

    /// Gemini's About (when a key is saved; once per song, cached) alongside
    /// the server's facts.
    func loadAbout(_ t: PlayableTrack, client: SubsonicClient?) async {
        let (title, artist, album) = (t.title, t.artist, t.album)
        let gemini = Task { await SongAboutLoader.shared.ensure(title: title, artist: artist, album: album) }
        await loadFacts(t, client: client)
        await gemini.value
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
        do {
            if let song = try await client.getSong(id: t.id) {
                about[k] = .ready(SongFacts(song, fallback: t))
                if t.starred == nil { hearts.report(song.starred, provider: t.provider, songId: t.id) }
                if t.userRating == nil, serverRating[k] == nil, let r = song.userRating { serverRating[k] = Int(r.int32Value) }
            } else {
                about[k] = .ready(SongFacts(t))
            }
        } catch {
            about[k] = .ready(SongFacts(t, complete: false))
        }
    }

    // MARK: Heart

    func isFavorite(_ t: PlayableTrack) -> Bool {
        hearts.isFavorite(provider: t.provider, songId: t.id, known: t.starred)
    }

    /// Optimistic: the heart flips at once and flips back if the server says
    /// no (`FavoritesStore`; Apple Music adds to the library only).
    func toggleFavorite(_ t: PlayableTrack, client: SubsonicClient?, apple: AppleMusicSource? = nil) {
        hearts.toggle(provider: t.provider, songId: t.id, known: t.starred, client: client, apple: apple)
    }

    // MARK: Rating

    /// 0–10: the finger's value while held, else this device's rating, else
    /// the server's stars × 2, else 0.
    func rating(_ t: PlayableTrack) -> Double {
        let k = Self.key(t)
        if let d = draftRating[k] { return d }
        if let local = ratings.rating(provider: t.provider, songId: t.id) { return local }
        return RatingScale.local(fromServer: serverRating[k]) ?? 0
    }

    func dragRating(_ value: Double, _ t: PlayableTrack) {
        draftRating[Self.key(t)] = value
    }

    /// Release: stored locally whatever happens (Android rule), then sent as
    /// 0–5 stars.
    func commitRating(_ t: PlayableTrack, client: SubsonicClient?) {
        let k = Self.key(t)
        guard let value = draftRating.removeValue(forKey: k) else { return }
        ratings.set(value, provider: t.provider, songId: t.id)
        let stars = RatingScale.serverRating(local: value)
        guard let client, t.provider == MediaRef.companion.PROVIDER_SUBSONIC else { return }
        Task {
            do {
                try await client.setRating(id: t.id, rating: stars)
                serverRating[k] = Int(stars)
            } catch {
                // The local rating stands; the server catches up on the next rating.
            }
        }
    }
}
