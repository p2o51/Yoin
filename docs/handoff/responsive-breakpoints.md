# Yoin 断点适配 · 实现交接（Claude Cowork → Claude Code）

> 2026-09-30，由 Claude Cowork 写。用户用中文交流，会用 ultracode 一次跑完。
> 设计来源：claude.ai Design 画布「Yoin 断点原型」（私有链接，Claude Code 打不开）。每块画板的渲染图和源文件已经放在本地，见 §10。
> 仓库规则照旧：`AGENTS.md`（动效必须 spring、MD3 Expressive、back surface 分类、public Composable 要 `@Preview`、ktlint）。`docs/design.md` 是 UI 决策的最终来源，这次的决定要同步写进去（WS7）。

---

## 0. 开工前必读

1. **工作区不干净。** 当前分支 `codex/provider-capability-ui` 上有大量未提交改动（provider capability、`SeamDissolve.kt` 溶解等），都不属于本任务。先 `git status`，不要回滚、不要重新格式化这些改动；开工前问用户：先把它们提交掉，还是在它们之上新开 `feat/responsive-breakpoints`。
2. **溶解是另一条线**（`docs/handoff/dissolve-design.md`），这次不动。Medium+ 的底栏换了位置，它仍然必须是 shell 拥有的浮层，不能塞进滚动内容里，以后要能挂同一个 seam。
3. **尺寸以画板源文件为准**：`.dc.html` 里的 inline style 就是规格，1 px = 1 dp。画板里的颜色是示意色，代码里一律用现有 token（`FloatingBottomBar` 的容器色、`NowPlayingPill` 的 primaryContainer + primary@0.25 正弦边进度等）。
4. **折叠屏展开 / 平板竖屏的 Now Playing 已定为 Spotify 式**：先开手机宽的侧栏，全屏键进放大的手机（画板 `FoldNPPanel`、`TabletPortraitNPPanel` → `FoldNPSingle`、`TabletPortraitNP`），见 §3.4。画布上的 F1 / F3 / T2–T4 是备选，不做。
5. **不改**：所有竖屏手机页面（画板里标「现状」的）、Tabletop 半折态、predictive back 架构、动效语言。
6. **原生 API 优先**：窗口、姿态、挖孔、分栏、返回、动效都有官方 API，对照表见 §13。自己写替代品要写明原因；不加第三方库；`material3Adaptive` 保持 1.2.0（1.3 要 compileSdk 37）。
7. **易错细节见 §14**，尤其 §14.1（分栏里的 Now Playing）。这份交接和画布便签不一致时，以交接为准。

---

## 1. 判定口径（先统一，再改页面）

现状：`LayoutMode` 按窗格宽度分（Compact < 600、Medium 600–840、Wide ≥ 840，另有 Tabletop）。问题是手机横屏按宽度读成 Medium / Wide（780 → Medium，844 → Wide），于是拿到了 rail 和桌面档，同是手机横屏跨 840 两边长得还不一样。

在 `ui/experience/WindowAdaptiveRuntime.kt`（`isDualPaneNowPlaying` 旁边）加一个唯一的判定源，shell、详情 Activity、各页面都读它：

| 形态 | 条件 | 用在哪 |
| --- | --- | --- |
| `PortraitBar` | 宽 < 600 且高 ≥ 480；以及 Tabletop | 现状底栏，不变 |
| `EdgeSplit` | 高 < 480（`!isHeightAtLeastMedium`），不管宽度 | 手机横屏：分离式 Button Group，住进挖孔带（§2.2） |
| `CenteredBar` | 宽 ≥ 600 且高 ≥ 480 | 竖屏那条底栏居中、限宽 600（§2.3） |

再提供一个 `LocalShellChromeInsets`（`PaddingValues`），页面用它给 Button Group 让位：

- `PortraitBar` / `CenteredBar`：bottom = 栏高 + 下边距 + `navigationBars.bottom`。
- `EdgeSplit`：start = **84dp**（8 + 64 + 12），左边若有三键导航栏再加 `navigationBars.left`；摄像头在右边时 end = 右侧挖孔 inset。

`LayoutMode` 是窗格相对的，Activity Embedding 分栏里每个窗格读自己的宽度，这点保持。

---

## 2. WS1 · Button Group（shell + 三个详情 Activity）

涉及：`ui/navigation/YoinNavHost.kt`、`ui/component/YoinButtonGroup.kt`、`NowPlayingPill.kt`、`PlaySplitButton.kt`、`FloatingBottomBar.kt`、`ui/detail/DetailBottomBar.kt`、`ui/navigation/YoinNavRail.kt`（删除）。

### 2.1 竖屏（PortraitBar）
不变。

### 2.2 手机横屏：分离式，住进挖孔带（EdgeSplit）
画板：`LandscapeHome`、`LibLandscape`、`AlbumLandscape`、`AlbumLandscapeRoomy`、`ArtistLandscape`、`PlaylistLandscape`，顺序对比见 `GroupOrder`。

