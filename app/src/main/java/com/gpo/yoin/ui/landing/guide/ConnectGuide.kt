package com.gpo.yoin.ui.landing.guide

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpo.yoin.R
import com.gpo.yoin.data.source.spotify.SpotifyAuthConfig
import com.gpo.yoin.ui.theme.GoogleSansFlex
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotionSpeed

// Turn-by-turn for Spotify for Developers, like a maps app's floating window:
// one instruction at a time, what to tap named the way the site names it.
// Yoin cannot see the browser, so the user moves on with the window's own
// actions (Next / Previous); the redirect URIs are copied from it.

/** One step of the guide. [labels] are the site's own words, kept in English and drawn as keys. */
@Immutable
internal data class GuideStep(
    @param:StringRes val text: Int,
    val labels: List<String>,
    @param:StringRes val then: Int?,
    /** What this step's copy action puts on the clipboard, if it has one. */
    val copies: String? = null,
)

internal val SpotifyGuideSteps: List<GuideStep> = listOf(
    GuideStep(R.string.guide_create, listOf("Create app"), R.string.guide_then_name),
    GuideStep(R.string.guide_name, listOf("Yoin"), R.string.guide_then_uris),
    GuideStep(R.string.guide_uri_first, listOf("Redirect URIs", "Add"), R.string.guide_then_second, copies = SpotifyAuthConfig.REDIRECT_URI),
    GuideStep(R.string.guide_uri_second, emptyList(), R.string.guide_then_apis, copies = SpotifyAuthConfig.APP_REMOTE_REDIRECT_URI),
    GuideStep(R.string.guide_apis, listOf("Web API", "Android", "Save"), R.string.guide_then_client_id),
    GuideStep(R.string.guide_client_id, listOf("Client ID"), null),
)

internal const val SpotifyDashboardUrl = "https://developer.spotify.com/dashboard"

/** The guide's colours: the mark's navy into violet, the same in light and dark (it floats over any app). */
private val GuideNavy = Color(0xFF1B2496)
private val GuideViolet = Color(0xFF5A16C9)
private val KeyFill = Color.White.copy(alpha = 0.22f)
private val GuideGround = Color(0xFF0B0A1C)

/**
 * The guide's window. In picture-in-picture the card is the whole window. Full screen — the half second before
 * it shrinks into the corner — the same card sits centred at the picture-in-picture shape on a dark ground, and
 * [onCardBounds] reports where (window px), so the system's shrink starts from the card itself
 * (`sourceRectHint`) instead of from a full-screen page.
 */
@Composable
internal fun SpotifyGuideScreen(
    inPictureInPicture: Boolean,
    stepIndex: Int,
    flash: String?,
    onCardBounds: (Rect) -> Unit,
) {
    if (inPictureInPicture) {
        SpotifyGuideCard(stepIndex = stepIndex, flash = flash)
        return
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GuideGround),
        contentAlignment = Alignment.Center,
    ) {
        SpotifyGuideCard(
            stepIndex = stepIndex,
            flash = flash,
            modifier = Modifier
                .padding(24.dp)
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .aspectRatio(GuideAspectRatio)
                .clip(RoundedCornerShape(28.dp))
                .onGloballyPositioned { onCardBounds(it.boundsInWindow()) },
        )
    }
}

/** The picture-in-picture window's shape; the full-screen card uses the same so the shrink is a pure scale. */
internal const val GuideAspectRatio = 4f / 3f

/**
 * The guide card. It fills whatever it is given: the picture-in-picture window, or the centred card of
 * [SpotifyGuideScreen].
 */
@Composable
internal fun SpotifyGuideCard(
    stepIndex: Int,
    flash: String?,
    modifier: Modifier = Modifier,
) = BoxWithConstraints(modifier = modifier.fillMaxSize()) {
    // Everything scales with the window: the system decides how big the picture-in-picture window is (and the
    // user can pinch it), so the card is laid out for 240 x 180dp and scaled to fit.
    val scale = minOf(maxWidth / GuideDesignWidth, maxHeight / GuideDesignHeight).coerceIn(0.55f, 1.8f)
    GuideCardContent(stepIndex, flash, scale)
}

private val GuideDesignWidth = 240.dp
private val GuideDesignHeight = 180.dp

