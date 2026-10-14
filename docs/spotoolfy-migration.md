# Spotoolfy → Yoin 迁移方案（最佳实践）

状态：提案，待 owner 拍板（2026-10-14）
基线：Yoin `main@e4bdd0d`（0.5.0 / Room v30）；Spotoolfy = `p2o51/spotoolfy_flutter@0a57d65`（1.3.7+18）

> 「Spotoolfy」在本文指 Flutter 版 `spotoolfy_flutter`：Play 封闭测试 + TestFlight + Web。
> `p2o51/Spotoolfy` 是 2024 年的 React 原型，数据在 Firestore `users/{uid}/thoughts`，只在 §9.3 处理。

---

## 0. 一页结论

1. **两个 App 是不同的包，不存在原地升级。**
   - Spotoolfy 是 `com.gojyuplusone.spotoolfy.spotoolfy_flutter`，Yoin 是 `com.gpo.yoin`。
   - Android 沙盒互不可读，所以数据迁移必须是**用户主动发起的文件交接**：Spotoolfy 导出 → Yoin 导入。
   - 好消息是 Spotoolfy 已经有 JSON 导出（`local_database_provider.dart:524`）。Yoin 只需补一个导入器，Spotoolfy 端可以一行不改就先跑通。
2. **只迁移用户亲手写下的东西**：笔记（`records.noteContent`）和评分（`records.rating`）。
   - 其余都是缓存或可再生数据，不迁：翻译、歌词缓存、AI 缓存、播放上下文、token。
   - 也**绝不伪造行为数据**：不造 `play_history`，不凭空生成专辑评分。
3. **导入必须满足五条：可预览、幂等、可撤销、单事务、不覆盖更新的数据。**
   - 笔记 ID 用确定性 UUID，重复导入、多设备导入加云同步都不会出现重复。
   - 评分按时间戳后写胜出（LWW）。
4. **Spotify 授权是最大的人为风险。** 2026-03-09 起，Development Mode 对存量 App 也生效三条限制：每个开发者只能有 1 个 Client ID、每个 App 最多 5 个用户、App 所有者必须是 Premium。
   - 所以已经在 Spotoolfy 里自建过 Spotify App 的用户，**很可能无法再建第二个**。
   - 最佳做法是**复用原来的 Client ID**：在同一个 Spotify App 里追加 Yoin 的 redirect URI、包名和 SHA-1。
   - Yoin 的连接引导需要为此加一条「我已有 Spotify App」分支。
5. **迁移前 Yoin 先补三个前置项。**
   - Room schema 导出：目前没有 `app/schemas`。
   - Gemini key 改为加密存储：目前是明文，而且会进 Auto Backup。
   - 「专辑归属」缺口：导入的笔记和评分没有 Yoin 播放记录，进不了回忆和 Rediscover，见 §4.6。
6. **分阶段上线，Spotoolfy 进入维护期而不是立刻下架。**
   - iOS 和 Web 用户在 Yoin 里没有去处，他们的导出能力至少保留 12 个月。
   - Yoin 的网易云歌词仍依赖 Spotoolfy 的代理 `api.spotoolfy.gojyuplus.com`，**下架 Spotoolfy 时不能关掉它**。

---

## 1. 范围与目标

**目标**

- 现有 Spotoolfy Android 用户迁到 Yoin 后，零丢失地保留笔记和评分，并能在 Yoin 的现有界面里看到它们：
  - Now Playing 的「笔记」页
  - 专辑剪贴簿
  - 回忆和 Rediscover
- 用户侧迁移步骤 ≤ 5 步。Spotify 授权一次完成，不需要新建 Spotify App。
- Spotoolfy 有序进入维护期，有明确的下架判据。

**非目标（YAGNI，对齐 AGENTS.md）**

- 不把 Spotoolfy 的全部功能搬进 Yoin。功能取舍见 §7，按「影响已迁移数据可见性」排优先级。
- 不做跨 Profile 或跨 provider 的自动合并（`design.md:253` 的阶段约束）。导入只写入一个 Spotify Profile。
- 不迁移 iOS 和 Web 用户的设备内数据：Yoin 只有 Android 版。他们可以导出 JSON 留存，日后再导入。

---

## 2. 两边现状对照

| 维度 | Spotoolfy (Flutter) | Yoin (Kotlin/Compose) | 迁移含义 |
|---|---|---|---|
| 平台 | Android / iOS / Web | 仅 Android（minSdk 26） | iOS 和 Web 用户无法迁移，只能保留导出 |
| 包名 | `com.gojyuplusone.spotoolfy.spotoolfy_flutter` | `com.gpo.yoin` | 不是原地升级，必须走文件交接 |
| Spotify 授权 | `spotify_sdk.getAccessToken`：只有 access token，没有 refresh token，55 分钟过期（`spotify_service.dart:375-487`） | Authorization Code + PKCE，有 refresh token，凭据 AES-GCM 加密（`SpotifyAuthService.kt`） | token 不迁，用户在 Yoin 里重新授权一次 |
| Redirect URI | `spotoolfy://callback` | `yoin://auth/spotify/callback` | 需要在同一个 Spotify App 里**追加**，不要替换 |
| Client ID | prefs `spotify_client_id` → 内置 `Env.clientId` | Room `spotify_config`（每设备一行）→ `BuildConfig` | 可以随迁移包带过去并预填 |
| 播放控制 | Web API / Spotify Connect，可以遥控任意设备 | App Remote，要求本机装有 Spotify App；Web API 起播是备选路径 | 行为变化，需要在说明里讲清楚 |
| 本地库 | sqflite `spotoolfy_database.db` v1，4 张表 | Room `yoin-database` v30 | 字段映射见 §4 |
| 评分 | 三档：0 👎 / 3 😐 / 5 🔥，按「记录」存，最新一条生效 | 0–10 浮点，步长 0.1，每曲一行（`local_ratings`） | 映射见 §4.3 |
| 笔记 | `records`：一条记录 = 评分 + 可选文本 + 歌词片段 + 播放位置 + 上下文 | `song_notes`：文本 + `positionMs` + 标题/艺人 | 歌词片段并入正文，上下文丢弃 |
| 专辑评分 | 不存储，按曲目平均实时计算（`album_page.dart:395-414`） | `album_ratings` 存储，并同步 NeoDB | **不生成**，见 §4.5 |
| 翻译 | Gemini 原文，按 (trackId, lang, style) 存 | `lyrics_translation_cache`，按 sourceHash 和 model 存 | 不迁（缓存，key 对不上） |
| AI | `gemini-flash-latest` / `gemini-3-flash-preview`，key 在 secure storage | `gemini-3.1-flash-lite`，key 在 Room 里**明文** | key 让用户重新填；Yoin 先改为加密存储 |
| 备份 | 手动 JSON 导出/导入（无版本号） | Google Drive appdata 云同步 + Auto Backup；**没有手动导入导出** | Yoin 新增的导入正好填上 `cloud-sync.md:107` 的待办 |
| 语言 | en / ja / zh / zh_TW | en / zh-rCN | ja 和 zh-TW 用户会降级为英文，见 §7 |

