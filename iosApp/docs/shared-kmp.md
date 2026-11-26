# Shared Kotlin (`:shared` → YoinShared)

Yoin's Android app (Compose) and iOS app (SwiftUI) share their design numbers and their pure algorithms through one Kotlin Multiplatform module, `:shared`. Android depends on it like any library. iOS links it as the static XCFramework `YoinShared`.

## What is shared

| Area | Kotlin (shared/src/commonMain/kotlin) | Android uses it in | iOS uses it in |
|---|---|---|---|
| Material Color Utilities (MCU) | `hct`, `utils`, `palettes`, `dynamiccolor`, `scheme`, `temperature`, `contrast`, `dislike` (the vendored Google code, moved unchanged apart from JVM-only calls) | `ExpressiveColorSchemeFactory`, `DefaultColorScheme` | `CoverScheme` via `ExpressiveRoles` |
| Cover colour roles | `com.gpo.yoin.shared.theme.ExpressiveRoles` (SchemeExpressive SPEC_2025 → ARGB per Compose role) | — (Compose builds its `ColorScheme` directly) | `CoverScheme.argbRoles` |
| Palette | `com.gpo.yoin.shared.palette` (androidx.palette's `ColorCutQuantizer`, `Palette`, `Target`, default filter) | `CoverSeedExtractor` (decode + Builder resize stay on Android bitmaps) | `CoverSeed.pick` (`Palette.scaleDownPixels` stands in for the resize) |
| Tokens | `com.gpo.yoin.shared.tokens.YoinTokens`: `Bar`, `Shape`, `NowPlaying`, `Detail`, `MetaLine`, `Home`, `Library`, `Search`, `Typography`, `Motion`, `Glass` | `FloatingBottomBar`, `WindowAdaptiveRuntime`, `Shape.kt`, `Type.kt`, `GrooveType`, `Motion.kt`, `NowPlayingMetrics`, the NP phone column, the album hero cover | `Tokens.swift` → `NowPlayingView`, `ShellBar` (split button), `YoinFont`, `GlassLab` (Balanced preset), `AlbumDetailView`, `AlbumScrapbookView`, `ArtistDetailView`, `HomeView`, `LibraryView`, `SearchView`, `TypeRoles` |
| NP height budget | `com.gpo.yoin.shared.nowplaying` (`resolveStageColumnBudget`, DualPane, landscape, `squeezeGaps`) | `ui/nowplaying/NowPlayingBudget.kt` (Dp wrapper) | `NowPlayingBudget` (CGFloat wrapper) |
| Album spectrum | `com.gpo.yoin.shared.detail.computeAlbumSpectrum` + tone lock | `ui/detail/AlbumSpectrum.kt` | `AlbumSpectrum.strips(for:)` |
| Fast scroller maths | `com.gpo.yoin.shared.library.FastScrollMath` | `FastScrollMath` in `YoinFastScroller.kt` | `LibraryFastScroller`, `LibraryView.ScrollMetrics` |
| Scrapbook tilts | `com.gpo.yoin.shared.detail.ScrapbookTilt` (FNV-1a, tilt sets) | `AlbumScrapbook.kt` | `AlbumScrapbookView` |
| Note text | `com.gpo.yoin.shared.notes.NoteMarkup`, `MentionMatcher`; `YoinNoteReader` (flat lines / runs / mentions for Swift: no sealed types, no `Char`, nothing throws) | `NoteMarkupText`, the note editor, `MentionResolver` | `NoteMarkupRender` / `NoteMarkupText`, `NoteMentions` (`Notes/`) |
| Note editor | `NoteEditorText` (trigger, Return-picks-mention, atomic mentions, list continuation, shortcuts, formatting tools); `YoinMentionRanking` + `YoinMentionRomanizer` (the @ popup's order: prefix > initials > substring > artist, pinyin); `YoinNoteEditor` (the flat Swift facade, UTF-16 offsets, composing range as two ints) | `ui/notes/editor/` (`NoteEditorState`, `MentionRanking` wraps the shared ranking with ICU's Han-Latin) | `Notes/Editor/` (`NoteEditorModel`, `NoteMarkupEditor`, `MentionRanking` with `PinyinRomanizer` = CFStringTransform Mandarin → Latin) |
| Backup, import, Memories transfer | `com.gpo.yoin.shared.portability` (`BackupExport`, `BackupParser`, `ReadableExport`, `SnapshotAssembler`, `ImportPlanner`, `BackupMerger`; `memories/`: `MemoryItems`, `TransferRules`, `MigrationIds`, `MemoryTransferApplier`); `YoinBackup` (the flat Swift facade) | `data/sync/backup/` (`BackupWriter` / `BackupReader` zip the files; `SnapshotBuilder`, `BackupImporter`, `AccountScopes` delegate through `SharedBackupBridge.kt`), `data/transfer/memories/` (`MemoryTransfer.execute` → `MemoryTransferApplier` on `RoomTransferStore`) | `YoinBackup` over the `SyncDomain` + `CloudSyncKit.store` it already has, under `CloudSyncCore.beginExclusive` (`CloudSync.withBackup`); Swift writes / reads the zip (`Data/Sync/BackupZip.swift`), implements `YoinTrackAlbumLinkStore` (`TrackAlbumLinkStore`) and matches on the catalog (`TransferMatching`, `MemoryCatalogs`) |
| Library search: Memories & notes | `com.gpo.yoin.shared.search` (`SearchQuery` / `FoldedText` matching, `searchMemoryLedger`, `memoryAttachments`, `layoutSearchPage` + `packSearchTiles`, `searchFilters` / `shownRows`, `noteOpenTarget`, `MemorySearchService` over `MemorySearchPort`), `com.gpo.yoin.shared.memory` (the ledger entry model); `YoinMemorySearch` (the flat Swift facade) | `data/search/` (`SearchPage.kt` maps the layout back onto `Track` / `Album` / …; `MemorySearchSource` is the Room port), `ui/library/LibrarySearchFilter.kt` (`LibrarySearchFilter` = `SearchFilter`), `SearchNotePlayback.kt`, `YoinNavHost.openSearchNote` | `Search/SearchMemories.swift` (`MemorySearchLedger`: the ledger, album notes and AI titles as `YoinLedgerEntry`; `SearchMemories`: one read per query, `searchEntries` + `attachmentsOf`, the cached `page`), `SearchTiles.swift` (the All page, the Memories list, the marker-pen highlight), `SearchNoteOpen.swift` (`noteTarget`, `noteSeekReady`), `SearchView` (`filters` for the chips) |
| Album About | `com.gpo.yoin.shared.albumabout` (`AlbumAboutPrompts`: the About / credits-only / Ask prompts, search queries and lead, the `[CREDITS]` / `[ABOUT]` parser; `AlbumCredits`: items, line, segments; `EditorialNotes`; models `AlbumAboutGrounding`, `AlbumAiCredits`, `AlbumAboutReply`, `AlbumAboutRow` / `AlbumAboutContent`, `AlbumCreditItem` / `AlbumCreditLabels` / `AlbumCreditsLine`); `YoinAlbumAbout` (the flat Swift facade) | `data/remote/GeminiService.kt` (prompts, parse), `data/album/AlbumAbout.kt` (Album → grounding, Room rows → content), `ui/detail/AlbumCredits.kt` (string resources, ICU date), `AppleMusicSource.appleEditorialNotes`, `UiText` / `AiProviderName` (`tightenCjkSpaces`), `SongAboutEntry.normalize` (`questionKey`) | `Data/About/AlbumAboutStore.swift` (rows → `content`), `GeminiClient+AlbumAbout.swift` (prompts, `storedReply`, `Album.aboutGrounding`), `Album/AlbumCredits.swift` (Localizable labels, DateFormatter `yMMMMd` / `yMMMM`), `AppleMusicMapping.editorialNotes`, `AiProviderName.copy` (`tightenCjkSpaces`); sync through the shared `AlbumAboutSyncAdapter` |

How it is kept honest:

- `McuGoldenFixtureTest` (Android) writes and asserts `iosApp/YoinTests/Fixtures/mcu-golden.json`. `McuGoldenTests` (iOS) checks the shared MCU against it: 1152 / 1152 roles.
- `PaletteParityTest` (Android, Robolectric) runs androidx.palette and the shared port on the same bitmaps: every swatch, the dominant swatch and the six targets match exactly.
- `SharedParityTest` (Android) compares each moved algorithm with a test-only copy of the Android code it replaced (`app/src/test/.../shared/reference`).
- `YoinTokensTest` (Android) checks every adopted token against the literal it replaced, and the mirrored type scale and motion schemes against Material's own.
- `CrossPlatformDigestTest` (`shared/src/commonTest`) folds MCU, Palette, budget, spectrum, scroller and hash results into digests. The JVM and the iOS simulator must produce the same ones (`./gradlew :shared:allTests`).
- `SharedKotlinTests` (iOS) checks the Swift wrappers: the Balanced glass preset, the tilt hash, the budget filling the column, the spectrum slots.
- `SearchMemoriesTests` (iOS) builds the golden ledger from iOS's own rows (`LedgerEntry`, `AlbumNote`, memory copy rows), maps it with `SearchLedgerMapping` and checks every `search`, `attachments` and `pages` vector of `memory-search.json` against it.
- `SearchPinTest` (Android) pins what library search answered for fixed data before its core moved (fold, match, fragments, the ledger's hits, attachments, the All page at several widths, packing, chips); it passes unchanged through `:shared`. `MemorySearchGoldenTest` (`shared/src/androidHostTest`) writes and checks `shared/src/commonTest/resources/golden/memory-search.json` from the same data through `YoinMemorySearch`; `SearchCoreTest` and `YoinMemorySearchTest` run on the JVM and the iOS simulator (folding goes through NSString's NFKD there).
- `BackupExportPinTest` (Android) pins every entry of a fixed export as the pre-move app code wrote it; Android's export through `:shared` stays byte-identical. `PortabilityGoldenTest` (`shared/src/androidHostTest`) writes and checks `shared/src/commonTest/resources/golden/backup-export.json` (and verifies it against that pin), `backup-import.json` and `memories-transfer.json`; the portability tests in `commonTest` run on the JVM and the iOS simulator (`BackupDates` uses java.time on Android and NSTimeZone on iOS, same zone rules).
- `AlbumAboutPinTest` (Android, Robolectric) runs every vector of `shared/src/commonTest/resources/golden/album-about.json` through the app's own paths (an `Album` mapped to its grounding, the en / zh-CN string resources and ICU dates for the credits line, Apple Music's JSON, the Room rows); before the move the same vectors were checked against the pre-move app code (235 vectors, all equal). `AlbumAboutGoldenTest` (`shared/src/androidHostTest`) writes the file and its Kotlin mirror (`AlbumAboutGoldenData.kt`); `AlbumAboutGoldenReplayTest` (`commonTest`) answers every vector again on the JVM and the iOS simulator.

