package com.gpo.yoin.ui.component

import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.memories.emblem.GrooveAwardState
import com.gpo.yoin.ui.memories.emblem.GrooveKind
import com.gpo.yoin.ui.memories.emblem.GrooveModel
import com.gpo.yoin.ui.memories.emblem.GrooveSurface
import com.gpo.yoin.ui.memories.showcase.MemoryPaletteSamples
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The score emblem facade's plain-input mapping onto the Memories groove model. */
class ScoreEmblemModelTest {

    private val palette = MemoryPaletteSamples.M1
    private val rated = List(10) { it < 4 }

    private fun model(score: Double?, kind: ScoreEmblemKind, trackRated: List<Boolean> = rated) =
        scoreEmblemModel(score, kind, trackRated, palette)

    @Test
    fun should_map_each_kind_to_the_groove_kind_of_the_same_name_when_converted() {
        assertEquals(GrooveKind.Album, ScoreEmblemKind.Album.toGrooveKind())
        assertEquals(GrooveKind.Average, ScoreEmblemKind.Average.toGrooveKind())
        assertEquals(GrooveKind.Unrated, ScoreEmblemKind.Unrated.toGrooveKind())
        assertEquals(ScoreEmblemKind.entries.size, GrooveKind.entries.size)
    }

    @Test
    fun should_map_artwork_to_cover_and_page_to_bar_when_surface_is_converted() {
        assertEquals(GrooveSurface.Cover, ScoreEmblemSurface.Artwork.toGrooveSurface())
        assertEquals(GrooveSurface.Bar, ScoreEmblemSurface.Page.toGrooveSurface())
    }

    @Test
    fun should_keep_kind_and_tracks_when_a_score_is_given() {
        val album = model(8.0, ScoreEmblemKind.Album)
        assertEquals(GrooveKind.Album, album.kind)
        assertEquals(8.0, album.score!!, 0.0)
        assertEquals(rated, album.trackRated)
        assertEquals(palette, album.palette)
        assertEquals(GrooveKind.Average, model(7.8, ScoreEmblemKind.Average).kind)
    }

    @Test
    fun should_draw_unrated_when_the_score_is_missing_or_not_finite() {
        listOf(null, Double.NaN, Double.POSITIVE_INFINITY).forEach { score ->
            listOf(ScoreEmblemKind.Album, ScoreEmblemKind.Average).forEach { kind ->
                val m = model(score, kind)
                assertEquals("$score / $kind", GrooveKind.Unrated, m.kind)
                assertNull(m.score)
                assertEquals(0, m.tier)
            }
        }
    }

    @Test
    fun should_drop_the_score_when_kind_is_unrated() {
        val m = model(8.0, ScoreEmblemKind.Unrated)
        assertEquals(GrooveKind.Unrated, m.kind)
        assertNull(m.score)
        assertEquals("", m.scoreText)
        assertEquals("Not rated", m.contentDescription)
    }

    @Test
    fun should_show_ten_and_award_tier_four_when_score_is_9_95() {
        assertEquals(10.0, scoreEmblemDisplayScore(9.95)!!, 0.0)
        val m = model(9.95, ScoreEmblemKind.Album)
        assertEquals(10.0, m.score!!, 0.0)
        assertEquals("10.0", m.scoreText)
        assertEquals(4, m.tier)
        assertEquals("Album rating 10.0", m.contentDescription)
    }

    @Test
    fun should_round_down_when_score_is_9_94() {
        assertEquals(9.9, scoreEmblemDisplayScore(9.94)!!, 0.0)
        val m = model(9.94, ScoreEmblemKind.Average)
        assertEquals("9.9", m.scoreText)
        assertEquals(3, m.tier)
    }

    @Test
    fun should_round_halves_up_when_score_has_two_decimals() {
        assertEquals(7.3, scoreEmblemDisplayScore(7.25)!!, 0.0)
        assertEquals(6.0, scoreEmblemDisplayScore(5.95)!!, 0.0)
        assertEquals(8.0, scoreEmblemDisplayScore(8.0)!!, 0.0)
    }

    @Test
    fun should_read_a_float_rating_through_its_shortest_decimal_when_converted() {
        // 9.95f is 9.9499998… in binary: the plain widening shows 9.9, the facade's conversion shows 10.0
        assertEquals(9.9, scoreEmblemDisplayScore(9.95f.toDouble())!!, 0.0)
        assertEquals(10.0, scoreEmblemDisplayScore(9.95f.toScoreEmblemScore())!!, 0.0)
        assertEquals(7.0, 7f.toScoreEmblemScore(), 0.0)
    }

    @Test
    fun should_clamp_to_the_ten_point_scale_when_score_is_out_of_range() {
        assertEquals(10.0, scoreEmblemDisplayScore(12.0)!!, 0.0)
        assertEquals(0.0, scoreEmblemDisplayScore(-1.0)!!, 0.0)
        assertNull(scoreEmblemDisplayScore(null))
    }

    @Test
    fun should_equal_the_memories_model_when_given_the_card_score() {
        // Memories builds its model from the card's one-decimal text ("10.0" for a 9.95 rating)
        val memories = GrooveModel(GrooveKind.Album, "10.0".toDouble(), rated, palette)
        assertEquals(memories, model(9.95, ScoreEmblemKind.Album))
    }

    @Test
    fun should_not_crash_when_the_album_has_no_tracks() {
        val m = model(9.0, ScoreEmblemKind.Album, emptyList())
        assertEquals(GrooveKind.Album, m.kind)
        assertTrue(m.trackRated.isEmpty())
    }

    @Test
    fun should_drive_the_emblem_only_when_the_award_was_built_from_the_same_inputs() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val emblem = model(9.95, ScoreEmblemKind.Album)
        // the award's palette differs (colours are not an input of the award)
        val awardModel = scoreEmblemModel(9.95, ScoreEmblemKind.Album, rated, MemoryPaletteSamples.M3)
        val award = GrooveAwardState(awardModel, 96.0, GrooveSurface.Cover, false, null, scope, "test")

        assertTrue(award.drives(emblem, 96.dp, GrooveSurface.Cover))
        assertFalse(award.drives(emblem, 48.dp, GrooveSurface.Cover))
        assertFalse(award.drives(emblem, 96.dp, GrooveSurface.Bar))
        assertFalse(award.drives(model(9.0, ScoreEmblemKind.Album), 96.dp, GrooveSurface.Cover))
        assertFalse(award.drives(model(9.95, ScoreEmblemKind.Average), 96.dp, GrooveSurface.Cover))
        assertFalse(
            award.drives(model(9.95, ScoreEmblemKind.Album, List(10) { it < 5 }), 96.dp, GrooveSurface.Cover),
        )
    }
}
