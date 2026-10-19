# 跨服务歌单：备份与恢复

> 状态：第二版实现（2026-10-19，分支 `feat/playlist-transfer`，未合并、未真机验证）。
> 第一版（10-18）是歌单页上的「复制到…」：当场读、当场写，不落盘。10-19 owner 改了方向：「跨服务复制应该是一个存取服务：歌单可以存到我们的 Google Drive，像备份一样；备份之后可以在任何地方取出来——不在歌单页，而在设置里——取到选定的档案。歌曲有时匹配不上，有时需要用户确认。真正导不出去的东西、备份时间等都要记下来。」

## 1. 一句话结论

**先存、后取。** 歌单页 ⋮ →「备份歌单」把当前歌单整份读出来，存成一份与服务无关的元数据记录（不含音频和封面）。设置 › 存储 ›「歌单备份」里列出所有备份；点开一份 →「恢复」→ 选目标档案（任何能建歌单的档案，包括原来那个）→ 匹配进度 → **逐首审阅** → 「创建歌单」。每次恢复的时间、目标和结果都记在这份备份上。

备份永远先存在本机（Room）；打开云同步时随 Google Drive 走到其他设备（`docs/cloud-sync.md`）。

## 2. 两个服务的授权（App 里已经有的）

| | Spotify | Apple Music |
|---|---|---|
| 授权方式 | OAuth 2.0 Authorization Code + PKCE（`SpotifyOAuthActivity`），用户自带 Client ID | 开发者令牌（JWT，Yoin 的令牌服务签发，`AppleMusicDeveloperTokenProvider`）+ Music User Token（MusicKit Android 授权 AAR） |
| 读歌单 | `playlist-read-private`、`playlist-read-collaborative` | Music User Token，`/v1/me/library/playlists` |
| 写歌单 | `playlist-modify-private`、`playlist-modify-public`（已在 `REQUIRED_SCOPES`，老档案会被要求重连） | Music User Token，`POST /v1/me/library/playlists`、`POST …/{id}/tracks` |
| 查 ISRC | 曲目对象的 `external_ids.isrc`；搜索支持 `isrc:` 过滤 | `GET /v1/catalog/{storefront}/songs?filter[isrc]=…`（只要开发者令牌） |

结论：**不用新增任何授权。**

## 3. 为什么从「直接复制」改成「备份 + 恢复」

第一版认为同一台设备上两个档案可以同时在线，所以不需要中转。owner 10-19 否掉了这个前提，理由是实际场景：

- 用户换服务时往往也在换设备、或先退订再订阅；「先存下来，过几天再取」比「两个账号同时在线」常见。
- 匹配总有不确定的歌，需要用户看过、勾过才写；当场复制的结果页只能事后告诉你漏了什么。
- 「哪些没能导出、为什么」「什么时候备份的」要能回头查。

代价是把 Spotify 歌单元数据长期存下来（§5），owner 知情并选择了这个方向。

## 4. 恢复出什么、怎么匹配

### 4.1 数据范围

| 恢复 | 不恢复 |
|---|---|
| 歌单名称 | 封面（Apple 的 API 不能设置歌单封面） |
| 描述（有就带上） | 协作者、关注者、公开状态 |
| 曲目**顺序**（重复的歌保留重复） | 每首歌的添加时间、添加人 |
| | 本地文件、播客单集、音乐视频、不可用的歌（备份时就记进「没能备份」，§7.1） |

目标端永远**新建**歌单，不合并到已有歌单，也不覆盖。

### 4.2 匹配顺序（`data/transfer/TrackMatcher.kt`、`PlaylistTransfer.kt`）

