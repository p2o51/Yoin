# Yoin 评分、回忆与 NeoDB 集成设计分析文档

**目标读者**：开发者/设计者
**文档目的**：全面梳理 Yoin 目前在本地评分（Rating）、长评/回忆（Review）以及与 NeoDB 双向同步功能的现状，提供详细的代码结构与交互逻辑说明，并为下一步迭代提供决策支持和潜在风险点分析。

---

## 1. 核心概念与数据模型定义

在 Yoin 中，“评分”与“回忆”的存储和呈现具有多维度的结构：

- **单曲级（Track）**：
  - **LocalRating**：对应单曲在本地或 Subsonic 服务端的星标/评分。包含本地 `rating`、服务端 `serverRating` 以及是否需要同步回服务端的脏标记 `needsSync`。
  - **数据位置**：`app/src/main/java/com/gpo/yoin/data/local/LocalRating.kt`

- **专辑级（Album）—— 也是与 NeoDB 绑定的核心**：
  - **AlbumRating**：包含了对专辑的评分和用户的“回忆（长评/Review）”。
  - **核心字段**：
    - `rating`：`Float` 类型（0.0 - 10.0），支持小数是为了在 Memory 卡片中计算均分时的平滑感，推送至 NeoDB 时会 `roundToInt()` 转换为 0-10 整数。如果本地无评分但取消评分则记录为 `0f`。
    - `review`：用户的长评，支持 Markdown 格式。
    - `neoDbReviewUuid`：记录成功推送到 NeoDB 后得到的评论 UUID（注意：NeoDB 的 Mark 和 Review 是两个独立资源）。
    - `ratingNeedsSync` / `reviewNeedsSync`：脏位标记。独立追踪 Rating 和 Review 是否需要同步到远端，避免误覆盖未修改的项。
  - **数据位置**：`app/src/main/java/com/gpo/yoin/data/local/AlbumRating.kt`

## 2. 与 NeoDB 的对接架构

NeoDB 的交互采取的是一个渐进式的、带有本地草稿保护的双向同步模型。

### 2.1 API 通信层 (`NeoDBApi.kt`)
实现了一个轻量级的、仅针对所需端点裁剪的 NeoDB OpenAPI 0.14 客户端：
- **OAuth 鉴权**：通过 `NeoDBOAuthActivity` 和 `NeoDBOAuthContract` 进行动态应用注册和 OAuth Token 交换。**安全性设计**：配置类（如域名 `NeoDBConfig`）保存在 Room 数据库随系统云备份，但用户的 `accessToken` 存在于加密文件 `neodb/token.bin` 中且排除了云备份。
- **搜素与匹配**：`searchAlbum` 方法通过专辑/作者名向 NeoDB 搜索获取对应的 NeoDB `item_uuid`（这是打通本地 Subsonic ID 和 NeoDB 资源体系的核心映射，结果会缓存在 `external_mappings` 表里）。
- **资源类型**：
  - **Shelf/Mark**：针对 `rating_grade`（评分）。
  - **Review**：针对 `body`（长评/长文回忆）。针对这两种资源分发了不同的 POST/PUT/DELETE 请求。

### 2.2 同步引擎 (`NeoDBSyncService.kt`)
这是集成功能的核心大脑，职责如下：
- **向上推送 (Push)**：
  - 合并远端数据以防覆盖：发送 `ShelfMarkRequest` 前，会先执行 `GET` 获取远端的 tags、comment_text 和 visibility 状态，**然后再 POST 覆盖**，保障了不会覆盖掉 NeoDB 上其他客户端更新的 tag 和短评。
  - **Review 处理机制**：如果本地 Review 被清空，则向 NeoDB 发送 DELETE；否则发送 POST (新建) 或 PUT (更新)。同步成功后，会用返回的 uuid 覆写本地 `neoDbReviewUuid` 并清空 `needsSync` 脏位。
- **向下回拉 (Pull)**：
  - **合并策略（双向冲突处理）**：保护飞行模式草稿！当本地的 `ratingNeedsSync` 或 `reviewNeedsSync` 为 `true` 时，就算远端有新的数据，也**强行保留本地编辑版本**。
  - **解决远端孤儿 Review**：之前存在的问题：如果本地数据库重置，但远端有已存在的 Review，那么单单 GET Shelf 是拉不到 uuid 的，无法建立关联。修复后会在回拉时通过 `listMyReviewsForItem` 按 `item_uuid` 反查用户的 Review 以补全缺失的 `neoDbReviewUuid`。

## 3. 业务层与 UI 层实现

### 3.1 专辑详情页 (AlbumDetail)
- **UI 组件 (`AlbumDetailScreen.kt`)**：
  - 拥有 `RatingSlider` 进行 0-10 分打分（通过步长控制只在整数停靠）。
  - 有长文输入框用于记录回忆 (Review)，在用户有编辑但未保存时，“Save review”按钮可用。
