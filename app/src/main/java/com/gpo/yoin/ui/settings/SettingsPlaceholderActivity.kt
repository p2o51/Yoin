package com.gpo.yoin.ui.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.R
import com.gpo.yoin.YoinActivityRoot
import com.gpo.yoin.enableYoinEdgeToEdge
import com.gpo.yoin.ui.theme.YoinTheme

/**
 * The Settings list-detail's right pane before an account is picked
 * (SettingsTablet, 断点交接 §7). Started by the tag="settings-placeholder"
 * SplitPlaceholderRule only while the window is ≥ 840dp; the same empty seal
 * the shell's placeholder uses.
 */
class SettingsPlaceholderActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableYoinEdgeToEdge()
        setContent {
            // Same root (and playing-cover palette) as the Settings list
            // beside it, so the two panes paint the same page colour.
            YoinActivityRoot {
                SettingsPlaceholderPane()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SettingsPlaceholderPane(modifier: Modifier = Modifier) {
    // Same flat page colour as the Settings list beside it, so the split
    // reads as one surface.
    Surface(modifier = modifier.fillMaxSize(), color = settingsSurfaces().page) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(148.dp)
                    .border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = MaterialShapes.Cookie12Sided.toShape(),
                    ),
            )
            Text(
                text = stringResource(R.string.settings_placeholder),
                modifier = Modifier.padding(top = 24.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(widthDp = 860, heightDp = 800)
@Composable
private fun SettingsPlaceholderPanePreview() {
    YoinTheme {
        SettingsPlaceholderPane()
    }
}
