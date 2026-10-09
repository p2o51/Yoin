# Yoin Memories 展示柜 v4：落地实现计划

**路径别名（都展开成绝对路径）**
- `$M` = `/Users/gpo/Developer/Yoin/app/src/main/java/com/gpo/yoin`
- `$T` = `/Users/gpo/Developer/Yoin/app/src/test/java/com/gpo/yoin`
- `$DBG` = `/Users/gpo/Developer/Yoin/app/src/debug/java/com/gpo/yoin/debug`
- `$D` = `/private/tmp/claude-501/-Users-gpo-Developer-Yoin/23f51111-8eb4-4490-b6dd-c3b596c48260/scratchpad/design2`

---

## 0. 并发边界（先定规矩）

**不碰的文件。** 这些文件在别的会话里还没提交，读可以，改不行：
- `$M/data/memory/AlbumMemoryCandidate.kt`、`AlbumMemoryCandidateBuilder.kt`、`RediscoverSelection.kt`
- `$M/data/repository/YoinRepository.kt`
- `$M/ui/home/*`（HomeViewModel / HomeEditorialContent / HomeMemoryPill 等）
- `$M/ui/experience/Haptics.kt`、`ExperienceSessionStore.kt`
- `$M/ui/theme/Motion.kt`、`Shape.kt`
- `$M/ui/component/SeamDissolve.kt`、`ExpressiveBackdropPalette.kt`
- `YoinDatabase.kt`
- `docs/haptic-feedback.md`
- `$DBG/MemoriesScreenshotActivity.kt`

**必须改、但现在是脏的。** 每个只做一个独立的小 hunk，改之前重新 grep；最好等对方提交以后再动：
- `$M/ui/navigation/YoinNavHost.kt`：只动 Memories 宿主块，第 955–1034 行。
- `/Users/gpo/Developer/Yoin/app/src/main/AndroidManifest.xml`：加一行 VIBRATE 权限。
- `/Users/gpo/Developer/Yoin/docs/design.md`

**干净的文件，可以改：**
- `$M/ui/memories/*`
- `$M/ui/experience/RevealState.kt`
- `$M/ui/navigation/back/BackMotionTokens.kt`，以及新建文件
- `$M/ui/component/NoteContent.kt`、`MarqueeText.kt`
- `$M/ui/theme/Type.kt`
- `$M/player/PlaybackManager.kt`
- `$DBG/MemoryCardScreenshotActivity.kt`、debug manifest
- `/Users/gpo/Developer/Yoin/.claude/skills/predictive-back/SKILL.md`
- `/Users/gpo/Developer/Yoin/docs/adaptive-principles.md`

**设计稿要先归档。** 设计稿放在 /private/tmp，随时可能被清掉，所以 P0 第一件事就是把它归档进仓库。

---

## 1. 现状 → 目标映射

### 保留（小改）

**外层控制器 q：`$M/ui/experience/RevealState.kt`**
- 继续作为外层 q 的唯一控制器。dragBy / settle / launchAnimateTo / snapTo 全部保留。
- 新增一个按 dp 判定的 dismiss 规则，见 §3。
- Home 下拉打开 Memories 的路径不改：它在 HomeEditorialContent 329–369 调 `settle()`，按 0.5 位置 / 1.6 fraction/s 判定。

**`MemoriesViewModel.kt`**
- 保留单写者结构：ensureLoaded / ensureLoadedFocused / advanceDeck（79–217）。
- 修 §2 列的 bug。
- 新增：saveReview、写入信号刷新、高亮笔记 `litNoteId`、pendingSeek。

**`MemoriesDeckCoordinator.kt`**
- 保留：ensureDeck / ensureDeckFocused / advanceDeck / invalidate / ensureCandidates / resolveDeck / sample / 缓存（33–204）。
- 保留 loadAlbumWritings（468–492），给条目加上 trackId。
- 保留封面 helper（494–513）、`MEMORY_DECK_SIZE`、`formatScore`。
- 新增 `refreshDeck()`。
- 改 resolveAlbumMemory（277–411）的映射。

**`MemoriesUiState.kt`**
- `MemoryEntityType` / `MemoryScoreKind` / `MemoryNeoDbState` 的签名不动。它们被 Home、多个测试和 debug activity 引用。
- `MemoryEntry` / `MemoryWriting` / `MemoryTrack` 只加带默认值的新字段，不删旧字段（删字段放 P7）。

**其他照常用的部分**
- `MemoriesAuroraBackground.kt` 整个保留。
- `MemoriesScreen.kt`：入口的状态切换（140–255）、Empty / Error（257–312）、ReportMotionPressure、换叠 EdgeAdvance（410–450）、deck AnimatedContent（355–375）、方向映射（1498–1506）。
- 宿主侧：
  - `memoriesReveal`（YoinNavHost 409–415）
  - `closeMemories`（789–793）
  - `LaunchedEffect(homeSurface)`（800–809），它是唯一由状态驱动的 settle
  - 切 section 时的 snap（819–824）
  - 底栏随 reveal 隐藏的耦合（1285–1300）
  - `onOpenAlbum`（974–1001）、`toPlaybackActivityContext`（1436–1460）
  - `ShellBackResolver`
- 播放历史三个字段（`playCountFromHistory` / `firstPlayedFromHistoryAt` / `lastPlayedFromHistoryAt`）只读消费。

### 重写
- `MemoriesScreen.kt` 314–1255 整段换成新的 `showcase/` 包（见 §4）。这一段包括：
  - MemoriesContent（314–590）
  - Header / Dots（592–742）
  - MemorySealCard（750–1198）
  - MemoryCardLayout 和 memoryCardLayoutFor（1200–1229）
  - MemoryCardStandalone（1236–1255）
- 卡片下滑关闭：514–555 那个 `draggable` + `revealState.settle()` 换成 dp 规则。卡片主体是 112dp / 600dp/s，反向快甩 350dp/s 收回；顶栏是 56dp / 450dp/s。
- 系统返回：YoinNavHost 956–958 的离散 `BackHandler` 换成两级预测返回。

### 删除

**MemoriesScreen.kt 里的旧件**

| 删什么 | 位置 | 替代 / 原因 |
| --- | --- | --- |
| MemorySeal（含 60s 自转、光晕） | 1264–1373 | GrooveEmblem |
| 盖章逻辑 | 486–497、774–791 | 获得动画。`memoriesStampedTimestamp` 只有这里在用；字段留到 ExperienceSessionStore 干净后再删 |
| "Written by Yoin" | 925–928 | owner 决定不署名 |
| Go to album 的箭头 "→" | 1044–1045 | owner 决定不带箭头 |
| NeoDB 页脚 | 976–1007 | 页脚只剩两个数字 |
| 两个 ModalBottomSheet | 1132–1197 | 日记取代 |
| MemoryNoteCard、MemoryFootnote、evidenceLine | 1375–1467 | 日记取代 |
| ChevronUp 提示箭头 | 574–588 | 改成卡片底部的 "Swipe up for Home" |

**Coordinator 里的死代码**

| 删什么 | 位置 | 原因 |
| --- | --- | --- |
| resolveSongMemory | 208–275 | 全仓库没有调用方 |
| resolvePlaylistMemory | 413–462 | 全仓库没有调用方 |
| averageScoreText、ratedSummaryText、buildCollectionFooter、formatDurationSeconds | 523–551、599–608 | 只被上面两个死函数或 footerText 用 |
| buildAlbumReasonChips、buildAlbumFallbackCopy | 553–597 | 被 MemoryVoice 取代 |

