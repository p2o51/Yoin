package com.gpo.yoin.ui.landing.guide

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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

// Turn-by-turn floating windows for the places that send people out of Yoin,
// like a maps app's: one instruction at a time, what to tap named the way the
// other side names it. Yoin cannot see the browser or Spotoolfy, so there the
// user moves on with the window's own actions (Next / Previous) and, on Spotify
// for Developers, copies each value from it; on Apple's sign-in the window
// follows Yoin's attempts instead (owner 2026-10-10: one sign-in often doesn't
// take).

/** Which floating guide. */
internal enum class GuideKind { Spotify, AppleMusic, Spotoolfy }

/** A value a guide step puts on the clipboard, with its copy action's label. */
internal enum class GuideCopy(@param:StringRes val actionLabel: Int) {
    RedirectUri(R.string.guide_action_copy_first),
    AppRemoteRedirectUri(R.string.guide_action_copy_second),
    PackageName(R.string.guide_action_copy_package),
    Sha1(R.string.guide_action_copy_sha1),
    ;

    /** The value for this install: the package and signing certificate are this APK's own. */
    fun value(context: Context, fingerprint: String? = null): String? = when (this) {
        RedirectUri -> SpotifyAuthConfig.REDIRECT_URI
        AppRemoteRedirectUri -> SpotifyAuthConfig.APP_REMOTE_REDIRECT_URI
        PackageName -> context.packageName
        Sha1 -> fingerprint
    }
}

/**
 * One step of a guide. [labels] are the other side's own words, drawn as keys: kept in English for Spotify's
 * dashboard; [labelRes] for Spotoolfy, whose words follow the phone's language as Yoin's do. Both go into [text]
 * as format args, [labels] first.
 */
@Immutable
internal data class GuideStep(
    @param:StringRes val text: Int,
    val labels: List<String>,
    @param:StringRes val then: Int?,
    /** What this step's copy action puts on the clipboard, if it has one. */
    val copies: GuideCopy? = null,
    val labelRes: List<Int> = emptyList(),
    val fingerprint: String? = null,
) {
    fun copyValue(context: Context): String? = copies?.value(context, fingerprint)
}

/**
 * Spotify for Developers, Create app. Ticking Android opens the "Android packages" rows: without this
 * install's package name and signing fingerprint there, App Remote refuses to control playback.
 */
private val SpotifyGuideIntro: List<GuideStep> = listOf(
    GuideStep(R.string.guide_create, listOf("Create app"), R.string.guide_then_name),
    GuideStep(R.string.guide_name, listOf("Yoin"), R.string.guide_then_uris),
    GuideStep(R.string.guide_uri_first, listOf("Redirect URIs", "Add"), R.string.guide_then_second, copies = GuideCopy.RedirectUri),
    GuideStep(R.string.guide_uri_second, emptyList(), R.string.guide_then_apis, copies = GuideCopy.AppRemoteRedirectUri),
    GuideStep(R.string.guide_apis, listOf("Web API", "Android"), R.string.guide_then_package),
)

internal fun spotifyGuideSteps(fingerprints: List<String>): List<GuideStep> = buildList {
    addAll(SpotifyGuideIntro)
    val certificates = fingerprints.distinct().ifEmpty { listOf(null) }
    certificates.forEachIndexed { index, fingerprint ->
        // Each dashboard entry needs the same package name and one distinct certificate.
        add(
            GuideStep(
                R.string.guide_package,
                listOf("Android packages"),
                R.string.guide_then_sha1,
                copies = GuideCopy.PackageName,
            ),
        )
        add(
            GuideStep(
                text = if (fingerprint == null) R.string.guide_sha1_unavailable else R.string.guide_sha1,
                labels = if (fingerprint == null) emptyList() else listOf("Add"),
                then = if (index == certificates.lastIndex) {
                    R.string.guide_then_save
                } else {
                    R.string.guide_then_package
                },
                copies = if (fingerprint == null) null else GuideCopy.Sha1,
                fingerprint = fingerprint,
            ),
        )
    }
    add(GuideStep(R.string.guide_save, listOf("Save"), R.string.guide_then_client_id))
    add(GuideStep(R.string.guide_client_id, listOf("Client ID"), null))
}

/** Apple's sign-in. Step 0 while it's open; step [AppleMusicRetryStep] after an attempt that didn't take. */
internal val AppleMusicGuideSteps: List<GuideStep> = listOf(
    GuideStep(R.string.guide_apple_sign_in, emptyList(), R.string.guide_apple_then_tries),
    GuideStep(R.string.guide_apple_retry, emptyList(), null),
)

