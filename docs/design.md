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
5. **服务色（2026-10-03，唯一的非 token 颜色）** — Settings 家族里每个音乐服务固定一个色族（Subsonic 蓝 H256 / Spotify 绿 H148 / Apple Music 玫红 H10 / 本地文件 琥珀 H60），是 M3「自定义色」：MCU HCT 按色调取值，并向**壁纸（系统 dynamic）primary** 协调最多 10°（MCU 原版 15°，收紧是为了让任意壁纸下服务色两两 ≥ 30°；壁纸近灰（chroma < 6）时不转）。不向封面色协调，否则服务色会随歌漂移。只给"服务"上色：账号头像 / 角标、在用卡片、添加账号面板、服务二级页的服务标记；普通设置行一律单色线条图标。实现在 `ui/settings/SettingsColors.kt`。
6. **Memories 专辑色（2026-10-04）** — Memories 每张卡的颜色（ink、高亮、按钮、页点、底色、徽记）都由这张专辑的调色板（base / accent / deep / soft）直接做 sRGB lerp 得到，不走 `fromSeed`、不旋转色相，也不替换全局 color tokens。封面提取色的 base 先夹进可读的亮度窗口；深色下的播放高亮提到 OKLab L 0.92，不比正文暗。实现在 `ui/memories/showcase/MemoryPalette.kt`。

浮动底栏（含短窗的左缘分离胶囊）不投阴影（2026-10-01，取代 12dp 阴影及其跨窗口交接）：栏和内容靠下面「溶解」的底部网点场分开，不靠阴影。两个窗口的栏在交接时完全同形同色，叠在一起也没有可见差异。

### 溶解（Dissolve）：滚动内容撞上 chrome（2026-10-01 定稿，2026-10-04 顶部默认潮线、可在设置里换）

滚动内容碰到固定 chrome 时，交界上不盖任何东西。上面默认用潮线（用户可换，见下文“顶部样式可选”），下面用网点：顶部交界和状态栏是两道波浪，内容沉进水线；底部浮动栏周围，图形碎成锚定在自身上的错列网点（约 6dp，与切歌溶解同一网屏）。文字只淡出。实现：`SeamDissolve.kt`（`seamDissolveViewport` 标记滚动容器，默认 `SeamTop.Chrome`；图形 `seamDissolve`、文字 `seamFade`，不在 viewport 内时为空操作）、`SeamHalftone.kt`（底部网点场和顶部网点样式的 AGSL 与 Path 兜底共用的几何）、`SeamCookie.kt`（曲奇浪口）、`SeamTopStyle.kt`（用户选的顶部样式）、`SeamBarField.kt`（栏的几何）、`SeamTide.kt`（潮线，状态栏与 chrome 顶部交界共用一套波浪）。

1. **适用边界**：竖向滚动的内容碰到 Yoin 自己的 chrome 时，顶部交界（Library 的筛选胶囊、详情页的固定顶栏）和状态栏一样用潮线；底部浮动栏用网点场。横向列表和胶囊行的两端保持现状。
2. **图形和文字**：封面、头像、缩略图、带底色的卡片、评分异形徽章是图形；文字、图标、数字是文字，从不变成点。顶部交界处文字淡出，长度取 max(文字带, 0.75 × 字号)；底部不淡出，照常从栏下穿过。
3. **底部**：图形不在栏前停下：到栏上方 20dp 才开始碎，其余的过渡藏在栏后面完成，栏下面已经是纯网点，一直铺到屏幕底边。栏两侧越过页边距的内容也一样。栏去掉阴影。
4. **潮线（顶部交界与状态栏）**：两道页面底色的波浪，内容沉进水线里。状态栏的潮线画在页面之上，状态栏永远干净；chrome 下的潮线是遮罩：viewport 把波浪从自己的内容里挖掉（后层半透明），露出真正在后面的底色，所以在带强调色的详情页上颜色也对。文字在水线以下淡完。（2026-10-04 取代顶部曲线 C 网点成为默认：快速滑动时网点挤成密排的圆盘和针孔，有密恐感。网点仍可在设置里选回。）
5. **尺寸**：顶部潮线静止在交界下 2dp 再让出一个波峰，文字在水线以下 10dp 内淡完。底部静止时，只在栏上方 20dp 开始碎，其余 28dp 藏在不透明的栏后面。滚动时两处都随速度放宽（波浪变高、网点场上移），停下后收回。
6. **颜色**：网点只用内容自己的像素，只允许向底色靠拢，不引入新颜色、不加光晕。栏和内容不靠阴影分开：亮度和栏接近的点在栏边让位，其余照常。
7. **动效**：溶解程度只由位置、滚动速度和余韵决定。滚动时网点被搅乱，停下后在余韵的半秒里回到格点；静止时每个点都在格点上，完全不动。栏下面的点阵始终不流动。潮线的相位跟随滚动和余韵，停下即静止。省电模式和“移除动画”下，关掉余韵、随速度伸缩和无序。
8. **性能**：只有栏周围的图形和交界处的文字重绘，静止时零开销。按亮度让位每个像素多一次采样，只在栏边 8.5dp 以内执行。潮线是两条路径，chrome 下的潮线多一个只有波浪高度的离屏层，几乎没有成本。

- **顶部样式可选（2026-10-04）**：默认潮线；用户可在 设置 › Motion › Scroll edge 换成原版网点（曲线 C，与 36909fad 数值一致）或曲奇浪口。只换 chrome 下的顶部交界，底部网点场、Home 状态栏潮线和文字淡出都不变。选择存在本机（`SeamTopPreference`，认不出的值回到潮线；开了云同步会跟着同步，见 `docs/cloud-sync.md`），切换后已打开的页面立即重画。网点和曲奇浪口下文字按曲线 C 的文字带在交界处淡出。
- **曲奇浪口**：每张图形的顶缘是一道和 Now Playing 胶囊同源的波（每瓣约 30dp，至少两瓣，静止振幅 4dp），形状随这张图自己的离开进度 q 从 Cookie 圆齿、经正弦、到 SoftBurst 软尖，离开途中横滚四分之一圈；进出两端波是平的。尖端曲率半径不小于 4dp，尖端向页面底色退 50%。它是高度场，所以没有孔洞也没有孤岛；静止时只取决于 q，同一行完全对齐。快滑只放宽波带（12→22dp）和振幅（4→6dp），再加一道整排相干的涟漪，随余韵收回；省电模式和“移除动画”下不横滚、不放宽，形变和包络保留。方形图形两端有圆肩，圆形头像没有（API 33 以下 Path 兜底读不到像素，圆形头像也按方形加圆肩；方形框一律按椭圆做底边保护，所以圆形也不留孤岛）。
- 回退开关：`YoinMotion.SeamSettleAtRest`（默认 `true` = 静整动乱；`false` = 上一版，无序度 D 恒为 1，静止时也打旋、前沿也起伏）。D 只在 `seamDisorder()` 一处计算。
- 带底色的卡片内的文字从点阵里抬出、整块画在点阵之上（`seamFade` 自动处理）；嵌在另一个 `seamDissolve` 里的元素交给外层统一拆点。
- Now Playing 升起时底部网点场随栏的显隐目标淡出（点长回整图），收起后长回来。
- API 33 以下走 Path 兜底，几何与着色器一致，但读不到像素，所以不做按亮度让位。
- 快速滚动条（资料库的把手，2026-10-10）的跳转走 `requestScrollToItem`，不经过嵌套滚动：潮线相位、底部网点场的无序度和余韵都不动，内容直接换位。不为它伪造滚动速度，否则快速换位时的点阵会把密恐感带回来。

### 图标：Yoin Symbols

**来源**

- 依赖 `io.github.p2o51:yoin-symbols`（包名 `com.gpo.yoin.symbols`），源码在 `~/Developer/yoin-symbols`。发布到 Maven Central 之前用 composite build：`settings.gradle.kts` 默认 include `../yoin-symbols/android`，CI 传 `-PyoinSymbolsDir=<path>`。
- app 不再用 material-icons，也不在 app 里放图标 vector（`res/drawable/ic_yoin_*` 只剩 3 个 launcher 素材）。缺的符号先加进库的 `generator/`，再在 app 里用。
- 2026-10-05 补齐最后三个：`copy`（歌词选择条的复制，owner：「应按 Yoin 的语言自己画」——前一张纸完整、后一张被挡住的部分剪掉，同 `library` 的唱片套叠法）、`open_in_full` / `close_fullscreen`（NP 侧栏右上角的全屏 / 收回，`launch` 的直角箭头）。App 里已没有 Material 图标，`material-icons-extended` 依赖已删除。
- 另一个例外：16:9 矮屏「Tap to expand」提示里的展开符号动画（`NowPlayingScreen.kt` 的 `UnfoldHintSymbol`）仍在 app 里用 Canvas 画，因为库 0.1.0 只有静态 `UnfoldMore`；库加上动效版后换掉。
- 通知小图标这类要资源 id 的地方，用库的 VectorDrawable：`com.gpo.yoin.symbols.R.drawable.ic_yoin_<name>`（工程开了 `nonTransitiveRClass`，要 `import com.gpo.yoin.symbols.R as SymbolsR`）。

**规则**

- 默认用线性版；「选中」或「已开启」用 *Filled 版（导航的 Home / Library 选中时用 `HomeFilled` / `LibraryFilled`）。
- 播放控制键用 *Filled 多边形版：`PlayFilled`、`SkipNextFilled`、`SkipPreviousFilled`。PLAY / PAUSE 大按钮只有英文界面显示文字；其他语言换成 `rememberPlayPauseSymbolPainter` 形变图标，居中放在和英文词同宽的胶囊里（PlaybackControls 里按当前语言判断：只有 `en` 显示文字，以后新加的语言自动用图标；不用 `values-en` 资源，因为它会让 Lint 把英文当成一种翻译；2026-10-08 owner）。
- 返回、左右箭头、发送在 RTL 布局里自动镜像。
- 按场景选：专辑封面兜底 `Album`，歌单封面兜底 `Playlist`，单曲兜底 `MusicNote`，歌手 `Artist`，队列 `Queue`。

**动效符号和用在哪**（都读 `LocalSymbolMotion`，不显式传 motion）

| 符号 | 用在哪 |
| --- | --- |
| 翻译 `rememberTranslateSymbolPainter` | 歌词工具条的翻译键：翻译进行中「文」「A」绕圈换位，结束回到「文A」；进行中按键保持亮着 |
| 收藏 `rememberFavoriteSymbolPainter`（经 `FavoriteGlyphIcon`） | Now Playing 的收藏键、专辑页曲目行的收藏键；只有用户自己点的变化才跳，规则见下 |
| 均衡器 `rememberEqualizerSymbolPainter` | 专辑页当前曲目：播放时跳，暂停后从两边往中间沉成点 |
| 展开箭头 `rememberExpandSymbolPainter` | 设置的可展开项、Play ▾（横版和竖版）：铰链式先压平再翻过去，不转圈 |
| 播放模式 `rememberPlayModeSymbolPainter` | Now Playing 的播放模式键 |

- 动效档位跟随 `MotionProfile`：`AdaptiveReduced` 对应 `SymbolMotion.Reduced`，其余 `SymbolMotion.Default`（库的默认弹簧就是 M3 Expressive motion scheme），在 `YoinActivityRoot` 统一提供。系统「移除动画」时符号静止。
- **收藏心形的两种变化（D4，2026-10-10 owner）**：Now Playing 的收藏键和专辑页曲目行都一样。
  - **用户点的**（写入还在进行，或刚落地、仍在 60 秒宽限内）：照常由同一个 `rememberFavoriteSymbolPainter` 变化——点亮时填充长满、轮廓跳一下（beat），取消时只缩回填充。按钮自己的按压回弹也照旧。
  - **其余一切变化**都是静默翻转：Spotify 晚到的确认（App Remote `getLibraryState` 或 Web API contains）、资料库同步、写入失败回退、换到下一首。`FavoriteGlyphIcon` 按 key 换一个新的 painter，它一出现就处在终态；新旧两层按 effects spring 交叉淡入，只有填充和颜色在变，不跳。专辑行的底色、描边、心形颜色也都走 effects spring。
  - 实现：状态层是 `FavoriteGlyph(favorite, quietFlips)`，`quietFlips` 只在不是用户点的变化时 +1。仓库的 `FavoriteState.fromUser` 标出用户自己的写入，过了 60 秒宽限就不再算（`YoinRepository.observeFavoriteStates` 是这两处心形的读取入口）。看的是「什么引起了变化」而不只是「值从哪来」：Now Playing 按曲目 id 判断，换了一首，第一个值一律静默，哪怕它是用户先前点出来的状态（Subsonic 的覆盖整个会话都在，Spotify 的在宽限内）。没有改 yoin-symbols。
  - 先出页面再确认：Spotify 的已保存镜像只有最新 200 首，所以页面先按已知状态显示，确认结果晚到时静默翻转。不加载中样式，不加文字。

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
  - **Medium 及以上 · 居中底栏**（宽 ≥ 600 且高 ≥ 480）：竖屏那条本身，水平居中、限宽 600，下边距 24（Wide 28），**高 60（按钮 44 + 8 × 2；2026-10-02 owner 嫌 68 在平板上像块板）**；详情形态把 ▾ 里的动作拿出来单独放（随机播放 / 前往歌手 / 分享）+ 定宽 200 的正在播放 pill。原左侧 Navigation Rail 已删除
  - **合体形态**（2026-10-02，Wide 整窗开着详情列时，适配原则 2「一窗一栏」）：同一条栏横跨两列、上限放宽到 720 —— [Home][Library][正在播放 pill 伸缩][Play ▾][随机播放]，导航在 shell 列那侧、页面的 Play 在详情列那侧、pill 跨过分隔线；前往歌手 / 分享收进 ▾。列开合的 spring 同时驱动栏的姿态；NP 侧栏打开时 pill 折起、栏缩到正好包住剩下的键
- 页面用 `LocalShellChromeInsets` 给 Button Group 让位（底部两种形态留栏高，分离式留左侧 84dp + 右侧挖孔）
- 被拿出来单独放的动作一律从 ▾ 菜单里去掉，不重复
- **详情页 ▾ 菜单的功能（2026-10-05 owner F1「split button 里那些功能的实装」）**：Shuffle play 之后依次是 Play next（Spotify 不显示——它只有一个队列，本来就排在 context 前面）、Add to queue、Add to playlist（只在服务能写歌单时出现，打开 NP 的加入歌单面板）、Open in Spotify / Open in Apple Music（有公开链接时），再后面是可被拿出来的 Go to artist / Share。专辑页对整张专辑、艺人页对艺人全部曲目（艺人页不给 Add to playlist）。Share 发「标题 + 链接」，链接来自 `MusicMetadata.webUrl`（Spotify open.spotify.com；Apple Music 只对目录 id，带账号 storefront；Subsonic 没有，只发标题）。入队后 snackbar 一行「Added 12 songs to the queue」/「12 songs will play next」。窗口页和 Wide 详情列共用 `ui/detail/DetailMenu.kt`

### 🌱 首次引导（Landing，2026-10-09 定稿，原型 artifact PQQMmjTG9SHaoESTaWftEG v3）

