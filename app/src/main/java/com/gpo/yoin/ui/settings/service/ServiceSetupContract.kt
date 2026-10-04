package com.gpo.yoin.ui.settings.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import com.gpo.yoin.data.profile.ProviderKind

/**
 * Services that own a setup page. Local files have no account setup.
 */
enum class SetupService(val key: String) {
    Subsonic("subsonic"),
    Spotify("spotify"),
    AppleMusic("applemusic"),
    ;

    companion object {
        fun fromKey(key: String?): SetupService = entries.firstOrNull { it.key == key } ?: Subsonic

        fun forProvider(provider: ProviderKind): SetupService? = when (provider) {
            ProviderKind.SUBSONIC -> Subsonic
            ProviderKind.SPOTIFY -> Spotify
            ProviderKind.APPLE_MUSIC -> AppleMusic
            ProviderKind.LOCAL -> null
        }
    }
}

/**
 * @param profileId non-null = manage an existing profile (edit / reconnect)
 *   instead of adding a new one.
 * @param focusClientId deep-link from the "No Client ID" snackbar: open the
 *   Spotify developer setup and focus its field.
 * @param face the tapped account's face (name, where, avatar shape — no
 *   secrets), so the page's first frame already shows the right avatar.
 */
data class ServiceSetupRequest(
    val service: SetupService,
    val profileId: String? = null,
    val focusClientId: Boolean = false,
    val face: AccountFace? = null,
)

/**
 * Settings → setup page. The result carries the profile id Settings should
 * activate: the switch must run in Settings' scope, because this page finishes
 * as soon as the profile is saved and would cancel it mid-flight.
 */
class ServiceSetupContract : ActivityResultContract<ServiceSetupRequest, String?>() {
    override fun createIntent(context: Context, input: ServiceSetupRequest): Intent =
        Intent(context, ServiceSetupActivity::class.java).apply {
            putExtra(EXTRA_SERVICE, input.service.key)
            putExtra(EXTRA_PROFILE_ID, input.profileId)
            putExtra(EXTRA_FOCUS_CLIENT_ID, input.focusClientId)
            input.face?.let { face ->
                putExtra(EXTRA_FACE_TITLE, face.title)
                putExtra(EXTRA_FACE_DETAIL, face.detail)
                putExtra(EXTRA_FACE_AVATAR_SHAPE, face.avatarShape)
                putExtra(EXTRA_FACE_PHOTO_URL, face.photoUrl)
            }
        }

    override fun parseResult(resultCode: Int, intent: Intent?): String? =
        if (resultCode == Activity.RESULT_OK) intent?.getStringExtra(EXTRA_ACTIVATE_PROFILE_ID) else null

    companion object {
        internal const val EXTRA_SERVICE = "service"
        internal const val EXTRA_PROFILE_ID = "profileId"
        internal const val EXTRA_FOCUS_CLIENT_ID = "focusClientId"
        internal const val EXTRA_ACTIVATE_PROFILE_ID = "activateProfileId"
        private const val EXTRA_FACE_TITLE = "faceTitle"
        private const val EXTRA_FACE_DETAIL = "faceDetail"
        private const val EXTRA_FACE_AVATAR_SHAPE = "faceAvatarShape"
        private const val EXTRA_FACE_PHOTO_URL = "facePhotoUrl"

        internal fun readRequest(intent: Intent): ServiceSetupRequest = ServiceSetupRequest(
            service = SetupService.fromKey(intent.getStringExtra(EXTRA_SERVICE)),
            profileId = intent.getStringExtra(EXTRA_PROFILE_ID),
            focusClientId = intent.getBooleanExtra(EXTRA_FOCUS_CLIENT_ID, false),
            face = intent.getStringExtra(EXTRA_FACE_TITLE)?.let { title ->
                AccountFace(
                    title = title,
                    detail = intent.getStringExtra(EXTRA_FACE_DETAIL),
                    avatarShape = intent.getIntExtra(EXTRA_FACE_AVATAR_SHAPE, 0),
                    photoUrl = intent.getStringExtra(EXTRA_FACE_PHOTO_URL),
                )
            },
        )
    }
}
