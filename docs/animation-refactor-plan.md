# 动画重构方案：Lyrics 全屏体验修复 + 全局 Pixel 风格转场统一

> 2026-06-11。基于 10 个并行研究 agent 的代码分析与外部资料调研（AOSP WM Shell 源码、Nav3 1.1.0 源码、M3 设计规范、Compose 渲染管线），bug 根因均经过独立对抗性复核确认。

> **2026-06-12 实施记录**：方案已落地，与原稿的差异（按用户决策）——
> ① push 进入不用 Pixel 镜像缩放，改为**原生右滑推入**（新页 `slideIn{it}`，旧页 `-it/4` 视差让位）；pop/手势返回保持 Pixel 缩向中心（0.9 + 28dp 圆角 + 尾段淡出 keyframes）。
> ② Home→Detail 的**封面 shared element 飞行已删除**（detail 侧传 `animatedVisibilityScope = null`，Home 侧键位不再匹配、自动失活）。
> ③ 预览模型（scrub 前 40% + M3 缓动 + spring 追踪 + 速度连续 settle）应用于 Expanded 收起手势；mini player 收起（用户已满意）保持原样。
> ④ `YoinBackSurface`/`BackSurfaceController`/`BackProgressNormalizer`/`BackSurfaceKind` 已删除；nav3 升级 1.1.2。锯齿修复采用复核后的根因方案（Coil 固定尺寸请求 + `FilterQuality.Medium` + 发丝线描边 + 二值 alpha 回滚）。

> **2026-06-12 复审后修复**（4 lens 对抗性审查，2 个 confirmed + 2 个未验证但属实）——
> - **[critical] 取消手势卡死**：Expanded 收起手势取消时，settle 跑在已被系统取消的协程里 → 永远不执行 → 画面冻在半收起态。修法：把 `isGestureDriving` 加进重建 `LaunchedEffect` 的 keys，`endGesture()` 后 effect 重跑并按当前 `stageMode`（取消时仍是 Expanded）带速度恢复，取消路径不再自己 settle。
> - **[major] 状态错位 wedge**：手势/settle 窗口内若有 `stageMode` 变更（如收起途中点按重新展开）会被一次性 guard 静默吞掉 → `stageMode` 与进度永久脱节。同一个 keys 修复一并解决（effect 在退出 gesture-driving 时重新收敛）。
> - **[critical] 越界 pop 崩溃**：toolbar 返回箭头改成裸 `removeLastOrNull()` 后，pop 转场 450ms 内重复点击会二次出栈 → 清空回退栈使 NavDisplay 崩溃 / 深栈过度回退。修法：`popPage`（`size>1` 守卫）+ 每个 entry 的 `rememberSinglePop` 单次闩锁。
> - **[major] navPopCorners 误伤**：`EnterExitState.PostExit` 对「被前进 push 盖住的底层页」也触发，会给 Artist→Album 时底层 Artist 页加圆角。修法：`navPopCorners(isPopping)`，`isPopping = route !in backStack`（pop 时该 route 已出栈→true，push 盖住时仍在栈→false）。
> - 删除 refactor 后零调用的 `snapDetail`/`snapImmersive`；修正 `FilterQuality.Medium` 注释（Android 上等同 Low、无 mipmap，真正的修复是固定尺寸解码）；描边 alpha 加包络渐变避免端点处描边突跳。
>
> 待用户实测判断的视觉项：pop 时底层页 `fadeIn(initialAlpha = 0.6)` 在缩放露边期间可能有一瞬轻微的暗边（类似系统 scrim），若不喜欢可调高 initialAlpha 或改纯滑入。`nav3-transitions` 审查 lens 因账号会话上限未跑完，其余关注点（zIndex 顺序、shared element 失活、back 分发顺序）已人工推演确认无碍。

---

## 0. 结论速览

