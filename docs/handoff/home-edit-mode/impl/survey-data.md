# P0-1 and P0-9 data groundwork: design report (read-only, HEAD `93b689bb`)

Nothing in the repo was edited and nothing was built. All paths are under `app/src/main/java/com/gpo/yoin/` unless marked `test/`. DB stays at v28: only new `@Query` methods are added, no entity changes.

## 0. Where the current code contradicts the spec (read first)

| # | Finding | Evidence | Consequence |
|---|---|---|---|
| F1 | **Rediscover would be almost always empty.** The history aggregates are `ORDER BY lastPlayedAt DESC LIMIT :limit`, and the builder passes `scanLimit` = 48 for `limit` = 48. So the history fields only exist for the 48 most recently played albums. An album nobody has played for 90+ days is almost never in that set. | `PlayHistoryDao.kt:43-55`, `AlbumMemoryCandidateBuilder.kt:39-40` | Add a by-id lookup, `PlayHistoryDao.getAlbumAggregatesFor(...)`, for scanned seeds that have no aggregate row (§2.1). Spec §5 ("只取 getAlbumAggregates 的 MIN/MAX") is not enough on its own. |
| F2 | Seeds come from album ratings first (reviewed first, then rating desc), then notes, then the *recent* aggregates, then *recent* events, and only the first 48 are built. An album that only has a track-average score is seeded only if it was played or visited recently. | `Builder.kt:45-81, 88` | P0: the album-rating path is fully covered while a profile has ≤48 album ratings plus notes. The track-average path rarely reaches Rediscover. Accept this for P0 (the placeholder copy already says "Rate an album 8 or higher") and document it. A Rediscover-only seed pass is P1, because mixing it in would change the Memory pool. |
| F3 | Mock default-parameter trap. `coEvery { repository.getAlbumMemoryCandidates(any()) }` only matches `(any(), includeIneligible = false)`. | `test/.../HomeViewModelTest.kt:198,328,347,364,387,416,438,459,481,501,534` and verify `:356` | Change all 11 stubs to `(any(), any())` and the verify to `(48, true)`. `MemoriesDeckCoordinatorTest` keeps working: `limit = 48` with default `false` is exactly what the coordinator calls. |
| F4 | `candidate.playCount` is read by the deck. | `ui/memories/MemoriesDeckCoordinator.kt:390` | Don't fill `playCount` from the new lookup. Add a separate `playCountFromHistory`. |
| F5 | Turning on the new "append disabled" rule for all sections would hide Activities/JBI/RA whenever per-entry lenient decode drops a corrupt entry. All three have existed since the editor shipped (`3f3dc83e`), so every saved layout already lists them. | git history of `HomeSection.kt` | Keep `appendEnabled = true` on the three legacy sections. Only new sections, starting with Rediscover, use `false`. |
| F6 | `memory_teaser` and `memories` are retired ids and are expected to drop. | `test/.../HomeLayoutTest.kt:37-58` | Keep them in a retired-id set and never retain them. All other unknown ids are retained. |
| F7 | The `when (sectionState.section)` in the feed must be exhaustive (Kotlin 2.3.20). | `ui/home/HomeEditorialContent.kt:409-504` | Whichever commit adds `HomeSection.Rediscover` also adds its branch, at first a `-> Unit` stub. |
| F8 | The old editor writes even when nothing changed (`settleDrag → commit()`), and `HomeLayout(draft.toList())` drops retained ids. | `ui/home/HomeLayoutEditor.kt:95, 97-111` | Until Q7 deletes the editor, the VM carries `retained` over and the store skips identical writes (§1.3). |
| F9 | The removal flow emits the *existing* latest row as soon as it subscribes. For a user coming back after more than 90 days, that row's album can legitimately be on the Rediscover shelf, and it would be removed instantly. | Room Flow semantics | Only act on rows with `playedAt ≥ observedSince`. |
| F10 | There is no `ProfileManagerTest`. The constructors are in `ProfileManagerPersistenceTest.kt:39,93`, `ProfileManagerMigrationTest.kt:43,79,111,138,168` (two of them positional) and `SpotifyReconnectFlowTest.kt:30`. | grep | Add the cleanup as a defaulted *last* constructor parameter, so none of those tests change. |
| F11 | `PlayHistoryDao.getRecentHistory(profileId, provider, limit)` (`:10-16`) is the same SQL at `limit = 1`. | | `observeMostRecent` is a thin single-row twin. Keep it for clarity, as the spec says. |

---

## 1. P0-1: layout store hardening

### 1.1 `data/home/HomeLayoutStore.kt`