- **什么时候出现**：首次启动、还没有任何账号时（启动画面会等到判断完成，老用户永远不会看到它闪一下）。之后删光账号也**不会**回来；设置 › 关于 › 重新引导 可以再跑一遍（已连接的账号保留）。实现：`ui/landing/`，`LandingHost` 由 MainActivity 盖在 shell 之上（同一个窗口），`yoin_ui_hints/landing_done_v1` 记录是否走过。
- **角色**：启动图标的三片拆开当角色（`landing_mascot_*`，几何与 `ic_yoin_launcher_foreground` 一致），粉 = 左手、藏青 = 右手，从中心交汇处挥手；**没有眼睛**（owner 10-09）。入场三片从三个方向落位后挥手两下，每次挥到顶一拍 CLOCK_TICK；连接成功举手蹦一下，输错摇头，选中服务点头。气泡尾巴指着它，换句话时从尾巴尖弹出；气泡标题用 GSF 圆体（ROND 100）。
- **导航**：底部浮动胶囊 = shell 底栏的孪生（同高、同圆角、同色），里面是返回、步骤点、主按钮；内容放在从胶囊里长出来的浮动窗里，Yoin 站在窗顶（平板横屏：Yoin 在左列，窗在右列）。“进入 Yoin”时浮动窗缩回胶囊、胶囊变成首页底栏、Yoin 挥手后飞到左上角淡出，首页按第“主页内容”一步的选择出现。不加阴影。键盘升起时浮动窗站到键盘上（胶囊留在键盘后面），点连接按钮先收起键盘；从悬浮窗回来自动填好 Client ID 时不弹键盘。平板横屏双栏时，左栏的 Yoin 和气泡按键盘上方剩下的高度缩小，不被键盘盖住。
- **步骤**：你好 → 选服务（可多选）→ Yoin 负责 / 不负责 / 你需要 → 每个所选服务一页连接 → 主页内容（有账号才出现）→ 顶部边缘 → 完成。首次启动选服务至少选一个；已经有账号时（重新引导）一个不选也能“跳过”，直接去后面几步。
  - 连接页复用设置的 `ServiceSetupViewModel` / `AppleMusicValidationViewModel`。Subsonic 的“连接” = 先连上服务器再保存。Apple Music 两行单选：默认 Yoin 的令牌服务（`YoinTokenService.URL`，不显示、不用复制），选“你自己的令牌服务”才出现输入框，写明 Apple 开发者计划每年 US$99；不做价目卡。
  - **悬浮窗导航**（Spotify）：`SpotifyGuideActivity` 画中画小窗浮在浏览器（Spotify for Developers）上，像地图导航一样一步说一件事，共 6 步；点窗口出现系统按钮（上一步 / 复制回调地址 / 下一步，最后一步“回到 Yoin”）。回到 Yoin 时读剪贴板里的 32 位 Client ID 自动填上。不用“显示在其他应用上层”权限。缩小前那半秒是居中的 4:3 卡片（深色底），系统缩小动画从这张卡片开始（`sourceRectHint`），不会先闪一整屏紫色。两个回调地址也折叠在页面里，给在电脑上操作的人。
  - 主页内容：`HomeSection` 全部区块，开关 + 拖把手排序（触感同主页编辑态 §F）；行数不放进引导，列表下一行小字说明去主页长按调。结束时写进本次新加账号（重新引导时是当前账号）的 `home_layout`。
  - 顶部边缘：三种样式并排，每个是**真的**滚动边缘效果的小图（`ScrollEdgePreview`，`seamDissolveViewport(style = …)` 只给预览用）；下面一句说明，说明框按三句里最长的那句定高，切换不跳（owner 10-09）。选中立即生效。
- **返回**：分步流程（新类型，见 predictive-back skill）：第一步首次启动不拦截（系统回桌面），重新引导时第一步返回 = 关掉引导；其它步返回上一步。手势只驱动当前步的 AOSP 预览姿态（缩向 0.9），松手提交后再按弹簧换步，不擦洗换步本身。

### 🏠 主页

- Mix / 推荐区块（从 Navidrome 获取随机专辑、最近添加等；「最常播放」排序依赖 Scrobble，MVP 阶段使用服务端已有数据）
- 辅助可视化区域（当有曲目播放时，显示实时音频可视化效果）
- Memory teaser 只显示一条轻提示，点入 shell-owned Memories surface；Feed 不承载重型 Memory 卡片流
- 右上角 ⚙️ 设置入口
- **版块与布局**：Activities、Jump Back In、Recently Added、Rediscover，顺序和开关按 profile 存（`home_layout`）。只在真的改变时写入；改回默认就删掉这一行，等于「从未定制」。定制过的用户遇到新版块时，新版块以关闭状态追加进托盘并标 "New"；没定制过的按各版块的默认开关（Rediscover 默认开、排在最后）。
- **页头：两行问候（2026-10-09 owner 定，原型 artifact GWEBwdavGiwS1ngqmcqTyF）**：上面一行是按时段换的小字问候（titleMedium，次要文字色：5–11 点早上好、11–13 中午好、13–18 下午好、其余晚上好；英文只有 morning / afternoon / evening，整点自动更新），下面一行是 Home（headlineMedium，比原来的 headlineLarge 小一号；手机横屏再小一号 headlineSmall），两行都用 Google Sans Flex 的圆体（ROND 100，Medium）——中文没有这个字形，落到系统字体。编辑模式里问候语跟页头图标一起淡出，Edit Home 照旧盖在 Home 上。**冷启动开场**：Yoin 标志（三臂，tertiary / primary / secondary）在标题位置依次弹开（带回弹），停一拍后转 70° 缩小淡出，问候语和 Home 依次浮上来，约 1.4 秒；每个进程只播一次（切回 Home、转屏不重播）。首页加载时会临时报「动效压力」（AdaptiveReduced），开场最多等 0.4 秒让它解除；一次加载最多报 3 秒（`HOME_LOADING_PRESSURE_MAX_MS`），挂住的加载不会让整个 app 一直停在降级动效；真正的减少动态（低内存、省电）直接显示两行字。实现 `ui/home/HomeGreetingTitle.kt`。
- **账号切换器（2026-10-09 owner 定，参照 Play 商店的 Android 账号卡片）**：页头右上角的设置齿轮换成当前账号的头像（Settings 的 AccountAvatar，32dp；还没有账号时仍是齿轮）。点开是一张卡片（自己的 dialog 窗口，宽 min(屏宽 − 32, 420)，屏幕正中（owner 10-09 改：不放顶部）。**头像长成卡片**：一个空间弹簧 p 驱动卡片底色从页头头像的位置和圆度长到卡片的矩形（28dp 圆角），头像同时飞到卡片里 72dp 大头像的位置并放大，内容在 p 0.45–0.95 淡入，遮罩（scrim 32%）跟同一个 p 渐变；收起原路缩回头像，期间页头头像隐藏。弹窗窗口自己的变暗和进出场动画关掉，等卡片排好版、窗口画出两帧后才起弹簧（否则窗口出现前弹簧已跑完）。头像按钮不加波纹，变形本身就是反馈。减少动态：原地淡入淡出）：顶行是当前账号的服务行和关闭 ×，下面大头像（72dp）、账号名、「管理账号」描边按钮（进设置）；分段组一：其他账号（头像 + 名字 + 服务行，不可用的账号显示原因，点了进设置）和「添加账号」（进设置）；分段组二：「设置」和「编辑首页」。点其他账号即切换并收起卡片。卡片数据和设置页同一个映射（`ui/settings/ProfileCards.kt` 的 `profileCardsFlow`），两边永远一致。返回：卡片是自己的窗口（`DialogWindowPredictiveBack` 接这个窗口自己的返回），系统返回只关卡片，首页的返回桌面不受影响；预测性返回时卡片做 AOSP 预览姿态（缩向 0.9、偏向手势一侧、跟手），不擦洗变形，提交才缩回头像；点卡片外也关。实现 `ui/home/HomeAccountSwitcher.kt`。
- **加载顺序与切换账号（2026-10-10 owner 拍板，P2 分层发布 + Q14b）**：首页不再等所有区块到齐才出。本地层先出：页头、Activities 动态、任意年龄的 Jump Back In 候选池（过期的随后轮换）、笔记 1×2。记忆信号（记忆胶囊、JBI 的记忆 1×2、Rediscover）和网络区块（Recently Added、Recently Played、Your Playlists、hero 脚注、Spotify 的 recently-played）到了各自拼进同一页：区块出现走 `animateItem` 的淡入和位移弹簧，hero 卡的脚注行用空间弹簧展开并淡入，整页入场不重播。某个区块读取失败就保留它上一次的内容，读到空才清空——本地读取也一样：候选池读不出来时 JBI 的封面原样留着、这次不轮换，笔记 1×2 和 Rediscover 的单曲读不出来也留着上一次的；隐藏的区块照旧加载。Spotify 的 Activities 以 recently-played 为准：本机既没有动态记录也没有候选池时，转圈等它回来再出，不先亮一张空卡；本地层先出的 hero 不读脚注，等 recently-played 回来、hero 定了再读（只读最终那张专辑，不为马上被换掉的 hero 多发一次请求）。**切换账号**：从点下账号起，到新账号第一版内容出现前，首页显示现有的加载样式（`YoinLoadingIndicator`，不加文字），旧内容按入场的弹簧反向淡出；新账号有进程内缓存时提交后立刻出现。切换失败就回到原账号原样的内容（不重新加载）：滚动位置按账号留着（每版内容带着自己账号的 id，列表状态按它保存），旧内容按入场的弹簧淡回来；换到别的账号则从顶部开始，并丢掉上一个账号留下的状态。从首页发起的切换另在 shell 的 snackbar 提示「无法切换账号」（设置页发起的由设置页自己提示）。加载期间的动效压力会把全 app 切到 AdaptiveReduced，而这一刻账号卡片正在缩回头像：卡片按打开时的动效档位走完收起，中途不跟着切档。实现 `HomeViewModel`（`HomeLoad`、`observeSwitching`）、`HomeScreen`、`HomeAccountSwitcher`。
- **Spotify Activities 的艺人头像（2026-10-10 owner 拍板，Q16）**：recently-played 里的艺人不带图。发出 fresh 列表之前，先用本机已有的头像补上：`spotify_home_artist_cache` 里 30 天内的答案、库缓存里**关注的**艺人（库缓存里从收藏专辑和 Liked 歌曲推导出来、没关注的艺人带的是专辑封面，不算头像，照常去问）、打开艺人页时记下的头像、磁盘上的艺人详情；仍然没有的，先用这次播放的专辑封面兜底（和艺人详情页一样）。这一轮加载完、首页**静止** 1.5 秒后，才为 bento 带图卡片上的艺人（文字条带只有字，不算；平板 XL 最多 20 个）逐个请求 `GET /artists/{id}`。静止 = 首页在前台（resumed）、没有 Now Playing / 详情栏 / Memories 盖着、列表没在滚动；切到 Library、打开详情页、进后台都不算。请求中途首页不再静止，下一个请求就等它重新静止 1.5 秒再发。每个请求都过 `SpotifyRateLimitGate`；gate 关着或遇到第一个 429 就停，遇到 429 之后本进程不再为这个账号请求。答案存进 `spotify_home_artist_cache`：有图存 30 天，确认没图存 24 小时，网络错误不存。补到的头像经 `ExpressiveMediaArtwork` 用 effects spring 渐显换上；卡片底色从专辑封面的颜色直接弹到人像的颜色，不经主题色（`rememberExpressiveBackdropColors(holdUntilResolved = true)`）。由 `ServiceFeatures.activityArtistPortraits` 门控，只有 Spotify 打开；Subsonic 和 Apple Music 的 Activities 来自本地记录（自带封面），不走这条路。实现 `SpotifyActivityArtistArtwork`、`HomeViewModel.fillActivityPortraits`。
- **久别重逢 Rediscover（2026-10-04；2026-10-05 去掉 8 分门槛）**：有过记忆的专辑（专辑评分、专辑乐评、笔记、任一曲目评分，只播放过不算），且在 Yoin 里 90 天以上没播放过；少于 4 首的专辑不以专辑卡出现（复用 Memory 的 `meetsMemoryTrackCount`，`MEMORY_MIN_TRACK_COUNT` = 4：已是 Memory 的直接通过，曲目数还没加载到（0）时不拦），单曲和短 EP 以单曲卡回来（见下条）。有分的在前、高分在前，没分的排在所有有分的后面，同分时久别在前。分数 = 专辑评分；没有时用曲目平均分，须 ≥ 60% 曲目有评分，否则不算分。有分时封面右下角挂评分徽章（专辑评分 = 实心色块，曲目平均 = 描线，同 Memories 印章语法；色取封面调色板，暗色 accent、亮色 base，等宽数字），eyebrow 只写 "Not played in Yoin for 7 months"；没分时 eyebrow 先写记下了什么（"Reviewed" / "3 notes" / "2 tracks rated"）。同一排同类卡片的 eyebrow 按全排最高的那条留行（最多两行，短的贴着标题），标题、歌手、脚注在一排里对齐，文案不删不缩（2026-10-05 真机 QA）。时间只看播放历史，浏览不算，所以文案写 "in Yoin"。和 JBI 的 memory 卡、记忆胶囊、Recently Added 载入的专辑去重（Recently Added 按宽度只摆 12–20 张，ViewModel 不知宽度，载入的全算；2026-10-05 真机 QA），本次会话里播放过的专辑离开货架。点按进专辑详情，不进 Memories。所有宽度都是横滑货架：N ≤ 2 最多 6 张，N 3–4 和手机横屏 2 张，N ≥ 5 3 张。没有数据就不渲染，编辑态显示占位说明 "Albums and songs you rated or wrote about come back here when it's been a while"（2026-10-05 加入单曲后的文案）。
- **Rediscover 单曲卡（2026-10-05，owner「rediscover 本身也可以加入一些歌曲」）**：评过分（> 0）或写过非空笔记、在 Yoin 里播放过但最近一次已是 ≥ 90 天前（只看 play_history）的曲目也回来（`PlayHistoryDao.getRediscoverSongs`，按 profile + provider，每次最多读 64 首）；分数 = 曲目评分。专辑卡和单曲卡按同一个顺序交错排（有分在前、分高在前、同分久别在前，完全相同时专辑在前，`selectRediscoverShelf`），货架总数不变；专辑已作为专辑卡上了货架的单曲不出现。单曲和 JBI 的笔记 1×2 卡、Recently Added 的曲目去重，所在专辑已在 Recently Added 里的单曲也不出现（单曲卡用的是专辑封面和专辑名；2026-10-05 真机复验）；本次会话里播放过这首、或播放过它所在的专辑，卡片离开货架。卡片是专辑卡的小一号：Circle 形底（Home 的实体形状映射）76dp、内边距 12dp、卡高 116dp（专辑卡是 104dp 底、132dp 高），在货架里垂直居中；有分时封面角挂实心评分徽章（自己的曲目评分）；只有笔记时显示一行最新笔记的摘录，左侧是曲目笔记专用的 2dp 竖线；第二行 "歌手 · 专辑"。点按只播放这一首（Track 从播放历史重建），不震，TalkBack 点按标签 "Play"，徽章读作 "Your rating 9.0"。实现 `data/memory/RediscoverSelection.kt`、`ui/home/RediscoverSection.kt`。
- **Rediscover 的两个新专辑来源（2026-10-05）**：单曲笔记和单曲评分本身不带专辑，经这首歌的 play_history 归到专辑（`SongNoteDao.getNotedAlbumAggregates`、`LocalRatingDao.getRatedAlbumAggregates`，按 profile + provider），在 Memory 的 48 个种子扫描范围之外按信号数（同数按最近写入）取前 16 张，接在原有候选之后。只在 Home 构建 Rediscover 池（`includeIneligible = true`）时查询；这些专辑带 `inMemoryScan = false`，**只进 Rediscover，永远不会成为 Memory**（`build(n, true).memoryEligible(n) == build(n)` 不变）。
- **Recently Added 在平板上多放内容（2026-10-05）**：只加列、不放大卡片，曲目格永远 2 行。平板竖屏 3×2 首曲目 + 16 张专辑，平板横屏 4×2 + 20 张；手机（含手机横屏）不变，仍是 2×2 + 12 张。规则在 `HomeFeedDensity.kt`。
- **卡片背景色（2026-10-05）**：Home 卡片的 backdrop 色和 Now Playing 用同一个取色器（`CoverSeedExtractor`，带过滤的 16 色），蓝封面不再被肤色 / 赭色带染成土黄；旧的不过滤 12 色只在它什么都取不到时兜底（全棕褐封面、只有近黑近白）。降级状态（`AdaptiveReduced`）下也取色——颜色不算动画，和 Memories 一致；只在启动预热期间和滚动中推迟（每次要解码一张 200px 封面再做 16 色提取），恢复后补读一次，不丢。这条取色路径详情页同样在用（`ui/component/ExpressiveBackdropPalette.kt`）。
- **就地编辑（2026-10-04，P0）**：取代 2026-07-02 的列表编辑器（`HomeLayoutEditor` 已删除）。
  - **进入**：在页面任意处长按，页边也算。按在卡片上，进入编辑并拿起这张卡所在的 section，底板从按点长出来；按在空白处只进入、不拿起。按住过半时，被按的块先轻微缩小、底板预显。其它入口：鼠标右键；feed 末尾常驻的 "Edit Home"（托盘里有没见过的新版块时带 "New"）；TalkBack 里卡片的长按和 section 标题上的「Edit Home」动作。header 的 Memories 入口、设置齿轮和气泡是排除区，长按不进入。
  - **编辑态**：标题交叉淡化成 "Edit Home"，前 2 次编辑在放得下时旁边显示 "Drag to reorder"。每块加底板、隐藏键和拖动把手（只剩一块时没有把手）。卡片级轻摆：约 1°、2.4Hz，6 秒没有触摸就渐停，任何触摸恢复；交界带内振幅为 0，省电模式和「移除动画」下不摆。卡片不可点，空 section 显示占位块，Memories 的下拉和气泡关闭。
  - **底板 V1（2026-10-05 owner 拍板 H1）**：底板向外扩到「页边距 − 4dp」，夹在 8–24dp 之间——手机页边 16dp 正好外扩 12dp，平板最多 24dp（平板的宽页边不会铺成一整块板）；圆角 20dp。编辑态 feed 的块间距随编辑进度（smoothstep）从 18dp 拉到 32dp（手机横屏从 10dp 拉到 32dp），底板借这段间距上下各外扩 12dp（相邻底板之间至少留 4dp）。横向出血的货架在编辑态收进底板里，在底板内缘 12dp 内 smoothstep 渐隐——这是「不许页中截断」规则**只在编辑态成立的例外**（owner 已接受），普通态货架照旧出血到屏幕边。长按进入时被拿起的块在间距变大的过程中钉在手指下（`HomePlateSpacingAnchor`：每帧按「它上方的间隔数 × 间距增量」滚动 feed，上面的块向上散开、下面的向下散开，和底板一起完成一次动画）。实现 `ui/home/edit/HomePlateVariant.kt`；V0（拍板前的 8 × 6dp 底板）/ V2 / V3 只留给 debug QA 台（`MemoriesScreenshotActivity --es plate`）对比。
  - **排序**：按住把手，或在块上按住片刻，拿起这一块；一开始拖动，所有块收成带 3 张真实封面的签条，在手指下短距离排序，松手后展开落位。结算途中可以再次抓住。参数按原型 `proto.js` 原样移植。
  - **行数（H5，2026-10-05；改判 Q5 的「只动整块、不做尺寸档」）**：只有 Activities 和 Jump Back In 能调（`HomeSection.supportsRows`）；Recently Added 必须保持一张专辑卡高，Rediscover 是货架，都没有行数。四档预设 S / M / L / XL，**L = 有预设之前的版式**（每种屏幕逐像素不变）。存的是预设、不是行数，每种屏幕自己把预设翻译成版式（`ui/home/HomeRowPresets.kt`），所以跨设备同步的是「选了哪档」。同一屏幕上版式相同的相邻预设合成一个停靠点（手机横屏 Activities 的 S / M / L、2 行宽窗 JBI 的 S / M、历史太短分不出两档时），把手在那里只有一档，已存的预设保持不变。档与档嵌套：大档放得下小档的全部卡片（卡片可以换位置或换类型，往上走从不消失）。
    - Activities：手机 S = 只有大卡，M = + 一行「小卡 | 宽卡」（3 张），L = + 一条条形卡（4 张，今天的版式），XL = 在条形卡之前插一行镜像的「宽卡 | 小卡」（6 张，L 的条形卡在 XL 里长成宽卡）；窄手机每行是两张等宽小卡；手机横屏 S = M = L = 今天那一行，XL 多一行（宽 1.4 : 小 1 : 小 1）。平板单元网格见 `docs/adaptive-principles.md`「Feed 密度」。
    - Jump Back In：手机货架（3 列）2 / 3 / 4 / 6 行，手机横屏（6 列）1 / 1 / 2 / 3 行；只有一行或不足 9 格时只放 1 张信号卡，否则 2 张。平板保留今天定稿的 L 模板，其它档从它推出来（XL 每列底部追加一行封面，M、S 从底部逐行裁掉），规则同见 adaptive-principles。
    - **把手**：块底板右下角一个圆角的反 L「⌟」（iOS 小组件改尺寸的那个角）：24dp 字形、3dp 圆头描边，拐角和底板圆角同心（半径 20 − 6 = 14dp），离底板角 6dp，触摸框 48dp；平时 onSurfaceVariant，按住变 primary 并放大 12%，鼠标悬停给 60% 的按住态。和编辑徽标一起出现，只有一个停靠点时不显示；把手是编辑手势的排除区，按它永远不会拿起整块。
    - **拖动**：块的内容高度是这一块唯一的驱动值，1:1 跟手；越过最大 / 最小档走橡皮筋（量程 56dp、系数 0.55）。越过两档中点再 6dp 才换档（滞回），每次换档打 SEGMENT_TICK、把手脉冲一下。松手时速度 ≥ 650dp/s 且按 0.15 秒投影越过下一档的中点就多走一档，否则落到最近档；用默认空间弹簧带着松手速度落位（越界后松手只带 25% 的速度）。手指离底栏或状态栏 64dp 以内时 feed 自动滚动，最快 900dp/s。拖动时这一块不摆、下面的块 1:1 跟着推开。两档之间两档同时组合，每张卡在两个位置之间插值：两档都有的卡移动；只在大档出现的卡从 0.88 缩放、自上而下错开淡入；换类型的卡（条形卡 → 宽卡）先淡出再淡入。开始整理拖动时，行数过渡先停在当前档。
    - **写入**：抬手那一刻写入（档位真的变了才写，打一拍 CONFIRM），每次换档算一步 Undo；键盘 ↑ 少一档、↓ 多一档；TalkBack 用块自己的 "More rows" / "Fewer rows" 动作。Undo、Reset、键盘、TalkBack 换档都和松手走同一条弹簧。
    - **存储**：按账号存在 `home_layout` 里该块 `HomeSectionPref.config` 的 `{"rows":"s"|"m"|"xl"}`；L（默认）不写这个字段，也不会写出 `"config":null`。认不出的键原样保留；认不出的 rows 值按 L 显示，但值本身保留。不升数据库。Reset Home 连档位一起清掉；重排时各块的设置跟着块走。触感见 `docs/haptic-feedback.md` §F.1。
  - **隐藏与恢复**：隐藏的块缩放淡出，进 feed 末尾的 "Hidden" 托盘；托盘每行点 + 回到原来的位置，"Reset Home" 回到默认。每一步立即保存，可以 Undo（最多 20 步，退出即清空）。全部隐藏时，普通态显示 "Your Home is empty" 卡。
  - **退出**：Done、系统返回（= Done，离散，不震；返回进度预览是 P1）、点空白处。切 section、打开 Now Playing 或详情、进设置、App 退到后台时直接收起。状态是 `HomeSurface.Edit`，由 shell 持有的 `HomeEditController` 独占写入；返回归 `ShellBackOwner.HomeEdit`，优先级 NowPlaying > HomeEdit > DetailPane > Memories。
  - **无障碍**：编辑态每块是一个 TalkBack 停留点，播报 "Section j of N"（能调行数的块再加 ", N rows"），带 Move up / Move down / Hide 动作，能调行数的块另有 More rows / Fewer rows；托盘行带 Show。
  - **触感**见 `docs/haptic-feedback.md` §F。Pixel Tablet 没有振动马达，每个触感时刻都有能看到的等价动效。
