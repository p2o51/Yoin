# v1 port spec: Playback chain and system integration (PlaybackManager/PlaybackState/queue, Subsonic stream URL and quality policy, scrobbling, local play history for Home and Library, MediaSession and AVSession, audio focus, background lifetime) mapped onto the HarmonyOS engine in lib/platform/playback and ohos/.../yoin/playback

> Generated 2026-11-13 by reading the Android app (read-only agents). Android refs are relative to app/src/main/java/com/gpo/yoin. The "Corrections" section at the end overrides anything above it.

## Overview

What the user sees and does, in order:
1. They tap a track row, an album Play or Shuffle button, a Home song card, or the Songs list. The shell asks the controller to start a queue. The bar and Now Playing show the tapped song at once, in the buffering (`Connecting`) phase. NP's `Playing` branch is chosen whenever `currentTrack != null`, so the user never sees `Launching` for Subsonic. Playback starts when the first audio is ready.
2. Audio continues with the screen off. The lock screen, 播控中心 and headset keys show title, artist, album, cover, progress and prev/play/next. A heart and a shuffle or loop control sit in the overflow. Their taps go through the same controller NP uses.
3. When a track ends, the next one starts according to repeat all / one / off and shuffle. Unplugging headphones pauses. Another app's audio pauses, ducks or resumes playback.
4. About 30 s or half a track in, the play is submitted to the Subsonic server (offline plays are queued on disk). Every started track also writes a local activity row, which feeds Home Activities, the hero and Library Recents.
5. Tapping the lock-screen or 播控中心 card brings Yoin forward with Now Playing open.

Android architecture: `PlaybackManager` is a client of a Media3 `MediaController`, talking to `PlaybackService`, which owns the ExoPlayer and the MediaSession. The service outlives the Activities. The queue lives in the player's timeline.

HarmonyOS mapping: `PlaybackEngine.ets` (AVPlayer plus queue) and `SystemSession.ets` (AVSession plus continuous task) play the role of the service, and a new Dart `PlaybackController` Notifier plays the role of PlaybackManager. The native side must own the queue so that auto-advance, headset keys and the lock screen work with Flutter's UI paused.

The existing engine already covers this much:
- A serialised load chain with generations.
- Prev threshold of 3 s.
- Duck volume of 0.2.
- Basic AVSession transport.
- A continuous task.

It is missing:
- Repeat and shuffle.
- Queue mutations.
- A `playWhenReady` field.
- A heart and loop mode in the session.
- Headphone-unplug handling.
- PixelMap artwork.
- A lifetime that is independent of the plugin attach/detach cycle.

Everything Dart-side (controller, tracker, activity log) is new.

## v1 must

### 1. 1. Dart PlaybackController (Notifier) = PlaybackManager facade

One Riverpod `Notifier<PlaybackState>` (`playbackControllerProvider`) plus a separate playhead provider, wrapping the `PlaybackEngine` from `playbackEngineProvider`. It subscribes permanently to `engine.states` from construction. `ChannelPlaybackEngine` only subscribes natively while someone listens, and the scrobble tracker needs events with the UI gone.

Public API, same names and semantics as Android:
- `play(List<Track> tracks, int startIndex, MusicSource source, {ActivityContext context = none, bool shuffled = false, bool explicitStart = true, int startPositionMs = 0, bool startPaused = false})`. Returns immediately if `tracks.isEmpty || startIndex` is out of range.
- `playSingle(track, source)` = `play([track], 0, source)`.
- `pause()`, `resume()`, `skipNext()`, `skipPrevious()`, `seekTo(ms)`, `setPlayMode(PlayMode)`.
- `addToQueue(List<Track>, source, {next})`, `removeQueueItem(i)`, `skipToQueueItem(i)`, `clearQueue()`.
- `disconnect()` for a profile switch.
- `livePositionMs()`.
- `playWhenLoaded(source, context, load: Future<List<Track>> Function())` for an artist's Play or Shuffle: reads every release, a newer start cancels it, a failed or empty read starts nothing.

`play()` flow, with `generation = ++playbackGeneration` at the top:
1. Capture `profileId`.
2. Set `_currentActivityContext = context`.
3. Set `lastRecordedTrackId = null`.
4. For each track, build an `EngineItem` through `source.playback.handleFor(track)` (pure URL signing on Subsonic).
5. After each `await`, abort silently if `generation != playbackGeneration` or the active profile id changed.
6. Call `engine.setQueue(items, startIndex, startPositionMs, playWhenReady: !startPaused, repeat, shuffle)` with the remembered play mode, so repeat and shuffle are written together with the queue as Android's `applyPlayMode` does.

Optimistic UI: before the engine echoes, publish `PlaybackState(currentTrack: tracks[start], queue, currentIndex: start, phase: connecting, playWhenReady: !startPaused)`. Android gets this synchronously from the controller. It avoids a one-frame Idle flash.

On any exception set `phase = error`, message `player_play_failed` (EN "Unable to play this song" / ZH "无法播放此歌曲"), log it, and never rethrow (Android scope has no handler, so everything is wrapped in runCatching).

Keep a parallel `List<QueueEntry{entryId (uuid per entry), Track track, bool userQueued}>` in Dart. The engine only knows `EngineItem` order. Every mutation bumps a `queueRevision` that the engine echoes in states. Dart ignores `index` from an event whose revision is older than its own.

**Strings (key | en | zh):**
- player_play_failed | Unable to play this song | 无法播放此歌曲
- player_init_failed | Unable to initialize playback | 无法初始化播放

**Android:** player/PlaybackManager.kt (play ~L420, startQueue ~L534, playWhenLoaded ~L457, executeOrQueue ~L1189, disconnect ~L365, buildMediaItem ~L1456); player/PlaybackState.kt; player/PlayableQueue.kt; ui/navigation/YoinNavHost.kt (playSongsList / startQueueFor ~L1840)

### 2. 2. Queue model and start rules (Subsonic)

The queue handed to the player is the caller's list, whole on Subsonic. Library Songs, an album and a playlist are not windowed, because `startWindowQueue` (100 tracks, 20 of history) only applies to Spotify and Apple Music. A Play or Shuffle button on a page passes a list `shuffled` by the caller (`ordered.shuffled()`) with `shuffled = true, explicitStart = false`. Shuffle play shuffles the list itself and does NOT touch the remembered play mode. A row tap passes `explicitStart = true`.

ActivityContext per entry point:
- Album page: `Album(albumId, albumName, artistName, artistId, coverArtId)`.
- Playlist page: `Playlist(playlistId, playlistName, owner, coverArtId)`.
- Artist page: `Artist(artistId, artistName, coverArtId)`.
- Favorites list: `LikedSongs` (records as a SONG event).
- Songs list, search result, Home song card, single: `None`.

`playSingle` plays a queue of one. A search result starts the found songs as a queue (Gemini review 2026-10-20). Home card taps wait up to 4 000 ms for the active source on a cold start, latest tap wins (`HomeSongTap`).

Queue entries carry a stable per-entry id (uuid), so the same song queued twice is two entries. `userQueued` marks entries added by Play next or Add to queue.

`addToQueue`:
- Play next inserts at `currentIndex + 1`.
- Add to queue inserts after the last consecutive userQueued entry that follows the current one.
- With an empty queue it just appends and does not start playback.

Mutations:
- `removeQueueItem` ignores the current index.
- `clearUserQueue` removes every userQueued entry except the current one.
- `clearQueue` empties the player and the state becomes Idle.
- `skipToQueueItem(i)` jumps to the default position of that item and resets `lastRecordedTrackId = null`. It also cancels a queue still being read.

Published with each state: `queue`, `queueEntryIds`, `userQueued`, `currentIndex`, `upcoming` (indices still to play after the current one, in play order, without wrapping, shuffle included) and `nextTrack`. `nextTrack` is the track that will actually play next, with shuffle order and repeat-all wrap, and null at the end of the queue or under repeat one. NP uses it for the lyrics outro.

**Android:** player/PlaybackManager.kt (startQueue, queueTracks ~L873, moveQueueItem/removeQueueItem/clearUserQueue ~L913-955, syncState ~L1086, upcomingOrder ~L1943); player/PlayableQueue.kt; player/StartWindow.kt; ui/detail/AlbumDetailActivity.kt (playFrom ~L120); ui/home/HomeSongTap.kt; data/repository/ActivityContext.kt

### 3. 3. Play mode: repeat x shuffle, six states, engine-side

`enum PlayMode {repeatAll, shuffle, shuffleRepeatOne, repeatOne, repeatOff, shuffleRepeatOff}`. Each is a (repeat, shuffle) pair:
- repeatAll = (ALL, off)
- shuffle = (ALL, on)
- shuffleRepeatOne = (ONE, on)
- repeatOne = (ONE, off)
- repeatOff = (OFF, off)
- shuffleRepeatOff = (OFF, on)

`next()` (round button, a Gray-code cycle, one axis per tap): repeatAll -> shuffle -> shuffleRepeatOne -> repeatOne -> repeatAll. From repeatOff go to repeatAll. From shuffleRepeatOff go to shuffle.

Other transitions:
- `withShuffle(on)` changes only the shuffle axis.
- `nextRepeat()` (split pill) goes ALL -> ONE -> OFF -> ALL with shuffle kept.
- `PlayMode.of(repeat, shuffle)` reads state back.

Default is repeatAll. The mode is NOT persisted across process death (design.md ~L148). `preferredPlayMode` is remembered in the controller and written together (both axes) at every queue start. A switch while playing writes only the axis that changed.

