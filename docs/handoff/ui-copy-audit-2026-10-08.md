# 界面文案与分隔点排查（2026-10-08）

Owner 的判断：之前（主要是 ChatGPT 写的）界面里有两类东西读起来像 AI 写的：

1. 用「·」「•」「・」「—」把几个并列的属性串成一行（"Album · 11 songs"、"On · Synced 2 min ago"）。
2. 界面文字在讲解、提问、鼓励、拟人，或者一直挂在屏幕上的提示行。

这份文档是全量清单，外加每一类的改法。**执行方是 Cursor**，按包分批做，做完再接着做多语言抽取（见文末 prompt）。行号以 2026-10-08 的工作区为准，改之前先重新 grep。

代码路径都相对 `app/src/main/java/com/gpo/yoin/`。

---

## 一、规矩

### 1.1 不用分隔符号，用排版分层级

界面里**不再用任何字符**把并列属性连成一句：「·」「•」「・」「|」「—」「–」都不用。层级和分组交给排版：

| 手段 | 怎么用 |
|---|---|
| 颜色 | 主信息用 `onSurface` 或 `onSurfaceVariant`，次要信息用 `onSurfaceVariant` 的 60%，或者用 accent 色 |
| 字重 | 数字和关键值用 Medium/SemiBold，单位和说明用 Regular |
| 字号 | 类型标签（Album / Single / Playlist / Artist）用 `labelSmall`，内容用 `bodySmall`/`bodyMedium` |
| 间距 | 同一行里的分组用 **独立的 Text + 横向间距**（同组内 4dp，组和组之间 12dp），不用空格拼字符串 |
| 换行 | 状态和细节分两行：第一行状态，第二行细节 |

新建一个共享组件放在 `ui/component/MetaLine.kt`，所有改造都走它，不要每个页面各写一份：

```kotlin
/** One metadata line: groups laid out with gaps and weight/colour contrast, never a glyph. */
@Composable
fun MetaLine(
    groups: List<MetaGroup>,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    maxLines: Int = 1,
)

sealed interface MetaGroup {
    /** A count or measure: the number emphasised, the unit muted ("12 tracks", "44 min", "3 plays"). */
    data class Stat(val value: String, val unit: String) : MetaGroup
    /** Plain text at the line's normal emphasis (artist, album, host). */
    data class Plain(val text: String) : MetaGroup
    /** A kind label (Album, Single, Latest): smaller, accent or muted. */
    data class Kind(val text: String) : MetaGroup
}
```

- 用 FlowRow 或者自定义 Layout。宽度不够时**从最后一组开始整组丢掉**，不要把中间那组截成省略号。
- `Stat`：数字用 `withTabularFigures()` + Medium；单位用同字号 Regular，颜色 60%。中文写成「12 首」「44 分钟」，数字和单位之间不留空格（等 i18n 阶段用 plurals 资源）。

### 1.2 能删就删

很多分隔符连起来的信息本身就是多余的：

- **实体类型已经靠封面背后的形状表达了**（卡片底板形状 = 实体类型 → MaterialShapes）。所以 Jump Back In 卡片下的 "Album · Artist" / "Single · Artist" **只留 Artist**，"Playlist · owner" 只留 owner。
- Activities 卡片上的 "Album · 9h ago" 同理：类型去掉，只留时间。

### 1.3 文案：界面文字是标签，不是旁白

- 不写操作说明（"Tap to …"、"点…可以…"、"Drag to reorder"、"Long-press to copy"）。要么让元素本身就是按钮（给它一个动作标签，如「载入歌曲信息」），要么删掉。
- 不提问（"What is this song aiming for?"）。占位文字用名词或动词短语（「问 Gemini」）。
- 不鼓励、不预告、不拟人（"this feed will start filling in"、"we'll fetch…"、"Changes wait on this device"、"come back here when…"）。
- 空状态：一个短标签（"No activity yet"），去掉第二行解释。以后有涂鸦插画的地方，标签也可以不要。
- 错误：一句话说清发生了什么，加一个真按钮（Retry），不要写成 "… · Retry" 或 "Tap to try again"。
- 不要常驻提示行：只在需要的那一刻出现，然后自己消失。

### 1.4 例外（保留不改）

