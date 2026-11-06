# Codex review 3 (Spotify night work, 1d709cd7a..5f9a4020d)

Model: gpt-6.1-sol, reasoning high, read-only. All five findings fixed in the next commit; R01 confirmed against developer.spotify.com (Remove Playlist Items: body field `items`; `tracks` belongs to the deprecated /tracks endpoint; per-item positions no longer documented). Android sends `tracks` too (SpotifyDtos.kt SpotifyRemoveTracksRequest) — not fixed here.

| R01 | P1 | [SpotifySource+Playlists.swift:124](/Users/gpo/Developer/Yoin-wt-ios/iosApp/Yoin/Data/Spotify/SpotifySource+Playlists.swift:124) | Playlist removal sends the obsolete JSON field |
| R02 | P2 | [PlaylistActions.swift:308](/Users/gpo/Developer/Yoin-wt-ios/iosApp/Yoin/Data/PlaylistActions.swift:308) | Spotify results can overwrite another account’s playlist cache |
| R03 | P2 | [PlaylistActions.swift:250](/Users/gpo/Developer/Yoin-wt-ios/iosApp/Yoin/Data/PlaylistActions.swift:250) | Failed batch additions roll back songs already committed remotely |
| R04 | P2 | [SpotifyBackups.swift:35](/Users/gpo/Developer/Yoin-wt-ios/iosApp/Yoin/Data/Backup/SpotifyBackups.swift:35) | Restore reports “not created” after creating a playlist |
| R05 | P2 | [SpotifyAudioOutput.swift:309](/Users/gpo/Developer/Yoin-wt-ios/iosApp/Yoin/Playback/SpotifyAudioOutput.swift:309) | A delayed queue update can resume playback after Pause |

