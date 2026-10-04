# Yoin Home 现状到编辑态挂接点的映射（HEAD `93b689bb`，只读，未改任何文件）

路径默认相对 `app/src/main/java/com/gpo/yoin/`。缩写对照：

| 缩写 | 文件 |
|---|---|
| HEC | `ui/home/HomeEditorialContent.kt` |
| HS | `ui/home/HomeScreen.kt` |
| HWG | `ui/home/HomeWidgetGrid.kt` |
| HWGA | `ui/home/HomeWidgetGridAdaptive.kt` |
| HJT | `ui/home/HomeJbiTemplate.kt` |
| HFD | `ui/home/HomeFeedDensity.kt` |
| HFF | `ui/home/HomeFeedFrame.kt` |
| FU | `ui/experience/FeedUnits.kt` |
| HMB | `ui/home/HomeMemoryBubble.kt` |
| HMP | `ui/home/HomeMemoryPill.kt` |
| HVM | `ui/home/HomeViewModel.kt` |
| NH | `ui/navigation/YoinNavHost.kt` |

## 1. 现在的渲染树

**调用链**
1. NH:834-879：`ExpressivePageBackground { HomeScreen(...) }`。modifier 是 `.fillMaxSize().then(edgeContentPadding)`（NH:876-878），所以横屏手机（EdgeSplit）时整个 Home 盒子已经内缩，胶囊带不在 Home 的盒子里。
2. HS:61-122 `HomeScreen`，进入 HS:126-291 `HomeContent`：
   - `ProvideYoinMotionRole(Expressive)`（HS:158）→ `ExpressivePageBackground`（HS:162）→ `when(uiState)`；
   - Content 分支：Box 的 graphicsLayer 做首次进场的 alpha/位移（HS:230-237）；
   - 里面是 `AnimatedContent(targetState = isEditMode)`（HS:238-285），在 `HomeLayoutEditor`（HS:253）和 `HomeEditorialContent`（HS:260-283）之间切换。

**`HomeEditorialContent`（HEC:159-551）里的状态**

| 状态 | 位置 | 备注 |
|---|---|---|
| `listState` | :191 | 普通 remember，不是 saveable |
| `containerHeightPx` | :193 | |
| `pullToMemoriesConnection` | :200-238 | |
| `firstReveal = rememberStagedReveal("home-feed")` | :296-303 | 内部是 rememberSaveable 的 `played`，StagedReveal.kt:53-71 |
| `paneWidthInMotion` | :306 | |
| `windowInfo` / `isLandscapePhone` | :315-316 | |
| `feedFrame = rememberHomeFeedFrame(feedFrameClass(windowInfo))` | :322 | |
| `seamFlow`、`seamBackground`、`pageColor`、状态栏高度 | :327-334 | |
| `bubbleController` | :337-338 | 只在 Bubble 变体下非 null |

**根 Box（HEC:339-348）**

modifier 链：`fillMaxSize → watchMemoryBubbleTouches(bubbleController) → seamTide(seamFlow, pageColor, statusBarPx){ listState.seamScrolledPx() }`。

- `seamTide` 现在在 `ui/component/SeamTide.kt:41-46`。spec 说它在 SeamDissolve.kt，已过时。
- 子节点 1：LazyColumn（HEC:349-534）。
  - modifier 依次是：`fillMaxSize`、`feedFrameWidth(feedFrame)`（在 layout 阶段写入实时容器宽，HFF:124-128）、`onSizeChanged`、`seamDissolveViewport(top = SeamTop.FadeText, topInset = statusBarTop + TideRest)`（:355-361）、`nestedScroll(pullToMemoriesConnection)`（:362）、`pointerInput(Unit){ detectTapGestures(onLongPress) }`（:363-373）。
  - `contentPadding = FeedFramePadding(frame, top 4dp, bottom 108dp+navBar)`（:376-382），横屏手机底部是 16dp+navBar。左右两边在 measure 阶段读 `frame.start/end`（HFF:135-155）。
  - 行距 `spacedBy(18dp)`，横屏手机 10dp（:383）。
- 子节点 2：`MemoryBubbleOverlay`（HEC:535-549）。`matchParentSize`，叠在列表上面，自己不接 pointer input（HMB:518-600）。

**列表项与顺序**

| key | 位置 | 何时出现 |
|---|---|---|
| `"home-header"` | HEC:387-404 | 始终（没有 animateItem） |
| `"section-activities"` | HEC:411-476 | 启用就一定出现；没有活动时显示 `HomeEmptyCard`（:464-474） |
| `"section-widget-grid"` | HEC:480-500 | 只有 `widgetGrid` 非空才出现 |
| `"section-recently-added"` | HEC:504-531 | 曲目和专辑都为空时不出现 |

