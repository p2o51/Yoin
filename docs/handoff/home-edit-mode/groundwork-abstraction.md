# Yoin 音乐服务抽象层与主页数据可行性报告

只读调研，读的是 2026-10-03 当时的工作树，没有改任何文件。基准目录是 `app/src/main/java/com/gpo/yoin/`，下文路径都相对于它。

关键文件（绝对路径）：
- `app/src/main/java/com/gpo/yoin/data/source/MusicSource.kt`
- `app/src/main/java/com/gpo/yoin/data/source/ServiceFeatures.kt`
- `app/src/main/java/com/gpo/yoin/data/source/subsonic/SubsonicMusicSource.kt`
- `app/src/main/java/com/gpo/yoin/data/source/spotify/SpotifyMusicSource.kt`
- `app/src/main/java/com/gpo/yoin/data/source/spotify/SpotifyApiClient.kt`
- `app/src/main/java/com/gpo/yoin/data/source/spotify/SpotifyLibrarySyncCoordinator.kt`
- `app/src/main/java/com/gpo/yoin/data/source/applemusic/AppleMusicSource.kt`
- `app/src/main/java/com/gpo/yoin/data/remote/applemusic/AppleMusicApiClient.kt`
- `app/src/main/java/com/gpo/yoin/data/repository/YoinRepository.kt`（行很长，读的时候用 `grep -a`）
- `app/src/main/java/com/gpo/yoin/data/profile/ProfileManager.kt`
- `app/src/main/java/com/gpo/yoin/data/local/`（DAO 和实体）
- `app/src/main/java/com/gpo/yoin/ui/home/HomeViewModel.kt`、`HomeSection.kt`

---

## 0. 结论速览

1. **抽象层**：`MusicSource` 分 4 个接口切片，配一个 9 项的 `Capability` 枚举。但对 Spotify 的五个库列表方法（`getAlbumList`、`getArtists`、`getStarred`、`getRandomSongs`、`getPlaylists`），Repository 不走 source，直接读 Room 缓存（`SpotifyLibrarySyncCoordinator`，TTL 1 小时）。
2. **Apple Music 在主页上几乎没数据**：
   - `getStarred()` 返回空，`getRandomSongs()` 返回空。
   - `getAlbumList` 只认 `"newest"`，其他 type 一律忽略。
   - 结果：主页 "Recently Added" 永远是空的；Jump Back In 没有歌曲卡；所谓 "random" 专辑其实每次都是同一批前 18 张。
3. **Subsonic 主页的 "Recently Added" 实际显示的是"最近收藏"**。它读的是 `starred` 时间戳（`SubsonicMappers.kt:48-51, 67-70`），服务端真正的"新入库"（`newest`）只在 Library 页用了。另外 Yoin 从不调用 `scrobble.view`，所以服务端的 `recent` 和 `frequent` 列表能不能反映在 Yoin 里的播放，要看服务器怎么实现。
4. **Spotify**：
   - 每类库集合只取前 200 条（`DEFAULT_COLLECTION_LIMIT=200`，`SpotifyApiClient.kt:639`）。
   - 没申请 `user-top-read` 权限。
   - 2026 年 2 月开发模式改动删掉了 artist top-tracks、related-artists、new-releases。
   - 远端能拿到的"历史"只有 `/me/player/recently-played`，最多 50 条。
5. **三家通用的主页数据只能靠本地 Room**：`play_history`、`activity_events`、`local_ratings`、`album_ratings`、`song_notes`、`album_notes`、`home_grid_pool_cache`。限制有：
   - 还没有按时间窗口的聚合查询。
   - `play_history` 在**开始播放**时就记一条，`completedPercent` 恒为 0，而且只存艺人名，没有 artistId。
   - 这张表从不清理，这一点对"那年今日"之类的功能有利。
6. **Capability 的漂移风险**：
   - `LYRICS` 和 `SEARCH` 声明了，但 UI 从来不读。
   - Apple 的 capability 集合在 `AppleMusicSource.kt:50-56` 里内联写了一遍，没有引用 `ServiceFeatureCatalog`，以后两边可能对不上。
   - 不少 UI 是按"实体所属 provider"读静态 catalog，不是读当前激活的 source。

---

## 1. 分层图

