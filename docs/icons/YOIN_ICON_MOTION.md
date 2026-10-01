# Yoin 图标动效规格

> 配套原型：`docs/icons/preview.html` 的「动效」一节（22 个，可慢放、可切“减弱动效”）。
> Spring 档位全部对应 `YoinMotion` / `MotionScheme`，不引入 tween。

## 1. 原则

1. **状态是同一组线条的变形。** 三角变暂停条、箭头变对勾、平行线变交叉，都是点对点插值；不在两张图之间淡入淡出。
2. **等待时让图标自己干活。** 翻译、歌词搜索、投屏连接、AI 生成、加入资料库这些等待，不把按钮换成转圈，按钮尺寸不变，图标做和含义相关的动作。
3. **循环要有停顿。** 每一轮 = 一次 spring + 0.2–0.4 s 停顿。只在停顿点检查“完成了吗”，结束永远落在整齐的姿态上，不会半路刹车。
4. **位移走空间 spring，颜色走效果 spring。** 图标级位移用 fast 档（行程只有 1–3 dp）；透明度、填充、颜色用 effects 档，不过冲。
5. **余韵收尾。** 暂停、断开、取消用低刚度衰减结束，不硬切。

| 原型里的缩写 | 对应 | ζ / stiffness |
|---|---|---|
| E·fast | `YoinMotion.fastSpatialSpec(Expressive)` | 0.6 / 800 |
| E·default | `YoinMotion.defaultSpatialSpec(Expressive)` | 0.8 / 380 |
| S·fast | `YoinMotion.fastSpatialSpec(Standard)` | 0.9 / 1400 |
| S·default | `YoinMotion.defaultSpatialSpec(Standard)` | 0.9 / 700 |
| fastEffects / defaultEffects / slowEffects | `YoinMotion.*EffectsSpec()` | 1.0 / 3800 · 1600 · 800 |
| 余韵衰减 | 新增 token（建议放进 `YoinMotion`） | 1.0 / 60 |
| 抖动 | 新增 token（失败反馈） | 0.28 / 900 |

---

## 2. 重点：翻译等待 · 文 / A 轨道交换

**触发**：`LyricsActionBar` 点翻译 → `lyricsActionInFlight == LyricsAction.Translate`。

**动作**

- 文 和 A 各自保持直立，沿同一个圆（半径 ≈ 5.3，圆心在图标中心）每次走半圈，互换位置。
- 走到半路时，从右上经过的那个字放大到 1.08，从左下经过的缩到 0.86、透明度降到约 0.6——像两张牌一前一后交错，不会在中间撞在一起。
- 每次交换用 **E·default**，落位后停 **0.32 s** 再换下一次。

**结束**

- 结果到达时，**走完当前这半圈**再判断：如果停在交换后的位置（A 在左上），再补半圈回到 文A。图标的静止姿态永远是 文A。
- 落位后按钮进入“已翻译”状态（建议：容器从 `primaryContainer` 用效果 spring 过渡到 `primary`，作为开关的选中态），译文按现有规则逐行从行下沿展开。
- 失败：回到 文A 后左右摇两下（抖动 token）+ `performReject()`。

**减弱动效**：字形不动，只做 0.42 ↔ 1 的透明度呼吸（slowEffects）；完成时停在 1。

**顺带建议**：现在 `LyricsActionBar` 里四个按钮都用 `enabled = actionInFlight == null`，翻译进行中时翻译按钮本身也会变灰，动画会被压到 38% 透明度。建议进行中的那个按钮保持满强调（点击可忽略或视为取消），只把另外三个降为不可用。

**Compose 草图**

