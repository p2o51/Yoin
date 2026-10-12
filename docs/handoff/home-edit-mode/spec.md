<!-- 2026-10-03 设计会话产出：只做设计，未改代码。交互原型见 claude.ai Artifact「Yoin 主页编辑态」。行号会漂移，动手前按符号名重新 grep。 -->

# Yoin 主页长按编辑态与新组件：推荐设计（终稿）

> 本稿没有改动仓库里的任何文件。
>
> **依据**
> - 调研文档（同目录）：`groundwork-home.md`（下称 UH）、`groundwork-constraints.md`（UC）、`groundwork-abstraction.md`（UA）。
> - Mobbin 调研：`mobbin-references.md`（由 `research-*.json` 汇总）。本文的每个 Mobbin 链接都在这些文件里逐条核对过，没有编造。Mobbin MCP 接口只能搜 iOS 和 Web，调研只搜了 iOS。
> - 三份方案、组件目录、三位评委的评审，以及对综合稿的两份审查（代码事实核对、需求忠实度）。§10 列出本稿相对综合稿的全部改动。
>
> **约定**
> - 路径相对于 `app/src/main/java/com/gpo/yoin/`。行号取自 2026-10-03 晚间的工作树。热文件正被其他会话改动，行号每小时都在漂，**动手前一律按符号名重新 grep**。
> - 以下文件有其他会话的未提交改动：`YoinButtonGroup.kt`、`YoinChromeGroup.kt`、`YoinEdgeSplitGroup.kt`、`YoinNavHost.kt`、`ShellBackResolver.kt`、`BackMotionTokens.kt`、`SeamDissolve.kt`、`Motion.kt`、`WindowAdaptiveRuntime.kt`、`HomeEditorialContent.kt`、`HomeScreen.kt`、`HomeViewModel.kt`、`HomeUiState.kt`、`HomeWidgetGrid.kt`、`YoinRepository.kt`、`AlbumMemoryCandidate.kt`、`AlbumMemoryCandidateBuilder.kt`、`SongNoteDao.kt`。
> - 未跟踪的新文件：
>   - 「Home 提示」会话：`ui/home/HomeHintVariant.kt`、`HomeMemoryPill.kt`、`HomeWidgetGridAdaptive.kt`；
>   - 「重做 Home」会话（feed 框架与密度）：`ui/home/HomeFeedFrame.kt`、`HomeFeedDensity.kt`、`ui/experience/FeedUnits.kt`；
>   - 「同窗分列」会话：`ui/experience/PaneWidthMotion.kt`、`ui/navigation/pane/`。
> - 标了【Android 启动器惯例】的内容来自对 Pixel Launcher 和 One UI 的经验，不来自 Mobbin。
> - UI 文案沿用仓库现有的英文硬编码风格（例如 `"Edit Home"`，见 `HomeLayoutEditor.kt:316`），括号里给中文释义。

---

## 0. 结论

1. **推荐方案**：以方案 A「Section 级就地编辑」为骨架。
   - 在主页任意位置长按，就地进入编辑态，不换组件树。v1 能移动的单位只有 section。
   - 每个块垫上底板，出现「隐藏」键和把手。被按住的那一块，底板从按点处长出来。
   - 抖动形态交给 Q1。本稿推荐「轻摆后静默」：块内每张卡各自轻摆约 1°，6 秒没有触摸就停，一碰又动。备选是「入场一颤」和「一直摆」，§2.2.4 给了三种形态的完整参数。
   - 手指一拖，所有块收成带真实小封面的「签条」，在手指下短距离排序，松手后展开回原位。
   - 底栏还是同一条栏，切到 `[Undo|Add] [Done]` 姿态。返回由 `ShellBackOwner.HomeEdit` 门控。
2. **为什么选 A**：三位评委都把 A 列为最佳基底，品味 7.5、工程 7、UX 6.5，三项都是最高分。理由如下：
   - 就地编辑，状态连续；
   - 不迁移数据，DB 保持 v28；
   - 每个值只有一个 settle owner；
   - 底栏直接复用已上线的 idle 两半几何；
   - 返回优先级 NP > HomeEdit > DetailPane，三案中只有 A 排对了。
3. **从 B 嫁接**：
   - 卡片级持续轻摆的参数：外缘位移约 1dp、单时钟、6 秒静默；
   - kick 式余韵；
   - 进入、退出、返回统一用 `stageSettleSpring`；
   - 编辑期间冻结内容推送；
   - 导航位只放无害动作。
4. **从 C 嫁接**：
   - 抖动方向按 `section.id` 哈希；
   - 会话内撤销栈；
   - TalkBack 动作放进 P0；
   - 常驻的非手势入口。
5. **UX 评委的硬要求**是拖动不能依赖长距离自动滚动。本稿用一层只在拖动期间存在的「签条层」满足它，不做 B 那种布局级折叠。平板上要不要改为原尺寸拖动，交给 Q3。
6. **审查后的主要修订**：
   - 久别重逢的数据口径修正。原写法取不到「只有专辑分」的专辑，「多久没听」里混进了详情页访问，播放后卡片也不会离场。
   - 签条换成带封面的版本。
   - 蓄力阶段预显底板。
   - Pixel Tablet 没有振动马达（已实测），所以每个触感时刻都配了视觉孪生。
   - 补上 §1.4「主页新数据接在哪」。
   - §8 重写为 11 题。
7. **新组件**：
   - P0：「久别重逢」；
   - P1：「接着听」「常听前三」「常听单曲」「换一批」；
   - P2：「听歌日历」「差一点成回忆」「离线说明行」「那天在听」「库内漫游」；
   - JBI 格子层（钉选、改尺寸）的范围交给 Q5。
8. **工作量**：P0 约 16 人日，另加 2 天 Pixel Tablet 真机 QA 和 0.5 天手机触感验收。
9. **待拍板**：§8 共 11 题。Q1（抖动形态）、Q2（卡片长按语义）、Q3（拖动形态）可以在配套的交互原型里直接试手感，建议先试原型再答这三题。

---

## 1. 音乐服务抽象层速览（来自 UA）

### 1.1 分层

```
Composable（只读 UiState，不碰 AppContainer）
  → ViewModel（能力门控：repository.capabilities / ServiceFeatureCatalog.forProvider）
  → YoinRepository (data/repository/YoinRepository.kt:104)
       activeSource / activeProfileId；Spotify 的库列表绕过 source 直接读 Room（SpotifyLibrarySyncCoordinator，TTL 1h，每类 ≤200）
       详情缓存：内存 LRU + DetailCacheStore；本地 Room 信号（play_history、ratings、notes…）
  → ProfileManager (data/profile/ProfileManager.kt:43)：activeSource StateFlow，切换时 dispose
  → MusicSource (data/source/MusicSource.kt:26)：library() / metadata() / writeActions() / playback() + Capability(9)
  → SubsonicMusicSource | SpotifyMusicSource（+AppRemote） | AppleMusicSource（+MusicKit）
```

### 1.2 三家能力的实际差异

| 能力 / 方法 | Subsonic | Spotify | Apple Music |
|---|---|---|---|
| `FAVORITES` / `getStarred` | 实做（getStarred2） | 实做（Room：已存曲目、专辑 + 关注艺人）；Library 不显示 Favorites 标签（`ServiceFeatures.favoritesAreLibrary`） | **桩**，恒为空（`AppleMusicSource.kt:343`） |
| `RANDOM_SONGS` / `getRandomSongs` | 实做 | 部分：把已存曲目打乱 | **桩**（`:344`） |
| `getAlbumList(type)` | type 透传，random/newest/highest/frequent 都可用 | Repository 读 Room：newest、recent 按 addedAt，random 为打乱 | 只认 `newest`，**其他 type 被忽略**，「随机」其实永远是前 18 张（`:105-127`） |
| `LIBRARY_SONGS` / `getLibrarySongs` | 不支持 | 实做（2026-10-10 起 = Liked Songs：Repository 读同步缓存，按加入时间倒序；`SpotifyMusicSource` 本身不提供） | 实做 |
| `PLAYLISTS_WRITE` | 有 | 有（删除 = 取消关注） | 无（只读） |
| 播放 `handleFor` | `DirectStream` | `ExternalController`（App Remote，需要 Premium） | `ExternalController`（MusicKit）；导入曲目会抛错（`:361-366`） |
| 远端「历史」 | `recent`/`frequent` 依赖 scrobble，**Yoin 从不 scrobble** | 只有 `/me/player/recently-played`（≤50） | 未接线 |
| 推荐、相似、Top | `getSimilarSongs2` 未接线 | 2026-02 开发模式删除了 related-artists 和 top-tracks；`/me/top` 缺 `user-top-read` scope | heavy-rotation、recent/played 未接线 |

另外有两处漂移风险：
- `LYRICS` 和 `SEARCH` 这两个 Capability 没有任何 UI 读取。
- Apple 的能力集合在 `AppleMusicSource.kt:50-56` 里内联写死，没有引用 `ServiceFeatureCatalog`。

### 1.3 主页组件只能靠本地 Room 的部分

三家的远端统计要么没接线，要么口径对不上（Yoin 从不 scrobble），所以 P0/P1 只读本地。要不要改读远端，见 Q10。所有查询都要同时按 `profileId + provider` 过滤，可用的索引是 `(profileId, provider, playedAt)`。

| 主页需求 | 本地来源 | 口径限制（必须写进文案） |
|---|---|---|
| 最常播放、听歌天数、那年今日、常听单曲 | `play_history`（每行自带 title/artist/album/albumId/coverArtId/durationMs） | 见下方说明 |
| 久别重逢、差一点成回忆 | `getAlbumMemoryCandidates`（`YoinRepository.kt:1419`），**需改造，见 §5** | 评分只存本地。候选的 `lastPlayedAt`/`firstPlayedAt` 混入了 `activity_events` 的访问事件（`AlbumMemoryCandidateBuilder.kt:71-80`），不能当「播放时间」用 |
| 接着听 | `getRecentHistory`（`:2287`，目前没有调用方）、`activity_events` | Spotify 可以优先用 recently-played |
| 笔记卡 | `getRecentSongNotes`（`:1447`） | — |

`play_history` 的口径限制：
- **开始播放时就记一条**（`PlaybackManager.kt:727-747`），跳过的也算一次。
- `completedPercent` 恒为 0。
- 只存艺人名，没有 artistId。
- 在 Spotify App 和 Apple Music App 里的播放不会进来。

所以文案**只说「次」和「天」，不说分钟**，并写明 "in Yoin"。

Recently Added 的数据现状：
- Subsonic 实际显示的是「最近收藏」（`SubsonicMappers.kt:48-51`）。
- Apple 上恒为空，因为读的是 `getStarred` 桩。

这两处在 P1 修数据，不动已定稿的版式。

### 1.4 主页新数据接在哪

**纯本地数据**（推荐，三家都能用）：
1. 在 DAO 加 `@Query`，同时按 `profileId` 和 `provider` 过滤，尽量命中 `(profileId, provider, playedAt/timestamp)` 索引。
2. 在 Repository 加入口，写法照 `getRecentHistory`（`:2287`）或 `getRecentSongNotes`（`:1447`）：从 `activeSource.value?.id` 和 `activeProfileId` 取作用域。
3. 需要实时刷新的，用变更戳 `COUNT(*) + MAX(时间列)`，参考 `observeMemorySignalStamp`（`:1470`）。
4. 在 `HomeSection` 加一个常量。id 定下后永远不改。
5. 在 `HomeEditorialContent` 写渲染。`HomeLayout.reconcile` 会自动兼容已保存的布局。
6. 不动 `MusicSource` 和 `Capability`。

**依赖远端的数据**（AGENTS.md 步骤 2）：
1. 在 `MusicSource.kt` 对应的切片里加方法，同时加一个 `Capability`。
2. 同步 `ServiceFeatureCatalog`，以及 `AppleMusicSource.kt:50-56` 的内联集合。
3. 三家逐个实现，不支持的返回 failure。
4. UI 按 `capabilities` 门控。

**现状里主页绕过接口的地方**：
- `getSpotifyRecentActivities`（`YoinRepository.kt:2320`）对 `activeSource` 做 `as? SpotifyMusicSource`。它是 Activities 的 Spotify 数据源。
- Repository 里另有 4 处 Spotify 向下转型，都与主页无关：`requireSpotifySource`（`:424`）、库同步标脏（`:442`）、Connect 设备（`:1649`）、播放转移（`:1670`）。
- `getAlbumList(type: String, …)` 的 type 是字符串，source 没法声明自己支持哪些 type。

**建议（Q10 拍板后才做）**：
- 新增 `MusicLibrary.getRecentlyPlayed(limit)` 和 `Capability.RECENTLY_PLAYED`：
  - Spotify 把现有实现搬过来；
  - Apple 可接 `/v1/me/recent/played/tracks`（仓库内未验证）；
  - Subsonic 不支持。
  
  这样可以消掉与主页相关的那 1 处向下转型。
- `getAlbumList` 改为 `AlbumListKind` 枚举，source 声明 `supportedAlbumListKinds`。Apple 就不会再把 random 静默当成 newest。
- Apple 的能力集合改为引用 `ServiceFeatureCatalog.appleMusic`。
- 「接着听」**不需要**新方法。`PlaybackManager.play(tracks, startIndex, source, activityContext)`（`PlaybackManager.kt:231-236`）的 Spotify 分支已经在内部按 `ActivityContext` 走上下文播放（`buildSpotifyContextPlaybackFn` → `startContextPlayback`）。

**AGENTS.md 与代码的出入**（建议顺手修文档）：
- 开头说 Subsonic 是唯一已上线的后端、Spotify 在计划中。实际上 Spotify 和 Apple Music 都已接入。
- `LegacyViewCompat.kt` 已不存在。
- 加密凭据编解码已落地（`EncryptedProfileCredentialsCodec`）。
- `song_info` 已经不是实体。
- 现在的表都按 `profileId + provider` 隔离，AGENTS.md 只写了 `provider`。

---

## 2. 长按编辑态完整规格

### 2.0 状态与唯一写入者

- **编辑状态**：`HomeSurface` 增加 `Edit`，与 `Feed`、`Memories` 并列（`ui/experience/ExperienceSessionStore.kt:13`）。
  - 用类型保证 Memories 和编辑态不会同时出现。
  - 状态放在 shell 层，HomeScreen 被销毁时也不会丢（UC §1.2 gap 5）。
- **编辑进度 P**：`ExperienceSessionStore.homeEditProgress = Animatable(0f)`，先例是 `shellBarChromeMorph`（`:57`）。
- **`HomeEditController`**：在 `YoinNavHost` 里 `remember`，与 `memoriesReveal` 同级。
  - 对外只有这几个写入口：`enter(origin)`、`commitAndExit()`、`snapExit()`、`scrubBack()`、`settleBack()`。
  - P 的写法照 `RevealState.launchAnimateTo`（`RevealState.kt:109`）。
- **spec 在哪里取值**：
  - `YoinMotion` 的 `defaultSpatialSpec`、`fastSpatialSpec`、`slowSpatialSpec`、`fastEffectsSpec`、`spatialSpring` 都是 `@Composable`，并且读 `LocalYoinMotionRole`。
  - `YoinNavHost` 下面是 Standard；Expressive 只由 `HomeContent` 的 `ProvideYoinMotionRole(Expressive)` 提供。
  - 所以除 `stageSettleSpring` 和 `homeEditKickSpring` 外，所有 spec 都在 `HomeEditorialContent` 的组合期取值，`remember` 成一个 `HomeEditSpecs`，再传给 controller 和手势检测器。
  - controller 自己不调用 `@Composable` spec，与 `HomeLayoutEditor` 的 `settleSpec` 写法一致。

| 值 | 唯一写入者 | 规格 | 读取阶段 |
|---|---|---|---|
| P `homeEditProgress` | `HomeEditController` | 进入、退出、返回提交、返回取消都用 `YoinMotion.stageSettleSpring()`（0.85/700，`Motion.kt:258`）；返回手势期间用 `snapTo` | 块和 header 在 draw 阶段读；底栏在自己的 measure 里读（同 `chromeProgress`） |
| `charge`（蓄力） | 手势检测器 | 上升 `slowSpatialSpec()`，回落 `fastSpatialSpec()` | `graphicsLayer`（缩放）/ draw（底板预显） |
| `lift`（原位拿起） | 拖动引擎 | 拿起 `fastSpatialSpec()`，放下 `defaultSpatialSpec()` | `graphicsLayer` |
| `fold`（块⇄签条） | 拖动引擎 | 双向 `defaultSpatialSpec()` | draw / placement |
| 签条邻居让位 `stripOffset[id]` | 拖动引擎 | `YoinMotion.spatialSpring()` | placement |
| 被拖签条 | pointer 循环（1:1），松手后由 settle Animatable 接管 | `defaultSpatialSpec()` + 初速度 | placement |
| 落点洞 `holeY` | 拖动引擎 | `fastSpatialSpec()` | draw |
| `kick[id]`（余韵） | `HomeEditController.impulse()` | 新 token `homeEditKickSpring()`（§2.2.4） | draw（`drawWithContent` 画布旋转） |
| `E`（持续轻摆包络，仅 Q1 = b/c） | `HomeEditController` | 升起 `fastSpatialSpec()`，收回 `defaultSpatialSpec()` | draw |
| `t`（轻摆时钟，仅 Q1 = b/c） | 全页唯一的 `withFrameNanos` 循环 | 写一个 `MutableFloatState` | draw |
| 隐藏中的块 `hide[id]` | controller | `fastSpatialSpec()` / `fastEffectsSpec()` | `graphicsLayer` |
| feed 中块的位置 | LazyColumn `animateItem` | `spatialSpring()`；`fold > 0` 时 `placementSpec = null` | layout |

**缩放走 `graphicsLayer` 的代价**：`charge`（1 − 0.012·charge）、`lift`（1.02）、`hide`（0.96）的缩放属于 LayerPositionalProperties，变化时会让块内所有 `SeamNode`（`GlobalPositionAwareModifierNode`）逐帧回调。本稿**明确接受**这个代价，原因有两条：
- 每次只作用于一个块，持续 ≤ 300ms，和现有的 `elasticPress`、`stagedBeat` 同级；
- 如果改用 `drawWithContent` 缩放，`shadowElevation` 的轮廓会和缩放后的内容错开约 3dp。

长时间、多块同时运行的旋转（kick 和轻摆）一律走 `drawWithContent`。`shadowElevation` 和 `alpha` 不属于位置属性，可以继续留在 `graphicsLayer`。

