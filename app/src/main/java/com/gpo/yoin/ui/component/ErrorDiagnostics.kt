package com.gpo.yoin.ui.component

import android.content.ClipData
import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import com.gpo.yoin.BuildConfig
import com.gpo.yoin.R
import com.gpo.yoin.data.remote.applemusic.AppleMusicApiException
import com.gpo.yoin.data.repository.SubsonicException
import com.gpo.yoin.data.source.spotify.SpotifyAuthException
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.YoinMotion
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.launch
import retrofit2.HttpException

/**
 * What a tester pastes back when a page fails, since a release build logs nothing: the build, the device, and
 * the failure — for an Apple Music refusal its status, Apple's code and which request it was, then the
 * failures it was retried through (a library playlist's catalog read). Never a message (a server's or an
 * exception's can quote a URL, a host or a name), a token, or an id from the user's library.
 */
fun Throwable.diagnosticReport(
    now: Instant = Instant.now(),
    device: String = deviceLine(),
): String = buildString {
    appendLine("Yoin ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    appendLine(device)
    appendDiagnostic(this@diagnosticReport, prefix = "")
    suppressed.forEach { appendDiagnostic(it, prefix = "then ") }
    append(now.truncatedTo(ChronoUnit.SECONDS))
}

private fun StringBuilder.appendDiagnostic(failure: Throwable, prefix: String) {
    appendLine(prefix + failure.diagnosticLine())
    (failure as? AppleMusicApiException)?.request?.let { appendLine("GET $it") }
    failure.cause?.takeIf { it !== failure }?.let { appendLine("cause ${it.diagnosticLine()}") }
}

private fun Throwable.diagnosticLine(): String = when (this) {
    is AppleMusicApiException -> listOfNotNull(
        "Apple Music ${failure::class.simpleName}",
        status?.let { "HTTP $it" },
        appleCode?.let { "Apple $it" },
    ).joinToString(", ")
    is SubsonicException -> "Subsonic $code"
    is SpotifyAuthException -> "Spotify auth $code" + (oauthError?.let { " $it" } ?: "")
    is HttpException -> "HTTP ${code()}"
    else -> javaClass.simpleName.ifEmpty { javaClass.name }
}

private fun deviceLine(): String =
    "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}"

/**
 * Copies [report] for the user to send on. Android 13+ confirms a copy itself; the label also turns to Copied,
 * which older versions otherwise wouldn't show.
 */
@Composable
fun CopyDiagnosticsButton(
    report: String,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboard.current
    val haptics = rememberYoinHaptics()
    val scope = rememberCoroutineScope()
    val clipLabel = stringResource(R.string.cmp_error_details_clip_label)
    var copied by remember(report) { mutableStateOf(false) }
    TextButton(
        onClick = {
            haptics.performClick()
            scope.launch {
                clipboard.setClipEntry(ClipData.newPlainText(clipLabel, report).toClipEntry())
                copied = true
            }
        },
        modifier = modifier,
    ) {
        Crossfade(targetState = copied, animationSpec = YoinMotion.fastEffectsSpec(), label = "diagnosticsCopied") {
            Text(stringResource(if (it) R.string.cmp_error_details_copied else R.string.cmp_error_copy_details))
        }
    }
}
