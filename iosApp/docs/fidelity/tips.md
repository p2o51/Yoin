# Tips: the tip page and its demo phone

Android source of truth: `app/src/main/java/com/gpo/yoin/ui/tips/` (`TipScreen.kt`, `TipDemoPhone.kt`, `TipIllustration.kt`, `TipDemoData.kt`, the `Tip*Scene(s).kt` files) and `ui/settings/TipsPage.kt`. iOS: `iosApp/Yoin/Tips/` and `Settings/TipsView.swift`. 1 dp = 1 pt.

Pass of 2026-11-13 (slice 1 of 3). Checked on iPhone 17 Pro and iPad Pro 13 simulators, light and dark, with `-screen tips` and `-screen tip -tip <id>`.

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

The demo Home (`DemoHomePage`) is Home's header row (greeting, the real `EditTitleSwap`, the account avatar), Jump Back In's `AlbumItem`s three to a row, and the idle bar drawn from `ShellNavFace` (the bar's own button face) in the bar's glass.

Differences kept:

- Covers are the mock mesh covers iOS draws from a palette, in the three colours of each `tip_demo_cover_*` drawable.
- Lyric Cards: going back a card, the finger slides rightwards with the cards (Android's slides leftwards on every turn).
- All Memories on the phone has no glass edge blur under its top bar: the system draws it at the window's scale, over the phone's rim.
- Views that take a demo (`AllMemoriesView(demo:)`, `HomeLyricCardsSection(demo:)`, `HomeMemoryLedgerSection(onOpen:)`, `SpotifyElsewherePill(demoCover:)`) load, play, open and remember nothing in it.

## Not done (slices 2 and 3)

Scenes for the other tips: AccountQuickSwitch, MiniPlayerMenu, FocusCover (the `tall` phone), Downloads, EditHome, PullForMemories, CoverMenu, SongMenu, PlayModes, NoteMoments, LibrarySearch, KeepScreenOn, Name, Labs, and the iOS-only VolumeLeveling and HomeScreenWidgets (no Android scene). A new scene: a view taking `playing` and `scheme`, built on `DemoPhone`, listed in `TipIllustration.scenes` and its switch.
