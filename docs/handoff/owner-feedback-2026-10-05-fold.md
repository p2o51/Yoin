# Owner 反馈 · 2026-10-05 晚（三星折叠屏实测 wip3）

> 来源：owner 在对话里口述（语音转文字，个别词是识别错误，括号里是我的理解）。
> 状态列：📌 原则 · ⏳ 处理中 · ❓ 待 owner 澄清 · ↗ 转给别的会话 · ✅ 已修（未提交）

| # | 区域 | owner 原话（整理） | 理解 / 处理 | 负责 | 状态 |
|---|---|---|---|---|---|
| 1 | 专辑 | 专辑里那两个点，上下 padding 不对称，不舒服 | 详情页 pager 的两个页点（第 1/2 页指示），上下留白要对称。已改（未提交）：页点上 5dp、第 2 页顶 4dp、Medium 第 1 页顶 0、横屏第 1 页顶 4dp；平板实测 ≈15.5dp / 16.5dp | 本会话 | ✅ |
| 2a | 播放（最严重） | 点 playback（尤其在专辑里、Spotify）时 context 设计不太对，看看适配是不是有问题 | 查 `ActivityContext` → Spotify Web API context 播放（album 用 `offset.position = startIndex`）、App Remote 回退、Subsonic/Apple 各自的起播路径。已修（未提交）：起播统一走 Web API，按 Spotify 本机设备 id 播放（之前会在账号的「活跃设备」上播，平板实测曾放到 owner 的 SM-F971Q 上）；专辑/歌单保留 context，Liked Songs 用 `spotify:collection:tracks`；Artist/主页/搜索/队列点歌用 `uris` 临时列表；随机按钮在 context 上开 Spotify shuffle | 本会话 | ✅ |
| 2b | 播放（最严重） | 每次播放都会乱序；朋友点第 4 首每次放第 3 首，点第 3 首放第 2 首 | 两个症状：① 起播顺序被打乱（疑似 Spotify 账号侧 shuffle 状态被沿用）② 稳定差一位（疑似 position offset 和 Yoin 列表不对齐，应改用 uri offset）。根因（录屏 + 平板实测）：专辑按 `offset.position` 起播，这张 2026 专辑 Spotify 侧少算一首，点第 4 首放第 5 首；改为 `offset.uri` 后实测点第 4 首放第 4 首、点第 3 首放第 3 首。「乱序」：以前无 context 的起播会用 App Remote 把整张列表逐首 `queue` 进用户自己的 Spotify 队列（Liked 一次 200 首），残留会在后续播放前插队——已停止；**owner 需在 Spotify App 里手动清一次现有队列** | 本会话 | ✅ |
| 3 | 功能 | 「还有一些功能的时装什么的，可能也需要你去修改」 | 「时装」可能是「实装」或「视觉」，指哪些功能不明确 | — | ❓ |
| 4 | 专辑 / 艺人 | 页面底下 split button 里的那些功能需要小修小改 | 审一遍 Album / Artist 底栏 SplitButton 下拉里的每一项（文案、图标、能力门控、顺序），列出具体改法。F1 = 这些功能的实装。已做（未提交，平板实测）：▾ 加 Play next / Add to queue / Add to playlist / Open in Spotify·Apple Music；Share 带链接（`MusicMetadata.webUrl`）；窗口页和 Wide 详情列共用 DetailMenu | 本会话 | ✅ |
| 5a | 歌词分享卡 | 分享卡片里的字体太大，怪怪的 | `LyricsShareCard` 字号阶梯重订。已改（未提交）：歌词 28/24/22sp → 22/19/17sp（行高 1.3），译文 16 → 14sp；平板实测 | 本会话 | ✅ |
| 5b | 歌词分享卡 | 左下角「邀影」（Yoin）图标改用 app 的单色图标，比现在实心的好看 | 用 monochrome / themed icon 的几何（Icon-MONOCHOME）。已改（未提交）：换成 ic_yoin_launcher_monochrome 线稿，按卡片墨色着色、放大 1.7 倍填满 22dp；平板实测 | 本会话 | ✅ |
| 5c | 歌词选择 | 复制页的图标用的是 Material Icon，应按现在「Lumin Design」（Yoin 设计语言 / Yoin Symbols）自己画 | 在 yoin-symbols 里补 copy（及选择工具栏用到的其他）glyph。已做（未提交，两个仓库）：yoin-symbols 新增 copy / open_in_full / close_fullscreen（generator 生成），Yoin 换用后 App 里已无 Material Icon，删掉 material-icons-extended | 本会话 | ✅ |
| 6 | NP 末句拉长 | 拉长时（Google 字体调宽度轴）每帧之间有抖动；中文直接整体形变反而没问题。能解决抖动就保留，否则改成整体形变 | I-2 拉丁文走 `wdth` 轴逐帧重排导致抖动；改为与中文一致的整体形变（或消除抖动）。根因：不是 wdth 轴，而是「行占满 92% 以上」时改用逐级加字距、每级重排版，字形一格一格跳；已改为所有文字统一整体 scaleX（绘制阶段、不重排），满行也可伸进行内边距 3%（未提交，单测已过，平板未实测） | 本会话 | ✅ |
| 7a | 云同步 | 专辑同步过来了，但专辑的评价标题（AI 生成的标题）没同步过来，很严重 | Memory 文案 / AI 拟题当前在 v1「不同步」列表里。云同步会话已做（未提交）：新 kind memory_copy / memory_title，带 promptHash，对方不用重新生成；需装新包 | 云同步会话 | ✅ |
| 7b | 云同步 | 「go to album」后每首歌的笔记可以同步 | 确认可用，无需处理 | — | ✅ |
| 7c | 云同步 | 每首歌 Gemini 生成的内容也应该保存（同步）下来，里面有用户自己问的问题，比较重要 | About/Ask 当前在 v1「不同步」列表里（需先给 `song_about_entries` 加语言列）。云同步会话已做（未提交）：kind song_about，按语言写入，切语言清表不传删除 | 云同步会话 | ✅ |
| 7d | 主页 | Home 里显示的专辑是什么逻辑？我目前只有 5 张，没有更多 | 需要解释各区块的数据来源；新设备上播放历史不同步，可能是原因之一 | 本会话（解释） | ⏳ |
| 7e | 专辑 About | 刚听的一张专辑，所有 About 都显示成特别肥大的椭圆形；有时 About 没法完全显示 | 折叠屏宽度下 About 内容的形状 / 截断 bug，需复现。根因（调查）：第 2 页 FactTag 用 CircleShape 裁多行内容 → 大椭圆且切字；改为 16dp 圆角 + 值最多 2 行（未提交，未拿到真实 About 数据复现） | 本会话 | ✅ |
| 7f | 专辑第 2 页 | 标题可以放在封面右边，不用单独放在上面 | 拼贴页头部重排：封面左、标题右。按「标题 = AI 拟题」理解：从封面上方单独一行挪到封面右边（衬线 22/28sp）；手机上乐评移到封面下方，宽屏乐评/便签跟在标题下（未提交，平板竖屏与外屏宽度实测） | 本会话 | ✅ |
| 7g | 字体 | 很多地方还是 Roboto，不知道具体哪些，得改 | 全仓扫描未走 YoinTypography 的文字（裸 `TextStyle()`、M3 默认样式、系统组件）。扫描结果：Compose 里只剩 7-26 定的「用户正文用系统字体」（Memories 卡乐评/笔记、日记写作框、专辑第 2 页乐评/便签）落到 Roboto；没有 M2 组件、没有裸 TextStyle。是否推翻那条规则待 owner 定（T1） | 本会话 | ❓ |
| 7h1 | 评分页 | Rate & Comment 页面太老；最好把 NeoDB、Rating、Comment 结合起来，更好用、更 expressive | 重设计，先出方案 / 原型给 owner 选。owner 10-05 深夜：不等子 agent 额度，本会话自己做。方案页（可操作原型 + R1–R4）：https://claude.ai/artifact/FYwL6QMBHTNP2gT3rEKjdW。owner 10-06 拍板后已做（未提交，平板实测）：徽记 + NP 同款滑条（拖时徽记跟动、松手盖章）、一个会长大的输入框、关面板自动保存并同步 NeoDB、按长短归位短评 / 长评、不显示书架 | 本会话 | ✅ |
| 7h2 | 播放队列 | 专辑的队列、Now Playing 的队列功能也可以改 | 重设计，先出方案 / 原型。同上，本会话自己做。同一方案页 Q1–Q4。owner 10-06 否掉页签方案，改回独立按钮 + Spotify UX。已做（未提交，平板实测）：Playing from / Now playing / Next in queue（Clear）/ Next from 三段、拖把手排序、左滑移除、Spotify 式入队位置；Spotify 只读、Apple 只能移除 | 本会话 | ✅ |
| 7h3 | 文案 | 专辑第 2 页「Best on the record」不知道什么意思（AI slop） | 改成一看就懂的说法或去掉。已删（未提交）：最佳曲目只靠墨色票根 + 大分数表达 | 本会话 | ✅ |
| 7h4 | 文案 | 「×1」「×2」改成「播放 1 次」「播放 2 次」这类 | 播放次数改成文字表述（随 app 语言）。已改（未提交）：第 2 页票根「1:10 · 9 plays」，放不下只留「9 plays」；艺人页 Most Played「12 plays」 | 本会话 | ✅ |
| 8 | AI 专辑标题 | AI 生成的专辑标题也应给用户空间自己改，比如在专辑第 2 页或 Memory 里点标题就能改 | 第 2 页标题可点改（本会话）；Memory 侧由 Memories 会话做；改过的标题要能同步（云同步会话）。Memories 会话建 album_memory_titles（v29）+ AlbumMemoryTitleStore；云同步会话写适配器（最后修改为准，删行=恢复 AI 并同步）。第 2 页点标题就地改名（无描边输入框，完成/回车/失焦保存，清空=恢复），改过后旁边出现星芒按钮可恢复 AI 标题；主页 JBI 记忆卡也按「用户 > AI > 模板」显示（未提交，平板实测改名与恢复） | 本会话 + ↗ | ✅ |
| 9 | 等宽字体 | 「nomo」（mono，等宽 Google Sans Code）字体应全部换掉，影响观感，比如专辑里的 Last Play；唯一好的是专辑最底下的小票，那里可以保留 | 全仓扫 `--mono`/Google Sans Code 用法，只保留专辑第 2 页底部收据。已改（未提交）：专辑/艺人/歌单/第 2 页全部换成 Google Sans Flex（数字用 tnum），只留第 2 页底部收据为等宽；小节标签暂保留下划线；设置页两处（云同步、服务添加页）未动 | 本会话 | ✅ |
| 10.1 | 专辑第 2 页 | 底下显示「Not yet」，不知道是什么情况 | 查清它是什么（疑似未评曲目的占位），改成看得懂的表达或去掉。已改（未提交）：有任何一首留过东西就不显示；整张专辑都空时只留一行「Scores and notes you leave show up here.」 | 本会话 | ✅ |
| 10.2 | 专辑字段 | Last Play 需要；Rating 这个字样不需要，有徽章别人就知道是什么意思 | 去掉 Rating 文字标签，保留 Last Play。已改（未提交）：去掉「Rating/Avg.」字样；只有算出来的均分才在旁边显示一行「2/4 rated」 | 本会话 | ✅ |
| 10.3 | 专辑乐评 | 写了就把 comment 留在那，加上写的日期；没写过就留一个笔的图标 | 乐评区：有内容 = 文本 + 日期；无内容 = 笔形图标入口。部分完成（未提交）：第 1 页有字=直接显示文字（点按编辑）、无字=笔图标；第 2 页空状态=虚线圆圈+笔。写作日期需要新列 reviewUpdatedAt（DB 版本要与 Memories/云同步会话协调）。F4 已做（未提交）：album_ratings.reviewUpdatedAt（DB v30，迁移回填旧乐评、迁移测试链补齐），只在文字真变化时更新；第 1 页乐评下、第 2 页乐评卡底部显示「Today / 3 days ago / Oct 5 / Oct 5, 2025」；云同步合入由云同步会话补；平板实测 | 本会话 | ✅ |
| 10.4 | 原则 | 尽量不要用大片文字，页面保持简单、有趣、高级 | 第 2 页及相关改动的总原则 | — | 📌 |
| 11 | Library 搜索（QA 中发现） | — | 搜索框打字稍快就丢字（「you seem pretty sad」→「yo sem prtt sd」），疑似和歌词搜索修过的同类竞态（VM 把旧 query 写回输入框）。已修（未提交）：输入框记住自己发出的查询，VM 回传的旧值视为回声忽略，只有外部重置才写回；平板实测快速输入整句不丢字、清空正常 | 本会话 | ✅ |
| 12 | 详情底栏（QA 中发现） | — | 折叠屏外屏宽度（≈369dp）下详情页底栏右侧被截断：「Nothing playir / Tap to open playe」。实际是胶囊空闲文案（Nothing playing / Tap to open player）不滚动也不省略被硬切；改为放不下时省略号（未提交，未在外屏实测） | 本会话 | ✅ |
| 13 | Library 密度（10-05 深夜） | Fold 8 内屏全宽下 Album / Artist 一行只有 5 个，元素太大；手机一页约 9 个封面，大屏全宽才 10 个，信息密度太低 | 大屏网格按宽度加列、缩小单元，一屏显示的数量明显多于手机。已改（未提交，平板 + Fold 模拟实测）：Compact 以上不再夹 720dp、32dp 页边，Albums/Artists 统一 Adaptive(96dp)：Fold 内屏 7 列、平板竖屏 6 列、横屏 11 列 | 本会话 | ✅ |
| 14 | Apple Music 起播位置（10-05 深夜） | 朋友和 owner 手机上都复现：MusicKit 播放有时从第 15 秒或第 90 秒开始，触发时机不明 | 排查 MusicKit 起播路径里的 seek / 续播位置。根因（反编译 MusicKit 1.1.1）：`stop()` 把正在播的位置记成 `savedPlaybackPosition`（冷启动也会从上次会话恢复），下一次 `prepare` 把它套到新队列的第一首上——新歌从上一首停下的位置开始。已修（未提交，平板上 Apple Music 账号坏了没法实测）：换队列改用 pause 不再 stop；prepare 后起始曲第一次播放 / 暂停时若超过 1 秒且不是用户自己 seek 的，拉回 0:00 | 本会话 | ✅ |
| 15 | 折叠屏分栏换专辑动画（10-05 深夜） | 分栏打开一个 Album 后再点下一个 Album：前一个往左滑，但滑过界后不消失、还停留一会，下一个才打开，很难看 | 修同窗分列右栏的前进转场。根因：从 shell 点另一张专辑是「换根」，却走了推入转场，旧页后退 96dp 时右栏没裁边、滑到 shell 上停到动画结束。已改（未提交，平板横屏录屏逐帧验）：右栏裁边；换根改 M3 fade-through（旧页快速淡出、新页 0.96 淡入放大），栏内推入不变 | 本会话 | ✅ |
| 16 | Recently Added 长度（10-05 深夜） | 折叠屏和平板上 Recently Added 显示得太少，应该更长 | 大屏多给几行 / 多给条目。已改（未提交，平板横屏实测）：feed 单元 ≥ 3 时曲目 4 行、专辑卡两张一叠（同高、卡片不放大）；收录窗口 7 天 → 30 天 | 本会话 | ✅ |