| 问题 | 根因（已验证） | 修法 | 改动量 |
|---|---|---|---|
| (1c) 横滑切换 Lyrics/About/Note 失效 | 一个 `PagerState` 被绑到两个同时存活的 `HorizontalPager`，框架只支持一对一，主 pager 的测量被底部 accessory pager 抢走 | accessory 条改为自有 state 镜像主 pager | 小，独立可发 |
| (1a) 圆角矩形缩小时边缘毛刺 | 不是 clip 的问题——是 `CoverTransitionOverlay` 内 Coil 图片以 ~296dp 解析后用 `FilterQuality.Low`（双线性、无 mipmap）缩到 44dp，高对比封面内容在边缘锯齿；旧的 1dp 描边又被去掉了 | 高质量采样 + 恢复发丝线描边 | 小 |
| (1b) 预测性返回机械地跟手 | 手势 progress **线性 1:1** 钉满整个 Compact↔Expanded reshape：无输入缓动、无 spring 追踪、settle 不带手势速度 | 「缓动输入 + 预览上限 + spring 追踪 + 速度种子 settle」四件套 | 中 |
| (2) 全局 Pixel 风格转场 | 该动画 = Android **predictive back 跨 Activity 系统动画**（WM Shell `DefaultCrossActivityBackAnimation`），系统只在 Activity/Task 边界播放，应用内导航必须自己实现——这就是「Pixel 上也不是处处可见」的原因。本项目的 pop 已有雏形（scaleOut 0.70 + fade），但 pivot 在左缘、push 不对称、且 **Nav3 的手势预览被 `YoinBackSurface` 的普通 BackHandler 截胡而完全失效** | 移除 BackHandler 抢占，让 NavDisplay 的 seekable predictive pop 接管；按 AOSP 参数重定义统一转场 | 中 |

---

## 1. 风格基准：现有 Now Playing 展开动画为什么好

代码拆解提炼出的可迁移原则（重构各处都应遵守这套词汇）：

1. **每个运动轴一个标量 fraction**，由 `@Stable` holder 持有（[NowPlayingStageProgress.kt](../app/src/main/java/com/gpo/yoin/ui/nowplaying/NowPlayingStageProgress.kt)）；spring 与手势写同一个值，配 `isGestureDriving` 交接守卫——点按动画与手势 scrub 像素级一致。
2. **全 spring、按语义分桶**：空间运动用 spatial spring（expressive 0.8/380；大元素用更慢的 0.8/200 显得有分量），透明度用临界阻尼 effects spring（1.0/1600）。
3. **距离错峰代替时间错峰**：所有层吃同一个 progress，但各自位移距离不同（12/16/32/48dp），自然产生纵深视差。
4. **交叉淡入淡出必配反向位移**（出场往下 12dp、入场从下 16dp 来）。
5. **代理飞行元素**只在 progress 1%~99% 存在，端点处永远显示真实组件。
6. **settle 永远从当前值继续**，绝不从端点重启；释放判定带速度阈值（240px 或 800px/s）。
7. **每帧读数全部推迟到 draw phase**（`graphicsLayer{}` lambda、fraction 以 lambda 下传），不触发重组。

判断标准：下文所有改动若违反这 7 条，就是回退。

---

## 2. 问题诊断详情

### 2a. 锯齿（毛刺）——根因在图片采样，不在 clip

对抗复核**否决**了第一轮「fractional alpha 导致离屏合成再缩放」的诊断（那个层只缩 8%，且 HWUI 对 property-alpha 的 saveLayer 在变换后分配，clip 轮廓每帧仍以分析式 AA 重新求值；0.92 倍的双线性重采样也产生不了阶梯状锯齿）。

**确认的根因**：真正可见的、从 ~296dp 缩到 44dp 的圆角矩形是 [NowPlayingScreen.kt:1139-1176](../app/src/main/java/com/gpo/yoin/ui/nowplaying/NowPlayingScreen.kt) 的 `CoverTransitionOverlay`。它用每帧 layout（`.offset(lerpDp)+.size(lerpDp)`）做尺寸动画——**clip 边缘本身是分析式抗锯齿的，没有问题**。毛刺来自内容：

- `ExpressiveMediaArtwork` 的 `AsyncImage`（[ExpressiveComponents.kt:139-151](../app/src/main/java/com/gpo/yoin/ui/component/ExpressiveComponents.kt)）的 `ImageRequest` 按初始尺寸解析、未指定 `filterQuality`（默认 `Low` = 双线性、无 mipmap），收缩过程中位图被最高 ~6.7× 双线性缩小，高对比封面内容在 clip 边缘处闪烁出阶梯;
- 原本能遮住这条接缝的 1dp 描边被显式去掉（`PlainAlbumCover` 传 `border = null`，NowPlayingScreen.kt:1572）。

