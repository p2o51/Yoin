# Yoin 震动反馈 (Haptic Feedback) 设计提案

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
| **点击专辑/歌曲卡片 (Album/Song Card)** | *无震动* 或极轻微 `VIRTUAL_KEY` | 避免浏览过程中高频点击产生的烦躁感，仅在网络延迟导致无视觉即时响应时才作为补偿。 |
| **长按卡片 (呼出上下文菜单)** | `LONG_PRESS` | 明确长按手势已被识别。 |
| **展开折叠菜单 (MoreVert/Dropdown)** | `CLOCK_TICK` | 菜单弹出的机械感反馈。 |

### E. 破坏性操作与错误反馈 (Settings, Delete)

| 组件 / 动作 | 推荐震动类型 (HapticFeedbackConstants) | 设计意图 |
| --- | --- | --- |
| **删除歌单 / 删除账户 (Delete)** | `REJECT` 或长且重的 `LONG_PRESS` | 增加确认的心理阻力，提示操作的严重性。 |
| **操作失败 / 错误重试 (Error / Retry)** | `REJECT` | 如果网络失败或连接报错（如 NeoDB 登录失败），给出警告性质的连震。 |

## 3. 技术落地建议

1. **统一震动接口**：建议在 `com.gpo.yoin.ui.experience` 包下新建 `Haptics.kt`，封装一个全局的扩展函数或组合项（Composable），将硬编码的 `Constants` 语义化。例如：
   ```kotlin
   fun HapticFeedback.performConfirm() = performHapticFeedback(HapticFeedbackType.LongPress) // 或更高API的 CONFIRM
   fun HapticFeedback.performLightClick() = performHapticFeedback(HapticFeedbackType.TextHandleMove)
   ```
2. **结合 InteractionSource**：现有的代码大量使用了 `MutableInteractionSource`（如 `PressFeedback.kt`），可以通过监听 `collectIsPressedAsState()`，在按下 (Press) 和释放 (Release) 时分别提供不同轻重的震动，实现类似物理按键的下压和回弹感。
3. **设置开关**：为了兼顾部分对震动敏感的用户，建议在 Settings 中提供“触感反馈”总开关。