Current code:
```kotlin
// :42-58
suspend fun setLayout(profileId: String, sections: List<HomeSectionPref>) {
    try { dao.upsert(HomeLayoutPreference(profileId = profileId,
            sectionsJson = json.encodeToString(SectionsDto.serializer(), SectionsDto(sections)),
            updatedAt = clock()))
    } catch (cancellation: CancellationException) { throw cancellation } catch (_: Exception) { }
}
// :60-64
private fun decode(raw: String): List<HomeSectionPref>? =
    runCatching { json.decodeFromString(SectionsDto.serializer(), raw).sections }.getOrNull()
@Serializable private data class SectionsDto(val sections: List<HomeSectionPref>)
```

Proposed diff. `json` at `:33-36` and `layoutFlow` at `:39-40` stay as they are.
```kotlin
private val writeMutex = Mutex() // FIFO: move→undo writes land in call order; the equality check sees the last write

suspend fun setLayout(profileId: String, sections: List<HomeSectionPref>) = bestEffort {
    writeMutex.withLock {
        val stored = dao.getForProfile(profileId).first()?.sectionsJson?.let(::decode)
        if (stored == sections) return@withLock          // no real change → no write
        dao.upsert(HomeLayoutPreference(profileId, encode(sections), clock()))
    }
}

/** Back to "never customized" (Q6a); also the profile-delete hook. */
suspend fun clearLayout(profileId: String) = bestEffort { writeMutex.withLock { dao.delete(profileId) } }

internal fun encode(sections: List<HomeSectionPref>): String = json.encodeToString(
    SectionsDto.serializer(),
    SectionsDto(SCHEMA_VERSION, sections.map { json.encodeToJsonElement(HomeSectionPref.serializer(), it) }),
)

/** Unreadable document → null ("never set"); a bad entry drops only itself. */
internal fun decode(raw: String): List<HomeSectionPref>? {
    val dto = runCatching { json.decodeFromString(SectionsDto.serializer(), raw) }.getOrNull() ?: return null
    return dto.sections.mapNotNull { runCatching { json.decodeFromJsonElement(HomeSectionPref.serializer(), it) }.getOrNull() }
}

@Serializable private data class SectionsDto(
    val version: Int = SCHEMA_VERSION,   // absent in pre-P0 rows → 1; older builds ignore it (ignoreUnknownKeys)
    val sections: List<JsonElement>,
)
// bestEffort = the existing try / rethrow-CancellationException / swallow pattern; SCHEMA_VERSION = 1
```
- If every entry is bad, `decode` returns an empty list. `reconcile` (`HomeSection.kt:90`) turns that into `Default`, which is the "never customized" behavior.
- Also update the KDoc on `HomeLayoutPreference.kt:11-13`. It says "Unknown ids are reconciled away", which is no longer true.

### 1.2 `ui/home/HomeSection.kt`

Current code: the enum at `:20-45`, `toPrefs` at `:70-71`, `Default` at `:75-77`, and `reconcile` at `:89-102`, which appends missing sections at `defaultEnabled`.

```kotlin
enum class HomeSection(id, title, supportingText, defaultEnabled,
    /** Visibility when appended to an already-customized layout (Q6a). Legacy = true (F5); new = false → tray + "New". */
    val appendEnabled: Boolean = false,
) {
    Activities(..., appendEnabled = true), JumpBackIn(..., appendEnabled = true), RecentlyAdded(..., appendEnabled = true),
    Rediscover(id = "rediscover", title = "Rediscover",
        supportingText = "Rated high, not played in Yoin for a while",   // proto.js:140
        defaultEnabled = true, appendEnabled = false),
}

data class HomeLayout(
    val sections: List<HomeSectionState>,
    val retained: List<HomeSectionPref> = emptyList(),      // ids this build doesn't know, kept verbatim
    val newSections: Set<HomeSection> = emptySet(),          // appended to a customized layout on this read
) {
    fun toPrefs() = sections.map { HomeSectionPref(it.section.id, it.enabled) } + retained
    val isDefault: Boolean get() = sections == Default.sections          // ignores retained/newSections
    fun reset(): HomeLayout = Default.copy(retained = retained)
    fun withEnabled(id: String, enabled: Boolean): HomeLayout            // returns `this` when unchanged
    /** toEnabledIndex indexes the ENABLED order; disabled entries keep their absolute slot
     *  so "Show" returns home (proto.js withEnabledOrder :854-858). Returns `this` on a no-op. */
    fun moved(id: String, toEnabledIndex: Int): HomeLayout

    companion object {
        private val RetiredSectionIds = setOf("memory_teaser", "memories")
        fun reconcile(prefs: List<HomeSectionPref>?): HomeLayout {
            if (prefs.isNullOrEmpty()) return Default
            val seen = LinkedHashSet<HomeSection>(); val retainedIds = HashSet<String>()
            val ordered = mutableListOf<HomeSectionState>(); val retained = mutableListOf<HomeSectionPref>()
            for (pref in prefs) {
                val section = HomeSection.fromId(pref.id)
                when {
                    section != null -> if (seen.add(section)) ordered += HomeSectionState(section, pref.enabled)
                    pref.id.isBlank() || pref.id in RetiredSectionIds -> Unit
                    retainedIds.add(pref.id) -> retained += pref                    // first occurrence wins
                }
            }
            val appended = HomeSection.entries.filter { seen.add(it) }
            ordered += appended.map { HomeSectionState(it, it.appendEnabled) }
            return HomeLayout(ordered, retained, appended.filterNot { it.appendEnabled }.toSet())
        }
    }
}
```
- Compare layouts by `sections` or `toPrefs()`, never by data-class equality. `newSections` differs between the draft and the Room echo.
- Update the KDoc at `:5-19`: "newly shipped → appended at `appendEnabled`".

