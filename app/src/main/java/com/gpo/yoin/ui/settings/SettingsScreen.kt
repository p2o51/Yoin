package com.gpo.yoin.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.component.ExpressiveHeaderBlock
import com.gpo.yoin.ui.component.ExpressivePageBackground
import com.gpo.yoin.ui.component.ExpressiveSectionPanel
import com.gpo.yoin.ui.component.ExpressiveTextField
import com.gpo.yoin.ui.component.YoinLoadingIndicator
import com.gpo.yoin.ui.theme.ProvideYoinMotionRole
import com.gpo.yoin.ui.theme.YoinMotion
import com.gpo.yoin.ui.theme.YoinMotionRole
import com.gpo.yoin.ui.theme.YoinShapeTokens
import com.gpo.yoin.ui.theme.YoinTheme

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val connectionResult by viewModel.connectionResult.collectAsState()

    SettingsContent(
        uiState = uiState,
        connectionResult = connectionResult,
        onBackClick = onBackClick,
        onTestConnection = viewModel::testConnection,
        onSaveServer = viewModel::saveServer,
        onSaveGeminiApiKey = viewModel::saveGeminiApiKey,
        onClearCache = viewModel::clearCache,
        onDismissConnectionResult = viewModel::dismissConnectionResult,
        onRetry = viewModel::retry,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    uiState: SettingsUiState,
    connectionResult: SettingsViewModel.ConnectionResult?,
    onBackClick: () -> Unit,
    onTestConnection: (String, String, String) -> Unit,
    onSaveServer: (String, String, String) -> Unit,
    onSaveGeminiApiKey: (String) -> Unit = {},
    onClearCache: () -> Unit,
    onDismissConnectionResult: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ProvideYoinMotionRole(role = YoinMotionRole.Standard) {
        val contentAlpha by animateFloatAsState(
            targetValue = if (uiState is SettingsUiState.Loading) 0f else 1f,
            animationSpec = YoinMotion.defaultEffectsSpec(),
            label = "contentAlpha",
        )

        ExpressivePageBackground(modifier = modifier) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    TopAppBar(
                        title = { Text("Settings") },
                        navigationIcon = {
                            IconButton(onClick = onBackClick) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                            titleContentColor = MaterialTheme.colorScheme.onSurface,
                            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    )
                },
            ) { innerPadding ->
                val navBottom = WindowInsets.navigationBars.asPaddingValues()
                    .calculateBottomPadding()
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(innerPadding)
                        .padding(
                            start = 16.dp,
                            top = 16.dp,
                            end = 16.dp,
                            bottom = 16.dp + navBottom,
                        )
                        .alpha(contentAlpha),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    ExpressiveHeaderBlock(
                        title = "Settings",
                    )

                    when (uiState) {
                        is SettingsUiState.Loading -> {
                            YoinLoadingIndicator(
                                modifier = Modifier.align(Alignment.CenterHorizontally),
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
                            GeminiSection(
                                initialApiKey = uiState.geminiApiKey,
                                onSaveApiKey = onSaveGeminiApiKey,
                            )
                            CacheSection(
                                cacheSizeBytes = uiState.cacheSizeBytes,
                                onClearCache = onClearCache,
                            )
                            AboutSection()
                        }

                        is SettingsUiState.Error -> {
                            ExpressiveSectionPanel(
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text(
                                        text = uiState.message,
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    TextButton(onClick = onRetry) {
                                        Text("Retry")
                                    }
                                }
                            }
                        }
                    }
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

    ExpressiveSectionPanel(
        modifier = modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f),
        tonalElevation = 1.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ExpressiveHeaderBlock(
                title = "Server",
            )

            ExpressiveTextField(
                value = serverUrl,
                onValueChange = {
                    serverUrl = it
                    onDismissConnectionResult()
                },
                label = "Server URL",
                placeholder = "https://your-server.com",
                modifier = Modifier.fillMaxWidth(),
            )

            ExpressiveTextField(
                value = username,
                onValueChange = {
                    username = it
                    onDismissConnectionResult()
                },
                label = "Username",
                placeholder = "music-admin",
                modifier = Modifier.fillMaxWidth(),
            )

            ExpressiveTextField(
                value = password,
                onValueChange = {
                    password = it
                    onDismissConnectionResult()
                },
                label = "Password",
                placeholder = "App password",
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )

            ConnectionStatusIndicator(
                connectionResult = connectionResult,
                isConnecting = isConnecting,
            )

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
}

@Composable
private fun ConnectionStatusIndicator(
    connectionResult: SettingsViewModel.ConnectionResult?,
    isConnecting: Boolean,
    modifier: Modifier = Modifier,
) {
    val alpha by animateFloatAsState(
        targetValue = if (connectionResult != null || isConnecting) 1f else 0.88f,
        animationSpec = YoinMotion.effectsSpring(),
        label = "statusAlpha",
    )
    val statusColor by animateColorAsState(
        targetValue = when (connectionResult) {
            is SettingsViewModel.ConnectionResult.Success -> MaterialTheme.colorScheme.primary
            is SettingsViewModel.ConnectionResult.Failure -> MaterialTheme.colorScheme.error
            null -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = YoinMotion.defaultEffectsSpec(),
        label = "statusColor",
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .alpha(alpha),
        shape = YoinShapeTokens.Large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.64f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                isConnecting -> {
                    YoinLoadingIndicator(
                        modifier = Modifier.size(20.dp),
                        size = 20.dp,
                    )
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(8.dp))
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
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(8.dp))
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
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = connectionResult.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = statusColor,
                    )
                }

                else -> {
                    Text(
                        text = "Ready to test the current server settings.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun GeminiSection(
    initialApiKey: String,
    onSaveApiKey: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var apiKey by rememberSaveable { mutableStateOf(initialApiKey) }

    ExpressiveSectionPanel(
        modifier = modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.95f),
        tonalElevation = 1.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ExpressiveHeaderBlock(
                title = "AI Features",
            )

            Text(
                text = "Enter your Gemini API key to enable AI-powered song info. Get one from Google AI Studio.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ExpressiveTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = "Gemini API Key",
                placeholder = "AIza…",
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = { onSaveApiKey(apiKey) },
                enabled = apiKey.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save API Key")
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
    ExpressiveSectionPanel(
        modifier = modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ExpressiveHeaderBlock(
                title = "Cache",
            )

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
}

@Composable
private fun AboutSection(modifier: Modifier = Modifier) {
    ExpressiveSectionPanel(
        modifier = modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ExpressiveHeaderBlock(
                title = "About",
            )

            Text(
                text = "Built for testing navigation, playback, and Subsonic integration.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = "Version 0.1.0",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1_024L -> "$bytes B"
    bytes < 1_048_576L -> "%.1f KB".format(bytes / 1_024.0)
    bytes < 1_073_741_824L -> "%.1f MB".format(bytes / 1_048_576.0)
    else -> "%.2f GB".format(bytes / 1_073_741_824.0)
}

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
            onBackClick = {},
            onTestConnection = { _, _, _ -> },
            onSaveServer = { _, _, _ -> },
            onSaveGeminiApiKey = {},
            onClearCache = {},
            onDismissConnectionResult = {},
            onRetry = {},
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
            onBackClick = {},
            onTestConnection = { _, _, _ -> },
            onSaveServer = { _, _, _ -> },
            onSaveGeminiApiKey = {},
            onClearCache = {},
            onDismissConnectionResult = {},
            onRetry = {},
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
            onBackClick = {},
            onTestConnection = { _, _, _ -> },
            onSaveServer = { _, _, _ -> },
            onSaveGeminiApiKey = {},
            onClearCache = {},
            onDismissConnectionResult = {},
            onRetry = {},
        )
    }
}
