# Yoin 接入 Yoin Symbols · 实现交接（Claude Cowork → Claude Code）

> 2026-09-30，由 Claude Cowork 写。用户用中文交流，会用 ultracode 一次跑完。
> 图标库 Yoin Symbols 0.1.0：Maven 坐标 `io.github.p2o51:yoin-symbols`，包名 `com.gpo.yoin.symbols`。**还没发布**，这次用源码做 composite build（§1）。库的说明看它自己的 `README.zh-CN.md`、`docs/zh-CN/DESIGN.md`、`docs/zh-CN/MOTION.md`。画廊是 claude.ai 私有链接，Claude Code 打不开，也用不到。
> 仓库规则照旧：`AGENTS.md`（动效必须 spring、MD3 Expressive、public Composable 要 `@Preview`、ktlint、YAGNI）。`docs/design.md` 是 UI 决策的最终来源，这次的决定要写进去（§6.4）。

---

## 0. 开工前必读

1. **断点适配要先收尾。** 写这份文档时，`feat/responsive-breakpoints` 上正在按 `docs/handoff/responsive-breakpoints.md` 改 `YoinButtonGroup.kt`、`PlaySplitButton.kt`、`NowPlayingPill.kt`、`FloatingBottomBar.kt`、`YoinActivityRoot.kt`，还新加了 `YoinEdgeSplitGroup.kt`。这些和这次要动的文件重叠很多。
   - 开工先跑 `git status`、`git log -3`、`git branch --show-current`。
   - 如果断点那条线还没提交，停下来问用户：等它收尾，还是在它上面接着做。
2. **工作区不干净。** 除了断点，还有 provider capability、Apple Music、溶解等两百多个未提交改动，都不属于本任务。
   - 不要回滚，也不要重新格式化。
   - 开新分支 `feat/yoin-symbols` 还是直接在当前分支上做，问用户。
   - 不要对整个工程跑 `ktlintFormat`，它会改到别人的文件。跑 `ktlintCheck`，只修你改过的文件；跑完用 `git diff --stat` 自查。
3. **行号只是写文档时的位置。** 断点那条线合进来以后行号会变，以「文件 + 定位」为准：函数名、`contentDescription`、变量名。断点那条线新加的文件里如果有 Material 图标，照 §3 的映射一起换。
4. **已经定了的（用户确认过，不要再问）：**
   - PLAY / PAUSE 保持文字，不换成图标。
   - 播放模式做成一个按钮、三个状态：列表循环 → 随机 → 单曲循环 → 列表循环，和 Spotoolfy 一样。Yoin 默认列表循环（§5）。
   - 只改 Yoin。仓库里的 `projects/notionflow` 等其它项目不动；图标库这次也不发布。
   - app 里所有 Material 图标都换成 Yoin Symbols，最后去掉 `material-icons-extended`（§6.3）。
5. **不改的：**
   - 按钮的尺寸、形状、布局。
   - AI 加载态用的 `YoinLoadingIndicator`。
   - 动效语言。
   - 颜色，§5.4 的播放模式配色除外。

---

## 1. WS1 · 接入依赖（最先做，串行）

### 1.1 拿到库，先单独编一次

1. 有 `~/Developer/yoin-symbols` 就用它。没有就运行 `unzip ~/Downloads/yoin-symbols.zip -d ~/Developer`。
   - zip 里是完整的 git 仓库：`main` 分支，一个提交 `9c2163c feat: Yoin Symbols 0.1.0`。
   - 它和 Yoin 仓库（`~/Developer/Yoin`）同级，下面 settings 里的默认路径 `../yoin-symbols` 就是指它。
2. 编库：`cd ~/Developer/yoin-symbols/android && ./gradlew :yoin-symbols:assembleRelease`。
   - 这个库是在没有 Android SDK 的沙箱里写的，**从没用真正的 AGP 编过**。已经做过的检查：
     - Kotlin 源码用 Kotlin 2.0.21 编译器、对着手写的 Compose API stub 做过类型检查（strict explicit API），0 错误。
     - composite 替换用 Gradle 8.14.3 加一个替身工程试过。
   - 编不过就在 `~/Developer/yoin-symbols` 里修，报告里逐条写清楚。不要把库的源码拷进 Yoin。要不要在库里提交，问用户。
   - 库里有些文件是生成的：`YoinSymbols.kt`、`SymbolGeometry.kt`、`res/drawable/`。要改它们，改 `generator/`，再跑 `python3 generator/build.py`。
   - `AnimatedSymbols.kt`、`SymbolBuilder.kt`、`SymbolMotion.kt` 是手写的，可以直接改。