```
Composable (HomeScreen / LibraryScreen / NowPlayingScreen / *DetailScreen)
   │  只读 UiState；不碰 AppContainer
   ▼
ViewModel (HomeViewModel, LibraryViewModel, NowPlayingViewModel, AlbumDetailViewModel …)
   │  能力门控（两条路，见下）
   ▼
YoinRepository  (data/repository/YoinRepository.kt:104)
   │  构造参数：activeSource: StateFlow<MusicSource?>, activeProfileId: StateFlow<String?>
   │  (AppContainer.kt:422-424 注入 profileManager.activeSource / activeProfileId)
   │  ├─ capabilities: Flow<Set<Capability>>        :383   (= activeSource.capabilities)
   │  ├─ currentCapabilities()                      :387
   │  ├─ activeProviderId / currentProviderId()     :396 / :400
   │  ├─ requireSource()                            :406
   │  ├─ Spotify 分支：isSpotifyActive() → SpotifyLibrarySyncCoordinator（Room 缓存）
   │  │     getAlbumList :533 / getArtists :602 / getStarred :681 / getRandomSongs :694 / getPlaylists :817
   │  ├─ 详情缓存：内存 LRU + DetailCacheStore 磁盘缓存（getAlbum :553, getArtist :620, getPlaylist :856）
   │  └─ 本地 Room：play_history / activity_events / ratings / notes / grid pools / lyrics_cache
   ▼
ProfileManager (data/profile/ProfileManager.kt:43)
   │  activeSource: StateFlow<MusicSource?> :67 ; switchTo() :110 → buildSource() :354
   │  （切换时 ping 超时 8s，prime 超时 8s，然后 dispose 旧 source）
   ▼
MusicSource (data/source/MusicSource.kt:26)
   │  id, capabilities, resolveCoverUrl(), prime(), dispose()
   ├─ library():      MusicLibrary       :74
   ├─ metadata():     MusicMetadata      :98
   ├─ writeActions(): MusicWriteActions  :102
   └─ playback():     MusicPlayback      :175
   ▼
各 provider 实现 + 客户端
   ├─ SubsonicMusicSource  → remote/SubsonicApi.kt（Retrofit，19 个端点）
   ├─ SpotifyMusicSource   → SpotifyApiClient（OkHttp + OAuth 刷新 + SpotifyRateLimitGate）
   │                          另有 SpotifyAppRemotePlayer（播放）
   └─ AppleMusicSource     → AppleMusicApiClient（开发者 token + Music-User-Token）
                              另有 MusicKit SDK（播放）
```

**`Capability` 和 `ServiceFeatureCatalog` 在哪里接入**
- `Capability` 枚举定义在 `MusicSource.kt:62-72`，每个 source 通过 `override val capabilities` 声明自己支持哪些。
- `ServiceFeatureCatalog`（`ServiceFeatures.kt:37`）是静态的、和账号无关的"provider → 能力集合 + 用户可见功能说明"。
  - Subsonic（`SubsonicMusicSource.kt:47`）和 Spotify（`SpotifyMusicSource.kt:59`）的 capabilities 直接引用 catalog。
  - Apple 是**内联重复声明**（`AppleMusicSource.kt:50-56`）。

**UI 读能力的两条路**
1. **读当前激活的 source（动态）**：`repository.capabilities`（Flow）或 `repository.currentCapabilities()`（快照）。
   - `HomeViewModel.kt:547`
   - `LibraryViewModel.kt:167-262, 297, 607-615, 914-917`
   - `NowPlayingViewModel.kt:134, 786`
   - `NowPlayingOverlayHost.kt:731`
2. **按实体所属 provider 读静态 catalog**：`ServiceFeatureCatalog.forProvider(id)`。
   - `NowPlayingViewModel.kt:410` 写进 `NowPlayingUiState.serviceFeatures`（定义在 `NowPlayingUiState.kt:75`），再由 `NowPlayingScreen.kt:1457/2186/2644/3216` 按 `LIBRARY_ADD` 门控。
   - `AlbumDetailViewModel.kt:185`
   - `AlbumDetailComponents.kt:535`
   - `ArtistDetailScreen.kt:171`（`supportsFollow`）
   - AGENTS.md Phase C 设想的"通过 PlaybackState 传 Capability"没有做，NowPlaying 走的是 UiState 里的 catalog。

**不在接口里的 provider 专属方法（向下转型）**
- **Spotify**：`startContextPlayback`、`listDevices`、`transferPlayback`、`getRecentlyPlayed`、`resolvePlaylistContextOffset`、`warmLibraryCaches`、`invalidateLibraryCaches`（`SpotifyMusicSource.kt:321-393`）。
  - Repository 在 `:424, :442, :1639, :1660, :2311` 有 `as? SpotifyMusicSource`。
  - `PlaybackManager.kt:849`、`SpotifyLibrarySyncCoordinator.kt:167` 也会转型。
- **Apple**：`refreshPlaybackToken`、`playbackDeveloperToken`，在 `player/applemusic/AppleMusicPlaybackService.kt:52` 转型使用。
- **Subsonic**：`buildCoverArtUrl`、`buildStreamUrl`、`rawCredentials`（`SubsonicMusicSource.kt:225-232`）。

---

## 2. 每个切片的方法 × provider 实际实现情况（逐个读过函数体）

图例：**实做** = 真的调用了远端；**部分** = 有实现但语义打了折；**桩** = 写死返回空、null 或 failure；**默认** = 用接口里的 default 实现。

### MusicLibrary（`MusicSource.kt:74-96`）

