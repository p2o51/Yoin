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
import com.gpo.yoin.data.repository.FavoriteState
import com.gpo.yoin.symbols.rememberFavoriteSymbolPainter
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinTheme

/**
 * The favorite heart's shown state (D4). [quietFlips] counts the service's
 * answers that came in late and flipped the heart — Spotify confirming a like
 * after the page is up, or saying it was unliked elsewhere — each of which
 * [FavoriteGlyphIcon] crossfades in quietly instead of letting the symbol
 * beat. Every other change keeps [quietFlips], so the same symbol animates
 * it as ever (the fill grows and the outline beats): a tap, a failed write
 * rolling back, a library sync, another track. [answeredAtMs]: the newest
 * answer shown so far, which tells one coming in from one fallen back to.
 */
@Immutable
data class FavoriteGlyph(val favorite: Boolean, val quietFlips: Int = 0, val answeredAtMs: Long = 0L) {
    /** This glyph after the heart's state became [state]. */
    fun next(state: FavoriteState): FavoriteGlyph {
        val lateAnswer = state.fromAnswer && state.answeredAtMs > answeredAtMs
        val shown = when {
            state.isStarred == favorite -> this
            lateAnswer -> copy(favorite = state.isStarred, quietFlips = quietFlips + 1)
            else -> copy(favorite = state.isStarred)
        }
        return if (state.answeredAtMs > shown.answeredAtMs) shown.copy(answeredAtMs = state.answeredAtMs) else shown
    }

    /**
     * This glyph as the heart of another track (Now Playing moving on), whose
     * state is [state]: a change like a tap's, not a late answer, so a liked
     * next track beats in; its answers count from here.
     */
    fun forTrack(state: FavoriteState): FavoriteGlyph =
        if (state.isStarred == favorite && state.answeredAtMs == answeredAtMs) {
            this
        } else {
            copy(favorite = state.isStarred, answeredAtMs = state.answeredAtMs)
        }

    companion object {
        /** The heart as first shown, at [state] (nothing to animate from). */
        fun of(state: FavoriteState): FavoriteGlyph = FavoriteGlyph(state.isStarred, answeredAtMs = state.answeredAtMs)
    }
}

/**
 * The favorite symbol for [favorite]. A change within one [quietFlips] value
 * (a tap, a rollback, another track) runs the symbol's own motion. A new
 * [quietFlips] value (a late answer) starts a fresh symbol already at its
 * state and crossfades it over the old one on the effects spring — fill and
 * colour change, nothing beats.
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
