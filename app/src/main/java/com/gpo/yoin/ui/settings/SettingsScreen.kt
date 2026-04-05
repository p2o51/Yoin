package com.gpo.yoin.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinTheme

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val connectionResult by viewModel.connectionResult.collectAsState()

    SettingsContent(
        uiState = uiState,
        connectionResult = connectionResult,
        onTestConnection = viewModel::testConnection,
        onSaveServer = viewModel::saveServer,
        onClearCache = viewModel::clearCache,
        onDismissConnectionResult = viewModel::dismissConnectionResult,
        modifier = modifier,
    )
}

@Composable
fun SettingsContent(
    uiState: SettingsUiState,
    connectionResult: SettingsViewModel.ConnectionResult?,
    onTestConnection: (String, String, String) -> Unit,
    onSaveServer: (String, String, String) -> Unit,
    onClearCache: () -> Unit,
    onDismissConnectionResult: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentAlpha by animateFloatAsState(
        targetValue = if (uiState is SettingsUiState.Loading) 0f else 1f,
        animationSpec = YoinMotion.effectsSpring(),
        label = "contentAlpha",
    )

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .alpha(contentAlpha),
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )

            Spacer(modifier = Modifier.height(24.dp))

            when (uiState) {
                is SettingsUiState.Loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                is SettingsUiState.Connecting -> {
                    ServerSection(
                        initialUrl = "",
                        initialUsername = "",
                        isConnecting = true,
                        connectionResult = connectionResult,
                        onTestConnection = onTestConnection,
                        onSaveServer = onSaveServer,
                        onDismissConnectionResult = onDismissConnectionResult,
                    )
                }
                is SettingsUiState.Content -> {
                    ServerSection(
                        initialUrl = uiState.serverUrl,
                        initialUsername = uiState.username,
                        isConnecting = false,
                        connectionResult = connectionResult,
                        onTestConnection = onTestConnection,
                        onSaveServer = onSaveServer,
                        onDismissConnectionResult = onDismissConnectionResult,
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(24.dp))
                    CacheSection(
                        cacheSizeBytes = uiState.cacheSizeBytes,
                        onClearCache = onClearCache,
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(24.dp))
                    AboutSection()
                }
                is SettingsUiState.Error -> {
                    Text(
                        text = uiState.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerSection(
    initialUrl: String,
    initialUsername: String,
    isConnecting: Boolean,
    connectionResult: SettingsViewModel.ConnectionResult?,
    onTestConnection: (String, String, String) -> Unit,
    onSaveServer: (String, String, String) -> Unit,
    onDismissConnectionResult: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var serverUrl by rememberSaveable { mutableStateOf(initialUrl) }
    var username by rememberSaveable { mutableStateOf(initialUsername) }
    var password by rememberSaveable { mutableStateOf("") }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Server",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = serverUrl,
            onValueChange = {
                serverUrl = it
                onDismissConnectionResult()
            },
            label = { Text("Server URL") },
            placeholder = { Text("https://your-server.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = username,
            onValueChange = {
                username = it
                onDismissConnectionResult()
            },
            label = { Text("Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = password,
            onValueChange = {
                password = it
                onDismissConnectionResult()
            },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(16.dp))

        ConnectionStatusIndicator(
            connectionResult = connectionResult,
            isConnecting = isConnecting,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = { onTestConnection(serverUrl, username, password) },
                enabled = !isConnecting && serverUrl.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                Text("Test Connection")
            }

            Button(
                onClick = { onSaveServer(serverUrl, username, password) },
                enabled = !isConnecting && serverUrl.isNotBlank() && username.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                Text("Save")
            }
        }
    }
}

@Composable
private fun ConnectionStatusIndicator(
    connectionResult: SettingsViewModel.ConnectionResult?,
    isConnecting: Boolean,
    modifier: Modifier = Modifier,
) {
    val targetAlpha = if (connectionResult != null || isConnecting) 1f else 0f
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = YoinMotion.effectsSpring(),
        label = "statusAlpha",
    )

    val statusColor by animateColorAsState(
        targetValue = when (connectionResult) {
            is SettingsViewModel.ConnectionResult.Success ->
                MaterialTheme.colorScheme.primary

            is SettingsViewModel.ConnectionResult.Failure ->
                MaterialTheme.colorScheme.error

            null -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "statusColor",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(alpha),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            isConnecting -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Testing connection…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            connectionResult is SettingsViewModel.ConnectionResult.Success -> {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "Connected",
                    tint = statusColor,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Connection successful",
                    style = MaterialTheme.typography.bodyMedium,
                    color = statusColor,
                )
            }
            connectionResult is SettingsViewModel.ConnectionResult.Failure -> {
                Icon(
                    imageVector = Icons.Filled.Error,
                    contentDescription = "Error",
                    tint = statusColor,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = connectionResult.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = statusColor,
                )
            }
        }
    }
}

@Composable
private fun CacheSection(
    cacheSizeBytes: Long,
    onClearCache: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Cache",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Cache size: ${formatBytes(cacheSizeBytes)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedButton(onClick = onClearCache) {
                Text("Clear Cache")
            }
        }
    }
}

@Composable
private fun AboutSection(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "About",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Yoin — Subsonic Music Client",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Version 0.1.0",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1_024L -> "$bytes B"
    bytes < 1_048_576L -> "%.1f KB".format(bytes / 1_024.0)
    bytes < 1_073_741_824L -> "%.1f MB".format(bytes / 1_048_576.0)
    else -> "%.2f GB".format(bytes / 1_073_741_824.0)
}

// ── Previews ────────────────────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
fun SettingsContentPreview() {
    YoinTheme {
        SettingsContent(
            uiState = SettingsUiState.Content(
                serverUrl = "https://demo.navidrome.org",
                username = "demo",
                isConnected = true,
                cacheSizeBytes = 52_428_800L,
            ),
            connectionResult = SettingsViewModel.ConnectionResult.Success,
            onTestConnection = { _, _, _ -> },
            onSaveServer = { _, _, _ -> },
            onClearCache = {},
            onDismissConnectionResult = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
fun SettingsContentLoadingPreview() {
    YoinTheme {
        SettingsContent(
            uiState = SettingsUiState.Loading,
            connectionResult = null,
            onTestConnection = { _, _, _ -> },
            onSaveServer = { _, _, _ -> },
            onClearCache = {},
            onDismissConnectionResult = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
fun SettingsContentErrorPreview() {
    YoinTheme {
        SettingsContent(
            uiState = SettingsUiState.Content(
                serverUrl = "https://bad-server.com",
                username = "user",
                isConnected = false,
                cacheSizeBytes = 0L,
            ),
            connectionResult = SettingsViewModel.ConnectionResult.Failure(
                "Connection refused",
            ),
            onTestConnection = { _, _, _ -> },
            onSaveServer = { _, _, _ -> },
            onClearCache = {},
            onDismissConnectionResult = {},
        )
    }
}
