package com.gpo.yoin.ui.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 大屏限宽基线（2026-07-26 适配审计第 2 步）。
 *
 * 纵向滚动的内容页在 Medium+（>= 600dp）窗口里不该整页拉伸 —— 把内容夹到
 * [max] 并水平居中。手机上窗口本来就窄于 [max]，这条修饰符是无操作，所以
 * 不需要读 [com.gpo.yoin.ui.experience.LayoutMode] 做门控。
 *
 * 用在**内容容器**上（LazyColumn / Column），不要用在页面根上 —— 背景
 * （aurora、ExpressivePageBackground）必须留在全出血层。全出血横向 shelf
 * 的出血边界随之变成内容列边缘，与 contentPadding 对齐，边缘渐隐照常成立
 * （no-midpage-truncation 纪律不破）。
 */
fun Modifier.yoinPageContentWidth(max: Dp = YoinPageWidths.Feed): Modifier =
    this
        .fillMaxWidth()
        .wrapContentWidth(align = Alignment.CenterHorizontally)
        .widthIn(max = max)

/** 各类页面的限宽档。数值来自 M3 pane 常规宽度，不是新的魔法数。 */
object YoinPageWidths {
    /** 信息流页（Home / Library）：宽一点，容得下网格与 shelf。 */
    val Feed = 720.dp

    /** 阅读/表单页（Settings、全文 sheet 内容）。 */
    val Prose = 640.dp

    /** 单视口卡（Memories 印章卡）：按 393dp 设计稿放大到的舒适上限。 */
    val Card = 480.dp
}
