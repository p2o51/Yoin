# Spotoolfy → Yoin 迁移方案（最佳实践）

状态：主要决策已定（owner 2026-10-14），剩余待定项见 §12
基线：Yoin `main@e4bdd0d`（0.5.0 / Room v30）；Spotoolfy = `p2o51/spotoolfy_flutter@0a57d65`（1.3.7+18）

> 「Spotoolfy」在本文指 Flutter 版 `spotoolfy_flutter`：Play 封闭测试 + TestFlight + Web。
> `p2o51/Spotoolfy` 是 2024 年的 React 原型，数据在 Firestore `users/{uid}/thoughts`，只在 §10 收尾时处理。

---

## 0. 已定决策（owner 2026-10-14）

| # | 决策 | 落在本文 |
|---|---|---|
| D1 | **导入只对新用户开放**：只在首次引导里出现，目标账号必须还没有任何用户数据 | §4.1、§5 |
| D2 | **导入后展示一个好看的可视化回顾页**：多少条、写了什么、文字爆炸 | §6 |
| D3 | **引导页新增「Using Spotoolfy?」一问**：选文件导入，或提示去 Spotoolfy 导出、经分享菜单交给 Yoin | §5 |
| D4 | **Gemini API key 不导入** | §4.2 |
| D5 | **评分映射 👎/😐/🔥 → 1/5/10**（含 😐） | §4.4 |
| D6 | **Spotify 授权**：在原有 Spotify App 里加上 Yoin 一项即可，不做单独提醒或单独引导分支 | §7 |
| D7 | **导入的数据要进回忆和 Rediscover**：采用「曲目 → 专辑」事实表方案 | §4.6 |
| D8 | **翻译不迁移** | §4.2 |
| D9 | **Spotoolfy 维护期一年** | §9 阶段 7 |

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
4. **导入的价值要被「看见」**，靠三件事：
   - 导入后立即展示回顾页（§6）；
   - 用「曲目 → 专辑」事实表让数据进入回忆和 Rediscover（§4.6）；
   - 现有的 Now Playing 笔记页和专辑剪贴簿本来就能显示这些数据。
5. **Yoin 的前置清理只剩数据库卫生**：Room schema 导出、备份规则文件名、`activity_events` 触发器。
   - Gemini key 不再随迁移搬家，它的加密存储降为「建议，不阻塞」。
6. **Spotoolfy 进入一年维护期。** 期满后下架，但 Yoin 依赖的网易云代理 `api.spotoolfy.gojyuplus.com` **必须长期保留**。

---

## 2. 范围

**目标**

- 新装 Yoin 的 Spotoolfy Android 用户，在首次引导里一步把笔记和评分带过来。
- 导入后看到一份回顾，随后在 Now Playing、专辑剪贴簿、回忆和 Rediscover 里遇到这些数据。

**非目标（YAGNI）**

- 已经在用 Yoin 的老用户合并导入（D1）。
- 翻译、AI 缓存、播放上下文迁移（D8）。
- Spotoolfy 的 v2 导出格式或任何 Spotoolfy 侧改动。v1 已经够用，维护版只加一条公告（§9 阶段 5）。
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
  - **首次引导**（`LandingMode.FirstRun`）：本次引导连上了 Spotify 时出现。
  - **重新引导**（`LandingMode.Rerun`，设置 › 关于 › 重新引导）：只在满足硬条件时出现。这样刚走完引导、还没来得及导出的用户有一次补救机会，同时没有放开合并导入。
- **分享入口**：只在引导进行中有效。引导之外收到 Spotoolfy 文件时，一行提示「Spotoolfy 数据只能在引导中导入」，并删除文件（§5.3）。

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

**方案第 2 部分：从未在 Yoin 播放过的专辑或曲目，用「上次写下」作为离开时钟**

