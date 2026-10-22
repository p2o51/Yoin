# Yoin Playlist 真机技术验证｜2026-10-20

## 当前结论

Spotify 的“每次只播一首”有可行的受限场景，但 **P1 整体未通过，暂不能宣布自动跨源接棒可用**：

- 前台、手动队列为空、Autoplay OFF、Repeat OFF 时，App Remote 单曲播放完成了 20 次结束观察：3 次完整自然播放、17 次 seek 到末尾，涉及 3 首不同歌曲。每次开始均确认真实目标曲目从头播放；实验没有用计时器截断歌曲。
- 原有手动待播队列会保留并串入；`play(trackUri)` 不等于隔离单曲。
- Web API 单项 `uris` 返回 204，却没有真正起播；同账号、同平板、同曲目的专辑 `context_uri` 对照能正常起播。
- 仅保留 Activity 的 App Remote 连接不足以在后台调度：Android 冻结了 Yoin 进程，结束事件延迟至进程解冻才送达。需要继续按方案设计播放 Service，不能把本次现象解释为 Service 已验证失败。

P2 的三首歌自动接棒和 30 分钟锁屏混播尚未进行；没有实现生产混播协调器、统一总队列或新歌单 UI。当前平板没有 Subsonic 账号，不能把其他任务的临时音源测试写成用户 Subsonic 资料库的混播验证。

## 基线与数据保护

- 工作树 `/Users/gpo/.codex/worktrees/dlna-playlist-validation/Yoin`，基线 commit `ee2737b7a99b15e1486f40094fafe6b551dc1510`，另含本任务 DLNA 与 debug 探针 diff。
- Pixel Tablet，ADB server 5038，Android 17；Spotify 9.1.92.3058（147346334），Apple Music 7.0.0-beta（1611）。Spotify App Remote 0.8.0，MusicKit auth 1.1.2 / playback 1.1.1。
- Yoin 0.5.0（8），Room `user_version=32`。存在 Spotify、Apple Music 各一个 profile，没有 Subsonic profile 或旧 `server_config` 表；未创建、删除或切换用户账号。
- 安装前已核对签名：原 APK 与测试 APK 证书 SHA-256 均为 `5b70f9f06f557d2a065522cef70e0dd8c275dd02e04297ca4f8560bf28ec7fcf`。使用 `adb install -r` 保留数据；没有卸载、清数据或运行 `connectedAndroidTest`。
- 测试前原 APK 已备份 `/tmp/yoin-playlist-20261020/installed-before.apk`，SHA-256 `19a169d564e6e0917337a9f7fcaa8b0db21c4ead76a8105a26dab124b0379802`；测试结束恢复该原包，保留原日用修复。
- 首个实际测试 APK SHA-256：`6f3b1c25b614d62763ac1c295825448adb7ad27a1f79275a06ffa860cc5786d2`。`assembleDebug` 使用 Android Studio JBR 及 `-PyoinSymbolsDir=/Users/gpo/Developer/yoin-symbols` 构建通过。
- 所有 Spotify Web API 控制均明确指定唯一的 Pixel Tablet / Tablet 设备，没有向音响切换输出，没有操作 Deezer。平板音频框架显示 Spotify 本地 AAudio 输出为 started，但系统音量为静音，因此本报告不声称进行了可闻音质验收。
- PR #64 已于 2026-10-19T12:11:50Z 合并，merge commit `e45e53846d9100e825f55fe8c16c8b22ba467ba2` 是当前 HEAD 的祖先。跨账号复制歌单已落地；它没有实现跨源连续播放。

## Spotify 结果

