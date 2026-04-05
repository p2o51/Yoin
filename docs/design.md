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
2. **默认 = 系统 Dynamic Color** — 通过 `dynamicDarkColorScheme()` 跟随系统壁纸/主题色
3. **播放态 = 封面提取色** — 有内容播放时，用 Palette API 从专辑封面提取主色，替换 color tokens，实现全局色调切换
4. **颜色过渡** — 使用 Effects Spring 做平滑过渡，不生硬跳变

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
- 右上角 ⚙️ 设置入口

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
- 搜索功能
- 右上角 ⚙️ 设置入口

### ⚙️ 设置（从主页或 Library 进入）

- 服务器配置（Subsonic/Navidrome 地址、认证）
- 缓存管理（容量限制、清除）
- 主题偏好
- 关于 / 版本信息

---

## 关键转场动画

| 触发 | 动画效果 |
| --- | --- |
| 切换主页 ↔ Library | Button Group Shape Morph + 页面内容 crossfade（Effects Spring） |
| 点击中间按钮展开 Now Playing | 封面 Shared Element 扩展 + Button Group 容器 Spatial Spring 扩展为全屏 + 背景 fade in |
| 下滑 / 返回收回 Now Playing | 反向 Spring 动画，封面缩回缩略图，全屏收回 Button Group |
| Predictive Back | 跟手进度驱动的收回预览，松手后 Spring 完成或回弹 |
| 切歌 | 封面 crossfade + 背景色 Effects Spring 过渡 |

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

### 📱 第二期额外规划

- **大屏幕适配 / 响应式设计** — 适配平板、折叠屏、横屏模式：
    - 使用 Compose Material 3 Adaptive 库（`material3-adaptive`）
    - 大屏下可展示双栏布局（左侧 Library/主页 + 右侧 Now Playing）
    - Button Group 在大屏下可转为 Navigation Rail
    - 折叠屏状态感知（WindowSizeClass + Posture）

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
- [ ]  音频可视化的具体视觉效果 — 实现时再探索，先做基础版再迭代
- [ ]  App 图标设计

![image.png](attachment:37c0d660-23b7-4c25-9fb4-e77630964bcd:image.png)

![image.png](attachment:eba9a990-ab35-4304-ad5d-b5327f351346:image.png)

![image.png](attachment:86d59aea-0f12-414a-8b61-a3508223cd37:image.png)
