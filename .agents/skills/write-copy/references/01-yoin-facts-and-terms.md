# 01｜Yoin 事实与术语

撰写或修改 Yoin 文案前请先核对本页。涉及隐私、数据与联网的表述，均以隐私政策及对应端真实代码为准。

## 1. 参考来源

- [Yoin 文案](https://app.notion.com/p/418d1115e92181e89c26fbf42a8a34fb)（含 [首页](https://app.notion.com/p/418d1115e92181e68677f38ade0b59c1)、[手记 · 索引](https://app.notion.com/p/418d1115e92181e78ea8c20b2929b62d)、[手记 · 关于 499 首歌的“关于”测试](https://app.notion.com/p/418d1115e92181298122c1bc57f51ec8)、[手记 · 从不离开的导航栏](https://app.notion.com/p/418d1115e9218182865ac0601e2a224e)、[手记 · 预先收藏：Yoin](https://app.notion.com/p/418d1115e92181219f34d0b6971c6ba6)）：已勾选的区块代表已定稿。
- [隐私政策](https://yoin.gojyuplus.com/privacy.html)与[使用文档](https://yoin.gojyuplus.com/zh/docs.html)。
- 官网：[中文](https://yoin.gojyuplus.com/zh/)、[英文](https://yoin.gojyuplus.com/)。
- 仓库规范与多端矩阵：`docs/design.md`、`docs/platforms.md`、`docs/integrations/provider-alignment.md`、`docs/i18n/zh-glossary.md`。

## 2. 产品概况（0.6.4 基线）

- **定位与四端平台**：Spotoolfy 的原生重写续作，本地优先、多音源设计。
  - **Android（`app/`）**：采用 Jetpack Compose 与 Material 3 Expressive 构建，已在 Google Play 封闭测试发布 **0.6.4（`versionCode 36`）**，需 Android 8.0（API 26）及以上，适配手机、折叠屏（如 Pixel Fold、Galaxy Z Fold）、平板与 Googlebook（0.6.4 回归资料库封面取色形状与「EP / 单曲」「全景声」「E」脏标筛选、升级宽屏/平板正在播放页签与快捷分享底栏及笔记编辑卡、放开「关于/笔记」分享卡片全色系，并优化平板自由窗口/桌面模式顶部标题栏与多处状态栏图标对比度）。
  - **iOS / iPadOS 与 macOS（`iosApp/` + `:shared`）**：基于 Kotlin Multiplatform `:shared` 核心与 SwiftUI（Liquid Glass、iPad 双角浮动胶囊与详情列/侧栏、单窗口模式；**0.6.4 Build 39**，并通过 iPad-on-Mac 多平台形态运行于 Mac），已在 0.6.4 齐平「在 Yoin 中打开」、脏标「E」识别与资料库专辑筛选（标题旁固定「E」+ 版本标签随艺人行滚动，隐藏 Apple Music「- EP / - Single」后缀）、Memories 全文搜索、三页签 10 版式分享图片（全色系配色放开与署名自动换行）、专辑「关于」与完整发行日期、首页原地拖动编辑与 iPad/Mac 双栏首页、全应用及设置页动态 AI 服务命名，以及本地唱歌模式（Sing）与 Yoin Labs Lyra Sing 状态；原生 `YoinMac` 线已冻结。
  - **鸿蒙（`harmony/`）**：Flutter UI + ArkTS 原生模块，已合入 `main` 分支，适配华为手机、折叠屏（含 Pura X 外屏）与鸿蒙电脑（HarmonyOS 7 / API 26），处于开发与真机测试阶段（注：鸿蒙端暂不支持 Spotify App Remote SDK，且 ArkWeb 不支持 EME DRM，见 `docs/integrations/spotify-harmonyos.md`）。
- **多账号与多音源**：
  - 支持同时接入多个**自建服务器**（Navidrome、Gonic 等 Subsonic / OpenSubsonic 服务，支持原始音质与转码档位、音量平衡 ReplayGain 与离线下载）、**Spotify**（读取个人资料库，通过 Spotify 应用或 Spotify Connect 远程播放与控场，支持休眠唤醒、首页「为你推荐」与 Android 首页顶部曲目胶囊）与 **Apple Music**（通过 Apple MusicKit 原生播放、默认或自定义开发者令牌服务、首页「为你推荐」与曲目入库）。
  - 界面功能按服务实际能力（`Capability`）动态呈现；长按底栏头像并滑动可快速切换账号，首页提供「在你的其他资料里」组件。
- **核心功能**：
  - **评分、笔记、搜索与 Memories**：0.1 精度滑动评分（含填充边缘分色与删号时清理待发服务器队列）、锚定到秒的时间戳笔记（Android 支持 `@` 提及歌曲/专辑/艺术家与轻量 Markdown 格式、专辑剪贴簿封面旁制作署名行 + 专辑「关于」卡片（或 Apple Music 官方编辑推荐）+ 多张专辑笔记与底部「被提及」汇总，个人书写统一 `primary` 色、AI 信息统一 `tertiary` 色；支持歌词/关于/笔记 3 页签 10 版式卡片与 Story 分享）、专辑 Memories 与「全部 Memories」总账；**资料库搜索支持跨全部已登录账号搜索 Memories 与笔记**（「全部」页按卡片铺排并可点按跳至剪贴簿或从笔记对应秒数起播）；NeoDB 同步（评分与评论可独立同步，区分短评与长评）与「别人怎么说」（含 Discogs / MusicBrainz 公开评分展示）。
  - **歌词与本地化匹配**：逐行同步歌词与「聚焦」模式、制作人员（Credits）解析、多源查词（自动与手动查词涵盖 QQ 音乐、Spotoolfy 网易云网关、LRCLIB，手动搜索含华为音乐）；支持 Spotify 多语种原名（`Accept-Language`）+ MusicBrainz ISRC 反查，以及 Apple Music「歌曲名」三档设置（按 Apple Music 显示、按歌曲原地区、按指定地区，支持英/日/韩及简繁中文歌曲、专辑与歌手原名），并支持整首歌词翻译（0.6.3 含底部柔和光波、逐行轻缓展开与拨弦触感）。
  - **在线 AI v2 与端侧 AI**（「设置 › 功能与外观 › AI 功能」）：在线模型支持 **Gemini**（默认 3.1 Flash-Lite，可选 3.5 Flash-Lite + Google 搜索）、**DeepSeek**、**通义千问（Qwen）**，以及 **自定义服务（OpenAI 与 Anthropic 兼容接口）**；自定义/外接搜索支持 **DeepSeek、Exa、Tavily、Brave、火山引擎豆包（Doubao）** 或关闭搜索；支持从最近播放的 6 首歌中「拿一首歌试试」、歌曲与专辑「关于」与 Ask 问答收入专辑「剪贴簿」（问答入口及状态随当前启用的 AI 服务自动显示名称）。端侧 AI 支持 Android Gemini Nano（「歌词卡片」、首页早中晚问候、「关于」推荐追问与诊断页面）、实验功能中的 **Lyra Sing** 唱歌模型管理（查看就绪状态与硬件加速方式、随时下载或删除；Google Tensor 机型附带隐藏芯片彩蛋）及 iOS Apple Intelligence 歌词要点。
  - **迁移、备份、同步与桌面体验**：**歌单云端备份与恢复**（跨服务匹配与逐首审阅）、Spotoolfy 导入迁移、模块化主页（长按直接拖动、双指捏合「收成列表」/「展开版面」，大屏支持双栏等高网格与拖到旁边并排并分开记住横竖屏排布、封面架翻页与首页隐藏底栏、「接着听」Memories 卡片按次轮换）、桌面微件（Play / Memory）、预测性返回手势、键盘快捷键与指针悬停、屏幕圆角适配，以及 Google Drive `appDataFolder`（Android 支持查看云端数据、下载全部数据快照与 Markdown、从备份导入、按账号移除云端数据与跨账号转移 Memories）与 Apple 端私有 iCloud（CloudKit）同步（支持四步校验迁移同步位置）。

## 3. 事实边界与准确表述

| 避免使用的表述 | 原因 | 准确表述 |
| --- | --- | --- |
| 零追踪（绝对无追踪声明）、不收集任何数据 | Android 投屏使用的 Cast SDK 会向 Google 发送匿名使用与性能统计数据 | 没有广告；不设 Yoin 账号；Yoin 不收集你听了什么 |
| 你听什么只有你知道（stays yours alone） | 音源服务端会有记录；播放或查词时，歌词查询与本地化名称解析会向歌词源或 MusicBrainz 发送曲名、歌手或 ISRC | 评分、笔记和播放记录保存在本机 |
| 所有密钥都不进入系统备份 / 所有 AI 密钥都在云备份外 | **Gemini API 密钥目前保存在 Room 主数据库 `gemini_config` 表中**，可能随 Android 系统备份上传（不进 Google Drive 云同步）；而 DeepSeek、Qwen、自定义服务及外接搜索服务的密钥与配置保存在 `noBackupFilesDir/ai_provider.json`，不进入系统备份与云同步 | 音源凭据、NeoDB 令牌及 AI v2（DeepSeek / Qwen / 自定义与外接搜索）密钥保存在不可备份的本机私有目录中，不进入系统备份；Gemini API 密钥保存在本机主数据库中，不参与 Google Drive 云同步，但可能受 Android 系统备份影响 |
| 端到端加密云备份 | Google Drive 同步走 HTTPS 传输，并非端到端加密 | 通过你的 Google Drive 在不同设备之间同步评分、笔记与歌单备份等数据（存储在应用专用的 `appDataFolder` 隐藏空间中） |
| 只呈现经过验证的事实（only verified facts） | 检索与模型生成的内容仍可能存在偏差 | 通过你配置的 API 密钥与搜索服务检索并整理歌曲幕后班底与背景介绍 |
| 国内直连 / 国内网络直接连 | 作者常住海外，面向海内外中文用户，地域指向需明确 | 中国直连 |

## 4. 标准术语

- 测试状态统一写**封闭测试**或**开放测试**，不写“内测”“公测”。
- 音乐来源统称**音源**；指代 Navidrome、Gonic 等私有服务时写**自建服务器**。
- 界面标准中文名称：
  - `正在播放`、`专辑`、`资料库`、`艺术家`、`播放列表`、`歌曲 / 曲目`、`队列`、`首页`、`设置`
  - `Memories`（始终保留英文，禁用“回忆”）、`全部 Memories`
  - `剪贴簿`（新文案统一用剪贴簿，禁用早期手记中的“手账”）
  - `微件`（桌面小组件）
  - `别人怎么说`、`为你推荐`（Made For You）、`接着听`（Jump Back In）、`重温`（Rediscover）、`在你的其他资料里`
  - `关于`、`笔记`、`评论` / `短评`、`歌词`、`聚焦`、`在线 AI`（配置入口为“设置 › 功能 › 在线 AI”）
- 首页“音犹未尽？”模块的三个卡片标题为：`歌词看看`、`背景看看`、`别人看看`。
- 官网的博客栏目名称为`手记`。
- 禁止使用：`Nowledge Mem`、`nmem`（均不得出现在产品文案、规范或评审模板中）。
