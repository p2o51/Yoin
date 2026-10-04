package com.gpo.yoin.ui.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.gpo.yoin.data.profile.ProfileManager
import com.gpo.yoin.data.profile.ProviderKind
import hct.Hct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import utils.MathUtils
import kotlin.math.abs

class AccountIdentityTest {

    @Test
    fun should_giveEveryAccountADistinctShape_when_idsCollide() {
        // Ids whose hashes land on the same slot still get different shapes.
        val ids = (0 until 200).map { "profile-$it" }
        val bySlot = ids.groupBy { Math.floorMod(it.hashCode(), AvatarShapeCount) }
        val colliding = bySlot.values.first { it.size >= ProfileManager.MAX_PROFILES }
            .take(ProfileManager.MAX_PROFILES)

        val shapes = assignAvatarShapes(colliding.mapIndexed { i, id -> id to i.toLong() })

        assertEquals(colliding.size, shapes.values.toSet().size)
        assertTrue(shapes.values.all { it in 0 until AvatarShapeCount })
    }

    @Test
    fun should_keepAShape_when_assignedAgainOrANewerAccountIsAdded() {
        val first = assignAvatarShapes(listOf("home" to 1L, "spotify" to 2L))
        val later = assignAvatarShapes(listOf("home" to 1L, "spotify" to 2L, "apple" to 3L, "backup" to 4L))

        assertEquals(first, assignAvatarShapes(listOf("home" to 1L, "spotify" to 2L)))
        assertEquals(first["home"], later["home"])
        assertEquals(first["spotify"], later["spotify"])
    }

    @Test
    fun should_ignoreListOrder_when_profilesComeShuffled() {
        val ordered = listOf("a" to 1L, "b" to 2L, "c" to 2L, "d" to 3L)
        assertEquals(assignAvatarShapes(ordered), assignAvatarShapes(ordered.reversed()))
    }

    @Test
    fun should_coverEveryAccount_when_atTheProfileLimit() {
        assertTrue(AvatarShapeCount >= ProfileManager.MAX_PROFILES)
        val profiles = (1..ProfileManager.MAX_PROFILES).map { "id$it" to it.toLong() }
        assertEquals(profiles.map { it.first }.toSet(), assignAvatarShapes(profiles).keys)
    }

    @Test
    fun should_takeFirstLetterOrDigit_when_titleStartsWithSymbols() {
        assertEquals("D", monogramOf("demo @ demo.navidrome.org"))
        assertEquals("H", monogramOf("  @home"))
        assertEquals("陈", monogramOf("陈的 Spotify"))
        assertEquals("7", monogramOf("#7 server"))
        assertEquals("É", monogramOf("élodie"))
        assertEquals("C", monogramOf("🎧 Chen"))
    }

    @Test
    fun should_stayOneCharacter_when_letterUppercasesToTwo() {
        // "ß".uppercase() is "SS"; title case maps one code point to one.
        assertEquals(1, monogramOf("ßeta").codePointCount(0, monogramOf("ßeta").length))
    }

    @Test
    fun should_skipTheServiceName_when_titleStartsWithIt() {
        assertEquals("3", monogramOf("Spotify · 31xyz", serviceName = "Spotify"))
        // Nothing after the service name: keep its initial.
        assertEquals("A", monogramOf("Apple Music", serviceName = "Apple Music"))
        assertEquals("C", monogramOf("Chen", serviceName = "Spotify"))
    }

    @Test
    fun should_returnEmpty_when_titleHasNoLetters() {
        assertEquals("", monogramOf(""))
        assertEquals("", monogramOf("🎧🎶"))
        assertEquals("", monogramOf(" · "))
    }

    @Test
    fun should_showServiceAndHost_when_subsonicAccount() {
        assertEquals(
            "Subsonic · music.example.com",
            serviceLineOf("Subsonic", title = "chen", detail = "music.example.com"),
        )
    }

    @Test
    fun should_dropServiceName_when_titleAlreadySaysIt() {
        assertNull(serviceLineOf("Apple Music", title = "Apple Music", detail = null))
        assertEquals("Spotify", serviceLineOf("Spotify", title = "Chen", detail = null))
        // A legacy "user @ host" title already carries the host.
        assertEquals(
            "Subsonic",
            serviceLineOf("Subsonic", title = "chen @ music.example.com", detail = "music.example.com"),
        )
    }

