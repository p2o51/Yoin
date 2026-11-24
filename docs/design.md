## 项目定位

> 本文档为 **Yoin 0.6.3+** 的产品与视觉动效设计基准，覆盖 **Android**、**iOS & iPadOS**、**macOS**（iPad-on-Mac 多平台运行，原生 `YoinMac` 已冻结）与 **HarmonyOS** 四端，以及 **Subsonic / OpenSubsonic**（含 Navidrome）、**Spotify** 与 **Apple Music** 三类音源。四端架构差异、能力矩阵与平台限制参见 [platforms.md](platforms.md)，文档总览见 [README.md](README.md)。

- **产品名称**：Yoin（源自日语「余韻」）
- **一句话定义**：轻盈、重视情感记录（Memories）与连续弹簧动效的多音源、跨平台原生音乐客户端（Spotoolfy 架构演进接班）
- **目标用户**：同时使用自建 Navidrome / Subsonic 服务器、Spotify 或 Apple Music，追求高质感界面动效与听歌记忆沉淀的个人听众
- **核心差异化**：全链路弹簧物理动效（每个交互与返回均有连贯过渡，零生硬跳转）+ MD3 Expressive / Apple Liquid Glass 视觉张力 + 跨平台云同步与接力体验
- **克制边界**：遵循 YAGNI，聚焦纯粹听歌与音乐记忆（Memories / 剪贴簿 / 笔记 / 评论），不做播客、电台社交、聊天室、内置浏览器等冗余模块

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
2. **默认 = 壁纸色相 + Expressive 配色（2026-10-19 owner：默认配色太平淡，三星刚登录时最明显）** — 深浅色跟随系统设置（`isSystemInDarkTheme()`）。不再直接用系统 `dynamic*ColorScheme()`（系统的 Tonal Spot 色板偏灰），而是取壁纸的源色（API 31+ `system_accent1_500`）的色相，和封面取色一样生成 SPEC_2025 `SchemeExpressive`；壁纸近灰（HCT chroma < 6）或 Android 12 以下时，从 Yoin 图标的紫色起。实现在 `ui/theme/DefaultColorScheme.kt`，Settings 分栏的动画底色同源。
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
- PLAY ⇄ PAUSE 时胶囊变宽变窄（词宽 + 两侧留白）是同一个 Standard 快空间弹簧，和形变图标同频落定，NEXT 跟着平移，没有单帧跳变：宽度动画放在胶囊内容上，因为 ButtonGroup 按子项的固有宽度排版，`animateContentSize` 对它不起作用。按下只用 ButtonGroup 自带的 `animateWidth`（按下的那个变宽，旁边的让位），Yoin 不再叠自己的按下宽度。Apple Music 的播放 / 暂停在 MusicKit 回报之前按请求发布（最多等 2 秒，仍在缓冲就接着等），按钮不会「新 → 旧 → 新」来回翻（2026-10-12，熟人工单 4）。
- 返回、左右箭头、发送在 RTL 布局里自动镜像。
- 按场景选：专辑封面兜底 `Album`，歌单封面兜底 `Playlist`，单曲兜底 `MusicNote`，歌手 `Artist`，队列 `Queue`。

**动效符号和用在哪**（都读 `LocalSymbolMotion`，不显式传 motion）

| 符号 | 用在哪 |
| --- | --- |
| 翻译 `rememberTranslateSymbolPainter` | 歌词工具条的翻译键：翻译进行中「文」「A」绕圈换位，结束回到「文A」；进行中按键保持亮着 |
| 收藏 `rememberFavoriteSymbolPainter`（经 `FavoriteGlyphIcon`） | Now Playing 的收藏键、专辑页曲目行的收藏键；只有晚到的远端确认静默翻转，其余变化照常跳，规则见下 |
| 均衡器 `rememberEqualizerSymbolPainter` | 专辑页当前曲目：播放时跳，暂停后从两边往中间沉成点 |
| 展开箭头 `rememberExpandSymbolPainter` | 设置的可展开项、Play ▾（横版和竖版）：铰链式先压平再翻过去，不转圈 |
| 播放模式 `rememberPlayModeSymbolPainter` | Now Playing 的播放模式键 |

- 动效档位跟随 `MotionProfile`：`AdaptiveReduced` 对应 `SymbolMotion.Reduced`，其余 `SymbolMotion.Default`（库的默认弹簧就是 M3 Expressive motion scheme），在 `YoinActivityRoot` 统一提供。系统「移除动画」时符号静止。
- **收藏心形：晚到的远端确认静默翻转（D4，2026-10-10 owner）**：主人拍板的只有这一条——先出页面，远端确认晚到时心形静默翻转，只做填充和颜色的 effects spring 过渡，不播 beat。Now Playing 的收藏键和专辑页曲目行都一样。
  - **静默翻转**只给远端确认（外加下面专辑页打开时的第一次对齐）：Spotify 对一次查询的回答（App Remote `getLibraryState` 或 Web API contains）在页面出来之后才到，并且翻转了心形。`FavoriteGlyphIcon` 按 key 换一个新的 painter，它一出现就处在终态；新旧两层按 effects spring 交叉淡入，只有填充和颜色在变，不跳。
  - **其余变化照旧**（P4 之前的行为）：用户点的、写入失败回退、资料库同步、换到下一首（下一首已喜欢就照常亮起），都由同一个 `rememberFavoriteSymbolPainter` 变化——点亮时填充长满、轮廓跳一下（beat），取消时只缩回填充。按钮自己的按压回弹照旧；专辑行的底色、描边、心形颜色照旧走 effects spring。
  - 实现：状态层是 `FavoriteGlyph(favorite, quietFlips, answeredAtMs)`，`quietFlips` 只在晚到的远端确认翻转心形时 +1。仓库的 `FavoriteState` 标出值是不是远端的回答（`fromAnswer`），并带上这首歌最新一次回答的时间（`answeredAtMs`，不管最后显示的是哪一层都带着）。只有比心形已经见过的更新的回答才算「晚到」，所以写入失败退回到之前那次回答时照常变化，不算静默。Now Playing 换了一首用 `FavoriteGlyph.forTrack`，照常变化，这首歌之后再到的回答才静默。`YoinRepository.observeFavoriteStates` 是这两处心形的读取入口。没有改 yoin-symbols。
  - 先出页面再确认：Spotify 的已保存镜像只有最新 200 首，所以页面先按已知状态显示，确认结果晚到时静默翻转。不加载中样式，不加文字。
  - 专辑页打开时的第一次对齐也静默：曲目行先按专辑自带的标记画出来（专辑可能来自 7 天磁盘缓存，标记是旧的）。页面打开时发出的那次查询（`refreshFavoriteStates`）回来之前，每行第一次由非用户来源（已保存镜像、叠加层、远端回答）带来的、和初值不同的状态，按静默翻转处理（`FavoriteGlyph.settle`），这期间落地的资料库同步、页面打开前就写好的喜欢都算；和初值相同的状态不算对齐，这一行接着等。查询回来时，读到过状态的行就算对齐完了，之后的资料库同步照常 beat；还没读到过状态的行，第一份状态照样静默对齐。每行只静默对齐一次，之后的变化照上面的规则走。用户自己的变化不算对齐：在这一页点过的行、Now Playing 正在写的那一首，照常 beat，写入失败的回退也照常 beat。Now Playing 的心形直接从仓库的第一份状态起步（`FavoriteGlyph.of`），没有这一步。

**播放模式**（2026-10-17 owner「上线冲刺」重新抽象：随机和循环是两条独立的轴，2 × 2 = 4 种组合；默认是一颗圆形按钮，点一下按顺序切换，长按分裂成左随机、右循环两段，默认列表循环）

| 模式 | 随机 | 循环 | Media3 | Spotify App Remote |
| --- | --- | --- | --- | --- |
| 列表循环（默认） | 关 | 全部 | `REPEAT_MODE_ALL`，shuffle 关 | `setRepeat(ALL)` + `setShuffle(false)` |
| 随机 | 开 | 全部 | `REPEAT_MODE_ALL`，shuffle 开 | `setRepeat(ALL)` + `setShuffle(true)` |
| 随机 + 单曲循环 | 开 | 单曲 | `REPEAT_MODE_ONE`，shuffle 开 | `setRepeat(ONE)` + `setShuffle(true)` |
| 单曲循环 | 关 | 单曲 | `REPEAT_MODE_ONE`，shuffle 关 | `setRepeat(ONE)` + `setShuffle(false)` |

- 顺序：列表循环 → 随机 → 随机 + 单曲循环 → 单曲循环 → 列表循环。每一下只翻一段（格雷码），按钮上总是只有一段在变。开播时 repeat 和 shuffle 一起写；播放中点按钮只写变了的那一条轴（MusicKit 收到多余的 repeat 写入会先把 shuffle 恢复成旧值，暂停一会儿后的第一下会因此落空，2026-10-17 平板实测）。
- 「随机 + 单曲循环」：这首歌一直重复；手动 NEXT / PREVIOUS 时按随机顺序跳。
- 读回：两条轴分别读——`REPEAT_MODE_ONE` → 单曲，`REPEAT_MODE_OFF` → 不循环，`REPEAT_MODE_ALL` → 全部；shuffle 开 → 随机。
- **不循环（Repeat Off，Gemini 审查 1.4，owner 2026-10-21）**：只在长按分裂出的胶囊里。循环那一段点按的顺序是 全部 → 单曲 → 不循环 → 全部，随机那一段不受影响，所以有「不循环」和「随机 + 不循环」两种模式。圆键的四态循环不变：处在不循环时点一下圆键，回到列表循环，随机保持原样。不循环时，圆键（或分裂后循环那一段）的图标上有一道斜杠，用效果弹簧画进、画出；圆键这时亮起，因为偏离了默认。这一段对 TalkBack 来说是一颗按钮，状态读作「列表循环 / 单曲循环 / 不循环」。Media3、Spotify App Remote 和 MusicKit 的 OFF 都是 0，直接写入。没有读到 repeat 时（空的 `PlaybackState`、App Remote 还没给 playbackOptions），按全部处理。
- **教长按（owner 2026-10-21，和 Tips 合并）**：在 Now Playing 里快速点满圆键一整圈（四下，每两下间隔不超过 1.5 秒）回到起点时，如果 Tips 里的「随机与循环」还没读过，按钮自动分裂一次，并把这条 tip 记成已读，所以只演示一次。在设置里把 Tips 全部重置为未读，会再演示一次。实现 `PlayModeCycle`。
- 控制栏布局（2026-10-21 owner）：上一首留在第二行、在进度条左边，不并到第一行。
- 按钮（2026-10-17 owner 批注改）：**默认还是原来那颗圆形按钮**，图标是四态的 `rememberPlayModeSymbolPainter`（随机 + 单曲循环 = 交叉的两股，「1」缩到 0.72 倍、待在交叉左边的空隙里；从单曲循环转过去时「1」跟着两股一起滑过去），点一下按上面的顺序走一格。**长按分裂**成一颗两段的胶囊：左段随机、右段循环，各自点一下只切自己那条轴，亮成 `primary` / `onPrimary` 表示偏离默认（随机开、单曲循环），默认留在 `tertiaryContainer` / `onTertiaryContainer`；3 秒没人点（或再长按一次）**融合**回圆形。分裂时圆里的图标变成两份，一边往各自段的中心走一边变形——左边变成交叉的两股，右边变成循环（单曲时带 1）；融合时反过来，两份一边靠拢一边变回同一个四态图标。宽度、间隙、内角和图标位移共用一条空间弹簧，颜色走效果弹簧；圆形时两半内角为 0，正好拼成一个圆。圆形亮起的条件是「不是列表循环」。实现 `PlaybackControls.PlayModeButton`，图标动画在 yoin-symbols 的 `AnimatedSymbols.kt`。
- Apple Music：MusicKit（1.1.1）在队列还没有当前歌曲时会不声不响地丢掉 repeat / shuffle 的写入。冷启动或 Apple Music 会话重建后，Yoin 和第一次 prepare 同一批写模式，可能碰到这个时序。`MusicKitPlayModes` 记住 Yoin 要的模式，每次状态变化时 MusicKit 和它不一致、又能写，就再写一次。
- Yoin 自己开播的队列（任何后端）开播后套用用户选的模式；在 Spotify App 等别处开始的播放只观察、不写。模式不跨进程保存。
- 详情页的「Shuffle play」是把列表本身打乱，不碰播放模式。顺序「Play」也一样：播放模式是记住的，开着随机时点 Play 仍然随机播放；点 Shuffle 后 Now Playing 的随机图标照旧显示记住的模式（Subsonic / Apple Music / Spotify 歌手页）。只有 Spotify 的专辑、歌单、Liked Songs 点 Shuffle 时会把 Spotify 的随机打开，因为那时由 Spotify 自己按上下文洗牌（2026-10-21 owner 确认维持现状，Gemini 审查 2.2.1）。

**`docs/icons/`**：第一轮图标草稿，已被 yoin-symbols 取代，留着存档，不要删。

### 实验性 API 策略

- **核心 UI 框架用稳定版 M3**（Material3 `1.4.x` stable）— 主题、基础组件、Navigation
- **Expressive 组件按需 opt-in** — 用 `@OptIn(ExperimentalMaterial3ExpressiveApi::class)` 标注使用处，限定在特定 Composable 内
- **Motion Physics 可全局采用** — Spring 动画本身是 Compose Foundation 的稳定能力
- **Shape Morph** — 通过 `androidx.graphics:graphics-shapes`（stable 1.0.x）实现，不依赖实验性 API

---

## 技术栈

> 各端具体引擎、安全存储与能力矩阵见 [platforms.md](platforms.md)。以下以主仓 Android 为基线并标注跨平台核心：

| 层级 | 选型（Android 主线 + 跨平台核心） |
| --- | --- |
| 跨平台核心（`:shared` on `main`） | KMP `:shared`（MCU `ColorSpec2025`、`YoinTokens`、Ktor `SubsonicClient`、歌词解析/匹配、Google Drive `CloudSyncCore`、`PlaylistSnapshots`/`TrackMatcher`）；HarmonyOS 在 `harmony/lib/` 做 Dart 对齐移植 |
| 语言与 UI | Android：Kotlin + Jetpack Compose（Material 3 + Expressive opt-in，Shell 单 Activity + PushPage 分页 Activity）；iOS/macOS：Swift + SwiftUI + Metal Shader；HarmonyOS：Flutter (Dart) + ArkTS 原生插件 |
| 图标系统 | 统一复用兄弟仓 `../yoin-symbols`（Android Gradle `includeBuild` 编译为 `YoinSymbols.*`，Apple 编译为 SF Symbolsets，应用内不自绘标准 UI 图标） |
| 音频播放与路由 | Android：Media3 `ExoPlayer`、`SpotifyAppRemotePlayer`、`AppleMusicMedia3Player`（MusicKit Android SDK）、Google Cast SDK、LAN DLNA；Apple：`AVQueuePlayer` + `SPTAppRemote`/Connect + `MusicKit` + AirPlay；HarmonyOS：`EnginePlayer`/`AVSession` + MusicKit JS/API + `AVCastPickerHelper`/DLNA（无 Spotify） |
| 缓存与凭据安全 | Media3 `SimpleCache`（~500 MiB）/ `CachedAudio`；Android 使用 `AndroidKeyStore` AES-GCM 存 `noBackupFilesDir/profile_credentials/` 与 `ai_provider.json`（注：Gemini Key 当前存于 Room `gemini_config`）；Apple 用 Keychain；HarmonyOS 用系统 `SecretStore` |
| 形状与取色 | `androidx.graphics:graphics-shapes` + MCU `ColorSpec2025` / `:shared` `Palette`（带过滤 16 色主色板 + `AlbumSpectrum`） |
| 本地数据与云同步 | Room（`profileId` + `provider` 复合隔离）/ Swift / SQLite + 可选 Google Drive `appDataFolder`（`drive.appdata`，HTTPS 传输，非端到端加密） |

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
- **详情页 ▾ 菜单的功能（2026-10-05 owner F1「split button 里那些功能的实装」）**：Shuffle play 之后依次是 Play next（Spotify 不显示——它只有一个队列，本来就排在 context 前面）、Add to queue、Add to playlist（只在服务能写歌单时出现，打开 NP 的加入歌单面板）、Open in Spotify / Open in Apple Music（有公开链接时），再后面是可被拿出来的 Go to artist / Share。专辑页对整张专辑、艺人页对艺人全部曲目（艺人页不给 Add to playlist）。Share 发「标题 + 链接」，链接来自 `MusicMetadata.webUrl`（Spotify open.spotify.com；Apple Music 只对目录 id，带账号 storefront；Subsonic 没有，只发标题）。入队后 snackbar 一行「Added 12 songs to the queue」/「12 songs will play next」；Apple Music 里 MusicKit 播不了的歌（导入的、店面里没有目录版本的）不进队列，也不算进这个数，整页都是这种歌时同样回一行「Added 0 songs to the queue」，不再点了没反应（2026-10-12 真机 PLY-30）。窗口页和 Wide 详情列共用 `ui/detail/DetailMenu.kt`
- **歌曲长按面板（2026-10-17 owner「上线冲刺」，参考 Spotify 的歌曲长按面板；owner 当天改口：从底部升起的 bottom sheet，不用下拉菜单）**：正在播放长按封面（四种布局：手机、双栏、横屏、桌面模式都一样；点封面照旧是聚焦）和专辑页长按一首歌，从底部升起同一个面板 `ui/detail/TrackMenu.kt` 的 `TrackMenuSheet`（基于 `YoinModalBottomSheet`，所以 Wide 详情列里系统返回也能关它；预测性返回的收缩、遮罩、下拉关闭都是 M3 自带的）。顶上是这首歌：56dp 封面、歌名、艺术家。下面按组排，组内是和设置页一样的分段行（外角 20dp、内角 4dp、行间 2dp，组间 12dp）：第一组「下一首播放 / 加入队列 / 加入播放列表」（就是详情页 ▾ 那几行，只对这一首；Spotify 不显示下一首播放，Apple Music 播不了的导入歌曲不给入队，能写歌单的服务才有加入播放列表，snackbar 一行同 ▾）；第二组「前往专辑 / 在剪贴簿里查看 / 前往艺术家 / 在 Spotify 或 Apple Music 中打开」（专辑页里没有前往专辑——它就是这张专辑；「在剪贴簿里查看」在专辑页上直接翻到第二页剪贴簿，把这首歌的卡片滚进视野并轻轻放大一下；在正在播放里则留一个 10 秒内有效的 `ScrapbookFocusRequest`，再走前往专辑，列着这首歌的专辑页接到请求后翻到第二页（owner 2026-10-17 追加）；分享发「歌名 — 艺术家」加链接；打开链接要等 `webUrl` 解析回来才出现）；第三组只有正在播放有：「屏幕常亮」，整行是一个开关（`YoinSymbols.LightMode`，右边 M3 Switch，切换时面板不收）。点一行立刻执行，同时面板滑下去。专辑页原来的长按展开笔记删掉了——笔记都在第二页剪贴簿里管。
- **歌曲面板的顶排和迷你播放器长按（2026-11-03 owner，参考 Apple Music 长按歌曲的「加入 / 喜爱 / 分享」）**：歌曲头下面加一排 MD3 Expressive 连接按钮组（`ButtonGroup` + `ToggleButton`，外角圆、内角紧；按住的那颗用 `animateWidth` 变宽、两边让位，按下时形状收紧；选中的变成胶囊、`primaryContainer` 色，心形用 `rememberFavoriteSymbolPainter` 跳一下）。放上去的标准：对这首歌本身、点一下就生效、不用再选、常用、名字短。专辑和列表里长按：「下一首播放 / 加入队列 / 收藏 / 分享」；正在播放的这首（**长按底栏迷你播放器**、正在播放长按封面）：「收藏 / 加入播放列表 / 分享」——这首正在播，不给入队，空位给加入播放列表。下一首播放、加入队列、收藏点了面板不收（结果看 snackbar 和按钮本身）；分享、加入播放列表点了照旧收起再去下一步。收藏是新加的（服务有收藏能力才出现，乐观翻转，失败翻回并给一行 snackbar）。顶排凑不满两颗时并回下面的列表。分享从第二组挪到顶排，其余行不变。迷你播放器是 `FilledTonalButton`，本身没有长按，`NowPlayingPill` 在 Initial 阶段看手势：按住超过长按时间且没移动就打开面板，并吃掉后面的事件，所以按钮不会再触发点按；TalkBack 有「歌曲选项」长按动作。实现：`TrackMenuSheet(nowPlaying = true)`，shell 在 `YoinNavHost` 里挂。
- **屏幕常亮（2026-10-17，测试者反馈）**：在封面长按菜单里开关，存在本机（`yoin_ui_preferences` / `now_playing_keep_screen_on`），默认关。打开后，正在播放展开、而且在放歌时，这个窗口不灭屏（宿主 view 的 `keepScreenOn`）；暂停就恢复系统的灭屏时间，免得忘了关的平板亮一整夜。实现 `ui/nowplaying/NowPlayingKeepScreenOn.kt`，由 `NowPlayingOverlayHost` 套用，所以 shell 和每个详情页窗口都一样。
- **设置 › 关于（2026-10-17）**：欢迎引导、官网、使用文档、发送反馈、隐私政策、版本。官网和文档按 app 的语言打开英文页或 `/zh/` 页；隐私政策只有英文页（`https://yoin.gojyuplus.com/privacy`），本来就在官网上。链接常量在 `ui/settings/YoinLinks.kt`。
- **不可播放专辑的队列入口（2026-10-12 owner 拍板，FXAP-5）**：整张专辑没有可播放曲目时，隐藏「加入队列」和「下一首播放」；混有可播放曲目时只把可播放的部分加入队列。推入页和分栏共用同一规则，不显示「已加入 0 首」。

### 🌱 首次引导（Landing，2026-10-09 定稿，原型 artifact PQQMmjTG9SHaoESTaWftEG v3）

- **什么时候出现**：首次启动、还没有任何账号时（启动画面会等到判断完成，老用户永远不会看到它闪一下）。之后删光账号也**不会**回来；设置 › 关于 › 重新引导 可以再跑一遍（已连接的账号保留）。实现：`ui/landing/`，`LandingHost` 由 MainActivity 盖在 shell 之上（同一个窗口），`yoin_ui_hints/landing_done_v1` 记录是否走过。
- **角色**：启动图标的三片拆开当角色（`landing_mascot_*`，几何与 `ic_yoin_launcher_foreground` 一致），粉 = 左手、藏青 = 右手，从中心交汇处挥手；**没有眼睛**（owner 10-09）。入场三片从三个方向落位后挥手两下，每次挥到顶一拍 CLOCK_TICK；连接成功举手蹦一下，输错摇头，选中服务点头。气泡尾巴指着它，换句话时从尾巴尖弹出；气泡标题用 GSF 圆体（ROND 100）。
- **导航**：底部浮动胶囊 = shell 底栏的孪生（同高、同圆角、同色），里面是返回、步骤点、主按钮；内容放在从胶囊里长出来的浮动窗里，Yoin 站在窗顶（平板横屏：Yoin 在左列，窗在右列）。“进入 Yoin”时浮动窗缩回胶囊、胶囊变成首页底栏、Yoin 挥手后飞到左上角淡出，首页按第“主页内容”一步的选择出现。不加阴影。键盘升起时浮动窗站到键盘上（胶囊留在键盘后面），点连接按钮先收起键盘；从悬浮窗回来自动填好 Client ID 时不弹键盘。平板横屏双栏时，左栏的 Yoin 和气泡按键盘上方剩下的高度缩小，不被键盘盖住。
- **步骤**：你好 → 选服务（可多选）→ Yoin 负责 / 不负责 / 你需要 → 每个所选服务一页连接（选了 Spotify 时，它前面多一页“在用 Spotoolfy 吗？”） → 主页内容（有账号才出现）→ 顶部边缘 → 完成。首次启动选服务至少选一个；已经有账号时（重新引导）一个不选也能“跳过”，直接去后面几步。
  - 连接页复用设置的 `ServiceSetupViewModel` / `AppleMusicValidationViewModel`。Subsonic 的“连接” = 先连上服务器再保存。Apple Music 两行单选：默认 Yoin 的令牌服务（`YoinTokenService.URL`，不显示、不用复制），选“你自己的令牌服务”才出现输入框，写明 Apple 开发者计划每年 US$99；不做价目卡。
  - **在用 Spotoolfy 吗？**（2026-10-15，`LandingSpotoolfyStep.kt`，迁移方案 `docs/spotoolfy-migration.md` §5）：两行单选，形态同 Apple Music 连接页。“在用，带上我的数据”：主按钮“打开 Spotoolfy”，拉起 Spotoolfy 和它的悬浮窗；没装 Spotoolfy 时换成“选择导出文件”。文件从分享菜单的“导入到 Yoin”（`SpotoolfyShareReceiverActivity`）或文件选择器进来，读完自动打开回顾页，角色举手蹦一下；读不了就摇头并说原因。收到后这一页显示“已收到 N 条笔记，连上 Spotify 后放进你的 Yoin”，附“再看一次回顾”“换一个文件”。数据只是暂存：Spotify 连接成功的那一刻才写进刚加的账号（只写进还没有任何笔记、评分的账号），连接页的成功状态下多一行“已把 N 条笔记放进〈账号〉”。没连 Spotify 就点“进入 Yoin”时先问一句：“回去连接”或“不要这些数据”。
  - **悬浮窗导航**（`ConnectGuideActivity`，画中画小窗，不用“显示在其他应用上层”权限；缩小前那半秒是居中的 4:3 卡片，系统缩小动画从卡片开始（`sourceRectHint`））：
    - Spotify：浮在浏览器（Spotify for Developers）上，像地图导航一样一步说一件事：Create app → 名称 → 两个回调地址 → 勾 Web API 和 Android → **Android packages 里填包名 → SHA1 指纹，点 Add**（owner 10-10：没有这两项 App Remote 不认）→ 同意条款 Save → 复制 Client ID。单证书仍是 9 步；有多张相关证书时，为每张重复“包名 → 指纹”两步，每步只复制对应的一项。点窗口出现系统按钮，每步都是固定三格“上一步 / 复制 / 下一步”，用不上的格子由系统灰显占位（第一步的上一步、没东西可复制的复制），› 永远在同一个位置；最后一步中间格换成“回到 Yoin”，› 灰显（owner 10-11：快点不跑位，也不会误关引导；`guideActionSlots`）。包名和 SHA1 自动读取当前安装包，不询问下载来源、不硬编码渠道证书：单签名读取包含当前证书的完整历史，真正多签名读取全部当前证书，去重后保留顺序（`signingCertificateSha1s`，冒号分隔大写；owner 10-13）。悬浮窗保持原来的说明文字、布局和复制按钮，不显示长串指纹；复制按钮只复制当前步骤对应的指纹，重建时保留证书快照和步骤。回到 Yoin 时读剪贴板里的 32 位 Client ID 自动填上。页面上“后台要填的”折叠区也列出回调地址、包名及所有相关 SHA1，每个指纹沿用原来的单行横滚样式及复制按钮，不新增编号标题、不调整按钮或布局，给在电脑上操作的人；读取失败时悬浮窗显示明确状态，不提供空指纹的复制动作。
    - Apple Music（owner 10-10）：点“连接”先让小窗缩进角落，再在它下面打开 Apple 的登录页；小窗写“用 Apple ID 登录，允许 Yoin 访问 / 可能要试两次或更多次”。一次没成功（取消或失败）就变成“还没成功，回 Yoin 再点一次连接”，连上了或离开这一步就自动关。没有订阅的失败直接关（再试也没用）。小窗菜单给三个空白、点不了、读屏也不念的占位（没有标签）：空列表会让系统换成 Yoin 自己的播放按钮（⏭ 会切歌）。Settings 里的 Apple Music 连接用同一个（`AppleMusicSignInWithGuide`）。
    - Spotoolfy（D10）：浮在 Spotoolfy 上，用 Spotoolfy 图标的青绿色（浅色底 #DDFFFF、强调 #4FA6B0、深字 #0B3438；深色底 #12464C、强调 #8FD3DB、浅字 #DDFFFF；左上角是 Spotoolfy 的图标），和 Yoin 自己的紫色小窗一眼分开。共 4 步：点右上角 ⚙ → 打开 Spotify API，长按客户端 ID 复制（框是空的就跳过）→ 数据管理里点导出数据 → 分享菜单里选“导入到 Yoin”。三格同 Spotify，每步的复制格都灰显（复制在 Spotoolfy 里做），最后一步中间格是“回到 Yoin”。文件一到小窗就关；回到 Yoin 时读剪贴板里的 Client ID，Spotify 连接页直接填好（已经有用户自己的 Client ID 时不覆盖）。
    - 小窗怎么收尾（2026-10-12 真机 QA）：Android 17 的 ✕ 只把小窗挪到后面停住，Yoin 把这当作结束引导（不拉起 Yoin），下次打开从第一步来；关屏、锁屏时系统也会停住小窗，这不算 ✕，引导留着（只有亮屏且解锁时停住才算关掉）。小窗还浮着时再点“打开 Spotify for Developers”，只在它下面重开网页，停在原来那步（再启动一次小窗会被系统拉成全屏）；留在后面没浮着的小窗被再次打开时从第一步重来。“回到 Yoin”是让系统先把小窗展开再回 Yoin，和系统的展开键一样：Android 17 不把只剩小窗的应用算作可见，直接从小窗拉起 Yoin 会被拦（`GuideSession`）。Settings › Apple Music 的按钮失败后仍叫“连接 Apple Music”/“重新连接 Apple Music”，不改成“重试”，和小窗的“再点一次连接”一致。
  - 主页内容：`HomeSection` 全部区块，开关 + 拖把手排序（触感同主页编辑态 §F）；行数不放进引导，列表下一行小字说明去主页长按调。结束时写进本次新加账号（重新引导时是当前账号）的 `home_layout`。
  - 顶部边缘：三种样式并排，每个是**真的**滚动边缘效果的小图（`ScrollEdgePreview`，`seamDissolveViewport(style = …)` 只给预览用）；下面一句说明，说明框按三句里最长的那句定高，切换不跳（owner 10-09）。选中立即生效。
