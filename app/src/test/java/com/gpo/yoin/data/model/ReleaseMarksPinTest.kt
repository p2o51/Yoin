package com.gpo.yoin.data.model

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.gpo.yoin.data.source.applemusic.AppleMusicSource
import com.gpo.yoin.data.source.spotify.SpotifyAlbumObject
import com.gpo.yoin.data.source.spotify.SpotifyPagingObject
import com.gpo.yoin.data.source.spotify.SpotifyTrackObject
import com.gpo.yoin.data.source.spotify.contentRating
import com.gpo.yoin.data.source.spotify.spotifyReleaseType
import com.gpo.yoin.data.source.subsonic.subsonicReleaseType
import com.gpo.yoin.ui.component.ReleaseKindFilter
import com.gpo.yoin.ui.component.explicitBadgeFits
import com.gpo.yoin.ui.component.explicitBadgeGapPx
import com.gpo.yoin.ui.component.explicitBadgeSidePx
import com.gpo.yoin.ui.component.nameWithoutKindSuffix
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android answers the release-marks golden vectors (shared/src/commonTest/resources/golden/release-marks.json),
 * which iOS and HarmonyOS are tested against, through the app's own paths: Apple Music's album JSON for the kind,
 * Spotify's DTOs for the kind and an album's rating from its tracks, the Subsonic mapper, the cards' kind suffix,
 * Library's filter and the E's size, gap and fit. The same vectors passed against the app's own code before the
 * rules moved to :shared (2026-11-27), so the file is also what Android did before :shared took over. (The cover
 * band's layout runs the shared rule directly; CoverBandBadgesTest checks it on screen.)
 */
class ReleaseMarksPinTest {

    private val golden: JsonObject = Json.parseToJsonElement(
        File(
            generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile },
            "shared/src/commonTest/resources/golden/release-marks.json",
        ).readText(),
    ).jsonObject

    private fun section(name: String) = golden.getValue(name).jsonArray.map { it.jsonObject }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun JsonObject.intOrNull(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.intOrNull

    private fun JsonObject.boolOrNull(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.booleanOrNull

    private fun JsonObject.float(key: String): Float = getValue(key).jsonPrimitive.double.toFloat()

    private fun type(name: String?): ReleaseType? = name?.let(ReleaseType::valueOf)

    @Test
    fun should_readRatingsAsTheGoldenSays() {
        section("appleMusicRating").forEach { v ->
            assertEquals(v.toString(), v.string("rating"), ContentRating.fromAppleMusic(v.string("value"))?.name)
        }
        section("openSubsonicRating").forEach { v ->
            assertEquals(v.toString(), v.string("rating"), ContentRating.fromOpenSubsonic(v.string("value"))?.name)
        }
        section("explicitFlag").forEach { v ->
            assertEquals(v.toString(), v.string("rating"), ContentRating.fromExplicitFlag(v.boolOrNull("explicit"))?.name)
        }
        section("storage").forEach { v ->
            assertEquals(v.toString(), v.string("rating"), ContentRating.fromStorage(v.string("name"))?.name)
        }
    }

    private fun spotifyTrack(rating: String?) = SpotifyTrackObject(
        id = "t",
        name = "t",
        explicit = when (rating) {
            "Explicit" -> true
            "Clean" -> false
            else -> null
        },
    )

    @Test
    fun should_rateASpotifyAlbumFromItsTracks_asTheGoldenSays() {
        section("fromTracks").forEach { v ->
            val ratings = v.getValue("ratings").jsonArray.map { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content }
            val tracks = ratings.map(::spotifyTrack)
            val complete = v.getValue("complete").jsonPrimitive.boolean
            val album = SpotifyAlbumObject(
                id = "a",
                name = "a",
                totalTracks = if (complete) tracks.size else tracks.size + 1,
                tracks = SpotifyPagingObject(items = tracks),
            )
            assertEquals(v.toString(), v.string("rating"), album.contentRating()?.name)
            assertEquals(v.toString(), v.string("rating"), ContentRating.fromTracks(ratings.map(ContentRating::fromStorage), complete)?.name)
        }
        // A whole clean tracklist is Clean exactly when the golden calls it complete (loaded tracks only).
        section("tracklistComplete").filter { it.getValue("loadedTracks").jsonPrimitive.int > 0 }.forEach { v ->
            val loaded = v.getValue("loadedTracks").jsonPrimitive.int
            val album = SpotifyAlbumObject(
                id = "a",
                name = "a",
                totalTracks = v.intOrNull("totalTracks"),
                tracks = SpotifyPagingObject(items = List(loaded) { spotifyTrack("Clean") }, total = v.intOrNull("pageTotal")),
            )
            val complete = v.getValue("complete").jsonPrimitive.boolean
            assertEquals(v.toString(), if (complete) ContentRating.Clean else null, album.contentRating())
        }
    }

    @Test
    fun should_readReleaseKindsAsTheGoldenSays() {
        section("spotifyReleaseType").forEach { v ->
            assertEquals(v.toString(), v.string("type"), spotifyReleaseType(v.string("albumType"), v.intOrNull("totalTracks"))?.name)
        }
        section("subsonicReleaseType").forEach { v ->
            val types = v.getValue("releaseTypes").jsonArray.map { it.jsonPrimitive.content }
            assertEquals(v.toString(), v.string("type"), subsonicReleaseType(types)?.name)
        }
        section("appleMusicReleaseType").forEach { v ->
            val own = buildMap<String, JsonPrimitive> {
                v.string("name")?.let { put("name", JsonPrimitive(it)) }
                v.boolOrNull("isCompilation")?.let { put("isCompilation", JsonPrimitive(it)) }
                v.boolOrNull("isSingle")?.let { put("isSingle", JsonPrimitive(it)) }
            }
            // On the resource itself, and on a library album that only has its catalog album's.
            assertEquals(v.toString(), v.string("type"), AppleMusicSource.appleReleaseType(JsonObject(own), null)?.name)
            assertEquals(v.toString(), v.string("type"), AppleMusicSource.appleReleaseType(JsonObject(emptyMap()), JsonObject(own))?.name)
        }
    }

    @Test
    fun should_nameAndFilterKindsAsTheGoldenSays() {
        section("kindSuffix").forEach { v ->
            assertEquals(v.toString(), v.string("shown"), nameWithoutKindSuffix(v.string("name")!!, type(v.string("type"))))
        }
        section("filterAccepts").forEach { v ->
            val filter = ReleaseKindFilter.valueOf(v.string("filter")!!)
            assertEquals(v.toString(), v.getValue("accepts").jsonPrimitive.boolean, filter.accepts(type(v.string("type"))))
        }
        section("offeredFilters").forEach { v ->
            val types = v.getValue("types").jsonArray.map { ReleaseType.valueOf(it.jsonPrimitive.content) }
            val expected = (v.getValue("filters") as JsonArray).map { it.jsonPrimitive.content }
            assertEquals(v.toString(), expected, ReleaseKindFilter.offeredFor(types).map { it.name })
            // An unknown kind changes nothing: it is only ever under All.
            assertEquals(v.toString(), expected, ReleaseKindFilter.offeredFor(types + null).map { it.name })
        }
    }

    @Test
    fun should_sizeAndFitTheBadgeAsTheGoldenSays() {
        section("badgeFits").forEach { v ->
            val fits = explicitBadgeFits(
                v.getValue("maxWidth").jsonPrimitive.int,
                v.getValue("titleRoom").jsonPrimitive.int,
                v.getValue("naturalTitle").jsonPrimitive.int,
            )
            assertEquals(v.toString(), v.getValue("fits").jsonPrimitive.boolean, fits)
        }
        var checked = 0
        section("badgeSize").forEach { v ->
            // The vectors' pixels at the density their limits imply (9dp → 9px or 18px), font scale 1.
            val density = Density(density = v.float("minSide") / 9f, fontScale = 1f)
            val style = TextStyle(fontSize = (v.float("fontSize") / density.density).sp)
            val hero = v.getValue("hero").jsonPrimitive.boolean
            val side = with(density) { explicitBadgeSidePx(style, TextUnit.Unspecified, hero) }
            val gap = with(density) { explicitBadgeGapPx(style, TextUnit.Unspecified, hero) }
            assertEquals(v.toString(), v.float("side"), side, 1e-3f)
            assertEquals(v.toString(), v.getValue("gap").jsonPrimitive.int, gap)
            checked++
        }
        assertTrue(checked > 0)
    }
}