`HomeScreen.kt` 本地的 `isEditMode`（`:86`）、`BackHandler`（`:94`）和 `AnimatedContent(isEditMode)`（`:235` 起）全部删除。这样顺带修掉 UH §3 的 1–5 号问题：stagger 重播、滚动回顶、宽度跳变、空 section 抓不到、长按被吞。

### 2.1 进入

#### 2.1.1 哪里长按会进入

| 普通态下的操作位置 | 结果 |
|---|---|
| 任意 section 内的卡片：Activities 的 hero/small/wide/strip，JBI 的 1×1/1×2，RA 的曲目格和专辑卡 | 进入编辑，并**原地拿起**所在 section（Q2 推荐项） |
| section 标题、section 内部的缝隙（bento 10dp、网格 16/12dp、RA 8/14dp） | 进入编辑，拿起该 section |
| section 之间的 18dp 缝、header 标题 "Home"、状态栏带、页边（contentPadding 区，平板竖屏两侧各 56dp）、feed 末尾空白 | 进入编辑，不拿起。入场涟漪以离按点最近的块为原点 |
| header 的 Memories 入口（chevron，或「Home 提示」会话的记忆胶囊）、设置齿轮 | **不进入**。这些位置注册为排除区，保持纯按钮 |
| `HomeEmptyCard` | 进入编辑，拿起 Activities |
| feed 末尾的 "Edit Home" 入口（§4）、JBI 空位提示格（Q6b） | 点按进入（非手势入口） |
| 底栏 Home 键长按（P1） | 进入编辑；在 Library 时先切回 Home 再进入 |
| **鼠标、触控板的副键点按**（`PointerType.Mouse` 且 `buttons.isSecondaryPressed`，用于平板桌面窗口模式） | 立即进入编辑，选中该块但不拿起；点在空白处则只进入编辑。用鼠标按住左键 400ms 很难被发现，右键才是自然的入口 |

#### 2.1.2 检测器

新文件 `ui/home/edit/HomeEditGestures.kt`，提供 `Modifier.homeEditGestures(controller, listState)`，挂在外层 `seamTide` Box 上（`HomeEditorialContent.kt` 里带 `.seamTide(...)` 的那个 Box）。同时删除挂在 LazyColumn 上的 `detectTapGestures`（当前 `:356-366`），连同那段错误注释。

```kotlin
awaitEachGesture {
  val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
  if (controller.isExcluded(down.position)) return@awaitEachGesture      // header 按钮 / 徽标 / 托盘 / 页尾入口 / 提示格
  val target = controller.hitTest(listState.layoutInfo, down.position)   // Section(id) | Blank
  if (down.type == PointerType.Mouse && currentEvent.buttons.isSecondaryPressed) {
    down.consume(); controller.enter(origin = target, pointer = down.position, lift = false); return@awaitEachGesture
  }
  val T = viewConfiguration.longPressTimeoutMillis                       // 默认 400ms，跟随无障碍「触摸和按住延迟」
  // 阶段一：只观察，不消费。先抬起 → 放弃（卡片 onClick 照常）；位移 > touchSlop → 放弃（滚动/横滑/下拉照常）
  // 到 T/2 时：controller.startCharge(target, down.position)
  // 到 T：从这一刻起在 Initial pass 消费后续所有 change
  haptics.performLongPress(); controller.enter(origin = target, pointer = down.position, chargeLatch = charge.value)
  // 阶段二：位移 > touchSlop → controller.beginCarry()（收成签条，§2.3）；抬起 → 原地放下
}
```

- **命中测试的坐标换算**（工程评委的 must-fix）：
  - 纵向：`itemTopPx = info.offset − layoutInfo.viewportStartOffset + listOriginInBox.y`，其中 `viewportStartOffset = −beforeContentPadding`。
  - 横向：「重做 Home」会话的 `HomeFeedFrame` 已经让 LazyColumn 铺满容器，页边改成了 `contentPadding`：Capped 档两侧 16dp 且内容限宽 688dp，Wide 32dp，横屏手机 24dp。页边值在 layout 阶段写入 `feedFrame`。所以 x 落在 `[feedFrame 当前左页边, 宽 − 右页边]` 之外就算 Blank，页边值在 down 那一刻读一次。
  - section 的 item key 统一改成 `"section-${section.id}"`，替换现在硬编码的 `"section-activities"`、`"section-widget-grid"`、`"section-recently-added"`（当前 `:403/:473/:498`）。改之前先 grep 测试里有没有引用旧 key。
- **排除区**：`Modifier.homeEditExclusion(controller, key)` 用 `onPlaced` 保存 `LayoutCoordinates`，只在 down 那一刻用 `boxCoords.localBoundingBoxOf(coords)` 判断，不做逐帧计算。
  - 适用于：header 的两个入口、隐藏键、托盘行、页尾按钮、提示格。
  - 以后 header 换成记忆胶囊，这套也能自动适配。
- **为什么不会误触发 onClick**（已对照 foundation 1.11.0-beta02 字节码核实）：
  - `ClickableNode` 在 Main pass 无论如何都会消费 down。
  - 过阈值后，我们在 Initial pass 消费 move 和 up。卡片对已消费的 up 调用 `changedToUp()` 会返回 false，于是走 `cancelInput`，不触发 onClick。
  - 同一帧里，`HomeSurface.Edit` 把卡片变成 `noRippleClickable(enabled = false)`，触发 `disposeInteractions` 并发出 `PressInteraction.Cancel`。`elasticPress` 的 0.97 缩放和 Bun→Triangle（`HomeWidgetGrid.kt` 的按压形变）随之弹回。
- **为什么不用 `combinedClickable`**：
  - 它做不到「长按后继续移动就拖」，onLongClick 之后的 move 会被 LazyColumn 抢走。
  - 它会和容器检测器在同一个超时点双重触发。
  - 它默认还自带一次长按震动（UH §3.7）。
- **TalkBack**：
  - 每张卡加 `semantics { onLongClick(label = "Edit Home") { controller.enter(Section(id)); true } }`。
  - header 标题和每个 section 标题加 `customActions = Edit Home`。
  - 不挂在 LazyColumn 根节点上，因为根节点拿不到焦点。
- **鼠标悬停**：编辑态下，指针悬停到某个块上时，该块把手的颜色从 `onSurfaceVariant` 过渡到 `onSurface`（`fastEffectsSpec()`）。

#### 2.1.3 蓄力暗示

| 时间 | 表现 |
|---|---|
| 0 到 T/2（200ms） | 什么都不做，卡片自己的 `elasticPress` 0.97 照常 |
| T/2 到 T | **只作用于被按住的那个 section 块**，`charge` 0→1（`slowSpatialSpec()`）。<br>• 缩放：块的 `graphicsLayer` 缩放为 `1 − 0.012·charge`，`transformOrigin` 取按点。<br>• **底板预显**：以按点为中心的 96dp 方块与块矩形取交集，得到 `R_press`。底板画在 `R_press` 外扩 `4dp·charge` 的矩形上，alpha 为 `0.4·smoothstep(charge)`，形状用 `PanelAnimated`。96dp 大约是一张小卡，这样就不需要给每张卡登记坐标。<br>• 不做整页下沉，不震动。按在 Blank 上不显示蓄力 |
| 放弃 | `charge` 回到 0（`fastSpatialSpec()`），底板沿原路缩回、淡出 |
| 降级 | `AdaptiveReduced` 或 `MotionDurationScale == 0` 时不显示蓄力 |

**为什么要预显底板**：卡片自身的按压视觉（0.97 缩放，JBI 还有 Bun→Triangle）会盖过整块 1.2% 的缩放，用户只会以为是一次普通按压。蓄力其实是「通往编辑态的进度」，它预览的就是编辑态本身，放弃时原路退回。这和 Yoin 返回教条里的「手势进度直接驱动 UI、预览目的地」是同一个思路。

它和 §9 否掉的 A 方案「150ms 起整块预显」不同：
- 这里从 T/2 = 200ms 才开始；
- 只作用于被按的那一块；
- 最高 alpha 只有 0.4。

一次 250ms 的慢点按，底板 alpha 只到约 0.06，看不出来。

#### 2.1.4 过阈值那一帧（同一帧内按顺序发生）

1. `performLongPress()`。整个进入过程只震这一次。
2. 消费后续事件，设 `HomeSurface.Edit`。随之 resolver 返回 `HomeEdit`，VM 冻结推送（§2.2.6），卡片变为禁用，按压视觉弹回。
3. P 用 `stageSettleSpring()` 从 0 到 1。P 动画期间外层 Box 加 `voteHighFrameRate(true)`（`ui/experience/FrameRateVote.kt:22`）。
4. 被按住的块执行 `lift` 0→1（`fastSpatialSpec()`）：
   - 以按点为中心放大到 1.02，阴影从 0 到 6dp，`zIndex(1f)`。
   - **底板从蓄力矩形长到整块**：矩形为 `lerp(R_press⁺, R_plate, smoothstep(p_i))`，alpha 为 `max(0.4·smoothstep(chargeLatch), smoothstep(p_i))`，颜色用 `fastEffectsSpec()` 插值到 `surfaceContainerHighest`。
   - `chargeLatch` 是过阈值时锁存下来的普通 Float，不是新的 owner；P ≥ 0.6 时清零。
   - `charge` 的缩放部分同时回到 0，在 graphicsLayer 里与 `lift` 相乘。
5. 入场涟漪：`p_i = ((P − δ_i)/(1 − δ_max)).coerceIn(0f, 1f)`，其中 `δ_i = 0.06·min(abs(i − i₀), 4)`，`δ_max = 0.06·min(N − 1, 4)`。所有块共用 P 这一个值，不新增 owner。
6. 每个块的 `p_i` 第一次越过 0.85 时（用 `snapshotFlow` 判断），注入一次入场 kick（§2.2.4）。被拿起的块不注入。如果 Q1 选 (b) 或 (c)，持续轻摆的幅度还要再乘 `smoothstep(p_i)`。
7. 列表结构变化：
   - 用 `animateItem` 淡入新插入的项：空 section 的占位块、托盘、页尾编辑区。
   - 移除页尾的 "Edit Home" 和 JBI 空位提示格。
   - `pullToMemoriesConnection` 在编辑态直接返回 `Offset.Zero`。
   - RA 设 `userScrollEnabled = false`。

   **被按住的块不能被推走**：
   - LazyColumn 按首个可见 key 锚定。如果在被按块上方插入 112dp 的占位块，被按块会被 placement 弹簧从手指下推开，而此时检测器正在阶段二等待越过 slop。
   - 所以位于被按块**上方**的占位块推迟插入，等第一次抬起，或起拖收成签条之后再插。位于下方的（托盘、页尾）立即插入。
   - 加一条 androidTest：Activities 为空时长按 RA，RA 块不位移。
8. 底栏读取 P（§2.2.7）。

### 2.2 编辑态视觉

#### 2.2.1 块的结构

```
item(key = "section-${id}") {
  Box(Modifier
      .zIndex(if (held) 1f else 0f)
      .graphicsLayer { /* charge × lift 缩放、hide 缩放/透明度、lift 阴影 */ }
      .homeEditWiggle(controller, id, Block))   // 仅「整块」模式；drawWithContent 画布旋转
  {
    Plate()        // matchParentSize + 外扩 layout；Panel 20dp 连续圆角；surfaceContainerHigh；alpha = smoothstep(0,1,p_i)；自带 seamDissolve()
                   // 起点块：矩形从 R_press 长到整块，期间用 PanelAnimated（§2.1.4-4）
    SectionContent(interactive = !editing)   // 内容不缩放；「卡片级」模式下每张卡自带 .homeEditWiggle(controller, id, Card(k))
    EditBadges()   // 标题行末端：[隐藏][把手]；只在 P > 0.01 时组合（derivedStateOf）
  }
}
```

- **内容不缩放**。否决了 A 方案的 s_e≈0.927，理由有三：
  1. RA 的出血货架保持原样，编辑态不需要加遮罩，也就不必复议 owner 已经去掉的 RA 遮罩（`mem/feedback_no_midpage_truncation.md:23`）；
  2. 不改子树的位置属性，不会引发 seam 逐帧重算；
  3. 避开「厚重缩放」。
- **底板外扩**：
  - 水平外扩 8dp，伸进 16/24/32dp 的页边，离屏幕边至少留 8dp。
  - 竖直外扩 `min(6dp, (itemSpacing − 4dp)/2)`：18dp 间距时为 6dp，横屏手机 10dp 间距时为 3dp，保证相邻底板之间至少留 4dp。
  - 内容仍然对齐页边线，底板只是背衬。
- **形状**：
  - 非起点块的底板尺寸固定，只动 alpha，所以用连续圆角 `YoinContainerShapes.Panel`。
  - 起点块在生长期间用圆弧双胞胎 `PanelAnimated`，长成后换回 `Panel`，按 `Shape.kt:38` 的 `*Animated` 约定。
  - 不加描边，封面保持裸图。

#### 2.2.2 隐藏键与把手

- **隐藏键**：`FilledTonalIconButton(onClick, modifier = Modifier.size(32.dp))`。
  - 图标 `YoinSymbols.VisibilityOff` 18dp，容器 `surfaceContainerHighest`，contentDescription 为 `"Hide <title>"`。
  - 48dp 触控区由 M3 按钮内置的 `minimumInteractiveComponentSize()` 自动保证。**不要**再加仓库的 `minimumTouchTarget`：它是 `sizeIn`（`PressFeedback.kt:63-65`），会把视觉也撑到 48dp。
  - 语义是「隐藏」而不是「删除」。眼睛图标的先例见 Ubank：https://mobbin.com/flows/651e1927-c0f1-4379-b85f-8568569929c5
- **把手**：`YoinSymbols.DragHandle` 24dp，`onSurfaceVariant`，触控区 48dp，放在最靠外的一侧。只有一个启用的 section 时不显示。
- **布局**：
  - 两个控件都放在标题行（24dp 高）末端，垂直居中，触控区排成 48 + 8 + 48。
  - 每块只有一个徽标，把手是握持点，不算徽标。这满足 UX 评委「每格最多一个角标、相邻控件间隔 8dp」的要求。
- **弹出**：`local_i = ((p_i − 0.25f)/0.75f).coerceIn(0f, 1f)`，`scale = lerp(0.6f, 1f, smoothstep(local_i))`，alpha 同为 `smoothstep(local_i)`。返回预览时可以直接倒放。
- 空 section 的占位块同样有隐藏键和把手。

#### 2.2.3 header

header Row 目前的结构（`HomeEditorialContent.kt` 里的 header 函数）是：
- 标题 Text（headlineLarge，行高 40sp）；
- `weight(1f)` 的 `HomeMemoryEntry`；
- 2dp 间隔；
- 设置 `IconButton`（48dp）。

整行的高度由 48dp 的 IconButton 撑起。

- **入口和齿轮不卸载**：
  - alpha 为 `1 − smoothstep(0, 0.5, P)`。
  - P ≥ 0.5 后设 `enabled = false`，并用 `clearAndSetSemantics {}` 清空语义，但**保持组合**。如果卸载，行高会从 48dp 掉到约 40dp，整个 feed 会在 P 动画中途上跳约 8dp。
  - 稳妥起见，Row 另加 `Modifier.heightIn(min = 48.dp)`。
- **标题**："Home" 正常参与测量。"Edit Home" 叠在它上面，但不占宽度：
  ```kotlin
  Modifier.layout { m, c ->
      val p = m.measure(c.copy(maxWidth = Constraints.Infinity))
      layout(0, p.height) { p.place(0, 0) }
  }
  ```
  这样 Box 不会按更宽的 "Edit Home" 去挤占 `weight(1f)` 的区域，记忆胶囊的 fit rule 在普通态不受影响。
  - 字号同 headlineLarge（横屏手机为 headlineMedium 28sp），两个 Text 都带 `seamFade`。
  - "Home" 的 alpha 为 `1 − smoothstep(0.2, 0.6, P)`，"Edit Home" 为 `smoothstep(0.4, 0.8, P)`。
  - "Edit Home" 设 `liveRegion = Polite`，作为进出编辑态的读屏播报。
- **首次提示**：只在前 2 次编辑会话出现。
  - 在 `weight(1f)` 的 Box 里靠右叠放 `"Drag to reorder"`：labelMedium，字距 0.1sp，`onSurfaceVariant`，alpha 为 `smoothstep(0.5, 1, P)`。
  - 只显示一行，放不下就不显示，不改变 header 高度。

#### 2.2.4 抖动（形态待 Q1 拍板，这里给出三种形态的完整参数）

**公共参数**

| 项 | 值 |
|---|---|
| 谁在动（Q1 子项） | **整块**：section 块（底板、内容、徽标作为一个整体）绕块中心转。<br>**卡片级**（推荐）：块内每张卡（封面连同它自己的文字和底色）各自绕卡中心转；底板、标题、徽标不动；没有卡的块（占位块）整块转。<br>两种模式下，header、托盘、页尾、底栏都不动。被拿起的块，振幅乘以 `1 − lift` |
| 幅度 | **整块**：按角点位移 2.0dp 换算，`Θ_i = clamp(deg(atan(2dp / (0.5·hypot(w_i, h_i)))), 0.15°, 0.8°)`，w、h 取底板尺寸。例：手机 Activities 344×400 约 0.43°，JBI 344×676 约 0.30°，RA 344×196 约 0.58°，平板竖屏 704×420 约 0.28°，平板横屏 1232×340 约 0.18°。<br>**卡片级**（取自 B）：`A_c = clamp(1.1° × 100dp / w_card, 0.35°, 1.1°)`，外缘位移约 1dp。例：JBI 1×1 和 RA 82dp 专辑卡约 1.1°，JBI 1×2 约 0.5°，Activities hero 0.35° |
| 方向与相位 | **整块**：`sign_i = if (stableHash(section.id) and 1 == 0) +1 else −1`。按 id 而不是 index 计算，换位后不会跳变。<br>**卡片级**：同一块内同频同相，正反方向按块内序号的奇偶交替（v1 卡片不移动，序号稳定）；不同块之间的相位和频率按 `section.id` 哈希错开。<br>对正在余韵中的块（或卡）再次注入 kick 时，沿当前速度方向叠加 |
| 枢轴 | 块中心 / 卡中心 |
| 绘制方式 | `drawWithContent { withTransform({ rotate(angle, pivot = center) }) { drawContent() } }`。这是画布变换，不改 graphicsLayer 的位置属性，因此不会经 `updateLayerParameters → requestOnPositionedCallback` 让子树里所有 SeamNode 逐帧回调（工程评委 must-fix） |
| 交界带 | 与顶部潮线带或底部栏点阵带相交时振幅为 0，离交界带 48dp 以内线性衰减。依据是 `docs/design.md:83`「栏下面的点阵始终不流动」。<br>整块模式按块矩形判断，所以在 Compact 上压到栏下的块（常常是 JBI）整块都不动。<br>卡片级按每张卡自己的位置判断：卡在块内的相对位置用 `onPlaced` 记一次（只在重新布局时更新），块的位置每帧从 `layoutInfo` 取，两者相加，不挂 `onGloballyPositioned`。这样只有贴近栏的那几张卡静止 |
| 降级 | `LocalMotionProfile == AdaptiveReduced`、`MotionDurationScale == 0f`（判断照抄 `SeamDissolve.kt:497-498`）或系统省电模式时，完全不抖，kick 也不抖。编辑态改由底板、徽标、标题和底栏表达 |
| 帧率 | 不投高帧率票 |
| 试调开关 | `HomeEditTokens.wiggleMode = Kick \| IdleSettle \| Continuous` 和 `wiggleTarget = Block \| Card`，只在 debug 生效。owner 拍板后收成常量，写法同 `HomeHintVariant.kt` |