- 没有播放记录时，用 `lastWrittenAt`（最新笔记或评分的时间）代替 `lastPlayedAt` 判断「离开 90 天」。
- 卡片文案从「上次播放于…」改为「上次写下于…」。
- 这样 Spotoolfy 里几个月前写过、在 Yoin 里还没重听的专辑，会如实以「你写过它」的身份回来。
- 这一项改变了 Rediscover 的语义，需要同步写进 `design.md` 的 Rediscover 一节，见 §12 Q2。

**被否的备选**

- 伪造 `play_history`：污染 Most Played 和「90 天未播放」。
- 给 `song_notes` 加 `albumId` 列：同步载荷要升版本，每条笔记都要冗余一份。

### 4.7 批次与「换一个文件」

```text
import_batches(batchId PK, source="spotoolfy", profileId, fileSha256, importedAt,
               noteCount, ratingCount, summaryJson)
import_items(batchId, targetKind, targetKey)   -- PK (targetKind, targetKey)
```

- `summaryJson` 存回顾页数据（§6.3）。回顾页是独立的 Activity，只凭 `batchId` 就能从 Room 读到完整数据。
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

## 5. 引导页：「Using Spotoolfy?」（D3）

### 5.1 位置与形态

插在 Spotify 连接页之后：

```text
你好 → 选服务 → Yoin 负责/不负责 → 各服务连接页 → 【在用 Spotoolfy 吗？】 → 主页内容 → 顶部边缘 → 完成
```

- 新增 `LandingStep.Spotoolfy`。只有当本次引导里 Spotify 连接成功、且满足 §4.1 的硬条件时，才进入 `landingSteps(...)`。
- 返回分类沿用 **Pattern E（分步流程）**，不新写返回逻辑：返回 = 上一步。已经导入的数据不因返回而撤销，回到这一步时显示完成状态。
- 角色（mascot）反应沿用 `design.md` 的既有语言：
  - 选中选项时点头；
  - 导入成功时举手蹦一下；
  - 文件不认识时摇头。
- 气泡：英文 `Using Spotoolfy?`，中文「在用 Spotoolfy 吗？」。

### 5.2 浮动窗里的内容（单选，三行；形态同 Apple Music 连接页的两行单选）

1. **「我有导出文件」** → 系统文件选择器 `OpenDocument(arrayOf("application/json", "text/plain"))`。部分文件管理器会把 `.json` 标成 `text/plain`。
2. **「还没导出」** → 展开一句说明：「打开 Spotoolfy › 设置 › 数据管理 › 导出数据，在分享菜单里选 Yoin」。
   - 附一个「打开 Spotoolfy」按钮。这需要在 manifest 的 `<queries>` 里声明 Spotoolfy 包名；没装 Spotoolfy 时隐藏按钮。
   - Yoin 停在这一步等待分享回来（§5.3）。
3. **「没用过」** → 胶囊主按钮变为「下一步」。

### 5.3 选好文件之后的状态

| 状态 | 表现 |
|---|---|
| 解析中 | 浮动窗显示进度；胶囊主按钮禁用 |
| 待确认 | 一句话：「找到 N 条笔记、M 首歌的评分，导入到 〈账号名〉？」，主按钮「导入」。新用户没有冲突，所以只需这一次确认 |
| 导入中 | 进度；不可返回中断（事务很短） |
| 完成 | 自动打开回顾页（§6）。返回引导后浮动窗显示「✓ 已导入 N 条笔记」，附「再看一次回顾」「换一个文件」，主按钮「下一步」 |
| 失败 | 角色摇头，给出原因（不是 Spotoolfy 文件、文件损坏、为空），提供「换一个文件」 |

### 5.4 分享入口（从 Spotoolfy 的分享菜单进来）

- 新增一个分享接收 Activity（暂名 `SpotoolfyShareReceiverActivity`）：
  - exported、透明主题、`excludeFromRecents`、`noHistory`；
  - intent-filter 为 `ACTION_SEND` + `application/json`；
  - 在分享菜单里显示为「导入到 Yoin」。