---

## 3. 指导原则（Best Practices）

1. **先分清用户数据和缓存。** 只有用户亲手写下的内容不可再生，必须零丢失；缓存允许丢弃，代价只是重新拉取或一次 Gemini 调用。
2. **映射有损时，要显式、可追溯。**
   - 每个有损决策写进本文 §4 和导入报告。
   - 保留原始导出文件（用户自己持有）作为最终真相源。
   - 导入台账记录「源记录 → 目标行」。
3. **不伪造行为数据。** 回忆、Rediscover、Most Played 都建立在真实播放之上。造播放记录会让这些功能「说谎」，所以缺口用新的事实表补（§4.6），而不是造数据。
4. **导入要像数据库迁移一样严谨。**
   - 先校验，再预览，最后在一个 Room 事务里提交。
   - 失败整体回滚；成功的那批可以一键撤销。
   - 重复执行结果不变（幂等）。
5. **与云同步天然兼容。** Yoin 的同步适配器按扫描方式读表（`SongNoteSyncAdapter.readAll`）。导入只要写进正确的表、带对的时间戳和稳定的 ID，就会自动同步到其他设备，不需要特殊通道。
6. **遵守 Yoin 的既有约束。**
   - 每张引用远端 ID 的表都带 `provider`，并按 Profile 隔离。
   - 新页面先归类返回面（导入页 = PushPage，走共享返回实现，见 `.claude/skills/predictive-back`）。
   - 文案进 `values/` 和 `values-zh-rCN/`，不硬编码。
   - 测试命名 `should_x_when_y`。
7. **用版本化的契约加两端共用的 fixtures 锁定格式。** 导出格式写成文档（§10），并在两个仓库放同一批 golden 文件做测试。
8. **灰度上线，先拿 owner 自己的数据演练。** 先 dogfood，再开放给封闭测试用户；以观测数据决定是否下架 Spotoolfy。

---

## 4. 数据迁移设计

### 4.1 数据盘点与处置

| Spotoolfy 存储 | 内容 | 处置 | 理由 |
|---|---|---|---|
| `records`（有 `noteContent`） | 笔记 | ✅ → `song_notes` | 用户手写，不可再生 |
| `records.rating` | 评分历史 | ✅ 每曲取最新一条 → `local_ratings` | Yoin 每曲只存一个当前值；完整历史保留在原始文件里 |
| `records`（`noteContent` 为空） | 快速评分 | ✅ 只贡献评分，不生成笔记 | 避免产生空笔记 |
| `tracks` | 曲名、艺人、专辑名、封面 | ✅ 作为笔记的 `title`/`artist`，以及专辑关联的线索 | 冗余元数据，可以从 Spotify 补全 |
| `translations` | Gemini 翻译 | ❌ 不迁 | key 对不上 `(sourceHash, model)`；属于缓存，可以重新生成 |
| `play_contexts` | 最近播放的歌单/专辑 | ❌ 不迁 | 只有最后一次时间，不是真实的播放序列，导入会污染 Recents 和回忆 |
| prefs `lyrics_cache_*` / `netease_translation_*` / `translation_cache_*` | 歌词与翻译缓存 | ❌ 不迁（本来也不在导出里） | 可再生 |
| prefs `cached_song_info_*`、`album_insights_*`、`cached_music_insights` | AI 缓存 | ❌ 不迁 | 可再生；Yoin 的 About 表结构也不同 |
| prefs `spotify_client_id` | 用户的 Client ID | ✅（v2 导出）→ `spotify_config`，**仅在设备上还没有的时候预填** | Client ID 是公开标识（PKCE 无 secret），复用它是关键 |
| secure `target_translation_language` | 翻译目标语言 | ✅（v2 导出）→ `gemini_config.targetLanguage` | `en`/`zh-CN`/`zh-TW`/`ja` 都能一一对应 Yoin 的 8 种语言 |
| secure `gemini_api_key` | API key | ❌ **不进导出文件**，让用户重新粘贴 | 导出文件会被分享和转存，不能装秘密 |
| secure `spotify_access_token` | token | ❌ | 不同的 App 和授权流程，而且 1 小时就过期 |
| `last_used_rating`、`selected_language` 等 | UI 偏好 | ❌ | Yoin 没有对应概念 |

### 4.2 记录级映射（`records` + `tracks` → `song_notes`）