- **底栏编辑姿态**：随编辑进度变形，正在播放 pill 在所有形态下都折起，编辑期间不能从底栏打开 Now Playing。竖屏底栏和居中底栏是 `[Undo|Add] [Done]`：Home 槽换成 Undo（有可撤销的步骤时）或 Add（托盘非空时，滚到托盘），两者都没有时是变暗的 Undo；Library 槽换成主色的 Done。合体形态是 `[Undo|Add][Done][Play▾][随机播放]`；只剩图标的形态只显示图标；分离式挖孔带同构。Undo 暂用文字标签，所有形态都显示文字，等 Yoin Symbols 发布 Undo 符号后再换成图标。

### Album Memory

Yoin Memory 是 **当前 profile 下的 local-first 专辑记忆层**，不是 Spotify Wrapped、stats.fm、Last.fm clone，也不是全平台实时统计服务。Spotify、Subsonic、未来本地文件或其它来源都只是 provider；第一阶段不做跨 profile 自动合并、不做跨源 canonical album merge、不做通知 scrobbler。

v1 的 Memories surface 定义为 **profile-local, album-first listening journal deck**。顶栏只写 `Memories`（和 Last heard），不再加 `Album memories` 副标题或 chip。SONG / PLAYLIST Memory、timestamp memory / music time machine（例如 this day last week）属于后续方向，不进入 v1 主 deck。

Memory 的核心单位是 album。候选由当前 active profile/provider 下的播放历史、曲目评分覆盖率、Album Note、Song Note、AskAI 记录、专辑评分与 NeoDB review 状态共同生成。推荐型 Memory 的 gate 是：

`ratedTrackCount / totalTrackCount >= 60%`

但有专辑 review，或至少两条 album/song note 的专辑，也可以进入 Memory。Note 是用户自己的观点，AskAI 是参考资料；二者必须在 prompt 和 UI 表达中分开，不能混成同一种用户立场。

**v4 起卡片不再罗列「为什么成为 memory」（2026-10-04）**：卡面和日记都不写证据句，覆盖率、笔记数、AskAI、NeoDB ready/synced 都不上卡。听歌的事实只出现在三处：动机短句、Yoin 的旁白、日记页脚的两个数字。评分显示规则不变：有 album rating 时优先显示 album rating，否则显示 average track rating；卡面、徽记标签和日记用同一个一位小数（统一四舍五入，9.95 显示为 10.0）。

**展示柜两态 v4（2026-10-04 owner 验收，同日落地 Compose；取代 2026-07-26 的「印章」v2.2 / v2.3）**。规格和 owner 决定在 `docs/handoff/memories-showcase/`（`README.md`、`PLAN.md`，原型 `memories-showcase-v4.html` / `twostate4.html` / `groove.js`），代码在 `ui/memories/`（`showcase/`、`emblem/`、`award/`、`copy/`）。

- **模型**：Memories 仍是 ShellOverlayUp（从 Home 下拉打开）。横滑 deck，每页两态：**卡片**（展品）和**日记**（读和写）。一个共享顶栏：左边「⌃ Home」胶囊，任何状态下都只回首页；右边是页点，每个点有 32×48 的点击框，点按落到 x 方向最近的点；从胶囊或页点上起手的横拖照样翻页。两个控制器：外层 q（`RevealState`，退回 Home）和内层 p（`MemoriesDiaryState`，0 卡片 ⇄ 1 日记）。每次手势越过 slop 时，按方向和起点交给其中一个，不会同时交给两个（`MemoriesGestures.kt`）。全页扁平：没有阴影，封面裸图无描边。
- **卡片态**：
  - 两簇。展品簇定高：顶部留白 → 封面 + 徽记 → 拟题 → 专辑行，所以每张卡的封面顶边同位。预告簇贴底：摘录 → [Diary + 笔记数徽章 | Go to album] → 「Swipe up for Home」提示（短屏只留箭头）。中间的空隙吃掉余量。
  - 手机封面 256dp、徽记 96dp；短屏（高 < 760）168 / 72。徽记挂在封面右下角，向右、向下各探出自身尺寸的 0.3 / 0.24。
  - 摘录只用整句，永远不出省略号。依次试：乐评开头的整句（从多到少）；没有乐评时，第一条单曲笔记；最短的一条单曲笔记；最后只剩署名（小字「Your review」/「你的乐评」，日期 tabular、60%，不写 "in Diary"）。放得下哪个就用哪个。字号按长度分三档：≤ 16 字 22 / 500，≤ 60 字 17，更长 16，用 GSF（2026-10-07 起，原为系统字面）。Medium 上摘录只是预告：最多两句、约 60 个加权字。
  - 按钮：Diary 是 tonal（专辑 ink 14% 底，高 48），笔记数是旁边较轻的计数徽章；Go to album 是专辑色实心胶囊，不带箭头。
  - 手势：卡片上任意位置上滑一次，回首页。往下拉是 0.3× 橡皮筋（最多 −90dp），松手回卡片；拉下后再往上推也只停在卡片。打开日记只靠 Diary 按钮。
- **日记态**：
  - 只能纵向滚动。每页有自己的滚动位置，离开这一页就归零；开着日记横滑，下一张卡也是日记态，从头读。顶部交界用潮线（`seamDissolveViewport`），底部 40dp 渐隐。
  - 顶栏：胶囊收窄到 36dp，只剩箭头；后面是 40dp 封面（4dp 圆角）和专辑名、艺人行、⌄。艺人行是歌手和年份两段（年份 60%），放不下时先去掉年份，还放不下才跑马灯；跑马灯只在页面停稳、日记完全打开时滚。
  - 从上到下：标题行（Yoin 的标题，右侧是原生 48dp 徽记）→ Yoin 的段落（只在没有乐评时出现：讲你是怎么听的，问句接在同一段末尾）→ 你的条目 → 两段真实内容之间放 • • • → 曲目行 → 结尾。
  - 你的条目：有乐评就是乐评，不加引号、不署名；16 字以内又没有笔记的短乐评放大（26，平板 30），停在可视区 38% 的光学位置。没有乐评就是今天的空白日记页：左侧日记竖线加保存胶囊（共享组件 `JournalRail` / `JournalSavePill`；NP 的笔记页已改用方向 A，不再有竖线），用 BasicTextField，不用 OutlinedTextField；Cancel / Save 落在下面。保存后原地变成乐评条目：竖线淡出，正文左移 14→0（空间弹簧），标签 Today → Your review 交叉淡化，打一拍 CONFIRM。保存失败保留草稿，用 snackbar 提示。
  - 回卡片：在顶部继续下拉；把顶栏往下拉（1:1，滚动冻结）；点顶栏封面或 ⌄；系统返回。从正文里下拉越过顶部时，前 24dp 走半速；如果起手时正文已经滚动过，松手时还在半速带里就留在日记。惯性滚到顶只停住。
  - 回首页：顶栏上推；日记已经停在底部时，新起一次上推（越过末尾再推）。惯性滚到底只停住，不回首页。
- **曲目行（选项 A，显式推翻 v2.2 的「别复活曲目表」）**：日记只列有评分或有笔记的曲目，不是完整曲目表；完整曲目表仍在 Go to album 打开的专辑页里。专辑笔记排最前，左边一颗 6dp 空心小珠，不可点。每条曲目行高 48：曲号 / 歌名 / 分数。笔记像歌词一样挂在所属曲目下面：40dp 列里放时间戳，后面是正文，两者按基线对齐。点曲目行从头播放；点笔记行播放这首，等它成为当前曲目后 seek 到笔记的时间锚点一次（4 秒内没就绪就放弃）。正在播的曲目和播放头所在的笔记只变色高亮，不压暗其它行，也不自动滚动。
- **结尾**：跟着内容走，离上一块 56dp。依次是 28dp 的导出槽（三圈发丝细环）、两个大数字（在 Yoin 里的播放次数；距第一次播放的天数，"days since Mar 14"）、Go to album、NeoDB 入口。页脚只有这两个数字，取代旧的证据句和 NeoDB 状态页脚。「听过」只看播放历史，访问专辑页不算；从没在 Yoin 里播放过的专辑，两个数字整个不显示，顶栏也不写 Last heard。
- **NeoDB 状态行（2026-10-04 PLAN Q1；2026-10-06 改）**：日记末尾、两个数字下面，一行安静的小字（无底色，点按区 48），只在 NeoDB 已配置时占位。和评分面板同一套状态（`AlbumNeoDbSync`）：「Synced to NeoDB」「Syncing to NeoDB…」；有没推的改动时「Sync to NeoDB」（主色、可点）、失败时状态「Couldn't sync to NeoDB」旁边是「Retry」文字按钮。没写过东西时空着。同步是自动的（owner R3）：日记里存下乐评后自动推送。失败另出一次 snackbar，不崩溃。卡面不显示同步状态。（原来是写死的「Push to NeoDB」按钮，推送成功后也照旧显示，owner 10-06 报。）
- **不署名**：Yoin 写的字不署名，去掉 "Written by Yoin"（取代 2026-07-26「Yoin 代笔文案必须带署名」）。Yoin 的字和用户的字靠字体和位置区分。
- **字体（取代下面 2026-07-26 规范里 Memories 的部分）**：
  - 宋体（`YoinSerifTitle`）只给 AI 拟题：卡片 26sp SemiBold（短屏 24，Medium 30），日记标题 22（大屏 24），对开左页 30（收紧档 27）。
  - 没有 AI 拟题（也没有用户自己起的名）时，标题位放专辑名（GSF），专辑行只留歌手。**不再生成动机短句**（「18 plays since August」「三个季节，一首一首」「四天，四条笔记」；2026-10-06 owner：数出来的一行不算标题，没有就不要）——播放次数和时间跨度本来就在日记末尾的两个数字和旁白里。主页 JBI 的 memory 卡和专辑页第 2 页同理：没有写出来的标题就不放标题行。
  - Yoin 的旁白：GSF ROND 60，16 / 1.6，onSurfaceVariant；末尾的问句 onSurface、500。
  - 用户的字（摘录、乐评、笔记、日记）用 GSF（owner T1，2026-10-07：原来是系统字面 `FontFamily.Default`，全 app 不再留 Roboto）。页脚数字用 GSF 500、ROND 40。其余标签和按钮用 GSF。
