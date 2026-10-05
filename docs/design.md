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
  - **Medium 及以上 · 居中底栏**（宽 ≥ 600 且高 ≥ 480）：竖屏那条本身，水平居中、限宽 600，下边距 24（Wide 28），**高 60（按钮 44 + 8 × 2；2026-10-02 owner 嫌 68 在平板上像块板）**；详情形态把 ▾ 里的动作拿出来单独放（随机播放 / 前往歌手 / 分享）+ 定宽 200 的正在播放 pill。原左侧 Navigation Rail 已删除
  - **合体形态**（2026-10-02，Wide 整窗开着详情列时，适配原则 2「一窗一栏」）：同一条栏横跨两列、上限放宽到 720 —— [Home][Library][正在播放 pill 伸缩][Play ▾][随机播放]，导航在 shell 列那侧、页面的 Play 在详情列那侧、pill 跨过分隔线；前往歌手 / 分享收进 ▾。列开合的 spring 同时驱动栏的姿态；NP 侧栏打开时 pill 折起、栏缩到正好包住剩下的键
- 页面用 `LocalShellChromeInsets` 给 Button Group 让位（底部两种形态留栏高，分离式留左侧 84dp + 右侧挖孔）
- 被拿出来单独放的动作一律从 ▾ 菜单里去掉，不重复

### 🏠 主页

- Mix / 推荐区块（从 Navidrome 获取随机专辑、最近添加等；「最常播放」排序依赖 Scrobble，MVP 阶段使用服务端已有数据）
- 辅助可视化区域（当有曲目播放时，显示实时音频可视化效果）
- Memory teaser 只显示一条轻提示，点入 shell-owned Memories surface；Feed 不承载重型 Memory 卡片流
- 右上角 ⚙️ 设置入口
- **版块与布局**：Activities、Jump Back In、Recently Added、Rediscover，顺序和开关按 profile 存（`home_layout`）。只在真的改变时写入；改回默认就删掉这一行，等于「从未定制」。定制过的用户遇到新版块时，新版块以关闭状态追加进托盘并标 "New"；没定制过的按各版块的默认开关（Rediscover 默认开、排在最后）。
- **久别重逢 Rediscover（2026-10-04）**：评分 ≥ 8（专辑评分；没有时用曲目平均分，须 ≥ 60% 曲目有评分），且在 Yoin 里 90 天以上没播放过的专辑，高分在前，同分时久别在前。时间只看播放历史，浏览不算，所以文案写 "in Yoin"。和 JBI 的 memory 卡、记忆胶囊去重，本次会话里播放过的专辑离开货架。点按进专辑详情，不进 Memories。所有宽度都是横滑货架：N ≤ 2 最多 6 张，N 3–4 和手机横屏 2 张，N ≥ 5 3 张。没有数据就不渲染，编辑态显示占位说明。
- **就地编辑（2026-10-04，P0）**：取代 2026-07-02 的列表编辑器（`HomeLayoutEditor` 已删除）。
  - **进入**：在页面任意处长按，页边也算。按在卡片上，进入编辑并拿起这张卡所在的 section，底板从按点长出来；按在空白处只进入、不拿起。按住过半时，被按的块先轻微缩小、底板预显。其它入口：鼠标右键；feed 末尾常驻的 "Edit Home"（托盘里有没见过的新版块时带 "New"）；TalkBack 里卡片的长按和 section 标题上的「Edit Home」动作。header 的 Memories 入口、设置齿轮和气泡是排除区，长按不进入。
  - **编辑态**：标题交叉淡化成 "Edit Home"，前 2 次编辑在放得下时旁边显示 "Drag to reorder"。每块加底板、隐藏键和拖动把手（只剩一块时没有把手）。卡片级轻摆：约 1°、2.4Hz，6 秒没有触摸就渐停，任何触摸恢复；交界带内振幅为 0，省电模式和「移除动画」下不摆。卡片不可点，空 section 显示占位块，Memories 的下拉和气泡关闭。
  - **排序**：按住把手，或在块上按住片刻，拿起这一块；一开始拖动，所有块收成带 3 张真实封面的签条，在手指下短距离排序，松手后展开落位。结算途中可以再次抓住。参数按原型 `proto.js` 原样移植。
  - **隐藏与恢复**：隐藏的块缩放淡出，进 feed 末尾的 "Hidden" 托盘；托盘每行点 + 回到原来的位置，"Reset Home" 回到默认。每一步立即保存，可以 Undo（最多 20 步，退出即清空）。全部隐藏时，普通态显示 "Your Home is empty" 卡。
  - **退出**：Done、系统返回（= Done，离散，不震；返回进度预览是 P1）、点空白处。切 section、打开 Now Playing 或详情、进设置、App 退到后台时直接收起。状态是 `HomeSurface.Edit`，由 shell 持有的 `HomeEditController` 独占写入；返回归 `ShellBackOwner.HomeEdit`，优先级 NowPlaying > HomeEdit > DetailPane > Memories。
  - **无障碍**：编辑态每块是一个 TalkBack 停留点，播报 "Section j of N"，带 Move up / Move down / Hide 动作；托盘行带 Show。
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
  - 两簇。展品簇定高：顶部留白 → 封面 + 徽记 → 拟题 → 专辑行，所以每张卡的封面顶边同位。预告簇贴底：摘录 → [Diary · N notes | Go to album] → 「Swipe up for Home」提示（短屏只留箭头）。中间的空隙吃掉余量。
  - 手机封面 256dp、徽记 96dp；短屏（高 < 760）168 / 72。徽记挂在封面右下角，向右、向下各探出自身尺寸的 0.3 / 0.24。
  - 摘录只用整句，永远不出省略号。依次试：乐评开头的整句（从多到少）；没有乐评时，第一条单曲笔记；最短的一条单曲笔记；最后只剩署名行（"Your review · Jul 26 · in Diary"）。放得下哪个就用哪个。字号按长度分三档：≤ 16 字 22 / 500，≤ 60 字 17，更长 16，用系统字面。Medium 上摘录只是预告：最多两句、约 60 个加权字。
  - 按钮：Diary 是 tonal（专辑 ink 14% 底，高 48），带笔记数；Go to album 是专辑色实心胶囊，不带箭头。
  - 手势：卡片上任意位置上滑一次，回首页。往下拉是 0.3× 橡皮筋（最多 −90dp），松手回卡片；拉下后再往上推也只停在卡片。打开日记只靠 Diary 按钮。