### 1.3 Writing only on a real change: `ui/home/HomeViewModel.kt:90-96`

Current code:
```kotlin
fun setHomeLayout(layout: HomeLayout) { val profileId = activeProfileId.value; if (profileId.isNullOrBlank()) return
    viewModelScope.launch { homeLayoutStore.setLayout(profileId, layout.toPrefs()) } }
```
Proposed:
```kotlin
fun setHomeLayout(layout: HomeLayout) {
    val profileId = activeProfileId.value?.takeIf { it.isNotBlank() } ?: return
    val next = if (layout.retained.isEmpty()) layout.copy(retained = homeLayout.value.retained) else layout  // F8
    viewModelScope.launch {
        if (next.isDefault && next.retained.isEmpty()) homeLayoutStore.clearLayout(profileId)   // == defaults → "never customized"
        else homeLayoutStore.setLayout(profileId, next.toPrefs())                                 // store skips an identical row
    }
}
```
How the pieces fit:
- The new controller's pure operations return `this` on a no-op, so the controller never calls the VM in that case. This mirrors the prototype (`proto.js:1582-1584`, `changed = target !== k`). Entering edit writes nothing.
- The store's equality check catches the old editor's no-op drag.
- **Recommended refinement: clear the row when the layout equals Default.** A user who undoes back to Default, or presses Reset, goes back to "never customized". New sections then follow `defaultEnabled` again, which is what Q6a intends.
- This is a small departure from the spec's "Reset 写一次库". Flag it to the owner if strict adherence is wanted.

### 1.4 `HomeEditHintStore`

New file `ui/home/HomeEditHintStore.kt`, next to `MemoryBubbleSeenStore` (`HomeMemoryBubble.kt:149-180`). It copies the pattern at `ui/nowplaying/NowPlayingPresentation.kt:380-410`.

```kotlin
interface HomeEditHintStore {
    fun editSessionCount(): Int
    fun recordEditSession()
    fun seenSectionIds(): Set<String>
    fun markSectionsSeen(ids: Collection<String>)
    class InMemory : HomeEditHintStore { /* fields */ }
}
class SharedPrefsHomeEditHintStore(context: Context) : HomeEditHintStore {
    // PREFS_NAME = "yoin_ui_hints"; KEY_EDIT_SESSIONS = "home_edit_sessions"; KEY_SECTIONS_SEEN = "home_sections_seen"
    // seenSectionIds(): prefs.getStringSet(KEY, null)?.toSet().orEmpty()  — copy; never mutate the returned set
    // markSectionsSeen: putStringSet(KEY, HashSet(old + ids)), skipped when nothing is new
}
internal const val HomeEditHeaderHintSessions = 2                       // proto.js:47
internal fun showEditHeaderHint(sessionsBefore: Int) = sessionsBefore < HomeEditHeaderHintSessions  // = proto :978-979
```
- Q6b(b) drops the hint tile and the idle nudge, so **do not** add `home_edit_hint_lines`, `home_idle_nudges` or `home_hint_tile_dismissed`.
- Wiring: add `val homeEditHintStore: HomeEditHintStore by lazy { SharedPrefsHomeEditHintStore(context) }` next to `lyricHintStore` (`AppContainer.kt:349-352`), and pass it through `HomeViewModel.Factory` (`HomeViewModel.kt:781-789`).
- The VM API is in §3.2.

### 1.5 Orphan cleanup on profile delete