- **语言**：Yoin 写的成段文字（动机短句、旁白、问句）跟用户的写作语言。统计乐评和笔记里的字，一个汉字按两个拉丁字母算，汉字多就写中文。出现假名或谚文（日文、韩文，没有模板），或者用户还什么都没写时，用 app 语言。按钮、署名、页脚标签这类界面文字跟 app 语言；app 目前只有英文界面。
- **旁白（2026-10-04，PLAN Q5）**：继续由 Gemini 生成（BYOK），提示词改成原型的文风：第二人称、过去时、一两句，只讲这张专辑是怎么听的，只用本地信号（在 Yoin 里的播放次数、时间跨度、季节、曲目名、最高分的曲目）；不评价音乐，不引用也不转述乐评和笔记，不提 memory 这个机制，最后用一个承接前文事实的问句收尾；动机短句已经说过的事实不再重复。发给 Gemini 的只有事实清单，没有用户原文。结果缓存在 `memory_copy_cache`，旧提示词的缓存永不复用。没有 key 或生成失败时，用本地的四套模板（中英各一版）。有乐评时没有旁白。
- **刻纹徽记（唱片刻纹，`ui/memories/emblem/`）**：
  - 几何：一圈对应一首曲目，外圈是第 1 首；曲目比这个尺寸能画的圈数多时合并（≥ 110dp 12 圈，≥ 88 8 圈，≥ 64 6 圈，其余 3 圈，< 60 用简化画法）。有评分的曲目刻成实线，没评分的留点阵。中心标签带分数：专辑分是实心 Cookie12Sided，均分是浅底上的描边圆，未评分是一圈虚线曲奇「空模子」（中性 token），模子外有一圈慢涟漪。
  - 扁平：每个填充和描边都是一种平色，没有光泽、扫光、发光、阴影，也没有笔记小点。
  - 倾斜：表现为颜色变化，不是光。读 `TYPE_GAME_ROTATION_VECTOR`，相对 1.6 秒的慢基线取倾斜（拿稳的姿势会慢慢回到中性），±18° 映射到 ±1 并限在单位圆内，再用弹簧（阻尼 0.9，刚度 90）跟随。外缘和最外两圈分成 24 段平色，朝倾斜方向的一侧变浅、另一侧变深；静止时和不倾斜完全一样。只在卡片态、当前页、q = 0、没在横滑、没开减少动态时注册传感器；日记里的 48dp 不跟倾斜。
  - 尺寸：手机卡片 96，短屏 72，Medium 和对开 96–124（随封面），手机横屏 48–72，日记标题旁 48（按 48 原生绘制，不是缩小的 96）。徽记是展品，不可点。
  - 四档获得动画，按卡面显示的一位小数分：1 档 < 6.0，盘面从 −16° 回正（0.62s）；2 档 6.0–7.9，从 −330° 转回（1.0s）；3 档 8.0–9.9，转一整圈，有评分的圈依次点亮（1.3s）；4 档恰好 10.0，转两整圈，标签压下，外缘转成强调色（1.6s）。未评分没有动画。每个通道都是闭式弹簧的函数，用 `withFrameNanos` 推进、只在 draw 里读，没有 tween。
  - 触感：每一拍都落在画面同一条弹簧曲线上（停稳帧、过冲峰、标签触底、某一圈最亮的时刻），一档最多 7 拍，相隔不到 45ms 的两拍合并。API 31+、有马达、支持全部 primitive 时，整档组合成一个 `VibrationEffect.Composition`（33+ 用 USAGE_TOUCH，跟随系统触感开关）；否则在同样的时刻回退到 `YoinHaptics`。需要 VIBRATE 权限。逐拍映射待写入 `docs/haptic-feedback.md`。
- **获得动画的生命周期（`MemoriesAwardLifecycle`）**：
  - 每张卡每次打开 Memories 只颁一次，时机是它第一次完整展示：打开时 reveal 到 85%（q ≤ 0.15）；横滑停到离这张卡 0.15 以内，且手指已经抬起；日记收回到这张卡（p 落到 0）。第一拍离手指抬起至少 120ms。系统返回预览进行中不开始。
  - 还没颁的卡停在动画第 0 帧（一道都没刻），不会先显示成品再擦掉。
  - 任何拖动都暂停拍点，画面照播；回到同一张卡接着打。在高潮拍之前真正离开这张卡，就撤销这次颁奖，下次重新完整播放；过了高潮就算颁过。
  - 日记里从不颁奖。开着日记横滑遇到的卡，只让 48dp 小徽记「点头」（scale 0.6→1，2 档起再从 −90° 转回），打这一档最强的那一拍，强度减半；完整颁奖等回到卡片再播。
  - Memories 关闭就卸载，所以每次打开都会重新颁奖。
- **颜色**：每张卡的颜色由这张专辑自己的调色板直接 lerp 出来（见「颜色系统」第 6 条）；背景极光随当前卡换色，走效果弹簧。
- **五个默认选项（2026-10-04，原型的推荐项）**：① 曲目行只列有评分或有笔记的曲目；② 没有 AI 拟题时，用本地规则生成的动机短句；③ 竖屏平板是放大的手机两态；④ 日记结尾跟着内容走，不贴底；⑤ Yoin 的成段文字跟随用户的写作语言，界面文字跟随 app 语言。
- **减少动态**（省电模式、移除动画）：Memories 原地淡出，不平移、没有圆角；卡片⇄日记只做透明度，p 0.5 之前卡片淡完、0.5 起日记淡入，同一时刻屏上只有一层文字；不读倾斜，不播涟漪；获得动画改成约 200ms 的透明度显影，只在显影结束时打最强的一拍。
- **无障碍**：只有停稳的当前页可读，邻页和看不见的层不暴露语义；页面的 paneTitle 在 "Memory, …" 和 "Diary, …" 之间切换，开关日记会被播报。

**首页 Jump Back In 的字体（2026-07-26 定稿，同日收窄；Memories 的部分已由上面的 v4 取代）**：

- **宋体只属于 AI 拟题（memoryTitle）**：JBI memory 卡上那一枚生成的标题用衬线（`FontFamily.Serif`，Pixel 上即 Noto Serif CJK / 思源宋体同源字形），SemiBold 17–18sp。token：`YoinSerifTitle`（Type.kt）。
- **其它标题维持 GSF，只加大字号**：专辑名照旧；歌名（JBI 卡片标题）升到 16sp SemiBold。
- **用户正文**：JBI 的 note 正文用 Google Sans Flex，继承 `bodyMedium`（2026-09-19 调整）。
- **GSF = 其余一切**：评分数字、标签、按钮等。
- `HomeWidgetCard.commentIsHeadline` 区分拟题（宋体标题）与笔记原文（黑体正文）。

**拟题豁免（2026-07-26 决定）**：AI 拟题（`memoryTitle`，同时复用为首页 Jump Back In memory 槽位的标题）是「不上传原文」的唯一例外——拟题 prompt 允许携带正文槽占用者（album review 或最新一条 note）的原文，并拼上专辑背景（专辑名/艺人/年份 + 本地已缓存的 Gemini About 行，不产生额外请求）。豁免仅此一处用途；旁白只收事实清单。未配置 BYOK 或生成失败时，Memories 的标题位放专辑名（动机短句 2026-10-06 已取消）；首页 JBI 仍用原来的本地 deterministic 模板（覆盖率 / 笔记数，要不要也改成动机短句见 PLAN Q6）。

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
- **播放队列（2026-10-06 owner Q1：独立的 Queue 按钮 + 底部弹层，UX 照 Spotify，UI 好看一点、不复杂，`QueueSheet`）**：顶上「Playing from · 专辑 / 歌单名」（没有来源时写 Queue），然后三段：Now playing（当前这首，主色 + 跳动的均衡器）、Next in queue（用户 Play next / Add to queue 加进来的，右侧 Clear）、Next from: 专辑名（其余的，按真实播放顺序，随机时也是）。播过的不显示。点一行即播那首。行高固定 64dp：48dp 封面 + 标题 / 歌手 + 右侧拖动把手；按住把手拖动只在本段内换顺序（行抬起到 surfaceContainerHighest，邻行用空间弹簧让位，松手那一帧直接换成新顺序，不回弹再滑）；向左滑移除（errorContainer + 删除图标）。Add to queue 按 Spotify 语义排在「你已经加的」之后、专辑剩余之前，Play next 紧跟当前这首。能力随后端：Subsonic 全部可改；Apple Music 只能移除（MusicKit 不支持移动）；Spotify 只读（App Remote 不能改用户队列）；随机播放时不能拖动（列表顺序不是播放顺序）。条目的稳定 id 和「用户加的」标记存在 MediaItem extras 里。原型里的「NP 内 Queue 页签」方案已被否（16:9 屏会被挡、不能左右滑）。
- **背景**：两个暗色调的微妙渐变（渐变过渡极其平滑，几乎感觉是纯色）+ 实时音频可视化（与背景融为一体，有呼吸感）
- **退出**：下滑手势 / 系统返回键（适配 Android 14+ Predictive Back，缩回时有连贯的预览动画）
- **断点**（2026-09-30，`NowPlayingPresentation`）：
  - **16:9 矮屏**（先用完上下两层间距仍放不下两句完整歌词，适配原则 6）：Lyrics / About / Note 那一行 + 歌词窗口收成**一行当前歌词**（bold、primary、单行省略），整行就是展开按钮，没有单独的展开键；同一句停 ≥ 8 秒时这一行交叉淡入成「展开符号动画 + Tap to expand」，**一天最多一次**（按本地日期记在 `yoin_ui_hints`）；动画缩放为 0 时符号不动、字停约 2 秒。歌词展开页 tabs 保持文字，4 个歌词工具键挪到 tabs 同一行右侧，底部只剩标题
  - **自动沉浸**（所有尺寸）：播放中 + Lyrics 页 + 有同步歌词 + 3 秒无操作（2026-10-05 由 5 秒改短）→ 只有 4 个歌词工具键隐藏；在底部时淡出并塌缩槽位（标题下沉、歌词往下长），在顶部时原地淡出；手动滚过歌词、触摸、暂停都会恢复；选择歌词模式中不隐藏。工具在底部自动收起后，那一条里的第一下点按只唤醒工具，不会触发下面的标题或歌词
  - **✓ = 选择歌词**（2026-10-05，L6：owner「就是这样，先这么做一下，代码可以参考 spotoolfy」——行为参照 owner 的 `~/Developer/spotoolfy_flutter` 和 Spotify 的 Select lyrics；**W1（owner 2026-10-05 晚）：照 spotoolfy 任意挑行，Card / Story 照样保留**——「为啥必须连续的才能 story」，连续并不是出图的前提）：歌词工具的 ✓ 进入选择模式——歌词不再跟随播放，点行 = 选择，翻页手势和自动沉浸暂停。**任意挑行、最多 15 行**（`MaxSelectedLyricLines`，spotoolfy 海报的上限；规则在纯函数 `nextLyricSelection`）：点没选的行 = 加进来，点已选的行 = 退掉；已满 15 行时再加会被拒绝：REJECT 震动，顶部提示换成 "Up to 15 lines at a time" 约 1.8 秒。相邻的已选行连成一整块 primaryContainer（外圆角 16、相邻两行之间的内圆角 4，向页边多伸 12dp，文字位置不动，圆角变化走空间弹簧）；没选的行一律 0.7，选满 15 行后没选的行降到 0.42（不能再加了），歌名卡片不能选，有选择时也降到 0.42；正在唱的行文字用 primary。顶部独占一条标签（歌词在它下面淡入）："Tap lines to select" / "N lines selected" / 满了是 "15 lines selected · max"，文字变化用 AnimatedContent 淡入淡出。跳着选时，卡片和复制都按歌曲顺序排，中间跳过的地方算新一段（`lyricPassageStarts`）：卡片上段与段之间留 20dp（行间 6dp），复制的文字段与段之间空一行。工具条换成 [× 退出][复制][分享图片][编辑]。
    - **复制**：按歌词顺序，开着翻译时带上翻译，空一行后接「— 歌名 · 歌手」（没有歌手只写歌名）；Android 13 以下提示 "Copied N lines"（13 起系统自己提示）。
    - **分享图片**：底部面板依次是预览、Card / Story 切换（和 NP 标签组同一种按钮）、4 个配色色块、保存和分享。Card 宽 1440px（spotoolfy 的 720 × 2），高度随内容；Story 1080 × 1920，色板底色铺满、卡片居中，上下各留 72dp 避开 Story 自带的界面。4 套配色取当前封面色板（spotoolfy 的四种海报）：Soft（primaryContainer，默认）/ Deep（Soft 反相）/ Vivid（tertiary）/ Mist（tertiaryContainer）。预览和导出是同一次 GraphicsLayer 录制，按固定像素导出，字号缩放固定为 1（图片不受系统字号影响）；行多到 Story 放不下时先按更宽的宽度排版再整体缩回原宽，实在放不下再整体缩小，永远不截掉一行。保存：Android 10 起经 MediaStore 存到 `Pictures/Yoin`（不需要存储权限，文件名 `Yoin lyrics - <歌名> - <时间戳>.png`，按钮依次是 Save → Saving… → Saved / Try again，在面板里直接反馈），Android 10 以下不显示保存键。分享沿用 FileProvider，`EXTRA_TEXT` 只附「歌名 · 歌手」（spotoolfy 附全部歌词，在聊天软件里和图片重复）。
    - 编辑 = 原来 ✓ 打开的 LRC 编辑框。系统返回先退出选择模式；切歌或换歌词时退出选择模式。还没有歌词时 ✓ 直接打开 LRC 编辑框。spotoolfy 的「拿选中歌词问 Gemini」「带歌词写笔记」「复制成单行」三样没有移植
  - **控件永远排得下**：胶囊组先按真实宽度量，放不下整组收成纯图标（按下时展开标签），三个都在；控制行先收 PLAY 的内边距、再把控件从 56 降到 48，播放模式键永远完整
  - **Medium 整窗（折叠屏内屏、平板竖屏）· Spotify 式**：点 pill 先从右边推出手机宽的**侧栏**（clamp(窗宽 × 0.5, 360, 420)，左侧圆角 28，就是手机那一页、歌词吃满剩余高度）；旁边的内容让出这块宽度继续可用、读成 Compact；底栏折成 [Home][Library] 居中。侧栏右上角「全屏」→ **放大的手机**（列宽 ≤ 640 居中，封面随高度收，评分列 / 控件随列放大；右上角「收回侧栏」）。返回一级一级退：全屏 → 侧栏 → 关闭，左上角 ▾ 直接关闭；侧栏往右滑也能关，三者共用一个 dismiss 控制器。展开折叠屏（Compact → Medium）时直接进全屏态，其它进入 Medium 默认侧栏；「侧栏 / 全屏」状态在 NP 的 ViewModel 里。窗宽 − 侧栏 < 320 时不开侧栏，直接全屏态
  - **侧栏被手势带走时旁边的内容跟着补位**（2026-10-02）：返回预览 / 右滑关闭把侧栏往右带多少，宿主内容就 1:1 收回多少宽度（`NowPlayingPanelMotion` → `rememberNowPlayingPanelInset`），提交后冻结最后的位移随开合 spring 收完——侧栏和内容之间不再露出一条光秃秃的窗口底色
  - **Wide 整窗（平板横屏、桌面窗口）也是同一条链**（2026-10-02，适配原则 4）：点 pill 先开侧栏（宽同上），右上角「全屏」→ **双栏 TabletNP**（左栏的控件、评分、标题、胶囊恒按 312 排，封面吃剩下的高度、最大 312——矮窗先收间距、再把标题收成一行跑马灯，不再让控件跟着封面变窄；右栏歌词吃剩下的；点封面 = 共享的「看封面」，评分和标题让位、封面最多 1.5 倍、左栏跟着封面加宽、右栏不小于 320），右上角同一位置「收回侧栏」。返回：双栏 → 侧栏 → 关闭。横竖屏切换保留用户的侧栏 / 全屏选择。开着详情列时侧栏照开，两列整体让位（1280 → 剩 860 仍分两列，各读 Compact）；侧栏旁放不下两列（窗宽 − 侧栏 < 744，即 < 1164 的 Wide 窗）时 NP 直接全屏双栏。在双栏里点「前往专辑」：侧栏放得下就先退回侧栏再开列，放不下就收起 NP 再开列——页面永远可见。旧裁决「分栏窗格里 NP 占满本窗格、无侧栏无全屏键」随 Activity Embedding 分栏一起废止
  - **放大的手机的高度预算**（适配原则 6）：歌词窗口至少可见 4 行（165dp，按 `LyricsDisplay` 真实几何）、封面最小 168，先收间距再缩封面，再不够才退到一行歌词
  - **手机横屏**（2026-10-03 owner）：静止时右栏不显示 Lyrics / About / Note，只有一行可点的当前歌词（同 16:9 规则，点开进展开页才出 tabs + 工具）；标题和歌手并一行；右栏整列避开远端挖孔（播放模式键在行尾）。16:9 矮屏的单列同样把标题和歌手并一行（高度梯子里排在歌词换形之前）
  - **点封面 = 看封面**（2026-10-03，适配原则 6）：手机 / 侧栏 / 放大 / 双栏 / 手机横屏共用 Immersive；评分、tabs、歌词窗口、间距让给封面，封面至多 1.5 倍，**当前那一行歌词始终保留**；长不大 max(40dp, 12%) 的窗口里封面是纯图片、不可点。平板竖屏侧栏和放大态在焦点里保留歌词列表（放得下可见 4 行时）