Engine contract additions (ArkTS):
- `setRepeat('off'|'one'|'all')` and `setShuffle(bool)`. `setQueue` also takes `repeat` and `shuffle`.
- Engine state fields `repeat`, `shuffle`.

Shuffle on Android is Media3's `DefaultShuffleOrder`, a random permutation fixed when the queue is set; next and previous follow it from the current item. Spec for HarmonyOS: keep `order: int[]`. On shuffle on, build `[current] + random permutation of the rest` (Fisher-Yates), so previous after enabling shuffle goes nowhere new, as in Spotify. On shuffle off, `order = identity` and `index` is kept. Inserted items get a random position after the current one in `order`. Removed items are dropped from `order`.

Manual next and previous also use the shuffle order. Under repeat ONE, manual next and previous still move (Media3 maps ONE to ALL for navigation, `getRepeatModeForNavigation`). Only auto-advance repeats the item.

**Strings (key | en | zh):**
- np_transport_repeat_all | Repeat all | 列表循环
- np_transport_shuffle | Shuffle | 随机播放
- np_transport_repeat_one | Repeat one | 单曲循环
- np_transport_shuffle_repeat_one | Shuffle, repeat one | 随机播放，单曲循环
- np_transport_repeat_off | Repeat off | 不循环
- np_transport_shuffle_repeat_off | Shuffle, repeat off | 随机播放，不循环
- np_transport_repeat | Repeat | 循环
- np_cd_play_mode | Play mode | 播放模式

**Android:** player/PlayMode.kt; player/PlaybackManager.kt (setPlayMode ~L840, applyPlayMode/switchPlayMode ~L1199, preferredPlayMode ~L90); docs/design.md ~L130-150 (play mode table); player/PlaybackState.kt (repeatMode, shuffleEnabled, playMode)

### 4. 4. Transport rules: pause/resume/next/previous/seek, playWhenReady

`playWhenReady` is a first-class field. The button shows PAUSE and toggles pause whenever `playWhenReady` is true, including through a buffering or seek dip. `togglePlayPause = playWhenReady ? pause() : resume()`.

Effective value: `playWhenReadyOf(intent, phase) = intent && phase not in {idle, ended}`. Add `playWhenReady` to `EngineState`; the engine already tracks the intent. In `ended` it must report false (Android shows PLAY). Same while the system suppressed playback for a transient focus loss.

`skipNext`:
- Moves to the next item in play order. With repeat ALL it wraps from last to first.
- With repeat OFF at the last item it does nothing.
- It keeps the paused or playing intent.
- Today's engine has no wrap and no shuffle order.

`skipPrevious` (Spotify rule, owner W2 2026-10-05):
- If `position > 3000 ms`, or there is no previous item, restart the current item at 0.
- Otherwise go to the previous item in play order.
- With repeat ALL at the first item it wraps to the last.
- The existing 3000 ms constant is right, and `index > 0` must become hasPrevious-with-wrap.
- The UI reads a restart via `restartedInPlace` (NP pulse), so the controller should expose whether the tap restarted. Compare the track id and position before and after.

`seekTo(ms)`:
- Clamp to `[0, duration]`.
- While loading, store it as `pendingStartMs`, which the engine already does.
- Seeking after `ended` revives the phase to ready.
- A seek on an unknown duration is not clamped above.

`resume()` at `ended`: Android calls `player.play()` and Media3's behaviour at ENDED was not verified here. Spec for HarmonyOS: restart from the first item of the play order, as Spotify does. The current engine replays the last item. Change it.

`resume()` in `error`: reload the current item at `lastPositionMs` (already implemented).

`skipTo(i)` on the current index restarts nothing visible: Android `seekToDefaultPosition(i)` resets to 0 without a new item, so the same play key and no new history row. The engine can keep `load(i, 0)`, but must not bump `repeatPlays`.

**Strings (key | en | zh):**
- np_cd_skip_next | Skip next | 下一首
- np_cd_skip_previous | Skip previous | 上一首
- np_cd_transport_play | Play | (not translated)
- np_cd_transport_pause | Pause | (not translated)

**Android:** player/PlaybackManager.kt (pause/resume/skipNext/skipPrevious/seekTo ~L774-820, skipToQueueItem ~L955); player/PlaybackState.kt (playWhenReadyOf); ui/nowplaying/NowPlayingViewModel.kt (togglePlayPause ~L756, skipNext/Previous ~L782-800, seekTo ~L815)

### 5. 5. Auto-advance, repeat one, end of queue (and gapless)

Engine `completed` handler must implement:
- Repeat ONE: replay the same item from 0 (seek 0 + play, or reload) and bump `repeatPlays` by 1. `repeatPlays` is exposed in state, and the scrobble `playKey = entryId#repeatPlays` depends on it.
- Otherwise take the next item in play order; with repeat ALL wrap to the first.
- Repeat OFF at the last item: phase becomes `ended`, `position = duration`, ticker stopped, background task released. UI shows PLAY, and the bar keeps the last song.

A one-song queue under repeat ALL repeats that song.

Gapless: Android does nothing special beyond ExoPlayer preloading the next item. There is no crossfade setting, and Spotify/Apple Music are unrelated. The current ArkTS engine does `reset -> setSource -> prepare -> play` per item, which leaves an audible gap of roughly 0.3-1.5 s, longer on cold HTTP. v1 accepts the gap (see defer). Cheap mitigation for v1: start `prepare()` of the next item as early as possible, i.e. immediately on `completed`, never waiting for UI or any other event.

**Android:** player/PlaybackService.kt (ExoPlayer.Builder; repeatMode default ALL); player/PlaybackManager.kt (onMediaItemTransition REASON_REPEAT -> repeatPlays ~L1033); docs/design.md ~L136-148

### 6. 6. connectionPhase, error handling, retry

`enum ConnectionPhase {idle, connecting, ready, error}`.

Derivation from the engine:
- No queue: idle.
- Engine `loading` (and a later buffering stall, if reported): connecting.
- `ready` or `ended`: ready.
- `error`: error.

Android sets Error from `player.playerError != null` and Connecting from `STATE_BUFFERING`. Connecting for Subsonic does not change NP, because the `Playing` branch wins whenever `currentTrack != null`. It matters only for Idle and Error.

On error:
- `PlaybackState.connectionErrorMessage` is set and NP shows its `ConnectError` state (title, artist, cover, message). Raw message if present else `np_msg_playback_interrupted`; with a pending track and no message `np_msg_couldnt_start`.
- There is NO auto-skip and no auto-retry on Android.
- Tapping play retries (the engine reloads the current item at its last position).
- HarmonyOS divergence: do not show the raw `BusinessError` text (`5400101: ...`). Log code and message through hilog with the URL masked, and show the localised `np_msg_playback_interrupted`.

Failure to even start (exception in handleFor or the channel) -> phase error with `player_play_failed`.

The controller must keep `currentTrack` through an error so NP can render the ConnectError card.

**Strings (key | en | zh):**
- np_msg_playback_interrupted | Playback was interrupted. | 播放已中断。
- np_msg_couldnt_start | Couldn't start playback. | 无法开始播放。
- player_play_failed | Unable to play this song | 无法播放此歌曲
- player_init_failed | Unable to initialize playback | 无法初始化播放

**Android:** player/PlaybackManager.kt (syncState connectionPhase ~L1113-1120, connectInBackground error ~L261, startQueue onFailure ~L650); player/PlaybackState.kt (ConnectionPhase); ui/nowplaying/NowPlayingViewModel.kt (baseUiState ~L519-610)

### 7. 7. Track -> stream (Subsonic) and quality policy for v1

`MusicPlayback.handleFor(track)` returns `DirectStream(uri)`: the signed `<root>/rest/stream.view?id=<raw>&u&t&s&v&c&f`. The Harmony source already does this. The salt is drawn once per source, so URLs are stable.

v1 sends the ORIGINAL file only: no `maxBitRate`, no `format`, no `timeOffset`, no `estimateContentLength`. That is Android's default on Wi-Fi, but Android's mobile default is Auto: wired -> original, Bluetooth -> 320, speaker -> 192, as mp3.

Why v1 skips transcoding:
- A transcode has no length. Android needs `estimateContentLength=true` (only when the server lists a bit rate), CBR mp3 seeking, and OpenSubsonic `transcodeOffset` + `timeOffset` for reopen seeks. AVPlayer has no equivalent hooks.
- `StreamQualityPolicy.plan` and `AudioOutputKind` detection need a Harmony network-type and audio-route API.

Future hook to keep: `StreamDelivery.plan(quality, sourceKbps, traits)` returns Original if `quality.maxBitRateKbps == null` or `sourceKbps <= cap`, else `(cap, mp3)`. `Track.audioFormat.bitRateKbps` is already mapped for it.

Do not log the URL (token and salt are in the query). Android masks it through `PlayTrace.describe`.

The `EngineItem` also carries:
- `id` = `MediaId.toString()` (`subsonic:<raw>`).
- `title`, `artist`, `album`.
- `durationMs = durationSec*1000`: a hint for the UI and AVSession before the real duration arrives. Android's duration fallback is `player.duration` if > 0, else `track.durationSec*1000`.
- `artworkUri` = `source.resolveCoverUrl(ref, size: 512)`. Android passes the size-less URL (original image), which is too heavy for a lock screen, so ask for 512.

AVPlayer needs Range support. Navidrome serves ranges for the original file.

**Strings (key | en | zh):**
- settings_stream_quality_title | Streaming quality | 播放音质 (settings UI is deferred; only the policy defaults matter)

