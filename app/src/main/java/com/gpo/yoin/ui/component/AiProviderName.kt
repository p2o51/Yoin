package com.gpo.yoin.ui.component

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.res.stringResource
import com.gpo.yoin.R
import com.gpo.yoin.data.remote.ai.AiProvider
import com.gpo.yoin.ui.common.UiText
import com.gpo.yoin.ui.common.tightenCjkSpaces

/*
 * Whose words the generated text is, and who a question goes to: the AI service chosen in Settings › AI
 * features (51, 2026-11-25: "Ask Gemini" while DeepSeek answers is wrong). Gemini and DeepSeek by name,
 * Qwen as 千问 in Chinese, a Custom endpoint as plain "AI". On-device Gemini Nano copy is not this: it is
 * always Gemini.
 */

/** The AI service in use, provided per window by YoinActivityRoot (Gemini in previews and tests). */
val LocalAiProvider = compositionLocalOf { AiProvider.Gemini }

@StringRes
fun aiProviderNameRes(provider: AiProvider): Int = when (provider) {
    AiProvider.Gemini -> R.string.ai_provider_gemini
    AiProvider.DeepSeek -> R.string.ai_provider_deepseek
    AiProvider.Qwen -> R.string.ai_provider_qwen
    AiProvider.Custom -> R.string.ai_provider_generic_name
}

/** The name as copy reads it ("Ask %1$s", "%1$s is writing…"). */
@Composable
@ReadOnlyComposable
fun aiProviderName(provider: AiProvider = LocalAiProvider.current): String = stringResource(aiProviderNameRes(provider))

/**
 * Copy with the active service's name filled in ([id] takes it as `%1$s`), the space before or after a
 * Chinese name dropped (问千问, 千问正在写…; 问 Gemini keeps its space).
 */
@Composable
@ReadOnlyComposable
fun aiString(@StringRes id: Int, provider: AiProvider = LocalAiProvider.current): String =
    tightenCjkSpaces(stringResource(id, aiProviderName(provider)))

/** The same name for a ViewModel's [UiText] arguments (spaces tightened the same way when shown). */
fun aiProviderNameText(provider: AiProvider): UiText = UiText.Res(aiProviderNameRes(provider))