| `song_notes` 字段 | 来源 | 规则 |
|---|---|---|
| `id` | 派生 | `UUID.nameUUIDFromBytes("spotoolfy:v1|$trackId|$recordedAt|${sha256(noteContent)}")`。不直接用 `records.id`：那是每台设备各自的自增 ID，两台手机的导出会撞号 |
| `profileId` | 用户在预览页选择 | 默认当前激活的 Spotify Profile；没有 Spotify Profile 时先引导连接 |
| `provider` | 常量 | `MediaId.PROVIDER_SPOTIFY` |
| `trackId` | `records.trackId` | 去掉 `spotify:track:` 前缀，再校验 `^[0-9A-Za-z]{22}$`。不合格的跳过并计入报告，内置样例 `spotify:track:sample1/2` 会在这一步被滤掉 |
| `content` | `noteContent` + `lyricsSnapshot` | 见 §4.4 |
| `createdAt` / `updatedAt` | `recordedAt` | 保留原始时间（毫秒），让笔记在时间线、回忆和同步的 LWW 里处在正确的位置 |
| `title` / `artist` | `tracks.trackName` / `artistName` | `artistName` 本来就是 `", "` 拼接的，原样保留 |
| `positionMs` | `songTimestampMs` | 为 null 或 ≤ 0 时写 null；它让笔记自动挂到歌词时间线上 |
| — | `contextUri` / `contextName` | 丢弃。Yoin 笔记没有上下文字段；而且 Spotoolfy 即使在歌单里播放也写入专辑名（`add_note.dart:137-140`），信息本来就不可靠 |

### 4.3 评分映射（三档 → 0–10）

| Spotoolfy | 含义 | Yoin `local_ratings.rating` |
|---|---|---|
| 5 | 🔥 whatshot | **10.0** |
| 3 | 😐 neutral | **5.0** |
| 0 | 👎 thumb_down | **1.0** |
| NULL / 其它 | 未评分 | 不写 |

- **为什么是 1/5/10：** 这就是 Spotoolfy 自己算专辑分时用的映射（`album_page.dart:1140-1150` 的 `_mapRatingToScore`）。用户在 Yoin 里看到的专辑平均分和在 Spotoolfy 里看到的一致。
- **为什么 👎 不映射成 0：** Yoin 把 `rating > 0` 当作「打过分」的信号（`LocalRatingDao.getRatedAlbumAggregates`），滑条上 0 也显示为空。映射成 0 会让「明确不喜欢」等同于「没评」。
- **取哪一条：** 每个 `(profileId, trackId)` 取 `MAX(recordedAt)` 那条记录，和 Spotoolfy 的「最新评分」语义一致（`database_helper.dart:230-294`）。
- **写入方式：**
  - `updatedAt = recordedAt`。
  - Spotify 不支持服务端评分（`SpotifyMusicSource.setRating` 返回 failure），所以写 `needsSync = false`，`serverRating` 按 `round(r/2)` 照常计算。
- **与 Yoin 已有评分冲突时：**
  - Yoin 那条的 `updatedAt` 更新，就保留 Yoin 的。
  - 否则用 Spotoolfy 的。
  - 预览页列出冲突数量。
- **注意：** `records.rating` 的默认值是 3，而且添加笔记时会沿用上次的评分（`add_note.dart:30`），所以部分 😐 可能是顺手带上的。建议照常导入，这正是 Spotoolfy 界面上显示的「最新评分」；如果 owner 不同意，见 §11 待决问题 Q2。

### 4.4 笔记正文的组装

Yoin 的笔记是纯文本，不渲染 Markdown。`lyricsSnapshot` 可能是用户在歌词选择页选中的行，也可能是自动截取的 3 行，两者无法区分，所以保守地无损保留：

```text
<noteContent，trim 后>

「<歌词片段第 1 行>
  <歌词片段第 2 行>」
```

- 用正则 `^\[[^\]]*\]` 去掉片段里的 LRC 标签。在没有时间戳的分支里，Spotoolfy 会把整份 LRC 的前 3 行原样存下来，其中可能是 `[ti:…]` 这类元数据行，这类行整行丢掉。
- 只有快速评分（`noteContent` 为空）时，片段直接丢弃，不生成笔记。
- 预览页提供开关「附带当时的歌词片段」，默认开启。

### 4.5 不生成专辑评分

Spotoolfy 从未存储专辑评分。Yoin 一旦生成 `album_ratings` 行，就会：

1. 把用户从没打过的分推到 NeoDB（`NeoDBSyncService`）；
2. 让回忆的写作门槛（专辑评分或书评）被一个推算值满足。

两者都违反「不伪造」原则。曲目评分导入之后，专辑剪贴簿和回忆会按 Yoin 自己的规则计算平均分和覆盖率，效果等价而且诚实。

### 4.6 关键缺口：导入的数据进不了回忆和 Rediscover

现状是 `song_notes` 和 `local_ratings` 不带专辑。专辑归属**只能**通过 `play_history` 的 JOIN 得到（`SongNoteDao.getNotedAlbumAggregates`、`LocalRatingDao.getRatedAlbumAggregates`）。没在 Yoin 里播放过的曲目，导入的笔记和评分只会出现在：

- ✅ Now Playing 的「笔记」页（按曲目查）；
- ✅ 专辑详情的剪贴簿（按专辑曲目表查）；
- ❌ 回忆候选和 Rediscover（依赖 play_history）。

对一个刚迁移过来、有几百条笔记、但在 Yoin 里一首都还没播过的用户来说，回忆页会是空的，这是迁移体验的头号问题。

**推荐方案：新增事实表 `track_album_links`**（Room 30→31）

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

- 它记录的是「这首曲属于哪张专辑」这一**客观事实**，不是行为数据。
- 仍然要按 Profile 隔离：两个 Subsonic 服务器共用 provider token `subsonic`，但 ID 空间各自独立，和 `play_history` 带 `profileId` 是同一个原因。
- 两个 DAO 查询把 `play_history` 子查询改成 `play_history ∪ track_album_links` 的去重并集。`recordPlay` 和专辑详情加载时也顺手写一份，这对迁移之外的场景同样有益：在专辑页直接打分、从未播放过的曲目也能进入 Rediscover。
- 导入时由 Spotify API 补全，作为一个**独立的后台任务**：
  - 导入本身只写本地数据，瞬间完成，不依赖网络。
  - 补全任务可续跑，经 `SpotifyRateLimitGate` 限流，失败了就下次再试。
  - **只用单曲接口 `GET /v1/tracks/{id}`。** 批量 `GET /v1/tracks?ids=` 已从新建的 Development Mode App 里移除，存量 App 的移除只是延期（见 §6.1），不能依赖。playground 的 `TrackMatchPlayground` 用的就是批量接口，同样受影响。
  - 每次补全顺带拿到 `external_ids.isrc`（2026-03-13 已恢复），为将来的跨 provider 映射留好线索。**本期不用它做任何合并。**