**附带发现**：未提交改动把 compact `AlbumCover` 的 alpha 从二值改成了 fractional（NowPlayingScreen.kt:722-726），叠加父层同为 `compactProgress` 的 alpha 后形成 alpha² 双重淡出——不产生锯齿但会有 ghosting，顺手修。

### 2b. 机械的预测性返回——「拖直尺」三缺一对照

Expanded 阶段的 handler（[YoinNavHost.kt:711-733](../app/src/main/java/com/gpo/yoin/ui/navigation/YoinNavHost.kt)）：

```kotlin
progress.collectLatest { event -> stageProgress.snapDetail(1f - event.progress) }
```

- 系统 progress **线性直写**主 fraction，整个 reshape（封面行高度→0、tabs 变形、controls 回归、pane 交叉淡化）被钉满手势全程——每毫米指尖位移都 1:1 驱动布局；
- 释放 settle（`animateDetailTo`）**初速度为 0**（[NowPlayingStageProgress.kt:44-56](../app/src/main/java/com/gpo/yoin/ui/nowplaying/NowPlayingStageProgress.kt) 的 `animate()` 没传 `initialVelocity`），手势速度被丢弃；
- 对照你满意的 Compact 收起路径：它把 progress 喂给 spring 追踪器（`animateFloatAsState(progress * 1200f)`，YoinNavHost.kt:542-546）+ 速度感知释放（:938-952）——三件套全有。

技术背景（外部研究确认）：`BackEvent.progress` 在工作区间内与指尖位移近似线性（系统只做了中等刚度的临界阻尼预平滑），Google 设计规范明确写着 **"Do not use linear progress directly"**；material3 内部统一用 `CubicBezierEasing(0.1, 0.1, 0, 1)`（= AOSP `BACK_GESTURE` 插值器）重塑输入。

### 2c. 横滑失效——一个 PagerState 绑了两个 Pager

提交 `9f08a6d0` 把同一个 `pagerState` 同时绑给：

- 主 detail pager（NowPlayingScreen.kt:804-808）；
- 底部 accessory 条 pager（LyricsActionBar/AskGeminiBar 切换，NowPlayingScreen.kt:941-949，`userScrollEnabled = false`）。

`PagerState` 只支持单一附着：`remeasurement` 槽位按组合顺序后到者得（accessory），`pagerLayoutInfoState` 按测量顺序竞争。结果：拖动主 pager 时滚动增量作用在 accessory 的测量结果上，主 pager 自己永远不被重测——内容不跟手、也不落位。两种状态（Compact/Expanded）共用这同一个 pager，所以**两处全坏**。复核 agent 逐条核对了 foundation 1.11.0-beta02 源码（`PagerState.kt:515-523` 无多重附着保护、`performScroll` 快慢路径、`PagerMeasurePolicy.kt:170` 的 `withoutReadObservation` 使主 pager 无法被失效重测），并排除了未提交的 `detectTapGestures` 改写（只消费 down/up，不碰 move）。**回归源 = 9f08a6d0，与未提交改动无关。**

---

## 3. Pixel 那个动画到底是什么

**官方身份**：predictive back 系统动画家族中的 **cross-activity** 变体。渲染方是 SystemUI/WindowManager Shell（直接操作 SurfaceControl leash），不是 app 进程：

- 主实现：`frameworks/base/libs/WindowManager/Shell/src/com/android/wm/shell/back/DefaultCrossActivityBackAnimation.kt`（基类 `CrossActivityBackAnimation.kt`，编排 `BackAnimationController.java`）；
- 回桌面的变体在 Launcher3 quickstep（`LauncherBackAnimationController.java`，缩得更小、缩向应用图标，所以观感略不同）；
- progress 预平滑：`android.window.BackProgressAnimator`（刚度 1500、临界阻尼的 spring 追手指）。

