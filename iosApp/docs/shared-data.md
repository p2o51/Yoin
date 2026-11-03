# Shared data layer (`com.gpo.yoin.shared.data` → YoinShared)

The iOS app talks to Subsonic / OpenSubsonic servers through Kotlin in `:shared`, linked as part of the `YoinShared` XCFramework (see `shared-kmp.md` for how the framework is built and linked). Android does **not** use this code yet: it keeps its own Retrofit client in `app/src/main/java/com/gpo/yoin/data/remote/` and `data/source/subsonic/`. The Kotlin here is a port of that code, kept to the same behaviour.

## What's shared

| Kotlin (`shared/src/commonMain/kotlin/com/gpo/yoin/shared/data`) | What it is | Ported from (Android `app/src/main/java/com/gpo/yoin/`) |
|---|---|---|
| `model/Models.kt` | Neutral models: `MediaRef`, `SongInfo`, `AlbumInfo`, `ArtistInfo`, `ArtistIndex`, `PlaylistSummary`, `PlaylistDetail`, `SearchResult`, `Starred`, `LyricLine`, `Lyrics`, `ServerInfo` (all `@Serializable` data classes) | `data/model/*` (Track, Album, Artist, Playlist, Lyrics, MediaId) |
| `subsonic/SubsonicClient.kt` | The client: token auth, every endpoint below, `streamUrl` / `coverArtUrl`, error mapping | `data/remote/SubsonicApi.kt`, `SubsonicInterceptor.kt`, `SubsonicApiFactory.kt`, `data/source/subsonic/SubsonicMusicSource.kt` |
| `subsonic/SubsonicDtos.kt` (internal) | The JSON wire shapes | `data/remote/SubsonicModels.kt` |
| `subsonic/SubsonicMappers.kt` (internal) | Wire → neutral | `data/source/subsonic/SubsonicMappers.kt` |
| `subsonic/SubsonicAuth.kt` (internal) | Salt + pure-Kotlin MD5 (`t = md5(password + salt)`) | `data/remote/SubsonicAuth.kt` (which uses `java.security.MessageDigest`) |
| `subsonic/SubsonicException.kt` | `SubsonicException` + `SubsonicErrorKind` | `data/repository/SubsonicException.kt` (code + message only) |
| `lyrics/LrcParser.kt` | LRC text → `Lyrics` | `data/lyrics/LrcParser.kt` |
| `subsonic/PlatformEngine.kt` + `iosMain` / `androidMain` actuals | Ktor engine: Darwin (NSURLSession) on iOS, OkHttp on Android / JVM tests | — |

Semantics kept from Android's mappers: `starred` is "the server sent a non-empty timestamp" (the timestamp is kept as `starredAt`); album art falls back to the album id (`coverArt ?: id`), playlist art to the playlist id; `userRating` stays the raw 1–5 (null = unrated); dates stay the server's ISO-8601 strings (`created`, `changed`, `starredAt`); `releaseType` is the first recognised OpenSubsonic `releaseTypes` value, lower-cased; playlist `canWrite` = not `readonly` and (no owner, or owner == login), `ownedByMe` = owner equals the login ignoring case, null when no owner; structured lyrics use the first entry, synced only if it says so and has at least one `start`, lines sorted by time; `setRating` clamps to 0…5; `star` / `unstar` send only the id kind you pass (artists must go through `artistId`).

Deliberate differences from Android:

- **Lyric offsets are applied.** OpenSubsonic's structured `offset` and LRC's `[offset:±N]` (ms, positive = sooner) shift every line, clamped at 0. Android ignores both.
- **Plain `getLyrics` fallback.** `lyricsFor(songId:artist:title:)` tries `getLyricsBySongId` and, when it's empty or the server doesn't know it, `getLyrics(artist, title)`; that plain text is run through `LrcParser`, so LRC-in-plain-text comes back synced. Android only calls `getLyricsBySongId` (and gets the rest from NetEase / QQ / LRCLIB providers, which are not shared).
- **Unsynced lyrics are lines, not one string.** `Lyrics(synced: false)` has one `LyricLine` per text line at `timeMs = 0`; `lyrics.text` joins them back.
- **Errors are typed.** `SubsonicErrorKind` names every Subsonic error code plus `network`, `http`, `parse`.
- Extra fields Android doesn't read yet: `SongInfo.disc` (`discNumber`), `SongInfo.playCount`, `AlbumInfo.playCount`, `AlbumInfo.userRating`, playlist `coverArt` / `changed` / `public`.

