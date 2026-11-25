package com.gpo.yoin.data.album

import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.data.local.AlbumAboutEntry
import com.gpo.yoin.data.local.SongAboutEntry
import com.gpo.yoin.data.model.Album
import com.gpo.yoin.data.model.MediaId
import com.gpo.yoin.data.model.ReleaseDate
import com.gpo.yoin.data.model.ReleaseType
import com.gpo.yoin.data.model.Track
import com.gpo.yoin.data.source.applemusic.AppleMusicSource
import com.gpo.yoin.shared.albumabout.AlbumAboutGrounding
import com.gpo.yoin.shared.albumabout.AlbumAiCredits
import com.gpo.yoin.shared.albumabout.AlbumCreditLabels
import com.gpo.yoin.shared.albumabout.YoinAlbumAbout
import com.gpo.yoin.ui.detail.albumCreditDateText
import com.gpo.yoin.ui.detail.albumCreditItems
import com.gpo.yoin.ui.detail.albumCreditsLine
import com.gpo.yoin.ui.detail.resourceAlbumCreditLabels
import java.io.File
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Android answers every album About golden vector (shared/src/commonTest/resources/golden/album-about.json) the
 * way iOS and HarmonyOS are tested against, through the app's own paths: an [Album] mapped to its grounding
 * ([toAboutGrounding]) for the prompts, the app's string resources (en, zh-CN) and ICU date format for the
 * credits line, Apple Music's JSON for the notes, the Room rows for the content, the song About's key.
 * Before the move (2026-11-25) the same vectors were checked against the app's own code (235 vectors, all
 * equal), so the file is also what Android did before :shared took over.
 */
@RunWith(RobolectricTestRunner::class)
class AlbumAboutPinTest {