- Memories 不再调用 `deterministicMemoryTitle`。Home JBI 的回退标题还在用它，函数暂留（§7 Q6）。

---

## 2. 审计一期的五个稳定性 bug

**1. 空池粘滞：部分修了。**
- 已修：b947ee8a 让 coordinator 不再缓存空池（ensureCandidates 157–163），首页 focus 点进来也会重建候选池。
- 还没修：VM 的 `ensureLoaded()` 在 84–88 把 `Empty` 当终态。冷启动时 activeSource 还没起来，`getAlbumMemoryCandidates` 直接返回空，状态落成 Empty。之后从页头或下拉打开都会直接 return，所以仍然显示 "No memories yet"。
- 修法：
  - 非 force 时，只有 `Content` 或已有加载在跑才跳过。
  - 订阅 `profileManager.activeSource.map { it?.id }.distinctUntilChanged()`；状态是 Empty / Error 时强制重载。
- 单测：
  - `should_reload_when_previous_result_was_empty`
  - `should_reload_when_active_source_becomes_ready_after_empty`

**2. 写完不刷新：没修。**
- 原因：`resolvedMemoryCache` 只在切账号、强刷、focus 点击时清空。Memories 也没订阅 `observeMemorySignalStamp()`，现在只有 HomeViewModel:443 订阅了。
- 修法：
  - VM 订阅这个信号，先 `drop(1)` 再 `debounce(250)`，然后调用 `coordinator.refreshDeck()`。
  - `refreshDeck()` 先 invalidate，再用新的候选池按当前 deck 的 id 顺序重新解析。
  - 新池里已经没有的 id，保留旧条目，免得 pager 跳页。
  - deckRevision 和 currentPage 都不变，所以 AnimatedContent 不会重放。
  - 日记里写乐评走乐观更新，再走同一条刷新。
- 单测：
  - `should_refresh_current_deck_in_place_when_memory_signal_changes`
  - `should_keep_stale_entry_when_candidate_leaves_pool_after_write`

**3. 触摸穿透到 Home：没修。**
- 原因：Memories 根节点（ExpressivePageBackground）上没有任何 pointer 修饰，页头空白处的点按会落到下层 Home 的设置齿轮。
- 修法：在 MemoriesScreen 根上加一个不消费事件的 `pointerInput`，内容是 `awaitPointerEventScope { while (true) awaitPointerEvent(Initial) }`。有了 pointer 节点，Memories 就成为命中目标，下面的兄弟节点不会再被 hit test。它跟着宿主的 graphicsLayer 一起平移，所以半开时 Home 露出来的部分照常能点。
- 测试：androidTest `MemoriesTouchTest.should_not_deliver_tap_to_home_when_header_blank_is_tapped`。

**4. pushToNeoDb 离线崩溃：没修。**
- 原因：249–307 只有 try/finally，没有 catch。离线且没有缓存时，`repository.getAlbum` 会抛异常（loadCachedDetail 的失败会传给所有等待者），viewModelScope 里没人接，进程直接崩。
- 修法：`CancellationException` 继续往外抛；其他 `Exception` 转成 `NeoDBSyncResult(success = false)` 事件。
- 单测：`should_emit_failure_result_when_album_lookup_throws_during_neodb_push`。
- NeoDB 入口本身还留不留，见 §7 Q1。

**5. 访问专辑页被算成“听过”：没修。前置条件在工作区里，还没提交。**
- 原因：`getRecentAlbumEvents` 不过滤 actionType，builder 把 VISITED 事件并进了 lastPlayedAt / firstPlayedAt。MemoryEntry.timestamp（coordinator:376）和 evidenceLine 都吃这两个值。
- 另一个会话未提交的改动加了只看 play_history 的三个字段，并且刻意不改 playCount 和 lastPlayedAt 的 Memory 语义，入选和排序都不变。
- 修法（不碰他们的文件）：
  - coordinator 里加一个私有函数 `historyOf(candidate)`，映射到 MemoryEntry 的三个新字段：`playsInYoin`、`firstHeardAt`、`lastHeardAt`。
  - 所有“听过”的展示都只读这三个字段：顶栏的 Last heard、页脚两个数字、旁白里的事实、动机短句。
  - 那三个字段提交进 HEAD 之前，`historyOf` 暂时回落到旧字段。切换只改一行。
  - 提交前用 `git show HEAD:…/AlbumMemoryCandidate.kt | grep FromHistory` 确认已经在 HEAD 里。
- ActivityEventDao 不改。访问记录仍然可以作为入选信号，这是对方保留的语义。
- 单测：
  - `should_ignore_visits_when_mapping_last_heard_and_plays`
  - `should_hide_play_facts_when_album_has_no_play_history`

---

## 3. 返回面

### 3.1 AGENTS.md 的五个问题

1. **类型：** ShellOverlayUp，不变。卡片⇄日记是页面内部的状态机，对应 skill 里的 In-page state machine，不是路由，也不是第二个 overlay。
2. **返回到哪：** 日记 → 卡片 → Home（Feed）。Home 是 RootSection，再按返回交给系统。
3. **显式返回按钮：** 有。
   - 顶栏 "⌃ Home" 胶囊，任何状态下都只表示回首页。
   - 日记态另有两个回卡片的入口：顶栏 40dp 封面，和专辑名旁的 ⌄。
4. **手势和系统返回共用控制器吗：** 共用，并且是一对边界清楚的控制器。
   - 外层 q 只表示“退回 Home”。
   - 内层 p 只表示“卡片⇄日记”。
   - 每次手势越过 slop 的那一刻，就决定交给 q 还是 p，不会同时交给两个。
   - q 和 p 各有唯一的 settle 所有者。
5. **复用：**
   - RevealState、`BackMotionTokens.MemoriesDismissTrigger`、`YoinMotion.backGestureEasing`、`predictiveBackSettleSpring`、ShellBackResolver、宿主的 graphicsLayer 平移，全部照用。
   - 新加的阈值全部放进 `BackMotionTokens`：
     - `MemoriesBarDismissTrigger` = 56dp
     - `MemoriesDismissFling` = 600dp/s，`MemoriesBarDismissFling` = 450dp/s
     - `MemoriesFlickBack` = 350dp/s
     - `MemoriesDiaryMorphDistance` = 320dp（对应原型的 `d.D`）
     - `MemoriesDiaryPullBand` = 24dp
   - 扩展 `BackMotionTokensTest`。
   - 注意：MemoriesDismissTrigger 原来只是提示距离，现在也当提交阈值用，在常量注释里写明。

### 3.2 两个控制器怎么接输入

**外层 q**
- 实现：`RevealState.fraction`，0 = 展开，1 = 收回到 Home 上方。
- 新增纯函数 `chooseDismissTarget(px, velocityPxPerSec, commitPx, flingPx, flickBackPx)`：
  - 反向快甩 ≥ flickBack → 0；
  - 已经推过 commit 距离，或速度 ≥ fling → 1；
  - 其余 → 0。
- 新增 `settleDismiss(...)`，复用现有的 settleSpec。