3. 两边版本必须一致：AGP 9.1.0、Kotlin 2.3.20、Gradle 9.4.1、Compose BOM 2026.03.01。现在是一致的。一个构建里不能有两个 AGP 版本，composite build 也算在内。

### 1.2 Gradle

`settings.gradle.kts`，放在 `include(...)` 之后：

```kotlin
// Yoin Symbols (io.github.p2o51:yoin-symbols) is built from source until it is on Maven Central:
// by default from a checkout next to this repo; CI passes -PyoinSymbolsDir=<path>.
val yoinSymbolsDir = providers.gradleProperty("yoinSymbolsDir").getOrElse("../yoin-symbols")
val yoinSymbolsBuild = file("$yoinSymbolsDir/android")
if (yoinSymbolsBuild.isDirectory) includeBuild(yoinSymbolsBuild)
```

`gradle/libs.versions.toml`：

```toml
[versions]
yoinSymbols = "0.1.0"

[libraries]
yoin-symbols = { group = "io.github.p2o51", name = "yoin-symbols", version.ref = "yoinSymbols" }
```

`app/build.gradle.kts`：加 `implementation(libs.yoin.symbols)`。`material-icons-extended` 先留着，最后在 §6.3 删。

检查：运行 `./gradlew :app:dependencies --configuration debugRuntimeClasspath | grep yoin-symbols`，应该看到它被替换成了 project（`-> project …yoin-symbols`）。

### 1.3 CI

改动范围：`.github/workflows/ci.yml` 的 4 个 job，加上 `release.yml`。

每个 job 在第一个 `actions/checkout@v5` 之后加：

```yaml
      - name: Check out Yoin Symbols
        uses: actions/checkout@v5
        with:
          repository: p2o51/yoin-symbols
          ref: v0.1.0
          path: yoin-symbols
```

每条 `./gradlew …` 命令都加上 `-PyoinSymbolsDir=yoin-symbols`。

- **这一步要等用户。** 库还没有推到 GitHub。
  - 用户先把 `~/Developer/yoin-symbols` 推到 `p2o51/yoin-symbols`，并打 tag `v0.1.0`。
  - 如果仓库是私有的，还要在 Yoin 的 Actions secrets 里放一个只读 token，并在上面的 step 里加 `token: ${{ secrets.<名字> }}`。名字问用户，token 不进代码。
  - **不要替用户建仓库、推送或打 tag**，在报告里提醒。在那之前 CI 是红的，这是预期。
- 以后库发到 Maven Central 时，删掉这个 step、`-P` 参数和 settings 里那三行，依赖坐标不用改。

### 1.4 动效档位

`YoinActivityRoot.kt`：在 `CompositionLocalProvider` 之前算好档位，再紧挨着 `LocalMotionProfile provides motionProfile` 提供出去（`.editorconfig` 限制行宽 120，所以不要写成一行）：

```kotlin
val symbolMotion =
    if (motionProfile == MotionProfile.AdaptiveReduced) SymbolMotion.Reduced else SymbolMotion.Default
// …
LocalSymbolMotion provides symbolMotion,
```

- Main、Settings、ServiceSetup 和三个详情 Activity 都包在 `YoinActivityRoot` 里，所以加这一处就够了。
- 库的默认弹簧就是 M3 Expressive 的 motion scheme，和 `YoinMotion` 的 expressive 档一样，不用从 `YoinMotion` 重新拼。
- 系统「移除动画」（animator 时长 = 0）由库自己处理：循环停下，符号静止。

---

## 2. 分工（ultracode 并行时按文件分）

1. WS1 做完，再开 WS2、WS3、WS4。
2. WS2–WS4 可以并行。**一个文件只归一条线**，这条线负责这个文件里所有的图标改动：静态、动效和数据传递都算。
3. WS5、WS6 最后做。

