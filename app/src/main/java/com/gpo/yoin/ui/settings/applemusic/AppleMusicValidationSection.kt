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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
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
    var endpoint by rememberSaveable(state.endpoint) { mutableStateOf(state.endpoint) }
    Column(
        Modifier.fillMaxWidth().testTag("apple_music_validation").padding(18.dp)
            .animateContentSize(YoinMotion.spatialSpring()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ExpressiveTextField(
            endpoint,
            { endpoint = it },
            "Developer token service",
            "https://…/token",
            modifier = Modifier.fillMaxWidth().testTag("apple_music_token_endpoint")
        )
        Text(
            if (state.busy) "Working…" else state.status,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("apple_music_status")
        )
        Button(
            onClick = { onConnect(endpoint) },
            enabled = !state.busy && endpoint.startsWith("https://"),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (state.connected) "Reconnect Apple Music" else "Connect Apple Music")
        }
    }
}

@Preview
@Composable
private fun AppleMusicValidationPreview() {
    YoinTheme { AppleMusicValidationContent(AppleMusicValidationUiState(), {}) }
}
