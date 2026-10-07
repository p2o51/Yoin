# Yoin 适配原则（Adaptive Principles）

> 2026-10-02 定稿。这是以后做任何尺寸适配时的裁判：新页面、新窗口形态先对照这八条，
> 规则覆盖不到的东西才单独修。实现对应的代码位置在每条末尾。
> 与 `docs/design.md`「大屏幕适配」一节、`.claude/skills/predictive-back` 配套；
> 三者冲突时以本文为准（design.md 记决定，本文记规则）。

## 一句话

**每个窗口只有一棵树、一条栏、一套 Now Playing 状态链；窗口里的"栏目"是列，不是第二个窗口。**
页面只回答三个问题：我在哪个容器里、容器多宽多高、我要不要让出宽度。

## 八条原则

### 1. 先判高，再判宽，只看自己的容器
- 判定输入只有两个数：**所在容器**的宽和高。高 < 480dp 先出局（手机横屏，`EdgeSplit`），
  再按宽分 Compact < 600 / Medium 600–840 / Wide ≥ 840（Tabletop 另算）。
- 组件永远读 `LocalYoinWindowInfo`，永远不读 `LocalConfiguration`、`currentWindowAdaptiveInfo`
  或 `WindowMetricsCalculator`。
- 任何让出宽度的容器（NP 侧栏、详情列）都用 `YoinWindowInfo.forPaneWidth(列宽)` 给子树重新
  提供 `LocalYoinWindowInfo`（详情列：`rememberColumnWindowInfos` 算出 shell / 列两份，YoinNavHost 用常驻的
  `CompositionLocalProvider` 提供；详情 Activity 里 NP 侧栏旁：`ProvideBesidePanelWindowInfo`）。
  属于窗口的 chrome（底栏）不跟列走，读 `windowChromeInfo`（`LocalWindowChromeInfo`）。
  所以 1280 宽的窗口里，600 宽的 shell 列就是 Medium，460 宽的详情列就是 Compact，和它们在
  同尺寸的独立窗口里长得一模一样。
- 例：Memories（2026-10-04）的档位只看自己的容器。它在自己的 BoxWithConstraints 里用纯函数
  `memoriesLayoutFor(宽, 高)` 分三档：手机两态 / Medium 放大两态 / 对开，门槛 600 / 900，另加
  「右栏 ≥ 360、对开的封面不小于 Medium」；高 < 480 先出局，走对开结构、封面下限更低。所以开着
  详情列的 1280 窗口里，它就是 Medium。档位之间**展品永不变小**：Medium 的下限取手机在同一高度
  的值，对开给的永远不少于 Medium，窗口拖过 600 或 900 时封面和徽记只会不变或变大。代码：
  `ui/memories/showcase/MemoriesLayout.kt`（`MemoriesLayoutTest`，原型导出的黄金数据）。
- 代码：`ui/experience/WindowAdaptiveRuntime.kt`（`rememberYoinWindowInfo`、`forPaneWidth`、
  `resolveShellChromeForm`）。

### 2. 一窗一栏
- 每个 Activity 窗口恰好一条 Button Group，归窗口所有，横跨整窗、居中限宽。**列不拥有 chrome**。
- 栏的形态 = 窗口形态（竖屏底栏 / 挖孔带分离式 / 居中底栏）× 内容姿态（导航 / 详情 / 合体）。
  三种姿态都是同四个槽位的 dp 插值：
  - 导航：[Home][pill][Library]
  - 详情（整窗详情页）：[Play ▾][外提动作][pill 200]
  - 合体（Wide 开着详情列）：[Home][Library][pill 伸缩][Play ▾][随机] —— 导航在 shell 那侧，
    页面的 Play 在详情那侧，pill 横跨分隔线；上限从 600 放宽到 720。
- 姿态值由宿主驱动（shell 的 morph Animatable / 详情列的开合 spring / 预测返回的 scrub），
  组件自己从不动画。
- 高度跟形态走：竖屏底栏 68（按钮 48），居中 / 合体栏 60（按钮 44）；只在 `FloatingBottomBar`
  一处定义，页面让位用 `LocalShellChromeInsets`。
- 栏的每个槽宽是一个纯函数（`resolveBarGeometry`，有单测）：pill 折起时（无播放 / NP 侧栏）
  栏面**正好包住**其余槽，任何一帧都不留空洞；隐形的姿态孪生不参与组合（不抢点击）。