**形态 (a) Kick：入场颤一下，余韵后归静**

| 项 | 值 |
|---|---|
| 形态 | 一次欠阻尼弹簧余韵：`kick.animateTo(0f, YoinMotion.homeEditKickSpring(), initialVelocity = v)`，从 0 带初速出发。**没有常驻时钟**，弹簧自己停下，静止时零开销，Compose 测试可以 idle |
| 新 token（`ui/theme/Motion.kt`） | `homeEditKickSpring() = spring(dampingRatio = 0.3f, stiffness = 450f, visibilityThreshold = 0.01f)`。<br>• 阻尼后频率 3.22Hz，相邻峰值比 0.37，峰值依次约为 Θ → −0.37Θ → 0.14Θ → −0.05Θ。<br>• 约 0.47s 衰减到 5% 以下。<br>• 按 0.01° 阈值，整块模式 0.52–0.76s 完全静止（Θ 越大越久）；卡片级 1.1° 时约 0.81s |
| 初速度 | 要让峰值等于 `a·Θ`，取 `v = a·Θ·31.6 s⁻¹`（由 ζ=0.3、k=450 的解析解得出，写成纯函数并加单测）。a 的取值：入场 1.0，编辑态点块 0.5，放下的块 0.7，被挤开的邻居 0.35 |
| 触发时机 | 入场（p_i 越过 0.85）、编辑态点块主体、放下（放下的块和位置变了的邻居）。滚动、隐藏、撤销都**不**触发 |

**形态 (b) IdleSettle：轻摆，6 秒无触摸后静默（推荐）**

| 项 | 值 |
|---|---|
| 角度 | `angle(t) = sign · A · E · smoothstep(p_i) · band · sin(2π·f·t + φ) + kick(t)`。整块模式 A = 0.6·Θ_i，卡片级 A = A_c |
| 频率 | `f = 2.4Hz × (1 + 0.07·h)`，h ∈ [−1, 1] 来自 `stableHash(section.id)`，块与块之间去谐 ±7%。初值要在平板上再调 |
| 时钟 | 全页**一个** `withFrameNanos` 循环，写一个 `MutableFloatState t`。各节点只在 `drawWithContent` 里读，不触发重组（adaptive 原则 7）。`E == 0` 时循环挂起等待，所以静止时零开销 |
| 包络 E | 只由 controller 持有：<br>• 进入编辑时目标为 1。<br>• **6s 内没有任何 pointer down**：用 `defaultSpatialSpec()` 收到 0，约 300ms。<br>• 任何 pointer down（检测器在 Initial pass 观察，不消费）：用 `fastSpatialSpec()` 回到 1，并重置 6s 计时。<br>• 拖动期间：其余块 E × 0.6，被拖块为 0 |
| 运行条件 | 只在 Home 可见、生命周期 ≥ RESUMED、NP 没有全屏盖住时运行；其余时候时钟停止 |
| kick | 入场、点块、放下照样注入 kick，与轻摆相加 |
| 测试 | Compose 测试用 `AdaptiveReduced` 关掉，或者等 6s 后再 idle |

**形态 (c) Continuous：一直摆到退出**

与 (b) 相同，只是没有 6s 静默。Compose 测试必须用 `AdaptiveReduced`。

**诚实代价**

| 形态 | 实际看上去 | 代价 |
|---|---|---|
| (a) Kick | 只是一颤，不算「抖动」。手机首屏通常只有一两块会动；整块模式下，压在栏下的块不动；平板横屏的 0.18° 几乎看不见。进入编辑主要靠底板、徽标、标题和底栏表达 | 零常驻开销 |
| (b) IdleSettle | 进入后、每次触摸后，有约 6 秒的「桌面抖动」，然后整页静止 | 一个时钟，每帧只做 draw 失效；静止后零开销 |
| (c) Continuous | iOS 式，一直在动 | 编辑态全程每帧 draw 失效；静止时也在动，测试必须关动画 |

关于「静止时完全不动」：原文（`docs/design.md:83`）是溶解点阵的规则，不是全局禁令。编辑态是用户主动进入的长时状态，更接近 NP「Gemini 思考」极光的先例：长时状态持续运动，短动作一次性运动。三种形态都遵守「交界带内振幅为 0」，栏下点阵始终静止。

#### 2.2.5 编辑态点块主体

- 点块主体时，该块（或被点的卡）注入 0.5Θ 的 kick。
- 同时把手图标用 `fastSpatialSpec()` 做一次 1→1.2→1 的脉冲，提示「从这里拖」。
- 如果这个块被交界带屏蔽了振幅，就只有把手脉冲。
- Q1 选 (b) 时，这一下也会把 E 拉回 1。
- 点块**不会退出编辑**。

#### 2.2.6 页面其余部分

- **内容冻结**：新增 `HomeViewModel.setEditing(Boolean)`。编辑期间对外发出的状态固定为进入时的快照。
  - 以下结果都先排队，退出后一次性应用：`refreshWidgetGridSignalCards`（`HomeViewModel.kt:388`）的拼接、6h 池轮换、Activities 刷新、久别重逢的离场。
  - feed 按 draft 的顺序渲染。退出后如果 Room 的回声还没到，继续渲染 draft，避免顺序闪回。
  - JBI 自带的列数 `AnimatedContent(SizeTransform)`（`HomeWidgetGrid.kt`，并行会话新加的）在编辑态不会触发：内容已冻结，档位也只在静止时切换。
- **seam**：`seamTide` 和 `seamDissolveViewport` 的机制不变。底板和徽标都自带 `seamDissolve()`。交界处不叠任何覆盖层。
- **Memories**：编辑期间下拉关闭、入口隐藏，`HomeSurface` 的类型保证两者互斥。

#### 2.2.7 底栏编辑姿态

底栏还是同一条栏，只是由宿主驱动做 dp-lerp。

- **新参数**：`YoinButtonGroup` 增加 `editProgress: () -> Float = { 0f }`，放在 `chromeProgress` 旁边。`YoinChromeGroup` 透传，`YoinEdgeSplitGroup` 同样加上。
- **几何**：在 `resolveBarGeometry`（`:557`）里加参数 `edit`，令 `val idleLike = maxOf(idle, edit)`。用它替换以下位置里的 `idle`：`idleWeight`（`:573`）、`collapsePill`（`:588`）、`pillComposed`（`:589`），以及供 `pillIdleAlpha` 使用的 `BarGeometry.idle` 字段。逐个姿态验证：
  - **导航姿态**：两个导航槽逐渐增宽到 `idleHalf`，pill 宽度按剩余空间公式精确收到 0。这就是已上线的 idle 路径。
  - **合并姿态（pane = 1）**：`idleWeight = 0`，导航槽保持静止宽度；`collapsePill = edit`，栏面收拢包住剩下的槽。这就是已上线的「合并 + 无播放」路径。A 方案说「合并姿态下 pill 保留」是错的，这里纠正为 **pill 在所有姿态下都折起**。
  - **navOnly**：pill 本来就已折起，edit 只替换槽里的内容。因为 `idleWeight = 0`，labels 不显示，只剩图标。
- **Done 要替换两处 Library**：图标 `YoinSymbols.Check`，文字 "Done"。
  - 导航姿态：右槽的 nav `LibraryButton`（`:470` 一带）。
  - 合并姿态：**左槽**里的 merged `LibraryButton`（`:318-337`）。合并时 Library 孪生画在左槽（`leftWidth = homeWidth + gap + libraryWidth`），右槽是页面的 Play split 和 Shuffle。
  - 两处都用同一个 `smoothstep(0.35, 0.65, edit)` 交叉淡化，点击都路由到 `commitAndExit`。
  - 容器色为 `lerp(libraryContainer, colorScheme.primary, edit)`，内容色插值到 `onPrimary`。这是局部插值，不新建 ColorScheme。
- **左槽（Home 位）**：替换左槽的 Home `FilledIconButton`（`:341` 起）。显示哪一项由纯函数 `resolveEditLeftSlot(undoDepth, trayCount)` 决定：
  - 撤销栈非空：显示 Undo；
  - 否则托盘非空：显示 Add，点按后 `animateScrollToItem` 滚到托盘；
  - 否则：显示置灰的 Undo（alpha 0.38，不可点）。

  容器 `surfaceContainerHighest`。**导航位不放任何破坏性操作**。
- **内容切换**：
  - 新旧内容是同一个槽里叠放的两个子元素，用 `smoothstep(0.35, 0.65, edit)` 交叉淡化，写法同 `idleLabelAlpha`（`:304`）。
  - Undo 和 Add 之间的切换用 `fastEffectsSpec()` 控制 alpha。
  - 全程不用 `AnimatedContent`、`ButtonGroup`、`FloatingToolbar`。
- **合并姿态**最终为 `[Undo|Add] [Done] [Play ▾] [Shuffle]`，详情列那一侧的页面动作照常可用。
- **EdgeSplit**：沿用已上线的 idle 退场（`YoinEdgeSplitGroup.kt:137-151`：alpha = 1 − hide，再沿自身边缘做 translationX 平移退场，不缩放）。
  - 令 `val hide = if (morph > 0.005f) 0f else maxOf(idleProgress, edit())`。
  - 组合门槛为 `maxOf(idleProgress, edit) < 0.995f || morph > 0.005f`。
  - 导航胶囊里的图标交叉替换。
- **点击路由**按离散的 `HomeSurface.Edit` 判断，不看动画值。编辑期间禁用 Library 长按搜索。
- **符号前置**：yoin-symbols 里没有 `Undo`，P0 先在 generator 里补上（`docs/design.md:92-98`）。如果发版跟不上，图标位改为显示 "Undo" 文字。
- **测试**：`BarGeometryTest` 补齐 edit ∈ {0, 0.5, 1} × idle ∈ {0, 1} × pane ∈ {0, 1} × navOnly ∈ {0, 1} 的矩阵，断言两点：
  - pill 不组合时 `surfaceInner == left + gap + extras + right`；
  - 任何一帧 pill 宽度都不小于 0，栏面不留洞。

### 2.3 拖动逐帧反馈（签条层，Q3 推荐项）

**拖动时 feed 本身不重排。** 拖动一开始，所有块都飞成签条，排序在签条层里完成；松手后一次性提交给 feed，然后展开。这样同时解决了四个问题：
- 高块（JBI 约 660dp）需要长距离自动滚动（UX 评委 must-fix）；
- LazyList 按首个可见 key 锚定，被拖项一动，列表就跟着滚走（工程评委 must-fix）；
- 被拖项会被回收；签条层不在列表里，所以不需要 pin；
- 拖动期间 seam 逐帧重算。

签条层是新文件 `ui/home/edit/HomeCarryStack.kt`，在外层 Box 里叠在 LazyColumn 之上。只用普通 Box，不用 SubcomposeLayout，也不用 `AnimatedContent`。

| 阶段 | 视觉 | Motion token | 触感 |
|---|---|---|---|
| ① 原位拾起 | **触发**：普通态在过阈值时。编辑态下，手指落在把手上、移动超过 slop 时立即拿起；落在块主体上要按住 `max(150ms, T/2)`（默认 200ms）。<br>**表现**：以按点为中心放大到 1.02，阴影 6dp，底板变为 `surfaceContainerHighest`，该块的 kick 和轻摆归零 | `fastSpatialSpec()` / `fastEffectsSpec()` | 普通态只有入口那一次 `performLongPress`；编辑态 `performDragStart()` |
| ①′ 拿起后不动就松手 | `lift` 回到 0，块注入 0.5Θ 的 kick，留在编辑态 | `defaultSpatialSpec()` | 无 |
| ② 起拖，收成签条（`fold` 0→1） | **触发**：拿起后位移超过 `touchSlop`。<br>**几何**、**起点矩形**、**签条底板**、**签条内容**、**feed 一侧**：见表下的 ② 详述 | `defaultSpatialSpec()`（Expressive） | 无 |
| ③ 跟手 | 被拖的签条在 y 方向 1:1 跟手，x 固定；签条中心与手指对齐 | — | — |
| ④ 越格与让位 | 移植 `HomeLayoutEditor` 的实时交换（`:225-249`）：`abs(dragOffset) > pitch/2` 时在 draft 里交换，并令 `dragOffset ∓= pitch`；邻居先 `snapTo(±pitch)`，再弹回 0。签条等高，所以旧编辑器「固定步长」的假设在这里重新成立 | `spatialSpring()` | 每换一格 `performSegmentTick()` |
| ⑤ 落点预览 | 在目标槽位画一块「洞」：`secondaryContainer` α0.35 填充，形状 `PanelAnimated`，**不描边**。洞的 y 由 `holeY` 追踪目标槽位。参考 Apple Podcasts 邻居让出落点 https://mobbin.com/flows/58435602-fe39-4434-b6b2-4ee4ee588e05 ，Deel 的落点占位 https://mobbin.com/screens/8b891b14-7137-4e69-ae3d-3557e0b6bc5c | `fastSpatialSpec()` | — |
| ⑥ 越界 | 超出首槽或末槽时，显示位移 = `d·(1 − 1/(x·0.55/d + 1))`，d = 56dp。只改显示，不改真实值 | — | 每次越界首次进入时 `performThreshold()` |
| ⑦ 签条栈放不下 | 只在手机横屏或 section 很多时出现。手指离安全区边缘 32dp 以内时，栈本身滚动，速度为 `900dp/s × ((32 − dist)/32)²`，在手指按住期间由帧循环驱动 | — | — |
| ⑧ 松手 settle | 目标槽位 = 当前槽位；如果 `abs(v) > 1600dp/s`，按速度方向最多多跨 1 格（借用 C 的投掷预测）。<br>被拖签条先 `snapTo(残差)`，再 `animateTo(0, initialVelocity = v)`，写法同 `settleDrag`（`HomeLayoutEditor.kt:97-112`）。`VelocityTracker` 喂累积位移（`:216-220`）。<br>从此刻到展开结束，一直 `voteHighFrameRate(true)` | `defaultSpatialSpec()` + 初速度 | — |
| ⑨ 提交并展开（`fold` 1→0） | 分 (a)–(d) 四步，见表下的 ⑨ 详述 | `defaultSpatialSpec()` | 顺序变了：`performConfirm()`；没变：无 |
| ⑩ 放下确认 | `fold == 0` 时签条层销毁，feed 底板重新显示。被放下的块注入 0.7Θ 的 kick，位置变了的邻居注入 0.35Θ | `homeEditKickSpring()` | — |
| ⑪ 中途再抓 | settle 或展开还没结束时按住签条：原地接住（`dragOffset = offsetAnim.value; stop()`，同 `:200-206`），`fold` 弹回 1。按在 feed 上则忽略，直到 `fold == 0` | — | — |
| ⑫ 第二根手指 | 忽略，但仍然 consume，避免触发滚动（同 `:213`） | — | — |
| ⑬ 拖动中发生档位变化、外部打开 NP 或详情列、按返回、ON_STOP | 先按 ⑧ 在当前槽位放下，展开完成后再处理该事件。档位只在静止时切换（adaptive §5/§7） | 同 ⑧⑨ | 同 ⑨ |

**② 详述**
- **几何**：
  - 安全区的 Y 范围为 [潮线静止位 + 8dp, 栏顶 − 8dp]。
  - `h_s = clamp((H_safe − (N−1)·8dp)/N, 48dp, 64dp)`，`pitch = h_s + 8dp`。
  - 宽度 `min(contentWidth, 560dp)`，左边对齐页面内容线。
  - 栈顶 = `clamp(finger.y − (k + 0.5)·pitch + 4dp, safeTop, safeBottom − H)`，k 为被拿起块的序号。
- **起点矩形 B_i**：
  - 可见块：取底板矩形与安全区的交集，保证签条层从不画进交界带。
  - 屏外块：在所在一侧的安全区边缘取一个签条大小的矩形，alpha 0、缩放 0.96。
- **签条底板**：
  - 矩形为 `lerp(B_i, S_i, fold)`，在 draw 阶段用新增的 `YoinContainerShapes.PanelAnimated`（20dp 圆弧双胞胎）绘制。
  - 颜色从底板色插值到签条色 `lerp(surfaceContainerHigh, 第一张封面 palette.baseColor, 0.30f)`，权重为 `smoothstep(0.3, 1, fold)`。这和 bento 用的是同一条规则，不用 `fromSeed`。
- **签条内容**：
  - 左起 3 张该块的真实小封面，边长 `clamp(h_s − 24dp, 24dp, 36dp)`，按实体类型取 backdrop 形状，相互压叠 30%，不加描边。
  - 封面取自 UiState 里该块已显示的前 3 项：Activities 前 3 个、JBI 前 3 格、RA 前 3 张专辑、久别重逢前 3 张。这些都命中 Coil 缓存，不发新请求。
  - 封面之后是标题（titleMedium SemiBold，单行），末端是 `DragHandle` 24dp，内边距 16dp。
  - 整组用 `Modifier.offset {}` 放在插值后的中心，alpha 为 `smoothstep(0.55, 1, fold)`。
  - 占位块没有封面，只显示标题。
  - `supportingText` 是旧列表编辑器的行文案（`HomeSection.kt:23`），签条上不显示，只留给 TalkBack。
