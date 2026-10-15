# Spotoolfy → Yoin 迁移方案（最佳实践）

状态：决策全部已定（owner 2026-10-14 三轮反馈；2026-10-15 回顾页精简为两页）
回顾页交互原型：<https://claude.ai/artifact/PohjTmhwmz3MTkehDUu5it>
基线：Yoin `main@e4bdd0d`（0.5.0 / Room v30）；Spotoolfy = `p2o51/spotoolfy_flutter@0a57d65`（1.3.7+18）

> 「Spotoolfy」在本文指 Flutter 版 `spotoolfy_flutter`：Play 封闭测试 + TestFlight + Web。
> `p2o51/Spotoolfy` 是 2024 年的 React 原型，数据在 Firestore `users/{uid}/thoughts`，只在 §10 收尾时处理。

---

## 0. 已定决策（owner 2026-10-14）

| # | 决策 | 落在本文 |
|---|---|---|
| D1 | **导入只对新用户开放**：只在首次引导里出现，目标账号必须还没有任何用户数据 | §4.1、§5 |
| D2 | **导入后展示一个好看的可视化回顾页**：多少条、写了什么、文字爆炸 | §6 |
| D3 | **引导页新增「Using Spotoolfy?」一问**，回答「在用」后打开 Spotoolfy 悬浮窗：一步一步复制 Client ID → 导出 → 分享给 Yoin；之后再进入 Spotify Client ID 填写 | §5 |
| D4 | **Gemini API key 不导入** | §4.2 |
| D5 | **评分映射 👎/😐/🔥 → 1/5/10**（含 😐） | §4.4 |
| D6 | **Spotify 授权**：在原有 Spotify App 里加上 Yoin 一项即可，不做单独提醒或单独引导分支 | §7 |
| D7 | **导入的数据要进回忆和 Rediscover**：采用「曲目 → 专辑」事实表方案 | §4.6 |
| D10 | **Spotoolfy 悬浮窗用 Spotoolfy 的青绿色**，和 Yoin 自己的紫色 Spotify 悬浮窗区分开 | §5.3 |
| D11 | **Rediscover 按比例混排**：一部分来自聆听记录，一部分来自笔记和评分；**不用「上次写下于」**这种让人一头雾水的说法 | §4.6 |
| D12 | **Spotoolfy 歌词服务与 Yoin 同步**（今晚出 1.3.8+19） | §9 阶段 5 |
| D13 | **不做日语和繁体中文界面**：简体中文都还没做完，先把它做好 | §8 |
| D14 | **Rediscover 笔记卡约占 1/3**，有笔记卡时至少放 1 张 | §4.6 |
| D15 | **回顾页可以随时再打开**：导入后在 Yoin 里保留入口 | §6.1 |
| D16 | **回顾页只做两页**（2026-10-15）：开场（封面墙 + 笔记数），和关键词爆炸 + 歌词片段；第二页底部引导加入 Yoin。打分、时间、Top 榜、第一条笔记都不做 | §6.2 |
| D8 | **翻译不迁移** | §4.2 |
| D9 | **Spotoolfy 维护期一年，从 2026-10-14 起算，到 2027-10-14 为止** | §9 阶段 7 |

---

## 1. 一页结论

1. **两个 App 是不同的包，不存在原地升级。**
   - Spotoolfy 是 `com.gojyuplusone.spotoolfy.spotoolfy_flutter`，Yoin 是 `com.gpo.yoin`。
   - 数据只能由用户主动交接一个 JSON 文件。
   - Spotoolfy **现有的导出已经通过系统分享菜单发出 `application/json`**（`local_database_provider.dart:524-561`，`Share.shareXFiles`）。所以只要 Yoin 注册成分享目标、并能读 v1 格式，**Spotoolfy 一行代码都不用改**就能完成迁移。
2. **只迁移用户亲手写下的东西**：笔记和评分。
   - 翻译、歌词、AI 缓存、播放上下文、token、Gemini key 一律不迁。
   - 绝不伪造行为数据：不造播放记录，不生成专辑评分。
3. **新用户限定让导入变简单。** 目标账号是空的，不存在和已有数据冲突的问题，也不需要复杂的撤销。
   - 引导中途可以「换一个文件」：删掉刚导入的那一批，再导一次。
   - 笔记 ID 仍然用确定性 UUID，防止与云同步拉回的数据重复。
4. **引导顺序：先导入，再连 Spotify。** 从 Spotoolfy 回来时数据先暂存、看回顾，Client ID 也一起带回来；Spotify 连上后才写进账号（§5.1）。
5. **导入的价值要被「看见」**，靠三件事：
   - 导入后立即展示回顾页（§6）；
   - 用「曲目 → 专辑」事实表让数据进入回忆和 Rediscover（§4.6）；
   - 现有的 Now Playing 笔记页和专辑剪贴簿本来就能显示这些数据。
6. **Yoin 的前置清理只剩数据库卫生**：Room schema 导出、备份规则文件名、`activity_events` 触发器。
   - Gemini key 不再随迁移搬家，它的加密存储降为「建议，不阻塞」。
7. **Spotoolfy 从 2026-10-14 起进入一年维护期，到 2027-10-14 为止。** 期满后下架，但 Yoin 依赖的网易云代理 `api.spotoolfy.gojyuplus.com` **必须长期保留**。

---

## 2. 范围

**目标**

- 新装 Yoin 的 Spotoolfy Android 用户，在首次引导里一步把笔记和评分带过来。
- 导入后看到一份回顾，随后在 Now Playing、专辑剪贴簿、回忆和 Rediscover 里遇到这些数据。

**非目标（YAGNI）**

- 已经在用 Yoin 的老用户合并导入（D1）。
- 翻译、AI 缓存、播放上下文迁移（D8）。
- Spotoolfy 的 v2 导出格式。v1 已经够用；迁移本身不需要改 Spotoolfy，维护版只加一条公告（§9 阶段 5）。
- 跨 Profile 或跨 provider 的自动合并（`design.md:253`）。
- iOS 和 Web 用户：Yoin 只有 Android 版。他们在维护期内可以导出留存。

---

## 3. 两边现状对照