- 区块按 `for (sectionState in sections)` 循环发出（:408-533），未启用的在 :409 `continue`，再按 :410 的 `when` 分发。
- 顺序来自 HVM:71-81 的 `homeLayout` StateFlow（`activeProfileId.flatMapLatest → HomeLayoutStore.layoutFlow → HomeLayout::reconcile`），经 HS:85/:98 → HS:128 → HEC:171 传下来。禁用项保留原位。
- **现在没有页尾项**，也没有「全部隐藏」时的空状态（只剩 header）。
- 每个区块的 animateItem 写法一样：`placementSpec = if (paneWidthInMotion.value || feedFrame.isBlending) null else spatialSpring()`，位置在 :452-456、:469-473、:489-493、:520-524。之后接 `.stagedBeat(hero|meta|payload)`（:457-461、:494-497、:525-528）。

**Header：`HomeContentHeader`（HEC:553-623）**

结构是 `Row(statusBarsPadding + top 8dp)`（:571-576）：
1. 标题 "Home" 的 Text（:580-587）：headlineLarge，横屏手机 headlineMedium（:577）；`padding(end = 24dp)`（`HomeHeaderTitleBreathing`，HMP:117）；`seamFade(fontSize)`。
2. 生产默认是 Bubble 变体（`HomeHintVariant.Recommended = Bubble`，HomeHintVariant.kt:64、:78）。这时中间是 `Spacer(weight 1f).memoryBubbleFreeSpan(controller)`（:588-590），作用只是把标题和齿轮之间的空隙以窗口坐标上报给 overlay（HMB:302-311）。
3. HeaderPill / HeaderChevron 两个变体只在 debug 用：`Box(weight 1f){ HomeMemoryEntry }`（:591-607 → HMP:279-312）。
4. 2dp Spacer（:608）；设置齿轮 `IconButton`（:609-621，点按带 `performContextClick`）。header 行高由这个 48dp 按钮撑起。

**记忆箭头和气泡**
- 箭头的触控区 48dp，位于 cutout 下沿（或状态栏）+2dp 处；x 取 cutout 下方、页面中线或空隙内（HMB:324-338、:543-600）。
- 气泡从箭头处往右长，高 28+54+4dp。
- 点按全部由根 Box 的 `watchMemoryBubbleTouches` 在 Initial pass 判定（HMB:258-299）：按点落在箭头或正在说话的气泡上时，`down.consume()`，抬起时触发点按；否则只发 `touches`（收起气泡、重置空闲计时）。
- `covered = homeCovered`（HEC:543）决定 `canSpeak`（HMB:432-434）。

**下拉进 Memories（HEC:200-238）**
- 只处理 `UserInput`。在顶部往下拉（`isAtTop`，HEC:1703），或者 fraction<1 时往上推，就走 `memoriesRevealState.dragBy`。
- `onPreFling` 负责 settle，越过阈值时调 `onCommitMemoriesReveal`。

**Seam**
- `seamScrolledPx` 在 `firstVisibleItemIndex > 0` 时返回 +∞（SeamDissolve.kt:614-615）。
- `seamRemainingPx`：SeamDissolve.kt:618-624。
- 封面用 `seamDissolve()`（:638），文字用 `seamFade()`（:646）。
- `SeamFlow`（:317-337）还没有 `hold` 接口。
- Reduced 判断在 SeamDissolve.kt:560-561。
- 底栏矩形可以从 `LocalSeamBarField.bounds` 拿到（root px，`ui/component/SeamBarField.kt:45-93`），可用作安全区下沿和交界带衰减的依据。

**各宽度档的页边与密度**（FU:138-154、HFF:51-91，页边在 layout 阶段实时读取）