| 输入 | 规则 |
| --- | --- |
| 卡片主体上推 | 1:1 `dragBy`；松手按 112dp / 600dp/s 提交，350dp/s 反向快甩收回 |
| 顶栏上推（卡片和日记都是） | 56dp / 450dp/s |
| 日记滚到底后，新的一次上推 | 起手时记下“当时就在底部”；只路由 UserInput，惯性滚到底只会停住；判定同卡片主体 |
| Home 胶囊点按 | `closeMemories()` → 宿主 effect 调 `launchAnimateTo(1)` |
| 系统返回（p < 0.5 时） | 起手取 q0；`q = q0 + (Δ − q0) · backGestureEasing(progress)`，Δ = MemoriesDismissTrigger / H。原型 `sysBack` 的 QB = 0.09/backEase(0.3) ≈ 0.121，在 915 高的屏上约等于 111dp，也就是预览行程刚好等于提交阈值。提交 → `closeMemories()`；取消 → `launchAnimateTo(0)`；三键导航没有 progress 事件，直接提交 |

**内层 p**
- 新文件 `showcase/MemoriesDiaryState.kt`，API 照抄 RevealState 的 settleJob 语义：手指一按下，就取消正在走的弹簧。
- 取值：0 = 卡片，1 = 日记。小于 0 时是卡片下拉的 0.3× 橡皮筋，下限 −90dp。

| 输入 | 规则 |
| --- | --- |
| Diary 按钮 | `launchAnimateTo(1)`，弹簧推动，不跟手 |
| 日记滚到顶后继续下拉 | 来自嵌套滚动的 UserInput post-scroll。如果手势起点在正文中间（fromScrolled），越过顶部后的前 24dp 只走一半，对应原型的 `wToV` / `vToW`。松手按 `releaseV`：fromScrolled 且还在半速带里 → 留在日记；否则 |v| > 350dp/s 按方向走，否则 p > 0.5 留在日记。惯性滚到顶只停住 |
| 日记态从顶栏往下拉 | 先 `freezeScroll`，再 1:1 擦洗 p |
| 顶栏封面 / ⌄ 点按 | 先 freeze，再 `launchAnimateTo(0)` |
| 系统返回（p ≥ 0.5 时） | 先 stop 正在走的弹簧，取当前值 p0；**p 不随手势擦洗**（2026-10-09 owner：全程擦洗时缓动前段太陡，一划就收掉大半，像返回占了整段行程），改为整页做 AOSP 预览姿态（缩向 0.9、偏向手势一侧、竖向跟手，同 Now Playing 的 StageBackPreview）。提交 → p 走 morph 弹簧到 0，取消 → 1；两端姿态都弹回 |

### 3.3 和 doctrine 不变量逐条对照

- **一个值只有一个 settle 所有者。**
  - q 由状态驱动的 settle 只在宿主 effect 里，用 `launchAnimateTo`。
  - 手势松手走 settle 家族。
  - 返回预览擦洗用 `snapTo`。
- **invariant 3（不许用返回进度擦洗布局变化）。**
  - p 的形变全部是 graphicsLayer 的位移、缩放和透明度。卡片层和日记层任何时刻都是完整排好版的，不改尺寸，不截断；锚点在 placement 阶段量出来。
  - 这等于“对完整布局做变换预览”，也是 owner 在 v4 明确批准的“全程擦洗变形”。
  - 在 skill 文档的 Memories 一节把这一点写清楚。
- **invariant 6（逐帧值只在 layout / draw 里读）。**
  - p、q 和滚动位置只在 graphicsLayer 和 layout modifier 里读。胶囊宽度 88→36 是 layout 阶段的事。
  - 跨阈值的布尔量用 derivedStateOf，只在跨过的那一帧重组。
- **invariant 7、8、9、10。**
  - settle 在外层 `rememberCoroutineScope` 上跑；`CancellationException` 要重新抛出。
  - 三键导航的空 flow 直接走提交。
  - 处理器只在 `backEnabled` 为真时启用，挂载位置和现在的 BackHandler 等价。
  - settle 期间调 `voteHighFrameRate`，避免被压到 60Hz。
- **AGENTS 要求取消和提交用同一组动效 token。**
  - q 的提交和取消都用 RevealState 的 `predictiveBackSettleSpring`，不照搬原型里 `SPR.out` 和 `SPR.back` 两条不同的弹簧。
  - p 的打开、关闭、取消都用同一条 morph 弹簧。

### 3.4 怎么接进现有宿主

- 宿主 Box 上的 `translationY = -fraction * height` 仍然是唯一的位移。
- Memories 在自己根上做底部圆角裁剪：`r = PopPageCornerRadius(28dp) · smoothstep(0, thr / H, q)`。thr 取当前驱动 q 的那个阈值（56 或 112）。不加阴影。
- 新增基础设施 `$M/ui/navigation/back/MemoriesBackHandler.kt`：`MemoriesPredictiveBack(enabled, level, q, p)`。
  - 内部用 PredictiveBackHandler，文件放在 back 基础设施目录里，合规。
  - 功能屏只调这个包装。
- YoinNavHost 在 P2 只改一处：删掉 956–958 的 BackHandler，给 MemoriesScreen 传 `backEnabled = shellBackOwner == ShellBackOwner.Memories`。
- P6 在同一块里再补三件小事：
  - 减少动态时，Memories 原地淡出：translationY 置 0，alpha = 1 − q。
  - Home 在背后从 scale 0.94 / alpha 0.5 回到 1。只在 graphicsLayer 里读 q。
  - Wide 窗开着详情列时，底栏不隐藏，所以要给 Memories 传底部 inset。
- ShellBackResolver 的优先级不变：NowPlaying > HomeEdit > DetailPane > Memories。

---

## 4. 组件拆分

新文件都放在 `$M/ui/memories/` 下面。

### 4.1 刻纹徽记：`emblem/`

**`GrooveGeometry.kt`**
- 纯函数，移植 groove.js 的 `geom` / `cutRuns` / `layout`。
- 按尺寸决定最多几圈：≥110dp 12 圈，≥88dp 8 圈，≥64dp 6 圈，其余 3 圈。小于 60dp 算 tiny。
- `on = Cover | Bar`：Bar 模式外缘内收 0.5，没有说明字，没有涟漪，小于 64dp 时画纺锤孔。
- 实际用到的尺寸：
  - 96：手机卡片
  - 72：短屏手机
  - 96–124：Medium 和对开，按 `sealFor` 算
  - 48：日记标题右侧，原生按 48 画
  - 44、40：只做几何和测试，暂不接线（给首页胶囊以后用）

**`GrooveColors.kt`**
- 移植 `colours()`。所有填充和描边都是单色。
- 输入是 `MemoryPalette(base, accent, deep, soft)`。
- 未评分时只用中性 token。
- `tint(base)` 给出倾斜用的 hi / lo 两个色。

**`GrooveEmblem.kt`**
- `@Composable GrooveEmblem(model, size, surface, tilt: () -> Offset, award: GrooveAwardChannels?)`。
- 全部画在一个 `drawWithCache` 里：
  - 点阵：`dashPathEffect(0, gap)` 加圆头。
  - 刻纹：`drawArc`，平头。
  - 外缘：rim 和 rim2。
  - 中心标签：专辑分是 `MaterialShapes.Cookie12Sided` 的 path；均分是圆；未评是 24 段虚线曲奇。
  - 文字：TextMeasurer 的 `drawText`。分数用 GSF，wght 690 / 640，ROND 50；四个字符时 wdth 76，否则 92。这些 FontFamily 实例放在新文件 `GrooveType.kt`，不动 Type.kt。
- 倾斜着色：外缘和最外两圈刻纹各切 24 段，每段透明度 = `max(0, ±cos(θ − ta)) · tm · w`。只在刻纹覆盖的角度区间里画（直接算角度交集，不用 mask）。
- 获得动画的各通道（盘面旋转、标签缩放、刻纹显现用 `PathMeasure.getSegment`、各种 glow / flare / burst 的透明度）都只在 draw 里读。
- 不可点，是展品。

