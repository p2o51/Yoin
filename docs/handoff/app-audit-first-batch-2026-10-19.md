# App audit 第一批修复与验证

基线：`main` / `ee2737b7a`。范围为 Library 播放指示、Memory Widget 暗色对比、Widget 详情路由、删除歌单退场。改动保留在当前工作区，未提交或推送。原审查报告 `app-audit-2026-10-19.md` 未修改。

本轮验证于 2026-10-20（JST）结束。

## 修复行为

- **Library 播放指示**：歌曲、收藏、搜索结果按当前歌曲 ID 显示均衡器和标题强调色。暂停保留当前歌曲标记，均衡器停止；切歌后指示转移。使用既有符号和 `YoinMotion` 弹簧，保留笔记、不可播放提示及长按行为。
- **Memory Widget 暗色对比**：分数与最后播放时间在暗色背景使用封面同色系的 `inkDark`。浅色和背景色保持既有设计；默认配色对比度从约 2.99:1 提高到 13.96:1。测试覆盖深蓝、饱和色、浅色和灰阶封面。
- **Widget 详情路由**：MainActivity 保存待处理目标，等 Landing 就绪、窗口获得焦点且 Shell RESUMED 后，由 Shell 原有 Album / Artist / Playlist 回调决定 Wide 详情栏或 Compact / Medium Activity，并复用底栏交接。待处理目标可恢复、只消费一次；后来的 Widget tap 替换旧目标，Memories tap 清除待开的详情。
- **删除歌单退场**：成功事件进入详情页已有返回控制器，页面、底栏及 Shell 交接完成后再退出；重复事件不会重复退出。Wide 保留原有详情栏退出路径。

没有增加返回处理器、动效阈值或独立的返回状态机。

## 已通过的验证

| 检查 | 结果 |
| --- | --- |
| 全量 Debug 单元测试 | 422 个套件、3795 个测试，0 失败 / 错误 / 跳过 |
| 最后一次 Widget 单元测试 | 12 个测试通过，包括请求消费、生命周期、过期请求、防重放、ID 恢复及配色对比 |
| 设备 UI 测试 | emulator-5570 上 4 个通过：播放指示 2 个、删除退场 1 个、返回所有权 1 个 |
| Lint | 通过仓库既有 baseline；43 个有效警告 |
| ktlintCheck | 配置的任务通过；当前主要检查 Kotlin 脚本，不能据此声称全部 `.kt` 已格式检查 |
| 最后一次构建 | Debug、Debug AndroidTest、ReleaseDebugSigned 全部成功 |
| 差异检查 | `git diff --check` 通过 |

全量测试后又调整了 MainActivity 的请求覆盖和窗口就绪门控；最终重新运行 Widget 测试、Lint 及三种构建。UI 测试涉及的行组件和歌单返回代码在成功运行后未再修改。

路由门控只观察 `holdShell` / `showing` / 窗口焦点；不读取 Splash 的普通 `decided` 字段，避免该字段变化未触发重组而使 Widget 请求等待。

Gradle 默认 2 GB 堆曾发生 GC thrashing；验证使用临时命令参数 `-Dorg.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8`、`--no-parallel` 和有限 workers，未修改仓库构建配置。构建仍有 MusicKit 依赖的 R8 stack-map 警告。

## 设备验证边界

- 早期构建手工确认过 Compact 专辑 Widget 入口路由到详情 Activity；当时无 Profile，所以验证了目标和窗口路由，没有验证远端详情内容。
- 最终构建的矩阵重试尝试 Compact / Medium 六项，均未在每项 25 秒冷启动 / 10 秒热启动预算内同时确认对应日志、前台目标和焦点；Wide 因总预算耗尽未运行。Compact 歌单详情已出现于 Activity 栈，但焦点仍属 Shell，未记作通过，也未发送返回键。
- 因此完整尺寸矩阵和有效焦点下的 Widget 往返检查仍未完成。过程中出现 ADB 命令超时和窗口交接缓慢；现有证据不足以确定是生产路由缺陷或特定环境根因。最终矩阵记录：`/private/tmp/yoin-widget-route-matrix-final.json`。
- 模拟器尺寸与密度已恢复为原始 `1280×2856` / `480`。未重启 ADB，未操作正在其他 QA 会话中的平板；本轮未发现连接的 Pixel 11 Pro。
- UI 用例使用直接 instrumentation，未运行会自动卸载应用的 `connectedAndroidTest`。最终 QA 安装使用本轮创建的独立 `com.gpo.yoin.qa` 包。

## 构建产物

`release/Yoin-0.5.0-release-debug-signed-20261019-first-audit-batch.apk`

- package：`com.gpo.yoin`；version：`0.5.0 (8)`。
- APK SHA-256：`9900cf685a41b5fa6d46675f6ce963282243ca39db87e7d70238a4e96248d4ba`。
- 签名验证通过，Android Debug 证书 SHA-256：`5b70f9f06f557d2a065522cef70e0dd8c275dd02e04297ca4f8560bf28ec7fcf`。

主要原始日志：`/private/tmp/yoin-first-batch-tests.log`、`/private/tmp/yoin-first-batch-qa-ui-tests-retry.log`、`/private/tmp/yoin-first-batch-final-validated.log`。日志为本机临时验证材料。