**为什么 Pixel 上不是处处可见**：系统只动画 back-to-home / cross-activity / cross-task 三种边界。单 Activity 内部导航（Fragment/Compose）**系统不管，必须 app 自绘**；且任何以默认优先级拦截 back 的 `OnBackPressedCallback` 都会压制系统动画。Yoin 是单 Activity Compose 应用 → 全部要自己实现，这正是本方案 Phase 3。

**AOSP 参数表**（实现时的对标数值）：

| 属性 | 值 | 出处 |
|---|---|---|
| 关闭页最小缩放 | **0.9**（`MAX_SCALE`） | CrossActivityBackAnimation.kt |
| 关闭页圆角 | 设备物理窗口圆角（~28-32dp 替代值） | `setCornerRadius(leash, …)` |
| 屏幕边距 | 8dp（`cross_task_back_vertical_margin`） | WM Shell dimen.xml |
| 入场页起始 | 偏移 96dp、同步 0.9→1.0 | `cross_activity_back_entering_start_offset` |
| 入场页 scrim | 黑色 alpha 0.2（浅色主题）/ 0.8（深色） | CrossActivityBackAnimation.kt |
| 手势进度曲线 | `PathInterpolator(0.1, 0.1, 0, 1)` | BackGestureInterpolator |
| Y 跟手 | DecelerateInterpolator，钳制不越 8dp 边距 | `getYOffset()` |
| commit 后 settle | 450ms EMPHASIZED + 速度种子 spring(刚度 200, 阻尼 0.75) FLING_BOUNCE | DefaultCrossActivityBackAnimation.kt |
| 关闭页淡出 | settle 前 20% 内完成（`alpha = max(1 − p·5, 0)`） | 同上 |
| M3 设计规范 | 缩放下限 90%；x-shift = (宽/20 − 8)dp；内容 fade-through 在 35% 进度 | developer.android.com predictive-back 设计页 |

---

## 4. 重构方案（四个阶段，可独立合入）

### Phase 0 — 修横滑（最高优先级，纯 bug fix）

**改动**：accessory 条不再共享主 `pagerState`。二选一：

- **方案 A（推荐，保留联动视觉）**：accessory 用自己的 `rememberPagerState`，由主 pager 驱动镜像：

```kotlin
val accessoryPagerState = rememberPagerState(initialPage = detailPage.ordinal, pageCount = { 3 })
LaunchedEffect(pagerState) {
    snapshotFlow { pagerState.currentPage + pagerState.currentPageOffsetFraction }
        .collect { accessoryPagerState.scrollToPage(it.toInt(), it - it.toInt().toFloat()) }
}
```

- **方案 B（更简单）**：accessory pager 整个换成 `AnimatedContent(targetState = detailPage)`，用既有的「反向位移交叉淡化」词汇（原则 #4）。失去拖动中途的连续联动，但代码最少。

accessory 本来就 `userScrollEnabled = false`，镜像是单向数据流，无回流风险。

**验收**：Compact 与 Expanded 下均可横滑切三个 pane；Immersive 保持禁滑（`userScrollEnabled = stageMode != Immersive` 不变）；accessory 条跟随翻页。

### Phase 1 — 修锯齿

按性价比顺序（通常 1+2 就够）：

1. **修图片采样**：`ExpressiveMediaArtwork` 的 `AsyncImage` 显式传 `filterQuality = FilterQuality.Medium`（mipmap 缩小），并给 `ImageRequest` 固定 `size(startSizePx)`，保证整个飞行过程用同一张按大端解析的位图。
2. **恢复发丝线**：`CoverTransitionOverlay` 内的 `PlainAlbumCover` 恢复 1dp 低透明度描边（或 `Modifier.border(1.dp, surfaceColor.copy(alpha = …), shape)`），遮住内容与 clip 的接缝——这是系统级动画也在用的经典手法。
3. **回滚 alpha² ghosting**：compact `AlbumCover`（NowPlayingScreen.kt:722-726）的 alpha 改回二值门控（或层内 `compositingStrategy = CompositingStrategy.ModulateAlpha`），避免与父层 `compactProgress` 双重淡出。
4. **守则（防回归）**：动画中的圆角矩形要么**动真实尺寸**（现状，保持）、要么 scale 与 `clip=true; shape=…` 写进**同一个** `graphicsLayer` 并反向缩放圆角半径（`shape = RoundedCornerShape(visualRadiusPx / scale)`）；动画期间避免 fractional alpha / RenderEffect 触发隐式离屏（Auto 策略下离屏缓冲「按位图缩放」正是毛刺来源）。

