@file:Suppress("ktlint:standard:function-naming")

package com.gpo.yoin.ui.settings.applemusic

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.yoin.R
import com.gpo.yoin.data.source.applemusic.AppleMusicNameRegion
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.component.handPointer
import com.gpo.yoin.ui.experience.rememberYoinHaptics
import com.gpo.yoin.ui.settings.LocalSettingsRowShape
import com.gpo.yoin.ui.settings.SettingsGroup
import com.gpo.yoin.ui.theme.YoinTheme
import java.util.Locale

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppleMusicNameRegionContent(
    selected: AppleMusicNameRegion,
    onSelect: (AppleMusicNameRegion) -> Unit,
    modifier: Modifier = Modifier,
    rowModifier: (String) -> Modifier = { Modifier },
) {
    val configuration = LocalConfiguration.current
    val locale = remember(configuration) {
        configuration.locales.get(0) ?: Locale.getDefault()
    }
    val chosenStorefront = selected as? AppleMusicNameRegion.Storefront
    var showRegionPicker by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsGroup(title = stringResource(R.string.settings_apple_names_title)) {
            item(key = AppleMusicNameRegion.Off.key) {
                RegionRow(
                    title = stringResource(R.string.settings_apple_names_off),
                    summary = stringResource(R.string.settings_apple_names_off_summary),
                    selected = selected == AppleMusicNameRegion.Off,
                    testTag = "apple_music_names_${AppleMusicNameRegion.Off.key}",
                    modifier = rowModifier(AppleMusicNameRegion.Off.key),
                    onClick = { onSelect(AppleMusicNameRegion.Off) },
                )
            }
            item(key = AppleMusicNameRegion.Original.key) {
                RegionRow(
                    title = stringResource(R.string.settings_apple_names_original),
                    summary = stringResource(R.string.settings_apple_names_original_summary),
                    selected = selected == AppleMusicNameRegion.Original,
                    testTag = "apple_music_names_${AppleMusicNameRegion.Original.key}",
                    modifier = rowModifier(AppleMusicNameRegion.Original.key),
                    onClick = { onSelect(AppleMusicNameRegion.Original) },
                )
            }
            item(key = "region") {
                val regionSummary = if (chosenStorefront != null) {
                    stringResource(
                        R.string.settings_apple_names_region_selected,
                        chosenStorefront.displayName(locale),
                    )
                } else {
                    stringResource(R.string.settings_apple_names_region_summary)
                }
                RegionRow(
                    title = stringResource(R.string.settings_apple_names_region),
                    summary = regionSummary,
                    selected = chosenStorefront != null,
                    testTag = "apple_music_names_region",
                    modifier = rowModifier("region"),
                    alwaysClickable = true,
                    trailing = {
                        Icon(
                            imageVector = YoinSymbols.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    onClick = { showRegionPicker = true },
                )
            }
        }
        Text(
            text = stringResource(R.string.settings_apple_names_foot),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }

    if (showRegionPicker) {
        StorefrontPickerSheet(
            selected = chosenStorefront,
            locale = locale,
            onPick = { storefront ->
                showRegionPicker = false
                onSelect(storefront)
            },
            onDismiss = { showRegionPicker = false },
        )
    }
}

@Composable
private fun RegionRow(
    title: String,
    summary: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    alwaysClickable: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val haptics = rememberYoinHaptics()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(LocalSettingsRowShape.current)
            .handPointer()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = {
                    if (alwaysClickable || !selected) {
                        haptics.performClick()
                        onClick()
                    }
                },
            )
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag(testTag),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.handPointer())
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        trailing?.invoke()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StorefrontPickerSheet(
    selected: AppleMusicNameRegion.Storefront?,
    locale: Locale,
    onPick: (AppleMusicNameRegion.Storefront) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = rememberYoinHaptics()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.settings_apple_names_picker_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(
                    items = AppleMusicNameRegion.CuratedStorefronts,
                    key = { it.storefront },
                ) { option ->
                    val isSelected = option == selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .handPointer()
                            .clickable {
                                haptics.performClick()
                                onPick(option)
                            }
                            .heightIn(min = 56.dp)
                            .padding(horizontal = 24.dp, vertical = 12.dp)
                            .testTag("apple_music_storefront_${option.storefront}"),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = option.displayName(locale),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.weight(1f),
                        )
                        if (isSelected) {
                            Icon(
                                imageVector = YoinSymbols.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun AppleMusicNameRegionPreview() {
    YoinTheme { AppleMusicNameRegionContent(selected = AppleMusicNameRegion.Japan, onSelect = {}) }
}