@Composable
private fun GuideCardContent(stepIndex: Int, flash: String?, scale: Float) {
    val step = SpotifyGuideSteps[stepIndex]
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(GuideNavy, GuideViolet))),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = (14 * scale).dp, vertical = (11 * scale).dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((8 * scale).dp)) {
                GuideMark(size = (22 * scale).dp)
                Text(
                    text = stringResource(R.string.guide_step, stepIndex + 1, SpotifyGuideSteps.size),
                    style = guideText(12 * scale, FontWeight.Medium),
                    color = Color.White.copy(alpha = 0.85f),
                )
            }
            AnimatedContent(
                targetState = stepIndex,
                transitionSpec = {
                    val forward = targetState > initialState
                    (YoinMotion.fadeIn(role = YoinMotionRole.Standard) + YoinMotion.slideInHorizontally(role = YoinMotionRole.Standard, speed = YoinMotionSpeed.Fast) { if (forward) it / 8 else -it / 8 })
                        .togetherWith(
                            YoinMotion.fadeOut(role = YoinMotionRole.Standard, speed = YoinMotionSpeed.Fast) +
                                YoinMotion.slideOutHorizontally(role = YoinMotionRole.Standard, speed = YoinMotionSpeed.Fast) { if (forward) -it / 8 else it / 8 },
                        )
                },
                label = "guideStep",
                modifier = Modifier.weight(1f),
            ) { index ->
                val current = SpotifyGuideSteps[index]
                Column {
                    Text(
                        text = instruction(current),
                        style = guideText(17 * scale, FontWeight.SemiBold, lineHeight = 23 * scale),
                        color = Color.White,
                        modifier = Modifier.padding(top = (7 * scale).dp),
                    )
                }
            }
            step.then?.let {
                Text(
                    text = stringResource(it),
                    style = guideText(12 * scale, FontWeight.Normal),
                    color = Color.White.copy(alpha = 0.72f),
                )
            }
        }
        AnimatedVisibility(
            visible = flash != null,
            enter = YoinMotion.fadeIn(role = YoinMotionRole.Standard, speed = YoinMotionSpeed.Fast),
            exit = YoinMotion.fadeOut(role = YoinMotionRole.Standard),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x8C0A0828)),
                contentAlignment = Alignment.Center,
            ) {
                Text(flash.orEmpty(), style = guideText(16 * scale, FontWeight.Bold), color = Color.White)
            }
        }
    }
}

@Composable
private fun instruction(step: GuideStep): AnnotatedString {
    // The labels go in as format args; each is then drawn as a key (bold on a light fill).
    val raw = stringResource(step.text, *step.labels.toTypedArray())
    return buildAnnotatedString {
        var cursor = 0
        val spans = step.labels.mapNotNull { label ->
            val at = raw.indexOf(label)
            if (at >= 0) at to label else null
        }.sortedBy { it.first }
        spans.forEach { (at, label) ->
            if (at < cursor) return@forEach
            append(raw.substring(cursor, at))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, background = KeyFill)) { append(" $label ") }
            cursor = at + label.length
        }
        append(raw.substring(cursor))
    }
}

private fun guideText(size: Float, weight: FontWeight, lineHeight: Float = size * 1.3f) = TextStyle(
    fontFamily = GoogleSansFlex,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = 0.sp,
)

/** The three-fin mark, small: pink, a lighter violet (it sits on violet) and white. */
@Composable
private fun GuideMark(size: Dp) {
    Box(Modifier.size(width = size * 1.25f, height = size)) {
        listOf(
            R.drawable.landing_mascot_pink to null,
            R.drawable.landing_mascot_violet to ColorFilter.tint(Color(0xFFB98CFF)),
            R.drawable.landing_mascot_navy to ColorFilter.tint(Color.White),
        ).forEach { (drawable, filter) ->
            Image(painterResource(drawable), contentDescription = null, colorFilter = filter, modifier = Modifier.fillMaxSize())
        }
    }
}

@Preview(widthDp = 240, heightDp = 180)
@Composable
private fun SpotifyGuideCardPreview() {
    SpotifyGuideCard(stepIndex = 2, flash = null)
}

@Preview(widthDp = 252, heightDp = 162)
@Composable
private fun SpotifyGuideCardLastPreview() {
    Column(Modifier.height(162.dp).width(252.dp)) {
        SpotifyGuideCard(stepIndex = 5, flash = null)
    }
}
