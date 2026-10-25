package com.gpo.yoin.ui.settings

import androidx.annotation.RawRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.theme.YoinTheme

/** A license the app ships the full text of, in res/raw. */
internal enum class OssLicense(val displayName: String, @RawRes val text: Int) {
    Apache2("Apache License 2.0", R.raw.license_apache_2_0),

    /** The text carries the font's copyright line, as the OFL asks. */
    Ofl11("SIL Open Font License 1.1", R.raw.license_ofl_1_1),
}

/**
 * One acknowledged component. Keep in step with NOTICE and the
 * dependencies in app/build.gradle.kts; Google Play services, Sign in with
 * Google and MusicKit are under their vendors' terms, not open source, and
 * are not listed.
 */
internal data class OssComponent(
    val name: String,
    val copyright: String,
    val license: OssLicense,
)

internal val OpenSourceComponents = listOf(
    OssComponent("Google Sans Flex", "Copyright 2022 The Google Sans Flex Project Authors", OssLicense.Ofl11),
    OssComponent("Material Symbols", "Copyright Google LLC", OssLicense.Apache2),
    OssComponent("Material Color Utilities", "Copyright 2022 Google LLC", OssLicense.Apache2),
    OssComponent(
        "Jetpack Compose, Material 3 and AndroidX",
        "Copyright The Android Open Source Project",
        OssLicense.Apache2,
    ),
    OssComponent("AndroidX Media3", "Copyright The Android Open Source Project", OssLicense.Apache2),
    OssComponent(
        "Kotlin, kotlinx.coroutines and kotlinx.serialization",
        "Copyright JetBrains s.r.o. and Kotlin Programming Language contributors",
        OssLicense.Apache2,
    ),
    OssComponent("OkHttp and Okio", "Copyright Square, Inc.", OssLicense.Apache2),
    OssComponent("Retrofit", "Copyright Square, Inc.", OssLicense.Apache2),
    OssComponent("Gson", "Copyright Google Inc.", OssLicense.Apache2),
    OssComponent("Coil", "Copyright Coil Contributors", OssLicense.Apache2),
    OssComponent("Spotify Android App Remote SDK", "Copyright Spotify AB", OssLicense.Apache2),
)

/**
 * Settings › About › Open source licenses as a page: the fonts and libraries
 * Yoin ships, each with its copyright and license; a tap shows the license
 * in full.
 */
@Composable
internal fun OpenSourceLicensesPageContent(modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.settings_licenses_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 16.dp),
        )
        SettingsSegments {
            OpenSourceComponents.forEach { component ->
                item(key = component.name) {
                    SettingsItem(
                        icon = YoinSymbols.Code,
                        title = component.name,
                        summary = component.copyright,
                        summaryDetail = component.license.displayName,
                        onClick = { open = component.name },
                    )
                }
            }
        }
    }
    OpenSourceComponents.firstOrNull { it.name == open }?.let { component ->
        LicenseTextSheet(component = component, onDismiss = { open = null })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LicenseTextSheet(component: OssComponent, onDismiss: () -> Unit) {
    val resources = LocalContext.current.resources
    val text = remember(component.license) {
        resources.openRawResource(component.license.text).bufferedReader().use { it.readText() }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            Text(
                text = component.name,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = component.copyright,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OpenSourceLicensesPageContentPreview() {
    YoinTheme {
        OpenSourceLicensesPageContent()
    }
}
