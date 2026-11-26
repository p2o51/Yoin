package com.gpo.yoin.ui.nowplaying

import android.content.Context
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.R
import com.gpo.yoin.data.local.SongAboutEntry
import com.gpo.yoin.data.local.SongNote
import com.gpo.yoin.data.model.LyricCredit
import com.gpo.yoin.data.model.LyricCreditRole
import com.gpo.yoin.data.model.LyricCredits
import com.gpo.yoin.shared.notes.YoinNoteReader
import com.gpo.yoin.shared.share.YoinShareCards
import com.gpo.yoin.ui.component.markdownBoldAnnotatedString
import java.io.File
import kotlin.math.round
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Android answers every share-card golden vector (shared/src/commonTest/resources/golden/share-cards.json) the way
 * iOS and HarmonyOS are tested against, through the app's own paths: Now Playing's [LyricLine]s, the Room rows
 * ([SongAboutEntry], [SongNote]), the lyrics' [LyricCredits] and the server's [SpineSourceFacts] (with every file
 * fact filled in, none of which may reach the image), the app's string resources (en, zh-CN), the layout store's
 * SharedPreferences, a Compose colour scheme. Before the move (2026-11-26) this same test ran against the app's own
 * code (with the composables' inline arithmetic copied in where it now calls [YoinShareCards]): all 275 vectors
 * equal, so the file is also what Android did before :shared took over.
 */
@RunWith(RobolectricTestRunner::class)
class ShareCardsPinTest {

    private val golden: JsonObject = Json.parseToJsonElement(
        File(
            generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile },
            "shared/src/commonTest/resources/golden/share-cards.json",
        ).readText(),
    ).jsonObject

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun section(name: String) = golden.getValue(name).jsonArray.map { it.jsonObject }.also { assertTrue(name, it.isNotEmpty()) }

    @Test
    fun should_gateVerticalAsPinned() {
        section("isCjk").forEach { v -> assertEquals(v.name, v.bool("cjk"), isCjkLyric(v.string("text")!!)) }
    }

    @Test
    fun should_offerTabsAndLayoutsAsPinned_throughTheLayoutStore() {
        section("content").forEach { v ->
            val content = contentOf(v.obj("content"))
            assertEquals(v.name, v.strings("tabs"), content.tabs.map { it.name })
            val prefs = context.getSharedPreferences("yoin_share_layouts", Context.MODE_PRIVATE)
            val editor = prefs.edit().clear()
            v.obj("remembered").forEach { (key, value) ->
                (value as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { editor.putString(key, it.content) }
            }
            editor.commit()
            val store = SharedPrefsShareLayoutStore(context)
            ShareTab.entries.forEach { tab ->
                assertEquals(v.name, v.obj("layouts").strings(tab.name), content.layoutsFor(tab).map { it.name })
                assertEquals(v.name, v.obj("opens").string(tab.name), content.resolveLayout(tab, store.layoutFor(tab))?.name)
            }
        }
        section("layoutStore").forEach { v ->
            val prefs = context.getSharedPreferences("yoin_share_layouts", Context.MODE_PRIVATE)
            val tab = ShareTab.valueOf(v.string("tab")!!)
            val editor = prefs.edit().clear()
            v.string("value")?.let { editor.putString(tab.name, it) }
            editor.commit()
            assertEquals(v.name, v.string("layout"), SharedPrefsShareLayoutStore(context).layoutFor(tab)?.name)
        }
    }

    @Test
    fun should_pickLyricsAsPinned() {
        section("currentLine").forEach { v ->
            val pick = currentLyricsPick(v.array("lines").map { lineOf(it.jsonObject) }, v.long("positionMs"), v.bool("showTranslation"))
            assertEquals(v.name, v["pick"], pick?.let(::pickJson) ?: JsonNull)
        }
        section("passageStarts").forEach { v ->
            val lyrics = List(v.int("lineCount")) { LyricLine(null, "l$it") }
            val starts = lyricPassageStarts(lyrics, v.array("selected").map { it.jsonPrimitive.int }.toSet())
            assertEquals(v.name, v.array("starts").map { it.jsonPrimitive.int }, starts.sorted())
        }
        // The share sheet sends YoinShareCards.caption with the image (ShareSheet.kt).
        section("caption").forEach { v ->
            assertEquals(v.name, v.string("caption"), YoinShareCards.caption(v.string("title"), v.string("artist")))
        }
        section("cardLineSize").forEach { v ->
            eq(v.name, v.double("sizeSp"), num(lyricsCardLineSize(v.strings("lines").map { LyricLine(null, it) }).value))
        }
    }

    @Test
    fun should_drawPostersAndPlayerBarsAsPinned() {
        section("poster").forEach { v ->
            val poster = posterLines(pickOf(v.obj("pick")))
            val expected = v["poster"] as? JsonObject
            if (expected == null) {
                assertEquals(v.name, null, poster)
                return@forEach
            }
            poster!!
            assertEquals(v.name, expected.getValue("lead"), lineJson(poster.lead))
            assertEquals(v.name, expected.getValue("rest"), JsonArray(poster.rest.map(::lineJson)))
            assertEquals(v.name, expected.string("translation"), poster.translation)
            eq(v.name, expected.double("leadSizeSp"), num(posterLeadSizeSp(poster.lead.text)))
            assertEquals(v.name, expected.string("leadTime"), poster.lead.startMs?.let(::shareTime))
        }
        section("playerExcerpt").forEach { v ->
            val excerpt = playerExcerpt(pickOf(v.obj("pick")), v.long("durationMs"))
            val expected = v["excerpt"] as? JsonObject
            if (expected == null) {
                assertEquals(v.name, null, excerpt)
                return@forEach
            }
            excerpt!!
            eq(v.name, expected.double("start"), num(excerpt.start))
            eq(v.name, expected.double("end"), num(excerpt.end))
            eq(v.name, expected.double("centre"), num((excerpt.start + excerpt.end) / 2f))
            assertEquals(v.name, expected.long("startMs"), excerpt.startMs)
            assertEquals(v.name, expected.long("endMs"), excerpt.endMs)
            assertEquals(v.name, expected.string("startTime"), shareTime(excerpt.startMs))
            assertEquals(v.name, expected.string("endTime"), shareTime(excerpt.endMs))
        }
    }

    @Test
    fun should_setVerticalTextAsPinned() {
        section("verticalPlan").forEach { v ->
            val plan = verticalLyricsPlan(v.strings("lines"), v.float("columnHeightDp"), v.float("widthDp"))
            assertPlan(v.name, v.obj("plan"), plan)
        }
        section("verticalLyrics").forEach { v ->
            val pick = pickOf(v.obj("pick"))
            assertEquals(v.name, v.strings("gist"), verticalGist(pick))
            assertPlan(v.name, v.obj("plan"), verticalLyricsPlanFor(pick))
        }
        section("verticalText").forEach { v ->
            val columns = verticalTextColumns(v.string("text")!!, v.float("sizeSp"), v.float("columnHeightDp"))
            assertEquals(v.name, v["columns"], columns?.let { JsonArray(it.map(::JsonPrimitive)) } ?: JsonNull)
        }
        section("verticalForm").forEach { v ->
            assertEquals(v.name, v.string("vertical"), v.string("text")!!.map(::verticalForm).joinToString(""))
        }
    }

    @Test
    fun should_setAboutsBoldAsPinned_throughNowPlayingsRenderer() {
        // The About layouts draw values and the paragraph through markdownBoldAnnotatedString (ShareCards.kt).
        section("boldRuns").forEach { v ->
            val styled = markdownBoldAnnotatedString(v.string("text")!!)
            val runs = mutableListOf<Pair<String, Boolean>>()
            var at = 0
            fun add(text: String, bold: Boolean) {
                if (text.isEmpty()) return
                val last = runs.lastOrNull()
                if (last != null && last.second == bold && !bold) runs[runs.lastIndex] = (last.first + text) to false else runs += text to bold
            }
            styled.spanStyles.sortedBy { it.start }.forEach { span ->
                add(styled.text.substring(at, span.start), false)
                add(styled.text.substring(span.start, span.end), span.item.fontWeight == FontWeight.Bold)
                at = span.end
            }
            add(styled.text.substring(at), false)
            val expected = v.array("runs").map { it.jsonObject.string("text")!! to it.jsonObject.bool("bold") }
            assertEquals(v.name, expected, runs)
        }
    }

    private fun assertPlan(name: String, expected: JsonObject, plan: VerticalPlan) {
        assertEquals(name, expected.strings("columns"), plan.columns)
        eq(name, expected.double("fontSizeSp"), num(plan.fontSizeSp))
        assertEquals(name, expected.int("charsPerColumn"), plan.charsPerColumn)
    }

    @Test
    fun should_shareAboutAsPinned_inEnglish() = checkAbout("en")

    @Test
    @Config(qualifiers = "zh-rCN")
    fun should_shareAboutAsPinned_inChinese() = checkAbout("zh")

    private fun checkAbout(language: String) {
        val resources = context.resources
        val labels = resourceAboutLabels(resources)
        val review = resources.getString(R.string.np_about_review)
        // The golden's words are the app's string resources.
        val l = golden.getValue("labels").jsonObject.obj(language)
        assertEquals(l.string("lyricist"), labels.about(SongAboutEntry.CANON_LYRICIST))
        assertEquals(l.string("composer"), labels.about(SongAboutEntry.CANON_COMPOSER))
        assertEquals(l.string("arranger"), labels.arranger)
        assertEquals(l.string("producer"), labels.about(SongAboutEntry.CANON_PRODUCER))
        assertEquals(l.string("creationTime"), labels.about(SongAboutEntry.CANON_CREATION_TIME))
        assertEquals(l.string("creationLocation"), labels.about(SongAboutEntry.CANON_CREATION_LOCATION))
        assertEquals(l.string("year"), labels.year)
        assertEquals(l.string("review"), review)

        // About's sign as AiSign writes it: the app's template and provider names, Chinese names tightened.
        val template = resources.getString(R.string.np_share_ai_sign)
        val signs = section("aiSign").filter { it.string("template") == template }
        assertTrue(signs.isNotEmpty())
        signs.forEach { v -> assertEquals(v.name, v.string("sign"), YoinShareCards.aiSign(template, v.string("provider")!!)) }
        val providerNames = listOf(R.string.ai_provider_gemini, R.string.ai_provider_deepseek, R.string.ai_provider_qwen, R.string.np_share_ai_generic)
            .map(resources::getString)
        providerNames.forEach { name -> assertTrue(name, signs.any { it.string("provider") == name }) }

        val vectors = section("about").filter { it.string("labels") == language }
        assertTrue(vectors.isNotEmpty())
        vectors.forEach { v ->
            val entries = v.array("entries").map { entryOf(it.jsonObject) }
            val sources = v.obj("sources")
            val credits = LyricCredits(
                roles = listOfNotNull(
                    sources.string("lyricist")?.let { LyricCredit("作词", it, LyricCreditRole.Lyricist) },
                    sources.string("composer")?.let { LyricCredit("作曲", it, LyricCreditRole.Composer) },
                    sources.string("arranger")?.let { LyricCredit("编曲", it, LyricCreditRole.Arranger) },
                    sources.string("producer")?.let { LyricCredit("制作人", it, LyricCreditRole.Producer) },
                    // A role About doesn't headline never reaches the image.
                    LyricCredit("混音", "Mixer Name"),
                ),
                notices = listOf("© 2003 Notice"),
            )
            // Every file fact the server has; only the composer and the year may cross over.
            val facts = SpineSourceFacts(
                format = "FLAC",
                bitRateKbps = 900,
                samplingRateHz = 44_100,
                bitDepth = 16,
                serverRating = 4,
                genre = "Pop",
                year = (sources["year"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.int,
                trackNumber = 3,
                discNumber = 2,
                durationSec = 269,
                channels = 2,
                sizeBytes = 9_437_184L,
                playCount = 14L,
                bpm = 120,
                composer = sources.string("serverComposer"),
                comment = "A comment",
                added = "2024-01-01T00:00:00Z",
                path = "/music/a.flac",
            )
            val focus = v.string("focusKind")?.let { kind -> entry(kind, v.string("focusKey")!!, "", null, null) }
            val pick = aboutSharePick(entries, credits, facts, labels, review, focus)
            assertEquals(v.name, v["about"], pick?.let(::aboutJson) ?: JsonNull)
            assertEquals(v.name, v.bool("aiContent"), pick?.aiContent == true)
            val song = ShareSong("s", "T", "A", null)
            assertEquals(v.name, v.bool("hasTab"), ShareContent(song, about = pick).has(ShareTab.About))
        }
    }

    @Test
    fun should_pickTheNoteAsPinned() {
        section("note").forEach { v ->
            val notes = v.array("notes").map { o ->
                val n = o.jsonObject
                SongNote(
                    id = n.string("noteId")!!,
                    trackId = "t",
                    content = n.string("content")!!,
                    createdAt = n.long("createdAt"),
                    updatedAt = n.long("createdAt"),
                    title = "T",
                    artist = "A",
                    positionMs = n.longOrNull("positionMs"),
                )
            }
            val pick = noteSharePick(notes, v.string("noteId"), v.long("positionMs"))
            val expected = v["note"] as? JsonObject
            if (expected == null) {
                assertEquals(v.name, null, pick)
                return@forEach
            }
            pick!!
            assertEquals(v.name, expected.string("noteId"), pick.noteId)
            assertEquals(v.name, expected.string("content"), pick.content)
            assertEquals(v.name, expected.longOrNull("positionMs"), pick.positionMs)
            assertEquals(v.name, expected.long("createdAt"), pick.createdAt)
            assertEquals(v.name, v.string("time"), pick.positionMs?.let(::shareTime))
            val fraction = timelineFraction(pick.positionMs, v.long("durationMs"))
            assertEquals(v.name, v["timelineFraction"], fraction?.let { JsonPrimitive(num(it)) } ?: JsonNull)
            // The card draws the note through NoteMarkup (YoinNoteReader is its flat reading).
            assertEquals(v.name, v.array("lines").size, YoinNoteReader.read(pick.content).size)
        }
        section("time").forEach { v -> assertEquals(v.name, v.string("text"), shareTime(v.long("ms"))) }
    }

    @Test
    fun should_colourEachToneAsPinned() {
        val scheme = lightColorScheme(
            primary = Color(0xFF6750A4),
            onPrimary = Color(0xFF000011),
            primaryContainer = Color(0xFF000012),
            onPrimaryContainer = Color(0xFF000013),
            tertiary = Color(0xFF8BD8B5),
            onTertiary = Color(0xFF000015),
            tertiaryContainer = Color(0xFF000016),
            onTertiaryContainer = Color(0xFF000017),
        )
        fun resolve(ink: JsonObject): Color {
            val base = when (ink.string("source")!!) {
                "Primary" -> scheme.primary
                "OnPrimary" -> scheme.onPrimary
                "PrimaryContainer" -> scheme.primaryContainer
                "OnPrimaryContainer" -> scheme.onPrimaryContainer
                "Tertiary" -> scheme.tertiary
                "OnTertiary" -> scheme.onTertiary
                "TertiaryContainer" -> scheme.tertiaryContainer
                "OnTertiaryContainer" -> scheme.onTertiaryContainer
                "White" -> Color.White
                else -> error(ink)
            }
            val tone = (ink["tone"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.int ?: return base
            val retoned = section("retone").first { it.string("argb") == hex(base.toArgb()) && it.int("tone") == tone }
            return Color(argbOf(retoned.string("retoned")!!))
        }
        val roleTones = golden.getValue("constants").jsonObject.obj("roleTones")
        ShareRole.entries.forEach { role -> assertEquals(roleTones.strings(role.name), role.tones().map { it.name }) }
        section("tones").forEach { v ->
            val colors = ShareTone.valueOf(v.string("tone")!!).colors(scheme, ShareRole.valueOf(v.string("role")!!))
            assertEquals(v.name, resolve(v.obj("card")), colors.card)
            assertEquals(v.name, resolve(v.obj("content")), colors.content)
            assertEquals(v.name, resolve(v.obj("backdrop")), colors.backdrop)
            assertEquals(v.name, colors.content.copy(alpha = 0.72f), colors.secondary)
        }
        section("retone").forEach { v ->
            val role = Color(argbOf(v.string("argb")!!))
            val paper = ShareTone.Paper.colors(lightColorScheme(primary = role), ShareRole.Own)
            val retoned = if (v.int("tone") == 40) paperInk(role) else paper.backdrop
            if (v.int("tone") == 40) assertEquals(v.name, paperInk(role), paper.content)
            assertEquals(v.name, v.string("retoned"), hex(retoned.toArgb()))
        }
    }

    @Test
    fun should_zoomAndExportAsPinned() {
        golden.getValue("constants").jsonObject.getValue("layouts").jsonArray.forEach { e ->
            val o = e.jsonObject
            val layout = ShareLayout.valueOf(o.string("layout")!!)
            assertEquals(o.string("tab"), layout.tab.name)
            assertEquals(o.bool("storyIsPoster"), layout.storyIsPoster)
            eq(layout.name, o.double("storyMaxZoom"), num(storyMaxZoomOf(layout)))
        }
        section("storyZoom").forEach { v ->
            val limit = v.float("fitsUpTo")
            val tried = mutableListOf<Float>()
            val zoom = lyricsStoryZoom(v.float("maxZoom")) { z ->
                tried += z
                z <= limit
            }
            eq(v.name, v.double("zoom"), num(zoom))
            assertEquals(v.name, v.array("tried").map { it.jsonPrimitive.double }, tried.map(::num))
        }
        section("storyFits").forEach { v ->
            assertEquals(v.name, v.bool("fits"), storyFits(v.int("heightPx"), v.float("zoom"), v.int("frameHeightPx")))
        }
        section("exportSize").forEach { v ->
            val format = LyricsShareFormat.valueOf(v.string("format")!!)
            val size = lyricsShareExportSize(format, v.float("width"), v.float("height"))
            assertEquals(v.name, v.int("exportWidth"), size.width)
            assertEquals(v.name, v.int("exportHeight"), size.height)
        }
        section("fitScale").forEach { v ->
            val scale = lyricsShareFitScale(v.int("childWidth"), v.int("childHeight"), v.int("maxWidth"), v.int("maxHeight"), v.float("maxScale"))
            eq(v.name, v.double("scale"), num(scale))
        }
    }

    @Test
    fun should_nameTheFileAsPinned_inEnglish() = checkFileNames()

    @Test
    @Config(qualifiers = "zh-rCN")
    fun should_nameTheFileAsPinned_inChinese() = checkFileNames()

    private fun checkFileNames() {
        section("fileName").forEach { v ->
            assertEquals(v.name, v.string("fileName"), shareFileName(v.string("title"), v.long("nowMs")))
            assertEquals(v.name, v.string("fileName"), shareFileName(v.string("title"), v.long("nowMs"), context.resources))
        }
    }

    // ── what the composables call (ShareCards.kt, LyricsShareCard.kt) ─

    private fun verticalGist(pick: LyricsSharePick): List<String> = YoinShareCards.verticalGist(pick.toShared())

    private fun verticalLyricsPlanFor(pick: LyricsSharePick): VerticalPlan = YoinShareCards.verticalLyricsPlan(pick.toShared())

    private fun verticalTextColumns(text: String, sizeSp: Float, columnHeightDp: Float): List<String>? =
        YoinShareCards.verticalTextColumns(text, sizeSp, columnHeightDp)

    private fun timelineFraction(positionMs: Long?, durationMs: Long): Float? = YoinShareCards.timelineFraction(positionMs, durationMs)

    private fun storyFits(heightPx: Int, zoom: Float, frameHeightPx: Int): Boolean = YoinShareCards.storyFits(heightPx, zoom, frameHeightPx)

    private fun storyMaxZoomOf(layout: ShareLayout): Float = YoinShareCards.storyMaxZoom(layout)

    // ── codecs ───────────────────────────────────────────────────────

    private fun eq(name: String, expected: Double, actual: Double) = assertEquals(name, expected, actual, 0.0)

    private fun num(value: Float): Double = round(value.toDouble() * 1e6) / 1e6

    private fun hex(argb: Int): String = "#" + argb.toUInt().toString(16).uppercase().padStart(8, '0')

    private fun argbOf(hex: String): Int = hex.removePrefix("#").toLong(16).toInt()

    private fun lineOf(o: JsonObject) = LyricLine(startMs = o.longOrNull("startMs"), text = o.string("text")!!, translation = o.string("translation"))

    private fun lineJson(l: LyricLine) = JsonObject(
        linkedMapOf(
            "startMs" to (l.startMs?.let(::JsonPrimitive) ?: JsonNull),
            "text" to JsonPrimitive(l.text),
            "translation" to (l.translation?.let(::JsonPrimitive) ?: JsonNull),
        ),
    )

    private fun pickOf(o: JsonObject) = LyricsSharePick(
        lines = o.array("lines").map { lineOf(it.jsonObject) },
        passageStarts = o.array("passageStarts").map { it.jsonPrimitive.int }.toSet(),
        showTranslation = o.bool("showTranslation"),
    )

    private fun pickJson(p: LyricsSharePick) = JsonObject(
        linkedMapOf(
            "lines" to JsonArray(p.lines.map(::lineJson)),
            "passageStarts" to JsonArray(p.passageStarts.sorted().map(::JsonPrimitive)),
            "showTranslation" to JsonPrimitive(p.showTranslation),
        ),
    )

    private fun creditOf(o: JsonObject) = ShareCredit(o.string("label")!!, o.string("value")!!, ai = o.bool("ai"), fact = o.bool("fact"))

    private fun aboutOf(o: JsonObject) = AboutSharePick(o.array("credits").map { creditOf(it.jsonObject) }, o.string("heading")!!, o.string("paragraph"))

    private fun aboutJson(a: AboutSharePick) = JsonObject(
        linkedMapOf(
            "credits" to JsonArray(
                a.credits.map { c ->
                    JsonObject(
                        linkedMapOf(
                            "label" to JsonPrimitive(c.label),
                            "value" to JsonPrimitive(c.value),
                            "ai" to JsonPrimitive(c.ai),
                            "fact" to JsonPrimitive(c.fact),
                        ),
                    )
                },
            ),
            "heading" to JsonPrimitive(a.heading),
            "paragraph" to (a.paragraph?.let(::JsonPrimitive) ?: JsonNull),
        ),
    )

    private fun contentOf(o: JsonObject): ShareContent {
        val s = o.obj("song")
        return ShareContent(
            song = ShareSong(s.string("songId")!!, s.string("title")!!, s.string("artist")!!, s.string("coverArtUrl"), s.string("albumName")!!, s.long("durationMs")),
            lyrics = (o["lyrics"] as? JsonObject)?.let(::pickOf),
            about = (o["about"] as? JsonObject)?.let(::aboutOf),
            note = (o["note"] as? JsonObject)?.let { n ->
                NoteSharePick(n.string("noteId")!!, n.string("content")!!, n.longOrNull("positionMs"), n.long("createdAt"))
            },
        )
    }

    private fun entry(kind: String, key: String, answer: String, title: String?, prompt: String?) = SongAboutEntry(
        titleKey = "t",
        artistKey = "a",
        albumKey = "al",
        titleDisplay = "T",
        artistDisplay = "A",
        albumDisplay = "Al",
        kind = kind,
        entryKey = key,
        promptText = prompt,
        titleText = title,
        answerText = answer,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun entryOf(o: JsonObject) =
        entry(o.string("kind")!!, o.string("entryKey")!!, o.string("answerText")!!, o.string("titleText"), o.string("promptText"))

    private val JsonObject.name: String get() = string("name").orEmpty()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun JsonObject.strings(key: String): List<String> = (this[key] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()

    private fun JsonObject.bool(key: String): Boolean = getValue(key).jsonPrimitive.boolean

    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int

    private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long

    private fun JsonObject.longOrNull(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.long

    private fun JsonObject.double(key: String): Double = getValue(key).jsonPrimitive.double

    private fun JsonObject.float(key: String): Float = getValue(key).jsonPrimitive.double.toFloat()

    private fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject

    private fun JsonObject.array(key: String): JsonArray = getValue(key).jsonArray
}
