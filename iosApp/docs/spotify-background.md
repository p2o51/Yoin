# Spotify while Yoin is in the background

When iOS suspends Yoin, it can't move a Spotify queue from one song to the next. What Spotify plays
until Yoin comes back depends on where it plays and what the queue came from (parity C-F1, 2026-11-06).

| Where | Queue from | While Yoin is away | Back in front |
|---|---|---|---|
| Another device (Yoin is its remote) | anything | The Web API start handed it the current song and the queue after it, up to 100, in Yoin's play order (repeat all wraps, repeat one repeats). It plays on through them. | A read of `me/player` finds the song it reached. Yoin follows it there when the list it was handed is still Yoin's queue. |
| This iPhone | an album or playlist page | App Remote started the album or playlist as Spotify's own context (`play(contentItem, skipToTrackIndex:)`) with Yoin's shuffle and repeat. Spotify plays on through it. | App Remote's state names the song. Yoin follows it there while the queue still is that context. |
| This iPhone | Home, Library, Search (a loose list) | Only the current song and the one next song Yoin enqueued. **Known limitation:** after that Spotify stops, or plays its own autoplay. | As before. |

## Why a loose list can't carry on on this iPhone

- **App Remote can't do it.** It disconnects when iOS suspends Yoin (`SpotifyAudioOutput.background()`). Queueing more songs ahead (`enqueueTrackUri` in a loop) would fill the user's own Spotify queue. Nothing can take those songs back out, so they would play after every later start. That breaks the rule Android keeps: Yoin never touches Spotify's user queue.
- **The Web API can't do it either.** A Web API start on this iPhone's `device_id` knocks its Spotify off Connect. In 51's exported log of 2026-11-06, 11:30:47 to 11:31:14:
  - `PUT play?device_id=<this iPhone>` was sent while the iPhone was playing, and returned 204.
  - From 11:30:59, `me/player` returned 204 (nothing active).
  - Every pause after that got 404 "Device not found".
  - This is why commit 881850b17 made this iPhone App Remote only.

## Leaving the context

- **Edits:** a song queued by the listener, a reorder or a removal ends the context, but only at the next song, never mid-song. Spotify's context moves on. If that isn't Yoin's next song, the end starts Yoin's next as a lone song, and from there it is song by song as before.
- **Remote re-hand timing:** another device's list is re-handed when the queue changes. That happens at once only in a song's first 3 s. Later in the song it waits for the switch, and a read right at the expected end catches the stale next.
- **Wake starts:** a start that wakes the Spotify app (`authorizeAndPlayURI`) plays the song alone.

## Not verified

None of this has run against real Spotify (no real Spotify calls in this work).

- **What the tests check:** the start and catch-up logic, against a fake Web API (`SpotifyMirroringTests`, `SpotifyHandOffTests`).
- **What is inferred from the SDK headers:** that App Remote's context start keeps going in the background, and that its state names the reached song on reconnect.