`ProfileManager.kt:43-55` (constructor). Add a last parameter:
```kotlin
private val onProfileDeleted: suspend (profileId: String) -> Unit = {},
```
`ProfileManager.kt:227-244` (`delete`). Append after the `if (wasActive) {…}` block:
```kotlin
// After the switch, so Home no longer observes the outgoing profile (no Default flash). Never fails the delete.
try { onProfileDeleted(id) } catch (c: CancellationException) { throw c } catch (_: Exception) {}
```
`AppContainer.kt:291-310`: add `onProfileDeleted = { homeLayoutStore.clearLayout(it) }`. `homeLayoutStore` is `by lazy` at `:421` and is resolved at call time, so construction order doesn't matter.
- I chose a callback over injecting `HomeLayoutDao` so that `ProfileManager` stays free of Home/Room coupling and no tests have to change (F10).
- Optional, not recommended for P0: a one-time `DELETE FROM home_layout WHERE profileId NOT IN (SELECT id FROM profiles)` for rows left behind by profiles already deleted.

### 1.6 P0-1 tests

**New `test/.../data/home/HomeLayoutStoreTest.kt`** (uses a fake DAO backed by a `MutableStateFlow<Map>` that counts upserts):
- `should_roundTripPrefs_when_setThenRead`
- `should_writeVersionOne_when_encoding`
- `should_readLegacyRow_when_versionFieldMissing`
- `should_dropOnlyMalformedEntry_when_oneEntryIsBad`
- `should_emitNull_when_documentUnreadable`
- `should_keepUnknownIds_when_decodingAndReencoding`
- `should_ignoreUnknownEntryFields_when_newerBuildAddsConfig`
- `should_skipUpsert_when_prefsEqualStoredRow`
- `should_upsert_when_prefsDiffer`
- `should_deleteRow_when_clearLayout`
- `should_swallowFailure_when_daoUpsertThrows`

**`HomeLayoutTest`: modify three existing tests.**
- `reconcile_keeps_saved_order_and_flags` (`:18-34`): now expects a fourth entry, Rediscover, disabled and in `newSections`.
- `reconcile_drops_removed_ids_and_appends_new_sections_at_defaults` (`:37-58`): retired ids are not retained, legacy sections are appended enabled, Rediscover is appended disabled.
- `toPrefs_round_trips_through_reconcile` (`:72-83`): include Rediscover.

**`HomeLayoutTest`: add.**
- `should_enableRediscover_when_prefsNeverCustomized`
- `should_appendRediscoverDisabledAndMarkNew_when_prefsCustomized`
- `should_notMarkNew_when_sectionAlreadyInPrefs`
- `should_appendLegacySectionEnabled_when_itsEntryWasDropped`
- `should_retainUnknownIdsInOrder_when_reconciling`
- `should_appendRetainedAfterKnown_when_toPrefs`
- `should_dropRetiredIds_when_reconciling`
- `should_keepFirstRetained_when_unknownIdRepeats`
- `should_beDefault_when_sectionsMatchDefaultEvenWithRetained`
- `should_keepRetained_when_reset`
- `should_returnSameInstance_when_movedToCurrentIndex`
- `should_keepDisabledSlots_when_movingEnabledSection`
- `should_clampIndex_when_movedPastEnd`
- `should_returnSameInstance_when_withEnabledUnchanged`
- `should_restoreOriginalSlot_when_hiddenSectionShownAgain`

**`HomeEditHintStoreTest`** (Robolectric 4.16.1 is already a test dependency):
- `should_countSessions_when_recordEditSessionCalled`
- `should_showHeaderHint_when_fewerThanTwoSessions`
- `should_hideHeaderHint_when_twoSessionsRecorded`
- `should_unionSeenIds_when_markSectionsSeen`
- `should_persistAcrossInstances_when_sharedPrefsBacked`

**`ProfileManagerPersistenceTest`:**
- `should_invokeOnProfileDeleted_when_profileDeleted`
- `should_runOnProfileDeletedAfterSwitch_when_deletingActiveProfile`
- `should_completeDelete_when_onProfileDeletedThrows`

**`HomeViewModelTest`:**
- `should_notWriteLayout_when_layoutUnchanged`
- `should_clearLayout_when_layoutEqualsDefault`
- `should_carryRetainedIds_when_layoutArrivesWithout`

---

## 2. P0-9: Rediscover data, built on the current builder

### 2.1 Builder: `data/memory/AlbumMemoryCandidateBuilder.kt`