- **返回**：分步流程（新类型，见 predictive-back skill）：第一步首次启动不拦截（系统回桌面），重新引导时第一步返回 = 关掉引导；其它步返回上一步。手势只驱动当前步的 AOSP 预览姿态（缩向 0.9），松手提交后再按弹簧换步，不擦洗换步本身。
- **Spotoolfy 回顾**（2026-10-15，`ui/spotoolfy/`，原型 artifact PohjTmhwmz3MTkehDUu5it，迁移方案 §6）：独立 Activity，原生跨 Activity 返回（Pattern A），左上角关闭。两页竖向翻：第 1 页专辑封面从中心炸开成墙、笔记数从 0 数上来，下面一句“写给 K 位艺人的 M 首歌，每一首都打了分”（不用“·”拼数字）；第 2 页笔记关键词从中心炸开螺旋排布，最多两段歌词片段飞到左上、右下角，点词或歌词从底部升起卡片（点空白处收起），最后底部卡片升起：引导里是“带着它们，加入 Yoin”和“加入 Yoin”（直接去 Spotify 连接页），从设置打开是“完成”。笔记少于 20 条或关键词少于 8 个时，关键词换成笔记卡片。触感只在两次炸开时各一拍 CLOCK_TICK；减少动态效果时直接淡入最终布局。

### 🏠 主页

- Mix / 推荐区块（从 Navidrome 获取随机专辑、最近添加等；「最常播放」排序依赖 Scrobble，MVP 阶段使用服务端已有数据）
- 辅助可视化区域（当有曲目播放时，显示实时音频可视化效果）
- Memory teaser 只显示一条轻提示，点入 shell-owned Memories surface；Feed 不承载重型 Memory 卡片流
- **气泡什么时候说、什么时候走（2026-10-11 owner 拍板，2026-10-17「上线冲刺」改节奏、加提示）**：写下新的评分、乐评或笔记后，回到首页说一次（新内容），完全展开、弹簧停稳后停 6 秒。除此之外，首页看得见、**4–5 秒没人碰就说**，说完停 **2–3 秒**飞回箭头，再没人碰又过 4–5 秒再说——每次的等待和停留都在区间里随机取，像脉冲不像节拍器；不再是「每次打开 app 最多一次」。闲聊有 35% 的概率说完不走、原地「翻译」成下一句（旧的一行往上滑出、新的一行从下面升起，位移走空间弹簧、透明度走效果弹簧，底色跟着新内容的封面色过渡），再停一轮才走，每轮最多翻一次。闲聊的内容是 Memories 和提示轮流：有记忆就记忆、提示交替（记忆按 `recall` 顺序轮，提示按 `HomeBubbleTip` 的顺序轮，轮完一圈才重复），没有记忆就只说提示。提示一条只讲一件事：一个藏起来的手势（长按首页排版、首页下拉打开 Memories、长按封面打开歌曲面板、长按专辑里的歌、播放模式按钮、笔记钉在那一秒、长按底栏资料库搜索、屏幕常亮在封面菜单里、滚动边缘），或者一个 app 的小细节（名字来自「余韻」）；左边是圆形符号块（记忆是封面），右边一到两行字；**提示是一颗普通胶囊**（owner 批注：不用向上的气泡）——没有尾巴、两端全圆，收的时候折回标题，不飞进箭头；一句记忆原地换成提示（或反过来）时，形状在空间弹簧上过渡。点记忆打开这条记忆；点「首页下拉」那条打开 Memories；点别的提示只是让它飞走。系统无障碍里把「操作时长」调得更长就按系统的来；手指按在气泡上没松开时不走，松开再算。碰屏幕任何地方（包括底栏的播放胶囊和 Home / Library 按钮）、滚动、下拉 Memories、打开 Now Playing 或详情、编辑首页、别的窗口挡在前面（账号卡片、通知栏）都会提前飞走，闲置计时也从头算；app 退到后台（STOPPED）时悄悄复位。规则是纯函数 `MemoryBubbleTurn.after`，内容在 `ui/home/HomeBubbleSay.kt`，实现在 `ui/home/HomeMemoryBubble.kt`。
- 右上角 ⚙️ 设置入口
- **版块与布局（0.6.3+ 完整目录 `HomeSection`）**：完整目录按默认顺序包含 10 个区段：`activities`（动态，支持 S/M/L/XL 行数档位）、`jump_back_in`（接着听，支持行数档位）、`recently_added`（最近添加）、`rediscover`（重温，`appendEnabled = false`）、`recently_played`（最近播放）、`your_playlists`（你的播放列表）、`other_profiles`（**在你的其他资料里**）、`lyric_highlight`（**歌词卡片**，支持 1–4 张档位点选）、`memory_grid`（**全部 Memories** 日历网格）、`made_for_you`（**为你推荐**，iOS 端排期中）。顺序和开关按 profile 存（`home_layout`）。只在真的改变时写入；改回默认就删掉这一行，等于「从未定制」。定制过的用户遇到全新实验区段时按 `appendEnabled` 追加，无数据的区段自动隐藏。
- **「为你推荐」（`made_for_you`）**：聚合 Spotify（daylist、Discover Weekly、Daily Mix、Blend 等；Web API 404 的官方编辑歌单在 Android 上连同 App Remote `SpotifyContentBrowser` 补读，最多 200 条）与 Apple Music（`v1/me/recommendations` 的 `pl.pm-…` 个人混合歌单）。不支持或无数据的音源自动不渲染。实现 `ui/home/HomeMadeForYou.kt`（HarmonyOS `home_made_for_you.dart`）。
- **「在你的其他资料里」（`other_profiles`）**：展示用户其他已配置音乐账号最近 90 天最常听的专辑与歌单（不混入当前账号）。点按卡片会**先切换至对应账号，再立即起播**该内容。单账号用户自动隐藏。实现 `ui/home/HomeOtherProfiles.kt`（iOS `HomeOtherAccounts.swift`、HarmonyOS `home_other_profiles.dart`）。
- **Spotify Elsewhere 胶囊**：拥有多个 Spotify 账号且当前本机未在播放时，首页回到前台会轮询其他 Spotify 账号是否在别处播放，若命中则在首页页头展示 5 秒小胶囊（头像/封面 + 曲名与歌手），点按可直接切换至该账号（Android `HomeSpotifyElsewherePill.kt`、iOS `HomeSpotifyElsewhere.swift`）。
- **底栏随滚动隐藏（`ShellBarScrollHide`）**：主页与资料库长列表向下滑动累计超过量程（96dp，或 ≥300dp/s 快速上滑）时，底部导航/迷你播放胶囊向下顺滑让位；回滑、换页、聚焦搜索或进入编辑态时用弹簧拉回。宽屏右栏打开详情列时，底部信息条亦协同上抬避让。
- **就地编辑拖拽排序（`HomeDragEngine`）、捏合列表（`HomeEditList`）**与 **Tips（`HomeBubbleTip`）**：就地编辑拾起区块后原地跟手拖动，其它块让位，靠近上下边缘自动滚动；双指捏合把各区段收成带 3 张真实封面的紧凑列表，同样拖动排序；首页气泡提示当前收束为 Android 34 条、iOS 24 条（iPhone 23 条）、HarmonyOS 37 条（普通手机 36 条、桌面端 34 条）交互与功能演示，每一新功能 PR 必须随附对应的 Tip 更新（`HomeBubbleSay.kt`、`ui/tips/`）。
- **页头：两行问候（2026-10-09 owner 定，原型 artifact GWEBwdavGiwS1ngqmcqTyF）**：上面一行是按时段换的小字问候（titleMedium，次要文字色：5–11 点早上好、11–13 中午好、13–18 下午好、其余晚上好；英文只有 morning / afternoon / evening，整点自动更新），下面一行是 Home（headlineMedium，比原来的 headlineLarge 小一号；手机横屏再小一号 headlineSmall），两行都用 Google Sans Flex 的圆体（ROND 100，Medium）——中文没有这个字形，落到系统字体。编辑模式里问候语跟页头图标一起淡出，Edit Home 照旧盖在 Home 上。**冷启动开场**：Yoin 标志（三臂，tertiary / primary / secondary）在标题位置依次弹开（带回弹），停一拍后转 70° 缩小淡出，问候语和 Home 依次浮上来，约 1.4 秒；每个进程只播一次（切回 Home、转屏不重播）。首页加载时会临时报「动效压力」（AdaptiveReduced），开场最多等 0.4 秒让它解除；一次加载最多报 3 秒（`HOME_LOADING_PRESSURE_MAX_MS`），挂住的加载不会让整个 app 一直停在降级动效；真正的减少动态（低内存、省电）直接显示两行字。实现 `ui/home/HomeGreetingTitle.kt`。
- **账号切换器（2026-10-09 owner 定，参照 Play 商店的 Android 账号卡片）**：页头右上角的设置齿轮换成当前账号的头像（Settings 的 AccountAvatar，32dp；还没有账号时仍是齿轮）。点开是一张卡片（自己的 dialog 窗口，宽 min(屏宽 − 32, 420)，屏幕正中（owner 10-09 改：不放顶部）。**头像长成卡片**：一个空间弹簧 p 驱动卡片底色从页头头像的位置和圆度长到卡片的矩形（28dp 圆角），头像同时飞到卡片里 72dp 大头像的位置并放大，内容在 p 0.45–0.95 淡入，遮罩（scrim 32%）跟同一个 p 渐变；收起原路缩回头像，期间页头头像隐藏。弹窗窗口自己的变暗和进出场动画关掉，等卡片排好版、窗口画出两帧后才起弹簧（否则窗口出现前弹簧已跑完）。头像按钮不加波纹，变形本身就是反馈。减少动态：原地淡入淡出）：顶行是当前账号的服务行和关闭 ×，下面大头像（72dp）、账号名、「管理账号」描边按钮（进设置）；分段组一：其他账号（头像 + 名字 + 服务行，不可用的账号显示原因，点了进设置）和「添加账号」（进设置）；分段组二：「设置」和「编辑首页」。点其他账号即切换并收起卡片。卡片数据和设置页同一个映射（`ui/settings/ProfileCards.kt` 的 `profileCardsFlow`），两边永远一致。返回：卡片是自己的窗口（`DialogWindowPredictiveBack` 接这个窗口自己的返回），系统返回只关卡片，首页的返回桌面不受影响；预测性返回时卡片做 AOSP 预览姿态（缩向 0.9、偏向手势一侧、跟手），不擦洗变形，提交才缩回头像；点卡片外也关。实现 `ui/home/HomeAccountSwitcher.kt`。
- **账号需要重新连接（2026-10-11 owner 拍板）**：当前账号的凭据读不出来（换机恢复没带上、密钥解不开），或 Spotify 收回了授权（invalid_grant、缺必需 scope）时，页头头像右上角挂一个小红点（M3 小徽章 6dp、error 色、1dp 页头色描边，不写字，缩放走空间弹簧、透明度走效果弹簧，读屏念「账号需要重新连接」）。红点长在头像上：卡片打开或切换的替身接管头像时，它和头像在同一帧隐藏，头像回来时原样回来；只有账号自己的状态变化才让它弹簧进出。卡片里「管理账号」前多一个「重新连接」tonal 按钮：点了先让 ProfileManager 对当前账号再读一次凭据、建一次 source、ping 一次（不切换账号，在后台线程做，期间按钮文字换成加载指示、卡片其余入口不响应）。只有服务**拒绝凭据**才算没修好（Subsonic 错误码 40–44 或 HTTP 401/403、Spotify invalid_grant 或 401/403、Apple Music 没有用户令牌或 403）；服务器没应答（离线、超时、重启中、5xx）照样发布 source，和冷启动一致，由首页和资料库自己报网络错误，不把用户送去重填一套本来正确的凭据。发布后按切换账号的方式通知一次（`musicConfigurationRevision`），资料库、Memories、桌面小组件跟着重读，首页按自己的 scope 只加载一次。成功：卡片沿 T7 的弹簧缩回头像，头像上已经没有红点；首页在快照上逐块替换，没有快照时（换机恢复）在没有 source 时读出的那一版上逐块拼接，两种情况都不闪加载样式。没修好：等卡片缩回头像，再打开这个账号的设置页（`ServiceSetupActivity` 编辑模式：Subsonic 重填、Spotify 重新授权、Apple Music 重新登录）。信号只有一个 `ProfileManager.activeNeedsReconnect`，规则 `ProfileCredentials?.needsReconnect()` 和设置页账号卡的 Reconnect / Credentials missing 同源；只读本地状态，正常情况下不多发请求、不轮询，也不加下拉或上拉刷新。**App Remote 的授权失败不算**（`SpotifyProviderStatus.AuthFailure`：没登录 Spotify 应用、没在 Spotify 应用里允许 Yoin、它的认证失败）：Yoin 自己重新授权修不好，而且它粘滞到重启，算进来就是一个消不掉的红点；所以首页不挂红点，设置页账号卡、Spotify 设置页和首页卡片里的账号行都标「登录 Spotify 应用」（Sign in to Spotify app），不叫「重新连接」。账号卡读凭据放在后台线程，Apple Music 的卡不读。
- **加载顺序与切换账号（2026-10-10 owner 拍板，P2 分层发布 + Q14b）**：首页不再等所有区块到齐才出。本地层先出：页头、Activities 动态、任意年龄的 Jump Back In 候选池（过期的随后轮换）、笔记 1×2。记忆信号（记忆胶囊、JBI 的记忆 1×2、Rediscover）和网络区块（Recently Added、Recently Played、Your Playlists、hero 脚注、Spotify 的 recently-played）到了各自拼进同一页：区块出现走 `animateItem` 的淡入和位移弹簧，hero 卡的脚注行用空间弹簧展开并淡入，整页入场不重播。某个区块读取失败就保留它上一次的内容，读到空才清空——本地读取也一样：候选池读不出来时 JBI 的封面原样留着、这次不轮换，笔记 1×2 和 Rediscover 的单曲读不出来也留着上一次的；隐藏的区块照旧加载。Spotify 的 Activities 以 recently-played 为准：本机既没有动态记录也没有候选池时，转圈等它回来再出，不先亮一张空卡；本地层先出的 hero 不读脚注，等 recently-played 回来、hero 定了再读（只读最终那张专辑，不为马上被换掉的 hero 多发一次请求）。**切换账号**：从点下账号起，到新账号第一版内容出现前，首页显示现有的加载样式（`YoinLoadingIndicator`，不加文字），旧内容按入场的弹簧反向淡出；新账号有进程内缓存时提交后立刻出现。切换失败就回到原账号原样的内容（不重新加载）：滚动位置按账号留着（每版内容带着自己账号的 id，列表状态按它保存），旧内容按入场的弹簧淡回来；换到别的账号则从顶部开始，并丢掉上一个账号留下的状态。从首页发起的切换另在 shell 的 snackbar 提示「无法切换账号」（设置页发起的由设置页自己提示）。账号切换的 Loading 不上报动效压力，从点下账号到新账号首版内容出现（或失败回退）都不因此把全 app 切到 AdaptiveReduced；冷启动加载保留原来的限时压力上报（2026-10-12 owner 拍板）。卡片按打开时的动效档位走完收起，中途不跟着切档。实现 `HomeViewModel`（`HomeLoad`、`observeSwitching`）、`HomeScreen`、`HomeAccountSwitcher`。
- **从首页切换账号的即时反馈（2026-10-11 owner 拍板，T7）**：全部走弹簧，不加说明文字。点下账号的同一帧，目标就挂起（`AccountSwitcherViewModel.pendingTarget`），被点的那一行立刻成为 Settings 的选中段（`SelectableSegment`：四角全圆、脱离相邻行），底色填这个账号的服务 container 色，也就是设置里在用卡片的颜色（见颜色系统第 5 条），不算普通行着色；其它账号行用 effects spring 淡到 38% 并停止响应。切换进行中和卡片收起途中，卡片上其余入口（管理账号、添加账号、设置、编辑首页、关闭）都不再响应。选中段的 effects spring 走到 90%（约 0.1 秒）后卡片收起：被点那一行的 40dp 头像沿卡片同一个空间弹簧 p 飞进页头头像槽（32dp），旧账号的大头像随卡片内容一起淡出；落点在起飞时记下，页头在飞行途中挪了位置（新内容进来）就用空间弹簧追过去，窗口等它到位再移除。飞行和替身都按窗口绝对坐标摆放，从右到左的语言下不镜像。页头随 feed 进入 Loading 期间，首页在页头头像的位置放一个替身，显示目标账号（`HomeAccountAvatarHold`）；页头头像的位置按它实际绘制的范围算，不按列表裁剪后的可见部分算。切换失败时替身用同一组弹簧换回原账号，原账号的内容按原滚动位置淡回，并弹 snackbar「无法切换账号」；切换被拒（另一个切换正在进行、账号已被删除）也弹同一条。新内容完全进场、替身停在页头头像上之后交还：页头自己的头像先在替身下面出现，替身再用 effects spring 淡出，页头头像正被顶部潮线淡化或半滚出视口时也不跳；交还之前内容就被拖动，或有东西盖住首页，立即交还，替身不去追。Loading 期间窗口尺寸变了（转屏、展开折叠屏、桌面窗口缩放）而页头不在，替身用 effects spring 淡出，页头头像随新内容一起淡入。减少动态下没有飞行：卡片原地淡出，窗口移除后替身用 effects spring 淡入。加载指示器的入场和退场都是 effects 淡入淡出加空间弹簧缩放。实现 `ui/home/HomeAccountSwitcher.kt`（`HomeAccountSwitcherHost`、`HomeAccountAvatarHold`）、`HomeScreen.kt`。
- **Spotify Activities 的艺人头像（2026-10-10 owner 拍板，Q16）**：recently-played 里的艺人不带图。发出 fresh 列表之前，先用本机已有的头像补上：`spotify_home_artist_cache` 里 30 天内的答案、库缓存里**关注的**艺人（库缓存里从收藏专辑和 Liked 歌曲推导出来、没关注的艺人带的是专辑封面，不算头像，照常去问）、打开艺人页时记下的头像、磁盘上的艺人详情；仍然没有的，先用这次播放的专辑封面兜底（和艺人详情页一样）。这一轮加载完、首页**静止** 1.5 秒后，才为 bento 带图卡片上的艺人（文字条带只有字，不算；平板 XL 最多 20 个）逐个请求 `GET /artists/{id}`。静止 = 首页在前台（resumed）、没有 Now Playing / 详情栏 / Memories 盖着、列表没在滚动；切到 Library、打开详情页、进后台都不算。请求中途首页不再静止，下一个请求就等它重新静止 1.5 秒再发。每个请求都过 `SpotifyRateLimitGate`；gate 关着或遇到第一个 429 就停，遇到 429 之后本进程不再为这个账号请求。答案存进 `spotify_home_artist_cache`：有图存 30 天，确认没图存 24 小时，网络错误不存。补到的头像经 `ExpressiveMediaArtwork` 用 effects spring 渐显换上；卡片底色从专辑封面的颜色直接弹到人像的颜色，不经主题色（`rememberExpressiveBackdropColors(holdUntilResolved = true)`）。由 `ServiceFeatures.activityArtistPortraits` 门控，只有 Spotify 打开；Subsonic 和 Apple Music 的 Activities 来自本地记录（自带封面），不走这条路。实现 `SpotifyActivityArtistArtwork`、`HomeViewModel.fillActivityPortraits`。
- **久别重逢 Rediscover（2026-10-04；2026-10-05 去掉 8 分门槛）**：有过记忆的专辑（专辑评分、专辑乐评、笔记、任一曲目评分，只播放过不算），且在 Yoin 里 90 天以上没播放过；少于 4 首的专辑不以专辑卡出现（复用 Memory 的 `meetsMemoryTrackCount`，`MEMORY_MIN_TRACK_COUNT` = 4：已是 Memory 的直接通过，曲目数还没加载到（0）时不拦），单曲和短 EP 以单曲卡回来（见下条）。有分的在前、高分在前，没分的排在所有有分的后面，同分时久别在前。分数 = 专辑评分；没有时用曲目平均分，须 ≥ 60% 曲目有评分，否则不算分。有分时封面右下角挂评分徽章（专辑评分 = 实心色块，曲目平均 = 描线，同 Memories 印章语法；色取封面调色板，暗色 accent、亮色 base，等宽数字），eyebrow 只写 "Not played in Yoin for 7 months"；没分时 eyebrow 先写记下了什么（"Reviewed" / "3 notes" / "2 tracks rated"）。同一排同类卡片的 eyebrow 按全排最高的那条留行（最多两行，短的贴着标题），标题、歌手、脚注在一排里对齐，文案不删不缩（2026-10-05 真机 QA）。时间只看播放历史，浏览不算，所以文案写 "in Yoin"。和 JBI 的 memory 卡、记忆胶囊、Recently Added 载入的专辑去重（Recently Added 按宽度只摆 12–20 张，ViewModel 不知宽度，载入的全算；2026-10-05 真机 QA），本次会话里播放过的专辑离开货架。点按进专辑详情，不进 Memories。所有宽度都是横滑货架：N ≤ 2 最多 6 张，N 3–4 和手机横屏 2 张，N ≥ 5 3 张。没有数据就不渲染，编辑态显示占位说明 "Albums and songs you rated or wrote about come back here when it's been a while"（2026-10-05 加入单曲后的文案）。
- **Rediscover 单曲卡（2026-10-05，owner「rediscover 本身也可以加入一些歌曲」）**：评过分（> 0）或写过非空笔记、在 Yoin 里播放过但最近一次已是 ≥ 90 天前（只看 play_history）的曲目也回来（`PlayHistoryDao.getRediscoverSongs`，按 profile + provider，每次最多读 64 首）；分数 = 曲目评分。专辑卡和单曲卡按同一个顺序交错排（有分在前、分高在前、同分久别在前，完全相同时专辑在前，`selectRediscoverShelf`），货架总数不变；专辑已作为专辑卡上了货架的单曲不出现。单曲和 JBI 的笔记 1×2 卡、Recently Added 的曲目去重，所在专辑已在 Recently Added 里的单曲也不出现（单曲卡用的是专辑封面和专辑名；2026-10-05 真机复验）；本次会话里播放过这首、或播放过它所在的专辑，卡片离开货架。卡片是专辑卡的小一号：Circle 形底（Home 的实体形状映射）76dp、内边距 12dp、卡高 116dp（专辑卡是 104dp 底、132dp 高），在货架里垂直居中；有分时封面角挂实心评分徽章（自己的曲目评分）；只有笔记时显示一行最新笔记的摘录，左侧是曲目笔记专用的 2dp 竖线；第二行 "歌手 · 专辑"。点按只播放这一首（Track 从播放历史重建），不震，TalkBack 点按标签 "Play"，徽章读作 "Your rating 9.0"。实现 `data/memory/RediscoverSelection.kt`、`ui/home/RediscoverSection.kt`。
- **Rediscover 的两个新专辑来源（2026-10-05）**：单曲笔记和单曲评分本身不带专辑，经这首歌的 play_history 归到专辑（`SongNoteDao.getNotedAlbumAggregates`、`LocalRatingDao.getRatedAlbumAggregates`，按 profile + provider），在 Memory 的 48 个种子扫描范围之外按信号数（同数按最近写入）取前 16 张，接在原有候选之后。只在 Home 构建 Rediscover 池（`includeIneligible = true`）时查询；这些专辑带 `inMemoryScan = false`，**只进 Rediscover，永远不会成为 Memory**（`build(n, true).memoryEligible(n) == build(n)` 不变）。 2026-10-15 起两个聚合查询的专辑来源是 play_history 与 `track_album_links` 的并集：在专辑页打过分、或从 Spotoolfy 带来但没在 Yoin 里播过的歌也能归到专辑（迁移方案 §4.6）。
- **Rediscover 笔记卡（2026-10-15，D11 / D14）**：写过笔记或打过分、但从没在 Yoin 里播过的专辑（多半是从 Spotoolfy 带来的），最近 90 天没写过，也能上架。约占三分之一（6 张里 2 张，至少 1 张），每第三张是笔记卡；一类不够时另一类补满，所以刚迁来的人先看到的全是笔记卡。笔记卡排序：有分的在前、分高的在前，同分时笔记多的在前。卡片不写“多久没听”，眉题只写留下了什么（N 条笔记 / N 首歌打过分），分数照常在封面角上（`selectRediscoverNotes`、`mixRediscoverShelf`）。
- **Recently Added 在平板上多放内容（2026-10-05）**：只加列、不放大卡片，曲目格永远 2 行。平板竖屏 3×2 首曲目 + 16 张专辑，平板横屏 4×2 + 20 张；手机（含手机横屏）不变，仍是 2×2 + 12 张。规则在 `HomeFeedDensity.kt`。
- **专辑封面的记忆印记（2026-10-11 owner 拍板，T4「JBI 往下全是光封面」）**：用户在这张专辑上留过记录（专辑评分、单曲评分的均分、笔记），它的普通封面就在右上角挂一个小记忆印记。范围是 Jump Back In 的普通专辑封面（1×1）、Recently Added 和 Recently Played 的专辑卡；手机和平板同一条规则（手机货架和平板模板用的是同一个封面块）。单曲、歌单、JBI 的两张 1×2 信号卡（本身已带分数）不挂，没有记录的封面保持原样。印记就是记忆胶囊封面上那枚曲奇贴纸（`PillCover`），缩在封面角上：16dp 的页面底色小圆托着 12dp 的 `Cookie12Sided`，圆心压在封面上沿，右缘和封面右缘对齐，整个印记不超出背景形状，滚到页顶溶解时不会被切掉。实心 = 你的专辑评分；描线 = 你的单曲均分（不设覆盖率门槛，封面上不显示数字）；虚线 = 只有笔记。只有乐评、只有播放的专辑都不挂。墨色取封面调色板（暗色用 accent、亮色用 base，同 JBI 分数规则）；调色板还没取到时（启动预热、滚动中、没有封面）改用记忆胶囊自己的兜底墨色（暗色 tertiary、亮色 secondary），不用封面背景的 container 兜底色——那种浅色压在页面底色小圆上几乎看不见；取到后用 effects spring 过渡（颜色不算动画，减少动态下也过渡）。**不写数字、不写字、封面不加描边**；TalkBack 读 "Your rating 9.0, 2 notes" / "Track average 7.4" / "1 note"（复用 Rediscover 的文案）。数据只来自 Home 已有的记忆信号构建（`MemorySignals.pool`，含不合格专辑），按 raw album id 挂上，不新增查询，所以不在这次构建里的专辑（48 个种子和 Rediscover 单曲信号专辑之外）不挂；种子随最近播放轮换，掉出构建的专辑印记也会跟着消失。同一张专辑在构建里出现两次（旧格式 `provider:raw` 的专辑评分行，加上播放记录的 raw 种子）时合并成一枚：种类取强的（专辑评分 > 单曲均分 > 只有笔记），分数跟着这个种类，笔记数取大的。Apple Music 的 Recently Played 读 `/me/recent/played` 时带上 `include[library-albums]=catalog`，资料库专辑拿到它打开时用的目录 id（评分就记在这个 id 下），和 Jump Back In 读资料库专辑一样，不多发请求；Apple 拒绝这个 include 时（400 或 5xx）这一页去掉它重读，回的是 400 时，在重启 app 或切换账号之前都不再带；没能匹配到目录的导入专辑保留资料库 id，印记也按它挂。这个 include 还没在真机上验证过，Apple 若静默忽略它，资料库专辑在 Recently Played 里就不挂印记。信号构建失败时保留上一次的印记；评分、笔记写入后随记忆信号的变更戳一起刷新。印记晚于封面到达时用 effects spring 淡入，种类变化（虚线 → 描线 → 实心）在同一个弹簧里渐变，消失时以原样淡出；减少动态下直接切换。实现在 `ui/home/HomeAlbumMemoryMark.kt`（`HomeUiState.Content.albumMemoryMarks`）。
- **卡片背景色（2026-10-05）**：Home 卡片的 backdrop 色和 Now Playing 用同一个取色器（`CoverSeedExtractor`，带过滤的 16 色），蓝封面不再被肤色 / 赭色带染成土黄；旧的不过滤 12 色只在它什么都取不到时兜底（全棕褐封面、只有近黑近白）。降级状态（`AdaptiveReduced`）下也取色——颜色不算动画，和 Memories 一致；只在启动预热期间和滚动中推迟（每次要解码一张 200px 封面再做 16 色提取），恢复后补读一次，不丢；封面读失败时（5xx、断网）取色跟着封面自己的重试节奏再读（退避 3/15/60 秒、网络恢复、回到前台），封面好了底色也跟着上色，不用等下一次滚动。这条取色路径详情页同样在用（`ui/component/ExpressiveBackdropPalette.kt`）。
- **就地编辑（2026-10-04，P0）**：取代 2026-07-02 的列表编辑器（`HomeLayoutEditor` 已删除）。
  - **进入**：在页面任意处长按，页边也算。按在卡片上，进入编辑并拿起这张卡所在的 section，底板从按点长出来；按在空白处只进入、不拿起。按住过半时，被按的块先轻微缩小、底板预显。其它入口：鼠标右键；feed 末尾常驻的 "Edit Home"（托盘里有没见过的新版块时带 "New"）；TalkBack 里卡片的长按和 section 标题上的「Edit Home」动作。header 的 Memories 入口、设置齿轮和气泡是排除区，长按不进入。
  - **编辑态**：标题交叉淡化成 "Edit Home"，前 2 次编辑在放得下时旁边显示 "Drag to reorder"。每块加底板、隐藏键和拖动把手（只剩一块时没有把手）。卡片级轻摆：约 1°、2.4Hz，6 秒没有触摸就渐停，任何触摸恢复；交界带内振幅为 0，省电模式和「移除动画」下不摆。卡片不可点，空 section 显示占位块，Memories 的下拉和气泡关闭。
  - **底板 V1（2026-10-05 owner 拍板 H1）**：底板向外扩到「页边距 − 4dp」，夹在 8–24dp 之间——手机页边 16dp 正好外扩 12dp，平板最多 24dp（平板的宽页边不会铺成一整块板）；圆角 20dp。编辑态 feed 的块间距随编辑进度（smoothstep）从 18dp 拉到 32dp（手机横屏从 10dp 拉到 32dp），底板借这段间距上下各外扩 12dp（相邻底板之间至少留 4dp）。横向出血的货架在编辑态收进底板里，在底板内缘 12dp 内 smoothstep 渐隐——这是「不许页中截断」规则**只在编辑态成立的例外**（owner 已接受），普通态货架照旧出血到屏幕边。长按进入时被拿起的块在间距变大的过程中钉在手指下（`HomePlateSpacingAnchor`：每帧按「它上方的间隔数 × 间距增量」滚动 feed，上面的块向上散开、下面的向下散开，和底板一起完成一次动画）。实现 `ui/home/edit/HomePlateVariant.kt`；V0（拍板前的 8 × 6dp 底板）/ V2 / V3 只留给 debug QA 台（`MemoriesScreenshotActivity --es plate`）对比。
  - **排序（2026-11-22 owner 改判，取代「拖动时全部收成签条」）**：按住把手，或在块上按住片刻，拿起这一块，原地拖动：布局不变，拿起的块在自己的图层里跟着手指走（两栏时横竖都跟），它的落点一变，其它块用空间弹簧让位；拿起的块越过相邻一行时它自己的位置的一半就换位（「半程」规则，来回同一条线，不会抖）。拖动时落点槽位画一个浅底框（拿起态底板色 + outlineVariant 描边），标出松手后的宽窄和位置。手指进入安全区上下 72dp 时 feed 自动滚动（直接拖动和捏合列表通用），速度按深度平方从 120dp/s 缓入到 900dp/s；拿起时手指本就在边缘带里，要朝边缘再移 8dp 或停留 180ms 才开始滚。滚动中每帧重算落点。每次换位（滚动带来的也算）打 SEGMENT_TICK；换位后要再移动 12dp（手指或滚动）才会做下一次判断，布局在静止的手指下变了也不会连跳。松手时一次写入（变了才写，打一拍 CONFIRM，一步 Undo），块从松手处用默认空间弹簧滑回落点，落定后它和被挪动的块各轻摆一下。拖动中 Undo、Reset 和其它编辑等它落下，捏合不响应；拉宽 / 拉高把手按住时不能拿起块。**在块上按住片刻才拿起（2026-11-23）**：按住时间按触摸事件自己的时间戳算，手指必须在整段时间里都没移出触摸阈值；一个长帧把手指的移动晚交过来时，晚到的移动也按它们的时间戳排在计时之前（批量事件里的历史采样逐个算），只要计时之前已经移出阈值就不拿起；计时已经触发后才到的、时间戳更早的移出阈值的移动会撤销这次拿起，不会变成拖动。feed 或列表正在惯性滚动、或停下不到 120ms 时按下，这一下只算停住滚动，不拿起、不轻摆；非编辑态也不算长按。**中断（2026-11-22 设计定稿）**：拖动中按 Done 照常写入再退出；系统返回、旋屏、单栏 ⇄ 双栏切换、窗口宽度变化、系统取消手势，都回滚到这次拿起前的顺序（不写入），返回再退出编辑。
    - **两栏（2026-11-22 设计定稿，owner 批准；`HomeDragRules.kt`）**：同行横拖 = 左右对调，跨行竖拖 = 拆开 / 换位；宽窄归拉宽把手，拖动只管位置和拼拆。下面的「横向位置」都是手指在内容区宽度上的比例。① 拖并排行里的半块，手指还在自己这一行（上下各多 16dp 缓冲）时只换左右：在右边的过 0.45 换到左边，在左边的过 0.55 换到右边，0.45–0.55 之间不动；手指上下移出这一行（超过缓冲）搭档才变回整行，拿起的块按落点自成一行或去配对；落在行间缝隙就在那里自成一行。② 手指在另一个 section 的整行上：这一行上 25% / 下 25% 不管横向位置，都把拿起的块作为一整行插到它上方 / 下方；中间 50% 里横向 < 0.30 拼成 [拿起的 | 它]、> 0.70 拼成 [它 | 拿起的]，0.30–0.70 之间整行按半程规则上下换位（从一对里出来的半块则按这一行的中线落到上方或下方）；拼成并排后立刻按 ①。③ 手指在别的两块组成的一对上：不三栏、不拆别人的搭档，按那一行的中线自成一行插在它上方或下方。④ 槽位在半宽和整宽之间变化时，手指在块上的横向位置按块**当前画出的宽度**逐帧按比例换算（宽度本身在弹簧上过渡），块不会先被甩到半屏外再长回来；两栏里块的左右始终收在内容区内，整宽块原地不动，不会为了让把手留在手指下而半截出屏（2026-11-22 闪现修复）。经过一对时整对一起跳过。宽窄变化时（拖出一对、拼进一对）块的**内容只按落定后的宽度排一次版**，块的底板、徽标和把手跟着宽度弹簧走：变宽时内容在底板里逐渐露出，变窄时底板收拢到内容上；不再在中间每个宽度重排卡片（Activities 的便当格、Jump Back In 的封面逐帧换列，卡片叠在一起、每次换列一个长帧，2026-11-23）。换宽度档时旧版式不和新版式同画一帧：版块留在同一个组合里按新宽度重排（只有同一宽度下换模板、换种子、拿掉卡片才交叉淡化成新组合），网格里的卡片（Jump Back In 的封面、记忆专辑、最近添加的曲目）直接坐到新位置，不从旧版式的位置弹过去，新出现的不淡入、离开的不在原处淡出；变宽时内容在底板边缘裁掉，不画出底板之外（2026-11-23 实测：旧卡片滑过新卡片、新一行淡入叠在上面、拆分前约 200ms 卡一帧）。边缘自动滚动带着行从手指下经过时不和任何整行配对（中间 50% 的左右两侧也按半程规则换位），滚动停下（手指离开边缘带，或已滚到头）才按 ② 配对；否则每经过一行就配对一次又拆开，块在手指下反复半宽⇄整宽（2026-11-23）。拿起的块从拿起到落定始终画在所有行之上（feed 和列表都是），而且这期间不吃滚动边缘的点阵溶解（`seamHold`）：块浮在内容上面，它被溶解出的空洞里露出的是从它下面滚过的行，看起来就像那些行的标题画在了块上（2026-11-23 实测：边缘自动滚动时 Lyric Cards、Recently Added 的字压在拿着的块上）。被拼进一对的邻居（比如把 Activities 拖回 Jump Back In 左边，Jump Back In 变成右半）内容和自己的底板一起走：底板从左边收拢，内容从第一帧就排在右半、贴着底板不动的那条边（右边），不会先在左半排好再滑过来，也就不会画进拿起的块的空槽里、压过槽的边线；反过来右半变整行时内容同样贴右边、在底板里向左露出（`homeCellAnchorsEnd`）。拿起的块的位置在排版阶段按这一帧的实际布局算（滚动、换位当帧生效），落点槽位和块同帧，不会晚一帧跳一下再弹回；松手时按手指最后的位置再判一次（快速拖到整行中间 90% 处松手是并排，不是按上一帧的旧位置换行），块从松手处滑进这一帧布局给它的槽位。
    - **捏合列表**：编辑态双指捏合（还没拿起块时第二根手指落下，滚动中也行）把 feed 收成紧凑列表：每个 section 一条，封面、标题、隐藏键、拖动把手；两栏时一对仍是并排的两条半宽条。列表跟着两指间距走，捏到起始间距的 55% 全部收起，松手时走过 35% 就收起，否则弹回；张开（到 1.8 倍全部展开，同样 35% 的门槛）回到原布局，新顺序已经生效。越过门槛时打一拍 SEGMENT_TICK。列表里拖动和 feed 一样（按把手立刻拿起，按条身片刻后拿起，滑动是滚动列表），双栏的并排和上面的两栏规则完全一样，列表比屏幕长时同样贴边自动滚动；隐藏、已隐藏的版块和 Reset 跟在列表下面，和 feed 的托盘一样，撤销和完整版面共用一个栈。单手兜底：编辑态 header 里设置齿轮的位置换成一个小图标（完整版面时是 ViewList「收成列表」，列表里列表顶上是 ViewCards「展开版面」），一点切换，仍在编辑态；拿起块、拖动或捏合时不响应。Done 直接保存并退出编辑。减少动态下列表只淡入淡出，不缩放。
  - **行数（H5，2026-10-05；改判 Q5 的「只动整块、不做尺寸档」）**：只有 Activities 和 Jump Back In 能调（`HomeSection.supportsRows`）；Recently Added 必须保持一张专辑卡高，Rediscover 是货架，都没有行数。四档预设 S / M / L / XL，**L = 有预设之前的版式**（每种屏幕逐像素不变）。存的是预设、不是行数，每种屏幕自己把预设翻译成版式（`ui/home/HomeRowPresets.kt`），所以跨设备同步的是「选了哪档」。同一屏幕上版式相同的相邻预设合成一个停靠点（手机横屏 Activities 的 S / M / L、2 行宽窗 JBI 的 S / M、历史太短分不出两档时），把手在那里只有一档，已存的预设保持不变。档与档嵌套：大档放得下小档的全部卡片（卡片可以换位置或换类型，往上走从不消失）。
    - Activities：手机 S = 只有大卡，M = + 一行「小卡 | 宽卡」（3 张），L = + 一条条形卡（4 张，今天的版式），XL = 在条形卡之前插一行镜像的「宽卡 | 小卡」（6 张，L 的条形卡在 XL 里长成宽卡）；窄手机每行是两张等宽小卡；手机横屏 S = M = L = 今天那一行，XL 多一行（宽 1.4 : 小 1 : 小 1）。平板单元网格见 `docs/adaptive-principles.md`「Feed 密度」。
    - Jump Back In：手机货架（3 列）2 / 3 / 4 / 6 行，手机横屏（6 列）1 / 1 / 2 / 3 行；只有一行或不足 9 格时只放 1 张信号卡，否则 2 张。平板保留今天定稿的 L 模板，其它档从它推出来（XL 每列底部追加一行封面，M、S 从底部逐行裁掉），规则同见 adaptive-principles。
    - **把手**：块底板右下角一个圆角的反 L「⌟」（iOS 小组件改尺寸的那个角）：24dp 字形、3dp 圆头描边，拐角和底板圆角同心（半径 20 − 6 = 14dp），离底板角 6dp，触摸框 48dp；平时 onSurfaceVariant，按住变 primary 并放大 12%，鼠标悬停给 60% 的按住态。和编辑徽标一起出现，只有一个停靠点时不显示；把手是编辑手势的排除区，按它永远不会拿起整块。
    - **拖动**：块的内容高度是这一块唯一的驱动值，1:1 跟手；越过最大 / 最小档走橡皮筋（量程 56dp、系数 0.55）。越过两档中点再 6dp 才换档（滞回），每次换档打 SEGMENT_TICK、把手脉冲一下。松手时速度 ≥ 650dp/s 且按 0.15 秒投影越过下一档的中点就多走一档，否则落到最近档；用默认空间弹簧带着松手速度落位（越界后松手只带 25% 的速度）。手指离底栏或状态栏 64dp 以内时 feed 自动滚动，最快 900dp/s。拖动时这一块不摆、下面的块 1:1 跟着推开。两档之间两档同时组合，每张卡在两个位置之间插值：两档都有的卡移动；只在大档出现的卡从 0.88 缩放、自上而下错开淡入；换类型的卡（条形卡 → 宽卡）先淡出再淡入。开始整理拖动时，行数过渡先停在当前档。
    - **写入**：抬手那一刻写入（档位真的变了才写，打一拍 CONFIRM），每次换档算一步 Undo；键盘 ↑ 少一档、↓ 多一档；TalkBack 用块自己的 "More rows" / "Fewer rows" 动作。Undo、Reset、键盘、TalkBack 换档都和松手走同一条弹簧。
    - **存储**：按账号存在 `home_layout` 里该块 `HomeSectionPref.config` 的 `{"rows":"s"|"m"|"xl"}`；L（默认）不写这个字段，也不会写出 `"config":null`。认不出的键原样保留；认不出的 rows 值按 L 显示，但值本身保留。不升数据库。Reset Home 连档位一起清掉；重排时各块的设置跟着块走。触感见 `docs/haptic-feedback.md` §F.1。
  - **两栏的并排记忆（2026-11-23 owner：「转了竖屏以后只保留顺序即可，回到横屏再记忆哪些在左边哪些在右边，是否要双栏等」「转了以后要保证左右两边高度一致」「调整的时候也应该让它俩最高高度不超过这两者最小可调整高度」）**：单栏和两栏共用**一份顺序和显示 / 隐藏**；两栏里谁和谁并排另外记一份（`HomeLayout.widePairs`，存的是记下时的左、右；显示时左右按顺序定，见下）。
    - **重新套用规则**：一对记住的并排，只要两块都显示、并且在顺序里紧挨着，就在两栏里并排；被拆开、或有一块隐藏了，两块各自整行，但这对仍记着，以后两块再挨到一起（比如在单栏里挪回来、从托盘点回来）就又并排。**顺序反过来**（在竖屏里把右边那块挪到了左边那块前面）时仍然并排，只是左右对调：顺序在前的那块在左（2026-11-23 owner 改定，取代之前「顺序反过来就两个整行」）。所以记忆实际只记谁和谁一对，左右跟着顺序走；比较两份记忆时不看左右（`homeSamePairs`），对调不算改动。两块高度范围没有交集的不并排（见下）。
    - **单栏编辑只改顺序和显示**，不动记忆；**两栏里的任何编辑**（拖动、拉宽、拉高、隐藏 / 显示、Move up / down、Reset）之后，记忆就是两栏此刻显示的样子：没显示出来的记忆会被忘掉（两栏里隐藏一半再点回来是两个整行，和以前一样）。从没自定义过的首页记忆是大屏默认（Activities | Jump Back In、Lyric Cards | Recently Added、Your Playlists | Rediscover），所以在手机或竖屏里第一次编辑后，横屏照样按这三对在新顺序里能挨着的并排，不会再出现「编辑过的首页横屏只剩一栏」。
    - **编辑中转屏**：草稿跟着新宽度走。没改过的草稿和以前一样等于在新宽度进入编辑；改过的草稿保留顺序，两栏按记忆并排；草稿是某个宽度的默认时换成另一个宽度的默认（两者都是「从没自定义过」），Undo 回到的那一步也按当前宽度显示。拖动中转屏照旧回滚这次拖动。
    - **高度一致**：一行里的两半永远同一个格数（`HomeLayout.unitsOf(row)`：取左半的格数，夹在两块可调范围的交集里），所以自动重新并排、用户拖成并排、读到旧布局时并排，结果都一样，最高不超过两块最大格数里小的那个。拉高把手对一对只给交集范围（`homeRowUnitRange`），写入时两块一起改。两块范围没有交集（`homeCanPair`）就不能同高，拖动、拉宽、TalkBack「Place beside previous」都不会把它们拼成一对，自动套用也给两个整行；目前目录里任意两块都有交集（都至少 1–2 格）。
    - **存储与兼容**：`home_layout` 文档加一个可选键 `"wide":{"pairs":[["activities","jump_back_in"], …]}`（左、右），版本仍是 1，没有就写空数组，表示「什么都不记」；每块的 `"span":"half"` 照写两栏此刻显示的并排，旧版本照它显示，看到的和新版本两栏一致。iOS 和 HarmonyOS 写入时把 `wide` 和文档里其它认不出的键（每块里认不出的键也保留）原样写回，和单栏编辑一样不动记忆，只有「恢复默认」时丢掉；旧版本写入时会丢掉 `wide`（它们只写 `version` 和 `sections`），新版本读到没有 `wide` 的文档就从 `span` 推出记忆，一个 `span` 都没有（只在单栏编辑过）时用大屏默认的三对。所以本版之前存下的、没有并排记忆的旧布局，升级后在大屏上按大屏默认的三对在它自己的顺序里能挨着的并排（2026-11-23 owner 接受：旧版在单栏里编辑过的首页，横屏会出现 Activities | Jump Back In 这类默认并排，而不是全是整行）。认不出的 id 组成的对和 `wide` 里别的键原样保留；指向已下线 section 的对丢掉。新加的 section 被追加时，大屏默认里它的那一对在两块都空着时加进记忆。云同步和备份把整份文档当字符串原样带着（`HomeLayoutSyncAdapter`，Android 和 `:shared` 都是），不用改格式。
  - **隐藏与恢复**：隐藏的块缩放淡出，进 feed 末尾的 "Hidden" 托盘；托盘每行点 + 回到原来的位置，"Reset Home" 回到默认。每一步立即保存，可以 Undo（最多 20 步，退出即清空）。全部隐藏时，普通态显示 "Your Home is empty" 卡。
  - **退出**：Done、系统返回（= Done，离散，不震；返回进度预览是 P1）、点空白处。切 section、打开 Now Playing 或详情、进设置、App 退到后台时直接收起。状态是 `HomeSurface.Edit`，由 shell 持有的 `HomeEditController` 独占写入；返回归 `ShellBackOwner.HomeEdit`，优先级 NowPlaying > HomeEdit，其后才轮到并排的面（详情列、Memories，按打开顺序的反序，见「详情列」）。
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