- **歌词的末句与切歌（2026-10-05 owner 验收）**：
  - **末句拉长 = I-2（像拖长的一个音）**：从末句成为当前行（或结束前 10 秒，取较晚者）到交接，末句以左缘为原点向右拉宽，`scaleX = 1 + min(0.08, 行宽 ÷ 最长行 − 1) × p`，拉宽后一定放得下这一行；已经占满行宽的句子也至少能拉宽 3%（`LastLineStretchGutterRoom`），伸进页边留白里，只会变宽、不会被截断。**所有文字一律是整行绘制期 scaleX**，不重新排版（2026-10-05 晚 owner：拉英文时一帧一帧地抖——之前满行的拉丁文改加字距，每一级都重新排版；中文整行缩放一直是顺的，所以字距方案已删除）。拉宽用慢空间弹簧；交接时用默认空间弹簧带一点回弹收回，同时整块淡出。拉长和下一首浮现读同一个进度（`UpNextTiming.outroProgress`）：拉长从头起步，下一首歌名等这段进度过半才浮现（2026-10-08 owner：预告太抢眼）；拿不到下一首（Spotify / Apple 没有 nextTrack，浮现只在 Subsonic 上有）也照样拉长到换歌。只在展开歌词页和双栏右栏，小歌词窗没有。实现 `ui/nowplaying/LastLineStretch.kt`、`UpNextTiming.kt`。
  - **切歌脉冲 = 方案 B：顺着歌词流的一道光**：歌词是主表面时（单栏：Lyrics 页展开，或小歌词窗可见且不是一行模式；双栏：右栏在 Lyrics；横屏：展开且在 Lyrics）切歌，背景放一道光：下一首 / 自动播完从歌词列下缘外 0.25H 处升起，上一首从上缘外落下（方向按队列位置判断，和 `LyricsTrackTransition` 同一规则，光和歌词永远同向），落到新歌名卡片上；半宽 0.62W，半高 0.25H → 0.09H。位移走慢空间弹簧，亮度用快效果弹簧升起，位移过 85% 后用慢效果弹簧退掉，全程申请高帧率。双栏和横屏把光裁在歌词栏内，单栏不裁。点击、自然播完、外部控制都算；歌词页已经把这首原地交接过来时不放（那段浮现本身就是转场）。封面是主表面时，只有 3 秒内点过 NEXT / PREVIOUS 才从那个按钮起环，没有点击来源就不发，绝不复用旧坐标。播放 / 暂停的呼吸照旧：3 秒内点过 PLAY 从按钮起，耳机、通知栏从默认点（0.5W, 0.62H）起。点击记时间戳、只用一次；「上一首」只把本曲拉回开头时（还是同一首、进度回退 ≥ 1 秒），这次点击当场作废。**「上一首」各服务一致（W2，owner 2026-10-05 晚）**：播放超过 3 秒时先回到本曲开头，不到 3 秒才跳上一首——Spotify 本来如此，Subsonic / Apple Music 改走 Media3 的 `seekToPrevious()`（`maxSeekToPreviousPosition` 默认 3 秒），通知栏、耳机和 App 内按钮同一规则。实现 `TransportPulse.kt`、`NowPlayingAuroraBackground.kt`。
  - **不做 QRC 逐字**（owner 2026-10-05）：歌词只到逐行高亮。
- **歌词来源与缓存（2026-10-05）**：
  - **加载顺序**：用户选的歌词 → Subsonic 服务端歌词（Subsonic 账号没有用户选的行时只用服务端歌词，不走自动兜底）→ `lyrics_cache` 里 30 天内的自动行 → 自动兜底链 QQ 音乐 → 网易云 → LRCLIB，第一个返回非空 LRC 的获胜。QQ、网易云、华为挑候选都过同一个 `LyricCandidateMatcher`（2026-10-05 起网易云也走它），宁可返回 null 交给下一家，也不贴错歌。
  - **华为音乐**：只在手动搜索面板出现（`automatic = false`），是网易云之后、LRCLIB 之前单独一栏，不进自动链，也从不被提议为「整套切换」的目标。只用匿名公开接口，不带 Authorization、Cookie、设备 id 或任何 token。简体中文译文写在同一行的 `^` 后面，按最后一个 `^` 拆成原文和同时间戳的译文，没有译文的行用 `//` 占位。song id 是自带解析信息的 `hw1|<contentID>|<查询提示>`：歌词地址只在进程内记住，没记住或已失效（403 / 404 / 410 / NoSuchKey）时用查询提示重搜一次、只认同一个 contentID，找不到就算没词，绝不换成同名的另一条录音。接口不可用时手动搜索提示 "Huawei Music is unavailable right now"。
  - **自带译文**：当前歌词源自带的译文能服务目标语言（三家自带的都是简体中文，只在 AI features › Answer in 选中文时用）就直接用；不能时只提议把整套歌词换到排在它后面、自动、带译文的源（原文和译文一起换，绝不按行号拼两家）——实际效果和以前一样：QQ 提议网易云，其余走 Gemini。同一时间戳下有多行、两边行数相同时按位置配对。snackbar 写显示名（"Lyrics applied from Huawei Music"），不写原始 id。
  - **缓存寿命**（owner 拍板：「用户选择的缓存还是不要按照天数吧，按照总大小比较好」）：`lyrics_cache` 有两种行，不改 schema。**自动行**保持 30 天 TTL，过期重新匹配，让更好的来源或 matcher 的修正还能换掉它。**用户选的行**——手写 / 粘贴（`lyricsProvider = manual`），以及在搜索面板里明确应用的任何一家、接受了的整套切换（这两种在 `lyricsProviderSongId` 前加 `user|` 前缀）——不按天数过期，重新加载时压过自动歌词，也压过 Subsonic 服务端歌词；`cachedAt` 对它们表示「最近一次用到」，读取时刷新（一天最多写一次库）。重新搜索、应用别的结果会覆盖它。**整个缓存按总大小封顶**：一首歌的占用 = 歌词 LRC 的 UTF-8 字节 + 这首歌免费的 `provider:*` 译文字节；总量超过 10 MiB 就淘汰到 9 MiB——先淘汰自动行（和没有歌词行的孤儿译文），旧的先走；再淘汰搜索里选的行，最久没用的先走；手写 / 粘贴的行计入总量但永不淘汰，刚写入的那首也不淘汰；付费的 Gemini 译文不计入、也不淘汰（云同步对它只增不删）。规则改动不清空现有缓存：旧行照自动行处理，到期自然重配。实现 `data/lyrics/LyricsCachePolicy.kt`、`LyricsCacheBudget.kt`。
- **笔记 Take Notes（方向 A，2026-10-05 owner「感觉完美」）**：
  - **笔记就是歌词行**：收起的小窗和展开的 Note 页都用共享的 `NoteLine`（`ui/component/NoteContent.kt`）：时间戳占固定 40dp 一列、等宽数字，正文跟在后面；播放头所在的那条（最近一条 ≤ 播放头的锚点笔记，和歌词同一规则）亮成 primary，颜色在绘制阶段读；没有锚点的旧笔记显示 6dp 空心点。小窗沿用强衰减和缩小、固定时间线顺序、不带排序和页头；展开页只弱衰减（0.62，播放头不在任何一条里时 0.86），**没有卡片、没有竖线、没有垃圾桶**，排序开关（时间线 / 先后）保留，「先后」模式下每行多一行小字日期；自动跟随把当前那条停在视口约 22% 处（写作时暂停），列表上 24dp、下 64dp 渐隐。写作时在锚点位置插一行虚线下划的「正在写…」（草稿属于别的歌时不显示）。点一行跳到那一刻。
  - **写作条 `NoteWriteBar`**：放在 NP 底部的 accessory 槽（Note 页底部，Wide 也一样），贴着键盘（IME insets 走 `ui/component/ImeInsets.kt`）。平时是 56dp 胶囊（surfaceContainerHigh 92%，圆角 28），左边是按秒刷新的播放头时间片，占位「记录笔记」；写的时候长成卡片（2–6 行，超出在内部滚动，圆角 28 → 20）。时间片点一下 = 对齐到现在并打一拍 tick；**不写说明文字**（2026-10-08 owner：常驻提示行很怪）：已经写了字、且播放头偏开 ≥ 2 秒时，时间片前滑出一个对齐图标（`YoinSymbols.Refresh`）；草稿属于上一首时时间片前放那首歌的小封面（Thumb 24dp）。卡片底部只有「记下」。「记下」（`JournalSavePill`）存完键盘不收，可以接着写下一句；键盘收起且草稿为空时回到胶囊。整个过程只有一个输入框实例，变形不丢焦点。手机横屏改成单行布局（「记下」在行尾，最多 3 行），写作时标签行和标题行让位。**没有底部 sheet**：`WriteNoteSheet`、`NoteComposer`、`NoteCard` 已删除；点 Write 胶囊切到 Note 页并展开，等页面落定（展开过半到 90%）才弹键盘，这个请求 2 秒内没兑现就作废；单列里键盘起来时歌名让位，歌名和歌手改成小字出现在顶栏封面旁；Wide 左栏的 Write 切到 Note 标签并聚焦。
  - **空状态**（这首歌还没有笔记）：区域中间画一个涂鸦，下面只有一个标签「还没有笔记」，不写提示语（2026-10-08 owner）。涂鸦三选一，按歌随机：从播放头小圆点拉出一道波浪、落成一个音符；写了一半的便签，光标在等；手画的 Yoin 三根箭头。笔画用效果弹簧按顺序画出，实心形状用表现型空间弹簧弹出，画完就静止（光标只闪 3 下）。颜色取主题的 primary / secondary / tertiary / outlineVariant，便签纸是 surfaceContainerHigh 混 45% tertiaryContainer，跟着动态取色和播放配色走。区域放不下时只留标签。实现 `ui/nowplaying/NoteEmptyDoodle.kt`。
  - **长按一条笔记** → `YoinDropdownMenu`：「对齐到现在 · m:ss」/「删除」。删除后这条立刻从两个窗格和进度条刻度上消失，原位变成「已删除 · 撤销」一行；5 秒后、或删下一条、或 NP 退出时才真的删库，撤销还原的是同一行、不新建。撤销做成列表里原位的一行，不用 snackbar。
  - **草稿**在开始写的那一刻（聚焦空草稿，或写下第一个字）绑定到当时的曲目和时间锚点，直到存下、清空或明确对齐为止——写到一半切歌，存下的笔记仍归原来那首、带原来的锚点。草稿按账号分开：切换账号时丢弃。

### 🔊 后台播放与系统集成

- **MediaSession + Media3** — 后台持续播放，系统媒体控件联动
- **通知栏控制** — 显示封面、歌曲信息、播放/暂停/上一曲/下一曲按钮
- **蓝牙/耳机按键响应** — 通过 MediaSession 自动支持
- **Audio Focus** — 正确处理与其他 App 的音频焦点争抢

### 📚 Library

- 分类浏览：歌单 / 歌手 / 专辑 / 歌曲 / 收藏。收藏标签只在「收藏」和「资料库」是两回事的服务上出现（Subsonic）。Spotify 的资料库本身就是收藏：喜欢一首歌就是把它加进 Liked Songs，专辑是保存的，歌手是关注的。所以 Spotify 下不显示收藏标签（2026-10-10 拍板），由 `ServiceFeatures.favoritesAreLibrary` 显式标记，不从能力组合推断。Apple Music 没有收藏能力，也不显示
- **歌单的 By You 子胶囊**（2026-10-10 Q8 / D3，Spotify 式）：开着「歌单」时，紧跟它用空间弹簧展开一颗 By You（中文「我创建的」），后面的胶囊跟着同一个弹簧让位；它是叠在歌单视图上的开关，不是新视图，点它只筛歌单列表，All 和搜索不受影响。只有已加载的歌单里既有自己建的、也有别人的才出现，所以永远不会筛出空列表；归属不明的歌单在 By You 下不显示。展开时把自己滚进可视区并避开两端的渐隐：「歌单」是第一颗胶囊，手机和平板的胶囊行一般放得下；放不下的是窄 Wide 头排（详情栏把 shell 列挤到 840dp 左右），从 All 进来时 ✕ 还在同一组帧里展开、把这一排再挤窄，所以展开期间跟着这一排的宽度一起滚；行被滚到「歌单」贴边时同样会滚。落定以后这一排归用户，之后再改宽度或拖动都不会把它拉回来。状态在 ViewModel：切胶囊、刷新都保留，切账号复位，不持久化；列表不再混合时胶囊收起、列表回到全部，再混合时按原来的开关恢复。读屏念成复选框。归属是中性 `Playlist.ownedByMe: Boolean?`，和决定能否编辑的 `canWrite` 分开，不升库、不加 Capability：Spotify 比 `owner.id` 和当前用户 id（同步缓存只存 `canWrite`，两者同一个比较，读缓存时就当作归属；你参与协作但别人建的歌单不算）；Apple Music 只认资料库歌单的 `canEdit`（Apple 不给归属字段，系统生成的 Favorites Mix、Purchased 是 false；目录歌单记未知；`canWrite` 仍恒为 false）；Subsonic 比 `owner` 和登录名、不分大小写，没有 owner 记未知（单用户服务器全是自己的，胶囊不出现；Navidrome 的智能歌单默认归第一个管理员，所以管理员账号的 By You 里也有它们）。Spotify 仍最多同步 200 个歌单，By You 只在这批里筛
- **All 和切换胶囊**（2026-10-10 Q12/Q13，Spotify Your Library 模型）：顶部一行胶囊「歌单 / 歌手 / 专辑 / 歌曲」，Subsonic 另有「收藏」。不开任何胶囊就是 **All**（默认）：歌单、歌手、专辑混在同一张网格里，歌手圆形、专辑和歌单方形（同歌手 / 专辑网格的格子，列数随宽度），不放歌曲。开着某个胶囊时，前面用空间弹簧展开一个 ✕；点 ✕ 或再点一次那个胶囊回到 All。选中状态在 ViewModel 里，切账号回到 All。Library 是根页面，系统返回不经过胶囊。冷启动只读歌手（和以前一样）；All 的专辑和歌单在 Library 上屏时才读，哪一类先到就先进网格（冷启动读到的歌手立刻就在），晚到的用条目弹簧挪进来，不等最慢的那一类；什么都还没到时才显示加载，全部到齐仍为空才显示空状态。Spotify 读本地同步缓存，不触发同步：专辑和歌单胶囊随后沿用 All 读到的这批，不再做新鲜度检查（被限流期间有意如此），要等冷启动按 TTL 同步、切账号或资料库变更才重读。改过歌单（加歌、新建、改名、删除）会把整个同步缓存标成过期，所以 Spotify 的 All 不为此重读（那会同步整个资料库），只有「歌单」胶囊和以前一样新鲜重读；Subsonic 和 Apple Music 的 All 照常重读歌单
- **排序**（同上）：列表第一行，随列表滚走。左边是当前排序名加排序符号，点开是 YoinDropdownMenu，正在用的一项带勾；换排序时条目用 spatialSpring 移到新位置。只在已加载的集合里排，Spotify 每类仍最多 200 条。专辑读整个资料库（2026-10-11 评审修复）：第一批照常先出，满一批就在后台按顺序接着读下一批，同时只有一个读取，切账号或刷新时取消；每批读到就并进列表，排序、All 的混排和滚动条分段随之重算，已显示的不消失，新来的用条目弹簧进场。Subsonic 是 `getAlbumList2` newest 按 offset 每批 500 张；Apple Music 读 `/v1/me/library/albums`（每页 100、带 `catalog` 关系、`dateAdded` 作 `libraryAddedAt`），不再借 recently-added（那只是最近加入的一段）；Spotify 不变。某一批读失败就停在那里，下次 Library 上屏从那一批接着读
  - Recents（最近）：Yoin 本地的访问和播放记录，按 profile + provider 取每个歌手、专辑、歌单最近一次的时间。来源是 activity_events 的 VISITED / PLAYED，加上 play_history 里每张专辑最后一次播放。没有记录的按 Recently added 排在后面，再没有日期的按名字。新设备没有记录时就等于 Recently added。另外在 Library 里点开的歌手、专辑、歌单也记一笔，用 Library 列出它的那个 id，存在本机 SharedPreferences（`yoin_library_opens`，每个 profile × 服务保留最新 300 条）：Apple Music 的资料库专辑带 `catalog` 关系读，对得上目录的按目录专辑 id 列出，和详情页记访问、播放用的 id 一样；对不上目录的（导入的音乐）仍按资料库专辑 id（`library:l.…`）列出，靠这一笔对上；歌单页本身不记访问（记进 activity_events 会出现在首页动态里），从 Library 点开的歌单靠这一笔进 Recents。仍然对不上的：从首页、搜索打开的歌单只有「从歌单播放」留下记录
  - All 里歌手没有入库时间（三家都不给），就取它在已加载专辑里最新一张的入库时间（先按专辑的歌手 id，没有 id 按名字），这样 Recently added 和 Recents 的兜底里歌手和专辑、歌单混排，而不是全部歌手排在最后。专辑是整个资料库（Spotify 是最新的 200 张），歌手只要有专辑在里面，最新那张一定在。歌手视图不借专辑的时间
  - Recently added（最近添加）：各服务的入库时间 `libraryAddedAt`。Subsonic 专辑用 `created`、歌单用 `created`；Spotify 已存专辑用 `added_at`；Apple 资料库专辑和歌单用 `dateAdded`。Subsonic 的 `addedAt` 仍是收藏时间，留给首页 Recently Added
  - Alphabetical（字母顺序）：快速滚动条的字母表（`LibraryIndex`：系统 ICU，跟随 app 语言），排序和分段用同一个 collator，所以每个字母只有一段：拉丁字母、日文假名行、韩文初声各自分段，数字和符号归入末尾的「#」；API 29+ 汉字按拼音排进字母段（周杰伦在 Z），API 26–28 只在 app 语言是中文时按拼音，否则汉字排在字母、假名、韩文之后、末尾「#」之前。Subsonic 跳过开头的冠词（服务器默认的 The / El / La / Los / Las / Le / Les / Os / As / O / A）。歌单没有滚动条，也用这套顺序，A–Z 在每个视图里一样
  - Creator（创建者）：专辑按歌手，歌单按创建者，歌手按自己的名字
  - 每个视图只露出做得到的：All 和专辑四种都有；歌手只有 Recents 和 Alphabetical（三家都没有关注或入库时间）；歌单有 Recents、Recently added、Alphabetical，但 Spotify 的 `/me/playlists` 没有日期，所以 Spotify 的歌单没有 Recently added；歌曲和收藏没有排序行（Spotify 的歌曲就是 Liked Songs 的加入顺序）。由 `ServiceFeatures.albumsHaveLibraryDates` / `playlistsHaveLibraryDates` / `sortIgnoresArticles` 显式标记
  - **快速滚动条**（2026-10-10 U2 / D2 / Q13）：All、歌手、专辑的网格贴边一个小把手（`YoinFastScroller`，接线在 `LibraryFastScroller.kt`），歌单、歌曲、收藏没有。刻度跟随当前排序（`LibraryScrollIndexer`）：Alphabetical 是名字的首字母；Creator 是创建者的首字母，没有创建者的在末尾「#」（顺序本来就按创建者的字母排，它的首字母是唯一和网格对得上的索引，「R」就落在 Radiohead 的专辑上；标题首字母在这个顺序里是散的）；Recently added 是入库时间线（跨度不到两年按月，单段超过约 60% 或缺日期超过 20% 时不分段）；Recents 只有把手。跳到一段时，含这一段第一项的那一行停在顶部。轨道上沿 = 网格顶 + 8dp，下沿让出浮动栏（同网格底部留白）；把手贴页面自己的 end 边：Compact 以上越过 16dp 页边贴到列边，Wide 分栏时停在 shell 列边、不进 24dp 槽，RTL 在左边；切换视图的交叉淡化不裁切（`SizeTransform(clip = false)`，各视图同尺寸），把手在淡出途中仍然完整。触摸区 32×64dp（图形 24×48dp），只有把手可见时接收触摸，隐藏后点按全部穿透：Compact 以上整块落在 32dp 页边里，不压封面；Compact 和手机横屏页边只有 16dp，把手可见时最后一列封面最右 16dp（其中 8dp 在图形之外）、把手所在的 64dp 高度内的点按归把手。不放宽到 48dp，那样会压住封面 32dp。跳转不经过嵌套滚动（见溶解一节）；每次跳转把分栏开合用的宽度锚点设到这一行起始的那一段（没有就是这一行），列数变了这一段仍在顶部
  - 偏好按 profile × 视图存在本机 SharedPreferences（`yoin_library_sort`），不进 Room 和云同步。自动备份只在 Android 12+ 排除它（data_extraction_rules.xml 只列要备份的）；Android 8–11 不读这份规则，manifest 也没有 fullBackupContent，所有 SharedPreferences 都会进备份，这份也一样（键是 profile id，恢复后跟着数据库一起，无害）