| 场景 | 实际结果 | 判断 |
|---|---|---|
| App Remote，空手动队列，Autoplay OFF / Repeat OFF，前台 | 20 次稳定终态；3 次 Get Up 完整自然结束，17 次 seek 末尾，seek 样本轮换 Get Up / New Jeans / Cool With You | 在这些条件下观察到自然停播；不是完整 P1 通过 |
| 原有手动队列非空 | 先 queue(Cool With You)，再 play(Get Up)，Web API 读取确认待播项前后都存在；Get Up 尾部结束后 Cool With You 实际播放到 1.274s | 单曲请求无法隔离原待播队列 |
| Autoplay ON，手动队列空 | Get Up 结束后推荐曲 Right Now 实际播放到 9.414s | 与 Repeat 不同；必须关闭 Autoplay |
| Repeat ONE | Get Up 越过末尾后同 URI 从头继续，观察到 2.679s、仍 playing | 不能把同 URI 的位置回零直接当作结束 |
| 末尾暂停 | seek 至 32s 后 pause，5s 后仍暂停于 32.240s | 与自然终态不同；命令必须关联 |
| 末尾回拖 | seek 至 32s 后 seek 回 1s，随后播放位置为 3.987s | 不能因曾接近末尾就推进 |
| Web API 单项 uris | Get Up（不带 position_ms）、New Jeans、Cool With You 均返回 204，但实际 item=null、paused、position=0；Get Up 显式 offset.position=0 也相同 | 当前客户端/环境下启动失败；不能从 HTTP 成功推断播放 |
| Web API 专辑 context 对照 | 同设备 PUT context_uri=该专辑，offset.position=4，position_ms=0；实际 Get Up 播放至 3.014s | 账号、权限、设备控制链可用，单项 uris 路径仍需单独解决 |
| 官方 Spotify App 对照 | 点击专辑 Get Up，从头完整播放，专辑顺序自然进入 ASAP | 同曲目在官方客户端可播；不作为单曲停播样本 |

### 真实结束状态序列

App Remote 没有原生自然完成回调。实际序列不是“固定停在 duration”：接近末尾 → speed=0 / paused=false → 可能暂时 track=null、context 为空 → 恢复同 URI、paused=true、speed=0、position=0。终态事件可能重复。

采样脚本最初把样本 02 的暂时 null 误归为“意外换曲”而停止该段采集；完整 trace 随后恢复到了预期的暂停终态，最终证据摘要已按真实完整序列校正。没有把 null 当作另一首歌，没有生产自动推进代码。

### 后台冻结实验

样本 03 是 New Jeans 完整自然播放（108.987s），播放后 Home → Dozing。最后一条实时位置为 81.732s；ActivityManager 在 wall `1792473463.654` 明确记录 `freezing 20474 com.gpo.yoin`。之后探针轮询与事件均停止。

后来一次 debug token-provider 读取在 wall `1792473524.880` 解冻进程，排队的末尾事件才集中送达：108.128s → null → 原曲 position=0 / paused。结束事件比预计曲尾迟约 34s。Spotify Web API 独立确认音频已经结束。这一条单列为后台调度失败，没有混进 20 次前台成功结果。

该探针故意保留 Activity stop 后的 binding，仍不能阻止 cached process 被冻结；生产 `SpotifyAppRemotePlayer.onHostStop()` 当前还会直接断开连接。Service 的连接寿命、前台状态、音频焦点、媒体会话接收方和实际锁屏混播仍需后续原型实测。

## Apple Music

官方 Apple Music App 能播放同一首 Get Up（catalog `1698276083`）；系统 session 显示 PLAYING，随后手动暂停确认在 17.083s。订阅及曲目可用性对照通过。

修正后的 Apple 专用测试 APK SHA-256 为 `92097c9b552052b7c63ada1ea45665d3e0122bea187a28723e90f24dc7cb49a1`，签名与原包相同。

初版 debug 探针用了 prepare → play，首次停在 READY / position=0；这与生产 PlaybackManager 的 setMediaItems → playWhenReady=true → prepare 顺序不同。复现后已修正探针启动顺序，不能把首次探针错误认作 MusicKit/订阅故障。

已有直接回调证据：未播完的 Get Up 在主动 Stop（12.357s）时触发 `onItemEnded(end=12357)`；替换一个准备好但尚未起播的队列也触发 `onItemEnded(end=0)`。该回调不是“自然播完”的充分条件，必须结合命令、队列代次和状态处理。