Current code:
```kotlin
// :38
suspend fun build(limit: Int): List<AlbumMemoryCandidate>
// :86-94
seeds.values.take(scanLimit).map { async { buildGate.withPermit { buildCandidate(it) } } }.awaitAll()
    .filterNotNull().filter(AlbumMemoryCandidate::isMemoryEligible).sortedWith(comparator).take(limit)
```
Proposed:
```kotlin
suspend fun build(limit: Int, includeIneligible: Boolean = false): List<AlbumMemoryCandidate> = coroutineScope {
    // seeds unchanged; inside the playAggregates loop (:60-70) also set
    //   historyPlayCount / historyFirstPlayedAt / historyLastPlayedAt = aggregate.*
    val scanned = seeds.values.take(scanLimit)
    fillHistoryGaps(scanned)                                   // F1
    val sorted = scanned.map { async { buildGate.withPermit { buildCandidate(it) } } }.awaitAll()
        .filterNotNull().sortedWith(albumMemoryCandidateComparator)
    if (includeIneligible) sorted else sorted.memoryEligible(limit)
}

/** Seeds outside getAlbumAggregates' recency window get history by id. Fills history-only fields;
 *  seed.playCount/firstPlayedAt/lastPlayedAt (Memory's inputs) are never touched. Failure leaves them null. */
private suspend fun fillHistoryGaps(scanned: List<AlbumMemorySeed>) {
    val missing = scanned.filter { it.historyLastPlayedAt == null && it.albumId.isNotBlank() }
    if (missing.isEmpty()) return
    val rows = try {
        playHistoryDao.getAlbumAggregatesFor(profileId, provider, missing.map { MediaId.storedRawId(provider, it.albumId) }.distinct())
    } catch (c: CancellationException) { throw c } catch (_: Exception) { return }
    val byRaw = rows.associateBy { it.albumId }
    missing.forEach { seed -> byRaw[MediaId.storedRawId(provider, seed.albumId)]?.let {
        seed.historyPlayCount = it.playCount; seed.historyFirstPlayedAt = it.firstPlayedAt; seed.historyLastPlayedAt = it.lastPlayedAt } }
}
```
- `AlbumMemorySeed` (`:232-242`) gets `historyPlayCount: Int = 0`, `historyFirstPlayedAt: Long? = null` and `historyLastPlayedAt: Long? = null`. `buildCandidate` (`:136-162`) maps these to the new fields.
- `fillHistoryGaps` runs for both flag values, so the eligible subset is equal *including* the new fields. The cost is at most one grouped query, and only when some scanned seed has no aggregate row.
- History `albumId` is the raw id (`recordPlay`, `YoinRepository.kt:2268-2281`). Seeds may hold the legacy form `provider:raw`, hence `storedRawId`.

### 2.2 Candidate model: `data/memory/AlbumMemoryCandidate.kt:3-31`

Append three defaulted fields after `coverArtUrl`. Every caller uses named arguments, so nothing else needs to change.
```kotlin
val firstPlayedFromHistoryAt: Long? = null,   // play_history MIN(playedAt) only; never VISITED events
val lastPlayedFromHistoryAt: Long? = null,    // play_history MAX(playedAt) only
val playCountFromHistory: Int = 0,            // COUNT(*) — "23 plays"; `playCount` stays as-is (F4)
```
Plus a single shared definition of the Memory pool:
```kotlin
fun List<AlbumMemoryCandidate>.memoryEligible(limit: Int) = filter(AlbumMemoryCandidate::isMemoryEligible).take(limit)
```

### 2.3 DAO: `data/local/PlayHistoryDao.kt`

No schema change. The existing index `(profileId, provider, playedAt)` is at `PlayHistory.kt:12-17`.
```kotlin
@Query("SELECT albumId, provider, album AS albumName, artist AS artistName, coverArtId, " +
    "COUNT(*) AS playCount, MIN(playedAt) AS firstPlayedAt, MAX(playedAt) AS lastPlayedAt " +
    "FROM play_history WHERE profileId = :profileId AND provider = :provider AND albumId IN (:albumIds) " +
    "GROUP BY albumId, provider")
suspend fun getAlbumAggregatesFor(profileId: String, provider: String, albumIds: List<String>): List<AlbumPlayHistoryAggregate>

@Query("SELECT * FROM play_history WHERE profileId = :profileId AND provider = :provider ORDER BY playedAt DESC LIMIT 1")
fun observeMostRecent(profileId: String, provider: String): Flow<PlayHistory?>
```
Note: `deleteOlderThan` (`:34-35`) has no callers, so history is never pruned and the 90-day rule is safe.

### 2.4 Repository: `data/repository/YoinRepository.kt`

Read it with `grep -a`.
- `:1419` becomes `getAlbumMemoryCandidates(limit: Int = 48, includeIneligible: Boolean = false)`, and `:1437` becomes `.build(limit, includeIneligible)`. The deck (`MemoriesDeckCoordinator.kt:160`) and `getTopAlbumMemoryCandidate` (`:1440`) are unchanged.
- New, next to `getRecentHistory` (`:2287`):
  ```kotlin
  /** Newest play row for the active scope. NOT folded into observeMemorySignalStamp (:1470-1485), which would rebuild every candidate on each track change. */
  fun observeMostRecentPlay(): Flow<PlayHistory?> =
      combine(activeSource, activeProfileId) { s, p -> s?.id to p }.flatMapLatest { (provider, profileId) ->
          if (provider == null || profileId.isNullOrBlank()) flowOf(null)
          else database.playHistoryDao().observeMostRecent(profileId, provider)
      }.distinctUntilChangedBy { it?.id }
  ```