- **日记态**：
  - 只能纵向滚动。每页有自己的滚动位置，离开这一页就归零；开着日记横滑，下一张卡也是日记态，从头读。顶部交界用潮线（`seamDissolveViewport`），底部 40dp 渐隐。
  - 顶栏：胶囊收窄到 36dp，只剩箭头；后面是 40dp 封面（4dp 圆角）和专辑名、艺人行、⌄。艺人行放不下时先去掉年份，还放不下才跑马灯；跑马灯只在页面停稳、日记完全打开时滚。
  - 从上到下：标题行（Yoin 的标题，右侧是原生 48dp 徽记）→ Yoin 的段落（只在没有乐评时出现：讲你是怎么听的，问句接在同一段末尾）→ 你的条目 → 两段真实内容之间放 • • • → 曲目行 → 结尾。
  - 你的条目：有乐评就是乐评，不加引号、不署名；16 字以内又没有笔记的短乐评放大（26，平板 30），停在可视区 38% 的光学位置。没有乐评就是今天的空白日记页：左侧日记竖线加保存胶囊（共享组件 `JournalRail` / `JournalSavePill`；NP 的笔记页已改用方向 A，不再有竖线），用 BasicTextField，不用 OutlinedTextField；Cancel / Save 落在下面。保存后原地变成乐评条目：竖线淡出，正文左移 14→0（空间弹簧），标签 Today → Your review 交叉淡化，打一拍 CONFIRM。保存失败保留草稿，用 snackbar 提示。
  - 回卡片：在顶部继续下拉；把顶栏往下拉（1:1，滚动冻结）；点顶栏封面或 ⌄；系统返回。从正文里下拉越过顶部时，前 24dp 走半速；如果起手时正文已经滚动过，松手时还在半速带里就留在日记。惯性滚到顶只停住。
  - 回首页：顶栏上推；日记已经停在底部时，新起一次上推（越过末尾再推）。惯性滚到底只停住，不回首页。