| 方法 | Subsonic | Spotify | Apple Music |
|---|---|---|---|
| `ping()` | 实做：`ping.view`（:50） | 实做：`GET /v1/me`（:68） | 实做：`GET /v1/me/storefront`（:72） |
| `getAlbumList(type,size,offset)` | 实做：type 原样传给 `getAlbumList2`（:55）。服务端支持 random/newest/highest/frequent/recent/alphabeticalBy*/starred。**byYear/byGenre 用不了**，因为 `SubsonicApi.kt:12-17` 没有 fromYear/toYear/genre 参数 | 部分：source 里只对 alphabeticalByName、recent、random 排序，其他 type 原序返回（:73-86）。**实际调用路径**是 Repository 绕过 source 读 Room：`newest` 和 `recent` 都按 addedAt，`random` 是 shuffle（`YoinRepository.kt:533-551`） | 部分：只有 `"newest"` 走 `/me/library/recently-added`（只保留专辑）。其他 type 一律返回库专辑的 Apple 默认顺序，**type 被忽略**（:105-127） |
| `getAlbum(id)` | 实做（:59） | 实做：album + album tracks 两次请求，404 返回 null（:88-102） | 实做：库专辑解析成完整的目录专辑，并标记哪些曲目在库里；没有目录匹配的导入专辑保留库内曲目表（:136-188） |
| `getArtists()` | 实做：`getArtists.view`（:62） | 实做（推导型）：关注的艺人加上从已存专辑和曲目里提取的艺人（:404-459）。Repository 读 Room（:602-618） | 实做：`/me/library/artists` 全部分页，按首字母分组（:196-198） |
| `getArtist(id)` | 实做（:65） | 实做：artist 加 `getArtistAlbums`（album/single/compilation，每页 10，总上限 60）（:107-123） | 实做：artist 加 `/albums` 关系（:200-206） |
| `getPlaylists()` | 实做，canWrite 按 owner 判断（:68） | 实做，canWrite = 自己是 owner（:125-130）。Repository 读 Room | 实做，**只读**，canWrite=false（:208, :416） |
| `getPlaylist(id)` | 实做（:72） | 实做，同时记录原始 offset，供带上下文播放（:132-150） | 实做（:209-220） |
| `getStarred()` | 实做：`getStarred2`（:75） | 实做：已存曲目 + 已存专辑 + 关注艺人，每类最多 200 条（:152-169）。Repository 读 Room | **桩**：返回 `Starred()`（:343） |
| `getRandomSongs(size)` | 实做：`getRandomSongs.view`（:78） | 部分：把已存曲目 shuffle 一下（:171-178）。Repository 从 Room 取后 shuffle（:694-705） | **桩**：返回 `emptyList()`（:344） |
| `search(query)` | 实做：`search3`（:82） | 实做：目录搜索 `/v1/search`，每类最多 10 条（`SpotifyApiClient.kt:641`） | 实做：目录搜索，每类 25 条（:222-239） |
| `searchLibrary(query)` | 默认，等于 `search`（服务器的库就是它的目录） | 默认，等于 **目录** search。Library UI 改用 `getSpotifyLocalSearchSnapshot`（`LibraryViewModel.kt:651`） | 实做：`/me/library/search`（:241-257） |
| `getLibrarySongs(size,offset)` | 默认：抛 UnsupportedOperationException | 默认：抛 UnsupportedOperationException | 实做：`/me/library/songs` 分页（:259-279） |

### MusicMetadata（`:98-100`）

| 方法 | Subsonic | Spotify | Apple Music |
|---|---|---|---|
| `getLyrics(trackId)` | 实做：`getLyricsBySongId`（OpenSubsonic 扩展）（:87） | **桩**：返回 `null`（:189） | **桩**：返回 `null`（:345） |

Repository 的兜底逻辑在 `getLoadedLyrics`（`YoinRepository.kt:1680-1727`）：
- Subsonic **只用服务端歌词**，没有就返回 null，也不读 `lyrics_cache`。
- 非 Subsonic 先查 `lyrics_cache`（TTL 30 天），没命中再走 `LyricsProviderRegistry`（LRCLIB、网易云、QQ）。
- 手动搜索和应用歌词（:1729-1832）对三家都可用。但 Subsonic 手动选的歌词会写进 `lyrics_cache`，下次自动加载时却**不会读**它（见附录）。

### MusicWriteActions（`:102-173`）

| 方法 | Subsonic | Spotify | Apple Music |
|---|---|---|---|
| `libraryMembership` | 默认 failure | 默认 failure | 实做：查 catalog→library 关系，404 时再用公开目录确认这首歌存在（:281-288；`AppleMusicApiClient.kt:94-125`） |
| `addToLibrary` | 默认 failure | 默认 failure | 实做：POST 返回 202 后按 0/0.5/1/2/4s 轮询确认，确认不了返回 Pending（:290-319） |
| `setFavorite` | 实做：star/unstar 的 `id` 参数（:93） | 实做：`PUT/DELETE /me/library`，参数 `spotify:track:` URI（:193-204） | 失败："Manage this in Apple Music."（:349） |
| `setArtistFollowed` | 实做：star 的 `artistId` 参数（:106） | 实做：`/me/library`，参数 `spotify:artist:` URI（:206；`SpotifyApiClient.kt:273`） | 默认转到 setFavorite，结果是失败 |
| `setRating` | 实做：`setRating.view`，限制在 0-5（:116） | 失败（:214） | 失败（:350） |
| `createPlaylist` / `renamePlaylist` / `deletePlaylist` | 实做；描述通过 updatePlaylist 的 comment 写入（:122-157） | 实做；删除 = 取消关注（:217-244） | 失败（:351-353） |
| `addTracksToPlaylist` / `removeTracksFromPlaylist` | 实做；按 index 删除，没有 snapshot（:159-192） | 实做；有 snapshot id（:246-284） | 失败（:354-360） |