**验收**：开发者选项动画时长 10× 慢放展开/收起，边缘无阶梯、无闪烁。

### Phase 2 — Lyrics 预测性返回的「灵动感」

把系统动画的完整管线搬进 Expanded handler，四件套：

1. **输入缓动**：`val eased = CubicBezierEasing(0.1f, 0.1f, 0f, 1f).transform(event.progress)`——前几厘米响应积极、后段饱和，立刻摆脱「拖直尺」感。
2. **预览上限（关键设计决策）**：手势期间不再 scrub 整个 reshape，只走前 35–45%：`target = 1f - PreviewMaxFraction * eased`。这正是 Pixel 的「预览 + 提交后完成」模型——手指控制的是一个有限承诺的预览，松手后 spring 完成剩余行程，灵动感主要来自这里。
3. **spring 追踪**：`detail` 升级为 `Animatable`（`NowPlayingStageProgress` 内部替换实现、对外 API 不变：`snapDetail→snapTo`、`animateDetailTo→animateTo`），手势事件改为重定目标而非直写：

```kotlin
// inside PredictiveBackHandler block
var chase: Job? = null
progress.collect { event ->
    val eased = BackGestureEasing.transform(event.progress)
    val target = 1f - PreviewMaxFraction * eased
    chase?.cancel()
    chase = launch { stageProgress.animateDetailTo(target, spring(Spring.DampingRatioNoBouncy, 350f)) }
}
chase?.cancel()  // Animatable 取消时保留当前值与速度
nowPlayingViewModel.stepBackStage()
stageProgress.animateDetailTo(0f, YoinMotion.predictiveBackSettleSpring())  // 初速度=追踪残余速度，免费获得连续性
```

   追踪刚度 200–400 区间手感最自然（<150 发飘）；追踪期间不弹（NoBouncy），弹性只留给释放 settle（现有 0.75/520 不动）——与 AOSP 的分工一致。
4. **取消路径对称**：cancel 时同样带速度 settle 回 1f。

加分项（可后做）：corner radius / 封面位移给独立的更快曲线（M3 的多速率纵深），但预览上限 + 追踪已解决主要手感问题。

**注意**：Compact 收起的 handler（chase 模式）和拖拽 dismiss（1:1 + 速度阈值）是你满意的现状，**不动**。记忆库中验证过的 drag-reveal 模式（1:1 拖拽 + 非对称 rubber-band + 速度感知 settle）适用于**持续接触的拖拽**；predictive back 是系统代理的手势，缺少直接操纵感，所以反而需要追踪与缓动来补「生命感」——两者不矛盾。

### Phase 3 — 全局统一 Pixel 风格导航转场

现状盘点（代码分析确认）：

- 四个 push 路由（Settings/AlbumDetail/ArtistDetail/PlaylistDetail）已统一走 `Motion.kt:410-431` 的 `simplePush*` 定义——**天然的单点修改入口**；
- 现有 pop 已是「缩小+淡出、底层淡入」，但 pivot 在**左缘**（`TransformOrigin(0f, 0.5f)`）、缩到 0.70 过深、push 时底层完全不动；
- **手势预览完全失效**：每个 push 页包着 `YoinBackSurface`，其内部无条件注册的普通 `BackHandler`（[YoinBackSurface.kt:43](../app/src/main/java/com/gpo/yoin/ui/navigation/back/YoinBackSurface.kt)）在 OnBackPressedDispatcher 的 LIFO 中压过 NavDisplay，手势直接瞬时 commit；`BackSurfaceController` 里那套 scale 0.70 + 32dp 圆角的手势管线（`updateFromSystemBack`/`pushPageTransform`）是**零调用的死代码**；
- Nav3 的 `predictivePopTransitionSpec` 是 seekable 的：NavDisplay 内部用 `SeekableTransitionState.seekTo(progress)` 跟手指、commit/cancel 自动补完剩余段，zIndex 也自动保证 pop 时出场页在上——**只要不被截胡，跟手预览是免费的**。

