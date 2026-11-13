# Apple Music on HarmonyOS

> 2026-11-14. Written while porting Apple Music to the HarmonyOS build (branch `feat/harmony-flutter`).
> Android references are relative to `app/src/main/java/com/gpo/yoin` in the Android repo. This
> follows section 4 of the Notion page "Yoin 鸿蒙版技术选型": the catalogue and library are safe
> through the Apple Music API; full playback needs Apple's Android MusicKit native library running on
> musl, in personal builds only; MusicKit JS in ArkWeb most likely plays previews only.

## Recommendation

| Part | Verdict | Evidence |
| --- | --- | --- |
| Catalogue and library through the Apple Music API (`AppleMusicSource`) | **Go.** The code is written and tested, but not wired into the app yet. | Plain HTTPS/JSON with the same contract as Android. 52 unit tests with `MockClient` fixtures. |
| Sign-in (Music User Token) | **Go, through MusicKit JS in ArkWeb.** Needs a device check. | Android's sign-in deep-links into the Apple Music Android app, which HarmonyOS doesn't have. |
| Full-length playback through Apple's native MusicKit library | **Conditional go, for a time-boxed device probe only.** No-go for anything that gets distributed. | After the shim, the link-time gap is closed: zero unresolved imports. The FairPlay pieces are exported as plain C++ symbols, so no JVM or JNIEnv is needed. Two runtime hazards remain, and only a device run settles them (see Risks). |
| Playback through MusicKit JS in ArkWeb | **No-go for full tracks.** It is still useful for sign-in. | MusicKit JS needs EME with FairPlay (Safari) or Widevine (Chrome). ArkWeb most likely has neither, because HarmonyOS's DRM is WisePlay, so MusicKit JS would fall back to 30-second previews. Not checked on a device. |

The next step should be the device probe below (Phase 0, about half a day). Nothing beyond it is
worth starting until the probe passes.

## A. What landed: `lib/data/applemusic/`

| File | Android counterpart |
| --- | --- |
| `apple_music_source.dart` | `data/source/applemusic/AppleMusicSource.kt` |
| `apple_music_api.dart` | `data/remote/applemusic/AppleMusicApiClient.kt` |
| `apple_music_developer_token.dart` | `data/remote/applemusic/AppleMusicDeveloperTokenProvider.kt` |
| `apple_music_mappers.dart` | `data/remote/applemusic/AppleMusicSong.kt` and the mapping half of the source |
| `apple_music_credentials.dart` | `data/profile/ProfileCredentials.kt` (`AppleMusic`) |

Tests are in `test/data/applemusic/`. The fixtures are trimmed copies of Apple's JSON shapes, and the
developer token in them is a fake JWT that is never signed.

### What it does (same rules as Android)

- **Identity.** A song is identified by its catalogue id (digits) whenever Apple knows one. A library
  song (`i.…`) folds onto that id, taken from its `catalog` relationship or else
  `playParams.catalogId`, so it is one row everywhere and plays through MusicKit by that id. An
  import keeps `library:i.…`.
  - Albums, artists and playlists keep `library:<id>` for library resources and the bare id for
    catalogue ones.
  - A library album that lists its catalogue album opens as that full catalogue album (Spotify
    parity). An import, or a match the storefront no longer serves, keeps its library tracklist under
    the library id.
  - The `[Track]` model here has no `extras` map, so the source keeps an `AppleMusicSongFacts` side
    table for every row it hands out: library id, catalogue id, `catalogUnavailable`, ISRC.
    `AppleMusicSource.factsFor(id)` reads it.
- **Reads.** The library calls map as follows:

  | Call | Request |
  | --- | --- |
  | `getAlbumList(newest)` | `/me/library/recently-added`, albums only, 25 per page |
  | `getAlbumList(recent)` | `/me/recent/played` with `include[library-albums]=catalog`, at most 5 pages |
  | Every other order | `/me/library/albums?include=catalog` |
  | `getAlbumList(starred)` | Nothing (no request) |

  - Artists are read with `include=catalog`, so each one gets its catalogue portrait, and are grouped
    by first letter.
  - An artist's albums are read from the `albums` relationship, 100 per page.
  - A playlist's tracks skip music videos.
  - A library playlist that Apple refuses to read opens as its catalogue playlist (`playParams.globalId`)
    when it has one.
  - Search uses the catalogue: songs (with their albums and artists), albums, artists and playlists,
    25 of each.