## iOS parity tokens (2026-11-04)

The per-screen fidelity passes (`docs/fidelity/*.md`) moved the constants iOS matched by hand into shared groups. Android doesn't read these yet; the values are the ones Android uses at the cited files, and adopting them there is a later, separate change.

- `Typography.GSF_NATURAL_LINE_HEIGHT` (1.252): Google Sans Flex's own line on iOS. `Theme/TypeRoles.swift` holds the one set of role helpers that read it: `yoinRole` (one-line roles: line box as min height), `homeType` / `homeLine` (wrapping text: the extra leading on every line), `YoinType.lineSpacing` (album and scrapbook paragraphs).
- `MetaLine`: group gap 12, Stat number → unit 4, muted 0.6. Home, Library, Search, album and artist meta lines.
- `Home`: header breathing, Activities row and gap, card wash, Jump Back In cover and gaps, Recently Added covers and tile gap, Rediscover card, backdrop, padding, shelf gap and score badge.
- `Library`: segment chips (height, corner, padding, gap, ✕ glyph), the grid (gutters, top padding, cover → title), bottom group padding, fast-scroller top inset, sort-row press bleed, song row (padding, thumb, gap), meta pill, empty state. Search reuses the chip, grid, row, pill and empty-state values.
- `Search`: filter-row padding, results bottom reserve, section header top / bottom.
- `NowPlaying`: ⌄ glyph, tab gap and selected stretch, transport gap, time-label widths and gap, one-row hero gap and artist share, rating label crossover.
- `Detail`: header back → title gap, album stamp (size, end inset, title gap), playlist ⋮ reserve and gap, the scrapbook (piece gap, cover, emblem and its overhang, overlap, sticky tuck and width cap, ticket stub and notch, dot paper, pager parallax and lean), the artist page (portrait, pinwheel band, mark scale / rest angle / turn per px, year column, collapsed count, bottom padding).