| 维度 | Spotoolfy | Yoin | 迁移含义 |
|---|---|---|---|
| 平台 | Android / iOS / Web | 仅 Android（minSdk 26） | 只迁 Android 用户 |
| 包名 | `com.gojyuplusone.spotoolfy.spotoolfy_flutter` | `com.gpo.yoin` | 文件交接 |
| 导出 | 有：JSON，无版本号，走系统分享菜单 | 没有手动导入导出（`cloud-sync.md:107` 列为待办） | Yoin 只补导入 |
| Spotify 授权 | `spotify_sdk` 只有 access token；redirect `spotoolfy://callback` | PKCE；redirect `yoin://auth/spotify/callback` | 在同一个 Spotify App 里加上 Yoin（§7） |
| 播放 | Web API / Connect，遥控任意设备 | App Remote（要求本机装有 Spotify），Web API 起播是备选路径 | 行为差异写进说明 |
| 本地库 | sqflite v1：`tracks`、`records`、`translations`、`play_contexts` | Room v30 | 映射见 §4 |
| 评分 | 0 👎 / 3 😐 / 5 🔥，每条记录带一个评分 | 0–10 浮点，每曲一行 | 1 / 5 / 10（D5） |
| 笔记 | 文本 + 歌词片段 + 播放位置 + 上下文 | 文本 + `positionMs` + 标题/艺人 | 片段并入正文，上下文丢弃 |
| 引导 | 无 | 首次引导 `ui/landing/`：你好 → 选服务 → … → 完成 | 新增一步（§5） |

---

## 4. 数据迁移设计

### 4.1 「新用户」的判定

- **硬条件**（导入器强制检查，界面只是不显示入口）：目标 Spotify Profile 里没有任何用户写下的数据，即：
  - `song_notes` 为空；
  - `local_ratings` 为空；
  - `album_ratings` 为空（含书评）；
  - 没有已完成的导入批次（`import_batches`）。
- **入口**：只在引导里出现。
  - **首次引导**（`LandingMode.FirstRun`）：「选服务」里选了 Spotify 时出现，排在 Spotify 连接页之前。资格在**写入时**按刚连上的账号再检查一次。
  - **重新引导**（`LandingMode.Rerun`，设置 › 关于 › 重新引导）：只在满足硬条件时出现。这样刚走完引导、还没来得及导出的用户有一次补救机会，同时没有放开合并导入。
- **分享入口**：只在引导进行中有效。引导之外收到 Spotoolfy 文件时，一行提示「Spotoolfy 数据只能在引导中导入」，文件不收（§5.4）。

### 4.2 数据盘点与处置

| Spotoolfy 存储 | 处置 | 理由 |
|---|---|---|
| `records`（有正文） | ✅ → `song_notes` | 用户手写，不可再生 |
| `records.rating` | ✅ 每曲取最新一条 → `local_ratings` | Yoin 每曲只存当前值 |
| `records`（只有评分，没有正文） | ✅ 只贡献评分 | 不生成空笔记 |
| `tracks` | ✅ 作为标题、艺人、回顾页素材和专辑关联线索 | 冗余元数据 |
| `translations` | ❌（D8） | 缓存；key 对不上 `(sourceHash, model)` |
| `play_contexts` | ❌ | 只有最后一次时间，导入会污染 Recents 和回忆 |
| 歌词、翻译、AI 缓存（prefs） | ❌（本来也不在导出文件里） | 可再生 |
| Spotify Client ID | ❌（不在 v1 导出里） | 用户照用原来的 ID（§7） |
| Gemini API key | ❌（D4，本来也不在导出里） | — |
| token、UI 偏好 | ❌ | 不同的 App |

### 4.3 记录映射（`records` + `tracks` → `song_notes`）

| `song_notes` 字段 | 来源与规则 |
|---|---|
| `id` | `UUID.nameUUIDFromBytes("spotoolfy:v1|$trackId|$recordedAt|${sha256(noteContent)}")`。不直接用 `records.id`：那是每台设备的自增 ID，不同设备会撞号；确定性 ID 还能防止与云同步拉回的同一条笔记重复 |
| `profileId` | 本次引导连上的 Spotify Profile |
| `provider` | `MediaId.PROVIDER_SPOTIFY` |
| `trackId` | 去掉 `spotify:track:` 前缀，再校验 `^[0-9A-Za-z]{22}$`。不合格的跳过，内置样例 `spotify:track:sample1/2` 也在这一步被滤掉（`database_helper.dart:634-738`） |
| `content` | 见 §4.5 |
| `createdAt` / `updatedAt` | `recordedAt`（毫秒），保留原始时间 |
| `title` / `artist` | `tracks.trackName` / `tracks.artistName`。找不到对应 track 时，标题占位为「未知曲目」，等补全任务修正 |
| `positionMs` | `songTimestampMs`；为 null 或 ≤ 0 时写 null。有值的笔记会自动挂到歌词时间线上 |
| — | `contextUri` / `contextName` 丢弃。Spotoolfy 在歌单里播放时也写入专辑名（`add_note.dart:137-140`），信息本身不可靠 |

### 4.4 评分映射（D5）

| Spotoolfy | Yoin `local_ratings.rating` |
|---|---|
| 5 🔥 | 10.0 |
| 3 😐 | 5.0 |
| 0 👎 | 1.0 |
| NULL / 其它值 | 不写 |

- **为什么是这组数：** 和 Spotoolfy 自己算专辑分的 `_mapRatingToScore` 一致（`album_page.dart:1140-1150`）。👎 不映射成 0，因为 Yoin 把 `rating > 0` 当作「评过分」（`LocalRatingDao.getRatedAlbumAggregates`）。
- **取哪一条：** 每曲取 `MAX(recordedAt)` 那条，与 Spotoolfy 的「最新评分」语义一致。
- **写入方式：** `updatedAt = recordedAt`；`needsSync = false`，因为 Spotify 不支持服务端评分；`serverRating = round(r/2)`。

### 4.5 笔记正文组装

Yoin 笔记是纯文本。`lyricsSnapshot` 可能是用户选中的歌词，也可能是自动截取的 3 行，两者无法区分，所以保留并附在正文后面：

```text
<noteContent，trim 后>

「<歌词片段，每行一行>」
```

- 用正则 `^\[[^\]]*\]` 剥掉 LRC 时间标签；`[ti:…]` 这类纯元数据行整行丢弃。没有时间戳的分支会把整份 LRC 的前 3 行原样存下来，所以必须做这一步（`local_database_provider.dart:281-343`）。
- 只有评分、没有正文的记录，歌词片段丢弃。

### 4.6 让导入数据进入回忆和 Rediscover（D7）

**现状有两道门。**