| 窗口 | FeedFrameClass | 页边 start/end | 内容宽 C | feedUnits N（FU:68-80） | 封面列数 K（FU:106-110） | Activities | JBI |
|---|---|---|---|---|---|---|---|
| 手机 360–412 | Capped | 16/16 | W−32 | 2 | 3 | Phone 配方，4 卡 | 3 列 Row，12 格 |
| 容器 <342（折叠屏 NP 旁） | Capped | 16/16 | <310 | 1 | 3 | PhoneNarrow | 3 列 Row |
| 横屏手机（EdgeSplit） | Landscape | 24/24 | W−48 | 任意 | — | Landscape，一行 3 卡 | 6 列 Row |
| Pixel Tablet 竖屏 800 | Capped | **56/56** | 688 | 4 | 5 | Units N=4，[2,2]/[1,2,1]/2 条 = 7 卡 | 模板 5 列 × 3 行 |
| shell 600（开着详情列） | Capped | 16/16 | 568 | 4 | 4 | Units N=4 | 模板 4×3 |
| Pixel Tablet 横屏 1280 | Wide | 32/32 | 1216 | 8 | 9 | Units N=8，13 卡 | 模板 9×2 |

- Capped 的 start = 16 + max(0, (min(W,840) − 720)/2)。
- Tabletop 用 CappedCentred。
- 宽度档切换时页边沿弹簧过渡，期间 `feedFrame.isBlending` 为真，placementSpec 置 null。

## 2. 卡片与点按修饰符

整个 `ui/home` 里**没有 `combinedClickable`**。所有点按都是 `noRippleClickable`，即 `clickable(indication = null, enabled)`（PressFeedback.kt:30-41，已经带 `enabled` 参数）。

| 卡片 | 定义 | 根 modifier（加轻摆的位置） | 点按 | 按压反馈 |
|---|---|---|---|---|
| ActivityHeroCard | HEC:999-1074 | Surface `modifier.elasticPress.seamDissolve` :1012-1014 | :1023 内层 Row | 卡 0.97 缩放；封面 `WidgetBackdropArtwork(is)` :1028 带 Bun→Triangle 形变和封面缩放（HWG:626-653） |
| ActivitySmallCard | HEC:1076-1150 | :1088-1090 | :1098 | 封面 :1109 |
| ActivityWideCard | HEC:1152-1216 | :1164-1166 | :1174 | 封面 :1179 |
| ActivityStripCard | HEC:1218-1277 | :1242-1244 | :1253 | 无封面 |
| HomeEmptyCard | HEC:1523-1553 | :1530 | 不可点 | — |
| WidgetCard12（JBI 信号卡） | HWG:347-427 | `cardModifier` :368 | :369-372，带 `performContextClick`（§2.8 要删） | 内部的 `WidgetCoverBlock` 不传 onClick，但共用同一个 interactionSource |
| WidgetCoverBlock（JBI 封面） | HWG:501-541 | Column :527 | :513-520，只在 onClick 非 null 时挂；`performContextClick` 在 :515（要删） | 同上 |
| RA 曲目格 RecentlyAddedTrackTile | HEC:1418-1475 | Row :1428-1431 | :1430 | :1431 |
| RA 专辑卡 RecentlyAddedAlbumCard | HEC:1477-1521 | Column :1487-1491 | :1490 | :1491，封面 :1493 |
| 设置齿轮 | HEC:609-621 | — | IconButton | — |
| 记忆胶囊（只在 debug 变体） | HMP:430-441 | — | :437 | — |
| 记忆箭头 / 气泡 | HMB:604-645、:648-755 | — | 没有 clickable，由根 Box 分发；语义上的 onClick 在 HMB:615-626、:684-695 | — |

**调用点**
- Activities：
  - 横屏一行：HEC:700-740。
  - 手机：`ActivityBentoPhone`（HEC:773-850），hero 在 :784，第二行 :796-839，条状卡 :841。
  - Units：`ActivityUnitGrid`（HEC:860-955），每张卡一个 Layout 节点，按 `entry.stableId` 作 key（:880），measure 在 :908-954。
  - 三者都包在 `AnimatedContent(contentKey = spec)`、`springHeight` 和 `heightOfIncomingOnly` 里（HEC:677-763）。
- JBI：模板路径 HWG:197-216，列网格 :221-243，手机 Row :246-282；标题在 :163，标题到网格的间距 16dp（:159-162）。
- RA：LazyRow 在 HEC:1333-1375。第一项是 2×2 曲目格（:1345-1362，4 个格子在 :1380-1416），后面是专辑项，key 为 `recently-added-album:<id>`（:1364）。货架用 `ignoreParentHorizontalPadding(start={frame.start}, end={frame.end})`（:1337）出血到容器边缘，`contentPadding = FeedFrameSidePadding`（:1332/:1338）。按 owner 2026-07-18 的裁决，**这里没有边缘渐隐**（:1327-1330）。

