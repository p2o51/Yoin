# YoinPerf 打点（只在 debug 包里生效）

> 2026-10-10 加入（Batch 0）。用途：在 Pixel Tablet 上量「点开 Album/Artist 详情慢」「进 Home 久」
> 「图片丢了不恢复」的基线，改数据路径之后用同一套打点再量一次做对比。

## 开关

- 入口：`app/src/main/java/com/gpo/yoin/perf/YoinPerf.kt`（`YoinPerf.mark / begin / end`）。
- 只认 `BuildConfig.DEBUG`。release 和 releaseDebugSigned 里 `YoinPerf.enabled == false`：
  不写日志、不打 Trace、不给 OkHttp 加拦截器、不给 Coil 加 listener。
- 每个事件一行 logcat，tag 固定 `YoinPerf`，级别 D，格式：

  ```
  <event> k1=v1 k2=v2 … t=<SystemClock.elapsedRealtime() 毫秒>
  ```

  值里的空白换成 `_`，值为 null 的字段整个省略。`t` 是同一时钟，跨线程、跨 Activity 可以直接相减。
- 同名 Trace 段：点事件（`mark`）是零长度的同步 slice；带 `ms=` 的跨度事件（`begin/end`）
  是 async slice（API 29+）；`http` 是包住 `chain.proceed` 的同步 slice。Perfetto 抓包时在
  `atrace_apps` 里加 `com.gpo.yoin`（debug 包可调试，app 段能抓到）。

## 事件表

### 详情页

| 事件 | 字段 | 打在哪 | 含义 |
| --- | --- | --- | --- |
| `detail.click` | `kind=album\|artist\|playlist` `id=<provider:rawId>` `via=` | `YoinNavHost.kt` 的 `navigateTo{Album,Artist,Playlist}FromShell`、Memories 印章的 `onOpenAlbum`、`pushPane`；`AlbumDetailActivity` 的 `onOpenArtist`、`ArtistDetailActivity` 的 `onAlbumClick` | 用户请求打开详情。`via=activity`（推独立 Activity）/ `pane`（同窗分列，替换右列根页）/ `pane-push`（右列里再推一页）/ `push`（详情 Activity 里点到另一个详情）。被 launch gate 挡掉的点击不打。shell 里的 Now Playing 点专辑/艺人也走 `navigateTo*FromShell`，同样会打；没覆盖桌面小组件、详情 Activity 里 Now Playing 的入口（`launchChildDetail(…, fromNowPlaying = true)`）、以及横竖屏切换时把右列页面转成 Activity 的自动重开。点击即预取（`ui/detail/DetailPrefetch.kt`）挂在这些入口最前面、launch gate 之前，上面没打点的小组件和详情页里 Now Playing 的入口也有：被挡掉的点击不打 `detail.click`，但照样预取。小组件冷启动时 source 还没建好，预取等它建好再发（最多等 3 秒）。 |
| `detail.load` | `kind` `id` `src=` `ms=` [`provider`] [`joined=true`] [`err`] | `YoinRepository.loadCachedDetail` | 一次 `getAlbum/getArtist/getPlaylist` 调用从进门到拿到结果的耗时。`src=mem`（内存新鲜命中）/ `disk`（Room 磁盘新鲜命中）/ `net`（走网络，带 `provider`）/ `stale`（网络失败，退回磁盘或过期内存，`err=` 是网络异常类名）/ `err`（整体失败或调用方被取消，`err=` 是异常类名）。`joined=true`：这次调用搭了别人已在飞的同一请求（预取、并发读者）。有了点击即预取，一次打开通常有两条：预取那条（从点击起计时）和 VM 自己那条。两条等的是同一个请求时几乎同时结束，谁先打出来不固定；发起请求的一方不带 `joined`，通常是预取，搭车的一方带 `joined=true`，它的 `ms` 从它自己进门算起、偏短。预取已经落地时 VM 那条是 `src=mem`（歌单只认 5 秒内从网络拿到的内存副本，离线回退的旧副本不算，过了照样联网）。 |
| `detail.diskWrite` | `kind=ALBUM\|ARTIST\|PLAYLIST` `chars=` `ok=` `ms=` | `DetailCacheStore.write` | JSON 编码 + 等锁 + upsert（+ 可能的 trim）。网络路径的磁盘写在数据交给调用方之后、在后台做，不算在 `net` 那次 `detail.load` 的 `ms` 里。`skipped=true`：拿到锁时这条已经作废（点赞、关注、歌单编辑）或已经切了账号，没写。 |
| `detail.diskTrim` | `rows=` `ms=` | `DetailCacheStore.trimToBudget` | 超 24MB 预算时的 SUM + 扫描 + 逐行删除，删到 20MB 为止（回滞，免得之后每次写都再 trim；刚写的那行、刚读出而 LRU touch 还没落库的行都不删）；`rows` 是删掉的行数（SUM 发现没超就是 0）。 |
| `detail.content` | `kind` `id` [`resolved`] | `Album/Artist/PlaylistDetailViewModel` 第一次把 `_uiState` 设成 Content 之后 | VM 级别的「数据就绪」。每个 VM 只打一次（retry / 歌单刷新不重复打）。`id` 是打开时请求的 id；`resolved` 只在内容实体 id 不同时出现（Apple Music 会把 library 专辑折叠成目录专辑）。 |
| `detail.visible` | `kind` `id` `host=window\|pane` `commit=` | `DetailEnterIntro.kt` 的 `DetailPerfVisibleEffect`，挂在三个 Screen 的 `AnimatedContent` Content 分支里 | Content 第一次组合后，下一次帧提交（`registerFrameCommitCallback`，API 29+）完成的时间。`id` 是内容实体 id（= `detail.content` 的 `resolved`，没有 `resolved` 时 = `id`）。`commit=false`：帧提交握手失败（1.5s 超时或视图已脱离），这一行的时间不可信。**近似值**：若 Loading 先上了屏（700ms 超时、或分列模式），这一帧是 Content 交叉淡入的开始，不是完全不透明的时刻；Activity 模式下页面还在 96dp 滑入途中（不透明）。同一页面组合里只打一次。 |