- **曲目行（选项 A，显式推翻 v2.2 的「别复活曲目表」）**：日记只列有评分或有笔记的曲目，不是完整曲目表；完整曲目表仍在 Go to album 打开的专辑页里。专辑笔记排最前，左边一颗 6dp 空心小珠，不可点。每条曲目行高 48：曲号 / 歌名 / 分数。笔记像歌词一样挂在所属曲目下面：40dp 列里放时间戳，后面是正文，两者按基线对齐。点曲目行从头播放；点笔记行播放这首，等它成为当前曲目后 seek 到笔记的时间锚点一次（4 秒内没就绪就放弃）。正在播的曲目和播放头所在的笔记只变色高亮，不压暗其它行，也不自动滚动。
- **结尾**：跟着内容走，离上一块 56dp。依次是 28dp 的导出槽（三圈发丝细环）、两个大数字（在 Yoin 里的播放次数；距第一次播放的天数，"days since Mar 14"）、Go to album、NeoDB 入口。页脚只有这两个数字，取代旧的证据句和 NeoDB 状态页脚。「听过」只看播放历史，访问专辑页不算；从没在 Yoin 里播放过的专辑，两个数字整个不显示，顶栏也不写 Last heard。
- **NeoDB 入口（2026-10-04，PLAN Q1）**：日记末尾、两个数字下面，一行安静的 "Push to NeoDB"（onSurfaceVariant 小字，无底色，点按区 48），只在 NeoDB 已配置时出现，推送中显示 "Pushing to NeoDB…"。离线等失败只出 snackbar，不崩溃。卡面不再显示同步状态。
- **不署名**：Yoin 写的字不署名，去掉 "Written by Yoin"（取代 2026-07-26「Yoin 代笔文案必须带署名」）。Yoin 的字和用户的字靠字体和位置区分。
- **字体（取代下面 2026-07-26 规范里 Memories 的部分）**：
  - 宋体（`YoinSerifTitle`）只给 AI 拟题：卡片 26sp SemiBold（短屏 24，Medium 30），日记标题 22（大屏 24），对开左页 30（收紧档 27）。
  - 没有 AI 拟题时，标题是本地规则生成的动机短句，用 GSF 600、ROND 60（卡片 24，Medium 27；日记 21 / 23）。连动机短句都凑不出时，标题位放专辑名（GSF）。
  - Yoin 的旁白：GSF ROND 60，16 / 1.6，onSurfaceVariant；末尾的问句 onSurface、500。
  - 用户的字（摘录、乐评、笔记）用系统字面 `FontFamily.Default`。页脚数字用 GSF 500、ROND 40。其余标签和按钮用 GSF。
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