**`interactive` 标志和卡片级轻摆怎么接**
- 卡片函数都是 private，而且嵌了 3–4 层（AnimatedContent → Phone / Units / Landscape → 卡；JBI 是 AnimatedContent → `JbiSpanGrid` 的 cell → 卡）。逐层加参数会动大约 15 个签名。建议由块包装器提供一个 CompositionLocal（例如 `LocalHomeEditCard`，含 `interactive`、`sectionId` 和轻摆 modifier 工厂）。
- `enabled = interactive` 要加在：HEC:1023、:1098、:1174、:1253、:1430、:1490，HWG:369、:514。
- 轻摆 modifier 放在每张卡根链的**最外层**，在 `elasticPress` 之前，这样画布旋转能包住缩放、形状和阴影：HEC:1012、:1088、:1164、:1242、:1428、:1487，HWG:368（整张 1×2）、HWG:527（只在独立封面时加；嵌在 1×2 里的封面块不要加，否则会转两次）。
  - `JbiSpanGrid` 和 `JbiColumnGrid` 要求每个 cell 只发一个 layout 节点（HWGA:102、:179）。加 modifier 不会破坏这一点。
- 交替方向用的卡片序号：
  - Activities：手机 hero=0、小卡=1、宽卡=2、条状卡=3；Units 用 `placed` 里的下标（HEC:873-876）。
  - JBI：模板路径用 `layout.cells` 的下标（阅读顺序，按 row+offset 再按 column，HJT:227-229）；其他两条路径按行展开。
  - RA：曲目格 0–3，专辑接着按 LazyRow 下标。
- 振幅公式要改：spec 和原型的 `A_c = clamp(1.1°×100/w, 0.35°, 1.1°)` 只看宽度（proto.js:869-870），而原型里的 JBI 只有手机 3 列（proto.js:51），没有立式卡。现在模板路径的 **TallSignal** 宽 1 列（100–128dp）、高至少 2 行 + 16dp（约 330dp 以上），按宽度会拿到 1.1°，上下角会移动约 3dp，违背「外缘位移约 1dp」。建议 TallSignal 按半对角线算。其他卡算下来都在约 1dp 以内。

**JBI 模板的几何**（N≥3 且不是矮窗口时启用，HFD:296-305）
- **列与行**：列数 K = `feedCoverColumns` = ⌊(C+12)/124⌋，取值 3..10；行数 R = 4（K≤3）/ 3（K 为 4–5）/ 2（K≥6）（HFD:324-328）。
- **三种块**（HJT:27-34）：
  - Cover 1×1；
  - TallSignal 1×2，立式：封面在上、文字在下，评语最多 3 行；
  - WideSignal 2×1，卧式：封面在左、文字在右，占两列但算一条 lane。
  - 最多 2 张信号卡（取 `expanded` 的卡）。立还是卧由种子决定（立式要求至少 2 行）。摆放时优先让两张信号卡相隔至少 2 条 lane，其次让剩下的 run 尽量多（HJT:277-307）。
- **lane、run 与错位**：
  - lane 是一列，或一张卧式卡的两列；
  - 立式卡会把左右邻居并进同一个 run（HJT:251-270）；
  - 每个 run 有一个以行距为单位的偏移：第 0 个 run 为 0；卧式卡所在 run 为 0，它右边的 run 必须 +0.5；其余在 {0, ¼, ½} 里漂移，不能和左邻相同，也不能和必须的右邻相同（HJT:200-216、:315-330）。
- **测量**（`JbiSpanGrid`，HWGA:181-254）：
  - pitchX 由宽度、K 和 12dp 间距算出；
  - rowHeight = 非立式块里最高的那块；立式块最低高度 = 2×rowHeight + 16dp；
  - pitchY = max(rowHeight+16, (立式块最高 + 16)/2)；
  - 每块的 y = (row + offset[col]) × pitchY；区块高度 = 所有块 max(y+h)。
  - 结论：**JBI 的高度随种子和评语长度变化，底边参差不齐**（各 lane 结束在不同高度）。
- **所有封面一样大**：都等于 `jbiCoverSide(列宽, Capped128)` = clamp(0.84×列宽, 100, 128)。PhoneRhythm 在模板路径里会被强制成 Capped128（HWG:195）。立式卡封面用 `jbiCoverSquare`，卧式卡封面用 `JbiWideCardLayout`（HWGA:263-300），尺寸相同。
- **种子与缓存**：种子取第一张封面的 stableId（HJT:333-334），所以同一批内容永远同样排布；`jbiLayout` 有容量 8 的缓存（HJT:77-94）。封面不够时逐行减少，减到 null 就退回 `JbiColumnGrid` + `packWidgetRows`（HJT:111-126）。
- **两次切换的过渡**：换模板或换路径时，AnimatedContent 用 `contentKey = template ?: spec` 交叉淡化，**没有 SizeTransform**（`using null`），高度由 `springHeight` 缓动（HWG:166-182、HFF:191-262）。