**`GrooveScript.kt`**
- 纯 Kotlin 移植 `TIERS`、`tierOf`、`script()`、`spring` / `pulse` / `argmax` / `firstT`，以及 45ms 内合并成一拍的规则。
- 结果按 (tier, kind, 圈布局) 缓存。

**`GrooveAward.kt`**
- 用 `withFrameNanos` 推进 t，各通道就是闭式弹簧的函数，所以没有 tween。
- 未颁奖的卡停在 t = 0 的姿态（刻纹还没刻）。对应原型的 `setPending` / `barCut`。
- 被打断时，记下各通道当前值，用一个 e: 1→0 的弹簧把它们一起收回静止，不跳帧。对应 `settleFrom`。
- 重播：盘面圈数取整到整圈，标签用 stiffness 1600 的弹簧接上。
- 日记里的 48dp 小徽记：只“点头”，scale 0.6→1，用 NOD 弹簧；第 2 档及以上再转 −90°→0°。

**`GrooveHaptics.kt`**
- 拍点：`awardBeats(model, mode, size)`，规则照搬原型：
  - 第 4 档的逐圈 tick 最多 4 拍，步长 ceil(n/4)，间隔 ≥ 90ms，强度 0.35；去掉 QUICK_RISE；整档不超过 7 拍。
  - 重播只打高潮拍。
  - 日记点头只打这一档最强的一拍，强度减半，落在 NOD_MS / NOD_RE_MS。
  - 减少动态时只打最强的一拍，在 200ms。
- `GrooveHapticPlayer`：
  - API ≥ 31、`hasVibrator`、`areAllPrimitivesSupported` 三个条件都满足：组合成一个 `VibrationEffect.Composition`，用 `getPrimitiveDurations` 推算每拍之间的 delay。API 33 以上用 USAGE_TOUCH；31–32 先读 `HAPTIC_FEEDBACK_ENABLED`。
  - 不满足：在同样的时刻调用 YoinHaptics 回退：
    - TICK → `performTick`，LOW_TICK → `performLightTick`
    - CLICK → `performClick`，THUD → `performConfirm`
    - QUICK_RISE 跳过；第 4 档的逐圈 tick 只保留第一下
  - 取消：`vibrator.cancel()`。暂停或恢复：先取消，再把剩下的拍重新组合。
- debug 下加一个 `LocalHapticTrace`，作为平板上的视觉等价物。
- 前提：AndroidManifest 要加 VIBRATE 权限。

**`GrooveTilt.kt`**
- SensorManager 注册 `TYPE_GAME_ROTATION_VECTOR`，采样率 SENSOR_DELAY_GAME。
- 只在这些条件同时满足时注册：卡片态、当前页、q = 0、p = 0、RESUMED、不是减少动态。任何一条不满足就注销。
- 处理链：`getRotationMatrixFromVector` → 按屏幕方向 `remapCoordinateSystem` → `getOrientation`。
- 低通基线 τ = 1.6s；±18° 映射到 ±1，限幅到单位圆。
- 跟随弹簧 (0.9, 90) 写进两个 float state，只在 draw 里读。
- `tiltVars` 是纯函数，可以单测。
- 日记里的 48dp 小徽记不接倾斜。

**减少动态**
- 不注册传感器。
- 未评分的涟漪不播。
- 获得动画改成约 200ms 的透明度显影，那一拍放在显影结束时。

### 4.2 卡片态：`showcase/MemoryCardFace.kt`

**两簇布局**
- 展品簇固定在同一高度：air1 → 封面 + 徽记 → 拟题 → 专辑行。air1 让每张卡的封面顶边都在同一位置。
- 预告簇贴底：摘录 → [Diary + 笔记数徽章 | Go to album] → "Swipe up for Home"。
- 中间的 air2 吃掉多余空间。

**尺寸**
- 普通手机：封面 256，徽记 96。
- 短屏（高 < 760）：封面 168，徽记 72。
- 徽记贴在封面右下角：right −0.3s，bottom −0.24s。
- 封面用 `YoinArtworkShapes.Hero`，裸图，没有描边，没有阴影。

**标题**
- 有 AI 拟题：宋体 SemiBold 26sp，短屏 24。
- 没有拟题：动机短句，GSF 600 24sp，ROND 60。

**摘录**
- 用 SubcomposeLayout 量出槽高：按钮行顶 − 16 − 专辑行底 − 24。
- 用 `pickExcerpt` 从候选里选第一个放得下的。候选就是原型 `excerptCands` 的顺序：
  1. 乐评开头的整句，从多到少；
  2. 最短的一条单曲笔记；
  3. 只留一行署名。
- 永远不出省略号。
- 字号按长度分三档：≤ 16 字 22/500，≤ 60 字 17，更长 16。用户正文用 `FontFamily.Default`。
- 署名两级：小字 "Your review" / "你的乐评"（笔记是 "Your note" / "你的笔记"），日期 tabular、60%，不写 "in Diary"。

**按钮**
- Diary 是 tonal：ink 14% 底色，高 48。
- Go to album 是专辑色实心胶囊，没有箭头。

**颜色**
- `MemoryPalette` 直接对 base / accent 做 lerp，得到 ink / hl / btn / dot / tint。
- 深色下的高亮色 hl = `liftHue(base, L = 0.92)`。
- 去掉旧的 `ExpressiveColorSchemeFactory.fromSeed`。

### 4.3 日记态：`showcase/MemoryDiary.kt` 和 `MemoryDiaryWriter.kt`

**容器**
- `Column` + `verticalScroll`。每页一个 ScrollState，换卡后归零。
- 页面停稳后，在 alpha 0 下预先组合当前页的日记，这样锚点已经量好，重帧也不会落在弹簧中间。

**内容顺序**
1. 标题行：宋体 22 + 原生 48dp 徽记靠右。
2. Yoin 的段落：GSF 16/1.6，ROND 60，onSurfaceVariant；末尾的问句用 onSurface 500。
3. 你的条目：有乐评就是乐评条目，没有就是今天的空白日记。
4. 两段真实内容之间放 • • •。
5. 曲目行：选项 A，只列有评分或有笔记的曲目。
   - 专辑笔记排最前，左边一颗 6dp 空心小珠，不可点。
   - 每条曲目行高 48：曲号 / 歌名 / 分数。
   - 笔记挂在所属曲目下面，像歌词：时间戳 12.5 放在 40dp 宽的列里，正文 15.5，两者按基线对齐。整行可点，按下是 `YoinContainerShapes.ListRow` 的浅底。
6. 结尾跟着内容走，离上一块 56dp：
   - 导出槽标记：28dp，三圈发丝细环。
   - 两个数字：plays，以及 "days since Mar 14"。数字 34/500，标签 12，两列相隔 48dp。
   - Go to album。

**特殊排版**
- 16 字以内、又没有笔记的短乐评，放在可视区 38% 的光学位置。

**空白日记的写作器**
- 页眉是今天的日期。
- 左边是 NoteComposer 同款竖线：未聚焦时 ink 32%，聚焦时 100%。
- 用 BasicTextField，不用 OutlinedTextField。
- 下面一行 Cancel 和 Save 两个按钮：Save 是专辑色实心胶囊，点按区 48。
- 从 `$M/ui/component/NoteContent.kt` 把竖线和保存胶囊抽成 internal 的 `JournalRail` / `JournalSavePill`，NoteComposer 改成调用它们，视觉逐像素不变。
- 保存时：
  - 调 `viewModel.saveReview(memory, text)`。VM 里先从缓存拿 `getAlbum`，拿到后调 `setAlbumReview`。拿不到就保留草稿，并给出错误提示。
  - 打一拍 CONFIRM。
  - 原地变成乐评条目：竖线淡出，左内边距 14→0 走弹簧，标签从 "Today" 交叉淡化成 "Your review"。