| 线 | 独占的文件 | 内容 |
| --- | --- | --- |
| WS4 播放模式 | `player/PlaybackManager.kt`、`PlaybackState.kt`、`SpotifyAppRemotePlayer.kt`、`PlaybackService.kt`，新文件 `player/PlayMode.kt`；`ui/nowplaying/NowPlayingViewModel.kt`、`NowPlayingUiState.kt`、`NowPlayingOverlayHost.kt`、`NowPlayingScreen.kt`、`PlaybackControls.kt`；相应的测试 | §5。还有这些文件里的静态图标，以及 `NowPlayingScreen.kt` 的 `FavoriteButton`（做法见 §4.2） |
| WS3 动效 | `ui/nowplaying/LyricsActionBar.kt`、`ui/settings/SettingsComponents.kt`、`ui/component/PlaySplitButton.kt`、`ui/detail/AlbumDetailComponents.kt`、`AlbumDetailScreen.kt`、`AlbumDetailActivity.kt` | §4。还有这些文件里的静态图标 |
| WS2 静态替换 | 其余所有用到 Material 图标的文件。`main`、`debug`、`androidTest` 都算，包括断点那条线新加的文件 | §3 |
| WS5 收尾 | 资源、依赖、文档 | §6 |
| WS6 验证 | — | §7 |

用这条命令重新列出用到图标的地方：`grep -rnE "Icons\.(Rounded|Filled|Outlined|Default|AutoMirrored)" app/src`。写文档时共 34 个文件。

---

## 3. WS2 · 静态图标映射

通用做法：

- `Icons.<任意风格>.<Name>` 换成 `YoinSymbols.<Name>`，加 `import com.gpo.yoin.symbols.YoinSymbols`。
- `YoinSymbols.*` 本身就是 `ImageVector`，所以下面这些参数的类型都不用改：
  - `Icon(imageVector = …)`
  - `fallbackIcon: ImageVector`
  - `SettingsItem(icon = …)`
  - `iconForDevice()`
- tint 和 size 照旧。
- Filled、Rounded、Outlined、AutoMirrored 都对应同一个符号。下表明确写了实心版的除外。
- 改完删掉对应的 `androidx.compose.material.icons.*` import。

### 3.1 名字映射（Material → YoinSymbols）

| Material | YoinSymbols | 说明 |
| --- | --- | --- |
| Add、Album、Cast、CastConnected、Check、Code、Computer、Delete、Devices、DragHandle、Edit、EditNote、Folder、Headphones、Info、Insights、Launch、MusicNote、PlayCircle、Refresh、Reviews、Search、Send、Settings、Shuffle、Smartphone、Speaker、Storage、Tablet、Tv、UnfoldLess、UnfoldMore、Visibility、VisibilityOff | 同名 | 例外：Now Playing 的随机键会变成播放模式键（§5） |
| ArrowBack | Back | Back、ChevronLeft、ChevronRight、Send 在 RTL 布局里会自动镜像，和 AutoMirrored 一样 |
| AutoAwesome | Sparkle | 设置里的「AI features」 |
| ChevronRight、KeyboardArrowRight | ChevronRight | |
| Clear、Close | Close | |
| CloudQueue | Cloud | |
| DeviceHub | DeviceOther | |
| DirectionsCar | Car | |
| SportsEsports | Gamepad | |
| Error | ErrorFilled | Material 的 Error 是实心的 |
| ErrorOutline | Error | |
| Favorite | FavoriteFilled | 可以切换的收藏按钮用动效版（§4.2） |
| FavoriteBorder | Favorite | |
| Star | StarFilled | |
| StarBorder | Star | |
| GraphicEq | Equalizer | 专辑页曲目行里的那个用动效版（§4.3） |
| Home、LibraryMusic、QueueMusic | 按场景选 | 见 §3.2 |
| IosShare | Share | |
| KeyboardArrowDown | ChevronDown | Play ▾ 用动效版（§4.4） |
| KeyboardArrowUp | ChevronUp | |
| ExpandMore | — | 只出现在设置的可展开项里，用动效版（§4.4） |
| MoreVert | MoreVertical | |
| Person | Artist | 现在 5 处都和歌手有关：头像兜底、歌手类型、「去歌手页」 |
| PlayArrow | PlayArrow | 封面兜底图用 PlayArrow；`PlaySplitButton` 的 Play 键用 PlayFilled |
| SkipNext、SkipPrevious | SkipNextFilled、SkipPreviousFilled | 只在 `PlaybackControls.kt` 的 FilledIconButton 里 |
| StickyNote2 | Note | |
| Translate | — | 用动效版（§4.1） |
| VerticalAlignCenter | Recenter | 歌词的「回到当前行」 |