### MusicPlayback（`:175-177`）

| 方法 | Subsonic | Spotify | Apple Music |
|---|---|---|---|
| `handleFor(track)` | `DirectStream`（stream.view 的 URL）（:196） | `ExternalController(SPOTIFY_APP_REMOTE, "spotify:track:…")`（:288） | `ExternalController(APPLE_MUSIC_KIT, catalogId)`；没有目录版本的导入曲目会直接抛错（:361-366） |

### MusicSource 本身的方法

| 方法 | Subsonic | Spotify | Apple Music |
|---|---|---|---|
| `prime()` | 预取 recent 专辑 + artists（:213） | 同上（:302） | **默认 no-op** |
| `resolveCoverUrl` | `SourceRelative` 转成 getCoverArt URL（:218） | 只处理 URL（:350） | 只处理 URL，固定替换成 600×600（:76, :390-392） |

---

## 3. Capability 集合和 ServiceFeatureCatalog

### Capability × provider × UI 在哪里读

| Capability | Subsonic | Spotify | Apple | UI 读取点 |
|---|---|---|---|---|
| FAVORITES | 有 | 有 | 无 | `LibraryViewModel.kt:261`（Favorites 标签；Spotify 的由 `ServiceFeatures.favoritesAreLibrary` 隐藏，它的收藏就是 Songs / Albums / Artists 三个标签）；`supportsFavorites` 用于 `ArtistDetailScreen.kt:171` 的关注按钮 |
| SEARCH | 有 | 有 | 有 | **没人读** |
| CATALOG_SEARCH | 无 | 有 | 有 | `LibraryViewModel.kt:607-615, 914` |
| LIBRARY_ADD | 无 | 无 | 有 | NowPlaying VM 和 Screen；`LibraryViewModel.kt:194/221/246`；Repository 守卫 `:343` |
| LIBRARY_SONGS | 无 | 有（2026-10-10 起 = Liked Songs，Repository 读同步缓存） | 有 | `LibraryViewModel.kt:262, 297` |
| RANDOM_SONGS | 有 | 有（其实是 shuffle 已存曲目） | 无 | `HomeViewModel.kt:547`；`LibraryViewModel.kt:262, 917` |
| PLAYLISTS_READ | 有 | 有 | 有 | `LibraryViewModel.kt:260` |
| PLAYLISTS_WRITE | 有 | 有 | 无 | `LibraryViewModel.kt:192/219/244`；`NowPlayingOverlayHost.kt:731`；`AddToPlaylistSheet` |
| LYRICS | 有 | 无 | 无 | **没人读**（歌词三家都走 Repository） |

注释里写明 5 星评分**不算** capability：评分先存本地，三家都显示控件，只有 Subsonic 会推到服务器（`MusicSource.kt:57-61`）。

### 用户可见功能表（`ServiceFeatures.kt:38-209`）

| 功能 | Subsonic | Spotify | Apple Music |
|---|---|---|---|
| 播放 | Available（在 Yoin 内串流，支持后台和系统控件） | Limited（需要 Spotify App + Premium，远程控制） | Limited（MusicKit 播目录曲目；导入曲目要去 Apple Music 播） |
| 收藏 / 已存 | Available（star） | Available（Liked Songs） | 无此项；"Add to library" 是 Available，**和收藏是两个概念** |
| 库与搜索 | — | — | Available（库 + 目录搜索） |
| 歌单 | Limited（能编辑可写歌单，具体看服务器） | Limited（能编辑自己的；删除 = 取消关注） | Limited（只读，编辑去 Apple Music） |
| 歌词 | Limited（服务端歌词 + 额外歌词源） | By Yoin（第三方歌词源） | By Yoin |
| 评分与笔记 | By Yoin（评分额外同步到服务器） | By Yoin（不同步） | By Yoin |
| 音质 | Limited（服务器可能转码） | Limited（由 Spotify 管理） | Not verified |
| 设备与缓存 | Limited（Cast + 自动缓存，不是离线库） | Limited（用 Spotify Connect） | Not in Yoin yet |

另外：`local` 条目是 "Not in Yoin yet"（`integrated=false`，没有 capability）；`forProvider()` 遇到未知 provider 返回 UNVERIFIED 兜底（:212-221）。`ProviderKind` 里 LOCAL 的 `isAvailable=false`（`ProviderKind.kt:20`）。

---

## 4. 主页能用的本地 Room 信号（DB v28，`YoinDatabase.kt:35`）

这些信号对所有 provider 都适用，但都**按 `profileId` + `provider` 隔离**。Repository 的写法是 `combine(activeSource, activeProfileId)`，不会跨 profile 或跨 provider 混用。