- 普通点击底部 Library：进入当前 profile 的 Library，展示 saved artists / albums / playlists / songs。Spotify 下：
  - 歌曲 = Liked Songs，按加入时间倒序，新喜欢的在最上面；同一秒加入的几首按 Spotify 自己列出的顺序，不按标题；在别处取消喜欢，这一行淡出
  - 点一行以 Liked Songs 起播（`spotify:collection:tracks` + `offset.uri`）：Spotify 从这首往下播它自己的收藏，不动用户的队列
  - 歌手只放关注的歌手；在歌手页关注或取消关注，列表随即增减；资料库搜索仍然搜得到已存专辑和喜欢歌曲的歌手
  - 心形和关注改动列表时只重读本地同步缓存，不触发同步请求
  - 每类和以前一样最多同步最近 200 条
- **Subsonic 的歌曲**（2026-10-10 Q9）：服务器没有能分页的「全部歌曲」，所以歌曲 = 各专辑的曲目。专辑按加入资料库的时间倒序（`getAlbumList2` type=newest，一页 10 张），专辑内按专辑页的曲目顺序（逐碟；`Track` 没有碟号，只按曲目号排会把多碟专辑交错）；展开专辑走 `getAlbum`，和专辑页共用详情缓存，同时最多开 3 张，按专辑顺序拼接，同一首只出现一次。代价：展开过的专辑都写进详情缓存，磁盘那份是全部账号共用的约 24MB LRU，一路滑下去会把很久没碰的详情（含其他账号的）挤出磁盘，挤掉的再打开要走网络。滑到离底部约一屏时读下一页，这是 Library 第一个增量列表：同时只有一次读取，刷新或切账号时取消；底部只放一个 YoinLoadingIndicator，读失败换成一个重试图标按钮（不加文字），到底什么都不放。一张专辑打不开，这一页算失败；重试时它再失败就跳过它，不卡住后面的专辑（首页那一页失败时，再点一次歌曲 chip 就是重试）。滑动期间服务器的专辑表会变：下一页从上一页末尾往回多读 5 张，接在已读过的最后一张后面，所以期间删掉不超过 5 张不会漏读；期间新加的专辑（在最上面）不插进列表中间，刷新后出现在顶部。不再是随机样本，所以没有「随机歌曲 / 换一批」标题行；首页的随机歌曲不受影响。点一行从这首起整表播放，取它周围最多 100 首（`startWindowQueue`），不带 context。由 `ServiceFeatures.songsFromNewestAlbums` 显式标记。Apple Music 的歌曲维持原样：资料库曲目，字母序，最多 500 首
- Library 内普通搜索：默认 scope 为 Current Library，只搜索当前 profile 已保存内容
- 长按底部 Library：支持目录搜索的 profile 打开搜索框并默认 scope 为该服务目录（Spotify：`Search Spotify`、Spotify / Library；Apple Music：`Search Apple Music`、Apple Music / Library）；Subsonic 打开 Current Library 搜索。Apple Music 的 Library scope 调用个人资料库搜索接口，歌曲标签读取已加入资料库的歌曲，不用随机歌曲代替
- 搜索面的筛选（2026-10-10）：范围胶囊（服务目录 | 资料库，只在支持目录搜索的服务出现）和类型胶囊（全部 / 歌手 / 专辑 / 歌曲 / 歌单）放在同一行，中间一条 1dp `outlineVariant` 竖线；这一行全宽出血、可横滑，带滚动感知的边缘渐隐，静止时第一颗胶囊对齐结果列的左边线。类型胶囊只按能力出现，不看当前结果里有没有这一类：歌单要 `SEARCH_PLAYLISTS`（Spotify、Apple Music 声明，Subsonic 的 search3 没有歌单），资料库范围另外要 `PLAYLISTS_READ`。选了某一类只列出这次搜索已经拿到的那一类，不另发请求、不翻页、没有「加载更多」；「全部」每类最多 40 条，Spotify 资料库范围选了某一类就不再截断（上限是同步快照每类 200 条）。切换用 YoinMotion 淡入淡出，滚动位置按（查询，范围，类型）分别记住。收起搜索、长按快捷进入、回到 Library 首页都回到「全部」；Wide 分栏打开详情时连同查询一起保留；换范围或换服务后不再支持的类型回到「全部」。返回不拦截，仍由官方全屏 Search 一次收起
- 右上角 ⚙️ 设置入口
- 筛选胶囊与下方网格 / 列表的交界用潮线（2026-10-04 取代 10-01 的曲线 C 网点；2026-09-29 起已取代硬截断）：交界处不盖任何渐变、模糊或色带；两道页面底色的波浪以遮罩形式把内容从交界处挖掉，图形沉进水线，文字在水线以下 10dp 内淡完、不拆成点。曲线 C 的顶部网点在快速滑动时挤成密排圆盘和针孔，有密恐感，所以上面默认用潮线，下面保留网点场；用户可在 设置 › Motion › Scroll edge 换成原版网点或曲奇浪口（见「溶解」一节）。胶囊下的固定间距只留 4dp，网格顶部内边距 8dp
- 五个标签的底部都接底部网点场：封面到栏上方 20dp 才开始碎，栏下和栏两侧是纯网点；文字照常从栏下穿过
- 这对遮罩是通用语言：任何“滚动内容撞上固定 chrome”的交界都应复用（规则见「溶解（Dissolve）」一节）

### 💿 专辑详情（2026-10-05：评分徽记 + 第 2 页拼贴手册）

- **色谱顶栏（2026-10-09 owner 定稿，适配稿 artifact 1k9mD3vWBCBVZvDso35Csf）**：一条规则管所有尺寸——**封面离开视野，就化成色谱顶栏；封面回来，色谱就拼回封面**。三样东西读同一个进度：①色谱：封面的复制体切成 28 条竖条，按颜色归队成色带铺满页头，每道色带宽 = 这种颜色在封面里的占比（k-means 取色，每道至少两条；单色封面也拆成深浅两道）；每个颜色保留色相和饱和度，**OKLab 明度锁在 0.30–0.46**，所以白字在任何一道上都 ≥ 7:1，不模糊、不加遮罩（owner：要对抗模糊感）；②蝴蝶：封面后面的两块 Yoin 色块跟手飞上去，颜色换成它要落进的那道色谱，进顶栏后淡掉；③封面只平移缩放，停在顶栏末端（48dp，横屏 44dp；圆角 Hero 8 → Thumb 4），标题让出它的宽度 + 12dp；小封面可点，点了放下顶栏（Medium 是滚回顶部）。谁推动进度：Compact 和手机横屏是上拉（RevealState），Medium 是封面那一行滚过的距离 ÷ 封面高，Wide 整窗封面常驻身份栏、不形成顶栏。停靠后曲目表直接从页头下沿开始（取消原来的 56dp 直角通栏和 ≤ 5 首时的大胶囊），下沿走潮线。翻到第 2 页时色谱和小封面跟着第 1 页滑走，页头文字沿色谱边界一分为二（色谱上白、外面主题色）。整窗时状态栏图标在顶栏成形过半时切浅色，详情列里不动（状态栏属于整个窗口）。页面两侧不是屏幕边时（详情列），Compact 的两块色块从「出血」改为抱住封面；Medium（新加了蝴蝶）和横屏同样抱住封面；都离页面起始边 ≥ 8dp，绝不在列边上切。减少动态：竖条不飞、色谱原地淡入，封面原地淡出、小封面淡入。主题色先当临时色谱，封面取色好后用效果弹簧过渡。飞行的封面画在页头和 pager 下面一层（封面组件的 Surface 会吞触摸，必须让上拉手势先到页面）。实现 `ui/detail/AlbumSpectrum.kt`（纯函数 + 单测）、`AlbumSpectrumBar.kt`（几何、绘制、飞行封面），`AlbumDetailScreen.kt` 只做接线。
- **两页**：专辑页是两页的 pager，第 1 页是 hero ⇄ 曲目表的上拉两态，第 2 页是**拼贴手册**（scrapbook，D3，owner 2026-10-05 批准并改了几处）：把用户在这张专辑上留下的东西按曲目顺序贴在点阵纸上。全页扁平无阴影，各件靠纸色、字面和角标区分；倾斜只在 graphicsLayer 里（布局和点击区仍是方的），角度来自专辑 id + 这一件的 key 的哈希，所以重开永远一样；拖动翻页时各件按深度视差、多倾一点，减少动态时关掉。返回不变：第 2 页只是页内 pager 状态，从这里返回和第 1 页一样直接离开专辑页，不加返回处理。平板横屏的详情列同样有第 2 页和页点。实现 `ui/detail/AlbumScrapbook.kt`（模型 + 纯函数 builder）、`AlbumScrapbookPage.kt`；数据 `data/album/AlbumScrapbookSource.kt`（评分、笔记、播放按 profile + provider 过滤，不改表、不升库；About 表本来跨 profile 共享，按专辑名规范键查）。
- **评分图形 = 共享的 `ScoreEmblem`**（来自 Memories 的唱片刻纹徽记，见「评分与视觉形状系统」）：第 1 页 64dp，替换原来的 Bun——旁边的 "Based on X/N" 照旧只在不是手动专辑评分时出现，点徽记照旧打开评分与乐评面板，提交评分时的 bloom 保留；第 2 页 64dp（宽屏 72dp）挂在封面右下角，向右、向下各探出自身的 30% / 24%（和 Memories 卡片一样），翻到第 2 页停稳后用徽记自带的获得动画「盖章」一次（每次打开专辑页只盖一次，不震动），停稳前是未刻的状态。两页颜色都走 NP 的封面取色路径（`rememberScoreEmblemColors`），一张专辑在所有地方是同一枚徽记。艺术家页不放这枚徽记，也没有 Last Play，Discography 每行也不显示专辑分数（2026-10-10 owner 删除，见「评分与视觉形状系统」）。
- **评分与乐评面板（2026-10-06 owner R1–R4 拍板，`AlbumRateSheet`）**：左上角是一行小字「Rate」和专辑名（titleLarge SemiBold，左对齐，最多三行），右边并排 120dp 的徽记（2026-10-06 owner：只有徽章太空，标题放左上角、和徽章并排，像页面上 Last Play 挨着徽记那样），没有说明文字。下面是 NP 同款横向 `RatingSlider`（0.1 一格，填充用封面配色）——**不转徽记**（owner：转盘手势像 Google 旧闹钟，被抱怨），滑条在面板里要先横向越过触摸阈值才算拖动（`claimOnDown = false`），从滑条上开始的竖向滑动归面板（滚动或下拉关闭），不改分；轻点照样打分。拖滑条时徽记跟着分数实时变，并随拖动抬起 6%、按离 5 分的距离倾斜 1.4°/分；松手即保存分数，徽记用获得动画「盖章」一次（分档动画 + 触感）。然后是一个输入框：平时是 56dp 胶囊（「Say something about it…」），写的时候长成 20dp 圆角卡片，**随字数一直长高、框内不滚动**（owner 10-06：原来限高 168dp，字在框里上下被截断）；面板整体滚动，打字时自动把光标和下面的计数行留在键盘上方，滚出面板上下边的内容渐隐（`verticalEdgeFadeOnScroll`）；徽记上方在滚动区里留 14dp，10.0 的盖章光环不被裁。有字后小字标「Short comment n / 360」或「Review n」。没有 Save 按钮，关面板即保存。最后一行是 NeoDB 状态：未登录「Sign in to sync」（点了进设置的 NeoDB 一节）、有改动「Syncs when you close this」、「Syncing…」、「Synced」、失败「Couldn't sync · Retry」（点了重试，失败另发 snackbar）。**登录 NeoDB 后关面板自动推送**；书架状态不显示（打分或写字即「听过」，NeoDB 上改过的保留）。Yoin 只有一段文字，推送时按长短归位：360 字以内写进 Mark 的短评并删掉远端 Review，更长发成 Review 并清空短评，清空文字两边都清；拉取时 Review 优先，没有才取短评（`NeoDBSyncService`）。
- **乐评**只靠颜色区分：专辑色的 primaryContainer 纸、GSF（用户的字，2026-10-07 起不再用系统字面），**不加「Your review」标签，也没有竖线**；没有乐评时是一张空白剪报 "Write a review"（v1 第 2 页只有乐评能写）。**竖线这个视觉符号只留给单曲笔记**：单曲笔记是中性纸 + 日记竖线 + 每行时间戳，一张剪报最多 3 条，其余收进 "+N notes" 面板；点笔记行播放这首，等它成为当前曲目后 seek 到笔记的时间点（4 秒内没就绪就放弃，同 Memories 日记）。
- **专辑笔记最多一条**（`album_notes` 是 v11 的旧表，现在 app 里没有任何地方再写它）：只取 `updatedAt` 最大的那条非空行（同时间取先建的），和 Memories 日记显示的是同一行。做成便签：secondaryContainer 底、16dp 圆角、右下角 17dp 折角（折角色 = 便签底色混入 32% 的 secondary）、GSF（用户的字），**不带标签也不带竖线**（TalkBack 读 "Album note: …"），只读。位置在开篇簇里、封面和乐评下面，和乐评反向倾斜：手机上单独一行靠右，宽 = 可用宽度的 72%、最多 280dp，紧跟封面时离徽记留 6dp（盖章时徽记会放大），乐评太长落到封面下面时压住乐评底边 6dp；宽屏压在乐评那一列下面、缩进 24dp。收据的 "Notes" 计数把它算进去。
- **其余各件**：单曲分数不用小印章，直接把数字用专辑色印在票根上；全专最高分（≥ 9.0）的那张票根整张用 primary，写 "Best on the record"，每张专辑最多一张。只有分数的曲目两张一行做小票根；票根上时长和播放次数是两组（数字重、单位轻，不用分隔符），放得下就排一行；放不下时播放次数换到时长下面一行，票根跟着变高，两样都不丢（2026-10-08 owner；取代 10-05「去掉播放次数只留时长」）；什么都没留的曲目进 "Not yet" 空模子，点了播放。Ask / About 是 tertiary 索引卡（GSF、"Q" 角标，问答卡不再写 "Asked"），"About" 和 "Not yet" 两个标签保留。收据（在 Yoin 里的播放）只在专辑在 Yoin 里播放过时出现。第 1 页的「上次播放 / 均分 / 哪些曲目评过分」和第 2 页读同一条数据，跟数据库实时更新。
- **读失败**：第 2 页的数据读失败时不会一直停在 Loading——还没读到过数据就先显示空状态，读到过就保留上次的内容（第 1 页的分数和播放信息也不变）；随后按 1 / 2 / 4 / 8 / 16 秒自动重试 5 次，成功后下一次数据到达就替换，5 次都失败就停在当前内容，错误被拦下、不崩溃。第 2 页没有手动「重试」按钮；专辑本身加载失败时仍是带 Retry 的错误页。
- **保存到资料库（Q11，2026-10-10，只有 Spotify）**：▾ 菜单在「Add to playlist」后面多一行开关：「Save to library / 保存到资料库」⇄「Remove from library / 移出资料库」（图标 LibraryAdd / LibraryAdded，走 `PlayMenuItem`，详情列的合体栏菜单也有）。写操作是 `PUT` / `DELETE /v1/me/library?uris=spotify:album:{id}`，复用 `mutateLibrary`，经过 `SpotifyRateLimitGate`。门控是 `Capability.ALBUM_SAVE`，只有 Spotify 声明；Subsonic（专辑收藏走星标）和 Apple Music（只能逐首加入资料库）没有这一行，也不发请求。
  - **初始状态**：先看已保存专辑镜像（`spotify_library_album_cache`，最新 200 张）：在里面就是已保存，不问。不在里面时，专辑 URI 排在这页曲目 contains 查询的第一个，和曲目一起问。
  - **状态未知时没有这一行**（2026-10-10 评审）：镜像里没有、Spotify 还没答到（没问、在途、失败，或限流门关着没问）、也没有 Yoin 自己的写入时，状态是「未知」，▾ 菜单里不出这一行，既不会对已保存的专辑再存一次（可能把它在 Spotify 里的加入时间顶到最前），也不会在限流时给出注定失败的按钮。答复到了这一行才出现；菜单开着时它用 spatial spring 展开、effects spring 淡入，不硬切（菜单宽度若因这一行变宽会直接变，菜单是共享组件，没动它）。状态已知但限流门关着时照常显示，点了失败回滚，提示「Spotify 正忙」，和心形一样。
  - **请求数**：专辑不额外占请求的前提是曲目给它留了位置。两种情况会多一个只为专辑的请求，属于有意保留：① 这次要问的曲目数正好是 40 的倍数（40、80…），专辑挤到下一批；② 这张专辑的曲目 30 秒内都已问过（比如 Now Playing 刚问过正在放的单曲），专辑只能单独问。不问的话这一行就一直出不来。Yoin 自己的保存或移出还在途、或落地不满 60 秒宽限时不问：这时 Spotify 的答复反正赢不了本地写（移出会删掉镜像行，以前回到前台会为它单独发一个没用的请求）。
  - **点了之后**：立刻翻转（仓库里的写入中状态），失败回滚，并通过窗口现有的 snackbar 说原因（限流是「Spotify 正忙」，其余是「无法保存到资料库 / 无法移出资料库」或连接类提示）。连点时按顺序写，最后一次为准。成功后镜像当场更新：保存插到 Recently added 顶部，移出删掉那一行，不把整个库标记过期（重同步要翻四个列表）。数据源的已保存专辑列表另叠一层增量（和 `SavedTrackDelta` 同一机制），等 Spotify 的列表跟上再撤掉。
  - **状态优先级**和心形一样（`YoinRepository.observeAlbumSaved`，返回 `AlbumSavedState`：不支持 / 未知 / 已保存 / 未保存）：写入中 > 60 秒宽限内刚落地的本地写 > 远端答复和镜像行（比时间戳）> 未知。只在内存里，切账号清空（回到未知，等这页再问）。
  - **Library 当场跟上**（2026-10-11 评审修复）：写入落地、镜像更新之后，仓库按 profile 发一次 `libraryAlbumsRevision`。Library 收到后只从镜像重读专辑（`getSpotifyLocalSearchSnapshot`，不做新鲜度检查、不触发同步、不发请求），All 和 Albums 里保存的专辑用条目弹簧进场，移出的淡出；资料库范围的搜索也以镜像为准，屏幕上有资料库搜索时重跑一次，移出的专辑不再留在结果里。没有借 `libraryRevision`：那会让 Library 整库重读，Spotify 读前查新鲜度，超过 1 小时就整库重同步（最多 16 页），限流期间不允许。Home 的 Recently added 仍要等它下次读取才看到。