## Calling it from Swift

```swift
import YoinShared

let client = SubsonicClient(baseUrl: "https://music.example.com", username: "alice", password: "secret")
// Saved token instead of the password:
// SubsonicClient(baseUrl: url, username: user, token: savedToken, salt: savedSalt)  (client.token / client.salt)

do {
    let info = try await client.ping()                                  // ServerInfo
    let newest = try await client.getAlbumList2(type: .newest, size: 20, offset: 0)
    guard let album = try await client.getAlbum(id: newest[0].id) else { return }
    let song = album.songs[0]
    let streamURL = URL(string: client.streamUrl(id: song.id, maxBitRate: nil, format: nil))!
    let coverURL = URL(string: client.coverArtUrl(id: album.coverArtId, size: 600))!
    let lyrics = try await client.lyricsFor(songId: song.id, artist: song.artist, title: song.title)
    let current = lyrics?.lineIndexAt(positionMs: 42_000) ?? -1        // Int32, -1 = before first / unsynced
    try await client.setRating(id: song.id, rating: 5)
    try await client.scrobble(id: song.id, submission: true, timeMs: KotlinLong(value: Int64(Date().timeIntervalSince1970 * 1000)))
} catch {
    if let e = (error as NSError).kotlinException as? SubsonicException {
        switch e.kind {
        case .wrongCredentials, .tokenAuthNotSupported: break   // e.isAuthFailure: ask for the password again
        case .notFound: break
        case .network: break                                    // offline / refused / timeout
        default: break                                          // e.code, e.httpStatus, e.message
        }
    }
}
client.close()   // when the profile goes away
```

Rules of the bridge (Kotlin/Native → Objective-C → Swift):