### 3.2 按场景选的

- **导航的 Home、Library**：在 `YoinButtonGroup.kt`、`YoinEdgeSplitGroup.kt` 里；`YoinNavRail.kt` 如果还在也算。
  - 选中的用 `HomeFilled` / `LibraryFilled`，没选中的用 `Home` / `Library`。
  - 直接换图就行，按钮本身已经有形变动画。
- **LibraryMusic**：
  - 专辑封面兜底图（`fallbackIcon`）改成 `Album`。涉及 `AlbumCard`、`HomeEditorialContent`、`LibraryScreen` 的专辑格、`AlbumDetailScreen` 的两处、`MemoriesScreen`。
  - 歌单封面兜底图改成 `Playlist`。涉及 `PlaylistDetailScreen` 的两处、`AddToPlaylistSheet`。
  - `HomeWidgetGrid` 的 Album 类型见下面那张表。
  - `ServiceIntro` 里 Spotify 的「Everything you've saved」改成 `Library`。
  - debug 的 `MemoriesEmptyPreviewActivity` 跟它预览的组件保持一致。
- **QueueMusic**：
  - `BottomPills` 的队列按钮改成 `Queue`。
  - `LibraryScreen` 的歌单兜底图、`ServiceIntro` 的「Your playlists」、`HomeWidgetGrid` 的 Playlist 改成 `Playlist`。
- **`HomeWidgetGrid` 的类型图标**：

  | 类型 | 符号 |
  | --- | --- |
  | Playlist | Playlist |
  | Song | MusicNote |
  | Album | Album |
  | Artist | Artist |

- **总原则**：
  - 默认用线性版。
  - 「选中」或「已开启」用 *Filled 版。
  - 播放控制键用 *Filled 多边形版：PlayFilled、SkipNextFilled 等。
- **库里没有对应符号的**：断点那条线可能加了库里没有的图标，比如全屏。
  - 不要在 app 里画 vector，也不要换成意思不对的符号。
  - 先把别的都做完，最后在报告里列出来：Material 名、文件、用途。
  - 这时 `material-icons-extended` 先保留（§6.3）。
  - 要补的符号以后加进 yoin-symbols 的 `generator/`。

---

## 4. WS3 · 动效符号

- 所有 painter 都读 `LocalSymbolMotion`（§1.4），不要显式传 `motion`。
- 用法都是 `Icon(painter = rememberXxxSymbolPainter(state), contentDescription = …)`，tint 照旧。

### 4.1 翻译（`LyricsActionBar.kt`）

- `LyricsActionIcon` 的参数 `icon: ImageVector` 改成 `icon: Painter`。静态图标传 `rememberVectorPainter(YoinSymbols.Search)`、`Check`、`Recenter`。
- 翻译键用 `rememberTranslateSymbolPainter(translating = actionInFlight == LyricsAction.Translate)`。翻译中「文」和「A」绕圈换位置，结束后回到「文A」。
- 翻译进行中，这个键要保持亮着：
  - `enabled = (actionInFlight == null || actionInFlight == LyricsAction.Translate) && canTranslate`
  - `onClick = { if (actionInFlight == null) onTranslateClick() }`
  - 原因：disabled 的 IconButton 内容只有 38% 不透明度，动画几乎看不见。
- 其它三个键照旧：只要有动作在跑就 disabled。

### 4.2 收藏

涉及两处：

- `NowPlayingScreen.kt` 的 `FavoriteButton`（参数 `isStarred`）。这个文件归 WS4，由 WS4 按这里的做法改。
- `AlbumDetailComponents.kt` 的收藏键（参数 `active`）。

两处都改成 `rememberFavoriteSymbolPainter(favorite = …)`。

- 效果：点亮时描边跳一下，填充从中心长出来；取消时只收回填充。
- tint 和 `contentDescription` 的逻辑不变。

