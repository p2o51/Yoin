package com.gpo.yoin.ui.component

import androidx.compose.foundation.layout.size
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicatorDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Official Material 3 Expressive loading affordance used across Yoin.
 *
 * We intentionally wrap `ContainedLoadingIndicator` instead of hand-rolling a custom spinner so
 * loading states stay aligned with the MD3 Expressive component model.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun YoinLoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
) {
    ContainedLoadingIndicator(
        modifier = modifier.size(size),
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        indicatorColor = MaterialTheme.colorScheme.onSecondaryContainer,
        containerShape = LoadingIndicatorDefaults.containerShape,
    )
}
