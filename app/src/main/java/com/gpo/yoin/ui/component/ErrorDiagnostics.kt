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
import com.gpo.yoin.data.source.spotify.SpotifyResponseDecodeException
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.theme.YoinMotion
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.launch
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

/**
 * What a tester pastes back when a page fails, since a release build logs nothing: the build, the device, and
 * the failure — for an Apple Music refusal its status, Apple's code and which request it was, then the
 * failures it was retried through (a library playlist's catalog read); for a Spotify one its status, Spotify's
 * reason with ids taken out, the request as a template and whose developer app it went out under; for a
 * response that wouldn't decode, where in the JSON it stopped. Never a raw message (a server's or an
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
    val spotifyRequest = when (failure) {
        is SpotifyAuthException -> failure.request?.let { it to failure.clientIdKind }
        is SpotifyResponseDecodeException -> failure.request to failure.clientIdKind
        else -> null
    }
    spotifyRequest?.let { (request, clientIdKind) ->
        appendLine(request)
        clientIdKind?.let { appendLine("Spotify client ID ${it.label}") }
    }
    failure.cause?.takeIf { it !== failure }?.let { appendLine("cause ${it.diagnosticLine()}") }
}

private fun Throwable.diagnosticLine(): String = when (this) {
    is AppleMusicApiException -> listOfNotNull(
        "Apple Music ${failure::class.simpleName}",
        status?.let { "HTTP $it" },
        appleCode?.let { "Apple $it" },
    ).joinToString(", ")
    is SubsonicException -> "Subsonic $code"
    // A Web API refusal says Spotify's reason; the token endpoint, its OAuth error.
    is SpotifyAuthException -> if (request != null) {
        "Spotify HTTP $code" + (reason?.let { ": $it" } ?: "")
    } else {
        "Spotify auth $code" + (oauthError?.let { " $it" } ?: "")
    }
    is SpotifyResponseDecodeException -> "Spotify response unreadable"
    // Where in the JSON decoding stopped, never the JSON itself (the message quotes it).
    is SerializationException -> listOfNotNull(
        javaClass.simpleName.ifEmpty { javaClass.name },
        (this as? MissingFieldException)?.missingFields?.joinToString(prefix = "missing ", separator = ", "),
        message?.let(JSON_PATH_REGEX::find)?.groupValues?.get(1)?.let { "at $it" },
    ).joinToString(" ")
    is HttpException -> "HTTP ${code()}"
    else -> javaClass.simpleName.ifEmpty { javaClass.name }
}

private val JSON_PATH_REGEX = Regex("at path: (\\$[^\\s]*)")

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