**接缝和淡出**
- 顶部用 `seamDissolveViewport(top = SeamTop.Chrome) { scroll.value }`，即用户选的潮线样式。文字挂 `seamFade()`，48dp 徽记挂 `seamDissolve()`。
- 底部用 `verticalEdgeFadeOnScroll(state, bottom = 40.dp)`。

**播放高亮**
- VM 暴露 `litNoteId: StateFlow<String?>` 和 `playingTrackId`，按 `currentAnchoredNoteId` 的规则算，并且 `distinctUntilChanged`。
- 逐 tick 的播放位置不进 UiState，遵守 NP position dedup 的规定。

**点笔记行**
- 走宿主现有的 `onPlayMemoryTrack(memory, index)`。
- 同时调 `viewModel.requestSeek(trackId, positionMs)`：等目标曲目真正成为当前曲目、duration > 0 之后，再 `seekTo` 一次，超时 4 秒放弃。
- 在高亮落定的那一帧打 TICK ×0.5；点曲目行打 TICK ×0.4。

### 4.4 顶栏：`showcase/MemoriesTopBar.kt`

**共享层，放在 pager 外面**
- "⌃ Home" 胶囊：宽度 88→36，在 layout 阶段读 p；标签透明度按 `1 − ss(0, .4, fp)` 淡出。点按区 48。
- 圆点：每个点击框 32×48，点按落到 x 方向最近的圆点中心。

**每页一份，放在 page 里**
- slotA："Memories" 和 "Last heard Oct 2"。
- 40dp 封面：4dp 圆角，点按区 48。
- slotB：专辑名和艺人行。
  - 艺人行是歌手和年份两段（年份 60%），放不下时先去掉年份，还放不下才滚动。
  - ⌄ 固定在右端，不跟着文字滚。
  - 用 MarqueeText，新增 `running` 参数，只在页面停稳、日记完全打开时才滚。
- 视差：x 位移 = 0.6·W·rel，可见度 = 1 − |rel|·2.2。
- 邻页隐形的控件要禁用，并且用 `clearAndSetSemantics` 清掉语义，对应原型里的 `inert`。

### 4.5 共享元素形变：`showcase/MemoriesMorph.kt`

**锚点**
- 在 `onPlaced` 里记录相对 page 根的位置：封面中心、卡片标题、96dp 徽记、日记标题、48dp 徽记位、栏封面位、日记第一块。

**编排**
- 编排本身写成纯函数，交给 graphicsLayer 用：
  - 封面：
    - 尺寸领先于位置：`1 − (1 − fp)²`。
    - 圆角 8→4dp，用 HeroAnimated 和 ThumbAnimated 的圆形孪生形状。
    - fp 在 0.62–0.9 之间，交给栏里自己的 40dp 封面：`barShown` 夹在 [ss(.75, 1, fp), ss(.62, .9, fp)] 区间里，用 `fastEffectsSpec` 去追。
  - 标题：p < 0.2 时原地不动，之后飞到日记标题，在 tt 0.42–0.78 之间交叉淡化，字号按比例缩放。
  - 徽记：96 跟着标题一起飞，缩到 48；说明字先淡出（`1 − ss(0, .4, te)`）；te 在 0.6–0.95 之间交给原生 48。
  - 专辑行：上移 36·p，透明度 `1 − ss(0, .32, p)`。
  - 摘录、按钮、提示语：下移 56·p 并淡出。
  - 日记各块：偏移 (1 − p)(lag + 28k)，透明度 `ss(.26 + .04k, .92, p)`，k 最多算到 6。
- 这些窗口常数放进 `MemoriesMorphTokens`。它们是编排参数，不是返回阈值。

**冻结式收起**
- 如果收起时日记标题已经滚到顶栏下面（`so ≥ thr`），就进入冻结模式：
  - 正文整体下沉淡出，偏移 (1 − p)·lag；
  - 封面在顶栏里等，fp = p / 0.55；
  - 卡片上的标题和 96dp 徽记在原位淡入；
  - p < 0.05 时，再看不见地把滚动归零。

**减少动态**
- 走同一个 p，只画透明度：卡片在 p 0.5 前淡完，日记从 0.5 开始淡入。同一时刻屏上只有一层文字。

### 4.6 大屏：`showcase/MemoriesLayout.kt` 和 `MemoriesSpread.kt`

**`MemoriesLayout.kt`**
- 纯函数，入参是容器的宽高（BoxWithConstraints），不是设备尺寸。
- 移植 `layoutFor` / `medCov` / `sealFor` / `balanceMedium`、对开的高度梯子，以及 `placeSpread` 的居中规则。
- 文字高度用 TextMeasurer 量，做成像 NowPlayingBudget 那样的预算函数，符合 adaptive-principles 第 6 条。

**Medium（选项 A，放大的手机两态）**
- 卡片放在 480 列（`YoinPageWidths.Card`），日记放在 `min(640, W − 32)` 列（`YoinPageWidths.Prose`）。
- 平板字号：标题 30，乐评 17.5/1.9，短乐评 30，旁白 17，数字 38。
- 曲目表最大宽度 520，编号列 44。

**Expanded：对开**
- 左页：封面 + 徽记、拟题、旁白和问句、专辑行、Go to album。
- 右页：从你的条目开始，接着是曲目行和页脚。
- 两页之间不画分隔线，至少空 64dp。
- 左页上滑回首页；右页纵向只滚日记，滚到底再上推回首页。
- 系统返回只有一级。

**手机横屏（高 < 480）**
- 原型里没做，见 §7 Q2。临时方案：按对开的结构，加更低的封面下限。

### 4.7 文案生成：`copy/`，全部是纯 Kotlin，可以单测

| 文件 | 移植内容 |
| --- | --- |
| `MemoryVoice.kt` | `writesIn`、`signals`、`motif`、`narrative`（①–④ 四套模板，中英各一版）、`voice()` 用 `said` 集合去重、`num`（“两”只用来计数）、`ZH_MON`（月份不用“两”） |
| `MemorySentences.kt` | `sentences`、`joinS`、`wlen` |
| `MemoryDates.kt` | 日期语法：今年省略年份，别的年份带上；日记条目页眉总带年份；“今天”由注入的时钟决定 |
| `MemoryExcerpt.kt` | `excerptCands`，Medium 下最多两句、约 60 字 |

- 语言规则：Yoin 写的成段文字跟用户的写作语言；按钮、署名这类界面文字跟 app 语言。app 目前没有 strings.xml，界面文字都是硬编码的英文。
- 宋体只给 AI 拟题。

### 4.8 粘合层

- `showcase/MemoriesShowcase.kt`：HorizontalPager，每页有卡片层和日记层；共享顶栏；按档位在 phone 和 spread 之间切换。
- `showcase/MemoriesGestures.kt`：
  - `Modifier.memoriesVerticalDrag(router)`：基于 `awaitVerticalTouchSlopOrCancellation`，越过 slop 时按方向决定路由，带 VelocityTracker。
  - 日记的 NestedScrollConnection。
  - 用一个 Initial pass、不消费的 down 监听，记下按下时是否在顶部、是否在底部、是否从正文中间起手。