- **收到后立刻**把流复制到 `noBackupFilesDir/spotoolfy-import/pending.json`（超过 32 MB 拒绝），然后用内部 action 拉起 `MainActivity`，不把外部 URI 往下传。这样做的原因：
  - 分享来的 URI 读权限只属于接收它的 Activity，转交容易失效；
  - `MainActivity` 不必对外暴露新的 intent 入口。
- `MainActivity` 拿到待导入文件后：
  - **引导进行中**：保留文件。到 Spotoolfy 这一步时自动进入「待确认」，并提示「已收到你从 Spotoolfy 分享的文件」。用户先分享、后打开 Yoin 也一样适用，顺序无关。
  - **引导之外**：一行 snackbar「Spotoolfy 数据只能在引导中导入」，并删除文件。
- 绝不静默导入，所有路径都要经过「待确认」。

### 5.5 实现落点

- 数据层：`data/migration/spotoolfy/`
  - `SpotoolfyExportParser`（流式、宽容）
  - `SpotoolfyImportMapper`（纯函数）
  - `SpotoolfyRecapCalculator`（纯函数，§6.3）
  - `SpotoolfyImporter`（事务、批次、资格检查）
  - `TrackAlbumLinkEnricher`（后台补全）
- 界面层：
  - 引导步骤放在 `ui/landing/LandingSpotoolfyStep.kt`，复用 `LandingViewModel` 的步骤机制；
  - 分享接收放在 `ui/landing/import/`。
- 写入经 `YoinRepository` 新增的方法，UI 不直接调用 DAO。
- 实现完成后，把这一步补进 `design.md` §首次引导：那里是 UI 的事实来源（AGENTS.md）。

---

## 6. 导入回顾页（D2）

### 6.1 定位

- 新的全屏目的地 `SpotoolfyRecapActivity`，导入成功后由引导自动打开。
- **返回分类：Pattern A（原生跨 Activity 预测性返回）**：没有共享 chrome，注册零个返回回调，返回落在引导的 Spotoolfy 步。
  - 页内**不加** `BackHandler`。展开的词卡点空白处收起，而不是用返回键收起，否则会压掉原生预览（predictive-back 不变式 13）。
  - 结尾的「继续」按钮直接 `finish()`。
- 数据只凭 `batchId` 从 `import_batches.summaryJson` 读取，符合「Activity 边界即数据边界」。

### 6.2 页面结构（竖向分页，每屏一个场景，进入当前屏时才播放动画）

| # | 场景 | 内容 | 动效 |
|---|---|---|---|
| 1 | **开场** | 大数字「N 条笔记」；副标题「M 首歌 · K 位艺人 · 2024-11-06 → 2026-07-16」 | 数字滚动计数；最多 24 张专辑封面从中心**爆炸**散开、落成拼贴 |
| 2 | **你最常写的词** | 笔记正文分词后的关键词，最多 48 个；字号按词频分 5 档 | 文字爆炸：词从中心按名次错开弹出，落到螺旋排布的位置；点一个词 → 词卡展开，列出最多 3 条包含它的笔记片段和曲名 |
| 3 | **你截下的歌词** | `lyricsSnapshot` 去重后的歌词行，最多 20 行，每行带小字曲名 | 行级爆炸：歌词行像便签一样飞散落位；点一行展开整段 |
| 4 | **打分** | 🔥 / 😐 / 👎 三堆计数 | 三根柱子长出后变形为 Yoin 的 `ScoreEmblem` 10 / 5 / 1，一句话说明映射 |
| 5 | **时间** | 按月的笔记柱状图；「你最常在 23 点写笔记」；最长连续记录天数 | 柱子依次升起；一天 24 小时排成一圈，最高的那个钟点亮起 |
| 6 | **写得最多的** | Top 3 曲目（封面 + 条数）、Top 3 艺人 | 卡片依次落下 |
| 7 | **一切的开始** | 第一条笔记：日期、曲目、正文 | Big Bang 式：正文拆成词块散开，再按原顺序合拢成句 |
| 8 | **都在 Yoin 里了** | 去哪里找：Now Playing 的笔记页、专辑剪贴簿、回忆；「N 条笔记会跟着歌词时间线出现」（有 `positionMs` 的） | 收尾，「继续」 |

