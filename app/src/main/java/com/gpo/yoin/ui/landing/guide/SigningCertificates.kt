package com.gpo.yoin.ui.landing.guide

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

@Suppress("DEPRECATION")
internal fun signingCertificateSha1s(context: Context): List<String> = runCatching {
    val packageManager = context.packageManager
    val certificates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val signingInfo = packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            .signingInfo ?: return@runCatching emptyList()
        if (signingInfo.hasMultipleSigners()) {
            signingInfo.apkContentsSigners
        } else {
            // Hybrid signing exposes PQC as current; its classical keys remain in the history.
            signingInfo.signingCertificateHistory?.takeIf { it.isNotEmpty() }
                ?: signingInfo.apkContentsSigners
        }
    } else {
        packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
    }
    certificates.orEmpty().map { certificate ->
        formatFingerprint(MessageDigest.getInstance("SHA-1").digest(certificate.toByteArray()))
    }.distinct()
}.getOrDefault(emptyList())