- 代码：`ui/component/YoinButtonGroup.kt`、`FloatingBottomBar.kt`、`YoinChromeGroup.kt`。

- 手机横屏（`EdgeSplit`）的胶囊永远在挖孔那一侧：手机转向另一边时，挖孔跑到右边，Home / Library
  胶囊和正在播放 pill 跟着到右边（`EdgeSplitSide`，`rememberEdgeSplitSide`），内容的让位、进出场
  和收起的方向都按这一侧走；没有挖孔时在左边。页面底色铺到屏幕边缘，胶囊让出的那条带也是渐变，
  不露白底。展开的横屏播放器里，封面那一侧避开挖孔；另一侧的歌词被挖孔挡一点可以接受。

### 3. 分栏是同一窗口里的列，不是第二个窗口
- Wide 且高 ≥ 480（`YoinWindowInfo.hasDetailPane`）的整窗：详情页作为 shell 窗口里的**右列**打开
  （Navigation 3 的子栈），不再启动 Activity，也不再用 Activity Embedding。
  这样一条栏才能横跨两列、NP 侧栏才能滑到两列旁边、分隔条才是我们自己的 M3 拖动把手、
  开合/拖动/返回才能和全 app 共用一套弹簧。
- 列宽来自**一个**纯函数（`resolvePaneBudget`）：窗宽 − 24dp 槽 = 内容宽；shell 份额默认 0.45，
  ≥ 1080 的窗口保证 shell ≥ 600；两列都不小于 360（一个手机列）。把手 1:1 拖动，只做钳制，
  没有吸附、没有弹簧。
- 两列之间没有分隔线：列里的页面和中间的槽都画 shell 的同一种中性页面底色（不带封面色、不随播放
  律动，`LocalSharedPageBackground`）。底色是纵向渐变，参数相同就无缝相接，两列读作一整块，只剩
  中间的拖动把手。封面色留在页面自己的主视觉、Play 键上。
- 更窄的窗口（Compact / Medium / 任何高 < 480）照旧推入 Activity；Wide → 窄时列里的顶层页
  自动变成推入页（NP 正开着时不推，播放器留在最前）。反向不转换：窄窗里已推入的详情 Activity
  在窗口长到 Wide 后仍是整窗推入页，直到返回为止；之后新打开的详情才进列。Activity Embedding 只留给 Settings 这类"独立任务"的 list-detail。
- 代码：`ui/navigation/pane/`（`PaneSplit.kt`、`DetailPaneHost.kt`、`DetailPaneEntries.kt`）、
  `YoinNavHost.kt` 的列布局、`ui/detail/DetailHostMode.kt`。

### 4. Now Playing 只有一条状态链，所有尺寸共用
- `Closed → Panel → Full`。Panel = 从右侧推出的手机宽侧栏（宽 = clamp(窗宽 × 0.5, 360, 420)），
  宿主内容让位继续可用；Full = 整窗播放器：Compact 是手机、Medium 是放大的手机、Wide 是双栏。
- Panel 可用 = 高 ≥ 480 且 窗宽 − 侧栏 ≥ 320（Medium 和 Wide 一视同仁）。不可用就跳过 Panel
  直接 Full。右上角的全屏/收回键只在"存在 Panel 可退回"时出现。
- 返回链：Full → Panel → Closed（手机的 Expanded 舞台另算一级）。横竖屏切换保留用户的
  Panel/Full 选择；从 Compact 长大（展开折叠屏）落在 Full。
- 侧栏旁要留的最小宽度由宿主给：普通是一个手机列 320；**开着详情列时是两列 + 槽 = 744**
  （`TwoColumnsMinWidth`）。放得下就 Panel、两列整体让位；放不下（< 1164 的 Wide 窗）
  NP 直接 Full。
- 页面永远可见：在 NP 里点"前往专辑"——侧栏放得下就从 Full 退回 Panel 再开列；放不下就
  收起 NP 再开列。
