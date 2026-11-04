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
    /// The heart as the user last set it (optimistic; reverted on failure).
    private(set) var favorites: [String: Bool] = [:]
    /// What the server said (from the queue entry or a fetched album).
    private(set) var serverStarred: [String: Bool] = [:]
    private(set) var serverRating: [String: Int] = [:]
    /// The rating under the finger, until it is committed on release.
    private(set) var draftRating: [String: Double] = [:]

    /// The About page's facts per song (`getSong` on Subsonic; the queue
    /// entry elsewhere).
    private(set) var about: [String: AboutState] = [:]

    enum AboutState: Equatable {
        case loading
        case ready(SongFacts)
    }

    @ObservationIgnored private let ratings: LocalRatings
    @ObservationIgnored private var lyricsRetry: Set<String> = []
    @ObservationIgnored private var infoRequested: Set<String> = []

    init(ratings: LocalRatings? = nil) {
        self.ratings = ratings ?? .shared
    }

    static func key(_ t: PlayableTrack) -> String { LocalRatings.key(provider: t.provider, songId: t.id) }

    // MARK: Loading

    /// Fetches what this song needs and hasn't got yet.
    func prepare(_ t: PlayableTrack, client: SubsonicClient?, apple: AppleMusicSource? = nil) async {
        let k = Self.key(t)
        if let s = t.starred, serverStarred[k] == nil { serverStarred[k] = s }
        if let r = t.userRating, serverRating[k] == nil { serverRating[k] = r }
        if t.provider == AppleMusicMapping.provider {
            // No lyrics through MusicKit; the "heart" is library membership
            // (Android's add-to-library button), the rating stays local.
            if lyrics[k] == nil { lyrics[k] = LyricsState.none }
            if let apple, serverStarred[k] == nil, !infoRequested.contains(k) {
                infoRequested.insert(k)
                if let added = await apple.isInLibrary(t.id) { serverStarred[k] = added } else { infoRequested.remove(k) }
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
        if t.starred == nil { serverStarred[k] = song.starred }
        if t.userRating == nil, let r = song.userRating { serverRating[k] = Int(r.int32Value) }
    }

    func lyricsState(_ t: PlayableTrack) -> LyricsState { lyrics[Self.key(t)] ?? .loading }

    // MARK: About

    func aboutState(_ t: PlayableTrack) -> AboutState { about[Self.key(t)] ?? .loading }

    /// The song's facts: the server's full record where there is one, else
    /// what the queue entry knows. A failed fetch shows the entry's facts and
    /// asks again next time.
    func loadAbout(_ t: PlayableTrack, client: SubsonicClient?) async {
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
                if t.starred == nil, serverStarred[k] == nil { serverStarred[k] = song.starred }
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
        let k = Self.key(t)
        return favorites[k] ?? serverStarred[k] ?? false
    }

    /// Optimistic: the heart flips at once and flips back if the server says no.
    func toggleFavorite(_ t: PlayableTrack, client: SubsonicClient?, apple: AppleMusicSource? = nil) {
        let k = Self.key(t)
        let was = isFavorite(t)
        let target = !was
        if t.provider == AppleMusicMapping.provider {
            // Add to library only: Apple has no remove, so an added song stays added.
            guard !was, let apple else { return }
            favorites[k] = true
            Task {
                do {
                    try await apple.addToLibrary(t.id)
                    serverStarred[k] = true
                } catch {
                    if favorites[k] == true { favorites[k] = false }
                }
            }
            return
        }
        favorites[k] = target
        guard let client, t.provider == MediaRef.companion.PROVIDER_SUBSONIC else { return }
        Task {
            do {
                if target {
                    try await client.star(id: t.id, albumId: nil, artistId: nil)
                } else {
                    try await client.unstar(id: t.id, albumId: nil, artistId: nil)
                }
                serverStarred[k] = target
            } catch {
                // Only undo our own write: a newer tap owns the heart now.
                if favorites[k] == target { favorites[k] = was }
            }
        }
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