**拟题豁免（2026-07-26 决定）**：AI 拟题（`memoryTitle`，同时复用为首页 Jump Back In memory 槽位的标题）是「不上传原文」的唯一例外——拟题 prompt 允许携带正文槽占用者（album review 或最新一条 note）的原文，并拼上专辑背景（专辑名/艺人/年份 + 本地已缓存的 Gemini About 行，不产生额外请求）。豁免仅此一处用途；旁白只收事实清单。未配置 BYOK 或生成失败时，拟题槽永不为空：Memories 退到动机短句，再退到专辑名；首页 JBI 仍用原来的本地 deterministic 模板（覆盖率 / 笔记数，要不要也改成动机短句见 PLAN Q6）。

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
  - **16:9 矮屏**（先用完上下两层间距仍放不下两句完整歌词，适配原则 6）：Lyrics / About / Note 那一行 + 歌词窗口收成**一行当前歌词**（bold、primary、单行省略），整行就是展开按钮，没有单独的展开键；同一句停 ≥ 8 秒时这一行交叉淡入成「展开符号动画 + Tap to expand」，**一天最多一次**（按本地日期记在 `yoin_ui_hints`）；动画缩放为 0 时符号不动、字停约 2 秒。歌词展开页 tabs 保持文字，4 个歌词工具键挪到 tabs 同一行右侧，底部只剩标题
  - **自动沉浸**（所有尺寸）：播放中 + Lyrics 页 + 有同步歌词 + 5 秒无操作 → 只有 4 个歌词工具键隐藏；在底部时淡出并塌缩槽位（标题下沉、歌词往下长），在顶部时原地淡出；手动滚过歌词、触摸、暂停都会恢复
  - **控件永远排得下**：胶囊组先按真实宽度量，放不下整组收成纯图标（按下时展开标签），三个都在；控制行先收 PLAY 的内边距、再把控件从 56 降到 48，播放模式键永远完整
  - **Medium 整窗（折叠屏内屏、平板竖屏）· Spotify 式**：点 pill 先从右边推出手机宽的**侧栏**（clamp(窗宽 × 0.5, 360, 420)，左侧圆角 28，就是手机那一页、歌词吃满剩余高度）；旁边的内容让出这块宽度继续可用、读成 Compact；底栏折成 [Home][Library] 居中。侧栏右上角「全屏」→ **放大的手机**（列宽 ≤ 640 居中，封面随高度收，评分列 / 控件随列放大；右上角「收回侧栏」）。返回一级一级退：全屏 → 侧栏 → 关闭，左上角 ▾ 直接关闭；侧栏往右滑也能关，三者共用一个 dismiss 控制器。展开折叠屏（Compact → Medium）时直接进全屏态，其它进入 Medium 默认侧栏；「侧栏 / 全屏」状态在 NP 的 ViewModel 里。窗宽 − 侧栏 < 320 时不开侧栏，直接全屏态
  - **侧栏被手势带走时旁边的内容跟着补位**（2026-10-02）：返回预览 / 右滑关闭把侧栏往右带多少，宿主内容就 1:1 收回多少宽度（`NowPlayingPanelMotion` → `rememberNowPlayingPanelInset`），提交后冻结最后的位移随开合 spring 收完——侧栏和内容之间不再露出一条光秃秃的窗口底色
  - **Wide 整窗（平板横屏、桌面窗口）也是同一条链**（2026-10-02，适配原则 4）：点 pill 先开侧栏（宽同上），右上角「全屏」→ **双栏 TabletNP**（左栏的控件、评分、标题、胶囊恒按 312 排，封面吃剩下的高度、最大 312——矮窗先收间距、再把标题收成一行跑马灯，不再让控件跟着封面变窄；右栏歌词吃剩下的；点封面 = 共享的「看封面」，评分和标题让位、封面最多 1.5 倍、左栏跟着封面加宽、右栏不小于 320），右上角同一位置「收回侧栏」。返回：双栏 → 侧栏 → 关闭。横竖屏切换保留用户的侧栏 / 全屏选择。开着详情列时侧栏照开，两列整体让位（1280 → 剩 860 仍分两列，各读 Compact）；侧栏旁放不下两列（窗宽 − 侧栏 < 744，即 < 1164 的 Wide 窗）时 NP 直接全屏双栏。在双栏里点「前往专辑」：侧栏放得下就先退回侧栏再开列，放不下就收起 NP 再开列——页面永远可见。旧裁决「分栏窗格里 NP 占满本窗格、无侧栏无全屏键」随 Activity Embedding 分栏一起废止
  - **放大的手机的高度预算**（适配原则 6）：歌词窗口至少可见 4 行（165dp，按 `LyricsDisplay` 真实几何）、封面最小 168，先收间距再缩封面，再不够才退到一行歌词
  - **手机横屏**（2026-10-03 owner）：静止时右栏不显示 Lyrics / About / Note，只有一行可点的当前歌词（同 16:9 规则，点开进展开页才出 tabs + 工具）；标题和歌手并一行；右栏整列避开远端挖孔（播放模式键在行尾）。16:9 矮屏的单列同样把标题和歌手并一行（高度梯子里排在歌词换形之前）
  - **点封面 = 看封面**（2026-10-03，适配原则 6）：手机 / 侧栏 / 放大 / 双栏 / 手机横屏共用 Immersive；评分、tabs、歌词窗口、间距让给封面，封面至多 1.5 倍，**当前那一行歌词始终保留**；长不大 max(40dp, 12%) 的窗口里封面是纯图片、不可点。平板竖屏侧栏和放大态在焦点里保留歌词列表（放得下可见 4 行时）

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
- 长按底部 Library：支持目录搜索的 profile 打开搜索框并默认 scope 为该服务目录（Spotify：`Search Spotify`、Spotify / Library；Apple Music：`Search Apple Music`、Apple Music / Library）；Subsonic 打开 Current Library 搜索。Apple Music 的 Library scope 调用个人资料库搜索接口，歌曲标签读取已加入资料库的歌曲，不用随机歌曲代替
- 右上角 ⚙️ 设置入口
- 筛选胶囊与下方网格 / 列表的交界用潮线（2026-10-04 取代 10-01 的曲线 C 网点；2026-09-29 起已取代硬截断）：交界处不盖任何渐变、模糊或色带；两道页面底色的波浪以遮罩形式把内容从交界处挖掉，图形沉进水线，文字在水线以下 10dp 内淡完、不拆成点。曲线 C 的顶部网点在快速滑动时挤成密排圆盘和针孔，有密恐感，所以上面默认用潮线，下面保留网点场；用户可在 设置 › Motion › Scroll edge 换成原版网点或曲奇浪口（见「溶解」一节）。胶囊下的固定间距只留 4dp，网格顶部内边距 8dp
- 五个标签的底部都接底部网点场：封面到栏上方 20dp 才开始碎，栏下和栏两侧是纯网点；文字照常从栏下穿过
- 这对遮罩是通用语言：任何“滚动内容撞上固定 chrome”的交界都应复用（规则见「溶解（Dissolve）」一节）