- **结构**：两段胶囊，都贴屏幕左边。x = 8dp（就在挖孔那条 inset 带里），宽 64dp（padding 6，按钮宽 52，圆角 32）。容器色和阴影同底栏。
- **顺序已定（方案 A）**：挖孔上面是导航 / Play，下面是正在播放。理由：往下拖收起 Now Playing 正好落回下面的 pill（和竖屏一致），Home → Play 在同一格变形。
- **切分**：从 `view.rootWindowInsets.displayCutout?.boundingRects`（或 `WindowInsetsCompat.getDisplayCutout()`）里取贴左边的那块。上段 = 上边距 14 到 `rect.top − 8`；下段 = `rect.bottom + 8` 到 窗高 − 16 − `navigationBars.bottom`。390 高的窗口里两段各 154。
- **左边没有挖孔**（摄像头在右 / 无挖孔机型）：下段位置不变（按"挖孔在正中、高 36"算出的同一位置）；上段往下长到离下段 8dp，多出来的高度放一个「随机播放」（`AlbumLandscapeRoomy`）。这时右边有挖孔，内容右边距 = 右侧挖孔 inset，别画进去。
- **上段 · 导航形态（shell）**：[Home][Library] 竖排，选中的高 82（圆角 26），另一个高 56，间距 4，图标 22。选中色同竖屏。
- **上段 · 详情形态**：竖向 Play 分体键：Play 52×94（圆角 26/26/8/8）+ ▾ 52×46（圆角 8/8/26/26），间距 2。▾ 菜单同竖屏（Shuffle play / Go to artist / Open in Spotify / Share）；被拿出来单独放的动作要从菜单里去掉，别重复。M3 的 Split Button 只有「主按钮 + 菜单」两段，没有多功能变体；要多放按钮就在这组里加段。
- **下段**：`NowPlayingPill` 的竖版：52 × (段高 − 12)，圆角 26。进度波浪从下往上涨（同一条正弦边，转 90°）；封面 34dp 在底部（内边距 9）；歌名 13sp SemiBold、歌手 11sp，竖排、从下往上读，放不下省略（跑马灯改成竖着跑也可以）。
- **接力**：shell ↔ 详情的变形照搬竖屏（`chromeProgress` 那套），只是换成竖轴：上段原地从 Home/Library 变成 Play/▾，下段不动。跨窗口的 bar 交接（`DetailLaunchMode`）在这个形态下也要对齐：详情 Activity 里的 `DetailBottomBar` 渲染同一个分离式组，位置分毫不差。
- **Memories 打开时整组隐藏**（竖屏也隐藏底栏；现状横屏的 rail 还在，要去掉）。
- Now Playing 的展开 / 收起以竖版 pill 为锚点。
- 注意 `SharedTransitionLayout` 的 lookahead 对无界约束很敏感（代码里有注释记录过 `ButtonGroup` intrinsic 测量崩溃），竖版 pill 的共享元素尺寸要有界。
- 左边缘是系统返回手势区：点按不受影响，不需要 `systemGestureExclusion`。

### 2.3 Medium 及以上：回到底部（CenteredBar）
画板：`FoldHome`、`TabletSplit`、`DesktopHome`、`AlbumDesktop`、`ArtistFold`、`ArtistDesktop`。

- 就是竖屏那条底栏组件本身：水平居中，宽 = min(窗格内容宽, **600dp**)，下边距 24（Wide 28）+ `navigationBars.bottom`，高 72（padding 8，按钮 56）。
- **导航形态**：[Home][正在播放 pill（伸缩，带歌名 / 歌手 + 波浪进度）][Library]，选中的那个宽 84，另一个 56 圆形。
- **详情形态**（专辑 / 歌手 / 歌单整窗）：[Play 分体键（伸缩）][▾ 56][额外动作 56 圆形…][正在播放 pill 定宽 200，带歌名]。额外动作：专辑 = 随机播放、前往歌手、分享；歌手 / 歌单 = 随机播放、分享。
- **删掉 `YoinNavRail`**：`YoinNavHost` 里的 `chromeUsesRail` 分支和 `Modifier.padding(start = YoinNavRailWidth)` 一起去掉。Tabletop 保持现状。
- **平板分栏**（Activity Embedding，≥ 840）：shell 窗格照常显示自己的底栏（导航形态，宽 = 窗格宽 − 40，最大 600）；**详情窗格不显示底栏**，[分享][Play 分体键] 直接放在 hero 里（`TabletSplit` 右半边），正在播放只在 shell 那条里出现一次。判定用 `ActivityEmbeddingController.embeddedActivityWindowInfo(activity)`（Flow，跟着分栏变化更新；老机型退回 `SplitController.splitInfoList(activity)`），不要只在创建时调一次 `isActivityEmbedded()`——MainActivity 自己处理 configChanges，旋转不重建。
- **shell 窗格宽度**：`TabletSplit` 画的是 shell 600 | 分隔条 10 | 详情 670；现在 XML 的 0.45 在 1280 宽只有 576，会读成 Compact。由 WS6 用 `SplitAttributesCalculator` 让 1280 宽时 shell ≥ 600（公式见 §7）。
- 页面内容底部留出栏高（`LocalShellChromeInsets`），最后一行不被盖住。

---

## 3. WS2 · Now Playing

涉及：`ui/nowplaying/NowPlayingScreen.kt`、`BottomPills.kt`、`PlaybackControls.kt`、`LyricsFullscreenPane.kt`、`LyricsActionBar.kt`、`NowPlayingOverlayHost.kt`。

### 3.1 16:9 矮屏（画板 `PhoneNPShortB`、`PhoneNPShortIdle`、`PhoneExpandedShort`）
- **门控**：歌词区放不下 2 行时（和歌词展开页工具键上移用同一个门控）。例：375×667，以及 Pura X / 折叠屏外屏这类宽屏（`PhoneExpandedShort` 的画板标题就是用户写的 “Pura X View（Fold 8）”）。
- **首页**：Lyrics / About / Note 那一行换成**一行当前歌词**（bold、primary、单行省略）。整行就是按钮，点一下 = 展开歌词。**不要单独的展开键**（用户：离歌词太远）。
- **歌词停住时的提示**：同一句持续 ≥ 8 秒（前奏 / 间奏 / 长音）→ 这一行交叉淡入成 [展开符号动画 + 「轻点以展开」]。符号动一遍（约 1.8 s：两道折线先向中线压约 1.8dp，再向外弹约 2.6dp，spatial spring）期间才显示文字，动完淡回歌词。
  - **频率：一天最多一次**（用户原话：“一天不要超过一次”）。按本地日期记在现有的偏好存储里，当天出现过就不再出现。
  - 系统动画缩放为 0：只显示符号 + 文字约 2 s，不动。
  - 符号用 `ic_yoin_unfold_more` 的几何（`M8 9.1 L12 5.1 L16 9.1` / `M8 14.9 L12 18.9 L16 14.9`，描边 1.5）。Yoin Symbols 的动效 painter 还没接进仓库，直接用 Compose `Canvas` 画两条 path 做动效，别为此加依赖。