**Android:** player/StreamQuality.kt (defaults: wifi Original, mobile Auto; Auto = Wired original / Bluetooth 320 / Speaker 192); player/SubsonicStreamResolver.kt (StreamDelivery.plan, estimateContentLength, timeOffset); data/remote/SubsonicStreamUrls.kt; data/source/subsonic/SubsonicMusicSource.kt (handleFor ~L260); player/PlaybackManager.kt (buildMediaItem ~L1456); harmony: lib/data/subsonic/subsonic_music_source.dart (_SubsonicPlayback.handleFor)

### 8. 8. Published state and position model (no 250 ms timer)

`PlaybackState` (v1 fields): `currentTrack`, `isPlaying`, `playWhenReady`, `durationMs`, `queue`, `currentIndex`, `queueEntryIds`, `userQueued`, `upcoming`, `nextTrack`, `repeat` (off|one|all), `shuffle`, `connectionPhase`, `connectionErrorMessage`, plus getter `playMode`.

Position is NOT in that state. This follows the position-dedup invariant (memory: NP position dedup) and `NowPlayingUiState` stripping `position` and `bufferedPosition`. Expose a separate `playheadProvider` with `{trackId, positionMs, sampledAtMs, isPlaying}`.

The UI extrapolates with a Ticker: `pos = positionMs + (now - sampledAtMs)` while `isPlaying`. Do not add a 250 ms Dart timer, which Android needs because Media3 does not push.

The controller republishes `PlaybackState` only when a non-position field changes. Engine ticks (1 s) update only the playhead.

Duration: engine `durationMs` if > 0, else `track.durationSec*1000`, else 0. The engine already sends the hint first, then `durationUpdate`.

Track lookup: `currentTrack = queue[currentIndex]` using the Dart-side entries, with `currentId` as a fallback when the index is stale. Never rebuild a Track from engine metadata on the hot path.

bufferedPosition: not published in v1.

**Android:** player/PlaybackState.kt; player/PlaybackManager.kt (syncState ~L1086-1141, startPositionUpdates ~L1143, POSITION_UPDATE_INTERVAL_MS=250); ui/nowplaying/NowPlayingViewModel.kt (positionMs ~L675-704)

### 9. 9. Scrobbling: now playing + submission + offline queue

Port `ScrobbleTracker` + `ScrobbleReporter` + `PendingScrobbles` to Dart as pure logic.

Only gated plays are fed: local engine, `track.id.provider == activeSource.provider`, `Capability.playReporting` in `source.capabilities`, and an active profile id.

Snapshot:
```
ScrobbleSnapshot{profileId, songId (rawId), playKey = "$entryId#$repeatPlays", isPlaying, positionMs, durationMs (engine duration, else track.durationSec*1000)}
```

Rule `ScrobbleRule`:
- `minTrackMs = 30000`, `maxListenMs = 240000`.
- `threshold = null` (never counts) if `duration <= 30000`.
- `threshold = 240000` if duration is unknown (<= 0).
- Else `threshold = min(duration/2, 240000)`.
- Listened time accrues only while playing, measured on a monotonic clock (Stopwatch), so a forward seek earns nothing and a pause does not count.

Tracker `update(snapshot?)`, called on every engine state:
- Session key = `(profileId, playKey)`. A different key, or null, closes the old session: accrue, checkDue, and, if announced, emit Stopped (only with reportPlayback).
- First `isPlaying` in a session emits `Started`, sets `startedAtWallMs = wall clock` and `announced = true`.
- `Submit(startedAtMs)` fires once when `listened >= threshold`.
- `msUntilSubmit()` schedules a Dart `Timer` so the play counts on time without other events. The 1 s native tick is the safety net if timers stall in the background.

What goes to the server:
- On `Started`: `scrobble?id=<raw>&submission=false` (now playing). Failure is dropped, never queued.
- A server listing OpenSubsonic `playbackReport` gets `reportPlayback?mediaId&mediaType=song&positionMs&state&ignoreScrobble=true` instead: `starting` on Started, then `playing` / `paused` on each change and on a seek (position more than 3 s from where it should be), and `stopped` at the last position when the session closes. Live or nothing, in order (ported 2026-11-17).
- On `Submit`: enqueue `PendingScrobble{profileId, songId, timeMs = startedAtMs (epoch ms)}`, then flush.

Offline queue (`PendingScrobbles`):
- Dedupe identical triples.
- `MAX_PENDING = 2000` (oldest dropped).
- Persisted as JSON through `KeyValueStore` key `scrobble.queue` (Android: `noBackupFilesDir/scrobble-queue.json`). An unreadable value is treated as empty.
- `flush` sends batches of 50 for the ACTIVE profile only (`scrobble` takes repeated `id` + `time` pairs, `submission=true`).
- Subsonic error 10 or 70 => rejected: drop the play.
- A rejected batch is split per play so one bad play does not block the others. Other errors (offline, auth, 5xx) keep the queue.

Flush triggers:
- After each Submit.
- When the active source changes.
- App resumed (a Harmony network-available callback is deferred).

Source API: Harmony `MusicPlayback` has only `submitPlay(id, DateTime)` and `reportNowPlaying(id)`. Add `submitPlays(List<PlayReport{MediaId, int timeMs}>)` (batched) and map errors 10/70 to a `PlayReportRejectedException`.

Also on profile switch or `disconnect()`: `tracker.update(null)`.

Why plain scrobble: Android also counts plays through plain scrobble when reportPlayback exists (`ignoreScrobble=true`), so a play counts once. reportPlayback carries live state only.

**Android:** player/scrobble/ScrobbleTracker.kt (ScrobbleRule, ScrobbleSnapshot, ScrobbleTracker.update, msUntilSubmit, SEEK_TOLERANCE_MS=3000); player/scrobble/ScrobbleReporter.kt; player/scrobble/PendingScrobbles.kt (MAX_PENDING=2000, BATCH_SIZE=50); player/scrobble/SourceScrobbleTarget.kt; player/PlaybackManager.kt (scrobbleSnapshotOf ~L1401, publishPlaybackState ~L1386, endScrobbleSession ~L403); data/source/subsonic/SubsonicMusicSource.kt (reportNowPlaying/submitPlays ~L266-285); AppContainer.kt (scrobbleReporter ~L546); YoinApplication.kt (flush on network ~L130)

### 10. 10. Local play history / activity log (what Home and Library read)

When a track STARTS playing, not on completion, Android runs `repository.recordPlay` inside a try/catch that logs and swallows, so a failed write never affects playback.

Trigger: `state.isPlaying` is true AND `track.id != lastRecordedTrackId`. Then set `lastRecordedTrackId = track.id`. It is reset to null on every `startQueue` and every `skipToQueueItem`. So repeat-one replays and consecutive same-id entries are not re-recorded.

Writes per started track:
1. `play_history` row: `{songId, profileId, provider, title, artist, album, albumId, coverArtId (CoverRef storage key: a URL or the bare cover id), playedAt = now, durationMs (engine duration else track.durationSec*1000), completedPercent = 0}`.
2. `activity_events` row (`PLAYED`) from `buildPlaybackActivity(track, ctx, profileId)`:
   - ctx `Album` -> entityType ALBUM, entityId = albumId, title = albumName, subtitle = artistName else track.artist.
   - ctx `Artist` -> ARTIST, subtitle "Artist".
   - ctx `Playlist` -> PLAYLIST, subtitle = owner else "Playlist".
   - ctx `LikedSongs` or `None` -> SONG, entityId = songId, title = track.title, subtitle = track.artist.
   - All rows carry `songId`, `albumId`, `artistId`, `coverArtId` (= `ctx.coverArtId` ?: track cover ?: album raw id as fallback on Subsonic), `profileId`, `provider`, `timestamp`.
3. `track_album_link` upsert (Memories only; not needed in v1).

Visits: `recordAlbumVisit` and `recordArtistVisit` (opening a detail page) write a `VISITED` ALBUM/ARTIST row. The album visit's cover key falls back to the album raw id.

Readers:
- Home Activities, `getRecentActivities(limit = 40)`: read `limit*8` rows newest first, collapse by key `actionType:entityType:entityId:provider` keeping the first (newest), take `limit`. The hero is the first ALBUM or PLAYLIST entry (`selectHomeHeroActivity`). The feed re-reads on every insert, debounced 1 000 ms.
- Library Recents `LibraryRecents{albums, artists, playlists: Map<rawId, epochMs>}` = max over ALBUM/ARTIST/PLAYLIST activity rows by entityId, merged with the per-album max `playedAt` from `play_history` (so a song played from the Songs list or a search still bumps its album). A new device has no data and Recents falls back to Recently added.
- Rediscover, JBI memory cards, ArtistTopSongs, play counts and other-profile feeds are deferred.

HarmonyOS storage (no Room/SQLite in the framework): a Dart `ActivityStore` over `KeyValueStore`, one JSON value per key and profile:
- `activity.feed.<profileId>`: newest-first capped list of `ActivityEvent` JSON, cap 300, trimmed on write. Fields as above. Keep `profileId` and `provider` in every row.
- `activity.lastSeen.<profileId>`: `{album:{id:ts}, artist:{...}, playlist:{...}}`, updated on every PLAYED or VISITED row and on each played track's albumId (as album), pruned to the newest 2 000 entries. It carries the Recents semantics beyond the 300-row feed.

Expose `Stream<List<ActivityEvent>> watchRecent(limit)` and `Stream<LibraryRecents> watchLibraryRecents()` that re-emit after each write.

Persist writes fire-and-forget, serialised in one queue, with debounced flush so a rapid skip-through does not rewrite the Preferences file per track.

