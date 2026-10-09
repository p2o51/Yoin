@file:Suppress("ktlint:standard:function-naming")

package com.gpo.yoin.ui.settings.applemusic

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gpo.yoin.R
import com.gpo.yoin.data.remote.applemusic.YoinTokenService
import com.gpo.yoin.ui.common.asString
import com.gpo.yoin.ui.component.ExpressiveTextField
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinTheme

/** Uses the setup page's existing native back surface. */
@Composable
fun AppleMusicValidationSection(
    profileId: String? = null,
    onSaved: (String) -> Unit = {},
    viewModel: AppleMusicValidationViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.authorizationResult(it.data)
    }
    LaunchedEffect(viewModel, profileId) { viewModel.initialize(profileId) }
    LaunchedEffect(viewModel) { viewModel.authorization.collect { launcher.launch(it) } }
    LaunchedEffect(viewModel, onSaved) { viewModel.saved.collect { onSaved(it) } }
    AppleMusicValidationContent(state, viewModel::connect)
}

@Composable
fun AppleMusicValidationContent(state: AppleMusicValidationUiState, onConnect: (String) -> Unit) {
    // Yoin's own token service unless the account already uses another.
    var endpoint by rememberSaveable(state.endpoint) { mutableStateOf(state.endpoint.ifBlank { YoinTokenService.URL }) }
    Column(
        Modifier.fillMaxWidth().testTag("apple_music_validation").padding(18.dp)
            .animateContentSize(YoinMotion.spatialSpring()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ExpressiveTextField(
            endpoint,
            { endpoint = it },
            stringResource(R.string.settings_apple_endpoint_label),
            stringResource(R.string.settings_apple_endpoint_placeholder),
            modifier = Modifier.fillMaxWidth().testTag("apple_music_token_endpoint")
        )
        Text(
            if (state.busy) stringResource(R.string.settings_apple_working) else state.status.asString(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("apple_music_status")
        )
        state.storefront?.takeIf { it.isNotBlank() }?.let { storefront ->
            Text(
                storefront,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Button(
            onClick = { onConnect(endpoint) },
            enabled = !state.busy && endpoint.startsWith("https://"),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                stringResource(
                    when {
                        state.retryable -> R.string.settings_apple_retry
                        state.connected -> R.string.settings_apple_reconnect
                        else -> R.string.settings_apple_connect
                    },
                ),
            )
        }
    }
}

@Preview
@Composable
private fun AppleMusicValidationPreview() {
    YoinTheme { AppleMusicValidationContent(AppleMusicValidationUiState(), {}) }
}
