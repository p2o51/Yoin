package com.gpo.yoin.data.sync.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlin.coroutines.cancellation.CancellationException

/** What the Sign in with Google sheet answered. */
sealed interface GoogleAccountPick {
    data class Picked(val email: String) : GoogleAccountPick

    /** The user closed the sheet. */
    data object Canceled : GoogleAccountPick

    /** The sheet couldn't run (no web client, old Play services, no account): use the legacy chooser. */
    data object Unavailable : GoogleAccountPick
}

/**
 * Picks the Google account for Cloud sync with Credential Manager's Sign in with Google bottom sheet, so
 * the Drive authorization that follows is pinned to it and skips AuthorizationClient's old-style
 * account chooser. Only the account's email is used; the ID token is not kept or sent anywhere.
 */
class GoogleAccountPicker(private val serverClientId: String) {
    val enabled: Boolean get() = serverClientId.isNotBlank()

    /** [activityContext] must be an Activity: Credential Manager anchors its sheet to it. */
    suspend fun pick(activityContext: Context): GoogleAccountPick {
        if (!enabled) return GoogleAccountPick.Unavailable
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(
                GetGoogleIdOption.Builder()
                    .setServerClientId(serverClientId)
                    .setFilterByAuthorizedAccounts(false)
                    .setAutoSelectEnabled(false)
                    .build(),
            )
            .build()
        return try {
            val credential = CredentialManager.create(activityContext).getCredential(activityContext, request).credential
            if (credential !is CustomCredential ||
                credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                return GoogleAccountPick.Unavailable
            }
            val email = GoogleIdTokenCredential.createFrom(credential.data).id.trim()
            if (email.isEmpty()) GoogleAccountPick.Unavailable else GoogleAccountPick.Picked(email)
        } catch (error: CancellationException) {
            throw error
        } catch (_: GetCredentialCancellationException) {
            GoogleAccountPick.Canceled
        } catch (_: GetCredentialException) {
            GoogleAccountPick.Unavailable
        } catch (_: GoogleIdTokenParsingException) {
            GoogleAccountPick.Unavailable
        } catch (_: RuntimeException) {
            GoogleAccountPick.Unavailable
        }
    }
}