internal const val AppleMusicRetryStep = 1

/**
 * Spotoolfy 1.3.x, from its home page to the share sheet (docs/spotoolfy-migration.md §5.3): the Client ID first,
 * so the Spotify step is filled in when the user comes back; an empty box means Spotoolfy's built-in ID, which
 * Yoin can't use, and the step is skipped.
 */
internal val SpotoolfyGuideSteps: List<GuideStep> = listOf(
    GuideStep(R.string.guide_spotoolfy_settings, emptyList(), R.string.guide_spotoolfy_then_client_id),
    GuideStep(
        R.string.guide_spotoolfy_client_id,
        listOf("Spotify API"),
        R.string.guide_spotoolfy_then_empty,
        labelRes = listOf(R.string.guide_spotoolfy_label_client_id),
    ),
    GuideStep(
        R.string.guide_spotoolfy_export,
        emptyList(),
        R.string.guide_spotoolfy_then_share,
        labelRes = listOf(R.string.guide_spotoolfy_label_data, R.string.guide_spotoolfy_label_export),
    ),
    GuideStep(R.string.guide_spotoolfy_share, emptyList(), null, labelRes = listOf(R.string.spotoolfy_share_label)),
)

internal fun guideSteps(kind: GuideKind, fingerprints: List<String>): List<GuideStep> = when (kind) {
    GuideKind.Spotify -> spotifyGuideSteps(fingerprints)
    GuideKind.AppleMusic -> AppleMusicGuideSteps
    GuideKind.Spotoolfy -> SpotoolfyGuideSteps
}

/** What one of the guide window's actions does when tapped, drawn with [icon]. */
internal enum class GuideCommand(@param:DrawableRes val icon: Int) {
    Prev(R.drawable.ic_guide_prev),
    Copy(R.drawable.ic_guide_copy),
    Next(R.drawable.ic_guide_next),
    Return(R.drawable.ic_guide_return),

    /** A blank place that never fires. */
    None(R.drawable.ic_guide_blank)
}

/**
 * One place in the guide window's action row (the system's picture-in-picture menu: tap the window to see it).
 * A place the step can't use stays in the row with [enabled] false, which the system draws greyed and ignores.
 * [label] is what TalkBack reads; a blank place has none, so TalkBack passes over it as the eye does.
 */
@Immutable
internal data class GuideActionSlot(
    val command: GuideCommand,
    @param:StringRes val label: Int?,
    val enabled: Boolean
)

/** Every step of every guide has this many action places. */
internal const val GUIDE_ACTION_SLOT_COUNT = 3

/** The place › keeps on every step. */
internal const val GUIDE_NEXT_SLOT = 2

/**
 * The guide window's action row on [step]: always [GUIDE_ACTION_SLOT_COUNT] places in the same order, so a quick
 * second tap where › was finds › again (owner 2026-10-11). The system centres the row, so a row that grows or
 * shrinks moves every button.
 *
 * - Spotify and Spotoolfy: ‹ | middle | ›. ‹ is greyed on the first step. The middle copies the step's value,
 *   greyed where there is nothing to copy (every Spotoolfy step: the user copies inside Spotoolfy); on the last step
 *   it is "back to Yoin" and › is greyed, so tapping on stops there instead of closing the guide.
 * - Apple Music: Yoin drives the sign-in, so nothing to tap: blank, unlabelled places that never fire. An empty row
 *   would let the system fill it with Yoin's own playback buttons, whose ⏭ skips a track.
 */
internal fun guideActionSlots(kind: GuideKind, step: Int, steps: List<GuideStep>): List<GuideActionSlot> {
    val index = step.coerceIn(0, steps.lastIndex)
    return when (kind) {
        GuideKind.AppleMusic -> List(GUIDE_ACTION_SLOT_COUNT) { AppleMusicBlankSlot }
        GuideKind.Spotify, GuideKind.Spotoolfy -> {
            val last = index == steps.lastIndex
            val copies = steps[index].copies
            listOf(
                GuideActionSlot(GuideCommand.Prev, R.string.guide_action_prev, enabled = index > 0),
                when {
                    last -> GuideActionSlot(GuideCommand.Return, R.string.guide_action_back, enabled = true)
                    copies != null -> GuideActionSlot(GuideCommand.Copy, copies.actionLabel, enabled = true)
                    else -> GuideActionSlot(GuideCommand.Copy, R.string.guide_action_copy, enabled = false)
                },
                GuideActionSlot(GuideCommand.Next, R.string.guide_action_next, enabled = !last)
            )
        }
    }
}