## 3. 现在的长按进编辑器，以及就地编辑要删改的东西

**现在的流程**
1. LazyColumn 上的 `detectTapGestures(onLongPress)`（HEC:363-373）触发 `performLongPress()`，再调 `onEnterEditModeState`（:263、参数 :174-175）。
2. 经 HS:271 → `HomeContent.onEnterEditMode` → HS:100 设 `isEditMode = true`。`isEditMode` 是 rememberSaveable（HS:86）。
3. 换 profile 时退出编辑：HS:91-93，`activeProfileId.drop(1)`。
4. 返回键：`BackHandler(enabled = isEditMode && !suppressBackHandling)`（HS:94）。
5. HS:238-285 的 `AnimatedContent` 把整个 HEC 换成 `HomeLayoutEditor`（Expressive 的 fadeIn + scaleIn 0.98 / fadeOut）。

**为什么出问题**（spec UH §3 的 1–5 号，下面是从代码推出的原因，未上机复现）
- AnimatedContent 会销毁 HEC：`listState` 不是 saveable，滚动回到顶部；`rememberStagedReveal` 的 saveable 没有 SaveableStateHolder 保护，入场 stagger 重播；`bubbleController` 被重建。
- 编辑器写死 16dp 页边（`HomeLayoutEditor.kt:119`），平板上 feed 是 56dp，所以宽度跳变。
- 空的 JBI / RA 在 feed 里根本没有 item，长按不到。
- 长按卡片会被吞：`detectTapGestures` 挂在 LazyColumn（祖先）上，在 Main pass 才拿到事件，此时卡片的 ClickableNode 已经消费了 down，而它的 `awaitFirstDown` 要求未被消费。所以现在只有缝隙、页边和 header 能进编辑。

**要删或改的**
1. HEC:363-373 的检测器和注释；import :22（`detectTapGestures`）；`onEnterEditMode` 参数（:174-175、:263）。
2. HS 里的 `isEditMode`（:86）、换 profile 的 `LaunchedEffect`（:87-93，连同 `drop` import :54）、`BackHandler`（:94、import :3）、`HomeContent` 的四个编辑参数（:129-132）。HS:238-285 的 AnimatedContent 换成直接调用 HEC（连带 import :4、:9）。HS:230-237 的进场 Box 保留。
3. HS:70 的 `suppressBackHandling` **不能直接删**：它同时作为 `homeCovered` 往下传（HS:115 → HEC:170 → 气泡的 `covered`，HEC:543）。改名为 `homeCovered` 保留。NH:842-843 的表达式照旧，传给 `homeCovered`。
4. 整个删除 `HomeLayoutEditor.kt`（328 行，唯一调用方是 HS:253）。要移植进签条层的不变式：

   | 不变式 | 行号 |
   |---|---|
   | draft 是唯一事实来源 | :78-82 |
   | 每次改动都落盘 | :95 |
   | `settleDrag`：snapTo 残差，再 animateTo(0, v) | :97-112 |
   | pointerInput 按 section id 作 key | :181-184 |
   | 只允许一根手指拖 | :189-195 |
   | 原地接住 | :196-206 |
   | 被拒绝的手势也要 consume | :209-214 |
   | 速度按累积位移算 | :216-220 |
   | 实时交换：`dragOffset -= shift`，邻居 snapTo(value+shift) 再 animateTo(0) | :225-249 |
   | 只有拥有拖动的手势能 settle | :251-261 |

   旧编辑器每换一格用 `performTick`（:247），起拖用 `performContextClick`（:207）。新实现改为 `performSegmentTick` / `performDragStart`。
5. NH 侧：
   - resolver 加 `HomeEdit`：ShellBackResolver.kt:6-12、:23-36，现在的顺序是 NP > DetailPane > Memories；调用在 NH:441-446。
   - NH:746-755 的 `when(homeSurface)` 补 Edit 分支。HomeSurface 加常量后这里会编译失败，所以不会漏。
   - NH:757-761 的 `LaunchedEffect(selectedSection)` 里加 snapExit。
   - NH:422-434 的 `musicConfigurationRevision`（换 profile）里加 snapExit，替代 HS:91-93。
   - 逐个核对 `dismissMemoriesIfActive`（NH:510-514）的调用点。
   - 新的 `BackHandler` 挂在 HomeScreen 调用（:835-879）之后、`if (memoriesMounted)`（:881）之外。
   - `ShellDetailOrigin.kt:10` 读了 `homeSurface == Memories`，不用改，但要知道它在。