**NeoDB 同步、全部 Memories 与「别人怎么说」（2026-11 更新）**：

- **NeoDB 同步边界（`NeoDBSyncService`）**：以 album 为单位；**专辑评分与非空专辑评论彼此独立触发同步**（`ratingNeedsSync || reviewNeedsSync`，不再要求两者同时具备）。通过 `ShelfMarkRequest` 更新 `ratingGrade`（Yoin 0.5–5.0 / 内部精度按 NeoDB 0–10 刻度对齐）与/或 `commentText`；并按字符码点长度自动归位（`NeoDbShortCommentMax = 360`）：
  - **`<= 360` 字**：作为 NeoDB **短评**写入 Mark 的 `commentText`，并清除此前关联的长评；
  - **`> 360` 字**：作为公开**长评**（`Review.body`，`visibility = 0`）发布并清空短评字段。
  - 单曲碎片笔记不直接推送到 NeoDB。
- **全部 Memories（`MemoryLedgerScreen` / `memory_grid`）**：收录全部已评/已写专辑。页面顶部展示统计数据与**自适应日历格点（写得越少、单格越大）**，点击任一格点可仅筛选该时间段；向下滚动时日历格点向上飞入顶栏合成为**像素条（`MemoryGridLift`）**。支持 **时间线（Timeline）/ 双列卡片（Cards）/ 可排序列表（List）** 三视图切换（平板等宽窗下时间线与卡片按 2–3 列展开）。账本包含所有账号的记忆：点按属于其他账号的专辑前会弹出一次性切换确认提示，先切过去再打开详情。
- **NeoDB「别人怎么说」（`NeoDbVoicesScreen` / `NeoDBVoicesView`）**：无需登录 NeoDB 即可查阅；专辑页与专辑详情栏提供入口。顶部分别绘制 NeoDB 五档分布条（月相评分分布）与均值/人数、并列读取关联的 **Discogs** 与 **MusicBrainz** 外部评分；正文流按「长评铺满宽度、短评双列排布、作者折叠内容可点开」展示众人短评与长评（每页 20 条分页加载），底部外链支持跳转 **NeoDB / 豆瓣 / RYM / AOTY**。

### 🎵 Now Playing（全屏展开态）