1. **找不到专辑。** 笔记和评分不带专辑，专辑归属**只能**从 `play_history` JOIN 出来（`SongNoteDao.getNotedAlbumAggregates`、`LocalRatingDao.getRatedAlbumAggregates`）。没在 Yoin 里播过的曲目不属于任何专辑。
2. **Rediscover 没有「离开时钟」。** 每张卡都要求 `lastPlayedFromHistoryAt`，没有就直接剔除（`RediscoverSelection.kt:100`）；单曲卡同理（`:130`）。

**方案第 1 部分：新增事实表 `track_album_links`**（Room 30→31）

```kotlin
@Entity(tableName = "track_album_links", primaryKeys = ["profileId", "provider", "trackId"])
data class TrackAlbumLink(
    val profileId: String,
    val provider: String,
    val trackId: String,
    val albumId: String,
    val source: String,      // "play" | "import" | "detail"
    val updatedAt: Long,
)
```

- 它记录的是「这首曲属于哪张专辑」这一客观事实。按 Profile 隔离：两个 Subsonic 服务器共用 provider token，但 ID 空间各自独立。
- 两个聚合查询的 `play_history` 子查询改成「`play_history` ∪ `track_album_links`」的去重并集。
- `recordPlay` 和专辑详情加载时也顺手写一份，迁移之外同样受益：在专辑页打过分、但从没播过的曲目也能被找到。
- **由后台补全任务填充：**
  - 导入后排队，经 `SpotifyRateLimitGate` 限流，可续跑。
  - **只用单曲接口 `GET /v1/tracks/{id}`**：批量 `GET /v1/tracks?ids=` 已从新建的 Development Mode App 移除，存量 App 的移除只是延期（§7）。
  - 补全时一并修正「未知曲目」的标题，并记下 ISRC（`external_ids` 已于 2026-03-13 恢复），留作将来跨 provider 的线索，本期不用。

**方案第 2 部分：Rediscover 按比例混排两种来源（D11）**

现在每张卡都要求 `lastPlayedFromHistoryAt`，没有就直接剔除（`RediscoverSelection.kt:100`、`:130`），从 Spotoolfy 带来、还没在 Yoin 里听过的专辑永远上不了架。改成两类卡按比例混排：

| 类别 | 来源 | 进架条件 | 卡片第二行 |
|---|---|---|---|
| **聆听** | 现有规则 | 在 Yoin 里听过、留过评分或笔记、已经 90 天没听 | 保持现有文案 |
| **笔记** | 笔记和评分（专辑归属来自第 1 部分的 `track_album_links`） | 写过笔记或打过分，最近 90 天没在 Yoin 里听过（包括从没听过） | **不写日期**，直接给内容：笔记摘录（「前奏一响就想起高中的夏天」）或 `ScoreEmblem` 分数 |

- **比例**：约 2/3 聆听、1/3 笔记（`REDISCOVER_NOTE_SHARE = 1/3`；Compact 6 张时 4 + 2，Medium / Wide 张数少时按同一比例取整，至少 1 张笔记卡）。
- **互相补位**：一类不够时由另一类补满。刚从 Spotoolfy 迁来、在 Yoin 里还没有播放记录的人，架子上先全是笔记卡；听得越多，聆听卡占得越多，最后稳定在比例上。
- **排列**：两类交错（聆听、聆听、笔记……），每天按日期固定一次顺序，不在一天里来回跳。
- **笔记卡内部排序**：有分的在前、分高的在前，同分时笔记多的在前。最近 90 天在 Yoin 里写过或改过的不出现，刚写的不算「重新发现」。
- 为什么不用「上次写下于」：用户不知道「写下」指什么、什么时候写的；直接展示写过的那句话或打过的分，一眼就明白这张卡为什么回来了。
- 实现后同步改 `design.md` 的 Rediscover 一节。

**被否的备选**

- 伪造 `play_history`：污染 Most Played 和「90 天未播放」。
- 给 `song_notes` 加 `albumId` 列：同步载荷要升版本，每条笔记都要冗余一份。

### 4.7 批次与「换一个文件」

```text
import_batches(batchId PK, source="spotoolfy", status, profileId?, fileSha256, importedAt,
               noteCount, ratingCount, summaryJson)   -- status: staged | committed
import_items(batchId, targetKind, targetKey)   -- PK (targetKind, targetKey)
```

- `summaryJson` 存回顾页数据（§6.3）。回顾页是独立的 Activity，只凭 `batchId` 就能从 Room 读到完整数据。
- **暂存 → 写入**：文件解析后批次是 `staged`（还没有 profileId，`import_items` 为空，原文件留在 `noBackupFilesDir/spotoolfy-import/`）；Spotify 连上、资格检查通过后，在一个事务里写入笔记和评分、填上 profileId、改成 `committed`。引导结束时仍是 `staged` 的批次连同文件一起删除。
- **换一个文件**：仅在同一次引导内可用。按 `batchId` 删除 `import_items` 指向的行，再导入新文件。由于是新用户，这一批就是该 Profile 的全部数据，不会误伤。
- **同一个文件再导一次**（`fileSha256` 相同）：直接提示「已导入」，不重复写。
- 两张表都是本地运维数据，不进云同步。

### 4.8 与云同步、NeoDB

- 同步适配器按扫描读表（`SongNoteSyncAdapter.readAll`），导入的行会自动同步到其他设备。
- 同一用户换新设备，又在新设备的引导里导入同一个文件：笔记 ID 是确定性的，不会重复；评分按 LWW 合并（`updatedAt` 用原始时间，所以 Yoin 里后来改过的评分胜出）。
- 不生成专辑评分，所以不触发 NeoDB 推送。

### 4.9 输入健壮性

- **格式识别**：顶层同时有 `tracks`、`records`、`translations`、`play_contexts` 四个 key 即为 v1。其余字段一律忽略。
- **类型宽容**：与 Spotoolfy 自己的导入器（`local_database_provider.dart:563-676`）同等宽容。
  - `rating` 可能是字符串（按 3 处理）或浮点。
  - `lastPlayedAt` 可能是 int、string 或 double。
- **规模**：流式解析（`decodeFromStream`）；单事务、每批 500 条写入。5 万条记录不能 OOM。
- **体积上限**：32 MB。
- **编码**：UTF-8，遇到 BOM 去掉。
- **隐私**：日志不打印正文；报错只给字段名和序号；临时文件导入完成或引导结束即删除。

---

## 5. 引导页：「Using Spotoolfy?」与 Spotoolfy 悬浮窗（D3、D10）

### 5.1 位置与顺序