## Changing a token so both apps follow

Example: the album page's hero cover goes from 74 % of the width to 70 %.

1. Edit `shared/src/commonMain/kotlin/com/gpo/yoin/shared/tokens/YoinTokens.kt`:

   ```kotlin
   object Detail {
       const val ALBUM_COVER_WIDTH_FRACTION = 0.70
   ```

2. Android reads it in `AlbumDetailScreen` (`maxW * YoinTokens.Detail.ALBUM_COVER_WIDTH_FRACTION.toFloat()`). iOS reads it in `AlbumDetailView` (`Tokens.detail.ALBUM_COVER_WIDTH_FRACTION.cg * w`). Neither needs a code change.
3. `YoinTokensTest` pins the values Android had when it adopted the tokens. A deliberate change updates the expected value there in the same commit.
4. Build both apps (below). The iOS build regenerates the framework by itself.

Values are Doubles so iOS gets the exact decimal. Android converts them with `.dp` / `.toFloat()`, the same Float it had as a literal. In Swift they're `Tokens.<group>.<NAME>` (a `Double`; `.cg` gives a `CGFloat`).

To add a token, put it in the right `YoinTokens` group with a one-line comment. Then replace the literal on both sides. If Android adopts it, add the old literal to `YoinTokensTest`.

## Building the framework