- **离开 App 的纯文本**：分享文字、剪贴板、写进用户乐评里的同步冲突标记（下表 #40、#50、#51）。纯文本没有排版可用，分隔符可以留着。
- **专辑小票**（AlbumScrapbook.kt:349 "ALBUM — ARTIST"，等宽字体）：它是「物件」，只保留英文、不改。
- **Memories 日记的提问语气**（MemoryVoice.kt，design.md:244/256/258 已拍板）：这是作者声音，不在这次范围内，单列为 owner 待定事项。

---

## 二、分隔符清单（51 处）

改法缩写：**M** = 换成 `MetaLine`；**删** = 去掉多余部分；**两行** = 状态 / 细节分两行；**按钮** = 动作改成真按钮；**留** = 例外，不改；**定** = 需要 owner 拍板（design.md 里已经拍过，现在要不要一起改）。

### Home
| # | 位置 | 现在 | 改法 |
|---|---|---|---|
|1–3|ui/home/HomeEditorialContent.kt:1937 / 2096 / 2175（Activity Hero / Wide / Strip）|"Album · 9h ago"|删类型，只留时间（或在 Hero 卡上把类型做成 `labelSmall` 放在时间上一行，参照 ActivitySmallCard:2000 已有的「两行、无点」写法）|
|4|HomeEditorialContent.kt:2137（Strip）|"Title・Artist"|标题 SemiBold + 12dp 间距 + 歌手 Regular 60%，两个 Text|
|5–9|ui/home/HomeViewModel.kt:887/934/958/969/981 → HomeWidgetGrid.kt:662|"Album · Artist" 等|删类型，只留 Artist / owner|
|10|HomeViewModel.kt:1029 → HomeEditorialContent.kt:1956|"2024 · 12 songs · 44 min"|M：Plain(2024) + Stat(12, songs) + Stat(44, min)|
|11|ui/home/RediscoverSection.kt:621|"3 notes · Not played in Yoin for 7 months"|两行：第一行 Stat(3, notes)，第二行改短成「7 个月没听」那种标签|
|12|RediscoverSection.kt:628|"Artist · Album"|M：Plain(artist) + Plain(album, 60%)|
|13|RediscoverSection.kt:650|"First played 2025.11 · 23 plays"|M：Plain(2025.11) + Stat(23, plays)；去掉 "First played"（日期本身够了）|

### Library
| # | 位置 | 现在 | 改法 |
|---|---|---|---|
|14|ui/library/LibraryScreen.kt:1790|"gpo · 24 tracks · 1h 32m"|M：Plain(owner) + Stat(24, tracks) + Stat(1h 32m)|
|15|ui/component/SongListItem.kt:148|"Artist  ·  Album"|M：Plain(artist) + Plain(album, 60%)；Library 歌曲、收藏、搜索三处共用|

### 专辑详情（第 1 页 + 评分 sheet）
| # | 位置 | 现在 | 改法 |
|---|---|---|---|
|16|ui/detail/AlbumDetailScreen.kt:606|"Artist  ·  Album 2024"|M：Plain(artist) + Kind(Album) + Plain(2024)，Kind 用 accent 色|
|17|ui/detail/AlbumDetailComponents.kt:388（4 处调用）|"12 tracks  ·  50m"|M：Stat(12, tracks) + Stat(50, m)|
|18|AlbumDetailComponents.kt:413/462 FlowingTitleSeparator|大号标题流 "Title •  Title"|**定**：design.md 没有记录过这个 •，是实现时自带的。推荐改成相邻标题交替明暗（单数 `onSurface`、双数 `onSurfaceVariant`），靠颜色断开、不加符号；FlowingTitleTest 要跟着改|
|19|AlbumDetailComponents.kt:1014|"Couldn't sync · Retry"|按钮：状态文字 + 独立的 Retry 文字按钮|

### 专辑剪贴簿（第 2 页）
| # | 位置 | 现在 | 改法 |
|---|---|---|---|
|20|ui/detail/AlbumScrapbookPage.kt:1244|"1:10 · 3 plays"|**定**（design.md:357 拍过）。推荐 M：Stat(1:10) + Stat(3, plays)|
|21|AlbumScrapbookPage.kt:1412|"Read all · +3"|按钮文字 "Read all"，+3 做成旁边的小计数徽章|
|22|ui/detail/AlbumScrapbook.kt:349|"ALBUM — ARTIST"（小票）|留|