```text
你好 → 选服务 → Yoin 负责/不负责 → 【在用 Spotoolfy 吗？】 → Spotify 连接页 → 其它服务连接页 → 主页内容 → 顶部边缘 → 完成
                                         │
                                         └─ Spotoolfy 悬浮窗：复制 Client ID → 导出 → 分享给 Yoin → 回顾页
```

- 新增 `LandingStep.Spotoolfy`，**排在 Spotify 连接页之前**，只在「选服务」里选了 Spotify 时进入 `landingSteps(...)`（重新引导时另需满足 §4.1）。先导入、后连接：用户从 Spotoolfy 回来时已经带着 Client ID，连接页直接填好。
- **暂存，连上再写**（§4.7）：解析完成即生成 `staged` 批次并打开回顾页；Spotify 连接成功后写入这个账号，连接页的成功状态多一行「已把 N 条笔记放进〈账号名〉」，角色举手蹦一下。
- **没连上就想结束**：在「完成」前提示「不连接 Spotify，Spotoolfy 的数据不会保存」，按钮「回去连接」「不要这些数据」。选后者就删除暂存批次和文件。
- 返回分类沿用 **Pattern E（分步流程）**，不新写返回逻辑：返回 = 上一步。暂存的数据不因返回而丢弃，回到这一步时显示已收到。
- 角色反应沿用 `design.md` 的既有语言：选中选项时点头；收到文件时举手蹦一下；文件不认识时摇头。
- 气泡：英文 `Using Spotoolfy?`，中文「在用 Spotoolfy 吗？」。

### 5.2 这一步的浮动窗（两行单选，形态同 Apple Music 连接页）

1. **「在用，带上我的数据」**：胶囊主按钮变成「打开 Spotoolfy」。点下后拉起 Spotoolfy，同时启动 Spotoolfy 悬浮窗（§5.3）。
   - 需要在 manifest 的 `<queries>` 里声明 Spotoolfy 包名 `com.gojyuplusone.spotoolfy.spotoolfy_flutter`。
   - 没装 Spotoolfy（比如在别的手机上导出过）：主按钮换成「选择导出文件」，打开系统文件选择器 `OpenDocument(arrayOf("application/json", "text/plain"))`（部分文件管理器会把 `.json` 标成 `text/plain`）。
2. **「没用过」**：主按钮「下一步」。

收到文件之后这一步的状态：

| 状态 | 表现 |
|---|---|
| 解析中 | 浮动窗显示进度；胶囊主按钮禁用 |
| 已收到 | 解析完自动打开回顾页（§6）。返回后浮动窗显示「✓ 已收到 N 条笔记，连上 Spotify 后放进你的 Yoin」，附「再看一次回顾」「换一个文件」，主按钮「下一步」（即 Spotify 连接页） |
| 失败 | 角色摇头，给出原因（不是 Spotoolfy 文件、文件损坏、为空），提供「换一个文件」 |

### 5.3 Spotoolfy 悬浮窗（`SpotoolfyGuide`，D10）

- **复用 `ConnectGuideActivity`**：同一个画中画小窗（浮在 Spotoolfy 上，不要「显示在其他应用上层」权限），同一套交互约定：一步只说一件事，固定三格「上一步 / 复制 / 下一步」，用不上的格子由系统灰显占位；收尾走 `GuideSession`（先把小窗展开再回 Yoin）。
- **用 Spotoolfy 的青绿色提升辨识度**：小窗的底色和强调色取 Spotoolfy 图标色板。
  - 浅色：底 `#DDFFFF`，强调 `#4FA6B0`，深字 `#0B3438`；深色：底 `#12464C`，强调 `#8FD3DB`，浅字 `#DDFFFF`。图标底色是 `#71ACB3`。
  - 左上角放 Spotoolfy 图标轮廓。
  - 一眼看出「现在是在 Spotoolfy 里操作」，和 Yoin 自己的紫色 Spotify 悬浮窗区分开。
  - 颜色作为一组 token 定义在悬浮窗里，不进 Yoin 的主题。
- **步骤**（按 Spotoolfy 1.3.x 的实际界面）：
  1. 「点右上角的设置」。
  2. 「点『Spotify API』，复制客户端 ID」：长按输入框全选、复制。框是空的说明用的是 Spotoolfy 内置的 ID，**跳过这步**，稍后在 Yoin 里新建自己的 App（§7）。
  3. 「滑到『数据管理』，点『导出数据』」。
  4. 「在分享菜单里选『导入到 Yoin』」。这一步中间格换成「回到 Yoin」，给存了文件、想在 Yoin 里自己选的人。
- **回到 Yoin**：
  - 分享接收 Activity（§5.4）收下文件、拉起 Yoin，小窗随之关闭。
  - 回到前台时读一次剪贴板：有 32 位十六进制 Client ID 就记下，到 Spotify 连接页时直接填好，和现有 Spotify 悬浮窗回来时的行为一致。
  - Yoin 收到文件 → 解析 → 回顾页 → 返回这一步（已收到）→ 下一步就是 Spotify 连接页。

### 5.4 分享入口（从 Spotoolfy 的分享菜单进来）

- 新增一个分享接收 Activity（暂名 `SpotoolfyShareReceiverActivity`）：
  - exported、透明主题、`excludeFromRecents`、`noHistory`；
  - intent-filter 为 `ACTION_SEND` + `application/json`；
  - 在分享菜单里显示为「导入到 Yoin」。
- **收到后立刻**把流复制到 `noBackupFilesDir/spotoolfy-import/pending.json`（超过 32 MB 拒绝），然后用内部 action 拉起 `MainActivity`，不把外部 URI 往下传。原因：
  - 分享来的 URI 读权限只属于接收它的 Activity，转交容易失效；
  - `MainActivity` 不必对外暴露新的 intent 入口。
- `MainActivity` 拿到待导入文件后：
  - **引导进行中**：交给 Spotoolfy 这一步解析、暂存、打开回顾。用户先分享、后第一次打开 Yoin 也适用，文件留到这一步再处理，顺序无关。
  - **引导之外**：分享接收 Activity 自己弹一行 Toast「Spotoolfy 数据只能在引导中导入」，不拷文件、不拉起 Yoin。「引导中」= 当前进程里有引导在显示（`LandingPresence`），或者这台机器还没走过引导（先分享、后第一次打开 Yoin）。
- 文件只会被暂存；写进账号永远发生在 Spotify 连接成功之后（§5.1），不存在静默写入。

### 5.5 实现落点