- 被拒的备选：
  - 伪造 `play_history`：污染 Most Played 和 Rediscover 的「90 天未播放」判断。
  - 给 `song_notes` 加 `albumId` 列：要改同步载荷版本，而且同一首曲的每条笔记都得冗余存一份。

### 4.7 导入台账与撤销

新增 `import_ledger`（与 4.6 同一个 migration）：

```text
import_ledger(batchId, source="spotoolfy", sourceKey, targetKind, targetKey, previousJson?, importedAt)
```

- **幂等：** 写入前按 `(source, sourceKey)` 查台账，已经导入过的直接跳过。笔记 ID 本身也是确定性的，这是双保险。
- **撤销：** 「撤销这次导入」按 `batchId` 删除导入的笔记；被覆盖的评分用 `previousJson` 还原。撤销只针对**导入后未被用户编辑过**的行（比较 `updatedAt`），编辑过的视为用户已经接管，保留。
- 台账是本地运维数据，不进云同步。

### 4.8 与云同步、NeoDB 的交互

- 笔记同步的 key 就是笔记 ID（`SongNoteSyncAdapter`），而且 ID 是确定性的。所以「A 机导入 → 同步 → B 机又导入同一文件」不会产生重复：B 机台账里没有记录，但写入时同 ID 的行已经存在，按 LWW 合并。
- 评分同步 key 是 `provider␟songId`，按 Profile 隔离。导入时 `updatedAt` 用的是原始时间，所以任何设备上更新过的评分都会胜出，符合直觉。
- 建议在导入页提示「只需在一台设备上导入，其余设备会自动同步」。
- NeoDB 只同步专辑评分和书评，导入不碰这两样（§4.5），所以不会触发 NeoDB 推送。

### 4.9 输入健壮性清单

- **顶层：** v1 文件没有版本号，按「同时有 `tracks`、`records`、`translations`、`play_contexts` 四个顶层 key」判定为 v1。`translations` 和 `play_contexts` 会被读取但丢弃。
- **类型宽容：** 和 Spotoolfy 自己的导入器（`local_database_provider.dart:563-676`）保持同等宽容。
  - `rating` 可能是字符串（Spotoolfy 会把它当 3）或浮点。
  - `lastPlayedAt` 可能是 int、string 或 double。
  - 未知字段一律忽略。
- **孤儿记录：** `records` 有、`tracks` 里没有的 trackId，用 `"未知曲目"` 占位标题导入，等补全任务修正，不丢弃。
- **规模：** 用流式解析（kotlinx.serialization 的 `decodeFromStream`）。5 万条记录或 50 MB 文件不能 OOM；单事务分批 `insert`，每批 500 条。
- **编码：** 只接受 UTF-8，遇到 BOM 去掉。
- **隐私：**
  - 文件经 SAF 读取，不复制到外部存储，日志里不打印正文。
  - 解析出错时只报告行号和字段名，不回显内容。

---

## 5. 交接通道设计

### 5.1 Yoin 侧：「从 Spotoolfy 导入」

- **入口：** 设置 → 存储（`settings_section_storage`）分组 → 从 Spotoolfy 导入，具体位置由设计定。这是一个 PushPage，有显式返回按钮，系统返回走共享实现。
- 同时注册 `ACTION_SEND` / `ACTION_VIEW`（`application/json`）的 intent filter，用户在 Spotoolfy 里分享时可以直接选 Yoin。导入页在 intent 里收到 URI 后，**仍然先进预览**，绝不静默导入。
- **流程：**
  1. **选文件**：`ActivityResultContracts.OpenDocument(arrayOf("application/json", "text/plain"))`。部分文件管理器会把 `.json` 标成 `text/plain`。
  2. **解析与校验**（后台线程）：识别 v1/v2，产出 `ImportPlan`，包含：
     - 要新增的笔记数、要写入的评分数
     - 与现有评分冲突的数量
     - 跳过的记录数和原因（样例、无效 ID、空记录）
     - 时间范围
  3. **预览页**：
     - 目标 Profile 选择器（只列 Spotify Profile）
     - 计数和冲突摘要
     - 「附带歌词片段」开关
     - v2 文件含 Client ID 时，显示「将预填 Spotify Client ID」（仅当设备上还没有时）
  4. **提交**：一个 `withTransaction` 写入笔记、评分和台账；提交后排队专辑补全任务。
  5. **结果页**：
     - 成功 N 条、跳过 M 条（可展开看原因）
     - 「撤销这次导入」按钮
     - 「去看看我的回忆」
- **代码落点**（按 feature 分包）：
  - `data/migration/spotoolfy/`
    - `SpotoolfyExportParser`
    - `SpotoolfyImportMapper`（纯函数，便于单测）
    - `SpotoolfyImporter`（事务和台账）
  - `ui/settings/importer/`
  - 数据写入经 `YoinRepository` 的新方法，**不要**在 UI 层直接调 DAO。

### 5.2 Spotoolfy 侧：最后一个功能版本（1.4.0「迁移桥」）

Yoin 导入器能吃 v1，所以 1.4.0 是锦上添花，不是阻塞项。建议内容：

1. **v2 导出**：在 v1 四个数组之外加一个信封（格式见 §10.2），带：
   - `format`、`schemaVersion`、`exportedAt`、`appVersion`
   - `spotifyUserId`：导出时调一次 `/v1/me`，供 Yoin 校验目标 Profile 是否是同一个 Spotify 账号，不一致时给出醒目警告
   - `settings.spotifyClientId`、`settings.translationTargetLanguage`
   - **不含** Gemini key
2. **「迁移到 Yoin」入口**（设置 → 数据）：
   - 已安装 Yoin 时，用 `Intent(ACTION_SEND).setPackage("com.gpo.yoin")` 直接投递。
   - 否则走普通分享或保存，并给出 Yoin 封闭测试的加入链接。