- **歌词展开页**：tabs 改回文字，4 个歌词工具键挪到 tabs 同一行右侧，底部只剩标题。自动沉浸时工具键原地淡出，什么都不移动。

### 3.2 自动沉浸（所有尺寸，画板 `PhoneExpandedImmersive`、`FoldNPImmersive`）
播放中 + Lyrics 页 + 有同步歌词 + 5 秒无操作 → **只有** 4 个歌词工具键隐藏，别的都不变。工具键在底部时：淡出 + 槽位塌缩，标题下沉到原来工具键的底边，歌词往下长；工具键在顶部（16:9）：原地淡出。手动滚过歌词（回中键亮着）不触发；触摸 / 滚动 / 暂停 → 恢复。先核对现状，已实现就跳过。

### 3.3 控件永远排得下（所有布局，必做）
用户在 Pixel Tablet 竖屏（800×1280）实测：随机键被截一截，胶囊组里的 Write 直接消失。
- 原因一：`WidePlayingContent` 左栏是 `weight(1f) : weight(1.5f)`，800 宽只剩约 277dp。改完 §3.4 以后双栏只剩 Wide（≥ 840 的整窗或窗格，见 §14.1）在用：**左栏宽度改成按控件定，312dp（= 封面边长，见 `TabletNP`）**，右栏（歌词）吃剩下的；点封面放大时左栏最多长到 1.5 倍，但右栏不能小于 320dp。Medium 不再走双栏。
- 原因二：`BottomPills` 用 M3 `ButtonGroup`，`overflowIndicator = { _ -> }` 是空的，放不下的 pill 被静默丢掉。改成先按真实宽度量，放不下就整组收成纯图标 pill（标签在按下时展开——`PillButton` 已经有 `showLabel` 逻辑），Cast 也算进宽度；任何情况下三个都在。
- 原因三：`PlaybackControls` 顶行 [PLAY/PAUSE][Next] … [Shuffle] 会溢出。宽度不够时先收 PLAY 的水平 padding，再把控件从 56 降到 48；Shuffle 永远完整。
- 加 Preview / UI 测试：左栏 240dp、fontScale 1.3，证明不裁切、不丢按钮。

### 3.4 折叠屏展开 / 平板竖屏：先开侧栏，再全屏（Spotify 式）
画板：`FoldNPPanel`、`TabletPortraitNPPanel`（侧栏态）→ `FoldNPSingle`、`TabletPortraitNP`（全屏态）。用户看了 Spotify 2026 年的平板 / 折叠屏改版后定的：“先打开一个细的页面（其实就是手机页面）……展开之后，也是变成一个放大的手机那种类型”。

- **适用范围**：`LayoutMode.Medium`（600–840）且高 ≥ 480、并且**不在 Activity Embedding 分栏里**的整窗，即折叠屏内屏、平板竖屏、分屏半窗。窗宽 − 侧栏宽 < 320dp 时（例如 600–680 的分屏半窗）不开侧栏，直接进全屏态。分栏里的窗格（平板横屏、桌面窗口）不开侧栏，见 §14.1。Wide（≥ 840 的整窗或窗格）照旧双栏（已确认的 `TabletNP`）；Compact / Tabletop 不变。相应地 `isDualPaneNowPlaying` 收窄成只有 Wide（+ 现有高度门），它门控的所有地方——body 分发、drag-to-dismiss、stage back layer、共享元素、`NowPlayingOverlayHost` 里两对 `BackHandler` / `PredictiveBackHandler` 的 enabled 条件——都要跟着一致改。
- **侧栏态（点正在播放 pill 之后的第一态）**：
  - 从右边推出一块手机宽的侧栏：宽 = clamp(窗宽 × 0.5, 360, 420)，折叠屏 690 → 360，平板竖屏 800 → 400。满高，左侧圆角 28，带 NP 背景；shell 内容（Home / Library）让出这块宽度继续可用（内容区读成 Compact，用手机的布局）。
  - 侧栏里就是手机那一页（Compact 的 NP body 放进定宽容器）：封面 + 竖向评分 → Lyrics / About / Note → 歌词 → 两行控制 → 标题 → 胶囊。**歌词窗口吃掉剩余高度**，窗口越高行数越多（平板竖屏约 12 行），中间不会空。要确认 Compact body 在高窗口里真的是歌词区在伸缩，而不是留白。
  - 头部右上角一个「全屏」键（44 圆形，`open_in_full` 的两道斜箭头）。
  - 侧栏打开时，底栏里的正在播放 pill 以容器变换长成侧栏；底栏只剩 [Home][Library]，在左边内容区底部居中（`FoldNPPanel` 左半）。侧栏关掉时 pill 原路回来。
- **全屏态**：点全屏键 → 整屏变成放大的手机。折叠屏 = `FoldNPSingle`（列宽 460 居中：封面 380 + 竖向评分，歌词收成一行、整行点开，同 16:9 的做法）；平板竖屏 = `TabletPortraitNP`（列宽 640 居中：封面 520 + 竖向评分 104、tabs 行右端 4 个歌词工具键、约 6 行歌词窗口、控件 60）。右上角同一个位置变成「收回侧栏」。
- **返回**：一级一级退——全屏 → 侧栏 → 关闭。左上角 ▾ 直接关闭。侧栏上往右滑也能关。改之前先用仓库里的 `.claude/skills/predictive-back` 给侧栏归类：它还是 NP 的 ShellOverlayDown / Pattern C，只是多了一种向右收回到 pill 的方向。系统预测性返回、手势和按钮共用 NP 现有的那一个控制器，别另起一套曲线；说明补进那个 skill（skill 写明它和代码优先于 AGENTS.md 里较旧的返回章节）。
- 动效：侧栏进出、侧栏 ↔ 全屏都用 spatial spring；shell 内容让位的宽度变化同一条 spring。
- **尺寸切换**：NP 开着时折叠 / 展开 / 旋转，NP 保持打开：Compact → Medium（展开折叠屏）进全屏态；Medium → Compact 回手机 NP；其它进入 Medium 的情况默认侧栏。「侧栏 / 全屏」状态放进 NP 的 ViewModel / saved state（详情 Activity 旋转会重建，MainActivity 不重建）。
- **详情页也能开侧栏**：`NowPlayingOverlayHost` 在 shell 和每个详情 Activity 里都挂着；折叠屏上开着专辑页点 pill，专辑页同样让位、读成 Compact（§14.3）。