- `award/MemoriesAwardLifecycle.kt`：
  - 三个集合：awarded、nodded、unaward，在 Memories 卸载时清空。所以每次打开都是新的一叠，和原型的 reset 一致。
  - 触发时机：
    - 打开时 q ≤ 0.15；
    - pager 停到离当前页 0.15 以内，并且手指已离开；
    - 日记收回、p 落到 0。
  - 第一拍离手指抬起至少 120ms。
- `$DBG/MemoryCardScreenshotActivity.kt`：改写成 fixture harness，可以不依赖 VM 跑整个展示柜。
  - fixture 移植 m1–m4 和 m5。
  - extras：`--es tier`、`--ei page`、`--ez diary`、`--ef p`、`--ez dark`、`--ez reduced`、`--es play track:pos`、`--ez trace`。
  - debug manifest 不用改。

---

## 5. 分期

### 每期通用的验证方法

**构建和安装**
- 构建：`./gradlew assembleDebug`，JDK 用 Android Studio 自带的 jbr。
- 安装：`$ADB install -r`。其中：
  ```
  ADB="ANDROID_ADB_SERVER_PORT=5038 ~/Library/Android/sdk/platform-tools/adb -s adb-3408105H803AEE-Deuouc._adb-tls-connect._tcp"
  ```
- 单测：`./gradlew :app:testDebugUnitTest --tests "com.gpo.yoin.ui.memories.*"`。
- Kotlin lint：用 ktlint 1.0.1 CLI 查改过的 .kt 文件。AGP 9 下 `ktlintCheck` 只看 .kts。

**每轮平板 QA 前**
- 先 `wm size` 复核尺寸。
- 用 `logcat -d | grep "adbd service requested"` 排除别的会话在驱动平板。

**三种尺寸**
- 竖屏 800dp。
- 横屏 1280dp。
- 模拟手机：`$ADB shell wm size 1080x2400 && $ADB shell wm density 420`，得到 411×914dp。测完执行 `wm size reset; wm density reset`。

**触感**
- 平板没有振动马达，只看 HapticTrace 叠层。
- 真实手感在手机上验。release 包放 Google Drive 的 Inbox，按 feedback_test_apk_drive 的约定。

### P0　归档和协调（不写代码）

**做什么**
1. 把 `$D` 里的这些文件复制到 `/Users/gpo/Developer/Yoin/docs/handoff/memories-showcase/`：pages/twostate4.html、emblems/groove.js、pages/groove-final.html、polish_plan.json、shared/data.js、memories-showcase-v4.html。
2. 写一份 README，记下 owner 的最终决定和五个默认选项。
3. 用 node 跑原型，导出黄金数据：`golden/groove-geom.json`、`groove-beats.json`、`layoutFor.json`、`voice.json`。
4. 确认另一个会话的提交状态。
5. 找 owner 确认阻塞项：§7 的 Q1、Q2、Q3。

**风险：** /tmp 被清空。所以这一期要最先做。

### P1　稳定性修复

P1 分两段：**1a** 不依赖任何人，现在就能做；**1b** 等对方会话把播放历史那三个字段提交进 HEAD 以后再做。

**改的文件**
- `MemoriesViewModel.kt`、`MemoriesDeckCoordinator.kt`
- `MemoriesScreen.kt`：只在根上加 `pointerInput`
- 新建 `$T/ui/memories/MemoriesViewModelTest.kt`；扩展 `MemoriesDeckCoordinatorTest`
- androidTest：`MemoriesTouchTest`

**风险**
- 刷新时机落在横滑过程中：用 debounce，并保证 deck 身份不变。
- NP 里写笔记也会触发刷新：重新解析 6 张卡，读的都是缓存，成本可以接受。

**单测：** §2 列出的那几条。

**平板 QA**
- 竖屏：
  - 冷启动后立刻下拉打开 Memories → 数据源就绪后能自动出卡。
  - 从卡片进 Go to album，写一段乐评，返回 → 卡片已经更新。
  - 点页头空白处 → 不会打开设置。
  - 飞行模式下点 NeoDB 推送 → 只出 snackbar，不崩溃。
  - 只访问过、没播放过的专辑 → 不会显示“heard”。
- 横屏 1280 和模拟手机：重复页头空白点按那一条。

### P2　返回基础设施和两个控制器（在旧 UI 上就能验）

**改的文件**
- `RevealState.kt`：加 `chooseDismissTarget` 和 `settleDismiss`。
- `BackMotionTokens.kt`
- 新建 `$M/ui/navigation/back/MemoriesBackHandler.kt`
- 新建 `showcase/MemoriesDiaryState.kt`，含纯决策函数
- `MemoriesScreen.kt`：旧卡片改用 dp 规则，根上加圆角裁剪，只挂卡片这一级返回
- YoinNavHost 第一处小改

**风险**
- 挂载顺序和返回优先级：NP 打开时、详情列打开时，都不能抢它们的返回。
- 三键导航的空 flow 路径。
- 宿主 effect 和手势 settle 在端点处的 guard 要正确跳过。

**单测**
- `$T/ui/experience/RevealStateDismissRuleTest.kt`：
  - `should_commit_when_pushed_past_commit_distance`
  - `should_commit_when_fling_exceeds_threshold_below_distance`
  - `should_return_when_flicked_back_past_threshold`
  - `should_return_when_released_short_without_speed`
- `BackMotionTokensTest`：`should_keep_memories_back_tokens_stable`
- `MemoriesBackMathTest`：
  - `should_map_full_back_progress_to_dismiss_trigger_travel`
  - `should_start_card_preview_from_current_fraction`
  - `should_scrub_diary_from_p0_when_back_starts_mid_spring`
- `MemoriesDiaryStateTest`：
  - `should_stay_in_diary_when_fling_to_top_started_in_text`
  - `should_close_when_pull_from_top_passes_half`
  - `should_halve_pull_in_first_band_when_started_scrolled`

**平板 QA**
- 竖屏：
  - 从左右两边分别慢拖返回 → 卡片缓动上移到 112dp，圆角同时给满。
  - 停住、取消、快甩提交、三键返回，各验一遍。
  - 慢推 112dp 能提交，100dp 不能；小距离快甩能提交；推过阈值后再往下甩能收回。
- 横屏 1280：开着详情列时，第一次返回关列，第二次返回才收 Memories。
- 模拟手机：重复竖屏那几条。

### P3　刻纹徽记（独立组件，用 harness 验）

**改的文件**
- `emblem/` 下 8 个文件
- `showcase/MemoryPalette.kt`
- harness 加 `--es mode emblem`
- AndroidManifest 加 VIBRATE，需要协调

**风险**
- Canvas 性能：path 多，draw 里有 TextMeasurer。
- 各机型对 primitive 的支持不一样。
- 平板的自然方向是横屏，坐标换算容易错。
- 可变字体实例的开销。

**单测**
- `GrooveGeometryTest`（对黄金数据）：`should_match_prototype_rings_when_size_is_96_72_48_44_40`
- `GrooveScriptTest`：
  - `should_pick_tier_two_when_score_displays_6_0`（5.95 显示成 6.0）
  - `should_place_beats_on_spring_extrema_within_1ms`
  - `should_merge_beats_closer_than_45ms`
- `GrooveHapticsTest`：
  - `should_cap_tier_four_at_seven_beats`
  - `should_keep_only_climax_when_replaying`
  - `should_halve_strongest_beat_when_nodding`
  - `should_skip_quick_rise_when_falling_back_to_view_haptics`
- `GrooveTiltTest`：
  - `should_clamp_to_unit_disc_when_tilt_exceeds_18_degrees`
  - `should_return_to_rest_when_baseline_catches_up`