6. 两处注释会过时：HVM:48-51（说 `activeProfileId` 公开是为了编辑态）；`HomeSection.kt:23`（`supportingText` 说「显示在编辑器行里」，删除后改为供托盘和 TalkBack 使用）。

## 4. spec 里已经不成立或需要调整的假设

1. **Memories 入口已经不在 header 里了。** 生产默认是 Bubble overlay（HEC:535-549），header 里只剩 `Spacer(weight 1f)`（HEC:590）。
   - header 行高现在只靠设置齿轮的 48dp。§2.2.3 担心的「卸载入口，行高从 48 掉到 40」现在只对齿轮成立：保持组合，只禁用。
   - 箭头和气泡**天然就是排除区**：根 Box 的 watcher 在 Initial pass 先 consume 了 down（HMB:263-271）。只要新检测器插在 `watchMemoryBubbleTouches` 之后，再看一下 `down.isConsumed`（或者调 `controller.tapTargetAt`）就行。
   - 编辑态下：`covered` 改传 `homeCovered || editing`（HEC:543），让气泡收起且不能说话；HMB:513-516 的 SideEffect 里令 `onArrowTap = null`；`MemoryArrow` 的 graphicsLayer（HMB:635-642）的 alpha 乘上 `1 − smoothstep(0, .5, P)`。
   - 「Drag to reorder」提示行要放进 weight(1f) 那一格。Bubble 模式下那里是 Spacer，要改成 Box，同时保留 `memoryBubbleFreeSpan`。
   - 「Edit Home」用零宽叠放（spec 里的 `Modifier.layout`），这样空隙宽度不变，气泡定位不受影响。HMP 的 fit rule 只影响 debug 变体。
2. **页边**：Capped 档只有窄屏时才是 16dp，平板竖屏是 56dp（FU:141-147）。命中测试里「x 落在页边就算 Blank」对 **RA 不成立**：RA 货架出血进了页边（HEC:1337）。建议按 y 带判断属于哪个 section。签条的 x 要用 `frame.start`，原型里写死 `PAGE_PAD=16`（proto.js:39、:1394）。
3. **item key**：`section-widget-grid` 和 id `jump_back_in` 对不上。统一改成 `section-${id}` 是安全的：测试里没有引用旧 key（已 grep）。
4. **空区块不对称**：
   - Activities 为空时仍然有 item（显示 `HomeEmptyCard`），所以永远不需要占位块。
   - 只有 JBI、RA（以及新的 Rediscover）为空时会被跳过。
   - 所以 spec 的 androidTest「Activities 为空时长按 RA，块不位移」测不到要测的情况，应改为「JBI 为空时长按 RA」。
   - 「全部隐藏」现在没有任何空状态，要新加一个 item。
5. **JBI**：
   - 有三条渲染路径，高度不固定，底边参差（见 §2）。§2.2.4 的 Θ 例子（344×676 等）已经作废；Q1 选了卡片级，Θ 只剩占位块用。
   - §2.2.6 说的「AnimatedContent(SizeTransform)」实际是 `using null` + `springHeight`（HWG:176-181；Activities 同样，HEC:687-692）。
   - **24 封面供给要保住**：`GRID_MAX_COMPACTS = 24`（HVM:798）在 `buildWidgetGrid`（:530-532）和 `refreshWidgetGridSignalCards`（:414）都用到。冻结快照和 Rediscover 去重都不能截短它。
     - 模板实际最多只用 20 张（K=10、R=2）。HVM:792-797 注释里的「10×3、两张 2×2」和 :385 注释里的「12 格预算」都过时了，但上限 24 照留。
   - 签条用的「JBI 前 3 格」：模板的阅读顺序和列表顺序不同（列表里信号卡在前，模板按 row+offset、column 排序，HJT:227-229）。要和屏幕上一致，应该用同一个纯函数 `jbiLayout`（或 `trimToPhoneShelf` + `packWidgetRows`）来取。原型里取的是记忆 1×2 加前 2 张。