- Plays are recorded once per new playing track, at start (`PlaybackManager.kt:728-747`), so a card leaves on first play.

### 2.5 Selection: new `data/memory/RediscoverSelection.kt` (pure)

```kotlin
const val REDISCOVER_MIN_SCORE = 8f
const val REDISCOVER_AWAY_MS = 90L * 24 * 60 * 60 * 1000
const val REDISCOVER_COVERAGE_GATE = 0.6f      // same gate as Builder.MEMORY_RATING_COVERAGE_GATE (:250)
const val REDISCOVER_LIMIT = 6                 // Compact LazyRow max; Medium/Wide take 2/3

fun rediscoverScore(c: AlbumMemoryCandidate): Float? =
    c.albumRating?.takeIf { it > 0f } ?: c.averageSongRating?.takeIf { c.ratingCoverage >= REDISCOVER_COVERAGE_GATE }

data class RediscoverPick(val candidate: AlbumMemoryCandidate, val score: Float, val lastPlayedAt: Long)

fun selectRediscover(candidates: List<AlbumMemoryCandidate>, nowMillis: Long,
                     excludeRawAlbumIds: Set<String>, limit: Int = REDISCOVER_LIMIT): List<RediscoverPick> =
    candidates.mapNotNull { c ->
        val last = c.lastPlayedFromHistoryAt ?: return@mapNotNull null          // visited-only never qualifies
        val score = rediscoverScore(c)?.takeIf { it >= REDISCOVER_MIN_SCORE - 1e-4f } ?: return@mapNotNull null
        if (nowMillis - last < REDISCOVER_AWAY_MS) null else RediscoverPick(c, score, last)
    }
        .sortedWith(compareByDescending<RediscoverPick> { it.score }.thenBy { it.lastPlayedAt })
        .distinctBy { MediaId.storedRawId(it.candidate.provider, it.candidate.albumId) }   // raw/legacy duplicate seeds → LazyRow key crash
        .filterNot { MediaId.storedRawId(it.candidate.provider, it.candidate.albumId) in excludeRawAlbumIds }
        .take(limit)
```
- `candidate.albumRating` is already `null` when it is ≤ 0 (`Builder.kt:146`).
- Dedupe set, computed at publish time against what is actually shown (§3.2):
  - the album of the JBI `MemoryFocus` card, via `memoryCardAlbumId` (`HomeViewModel.kt:563-569`);
  - `pill.latest.albumId.rawId` (the bubble shows the same `latest`, `HomeMemoryBubble.kt:662`);
  - the session set of played albums.
- Optional, not in the spec: also exclude JBI `AlbumDetail` compacts, since a random-pool album can coincide.

### 2.6 Why the eligible subset is identical, and the test that pins it

- `sortedWith` is TimSort, which is stable. So `sort(all).filter(e)` equals `sort(filter(e))`, and `take(limit)` is applied the same way.
- The seeds, `scanLimit` (48 for `limit` = 48) and `buildCandidate` outputs are unchanged.
- `pickLatestMemory` (`HomeMemoryPill.kt:154`) breaks ties by input order, which this also preserves.

Builder tests in `test/.../AlbumMemoryCandidateBuilderTest`. All mocks there are strict. Existing tests stub an aggregate for `album-1`, so the gap lookup never fires and they need no changes.
- `should_returnIdenticalEligibleSubset_when_includeIneligible`: five albums, eligible and ineligible mixed, with comparator ties. Assert `build(3, true).memoryEligible(3) == build(3)` plus a golden albumId order.
- `should_includeIneligibleCandidates_when_includeIneligible`: album rating 9.0 only, coverage < 0.6, no review.
- `should_takeHistoryFieldsFromPlayHistoryOnly_when_visitIsNewer`: aggregate 100/300 plus a VISITED event at 9000. Expect `lastPlayedFromHistoryAt` = 300 while `lastPlayedAt` = 9000.
- `should_lookUpHistoryById_when_albumOutsideRecentAggregateWindow`: fields filled, `playCount` stays 0.
- `should_skipHistoryLookup_when_everyScannedSeedHasAggregate`: `coVerify(exactly = 0)`.
- `should_leaveHistoryFieldsNull_when_albumOnlyVisited`
- `should_keepMemoryFieldsUnchanged_when_historyLookupFails`