### 3.5 不改（用户已确认）
手机横屏 NP（`LandscapeNP`）、平板横屏 NP（`TabletNP`，只在 ≥ 840 的整窗或窗格出现，见 §14.1）、半折态（`TabletopNP`）、竖屏歌词展开页（`PhoneExpanded`）。只核对实现是否和画板一致，不一致再对齐。

---

## 4. WS3 · Home / Library

- **Home 手机横屏**（`LandscapeHome`）：高 < 480 时用单独的横屏档：标题 28sp；Activities 单行三卡（高 116）；Recently Added 单行（84dp 封面横排）。现状 844 宽读成 Wide 会走桌面档，要先按高度判。
- **Home Medium / Wide**（`FoldHome`、`DesktopHome`）：内容不变，只是换成底部栏，桌面档不再给 rail 让左边。
- **Library 手机横屏**（`LibLandscape`）：高 < 480 时统一用单行头部：[搜索 pill 208][chips 横滑][设置]，不显示标题（左边的组已经标着 Library）；格子约 100–108dp → 6 列，首屏两整行。宽度档管列数，高度档管头部。现状 780 宽走两行头部、844 宽走桌面档，要统一。
- 竖屏、折叠屏、平板分栏的 Library 沿用现状（`LibraryScreen` 里 Compact → `Fixed(3)`、Medium → `Adaptive`、Wide → 桌面档，这些不动，只在前面加高度判定）。

---

## 5. WS4 · 详情页（专辑 / 歌手 / 歌单）

涉及：`ui/detail/AlbumDetailScreen.kt`、`ArtistDetailScreen.kt`、`PlaylistDetailScreen.kt`、`DetailBackdrops.kt`、`DetailPullUpReshape.kt`。

- **竖屏不变**（`AlbumPhone`、`ArtistPhone`、`PlaylistPhone`）。
- **手机横屏**（`AlbumLandscape`、`ArtistLandscape`、`PlaylistLandscape`）：把竖屏 hero 横过来。头部行 = 返回 + 标题 + 副标题（一开始就是收起态，不要 156dp 的大标题栏）；左边封面 256（歌手是圆形肖像 220），右边放竖屏里封面下面的东西：
  - 专辑：Last Play | Avg.（bun）、Comment（单行）、`12 tracks · 50m`、流动曲名（当前曲高亮），上拉看完整列表（同竖屏的 pull-up reshape）。
  - 歌手：Releases | Follow（bun）、Popular；Discography 跟在下面随页面滚。名字只出现一次。
  - 歌单：Length | Owner、流动曲名；头部行右边 ⋮。
  - 背景图形（专辑箭头块 / 歌手风车 / 歌单叠层 V）做成贴着封面的闭合形状，不在中间切直边，也不伸到左边的组下面（从 x ≥ 84 开始）。
- **Medium 整窗**（`ArtistFold`）：歌手 = 宽 hero（肖像 180 在左，名字 32 / meta / Favorite 在右），名字只出现一次；**Play 只在底栏里**，hero 里不再放 Play；Discography 改 `Adaptive(150)` 填满宽度（现状是固定 156 的 FlowRow，690 宽右边空 166）。专辑 / 歌单的 Medium 沿用 hero 行 + 列表。
- **Wide 整窗**（`AlbumDesktop`、`ArtistDesktop`）：
  - 专辑：不再用 `yoinPageContentWidth()` 夹 720 居中；左身份栏 400（封面 360、Last Play | Avg.、Comment）+ 右完整曲目列表（最大 800）并排。
  - 歌手：hero 横在顶部（肖像 220 + 名字 48 + meta + Follow）；下面 Popular（480）| Discography（`Adaptive(150)`）并排。
- **平板分栏的详情窗格**（`TabletSplit` 右半）：没有底栏；hero 里放 [分享][Play 分体键]。
- Follow / Favorite 的文案跟 provider 走（Spotify = Follow，Subsonic = Favorite），不跟尺寸走。

---

## 6. WS5 · Memories

涉及：`ui/memories/MemoriesScreen.kt`。

- **16:9**（`MemShort`）：和 NP 同一个“放不下”门控：印章 148 → 112，标题降一级，提问句和 Write a review 并成一行。结构不变，卡内依旧不滚动（现状 667 高会溢出约 100dp，证据句和 Go to album 被挤掉）。
- **手机横屏**（`MemLandscape`）：还是一张卡、不滚动，拆两栏：左 = 标题 + 封面 + 印章；右 = Written by Yoin 的话 / 你的乐评 + 证据句 + Go to album；日期和圆点压成一行头部。Button Group 隐藏，左边只让出挖孔带。
- **Medium / 平板分栏**：480 的卡放得下，沿用现状。
- **Wide 整窗**（`MemDesktop`）：对开：左页身份 + 大印章（190），右页乐评全文；笔记卡回到卡面（手机上它们在「N notes」按钮后面），最大宽 1160 居中；Button Group 隐藏。

---

## 7. WS6 · Settings / 账号二级页

涉及：`ui/settings/SettingsScreen.kt`、`ui/settings/service/ServiceSetupScreen.kt`、`res/xml/main_split_config.xml`、`AndroidManifest.xml`（如需占位 Activity）、`YoinApplication.kt`（`RuleController` 在这里注册）。WS6 负责所有 Activity Embedding 配置。