- `MemoryPaletteTest`：`should_lift_dark_highlight_to_at_least_on_surface_luminance`

**平板 QA**
- 竖屏和横屏：
  - 网格展示 4 档 × 3 种分数类型 × 6 个尺寸 × 浅深色，和 Chrome 里同 dp 的原型截图逐个对比。
  - 每一档重播一次，看 HapticTrace 的拍点时刻。
  - 实际倾斜平板：外圈朝倾斜方向变浅，静止时和没有倾斜完全一样。
  - 打开省电模式（触发 AdaptiveReduced）→ 不再跟倾斜，获得动画变成显影。
- 手机（release 包）：感受四档触感。

### P4　文案和数据模型

**改的文件**
- `copy/` 下 4 个文件
- `MemoriesUiState.kt`：新字段，包括带 number 和 trackId 的 tracks、writings 的 trackId、`playsInYoin`、`firstHeardAt`、`lastHeardAt`
- coordinator：拟题回退改成动机短句；是否停用 Gemini 旁白看 §7 Q5
- 旧 UI 先吃到几处改动：标题槽显示动机短句、去掉 Written by Yoin、去掉箭头

**风险**
- 中英模板和实际数据对不上，比如没有播放记录时 plays 是 0。

**单测**
- `MemoryVoiceTest`：
  - `should_fall_back_to_app_language_when_kana_present`
  - `should_write_in_chinese_when_han_outweighs_latin`
  - `should_not_repeat_fact_already_in_motif`
  - `should_ask_question_that_follows_preceding_fact`
- `MemorySentencesTest`：
  - `should_not_split_decimal_score_when_period_precedes_digit`
  - `should_keep_closing_quote_with_sentence`
- `MemoryDatesTest`：
  - `should_drop_year_when_date_is_this_year`
  - `should_never_use_liang_in_month_names`
- `MemoryExcerptTest`：
  - `should_use_whole_sentences_only_when_slot_is_short`
  - `should_cap_medium_teaser_at_two_sentences_or_60_weighted_chars`
- coordinator：
  - `should_group_notes_under_tracks_by_track_id`
  - `should_list_only_rated_or_noted_tracks_when_mode_a`

**平板 QA**
- 竖屏：写一条中文笔记 → 旁白变成中文；没有 AI 拟题时显示动机短句。

### P5a　卡片态、顶栏、回首页手势、获得动画

**改的文件**
- `MemoriesShowcase.kt`、`MemoryCardFace.kt`、`MemoriesTopBar.kt`、`MemoriesGestures.kt`、`MemoriesAwardLifecycle.kt`
- MemoriesScreen 改成转发给新组件
- 删除旧卡片代码

**单测**
- `MemoriesAwardLifecycleTest`：
  - `should_award_once_per_open_when_card_first_settles`
  - `should_delay_first_beat_120ms_after_lift`
  - `should_unaward_when_card_changes_before_climax`
  - `should_start_open_award_when_reveal_passes_85_percent`
- `MemoriesGestureRouterTest`：
  - `should_route_bar_push_up_to_dismiss_with_bar_rule`
  - `should_route_card_pull_down_to_rubber_band`

**QA**
- 模拟手机：
  - 6 张卡的封面顶边在同一位置。
  - 摘录都是整句。
  - 卡片上任意位置上滑回首页；顶栏上推 56dp 回首页。
  - 点圆点落到最近的那一个。
  - 横滑时拍点暂停，画面照播。
- 短屏模拟：`wm size 1080x1920`、density 420。

### P5b　日记、形变、两级返回、写作器、播放高亮

**改的文件**
- `MemoryDiary.kt`、`MemoryDiaryWriter.kt`、`MemoriesMorph.kt`
- 返回处理器补上日记这一级
- `NoteContent.kt`：抽出竖线和保存胶囊
- `MarqueeText.kt`
- VM：`saveReview` / `litNoteId` / `requestSeek`

**风险**
- IME 弹起时 Save 被推出屏幕外，参照 NP 写作器的处理方式。
- 播放起始的 seek 在各个 provider 上表现不一样，见 §7 Q11。

**单测**
- `MemoriesMorphTest`：
  - `should_hold_title_until_p_0_2`
  - `should_keep_bar_cover_hidden_below_fp_0_62`
  - `should_delay_cover_flight_when_frozen`
- `MemoriesViewModelTest`：
  - `should_keep_draft_and_report_when_album_unavailable_on_save`
  - `should_light_latest_anchored_note_at_or_before_playhead`
  - `should_seek_once_when_target_track_becomes_current`
  - `should_not_emit_when_position_ticks_within_same_note`

**QA**
- 模拟手机：
  - 点 Diary，看三条轨迹：封面飞进顶栏、徽记落到日记标题右边、正文块错落升起。
  - 关回卡片：顶部下拉、点顶栏封面、点 ⌄。
  - 日记里的系统返回：两边慢拖、取消、提交、三键返回，都应该回到卡片。
  - 回首页：日记里顶栏上推、在底部重新上推都回首页；惯性滚到底只停住。
  - 日记态横滑：新卡也是日记态，从开头读，小徽记点头。
  - 写一段乐评：保存后原地变成乐评条目。
  - 点笔记行：从锚点开始播放，高亮跟着走。
  - Go to album 返回后，日记还在原来的滚动位置。

### P5c　收尾

**做什么**
- 从日记深处收起：正文不倒带。
- 潮线、底部 40dp 淡出、跑马灯只在停稳时滚。
- 减少动态下的淡出淡入。
- 日记里的触感：越过 56dp、越过 p 0.5 时各打一拍 CLOCK_TICK，退回再打一拍。
- TalkBack 标签。

**单测：** `should_fade_through_without_overlap_when_reduced_motion`、穿越阈值的检测器测试。

**QA**
- 用 m5 长日记 fixture 验滚动、潮线、结尾。
- 省电模式下验一遍，再把开发者选项里的动画缩放关掉验一遍。

### P6　大屏，以及宿主第二处小改

**改的文件**
- `MemoriesLayout.kt`、`MemoriesSpread.kt`
- YoinNavHost 第二处小改

**风险**
- Wide 窗里点 Go to album 时，Memories 会从对开换到 600 列的 Medium。这个切换过程设计稿没做，见 §7 Q9。
- 详情列开着时底栏会盖住内容。

**单测：`MemoriesLayoutTest`，对黄金数据**

| 容器 W×H | 档位 | 预期 |
| --- | --- | --- |
| 412×915 | phone | 封面 256 / 徽记 96 |
| 375×667 | phone 短屏 | 168 / 72 |
| 600×728 | Medium 短屏 | 168 / 72 |
| 690×840 | Medium | 260 / 98 |
| 800×1280 | Medium | 360 / 124，air1 104，日记列 640 |
| 860×800 | Medium | 256 / 96 |
| 900×1100 | Medium | 对开装不下 360 的封面 |
| 1000×700 | spread | lp 460，measure 436，封面 300 / 徽记 120 |
| 1280×800 | spread | lp 589，measure 560，封面 300 / 徽记 120 |

另加：
- `should_tighten_spacing_before_shrinking_cover`
- `should_use_one_cover_for_whole_deck`
- `should_never_shrink_exhibit_when_crossing_600_or_900`

**平板 QA**
- 竖屏 800：放大的两态，卡片在 480 列、日记在 640 列，air2 最多 120。
- 横屏 1280：对开。
  - 短内容时右页居中在左页的光学中线上。
  - 左页上滑回首页；右页滚到底再上推回首页；返回只有一级。