- **feed 一侧**：内容 alpha 为 `1 − smoothstep(0, 0.45, fold)`。底板在签条层接手的同一帧隐藏；两者矩形相同、颜色相同，看不出交接。

**⑨ 详述**
- (a) 顺序变了：把 draft 提交给 feed（此时 `placementSpec = null`，feed 内容不可见），`setHomeLayout(draft)` 落盘，压入撤销栈。
- (b) **锚定，一步完成**：`listState.requestScrollToItem(idx(dropped), scrollOffset = -(S_dropped.top − listViewportTop).roundToInt())`。
  - 负 offset 由 LazyList 在同一次 measure 里向前回溯补足 item，首个可见项一次落定，不产生中间帧。
  - 如果分两步做，中间那一帧 `firstVisibleItemIndex > 0`，`seamScrolledPx()` 会变成 +∞（`SeamDissolve.kt:554`），潮线会硬切一帧。
  - 约束：**锚定前后，header（index 0）是否可见不能改变**。必要时钳住，剩下的距离由签条飞完。
  - `fold > 0` 期间调用 `SeamFlow.hold(true)`（在 `SeamDissolve.kt` 新增的小 API），锚定产生的滚动增量不喂给余辉和拉伸。
- (c) 用 `snapshotFlow` 等到 `layoutInfo` 里出现被放下的 key，再读取新矩形 R_i，取值规则同 ②。
- (d) `fold.animateTo(0)`：签条飞回 R_i，feed 内容淡回。

**P1 候选（不进 P0）**：在签条层里加一条 "Hidden" 分隔线，隐藏的 section 也作为签条排在线下，拖过分隔线即隐藏或显示。这相当于 Apple Photos 两栏增删的拖动版。它要先定清两件事如何对齐：「拖到隐藏区的哪个位置」，以及 `HomeLayout` 保留禁用项顺序的规则。所以暂时不做。

### 2.4 增删、重置与撤销

- **隐藏**：点隐藏键 → `performToggle(false)`，压入撤销栈。
  - 块先缩到 0.96（`fastSpatialSpec()`）并淡到 alpha 0（`fastEffectsSpec()`），然后在 draft 里设 `enabled = false` 并落盘。
  - item 离场时用 `animateItem` 淡出，邻居用 placement spring 合拢，托盘里随之淡入一行。
- **托盘**：编辑态下放在最后一个启用 section 之后，key 依次为 `tray-title`、`tray-<id>`、`edit-footer`。
  - 标题 "Hidden"（`HomeSectionTitle`）。
  - 每行的样式：
    - Panel 底板 `surfaceContainerHigh`，固定高度 64dp × fontScale，内边距 16dp。
    - 标题（titleMedium SemiBold）与 supportingText（bodySmall，`MarqueeText` 单行）上下贴合，复用 `HomeSection.supportingText`。
    - 新版块在标题后加一个 "New" 小标（labelSmall，`tertiaryContainer`，`YoinShapeTokens.Full`）。
    - 末端是 `FilledTonalIconButton(modifier = Modifier.size(40.dp))` + `YoinSymbols.Add`，48dp 触控区由 M3 自动保证，contentDescription 为 `"Show <title>"`。
  - 整行可点。不加虚线，托盘行也不抖。
  - 托盘兼做「默认关闭的新组件」目录，参考 Apple Photos 的两栏增删：https://mobbin.com/flows/a6d19d02-8f23-47c8-a884-36bb4b021bb4
- **显示**：`performToggle(true)`，压入撤销栈。
  - 该 section 回到**它原来的位置**，因为 `HomeLayout` 本来就保留禁用项的顺序（`HomeSection.kt:60-64`）。
  - 回来时用 `animateItem` 淡入并让邻居让位，进入视口后注入 0.35Θ 的 kick。不自动滚动。
- **重置**：放在托盘页脚，TextButton "Reset Home"，图标 `YoinSymbols.Refresh`。
  - 只有 `draft.sections != HomeLayout.Default.sections` 时可用。只比较 sections，不比较 `retained`，否则带有未知 id 的用户会永远可点。参考 Klarna：https://mobbin.com/screens/f6b05568-9203-4f66-9cef-b2726ab8eedf
  - 重置时保留 `retained`。
  - 点按后先压栈，再 `performReject()`。因为可以撤销，所以不弹确认框。
- **撤销**：在底栏左槽。
  - 会话内的内存栈，最多 20 个 `HomeLayout` 快照，退出编辑即清空。
  - 每撤一步写一次库，触感为 `performClick()`，与栏上其他按钮一致。
  - 不做跨会话撤销，也不做重做。
- **全部隐藏**：允许。普通态下 feed 显示 `HomeEmptyCard`：标题 "Your Home is empty"，说明 "Press and hold anywhere, or tap Edit Home, to bring sections back"，下面还有常驻的页尾入口。
- **空 section 占位**：section 已启用但没有数据时（JBI、RA 现在为空时都不渲染；久别重逢没有候选时同理），编辑态显示一个 `ExpressiveSectionPanel` 块。
  - 固定高度 112dp × fontScale，内容是 `HomeSectionTitle` 加一行如实的说明，例如 JBI "Nothing to jump back into yet"，RA "Nothing added this week"。
  - 它可以移动、可以隐藏，也会抖。
  - 插入时机见 §2.1.4 第 7 条。退出编辑后恢复「为空时不渲染」。

### 2.5 退出

| 途径 | 行为 | 动效 | 触感 |
|---|---|---|---|
| Done（§2.2.7 的两处之一） | 改动已经逐次落盘，这里只退出 | P → 0（`stageSettleSpring()`）。托盘、占位块和页尾入口的结构变化在提交那一刻发生，用 `animateItem` 淡出或淡入，与 P 并行 | `performConfirm()` |
| 点空白（缝、header、页边、托盘下方） | 等同 Done。如果按下时 `listState.isScrollInProgress`，这一下只算止住惯性滚动 | 同 Done | 无 |
| 点块主体 | **不退出**，见 §2.2.5 | — | — |
| 系统返回 | 见下文 | — | — |
| 外部打开 NP（通知、媒体键） | Compact：NP 全屏盖住，`snapExit()`。<br>Medium/Wide：先 `commitAndExit()` 动画退出，P 落定后再打开 NP 侧栏，不在弹簧中途换档 | — | — |
| 外部打开详情列或深链 | 同上（走 Medium/Wide 分支） | — | — |
| 切换 profile | `snapExit()`。逻辑从 `HomeScreen.kt:92` 迁出，放进 `YoinNavHost` 现有的 `LaunchedEffect(musicConfigurationRevision)`（`:421`）。改动早已落盘 | snap | — |
| ON_STOP | `snapExit()`，回到 app 时不会看到一页仍处在编辑态 | snap | — |
| 程序性切 section | 在 `LaunchedEffect(selectedSection)`（`YoinNavHost.kt:756`）里补 `if (selectedSection != HOME && homeSurface == HomeSurface.Edit) controller.snapExit()`。<br>`dismissMemoriesIfActive`（`:509`）的各调用点逐个核对：会打开 NP 或详情列的，按上面「外部打开」两行处理；其余用 `snapExit()` | snap | — |
| 旋转或其他配置变化 | `HomeSurface.Edit` 在 session store 里，所以保持编辑态。进行中的拖动按 ⑬ 处理；几何在静止后重新计算 | — | — |

**系统返回**

- **resolver**：`ShellBackOwner` 增加 `HomeEdit`。
  - 优先级为 **NowPlaying > HomeEdit > DetailPane > Memories > None**，判断条件依次是：`showNowPlaying` → `selectedSection == HOME && homeSurface == Edit` → `detailPaneOpen` → Memories。补 `ShellBackResolverTest`。
  - HomeEdit 排在 DetailPane 之前的理由：编辑态里卡片点击是禁用的，所以两者同时存在时，详情列一定是先打开的，编辑态更新，返回应该先退出编辑。详情列的子 dispatcher 只在 DetailPane 时启用，HomeEdit 胜出时它自动失效。
  - B 和 C 把 HomeEdit 排在 DetailPane 之后。这样在 Wide 下返回会先关掉详情列，shell 列从 600 跳到 1280，JBI 从 4 列变成 6 列，等于编辑到一半换了档。这是 UX 评委的 must-fix。
- **P0**：在 `YoinSection.HOME` 分支里、`HomeScreen(...)` 调用之后、`if (memoriesMounted)` 之前，无条件挂上 `BackHandler(enabled = shellBackOwner == ShellBackOwner.HomeEdit) { controller.commitAndExit() }`。
  - **不能**挂在 Memories 的 `BackHandler`（`:873-874`）旁边：那里在 `if (memoriesMounted)` 分支内部，而编辑态下 `memoriesMounted` 为 false（`:410`），处理器根本不会注册。
  - 优先级只靠 resolver 门控表达，与注册顺序无关（adaptive §8；`ShellBackResolver.kt:14-22`）。
  - 同时删除 `HomeScreen.kt:94` 和 `suppressBackHandling`（`YoinNavHost.kt:834`）。
  - `YoinNavHost.kt:746` 的 `when (homeSurface)` 是全工程唯一一处对 `HomeSurface` 的 when，要补 `Edit` 分支，处理方式同 Feed（reveal 保持关闭）。
  - 先例：现有编辑器本来就拦截返回（`HomeScreen.kt:94`），`docs/adaptive-principles.md:145` 也写了「Home 的编辑态也按 `ShellBackOwner` 门控」。
- **P1（视 Q8）**：新建 `ui/navigation/back/InPageStateBackHandler.kt`，作为返回基础设施里的包装层。功能代码里不出现裸 `PredictiveBackHandler`。
  - 手势期间 `P.snapTo(1f − YoinMotion.backGestureEasing.transform(e.progress))`（`Motion.kt:100`），全程缓动跟手，不设上限，也没有追赶协程。
  - 只 scrub draw 阶段的东西：底板、徽标、header 交叉淡化、底栏 lerp。托盘、占位块、页尾入口属于布局，只在提交时变化（SKILL 不变式 3）。不做整页缩放，因为 0.9 缩放是页面 pop 的语言（adaptive §7）。
  - 提交 `animateTo(0)` 和取消 `animateTo(1)` 都用 `stageSettleSpring()`。三键导航收到空 flow 时直接走提交动画。`CancellationException` 必须重新抛出。
  - **合规**：
    - AGENTS.md 的 RootSection 一节写着 "Do not intercept root back for local UI behavior" 和 "Do not wrap it in a local predictive back surface"，`SKILL.md` 分类表里写的是 "Never intercept"。
    - P0 的离散处理器有上面的先例，但 P1 的 scrub 与这两句字面冲突，需要 owner 豁免（Q8）。
    - 获批后，在 AGENTS.md 的 RootSection 下和 SKILL.md 的表下各加一行例外：「Home 编辑态是 In-page state machine，只在 `HomeSurface.Edit` 时启用」。

### 2.6 卡片长按与空白长按的语义分工

| 手势 | 普通态 | 编辑态 |
|---|---|---|
| 点卡片 | 打开或播放（不变） | 不打开；块（或卡）kick，把手脉冲 |
| 长按卡片 | **进入编辑，并原地拿起所在 section**（Q2 推荐 (a)） | 按住 200ms 后拿起该 section |
| 长按 section 标题或 section 内的缝隙 | 进入编辑，拿起该 section | 拿起 |
| 长按空白、header 标题、页边 | 进入编辑，不拿起 | 无反应 |
| 鼠标副键点按 | 进入编辑并选中该块，不拿起 | 无反应 |
| 鼠标悬停在块上 | — | 把手提亮 |
| 点空白 | 无反应 | 退出（止住惯性滚动的那一下除外） |
| 拖把手 | — | 越过 slop 立即拿起，收成签条 |
| 在块主体上快速竖划 | 滚动 | 滚动（200ms 内越过 slop 时检测器放弃） |
| RA 横滑 | 横向滚动 | 禁用 |
| 顶部下拉 | 打开 Memories | 只有系统 overscroll |
| 点隐藏键 / 点托盘行 | — | 隐藏 / 显示 |
| 底栏 Home 长按（P1） | 进入编辑 | 该槽已变成 Undo/Add |
| 底栏 Library 长按 | 目录搜索（不变） | 禁用（该槽已变成 Done） |
| TalkBack | 卡片「双击并按住」→ Edit Home；标题提供 customAction "Edit Home" | 每块提供 Move up / Move down / Hide，`stateDescription = "Section 2 of 4"`；托盘行提供 Show |

Library 页不受影响：歌曲行长按仍是「加入歌单」（`ui/library/LibraryScreen.kt:1306/1709/2027`）。如果 Q2 选 (a)，`docs/haptic-feedback.md:59` 的「长按卡片（呼出上下文菜单）」要同步改为「Home 卡片长按进入编辑」。

### 2.7 自适应

| 窗口 | Home 档位 | 底板外扩（水平/竖直） | 签条高×宽 | Θ 示例（整块） | 底栏编辑姿态 | 备注 |
|---|---|---|---|---|---|---|
| 手机竖屏 360–412 | Compact | 8 / 6dp | 64dp × 内容宽 | 0.30–0.58° | 竖屏栏两半 `[Undo\|Add][Done]` | 整块模式下，压到栏下的块不抖 |
| 手机横屏（高 < 480，EdgeSplit） | Landscape（页边 24dp） | 8 / 3dp | `h_s` 按公式取 48–64dp；7 个签条时启用栈滚动 | ≈0.2° | 导航胶囊里的图标交叉替换，pill 胶囊按 idle 路径平移退场 | header 提示行放不下就省略 |
| **Pixel Tablet 竖屏 800** | Medium（`HomeFeedFrame` Capped：内容 688，页边各 56dp） | 8 / 6dp | 64 × 560 | ≈0.28° | CenteredBar 两半 | 外层 Box 的检测器覆盖两侧页边（长按进入、点按退出） |
| **Pixel Tablet 横屏 1280** | Wide（页边 32dp，内容 1216） | 8 / 6dp | 64 × 560，左对齐 32dp 线 | ≈0.18° | CenteredBar wideMargin 两半 | Q3 选 (b) 时这里改为原尺寸拖动 |
| 1280 + 详情列（shell 600，按 Medium） | Medium | 同上 | 同上 | — | 合并姿态 `[Undo\|Add][Done][Play ▾][Shuffle]`，pill 折起 | 返回先退出编辑 |
| 1280 + NP 侧栏（Home 860，navOnly） | Wide | 同上 | 同上 | — | 只显示图标 | — |
| NP 侧栏 + 详情列（shell 376） | Compact | 同手机 | 同手机 | — | 合并 + navOnly | — |

- 档位只在静止时切换。签条几何（`h_s`、安全区）在起拖那一刻计算一次。
- 按 adaptive §8（`docs/adaptive-principles.md:136-139`）的要求拆分纯函数：
  - 档位判断（Landscape、Compact、Medium、Wide → 底板外扩档、签条尺寸档）放进 `WindowAdaptiveRuntime` 的纯函数，并加单测；
  - `ui/home/edit/HomeEditGeometry.kt` 只放与档位无关的几何纯函数：签条 `h_s`、安全区、起点矩形交集、锚定钳制、蓄力矩形。
- Activities 的卡数随容器宽度变化（「重做 Home」会话的 `HomeFeedDensity`）。本设计只依赖实测的块矩形，不受影响。
- **真机 QA**：按全局规则在 Pixel Tablet 上做，adb 走 :5038。
  - 竖屏、横屏都要测。
  - 手机宽度用 `wm size 1080x2400` 加 `wm density 420` 模拟，测完**必须**执行 `wm size reset` 和 `wm density reset`。
  - 再接上鼠标，测一遍副键进入和悬停。
- **Pixel Tablet 没有振动马达**：2026-10-03 实测 `cmd vibrator_manager list` 返回 "No vibrator found"。平板上的每个触感时刻都靠 §2.8 的视觉孪生表达；触感本身只能在手机上验收（§7）。

### 2.8 触感总表（统一取代组件目录 §0(e) 和三个方案各自的映射）

| 时机 | 方法 | 常量（API） | 低版本回退 | 视觉孪生（平板没有马达时，能看到的等价物） |
|---|---|---|---|---|
| 普通态长按到阈值 | `performLongPress()` | LONG_PRESS | — | lift 1.02 + 底板从按点长出 |
| 编辑态拾起 | **新** `performDragStart()` | DRAG_START（34） | CONTEXT_CLICK | lift + 阴影 |
| 签条每换一格 | **新** `performSegmentTick()` | SEGMENT_TICK（34） | CLOCK_TICK | 邻居让位 + 落点洞移动 |
| 首次越界 | **新** `performThreshold()` | GESTURE_THRESHOLD_ACTIVATE（34） | TEXT_HANDLE_MOVE（27，即现有的 `performLightTick()`） | 橡皮筋阻尼 |
| 放下且顺序变了 / Done | `performConfirm()` | CONFIRM（30） | 沿用现有的 API < 30 分支 | 展开 + 放下 kick / 底栏 morph |
| 原地放下、点块、滚动 | 无 | — | — | 0.5Θ kick + 把手脉冲 |
| 隐藏 / 显示 | **新** `performToggle(on)` | TOGGLE_OFF / TOGGLE_ON（34） | CONTEXT_CLICK | 缩放淡出 / 淡入 + 0.35Θ kick |
| Reset | `performReject()` | REJECT（30） | — | 各块回位 |
| Undo / Add（栏按钮） | `performClick()` | KEYBOARD_TAP | — | 栏按钮的按压形变 |
| **普通态点卡片** | **无**。删掉 JBI 点按里的 `performContextClick`（`HomeWidgetGrid.kt` 当前 `:301/:427`），依据 `docs/haptic-feedback.md` §D | — | — | — |
| 底栏 Home 长按（P1） | `performLongPress()`，实现方式见表下说明 | — | — | 冒出编辑图标 |

**底栏 Home 长按的实现**：
- M3 的 `FilledIconButton` 没有 `onLongClick`。Home 槽要改成与 `LibraryButton`（`:629`）同构的 `Surface + combinedClickable(hapticFeedbackEnabled = false)`，并保留 `homeInteraction`，让邻居挤压照常工作。
- 顺带修掉 Library 键现有的双震：`combinedClickable` 默认会震一次，`:332/:483` 又调了一次 `performContextClick`。