- **账号二级页手机横屏**（`SetupLandscape`）：左栏固定（返回、hero、You'll need），不随输入法滚走；右栏可滚，表单放最前，What you get 挪到表单后面。
- **Settings Expanded**（宽 ≥ 840 且高 ≥ 600，`SettingsTablet`）：list-detail。左 = Settings 本身（账号卡改成竖排行，选中高亮），右 = 选中账号的二级页；Features 里的展开项（Gemini / NeoDB）也在右栏打开。实现：`main_split_config.xml` 加一条 `SettingsActivity → ServiceSetupActivity` 的 `SplitPairRule`（840 线）+ 占位页，**不要把两个 Activity 合并**。现在的注释写着“Settings 不参与分栏”，一起改掉。
- Settings 主页横屏：640 表单列居中，沿用现状。
- **分栏里的落点先实测**：平板横屏时 shell 一直在分栏里。Settings 从 shell 打开，又不匹配任何规则，默认会进 shell 所在的左栏（约 600）；而 `SettingsTablet` 画的是整窗 420 | 860。先在 Pixel Tablet 模拟器上确认 Settings 和 ServiceSetup 实际落在哪，再定规则。不要给 Settings 加 `alwaysExpand`：那样它永远不参与分栏，list-detail 就做不成了。原生规则凑不出整窗 420 | 860 时，停下来带截图问用户，别 hack。
- **分栏几何（给 WS1 用）**：`SplitController.setSplitAttributesCalculator` 按窗口宽算比例：窗口 ≥ 1080 时 shell = max(600, 0.45 × 窗宽)（1280 宽约 0.47），详情至少留 480；更窄的窗口照旧 0.45。`WindowSdkExtensions` < 2 的机器不支持 calculator，就用 XML 的 0.45。分隔条用 `DividerAttributes.DraggableDividerAttributes`（extension < 6 的机器就没有分隔条）。

---

## 8. WS7 · 文档

更新 `docs/design.md`：
- 「导航结构」「📱 大屏幕适配 / 响应式设计」两节：去掉“Medium+ 全窗转为左侧 Navigation Rail”，写入三种形态（竖屏底栏 / 手机横屏分离式挖孔带 / Medium+ 底栏居中限宽 600）、分栏规则（shell ≥ 600、分隔条、Settings list-detail）、`LocalShellChromeInsets`。
- 「Now Playing」一节：16:9 一行歌词（整行点开）+「轻点以展开」每天最多一次；自动沉浸规则；控件不裁切、不丢按钮；Medium 整窗先开侧栏再全屏（Spotify 式）；分栏窗格里 NP 占满本窗格；双栏只留给 Wide，左栏按控件定宽。

---

## 9. 建议的并行拆分（ultracode）

WS1 先落地（它定义 `ShellChromeForm` 和 `LocalShellChromeInsets`，别的都依赖它）；之后 WS2–WS6 并行，文件基本不重叠；WS7 最后。

| WS | 负责的文件 | 依赖 |
| --- | --- | --- |
| WS1 | `WindowAdaptiveRuntime.kt`、`YoinNavHost.kt`、`YoinButtonGroup.kt`、`NowPlayingPill.kt`、`PlaySplitButton.kt`、`FloatingBottomBar.kt`、`DetailBottomBar.kt`、删 `YoinNavRail.kt` | — |
| WS2 | `ui/nowplaying/*`（含侧栏本体和全屏态） | WS1 的判定；侧栏在 shell 这边的部分（内容让位、底栏收起 pill）写在 `YoinNavHost` / `YoinButtonGroup`，和 WS1 商量好接口，由 WS1 落地 |
| WS3 | `ui/home/*`、`ui/library/*` | WS1 的 insets |
| WS4 | `ui/detail/*Screen.kt`、`DetailBackdrops.kt`、`DetailPullUpReshape.kt` | WS1 的 insets |
| WS5 | `ui/memories/*` | WS1 的判定 |
| WS6 | `ui/settings/*`、`main_split_config.xml`、`AndroidManifest.xml`、`YoinApplication.kt`（分栏规则、比例、分隔条） | — |
| WS7 | `docs/design.md` | 全部 |

`WindowAdaptiveRuntime.kt` 只归 WS1 改，其它 WS 只读。

---

## 10. 设计稿（本地文件）

- 渲染图：`/Users/gpo/Developer/Yoin/docs/temp/responsive-breakpoints/boards/NN-名字.png`（1 px = 1 dp；用的是回退字体，布局和尺寸是准的）。
- 源文件：`/Users/gpo/Developer/Yoin/docs/temp/responsive-breakpoints/src/*.dc.html`（inline style 就是规格）和 `canvas.json`（画板标题、画布上的便签原文）。
- `docs/temp/` 在 `.gitignore` 里，不会进仓库。如果用 worktree 跑子任务，请用上面的绝对路径读。