private val AppleMusicBlankSlot = GuideActionSlot(GuideCommand.None, label = null, enabled = false)

/**
 * The row as the system takes it. [max] is its picture-in-picture action limit, 3 on phones and tablets, so this
 * is normally the whole row. With fewer places the row loses places from the left (‹ first) and every kept place
 * keeps its command, greyed or not: the last place is always ›, greyed on the last step, so tapping on stops there
 * even when "back to Yoin" has no room (owner 2026-10-11; the system's expand button still goes back to Yoin).
 * Cut here rather than by the system, which keeps the first places and would drop ›.
 */
internal fun fitGuideActionSlots(slots: List<GuideActionSlot>, max: Int): List<GuideActionSlot> =
    slots.takeLast(max.coerceAtLeast(1))

internal fun formatFingerprint(digest: ByteArray): String = digest.joinToString(":") { "%02X".format(it) }

internal const val SpotifyDashboardUrl = "https://developer.spotify.com/dashboard"

/** Spotoolfy's Android package (declared under <queries>). */
internal const val SpotoolfyPackage = "com.gojyuplusone.spotoolfy.spotoolfy_flutter"

/** Spotoolfy's icon tile colour. */
internal val SpotoolfyIconTeal = Color(0xFF71ACB3)

/**
 * A guide's colours. Yoin's own guides: the mark's navy into violet, the same in light and dark (they float over any
 * app). Spotoolfy's (D10): its icon's teal, light or dark with the system, so it reads as "in Spotoolfy now" and
 * never as Yoin's violet Spotify window. Local to the window, not Yoin's theme.
 */
@Immutable
private data class GuidePalette(
    val card: List<Color>,
    val ground: Color,
    val text: Color,
    val keyFill: Color,
    val flashScrim: Color,
    /** Spotoolfy's icon tile; null draws Yoin's own mark. */
    val markFill: Color?,
)

private val YoinGuidePalette = GuidePalette(
    card = listOf(Color(0xFF1B2496), Color(0xFF5A16C9)),
    ground = Color(0xFF0B0A1C),
    text = Color.White,
    keyFill = Color.White.copy(alpha = 0.22f),
    flashScrim = Color(0x8C0A0828),
    markFill = null,
)

private val SpotoolfyLightPalette = GuidePalette(
    card = listOf(Color(0xFFDDFFFF), Color(0xFFDDFFFF)),
    ground = Color(0xFFDDFFFF),
    text = Color(0xFF0B3438),
    keyFill = Color(0xFF4FA6B0).copy(alpha = 0.24f),
    flashScrim = Color(0xD9DDFFFF),
    markFill = SpotoolfyIconTeal,
)

private val SpotoolfyDarkPalette = GuidePalette(
    card = listOf(Color(0xFF12464C), Color(0xFF12464C)),
    ground = Color(0xFF0B3438),
    text = Color(0xFFDDFFFF),
    keyFill = Color(0xFF8FD3DB).copy(alpha = 0.26f),
    flashScrim = Color(0xD912464C),
    markFill = SpotoolfyIconTeal,
)

@Composable
private fun guidePalette(kind: GuideKind): GuidePalette = when (kind) {
    GuideKind.Spotoolfy -> if (isSystemInDarkTheme()) SpotoolfyDarkPalette else SpotoolfyLightPalette
    else -> YoinGuidePalette
}

/**
 * The guide's window. In picture-in-picture the card is the whole window. Full screen — the half second before
 * it shrinks into the corner — the same card sits centred at the picture-in-picture shape on a dark ground, and
 * [onCardBounds] reports where (window px), so the system's shrink starts from the card itself
 * (`sourceRectHint`) instead of from a full-screen page.
 */