3. **启动横幅**（可关闭，一次性）：告知 Spotoolfy 进入维护期、数据如何带走。iOS 和 Web 显示「导出备份」而不是「迁移到 Yoin」。
4. **顺手修两个会影响导出质量的问题：**
   - 首次启动插入的样例数据（`database_helper.dart:634-738`）会混进真实导出。1.4.0 里删掉这些样例行，Yoin 端也要过滤。
   - `AndroidManifest` 引用的 `@xml/locale_config` 不存在。

### 5.3 为什么不选其它通道

| 方案 | 结论 |
|---|---|
| Yoin 直接读 Spotoolfy 的数据库文件 | ❌ 沙盒隔离。两个 App 签名不同，也无法 `sharedUserId` |
| ContentProvider 跨 App 直连 | ❌ 要给 Spotoolfy 加 provider 和权限校验，复杂度高；用户随后会卸载 Spotoolfy，一次性用途不值得 |
| 云端中转（Firebase、Drive） | ❌ 引入账号、隐私和服务端，违背 local-first；Spotoolfy 也没有云端账号 |
| Yoin 云同步格式直接导入 | ❌ 那是设备间的内部协议（`schema:1`），不应该承担外部格式的兼容负担 |
| **JSON 文件 + 分享 intent** | ✅ 两端都已有基础，用户可控、可留档，iOS 和 Web 用户也能用同一个文件 |

---

## 6. 账号与授权迁移

### 6.1 2026 年 Spotify 开发者规则（必须先读）

依据 Spotify 2026-02-06 的公告及后续社区更新：

- Development Mode 下：
  - App 所有者必须有 Premium；
  - **每个开发者只能有 1 个 Client ID**；
  - **每个 App 最多 5 个授权用户**。
- 2026-02-11 起对新建 App 生效，**2026-03-09 起对存量 App 同样生效**。只有「接口裁剪」对存量 App 延期了，时间另行公布。
- 新 App 已经失去批量元数据接口（如 Get Several Tracks）。`external_ids`（ISRC）于 2026-03-13 恢复。Yoin 代码里已经记录了部分裁剪（`SpotifyApiClient.kt:193,449`）。

> 落地前请 owner 在 Spotify Developer Dashboard 核对一次当前规则；本节是 2026-10 的调研结论。

### 6.2 推荐路径：复用同一个 Spotify App

Spotoolfy 用户分两类：

| 用户类型 | 在 Spotoolfy 里的 Client ID | 迁移到 Yoin 的做法 |
|---|---|---|
| A. 自建 App（用户指南让大多数人这样做） | prefs `spotify_client_id` | **在原 App 上追加**：Redirect URI `yoin://auth/spotify/callback`；Android package `com.gpo.yoin` + Yoin 的 SHA-1；勾选 Web API 和 Android。保留 `spotoolfy://callback`，过渡期两个 App 都能用。然后把同一个 Client ID 填进 Yoin，v2 迁移包会自动预填 |
| B. 用内置共享 ID `Env.clientId` | owner 的 App | 这个 App 最多 5 个授权用户，无法承载迁移。引导用户**自建**（这是他们的第 1 个 Client ID，额度允许），走 Yoin 现有的 9 步引导 |

对 Yoin 的改动：

1. `ConnectGuide` 在第 1 步前加一个分支：「我在 Spotoolfy 里已经建过 Spotify App」。
   - 跳过「Create app」，直接进入「Edit settings」。
   - 只做追加 Redirect URI、追加 Android package/SHA-1 两步。
   - 引导里已经能复制本机的包名和**本机安装包的签名 SHA-1**（`ConnectGuide.kt:73-82`）。Play 分发版本读到的是 Play App Signing 证书，正好就是 Spotify 需要的那个，不需要额外说明。
2. 引导文案明确写出「每个 Spotify 开发者账号只能有一个 Client ID，请不要删除旧 App」。删掉旧 App 会同时废掉 Spotoolfy，而且可能拿不回额度。
3. 预填规则与云同步一致：只有设备上**没有**自定义 Client ID、也**没有** Spotify 账号时，才写入 `spotify_config`（`cloud-sync.md:20,31`）。

### 6.3 其它授权与凭据

- **Spotify token：** 不迁。Yoin 里走一次 PKCE 授权即可。Spotoolfy 的 token 本来就没有 refresh token，1 小时过期。
- **Premium 与 Spotify App：** 两边都要求 Premium。Yoin 用 App Remote，**要求本机安装 Spotify App**；Spotoolfy 是 Web API 遥控任意设备。习惯用 Spotoolfy 遥控电脑或音箱的用户要在说明里提示：Yoin 有设备面板（`DevicesSheet`），但主路径是本机 Spotify。
- **Gemini key：** 用户在 Yoin 设置 → Gemini 重新粘贴。
  - **前置修复：** Yoin 目前把 key 明文存在 Room `gemini_config`（`GeminiConfig.kt:9`），而整个 `yoin-database` 会进 Auto Backup 和设备迁移（`data_extraction_rules.xml`）。
  - Spotoolfy 的 key 存在 secure storage。在动员用户把 key 搬过来之前，Yoin 应先把它挪到 AndroidKeyStore 加密的 `noBackupFilesDir` 文件，与 Spotify 凭据同一套 `CredentialsCipher`。否则迁移会让 key 的保护等级**降级**。
- **语言：**
  - Spotoolfy 的 `en`/`zh-CN`/`zh-TW`/`ja` 分别映射到 Yoin 的 `English`/`Simplified Chinese`/`Traditional Chinese`/`Japanese`。
  - **改语言会删除所有 About/Ask 行**（`SettingsViewModel.kt:232-240`），所以导入时只在 `gemini_config` 还不存在时写入目标语言。

---

## 7. 功能对等矩阵与取舍

只把「影响已迁移数据能否被看见、被使用」的缺口列为迁移阻塞项（P0）。其余按用户价值排，并受 YAGNI 约束。

