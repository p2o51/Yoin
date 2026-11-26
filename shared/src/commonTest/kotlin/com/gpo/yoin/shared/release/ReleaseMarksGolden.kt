package com.gpo.yoin.shared.release

import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The golden vectors of the release marks (shared/src/commonTest/resources/golden/release-marks.json). Each vector
 * is its inputs followed by what [YoinReleaseMarks] answers for them ([outputs]); the file is written by
 * `ReleaseMarksGoldenTest` (androidHostTest) from [inputs], and [replay] reads every vector back and answers it
 * again, on the JVM and on the iOS simulator (through [RELEASE_MARKS_GOLDEN], the file as a Kotlin string). A port
 * (HarmonyOS's Dart) does what [outputs] does, section by section.
 */
internal object ReleaseMarksGolden {

    const val FILE = "shared/src/commonTest/resources/golden/release-marks.json"

    val sections: List<String> = listOf(
        "appleMusicRating", "openSubsonicRating", "explicitFlag", "fromTracks", "tracklistComplete", "storage",
        "spotifyReleaseType", "subsonicReleaseType", "appleMusicReleaseType", "releaseTypeStorage", "kindSuffix",
        "kindTag", "filterAccepts", "offeredFilters", "badgeFits", "badgeSize", "coverBand",
    )

    const val ABOUT = "Golden vectors for the release marks (com.gpo.yoin.shared.release, Android PR #343): the " +
        "Explicit / Clean mark read from Apple Music, OpenSubsonic and Spotify (a release's from its tracks), the " +
        "release kind read from each service, Apple Music's \" - EP\" / \" - Single\" hidden where the kind is " +
        "shown, the album header's kind, Library's kind filter, the E's size, gap and whether it fits after a " +
        "title, and the marks on a cover's band. Generated from :shared Kotlin through YoinReleaseMarks by " +
        "ReleaseMarksGoldenTest (androidHostTest); do not edit by hand. Every vector is its inputs followed by the " +
        "answer. Ratings, kinds and filters are their Kotlin names (null = unknown). fromTracks lists each " +
        "track's rating; YoinReleaseMarks.albumContentRating takes the same as counts. Fractional numbers are " +
        "32-bit floats written rounded to 6 decimals: compare within 1e-4. Sizes are the platform's pixels; " +
        "badgeGap rounds half up (Kotlin roundToInt). coverBand x is from the band's start edge (mirror for RTL)."

    // ── inputs ──────────────────────────────────────────────────────────────────────────────────────────

    private val ratingStrings = listOf(
        "explicit", "clean", "Explicit", "CLEAN", " explicit ", "", "  ", "notExplicit", "restricted", "cleaned", null,
    )

    val inputs: Map<String, List<JsonObject>> = mapOf(
        "appleMusicRating" to ratingStrings.map { obj("value" to str(it)) },
        "openSubsonicRating" to ratingStrings.map { obj("value" to str(it)) },
        "explicitFlag" to listOf(true, false, null).map { obj("explicit" to (it?.let(::JsonPrimitive) ?: JsonNull)) },
        "fromTracks" to listOf(
            listOf("Explicit") to false,
            listOf(null, "Explicit") to false,
            listOf("Clean", "Explicit", "Clean") to true,
            listOf("Clean", "Clean") to true,
            listOf("Clean", "Clean") to false,
            listOf("Clean", null) to true,
            listOf<String?>() to true,
            listOf<String?>() to false,
            listOf(null, null) to true,
            listOf("Clean") to true,
        ).map { (ratings, complete) ->
            obj("ratings" to JsonArray(ratings.map(::str)), "complete" to JsonPrimitive(complete))
        },
        "tracklistComplete" to listOf(
            Triple(10, 10, null), Triple(9, 10, null), Triple(11, 10, null), Triple(3, null, 3), Triple(2, null, 3),
            Triple(5, null, null), Triple(0, 0, null), Triple(50, 12, 50), Triple(12, null, 100),
        ).map { (loaded, total, page) ->
            obj("loadedTracks" to JsonPrimitive(loaded), "totalTracks" to int(total), "pageTotal" to int(page))
        },
        "storage" to listOf("Explicit", "Clean", "explicit", "Restricted", "", null).map { obj("name" to str(it)) },
        "spotifyReleaseType" to listOf(
            "album" to 12, "ALBUM" to null, "compilation" to 20, "single" to 1, "single" to 3, "single" to 4,
            "single" to 6, "single" to null, "Single" to 5, "appears_on" to 10, "" to 3, null to 4,
        ).map { (type, total) -> obj("albumType" to str(type), "totalTracks" to int(total)) },
        "subsonicReleaseType" to listOf(
            listOf("album"), listOf("EP"), listOf(" single "), listOf("Compilation"), listOf("live", "album"),
            listOf("soundtrack"), listOf("ep", "album"), emptyList(), listOf(""), listOf("Album", "compilation"),
        ).map { obj("releaseTypes" to strings(it)) },
        "appleMusicReleaseType" to listOf(
            Triple("Section.80 - EP", null, false),
            Triple("HUMBLE. - Single", null, true),
            Triple("Now 100", true, false),
            Triple("DAMN.", null, false),
            Triple("DAMN.", null, null),
            Triple("Untitled - EP", null, null),
            Triple("Loud - Single", null, null),
            Triple("Greatest Hits - EP", true, false),
            Triple("A Single - Song", false, true),
            Triple("Ends in -EP", null, false),
            Triple("Ends in - ep", null, false),
            Triple(null, null, null),
            Triple(null, null, false),
            Triple(null, false, null),
            Triple("Song - Single", false, false),
        ).map { (name, compilation, single) ->
            obj("name" to str(name), "isCompilation" to bool(compilation), "isSingle" to bool(single))
        },
        "releaseTypeStorage" to listOf("Album", "EP", "Single", "Compilation", "ep", "Mixtape", null)
            .map { obj("name" to str(it)) },
        "kindSuffix" to listOf(
            "Section.80 - EP" to "EP", "Section.80 - EP" to "Single", "HUMBLE. - Single" to "Single",
            "HUMBLE. - Single" to "EP", "HUMBLE. - Single" to null, "HUMBLE. - Single" to "Album",
            " - EP" to "EP", " - Single" to "Single", "  - EP" to "EP", "Live - EP - EP" to "EP",
            "The EP" to "EP", "再生 - EP" to "EP", "Single" to "Single", "DAMN." to "Compilation", "" to "EP",
        ).map { (name, type) -> obj("name" to JsonPrimitive(name), "type" to str(type)) },
        "kindTag" to listOf("Album", "EP", "Single", "Compilation", null).map { obj("type" to str(it)) },
        "filterAccepts" to ReleaseKindFilter.entries.flatMap { f ->
            (ReleaseType.entries.map { it.name } + listOf<String?>(null)).map { t ->
                obj("filter" to JsonPrimitive(f.name), "type" to str(t))
            }
        },
        "offeredFilters" to listOf(
            listOf(),
            listOf("Album", "Album"),
            listOf("Album", "EP"),
            listOf("EP", "Single"),
            listOf("Single", "Compilation"),
            listOf("Album", "Single", "Compilation", "EP"),
            listOf("Compilation", "Album"),
            listOf("EP"),
        ).map { obj("types" to strings(it)) },
        "badgeFits" to listOf(
            Triple(300, 200, 120), Triple(300, 100, 120), Triple(300, 150, 400), Triple(300, 149, 400),
            Triple(301, 150, 400), Triple(301, 149, 400), Triple(100, 30, 30), Triple(100, 29, 30),
            Triple(0, 0, 0), Triple(80, 0, 10), Triple(80, 40, 200), Triple(200, -10, 50),
        ).map { (max, room, natural) ->
            obj("maxWidth" to JsonPrimitive(max), "titleRoom" to JsonPrimitive(room), "naturalTitle" to JsonPrimitive(natural))
        },
        "badgeSize" to listOf(
            // dp-scale (density 1) sizes, then Android xhdpi px (density 2, font scale 1 and 1.3).
            14f, 11f, 12f, 16f, 22f, 24f, 28f, 32f, 36f, 45f, 8f, 17f, 72f, 28.8f, 34.4f, 48f, 93.6f,
        ).flatMap { size ->
            val density = if (size > 40f) 2f else 1f
            listOf(false, true).map { hero ->
                obj(
                    "fontSize" to num(size),
                    "hero" to JsonPrimitive(hero),
                    "minSide" to num(ReleaseMarkRules.MIN_SIDE_DP * density),
                    "heroMaxSide" to num(ReleaseMarkRules.HERO_MAX_SIDE_DP * density),
                    "minGap" to num(ReleaseMarkRules.GAP_DP * density),
                )
            }
        },
        "coverBand" to coverBandInputs(),
    )

    private fun coverBandInputs(): List<JsonObject> {
        fun item(w: Int, h: Int) = obj("width" to JsonPrimitive(w), "height" to JsonPrimitive(h))
        val e = item(12, 12)
        val ep = item(22, 15)
        val single = item(40, 15)
        val atmos = item(44, 15)
        val deluxe = item(118, 15)
        val cases = listOf(
            // name, width, height, fraction, sticker, kind, version, explicit
            listOf("phone grid, all three", 118, 118, 0.72f, 0, ep, atmos, e),
            listOf("phone grid, E only", 118, 118, 0.72f, 0, null, null, e),
            listOf("phone grid, kind only", 118, 118, 0.72f, 0, single, null, null),
            listOf("long version cut", 118, 118, 0.72f, 0, ep, deluxe, e),
            listOf("long version cut, sticker", 118, 118, 0.72f, 14, ep, deluxe, e),
            listOf("narrow: version dropped", 48, 48, 0.72f, 0, null, deluxe, e),
            listOf("narrow: kind dropped too", 40, 40, 0.72f, 0, single, atmos, e),
            listOf("band too short for the E", 30, 30, 0.72f, 0, null, null, e),
            listOf("tablet", 180, 180, 0.72f, 0, single, atmos, e),
            listOf("tablet, sticker", 180, 180, 0.72f, 14, single, deluxe, e),
            listOf("px xhdpi", 236, 236, 0.72f, 28, item(44, 30), item(236, 30), item(24, 24)),
            listOf("odd fraction", 101, 133, 0.7f, 0, ep, atmos, e),
            listOf("nothing", 118, 118, 0.72f, 0, null, null, null),
            listOf("version shorter than min tag", 60, 60, 0.72f, 0, null, item(30, 15), null),
            listOf("version cut to exactly min", 70, 70, 0.72f, 0, null, item(80, 15), null),
        )
        return cases.map { c ->
            obj(
                "name" to JsonPrimitive(c[0] as String),
                "width" to JsonPrimitive(c[1] as Int),
                "height" to JsonPrimitive(c[2] as Int),
                "coverFraction" to num(c[3] as Float),
                "stickerRoom" to JsonPrimitive(c[4] as Int),
                "gap" to JsonPrimitive(4),
                "minTagWidth" to JsonPrimitive(40),
                "kind" to (c[5] as JsonObject? ?: JsonNull),
                "version" to (c[6] as JsonObject? ?: JsonNull),
                "explicit" to (c[7] as JsonObject? ?: JsonNull),
            )
        }
    }

    // ── outputs ─────────────────────────────────────────────────────────────────────────────────────────

    /** What the facade answers for one vector's inputs (the vector's own output fields are ignored). */
    fun outputs(section: String, v: JsonObject): JsonObject {
        val m = YoinReleaseMarks
        return when (section) {
            "appleMusicRating" -> obj("rating" to str(m.contentRatingFromAppleMusic(v.string("value"))?.name))
            "openSubsonicRating" -> obj("rating" to str(m.contentRatingFromOpenSubsonic(v.string("value"))?.name))
            "explicitFlag" -> obj("rating" to str(m.contentRatingFromExplicitFlag(v.boolOrNull("explicit"))?.name))
            "fromTracks" -> {
                val ratings = v.array("ratings").map { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content }
                val complete = v.bool("complete")
                val list = ContentRating.fromTracks(ratings.map { ContentRating.fromStorage(it) }, complete)
                val counted = m.albumContentRating(
                    explicitTracks = ratings.count { it == "Explicit" },
                    cleanTracks = ratings.count { it == "Clean" },
                    loadedTracks = ratings.size,
                    complete = complete,
                )
                check(list == counted) { "albumContentRating disagrees with fromTracks for $v" }
                obj("rating" to str(list?.name))
            }
            "tracklistComplete" -> obj(
                "complete" to JsonPrimitive(m.tracklistComplete(v.int("loadedTracks"), v.intOrNull("totalTracks"), v.intOrNull("pageTotal"))),
            )
            "storage" -> obj("rating" to str(m.contentRatingFromStorage(v.string("name"))?.name))
            "spotifyReleaseType" -> obj("type" to str(m.spotifyReleaseType(v.string("albumType"), v.intOrNull("totalTracks"))?.name))
            "subsonicReleaseType" -> obj("type" to str(m.subsonicReleaseType(v.strings("releaseTypes"))?.name))
            "appleMusicReleaseType" -> obj(
                "type" to str(m.appleMusicReleaseType(v.string("name"), v.boolOrNull("isCompilation"), v.boolOrNull("isSingle"))?.name),
            )
            "releaseTypeStorage" -> obj("type" to str(m.releaseTypeFromStorage(v.string("name"))?.name))
            "kindSuffix" -> obj("shown" to str(m.nameWithoutKindSuffix(v.string("name")!!, type(v.string("type")))))
            "kindTag" -> {
                val t = type(v.string("type"))
                obj("showsKindTag" to JsonPrimitive(m.showsKindTag(t)), "headerKind" to str(m.headerKind(t).name))
            }
            "filterAccepts" -> obj(
                "accepts" to JsonPrimitive(m.filterAccepts(ReleaseKindFilter.valueOf(v.string("filter")!!), type(v.string("type")))),
            )
            "offeredFilters" -> obj(
                "filters" to strings(m.offeredFilters(v.strings("types").map { ReleaseType.valueOf(it) }).map { it.name }),
            )
            "badgeFits" -> obj("fits" to JsonPrimitive(m.explicitBadgeFits(v.int("maxWidth"), v.int("titleRoom"), v.int("naturalTitle"))))
            "badgeSize" -> {
                val size = v.float("fontSize")
                val hero = v.bool("hero")
                val minSide = v.float("minSide")
                val heroMax = v.float("heroMaxSide")
                obj(
                    "side" to num(m.badgeSide(size, hero, minSide, heroMax)),
                    "gap" to JsonPrimitive(m.badgeGap(size, hero, minSide, heroMax, v.float("minGap"))),
                )
            }
            "coverBand" -> {
                fun item(key: String) = (v[key] as? JsonObject)?.let { CoverBandItem(it.int("width"), it.int("height")) }
                val layout = m.coverBand(
                    v.int("width"), v.int("height"), v.float("coverFraction"), v.int("stickerRoom"), v.int("gap"),
                    v.int("minTagWidth"), item("kind"), item("version"), item("explicit"),
                )
                obj(
                    "band" to JsonPrimitive(layout.band),
                    "start" to JsonPrimitive(layout.start),
                    "placed" to JsonArray(
                        layout.placed.map {
                            obj(
                                "role" to JsonPrimitive(it.role.name),
                                "x" to JsonPrimitive(it.x),
                                "y" to JsonPrimitive(it.y),
                                "width" to JsonPrimitive(it.width),
                                "height" to JsonPrimitive(it.height),
                            )
                        },
                    ),
                )
            }
            else -> error("unknown section $section")
        }
    }

    fun constants(): JsonObject = obj(
        "capHeightEm" to num(YoinReleaseMarks.capHeightEm),
        "xHeightEm" to num(YoinReleaseMarks.xHeightEm),
        "letterShare" to num(YoinReleaseMarks.letterShare),
        "cornerShare" to num(YoinReleaseMarks.cornerShare),
        "minSideDp" to num(YoinReleaseMarks.minSideDp),
        "heroMaxSideDp" to num(YoinReleaseMarks.heroMaxSideDp),
        "gapDp" to num(YoinReleaseMarks.gapDp),
        "bandBadgeTextSp" to num(YoinReleaseMarks.bandBadgeTextSp),
        "bandInsetFraction" to num(YoinReleaseMarks.bandInsetFraction),
        "bandMinTagWidthDp" to num(YoinReleaseMarks.bandMinTagWidthDp),
        "bandGapDp" to num(YoinReleaseMarks.bandGapDp),
        "bandStickerRoomDp" to num(YoinReleaseMarks.bandStickerRoomDp),
        "ratings" to strings(ContentRating.entries.map { it.name }),
        "releaseTypes" to strings(ReleaseType.entries.map { it.name }),
        "filters" to strings(ReleaseKindFilter.entries.map { it.name }),
    )

    fun render(): String = buildString {
        append("{\n")
        append("  \"about\": ").append(encode(JsonPrimitive(ABOUT))).append(",\n")
        append("  \"constants\": ").append(encode(constants()))
        sections.forEach { section ->
            val inputs = inputs.getValue(section)
            append(",\n  \"").append(section).append("\": [\n")
            inputs.forEachIndexed { i, input ->
                append("    ").append(encode(JsonObject(input + outputs(section, input))))
                append(if (i == inputs.lastIndex) "\n" else ",\n")
            }
            append("  ]")
        }
        append("\n}\n")
    }

    /** Every vector of [text] answered again; one line per vector whose answer differs. */
    fun replay(text: String): List<String> {
        val root = Json.parseToJsonElement(text).jsonObject
        val mismatches = mutableListOf<String>()
        if (!same(root.getValue("constants"), constants())) mismatches += "constants differ"
        sections.forEach { section ->
            val vectors = root[section]?.jsonArray ?: return@forEach run { mismatches += "missing section $section" }
            vectors.forEachIndexed { i, element ->
                val vector = element.jsonObject
                outputs(section, vector).forEach { (key, actual) ->
                    if (!same(vector.getValue(key), actual)) {
                        mismatches += "$section[$i] $key: expected ${vector[key]} but was $actual"
                    }
                }
            }
        }
        return mismatches
    }

    /** Equal JSON, numbers within 1e-4 (the file's floats are rounded; Kotlin/Native prints them its own way). */
    private fun same(a: JsonElement, b: JsonElement): Boolean = when {
        a is JsonObject && b is JsonObject -> a.keys == b.keys && a.keys.all { same(a.getValue(it), b.getValue(it)) }
        a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { same(a[it], b[it]) }
        a is JsonPrimitive && b is JsonPrimitive && !a.isString && !b.isString &&
            a.doubleOrNull != null && b.doubleOrNull != null -> abs(a.double - b.double) < 1e-4
        else -> a == b
    }

    // ── json helpers ────────────────────────────────────────────────────────────────────────────────────

    private fun type(name: String?): ReleaseType? = name?.let(ReleaseType::valueOf)

    fun str(value: String?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)

    private fun int(value: Int?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)

    private fun bool(value: Boolean?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)

    /** A 32-bit float rounded to 6 decimals, so the JVM and Kotlin/Native write the same digits. */
    private fun num(value: Float): JsonElement = JsonPrimitive((value.toDouble() * 1e6).roundToLong() / 1e6)

    fun strings(values: List<String>): JsonArray = JsonArray(values.map(::JsonPrimitive))

    fun obj(vararg entries: Pair<String, JsonElement>): JsonObject = JsonObject(linkedMapOf(*entries))

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun JsonObject.strings(key: String): List<String> = (this[key] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()

    private fun JsonObject.bool(key: String): Boolean = getValue(key).jsonPrimitive.boolean

    private fun JsonObject.boolOrNull(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.booleanOrNull

    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int

    private fun JsonObject.intOrNull(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.intOrNull

    private fun JsonObject.float(key: String): Float = getValue(key).jsonPrimitive.double.toFloat()

    private fun JsonObject.array(key: String): JsonArray = getValue(key).jsonArray

    fun encode(e: JsonElement): String = Json.encodeToString(JsonElement.serializer(), e)
}
