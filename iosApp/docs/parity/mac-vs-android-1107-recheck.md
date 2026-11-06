# Yoin for Mac vs Android — high-priority parity recheck

- **Date:** 2026-11-07
- **Mac HEAD:** `e05e629ffc74a060e90197c80a71c33ce77744f9`
- **Android reference:** `/Users/gpo/Developer/Yoin-wt-main-ro` at `55a80fcc2a63ece94529f2dcb8d72ec573c2c7a4`
- **Scope:** Every high-severity row in [the 2026-11-06 review](mac-vs-android-1106.md), covering `YoinMac/**` and shared files compiled by [the YoinMac target](../../project.yml#L155).

Read-only static review; neither app was run and no files were edited. Evidence and line numbers refer to the Mac commit above; concurrent uncommitted changes are excluded. “Fixed” means the original row’s gap is closed in code.

## Counts summary

**Fixed: 44 · Partly: 7 · Still open: 8 · Total: 59**

- **Part A:** 15 fixed / 3 partly / 2 still open — 20 total.
- **Part B:** 11 fixed / 1 partly / 4 still open — 16 total.
- **Part C:** 16 fixed / 2 partly / 2 still open — 20 total.
- **Part D:** 2 fixed / 1 partly / 0 still open — 3 total.

Overlapping findings in different Parts remain separate rows, matching the original report.

## Part A — Home, Library, detail pages, scrapbook, search

| Row | Status | Mac evidence | What remains |
|---|---|---|---|
| Activities content | fixed | [MacHomeContent.swift:114](../../YoinMac/Data/MacHomeContent.swift#L114) merges local collection activity with the remote feed; [MacHomeShelves.swift:37](../../YoinMac/UI/MacHomeShelves.swift#L37) renders albums, artists and playlists. | — |
| Spotify Activities | fixed | [MacLibrary.swift:195](../../YoinMac/Data/MacLibrary.swift#L195) fetches Spotify’s recently played feed and publishes album/artist activity cards. | — |
| Jump Back In | fixed | [MacHomeContent.swift:138](../../YoinMac/Data/MacHomeContent.swift#L138) combines albums, songs, playlists and Memory/note signals; [MacHomeShelves.swift:116](../../YoinMac/UI/MacHomeShelves.swift#L116) wires their actions. | — |
| Recently Added songs | fixed | [MacLibrary.swift:256](../../YoinMac/Data/MacLibrary.swift#L256) loads recent songs for all three providers; [MacHomeView.swift:46](../../YoinMac/UI/MacHomeView.swift#L46) renders them alongside albums. | — |
| Other accounts | fixed | [MacHomeContent.swift:171](../../YoinMac/Data/MacHomeContent.swift#L171) ranks other accounts’ album/playlist plays; [MacHomeContent.swift:215](../../YoinMac/Data/MacHomeContent.swift#L215) switches account and plays the selection. | — |
| All view | fixed | [MacModel.swift:48](../../YoinMac/App/MacModel.swift#L48) defaults to All; [MacLibraryView.swift:110](../../YoinMac/UI/MacLibraryView.swift#L110) renders the mixed artist/album/playlist grid. | — |
| Favorites | fixed | [MacLibrary.swift:363](../../YoinMac/Data/MacLibrary.swift#L363) loads starred artists, albums and songs; [MacLibraryView.swift:127](../../YoinMac/UI/MacLibraryView.swift#L127) exposes the Favorites view. | — |
| Large album collections | fixed | [MacLibrary.swift:337](../../YoinMac/Data/MacLibrary.swift#L337) requests up to 100,000 Apple Music albums; [MacLibrary.swift:343](../../YoinMac/Data/MacLibrary.swift#L343) pages Subsonic to the same guard. | — |
| Nested detail navigation | fixed | [MacModel.swift:224](../../YoinMac/App/MacModel.swift#L224) appends pushed details and pops one on Back; [MacDetailViews.swift:45](../../YoinMac/UI/MacDetailViews.swift#L45) explicitly pushes album → artist navigation. | — |
| Overview’s personal metadata | fixed | [MacDetailViews.swift:75](../../YoinMac/UI/MacDetailViews.swift#L75) includes the personal metadata block; [MacDetailViews.swift:271](../../YoinMac/UI/MacDetailViews.swift#L271) renders Last Play, score emblem and review/edit action. | — |
| Spotify album save/remove | fixed | [MacDetailViews.swift:148](../../YoinMac/UI/MacDetailViews.swift#L148) exposes saved-state-aware actions; [MacLibrary.swift:792](../../YoinMac/Data/MacLibrary.swift#L792) performs the mutation and updates Library. | — |
| Responsive paper layout | partly | [MacAlbumScrapbook.swift:89](../../YoinMac/UI/MacAlbumScrapbook.swift#L89) now shrinks the paper to the pane, but retains a single lane capped at 560pt. | Add Android’s two- and three-lane masonry layouts when the available width permits them. |
| Ask/About content and management | still open | [MacAlbumScrapbook.swift:256](../../YoinMac/UI/MacAlbumScrapbook.swift#L256) builds track pieces from scores/notes only; [MacAlbumScrapbook.swift:480](../../YoinMac/UI/MacAlbumScrapbook.swift#L480) provides note editing without About management. | Render stored Ask/About cards, excerpts and facts, and provide their editing/generation controls in the scrapbook. |
| Artist Play/Shuffle | partly | [MacModel.swift:385](../../YoinMac/App/MacModel.swift#L385) queues songs across releases for the page actions, but [MacBottomBar.swift:96](../../YoinMac/UI/MacBottomBar.swift#L96) still plays only `releases.first`. | Route the bottom-bar artist Play/Shuffle actions through the same discography queue builder. |
| Follow/Favorite artist | fixed | [MacDetailViews.swift:247](../../YoinMac/UI/MacDetailViews.swift#L247) renders the provider-specific star; [MacLibrary.swift:821](../../YoinMac/Data/MacLibrary.swift#L821) writes Spotify follow or Subsonic favorite state. | — |
| Most Played meaning | still open | [SubsonicMapping.swift:149](../../Yoin/Data/SubsonicMapping.swift#L149) ranks server play counts, [AppleMusicMapping.swift:385](../../Yoin/Data/AppleMusicMapping.swift#L385) uses top songs, and [SpotifyMapping.swift:160](../../Yoin/Data/Spotify/SpotifyMapping.swift#L160) supplies an empty list. | Build Most Played from the listener’s account-scoped local history for every provider. |
| Whole-playlist download | fixed | [MacDetailViews.swift:410](../../YoinMac/UI/MacDetailViews.swift#L410) exposes the Subsonic collection action; [MacModel.swift:431](../../YoinMac/App/MacModel.swift#L431) downloads/removes/retries the playlist collection. | — |
| Library/catalog scope | fixed | [MacSearchOverlay.swift:89](../../YoinMac/UI/MacSearchOverlay.swift#L89) provides the scope picker; [MacLibrary.swift:726](../../YoinMac/Data/MacLibrary.swift#L726) dispatches saved-library versus catalog searches. | — |
| Playlist results | fixed | [MacLibrary.swift:28](../../YoinMac/Data/MacLibrary.swift#L28) preserves playlist hits; [MacSearchOverlay.swift:168](../../YoinMac/UI/MacSearchOverlay.swift#L168) renders and opens them. | — |
| Further result pages | partly | [MacSearchOverlay.swift:207](../../YoinMac/UI/MacSearchOverlay.swift#L207) loads further pages using stored offsets, but failures use the whole-search [Retry action at line 129](../../YoinMac/UI/MacSearchOverlay.swift#L129). | Retry the failed page while preserving accumulated results; the current Retry restarts the search and replaces them. |

## Part B — Now Playing, queue, scrobble, Spotify, Apple Music

| Row | Status | Mac evidence | What remains |
|---|---|---|---|
| Current-song actions | still open | [MacNowPlaying.swift:186](../../YoinMac/UI/MacNowPlaying.swift#L186) gives the cover only a focus tap; [MacNowPlaying.swift:482](../../YoinMac/UI/MacNowPlaying.swift#L482) gives the heart only a like action. | Expose current-song playlist/share actions and album, artist, scrapbook and service destinations directly from Now Playing. |
| Playback failures in full mode | fixed | [MacRootView.swift:120](../../YoinMac/UI/MacRootView.swift#L120) overlays the failure banner above full Now Playing, including focus mode. | — |
| Browsing away from the playhead | fixed | [MacNowPlaying.swift:780](../../YoinMac/UI/MacNowPlaying.swift#L780) suspends following on manual scrolling and provides Back to Current to resume it. | — |
| Persistent original-lyrics cache | fixed | [MacLyrics.swift:113](../../YoinMac/Data/MacLyrics.swift#L113) reads disk before fetching; [MacLyrics.swift:373](../../YoinMac/Data/MacLyrics.swift#L373) keeps chosen lyrics usable without expiry. | — |
| Rating precision | fixed | [MacNowPlaying.swift:465](../../YoinMac/UI/MacNowPlaying.swift#L465) rounds the 0–10 drag value to tenths and commits that value. | — |
| Draft lifetime | fixed | [MacNowPlaying.swift:1572](../../YoinMac/UI/MacNowPlaying.swift#L1572) restores the account’s saved draft; [MacNowPlaying.swift:1580](../../YoinMac/UI/MacNowPlaying.swift#L1580) persists words, timestamp and target outside the popover. | — |
| Queue visibility limit | fixed | [MacNowPlaying.swift:597](../../YoinMac/UI/MacNowPlaying.swift#L597) uses the complete upcoming list; [MacNowPlaying.swift:643](../../YoinMac/UI/MacNowPlaying.swift#L643) wires selection, removal and reordering. | — |
| History for mirrored Spotify playback | fixed | [MacSpotifyOutput.swift:485](../../YoinMac/Playback/MacSpotifyOutput.swift#L485) reports mirrored playback ticks; [MacModel.swift:565](../../YoinMac/App/MacModel.swift#L565) opens history rows and counts actual listening. | — |
| Queue handed to Spotify | fixed | [MacSpotifyOutput.swift:189](../../YoinMac/Playback/MacSpotifyOutput.swift#L189) sends the current song plus upcoming Spotify URIs, bounded to 100. | — |
| Automatic queue advancement | fixed | [MacSpotifyOutput.swift:378](../../YoinMac/Playback/MacSpotifyOutput.swift#L378) follows Spotify’s advancement through the handed list; [MacSpotifyOutput.swift:647](../../YoinMac/Playback/MacSpotifyOutput.swift#L647) advances the engine when more queued songs remain. | — |
| Start confirmation | still open | [PlayerEngine.swift:477](../../Yoin/Playback/PlayerEngine.swift#L477) publishes playing immediately; [MacSpotifyOutput.swift:211](../../YoinMac/Playback/MacSpotifyOutput.swift#L211) clears buffering after HTTP success before starting observation. | Keep the requested song pending until observed Spotify playback confirms it, with bounded failure/recovery handling. |
| Queue while mirroring | fixed | [MacSpotifyOutput.swift:552](../../YoinMac/Playback/MacSpotifyOutput.swift#L552) reads Spotify’s queue; [MacNowPlaying.swift:550](../../YoinMac/UI/MacNowPlaying.swift#L550) selects the Spotify Up Next view while mirroring. | — |
| Shuffle/repeat while mirroring | fixed | [MacNowPlaying.swift:282](../../YoinMac/UI/MacNowPlaying.swift#L282) uses Spotify’s reported modes; [MacSpotifyOutput.swift:519](../../YoinMac/Playback/MacSpotifyOutput.swift#L519) sends shuffle/repeat commands to the owned device. | — |
| Like-state freshness | partly | [MacModel.swift:126](../../YoinMac/App/MacModel.swift#L126) refreshes on foreground entry and [MacLibrary.swift:885](../../YoinMac/Data/MacLibrary.swift#L885) can clear old overrides, but [SpotifySource.swift:390](../../Yoin/Data/Spotify/SpotifySource.swift#L390) still returns cached true immediately. | Recheck unchanged tracks periodically and bypass or invalidate cached true values so Spotify-side changes become visible. |
| Failed transport commands | still open | [MacSpotifyOutput.swift:152](../../YoinMac/Playback/MacSpotifyOutput.swift#L152) still discards command errors with `try?`; [MacSpotifyOutput.swift:630](../../YoinMac/Playback/MacSpotifyOutput.swift#L630) routes external skips through that helper. | Surface rejected pause/resume/seek/skip commands with actionable failure or reconnect feedback. |
| Library-add confirmation | still open | [MacLibrary.swift:941](../../YoinMac/Data/MacLibrary.swift#L941) optimistically fills the Apple Music heart, waits three seconds, checks once and clears the override unless confirmed. | Preserve an accepted-but-unconfirmed Pending state and continue confirmation instead of silently reverting the heart. |

## Part C — Memories, settings/accounts, downloads, sync, backups, NeoDB, Gemini

| Row | Status | Mac evidence | What remains |
|---|---|---|---|
| Playback from diary entries | still open | [MacMemories.swift:98](../../YoinMac/UI/MacMemories.swift#L98) renders diary track titles, ratings and note contents as text. | Add track playback, note playback/seek, timestamp display and current-playhead highlighting. |
| Generating Memory titles and prose | still open | [MacModel.swift:664](../../YoinMac/App/MacModel.swift#L664) reads stored titles/copy; [MemoryDeck.swift:123](../../Yoin/Memories/MemoryDeck.swift#L123) displays supplied prose without generating it. | Implement local Memory-title generation and narration/question fallbacks rather than relying on imported copy. |
| Editing a Subsonic account | fixed | [MacAccountSettings.swift:226](../../YoinMac/UI/MacAccountSettings.swift#L226) exposes editable credentials plus Test/Save; [MacSession.swift:334](../../YoinMac/Data/MacSession.swift#L334) validates and saves the changes. | — |
| Spotify reconnect | fixed | [MacAccountSettings.swift:158](../../YoinMac/UI/MacAccountSettings.swift#L158) exposes Reconnect for missing tokens or a failed active account; [MacSession.swift:154](../../YoinMac/Data/MacSession.swift#L154) reauthorizes the same Spotify user. | — |
| Removing the active account | fixed | [MacModel.swift:824](../../YoinMac/App/MacModel.swift#L824) removes the account and activates the first remaining profile. | — |
| Playlist downloads | fixed | [MacDetailViews.swift:410](../../YoinMac/UI/MacDetailViews.swift#L410) exposes collection download state/actions; [MacModel.swift:431](../../YoinMac/App/MacModel.swift#L431) supplies the playlist entries. | — |
| Retrying partial downloads | fixed | [MacModel.swift:483](../../YoinMac/App/MacModel.swift#L483) calls `retryFailed`; [OfflineDownloads.swift:275](../../Yoin/Data/Cache/OfflineDownloads.swift#L275) requeues failed songs without deleting completed files or their quality. | — |
| Offline manually selected lyrics | fixed | [MacLyrics.swift:204](../../YoinMac/Data/MacLyrics.swift#L204) saves and applies the chosen text through the disk-writing path; [MacLyrics.swift:373](../../YoinMac/Data/MacLyrics.swift#L373) restores chosen lyrics without a TTL. | — |
| Reconnecting Google | fixed | [MacSignInView.swift:405](../../YoinMac/UI/MacSignInView.swift#L405) provides Reconnect Google for `NeedsReauth`. | — |
| Recovering a removed cloud copy | fixed | [MacSignInView.swift:408](../../YoinMac/UI/MacSignInView.swift#L408) exposes Upload Again for `CloudCopyRemoved`. | — |
| Cloud-only accounts | fixed | [MacSignInView.swift:423](../../YoinMac/UI/MacSignInView.swift#L423) lists cloud-only accounts with Add on This Mac; [MacSignInView.swift:475](../../YoinMac/UI/MacSignInView.swift#L475) exposes link candidates on local account rows. | — |
| Earlier versions | fixed | [MacBackups.swift:73](../../YoinMac/UI/MacBackups.swift#L73) lists earlier versions and passes their `since` value; [MacBackups.swift:226](../../YoinMac/UI/MacBackups.swift#L226) opens the selected version. | — |
| Correcting song matches | fixed | [MacBackups.swift:265](../../YoinMac/UI/MacBackups.swift#L265) offers candidate selection and [MacBackups.swift:278](../../YoinMac/UI/MacBackups.swift#L278) exposes search for missing matches. | — |
| Failed playlist creation | fixed | [MacBackups.swift:214](../../YoinMac/UI/MacBackups.swift#L214) explicitly reports that the playlist was not created when `record.failed` is true. | — |
| Diary sync status/retry | fixed | [MacMemories.swift:140](../../YoinMac/UI/MacMemories.swift#L140) renders NeoDB status and Retry; [MacMemories.swift:46](../../YoinMac/UI/MacMemories.swift#L46) also exposes failure/retry on the Memory tile. | — |
| Answer/translation language | fixed | [MacSignInView.swift:351](../../YoinMac/UI/MacSignInView.swift#L351) provides the supported target-language picker and saves its selection. | — |
| Ask Gemini | fixed | [MacNowPlaying.swift:1050](../../YoinMac/UI/MacNowPlaying.swift#L1050) provides question input/submission; [MacNowPlaying.swift:1118](../../YoinMac/UI/MacNowPlaying.swift#L1118) calls the shared Ask loader. | — |
| Managing/regenerating About entries | partly | [MacNowPlaying.swift:1186](../../YoinMac/UI/MacNowPlaying.swift#L1186) now offers Regenerate and Delete, but no Manage in Scrapbook action or entry editor. | Add the scrapbook management route and editing flow for the selected About entry. |
| About error/key recovery | fixed | [MacNowPlaying.swift:1018](../../YoinMac/UI/MacNowPlaying.swift#L1018) renders missing-key Settings access and failed-request explanation/Retry. | — |
| Translation changing lyric source | partly | [MacLyrics.swift:240](../../YoinMac/Data/MacLyrics.swift#L240) now offers alternate-source lyrics for confirmation, but [MacLyrics.swift:225](../../YoinMac/Data/MacLyrics.swift#L225) still searches native providers when the current source has no applicable native translation. | Match Android’s policy by sending non-native current sources directly to Gemini rather than offering an alternate source first. |

## Part D — Tips, onboarding, standing-rules audit

| Row | Status | Mac evidence | What remains |
|---|---|---|---|
| Reader and demonstrations | partly | [MacTips.swift:144](../../YoinMac/UI/MacTips.swift#L144) implements a paged reader with read tracking, but [MacTips.swift:166](../../YoinMac/UI/MacTips.swift#L166) shows only a static icon and text for the ten Mac tips. | Add interactive animated demonstrations for the applicable Mac tips; page-transition animation alone does not replace them. |
| Selected-service flow | fixed | [MacRootView.swift:13](../../YoinMac/UI/MacRootView.swift#L13) hands selected services to [MacModel.swift:70](../../YoinMac/App/MacModel.swift#L70); [MacSignInView.swift:15](../../YoinMac/UI/MacSignInView.swift#L15) presents each connection step and advances after connection or Skip. | — |
| Focus from About loses lyrics | fixed | [MacNowPlaying.swift:69](../../YoinMac/UI/MacNowPlaying.swift#L69) switches About to Lyrics when entering focus. | — |

## Still-open rows that look small

Likely under roughly 50 changed lines each, subject to implementation checks:

- **Part B — Current-song actions:** reuse [SongMenu](../../YoinMac/UI/MacComponents.swift#L196) on the current cover, adding the scrapbook/service destinations.
- **Part B — Failed transport commands:** replace the central discarded-error path in [the command helper](../../YoinMac/Playback/MacSpotifyOutput.swift#L152) with account/device-scoped reporting wired to a visible notice.
## After this recheck (2026-11-07, same day)

Fixed on top of the commit above, in round 11:

- **Artist Play/Shuffle** (Part A, was partly): the bottom bar's Play / Shuffle on an artist page now queue every release, as the page's own buttons do.
- **Current-song actions** (Part B, was still open): right-clicking the Now Playing cover opens the song menu (queue, like, rate, add to playlist, download, album / artist, share).
- **Further result pages** (Part A, was partly): a failed Load More keeps the results so far and offers Retry for that page only.

Counts with these: fixed 46, partly 5, still open 7 (59).
