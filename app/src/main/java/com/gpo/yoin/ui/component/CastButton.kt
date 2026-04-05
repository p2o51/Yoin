package com.gpo.yoin.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.player.CastState
import com.gpo.yoin.ui.theme.YoinTheme

/**
 * Cast button / device pill shown in the Now Playing bottom area.
 *
 * - [CastState.NotAvailable] → hidden (animated out)
 * - [CastState.Available] → cast icon button
 * - [CastState.Connected] → tonal pill with device name
 */
@Composable
fun CastButton(
    castState: CastState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = castState !is CastState.NotAvailable,
        enter = fadeIn(spring(stiffness = Spring.StiffnessLow)) +
            expandHorizontally(spring(stiffness = Spring.StiffnessMediumLow)),
        exit = fadeOut(spring(stiffness = Spring.StiffnessLow)) +
            shrinkHorizontally(spring(stiffness = Spring.StiffnessMediumLow)),
        modifier = modifier,
    ) {
        when (castState) {
            is CastState.Available -> {
                IconButton(onClick = onClick) {
                    Icon(
                        imageVector = Icons.Filled.Cast,
                        contentDescription = "Cast to device",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            is CastState.Connected -> {
                FilledTonalButton(onClick = onClick) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.CastConnected,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = castState.deviceName)
                    }
                }
            }

            // NotAvailable — AnimatedVisibility hides the entire block
            else -> {}
        }
    }
}

// ── Previews ────────────────────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun CastButtonAvailablePreview() {
    YoinTheme {
        CastButton(
            castState = CastState.Available,
            onClick = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun CastButtonConnectedPreview() {
    YoinTheme {
        CastButton(
            castState = CastState.Connected("Living Room TV"),
            onClick = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun CastButtonNotAvailablePreview() {
    YoinTheme {
        CastButton(
            castState = CastState.NotAvailable,
            onClick = {},
        )
    }
}