New `RediscoverSelectionTest`:
- `should_scoreAlbumRating_when_present`
- `should_scoreTrackAverage_when_noAlbumRatingAndCoverageAtLeastSixtyPercent`
- `should_notScore_when_trackAverageCoverageBelowSixtyPercent`
- `should_exclude_when_scoreBelowEight`
- `should_include_when_scoreExactlyEight`
- `should_exclude_when_playedWithinNinetyDays`
- `should_include_when_exactlyNinetyDaysAway`
- `should_exclude_when_onlyVisited`
- `should_orderByScoreThenLongestAway`
- `should_excludeJbiMemoryAndPillAlbums`
- `should_dedupeByRawAlbumId_when_legacyPrefixedSeedDuplicates`
- `should_capAtLimit`

### 2.7 Removal on play, and freezing during edit

```kotlin
private val playedRediscoverRawIds = mutableSetOf<String>()   // session-only; cleared when contentScopeKey changes
private fun observeRediscoverRemovals() = viewModelScope.launch {
    val observedSince = nowMillis()                                   // F9: ignore the pre-existing latest row
    repository.observeMostRecentPlay()
        .filter { it != null && it.playedAt >= observedSince && it.albumId.isNotBlank() }
        .map { MediaId.storedRawId(it!!.provider, it.albumId) }.distinctUntilChanged()
        .collect { raw ->
            playedRediscoverRawIds += raw            // also closes the race where a tick built before the play would re-add the card
            val scopeKey = homeScopeKey(repository.currentProviderId(), activeProfileId.value)
            if (contentScopeKey != scopeKey) return@collect
            val latest = currentContent() ?: return@collect
            if (latest.rediscover.none { it.albumId.rawId == raw }) return@collect
            val next = latest.copy(rediscover = latest.rediscover.filterNot { it.albumId.rawId == raw })
            homeContentCache[scopeKey] = next; emit(next)
        }
}
```
The freeze (spec §2.2.6; shared with P0-5):
```kotlin
private var editing = false
private var frozenState: HomeUiState? = null
fun setEditing(editing: Boolean)                 // false → publish frozenState
private fun emit(s: HomeUiState)                 // editing ? frozenState = s : _uiState.value = s
private fun currentContent(): HomeUiState.Content? = (frozenState ?: _uiState.value) as? HomeUiState.Content
```
- Replace the five `_uiState.value =` writes (`:113, :127, :132, :328, :377`) with `emit`.
- Replace the four `_uiState.value as? Content` reads (`:292, :321, :353, :364`) with `currentContent()`, so splices made while frozen build on each other.
- Cache writes stay where they are.

Caveat for Apple Music QA: removal and the history lookup assume that `track.albumId.rawId` in history uses the same id form as the album-rating key (catalog vs library; see the Apple identity memory). If they differ, a played album never leaves Rediscover. Check this on the device.

---

## 3. HomeUiState, HomeViewModel, and `HomeSection.Rediscover`

### 3.1 `ui/home/HomeUiState.kt`

After `memoryPill` (`:38`):
```kotlin
// Rediscover: rated ≥8, not played in Yoin for 90+ days, best first. Empty = section not rendered.
val rediscover: List<HomeRediscoverItem> = emptyList(),
```
```kotlin
@Immutable data class HomeRediscoverItem(
    val albumId: MediaId,            // tap → onAlbumClick(albumId.toString(), null); LazyRow key "rediscover:$albumId"
    val albumName: String, val artistName: String?, val coverArtUrl: String?,
    val score: Float, val scoreText: String /* "%.1f" Locale.US */, val scoreKind: MemoryScoreKind,
    val lastPlayedAt: Long, val firstPlayedAt: Long?, val playCount: Int,   // history-only; "in Yoin" copy formats them
)
```

### 3.2 `HomeViewModel` changes

**Constructor** (`:46-53`). Add defaulted parameters so existing tests compile; the Factory (`:781-789`) passes the hint store:
```kotlin
private val homeEditHintStore: HomeEditHintStore = HomeEditHintStore.InMemory(),
private val nowMillis: () -> Long = System::currentTimeMillis,
```

**`MemorySignals`** (`:860-863`): add `val pool: List<AlbumMemoryCandidate> = emptyList()`.

**`loadMemorySignals`** (`:541-561`):
```kotlin
val pool = repository.getAlbumMemoryCandidates(limit = MEMORY_CANDIDATE_LIMIT, includeIneligible = true)
val candidates = pool.memoryEligible(MEMORY_CANDIDATE_LIMIT)   // == old build(48)
// pill (:553) + memoryCard (:554-559) unchanged, from `candidates`
return MemorySignals(pill, memoryCard, pool)
```
This is still one build per load or tick, so the "fetch once" test stays true.

