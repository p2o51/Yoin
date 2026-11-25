package com.gpo.yoin.ui.component

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * A Home re-tier builds a screenful of cards at once, each with a press
 * shape; matching two polygons into a Morph was most of a card's cost and
 * every card built its own. One Morph per (base, pressed) pair.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
class PressMorphsTest {

    @Test
    fun should_shareOneMorph_when_cardsAskForTheSamePair() {
        assertSame(
            PressMorphs.of(MaterialShapes.Bun, MaterialShapes.Triangle),
            PressMorphs.of(MaterialShapes.Bun, MaterialShapes.Triangle),
        )
    }

    @Test
    fun should_buildAnotherMorph_when_theBaseDiffers() {
        assertNotSame(
            PressMorphs.of(MaterialShapes.Bun, MaterialShapes.Triangle),
            PressMorphs.of(MaterialShapes.Ghostish, MaterialShapes.Triangle),
        )
    }
}