- 大封面图（共享元素：从 Button Group 缩略图扩展而来）
- 歌曲标题 + 歌手
- 进度条（波浪形设计，可拖拽）：播放时从 0 到 100% 都是波浪，不沿用 M3 默认的首 10%、尾 5% 拉平（2026-10-08 / 10-10 owner）；暂停时收平成直线。振幅只看播放状态，过渡交给 M3E 组件自带的 500ms 振幅动画，再加一次延后复核，补发它在动画进行中丢掉的最终目标（`WaveProgressBar.kt`）
- 播放控制（上一曲 / 播放暂停 / 下一曲）— MD3 Expressive 大按钮风格
- **滑动评分条**（垂直粗条设计，位于封面右侧，直接显示精确浮点评分如 3.7，视觉表现力强）
- 收藏按钮（心形图标，位于评分条下方）
- 歌词同步显示区域（位于控制区上方，逐行高亮）
- **底部操作胶囊 (Pills)**：设备投射（Chromecast/Sonos）、播放队列、笔记等次级入口
- **播放队列（2026-10-06 owner Q1：独立的 Queue 按钮 + 底部弹层，UX 照 Spotify，UI 好看一点、不复杂，`QueueSheet`）**：顶上「Playing from · 专辑 / 歌单 / 歌手名」（没有来源时写 Queue；歌手见下条 T6），然后三段：Now playing（当前这首，主色 + 跳动的均衡器）、Next in queue（用户 Play next / Add to queue 加进来的，右侧 Clear）、Next from: 专辑 / 歌单 / 歌手名（其余的，按真实播放顺序，随机时也是；三种来源各用自己的字符串）。播过的不显示。点一行即播那首。行高固定 64dp：48dp 封面 + 标题 / 歌手 + 右侧拖动把手；按住把手拖动只在本段内换顺序（行抬起到 surfaceContainerHighest，邻行用空间弹簧让位，松手那一帧直接换成新顺序，不回弹再滑）；向左滑移除（errorContainer + 删除图标）。Add to queue 按 Spotify 语义排在「你已经加的」之后、专辑剩余之前，Play next 紧跟当前这首。能力随后端：Subsonic 全部可改；Apple Music 只能移除（MusicKit 不支持移动）；Spotify 只读（App Remote 不能改用户队列）；随机播放时不能拖动（列表顺序不是播放顺序）。条目的稳定 id 和「用户加的」标记存在 MediaItem extras 里。原型里的「NP 内 Queue 页签」方案已被否（16:9 屏会被挡、不能左右滑）。
- **「Playing from」的来源（2026-10-11 owner 拍板，T6）**：
  - **Apple Music 大列表起播（2026-10-12）**：先排除无法播放的歌曲；剩余超过 300 首时，沿用 Library Songs 和歌手页的 100 首起播窗口，保留点中的歌曲和窗口内原有顺序，通常保留它之前的 20 首，列表末尾向前补足 100 首。300 首以内仍交给 MusicKit 整份列表。超过限制的歌单不会整份入队；这是避免 MusicKit 一次目录请求超过上限导致整次起播失败的现有窗口策略延伸。
  - **歌手页**：Play / Shuffle / Most Played 起播都带歌手上下文（`ActivityContext.Artist`）。NP 页头的两种状态（完整页头和停靠页头）都写 PLAYING FROM ARTIST / 来自歌手 + 歌手名，队列顶上是歌手名，「Next from」用歌手自己的字符串（`cmp_queue_next_from_artist`）。Spotify 仍按 `uris` 列表起播：Spotify 的歌手上下文是歌手电台，不是 Yoin 列出的顺序。
  - **首页的单曲卡保持只播这一首**，不带来源。Memories 起播的专辑、歌单用完整的 MediaId（`provider:rawId`），歌手用 `MediaId.toString()`，不用裸 id。
  - **Yoin 设定的来源不被 Spotify 的空话盖掉**（`SpotifyContextSync`）：Yoin 起播时设的来源（专辑 / 歌单 / 歌手 / Liked Songs / 记忆）代表交给 Spotify 的那份队列，Spotify 播这份队列期间一直显示它。Spotify 报来的上下文为空、uri 为空或空白、或是 Yoin 认不出的（`uris` 起播本来就没有上下文；歌手电台；Yoin 起播的不是 Liked Songs 时的收藏），都不算数，绝不覆盖 Yoin 的。原始 uri 和 title 打 `Log.d`。
  - **起播确认**：Spotify 还没播到这次点中的歌之前，它报的一切都不算（冷连接先报的是起播前在放的东西），这期间也不记播放。Spotify 显然已经走开时才放手：在这条连接上第一次听到 Spotify 15 秒后、又来一首不同的歌，或者已是第 3 首不同的歌（起播在后台被用户在 Spotify 里盖掉，或者根本没落地），来源改为跟随 Spotify。
  - **起播落地（2026-10-12）**：搜索和 Home 的无来源单曲直接用 App Remote 起播。其他列表先用 Web API，必须明确找到本机 device id；找不到则回退本机 App Remote，不能让账号上另一台活跃设备接管。Web API 回 2xx 后，等 App Remote 在 4 秒内报出正在放点中的这首（同 id 或同名同歌手的换市场版本，且没有暂停）；听不到就由 App Remote 直接 `play` 这一首，再等 4 秒。起播绝不通过 `queue` 循环追加歌曲。最终没有确认或 App Remote 起播失败，就显示现有播放错误，不能静默重连并丢掉点按。冷连接报来的同专辑旧曲目不确认起播，也不计入播放；快速连点时，前一个网络请求结束后才能发送新的起播。切后台期间失去连接则在回来时观察结果，不重新起播。
  - **确认之后**：Spotify 报出另一个专辑或歌单，就显示它；还在 Yoin 队列里时报来的空话，把 Yoin 的来源带回来。放到 Yoin 队列以外的歌，表示 Spotify 走开了（用户在 Spotify 里接手，或播过了列表），此后来源跟随 Spotify：它这条连接上最后报的专辑或歌单，没有就是无。同名同歌手的歌算同一首（Spotify 换了市场版本、换了 id）；Spotify 仍报着 Yoin 的专辑或歌单时，标签保持 Yoin 那份带封面的。Liked Songs 只交给 Spotify 点中那首前后最多 100 首，但起播的是整个收藏：Spotify 报 `spotify:collection:tracks`、`spotify:user:<id>:collection` 这类收藏上下文时，算作 Yoin 的 Liked Songs，播出这 100 首也不算走开。
  - **断线重连**：断线期间 Spotify 可能已经换了内容，所以旧的上报作废。重连后 PlayerState 先于 PlayerContext 到达，队列外的歌要等 Spotify 新报的上下文（最多 2 秒）再决定交不交接：还是 Yoin 的专辑、歌单（300 首以上的歌单随机播到 Yoin 没镜像的部分）或收藏，就保留 Yoin 的来源；这首歌等来源定下来再记播放。
- **背景**：两个暗色调的微妙渐变（渐变过渡极其平滑，几乎感觉是纯色）+ 实时音频可视化（与背景融为一体，有呼吸感）
  - **浅色静止时的「房间」和 iOS World 一致（2026-11-04 owner 定，波点保留；2026-11-05 改口：深色仍用安卓原来的 surfaceContainer → background 渐变加四个封面色点，深色聚焦不变）**：本封面 `background` 打底；上一首的 `primaryContainer` 从顶边外渗进来、下一首的从底边外渗进来（整宽软椭圆）；本封面 `primary` 一团柔光在正中（画在邻曲上面，切歌时照旧从按键一侧 / 随歌词流进来）；最上面一层 `background` 薄雾；整个房间画在一层里，浅色提饱和、深色压暗，模拟 iOS 液态玻璃的效果。iOS 的原数值在安卓上浅色发灰、深色发雾，所以强度是在模拟器上对照 iOS 同一批封面截图调出来的（中间亮度差 ≤2、饱和度差 ≤11），具体值见 `RoomAmbient.kt`。邻曲按播放顺序取（列表循环首尾环绕；单曲循环、队列两端、随机时的上一首没有邻曲，退回本封面自己的 `secondaryContainer` / `tertiaryContainer`，边缘不发灰），封面读完后才取、带缓存，换曲时用 effects spring 过渡；全局主题仍一步切换。数值在 `RoomAmbient.kt`，iOS 对照 `iosApp/docs/gradient-parameters.md`。聚焦的封面取色场、Gemini 思考、点歌词色带、按键细点和各处波点都不变。
  - **聚焦的封面取色场（2026-11-05 owner 定，原型「Yoin 聚焦新意」）**：在原来的 16 点场上微调，不换形态。运动选「明显」档：所有路径快 3 倍、幅度 2.2 倍（游走点不出屏）。配色：原型里压深、加饱和的一版在真机上让歌词不好读（owner 同日：「渐变有点太夸张」），所以亮度带和深色色调都回到原来（浅色 0.70–0.94，深色 32 / 24 / 32 / 14、游走点 46），只留一点饱和度（浅色 ×1.5、深色 chroma 下限 44）。另有一条可读性底线（owner 同日：「运动完全可以动，但是颜色不应该超过可读的颜色过多」）：浅色聚焦里每个色点按当前歌词行（primary）≥ 4:1 的对比度设亮度下限，不够就只提亮度、保留色相饱和度；运动不受限。深色当前行本来就在 7.5:1 以上，不加。深色房间的第二、第三色相取自封面本身：同一次 Palette 取色（vibrant / muted / 暗、亮 vibrant / 暗、亮 muted / dominant）里，按重要性挑出不是灰色、和主色及彼此差 ≥ 25° 的颜色，再按 HCT 改到深色房间的色调。封面没有别的颜色时才用配色方案的 secondary / tertiary，并限制在离 primary 50° 以内（expressive 配色会把它们转到将近对面，紫色封面得到绿色，下半屏泛墨绿）。owner 同日定：只限制封面里没有的颜色。浅色聚焦随后也改成同一套（「浅色也这么改一下」）：主色和封面色板里的颜色，按 HCT 换成浅色调（88 / 84 / 88 / 93，游走点 80，chroma 下限 30），再过当前歌词行 ≥ 4:1 的亮度底线。加「一首歌一段色程」：随播放进度，封面第二色从一侧推进来、到结尾铺满（最多 60%）；下一首或自动下一首来的从右往左，上一首来的从左往右。显示值约 1.2 秒追上进度，切歌、拖进度都是滑过去不跳。第二色取封面主色之外色相差最大的那个（相近色看不出变化）；前沿占屏宽 30%。聚焦里切歌，新歌的颜色也按同一方向扫进来（约 0.9 秒），不再整屏同时淡入——方向感主要来自这一下。数值在 `FocusField.kt`。没选的点子：歌词牵光、切歌从底边涌上、胶片颗粒、倾斜视差。
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
  - **Wide 整窗（平板横屏、桌面窗口）也是同一条链**（2026-10-02，适配原则 4）：点 pill 先开侧栏（宽同上），右上角「全屏」→ **双栏 TabletNP**（左栏的控件、评分、标题、胶囊恒按 312 排，封面吃剩下的高度、最大 312——矮窗先收间距、再把标题收成一行跑马灯，不再让控件跟着封面变窄；右栏歌词吃剩下的；点封面 = 共享的「看封面」，评分和标题让位、封面最多 1.5 倍、左栏跟着封面加宽、右栏不小于 320），右上角同一位置「收回侧栏」；2026-11-06 起评分条竖在封面右边、三颗胶囊收成右上角三个图标、标题靠底（见 adaptive-principles）。返回：双栏 → 侧栏 → 关闭。横竖屏切换保留用户的侧栏 / 全屏选择。开着详情列时侧栏照开，两列整体让位（1280 → 剩 860 仍分两列，各读 Compact）；侧栏旁放不下两列（窗宽 − 侧栏 < 744，即 < 1164 的 Wide 窗）时 NP 直接全屏双栏。在双栏里点「前往专辑」：侧栏放得下就先退回侧栏再开列，放不下就收起 NP 再开列——页面永远可见。旧裁决「分栏窗格里 NP 占满本窗格、无侧栏无全屏键」随 Activity Embedding 分栏一起废止
  - **放大的手机的高度预算**（适配原则 6）：歌词窗口至少可见 4 行（165dp，按 `LyricsDisplay` 真实几何）、封面最小 168，先收间距再缩封面，再不够才退到一行歌词
  - **手机横屏**（2026-10-03 owner）：静止时右栏不显示 Lyrics / About / Note，只有一行可点的当前歌词（同 16:9 规则，点开进展开页才出 tabs + 工具）；标题和歌手并一行；右栏整列避开远端挖孔（播放模式键在行尾）。16:9 矮屏的单列同样把标题和歌手并一行（高度梯子里排在歌词换形之前）
  - **点封面 = 看封面**（2026-10-03，适配原则 6）：手机 / 侧栏 / 放大 / 双栏 / 手机横屏共用 Immersive；评分、tabs、歌词窗口、间距让给封面，封面至多 1.5 倍，**当前那一行歌词始终保留**；长不大 max(40dp, 12%) 的窗口里封面是纯图片、不可点。平板竖屏侧栏和放大态在焦点里保留歌词列表（放得下可见 4 行时）
- **歌词的末句与切歌（2026-10-05 owner 验收）**：
  - **末句拉长 = I-2（像拖长的一个音）**：从末句成为当前行（或结束前 10 秒，取较晚者）到交接，末句以左缘为原点向右拉宽，`scaleX = 1 + min(0.08, 行宽 ÷ 最长行 − 1) × p`，拉宽后一定放得下这一行；已经占满行宽的句子也至少能拉宽 3%（`LastLineStretchGutterRoom`），伸进页边留白里，只会变宽、不会被截断。**所有文字一律是整行绘制期 scaleX**，不重新排版（2026-10-05 晚 owner：拉英文时一帧一帧地抖——之前满行的拉丁文改加字距，每一级都重新排版；中文整行缩放一直是顺的，所以字距方案已删除）。拉宽用慢空间弹簧；交接时用默认空间弹簧带一点回弹收回，同时整块淡出。拉长和下一首浮现读同一个进度（`UpNextTiming.outroProgress`）：拉长从头起步，下一首歌名等这段进度过半才浮现（2026-10-08 owner：预告太抢眼）；拿不到下一首（Spotify / Apple 没有 nextTrack，浮现只在 Subsonic 上有）也照样拉长到换歌。只在展开歌词页和双栏右栏，小歌词窗没有。实现 `ui/nowplaying/LastLineStretch.kt`、`UpNextTiming.kt`。
  - **切歌脉冲 = 方案 B：顺着歌词流的一道光**：歌词是主表面时（单栏：Lyrics 页展开，或小歌词窗可见且不是一行模式；双栏：右栏在 Lyrics；横屏：展开且在 Lyrics）切歌，背景放一道光：下一首 / 自动播完从歌词列下缘外 0.25H 处升起，上一首从上缘外落下（方向按队列位置判断，和 `LyricsTrackTransition` 同一规则，光和歌词永远同向），落到新歌名卡片上；半宽 0.62W，半高 0.25H → 0.09H。位移走慢空间弹簧，亮度用快效果弹簧升起，位移过 85% 后用慢效果弹簧退掉，全程申请高帧率。双栏和横屏把光裁在歌词栏内，单栏不裁。点击、自然播完、外部控制都算；歌词页已经把这首原地交接过来时不放（那段浮现本身就是转场）。封面是主表面时，只有 3 秒内点过 NEXT / PREVIOUS 才从那个按钮起环，没有点击来源就不发，绝不复用旧坐标。播放 / 暂停的呼吸照旧：3 秒内点过 PLAY 从按钮起，耳机、通知栏从默认点（0.5W, 0.62H）起。点击记时间戳、只用一次；「上一首」只把本曲拉回开头时（还是同一首、进度回退 ≥ 1 秒），这次点击当场作废。**「上一首」各服务一致（W2，owner 2026-10-05 晚）**：播放超过 3 秒时先回到本曲开头，不到 3 秒才跳上一首——Spotify 本来如此，Subsonic / Apple Music 改走 Media3 的 `seekToPrevious()`（`maxSeekToPreviousPosition` 默认 3 秒），通知栏、耳机和 App 内按钮同一规则。Apple Music 的 PREVIOUS 命令同时表达「回到本曲开头」和「上一项」：前面有歌或本曲能 seek 就有，不只看 MusicKit 的「有没有上一项」（MusicKit 在 repeat 关时队列第一首前面没有歌，以前 Media3 因此把首曲上的 ⏮ 整个丢掉；测试反馈 2026-10，`musicKitTransportCommands`）。实现 `TransportPulse.kt`、`NowPlayingAuroraBackground.kt`。
  - **不做 QRC 逐字**（owner 2026-10-05）：歌词只到逐行高亮。
- **操作光效（2026-11-04 owner 认可原型 v1）**：Now Playing 上每个会改变东西的操作，都让背景从按的那个控件亮一下，和播放 / 暂停、切歌、点歌词同一套材料：封面的 primary / secondary / tertiary 加细波点，平时静止，只亮 0.65–2.1 秒，跑的时候申请高帧率。「加上」光往外放，「去掉」光收回控件、更暗更短。等待（翻译）用封面色的慢扫光，**在线 AI** 用彩虹色。只打开面板、切 tab、离开页面的操作不加光。逐项：收藏 = 爱心跳两下、一圈细点往外散，取消收藏 = 暗光收回爱心；长按爱心（加歌单）和资料库按钮 = 细点收进按钮再亮一下；评分 = 按住时光沿评分条升到手指，分越高越亮，松手后一道光在那个高度横穿背景，9.5 分以上多一串细点，清空 = 光往下退；随机开 = 三种封面色转着换位、细点散开，关 = 细点排回一行；列表循环 = 一颗光绕键一圈，单曲循环 = 两圈紧环，不循环 = 环散成点落下；拖进度条 = 光跟着手指，松手处亮一下；存笔记 = 光从「写」升到当前歌词行、那行亮起，删笔记 = 那行的光缩回；翻译 = 等待时慢扫，出来后每行从上往下亮一次；换用搜到的 / 手写的歌词 = 每行亮一次；换播放设备 = 光从「设备」往下离开，再从底边回来一圈；保持亮屏 = 整个背景轻轻亮一下；长按封面 = 一圈光收到封面上；复制 / 分享歌词 = 当前行一道光。原型 `/mnt/project-files/np-light/prototype-v1.html`，实现 `ActionGlow.kt`（控件调 `NowPlayingTransportSignal.glow`，背景在 `nowPlayingAuroraBackground` 里画）。
- **展开歌词页的焦点位与译文波浪（2026-10-12；2026-11-16 提交 `d7d09efc0`）**：当前行按它实际排出来的高度，把**行中心**放在上下渐隐之间那段清晰区的正中（`LyricsFocusFraction` = 0.5，`lyricsFocusScrollOffsetPx`，扣掉列表顶部 48dp 留白）；行比清晰区还高时，行顶贴住上渐隐的下沿，后半截进下渐隐。上下渐隐各 64dp，但每边不超过歌词窗高的 20%（手机横屏约 227dp 的歌词区约 45dp，`LyricsEdgeFadeMaxFraction`）。以前是把**行顶**放在 48dp + 38% 窗高：约 600dp 的竖屏歌词页上单行正好居中，横屏却落在 59% 处。中途打开、目标行还没排出来时先按可见行的平均高度估算，排出来后在同一帧里改正；滑到下一行前先等一帧，让它按当前行的字重排好再算位置（settled focus seat）；滑完还差一点就用空间弹簧补完。开关翻译时当前行原地不动（按开关前的行高钉住），**其余行的译文从当前聚焦行开始依次向外一拍拍波浪展开/收起**（近乎无回弹的慢弹簧，文字从原句下方缓出），同时伴随 `YoinHaptics.performPluck(opening)` 弹拨合成触感（`PRIMITIVE_CLICK` + 衰减 `PRIMITIVE_LOW_TICK`）；`LyricsActionBar`、点句跳播、选择模式勾选项、`YoinTabGroup` 与可播曲目行亦已补全点击触感。
- **歌词来源、多语种名称解析与缓存（2026-10-05；2026-11 扩展）**：
  - **加载顺序**：用户选的歌词 → Subsonic 服务端歌词（Subsonic 账号没有用户选的行时只用服务端歌词，不走自动兜底）→ `lyrics_cache` 里 30 天内的自动行 → 自动兜底链 QQ 音乐 → 网易云（经 gateway）→ LRCLIB，第一个返回非空 LRC 的获胜。QQ、网易云、华为挑候选都过同一个 `LyricCandidateMatcher`（按标题、母带后缀、专辑、歌手、繁简/跨文字与时长容差严格评分），宁可返回 null 交给下一家，也不贴错歌。`LyricCreditsExtractor` 从歌词首尾抽离词曲/编曲/制作人信息注入「关于」页与专辑侧脊。
  - **跨语言与 ISRC 别名回退（`fetchLyricUnderOtherNames`）**：非 Subsonic 曲目首轮无结果时，按以下顺序补查：① `alternateNames`（展示名含非拉丁字符时尝试服务英文名，如 Apple Music 日区 `キム・ペトラス`）；② `nativeNames`（`SpotifyNameLanguages` 按设备 `Accept-Language` 优先，再按 ISRC 国家/地区码映射 `TW→zh-TW`、`HK/MO→zh-HK`、`CN→zh-CN`、`JP→ja`、`KR→ko`、否则非英文环境尝试 `en`）；③ **`MusicBrainzIsrcClient`**（`GET /ws/2/isrc/{isrc}?fmt=json&inc=artist-credits`，遵守 1 req/1.1s 限流并校验 `±3000ms` 时长窗口）；④ **在线 AI 发行名兜底**（已配可用密钥时 `askAi = true`，按曲目缓存在 `releaseNamesByTrack`）。配合 **`AppleMusicNameRegion`**（`Off` / `Original` / `MainlandChina` / `Japan`）与 **`AppleMusicNameResolver`** 还原或统一 Apple Music 店面歌名与专辑名（Android、iOS/macOS 与 HarmonyOS 三端均已支持 `AppleMusicNameRegion`；HarmonyOS 歌词端支持 `alternateNames` 与繁简表，iOS 歌词目前使用 `:shared` `LyricsRegistry` 且尚未接入 `otherNames` 多语回退链）。
  - **华为音乐**：只在手动搜索面板出现（`automatic = false`），是网易云之后、LRCLIB 之前单独一栏，不进自动链，也从不被提议为「整套切换」的目标。只用匿名公开接口，不带 Authorization、Cookie、设备 id 或任何 token。简体中文译文写在同一行的 `^` 后面，按最后一个 `^` 拆成原文和同时间戳的译文，没有译文的行用 `//` 占位。song id 是自带解析信息的 `hw1|<contentID>|<查询提示>`：歌词地址只在进程内记住，没记住或已失效（403 / 404 / 410 / NoSuchKey）时用查询提示重搜一次、只认同一个 contentID，找不到就算没词，绝不换成同名的另一条录音。接口不可用时手动搜索提示 "Huawei Music is unavailable right now"。
  - **自带译文**：当前歌词源自带的译文能服务目标语言（三家自带的都是简体中文，只在设置中回答语言选中文时用）就直接用；不能时只提议把整套歌词换到排在它后面、自动、带译文的源（原文和译文一起换，绝不按行号拼两家）——实际效果和以前一样：QQ 提议网易云，其余走**在线 AI** 翻译。同一时间戳下有多行、两边行数相同时按位置配对。snackbar 写显示名（"Lyrics applied from Huawei Music"），不写原始 id。
  - **缓存寿命**（owner 拍板：「用户选择的缓存还是不要按照天数吧，按照总大小比较好」）：`lyrics_cache` 有两种行，不改 schema。**自动行**保持 30 天 TTL，过期重新匹配，让更好的来源或 matcher 的修正还能换掉它。**用户选的行**——手写 / 粘贴（`lyricsProvider = manual`），以及在搜索面板里明确应用的任何一家、接受了的整套切换（这两种在 `lyricsProviderSongId` 前加 `user|` 前缀）——不按天数过期，重新加载时压过自动歌词，也压过 Subsonic 服务端歌词；`cachedAt` 对它们表示「最近一次用到」，读取时刷新（一天最多写一次库）。重新搜索、应用别的结果会覆盖它。**整个缓存按总大小封顶**：一首歌的占用 = 歌词 LRC 的 UTF-8 字节 + 这首歌免费的 `provider:*` 译文字节；总量超过 10 MiB 就淘汰到 9 MiB——先淘汰自动行（和没有歌词行的孤儿译文），旧的先走；再淘汰搜索里选的行，最久没用的先走；手写 / 粘贴的行计入总量但永不淘汰，刚写入的那首也不淘汰；付费的在线 AI 译文不计入、也不淘汰（云同步对它只增不删）。规则改动不清空现有缓存：旧行照自动行处理，到期自然重配。实现 `data/lyrics/LyricsCachePolicy.kt`、`LyricsCacheBudget.kt`。
- **笔记 Take Notes（方向 A，2026-10-05 owner「感觉完美」）**：
  - **笔记就是歌词行**：收起的小窗和展开的 Note 页都用共享的 `NoteLine`（`ui/component/NoteContent.kt`）：时间戳占固定 40dp 一列、等宽数字，正文跟在后面；播放头所在的那条（最近一条 ≤ 播放头的锚点笔记，和歌词同一规则）亮成 primary，颜色在绘制阶段读；没有锚点的旧笔记显示 6dp 空心点。小窗沿用强衰减和缩小、固定时间线顺序、不带排序和页头；展开页只弱衰减（0.62，播放头不在任何一条里时 0.86），**没有卡片、没有竖线、没有垃圾桶**，排序开关（时间线 / 先后）保留，「先后」模式下每行多一行小字日期；自动跟随把当前那条停在视口约 22% 处（写作时暂停），列表上 24dp、下 64dp 渐隐。写作时在锚点位置插一行虚线下划的「正在写…」（草稿属于别的歌时不显示）。点一行跳到那一刻。
  - **写作条 `NoteWriteBar`**：放在 NP 底部的 accessory 槽（Note 页底部，Wide 也一样），贴着键盘（IME insets 走 `ui/component/ImeInsets.kt`）。平时是 56dp 胶囊（surfaceContainerHigh 92%，圆角 28），左边是按秒刷新的播放头时间片，占位「记录笔记」；写的时候长成卡片（2–6 行，超出在内部滚动，圆角 28 → 20）。时间片点一下 = 对齐到现在并打一拍 tick；**不写说明文字**（2026-10-08 owner：常驻提示行很怪）：已经写了字、且播放头偏开 ≥ 2 秒时，时间片前滑出一个对齐图标（`YoinSymbols.Refresh`）；草稿属于上一首时时间片前放那首歌的小封面（Thumb 24dp）。卡片底部只有「记下」。「记下」（`JournalSavePill`）存完键盘不收，可以接着写下一句；键盘收起且草稿为空时回到胶囊。整个过程只有一个输入框实例，变形不丢焦点。手机横屏改成单行布局（「记下」在行尾，最多 3 行），写作时标签行和标题行让位。**没有底部 sheet**：`WriteNoteSheet`、`NoteComposer`、`NoteCard` 已删除；点 Write 胶囊切到 Note 页并展开，等页面落定（展开过半到 90%）才弹键盘，这个请求 2 秒内没兑现就作废；单列里键盘起来时歌名让位，歌名和歌手改成小字出现在顶栏封面旁；Wide 左栏的 Write 切到 Note 标签并聚焦。
  - **收起时的空状态（2026-11-23 笔记改版，原型场景 1）**：左边 Stroke 涂鸦自己画出来，右边「写笔记」胶囊：饼干形的笔徽章（进场时转正，按下变圆，形变走 `rememberPressMorphShape`），文字和实时播放头时间；点它和底部写笔记胶囊一样展开写字栏。这首歌在别处被提到过时，下面一行小字「在 X 的笔记里被提到，另有 N 处」，点了去那里。实现 `ui/nowplaying/compact/NoteCompactEmpty.kt`。
  - **空状态**（这首歌还没有笔记）：区域中间画一个涂鸦，下面只有一个标签「还没有笔记」，不写提示语（2026-10-08 owner）。涂鸦三选一，按歌随机：从播放头小圆点拉出一道波浪、落成一个音符；写了一半的便签，光标在等；手画的 Yoin 三根箭头。笔画用效果弹簧按顺序画出，实心形状用表现型空间弹簧弹出，画完就静止（光标只闪 3 下）。颜色取主题的 primary / secondary / tertiary / outlineVariant，便签纸是 surfaceContainerHigh 混 45% tertiaryContainer，跟着动态取色和播放配色走。区域放不下时只留标签。实现 `ui/nowplaying/NoteEmptyDoodle.kt`。
  - **长按一条笔记**（2026-11-24 owner 改：取代原来的「对齐到现在 / 删除」菜单）→ 这条笔记的原文进写作条，写作条拿到焦点和键盘，列表里这一行淡到 38%，表示它正在写作条里；时间片显示它原来的时刻，点一下照旧是对齐到现在；「记下」改写的是同一条笔记（id、创建时间不变，时刻跟着时间片）；改空再记下就是删除，走下面同一套「已删除 · 撤销」。没改动就收起键盘 = 不改，写作条回到胶囊。写作条里还有没存的新笔记时，长按不会把它冲掉，只是把写作条打开在那段字上（`NoteDraftState.beginEdit`）。TalkBack 的长按动作读「修改」。删除后这条立刻从两个窗格和进度条刻度上消失，原位变成「已删除 · 撤销」一行；5 秒后、或删下一条、或 NP 退出时才真的删库，撤销还原的是同一行、不新建。撤销做成列表里原位的一行，不用 snackbar。
  - **草稿**在开始写的那一刻（聚焦空草稿，或写下第一个字）绑定到当时的曲目和时间锚点，直到存下、清空或明确对齐为止——写到一半切歌，存下的笔记仍归原来那首、带原来的锚点。草稿按账号分开：切换账号时丢弃。

### 🔊 后台播放、输出设备与跨端接力