数据不足时场景自动省略或退化：

- 笔记少于 20 条：第 2 屏退化为笔记卡片墙。
- 没有歌词片段：省略第 3 屏。
- 没有评分：省略第 4 屏。
- 时间跨度不到 30 天：第 5 屏只保留钟点圈。

### 6.3 哪些内容适合做「文字爆炸」

| 素材 | 适合度 | 说明 |
|---|---|---|
| 专辑封面（图片爆炸） | ★★★ | 视觉冲击最强，放开场 |
| 笔记正文关键词 | ★★★ | 最个人化；需要分词、停用词和最低词频（≥ 2） |
| 歌词片段 | ★★★ | 行级、天然好看，且多为用户亲手选的 |
| 第一条笔记逐词拆分 | ★★ | 单条，仪式感强，放在结尾前 |
| 艺人名 | ★★ | 短、辨识度高，可以并进第 6 屏 |
| 曲名 | ★ | 太长，挤在一起不好看 |
| 歌单或上下文名 | ✗ | 数据不可靠（§4.3） |

**分词**：用 `android.icu.text.BreakIterator.getWordInstance(ULocale.ROOT)`（API 24+，基于词典，能处理中、日、英混排）。

- 过滤规则：
  - 中日文词长 ≥ 2，拉丁词长 ≥ 3；
  - 去掉纯数字和标点；
  - 停用词表（中、英、日各一份小表，放 `res/raw`）。
- emoji 单独计数，最多取 3 个进词云：🔥 在 Spotoolfy 用户的笔记里很常见，留着反而有味道。
- 全部在本地计算，不调用 Gemini。新用户此时也还没有 key。

**`summaryJson` 结构**（`SpotoolfyRecapCalculator` 在导入时一次算好）：

```kotlin
@Serializable
data class SpotoolfyRecap(
    val noteCount: Int, val ratedTrackCount: Int, val trackCount: Int, val artistCount: Int,
    val firstRecordAt: Long, val lastRecordAt: Long, val totalChars: Int,
    val anchoredNoteCount: Int,                       // 有 positionMs 的笔记
    val ratingCounts: Map<Int, Int>,                  // 5 / 3 / 0 → 曲目数
    val monthly: List<MonthBucket>, val hourOfDay: List<Int>, val longestStreakDays: Int,
    val topTracks: List<TrackStat>, val topArtists: List<ArtistStat>,
    val keywords: List<Keyword>,                      // text, count, noteIds（≤ 3）
    val lyricLines: List<LyricLine>,                  // text, trackTitle
    val firstNote: NoteRef?, val coverUrls: List<String>,
)
```

### 6.4 动效与工程约束（对齐 AGENTS.md）

- **全部用弹簧**：位置和尺寸用 spatial spring，颜色和透明度用 effect spring。爆炸的错开节奏用 Yoin 现有 motion token，不新增魔法数。
- **触感**：爆炸起点一拍（`YoinHaptics`）；词落位不逐个震，免得吵。
- **性能**：
  - 每个词块的位移和缩放只在 `graphicsLayer` 里读取，动画期间不重组；
  - 封面按 128px 解码；
  - 动画进行时投票高帧率；
  - 排布在后台线程计算，以 `batchId` 作随机种子，旋转屏幕后布局不变。