**R01 — Removal uses `tracks` instead of `items`.**  
Every removal calls `DELETE /playlists/{id}/items`, but `removalBody` constructs `{"tracks":[…]}`. The current endpoint requires `items`, so removal requests lack the required body field and fail. The new test also asserts the obsolete field, masking this. Spotify explicitly documents this parameter rename. [Spotify removal reference](https://developer.spotify.com/documentation/web-api/reference/remove-items-playlist)

**Fix:** Change the body key to `items` and update the request test to validate the current contract.

**R02 — Account switches do not invalidate asynchronous playlist mutations.**  
Start creating a playlist under account A, switch to B before Spotify responds, then let creation finish. The captured writer correctly creates on A, but the completion inserts A’s playlist into the shared `LibraryStore`, now displaying B. `patchPlaylists` also persists it under B’s current cache scope. The new `loadPicks` branch has the same problem at lines 167–170: A’s response replaces B’s entire playlist list.

**Fix:** Capture the store generation and Spotify source before awaiting. Apply results, rollback changes, and publish picker rows only if both still match.

**R03 — Multi-batch additions are treated as atomic.**  
Add 150 songs to an existing playlist. `SpotifySource.addTracks` successfully posts the first 100, then the remaining 50 fail. The catch block removes all 150 optimistic entries and subtracts 150 from the displayed count, although Spotify retained 100. Retrying the operation appends those 100 again, creating duplicates.

**Fix:** Preserve the committed count/snapshot in a partial-write result, or reload authoritative playlist contents after failure. Do not roll back the successfully committed prefix.

**R04 — Restore loses the created playlist’s identity on initial-add failure.**  
`SpotifyRestoreDestination.createPlaylist` first creates an empty playlist, then adds the first batch. If that addition fails, it throws without returning the created ID. `PlaylistRestore.write` interprets this as `created: false`, and restore history incorrectly records that nothing was created. Repeating Restore creates another empty playlist.

**Fix:** Return a structured partial-write result containing the created playlist ID and actual added count, and record it accurately. Alternatively, successfully remove the empty playlist before reporting that creation failed.

**R05 — Pause does not cancel a scheduled playback restart.**  
Change shuffle/repeat so the next song changes, then pause within the 600 ms rehand delay. `pause()` leaves `rehandTask` running, and `rehand()` checks neither `playing` nor `wantsPlay`. It therefore issues `PUT /me/player/play` after the pause. `send()` pauses again only after the request returns, allowing an audible restart in between.

**Fix:** Cancel the scheduled rehand on Pause and recheck playback intent before sending. Keep the updated next song pending for the existing `play()` path.


| R01 | P1 | [SpotifySource+Playlists.swift:124](/Users/gpo/Developer/Yoin-wt-ios/iosApp/Yoin/Data/Spotify/SpotifySource+Playlists.swift:124) | Playlist removal sends the obsolete JSON field |
| R02 | P2 | [PlaylistActions.swift:308](/Users/gpo/Developer/Yoin-wt-ios/iosApp/Yoin/Data/PlaylistActions.swift:308) | Spotify results can overwrite another account’s playlist cache |
| R03 | P2 | [PlaylistActions.swift:250](/Users/gpo/Developer/Yoin-wt-ios/iosApp/Yoin/Data/PlaylistActions.swift:250) | Failed batch additions roll back songs already committed remotely |
| R04 | P2 | [SpotifyBackups.swift:35](/Users/gpo/Developer/Yoin-wt-ios/iosApp/Yoin/Data/Backup/SpotifyBackups.swift:35) | Restore reports “not created” after creating a playlist |
| R05 | P2 | [SpotifyAudioOutput.swift:309](/Users/gpo/Developer/Yoin-wt-ios/iosApp/Yoin/Playback/SpotifyAudioOutput.swift:309) | A delayed queue update can resume playback after Pause |

**R01 — Removal uses `tracks` instead of `items`.**  
Every removal calls `DELETE /playlists/{id}/items`, but `removalBody` constructs `{"tracks":[…]}`. The current endpoint requires `items`, so removal requests lack the required body field and fail. The new test also asserts the obsolete field, masking this. Spotify explicitly documents this parameter rename. [Spotify removal reference](https://developer.spotify.com/documentation/web-api/reference/remove-items-playlist)

**Fix:** Change the body key to `items` and update the request test to validate the current contract.

**R02 — Account switches do not invalidate asynchronous playlist mutations.**  
Start creating a playlist under account A, switch to B before Spotify responds, then let creation finish. The captured writer correctly creates on A, but the completion inserts A’s playlist into the shared `LibraryStore`, now displaying B. `patchPlaylists` also persists it under B’s current cache scope. The new `loadPicks` branch has the same problem at lines 167–170: A’s response replaces B’s entire playlist list.

**Fix:** Capture the store generation and Spotify source before awaiting. Apply results, rollback changes, and publish picker rows only if both still match.

**R03 — Multi-batch additions are treated as atomic.**  
Add 150 songs to an existing playlist. `SpotifySource.addTracks` successfully posts the first 100, then the remaining 50 fail. The catch block removes all 150 optimistic entries and subtracts 150 from the displayed count, although Spotify retained 100. Retrying the operation appends those 100 again, creating duplicates.

**Fix:** Preserve the committed count/snapshot in a partial-write result, or reload authoritative playlist contents after failure. Do not roll back the successfully committed prefix.

**R04 — Restore loses the created playlist’s identity on initial-add failure.**  
`SpotifyRestoreDestination.createPlaylist` first creates an empty playlist, then adds the first batch. If that addition fails, it throws without returning the created ID. `PlaylistRestore.write` interprets this as `created: false`, and restore history incorrectly records that nothing was created. Repeating Restore creates another empty playlist.

**Fix:** Return a structured partial-write result containing the created playlist ID and actual added count, and record it accurately. Alternatively, successfully remove the empty playlist before reporting that creation failed.

**R05 — Pause does not cancel a scheduled playback restart.**  
Change shuffle/repeat so the next song changes, then pause within the 600 ms rehand delay. `pause()` leaves `rehandTask` running, and `rehand()` checks neither `playing` nor `wantsPlay`. It therefore issues `PUT /me/player/play` after the pause. `send()` pauses again only after the request returns, allowing an audible restart in between.

**Fix:** Cancel the scheduled rehand on Pause and recheck playback intent before sending. Keep the updated next song pending for the existing `play()` path.


tokens used
222,609
Found **5 correctness bugs** in `1d709cd7a..5f9a4020d`. Static review only; tests and live Spotify operations were not run.

| ID | Severity | File:line | Finding |
|---|---|---|---|