### Home

| 事件 | 字段 | 打在哪 | 含义 |
| --- | --- | --- | --- |
| `home.loading` | `ms_since_process_start` | `HomeViewModel` 构造（init）时一次；之后每次 `emit` 从非 Loading 退回 Loading 时 | 冷启动时 Home 从 VM 创建起就停在 Loading，直到 `home.content`。 |
| `home.content` | `ms_since_process_start` `sections=` `src=mem\|disk\|fresh` | `HomeViewModel.emit` 第一次发出 Content | `sections` 是非空区块数（Activities、Jump Back In 网格、Recently Added、Rediscover、Recently Played、Your Playlists，最多 6）。`src=mem`：进程内缓存（同进程里重建 VM）；`disk`：Spotify 的本地预绘；`fresh`：完整加载的结果。每个 VM 只打一次。 |
| `home.refresh` | `provider` `result=ok\|error\|superseded` [`err`] `ms=` | `HomeViewModel.refresh` | 一次完整刷新（从读缓存到 fresh 内容发出）的耗时；`superseded` = 期间切了账号，结果作废。 |

`ms_since_process_start = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()`，
只有冷启动时有意义（热启动时进程早就在了）。

### 网络

| 事件 | 字段 | 打在哪 |
| --- | --- | --- |
| `http` | `host` `path` `code` `cache=net\|hit\|cond\|none` `ms` `thread`，失败时没有 `code/cache`、多一个 `err=<异常类名>` | `YoinPerfHttpInterceptor`，挂在 `AppContainer` 的 `spotifyHttpClient`、`subsonicBaseHttpClient`，以及 `AppleMusicApiClient`、`AppleMusicDeveloperTokenProvider` 的 client 上 |

- **绝不记录 query、header、body**（Subsonic 的 `u/t/s` 认证参数在 query 里）。`path` 里纯数字段、
  Apple Music 的 `l.xxx/i.xxx/pl.xxx`、字母数字混合的长段（Spotify base62 id、图片哈希、UUID）替换成
  `{id}`；Subsonic 的 `getAlbumList2.view` 这类端点名保留。
- `ms` 是到拿到响应头为止，不含读 body。
- `cache`：`hit` = OkHttp 磁盘缓存直接命中，`cond` = 条件请求（304 重新验证），`net` = 纯网络。
- 应用拦截器：重定向、重试在它里面完成，一行代表一次调用。
- 歌词、NeoDB、Gemini、Drive 同步、播放流（Media3）的 client 没挂。

### 图片（Coil）

| 事件 | 字段 | 打在哪 |
| --- | --- | --- |
| `image.ok` | `src=MEMORY_CACHE\|MEMORY\|DISK\|NETWORK` `ms` | `YoinApplication.newImageLoader` 里 debug 才加的 `YoinPerfImages` listener |
| `image.error` | `host` [`code`] `err` `ms` | 同上 |

- 不记 URL，只记 host（非网络数据记数据类型名）；`code` 只在异常链里有 `coil3.network.HttpException`
  时出现。`ms` 从这次请求 `onStart` 算起（含排队）。
- ImageLoader 的其他配置（缓存策略等）没动。

## 抓日志

平板走独立 adb server（端口 5038），详见 `~/.claude/CLAUDE.md`：

```sh
ADB="env ANDROID_ADB_SERVER_PORT=5038 $HOME/Library/Android/sdk/platform-tools/adb -s adb-3408105H803AEE-Deuouc._adb-tls-connect._tcp"

$ADB logcat -c
$ADB logcat -v raw -s YoinPerf:D | tee yoinperf.log      # -v raw：每行只有消息本身
```

