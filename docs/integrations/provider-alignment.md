# Provider Alignment & Multi-Platform Matrix (0.6.3+)

Yoin supports three music providers — **Subsonic / OpenSubsonic** (including Navidrome), **Spotify**, and **Apple Music** — behind a neutral `MusicSource` domain model across **Android**, **iOS & iPadOS**, **macOS** (iPad-on-Mac), and **HarmonyOS**.

Yoin intentionally keeps **playback**, **favorites (stars/likes)**, **library membership**, **playlists**, and **local ratings/notes** as separate concepts. An Apple Music library addition (`+` -> `✓`) is never conflated with a server star or Spotify Liked Track, and local 0.5–5.0 ratings and timestamped notes remain profile-scoped across every provider.

---

## 1. Provider × Feature Matrix (0.6.3+)

| Capability | Subsonic / OpenSubsonic | Spotify | Apple Music |
| :--- | :--- | :--- | :--- |
| **Platform Availability** | Android, iOS/iPadOS, macOS, HarmonyOS | Android, iOS/iPadOS, macOS (Web API + desktop Connect; **absent on HarmonyOS**) | Android, iOS/iPadOS, macOS, HarmonyOS |
| **Playback Transport** | Direct stream (`DirectStream` via Media3 / `AVQueuePlayer` / OHOS `EnginePlayer`) | External delegate (`SPTAppRemote` + Web API queue on Android/iOS; Web API Connect to local desktop Spotify on macOS) | Native SDK / Media3 (`AppleMusicPlaybackService` on Android; `MusicKit` on Apple; ArkWeb + Music API on HarmonyOS) |
| **Audio Cache & Offline Downloads** | Auto stream cache (~500 MiB on Android; `CachedAudio` on Apple) + Offline downloads (`Subsonic` only) | Handled in Spotify app only | Handled in Apple Music app only |
| **Cast / Multi-Device Output** | Chromecast (Android) + LAN DLNA speakers (Android/OHOS) + AirPlay (Apple) + `AVCastPickerHelper` (OHOS) | Spotify Connect (managed inside Spotify app; `SpotifyAudioOutput` on Mac routes via Connect) | System Bluetooth / AirPlay / OHOS output (`AVAudioSession` / `AVCastPickerHelper`) |
| **ReplayGain (Volume Leveling)** | Supported (`Off` / `By track` / `By album`; iOS/macOS `AVAudioMix` clamps `linear <= 1.0`) | Not exposed | Not exposed |
| **Spatial Audio** | Multichannel streams passed to OS `Spatializer` / Apple Spatial Audio monitor | Handled by Spotify app | Apple Music playback handles supported spatial/lossless streams per OS |
| **Queue: Play Next / Enqueue / Reorder / Remove** | Full support (`Play Next`, `Add to Queue`, reorder, remove) | `Add to Queue` supported; `Play Next`, reorder, remove unavailable via external App Remote | `Play Next`, `Add to Queue`, remove supported; manual queue reorder unsupported; **known MusicKit quirk**: adding to queue while a `Play Next` exists appends to tail, and toggling Shuffle can shift `Play Next` |
| **Playback Start Window** | Full list | `SPOTIFY_START_MAX_URIS = 100` tracks around start index (`StartWindow`) | `100` tracks around start index on Android multi-provider list starts (`ArtistPlayTracks`) |
| **Favorites / Library Membership** | Two-way star (`star`/`unstar`) on songs, albums, artists | Heart = Liked Tracks (`PUT/DELETE /v1/me/library`, `contains` in batches of `40`), Save Album, Follow Artist | Library addition (`+` -> `✓` after `202 Accepted` read-back); no public API for track unfavorite or artist follow |
| **Playlists: Create / Edit / Delete** | Supported when server owner/user permissions allow (`readonly` flag & owner check) | Supported for owned/collaborative playlists; remove via `DELETE /v1/me/library`; foreign playlists return `403` on reads since 2026-02 | Browse & read only on catalog/library; no playlist delete or foreign edit |
| **Artist Page Top Tracks ("Most Played")** | Supported (OpenSubsonic/Subsonic `getTopSongs`) | **Empty** (Web API `top-tracks` removed in 2026-02 Dev Mode changes) | Supported via catalog top-songs relationship |
| **Made For You (`HomeSection.made_for_you`)** | Not applicable | Personal/editorial mixes (Android supplements Web API `404` editorial `37i9…` playlists via App Remote `SpotifyContentBrowser` up to `200`; not yet in iOS UI) | Personal mixes (`v1/me/recommendations` `pl.pm-…`; Android + HarmonyOS) |
| **Spotify Elsewhere Pill** | — | Polls other configured Spotify profiles on Home header (`5s` display; Android + iOS) | — |
| **Localized Track/Album Names** | Metadata as served by user's server | `SpotifyNameLanguages` (`Accept-Language` + ISRC locale) for lyrics `nativeNames` fallback (Android) | `AppleMusicNameRegion` (`Off` / `Original` / `MainlandChina` / `Japan`) + `AppleMusicNameResolver` (`300` songs / `100` albums) across Android, **iOS/macOS**, and HarmonyOS |
| **Lyrics Sources** | Server lyrics first (`getLyrics` / `getLyricsBySongId`) + provider search/manual fallback | Auto chain: `QQ Music` → `NetEase` → `LRCLIB` (+ manual `Huawei Music`); Android adds `otherNames` + MusicBrainz ISRC + Online AI fallback | Auto chain: `QQ Music` → `NetEase` → `LRCLIB` (+ manual `Huawei Music`); Android adds `otherNames` + MusicBrainz ISRC + Online AI fallback |
| **Cloud Sync Identity Matching** | Automatic: normalized server host + username (`AccountFingerprints`) | Automatic: Spotify user ID (`/v1/me`) | Manual linking on target device (`Drive` Apple Music descriptor) |