**Android:** player/PlaybackManager.kt (recordPlayIfDue ~L1425, lastRecordedTrackId resets); data/repository/YoinRepository.kt (recordPlay ~L3405, buildPlaybackActivity ~L3716, collapseToLatestUnique ~L3800, getRecentActivities ~L3465, recordAlbumVisit/recordArtistVisit ~L3650-3690); data/local/PlayHistory.kt, PlayHistoryDao.kt; data/local/ActivityEvent.kt, ActivityEventDao.kt; data/repository/LibraryRecents.kt; ui/home/HomeViewModel.kt (HOME_ACTIVITY_LIMIT=40 ~L2532, observeRecentHistory ~L1556, RECENT_HISTORY_DEBOUNCE_MS=1000); ui/home/HomeEditorialContent.kt (selectHomeHeroActivity ~L3176)

### 11. 11. AVSession: metadata and artwork (lock screen / 播控中心)

Metadata maps from Media3 `MediaMetadata` to `AVMetadata`: `assetId = item.id`, `title`, `artist`, `album`, `duration` (ms), `mediaImage`.

Current code passes `mediaImage = artworkUri` (a string URL). The HarmonyOS docs allow `mediaImage` as a URL string OR an `image.PixelMap`. System rendering of authenticated or LAN URLs is device-dependent.

Spec: the ArkTS side fetches the cover itself and sets a PixelMap.
- Download via `@kit.NetworkKit http` with the Subsonic `getCoverArt&size=512` URL.
- Decode with `image.createImageSource(buffer).createPixelMap({desiredSize: 512x512})`.
- Cache the last 3 by URL.
- Set metadata immediately WITHOUT the image so title and progress show at once, then call `setAVMetadata` again with the PixelMap when it lands, guarded by the same item id.
- Keep the URL string as a fallback if the fetch fails.
- Release the PixelMap on track change.

Update rules (reduce IPC): call `setAVMetadata` when the item id or the duration changes. Today's `syncMetadata` does this correctly, but `apply()` also calls `setAVPlaybackState` on EVERY 1 s tick. Change it to call only on phase, isPlaying, index, loop, favorite or seek-discontinuity changes (the system extrapolates from `{elapsedTime, updateTime}`).

Playback state mapping (already there, keep): loading -> BUFFERING, ready+playing -> PLAY, ready+paused -> PAUSE, ended -> COMPLETED, error -> ERROR, idle -> IDLE. Add `loopMode`, `isFavorite`, `activeItemId = item.id`.

Metadata lifetime: the session is created lazily on the first non-idle snapshot (`ensureSession`: `createAVSession(context, 'Yoin', 'audio')` -> listeners -> `setLaunchAbility` -> `activate()`) and destroyed when `index < 0`. Keep.

Android's equivalent: Media3 `MediaMetadata` (title, artist, albumTitle, artworkUri, trackNumber) with the default BitmapLoader loading the full URL.

**Android:** player/PlaybackManager.kt (buildMediaItem MediaMetadata ~L1495-1519); player/PlaybackService.kt (MediaSession.Builder, setSessionActivity); harmony: ohos/entry/src/main/ets/yoin/playback/SystemSession.ets (syncMetadata, toPlaybackState)

### 12. 12. AVSession commands and custom actions (favorite, loop mode, transport)

Android session surface (`SessionQuickActions`):
- Custom commands `com.gpo.yoin.session.SHUFFLE` and `.FAVORITE` (and LIBRARY_ADD for Apple Music only).
- Two overflow buttons: a shuffle toggle (`icon ICON_SHUFFLE_ON/OFF`, labels `player_action_shuffle_on/off`) and a heart (filled or unfilled, labels `np_cd_remove_favorite` / `np_cd_add_favorite`), shown only when `Capability.favorites` and the track id is non-blank.
- Taps call the same controller/repository paths as NP:
  - Shuffle: `setPlayMode(playMode.withShuffle(!shuffle))`.
  - Favorite: guarded against a second tap during an in-flight write; `next = !(overrides[id] ?: track.isStarred)`; optimistic overlay; on failure toast `np_msg_couldnt_save_favorite` / `np_msg_couldnt_remove_favorite`.
- Previous, next, play/pause and seek are the player's own commands.

HarmonyOS AVSession mapping. In `ensureSession` register, besides play/pause/stop/playNext/playPrevious/seek:
- `on('toggleFavorite', (assetId) => emit command {name:'toggleFavorite', id})` and `setAVPlaybackState({isFavorite})`. Dart does the Subsonic `star`/`unstar` write, optimistically through the shared favorite overlay that NP's heart uses, then calls `engine.setFavorite(id, bool)` so the session heart reflects the result (reverts on failure).
- `on('setLoopMode', (mode) => ...)` and `setAVPlaybackState({loopMode})`. AVSession's `LoopMode` is ONE axis: SEQUENCE (off), SINGLE, LIST (all), SHUFFLE. Mapping from PlayMode: repeatAll -> LIST, shuffle -> SHUFFLE, repeatOne and shuffleRepeatOne -> SINGLE, repeatOff -> SEQUENCE, shuffleRepeatOff -> SHUFFLE. Reverse for system-initiated changes: LIST -> repeatAll, SHUFFLE -> shuffle (repeat all), SINGLE -> repeatOne, SEQUENCE -> repeatOff. The shuffle+repeat-one pair is lossy on the system surface; this is an accepted divergence because the system cycles its own order. The Gray-code cycle exists only inside the app.
- Commands the engine cannot answer itself are forwarded to Dart as `{type:'command', name, ...}` on the existing event channel. Verify the exact `AVSession.on(...)` signatures against the API 26 SDK before coding.
- Heart is hidden (not set) when the source lacks `Capability.favorites` or the id is blank.

Favorite failures: v1 reverts the heart silently and logs. A native toast on failure is deferred.

**Strings (key | en | zh):**
- player_action_shuffle_on | Shuffle | 随机播放
- player_action_shuffle_off | Turn off shuffle | 关闭随机播放
- np_cd_add_favorite | Add to favorites | 加入收藏
- np_cd_remove_favorite | Remove from favorites | 取消收藏
- np_msg_couldnt_save_favorite | Couldn't save to favorites | 无法保存到收藏
- np_msg_couldnt_remove_favorite | Couldn't remove from favorites | 无法从收藏移除
- np_msg_cant_save | Can't save — track info unavailable | 无法保存 — 缺少曲目信息

**Android:** player/SessionQuickActions.kt (SHUFFLE/FAVORITE commands, buttons(), toggleShuffle, toggleFavorite); player/PlaybackService.kt (MediaSession.Builder.setCallback); docs/design.md 后台播放与系统集成 (~L391-397); ui/nowplaying/NowPlayingViewModel.kt (toggleFavorite ~L1063, setFavorite ~L1096); data/repository/FavoriteStateOverlay.kt; harmony: SystemSession.ets (ensureSession listeners)

### 13. 13. Audio focus and becoming noisy