| # | 画板 | 尺寸 | 说明 |
| --- | --- | --- | --- |
| 01 | `Main` | 390×844 | Compact · 手机竖屏 390×844 |
| 02 | `LandscapeHome` | 844×390 | Compact-height · 手机横屏 · 分离式 Button Group（挖孔上下各一段） |
| 03 | `FoldHome` | 690×840 | Medium · 折叠屏展开 · Button Group 回到底部 |
| 04 | `TabletSplit` | 1280×800 | Expanded · 平板横屏分栏 · shell 窗格底部 Button Group |
| 05 | `DesktopHome` | 1440×900 | Wide · 桌面整窗 · 底部 Button Group（居中限宽 600） |
| 06 | `PhoneNP` | 390×844 | Compact · 手机竖屏 NP |
| 07 | `PhoneNPShort` | 375×667 | Compact · 16:9 矮屏 · 方案 A 标题上移（未采用） |
| 08 | `PhoneNPShortB` | 375×667 | Compact · 16:9 矮屏 · 一行歌词（整行点按展开，去掉展开键） |
| 09 | `PhoneNPShortIdle` | 375×667 | Compact · 16:9 · 歌词停住 → 动态 symbol + 轻点以展开 |
| 10 | `LandscapeNP` | 844×390 | Compact-height · 手机横屏 NP |
| 11 | `TabletopNP` | 840×700 | Tabletop · 折叠屏半折 NP |
| 12 | `TabletNP` | 1280×800 | Expanded · 平板横屏 NP（左控制 · 右歌词） |
| 13 | `PhoneExpanded` | 390×844 | 手机竖屏 |
| 14 | `PhoneExpandedImmersive` | 390×844 | 自动沉浸 |
| 15 | `PhoneExpandedShort` | 375×667 | Pura X View（Fold 8） |
| 16 | `FoldNPImmersive` | 690×840 | Medium · 折叠屏展开 NP · 歌词自动沉浸 |
| 17 | `FoldNPPanel` | 690×840 | S1 · 折叠屏展开 · 先开侧栏（手机页面，Spotify 式）→ 全屏键 → F2 |
| 18 | `FoldNPSingle` | 690×840 | F2 · 折叠屏展开 · 全屏（放大的手机；右上角收回侧栏） |
| 19 | `TabletPortraitNPPanel` | 800×1280 | S2 · 平板竖屏 · 先开侧栏（手机页面，Spotify 式）→ 全屏键 → T1 |
| 20 | `TabletPortraitNP` | 800×1280 | T1 · 平板竖屏 · 全屏（放大的手机；右上角收回侧栏） |
| 21 | `FoldNP` | 690×840 | F1 · 折叠屏展开 · 双栏（之前的方案，备选） |
| 22 | `FoldNPStack` | 690×840 | F3 · 折叠屏展开 · 上下分区（备选） |
| 23 | `TabletPortraitNPUpNext` | 800×1280 | T2 · 平板竖屏 · 双栏 + 接下来（备选） |
| 24 | `TabletPortraitNPStack` | 800×1280 | T3 · 平板竖屏 · 上下分区（备选） |
| 25 | `TabletPortraitNPPoster` | 800×1280 | T4 · 平板竖屏 · 双栏左栏贴底（备选） |
| 26 | `LibPhone` | 390×844 | Library · Compact 手机竖屏（现状） |
| 27 | `LibLandscape` | 844×390 | Library · 手机横屏 · 单行头部 + 108 格 · 分离式 Button Group |
| 28 | `GroupOrder` | 600×490 | 分离式 Button Group · 上下顺序 A / B |
| 29 | `AlbumPhone` | 390×844 | 专辑 · Compact 手机竖屏（现状） |
| 30 | `AlbumLandscape` | 844×390 | 专辑 · 手机横屏 · 分离式 Button Group（上 Play · 下正在播放） |
| 31 | `AlbumLandscapeRoomy` | 844×390 | 专辑 · 手机横屏 · 摄像头在右：缺口合上，多出随机播放 |
| 32 | `AlbumDesktop` | 1440×900 | 专辑 · Wide 整窗 · 底部 Button Group 详情形态 + 身份栏 + 全宽曲目 |
| 33 | `ArtistPhone` | 390×844 | 歌手 · Compact 手机竖屏（现状） |
| 34 | `ArtistLandscape` | 844×390 | 歌手 · 手机横屏 · 分离式 Button Group |
| 35 | `ArtistFold` | 690×840 | 歌手 · Medium 折叠屏 · 底部 Button Group + 自适应唱片格 |
| 36 | `ArtistDesktop` | 1440×900 | 歌手 · Wide 整窗 · 底部 Button Group · Popular / Discography |
| 37 | `PlaylistPhone` | 390×844 | 歌单 · Compact 手机竖屏（现状） |
| 38 | `PlaylistLandscape` | 844×390 | 歌单 · 手机横屏 · 分离式 Button Group（与专辑同构） |
| 39 | `MemPhone` | 390×844 | Memories · Compact 手机竖屏（现状） |
| 40 | `MemShort` | 375×667 | Memories · 16:9 矮屏 · 印章 112，卡面不溢出 |
| 41 | `MemLandscape` | 844×390 | Memories · 手机横屏 · 一张卡拆两栏 |
| 42 | `MemDesktop` | 1440×900 | Memories · Wide 整窗 · 对开 + 笔记回到卡面 |
| 43 | `SettingsPhone` | 390×844 | Settings · Compact 手机竖屏（现状） |
| 44 | `SetupPhone` | 390×844 | 账号二级页 · Compact 手机竖屏（现状） |
| 45 | `SetupLandscape` | 844×390 | 账号二级页 · 手机横屏 · 左 hero 右表单 |
| 46 | `SettingsTablet` | 1280×800 | Settings · Expanded · list-detail（左设置 · 右账号页） |

---

## 11. 验收

每个 WS 做完，在对应尺寸上截图，和画板并排对照。

