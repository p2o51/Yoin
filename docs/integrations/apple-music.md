# Apple Music integration

Status (2026-09-26): Apple Music is a formal Profile provider. Existing encrypted validation authorization migrates once into the encrypted per-profile store without changing the active account. Authorization now creates or reconnects a Profile. Library albums/artists/playlists, catalog search, detail pages and the MusicKit-backed main playback session are implemented. 2026-09-29: real-account playback verified on the Pixel Tablet (Android 17 beta) — see Validation.

2026-10-01 implementation and physical validation: catalog and personal-library search now have separate provider capabilities and scopes; saved songs load from the paginated library endpoint. Search and Now Playing expose a separate song-library action, with authenticated membership confirmation before showing a stable checkmark. Two actual additions, personal-library read-back, three window sizes, playback and account switching were checked on Pixel Tablet. See the [current validation record](provider-alignment-validation-20261001.md) for the 427 JVM tests, three native tests, installed optimized APK and remaining boundaries.

## Implemented

`data/remote/applemusic/` contains a dedicated API client with per-profile token suppliers, user storefront, song search, library-song pagination and an add-to-library request. Ordinary catalog requests omit the Music User Token. Personal requests, including a catalog song's personal `library` relationship, require it. Redirects and pagination outside the expected API collection are rejected. Error messages exclude response bodies and credentials. A 403 is access denied, not proof that authorization was revoked.

The raw add request returns `AcceptedPendingConfirmation`. `AppleMusicSource.addToLibrary` first checks the exact authenticated catalog-song → library relationship, submits an unsaved catalog song once and checks up to five times (immediately, then after 0.5, 1, 2 and 4 seconds). Only an actual `library-songs` resource produces `LibraryMembership.Added`. An accepted request that remains absent or cannot be confirmed stays `Pending`; a later membership query can resolve it. Rechecking a pending addition in the same source instance does not submit another POST. Coroutine cancellation propagates. This pending state is held per profile source in memory, not persisted across process/account changes. No removal request is provided.

Live endpoint behavior observed on Pixel Tablet on 2026-10-01: an existing saved song's personal catalog → library relationship confirms `Added`, while the unsaved catalog song `1440899467` returns HTTP 404 from that relationship. The client now treats only a relationship 404 as absence, and only after a separate public catalog request returns HTTP 200 with the exact matching `songs` resource ID. Missing/mismatched catalog resources and all authorization/access failures remain failures. This is live-observed behavior, not a general assumption that every API 404 means unsaved. Successful read verification and this endpoint observation do not themselves prove that a new library write completed.

The separate native write check then added `ocean eyes` (`applemusic:1440899467`) on that subscribed account: membership changed from `NotAdded` to `Added` through the exact authenticated relationship. The immediate personal-library search still returned no songs, showing that search indexing can lag membership confirmation. The native write test now polls personal search for up to 30 seconds and reports search-index confirmation separately from the already confirmed library membership; the write must not be downgraded just because the search index has not caught up.

`search()` uses `/v1/catalog/{storefront}/search`; `searchLibrary()` uses `/v1/me/library/search` with library song/album/artist/playlist types. `getLibrarySongs()` uses paginated `/v1/me/library/songs` with catalog and navigation relationships, separate from random-song support. Apple Music declares `CATALOG_SEARCH`, `LIBRARY_SONGS` and `LIBRARY_ADD`, without declaring `RANDOM_SONGS` or favorite semantics.

Song mapping uses catalog IDs only when Apple supplies them. Library-only imports retain their library ID and cannot be assumed to support subscription playback. A supplied catalog relationship joins the library and catalog identities; no title matching is used. Library membership does not set the favorite-heart field.

### Full catalog albums and library membership (2026-10-02)

Pixel Tablet forensics traced the "cannot open this album" reports to a Compose crash, not an API failure: Apple can hold two `library-songs` for one catalog song (seen in the library album *Addison*: Aquamarine, catalog 1809015437, library ids `i.ZOM0Va9uv095aYK` and `i.V7BavAXI41xPpDo`), and `AppleMusicSong.toTrack()` collapses both onto the catalog id, so the album detail list (and the Songs tab at that row) threw `Key "applemusic:1809015437" was already used`. Four JavaCPP `maxPhysicalBytes` crashes in the same period are the separate MusicKit native-memory problem covered above.