- **媒体会话与原生引擎**：Android 使用 Media3 + `PlaybackService`（MusicKit Android 另由 `AppleMusicPlaybackService` 桥接）；Apple 使用 `AVQueuePlayer` + `MusicKit` + `SPTAppRemote`/Connect；HarmonyOS 使用 ArkTS `PlaybackEngine` + `AVSession` 与 API 21+ 多任务并发后台保活（`ContinuousTasks.ets`）。
- **投播与外部扬声器**：Android 支持 Google Cast（**注意：Google Cast SDK 会作为播放能力一部分向 Google 发送匿名使用与性能统计**）与局域网 DLNA；iOS/macOS 使用 AirPlay；HarmonyOS 使用系统 `AVCastPickerHelper` 与 DLNA。
- **空间音频（Spatial Audio）**：Android 通过 `SpatialAudio.kt` 以 `SPATIALIZATION_BEHAVIOR_AUTO` 将多声道交给系统 `Spatializer`（可在 `设置 › 播放音质` 开关并查看实际设备支持状态）；iOS 通过 `SpatialAudioMonitor` 监测输出通路。
- **跨设备接力（Continue On / Handoff / 应用接续）**：完整协议与 TCP 暂停反控频道规范见 [continue-on.md](continue-on.md)。
- **桌面与系统微件**：Glance `PlayWidget` / `MemoryWidget`（Android）、WidgetKit + ActivityKit 实时活动（iOS/macOS）、FormKit `PlayCard` / `MemoryCard` 1×2 / 2×2 / 2×4 服务卡片与下载 Live View（HarmonyOS）。
- **跨源播放实验（2026-10-20）** — 目前仅有 DUMP 权限保护的 debug 原型，没有产品入口、歌单持久化或跨源输出承诺。优先验证 Apple Music → 受控 Subsonic 音源 → Apple Music；每项持有明确的来源账号，不切换浏览账号来交接。旧后端必须由新鲜原生状态确认停止后才能启动下游；不确定或超时即停止调度。MusicKit 的单项结束回调还包含 Stop / Skip，只有归属于本次队列、无主动中断且有真实曲尾进度的最终项终止，才映射 Media3 `STATE_ENDED`；循环播放不走此终态。首次起播与显式恢复位置分别确认。Spotify 先独立验证真实 MediaSession / 前台服务的连接寿命，WakeLock 只作限时对照，不用伪造播放状态保活。

### 🎤 跟唱（Sing）（2026-11-23，Android 先行；2026-11-24 本机路径改为边播边分离、改用 LiteRT，界面上线）

把正在放的歌里的人声压低，按滑杆比例混回去。Android 已上线：界面（Rebecca，`ui/nowplaying/SingControls.kt`）从 Now Playing 封面菜单的「跟唱」进入，引擎在 `player/sing/`，本机分离用 LiteRT（Tensor TPU → GPU → CPU）。不开跟唱时，播放链路行为不变。

- **能用的地方**：Navidrome/Subsonic 的歌，在线播放或已下载都可以（`Capability.SING`，只有 Subsonic 声明）。Spotify、Apple Music 由各自的播放器出声，音频不经过 Yoin 的输出链，所以不支持。
- **来源选择**（`SingProviderSelector`）：Lyra 在线 → 服务端路径（`LyraSingStreamProvider`，取 `/api/v1/sing/{id}/quad.m4a`，服务端不可用时回落本机）；Navidrome/离线缓存 → 本机分离；Spotify/Apple Music → 不可用。
- **唯一的接缝 `SingStreamProvider`**：输入 `SingRequest`（`MediaId` + profileId + 原始流 url + 播放位置），输出 `Flow<SingStatus>`：`Unavailable(原因)` / `Preparing(进度)` / `Ready(SingSource)` / `Failed(原因)`。`SingSource` 有三种：本机分离的人声轨 `Live(track)`（不换流，混音器从原曲里实时减掉人声），四声道文件 `LocalFile`，远端四声道流 `Remote(uri)`（Lyra）。接缝还有 `onPlayhead(请求, 位置, 是否在播)`：跟唱开着时每 0.5 s 和每次 seek 报一次播放位置，本机引擎据此决定先算哪里。
- **四声道格式（Android、iOS、Lyra 服务端共用）**：AAC-LC 四声道，装在 MP4（.m4a）里，声道顺序 CH0=I_L、CH1=I_R、CH2=V_L、CH3=V_R（伴奏、人声），采样率和时间轴与原曲一致。Lyra 服务端地址是 `/api/v1/sing/{id}/quad.m4a`（audio/mp4，支持 Range/206，鉴权与其他 /api/v1 路由一样用 Subsonic 的 u/t/s 查询参数），文件做 faststart。本机用 MediaMuxer 写，moov 在文件尾，本地播放不受影响。AAC 的四声道没有纯“quad”布局，编解码器可能换序或折成立体声：首次使用时 `QuadChannelOrderProbe` 用四个不同频率的音在本机编解码一遍，得到声道映射交给混音器；解不出四声道的设备判为不支持。`androidTest/QuadAacRoundTripTest` 在真机上验证顺序。
- **混音器**（`SingMixerAudioProcessor`，在 `DefaultAudioSink` 处理链最前，ReplayGain 之前），支持 16-bit 和 float PCM，音频线程上不分配内存、不加锁，两种模式：
  - **实时模式**（单声道/立体声，本机跟唱）：原曲照常播放，输出 out = X − a·(1−g)·V，V 来自绑定的人声轨（`SingMixControl.liveBinding`）。**位置**：Media3 1.11 每次 flush 都带 `StreamMetadata`，`positionOffsetUs` 加上 flush 后的帧数就是每一帧在歌里的位置，`periodUid` 必须是绑定那首歌的；不带元数据的 `flush()` 视为位置未知，原样通过。Media3 给的位置不是逐样本准确的（seek 后不再裁编码器延迟、偏移还被 Trimming 处理器减去延迟+填充；MP3 seek 是估算的；Media3 和系统 MediaExtractor 对无缝延迟的计法可能不同），而差 1 ms 人声就减不干净，所以每段流开始（跳过前 60 ms 解码器预热后）截 150 ms 输入，在后台线程用 FFT 归一化互相关在人声轨的混音参考里 ±0.5 s 内找到准确位置（相关系数 ≥ 0.99 才算，重复的副歌一般只有 ~0.98），找到前原样通过。**a（可用度）**：对齐前为 0；之后 V 在的地方为 1，120 ms 线性过渡，并且提前看 120 ms，V 缺一段时先渐出、补上后再渐入，不会突变。sink 中途重新 flush 但位置正好接着的（变速、时间戳重同步）保持对齐不中断。**单声道流**减去 (V_L+V_R)/2；超过两声道（5.1）不处理。
  - **四声道模式**（Lyra 的 quad.m4a，`expectQuad` 时）：输入 [I_L, I_R, V_L, V_R]，输出立体声 Y = I + g·V。
  - g 的变化逐样本沿曲线过渡：滑杆 20 ms 线性；打开跟唱 ~950 ms ease-out cubic（从 1.0 开始，实时模式下在人声第一次真正被减掉的那一刻起步）；关闭 ~750 ms ease-in-out 回到 1.0。
- **滑杆曲线**（`SingGainCurve`，2026-11-24 owner：默认值原来在 62% 处，「超过 50%」太高）：位置 p∈[0,1]，g = 10^(−28·(1−p)/20)，最后 5% 线性收到 0（p=0 静音，p=1 原曲；拐点处 g≈4.7%）。默认 12%（Apple Music 跟唱的比例）落在 p≈0.342，约三分之一处，人声小的一端行程更长（中点是 −14 dB，20%）；增益落在 10%–13.5%（p≈0.286–0.379）时吸附到 12%，界面在吸附时给一下轻触感。这组数（28 dB、5%、0.12）和 iOS 一致。人声大小只在内存里按位置记住（不持久化），改曲线不需要迁移。
- **开关过程**（`SingPlaybackSwitcher`，在 `PlaybackService` 里）：**本机（Live）**不动播放队列，只把人声轨连同当前曲目的 period uid 绑给混音器；混音器第一次真正减掉人声时（对齐完成、播放位置有 V）调用 `SingController.onStreamHandedOver`，界面胶囊和 ~950 ms 的人声滑出同步展开。暂停着时没有声音可减，V 覆盖 [播放头, 播放头 + 4 s] 就交接，滑出仍等开始播放后第一次真正减掉人声；正在播放而 8 s 内没接上也照样交接（兜底）。交接原因（engaged / paused-coverage / timeout）记日志。关闭时等 ~750 ms 滑回 1.0 再解绑；提前解绑混音器也会自己 120 ms 渐回原曲。**Lyra 四声道流**仍是换流：原地替换当前曲目的 uri（id、元数据不变）并 seek 回原位置，从 g=1.0 滑入；关闭时滑回 1.0 再换回原流。DLNA 输出时都不做。
- **切歌**：跟唱保持开启并延续到下一首。新歌在人声轨准备好之前按原曲播放（人声满音量；混音器按 period uid 认歌，旧歌的轨不会用到新歌上），准备好后绑定并同样从 1.0 滑入；不支持的歌显示 `Unavailable`，跟唱仍开着，只有用户退出才关。
- **控制器 API**（`SingController`，界面分支绑定）：`availability`（当前曲目能否跟唱及原因）、`enter()`/`exit()`、`mode`（Off / Preparing(进度) / Ready / Unavailable / Failed；`waitForHandover` 时 Ready 要等 `onStreamHandedOver`）、`vocalLevel` + `setVocalLevel(p)`、`onPlayhead`。owner 定的界面方向（以后做）：长按 Now Playing 封面出现「跟唱」；跟唱时评分条变成人声音量（0–100%，默认 12%，10–12% 附近轻吸附），心形按钮变成跟唱按钮（再点退出），歌词展开，左下角一个胶囊控件。**拖过顶退出**（2026-11-24 owner，取代「在 0% 松手再往左拖」的两步退出）：人声在 100%（原曲）时继续往上 / 往右拖，超过 ~28dp 立刻退出，同一次拖动、不用松手（往上拖过头很少见，不加两步保护），拖的途中胶囊 / 滑杆朝手指方向轻微外倾；往下 / 往左拖到 0% 就停住，永远不退出。歌词胶囊（向右 = 向上）和收起时的竖向人声滑杆（向上）都这样（`SingCapsuleDismissState`）；点麦克风和退出按钮照旧。从 100% 退出时记住的人声回到默认 12%（下次进来不是原曲）；其他位置退出（包括 0%）保持原值。
- **本机分离 Lyra-Sing-Lite**（LiteRT 2.4.0 CompiledModel，2026-11-24 起；ONNX Runtime 已从 App 移除）：
  - **模型走 Play AI Pack**（2026-11-24 owner 定：模型不进 base APK，AI Pack 上线前不合并）：AI Pack 模块 `:sing-model-pack`（`com.android.ai-pack`，packName `sing_model_pack`，按需下载 on-demand），按设备组拆分（`app/device_targeting_configuration.xml`，lyra-sing-lite 官方导出：`Google_Tensor_G6` / `Google_Tensor_G5` 按 `<system-on-chip manufacturer="Google" model="Tensor G6/G5">`，其余设备落到 Play 自动生成的 `other` 组）。每组目录 `model#group_<组>/` 放该组的构建 `lyra_sing_lite.tflite`（G6/G5 AOT 8.3 / 7.4 MB 只跑 NPU；other 是 fp16 4.5 MB，GPU、CPU）和一份 `models.json`（pack、group、每个构建的 id、文件、SHA-256、大小、精度、目标 SoC、加速器），运行时据此知道 Play 给了哪组。Play 会去掉 `#group_` 后缀，App 读 `assets/model/`。按官方导出，G6/G5 组只有 AOT 构建：那里 NPU 打不开时路由没有 GPU/CPU 可退（要退路就把 fp16 也放进这两组并写进 meta，这两类设备多下 4.5 MB，代码不用改）。
  - **取模型**（`SingModelResolver` + `SingModelSource`）：先看哪个来源已经在本机（AI Pack：`AiPackManager.getPackLocation` 非空、meta 和文件大小对得上），否则按顺序取：AI Pack 用 `com.google.android.play:ai-delivery` 的 `fetch` 下载（进度进 `SingStatus.Preparing` 的模型阶段，界面不变），下完逐个验 SHA-256（对 meta 和 `SingLiteRtPlan.TRUSTED`），文件留在 Play 放的位置不复制。需要用户确认（非 Play 安装）、等 Wi‑Fi、失败、取消都按“模型不可用”处理（`SingFailure.ModelUnavailable`），再次跟唱会重新请求。都取不到时（侧载的正式版、没有 Play、pack 不可用）同样干净地报 `ModelUnavailable`。缓存键和设备门槛按“这颗 SoC 应该拿到的构建”命名（`SingLiteRtPlan.expectedSha256`），不必等下载。
  - **打进 APK 的测试包**：`-PyoinSingBundledModels=true`（默认关，Play 上传永远不开）时，构建把 AI Pack 各组复制进生成的 assets `sing/bundled/<组>/`（meta 原样），`BundledSingModelSource` 只取本机 SoC 用得上的构建，首次用时复制到 no-backup 存储并验 SHA-256；有它时先用它，再退到 AI Pack。Yoin Nano、每次提交自动构建的 debug-signed release APK（`scripts/build-debug-signed-release.sh` 已带上）和本地测试包都开着。测试包（debug、releaseDebugSigned）带一个受 DUMP 保护的探针 `SingModelProbeReceiver`：`adb shell am broadcast -f 0x20 -a com.gpo.yoin.debug.SING_MODEL -p <包名> --ez run true` 按跟唱的方式取模型并跑一块，日志在 `YoinSing`，不需要账号。
  - **路由**（`SingLiteRtPlan` + `SingRoutedModel`）：按 NPU → GPU → CPU 排出本机能跑的候选——NPU 只用本机 SoC 的 AOT 构建（API 31+，经 Google Tensor dispatch 库；没有 JIT 编译插件），GPU 跑 fp16 内核且不静默回落到 CPU，CPU 跑 fp16 构建（XNNPACK，线程数 (核数/2) 限制在 2..4）；从不在 CPU 上跑整数构建（int8 会抹掉人声）。第一个能打开的候选开跑；某块抛错或输出里有 NaN / 无穷（GPU 上 fp16 溢出）就关掉它、打开下一个、在那里重跑这一块；一次只开一个后端。CPU fp16 这一步就是原来 ONNX Runtime 的兜底。所有 LiteRT 调用都在同一个工作线程上（GPU 上下文属于建它的线程）；GPU 编译好的内核缓存在 no-backup 目录，下一首开得更快。实际跑在哪个后端（含回落原因，如「GPU (NPU unavailable)」）显示在 Labs 的 On-device model 一行（`SingAcceleratorReport`）。
  - **真机**（2026-11-24）：Pixel Tablet（Tensor G2，无 AOT 构建）走 GPU fp16，每块 0.14 s，点开到 Ready 0.7–0.9 s，已留存的人声轨 8 ms 就绪；owner 的 Pixel（Tensor）走 Tensor TPU，Ready 不到 1 s。
  - 管线与 Prism `lyra-sing-lite`（训练代码的 `torch.stft`）一致：解码一次成立体声 float（单声道复制成双声道，多声道按 −3 dB 折叠并归一），线性相位重采样到 22,050 Hz（复刻 `scipy.signal.resample_poly`），STFT n_fft=1024、hop=256、周期 Hann、center=True 反射填充；去掉直流取 1..512 bin；每 256 帧一块、块起点间隔 160 帧、对称 Hann 交叉淡化（下限 1e-4），输入 [1,4,512,256]、顺序 [L_re,L_im,R_re,R_im]，每块按 RMS 归一（sqrt(mean(x²)+1e-6)）再乘回；直流补 0，iSTFT，升回原采样率得 V（编码器延迟/填充按 Media3 的方式裁掉，V 的第 0 帧就是 Media3 播放的第 0 帧）。本机路径不再编码 AAC，V 保持源采样率（高于 48 kHz 的源也一样），播放时由混音器从原曲减掉。
- **边播边分离**（`LiveVocalSeparator` + `SingVocalTrack`）：Pixel Tablet 上分离约 1.6 倍实时，整首算完再播要等 ~2.5 分钟，所以改成边播边算。X 边解码边用（见下一条「边下边解码」），每块的输入一解出来就能算，X 的单声道参考也随解码写进人声轨；一块一块算：从播放位置“前面”开始——正在播时从“算完时播放头所在处”开始（`SingReadiness.planLead`：取最少的块数 k，使从 k × 每块耗时之后的播放头位置起 V 覆盖 4 s；每块耗时用边播边算的实测滑动平均，跨启动记住，没有实测时用门槛基准 × 2），这样算好时播放头正好走到——往后算到结尾，再倒着补前面；seek 到没算的地方就从新位置重排（之后只要求领先一块的时间）。STFT 在整首上做（反射填充只在真正的首尾），块的边缘和整首一次算完逐样本一致（合成信号 + 真实模型实测误差 2e-10，阈值 1e-5）；降采样按需分块做。某段源采样率的 V 一旦确定（所有覆盖它的块、帧、升采样滤波器的输入都齐了）就写进人声轨。V 覆盖 [播放头, 播放头 + 4 s] 时发 `Ready(Live)`（2026-11-24 从 8 s 降到 4 s：之后继续领先播放头算，万一被追上混音器也只是 120 ms 渐回原曲人声），之后继续在后台算完整首。**人声轨**：每帧三个 16-bit 小端样本 V_L、V_R、X 参考，内存映射文件（4 分钟 44.1 kHz 约 64 MB，在页缓存里，不占堆），按 4096 帧分块，`AtomicIntegerArray` 标记哪块就绪（写入者先写样本再置位，读者先读标记再读样本，无锁无分配）。分离会话在下载和解码的同时打开（两者互不依赖，`SingSessionPool`），算完一首后放回池里，空闲 60 s 或关闭 Sing 时关掉；当前这首已算完、离结尾不到 20 s 时提前为下一首打开。输入输出张量缓冲每个会话建一次、每块复用。人声轨一开始就写在缓存里（`cacheDir/sing-vocal/*.vpart`），整首算完后改名为 `*.vtrack`（LRU，默认上限 1 GB，约 16 首；旧的 `cacheDir/sing` 四声道缓存启动时删掉）。**缓存命中**走同一条实时路径：映射文件，所有块已就绪，立刻 Ready，零计算、不需要模型。
- **边下边解码**（2026-11-24 owner：以前要把整首下载、解码完才算第一块，网上的 4 分钟无损要好几秒）：`SingStreamDecoder` 不再先把文件拷一份，直接经 `SingSourceReader` 读播放器同一条数据源链（下载 → ExoPlayer 的流缓存 → 网络 Range 请求，ExoPlayer 已经拉过的字节不再下载；64 KB 一块，留最近 32 块，往回读或往前跳一点不重连）。**FLAC 和 WAV**（平台 FLACExtractor 吐出的是 raw PCM，seek 后的位置来自帧头的样本号，解码与起点无关、逐样本一致）从听的位置前 6 s 开始解，解到结尾，再回头补开头；分离要的输入不在已解范围、又离正在解的地方远（往前超过 30 s 或在后面）就另起一段 seek 过去。每段的第一帧按第一个输出缓冲的时间戳换算（微秒取整或四舍五入都能换回原样本）；两段接上时重叠的样本逐个比对，不一致说明放错了位置（`SingProgressivePcm.Misplaced`）：丢掉这次的结果，从头顺序解，这个进程以后都不再 seek 解码。**其他格式**（MP3、AAC…：有损解码器 seek 后头几帧不同，MP3 seek 是估算的）从头一遍顺序解，但也是边下边解，分离等到需要的那段解出来就开始，不等整首。每块开算前检查它读到的源样本（`LiveVocalSeparator.inputRange`：窗口、反射填充、降采样按 8192 点成块和滤波器的覆盖范围）是否都已解出；歌曲真实长度要解到结尾才知道，之前按容器给的时长估计，离估计的结尾不到 3% + 15 s 的块先不算（只有最后 ~3 s 的块和长度有关：钉在结尾的那一块、反射填充、窗包络），所以早算的块和整首一次算完逐样本一致；解到结尾发现长度和估计不同，就按真实长度重建人声轨，把完整的块原样搬过去，接着算。读失败（MediaExtractor 把读失败当成文件结尾）先查读取器记下的错误，不会误当成歌曲结束。已经 Ready 后解码失败（比如断网），跟唱保持开着，用已有的人声。日志记：首字节、容器头、格式就绪、解到听的位置、第一块、Ready、整首（解码耗时、段数、读取字节和连接数）。
- **留住算了一半的**（2026-11-24 owner：退出跟唱或切歌后，算了一半的人声轨以前直接扔掉，再进来从头算）：人声轨文件格式升到 2：样本后面跟一张覆盖表，每块 8 字节（标记 + 这块 V_L/V_R 的 CRC-32，不含混音参考，参考随解码另写），一块写完先写样本、再写 CRC 和标记。没算完的轨留在缓存里（`*.vpart`，和算完的 `*.vtrack` 一起按 LRU 计入 1 GB 上限；正在写的文件钉住不淘汰），进程被杀也留着（启动只清 `.part` / `.new` 临时文件）。再请求同一首歌 + 同一模型时：内存里还有这条轨就直接接着用，否则打开文件、逐块验 CRC（标记了但样本没落盘的块——比如断电——算没有，重算）；播放位置后 4 s 已覆盖就立刻 Ready，然后只算缺的块（`LiveVocalSeparator.markFinal`：只跑缺的帧所依赖的块，已有区域边上的块要重跑，因为一帧要它上面所有块都齐），输出仍与整首一次算完一致。全部补齐后盖完成标记、改名为 `.vtrack`。旧版本（1）的完整缓存仍能读。
- **设备门槛**（`SingDeviceGate`）：第一次打开前先用一块静音数据跑一次（先预热一次）；基准是空闲时测的，边播边算时 Pixel Tablet 慢一倍（650 ms → 1 280–1 300 ms），所以按基准 × 2 估算播放中的耗时，超过 1.65 s（每块推进 ~1.86 s 音频，边播边算至少要快于实时，留些余量；即基准超过 825 ms）、或系统温控已到 MODERATE 及以上，就判定“设备不够”（本机路径不再需要四声道解码；四声道探测只和 Lyra 流有关），给出字符串 `sing_device_insufficient`，界面可以建议改用 Lyra。结果按设备指纹和模型记住，温控每次都重新看。（上面的毫秒数是 ONNX Runtime CPU 时测的；LiteRT 下同一规则测路由选中的后端，记住的结果按后端和构建区分，路由换过后端就重新预热。）
- **体积**：只打包 arm64-v8a 的 LiteRT 库（`libLiteRt.so`、GPU 加速器、`liblitert_jni.so`、Tensor dispatch 库，约 10 MB；32 位和 x86 设备太慢，门槛本来也过不了），不带 Tensor 的设备端编译插件；三个模型（约 20 MB）不在 base 里，按设备组从 AI Pack 下载一份（4.5–8.3 MB）。原来的 ONNX Runtime（`libonnxruntime.so` 约 36 MB）已移除，只留桌面版 ORT 作单元测试依赖（`LyraSingModelParityTest` 拿导出的 .onnx 和 Prism 的 Python 输出对齐）。R8 规则保留 `com.google.ai.edge.litert.**`（JNI 按名字构造这些类）。
- **文案**：`strings_sing.xml` 里的状态文案仍是占位，待 Rebecca 写定。

### 📚 Library