| 设备 / 窗口 | 看什么 |
| --- | --- |
| 手机竖屏（412×915） | 全部不变；16:9 以外不出现一行歌词 |
| 手机横屏，摄像头在左（ROTATION_90） | 分离式组：上导航 / Play，下正在播放，挖孔空着；内容从 84 开始；详情接力位置一致；Memories 打开时组隐藏 |
| 手机横屏，摄像头在右（ROTATION_270） | 缺口合上、多一个随机播放；右边内容不压挖孔 |
| 16:9（375×667，或 Pura X / 折叠屏外屏） | 一行歌词整行点开；停住 8 秒出提示、当天第二次不再出；Memories 不溢出 |
| 折叠屏内屏（约 690×830） | 底栏居中限宽；歌手宽 hero、唱片格填满；点 pill 先开 360 侧栏（左边 Library 仍可用、底栏只剩两个键），全屏键进放大的手机，返回一级一级退 |
| Pixel Tablet 竖屏（800×1280） | 底栏居中；NP 先开 400 侧栏、歌词填满中间；全屏是放大的手机；随机键完整、三个 pill 都在 |
| Pixel Tablet 横屏（1280×800，分栏） | shell 窗格 ≥ 600，底栏在 shell 窗格；详情窗格没有底栏、Play 在 hero；开着专辑页点 pill → NP 占满 shell 窗格（放大的手机），右边专辑照常；Settings list-detail（落点见 §7） |
| 桌面窗口（约 1440×900） | 分栏开着时两个窗格都读 Medium（shell 约 648 / 详情约 792），照 Medium 规则；Wide 整窗画板（`DesktopHome`、`AlbumDesktop`、`ArtistDesktop`、`MemDesktop`、`TabletNP`）用 1440×900 的 Preview 验收 |
| 手机横屏 180° 翻转（90° ↔ 270°，没有 configuration change） | 分离式组跟着挖孔换边 |
| 分屏半窗（约 640 宽） | NP 不开侧栏，直接全屏态 |
| fontScale 1.3、显示大小调大 | 任何控件行不裁切、不丢按钮 |

命令：`./gradlew ktlintCheck test assembleDebug`；新增 / 修改的 public Composable 都给 `@Preview`（带 `widthDp` / `heightDp`，覆盖上面几种窗口）；UI 测试用 `DeviceConfigurationOverride`（尺寸 / 字号 / insets），见 §13。

---

## 12. 用户的原话（按时间，决定都来自这里）

- “就是说相当于把 Home 那个完全竖过来就行，不过要避开中间的那个摄像头位置？或者可以做一个分离式的”
- “还是顶着 Safe Area，整个分区横跨 Safe Area，只不过是说，挖孔的地方给他空着，低于挖孔的地方是 Nowplaying，高的地方是导航”
- “横屏和 Medium 的情况我就感觉不太一样，因为其实高度不再寸土寸金了……不用放左边的”
- “这个展开的感觉离左边歌词太远，我觉得不需要了……一个动态的 symbol，symbol 动画进行时，同时显示：轻点以展开”
- “那个‘轻点展开’的频率要低一些……一天不要超过一次”
- “昨天我用 Pixel Tablet 竖屏进行测试，发现左边的随机按钮被截断了一点，下面三个 Button 也少了一个”
- “我觉得 Spotify 的做法可能会好一点，就是它先打开一个细的页面（其实就是手机页面）……然后 Spotify 给了一个展开键，展开之后，也是变成一个放大的手机那种类型”

---

## 13. 原生 API 对照（先用这些，再考虑自己写）

原则：窗口判定、折叠姿态、挖孔、分栏、返回、动效，全部走 Jetpack / Material 3 / WindowManager 的官方 API；仓库已经在用的就接着用。确实要自己写的地方（比如挖孔带里的分离式组，没有现成组件）也只是在官方 API 给的数据上画 UI。每处偏离都在说明里写一句原因。不加第三方库；新依赖只加 AndroidX 官方库，走 `libs.versions.toml`。

| 要做的事 | 用这个（括号里是仓库现状） | 注意 |
| --- | --- | --- |
| 窗口档位 / 高度判定 | `currentWindowAdaptiveInfo()` + `WindowSizeClass.isWidthAtLeastBreakpoint()` / `isHeightAtLeastBreakpoint()`（adaptive 1.2.0，`rememberYoinWindowInfo()` 已在用） | `ShellChromeForm` 也在这里算。不要读 `LocalConfiguration.screenWidthDp`，也不要自己拿 dp 比大小 |
| 折叠姿态 | `windowPosture.isTabletop` / `hingeList`（已在用） | 不要另接一套 `WindowInfoTracker` |
| 挖孔让位 | `WindowInsets.displayCutout` / `safeDrawing` | 不写死任何高度 |
| 挖孔具体在哪 | `WindowInsetsCompat.getDisplayCutout()?.boundingRects`（或 `view.rootWindowInsets.displayCutout`） | 跟着 insets 变化重算；90° ↔ 270° 直接翻转不触发 configuration change（§14.8） |
| shell ↔ 详情分栏 | Activity Embedding：`main_split_config.xml` + `RuleController`（已在用） | 不要在 Compose 里手写左右两栏去模拟分栏 |
| 分栏比例（shell ≥ 600） | `SplitController.setSplitAttributesCalculator`（公式见 §7） | 需要 `WindowSdkExtensions` ≥ 2；不支持就用 XML 里的 0.45。只按窗口尺寸算就不用手动刷新；以后要按 app 状态变，才需要 `ActivityEmbeddingController.invalidateVisibleActivityStacks()` |
| 分隔条（`TabletSplit` 的 10dp 把手） | `SplitAttributes.Builder.setDividerAttributes(DividerAttributes.DraggableDividerAttributes…)`（window 1.5.1 已有） | 需要 extension ≥ 6；不支持的机器就没有分隔条，别自己画 |
| “我是不是在分栏里” | `ActivityEmbeddingController.embeddedActivityWindowInfo(activity)`（Flow，extension ≥ 6），老机型退回 `SplitController.splitInfoList(activity)`（Flow） | MainActivity 自己处理 configChanges、旋转不重建，一次性的 `isActivityEmbedded()` 不会更新。`DetailBottomBar` 现有的同步判断可以留，新代码一律订阅 |
| Settings list-detail | `SplitPairRule` + `SplitPlaceholderRule` | 两个 Activity 不合并；Settings 在 shell 分栏里的落点先实测，别用 `alwaysExpand`（§7） |
| NP 侧栏的布局容器 | 先评估 `SupportingPaneScaffold`（`androidx.compose.material3.adaptive:adaptive-layout`，版本跟 `material3Adaptive` 走，1.2.0）：侧栏宽用 `Modifier.preferredWidth`，状态用 `MutableThreePaneScaffoldState`，由 NP 现有的返回控制器驱动 | 不用 `NavigableSupportingPaneScaffold`，它自带返回处理，会变成两个返回控制器。如果和 pill → 侧栏的共享元素变换冲突，就保留自研容器，写明原因 |
| 返回 | `PredictiveBackHandler` / `BackHandler`（activity-compose，已在用）+ 仓库里的 `.claude/skills/predictive-back` | 动任何返回 / 关闭行为之前先读这个 skill 归类 |
| 动效 | `MaterialTheme.motionScheme` 的 spatial / effects spring（已在用） | 不写 tween 时长；skill 里写明的 AOSP 复刻除外 |
| 组件 | M3 Expressive `ButtonGroup`、`SplitButtonLayout`（已在用） | 现有 `FloatingBottomBar` / `YoinButtonGroup` 继续用，EdgeSplit 是同一套竖过来；不要为了“原生”整个换成 `FloatingToolbar` 重写 |
| shell 内部如果以后要窗格布局 | Navigation 3 的 `SceneStrategy` | M3 现成的 `ListDetailSceneStrategy` 在 adaptive-navigation3 1.3，要 compileSdk 37，这次不升级 |
| 预览 | `@PreviewScreenSizes`、`@PreviewFontScale`、`@PreviewLightDark`，外加 `widthDp` / `heightDp` 的具体窗口 | public Composable 都要有 |
| UI 测试 | `DeviceConfigurationOverride.ForcedSize` / `FontScale` / `WindowInsets`（最后一个能喂挖孔 insets）；折叠姿态用 `androidx.window:window-testing` 的 `WindowLayoutInfoPublisherRule`；分栏用它的 `ActivityEmbeddingRule` | 测试依赖也走 `libs.versions.toml` |
| 真机 / 模拟器 | Pixel Fold、Pixel Tablet、Resizable 模拟器；开发者选项里的「模拟具有凹口的显示屏」 | 平板横屏要测“开着专辑页时点 pill” |