### 4.3 均衡器（`AlbumDetailComponents.kt` 的 `AlbumTrackRow`）

- 「Now playing」那个图标改成 `rememberEqualizerSymbolPainter(playing = …)`。大小（16dp）和 tint（accent）不变。
- 效果：播放时每根柱子各自跳；暂停后从两边往中间沉成点。
- 需要把「是否在播放」传下来：
  - `AlbumDetailActivity` 已经把 `playbackState` 映射成 `currentTrackId`（带 seed 和 `distinctUntilChanged`）。
  - 照同样的写法加一个 `isPlaying`。
  - 经过 `AlbumDetailScreen` 的各层传到 `AlbumTrackRow`：凡是传 `currentTrackId` 的地方都一起传。
- 只在当前曲目上显示，沿用现在的 `isNowPlaying`。

### 4.4 展开箭头

- **`SettingsComponents.kt` 的 `SettingsExpandableItem`**：
  - 改成 `Icon(rememberExpandSymbolPainter(expanded), …)`。
  - 删掉 `chevronRotation`，也就是那段 `animateFloatAsState` 加 `graphicsLayer { rotationZ }`。
  - 这个箭头是先压平再翻过去，不是转圈。
- **`PlaySplitButton.kt` 的 ▾**（横版和断点新加的竖版都要改）：
  - 改成 `rememberExpandSymbolPainter(menuOpen)`。
  - 删掉 `graphicsLayer { rotationZ = if (menuOpen) 180f else 0f }`，它现在是瞬间翻转，没有动画。
  - 同一个文件里，Play 键用 `PlayFilled`，菜单里的 Shuffle 用 `Shuffle`。

### 4.5 预览

- 改了签名的 public Composable 要同步更新 `@Preview`。
- 新加的 public Composable 要有 `@Preview`（AGENTS.md）。

---

## 5. WS4 · 播放模式按钮（三态）

### 5.1 定义

| 模式 | 图标 `SymbolPlayMode` | Media3 | Spotify App Remote |
| --- | --- | --- | --- |
| 列表循环（默认） | `RepeatAll` | `REPEAT_MODE_ALL`，shuffle 关 | `setRepeat(ALL)` + `setShuffle(false)` |
| 随机 | `Shuffle` | `REPEAT_MODE_ALL`，shuffle 开 | `setRepeat(ALL)` + `setShuffle(true)` |
| 单曲循环 | `RepeatOne` | `REPEAT_MODE_ONE`，shuffle 关 | `setRepeat(ONE)` + `setShuffle(false)` |

- Spotify 的 Repeat 常量和 Media3 相同（OFF 0 / ONE 1 / ALL 2），现有的 `setRepeatMode` 可以直接用。
- 点一下按钮的顺序：列表循环 → 随机 → 单曲循环 → 列表循环。和 `SymbolPlayMode.next()` 的顺序一样。
- 切换时 repeat 和 shuffle **两个一起写**，这样结果不依赖之前的状态，也不依赖写入顺序。
- 从播放器读回状态时按这个顺序判断：
  1. `REPEAT_MODE_ONE` → 单曲循环。就算 shuffle 开着，听到的也是单曲。
  2. shuffle 开 → 随机。
  3. 其它情况 → 列表循环。`REPEAT_MODE_OFF` 也显示成列表循环；它只会在 Yoin 以外改了设置时出现，见 §5.3。

### 5.2 代码

新文件 `player/PlayMode.kt`（播放层不要依赖图标库）：

```kotlin
enum class PlayMode(val repeatMode: Int, val shuffle: Boolean) {
    RepeatAll(Player.REPEAT_MODE_ALL, shuffle = false),
    Shuffle(Player.REPEAT_MODE_ALL, shuffle = true),
    RepeatOne(Player.REPEAT_MODE_ONE, shuffle = false),
    ;

    fun next(): PlayMode = entries[(ordinal + 1) % entries.size]

    companion object {
        fun of(repeatMode: Int, shuffle: Boolean): PlayMode = when {
            repeatMode == Player.REPEAT_MODE_ONE -> RepeatOne
            shuffle -> Shuffle
            else -> RepeatAll
        }
    }
}
```