- 数据层：`data/migration/spotoolfy/`
  - `SpotoolfyExportParser`（流式、宽容）
  - `SpotoolfyImportMapper`（纯函数）
  - `SpotoolfyRecapCalculator`（纯函数，§6.3）
  - `SpotoolfyImporter`（暂存、写入、资格检查、换文件）
  - `TrackAlbumLinkEnricher`（后台补全）
- 界面层：
  - 引导步骤：`ui/landing/LandingSpotoolfyStep.kt`（`SpotoolfyStepViewModel` + 场景），步骤本身走 `LandingViewModel` 的步骤机制；
  - 悬浮窗：`GuideKind.Spotoolfy`（`ui/landing/guide/ConnectGuide.kt` 的步骤和色板），由 `ConnectGuideActivity` 承载；
  - 分享接收和回顾页：`ui/spotoolfy/`（`SpotoolfyShareReceiverActivity`、`SpotoolfyRecapActivity`）。
- 写入经 `YoinRepository` 新增的方法，UI 不直接调用 DAO。
- 实现完成后，把这一步和悬浮窗补进 `design.md` §首次引导：那里是 UI 的事实来源（AGENTS.md）。

---

## 6. 导入回顾页（D2、D16）

### 6.1 定位

- 新的全屏目的地 `SpotoolfyRecapActivity`，文件解析完成后由引导自动打开。此时数据还在暂存，连上 Spotify 后才写进账号（§5.1）。
- **只有两页**（D16）：开场，和「你写下的，和你截下的」。第二页底部引导用户加入 Yoin。
- **交互原型**：<https://claude.ai/artifact/PohjTmhwmz3MTkehDUu5it>（虚构示例数据；可切换「重度 / 轻度用户」看数据不足时的退化，切换「引导里 / 从设置打开」看底部的两种结尾）。
- **返回分类：Pattern A（原生跨 Activity 预测性返回）**：没有共享 chrome，注册零个返回回调。
  - 页内**不加** `BackHandler`。点词、点歌词弹出的底部卡片点空白处收起，而不是用返回键收起，否则会压掉原生预览（predictive-back 不变式 13）。
  - 系统返回落在引导的 Spotoolfy 步（「已收到」状态，§5.2）。
  - 第二页底部的「加入 Yoin」按钮 `setResult(RESULT_OK)` 后 `finish()`，引导收到结果直接进入 Spotify 连接页，Client ID 已经填好。
- 数据只凭 `batchId` 从 `import_batches.summaryJson` 读取，符合「Activity 边界即数据边界」。
- **随时再打开**（D15）：
  - 入口放在 设置 › 关于 › 「Spotoolfy 回顾」，只在当前账号有 `committed` 的导入批次时显示。
  - 同一个 Activity，同样是 Pattern A；从设置打开时返回落在设置。
  - 从设置打开时第二页底部不再引导加入，换成一句「它们都在你的 Yoin 里」和「完成」。
  - 每次打开都重播动画，数据用导入时存下的那份，不重新计算。

### 6.2 页面结构（两页，竖向分页，进入当前页时才播放动画）

| 页 | 内容 | 动效 |
|---|---|---|
| 1 **开场** | 「来自 Spotoolfy」小标签；大数字「N 条笔记」；一句话「写给 K 位艺人的 M 首歌，其中 R 首打了分」（全部打过分时说「每一首都打了分」，没打分时不提）；「2024.11.06 到 2026.07.16」；底部「向上滑」 | 最多 24 张专辑封面从中心**炸开**，落成封面墙；数字从 0 计数 |
| 2 **你写下的，和你截下的** | 笔记关键词（≤ 30 个）和常用 emoji（≤ 3 个）；最多 2 段歌词片段；底部的加入卡片 | 词块从中心按名次错开炸开，落到螺旋排布的位置，避开两张歌词卡；歌词卡随后飞到左上角和右下角；最后底部卡片升起。点一个词 → 底部卡片列出最多 3 条包含它的笔记和曲名；点一段歌词 → 截取时间和当时写的笔记 |

第二页底部的两种结尾：

| 打开方式 | 文案 | 按钮 |
|---|---|---|
| 引导里 | 「带着它们，加入 Yoin」；「连接 Spotify 之后，这 N 条笔记和 R 首歌的评分会放进你的 Yoin，出现在 Now Playing、专辑剪贴簿、回忆和 Rediscover 里。」 | 主按钮「加入 Yoin」→ Spotify 连接页 |
| 设置 › 关于 | 「它们都在你的 Yoin 里：Now Playing 的笔记页、专辑剪贴簿、回忆和 Rediscover。」 | tonal 按钮「完成」→ 回到设置 |

数据不足时第二页自动退化：

- 笔记少于 20 条，或关键词少于 8 个：关键词换成笔记卡片（最多 6 张，放不下的不放），标题改成「你写下的」。
- 没有歌词片段：只有词，标题改成「你最常写的词」。
- 没有封面（离线或全部加载失败）：第一页用色块拼成封面墙。

**不做**（D16）：打分分布、按月和按钟点的时间图、Top 曲目和艺人、第一条笔记、「接下来去哪里找」清单。「去哪里找」压成第二页底部的那一句话；导入的评分会在 Now Playing 和 Rediscover 里遇到。

### 6.3 素材、分词和 `summaryJson`

| 素材 | 放在 | 说明 |
|---|---|---|
| 专辑封面 | 第 1 页 | 图片爆炸，视觉冲击最强 |
| 笔记关键词 | 第 2 页 | 最个人化；需要分词、停用词和最低词频（≥ 2） |
| emoji | 第 2 页 | 单独计数，最多 3 个混进词里。🔥 在 Spotoolfy 用户的笔记里很常见，留着反而有味道 |
| 歌词片段 | 第 2 页 | `lyricsSnapshot` 去重；取最早和最近各一段，来自不同的歌；超过 40 个字的跳过，免得一张卡占掉半屏 |

**分词**：用 `android.icu.text.BreakIterator.getWordInstance(ULocale.ROOT)`（API 24+，基于词典，能处理中、日、英混排）。

- 过滤规则：
  - 中日文词长 ≥ 2，拉丁词长 ≥ 3；
  - 去掉纯数字和标点；
  - 停用词表（中、英、日各一份小表，在 `SpotoolfyStopwords`）。
- 按词频排序，取前 30 个；字号按名次分 4 档。
- 全部在本地计算，不调用 Gemini。新用户此时也还没有 key。

**`summaryJson` 结构**（`SpotoolfyRecapCalculator` 在导入时一次算好）：