### ⚙️ 设置（从主页或 Library 进入）

- 服务器配置（Subsonic/Navidrome 地址、认证）
- 缓存管理（容量限制、清除）
- 主题偏好
- 关于 / 版本信息；关于的第一行“重新引导”再跑一遍首次引导（2026-10-09）
- 动效 › 滚动边缘（2026-10-09 起是整页，不再是下拉菜单）：一种样式一张卡，卡上是这种样式真的在滚的小图 + 一段说明，最下面一句说明底栏和状态栏不受影响；`SettingsFeatureActivity` 的 `ScrollEdge`，大屏在右栏打开；小图的封面列数随宽度增加（每列约 112dp，3–8 列），不拉宽封面

#### 账号与服务二级页（2026-09-26，取代 2026-09-06 版）

- **大屏 list-detail**（2026-09-30，SettingsTablet）：窗口 ≥ 840 时 Settings 与二级页走原生 Activity Embedding 分栏（`tag="settings-*"` 的 SplitPairRule + SplitPlaceholderRule，约 420 | 860，两个 Activity 不合并；这是 app 里唯一还在用 Activity Embedding 的地方）。左栏账号卡改为竖排行、右栏打开的那个高亮（⋮ 里保留 Use / Remove）；Features 里的 AI features / NeoDB 也在右栏打开（`SettingsFeatureActivity`），窄窗仍是行内展开。二级页手机横屏：左栏固定（hero + You'll need，不随输入法滚走），右栏滚动、表单在前、What you get 在后
- Settings 主页只做「列出 / 切换 / 移除」：Accounts 横向卡片，其余设置按 Features / Storage / About 分组，行内折叠展开（空间弹簧 + 效果弹簧），不再平铺说明段落。
- **Settings 视觉系统（2026-10-03，参照 Pixel Settings 子页面，取代此前的渐变底 + 整块面板 + 分隔线）**：
  - 页面平铺 `surfaceContainer`，行用 `surfaceBright`（深浅同一规则，行永远比页面亮）；每行是 M3 Expressive 分段列表的一段（`ListItemDefaults.segmentedShapes` 位置圆角 + `SegmentedGap` 2dp 间隙，无分隔线），行位置变化时圆角走空间弹簧。行高 ≥ 72dp：16dp 内边距 + 40dp 图标列（24dp 单色线条图标，onSurfaceVariant）+ 12dp + 文字。分类标签 primary、内缩 8dp。
  - 页头：浅色圆底返回键（`DetailBackButton`，surfaceContainerHighest）+ 大号页标题（displaySmall），返回键左缘、页标题、分类标签同在 24dp 线上；标题滚入顶栏时交接为小标题（与服务二级页同一套交接）。Settings 家族（主页、功能子页、服务二级页）**全宽**，不套 640dp 阅读宽度（用户 2026-10-04：居中的内容列配上铺满的卡片行像两套布局）。
  - 大屏 list-detail：左栏底色降为 `surfaceDim`，打开的那一行换成右栏的 `surfaceContainer` 并四角全圆（M3 selectedShape），脱离相邻行；颜色效果弹簧、圆角空间弹簧。
- **账号卡片（2026-10-03，头像 2026-10-04 改版）**：头像两层——**后面大的是服务**：账号专属 MaterialShapes 形状（8 种轮廓互不相像；按创建顺序 + id 哈希探测分配，同屏不重复，形状变化时 Morph 过去）填服务强调色，上面是 Yoin Symbols 手法重画的服务标志（`ServiceMarks`：Spotify 三道弧、Apple Music 双连音符；Subsonic 用 Cloud）；**右下角小圆是本人**：有服务头像就用（Spotify `/me` 的头像，存在 `ProfileAvatarStore`，只存公开图片地址，登录时写入、打开设置时为当前 Spotify 账号刷新），否则是昵称首字（跳过开头的服务名；dp 定字号）。是否在用由卡片本身表达：在用卡片填服务 container 色、比例 1，其余 `surfaceBright`、0.96。卡片文字 = 账号名（Subsonic 用用户名）+ 服务行（"Subsonic · host" / "Spotify"；标题已含服务名则省略）+ 状态胶囊（问题 > In use；在用账号有问题时写成 "问题 · In use"）。卡片行越过 16dp 页边铺到屏幕两缘，静止时首卡对齐页边，**不加边缘渐隐**（用户 2026-10-03 明确要求去掉，同 Home Recently Added 例外）；卡片高度按字号逐行计算，大字号不截断。打开管理页时把账号的"脸"（名称 / 位置 / 形状序号 / 头像地址，不含凭据）随 Intent 带过去，首帧即正确。
- 服务能做什么，只在用户表达兴趣时讲：Add → 选择面板（每项名称 + 一句话）→ 服务二级页 `ServiceSetupActivity`。二级页 = 服务标识 + 一句定位 + What you get（≤4 条亮点）+ You'll need（前置条件）+ 连接表单。不做支持矩阵、不罗列“不能做什么”；做不了的操作照旧隐藏，失败时给简短可处理的错误。
- 管理已有账号（编辑 Subsonic、Spotify 重新登录、凭据缺失恢复）复用同一二级页的 manage 模式：跳过介绍，直接给表单/操作。Spotify Client ID 属于一次性开发者配置，收进二级页的 Developer setup 折叠行，仅在缺失时自动展开；「No Client ID」深链打开该页并聚焦输入框。
- 返回：二级页是无共享 chrome 的全屏目的地 → Pattern A 原生跨 Activity 预测性返回，零 back 代码；新账号的切换由 Settings 在自己的 scope 里执行（二级页经 ActivityResult 回传 id）。
- Apple Music 是可切换的正式 Profile：二级页通过开发者 Token 服务和 MusicKit 授权创建或重新连接加密账号。2026-09-29 已在 Pixel Tablet 订阅账号验证整曲与系统媒体控制；重新授权、删除和蓝牙硬件仍须分别验证。2026-10-01 的目录 / 资料库搜索和 + 加入资料库实现复用现有页面；+ 与喜爱心形分开，必须通过个人资料库关系查询确认后才显示稳定勾选。HTTP 202 只显示待确认，不能当成完成，新加入流程的真机验证须单独记录。
- **云同步（2026-10-04，v1）**：Storage 组第一行「Cloud sync」（单色 Devices 图标，摘要 Off / On · Synced … / Paused · Reconnect Google…），打开 `CloudSyncActivity`（Pattern A，大屏在右栏打开、左栏行高亮）。可选、默认关闭、本地优先：存进用户自己 Google Drive 的 appDataFolder，只申请 `drive.appdata`。同步笔记 / 评分 / 专辑长评 / 首页布局 / 少量设置 / 付费 AI 歌词翻译，凭据与 API Key 永不出设备。关闭页 = 一句定位 + What you get（≤4）+ Good to know（如实说明不是端到端加密、占用户存储、服务器地址和用户名会同步）+ 按钮；开启页 = 状态 + Google 账号 + 每个音乐账号的同步状态 + Drive 里有本机没有的账号 + 设备 + 「在本设备关闭」/「删除云端数据」。产品规则和存储格式以 `docs/cloud-sync.md` 为准。
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
| Memories 卡片 ⇄ 日记（2026-10-04） | 一个 p（`MemoriesDiaryState`）驱动全部共享元素（`MemoriesMorph.kt`）。点 Diary 由弹簧推开，不跟手。封面尺寸领先于位置，飞进顶栏的 40dp 槽，圆角 8→4dp，在 fp 0.62–0.9 之间按透明度交给顶栏自己的封面；拟题在 p 0.2 之前不动，之后飞到日记标题，在 0.42–0.78 之间交叉淡化；96dp 徽记跟着拟题飞，说明字先淡出，在 0.6–0.95 之间交给原生 48dp；专辑行上移 36dp 淡出，摘录和按钮下沉 56dp 淡出；日记各块错落升起；Home 胶囊 88→36。卡片层和日记层任何时刻都完整排版，只做位移、缩放、透明度和逐帧圆角，所以返回手势可以全程擦洗。收起：顶部下拉、顶栏下拉、点顶栏封面或 ⌄、系统返回；如果日记标题已经滚到顶栏下面，正文原地下沉淡出，封面先在顶栏里等，滚动在看不见时归零，不倒带。打开、关闭、松手、返回的提交和取消都走同一条 morph 弹簧（Expressive 默认空间弹簧）。拖动越过 p 0.5 时打一拍 CLOCK_TICK，退回再打一拍。减少动态：同一个 p 只做淡入淡出 |
| Memories 回首页（2026-10-04） | q（`RevealState`）是唯一位移：宿主 translationY = −q·H，Home 在背后从 0.94 / 50% 回到 1 / 100%。手指 1:1，按 dp 判定：卡片主体上推 112dp 或 600dp/s 提交，顶栏上推 56dp 或 450dp/s 提交；反向快甩 350dp/s 即使越过阈值也收回。阈值从按下那一刻算，slop 吃掉的距离也计入。日记已经停在底部时，新起一次上推按卡片规则回首页；惯性滚到底只停住。系统返回：日记态先把日记收回卡片（p 从当前值按 backGestureEasing 全程擦洗到 0）；卡片态把 q 从当前值擦洗到 112dp，progress 1 正好停在手指的提交距离上。提交交给宿主的关闭弹簧，取消回弹，都用 `predictiveBackSettleSpring`；三键返回直接提交。底部两角按 28dp · smoothstep(0, 阈值 / H, q) 变圆，在松手会提交的位置正好变满，无阴影。越过提交线打一拍 CLOCK_TICK，退回再打一拍，提交时 CONFIRM。减少动态：原地淡出（alpha 1 − q），没有圆角 |
| 多层详情 / 播放器返回 | 返回当前窗口的真实来源；内层详情不改写主页的返回进度和底栏状态 |
| 歌词搜索 | 从实际搜索按钮展开，收起回到该按钮；复用官方 SearchBarState 与全屏 Search 的动效、键盘处理和预测性返回 |
| 切歌 | 大封面在新图加载成功后用封面专用低刚度、临界阻尼 Effects Spring 驱动细密错列的波点溶解，约 700ms 显影完成后自然收尾：波前本身持续起伏，圆点轻微漂移、柔和浮现后合拢，边缘带低强度 Primary → Tertiary 渐变光晕；下一首从右、上一首从左接管。点距约 6dp，旧图始终不透明兜底，新图随溶解逐步显影。缩略图保留轻量 crossfade，背景色继续原有 Effects Spring 过渡 |
| Library 列表滚到筛选胶囊下 | 潮线（2026-10-04 取代 10-01 的曲线 C 网点；与 Home 状态栏同一种线，详情页固定顶栏下相同）：静止在顶部时不出现；前 40dp 滚动里从交界上方降下，静止在交界下 2dp 再让出一个波峰。前层不透明（波长 72dp），后层低 6dp、55% 不透明（波长 116dp）；振幅 2.4dp，随滚动速度最多再加 3.2dp（150dp/s 起，1450dp/s 满），相位跟随滚动和余韵，停下即静止。不画底色而是遮罩：`seamDissolveViewport` 把波浪从内容层里挖掉，露出真正的背景（Library 的渐变、详情页的强调色底）。文字在水线以下淡出，alpha = 1 − (1 − x′)²，长度 max(T, 0.75 × 字号)，T 静止 10dp、快滑 26dp。省电模式和“移除动画”下相位固定、振幅 2.4dp。原因：顶部网点在快速滑动时挤成密排圆盘和针孔，有密恐感。这是默认样式，用户可在设置里换成原版网点或曲奇浪口（见「溶解」一节）。底部浮动栏的网点场不变 |
| 内容滚到底部浮动栏 | 底部网点场（2026-10-01）：以屏幕坐标计，过渡段从栏上方 20dp 开始，其余 28dp 藏在栏后，快速滚动时起点最多再上移 20dp；栏上沿 28dp 以下是覆盖率 40% 的静止点阵，越往下略细，一直铺到屏幕底边，栏两侧同样。点的颜色越往下越靠近页面底色（栏上沿 16%、屏幕底 32%），只作用在图形上。亮度（L*，含退色）与栏容器色相差 ≤ 6 的点在离栏 1.5dp 内消失、8.5dp 外恢复，≥ 18 不让，每个点只判断一次；还没开始碎的实色内容贴着栏时也参加。列表最后 40dp 滚动里过渡段收成 0，最后一项完整停在栏上方。过渡段的起伏和打旋都乘无序度 D = 1 − e^(−速度/200dp/s)；停下后 D 与余韵按同一个 e^(−ωt)（ω = √90）衰减，约 0.45 秒回到格点，静止时点阵完全规整。余韵：流动落后内容的量与速度成正比（上限 6dp，快滑 17dp），停下后以停前速度继续漂一小段再停，不回弹。省电模式和“移除动画”下无余韵、D = 0。栏下点阵不流动。栏无阴影；Now Playing 升起时网点场按效果弹簧淡出、收起后长回 |
| Home 内容滚进状态栏 | 潮线（2026-10-01）：两道页面底色的波浪从屏幕上沿盖到状态栏 + 2dp，前 40dp 滚动里从屏幕外降下来；前层不透明（波长 72dp），后层低 6dp、55% 不透明（波长 116dp）。振幅 2.4dp，随滚动速度最多再加 3.2dp；相位跟随滚动和余韵，停下即静止。内容沉进水线，图形顶部不再用网点；文字（含 32sp 大标题，淡出约 24dp）在状态栏 + 2dp 处淡完。省电和“移除动画”下相位固定、振幅 2.4dp。与 Now Playing 胶囊里的波浪是同一种线 |
| 歌词翻译开关 | 每行译文从行下沿按空间弹簧展开 / 收起（间距在动画块内，收起即单行高）；焦点行全程钉在 38% 锚点，上下行向两侧让开 |
| 打开歌词时切歌 | 歌词流“继续滚动”：旧歌冻结在最后播放位置，向上漂移并慢速淡出；新歌从下方升入（上一首则方向相反）。加载完成时歌词短距离升入、加载指示淡出。新歌前奏期间焦点位是歌名卡片。**自然播完且下一首歌词已预取时**改为预告式接续：最后一句一成为当前行就开始（末句之后的尾奏超过 10 秒时，从结束前 10 秒开始；2026-10-05 之前要等末句开始 2 秒后），末句做 I-2 拉长，进度过半后，下一首**只有歌名卡片**在末句下方以 0.4 的强度浮现、上升 28dp，它的歌词行要到交接时才出现（2026-10-08 owner：之前歌名加前几句以 0.75 浮现，太抢眼）——两者读同一个进度（24 级量化）；结束前约 1.4 秒（最晚 0.9 秒）整页上滑，把下一首歌名推到它自己列表的起始位置，同时当前歌词淡出；真正切歌时两份画面在歌名以下完全一致，原地替换、不再播放滑入滑出。提前跳歌、手动滚动过、没有下一首或歌词无时间轴时仍走上述滑动过渡 |

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