- **状态流转 (`AlbumDetailViewModel.kt`)**：
  - `setUserRating` 和 `saveUserReview` 直接将数据存入 Room 数据库，同时更新脏标记。
  - **实时响应**：包含一个对 `album_ratings` 数据的 `collect` 观察流，如果通过 Memory 或 NeoDB 拉取造成本地数据发生了变动，详情页会立刻响应（除了当用户正在编辑评论框的“冲突期”外，避免光标或已打字被顶掉）。

### 3.2 回忆甲板 (Memories Deck)
- **UI 组件 (`MemoriesScreen.kt` & `MemoriesDeckCoordinator.kt`)**：
  - 提供类似于卡片流式的回忆功能。
  - 核心入口：**Sync to NeoDB 按钮**（仅在 Album 类型的实体显示）。
- **状态流转 (`MemoriesViewModel.kt`)**：
  - **拦截机制**：当点击 Sync 时，VM 首先校验当前环境 `isNeoDBConfigured()`。如果未配置或 token 失效，发射单次事件 `NeoDBNotConfigured` 跳转到 Settings 以引导用户 OAuth 登录。如果当前内存卡没有本地评分与长评数据，则提示 `NeoDBNothingToSync`。
  - **防连点控制**：利用 `syncingEntityIds` 发送状态，正在同步的卡片按钮将灰显并提示 "Syncing to NeoDB…"。

## 4. 关键决策参考与扩展性隐患

在决定后续的功能演进、UI 翻新或者交互升级时，以下几个方面的现状需要被重视：

### A. 冲突与优先级处理逻辑
- **现状**：本地只要标记为 `needsSync` (未推到 NeoDB 的草稿)，其优先级**永远高于**远端拉取的数据。
- **下一步决策思考**：这是一种典型的“以客户端离线为尊”的模型。这种模型在单点设备上体验极佳。但在多设备 Yoin 登录同一 NeoDB 帐号的场景下，可能会产生覆盖，因为 Yoin 没有做基于时间戳（`updatedAt`）的 CRDT 或复杂的合并提醒机制。如果将来期望更好的跨设备同步，需要引入提示机制（比如：“远端发现了更新，是否应用远端替换本地草稿？”）。

### B. 颗粒度限制 (单曲 vs 专辑)
- **现状**：NeoDB 体系以“专辑”（Album / Item UUID）为最小存储单元。因此 `MemoriesScreen` 中的 `pushToNeoDb` 硬编码了 `if (memory.entityType != MemoryEntityType.ALBUM) return`，**单曲回忆不允许推 NeoDB**。
- **下一步决策思考**：如果在 UI 设计上（例如新的 Memories M3 Expressive 设计卡片），给予用户的“记录灵感/回忆”是一个强感知的普遍操作，那么用户会对“为什么这首歌的回忆不能推 NeoDB”产生疑惑。在 UI/UX 上可能需要把针对单曲的“灵感记录”与针对专辑的“系统化 Review”在视觉和文案上做出区分，或者明确声明这是 Local Only Feature。

### C. 短评与长评 (Mark vs Review)
- **现状**：NeoDB 中有 `ShelfMark.comment_text` (属于短评/状态栏) 和 `Review.body` (博客式长评)。Yoin 的 `album_ratings.review` 映射的是**长评 (Review)**。在同步 Shelf 时，我们特别保护了远端的短评和标签不被覆盖。
- **下一步决策思考**：如果未来希望用户不仅能写大长文回忆，也能进行轻量级的“微点评”（对应 NeoDB `comment_text`），那么当前数据库结构 `AlbumRating` 只有一个 `review` 字段，且 `NeoDBSyncService` 的设计逻辑无法将它们分化处理，这就需要一次 Room 数据库 Migration 以及同步逻辑重构。

### D. 网络环境与重试机制
- **现状**：Room 中的 `upsert` 是同步结果（推完后消除脏位）。失败会在 `NeoDBSyncService.kt` 被 `runCatching` 拦截并抛出，脏位仍保留为 `true`。
- **隐患**：目前没有实现后台的 WorkManager 离线静默重试机制。如果因为网络超时失败，脏位保留了，但直到下一次用户主动触发 Sync 或者进行 Pull 时，才会再次流转。对用户来说，在 Memory 页面遇到网络错误，可能需要人工记忆再去尝试。

## 5. 总结

当前的评分与回忆（NeoDB 同步）系统架构清晰，采用了**数据本地化优先、以 Room 为事实原点（SSOT）**，并将 NeoDB 视为一个同步远端服务的设计思路。该设计最大限度地保障了离线可用性和 UI 的快速响应。

未来的迭代重点建议围绕：
1. **完善异常状态反馈机制**（如提供重试机制/WorkManager）。
2. **多端编辑冲突体验优化**（时间戳冲突检测和 UI 提示）。
3. **单曲级别的“碎片化回忆”存储模式与概念界定**（将其与无法推送到 NeoDB 的现状在 UI 表达上更好地拆分）。