各文件的改法：

- **`PlaybackState`**：保留 `repeatMode` 和 `shuffleEnabled` 两个原始字段，再加 `val playMode: PlayMode get() = PlayMode.of(repeatMode, shuffleEnabled)`。
- **`PlaybackManager`**：
  - 加 `fun setPlayMode(mode: PlayMode)`：
    - 本地：`executeOrQueue { it.repeatMode = mode.repeatMode; it.shuffleModeEnabled = mode.shuffle }`。
    - Spotify：`spotifyRemotePlayer.setRepeatMode(mode.repeatMode)`，再 `spotifyRemotePlayer.setShuffle(mode.shuffle)`。
  - 同时把模式记到 `preferredPlayMode`，默认 `PlayMode.RepeatAll`（见 §5.3）。
  - `toggleShuffle()` 没人用了就删。ViewModel 和 `SpotifyAppRemotePlayer` 里的也一样。
- **`SpotifyAppRemotePlayer`**：
  - `toggleShuffle()` 换成 `setShuffle(enabled: Boolean)`，里面调用 `playerApi.setShuffle(enabled)`，同样走 `enqueueOperation`。
  - `setRepeatMode` 已经有了。它的 `coerceIn(OFF, ALL)` 不会把 ONE 截掉。
- **MusicKit**：`AppleMusicMedia3Player` 已经支持 `COMMAND_SET_REPEAT_MODE` 和 `COMMAND_SET_SHUFFLE_MODE`，走本地那条路径就行，不用改。
- **`NowPlayingViewModel`**：`toggleShuffle()` 换成 `fun cyclePlayMode()`：读 `playbackManager.playbackState.value.playMode.next()`，再写回去。
- **`NowPlayingUiState.Playing`**：`shuffleEnabled: Boolean` 换成 `playMode: PlayMode`。
- **`NowPlayingScreen.kt`、`NowPlayingOverlayHost.kt`、`PlaybackControls.kt`**：
  - `shuffleEnabled` / `onToggleShuffle` 换成 `playMode` / `onCyclePlayMode`。`NowPlayingScreen.kt` 里好几个布局都在传，都要改。
  - 预览里用 `PlayMode.RepeatAll`。
- **UI 层映射**：在 `PlaybackControls.kt` 里写一个 private 的 `PlayMode.toSymbol(): SymbolPlayMode` 就行。

### 5.3 默认列表循环

- `PlaybackService` 里 ExoPlayer build 完以后，设 `player.repeatMode = Player.REPEAT_MODE_ALL`。
- **Yoin 自己发起的播放**：Yoin 起一个队列、选好后端以后（本地 ExoPlayer、MusicKit、Spotify remote 都算），`PlaybackManager` 在开播后把 `preferredPlayMode` 应用到这个后端。这样换 provider 以后，默认值和用户选的模式都还在。
- **别处开始的播放**：比如用户在 Spotify App 里自己放的，Yoin 只是观察到，这时不要写，显示后端报上来的状态就行。
- 模式不跨进程保存（YAGNI）。在报告里作为后续项提一句。
- 详情页的「Shuffle play」是把列表本身打乱（`tracks.shuffled()`），不碰播放模式，保持不变。

### 5.4 按钮（`PlaybackControls.kt` 原来的 shuffle 键）

- **内容**：
  - 图标：`Icon(rememberPlayModeSymbolPainter(playMode.toSymbol()), contentDescription = "Play mode", modifier = Modifier.size(controlIconSize))`。
  - 点击：`onClick = { haptics.performTick(); onCyclePlayMode() }`。
- **配色**：还是原来的 `animateColorAsState` 加 `YoinMotion.defaultEffectsSpec()`。
  - 列表循环用现在「shuffle 关」的颜色：`tertiaryContainer` / `onTertiaryContainer`。
  - 随机和单曲循环用现在「shuffle 开」的颜色：`primary` / `onPrimary`。
  - 这样默认状态看起来和现在一样，非默认模式像现在开了随机那样亮起来。
