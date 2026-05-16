package com.gpo.yoin.player

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.util.Log
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp
import com.spotify.android.appremote.internal.SdkRemoteClientConnectorFactory
import com.spotify.android.appremote.internal.SpotifyLocator
import java.security.MessageDigest
import java.util.Locale

/**
 * Spotify App Remote 0.8.0 still discovers Spotify through GET_SIGNATURES.
 * On newer Android/Spotify signing setups that can fail even when the official
 * app is installed. Resolve the package with modern signing APIs, then hand the
 * verified package name back to the SDK's own connector.
 */
internal object SpotifyAppRemoteCompat {
    private const val TAG = "SpotifyAppRemoteCompat"
    private const val LOCAL_CONNECTOR_CLASS = "com.spotify.android.appremote.api.LocalConnector"

    private val knownFingerprintsByPackage = linkedMapOf(
        "com.spotify.music" to setOf(
            "644D12F22FE5AD82A2BF0BEC66E99E699EB0A41608919507C784F648009B9290",
            "6505B181933344F93893D586E399B94616183F04349CB572A9E81A3335E28FFD",
            "3F6A8113086F25779B7316CAEC8B09C89A7EFA56446EC1B8877D3C1CC98FF518",
        ),
        "com.spotify.music.partners" to setOf(
            "7297CBC538FAA3AF7880B3D8B3D451B2E8F5A6BCA1E1F2888F5F80C94DA6DB7C",
        ),
        "com.spotify.music.canary" to setOf(
            "F6D3ED655F76734281ECE3398D18F45EED5F209678EBC2D5A586BBDD3B89148F",
            "7669D9186D3EA41E345925E89EAFCE87E0DBAF7174D2FD331DC29F62F3B0311B",
        ),
    )

    fun connect(
        context: Context,
        params: ConnectionParams,
        listener: Connector.ConnectionListener,
    ) {
        val packageName = findVerifiedPackageName(context)
        if (packageName == null) {
            listener.onFailure(CouldNotFindSpotifyApp())
            return
        }

        Log.d(TAG, "connect: using verified Spotify package $packageName")
        runCatching {
            val connectorClass = Class.forName(LOCAL_CONNECTOR_CLASS)
            val constructor = connectorClass.getDeclaredConstructor(
                SpotifyLocator::class.java,
                SdkRemoteClientConnectorFactory::class.java,
            )
            constructor.isAccessible = true
            val connector = constructor.newInstance(
                FixedSpotifyLocator(packageName),
                SdkRemoteClientConnectorFactory(),
            ) as Connector
            connector.connect(context, params, listener)
        }.onFailure { error ->
            Log.w(TAG, "connect: compat connector failed; falling back to SDK locator", error)
            SpotifyAppRemote.connect(context, params, listener)
        }
    }

    private fun findVerifiedPackageName(context: Context): String? {
        val packageManager = context.packageManager
        return knownFingerprintsByPackage.firstNotNullOfOrNull { (packageName, knownFingerprints) ->
            if (!packageManager.isLaunchable(packageName)) {
                return@firstNotNullOfOrNull null
            }

            val installedFingerprints = packageManager.signingFingerprints(packageName)
            when {
                installedFingerprints.any { it in knownFingerprints } -> packageName
                installedFingerprints.isEmpty() -> {
                    Log.w(TAG, "findVerifiedPackageName: no signing certificate for $packageName")
                    null
                }
                else -> {
                    Log.w(TAG, "findVerifiedPackageName: unrecognized Spotify signature for $packageName")
                    null
                }
            }
        }
    }

    private fun PackageManager.isLaunchable(packageName: String): Boolean =
        getLaunchIntentForPackage(packageName) != null

    @Suppress("DEPRECATION")
    private fun PackageManager.signingFingerprints(packageName: String): Set<String> =
        runCatching {
            val packageInfo = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
                )
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> getPackageInfo(
                    packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES,
                )
                else -> getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            }
            val signatures = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
                    val signingInfo = packageInfo.signingInfo ?: return@runCatching emptySet()
                    val currentSigners = signingInfo.apkContentsSigners?.toList().orEmpty()
                    val historicalSigners = if (signingInfo.hasMultipleSigners()) {
                        emptyList()
                    } else {
                        signingInfo.signingCertificateHistory?.toList().orEmpty()
                    }
                    currentSigners + historicalSigners
                }
                else -> packageInfo.signatures?.toList().orEmpty()
            }

            signatures
                .map { it.sha256Fingerprint() }
                .toSet()
        }.getOrElse { error ->
            Log.w(TAG, "signingFingerprints: failed to inspect $packageName", error)
            emptySet()
        }

    private fun Signature.sha256Fingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
        return digest.joinToString(separator = "") { byte ->
            "%02X".format(Locale.US, byte)
        }
    }

    private class FixedSpotifyLocator(
        private val packageName: String,
    ) : SpotifyLocator() {
        override fun isSpotifyInstalled(context: Context): Boolean = true

        override fun getSpotifyBestPackageName(context: Context): String = packageName
    }
}
