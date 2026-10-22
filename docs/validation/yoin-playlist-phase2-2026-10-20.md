# Yoin Playlist 第二轮实验｜2026-10-20

## 当前状态

本轮基于 `c59e920ec`，工作分支 `codex/mixed-playlist-phase2`，与 DLNA 草稿 PR #71 分开审查。

MusicKit 的结束判定及起播位置保护、Spotify 前台服务探针和 Apple Music → Subsonic → Apple Music 接棒探针已进入代码验证。**本轮尚未安装到平板，也未执行三首接棒或 30 分钟锁屏实验，不能把 P2 / P3 标为通过。** 平板前台出现 PiliPala，Spotify 启动请求后仍回到该页面，因此先确认连续占用时段；已完成日用 APK 和 Room 基线备份。

上一轮 [真机结论](yoin-playlist-2026-10-20.md) 保留。Spotify 的队列隔离、Autoplay 和单项 `uris` 起播问题没有因本轮写出 Service 而被认作解决。

## 实验代码

- `MusicKitEndGate` 将原生 `onItemEnded` 视为候选证据：必须属于当前原生队列项、最终项、Repeat OFF，并有近期真实尾部推进及后续终态观察，才进入 Media3 `STATE_ENDED`。主动命令、旧队列、重复事件、未知身份和过期证据不触发接棒。
- `MusicKitStartPosition` 在目标原生项确认后显式 seek，再观察位置连续确认；新开始请求 0，显式恢复请求保留非零位置。准备阶段暂停，确认后才允许播放。SDK 是否复用 queue ID、是否在确认后仍恢复旧位置，仍需真机验收。
- `SpotifyForegroundProbeService` 由 Service 持有 App Remote，使用真实状态镜像驱动 MediaSession。保留原始空曲目、速度为零和断连；不播放静音音频保活，不申请本地音频焦点，不拦截异曲或伪造完成。可比较无额外 WakeLock 与有限时 WakeLock。
- `MixedPlaylistProbeService` 提供单个三项逻辑 MediaSession，后台显式持有来源账号和本地 fixture，不切换浏览账号、不创建测试 Profile、不写评分/笔记/历史。每次启动下游前必须获得新鲜原生停止状态，观察稳定后才放行；超时和取消不并发启动新后端。
- 全部实验入口仅在 debug APK，shell 入口受 DUMP 权限保护。没有产品入口、歌单持久化或生产总队列。

## 复现入口

仅在无人使用的测试设备上执行；先核对并备份已安装 APK、签名与 Room 版本，更新安装使用 `adb install -r`，结束后恢复原包。不要运行 `connectedAndroidTest`。

受控 Subsonic 音源：

```sh
python3 scripts/qa/dlna-smoke-server.py --bind 0.0.0.0 --seconds 30 --require-auth
```

现有 MusicKit 单后端探针（使用已有加密 Apple Music 账号；`start_position` 可设为 7000 验证显式恢复）：

```sh
adb -P 5038 -s <tablet> shell am start -n com.gpo.yoin/.debug.PlaylistProbeActivity --es cmd apple --es ids 1698276083 --el start_position 0 --es label apple-natural
```

另有 `apple_stop`、`apple_pause`、`apple_play`、`apple_next`、`apple_seek --el position …`、`apple_repeat --ei mode …`。日志 `YoinPlaylistProbe` 包含原生项身份、命令标签、位置确认和 Media3 终态；Seek 到尾部只能用于时序验证，不能替代整曲自然播放。

Spotify Service 实验：

```sh
adb -P 5038 -s <tablet> shell am start -n com.gpo.yoin/.debug.SpotifyForegroundProbeActivity --es cmd authorize --ez wake_lock false --es label spotify-fg-no-lock
adb -P 5038 -s <tablet> shell am start -n com.gpo.yoin/.debug.SpotifyForegroundProbeActivity --es cmd play --es uri spotify:track:3r8RuvgbX9s7ammBn07D3W --es label spotify-fg-no-lock
```

