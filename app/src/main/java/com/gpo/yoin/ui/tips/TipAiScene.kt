package com.gpo.yoin.ui.tips

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.data.remote.ai.AiProvider
import com.gpo.yoin.data.remote.ai.AiProviderPrefs
import com.gpo.yoin.data.remote.ai.AiTier
import com.gpo.yoin.ui.settings.SettingsHeadline
import com.gpo.yoin.ui.settings.SettingsPageBackground
import com.gpo.yoin.ui.settings.ai.AiSettingsPageBody
import com.gpo.yoin.ui.settings.ai.AiSettingsState
import com.gpo.yoin.ui.settings.ai.AiSettingsStateActions
import kotlinx.coroutines.delay

/**
 * AI features (owner 2026-11-12, AI settings v2): Settings › AI features in the little phone, the real page. The
 * dot picks DeepSeek in the service row, then the Best tier, then goes down to Try a song. The demo keeps its
 * choices to itself: it never changes the real ones, opens no page and no link, and its fields take no typing.
 */
@Composable
internal fun AiServicesDemo(playing: Boolean) {
    val state = remember { mutableStateOf(AiSettingsState(AiProviderPrefs())) }
    val actions = remember { AiSettingsStateActions(state) }
    val scroll = rememberScrollState()
    DemoPhone(playing = playing, script = {
        state.value = AiSettingsState(AiProviderPrefs())
        scroll.scrollTo(0)
        delay(700)
        moveTo("ai-service-${AiProvider.DeepSeek.name}")
        delay(250)
        tap { actions.setProvider(AiProvider.DeepSeek) }
        delay(900)
        moveTo("ai-tier-${AiTier.Best.name}")
        delay(250)
        tap { actions.setTier(AiTier.Best) }
        lift()
        delay(900)
        scroll.animateScrollTo(scroll.maxValue, tween(900))
        moveTo("ai-try")
        delay(250)
        tap {}
        lift()
        delay(1_200)
        scroll.animateScrollTo(0, tween(900))
        delay(300)
    }) {
        SettingsPageBackground(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    // The fields are there to be seen: no focus, so no keyboard comes up over the tip.
                    .focusProperties { canFocus = false }
                    .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            ) {
                SettingsHeadline(title = stringResource(R.string.settings_gemini_title_page))
                AiSettingsPageBody(
                    state = state.value,
                    actions = actions,
                    onOpenTiers = {},
                    onOpenTry = {},
                    onOpenLink = {},
                    providerModifier = { provider -> Modifier.demoTarget("ai-service-${provider.name}") },
                    tierModifier = { tier -> Modifier.demoTarget("ai-tier-${tier.name}") },
                    tryModifier = Modifier.demoTarget("ai-try"),
                )
            }
        }
    }
}