修正顺序的新 APK 首次从 21ms 起播。主动 Stop 在 3.028s 再次触发 onItemEnded，并回到 Media3 STATE_IDLE / position=0。之后重新 prepare 曾恢复到旧的 3.028s，尚未证明现有 rewind guard 能覆盖该时序；完整自然样本在起播后显式 seek 到 0，再连续播放整段，不把这一条写成自动从头起播的成功。

完整样本显式回到 0 后，第一条真实播放观察为 178ms，随后没有再 seek / stop，连续播放至 `onItemEnded(end=36284)`。最终 Media3 是 `STATE_READY(3)`、`isPlaying=false`、`position=0`，**没有进入 STATE_ENDED**；不能只监听 Media3 ended 来调度接棒。

双曲队列 Get Up → New Jeans 中，在 Get Up 约 3.876s 主动下一首，同样收到 Get Up 的 `onItemEnded(end=3876)`，随后 New Jeans 实际播放至 756ms；之后显式 Stop 结束实验。自然结束、Stop、Skip、队列替换均可能触发该回调，且回调时的 controller state / currentPosition 会受切换时序影响。与 [MusicKit 官方 listener 说明](https://developer.apple.com/musickit/android/com/apple/android/music/playback/controller/MediaPlayerController.Listener.html)一致，需要关联用户命令和 transitionId；只新增完成回调监听仍不够。

Apple Music ↔ Subsonic 的真实双向接棒、MediaSession/音频焦点交接和长时间后台尚未验证；本次不把单后端事件验证写成 P3 已完成。

## 证据与复现

- [结构化样本和控制摘要](yoin-playlist-2026-10-20-evidence.json)：每个样本保留命令标签、真实从头播放状态、末尾状态、稳定终态、null 过渡与重复终态事件数。
- 原始私有 QA 目录 `/tmp/yoin-playlist-20261020/`：`probe.log`、`end-samples.jsonl`、`controls.jsonl`、`web-variants.jsonl`、`autoplay-on.jsonl`、官方 App UI/session 快照。数据库与 APK 基线快照不得加入仓库。
- `PlaylistProbeActivity` 仅打进 debug APK，要求 shell 的 DUMP 权限。日志只写公开曲目 ID、位置、命令及生命周期，不记录 Token、密码或签名音频 URL。
- MusicKit `onItemEnded` 结构化探针受 `BuildConfig.DEBUG` 保护；正常播放逻辑未改变。

示例：

```sh
adb -P 5038 -s <tablet> shell am start -n com.gpo.yoin/.debug.PlaylistProbeActivity --es cmd connect --es label sample01
adb -P 5038 -s <tablet> shell am start -n com.gpo.yoin/.debug.PlaylistProbeActivity --es cmd play --es uri spotify:track:1wUnuiXMMvhudmzvcCtlZP --es label sample01
adb -P 5038 -s <tablet> logcat -v epoch YoinPlaylistProbe:I '*:S'
```

Spotify Autoplay 测试前为 ON，已在官方 App 恢复并重新读取确认 ON。测试完成后 Spotify 已暂停，Repeat OFF / shuffle=false；开始时 Web API 没有当前 item，因此更早的循环/随机设置不可确认，仅能保留首次官方播放时观察到的 OFF/false。

## 收尾与交接

已用 `adb install -r` 恢复测试前原始日用 APK，并重新从平板 pull 已安装包，SHA-256 与备份完全相同：`19a169d564e6e0917337a9f7fcaa8b0db21c4ead76a8105a26dab124b0379802`。恢复后 Room 仍为 32，Spotify / Apple Music 各一个 profile。Spotify PAUSED 9.614s、官方 Apple Music PAUSED 17.083s，Yoin 无活跃媒体会话。平板随后已明确交还给 DLNA 验证负责人；本验证没有继续控制设备。

后续技术门槛：先解决 Spotify 单曲启动路径和旧待播队列隔离/拒绝策略，再实现带命令代次、结束推断去重、停止确认、超时与取消的协调器；连接寿命移入真正的播放 Service 后，才进行 30 分钟锁屏混播与系统媒体控制矩阵。MusicKit 需要补足自然结束适配和停止后起播位置验证。完整歌单持久化与 UI 应继续等待上述结果。