| 表 | 键 / 关键列 | 现有 DAO 查询 | Repository 入口 | 主页可用性和局限 |
|---|---|---|---|---|
| `play_history` | 自增 id；songId, profileId, provider, title, **artist（只有名字）**, album, albumId, coverArtId, playedAt, durationMs, completedPercent；索引 (profileId, provider, playedAt)（`PlayHistory.kt:10-34`） | `getRecentHistory`（Flow）、`getMostRecentPlay`、`getAlbumLastPlayed`、`getPlayCount(song)`（Flow，**没人调用**）、`getAlbumAggregates`（count/first/last，**按 lastPlayedAt 排序**）、`getArtistTopSongs`、`getArtistPlayStats`；`deleteOlderThan` **没人调用**，所以从不清理（`PlayHistoryDao.kt`） | `recordPlay` :2250、`getRecentHistory` :2277（**UI 没用**）、`getMostRecentPlay` :2410、`getAlbumLastPlayed` :2416、`getArtistListening` :2426 | **写入语义**：`PlaybackManager.kt:727-747` 在某个新 track id 第一次进入 isPlaying 时写一条，`completedPercent` 恒为 0f，`durationMs` 是整首歌的时长。所以跳过的歌也算一次播放，同一首连续重播不会再记，"收听时长"只是上限。没有 artistId、genre、year。在 Spotify App 里直接播的歌不会进来 |
| `activity_events` | entityType（SONG/ALBUM/ARTIST/PLAYLIST）、actionType（PLAYED/VISITED）、entityId、songId/albumId/artistId、title/subtitle/cover、timestamp；索引 (profileId, provider, timestamp)（`ActivityEvent.kt`） | `getRecentEvents`（Flow）、`getRecentAlbumEvents` | `getRecentActivities` :2287（会折叠成每个实体最新一条）、`getRecentMemoryActivities` :2396、`recordAlbumVisit` :2453、`recordArtistVisit` :2480 | 播放时按 ActivityContext 记成专辑、艺人、歌单或歌曲（:2516-2590）。有 artistId，但 SONG 行的 artistId 取决于 track 本身 |
| `local_ratings` | PK (profileId, songId, provider)；rating 0-10、serverRating、needsSync、updatedAt | `getRating`（Flow）、`getRatings(ids)`、`getRatingsNeedingSync`、`observeChangeStamp` | `setRating` :1084、`getRating` :1104、`getRatings` :1137、`syncPendingRatings` :1149 | **只能按 id 查**，没有"评分最高"的查询；**没有标题和封面**，显示时要和 play_history 或详情缓存 join |
| `album_ratings` | PK (profileId, albumId, provider)；rating 0-10、review、NeoDB 同步标记、updatedAt | `observe`、`get`、`getAll(ids)`、`observePending`、**`getAllForProfile`**、`observeChangeStamp` | `observeAlbumRating` :1359、`setAlbumRating` :1368、`getAlbumRatings` :2445 | 可以全量拿出后在客户端排序（"我评分最高的专辑"），但同样**没有名字和封面** |
| `song_notes` | id；trackId、provider、content、createdAt/updatedAt、title、artist、positionMs（v27） | `observeForTrack`、`observeCrossProvider`（按标题+艺人跨 provider）、`observeKeys`、`getForTracks`、**`getRecent`**、`observeChangeStamp` | `getRecentSongNotes` :1447（主页笔记卡在用） | 自带标题和艺人，可以直接显示 |
| `album_notes` | id；albumId、content、createdAt/updatedAt、albumName、artist | `observeForAlbum`、`observeKeys`、`getForAlbum`、`getNoteCountsForProfile` | :1272-1357 | 自带名字 |
| `home_layout`（v24） | PK profileId；sectionsJson（有序的 `{id, enabled}` 列表） | `getForProfile`（Flow）、`upsert`、`delete` | `data/home/HomeLayoutStore.kt`；目录在 `ui/home/HomeSection.kt` | 新增 section = 加一个 `HomeSection` 常量，`reconcile` 自动兼容 |
| `home_grid_pool_cache` | PK (profileId, provider, itemType, itemId)；title/subtitle/coverArtKey/albumId/durationSec/sortOrder/cachedAt | `getForProfile`、`deleteForProfile`、`insertAll` | `getCachedHomeGridPools` :771、`replaceHomeGridPools` :790 | JBI 的候选池，**TTL 6 小时**（`HomeViewModel.kt:690`），在 `fetchAndPersistGridPools`（:371-405）里预先打乱后落盘 |
| `detail_cache` | PK (profileId, kind, entityId)；json | get/touch/delete/size/`deleteOlderThan` | `DetailCacheStore`（专辑和艺人 7 天内算新鲜，歌单每次都重新校验） | 可以用来离线拿专辑和艺人的完整元数据 |
| `spotify_library_*`（track/album/artist/playlist）+ `_sync_meta` | 每个 profile 一套 | `getFresh*`、`observeTrack`、pending favorite 等（`SpotifyLibraryCacheDao.kt`） | 由 Coordinator 的 read* 方法读 | **只有 Spotify**；TTL 1 小时（`SpotifyLibrarySyncCoordinator.kt:309`）；每类最多 200 条 |
| `spotify_home_album_cache` / `_artist_cache` | 每个 profile | `getFresh*` | `getCachedSpotifyHomeJumpBackIn` :709 / `replace…` :721 | **UI 没有任何调用，是孤立表** |
| `lyrics_cache` / `lyrics_translation_cache` | (trackProvider, trackRawId) | `getFresh`、`upsert` | `getLoadedLyrics` | 能做"歌词卡片" |
| `song_about_entries` | 按规范化后的标题+艺人+专辑做键，**跨 provider** | observe、getCanonical、getAsk、count* | `observeAbout` :2068 等 | Gemini 生成的歌曲背景和问答 |
| `memory_copy_cache`、`external_mappings`（NeoDB）、`cache_metadata`（音频缓存 LRU，**没有 profileId**）、`profiles`、配置表 | — | — | — | 和主页基本无关；`cache_metadata` 可以统计"离线缓存了多少" |

