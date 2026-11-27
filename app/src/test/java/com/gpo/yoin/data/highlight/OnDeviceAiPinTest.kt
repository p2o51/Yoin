package com.gpo.yoin.data.highlight

import com.gpo.yoin.data.local.SongAboutEntry
import com.gpo.yoin.shared.ondevice.AboutFollowUpPrompt
import com.gpo.yoin.shared.ondevice.HomeGreetingPrompt
import com.gpo.yoin.shared.ondevice.HomeGreetingSchedule
import com.gpo.yoin.shared.reference.RefAboutFollowUpPrompt
import com.gpo.yoin.shared.reference.RefDailyHomeGreetings
import com.gpo.yoin.shared.reference.RefHomeGreetingPrompt
import com.gpo.yoin.shared.reference.RefHomeGreetingSchedule
import com.gpo.yoin.shared.reference.RefHomeGreetingSlot
import com.gpo.yoin.ui.home.HomeDayPart
import com.gpo.yoin.ui.home.homeDayPartAt
import com.gpo.yoin.ui.home.toGreetingSlot
import java.io.File
import java.time.DayOfWeek
import java.time.Month
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android answers the on-device AI golden vectors (shared/src/commonTest/resources/golden/ondevice-ai.json), which
 * iOS is tested against, twice: through the app's own paths (the `java.time` overloads, the Room rows mapped to the
 * shared entry, Home's day parts, `NanoBudget.dayOf`) and through the pre-move app code kept verbatim under
 * `shared/reference` (RefHomeGreetingPrompt, RefAboutFollowUpPrompt, RefHomeGreetingSchedule). Both must give what
 * the file says, so Gemini Nano gets exactly the prompts it got before the words moved to :shared (2026-11-27).
 */
class OnDeviceAiPinTest {

