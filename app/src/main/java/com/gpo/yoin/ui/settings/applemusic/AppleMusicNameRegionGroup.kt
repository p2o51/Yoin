@file:Suppress("ktlint:standard:function-naming")

package com.gpo.yoin.ui.settings.applemusic

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.yoin.R
import com.gpo.yoin.data.source.applemusic.AppleMusicNameRegion
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.settings.LocalSettingsRowShape
import com.gpo.yoin.ui.settings.SettingsGroup
import com.gpo.yoin.ui.theme.YoinTheme

/**
 * An Apple Music account's name region: which storefront its songs and albums are named from
 * ([AppleMusicNameRegion]). Shown on the account's own page, under its connection.
 */
@Composable
fun AppleMusicNameRegionGroup(
    profileId: String?,
    viewModel: AppleMusicValidationViewModel = viewModel(),
) {
    LaunchedEffect(viewModel, profileId) { viewModel.initialize(profileId) }
    val selected by viewModel.nameRegion.collectAsState()
    AppleMusicNameRegionContent(selected = selected, onSelect = viewModel::setNameRegion)
}

@Composable
fun AppleMusicNameRegionContent(
    selected: AppleMusicNameRegion,
    onSelect: (AppleMusicNameRegion) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsGroup(title = stringResource(R.string.settings_apple_names_title)) {
            AppleMusicNameRegion.entries.forEach { region ->
                item(key = region.key) {
                    RegionRow(region = region, selected = region == selected, onSelect = { onSelect(region) })
                }
            }
        }
        Text(
            text = stringResource(R.string.settings_apple_names_foot),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun RegionRow(region: AppleMusicNameRegion, selected: Boolean, onSelect: () -> Unit) {
    val haptics = rememberYoinHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(LocalSettingsRowShape.current)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = {
                    if (!selected) {
                        haptics.performClick()
                        onSelect()
                    }
                },
            )
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag("apple_music_names_${region.key}"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(region.titleRes),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(region.summaryRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@get:StringRes
private val AppleMusicNameRegion.titleRes: Int
    get() = when (this) {
        AppleMusicNameRegion.Off -> R.string.settings_apple_names_off
        AppleMusicNameRegion.Original -> R.string.settings_apple_names_original
        AppleMusicNameRegion.MainlandChina -> R.string.settings_apple_names_cn
        AppleMusicNameRegion.Japan -> R.string.settings_apple_names_jp
    }

@get:StringRes
private val AppleMusicNameRegion.summaryRes: Int
    get() = when (this) {
        AppleMusicNameRegion.Off -> R.string.settings_apple_names_off_summary
        AppleMusicNameRegion.Original -> R.string.settings_apple_names_original_summary
        AppleMusicNameRegion.MainlandChina -> R.string.settings_apple_names_cn_summary
        AppleMusicNameRegion.Japan -> R.string.settings_apple_names_jp_summary
    }

@Preview
@Composable
private fun AppleMusicNameRegionPreview() {
    YoinTheme { AppleMusicNameRegionContent(selected = AppleMusicNameRegion.Original, onSelect = {}) }
}
