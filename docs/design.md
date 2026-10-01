## 项目定位

- **产品名称**：Yoin
- **一句话定义**：一个轻量、高颜值、动效流畅的 Android 原生 Subsonic/OpenSubsonic 音乐客户端
- **目标用户**：自建 Navidrome/Subsonic 服务器的个人用户
- **核心差异化**：极致流畅的操作体验与转场动效（每个交互都有连贯的 Spring 动画，零生硬跳转） + MD3 Expressive 设计语言的视觉张力
- **替代目标**：Symfonium（功能过重、动效生硬、视觉细节粗糙）

---

## 设计原则

1. **动效即体验** — 每个交互都要有连贯的过渡动画，杜绝生硬跳转。使用 MD3 Expressive 的 Motion Physics（Spring 弹簧系统）替代传统 easing + duration
2. **沉浸暗色调** — 以深色为主基调，封面驱动配色，MD3 Expressive 大按钮 + Spotify 式沉浸感
3. **轻量 YAGNI** — 只做个人使用需要的功能，不堆砌 Symfonium 式的「全能」特性
4. **视觉细节克制** — 圆角、间距、字重等细节遵循 MD3 token 规则，有张力但不夸张

---

## 设计语言：Material Design 3 Expressive

MD3 Expressive 不是 M4，而是 M3 的扩展进化。

<aside>
⚠️