- 侧栏进出：滑动距离 = 侧栏自身宽度、Standard 空间弹簧、不淡入淡出——和旁边内容让位的弹簧、
  距离完全一致，两条边始终贴在一起（`OverlayPlayerVisibility(endTravel)`）。拖走或返回手势
  提交后，退场从手指留下的位置继续，不先弹回；退场结束后才清掉拖动量和 Full 标记，所以下次
  打开一定从侧栏开始。RTL 下从起始边进出。
- 代码：`ui/nowplaying/NowPlayingPresentation.kt`（纯函数 + 单测）、`NowPlayingOverlayHost.kt`。

### 5. 列旁的内容按列宽重新读档
- 原则 1 的推论，单独列出因为最容易漏：任何"让位"都必须伴随 `forPaneWidth`，否则旁边的内容
  以为自己还是整窗（6 列网格挤进 400dp）。提供者必须**常驻**树中、只换值，不能按条件包裹
  （包裹会重建页面、重排返回回调顺序）。
- 让位是可逆的、逐帧的：侧栏被手势带走多少，旁边内容就收回多少（`NowPlayingPanelMotion`），
  两者之间永远不露窗口底色；侧栏的圆角和阴影跟着侧栏容器一起走。
- 档位怎么跟：**换档永远不发生在弹簧中途**（整块 section 重排一次要几百毫秒，落在弹簧里弹簧就跳帧）。
  NP 侧栏在开始时就换档（按侧栏静止宽度算）；详情列打开时在点击那一帧换档、等帧率平稳后弹簧
  才起步（`DetailPaneState.requestColumn` / `awaitPrewarm`），关闭时等弹簧落定后才换回
  （`holdsColumn`）；把手拖动是手指驱动，按实时宽度换档（`rememberColumnWindowInfos`，
  `derivedStateOf` 只在跨断点那帧重组）。
- 新开的列先以空白页面滑入，页面在列落定后才构建并淡入（`DetailPaneState.pageReady`）：
  详情页首次组合 + 布局 + 光栅化要好几帧，放在弹簧里同样会跳。推入页的底栏 morph 也一样：
  跟页面 96dp 滑入同一拍起步（`slideReleased`，首个重帧已提交之后），不在 reveal 时起步。
- 列的生命周期：列里页面的 ViewModel 跟列一起结束（`DetailPaneViewModelStoreOwner`——清栈不会
  pop entry）；切换账号即关列；Library 搜索结果开列时先收起全屏搜索（保留查询）；Memories 和列
  同时开着时底栏不隐藏（它承载列页面的 Play）。

### 6. 高度按梯子让：先收间距，再动内容；点封面必须看得见变大
- 固定高度的页面（NP 各形态、Memories 卡）的槽位由**一个纯函数预算**算出
  （`ui/nowplaying/NowPlayingBudget.kt`，有单测）：内容最小值按当前字号量出来
  （`NowPlayingMetrics.kt`），每段间距是一对（标称，最小）。页面里不再出现手加的预留常数
  （以前的 392 / 450 漏算过 16dp，侧栏那行歌词被截底）。
- Memories 卡同理（2026-10-04）：文字高度用 TextMeasurer 按当前字号量，不估。卡片的摘录槽量出来后
  只换候选（更少的整句 → 只剩署名），永不截断；Medium 每张卡的标题、专辑行、摘录和预告一起量，
  展品和预告之间的空隙封顶 120，多出来的按余量最少的那张卡算，1:1 分给顶部和底部；对开在每个间距档
  各量一遍引文，梯子先收间距、再把封面往下降（对开到 200，手机横屏到 88），整叠卡共用一个封面，
  每张卡的封面顶边同位。代码：`MemoryCardFace.kt`（`rememberCardMetrics`）、`MemoriesSpread.kt`
  （`rememberSpreadDeckFit`）、`MemoriesLayout.kt`（`balanceMedium`、`fitSpreadCover`）。
- **让位梯子**，恢复时倒着走：
  1. 弹性余量（歌词窗口超出最少行数的部分、双栏的居中余量、一行歌词区多出的高度——行在区内居中）
  2. **下层间距**（封面以下：控件、标题、胶囊各自的余量）
  3. **上层间距**（顶栏 56→48、封面下 16→8、tabs 余量）
  4. 紧凑形态（单列：歌手 + 标题两行 → 并成一行；双栏标题两行 → 一行跑马灯）
  5. 歌词换形：列表 → 一行可点的歌词（手机 / 侧栏 / 手机横屏，标题这时也是一行）；放大态先让封面缩到 168 再换形
  6. 封面缩到地板；双栏标题收进顶栏副行
