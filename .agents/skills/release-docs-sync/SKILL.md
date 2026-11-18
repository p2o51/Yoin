---
name: release-docs-sync
description: Step-by-step workflow for synchronizing documentation, facts/terms references, READMEs, and yoin-site pages before every Yoin version release (0.x.y) or after user-facing feature, provider, settings, shortcut, or limit changes across Android, iOS/iPadOS, macOS, and HarmonyOS.
---

# Yoin 发版与功能文档同步（Release & Feature Docs Sync）

任何用户可见功能、音源行为、设置项、快捷键或能力限制的 PR，以及每一次版本发版（`0.x.y`）签核前，都必须执行本同步流程。

## 0. 前置原则与事实校验

1. **只写核实过的代码事实**：对照实际代码分支核对功能、路径、类名与限制，禁止凭记忆编写或补全细节：
   - Android（`app/`）与鸿蒙（`harmony/`）：检查 `origin/main`；
   - iOS / iPadOS、macOS（iPad-on-Mac）与 `:shared` KMP：检查 `origin/claude/project-thread-2amedi`（合并进 `main` 后看 `main`）。
2. **严格遵守术语与隐私边界**：执行前先核对 [docs/i18n/zh-glossary.md](../../../docs/i18n/zh-glossary.md) 与 [01｜Yoin 事实与术语](../write-copy/references/01-yoin-facts-and-terms.md)：
   - 必须使用：`Memories`（始终英文）、`剪贴簿`、`微件`、`别人怎么说`、`为你推荐`、`在线 AI`、`中国直连`、`封闭测试` / `开放测试`、`音源`、`自建服务器`、`接着听`、`全部 Memories`、`在你的其他资料里`、`关于`。
   - 严禁出现：零追踪（绝对无追踪承诺）、`回忆`（指代 Memories）、`手账`（用于新文案）、`国内直连`、`内测` / `公测`、`Nowledge Mem` / `nmem`。
3. **只改文档与文案**：不顺手改应用代码或构建配置；如果发现代码与设计文档有缺口，在跨端文档中如实记录并反馈给代码负责方。

---

## 1. 仓库内规格与平台文档同步

按改动范围逐一核对并更新（**功能 PR 必须在同一 PR 内完成本节更新**）：

1. **`docs/design.md`**：更新 UI 交互、排版与窗口档位规则、设置入口路径、功能规格及边界说明。
2. **`docs/platforms.md`**：更新 Android、iOS/iPadOS、macOS（iPad-on-Mac）、HarmonyOS 四端能力矩阵与实现状态；**若某功能只在部分平台先上线，必须在此登记跨端缺口与原因**（例如平台 SDK 限制）。
3. **`docs/integrations/provider-alignment.md`**：更新 Subsonic/OpenSubsonic、Spotify、Apple Music 的接口能力、官方限制（如 `/me/library` 迁移、开发模式限制）与四端差异。
4. **专项文档（按涉及范围更新）**：
   - `docs/cloud-sync.md`（Google Drive `appDataFolder` 同步表、排除项、多设备合并规则）
   - `docs/playlist-transfer.md`（歌单云端备份、`TrackMatcher` 匹配与逐首恢复审阅流程）
   - `docs/continue-on.md` / `docs/haptic-feedback.md` / `docs/ci-cd.md`
   - `docs/i18n/zh-glossary.md`（新增界面名词时补充中英对照）
   - `docs/README.md`（新增或重排文档时更新索引）
5. **平台专属文档**：
   - 涉及鸿蒙端结构、通道或限制：同步更新 `harmony/docs/`；
   - 涉及 iOS / iPadOS / macOS / `:shared`：同步更新 `iosApp/docs/`。

---

## 2. 应用内 Tips 核对

检查本次新增或修改的手势、快捷键或隐藏入口是否已同步更新应用内 Tips：

- Android：`HomeBubbleTip`（`app/src/main/java/com/gpo/yoin/ui/home/HomeBubbleSay.kt`）及 `ui/tips/` 演示组件（对应更新 `updated` 日期以便未读提示重现）；
- 同步确认 iOS / 鸿蒙等已有 Tips 机制的平台是否跟进；
- Tips 文案需由 Gemini/Argon 通过 `.argon/agy-yoin` 生成，保持一贯口径。

---

## 3. `README.md` 与 `README.zh-CN.md`（发版必检）