Albums now follow the Spotify model. A library album (`library:l.…`) is requested with `include=artists,catalog`; when Apple knows the catalog album, `getAlbum` returns that FULL catalog album (`/v1/catalog/{sf}/albums/{id}?include=artists,library`, then its `/tracks`; since 2026-10-10 the request order is as in [Album open in two round trips](#album-open-in-two-round-trips-2026-10-10)), and the library album's own `/tracks?include=catalog` marks which catalog tracks the user has added. A catalog album opened from search resolves its library copy through the `library` relationship instead. Marked tracks carry `appleMusicLibraryId`; every track of a resolved album carries `appleMusicLibraryChecked=true`, which `YoinRepository.getAlbum` turns into shared `LibraryMembership` state (Added / NotAdded, never overriding a Pending write) and which also invalidates album detail rows cached before this format. Library albums without a catalog match keep their library tracklist. Track lists are deduplicated by identity in `getAlbum` and `getLibrarySongs`, and the album detail and Songs lists use index-qualified lazy keys as a second guard. `/v1/me/library/albums` is listed with `include=catalog`, so Library grid entries open under their catalog identity; `recently-added` does not take `include`, so those entries resolve on open.

In the album detail, Apple Music rows show `TrackLibraryButton` (check when added, add icon otherwise, refresh while pending) in the slot where favorite providers show the heart; the same row callback adds an unchecked song. Imported library songs with no catalog match (`Track.isUnplayableAppleImport`) are dimmed with a "?" badge on the cover; tapping the row or the badge expands the reason instead of attempting MusicKit playback. Verified on the Pixel Tablet on 2026-10-02: *Addison* opens with 12 catalog tracks and checks on the added ones, the Songs tab scrolls past the former duplicate, the imported *Aloud* row shows its explanation, and the Now Playing library action confirmed a catalog add (`I Knew You Were Trouble`, now in the test account's library). The request count per album open in this paragraph no longer holds: see [Album open in two round trips](#album-open-in-two-round-trips-2026-10-10) for the 2026-10-10 order and count.

### Album open in two round trips (2026-10-10)

Pixel Tablet timing (Apple Music profile, side-by-side columns) put a first, network-bound library album open at p50 2.6 s / p90 3.2 s, from three serial steps: the library album, then the catalog album, then the two tracklists. It is now two:

1. The library album (`include=artists,catalog`) **and** its `/tracks?include=catalog`, together: that path is known from the start and the read is needed however the album opens (marks on the catalog album, or the tracklist itself when there is no usable catalog match). The one read serves both; it is never repeated. Its failure is weighed exactly where the one-after-the-other read weighed it: a token or rate-limit failure still fails the album, any other leaves the catalog album unmarked, and the library album's own failure still wins.
2. The catalog album with `include=artists,library&include[songs]=albums,artists`. Apple's [type-scoped parameters](https://developer.apple.com/documentation/applemusicapi/handling-resource-representation-and-relationships) apply to every `songs` resource in the response, and the album's `tracks` relationship includes objects by default (300 at most, `next` when there are more), so the embedded songs arrive as `/tracks?include=albums,artists` would return them. That request is skipped when `relationships.tracks.data` is present and non-empty, has no `next`, and every song has `attributes` plus `albums` and `artists` relationship data (the per-song navigation targets `AppleMusicSong` reads). Otherwise the separate tracklist request runs as before, with its bare-tracks retry.

If the catalog album request with `include[songs]` fails with 400 or 5xx (the include may be what Apple refused), the personal read is retried without it, and the separate tracklist request starts at the same moment instead of after it; a 404/403 goes straight to the public `include=artists` read (unmarked) as before. Debug builds log every `include*` parameter of a failed request. A searched catalog album with a library copy still needs the copy's tracklist after the album (two steps).

Requests per album open, not counting the storefront (the source keeps it after its first read):

| Album opened | Requests |
| --- | --- |
| Library album with a catalog match, tracklist embedded | 3: library album, library `/tracks`, catalog album |
| Same, embedded tracklist incomplete or absent | 4: plus the catalog `/tracks` |
| Library album without a catalog match | 2: library album, library `/tracks` |
| Catalog album (search, Home), tracklist embedded | 1, or 2 with the copy's library `/tracks` when the user has a library copy |
| Same, embedded tracklist incomplete or absent | plus the catalog `/tracks` |

A refused `include[songs]` (400/5xx) adds the album read without it; a refused `/tracks` include adds the bare-tracks read. Reads are cancelled with the caller: when the library album fails or comes back empty, the open returns at once and its library `/tracks` read in flight is cancelled instead of waited out (up to the 20 s call timeout). The library add `POST` is not cancelled, so leaving the screen right after Add does not lose the write. The client's OkHttp dispatcher allows as many calls per host as in total (64), keeping the parallelism the earlier blocking calls had instead of OkHttp's default of 5 per host. Not yet verified on the device: whether Apple honours `include[songs]` here and returns complete embedded songs; the debug `http` lines show it (no `/v1/catalog/<storefront>/albums/{id}/tracks` line after the album).

A library album that resolves to its catalog album is then also cached under the catalog id, in mem and on disk (`YoinRepository.getAlbum`'s alias copy). The visit row, plays and Memories name the album by that id, and Home's hero footnote read it by that id about a second after the page opened, whenever the visit made it Home's new hero (the activity insert, then Home's 1 s debounce): a second full load (`detail.load id=applemusic:<digits> src=net` plus two requests). That read is now a mem hit. The copy is only taken from a network fetch, never from a disk or fallback copy, and only when no detail invalidation landed while the fetch was out.

### Library artist portraits (2026-10-10)

A `library-artists` resource has a name and no artwork. Library › Artists (`/v1/me/library/artists?limit=100`) and a library artist's own page (`/v1/me/library/artists/{id}`) are now read with `include=catalog`, and the artist's portrait is its catalog artist's `artwork` (template sized to 600). The id stays the library one, so the row still opens the library artist page with the user's own releases; nothing about the album identity model changes. Next-page links get the include back the same way they get the page size. An artist with no catalog match (imported music), or whose catalog artist has no artwork, keeps no portrait: the list shows the placeholder icon and the artist page falls back to its first release's cover, as before. Library search still lists library artists without the include. Artist pages opened before this change can show the cached cover until the detail cache revalidates (after 2 h on the next open, or 7 days).

## Developer token service contract

Configure an HTTPS endpoint returning HTTP 200 JSON:

```json
{"developerToken":"<signed ES256 developer JWT>"}
```

`AppleMusicDeveloperTokenProvider` caches the token in memory until 60 seconds before its JWT expiry. It checks shape and freshness; Apple verifies the signature. The endpoint must be supplied by the app operator. The app does not receive or generate an Apple private key. No service URL, JWT or private key is committed. Settings → Add account → Apple Music accepts this endpoint. The endpoint and Music User Token are encrypted using the existing Android Keystore cipher and saved atomically under noBackupFilesDir. They are never placed in an Intent, saved UI state, or ordinary preferences. On upgrade, ProfileManager moves the legacy validation account into the regular encrypted credentials store. The old file is cleared only after persistence succeeds. Retrying after a partial migration does not create a duplicate; migration at the profile limit preserves the old authorization.

## Provider and playback boundaries

- `AppleMusicSource` keeps library IDs namespaced as `library:<id>`. Catalog-backed songs share a canonical track identity; album tracks retain their parent album and artist navigation targets. Paginated detail relationships are followed to completion with same-origin and same-path validation.
- `AppleMusicPlaybackService` owns the formal MediaSession. PlaybackManager connects its existing MediaController to this service for Apple Music; direct streams continue through PlaybackService/ExoPlayer and Spotify through App Remote. No Apple preview URL enters ExoPlayer or the audio cache.
- The MusicKit adapter exposes queue metadata, current item, pause/play, skipping/seeking, repeat/shuffle, appending/clearing, errors and preparation timeout. Developer tokens refresh through the source while the service lives. Account switch/delete stops the outgoing audio; reauthorization replaces that account's encrypted credentials. The service releases its session when a profile switch starts or `activeSource` stops being its source (System UI keeps it bound, so `stopService` alone would leave a media-button target that resumes Apple Music under another profile).
- MusicKit queue semantics: `getQueueItems()` returns only the items *after* the current one; `playbackQueueIndex` is the current item's index in play order (0 after enabling shuffle). `AppleMusicMedia3Player` publishes played + current + upcoming in that order, with MusicKit `playbackQueueId`s as uids, and maps seeks to `skipToQueueItemWithId` / `skipToPreviousItem`.
- The session callback accepts URI-less items carrying `appleMusicCatalogId`; Media3's default `onAddMediaItems` rejects items without a `localConfiguration`.
- Library membership is not a favorite. Apple Music currently has no FAVORITES, RANDOM_SONGS or PLAYLISTS_WRITE capability in Yoin. Unsupported hearts, random-song and favorite tabs, and playlist creation/edit affordances are gated. The Songs tab browses the saved library and the distinct library-add control confirms the write. Library imports without a catalog playback ID fail with a clear message. Favorite mutation, library removal, offline downloads, Cast and quality claims are not enabled.

### Native process-memory guard (2026-10-01)

Physical Pixel Tablet playback followed by responsive size changes and an Apple Music → Spotify account switch hit the vendor decoder's `SVAudioDecoderJNI.reset` with `physicalBytes (515M) > maxPhysicalBytes (512M)`. This account-switch crash is captured in `outputs/provider-alignment-20261001/account-switch-crash-sanitized.log`.

Inspection of the unmodified playback AAR's `org.bytedeco.javacpp.Pointer` bytecode confirms its process-memory guard defaults to twice `Runtime.maxMemory()` and reads `org.bytedeco.javacpp.maxPhysicalBytes` once during static initialization. The value measures whole-process native/physical memory, so the Java heap allowance alone is an incomplete bound for Compose, decoded artwork and the DRM decoder together. [JavaCPP's primary API documentation](https://bytedeco.org/javacpp/apidocs/org/bytedeco/javacpp/Pointer.html) and [upstream implementation](https://github.com/bytedeco/javacpp/blob/master/src/main/java/org/bytedeco/javacpp/Pointer.java) now describe a four-heap default; the [maintainer's Android-specific issue discussion](https://github.com/bytedeco/javacpp/issues/468) explains the older false-positive risk.

Yoin sets only this documented property in `YoinApplication.onCreate`, before container or MusicKit initialization. `AppleMusicNativeMemoryPolicy` uses four times the Java heap allowance, bounds any increase by 1 GiB and one quarter of reported device RAM, and never lowers the bundled vendor's existing two-heap default. Unknown RAM preserves that default, and explicit preexisting JavaCPP properties are respected. The tracked native-pointer `maxBytes` guard, pointer collection and retry behavior remain unchanged. No SDK binary is patched and memory checks are not disabled. This finite heuristic adds headroom for the observed false-positive threshold; it does not guarantee physical memory availability or prove the absence of a decoder leak.

Budget unit tests cover tablet, limited/unknown RAM, the RAM cap, larger preexisting defaults and integer overflow. The native SDK smoke test confirmed the initialized 1 GiB physical budget and unchanged 256 MiB tracked-allocation budget. On the final optimized APK, subscribed-device pause/resume, portrait/landscape changes and playing Apple Music → Spotify account switching succeeded; pre-switch RSS was about 732 MiB and the app process remained alive. Long-duration decoder stability remains unverified.

### Source audit and physical-device probe (2026-10-08)

This pass checks the current workspace at `782d8651` with debug-only measurement additions.
It does **not** establish the cause of subscribed-playback heat. Before any installation,
the tablet's Settings listed only two Subsonic QA profiles; no Apple Music profile was available.
The existing debug signing certificate was verified before `adb install -r`; app data was not cleared.
The installed diagnostic APK is `app/build/outputs/apk/debug/app-debug.apk`, version 0.5.0 (5).

Source comparisons:

- The actual Apple SDK 1.1.2 download in `Downloads/AndroidMusicKitSDK1.1.2.zip` includes
  JavaCPP **1.4.4** (embedded Maven metadata). Its official `sdk-test-app.zip` sets both
  JavaCPP limits to `0` before loading JNI, and enables `largeHeap`. The archive changelog
  ends at 2021-11-16; this alone is not proof that no newer distribution exists.
- [Cadenza's initialization fix](https://github.com/CadenzaApp/Cadenza/commit/7fcef86f7d698da43c800d5405102fbefe87a775)
  agrees on setting properties before `loadLibrary`. Yoin already sets its property in
  `YoinApplication.onCreate` before loading MusicKit; the device assertions below confirm it works.
- Bundled `Pointer` bytecode agrees with [JavaCPP 1.4.4](https://github.com/bytedeco/javacpp/blob/1.4.4/src/main/java/org/bytedeco/javacpp/Pointer.java):
  registering an owned pointer checks physical memory, and **exceeding** either enabled limit
  can trigger up to ten GC/sleep/trim retries while holding the deallocator-class monitor.
  Being below but near the physical limit does not itself trigger those retries. `maxBytes`
  limits JavaCPP-tracked allocations, not total process RSS; the two numbers must not be conflated.
- Yoin's `setContentPositionMs(long)` **already** uses
  [Media3 1.10.0's advancing position supplier](https://github.com/androidx/media/blob/1.10.0/libraries/common/src/main/java/androidx/media3/common/SimpleBasePlayer.java).
  Its ticker nevertheless invalidates the whole state and reconstructs play order every second,
  even without playback. The production service ignores `onObservation`; the validation service
  still consumes it. Removing the ticker should preserve that observation contract and verify
  seeks, buffering, pause, track transitions and system controls on a subscribed account.
- SDK bytecode creates an `AppleMusicPlayback` partial wake lock and Wi-Fi lock type 3.
  [Android's current Wi-Fi documentation](https://developer.android.com/reference/android/net/wifi/WifiManager#WIFI_MODE_FULL_HIGH_PERF)
  says API 34+ substitutes a low-latency lock, with connected/foreground/screen-on conditions.
  A request for the old flag does not prove continuously disabled Wi-Fi power saving on Android 17.
- [Kaset's 30-track window](https://github.com/alidogangullu/Kaset-Player/commit/25f2dff)
  is a confirmed project workaround. It is not evidence of a universal 100-track SDK limit;
  Yoin's long-queue failure and the need for a window remain unverified.
- Current `AppleMusicPlaybackService` releases its player/session when the source changes or
  switching starts. Recreating a controller after release is not by itself a leak. The official
  sample also ties controller ownership to a service, rather than promising process-long ownership.

Physical Pixel Tablet, isolated ADB server **5038**, Android 17:

| Check | Observed result | Scope |
|---|---|---|
| Display | Only a 60 Hz physical mode; active render rate 60 Hz | A sustained 120 Hz explanation does not apply to this tablet |
| Effective JavaCPP limits | `maxPhysicalBytes=1073741824`, `maxBytes=268435456` | Read from initialized `Pointer`, not just System properties |
| Idle controller, 20 s | Physical bytes about 251.6–253.2 MiB; tracked bytes 280; no GC | No authorization, queue or audio decoder workload |
| Ticker, stable 5–20 s | 15 calls, mean 1.498 ms, maximum 2.055 ms | Debug build, empty queue; wall time, not CPU time or energy |
| Release | No ticker calls during the following 2 s | First controller |
| Five create/release cycles | Java thread count returns to 24 each time; tracked bytes stay 280 | Short unloaded-controller probe, not proof against playback leaks |
| GC / contention | One background concurrent mark-compact GC in the full probe; largest recorded monitor-contention slice 1.321 ms | No explicit-GC burst or recorded JavaCPP monitor stall in this run |
| Trace integrity | No nonzero Perfetto error-severity stats | 45 s recording, 32.4 s instrumentation test |

`AppleMusicNativeMemoryPolicyTest` passed all five JVM cases; both existing SDK/session device tests
and the new opt-in probe passed. `assembleDebug`, `assembleDebugAndroidTest`, `ktlintCheck` and
`git diff --check` passed. The current ktlint task only reported Kotlin-script checks, so this is
not a claim of complete Kotlin-source formatting coverage. The diagnostic build launched back to Home.

Local evidence is under `outputs/musickit-audit-20261008/`: `instrumentation.txt`, `idle-probe.txt`,
`idle-controller.perfetto-trace`, `analysis.sql`, `analysis.csv`, `steady-idle.csv`, `display.txt`,
`signing-check.txt`, and before/after screenshots. The trace processor found no dropped-data errors.
The opt-in test runs with instrumentation arguments
`-e class com.gpo.yoin.player.applemusic.AppleMusicMemoryProbeTest -e musickitMemoryProbe true`;
ordinary test runs skip it. Debug builds emit `YoinMusicKitMemory` once per controller creation
and `Yoin.MusicKit.publish.ticker` / `.callback` trace sections, without credentials or track metadata.

Pending an authorized Apple Music profile: record the same queue in foreground Now Playing,
paused foreground, background and screen-off playback; then stress next/previous and long queues.
Compare JavaCPP physical/tracked bytes, explicit GC, scheduler stalls, frames, Wi-Fi lock state and
thermal status under matched conditions. This dock-powered debug run cannot establish energy use.
Keep the existing limits, queue behavior and animation policy until those measurements are available.

### Subscribed playback on the Pixel Tablet (2026-10-09)

The pending measurements above, taken with the owner's Apple Music profile on the same tablet:
debug build, dock-powered, 60 Hz, ADB over Wi-Fi (which also keeps the Wi-Fi rail busy), Perfetto
with ODPM power rails. MusicKit's own threads are inflated in debug builds (the SDK still builds the
strings of its stripped log calls on every 10 ms tick; R8 removes them in release), so the screen-off
figures are an upper bound until a release/profileable rerun.

| Scenario | Yoin, % of one core | MusicKit threads | Frames | Notes |
|---|---|---|---|---|
| Library albums + detail pane, playing | 93% | ExoPlayer 10%, decoder 5% | 60 fps, full window | wifi.bt 288 mW |
| Same page, paused | 72% | ~0 | 60 fps, full window | wifi.bt 141 mW |
| Now Playing full screen with lyrics, playing | ~100% | ~15% | 60 fps | |
| Screen off, playing | 31% | ExoPlayer 14%, decoder 15% | none | cpu.little 85 mW, wifi.bt 151 mW |

- Screen-on heat is the UI: every frame redraws the whole 2560×1600 window (grid marquees, wave,
  playing indicator), playing or not, on any provider. Screen-off cost is MusicKit's embedded
  ExoPlayer 2.6 loop (10 ms `doSomeWork`) plus software AAC decoding; Yoin's own work there is the
  1 s ticker (1–1.5 ms per call on screen, 6–12 ms on the little cores screen-off, where it also
  contends with MusicKit's queue lock) — about 1% of a core.
- Playing adds ~150 mW on the Wi-Fi rail with the screen on and none with it off, consistent with
  the SDK's `WIFI_MODE_FULL_HIGH_PERF` lock becoming a low-latency lock only while the screen is on
  (inferred, not isolated).
- Memory: no growth over 10 minutes of screen-off playback or 25 rapid skips (VmRSS 452 → peak 561
  → 471 MB, anonymous 163–190 MB, threads 79 → 87 then flat). Opening full Now Playing peaked at
  ~603 MB. About 157 MB of VmRSS is `/dev/mali0` and dma-buf mappings that `smaps` omits but
  `/proc/self/statm` — JavaCPP's `physicalBytes` — counts, so UI graphics push MusicKit toward its
  guard: the vendor's 512 MB default would have tripped; the 1 GiB budget left ~400 MB. The six
  explicit GCs seen ran on binder threads (system-initiated), not JavaCPP's retry loop.
- Seen once, not reproduced: after a queue ran out with the screen off, the main thread recomposed at
  60 Hz with no frames drawn for minutes while the Activity was stopped.

Fixed in the same pass (verified on this device): a song tapped after Play next / Add to queue
failed with a misleading subscription error because MusicKit's default REPLACE prepare refuses to
drop an Up Next — prepare now uses `INSERTION_TYPE_CLEAR_AND_REPLACE`; searched catalog albums whose
`/tracks?include=albums,artists` Apple answers with 500 or 400 (207192046, 206356495, 1452580932)
now refetch the bare tracklist; a repeated MusicKit queue id no longer crashes Media3's playlist
check; library search lists one row per catalog identity.

## Validation for the Profile integration

- Full JVM suite: 297 tests, no failures. New cases cover idempotent migration, preserving authorization at the account limit, credential serialization, catalog navigation IDs, complete playlist pagination, cross-origin pagination rejection, read-only playlist semantics and rejecting unmatched imports.
- Native ARM64 Android 16 (API 36) instrumentation: `AppleMusicSessionSmokeTest` passed. It initializes the official SDK, sets a queue starting at its second item, preserves metadata, appends and clears, then releases. It deliberately does **not** claim subscription/audio playback proof.
- Debug and minified debug-signed builds compile. Apple SDK stack-map warnings remain the vendor warnings described below.
- Pixel Tablet, 2026-09-29, subscribed account: library/catalog load, full-length audio (past the 30 s preview), NP open/collapse, pause/resume/seek, next/previous and queue jumps in order, shuffle on/off without changing the current song, background + media-button control, end of queue, account switch stops audio and releases the session as soon as the switch starts, lock-screen media card (play/pause/next/previous/seek). Reconnect could not be verified: the SDK deep-links into the installed Apple Music app, which on this tablet shows "Error Loading Library" and returns RESULT_CANCELED at once, surfaced as USER_CANCELLED. Still pending: reconnect with a signed-in Apple Music app, delete, Bluetooth hardware. The SDK logs the developer token in the `deeplinkAppleMusic` logcat line.
- Android 17 flags the SDK's `libappleMusicSDK.so` and bundled `libc++_shared.so` as RELRO-misaligned for 16 KB pages (PageSizeMismatchDialog); Yoin's own LOAD/zip alignment passes.

## Sources checked 2026-10-01

- [Official Android SDK and overview](https://developer.apple.com/musickit/)
- [User storefront](https://developer.apple.com/documentation/applemusicapi/get-a-user's-storefront)
- [Catalog search](https://developer.apple.com/documentation/applemusicapi/search-for-catalog-resources-(by-type))
- [Personal library search](https://developer.apple.com/documentation/applemusicapi/search-for-library-resources)
- [Library songs](https://developer.apple.com/documentation/applemusicapi/get-all-library-songs)
- [Add a resource to a library](https://developer.apple.com/documentation/applemusicapi/add-a-resource-to-a-library)
- [Catalog song relationship by name](https://developer.apple.com/documentation/applemusicapi/fetch-a-relationship-on-this-resource-by-name-56rq7)
- [Catalog song library relationship](https://developer.apple.com/documentation/applemusicapi/songs/relationships-data.dictionary)

Apple documents that the add endpoint returns HTTP 202 without a response body, may ignore IDs it cannot add and may delay a resource's appearance. An accepted request alone is therefore insufficient membership evidence.

`AppleMusicProfileIntegrationTest` has two separately enabled native account checks. `-e verifyAppleMusicProfile true` is read-only: it uses the currently active encrypted Apple Music profile to verify catalog search, saved-song listing, personal library search and exact membership, reporting only result counts and the membership enum. `-e verifyAppleMusicLibraryAddition true -e appleMusicSearchTerm <term>` explicitly enables a real addition of the first catalog-song result; it reports the public title/MediaId, before/after membership and the exact personal-library search match. Failure output contains only the stage and a typed API failure or exception class. Neither check switches profiles, removes content or logs tokens. The UI add flow was additionally exercised on the optimized APK with `Ocean Eyes (Blackbear Remix)`, then verified in personal-library search.

SDK was downloaded by the user from the authenticated official Apple Developer download entry on 2026-09-06. Archive SHA-256: `02b36be75a63e0c630fcb297b3367d8350a07bf37fc5e613ec26d24344e7f72b`. AAR hashes and source are in `app/libs/README-apple-music.md`.

The playback AAR supplies ARM64 and ARMv7 libraries only. Both ARM64 ELF files have 64 KB LOAD alignment; that static check does not replace a 16 KB device test. D8/R8 report stack-map warnings for Apple's prebuilt Java bytecode. The minified build passes with four narrow exclusions for JavaCPP desktop Maven annotations and optional SLF4J classes; the shipped consumer rules otherwise keep JNI code. No SDK binaries have been patched.

Mock HTTP tests verify request semantics, not live Apple account integration. A device smoke test initializes/releases the real native controller without starting playback. The no-credential Settings UI and system back were checked on Android 16 with large text in dark mode. Evidence is in `outputs/apple-music-sdk/`.