| Spotoolfy 功能 | Yoin 现状 | 建议 |
|---|---|---|
| 笔记 + 评分录入 | ✅ Now Playing 笔记页 + 评分条 | — |
| 按曲查看笔记、同名不同版本 | ✅ 笔记页；`observeCrossProviderNotes` 按标题+艺人匹配 | — |
| **Roam：全部记录列表 + 评分筛选 + 搜索** | ❌ 没有全局笔记列表（只有按曲、按专辑、按回忆） | **P0**。导入几百条笔记后，用户需要一个总览来确认「都过来了」。最小实现是导入结果页上的「查看导入的笔记」列表；完整的「我的笔记」入口另立项，并先按 AGENTS.md 归类返回面 |
| 专辑评分（按曲平均）与专辑海报 | ✅ 剪贴簿 + `ScoreEmblem`；海报 ❌ | 海报 P2 |
| 歌词：网易云 / QQ / LRCLIB、手动搜索、LRC 编辑 | ✅ 已移植，Yoin 还多了华为 | — |
| 歌词选择、复制、海报（4 种配色、15 行） | ✅ 已移植（`LyricsShareCard.kt`、`LyricsSelection.kt`） | — |
| **带歌词写笔记** | ❌ 明确没有移植（`design.md:350`） | **P1**。迁移过来的笔记里带着歌词片段（§4.4），用户会期待能继续这样写 |
| 拿选中歌词问 Gemini / 复制成单行 | ❌ 明确没有移植 | P2，等用户反馈 |
| 翻译：原生 + Gemini，3 种风格 | 原生 + Gemini，单一风格 | P2，风格不迁 |
| 歌曲信息（创作背景等） | ✅ About + Ask Gemini | — |
| 专辑洞察 | ≈ 回忆的 AI 标题和旁白 | — |
| 音乐性格洞察 | ❌ | 不做（YAGNI） |
| Time Machine（那年今日，基于 `/me/tracks` 的 `added_at`） | ❌（Rediscover 是「90 天未播」，概念不同） | P2。Yoin 只同步 200 首 Liked Songs（`SpotifyApiClient.kt:826`），做不了完整的那年今日 |
| 随机回顾卡 | ≈ Rediscover / 回忆 | — |
| Spotify Connect 设备切换 | ✅ `DevicesSheet` | QA 时验证 |
| 播放模式三态循环 | ✅ 已对齐 | — |
| 桌面小组件 | ✅ Glance（播放 + 回忆） | — |
| 日语 / 繁体中文界面 | ❌（en + zh-rCN） | P1，取决于 Spotoolfy 的 ja/zh-TW 用户占比，先看 Play Console 的语言分布 |
| iOS / Web | ❌ | 不做；Spotoolfy 保留导出能力（§8 阶段 6） |

---

## 8. 分阶段路线图

每个阶段都有可验证的退出标准。前一阶段没达标，不进入下一阶段。

### 阶段 0：Yoin 前置清理（约 1 周）

- [ ] **启用 Room schema 导出：** 提交 v30 基线 JSON。这必须在写 30→31 之前完成，否则新 migration 没有可比对的基线（`app/build.gradle.kts:119` 至今只有注释）。
  - 目前没有应用 `androidx.room` Gradle 插件，有两种做法：引入插件并配置 `room { schemaDirectory("$projectDir/schemas") }`；或者给 KSP 传 `room.schemaLocation`。
  - 同时把 `schemas/` 加进 `androidTest` 的 assets，供 `MigrationTestHelper` 使用。
- [ ] Gemini key 改为加密存储，并移出备份（§6.3）。
- [ ] `data_extraction_rules.xml` 引用的是 `profile_active_id.xml`，实际文件是 `yoin_profiles`（`ProfileActiveIdStore.kt:29`），修正。
- [ ] `activity_events` 的 1 万行上限触发器只在 10→11 migration 里创建，全新安装没有（`AppContainer.kt:1108-1131`）。补一个 `RoomDatabase.Callback.onCreate`。导入不写这张表，但同属数据卫生。
- [ ] 更新 AGENTS.md 的过时描述：第 9 行仍说 Spotify 是「planned」，第 89 行仍说凭据用明文 codec。避免后续 agent 按错误前提实现导入。

**退出标准：** CI 绿；`YoinDatabaseMigrationTest` 覆盖 schema 基线。

### 阶段 1：契约与样本（2–3 天）

- [ ] 把 §10 抽成 `docs/integrations/spotoolfy-export-format.md`，定稿 v1 和 v2。
- [ ] 收集 fixtures 并脱敏（曲名、正文替换，结构和边界值保留），放进两个仓库：
  - owner 自己的真实导出
  - 含样例行的全新安装导出
  - 含字符串 rating 的导出
  - 由 Web 版导出的文件
  - 一个 5 万条的合成大文件

**退出标准：** fixtures 和期望映射结果（golden JSON）评审通过。

### 阶段 2：Yoin 导入核心（约 1.5 周）

- [ ] Room 30→31：`track_album_links`、`import_ledger`，外加 migration 测试。
- [ ] 改写两个专辑聚合查询（并集），加单测。
- [ ] `SpotoolfyExportParser`（流式、宽容）、`SpotoolfyImportMapper`（纯函数）、`SpotoolfyImporter`（事务、台账、撤销）。
- [ ] `recordPlay` 和专辑详情加载时顺手写 `track_album_links`。
- [ ] 专辑补全后台任务：单曲接口、限流、可续跑，持久化在 `import_ledger` 或独立游标表。

**退出标准：** §9 的测试矩阵全绿；用 owner 的真实导出跑 `./gradlew test`。

### 阶段 3：Yoin 界面（约 1 周）

- [ ] 导入页、预览、结果、撤销（PushPage，共享返回实现），文案中英双语，通过 `check-hardcoded-strings.sh`。
- [ ] 「查看导入的笔记」最小列表（P0，§7）。
- [ ] `ConnectGuide` 的「已有 Spotify App」分支，以及 Client ID 预填。
- [ ] `ACTION_SEND` / `ACTION_VIEW` 的 intent filter。

**退出标准：** 在 Pixel 实机（含平板）完成端到端演练：导出 → 导入 → 回忆出现 → 撤销 → 重新导入。