1. **ISRC**：源曲目带 ISRC（Spotify 的 `external_ids.isrc`、Apple 目录曲的 `isrc`），并且目标服务支持 ISRC 查询（`MusicLibrary.tracksByIsrc`）时，先按 ISRC 查。
   - Apple：`filter[isrc]` 批量查询，每批 25 个。同一个 ISRC 可能对应多首歌（专辑版、合辑版），优先选专辑名相同、时长最接近的那首。时长相差超过 10 秒的会被排除（同一个 ISRC 下挂了不同剪辑版本）。
   - Spotify：没有批量接口，每个 ISRC 单独做一次 `isrc:` 搜索，逐个排队过限流闸门。
   - Subsonic：没有 ISRC（OpenSubsonic 扩展里有 `isrc` 字段，目前没解析），直接走第 2 步。
2. **标题搜索**：用「去掉修饰的标题 + 第一位艺人」搜索；搜不到时只用标题再搜一次。比如源端艺人名是罗马字、目标端是原文的情况。搜索结果按下面的规则打分：
   - 标题、艺人都先归一化：忽略大小写和全/半角，去掉重音符号，去掉 `feat.`/`with` 等客串署名和 `Remastered 2011` 这类版本标签。**保留** `Live`、`Remix`、`Acoustic` 这类会改变录音的词。CJK 字符原样保留。
   - 打分：标题 0.5 + 艺人 0.35 + 时长 0.15。标题相似度低于 0.6 或艺人相似度低于 0.5 直接排除；时长相差超过 10 秒直接排除。
   - 得分 ≥ 0.85 **且**时长相差在 ±3 秒以内 → 匹配；得分在 0.65 到 0.85 之间（或时长相差 4–10 秒）→「不确定」；其余 →「未找到」。
3. 匹配的结果交给恢复审阅（§7.4）：「匹配」默认勾选；「不确定」给出最多 3 个候选，默认**不**勾选，用户勾上或改选别的候选才会写；「未找到」只列出来。一首都没勾时不能创建，不会建空歌单。
4. **Spotify → Spotify**：Spotify 的曲目 ID 在所有账号里通用，备份恢复到任何 Spotify 档案时直接按原 ID 写回，不搜索（`MatchMethod.SourceId`）。其他方向一律走上面两步（Subsonic 的 ID 只在本服务器有效，Apple 资料库 ID 只在本账号有效）。

### 4.3 写入

- 统一接口：`MusicWriteActions.createPlaylistWithTracks(name, description, tracks)`。默认实现是先建歌单，再按每批 100 首追加（Spotify `POST /playlists/{id}/items` 每次最多 100 首）。
- Apple 覆盖了这个实现：建歌单的同一个请求里带上前 100 首（`relationships.tracks`），之后再追加。原因是 Apple 文档写明新资源出现在资料库里可能有延迟，100 首以内的歌单就完全不用发追加请求。
- 中途失败（比如第二批时被限流）时抛出 `PartialPlaylistImportException`。歌单保留，恢复记录里写实际加进去的数量，没写进去的算「跳过」。
- 恢复把「匹配」和「写入」拆成两步：`PlaylistBackupService.match` 只读目标服务，`create` 才写（`PlaylistTransfer.writeTracks`，按备份里的顺序写勾选的曲目）。

### 4.4 能力位

新增 `Capability.PLAYLIST_IMPORT`，含义是「能接收一份复制过来的歌单」。Subsonic、Spotify、Apple Music 都声明了它。**Apple 没有加 `PLAYLISTS_WRITE`**，否则 Library 的新建按钮、歌单页的重命名/删除/移除曲目都会出现，而 Apple 的 API 不支持这些操作（重命名、删除、移除都没有公开接口）。

## 5. 条款边界（不是法律意见）

### Spotify

