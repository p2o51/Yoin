package com.gpo.yoin.ui.landing.guide

import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class SigningCertificatesTest {

    private val packageManager = mockk<PackageManager>()
    private val context = mockk<Context> {
        every { packageName } returns PACKAGE_NAME
        every { packageManager } returns this@SigningCertificatesTest.packageManager
    }

    @Test
    fun should_returnColonSeparatedUppercaseSha1_when_packageHasOneSigner() {
        modernPackage(history = arrayOf(certificate("a")), current = arrayOf(certificate("a")))

        assertEquals(listOf(SHA1_A), signingCertificateSha1s(context))
    }

    @Test
    fun should_includeOriginalHybridClassicalAndCurrentPqc_when_packageHasRotationHistory() {
        modernPackage(
            history = arrayOf(certificate("a"), certificate("b"), certificate("c")),
            current = arrayOf(certificate("c")),
        )

        assertEquals(listOf(SHA1_A, SHA1_B, SHA1_C), signingCertificateSha1s(context))
    }

    @Test
    fun should_deduplicateWithoutChangingOrder_when_historyRepeatsCertificateBytes() {
        modernPackage(
            history = arrayOf(certificate("a"), certificate("b"), certificate("a"), certificate("b")),
            current = arrayOf(certificate("b")),
        )

        assertEquals(listOf(SHA1_A, SHA1_B), signingCertificateSha1s(context))
    }

    @Test
    fun should_returnEveryCurrentSignerWithoutReadingHistory_when_packageHasMultipleSigners() {
        val signingInfo = modernPackage(
            history = arrayOf(certificate("c")),
            current = arrayOf(certificate("a"), certificate("b")),
            multipleSigners = true,
        )

        assertEquals(listOf(SHA1_A, SHA1_B), signingCertificateSha1s(context))
        verify(exactly = 0) { signingInfo.signingCertificateHistory }
    }

    @Test
    fun should_fallBackToCurrentSigners_when_historyIsNull() {
        modernPackage(history = null, current = arrayOf(certificate("a")))

        assertEquals(listOf(SHA1_A), signingCertificateSha1s(context))
    }

    @Test
    fun should_fallBackToCurrentSigners_when_historyIsEmpty() {
        modernPackage(history = emptyArray(), current = arrayOf(certificate("a")))

        assertEquals(listOf(SHA1_A), signingCertificateSha1s(context))
    }

    @Test
    fun should_returnNoFingerprints_when_signingInfoIsMissing() {
        every {
            packageManager.getPackageInfo(PACKAGE_NAME, PackageManager.GET_SIGNING_CERTIFICATES)
        } returns PackageInfo()

        assertEquals(emptyList<String>(), signingCertificateSha1s(context))
    }

    @Test
    fun should_returnNoFingerprints_when_historyAndCurrentSignersAreMissing() {
        modernPackage(history = null, current = null)

        assertEquals(emptyList<String>(), signingCertificateSha1s(context))
    }

    @Test
    fun should_returnNoFingerprints_when_historyAndCurrentSignersAreEmpty() {
        modernPackage(history = emptyArray(), current = emptyArray())

        assertEquals(emptyList<String>(), signingCertificateSha1s(context))
    }

    @Test
    fun should_returnNoFingerprints_when_packageManagerCannotFindThePackage() {
        every {
            packageManager.getPackageInfo(PACKAGE_NAME, any<Int>())
        } throws PackageManager.NameNotFoundException(PACKAGE_NAME)

        assertEquals(emptyList<String>(), signingCertificateSha1s(context))
    }

    @Test
    fun should_returnNoFingerprints_when_signingInformationCannotBeRead() {
        val signingInfo = modernPackage(history = null, current = arrayOf(certificate("a")))
        every { signingInfo.signingCertificateHistory } throws SecurityException("unavailable")

        assertEquals(emptyList<String>(), signingCertificateSha1s(context))
    }

    private fun modernPackage(
        history: Array<Signature>?,
        current: Array<Signature>?,
        multipleSigners: Boolean = false,
    ): SigningInfo {
        val signingInfo = mockk<SigningInfo> {
            every { hasMultipleSigners() } returns multipleSigners
            every { signingCertificateHistory } returns history
            every { apkContentsSigners } returns current
        }
        every {
            packageManager.getPackageInfo(PACKAGE_NAME, PackageManager.GET_SIGNING_CERTIFICATES)
        } returns PackageInfo().apply { this.signingInfo = signingInfo }
        return signingInfo
    }
}

// Keep SigningInfo method signatures out of the pre-28 test class: that framework type does not exist yet.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 27], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class SigningCertificatesLegacyTest {

    private val packageManager = mockk<PackageManager>()
    private val context = mockk<Context> {
        every { packageName } returns PACKAGE_NAME
        every { packageManager } returns this@SigningCertificatesLegacyTest.packageManager
    }

    @Suppress("DEPRECATION")
    @Test
    fun should_returnAllDistinctLegacySigners_when_runningBeforeApi28() {
        every {
            packageManager.getPackageInfo(PACKAGE_NAME, PackageManager.GET_SIGNATURES)
        } returns PackageInfo().apply {
            signatures = arrayOf(certificate("a"), certificate("b"), certificate("a"))
        }

        assertEquals(listOf(SHA1_A, SHA1_B), signingCertificateSha1s(context))
        verify(exactly = 0) {
            packageManager.getPackageInfo(PACKAGE_NAME, PackageManager.GET_SIGNING_CERTIFICATES)
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun should_returnNoFingerprints_when_legacySignaturesAreMissing() {
        every {
            packageManager.getPackageInfo(PACKAGE_NAME, PackageManager.GET_SIGNATURES)
        } returns PackageInfo()

        assertEquals(emptyList<String>(), signingCertificateSha1s(context))
    }

    @Test
    fun should_returnNoFingerprints_when_packageManagerCannotFindTheLegacyPackage() {
        every {
            packageManager.getPackageInfo(PACKAGE_NAME, any<Int>())
        } throws PackageManager.NameNotFoundException(PACKAGE_NAME)

        assertEquals(emptyList<String>(), signingCertificateSha1s(context))
    }
}

private fun certificate(value: String): Signature = Signature(value.toByteArray(Charsets.US_ASCII))

private const val PACKAGE_NAME = "com.gpo.yoin"
private const val SHA1_A = "86:F7:E4:37:FA:A5:A7:FC:E1:5D:1D:DC:B9:EA:EA:EA:37:76:67:B8"
private const val SHA1_B = "E9:D7:1F:5E:E7:C9:2D:6D:C9:E9:2F:FD:AD:17:B8:BD:49:41:8F:98"
private const val SHA1_C = "84:A5:16:84:1B:A7:7A:5B:46:48:DE:2C:D0:DF:CB:30:EA:46:DB:B4"