- **Robustness from Android.**
  - The storefront is read once, single-flight: Android once sent 46 storefront reads in parallel and
    got 429s back.
  - When Apple refuses an `include` with a 400 or 5xx, the read is retried without it, and later pages
    drop it too.
  - When Apple refuses `include[songs]` on an album, later albums skip it.
  - Pagination links must stay on the same origin and path, and they keep the `limit` and `include`
    the first request sent.
  - The developer token is cached until 60 s before its `exp`, concurrent callers share one fetch,
    and the cache is dropped on a 401.
  - The Music User Token goes only on `/v1/me/...` requests.
  - Errors never contain a token, a query string or a response body.
- **Errors.** Failures map onto the interface's exceptions:

  | Failure | Exception |
  | --- | --- |
  | 401, or no Music User Token | `SourceAuthException` |
  | 403 | `SourceServerException(code: 403)`. Not "re-authenticate": Android saw library 403s that lasted minutes on healthy accounts. |
  | 429 | `SourceServerException(code: 429)` |
  | Socket, TLS or timeout | `SourceNetworkException` |
  | 404 on a detail read | `null` (the id doesn't exist) |

  For this source, `SourceServerException.code` is the HTTP status. For Subsonic it is the Subsonic
  error code.
- **Capabilities.** `{search, playlistsRead}`, which matches Android's
  `ServiceFeatureCatalog.appleMusic` as far as this interface goes:
  - No favourites and no ratings. `setFavorite` and `setRating` throw `UnsupportedSourceOperation`;
    Android refuses them the same way ("Manage this in Apple Music.").
  - No lyrics. Apple serves none to third-party apps, and Android returns `null` too.
  - No random songs.
  - No play reporting. MusicKit reports its own plays.
  - Android's `LIBRARY_ADD`, `LIBRARY_SONGS` and `PLAYLIST_IMPORT` have no counterpart in this
    interface yet. Following YAGNI, they wait for the feature that needs them.
- **Playback.** `handleFor` throws `UnsupportedSourceOperation`:
  1. `catalogIdForPlayback` first applies Android's checks. An import gets "no Apple Music catalog
     version". A song the storefront can't stream (no `playParams`, or an empty `catalog` answer) gets
     refused, because MusicKit would silently drop it and start that many songs further on.
  2. Every other song is refused as unsupported until a player exists.

  `PlaybackController._itemFor` doesn't catch this, so the play fails with an error instead of
  silently skipping the queue. That is the behaviour we want until a player lands.

### Wiring it in (shared files: the patch is below, nothing here is applied)

`ProfileCredentials` is `sealed` to `lib/data/profile/profile.dart`, so the subtype can't live in
this folder. Three changes are needed:

1. **`profile.dart`.** Move the class in and teach `fromJson` the `applemusic` type. The JSON matches
   Android's `@SerialName("applemusic")` fields `endpoint` and `musicUserToken`:

   ```diff
   --- a/lib/data/profile/profile.dart
   +++ b/lib/data/profile/profile.dart
   @@ static ProfileCredentials fromJson(Map<String, Object?> json) => switch (json['type']) {
            'subsonic' => SubsonicCredentials(
                baseUrl: json['baseUrl']! as String,
                username: json['username']! as String,
                password: json['password']! as String,
              ),
   +        'applemusic' => AppleMusicCredentials(
   +            endpoint: json['endpoint']! as String,
   +            musicUserToken: json['musicUserToken']! as String,
   +          ),
            final other => throw FormatException('Unknown credentials type', other),
          };
    }
   +
   +/// One Apple Music account: the developer-token service it was authorised with and the
   +/// Music User Token Apple issued at sign-in (Android ProfileCredentials.AppleMusic).
   +final class AppleMusicCredentials extends ProfileCredentials {
   +  const AppleMusicCredentials({required this.endpoint, required this.musicUserToken});
   +  final String endpoint;
   +  final String musicUserToken;
   +
   +  String get provider => MediaId.providerAppleMusic;
   +
   +  @override
   +  Map<String, Object?> toJson() => {'type': 'applemusic', 'endpoint': endpoint, 'musicUserToken': musicUserToken};
   +
   +  @override
   +  String toString() => 'AppleMusicCredentials(endpoint: $endpoint)';
   +}
   ```

   Then delete `lib/data/applemusic/apple_music_credentials.dart`. Also drop its `export` from
   `apple_music_source.dart`, and import `../profile/profile.dart` there and in the tests.
2. **`lib/main.dart`.** The factory switch is exhaustive:
   `AppleMusicCredentials() => AppleMusicSource(credentials),`.
3. **`lib/data/model/model.dart`, once a player exists.** Add
   `enum ExternalControllerType { spotifyConnect, appleMusicKit }` and give `ExternalController` an
   `id` (Android: `ExternalController(APPLE_MUSIC_KIT, catalogId)`). `handleFor` then returns it
   instead of throwing.

Settings needs an Apple Music service page to add an account (Android
`ui/settings/service/ServiceSetupActivity` plus `AppleMusicValidationViewModel`). Its copy must come
from the owner's copy model.

## Where the tokens come from (never copy their values)

### Developer token

- **What it is.** A short-lived ES256 JWT, signed with the owner's MusicKit private key (`.p8`).
- **Who signs it.** The token service in the Android repo at `services/apple-music-token/`
  (`worker.mjs`, `wrangler.jsonc`), deployed as the Cloudflare Worker `yoin-apple-music-token`.
  - The signing material lives only in that Worker's **Workers Secrets**: `APPLE_PRIVATE_KEY`,
    `APPLE_KEY_ID` and `APPLE_TEAM_ID`. The local Node variant reads `APPLE_PRIVATE_KEY_PATH`
    instead.
  - The `.p8` itself is kept outside every repository.
  - The service answers `GET /token` with `{"developerToken": "..."}`. It signs one-hour JWTs and
    re-signs once 5 minutes are left.
- **Where the URL is.** Android's public endpoint constant is `YoinTokenService.URL` in
  `data/remote/applemusic/YoinTokenService.kt`. The owner made it public on purpose on 2026-10-09:
  it hands out developer JWTs only, nothing account-specific.
- **On HarmonyOS.** `AppleMusicCredentials.endpoint` holds that URL. The Settings page should offer
  it as the default, like Android does. `AppleMusicDeveloperTokenProvider` accepts HTTPS only
  (loopback is allowed for a local service) and never sees a private key.

### Music User Token (MUT)

- **On Android.** `ui/settings/applemusic/AppleMusicValidationViewModel.kt` works in four steps:
  1. It fetches a developer token.
  2. It builds the sign-in intent with
     `AuthenticationFactory.createAuthenticationManager(app).createIntentBuilder(developerToken)`
     (from `musickitauth-release-1.1.2.aar`).
  3. That intent **opens the Apple Music Android app**, where the user approves.
  4. `handleTokenResult(intent).musicUserToken` comes back. The MUT is saved with the endpoint in
     the encrypted profile credentials, never in prefs or an Intent.
- **On HarmonyOS** there is no Apple Music app to hand off to, so the musickitauth AAR is useless.
  The working route is **MusicKit JS v3 in an ArkWeb page**:
  1. Load MusicKit JS with the same developer token (same Apple team, so the MUT is valid for the
     REST API and for MusicKit's `webPlayback`).
  2. Call `MusicKit.getInstance().authorize()`. ArkWeb needs `multiWindowAccess` and an `onWindowNew`
     handler for Apple's sign-in popup.
  3. The page returns the token through a JavaScript proxy into ArkTS, then a channel into Dart, and
     it is stored only in `SecretStore`.

  The user signs in with their own Apple ID and needs an active Apple Music subscription for
  anything but previews.
- **MUT lifetime.** Months. Apple can revoke it, which shows up as 401/403 on `/v1/me/...`.
  Reconnecting repeats the ArkWeb sign-in.

## B. Native playback spike: `spikes/applemusic/`

The spike is not part of the app or its build: nothing under `ohos/` and nothing in `pubspec.yaml`
references it. Apple's binaries are never committed. The owner extracts and patches them locally.

| Path | What it is |
| --- | --- |
| `shim/libam_shim.cpp` → `libam.so` | Everything the two Apple libraries import that OHOS musl lacks, and nothing else. Built without a C++ runtime of its own. |
| `tools/patch_musickit_so.py` | Writes patched copies: DT_NEEDED and SONAME renames in place, symbol versions cleared, and relocation and RELRO reported. Never patchelf, so program headers don't move. |
| `probe/musickit_probe.cpp` → `musickit_probe` | dlopens the patched set and resolves the 13 entry points a player needs. `--canary` and `--call-instance` are the two device checks. |
| `CMakeLists.txt` | Compile with the OHOS NDK toolchain. The configure and build commands are in its header. |

Verified on the Mac (compile only, no device): the shim and the probe build with
`-Wall -Wextra -Werror` against the API 26 NDK. `libam.so` needs only `libc.so`. Both patched Apple
libraries resolve every import against sysroot `libc.so`, `libam.so` and `libc++_ndk1.so`.

### What is inside the AAR (`mediaplayback-release-1.1.1.aar`, SHA-256 starts `39bc28cbc2413c0c`)

- **`jni/arm64-v8a/libappleMusicSDK.so`.** 6.8 MB, built with clang 8 (NDK r20 era).
  - Its NEEDED list is `libandroid liblog libOpenSLES libz libm libc++_shared libdl libc`.
  - It is linked `BIND_NOW`: one unresolved symbol fails the whole dlopen.
  - It has 214 `Java_*` JNI exports, plus `JNI_OnLoad` and JavaCPP's `JNI_OnLoad_jnijavacpp`.
  - It also has about 1,739 exported C++ symbols, including the whole FairPlay path:
    - `SVFootHillSessionCtrl::{instance, generateSessionExchange, generateSessionContext, decryptContext, resetSessionContext}`
    - `SVFootHillPContext::kdContext`
    - `FootHillConfig::config`
    - `SVData`
    - `SVDecryptorFactory::create`
    - `SVPastisDecryptor::decryptSample`
    - the SDK's own `SVAudioDecoder` (decrypt and decode) and `SVFuseAudioRenderer` (OpenSL ES)
    - an equaliser
    - a reimplementation of Apple's vDSP.
- **`jni/arm64-v8a/libc++_shared.so`.** The NDK libc++ (`std::__ndk1`), 977 KB.
- **`classes.jar`.** JavaCPP bindings (`org.bytedeco.javacpp`), Apple's playback layer
  (`com.apple.android.music.playback.*`), and an obfuscated ExoPlayer v2 fork (`com.a.a.a.*`) that
  does the HLS, the extraction and the buffering.

### Link-time gap

Measured with `llvm-nm`, the Apple imports compared with the OHOS API 26 sysroot `libc.so` exports:

| Library | Imports OHOS musl can't satisfy |
| --- | --- |
| `libappleMusicSDK.so` | `__android_log_print`, `__android_log_vprint`, `slCreateEngine`, `SL_IID_{ENGINE,PLAY,VOLUME,EQUALIZER,BASSBOOST,ANDROIDCONFIGURATION,ANDROIDSIMPLEBUFFERQUEUE}` |
| `libc++_shared.so` | `__errno`, `__sF`, `android_set_abort_message`, `strtoll_l`, `strtoull_l`, `__cxa_thread_atexit_impl` |

That is the entire list. Every other import (pthread, stdio, string, math, `dl*`, `arc4random`,
`sysinfo`, `memalign`, `dl_iterate_phdr`) exists in OHOS musl. The SDK uses no zlib and no
libandroid symbols, even though it lists both as NEEDED.

- HarmonyOS does ship an OpenSL ES, but it lacks the two `ANDROID*` interface IDs. The shim therefore
  exports all seven IDs and an engine that reports "unsupported".
- `libz.so` is a public NDK library. `libm.so` and `libdl.so` are reserved names that musl maps to
  `libc.so`.

### What playback needs, read from the Java layer (`javap` on `classes.jar`)

1. **Queue metadata.** `GET https://api.music.apple.com/v1/catalog/{sf}/songs?ids=…` with the
   developer token (`CatalogPlaybackQueueItemProvider`).
2. **Asset lookup.** `POST https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/webPlayback` with
   `salableAdamId`, `Authorization: Bearer <developer token>`, `X-Apple-Music-User-Token` and
   `X-Apple-Store-Front` (`playback.g.f`).
   - The reply (`playback.g.h`) carries `songList[].assets[]`, each with a `flavor` (MusicKit picks
     the `cbcp` HLS flavours), a `URL` (`.m3u8`) and a `file-size`.
   - It also carries `hls-key-cert-url` and `hls-key-server-url`.
3. **FairPlay certificate.** Fetched from `hls-key-cert-url` (`playback.g.a`; `fps-prod.crt` is its
   cache name).
4. **Key exchange (SPC).** `SVFootHillSessionCtrl::instance()->generateSessionExchange(keyUri, SVData(cert), adamId, …)`
   returns an `SVData` challenge (`foothill.a`).
5. **License request.** `POST hls-key-server-url` with JSON
   `{challenge, key-system: "com.apple.fps", uri, adamId, isLibrary, user-initiated}` and the same
   three headers (`playback.g.d`). The reply carries the CKC.
6. **Session context.** `generateSessionContext(keyUri, ckc)` returns a session context. A
   decryptor (`SVDecryptorFactory::create`) decrypts each sample (`SVPastisDecryptor::decryptSample`).
7. **Rendering.**
   - Inside the SDK, decrypted AAC goes through `SVAudioDecoder` to `SVFuseAudioRenderer`, then OpenSL
     ES.
   - On HarmonyOS, the host should decode with `OH_AudioCodec` (AAC) and render with
     `OH_AudioRenderer`. The SDK's OpenSL path stays unused, which is why the shim's engine may refuse
     to start.
8. **Play reporting.** `POST https://universal-activity-service.itunes.apple.com/play`, as Apple
   Music's "play activity" (`playback.reporting.e`). Optional, but the honest thing to send.

Because the JNI exports are thin JavaCPP wrappers over these C++ entry points, **a HarmonyOS host
needs no fake JavaVM or JNIEnv.** The probe never calls `JNI_OnLoad`. The host has to own steps 1–8
itself, roughly what Apple's Java layer and the ExoPlayer fork do:

- HTTP
- an HLS media playlist
- an fMP4 demux with per-sample IVs and subsamples
- the FootHill exchange
- decryption
- AAC decode
- an OHAudio sink
- an AVSession (already in `ohos/.../yoin/playback`).

### Architecture options

| Option | Plays | Effort | Verdict |
| --- | --- | --- | --- |
| 1. Run Apple's whole Java + native stack (JVM-on-HarmonyOS, or a JNI emulator that also implements Android framework classes) | Full | Months; open-ended | No. The Java layer needs Android's framework (Context, SharedPreferences, MediaCodec, Handler/Looper) plus an ExoPlayer. |
| 2. **Native host: C++ NAPI module calling FootHill and the decryptor; own HLS, demux, decode (OH_AudioCodec) and sink (OHAudio)** | Full | About 3–5 weeks after the probe passes | **The only realistic full-playback path.** Personal builds only. |
| 3. MusicKit JS in ArkWeb | 30 s previews | About 1 week | Not worth it as a player. Use ArkWeb for sign-in only. |
| 4. No playback in-app: browse in Yoin, "Open in Apple Music" on the web | None in-app | Days | A fallback if the probe fails. |

The effort for option 2 breaks down by phase:

| Phase | Work | Estimate |
| --- | --- | --- |
| 0 | Device probe (below) | ½ day |
| 1 | FootHill SPC/CKC round trip against Apple with the owner's account | 2–4 days |
| 2 | HLS plus fMP4 demux, decryption, AAC decode, OHAudio | 5–10 days |
| 3 | Queue, seek, gapless, AVSession and `PlaybackController` integration (a new `ExternalControllerType.appleMusicKit` engine next to the AVPlayer one) | 5–8 days |
| 4 | Token freshness (Android `MusicKitDeveloperToken`: renew 3 min before expiry, refresh before each Apple call), play reporting, error mapping | 2–3 days |

Each phase can hit a hard blocker (see Risks). Wiring `AppleMusicSource` into the app plus the
ArkWeb sign-in page is a separate 2–4 days, and it is worth doing whatever the probe says.

### Risks only a device run settles

1. **Stack canary, the biggest risk.** 494 functions in `libappleMusicSDK.so` read bionic's
   stack-protector canary from `[TPIDR_EL0 + 40]` (bionic's `TLS_SLOT_STACK_GUARD`).
   - Under musl, that word is whatever sits there in the static TLS block of the process's first
     module.
   - If anything writes it while an Apple frame is live, `__stack_chk_fail` aborts.
   - The probe's `--canary` prints it on four threads, twice each. It must read `stable` on every line,
     and ideally the same value over a few runs of an app process.
   - If it isn't stable, the fallback is to rewrite those 494 loads (an ASM-style binary patch, like
     Android's `yoin.musickit-large-id`), which is a much bigger job.
2. **Two C++ runtimes in one process.**
   - Flutter on OHOS loads the OHOS `libc++_shared.so` (`std::__n1`). Apple's is `std::__ndk1`, renamed
     `libc++_ndk1.so`, so the `std::` names don't clash.
   - The C ABI names are shared, though: `__cxa_throw`, `__gxx_personality_v0`, `_Unwind_*`,
     `operator new`, and `std::exception`'s typeinfo.
   - musl resolves symbols globally, in load order. Apple's code may therefore throw through OHOS's
     libc++abi. That works only if the two Itanium-ABI implementations agree.
   - AMFOrHarmonyOS_Base had to make NDK `__call_once` weak for a related reason.
   - Test it by calling `--call-instance`, then a deliberately failing `generateSessionContext`,
     which throws internally.
3. **`ios_base::Init` in NDK libc++.** AMFOrHarmonyOS_Base saw it crash in `use_facet` (a locale ABI
   mismatch) and patched it out (`tools/patch_ios_init.py` there). The SDK imports iostream
   symbols, so its static constructors may run this when it loads. If the dlopen crashes inside
   libc++, this is the first suspect.
4. **650 `R_AARCH64_RELATIVE` relocations with addends outside the image**, in `.data.rel.ro`
   (0x674ed0–0x676xxx).
   - They look like deliberate obfuscation: FairPlay white-box tables stored relative to the load base.
   - musl, like bionic, just adds the base. **Do not zero them**, as AMFOrHarmonyOS_Base did for a
     different Apple library: the decryptor would read garbage.
   - `patch_musickit_so.py` only reports them.
5. **Linker namespaces.**
   - HarmonyOS app processes dlopen only from the HAP's `libs/arm64-v8a/` root, so every library must
     sit flat there.
   - `RTLD_GLOBAL` preloading is unreliable, so the shim reaches libc++ through a rewritten DT_NEEDED
     instead.
   - Running `musickit_probe` from `hdc shell` exercises the default namespace, not the app's. A clean
     run there is necessary but not sufficient, and the final check is the same code in a NAPI module
     of a test HAP.
6. **RELRO and page size.**
   - Both libraries end RELRO on a 4 KB boundary but not a 16 KB one (0x679000 and 0xfe000).
   - On 4 KB-page devices that is fine. On a 16 KB-page HarmonyOS device, apply Android's fix
     (`build-logic` `MusicKitRelroPlugin`, `alignRelroEnd`).
7. **`sysconf` constants differ** between bionic and musl. Only JavaCPP's code calls it
   (`sysconf(0x60)`, bionic's `_SC_NPROCESSORS_CONF`; musl uses 83 for that name). A host that
   calls the C++ API directly never reaches it.
8. **Apple's side.** MusicKit for Android hasn't been updated since December 2021, and Apple can
   change `webPlayback` or the license protocol at any time. The MUT comes from a web sign-in rather
   than the Apple Music app, so `webPlayback` might treat it differently. MusicKit JS uses that same
   endpoint with a web MUT, so this is likely fine, but it is unverified.

### Running the probe (owner or the phone agent, personal device only)

1. Extract the AAR from the Android repo (`app/libs/mediaplayback-release-1.1.1.aar`) into a scratch
   directory outside every repo.
2. Patch both libraries:

   ```bash
   python3 -I spikes/applemusic/tools/patch_musickit_so.py --preset sdk jni/arm64-v8a/libappleMusicSDK.so out/libappleMusicSDK.so
   python3 -I spikes/applemusic/tools/patch_musickit_so.py --preset libcxx jni/arm64-v8a/libc++_shared.so out/libc++_ndk1.so
   ```

3. Build `libam.so` and `musickit_probe` with the commands in `spikes/applemusic/CMakeLists.txt`, into
   that scratch directory.
4. `hdc file send` the four files to one device directory. Then run
   `musickit_probe <dir> --canary`, and if that passes, `musickit_probe <dir> --call-instance`.
5. Pass means: `dlopen ok` three times, `PASS: 0 of 13 symbols missing`, every canary line `stable`,
   and `instance()` returns a pointer without aborting.
6. Then repeat the dlopen and the symbol checks from a NAPI module inside a test HAP. That HAP is a
   personal build, signed with the owner's own debug certificate.

## Legal and distribution

- **Apple's MusicKit for Android SDK** is licensed for Android apps. Running its binaries on
  HarmonyOS, patching them, and loading them outside Android is not covered by that licence. The
  same goes for doing so with a web-issued MUT.
- **Redistribution.** The AARs and `.so` files must never enter this repository, a HAP that leaves
  the owner's devices, AppGallery, or a public build. The spike only ever reads them from the
  Android checkout's `app/libs/` (which already holds them for Android) and writes patched copies to a
  scratch directory.
- **DRM.** Option 2 uses Apple's own FairPlay code, unchanged, to play a subscriber's own stream, and
  it keeps decrypted audio in memory only.
  - **Never** write decrypted samples to disk, to a cache or to a file the user can export. That is
    the line between a player and a ripper, and it is what anti-circumvention law cares about.
  - Leave Yoin's Subsonic audio cache out of the Apple path entirely, as Android does ("No Apple
    preview URL enters ExoPlayer or the audio cache").
- **Accounts.** The owner uses their own Apple ID and subscription. The developer token comes from
  the owner's Apple Developer membership through the existing token service. That service is public
  but rate-limited, and the JWT grants nothing without a user's MUT.
- **AppGallery and distributed builds.** Ship catalogue and library browsing only (option 2 removed),
  or no Apple Music at all. Treat native playback as a **personal-build-only** feature behind a
  compile-time flag that is off by default.

## Owner steps

1. **Developer token.** Nothing new is needed. The HarmonyOS account page should default to the same
   endpoint Android uses (`YoinTokenService.URL`, see above). If the Worker's signing key is ever
   rotated, update the three Workers Secrets as `services/apple-music-token/README.md` describes
   (`wrangler secret bulk` from a stream). Never put them on a command line or in a config file.
2. **Apple ID sign-in.** When the Settings page and its ArkWeb sign-in exist, sign in there with your
   own Apple ID. That stores the MUT in `SecretStore`. Until then, the source can be exercised only
   by tests.
3. **Decide on wiring.** Apply the `profile.dart` and `main.dart` patch above, or ask for it to be
   applied, so Apple Music can become a profile on HarmonyOS. Browsing works the moment it's wired.
   Playback refuses cleanly.
4. **Decide on the probe.** Allow half a day for Phase 0 on your personal device (Mate 70 Air or the
   MateBook Pro S), run as described above. If it passes, decide whether option 2 is worth 3–5 weeks for
   personal builds. If it fails on the canary or the C++ runtime, settle on option 4.
5. **Distribution flag.** If option 2 goes ahead, confirm that it stays out of any build that leaves
   your devices.
