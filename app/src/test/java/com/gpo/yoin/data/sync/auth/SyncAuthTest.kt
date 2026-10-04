package com.gpo.yoin.data.sync.auth

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.gpo.yoin.data.sync.GoogleAuthAvailability
import com.gpo.yoin.data.sync.GoogleAuthResult
import com.gpo.yoin.data.sync.GoogleSyncAuthorizer
import com.gpo.yoin.data.sync.SyncTransportException
import com.google.android.gms.common.api.CommonStatusCodes
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncAuthTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val debugFile = File(context.filesDir, DebugSyncOverrides.FILE_NAME)

    @After
    fun cleanup() {
        debugFile.delete()
    }

    @Test
    fun should_returnToken_when_silentAuthorizationSucceeds() = runTest {
        val authorizer = FakeAuthorizer(GoogleAuthResult.Token("access", listOf(GoogleSyncAuthorizer.SCOPE_DRIVE_APPDATA)))
        val provider = AuthorizerTokenProvider(authorizer) { "me@example.com" }

        assertEquals("access", provider.token())
        assertEquals(listOf("me@example.com"), authorizer.silentEmails)
    }

    @Test
    fun should_throwMisconfigured_when_oauthClientIsMissing() = runTest {
        val provider = AuthorizerTokenProvider(FakeAuthorizer(GoogleAuthResult.Misconfigured)) { null }

        assertThrows<SyncTransportException.Misconfigured> { provider.token() }
    }

    @Test
    fun should_throwNetwork_when_playServicesIsOffline() = runTest {
        val offline = GoogleAuthResult.Failure(CommonStatusCodes.NETWORK_ERROR, "offline")
        val provider = AuthorizerTokenProvider(FakeAuthorizer(offline)) { null }

        assertThrows<SyncTransportException.Network> { provider.token() }
    }

    @Test
    fun should_throwUnauthorized_when_consentIsMissing() = runTest {
        val missingScope = GoogleAuthResult.Failure(null, "Google Drive app data access wasn't granted")
        val provider = AuthorizerTokenProvider(FakeAuthorizer(missingScope)) { null }

        assertThrows<SyncTransportException.Unauthorized> { provider.token() }
    }

    @Test
    fun should_clearToken_when_tokenIsInvalidated() = runTest {
        val authorizer = FakeAuthorizer(GoogleAuthResult.Canceled)

        AuthorizerTokenProvider(authorizer) { null }.invalidate("stale")

        assertEquals(listOf("stale"), authorizer.cleared)
    }

    @Test
    fun should_throwNetwork_when_playServicesFailsTransiently() = runTest {
        for (code in listOf(CommonStatusCodes.INTERNAL_ERROR, CommonStatusCodes.INTERRUPTED, CommonStatusCodes.TIMEOUT)) {
            val provider = AuthorizerTokenProvider(FakeAuthorizer(GoogleAuthResult.Failure(code, "busy"))) { null }

            assertThrows<SyncTransportException.Network> { provider.token() }
        }
    }

    @Test
    fun should_clearLastIssuedToken_when_invalidatingCachedToken() = runTest {
        val authorizer = FakeAuthorizer(GoogleAuthResult.Token("access", listOf(GoogleSyncAuthorizer.SCOPE_DRIVE_APPDATA)))
        val provider = AuthorizerTokenProvider(authorizer) { null }

        provider.invalidateCached()
        assertTrue(authorizer.cleared.isEmpty())

        provider.token()
        provider.invalidateCached()
        provider.invalidateCached()
        assertEquals(listOf("access"), authorizer.cleared)
    }

    @Test
    fun should_notClearAgain_when_cachedTokenWasAlreadyInvalidated() = runTest {
        val authorizer = FakeAuthorizer(GoogleAuthResult.Token("access", listOf(GoogleSyncAuthorizer.SCOPE_DRIVE_APPDATA)))
        val provider = AuthorizerTokenProvider(authorizer) { null }

        provider.token()
        provider.invalidate("access")
        provider.invalidateCached()

        assertEquals(listOf("access"), authorizer.cleared)
    }

    @Test
    fun should_notRetryUnpinned_when_pinnedFailureIsTransientOrUnknown() {
        val transient = listOf(
            GoogleAuthResult.Failure(CommonStatusCodes.NETWORK_ERROR, "offline"),
            GoogleAuthResult.Failure(CommonStatusCodes.TIMEOUT, "slow"),
            GoogleAuthResult.Failure(CommonStatusCodes.INTERNAL_ERROR, "busy"),
            GoogleAuthResult.Failure(null, "unexpected"),
            GoogleAuthResult.Misconfigured,
            GoogleAuthResult.Canceled,
            GoogleAuthResult.Token("access", listOf(GoogleSyncAuthorizer.SCOPE_DRIVE_APPDATA)),
        )

        transient.forEach { pinned -> assertFalse("$pinned", SilentAuthPolicy.shouldRetryUnpinned(pinned)) }
    }

    @Test
    fun should_retryUnpinned_when_pinnedAccountIsUnusable() {
        val unusable = listOf(
            GoogleAuthResult.NeedsResolution(intentSender()),
            GoogleAuthResult.Failure(CommonStatusCodes.INVALID_ACCOUNT, "not on device"),
            GoogleAuthResult.Failure(CommonStatusCodes.SIGN_IN_REQUIRED, "signed out"),
        )

        unusable.forEach { pinned -> assertTrue("$pinned", SilentAuthPolicy.shouldRetryUnpinned(pinned)) }
    }

    @Test
    fun should_keepPinnedResolution_when_fallbackAlsoFails() {
        val pinned = GoogleAuthResult.NeedsResolution(intentSender())
        val token = GoogleAuthResult.Token("default-account", listOf(GoogleSyncAuthorizer.SCOPE_DRIVE_APPDATA))
        val failure = GoogleAuthResult.Failure(CommonStatusCodes.NETWORK_ERROR, "offline")

        assertSame(pinned, SilentAuthPolicy.choose(pinned, failure))
        assertSame(token, SilentAuthPolicy.choose(pinned, token))
        val invalid = GoogleAuthResult.Failure(CommonStatusCodes.INVALID_ACCOUNT, "not on device")
        assertSame(failure, SilentAuthPolicy.choose(invalid, failure))
    }

    @Test
    fun should_loadConfig_when_debugFileExists() {
        debugFile.writeText("""{"baseUrl":"http://127.0.0.1:8765","email":"qa@example.com","permissionId":"qa"}""")

        val config = DebugSyncOverrides.load(context)!!

        assertEquals("http://127.0.0.1:8765/drive/v3/", config.apiBase)
        assertEquals("http://127.0.0.1:8765/upload/drive/v3/", config.uploadBase)
        assertEquals("qa@example.com", config.email)
        assertEquals("qa", config.permissionId)
    }

    @Test
    fun should_returnNull_when_debugFileIsMissingOrInvalid() {
        assertNull(DebugSyncOverrides.load(context))
        debugFile.writeText("""{"baseUrl":"not a url","email":"qa@example.com","permissionId":"qa"}""")
        assertNull(DebugSyncOverrides.load(context))
        debugFile.writeText("{")
        assertNull(DebugSyncOverrides.load(context))
    }

    @Test
    fun should_alwaysGrantDriveScope_when_usingDebugAuthorizer() = runTest {
        val config = DebugSyncOverrides.parse("""{"baseUrl":"http://10.0.2.2:8765/","email":"qa@example.com","permissionId":"qa"}""")!!
        val authorizer = DebugSyncAuthorizer(config)

        assertEquals(GoogleAuthAvailability.Available, authorizer.availability())
        val token = authorizer.authorizeSilently(null) as GoogleAuthResult.Token
        assertEquals(DebugSyncAuthorizer.DEBUG_TOKEN, token.accessToken)
        assertTrue(GoogleSyncAuthorizer.SCOPE_DRIVE_APPDATA in token.grantedScopes)
        assertTrue(authorizer.authorizeInteractively() is GoogleAuthResult.Token)
    }

    private fun intentSender() =
        PendingIntent.getActivity(context, 0, Intent(), PendingIntent.FLAG_IMMUTABLE).intentSender

    private class FakeAuthorizer(private val silent: GoogleAuthResult) : GoogleSyncAuthorizer {
        val silentEmails = mutableListOf<String?>()
        val cleared = mutableListOf<String>()

        override fun availability(): GoogleAuthAvailability = GoogleAuthAvailability.Available

        override suspend fun authorizeSilently(accountEmail: String?): GoogleAuthResult {
            silentEmails += accountEmail
            return silent
        }

        override suspend fun authorizeInteractively(): GoogleAuthResult = silent

        override fun resultFromIntent(data: Intent?): GoogleAuthResult = silent

        override suspend fun clearToken(accessToken: String) {
            cleared += accessToken
        }

        override suspend fun revoke(accountEmail: String?) = Unit

        override fun signingCertSha1(): String? = null
    }

    private suspend inline fun <reified T : Throwable> assertThrows(block: suspend () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return error
            fail("Expected ${T::class.simpleName} but got $error")
        }
        fail("Expected ${T::class.simpleName}")
        throw AssertionError()
    }
}