    private val golden: JsonObject = Json.parseToJsonElement(
        File(
            generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile },
            "shared/src/commonTest/resources/golden/ondevice-ai.json",
        ).readText(),
    ).jsonObject

    private fun section(name: String): List<JsonObject> = golden.getValue(name).jsonArray.map { it.jsonObject }

    @Test
    fun should_buildTheSameGreetingPrompts_when_comparedWithThePreMoveCode() {
        section("greetingInstructions").forEach { v ->
            val expected = v.string("instructions")
            assertEquals(expected, HomeGreetingPrompt.systemInstruction(v.bool("hasAnyCue"), v.string("localeTag")!!))
            assertEquals(expected, RefHomeGreetingPrompt.systemInstruction(v.bool("hasAnyCue"), v.string("localeTag")!!))
        }
        section("greetingUserPrompt").forEach { v ->
            val day = DayOfWeek.of(v.int("dayOfWeek"))
            val month = v.int("month").takeIf { it in 1..12 }?.let(Month::of)
            val lang = v.string("localeTag")!!
            val avoid = v.strings("avoidRecent")
            val cues = cues(v.obj("cues"))
            val ref = refCues(v.obj("cues"))
            assertEquals(v.string("prompt"), HomeGreetingPrompt.userPrompt(cues, day, month, lang, avoid))
            assertEquals(v.string("prompt"), RefHomeGreetingPrompt.userPrompt(ref, day, month, lang, avoid))
            assertEquals(v.string("plainPrompt"), HomeGreetingPrompt.buildPrompt(cues, day, month, lang, avoid))
            assertEquals(v.string("plainPrompt"), RefHomeGreetingPrompt.buildPrompt(ref, day, month, lang, avoid))
            assertEquals(v.string("fingerprint"), cues.fingerprint())
            assertEquals(v.string("fingerprint"), ref.fingerprint())
        }
    }

    @Test
    fun should_checkGreetingsTheSame_when_comparedWithThePreMoveCode() {
        section("greetingSanitize").forEach { v ->
            assertEquals(v.toString(), v.string("clean"), HomeGreetingPrompt.sanitize(v.string("raw"), v.string("localeTag")!!))
            assertEquals(v.toString(), v.string("clean"), RefHomeGreetingPrompt.sanitize(v.string("raw"), v.string("localeTag")!!))
        }
        section("greetingValidate").forEach { v ->
            val args = listOf(v.string("morning"), v.string("afternoon"), v.string("evening"))
            val app = HomeGreetingPrompt.validateSlots(
                args[0], args[1], args[2], v.long("day"), v.string("localeTag")!!, v.string("profileId")!!,
                cues(v.obj("cues")), v.strings("avoidRecent"),
            )
            val ref = RefHomeGreetingPrompt.validateSlots(
                args[0], args[1], args[2], v.long("day"), v.string("localeTag")!!, v.string("profileId")!!,
                refCues(v.obj("cues")), v.strings("avoidRecent"),
            )
            assertEquals(v.toString(), v["result"], json(app))
            assertEquals(v.toString(), v["result"], json(ref))
        }
        section("greetingParse").forEach { v ->
            val app = HomeGreetingPrompt.parse(
                v.string("answer"), v.long("day"), v.string("localeTag")!!, v.string("profileId")!!,
                cues(v.obj("cues")), v.strings("avoidRecent"),
            )
            val ref = RefHomeGreetingPrompt.parse(
                v.string("answer"), v.long("day"), v.string("localeTag")!!, v.string("profileId")!!,
                refCues(v.obj("cues")), v.strings("avoidRecent"),
            )
            assertEquals(v.toString(), v["result"], json(app))
            assertEquals(v.toString(), v["result"], json(ref))
        }
        section("visualWidth").forEach { v ->
            assertEquals(v.int("width"), HomeGreetingPrompt.visualWidth(v.string("text")!!))
            assertEquals(v.int("width"), RefHomeGreetingPrompt.visualWidth(v.string("text")!!))
        }
        section("conflictsWithNoon").forEach { v ->
            assertEquals(v.bool("conflicts"), HomeGreetingPrompt.conflictsWithNoon(v.string("text")!!))
            assertEquals(v.bool("conflicts"), RefHomeGreetingPrompt.conflictsWithNoon(v.string("text")!!))
        }
    }

    @Test
    fun should_scheduleGreetingsTheSame_when_comparedWithThePreMoveStore() {
        section("slotAtHour").forEach { v ->
            val hour = v.int("hour")
            assertEquals(v.string("slot"), RefHomeGreetingSchedule.currentSlotAt(hour).name)
            assertEquals(v.string("slot"), HomeGreetingSchedule.slotAtHour(hour).name)
            // Home's title maps its day part onto the same slot, and says noon exactly when the schedule does.
            assertEquals(v.string("slot"), homeDayPartAt(hour).toGreetingSlot().name)
            assertEquals(v.bool("isNoon"), homeDayPartAt(hour) == HomeDayPart.Noon)
        }
        section("dayOf").forEach { v ->
            assertEquals(v.long("day"), NanoBudget.dayOf(v.long("epochMs"), v.long("utcOffsetMs").toInt()))
        }
        section("cachedText").forEach { v ->
            assertEquals(
                v.toString(),
                v.string("text"),
                RefHomeGreetingSchedule.cachedFor(
                    refGreetings(v["cached"]), refSlot(v, "slot"), v.long("today"), v.string("lang")!!, v.bool("isNoon"),
                ),
            )
        }
        section("needsGeneration").forEach { v ->
            assertEquals(
                v.toString(),
                v.bool("needs"),
                RefHomeGreetingSchedule.needsGeneration(
                    refGreetings(v["cached"]), v.long("today"), v.string("lang")!!, refSlot(v, "currentSlot"),
                    v.string("lastAttempt"),
                ),
            )
        }
        section("shouldGenerate").forEach { v ->
            assertEquals(
                v.toString(),
                v.bool("generate"),
                RefHomeGreetingSchedule.shouldGenerate(
                    refGreetings(v["cached"]), v.long("today"), v.string("lang")!!, refSlot(v, "currentSlot"),
                    v.string("cuesFingerprint")!!, v.string("lastAttempt"),
                ),
            )
            assertEquals(
                v.string("attemptValue"),
                RefHomeGreetingSchedule.attemptValue(v.long("today"), v.string("lang")!!, v.string("cuesFingerprint")!!),
            )
        }
        section("merged").forEach { v ->
            val fresh = refGreetings(v["fresh"])!!
            val existing = refGreetings(v["existing"])
            val fp = v.string("cuesFingerprint")!!
            val ref = if (existing != null) {
                RefHomeGreetingSchedule.mergeUpcomingSlots(existing, fresh, refSlot(v, "currentSlot"), fp)
            } else {
                fresh.copy(cuesFingerprint = fp, refreshCount = 0)
            }
            assertEquals(v.toString(), v["result"], json(ref))
        }
        section("history").forEach { v ->
            assertEquals(v.strings("recent"), RefHomeGreetingSchedule.loadRecentHistory(v.string("raw")!!, v.long("today")))
            assertEquals(
                v.string("recorded"),
                RefHomeGreetingSchedule.recordRecentHistory(v.string("raw")!!, v.long("today"), v.strings("phrases")),
            )
        }
        section("pickCues").forEach { v ->
            val recent = v.array("recent").map {
                RefHomeGreetingSchedule.Row(it.string("artist")!!, it.string("album")!!, it.long("playedAt"))
            }
            val rediscover = v.array("rediscover").map { it.string("artist")!! to it.string("album")!! }
            val albums = v.array("albums").map {
                RefHomeGreetingSchedule.Agg(it.string("artist")!!, it.string("album")!!, it.long("lastPlayedAt"))
            }
            val ref = RefHomeGreetingSchedule.loadSlotCues(recent, rediscover, albums, v.long("now"))
            assertEquals(v.toString(), v["cues"], cuesJson(ref))
            assertEquals(v.string("fingerprint"), ref.fingerprint())
        }
    }

    @Test
    fun should_suggestFollowUpsTheSame_when_comparedWithThePreMoveCode() {
        section("followUpPrepare").forEach { v ->
            val entries = v.array("entries").map(::entry)
            val asked = v.strings("asked")
            val lang = v.string("localeTag")!!
            assertEquals(v.string("instructions"), AboutFollowUpPrompt.systemInstruction(lang))
            assertEquals(v.string("instructions"), RefAboutFollowUpPrompt.systemInstruction(lang))
            val app = AboutFollowUpPrompt.prepare(entries, asked, lang)
            val ref = RefAboutFollowUpPrompt.prepare(entries, asked, lang)
            val expected = v["prepared"]
            if (expected == null || expected is JsonNull) {
                assertEquals(null, app)
                assertEquals(null, ref)
                return@forEach
            }
            val e = expected.jsonObject
            requireNotNull(app)
            requireNotNull(ref)
            listOf(
                Triple(app.title, ref.title, e.string("title")),
                Triple(app.artist, ref.artist, e.string("artist")),
                Triple(app.context, ref.context, e.string("context")),
                Triple(app.localeTag, ref.localeTag, e.string("localeTag")),
                Triple(app.fingerprint, ref.fingerprint, e.string("fingerprint")),
                Triple(AboutFollowUpPrompt.userPrompt(app), RefAboutFollowUpPrompt.userPrompt(ref), e.string("userPrompt")),
                Triple(AboutFollowUpPrompt.buildPrompt(app), RefAboutFollowUpPrompt.buildPrompt(ref), e.string("plainPrompt")),
            ).forEach { (a, r, x) ->
                assertEquals(x, a)
                assertEquals(x, r)
            }
            assertEquals(e.strings("asked"), app.asked)
            assertEquals(e.strings("asked"), ref.asked)
        }
        section("followUpParse").forEach { v ->
            val args = Triple(v.string("answer"), v.strings("asked"), v.string("localeTag")!!)
            assertEquals(v.toString(), v.strings("questions"), AboutFollowUpPrompt.parse(args.first, args.second, args.third))
            assertEquals(v.toString(), v.strings("questions"), RefAboutFollowUpPrompt.parse(args.first, args.second, args.third))
        }
        section("followUpValidate").forEach { v ->
            val args = Triple(v.strings("candidates"), v.strings("asked"), v.string("localeTag")!!)
            assertEquals(v.strings("questions"), AboutFollowUpPrompt.validateQuestions(args.first, args.second, args.third))
            assertEquals(v.strings("questions"), RefAboutFollowUpPrompt.validateQuestions(args.first, args.second, args.third))
        }
        section("followUpMerge").forEach { v ->
            val args = Triple(v.strings("suggested"), v.strings("fallback"), v.strings("asked"))
            assertEquals(v.strings("merged"), AboutFollowUpPrompt.mergeWithFallback(args.first, args.second, args.third))
            assertEquals(v.strings("merged"), RefAboutFollowUpPrompt.mergeWithFallback(args.first, args.second, args.third))
        }
    }

    @Test
    fun should_coverEverySection() {
        listOf(
            "greetingInstructions", "greetingUserPrompt", "greetingSanitize", "greetingValidate", "greetingParse",
            "visualWidth", "conflictsWithNoon", "slotAtHour", "dayOf", "cachedText", "needsGeneration",
            "shouldGenerate", "merged", "history", "pickCues", "followUpPrepare", "followUpParse", "followUpValidate",
            "followUpMerge",
        ).forEach { assertTrue(it, section(it).isNotEmpty()) }
    }

    // ── mapping ─────────────────────────────────────────────────────────────────────────────────────────

    private fun entry(o: JsonObject) = SongAboutEntry(
        titleKey = "t", artistKey = "a", albumKey = "", titleDisplay = o.string("titleDisplay")!!,
        artistDisplay = o.string("artistDisplay")!!, albumDisplay = "", kind = o.string("kind")!!,
        entryKey = o.string("entryKey")!!, promptText = o.string("promptText"), titleText = o.string("titleText"),
        answerText = o.string("answerText")!!, createdAt = 0L, updatedAt = 0L,
    )

    private fun play(e: JsonElement?) =
        (e as? JsonObject)?.let { HomeGreetingPrompt.RecentPlay(it.string("artist")!!, it.string("album")!!) }

    private fun refPlay(e: JsonElement?) =
        (e as? JsonObject)?.let { RefHomeGreetingPrompt.RecentPlay(it.string("artist")!!, it.string("album")!!) }

    private fun cues(o: JsonObject) = HomeGreetingPrompt.SlotCues(play(o["morning"]), play(o["afternoon"]), play(o["evening"]))

    private fun refCues(o: JsonObject) =
        RefHomeGreetingPrompt.SlotCues(refPlay(o["morning"]), refPlay(o["afternoon"]), refPlay(o["evening"]))

    private fun refGreetings(e: JsonElement?): RefDailyHomeGreetings? = (e as? JsonObject)?.let {
        RefDailyHomeGreetings(
            day = it.long("day"), localeTag = it.string("localeTag")!!, profileId = it.string("profileId")!!,
            morning = it.string("morning"), afternoon = it.string("afternoon"), evening = it.string("evening"),
            cuesFingerprint = it.string("cuesFingerprint")!!, refreshCount = it.int("refreshCount"),
        )
    }

    private fun refSlot(v: JsonObject, key: String) = RefHomeGreetingSlot.valueOf(v.string(key)!!)

    private fun json(g: DailyHomeGreetings?): JsonElement = g?.let {
        greetingsJson(it.day, it.localeTag, it.profileId, it.morning, it.afternoon, it.evening, it.cuesFingerprint, it.refreshCount)
    } ?: JsonNull

    private fun json(g: RefDailyHomeGreetings?): JsonElement = g?.let {
        greetingsJson(it.day, it.localeTag, it.profileId, it.morning, it.afternoon, it.evening, it.cuesFingerprint, it.refreshCount)
    } ?: JsonNull

    private fun greetingsJson(
        day: Long,
        lang: String,
        profile: String,
        m: String?,
        a: String?,
        e: String?,
        fp: String,
        refresh: Int,
    ) = JsonObject(
        linkedMapOf(
            "day" to JsonPrimitive(day), "localeTag" to JsonPrimitive(lang), "profileId" to JsonPrimitive(profile),
            "morning" to str(m), "afternoon" to str(a), "evening" to str(e),
            "cuesFingerprint" to JsonPrimitive(fp), "refreshCount" to JsonPrimitive(refresh),
        ),
    )

    private fun cuesJson(c: RefHomeGreetingPrompt.SlotCues): JsonObject {
        fun p(r: RefHomeGreetingPrompt.RecentPlay?): JsonElement = r?.let {
            JsonObject(linkedMapOf("artist" to JsonPrimitive(it.artist), "album" to JsonPrimitive(it.album)))
        } ?: JsonNull
        return JsonObject(linkedMapOf("morning" to p(c.morning), "afternoon" to p(c.afternoon), "evening" to p(c.evening)))
    }

    private fun str(s: String?): JsonElement = if (s == null) JsonNull else JsonPrimitive(s)

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()

    private fun JsonObject.bool(key: String): Boolean = getValue(key).jsonPrimitive.boolean

    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int

    private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long

    private fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject

    private fun JsonObject.array(key: String): List<JsonObject> = getValue(key).jsonArray.map { it.jsonObject }
}