```kotlin
@Serializable
data class SpotoolfyRecap(
    val noteCount: Int, val trackCount: Int, val artistCount: Int, val ratedTrackCount: Int,
    val firstRecordAt: Long, val lastRecordAt: Long,
    val coverUrls: List<String>,                      // ≤ 24，第 1 页
    val keywords: List<Keyword>,                      // ≤ 30：text、count、snippets（≤ 3 条 NoteSnippet）
    val emoji: List<EmojiCount>,                      // ≤ 3
    val lyricSnippets: List<LyricSnippet>,            // ≤ 2：lyric、trackTitle、artist、capturedAt、note
    val fallbackNotes: List<NoteSnippet>,             // ≤ 6，只在数据不足时有值
)

@Serializable
data class NoteSnippet(val text: String, val trackTitle: String, val artist: String, val at: Long)
```

- 笔记片段直接存进 `summaryJson`，不存 noteId：引导里打开回顾时笔记还在暂存，没写进笔记表。片段截到 120 个字以内。
- 退化与否由 Calculator 判断：`fallbackNotes` 非空就走笔记卡片。

### 6.4 动效与工程约束（对齐 AGENTS.md）

- **全部用弹簧**：位置和尺寸用 spatial spring，颜色和透明度用 effect spring。爆炸的错开节奏用 Yoin 现有 motion token，不新增魔法数。
- **触感**：只有两处，各一拍 `YoinHaptics`：第 1 页封面炸开、第 2 页词块炸开。词落位不逐个震，免得吵。
- **性能**：
  - 每个词块的位移和缩放只在 `graphicsLayer` 里读取，动画期间不重组；
  - 封面按 128px 解码；
  - 动画进行时投票高帧率；
  - 排布在后台线程计算，以 `batchId` 作随机种子，旋转屏幕后布局不变；螺旋放不下的词直接不放。
- **减少动态效果**：系统关闭动画时，不炸开，直接淡入最终布局。
- **自适应**：平板横屏时，每页左侧是可视化、右侧是文字和按钮（`docs/adaptive-principles.md`）。
- **颜色**：动态色，深浅模式都适用。词块按名次轮流使用 primary / secondary / tertiary 的 container 色，歌词卡用 tertiaryContainer。
- **无障碍**：每页提供一句完整的摘要给读屏；飞散的词块 `clearAndSetSemantics`，另外给读屏一个按词频排序的列表。
- **文案**：进 `values/` 和 `values-zh-rCN/`；数字和日期按 locale 格式化；通过 `scripts/check-hardcoded-strings.sh`。
  - 不用「·」或破折号把数字、曲名、艺人拼成一串，写成一句话：「写给 10 位艺人的 32 首歌」「2026.06.20 写给《第七个路口》」。
- **封面**：来自 Spotify CDN 的 `albumCoverUrl`。离线或加载失败时退化为色块拼贴，不留白洞。

---

## 7. Spotify 授权（D6）

- Spotoolfy 用户**在原来的 Spotify App 里加上 Yoin**：
  - Redirect URI `yoin://auth/spotify/callback`；
  - Android package `com.gpo.yoin` + SHA-1；
  - 原来的 Client ID 照用。
- Yoin 现有的连接引导和悬浮窗已经能复制这些值，包括本机安装包的签名 SHA-1（`ConnectGuide.kt:73-82`）。**不做单独的引导分支，也不加单独的提醒文案。**
- Client ID 从 Spotoolfy 悬浮窗带回来时（§5.3），连接页直接填好；Spotify 悬浮窗从「两个回调地址」那步开始，跳过 Create app 和起名，只做「加一个」。
- Spotoolfy 里客户端 ID 为空（用的是 Spotoolfy 内置的 ID）的用户，那个 App 不归他们，走现有的完整 9 步新建自己的 App。
- 背景：Spotify 2026 年起，Development Mode 的规则对新旧 App 一律生效（2026-03-09 起）：
  - 每个开发者只能有 1 个 Client ID；
  - 每个 App 最多 5 个授权用户；
  - App 所有者必须有 Premium。

  这正是「加一个」而不是「新建一个」的原因。批量元数据接口对新 App 已移除、对存量 App 的移除只是延期，所以补全任务只用单曲接口（§4.6）。
- token 不迁移：Yoin 里走一次 PKCE 授权即可。
- Premium 与本机 Spotify App：两边都要求 Premium；Yoin 走 App Remote，需要本机安装 Spotify。这一点写在维护版公告里（§9 阶段 5）。

---

## 8. 功能对等矩阵

| Spotoolfy 功能 | Yoin 现状 | 处理 |
|---|---|---|
| 笔记 + 评分录入、按曲查看 | ✅ | — |
| Roam：全部记录总览 | ❌ | 导入后由回顾页（§6）承担「确认都过来了」；完整的「我的笔记」入口另立项 |
| 歌词三源、手动搜索、选择、海报 | ✅ 已移植 | — |
| 带歌词写笔记 | ❌（`design.md:350` 明确未移植） | P1：导入的笔记里带着歌词片段，用户会期待能继续这样写 |
| 拿歌词问 Gemini / 复制成单行 | ❌ | P2 |
| 翻译三种风格 | 原生 + Gemini 单一风格 | 不迁（D8） |
| 歌曲信息 / 专辑洞察 | ✅ About / Ask；≈ 回忆的 AI 文案 | — |
| 音乐性格洞察 | ❌ | 不做 |
| Time Machine（那年今日） | ❌ | P2：Yoin 只同步 200 首 Liked Songs（`SpotifyApiClient.kt:826`） |
| 设备切换、播放模式、桌面小组件 | ✅ | QA 时验证 |
| 日语 / 繁体中文界面 | ❌ | 不做（D13）：先把简体中文做好 |
| iOS / Web | ❌ | 维护期内保留导出 |

---

## 9. 分阶段路线图

### 阶段 0：Yoin 前置清理

- [x] **启用 Room schema 导出**：KSP 参数 `room.schemaLocation` 指向 `app/schemas/`（2026-10-15）。
  - 这个环境拉不到 Google Maven，没能生成 schema JSON。下一次本地构建会写出 `31.json`，请一起提交；v30 基线没有补上，迁移测试沿用仓库现有的「手建旧库 → 迁移 → Room 打开时校验」方式。
- [x] 修正 `data_extraction_rules.xml`：备份的是 `yoin_profiles.xml`。
- [x] `activity_events` 上限触发器：全新安装由 `createCallback` 建，31 的迁移再补一次。
- [x] 更新 AGENTS.md 的过时描述。
- 建议，不阻塞迁移：Gemini key 改为加密存储并移出备份（目前明文存在 `GeminiConfig.kt:9`）。

