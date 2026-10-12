# Yoin 简体中文本地化术语表与文案风格指南 (zh-Hans Glossary)

> 本文档为 Yoin 简体中文本地化重构的基准规范与审校凭据。已根据用户最终审定意见确认术语及语调风格。

---

## 1. 文案设计哲学与风格定调

### 核心调性：精准 · iPod 式的质感与疏离感 · 克制幽默
1. **精准（Precision）**：数字音乐与播放器领域的术语严谨统一。
   - `Library` 统一为 **资料库**；
   - `Artist` 统一为 **艺术家**（还原 iPod 经典纯粹质感）；
   - `Playlist` 统一为 **歌单**；
   - `Queue` 统一为 **队列**；
   - `Memories` 统一保留专有名词 **Memories**；
   - `Review` 统一为 **专辑评价**；
   - `Note` 统一为 **笔记**；
   - `Scrapbook` 统一为 **拼贴手册**；
   - `Scroll edge` 统一为 **顶部滚动边缘**（样式：潮线 / 网点 / 曲奇浪口）。
2. **克制与疏离感（Restraint & iPod Aesthetic）**：
   - 冷静、客观、优雅，界面如同精致的硬件仪器；
   - 不谄媚迎合、不说教、不随意拟人（杜绝「开启您的美妙音乐旅程」等 AI Slop 假热情）；
   - 界面文字是清晰的「标签」或「动作」，避免冗余的旁白与操作提示。
3. **适度幽默（Subtle & Dry, Not Overdone）**：
   - 幽默感点到即止，不滥用，绝不强行搞笑；
   - 在空状态、极端状态与异常提示中保留克制自如的微调侃（如「首页空空如也」「换不动了」），保持界面典雅从容。

---

## 2. 核心功能名称与术语裁定表

| 功能 / 概念 (EN) | 最终中文定稿 | 裁定依据与风格说明 | 主要出现位置 |
|---|---|---|---|
| **Home** | **首页** | 顶级导航 Tab，干净利落。 | `strings_home.xml`, `strings_component.xml` |
| **Library** | **资料库** | 经典大气的音乐管理总集，符合 Apple / iPod 习惯。 | `strings_library.xml`, `strings_component.xml` |
| **Memories** | **Memories** | 专有产品名词全量保持原样，不生硬直译。 | `strings_memories.xml`, `strings_home.xml` |
| **Artist** | **艺术家** | iPod 式经典质感，替代泛滥的「歌手」与公文腔的「艺人」。 | `strings_library.xml`, `strings_detail.xml` |
| **Playlist** | **歌单** | 现代中文音乐产品标准术语，彻底淘汰机翻体「播放列表」。 | 全局资源 |
| **Queue** | **队列** | 面板与操作均使用「队列 / 加入队列」，淘汰臃肿的「播放队列」。 | `strings_nowplaying.xml`, `strings_component.xml` |
| **Scrapbook** | **拼贴手册** | 专辑第 2 页的艺术票根与记录汇总，富有物件质感。 | `strings_detail.xml` |
| **Review** | **专辑评价** | 专辑层面的完整撰写评价，全量统一。 | `strings_detail.xml`, `strings_memories.xml` |
| **Note** | **笔记** | 单曲随笔或时间戳记录，统称「笔记」。 | `strings_nowplaying.xml`, `strings_detail.xml` |
| **Scroll edge** | **顶部滚动边缘** | Settings › Motion › Scroll edge。内容滑经顶栏的过渡边缘。 | `strings_scroll_edge.xml`, `strings_settings.xml` |
| **Tide line** | **潮线** | 默认顶部过渡动效波形。 | `strings_scroll_edge.xml`, `strings_settings.xml` |
| **Dots** | **网点** | 半色调网格碎裂动效。 | `strings_scroll_edge.xml`, `strings_settings.xml` |
| **Cookie wave** | **曲奇浪口** | 软尖齿轮花边波形动效。 | `strings_scroll_edge.xml`, `strings_settings.xml` |
| **Now Playing** | **正在播放** | 播放器展开态与状态标准用语。 | `strings_nowplaying.xml`, `strings_shell.xml` |
| **Activities** | **动态** | Home 分区，记录近期播放与访问。 | `strings_home.xml` |
| **Jump Back In** | **接着听** | Home 分区，断点续听专辑与单曲。 | `strings_home.xml` |
| **Recently Added** | **最近添加** | Home 分区，本月新入库唱片。 | `strings_home.xml` |
| **Rediscover** | **重温** | Home 分区，翻出久未播放的旧爱。 | `strings_home.xml` |
| **Recently Played** | **最近播放** | Home 分区，各设备近期播放记录。 | `strings_home.xml` |
| **Your Playlists** | **你的歌单** | Home 分区，资料库中的歌单。 | `strings_home.xml` |
| **Diary** | **日记** | Memories 展开的阅读与书写态。 | `strings_memories.xml` |
| **Track / Song** | **曲目 / 歌曲** | 列表排版和序号称「曲目」，日常称「歌曲」。 | 全局资源 |
| **Single / EP** | **单曲 / EP** | 发行类型，EP 保留大写。 | `strings_detail.xml` |
| **Compilation** | **合辑** | 多人合辑与精选集。 | `strings_detail.xml` |
| **Album** | **专辑** | 标准音乐实体。 | 全局资源 |
| **Devices / Cast** | **设备 / 投播** | 音频投射（Google Cast）统一称「投播」。 | `strings_nowplaying.xml`, `strings_shell.xml` |
| **Cloud sync** | **云同步** | 笔记、评分与设置的 Google Drive 同步。 | `strings_settings.xml` |
| **Welcome guide** | **新手指引** | 首次引导与设置里的重温向导。 | `strings_scroll_edge.xml`, `strings_settings.xml` |
| **Ask Gemini** | **问 Gemini** | AI 辅助问答与曲目考据。 | `strings_nowplaying.xml`, `strings_settings.xml` |
| **Song info** | **歌曲档案** | 歌曲详细背景、词曲与制作人信息。 | `strings_component.xml`, `strings_nowplaying.xml` |