确认真实起播及实际前台身份后再 Home / 锁屏。重做一轮时通过 `connect --ez wake_lock true --el wake_timeout_ms 600000` 开启限时锁，并确认日志的 held 状态。命令还包括 `repeat --ei mode 0`、`pause`、`resume`、`seek`、`next`、`status` 和 `finish`。记录真实事件间隔与系统 freezer 状态，避免测试期间用 ContentProvider / Intent 唤醒应用。原始日志 tag 为 `YoinSpotifyFgProbe`。

三首接棒实验：

```sh
adb -P 5038 -s <tablet> shell am start -n com.gpo.yoin/.debug.MixedPlaylistProbeActivity --es cmd start --es endpoint http://<LAN>:18765 --es ids 1698276083,1698275560 --el runSeconds 0 --es label apple-subsonic-three
```

A / C 为两首 Apple Music 歌曲，B 经真实 Subsonic API 获取认证 stream URL。`runSeconds=0` 播完三首结束；1800 至少运行 30 分钟，并在完整三首轮次结束后停止，不用计时器伪造单曲完成。支持直接命令 `pause`、`play`、`next`、`previous`、`seek`、`stop`、`finish`；`session_pause`、`session_play`、`session_next` 等通过实际 MediaController 验证系统会话入口。日志 tag 为 `YoinMixedProbe`。

## 待执行的真机矩阵

1. MusicKit：整曲自然结束一次且仅一次；早期/尾部 Stop、Pause、回拖、Skip、替换、同曲再次 prepare 均不误推进；停止于非零位置后新开始从 0，显式恢复仍保留位置。
2. Apple → Subsonic → Apple：先前台三首，再锁屏 30 分钟；记录每次停止确认与下一后端实际起播时间，核对音频焦点、重复事件、取消与不可达音源。
3. Spotify：Service 无额外锁与有限时锁分别完整播放 3–5 分钟，确认不进入 cached freezer、没有失联或成批延迟事件，再测系统暂停/恢复及耳机接收方。
4. Web API：显式 Tablet device_id 上轮已覆盖；新增双元素 `uris` 对照尚待测试设备重新出现在 Connect 列表。单曲影子歌单另行验证，不能预设会隔离手动 Queue 或关闭 Autoplay。
5. 完整混合歌单接入前，补齐评分、笔记、历史及 NeoDB 同步按播放条目 profileId 路由的集成验证。Playlist Memory、微渐入和产品 UI 留在可靠接棒之后。

## 自动化与设备保护

最终集成的 MusicKit、Spotify 状态及 Mixed 原型相关测试共 89 项，13 个套件、0 失败、0 错误；`assembleDebug` 与 `assembleReleaseDebugSigned` 通过，16 个涉及 Kotlin 文件的 scoped ktlint 通过。完整 `lintDebug` 通过。没有运行全仓 `test` / `ktlintCheck` 或设备 instrumented tests。

首次多任务构建因 Gradle 2GB 堆发生 GC thrashing 而停止；日志确认后用 `-Dorg.gradle.jvmargs=-Xmx4g --max-workers=2` 重跑单测及 Debug，Release/lint 以单 worker 执行，没有修改项目配置。

本机交付目录为 `artifacts/mixed-playlist-phase2-2026-10-20/`，两个 APK 均为 0.5.0（8）及原 Android Debug 证书，已通过 apksigner 校验。探针仅存在于 Debug 包：

| APK | SHA-256 |
| --- | --- |
| `Yoin-0.5.0-mixed-probe-debug.apk` | `d17f140bc3cc354db70a3d8388b0ed1700b6307f1bccad3fddef7ba31234d0e7` |
| `Yoin-0.5.0-mixed-release-debugsigned.apk` | `22bc0bba87df018a61c30d689a35796d877d328a22c090ad4bbf3c7d1a39e57d` |

本轮未覆盖日用安装，没有更改账号或数据库结构，没有操作 Deezer 或 Sonos。原 APK SHA-256 为 `19a169d564e6e0917337a9f7fcaa8b0db21c4ead76a8105a26dab124b0379802`；Room `user_version=32`，Apple Music / Spotify 各一个账号。私有 APK、数据库快照和原始日志不提交。

结构化状态及构建摘要见 [第二轮证据清单](yoin-playlist-phase2-2026-10-20-evidence.json)。其中真机项目全部标为未执行，不能用自动化结果代替设备验收。