### 阶段 4：Spotoolfy 1.4.0 迁移桥（约 3–4 天，可与阶段 3 并行）

- [ ] v2 导出、「迁移到 Yoin」入口、维护期横幅、去掉样例数据（§5.2）。
- [ ] 发布到 Spotoolfy 现有的 Play 封闭测试、TestFlight 和 Web。

**退出标准：** v2 文件通过 Yoin 阶段 1 的 golden 测试。

### 阶段 5：灰度迁移（2–4 周）

1. owner 自己迁移，至少用 1 周。
2. 邀请 Spotoolfy 封闭测试用户加入 Yoin 封闭测试，复用同一个测试者 Google Group 或邮件列表。
   - 这同时满足 Yoin 个人开发者账号「12 名测试者连续 14 天」的上架门槛（`release-0.5-closed-test.md:60`），一举两得。
3. **观测**（Yoin 没有遥测，靠结果页上的可选反馈加人工回访）：
   - 导入成功率；
   - 跳过原因分布；
   - 「已有 Spotify App」分支的成功率；
   - 撤销率。撤销率高，说明映射不符合预期。

**退出标准：** ≥ 90% 的受邀用户完成导入，并且没有数据丢失报告。

### 阶段 6：Spotoolfy 维护期与下架

- **维护期（≥ 12 个月）：**
  - 只修崩溃和 Spotify API 兼容问题，不加功能。
  - 导出功能必须一直可用。
  - Play 商店描述改为「已由 Yoin 接替」。
- **基础设施不能随之下线：**
  - `api.spotoolfy.gojyuplus.com`：Yoin 的网易云歌词依赖它，`NetEaseLyricsProvider.kt:224`。
  - 用户保存在 owner Spotify App 下的授权。
  - 隐私政策页 `spotoolfy-privacy.pages.dev`：已安装用户仍然受它约束。
  - `lyrics.gojyuplus.com` 只服务 Spotoolfy Web 版，可以随 Web 版一起下线。
- **下架判据：** 封闭测试里活跃的 Spotoolfy Android 用户，在 Yoin 正式版上线后 3 个月内降到接近 0；并且 iOS/Web 用户已收到至少两次「导出备份」提醒。
- **下架动作：**
  - 最后一版 Spotoolfy 只保留导出页；
  - Play 上撤销发布但不删除应用记录；
  - 仓库归档，README 指向 Yoin。

---

## 9. 测试与验收

### 9.1 自动化（JUnit / Robolectric，命名 `should_x_when_y`）

| 场景 | 期望 |
|---|---|
| v1 fixture 全量导入 | 笔记数 = 非空 `noteContent` 的记录数 − 无效 ID 数；每曲评分 = 最新记录的映射值 |
| 同一文件导入两次 | 第二次 0 新增，台账不变 |
| A 机导入 → 模拟同步 apply → B 机再导入 | 无重复笔记（ID 确定性） |
| Yoin 已有更新的评分 | 保留 Yoin 的；预览冲突数正确 |
| 样例行 `spotify:track:sample*` | 全部跳过，原因为 `SAMPLE_DATA` |
| 字符串或浮点 `rating`、缺字段、未知字段 | 不崩溃，行为与 Spotoolfy 导入器一致 |
| 歌词片段含 `[00:12.34]` 和 `[ti:xx]` | 时间标签剥离，元数据行丢弃 |
| 只有评分、没有正文的记录 | 只写评分，不生成笔记 |
| 事务中途抛异常 | 全部回滚，台账为空 |
| 撤销：未编辑的行 / 已编辑的行 | 前者删除或还原，后者保留 |
| 5 万条合成文件 | Robolectric 下 < 10 s，内存峰值 < 64 MB |
| 30→31 migration | 现有数据不变，新表可写 |
| 聚合查询并集 | 只有 `track_album_links`、没有 `play_history` 的曲目，也会进入 Rediscover 候选 |

### 9.2 人工 QA（实机）

- 手机和平板各走一遍完整的用户路径（§12）。
- 「已有 Spotify App」分支：同一个 Client ID 下，Spotoolfy 和 Yoin 能同时登录、同时控制播放。
- 导入后打开一张有笔记的专辑：剪贴簿显示笔记；补全完成后，回忆出现该专辑。
- 进程被杀时正在导入：重启后没有半截数据，可以重新导入。

---

## 10. 附录：导出格式

### 10.1 v1（Spotoolfy ≤ 1.3.7，现状，无版本号）

文件名 `spotoolfy_backup_<ISO8601, ':'→'-'>.json`，结构如下：

```json
{
  "tracks":        [{ "trackId": "4uLU6hMCjMI75M1A2tKUQC", "trackName": "…", "artistName": "A, B",
                      "albumName": "…", "albumCoverUrl": "https://…", "lastRecordedAt": 1730000000000,
                      "latestPlayedAt": 1730000000000 }],
  "records":       [{ "id": 12, "trackId": "4uLU6hMCjMI75M1A2tKUQC", "noteContent": "…或 null",
                      "rating": 5, "songTimestampMs": 83000, "recordedAt": 1730000000000,
                      "contextUri": "spotify:album:…", "contextName": "…", "lyricsSnapshot": "…或 null" }],
  "translations":  [{ "trackId": "…", "languageCode": "zh-CN", "style": "faithful",
                      "translatedLyrics": "[L0]…[/L0]…", "generatedAt": 1730000000000 }],
  "play_contexts": [{ "contextUri": "spotify:playlist:…", "contextType": "playlist",
                      "contextName": "…", "imageUrl": "…", "lastPlayedAt": 1730000000000 }]
}
```

### 10.2 v2（Spotoolfy 1.4.0 起，向后兼容 v1 的四个数组）

```json
{
  "format": "spotoolfy-export",
  "schemaVersion": 2,
  "exportedAt": "2026-11-01T12:00:00Z",
  "app": { "name": "spotoolfy", "version": "1.4.0+19", "platform": "android" },
  "account": { "provider": "spotify", "userId": "<from /v1/me>" },
  "settings": {
    "spotifyClientId": "<可选，公开标识>",
    "translationTargetLanguage": "zh-CN"
  },
  "tracks": [], "records": [], "translations": [], "play_contexts": []
}
```