### ⚙️ 设置（从主页或 Library 进入）

- 服务器配置（Subsonic/Navidrome 地址、认证）
- 缓存管理（容量限制、清除）
- 主题偏好
- 关于 / 版本信息

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

### 📱 大屏幕适配 / 响应式设计（2026-07 落地，2026-09-30 断点适配重订，2026-10-02 同窗分列）

> 规则本体在 `docs/adaptive-principles.md`（八条原则 + 各窗口一览），这里只记决定。

- 宽度三档 LayoutMode（Compact < 600 / Medium 600–840 / Wide ≥ 840）+ Tabletop 折叠姿态，**高度另读**：高 < 480 是手机横屏（`isCompactHeight`），页面先判高度再判宽度——844 宽的手机横屏不会落到桌面档。判定源都在 `ui/experience/WindowAdaptiveRuntime.kt`，由 M3 adaptive 的 window size class + 铰链姿态驱动，LayoutMode 按**列**相对：任何让出宽度的容器（NP 侧栏、详情列）用 `forPaneWidth(列宽)` 给子树重新提供 `LocalYoinWindowInfo`，600 宽的 shell 列就是 Medium
- Button Group 三种形态（竖屏底栏 / 手机横屏分离式挖孔带 / Medium+ 底栏居中限宽 600）见「导航结构」；`LocalShellChromeInsets` 给页面让位；原 Navigation Rail 已删除
- 跨窗口 bar 交接（`DetailLaunchMode.FullChoreography`）在竖屏底栏与分离式挖孔带下都做（两个窗口的组逐像素同位）；居中底栏（Medium 整窗）是纯推入，但栏仍然有动画——**窗口内 morph**（2026-10-03，`DETAIL_EXTRA_BAR_MORPH`）：详情窗口的栏从和 shell 底栏像素相同的导航姿态出发，页面滑入的那一拍 morph 成详情姿态；返回手势把它 scrub 回导航姿态，溶解落在 shell 的导航栏上。不桥接 shell 的任何状态（`bridgeBackToShell=false`）。Wide 整窗没有跨窗口交接——详情根本不是另一个窗口
- **详情列（2026-10-02，适配原则 3「分栏是同一窗口里的列」）**：Wide 且高 ≥ 480 的整窗（`YoinWindowInfo.hasDetailPane`）里，Album / Artist / Playlist 不再启动 Activity，而是作为 shell 窗口的**右列**打开（`ui/navigation/pane/`：Navigation 3 子栈，同一批页面 composable，`LocalDetailHostMode = Pane`）。shell 平时独占整窗，开第一个详情才分列、关最后一个解散，开合走 `defaultSpatialSpec`（列从右缘滑入、shell 列同步让位，`DetailPaneState.openFraction` 是唯一驱动）。列宽 = 一个纯函数 `resolvePaneBudget`：窗宽 − 24dp 槽，shell 份额默认 0.45、≥ 1080 时保证 shell ≥ 600（1280 → 600 ｜ 24 ｜ 656），两列都 ≥ 360；槽里是 M3 `VerticalDragHandle`，整条槽可拖、1:1、只钳制（session 内记住）。两列和槽共用 shell 的中性页面底色（列里的详情页不再画封面色顶部渐变），中间没有分隔线，只有把手。列里每页读自己的列宽（Medium / Compact 布局照页面规则）。列内推入 / 弹出 = AOSP 96dp + EMPHASIZED 450ms；预测返回：栈内由 NavDisplay 的 predictive pop 做整体缩放预览，最后一页的返回缩放到 0.9 + 28dp 圆角、提交后列滑出、shell 变宽；返回归属 NowPlaying > DetailPane > Memories（`ShellBackResolver`），列的两个返回处理器挂在只在它拥有返回时才启用的子 dispatcher 上（处理器按注册先后排，后挂载的列否则会压过 NP）。列宽、侧栏让位、栏槽宽都在 layout 阶段算，手势 / 弹簧帧不重组 shell。换档永远不落在弹簧中途：开列时 shell 在点击那一帧换档，等帧率平稳后列才滑入（空白页面），页面在列落定后构建并淡入；关列时等弹簧落定后 shell 才换回 Wide。Wide → 窄（平板转竖屏）时列顶层页自动变成推入页。**Activity Embedding 的 shell ↔ detail 规则、`SplitAttributesCalculator` 的 shell 比例和平台分隔条全部删除**（两个 Activity 窗口物理上无法共享一条栏；平台分隔条拖后 calculator 会把比例改回去、颜色退成库默认黑色），只剩 Settings 的 list-detail
- 页面：Home / Library 手机横屏有单独一档（Home 28sp 标题、Activities 单行三卡；Library 单行头部 [搜索 208][chips][设置]、~100dp 格子 6 列）；详情页手机横屏把竖屏 hero 横过来（封面左、竖屏里封面下面的东西在右，上拉照旧），背景图形做成贴着封面的闭合形状、不伸到左边的组下面；Medium 歌手是宽 hero（名字只出现一次，Follow 进 hero，Play 只在底栏）；Wide 整窗专辑 / 歌单是 400 身份栏 + 完整曲目表，歌手是横顶 hero + Most Played 480 | Discography；Memories（2026-10-04 v4）按**自己的容器**分档（`memoriesLayoutFor(宽, 高)`，读 BoxWithConstraints，不看设备和 LayoutMode），门槛是 600 / 900：宽 < 600 是手机两态；Medium 是放大的手机两态，卡片在 480 列，封面 clamp(H − 580, 256, 360)，日记在 min(640, W − 32) 列，用平板字号；宽 ≥ 900、右栏 ≥ 360、而且左页放得下不小于 Medium 的封面时是对开：左页是展品、Yoin 的标题 / 旁白 / 问句和 Go to album，右页从你的条目开始，中间无分隔线、至少 64dp；左页上滑回首页，右页滚到底再上推回首页，返回只有一级。对开的高度梯子先收间距、再缩封面，整叠卡共用一个封面尺寸；窗口拖过 600 或 900 时展品永不变小。所以 1280 窗口开着详情列时，Memories 按 Medium 排。手机横屏（高 < 480）也用对开结构，但封面下限降到 88、徽记 48–72，Yoin 的段落移到右页开头
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


### Apple Music profiles (2026-10-01)

Apple Music connection creates a regular encrypted Profile; the previous validation authorization migrates once without automatically switching the active account. Its MusicSource supports library songs, albums, artists and read-only playlists, with distinct catalog and personal-library search scopes. MusicKit supplies DRM audio through a Media3 session shared by Now Playing and system controls. The separate library-add control uses the exact authenticated catalog-song → library relationship; it displays a stable checkmark only after membership is confirmed and preserves an unconfirmed HTTP 202 as pending. Membership never becomes a favorite heart. Unsupported favorite mutation, library removal, playlist editing, Cast and offline caching remain hidden. Imported tracks without a catalog playback ID are dimmed with a "?" badge on the cover whose tap expands the reason inline; they never enter the MusicKit queue. Since 2026-10-02 a library album opens as its full catalog album (Spotify parity): every catalog track is listed and the ones already in the user's library carry the check from `TrackLibraryButton`, which replaces the heart slot for Apple Music; tapping an unchecked row control adds that song. Library albums Apple cannot match to the catalog keep their library tracklist. Subscribed playback was verified on Pixel Tablet on 2026-09-29; these new search/library writes require their own current device verification, and reauthorization, deletion and Bluetooth hardware remain open.