```kotlin
@Composable
fun TranslateIcon(translating: Boolean, modifier: Modifier = Modifier) {
    val theta = remember { Animatable(0f) }
    val spatial = YoinMotion.defaultSpatialSpec<Float>(YoinMotionRole.Expressive)
    val translatingNow by rememberUpdatedState(translating)

    // 一个长寿命协程：不按 translating 重启，避免结果到达时把半圈动画掐断
    LaunchedEffect(Unit) {
        while (isActive) {
            if (translatingNow) {
                theta.animateTo(theta.targetValue + 180f, spatial)
                if (translatingNow) delay(320)
            } else {
                if (theta.targetValue % 360f != 0f) theta.animateTo(theta.targetValue + 180f, spatial)
                snapshotFlow { translatingNow }.first { it }
            }
        }
    }

    val painter = rememberVectorPainter(
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f, autoMirror = false,
    ) { _, _ ->
        OrbitGlyph(theta.value, home = Offset(8.2f, 8.3f), paths = WenPaths)
        OrbitGlyph(theta.value, home = Offset(15.75f, 15.75f), paths = LatinPaths)
    }
    Icon(painter, if (translating) "正在翻译" else "翻译歌词", modifier)
}

@Composable
private fun OrbitGlyph(thetaDeg: Float, home: Offset, paths: List<List<PathNode>>) {
    val v = home - Offset(12f, 12f)
    val a = Math.toRadians(thetaDeg.toDouble())
    val p = Offset((v.x * cos(a) - v.y * sin(a)).toFloat(), (v.x * sin(a) + v.y * cos(a)).toFloat())
    val depth = (p.x - p.y) / (v.getDistance() * sqrt(2f))          // +1 右上（前）, −1 左下（后）
    val s = 1f + (if (depth > 0) 0.08f else 0.14f) * depth
    val alpha = 1f - 0.42f * max(0f, -depth)
    Group(
        translationX = p.x - v.x, translationY = p.y - v.y,
        pivotX = home.x, pivotY = home.y, scaleX = s, scaleY = s,
    ) {
        paths.forEach { Path(it, stroke = SolidColor(Color.Black), strokeAlpha = alpha, strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) }
    }
}
```

`WenPaths` / `LatinPaths` 直接取 `ic_yoin_translate.xml` 里 `wen`、`latin` 两组的 `pathData`（`PathParser().parsePathString(…).toNodes()`）。

---

## 3. 动效清单

| # | 场景 | 位置 | 动作 | Spring | 减弱动效 | 触觉 |
|---|---|---|---|---|---|---|
| 1 | 翻译等待 | LyricsActionBar | 见上 | E·default + 0.32 s 停顿 | 透明度呼吸 | 失败 REJECT |
| 2 | 加入资料库 · 等待确认 | Apple Music「+」 | 竖线收起、横线缩成圆心 → 卡片内一段弧转动；确认后横线从圆心展开成对勾并回弹；失败回到 + 并摇 | 变形 E·fast；弧 fast/slowEffects | 弧不转只呼吸；确认直接出对勾 | 确认 CONFIRM；失败 REJECT |
| 3 | AI 生成 | AskGeminiBar · Memory 拟题 · About | 大星每次转 90° 后停，小星错拍闪；完成时填实 + 轻微 bloom | 旋转 E·default；填充 defaultEffects | 透明度呼吸，完成直接填实 | — |
| 4 | 投屏连接 | Devices 胶囊 · CastButton · App Remote | 点 → 内弧 → 外弧依次点亮并外弹；连上后面板从左下角长出（cast_connected） | 点亮 defaultEffects；外弹 E·fast；面板 E·default | 只有透明度 | 连上 CONFIRM |
| 5 | 歌词搜索中 | LyricsSheets | 放大镜在三个位置间“张望”，找到后回原位并放大一下 | E·default + 0.16 s | 透明度呼吸 | — |
| 6 | 重新扫描设备 | DevicesSheet | 每轮转一整圈后停，结束停在整圈 | E·default + 0.22 s | 透明度呼吸 | — |
| 7 | 下载 → 已下载 | 离线下载（二期） | 下载中箭头点头；完成时箭杆缩进、箭头三点变对勾 | E·fast | 直接切换 | 完成 CONFIRM |
| 8 | 正在播放律动 | 列表当前行 · NP 胶囊 | 播放时柱子追随随机目标（接通 Visualizer 后用真实能量）；暂停时两边到中间依次缩成圆点 | 跳动 E·fast；衰减 ζ1 k60 | 静止，暂停降透明度 | — |
| 9 | 播放 ↔ 暂停 | PlaySplitButton · 胶囊 | 三角竖切两半，各变一根暂停条；不旋转 | S·fast | 不需要降级 | 按现有 performClick |
| 10 | 收藏 | NP 心形 · 曲目 | 描边心跳（0.8 → 1），实心从中心长出，六点散开；取消只收回实心 | E·fast；散点 slowEffects | 实心淡入淡出 | CONFIRM / REJECT |
| 11 | 随机 | PlaybackControls | 关 = 两条平行线，开 = 两股交叉；下层留缝跟随交叉点 | E·default | S·fast | — |
| 12 | 循环 关/列表/单曲 | 播放控制 | 关 = 40% 透明度；切换时箭头各自推一下；单曲时「1」弹出 | E·fast；透明度 defaultEffects | 无推动 | — |
| 13 | 展开 / 收起 | 设置折叠行 · 下拉 · Memories | 推荐“压平再翻折”，不用整体旋转 | E·default | S·fast | — |
| 14 | 导航选中 | Button Group · Nav Rail | 实心从底部涨满 + 轻压回弹，与按钮变宽同帧 | 涨满 S·fast；回弹 E·fast | 实心淡入 | 现有 VIRTUAL_KEY |
| 15 | 预测性返回 | 所有返回箭头 | 进度经 backGestureEasing → 箭杆缩短、箭头左移；提交滑出，取消弹回 | 手势直驱；提交 E·fast；取消 S·default | 只缩箭杆 | — |
| 16 | 上一首 / 下一首 | PlaybackControls · 胶囊 | 三角朝切歌方向推一下（与胶囊推入方向一致） | E·fast 冲量 | 无位移 | CLOCK_TICK |
| 17 | 显示 / 隐藏 | 设置 Token 输入框 | 斜线画出、眼睛微眯，斜线下的轮廓自动断开 | S·default | 斜线直接出现 | — |
| 18 | 回到当前句 | LyricsActionBar | 两箭头向中线合拢再弹开，中线闪一下 | E·fast 冲量；slowEffects | 只闪中线 | — |
| 19 | 错误 | 设置徽标 · 连接失败 | 感叹号摇两下，只在刚失败时 | 抖动 ζ0.28 k900 | 只变色 | REJECT |
| 20 | 发送笔记 | NoteContent | 纸飞机右上飞出，从左下飞回 | E·fast / E·default | 淡出淡入 | CONFIRM |
| 21 | 记忆印章（提案） | Memories 生成完成 | 印章转回正位并盖下，心形随后弹出 | E·fast | 直接出现 | CONFIRM |
| 22 | 设置齿轮 | 右上角入口 | 每次点按转一个齿（45°） | E·default | 不转 | — |