- [Developer Policy](https://developer.spotify.com/policy) III「Some prohibited applications」第 9 条原文是 “Do not build an SDA that enables the transfer of data to another service.”，但紧接着有例外：为了让用户转移**自己的个人数据**或**自己歌单的元数据**到另一个服务，是允许的。**用户本人发起、复制自己的歌单名称和曲目信息到自己的另一个服务，看起来正好落在这个例外里。** 这是做这个功能的主要依据。
- 同一份 Policy 的第 11 条：不得复制或替代 Spotify 的核心体验，产品必须提供独立价值。歌单复制不涉及这一条。
- [Developer Terms](https://developer.spotify.com/terms) IV.2.5 写的是不得把 Spotify Content 转给第三方；IV.3.1.1 写的是不得无限期存储、不得汇编 Spotify Content 数据库；V.4 写的是把数据用于其他目的需要用户明确同意。和 Policy III.9 的例外放在一起看：**一次性、用户发起、不落盘**的复制风险最低；**长期保存**（本机 Room + 用户自己的 Drive）比一次性复制风险更高，IV.3.1.1 的「不得无限期存储」正是冲着这个来的。owner 10-19 知情后选择了备份方案，理由见 §3。我们能做的是把存的东西压到最少（§7.1）、只在用户本人点「备份歌单」时才存、用户随时能删且删除会同步到所有设备。
- 不确定的地方：「歌单元数据」是否包括 ISRC 这类外部 ID，条款没有明说；备份里**存了** ISRC（它是跨服务匹配最准的依据）。如果以后要正式上架或宣传「从 Spotify 迁移」，建议先发邮件问 Spotify 开发者支持，必要时把 ISRC 从备份里去掉（只会让匹配退回标题搜索）。
- 宣传上不要暗示和 Spotify 有合作或背书（Terms IX.7）。

### Apple

- Apple Developer Program License Agreement §3.3.6(D)「MusicKit」（[协议原文](https://developer.apple.com/support/terms/apple-developer-program-license-agreement/#d336)）：MusicKit API 只能用于帮助用户使用自己的 Apple Music 订阅；不得下载、上传或修改 MusicKit Content；专辑封面和文字只能用于播放或**管理歌单**；用户的歌单、收藏等元数据只能用于已向用户明确说明、且和 App 直接相关的服务。在用户自己的资料库里帮他建歌单，属于「管理歌单」，看起来没问题。
- 不确定的地方：Apple 对「directly relevant」保留单方面解释权。

### 本功能已经守住的边界

- 只在用户明确点「备份歌单」时才存，一次一个歌单；不在后台自动备份，也不批量备份整个资料库。
- 只存元数据（§7.1）：不存音频、不存封面、不存歌词、不存播放次数。
- 存在本机和**用户自己的** Drive 隐藏文件夹（`appDataFolder`），不经过 Yoin 的服务器。
- 用户可以在设置里删除任何一份备份，删除会同步到其他设备。

## 6. 已知限制

- **Spotify 开发模式（2026-02 起）**：2026-02-11 之后新建的 Client ID 读不了用户既不是创建者也不是协作者的歌单曲目（`GET /playlists/{id}/items` 返回 403）。这类歌单备份时会直接显示「歌单备份失败」。用户自己的歌单不受影响。`external_ids` 在 2 月的变更日志里被列为删除，3 月[已撤回](https://developer.spotify.com/documentation/web-api/references/changes/march-2026)；万一某些账号的响应里还是没有这个字段，就只能走标题搜索。
- **Spotify 搜索**：开发模式下每次最多返回 10 条结果，热门歌曲的搜索结果可能被翻唱版挤掉。Spotify 作为目标时，每首歌最多要发 1 次 ISRC 搜索和 1–2 次标题搜索，几百首的歌单很可能触发限流。遇到 429 时，现有的 `SpotifyRateLimitGate` 会让后续请求等待；但如果单次查询最终还是失败，这首歌会被记为「未找到」，而不是让整次复制失败。所以限流严重时，结果会比实际情况差。
- **Apple 区域商店（storefront）**：ISRC 查询和搜索都限定在账号所在的区域，所以不同区域上架的歌可能搜不到。另外 Apple 有时返回本地化的艺人名（日区返回假名），这种情况要么靠 ISRC，要么只能落进「未找到/不确定」。
- **匹配准确度**：没有用真实歌单测过准确率。规则偏保守（宁可漏加，不加错）。已知容易出错的情况：同名同艺人的不同录音（录音室版和现场版的时长恰好相近）、古典乐（作品名写法差异很大）、标题本身带括号的歌。
- **离开页面**：恢复跑在备份页的 ViewModel 里。关掉面板（不退出页面）匹配会继续，审阅也会留着，再点「恢复」回到原处；退出备份页会丢掉没写的审阅。写入一旦开始，就算退出页面也会写完并记进恢复记录（`NonCancellable`）。
- **Spotify 读取上限**：备份读整份歌单（最多 10,000 条，Spotify 自己的上限），歌单页本身仍然只读前 300 条。
- **Apple 写入**：创建歌单的请求体按 Apple 文档的示例构造，曲目类型用 `songs`（目录曲）或 `library-songs`（资料库曲）。开发者论坛上有人反映建歌单时遇到 403、`Unable to parse request body`，原因不明。

## 7. 歌单云备份与恢复

**2026-10-19 owner 改为「云备份」**：一个歌单只有一份备份，备份后一直跟着它——歌单有变化就再备份一次；原歌单在它的服务上消失后，备份停止跟随但保留。记录歌单自己的创建时间（服务给的话）、开始备份、最后备份、最近变化。恢复时没找到的歌可以在目标服务上搜索补上；恢复进度在通知里显示为 Android 16 的 Live Update。

### 7.0 跟随（`PlaylistBackupService.followDue`、`PlaylistBackupFollower`）

- 每次 App 回到前台、账号加载好后过 8 秒，检查**本机有对应账号**、还在跟随、且距上次备份超过 6 小时（`FOLLOW_INTERVAL_MS`）的备份。不在后台跑：没有 WorkManager、没有唤醒。
- 先读服务的歌单列表：列表读到了、里面没有这个歌单 → 记 `sourceGoneAt`，从此不再检查；列表读不到（离线、登录失效）→ 什么都不改，下次再说。
- 歌单还在 → 整份重读：内容或名字变了就更新并记 `lastChangedAt`；没变也更新 `lastBackedUpAt`。
- 单份备份页的「立即备份」随时读一次；歌单页 ⋮「云备份」对已有备份的歌单也是立即再备份（不会再新建一份）。
- `sourceProfileId` 是本机的档案 ID，**不同步**；别的设备能看到、能恢复这份备份，但不跟随（页面显示「在其他设备上跟随」），除非在那台设备上也点一次云备份。

### 7.1 存什么

一份备份（`data/backup/PlaylistBackupModels.kt`，Room 表 `playlist_backups`，DB v32）：

| 字段 | 说明 |
|---|---|
| 歌单名、描述 | 描述没有就空 |
| 来源服务、来源档案名、来源歌单 ID | 档案名是备份那一刻的名字 |
| 时间 | 歌单创建（Subsonic 的 `created`、Apple 资料库歌单的 `dateAdded`；Spotify 不给）、开始备份、最后备份、最近变化、原歌单消失（没消失为空）。一个歌单一份；列表按最近变化倒序 |
| 曲目（按顺序） | 标题、全部署名艺人（Spotify 有多位艺人时全存，其他服务是它给的那一串）、专辑、时长（秒）、ISRC、来源曲目 ID |
| 没能备份的条目 | 在歌单里的位置、能读到的标题/艺人、原因：本地文件、播客单集、音乐视频、不可用（被下架、本账号不可用、没有标题） |
| 恢复记录 | 每次恢复：时间、目标服务和档案名、加了几首、跳过几首（有候选但没勾 / 写入中途失败）、没找到几首、是否没建成 |

**不存**：音频、封面、歌词、添加时间和添加人、协作者、关注数、播放次数。

各服务能告诉我们哪些「没能备份」：

- Spotify（`getPlaylistInFull`）：`is_local` 的本地文件；`type = episode` 的播客单集；条目里没有曲目对象（被下架或本区不可用）。本地文件的艺人/专辑 ID 是 `null`，之前会让整页解码失败，顺手修了。
- Apple Music：歌单里 `music-videos` / `library-music-videos` 类型的条目。
- Subsonic：歌单只放歌曲，没有这类条目。

### 7.2 同步

新的同步类型 `playlist_backup`（`data/sync/adapters/PlaylistBackupSyncAdapter.kt`）：

- **全局**（不按音乐账号分区）：备份本来就要恢复到别的账号；而且 Apple Music 账号没有稳定的同步 ID。
- **artifacts 分片文件**：一份备份可能几百首歌；歌单变了才更新（最多每 6 小时一次），恢复记录会追加。
- **删除会同步**（墓碑）。冲突策略 `LWW_LIVE_WINS`：后写的为准（极少数情况下会丢一条恢复记录）；一台删了、另一台同时恢复了它，**保留备份**——备份删了就没法重做（原歌单可能已经没了），而多留一份用户随时能再删。
- `content`、`restores` 按 Room 里存的 JSON 原文传，新版本加的字段经过旧版本设备也不会丢。

不开云同步时备份照样能用，只在本机。

### 7.3 页面与返回

- 歌单页 ⋮ 「云备份」：读完存好并开始跟随，snackbar 报数（「已备份 45 首」或「已备份 42 / 45 首」）。
- 设置 › 存储 ›「歌单云备份」：`SettingsFeature.PlaylistBackups`，和「滚动边缘」一样是 `SettingsFeatureActivity` 里的一页（平板分栏里开在右栏）。
- 单份备份：`PlaylistBackupActivity`，Pattern A（原生跨 Activity 返回，没有返回代码），右上 ⋮ 里是删除。
- 单份备份页顶部是服务色的状态卡：账号、跟随状态（跟随变化中 / 原歌单已不在 / 在其他设备上跟随）、四个时间方块；下面「恢复」和「立即备份」两个按钮。
- 恢复：备份页上的 `YoinModalBottomSheet`，一个面板走完「选档案 → 匹配进度（确定进度的 Expressive LoadingIndicator + 波浪进度条）→ 审阅 → 写入 → 结果」；搜索是叠在上面的第二个 sheet。返回和拖动关闭由共享的 sheet 返回桥处理。
- 进度通知（`RestoreLiveUpdate`）：匹配和写入期间一条常驻通知，Android 16+ 用 `ProgressStyle`，并请求升级为 Live Update（`android.requestPromotedOngoing` extra，`setShortCriticalText` 给状态栏胶囊 "12/45"；manifest 声明 `POST_PROMOTED_NOTIFICATIONS`）。匹配完就撤掉（审阅在 App 里），写完换成可划掉的结果。2 分钟没有更新的常驻通知自动消失（进程中途死了也不会卡住）。第一次恢复时才请求通知权限；不给就不显示，恢复照常。

### 7.4 恢复审阅（`data/transfer/RestoreReview.kt`）

- 顶部一条按比例分段的条（已匹配 / 已选 / 待确认 / 未找到，随勾选用空间弹簧变化），下面一组相连的切换按钮筛选「全部 / 待确认 / 未找到」。
- 每行左边的标记：勾上 = 实心 9 边曲奇 + 对勾，没勾 = 空圆，两者之间形状变形。
- 「匹配」默认勾选，可以取消；「不确定」默认不勾，点开看最多 3 个候选（标题、艺人、专辑、时长），选一个就算勾上；「未找到」有「搜索」按钮，在**目标服务**上搜（从 Apple Music 恢复到 Spotify 就是搜 Spotify），选中的结果成为这首歌，状态变成「已选」并勾上。待确认的候选下面也有同样的搜索入口。
- 「创建歌单」按备份里的顺序写入勾选的曲目，歌单名和描述沿用备份。

## 8. 没验证过的（要真机 + 真账号）

- [ ] Spotify 备份 → Apple Music 恢复完整流程（建歌单、带曲目、追加超过 100 首的部分、新歌单出现在 Library 的延迟）
- [ ] Apple Music 备份 → Spotify 恢复（Spotify 的 `isrc:` 搜索的命中率；Apple 资料库歌曲没有 ISRC，只能靠目录关系）
- [ ] 任意方向 → Subsonic（search3 的匹配质量）
- [ ] Spotify 备份 → 另一个 Spotify 档案（按原 ID 写回）
- [ ] 含本地文件 / 播客单集的 Spotify 歌单，「没能备份」是否列全
- [ ] **云同步**：备份到 Drive、在第二台设备出现、删除同步、并发恢复记录——需要两台设备和一个 Google 账号，没跑过
- [ ] 平板竖屏、横屏：设置分栏里打开备份页、恢复面板的返回和拖动关闭
- [ ] 匹配准确率：建议用 `playground/track-match`（已有的 Spotify→MusicBrainz ISRC 研究工具）的思路，拿 100 首真实歌单抽样统计

## 9. 代码地图

| 文件 | 作用 |
|---|---|
| `data/transfer/TrackMatcher.kt` | 纯匹配规则（有单元测试） |
| `data/transfer/PlaylistTransfer.kt` | `resolve`（匹配）和 `writeTracks`（写入）两步 |
| `data/transfer/RestoreReview.kt` | 审阅规则：默认勾选、改选候选、按顺序取要写的曲目 |
| `data/backup/PlaylistBackupModels.kt` | 备份 JSON 格式、`PlaylistSnapshots`（歌单 → 备份、备份曲目 → 匹配用的 Track） |
| `data/backup/PlaylistBackupStore.kt` | Room 读写、追加恢复记录 |
| `data/backup/PlaylistBackupService.kt` | 备份、列出可恢复的档案、匹配、创建并记录 |
| `data/local/PlaylistBackup.kt` / `PlaylistBackupDao.kt` | `playlist_backups` 表（`MIGRATION_31_32`） |
| `data/sync/adapters/PlaylistBackupSyncAdapter.kt` | 云同步 `playlist_backup` |
| `data/source/MusicSource.kt` | `PLAYLIST_IMPORT`、`tracksByIsrc`、`createPlaylistWithTracks`、`getPlaylistInFull` |
| `data/model/Playlist.kt` | `Playlist.unreadable`（没能读成曲目的条目） |
| `data/profile/ProfileManager.withSourceFor` | 拿到非激活档案的 source，用完释放 |
| `remote/applemusic/AppleMusicApiClient.kt` | `catalogSongsByIsrc`、`createLibraryPlaylist`、`addTracksToLibraryPlaylist`、带 JSON body 的 POST |
| `source/spotify/*` | 解析 `external_ids.isrc`、全部艺人、本地文件/单集；`tracksByIsrc` 走 `isrc:` 搜索 |
| `ui/detail/PlaylistDetailViewModel.backUp` | 歌单页「备份歌单」 |
| `ui/settings/backup/*` | 备份列表、单份备份页、恢复面板 |

## 10. 待 owner 拍板

1. 恢复出来的歌单名要不要加后缀（比如「Mix（来自 Spotify）」）？现在用原名。
2. 同一个歌单备份很多次时，要不要自动只留最近 N 份？现在全留，手动删。
3. 歌单页备份成功的 snackbar 要不要带「查看」按钮直接跳到这份备份？现在没有。
4. 「跳过」现在把「有候选但没勾」和「写入中途失败」算在一起；要不要分开记？
5. 第一版的「复制到…」已删掉（引擎保留）；需要的话可以在备份页之外再给一个「备份并马上恢复」的快捷入口。
