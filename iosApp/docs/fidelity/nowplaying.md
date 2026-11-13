# Now Playing: layout parity (Compact phone)

Android source of truth: `app/src/main/java/com/gpo/yoin/ui/nowplaying/` (`CompactPlayingContent`
in NowPlayingScreen.kt, PlaybackControls.kt, BottomPills.kt), `ui/component/RatingSlider.kt`,
`ui/component/LyricsDisplay.kt`. iOS: `iosApp/Yoin/NowPlaying/NowPlayingView.swift`.
Slot heights still come from the shared Kotlin budget (`resolveStageColumnBudget`). dp → pt 1:1.

Kept on purpose (owner's iOS decisions): glass transport buttons, glass ⌄ circle, glass rating
track + held state, progress bar morphing into glass while touched, glass pills, the v4
neighbour-album background, lyrics running on under the transport, no halftone dots.

Screenshots: `iosApp/screenshots/iphone-np.png`, `-dark`, `-short`, `-touch`, `-touch-dark`.

| Element | Android (file:line) | iOS before | iOS after |
|---|---|---|---|
| ⌄ glyph | 28 dp `ChevronDown`, tint onSurfaceVariant (NowPlayingScreen.kt:3858) | 26 pt, onSurface | 28 pt, onSurfaceVariant (still in the glass circle) |
| "PLAYING FROM ALBUM" | labelSmall 11/16, 0.1 sp, onSurfaceVariant 55 % (NowPlayingScreen.kt:5047) | 11 pt, natural line box | `yoinRole(labelSmall)`: 16 pt line box |
| Album name | labelMedium 12/16, 0.1 sp, onSurface (NowPlayingScreen.kt:5054) | 12 pt, natural line box | `yoinRole(labelMedium)` |
| Liked heart | 44 dp circle: liked = **tertiary** container + **onTertiary** glyph; not liked = tertiaryContainer + onTertiaryContainer (NowPlayingScreen.kt:5135–5150) | Untinted glass with a **tertiary glyph** (dark olive on a light circle) | Liked: glass tinted with tertiary (the PAUSE glass recipe, 55 %), glyph onTertiary in light / white in dark (same legibility rule as PAUSE and the pills). Not liked: plain glass, onSurfaceVariant glyph (onTertiaryContainer if the lab tints the heart). Note: Android's heart is tertiary too, so for this violet cover it is green there as well; see report |
| Tabs | labelLarge 14/20, Bold when selected, 50 % unselected, ×1.08 left-anchored, 12 apart, top of a 30 slot (NowPlayingScreen.kt:4101–4150) | 14 pt, natural line box | `yoinRole(labelLarge)` (20 pt line box); rest unchanged |
| Lyric lines | active bodyLarge 16/24 rounded SemiBold primary; others bodyMedium 14/20 onSurfaceVariant; 4 above/below, 2 apart; alpha 1/.55/.40/.28; scale .94 left (LyricsDisplay.kt:240–300) | natural line boxes; scroll assumed 30 pt per line | role line boxes (24 / 20 + 8), scroll step = 20 + 8 + 2 from the tokens; anchor = `LYRICS_ANCHOR_FRACTION` |
| One-line lyric (short) | 32 dp line centered in its ≤ 44 slot (`OneLineLyricRow`, NowPlayingScreen.kt:1825) | top-aligned at 4 pt | centered: (slot − 32)/2 |
| PAUSE label | titleLarge×0.9 Bold, 0.5 sp, stretch 1.06 from the **leading** edge, onPrimary on primary (PlaybackControls.kt:171–176, 312) | stretch from center; onPrimary also in dark → dark text on light glass, unreadable | stretch anchored leading; onPrimary in light, white in dark |
| Time labels | labelLarge tabular, min width 44 / 52, 8 dp each side of the bar, flush with the row ends (PlaybackControls.kt:474–503) | min 40 / 48, 6 pt gaps, row inset 10 pt for the glass → bar 8 pt narrower, labels pulled in | 44 / 52, 8 pt gaps, no inset: bar and labels sit at Android's x. The touch-glass capsule is drawn outside the row instead (4 pt before, 10 pt after), so it still clears Previous |
| Hero, one row (short) | title displaySmall, artist titleMedium×0.9 on its baseline, 12 apart, artist ≤ 40 % of the row and measured first; slot content at the **top** (NowPlayingScreen.kt:4700–4760, 4905–4906, `StageHeightSlot` 4564) | bottom-aligned + 12 pt bottom padding (row rose when the slot was squeezed); artist uncapped; no artist stretch | top-aligned; artist capped at 40 %, layout priority over the title; artist stretch 1.02 |
| Hero, stacked | artist titleMedium×0.9 (24 line) / 2 / title displaySmall (44 line), stretches 1.02 / 1.03 | same | unchanged |
| Format tags (after the artist; also the song sheet's header, under the artist) | a block per tag, codec first then the layout ("FLAC 16/44.1", "5.1"): labelSmall 11/16 tabular, 6 × 1 inside a 6-corner block, 4 apart, secondaryContainer / onSecondaryContainer (`AudioFormatTagRow.kt`, `AudioFormatTags.kt`, NowPlayingScreen.kt:6445, TrackMenu.kt:454) | one capsule in onSurfaceVariant 12 %; the raw suffix ("M4A 256", "WAV"); a multichannel file showed its layout alone ("5.1"); none in the song sheet | `FormatTagRow` (Theme/Components.swift) with Android's numbers and colours, fed by `FormatTags.tags` (Playback/ReplayGain.swift, a port of `audioFormatTags` and `subsonicAudioFormat`): "AAC 256", "WAV 44.1", "FLAC 16/44.1" + "5.1"; an MP4 the server couldn't read gets no tag. Debug: `-formatDemo flac,0,16,44100,6` |
| Spine (short columns) | where the resting cover is height-capped, the band between it and the rating gap (stage − 48 − 12 − rating column − cover, shown from 24): one fact at a time turned a quarter, labelLarge onSurfaceVariant, label 60 %; cached About credits then the Subsonic server's quality / rating / genre / year / track, else album · artist · title; 9 s dwell, long facts pan at 18 pt/s after 1.2 s, swipe across steps past 32 (damped 0.15, ≤ 14), ends fade 20, fades with the rating's retreat (`NowPlayingSpine.kt`, NowPlayingScreen.kt:1599, 1831) | none | `NowPlayingSpine.swift`, the same numbers; the server's record comes from `getSong` without asking Gemini. iPhones in portrait never open the band (cover width-capped); `-screen np-short -npShortHeight 560` shows it |
| Pills | bottom-aligned in their slot, no bottom padding (NowPlayingScreen.kt:1918) | extra 8 pt bottom padding | removed: pills sit on the column's bottom edge as on Android |
| Rating number | titleLarge 22/28 tabular, 8 dp from the top; color lerps onSurfaceVariant → onPrimary as fill goes 80 → 100 % (RatingSlider.kt:205–225) | 22 pt natural box; hard switch at 80 % | `yoinRole(titleLarge)` line box; color mixed over 80 → 100 % |
| Cover, rating column, transport rows, pill sizes/shapes, column padding | (budget + YoinTokens) | already matched | unchanged |

## Not matched

- Lyric lines stay single-line (`lineLimit(1)`); Android wraps long lines. The iOS scroll offset
  is computed from fixed line heights, so wrapping would need measured rows.
- Short screens: Android shows only the current line; iOS lets the next line run on under the
  transport (owner's "lyrics under the transport" decision).
- `-screen np-short` puts the height the 700 pt reference wouldn't have between transport and
  hero (debug comparison only).
- Format tags show the file as the server lists it. Android's Now Playing shows what arrives instead
  (`playingFormatTags`: "Opus 192" for a transcode, the decoder's channel count and codec), names
  E-AC-3 JOC "Dolby Atmos" from the decoder's MIME type, and adds an accent "Spatial" while the system
  spatializes. iOS has the "Dolby Atmos" wording (`FormatTags.codecLabel`), but nothing reads the
  playing item's format yet, so that tag and "Spatial" never show.
- Android-only affordances not ported here: TrackLibraryButton, Cast button, play-mode split, PLAY/PAUSE width morph.