## Owner 答复（2026-10-05 晚）
- F1：第 3 条「功能的时装」= 专辑页、艺人页底部 split button 里那些功能的**实装**，并入第 4 条。
- F2：可以。另外查 Spotify、Apple Music 有没有「最近播放」端点；更重要的是**主页多做一些板块**，例如 Spotify 主页的「我的歌单」。→ 「Your Playlists」板块已做（未提交，平板实测）；端点：Spotify /me/player/recently-played（已在用）、Subsonic getAlbumList2?type=recent、Apple /v1/me/recent/played；三家已接成 MusicLibrary.getRecentlyPlayedAlbums，主页新增「Recently Played」板块（去掉 Activities 已显示的专辑；未提交、单测通过、平板未实测）。
- F3：小节标签的下划线先保留。
- F4：做乐评写作日期 → 已做（未提交，DB v30），云同步合入与 Memories 日期同步改好。
- F5：设置页的技术字符串保留等宽。
- F6：只有默认标题时也允许改名和恢复；主页与 Memories 的默认标题统一。→ 已做（未提交）：Memories 会话出了共享解析器 AlbumMemoryTitleResolver（5e60fea5），主页 JBI 与第 2 页都改用它；motif 标题用 App 字体、可改名可恢复（文案「Restore Yoin's title」）。
- W1–W8（wip3 交付页的选择题，晚上在对话里重新列出后答复）：
  - W1：「为啥必须连续的才能 story」→ 连续不是出图的前提。改成照 spotoolfy 任意挑行（最多 15 行），Card / Story 保留；跳着选时卡片上段与段之间留 20dp、复制的文字段间空一行（未提交，单测通过）。
  - W2 a：Subsonic / Apple Music 的「上一首」改走 Media3 `seekToPrevious()`，播放超过 3 秒先回本曲开头，与 Spotify 一致（未提交）。
  - W3 a：艺人页 Avg. 换成 Memories 徽章（一圈一张发行，有评分的刻实线；不可点；Bun 删除）（未提交）。
  - W4 a / W5 a / W6 a / W7 a：保持现状。
  - W8 a：Activities 条目不够时，供给断掉那一行的最后一张卡拉宽到行尾（单元网格、手机构图、手机横屏同一规则）（未提交，单测通过）。

