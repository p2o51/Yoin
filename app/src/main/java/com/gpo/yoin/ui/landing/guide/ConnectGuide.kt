package com.gpo.yoin.ui.landing.guide

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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
import java.security.MessageDigest

// Turn-by-turn floating windows for the two services that send people out of
// Yoin, like a maps app's: one instruction at a time, what to tap named the way
// the other side names it. Yoin cannot see the browser, so on Spotify for
// Developers the user moves on with the window's own actions (Next / Previous)
// and copies each value from it; on Apple's sign-in the window follows Yoin's
// attempts instead (owner 2026-10-10: one sign-in often doesn't take).

/** Which floating guide. */
internal enum class GuideKind { Spotify, AppleMusic }

/** A value a guide step puts on the clipboard, with its copy action's label. */
internal enum class GuideCopy(@param:StringRes val actionLabel: Int) {
    RedirectUri(R.string.guide_action_copy_first),
    AppRemoteRedirectUri(R.string.guide_action_copy_second),
    PackageName(R.string.guide_action_copy_package),
    Sha1(R.string.guide_action_copy_sha1),
    ;

    /** The value for this install: the package and signing certificate are this APK's own. */
    fun value(context: Context): String? = when (this) {
        RedirectUri -> SpotifyAuthConfig.REDIRECT_URI
        AppRemoteRedirectUri -> SpotifyAuthConfig.APP_REMOTE_REDIRECT_URI
        PackageName -> context.packageName
        Sha1 -> signingCertificateSha1(context)
    }
}

/** One step of a guide. [labels] are the other side's own words, kept in English and drawn as keys. */
@Immutable
internal data class GuideStep(
    @param:StringRes val text: Int,
    val labels: List<String>,
    @param:StringRes val then: Int?,
    /** What this step's copy action puts on the clipboard, if it has one. */
    val copies: GuideCopy? = null,
)

/**
 * Spotify for Developers, Create app. Ticking Android opens the "Android packages" rows: without this
 * install's package name and signing fingerprint there, App Remote refuses to control playback.
 */
internal val SpotifyGuideSteps: List<GuideStep> = listOf(
    GuideStep(R.string.guide_create, listOf("Create app"), R.string.guide_then_name),
    GuideStep(R.string.guide_name, listOf("Yoin"), R.string.guide_then_uris),
    GuideStep(R.string.guide_uri_first, listOf("Redirect URIs", "Add"), R.string.guide_then_second, copies = GuideCopy.RedirectUri),
    GuideStep(R.string.guide_uri_second, emptyList(), R.string.guide_then_apis, copies = GuideCopy.AppRemoteRedirectUri),
    GuideStep(R.string.guide_apis, listOf("Web API", "Android"), R.string.guide_then_package),
    GuideStep(R.string.guide_package, listOf("Android packages"), R.string.guide_then_sha1, copies = GuideCopy.PackageName),
    GuideStep(R.string.guide_sha1, listOf("Add"), R.string.guide_then_save, copies = GuideCopy.Sha1),
    GuideStep(R.string.guide_save, listOf("Save"), R.string.guide_then_client_id),
    GuideStep(R.string.guide_client_id, listOf("Client ID"), null),
)

/** Apple's sign-in. Step 0 while it's open; step [AppleMusicRetryStep] after an attempt that didn't take. */
internal val AppleMusicGuideSteps: List<GuideStep> = listOf(
    GuideStep(R.string.guide_apple_sign_in, emptyList(), R.string.guide_apple_then_tries),
    GuideStep(R.string.guide_apple_retry, emptyList(), null),
)

internal const val AppleMusicRetryStep = 1

internal fun guideSteps(kind: GuideKind): List<GuideStep> = when (kind) {
    GuideKind.Spotify -> SpotifyGuideSteps
    GuideKind.AppleMusic -> AppleMusicGuideSteps
}

/** SHA-1 of this APK's signing certificate as Spotify's dashboard takes it: `E7:47:B5:…`, upper case. */
internal fun signingCertificateSha1(context: Context): String? = runCatching {
    val pm = context.packageManager
    val certificate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            .signingInfo?.apkContentsSigners?.firstOrNull()
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
    } ?: return null
    formatFingerprint(MessageDigest.getInstance("SHA-1").digest(certificate.toByteArray()))
}.getOrNull()

internal fun formatFingerprint(digest: ByteArray): String = digest.joinToString(":") { "%02X".format(it) }

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
internal fun ConnectGuideScreen(
    kind: GuideKind,
    inPictureInPicture: Boolean,
    stepIndex: Int,
    flash: String?,
    onCardBounds: (Rect) -> Unit,
) {
    if (inPictureInPicture) {
        ConnectGuideCard(kind = kind, stepIndex = stepIndex, flash = flash)
        return
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GuideGround),
        contentAlignment = Alignment.Center,
    ) {
        ConnectGuideCard(
            kind = kind,
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
    stepIndex: Int,
    flash: String?,
    modifier: Modifier = Modifier,
) = BoxWithConstraints(modifier = modifier.fillMaxSize()) {
    // Everything scales with the window: the system decides how big the picture-in-picture window is (and the
    // user can pinch it), so the card is laid out for 240 x 180dp and scaled to fit.
    val scale = minOf(maxWidth / GuideDesignWidth, maxHeight / GuideDesignHeight).coerceIn(0.55f, 1.8f)
    GuideCardContent(kind, stepIndex, flash, scale)
}

private val GuideDesignWidth = 240.dp
private val GuideDesignHeight = 180.dp

@Composable
private fun GuideCardContent(kind: GuideKind, stepIndex: Int, flash: String?, scale: Float) {
    val steps = guideSteps(kind)
    val step = steps[stepIndex.coerceIn(0, steps.lastIndex)]
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
                    // Spotify is a numbered walk; Apple's sign-in is one thing that may need repeating.
                    text = when (kind) {
                        GuideKind.Spotify -> stringResource(R.string.guide_step, stepIndex + 1, steps.size)
                        GuideKind.AppleMusic -> stringResource(R.string.guide_apple_title)
                    },
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
                val current = steps[index.coerceIn(0, steps.lastIndex)]
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
    ConnectGuideCard(kind = GuideKind.Spotify, stepIndex = 6, flash = null)
}

@Preview(widthDp = 252, heightDp = 162)
@Composable
private fun AppleMusicGuideCardPreview() {
    Column(Modifier.height(162.dp).width(252.dp)) {
        ConnectGuideCard(kind = GuideKind.AppleMusic, stepIndex = 0, flash = null)
    }
}