---

## 14. 容易出错的细节

**14.1 分栏里的 Now Playing（最重要）**
- （2026-10-01 改为按需分栏）shell 没有占位规则：窗口 ≥ 840 时，没开详情 shell 就独占整窗，打开第一个详情才分栏，关掉最后一个详情分栏解散。分栏期间 shell 的窗口就是左边那一栏，NP 画不出这一栏。
- 所以 `TabletNP`（双栏）出现在「≥ 840 的整窗」（没开详情，或设备不支持分栏）或「窗格本身 ≥ 840」时。Pixel Tablet 横屏：从首页打开 NP 就是整屏 `TabletNP`；开着详情分栏时，shell 窗格约 600。
- 规则：在分栏窗格里，NP 占满本窗格，不开侧栏，也没有全屏键。窗格 < 600 → 手机 NP；600–840 → 放大的手机（同 §3.4 全屏态的做法）；≥ 840 → `TabletNP`。右边的详情照常显示。这本身就是 Spotify 的「细页面」。
- 画布便签里「打开 NP 时临时展开成整窗」作废：没有干净的原生办法让左边容器临时独占整个任务窗口（`expandContainers()` 是两个容器都铺满、右边的盖在上面）。不要为这个改分栏比例、临时删规则或关掉右栏。

**14.2 Wide 整窗画板什么时候出现**
`DesktopHome`、`AlbumDesktop`、`ArtistDesktop`、`MemDesktop`、`TabletNP` 只在窗格 ≥ 840 时出现。1440 宽的桌面窗口分栏后，shell 约 648、详情约 792，两个都读 Medium，照 Medium 的规则画。别为了让 Wide 画板出现去改分栏规则。这些画板用 1440×900 的 Preview 验收。

**14.3 侧栏旁边的内容按自己的宽度读档**
`LayoutMode` 现在按整个窗口算。侧栏打开后，左边的内容区和侧栏里的 NP body 都要各拿一份 Compact 的 `LocalYoinWindowInfo`，否则内容区会以为自己还是 Medium。

**14.4 侧栏的开启条件和尺寸切换**
- 窗宽 − 侧栏宽 < 320 时不开侧栏，直接进全屏态（例如 600–680 的分屏半窗）。
- 尺寸切换规则见 §3.4 最后两条。「侧栏 / 全屏」状态放进 ViewModel / saved state。

**14.5 MainActivity 不因旋转重建**
MainActivity 在 manifest 里自己处理 `configChanges`。任何只在 `onCreate` 或首次组合时算一次的判断（分栏、挖孔、姿态）旋转后都不会更新，一律订阅 Flow 或 insets。

**14.6 先判高度，再判宽度**
高 < 480 → EdgeSplit，然后才看宽度。844 宽的手机横屏不能落到桌面档。

**14.7 `isDualPaneNowPlaying` 要一起改**
收窄它会同时影响 body 分发、drag-to-dismiss、stage back layer、共享元素，以及 `NowPlayingOverlayHost.kt` 里两对 `BackHandler` / `PredictiveBackHandler` 的 enabled 条件。一起改、一起测。

**14.8 挖孔在哪边看 insets，不看旋转**
挖孔在左还是在右，看 insets / `boundingRects`，不看 `Display.rotation`。手机从 90° 直接转到 270° 时尺寸和方向都没变，不会有 configuration change，只有 insets 会变。

**14.9 `BackHandler` 的 enabled 条件要精确**
多开一个 enabled 的回调，就会吃掉那一下系统的预测性返回动画（skill 里写过这个坑）。侧栏关闭后，它的所有回调都要是 disabled。

**14.10 EdgeSplit 下的接力位置**
shell ↔ 详情的 Button Group 接力（`DetailLaunchMode`）在 EdgeSplit 下位置要逐像素一致，否则跨 Activity 时组会跳。竖版 pill 在 `SharedTransitionLayout` 里的尺寸要有界（已知有 intrinsic 测量崩溃）。

**14.11 别多做**
画板颜色是示意色，一律用现有 token。Yoin Symbols 的动效 painter 不接（§3.1），溶解线不动（§0）。
