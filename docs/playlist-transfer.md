# 跨服务歌单复制

> 状态：调研 + 第一版实现（2026-10-18，分支 `feat/playlist-transfer`，未合并、未真机验证）。
> 起点是 owner 的想法：「把用户的 Spotify 歌单（名称 + 曲目）存起来，比如存到 Google Drive；用户换到 Apple Music 以后，帮他建出对应的歌单。」

## 1. 一句话结论

**不需要 Google Drive 中转。** Yoin 本来就能在同一台设备上同时登录 Spotify、Apple Music、Subsonic 几个档案（Profile）。复制歌单时直接从当前档案读出歌单，在目标档案里逐首找到对应歌曲，然后在目标服务上新建歌单。整个过程在本机完成，不落盘、不上传。

入口：歌单页右上角 ⋮ →「复制到…」→ 选择目标档案 → 进度 → 结果（「已复制 42 / 45 首到 Apple Music」，下面列出没加进去的歌）。

## 2. 两个服务的授权（App 里已经有的）

| | Spotify | Apple Music |
|---|---|---|
| 授权方式 | OAuth 2.0 Authorization Code + PKCE（`SpotifyOAuthActivity`），用户自带 Client ID | 开发者令牌（JWT，Yoin 的令牌服务签发，`AppleMusicDeveloperTokenProvider`）+ Music User Token（MusicKit Android 授权 AAR） |
| 读歌单 | `playlist-read-private`、`playlist-read-collaborative` | Music User Token，`/v1/me/library/playlists` |
| 写歌单 | `playlist-modify-private`、`playlist-modify-public`（已在 `REQUIRED_SCOPES`，老档案会被要求重连） | Music User Token，`POST /v1/me/library/playlists`、`POST …/{id}/tracks` |
| 查 ISRC | 曲目对象的 `external_ids.isrc`；搜索支持 `isrc:` 过滤 | `GET /v1/catalog/{storefront}/songs?filter[isrc]=…`（只要开发者令牌） |

结论：**不用新增任何授权。** 两个档案都已经登录，复制只用到已有的令牌和 scope。

## 3. 为什么不需要 Drive 中转

- 「先存再建」只在用户**换设备或卸载重装**时才有用。同一台设备上，两个档案可以同时存在（`ProfileManager` 一次只有一个激活的 source，但新增的 `withSourceFor(profileId)` 能为非激活档案临时建一个 source，用完就释放，不切换当前档案）。
- 中转会带来额外负担：要定义格式、版本、过期策略，还要面对 §5 的条款问题（把 Spotify 数据长期存到别处）。
- 云同步 v1（`docs/cloud-sync.md`）的数据是按音乐账号分区的，换了服务的另一个档案本来就看不到 Spotify 档案的分区；要让它看到，就得新开一种跨账号的数据类型。
- **以后真要做跨设备**：最简单的做法是「导出歌单」，生成一个用户自己保存的文件（只包含名称、描述，以及每首歌的标题/艺人/时长/ISRC），而不是 Yoin 自动替用户存。这样数据由用户本人发起、由用户本人持有，也更符合 §5 的条款。现在先不做（YAGNI）。

## 4. 复制什么、怎么匹配

### 4.1 数据范围

| 复制 | 不复制 |
|---|---|
| 歌单名称 | 封面（Apple 的 API 不能设置歌单封面） |
| 描述（有就带上） | 协作者、关注者、公开状态 |
| 曲目**顺序**（重复的歌保留重复） | 每首歌的添加时间、添加人 |
| | 本地文件、播客单集（Spotify 歌单里可能有，读不出可匹配的曲目） |

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
3. **只有「匹配」的歌会加进歌单**，「不确定」和「未找到」都列在结果里，不会加进去。我们宁可漏加，也不加错歌。一首都没匹配上时不会建空歌单。

### 4.3 写入