### 📱 大屏幕适配 / 响应式设计（2026-07 落地，2026-09-30 断点适配重订，2026-10-02 同窗分列）

> 规则本体在 `docs/adaptive-principles.md`（八条原则 + 各窗口一览），这里只记决定。

- 宽度三档 LayoutMode（Compact < 600 / Medium 600–840 / Wide ≥ 840）+ Tabletop 折叠姿态，**高度另读**：高 < 480 是手机横屏（`isCompactHeight`），页面先判高度再判宽度——844 宽的手机横屏不会落到桌面档。判定源都在 `ui/experience/WindowAdaptiveRuntime.kt`，由 M3 adaptive 的 window size class + 铰链姿态驱动，LayoutMode 按**列**相对：任何让出宽度的容器（NP 侧栏、详情列）用 `forPaneWidth(列宽)` 给子树重新提供 `LocalYoinWindowInfo`，600 宽的 shell 列就是 Medium
- Button Group 三种形态（竖屏底栏 / 手机横屏分离式挖孔带 / Medium+ 底栏居中限宽 600）见「导航结构」；`LocalShellChromeInsets` 给页面让位；原 Navigation Rail 已删除
- 跨窗口 bar 交接（`DetailLaunchMode.FullChoreography`）在竖屏底栏与分离式挖孔带下都做（两个窗口的组逐像素同位）；居中底栏（Medium 整窗）是纯推入，但栏仍然有动画——**窗口内 morph**（2026-10-03，`DETAIL_EXTRA_BAR_MORPH`）：详情窗口的栏从和 shell 底栏像素相同的导航姿态出发，页面滑入的那一拍 morph 成详情姿态；返回手势把它 scrub 回导航姿态，溶解落在 shell 的导航栏上。不桥接 shell 的任何状态（`bridgeBackToShell=false`）。Wide 整窗没有跨窗口交接——详情根本不是另一个窗口
- **详情列（2026-10-02，适配原则 3「分栏是同一窗口里的列」）**：Wide 且高 ≥ 480 的整窗（`YoinWindowInfo.hasDetailPane`）里，Album / Artist / Playlist 不再启动 Activity，而是作为 shell 窗口的**右列**打开（`ui/navigation/pane/`：Navigation 3 子栈，同一批页面 composable，`LocalDetailHostMode = Pane`）。shell 平时独占整窗，开第一个详情才分列、关最后一个解散，开合走 `defaultSpatialSpec`（列从右缘滑入、shell 列同步让位，`DetailPaneState.openFraction` 是唯一驱动）。列宽 = 一个纯函数 `resolvePaneBudget`：窗宽 − 24dp 槽，shell 份额默认 0.45、≥ 1080 时保证 shell ≥ 600（1280 → 600 ｜ 24 ｜ 656），两列都 ≥ 360；槽里是 M3 `VerticalDragHandle`，整条槽可拖、1:1、只钳制（session 内记住）。两列和槽共用 shell 的中性页面底色（列里的详情页不再画封面色顶部渐变），中间没有分隔线，只有把手。列里每页读自己的列宽（Medium / Compact 布局照页面规则）。列内推入 / 弹出 = AOSP 96dp + EMPHASIZED 450ms；**从 shell 点另一个页面 = 换根**（清栈再放，不算前进）：M3 fade-through：旧页原地快速淡出（快效果弹簧），新页从 0.96 淡入放大，不会两页文字叠在一起，不走推入（2026-10-05 owner 在折叠屏上：旧专辑往左滑出列外、停在 shell 上一会儿新专辑才出现）；列的内容裁在列内，推入时后退的 96dp 也不会画到槽或 shell 上；预测返回：栈内由 NavDisplay 的 predictive pop 做整体缩放预览，最后一页的返回缩放到 0.9 + 28dp 圆角、提交后列滑出、shell 变宽；返回归属 NowPlaying > DetailPane > Memories（`ShellBackResolver`），列的两个返回处理器挂在只在它拥有返回时才启用的子 dispatcher 上（处理器按注册先后排，后挂载的列否则会压过 NP）。列宽、侧栏让位、栏槽宽都在 layout 阶段算，手势 / 弹簧帧不重组 shell。换档永远不落在弹簧中途：开列时 shell 在点击那一帧换档，等帧率平稳后列才滑入（空白页面），页面在列落定后构建并淡入；关列时等弹簧落定后 shell 才换回 Wide。Wide → 窄（平板转竖屏）时列顶层页自动变成推入页。**Activity Embedding 的 shell ↔ detail 规则、`SplitAttributesCalculator` 的 shell 比例和平台分隔条全部删除**（两个 Activity 窗口物理上无法共享一条栏；平台分隔条拖后 calculator 会把比例改回去、颜色退成库默认黑色），只剩 Settings 的 list-detail
- 页面：Home / Library 手机横屏有单独一档（Home 28sp 标题、Activities 单行三卡；Library 单行头部 [搜索 208][chips][设置]、~100dp 格子 6 列）；详情页手机横屏把竖屏 hero 横过来（封面左、竖屏里封面下面的东西在右，上拉照旧），背景图形做成贴着封面的闭合形状、不伸到左边的组下面；Medium 歌手是宽 hero（名字只出现一次，Follow 进 hero，Play 只在底栏）；Wide 整窗专辑 / 歌单是 400 身份栏 + 完整曲目表，歌手是横顶 hero + Most Played 480 | Discography；Memories（2026-10-04 v4）按**自己的容器**分档（`memoriesLayoutFor(宽, 高)`，读 BoxWithConstraints，不看设备和 LayoutMode），门槛是 600 / 900：宽 < 600 是手机两态；Medium 是放大的手机两态，卡片在 480 列，封面 clamp(H − 580, 256, 360)，日记在 min(640, W − 32) 列，用平板字号；宽 ≥ 900、右栏 ≥ 360、而且左页放得下不小于 Medium 的封面时是对开：左页是展品、Yoin 的标题 / 旁白 / 问句和 Go to album，右页从你的条目开始，中间无分隔线、至少 64dp；左页上滑回首页，右页滚到底再上推回首页，返回只有一级。对开的高度梯子先收间距、再缩封面，整叠卡共用一个封面尺寸；窗口拖过 600 或 900 时展品永不变小。所以 1280 窗口开着详情列时，Memories 按 Medium 排。手机横屏（高 < 480）也用对开结构，但封面下限降到 88、徽记 48–72，Yoin 的段落移到右页开头
- 页面内容宽度有 clamp 基线（`yoinPageContentWidth`，Feed=720 / Prose=640 / Card=480）

---

## 评分与视觉形状系统

- **UI 交互**：滑动评分条（Now Playing 界面垂直粗条设计，连续滑动，精确到 0.1），视觉上极具 Expressive 张力
- **分数展示**：Library 和主页中，评分与 MD3 Expressive 新增的 Shape API（如多边形、Squircle 等）结合，作为卡片背景或徽章（如 7.1 分的异形徽章）
- **专辑分数的图形 = 共享的 `ScoreEmblem`（2026-10-05 owner：「那个徽章…去替代现在的这个评分的图形，把专辑页面的也改一下」）**：`ui/component/ScoreEmblem.kt` 是 Memories 唱片刻纹徽记（`ui/memories/emblem/`）对外的稳定门面，别的页面只从普通输入画它：分数、种类（`Album` 专辑评分 / `Average` 曲目均分 / `Unrated` 空模子）、每首是否有评分、封面两色（`rememberScoreEmblemColors(封面 URL)`，走 NP 的取色路径直接 lerp，不 fromSeed）、尺寸，`surface` 分 `Artwork`（Memories 卡面）和 `Page`（普通页面底）。规则跟着徽记走：一圈一首曲目、有评分的刻实线；标签优先专辑评分，其次曲目均分，都没有是空模子；不写 "Album" 字样（"Avg." 和 "Unrated" 保留）；扁平，无光泽、扫光、阴影；一位小数统一四舍五入（Float 先走 `toScoreEmblemScore()`，9.95 显示 10.0）。获得动画由调用方决定何时播（`rememberScoreEmblemAwardState`）。现在用在 Memories 卡片与日记、专辑页第 1 页（取代 Bun）和第 2 页（见「💿 专辑详情」）。艺术家页 hero 下面原先有一行 Last Play | 专辑评分均分徽记（W3，2026-10-05），2026-10-10 owner 以「完全没必要」整行删除，各档位都不换成别的信息；同日第二轮 owner 又拿掉了 Discography 每张发行右侧的个人专辑分数（Q7），艺术家页现在不显示任何评分。Bun（`AlbumScoreBun`）已删除。其它页面不许 import `ui/memories` 的内部实现，一律经 `ScoreEmblem`。
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


### Apple Music profiles (2026-10-01)

Apple Music connection creates a regular encrypted Profile; the previous validation authorization migrates once without automatically switching the active account. Its MusicSource supports library songs, albums, artists and read-only playlists, with distinct catalog and personal-library search scopes. MusicKit supplies DRM audio through a Media3 session shared by Now Playing and system controls. The separate library-add control uses the exact authenticated catalog-song → library relationship; it displays a stable checkmark only after membership is confirmed and preserves an unconfirmed HTTP 202 as pending. Membership never becomes a favorite heart. Unsupported favorite mutation, library removal, playlist editing, Cast and offline caching remain hidden. Imported tracks without a catalog playback ID are dimmed with a "?" badge on the cover whose tap expands the reason inline; they never enter the MusicKit queue. Since 2026-10-02 a library album opens as its full catalog album (Spotify parity): every catalog track is listed and the ones already in the user's library carry the check from `TrackLibraryButton`, which replaces the heart slot for Apple Music; tapping an unchecked row control adds that song. Library albums Apple cannot match to the catalog keep their library tracklist. Subscribed playback was verified on Pixel Tablet on 2026-09-29; these new search/library writes require their own current device verification, and reauthorization, deletion and Bluetooth hardware remain open.

### Spotify 心形状态（2026-10-10，P4）

Spotify 的「已喜欢」= Liked Songs（Yoin 的爱心就是它）。已保存镜像（`/me/tracks` 读最新 200 首，6-21 的决定不变）装不下主人约 3000 首的库，所以心形另有一层按账号隔离的状态：

- **读取入口**：`YoinRepository.observeFavoriteStates`（Now Playing 和专辑行的心形都读它）。通知栏快捷按钮（`SessionQuickActions`）和 Library 仍直接读 `favoriteOverrides` 加曲目自带标记：下面这层状态只有 Spotify 会写入，而快捷按钮只挂在 Subsonic 和 Apple Music 的媒体会话上，Library 的行也不画心形，所以两者看到的一致。优先级：正在进行的写入 > 刚落地的本地写（60 秒宽限内） > 远端确认和镜像行（比时间戳，新的胜出；本地写在宽限之后也按「写入时间 + 60 秒」参加比较） > 曲目自带的 `isStarred`。取消喜欢记成明确的 false，队列里的旧副本翻不回来。只存在内存里，切账号清空，晚到的结果丢弃。
- **Now Playing**：由 app 级单例 `PlaybackManager` 在当前曲目变化时查一次（warm-connect 接管也算），不放进各 Activity 的 Now Playing VM。先走 App Remote `UserApi.getLibraryState`（本机 IPC，不占 Web API 配额）；只有它在换歌时出错，且这首歌停留超过 0.8 秒，才回退到 Web API contains。之后的 PlayerState 事件（暂停、拖动、回到前台后的重连）至多每 30 秒重查一次，只走 App Remote，不发后台 Web API 请求。只查 `spotify:track:`，播客单集和本地文件跳过。
- **专辑页**：页面先出，加载后批量查一次 `GET /v1/me/library/contains`（每批最多 40 个 URI，一批接一批，不并发）。页面回到前台时再查，同一首歌 30 秒内只问一次（专辑页和 Now Playing 共用这个节流）。
- **限流**：contains 必须经过 `SpotifyRateLimitGate`，gate 关着就不查；失败和 429 都不重试。多批查询中途某一批失败时，前面几批的答案照样记下（`FavoriteStatesIncompleteException` 带回），没答到的曲目照常在 30 秒内算「问过」。写操作仍走已验证的 `PUT/DELETE /v1/me/library`。
- **三家**：Subsonic 的星标本来就在每个响应里，`favoriteStates` 用默认的「不支持」，行为不变；Apple Music 没有收藏能力，资料库成员状态照旧不显示成心形；只有 Spotify 实现了这次的查询。`favoriteStates` 不是新的界面能力，所以没有另设 `Capability`：门控沿用 `FAVORITES`，Subsonic 由默认的「不支持」挡住，不发请求。
- **已知局限**（待 Q17 真机探针确认 `getLibraryState` 的语义之后再定）：
  - App Remote 答的是 Spotify 应用当前登录的账号。Yoin 里配了两个 Spotify 账号、而当前账号不是 Spotify 应用登录的那个时，答案会记到当前账号下。
  - 镜像行的时间戳是同步写库的时间，不是读 `/me/tracks` 的时间：同步前几秒内的 App Remote 答复可能被它盖过，下一次检查时纠正。
  - 在通知栏或别的设备上点的喜欢，要等下一次 PlayerState 事件（距上次检查至少 30 秒）才会反映；没有窗口聚焦或定时触发。
- 歌单页、搜索、Library 的行不画心形，这次不加查询。艺人页关注星的同类问题另行排期（App Remote 只支持 track 和 album，那里只能用 Web API）。
