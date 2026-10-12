# Yoin 震动反馈 (Haptic Feedback) 设计提案

> **状态：已实现**（2026-07-25 复核）。§3 的「统一震动接口」已落地为
> `app/src/main/java/com/gpo/yoin/ui/experience/Haptics.kt`——`YoinHaptics` 把
> `HapticFeedbackConstants` 语义化成 `performClick/performTick/performConfirm/performReject/`
> `performLongPress/performContextClick/performLightTick`（低 API 各有降级路径），
> 由 `rememberYoinHaptics()` 取用；当前 23 个 UI 文件（不含 `Haptics.kt` 本身）、80 处调用点。
> 2026-10-04 为 Home 编辑态新增 `performDragStart/performSegmentTick/performThreshold/performToggle(on)`
> （API 34 常量，低版本回退见 §F），编辑态只经 `HomeEditFeedback` 调用。
> 本文保留为场景映射与设计意图的原始依据，不再是待办。

震动反馈能够显著提升应用的操作质感，帮助用户在不完全依赖视觉的情况下确认操作结果。基于 Yoin 当前的 UI 架构（包含正在播放页、首页、详情页、资料库等），本提案建议结合 Android 提供的 `HapticFeedbackConstants` 为不同的交互场景赋予层次分明的震动体验。

## 1. 核心设计原则

- **克制与必要性**：并非所有点击都需要震动，避免“滥用”震动导致手指疲劳。只在**状态变更**、**操作确认**和**重要边界**发生时触发。
- **层次分明**：根据操作的“分量感”（如播放暂停 vs. 切换歌曲 vs. 收藏）提供不同级别的震动反馈。
- **情感反馈**：破坏性操作（如删除）和负面反馈（失败、边界限制）应提供有阻尼或沉闷的震动；正面、鼓励性的操作（如收藏）应提供干脆的确认感。

## 2. 交互场景与震动映射建议

我们将使用 Compose 的 `LocalHapticFeedback.current.performHapticFeedback()` 来实现以下设计（或底层 `View.performHapticFeedback`）。

### A. 播放控制与正在播放页 (Now Playing)

正在播放页是音乐应用的心脏，操作频率高，需提供扎实的手感。

| 组件 / 动作 | 推荐震动类型 (HapticFeedbackConstants) | 设计意图 |
| --- | --- | --- |
| **播放 / 暂停 (Play/Pause)** | `KEYBOARD_TAP` 或 `CLOCK_TICK` | 播放控制是核心功能，提供短促而有力的“咔哒”感，确认状态切换。 |
| **上一首 / 下一首 (Skip)** | `CLOCK_TICK` | 较轻微的反馈，表示列表移动，但比普通按钮稍微干脆。 |
| **长按收藏 (Add to Playlist)** | `LONG_PRESS` | 长按操作的标准反馈，告知用户操作已识别，即将弹出菜单或生效。 |
| **进度条拖动 (Slider Drag)** | 滑动时不震动，松手吸附或到达尽头时 `TEXT_HANDLE_MOVE` 或 `SEGMENT_TICK` | 如果有歌词吸附或边界，到达边界时提供极轻微的摩擦感。 |
| **底栏按键 (Cast, Queue, Devices)**| `CONTEXT_CLICK` 或 `VIRTUAL_KEY` | 二级功能菜单，使用标准轻量点击反馈。 |

### B. 核心业务操作：收藏与反馈

| 组件 / 动作 | 推荐震动类型 (HapticFeedbackConstants) | 设计意图 |
| --- | --- | --- |
| **点击收藏 (Favorite/Star)** | `CONFIRM` | 用户做出情感偏好，使用清晰的确认感（通常是短促的两下或一下清脆震动）。 |
| **取消收藏 (Unfavorite)** | `REJECT` 或 `CLOCK_TICK` | 相比于收藏，取消操作稍微沉闷，形成情感对比。 |
| **提交评价 (Save Review)** | `CONFIRM` | 明确告知用户撰写的长文本已安全保存。 |

### C. 导航与滑动 (Navigation & Scroll)

目前应用有大量的 List 和 Swipe 操作（如 Pull to Dismiss）。

| 组件 / 动作 | 推荐震动类型 (HapticFeedbackConstants) | 设计意图 |
| --- | --- | --- |
| **边缘滑动返回 / 退出 (Edge Advance / Pull to Dismiss)** | 滑动越界触发点 `GESTURE_START`，释放返回 `GESTURE_END` 或 `CONFIRM` | 在 `InteractionPrimitives.kt` 中的 `EdgeAdvanceState` 触发阈值时给出明确提示。 |
| **底部导航切换** | `VIRTUAL_KEY` 或 `KEYBOARD_PRESS` | 切换主 Tab 时提供底层基石般的稳固反馈。 |
| **下拉刷新 / 滑动到列表尽头** | 尽头触发 `SCROLL_ITEM_FOCUS` 或 `SCROLL_TICK` | 模拟物理橡皮筋拉满的紧绷感。 |