新方法都写在 `ui/experience/Haptics.kt` 里，用 `Build.VERSION_CODES.UPSIDE_DOWN_CAKE` 判断版本（minSdk 26）。

---

## 3. 可移动元素矩阵

| 元素 | 移动 | 隐藏 | 尺寸 | 钉住 | 为什么固定 / 能动 |
|---|---|---|---|---|---|
| header 标题 "Home" | — | — | — | — | 属于页面 chrome，不属于 section；编辑时交叉淡化成 "Edit Home" |
| Memories 入口（chevron 或记忆胶囊） | — | 编辑时淡出（不算用户隐藏） | — | — | 是 ShellOverlayUp 的入口，和下拉手势共用一个控制器 |
| 设置齿轮 | — | 编辑时淡出 | — | — | 全局入口，Settings 是推入页 |
| Activities section | ✔ | ✔（进托盘） | — | — | 顺序和开关是用户数据（`HomeSectionPref`） |
| Jump Back In section | ✔ | ✔ | Q5 (b)：行数档 2/3/4 | — | 同上；为空时编辑态显示占位块 |
| Recently Added section | ✔ | ✔ | — | — | 同上；Apple 在 P1 数据修复前恒为空，显示占位块，用户可以把它收起来 |
| 新版块（久别重逢、常听前三、常听单曲等） | ✔ | ✔ | v2 候选（密度、数量） | — | 新 section，默认开关见 §6 和 Q6a |
| Activities 的 hero/small/wide/strip | 跟随 section | ✗ | ✗ | ✗（v2 可以从详情页钉成格子） | 由 `activity_events` 按时间生成并去重，存下的顺序会被下一次播放冲掉 |
| JBI 1×1 / 1×2 格 | 跟随 section（v1） | ✗（v1；Q5 (c) 可设为「不再推荐」） | ✗（v1） | 视 Q5 | 来自 6h 轮换池和实时信号卡，按内容 id 存位置没有意义（UH §6b）。v2 改为存「槽位」，不存内容 |
| RA 曲目格和专辑卡 | 跟随 section | ✗ | ✗ | ✗ | 7 天窗口，「最新在前」就是这个 section 的含义；版式已由 owner 定稿 |
| 底栏 / NP pill | — | — | — | — | 一个窗口一条栏（adaptive §2），只变换姿态；编辑时 pill 折起 |
| 托盘行 | 不可拖（v1；P1 候选见 §2.3 末尾） | 本身就是已隐藏的 | — | — | 点 + 回到原位置 |
| 页尾 "Edit Home" 入口、JBI 空位提示格 | — | — | — | — | 非手势入口；编辑时被托盘和页脚取代 |
| 离线说明行（P2） | — | — | — | — | 系统行，不是 HomeSection |

**v1 已知落差**：按住一张封面，拿起的却是整块；JBI 的格子不能删、不能钉。这和手机桌面「按住图标就拿起图标」还有距离。v1 用三件事来缓解：
- 底板从按点处长出来（§2.1.3、§2.1.4-4），交代「这张卡属于这一块」；
- 卡片级轻摆，让小东西在动（Q1）；
- 签条上带真实封面（§2.3 ②）。

要不要往格子级再走一步，见 Q5。

---

## 4. 交互暗示清单（抖动之外）

**总预算**：
- 常驻的非手势入口最多一个。
- 「普通态下静止时的自发运动」类提示一律默认关闭。
- 编辑态里的轻摆不属于这一类，由 Q1 决定。

| 手段 | 触发条件 | 次数上限 |
|---|---|---|
| 蓄力缩放 + 底板预显（§2.1.3） | 按住超过 T/2，只作用于被按住的块 | 不限，它本身就是反馈 |
| 入场涟漪 + kick（Q1 选 b/c 时还有轻摆） | 每次进入编辑 | 不限，属于状态变化 |
| 编辑态标题 "Edit Home" | 编辑态全程（读屏会播报） | 不限 |
| header 提示行 "Drag to reorder"（Copilot 直接在编辑界面里写出手势：https://mobbin.com/flows/c3c4ab61-263b-4512-b584-7384be28bab0 ） | 前 2 次编辑会话，放得下才显示 | 每台设备 2 次 |
| **feed 末尾常驻的 "Edit Home"** | 普通态常驻。编辑主页是低频操作，学会了也不撤下（UX 评委） | 常驻 |
| 页尾入口上的 "New" 小标 | 托盘里有还没见过的新版块；进入一次编辑后消失（已读状态单独存，见 §6） | 每个新版块一次 |
| **JBI 空位静态提示格**（Q6b 推荐） | 只在 JBI 末行本来就有空位时出现：池子不足 12 格，或平板列数让尾行空出位置。没有空位就不出现，不挤掉内容 | 首次进入编辑后永久消失 |
| 空状态自述：`HomeEmptyCard` 文案 "Press and hold anywhere to arrange Home"，以及编辑态占位块里的一行说明 | 只在为空时出现 | — |
| 底栏 Home 长按 + 冒泡（P1）：复用 `LibrarySearchShortcutHint` 的写法（`YoinButtonGroup.kt:708`），按住 240ms 后冒出编辑图标 | 每次按住 | — |
| TalkBack 动作与播报（§2.6） | 一直有 | — |
| 一次性闲置轻推（块回放一次蓄力，不旋转） | **默认关闭**，只有 Q6b 选 (c) 才启用。条件：累计访问 Home ≥ 3 次、从未编辑过、静止 2.5s、不处于 Reduced、NP 未展开 | 启用后，**所有闲置提示渠道合计**每台设备最多 2 次，首次编辑后永久关闭 |

几项的样式与参考：
- **页尾 "Edit Home"**：低调的 `TextButton`，`YoinSymbols.Edit` 18dp + labelLarge，颜色 `onSurfaceVariant`，居中，高 48dp。参考 Bevel https://mobbin.com/flows/4deecd0d-c054-452e-9d05-6bf116e0636b 、Sonos https://mobbin.com/flows/88994b62-1120-4c32-8b91-ea634278a75c 、Craft https://mobbin.com/flows/7a52f30e-f8b9-4f32-ba1a-cb07de8f95e3
- **JBI 空位提示格**：
  - 1×1 格，Card 形状，`surfaceContainerHigh`，内容为 `DragHandle` 24dp + "Press and hold to arrange"（labelMedium，`onSurfaceVariant`）。
  - **没有任何动画**，不加虚线。点按即进入编辑。
  - 它在原地教手势，不需要覆盖层。参考 Notion 的 "Tap and hold to set up"：https://mobbin.com/screens/aaec51cd-8a6d-4e32-8c54-5c5641397cc7

**计数存储**：按设备存，不进 Room，也不按 profile 区分，因为学会手势的是人，不是账号。
- 复用现有的 `yoin_ui_hints` SharedPreferences 文件。
- 新建 `HomeEditHintStore` 接口，附一个 InMemory 测试实现，写法照 `NowPlayingPresentation.kt:374-401` 的 `LyricHintStore` / `SharedPrefsLyricHintStore`。
- 键名：`home_edit_sessions`、`home_edit_hint_lines`、`home_idle_nudges`、`home_hint_tile_dismissed`、`home_sections_seen`（id 集合）。

### 4.1 范围与分工

- **本稿只管编辑态的暗示**：怎么发现、怎么进入、进入后怎么操作。
- **feed 本身的信息层级和提示归「Home 提示」会话**（`HomeHintVariant.kt`、`HomeMemoryPill.kt`），包括记忆胶囊的 Ghost 形态、JBI 封面随宽度适配、下拉 Memories 的暗示。本稿和它只有两处接口：
  - 记忆胶囊注册为排除区；
  - 编辑时随 P 淡出。
- 两个成本低、完全静态的 feed 暗示候选，由 Q11 决定归谁做：
  1. **播放字形**：JBI 里点了就直接播放的格（`HomeWidgetTarget.PlaySong`），在封面角上画 `YoinSymbols.PlayFilled` 16dp，区分「点了就播」和「点了打开」。参考 Spotify DJ 格上的行内播放三角：https://mobbin.com/screens/c2609ecf-234e-4f04-af1d-18199a45f2d5
  2. **新内容点**：自上次打开以来有新曲目的歌单格，加一个 6dp 圆点（同一参考里的新内容蓝点）。需要按 profile 存已读状态。

---

## 5. 新组件目录

通用规则：
- 新 section 在编辑态下都按块处理：可以移动、隐藏，会 kick 或轻摆。
- 卡面点按一律不震。
- 封面一律不加描边。
- 横向滚动的组件必须三件齐用：`ignoreParentHorizontalPadding` + 与页边对齐的 `contentPadding` + `horizontalEdgeFadeOnScroll`。
- 有几个私有函数要先放开：`rememberActivityCardColors`（`HomeEditorialContent.kt:922`）、`HomeEmptyCard`（`:1472`）、`ActivityCardColors` 都是 `private`。新文件要复用，就改成 `internal`，或者挪到 `ui/home` 下的共享文件。

### P0（随编辑态一起发，不改表结构）

**久别重逢 Rediscover**（新 HomeSection，id `rediscover`；边界见 Q9）

- **定位**：把你打过高分、但在 Yoin 里很久没**播放**的专辑送回首页。每张卡用一行可以核实的理由说明为什么是它。点按打开专辑详情，不进阁楼，以此与 Memory 区分开：Memory 以策展为主，点按进阁楼。
- **数据**（三家都只用本地）：
  - **为什么要改候选构建**：
    - `AlbumMemoryCandidateBuilder.build()` 在 `:92` 做了 `.filter(AlbumMemoryCandidate::isMemoryEligible)`，只返回合格的候选。
    - 合格条件是覆盖率 ≥ 0.6、或有专辑短评、或笔记数 ≥ 2（`:132-134`）。
    - 所以一张只有专辑分 9.0、没写短评、覆盖率不到 60%、笔记少于 2 条的专辑，现在永远进不了候选。
  - **候选构建怎么改**：新增 `build(limit, includeIneligible: Boolean = false)`，Repository 对应提供 `getAlbumMemoryCandidates(limit, includeIneligible)`。HomeViewModel 只构建**一次**（`includeIneligible = true`），再从结果里派生两份：
    - eligible 子集取前 48，供记忆胶囊和 JBI memory 卡用。这一份和现在逐字节一致，Memory 链路不变。
    - 全集供久别重逢用，以后也供 P2「差一点成回忆」用。
  - **时间口径**：
    - 现在的 `lastPlayedAt`/`firstPlayedAt` 合并了 `activity_events` 里所有 ALBUM 事件，包括 VISITED（`:71-80`，`:123-124` 的注释也写明了）。只要打开过一次专辑详情页，90 天计时就会被重置。
    - 所以候选模型新增 `lastPlayedFromHistoryAt` / `firstPlayedFromHistoryAt`，只取 `PlayHistoryDao.getAlbumAggregates` 的 MIN/MAX(playedAt)。
    - 久别重逢的入选、排序和两行文案只读这两个字段。只访问过、没播放过的专辑不进入久别重逢。
  - **分数**：用 `albumRating`（> 0）；没有时用 `averageSongRating`，但只在 `ratingCoverage ≥ 0.6` 时采用。刻度 0–10。
  - **入选**：分数 ≥ 8.0，且 `now − lastPlayedFromHistoryAt ≥ 90d`。
  - **排序**：先按分数降序，再按 `lastPlayedFromHistoryAt` 升序。
  - **去重**：排除 JBI memory 1×2 卡用的 albumId，以及 header 记忆胶囊的 `pill.latest.albumId`。记忆胶囊已经是生产默认。
  - **离场触发**：
    - Home 现在唯一的候选重算源是 `observeMemorySignalStamp`（`YoinRepository.kt:1470`）。它只看笔记、单曲评分、专辑评分三张表，不看 `play_history`。
    - 所以新增 `PlayHistoryDao.observeMostRecent(profileId, provider): Flow<PlayHistory?>`。最新一行的 `albumId` 命中当前久别重逢列表时，在 VM 里本地移除该卡。
    - **不要**把 play_history 的变更戳并进 `observeMemorySignalStamp`，否则每次切歌都会重建全部候选。
    - 编辑态冻结期间，这次移除同样排队。
  - Spotify 和 Apple 的「多久没听」只统计 Yoin 里的播放，文案写 "in Yoin"。
- **尺寸**：
  - 卡片：`Surface(YoinContainerShapes.Card)`，高度固定 132dp × fontScale。不用 IntrinsicSize，否则 MarqueeText 会崩。容器色用 `rememberActivityCardColors`。
  - 左侧：Bun 背板 104dp，封面占 72%。
  - 右列从上到下：
    - eyebrow "Rated 9.0 · Not played in Yoin for 7 months"（labelMedium；暗色取 accent，亮色取 base）；
    - 标题 `MarqueeText`（titleLarge SemiBold 20sp）；
    - 艺人（bodyMedium）；
    - 底部 "First played 2025.11 · 23 plays"（labelSmall，等宽数字；首次时间取自 history，次数取自 `getAlbumAggregates`）。
  - 排布：
    - Compact：横向 LazyRow，卡宽 0.86 × 内容宽，最多 6 张；只有 1 张时铺满，不能滑；
    - Medium：2 张并排；
    - Wide：3 张并排；
    - 手机横屏：2 张。
- **编辑态**：按块处理；没有候选时显示占位块，文案 "Rate an album 8 or higher — when it's been a while, it comes back here"。
- **动效**：播放过这张专辑后，卡片用 `animateItem` 离场（effects 淡出 + spatial 补位）。
- **默认开关**：见 §6 和 Q6a。
- **Mobbin 参考**：
  - https://mobbin.com/screens/9290ae13-674b-455e-beda-ac34ca81425f （Apple Music 的理由 eyebrow）
  - https://mobbin.com/screens/9f4bc387-3b41-41f1-a4df-e46ba04e59d7 （TIDAL）
  - https://mobbin.com/screens/28c8d8ce-d982-4dac-ad6f-048dd77ecab6 （Pandora 单张卡的密度）
  - https://mobbin.com/screens/f3743197-453a-4ee5-a134-83305631c6ad （Spotify 诚实的「数据不足」文案）

### P1

**接着听**（增强 Activities hero）

- **定位**：没在播放时，hero 卡上出现一个续播键，从上次停下的那首歌开始，在原来的专辑或歌单里接着放。
- **数据**：
  - 本地：用 `getRecentHistory(1)`（`YoinRepository.kt:2287`）与 `selectHomeHeroActivity`（`HomeEditorialContent.kt:1522`）比对，判断最后一首是否属于这个 hero。
  - 播放：三家统一调用 `PlaybackManager.play(tracks, startIndex, source, ActivityContext(...))`。Spotify 的上下文播放由 PlaybackManager 内部路由（`buildSpotifyContextPlaybackFn` → `startContextPlayback`），Home 不做向下转型。
  - Spotify 的「上次停在哪」优先读 recently-played，走 Repository 现有的 `getSpotifyRecentActivities` 路径。
  - Apple 的最后一首如果是没有目录版本的导入曲目，就不显示续播键。
  - Home 只订阅一个窄投影 `HomePlaybackProjection(isIdle, contextKey)`，并 `distinctUntilChanged`，遵守 NP position 去重的不变式。
- **尺寸**：48dp `FilledIconButton`，形状用 `IconButtonDefaults.shapes()`，容器取 `palette.baseColor`。eyebrow 改为 `Stopped at “Song” · 2h ago`。
- **与底栏的耦合**（修正评委指出的问题）：
  - 按钮宽度 48↔0 和标题 end padding 56↔0 **跟随底栏的 idle 进度**，不自己另起动画。
  - 做法是把底栏的 `idleProgress` 提升到 `ExperienceSessionStore` 成为 Animatable，只由 NavHost 驱动。底栏和 hero 都只读它，在 layout 或 draw 阶段读取。
- **编辑态**：跟随 Activities 块；续播键隐藏。
- **Mobbin 参考**：https://mobbin.com/screens/b0868f3e-6205-402c-8c19-eb7d82633b68 、https://mobbin.com/screens/d7a177a7-685c-4bba-a27a-cf2e4053ca06 、https://mobbin.com/screens/8479afa0-da63-4b51-8070-e5dab2c51a7a

**常听前三 Most Played**（新 HomeSection，id `most_played`，默认进托盘）

- **定位**：最近 30 天在 Yoin 里播放最多的专辑，用领奖台式的尺寸差和名次数字来排。
- **数据**（只用本地）：
  - 新 DAO `PlayHistoryDao.getTopAlbumsBetween(profileId, provider, since, until, limit)`，命中 `(profileId, provider, playedAt)` 索引。
  - 用上一期窗口 [−60d, −30d) 来标 ↑、↓ 或 "New"。
  - 不接这些远端数据：Subsonic 的 `frequent`（依赖 scrobble）、Spotify `/me/top`（缺 scope）、Apple heavy-rotation（未接线）。
  - 门槛：第一名至少 3 次，并且至少有 2 张专辑各 ≥ 2 次。
  - 文案 "▷ 14 plays in Yoin"。
- **尺寸**（修正评委指出的问题）：
  - 封面尺寸由可用宽度算出：`c1 = min(120dp, (w − 2·12dp) / 3.01)`，`c2 = 0.8·c1`，`c3 = 0.667·c1`。
  - 每个 item 的盒宽 = 1.22 × 封面宽。名次数字画在盒内左侧、压在封面后面，**不越出页边线**。
  - 360dp 宽的手机（内容宽 328dp）上 c1 ≈ 101dp，一行放得下。
  - 名次变化引起的尺寸动画，在 `Modifier.layout` 或 `graphicsLayer` 里读 Animatable，**不用** `animateDpAsState`，避免在组合阶段逐帧读值。
  - Medium 显示前 4、Wide 显示前 5，公式按项数推广。
- **编辑态**：按块处理。
- **Mobbin 参考**：https://mobbin.com/screens/6d31e5b3-9213-4bc7-b894-96c0c3ca02e8 、https://mobbin.com/screens/dfe864b5-f474-4b87-8e34-8ad576677191 、https://mobbin.com/screens/ec5c8c61-7012-416a-b2b0-52ae8993d2f9 、https://mobbin.com/screens/bfbf738f-50f8-4feb-a557-98e05d9b0d5c 、https://mobbin.com/screens/2e228091-87ca-4b95-bde7-b4fd5c4c6799

**常听单曲 Your Tracks**（新 HomeSection，id `your_tracks`，默认开关按 Q6a；Q9 选 (b) 时提前到 P0）