    private val golden: JsonObject = Json.parseToJsonElement(
        File(
            generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile },
            "shared/src/commonTest/resources/golden/album-about.json",
        ).readText(),
    ).jsonObject

    private fun section(name: String) = golden.getValue(name).jsonArray.map { it.jsonObject }

    @Test
    fun should_promptAsPinned_fromTheAppsAlbum() {
        val prompts = section("prompts")
        assertTrue(prompts.isNotEmpty())
        prompts.forEach { v ->
            val expected = groundingOf(v.obj("grounding"))
            val grounding = albumOf(expected).toAboutGrounding()
            assertEquals(expected, grounding)
            val language = v.string("targetLanguage")!!
            val lead = v.string("searchLead")!!
            assertEquals(v.string("about"), YoinAlbumAbout.aboutPrompt(grounding, language, lead))
            assertEquals(v.string("ask"), v.string("question")?.let { YoinAlbumAbout.askPrompt(grounding, it, language, lead) })
        }
        section("searchQueries").forEach { v ->
            val grounding = albumOf(groundingOf(v.obj("grounding"))).toAboutGrounding()
            assertEquals(v.strings("queries"), YoinAlbumAbout.searchQueries(grounding, v.getValue("count").jsonPrimitive.int))
        }
    }

    @Test
    fun should_readRepliesAsPinned() {
        section("replies").forEach { v ->
            val stored = YoinAlbumAbout.storedReply(v.string("raw")!!, v.getValue("hasServiceNotes").jsonPrimitive.boolean)
            val expected = v.obj("stored")
            assertEquals(v.string("name"), creditsOf(expected.obj("credits")), stored.credits)
            assertEquals(v.string("name"), expected.string("blurb"), stored.blurb)
            assertEquals(v.string("name"), expected.getValue("answered").jsonPrimitive.boolean, stored.answered)
        }
    }

    @Test
    fun should_readStoredRowsAsPinned() {
        section("content").forEach { v ->
            val rows = v.getValue("rows").jsonArray.map { it.jsonObject }.map { r ->
                AlbumAboutEntry(
                    provider = "subsonic",
                    albumId = "al-1",
                    albumDisplay = "A",
                    artistDisplay = "B",
                    kind = r.string("kind")!!,
                    entryKey = r.string("entryKey")!!,
                    promptText = r.string("promptText"),
                    titleText = r.string("titleText"),
                    answerText = r.string("answerText")!!,
                    createdAt = r.getValue("createdAt").jsonPrimitive.long,
                    updatedAt = 0,
                )
            }
            val content = albumAboutContent(rows)
            val expected = v.obj("content")
            assertEquals(creditsOf(expected.obj("credits")), content.credits)
            assertEquals(expected.string("blurb"), content.blurb)
            assertEquals(expected.getValue("asks").jsonArray.map { it.jsonObject.string("question") }, content.asks.map { it.question })
            assertEquals(expected.getValue("generated").jsonPrimitive.boolean, content.generated)
        }
        section("encodeCredits").forEach { v -> assertEquals(v.string("encoded"), creditsOf(v.obj("credits")).encode()) }
        section("questionKey").forEach { v -> assertEquals(v.string("key"), SongAboutEntry.normalize(v.string("question")!!)) }
    }

    @Test
    fun should_readApplesNotesAsPinned() {
        section("editorialNotes").forEach { v ->
            val notes = JsonObject(
                buildMap {
                    v.string("standard")?.let { put("standard", JsonPrimitive(it)) }
                    v.string("short")?.let { put("short", JsonPrimitive(it)) }
                },
            )
            assertEquals(v.string("notes"), AppleMusicSource.appleEditorialNotes(notes))
        }
    }

    @Test
    fun should_fillTheAiNameAsPinned_likeAndroidsStringFormat() {
        section("aiCopy").forEach { v ->
            val android = YoinAlbumAbout.tightenCjkSpaces(String.format(v.string("template")!!, v.string("name")!!))
            assertEquals(v.string("text"), android)
        }
    }

    @Test
    fun should_writeTheCreditsLineAsPinned_inEnglish() = checkCreditsLines("en", Locale.US)

    @Test
    @Config(qualifiers = "zh-rCN")
    fun should_writeTheCreditsLineAsPinned_inChinese() = checkCreditsLines("zh", Locale.SIMPLIFIED_CHINESE)

    private fun checkCreditsLines(language: String, locale: Locale) {
        val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        // The golden's words are the app's string resources.
        assertEquals(labelsOf(golden.getValue("labels").jsonObject.obj(language)), resourceAlbumCreditLabels(resources))
        val vectors = section("creditsLine").filter { it.string("labels") == language }
        assertTrue(vectors.isNotEmpty())
        vectors.forEach { v ->
            val name = v.string("name")
            val items = albumCreditItems(
                sourceLabels = v.strings("sourceLabels"),
                releaseDate = ReleaseDate.parseIso(v.string("releaseDate")),
                originalReleaseDate = ReleaseDate.parseIso(v.string("originalReleaseDate")),
                ai = creditsOf(v.obj("ai")),
            )
            val expectedItems = v.getValue("items").jsonArray.map { it.jsonObject }
            assertEquals(name, expectedItems.map { it.string("kind") }, items.map { it.kind.name })
            assertEquals(name, expectedItems.map { it.strings("names") }, items.map { it.names })
            assertEquals(name, expectedItems.map { it.string("text") }, items.map { it.text })
            assertEquals(name, expectedItems.map { it.string("date") }, items.map { it.date })
            val dateText = albumCreditDateText(items, locale)
            // A vector without the date text shows the ISO date: Android always has its own.
            if (items.any { it.date != null } && v.string("dateText") == null) {
                assertNotNull(name, dateText)
                return@forEach
            }
            assertEquals(name, v.string("dateText"), dateText)
            val line = albumCreditsLine(items, resources)
            val expected = v["line"] as? JsonObject
            assertEquals(name, expected?.string("text"), line?.text)
            assertEquals(name, expected?.string("plain"), line?.plain)
            assertEquals(name, expected?.strings("segments"), line?.segments)
            assertEquals(
                name,
                expected?.getValue("rows")?.jsonArray?.map { it.jsonObject.let { r -> r.string("label") to r.string("value") } },
                line?.rows?.map { it.label to it.value },
            )
        }
    }

    // ── the golden's inputs as the app's models ─────────────────────

    /** An app album whose grounding is [g] (what a service would have given). */
    private fun albumOf(g: AlbumAboutGrounding) = Album(
        id = MediaId.subsonic("al-1"),
        name = g.album,
        artist = g.artist,
        artistId = null,
        coverArt = null,
        songCount = g.tracks.size,
        durationSec = g.totalDurationSec,
        year = null,
        genre = g.genre,
        tracks = g.tracks.mapIndexed { i, title -> track(i, title) },
        releaseType = g.releaseType?.let(ReleaseType::valueOf),
        version = g.version,
        releaseDate = ReleaseDate.parseIso(g.releaseDate),
        recordLabels = g.labels,
        originalReleaseDate = ReleaseDate.parseIso(g.originalReleaseDate),
        editorialNotes = g.serviceNotes,
    )

    private fun track(index: Int, title: String) = Track(
        id = MediaId.subsonic("t-$index"),
        title = title,
        artist = null,
        artistId = null,
        album = null,
        albumId = null,
        coverArt = null,
        durationSec = 0,
        trackNumber = index + 1,
        year = null,
        genre = null,
        userRating = null,
    )

    private fun groundingOf(o: JsonObject) = AlbumAboutGrounding(
        album = o.string("album")!!,
        artist = o.string("artist")!!,
        version = o.string("version"),
        releaseDate = o.string("releaseDate"),
        originalReleaseDate = o.string("originalReleaseDate"),
        labels = o.strings("labels"),
        releaseType = o.string("releaseType"),
        genre = o.string("genre"),
        tracks = o.strings("tracks"),
        totalDurationSec = (o["totalDurationSec"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.int,
        serviceNotes = o.string("serviceNotes"),
    )

    private fun creditsOf(o: JsonObject) = AlbumAiCredits(
        label = o.strings("label"),
        producers = o.strings("producers"),
        mastering = o.strings("mastering"),
        mixing = o.strings("mixing"),
        recordedTime = o.string("recordedTime"),
        recordedStudios = o.strings("recordedStudios"),
        source = o.string("source"),
        firstReleased = o.string("firstReleased"),
    )

    private fun labelsOf(o: JsonObject) = AlbumCreditLabels(
        label = o.string("label")!!,
        producer = o.string("producer")!!,
        mastering = o.string("mastering")!!,
        mixing = o.string("mixing")!!,
        recorded = o.string("recorded")!!,
        source = o.string("source")!!,
        firstReleased = o.string("firstReleased")!!,
        nameSeparator = o.string("nameSeparator")!!,
        timeAndPlace = o.string("timeAndPlace")!!,
    )

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun JsonObject.strings(key: String): List<String> = (this[key] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()

    private fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject
}