- **歌词行数按 `LyricsDisplay` 自己的几何算**（22% 锚点、12dp 内边距、底部 24dp 渐隐）：
  「两行」= 两句不被渐隐吃掉的完整行（126dp），放大态「4 行」= 可见 4 行（165dp）。
- **永不让**：两行播放控件、三个胶囊、一行标题、48dp 触控目标。宽度上：控件不跟封面变——
  双栏左栏恒按 312 排控件和胶囊，封面只吃剩下的高度（以前左栏跟着被高度压小的封面缩到
  170dp，丢了「下一首」和 Write）；胶囊按 M3 真实最小宽 58dp 判断能不能带字，永不丢。
- **点封面 = 看封面**，所有形态共用 ViewModel 的 Immersive（跨侧栏 ⇄ 全屏保留，不进返回链）：
  评分、tabs、歌词窗口和间距让给封面，封面至多长到 1.5 倍，**当前那一行歌词不让**。只有封面能长大 ≥ max(40dp, 12%)
  时封面才可点；否则它是纯图片（无点击、无按压、无点击语义），残留的 Immersive 自动收回。
  手机横屏也有了这个动作（评分行让位，封面长满高度）。

### 7. 动效一个家族
- 列的开合、NP 侧栏、栏姿态走 `YoinMotion.defaultSpatialSpec`；手指按着时 1:1，松手才弹簧。
  拖动把手例外：1:1 跟手、松手原地停，只钳制，无弹簧无吸附（原则 3，`PaneSplitState.dragBy`）。
  预测返回对页面只做整体缩放预览（0.9 + 28dp 圆角），不 scrub 页面布局；唯一例外是 NP 侧栏关闭时
  旁边内容随侧栏位移 1:1 收回宽度（原则 5，只在 layout 阶段读，档位不变）。推入/弹出用 AOSP 的
  96dp + EMPHASIZED 450ms（`BackMotionTokens.EnteringStartOffset` / `PostCommitDurationMs`）。
- 每个值只有一个 settle owner（列的开合 = `DetailPaneState.openFraction`）。
- **逐帧的值只在 layout / draw 阶段读**：手势进度、弹簧值、把手位置不进 shell 级的组合。
  列宽在 `DetailColumnsLayout` 的 measure 里算，侧栏让位在 `besideNowPlayingPanel` 的
  layout 里算，栏的槽宽在栏自己的子组合里算。组合只在档位、开/关这类离散值变化时发生。

### 8. 规则之外才写特例
- 新增 `== Wide` / `!= Compact` 之类的散落判断必须收口到 `WindowAdaptiveRuntime` 或
  `NowPlayingPresentation` 的纯函数并加单测；页面只读 `LocalYoinWindowInfo` 和
  `LocalShellChromeInsets`。
- 新页面上线前回答：它在哪个容器里（窗口 / 列 / 侧栏旁）、容器形态是什么、它要不要让出宽度、
  它的返回归谁（`ShellBackResolver` / 列的子栈 / 自己的 Activity）。答不上来就不是适配问题，
  是设计没做完。
- 同一窗口里多个返回主人时，优先级靠**门控**表达，不靠挂载顺序：返回处理器按注册先后排，
  后挂载的列会压过先挂载的 NP。详情列的处理器挂在一个只在它拥有返回时才启用的子 dispatcher 上；
  Home 的编辑态也按 `ShellBackOwner` 门控。
- 页面自己的返回键（左上箭头）只作用于页面本身，走页面的提交编排（`requestBack`），不经过窗口的
  返回分发——否则开着的 NP 侧栏会抢走这一下。系统返回仍按 NP 优先。

## 各窗口一览（规则推导出来的结果，不是另一套规则）