6. **Activities 卡数**随 N 和种子变化：3、4、5、7、8、10、12、13（HFD:89-135）。签条取 hero 加前 2 张辅助卡。
7. **RA**：
   - 没有边缘渐隐是 owner 的明确例外（HEC:1327-1330），和 §5「三件齐用」冲突。新的 Rediscover 横滑按通用规则做，RA 保持原样。
   - 专辑为空时签条封面退回用曲目封面（Thumb 形）。Apple 的 RA 恒为空，编辑态显示占位块。
8. **记忆胶囊的作用域和 newsKey 必须保持不变**：
   - `scope = homeScopeKey(providerId, profileId)`，即 `"provider|profile"`（HVM:834-835，用在 :553）。
   - `newsKey = "${latest.lastWrittenAt}#$noteCount"`（HMP:209-210）。
   - 气泡的已读记录存在 `yoin_ui_hints` 里，键是 `"memory_bubble_seen:"+scope`（HMB:155-169）。
   - P0-9 用 `includeIneligible` 构建候选时，**胶囊和 JBI 记忆卡都必须继续只吃合格子集**。`pickLatestMemory` 取 lastWrittenAt 最大的那张（HMP:154-155），一张不合格但有评分的专辑会变成 latest，于是 newsKey 改变，气泡会误报「有新内容」。
   - 去重要同时排除 `pill.latest.albumId` 和 `memoryCard.second`（HVM:554-558）。
   - `HomeEditHintStore` 和气泡共用 `yoin_ui_hints` 文件，键名要避开 `memory_bubble_seen:` 前缀。
9. **冻结**：VM 的推送来自三处：`refresh`（HVM:98-138，换 profile 时由 NH:431 触发，应先 snapExit）、`observeRecentHistory`（:279-331）、`observeMemorySignals`（:341-380）。胶囊是 header 外框，编辑时本来就隐藏，可以只冻结 feed 字段，让胶囊照常更新。
10. **动效参数**：原型的 TOKENS（proto.js:56-65）就是 `MotionScheme.expressive()` 的值（0.8/380、0.6/800、0.8/200、1.0/1600、1.0/3800）。在 HEC 里（HS:158 提供了 Expressive role）读 `YoinMotion.*Spec` 就能逐值对上。`stageSettle` 0.85/700 已存在（Motion.kt:63-64、:258-261）。NavHost 是 Standard role，所以 spec 必须在 HEC 的组合期取值。
11. **图标**：yoin-symbols 里有 `Edit` / `DragHandle` / `VisibilityOff` / `Check` / `Add` / `Refresh`，**没有 `Undo`**，spec 的前置任务仍然成立。
12. **测试与调试页**：
    - `HomeLayoutTest` :18-33、:37-57 断言完整的 3 个 section 列表，加 Rediscover 和 appendEnabled 后会失败。
    - `MemoriesScreenshotActivity`（`app/src/debug/.../MemoriesScreenshotActivity.kt:127-155`）调用 HEC 时没传 `sections` 也没有编辑参数，所以新参数要有默认值，或者给调试页加 `--ez edit` 之类的入口。它文档里 :67 说「activities=false 时 JBI 打头」是错的，实际打头的是 `HomeEmptyCard`。
13. **裁剪**：Compose 的滚动容器只在主轴方向裁剪，交叉轴会外扩 15dp（foundation 的 `clipScrollableContainer` 行为，没在真机验证）。所以原型里为横滑货架留的 `SHELF_V` 内边距（proto.js:50）在 Compose 里不需要。
14. **交界带衰减的依据**：上沿用 `statusBarTop + TideRest`（HEC:357）；下沿用 `LocalSeamBarField.bounds.top`，在 attached 时有效。横屏 EdgeSplit 底部没有栏，下沿只有导航栏。

## 5. 具体挂接点

**(a) Initial pass 长按检测器（覆盖整页，含页边）**
- 挂在根 Box 的链上：HEC:342 的 `.watchMemoryBubbleTouches(...)` 之后、:343 的 `.seamTide` 之前。删除 :363-373。
- 遇到 `down.isConsumed`（气泡或箭头已认领）直接放弃。
- 列表原点就是 Box 原点（:352 是 fillMaxSize）。
  - 纵向：`itemTop = info.offset − layoutInfo.viewportStartOffset`。
  - section 由 `visibleItemsInfo` 的 key 判断：`home-header` 和 `section-*`。
  - 横向页边在 down 那一刻读一次 `feedFrame.start/end`（HFF:78-87）；RA 例外见 §4.2。
- 编辑态下：
  - 下拉进 Memories 关掉：在 HEC:202 的 `onPreScroll` 开头判断 editing。这个 connection 以 `(listState, memoriesRevealState)` 作 key（:200），editing 要通过 State 读进去。
  - RA 设 `userScrollEnabled = !editing`（HEC:1333）。
