package com.gpo.yoin.ui.component

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.gpo.yoin.ui.navigation.back.ProvideSheetBack
import com.gpo.yoin.ui.navigation.back.SheetWindowBackInput
import com.gpo.yoin.ui.navigation.back.rememberSheetBackBridge

/**
 * M3's [ModalBottomSheet] whose system back always reaches it. Use it for any sheet a detail page
 * opens: on a Wide window the page lives in the shell's detail column, which provides its own back
 * dispatcher, and a raw ModalBottomSheet there ignores back (see SheetWindowBack.kt). Everything
 * else — the predictive shrink, scrim, drag-to-dismiss — is M3's own.
 *
 * No @Preview: a sheet is its own dialog window, which previews don't render.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YoinModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val back = rememberSheetBackBridge()
    ProvideSheetBack(back) {
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            sheetState = sheetState,
        ) {
            SheetWindowBackInput(back)
            content()
        }
    }
}