兼容规则：

- Yoin 遇到 `schemaVersion > 2` 时拒绝导入，并提示升级 Yoin。
- 遇到未知字段一律忽略。
- 新增字段只能加，不能改变已有字段的含义。

### 10.3 映射伪代码

```kotlin
fun map(file: SpotoolfyExport, opts: ImportOptions): ImportPlan {
    val tracks = file.tracks.associateBy { normalize(it.trackId) }
    val valid = file.records.mapNotNull { r ->
        val id = normalize(r.trackId) ?: return@mapNotNull skip(r, Reason.INVALID_ID)  // 含 sample 行
        r.copy(trackId = id)
    }
    val notes = valid.filter { !it.noteContent.isNullOrBlank() }.map { r ->
        SongNote(
            id = deterministicNoteId(r),
            profileId = opts.profileId, trackId = r.trackId, provider = PROVIDER_SPOTIFY,
            content = composeContent(r.noteContent!!, r.lyricsSnapshot.takeIf { opts.includeLyrics }),
            createdAt = r.recordedAt, updatedAt = r.recordedAt,
            title = tracks[r.trackId]?.trackName ?: UNKNOWN_TITLE,
            artist = tracks[r.trackId]?.artistName.orEmpty(),
            positionMs = r.songTimestampMs?.takeIf { it > 0 },
        )
    }
    val ratings = valid.filter { it.rating in setOf(0, 3, 5) }
        .groupBy { it.trackId }
        .mapValues { (_, rs) -> rs.maxBy { it.recordedAt } }
        .map { (trackId, r) -> RatingImport(trackId, mapRating(r.rating!!), updatedAt = r.recordedAt) }
    return ImportPlan(notes, ratings, skipped, conflicts = detectRatingConflicts(ratings))
}

fun mapRating(v: Int): Float = when (v) { 5 -> 10f; 3 -> 5f; 0 -> 1f; else -> error("unreachable") }
```

---

## 11. 风险与待决问题

### 11.1 风险

| 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|
| 用户删掉旧 Spotify App 后建不了新的 | 中 | 高：两个 App 都不能用 | 引导文案强调「追加，不要删」；Client ID 预填 |
| owner 的共享 Client ID 超过 5 人 | 高 | 中 | B 类用户引导自建（§6.2） |
| Spotify 继续裁剪存量 App 的接口 | 中 | 中：补全变慢或失败 | 只用单曲接口；补全失败不影响导入本身 |
| 映射不符合预期（如 😐 = 5 分） | 中 | 中 | 撤销 + 重新导入；预览展示映射规则 |
| 回忆页迁移后为空 | 高（如不做 §4.6） | 高：用户觉得数据丢了 | §4.6 列为阶段 2 必做 |
| Gemini key 保护等级降级 | 确定（如不做前置） | 中 | 阶段 0 修复 |
| 网易云代理随 Spotoolfy 一起下线 | 低 | 高：Yoin 歌词退化 | §8 阶段 6 明确保留 |

### 11.2 待 owner 拍板（附推荐）

- **Q1. 评分映射用 1/5/10？** 推荐：是，与 Spotoolfy 的专辑分一致。备选 2/6/9，更保守，给 Yoin 的细粒度评分留出余地。
- **Q2. 😐（可能是默认值）要不要导入？** 推荐：导入。它是 Spotoolfy 界面上展示过的状态；不想要的用户可以在 Yoin 里逐首改。
- **Q3. 「我的笔记」总览做到什么程度？** 推荐：本期只在导入结果页做最小列表，完整入口另立项。
- **Q4. 翻译要不要迁？** 推荐：不迁。如果用户反馈重新翻译太费 Gemini 额度，再做一个按 `(trackId, targetLanguage)` 回填、`model = "spotoolfy-import"` 的兼容层。
- **Q5. 繁体中文和日语要不要提前做？** 推荐：先看 Spotoolfy 的 Play Console 语言分布再定。
- **Q6. Spotoolfy 维护期多长？** 推荐：Yoin 正式版之后 12 个月。

---

## 12. 附录：给用户的迁移指引（草稿）

> 适用于 Android 上的 Spotoolfy 用户。iOS 和网页版用户请先在 Spotoolfy 里「导出数据」并妥善保存文件。

1. **在 Spotoolfy 里导出**：设置 → 数据 → 迁移到 Yoin（旧版本为「导出」）。
2. **安装 Yoin**：通过封闭测试链接加入并安装。
3. **连接 Spotify**：
   - 选「我在 Spotoolfy 里已经建过 Spotify App」。
   - 按提示在**原来的** Spotify App 里**追加**一个 Redirect URI 和一个 Android 包。
   - ⚠️ 不要删除旧 App：每个 Spotify 开发者账号只能有一个 Client ID。
4. **导入**：Yoin 设置 → 存储 → 从 Spotoolfy 导入。选中第 1 步的文件，确认预览后点「导入」。
5. **（可选）重新填写 Gemini API key**：Yoin 设置 → Gemini。

---

## 13. 参考

- Spotify for Developers，*Update on Developer Access and Platform Security*（2026-02-06）：<https://developer.spotify.com/blog/2026-02-06-update-on-developer-access-and-platform-security>
- Spotify，*February 2026 Web API Dev Mode Changes – Migration Guide*：<https://developer.spotify.com/documentation/web-api/tutorials/february-2026-migration-guide>
- Spotify Community，*February 2026 Spotify for Developers update thread*：<https://community.spotify.com/t5/Spotify-for-Developers/February-2026-Spotify-for-Developers-update-thread/td-p/7330564>
- rspotify issue #550（受影响接口与字段清单，含 2026-03-13 恢复 `external_ids`）：<https://github.com/ramsayleung/rspotify/issues/550>
- 仓库内：`docs/cloud-sync.md`、`docs/design.md` §回忆 / §歌词、`docs/release-0.5-closed-test.md`、`AGENTS.md`
