# Yoin 简体中文本地化术语表与文案风格指南 (zh-Hans Glossary)

> 本文档为 Yoin 简体中文本地化与产品文档的基准规范（2026-11-18 校准）：准确直译英文母本，保持 iTunes / iPod 式的冷静、干净与疏离感，不自行加戏、不堆砌矫揉造作的修饰词，并严格遵守对外事实与隐私表达边界。

---

## 1. 核心原则

1. **准确克制，贴近英文原文**：
   - 界面标签、状态、错误提示一律直接、干净地对应英文原意（如 `Couldn't save the review. Your draft is kept.` → `无法保存评论，草稿已保留。`；`No memories yet` → `暂无 Memories`；`Failed to load memories` → `无法加载 Memories`）。
   - 不乱加口语俚语（「没存上」「没洗开」「半天没回话」），也不乱用文绉绉的 AI 词汇（「微澜」「告罄」「落灰多时」「后方可」）。
2. **首次引导（Landing 气泡）的平淡距离感**：
   - 紧贴英文原文的短句结构（如 `I play the music you already have, from your own server, Spotify or Apple Music.` → `我只播放你本来就有的音乐——来自你自己的服务器、Spotify 或 Apple Music。`；`Apple charges developers for one. Mine is free to use.` → `Apple 向开发者收取这项费用。你可以免费用我的。`），诚实、平淡、有边界感，不刻意卖萌或过度戏剧化。
3. **四端（Android / iOS / macOS / HarmonyOS）口径一致**：
   - 除平台系统自身固定称谓（如 Apple 的「小组件 / Handoff」、HarmonyOS 的「服务卡片 / 应用接续」）需要在引导或帮助中对照注明外，产品内部术语四端统一。

---

## 2. 标准术语表（2026-11-18）

### 核心产品与品牌术语

| 英文 / 概念 | 标准中文译法 | 备注与使用说明 |
| :--- | :--- | :--- |
| `Memories` | **`Memories`** | 专有名词，**始终保留英文**，不翻译成「回忆」（如 `暂无 Memories`、`无法加载 Memories`、`打开 Memories`） |
| `All Memories` | **`全部 Memories`** | 首页区段与全量账本页面标题 |
| `Scrapbook` | **`剪贴簿`** | 专辑第 2 页手写感图文拼贴界面（**禁用「手账」**） |
| `Widget` | **`微件`** | 产品与 Android 标准词；说明 HarmonyOS 桌面入口时可注明「服务卡片」，iOS/macOS 可注明「小组件」 |
| `What others say` | **`别人怎么说`** | 专辑页查看 NeoDB 众人评价的入口；页面标题为 `NeoDB 上别人怎么说` |
| `Explicit`（歌曲 / 专辑的 E 标） | **`含不雅内容`** | 只用于 E 标的读屏文字（`cmp_explicit_badge_cd`）；标本身就是字母 E，不翻译。2026-11-25 Claude 暂拟，2026-11-26 owner 认可 |
| `Made For You` | **`为你推荐`** | Spotify（daylist / Daily Mix / Blend 等）与 Apple Music 个人混合歌单首页区段 |
| `Online AI` | **`在线 AI`** | `设置 › 功能 › 在线 AI`（涵盖 Gemini、DeepSeek、千问、自定义服务与外部搜索） |
| `China Direct` | **`中国直连`** | 在中国大陆无需代理即可使用的服务（自建服务器、QQ/网易云歌词、DeepSeek、千问、HarmonyOS 等） |
| `Closed testing` / `Open testing` | **`封闭测试`** / **`开放测试`** | Play / App 商店测试阶段表述（禁用「内测」「公测」） |
| `Music source` / `Provider` | **`音源`** | Subsonic/OpenSubsonic、Spotify、Apple Music |
| `Self-hosted server` | **`自建服务器`** | 用户自己搭建或托管的 Navidrome / Subsonic / OpenSubsonic 服务器 |

### iTunes / iPod 传统与标准 UI 术语