- **减少动态效果**：系统关闭动画时，直接淡入最终布局。
- **自适应**：平板横屏时，每屏左侧是可视化、右侧是文字（`docs/adaptive-principles.md`）。
- **颜色**：动态色，深浅模式都适用。词块轮流使用 primary / secondary / tertiary 的 container 色。
- **无障碍**：每屏提供一句完整的摘要给读屏；飞散的词块 `clearAndSetSemantics`，另外给读屏一个按词频排序的列表。
- **文案**：进 `values/` 和 `values-zh-rCN/`；数字和日期按 locale 格式化；通过 `scripts/check-hardcoded-strings.sh`。
- **封面**：来自 Spotify CDN 的 `albumCoverUrl`。离线或加载失败时退化为色块拼贴，不留白洞。
- **分享回顾图**：P2。可以复用歌词分享卡的 GraphicsLayer 录制管线。

---

## 7. Spotify 授权（D6）

- Spotoolfy 用户**在原来的 Spotify App 里加上 Yoin**：
  - Redirect URI `yoin://auth/spotify/callback`；
  - Android package `com.gpo.yoin` + SHA-1；
  - 原来的 Client ID 照用。
- Yoin 现有的连接引导和悬浮窗已经能复制这些值，包括本机安装包的签名 SHA-1（`ConnectGuide.kt:73-82`）。**不做单独的引导分支，也不加单独的提醒文案。**
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
| 日语 / 繁体中文界面 | ❌ | 待定（§12 Q1） |
| iOS / Web | ❌ | 维护期内保留导出 |

---

## 9. 分阶段路线图

### 阶段 0：Yoin 前置清理

- [ ] **启用 Room schema 导出，并提交 v30 基线**：必须在写 30→31 之前完成（`app/build.gradle.kts:119` 至今只有注释）。
  - 目前没有应用 `androidx.room` Gradle 插件：要么引入插件并配置 `room { schemaDirectory(...) }`，要么给 KSP 传 `room.schemaLocation`。
  - 同时把 `schemas/` 加进 `androidTest` 的 assets。
- [ ] 修正 `data_extraction_rules.xml`：规则里写的是 `profile_active_id.xml`，实际文件是 `yoin_profiles`（`ProfileActiveIdStore.kt:29`）。
- [ ] 全新安装时补建 `activity_events` 的上限触发器：现在只在 10→11 migration 里创建（`AppContainer.kt:1108-1131`）。
- [ ] 更新 AGENTS.md 的过时描述：第 9 行称 Spotify 为 planned，第 89 行称凭据是明文 codec。
- 建议，不阻塞迁移：Gemini key 改为加密存储并移出备份（目前明文存在 `GeminiConfig.kt:9`）。

### 阶段 1：契约与样本

- [ ] 把 §13 的 v1 格式抽成 `docs/integrations/spotoolfy-export-format.md`。
- [ ] 准备脱敏 fixtures：
  - owner 的真实导出；
  - 含样例行的全新安装导出；
  - 含字符串评分的导出；
  - Web 版导出；
  - 5 万条的合成文件。

### 阶段 2：导入核心

- [ ] Room 30→31：`track_album_links`、`import_batches`、`import_items`，加 migration 测试。
- [ ] 两个专辑聚合查询改为并集；Rediscover 加「上次写下」时钟（§4.6）。
- [ ] Parser、Mapper、RecapCalculator、Importer（含资格检查、换文件）。
- [ ] `recordPlay` 和专辑详情加载时写 `track_album_links`；后台补全任务。

### 阶段 3：引导步骤与分享入口

- [ ] `LandingStep.Spotoolfy`（§5）、分享接收 Activity、manifest 的 `<queries>`；补 `design.md` §首次引导。

### 阶段 4：回顾页

- [ ] `SpotoolfyRecapActivity`（§6），中英文案，减少动态效果、平板、读屏三项验收。

### 阶段 5：Spotoolfy 维护版（不阻塞迁移）

- [ ] 一条可关闭的启动公告：
  - Spotoolfy 进入一年维护期；
  - Android 用户可以迁到 Yoin（导出 → 分享给 Yoin），并说明 Yoin 需要本机 Spotify；
  - iOS 和 Web 用户请导出留存。