版本发版签核前，对照仓库根目录双语 README 核对：

- 当前版本号与测试轨道状态（如 Google Play 封闭测试版本、TestFlight、HarmonyOS 进展）；
- 新增或调整的核心能力要点与四端说明；
- 技术栈版本如有升级（如 Kotlin / Compose / Media3 / Room / `:shared` / Flutter OHOS），同步更新对应行，保持中英文一致。

---

## 4. 事实与术语基准（`01-yoin-facts-and-terms.md`）

更新 [`.agents/skills/write-copy/references/01-yoin-facts-and-terms.md`](../write-copy/references/01-yoin-facts-and-terms.md)：

1. **产品概况与版本**：更新最新版本号、四端现状、支持的音源与重点能力。
2. **AI 与数据/密钥存储边界**：逐项核实模型、外接搜索服务、端侧 AI 与密钥存储位置（例如区分 `noBackupFilesDir/ai_provider.json` 与 Room `gemini_config` 的备份差异），绝不写出与实际代码或隐私政策矛盾的承诺。
3. **标准术语**：补充新出现的固定译名与禁用表达。

---

## 5. 官网 `yoin-site` 同步（与 Argon 发版说明并行）

当为版本发版准备双语更新日志，或 `write-copy` 标出了从更新日志精简下沉的使用细节时，在独立的 `yoin-site` 隔离工作树中同步以下文件（**中英文 `public/` 与 `public/zh/` 必须成对更新，保持 DOM 结构一致**）：

| 目标文件 | 同步内容 | 备注 |
| --- | --- | --- |
| `public/changelog.html`<br>`public/zh/changelog.html` | 新增版本区块、移动 `Latest / 最新` 标签、一句话 `.sum`、脱敏测试者致谢、新增 `public/spot.js` + `changelog.css` 前后对比草图 | 保持精简，不写成操作说明书 |
| `public/index.html`<br>`public/zh/index.html` | 更新 Hero 与页脚两处 `a.ver` 版本胶囊、四端状态徽标及对应功能亮点 | 首页文案风格见 `yoin-site/AGENTS.md` |
| `public/licenses.html`<br>`public/zh/licenses.html` | 更新页脚 `a.ver` 版本胶囊；如本版引入新开源库或字体则补充列表 | 与应用内开源许可页保持一致 |
| `public/docs.html`<br>`public/zh/docs.html` | **承接所有从更新日志精简下沉的具体规则**：表单字段、可选参数/档位、支持的服务列表、平台差异、额度/数量限制、快捷键列表与故障排查 | 对应 `write-copy` 输出的文档同步清单 |
| `public/privacy.html` | **仅当**本版新增或修改了数据流向、第三方 SDK、联网请求、AI 服务商/外接搜索或密钥存储位置时更新 | 法律文本，改动须经 51 确认 |

如果当前任务仅有权修改 `Yoin` 仓库而不能直接改动 `yoin-site`，必须在输出中附带一份按上述文件拆分的 **`yoin-site` 待同步清单**。

在 `yoin-site` 改动完成后，执行校验与本地预览：

```bash
python3 tools/check.py   # 必须输出 ok
python3 -m http.server 4611 --directory public
```

---

## 6. 完成签核检查表

发布或合并前确认以下各项全部满足：

- [ ] 本次改动涉及的 `docs/` 规格与 `docs/platforms.md`（含单端缺口记录）已更新，与目标代码一致
- [ ] 若涉特定平台，已同步 `harmony/docs/` 或 `iosApp/docs/`
- [ ] 新增手势 / 快捷键 / 隐藏入口已同步更新 Tips
- [ ] （发版时）`README.md` 与 `README.zh-CN.md` 版本号及亮点已对齐
- [ ] `.agents/skills/write-copy/references/01-yoin-facts-and-terms.md` 已同步最新事实、术语与密钥/隐私边界
- [ ] `yoin-site`（`docs.html`、`zh/docs.html`、`changelog.html`、`zh/changelog.html`、`index.html`、`zh/index.html`、`licenses.html`、`zh/licenses.html`，以及必要时的 `privacy.html`）已同步或已输出清晰的待同步清单，且 `python3 tools/check.py` 通过
- [ ] 全文未出现禁用术语（绝对“零追踪”声明、`Nowledge Mem`、`nmem`、`回忆`、`手账`、`国内直连`）