- **定位**：主页现在以专辑为主。这一块提供曲目级的入口：你在 Yoin 里反复听的、打了高分的、写过笔记的单曲，点一下就从这首开始播。
- **数据**（只用本地）：
  - 新 DAO `PlayHistoryDao.getTopTracksBetween(profileId, provider, since, until, limit)`：按 songId 分组，取最近一行的 title/artist/album/albumId/coverArtId/durationMs，命中 `(profileId, provider, playedAt)` 索引。
  - 候选按以下顺序取，去重后最多 16 首（4 列 × 4 行）：
    1. 30 天内播放 ≥ 2 次的曲目，按次数降序；
    2. 本地评分 ≥ 8.0 的曲目（`local_ratings` 关联最近一行 `play_history` 取元数据）；
    3. 有笔记的曲目（`song_notes` 自带 title/artist，封面取最近一行 `play_history`）。
  - 不足 4 首时按空处理。
  - 待核实：用 `play_history` 行还原出的 `Track`，是否满足三家 `handleFor` 的最小字段要求。Apple 导入曲目沿用现有的灰显规则。
- **交互**：
  - 点一行 → `PlaybackManager.play(本块曲目, startIndex = 该行, source)`。
  - 正在播放的那一行，标题用 `primary` 色，静态，不加跳动的均衡器。
- **尺寸**：
  - 横向分页的曲目列，每列 4 行，行高 56dp × fontScale。
  - 封面 40dp，用 Thumb 形状 token（4dp）。
  - 标题 bodyLarge 单行；艺人 bodyMedium，`onSurfaceVariant`。
  - 行尾字形三选一：
    - 评分 ≥ 8：`YoinSymbols.StarFilled` 14dp + "9.0"（labelMedium，等宽数字，取色同久别重逢）；
    - 有笔记：`YoinSymbols.Note` 14dp；
    - 其余："6 plays"。
  - 列宽：Compact 为 0.86 × 内容宽，露出下一列的开头；Medium 2 列 + 露头；Wide 3 列。用 LazyRow + snap 分页 + 横滑三件套。
- **编辑态**：按块处理；空时占位块文案为 "Play a few tracks — your repeats show up here"。
- **Mobbin 参考**：
  - https://mobbin.com/screens/a84ec650-303c-418a-a02f-1d834dea959a （YTM Quick picks：分页曲目列 + Play all）
  - https://mobbin.com/screens/b0868f3e-6205-402c-8c19-eb7d82633b68 （Spotify Start listening）
  - https://mobbin.com/screens/3d34a415-571a-48ec-a251-794b75407811 （Apple Music Latest Songs 分页列）

**换一批 Reshuffle**（增强 JBI 标题行）

- **定位**：立即轮换随机池。信号卡原地不动，用「哪些没动」告诉用户哪些是自己的。
- **数据**：
  - 新增 public 的 `HomeViewModel.reshuffleGrid()`：
    - 在 viewModelScope 里调用现有的 private `fetchAndPersistGridPools()`（`HomeViewModel.kt:445`）。它无参，本身就是强制拉取；TTL 判断在 `resolveWidgetGrid` 里。
    - 再调用 `buildWidgetGrid(snapshot, 当前 MemorySignals)`，更新 `Content.widgetGrid` 和 `homeContentCache`。
    - 编辑态冻结期间不可调用。
  - Subsonic：`getAlbumList("random")` + `getRandomSongs`。
  - Spotify：打乱 Room 里的缓存。
  - **Apple**：要先修 `getAlbumList`，改用随机 offset（`AppleMusicSource.kt:105-127`）；曲目改用 `getLibrarySongs(size, 随机 offset)`。修好之前不显示这个按钮。
  - 门控：`RANDOM_SONGS || LIBRARY_SONGS`。Spotify 两个都声明（2026-10-10 起 `LIBRARY_SONGS` = Liked Songs，`RANDOM_SONGS` 留给 Home 的随机池），对它恒为真，这里正好要放行它。
- **尺寸**（修正评委指出的问题）：
  - 不用负 margin，`Modifier.padding` 遇到负值会抛异常。
  - 标题行末端放一个 M3 IconButton，视觉 40dp、触控 48dp。
  - JBI 现在的结构是 `Column(spacedBy(16.dp)) { HomeSectionTitle; AnimatedContent(网格) }`。改成 48dp 高的标题行，并把标题行与网格之间的 16dp 收为 0。标题约 24dp 高，所以整块净增 48 − (24 + 16) = **8dp**。
- **失败反馈**：不插入文字把内容顶开。图标用 `homeEditKickSpring()` 抖一下，交叉淡化成 `CloudOffline` 保持 2 秒，再 `performReject()`。
- **成功动效**：
  - 图标转一圈（`defaultSpatialSpec()`，只转一次）；
  - 旧格缩到 0.9 并淡出，新格从 0.9 弹到 1，按格错峰 24ms。
- **编辑态**：隐藏。
- **Mobbin 参考**：https://mobbin.com/screens/4a107313-9449-45ba-8019-d00519ec59b5 （Calm 的 Refresh Recommendations）

### P2

| 组件 | 定位 | 数据（Subsonic / Spotify / Apple / 本地） | 尺寸 | 编辑态 | 修正 | Mobbin |
|---|---|---|---|---|---|---|
| **听歌日历** | 一个月一页的封面日历，是日记，不是统计 | 三家都只用本地；新 DAO `getDailyAlbumPlays`（按本地日期分组，每天取播放最多的那张专辑） | section，两档：两周 / 整月；7 列，格宽 `clamp((w − 6·gap)/7, 40dp, 72dp)` | 块 | **去掉「连续天数胶囊」**，它是被否决的 Streak 的变体；只保留 "Listened 18 days" 这一句 | https://mobbin.com/screens/e718ba60-f5f3-466a-8472-2d084cd350b3 、https://mobbin.com/screens/77dae2c3-cf8d-410a-9e0f-465789c68f00 、https://mobbin.com/screens/769caf89-a612-4153-ba42-4419960f7d98 、https://mobbin.com/screens/483648e0-d2c3-47ed-9c05-384c4d078d49 |
| **差一点成回忆** | 常听、但还没成为 Memory 的专辑 | 取 P0-9 改造后的候选全集（`includeIneligible = true`）里 `!isMemoryEligible` 的项 | JBI 信号卡 1×1 / 1×2 | 跟随 JBI（v1） | **文案要覆盖成为 Memory 的三种途径**（评分覆盖率 ≥ 60%、有专辑短评、笔记数达到门槛）："Rate 3 more tracks, or write a short review, to make it a Memory"。进度点**不用** `LinearWavyProgressIndicator`，它的波形会一直流动 | https://mobbin.com/screens/b7578a37-b5f0-4921-a9c2-3b42bad94eb9 （Duolingo，只借它表达进度的方式）、https://mobbin.com/screens/c1fe70c1-c102-41c8-9352-15f5897055f5 （Apple Fitness） |
| **离线说明行** | 自建服务器连不上时，主页不再整页报错；能靠本地撑住的版块照常显示 | 三家的 `ping()`；Spotify 要区分 401、需要重连、被限流三种情况，这三种都不算离线 | header 下方单独一行，高 48dp，不套卡片 | 固定，不参与排序 | 依赖 P1-0 的 HomeUiState 按版块重构 | https://mobbin.com/screens/ac1946e9-29ef-4953-a433-d073ed7bb066 （Spotify 离线时的行内说明）、https://mobbin.com/screens/943c8668-1883-4e94-8d59-279d9f5e5f16 （Pandora 冷启动的诚实文案，可借给新 profile 的空 Home） |
| **那天在听** | 一年前的今天你在听什么；历史不足一年时改看一个月前 | 本地新 DAO `getAlbumsPlayedBetween`，按时间范围查询以命中索引，不用 strftime | JBI 信号卡 1×2 | 跟随 JBI | 历史不满一年时会长期为空，这是如实的；作为边界情况，它不进 Memories 主 deck | https://mobbin.com/screens/a32b6b5c-3da9-4d88-ae47-b795c5f510a9 、https://mobbin.com/screens/a9768b9f-0911-498d-b64c-ee59b1c70fa8 、https://mobbin.com/screens/5c79bb05-539c-4599-a4d9-10d3eae073c2 |
| **库内漫游 Drift** | 一键开一条只用你自己曲库的无尽电台，在「Familiar」和「Forgotten」之间二选一 | 见表下说明 | section，不做成格子，避免把可点的控件嵌进可拖的格子里；单卡高 120dp × fontScale，Panel 容器；左侧 56dp 播放键，右侧标题 "Drift" + 一行说明；底部是 M3 `SingleChoiceSegmentedButtonRow` 两段 "Familiar \| Forgotten" | 块；播放键和切换在编辑态禁用 | 播放中播放键换成暂停字形，不加跳动的均衡器 | https://mobbin.com/screens/82d59a7d-4a79-412e-b2e1-7beb545c9d96 （Deezer Flow 格）、https://mobbin.com/screens/9d0a3cc3-2c05-4211-9508-484b45a1d991 （Deezer 的发现/熟悉切换）、https://mobbin.com/screens/ff23447d-714c-4d4a-abbf-baf91063ec82 （Amazon My Soundtrack） |

**库内漫游的数据**：
- Subsonic：`getRandomSongs(50)`，再按本地信号重排。Familiar 优先评分 ≥ 8 或 90 天内播过的；Forgotten 优先 Yoin 里 90 天没播过的。
- Apple：`getLibrarySongs(size, 随机 offset)`，依赖 P1-3。
- **Spotify 关闭**：App Remote 上任意队列的语义未验证，由 `ServiceFeatureCatalog` 关掉。
- 队列快播完时续取 50 首。
- 门控：`RANDOM_SONGS || LIBRARY_SONGS`。注意 Spotify 两个都声明（2026-10-10 起 `LIBRARY_SONGS` = Liked Songs），这个门控挡不住它；关掉 Spotify 只能靠 `ServiceFeatureCatalog` 上的显式标记（照 `supportsYoinCast`、`favoritesAreLibrary` 的做法），不能靠能力组合。

### 格子层（范围由 Q5 决定）

**Q5 (b) section 尺寸档**：JBI 提供 2/3/4 行三档，存进 `HomeSectionPref.config`。这相当于桌面「改小组件尺寸」在 section 级的对应物。编辑态在 JBI 标题行放一个三段选择。参考 Tiimo 同一组件的 L/M/S 三档：https://mobbin.com/screens/328f199c-2624-429a-aa4f-60ebf1ada74d

**Q5 (c) 格子「−」**：编辑态下，每个生成格带一个 `Close` 徽标，含义是「不再推荐」。被移除的格进本地排除表（存进 `HomeSectionPref.config`，上限 200 个 id），由池子补位，不存位置。

**Q5 (d) 完整格子层（v2）**：钉选格 Pinned Tile + 1×1/1×2 尺寸 + 槽位模型。
- **交互**：
  - 详情页的 ▾ 菜单加一项 "Pin to Home"。钉住后，菜单项文字原地翻转成 "Unpin from Home"，并 `performConfirm()`。**不弹 HUD**，因为交界处禁止覆盖层。
  - 格子的抖动已经由卡片级覆盖。
  - 每格只有一个徽标：钉选格用 `Close`（取消钉选），生成格用 `VisibilityOff`。其他操作放进选中后弹出的 `YoinDropdownMenu`。
  - 移除生成格用「墓碑 + 原地 Undo」，参考 Character AI：https://mobbin.com/screens/55f57d8b-572c-4b93-acca-3a0865b904e9
- **前置**：yoin-symbols 先补 `Pin`/`PinFilled`。
- **Mobbin 参考**：https://mobbin.com/flows/356505f4-3908-4cbe-8b69-6cc7134fb8ef 、https://mobbin.com/flows/88994b62-1120-4c32-8b91-ea634278a75c 、https://mobbin.com/screens/73f2b851-60b7-40e5-8fe8-242352d374f2 、https://mobbin.com/flows/63f21f42-fd95-4309-809b-9a14fe7522c4 、https://mobbin.com/screens/868d3701-d08a-4106-b524-1ee7db8aa8c6

### 考虑过但不做

| 组件 | 不做的理由 |
|---|---|
| 「按住整理」提示格的**摆动版**（DragHandle ±6° 摆动） | 属于普通态下静止时的自发运动。去掉动画的静态版已经放进 §4（Q6b） |
| 库里还没听的 | 只能按艺人名聚合，feat. 和合作名拆不准；Spotify 每个种子要 1 次请求再加最多 6 页，受限流闸门约束；Spotify 的「库里」语义和另外两家不一致 |
| 组件目录里已否的项（保持否决） | 听歌分钟数和 KPI（口径不诚实）；年度回顾故事页；连续打卡与冻结；流派入口格；服务器上也在听；情绪转盘；外部推荐和相似电台（三家都没有可用的 API）；顶部筛选 chips；顶部提示横幅；RA 音质角标；常驻跳动的均衡器；按时段分桶的「此刻常听」；Memories 预告卡（owner 已永久退役）；歌词一句卡；多实例版块；常听艺人圆头像排；单卡「点按行为」自定义 |

---

## 6. 数据模型与迁移

### P0：不改表结构，数据库保持 v28

以下改动都要在任何新 JSON 字段上线**之前**完成。

1. **`HomeLayoutStore` 加固**：
   - 改为 `SectionsDto(version: Int = 1, sections: List<JsonElement>)`，逐条用 `runCatching` 解码，坏一条只丢这一条。不再像现在这样，一处失败就整份回落到 Default（`HomeLayoutStore.kt:60-61`）。
   - 保持现有的 `ignoreUnknownKeys = true` / `encodeDefaults = true`（`:33-36`），不用新增。
2. **保留未知 id**：`HomeLayout` 新增 `retained: List<HomeSectionPref>`，`toPrefs()` 把它们按原顺序追加在已知项之后，防止降级再升级时丢数据（UH §3-11）。
3. **新版块的默认开关**：`HomeSection` 新增 `appendEnabled: Boolean = false`。
   - prefs 为 null（从未定制过）时，仍取 `HomeLayout.Default`，新版块按 `defaultEnabled` 处理。`HomeSection.kt:89-102` 的 reconcile 对 null 或空 prefs 本来就返回 Default。
   - prefs 不为 null（定制过）时，`reconcile` 把新版块按 `appendEnabled` 追加。追加的这些记进 `HomeLayout.newSections`，用来显示 "New" 小标。
   - **prefs 只在 draft 真正改变时才写**：移动、隐藏、显示、重置、撤销。单纯进入编辑不写。否则一次误触长按，就会把用户永久划进「已定制」，此后所有新版块都追加为关闭，Q6a 的意图就落空了。
   - **"New" 的已读状态单独存**：放在 `HomeEditHintStore` 的 `home_sections_seen`（id 集合）里，进入编辑即标为已读。
   - 现有 `HomeLayoutTest` 里「追加时用默认值」的用例要同步修改。
4. **纯函数**：
   - `moved(id, toIndex)`；
   - `withEnabled(id, enabled)`；
   - `isDefault`，定义为 `sections == HomeLayout.Default.sections`，不比较 `retained`；
   - `reset()`，保留 `retained`。

   都按 `should_…` 风格写单测。
5. **孤儿行清理**：`home_layout` 没有外键级联。
   - `ProfileManager` 的构造参数新增 `homeLayoutDao: HomeLayoutDao`，或者一个 `onProfileDeleted: suspend (String) -> Unit` 回调，由 `AppContainer` 注入。
   - 在 `ProfileManager.delete`（`:227-244`）里调用 `HomeLayoutDao.delete(profileId)`。
   - `ProfileManagerTest` 里的构造调用同步更新。
6. **候选构建**（只改查询结果的组装方式，不改表）：
   - `AlbumMemoryCandidateBuilder.build(limit, includeIneligible)`；
   - 候选模型新增 `lastPlayedFromHistoryAt` / `firstPlayedFromHistoryAt`；
   - 新查询 `PlayHistoryDao.observeMostRecent(profileId, provider)`；
   - 测试要保证 eligible 子集的输出和改造前逐字节一致。
7. **新增测试**：`HomeLayoutStore` 的 JSON 往返、逐条宽松解码、未知 id 保留。
8. **只放内存的状态**：`HomeSurface.Edit`、`ExperienceSessionStore.homeEditProgress`、`HomeEditController`（draft、撤销栈、拿起/折叠/kick/轻摆状态）、VM 的冻结开关；`HomeUiState.Content` 新增 `rediscover`。
9. **按设备存的提示计数**：`HomeEditHintStore`（存于 `yoin_ui_hints`），键名见 §4。

### P1

- **P1-0，单独一个前置提交**：把 `HomeUiState.Content` 改成按版块存放，即 `sections: Map<HomeSection, SectionPayload>`，状态取值为 `Loaded / Empty / Insufficient / Unreachable`。
  - 只加载启用中的版块。
  - `HomeSection.requiredCapabilities` 在渲染时过滤，不在持久化时过滤。
  - 这次改动会大改 `HomeViewModelTest`，所以不和编辑态捆在一起。
- **新查询**：
  - `PlayHistoryDao.getTopAlbumsBetween`、`getTopTracksBetween`；
  - `observeChangeStamp`（`COUNT(*)` + `MAX(playedAt)`），供常听前三和常听单曲刷新用。

  所有查询都同时带 `profileId` 和 `provider`。
- **数据修正**（不动版式）：
  - Subsonic 的 Recently Added 改走 `getAlbumList("newest")`，给 Album DTO 加上 `created` 字段，并拆开 `SubsonicMappers.kt:48-51` 里 `addedAt` 和 starred 的混用。
  - Apple 的 Recently Added 改走 `getAlbumList("newest")`。
  - 修复 Apple 的随机 offset；`AppleMusicSource.kt:50-56` 的能力集合改为引用 `ServiceFeatureCatalog.appleMusic`。
- **Q10 选 (b) 时**：加 `MusicLibrary.getRecentlyPlayed` + `Capability.RECENTLY_PLAYED`，以及 `AlbumListKind` 枚举。

### 格子层（Q5 选 (b)/(c)/(d) 时）

- `HomeSectionPref` 新增 `config: JsonObject? = null`，旧版本靠 `ignoreUnknownKeys` 忽略它。
- 各档的 config 结构：
  - (b) `jump_back_in` 的 config 为 `{"rows": 2|3|4}`，`packWidgetRows` 接受行数参数。
  - (c) config 为 `{"excluded": [{provider, rawId}]}`，上限 200 条，先进先出。
  - (d) config 为 `{"slots":[{slotId, kind, span, entity?:{provider, rawId, type, title, subtitle, coverKey, pinnedAt}}]}`。