**Publish-time selection**, so dedupe always matches the grid and pill that are actually published:
```kotlin
private fun rediscoverFor(signals: MemorySignals?, grid: List<HomeWidgetCard>, pill: HomeMemoryPill?,
                          fallback: List<HomeRediscoverItem>): List<HomeRediscoverItem> {
    signals ?: return fallback
    val exclude = buildSet {
        grid.firstOrNull { it.target is HomeWidgetTarget.MemoryFocus }?.let(::memoryCardAlbumId)?.let { add(it.rawId) }
        pill?.latest?.albumId?.rawId?.let(::add); addAll(playedRediscoverRawIds) }
    return selectRediscover(signals.pool, nowMillis(), exclude).mapNotNull { it.toItem() }   // toItem guards blank raw ids
}
```
**Call sites:**
- `loadHomeContent` (`:147-174`), `loadCachedSpotifyHomeContent` (`:209-231`) and `loadSpotifyHomeContent` (`:233-253`): set `rediscover = rediscoverFor(signals, grid, pill, cachedRediscover())`. `cachedRediscover()` mirrors `cachedMemoryPill()` at `:141-142`.
- `observeMemorySignals` (`:342-381`): add `val refreshedRediscover = rediscoverFor(signals, nextGrid, refreshedPill, latest.rediscover)`.
- **Extend the no-op return at `:371`** with `&& refreshedRediscover == latest.rediscover`. Otherwise a change that only affects Rediscover is dropped.

**Hints and "New":**
```kotlin
private val seenSectionIds = MutableStateFlow(homeEditHintStore.seenSectionIds())
val unseenNewSections: StateFlow<Set<HomeSection>> = combine(homeLayout, seenSectionIds) { l, seen ->
    l.newSections.filterTo(LinkedHashSet()) { it.id !in seen } }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
/** HomeEditController.enter() calls this once: snapshot this session's hints, then record. Writes no home_layout. */
fun onEditSessionStarted(): HomeEditSessionHints   // (showHeaderHint, newBadges); recordEditSession + markSectionsSeen
```
`unseenNewSections` drives the footer "Edit Home" badge. The controller keeps the `newBadges` snapshot for the tray rows during that session.

**`init`** (`:83-87`): add `observeRediscoverRemovals()`.

### 3.3 Rediscover × `reconcile` × Q6a

| Stored prefs | Rediscover after `reconcile` | Feed | Tray / "New" |
|---|---|---|---|
| `null`, empty, or all entries unreadable (never customized) | `Default`, enabled, **last** (enum order) | Renders only when `rediscover` is non-empty | Not in tray, no badge |
| Customized before P0-9 (no `rediscover` id) | Appended last with `appendEnabled = false`, in `newSections` | Hidden | Tray row badged "New" until the first edit entry marks it seen; footer badge while unseen |
| Contains `rediscover` | Saved position and flag | As saved | No badge |
| Unknown ids from a newer build | Retained, re-emitted after the known entries | — | — |

- Merely entering edit never writes `home_layout`, so an accidental long-press does not make a user "customized".
- Reset or undo back to `Default` clears the row (§1.3), which returns the user to row 1 of the table.
- Default position: last keeps the current three-section `Default` unchanged for existing users. The prototype put it second (`proto.js:144`), but it also puts RA before JBI, so its order is not a spec. Moving it up is an owner call.
- **Recommended commit order:**
  1. P0-1: store, model, VM write guard, hint store, `ProfileManager` hook, **plus** `HomeSection.Rediscover` with a `-> Unit` branch in the feed `when` (F7), so the Q6a tests exercise a real section. Rendering nothing until P0-9 looks the same as "no data".
  2. P0-9a: DAO, candidate fields, builder, repository, selection, with their tests.
  3. P0-9b: UiState, VM, freeze, `RediscoverSection`.

### 3.4 VM tests (P0-9)

- **Update existing stubs:** the 11 `getAlbumMemoryCandidates(any())` stubs become `(any(), any())`, and the verify at `:356` becomes `(48, true)`. Use distinct profile ids in new tests, because `homeContentCache` is static and shared across instances (`:832`).
- **New:**
  - `should_feedPillAndJbiFromEligibleOnly_when_poolHasIneligible`
  - `should_exposeRediscover_when_poolHasStaleHighRatedAlbum`
  - `should_dedupeRediscoverAgainstPillAndJbiMemory`
  - `should_removeRediscoverCard_when_itsAlbumPlays`
  - `should_ignoreExistingLatestPlay_when_subscriptionStarts`
  - `should_notResurrectPlayedCard_when_staleTickLands`
  - `should_queueRediscoverRemoval_when_editing` (turbine)
  - `should_applyQueuedContent_when_editingEnds` (turbine)
  - `should_keepRediscover_when_tickCannotResolveSignals`
  - `should_snapshotNewBadgesAndMarkSeen_when_editSessionStarts`
  - `should_showHeaderHintOnlyFirstTwoSessions_when_editSessionsStart`