### 艺人详情
| # | 位置 | 现在 | 改法 |
|---|---|---|---|
|23|ui/detail/ArtistDetailScreen.kt:395|"Artist  ·  2016 – 2025"|删 "Artist"（页面本身就是艺人页），只留年份区间|
|24|ArtistDetailScreen.kt:876|"Artist · 12 releases · 2016 – 2025"|M：Stat(12, releases) + Plain(2016 – 2025)，去掉 "Artist"|
|25|ArtistDetailScreen.kt:1001|"Album  ·  3:45"|M：Plain(album) + Plain(3:45, tabular, 60%)|
|26–27|ArtistDetailScreen.kt:1220 / 1258|"Latest  ·  Album  ·  11 songs"|M：Kind(Latest, accent) + Kind(Album) + Stat(11, songs)；或者 Latest 做成小 chip|

### 歌单详情
| # | 位置 | 现在 | 改法 |
|---|---|---|---|
|28|ui/detail/PlaylistDetailScreen.kt:404|"(owner)  ·  Playlist"|M：Plain(owner) + Kind(Playlist)；去掉括号|
|29|PlaylistDetailScreen.kt:1006|"Artist  ·  Album"|同 #15|
|30|PlaylistDetailScreen.kt:1154|标题流 •|同 #18|

### Memories（全部是 PLAN.md / design.md 拍过的，统一列为**定**）
| # | 位置 | 现在 | 推荐 |
|---|---|---|---|
|31|ui/memories/MemoriesDeckCoordinator.kt:446|"Artist · 2019"|M：Plain(artist) + Plain(2019, 60%)|
|32–34|ui/memories/copy/MemoryExcerpt.kt:68/82/90/92|"Your review · Jul 26 · in Diary"|署名两级：「你的乐评」小字 label，日期 tabular 60%；"in Diary" 删掉|
|35|ui/memories/showcase/MemoryCardFace.kt:254|"Diary · 4 notes"|按钮 "Diary" + 计数徽章 4|
|36|ui/memories/showcase/MemoryDiaryWriter.kt:310|"Jul 26 · Your review"|已经是两个 Text：去掉 " · "，靠间距和字重区分|
|37|ui/memories/showcase/MemoryDiary.kt:1009|"Couldn't sync to NeoDB · Retry"|按钮，同 #19|

### Now Playing / 歌词 / 笔记
| # | 位置 | 现在 | 改法 |
|---|---|---|---|
|38|ui/nowplaying/NoteFullscreenPane.kt:369|菜单项 "对齐到现在 · 1:34"|菜单项用 trailing 文字放时间：label「对齐到现在」，trailing「1:34」(tabular, 60%)。YoinDropdownMenuItem 如果没有 trailing 槽就加一个|
|39|ui/nowplaying/LyricsSelection.kt:196|"3 lines selected · max"|"3 / 3"（到上限时数字变 accent），去掉 "max"|
|40|LyricsSelection.kt:184|"— Title · Artist"（剪贴板 / 分享图）|留（纯文本）；分享**图**上的署名改成两行（LyricsShareCard.kt:438）|

### 设置 / 服务 / 云同步
| # | 位置 | 现在 | 改法 |
|---|---|---|---|
|41|ui/settings/AccountIdentity.kt:139|"Subsonic · music.example.com"|M：Kind(Subsonic) + Plain(host)；服务名本来就有颜色和图标，也可以只留 host|
|42|ui/settings/SettingsScreen.kt:947|"Needs sign-in · In use"|只留问题；"In use" 用已有的选中态表达|
|43|SettingsScreen.kt:1192|"Gemini · 中文"|summary 只写语言|
|44|ui/settings/service/ServiceSetupViewModel.kt:243/254|账号名 "Spotify · userId"|账号名只用 display name / userId，服务靠头像区分|
|45|ui/settings/applemusic/AppleMusicValidationViewModel.kt:114|"Connected · JP"|两行，或者只留 storefront|
|46|ui/settings/sync/CloudSyncTexts.kt:76–85（6 个模板）|"On · Synced 2 min ago" 等|**定**（design.md:380 拍过）。推荐两行：第一行状态词（On / Paused / Needs review），第二行细节|
|47|ui/settings/sync/CloudSyncScreen.kt:666|"account · Choose what happens to them"|两行，第二行删掉说明|
|48|CloudSyncScreen.kt:938|"Subsonic · host · Paused — signed in as …"|M：服务 + host；状态另起一行|
|49|CloudSyncScreen.kt:1040|"Subsonic · host · from Pixel 9"|M：服务 + host + Plain(Pixel 9, 60%)|