- 分类浏览：歌单 / 歌手 / 专辑 / 歌曲 / 收藏。收藏标签只在「收藏」和「资料库」是两回事的服务上出现（Subsonic）。Spotify 的资料库本身就是收藏：喜欢一首歌就是把它加进 Liked Songs，专辑是保存的，歌手是关注的。所以 Spotify 下不显示收藏标签（2026-10-10 拍板），由 `ServiceFeatures.favoritesAreLibrary` 显式标记，不从能力组合推断。Apple Music 没有收藏能力，也不显示
- **歌单的 By You 子胶囊**（2026-10-10 Q8 / D3，Spotify 式）：开着「歌单」时，紧跟它用空间弹簧展开一颗 By You（中文「我创建的」），后面的胶囊跟着同一个弹簧让位；它是叠在歌单视图上的开关，不是新视图，点它只筛歌单列表，All 和搜索不受影响。只有已加载的歌单里既有自己建的、也有别人的才出现，所以永远不会筛出空列表；归属不明的歌单在 By You 下不显示。展开时把自己滚进可视区并避开两端的渐隐，前面的「歌单」一起带进来（2026-10-12 评审修复）：这一排放得下多少就带多少，By You 自己总是完整落下，所以两颗不会互相把对方挤到边外（原先只顾 By You，行被滚过时「歌单」会剩半截压在 ✕ 下面）。要滚的是窄 Wide 头排（详情栏把 shell 列挤到 840dp 左右）——从 All 进来时 ✕ 还在同一组帧里展开、把这一排再挤窄，所以展开期间跟着这一排的宽度一起滚——以及这一排被滚到「歌单」贴边的时候。落定以后这一排归用户，之后再改宽度或拖动都不会把它拉回来。状态在 ViewModel：切胶囊、刷新都保留，切账号复位，不持久化；列表不再混合时胶囊收起、列表回到全部，再混合时按原来的开关恢复。读屏念成复选框。归属是中性 `Playlist.ownedByMe: Boolean?`，和决定能否编辑的 `canWrite` 分开，不升库、不加 Capability：Spotify 比 `owner.id` 和当前用户 id（同步缓存只存 `canWrite`，两者同一个比较，读缓存时就当作归属；你参与协作但别人建的歌单不算）；Apple Music 只认资料库歌单的 `canEdit`（Apple 不给归属字段，系统生成的 Favorites Mix、Purchased 是 false；目录歌单记未知；`canWrite` 仍恒为 false）；Subsonic 比 `owner` 和登录名、不分大小写，没有 owner 记未知（单用户服务器全是自己的，胶囊不出现；Navidrome 的智能歌单默认归第一个管理员，所以管理员账号的 By You 里也有它们）。Spotify 仍最多同步 200 个歌单，By You 只在这批里筛
- **All 和切换胶囊**（2026-10-10 Q12/Q13，Spotify Your Library 模型）：顶部一行胶囊「歌单 / 歌手 / 专辑 / 歌曲」，Subsonic 另有「收藏」。不开任何胶囊就是 **All**（默认）：歌单、歌手、专辑混在同一张网格里，歌手圆形、专辑和歌单方形（同歌手 / 专辑网格的格子，列数随宽度），不放歌曲。开着某个胶囊时，前面用空间弹簧展开一个 ✕；点 ✕ 或再点一次那个胶囊回到 All。刚打开的胶囊把自己滚进可视区、避开两端 24dp 的渐隐（2026-10-12 评审修复）：✕ 展开会把这一排挤窄、所有胶囊右移一个 ✕ 的宽度，手机上 Subsonic 五颗胶囊时行尾的「收藏」会被推出屏外，行被滚过时刚开的胶囊会有一半压在固定的 ✕ 下面。所以它在 ✕ 展开期间跟着这一排的宽度一起滚，落定时再滚一次，之后这一排归用户；已经完整露出、离两端都超过渐隐的胶囊不动这一排。有 By You 时「歌单」不自己滚，由 By You 带进来（见上一条）。选中状态在 ViewModel 里，切账号回到 All。Library 是根页面，系统返回不经过胶囊。冷启动只读歌手（和以前一样）；All 的专辑和歌单在 Library 上屏时才读，哪一类先到就先进网格（冷启动读到的歌手立刻就在），晚到的用条目弹簧挪进来，不等最慢的那一类；什么都还没到时才显示加载，全部到齐仍为空才显示空状态。Spotify 读本地同步缓存，不触发同步：专辑和歌单胶囊随后沿用 All 读到的这批，不再做新鲜度检查（被限流期间有意如此），要等冷启动按 TTL 同步、切账号或资料库变更才重读。改过歌单（加歌、新建、改名、删除）会把整个同步缓存标成过期，所以 Spotify 的 All 不为此重读（那会同步整个资料库），只有「歌单」胶囊和以前一样新鲜重读；Subsonic 和 Apple Music 的 All 照常重读歌单。Apple Music 确认加入资料库（`libraryRevision`）时，Library 不清空、不回到加载、不丢滚动位置（2026-10-11 评审修复）：列表留在原处，加一首歌可能带进它的专辑和歌手，所以歌手、专辑和资料库歌曲在后台重读，到了就替换（专辑按下面「排序」里的规则并入：视图的顺序和批次来的顺序一致就一批批进，不一致就留到读完一起进；整个资料库读完才去掉已不在的）；歌单、各专辑的歌曲和随机样本不重读
- **排序**（同上）：列表第一行，随列表滚走。左边是当前排序名加排序符号，点开是 YoinDropdownMenu，正在用的一项带勾；换排序时条目用 spatialSpring 移到新位置。只在已加载的集合里排，Spotify 每类仍最多 200 条。专辑读整个资料库（2026-10-11 评审修复）：第一批照常先出，满一批就在后台按顺序接着读下一批，同时只有一个读取，切账号或刷新时取消；后面的批次不当着用户的面重排（2026-10-11 评审修复）：Subsonic 的批次 newest 新的在前，就是 Recently added 的顺序，屏幕上的视图（All 或专辑）正按这个顺序排时，新的一批只会落在已显示的专辑后面，上面的不动，读到就并进去。Apple 的资料库专辑文档说按字母序（“Fetch all the library albums in alphabetical order”），但用的是 Apple 自己的排序键：中日文标题、排序名和标题不同的专辑都在靠后的页里，Yoin 的 collator（拼音、假名分段）会把它们排到前面（2026-10-12 真机 LIB-30：第 16 页的張懸《A City》插进了第一行第二格），所以 Apple 的批次不对应任何一种 Library 排序，第一页之后在所有排序下都先留着（owner R1，2026-10-11）。按别的顺序排时（Apple 的所有排序，Subsonic 的 Recents / Alphabetical / Creator），新批次会插进已显示的格子中间、把网格往下推（Apple 每一页里加入最晚的那几张都会顶到最前），所以先留着不显示，等读完、Library 重新进入视野、换视图或换排序时一次并入，只重排一次。并入时排序、All 的混排和滚动条分段随之重算，已显示的不消失，新来的用条目弹簧进场，不重置滚动位置。Subsonic 是 `getAlbumList2` newest 按 offset 每批 500 张；Apple Music 读 `/v1/me/library/albums`（每页 100、带 `catalog` 关系、`dateAdded` 作 `libraryAddedAt`），不再借 recently-added（那只是最近加入的一段），一页就是一批：第一个请求回来 All 和专辑就有专辑，不再等二十来页翻完；Spotify 不变。某一批读失败就停在那里，下次 Library 上屏从那一批接着读。读到不满一批就算读完；另有两道保护，防服务器永远回满批（2026-10-11 评审修复）：满批里没有一张是这次读取没见过的（服务器不理会 offset、每次都回同一批），也算读完；一个集合最多读 10 万张（Subsonic 200 批、Apple Music 1000 页）
  - Recents（最近）：Yoin 本地的访问和播放记录，按 profile + provider 取每个歌手、专辑、歌单最近一次的时间。来源是 activity_events 的 VISITED / PLAYED，加上 play_history 里每张专辑最后一次播放。没有记录的按 Recently added 排在后面，再没有日期的按名字。新设备没有记录时就等于 Recently added。新的访问和播放不当着用户的面重排（2026-10-11 评审修复）：Library 只在重新进入视野时读一次记录、重排一次——切回 Library、从详情页或后台回到前台（resume）、详情列或 Now Playing 侧栏关上后列宽静止变宽；同一时刻的几次触发合成一次（150ms debounce）。其余时间不订阅记录，在别处听歌既不查库也不排序；而且只重排排序是 Recents 的视图，别的视图沿用上次的结果。另外在 Library 里点开的歌手、专辑、歌单也记一笔，用 Library 列出它的那个 id，存在本机 SharedPreferences（`yoin_library_opens`，每个 profile × 服务保留最新 300 条）；搜索结果只在资料库范围里点开才记，服务目录的结果不在资料库里，不记，免得挤掉真正的记录（2026-10-11 评审修复）：Apple Music 的资料库专辑带 `catalog` 关系读，对得上目录的按目录专辑 id 列出，和详情页记访问、播放用的 id 一样；对不上目录的（导入的音乐）仍按资料库专辑 id（`library:l.…`）列出，靠这一笔对上；歌单页本身不记访问（记进 activity_events 会出现在首页动态里），从 Library 点开的歌单靠这一笔进 Recents。仍然对不上的：从首页、搜索打开的歌单只有「从歌单播放」留下记录
  - All 里歌手没有入库时间（三家都不给），就取它在已加载专辑里最新一张的入库时间（先按专辑的歌手 id，没有 id 按名字），这样 Recently added 和 Recents 的兜底里歌手和专辑、歌单混排，而不是全部歌手排在最后。专辑是整个资料库（Spotify 是最新的 200 张），歌手只要有专辑在里面，最新那张一定在。歌手视图不借专辑的时间
  - Recently added（最近添加）：各服务的入库时间 `libraryAddedAt`。Subsonic 专辑用 `created`、歌单用 `created`；Spotify 已存专辑用 `added_at`；Apple 资料库专辑和歌单用 `dateAdded`。Subsonic 的 `addedAt` 仍是收藏时间，留给首页 Recently Added
  - Alphabetical（字母顺序）：快速滚动条的字母表（`LibraryIndex`：系统 ICU，跟随 app 语言），排序和分段用同一个 collator，所以每个字母只有一段：拉丁字母、日文假名行、韩文初声各自分段，数字和符号归入末尾的「#」；API 29+ 汉字按拼音排进字母段（周杰伦在 Z），API 26–28 只在 app 语言是中文时按拼音，否则汉字排在字母、假名、韩文之后、末尾「#」之前。Subsonic 跳过开头的冠词（服务器默认的 The / El / La / Los / Las / Le / Les / Os / As / O / A）。歌单没有滚动条，也用这套顺序，A–Z 在每个视图里一样
  - Creator（创建者）：专辑按歌手，歌单按创建者，歌手按自己的名字
  - 每个视图只露出做得到的：All 和专辑四种都有；歌手只有 Recents 和 Alphabetical（三家都没有关注或入库时间）；歌单有 Recents、Recently added、Alphabetical、Creator（按 owner，2026-10-11 补上），但 Spotify 的 `/me/playlists` 没有日期，所以 Spotify 的歌单没有 Recently added；Spotify 歌单在 All 的 Recently added 和 Recents 兜底里也没有日期，排在有日期的条目后面按名字排。想拿 `/me/playlists` 的原始顺序当「最近」的代理，但同步时数据源已按名字排好再入库（`SpotifyMusicSource.getPlaylists`），缓存表按名字读、没有位置列，拿不到原始顺序，不改 schema 就做不了，维持现状；歌曲和收藏没有排序行（Spotify 的歌曲就是 Liked Songs 的加入顺序）。由 `ServiceFeatures.albumsHaveLibraryDates` / `playlistsHaveLibraryDates` / `sortIgnoresArticles` 显式标记
  - **快速滚动条**（2026-10-10 U2 / D2 / Q13）：All、歌手、专辑的网格贴边一个小把手（`YoinFastScroller`，接线在 `LibraryFastScroller.kt`），歌单、歌曲、收藏没有。刻度跟随当前排序（`LibraryScrollIndexer`）：Alphabetical 是名字的首字母；Creator 是创建者的首字母，没有创建者的在末尾「#」（顺序本来就按创建者的字母排，它的首字母是唯一和网格对得上的索引，「R」就落在 Radiohead 的专辑上；标题首字母在这个顺序里是散的）；Recently added 是入库时间线（跨度不到两年按月，单段超过约 60% 或缺日期超过 20% 时不分段）；Recents 按最后一次打开或播放的时间分段（2026-10-11 评审修复，原先只有把手，默认排序下 D2 的刻度列从不出现）：今天 / 本周（按 app 语言的一周起始日）/ 本月，再往前按月（换到更早一年的那一段刻度写年份），记录跨度到两年及以上时按年；从没在 Yoin 里打开或播放过的排在最后，合成一段「未打开过」。退化规则只看有记录的部分：其中单段超过约 60% 时不分段；没有记录的不算缺日期，所以资料库大半没打开过也照样有刻度；一条记录都没有（新设备）时只有把手。今天 / 本周 / 本月 / 未打开过 走字符串资源（en、zh-rCN），月份和年份走 ICU。刻度太挤时从上往下排（2026-10-12 评审修复）：被上一个挤到的标签往下挪进空隙，最多挪一个最小间距（18dp），且不越过下一个刻度的位置，所以仍在自己那一段上，松手落在它上面仍跳进这一段；挪不下才不画。第一个和最后一个刻度不动，中间的给最后一个留出间距；这样跨度够 18dp 的一段总有标签，不会被上面一段很短的邻居挤掉（比如 350 张专辑的一年被 20 张的一年挤掉）。加粗的是画出来的刻度里最后一个不晚于当前段的。跳到一段时，含这一段第一项的那一行停在顶部；把手拖过列表能滚到顶部的最后一行以后就跳到列表真正的末尾，最后一行完整露在浮动栏上方；列表滚到底时把手正好落在轨道底端（2026-10-12 评审修复，行距也算进一屏的行数）。轨道上沿 = 网格顶 + 8dp，下沿让出浮动栏（同网格底部留白）；把手贴页面自己的 end 边：Compact 以上越过 16dp 页边贴到列边，Wide 分栏时停在 shell 列边、不进 24dp 槽，RTL 在左边；切换视图的交叉淡化不裁切（`SizeTransform(clip = false)`，各视图同尺寸）；淡化按视图大小的图层画，越出视图的部分会被切掉，所以把手越过页边的布局里每个视图都向 end 边伸过页边（`overhangEnd`），内容再用同样的 padding 退回原位，网格、列表和封面位置不变，淡化的图层装得下整个把手，把手在淡出途中仍然完整（2026-10-12 评审修复，原先只剩视图内的 8dp）；Compact 和手机横屏没有外伸。触摸区 32×64dp（图形 24×48dp），只有把手可见时接收触摸，隐藏后点按全部穿透：Compact 以上整块落在 32dp 页边里，不压封面；Compact 和手机横屏页边只有 16dp，把手可见时最后一列封面最右 16dp（其中 8dp 在图形之外）、把手所在的 64dp 高度内的点按归把手。不放宽到 48dp，那样会压住封面 32dp。跳转不经过嵌套滚动（见溶解一节）；每次跳转把分栏开合用的宽度锚点设到这一行起始的那一段（没有就是这一行），列数变了这一段仍在顶部
  - 偏好按 profile × 视图存在本机 SharedPreferences（`yoin_library_sort`），不进 Room 和云同步。自动备份只在 Android 12+ 排除它（data_extraction_rules.xml 只列要备份的）；Android 8–11 不读这份规则，manifest 也没有 fullBackupContent，所有 SharedPreferences 都会进备份，这份也一样（键是 profile id，恢复后跟着数据库一起，无害）
- 普通点击底部 Library：进入当前 profile 的 Library，展示 saved artists / albums / playlists / songs。Spotify 下：
  - 歌曲 = Liked Songs，按加入时间倒序，新喜欢的在最上面；同一秒加入的几首按 Spotify 自己列出的顺序，不按标题；在别处取消喜欢，这一行淡出。列表停在最顶上时，新喜欢的那一行占住第一行淡入、其余的用条目弹簧下移（在别的视图开着时点的喜欢，切回歌曲也一样）；已经往下滚过的列表保持原位（2026-10-12 评审修复）
  - 点一行以 Liked Songs 起播（`spotify:collection:tracks` + `offset.uri`）：Spotify 从这首往下播它自己的收藏，不动用户的队列
  - 歌手只放关注的歌手；在歌手页关注或取消关注，列表随即增减；资料库搜索仍然搜得到已存专辑和喜欢歌曲的歌手
  - 心形和关注改动列表时只重读本地同步缓存，不触发同步请求
  - 每类和以前一样最多同步最近 200 条
- **Subsonic 的歌曲**（2026-10-10 Q9）：服务器没有能分页的「全部歌曲」，所以歌曲 = 各专辑的曲目。专辑按加入资料库的时间倒序（`getAlbumList2` type=newest，一页 10 张），专辑内按专辑页的曲目顺序（逐碟；`Track` 没有碟号，只按曲目号排会把多碟专辑交错）；展开专辑走 `getAlbum`，和专辑页共用详情缓存，同时最多开 3 张，按专辑顺序拼接，同一首只出现一次。代价：展开过的专辑都写进详情缓存，磁盘那份是全部账号共用的约 24MB LRU，一路滑下去会把很久没碰的详情（含其他账号的）挤出磁盘，挤掉的再打开要走网络。滑到离底部约一屏时读下一页，这是 Library 第一个增量列表：同时只有一次读取，刷新或切账号时取消；底部只放一个 YoinLoadingIndicator，读失败换成一个重试图标按钮（不加文字），到底什么都不放。一张专辑打不开，这一页算失败；重试时它再失败就跳过它，不卡住后面的专辑（首页那一页失败时，再点一次歌曲 chip 就是重试）。滑动期间服务器的专辑表会变：下一页从上一页末尾往回多读 5 张，接在已读过的最后一张后面，所以期间删掉不超过 5 张不会漏读；期间新加的专辑（在最上面）不插进列表中间，刷新后出现在顶部。不再是随机样本，所以没有「随机歌曲 / 换一批」标题行；首页的随机歌曲不受影响。点一行从这首起整表播放，取它周围最多 100 首（`startWindowQueue`），不带 context。已知限制（2026-10-11 评审，不修）：交给播放器的只是已经读到的那些行，之后读到的页不会追加进正在播的队列；在下一页到达之前点最后几行，后面只排得上屏幕上那几首，播完按 Repeat All 回到窗口开头。Spotify 用 Liked Songs context 起播、Apple Music 的表是整张读的，不受影响。由 `ServiceFeatures.songsFromNewestAlbums` 显式标记。Apple Music 的歌曲：资料库曲目，按标题字母序，最多 500 首。Apple 给的顺序不是一条字母序（2026-10-12 真机 LIB-29：1–233 行一段 A 开头之后，第 234 行又从 “A Ticket to the Moon” 重新排起），所以读到的这 500 首由 Yoin 自己按标题排，用 Alphabetical 的同一个 collator（不跳冠词，同名按歌手，再按 Apple 列出的先后），点一行起播的队列也是这个顺序；由 `ServiceFeatures.songsByTitle` 显式标记。已知限制：这 500 首是 Apple 顺序里的前 500 首，资料库更大时不一定是按字母的前 500 首
- Library 内普通搜索：默认 scope 为 Current Library，只搜索当前 profile 已保存内容
- 长按底部 Library：支持目录搜索的 profile 打开搜索框并默认 scope 为该服务目录（Spotify：`Search Spotify`、Spotify / Library；Apple Music：`Search Apple Music`、Apple Music / Library）；Subsonic 打开 Current Library 搜索。Apple Music 的 Library scope 调用个人资料库搜索接口，歌曲标签读取已加入资料库的歌曲，不用随机歌曲代替
- 搜索面的筛选（2026-10-10）：范围胶囊（服务目录 | 资料库，只在支持目录搜索的服务出现）和类型胶囊（全部 / 歌手 / 专辑 / 歌曲 / 歌单）放在同一行，中间一条 1dp `outlineVariant` 竖线；这一行全宽出血、可横滑，带滚动感知的边缘渐隐，静止时第一颗胶囊对齐结果列的左边线。类型胶囊只按能力出现，不看当前结果里有没有这一类：歌单要 `SEARCH_PLAYLISTS`（Spotify、Apple Music 声明，Subsonic 的 search3 没有歌单），资料库范围另外要 `PLAYLISTS_READ`。选了某一类先列出这次搜索已经拿到的那一类，切换不另发请求；服务还有更多时（目前只有 Spotify 服务目录，开发者模式每类每页最多 10 条），这一类自己的列表滚到离底部约一屏时自动读下一页，底部显示加载指示，读失败换成重试按钮，已有的行不动（Gemini 审查 1.3，owner 2026-10-21 同意）；「全部」不翻页；「全部」每类最多 40 条，Spotify 资料库范围选了某一类就不再截断（上限是同步快照每类 200 条）。切换用 YoinMotion 淡入淡出，滚动位置按（查询，范围，类型）分别记住；Wide 上全屏搜索收成胶囊再展开时（比如在右列打开详情后点回胶囊），网格回到原来那一行、行内原来的偏移，形变途中按滚过这一行的比例跟着走，不在最后一帧跳（2026-10-12 评审修复）；Library 主网格的宽度锚点不变（见快速滚动条）。Spotify 的资料库范围在本地同步快照里匹配（2026-10-12 评审修复）：只匹配可见的名字——歌手名；专辑名、歌手；歌曲名、歌手、专辑；歌单名、创建者——不匹配流派和年份（2026-10-12 owner 拍板），从不匹配服务的 id（Spotify id 是 22 位随机字母数字，搜一个字母就命中一半）；查询和字段都先折叠拉丁、希腊、西里尔字母的变音符号（含 đ、ø、ł、ħ）再不分大小写比较，假名的浊音符和韩文音节保留。Subsonic 和 Apple Music 由服务端搜索，不受影响。收起搜索、长按快捷进入、回到 Library 首页都回到「全部」；Wide 分栏打开详情时连同查询一起保留；换范围或换服务后不再支持的类型回到「全部」。返回不拦截，仍由官方全屏 Search 一次收起
- **搜索的「全部」页（2026-11-20 owner，#Yoin:#18 原型 v3.1；取代上一条里「全部每类最多 40 条」的竖排列表）**：
  - **结果是首页 Jump Back In 的方块**：三列（更宽的窗口按资料库网格的列数），每个封面后面垫 Material 形状，专辑 Bun、歌曲 Circle、歌单 Ghostish；艺人是三列圆形头像。名字或前缀完全命中时，最上面给一张「最佳匹配」大卡。
  - **分区按命中排序**：歌曲默认在前；名字打全了的那一类（艺人、专辑）浮到上面。
  - **Memories 不单列一区**：
    - 你的笔记和回忆（所有账号的，#178）并进歌曲、专辑结果。本地先出，不等服务。
    - 命中笔记的歌、命中回忆里的字（标题、乐评、专辑日记）的专辑占两格：右半边写时间点或分数 + 回忆标题 + 那句话，命中的字用马克笔高亮。
    - 两格卡和一格卡错落排，规则同首页：有两格卡的一行是「两格 + 一格」，两格卡左右交替。
    - 有回忆的专辑封面戴首页同款回忆标记；Spotify 封面不叠东西，标记放在标题前。
  - **每类最多 4 行、两格卡最多 3 张**（`YoinTokens.Search.ALL_MAX_ROWS / ALL_MAX_WIDE`），超出的走该类的「显示全部」。类型胶囊多一颗 **Memories**，整行宽列出全部命中的笔记和回忆。
  - **相关性**：服务返回、但名字/艺人/专辑里都没有查询字的结果（Spotify 常见），在同一类里还有对得上的结果时收进「显示全部」，并注明「另有 N 条不太相关」；一条都对不上时（比如罗马音搜日文名）照常信任服务。
  - **点击**：
    - 点有回忆的专辑进剪贴簿（专辑页第 2 页）。
    - 点笔记从写笔记的那一秒开始播。
    - 别的账号的先走现有的切换账号提示。
    - 首页 Jump Back In 的回忆 1×2 同样直接进剪贴簿，不再拉下卡组。
  - **实现**：
    - `data/search/`：`SearchMatch` 匹配、`MemorySearch` 读 ledger、`SearchPage` 分区与打包。
    - `ui/library/LibrarySearchTiles.kt`。
- 右上角 ⚙️ 设置入口
- 筛选胶囊与下方网格 / 列表的交界用潮线（2026-10-04 取代 10-01 的曲线 C 网点；2026-09-29 起已取代硬截断）：交界处不盖任何渐变、模糊或色带；两道页面底色的波浪以遮罩形式把内容从交界处挖掉，图形沉进水线，文字在水线以下 10dp 内淡完、不拆成点。曲线 C 的顶部网点在快速滑动时挤成密排圆盘和针孔，有密恐感，所以上面默认用潮线，下面保留网点场；用户可在 设置 › Motion › Scroll edge 换成原版网点或曲奇浪口（见「溶解」一节）。胶囊下的固定间距只留 4dp，网格顶部内边距 8dp
- 五个标签的底部都接底部网点场：封面到栏上方 20dp 才开始碎，栏下和栏两侧是纯网点；文字照常从栏下穿过
- 这对遮罩是通用语言：任何“滚动内容撞上固定 chrome”的交界都应复用（规则见「溶解（Dissolve）」一节）
- **下拉刷新**（2026-11-13，对齐 iOS Library 的 `.refreshable`）：All、歌单、歌手、专辑、歌曲、收藏每个视图都能下拉，空状态也能；搜索面没有。下拉只重读当前视图自己的列表：All 读歌手、专辑、歌单，开着哪个胶囊就只读那一类；没显示的视图不动。列表留在原处，读到了就地替换（条目弹簧进出），不回到加载、不丢滚动位置、不切回 All；这和设置里换账号后的整页重载（`refresh`）是两回事。读不到的列表保持原样，shell 的 snackbar 用现成的「无法加载…」说是哪一类；All 里上次没读到的那一类，这次读到了就并进来。各服务：Subsonic 和 Apple Music 直接重读；专辑从第一批读到整个资料库读完指示器才收起（iOS 相同；Apple Music 的大资料库一页 100 张，要等一阵），读完才去掉已不在的。Subsonic 的歌曲从最新专辑重新读第一页（期间新加的专辑出现在顶部），之后照常滑到底续读；连续两次下拉都打不开的专辑第二次跳过。Apple Music 的歌曲整张重读。Spotify 的列表是本地同步镜像，下拉 = 强制同步一次并等它完成（`refreshSpotifyLibrary(force = true)`），镜像写完由 `observeSpotifyLibrarySynced` 就地换上已加载的各类；被限流期间镜像不动，下拉什么都不变。同时只有一次下拉；整页重载、切账号、资料库变更自己重读（Apple Music 加歌、Spotify 同步落地）时这次下拉结束。指示器是 MD3 Expressive 的下拉 LoadingIndicator（`YoinPullToRefreshBox`：颜色同 `YoinLoadingIndicator`，无阴影；下拉时跟着形变，刷新时转动），从胶囊下方那条交界线往下出现。内容本身不位移，所以静止时潮线没有东西可切，固定的页头和胶囊也不动；收回、落到停驻位、收起都走 `YoinMotion.spatialSpring`。触发时一拍 tick。空状态本身不滚动，只把拖动交给下拉（无回弹）；空歌单页上「新建歌单」不因此收起标签。实现 `LibraryViewModel.pullRefresh`、`LibraryUiState.Content.isRefreshing`

### 💿 专辑详情（2026-10-05：评分徽记 + 第 2 页拼贴手册）