### 阶段 1：契约与样本

- [x] 把 §13 的 v1 格式抽成 `docs/integrations/spotoolfy-export-format.md`。
- [x] fixtures：`app/src/test/resources/spotoolfy/export_v1.json`（样例行、字符串评分、字符串和浮点时间、带标签的歌词片段、坏行）；5 万条和超过 32 MB 在测试里现场生成。
- [ ] 还缺：owner 的真实导出（脱敏后放进 fixtures）、Web 版导出各一份。

### 阶段 2：导入核心

- [x] Room 30→31：`track_album_links`、`import_batches`、`import_items`，加 migration 测试。
- [x] 两个专辑聚合查询改为并集；Rediscover 按比例混排聆听卡和笔记卡（§4.6，笔记卡 = 从没在 Yoin 里播过的专辑）。
- [x] Parser、Mapper、RecapCalculator、Importer（含暂存 / 写入、资格检查、换文件）。
- [x] `recordPlay` 和专辑详情加载时写 `track_album_links`；后台补全 `TrackAlbumLinkEnricher`（单曲接口，逐首，出错即停、下次续跑，顺手补上「未知曲目」的曲名）。

### 阶段 3：引导步骤与分享入口

- [x] `LandingStep.Spotoolfy`（§5），排在 Spotify 连接页之前；暂存批次在 Spotify 连上后写入。
- [x] Spotoolfy 悬浮窗 `GuideKind.Spotoolfy`（青绿色，4 步），回到 Yoin 时读剪贴板里的 Client ID。
- [x] 分享接收 Activity、manifest 的 `<queries>`；补 `design.md` §首次引导。

### 阶段 4：回顾页

- [x] `SpotoolfyRecapActivity`（§6，两页），中英文案。
- [ ] 真机验收：减少动态效果、平板横屏、读屏、深色模式、离线封面。
- [x] 设置 › 关于 里的「Spotoolfy 回顾」入口（D15）。
- 交互原型已做：<https://claude.ai/artifact/PohjTmhwmz3MTkehDUu5it>。

### 阶段 5：Spotoolfy 维护版（不阻塞迁移）

- [x] **歌词服务与 Yoin 同步**（D12，2026-10-14 已完成，`p2o51/spotoolfy_flutter` 分支 `claude/optimistic-albattani-xk14mj`，版本 1.3.8+19）：
  - 移植 Yoin 的 `LyricCandidateMatcher`，QQ 与网易云的自动匹配都经过它；
  - QQ 取词换成数字 songID + `GetPlayLyricInfo`，主机轮换，旧接口兜底，翻译去掉 `//` 和注音行；
  - 歌词源顺序改为 QQ → 网易云 → LRCLIB；
  - 新的 LRC 解析与音源翻译逐行配对；
  - 手动选的歌词不再过期；
  - 115 个测试通过，其中 Yoin 的测试用例和录制数据原样移植。
- [ ] 一条可关闭的启动公告：
  - Spotoolfy 进入维护期，到 2027-10-14 为止；
  - Android 用户可以迁到 Yoin（导出 → 分享给 Yoin），并说明 Yoin 需要本机 Spotify；
  - iOS 和 Web 用户请导出留存。
- [ ] 去掉首次启动插入的样例数据。
- [ ] 除此之外不加任何功能。

### 阶段 6：灰度

1. owner 自己迁移，至少用 1 周。
2. 邀请 Spotoolfy 封闭测试用户加入 Yoin 封闭测试，复用同一个测试者名单。这同时满足 Yoin「12 名测试者连续 14 天」的门槛（`release-0.5-closed-test.md:60`）。
3. 收集反馈：导入成功率、跳过原因、回顾页停留与分享意愿。

### 阶段 7：维护期一年，然后下架（D9）

- **起止**：2026-10-14 起算，2027-10-14 结束（D9）。
- **维护期内**：只修崩溃和 Spotify API 兼容问题；导出必须一直可用。
- **期满下架**：
  - 最后一版只保留导出页；
  - Play 上撤销发布，但不删除应用记录；
  - 仓库归档，README 指向 Yoin。
- **长期保留的基础设施**：
  - `api.spotoolfy.gojyuplus.com`：Yoin 的网易云歌词依赖它（`NetEaseLyricsProvider.kt:224`）。
  - 隐私政策页：期满后再保留至少半年。
  - `lyrics.gojyuplus.com` 只服务 Spotoolfy Web 版，可以随 Web 版一起下线。

---

## 10. 收尾：React 原型 `p2o51/Spotoolfy`

- 它的数据在 Firestore `users/{uid}/thoughts`，字段为 `content`、`createdAt`、`trackId`、`trackName`、`artistName`、`rating`、`albumCover`。
- 用的是 Spotify 已废弃的 implicit grant，不可能还有真实用户在用。
- **建议**：
  - 若 owner 自己在 Firestore 里有数据，写一次性脚本转成 Spotoolfy v1 的 JSON，走同一个导入器；
  - 然后归档仓库，README 指向 Yoin。

---

## 11. 测试与验收

### 11.1 自动化（命名 `should_x_when_y`）

| 场景 | 期望 |
|---|---|
| v1 fixture 全量导入 | 笔记数 = 有正文的记录数 − 无效 ID 数；每曲评分 = 最新记录的映射值 |
| 目标 Profile 已有笔记、评分或专辑评分 | 资格检查拒绝，不写任何数据 |
| 同一文件再导一次 | 提示「已导入」，数据不变 |
| 换一个文件 | 上一批全部删除，新一批写入 |
| 云同步拉回同一批笔记 | 无重复（确定性 ID） |
| 样例行、无效 ID、字符串评分、缺字段 | 不崩溃，计入跳过原因 |
| 歌词片段含 LRC 标签或元数据行 | 剥离或丢弃 |
| 事务中途异常 | 全部回滚，没有批次记录 |
| 5 万条合成文件 | Robolectric 下 < 10 s，内存峰值 < 64 MB |
| 30→31 migration | 现有数据不变，新表可写 |
| 只有 link、没有播放记录的曲目 | 进入回忆候选；作为 Rediscover 的笔记卡出现 |
| Rediscover 混排 | 两类都充足时笔记卡约占 1/3；一类不足时另一类补满；笔记卡第二行是笔记摘录或分数，没有日期 |
| 暂存与写入 | 解析后只有 `staged` 批次，账号里没有任何笔记或评分；Spotify 连上后一次写入；放弃或引导结束时暂存批次和文件都被删除 |
| 剪贴板 Client ID | 32 位十六进制才预填；其余内容忽略 |
| `SpotoolfyRecapCalculator` | 中、英、日混排分词正确；停用词生效；歌词片段取最早和最近两段且来自不同的歌；数据不足时正确退化 |
| 分享接收 | 超过 32 MB 拒绝；引导之外收到文件时提示并删除；从不自动导入 |