改动清单：

1. **解除截胡**：`BackSurfaceKind.PushPage` 不再注册 `BackHandler`，让 NavDisplay 的 onBack + `PredictivePopTransitionKey` 接管手势与按钮返回；保留 `requestBack`/`commitImmediately` 给 toolbar 返回箭头用。随后**删除** `pushPageTransform`、`BackSurfaceController` 手势管线、`BackProgressNormalizer`（避免日后有人接上造成双重变换）。
2. **重定义统一转场**（单点：Motion.kt 的四个 `simplePush*` vals；四个路由的 metadata 块自动吃到）：

```kotlin
// pop / predictive pop —— Pixel cross-activity 对标
val pixelPopExit: ExitTransition =      // 出场页：缩向中心 + 快速淡出（AOSP 在 settle 前 20% 淡完）
    scaleOut(motionScheme.defaultSpatialSpec(), targetScale = 0.9f, transformOrigin = TransformOrigin.Center) +
    fadeOut(motionScheme.fastEffectsSpec())
val pixelPopEnter: EnterTransition =    // 底层页：从 0.95 浮现（替代 AOSP 的 96dp 视差）
    fadeIn(motionScheme.defaultEffectsSpec()) +
    scaleIn(motionScheme.defaultSpatialSpec(), initialScale = 0.95f)

// push —— pop 的精确镜像，保证前进/后退视觉可逆
val pixelPushEnter: EnterTransition =
    fadeIn(motionScheme.defaultEffectsSpec()) +
    scaleIn(motionScheme.defaultSpatialSpec(), initialScale = 0.9f, transformOrigin = TransformOrigin.Center)
val pixelPushExit: ExitTransition =     // 旧页退后形成纵深（现状是 None，旧页纹丝不动）
    scaleOut(motionScheme.defaultSpatialSpec(), targetScale = 0.95f)
```

   要点：**透明度一律 effects spring（临界阻尼）**，spatial spring 会过冲、alpha>1 钳制很难看；`predictivePopTransitionSpec` 的 lambda 能拿到 swipeEdge，可选做 edge-aware pivot（左缘滑 `TransformOrigin(0.4f, 0.5f)`，右缘 0.6——AOSP 行为）。
3. **圆角与 scrim**（`ContentTransform` 表达不了 clip，挂到页面根上，自动跟手势 seek）：

```kotlin
@Composable
fun Modifier.navTransitionSurface(maxRadius: Dp = 28.dp): Modifier {
    val scope = LocalNavAnimatedContentScope.current
    val radius by scope.transition.animateDp(label = "navCorners") { state ->
        if (state == EnterExitState.Visible) 0.dp else maxRadius
    }
    return graphicsLayer {
        val r = radius.toPx()
        if (r > 0f) { clip = true; shape = RoundedCornerShape(r) }
    }
}
```

   同一 pattern 用 `animateFloat` 可在底层页加 scrim（深色主题 0.8 上限）。注意 Phase 1 守则：clip 与 scale 在同一层、半径均匀——这个 modifier 与 ContentTransform 的 scale 分属两层时，把它合进每页根部唯一的 graphicsLayer。
4. **升级 `nav3Core = "1.1.2"`**（binary-compatible patch）：修掉 1.1.0 的 OverlayScene `LocalNavAnimatedContentScope` 崩溃与 Studio 预览中 predictive back 失效两个坑。
5. **保持不破**：`sharedTransitionScope` 继续传给 NavDisplay；enter/exit 必须保持非零时长（`rememberActiveOnlySharedContentConfig` 依赖过渡窗口存在），Home→Detail 封面 shared element 与新转场可共存（shared element 走自己的 boundsTransform，独立于容器变换）。

已知取舍：NP overlay 展开时 push Settings，整个 Shell（含 NP）一起缩向中心——现有注释标注过 by design，中心缩放会让这一点更明显，验证阶段确认观感。

### Phase 4（可选）— 收尾统一