### 其他
| # | 位置 | 改法 |
|---|---|---|
|50|ui/detail/AlbumDetailActivity.kt:220、ui/navigation/pane/DetailPaneEntries.kt:152（分享文字）|留|
|51|data/sync/engine/SyncEngine.kt:1101、data/sync/adapters/AlbumReviewSyncAdapter.kt:148（写进乐评的冲突标记）|留|

- **死代码**：data/source/ServiceFeatures.kt:42/94/150 的 `summary`、ui/memories/copy/MemoryDates.kt:60 的 `entryHeader`/`reviewHeader`/`todayHeader` 都没人调用，直接删掉。
- **当连接符用的破折号**也一并处理：CloudSyncScreen.kt:911/913/915、CloudSyncTexts.kt:97/127、ServiceSetupScreen.kt:495/575、ServiceSetupViewModel.kt:298/299、ServiceIntro.kt:33、HomeSection.kt:44、AboutFullscreenPane.kt:79。

---

## 三、AI 腔文案清单（约 55 条）

改法：**删** / **标签**（改成短标签）/ **按钮**（改成真按钮）/ **定**（已拍板，需要 owner 再看）。

### Home
| 位置 | 现在 | 改法 |
|---|---|---|
|ui/home/HomeEditorialContent.kt:1017–1018|"No recent activity yet" + "Once you listen … start filling in."|标签 "No activity yet"，删第二行|
|ui/home/RediscoverSection.kt:93|"Albums and songs you rated or wrote about come back here when it's been a while"|标签 "Nothing to rediscover yet"|
|ui/home/edit/HomeEditHeader.kt:184|"Drag to reorder"（编辑态常驻）|删。卡片本身在晃，已经说明可以拖|
|ui/home/edit/HomeEditTray.kt:316–317|"Your Home is empty" + "Press and hold anywhere, or tap Edit Home…"|标签 "Home is empty"，加一个 "Edit Home" 按钮|
|HomeEditTray.kt:312|"Hidden sections appear here"|标签 "No hidden sections"|
|ui/home/edit/HomeEditBlock.kt:466–470|"Nothing to jump back into yet" 等|标签，去掉 "yet"|
|ui/home/HomeSection.kt:44|"Albums, songs, and playlists to pick back up — memories woven in"|标签 "Albums, songs and playlists"|
|ui/home/HomeMemoryPill.kt:243|TalkBack "Memories, nothing kept yet"|"Memories, empty"|

### Memories
| 位置 | 现在 | 改法 |
|---|---|---|
|ui/memories/MemoriesScreen.kt:417/423|"No memories yet" + "Listen a little more and this page will start surfacing older plays."|标签 "No memories yet"，删第二行（以后换涂鸦）|
|MemoriesScreen.kt:453|"Tap to try again"|按钮 "Retry"|
|ui/memories/copy/MemoryVoice.kt:330/343/374/376/394/347–385|日记里的提问和第二人称旁白|**定**（design.md:244/256/258 已拍板，是作者声音）。这次不动|
|ui/memories/showcase/MemoryDiaryWriter.kt:218|"Write a few lines…"|"Write"（或留空）|

### 专辑详情 / 剪贴簿
| 位置 | 现在 | 改法 |
|---|---|---|
|ui/detail/AlbumScrapbookPage.kt:1517|"Scores and notes you leave show up here."|删整行，第 2 页空着时显示涂鸦（涂鸦另做）；先只留一个标签 "Nothing yet"|
|ui/detail/AlbumDetailComponents.kt:919|"Say something about it…"|"Review"|
|AlbumDetailComponents.kt:1011|"Syncs when you close this"|删|
|ui/detail/AlbumDetailScreen.kt:1428|"No notes yet"|保留（已经是标签）|

### Library
| 位置 | 现在 | 改法 |
|---|---|---|
|ui/library/LibraryScreen.kt:1409|"No playlists yet. Tap + to create one."|标签 "No playlists"|
|ui/library/LibraryViewModel.kt:782|"Waiting for Apple Music to confirm. Tap again to check."|snackbar "Waiting for Apple Music" + action "Check"|