@Composable
internal fun ConnectGuideScreen(
    kind: GuideKind,
    steps: List<GuideStep>,
    inPictureInPicture: Boolean,
    stepIndex: Int,
    flash: String?,
    onCardBounds: (Rect) -> Unit,
) {
    if (inPictureInPicture) {
        ConnectGuideCard(kind = kind, steps = steps, stepIndex = stepIndex, flash = flash)
        return
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(guidePalette(kind).ground),
        contentAlignment = Alignment.Center,
    ) {
        ConnectGuideCard(
            kind = kind,
            steps = steps,
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
 * [ConnectGuideScreen].
 */
@Composable
internal fun ConnectGuideCard(
    kind: GuideKind,
    steps: List<GuideStep>,
    stepIndex: Int,
    flash: String?,
    modifier: Modifier = Modifier,
) = BoxWithConstraints(modifier = modifier.fillMaxSize()) {
    // Everything scales with the window: the system decides how big the picture-in-picture window is (and the
    // user can pinch it), so the card is laid out for 240 x 180dp and scaled to fit.
    val scale = minOf(maxWidth / GuideDesignWidth, maxHeight / GuideDesignHeight).coerceIn(0.55f, 1.8f)
    GuideCardContent(kind, steps, stepIndex, flash, scale)
}

private val GuideDesignWidth = 240.dp
private val GuideDesignHeight = 180.dp

@Composable
private fun GuideCardContent(kind: GuideKind, steps: List<GuideStep>, stepIndex: Int, flash: String?, scale: Float) {
    val step = steps[stepIndex.coerceIn(0, steps.lastIndex)]
    val palette = guidePalette(kind)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(palette.card)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = (14 * scale).dp, vertical = (11 * scale).dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((8 * scale).dp)) {
                if (palette.markFill != null) {
                    SpotoolfyMark(size = (22 * scale).dp, fill = palette.markFill)
                } else {
                    GuideMark(size = (22 * scale).dp)
                }
                Text(
                    // Spotify and Spotoolfy are numbered walks; Apple's sign-in is one thing that may need repeating.
                    text = when (kind) {
                        GuideKind.Spotify, GuideKind.Spotoolfy ->
                            stringResource(R.string.guide_step, stepIndex + 1, steps.size)
                        GuideKind.AppleMusic -> stringResource(R.string.guide_apple_title)
                    },
                    style = guideText(12 * scale, FontWeight.Medium),
                    color = palette.text.copy(alpha = 0.85f),
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
                val current = steps[index.coerceIn(0, steps.lastIndex)]
                Column {
                    Text(
                        text = instruction(current, palette.keyFill),
                        style = guideText(17 * scale, FontWeight.SemiBold, lineHeight = 23 * scale),
                        color = palette.text,
                        modifier = Modifier.padding(top = (7 * scale).dp),
                    )
                }
            }
            step.then?.let {
                Text(
                    text = stringResource(it),
                    style = guideText(12 * scale, FontWeight.Normal),
                    color = palette.text.copy(alpha = 0.72f),
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
                    .background(palette.flashScrim),
                contentAlignment = Alignment.Center,
            ) {
                Text(flash.orEmpty(), style = guideText(16 * scale, FontWeight.Bold), color = palette.text)
            }
        }
    }
}

@Composable
private fun instruction(step: GuideStep, keyFill: Color): AnnotatedString {
    // The labels go in as format args; each is then drawn as a key (bold on a light fill).
    val labels = step.labels + step.labelRes.map { stringResource(it) }
    val raw = stringResource(step.text, *labels.toTypedArray())
    return buildAnnotatedString {
        var cursor = 0
        val spans = labels.mapNotNull { label ->
            val at = raw.indexOf(label)
            if (at >= 0) at to label else null
        }.sortedBy { it.first }
        spans.forEach { (at, label) ->
            if (at < cursor) return@forEach
            append(raw.substring(cursor, at))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, background = keyFill)) { append(" $label ") }
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

/** Spotoolfy's icon: its white glyph on its teal tile. */
@Composable
internal fun SpotoolfyMark(size: Dp, fill: Color = SpotoolfyIconTeal) {
    Box(
        modifier = Modifier
            .size(size)
            .background(fill, RoundedCornerShape(size * 0.28f)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_spotoolfy_mark),
            contentDescription = null,
            colorFilter = ColorFilter.tint(Color.White),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Preview(widthDp = 240, heightDp = 180)
@Composable
private fun SpotoolfyGuideCardPreview() {
    ConnectGuideCard(kind = GuideKind.Spotoolfy, steps = SpotoolfyGuideSteps, stepIndex = 1, flash = null)
}

@Preview(widthDp = 240, heightDp = 180)
@Composable
private fun SpotifyGuideCardPreview() {
    ConnectGuideCard(
        kind = GuideKind.Spotify,
        steps = spotifyGuideSteps(listOf("00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33")),
        stepIndex = 6,
        flash = null,
    )
}

@Preview(widthDp = 252, heightDp = 162)
@Composable
private fun AppleMusicGuideCardPreview() {
    Column(Modifier.height(162.dp).width(252.dp)) {
        ConnectGuideCard(kind = GuideKind.AppleMusic, steps = AppleMusicGuideSteps, stepIndex = 0, flash = null)
    }
}