- ~~`BackMotionTokens.NowPlayingCornerRadius = 28.dp` 已声明未用~~ **作废**：该 token 已随 d7186402 的 NP reshape 一并删除，全仓零引用；现存的是 `PopPageCornerRadius`，正被 `DetailPredictiveBackCollapse` 使用。“同一种语言”的前提也不再成立——detail 页缩到 0.9 且内缩 8dp、真正脱离屏幕边缘，所以必须收圆角；而 NP 的 compact dismiss 是 scale 1 的纯下移，Expanded collapse 只缩内容不缩 overlay（`contentScale`，见 `NowPlayingOverlayHost` 的 “NOT the whole overlay” 注释），满幅贴边的表面收圆角只有顶部两角可见。真想要这个观感，得先改 NP 的退场几何，而不是复活一个 token；
- ~~删除 Motion.kt 中确认无引用的遗留转场 vals（`navEnterForward/navExitForward/navEnterBack/navExitBack/navEnterOverlay/navExitOverlay/albumDetailSharedEnter/PopExit`）~~ **已完成**：2026-07-25 复核，八个名字在 `app/src` 全树零命中——连声明本身都已不在；
- ~~`motion-audit-matrix.md` 若仍在维护，补登本次变更~~ **已完成**：2026-07-25 补登了预测性返回（detail 页内自绘的 collapse、shell 的 entering 侧）与底栏 morph 相关条目。

---

## 5. 风险与兼容性

| 风险 | 缓解 |
|---|---|
| 解除 BackHandler 截胡后，back 语义变化（手势 cancel、连续 back） | Nav3 内部 SeekableTransitionState 处理 commit/cancel/中断；重点回归测试见 §6 |
| Phase 2 改 `NowPlayingStageProgress` 内部实现 | 对外 API 不变；`isGestureDriving` 守卫逻辑保持原样 |
| 预览上限值（0.35–0.45）与追踪刚度（200–400）需要调感觉 | 做成 `BackMotionTokens` 常量，真机上扫参 |
| nav3 1.1.0→1.1.2 升级 | 官方 patch 版本，release notes 无行为变更条目 |
| accessory pager 镜像的滚动同步丢帧 | `snapshotFlow` 在 frame 边界发射；若可感知则退回方案 B（AnimatedContent） |

## 6. 验证清单

> **2026-07-25 状态**：以下全部是真机手动回归项，至今无人跑过，故一律保持未勾选。预测性返回改成 Activity 内自绘、底栏改成跨窗口 morph 之后，这一轮要重新完整跑一遍才算数。

- [ ] Compact / Expanded 下横滑切换 Lyrics↔About↔Note；Immersive 禁滑；accessory 条联动
- [ ] 动画时长 10×：lyrics 展开/收起边缘无锯齿、无双影
- [ ] Expanded 手势返回：跟手有「呼吸感」、松手带速度完成、cancel 弹回顺滑；按钮返回不回归
- [ ] Compact 手势 dismiss / 拖拽 dismiss 与现状一致（不应受影响）
- [ ] 四个 push 路由：push 旧页退后、pop 手势预览跟手、cancel 复原、3-button 长按预览（API 36）
- [ ] Home→AlbumDetail 封面 shared element、mini-player→NP sharedBounds 完好
- [ ] NP 展开状态下 push Settings 的整体缩放观感
- [ ] 低端机 profile：`MotionProfile.AdaptiveReduced` 下转场仍完整（只降装饰）

## 7. 主要资料源

- AOSP：`CrossActivityBackAnimation.kt` / `DefaultCrossActivityBackAnimation.kt` / `BackProgressAnimator.java` / `BackGestureInterpolator.java`（cs.android.com, frameworks/base）
- 设计规范：developer.android.com — Predictive back design guidance（90% scale、8dp margin、(宽/20−8)dp shift、35% fade-through）
- Nav3：developer.android.com/guide/navigation/navigation-3/animate-destinations；androidx release notes（1.1.x 已知问题）；github.com/android/nav3-recipes
- 渲染：developer.android.com Compose graphics modifiers（CompositingStrategy 语义）；HWUI `RenderNodeDrawable.cpp`（outline clip AA）；SurfaceFlinger `SkiaRenderEngine.cpp`（系统动画为何无毛刺）
- 手感参考实现：material3 `SearchBar`/`ModalBottomSheet` predictive back 源码；compose-samples JetLagged（VelocityTracker 模式）