### Now Playing
| 位置 | 现在 | 改法 |
|---|---|---|
|ui/nowplaying/compact/NoteCompactPane.kt:58|"Tap to write a note"（常驻）|标签 "Write"，整个区域可点|
|ui/nowplaying/NowPlayingScreen.kt:4224|"Tap to expand"|删|
|ui/component/NowPlayingPill.kt:235|"Tap to open player"|删（或者 TalkBack 才读的 onClickLabel "Open player"）|
|ui/nowplaying/AboutFullscreenPane.kt:79|"Tap About to start — we'll fetch song details on first open."|删；第一次打开就直接加载|
|ui/component/SongInfoDisplay.kt:65|"Tap to load song info"|按钮 "Load song info"|
|AboutFullscreenPane.kt:96、SongInfoDisplay.kt:95|"Configure your Gemini API key in Settings to see AI-generated song info."|"No Gemini API key" + 按钮 "Settings"|
|ui/nowplaying/AskGeminiBar.kt:230|占位 "What is this song aiming for?"|"Ask Gemini"|
|AskGeminiBar.kt:240|"Enter to ask Gemini"（常驻）|删|
|ui/nowplaying/LyricsSelection.kt:194|"Tap lines to select"|"Select lines"（只在进入选择态那一刻出现，可以保留位置）|
|ui/nowplaying/NowPlayingViewModel.kt:924|"Apple Music accepted the addition. Tap check to confirm when it appears."|snackbar "Added to Apple Music" + action "Check"|
|NowPlayingViewModel.kt:1538/1558|"Switch back from the Cast pill" / "Use the Cast pill to choose a device"|状态标签 "Casting" / "Not casting"|

### 设置 / 服务 / 云同步
| 位置 | 现在 | 改法 |
|---|---|---|
|ui/settings/SettingsScreen.kt:778–780|"Bring your music" + "Connect a server or a streaming account to start listening."|标签 "No accounts" + 按钮 "Add account"|
|ui/settings/SettingsPlaceholderActivity.kt:69|"Pick an account to set it up here"|"No account selected"|
|ui/settings/service/ServiceIntro.kt:33–103|"Send music to a speaker; recent plays are cached for you." 等|改成干的能力名词列表（"Cast"、"Offline cache"、"Lyrics, ratings, notes"），去掉宣传腔|
|ServiceSetupScreen.kt:628|"You'll sign in through Spotify and come right back."|删|
|ServiceSetupScreen.kt:520/632|"You've reached the account limit. Remove one in Settings to add another."|"Account limit reached"|
|AppleMusicValidationViewModel.kt:53/81/88–89/141|"Connected. Your account is available in Profiles." 等|"Connected" / "Couldn't connect" + Retry 按钮|
|ui/settings/sync/CloudSyncScreen.kt:418|"Keep your notes, ratings and translations on all your devices."|**定**（design.md:380）。推荐 "Sync notes, ratings and translations"|
|CloudSyncScreen.kt:452–455|"Notes & ratings follow your accounts", "Works offline — syncs when it can"|**定**。推荐改成名词短语|
|CloudSyncScreen.kt:546|"Google will ask to let Yoin store its own data in your Drive."（常驻）|删|
|CloudSyncScreen.kt:610|"Long-press to copy"（常驻）|删；给 SHA-1 行一个复制图标按钮|
|CloudSyncScreen.kt:650–651/679–680|"Google needs you to sign in again before Yoin can sync. Your changes wait on this device."|"Signed out of Google" + 按钮 "Sign in"|
|CloudSyncTexts.kt:105/114/118/127|"Changes wait on this device and sync when they can." 等|删安慰句，只留状态|
|data/sync/CloudSyncManager.kt:723|"Synced X and Y from your other devices"|"Synced from other devices"，计数用 Stat|

---

## 四、要 owner 拍板的（Cursor 先跳过这些）

1. 专辑、歌单大标题流里的「•」（#18 #30）：推荐改成相邻标题交替明暗。
2. design.md 已经拍过的分隔点：剪贴簿票根（#20）、Memories 一组（#31–37）、云同步状态行（#46 及上面标「定」的文案）。推荐和其他地方一起改，保持一致。
3. Memories 日记的提问语气（MemoryVoice）：推荐这次不动。
4. 空状态涂鸦：先做 Now Playing 笔记页，原型另给。