- **无障碍**：按钮加 `Modifier.semantics { stateDescription = "Repeat all" / "Shuffle" / "Repeat one" }`。TalkBack 会读成「Play mode, Shuffle, button」。
- **状态来源**：图标跟播放器报上来的状态走，和现在的 shuffle 键一样。
  - 如果 Spotify 往返让图标明显慢半拍，再在 ViewModel 里加一个乐观的 pending 值：后端报上相同的值，或者过了 2 s，就清掉。
  - 不慢就别加。
- **同一个文件里的其它图标**：
  - Skip 键用 `SkipPreviousFilled` / `SkipNextFilled`。
  - 歌词展开键用 `UnfoldLess` / `UnfoldMore`。
  - PLAY / PAUSE 文字不动。
- 三种模式各写一个 `@Preview`。

### 5.5 测试

- 新建 `app/src/test/java/com/gpo/yoin/player/PlayModeTest.kt`：
  - `next()` 的循环顺序。
  - `of()`：OFF → RepeatAll，ONE + shuffle → RepeatOne，ALL + shuffle → Shuffle。
- `SpotifyAppRemotePlayerMappingTest` 如果覆盖了 shuffle / repeat 的映射，跟着改。

---

## 6. WS5 · 资源、依赖、文档（WS2–4 之后）

### 6.1 删掉 app 里第一轮的图标副本

- **要删的**：`app/src/main/res/drawable/ic_yoin_*.xml`，下面 3 个除外：
  - `ic_yoin_launcher_background.xml`
  - `ic_yoin_launcher_foreground.xml`
  - `ic_yoin_launcher_monochrome.xml`
  - 写文档时共 94 个，删 91 个。
- **为什么必须删**：资源合并时，app 的同名资源会盖掉库里的。不删的话，库的 VectorDrawable 会被第一轮的旧图形替换掉。
- **删之前检查**：运行 `grep -rn "ic_yoin_" app/src --include=*.kt --include=*.xml | grep -v "res/drawable/ic_yoin_" | grep -v launcher`，应该只剩 §6.2 那一处。

### 6.2 通知小图标

- 位置：`player/applemusic/AppleMusicValidationService.kt` 里的 `R.drawable.ic_yoin_music_note`，改成用库里的同名资源。
- 工程开了 `android.nonTransitiveRClass=true`，app 的 `R` 里没有库的资源。
- 所以要写成 `com.gpo.yoin.symbols.R.drawable.ic_yoin_music_note`，可以用 `import com.gpo.yoin.symbols.R as SymbolsR`。

### 6.3 去掉 material-icons-extended

- 条件：`grep -rn "androidx.compose.material.icons" app/src` 的结果为空。
- 满足后删两处：
  - `app/build.gradle.kts` 的 `implementation(libs.material.icons.extended)`。
  - `gradle/libs.versions.toml` 的 `material-icons-extended`。
- `projects/notionflow` 有自己的依赖声明，和这里无关。
- 如果还有图标没对应上（§3.2 最后一条），先保留依赖，在报告里说明。

### 6.4 `docs/design.md`

在「设计语言：Material Design 3 Expressive」下面、颜色系统之后，加一节 `### 图标：Yoin Symbols`，写清楚这些：

- **来源**：
  - 依赖是 `io.github.p2o51:yoin-symbols`，源码在 `~/Developer/yoin-symbols`。发布之前用 composite build。
  - app 不再用 material-icons，也不在 app 里放图标 vector。缺的符号先加进库。
- **规则**：
  - 默认线性版；选中或已开启用 Filled 版；播放控制用 *Filled 多边形版。
  - 返回、左右箭头、发送在 RTL 布局里会镜像。
- **动效符号和用在哪**：
  - 翻译：歌词翻译进行中。
  - 收藏：Now Playing、专辑页。
  - 均衡器：专辑页的当前曲目，跟随播放 / 暂停。
  - 展开箭头：设置的可展开项、Play ▾。
  - 播放模式。
  - 动效档位跟随 `MotionProfile`：`AdaptiveReduced` 对应 `SymbolMotion.Reduced`。
- **播放模式**：§5.1 的表，以及「默认列表循环」。
- **`docs/icons/`**：第一轮草稿，已经被 yoin-symbols 取代，留着存档，不要删。
- 顺手修一处路径：「待确认事项」里 App 图标那一条写的是 `res/drawable/ic_launcher_{…}.xml`，实际文件名是 `ic_yoin_launcher_{…}.xml`。