- [ ] 去掉首次启动插入的样例数据。
- [ ] 除此之外不加任何功能。

### 阶段 6：灰度

1. owner 自己迁移，至少用 1 周。
2. 邀请 Spotoolfy 封闭测试用户加入 Yoin 封闭测试，复用同一个测试者名单。这同时满足 Yoin「12 名测试者连续 14 天」的门槛（`release-0.5-closed-test.md:60`）。
3. 收集反馈：导入成功率、跳过原因、回顾页停留与分享意愿。

### 阶段 7：维护期一年，然后下架（D9）

- **起算点**：建议从 Yoin 公开上架（任何人都能安装）那天起算，见 §12 Q4。
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
| 只有 link、没有播放记录的曲目 | 进入回忆候选；Rediscover 按「上次写下」判断 90 天 |
| `SpotoolfyRecapCalculator` | 中、英、日混排分词正确；停用词生效；数据不足时正确退化 |
| 分享接收 | 超过 32 MB 拒绝；引导之外收到文件时提示并删除；从不自动导入 |

### 11.2 实机 QA

- 先分享、后首次打开 Yoin；先打开 Yoin、再回 Spotoolfy 分享。两个顺序都能走到「待确认」。
- Spotoolfy 步的返回、手势取消、三键返回（Pattern E）；回顾页的原生预测性返回（Pattern A）。
- 回顾页：减少动态效果、平板横屏、深色模式、TalkBack、离线时的封面退化。
- 走完引导后，首页出现回忆气泡；打开有笔记的专辑，剪贴簿显示这些笔记。

---

## 12. 待定事项（附推荐）

- **Q1. 日语和繁体中文界面**：先看 Spotoolfy 在 Play Console 的语言分布再定。
- **Q2. Rediscover 用「上次写下于」**作为从未在 Yoin 播放过的内容的离开时钟（§4.6 第 2 部分）。推荐：采用，并写进 `design.md`。
- **Q3. 回顾页以后能否再打开**：推荐 P2，在设置 › 关于放一个「Spotoolfy 回顾」入口（只在有导入批次时显示）。
- **Q4. 维护期一年从哪天起算**：推荐从 Yoin 公开上架日起算，而不是维护版发布日。否则封闭测试期间就在消耗用户的迁移窗口。

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

1. 在 Spotoolfy 里：设置 › 数据管理 › 导出数据，在分享菜单里选 **Yoin**。也可以先把文件存下来。
2. 安装并打开 Yoin，按引导连接 Spotify：在你原来的 Spotify App 里加上 Yoin 的回调地址和 Android 包，Client ID 照用。
3. 引导问「在用 Spotoolfy 吗？」：选文件，或者确认刚才分享过来的文件，点「导入」。
4. 看完你的 Spotoolfy 回顾，继续完成引导。

---

## 15. 参考

- Spotify for Developers，*Update on Developer Access and Platform Security*（2026-02-06）：<https://developer.spotify.com/blog/2026-02-06-update-on-developer-access-and-platform-security>
- Spotify，*February 2026 Web API Dev Mode Changes – Migration Guide*：<https://developer.spotify.com/documentation/web-api/tutorials/february-2026-migration-guide>
- Spotify Community，*February 2026 Spotify for Developers update thread*：<https://community.spotify.com/t5/Spotify-for-Developers/February-2026-Spotify-for-Developers-update-thread/td-p/7330564>
- rspotify issue #550（受影响接口与字段，含 2026-03-13 恢复 `external_ids`）：<https://github.com/ramsayleung/rspotify/issues/550>
- 仓库内：`docs/design.md`（§首次引导、§回忆、§Rediscover）、`.claude/skills/predictive-back`（Pattern A / E）、`docs/cloud-sync.md`、`AGENTS.md`