### 11.2 实机 QA

- 先分享、后首次打开 Yoin；先打开 Yoin、再跟着 Spotoolfy 悬浮窗分享。两个顺序都能走到回顾页，连上 Spotify 后数据写入。
- Spotoolfy 悬浮窗：浅色 / 深色下的青绿色、四步的上一步 / 下一步、关屏锁屏后小窗的去留（与 Spotify 悬浮窗同一套规则）。
- Spotoolfy 步的返回、手势取消、三键返回（Pattern E）；回顾页的原生预测性返回（Pattern A）。
- 回顾页：减少动态效果、平板横屏、深色模式、TalkBack、离线时的封面退化。
- 走完引导后，首页出现回忆气泡；打开有笔记的专辑，剪贴簿显示这些笔记。

---

## 12. 待定事项

全部已定（2026-10-14）：

- Q1 日语和繁体中文界面 → 不做（D13）
- Q2 Rediscover 笔记卡比例 → 约 1/3，至少 1 张（D14）
- Q3 回顾页能否再打开 → 可以，设置 › 关于 里保留入口（D15）
- Q4 维护期起算 → 2026-10-14，至 2027-10-14（D9）

---

## 13. 附录：Spotoolfy v1 导出格式（现状，无版本号）

文件名 `spotoolfy_backup_<ISO8601，':' 替换为 '-'>.json`，经系统分享菜单发出，MIME 为 `application/json`。

```json
{
  "tracks":        [{ "trackId": "4uLU6hMCjMI75M1A2tKUQC", "trackName": "…", "artistName": "A, B",
                      "albumName": "…", "albumCoverUrl": "https://…", "lastRecordedAt": 1730000000000,
                      "latestPlayedAt": 1730000000000 }],
  "records":       [{ "id": 12, "trackId": "4uLU6hMCjMI75M1A2tKUQC", "noteContent": "…或 null",
                      "rating": 5, "songTimestampMs": 83000, "recordedAt": 1730000000000,
                      "contextUri": "spotify:album:…", "contextName": "…", "lyricsSnapshot": "…或 null" }],
  "translations":  [{ "trackId": "…", "languageCode": "zh-CN", "style": "faithful",
                      "translatedLyrics": "…", "generatedAt": 1730000000000 }],
  "play_contexts": [{ "contextUri": "spotify:playlist:…", "contextType": "playlist",
                      "contextName": "…", "imageUrl": "…", "lastPlayedAt": 1730000000000 }]
}
```

映射伪代码：

```kotlin
fun map(file: SpotoolfyExport, profileId: String, now: Long): ImportPlan {
    val tracks = file.tracks.associateBy { normalize(it.trackId) }
    val valid = file.records.mapNotNull { r ->
        val id = normalize(r.trackId) ?: return@mapNotNull skip(r, Reason.INVALID_ID)  // 含 sample 行
        r.copy(trackId = id)
    }
    val notes = valid.filter { !it.noteContent.isNullOrBlank() }.map { r ->
        SongNote(
            id = deterministicNoteId(r),
            profileId = profileId, trackId = r.trackId, provider = PROVIDER_SPOTIFY,
            content = composeContent(r.noteContent!!, r.lyricsSnapshot),
            createdAt = r.recordedAt, updatedAt = r.recordedAt,
            title = tracks[r.trackId]?.trackName ?: UNKNOWN_TITLE,
            artist = tracks[r.trackId]?.artistName.orEmpty(),
            positionMs = r.songTimestampMs?.takeIf { it > 0 },
        )
    }
    val ratings = valid.filter { it.rating in setOf(0, 3, 5) }
        .groupBy { it.trackId }
        .map { (trackId, rs) -> rs.maxBy { it.recordedAt }.let { r ->
            LocalRating(profileId, trackId, PROVIDER_SPOTIFY, rating = mapRating(r.rating!!),
                        serverRating = (mapRating(r.rating) / 2f).roundToInt(),
                        needsSync = false, updatedAt = r.recordedAt)
        } }
    return ImportPlan(notes, ratings, skipped, recap = SpotoolfyRecapCalculator.compute(file, notes, ratings))
}

fun mapRating(v: Int): Float = when (v) { 5 -> 10f; 3 -> 5f; 0 -> 1f; else -> error("filtered above") }
```

---

## 14. 给用户的迁移指引（草稿）

> 适用于 Android 上的 Spotoolfy 用户。iOS 和网页版用户请在维护期内用 Spotoolfy 的「导出数据」留存数据。

1. 安装并打开 Yoin，选服务时选上 Spotify。
2. Yoin 问「在用 Spotoolfy 吗？」，选「在用，带上我的数据」，点「打开 Spotoolfy」。
3. 跟着青绿色的 Spotoolfy 小窗做：复制 Spotify 客户端 ID → 导出数据 → 在分享菜单里选「导入到 Yoin」。
4. 看完你的 Spotoolfy 回顾，连接 Spotify：Client ID 已经填好，在你原来的 Spotify App 里加上 Yoin 的回调地址和 Android 包就行。
5. 连接成功，笔记和评分就放进了你的 Yoin。

---

## 15. 参考

- Spotify for Developers，*Update on Developer Access and Platform Security*（2026-02-06）：<https://developer.spotify.com/blog/2026-02-06-update-on-developer-access-and-platform-security>
- Spotify，*February 2026 Web API Dev Mode Changes – Migration Guide*：<https://developer.spotify.com/documentation/web-api/tutorials/february-2026-migration-guide>
- Spotify Community，*February 2026 Spotify for Developers update thread*：<https://community.spotify.com/t5/Spotify-for-Developers/February-2026-Spotify-for-Developers-update-thread/td-p/7330564>
- rspotify issue #550（受影响接口与字段，含 2026-03-13 恢复 `external_ids`）：<https://github.com/ramsayleung/rspotify/issues/550>
- 仓库内：`docs/design.md`（§首次引导、§回忆、§Rediscover）、`.claude/skills/predictive-back`（Pattern A / E）、`docs/cloud-sync.md`、`AGENTS.md`