### D. 列表、卡片与内容交互 (Home, Detail, Library)

在首页或列表页浏览时，震动应尽最大可能轻量化。

| 组件 / 动作 | 推荐震动类型 (HapticFeedbackConstants) | 设计意图 |
| --- | --- | --- |
| **点击专辑/歌曲卡片 (Album/Song Card)** | *无震动* 或极轻微 `VIRTUAL_KEY` | 避免浏览过程中高频点击产生的烦躁感，仅在网络延迟导致无视觉即时响应时才作为补偿。Home 的 Jump Back In 卡片原有的点按 `performContextClick` 已于 2026-10-04 删除，Home 上点卡片一律不震。 |
| **长按卡片** | `LONG_PRESS` | 明确长按手势已被识别。**长按 Home 的卡片进入 Home 编辑态**，并拿起所在 section（见 §F）；其它页面仍是各自的长按动作（如呼出上下文菜单、加入歌单）。 |
| **展开折叠菜单 (MoreVert/Dropdown)** | `CLOCK_TICK` | 菜单弹出的机械感反馈。 |

### E. 破坏性操作与错误反馈 (Settings, Delete)

| 组件 / 动作 | 推荐震动类型 (HapticFeedbackConstants) | 设计意图 |
| --- | --- | --- |
| **删除歌单 / 删除账户 (Delete)** | `REJECT` 或长且重的 `LONG_PRESS` | 增加确认的心理阻力，提示操作的严重性。 |
| **操作失败 / 错误重试 (Error / Retry)** | `REJECT` | 如果网络失败或连接报错（如 NeoDB 登录失败），给出警告性质的连震。 |

### F. Home 编辑态（2026-10-04）

Home 编辑态的触感总表（来自 `docs/handoff/home-edit-mode/spec.md` §2.8）。编辑态的触感只经 `HomeEditFeedback` 发出，底栏在编辑态不再自己震。Pixel Tablet 没有振动马达，所以每一行都有一个能看到的等价动效（视觉孪生），触感本身只能在手机上验收。

| 时机 | 方法 | 常量（API） | 低版本回退 | 视觉孪生 |
| --- | --- | --- | --- | --- |
| 普通态长按到阈值（进入编辑） | `performLongPress()` | `LONG_PRESS` | — | 块放大到 1.02，底板从按点长出来 |
| 编辑态拿起一块 | `performDragStart()` | `DRAG_START`（34） | `CONTEXT_CLICK` | 放大 + 阴影 |
| 签条每换一格；TalkBack 的 Move up / Move down | `performSegmentTick()` | `SEGMENT_TICK`（34） | `CLOCK_TICK` | 邻居让位，落点的洞跟着移动 |
| 签条第一次越过首尾 | `performThreshold()` | `GESTURE_THRESHOLD_ACTIVATE`（34） | `TEXT_HANDLE_MOVE`（27，即 `performLightTick()`） | 橡皮筋阻尼 |
| 放下且顺序变了；Done | `performConfirm()` | `CONFIRM`（30） | `KEYBOARD_TAP` | 展开 + 放下时的 kick；底栏变回导航 |
| 原地放下、点块、滚动 | 无 | — | — | 0.5Θ 的 kick + 把手脉冲 |
| 隐藏 / 显示 | `performToggle(on)` | `TOGGLE_OFF` / `TOGGLE_ON`（34） | `CONTEXT_CLICK` | 缩放淡出 / 淡入 + 0.35Θ 的 kick |
| Reset Home | `performReject()` | `REJECT`（30） | `LONG_PRESS` | 各块回到默认位置 |
| 底栏 Undo / Add | `performClick()` | `KEYBOARD_TAP` | — | 按钮的按压形变 |
| 系统返回（= Done） | 无 | — | — | 底栏变回导航 |

一次进入只震一次，就是阈值那一下；点卡片不震（§D）。底栏 Home 键长按进入编辑属于 P1，尚未实现。

### F.1 行数把手 ⌟（2026-10-05，H5）

Activities 和 Jump Back In 在编辑态底板右下角的 ⌟ 把手（规则见 `docs/design.md`「主页 › 就地编辑 › 行数」，实现 `ui/home/edit/HomeRowsResize.kt` 的 `HomeRowsEngine`）。和 §F 一样只经 `HomeEditFeedback` 发出，复用同一组方法和低版本回退，没有新常量。Pixel Tablet 没有振动马达：每一行都有视觉孪生，触感只能在手机上验收。

