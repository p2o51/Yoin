# Tips: the tip page and its demo phone

Android source of truth: `app/src/main/java/com/gpo/yoin/ui/tips/` (`TipScreen.kt`, `TipDemoPhone.kt`, `TipIllustration.kt`, `TipDemoData.kt`, the `Tip*Scene(s).kt` files) and `ui/settings/TipsPage.kt`. iOS: `iosApp/Yoin/Tips/` and `Settings/TipsView.swift`. 1 dp = 1 pt.

Pass of 2026-11-13 (slices 1, 2 and 3 of 3). Checked on iPhone 17 Pro and iPad Pro 13 simulators, light and dark, with `-screen tips` and `-screen tip -tip <id>`.

## Where it opens

- Settings › About › Tips: a row opens that tip's page (it used to toggle read). The check stays on read rows.
- Home's bubble, when it says a tip: the tip's page (`Route.tip`), and the tip is not marked read by the tap (Android: it is read on its page). It used to open the list and mark it read.

## The page (TipScreen.kt)

| Element | Android | iOS |
|---|---|---|
| Pager | `HorizontalPager`, one tip a page, starts at the tip opened | Horizontal paging `ScrollView` on a `ScrollPosition`; the page a swipe rests on is the settled one |
| Header | back, "3 / 19" labelLarge, end 24 | The pages' glass back circle; the same label |
| Page column | max 640, 24 sides, 24 top and bottom; illustration, 28, tile 64 + 16 + title headlineMedium, 16, text bodyLarge, 12, "Updated …" labelLarge in `outline` | Same |
| Illustration card | `surfaceContainerLow`, corner 28, 16 above and below the phone | Same |
| Tile | symbol 30 on a 64 shape; turns 40° and shrinks 20 % as its page slides a whole page away; grows in from the previous tip's shape on the spatial spring | Same numbers. The six MaterialShapes (Cookie9Sided, Clover4Leaf, Sunny, Cookie6Sided, Gem, Pentagon) are the scalloped circle the account avatars are cut from (`AvatarShape`), by lobes and depth, so one morphs into the next |
| Read | M3 toggle, 56 tall; checked: primary, a check, squared off | Tonal `surfaceContainerHigh`; read: `primaryContainer`, a check, corner 28 → 16 |
| Next / Done | Filled, 56 tall, chevron; marks read, goes to the next unread (wrapping); Done when none, which closes | Same (`Tips.nextUnread`) |
| Foot | max(nav bar, 16) | The phone's bar floats over the page: the buttons sit `ShellBarMetrics.clearance` above the safe area; iPad's column already clears its dock |

The header and the buttons are fixtures of the page, not chrome over moving content: the pager runs between them and fades 12 pt at both ends. So the buttons are tonal, not glass (glass-layers.md R1), with Next the one filled accent (R2). That is why a read toggle takes `primaryContainer` here, not Android's full primary.

A tip without a demo yet opens on its tile and title.

## The demo phone (TipDemoPhone.kt)

| | Android | iOS |
|---|---|---|
| Frame | 168 × 300, corner 24, 1.5 `outlineVariant` rim, `surface` | Same |
| Screen | laid out 360 wide (643 tall), drawn scaled to the frame | Same (`DemoPhoneMetrics`); a 28 pt band at the top stands in for the status bar |
| `tall` | 20:9 frame, 412-wide screen | In the metrics; no scene uses it yet |
| Growth | min(36 % of the window's height / 300, half its width / 168), 1…1.7 | Same, of the page's own size (an iPad column, not the window) |
| Touch | disc r 14 at 22 %, dot r × 0.42 at 90 %, both × 0.85 when down; ripple r → r × 2.4 fading from 50 %, 2 pt line; hold ring r × 1.3, 2.5 pt | Same |
| Timing | appear 180, tap down 70, ripple 420, hold down 90, hold 550 (linear), drag down 80, lift 220, moves on the spatial spring; start 500, loop gap 700 | Same (`DemoTiming`); tweens are FastOutSlowIn |
| Takeover | first touch stops the demo, 5 s untouched restarts it; touches are watched, never taken | Same. The watcher is a UIKit recognizer that fails at touch-down: a SwiftUI gesture watching from the first point kept the pager from turning when a swipe began on the phone |
| Reduced motion | phone still, pressable | Same |

Scripts are `async` and step with `DemoTiming.delay`; a scene's own values change inside `withAnimation`, as the app's do. `finger.drag(…, along:)` moves a scene value on the finger's curve (Android's per-frame `onProgress`).

## Scenes so far

