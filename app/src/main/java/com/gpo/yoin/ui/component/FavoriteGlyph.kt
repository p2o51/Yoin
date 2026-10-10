@file:Suppress("ktlint:standard:function-naming")

package com.gpo.yoin.ui.component

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.symbols.rememberFavoriteSymbolPainter
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinTheme

/**
 * The favorite heart's shown state. [quietFlips] counts the changes that were
 * not the user's own tap — the service confirming a like late, a library
 * sync, the next track — each of which [FavoriteGlyphIcon] crossfades in
 * quietly instead of letting the symbol beat. A tap keeps [quietFlips], so
 * the same symbol animates it: the fill grows and the outline beats.
 */
@Immutable
data class FavoriteGlyph(val favorite: Boolean, val quietFlips: Int = 0) {
    /** This glyph after the heart became [favorite]; [fromUser]: the user's own write did it. */
    fun next(favorite: Boolean, fromUser: Boolean): FavoriteGlyph = when {
        favorite == this.favorite -> this
        fromUser -> copy(favorite = favorite)
        else -> FavoriteGlyph(favorite, quietFlips + 1)
    }
}

/**
 * The favorite symbol for [favorite]. A change within one [quietFlips] value
 * (the user's tap) runs the symbol's own motion. A new [quietFlips] value
 * starts a fresh symbol already at its state and crossfades it over the old
 * one on the effects spring — fill and colour change, nothing beats.
 */
@Composable
fun FavoriteGlyphIcon(
    favorite: Boolean,
    quietFlips: Int,
    contentDescription: String?,
    tint: Color,
    modifier: Modifier = Modifier
) {
    val latestFavorite by rememberUpdatedState(favorite)
    val latestFlips by rememberUpdatedState(quietFlips)
    // The state each flip last showed, for the one fading out.
    val shownByFlip = remember { HashMap<Int, Boolean>() }
    SideEffect {
        shownByFlip[quietFlips] = favorite
        shownByFlip.keys.removeAll { it < quietFlips - KEPT_FLIPS }
    }
    Crossfade(
        targetState = quietFlips,
        modifier = modifier,
        animationSpec = YoinMotion.effectsSpring(),
        label = "favoriteQuietFlip"
    ) { flip ->
        val current = flip == latestFlips
        Icon(
            painter = rememberFavoriteSymbolPainter(
                favorite = if (current) latestFavorite else shownByFlip[flip] ?: !latestFavorite
            ),
            contentDescription = contentDescription.takeIf { current },
            tint = tint,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/** Flips older than this many behind the current one have long faded out. */
private const val KEPT_FLIPS = 2

@Preview(showBackground = true)
@Composable
private fun FavoriteGlyphIconPreview() {
    YoinTheme {
        Row {
            FavoriteGlyphIcon(
                favorite = false,
                quietFlips = 0,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            FavoriteGlyphIcon(
                favorite = true,
                quietFlips = 1,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