| 窗口 | 形态 | 详情 | NP | 栏 |
| --- | --- | --- | --- | --- |
| 手机竖屏 390×844 | Compact | 推入 Activity（Pattern B） | Full（手机） | 竖屏底栏，导航⇄详情 morph |
| 手机横屏 844×390 | 高 < 480 → EdgeSplit | 推入 Activity | Full（横屏手机） | 挖孔带两段胶囊 |
| 折叠屏内屏 690×840 | Medium | 推入 Activity（PlainPush） | Panel 360 → Full 放大 | 居中底栏 |
| Pixel Tablet 竖屏 800×1280 | Medium | 推入 Activity（PlainPush） | Panel 400 → Full 放大 | 居中底栏 |
| 1/2 分屏 640×800 | Medium，Panel 不可用 | 推入 Activity | 直接 Full 放大（4 行歌词预算） | 居中底栏 |
| Pixel Tablet 横屏 1280×800 | Wide | **右列** 600 ｜ 24 ｜ 656，把手可拖 | Panel 420 → Full 双栏 | 合体栏 ≤ 720 横跨两列 |
| 桌面窗口 1440×900 | Wide | 右列 637 ｜ 24 ｜ 779 | Panel 420 → Full 双栏 | 合体栏 |
| 1280 横屏 + NP 侧栏 | 剩 860 仍 Wide | 两列 376 ｜ 24 ｜ 460，均读 Compact | Panel | 合体栏居中在 860 内 |
| 桌面窗口 1000×700 + 详情列 | Wide | 右列 | 侧栏放不下两列（580 < 744）→ 直接 Full 双栏 | 合体栏 |

## 本次推翻的旧裁决（记录在案）

- 「Wide 直接双栏、无侧栏无全屏键」（断点交接 §3.4 / §14.1）→ 改为原则 4 的统一链。
- 「shell ↔ 详情用 Activity Embedding、分栏窗格里的详情没有底栏、不要在 Compose 里写两栏」
  （断点交接 §2.3 / §13，predictive-back skill 的 Pattern A 分栏条目，2026-07「split is settled」）
  → 改为原则 3。理由：两个 Activity 窗口物理上无法共享一条栏；系统分隔条拖动后 calculator
  会把比例改回去且颜色退成库默认黑色；在列模型里这些问题不存在。
- 「分栏窗格的 NP 占满本窗格」→ 不再有窗格，NP 按窗口规则。

## Feed 密度（Home，2026-10-03）

owner 要求「各个 Home 组件在各种形态屏幕下的信息密度，不能过高过低」。原则 1、5 落到 Home 上就是下面这几条（实现：`ui/experience/FeedUnits.kt`、`ui/home/HomeFeedDensity.kt`、`ui/home/HomeFeedFrame.kt`，Jump Back In 的版式生成在 `ui/home/HomeJbiTemplate.kt`；Home 新增的密度判断只进这几个文件，并配单测）。