- **色谱顶栏（2026-10-09 owner 定稿，适配稿 artifact 1k9mD3vWBCBVZvDso35Csf）**：一条规则管所有尺寸——**封面离开视野，就化成色谱顶栏；封面回来，色谱就拼回封面**。三样东西读同一个进度：①色谱：封面的复制体切成 28 条竖条，按颜色归队成色带铺满页头，每道色带宽 = 这种颜色在封面里的占比（k-means 取色，每道至少两条；单色封面也拆成深浅两道）；每个颜色保留色相和饱和度，**OKLab 明度锁在 0.30–0.46**，所以白字在任何一道上都 ≥ 7:1，不模糊、不加遮罩（owner：要对抗模糊感）；②蝴蝶：封面后面的两块 Yoin 色块原地不动，跟着上拉碎成细点散掉（和聚焦模式的细点溶解同一种，`dotDissolve`，smoothStep(0, 0.7, 进度)；2026-11-06 owner 定，原型 https://claude.ai/artifact/Va9P7ZE4MkAt4Pd75JRtEP v7，取代原来「跟手飞进顶栏」）；③封面只平移缩放，停在顶栏末端（48dp，横屏 44dp；圆角 Hero 8 → Thumb 4），标题让出它的宽度 + 12dp；小封面可点，点了放下顶栏（Medium 是滚回顶部）。谁推动进度：Compact 和手机横屏是上拉（RevealState），Medium 是封面那一行滚过的距离 ÷ 封面高，Wide 整窗封面常驻身份栏、不形成顶栏。停靠后曲目表直接从页头下沿开始（取消原来的 56dp 直角通栏和 ≤ 5 首时的大胶囊），下沿走潮线。翻到第 2 页时色谱跟着第 1 页滑走，页头文字沿色谱边界一分为二（色谱上白、外面主题色）。**整页只有一张封面（2026-11-06 owner）**：往左翻时封面从它当时的位置飞到剪贴簿的封面那一格——没上拉时从页面正中，上拉后从顶栏的小封面——剪贴簿自己的封面在飞行中藏着，落定才接手；翻回来封面飞回原位（原来在右上角就回右上角）。**翻页不切蝴蝶（2026-11-06 owner）**：Compact 的蝴蝶横向拉宽从 1.8 收到 1.35；它画在一层两页宽的图层里，跟着第 1 页走时伸出页边的那只翅膀整只伸进剪贴簿（不再按页面边框裁出一条竖直切口），剪贴簿落定前碎成细点散掉（smoothStep(0.45, 1, 翻页进度)）。整窗时状态栏图标在顶栏成形过半时切浅色，详情列里不动（状态栏属于整个窗口）。页面两侧不是屏幕边时（详情列），Compact 的两块色块从「出血」改为抱住封面；Medium（新加了蝴蝶）和横屏同样抱住封面；都离页面起始边 ≥ 8dp，绝不在列边上切。减少动态：竖条不飞、色谱原地淡入，封面原地淡出、小封面淡入。主题色先当临时色谱，封面取色好后用效果弹簧过渡。飞行的封面画在页头和 pager 下面一层（封面组件的 Surface 会吞触摸，必须让上拉手势先到页面）。实现 `ui/detail/AlbumSpectrum.kt`（纯函数 + 单测）、`AlbumSpectrumBar.kt`（几何、绘制、飞行封面），`AlbumDetailScreen.kt` 只做接线。
- **两页**：专辑页是两页的 pager，第 1 页是 hero ⇄ 曲目表的上拉两态，第 2 页是**拼贴手册**（scrapbook，D3，owner 2026-10-05 批准并改了几处）：把用户在这张专辑上留下的东西按曲目顺序贴在点阵纸上。全页扁平无阴影，各件靠纸色、字面和角标区分；倾斜只在 graphicsLayer 里（布局和点击区仍是方的），角度来自专辑 id + 这一件的 key 的哈希，所以重开永远一样；拖动翻页时各件按深度视差、多倾一点，减少动态时关掉。返回不变：第 2 页只是页内 pager 状态，从这里返回和第 1 页一样直接离开专辑页，不加返回处理。平板横屏的详情列同样有第 2 页和页点。实现 `ui/detail/AlbumScrapbook.kt`（模型 + 纯函数 builder）、`AlbumScrapbookPage.kt`；数据 `data/album/AlbumScrapbookSource.kt`（评分、笔记、播放按 profile + provider 过滤，不改表、不升库；About 表本来跨 profile 共享，按专辑名规范键查）。
- **评分图形 = 共享的 `ScoreEmblem`**（来自 Memories 的唱片刻纹徽记，见「评分与视觉形状系统」）：第 1 页 64dp，替换原来的 Bun——旁边的 "Based on X/N" 照旧只在不是手动专辑评分时出现，点徽记照旧打开评分与乐评面板，提交评分时的 bloom 保留；第 2 页 64dp（宽屏 72dp）挂在封面右下角，向右、向下各探出自身的 30% / 24%（和 Memories 卡片一样），翻到第 2 页停稳后用徽记自带的获得动画「盖章」一次（每次打开专辑页只盖一次，不震动），停稳前是未刻的状态。两页颜色都走 NP 的封面取色路径（`rememberScoreEmblemColors`），一张专辑在所有地方是同一枚徽记。艺术家页不放这枚徽记，也没有 Last Play，Discography 每行也不显示专辑分数（2026-10-10 owner 删除，见「评分与视觉形状系统」）。
- **评分与乐评面板（2026-10-06 owner R1–R4 拍板，`AlbumRateSheet`）**：左上角是一行小字「Rate」和专辑名（titleLarge SemiBold，左对齐，最多三行），右边并排 120dp 的徽记（2026-10-06 owner：只有徽章太空，标题放左上角、和徽章并排，像页面上 Last Play 挨着徽记那样），没有说明文字。下面是 NP 同款横向 `RatingSlider`（0.1 一格，填充用封面配色）——**不转徽记**（owner：转盘手势像 Google 旧闹钟，被抱怨），滑条在面板里要先横向越过触摸阈值才算拖动（`claimOnDown = false`），从滑条上开始的竖向滑动归面板（滚动或下拉关闭），不改分；轻点照样打分。拖滑条时徽记跟着分数实时变，并随拖动抬起 6%、按离 5 分的距离倾斜 1.4°/分；松手即保存分数，徽记用获得动画「盖章」一次（分档动画 + 触感）。然后是一个输入框：平时是 56dp 胶囊（「Say something about it…」），写的时候长成 20dp 圆角卡片，**随字数一直长高、框内不滚动**（owner 10-06：原来限高 168dp，字在框里上下被截断）；面板整体滚动，打字时自动把光标和下面的计数行留在键盘上方，滚出面板上下边的内容渐隐（`verticalEdgeFadeOnScroll`）；徽记上方在滚动区里留 14dp，10.0 的盖章光环不被裁。有字后小字标「Short comment n / 360」或「Review n」。没有 Save 按钮，关面板即保存。最后一行是 NeoDB 状态：未登录「Sign in to sync」（点了进设置的 NeoDB 一节）、有改动「Syncs when you close this」、「Syncing…」、「Synced」、失败「Couldn't sync · Retry」（点了重试，失败另发 snackbar）。**登录 NeoDB 后关面板自动推送**；书架状态不显示（打分或写字即「听过」，NeoDB 上改过的保留）。Yoin 只有一段文字，推送时按长短归位：360 字以内写进 Mark 的短评并删掉远端 Review，更长发成 Review 并清空短评，清空文字两边都清；拉取时 Review 优先，没有才取短评（`NeoDBSyncService`）。
- **乐评**只靠颜色区分：专辑色的 primaryContainer 纸、GSF（用户的字，2026-10-07 起不再用系统字面），**不加「Your review」标签，也没有竖线**；没有乐评时是一张空白剪报 "Write a review"（v1 第 2 页只有乐评能写）。**竖线这个视觉符号只留给单曲笔记**：单曲笔记是中性纸 + 日记竖线 + 每行时间戳，一张剪报最多 3 条，其余收进 "+N notes" 面板；**点笔记行打开这条笔记**（2026-11-24 owner：不再直接播放）：单曲面板翻到笔记页，这条笔记滚进视野并描一圈墨色边，点它就是「修改 / 从这里播放」——播放成了一个明确的动作。笔记里的提及照常可点。
- **专辑笔记**（2026-11-22 笔记改版：一张专辑可以有多条，取代 10-05 的「最多一条」；数据层 phase A 已能写——`YoinRepository.addAlbumNote` / `editAlbumNoteById` / `restoreAlbumNote`，剪贴簿走 `ScrapEdit.NewAlbumNote` / `AlbumNoteText` / `RestoreAlbumNote`，`AlbumScrapbookData.albumNotes` 读全部；页面上一条一张便签、点纸写新便签是 phase B）：phase B（2026-11-23）起一条一张便签，按创建先后排，最后是一张虚线便签（点它或点纸上空白处，就在原处打开同一个编辑器写新的，能 @）；点一张便签原地编辑，清空保存就是删除，底部「已删除专辑笔记 · 撤销」。Memories 日记也列出全部专辑笔记。做成便签：secondaryContainer 底、16dp 圆角、右下角 17dp 折角（折角色 = 便签底色混入 32% 的 secondary）、GSF（用户的字），**不带标签也不带竖线**（TalkBack 读 "Album note: …"，读的是去掉标记的纯文字）。位置在开篇簇里、封面和乐评下面，和乐评反向倾斜：手机上单独一行靠右，宽 = 可用宽度的 72%、最多 280dp，紧跟封面时离徽记留 6dp（盖章时徽记会放大），乐评太长落到封面下面时压住乐评底边 6dp；宽屏压在乐评那一列下面、缩进 24dp。收据的 "Notes" 计数把它算进去。iOS（笔记 phase 1b，2026-11-24）同样一条一张便签、按创建先后排、虚线便签和点纸上空白处写新的、点便签原地编辑、清空保存即删除并「已删除专辑笔记 · 撤销」，便签用同样的纸色、折角和倾斜（手机一行靠右，72%、最多 280pt，压乐评 6pt）；编辑暂为纯文本（@ 编辑器和格式行是 phase 1c），没改的字原样保留（提及和 Markdown 不会被改写），待办暂不可勾。
- **笔记的文字格式（2026-11-22 笔记改版 phase A，原型 notes-proto 获批）**：单曲笔记、专辑笔记、专辑长评在库里仍是纯文本，带轻量 Markdown 和提及：`# ` 标题（`##`/`###` 也认）、`- ` 列表（`* `、`• ` 也认）、`- [ ] ` / `- [x] ` 待办、`**粗体**`、`_斜体_`（`*斜体*` 也认），提及写成 `[@名字](yoin:<kind>/<provider>:<id>?a=<艺人>&al=<专辑>)`，kind 是 track / album / artist（旧原型的 song 读成 track），provider 是 MediaId 的 provider，id 和参数值做百分号编码，名字里的 `[` `]` 用反斜杠转义（名字常带括号）。解析器和投影都在 `:shared`（`shared/notes/NoteMarkup.kt`，iOS 可直接用）：读不懂的部分一律原样当文字，不丢字；读取从不改写库里的文字。纯文字投影（`NoteMarkup.plainText`）给搜索、无障碍描述、首页卡片评语；推 NeoDB 时长评（Review 正文是 Markdown）用 `NoteMarkup.toNeoDb`：只把提及换成名字，其余 Markdown 原样；短评（`comment_text` 不渲染 Markdown）用纯文字投影；长短按纯文字的字数算。拉回来时远端若正是本地的投影（长评比 Markdown 投影、短评比纯文字），保留本地原文，提及和格式都不会被一次 pull 冲掉。斜体的 `_` 只在挨着 ASCII 字母数字时不算（snake_case 照旧），`*` 只在挨着 ASCII 数字时不算（2*3*4 照旧），所以中文不加空格也能写斜体。提及解析（`data/notes/MentionResolver.kt`）：和当前账号同一 provider 的按 id；别的 provider（如 Subsonic 账号里的 Spotify / Apple Music 提及）按「名字 + 艺人（+ 单曲的专辑）」对当前账号本地知道的曲目和专辑（播放记录、笔记、专辑笔记）匹配，规则同歌单迁移的 `TrackMatcher`；对不上的提及显示成正文颜色的普通文字（不是灰色），不可点。被提及（`data/notes/NoteBacklinks.kt`）：同一账号里提到某张专辑（含它的单曲）、某首歌或某位艺人的单曲笔记、专辑笔记、长评，按时间倒序，带片段和日期；这张专辑自己的笔记和长评不算；同服务按 id 算，Spotify / Apple Music 同服务 id 对不上时也按名字算（`library:` id 和目录 id、豪华版 / 地区再版），Subsonic 只认 id；不建索引表，`LIKE '%](yoin:%'` 预筛后解析。按名字解析时先用标题前缀 `LIKE` 快速找，找不到再把当前账号知道的全部曲名 / 专辑名交给 `MentionMatcher` 比一遍（SQLite 的 LIKE 只折叠 ASCII 大小写，繁简、重音、全半角要靠后者）。专辑笔记随云同步走（`album_note`，见 `docs/cloud-sync.md`）。**phase B 界面（2026-11-23）**：所有显示笔记 / 专辑笔记 / 长评的地方（NP 笔记行（**收起的 NP 里提及不可点**，2026-11-24 owner：笔记区任何地方点一下都只是展开 NP）、全部回忆的专辑乐评卡和单曲笔记卡（2026-11-24 起不再显示原始标记）、剪贴簿笔记行 / 便签 / 乐评、第 1 页乐评、Memories 日记和全部回忆）都走 `NoteMarkupText`：提及读窗口级的 `NoteMentionController`（`YoinActivityRoot` 提供），解析出来的是主题色、可点；**2026-11-24 owner「看着像普通粗体」**：解析出来的提及读作链接但不喊叫——名字前一个和字一样高的小封面（单曲 / 专辑是封面，圆角约 24%；艺术家是圆形头像；还没有图时是这类的 Yoin Symbol 放在提及色的淡底上；封面由 `YoinRepository.mentionArtworkUrl` 按需取一次：单曲先看自己的播放记录，再问服务；专辑、艺术家走详情缓存），封面和名字一起坐在一颗提及色 12% 的圆角胶囊上（按下加深），不加下划线；胶囊是一整块，绝不在里面断行（2026-11-24 平板 QA：封面留在上一行、名字掉到下一行，或「林 / 间」被切开）：放不下就整块换到下一行，比一整行还长时名字在胶囊里截成省略号，封面始终跟着名字；**长按提及**弹出一张小卡（封面、名字、艺术家、专辑），手指按着时一直在，松手就收，不会打开任何东西，也不会触发外层的长按（`ui/component/NoteMentionPress.kt`）；点专辑开它的剪贴簿，点单曲开它所在专辑的剪贴簿并高亮那首（`ScrapbookFocusRequest`），点艺术家开艺术家页；对不上的是正文颜色普通字、不可点。待办前的 ☐ / ☑ 可点，勾选直接存（单曲笔记、专辑笔记；乐评暂不）。编辑器（`ui/notes/editor/`）在 NP 写笔记栏、剪贴簿便签、评分与评论面板、Memories 日记的写评论处共用：存的仍是原文，`TextFieldState` + 输出变换把提及画成一整块「@名字」底色胶囊、列表画成「•」、待办画成「☐ / ☑」，标记符号淡显、内容加粗 / 斜体 / 标题；输入变换保证提及是原子的（退格整块删掉，在里面打字落到它后面），回车续列表、空项回车退出列表，行首「[] 」「【】 」「[x] 」「＃ 」变待办 / 标题。输入「@」（行首或非字母数字之后）在光标旁弹出提及框：空着时是正在播放的歌、它的专辑和艺术家、最近播放；**打字后每个字都立刻过滤**（2026-11-24 owner：「@N」要把 New York 排第一）：先在已经给过的行（正在播放、最近播放、之前的搜索结果）里马上排，再把当前账号的搜索结果排进来；排序是标题开头对上 > 首字母对上（New York = ny）> 标题里含有 > 艺术家对上，同一档里正在播放、最近播放在前，然后是服务的顺序；服务找回来但这里对不上的留在最后；最好的那一类（单曲 / 专辑 / 艺术家）排在最上面。中文名也按拼音比（全拼 niuyue、首字母 ny），拼音输入法还在组字时（「@niu'yue」「@ny」还没上屏）就能找到「纽约」：组字区里的空格和撇号不算结束（`MentionRanking`；拼音用系统 ICU 的 Han-Latin，Android 10 起才有，更低的系统只按字面比，不打包拼音表）。中文键盘打出的全角「＠」和「@」一样（Gboard 中文的拼音只在它自己的候选栏里，不进输入框，所以要等汉字上屏后才按汉字匹配，如「＠灯塔」）。空格（已上屏的）、Esc、删掉「@」结束提及；软键盘的回车在提及框开着时选最上面（或高亮）的那行，不换行——不管键盘怎么送这个回车：单独一个换行、把正在组的词（或自动更正后的词）和换行一起提交、用换行替换正在组的词，或者走编辑器动作（IME action），都算；提及框的位置不挡正在写的那一行：有地方就放在整个输入框上面，否则放在光标那一行上面，再不行放在它下面；它始终在状态栏和键盘顶边之间，两边都放不下整框时，列表缩到较宽裕的那一边；点软键盘（或框外任何地方）不会关掉它——提及只在光标离开「@」那个词、输入框失焦、点了这个窗口里输入框以外的任何地方（输入框外面那层装饰框——圆角底、内边距、标签和字数行，如评论面板的「短评」行、NP 写作条整张卡片——算在输入框里面，`Modifier.noteEditorContainer`；空白背景、笔记列表、空的工具栏也算，不需要它们能拿焦点：每个 Yoin 窗口——Activity 根和底部面板——在最外层听所有按下，不吃掉，`ui/notes/editor/WindowTaps.kt`；键盘和提及框是别的窗口，它们的触摸不会传到这里）、Esc 或选中时结束（2026-11-24 平板 QA：点第一个键就把它关了）；编辑器里的「@名字」胶囊也不在中间断行（显示文字用不换行空格和连字符号连起来，库里存的不变）；↑↓ / 回车 / Tab / Esc 可用键盘；「展开搜索」打开整页搜索面板，选中的插回 @ 处。写字栏底部在「保存」旁有格式行（@、B、I、H、列表、待办，M3 Expressive 切换按钮，按下形变；Yoin Symbols 没有列表符号，暂用「•」字形）。 摘录式的卡片（Memories 卡面的引文、首页 Rediscover 单曲卡的一行笔记、JBI 笔记 1×2）用纯文字投影（`NoteMarkup.plainText`：提及只留名字，没有标记）。
- **被提及（2026-11-23，原型场景 4）**：剪贴簿最后一块。同一账号里提到这张专辑或其中单曲的笔记、专辑笔记、评论，每条一张剪报：来源封面、名字、「笔记 · 1:23 · 3 天前」、一行片段（提到这里的名字用马克笔底色标出）；点一张打开它写在的那张专辑的剪贴簿（单曲笔记会高亮那首）。实现 `ui/detail/AlbumScrapbookNotes.kt`。
- **其余各件**：单曲分数不用小印章，直接把数字用专辑色印在票根上；全专最高分（≥ 9.0）的那张票根整张用 primary，写 "Best on the record"，每张专辑最多一张。只有分数的曲目两张一行做小票根；票根上时长和播放次数是两组（数字重、单位轻，不用分隔符），放得下就排一行；放不下时播放次数换到时长下面一行，票根跟着变高，两样都不丢（2026-10-08 owner；取代 10-05「去掉播放次数只留时长」）；什么都没留的曲目进 "Not yet" 空模子，点了播放。Ask / About 是 tertiary 索引卡（GSF、"Q" 角标，问答卡不再写 "Asked"），"About" 和 "Not yet" 两个标签保留。收据（在 Yoin 里的播放）只在专辑在 Yoin 里播放过时出现。第 1 页的「上次播放 / 均分 / 哪些曲目评过分」和第 2 页读同一条数据，跟数据库实时更新。
- **单曲面板：在剪贴簿里改评分和 AI 内容（2026-10-29 owner「增加管理剪贴簿里的 AI Generated Content / 评分的编辑功能」）**：长按任意票根（大票根、两张一行的小票根都算；TalkBack 是「评分和编辑」这个长按动作），或点这首歌的问答卡、About 卡、事实标签，从底部升起同一个单曲面板（`AlbumScrapbookTrackSheet.kt`，取代原来只读的全文面板）。顶上是「07  歌名」，然后是「评分」和 NP 同款横向 `RatingSlider`（`claimOnDown = false`，同专辑评分面板），松手即保存；有分数时右边有「清除评分」。下面是这首歌的每个问题和每条 About 事实的全文，各带一个铅笔：点了原地换成编辑框（问题本身是用户的原话，不改；能改 Gemini 的标题和回答，事实只改内容），「保存」「取消」和红色「删除」；一次只开一条。回答清空再保存等于删除。删除后这一条立刻消失，底部弹出「已删除 · 撤销」（M3 snackbar，约 4 秒，owner 2026-10-29：和 Google 相册一样）：面板开着时在面板底部，关了就在页面底栏上方；点撤销把原来的标题和回答写回去。问题按第一次提问的时间排（`createdAt`），所以改过或撤销回来的问题留在原来的位置。删除不是真删行，而是把 `answerText` 写成空：About 表的删除不同步（`SongAboutSyncAdapter`），真删会从 Drive 回来；空回答会同步，所有读者都把空行藏起来（剪贴簿、NP 的 About、Memories 的提问计数），在 NP 里再问同一个问题会填回来。评分清掉或最后一条问答删掉时这张票根可能离开页面，面板保持最后的内容直到关上。
- **单曲面板 v2（2026-11-02 owner 同意原型 v3，取代上一条的版式；删除即撤销、snackbar 样式不变）**：顶上是「07  歌名」和一颗只写数字的分数胶囊（没有「评分」两个字；没分数时是「—」），点胶囊才展开横向 `RatingSlider` 和「清除」。下面是和 NP 展开页同一个按钮组（`ui/component/YoinTabGroup.kt`，NP 的 `FullscreenTabGroup` 也改成用它）：「关于 | 笔记 · N」。
  - **关于**：事实、问答、自己写的条目各带铅笔，长按整条也直接进编辑。编辑框下面是「删除 / ✦ 重新生成 / 完成」；重新生成时这一条换成三个跳动的点，回来后弹「已重新生成 · 撤销」，撤销写回旧的标题和回答；自己写的条目没有重新生成。最下面是新建：先写标题，标题下两个按钮「问 Gemini」「输入」；点「输入」展开正文框、按钮变「保存」，存成 `kind = own` 的行（只是用户自己的字，不会被重新生成）。标题框为空且聚焦时，上面出一排快捷提问（`cmp_quick_prompts`：采样了谁、谁采样了它、歌词在讲什么、怎么写出来的、有哪些翻唱、当时反响），这首歌已经问过的、或已经用作自己条目标题的不再出现；点一个填进标题框。这一排横滑到卡片自己的边缘（越过卡片内边距），两端在还有更多时渐隐，不在卡片中间硬切（2026-11-04 平板横屏发现）。
  - **笔记**：每条笔记嵌在歌词里：它那一行的上下各三行，挨得近的段落合并，段与段之间是「·  ·  ·」；「展开全部歌词」把所有笔记插进全文。点笔记是「修改 / 从这里播放」，点歌词是「新建笔记 / 从这里播放」（新笔记记在那一行的时间）。笔记改空即删除，弹「已删除 · 撤销」。歌词只在第一次切到笔记页时读（只用带时间轴的歌词）；没有时间轴歌词时只列笔记，另有「新建笔记」按钮（不带时间）。不写操作说明：没有常驻提示行，没有笔记时只有「还没有笔记」（和 NP 笔记页同一条规则，2026-10-08 owner）。剪贴簿页上的「+N 条笔记」直接打开这张面板的笔记页。
  - **NP 关于页长按**：展开后的关于页，长按任意一条（事实、问答、自己写的条目），从底部升起和歌曲长按同款的面板（`AboutEntrySheet.kt`）：顶上是这一条的标题和前两行，下面「在剪贴簿里管理」（这首歌有专辑页才有：留一个带这一条的 `ScrapbookFocusRequest`，打开专辑翻到剪贴簿，面板直接停在这一条的编辑框）和「用 Gemini 重新生成」（自己写的条目没有）。重新生成同上：三个点，回来后 NP 的 snackbar「已重新生成 · 撤销」。
  - **问 Gemini 快捷提问**：NP 的问 Gemini 输入框聚焦且为空时，底部出同一排快捷提问（已经问过的不出现），点一个填进输入框，键盘的发送键提问。
- **读失败**：第 2 页的数据读失败时不会一直停在 Loading——还没读到过数据就先显示空状态，读到过就保留上次的内容（第 1 页的分数和播放信息也不变）；随后按 1 / 2 / 4 / 8 / 16 秒自动重试 5 次，成功后下一次数据到达就替换，5 次都失败就停在当前内容，错误被拦下、不崩溃。第 2 页没有手动「重试」按钮；专辑本身加载失败时仍是带 Retry 的错误页。
- **保存到资料库（Q11，2026-10-10，只有 Spotify）**：▾ 菜单在「Add to playlist」后面多一行开关：「Save to library / 保存到资料库」⇄「Remove from library / 移出资料库」（图标 LibraryAdd / LibraryAdded，走 `PlayMenuItem`，详情列的合体栏菜单也有）。写操作是 `PUT` / `DELETE /v1/me/library?uris=spotify:album:{id}`，复用 `mutateLibrary`，经过 `SpotifyRateLimitGate`。门控是 `Capability.ALBUM_SAVE`，只有 Spotify 声明；Subsonic（专辑收藏走星标）和 Apple Music（只能逐首加入资料库）没有这一行，也不发请求。
  - **初始状态**：先看已保存专辑镜像（`spotify_library_album_cache`，最新 200 张）：在里面就是已保存，不问。不在里面时，专辑 URI 排在这页曲目 contains 查询的第一个，和曲目一起问。
  - **状态未知时没有这一行**（2026-10-10 评审）：镜像里没有、Spotify 还没答到（没问、在途、失败，或限流门关着没问）、也没有 Yoin 自己的写入时，状态是「未知」，▾ 菜单里不出这一行，既不会对已保存的专辑再存一次（可能把它在 Spotify 里的加入时间顶到最前），也不会在限流时给出注定失败的按钮。答复到了这一行才出现；菜单开着时它用 spatial spring 展开、effects spring 淡入，不硬切（菜单宽度若因这一行变宽会直接变，菜单是共享组件，没动它）。状态已知但限流门关着时照常显示，点了失败回滚，提示「Spotify 正忙」，和心形一样。
  - **请求数**：专辑不额外占请求的前提是曲目给它留了位置。两种情况会多一个只为专辑的请求，属于有意保留：① 这次要问的曲目数正好是 40 的倍数（40、80…），专辑挤到下一批；② 这张专辑的曲目 30 秒内都已问过（比如 Now Playing 刚问过正在放的单曲），专辑只能单独问。不问的话这一行就一直出不来。Yoin 自己的保存或移出还在途、或落地不满 60 秒宽限时不问：这时 Spotify 的答复反正赢不了本地写（移出会删掉镜像行，以前回到前台会为它单独发一个没用的请求）。
  - **点了之后**：立刻翻转（仓库里的写入中状态），失败回滚，并通过窗口现有的 snackbar 说原因（限流是「Spotify 正忙」，其余是「无法保存到资料库 / 无法移出资料库」或连接类提示）。连点时按顺序写，最后一次为准。成功后镜像当场更新：保存插到 Recently added 顶部，移出删掉那一行，不把整个库标记过期（重同步要翻四个列表）。数据源的已保存专辑列表另叠一层增量（和 `SavedTrackDelta` 同一机制），等 Spotify 的列表跟上再撤掉。
  - **状态优先级**和心形一样（`YoinRepository.observeAlbumSaved`，返回 `AlbumSavedState`：不支持 / 未知 / 已保存 / 未保存）：写入中 > 60 秒宽限内刚落地的本地写 > 远端答复和镜像行（比时间戳）> 未知。只在内存里，切账号清空（回到未知，等这页再问）。
  - **Library 当场跟上**（2026-10-11 评审修复）：写入落地、镜像更新之后，仓库按 profile 发一次 `libraryAlbumsRevision`。Library 收到后只从镜像重读专辑（`getSpotifyLocalSearchSnapshot`，不做新鲜度检查、不触发同步、不发请求），All 和 Albums 里保存的专辑用条目弹簧进场，移出的淡出；资料库范围的搜索也以镜像为准，屏幕上有资料库搜索时重跑一次，移出的专辑不再留在结果里。没有借 `libraryRevision`：那会让 Library 整库重读，Spotify 读前查新鲜度，超过 1 小时就整库重同步（最多 16 页），限流期间不允许。Home 的 Recently added 仍要等它下次读取才看到。

### 🎤 艺人详情

- **开口环（2026-11-06 owner 定，取代三片叶子的风车；原型 https://claude.ai/artifact/Va9P7ZE4MkAt4Pd75JRtEP）**：头像外面一道很粗的圆头环，开口在右上（中心 −45°，开口 0.9 弧度），开口处坐一个小圆点；环取头像的 primary，点取 tertiary，effects spring 过渡，不描边、不留白缝。尺寸按「环外沿到头像中心 = 头像的几倍」定：手机竖屏 0.84（216dp 头像，带子高 = 头像 + 150dp 正好放下）；横屏 0.62（不进胶囊带和上方滚动边）；Medium 0.62、Wide 0.78（不出窗格、不碰右边的名字栏）。头像以外的空间里缝占 41%、环占 59%，点的半径 = 环宽的 0.45。环随页面滚动慢慢转（每滚 1px 转 0.06°），滚走时在 220dp 内碎成细点散掉（`dotDissolve`）。减少动态：不转、不溶解，跟页面一起滚走。实现 `DetailBackdrops.kt` 的 `ArtistOpenRing`。

### 🎼 歌单详情