```sh
# All: Debug + Release XCFrameworks (shared/build/XCFrameworks/{debug,release}/YoinShared.xcframework)
./gradlew :shared:assembleYoinSharedXCFramework

# What the iOS project links (Debug)
./gradlew :shared:assembleYoinSharedDebugXCFramework

# Shared tests on the JVM (Android host) and the iOS simulator
./gradlew :shared:allTests
```

JDK: Android Studio's bundled JBR (`JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`). The first build downloads the Kotlin/Native toolchain into `~/.konan`.

In Xcode (`iosApp/project.yml`, regenerate with `cd iosApp && xcodegen generate`):

- The `Yoin` scheme's **build pre-action** runs `./gradlew :shared:assembleYoinSharedDebugXCFramework` before Xcode plans the build. Gradle is up to date, and takes a few seconds, when nothing in `:shared` changed. Its output goes to `iosApp/build/yoin-shared-gradle.log`.
- The `Yoin` target's **"Check YoinShared (Kotlin) is current"** build phase fails the build if the framework is missing, or older than a file in `shared/src` or `shared/build.gradle.kts`. Scheme pre-actions can't fail a build themselves, so this catches a failed Gradle run.
- `YOIN_SKIP_SHARED_BUILD=1` skips both and links the framework as it is. Use it for repeated iOS-only builds:

  ```sh
  YOIN_SKIP_SHARED_BUILD=1 xcodebuild -project Yoin.xcodeproj -scheme Yoin ... build
  ```

- The framework is static (`isStatic = true`). The app links it and nothing is embedded. `YoinTests` gets its headers only (`link: false`) and resolves the symbols through the app.

If Swift reports a Kotlin type or member missing right after you change `:shared`, delete `iosApp/build/dd/ModuleCache.noindex` (a stale clang module) and build again.

### Swift names

Kotlin/Native exports to Objective-C, so a few names change in Swift:

- `object`s and companions are `.shared` / `.companion`: `YoinTokens.Bar.shared.SPLIT_WIDTH`, `Palette.companion.generate(…)`, `Hct.companion.fromInt(argb:)`.
- Top-level functions live on `<File>Kt`: `NowPlayingBudgetKt.resolveStageColumnBudget(…)`, `AlbumSpectrumKt.computeAlbumSpectrum(pixels:n:)`.
- Enum entries are lower camel case: `LyricFrame.oneline`, `ColorSpecSpecVersion.spec2025`.
- `IntArray` / `FloatArray` are `KotlinIntArray` / `KotlinFloatArray` (`get(index:)`, `set(index:value:)`). `Int` is `Int32`, and ARGB colours come back signed (`Int(UInt32(bitPattern:))`).
- Default arguments are not exported, so Swift passes every argument.
- `DynamicScheme`'s `Int` role getters export as `primary_` and so on, because they clash with `MaterialDynamicColors`. Use `ExpressiveRoles` instead.

## Backup, import and Memories transfer from Swift (`YoinBackup`)

`YoinBackup(domain:replica:links:deviceId:deviceName:appVersionName:appVersionCode:)` takes the same `SyncDomain` CloudSyncKit uses, `CloudSyncKit.store` (or nil before sync ever ran) and a `YoinTrackAlbumLinkStore` (Swift implements it: `links` / `link` / `putLink`). Every async call is `async throws`; `readBackup` is synchronous and never throws. Use it while no sync runs, and call `CloudSyncKit.flush()` after an import or transfer that touched the replica.