| 英文 | 标准中文译法 | 备注 |
| :--- | :--- | :--- |
| `Now Playing` | **`正在播放`** | 主播放器展开视图与通用标签 |
| `Library` | **`资料库`** | 一级 Tab 与音乐藏品视图 |
| `Home` | **`首页`** | 一级 Tab |
| `Jump Back In` | **`接着听`** | 首页常驻推荐区段 |
| `Rediscover` | **`重温`** | 首页高分/有记述但久未播放的专辑与单曲区段 |
| `Recently Added` / `Recently Played` | **`最近添加`** / **`最近播放`** | 首页标准区段 |
| `Your Playlists` | **`你的播放列表`** | 首页歌单区段；通用名词 `Playlist` 译为 **`播放列表`**（或简短口语场景「歌单」，界面主导航与标题统一用「播放列表」） |
| `On Your Other Accounts` | **`在你的其他资料里`** | 首页展示其他已连接音乐账号最常听的内容 |
| `Lyric Cards` | **`歌词卡片`** | 首页单句歌词摘录区段（`HomeSection.lyric_highlight`）及分享图卡 |
| `Activities` | **`动态`** | 首页最近活动 bento 区段 |
| `Album` / `Artist` / `Song (Track)` / `Queue` | **`专辑`** / **`艺术家`** / **`歌曲` / `曲目`** / **`队列`** | 保持 iTunes / iPod 传统译法 |
| `About` | **`关于`** | Now Playing 歌曲背景页与 `设置 › 关于` |
| 专辑制作信息行（`Label` / `Producer` / `Mastering` / `Mix` / `Recorded` / `Source` / `First released`） | **`厂牌` / `制作` / `母带` / `混音` / `录制` / `原作` / `首版`** | 剪贴簿标题下一行（2026-11-25 owner + Carbon 拟定，**待 Rebecca / Argon 定稿**）；项间「 · 」，名字间中文「、」 |
| `Review` / `Short comment` | **`评论`** / **`短评`** | 专辑长评（>360 字）与 NeoDB 短评（<=360 字） |
| `Note` | **`笔记`** / **`手记`** | 带播放时间戳的单曲随手记与手记记录 |
| `Volume leveling` | **`音量平衡`** | 设置中控制 Subsonic ReplayGain 的入口 |
| `Screen corners` / `Scroll edge` | **`屏幕圆角`** / **`滚动边缘`** | `设置 › 外观` 下的视觉设置 |
| `Continue On` | **跨设备接力** / **应用接续** / **Handoff** | Android 用「跨设备接力」/ Continue On，HarmonyOS 用「应用接续」，Apple 用「Handoff」 |
| `Open in Yoin` / `Choose a match` / `Listen` | **`在 Yoin 中打开`** / **`选择匹配结果`** / **`收听`** | 分享入口、找到链接后的搜索结果页、剪贴板卡片按钮（Rebecca 2026-11-24，[open-in-yoin.md](../open-in-yoin.md)） |
| Now Playing 引导句与功能提示 | **`音犹未尽？`** / **`歌词看看`** / **`背景看看`** / **`别人看看`** | 分别对应展开收尾引导、歌词、背景/关于、NeoDB 别人怎么说 |

---

## 3. 严格禁用词（Forbidden Terms）

所有新增界面文案、官网文档、发布说明与内部规范**禁止**使用下列词汇：

| 禁用词 | 原因 | 正确替代 |
| :--- | :--- | :--- |
| **`Nowledge Mem`** / **`nmem`** | 已废弃或易混用的外部名称 | **`Memories`** |
| **`手账`** | 与本地化的冷静风格不符 | **`剪贴簿`**（指 Scrapbook）；**`手记`** / **`笔记`**（指单曲笔记） |
| **`回忆`**（指代 `Memories` 时） | `Memories` 为固定专有名词 | **`Memories`**、**`全部 Memories`** |
| **`内测`** / **`公测`** | 测试阶段统一规范用词 | **`封闭测试`** / **`开放测试`** |
| **`国内直连`** | 统一地域表述规范 | **`中国直连`** |

---

## 4. 对外事实与隐私表述边界

撰写面向用户的文案、FAQ、Tips 或官网说明时，不得突破以下技术事实边界：

1. **禁止宣称「零追踪」「不收集任何数据」（Zero tracking）**：Android 版本集成了 Google Cast SDK，该 SDK 会作为系统投播能力的一部分向 Google 发送匿名 Cast 使用与性能数据（iOS / macOS 使用 AirPlay，无 Cast SDK；HarmonyOS 使用系统 `AVCastPickerHelper`）。准确表述应为：**「没有 Yoin 账号，没有广告，也没有第三方应用内统计分析」**，并如实列出 Google Cast。
2. **禁止宣称「只有你自己知道你在听什么」（Stays yours alone）**：你连接的音源服务、自动/手动歌词源（QQ 音乐、网易云音乐、LRCLIB、华为音乐）、在线 AI、MusicBrainz / Discogs 与 NeoDB 都会按功能触发收到对应请求。
3. **禁止笼统宣称「所有 API 密钥都不进入系统备份」**：
   - Android：账号凭据、NeoDB Token 与 DeepSeek / 千问 / 自定义 AI / 外部搜索密钥保存在 `noBackupFilesDir` 下，不参与 Android 自动备份；但 **Gemini API 密钥目前保存在主数据库 `gemini_config` 表中**，可能随 Android 系统备份保存。
   - iOS / macOS：密钥保存在系统 **Keychain**。
   - HarmonyOS：密钥保存在系统 **Asset Store Kit（`SecretStore`）**。
4. **禁止宣称「端到端加密云同步」**：Google Drive `appDataFolder` 同步仅在 HTTPS 传输层加密并受用户自己的 Google 账号权限隔离，Yoin 未额外实施客户端端到端加密（E2EE）。
5. **禁止宣称在线 AI「只输出已核实的事实」（only verified facts）**：在线 AI 回答（含启用外部搜索后）仍由模型生成，可能存在错误或幻觉。