- **数量跟宽度走，卡片内部不缩放。** 封面（Activities 96 / 80 / 48、JBI 1×1 文字块、RA 52 / 82）和字号在所有形态下相同；容器变宽时加条目、加列，不放大内容。
- **Library 网格（2026-10-05 owner：Fold 内屏一行只有 5 个、元素太大）**：Compact 以上不再夹 720dp，铺满本列、32dp 页边；Albums / Artists 统一 `GridCells.Adaptive(96dp)`，格子比手机的 ~118dp 略小——600 → 5 列、800（平板竖屏）→ 6、~832（Fold 内屏）→ 7、1280 → 11。手机横屏（高 < 480）仍是 100dp。
- **Recently Added（同日 owner：折叠屏和平板上应该更长）**：feed 单元 ≥ 3 时曲目 4 行（手机 2 行），专辑卡两张一叠，和 4 行曲目同高；卡片不放大。收录窗口 7 天 → 30 天。
- **唯一输入是所在容器的静止宽度，折算成离散的 `feedUnits`（N）。** 内容宽 C：Wide 为 W − 64，其余 min(W, 720) − 32。非 Wide：C < 310 → 1，C < 448 → 2，否则 3–4；Wide：⌊(C + 10) / 140⌋ 夹在 5–8。N 写在 `YoinWindowInfo.feedUnits`（`rememberYoinWindowInfo` / `forPaneWidth` 计算），高 < 480 的手机横屏仍走横屏档。Home 不再用 `layoutMode` 判断密度。
- **Activities：** N ≤ 2 是手机构图（N = 1 时支撑行改成两张等宽小卡），N ≥ 3 是单元网格（small 1 单元，wide / hero 2 单元，N ≥ 7 时 hero 可占 3 单元）。条目数 5 / 7 / 8 / 10 / 12 / 13（N = 3…8）。**错落感是 owner 裁决**：每个 N 有一组手工挑选的行组合（第 1 行 hero 后递减，第 2 行接缝尽量与第 1 行错开），按 hero 的实体身份做确定性种子挑选——同一份数据每次结果一致，不做整齐划一的交替网格。**条目不够时**（W8，owner 2026-10-05 晚）：从尾巴往前少——先少条形卡，再少第 2 行的尾巴；前面的卡保持各自的跨度和单元线，供给断掉的那一行由最后一张卡拉宽到行尾（小卡拉到 ≥ 2 单元就按宽卡画；条形卡按份数拉），不留右侧空洞。手机构图（N ≤ 2）和手机横屏同一规则：缺的槽位把宽度让给这一行最后一张卡。
- **Jump Back In（2026-10-04 改）：** N ≤ 2 与手机横屏仍是 3 列 / 6 列的手机路径，取前 12 格，逐像素不变。N ≥ 3 用 `feedCoverColumns` 列（每列 ≥ 112dp，⌊(C + 12) / 124⌋ 夹 3–10），3 列 4 行、4–5 列 3 行、更宽 2 行；封面 = clamp(0.84 × 列宽, 100, 128)，所有封面同大，宽屏只加列。错落不靠放大（owner 否掉了 2×2）：每列（横放卡的两列算一道）从各自的高度开始，相邻两道的起点错开 0 / ¼ / ½ 行，第一道顶格；评分 / 笔记卡按种子竖放 1×2 或横放 2×1；竖放卡左右两列与卡片同高起步（评语旁边是邻列下一行的封面）；横放卡的文字在右边，它的右邻低半行起步，评语只和自己的封面对齐，有右邻的横放卡固定在第一行（否则右邻上一行的标题会落在评语旁）；两张卡至少隔一列。同一份数据结果相同。ViewModel 最多交出 28 张封面 + 2 张信号卡（2026-10-05 为行数预设从 24 提高；持久化的封面池 14 专辑 / 10 曲目 / 10 歌单），卡片不够时减一行。以上都是 L 档，其它档见下条。
- **行数预设叠在宽度档位上（2026-10-05，H5）：** 宽度决定列数、封面大小和 L 档的版式；用户选的预设（S / M / L / XL，按账号存，规则见 `docs/design.md`「主页 › 就地编辑 › 行数」）只在这个版式上加减**行**，从不改列数、封面大小或卡片内部——「数量跟宽度走，卡片内部不缩放」不变。每个屏幕把预设翻译成自己的版式（`ui/home/HomeRowPresets.kt`，纯函数 + 单测；L 逐像素等于有预设之前），版式相同的相邻预设合成一个停靠点。档与档嵌套：每一档都放得下下一档的全部卡片，所以行数变化能从底部逐行长出 / 收起，不用整块淡入淡出。
  - **Jump Back In：** 手机路径（N ≤ 2，3 列打包货架）2 / 3 / 4 / 6 行，L = 今天的 3 × 4 = 12 格；手机横屏（6 列）1 / 1 / 2 / 3 行。平板模板（N ≥ 3，L = `jbiTemplateRows(列数)`）：S = L − 2、M = L − 1、XL = L + 1，最少 1 行（所以 2 行的宽窗 S = M）。L 模板（错位、种子、卡片位置）原样不动，其它档从它推出来（`jbiPresetLayout`）：**XL** 在每一道底部追加一整行封面，按阅读顺序接着池子里还没上的封面，凑不满一整行就不加；**M、S** 从底部逐行裁掉，信号卡在自己那一道里上移，只剩一行放不下竖卡时躺成横卡（先跨右邻、不行跨左邻），超出信号卡上限的低排名那张离开，空出来的格子由被挤掉的封面按原阅读顺序补上，偏移不变。信号卡上限：只有一行或不足 9 格时 1 张，否则 2 张（`jbiSignalLimit`）。打包路径（手机 / 手机横屏）每档按「行数 × 列数」格重新打包；卡片少到连两行模板都排不出的平板退回普通列网格，那里所有预设都是同样的 12 格（只有一个停靠点，不显示把手）。最深的是 10 列 × 3 行的 XL：减去两张信号卡 = 26 张封面，在 28 张供给之内。
  - **Activities：** 手机构图（N ≤ 2）S = 大卡，M = + 一行「小 | 宽」，L = + 条形卡，XL = 在条形卡前插一行镜像的「宽 | 小」（L 的条形卡长成宽卡）；N = 1 每行两张等宽小卡；手机横屏 S = M = L = 一行，XL 两行。单元网格（N ≥ 3）S = 第 1 行，M = 第 1–2 行，L = + 条形卡（今天），XL = 条形卡之前加一行 `activityExtraRow(N, 第 2 行)`：在所有「1 / 2 单元」拆法里取内部接缝和第 2 行重合最少的，再取卡最少的，再取宽卡在前的——N 3 `[2, 1]`（第 2 行 `[1, 2]`）、N 4 `[2, 2]`（第 2 行 `[1, 2, 1]`）、N 5 `[2, 2, 1]`，读作第 2 行的镜像。历史太短、两档放的卡一样时这两档合并。
  - Recently Added、Rediscover 没有预设：前者必须保持一张专辑卡高（曲目格永远 2 行），后者是货架。