| 时机 | 方法 | 常量（API） | 低版本回退 | 视觉孪生 |
| --- | --- | --- | --- | --- |
| 按住把手（开始调整） | 无 | — | — | 把手变 primary、放大 12%；这一块停止摆动 |
| 拖动中跨过一档（越过两档中点再 6dp 才算，有滞回） | `performSegmentTick()` | `SEGMENT_TICK`（34） | `CLOCK_TICK` | 把手脉冲一下（快空间弹簧；减少动态下不脉冲）；卡片在两档之间逐张插值 |
| 一次拖动里第一次越过最大档 / 最小档（每个方向每次拖动只打一次） | `performThreshold()` | `GESTURE_THRESHOLD_ACTIVATE`（34） | `TEXT_HANDLE_MOVE`（27，即 `performLightTick()`） | 橡皮筋阻尼（量程 56dp，系数 0.55） |
| 松手时快甩多走一档（≥ 650dp/s，落点 ≠ 手指下的档） | `performSegmentTick()` | `SEGMENT_TICK`（34） | `CLOCK_TICK` | 把手脉冲 |
| 松手后档位真的变了（写入、算一步 Undo） | `performConfirm()` | `CONFIRM`（30） | `KEYBOARD_TAP` | 块高按默认空间弹簧落到新档 |
| 松手落回起始档 | 无 | — | — | 块高弹回 |
| 手势被系统拿走（取消） | 手指下已经换了档时，回到起始档那一下打 `performSegmentTick()`；否则无 | `SEGMENT_TICK`（34） | `CLOCK_TICK` | 把手脉冲，块高弹回起始档；不写入 |
| 键盘 ↑ / ↓；TalkBack 的 More rows / Fewer rows | `performSegmentTick()`（不打 CONFIRM） | `SEGMENT_TICK`（34） | `CLOCK_TICK` | 把手脉冲 + 和松手同一条弹簧落位；TalkBack 读出新的 "N rows" |
| Undo / Reset 把档位改回去 | 只有底栏按钮自己的触感（Undo `performClick()`、Reset `performReject()`，见 §F） | — | — | 块按同一条弹簧变回 |

一次拖动最多：每跨一档一拍 tick，两个边界各一拍 threshold，抬手时最多再一拍 tick（快甩）和一拍 confirm。按住把手本身不震，和「编辑态拿起一块」（`performDragStart()`）区分开。

### G. 首次引导（Landing，2026-10-09）

“开始”“下一步”“跳过”不震（只是翻页）。平板没有马达，触感只能在手机上验。

| 时机 | 方法 | 视觉孪生 |
| --- | --- | --- |
| 入场挥手挥到顶（两次）；点 Yoin | `performTick()` | 手臂到最高点 |
| 选中 / 取消服务；主页区块开关 | `performToggle(on)` | 头像变成曲奇形 + 卡片换服务色；开关滑动 |
| 拿起区块 / 每换一格 / 放下且顺序变了 | `performDragStart()` / `performSegmentTick()` / `performConfirm()` | 行放大、换色，邻居让位 |
| 选令牌服务；选滚动边缘样式 | `performContextClick()` | 单选点；小图描边 |
| 复制（页面里和画中画小窗里） | `performConfirm()` | “已复制” |
| 连接成功 / 输入有误、没选服务就点下一步 | `performConfirm()` / `performReject()` | Yoin 举手蹦一下 / 表单横抖、Yoin 摇头 |
| 进入 Yoin | `performConfirm()` | 胶囊变成首页底栏 |

### H. 资料库快速滚动条（2026-10-10，U2）

All / Artists / Albums 贴边的把手（`ui/component/YoinFastScroller.kt`，直接用 `rememberYoinHaptics()`，没有新常量）。平板没有马达：每一行都有视觉孪生，触感只能在手机上验。

| 时机 | 方法 | 常量（API） | 低版本回退 | 视觉孪生 |
| --- | --- | --- | --- | --- |
| 按住把手 | 无 | — | — | 把手变 primary；刻度列和气泡展开；列表若还在惯性滑动，原地停下 |
| 拖动中进入下一个刻度（字母；时间线是年份，跨度不到两年时是月份）；距上一拍不到 45ms 的不打 | `performSegmentTick()` | `SEGMENT_TICK`（34） | `CLOCK_TICK` | 气泡脉冲一下（快空间弹簧；减少动态下不脉冲），当前刻度变 primary |
| 气泡里的年月变了，但还在同一个刻度里 | 无 | — | — | 只换字 |
| 松手 | 无 | — | — | 刻度列和气泡收起；把手留在手指离开的地方，列表自己动了或长度变了才用弹簧回到列表的位置，否则原地淡出 |

## 3. 技术落地建议

1. **统一震动接口**：建议在 `com.gpo.yoin.ui.experience` 包下新建 `Haptics.kt`，封装一个全局的扩展函数或组合项（Composable），将硬编码的 `Constants` 语义化。例如：
   ```kotlin
   fun HapticFeedback.performConfirm() = performHapticFeedback(HapticFeedbackType.LongPress) // 或更高API的 CONFIRM
   fun HapticFeedback.performLightClick() = performHapticFeedback(HapticFeedbackType.TextHandleMove)
   ```
2. **结合 InteractionSource**：现有的代码大量使用了 `MutableInteractionSource`（如 `PressFeedback.kt`），可以通过监听 `collectIsPressedAsState()`，在按下 (Press) 和释放 (Release) 时分别提供不同轻重的震动，实现类似物理按键的下压和回弹感。