- `exportBackup(nowMs:syncLocation:language:zoneId:knownAlbums:knownTracks:)` → `YoinBackupExport` (`fileName`, `manifest`, `manifestJson`, `snapshotJson`, `readable` path/text list, `readFailures`). `syncLocation` is where Cloud sync keeps the data: nil (sync off) → manifest source `this-device`; `SyncLocations.GOOGLE_DRIVE` / `SyncLocations.ICLOUD` (or `BackupFormat.LOCATION_GOOGLE_DRIVE` / `LOCATION_ICLOUD`) → `google-drive` / `icloud`; anything else throws. Swift writes the zip: `manifest.json`, `data/snapshot.json`, then the readable files, UTF-8. `zoneId` is `TimeZone.current.identifier` (an abbreviation NSTimeZone knows also works); `language` "zh…" gives the Chinese labels.
- `readBackup(manifestJson:snapshotJson:readableFiles:)` → `YoinReadBackupResult(backup:error:)` (pass the two entries' bytes, nil when missing). Exactly one is set; `error` is a flat `YoinBackupError` (`kind`: `notABackup`, `tooNew` with `formatVersion`, `checksumMismatch`, `corrupt`, `other`; `message`).
- `planImport(backup:nowMs:)` → `YoinImportPlan` (`accounts`: `YoinImportAccount` with the default `target` `YoinImportChoice(scope, kind, profileId)`, `sameAccount`, `options`, `cloudOnlyPossible`, `wasPurged`; `globalCounts`). `applyImport(plan:choices:importGlobal:nowMs:)` → `YoinImportResult`; importing the same file again changes nothing.
- `transferSources()` (this device's profiles, then cloud-only accounts), `transferSourcesOf(backup:)`, `transferAccountOf(profileId:)`, `memoryItems(source:backup:)`.
- Run every call inside `CloudSync.withBackup { facade in … }`: it takes the sync lock (`CloudSyncCore.beginExclusive()` returns this device's sync id, `endExclusive()` releases it and writes the store), builds the facade over `SyncDomainBridge`, `CloudSyncKit.store` and `TrackAlbumLinkStore.shared`, and flushes the kit afterwards.
- `planTransfer(source:items:target:)` → `YoinTransferPlan`: `tracksToMatch` / `albumsToMatch` (what the source knows: title, artist, album, duration) for Swift to search with the shared `TrackMatcher`, `reusedTracks` / `reusedAlbums` (earlier matches and picks from the ledger), `previouslyCopied`, `toCopy`, `directSongs` (songs whose ids carry over and still have something to copy). Album searches use `TrackMatcher.albumQuery(name:artist:)` and `sameVersion(a:b:)` (Android MemoryTransfer.matchAlbum). `applyTransfer(plan:tracks:albums:nowMs:)` copies with the picks (`TrackPick` / `AlbumPick`: Matched or Picked with a target are copied, anything else retried next run) and records the run in the replica's meta (the keys Android keeps in `sync_meta`).

## Library search from Swift (`YoinMemorySearch`)

`YoinMemorySearch(ledger:)` takes a `YoinMemorySearchLedger` (Swift implements `entries() throws -> [YoinLedgerEntry]` and `aiTitles() throws -> [YoinAiTitle]`: every counted account's ledger, as `Data/Memories/MemoryLedger.swift` builds it). Types are flat (no defaults, no sealed types, no ranges); offsets are UTF-16 (`String.utf16` / `NSString`), a highlight is `[start, end)` and `-1 / -1` for none; scores are `KotlinFloat?`.

- `search(query:activeProfileId:) throws` → `YoinMemorySearchResult` (`memories`: `YoinMemoryHit` with `album`, `field`, `entryId`, `fragment`, `tier` / `strength`, `title`, `albumScore`, `lastAt`, `isOtherAccount`, `mark`; `notes`: `YoinNoteHit` with `profileId`, `provider` / `songId`, `trackTitle`, `album`, `positionMs`, `fragment`, …). A blank query reads nothing.
- `attachments(albums:activeProfileId:) throws` → `[YoinMemoryAttachment]` for the service's album rows (`YoinSearchAlbum`), in their order: only the exact album in the account in use.

Everything that doesn't read the ledger is on the companion, so Swift calls it without a ledger: `YoinMemorySearch.companion.filters(…)`.

- `YoinMemorySearch.companion.page(query:songs:albums:artists:playlists:result:attachments:activeProfileId:columns:maxRows:maxWide:)` → `YoinSearchPage`: `sections` (best first; each `tiles` for its chip, `rows` for All, `folded`, `more`), `best` (`YoinSearchBestMatch` kind + index), `memories` (the Memories chip). A tile's `index` points into the rows passed in (`-1` for a note or memory standing alone). Pass `allMaxRows` / `allMaxWide` for All.
- `companion.filters(canSearchPlaylists:canReadPlaylists:savedLibrary:)`, `companion.shownRows(filter:section:available:)` (All keeps `allRowsPerType` = 40 a type).
- `companion.noteTarget(note:returnedSongProvider:returnedSongId:activeProfileId:)` → `YoinNoteOpenTarget`: `playSong` (play it, then seek to `seekToMs` once `companion.noteSeekReady(currentRawId:durationMs:targetRawId:)`, giving up after `noteSeekTimeoutMs`), `openAlbum` (the album's scrapbook at the song, in `profileId`'s account; switch first when `switchAccount`), or `none`.
- `companion.searchEntries(entries:aiTitles:query:activeProfileId:)` and `companion.attachmentsOf(albums:entries:aiTitles:activeProfileId:)`: `search` and `attachments` over a ledger you pass in. **iOS should snapshot the ledger once per query** (`entries()` + `aiTitles()`) and call these two, rather than `search` then `attachments`, which each read the ledger.
- Constants and text helpers on the companion: `allMaxRows`, `allMaxWide`, `allRowsPerType`, `fragmentMaxChars`, `noteSeekTimeoutMs`; `fold(text:)`, `match(query:text:)`, `fragment(query:text:maxChars:)`, `plainWords(text:)`, `albumKey(profileId:provider:albumId:)`.

## Album About from Swift (`YoinAlbumAbout`)

`YoinAlbumAbout.shared` is stateless. Types are flat data classes (lists, strings, `String?`, `KotlinInt?` for `totalDurationSec`) and one enum, `AlbumCreditKind` (`.label`, `.producer`, `.mastering`, `.mixing`, `.recorded`, `.source`, `.firstreleased`). Dates are ISO strings at the service's precision ("2014-05-20", "2014-05", "2014"). Every answer is pinned by `shared/src/commonTest/resources/golden/album-about.json`.

- Prompts: `searchQueries(grounding:count:)`, `searchLead(builtInSearch:searchedPages:)`, `aboutPrompt(grounding:targetLanguage:searchLead:)` (credits-only when `grounding.serviceNotes` is set: `isCreditsOnly(grounding:)`), `askPrompt(grounding:question:targetLanguage:searchLead:)`. Append your search results after the prompt as Android does (`"\n\nSearch results:\n" + block`, only when Yoin searched and found pages).
- Replies: `storedReply(rawText:hasServiceNotes:)` → `AlbumAboutReply(credits, blurb, answered, isEmpty)`. `answered == false` is a failure: store nothing, ask again next visit. Answered with `isEmpty` is still stored (no re-asking on every visit); a Regenerate that comes back `isEmpty` keeps the old rows ("nothing new"). The Ask reply is the song Ask's `[TITLE]` / `[ANSWER]` format.
- Rows: kinds `KIND_CANONICAL` / `KIND_ASK`, keys `CANON_CREDITS` / `CANON_BLURB`; the credits row holds `encodeCredits(credits:)` (same JSON as Android, synced as is), the blurb row the paragraph (empty when the AI wrote N/A). Ask rows are keyed by `questionKey(question:)`. `content(rows:)` → `AlbumAboutContent` for the page.
- Credits line: `creditItems(sourceLabels:releaseDate:originalReleaseDate:ai:)`, then `creditsLine(items:labels:dateText:)` → `AlbumCreditsLine?` (nil = no line). `labels` is `AlbumCreditLabels` from Localizable (厂牌 / Label …, the name separator "、" / ", ", the time-and-place format "%1$s，%2$s" / "%1$s, %2$s"); `dateText` is the item's `date` in the app language's long date (DateFormatter `yMMMMd` / `yMMMM`, year alone as the year). Render `text` as it is (no-break spaces and U+2060 word joiners; Core Text breaks it as Android does) or lay out `segments` yourself (break only between them). `rows` feed the sheet's receipt.
- Apple Music: `editorialNotes(standard:short:)` with the album's `attributes.editorialNotes.standard` / `.short` (the catalog album's when the library album has none). The notes are the paragraph (attributed to Apple Music, never stored or synced); the AI writes the credits only.
- AI copy: `aiCopy(template:name:)` / `tightenCjkSpaces(text:)` for "Ask %1$s", "%1$s is writing…" (问千问, 问 Gemini).

iOS (2026-11-26): `AlbumAboutStore` keeps the rows per AI language (as `SongAboutStore` does) in `Yoin/album_about.json`; `AlbumAboutLoader` runs the call through the chosen AI service (`GeminiClient.albumAbout` / `askAlbum`, Yoin's own search when the service has one); `SyncDomainBridge` implements `albumAbouts` / `albumAbout` / `putAlbumAbout` / `albumAboutEntryCount` for the shared `AlbumAboutSyncAdapter` (kind `album_about`, registered in `SharedSyncAdapters`). `AlbumAboutTests` runs the golden creditsLine, prompts, replies, content and aiCopy vectors through the iOS side (Localizable, DateFormatter, the Album → grounding mapping, the Gemini call over a stub, the store), reading the golden file from `shared/src/commonTest/resources/golden/`.

## Share cards from Swift (`YoinShareCards`)

`YoinShareCards.shared` is stateless; every function takes all its arguments. Models are flat data classes (`ShareSong`, `ShareLyricLine`, `ShareLyrics`, `ShareCredit`, `ShareAbout`, `ShareNote`, `ShareCardContent`) and enums (`ShareTab`, `ShareLayout`, `ShareRole`, `ShareTone`, `ShareFormat`, `ShareInkSource`). Lengths are UTF-16; fractions are `Float`. Every answer is pinned by `shared/src/commonTest/resources/golden/share-cards.json`.

- Tabs and layouts: build a `ShareCardContent` when the sheet opens (freeze it: the song can change under the sheet). `tabs(content:)` (none: don't open), `layouts(content:tab:)` (版式 order), `resolveLayout(content:tab:remembered:)`. Remember each tab's last layout per device, outside sync: store `layout.storeValue` under `tab.storeKey` (UserDefaults), read it back with `layoutForStoreValue(tab:value:)`. The tone is a slot 0–3 kept across tabs: `tone(role: tab.role, slot:)`.
- Lyrics: `currentLine(lyrics:positionMs:showTranslation:)` when the sheet opens without a pick; `passageStarts(selected:lineCount:)` for a pick. `poster(pick:)` (lead + up to three more, `translation` as one line, `leadSizeSp`, plus `posterStoryLeadBoostSp` in Story, `leadTime`), `cardLineSizeSp(lines:)` (Classic), `playerExcerpt(pick:durationMs:)` (nil: no bar), `verticalLyricsPlan(pick:)` + `verticalGist(pick:)` (the 「歌词大意：」 footnote, joined by `verticalGistSeparator`), `verticalTextColumns(text:sizeSp:columnHeightDp:)` (nil: turn it sideways), `verticalText(text:)` (vertical punctuation). `isCjk(text:)` is the Vertical gate.
- About: `aboutPick(entries:sources:labels:focusKind:focusKey:)` from `SongAboutStore`'s rows, the lyrics' headline credits, the server's composer and year, and `ShareAboutLabels` from Localizable; focus is the long-pressed row. Quote shows `quoteCredits`, Liner `linerCredits`. Sign only when `about.aiContent`, written with `aiSign(template:name:)` (Localizable "由 %1$s 整理" / "By %1$s": 由千问整理, 由 Gemini 整理).
- Note: `notePick(notes:noteId:positionMs:)`, `timelineFraction(positionMs:durationMs:)` (nil: no Timeline), `noteLines(note:)` (the same reading as `YoinNoteReader.read`, mentions as chips), `time(ms:)` for every time chip.
- Colours: `toneColors(tone:role:)` → `ShareToneColors` (card / content / backdrop as `ShareInk`: a scheme role, or `White`; re-tone when `tone` is set with `retone(argb:tone:)`). Secondary text is content at `secondaryAlpha`.
- Story: frame = 360 × 640 minus `storyMarginHorizontalDp` / `storyMarginVerticalDp`; lay the content out at frame width / zoom, `storyZoom(maxZoom: storyMaxZoom(layout:), fits:)` with `storyFits(heightPx:zoom:frameHeightPx:)`. A poster layout (`layout.storyIsPoster`) fills the frame's height and never grows.
- Export: Card renders at `cardDesignWidthDp` wide, Story at 360 × 640; `exportSize(format:laidOutWidth:laidOutHeight:)` is the PNG's pixel size (opaque, square-cornered). `fileName(songTitle:nowMs:)` names the file; `caption(title:artist:)` is the text shared with it (「歌名 · 歌手」, every tab).

iOS (2026-11-26): `NowPlaying/Share/` draws the sheet and the ten layouts. `ShareContentBuilder` maps Now Playing's lyric lines and shown translations, `SongAboutStore` rows, `SongCredits`, `SpineSourceFacts` and `SongNote`s in; `ShareLayoutStore` keeps `layout.storeValue` under `yoin_share_layouts.<tab.storeKey>` in UserDefaults; `ShareTone.colors` resolves `ShareInk` on the cover's `CoverScheme` (re-toned through `retone`); `StoryZoomLayout` runs `storyZoom` / `storyFits` inside one layout pass (so `ImageRenderer` draws a settled Story); `ShareExport` renders the PNG (fixed type, `scale` = export width / 360, the Card's height rounded to whole export pixels, `isOpaque`), saves through `PHPhotoLibrary` add-only and shares through `UIActivityViewController`. The About sign uses `YoinAlbumAbout.aiCopy` (the rule `YoinShareCards.aiSign` applies in PR #350). `ShareCardsTests` runs the golden labels, about, note, passage, layout-store, content, tone, retone, storyZoom, fileName (and, once present, aiSign) vectors through these adapters, checks every layout × Card / Story exports opaque to every edge pixel at the shared size, and renders snapshots (`SHARE_SNAPSHOT_DIR`). DEBUG hooks: `-shareDemo lyrics|about|note`, `-shareLayout <storeValue>`, `-shareFormat story`, `-shareTone 0…3`, `-shareTranslation YES`, `-shareTour YES`.

## What is still done twice

Only numbers and pure functions are shared. Everything that is UI is written once per platform:

- **Layout and structure.** Compose screens and SwiftUI views are separate code: Home, Library, Now Playing, the detail pages, the bars, Search, the scrapbook. They read the same tokens and call the same algorithms, but the view trees, modifiers, animations and gestures are per platform.
- **Motion.** Android takes its spatial and effects springs from M3's `MotionScheme`; `YoinTokens.Motion` mirrors them. iOS still uses its own `Yoin.spatial` / `spatialFast` / `effects` (SwiftUI response + damping). To align them, convert with `response = 2π / √stiffness`.
- **Image decoding and scaling.** Android decodes with Coil and resizes with `Bitmap.createScaledBitmap`. iOS renders with `ImageRenderer` and resizes with `Palette.scaleDownPixels`, which is pixel-exact against Skia for the 200 px decode both apps use.
- **Glass.** It is iOS-only. Only the preset values are shared.
- **Fonts and shapes.** Each platform loads Google Sans Flex itself (Compose `FontVariation`, CoreText variation axes), using the shared axis values. Continuous corners, MaterialShapes and the Yoin mark paths are drawn per platform.
- **Remaining literals.** Values not yet in `YoinTokens` stay as literals on each side: Home card insides (text gaps, small/wide card padding, memory marks), one-off text sizes and line heights, the scrapbook's piece-local insets and ticket widths, the album header paddings, the iOS-only glass and bar sizes. Move them over when you next touch them. `fidelity/*.md` maps them to Android.

## Release builds and motion (2026-11-03)

- Xcode links `shared/build/XCFrameworks/current/YoinShared.xcframework`, a symlink the scheme pre-action points at `debug` or `release` to match `$CONFIGURATION` (Release / Archive run `:shared:assembleYoinSharedReleaseXCFramework`). Verified: Debug (simulator), Release (generic simulator) and Release (generic iOS device, unsigned) all build. The framework has arm64 slices only, so simulator builds exclude x86_64 (`EXCLUDED_ARCHS[sdk=iphonesimulator*]`).
- Motion: Glass Lab › Motion switches `Yoin.spatial / spatialFast / effects` between the current iOS springs (default) and Android's `YoinTokens.Motion` springs (expressiveDefaultSpatial, expressiveFastSpatial, expressiveDefaultEffects; damping ratio → SwiftUI damping = ratio × 2√stiffness, mass 1).
- Decision (51, 2026-11-03): iOS deliberately does **not** use `YoinTokens.Motion`. It keeps its own platform springs (`Yoin.spatial` 0.42 s / 0.78, `spatialFast` 0.3 s / 0.72, `effects` 0.35 s / 1.0). The Glass Lab Motion toggle stays (default "Current iOS") for future comparison only.