- 打开日记时旋转屏幕：日记是否打开这个状态要带过去。
- 开着 NP 侧栏时，Memories 进入 Medium。
- 开着详情列时，底栏不压住内容。
- 模拟手机：回到手机布局。

### P7　清理和文档

**做什么**
- 删除不再使用的字段和参数。`onNavigateToNeoDbSettings` 的去留按 Q1 的结论。
- 更新 §6 列出的文档、predictive-back skill、haptic-feedback.md 新增 §G、adaptive-principles 第 6 条。
- 更新相关的 memory 笔记。
- 截图做成 Artifact 页交给 owner。
- 跑全量 `./gradlew test` 和 Memories 相关的 connectedAndroidTest。

---

## 6. docs/design.md 要改哪里

**`### Album Memory`（204–228）重写成“展示柜两态 v4”**
- 卡片和日记两种状态，以及手势。
- 不署名：去掉 "Written by Yoin"。字体规则改成：宋体只属于 AI 拟题，尺寸按 YoinSerifDisplay 26 / 30 / 22；回退动机短句用 GSF ROND 60；Yoin 的旁白用 GSF ROND 60。
- 语言规则。
- 页脚只剩两个数字。这取代第 216 行“卡片必须解释为什么成为 memory”的要求，要在文中写明是 v4 的决定。
- 曲目行选项 A 推翻了 v2.2 的“别复活曲目表”，要显式写出来。
- 徽记：唱片刻纹，四档、倾斜、触感、尺寸。
- 获得动画的生命周期。
- 五个默认选项。
- NeoDB 入口：等 Q1 的结论再写。

**其他位置**
- `## 关键转场动画`（第 307 行附近）：新增两行。
  - 卡片⇄日记的形变；
  - 回首页：卡片 112dp、顶栏 56dp、滚到底后重新上推、返回预览 112dp。
- `### 📱 大屏幕适配`（第 372 行）：Memories 那句改成按容器尺寸判定（phone / Medium / spread），手机横屏待定。
- 颜色系统：补一句 Memories 用专辑调色板直接 lerp。

**冲突风险和处理办法**
- 另一个会话的未提交 hunk 在第 86 行、192–202 行（Home）和第 292 行。我们要改的 204 行起，紧挨着它们 202 行结束的那一块。
- 首选：等对方提交以后再改。
- 等不了的话：
  1. 直接改工作区里的文件。
  2. 另外用 `git show HEAD:docs/design.md` 取出原文，只套用我们的修改。
  3. 用 `git diff --no-index` 生成补丁，再 `git apply --cached` 只暂存我们的 hunk。

  不能用 `git add -p`，这个环境不支持交互式命令。

---

## 7. 开放问题

**需要 owner 拍板**

| # | 问题 | 现状 / 建议 |
| --- | --- | --- |
| Q1 | NeoDB 推送入口放哪 | v4 页脚不再显示同步状态，但 AlbumDetail 的文案写着 "pushed to NeoDB from the Memory card"，这是唯一入口。可选：A 挪到 AlbumDetail 的乐评编辑里（要改文案）；B 日记里留一个安静的入口；C 保存乐评时自动推送 |
| Q2 | 手机横屏（高 < 480） | 原型没做。建议按对开结构排，封面下限更低 |
| Q3 | 从没在 Yoin 里播放过的专辑 | 只访问过、或只有笔记的专辑：页脚两个数字、顶栏 Last heard、“N 遍”动机短句怎么处理。建议：显示 0 plays，隐藏天数；顶栏只写 Memories；动机短句跳过“遍数”那一条，最后退到专辑名 |
| Q4 | 乐评的写作日期和修改日期 | 原型区分这两个日期。数据库只有一个 updatedAt，而且评分改动也会刷新它，所以 v1 拿不到准确的日期。要准确就得做迁移，但 YoinDatabase 现在是脏的 |
| Q5 | Gemini 旁白 | 建议停用，旁白全部由本地模板生成，与 Yoin 文风规则一致，也省一次调用。AI 拟题的语言要不要按写作语言显式传参 |
| Q6 | 首页 Jump Back In 的回退标题 | 是否同步改成动机短句，保持一致。HomeViewModel 现在是脏的，要等它干净 |
| Q7 | 每次打开都重新颁一次奖 | 原型里 reset 等于重新打开，每次打开都颁奖。频率会不会嫌烦 |
| Q8 | 卡片下面的环境循环 | 卡片底部提示箭头每 3.2 秒轻推一下，未评分盘面每 4.8 秒一次涟漪，原型都是关键帧动画。要满足“只用弹簧”，就改成周期性弹簧脉冲，或者干脆不要 |
| Q9 | Wide 窗里 Go to album 时的切档 | 对开切到 600 列 Medium 的过程原型没做；日记打开状态如何带过去 |
| Q10 | 文案小项 | Diary 按钮的图标：Yoin Symbols 里没有书本图标，要去 yoin-symbols 仓库加一个，还是先用 Note。保存按钮写 "Save" 还是沿用 NP 的「记下」 |

**实现层面的偏差（拿不准再请 owner 确认）**

- **Q11 从笔记时间点开始播放。** 现在的做法是先 play，等目标曲目成为当前曲目后再 seek。另一种是给 `PlaybackManager.play` 加 `startPositionMs`，但要改 Media3、Spotify、Apple Music 三条路径，风险更高。
- **Q12 弹簧映射和原型数值有偏差。**
  - SPR.morph (340 / .82) → expressive 默认空间弹簧 (380 / .8)。
  - SPR.out 和 SPR.back 合并成同一条 RevealState settle：AGENTS 要求取消和提交用同一组 token。
  - EFFECTS (200 / 1) → `slowEffectsSpec` (800)。
  - 圆角 32 → `PopPageCornerRadius` 28。
  - 潮线用 app 自己的 token（波长 72 / 116，40dp），不用原型的 48 / 78、12dp。
  - q 在展开端硬钳到 0。原型允许 −0.05 的过冲，RevealState 的 doctrine 是硬钳。
- **Q13 CSS 能力在 Compose 里没有直接对应。**
  - `text-wrap: balance` / `pretty` → 用 `LineBreak.Heading` / `LineBreak.Paragraph` 近似。
  - mask-composite 做的潮线 → 改用 seam viewport。
  - 专辑名的两行截断：Compose 用 `maxLines = 2`、`TextOverflow.Clip`，要确认是否违反“不许截断”。
  - 原型里的鼠标滚轮模型：桌面窗口要不要支持。
- **Q14 发布相关。** 新增 VIBRATE 权限需要改 AndroidManifest。它是 normal 权限，不弹授权，但要和正在改 manifest 的会话协调。

---

### Critical Files for Implementation
- /Users/gpo/Developer/Yoin/app/src/main/java/com/gpo/yoin/ui/memories/MemoriesScreen.kt
- /Users/gpo/Developer/Yoin/app/src/main/java/com/gpo/yoin/ui/memories/MemoriesViewModel.kt
- /Users/gpo/Developer/Yoin/app/src/main/java/com/gpo/yoin/ui/memories/MemoriesDeckCoordinator.kt
- /Users/gpo/Developer/Yoin/app/src/main/java/com/gpo/yoin/ui/experience/RevealState.kt
- /Users/gpo/Developer/Yoin/app/src/main/java/com/gpo/yoin/ui/navigation/YoinNavHost.kt（只改 955–1034 这一块，以及 BackMotionTokens.kt 旁边新建的 MemoriesBackHandler.kt）