## Owner 答复 · 评分与队列方案（2026-10-06 凌晨）
- R1：保留打分，用滑条，换个样式就行；**徽章下面放滑条，滑的时候徽章播动画**。不要直接转徽章（像 Google 旧闹钟那样的转盘手势被很多人抱怨）。
- R2：未答，按推荐 a（一个输入框，发 NeoDB 时按长短归位短评 / 长评）。
- R3：同意自动同步 NeoDB。
- R4：书架状态先不显示。
- Q1：原型方案（NP 里加 Queue 页）不行。队列优先级更高，要有单独的按钮；放在页签里 16:9 屏幕上直接被挡、也不能左右滑、要先点再滑。**放回原来的地方（独立 Queue 按钮 + 底部弹层），UX 照 Spotify，UI 做得好看一点，不要复杂**。
- Q2–Q4 未答：Q2/Q3 按 Spotify 的做法（拖动排序、移除、「你加的」和「来自专辑」分段）；Q4（专辑页标出队列）先不做。

## Owner 反馈 · wip7（2026-10-06 上午）
- NeoDB 推送成功，但 Memories 日记末尾仍显示「Push to NeoDB」（评分面板显示 Synced 是对的）→ 改成和评分面板同一套状态的一行字（Synced / Syncing / Sync / Retry），日记里存乐评后自动推送（未提交，单测通过；平板没有 NeoDB 账号，这一行没法实测）。
- Spotify 队列、Apple Music 起播位置 / 折叠屏分栏：owner 稍后测。触感 OK。
- 评分面板「上面只有个徽章，空空的，可以加个标题，比如『为 xxx 评分』」→ 加了小字「Rate」+ 专辑名（未提交，平板实测）。
- 「有的专辑 memory 显示 18 plays since august 这种，没必要，没有标题就行」→ 同意，三种动机短句全部取消（数播放、数季节、数笔记），没有 AI / 用户标题时 Memories 卡标题位放专辑名，主页 JBI 卡和专辑第 2 页不放标题行（未提交，单测通过）。

