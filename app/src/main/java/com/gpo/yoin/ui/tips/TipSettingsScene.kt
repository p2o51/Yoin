package com.gpo.yoin.ui.tips

import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gpo.yoin.BuildConfig
import com.gpo.yoin.R
import com.gpo.yoin.symbols.YoinSymbols
import com.gpo.yoin.ui.settings.SettingsGroup
import com.gpo.yoin.ui.settings.SettingsHeadline
import com.gpo.yoin.ui.settings.SettingsItem
import kotlinx.coroutines.delay

/**
 * Settings, regrouped (owner 2026-11-09, plan A): the page's groups as they now stand — features,
 * appearance, sync & storage, then tips, docs & feedback, Yoin and licenses — scrolled through once.
 * Built from the real rows and labels, so the demo follows any later wording change.
 */
@Composable
internal fun SettingsGroupsDemo(playing: Boolean) {
    val scroll = rememberScrollState()
    val travel = with(LocalDensity.current) { SettingsTravel.toPx() }
    DemoPhone(playing = playing, script = {
        scroll.scrollTo(0)
        delay(500)
        moveTo(fx = 0.5f, fy = 0.75f)
        drag(fx = 0.5f, fy = 0.3f, ms = 1_400) {}
        lift()
        scroll.animateScrollBy(travel, tween(1_400))
        delay(1_400)
        scroll.animateScrollTo(0, tween(900))
        delay(400)
    }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll, enabled = true)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            SettingsHeadline(title = stringResource(R.string.settings_title))
            SettingsGroup(title = stringResource(R.string.settings_section_features)) {
                item { SettingsItem(icon = YoinSymbols.Sparkle, title = stringResource(R.string.settings_gemini_title)) }
            }
            SettingsGroup(title = stringResource(R.string.settings_section_appearance)) {
                item { SettingsItem(icon = YoinSymbols.UnfoldLess, title = stringResource(R.string.settings_motion_scroll_edge)) }
                item { SettingsItem(icon = YoinSymbols.Smartphone, title = stringResource(R.string.settings_motion_screen_corners)) }
            }
            SettingsGroup(title = stringResource(R.string.settings_section_sync_storage)) {
                item { SettingsItem(icon = YoinSymbols.Reviews, title = stringResource(R.string.settings_neodb_title)) }
                item { SettingsItem(icon = YoinSymbols.Playlist, title = stringResource(R.string.settings_playlist_backups_title)) }
            }
            SettingsGroup(title = stringResource(R.string.settings_section_help_about)) {
                item { SettingsItem(icon = YoinSymbols.Sparkle, title = stringResource(R.string.settings_about_tips)) }
                item { SettingsItem(icon = YoinSymbols.Note, title = stringResource(R.string.settings_about_docs)) }
                item { SettingsItem(icon = YoinSymbols.Send, title = stringResource(R.string.settings_about_feedback)) }
                item {
                    SettingsItem(
                        icon = YoinSymbols.Info,
                        title = stringResource(R.string.settings_about_name),
                        summary = stringResource(R.string.settings_about_version, BuildConfig.VERSION_NAME),
                    )
                }
                item { SettingsItem(icon = YoinSymbols.Code, title = stringResource(R.string.settings_about_licenses)) }
            }
        }
    }
}

private val SettingsTravel = 520.dp