---

## 2. Current API Caps & Platform Quirks (0.6.3+)

1. **Spotify 2026-02 Development Mode Contracts**:
   - **Dashboard allowlist (`403`)**: Dev Mode Client IDs authenticate allowlisted Spotify accounts only; users can enter their own Client ID in Settings.
   - **Pagination & batch caps (verified in `SpotifyApiClient.kt` & iOS `SpotifySource.swift`)**:
     - Search: max `10` items per type (`MAX_SEARCH_LIMIT = 10`).
     - Artist albums: max `10` items per request (`ARTIST_ALBUMS_PAGE_LIMIT = 10`, capped at `100` releases; `appears_on` omitted).
     - `GET /v1/me/library/contains`: max `40` URIs per batch (`LIBRARY_CONTAINS_BATCH = 40`).
     - Playlist item mutations (`POST/DELETE /playlists/{id}/items`): `100` URIs per request.
     - Library & playlist read caps: Android `SpotifyLibrarySyncCoordinator` caches the newest **`200`** saved albums/tracks in its local mirror while `SpotifyApiClient` pages up to `2,000` liked tracks / `1,000` collections / `10,000` playlist backup tracks; iOS `SpotifySource` defaults Liked Songs & Album track reads to **`300`**, Followed Artists to `2,000`, and Playlist backups to `.max`.
2. **Apple Music Contracts**:
   - Library additions via `POST /v1/me/library` return `202 Accepted` before catalog membership propagates; Yoin reads back library state before showing `✓`.
   - Album responses embed up to `300` tracks inline; Android MusicKit requires 16 KB ELF RELRO alignment and >`Int.MAX_VALUE` catalog ID ASM patches (see [apple-music.md](apple-music.md)).
3. **Subsonic / OpenSubsonic Playlist Mutability**:
   - Explicit `readonly: true` or a named owner differing from the authenticated username disables edit affordances; legacy servers omitting both remain editable.

---

## 3. Historical Baseline — 2026-10-01 Initial Alignment Audit

> Retained below for provenance. Note that Spotify library read caps on Android (`SpotifyApiClient`) were later expanded beyond the early 200-item prototype mirror, multi-platform support was added, and cross-provider Playlist Cloud Backup & Restore is specified in [../playlist-transfer.md](../playlist-transfer.md).

Yoin keeps search, playback, favorites, library membership, playlists and local ratings as distinct concepts. An Apple Music library addition is not an Apple favorite. A Subsonic star and a Spotify liked song remain their existing heart actions. Local ratings and notes remain profile scoped for every provider.

### Official API findings (2026-10-01)

| Area | Official contract | Yoin consequence |
| --- | --- | --- |
| Apple Music search | Apple exposes both catalog search and personal-library search. | Offer explicit library/catalog scopes and retain catalog-versus-library IDs. |
| Apple Music add to library | `POST /v1/me/library` accepts catalog IDs and requires a music user token. `202 Accepted` may precede the resource appearing in the library; ignored IDs do not prove membership. | An add request must distinguish acceptance from confirmed membership and refresh the library. Do not model this as a reversible favorite toggle. |
| Spotify catalog search | Current maximum `limit` is 10 per result type. | The former default 12 was invalid. Use 10 and clamp caller values to the documented range. |
| Spotify remove playlist | The February 2026 migration replaces `DELETE /playlists/{id}/followers` with `DELETE /me/library` and a playlist URI. | Remove/unfollow playlists through the shared library endpoint. |
| Subsonic playlist permissions | `getPlaylists` returns playlists the user may play, while updates are owner-only. OpenSubsonic adds an optional `readonly` flag. | Public foreign-owned playlists and explicitly read-only playlists cannot expose editing actions. Pass the authenticated username into mapping. |

Sources read on 2026-10-01:

- [Apple Music search](https://developer.apple.com/documentation/applemusicapi/search)
- [Add a resource to a library](https://developer.apple.com/documentation/applemusicapi/add-a-resource-to-a-library)
- [Spotify Search for Item](https://developer.spotify.com/documentation/web-api/reference/search)
- [Spotify February 2026 migration guide](https://developer.spotify.com/documentation/web-api/tutorials/february-2026-migration-guide)
- [Spotify Remove Items from Library](https://developer.spotify.com/documentation/web-api/reference/remove-library-items)
- [OpenSubsonic getPlaylists](https://opensubsonic.netlify.app/docs/endpoints/getplaylists/)
- [OpenSubsonic updatePlaylist](https://opensubsonic.netlify.app/docs/endpoints/updateplaylist/)
- [OpenSubsonic playlist response](https://opensubsonic.netlify.app/docs/responses/playlist/)

### Bounded correctness repairs & validation (2026-10-01)

- Spotify search uses the 10-per-type page limit; playlist removal sends `spotify:playlist:{id}` to `DELETE /v1/me/library`.
- Subsonic maps optional `readonly` and checks owner against username (`readonly: false` still blocks foreign-owned playlists).
- Now Playing renders `TrackLibraryButton` separately from local ratings for Apple Music.
- See the [2026-10-01 validation record](provider-alignment-validation-20261001.md) for the historical 427 JVM test and device check run.