---

## 4. 实现建议

- 做一个 `YoinAnimatedIcon` 小家族：每个动画图标一个 Composable，内部 `rememberVectorPainter { Group { Path } }`，由 `Animatable` + `YoinMotion.*Spec()` 驱动；静态场景继续用 `painterResource(R.drawable.ic_yoin_*)`。
- 循环类统一成一个 helper：`suspend fun loopUntil(done: () -> Boolean, beat: suspend () -> Unit, hold: Long)`，只在 `beat()` 结束后检查 `done`。
- 所有循环在 `MotionProfile` 为 AdaptiveReduced 时切到透明度呼吸，时长不变。
- 两态插值要求两态路径点数相同：本套图标里 `play_filled`/`pause_filled`、`download`/`download_done`、`library_add`/`library_added`、`chevron_*` 已经按这个规则画好。
- 新 token：`resonance`（ζ1 k60）和 `shake`（ζ0.28 k900）建议放进 `YoinMotion`，不要在业务代码里写裸 `spring()`。

---

## 5. 待定

1. 翻译完成后，翻译按钮要不要变成真正的开关（选中态 = 实心 primary，再点收起译文）？原型按“是”做的。
2. 翻译进行中再点一次：忽略，还是取消？
3. 落位规则：原型总是回到 文A（最多多走半圈）。也可以允许停在 A文，但那样同一个按钮会有两种静止外观。
4. Now Playing 的主播放键现在是文字 PAUSE / PLAY。建议大按钮保留文字（更 Expressive），图标变形只用在 PlaySplitButton 和胶囊。
5. 律动柱要不要等 AudioVisualizer 接通后再上（design.md 待确认事项里写着还没接通）？在此之前可以先用随机目标。
6. `memory` 印章图标和第 21 个动效是提案，要不要给 Memories 一个图标入口？