Android: `setAudioAttributes(USAGE_MEDIA, CONTENT_TYPE_MUSIC, handleAudioFocus = true)` and `setHandleAudioBecomingNoisy(true)`. Media3's behaviour:
- Permanent focus loss: pause, no auto-resume.
- Transient loss: pause with suppression, auto-resume on regain. `playWhenReadyOf` shows PLAY while suppressed.
- Duck: volume 0.2 (Media3's ducked multiplier).
- Headphones or BT disconnected: pause (`playWhenReady = false`), never auto-resumed.

HarmonyOS engine, already implemented:
- `STREAM_USAGE_MUSIC`.
- `on('audioInterrupt')` with a force/share split.
- Duck 0.2 / unduck 1.0.
- Pause + resume-on-gain for transient interruptions.
- Stop hint -> pause.

To add:
- Headphone unplug / BT disconnect / output device removed: subscribe on the AVPlayer to the audio output device change event (`on('audioOutputDeviceChangeWithInfo')`, reason `REASON_OLD_DEVICE_UNAVAILABLE`), or fall back to `audio.getAudioManager().getRoutingManager().on('deviceChange')` with `DeviceChangeType.DISCONNECT`. On that: `pause()` (playWhenReady = false, resumeOnFocusGain = false). Confirm the API names on API 26; HarmonyOS may already pause the stream itself and report it via `audioInterrupt` PAUSE, in which case the handler must not double-pause.
- A transient pause due to focus must not clear the user's intent permanently. Keep `resumeOnFocusGain` and `playWhenReady` exposed through the new effective `playWhenReady` rule (false while suppressed).
- `INTERRUPT_FORCE` pause/stop already mirrors the system's pause; keep.

No wake locks are needed; the continuous task covers the process.

**Android:** player/PlaybackService.kt (setAudioAttributes, setHandleAudioBecomingNoisy); player/SpatialAudio.kt (musicAudioAttributes L58); player/PlaybackState.kt (playWhenReadyOf, PLAYBACK_SUPPRESSION_REASON); harmony: PlaybackEngine.ets (onInterrupt, DUCK_VOLUME=0.2)

### 14. 14. Background lifetime, process model, resume after death

Android: `PlaybackService` (MediaSessionService, foreground notification) outlives Activities. `onTaskRemoved` stops the service unless `playWhenReady && mediaItemCount > 0`. PlaybackManager only ticks position while a Yoin Activity is started. The queue and position are NOT persisted: a killed process loses them, the notification disappears and the bar opens Idle. The play mode is not persisted either (design.md ~L148).

HarmonyOS today: `YoinPlaybackPlugin.onDetachedFromEngine` calls `session.release()` and `engine.release()`. The engine and session are created in `onAttachedToEngine` with `EntryAbility.context` (UIAbilityContext). So when the FlutterAbility is destroyed, or its Flutter engine detaches while the process lives, playback and the AVSession die with the plugin.

v1 spec:
- Move `PlaybackEngine` and `SystemSession` into a process-level singleton (`YoinPlaybackHost`). Plugin attach/detach only wires the channels and sink. While detached the sink is null and events are dropped. `stop()`, `setQueue([])` or process death are the teardown.
- On `EntryAbility.onDestroy`: if playing, keep the host alive only if the AVSession and continuous task survive the context (verify on device). Otherwise pause, persist the snapshot (below) and release. A user swipe-away of the task kills the process on HarmonyOS, like a hard stop; this is accepted (Android continues while playing, which has no HarmonyOS equivalent).
- New channel method `getSnapshot()` returning `{queue:[EngineItem...], index, order, repeat, shuffle, revision, state}`. On Dart cold start while the native host still holds a queue (ability re-created, process alive), the controller rehydrates its entries from it. The tracks come from the engine items' title, artist, album, artwork and duration, with `id` = MediaId string; build placeholder `Track`s and enrich later from the Subsonic source when Now Playing opens.
- Persistence parity with Android: no queue or position restore after process death in v1. Spec only what is needed: while playing, `lastPositionMs` already survives an error retry. Optional, off by default, for later: persist `{trackIds, index, positionMs, playMode}` to KeyValueStore on pause and every 15 s, restore paused at launch.

Continuous task:
- Start `AUDIO_PLAYBACK` as soon as `playWhenReady && phase in {loading, ready}`, not only when `isPlaying` as the code does today. A user who locks the phone during the initial buffering would otherwise lose the process.
- Stop on `ended`, `error`, `idle` and `stop`.
- Paused: keep the task up to an idle timeout (suggest 10 min), then stop the task but keep the session. Verify on device that the lock-screen play resumes after that. Android keeps its session as long as the service lives.

Permissions already in module.json5: `INTERNET`, `KEEP_BACKGROUND_RUNNING`, `backgroundModes: [audioPlayback]`.

**Android:** player/PlaybackService.kt (onTaskRemoved, onDestroy); player/PlaybackManager.kt (hostStarted gating ~L298-330, disconnect ~L365); docs/design.md ~L148 (play mode not persisted), ~L391-397; harmony: ohos/.../PlaybackPlugin.ets (onDetachedFromEngine), SystemSession.ets (startBackground/stopBackground), EntryAbility.ets, module.json5

### 15. 15. Tap on the system card opens Now Playing

Android: the MediaSession's `setSessionActivity(OpenNowPlayingActivity.pendingIntent)`. An invisible Activity calls `experienceSessionStore.requestNowPlaying()` (a request with a TTL), brings Yoin's existing task to front as it is, and finishes. A cold start opens Home with Now Playing over it. A page without a player of its own (Settings) comes back unchanged and the request lapses.

HarmonyOS: `SystemSession.getLaunchAgent()` currently builds a `WantAgent` that starts EntryAbility with no parameters. Add `parameters: { yoinAction: 'openNowPlaying' }`. EntryAbility is singleton, so a tap calls `onNewWant` while running and `onCreate` on a cold start. Forward the parameter through an event on the playback event channel (`{type:'openNowPlaying'}`), which the shell consumes by opening the NP overlay (one controller, like a mini-bar tap). A cold start can read it from the launch want and emit it once Dart is subscribed.

The request lapses after about 2-3 s if the shell cannot open NP (Android TTL).

Use the same agent for the continuous task. The `FlutterAbility.onNewWant` hook name needs confirming in the OHOS Flutter embedding.

**Android:** player/OpenNowPlayingActivity.kt; player/PlaybackService.kt (setSessionActivity); harmony: SystemSession.ets getLaunchAgent

### 16. 16. Profile switch / disconnect

`disconnectForProfileChange()` and `disconnect()`:
- `playbackGeneration++`, so async starts in flight abort.
- Cancel any `playWhenLoaded` read.
- `engine.stop()`, which tears down the AVSession and the continuous task.
- Clear pending state.
- State becomes `PlaybackState(phase: idle)`.
- `tracker.update(null)` so the scrobble session ends.
- Android also clears a pending-command queue; here that means dropping commands made before the engine attached.

A queue read for the outgoing account never starts or queues on the next one.

The controller re-checks `activeProfile.id` after every await in a start, so a start that was in flight when the account changed is dropped.

**Android:** player/PlaybackManager.kt (disconnect ~L365-401, disconnectForProfileChange ~L331, startQueue guard ~L560)

## Data calls
- `source.playback.handleFor(track)` per queued track -> `DirectStream(signed stream.view URL)`. It is pure local URL signing on Subsonic, so build the whole queue up front, as Android does for `tracks.map { buildMediaItem }`.
- `source.resolveCoverUrl(track.coverArt, size: 512)` for `EngineItem.artworkUri` (lock screen) and the size NP and the bar use for their own cover images. Do not use the size-less original for AVSession.
- `source.playback.reportNowPlaying(id)` = `scrobble?id=&submission=false`, once per play start. Failure is dropped.
- `source.playback.submitPlay(s)`: needs a NEW batched `submitPlays(List<PlayReport>)` on the Harmony `MusicPlayback` = `scrobble` with repeated `id` + `time` (epoch ms), `submission=true`. Error codes 10 and 70 -> rejected (drop the play); everything else is retried later.
- `source.writeActions.setFavorite(id, bool)` for the system heart (star / unstar). The controller shares the favorite overlay with NP.
- `source.capabilities` gates `playReporting` and `favorites` (scrobble tracker, session heart).
- For Home and Library to start playback: `library.getAlbum(id).tracks`, `library.getPlaylist(id).tracks`, `library.getStarred().tracks`, `library.getRandomSongs()`, `library.search(...)`. They feed `controller.play(...)`. They are other areas' calls.
- Local reads and writes (no network): `ActivityStore.record(play, ctx)` on each track start, `ActivityStore.recordVisit(album|artist)` on detail open, `watchRecent(limit = 40)` and `watchLibraryRecents()` for Home Activities, the hero and Library Recents.
- Paging and refresh: nothing is paged here. The controller re-publishes on engine events. Activities re-emit on each write, debounced 1 s on the Home side.
- Caching: stream URLs are stable per source instance (salt drawn once). There is no HTTP media cache in v1 (Android's SimpleCache is deferred). Offline downloads (`lib/data/offline/`) keep songs under the sandbox's `filesDir/offline_media/`; `_itemFor` hands the engine a downloaded song's `file://` address as `EngineItem.localUri` beside its stream URL; `AvEnginePlayer` opens the file as a file descriptor (`fdSrc`) and streams `uri` when the file has gone (removed after the queue was built), as Android's `OfflineFirstDataSource` decides when the song opens. Cover art and AVSession PixelMaps are cached by URL (last 3) on the ArkTS side.

## State model
Android: `PlaybackState` is a plain `StateFlow` in `PlaybackManager` with `currentTrack`, `pendingTrack` (Spotify only), `isPlaying`, `playWhenReady`, `position`, `duration`, `bufferedPosition`, `queue`, `currentIndex`, `queueEntryIds`, `userQueued`, `upcoming`, `queueEdit`, `nextTrack`, `repeatMode`, `shuffleEnabled`, `connectionPhase`, `connectionErrorMessage`, plus Spotify/cast fields. `_currentActivityContext` is a separate StateFlow. `NowPlayingViewModel` strips `position` and `bufferedPosition` into a separate 4 Hz `positionMs` flow, so a pure position tick does not rebuild the UI state.

Flutter (new):
- `final playbackControllerProvider = NotifierProvider<PlaybackController, PlaybackState>`.
- `final class PlaybackState` (immutable, `==` over all fields): `Track? currentTrack; bool isPlaying; bool playWhenReady; int durationMs; List<Track> queue; List<String> queueEntryIds; Set<int> userQueued; int currentIndex; List<int> upcoming; Track? nextTrack; RepeatMode repeat; bool shuffle; ConnectionPhase phase; String? errorMessage; PlayMode get playMode`.
- `final playheadProvider = NotifierProvider<..., Playhead>` with `{String? trackId, int positionMs, int sampledAtMs, bool isPlaying}`. The UI ticks with a `Ticker`; no timers.
- `activityContextProvider`: `ActivityContext` (sealed: none, likedSongs, album, artist, playlist) for NP's "played from" label and for history rows.
- `playbackEventsProvider`: one-shot Stream for session commands that need UI (reserved).
- `ScrobbleReporter` (plain Dart class, owned by the controller, NOT a widget): tracker + queue + flush. Created once; subscribes to the same engine stream as the controller.
- `ActivityStore` provider over `keyValueStoreProvider`.

Engine contract (Dart `PlaybackEngine` plus ArkTS), additions:
- `setQueue(items, {startIndex, startPositionMs, playWhenReady, repeat, shuffle})` returns the `revision`.
- `insert(at, items)`, `removeAt(i)`, `move(from, to)`, `clear()`; each returns the new `revision`.
- `setRepeat(off|one|all)`, `setShuffle(bool)`.
- `setFavorite(id, bool)` (session heart).
- `getSnapshot()`.
- `EngineState` adds `playWhenReady`, `repeat`, `shuffle`, `revision`, `repeatPlays`, `nextIndex`, `order` (sent only when the revision or shuffle changes; absent on ticks), `errorCode` (for logging only), and a separate event `{type:'command', name, id}` for session actions (toggleFavorite) and `{type:'openNowPlaying'}`.
- Native keeps `items`, `order`, `index`, repeat, shuffle, `revision`. Dart keeps rich `Track` entries parallel to it.

Data flow: user action -> controller method -> engine call -> engine events -> controller maps to `PlaybackState` (dedupes non-position fields) and `Playhead`, feeds `ScrobbleReporter.onState` and `recordPlayIfDue` (history), updates `lastRecordedTrackId`.

## Adaptive
Playback and system integration are layout-independent. There is no Compact / Medium / Wide difference in the engine, queue, scrobbling, history or AVSession.

Phone (Compact), the v1 target: one shell, an NP overlay, the idle bottom bar, and the playhead read by the NP progress control through a Ticker.

Medium and Wide change only how it is presented:
- The queue sheet and NP column are NP's layout concern (another spec).
- A detail page opens in the shell's right column on Wide. The controller call is identical.
- Window size changes must not restart or reload playback: the engine is native, so rotation and fold/unfold only rebuild Dart widgets. Test that a rotation during playback does not emit a new `setQueue` or drop the position.
- Foldables: the Mate X-style outer screen is a ~1:1 compact window. Same rules. Nothing in this area keys on window size.
- Landscape or a short window changes nothing here. Keep the 1 s native tick regardless of visibility (Android gates its 250 ms ticker on `hostStarted`; Harmony needs the tick for scrobbling even with the UI paused).

## Deferred
- Gapless / next-track preload: AVPlayer has no playlist, so it needs a second prepared AVPlayer and a handoff of focus and the session. Not required for the target; the plain reload gap is audible only on continuous albums. Do it in v1.1 (design: prepare next at ~10 s before the end, start on `completed`).
- Seeking in a transcode by time: ported (2026-11-17). A constant-bit-rate mp3 transcode of a file listed above the cap carries `DirectStream.timeSeek`; the stream cache proxy answers a range it can't serve from its whole-song fetch (a far seek, a refused or missing fetch, the rest after a dropped fetch) with `stream.view?…&timeOffset=<s>` from the second the byte falls in, as a 206 cut to the asked span, where the server lists OpenSubsonic `transcodeOffset` (Android SubsonicStreamResolver). An extensions ask that gets no answer makes seeks go by byte for 5 minutes. Not covered: the native engine's fallback to the server's own address (no proxy) still sends Range. Unverified on a device against Navidrome.
- OpenSubsonic `playbackReport` / `reportPlayback`: ported (2026-11-17), see section 9.
- Network-available callback to flush the scrobble queue: no network-callback plugin yet. v1 flushes on each submit, on app resume and on source change.
- Disk media cache (SimpleCache): AVPlayer has no cache hook. Offline downloads landed as their own downloader (`OfflineDownloads`, Dart `dart:io` + `http`); they run while the app runs, with no background download task or progress notification yet.
- ReplayGain / volume leveling: ported (2026-11-16). AVPlayer exposes no audio processor, so the gain Android multiplies in ReplayGainAudioProcessor is worked out in Dart (`lib/playback/replay_gain.dart`, same modes, +4 dB pre-amp and peak guard) and sent per queue entry as `EngineItem.gain`; the ArkTS engine sets it as that entry's AVPlayer volume before it plays (load, or the gapless swap: each AVPlayer holds one song, so it changes with the song), ducked on top, and `setGains` re-levels the queue at once when the setting changes. Difference: AVPlayer's volume stops at 1, so a gain above unity (a quiet song plus the pre-amp) plays at the file's own level instead of being raised.
- Spatial audio, the decoder's format readout, Streaming quality's Auto and DLNA volume keys: ported (2026-11-17). Now Playing's tags show what arrives: each `EngineItem` carries its stream's `delivery` and its download's `fileDelivery` (echoed by `getSnapshot`), and the ArkTS engine reads `AVPlayer.getTrackDescription()` once a source is prepared (or swapped in gapless) and sends `playingFormat` (codec MIME, channels, rate, depth, bit rate, file or stream); `lib/nowplaying/now_playing_format.dart` combines them as Android's `playingFormatTags` / `transcodedFrom`. Auto follows the routing manager's preferred output for music (`yoin/audio`), read as the queue is built (Android reads it as each song opens). Mobile data's default stays 320 kbps, not Android's Auto: **pending the owner**. Spatial audio reads `AudioSpatializationManager` (`isSpatializationEnabledForCurrentDevice`, the output's `spatializationSupported`; HarmonyOS keeps the system switch per output and has no public head-tracking read, so "with head tracking" never shows); Yoin's switch plays multichannel songs with `audioEffectMode` EFFECT_NONE while off (not verified on a device that the spatial renderer then leaves them alone). DLNA speakers take the volume keys through `inputConsumer.on('keyPressed')` (foreground only; Android's remote DeviceInfo also works from the lock screen).
- DLNA / Cast output (CastManager, DlnaManager, transferToDlna): out of v1 scope; system output switching is the OS's.
- Spotify App Remote, Apple Music MusicKit, ExternalController handles, SpotifyContextSync, SpotifyQueueMirror, pendingTrack/Launching: out of scope, Subsonic only.
- Continue On (startPositionMs/startPaused hand-off from another device) and its pause-from-elsewhere path: out of scope. `startPositionMs` and `startPaused` stay in the engine and controller signatures because they are free.
- Session LIBRARY_ADD action: Apple Music only. SessionQuickActions' membership refresh with it.
- Queue drag-reorder (`moveQueueItem`), user-queued section UI, Spotify queue edit levels (`QueueEdit.None/Remove`): the engine verbs exist in the spec, but the sheet UI is NP's job; the v1 level is `Full` for Subsonic.
- Native toast on a failed system-heart write: needs an ArkTS promptAction path; v1 reverts the heart silently.
- Restore queue and position after process death: Android does not do it either (play mode and queue are not persisted). Optional later, with a snapshot written on pause.
- PlayTrace diagnostic log (export log) and AudioOutputLog: replace with masked hilog now; an export feature is out of scope.
- play_history Room table with play counts, Rediscover, artist top songs, memory aggregates, track_album_link, other-profile feeds, HomeGridPoolCache: need aggregate queries (relationalStore) and Memories features that are out of scope. v1's JSON activity log covers Activities, hero and Library Recents.
- Android 'Quick actions' fast-forward / rewind: neither the Android notification nor 播控中心 needs them for music; leave the AVSession listeners unregistered so the buttons stay hidden.