**已有的组合信号**
- `getAlbumMemoryCandidates`（:1419），由 `data/memory/AlbumMemoryCandidateBuilder.kt` 实现，把专辑评分和长评、专辑笔记数、播放聚合（`getAlbumAggregates`）、专辑类活动事件、曲目评分覆盖率、AI 问答数融合成一个专辑候选排名。
- `observeMemorySignalStamp`（:1460）在笔记、曲目评分、专辑评分任一变化时重新发射。

---

## 5. 主页组件数据可行性表

格式：**状态** + 调用方法或说明。"未接线"指 provider 的 API 本身有，但 Yoin 没写客户端。

| 候选数据 | Subsonic | Spotify | Apple Music | 本地（任意 provider） |
|---|---|---|---|---|
| **最近播放** | 部分：`getAlbumList("recent")`。Yoin 从不 scrobble，服务端数据可能不含 Yoin 的播放 | **有**：`SpotifyMusicSource.getRecentlyPlayed(≤50)`，不在接口上；Repository 用 `getSpotifyRecentActivities` :2310。需要 `user-read-recently-played` 权限（不在 REQUIRED 里），没有就回落到本地 | 无：`/v1/me/recent/played` 未接线 | **有**：`getRecentActivities` :2287；`getRecentHistory` :2277 |
| **最近添加** | **有**：`getAlbumList("newest")`（服务端入库时间）。主页现在用的是 `getStarred().addedAt`，也就是收藏时间 | **有**：Repository 的 `getAlbumList("newest"/"recent")` 按 addedAt；`getStarred().tracks/albums.addedAt` | 部分：`getAlbumList("newest")` 走 `/me/library/recently-added`，只有专辑。**主页现在用 `getStarred` 所以恒为空**（`HomeViewModel.kt:165-182`） | 无（没有"加入库"日志） |
| **随机专辑** | **有**：`getAlbumList("random")` | **有**：Repository 对 Room 里的专辑 shuffle（:545） | 部分：type 被忽略，总是同样的前 N 张，主页只是把这 18 张打乱（`HomeViewModel.kt:374`）。改用随机 offset 能修 | 只有缓存池（`home_grid_pool_cache`） |
| **随机歌曲** | **有**：`getRandomSongs` | 部分：已存曲目（最多 200）shuffle（:694-705） | **无**：`getRandomSongs` 返回空；主页回落到 `getStarred().tracks`，也是空（`HomeViewModel.kt:546-552`）。可以用 `getLibrarySongs(size, 随机 offset)` 替代 | 只有缓存池 |
| **最新发行**（按发行日期） | 无：byYear 缺参数；`newest` 是入库时间；可以客户端按 `year` 排序 | 无：`/browse/new-releases` 已在 2026 年 2 月删除 | 无：目录排行和新发行未接线 | 无。所有 mapper 都只保留到**年份**（`SpotifyMappers.kt:242-245`、Apple `releaseDate.take(4)`） |
| **最常播放** | 部分：`getAlbumList("frequent")`（同样受 scrobble 影响）；Song DTO 没解析 `playCount` | 无：`/me/top` 需要 `user-top-read`，没申请（`SpotifyAuthConfig.kt:37-73`），开发模式下是否可用未核实 | 无：heavy-rotation 未接线 | 部分：`getAlbumAggregates` 有 playCount 但按最近播放排序；`getArtistTopSongs` 只限单个艺人；`getPlayCount` 只限单曲。**需要新写全局 Top 查询** |
| **评分最高** | **有**：`getAlbumList("highest")`；`Track.userRating` | 无，只能靠本地 | 无，只能靠本地 | 部分：`albumRatingDao.getAllForProfile` 后客户端排序；`local_ratings` 需要新查询，而且要 join 才有元数据 |
| **收藏的歌曲/专辑/艺人** | **有**：`getStarred`（getStarred2） | **有**：`getStarred` = 已存曲目 + 已存专辑 + 关注艺人，读 Room，每类最多 200 | **无**：`Starred()` 是桩；库成员关系（LIBRARY_ADD）不是收藏 | 只有 Spotify 的 Room 缓存 |
| **歌单** | **有**：`getPlaylists`（可读写） | **有**：`getPlaylists`（读 Room） | **有**：`getPlaylists`（只读） | 缓存池、`detail_cache` |
| **艺人索引** | **有**：`getArtists` | **有**：`getArtists`（关注 + 推导出的艺人，读 Room） | **有**：`getArtists`（库艺人，多数没有头像） | `activity_events` 里的 ARTIST 行 |
| **流派** | 无：`getGenres` 和 byGenre 未接线；`Track.genre`、`Album.genre` 有值 | 无：mapper 写死 `genre=null`；艺人 genres 字段已废弃 | 部分：`Album.genre` 取 genreNames 第一个，没有浏览入口 | 无：`play_history` 不存 genre |
| **歌词** | **有**：`getLyrics`（服务端）+ 手动搜索 | 有（经 Repository）：第三方歌词源 | 有（经 Repository）：第三方歌词源 | `lyrics_cache`，可以做"歌词一句"卡片 |
| **本地评分与笔记** | 有（本地，评分额外推送到服务器） | 有（本地） | 有（本地） | **有**：`getRecentSongNotes`、`album_ratings.getAllForProfile`、`getNoteCountsForProfile`、`getAlbumMemoryCandidates`、`observeMemorySignalStamp` |
| **收听统计**（本周 Top 艺人/专辑、连续天数、时长） | 无 | 无 | 无 | 部分：数据和索引都有，**缺按时间窗口的聚合查询**（`WHERE playedAt >= :since GROUP BY artist/albumId`；按 `date(playedAt/1000,'unixepoch','localtime')` 算连续天数；`SUM(durationMs)`）。注意：时长是上限，艺人只能按名字聚合 |
| **那年今日 / 纪念日** | 无（只有年份） | 无（recently-played 只有 50 条） | 无 | 部分：`play_history` 从不清理；`song_notes.createdAt`；`activity_events.timestamp`；`getAlbumAggregates.firstPlayedAt` 能当"第一次听"。需要新的按月日查询。`album_ratings` 只有 updatedAt，没有 createdAt |
| **相似 / 电台** | 无：`getSimilarSongs2` 未接线（还要服务器配 Last.fm） | 无：recommendations 和 related-artists 对开发模式不可用 | 无：stations 未接线 | 无，只能做近似（同一艺人） |
| **Top Tracks**（艺人） | 无：`getTopSongs` 未接线 | 无：2026 年 2 月删除（`SpotifyApiClient.kt:155-156`） | 无 | **有（单艺人）**：`getArtistListening` → `getArtistTopSongs`（艺人详情页在用） |
| **关注艺人的新发行** | 部分：`getStarred().artists` 再逐个 `getArtist(id).albums`，按年份筛，需要 N 次请求 | 部分：关注艺人再逐个 `getArtist`，每人 1 + 最多 6 页，受限流闸门约束，成本高，只到年份 | 无：没有关注的概念 | 部分：扫 `detail_cache` 里的艺人 JSON |
| **推荐** | 无 | 无（`/recommendations` 和 `/browse` 不可用） | 无：`/v1/me/recommendations` 未接线 | 只有启发式：Memory 候选排名是现成最接近的 |