---

## 3. 30 条代表性字符串改写对照表

| # | 资源 Key | 英文原文 (Original) | 当前中文 (Current / AI Slop) | 建议新中文 (Final Proposed) | 优化要点 |
|---|---|---|---|---|---|
| 1 | `landing_hello_body` | I play the music you already have, from your own server, Spotify or Apple Music. | 我负责播放你已经有的音乐：你自己的服务器、Spotify 或 Apple Music。 | **我只会播放你已拥有的音乐：来自你的服务器、Spotify 或 Apple Music。** | 用户审定：精准划定播放器边界，语气干脆。 |
| 2 | `landing_apple_body` | Apple charges developers for one. Mine is free to use. | Apple 向开发者收费，我的可以免费用。 | **您需要一个开发者令牌使用 Apple Music Kit。如果没有，您也可以选择由 Yoin 提供。** | 用户审定：清晰说明机制，庄重得体。 |
| 3 | `landing_about_title` | Here\'s my part, and what stays with your service. | 哪些归我管，哪些归你的音乐服务，先说清楚。 | **有些事归 Yoin，剩下的依然由你的音乐服务打理。** | 客观划清职能，克制优雅。 |
| 4 | `landing_spotify_body` | I\'ll walk you through it. | 我带你一步一步来。 | **跟着指引来，几步就好。** | 褪去保姆式说教，利落明确。 |
| 5 | `landing_edge_title` | When you scroll, how should things slip under the top bar? | 滚动时，内容怎么溜进顶栏下面？ | **向上滑动时，内容穿过顶栏的样式：** | 规整为专业设计设定，淘汰口语「溜进」。 |
| 6 | `home_greeting_morning` | Good morning | 早上好 | **早** | 极简、干脆、克制，带有 iPod 式的高级疏离感。 |
| 7 | `home_placeholder_jump_back_in` | Nothing to jump back into | 没有可以接着听的 | **手头没有还没听完的唱片。** | 结合实体唱片意象，客观陈述。 |
| 8 | `home_placeholder_activities` | No recent activity | 最近没有动态 | **最近没有播放记录。四周很安静。** | 适度画面感，幽默不过头。 |
| 9 | `home_section_rediscover_supporting` | Albums and songs you rated or wrote about, not played in a while | 你评过或写过、有一阵子没播放的专辑和歌曲 | **评过分或写过笔记，但已落灰多时的专辑与单曲。** | 消除 AI 废话，沉淀感十足。 |
| 10 | `home_edit_all_hidden_title` | Home is empty | 首页是空的 | **首页空空如也** | 用户审定：简练纯粹，不做多余发散。 |
| 11 | `library_empty_search` | 没有找到结果 | No results found | **未找到匹配结果** | 干净专业的搜索空状态。 |
| 12 | `library_empty_favorites` | No favorites yet | 还没有收藏 | **暂无收藏内容** | 克制客观。 |
| 13 | `library_error_reshuffle` | Couldn\'t reshuffle songs | 无法换一批 | **换不动了，再试一次看看。** | 适度自然幽默，摆脱公文腔。 |
| 14 | `np_empty_nothing_playing` | Nothing playing | 没有在播 | **没有正在播放的内容** | 用户审定：准确清晰，不强加修辞。 |
| 15 | `np_pill_write` | Write | 写下 | **记一笔** | 随手记录的轻快动词。 |
| 16 | `np_about_api_key` | No Gemini API key | 没有 Gemini API 密钥 | **未配置 Gemini 密钥，背景资料无法查询。** | 清楚交代原因与结果。 |
| 17 | `np_msg_playback_interrupted` | Playback was interrupted. | 播放中断了。 | **播放被打断了。** | 口吻自然。 |
| 18 | `np_msg_already_language` | Lyrics already appear to be %1$s | 歌词看起来已经是 %1$s | **歌词本来就是 %1$s，无需翻译。** | 直率客观，避免机械感。 |
| 19 | `np_msg_cant_save` | Can\'t save — track info unavailable | 无法保存 — 缺少曲目信息 | **无法保存：曲目信息不完整。** | 规范标点与用词。 |
| 20 | `mem_empty_title` | No memories yet | 还没有回忆 | **暂无 Memories 记录** | 保持 Memories 专有名词。 |
| 21 | `mem_snackbar_save_review` | Couldn\'t save the review. Your draft is kept. | 乐评没保存。草稿还在。 | **无法保存专辑评价，草稿暂时保留。** | 区分尚未保存的草稿与已保存内容，统一使用「专辑评价」。 |
| 22 | `detail_album_never` | Never | 从未 | **从未播放** | 精准的播放历史状态。 |
| 23 | `detail_scrap_not_yet` | Nothing yet | 还没有 | **暂无记录** | 拼贴手册空状态，克制典雅。 |
| 24 | `detail_playlist_empty_hero` | This playlist is empty. | 播放列表是空的。 | **歌单是空的。** | 统一为「歌单」，简短有力。 |
| 25 | `settings_scroll_edge_tide_desc` | Covers and text sink under two soft waves at the edge. Stays calm even when you scroll fast. | 封面和文字在边缘沉进两道柔和的波浪，快速滑动时也很安静。 | **封面与文字沉入边缘的两道微澜。即便快速滑动，界面也足够克制。** | 消除机翻感，文雅克制。 |
| 26 | `settings_scroll_edge_dots_desc` | Covers break into a fine dot screen as they pass under the bar. Yoin\'s original look. | 封面经过顶栏时碎成细密的网点。Yoin 最早的样子。 | **封面掠过顶栏时碎成细密的半色调网点。Yoin 最初的模样。** | 准确使用「半色调网点」视觉术语。 |
| 27 | `settings_provider_local_summary` | Coming later | 以后再来 | **还在路上** | 修正旧译「以后再来」的严重歧义。 |
| 28 | `settings_sync_know_drive` | Saved in a hidden Yoin folder in your own Google Drive. Only Yoin can open it. It\'s protected by your Google account, not end-to-end encrypted, and counts toward your storage. | 保存在你 Google 云端硬盘里的 Yoin 隐藏文件夹。只有 Yoin 能打开它。受你的 Google 账号保护，非端到端加密，计入存储空间。 | **存放在你 Google Drive 的 Yoin 专属隐藏目录，仅 Yoin 可访问。受 Google 账号保护而非端到端加密，会占用网盘配额。** | 严谨客观的隐私与配额说明。 |
| 29 | `settings_sync_summary_storage` | Google storage full | Google 存储空间已满 | **Google 网盘空间告罄** | 紧凑有力。 |
| 30 | `player_spotify_track_only_fallback` | Spotify playback fell back to track-only mode. Open Settings → Spotify and reconnect to restore album / playlist context. | Spotify 已改为仅播放单曲。打开设置 → Spotify 并重新连接，以恢复专辑和播放列表播放。 | **Spotify 降级为单曲播放模式。前往「设置 → Spotify」重新连接以找回专辑与歌单上下文。** | 专业精准，术语统一。 |

---
*术语表已按审校意见锁定，后续全量改写将严格以此为准执行。*