    @Test
    fun should_giveEachServiceItsOwnHue_when_mapped() {
        val hues = ProviderKind.entries.map { it.serviceIdentity.hue }
        assertEquals(hues.size, hues.toSet().size)
    }

    @Test
    fun should_turnAtMostTheCap_when_harmonizing() {
        // Far apart: capped, toward the source.
        assertEquals(148.0 + MaxHarmonizeDegrees, harmonizeHue(designHue = 148.0, sourceHue = 300.0), 0.001)
        // Close: half the distance.
        assertEquals(261.0, harmonizeHue(designHue = 256.0, sourceHue = 266.0), 0.001)
        // Across 0°.
        assertEquals(0.0, harmonizeHue(designHue = 10.0, sourceHue = 330.0), 0.001)
    }

    @Test
    fun should_keepServicesAtLeast30DegreesApart_when_anyWallpaperHue() {
        (0 until 360).forEach { sourceHue ->
            val hues = SettingsHue.entries.map { harmonizeHue(it.sourceHue, sourceHue.toDouble()) }
            for (i in hues.indices) {
                for (j in i + 1 until hues.size) {
                    val gap = MathUtils.differenceDegrees(hues[i], hues[j])
                    val pair = "${SettingsHue.entries[i]}/${SettingsHue.entries[j]}"
                    assertTrue("source $sourceHue: $pair = $gap", gap >= 30.0)
                }
            }
        }
    }

    @Test
    fun should_keepPairsReadable_when_buildingAFamily() {
        val source = 0xFF6750A4.toInt()
        SettingsHue.entries.forEach { hue ->
            listOf(true, false).forEach { dark ->
                val tone = settingsTone(hue, source, dark)
                assertTrue(toneGap(tone.container, tone.onContainer) >= 55.0)
                assertTrue(toneGap(tone.accent, tone.onAccent) >= 55.0)
                assertTrue(toneGap(tone.iconContainer, tone.iconContent) >= 45.0)
            }
        }
    }

    @Test
    fun should_notTurn_when_wallpaperIsGrey() {
        val grey = 0xFF777777.toInt()
        SettingsHue.entries.forEach { hue ->
            val actual = Hct.fromInt(settingsTone(hue, grey, dark = false).accent.toArgb()).hue
            assertTrue("$hue drifted to $actual", MathUtils.differenceDegrees(actual, hue.sourceHue) <= 3.0)
        }
    }

    @Test
    fun should_stayNearTheSourceHue_when_harmonized() {
        val source = 0xFF6750A4.toInt()
        SettingsHue.entries.forEach { hue ->
            val actual = Hct.fromInt(settingsTone(hue, source, dark = false).accent.toArgb()).hue
            assertTrue(
                "$hue drifted to $actual",
                MathUtils.differenceDegrees(actual, hue.sourceHue) <= MaxHarmonizeDegrees + 3.0,
            )
        }
    }

    private fun toneGap(a: Color, b: Color): Double =
        abs(Hct.fromInt(a.toArgb()).tone - Hct.fromInt(b.toArgb()).tone)

    @Test
    fun should_roundAllCorners_when_aGroupHasOneRow() {
        val density = androidx.compose.ui.unit.Density(1f)
        fun radii(shape: androidx.compose.ui.graphics.Shape): List<Float> {
            val outline = shape.createOutline(
                androidx.compose.ui.geometry.Size(300f, 72f),
                androidx.compose.ui.unit.LayoutDirection.Ltr,
                density,
            ) as androidx.compose.ui.graphics.Outline.Rounded
            val r = outline.roundRect
            return listOf(r.topLeftCornerRadius.x, r.topRightCornerRadius.x, r.bottomRightCornerRadius.x, r.bottomLeftCornerRadius.x)
        }
        assertEquals(listOf(20f, 20f, 20f, 20f), radii(settingsSegmentShape(0, 1)))
        assertEquals(listOf(20f, 20f, 4f, 4f), radii(settingsSegmentShape(0, 3)))
        assertEquals(listOf(4f, 4f, 4f, 4f), radii(settingsSegmentShape(1, 3)))
        assertEquals(listOf(4f, 4f, 20f, 20f), radii(settingsSegmentShape(2, 3)))
    }
}