**主页现在实际用的数据**（`HomeViewModel.kt`）
- **Activities**：本地 `getRecentActivities`；Spotify 优先走 recently-played（:237-250）。
- **Jump Back In**：随机专辑 18 张，加上随机或收藏歌曲 12 首，加上全部歌单，打乱后写进 grid pool（:371-405）。再叠加 Memory 专辑卡（:455）和笔记歌曲卡（:507），组成 3×4 网格。
- **Recently Added**：`getStarred()` 里 7 天内的条目（:165-182）。
- **HomeSection 目录**目前只有这 3 项（`HomeSection.kt:27-44`）。

---

## 6. 新功能的实现步骤

### A. 依赖远端的功能（按 AGENTS.md）

1. **先复用**：现有切片方法能满足就直接用。注意 Subsonic 的 `getAlbumList` 是 type 透传，`frequent`、`highest`、`recent`、`starred` 都已经能用，不用加接口。
2. **加接口方法**：在 `data/source/MusicSource.kt` 对应的切片里加。
   - 推荐沿用现有写法，带 default 实现：读类返回 `throw UnsupportedOperationException`（参考 `getLibrarySongs` :94），写类返回 `Result.failure(UnsupportedOperationException(...))`（参考 `libraryMembership` / `addToLibrary` :103-108）。
   - AGENTS.md 要求每个子类都显式实现，不支持的也要明确返回 failure。
3. **加 `Capability` 常量**（`MusicSource.kt:62-72`），并同步到：
   - `ServiceFeatureCatalog.subsonic/spotify/appleMusic.capabilities`（`ServiceFeatures.kt:44/96/152`）；
   - **`AppleMusicSource.kt:50-56` 的内联集合**。建议顺手改成引用 `ServiceFeatureCatalog.appleMusic.capabilities`，免得两边漂移。
   - 如果用户能看到这个功能，再加或改一行 `ServiceFeature` 说明。