- 统一接口：`MusicWriteActions.createPlaylistWithTracks(name, description, tracks)`。默认实现是先建歌单，再按每批 100 首追加（Spotify `POST /playlists/{id}/items` 每次最多 100 首）。
- Apple 覆盖了这个实现：建歌单的同一个请求里带上前 100 首（`relationships.tracks`），之后再追加。原因是 Apple 文档写明新资源出现在资料库里可能有延迟，100 首以内的歌单就完全不用发追加请求。
- 中途失败（比如第二批时被限流）时抛出 `PartialPlaylistImportException`。歌单保留，结果页写「中途停止」，并显示实际加进去的数量。

### 4.4 能力位

新增 `Capability.PLAYLIST_IMPORT`，含义是「能接收一份复制过来的歌单」。Subsonic、Spotify、Apple Music 都声明了它。**Apple 没有加 `PLAYLISTS_WRITE`**，否则 Library 的新建按钮、歌单页的重命名/删除/移除曲目都会出现，而 Apple 的 API 不支持这些操作（重命名、删除、移除都没有公开接口）。

## 5. 条款边界（不是法律意见）

### Spotify

- [Developer Policy](https://developer.spotify.com/policy) III「Some prohibited applications」第 9 条原文是 “Do not build an SDA that enables the transfer of data to another service.”，但紧接着有例外：为了让用户转移**自己的个人数据**或**自己歌单的元数据**到另一个服务，是允许的。**用户本人发起、复制自己的歌单名称和曲目信息到自己的另一个服务，看起来正好落在这个例外里。** 这是做这个功能的主要依据。
- 同一份 Policy 的第 11 条：不得复制或替代 Spotify 的核心体验，产品必须提供独立价值。歌单复制不涉及这一条。
- [Developer Terms](https://developer.spotify.com/terms) IV.2.5 写的是不得把 Spotify Content 转给第三方；IV.3.1.1 写的是不得无限期存储、不得汇编 Spotify Content 数据库；V.4 写的是把数据用于其他目的需要用户明确同意。和 Policy III.9 的例外放在一起看：**一次性、用户发起、不落盘**的复制风险最低；Drive 中转（长期存储 + 离开 Spotify 授权范围）风险更高。这也是不做中转的另一个理由。
- 不确定的地方：「歌单元数据」是否包括 ISRC 这类外部 ID，条款没有明说；我们只在本机内存里用它去匹配，不保存。如果以后要正式上架或宣传「从 Spotify 迁移」，建议先发邮件问 Spotify 开发者支持。
- 宣传上不要暗示和 Spotify 有合作或背书（Terms IX.7）。

### Apple

- Apple Developer Program License Agreement §3.3.6(D)「MusicKit」（[协议原文](https://developer.apple.com/support/terms/apple-developer-program-license-agreement/#d336)）：MusicKit API 只能用于帮助用户使用自己的 Apple Music 订阅；不得下载、上传或修改 MusicKit Content；专辑封面和文字只能用于播放或**管理歌单**；用户的歌单、收藏等元数据只能用于已向用户明确说明、且和 App 直接相关的服务。在用户自己的资料库里帮他建歌单，属于「管理歌单」，看起来没问题。
- 不确定的地方：Apple 对「directly relevant」保留单方面解释权。

### 本功能已经守住的边界

- 只在用户明确点「复制到…」并选了目标之后才执行；不在后台运行，也不批量运行。
- 不缓存、不上传源歌单。源歌单每次都重新读取（为了拿到最新的 ISRC），只放在内存里，ViewModel 销毁后就没有了。
- 不复制音频，不复制封面。

## 6. 已知限制

- **Spotify 开发模式（2026-02 起）**：2026-02-11 之后新建的 Client ID 读不了用户既不是创建者也不是协作者的歌单曲目（`GET /playlists/{id}/items` 返回 403）。这类歌单复制时会直接显示「歌单复制失败」。用户自己的歌单不受影响。`external_ids` 在 2 月的变更日志里被列为删除，3 月[已撤回](https://developer.spotify.com/documentation/web-api/references/changes/march-2026)；万一某些账号的响应里还是没有这个字段，就只能走标题搜索。
- **Spotify 搜索**：开发模式下每次最多返回 10 条结果，热门歌曲的搜索结果可能被翻唱版挤掉。Spotify 作为目标时，每首歌最多要发 1 次 ISRC 搜索和 1–2 次标题搜索，几百首的歌单很可能触发限流。遇到 429 时，现有的 `SpotifyRateLimitGate` 会让后续请求等待；但如果单次查询最终还是失败，这首歌会被记为「未找到」，而不是让整次复制失败。所以限流严重时，结果会比实际情况差。
- **Apple 区域商店（storefront）**：ISRC 查询和搜索都限定在账号所在的区域，所以不同区域上架的歌可能搜不到。另外 Apple 有时返回本地化的艺人名（日区返回假名），这种情况要么靠 ISRC，要么只能落进「未找到/不确定」。
- **匹配准确度**：没有用真实歌单测过准确率。规则偏保守（宁可漏加，不加错）。已知容易出错的情况：同名同艺人的不同录音（录音室版和现场版的时长恰好相近）、古典乐（作品名写法差异很大）、标题本身带括号的歌。
- **离开页面**：复制跑在页面的 ViewModel 里，退出歌单页会取消复制。在匹配阶段退出，什么都不会写；在写入阶段退出，可能留下一个只有部分曲目的歌单。关掉面板（不退出页面）复制会继续，完成后在 snackbar 里报告结果。
- **Apple 写入**：创建歌单的请求体按 Apple 文档的示例构造，曲目类型用 `songs`（目录曲）或 `library-songs`（资料库曲）。开发者论坛上有人反映建歌单时遇到 403、`Unable to parse request body`，原因不明。

## 7. 没验证过的（要真机 + 真账号）

- [ ] Spotify → Apple Music 完整流程（建歌单、带曲目、追加超过 100 首的部分、新歌单出现在 Library 的延迟）
- [ ] Apple Music → Spotify（Spotify 的 `isrc:` 搜索的命中率；Apple 资料库歌曲没有 ISRC，只能靠目录关系）
- [ ] 任意方向 → Subsonic（search3 的匹配质量）
- [ ] 目标是 Spotify 档案而当前档案也是 Spotify 时（两个 Spotify source 同时存在，令牌刷新各写各的档案）
- [ ] 平板竖屏、横屏（Wide 详情列）里面板的返回和拖动关闭
- [ ] 匹配准确率：建议用 `playground/track-match`（已有的 Spotify→MusicBrainz ISRC 研究工具）的思路，拿 100 首真实歌单抽样统计

## 8. 代码地图

| 文件 | 作用 |
|---|---|
| `data/transfer/TrackMatcher.kt` | 纯匹配规则（有单元测试） |
| `data/transfer/PlaylistTransfer.kt` | 解析 → 写入，生成报告 |
| `data/transfer/PlaylistCopyService.kt` | 列出可选目标档案，为目标档案临时建 source |
| `data/profile/ProfileManager.withSourceFor` | 拿到非激活档案的 source，用完释放 |
| `data/source/MusicSource.kt` | `PLAYLIST_IMPORT`、`tracksByIsrc`、`createPlaylistWithTracks` |
| `remote/applemusic/AppleMusicApiClient.kt` | `catalogSongsByIsrc`、`createLibraryPlaylist`、`addTracksToLibraryPlaylist`、带 JSON body 的 POST |
| `source/spotify/*` | 解析 `external_ids.isrc`；`tracksByIsrc` 走 `isrc:` 搜索 |
| `ui/detail/PlaylistCopySheet.kt` | 「复制到…」面板（`YoinModalBottomSheet`，返回和拖动关闭由 M3 处理） |

## 9. 待 owner 拍板

1. 「不确定」的歌要不要让用户手动确认后再加进去（结果页上逐首勾选）？现在一律不加。
2. 目标端歌单名要不要加后缀（比如「Mix（来自 Spotify）」）？现在用原名。
3. 要不要做「导出为文件」（§3 末尾）来覆盖换设备的场景？
4. 退出页面是否要让复制继续在后台跑（需要放进 app 级的 scope，可能还需要通知）？
