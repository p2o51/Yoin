# Yoin AI 问答 + 联网搜索：Provider 方案研究

> 状态：研究报告，本轮不改业务代码 · 撰写日期：2026-10-16 · 面向：v0.5.0 closed test 及之后
> 配套脚本：[`scripts/ai-research/`](../../scripts/ai-research/)（key 只从环境变量读，输出写到系统临时目录，不进仓库）
> 资料来源统一编号见文末[附录 A](#附录-a资料来源)，正文用 `[G1]` 这类编号引用。**所有链接的查阅日期都是 2026-10-16。**

## 怎么读这份报告里的「事实」

本轮的执行环境网络受限：WebFetch 解析不了外网域名，curl 只能访问 Google API、developer.android.com 和 GitHub；第三方 AI 平台和 MusicBrainz / Discogs / Wikipedia 都被代理挡掉（`CONNECT 403`）。因此每条价格、条款结论都带一个核实等级标签：

| 标签 | 含义 |
|---|---|
| **读原文** | 直接读到了一手内容：Gemini API discovery 文档、developer.android.com、GitHub 上的源码或条款镜像 |
| **官方摘要** | 页面是官方的，但只通过搜索引擎摘要看到，没有通读全文 |
| **第三方** | 只有第三方来源（论坛、博客、聚合站） |
| **冲突** | 不同来源说法不一致，正文会写明分歧 |
| **推断** | 本报告根据上述事实推出来的，没有直接出处 |

另外，本轮 WebSearch 用完了 200 次额度，有几项没查完，统一列在 [§12 未决问题](#12-未决问题--上线前需人工核实)。

---

## 0. 结论速览

1. **当前实现已经违反 Gemini Grounding 条款，这是 closed test 的阻断项。** `GeminiService` 带 `google_search` 调用，但完全没解析 `groundingMetadata`：Search Suggestions 没展示，来源也没展示。条款要求 Grounded Results 必须和对应的 Search Suggestions 一起展示 [G4][G12]。要么在上测试前补上展示，要么先关掉 grounding。
2. **免费 key 用不了 Gemini 3 的 Grounding。** 官方定价页写的是 "Not available" [G1]；论坛上实际看到的是 `429 RESOURCE_EXHAUSTED`、配额上限为 0 [G17]。付费层每月 5,000 次免费额度按**搜索查询数**算，而不是按请求数，一个问题可能触发多次查询；超出部分每千次 $14 [G1][G2]。另外，Gemini 2.5 Flash-Lite 免费层虽然有 500 RPD 的 grounding，但 2.5 系列已经**只对用过的老账号开放** [G8]，BYOK 的新用户靠不上它。
3. **存储条款比任务描述里说的更严。** 我读到的是 2025-05-20 版条款原文（镜像）：只能存 Grounded Result 的**文本**，**不含 Links**；作为聊天记录最多保留 **6 个月**；而且没有允许保存 Search Suggestions 的条款 [G4]。任务描述里的「最多 2 年」只在一条搜索摘要里出现过，对应 2026-03-23 版条款，我没能读到原文，标为**冲突**。在有人亲自核对线上条款之前，建议按 6 个月、只存文本来实现。
4. **原来的倾向大体成立，但需要四处修正：**
   - **Gemini 直连**：保留为主力（不走 OpenRouter）。
   - **MusicBrainz**：从「补充的函数调用」升格为 **About 结构化字段的第一来源**。它的 CC0 数据可以随意缓存和同步，还能给免费 key 用户提供「不联网但有出处」的答案；Wikipedia 作为第二个工具。
   - **智谱 GLM-4.7-Flash + web_search**：作为中国区主力成立。模型免费，但搜索按次收费，每次 ¥0.01–0.05；免费模型只允许 1 个并发 [Z1][Z3]。
   - **Qwen**：降级为可选后备，而且只走北京地域和 DashScope 原生端点。OpenAI 兼容端点拿不到来源 [Q1]；新加坡地域的搜索比北京贵约 17 倍 [Q1]。
5. **否决 OpenRouter 和 Perplexity。**
   - OpenRouter：它的 native 引擎只对 Gemini 3+ 才真正走 Google Search，但没有任何文档表明它会透传 `searchEntryPoint`，展示义务因此无法履行 [O1][O2]；Exa 引擎正是已经被否掉的方案。
   - Perplexity：Sonar 的 Chat Completions 接口已于 2026-09-27 退役，改成 Agent API（Responses 格式），没有免费额度，中国大陆也难以付款 [P1][P2][P8]。
   - Kimi：原来的 `$web_search` 在 2026-10-20 下线，新的 `/v1/tools/search` 要客户端自己编排 [K1][K2]。模型本身收费，作为首发候选性价比不够。
6. **Key 安全直接沿用仓库已有的做法，不引入 DataStore 或 Tink。**
   - 现成模板是 `NeoDbTokenStore`：用 AndroidKeyStore AES-GCM 加密，文件放在 `noBackupFilesDir`。
   - `noBackupFilesDir` 在所有 API 级别都不进备份。这一点很重要：minSdk 是 26，而 API 26–30 不认 `dataExtractionRules`，**整个数据库连同明文 key 都会被备份**。
   - `security-crypto` 已经整体废弃 [A2]。
7. **还发现两个暗坑：**
   - `Theme.Yoin` 继承的是深色主题 `Theme.Material`，`isLightTheme` 恒为 false。如果直接放 WebView，Search Suggestions 会**永远按深色渲染**，浅色模式下也一样（见 §7.4）。
   - `generateAlbumMemoryTitle` 会把用户自己写的笔记原文发给 Gemini。免费层条款写的是「数据会被用于训练、可能人工审核、不要提交个人信息」[G4]，两者冲突。

### 推荐组合

| 场景 | Provider | 搜索方式 | 用户需要准备 |
|---|---|---|---|
| 海外 / 能翻墙，key 已开结算 | Gemini `gemini-3.1-flash-lite` 直连 | `google_search`，加 MusicBrainz / Wikipedia 函数工具（同一请求内的 tool combination） | 一个已开结算的 Gemini key |
| 海外，免费 key | 同上 | 不联网，只用 MusicBrainz / Wikipedia 函数工具，答案标「未联网」 | 一个免费 key |
| 中国大陆 | 智谱 `glm-4.7-flash` | 内置 `web_search`（引擎待实测，候选 `search_pro_sogou` / `search_std`），加同样的 KB 函数工具 | 一个智谱 key，账户里有少量余额 |
| 中国大陆后备（可选，P3） | 阿里百炼 北京 `qwen-flash` / `qwen-plus` | DashScope 原生 `enable_search` + `enable_source` | 一个北京地域 key，需实名 |
| 所有不需要联网的功能（翻译、Memory 文案） | 上述任一 provider | 不搜索 | — |

---

## 1. 现状盘点：仓库里已经存在的问题

以下文件路径都相对于 `app/src/main/java/com/gpo/yoin/`。

| # | 问题 | 位置 | 风险 | 处理阶段 |
|---|---|---|---|---|
| 1 | 带 `google_search` 调用，但不解析 `groundingMetadata`，Search Suggestions 和来源都不展示 | `data/remote/GeminiService.kt:35-63, 82-111`；`data/remote/GeminiModels.kt` 只有 `candidates→content→parts{text}` | **违反 Gemini Grounding 条款** [G4][G12] | P0 |
| 2 | API key 放在 URL 查询串里（`?key=$apiKey`，7 处） | `GeminiService.kt:44, 91, 139, 202, 260, 321, 365` | 容易进日志、代理、崩溃报告；官方支持 `x-goog-api-key` 请求头 | P0 |
| 3 | `gemini_config.apiKey` 是 Room 明文列 | `data/local/GeminiConfig.kt:9`，schema `31.json:434-460` | 云备份和设备迁移都会带上它（`data_extraction_rules.xml` 包含 `yoin-database`）；API 26–30 没有 `fullBackupContent`，整库都会备份 | P0 |
| 4 | Settings 把明文 key 放进 `SettingsUiState`，并用 `rememberSaveable` 写进 instance state | `ui/settings/SettingsViewModel.kt:80-104, 132-133`；`ui/settings/SettingsScreen.kt:1204-1283` | 明文 key 进了 Bundle（进程被杀后会恢复） | P0 |
| 5 | 没有 AI 内容的举报 / 反馈入口，也没有「AI 生成」标识 | 全仓库都没有 | Play AI-Generated Content 政策要求「不离开应用即可举报」[PL1] | P0 |
| 6 | 隐私政策草稿和 Data safety 草稿说「凭据不进备份」，对 Gemini key 来说不成立 | `docs/privacy-policy-draft.md:48`；`docs/release-0.5-closed-test.md:93` | 申报内容与实际不符 | P0 |
| 7 | grounded 结果永久保存，并且同步到用户的 Google Drive | `data/local/SongAboutEntry.kt`（没有过期字段）；`data/sync/adapters/SongAboutSyncAdapter.kt` | 条款规定最多 6 个月、不含 Links、不得复制或分发 [G4]，见 §7.1 | P1 |
| 8 | `generateAlbumMemoryTitle` 把用户笔记原文发给模型 | `GeminiService.kt:297-343`；`docs/design.md:322` 的豁免条款 | 免费层的数据会被用于训练、可能人工审核 [G4] | P1（需要披露或单独开关） |
| 9 | `runCatching` / `catch (Exception)` 会吞掉 `CancellationException` | `data/repository/YoinRepository.kt:2887-2918, 2941-2953, 2983-3013, 2671` | 协程取消不生效，正确性问题，顺手修 | P1 |
| 10 | 主题的 `isLightTheme` 恒为 false | `res/values/themes.xml:21`（parent 是 `@android:style/Theme.Material.NoActionBar`） | 以后加的 WebView 会永远是深色（§7.4） | 随 P0 的渲染一起修 |
| 11 | 死代码：`generateAlbumMemoryCopy` / `getOrGenerateAlbumMemoryCopy` 已经没有调用方 | `GeminiService.kt:176-223`；`YoinRepository.kt:2230-2285` | 迁移时直接删掉，不要迁 | P1 |

---

## 2. 实测

### 2.1 本轮能测什么、不能测什么

| 测试项 | 本轮状态 | 原因 |
|---|---|---|
| Gemini 免费 key 和付费 key × `gemini-3.1-flash-lite` / `gemini-2.5-flash-lite` × `google_search` | **没跑** | 环境里没有任何 key，环境变量都是空的 |
| Gemini 的错误结构（非法 key） | **已跑** | `generativelanguage.googleapis.com` 可以访问 |
| Gemini 响应 schema（grounding、tool combination） | **已读原文** | discovery 文档可以访问 [G3] |
| 智谱、百炼、Kimi、OpenRouter、Perplexity 的实际调用 | **没跑** | 代理拒绝这些域名，而且没有 key |
| MusicBrainz、Wikipedia、Discogs 的覆盖率 | **没跑** | 代理拒绝，`Tunnel connection failed: 403` |
| 5 个音乐问题的质量对比 | **没跑** | 同上 |

我没有用模拟数据去填补缺失的结果。下面 §2.5 的表格留空，在本机跑完脚本后再填。

### 2.2 已拿到的结果

**(a) 非法 key 的错误结构**（2026-10-16 实测，三个模型 ID 返回的完全一样）：

```json
{ "error": { "code": 400, "status": "INVALID_ARGUMENT",
  "message": "API key not valid. Please pass a valid API key.",
  "details": [ { "@type": "type.googleapis.com/google.rpc.ErrorInfo", "reason": "API_KEY_INVALID",
                 "domain": "googleapis.com", "metadata": { "service": "generativelanguage.googleapis.com" } },
               { "@type": "type.googleapis.com/google.rpc.LocalizedMessage", "locale": "en-US", "message": "…" } ] } }
```

两个结论：
- key 校验发生在解析模型之前，所以用非法 key 判断不出某个模型 ID 是否存在。
- 完全不带 key 时返回 `403 PERMISSION_DENIED`，消息是 "Method doesn't allow unregistered callers"。

**(b) discovery 文档里的关键 schema**（revision 20261015）[G3]：

| 字段 | 实现时要注意的地方 |
|---|---|
| `GroundingMetadata` | 包含 `webSearchQueries[]`、`searchEntryPoint{renderedContent, sdkBlob}`、`groundingChunks[]`、`groundingSupports[]`、`retrievalMetadata`、`imageSearchQueries[]` |
| `GroundingChunk.web` | 只有 `uri` 和 `title`。在 Google cookbook 的录制输出里，`uri` 是 `vertexaisearch.cloud.google.com/grounding-api-redirect/…` 形式的重定向地址，`title` 只是域名 [G18] |
| `GroundingSupport.segment.startIndex/endIndex` | 文档原话是 "measured in **bytes**"，即 UTF-8 字节偏移。Kotlin 的 `String` 用 UTF-16 下标，**中文和日文答案如果直接 `substring` 会切错**，需要先转换 |
| `ToolConfig.includeServerSideToolInvocations` | 同一个请求里同时用内置工具（`google_search`）和自定义函数时必须开启 [G11]。不开会报 `400 INVALID_ARGUMENT: Please enable tool_config.include_server_side_tool_invocations…`（来自官方摘要） |
| `Part.toolCall / toolResponse / thoughtSignature` | 多轮函数调用时，必须把这些原样回传。`FinishReason` 的枚举里有 `MISSING_THOUGHT_SIGNATURE`，所以抽象层必须保留 provider 的不透明状态（§6.2） |
| `Tool.googleSearchRetrieval` | 仍在 schema 里，但官方说当前模型（包括 2.5）一律用 `google_search` [G2] |
| `Tool.googleSearch.timeRangeFilter` | 可以按时间过滤，回答「最新专辑」这类问题时有用 |

### 2.3 脚本与复现方式

| 脚本 | 用途 | 需要的环境变量 |
|---|---|---|
| `scripts/ai-research/gemini_grounding_probe.py` | 对每个 key × 模型跑四种请求：不带工具、`google_search`、`google_search` + 函数、旧版 `google_search_retrieval`。记录 HTTP 状态码、`google.rpc` 的 reason / QuotaFailure、延迟、token 用量、grounding 结构（查询数、来源域名、偏移量是字节还是字符、`renderedContent` 里有没有 `<script>`、外部资源、深色媒体查询），并把 `renderedContent` 另存为 `.html` 方便肉眼检查 | `GEMINI_FREE_KEY`、`GEMINI_PAID_KEY`，可选 `GEMINI_MODELS`、`GEMINI_CASES` |
| `scripts/ai-research/ai_search_compare.py` | 用 `questions.json` 里的 5 个问题依次问各家 provider，输出 `results.jsonl` 和待人工打分的 `grading.md` | `GEMINI_API_KEY`、`ZHIPU_API_KEY`、`DASHSCOPE_API_KEY`、`MOONSHOT_API_KEY`、`OPENROUTER_API_KEY`、`PERPLEXITY_API_KEY`（有哪个 key 就测哪家） |
| `scripts/ai-research/music_kb_probe.py` | 按 §4.3 的工具链去查 MusicBrainz、Wikipedia、Discogs，统计每家命中了哪些字段、发了几次请求、延迟多少 | 可选 `DISCOGS_TOKEN`、`YOIN_UA_CONTACT` |

在本机运行（Python 3.9+，只用标准库）：

```bash
export GEMINI_FREE_KEY=...      # 未开结算的项目
export GEMINI_PAID_KEY=...      # 已开结算的项目（每次最多消耗 8 次 grounded 请求）
python3 scripts/ai-research/gemini_grounding_probe.py

export GEMINI_API_KEY=$GEMINI_PAID_KEY ZHIPU_API_KEY=... DASHSCOPE_API_KEY=...
python3 scripts/ai-research/ai_search_compare.py
python3 scripts/ai-research/music_kb_probe.py
```

注意：除 Gemini 以外，其他 provider 的请求和响应结构都是按 2026-10-16 的文档摘要写的，**一次真实请求都没发过**。所以脚本里的解析逻辑写得比较宽松，而且所有原始响应都会落盘。如果字段对不上，改提取逻辑即可，不要据此改评分。

如果想在 Claude Code 云端会话里跑：需要在环境的 Network access 里放行这些域名：
- `open.bigmodel.cn`
- `dashscope.aliyuncs.com`
- `api.moonshot.cn`
- `openrouter.ai`
- `api.perplexity.ai`
- `musicbrainz.org`
- `*.wikipedia.org`
- `api.discogs.com`

key 放在环境的 secrets 里。

### 2.4 5 个音乐问题与评分口径

题目定义在 `scripts/ai-research/questions.json`，覆盖华语主流、华语独立、日语主流、日语独立和英语小众：

| id | 问题（缩写） | 判分要点 | 参考答案的核实情况 |
|---|---|---|---|
| q1-mandopop | 周杰伦《晴天》的作词、作曲、专辑、年份 | 词曲均为周杰伦；收录于《叶惠美》，2003 年 | 广为人知，未逐条核实一手来源 |
| q2-mandarin-indie | 草东没有派对《大风吹》的专辑、奖项、歌词含义 | 《醜奴兒》，2016 年；第 28 届金曲奖，《大风吹》获年度歌曲，乐团另获最佳乐团和最佳新人 | 奖项有媒体报道佐证；**词曲署名尚未核实** |
| q3-anisong | 高橋洋子「残酷な天使のテーゼ」的作詞、作曲、編曲、年份 | 及川眠子 / 佐藤英敏 / 大森俊之；1995 年 | 广为人知，未逐条核实 |
| q4-japanese-indie | ゆらゆら帝国『空洞です』的制作人、工程师、发售日、厂牌 | 2007-10-10，ソニー・ミュージックアソシエイテッドレコーズ AICL-1877；制作人 石原洋 | Billboard Japan 佐证了发售信息；**工程师（中村宗一郎）尚未核实** |
| q5-english-niche | Have a Nice Life《Deathconsciousness》的成员、厂牌、录制方式 | Dan Barrett 和 Tim Macuga；2008 年，Enemies List Home Recordings；家中低成本录制 | Wikipedia 等佐证 |

每个回答按以下几项打分：
- **事实**：每个要点记一分，满分为要点数。
- **幻觉**：编造或错误的署名记 −2。
- **来源质量**：有可点击的一手或百科来源记 1，只有内容农场记 0。
- **语言**：回答语言与提问语言一致记 1。
- **延迟**：秒数。
- **成本**：根据 usage 和搜索次数换算。

q2 和 q4 专门用来测试「找不到时会不会坦白说找不到」。

### 2.5 结果表（待填）

| provider / 变体 | q1 | q2 | q3 | q4 | q5 | 平均延迟 | 每题成本 | 备注 |
|---|---|---|---|---|---|---|---|---|
| Gemini 3.1 FL + google_search（付费 key） | | | | | | | | |
| Gemini 3.1 FL + google_search（免费 key） | 预期：429 或 grounding 不生效 | | | | | | | 用于验证 [G1][G17] |
| Gemini 2.5 FL + google_search（免费 key） | 预期：免费 500 RPD；新账号可能 404/403 | | | | | | | 用于验证 [G8] |
| GLM-4.7-Flash + search_std | | | | | | | | |
| GLM-4.7-Flash + search_pro | | | | | | | | |
| GLM-4.7-Flash + search_pro_sogou | | | | | | | | 重点看知乎、腾讯内容 |
| GLM-4.7-Flash + search_pro_quark | | | | | | | | |
| qwen-plus（北京）+ turbo | | | | | | | | |
| Kimi k2.6 + /v1/tools/search | | | | | | | | |
| OpenRouter Gemini + native | | | | | | | | 查看响应里有没有 `searchEntryPoint` |
| Perplexity Agent API sonar | | | | | | | | |
| MusicBrainz（不经 LLM） | | | | | | | | 只看字段命中情况 |
| Wikipedia（不经 LLM） | | | | | | | | |

**Gemini 探针需要额外记录的项：**
- 免费 key 调 Gemini 3 时，返回的错误码和 `QuotaFailure.violations[].quotaId` 具体是什么，用来区分「搜索配额」和「普通 RPM」。
- 一次问答平均产生几个 `webSearchQueries`。这直接决定成本，见 §3.3。
- `segment` 偏移量的单位：脚本会同时用字节和字符两种方式切片，看哪种能对上。
- `renderedContent` 有没有 `<script>`，有没有外部资源。这决定 WebView 能不能关掉 JS、能不能阻断网络加载，见 §7.3。
- `google_search` + 函数工具 + `include_server_side_tool_invocations` 在 3.1 FL 上能不能用，2.5 FL 会怎样报错。

---

## 3. 第三方方案对比

### 3.1 总表

价格均为官方标价，CNY 与 USD 不换算。

| 方案 | 接口形态 | 一个 key 同时搞定模型和搜索 | 免费额度 | 单价（模型 / 搜索） | 大陆直连 | 来源与引用格式 | 强制展示要求 | 结论 |
|---|---|---|---|---|---|---|---|---|
| **Gemini 3.1 Flash-Lite**（直连） | 原生 REST `generateContent`（已标为 Legacy，但仍完全支持）[G14]；另有 Interactions API | ✓，但**必须开通结算** | 免费层：token 免费，**Grounding 不可用**；付费层：每月 5,000 次**搜索查询**免费，Gemini 3+ 各模型共享 [G1] | $0.25 / $1.50 每百万 token；搜索每千次查询 $14 [G1][G2] | ✗，中国大陆和香港不在可用地区 [G10] | `groundingChunks{web.uri（重定向）, title（域名）}` + `groundingSupports`（UTF-8 字节偏移）+ `searchEntryPoint.renderedContent` [G3] | **有**：原样展示 Search Suggestions；不得修改或插入内容；不得有中间页；不得追踪点击；存储受限 [G4][G12][G13] | **主力（海外）** |
| Gemini 2.5 Flash-Lite | 同上 | ✓ | 免费层 grounding 500 RPD，与 2.5 Flash 共享；付费层 1,500 RPD 免费 [G1]（**冲突**：有论坛说免费层也是 1,500 [G21]） | $0.10 / $0.40；每千次 grounded 请求 $35 [G1] | ✗ | 同上 | 同上 | **不作为依赖**：2.5 系列只对老用户开放 [G8]；Vertex 上的退役日期与 Gemini API 不一致 [G20] |
| **智谱 GLM-4.7-Flash** + web_search | **兼容 OpenAI** 的 `chat/completions`，内置 `web_search` 工具；另有独立的 `/web_search` API [Z4][Z5][Z11] | ✓ | 模型免费，**并发 1** [Z2][Z3]；**搜索没有免费额度** [Z1] | 模型 ¥0；搜索每次 `search_std` ¥0.01、`search_pro` ¥0.03、`search_pro_sogou` ¥0.05、`search_pro_quark` ¥0.05 [Z1]；Z.ai 国际站 $0.01 每次 [Z6] | ✓ | 响应顶层的 `web_search[]{title, content, link, media, icon, refer, publish_date}` [Z7]；正文里是否带 `[ref_n]` 角标**未确认** | 没有找到 | **主力（中国）** |
| 阿里百炼 Qwen | 兼容 OpenAI 的 `enable_search`，但**兼容模式不返回来源**；要来源得用 DashScope 原生接口（`search_info`）或 Responses API（`url_citation`）[Q1][Q10] | ✓ | 新用户每个模型 100 万 token，有效期 90 天；适用哪个地域，中国站和国际站说法**冲突** [Q8][Q9]；内置搜索没有免费额度 [Q1] | 北京 `qwen-flash` ¥0.15 / ¥1.5，`qwen-plus` ¥0.8 / ¥2；搜索每千次 turbo ¥3、max ¥4、agent ¥4。新加坡 `qwen-plus` $0.40 / $1.20；agent 搜索每千次 **$10**（北京为 $0.57）[Q1][Q2][Q3][Q5] | ✓（北京地域，需实名）[Q7] | `search_info.search_results[]{index, title, url, site_name, icon}`，正文用 `[1]` 或 `[ref_1]` 角标 [Q2] | 没有找到 | **可选后备（P3）**，只用北京地域加原生端点 |
| Kimi（Moonshot） | 兼容 OpenAI；`$web_search` **将于 2026-10-20 下线**，替代方案是客户端用函数工具调用 `/v1/tools/search` 和 `/v1/tools/fetch` [K1][K3] | ✓（同一个 key） | 没有常设免费额度；国际站累计充值 $5 送 $5 [K5] | `kimi-k2.6` ¥6.5 / ¥27（官方摘要）[K8]；搜索每次 ¥0.01，search_pro ¥0.015，fetch ¥0.01 [K2] | ✓（.cn 站） | `search_results[]{title, url, snippet, site_name, date, …}`，由客户端自己拼进 tool 消息 [K4] | 没有找到 | **暂不采用**：模型收费较贵；它的「可控搜索」架构值得参考 |
| OpenRouter | 兼容 OpenAI，`openrouter:web_search` 服务端工具；`plugins:[{id:"web"}]` 和 `:online` 已废弃 [O1][O3] | ✓ | `:free` 模型每天 50 次，累计充值满 $10 后每天 1,000 次；**免费模型用搜索也收费** [O3][O4] | 充值时收 5.5% 平台费 [O4]；native 搜索按原价透传（Gemini 3+ 为每千次 $14）；Exa 每次 $0.007 [O2]（**冲突**：其他页面写 $0.004–0.005） | 不稳定（第三方报告）；条款禁止用 VPN 绕过地区限制 [O6] | 统一成 `annotations[{type: url_citation, url, title, content, start_index, end_index}]` [O3]；**没有文档表明会透传 `searchEntryPoint`** | 平台本身没有要求，但走 Google native 搜索时**无法履行 Google 的展示义务**（推断） | **否决** |
| Perplexity | Sonar Chat Completions **已于 2026-09-27 退役**，改为 Agent API `POST /v1/agent`（OpenAI Responses 格式）；`/v1/chat/completions` 返回 404 [P1][P4] | ✓ | **没有** [P7] | `perplexity/sonar` $0.25 / $2.50 [P3]；`web_search` 每千次 $2.50（standard）或 $1（fast）；fast preset 的 token 按 2 倍计费 [P2]；独立 Search API 每千次 $5 [P9] | 未确认；只能用 Stripe 刷卡，没有看到支付宝 [P8] | `output` 里的 `search_results` 项，正文用 `[1]`（fast）或 `[web:1]` 角标 [P4] | 没有找到 | **否决**（接口刚换代、没有免费额度、大陆难以付款） |

**「能不能一个 key 搞定」的补充说明：** 表里每家都能做到「一个 key 同时调模型和搜索」，区别在于门槛：
- Gemini 要求开通结算，免费 key 只能调模型。
- 智谱要求账户有余额（错误码 1113 表示欠费）[Z8]。
- 百炼北京地域要求实名 [Q7]。

### 3.2 逐项核实

#### 3.2.1 Gemini

**模型**
- `gemini-3.1-flash-lite` 已正式发布（GA）[G5]。preview 版 ID 已于 2026-05-25 关停（第三方）。
- 弃用页面写着 3.1 Flash-Lite 将于 **2027-05-07** 关停，替代者是 `gemini-3.5-flash-lite`（官方摘要，**低置信**）[G7]。
- `gemini-3.5-flash-lite` 于 2026-07-21 GA，价格 $0.30 / $2.50 [G6][G1]。
- **建议：** 模型 ID 集中到一处，在 2027 年 Q1 之前评估是否升级到 3.5；遇到 404 时映射成 `AiError.ModelRetired`（§6.5）。

**定价**（官方摘要 [G1][G2]）
- Gemini 3+ 免费层的 Grounding：Not available。
- 付费层：每月 5,000 次免费搜索，所有 Gemini 3+ 模型共享，超出后每千次查询 $14。
- 计费单位是「**search query**」：一次请求可能触发多次查询，每次查询分别计费。论坛帖 167664 里，有用户不到 500 次请求就用完了 5,000 次免费额度 [G16]，没有官方回复。
- 这 5,000 次额度按项目算还是按结算账户算，**没有官方答复** [G21]。

**免费 key 调 Gemini 3 + `google_search` 的行为**（论坛和版主回复，第三方 [G17]）
- 返回 `429 RESOURCE_EXHAUSTED`，AI Studio 里显示 grounding 配额是「Limit 0」。
- 要用 `gemini_grounding_probe.py` 确认具体的 `quotaId`。

**2.5 系列**
- 发布说明里写着，2.5 现在**只对用过它的账号开放** [G8]（官方摘要）。
- 这意味着 BYOK 的新用户很可能根本调不了 2.5。所以「免费层就退回 2.5 Flash-Lite 拿免费 grounding」这条路不可靠。

**API 形态**
- Interactions API 已在 2026-06 GA，是官方推荐的新接口；`generateContent` 标为 "Legacy"，但仍然完全支持 [G14]。
- Interactions API 目前还**不能**在 3.5 Flash-Lite 上设置 `include_server_side_tool_invocations` [G19]。
- **建议：** 继续用 `generateContent`，靠 `AiProvider` 抽象隔离接口差异，以后再迁移。

**地区**
- 中国大陆和香港不在可用地区列表里 [G10]。
- 不支持的地区返回 `400 FAILED_PRECONDITION "User location is not supported…"`（第三方）。

**条款**（2025-05-20 版原文，读的是镜像 [G4]；线上版生效日期为 2026-03-23，**没能读到**）
- 年龄：用户必须年满 18 岁；API Client 不得面向或可能被未成年人访问。Yoin 在 Play 上设为仅限成年人，这一条满足。
- EEA、瑞士、英国：「向这些地区的用户提供 API Client 时只能使用 Paid Services」。
- 免费层（Unpaid Services）：数据会被用于改进 Google 的产品；人工审核员可能阅读输入和输出；条款原话是「Do not submit sensitive, confidential, or personal information」。
- 开通结算后，即使用的是免费额度，数据处理也按付费服务的规则执行，不用于训练。
- Grounding 的完整限制，以及它对设计的影响，见 §7.1。
- 使用 Grounding 时，Google 会把 prompt 和输出保存 30 天 [G4]；Grounding 不支持零数据留存（ZDR）[G15]。

#### 3.2.2 智谱 GLM

**模型**
- GLM-4.7-Flash 于 2026-01 发布，取代 GLM-4.5-Flash 成为免费模型 [Z2]。
- 规格：200K 上下文，支持函数调用和流式输出 [Z2]。最大输出长度说法不一，有 16K、128K、131K 三种（**冲突**）。
- 免费模型**限 1 个并发**；账户权益等级越高，并发越多 [Z3][Z12]。
- 定价页上「限时免费」的写法前后不一致，所以「永久免费」只能算第三方说法。

**搜索**
- 内置工具写法：`tools:[{type:"web_search", web_search:{enable, search_engine, search_result, count, search_domain_filter, search_recency_filter, content_size, search_prompt}}]` [Z4]。
- BigModel 现在只有 `search_std`、`search_pro`、`search_pro_sogou`、`search_pro_quark` 四种引擎。`search_pro_jina` 和 `search_pro_bing` 只在第三方页面出现过 [Z5]。
- `search_pro_sogou` 的 `count` 只接受 10、20、30、40、50 [Z5]。

**内容覆盖**（官方摘要 [Z4][Z1]）
- `search_pro_sogou`：覆盖腾讯生态（新闻、企鹅号）和**知乎**，百科、医疗类内容权威；支持搜狗百科、搜狗问问。
- `search_pro_quark`：垂直内容精准、时效性强。
- `search_pro`：多引擎组合，减少空结果。
- **会不会返回微信公众号、百度百科、网易云、QQ 音乐的内容，官方没有说**，需要用 `search_domain_filter` 实测。

**费用**
- 搜索按实际调用次数计费，即使配免费模型也要付费（根据计费方式推断，有第三方明确这样说）。
- 有资源包可买，例如 2,000 次 ¥70 [Z1]。

**来源格式**：`web_search[]` 里的 `refer` 形如 `ref_1`（第三方文档）。正文会不会带这种角标，要实测。

**账号**
- 海外手机号可以注册 BigModel。
- 调用 API 目前**不强制**实名，只是建议 [Z9]；隐私政策里写的是「可能需要」[Z10]，两处**轻微冲突**。

**内容安全与错误码**
- 违规内容返回 `1301`（HTTP 400）；欠费返回 `1113`（HTTP 429）；限流返回 `1302`（HTTP 429）[Z8]。

**数据**
- 隐私政策保留了用匿名化数据训练模型的权利，没有找到「API 数据不用于训练」的承诺 [Z10]。这需要在 Yoin 的隐私政策里披露。

#### 3.2.3 阿里百炼 Qwen

**搜索参数**（官方摘要 [Q1][Q2]）
- `enable_search` 加 `search_options`：
  - `search_strategy`：可选 `turbo`、`max`、`agent`、`agent_max`。
  - `forced_search`：强制搜索。
  - `freshness`：7、30、180 或 365 天。
  - `assigned_site_list`：限定站点。
  - `enable_search_extension`：垂直领域扩展。
- `enable_source`、`enable_citation`、`citation_format` 这三个参数**只有 DashScope 原生接口支持**，OpenAI 兼容端点拿不到来源。
- 有一处摘要说兼容模式只支持 `agent` 策略，这是适用于全部模型还是某个示例模型，**不清楚（冲突）**。

**地域与价格**
- 北京、新加坡、弗吉尼亚是三套独立的端点，key 不能跨地域使用 [Q6]。
- agent 搜索每千次：北京和弗吉尼亚 $0.57（约 ¥4），新加坡 **$10**，相差约 17 倍 [Q1]。
- 北京的 turbo 和 max 自 2026-02-27 起每千次分别收 ¥3 和 ¥4。这只出现在中文页面上；新加坡对应价格没查到 [Q2]。
- 搜索结果会作为输入 token 另外计费；内置搜索没有免费调用额度 [Q1]。

**模型支持搜索的情况**
- `qwen-flash` 的模型页写着新加坡地域**不支持**搜索，与总规则矛盾（**冲突**）[Q4]。

**账号**
- 北京地域需要实名，并且要有购买中国内地资源的资格；中国站和国际站是两套独立的账号体系 [Q7][Q11]。
- 国际账号能不能开通北京地域的百炼，文档没有明说。

**内容审查**：违规内容返回 `data_inspection_failed`（HTTP 400）；搜索结果注入到上下文后也可能触发（推断）[Q7]。

#### 3.2.4 Kimi

**旧接口下线**
- `$web_search` 是 builtin function，计划 **2026-10-20 下线** [K1][K2]。
- 它的价格是每次 ¥0.03，搜索结果的 token 另算。

**替代接口**（官方摘要 [K3][K4][K5]）
- `POST /v1/tools/search`：必填 `text_query`，可选 `limit`、`timeout_seconds`、`include_content`；每次 ¥0.01。
- `POST /v1/tools/search_pro`：增加 `sites`（最多 5 个）、`time_window`，返回结果带 `chunks`；每次 ¥0.015。
- `POST /v1/tools/fetch`：每次 ¥0.01。**请求字段名未核实**。
- 计费规则：只有 HTTP 200 且结果非空时才收费。
- 限流：每个端点单独限 QPS，Tier 1 为 3 QPS。
- 搜索由客户端决定什么时候发起，模型本身不会触发。和对话结合的方式是：自己定义一个函数工具，在客户端调用搜索接口，再把结果作为 `role:"tool"` 消息回传（推断，依据官方的工具调用指南）。

**模型**
- `moonshot-v1` 和 `kimi-k2` 系列都已退役 [K6]。
- 当前模型是 `kimi-k3` 和 `kimi-k2.6`。
- `kimi-k3` 价格：$3 / $15 [K7]。

**端点**：中国境内用 `api.moonshot.cn`，境外用 `api.moonshot.ai`；两边的账号和 key 互不通用 [K9]。

#### 3.2.5 OpenRouter

**native 引擎**（官方摘要 [O1][O2]）
- 只有 **Gemini 3 及以上**才走 Google Search Grounding；Gemini 2.x 不走。
- 在 `auto` 模式下，模型没有原生搜索时会退回 Exa。
- Google 的原生搜索不支持域名过滤：`engine: native` 加过滤会报 400，`auto` 模式下则退回 Exa。

**计费**
- native 搜索按 provider 原价透传；会不会继承 Google 每月 5,000 次免费额度，**无法确认**。
- 充值时收 5.5% 平台费，不按请求收 [O4]。

**来源格式与展示义务**
- 所有引擎的结果都统一成 `url_citation` 注解 [O3]。
- **没有文档表明它会透传 `searchEntryPoint`。** 如果 Yoin 通过 OpenRouter 调用 Gemini 原生搜索，就无法按 Google 条款原样展示 Search Suggestions。这也是否决 OpenRouter 的主要理由（推断）。
- 如果强制用 Exa 引擎，又回到了已经因质量差被否决的方案。

**条款**（[O6]）
- provider 可以限制某些国家的用户，**禁止用 VPN 或代理绕过**。
- 这对中国大陆用户来说本身就是合规问题。

#### 3.2.6 Perplexity

**接口退役**
- Sonar Chat Completions 已于 2026-09-27 退役，同步请求会被自动转换成 Agent API 请求 [P1]。
- 有第三方报告说旧端点返回 `403 agent_api_migration_required`。

**新接口**
- Agent API 用 OpenAI Responses 格式，端点是 `/v1/agent`，`/v1/responses` 是别名 [P4]。
- 支持客户端函数调用 [P5]。

**数据**：官方 FAQ 说 API 查询数据不留存、不用于训练 [P6]；有第三方说零留存是「按申请」提供的（**冲突**）。

**条款**：没有类似 Google 的展示义务。

### 3.3 成本估算（推断）

估算假设如下，请用探针实测的数值替换：
- 一次 Ask：prompt 约 400 token，搜索注入的上下文约 2,000–4,000 token，输出约 250 token。
- Gemini 平均每题触发 2 次搜索查询。
- 汇率按 ¥7.1 = $1 估算。

| 方案 | 每题成本 | 每千题成本 | 说明 |
|---|---|---|---|
| Gemini 3.1 FL + grounding，免费额度内 | token 约 $0.001 | ≈ $1 | 每月 5,000 次查询 ≈ 2,500 题，额度按项目计，**About 和 Ask 共用** |
| Gemini 3.1 FL + grounding，超出额度后 | $0.001 + 2 × $0.014 ≈ $0.029 | **≈ $29** | 搜索费用占九成以上 |
| GLM-4.7-Flash + search_std / pro / sogou | ¥0.01 / ¥0.03 / ¥0.05 | ¥10 / ¥30 / ¥50（约 $1.4 / $4.2 / $7） | token 免费 |
| Qwen 北京 qwen-flash + turbo | ≈ ¥0.004 | ≈ ¥4（约 $0.6） | 最便宜；需要实名 |
| Qwen 新加坡 qwen-plus + agent | ≈ $0.012 | ≈ $12 | |
| Kimi k2.6 + tools/search（两轮） | ≈ ¥0.045 | ≈ ¥45（约 $6.3） | 用上缓存命中可以更低 |
| Perplexity sonar + web_search fast | ≈ $0.003 | ≈ $3 | 没有免费额度 |
| OpenRouter + Gemini native | 与 Gemini 相同，另加 5.5% | ≈ $30 | 免费 5,000 次能否透传未知 |

**About 的消耗：**
- 在 Now Playing 里打开一首新歌的 About，会发出一次 grounded 请求，一次要查 6 个字段，可能触发 3–5 次搜索查询。
- 一个重度用户每天看 30 首新歌，一个月约 900 次请求、约 3,600 次查询，仍在 5,000 次免费额度内。
- 改成 MusicBrainz 优先（§4.2）之后，搜索只用来补缺失字段和生成乐评，查询量还能再降。
- 在 BYOK 模式下，费用由用户自己承担，开发者没有成本风险。真正的风险是「用户意外收到账单」，见 §9。

---

## 4. 结构化数据方案（与模型无关）

### 4.1 三个数据源的约束

| 数据源 | 限流 | 鉴权 | User-Agent | 许可 / 署名 | 本轮核实情况 |
|---|---|---|---|---|---|
| MusicBrainz WS/2 | 平均每 IP 约 1 次/秒，超出返回 503；每个 UA 约 50 次/秒，全局约 300 次/秒 [M1] | 不需要 | **必须有意义**，格式 `App/版本 ( 联系方式 URL )`，通用库的 UA 会被限速 [M1] | 核心数据 CC0（未核实 [M4]）；补充数据 CC BY-NC-SA | 限流是官方摘要；`inc=` 的合法取值是读源码确认的 [M3] |
| Wikipedia / Wikimedia | 2026 年新规（实验性）：未识别客户端每分钟 10 次，合规 UA 未登录每分钟 200 次，超出返回 429 并带 `Retry-After` [W1] | 不需要 | 必须带联系方式 [W2] | 正文 CC BY-SA 4.0，需要署名并附链接 [W4] | 官方摘要；`api.wikimedia.org` 正在逐步废弃，应改用各语言站点的 `/w/api.php` 和 `/api/rest_v1` [W3] |
| Discogs | 已鉴权每分钟 60 次，未鉴权 25 次，按 IP 计 [D1] | **`/database/search` 必须鉴权**（未核实） | 必须唯一 | API 条款里的署名要求和 6 小时缓存限制**未核实** | 第三方镜像 |

**用户 IP 与 UA 的推断：**
- 请求从用户设备直接发出，每个用户各自占用自己 IP 的配额。
- 但所有安装都用同一个 UA，用户量大了以后可能碰到 MusicBrainz 每个 UA 约 50 次/秒的上限。
- UA 的联系方式应该用开发者可控的 URL，比如仓库地址或支持页。**不要写个人邮箱。**

**华语和日语的覆盖率本轮没查到可靠资料，这是最大的未知项。** 要用 `music_kb_probe.py` 实测，最好再从用户真实曲库里抽一批歌测。

### 4.2 设计原则：「事实层」和「联网散文层」分开

| | 事实层（结构化） | 联网散文层（grounded） |
|---|---|---|
| 内容 | 作词、作曲、编曲、制作人、录音地点和时间、厂牌、发行日期 | 乐评、背景故事、对自由提问的回答 |
| 来源 | MusicBrainz（主）、Wikipedia / Wikidata（辅）、Discogs（可选） | Gemini `google_search` 或 GLM `web_search` |
| 可缓存 | ✓ MusicBrainz 核心数据是 CC0 | ✗ 受 Gemini 条款限制：只能存文本、不含 Links、最多 6 个月（§7.1） |
| 可同步到 Drive | ✓ | ✗ 有争议，建议不同步（§7.7） |
| 对免费 key 的用处 | ✓ 函数调用在免费层可用 | ✗ 免费层没有 Gemini 3 grounding |

About 的 6 个固定字段里，有 5 个（创作时间、地点、作词、作曲、制作人）正好对应 MusicBrainz 的 relationship，适合**先查 MusicBrainz，查不到再用搜索补**。REVIEW 属于散文，交给 grounding。

### 4.3 Tool schema 草案

工具应该设计成**少而粗**：一次工具调用就在设备上跑完一整条 HTTP 请求链，尽量减少 LLM 的往返轮数。下面的 JSON Schema 可以同时用于 Gemini 的 `functionDeclarations[].parametersJsonSchema` 和 OpenAI 兼容接口的 `tools[].function.parameters`。

```json
[
  {
    "name": "lookup_track_credits",
    "description": "Look up structured credits for one recording in MusicBrainz: lyricist, composer, arranger, producer, engineer, recording place and date, releases. Use before web search for any credit question.",
    "parameters": {
      "type": "object",
      "properties": {
        "title":  { "type": "string", "description": "Track title as shown to the user" },
        "artist": { "type": "string", "description": "Primary artist name, any script" },
        "album":  { "type": "string", "description": "Album title, optional, used to disambiguate" },
        "isrc":   { "type": "string", "description": "ISRC if known; exact match beats text search" }
      },
      "required": ["title", "artist"]
    }
  },
  {
    "name": "lookup_album_credits",
    "description": "Look up an album (release group) in MusicBrainz: release date, label and catalog number, album-level producer/engineer/recording credits, track list.",
    "parameters": {
      "type": "object",
      "properties": {
        "album":  { "type": "string" },
        "artist": { "type": "string" },
        "year":   { "type": "integer", "description": "Optional, prefer the edition from this year" }
      },
      "required": ["album", "artist"]
    }
  },
  {
    "name": "wikipedia_summary",
    "description": "Fetch the lead summary of the best-matching Wikipedia article for a song, album or artist, in the given language edition.",
    "parameters": {
      "type": "object",
      "properties": {
        "query": { "type": "string", "description": "Entity name plus artist, e.g. '空洞です ゆらゆら帝国'" },
        "lang":  { "type": "string", "enum": ["zh", "ja", "en", "ko"], "description": "Wikipedia language edition" }
      },
      "required": ["query", "lang"]
    }
  }
]
```

工具返回的结构统一如下。设计约束：
- 字段缺失就填 `null`，不让模型去猜。
- 带上 `match` 字段，让模型能判断「这条记录是不是用户问的那首歌」。
- 带上 `source_url` 和 `license`，供 UI 生成来源卡片。

```json
{
  "match": { "score": 0, "matched_title": "<MB 标题>", "matched_artist": "<MB 艺人名>", "mbid": "<recording MBID>" },
  "credits": {
    "lyricist": ["<name>"], "composer": ["<name>"], "arranger": [], "producer": [],
    "engineer": [], "recorded_at": [{ "place": "<place>", "date": "<YYYY[-MM[-DD]]>" }]
  },
  "first_release_date": "<YYYY-MM-DD>",
  "releases": [{ "title": "<release>", "date": "<YYYY-MM-DD>", "label": "<label>", "catalog": "<catno>" }],
  "source_url": "https://musicbrainz.org/recording/<mbid>",
  "license": "CC0",
  "fetched_at": "<ISO-8601>"
}
```

（以上只是结构示意，所有值都是占位符，不是查询结果。）

### 4.4 客户端执行与限流

`lookup_track_credits` 的请求链和 `music_kb_probe.py` 里的实现相同：
1. `artist?query=`：用别名把简体、繁体、罗马字名都解析到同一个 artist MBID。
2. `recording?query=recording:"…" AND arid:<mbid>`。
3. `recording/{id}?inc=artist-rels+work-rels+releases+place-rels`。
4. 沿 performance 关系找到 `work/{id}?inc=artist-rels`，从中取作词、作曲。

另一种做法是用一次 release 查询，带 `inc=recordings+recording-level-rels+work-level-rels+artist-rels+work-rels+place-rels`，一次拿到整张专辑的署名（`inc` 取值合法性来自源码 [M3]）。

**限流：**
- MusicBrainz 在进程内用一个令牌桶，每秒 1 个，留 10% 余量。
- 遇到 503 就指数退避，最多重试 2 次；还不行就让工具返回 `{"error":"rate_limited"}`，让模型改走网页搜索。
- 每次 About 最多发 5 次 MusicBrainz 请求，所以延迟在 5 秒以内。**About 已经是加载态 UI，可以接受**；Ask 的问答会感到慢，所以 Ask 优先命中缓存。

### 4.5 缓存策略

新建一张 Room 表 `knowledge_cache`（放在 `data/local/`）：

```
knowledge_cache(
  source TEXT,        -- "musicbrainz" | "wikipedia" | "discogs"，相当于 AGENTS.md 里的 provider 列
  queryKey TEXT,      -- 归一化后的 "track|<title>|<artist>|<album>" 或 "mbid:<id>"
  payloadJson TEXT,   -- 上面的工具返回 JSON
  fetchedAt INTEGER, expiresAt INTEGER,
  PRIMARY KEY (source, queryKey)
)
```

| 内容 | TTL | 理由 |
|---|---|---|
| MusicBrainz 命中 | 30 天 | CC0，署名很少变动 |
| MusicBrainz 未命中（负缓存） | 7 天 | 避免反复打满限流 |
| Wikipedia 摘要 | 7 天 | 页面会被编辑 |
| Discogs | 以 API 条款为准（未核实；第三方说法是 6 小时） | |

两个推断：
- 这张表可以进备份，也可以随 Drive 同步，因为它不受 Google 条款约束。
- 但第一版不必同步，YAGNI。

### 4.6 与网页搜索的配合

| 用途 | 推荐做法 | 原因 |
|---|---|---|
| About 的固定字段 | **预取**：调 LLM 之前，设备先按已知的歌名、歌手、专辑确定性地查 MusicBrainz，把结果作为「已知事实」注入 prompt；LLM 只负责补 `null` 字段（这时才开搜索）和写 REVIEW | 不依赖函数调用，所有 provider 都能用；省掉一轮往返；免费 key 也能拿到大部分字段 |
| Ask 自由提问 | **函数调用**加 provider 自带搜索：Gemini 用 tool combination（`google_search` + `functionDeclarations` + `include_server_side_tool_invocations`）[G11][G3]；GLM 同时声明 `web_search` 和 `function`（能否在同一请求里混用**未核实**，有一条摘要说两者互斥，需要实测）；不支持的 provider 就分两阶段：先跑工具，再带着结果开搜索 | 需要查什么不可预测 |
| 冲突处理 | prompt 里规定：署名类事实以结构化来源为准；来源之间有矛盾就如实说明 | 降低幻觉 |
| 展示 | 结构化来源和网页来源放在同一个「来源」列表里，用不同图标区分；Wikipedia 来源附 CC BY-SA 署名 | 统一用 `AiSource.origin` 区分（§6.3） |

**条款提醒：** 用函数调用把 Grounded Result 重新提交给 Gemini 是允许的，但只能临时保存，最终展示时最多附 5 个 Search Suggestions [G4]。

---

## 5. 推荐组合（以及对原倾向的修正）

原倾向是：Gemini + Grounding 做主力，加 MusicBrainz 函数调用；中国用户走智谱 GLM，Qwen 做后备。**结论是基本保留，有四处修正：**

| 原倾向 | 修正后 | 理由 |
|---|---|---|
| Gemini + Grounding 做主力 | **保留**，但①必须先补上 Search Suggestions 展示和存储合规（P0）；②把「免费 key」当成一等场景：不开搜索，只走 KB 工具，答案标「未联网」，并在设置页说明开通结算后才能联网；③不要指望用 2.5 Flash-Lite 拿免费 grounding | [G1][G8][G4] |
| MusicBrainz 做函数调用 | **升格为事实层的第一来源**：About 走预取，Ask 走函数调用；加上 Wikipedia；Discogs 推迟（搜索要求用户再提供一个 token，等于又一个 BYOK） | CC0 可以缓存和同步；免费层也能用；省搜索费；绕开存储条款 |
| 中国用户走智谱 GLM | **保留**。默认搜索引擎等 §2.5 实测后再定。在实测之前，按文档描述先选 `search_pro_sogou`（知乎和腾讯内容，¥0.05/次）作为中文问题的候选，`search_std`（¥0.01/次）作为省钱选项。注意免费模型只有 1 个并发：About 和 Ask 并行发请求时要排队 | [Z1][Z3][Z4] |
| Qwen 做后备 | **降级为 P3 可选**：只支持北京地域和 DashScope 原生端点（要拿 `search_info`），这意味着第三种 wire 格式；北京需要实名；新加坡贵约 17 倍，而且 `qwen-flash` 在新加坡不支持搜索 | [Q1][Q4][Q7] |
| （新增）否决 OpenRouter、Perplexity；Kimi 暂不采用 | 理由见 §3.1 | |

**为什么不让中国用户也用 Gemini（翻墙）：** 条款写明只能在可用地区使用 [G4][G10]；而且翻墙用户直连 Gemini 的体验不稳定。所以 GLM 是正确的分流。

**一个 key 的原则：** 每个 provider 只需要一个 key。用户只要配置 Gemini 或 GLM 中的一个，所有 AI 功能就都能用；两个都配置时，可以按功能选择 provider（§6.4 的 `AiRouter`）。

---

## 6. AiProvider 抽象设计

### 6.1 分层

参照 `MusicSource` 的组织方式（接口在 `data/source/`，实现在 `data/source/<provider>/`，传输层在 `data/remote/`）：

```
data/ai/AiProvider.kt              接口 + AiCapability + 请求/响应模型 + AiError
data/ai/AiRouter.kt                按「用途 × 能力 × 用户偏好」选 provider，并暴露 activeAi: StateFlow<ActiveAiState>
data/ai/AiOrchestrator.kt          工具循环（最多 3 轮）、预取、两阶段降级
data/ai/AiKeyStore.kt              加密 key 存储（§8）
data/ai/prompts/                   从 GeminiService 搬过来的 prompt 和 tag 解析器（与 provider 无关）
data/ai/gemini/GeminiAiProvider.kt     把通用请求翻译成 Gemini 请求
data/ai/zhipu/ZhipuAiProvider.kt
data/remote/gemini/GeminiApi.kt + GeminiWire.kt     传输层和 DTO（含 groundingMetadata）
data/remote/zhipu/ZhipuApi.kt + ZhipuWire.kt
data/knowledge/MusicKnowledgeTools.kt + data/remote/musicbrainz/ + data/remote/wikipedia/
```

**关键原则：** 各 provider 只负责「一次调用」的格式转换；prompt、工具循环、缓存、持久化都放在与 provider 无关的那一层。这样新增一个 provider 只需要写一个 Provider 类和一个传输类。

### 6.2 接口草案

```kotlin
package com.gpo.yoin.data.ai

/** One text-generation backend the user brings a key for (Gemini, Zhipu GLM, Qwen …). */
interface AiProvider {
    val id: AiProviderId
    val model: String
    val capabilities: Set<AiCapability>

    /** Exactly one model call. Tool loops live in [AiOrchestrator]. */
    suspend fun generate(request: AiRequest): Result<AiTurn>

    fun dispose() = Unit
}

enum class AiProviderId(val wire: String) { GEMINI("gemini"), ZHIPU("zhipu"), QWEN("qwen") }

enum class AiCapability {
    /** Provider-hosted web search in the same call (google_search / web_search / enable_search). */
    WEB_SEARCH,
    /** Client-executed function tools (MusicBrainz, Wikipedia). */
    FUNCTION_CALLING,
    /** WEB_SEARCH and FUNCTION_CALLING in one request (Gemini 3 tool combination). */
    SEARCH_WITH_FUNCTIONS,
    /** Answer spans map back to sources (Gemini groundingSupports, Qwen [n] markers). */
    INLINE_CITATIONS,
}

data class AiRequest(
    val purpose: AiPurpose,
    val system: String?,
    val messages: List<AiMessage>,
    val search: SearchMode = SearchMode.Off,
    val tools: List<AiToolSpec> = emptyList(),
    val temperature: Float? = null,
    val timeout: Duration? = null,
)

enum class AiPurpose { ABOUT, ASK, ASK_TITLE, LYRICS_TRANSLATION, MEMORY_NARRATION, MEMORY_TITLE }
enum class SearchMode { Off, Auto, Required }

sealed interface AiMessage {
    data class User(val text: String) : AiMessage
    /**
     * [opaque] is provider state that must go back verbatim on the next round:
     * Gemini thoughtSignature / toolCall / toolResponse parts, Kimi reasoning_content.
     * Providers write it, only the same provider reads it.
     */
    data class Assistant(
        val text: String,
        val toolCalls: List<AiToolCall>,
        val opaque: JsonElement?,
    ) : AiMessage
    data class ToolResult(val callId: String, val name: String, val content: JsonElement) : AiMessage
}

data class AiToolSpec(val name: String, val description: String, val parameters: JsonObject)
data class AiToolCall(val id: String, val name: String, val arguments: JsonObject)

data class AiTurn(
    val text: String,
    val toolCalls: List<AiToolCall>,
    val assistant: AiMessage.Assistant,
    val grounding: AiGrounding?,
    val usage: AiUsage?,
)

data class AiUsage(val inputTokens: Int?, val outputTokens: Int?, val searchQueries: Int?)
```

`ActiveAiState`（provider id、capabilities、是否已配置 key）用 `StateFlow` 暴露，ViewModel 据此决定是否显示「联网」等入口。这和 AGENTS.md 中 Phase C 对 `Capability` 的要求一样：feature composable 不能直接访问 `AppContainer`。

### 6.3 引用和来源的统一结构

```kotlin
data class AiGrounding(
    val queries: List<String>,
    val sources: List<AiSource>,
    val citations: List<AiCitation>,
    val attribution: ProviderAttribution,
    val retention: GroundingRetention,
)

data class AiSource(
    val index: Int,
    val origin: SourceOrigin,          // WEB, MUSICBRAINZ, WIKIPEDIA, DISCOGS
    val title: String?,                // Gemini: usually the bare domain
    val url: String?,                  // Gemini: vertexaisearch redirect, never rewrite or unwrap
    val siteName: String?,
    val publishedAt: String?,
    val license: String?,              // "CC0", "CC BY-SA 4.0" …
)

/** [start, end) in UTF-16 indices of the final text; each provider converts its own units. */
data class AiCitation(val start: Int, val end: Int, val sourceIndices: List<Int>)

enum class GroundingRetention {
    /** Gemini grounded output: text only, no links, ≤ policy window, never synced (§7.1). */
    GOOGLE_GROUNDED,
    /** Structured or unrestricted sources: cache and sync freely. */
    UNRESTRICTED,
}
```

**各 provider 怎么映射到这个结构：**

| Provider | sources 来自 | citations 来自 | 换算 |
|---|---|---|---|
| Gemini | `groundingChunks[].web` | `groundingSupports[]` | **UTF-8 字节偏移转 UTF-16 下标**：先对 `parts[partIndex].text` 做 `encodeToByteArray()`，用二分或前缀表把字节位置映射回字符位置 |
| 智谱 | `web_search[]`（`refer` = `ref_n`） | 如果正文里有 `[ref_n]` 就解析，否则为空 | 去掉正文里的角标，记下所在位置 |
| Qwen（原生） | `search_info.search_results[]` | 正文里的 `[n]` 角标 | 同上 |
| KB 工具 | 工具返回的 `source_url` 和 `license` | 无 | — |

### 6.4 provider 特有的展示要求

```kotlin
sealed interface ProviderAttribution {
    data object None : ProviderAttribution
    /** Gemini: render renderedHtml verbatim, full answer width, every time the answer is shown. */
    data class GoogleSearchSuggestions(val renderedHtml: String) : ProviderAttribution
    /** Text-licence attribution, e.g. Wikipedia CC BY-SA. */
    data class License(val label: String, val url: String) : ProviderAttribution
}
```

UI 层用一个 `when` 分发，`GoogleSearchSuggestions` 交给 `SearchSuggestionsView`（§7.3）渲染。以后如果出现新的 provider 展示义务，就加一个新的子类，编译器会强制所有展示点都处理它。

`AiRouter` 的默认规则：
- ABOUT 和 ASK 选有 `WEB_SEARCH` 能力、且已配置 key 的 provider。
- 翻译和 Memory 只要有任一 provider 可用即可，不开搜索。
- 用户同时配置了 Gemini 和 GLM 时，在设置里选主用哪个。不按功能分别选，这是 YAGNI。

### 6.5 错误类型

| `AiError` | Gemini | 智谱 | Qwen | UI 怎么降级 |
|---|---|---|---|---|
| `NoKey` | — | — | — | 沿用现有的 `ApiKeyMissing` 引导 |
| `InvalidKey` | 400 `API_KEY_INVALID`（实测）；403 `PERMISSION_DENIED` | 1000 / 1002–1004 | 401 | 设置页标红 |
| `RegionUnsupported` | 400 `FAILED_PRECONDITION` "User location is not supported"（第三方） | — | 401（key 用错了地域） | 提示改用 GLM |
| `SearchUnavailable` | 免费层 grounding 返回 429，`quotaId` 要靠探针确认 | 1113（欠费） | — | **重新发一次不带搜索的请求，只用 KB 工具**，答案标「未联网」 |
| `RateLimited(retryAfter)` | 429 + `RetryInfo` | 1302 / 1305 | 429 | 退避后重试一次 |
| `ContentBlocked` | `finishReason` 为 SAFETY / PROHIBITED_CONTENT / BLOCKLIST，或 `promptFeedback.blockReason` | 1301，`finish_reason: sensitive` | `data_inspection_failed` | 显示「无法回答」，不缓存 |
| `ModelRetired` | 404 `NOT_FOUND` | 模型不存在 | 同左 | 提示更新应用 |
| `Transport` | 网络错误 | | | 沿用现有错误态 |

另外，`CancellationException` 必须重新抛出，不能用 `runCatching` 吞掉（顺带修复 §1 第 9 项）。

### 6.6 把 `GeminiService` 迁过去的改动范围

| 现有方法 | 迁移后的位置 | 调用方（需要改） |
|---|---|---|
| `generateCanonicalAbout` + `buildPrompt` + `parseTaggedResponse` | `data/ai/prompts/AboutPrompt.kt` + `AboutGenerator`（预取 MusicBrainz，用 `SearchMode.Auto`） | `YoinRepository.ensureCanonicalAbout` 2854-2919 |
| `askAboutSong` + `buildAskPrompt` + `parseAskResponse` | `AskPrompt.kt` + `AskAnswerer`（工具 + 搜索） | `YoinRepository.askAboutSong` 2962-3014 |
| `generateAskTitle` + `cleanAskTitle` | `AskPrompt.kt` | `YoinRepository.generateAskTitle` 2925-2954 |
| `translateLyricLines` + `parseLineTranslations` | `LyricsTranslationPrompt.kt`；`lyrics_translation_cache.model` 改为写 `"<provider>:<model>"` | `YoinRepository.translateLyrics` 2598-2674；`data/lyrics/LyricsCachePolicy.kt:50` |
| `generateAlbumMemoryNarration` + 解析器 + `MEMORY_NARRATION_PROMPT_VERSION` | `MemoryPrompts.kt` | `ui/memories/MemoryNarrationSource.kt:62-76`；`MemoriesViewModel.kt:654-659` |
| `generateAlbumMemoryTitle` | `MemoryPrompts.kt`，并加上「发送原文」的开关（§9） | `YoinRepository.getOrGenerateAlbumMemoryTitle` 2295-2346 |
| `generateAlbumMemoryCopy` | **删除**（死代码） | `YoinRepository.getOrGenerateAlbumMemoryCopy` 2230-2285，`AlbumMemoryTitleResolverTest.kt:203` |

**规模估计：**
- `GeminiService.kt` 共 823 行，其中约 60% 是 prompt 和解析器，可以原样搬走；HTTP 部分有 7 份几乎相同的代码，合并成一个 `GeminiApi.generate()`。
- `YoinRepository` 有 6 处读 key：2255（死代码）、2318、2612、2876、2936、2973；再加上 `MemoryNarrationSource`。统一改成走 `AiRouter`。
- 测试方面：`GeminiServiceTest` 迁到 prompts 包；大约 10 个 `YoinRepository*Test` 文件要更新构造参数；`MemoryNarrationSourceTest` 要改 mock 的对象。
- 涉及 UI 的只有 About、Ask、Scrapbook 的来源展示（§7），以及设置页。

---

## 7. Search Suggestions 渲染与持久化

### 7.1 条款约束，以及它们推出的设计约束

**条款原文要点**（2025-05-20 版，读镜像 [G4]；展示规则摘自 [G12][G13]）

| 条款要求 | 对 Yoin 的设计约束 |
|---|---|
| Grounded Results 只能和对应的 Search Suggestions 一起，展示给发起 prompt 的那个用户 | 凡是展示 grounded 文本的地方，**都要同时展示 Suggestions**：Now Playing 的 About 紧凑卡片、全屏 About、Album Scrapbook 卡片、全文弹层。Scrapbook 卡片上的摘录也算「展示」，所以有两个选择：卡片上只显示标题，或者卡片上也放 Suggestions（推断） |
| 不得修改 Grounded Results 或 Search Suggestions，也不得在其中插入其他内容；展示 Suggestions 时颜色、字体、外观都要保持原样 | 只用 WebView 原样渲染，**不注入任何 CSS 或 JS**；外层容器可以加留白，但不能改它内部的样式 |
| 点击后直接进入 Google 搜索结果页，不得有中间页，不得把用户重定向离开目标页 | 点击时直接用 Custom Tabs 打开原始 URL：不做 URL 改写，不弹确认框，不先经过应用内页面 |
| 不得追踪对某个具体 Suggestion 或 Link 的点击 | **不记录任何点击分析事件**，可以记录「打开过 About」这种界面级事件 |
| 不得缓存、复制、分发 Grounded Results 和 Suggestions；可以存 Grounded Result 的**文本（不含 Links）**：用于评估展示效果的最多 30 天，作为用户聊天记录的最多 6 个月 | 见下面的「保留策略」 |
| 不得分析，也不得用来训练 | 不把回答拿去做任何统计 |

**冲突：** 任务描述里说「最多 2 年」；本次调研中有一条搜索摘要说 2026-03-23 版线上条款允许保存两年，但**我没能读到原文**。而 2025 版原文明确写的是「文本、不含 Links、6 个月」，**也没有允许保存 Search Suggestions**。

因此推荐把保留策略做成一个常量，在有人亲自核对线上条款之前，**先按严格档实现**：

```kotlin
enum class GroundingRetentionPolicy(val maxAge: Duration, val storeLinks: Boolean, val storeSuggestions: Boolean) {
    STRICT_2025(maxAge = 180.days, storeLinks = false, storeSuggestions = false),
    // 只有在线上条款原文确认允许后才切换，并在 docs/design.md 里记下条款原文和日期
    LIVE_2026_IF_CONFIRMED(maxAge = 730.days, storeLinks = true, storeSuggestions = true),
}
```

**在严格档下：**
- 当次会话中，回答和 Suggestions 照常一起展示。
- 写入 Room 的只有文本。
- 以后从「聊天记录」里再打开时，显示的是文本，加一句「来自 Google 搜索 · 重新搜索」的入口；点击后重新发起 grounded 请求，拿到新的 Suggestions。
- 条款里「在聊天记录里查看」的例外只说允许保存文本，所以这样做是可行的（推断）。

### 7.2 存储结构

新建一张表，把「一次 grounded 调用」作为单位。原因是 About 的 6 个字段来自同一次调用，共享同一组 Suggestions。

```
ai_groundings(
  id TEXT PRIMARY KEY,               -- UUID
  providerId TEXT NOT NULL,          -- "gemini" | "zhipu" | …
  model TEXT NOT NULL,
  retention TEXT NOT NULL,           -- GOOGLE_GROUNDED | UNRESTRICTED
  attributionKind TEXT NOT NULL,     -- NONE | GOOGLE_SEARCH_SUGGESTIONS | LICENSE
  attributionPayload TEXT,           -- renderedContent 原文，仅在 policy.storeSuggestions 时写入
  queriesJson TEXT,                  -- webSearchQueries；严格档下同样不存（它们会出现在 Suggestions 里）
  sourcesJson TEXT,                  -- List<AiSource>；GOOGLE_GROUNDED 且 !storeLinks 时为空
  createdAt INTEGER NOT NULL,
  expiresAt INTEGER NOT NULL
)
song_about_entries + groundingId TEXT NULL   -- 外键指向 ai_groundings.id，结构化事实行为 NULL
```

- **清理**：应用启动时以及每天一次，删除 `expiresAt < now` 的 grounding 行和关联的 About 行。也可以只把 About 行标记为过期，显示「重新搜索」入口。
- **语言**：切换目标语言时，现有逻辑会清空 `song_about_entries`（`SettingsViewModel.kt:250`），要连带清掉 `ai_groundings`。
- **迁移**：需要一次 Room 迁移，建表并给 `song_about_entries` 加一列；`SongAboutSyncAdapter` 的 projection 要相应改动，并提升 `kindVersion`（§7.7）。

### 7.3 Compose 中的 WebView

仓库里目前**没有** WebView，也没有 `AndroidView` 互操作；外链统一走 Custom Tabs（`androidx.browser` 1.8.0 已经是依赖）。草案如下：

```kotlin
@Composable
fun SearchSuggestionsView(renderedHtml: String, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    var contentHeightPx by remember(renderedHtml, dark) { mutableIntStateOf(0) }
    val alpha by animateFloatAsState(if (contentHeightPx > 0) 1f else 0f, YoinMotion.effectsSpring(), label = "suggestionsAlpha")
    val height by animateDpAsState(/* contentHeightPx → dp，未就绪时用预留高度 */, YoinMotion.spatialSpring(), label = "suggestionsHeight")
    key(dark) { // isLightTheme 只在构造时读取一次，深浅色切换时重建
        AndroidView(
            modifier = modifier.fillMaxWidth().height(height).graphicsLayer { this.alpha = alpha },
            factory = { ctx ->
                WebView(ContextThemeWrapper(ctx, if (dark) R.style.ThemeOverlay_Yoin_WebDark else R.style.ThemeOverlay_Yoin_WebLight)).apply {
                    setBackgroundColor(Color.TRANSPARENT)       // 只影响外层，不碰内容样式
                    isVerticalScrollBarEnabled = false
                    settings.javaScriptEnabled = false           // 先由探针确认 renderedContent 里没有 <script>
                    settings.setSupportMultipleWindows(false)
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            CustomTabsIntent.Builder().build().launchUrl(view.context, request.url) // 不改写、不追踪
                            return true
                        }
                        override fun onPageFinished(view: WebView, url: String?) {
                            view.post { contentHeightPx = (view.contentHeight * view.resources.displayMetrics.density).roundToInt() }
                        }
                    }
                    loadDataWithBaseURL(null, renderedHtml, "text/html", "utf-8", null)
                }
            },
            onRelease = { it.destroy() },
        )
    }
}
```

**宽度：** 和回答文本放在同一个 `Column` 里，用 `fillMaxWidth()` 并沿用同样的水平内边距，这样两者宽度一致。这是任务描述里的要求；本轮读到的官方文档里没有找到「宽度必须一致」的原文，但这样做没有坏处。

**高度：**
- `contentHeight` 的单位是 CSS px，要乘以 density 换算。
- `onPageFinished` 时布局可能还没完成；更稳妥的做法是用 `androidx.webkit` 的 `WebViewCompat.postVisualStateCallback`，或者给 WebView 加 `OnLayoutChangeListener`。
- 在高度确定之前，先按单行 chip 的高度预留空间，然后用 `spatialSpring` 过渡到实际高度、用 `effectsSpring` 淡入，避免硬切。这符合 AGENTS.md 的动画规范。

**JS 和网络：** 如果探针发现 `renderedContent` 里有 `<script>` 或外部资源（比如 Google 的 logo），就要打开 JS，或者不阻断网络加载。**不能为了安全去裁剪它的内容**，因为那就构成「修改」。

### 7.4 深色模式（**必须处理**）

developer.android.com 原文（读原文 [A1]，该页最后更新于 2026-06-17）：
> "WebView always sets `prefers-color-scheme` according to `isLightTheme`. If `isLightTheme` is true or not specified, then `prefers-color-scheme` is light; otherwise, it is dark."

而 Yoin 的 `Theme.Yoin` 继承 `@android:style/Theme.Material.NoActionBar`，这是框架的**深色**基础主题，`isLightTheme` 为 false；`values-night` 只覆盖了颜色。结果是，**直接放一个 WebView，浅色模式下 Search Suggestions 也会按 `prefers-color-scheme: dark` 渲染**。cookbook 的录制输出显示，`renderedContent` 的 CSS 同时包含浅色和深色两套媒体查询 [G18]。

**修复方法：** 不要改全局主题，否则会波及 Force Dark 等行为。只给 WebView 包一层 `ContextThemeWrapper`，套用两个只设置 `android:isLightTheme` 的 overlay：

```xml
<style name="ThemeOverlay.Yoin.WebLight" parent=""><item name="android:isLightTheme">true</item></style>
<style name="ThemeOverlay.Yoin.WebDark"  parent=""><item name="android:isLightTheme">false</item></style>
```

几点补充：
- `android:isLightTheme` 从 API 29 起才是公开属性。在 API 26–28 上，这个属性不起作用，WebView 会按「未指定」处理，也就是一律浅色。这是 Google 自己的浅色样式，可以接受。
- 不要开启算法暗化（`setAlgorithmicDarkeningAllowed`）。targetSdk 36 默认就是关闭的，开了反而会改变外观。
- Yoin 的深浅色跟随系统，没有应用内开关（AGENTS.md），所以用 `isSystemInDarkTheme()` 作为 `key` 就够了。

### 7.5 点击跳转

- `shouldOverrideUrlLoading` 拦截所有导航，交给 Custom Tabs 打开原始的 `https://www.google.com/search?...` 地址：不加参数，不经过中间页，不记录点击。
- 不在这个 WebView 里加载目标页面，否则就违反了「不得 frame（嵌入）」的规定。
- 来源列表里 Gemini 的 `uri` 是 `vertexaisearch` 重定向链接，也要**原样**用 Custom Tabs 打开，不要解包取出真实地址再打开（推断：解包属于「Link 采集」的灰色地带）。

### 7.6 性能

| 问题 | 对策 |
|---|---|
| 首个 WebView 的初始化要加载 Chromium，主线程卡顿约数十到数百毫秒（经验值，未测），内存增加约 20–40 MB（经验值） | 只在回答出现、并且有 Suggestions 时才创建 WebView；可选的预热手段：首次进入 About 时在后台线程调一次 `WebSettings.getDefaultUserAgent(context)`，它会提前加载 WebView provider（经验做法，需要实测效果） |
| 列表里有多个 WebView（Scrapbook 上可能有几十张 Ask 卡片） | 同屏最多 1–2 个 WebView；卡片上不放 grounded 摘录（§7.1），WebView 只出现在全文弹层或全屏 About 里；`onRelease` 时调用 `destroy()` |
| Now Playing 预测性返回时有 scale / translation 变换 | WebView 在 View 层级里绘制，会跟随 `graphicsLayer` 变换；但它不参与共享元素动画。所以要让它待在不参与 shared element 的区域，并在返回手势开始时保持当前帧（不触发重新加载） |
| 横向滚动的 chip 和外层的 pager 或拖拽手势冲突 | 如果 About 在横向 pager 里，需要在 WebView 上用 `requestDisallowInterceptTouchEvent` 处理横向手势，**要在真机上测试** |
| 同一段 HTML 反复加载 | 以 `renderedHtml.hashCode()` 作为 `remember` 的 key，避免重组时重复 `load` |

### 7.7 与云同步、Scrapbook 的关系

- `SongAboutSyncAdapter` 目前会把所有 About 和 Ask 行同步到 Drive。按严格读法，「复制或分发」grounded 文本有风险，而且在另一台设备上展示时也拿不到 Suggestions（推断）。
- **建议：** `retention = GOOGLE_GROUNDED` 的行**不同步**；`groundingId IS NULL` 的结构化事实行，以及非 Google provider 的回答照常同步。需要提升 `kindVersion`。
- 这是一个产品层面的取舍：同步完整性和合规性之间要二选一。请在实施前确认。
- Scrapbook 上 grounded 的 Ask 卡片：只显示问题标题，点进去后在弹层里同时展示回答和 Suggestions。

---

## 8. API key 安全

### 8.1 现状

- `gemini_config(id, apiKey TEXT NOT NULL, targetLanguage)` 是明文存放的。
- **备份覆盖了所有 API 级别：**
  - API 31 及以上，`data_extraction_rules.xml` 的 `<cloud-backup>` 和 `<device-transfer>` 都包含了 `yoin-database`。
  - API 26–30 下 `dataExtractionRules` 被忽略，而 manifest 里没有 `fullBackupContent`，于是除 `noBackupFilesDir` 和 cache 以外的所有内容都会被备份（[A5]，读原文）。
- 云同步**不会**上传 key，有测试保护：`SyncSecretSentinelTest`、`CloudSyncManagerTest.should_neverUploadSecrets_when_publishing`。这一点要保持。

### 8.2 方案选择

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| **沿用 `CredentialsCipher` + 文件存放在 `noBackupFilesDir/ai_keys/<provider>.bin`**（照搬 `NeoDbTokenStore`） | 不新增依赖；Keystore AES-256-GCM 符合官方「直接用 Keystore」的建议 [A3][A6]；`noBackupFilesDir` 在**所有 API 级别**都自动排除，不用改两份备份 XML；和 profile 凭据、NeoDB token 是同一套语义（换机后失效，需要重新输入） | 每个 provider 一个文件，需要自己写读写代码（约 80 行，可以直接照抄） | **推荐** |
| DataStore + `datastore-tink` | 官方提供的组件 | `datastore-tink` 在 1.3.0-alpha07 才加入，截至 2026-10-12 只到 rc01 [A4]；要新增 DataStore 和 Tink 两个依赖；文件放在 `filesDir/datastore/`，**要在两份备份规则里都显式排除**，Tink keyset 的 prefs 也要排除 | 不推荐 |
| `EncryptedSharedPreferences` | — | `security-crypto` 已在 1.1.0 全部废弃，不会再发新版本 [A2][A3] | 否决 |

### 8.3 迁移步骤

```
Room v31 → v32（与 §7.2 的 ai_groundings 放在同一个版本，或分成两个版本）
```

1. **新建 `data/ai/AiKeyStore`。**
   - 接口：`read(id)`、`write(id, key)`、`clear(id)`、`hasKey: StateFlow<Set<AiProviderId>>`。
   - 文件格式：`iv(12) + ciphertext`，先写 `.tmp` 再 rename，与 `NeoDbTokenStore` 一致。
   - 解密失败返回 null，不抛出异常。
   - 读取放在 IO 线程。现有的 `SettingsViewModel.kt:64` 在主线程同步解密 NeoDB token，**这次不要照抄这个毛病**。
2. **`MIGRATION_31_32`**（在 `AppContainer` 里构造，把 `AiKeyStore` 传进去，这样迁移代码就能访问 Keystore）：
   ```kotlin
   class Migration31To32(private val keys: AiKeyStore) : Migration(31, 32) {
       override fun migrate(db: SupportSQLiteDatabase) {
           val legacy = db.query("SELECT apiKey FROM gemini_config WHERE id = 1").use { c ->
               if (c.moveToFirst()) c.getString(0)?.trim().orEmpty() else ""
           }
           if (legacy.isNotEmpty() && keys.read(AiProviderId.GEMINI) == null) {
               runCatching { keys.write(AiProviderId.GEMINI, legacy) }
                   .onFailure { MigrationNotice.markGeminiKeyLost() } // 一次性提示「请重新输入 Gemini key」
           }
           db.execSQL("CREATE TABLE gemini_config_new (id INTEGER NOT NULL PRIMARY KEY, targetLanguage TEXT NOT NULL DEFAULT 'English')")
           db.execSQL("INSERT INTO gemini_config_new (id, targetLanguage) SELECT id, targetLanguage FROM gemini_config")
           db.execSQL("DROP TABLE gemini_config")
           db.execSQL("ALTER TABLE gemini_config_new RENAME TO gemini_config")
       }
   }
   ```
   - Keystore 失败时的做法与 v11→v12 的 NeoDB 迁移一致：丢弃 key，让用户重新输入。区别在于这次会**先尝试搬迁**，而且搬迁是幂等的：如果加密文件已经存在，就不覆盖。
   - 之所以不分两个版本（先在启动时搬迁，下个版本再删列），是因为那样的话，明文会在数据库里多留一个版本。
3. **清除残留的明文。**
   - 迁移完成后的首次启动，在事务之外执行一次 `PRAGMA wal_checkpoint(TRUNCATE)` 和 `VACUUM`，用一个 no-backup 的标记文件保证只执行一次。这样能清掉 WAL 和空闲页里残留的旧明文。
   - Android 平台的 SQLite 是否默认开启了 `SQLITE_SECURE_DELETE` **没有核实**，所以不要依赖它。
4. **备份规则。**
   - 新增 `res/xml/backup_rules_legacy.xml`（`<full-backup-content>`），在 manifest 里通过 `android:fullBackupContent` 引用，内容与 `dataExtractionRules` 的 include 清单一致，让 API 26–30 也只备份数据库和 `yoin_profiles.xml`。
   - 同时更新 `data_extraction_rules.xml` 的头部注释，在凭据清单里加上 `ai_keys/*.bin`。
5. **传输方式**：所有请求改用 `x-goog-api-key` 请求头，智谱和百炼用 `Authorization: Bearer`，URL 里不再带 key。
6. **设置界面。**
   - `SettingsUiState` 只暴露 `hasKey` 和 key 末 4 位（在 IO 线程解密一次后截取）。
   - 输入框初始为空，不再预填明文；去掉 `rememberSaveable(apiKey)`。
   - 新增「移除 key」按钮。
   - 保存时做一次免费的校验请求：`GET /v1beta/models?pageSize=1`，带请求头。用它区分 `InvalidKey`、`RegionUnsupported` 和正常。**不要**用一次 grounded 请求来判断用户是否开通了结算，那会消耗搜索额度；等第一次真实请求返回 429 时再标记「未开结算」。
7. **同步层清理。**
   - `SyncDomainDao.insertGeminiConfigIfAbsent(GeminiConfig(apiKey = ""))` 改为不带 key。
   - `SyncSecretSentinelTest` 改成往 `AiKeyStore` 里放哨兵值，继续断言哨兵不会出现在任何 projection 里。
8. **告知测试者**：在 closed test 的说明里写一句：如果之前填过 Gemini key，旧的系统备份里可能含有它的明文，建议到 AI Studio 轮换 key。

### 8.4 测试

- `YoinDatabaseMigrationTest` 里新增 `should_moveGeminiKeyToKeyStore_when_migrating_31_to_32`（Robolectric 环境，用 `InMemoryCredentialsCipher` 作为假的加密器）。另加一个用例，覆盖 Keystore 抛异常时仍能完成迁移并标记 lost。
- `AiKeyStoreTest`：读写往返、空串删除文件、文件损坏时返回 null、`.tmp` 残留的处理。
- 调整 `SettingsSyncAdapterTest`、`CloudSyncManagerTest` 和 `SyncSecretSentinelTest` 的断言。

---

## 9. 合规与成本风险

| 风险 | 依据 | 严重度 | 应对 |
|---|---|---|---|
| Search Suggestions 未展示 | [G4][G12] | **高（现在就违反）** | P0：补上展示，或者在补上之前关掉 `google_search` |
| grounded 结果的保存期限和 Links 保存问题：2 年还是 6 个月，**冲突** | [G4] 与一条无法核实的摘要 | 高 | 先按 `STRICT_2025` 实现；由开发者亲自读一遍 `ai.google.dev/gemini-api/terms` 的 Grounding 章节，把原文和日期记进 `docs/design.md` |
| grounded 结果同步到 Drive | [G4]「copy、syndicate」（推断） | 中 | `GOOGLE_GROUNDED` 行不同步（§7.7） |
| EEA、瑞士、英国只能用 Paid Services | [G4] | 中 | BYOK 下谁算条款里的「你」并不明确（论坛无官方答复，见 Gemini 调研的未决项）。保守做法：在设置文案里说明「位于 EEA、瑞士、英国时，请使用已开通结算的 key」，不做地区检测（YAGNI） |
| 免费层数据被用于训练、可能人工审核，条款要求「不要提交个人信息」 | [G4] | 中 | 在 AI 设置页加一次性披露；`MEMORY_TITLE` 会发送用户笔记原文，把它做成**单独的开关，默认关闭**，或者在免费层时不发原文。这一条与 design.md 的豁免条款冲突，需要产品决策 |
| 用户必须年满 18 岁 | [G4] | 已满足 | Play 上设为仅限成年人（[PL5]）；隐私政策里注明 |
| 中国大陆不在 Gemini 的可用地区 | [G10][G4] | 中 | 中国用户走 GLM；Gemini 返回 `RegionUnsupported` 时引导用户切换 |
| Play AI-Generated Content 政策：必须能在应用内举报 | [PL1][PL2] | **高（上架要求）** | 每个 AI 回答都要有「举报 / 反馈」入口（溢出菜单 → 原因选择 → 提交）。仓库里已经有一个 Cloudflare Worker（`services/apple-music-token/`），可以照它的部署方式新建 `services/ai-report/`，接收 `{provider, model, purpose, 问题, 回答摘录, 原因}`；举报后在本地隐藏这条回答。注意这本身也是一项数据收集，要在 Data safety 里申报。Yoin 作为音乐播放器里的 AI 问答，不明显属于豁免情形（[PL2]，推断） |
| Data safety 申报 | [PL3] | 高 | 见下表 |
| 隐私政策过时 | `docs/privacy-policy-draft.md:16, 28, 37, 48` | 高 | 补充：Google 搜索 grounding（Google 会把 prompt 和输出保存 30 天 [G4]）；智谱和百炼作为数据接收方；MusicBrainz 和 Wikipedia 会收到歌名等查询；key 存储方式的变化；AI 举报会收集哪些数据 |
| 用户意外收到账单（一个问题触发多次查询，超出 5,000 次后每千次 $14） | [G1][G2][G16] | 中，费用由用户承担 | 在设置里说明计费方式；可选做法：根据 `webSearchQueries` 在本地统计「本月约 N 次搜索」，只做提示，不上报 |
| 模型退役（3.1 FL 可能在 2027-05-07 关停） | [G7]（低置信） | 低到中 | 模型 ID 集中管理，处理 404，2027 年 Q1 前评估 3.5 FL |
| 智谱免费模型只有 1 个并发 | [Z3] | 中 | `ZhipuAiProvider` 内部用一个 `Mutex` 让请求排队；About 和 Ask 同时触发时串行执行 |
| 智谱、百炼的内容审查拦截正常的音乐问题（歌词或乐队名敏感） | [Z8][Q7] | 低到中 | 映射为 `ContentBlocked`，提示「该服务无法回答此问题」，不缓存 |
| 智谱的数据可能被用于训练 | [Z10] | 低 | 在隐私政策中披露 |
| MusicBrainz 的 UA 级限流（用户多了会触顶） | [M1] | 低 | 缓存、负缓存、每次 About 最多 5 次请求；用户量真的上来之后，考虑成为 MetaBrainz 的商业支持者 |
| Wikipedia CC BY-SA 的署名要求 | [W4] | 低 | 来源卡片上显示条目名、链接和许可 |

**Data safety 草稿的增量**（推断，以 Play Console 实际表单为准 [PL3]）：

| 数据 | Play 分类 | 发往 | 是否收集 | 是否共享 | 是否可选 |
|---|---|---|---|---|---|
| 用户输入的问题 | 应用活动 → 其他用户生成的内容 | Google（Gemini）/ 智谱 / 百炼 | 是 | 属于用户主动发起，可主张豁免；保守起见声明为共享 | 是（不用 AI 功能就不会发送） |
| 正在播放的歌名、歌手、专辑 | 应用活动 → 其他操作 | 同上，以及 MusicBrainz、Wikipedia | 是 | 同上 | 是 |
| 歌词片段（翻译时） | 应用活动 → 其他用户生成的内容 | AI provider | 是 | 同上 | 是 |
| 收听统计（Memory 文案用到的事实） | 应用活动 → 其他操作 | AI provider | 是 | 同上 | 是 |
| 用户笔记原文（Memory 标题） | 应用活动 → 其他用户生成的内容 | AI provider | 是 | 同上 | 是（建议做成单独开关） |
| AI 举报内容 | 应用活动 → 其他用户生成的内容 | 开发者的 Worker | 是 | 否 | 是 |
| API key | 不属于 Play 的用户数据类型，只保存在本地 | 发给对应 provider 用于鉴权 | — | — | — |

传输全部使用 HTTPS（[PL4] 要求认证信息安全传输）。

---

## 10. 需要改动的文件清单

除特别注明外，路径都相对于 `app/src/main/java/com/gpo/yoin/`。

**P0：合规与 key 安全**

| 文件 | 改动 |
|---|---|
| `data/remote/GeminiModels.kt` | 增加 `groundingMetadata`、`searchEntryPoint`、`groundingChunks`、`groundingSupports`、`finishReason`、`promptFeedback` 等 DTO |
| `data/remote/GeminiService.kt` | 解析 grounding；改用 `x-goog-api-key` 请求头；删除 `generateAlbumMemoryCopy`。这是 P1 迁移前的过渡改动 |
| `data/local/AiGrounding.kt`（新）+ `AiGroundingDao.kt`（新） | §7.2 |
| `data/local/SongAboutEntry.kt` / `SongAboutEntryDao.kt` | 增加 `groundingId`；增加过期清理的查询 |
| `data/local/GeminiConfig.kt` / `GeminiConfigDao.kt` | 删除 `apiKey` |
| `data/local/YoinDatabase.kt`、`app/schemas/.../32.json` | 数据库版本升到 32，加入新实体 |
| `AppContainer.kt` | `MIGRATION_31_32`；构造 `AiKeyStore`；一次性 VACUUM；`geminiService` 改为从 `AiKeyStore` 取 key |
| `data/ai/AiKeyStore.kt`（新） | §8.3 |
| `data/repository/YoinRepository.kt` | 2318、2612、2876、2936、2973 处改为从 `AiKeyStore` 读 key（2255 随死代码一起删除）；修复吞掉 `CancellationException` 的问题；持久化 grounding |
| `ui/memories/MemoryNarrationSource.kt` | key 改从 `AiKeyStore` 读取 |
| `data/sync/domain/SyncDomainDao.kt`（:124-134）、`data/sync/adapters/SettingsSyncAdapter.kt`（:138-154） | 去掉 `apiKey` |
| `data/sync/adapters/SongAboutSyncAdapter.kt` | 不同步 `GOOGLE_GROUNDED` 行，提升 `kindVersion` |
| `ui/settings/SettingsViewModel.kt`、`SettingsUiState.kt`、`SettingsScreen.kt`（:1204-1283）、`res/values*/strings_settings.xml` | 不暴露明文；末 4 位显示；移除 key；保存时校验；免费层与 EEA 说明 |
| `ui/component/SearchSuggestionsView.kt`（新） | §7.3 |
| `ui/component/AiContentReportSheet.kt`（新） | 举报入口 |
| `ui/component/SongInfoDisplay.kt`、`ui/nowplaying/AboutFullscreenPane.kt`、`ui/nowplaying/AboutUiState.kt` | 展示 Suggestions、来源、举报入口 |
| `ui/detail/AlbumScrapbookPage.kt`（`AskCard` :1490、`AboutCard` :1582、`ScrapAboutSheetContent` :1759）、`ui/detail/AlbumScrapbook.kt` | grounded 卡片不显示摘录；弹层里展示 Suggestions |
| `res/values/themes.xml` | 新增 `ThemeOverlay.Yoin.WebLight` 和 `ThemeOverlay.Yoin.WebDark` |
| `app/src/main/AndroidManifest.xml`、`res/xml/backup_rules_legacy.xml`（新）、`res/xml/data_extraction_rules.xml` | 加 `fullBackupContent`；更新注释 |
| `gradle/libs.versions.toml`、`app/build.gradle.kts` | （可选）引入 `androidx.webkit`，用于 `postVisualStateCallback` |
| `services/ai-report/`（新，照 `services/apple-music-token/` 的结构） | 举报接收端 |
| `docs/privacy-policy-draft.md`、`docs/release-0.5-closed-test.md`、`docs/cloud-sync.md`、`docs/design.md` | 更新申报和设计说明 |
| 测试：`YoinDatabaseMigrationTest`、`SyncSecretSentinelTest`、`SettingsSyncAdapterTest`、`CloudSyncManagerTest`、`YoinRepositoryTest`、`MemoryNarrationSourceTest`、`AiKeyStoreTest`（新）、`GeminiGroundingParserTest`（新：重点测 UTF-8 偏移转换） | |

**P1：AiProvider 抽象（§6）**

| 文件 | 改动 |
|---|---|
| `data/ai/AiProvider.kt`、`AiRouter.kt`、`AiOrchestrator.kt`、`data/ai/prompts/*`（均为新文件） | 接口、路由、工具循环、prompt |
| `data/ai/gemini/GeminiAiProvider.kt`、`data/remote/gemini/GeminiApi.kt`（新） | 替代 `GeminiService` |
| `data/remote/GeminiService.kt`、`GeminiModels.kt` | 删除或迁移 |
| `data/repository/YoinRepository.kt`（构造参数 155-163，以及 §6.6 列出的各个方法） | 改为依赖 `AiRouter` 和各用例类 |
| `ui/memories/MemoriesViewModel.kt:654-659` | 改为注入 narrator |
| `data/lyrics/LyricsCachePolicy.kt:50` | cache 的 model 字段改为 `"<provider>:<model>"` |
| 大约 10 个 `YoinRepository*Test`；`GeminiServiceTest` 迁到 `AiPromptParsersTest` | |

**P2：结构化数据（§4）**

| 文件 | 改动 |
|---|---|
| `data/remote/musicbrainz/MusicBrainzApi.kt`、`data/remote/wikipedia/WikipediaApi.kt`（新） | |
| `data/knowledge/MusicKnowledgeTools.kt`、`data/knowledge/RateLimiter.kt`（新） | |
| `data/local/KnowledgeCache.kt` + DAO（新），并做 Room 迁移 | |

**P3：中国区 provider**

| 文件 | 改动 |
|---|---|
| `data/ai/zhipu/ZhipuAiProvider.kt`、`data/remote/zhipu/ZhipuApi.kt`（新） | |
| （可选）`data/ai/qwen/QwenAiProvider.kt`、`data/remote/qwen/DashScopeApi.kt` | |
| 设置页：provider 选择器，每个 provider 一个 key | |

---

## 11. 分阶段实施计划

| 阶段 | 内容 | 完成标准 | 依赖 |
|---|---|---|---|
| **P−1 实测**（本机，约半天） | 用免费和付费 Gemini key 跑 `gemini_grounding_probe.py`；用 Gemini 和智谱（加上能拿到的其他 key）跑 `ai_search_compare.py`；跑 `music_kb_probe.py`；把结果填进 §2.5；**亲自读一遍线上 Gemini 条款的 Grounding 章节** | §2.5 填完；保留策略选定 STRICT 还是 LIVE；智谱默认引擎选定 | key；能访问外网的机器 |
| **P0 合规与安全**（closed test 前） | 解析 grounding，用 WebView 展示 Suggestions（含深色修复）和来源；grounded 结果按保留策略存储，加过期清理，不同步；key 迁移到 `AiKeyStore`（v31→v32），改用请求头，加 legacy 备份规则，设置页不再暴露明文；AI 举报入口和 Worker；更新隐私政策和 Data safety | Search Suggestions 在 About、Ask、Scrapbook 上都按规则展示；全仓库搜不到 `?key=`；迁移测试通过；`ktlintCheck` 和 `test` 通过 | P−1 的保留策略结论 |
| **P1 抽象** | `AiProvider` / `AiRouter` / `AiOrchestrator`；`GeminiAiProvider` 取代 `GeminiService`；拆出 prompt；`AiError` 映射；免费 key 遇到 429 时降级为不联网；`MEMORY_TITLE` 加开关 | 行为与 P0 一致（回归测试）；`GeminiService.kt` 删除 | P0 |
| **P2 事实层** | MusicBrainz 和 Wikipedia 工具加缓存；About 改为预取，Ask 用 tool combination；来源列表统一 | §2.4 的 q1、q3、q4、q5 在不联网时也能答出主要署名（以实测为准）；About 平均搜索查询数下降 | P1 |
| **P3 中国区** | `ZhipuAiProvider`（`web_search`、并发互斥、错误码映射）；设置页选择 provider；（可选）Qwen 北京原生端点 | 只配置智谱 key 时，所有 AI 功能都能用，并带来源 | P1；P2 可以并行 |
| **P4 收尾** | 根据 [G7] 的弃用时间表评估 `gemini-3.5-flash-lite`；评估迁移到 Interactions API；本地统计搜索用量 | — | — |

---

## 12. 未决问题 / 上线前需人工核实

1. **Gemini 线上条款**（生效日期 2026-03-23）的 Grounding 章节：保存期限是 2 年还是 6 个月？能不能保存 Links 和 Search Suggestions？能不能同步到用户自己的云端？这直接决定 §7 选哪一档。
2. 免费 key 调 Gemini 3 + `google_search` 时的确切错误码和 `quotaId`（探针会记录）。
3. 每月 5,000 次 grounding 免费额度按项目算还是按结算账户算 [G21]；是否存在 1,500 RPD 的隐藏上限（论坛报告）。
4. `gemini-2.5-flash-lite` 对新账号到底能不能用（探针用一个新建项目的 key 测）。
5. `renderedContent` 有没有 `<script>`、外部资源，宽度和高度表现如何；Search Suggestions 的「宽度一致」要求在官方文档里没有找到原文。
6. 智谱：正文里是否带 `[ref_n]`；`web_search` 和 `function` 能否在同一请求里混用；搜狗和夸克实际返回的内容来源（知乎、公众号、百科、网易云）；GLM-4.7-Flash 的最大输出长度。
7. 百炼：兼容模式是否只支持 `agent` 策略；国际账号能否开通北京地域。
8. Kimi `/v1/tools/fetch` 的请求字段名（目前未采用 Kimi，优先级低）。
9. MusicBrainz、Wikipedia、Discogs 对华语和日语的覆盖率（`music_kb_probe.py`）；Discogs API 条款里的署名和缓存要求；MusicBrainz 数据许可页的原文。
10. BYOK 模式下，「EEA、瑞士、英国只能用 Paid Services」这条义务由谁承担（Gemini 论坛多帖无官方答复）。
11. Play AI 政策：Yoin 的使用场景是否落在豁免范围内（我们按「不豁免」处理）。
12. Android 平台 SQLite 是否默认开启 `SQLITE_SECURE_DELETE`（无论结果如何，都按 §8.3 执行 VACUUM）。

---

## 附录 A：资料来源

查阅日期：**全部为 2026-10-16**。标签含义见文首。

**Gemini / Google**

| 编号 | 链接 | 标签 | 用于 |
|---|---|---|---|
| G1 | https://ai.google.dev/gemini-api/docs/pricing | 官方摘要 | token 价格、grounding 免费额度和单价 |
| G2 | https://ai.google.dev/gemini-api/docs/google-search | 官方摘要 | 按查询计费；当前模型一律用 `google_search` |
| G3 | https://generativelanguage.googleapis.com/$discovery/rest?version=v1beta（revision 20261015） | **读原文** | 所有 schema 字段、UTF-8 字节偏移、`includeServerSideToolInvocations`、`FinishReason` |
| G4 | https://ai.google.dev/gemini-api/terms（线上 2026-03-23 版未能读取）；2025-05-20 版原文镜像：https://raw.githubusercontent.com/aboutcode-org/scancode-toolkit/develop/src/licensedcode/data/licenses/gemini-api-additional-tos-2025.LICENSE | **读原文（镜像，2025 版）**；2026 版**冲突** | 年龄、地区、免费层数据使用、Grounding 的使用、存储和展示限制、Google 保存 30 天 |
| G5 | https://ai.google.dev/gemini-api/docs/models/gemini-3.1-flash-lite | 官方摘要 | 3.1 FL GA |
| G6 | https://ai.google.dev/gemini-api/docs/models/gemini-3.5-flash-lite | 官方摘要 | 3.5 FL GA |
| G7 | https://ai.google.dev/gemini-api/docs/deprecations | 官方摘要（低置信） | 3.1 FL 的关停日期 |
| G8 | https://ai.google.dev/gemini-api/docs/changelog | 官方摘要 | 2.5 只对老用户开放 |
| G9 | https://ai.google.dev/gemini-api/docs/rate-limits | 官方摘要 | 不再公开各模型的免费层数字；限额按项目计 |
| G10 | https://ai.google.dev/gemini-api/docs/available-regions | 官方摘要 | 中国大陆和香港不在列表内 |
| G11 | https://ai.google.dev/gemini-api/docs/generate-content/tool-combination | 官方摘要 | 内置工具与函数调用组合 |
| G12 | https://ai.google.dev/gemini-api/docs/grounding/search-suggestions | 官方摘要 | 原样展示 |
| G13 | https://firebase.google.com/docs/ai-logic/grounding-google-search | 官方摘要 | 展示规则（不修改、直达搜索结果页、不得有中间页、始终可见） |
| G14 | https://ai.google.dev/gemini-api/docs/interactions-overview | 官方摘要 | Interactions API GA；`generateContent` 为 Legacy |
| G15 | https://ai.google.dev/gemini-api/docs/zdr | 官方摘要 | Grounding 不支持 ZDR |
| G16 | https://discuss.ai.google.dev/t/gemini-3-x-search-grounding-free-usage-not-included-anymore/167664 | 第三方（论坛） | 按查询计费造成的意外账单 |
| G17 | https://discuss.ai.google.dev/t/google-search-grounding-is-broken/180922 | 第三方，含版主回复 | 免费层返回 429 |
| G18 | https://github.com/google-gemini/cookbook/blob/archive/generate-content-api/quickstarts/Search_Grounding.ipynb | 读原文 | `uri` 为重定向地址、`title` 为域名；`renderedContent` 含浅色和深色 CSS |
| G19 | https://github.com/googleapis/python-genai/issues/2761 | 第三方 | Interactions API 无法设置组合工具的开关 |
| G20 | https://discuss.ai.google.dev/t/gemini-2-5-flash-lite-retirement-date-different-for-gemini-api-vs-vertex-ai/177897 | 第三方，**冲突** | 2.5 FL 的退役日期 |
| G21 | https://discuss.ai.google.dev/t/grounding-with-google-search-5-000-free-prompts-month-is-the-quota-scoped-per-project-or-per-billing-account/134866 ；…/187033 ；https://discuss.ai.google.dev/t/grounding-free-quota-clarifying-2026-rules-for-gemini-2-5-flash-paid-tier-1/144593 | 第三方，**冲突** | 额度的计算范围；RPD 上限；2.5 免费额度 |

**智谱**

| 编号 | 链接 | 标签 |
|---|---|---|
| Z1 | https://docs.bigmodel.cn/cn/guide/start/pricing | 官方摘要（搜索单价；GLM-4.7-Flash 免费） |
| Z2 | https://docs.bigmodel.cn/cn/guide/models/free/glm-4.7-flash | 官方摘要 |
| Z3 | https://www.zhipuai.cn/zh/news/148 | 官方摘要（限 1 个并发） |
| Z4 | https://docs.bigmodel.cn/cn/guide/tools/web-search | 官方摘要（工具参数；搜狗、夸克的覆盖描述） |
| Z5 | https://docs.bigmodel.cn/api-reference/工具-api/网络搜索 | 官方摘要（四种引擎；`count` 的限制） |
| Z6 | https://docs.z.ai/guides/overview/pricing | 官方摘要（国际站 $0.01 每次） |
| Z7 | https://docs.z.ai/api-reference/llm/chat-completion | 官方摘要（`web_search` 响应字段） |
| Z8 | https://docs.bigmodel.cn/cn/faq/api-code | 官方摘要（错误码） |
| Z9 | https://docs.bigmodel.cn/cn/faq/authentication-issues | 官方摘要（实名非强制） |
| Z10 | https://docs.bigmodel.cn/cn/terms/privacy-policy | 官方摘要（可能需要实名；匿名化数据可用于训练） |
| Z11 | https://docs.bigmodel.cn/cn/guide/develop/openai/introduction | 官方摘要（OpenAI 兼容；base URL 的写法未读到原文） |
| Z12 | https://docs.bigmodel.cn/cn/guide/platform/equity-explain | 官方摘要（权益等级与并发） |

**阿里百炼**

| 编号 | 链接 | 标签 |
|---|---|---|
| Q1 | https://www.alibabacloud.com/help/en/model-studio/web-search | 官方摘要（兼容模式不返回来源；各地域 agent 价格） |
| Q2 | https://help.aliyun.com/zh/model-studio/web-search | 官方摘要（`search_options` 各字段；turbo 和 max 价格；来源字段） |
| Q3 | https://help.aliyun.com/zh/model-studio/model-pricing | 官方摘要 |
| Q4 | https://www.alibabacloud.com/help/en/model-studio/qwen-flash | 官方摘要（新加坡不支持搜索，**冲突**） |
| Q5 | https://www.alibabacloud.com/help/en/model-studio/qwen-plus | 官方摘要 |
| Q6 | https://help.aliyun.com/en/model-studio/base-url | 官方摘要（三个地域的端点） |
| Q7 | https://www.alibabacloud.com/help/en/model-studio/error-code | 官方摘要（实名；`data_inspection_failed`） |
| Q8 | https://help.aliyun.com/zh/model-studio/new-free-quota | 官方摘要（**冲突**） |
| Q9 | https://www.alibabacloud.com/help/en/model-studio/new-free-quota | 官方摘要（**冲突**） |
| Q10 | https://www.alibabacloud.com/help/en/model-studio/compatibility-with-openai-responses-api | 官方摘要 |
| Q11 | https://www.alibabacloud.com/help/en/model-studio/regions | 官方摘要 |

**Kimi**

| 编号 | 链接 | 标签 |
|---|---|---|
| K1 | https://platform.kimi.ai/docs/guide/use-web-search | 官方摘要（`$web_search` 于 2026-10-20 下线） |
| K2 | https://platform.kimi.com/docs/pricing/websearch | 官方摘要（各接口单价） |
| K3 | https://platform.kimi.com/docs/api/tools-search | 官方摘要 |
| K4 | https://platform.kimi.com/docs/api/tools-search-pro | 官方摘要 |
| K5 | https://platform.kimi.com/docs/pricing/limits | 官方摘要 |
| K6 | https://platform.kimi.com/docs/models | 官方摘要（已退役的模型） |
| K7 | https://platform.kimi.ai/docs/pricing/chat | 官方摘要 |
| K8 | https://www.kimi.com/resources/kimi-k2-6-pricing | 官方摘要 |
| K9 | https://platform.kimi.com/docs/api/overview | 官方摘要（中国站与国际站端点） |

**OpenRouter**

| 编号 | 链接 | 标签 |
|---|---|---|
| O1 | https://openrouter.ai/docs/guides/features/server-tools/web-search | 官方摘要 |
| O2 | https://openrouter.ai/tool/web-search | 官方摘要（native 只覆盖 Gemini 3+；Exa 单价，**冲突**） |
| O3 | https://openrouter.ai/docs/guides/features/plugins/web-search | 官方摘要（旧接口已废弃；`url_citation`；免费模型也收搜索费） |
| O4 | https://openrouter.ai/pricing | 官方摘要（5.5% 平台费；`:free` 的配额） |
| O5 | https://openrouter.ai/docs/faq | 官方摘要（支付方式） |
| O6 | https://openrouter.ai/terms | 官方摘要（禁止用 VPN 绕过地区限制） |
| O7 | https://openrouter.ai/docs/guides/overview/auth/byok | 官方摘要 |

**Perplexity**

| 编号 | 链接 | 标签 |
|---|---|---|
| P1 | https://docs.perplexity.ai/docs/agent-api/migrate-from-sonar/overview | 官方摘要（2026-09-27 退役） |
| P2 | https://docs.perplexity.ai/docs/getting-started/pricing | 官方摘要 |
| P3 | https://docs.perplexity.ai/docs/agent-api/models | 官方摘要 |
| P4 | https://docs.perplexity.ai/docs/agent-api/migrate-from-sonar/how-to | 官方摘要 |
| P5 | https://docs.perplexity.ai/docs/agent-api/tools/custom-functions | 官方摘要 |
| P6 | https://docs.perplexity.ai/docs/resources/faq | 官方摘要（不留存数据，**冲突**） |
| P7 | https://www.perplexity.ai/help-center/en/articles/11187416 | 官方摘要（没有免费额度） |
| P8 | https://docs.perplexity.ai/docs/getting-started/api-groups | 官方摘要（支付方式） |
| P9 | https://docs.perplexity.ai/docs/search/quickstart | 官方摘要（Search API 价格） |

**音乐数据**

| 编号 | 链接 | 标签 |
|---|---|---|
| M1 | https://musicbrainz.org/doc/MusicBrainz_API/Rate_Limiting | 官方摘要 |
| M2 | https://musicbrainz.org/doc/MusicBrainz_API/Search | 官方摘要 |
| M3 | https://github.com/metabrainz/musicbrainz-server/blob/master/lib/MusicBrainz/Server/Controller/WS/2/Recording.pm（以及同目录下的 Release.pm、Validator.pm） | 读原文（`inc` 的合法取值） |
| M4 | https://musicbrainz.org/doc/About/Data_License | **未核实** |
| D1 | https://www.discogs.com/developers | 第三方镜像 |
| W1 | https://www.mediawiki.org/wiki/Wikimedia_APIs/Rate_limits | 官方摘要 |
| W2 | https://meta.wikimedia.org/wiki/User-Agent_policy | 官方摘要 |
| W3 | https://wikitech.wikimedia.org/wiki/API_Portal/Deprecation | 官方摘要 |
| W4 | https://www.mediawiki.org/wiki/Wikimedia_APIs/Content_reuse | 官方摘要 |

**Google Play**

| 编号 | 链接 | 标签 |
|---|---|---|
| PL1 | https://support.google.com/googleplay/android-developer/answer/13985936 | 官方摘要（应用内举报） |
| PL2 | https://support.google.com/googleplay/android-developer/answer/14094294 | 官方摘要（豁免范围） |
| PL3 | https://support.google.com/googleplay/android-developer/answer/10787469 | 官方摘要（Data safety） |
| PL4 | https://support.google.com/googleplay/android-developer/answer/10144311 | 官方摘要（User Data） |
| PL5 | https://support.google.com/googleplay/android-developer/answer/9867159 | 官方摘要（目标受众） |

**Android**

| 编号 | 链接 | 标签 |
|---|---|---|
| A1 | https://developer.android.com/develop/ui/views/layout/webapps/dark-theme（最后更新 2026-06-17） | **读原文** |
| A2 | https://developer.android.com/jetpack/androidx/releases/security | 读原文（security-crypto 已废弃） |
| A3 | https://developer.android.com/privacy-and-security/cryptography | 读原文 |
| A4 | https://developer.android.com/jetpack/androidx/releases/datastore | 读原文（`datastore-tink` 的版本） |
| A5 | https://developer.android.com/identity/data/autobackup | 读原文（`noBackupFilesDir` 永远不备份；API ≤30 需要 `fullBackupContent`） |
| A6 | https://developer.android.com/privacy-and-security/security-tips | 读原文（用 Keystore 保存 API key） |