- `grid == null` 时仍走 `packWidgetRows`，`HomeWidgetGridPackTest` 的黄金路径不动。
- (d) 另需新增保序 packer；`refreshWidgetGridSignalCards` 改为按 `slotId` 原地替换，不再 prepend。
- provider 在 JSON 里显式写入，读取时丢掉与当前 profile 不一致的项。**不建新表**，以免和并行会话同时 bump DB 版本，撞上 `YoinDatabaseMigrationTest` 的迁移链。

---

## 7. 分阶段实现计划

### P0（约 16 人日 + 2 天 Pixel Tablet 真机 QA + 0.5 天手机触感验收）

| 步骤 | 文件 | 复用 | 测试 | 工作量 |
|---|---|---|---|---|
| P0-1 数据加固 | `data/home/HomeLayoutStore.kt`、`ui/home/HomeSection.kt`、`data/profile/ProfileManager.kt` + `AppContainer.kt`、新 `HomeEditHintStore` | `LyricHintStore` 的「接口 + InMemory」写法 | 往返、宽松解码、未知 id 保留、reconcile 追加时为关闭、只在真实改变时写 prefs、`isDefault` 忽略 `retained`、ProfileManagerTest | 1d |
| P0-2 基础 token 与图标 | `ui/experience/Haptics.kt`（4 个新方法）、`ui/theme/Motion.kt`（`homeEditKickSpring`）、`ui/theme/Shape.kt`（`PanelAnimated`）、yoin-symbols generator（`Undo`）、新 `HomeEditTokens.kt`（含 `wiggleMode` / `wiggleTarget` 两个 debug 开关；实现 Q1 选定的形态 + Kick） | — | kick 峰值的解析解、轻摆角度纯函数 | 1d |
| P0-3 会话状态与返回 | `ExperienceSessionStore.kt`、`ShellBackResolver.kt` + 测试、`YoinNavHost.kt`，具体见表下 | `RevealState.launchAnimateTo` 的写法 | resolver 优先级矩阵 | 1d |
| P0-4 手势 | 新 `ui/home/edit/HomeEditGestures.kt`、`HomeEditGeometry.kt`；`WindowAdaptiveRuntime.kt`（档位纯函数）；`HomeEditorialContent.kt` 和各卡片调用点，具体见表下 | `noRippleClickable(enabled)`、`elasticPress` | 见表下 | 2.5d |
| P0-5 编辑态视觉 | 新 `HomeEditBlock.kt`、`HomeEditTray.kt`；`HomeScreen.kt`（删 `AnimatedContent`、`BackHandler`、`isEditMode`）；`HomeEditorialContent.kt`（header 不卸载、标题零宽叠放、提示格）；`HomeViewModel.kt`（冻结） | `ExpressiveSectionPanel`、`HomeEmptyCard`（改 internal）、`animateItem` | VM 冻结（turbine）；`resolveEditLeftSlot`；蓄力矩形与 chargeLatch；header 高度在 P 全程不变 | 3d |
| P0-6 签条层 | 新 `HomeCarryStack.kt`；`SeamDissolve.kt`（`SeamFlow.hold`） | `HomeLayoutEditor` 的单拖、原地接住、按 id 作 key、累积速度、实时交换、`settleDrag`；`voteHighFrameRate` | 签条几何、起点矩形取交集、一步锚定与钳制（纯函数）；androidTest：按住后移动会折叠，放下后顺序已持久化 | 3d |
| P0-7 底栏姿态 | `YoinButtonGroup.kt`（导航右槽和合并左槽两处 Done）、`YoinChromeGroup.kt`、`YoinEdgeSplitGroup.kt`（沿用 idle 退场） | 已上线的 idle 两半几何 | `BarGeometryTest` 的 edit × idle × pane × navOnly 矩阵 | 1d |
| P0-8 无障碍与收尾 | 见表下 | — | androidTest：用自定义动作完成移动、隐藏、显示 | 1d |
| P0-9 久别重逢 | 新 `ui/home/RediscoverSection.kt`；`AlbumMemoryCandidateBuilder.kt`、`AlbumMemoryCandidate.kt`、`PlayHistoryDao.kt`、`YoinRepository.kt`、`HomeSection.kt`、`HomeViewModel.kt`、`HomeUiState.kt`；`rememberActivityCardColors` 改 internal | 候选构建、横滑三件套 | eligible 子集逐字节不变；只用 history 时间；过滤、排序、双重去重；播放后本地移除 | 2.5d |

部分步骤的细节：
- **P0-3 在 `YoinNavHost.kt` 里要做的事**：
  - hoist controller；
  - BackHandler 挂在 `if (memoriesMounted)` 之外；
  - `:746` 的 when 补分支；
  - 删除 `:834` 的 suppress；
  - 在 `LaunchedEffect(selectedSection)` 和 `LaunchedEffect(musicConfigurationRevision)` 里加 `snapExit`；
  - 外部打开 NP 或详情时退出编辑。
- **P0-4 的改动**：
  - `HomeEditorialContent.kt`：删 `detectTapGestures`，key 改为 `section.id`。
  - Activities、JBI、RA 的卡片调用点：加 `interactive`、`onLongClick` 语义和卡片级轻摆修饰符；删除 JBI 的点按触感。
  - 鼠标副键和悬停。
- **P0-4 的测试**（androidTest）：
  - 长按卡片不触发 onClick；
  - 轻点照常打开；
  - 过阈值前拖动仍是滚动；
  - 在平板页边长按能进入；
  - RA 横滑不受影响；
  - 在顶部长按不会误开 Memories；
  - 鼠标副键能进入；
  - Activities 为空时长按 RA，块不位移。
- **P0-8 的改动**：
  - TalkBack 动作与播报；
  - **Q7 选 (a) 时，和删除 `HomeLayoutEditor.kt` 放在同一个提交里**；
  - `debug/MemoriesScreenshotActivity.kt` 的签名；
  - 更新 `docs/design.md` 的 🏠 主页一节、`docs/haptic-feedback.md:59`，以及 AGENTS.md（Q8 的例外）。

相对综合稿的 12.5d，增量来自三部分：
- 久别重逢的数据修正：+1d；
- 桌面感补强：+1.5d（底板预显与生长、签条封面、卡片级轻摆）；
- 审查修正：+1d（header、两处 Done、提示存储、ProfileManager 注入、鼠标入口）。

**真机 QA**：
- Pixel Tablet：
  - 竖屏、横屏，以及用 `wm size 1080x2400` + `wm density 420` 模拟手机（测完 reset）；
  - 接上鼠标再测一遍。
- 用 Perfetto 看五个时段的帧耗时：进入、kick、轻摆（Q1 = b/c 时）、签条折叠与展开、隐藏。
- **触感验收**：平板没有马达，只能在手机上验。按 `feedback_test_apk_drive` 的流程出两种签名的 release APK，放进 Drive 的 Inbox（先删掉之前传过的 `Yoin-*.apk`），请 owner 在自己手机上把 §2.8 过一遍。

### P1（约 13–15 人日，取决于 Q8/Q10/Q11）

| 步骤 | 内容 | 工作量 |
|---|---|---|
| P1-0 | HomeUiState 按版块重构（前置提交） | 2d |
| P1-1 | `InPageStateBackHandler` 预测性返回（Q8 = b） | 1.5d |
| P1-2 | 底栏 Home 长按（改为 Surface + combinedClickable）+ 冒泡提示 + 修 Library 键双震 | 0.75d |
| P1-3 | RA 数据修正 + Apple 随机 offset；Q10 = b 时另加 `AlbumListKind` 0.5d、`getRecentlyPlayed` 1d | 1.5d（+1.5d） |
| P1-4 | 接着听（含把底栏 idle 进度提升到 store） | 2d |
| P1-5 | 常听前三 | 2d |
| P1-6 | 常听单曲 | 2d |
| P1-7 | 换一批 | 1d |
| P1-8 | 播放字形（Q11 = b） | 0.5d |

### P2 与格子层

- P2：听歌日历 2d，差一点成回忆 1.5d（候选改造已在 P0-9 完成），离线说明行 2d，那天在听 1.5d，库内漫游 2d。
- 格子层：Q5 (b) 约 3d；(c) 约 4d；(d) 12–15 人日，另加一轮 2×2 的 Figma。

### 风险与缓解

| 风险 | 缓解 |
|---|---|
| 手势冲突：Initial pass 检测器与 LazyRow、Memories 的 nested scroll、惯性滚动、header 按钮之间 | 过阈值前不消费任何事件，越过 slop 立即放弃；header 按钮和徽标用排除区；P0-4 的 androidTest 覆盖上述每一种情况 |
| 签条折叠与展开的正确性：起点矩形来源、锚定、header 可见性翻转、SeamFlow | 全部写成纯函数并单测；锚定一步完成；先在 `MemoriesScreenshotActivity` 调试页预演；`SeamFlow.hold` |
| seam 逐帧成本 | kick 和轻摆走 `drawWithContent`；graphicsLayer 缩放只用于单块的短动画（§2.0）；折叠时 feed 一侧只改 alpha；签条层从不进入交界带；用 Perfetto 实测 |
| lookahead | 不新增 `AnimatedContent(SizeTransform)`、`ButtonGroup` 容器或 SubcomposeLayout；签条层是普通 Box；底栏只做 dp-lerp；在折叠屏上复查。JBI 已有的列数 `AnimatedContent` 在编辑态不会触发 |
| 口味：抖动、签条和底板的观感 | 所有 dp、角度、频率、时长都集中在 `HomeEditTokens.kt`，debug 开关可以切换形态。先用交互原型定 Q1–Q3，再在平板上录屏确认 |
| 默认测试机无法验收触感 | 每个触感时刻配视觉孪生（§2.8）；用手机 APK 验收 |
| 无障碍回退 | TalkBack 动作和删除旧编辑器放在同一个提交里 |
| **并行会话冲突** | 见下方 ①–⑥ |

**并行会话冲突的缓解**：
1. 在独立 worktree 开发，分支 `feat/home-edit`。新逻辑尽量放在 `ui/home/edit/` 下的新文件里，热文件只留挂接点。
2. 「同窗分列」会话：等它把 `YoinButtonGroup.kt`、`YoinNavHost.kt`、`ShellBackResolver.kt` 的大块改动提交并真机验证之后，P0-3 和 P0-7 再动手，并且各自做成小提交。
3. 「Home 提示」会话（`HomeHintVariant.kt`、`HomeMemoryPill.kt`、`HomeWidgetGridAdaptive.kt`）：
   - 记忆胶囊必须注册为排除区，并跟随 P 淡出；
   - JBI 的高度和列数会变，但本设计只依赖实测高度；
   - §4.1 的 feed 暗示要先与它对齐再做。
4. 「重做 Home」会话（`HomeFeedFrame.kt`、`HomeFeedDensity.kt`、`FeedUnits.kt`）：命中测试的横向范围和底板外扩都依赖它的页边，Activities 的卡数也随宽度变化。等它合入后再接 P0-4、P0-5。
5. P0-2 要改 `Motion.kt`；P0-9 要改 `HomeViewModel.kt`、`HomeUiState.kt`、`AlbumMemoryCandidateBuilder.kt`、`YoinRepository.kt`。这些文件都有别人未提交的改动，动手前重新 grep。
6. 平板也是共享的：每一步操作前重新取坐标，测完执行 `wm size reset` 和 `wm density reset`。

---

## 8. 需要 owner 拍板的问题

### 8.0 拍板结果（2026-10-04，owner 已答）

| 题 | 结果 | 备注 |
|---|---|---|
| Q1 | **(b) 轻摆后静默 + 卡片级** | |
| Q2 | **(a)** 长按卡片直接进入编辑并拿起所在 section | |
| Q3 | **(a)** 全设备签条 | owner：签条「收拢再展开」的动画「特别好，一定要保留」——实现时按原型 `proto.js` 的参数原样移植 |
| Q4 | (a) `[Undo\|Add] [Done]`（owner 未答，按推荐） | |
| Q5 | (a) 只到 section（owner 未答，按推荐） | |
| Q6a | (a)（未答，按推荐） | |
| Q6b | **(b) 精简**：页尾常驻 Edit Home + 前 2 次的 header 提示行 | owner 否掉了「页尾虚线 + 号框」，「有 Edit Home 就够了」；不做 JBI 空位提示格、底栏 Home 长按 |
| Q7 | **(a) 删掉 `HomeLayoutEditor.kt`，重做** | |
| Q8 | **(b)** P0 离散返回 + P1 scrub 预览 | 需在 AGENTS.md / predictive-back SKILL 写例外 |
| Q9 | **(a) 做久别重逢** | |
| Q10 | **(b) 小重构**（`getRecentlyPlayed` + `RECENTLY_PLAYED`、`AlbumListKind`、Apple 能力集合引用 Catalog） | 不 scrobble、不申请 `user-top-read` |
| Q11 | **都不做**（播放字形、新内容点都不做） | |

开工时机：「重做 Home」「同窗分列」两个会话已收尾（2026-10-04），在它们未提交的工作区改动上接着做，不回退。


> **先试原型**：Q1（抖动形态）、Q2（卡片长按语义）、Q3（拖动形态）都可以在配套的交互原型里切换选项，直接试手感。建议先玩原型再答这三题。实现阶段还会在 Pixel Tablet 上录屏确认 Q1，因为平板上的可见度和网页上不一样。

**Q1 抖动形态（含「谁在动」）**

- 形态，三选一：
  - (a) **一颤**：每块进入时颤一次，约 0.5s 后静止；之后只在点块和放下时再颤。手机首屏通常只有一两块会动，平板横屏几乎看不见。
  - (b) **轻摆后静默**：约 1°、2.4Hz 的轻摆，6 秒没有触摸就渐停，任何触摸都会恢复。只在页面可见、且没开省电模式或「移除动画」时运行。
  - (c) **一直摆到退出**：iOS 式。
- 谁在动，二选一：
  - 整块：每个 section 作为一个整体转。
  - 卡片级：块内每张卡各自转，同一块内同相、正反交替。
- 两种「谁在动」在三种形态下都成立。所有组合里，交界带内的振幅都是 0，栏下的点阵始终静止。
- **推荐 (b) + 卡片级**，理由：
  - owner 的原话是「长按抖动，类似手机桌面」。(a) 只是一颤，在默认测试机平板的横屏上几乎看不见。
  - 「静止时完全不动」原本是溶解点阵的规则（`docs/design.md:83`），不是全局禁令。编辑态是用户主动进入的长时状态，和 NP「Gemini 思考」极光「长时状态持续动」的先例一致。
  - 卡片级是小物件在动，比整块大面积倾斜更像桌面。B 方案评估过：370dp 宽的块持续倾斜会让人「晕船」。卡片级的交界衰减也可以逐卡判断，栏边的 JBI 不会整块静止。
- 这一题和 Q5 有耦合：能动的小物件越多，越像桌面。

**Q2 卡片长按的语义**

- (a) **直接进入编辑，并拿起所在 section**。底板从按点处长出来，交代「这张卡属于这一块」。这最贴近「长按就抖」的字面要求。
- (b) **两段式**：
  - 长按后松手，弹出条目菜单（`YoinDropdownMenu`，最后一项是 Edit Home）；继续按住或开始移动，则进入编辑。
  - 好处：和 iOS 13+、Pixel 桌面的图标长按一致，也和 app 现有的条目长按语义一致（Library 长按是加入歌单，`docs/haptic-feedback.md:59` 写的是「长按卡片 → 上下文菜单」）。
  - 代价：要新做一套卡片菜单，还要决定菜单里放哪些条目动作。
- **推荐 (a)**，前提是 Q5 选 (a) 或 (b)：v1 只有 section 级的操作，菜单里除了 Edit Home 没有别的必需项。如果 Q5 选 (c) 或 (d)，格子需要自己的操作入口，那时改为推荐 (b)。
- 检测器预留了阶段一的位置，从 (a) 迁到 (b) 只需要改检测器。

**Q3 拖动形态**

- (a) **全设备签条**：拖动时所有块收成带 3 张真实小封面的签条，在手指下短距离排序，松手后展开。只要一套引擎。
- (b) **按设备区分**：
  - 手机（Compact、横屏手机）收成签条；平板（Medium、Wide）按原尺寸拖，邻居用 `animateItem` 让位，需要时做短距离的边缘滚动。平板上的块又宽又矮，一屏内基本放得下。
  - 代价是两套拖动引擎。原尺寸那一套要重新处理锚定、回收和 seam 逐帧，P0 约增加 3d。
- (c) **全设备原尺寸拖动 + 自动滚动**（A 的原案）：在手机上把 RA 拖过 JBI，要走约 1000dp。
- **推荐 (a)**：只要一套引擎；签条带了封面，不再像设置列表。如果在原型里觉得平板上的签条不对劲，再升级到 (b)。

**Q4 编辑态底栏**

- (a) `[Undo|Add] [Done]`：pill 折起，编辑期间不能从底栏打开 NP。好处是零新几何，复用已上线的 idle 路径。
- (b) `[Add] [pill] [Done]`：保留 NP 入口，点 pill 先提交、退出编辑，再打开 NP。代价是多一个几何维度，`BarGeometryTest` 的矩阵扩大约 3 倍，而且要在「同窗分列」会话的大块 diff 上改。
- **推荐 (a)**。不管选哪项，底栏的改动都要等「同窗分列」会话提交并真机验证后再开工。

**Q5 v1 可移动的最小单位**

- (a) **只到 section**：按住封面，拿起的是整块；JBI 的格子不能删、不能钉。
- (b) **section + 尺寸档**：JBI 提供 2/3/4 行三档，约 +3d。
- (c) **section + 格子「−」**：「不再推荐」进本地排除表，由池子补位，不存位置，约 +4d。
- (d) **完整格子层**：钉选、1×1/1×2、槽位模型，约 +12–15 人日，另加 Figma。它包含 (b)(c) 的能力，同时引入「钉住 vs 轮换」这层新心智。
- **推荐 (a)**：P0 先上 section 级，用 Q1 的卡片级轻摆和底板生长来补桌面感。用过 v1 后如果还是觉得「不够桌面」，最便宜的下一步是 (b)。

**Q6a 新版块对已有用户的默认开关**

