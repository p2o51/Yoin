package com.gpo.yoin.ui.detail

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gpo.yoin.enableYoinEdgeToEdge
import com.gpo.yoin.ui.theme.YoinTheme

/**
 * Activity Embedding 右栏的占位面（SplitPlaceholderRule，main_split_config.xml）。
 *
 * 它的存在解决分栏返回的两个怪点：detail 的半透明预测性返回塌缩背后有了
 * 实际内容（不再透出黑幕），最后一张 detail 关掉后右栏落回这里 —— 分栏
 * 不解散，shell 永不跳宽。窄窗（< 840dp）规则不激活，本 Activity 不会启动。
 *
 * 视觉是一枚空印模：Memories 印章语汇里「备好未用」的那一档。
 */
class DetailPlaceholderActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableYoinEdgeToEdge()
        setContent {
            YoinTheme {
                DetailPlaceholderPane()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DetailPlaceholderPane(modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 空印模：与 Memories 印章同形（Cookie12），描边 = 还没有内容落座。
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
                text = "Pick something to open it here",
                modifier = Modifier.padding(top = 24.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview
@Composable
private fun DetailPlaceholderPanePreview() {
    YoinTheme {
        DetailPlaceholderPane()
    }
}