- **重命名立即生效（2026-11-13，对齐 iOS 的 `PlaylistActions.rename`）**：在重命名对话框里确认后，页面上的歌单名当下换成新名字，不等服务回应；写入随后发出，页面这时关掉也照样发完。服务接受：snackbar「已重命名」，页面重读一次歌单（名字以服务为准，Spotify 的 snapshot 一起更新），Library 照旧重读歌单。服务拒绝：名字换回服务还保留的那个，snackbar 和以前一样（服务给了原因就显示原因，否则「无法重命名播放列表」）；这时页面上已经是更新的名字（又改了一次，或重读过）就不动它。连着改几次时一条一条按顺序发，后一条被拒绝时回到前一条实际落定的名字。名字没变或只有空白时不发请求。名字本身的显示没有改动（和重读后换名一样）。**删除仍然等服务回应后页面才离开**：独立 Activity 的 snackbar 跟着页面一起消失，页面先关的话，被拒绝就没有地方说，Library 里那一行也还在（Spotify 的 All 本来就不为歌单变更重读）；要做到 iOS 那样「页面立刻关、Library 的行立刻走、被拒绝再回来」，需要 Library 那一行的即时增删和 shell 一级的提示，另行排期。实现：`PlaylistDetailViewModel.rename`，单测 `PlaylistDetailViewModelRenameTest`。
- **封面后面的两张专辑封套（2026-11-06 owner 定稿，原型 https://claude.ai/artifact/Va9P7ZE4MkAt4Pd75JRtEP）**：歌单封面后面不再画叠起来的 V 形，改成这张歌单自己收录的两张专辑封面，扇形叠在封面后面，像一小摞唱片封套。选哪两张：从第一首往下找，用曲目行同一个封面地址（320px，图片缓存里已经有，不发新的 API 请求），只留不同的封面，跳过和歌单封面同一张的；歌单没有自己的封面、用第一首的封面顶上时，也跳过第一首的专辑；最多两张。一张都没有就不画，只有一张就只叠在左边（RTL 在右边）。挑选是纯函数 `playlistSleeveCovers`（`ui/detail/PlaylistSleeves.kt`，有单测），在 ViewModel 里算好放进 `sleeveCoverUrls`。每张封套是封面边长的 0.86 倍，以封面中心为准左右各偏 0.20 倍边长、向下 0.07 倍，左边那张向左倾 6.3°、右边那张向右倾；圆角和封面一样，扁平，没有阴影和描边。按 Yoin Symbols 的叠层规则，前面一层压住后面一层的地方留一道页面底色的缝（宽 max(3dp, 边长的 2.5%)），封面和前面那张封套各有一圈，只画在压住后面那张的部分，所以三张读成分开的三层，页面底色上也不会多出一圈光晕。封套任何时候都整张画完，不被页头或屏幕边切；地方窄时缩小左右的偏移，离所在区域的边至少 8dp：手机竖屏按封面到页边的距离；横屏按封面起始边的 44dp 和到右侧信息的 40dp 里窄的那个；Medium 列表会裁两侧，所以有封套时封面往里让 40dp、和右边信息的间距也多 40dp（封套多了或没了时用空间弹簧挪动）；Wide 把原来的 40dp 起始边距和到曲目表的 40dp 间距挪进身份栏的滚动列里，封套能伸进去。动效：进场跟着封面的分段启幕，封面落下后左边那张、再隔 50ms 右边那张从封面后面展开（缩放 0.6 → 1，偏移和倾角从 0 到满），Expressive 慢速空间弹簧，返回时不重放。手机竖屏上拉时封套留在原地，按 smoothStep(0, 0.7, 进度) 碎成细点散掉（和专辑蝴蝶、艺人开口环同一种消失，2026-11-06 owner 定），封面那圈缝在封面一离开就淡掉；画在色谱上面、飞行封面下面。横屏上拉同样碎成细点，hero 一起淡出；Medium 封套在封面那一行里，跟着列表一起滚，碰到潮线时和封面一起化成网点。减少动态：扇形不动，只随进度淡出。缝的颜色变化和封套出现、消失都走 effects spring，不硬切。所有逐帧的量只在布局、绘制和 graphicsLayer 里读，不让页面逐帧重组。
- **曲目行的笔记标记（2026-10-11，T4）**：有笔记的曲目在行标题后面挂和专辑曲目行一样的笔记小图标（12dp `YoinSymbols.Note`，页面 accent 色、0.8 透明度，TalkBack 读 "Has note"）。独立的歌单 Activity 和同窗分列的详情列都一样；笔记写入后实时出现。有笔记的曲目集合在行出来之后才读到，所以标记落在已经显示的行上时，从标题末尾用空间弹簧展开、用 effects spring 淡入，标题的省略号跟着平滑让位，不硬切；笔记删掉时按同样的方式收起。accent 在绘制阶段读取，封面配色的弹簧只重绘标记，不让曲目列表逐帧重组。Hero 里的流动标题不加标记（刻意的设计）。歌单本身没有 Memory。
- **从播放列表移除（2026-11-12，和 iOS 对齐）**：歌单页长按曲目行升起的歌曲面板，最后多一组只有一行的「从播放列表移除 / Remove from playlist」（`YoinSymbols.Delete`，排在「前往…」那组后面；专辑页和正在播放的面板没有这一行）。只在这个账号能改的歌单上出现：`Playlist.canWrite`（重命名、删除用的同一个判断）并且服务有 `Capability.PLAYLISTS_WRITE`，所以 Apple Music 和别人的歌单没有。点了不二次确认：面板收起，这一行用 effects spring 淡出，下面的行用空间弹簧补上来（`animateItem`，行按条目而不是按位置编 key），曲目数和总时长一起减。只移除点的这一条：同一首歌在歌单里出现两次，另一条留着。服务拒绝时这一行回到原位，snackbar 和重命名、删除失败时一样（服务给了原因就显示原因，否则「无法移除曲目」）。连着移除几首时一条一条发，后一条按前一条完成后的位置算。Subsonic 的 `updatePlaylist` 只认下标、不做校验，所以发之前先重新读一次服务器上的歌单，按歌曲 id 找离原位置最近的那一条，找不到就不发；Spotify 发 `DELETE /v1/playlists/{id}/items`，body 是 `items: [{uri, positions}]` 加 `snapshot_id`（`items` 是现行参考里的字段，`tracks` 属于已弃用的 `/tracks`；`positions` 现行参考没写，和 iOS 一样照发，Spotify 若不认它，可能按 uri 删掉所有同曲条目，未在真机验证），位置是 Spotify 自己的条目序号（本地文件、播客也占位）。成功后重新读一次歌单。实现：`PlaylistDetailViewModel.removeTrack`、`TrackMenuSheet(onRemoveFromPlaylist)`。

### ⚙️ 设置（从主页或 Library 进入）

- 服务器配置（Subsonic/Navidrome 地址、认证）
- 缓存管理（容量限制、清除）
- 主题偏好
- 关于 / 版本信息；关于的第一行“重新引导”再跑一遍首次引导（2026-10-09）；当前账号导入过 Spotoolfy 时，关于里多一行“Spotoolfy 回顾”，打开同一个回顾页，最后一页是“完成”（2026-10-15，D15）
- 动效 / 外观 › **滚动边缘（`ScrollEdge`）**（2026-10-09 起是整页，不再是下拉菜单）：一种样式一张卡，卡上是这种样式真的在滚的小图 + 一段说明，最下面一句说明底栏和状态栏不受影响；`SettingsFeatureActivity` 的 `ScrollEdge`，大屏在右栏打开；小图的封面列数随宽度增加（每列约 112dp，3–8 列），不拉宽封面
- 外观 › **屏幕圆角（`ScreenCorners`，Android & HarmonyOS）**：允许在 `0–64 dp` 滑块调节或还原「跟随屏幕显示圆角」（Android 通过 `Display.getRoundedCorner()` 读取，无报告兜底 `28 dp`；**按设备本地保存、不上云同步**），让 Now Playing 升降、Memories 收退与预测性返回缩放卡缘严丝合缝贴合屏幕玻璃；调节微步触发 `performFrequentTick()`。iOS/macOS 由系统屏幕容器统一管理，不单独暴露该设置。
- 账号 › **音量平衡（`Volume leveling` / ReplayGain）**：仅对 **Subsonic / OpenSubsonic** 账号开放（Spotify 与 Apple Music 由官方客户端/SDK 自行处理故隐藏）：支持 `Off`、`By track`、`By album`（音轨或专辑 ReplayGain 标签叠加 Pre-amp 与防削波峰值上限）。iOS/macOS 端受 `AVAudioMix` 音量上限 `1.0` 限制，正增益仅播放至满幅 `0 dB`，超响音轨正常衰减。
- **在线 AI v2（`设置 › 功能 › 在线 AI`，Android + HarmonyOS；iOS/macOS 目前为 Gemini v1）**：
  - **可选模型服务（`AiProvider`）**：① **Gemini**（`gemini-3.1-flash-lite` Good / `gemini-3.5-flash-lite` Best，自带 Google 搜索）；② **DeepSeek**（`deepseek-flash`，Good 1 次搜索 / Best 2 次搜索，走 `/v1/tools/search`）；③ **千问 Qwen**（`qwen-flash`，仅 Good 单档，`enable_search + forced_search`）；④ **自定义服务**（`AiApiFormat.OpenAi` `/chat/completions` 或 `Anthropic` `/messages`，支持自填 Base URL、手动或拉取 `/models` 列表、思考强度 `Off/Low/Medium/High`）。
  - **外部搜索与评测**：自定义服务可挂接 **DeepSeek Search**、**Exa**（`api.exa.ai/search`）、**Tavily**（`api.tavily.com/search` advanced+chunks）、**Brave**（`res/v1/llm/context`，403/404/422 自动回退 `res/v1/web/search`）、**火山/豆包搜索**（`open.feedcoopapi.com/search_api/web_search`）或关闭搜索；设置页内嵌约 500 首实测参考表（`AiTierBenchmark`），并支持「**拿一首歌试试**」（从最近 `6` 首播放里选曲现场试跑并展示耗时与效果）。
  - **密钥安全**：Android 上 DeepSeek/千问/自定义/搜索密钥存于 `noBackupFilesDir/ai_provider.json`（`FileAiProviderStore`），不进 Android 备份；Gemini Key 当前位于主库 `gemini_config` 表；iOS 存于 Keychain；HarmonyOS 全量存于系统 `SecretStore`。**所有 AI 密钥均不参与 Google Drive 云同步**。
- **设备端 AI 与歌词卡片（`lyric_highlight`）**：Android 依赖系统 **Gemini Nano** 后台挑句（低电量/压力时让路，`NanoDiagnostics` 提供测试面板）；iOS/macOS 使用 **Apple Intelligence**（`LanguageModelSession`）；HarmonyOS 由用户分享/保存歌词图片卡时自选句子收录（无本地 LLM 依赖）。
- **硬件键盘快捷键与桌面指针交互**：Android `ShellShortcuts` 提供 12 组 `Ctrl`/`Alt` 快捷键（避开 Android 系统占用 `Meta`）；HarmonyOS `desktop_shortcuts.dart` 覆盖全部 12 组并扩展桌面快捷键（`Ctrl+L` 正在播放面板 ⇄ 全窗、`Ctrl+F` 正在播放聚焦、`←/→` 快退/快进 5 秒、`Esc` 返回/退出搜索、`Shift+F10`/`Menu` 上下文菜单，注 `Ctrl+K` 为资料库搜索）及鼠标悬停/右键菜单；iPadOS/macOS 通过 `YoinCommands` 提供 `⌘` 键与 Mac 菜单栏指令，`PointerHover` 在 Mac 端以 `.brightness()` 呈现悬停反馈并开启间接触控板右键（`SecondaryClick`）。

#### 账号与服务二级页（2026-09-26，取代 2026-09-06 版）

- **大屏 list-detail**（2026-09-30，SettingsTablet）：窗口 ≥ 840 时 Settings 与二级页走原生 Activity Embedding 分栏（`tag="settings-*"` 的 SplitPairRule + SplitPlaceholderRule，约 420 | 860，两个 Activity 不合并；这是 app 里唯一还在用 Activity Embedding 的地方）。左栏账号卡改为竖排行、右栏打开的那个高亮（⋮ 里保留 Use / Remove）；Features 里的 AI features / NeoDB 也在右栏打开（`SettingsFeatureActivity`），窄窗仍是行内展开。二级页手机横屏：左栏固定（hero + You'll need，不随输入法滚走），右栏滚动、表单在前、What you get 在后
- Settings 主页只做「列出 / 切换 / 移除」：Accounts 横向卡片，其余设置按 Features / Storage / About 分组，行内折叠展开（空间弹簧 + 效果弹簧），不再平铺说明段落。
- **Settings 视觉系统（2026-10-03，参照 Pixel Settings 子页面，取代此前的渐变底 + 整块面板 + 分隔线）**：
  - 页面平铺 `surfaceContainer`，行用 `surfaceBright`（深浅同一规则，行永远比页面亮）；每行是 M3 Expressive 分段列表的一段（`ListItemDefaults.segmentedShapes` 位置圆角 + `SegmentedGap` 2dp 间隙，无分隔线），行位置变化时圆角走空间弹簧。行高 ≥ 72dp：16dp 内边距 + 40dp 图标列（24dp 单色线条图标，onSurfaceVariant）+ 12dp + 文字。分类标签 primary、内缩 8dp。
  - 页头：浅色圆底返回键（`DetailBackButton`，surfaceContainerHighest）+ 大号页标题（displaySmall），返回键左缘、页标题、分类标签同在 24dp 线上；标题滚入顶栏时交接为小标题（与服务二级页同一套交接）。Settings 家族（主页、功能子页、服务二级页）**全宽**，不套 640dp 阅读宽度（用户 2026-10-04：居中的内容列配上铺满的卡片行像两套布局）。
  - 大屏 list-detail：左栏底色降为 `surfaceDim`，打开的那一行换成右栏的 `surfaceContainer` 并四角全圆（M3 selectedShape），脱离相邻行；颜色效果弹簧、圆角空间弹簧。
- **账号卡片（2026-10-03，头像 2026-10-04 改版）**：头像两层——**后面大的是服务**：账号专属 MaterialShapes 形状（8 种轮廓互不相像；按创建顺序 + id 哈希探测分配，同屏不重复，形状变化时 Morph 过去）填服务强调色，上面是 Yoin Symbols 手法重画的服务标志（`ServiceMarks`：Spotify 三道弧、Apple Music 双连音符；Subsonic 用 Cloud）；**右下角小圆是本人**：有服务头像就用（Spotify `/me` 的头像，存在 `ProfileAvatarStore`，只存公开图片地址，登录时写入、打开设置时为当前 Spotify 账号刷新），否则是昵称首字（跳过开头的服务名；dp 定字号）。是否在用由卡片本身表达：在用卡片填服务 container 色、比例 1，其余 `surfaceBright`、0.96。卡片文字 = 账号名（Subsonic 用用户名）+ 服务行（"Subsonic · host" / "Spotify"；标题已含服务名则省略）+ 状态胶囊（问题 > In use；在用账号有问题时写成 "问题 · In use"）。卡片行越过 16dp 页边铺到屏幕两缘，静止时首卡对齐页边，**不加边缘渐隐**（用户 2026-10-03 明确要求去掉，同 Home Recently Added 例外）；卡片高度按字号逐行计算，大字号不截断。打开管理页时把账号的"脸"（名称 / 位置 / 形状序号 / 头像地址，不含凭据）随 Intent 带过去，首帧即正确。
- 服务能做什么，只在用户表达兴趣时讲：Add → 选择面板（每项名称 + 一句话）→ 服务二级页 `ServiceSetupActivity`。二级页 = 服务标识 + 一句定位 + What you get（≤4 条亮点）+ You'll need（前置条件）+ 连接表单。不做支持矩阵、不罗列“不能做什么”；做不了的操作照旧隐藏，失败时给简短可处理的错误。
- 管理已有账号（编辑 Subsonic、Spotify 重新登录、凭据缺失恢复）复用同一二级页的 manage 模式：跳过介绍，直接给表单/操作。Spotify Client ID 属于一次性开发者配置，收进二级页的 Developer setup 折叠行，仅在缺失时自动展开；「No Client ID」深链打开该页并聚焦输入框。Subsonic 的「测试」连上后，状态行下面多一行服务器自报的名称和版本（OpenSubsonic 的 `type` 加 `serverVersion`，如 Navidrome 0.64.2，onSurfaceVariant，和状态文字左对齐）；纯 Subsonic 服务器不报，就只有状态行。
- 返回：二级页是无共享 chrome 的全屏目的地 → Pattern A 原生跨 Activity 预测性返回，零 back 代码；新账号的切换由 Settings 在自己的 scope 里执行（二级页经 ActivityResult 回传 id）。
- Apple Music 是可切换的正式 Profile：二级页通过开发者 Token 服务和 MusicKit 授权创建或重新连接加密账号。2026-09-29 已在 Pixel Tablet 订阅账号验证整曲与系统媒体控制；重新授权、删除和蓝牙硬件仍须分别验证。2026-10-01 的目录 / 资料库搜索和 + 加入资料库实现复用现有页面；+ 与喜爱心形分开，必须通过个人资料库关系查询确认后才显示稳定勾选。HTTP 202 只显示待确认，不能当成完成，新加入流程的真机验证须单独记录。
- **云同步（0.6.3+ 多端互通）**：Storage 组第一行「Cloud sync」（单色 Devices 图标，摘要 Off / On · Synced … / Paused · Reconnect Google…），打开 `CloudSyncActivity`（Pattern A，大屏在右栏打开、左栏行高亮）。可选、默认关闭、本地优先：存进用户自己 Google Drive 的 appDataFolder，只申请 `drive.appdata`。同步笔记 / 评分 / 专辑长评 / 首页布局 / 歌单备份 / 少量设置 / **在线 AI** 歌词翻译与关于问答，凭据与 API Key 永不上传云同步。关闭页 = 一句定位 + What you get（≤4）+ Good to know（如实说明不是端到端加密、占用户存储、服务器地址和用户名会同步）+ 按钮；开启页 = 状态 + Google 账号 + 每个音乐账号的同步状态 + Drive 里有本机没有的账号 + 设备 + 「在本设备关闭」/「删除云端数据」。四端兼容格式（Android `yoin-state` + `artifacts`、iOS/macOS `:shared` `CloudSyncCore`、HarmonyOS `SyncEngine`）以 `docs/cloud-sync.md` 为准。
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
| 歌词翻译开关 | 每行译文从行下沿按空间弹簧展开 / 收起（间距在动画块内，收起即单行高）；焦点行全程钉在开关前的焦点位（见「展开歌词页的焦点位」），上下行向两侧让开 |
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
| Sonos 投射 | UPnP/DLNA（SSDP + AVTransport） | Subsonic 直连音频；现有设备面板发现与选择音响，Media3 会话统一控制 |
| 缓存管理 UI | 本地 | 占用空间可视化、按专辑清除缓存 |

### ❌ 不做（YAGNI）

Podcast、Internet Radio、Chat、User Management、Jukebox、Bookmarks、Shares、Video

### 📱 大屏幕适配 / 响应式设计（2026-07 落地，2026-09-30 断点适配重订，2026-10-02 同窗分列）

> 规则本体在 `docs/adaptive-principles.md`（八条原则 + 各窗口一览），这里只记决定。

- 宽度三档 LayoutMode（Compact < 600 / Medium 600–840 / Wide ≥ 840）+ Tabletop 折叠姿态，**高度另读**：高 < 480 是手机横屏（`isCompactHeight`），页面先判高度再判宽度——844 宽的手机横屏不会落到桌面档。判定源都在 `ui/experience/WindowAdaptiveRuntime.kt`，由 M3 adaptive 的 window size class + 铰链姿态驱动，LayoutMode 按**列**相对：任何让出宽度的容器（NP 侧栏、详情列）用 `forPaneWidth(列宽)` 给子树重新提供 `LocalYoinWindowInfo`，600 宽的 shell 列就是 Medium
- Button Group 三种形态（竖屏底栏 / 手机横屏分离式挖孔带 / Medium+ 底栏居中限宽 600）见「导航结构」；`LocalShellChromeInsets` 给页面让位；原 Navigation Rail 已删除
- 跨窗口 bar 交接（`DetailLaunchMode.FullChoreography`）在竖屏底栏与分离式挖孔带下都做（两个窗口的组逐像素同位）；居中底栏（Medium 整窗）是纯推入，但栏仍然有动画——**窗口内 morph**（2026-10-03，`DETAIL_EXTRA_BAR_MORPH`）：详情窗口的栏从和 shell 底栏像素相同的导航姿态出发，页面滑入的那一拍 morph 成详情姿态；返回手势把它 scrub 回导航姿态，溶解落在 shell 的导航栏上。不桥接 shell 的任何状态（`bridgeBackToShell=false`）。Wide 整窗没有跨窗口交接——详情根本不是另一个窗口
- **详情列（2026-10-02，适配原则 3「分栏是同一窗口里的列」）**：Wide 且高 ≥ 480 的整窗（`YoinWindowInfo.hasDetailPane`）里，Album / Artist / Playlist 不再启动 Activity，而是作为 shell 窗口的**右列**打开（`ui/navigation/pane/`：Navigation 3 子栈，同一批页面 composable，`LocalDetailHostMode = Pane`）。shell 平时独占整窗，开第一个详情才分列、关最后一个解散，开合走 `defaultSpatialSpec`（列从右缘滑入、shell 列同步让位，`DetailPaneState.openFraction` 是唯一驱动）。列宽 = 一个纯函数 `resolvePaneBudget`：窗宽 − 24dp 槽，shell 份额默认 0.45、≥ 1080 时保证 shell ≥ 600（1280 → 600 ｜ 24 ｜ 656），两列都 ≥ 360；槽里是 M3 `VerticalDragHandle`，整条槽可拖、1:1、只钳制（session 内记住）。两列和槽共用 shell 的中性页面底色（列里的详情页不再画封面色顶部渐变），中间没有分隔线，只有把手。列里每页读自己的列宽（Medium / Compact 布局照页面规则）。列内推入 / 弹出 = AOSP 96dp + EMPHASIZED 450ms；**从 shell 点另一个页面 = 换根**（清栈再放，不算前进）：M3 fade-through：旧页原地快速淡出（快效果弹簧），新页从 0.96 淡入放大，不会两页文字叠在一起，不走推入（2026-10-05 owner 在折叠屏上：旧专辑往左滑出列外、停在 shell 上一会儿新专辑才出现）；列的内容裁在列内，推入时后退的 96dp 也不会画到槽或 shell 上；预测返回：栈内由 NavDisplay 的 predictive pop 做整体缩放预览，最后一页的返回缩放到 0.9 + 28dp 圆角、提交后列滑出、shell 变宽；返回归属：整窗 NP 优先，其次 Home 编辑；NP 侧栏、详情列、Memories 这些并排的面按打开顺序的反序关（最后打开的先关，2026-11-12；在已打开的面里再往里走——列里换页或推入、播放器展开歌词或进专注——也算最后打开；`ShellBackResolver`），列的两个返回处理器挂在只在它拥有返回时才启用的子 dispatcher 上（处理器按注册先后排，后挂载的列否则会压过 NP）。列宽、侧栏让位、栏槽宽都在 layout 阶段算，手势 / 弹簧帧不重组 shell。换档永远不落在弹簧中途：开列时 shell 在点击那一帧换档，页面在点击时就构建、等帧率平稳后随列一起滑入（2026-11-12 起不再空白滑入）；关列时等弹簧落定后 shell 才换回 Wide；最后一页的返回手势同时把列往右带（列宽 1/4），松手从当前位置继续滑出。Wide → 窄（平板转竖屏）时列顶层页自动变成推入页。**Activity Embedding 的 shell ↔ detail 规则、`SplitAttributesCalculator` 的 shell 比例和平台分隔条全部删除**（两个 Activity 窗口物理上无法共享一条栏；平台分隔条拖后 calculator 会把比例改回去、颜色退成库默认黑色），只剩 Settings 的 list-detail
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

### Sonos / DLNA（2026-10-20 接入）

- 当前范围是 Subsonic / OpenSubsonic。音响直接读取服务器的 HTTP(S) stream URL，服务器必须能从音响所在网络访问；不代理、录制或重新编码音频。带认证参数的音频和封面地址不写入诊断日志。
- 复用正在播放的「设备」底部面板。打开或手动刷新时进行有时限的 SSDP 发现，读取设备的 AVTransport / RenderingControl 服务，不在空闲时持续扫描。原设计的 Cling 框架改为已有 OkHttp + SSDP 的小范围实现，避免为这组指令引入另一套后台服务与依赖。
- DLNA 播放器实现 Media3 Player，接入现有 PlaybackService 的同一个 MediaSession。队列、暂停、继续、上一首、下一首、进度和音量随真实后端能力开放；不能 seek 的设备在返回不支持后撤回该命令。
- 转到音响前先暂停本机；转回本机前先确认音响停止。音响失联时保留可重试的远端选择，不能自行在本机恢复发声。Chromecast 与 DLNA 不同时接管播放。
- Sonos 分组通过只读 ZoneGroupTopology 查询定位组主设备，再发送播放指令，不改变音响分组。
- Apple Music 的 MusicKit DRM 播放没有可交给普通 DLNA renderer 的完整音频 URL 或公开 DLNA 路由接口，本次不支持，设备入口继续按来源能力隐藏。Spotify 继续使用现有 Spotify Connect 设备逻辑。
- 设备和播放验收见 [DLNA 验证记录](validation/dlna-2026-10-20.md)。发现设备、SOAP 成功和实际完成音频播放分别记录。

---

## 待确认事项

- [x]  滑动评分条的 UI 设计稿 — 已确认：垂直粗条，位于封面右侧，显示浮点评分（参见设计稿截图）
- [ ]  音频可视化的具体视觉效果 — 实现时再探索，先做基础版再迭代（视觉表现仍未定；采样代码写好了但**尚未接通**：`player/AudioVisualizerData.kt` 的 `AudioVisualizerManager` 只在 `AppContainer` 里 lazy 构造，全仓没有任何一处调用它的 `start(audioSessionId)`，`PlaybackService.audioSessionId` 这条流也没人喂给它，所以 UI 侧 collect 的 `playbackSignal` / `visualizerData` 目前恒为 0 / Empty）
- [x]  App 图标设计 — 已完成：三箭头全出血几何，自适应图标 `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`（含 `ic_launcher_round.xml`），三层素材在 `res/drawable/ic_yoin_launcher_{background,foreground,monochrome}.xml`，monochrome 供主题图标使用

![image.png](attachment:37c0d660-23b7-4c25-9fb4-e77630964bcd:image.png)

![image.png](attachment:eba9a990-ab35-4304-ad5d-b5327f351346:image.png)

![image.png](attachment:86d59aea-0f12-414a-8b61-a3508223cd37:image.png)


### Apple Music profiles (2026-10-01)

Apple Music connection creates a regular encrypted Profile; the previous validation authorization migrates once without automatically switching the active account. Its MusicSource supports library songs, albums, artists and read-only playlists, with distinct catalog and personal-library search scopes. MusicKit supplies DRM audio through a Media3 session shared by Now Playing and system controls. The separate library-add control uses the exact authenticated catalog-song → library relationship; it displays a stable checkmark only after membership is confirmed and preserves an unconfirmed HTTP 202 as pending. Membership never becomes a favorite heart. Unsupported favorite mutation, library removal, playlist editing, Cast and offline caching remain hidden. Imported tracks without a catalog playback ID are dimmed with a "?" badge on the cover whose tap expands the reason inline; they never enter the MusicKit queue. The same goes for a library song whose `catalog` relationship Apple answers empty although its play parameters still name a catalog id (a song pulled from, or never in, the account's storefront; 2026-10-12 device QA): MusicKit's catalog request leaves such an id out of the queue without an error and reads the start index in the shorter list, so a Songs tap with eight of them above it started the song eight rows down. It keeps that catalog id as its identity, is marked `appleMusicCatalogUnavailable`, and plays nowhere in Yoin (a tap on it shows the localized "Unable to play this song"; an import, which Apple also answers with an empty `catalog` but which names no catalog id, is never marked so and keeps the import refusal); a debug build logs `YoinMusicKitQueue` when a prepared queue comes back shorter than asked or starts on another song. Since 2026-10-02 a library album opens as its full catalog album (Spotify parity): every catalog track is listed and the ones already in the user's library carry the check from `TrackLibraryButton`, which replaces the heart slot for Apple Music; tapping an unchecked row control adds that song. A row's membership comes with the album's own read (`include[songs]=albums,artists,library`: each song's own `library` relationship; after a 400 the album is read once more without it, and only when that read answers does the source stop asking for it; a 400 that read gets as well was never about the songs' `library`, so the album falls back as before and later albums still ask); the user's copy of the album marks the rows whose song carries none. Apple can leave the album's `library` relationship out while the library holds some of its songs (POP DELIVERY, 2026-10-11 device QA: every row showed + though アルキメデス was added), so when neither answers a row is left unconfirmed (`appleMusicLibraryChecked=false`): it draws + but never records "not added" over a check known from elsewhere. Library albums Apple cannot match to the catalog keep their library tracklist. Subscribed playback was verified on Pixel Tablet on 2026-09-29; these new search/library writes require their own current device verification, and reauthorization, deletion and Bluetooth hardware remain open.

### Spotify 心形状态（2026-10-10，P4；0.6.3+ 补充）

Spotify 的「已喜欢」= Liked Songs（Yoin 的爱心就是它）。由于本地同步镜像（`SpotifyLibrarySyncCoordinator` 缓存最新 200 条；完整 Web API 分页在 `SpotifyApiClient` 上限 `2,000`，iOS `SpotifySource` 默认 `300`）仍可能装不下更大的曲库，心形另有一层按账号隔离的状态（各服务完整能力与限制另见 [platforms.md](platforms.md) 与 [integrations/provider-alignment.md](integrations/provider-alignment.md)）：

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
