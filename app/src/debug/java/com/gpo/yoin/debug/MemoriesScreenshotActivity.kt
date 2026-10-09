package com.gpo.yoin.debug

import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.gpo.yoin.data.local.ActivityActionType
import com.gpo.yoin.data.local.ActivityEntityType
import com.gpo.yoin.data.local.ActivityEvent
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.CoverRef
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.enableYoinEdgeToEdge
import com.gpo.yoin.ui.component.ExpressivePageBackground
import com.gpo.yoin.ui.home.HomeEditorialContent
import com.gpo.yoin.ui.home.HomeHintVariant
import com.gpo.yoin.ui.home.HomeLayout
import com.gpo.yoin.ui.home.HomeRowPreset
import com.gpo.yoin.ui.home.HomeSection
import com.gpo.yoin.ui.home.HomeMemoryPill
import com.gpo.yoin.ui.home.HomeRediscoverItem
import com.gpo.yoin.ui.home.HomeWidgetCard
import com.gpo.yoin.ui.home.HomeWidgetTarget
import com.gpo.yoin.ui.home.LocalHomeHintVariant
import com.gpo.yoin.ui.home.InMemoryMemoryBubbleSeenStore
import com.gpo.yoin.ui.home.LocalMemoryBubbleIdleMs
import com.gpo.yoin.ui.home.LocalMemoryBubbleSeenStore
import com.gpo.yoin.ui.home.MemoryBubbleIdleMs
import com.gpo.yoin.ui.home.edit.HomePlateVariant
import com.gpo.yoin.ui.home.edit.HomeWiggleMode
import com.gpo.yoin.ui.home.edit.HomeWiggleStyle
import com.gpo.yoin.ui.home.edit.HomeWiggleTarget
import com.gpo.yoin.ui.home.edit.LocalHomePlateVariant
import com.gpo.yoin.ui.home.edit.LocalHomeWiggleStyle
import com.gpo.yoin.ui.home.edit.rememberStandaloneHomeEditController
import com.gpo.yoin.ui.home.rediscoverScoreText
import com.gpo.yoin.ui.memories.MemoryEntityType
import com.gpo.yoin.ui.memories.MemoryScoreKind
import kotlinx.coroutines.delay
import androidx.compose.runtime.CompositionLocalProvider
import com.gpo.yoin.ui.experience.LocalMotionProfile
import com.gpo.yoin.ui.experience.LocalYoinWindowInfo
import com.gpo.yoin.ui.experience.MotionProfile
import com.gpo.yoin.ui.experience.rememberYoinWindowInfo
import com.gpo.yoin.ui.theme.YoinTheme
import java.io.File

/**
 * Debug-only launcher for visual QA of the whole redesigned home feed —
 * Activities bento, the Jump Back In widget grid, and the compact Recently
 * Added shelf — with fixed fake data. Not exported in release. Stand-in covers
 * are solid-colour bitmaps written to cacheDir on first launch so the backdrop
 * palette tints each shape (and card) the way real album art would. Launch:
 *   adb shell am start -S -n com.gpo.yoin/com.gpo.yoin.debug.MemoriesScreenshotActivity
 *
 * Home hint trial extras (-S so the once-per-process launch reveal replays):
 *   --es variant  Recommended | Sticker | QuietEmpty | HierarchyCap | Baseline
 *   --es pill     unresolved | empty | notes | none | average | album (default)
 *   --ei notes    note count (default 12; 0 hides it; 1234 shows the 999+ cap)
 *   --es grid     full (default: both 1×2 signal cards) | plain (12 1×1, two artless)
 *   --ez recent   false hides Recently Added (Apple Music never has it)
 *   --el pillDelayMs  start unresolved, resolve the pill after this delay
 *   --ez cycle    loop empty → none → average → album every 2.5s
 *   --ei rotate   rotate the fake activities by N, so a different hero leads and
 *                 the unit bento picks a different staggered composition
 *
 * Jump Back In stagger trial (owner 2026-10-04):
 *   --ei shelf    rotate the shelf's covers by N — a different leading cover,
 *                 so the template seed (and layout) changes
 *   --ei signals  how many 1×2 signal cards to keep (default 2)
 *   --ez activities false empties the Activities bento (its empty card leads)
 *
 * Memories speech bubble (owner 2026-10-04):
 *   --es bubble   news (default: the pill is untold news, the bubble speaks
 *                 after the reveal) | seen (only the arrow until idle)
 *   --el idleMs   the idle threshold (default 15000)
 *
 * Rediscover (P0-9; 2026-10-05 no score bar + rating badge):
 *   --ez rediscover true  adds the shelf: an album rating (solid badge), a
 *                 track average (outlined badge), a score under the old 8.0
 *                 bar, and unscored albums kept by notes / a review (no badge)
 *   --ei rediscoverCount  keep only the first N cards (1 = the lone full-width card)
 *
 * Recently Added (2026-10-05 tablet density): 8 tracks and 14 albums supplied;
 * a phone seats its 2×2 (4) and 12 albums, 3 columns × 2 rows on the 800dp
 * tablet portrait, 4 × 2 on the 1280dp landscape.
 *   --ei recentTracks  keep only the first N tracks (default 8)
 *
 * Home edit mode (P0, in place; a standalone controller, so no bar):
 *   --ez edit     true starts in edit mode (a blank tap leaves it)
 *   --es wiggle   IdleSettle (default) | Kick | Continuous
 *   --es wiggleTarget  Card (default) | Block
 *   --ez reduced  true runs the page as AdaptiveReduced (no charge, sway or kicks)
 *   --es plate    V1 (default: the shipped plate — outset to 4dp short of the page
 *                 margin, spacing 18 → 32dp while editing, shelves clipped to the
 *                 plate with a fade) | V0 (the pre-2026-10-05 plate) | V2 (plate on
 *                 the page margins, content 12dp narrower inside it) |
 *                 V3 (no plate fill: wiggle + badges + a tonal title row)
 *   e.g. adb shell am start -S -n com.gpo.yoin/com.gpo.yoin.debug.MemoriesScreenshotActivity \
 *          --ez edit true --ez rediscover true --es plate V0
 *
 * Row presets (D1, 2026-10-05; drag the ⌟ at a block's bottom-right in edit mode):
 *   --es rowsActivities  s | m | l (default) | xl
 *   --es rowsJbi         s | m | l (default) | xl
 *   e.g. … --ez edit true --es rowsJbi xl --es rowsActivities s
 */
class MemoriesScreenshotActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableYoinEdgeToEdge()
        val variant = intent.getStringExtra("variant")
            ?.let { name -> HomeHintVariant.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
            ?: HomeHintVariant.Recommended
        val pillKey = intent.getStringExtra("pill") ?: "album"
        val noteCount = intent.getIntExtra("notes", 12)
        val plainGrid = intent.getStringExtra("grid") == "plain"
        val showRecent = intent.getBooleanExtra("recent", true)
        val pillDelayMs = intent.getLongExtra("pillDelayMs", 0L)
        val cycle = intent.getBooleanExtra("cycle", false)
        val rotate = intent.getIntExtra("rotate", 0)
        val shelfShift = intent.getIntExtra("shelf", 0)
        val signalCount = intent.getIntExtra("signals", 2)
        val showActivities = intent.getBooleanExtra("activities", true)
        val bubbleSeen = intent.getStringExtra("bubble") == "seen"
        val idleMs = intent.getLongExtra("idleMs", MemoryBubbleIdleMs)
        val rediscover = if (intent.getBooleanExtra("rediscover", false)) {
            fakeRediscover().take(intent.getIntExtra("rediscoverCount", Int.MAX_VALUE).coerceAtLeast(0))
        } else {
            emptyList()
        }
        val recentTracks = intent.getIntExtra("recentTracks", Int.MAX_VALUE).coerceAtLeast(0)
        val startEditing = intent.getBooleanExtra("edit", false)
        val plateVariant = intent.getStringExtra("plate")
            ?.let { name -> HomePlateVariant.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
            ?: HomePlateVariant.V1
        val initialLayout = HomeLayout.Default
            .withRows(HomeSection.Activities, rowsExtra("rowsActivities"))
            .withRows(HomeSection.JumpBackIn, rowsExtra("rowsJbi"))
        val wiggleStyle = HomeWiggleStyle(
            mode = intent.getStringExtra("wiggle")
                ?.let { name -> HomeWiggleMode.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
                ?: HomeWiggleMode.IdleSettle,
            target = intent.getStringExtra("wiggleTarget")
                ?.let { name -> HomeWiggleTarget.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
                ?: HomeWiggleTarget.Card,
        )
        val motionProfile =
            if (intent.getBooleanExtra("reduced", false)) MotionProfile.AdaptiveReduced else MotionProfile.Full
        val bubbleStore = InMemoryMemoryBubbleSeenStore()
        setContent {
            // 对齐生产环境：QA 台也提供窗口信息，Medium/Wide 分支才可验。
            val windowInfo = rememberYoinWindowInfo()
            if (bubbleSeen) {
                remember { fakeMemoryPill(pillKey, noteCount)?.let { bubbleStore.markSeen(it.scope, it.newsKey) } }
            }
            var pill by remember {
                mutableStateOf(if (pillDelayMs > 0L || cycle) null else fakeMemoryPill(pillKey, noteCount))
            }
            LaunchedEffect(Unit) {
                if (cycle) {
                    val steps = listOf("empty", "none", "average", "album")
                    var index = 0
                    while (true) {
                        pill = fakeMemoryPill(steps[index % steps.size], noteCount)
                        index++
                        delay(2_500L)
                    }
                } else if (pillDelayMs > 0L) {
                    delay(pillDelayMs)
                    pill = fakeMemoryPill(pillKey, noteCount)
                }
            }
            CompositionLocalProvider(
                LocalYoinWindowInfo provides windowInfo,
                LocalHomeHintVariant provides variant,
                LocalMemoryBubbleSeenStore provides bubbleStore,
                LocalMemoryBubbleIdleMs provides idleMs,
                LocalHomeWiggleStyle provides wiggleStyle,
                LocalMotionProfile provides motionProfile,
                LocalHomePlateVariant provides plateVariant,
            ) {
            YoinTheme {
                // The page's own controller, so the harness can open in edit mode.
                val editController = rememberStandaloneHomeEditController(initialLayout)
                LaunchedEffect(editController) {
                    if (startEditing) editController.enter(null, lifted = false)
                }
                // Production's page gradient (HomeScreen), so the pill and the
                // seams are judged on the background they really sit on.
                ExpressivePageBackground(modifier = Modifier.fillMaxSize()) {
                    HomeEditorialContent(
                        activities = if (!showActivities) emptyList() else fakeActivities().let { list ->
                            // Newest-first is the feed's contract: rotate, then
                            // re-stamp the times so order and recency agree.
                            val shift = Math.floorMod(rotate, list.size)
                            val rotated = list.drop(shift) + list.take(shift)
                            rotated.zip(list) { event, slot -> event.copy(timestamp = slot.timestamp) }
                        },
                        widgetGrid = (if (plainGrid) fakePlainWidgetGrid() else fakeWidgetGrid()).let { cards ->
                            val signals = cards.filter { it.expanded }.take(signalCount)
                            val covers = cards.filterNot { it.expanded }
                            val shift = Math.floorMod(shelfShift, covers.size.coerceAtLeast(1))
                            signals + covers.drop(shift) + covers.take(shift)
                        },
                        activityHeroFootnote = "2024 · 12 songs · 44 min",
                        recentlyAddedTracks = if (showRecent) {
                            fakeRecentlyAddedTracks().take(recentTracks)
                        } else {
                            emptyList()
                        },
                        recentlyAddedAlbums = if (showRecent) fakeRecentlyAddedAlbums() else emptyList(),
                        rediscover = rediscover,
                        memoryPill = pill,
                        sections = initialLayout.sections,
                        onNavigateToSettings = {},
                        onNavigateToMemories = {},
                        editController = editController,
                        onAlbumClick = { _, _ -> },
                        onArtistClick = {},
                        onPlaylistClick = {},
                        onSongClick = {},
                        // Storage keys in the fakes are file:// URIs; identity
                        // pass-through lets Coil load them directly.
                        buildCoverArtUrl = { it },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            }
        }
    }

    private fun rowsExtra(name: String): HomeRowPreset =
        HomeRowPreset.fromKey(intent.getStringExtra(name)?.lowercase()) ?: HomeRowPreset.Default

    private fun swatchCover(name: String, color: Int): String {
        val file = File(cacheDir, "mem_$name.png")
        if (!file.exists()) {
            val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawColor(color)
            file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            bitmap.recycle()
        }
        return Uri.fromFile(file).toString()
    }

    private fun fakeActivities(): List<ActivityEvent> {
        val now = System.currentTimeMillis()
        return listOf(
            ActivityEvent(
                id = 1,
                entityType = ActivityEntityType.ALBUM.name,
                actionType = ActivityActionType.PLAYED.name,
                entityId = "a1",
                title = "This Infinite",
                subtitle = "Vitesse X",
                coverArtId = swatchCover("mint", 0xFF3DAE77.toInt()),
                albumId = "a1",
                timestamp = now - 5L * 60 * 60 * 1000,
            ),
            ActivityEvent(
                id = 2,
                // Artist lands in the small bento card — verifies the circular
                // portrait (not a rounded rect / SoftBoom blob).
                entityType = ActivityEntityType.ARTIST.name,
                actionType = ActivityActionType.VISITED.name,
                entityId = "ar1",
                title = "Hannah Jadagu",
                subtitle = "Artist",
                coverArtId = swatchCover("pink", 0xFFD4537E.toInt()),
                timestamp = now - 26L * 60 * 60 * 1000,
            ),
            ActivityEvent(
                id = 3,
                entityType = ActivityEntityType.ALBUM.name,
                actionType = ActivityActionType.PLAYED.name,
                entityId = "a2",
                title = "Para que salgamos bien en la foto",
                subtitle = "Rakky Ripper",
                coverArtId = swatchCover("blue", 0xFF378ADD.toInt()),
                albumId = "a2",
                timestamp = now - 60L * 60 * 1000,
            ),
            ActivityEvent(
                id = 4,
                entityType = ActivityEntityType.ALBUM.name,
                actionType = ActivityActionType.PLAYED.name,
                entityId = "a3",
                title = "天国の部屋",
                subtitle = "坂口諒之介",
                coverArtId = swatchCover("salmon", 0xFFE0705A.toInt()),
                albumId = "a3",
                timestamp = now - 25L * 60 * 60 * 1000,
            ),
            // 5-8 号：喂满 Medium 密度 bento（hero + 双支撑行 + 双 strip = 7 条）。
            ActivityEvent(
                id = 5,
                entityType = ActivityEntityType.ALBUM.name,
                actionType = ActivityActionType.PLAYED.name,
                entityId = "a4",
                title = "Freakout/Release",
                subtitle = "Hot Chip",
                coverArtId = swatchCover("magenta", 0xFFC2447F.toInt()),
                albumId = "a4",
                timestamp = now - 30L * 60 * 60 * 1000,
            ),
            ActivityEvent(
                id = 6,
                entityType = ActivityEntityType.ARTIST.name,
                actionType = ActivityActionType.VISITED.name,
                entityId = "ar2",
                title = "hemlocke springs",
                subtitle = "Artist",
                coverArtId = swatchCover("violet", 0xFF7A67D8.toInt()),
                timestamp = now - 32L * 60 * 60 * 1000,
            ),
            ActivityEvent(
                id = 7,
                entityType = ActivityEntityType.PLAYLIST.name,
                actionType = ActivityActionType.PLAYED.name,
                entityId = "p1",
                title = "monday afternoon",
                subtitle = "Playlist",
                coverArtId = swatchCover("amber", 0xFFD89A2E.toInt()),
                timestamp = now - 40L * 60 * 60 * 1000,
            ),
            ActivityEvent(
                id = 8,
                entityType = ActivityEntityType.ALBUM.name,
                actionType = ActivityActionType.PLAYED.name,
                entityId = "a5",
                title = "sense (is)",
                subtitle = "hemlocke springs",
                coverArtId = swatchCover("teal2", 0xFF1D9E9E.toInt()),
                albumId = "a5",
                timestamp = now - 48L * 60 * 60 * 1000,
            ),
            // 9-11 号：喂满 Wide 桌面 tapestry（hero + 3:2:1 行 + 1:2:1:2 行
            // + 三 strip = 10 条），1280dp QA 不缺粮。
            ActivityEvent(
                id = 9,
                entityType = ActivityEntityType.ALBUM.name,
                actionType = ActivityActionType.PLAYED.name,
                entityId = "a6",
                title = "Little House",
                subtitle = "Rachel Chinouriri",
                coverArtId = swatchCover("green", 0xFF639922.toInt()),
                albumId = "a6",
                timestamp = now - 4L * 24 * 60 * 60 * 1000,
            ),
            ActivityEvent(
                id = 10,
                entityType = ActivityEntityType.ARTIST.name,
                actionType = ActivityActionType.VISITED.name,
                entityId = "ar3",
                title = "Rachel Chinouriri",
                subtitle = "Artist",
                coverArtId = swatchCover("navy", 0xFF185FA5.toInt()),
                timestamp = now - 5L * 24 * 60 * 60 * 1000,
            ),
            ActivityEvent(
                id = 11,
                entityType = ActivityEntityType.PLAYLIST.name,
                actionType = ActivityActionType.PLAYED.name,
                entityId = "p2",
                title = "Endless Natsu",
                subtitle = "Playlist",
                coverArtId = swatchCover("teal", 0xFF1D9E75.toInt()),
                timestamp = now - 6L * 24 * 60 * 60 * 1000,
            ),
            // 12-17: enough for the widest unit bento (13 slots) — feed units
            // follow the width now (HomeFeedDensity), up to 13 on a 1280dp canvas.
            ActivityEvent(
                id = 12,
                entityType = ActivityEntityType.ALBUM.name,
                actionType = ActivityActionType.PLAYED.name,
                entityId = "a7",
                title = "Pang",
                subtitle = "Caroline Polachek",
                coverArtId = swatchCover("amber", 0xFFD89A2E.toInt()),
                albumId = "a7",
                timestamp = now - 7L * 24 * 60 * 60 * 1000,
            ),
            ActivityEvent(
                id = 13,
                entityType = ActivityEntityType.ARTIST.name,
                actionType = ActivityActionType.VISITED.name,
                entityId = "ar4",
                title = "Mom",
                subtitle = "Artist",
                coverArtId = swatchCover("coral", 0xFFD85A30.toInt()),
                timestamp = now - 8L * 24 * 60 * 60 * 1000,
            ),
            ActivityEvent(
                id = 14,
                entityType = ActivityEntityType.ALBUM.name,
                actionType = ActivityActionType.PLAYED.name,
                entityId = "a8",
                title = "AIと刹那のポリティクス",
                subtitle = "Mom",
                coverArtId = swatchCover("blue", 0xFF378ADD.toInt()),
                albumId = "a8",
                timestamp = now - 9L * 24 * 60 * 60 * 1000,
            ),
            ActivityEvent(
                id = 15,
                entityType = ActivityEntityType.PLAYLIST.name,
                actionType = ActivityActionType.PLAYED.name,
                entityId = "p3",
                title = "My Angelist #101",
                subtitle = "Playlist",
                coverArtId = swatchCover("mint", 0xFF3DAE77.toInt()),
                timestamp = now - 10L * 24 * 60 * 60 * 1000,
            ),
            ActivityEvent(
                id = 16,
                entityType = ActivityEntityType.ALBUM.name,
                actionType = ActivityActionType.PLAYED.name,
                entityId = "a9",
                title = "Gemini Rights",
                subtitle = "Steve Lacy",
                coverArtId = swatchCover("violet", 0xFF7F77DD.toInt()),
                albumId = "a9",
                timestamp = now - 11L * 24 * 60 * 60 * 1000,
            ),
            ActivityEvent(
                id = 17,
                entityType = ActivityEntityType.ARTIST.name,
                actionType = ActivityActionType.VISITED.name,
                entityId = "ar5",
                title = "Steve Lacy",
                subtitle = "Artist",
                coverArtId = swatchCover("pink", 0xFFD4537E.toInt()),
                timestamp = now - 12L * 24 * 60 * 60 * 1000,
            ),
            // Deep enough for the widest XL bento (a fourth unit row).
            ActivityEvent(
                id = 18,
                entityType = ActivityEntityType.ALBUM.name,
                actionType = ActivityActionType.PLAYED.name,
                entityId = "a10",
                title = "Aftertaste",
                subtitle = "Hannah Jadagu",
                coverArtId = swatchCover("rose", 0xFFC9566B.toInt()),
                albumId = "a10",
                timestamp = now - 13L * 24 * 60 * 60 * 1000,
            ),
            ActivityEvent(
                id = 19,
                entityType = ActivityEntityType.PLAYLIST.name,
                actionType = ActivityActionType.PLAYED.name,
                entityId = "p4",
                title = "Sunday Tape",
                subtitle = "Playlist",
                coverArtId = swatchCover("plum", 0xFF7A4E8C.toInt()),
                timestamp = now - 14L * 24 * 60 * 60 * 1000,
            ),
            ActivityEvent(
                id = 20,
                entityType = ActivityEntityType.ARTIST.name,
                actionType = ActivityActionType.VISITED.name,
                entityId = "ar6",
                title = "Ezra Collective",
                subtitle = "Artist",
                coverArtId = swatchCover("coral2", 0xFFE07B4F.toInt()),
                timestamp = now - 15L * 24 * 60 * 60 * 1000,
            ),
        )
    }

    private fun fakeMemoryPill(key: String, noteCount: Int): HomeMemoryPill? {
        // A different album than the grid's memory 1×2 — the real pipeline
        // keeps them apart (pickJbiMemoryCandidate).
        val latest = HomeMemoryPill.Latest(
            sessionId = 2L,
            albumId = MediaId.subsonic("pang"),
            albumName = "Pang",
            artistName = "Caroline Polachek",
            coverArtUrl = swatchCover("amber", 0xFFD89A2E.toInt()),
            scoreKind = MemoryScoreKind.ALBUM_RATING,
            scoreText = "8.4",
            writtenAtMillis = System.currentTimeMillis() - 3L * 86_400_000L,
            memoryTitle = intent.getStringExtra("memoryTitle") ?: "Rain on the Night Bus",
        )
        return fakePillByKey(key, noteCount, latest)?.let { pill ->
            pill.copy(scope = "fake", newsKey = "fake-$key-$noteCount")
        }
    }

    private fun fakePillByKey(key: String, noteCount: Int, latest: HomeMemoryPill.Latest): HomeMemoryPill? {
        return when (key) {
            "unresolved" -> null
            "empty" -> HomeMemoryPill(latest = null, noteCount = 0)
            "notes" -> HomeMemoryPill(latest = null, noteCount = noteCount.coerceAtLeast(1))
            "none" -> HomeMemoryPill(
                latest = latest.copy(scoreKind = MemoryScoreKind.NONE, scoreText = null),
                noteCount = noteCount,
            )
            "average" -> HomeMemoryPill(
                latest = latest.copy(scoreKind = MemoryScoreKind.AVERAGE_TRACK_RATING, scoreText = "7.6"),
                noteCount = noteCount,
            )
            else -> HomeMemoryPill(latest = latest, noteCount = noteCount)
        }
    }

    /**
     * The Apple Music profile's shape: no notes / ratings → no 1×2 signal
     * cards, only albums and playlists, the last two playlists artless.
     */
    private fun fakePlainWidgetGrid(): List<HomeWidgetCard> = listOf(
        fakeAlbumCard("Ajala (Single Edit)", "Ezra Collective", swatchCover("coral", 0xFFD85A30.toInt())),
        fakePlaylistCard("Endless Natsu", "51", swatchCover("teal", 0xFF1D9E75.toInt())),
        fakeAlbumCard("Abracadabra", "Lady Gaga", swatchCover("navy", 0xFF185FA5.toInt())),
        fakePlaylistCard("Atypical 1", "51", swatchCover("salmon", 0xFFE0705A.toInt())),
        fakeAlbumCard("Addison", "Addison Rae", swatchCover("amber", 0xFFD89A2E.toInt())),
        fakePlaylistCard("繼續唱歌給你聽", "51", swatchCover("pink", 0xFFD4537E.toInt())),
        fakeAlbumCard("A Night To Remember", "beabadoobee", swatchCover("blue", 0xFF378ADD.toInt())),
        fakePlaylistCard("游戏电台 1", "51", swatchCover("magenta", 0xFFC2447F.toInt())),
        fakeAlbumCard("After LIKE", "IVE", swatchCover("mint", 0xFF3DAE77.toInt())),
        fakeAlbumCard("Absolution", "Muse", swatchCover("violet", 0xFF7F77DD.toInt())),
        fakePlaylistCard("todo", "51", null),
        fakePlaylistCard("路灯下", "51", null),
    )

    private fun fakeWidgetGrid(): List<HomeWidgetCard> {
        // The design composition: 2+2+3+3+2 = 12 cells.
        val notedSong = fakeTrack("t-note", "跳不完的舞", "秦凡淇", swatchCover("olive", 0xFF7C9A1E.toInt()))
        return listOf(
            HomeWidgetCard(
                stableId = "grid-memory:demo",
                entityType = MemoryEntityType.ALBUM,
                title = "Describe",
                subtitle = "Album · Hannah Jadagu",
                coverArtUrl = swatchCover("green", 0xFF639922.toInt()),
                ratingText = "8.4",
                ratingBasis = "Jun 26",
                comment = "Still opens the same door, six months on.",
                expanded = true,
                target = HomeWidgetTarget.MemoryFocus(1L),
            ),
            HomeWidgetCard(
                stableId = "grid-note:demo",
                entityType = MemoryEntityType.SONG,
                title = notedSong.title.orEmpty(),
                subtitle = "Single · ${notedSong.artist}",
                coverArtUrl = swatchCover("olive", 0xFF7C9A1E.toInt()),
                ratingText = "9.5",
                ratingBasis = "Jun 26",
                comment = "在她的身体里，跳舞也可以变成带着哲思的苦行。",
                expanded = true,
                target = HomeWidgetTarget.PlaySong(notedSong),
            ),
            fakeAlbumCard("Little House", "Rachel C.", swatchCover("pink", 0xFFD4537E.toInt())),
            fakeSongCard("Gimme Time", "Hannah Jadagu", swatchCover("coral", 0xFFD85A30.toInt())),
            fakePlaylistCard("Endless Natsu", "51", swatchCover("teal", 0xFF1D9E75.toInt())),
            fakeAlbumCard("AIと刹那", "Mom", swatchCover("blue", 0xFF378ADD.toInt())),
            fakeSongCard("sense (is)", "hemlocke springs", swatchCover("violet", 0xFF7F77DD.toInt())),
            fakePlaylistCard("My Angelist #101", "HESSBEN", swatchCover("mint", 0xFF3DAE77.toInt())),
            fakeAlbumCard("Freakout/Release", "Hot Chip", swatchCover("salmon", 0xFFE0705A.toInt())),
            fakePlaylistCard("305tilidie", "Camila Cabello", swatchCover("navy", 0xFF185FA5.toInt())),
            // The deeper tablet shelf (a template seats up to 24 covers at L).
            fakeAlbumCard("This Infinite", "Vitesse X", swatchCover("amber", 0xFFD89A2E.toInt())),
            fakeSongCard("Mom", "Rachel Chinouriri", swatchCover("magenta", 0xFFC2447F.toInt())),
            fakePlaylistCard("Late Trains", "51", swatchCover("teal2", 0xFF2A8C8C.toInt())),
            fakeAlbumCard("天国の部屋", "坂口諒之介", swatchCover("indigo", 0xFF4B4FA8.toInt())),
            fakeSongCard("Describe", "Hannah Jadagu", swatchCover("lime", 0xFF8DB33A.toInt())),
            fakePlaylistCard("Rainy Bus", "51", swatchCover("slate", 0xFF52707F.toInt())),
            fakeAlbumCard("Aftertaste", "Hannah Jadagu", swatchCover("rose", 0xFFC9566B.toInt())),
            fakeSongCard("Gemini Rights", "Steve Lacy", swatchCover("ochre", 0xFFB7862D.toInt())),
            fakePlaylistCard("Sunday Tape", "51", swatchCover("plum", 0xFF7A4E8C.toInt())),
            fakeAlbumCard("Abracadabra", "Lady Gaga", swatchCover("sky", 0xFF4F9BD6.toInt())),
            fakeSongCard("After LIKE", "IVE", swatchCover("mint2", 0xFF56B894.toInt())),
            fakePlaylistCard("Atypical 1", "51", swatchCover("brick", 0xFFB5523C.toInt())),
            fakeAlbumCard("Absolution", "Muse", swatchCover("violet2", 0xFF6A5ACD.toInt())),
            fakeSongCard("Ajala", "Ezra Collective", swatchCover("coral2", 0xFFE07B4F.toInt())),
            fakePlaylistCard("游戏电台 1", "51", swatchCover("pink2", 0xFFE06C9F.toInt())),
            fakeAlbumCard("A Night To Remember", "beabadoobee", swatchCover("blue2", 0xFF2F6FB8.toInt())),
            // XL on the widest pane (10 columns × 3 rows) seats 26 covers.
            fakeSongCard("Couldn't Call It Love", "Hannah Jadagu", swatchCover("green2", 0xFF4E9A3A.toInt())),
            fakePlaylistCard("Night Bus", "51", swatchCover("gold", 0xFFC9A227.toInt())),
            fakeAlbumCard("Little Dark Age", "MGMT", swatchCover("teal3", 0xFF2E7D7A.toInt())),
            fakeSongCard("Sofia", "Clairo", swatchCover("peach", 0xFFE89A6B.toInt())),
        )
    }

    // Eight supplied: a phone shows the first four (2×2), the tablet 6 or 8.
    private fun fakeRecentlyAddedTracks(): List<Track> = listOf(
        fakeTrack("r1", "Describe", "Hannah Jadagu", swatchCover("green", 0xFF639922.toInt())),
        fakeTrack("r2", "D.I.A.A", "Hannah Jadagu", swatchCover("coral", 0xFFD85A30.toInt())),
        fakeTrack("r3", "Couldn't Call It Love", "Hannah Jadagu", swatchCover("teal", 0xFF1D9E75.toInt())),
        fakeTrack("r4", "My Love", "Hannah Jadagu", swatchCover("pink", 0xFFD4537E.toInt())),
        fakeTrack("r5", "Ajala", "Ezra Collective", swatchCover("amber", 0xFFD89A2E.toInt())),
        fakeTrack("r6", "After LIKE", "IVE", swatchCover("violet", 0xFF7F77DD.toInt())),
        fakeTrack("r7", "Gemini Rights", "Steve Lacy", swatchCover("navy", 0xFF185FA5.toInt())),
        fakeTrack("r8", "跳不完的舞", "秦凡淇", swatchCover("olive", 0xFF7C9A1E.toInt())),
    )

    private fun fakeRecentlyAddedAlbums(): List<Album> = listOf(
        fakeAlbum("ra1", "AIと刹那のポリティクス", "Rachel Chinouriri", swatchCover("salmon", 0xFFE0705A.toInt())),
        fakeAlbum("ra2", "Little House", "Rachel Chinouriri", swatchCover("green", 0xFF3DAE77.toInt())),
        fakeAlbum("ra3", "This Infinite", "Vitesse X", swatchCover("blue", 0xFF378ADD.toInt())),
        fakeAlbum("ra4", "Freakout/Release", "Hot Chip", swatchCover("violet", 0xFF7F77DD.toInt())),
        fakeAlbum("ra5", "天国の部屋", "坂口諒之介", swatchCover("navy", 0xFF185FA5.toInt())),
        // The deeper tablet shelf.
        fakeAlbum("ra6", "Pang", "Caroline Polachek", swatchCover("amber", 0xFFD89A2E.toInt())),
        fakeAlbum("ra7", "Gemini Rights", "Steve Lacy", swatchCover("magenta", 0xFFC2447F.toInt())),
        fakeAlbum("ra8", "sense (is)", "hemlocke springs", swatchCover("teal2", 0xFF1D9E9E.toInt())),
        fakeAlbum("ra9", "Absolution", "Muse", swatchCover("violet2", 0xFF6A5ACD.toInt())),
        fakeAlbum("ra10", "Abracadabra", "Lady Gaga", swatchCover("sky", 0xFF4F9BD6.toInt())),
        fakeAlbum("ra11", "A Night To Remember", "beabadoobee", swatchCover("rose", 0xFFC9566B.toInt())),
        fakeAlbum("ra12", "Ajala", "Ezra Collective", swatchCover("coral2", 0xFFE07B4F.toInt())),
        fakeAlbum("ra13", "After LIKE", "IVE", swatchCover("mint2", 0xFF56B894.toInt())),
        fakeAlbum("ra14", "Aftertaste", "Hannah Jadagu", swatchCover("brick", 0xFFB5523C.toInt())),
    )

    private fun fakeRediscover(): List<HomeRediscoverItem> {
        val now = System.currentTimeMillis()
        val day = 24L * 60 * 60 * 1000
        fun item(
            rawId: String,
            name: String,
            artist: String,
            cover: String,
            score: Float?,
            daysAway: Long,
            firstDaysAgo: Long,
            plays: Int,
            kind: MemoryScoreKind = MemoryScoreKind.ALBUM_RATING,
            review: Boolean = false,
            notes: Int = 0,
        ) = HomeRediscoverItem(
            albumId = MediaId.subsonic(rawId),
            albumName = name,
            artistName = artist,
            coverArtUrl = cover,
            score = score,
            scoreText = score?.let(::rediscoverScoreText),
            scoreKind = if (score == null) MemoryScoreKind.NONE else kind,
            lastPlayedAt = now - daysAway * day,
            firstPlayedAt = now - firstDaysAgo * day,
            playCount = plays,
            hasReview = review,
            noteCount = notes,
        )
        // The selection's order: scored best-first, then the unscored.
        return listOf(
            item("rd1", "Emotion", "Carly Rae Jepsen", swatchCover("rose", 0xFFE4577A.toInt()), 9.0f, 214, 700, 23),
            item(
                "rd2",
                "Para que salgamos bien en la foto (Edición Deluxe)",
                "Rakky Ripper",
                swatchCover("blue", 0xFF378ADD.toInt()),
                8.4f,
                160,
                420,
                41,
                kind = MemoryScoreKind.AVERAGE_TRACK_RATING,
            ),
            // Under the retired 8.0 bar: back all the same.
            item("rd4", "Little House", "Rachel Chinouriri", swatchCover("moss", 0xFF639922.toInt()), 6.5f, 95, 95, 1),
            item(
                "rd3",
                "Blonde",
                "Frank Ocean",
                swatchCover("amber", 0xFFD89A2E.toInt()),
                null,
                800,
                1500,
                17,
                notes = 3,
            ),
            item(
                "rd5",
                "Heaven or Las Vegas",
                "Cocteau Twins",
                swatchCover("teal", 0xFF1D9E75.toInt()),
                null,
                400,
                900,
                9,
                review = true,
            ),
        )
    }

    private fun fakeAlbum(rawId: String, name: String, artist: String, cover: String): Album =
        Album(
            id = MediaId.subsonic(rawId),
            name = name,
            artist = artist,
            artistId = null,
            coverArt = CoverRef.Url(cover),
            songCount = 10,
            durationSec = 2000,
            year = 2024,
            genre = null,
        )

    private fun fakeAlbumCard(title: String, artist: String, cover: String): HomeWidgetCard =
        HomeWidgetCard(
            stableId = "grid-album:$title",
            entityType = MemoryEntityType.ALBUM,
            title = title,
            subtitle = "Album · $artist",
            coverArtUrl = cover,
            target = HomeWidgetTarget.AlbumDetail("subsonic:$title"),
        )

    private fun fakeSongCard(title: String, artist: String, cover: String): HomeWidgetCard =
        HomeWidgetCard(
            stableId = "grid-song:$title",
            entityType = MemoryEntityType.SONG,
            title = title,
            subtitle = "Single · $artist",
            coverArtUrl = cover,
            target = HomeWidgetTarget.PlaySong(fakeTrack("s-$title", title, artist, cover)),
        )

    private fun fakePlaylistCard(title: String, owner: String, cover: String?): HomeWidgetCard =
        HomeWidgetCard(
            stableId = "grid-playlist:$title",
            entityType = MemoryEntityType.PLAYLIST,
            title = title,
            subtitle = "Playlist · $owner",
            coverArtUrl = cover,
            target = HomeWidgetTarget.PlaylistDetail("subsonic:$title"),
        )

    private fun fakeTrack(rawId: String, title: String, artist: String, cover: String): Track =
        Track(
            id = MediaId.subsonic(rawId),
            title = title,
            artist = artist,
            artistId = null,
            album = null,
            albumId = null,
            coverArt = CoverRef.Url(cover),
            durationSec = 200,
            trackNumber = null,
            year = null,
            genre = null,
            userRating = null,
        )
}