- 高帧率投票加在同一个 Box 上（`ui/experience/FrameRateVote.kt:22`）。

**(b) 区块包装器（底板、徽标、把手）**
- 在 HEC:411、:481、:506（以及新的 Rediscover 分支）里，把 section 组件包进 `HomeEditBlock(id)`。
  - `.animateItem(...)` 移到包装器根上，并把 `fold > 0` 也并进 placementSpec 为 null 的条件。现在同样的写法重复了 4 处，正好收成一个 helper。
  - `.stagedBeat` 留在内容上。
- 底板用 `drawBehind` 外扩：水平 8dp，竖直 min(6dp, (行距 − 4)/2)。行距来自 HEC:383（18 / 10dp）。
- 徽标**做成包装器 Box 里的叠加层**，对齐标题行（`HomeSectionTitle` 在 HWG:92-107，字号 18sp、行高 28sp）。不要塞进标题 Row：48dp 的触控区会把区块撑高。
- 各区块标题的位置：Activities HEC:663-666，JBI HWG:163，RA HEC:1323。
- 空区块占位：HEC:480 和 :505 的门槛改成 `|| editing`。位于被按住的块**上方**的占位块推迟插入。

**(c) 卡片级轻摆**：插入点见 §2 最后一部分（HEC:1012、:1088、:1164、:1242、:1428、:1487；HWG:368、:527）。没有卡的占位块和 `HomeEmptyCard` 整块转。

**(d) 排除区**
- 设置齿轮 HEC:609-621，加 `homeEditExclusion`。它在 lazy item 里，header 滚出屏幕后坐标会失效，要检查 `isAttached`。
- debug 变体的胶囊（HMP:430）和旧 chevron（HMP:322）。
- 箭头和气泡靠 consume，无需额外处理。
- 新增的隐藏键、托盘行、页尾按钮也要注册。

**(e) 页尾「Edit Home」和 header 提示**
- 普通态在循环结束后（HEC:533 之后、:534 之前）加 `item(key = "home-edit-entry")`：TextButton，`YoinSymbols.Edit` 18dp，onSurfaceVariant，高 48dp，点按调 `controller.enter(Blank)`。底部 108dp+navBar 的内边距会让它避开底栏。
- 「Edit Home」标题叠在 HEC:580-587 上。
- 提示行放在 HEC:588-590 的那一格里。

**(f) 隐藏区托盘**
- 编辑态下在同一位置依次放 `tray-title`、`tray-<id>`（来自 draft 里 `!enabled` 的项，也就是现在 HEC:409 跳过的那些）、`edit-footer`（Reset Home）。每行显示 `HomeSection.title` 和 `supportingText`（HomeSection.kt:21-24）。
- 编辑期间 `sections` 要用 controller 的 draft，覆盖 VM 推来的 `homeLayout`，并一直保持到 Room 的回声到达。
- 底栏的 Add 按钮滚到 `tray-title` 的下标。

**(g) Rediscover 插入**
- `HomeSection.kt` 的枚举（:27-45）加常量 `Rediscover("rediscover", …)`，再加 `appendEnabled` 字段。
  - **默认位置由枚举顺序决定。** 原型的顺序是 activities → rediscover → recently_added → jump_back_in（proto.js:146），和代码的 Activities → JBI → RA 不同。不要动已有常量的顺序（会改变未定制用户的 Default），插在哪需要 owner 定。
- HEC 的 `when`（:410）必须补分支，编译器会强制。
- `HomeUiState.Content`（HomeUiState.kt:14-39）加 `rediscover` 字段，经 HS:260-283 和 HEC 参数（:160-189）传下去。
- VM 侧：`MemorySignals`（HVM:860-863）、`loadMemorySignals`（:541-560，唯一的一次候选构建）、三个 loader（:147-174、:209-231、:233-253）、`observeMemorySignals` 的拼接（:372-375）都要改，外加一个新的 play_history 观察者。
- 新文件 `ui/home/RediscoverSection.kt` 需要把 `rememberActivityCardColors`（HEC:973-993）和 `ActivityCardColors`（:959-963）改成 internal。`WidgetBackdropArtwork` 已经是 internal（HWG:581）。
- 横滑沿用 RA 的出血写法（HEC:1331-1343）。各档的密度规则写进 HFD 或 FU，那里注明了「新的 Home 密度决定只放在这两个文件」（HFD:14-17）。