## Owner 反馈 · wip8（2026-10-06 下午）
- 专辑页评分面板 NeoDB 显示 Synced（确认 OK）。
- 「Say something about 那里上下有 padding，文字会截断」→ 输入框不再限高，随字数长高，面板整体滚动并跟随光标，滚出边缘的字渐隐（未提交，平板实测）。
- 「Rate 放页面上面了，想放左上角和徽章并排」→ 改成左上角「Rate」+ 专辑名，右边并排徽记（未提交，平板横屏 + 手机宽度实测）。
- 顺带（多 agent 审查后修）：① NeoDB 推送不再用推送前的快照整行覆盖——只在行里仍是发出去的那份时清脏位（`AlbumRatingDao.markRatingPushed / markReviewPushed`），推送途中的新改动保留并下次再推；② Memories 那行：别处同步成功后不再残留「Retry」，从设置登录 / 退出 NeoDB 回来会重读；③ 评分面板的 NeoDB 行在面板开着时跟着改动变回「Syncs when you close this」；④ 面板里从滑条上开始的竖向滑动不再改分。
- 未改（转云同步会话）：云同步拉下来的专辑评分 / 乐评行脏位为 0，本机会显示 Synced 但从没推过 NeoDB。

## Owner 反馈 · 性能（2026-10-06 晚）
- 「Lyrics 展开时预测性返回手势卡顿，还有发热，不确定是不是这个 app」→ 确认是 app（平板 Perfetto 实测）。三个主因：① 详情页终身半透明，被盖住的首页一直在每帧重绘（和可见窗口共用主线程，返回手势卡顿的主因）；② Compose 的 ContentCapture 每帧遍历语义树约 3 ms；③ 一堆看不出来的持续动画每帧重组/重绘（隐藏的播放控件波形、详情页背景漂移、迷你播放条波形每帧）。已修（未提交，单测 2095 通过，平板实测）：被盖住的窗口冻结动画（`CoveredWindowAnimationGate`，返回手势一开始就解冻正下方那层）、关 ContentCapture、波形 30 Hz、漂移 500 ms 一步、返回预览改绘制期缩放、帧率投票不再改修饰链结构。结果：歌词展开静止 61–123 → 7–15 帧/秒、CPU 79–86% → 15–22%；返回手势拖动 69 → 92 帧/1.5 秒（满帧）。
- 待 owner 定：迷你播放条和 NP 进度条的波形只要在播且看得见，就让整屏每秒重画 30–60 次（这块 GPU 每次都整窗重画）；首页标题跑马灯无限循环。

## Owner 答复（2026-10-07）
- 歌词返回手势改成直接跟手 + AOSP 位移后：「正常了」。
- T1：要换 → 用户的字（乐评、笔记、日记、Memories 摘录、专辑第 2 页）改用 Google Sans Flex，全 app 不再有 Roboto。随后提交并推送。

## Owner 答复（2026-10-08）
- 图标库建 GitHub 仓库 → `p2o51/yoin-symbols`（public，README 原本就指向这个地址）。
- 返回手势规范文档纳入版本管理（`.claude/skills/` 不再被忽略）。
- P1：首页和播放页的波形**都要一直流动**，不做静止后停下。
- P2：首页标题跑马灯**一直滚动**，不限次数，不要擅自让它停下。

## 备注
- owner 的测试机：三星折叠屏（外屏 Compact、内屏接近方形 Medium / Expanded）。Pixel Tablet 上用 `wm size` 模拟两块屏复现。
- 7a / 7c 属于云同步会话的范围（`data/sync/**`、`docs/cloud-sync.md`），已转过去；本会话不改同步代码。