- (a) 分两类用户：
  - 从未定制过的用户（prefs 为 null）按 `defaultEnabled` 处理。久别重逢默认开启，没有数据时不渲染。
  - 定制过的用户，新版块追加为关闭，放进托盘并标 "New"。
- (b) 所有用户的新版块一律默认关闭、进托盘。
- (c) 所有用户的新版块一律默认开启，追加到 feed 末尾。这是现有 `reconcile` 的行为。
- **推荐 (a)**。配套规则是「只在真实改变时写 prefs」，这样误触一次长按不会把用户划进「已定制」。

**Q6b 发现性渠道组合**

- (a) 页尾常驻的 "Edit Home" + 进入后的 header 提示行（前 2 次）+ JBI 空位静态提示格（首次编辑后消失）+ 底栏 Home 长按（P1）；**不做**闲置轻推。
- (b) 精简：只要页尾常驻的 "Edit Home" 和 header 提示行。
- (c) 全开：在 (a) 的基础上再加一次性闲置轻推，所有闲置渠道合计每台设备 ≤ 2 次。
- **推荐 (a)**：长按入口在原位有一处静态暗示，普通态下页面没有任何自发运动。

**Q7 要不要删除 2026-07-02 上线的列表编辑器（`HomeLayoutEditor.kt`，Switch 行）**

- (a) 删除。
  - 就地编辑是 2026-07-01「在 Home 上就地编辑」这个决定的完整实现。
  - 无障碍由 TalkBack 自定义动作覆盖，并和删除放在同一个提交里。
  - 旧编辑器里的单拖、原地接住、累积速度、实时交换等不变式，都移植进签条层。
- (b) 保留，作为无障碍和兜底入口（比如从 TalkBack 或设置进入）。代价是两套编辑 UI 长期并存。
- **推荐 (a)**。

**Q8 Root 页编辑态的系统返回**

- (a) 只做离散的「返回 = Done」（P0），永远不做进度预览。
- (b) P0 先做离散返回，P1 再做全程缓动的 scrub 预览，只动底板、徽标、header 和底栏。这属于 RootSection「Never intercept」「不得包裹局部预测性返回」两条规则的书面例外，要在 AGENTS.md 和 `SKILL.md` 里各加一行，限定只在 `HomeSurface.Edit` 时启用。
- **推荐 (b)**：编辑态本来就会拦截返回（现有编辑器就是这样）。拦了返回却不给预览，恰好违背了「手势进度直接驱动 UI、预览目的地」这条返回教条。

**Q9 久别重逢与已退役的 memory_teaser**

- (a) **做**：定位是「久别」，也就是高分、且在 Yoin 里 90 天没播放过。点按进专辑详情，不进阁楼；与 JBI memory 卡和记忆胶囊去重。
- (b) **不做**：认为它碰到了「首页不再出现 Memory 预告」的边界。P0 的新版块改为「常听单曲」，P0 净减 0.5d。
- **推荐 (a)**：它的入选理由（「多久没听」）和点按去向（专辑详情）都和 Memory 不同。不过它和记忆胶囊、JBI memory 卡用的是同一批候选，首页上会有三处和高分专辑有关，这是否过量请 owner 判断。

**Q10 抽象层与远端统计**

- (a) 维持现状：主页新数据全走本地，接口不动。
- (b) 小重构，随 P1-3 一起做，约 +1.5d：
  - 加 `MusicLibrary.getRecentlyPlayed` + `Capability.RECENTLY_PLAYED`，消掉与主页相关的那 1 处 Spotify 向下转型；Apple 也能接上 recent/played。
  - `getAlbumList` 改为 `AlbumListKind` 枚举。
  - Apple 的能力集合改为引用 Catalog。
  - 不碰 scrobble，也不申请新 scope。
- (c) 在 (b) 的基础上：Subsonic 开始 scrobble（会改变用户服务器上的播放统计），并申请 Spotify `user-top-read`（老账号需要重连）。之后「常听前三」等组件可以改读远端。
- **推荐 (b)**：它修掉了 Apple「随机永远是前 18 张」这类静默错误，接口也更诚实。(c) 会对用户的服务器产生副作用，现在不值得。

**Q11 交互暗示的范围**

- (a) 本稿只做编辑态暗示；feed 上的暗示（播放字形、新内容点）全部移交「Home 提示」会话。
- (b) 本稿在 P1 做播放字形（0.5d，静态）；新内容点移交「Home 提示」会话。
- (c) 本稿在 P1 两个都做：播放字形 0.5d + 新内容点 1.5d（需要按 profile 存已读状态）。
- **推荐 (b)**：owner 要的是「更多交互暗示」。播放字形成本最低、完全静态，又直接回答了「点了会怎样」。新内容点和那个会话的信息层级工作重叠更多。

---

## 9. 附：被否决的设计点及理由

| 出处 | 被否决的点 | 理由 |
|---|---|---|
| A | 编辑态内容等比缩到约 0.927 | RA 的出血货架会被迫加遮罩，或在页中被截断，等于复议 owner 已去掉的 RA 遮罩；还会引发重算和 seam 位置变化。改为底板外扩、内容不缩放 |
| A | 拿起高块时「便携缩放」到 0.45 | 接近被否的「厚重缩放」；而且落点洞仍是全尺寸，邻居依旧要越过约 600dp 的中线。改为签条层 |
| A | 2Hz 持续摆动、8s 后衰减；相位按 index 取 `2.4·i`；写在 graphicsLayer 上 | 相位按 index 计算，换位后会跳；写在 graphicsLayer 的位置属性上，会让 SeamNode 逐帧回调。「持续摆动」这个形态本身没有被否，交给 Q1 |
| A | 从 150ms 起整块下沉，并预显 0.35 的底板 | 稍慢一点的普通点按也会晃一下。改为从 T/2 起、只作用于被按块、从按点长出、最高 0.4（§2.1.3） |
| A | P0 的 Reset 放在 Home 槽，既不能撤销也不确认 | 把不可逆的操作放在了肌肉记忆最强的导航位 |
| A | `onLongClick` 语义挂在 feed 根节点上 | LazyColumn 根节点拿不到无障碍焦点 |
| A | 页尾入口在用户学会后撤下；一次性轻推默认开启 | 编辑主页是低频任务；普通态下静止时的自发运动需要 owner 批准（Q6b） |
| A | 宣称「合并姿态下 pill 保留」 | 与 `YoinButtonGroup.kt:588-589` 不符，实际在所有姿态下都会折起 |
| B | v1 就做两级桌面（JBI 换引擎、钉住、2×2） | 范围是基线方案的 2–3 倍（实际估计 25–30 人日以上），还引入「钉住与轮换」的新心智，接近「照着 mockup 发明功能」。作为 Q5 (d) 保留 |
| B | 每格两个角标 | 在 Compact 的 3 列网格里，相邻格的触控区重叠约 20dp，信息噪音也大 |
| B | 拖 section 时做布局级的胶囊折叠 | 手势按住期间连续重排约 300ms，违背 adaptive §5，而且发生在 lookahead 下。签条层用离散提交加 draw 阶段插值来代替 |
| B | 用 `PressHoldDrag` 替换 8 处 clickable | 会丢掉滚动容器里的按压延迟（开始滚动时形变会闪一下），语义、焦点、键盘支持都要手工补齐 |
| B | 托盘用 `ModalBottomSheet`，Reset 用 `AlertDialog` | 太接近 stock 组件；sheet 还会引入新的返回 owner |
| B / C | HomeEdit 排在 DetailPane 之后 | Wide 下返回会先关掉详情列，编辑到一半换档 |
| B | `sign = (−1)^(col+row)` 按全局位置计算；轻摆写在 graphicsLayer 上 | 按全局位置计算，section 换位后会跳（本稿改为按块内序号奇偶，v1 卡片不移动，所以稳定）；graphicsLayer 会触发 SeamNode 逐帧回调。B 的幅度和 6s 静默参数被本稿采纳 |
| — | 隐藏时整块碎成网屏点（`ArtworkHalftone`） | 逐格画点，成本随格数线性增长，一整个 section 每帧有 6k–13k 格。改为下沉淡出 |
| C | 整页缩成总览 | 在 Yoin 里，整页缩小是预测性返回的 pop 语言；返回时内容反而放大，方向和 app 里其他所有返回相反；手机上缩到 0.39 只能认、不能读；P0 要 14–18 人日 |
| C | 底栏公式 `idleWeight = max(…, edit·…)` 配合 `collapsePill` | navOnly 时与 `fixedWithoutPill` 对不上，中途留洞，末端跳变 |
| C | 抖动 20s 后才停；从 120ms 起整页下沉；托盘用虚线框住缩略内容；点缩略块就退出并跳转；拖动的起始规则随「放不放得下」而变 | 依次的问题是：静止运动持续得最久；每次点按都晃；近似给封面加框；容易被意外踢出编辑；规则难以学会 |
| C | 返回的提交和取消用 `predictiveBackSettleSpring` | 与 motion taste 的做法（`stageSettleSpring`）不一致 |
| C | 进入时 `requestScrollToItem(0)` | `seamScrolledPx` 会在一帧内从 ∞ 跳到有限值，潮线 reveal 出现硬切 |
| 组件目录 §0(a) | 用 `combinedClickable` 实现「松手弹菜单、按住拖动进编辑」 | 做不到「长按后继续拖」，移动会被 LazyColumn 接管；会和容器检测器双重触发，还会双震。两段式本身保留为 Q2 (b)，改用容器检测器实现 |
| 组件目录 §0(c) | 每格一个 `LaunchedEffect` 循环 `animateTo(±θ)`，约 4Hz、±1.5° | 循环切换目标的弹簧，过冲会累积；12 个协程各写各的。改为全页一个时钟（§2.2.4） |
| 组件目录 §0(e) | 拿起用 ContextClick、换位用 Tick | 已统一到 §2.8（API 34 常量 + 回退） |
| 组件目录 | 钉选后弹出「已钉到主页 · 查看」HUD | 交界处禁止覆盖层。改为菜单项文字翻转 + `performConfirm()` |
| 组件目录 | 换一批用负 margin，失败时插入文字 | `Modifier.padding` 不接受负值；插入的文字会把下方内容顶开 |
| 组件目录 | 常听前三总宽 352dp、名次数字向左探出 24dp、尺寸动画用 `animateDpAsState` | 依次的问题是：在 360dp 设备上溢出；打破页边线；在组合阶段逐帧读值 |
| 组件目录 | 听歌日历的连续天数胶囊；差一点成回忆的文案只写评分这一条路径；久别重逢「默认开启也不会凭空多一块」的说法 | 第一项是被否决的 Streak 的变体；第二项与实际的资格条件不符；第三项是事实错误：只要有候选，`reconcile` 就会把它追加到老用户主页的末尾 |
| 忠实度审查 | 拖动时把 header 标题区变成 Pixel Launcher 式的「Hide」落点 | 落点在潮线带里，等于往交界处加覆盖层（`project_seam_dissolve`：「别再往交界处加 overlay」） |

---

## 10. 审查记录

### 相对综合稿的改动

**代码事实修正**（全部采纳，逐条对照过源码）

久别重逢与候选数据：
1. 改造候选构建。原来 `build()` 只返回 eligible 的候选（`AlbumMemoryCandidateBuilder.kt:92`），只有专辑分的专辑永远取不到。现在加了 `includeIneligible`，「差一点成回忆」也依赖它。
2. 改用纯 history 时间。原来的 `lastPlayedAt`/`firstPlayedAt` 混入了 VISITED 访问事件（`:71-80`）。
3. 补上离场的触发源，新增 `observeMostRecent`。没有采用「把 play_history 的变更戳并入 `observeMemorySignalStamp`」，因为那样每次切歌都会重建全部候选。
4. 去重时同时排除记忆胶囊的专辑。
5. 换一批改为新增 `reshuffleGrid()`，调用 private、无参的 `fetchAndPersistGridPools()`。综合稿写的 `fetchAndPersistGridPools(force = true)` 不存在。
6. 换一批的净增高是 8dp，不是 12dp。
7. 接着听直接用 `PlaybackManager.play`，不做向下转型。

返回与退出：
8. P0 的 BackHandler 挂在 `if (memoriesMounted)` 分支之外。
9. 程序性切 section 和切 profile 时退出编辑的挂点改正。
10. 写明 Q8 与 RootSection 规则的冲突。

底栏：
11. 合并姿态下 Library 孪生在左槽，Done 要替换两处。
12. EdgeSplit 沿用已上线的 idle 退场。
13. Home 键改成 Surface + combinedClickable。

header 与布局：
14. header 不卸载入口和齿轮，"Edit Home" 零宽叠放。原写法会让 feed 跳约 8dp，还会挤占记忆胶囊的宽度。
15. 被按块上方的占位块延迟插入。
16. 锚定改成一步完成：带负 offset 的 `requestScrollToItem`。
17. 隐藏键用 `Modifier.size(32.dp)`，去掉 `minimumTouchTarget`。

动效与渲染：
18. 写明 graphicsLayer 缩放同样会触发 SeamNode 回调。
19. kick 的完全静止时间改为 0.52–0.76s，阈值改为 0.01。
20. spec 改在组合期取值。

数据与存储：
21. prefs 只在真实改变时写；"New" 的已读状态单独存。
22. 计数改用 `yoin_ui_hints`。
23. 删掉已经存在的 `encodeDefaults` 加固项。
24. `isDefault` 只比较 sections。
25. ProfileManager 改为注入 `HomeLayoutDao`。

其他：
26. 几个 private 函数要改成 internal。
27. 档位判断收口到 `WindowAdaptiveRuntime`。
28. `TEXT_HANDLE_MOVE` 是 API 27，新增 `performThreshold()`。
29. 未提交文件清单和行号全部重新 grep。

**需求忠实度改进**（采纳，或改写后采纳）

抖动与桌面感：
1. 删掉「静止时完全不动是已拍板的全局方向」这一说法。抖动给出三种形态的完整参数、诚实代价和 debug 开关，推荐改为 (b) 轻摆后静默 + 卡片级。
2. Q1 加入「谁在动」子项，并写明与 Q5 的耦合。
3. §3 加入「v1 已知落差」。缓解手段是：底板从按点长出、卡片级轻摆、签条带封面。
4. 签条加上真实小封面和 palette 底色，去掉 supportingText。
5. 蓄力阶段预显底板。

验收与范围：
6. Pixel Tablet 没有马达：本稿作者已复测确认。为此新增视觉孪生列和手机 APK 验收。
7. 新增 §1.4 和 AGENTS.md 与代码的出入清单；改正 §1.3 的措辞。
8. 新增 §4.1 范围与分工，以及播放字形、新内容点两个候选（Q11）。
9. 新增鼠标副键入口和悬停效果。

新组件：
10. 新增 P1「常听单曲」；「库内漫游」从「不做」改为 P2，只做 Subsonic 和 Apple，以 section 的形态出现。
11. 久别重逢写明点按去向，并新增 Q9。
12. 恢复静态提示格，作为 Q6b 推荐组合的一部分。

问题清单与引用：
13. Q6 拆成 Q6a 和 Q6b。
14. 补上 Q7–Q11。
15. 三个错配的 Mobbin 引用改挂到正确的位置，没有删除任何链接：a84ec650 → 常听单曲；328f199c → Q5 (b) 尺寸档；943c8668 改注为冷启动文案。
16. §9 删去「频率过于 iOS」；A、B 的持续摆动只否具体实现，不否形态。

**本稿作者另外发现的**
1. 「重做 Home」会话的 `HomeFeedFrame` 已经把 feed 改成铺满容器、页边用 contentPadding。命中测试的横向范围、平板页边数值（56dp）和并行会话清单都已据此更新。
2. JBI 现在包了一层列数 `AnimatedContent(SizeTransform)`。换一批的增高按新结构计算，并在风险表里说明编辑态不会触发它。
3. 新增 5 个 Mobbin 链接，都取自 `research-music-homes.json`：3d34a415、82d59a7d、9d0a3cc3、ff23447d、c2609ecf。

### 拒绝或改写的审查意见

| 意见 | 处理 | 理由 |
|---|---|---|
| 忠实度：把 recently-played 提升为接口方法后「消掉 5 处向下转型」 | 改写 | 逐处核对后，5 处里只有 `:2320`（Activities 的 Spotify 数据源）与主页有关；其余 4 处是 `requireSpotifySource`、库同步标脏、Connect 设备、播放转移 |
| 忠实度：常听单曲取「评分 ≥ 4」 | 改为 ≥ 8.0 | 评分刻度是 0–10（`RatingSlider.kt:84-89`） |
| 忠实度：把 Q1 与 Q5 合成一题，并重排题号 | 部分采纳 | 任务要求 Q1/Q2/Q3 分别对应抖动、长按、拖动（原型也按这个编号），所以 Q5 保持独立，只在两题里写明耦合 |
| 忠实度：Medium/Wide 默认按原尺寸拖 | 不作为默认，列为 Q3 (b) | 需要两套拖动引擎，原尺寸那一套要重新解决锚定、回收和 seam 逐帧，约 +3d。先用原型判断平板上的签条是否真的不行 |
| 忠实度：隐藏的 section 也作为签条，排在 Hidden 线下 | 推迟为 P1 候选 | 「拖到隐藏区的哪个位置」这层语义，要先和 `HomeLayout` 保留禁用项顺序的规则对齐 |
| 忠实度：拖动时 header 变成「Hide」落点 | 拒绝 | 落点在潮线带里，属于往交界处加覆盖层 |
| 忠实度：底板「从被按卡片的矩形」长出 | 改为以按点为中心的 96dp 方块 | 要拿到卡片矩形，就得给每张卡登记坐标；96dp 约等于一张小卡，视觉意图相同 |
| 忠实度：Q2 保持中立、不给推荐 | 改为条件推荐 | 任务要求每题都给推荐，所以按 Q5 的选择分别推荐 (a) 或 (b) |
| 代码事实：charge/lift/hide 的缩放改用 `drawWithContent` | 选择「明确接受代价」那一支 | 只作用于单块、≤ 300ms；改用画布缩放会让 `shadowElevation` 的轮廓和内容错开约 3dp |
| 代码事实：header 也可以「只在 P > 0 时组合」 | 未采用 | 选用零宽叠放，普通态完全不受影响，也不依赖胶囊在编辑态淡出 |
| 代码事实：`fetchAndPersistGridPools` 在 `:411` | 行号更新为 `:445` | 这个文件仍在被其他会话改动，行号已经漂移 |