- Every `suspend fun` is `async throws`. They're all `@Throws(SubsonicException::class, CancellationException::class)` and the client wraps network, HTTP and JSON failures, so nothing else escapes (an undeclared Kotlin exception would abort the app). Cancelling the Swift `Task` cancels the request.
- The completion resumes on the main thread (Kotlin/Native's default), so don't block the main thread waiting for one.
- No default arguments in Swift: pass every parameter, `nil` for optional ones.
- Optional numbers are boxes: `KotlinInt?` (`.int32Value`), `KotlinLong?` (`.int64Value`); build them with `KotlinInt(int: 320)`, `KotlinLong(value: ms)`. `Int` is `Int32`, `Long` is `Int64`.
- Enums are classes with lower-camel statics: `AlbumListType.alphabeticalByName`, `SubsonicErrorKind.wrongCredentials`; `switch` works with a `default`.
- Objects: `LrcParser.shared.parse(raw:)`. Constants: `SubsonicClient.companion.API_VERSION` (`"1.16.1"`), `MediaRef.companion.PROVIDER_SUBSONIC`.
- Models are immutable classes; data-class `copy` is `doCopy(...)`.
- Keep one client per server profile. The password constructor makes a fresh salt per client; the token and salt then stay fixed for its lifetime, so stream and cover URLs are stable (image caches don't refetch).

### Swift-facing API (from `YoinShared.h`, ios-arm64-simulator, 2026-11-04)

```swift
class SubsonicClient {
    init(baseUrl: String, username: String, password: String)
    init(baseUrl: String, username: String, token: String, salt: String)
    var baseUrl: String { get }
    var username: String { get }
    var token: String { get }          // md5(password + salt)
    var salt: String { get }

    func ping() async throws -> ServerInfo
    func getAlbumList2(type: AlbumListType, size: Int32, offset: Int32) async throws -> [AlbumInfo]
    func getAlbum(id: String) async throws -> AlbumInfo?
    func getArtists() async throws -> [ArtistIndex]
    func getArtist(id: String) async throws -> ArtistInfo?
    func getPlaylists() async throws -> [PlaylistSummary]
    func getPlaylist(id: String) async throws -> PlaylistDetail?
    func search3(query: String, artistCount: Int32, albumCount: Int32, songCount: Int32) async throws -> SearchResult
    func getStarred2() async throws -> Starred
    func getRandomSongs(size: Int32) async throws -> [SongInfo]
    func star(id: String?, albumId: String?, artistId: String?) async throws
    func unstar(id: String?, albumId: String?, artistId: String?) async throws
    func setRating(id: String, rating: Int32) async throws
    func scrobble(id: String, submission: Bool, timeMs: KotlinLong?) async throws
    func getLyricsBySongId(id: String) async throws -> Lyrics?
    func getLyrics(artist: String?, title: String?) async throws -> Lyrics?
    func lyricsFor(songId: String, artist: String?, title: String?) async throws -> Lyrics?
    func streamUrl(id: String, maxBitRate: KotlinInt?, format: String?) -> String
    func coverArtUrl(id: String, size: KotlinInt?) -> String
    func close()
}

class SubsonicException: KotlinException {   // kind, code, httpStatus: KotlinInt?, isAuthFailure, message
    init(kind: SubsonicErrorKind, code: Int32, message: String, httpStatus: KotlinInt?, cause: KotlinThrowable?)
}
// SubsonicErrorKind: generic(0) missingParameter(10) clientTooOld(20) serverTooOld(30) wrongCredentials(40)
//   tokenAuthNotSupported(41) authMechanismNotSupported(42) conflictingAuth(43) invalidApiKey(44)
//   notAuthorized(50) trialExpired(60) notFound(70) network(-1) http(-2) parse(-3);  .code
// AlbumListType: newest recent frequent random alphabeticalByName alphabeticalByArtist starred highest;  .apiValue

class Lyrics { synced: Bool; lines: [LyricLine]; language: String?; text: String; func lineIndexAt(positionMs: Int64) -> Int32 }
class LyricLine { timeMs: Int64; text: String }
class LrcParser { static shared; func parse(raw: String) -> Lyrics }
class MediaRef { provider: String; rawId: String; key: String }      // "subsonic:al-1"
class SongInfo { ref, id, title?, artist?, artistId?, album?, albumId?, track: KotlinInt?, disc: KotlinInt?,
                 durationSec: KotlinInt?, coverArtId?, year: KotlinInt?, genre?, starred: Bool, starredAt?,
                 userRating: KotlinInt?, suffix?, contentType?, bitRate: KotlinInt?, size: KotlinLong?, path?, playCount: KotlinInt? }
class AlbumInfo { ref, id, name, artist?, artistId?, year?, songCount?, durationSec?, coverArtId: String, starred, starredAt?,
                  userRating?, playCount?, created?, genre?, releaseType?, songs: [SongInfo] }
class ArtistInfo { ref, id, name, albumCount?, coverArtId?, starred, starredAt?, albums: [AlbumInfo] }
class ArtistIndex { name: String; artists: [ArtistInfo] }
class PlaylistSummary { ref, id, name, owner?, isPublic: KotlinBoolean?, comment?, coverArtId: String, songCount?, durationSec?,
                        created?, changed?, canWrite: Bool, ownedByMe: KotlinBoolean? }
class PlaylistDetail { summary: PlaylistSummary; entries: [SongInfo] }
class SearchResult { artists: [ArtistInfo]; albums: [AlbumInfo]; songs: [SongInfo] }
class Starred { artists: [ArtistInfo]; albums: [AlbumInfo]; songs: [SongInfo] }
class ServerInfo { apiVersion: String?; type: String?; serverVersion: String?; openSubsonic: Bool }
```

The exact Objective-C declarations are in `shared/build/XCFrameworks/debug/YoinShared.xcframework/ios-arm64-simulator/YoinShared.framework/Headers/YoinShared.h` after a build (search for `swift_name("SubsonicClient")`). A Swift file calling every method above was type-checked against the framework (`swiftc -typecheck`), and the app builds with it linked.

## Dependencies

| Library | Version | Where |
|---|---|---|
| Ktor client (`ktor-client-core`) | 3.4.3 | commonMain |
| `ktor-client-darwin` | 3.4.3 | iosMain |
| `ktor-client-okhttp` | 3.4.3 | androidMain |
| `ktor-client-mock` | 3.4.3 | commonTest |
| kotlinx-coroutines-core | 1.10.2 | commonMain |
| kotlinx-serialization-json | 1.12.0 (catalog's) | commonMain |
| kotlinx-coroutines-test | 1.11.0 (catalog's `coroutines-test`) | commonTest |

Why 3.4.3 and not 3.6: it brings coroutines 1.10.2, okhttp 5.3.2 and serialization 1.9.0, all at or below what Android already resolves, so the Android app's existing dependency versions don't move (checked with `:app:dependencies --configuration debugRuntimeClasspath` before and after: only new `io.ktor`, `kotlinx-io`, `kotlinx-coroutines-slf4j` and `slf4j-api` entries; R8 on `:app:minifyReleaseWithR8` passes and strips all but one Ktor class). Ktor 3.5+ would pull coroutines 1.11.0 into the app. Responses are decoded from text with kotlinx-serialization (`ignoreUnknownKeys`, `isLenient`, `coerceInputValues`) rather than Ktor's ContentNegotiation, because servers label JSON inconsistently and error pages come back as HTML; so `ktor-client-content-negotiation` isn't a dependency.

## Tests

```sh
iosApp/scripts/with-lock.sh ./gradlew :shared:allTests   # JVM host + iOS simulator
```

- `SubsonicClientTest` (34): every endpoint through Ktor's `MockEngine` with JSON adapted from Android's `SubsonicModelsTest` / `SubsonicMappersTest` — auth query (`u t s v c f`, Subsonic's own `sesame` / `c19b2d` example, no `p`), field mapping and null handling, playlist ownership rules, star / unstar id kinds, rating clamp, scrobble params, every error code, HTTP / parse / network failures, lyrics (structured, offset, unsynced, fallback, auth not swallowed), URL builders.
- `Md5Test` (5): RFC 1321 vectors, block-boundary lengths, UTF-8, the Subsonic doc token.
- `LrcParserTest` (11): Android's cases plus `[mm:ss:xx]`, offsets and `lineIndexAt`.
- `SubsonicLiveSmokeTest` (androidHostTest and iosTest, 1 each): skipped unless `YOIN_SUBSONIC_SMOKE_URL` is set. Runs every call against a real server through the real engine (OkHttp / NSURLSession) and fetches a stream and a cover URL. With the promo demo server:

  ```sh
  python3 ~/Developer/Yoin-archive/promo-demo-2026-11-02/demo_subsonic.py 4977 &
  YOIN_SUBSONIC_SMOKE_URL=http://127.0.0.1:4977 SIMCTL_CHILD_YOIN_SUBSONIC_SMOKE_URL=http://127.0.0.1:4977 \
    iosApp/scripts/with-lock.sh ./gradlew :shared:testAndroidHostTest :shared:iosSimulatorArm64Test --tests '*SubsonicLiveSmokeTest*'
  ```

  2026-11-04: both engines passed (ping, lists, album, artists, artist, playlists, playlist, search, starred, random, star / unstar / rating / scrobble, synced Japanese lyrics with 27 lines, cover 200 image/jpeg, stream 200 audio/wav).

## Not done (not in scope yet)

- **Nothing in the iOS app calls the client yet.** Views read `Model/MockLibrary.swift`, and the playback engine's `Playback/DemoQueue.swift` builds its own `rest/…` URLs (written in parallel tonight). Next step: a Swift repository on `SubsonicClient` feeding the views, and `DemoQueue` / `PlayableTrack` built from `SongInfo` + `streamUrl` (+ `scrobble` from `PlayerEngine`).
- **Android still uses Retrofit.** Moving `app/` onto this client (and deleting the Retrofit one) is a separate migration.
- **Playlist writes**: `createPlaylist`, `updatePlaylist` (rename, add, remove), `deletePlaylist` are on Android but not here.
- **Other providers**: Spotify (Web API + App Remote) and Apple Music (MusicKit) sources, and the `MusicSource` / `Capability` abstraction around them.
- **Third-party lyrics**: NetEase, QQ, LRCLIB, Huawei providers, candidate matching, translations, the lyrics cache.
- **Persistence and sync**: Room tables (ratings, play history, notes, memories, detail cache), cloud sync (`SyncEngine`, `SnapshotCodec`, Drive appDataFolder), profiles and encrypted credentials. iOS needs its own Keychain storage for the password or token / salt.
- **Other Subsonic endpoints**: `getAlbumList2` by year / genre, `getTopSongs`, `getSimilarSongs2`, `getArtistInfo2`, `getUser`, `getOpenSubsonicExtensions`, API-key auth (OpenSubsonic `apiKey`), `download`, transcoding decisions (Android's network-aware quality, #121).