冷启动量 Home：

```sh
$ADB shell am force-stop com.gpo.yoin
$ADB logcat -c
$ADB shell am start -W -n com.gpo.yoin/.MainActivity
$ADB logcat -d -v raw -s YoinPerf:D | grep '^home\.'
```

只看某类：`grep '^detail\.'`、`grep '^http '`、`grep '^image\.error'`。

## 按 id 配对算时长

一次打开详情的链路：

```
detail.click  →  detail.load  →  detail.content  →  detail.visible
   (点击)        (repo 返回)      (VM 发 Content)    (Content 帧上屏)
```

规则：

1. 以 `detail.click` 为起点，键是 `(kind, id)`。
2. 同键、`t` ≥ 点击时间的第一条 `detail.content` 归这次点击。`detail.load` 取同键、`t` ≥ 点击时间、
   不带 `joined=true` 的第一条（没有再退回第一条）：点击即预取和 VM 搭车的那条等的是同一个请求，
   几乎同时结束，先后不固定，不带 `joined` 的是发起请求的一方，通常就是点击发出的预取。
   艺人页预取前 6 张专辑的那些 `detail.load` 前面没有对应的点击，自然配不上；预取之后再点进去，
   点击后的那条 `detail.load` 一般是 `src=mem`。
   `ms` 是这一条自己从进门算起的耗时，不一定从这次点击算起：被 launch gate 挡掉的点击也会预取，
   紧接着再点一次时，前一次的预取那条会落在这次点击之后、配给这次点击，`ms` 从更早那次点击算起。
   所以点击到数据看 `load.t - click.t`，不要直接用 `ms`。
3. `detail.visible` 用内容 id：先从同次的 `detail.content` 取 `resolved`（没有就用 `id`），
   再找同 kind、该 id、`t` ≥ content 时间的第一条 `detail.visible`。
4. 时长都是 `t` 相减：
   - 点击 → 数据：`load.t - click.t`（有点击即预取时 ≈ 加载本身，和 Activity 启动 / 分列展开并行；
     没有预取的入口 ≈ Activity 启动 / 分列展开 + VM 创建 + 加载本身）
   - 数据 → VM：`content.t - load.t`（数据先到时，是 Activity 启动 / 分列展开 + VM 创建还剩下的部分；
     VM 拿到数据后到发 Content 之间不再等 visit 记录写库）
   - VM → 上屏：`visible.t - content.t`（Activity 模式含 200ms 底栏交接等待和入场门控）
   - 总计：`visible.t - click.t`

示例脚本（Python 3，读 `-v raw` 抓下来的文件）：

```python
import re, sys

def parse(path):
    for line in open(path, encoding="utf-8"):
        parts = line.split()
        if not parts or "=" in parts[0]:
            continue
        fields = dict(p.split("=", 1) for p in parts[1:] if "=" in p)
        yield parts[0], fields

events = list(parse(sys.argv[1]))
for i, (name, f) in enumerate(events):
    if name != "detail.click":
        continue
    kind, req, t0 = f["kind"], f["id"], int(f["t"])

    def first(event, ident, after, ok=lambda g: True):
        for n, g in events[i + 1:]:
            if n == event and g.get("kind") == kind and g.get("id") == ident and int(g["t"]) >= after and ok(g):
                return g
        return None

    load = first("detail.load", req, t0, lambda g: "joined" not in g) or first("detail.load", req, t0)
    content = first("detail.content", req, t0)
    shown = content.get("resolved", req) if content else req
    visible = first("detail.visible", shown, int(content["t"]) if content else t0)
    row = [kind, req, f.get("via"), load and load.get("src"), load and load.get("ms")]
    for g in (load, content, visible):
        row.append(int(g["t"]) - t0 if g else None)
    print("{} {} via={} src={} load_ms={} click→load={} click→content={} click→visible={}".format(*row))
```

Home 冷启动：`home.content` 的 `ms_since_process_start` 就是「进程起来到 Home 有内容」；
和 `home.loading`（VM 创建）的差就是 Home 停在 Loading 的时长。

## 已知的近似和空白

- `detail.visible` 见上表：是「Content 开始上屏」，不是动画结束。
- `detail.load` 的 `src=err` 也包括调用方被取消（离开页面），`err=JobCancellationException`。
- stale-while-revalidate 的后台刷新不打 `detail.load`，只能从 `http` 行看到。
- 分列模式下右列从无到有展开时，详情页（连同它的 VM）要等展开弹簧落定
  （`DetailPaneState.pageReady`）才组合；点击即预取让加载和展开并行，所以这种 `pane` 点击的
  「点击 → load」不再含展开时间，「load → content」里才有。右列已经开着时换页没有这段。
- 单测里 `YoinPerf.sink` 可替换（`YoinPerfTest`）；release 单测里 `enabled == false`，相关断言跳过。