| Tip | Android scene | iOS |
|---|---|---|
| SpotifyElsewhere | `SpotifyElsewhereDemo` | Home's `SpotifyElsewherePill` and `HeaderAccountAvatar` in the demo Home header; 700, show, 1600, tap, 2000 |
| MemoryLedger | `MemoryLedgerDemo` | `HomeMemoryLedgerSection` → `AllMemoriesView` (cross-fade); 900, tap, 1100, tap the newest week with something, 2600 |
| LyricHighlight | `LyricHighlightDemo` | `HomeLyricCardsSection` in the demo Home; turns to card 2, 3, back to 2 (420 ms drags, 900 between), tap |
| AccountQuickSwitch | `AccountQuickSwitchDemo` | Home's own hold (`accountQuickSwitch(fan:space:)`) and column (`AccountFanColumn`) on a fan of the phone's own, in its screen's space, haptics off; hold 550, column, 450, slide onto the first and second account (380 each), let go on the second, 1800 |
| EditHome | `EditHomeDemo` | Home's `HomeEditBlock` (plate, sway, Hide, handle) over the demo Jump Back In, the real `EditTitleSwap` and `HeaderActions` (Undo, Done) on a `HomeEditController` with a layout file of its own; hold the first cover, 2200, tap Done, 600 |
| SongMenu | `SongMenuDemo` | `AlbumDetailView(album:demo:)` with its list up, then `TrackMenuBody` (the sheet's own body, split out of `TrackMenuSheet`) in a sheet of the phone's own (`DemoSheetStage`); hold row 3, 1800, tap above the sheet |
| Downloads | `DownloadsDemo` | The same, with the sheet's Download row (`OfflineDownloads.rowLabel / rowIcon`); tap Download, the row reads Remove download, sheet down, 900, hold again, point at Remove download, 1800, tap above |
| PlayModes | `PlayModesDemo` | `NowPlayingView(demo:)`'s own `PlayModeButton` (split held by the demo, haptics off); tap, 600, tap, 600, hold to split, 250, shuffle half, 500, repeat half, merge after `holdSeconds` + 600 |
| FocusCover | `FocusCoverDemo` | `NowPlayingView(demo:)` on the `tall` (20:9) phone; tap the cover, focus 2400, tap, 1400 |
| CoverMenu | `CoverMenuDemo` | Hold the cover, the playing song's sheet (Favorite / Add to playlist / Share, Go to album), 1800, tap the cover above it |
| KeepScreenOn | `CoverMenuDemo(keepScreenOn:)` | The same sheet with `TrackMenuToggleRow`; switch Keep screen on, 1200 |
| MiniPlayerMenu | `MiniPlayerMenuDemo` | The demo Library under the playing bar (`DemoShellBar(nowPlaying:)`: Home, the real `PillContent(demo:)`, Library, Search); hold the pill, the playing song's sheet (as the app's pill gives it: Favorite / Add to playlist / Share, Go to album, Keep screen on), 1800, tap above it |
| NoteMoments | `NoteMomentsDemo` | `NowPlayingView(demo:)`: tap Write (the phone keeps the playhead's second as an anchor, `NowPlayingDemo.write`), 1600; scrub from 14 % past the anchor onto it over 1100 (the bar catches, its bubble reads Note), 1100, on to 5 % before it over 500 |
| LibrarySearch | `LibrarySearchDemo` | The demo Library (`DemoLibraryPage`: `LibrarySearchPillLabel`, the account, `LibraryFilterRow`, `LibraryGridCellFace` three to a row) under the idle bar; hold Library, Search opens over it (`DemoLibrarySearch`), 1800, tap ✕ |
| Labs | `LabsDemo` | `SettingsPage(demoScheme:)` + `LabsBody` (split out of `LabsView`) with the model ready; 700, tap the Lyric Cards switch, 2400 |
| Name | `NameDemo` | The mark (`YoinArmsCanvas`, tertiary / primary / secondary arms) 96 in the middle; a tap anywhere sounds three rings 0.22 s apart over 2.4 s, to 60 % of the screen's height; tap, 1200, tap, 2000 |
| PullForMemories | `PullDemo` | The demo Home under Memories (`DemoMemoriesPage`: the aurora, the real `MemoryCardFace` and `MemoriesTopBar` on a `MemoriesDeckModel` of its own); pull from under the header to 62 % over 750 (Memories comes down 45 % with it), the spring opens it, 1800, tap the bar's Home pill, 900. A pull by hand works too: past 25 % opens, short of it springs back |
| MemoriesAllAccounts | `MemoriesAllAccountsDemo` | Two `DemoMemoriesPage`s side by side; 900, swipe from 80 % to 25 % over 600 (the deck moves 60 %), the spring settles on the other account's card, 2800 |

The demo Home (`DemoHomePage`) is Home's header row (greeting, the real `EditTitleSwap`, the account avatar), Jump Back In's `AlbumItem`s three to a row, and the idle bar drawn from `ShellNavFace` (the bar's own button face) in the bar's glass.

Differences kept:

- Covers are the mock mesh covers iOS draws from a palette, in the three colours of each `tip_demo_cover_*` drawable.
- Lyric Cards: going back a card, the finger slides rightwards with the cards (Android's slides leftwards on every turn).
- All Memories on the phone has no glass edge blur under its top bar: the system draws it at the window's scale, over the phone's rim.
- Edit Home: the finger taps Done in the header, where iOS keeps Undo and Done (Android's bar turns into them).
- Now Playing's one-line hero: the artist now takes its own width up to its cap (Android `widthIn(max =)`), in the app too; a cap-wide frame had cut "Slow Light" short beside "Mira Sol".
- Library on the phone: no sort row over the grid (its menu is the account's).
- Quick search: Search is a page of the app's window, so the phone draws its open state from SearchView's numbers (the field on Library's pill, the ✕, the types for a Subsonic server: no scope pair, no Playlists), as Android draws M3's full-screen search. Closing is the ✕ (Android: the back arrow), and the surface covers the bar, as Android's does.
- Mini player options: the sheet keeps Keep screen on, as iOS's pill sheet does (Android's leaves the player group out).
- Name: the page behind the mark is the Library / Home gradient (Android ExpressivePageBackground).
- Memories on the phone (`TipDemo.memoryDeck`): Night Garden with your review's line, then Harbour Lights from Okto (the switcher demo's Spotify account: its From line, no quote), then Low Tide. Each page's bar shows the deck's three dots at that page (Android's both stand on the first dot, with the first page's colours). The card takes no touch; the bar's Home pill closes the pull demo's Memories.
- Memories across accounts: a sideways swipe by hand turns the tips, as on every phone (Android's phone takes it and moves the deck). A pull by hand on Pull for Memories is a UIKit pan that begins only when the finger moves more up or down than across, so a swipe begun on the phone still turns the tips.
- Pull for Memories: the demo Home has no Memories chevron under the island (Home's bubble lives in the window's safe area); Android's pull grows its bubble's arrow.
- Volume leveling (Android VolumeLevelingDemo, 11-13): the page is the Subsonic account's (titled Subsonic, as ServiceSetupView names it) with its real Volume leveling group (`VolumeLevelingGroup`, which SubsonicAccountSettings draws too); Android's is its own Settings page. The dot picks By track, then By album; the choice is the demo's own.
- Views that take a demo (`AllMemoriesView(demo:)`, `HomeLyricCardsSection(demo:)`, `HomeMemoryLedgerSection(onOpen:)`, `SpotifyElsewherePill(demoCover:)`) load, play, open and remember nothing in it.

Now Playing on the phone (`NowPlaying/NowPlayingDemo.swift`): a made-up song (Android `DemoNowPlayingTrack`: Harbour Lights' first song, 3:34, from 0:58, FLAC 24/96, a favorite, 8.2) with its own playhead on a 250 ms tick, read through `PlaybackPosition` (`\.demoPlayhead`). In demo mode the page plays, loads, writes, opens and folds nothing; its rating bar and heart change only the demo's values; the pager, the seek bar and the pills take no drag (a sideways drag on the phone turns the tips).

The album page on the phone: `AlbumDetailView(album:demo:)` starts and stays on its track list (no hero, butterfly, scrapbook, liner notes or edge gate), its rows' hold is the demo's, and track 4 wears the note mark. The bar's Play split over its foot (`DemoPlaySplit`) is drawn from `ShellBar.playSplit`'s numbers, not the bar itself (the bar's split carries the app's menu and model), and takes no touch.

The phone's sheet (`DemoSheetStage`): the system's sheet belongs to the window, so the phone's rises from its foot on the spatial spring, 8 inside the screen's edges, corner 38, the grabber 36 × 5, on regular glass (content moves under it: the page behind steps back by the system sheet's GlassLab numbers, dimmed at least 10 %). Android's Keep screen on demo scrolls its sheet first; on iOS the sheet fits the phone and the finger goes straight to the switch.

## Without a scene

- HomeScreenWidgets: Android draws one since #207 (its widget picker over a home screen); iOS's widget views live in the widget extension (Widgets/), which the app target doesn't build, so the phone can't draw them yet. Left for a pass of its own.
- ContinueOn: on iOS the offer is the system's Handoff (the App Switcher's bottom, the Dock), not a Yoin card, so there is no Yoin component to act it out; Android's scene is its tablet taskbar and card.
- SpotifySpeakers: the Devices sheet (`SpotifyDevicesSheet`) reads `SpotifySource` and `SpotifyDeviceMemory` for the account in use; a phone of its own needs the sheet split from its loading the way TrackMenuBody was. Left for a pass of its own.
