package com.gpo.yoin.ui.home

import com.gpo.yoin.data.home.FakeHomeLayoutDao
import com.gpo.yoin.data.home.HomeLayoutDocument
import com.gpo.yoin.data.home.HomeLayoutStore
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.repository.YoinRepository
import com.gpo.yoin.shared.home.HomeFeedRow
import com.gpo.yoin.shared.home.HomeLayoutCodec
import com.gpo.yoin.shared.home.toLayout
import com.gpo.yoin.shared.home.toState
import com.gpo.yoin.testutil.MainDispatcherRule
import com.gpo.yoin.ui.home.HomeGoldenPin.bool
import com.gpo.yoin.ui.home.HomeGoldenPin.int
import com.gpo.yoin.ui.home.HomeGoldenPin.name
import com.gpo.yoin.ui.home.HomeGoldenPin.obj
import com.gpo.yoin.ui.home.HomeGoldenPin.pairs
import com.gpo.yoin.ui.home.HomeGoldenPin.section
import com.gpo.yoin.ui.home.HomeGoldenPin.string
import com.gpo.yoin.ui.home.HomeGoldenPin.strings
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test

/**
 * Android answers every home-layout golden vector (shared/src/commonTest/resources/golden/home-layout.json) that
 * iOS and HarmonyOS are tested against, through the app's own paths: the Room-backed [HomeLayoutStore] (its
 * encode, decode and write-skip), [HomeViewModel]'s read of the stored document and its write rule
 * (`setHomeLayout`: clear the row or write the document, retained ids carried), and the layout edits under
 * the app's names. Before the move (parity p4 step A, 2026-11-26) every vector but the unknown-key ones was
 * what the app's own code answered: the move changed nothing else.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeLayoutPinTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val golden = HomeGoldenPin.load("home-layout.json")
    private val states = HomeGoldenPin.states(golden)

    private fun vectors(name: String) = with(HomeGoldenPin) { golden.section(name) }

    @Test
    fun should_decodeAndEncodeAsPinned_throughTheStore() = runTest {
        vectors("codec").forEach { v ->
            val dao = FakeHomeLayoutDao()
            val store = HomeLayoutStore(dao)
            dao.seed(READ, v.string("raw")!!)
            val document = store.readDocument(READ)
            assertEquals(v.name, v["document"], documentJson(document))
            if (document != null) {
                store.setDocument(WRITE, document)
                assertEquals(v.name, v.string("encoded"), dao.raw(WRITE))
            }
            store.setDocument(LAYOUT, HomeLayout.fromDocument(document).toDocument())
            assertEquals(v.name, v.string("layoutEncoded"), dao.raw(LAYOUT))
        }
    }

    @Test
    fun should_readAndPersistAsPinned_throughTheViewModel() = runTest {
        vectors("reconcile").forEach { v ->
            val dao = FakeHomeLayoutDao()
            (v["document"] as? JsonObject)?.let { dao.seed(PROFILE, it.toString()) }
            val viewModel = viewModel(HomeLayoutStore(dao))
            advanceUntilIdle()
            val read = viewModel.homeLayout.value
            assertEquals(v.name, v.obj("state"), HomeGoldenPin.stateJson(read.toState()))
            assertEquals(v.name, v.bool("isDefault"), read.isDefault)

            val upserts = dao.upserts
            viewModel.setHomeLayout(read)
            advanceUntilIdle()
            assertPersisted(v.name, v.obj("persist"), dao, upserts)
        }
    }

    @Test
    fun should_persistAsPinned_throughTheViewModel() = runTest {
        vectors("predicates").forEach { v ->
            val dao = FakeHomeLayoutDao()
            val viewModel = viewModel(HomeLayoutStore(dao))
            advanceUntilIdle()
            val layout = HomeGoldenPin.stateIn(v.getValue("state"), states).toLayout()
            viewModel.setHomeLayout(layout)
            advanceUntilIdle()
            assertPersisted(v.name, v.obj("persist"), dao, upserts = 0)

            assertEquals(v.name, v.bool("isDefault"), layout.isDefault)
            assertEquals(v.name, v.bool("isWideDefault"), layout.isWideDefault)
            assertEquals(v.name, v.bool("isAnyDefault"), layout.isAnyDefault)
            assertEquals(v.name, v.bool("isDefaultForTwoColumn"), layout.isDefaultFor(twoColumn = true))
            assertEquals(v.name, v.strings("enabled"), layout.enabledSections.map { it.id })
            assertEquals(v.name, v.strings("hidden"), layout.hiddenSections.map { it.id })
            assertEquals(v.name, v.pairs("pairs"), layout.pairs.map { (l, r) -> listOf(l.id, r.id) })
            HomeSection.entries.forEach { s ->
                assertEquals(v.name, v.obj("partners").string(s.id), layout.partnerOf(s)?.id)
                assertEquals(v.name, v.obj("units").int(s.id), layout.unitsOf(s))
                assertEquals(v.name, v.obj("rows").string(s.id), layout.rowsOf(s).key)
            }
        }
    }

    @Test
    fun should_editAsPinned_underTheAppsNames() {
        vectors("ops").forEach { v ->
            val layout = HomeGoldenPin.stateIn(v.getValue("state"), states).toLayout()
            val next = edit(layout, v)
            if (v.bool("changed")) {
                assertEquals(v.name, v.obj("after"), HomeGoldenPin.stateJson(next.toState()))
            } else {
                // Edit mode skips the Undo step by identity: unchanged means the same object.
                assertSame(v.name, layout, next)
            }
        }
    }

    @Test
    fun should_pairAndRowAsPinned_underTheAppsNames() {
        vectors("widePairsAt").forEach { v ->
            val memory = HomeGoldenPin.pairMap(v.pairs("memory"))
            // An order naming an id this build doesn't know shows no pairs (YoinHomeLayout's rule for foreign ids).
            val order = v.strings("order").map(HomeSection::fromId).takeIf { ids -> ids.none { it == null } }.orEmpty().filterNotNull()
            assertEquals(v.name, v.pairs("pairs"), homeWidePairsAt(order, memory).map { (l, r) -> listOf(l.id, r.id) })
        }
        vectors("samePairs").forEach { v ->
            assertEquals(v.name, v.bool("same"), homeSamePairs(HomeGoldenPin.pairMap(v.pairs("a")), HomeGoldenPin.pairMap(v.pairs("b"))))
        }
        vectors("feedRows").forEach { v ->
            val rows = homeFeedRows(HomeGoldenPin.sections(v.strings("displayed")), HomeGoldenPin.pairMap(v.pairs("pairs")), v.bool("twoColumn"))
            assertEquals(v.name, v.pairs("rows"), rows.map(::ids))
        }
        vectors("canPair").forEach { v ->
            val left = section(v.string("left")!!)
            val right = v.string("right")?.let(HomeSection::fromId)
            if (v.string("right") != null) assertEquals(v.name, v["canPair"], JsonPrimitive(right != null && homeCanPair(left, right)))
            val range = homeRowUnitRange(left, right)
            assertEquals(v.name, listOf(range.first, range.last), with(HomeGoldenPin) { v.ints("rowUnitRange") })
        }
        vectors("unitsPreset").forEach { v -> assertEquals(v.name, v.string("preset"), homeUnitsPreset(v.int("units")).key) }
    }

    // ── helpers ──────────────────────────────────────────────────────

    private fun edit(layout: HomeLayout, v: JsonObject): HomeLayout {
        fun id() = HomeSection.fromId(v.string("id")!!)
        fun all(key: String) = v.strings(key).map { HomeSection.fromId(it) }
        return when (v.string("op")) {
            "withEnabled" -> id()?.let { layout.withEnabled(it, v.bool("enabled")) } ?: layout
            "withEnabledOrder" -> {
                val order = all("order")
                val unseen = all("unseen")
                if (order.any { it == null } || unseen.any { it == null }) layout else layout.withEnabledOrder(order.filterNotNull(), unseen.filterNotNull().toSet())
            }
            "moved" -> id()?.let { layout.moved(it, v.int("index")) } ?: layout
            "withHalf" -> id()?.let(layout::withHalf) ?: layout
            "withFull" -> id()?.let(layout::withFull) ?: layout
            "withBesidePrevious" -> id()?.let(layout::withBesidePrevious) ?: layout
            "withArrangement" -> {
                val order = all("order")
                val unseen = all("unseen")
                val pairs = v.pairs("pairs").map { HomeSection.fromId(it[0]) to HomeSection.fromId(it[1]) }
                if (order.any { it == null } || unseen.any { it == null } || pairs.any { it.first == null || it.second == null }) {
                    layout
                } else {
                    layout.withArrangement(order.filterNotNull(), pairs.associate { it.first!! to it.second!! }, unseen.filterNotNull().toSet())
                }
            }
            "withUnits" -> id()?.let { layout.withUnits(it, v.int("units")) } ?: layout
            "withRows" -> {
                val rows = HomeRowPreset.fromKey(v.string("rows"))
                val s = id()
                if (rows == null || s == null) layout else layout.withRows(s, rows)
            }
            "reset" -> layout.reset(v.bool("twoColumn"))
            "forTwoColumn" -> layout.forTwoColumn(v.bool("twoColumn"))
            "shownAt" -> layout.shownAt(v.bool("twoColumn"))
            "rememberingShownPairs" -> layout.rememberingShownPairs()
            "withRememberedPairs" -> layout.withRememberedPairs()
            "normalizedSpans" -> layout.normalizedSpans()
            else -> error("unknown op ${v.string("op")}")
        }
    }

    private fun ids(row: HomeFeedRow): List<String> = when (row) {
        is HomeFeedRow.Full -> listOf(row.section.id)
        is HomeFeedRow.Halves -> listOf(row.left.id, row.right.id)
    }

    // The store writes only when the document differs from the stored one: compare documents, and the exact
    // text whenever a write happened.
    private fun assertPersisted(name: String, persist: JsonObject, dao: FakeHomeLayoutDao, upserts: Int) {
        if (persist.bool("clear")) {
            assertNull(name, dao.raw(PROFILE))
            return
        }
        val expected = persist.string("document")!!
        val raw = dao.raw(PROFILE)
        assertEquals(name, HomeLayoutCodec.decode(expected), raw?.let(HomeLayoutCodec::decode))
        if (dao.upserts > upserts) assertEquals(name, expected, raw)
    }

    private fun documentJson(d: HomeLayoutDocument?): JsonElement = if (d == null) {
        JsonNull
    } else {
        JsonObject(
            linkedMapOf(
                "sections" to JsonArray(d.sections.map(HomeLayoutCodec::encodeEntry)),
                "wide" to (d.wide ?: JsonNull),
                "extras" to (d.extras ?: JsonNull),
            ),
        )
    }

    private fun TestScope.viewModel(store: HomeLayoutStore): HomeViewModel {
        val repository = mockk<YoinRepository>(relaxed = true)
        every { repository.currentProviderId() } returns MediaId.PROVIDER_SUBSONIC
        every { repository.activeProviderId } returns flowOf(MediaId.PROVIDER_SUBSONIC)
        every { repository.currentCapabilities() } returns emptySet()
        every { repository.getRecentActivities(limit = any()) } returns flowOf(emptyList())
        every { repository.observeMemorySignalStamp() } returns flowOf()
        every { repository.observeMostRecentPlay() } returns flowOf()
        every { repository.getRating(any()) } returns flowOf(null)
        return HomeViewModel(
            repository = repository,
            activeProfileId = MutableStateFlow(PROFILE),
            homeLayoutStore = store,
            homeEditHintStore = HomeEditHintStore.InMemory(),
        )
    }

    private companion object {
        const val PROFILE = "pin-profile"
        const val READ = "pin-read"
        const val WRITE = "pin-write"
        const val LAYOUT = "pin-layout"
    }
}