---

## 7. WS6 · 验证和报告

### 7.1 命令

```bash
cd ~/Developer/yoin-symbols/android && ./gradlew :yoin-symbols:assembleRelease
cd <Yoin 仓库> && ./gradlew ktlintCheck test :app:lintDebug assembleDebug :app:assembleDebugAndroidTest
```

加 `assembleDebugAndroidTest` 是因为 androidTest 里也有图标，而 CI 不编译 androidTest。

### 7.2 grep 检查

| 命令 | 期望结果 |
| --- | --- |
| `grep -rn "androidx.compose.material.icons" app/src` | 为空，或只剩报告里列出的 |
| `grep -rn "R.drawable.ic_yoin_" app/src \| grep -v launcher` | 只剩 `SymbolsR` 那一处 |
| `ls app/src/main/res/drawable/ic_yoin_*` | 只剩 3 个 launcher 文件 |

### 7.3 手动检查

有设备或模拟器就做；没有就在报告里写明没做。

- **播放模式**（Now Playing）：
  - 连点三下，顺序是列表循环 → 随机 → 单曲循环 → 列表循环，每次形变都对。
  - 切歌、换 provider 以后，模式还是对的。
  - 用 Spotify 时，Spotify App 里的状态跟着变。
- **歌词翻译**：翻译中两个字绕圈，结束后停在「文A」。
- **收藏**：点亮时跳一下。
- **均衡器**：专辑页当前曲目的均衡器，播放时跳，暂停后沉成点。
- **展开箭头**：设置的可展开项和 Play ▾ 都是铰链式翻转。
- **关闭动画**：开发者选项里把 animator 时长设为 0，符号全部静止，不闪。
- **RTL**：开发者选项打开「强制使用从右到左的布局方向」，返回箭头方向正确。

### 7.4 报告要写的

- 每条线改了哪些文件；库里改了什么（如果有）。
- 还剩哪些 Material 图标，为什么。
- CI 还需要用户做什么：推库、打 tag、放 token。
- 后续可以做的：
  - 播放模式跨进程保存。
  - AI 加载态要不要用 Sparkle 动效。现在用的是 `YoinLoadingIndicator`，这次不动。
- 提交之前问用户，包括库那边要不要提交。

---

## 8. 不做

- 其它项目，比如 `projects/notionflow`。
- 发布图标库：Maven Central、npm、pub.dev 都不发。
- 第四种模式（不循环）。
- 把 PLAY / PAUSE 换成图标。
- 播放模式跨进程保存。
- 替换 `YoinLoadingIndicator`。
- 重画或新增图标。
- 改按钮的尺寸或布局。

---

## 附录 · 库 API 速查

```kotlin
import com.gpo.yoin.symbols.*

YoinSymbols.<Name>          // 91 个 ImageVector，名字见 §3.1
YoinSymbolCatalog.all       // Map<String, () -> ImageVector>，key 是 snake_case 名字
// VectorDrawable：com.gpo.yoin.symbols.R.drawable.ic_yoin_<snake_name>

@Composable fun rememberTranslateSymbolPainter(translating: Boolean): Painter
@Composable fun rememberPlayModeSymbolPainter(mode: SymbolPlayMode): Painter
@Composable fun rememberFavoriteSymbolPainter(favorite: Boolean): Painter
@Composable fun rememberExpandSymbolPainter(expanded: Boolean): Painter
@Composable fun rememberEqualizerSymbolPainter(playing: Boolean): Painter
@Composable fun rememberPlayPauseSymbolPainter(playing: Boolean): Painter        // 这次不用：PLAY/PAUSE 保持文字
@Composable fun rememberSparkleSymbolPainter(generating: Boolean, filled: Boolean = false): Painter  // 这次不用
// 每个函数最后还有一个参数 motion: SymbolMotion = LocalSymbolMotion.current

enum class SymbolPlayMode { RepeatAll, Shuffle, RepeatOne; fun next(): SymbolPlayMode }
class SymbolMotion(reduced, spatial, fastSpatial, control, effects, slowEffects, resonance, holdMillis)
SymbolMotion.Default, SymbolMotion.Reduced, LocalSymbolMotion
const val YoinSymbolStrokeWidth = 1.5f
```