## Gaps in the HarmonyOS framework
- ArkTS engine is missing repeat and shuffle (play-order permutation) and manual/auto navigation with wrap: `next()`/`previous()` do not wrap, `completed` has no repeat-ONE or repeat-ALL, and shuffle is absent. Add `repeat`, `shuffle`, `order`, `revision`, `repeatPlays` to the engine and to the snapshot.
- No queue mutation verbs (`insert`, `removeAt`, `move`, `clear`) and no `getSnapshot()` on `yoin/playback`. Needed for Play next / Add to queue, the queue sheet, and Dart rehydration.
- `EngineState` lacks `playWhenReady`, `repeat`, `shuffle`, `revision`, `repeatPlays`, `nextIndex`, `order`. The `play()` after `ended` behaviour replays the last item instead of restarting the queue.
- `SystemSession` registers only play/pause/stop/next/previous/seek. Missing: `toggleFavorite`, `setLoopMode`, `isFavorite` / `loopMode` / `activeItemId` in `AVPlaybackState`. It also calls `setAVPlaybackState` on every 1 s tick: coalesce to real changes.
- AVSession artwork is a URL string only. Needs a native cover fetch -> `image.createPixelMap` (512 px) with a small cache and a metadata re-set when it lands; Dart must pass the `size: 512` cover URL.
- No headphone-unplug / output-device-removed handling in the engine (Android `setHandleAudioBecomingNoisy`). Add the AVPlayer output-device-change event or the RoutingManager `deviceChange` listener and pause without auto-resume.
- Lifetime bug: `YoinPlaybackPlugin.onDetachedFromEngine` releases the engine, session and background task, and both are created with the UIAbilityContext in `onAttachedToEngine`. Playback dies with the FlutterAbility/engine. Needs a process-level host singleton and a decision on what happens at `EntryAbility.onDestroy` (see v1 item 14).
- Continuous task starts only on `isPlaying`; it should start on `playWhenReady && (loading|ready)` and have an idle timeout while paused. `getLaunchAgent` sends no parameters, so a session tap cannot open Now Playing.
- No event path from ArkTS to Dart for session commands or launch intents. The `yoin/playback/events` payload is a flat state map today; it needs a typed `{type: 'state'|'command'|'openNowPlaying'}` envelope.
- Channel payload sizes: `setQueue` with a 2 000+ song Songs list is one large message parsed on the ArkTS main thread. No chunking exists.
- Persistence: the framework has only `KeyValueStore` (Preferences) and `SecretStore`. There is no database. Fine for the v1 capped activity log and scrobble queue, but Memories, Rediscover and play counts will need an ArkData relationalStore plugin (`yoin/db`).
- No network-availability callback (needed to flush offline scrobbles as Android's `onAvailable` does) and no network-type / audio-route API (needed later for quality Auto).
- Cleartext HTTP: `ohos/AppScope` and `entry/src/main/resources/base/profile` contain no `network_config` (cleartext allowance). A LAN Navidrome behind plain http:// may be blocked for AVPlayer. Check the default on API 26 and add a `network_config.json` with `cleartextTrafficPermitted` if needed.
- Missing Dart pieces (all new): `PlaybackController`, `PlaybackState`/`PlayMode`/`ConnectionPhase`/`ActivityContext`, `ScrobbleTracker`/`ScrobbleReporter`/`PendingScrobbles` (pure Dart ports), `ActivityStore`, `MusicPlayback.submitPlays(List<PlayReport>)`, strings in `lib/l10n/strings.dart` for the keys above (EN + ZH).
- The Dart `PlaybackEngine` interface has `next`/`previous` but no `setRepeat`, `setShuffle`, `insert`, `removeAt`, `move`, `setFavorite`, `getSnapshot`. Update `playback_engine.dart`, `channel_playback_engine.dart` and the test fakes.

## Risks
- Dart in the background: scrobble timers, the favorite write from the lock-screen heart and `recordPlay` run in the Dart isolate. Whether the isolate keeps running with the screen off under the AUDIO_PLAYBACK continuous task must be verified on the Mate 70 Air. Fallback: native `scrobbleDue` event at the threshold, or do the favorite write natively.
- UIAbilityContext validity: AVSession and `startBackgroundRunning` are bound to the ability that created them. If the ability is destroyed and the process survives, the session may be invalid. A swipe-away on HarmonyOS likely kills the process, so persistent playback after task removal (Android's behaviour while playing) is not achievable. Needs device testing.
- `AVSession.setAVMetadata({mediaImage: string})` may not render for LAN or token URLs on 播控中心 / lock screen; the PixelMap path is the safer default. PixelMap lifetime and memory (release on track change) must be handled.
- System-surface loop mode is one axis, so shuffle+repeat-one and shuffle+repeat-off collapse. A system tap can change the mode into a state the in-app Gray-code cycle would not produce; the app must read mode back from the engine and not assume the order.
- Reload-per-track means an audible gap, and a slow server stalls the gap further. For a user with lossless albums and Atmos tracks this is the biggest perceived quality gap versus Android; plan the second-AVPlayer preload for v1.1.
- Large queues: Subsonic Songs and some playlists run to thousands of tracks. If `setQueue` takes noticeably long on the ArkTS main thread, fall back to the same 100/20 window the other providers use (`startWindow`) or send in chunks.
- AVPlayer `error` and `stateChange('error')` recovery: `reset()` is valid from the error state, but a failed `prepare` on an HTTP 4xx (wrong token, 401 after a password change) looks like a generic error. The localised message hides the cause; auth failures should be distinguished (Dart can call `ping()` when a stream errors) in a later pass.
- AVSession and `audioInterrupt` signatures (toggleFavorite, setLoopMode, `audioOutputDeviceChangeWithInfo`, `onNewWant`) are written from the HarmonyOS API surface and must be checked against the API 26 SDK in DevEco before coding.
- The history/activity JSON in Preferences is rewritten whole on every track start. Keep it small (feed 300 rows, lastSeen 2 000 ids), debounce flushes, and do not run it on the playback path.
- Never audible audio at night and never touch other apps' data (AGENTS.md testing rules): QA of the playback chain on the real phone must respect that. Use quiet or short test tracks and a volume cap.
- Two writers for 'what is playing': the engine events and the controller's optimistic state. The `revision` rule must hold or a fast skip shows the old track briefly.

## Corrections (from a second reader — these win)
AUDIT OF THE PLAYBACK / SYSTEM-INTEGRATION SPEC. Corrections and additions only. I checked the Android code under player/, ui/nowplaying, data/repository, data/local and ui/library. I also checked the Harmony tree (lib/playback, ArkTS yoin/playback) and the DevEco SDK .d.ts files.

A. WRONG IN THE SPEC (Android behaviour)

1. nextTrack under repeat ONE.
- The spec says `nextTrack` is null under repeat one. The code says otherwise (player/PlaybackManager.kt syncState ~L1111). It uses `player.nextMediaItemIndex.takeIf { != INDEX_UNSET && != currentIndex }`.
- Media3 maps ONE to ALL for navigation, so under repeat one `nextTrack` is the real next item in play order, and it wraps.
- `nextTrack` is null only at the end of the queue under repeat OFF, or when the queue has one item.
- The doc comment in PlaybackState.kt says "under repeat-one" and is stale. The code wins.
- `upcoming` is built with REPEAT_MODE_OFF (no wrap), as the spec says.

2. "Open Now Playing" request lifetime.
- The spec says the request lapses after about 2-3 s. The real value is `NOW_PLAYING_REQUEST_TTL_NS = 30_000_000_000L`, i.e. 30 s (ui/experience/ExperienceSessionStore.kt L322).
- The request is single-use. It is consumed with compareAndSet and checked against the token's age.
- `OpenNowPlayingActivity` brings the existing task to the front as it is. It never stacks a second Home.

3. Retry from the NP error card.
- The spec says NP's ConnectError state lets the user tap play to retry. Android's `ConnectErrorContent` (NowPlayingScreen.kt L727) has no transport and no retry. It has:
  - a collapse chevron, rotated 180° times dismissFraction;
  - a 200dp cover (NowPlayingCover shape, surfaceContainerHighest placeholder);
  - the title (titleMedium) and artist (bodyMedium);
  - the message (bodyLarge, `colorScheme.error`, centered).
- Padding is 24dp horizontal and 40dp vertical. Gaps are 24 / 28 / 2 / 24dp.
- The chevron's content description is `np_cd_connect_error_collapse`.
- The raw `playerError.message` is shown when present. The Spotify-only button is irrelevant.
- Android has no retry at all. ExoPlayer's `play()` in the error state does not re-prepare, and `play()` after ended is not verified.
- The engine's reload-on-play at error is therefore a Harmony addition. If kept, it must live on the mini-bar toggle. Do not draw it on the card.
- In startQueue's catch (PlaybackManager L664-L667) Android sets the message to `error.message ?: player_play_failed`. It is not always `player_play_failed`. Showing the localized text always is a deliberate divergence.

4. Library Recents sources.
- The spec lists two sources: activity_events and per-album play_history. A third is missing.
- `LibraryOpenStore` (ui/library/LibraryOpenStore.kt, SharedPreferences file `yoin_library_opens`) records opens from a Library cell:
  - Kinds are Artist, Album and Playlist.
  - The key is `profile/provider/Kind/rawId` and the value is epoch ms.
  - It is capped at the newest 300 per profile × service.
  - It is merged with `LibraryRecents.latestWith`.
- A playlist page records no visit, because that would put it in Home Activities. Playlists therefore reach Recents only through the playlist Play context or this store.
- The design doc (docs/design.md ~L405) says Recents is read once per Library re-entry, with a 150 ms debounce. It is not a live subscription.
- Add this store to the Dart ActivityStore as its own key, for example `activity.libraryOpens.<profileId>`, newest 300 per profile × provider. Library's open handler writes it.
- Also record opens from search results only when the search scope is Library.

5. Home hero and Activities are not what the spec describes.
- `selectHomeActivities` (HomeEditorialContent.kt L3153) drops all SONG entries. It also dedupes by `entityType:entityId`, ignoring actionType. A PLAYED and a VISITED row of the same album collapse into one.
- The repository-level collapse key is `actionType:entityType:entityId:provider`, so Home applies a second dedupe on top.
- The hero is the first ALBUM or PLAYLIST after that filter. Plays from the Songs list, search or a Home song card (context None) write SONG rows and never appear in Home Activities. Only the per-album `play_history` bump affects Library Recents.
- Normalise `entityId` and `songId` with `MediaId.storedRawId`. Legacy rows can carry a `provider:` prefix.
- The Activities feed is read as `limit * 8 = 320` newest rows. The spec's cap of 300 rows makes the feed slightly shorter than Android's. Use a cap of at least 320 (400 is fine).

6. Row details for `buildPlaybackActivity` (YoinRepository.kt L3716).
- For an Album context, `albumId` is the context's album id. For Artist and Playlist contexts, `albumId` is the track's album id.
- The Artist subtitle is the literal "Artist".
- The Playlist subtitle is `owner.ifBlank { "Playlist" }`.
- VISITED rows carry no `songId`. The album visit stores `subtitle = album.artist` and `artistId`. The artist visit stores `subtitle = "Artist"`.
- Visits are wrapped in runCatching and must never fail a page load.

7. AVSession `activeItemId` is a number, not a string.
- The spec says `activeItemId = item.id` (the MediaId string).
- In the SDK (@ohos.multimedia.avsession.d.ts L3475), `AVPlaybackState.activeItemId?: number`.
- `on('skipToQueueItem')` and `on('playFromAssetId')` also take a number.
- `AVMetadata.assetId` stays a string, and `toggleFavorite` returns that string assetId.
- Use a numeric queue position or the queue revision as `activeItemId`. Better still, do not set it unless `setAVQueueItems` is used.
- The enum names are `avSession.LoopMode.LOOP_MODE_SEQUENCE=0 / SINGLE=1 / LIST=2 / SHUFFLE=3`. `LOOP_MODE_CUSTOM=4` also exists.

8. The scrobble tracker's `isPlaying` input.
- It is `state.isPlaying` (actually playing audio). It is not `playWhenReady` (scrobbleSnapshotOf, L1411).
- A buffering or loading dip, an ended or error state, and a transient focus loss all earn no listened time.
- `recordPlayIfDue` uses the same `isPlaying` trigger.
- Also: manual next on a one-song queue under repeat ALL is a seek to the default position of the same index. No transition fires, so no new history row and no new `repeatPlays`. Only the auto-repeat transition (`MEDIA_ITEM_TRANSITION_REASON_REPEAT`) bumps `repeatPlays`.
- `repeatPlays` is never reset (L100, L1034). It does not need to be, because `entryId` is new per queue.

9. clearQueue.
- It does not set Idle. It calls `clearMediaItems()`, so `syncState` shows `currentTrack = null` with phase Ready.
- NP derives Idle from the null track. The scrobble snapshot becomes null, so the session ends.
- Only `disconnect` resets to `PlaybackState(connectionPhase=Idle)` and calls `endScrobbleSession`.

10. Strings, to complete the list:
- `np_from_album` = "PLAYING FROM ALBUM" / "播放自专辑"
- `np_from_playlist` = "PLAYING FROM PLAYLIST" / "播放自播放列表"
- `np_from_artist` = "PLAYING FROM ARTIST" / "播放自艺术家"
- `np_from_now_playing` = "NOW PLAYING" / "正在播放". This is used for the Songs list and the Liked-songs context.
- `np_cd_transport_play` ("Play") and `np_cd_transport_pause` ("Pause") are `translatable=false` in Android. The spec's "(not translated)" is correct. The owner's i18n rule is that PLAY/PAUSE are English only.

11. LikedSongs vs None.
- The Subsonic Favorites tab uses `ActivityContext.LikedSongs(coverArtId = first track's cover)`. It is a SONG row.
- The Songs list (`playSongsList`) uses None on Subsonic. It uses LikedSongs only where `favoritesAreLibrary` (Spotify).
- The spec has these roughly right but states the Songs list loosely.
- Library Songs on Subsonic are read page by page (LibraryScreen.kt ~L1887). The queue is only the rows already loaded, around the tapped row. Pages loaded later do not join the queue.
- Android accepts that limit, and Repeat All then wraps inside the loaded rows. Harmony's Library spec must state the same.

B. MISSING FROM THE SPEC (Android behaviour v1 needs)

12. Transport-related hints NP reads from the controller.
- `skipDirection` is a display hint: +1 on next, −1 on a previous tap.
  - It is armed by tap. It waits up to `TransportTapValidityMs = 3000` for the current song id to change.
  - It holds −1 for `SKIP_DIRECTION_HOLD_MS = 1000` after the change, then returns to +1.
  - It resets at once on a restart or when there is no previous song.
  - It drives the cover's directional ride-in.
- `TransportPulseKey(songId, isPlaying, queueIndex)` is the committed state the transport pulse reacts to. A pulse also fires for headset, lock-screen and notification taps, which are state changes without an NP tap.
- `restartedInPlace` means a previous tap that left the same song with the playhead at least `PreviousRestartJumpMs = 1000` ms lower.
- The controller therefore needs `currentIndex` and a stable `songId` in published state, so these can be derived.
- NP's `Playing.isPlaying` is `state.playWhenReady`.

13. buffered position.
- It is only used by `WaveProgressBar`. If `buffered > progress + 0.02`, the track alpha is 0.2. Otherwise it is 0.14.
- Skipping it is fine for v1: always 0.14, or approximate from `bufferingUpdate`.

14. Position-ticker gating.
- Android's 250 ms ticker runs only while `hostStarted && isPlaying`.
- `recordPlayIfDue` and the scrobble feed run on every controller callback, including with Yoin in the background.
- So history and scrobbling work with the UI gone. Harmony's controller must be built and subscribed at app start. Riverpod 3.3 notifiers are lazy, so a root widget or main() must read or watch it.
- The shared source is disposed via `ref.onDispose(source.dispose)` in `activeSourceProvider`. The scrobble target must resolve the active source at call time, never hold a stale one.

15. Session heart on failure.
- Android DOES toast on failure (SessionQuickActions.toggleFavorite uses `Toast`). The spec's "revert silently" is a Harmony simplification.
- The write is blocked while another write for the same id is in flight (`favoriteWrites`). The displayed state is `writes[id] ?: overrides[id] ?: track.isStarred`.
- The heart is hidden when `rawId` is blank.

16. Stream cost on mobile data.
- Android defaults to Auto on mobile (wired = original, Bluetooth = 320, speaker = 192 as mp3). v1 sends the original file on every network.
- An original lossless file over cellular can be tens of MB per track. List this as a known v1 data-use risk, or add a 2-value setting (original / 320 mp3) with no seek hooks. Navidrome handles a CBR mp3 `maxBitRate` stream.

17. Queue item metadata.
- Android keeps the full Track in the player item (extras: ids, starred, rating, content type and so on) and rebuilds `currentTrack` from it on controller reconnect.
- The Harmony native side only holds `EngineItem`. For faithful cold-start rehydration, add an opaque `extra` JSON string (the full Track) to `EngineItem`.
- Echo it in `getSnapshot()`.
- Add a per-entry `entryId` to `EngineItem` and echo `currentEntryId`. Today's `currentId` is the MediaId, which is ambiguous when the same song is queued twice.

C. CORRECTIONS ABOUT THE HARMONY SIDE

18. Existing code the spec calls "new".
- `lib/playback/playback_controller.dart` already exists (a Notifier over `NowPlayingState`) with `playTracks`, `play`, `pause`, `next`, `previous`, `skipTo`, `seekTo` and `stop`. The spec's controller should extend it, not start fresh.
- Its `_onEngineState` prefers the engine's `currentId` over `index` (indexWhere by id). That breaks queues with the same song twice. Use `index` plus the `revision` rule instead.
- `playTracks` computes `playable.indexOf(tracks[start])`. That also finds the wrong entry with duplicates.
- Its position lives in `NowPlayingState`. The spec's separate playhead provider is right.
- It already uses `size: 600` for artwork. The spec says 512. Pick one value.

19. Verified SDK signatures (API in DevEco, ets/api):
- `on('toggleFavorite', (assetId: string))`, `on('setLoopMode', (mode: LoopMode))` and `on('skipToQueueItem', (itemId: number))` exist.
- `AVPlaybackState` has `loopMode`, `isFavorite` and `activeItemId` (number).
- `setSupportedLoopModes(Array<LoopMode>)` exists on the session. The spec never calls it.
  - Declare the modes you map: SEQUENCE, SINGLE, LIST, SHUFFLE.
  - Otherwise the control-center loop button may not appear.
- `AVMetadata.mediaImage` is `PixelMap | string`.
- `AVPlayer.on('audioOutputDeviceChangeWithInfo', Callback<audio.AudioStreamDeviceChangeInfo>)` exists.
- `audio.AudioStreamDeviceChangeReason.REASON_OLD_DEVICE_UNAVAILABLE = 2` exists. The unplug handler is confirmed available. Pause without resume-on-gain, and do not double-pause if the system already paused.
- `AVPlayer.loop: boolean` and `on('endOfStream')` exist. For repeat-one on a single item, `loop = true` would avoid the reload gap. It needs its own `repeatPlays` accounting from the loop event.
- `AVPlayer.on('bufferingUpdate')` can drive a mid-play `connecting` phase.

20. `FlutterAbility.onNewWant(want, launchParams)` exists and is overridable.
- Source: /Users/gpo/Developer/flutter_ohos/.../embedding/ohos/FlutterAbility.ets L194. It forwards to the delegate, which calls the plugins' ability control surface.
- Override it in EntryAbility. Call `super`, then read `want.parameters.yoinAction` and emit `{type:'openNowPlaying'}`.
- For a cold start, read `this.launchWant.parameters`.
- `maybeGetInitialRouteFromIntent` is a stub returning '', so do not rely on a route parameter.

21. Ability lifetime on back.
- `FlutterAbility.onDestroy` → `delegate.onDetach()` → plugin `onDetachedFromEngine`. That kills engine, session and task in the current code.
- The most common exit is back at the root of Home or Library. On Android that moves the task to the back and the service plays on.
- It must be tested early on device that root-back goes to `onBackground`, not `terminateSelf`. If Flutter's back handling terminates the ability, playback dies.
- The host-singleton fix (spec item 14) is needed, but verify first whether it is reached in normal use.
- AVSession and `startBackgroundRunning` take a `UIAbilityContext`. If the ability is destroyed while the process lives, a session created from that context may be invalid. Keep the "pause and persist on destroy" fallback.

22. Continuous-task / AVSession coupling.
- HarmonyOS audio-playback long tasks are meant to be backed by an active AVSession in a playing state.
- The spec's "keep the task up to 10 minutes while paused" may be reclaimed by the system earlier. Keep the AVSession active and verify this on device. Do not assume 10 minutes.
- Order matters: create and activate the AVSession before `startBackgroundRunning`. The code already does this. Keep it.

23. Cleartext and AVPlayer.
- `network_config` for cleartext HTTP is missing, as the spec says. I confirmed there is no `network_config` anywhere under ohos/.
- The module's `skills` block only has the home entity and action. A `WantAgent` that launches EntryAbility by name still works for a running singleton.

D. SMALLER ITEMS

- Android's `play()` / `startQueue` has no optimistic state. The player's state stays as it was until the controller echoes. The spec's optimistic publish is a good divergence, but it must not set `isPlaying` or fire scrobble or history. Those stay driven by engine events.
- On Android, enabling shuffle mid-queue keeps Media3's existing random permutation. Current is not moved first, and "previous" can jump anywhere. The spec's "[current] + permutation of the rest" is a deliberate improvement. Mark it as a divergence, because the Android `upcoming` list can be short after a toggle.
- Because the shuffle write is atomic with `setQueue`, a Play on a fresh queue under shuffle mode shuffles from the first item. Android applies the mode after `setMediaItems` and `prepare`, so the same end state results.
- Gapless: Android's ExoPlayer preloads the next item. The AVPlayer reset-per-item gap is the largest quality regression for continuous albums. Deferring is acceptable for a first build, but record it as a known regression rather than a neutral deferral.
- Scrobble: `Started` and `StateChanged` events are dropped if the event's profile id is not the active target's. The spec's gating covers this. For the Dart isolate being frozen, listened time is accrued from the monotonic clock between updates, so late events are safe. A pause that is missed entirely would over-count, so the native side should send a state event on pause even with the UI detached.
- Library Recents has no "activity" row for a song played from Library Songs or search. Its album is bumped through `play_history`'s per-album max, so the ActivityStore `lastSeen.album` update on every played track is required, as the spec says.