- **Recently Added：** 曲目格宽 G = clamp((C − 14) × 2.6/3.6, 226.8, 340)，只在 layout 阶段读；shelf 出血到容器真实边缘（不再停在 720 列边、在页中硬切）。**平板加列（2026-10-05，owner「在平板端可以多放一点内容」）**：按静止的 `feedCoverColumns`（不按实时宽度）定曲目列上限——手机（N ≤ 2）和手机横屏 2 列；≤ 4（600dp 折叠屏）2 列；5–7（800dp 平板竖屏 C 688，Wide 到约 1044dp）3 列；≥ 8（1280dp 平板横屏 C 1216）4 列；行永远 2 行。3 列以上曲目格宽 = 整数个 166dp 格 + 8dp 间距（3 列 514、4 列 688dp），专辑货架相应 12 / 16 / 20 张；实际列数只取刚好排满两行的数（4 首在能放 3 列的地方仍是 2 × 2，宽度还给专辑）。实现 `recentlyAddedTrackColumns` / `recentlyAddedGridColumns` / `recentlyAddedGridWidth`（`HomeFeedDensity.kt`）。
- **页面框架：** 列表满宽，页边是它的 contentPadding——Capped 16dp 且内容 ≤ 688dp，Wide 32dp，横屏 24dp；容器宽于本档静止宽度时（弹簧途中）内容贴起始边，不居中成孤岛。Tabletop 可以静止在 840dp 以上，所以它的框架始终居中。页边类别变化时在新旧公式之间按空间弹簧插值，中途反向从当前位置出发。
- **离散的值换档，连续的值在 layout 阶段算。** N 只在静止宽度跨阈值时变（弹簧起点那一帧），变化时 section 淡入淡出（淡出层带着自己的条目、不参与高度），高度用只管高度的差值弹簧（`springHeight`）过渡——不用 SizeTransform，它的宽度动画追不上正在收窄的容器，超出部分会被父布局居中、把 section 推出起始边；列宽、封面、G、页边按实时宽度在 layout 阶段算。列宽在弹簧、手势拖动 NP 侧栏或把手中移动时，以及页边插值进行中，Home 各 section 的位置弹簧关闭（`LocalPaneWidthInMotion` / `HomeFeedFrame.isBlending`），1:1 跟随，不追赶。
- **手机不动。** 360dp 及以上的手机（N = 2）与横屏档逐像素等于此前。窄于 342dp 的容器（折叠屏开 NP 侧栏后的 Home 等）是 N = 1，支撑行改为两张等宽小卡；342–359dp 的极窄手机 RA 曲目格不小于 360dp 手机的宽度。

本节推翻的旧裁决：Medium 固定 6 条（2026-07-27）、Wide 固定 10 条的 3:2:1 tapestry（A-prime 2026-07-28）→ 改为条目数随 N，Wide 的错落改由行组合调色板提供。2026-10-04：Jump Back In 的 3 / 4 / 6 列跟列宽（封面到 160dp）→ 改为手机大小的封面列 + 错位列版式（owner：「错落感不够，要考虑到横向和纵向」，折叠屏和平板横屏的单个封面太大，2×2 大封面「好奇怪太大了」）。