4. **三家逐个实现**：
   - **Subsonic**：在 `remote/SubsonicApi.kt` 加 `@GET`，`SubsonicModels.kt` 加 DTO 字段，`SubsonicMappers.kt` 加映射，然后在 `SubsonicMusicSource` 对应的匿名 object 切片里实现。
   - **Spotify**：在 `SpotifyApiClient` 加方法（请求走 `getDecoded` 或 `collect*Pages`，自动带鉴权重试和限流闸门），`SpotifyDtos.kt`、`SpotifyMappers.kt` 跟着改。
     - 新权限加到 `SpotifyAuthConfig.SCOPES`，并决定要不要放进 `REQUIRED_SCOPES`（放进去会强制老账号重连）。
     - 先对照 2026 年 2 月开发模式删减清单。
     - 如果是库类集合，还要接进 `SpotifyLibrarySyncCoordinator` 和 Room，因为 Repository 对 Spotify 库列表是不走 source 的。
   - **Apple**：`AppleMusicSource` 一个类实现了全部切片。用 `page()`/`all()` 和 `path(kind, id)` 组请求：`library:` 前缀走 `/me/library`，否则走 `/catalog/{storefront}`；`personal=true` 需要 Music-User-Token。
5. **Repository**：在 `YoinRepository` 加入口，调用 `requireSource().<slice>().method()`。按需要选缓存方式：
   - 详情类用 `loadCachedDetail`；
   - 主页池子模仿 `home_grid_pool_cache`；
   - Spotify 走 Room 同步。
6. **ViewModel 门控**：读 `repository.capabilities` 或 `currentCapabilities()`；和具体实体相关的按钮用 `ServiceFeatureCatalog.forProvider(entity.provider)`。Composable 不要直接碰 `AppContainer`。
7. **如果要存远端 id**：
   - 实体要带 `profileId` 和 `provider` 列，组合主键；DAO 同时按 `(id, provider, profileId)` 过滤。
   - DB 版本从 28 往上加，在 `AppContainer` 写 Migration，并把 `YoinDatabaseMigrationTest` 的 `addMigrations` 链补到最新版本（不补的话整套测试会静默变红）。
8. **测试**：API 层用 mock 响应测解析；ViewModel 用 turbine 测 Flow；测试名用 `should_x_when_y` 格式。
9. **如果是主页组件**：在 `HomeSection` 追加一个常量（id 定下后永远不改），在 `HomeEditorialContent` 写渲染。`HomeLayout.reconcile` 会自动兼容已保存的布局。

### B. 纯本地功能

1. **不动** `MusicSource` 和 `Capability`，三家天然都能用。
2. 在对应 DAO 加 `@Query`，永远带 `profileId = :profileId AND provider = :provider`。`play_history` 和 `activity_events` 已有 `(profileId, provider, playedAt/timestamp)` 索引，适合做时间窗口查询。例如：
   - 本周 Top 艺人：`SELECT artist, COUNT(*) … WHERE playedAt >= :since GROUP BY artist ORDER BY COUNT(*) DESC LIMIT :n`
   - 全局 Top 专辑：`getAlbumAggregates` 的变体，按 `playCount` 排序
   - 那年今日：按 `strftime('%m-%d', playedAt/1000, 'unixepoch', 'localtime')` 匹配
3. Repository 方法照 `getRecentHistory`（:2277）或 `getRecentSongNotes`（:1447）的写法：从 `activeSource.value?.id` 和 `activeProfileId` 取作用域；要实时刷新的话返回 Flow，或者参考 `observeMemorySignalStamp` 用变更戳触发。
4. 封面：存储键用 `CoverRef.fromStorageKey` 还原，再用 `repository.resolveCoverUrl` 解析。这只对**当前激活的 provider** 有效，正好和按 provider 隔离一致。不同 profile 是互相独立的数据源，不要混用。目前唯一有意跨 provider 的是笔记的 `observeCrossProvider`。
5. 只有加列时才需要 schema 变更，流程同 A.7。
6. 文案里要讲清数据口径：时长是上限；跳过的歌也算播放；在 Spotify App 外部播放的歌不计入。

---

## 附：AGENTS.md 和代码的出入，以及顺带发现的问题

- AGENTS.md 提到的 `LegacyViewCompat.kt` 已经不存在（`data/model/` 下没有）。
- `song_info` 表只在 `AppContainer.kt:589-661` 的旧迁移里出现，已经不是实体了。
- 加密的凭据编解码已经落地（`EncryptedProfileCredentialsCodec` + `AndroidKeyStoreCredentialsCipher` + `FileBackedProfileCredentialsStore`，见 `AppContainer.kt:137-143`）。
- `spotify_home_album_cache` / `spotify_home_artist_cache` 和对应的 Repository 方法（:709-769）是死代码。`getRecentHistory`、`PlayHistoryDao.getPlayCount`、`deleteOlderThan` 也没有调用方。
- Subsonic 手动选的歌词会写进 `lyrics_cache`，但 `getLoadedLyrics`（:1686-1694）对 Subsonic 只走服务端、不读这张缓存表，所以手动选的歌词重新加载后会丢。
- Spotify 的 `searchLibrary` 用的是默认实现，结果是**目录**搜索。Library 页已经绕开它，改用本地快照（`LibraryViewModel.kt:651`）。