**给 AI Agent 的提示**：MD3 Expressive 是较新的设计规范，如果在实现过程中对某个组件、动画或 API 的用法不确定，应主动搜索官方文档（[m3.material.io](http://m3.material.io)）、Jetpack Compose 发布说明和社区实践案例，而不是猜测实现方式。

</aside>

### Shape 系统

10 级圆角尺度（均有严格 token 定义）：

| Token | 圆角 |
| --- | --- |
| None | 0dp |
| Extra Small | 4dp |
| Small | 8dp |
| Medium | 12dp |
| Large | 16dp |
| Large Increased | 20dp |
| Extra Large | 28dp |
| XL Increased | 32dp |
| Extra Extra Large | 48dp |
| Full | 完全圆角 |

另外提供 **35 种装饰性形状** 和内置 **Shape Morph** 动画。

### Motion Physics 系统

用弹簧物理（Spring）替代传统 easing + duration：

- **Spatial Spring** — 位移、大小变化（页面转场、共享元素动画）
- **Effects Spring** — 颜色、透明度变化（主题色切换、淡入淡出）

### 新组件（本项目使用）

- **Button Group**（带 Shape Morph 交互）— 底部导航
- **大按钮风格** — 播放控制
- **Loading Indicator** — 加载状态

### 颜色系统

1. 全部使用 **MD3 Color Tokens**（Primary、Secondary、Tertiary、Surface 等语义色）
2. **默认 = 系统 Dynamic Color** — 深浅色跟随系统设置（`isSystemInDarkTheme()`），API 31+ 通过 `dynamicDarkColorScheme()` / `dynamicLightColorScheme()` 跟随系统壁纸/主题色
3. **播放态 = 封面提取色** — 有内容播放时，用 Palette API 从专辑封面提取主色，替换 color tokens，实现全局色调切换
4. **颜色过渡** — 使用 Effects Spring 做平滑过渡，不生硬跳变。播放封面取色与当前过渡进度由 app session 共享，首页与子页面首帧使用同一色板；底部 NP 和底栏直接读取这套已动画的 tokens，不再叠加按窗口重启的颜色动画。

浮动底栏（含短窗的左缘分离胶囊）不投阴影（2026-10-01，取代 12dp 阴影及其跨窗口交接）：栏和内容靠下面「溶解」的底部网点场分开，不靠阴影。两个窗口的栏在交接时完全同形同色，叠在一起也没有可见差异。

### 溶解（Dissolve）：滚动内容撞上 chrome（2026-10-01 定稿）

滚动内容碰到固定 chrome 时，交界上不盖任何东西，遮罩做进每个 item：图形碎成锚定在自身上的错列网点（约 6dp，与切歌溶解同一网屏），文字只淡出。实现：`SeamDissolve.kt`（`seamDissolveViewport` 标记滚动容器，图形 `seamDissolve`、文字 `seamFade`，不在 viewport 内时为空操作）、`SeamHalftone.kt`（AGSL 与 Path 兜底共用的几何）、`SeamBarField.kt`（栏的几何）、`SeamTide.kt`（潮线）。

1. **适用边界**：竖向滚动的内容碰到 Yoin 自己的 chrome 时用网点：Library 的筛选胶囊、详情页的固定顶栏、底部浮动栏。状态栏属于系统，用潮线。横向列表和胶囊行的两端保持现状。
2. **图形和文字**：封面、头像、缩略图、带底色的卡片、评分异形徽章是图形；文字、图标、数字是文字，从不变成点。顶部交界处文字淡出，长度取 max(文字带, 0.75 × 字号)；底部不淡出，照常从栏下穿过。
3. **底部**：图形不在栏前停下：到栏上方 20dp 才开始碎，其余的过渡藏在栏后面完成，栏下面已经是纯网点，一直铺到屏幕底边。栏两侧越过页边距的内容也一样。栏去掉阴影。
4. **状态栏**：用潮线：两道页面底色的波浪，内容沉进水线里，状态栏永远干净。
5. **尺寸**：顶部交界静止时，离交界 11dp 以内才出缝，文字只在最后 10dp 淡出。底部静止时，只在栏上方 20dp 开始碎，其余 28dp 藏在不透明的栏后面。滚动时两处都随速度放宽，停下后收回。
6. **颜色**：网点只用内容自己的像素，只允许向底色靠拢，不引入新颜色、不加光晕。栏和内容不靠阴影分开：亮度和栏接近的点在栏边让位，其余照常。
7. **动效**：溶解程度只由位置、滚动速度和余韵决定。滚动时网点被搅乱，停下后在余韵的半秒里回到格点；静止时每个点都在格点上，完全不动。栏下面的点阵始终不流动。省电模式和“移除动画”下，关掉余韵、随速度伸缩和无序。
8. **性能**：只有碰到交界或在栏周围的 item 重绘，静止时零开销。按亮度让位每个像素多一次采样，只在栏边 8.5dp 以内执行。潮线是一条路径，几乎没有成本。

- 回退开关：`YoinMotion.SeamSettleAtRest`（默认 `true` = 静整动乱；`false` = 上一版，无序度 D 恒为 1，静止时也打旋、前沿也起伏）。D 只在 `seamDisorder()` 一处计算。
- 带底色的卡片内的文字从点阵里抬出、整块画在点阵之上（`seamFade` 自动处理）；嵌在另一个 `seamDissolve` 里的元素交给外层统一拆点。
- Now Playing 升起时底部网点场随栏的显隐目标淡出（点长回整图），收起后长回来。
- API 33 以下走 Path 兜底，几何与着色器一致，但读不到像素，所以不做按亮度让位。

### 图标：Yoin Symbols

**来源**

- 依赖 `io.github.p2o51:yoin-symbols`（包名 `com.gpo.yoin.symbols`），源码在 `~/Developer/yoin-symbols`。发布到 Maven Central 之前用 composite build：`settings.gradle.kts` 默认 include `../yoin-symbols/android`，CI 传 `-PyoinSymbolsDir=<path>`。
- app 不再用 material-icons，也不在 app 里放图标 vector（`res/drawable/ic_yoin_*` 只剩 3 个 launcher 素材）。缺的符号先加进库的 `generator/`，再在 app 里用。
- 暂时的例外：Now Playing 侧栏右上角的「全屏 / 收回侧栏」键库里还没有对应符号，仍用 Material 的 `OpenInFull` / `CloseFullscreen`，所以 `material-icons-extended` 暂时保留；补上符号后删掉依赖。
- 另一个例外：16:9 矮屏「Tap to expand」提示里的展开符号动画（`NowPlayingScreen.kt` 的 `UnfoldHintSymbol`）仍在 app 里用 Canvas 画，因为库 0.1.0 只有静态 `UnfoldMore`；库加上动效版后换掉。
- 通知小图标这类要资源 id 的地方，用库的 VectorDrawable：`com.gpo.yoin.symbols.R.drawable.ic_yoin_<name>`（工程开了 `nonTransitiveRClass`，要 `import com.gpo.yoin.symbols.R as SymbolsR`）。

**规则**

- 默认用线性版；「选中」或「已开启」用 *Filled 版（导航的 Home / Library 选中时用 `HomeFilled` / `LibraryFilled`）。
- 播放控制键用 *Filled 多边形版：`PlayFilled`、`SkipNextFilled`、`SkipPreviousFilled`。PLAY / PAUSE 大按钮保持文字。
- 返回、左右箭头、发送在 RTL 布局里自动镜像。
- 按场景选：专辑封面兜底 `Album`，歌单封面兜底 `Playlist`，单曲兜底 `MusicNote`，歌手 `Artist`，队列 `Queue`。

**动效符号和用在哪**（都读 `LocalSymbolMotion`，不显式传 motion）

| 符号 | 用在哪 |
| --- | --- |
| 翻译 `rememberTranslateSymbolPainter` | 歌词工具条的翻译键：翻译进行中「文」「A」绕圈换位，结束回到「文A」；进行中按键保持亮着 |
| 收藏 `rememberFavoriteSymbolPainter` | Now Playing 的收藏键、专辑页曲目行的收藏键 |
| 均衡器 `rememberEqualizerSymbolPainter` | 专辑页当前曲目：播放时跳，暂停后从两边往中间沉成点 |
| 展开箭头 `rememberExpandSymbolPainter` | 设置的可展开项、Play ▾（横版和竖版）：铰链式先压平再翻过去，不转圈 |
| 播放模式 `rememberPlayModeSymbolPainter` | Now Playing 的播放模式键 |

- 动效档位跟随 `MotionProfile`：`AdaptiveReduced` 对应 `SymbolMotion.Reduced`，其余 `SymbolMotion.Default`（库的默认弹簧就是 M3 Expressive motion scheme），在 `YoinActivityRoot` 统一提供。系统「移除动画」时符号静止。

**播放模式**（一个按钮三个状态，点一下按顺序切换，默认列表循环）

| 模式 | 图标 `SymbolPlayMode` | Media3 | Spotify App Remote |
| --- | --- | --- | --- |
| 列表循环（默认） | `RepeatAll` | `REPEAT_MODE_ALL`，shuffle 关 | `setRepeat(ALL)` + `setShuffle(false)` |
| 随机 | `Shuffle` | `REPEAT_MODE_ALL`，shuffle 开 | `setRepeat(ALL)` + `setShuffle(true)` |
| 单曲循环 | `RepeatOne` | `REPEAT_MODE_ONE`，shuffle 关 | `setRepeat(ONE)` + `setShuffle(false)` |

- 顺序：列表循环 → 随机 → 单曲循环 → 列表循环。repeat 和 shuffle 每次一起写。
- 读回：`REPEAT_MODE_ONE` → 单曲循环；shuffle 开 → 随机；其它（含 `REPEAT_MODE_OFF`）→ 列表循环。
- Yoin 自己开播的队列（任何后端）开播后套用用户选的模式；在 Spotify App 等别处开始的播放只观察、不写。模式不跨进程保存。
- 配色：列表循环用 `tertiaryContainer` / `onTertiaryContainer`，随机和单曲循环亮成 `primary` / `onPrimary`，Effects Spring 过渡。
- 详情页的「Shuffle play」是把列表本身打乱，不碰播放模式。

**`docs/icons/`**：第一轮图标草稿，已被 yoin-symbols 取代，留着存档，不要删。

### 实验性 API 策略

- **核心 UI 框架用稳定版 M3**（Material3 `1.4.x` stable）— 主题、基础组件、Navigation
- **Expressive 组件按需 opt-in** — 用 `@OptIn(ExperimentalMaterial3ExpressiveApi::class)` 标注使用处，限定在特定 Composable 内
- **Motion Physics 可全局采用** — Spring 动画本身是 Compose Foundation 的稳定能力
- **Shape Morph** — 通过 `androidx.graphics:graphics-shapes`（stable 1.0.x）实现，不依赖实验性 API

---

## 技术栈

| 层级 | 选型 |
| --- | --- |
| 语言 | Kotlin |
| UI 框架 | Jetpack Compose |
| 设计系统 | Material 3 `1.4.x` stable + `1.5.x` alpha（Expressive 按需 opt-in） |
| 音频播放 | Media3（ExoPlayer） |
| 缓存 | Media3 `CacheDataSource`  • `SimpleCache`（LRU 淘汰） |
| 形状动画 | `androidx.graphics:graphics-shapes:1.0.x` |
| 封面取色 | AndroidX Palette API |
| Chromecast | Media3 Cast Extension + Google Cast SDK |
| 本地数据 | Room（本地评分、缓存元数据、播放历史） |
| 架构 | 单 Activity + Compose Navigation，MVVM |

---

## 页面结构与交互模型

### 导航结构

整个 App 只有一个主屏幕，底部一个 **Button Group**（MD3 Expressive）作为唯一导航。

*(注：替代了传统音乐 App 常见的常驻 Bottom Player Bar，将导航与播放状态显示合二为一)*

`[ 🏠 主页 ]  [ 🎵 封面 + 歌名/歌手 ]  [ 📚 Library ]`

- **左按钮 — 主页**：点击切换到主页内容
- **中间按钮 — 正在播放**：显示当前曲目缩略封面 + 歌名 + 歌手名；点击后通过共享元素转场展开为全屏 Now Playing
- **右按钮 — Library**：点击切换到 Library 内容
- Button Group 之间的切换使用 MD3 Expressive 的 **Shape Morph** 动画（选中按钮膨胀、未选中收缩）
- **同一个 Button Group，三种形态**（2026-09-30 断点适配，判定源 `ShellChromeForm`，先判高度再判宽度）：
  - **竖屏底栏**（宽 < 600 且高 ≥ 480，以及 Tabletop）：上面这条，不变
  - **手机横屏 · 分离式挖孔带**（高 < 480，不管宽度）：两段 64dp 胶囊贴左边、住进挖孔那条 inset 带——挖孔上面是导航 / 详情页的竖向 Play 分体键，下面是竖版正在播放 pill（封面在底、进度从下往上涨、歌名从下往上读）；挖孔位置读 `displayCutout.boundingRects`，90° ↔ 270° 翻转只跟 insets 走。左边没有挖孔时两段之间只留 8dp，多出的一格放「随机播放」。内容从 8 + 64 + 12 = 84dp 开始
  - **Medium 及以上 · 居中底栏**（宽 ≥ 600 且高 ≥ 480）：竖屏那条本身，水平居中、限宽 600，下边距 24（Wide 28）；详情形态把 ▾ 里的动作拿出来单独放（随机播放 / 前往歌手 / 分享）+ 定宽 200 的正在播放 pill。原左侧 Navigation Rail 已删除
- 页面用 `LocalShellChromeInsets` 给 Button Group 让位（底部两种形态留栏高，分离式留左侧 84dp + 右侧挖孔）
- 被拿出来单独放的动作一律从 ▾ 菜单里去掉，不重复

### 🏠 主页

- Mix / 推荐区块（从 Navidrome 获取随机专辑、最近添加等；「最常播放」排序依赖 Scrobble，MVP 阶段使用服务端已有数据）
- 辅助可视化区域（当有曲目播放时，显示实时音频可视化效果）
- Memory teaser 只显示一条轻提示，点入 shell-owned Memories surface；Feed 不承载重型 Memory 卡片流
- 右上角 ⚙️ 设置入口

### Album Memory

Yoin Memory 是 **当前 profile 下的 local-first 专辑记忆层**，不是 Spotify Wrapped、stats.fm、Last.fm clone，也不是全平台实时统计服务。Spotify、Subsonic、未来本地文件或其它来源都只是 provider；第一阶段不做跨 profile 自动合并、不做跨源 canonical album merge、不做通知 scrobbler。

v1 的 Memories surface 定义为 **profile-local, album-first listening journal deck**。Header 可以继续使用 `Memories` 作为 umbrella 名称，但页面表达需要用 `Album memories` 副标题或 chip 说明当前 MVP 的实体范围。SONG / PLAYLIST Memory、timestamp memory / music time machine（例如 this day last week）属于后续方向，不进入 v1 主 deck。

Memory 的核心单位是 album。候选由当前 active profile/provider 下的播放历史、曲目评分覆盖率、Album Note、Song Note、AskAI 记录、专辑评分与 NeoDB review 状态共同生成。推荐型 Memory 的 gate 是：

`ratedTrackCount / totalTrackCount >= 60%`

但有专辑 review，或至少两条 album/song note 的专辑，也可以进入 Memory。Note 是用户自己的观点，AskAI 是参考资料；二者必须在 prompt 和 UI 表达中分开，不能混成同一种用户立场。

Memory 卡片必须解释「为什么这张专辑成为 memory」：至少表达 album review、rated-track coverage、note count、AskAI references、recent revisit、NeoDB ready/synced 等本地信号。评分显示遵循 Notion 产品规则：用户有 album rating 时优先显示 album rating；否则显示 average track rating。Gemini emotional copy 是可选增强；未配置 BYOK 或生成失败时使用本地 deterministic fallback copy，不扩大 Gemini scope，也不上传 note/review 原文。

**记忆表面字体规范（2026-07-26 定稿，同日收窄；适用 Memories 卡 + 首页 Jump Back In）**：

- **宋体只属于 AI 拟题（memoryTitle）**：那一枚生成的标题（JBI memory 卡与 Memories 卡印章旁两处），衬线（`FontFamily.Serif`，Pixel 上即 Noto Serif CJK / 思源宋体同源字形），SemiBold 17–18sp。token：`YoinSerifTitle`（Type.kt）。
- **其它标题维持 GSF，只加大字号**：专辑名照旧；歌名（笔记卡头行、JBI 卡片标题）升到 16sp SemiBold。
- **用户正文**：Memories 卡的乐评正文、笔记正文用系统默认字面（`FontFamily.Default`）；首页 Jump Back In 的 note 正文改用 Google Sans Flex，继承 `bodyMedium`（2026-09-19 调整）。
- **GSF = 其余一切**：Yoin 代笔文案（必须带「Written by Yoin」署名）、评分数字、标签、按钮、证据句。
- `HomeWidgetCard.commentIsHeadline` 区分拟题（宋体标题）与笔记原文（黑体正文）。

**拟题豁免（2026-07-26 决定）**：Memory 卡的 AI 拟题（`memoryTitle`，同时复用为首页 Jump Back In memory 槽位的标题）是上一条「不上传原文」的唯一例外——拟题 prompt 允许携带正文槽占用者（album review 或最新一条 note）的原文，并拼上专辑背景（专辑名/艺人/年份 + 本地已缓存的 Gemini About 行，不产生额外请求）。豁免仅此一处用途；`narrativeCopy` 的输入契约不变。未配置 BYOK 或生成失败时，拟题回退本地 deterministic 模板（覆盖率/笔记数），拟题槽永不为空。

NeoDB 同步以 album 为边界。第一阶段只有同时具备 album rating 和非空 album review 的 Memory 才能推送到 NeoDB；单曲碎片笔记只作为本地 Memory/Review 草稿素材，不直接推 NeoDB。

### 🎵 Now Playing（全屏展开态）

- 大封面图（共享元素：从 Button Group 缩略图扩展而来）
- 歌曲标题 + 歌手
- 进度条（波浪形设计，可拖拽）
- 播放控制（上一曲 / 播放暂停 / 下一曲）— MD3 Expressive 大按钮风格
- **滑动评分条**（垂直粗条设计，位于封面右侧，直接显示精确浮点评分如 3.7，视觉表现力强）
- 收藏按钮（心形图标，位于评分条下方）
- 歌词同步显示区域（位于控制区上方，逐行高亮）
- **底部操作胶囊 (Pills)**：设备投射（Chromecast/Sonos）、播放队列、笔记等次级入口
- **背景**：两个暗色调的微妙渐变（渐变过渡极其平滑，几乎感觉是纯色）+ 实时音频可视化（与背景融为一体，有呼吸感）
- **退出**：下滑手势 / 系统返回键（适配 Android 14+ Predictive Back，缩回时有连贯的预览动画）
- **断点**（2026-09-30，`NowPlayingPresentation`）：
  - **16:9 矮屏**（歌词区放不下 2 行）：Lyrics / About / Note 那一行 + 歌词窗口收成**一行当前歌词**（bold、primary、单行省略），整行就是展开按钮，没有单独的展开键；同一句停 ≥ 8 秒时这一行交叉淡入成「展开符号动画 + Tap to expand」，**一天最多一次**（按本地日期记在 `yoin_ui_hints`）；动画缩放为 0 时符号不动、字停约 2 秒。歌词展开页 tabs 保持文字，4 个歌词工具键挪到 tabs 同一行右侧，底部只剩标题
  - **自动沉浸**（所有尺寸）：播放中 + Lyrics 页 + 有同步歌词 + 5 秒无操作 → 只有 4 个歌词工具键隐藏；在底部时淡出并塌缩槽位（标题下沉、歌词往下长），在顶部时原地淡出；手动滚过歌词、触摸、暂停都会恢复
  - **控件永远排得下**：胶囊组先按真实宽度量，放不下整组收成纯图标（按下时展开标签），三个都在；控制行先收 PLAY 的内边距、再把控件从 56 降到 48，播放模式键永远完整
  - **Medium 整窗（折叠屏内屏、平板竖屏）· Spotify 式**：点 pill 先从右边推出手机宽的**侧栏**（clamp(窗宽 × 0.5, 360, 420)，左侧圆角 28，就是手机那一页、歌词吃满剩余高度）；旁边的内容让出这块宽度继续可用、读成 Compact；底栏折成 [Home][Library] 居中。侧栏右上角「全屏」→ **放大的手机**（列宽 ≤ 640 居中，封面随高度收，评分列 / 控件随列放大；右上角「收回侧栏」）。返回一级一级退：全屏 → 侧栏 → 关闭，左上角 ▾ 直接关闭；侧栏往右滑也能关，三者共用一个 dismiss 控制器。展开折叠屏（Compact → Medium）时直接进全屏态，其它进入 Medium 默认侧栏；「侧栏 / 全屏」状态在 NP 的 ViewModel 里。窗宽 − 侧栏 < 320 时不开侧栏，直接全屏态
  - **分栏窗格里**（开着详情时）：NP 占满本窗格，不开侧栏也没有全屏键——< 600 手机 NP、600–840 放大的手机、≥ 840 双栏；没开详情时 shell 独占整窗，平板横屏从首页打开就是整屏双栏
  - **双栏只留给 Wide**（≥ 840 的整窗或窗格，且够高）：左栏按控件定宽 312（= 封面边长），右栏歌词吃剩下的；点封面放大时左栏最多 1.5 倍、右栏不小于 320

### 🔊 后台播放与系统集成

- **MediaSession + Media3** — 后台持续播放，系统媒体控件联动
- **通知栏控制** — 显示封面、歌曲信息、播放/暂停/上一曲/下一曲按钮
- **蓝牙/耳机按键响应** — 通过 MediaSession 自动支持
- **Audio Focus** — 正确处理与其他 App 的音频焦点争抢

### 📚 Library

- 分类浏览：歌手 / 专辑 / 歌曲 / 收藏
- 播放列表浏览在第二期加入（依赖播放列表 CRUD）
- 普通点击底部 Library：进入当前 profile 的 Library，展示 saved artists / albums / playlists / songs
- Library 内普通搜索：默认 scope 为 Current Library，只搜索当前 profile 已保存内容
- 长按底部 Library：Spotify profile 下打开搜索框并默认 scope 为 Spotify Global（占位文案 `Search Spotify`，chips 为 Spotify / Library）；非 Spotify provider 下打开 Current Library 搜索
- 右上角 ⚙️ 设置入口
- 筛选胶囊与下方网格 / 列表的交界用“逐项溶解”软衔接（2026-09-29 取代硬截断，2026-10-01 定稿为曲线 C）：交界处不盖任何渐变、模糊或色带，遮罩在每个 item 里。图形（封面、头像、缩略图）在滑到交界时碎成跟随自身的错列波点，越往上越小，到交界线正好消失；静止时离交界 11dp 以内才出缝，胶囊下只有两行整齐的小点和中点，前沿是直的；文字只在最后 10dp 渐隐、不拆成点。因此胶囊下的固定间距只留 4dp，网格顶部内边距 8dp
- 五个标签的底部都接底部网点场：封面到栏上方 20dp 才开始碎，栏下和栏两侧是纯网点；文字照常从栏下穿过
- 这对遮罩是通用语言：任何“滚动内容撞上固定 chrome”的交界都应复用（规则见「溶解（Dissolve）」一节）

### ⚙️ 设置（从主页或 Library 进入）

- 服务器配置（Subsonic/Navidrome 地址、认证）
- 缓存管理（容量限制、清除）
- 主题偏好
- 关于 / 版本信息

#### 账号与服务二级页（2026-09-26，取代 2026-09-06 版）

- **大屏 list-detail**（2026-09-30，SettingsTablet）：窗口 ≥ 840 时 Settings 与二级页走原生 Activity Embedding 分栏（`tag="settings-*"` 的 SplitPairRule + SplitPlaceholderRule，约 420 | 860，叠在 shell 分栏之上，两个 Activity 不合并）。左栏账号卡改为竖排行、右栏打开的那个高亮（⋮ 里保留 Use / Remove）；Features 里的 AI features / NeoDB 也在右栏打开（`SettingsFeatureActivity`），窄窗仍是行内展开。二级页手机横屏：左栏固定（hero + You'll need，不随输入法滚走），右栏滚动、表单在前、What you get 在后
- Settings 主页只做「列出 / 切换 / 移除」：Accounts 横向卡片（名称 + 一行状态：问题徽标 > In use > 服务器/账号），其余设置按 Features / Storage / About 分组，行内折叠展开（空间弹簧 + 效果弹簧），不再平铺说明段落。
- 服务能做什么，只在用户表达兴趣时讲：Add → 选择面板（每项名称 + 一句话）→ 服务二级页 `ServiceSetupActivity`。二级页 = 服务标识 + 一句定位 + What you get（≤4 条亮点）+ You'll need（前置条件）+ 连接表单。不做支持矩阵、不罗列“不能做什么”；做不了的操作照旧隐藏，失败时给简短可处理的错误。
- 管理已有账号（编辑 Subsonic、Spotify 重新登录、凭据缺失恢复）复用同一二级页的 manage 模式：跳过介绍，直接给表单/操作。Spotify Client ID 属于一次性开发者配置，收进二级页的 Developer setup 折叠行，仅在缺失时自动展开；「No Client ID」深链打开该页并聚焦输入框。
- 返回：二级页是无共享 chrome 的全屏目的地 → Pattern A 原生跨 Activity 预测性返回，零 back 代码；新账号的切换由 Settings 在自己的 scope 里执行（二级页经 ActivityResult 回传 id）。
- Apple Music 尚未注册为可切换的 provider：选择面板里标 Preview，二级页的 Connection test 组承载开发者 Token 服务、MusicKit 授权、搜索与整曲播放测试。只有真实账号验证后才开放常规 Profile；SDK 初始化不代表授权或整曲已跑通。未来 + 加入资料库必须查询确认完成后才显示稳定的勾选状态，不能将 HTTP 202 当成已完成。
- Apple Music 能力依据：[MusicKit](https://developer.apple.com/musickit/)、[添加资料库](https://developer.apple.com/documentation/applemusicapi/add-a-resource-to-a-library)、[喜爱限制](https://support.apple.com/en-us/111118)。接入时重新核验。

---

## 关键转场动画

| 触发 | 动画效果 |
| --- | --- |
| 切换主页 ↔ Library | Button Group Shape Morph + 页面内容 crossfade（Effects Spring） |
| 点击中间按钮展开 Now Playing | 封面 Shared Element 扩展 + Button Group 容器 Spatial Spring 扩展为全屏 + 背景 fade in |
| 下滑 / 返回收回 Now Playing | 反向 Spring 动画，封面缩回缩略图，全屏收回 Button Group |
| Predictive Back | 跟手进度驱动的收回预览，松手后 Spring 完成或回弹 |
| Now Playing → 专辑 / 歌手 / 歌单 | 保留原播放器及其 stage；详情正文准备好后不透明推入，详情底栏同步从下方进入，不等待主页底栏变形 |
| 详情 → 原 Now Playing | 返回手势同时驱动页面收回和底栏向下退出；取消回到详情，提交等空间 / 透明度弹簧收尾后再关闭窗口 |
| Memories → Go to album → 返回 | 保留原 Memories 卡片与滚动位置；专辑详情底栏随正文进入和退出，不与隐藏的首页底栏交接或变形 |
| 多层详情 / 播放器返回 | 返回当前窗口的真实来源；内层详情不改写主页的返回进度和底栏状态 |
| 歌词搜索 | 从实际搜索按钮展开，收起回到该按钮；复用官方 SearchBarState 与全屏 Search 的动效、键盘处理和预测性返回 |
| 切歌 | 大封面在新图加载成功后用封面专用低刚度、临界阻尼 Effects Spring 驱动细密错列的波点溶解，约 700ms 显影完成后自然收尾：波前本身持续起伏，圆点轻微漂移、柔和浮现后合拢，边缘带低强度 Primary → Tertiary 渐变光晕；下一首从右、上一首从左接管。点距约 6dp，旧图始终不透明兜底，新图随溶解逐步显影。缩略图保留轻量 crossfade，背景色继续原有 Effects Spring 过渡 |
| Library 列表滚到筛选胶囊下 | 曲线 C（2026-10-01）：静止在顶部时不出现；前 40dp 滚动里过渡带从 0 长满。带高 G 静止 12dp、前沿起伏 F 3dp、文字带 T 10dp，随滚动速度放宽（150dp/s 起，1450dp/s 时 G 34 / F 8 / T 26），停下约 0.5 秒收回。点半径 = 0.64 格距 × t^0.8（t = 到交界的距离 / G），交界处为 0，G + F 以下是实色；点距 6dp 与切歌溶解同一网屏。滚动时前沿每个 item 各有起伏、点沿同一流场打旋，二者都乘无序度 D = 1 − e^(−速度/200dp/s)；停下后 D 与余韵按同一个 e^(−ωt)（ω = √90）衰减，约 0.45 秒回到格点，静止时点阵完全规整。余韵：流动落后内容的量与速度成正比（上限 G/2），停下后以停前速度继续漂一小段再停，不回弹。文字淡出 alpha = 1 − (1 − x′)²，长度 max(T, 0.75 × 字号)。省电模式和“移除动画”下无余韵、不放宽、D = 0。API 33+ 走 AGSL `RenderEffect`，以下用 Path 裁剪兜底；只有进入带内的 item 才重绘 |
| 内容滚到底部浮动栏 | 底部网点场（2026-10-01）：以屏幕坐标计，过渡段从栏上方 20dp 开始，其余 28dp 藏在栏后，快速滚动时起点最多再上移 20dp；栏上沿 28dp 以下是覆盖率 40% 的静止点阵，越往下略细，一直铺到屏幕底边，栏两侧同样。点的颜色越往下越靠近页面底色（栏上沿 16%、屏幕底 32%），只作用在图形上。亮度（L*，含退色）与栏容器色相差 ≤ 6 的点在离栏 1.5dp 内消失、8.5dp 外恢复，≥ 18 不让，每个点只判断一次；还没开始碎的实色内容贴着栏时也参加。列表最后 40dp 滚动里过渡段收成 0，最后一项完整停在栏上方。过渡段的起伏和打旋同样乘 D，栏下点阵不流动。栏无阴影；Now Playing 升起时网点场按效果弹簧淡出、收起后长回 |
| Home 内容滚进状态栏 | 潮线（2026-10-01）：两道页面底色的波浪从屏幕上沿盖到状态栏 + 2dp，前 40dp 滚动里从屏幕外降下来；前层不透明（波长 72dp），后层低 6dp、55% 不透明（波长 116dp）。振幅 2.4dp，随滚动速度最多再加 3.2dp；相位跟随滚动和余韵，停下即静止。内容沉进水线，图形顶部不再用网点；文字（含 32sp 大标题，淡出约 24dp）在状态栏 + 2dp 处淡完。省电和“移除动画”下相位固定、振幅 2.4dp。与 Now Playing 胶囊里的波浪是同一种线 |
| 歌词翻译开关 | 每行译文从行下沿按空间弹簧展开 / 收起（间距在动画块内，收起即单行高）；焦点行全程钉在 38% 锚点，上下行向两侧让开 |
| 打开歌词时切歌 | 歌词流“继续滚动”：旧歌冻结在最后播放位置，向上漂移并慢速淡出；新歌从下方升入（上一首则方向相反）。加载完成时歌词短距离升入、加载指示淡出。新歌前奏期间焦点位是歌名卡片。**自然播完且下一首歌词已预取时**改为预告式接续：最后一句唱完、离结束约 10 秒起，下一首的歌名卡片和前几句在最后一句下方以半强度浮现、上升；结束前约 1.4 秒整页上滑，把下一首歌名推到它自己列表的起始位置，同时当前歌词淡出；真正切歌时两份画面在歌名以下完全一致，原地替换、不再播放滑入滑出。提前跳歌、手动滚动过、没有下一首或歌词无时间轴时仍走上述滑动过渡 |

Now Playing 恢复原有的封面、标题、歌手名 Shared Bounds：整页使用 Expressive 上滑与 Standard 淡入，封面和文字各自使用原来的空间弹簧。本次回退保留播放器和底栏各自原生 AnimatedVisibility 时钟，移除新加的统一 seek 时间轴及时间轴弹簧。原有预测性返回与下滑交互保持原实现，后续修改必须同时对比完整展开、关闭和取消手势。

底部 Now Playing 胶囊的波浪相位和播放 / 暂停振幅属于 app session，首页、详情页和返回时重建的胶囊读取同一个状态；两端使用相同精度的播放进度。帧更新由可见窗口提供，不新建后台动画时钟。封面过渡保留已解码的图片而非只记 URL，缓存失效或连续切歌不能露出浅色底；中断点阵溶解时保留当前混合画面。

展开尚未结束时切入歌词，封面立即退出 mini → full Shared Bounds，由歌词 stage 的缩小转场接管；不得等待整页展开弹簧结束，也不得让旧共享封面绕过 stage 的隐藏和裁剪继续漂浮。歌词展开时，完整歌词列表保持最终视口的测量尺寸，通过位移和裁剪跟随可见区域；避免每帧改变 LazyColumn 高度并反复滚动校正当前句。

---

## Subsonic API 功能范围

### ✅ MVP 必须实现

| 功能 | API | 说明 |
| --- | --- | --- |
| 认证/连接 | `ping` | 服务器连接测试 + token 认证 |
| 浏览专辑 | `getAlbumList2` | 按最近添加、随机、最常播放等排序（主页 Mix 数据来源） |
| 专辑详情 | `getAlbum` | 专辑曲目列表 |
| 歌手列表 | `getArtists` | Library 歌手浏览 |
| 歌手详情 | `getArtist` | 歌手的专辑列表 |
| 搜索 | `search3` | Library 内全局搜索（歌手/专辑/歌曲） |
| 流式播放 | `stream` | 核心播放功能，Media3 ExoPlayer 对接 |
| 封面 | `getCoverArt` | 专辑封面显示 + Palette 配色提取 |
| 歌词 | `getLyricsBySongId` | OpenSubsonic 扩展，同步歌词 LRC |
| 收藏 | `star` / `unstar` | 收藏歌曲/专辑，同步到 Navidrome |
| 获取收藏 | `getStarred2` | Library 中显示收藏内容 |
| 随机歌曲 | `getRandomSongs` | 主页推荐 / 随机播放 |
| 评分 | `setRating` | 滑动评分条，本地浮点精确值，服务端 1-5 整数同步 |
| Chromecast | Media3 Cast Extension | 插件式接入，检测 Cast session 自动切换播放器 |
| 后台播放 | MediaSession + Foreground Service | 后台持续播放 + 通知栏控制 + 系统媒体控件 |
| 自动缓存 | Media3 CacheDataSource | 播放时自动缓存，LRU 淘汰策略，可配置容量上限 |

### 📋 第二期加入

| 功能 | API | 说明 |
| --- | --- | --- |
| 播放列表 | `getPlaylists` / `createPlaylist` / `updatePlaylist` / `deletePlaylist` | 播放列表完整 CRUD |
| 离线下载 | `download` | 手动下载专辑/播放列表供离线播放 |
| 播放队列同步 | `savePlayQueue` / `getPlayQueue` | 跨设备继续播放 |
| Scrobble | `scrobble` | 报告播放记录，用于「最常播放」排序 |
| 按风格浏览 | `getGenres` / `getSongsByGenre` | Library 增加风格分类 |
| Sonos 投射 | UPnP/DLNA（Cling 库） | 发现 Sonos 设备，通过 UPnP AV Transport 协议控制播放 |
| 缓存管理 UI | 本地 | 占用空间可视化、按专辑清除缓存 |

### ❌ 不做（YAGNI）

Podcast、Internet Radio、Chat、User Management、Jukebox、Bookmarks、Shares、Video

### 📱 大屏幕适配 / 响应式设计（2026-07 落地，2026-09-30 断点适配重订）

- 宽度三档 LayoutMode（Compact < 600 / Medium 600–840 / Wide ≥ 840）+ Tabletop 折叠姿态，**高度另读**：高 < 480 是手机横屏（`isCompactHeight`），页面先判高度再判宽度——844 宽的手机横屏不会落到桌面档。判定源都在 `ui/experience/WindowAdaptiveRuntime.kt`，由 M3 adaptive 的 window size class + 铰链姿态驱动，LayoutMode 按窗格相对（分栏里每个窗格读自己的宽度）
- Button Group 三种形态（竖屏底栏 / 手机横屏分离式挖孔带 / Medium+ 底栏居中限宽 600）见「导航结构」；`LocalShellChromeInsets` 给页面让位；原 Navigation Rail 已删除
- 跨窗口 bar 交接（`DetailLaunchMode.FullChoreography`）在竖屏底栏与分离式挖孔带下都做（两个窗口的组逐像素同位）；居中底栏的详情形态换了排布，纯推入；分栏交给系统默认
- Activity Embedding（`res/xml/main_split_config.xml`）：**按需分栏**（2026-10-01）——任务窗 ≥ 840 时 shell 平时独占整窗（平板横屏读 Wide，首页 / 资料库走桌面档），打开第一个详情才分成 shell | detail，关掉最后一个详情分栏解散、shell 回到整窗；不再有右栏占位页。分栏开合、栏内推入的动画全交给系统（不叠我们的 96dp 内容滑入），动画露出的底色由 calculator 设成 app 背景色（深浅色各自取 dynamic scheme 的 background）。分栏里的详情没有底栏，返回走系统原生（predictive-back Pattern A：窗口不透明、不注册返回回调、用系统关闭动画），右栏最后一张关掉时系统直接收起分栏。宽窄切换时首页 / 资料库的 tab 和滚动位置保留（资料库网格记住用户滚到的那一格，列数变了也回到那一行）。比例由 `SplitAttributesCalculator` 按窗宽算：≥ 1080 时 shell = max(600, 0.45 × 窗宽)（扣掉分隔条后仍 ≥ 600，1280 宽约 0.48），详情 ≥ 480；更窄照旧 0.45。分隔条用平台的可拖动 `DraggableDividerAttributes`（extension < 6 没有，不自己画），颜色设成 `surfaceContainerHigh`（比页面底深一档，同 TabletSplit 的色带；库默认是黑色），深浅色切换时主动刷新分栏让它跟着变。分栏里详情窗格没有底栏，[分享][Play 分体键] 放进 hero，正在播放只在 shell 那条出现
- 页面：Home / Library 手机横屏有单独一档（Home 28sp 标题、Activities 单行三卡；Library 单行头部 [搜索 208][chips][设置]、~100dp 格子 6 列）；详情页手机横屏把竖屏 hero 横过来（封面左、竖屏里封面下面的东西在右，上拉照旧），背景图形做成贴着封面的闭合形状、不伸到左边的组下面；Medium 歌手是宽 hero（名字只出现一次，Follow 进 hero，Play 只在底栏）；Wide 整窗专辑 / 歌单是 400 身份栏 + 完整曲目表，歌手是横顶 hero + Most Played 480 | Discography；Memories 16:9 印章 112、提问与 Write a review 并行，手机横屏一张卡拆两栏，Wide 整窗对开（大印章 190、笔记回到卡面）
- 页面内容宽度有 clamp 基线（`yoinPageContentWidth`，Feed=720 / Prose=640 / Card=480）

---

## 评分与视觉形状系统

- **UI 交互**：滑动评分条（Now Playing 界面垂直粗条设计，连续滑动，精确到 0.1），视觉上极具 Expressive 张力
- **分数展示**：Library 和主页中，评分与 MD3 Expressive 新增的 Shape API（如多边形、Squircle 等）结合，作为卡片背景或徽章（如 7.1 分的异形徽章）
- **本地存储**：浮点精确值（如 3.7）
- **服务端同步**：四舍五入为 1-5 整数，通过 `setRating` 写入 Navidrome

---

## 音频可视化

- **实时频谱/波形可视化**，与 Now Playing 背景和主页辅助区域融为一体
- 不是贴上去的独立层，而是**驱动整个界面氛围感**的一部分
- Now Playing 背景：两个暗色调的微妙渐变 + 可视化效果叠加
- 具体视觉效果在实现时探索，先做基础版再迭代调整

---

## 投射功能

### Chromecast（MVP）

- 使用 Media3 Cast Extension，提供 `CastPlayer`
- 实现 `SessionAvailabilityListener`：检测到 Cast session 时自动从本地 ExoPlayer 切换到 CastPlayer，断开时切回
- Cast 按钮使用 Google Cast SDK 标准 `MediaRouteButton`
- 需要在 Google Cast Developer Console 注册应用（或使用 Default Media Receiver）

### Sonos / DLNA（第二期）

- Sonos 音箱仍支持作为 UPnP/DLNA Media Renderer
- 使用 Cling（Java UPnP 库）或后继项目进行设备发现
- 通过 UPnP AV Transport 协议发送 Play/Pause/Stop/Seek 指令
- 投射时使用 Navidrome 的直接 stream URL（非本地代理），确保 Sonos 在同一网络内可访问

---

## 待确认事项

- [x]  滑动评分条的 UI 设计稿 — 已确认：垂直粗条，位于封面右侧，显示浮点评分（参见设计稿截图）
- [ ]  音频可视化的具体视觉效果 — 实现时再探索，先做基础版再迭代（视觉表现仍未定；采样代码写好了但**尚未接通**：`player/AudioVisualizerData.kt` 的 `AudioVisualizerManager` 只在 `AppContainer` 里 lazy 构造，全仓没有任何一处调用它的 `start(audioSessionId)`，`PlaybackService.audioSessionId` 这条流也没人喂给它，所以 UI 侧 collect 的 `playbackSignal` / `visualizerData` 目前恒为 0 / Empty）
- [x]  App 图标设计 — 已完成：三箭头全出血几何，自适应图标 `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`（含 `ic_launcher_round.xml`），三层素材在 `res/drawable/ic_yoin_launcher_{background,foreground,monochrome}.xml`，monochrome 供主题图标使用

![image.png](attachment:37c0d660-23b7-4c25-9fb4-e77630964bcd:image.png)

![image.png](attachment:eba9a990-ab35-4304-ad5d-b5327f351346:image.png)

![image.png](attachment:86d59aea-0f12-414a-8b61-a3508223cd37:image.png)


### Apple Music profiles (2026-09-26)

Apple Music connection now creates a regular encrypted Profile; the previous validation authorization migrates once without automatically switching the active account. Its MusicSource supports library albums, artists and read-only playlists plus catalog search. MusicKit supplies DRM audio through a Media3 session shared by Now Playing and system controls. Unsupported favorite/library writes, playlist editing, Cast and offline caching remain hidden; adding to the Apple Music library must never be represented as a favorite heart. Imported tracks without a catalog playback ID require an explicit unavailable message. Physical subscribed-account QA is still pending for this integration.
