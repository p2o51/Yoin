package com.gpo.yoin.player.applemusic

import android.app.Application
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.apple.android.music.playback.model.MediaPlayerException
import com.gpo.yoin.R
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MusicKitPlaybackErrorTest {
    @Test
    fun should_identifyFailedOperation_when_sdkReportsKnownErrorType() {
        // A queue or I/O failure must no longer be reported as a subscription/reconnection problem.
        val messages = mapOf(
            MediaPlayerException.TYPE_IO to R.string.player_apple_music_io_failed,
            MediaPlayerException.TYPE_DRM to R.string.player_apple_music_drm_failed,
            MediaPlayerException.TYPE_PLAYBACK_QUEUE to R.string.player_apple_music_queue_failed,
            MediaPlayerException.TYPE_PLAYBACK_LEASE to R.string.player_apple_music_session_failed,
            MediaPlayerException.TYPE_CHROMECAST_ERROR to R.string.player_apple_music_cast_failed,
            MediaPlayerException.TYPE_EXPLICIT_VERIFY_AGE to R.string.player_apple_music_age_verification
        )

        messages.forEach { (type, message) -> assertEquals(message, musicKitPlaybackErrorMessage(type)) }
    }

    @Test
    fun should_offerGenericRetry_when_errorTypeIsUnknownEvenWithCode401() {
        // SDK component codes are not HTTP status codes. The Android callback extracts only these numbers;
        // no Throwable, cause text or credentials can enter the formatter's primitive-only boundary.
        assertEquals(
            R.string.player_apple_music_play_failed,
            musicKitPlaybackErrorMessage(MediaPlayerException.TYPE_UNKNOWN)
        )
        assertEquals(R.string.player_apple_music_play_failed, musicKitPlaybackErrorMessage(Int.MAX_VALUE))
        assertEquals(
            "type=0 code=401 component=-2147483648",
            musicKitPlaybackErrorDiagnostic(MediaPlayerException.TYPE_UNKNOWN, 401, MediaPlayerException.ERROR_UNKNOWN)
        )
    }

    @Test
    fun should_preserveOnlyNumericDiagnostics_when_sdkCodesAreMissingOrNegative() {
        assertEquals(
            "type=3 code=-2147483648 component=-2147483648",
            musicKitPlaybackErrorDiagnostic(
                MediaPlayerException.TYPE_PLAYBACK_QUEUE,
                MediaPlayerException.ERROR_UNKNOWN,
                MediaPlayerException.ERROR_UNKNOWN
            )
        )
        assertEquals("type=2 code=-1009 component=7", musicKitPlaybackErrorDiagnostic(2, -1009, 7))
    }

    @Test
    fun should_avoidUnprovenAccountAdvice_when_playbackErrorIsShownInEitherLanguage() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val messages = (0..6).map(::musicKitPlaybackErrorMessage) + R.string.player_apple_music_timeout

        for (language in listOf("en", "zh-CN")) {
            val config = Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
            val context = app.createConfigurationContext(config)
            messages.forEach { id ->
                val text = context.getString(id)
                for (unsupportedAdvice in listOf("reconnect", "subscription", "重新连接", "订阅")) {
                    assertFalse("$language: $text", text.contains(unsupportedAdvice, ignoreCase = true))
                }
            }
            val queueMessage = context.getString(musicKitPlaybackErrorMessage(MediaPlayerException.TYPE_PLAYBACK_QUEUE))
            val expected = if (language == "en") {
                "Apple Music could not prepare this queue. Try another song or reopen the playlist."
            } else {
                "Apple Music 无法准备此队列。请换一首歌曲或重新打开播放列表。"
            }
            assertEquals(expected, queueMessage)
        }
    }
}
