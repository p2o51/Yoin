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

浮动底栏的 12dp 阴影由最上层、同位置的底栏窗口绘制；透明页面交接与预测性返回期间不叠加下层阴影。分栏中不重叠的底栏各自保留阴影。“最上层”以真正上屏为准：新窗口首帧提交后才接管阴影，窗口开始 finish（关闭淡出）时立即交还；下层阴影随之按效果弹簧淡出 / 淡入，不做硬切，避免进场时阴影先消失、返回时阴影闪回。

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
- 筛选胶囊与下方网格 / 列表的交界用“逐项溶解”软衔接（2026-09-29，取代硬截断）：交界处不盖任何渐变、模糊或色带，遮罩在每个 item 里。图形（封面、头像、缩略图）在滑到交界时碎成跟随自身的错列波点，越往上越小，到交界线正好消失；文字只渐隐、不拆成点。因此胶囊下的固定间距只留 4dp，网格顶部内边距 8dp
- 这对遮罩是通用语言：任何“滚动内容撞上固定 chrome”的交界都应复用（`seamDissolveViewport` 标记滚动容器，图形用 `seamDissolve`、文字用 `seamFade`；不在 viewport 内时为空操作，共享组件可以常驻挂载）

### ⚙️ 设置（从主页或 Library 进入）

- 服务器配置（Subsonic/Navidrome 地址、认证）
- 缓存管理（容量限制、清除）
- 主题偏好
- 关于 / 版本信息

#### 账号与服务二级页（2026-09-26，取代 2026-09-06 版）

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
| Library 列表滚到筛选胶囊下 | 静止时不出现；滚动开始后过渡带随前 40dp 滚动从 0 长到 36dp。图形在带内按每颗点到交界的距离缩小（交界处为 0，约 1.34 倍带高处合成实色），点距 6dp 与切歌溶解同一网屏，前沿每个 item 各有起伏，点沿同一流场轻微打旋；旋转相位跟随滚动，但带一点惯性：流动落后内容的量与滚动速度成正比（上限半个过渡带），内容停下后以停前的速度继续漂一小段，约 0.5 秒指数衰减到静止，不回弹；静止时完全不动，省电模式和“移除动画”下关闭余韵。文字在带内 80% 高度渐隐。API 33+ 走 AGSL `RenderEffect`，以下用 Path 裁剪兜底；只有进入带内的 item 才重绘 |
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

### 📱 大屏幕适配 / 响应式设计（已落地，2026-07）

- 三档 LayoutMode（Compact / Medium / Wide）+ Tabletop 折叠姿态（`ui/experience/WindowAdaptiveRuntime.kt`），全部由 M3 adaptive 的 window size class + 铰链姿态驱动
- Medium+ 全窗：底部 Button Group 转为左侧 Navigation Rail（`ui/navigation/YoinNavRail.kt`）
- Now Playing 从 Medium 起为双栏布局；Tabletop 沿铰链分上下两半
- Activity Embedding（`res/xml/main_split_config.xml`，splitMinWidthDp=840）：任务窗 ≥840dp 时 detail 页进入右侧分栏，左侧为 placeholder
- detail 启动按构型三值分流（`DetailLaunchMode`）：Compact 全窗走跨窗口 bar 交接编舞，Medium+ 纯推入，分栏交给系统默认
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
- [x]  App 图标设计 — 已完成：三箭头全出血几何，自适应图标 `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`（含 `ic_launcher_round.xml`），三层素材在 `res/drawable/ic_launcher_{background,foreground,monochrome}.xml`，monochrome 供主题图标使用

![image.png](attachment:37c0d660-23b7-4c25-9fb4-e77630964bcd:image.png)

![image.png](attachment:eba9a990-ab35-4304-ad5d-b5327f351346:image.png)

![image.png](attachment:86d59aea-0f12-414a-8b61-a3508223cd37:image.png)


### Apple Music profiles (2026-09-26)

Apple Music connection now creates a regular encrypted Profile; the previous validation authorization migrates once without automatically switching the active account. Its MusicSource supports library albums, artists and read-only playlists plus catalog search. MusicKit supplies DRM audio through a Media3 session shared by Now Playing and system controls. Unsupported favorite/library writes, playlist editing, Cast and offline caching remain hidden; adding to the Apple Music library must never be represented as a favorite heart. Imported tracks without a catalog playback ID require an explicit unavailable message. Physical subscribed-account QA is still pending for this integration.
