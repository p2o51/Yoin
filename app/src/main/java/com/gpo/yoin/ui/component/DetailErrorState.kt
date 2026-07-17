package com.gpo.yoin.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.ui.detail.DetailBackButton
import com.gpo.yoin.ui.theme.YoinTheme

/**
 * Shared error surface for detail pages (album / artist / playlist): a
 * centered message with a Retry action, plus the standard [DetailBackButton]
 * when [onBack] is provided so a failed load never strands the user.
 *
 * [backPadding] must mirror the page's Content-state back-button slot so an
 * Error → Content transition after Retry doesn't make the button jump. The
 * default matches the album header (see `AlbumTopHeader`); pages whose nav
 * slot sits elsewhere (the artist top bar) pass their own offsets. Only the
 * button takes the status-bar inset, so hosts already inside a Scaffold's
 * content padding don't get it twice.
 */
@Composable
fun DetailErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backPadding: PaddingValues = PaddingValues(start = 8.dp, top = 4.dp),
) {
    Box(modifier = modifier.fillMaxSize()) {
        if (onBack != null) {
            DetailBackButton(
                onClick = onBack,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(backPadding),
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun DetailErrorStatePreview() {
    YoinTheme {
        DetailErrorState(
            message = "Can't reach the server. Check your connection.",
            onRetry = {},
            onBack = {},
        )
    }
}
