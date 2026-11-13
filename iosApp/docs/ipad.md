# iPad / 大屏

## 现在的样子

iPad 用 `PadShell`（`Yoin/Shell/PadShell.swift`），不用系统 Tab 栏，也不用侧边栏。两列都通到屏幕顶边（状态栏下面是和手机一样的柔和色带），导航交给 Yoin 自己的两块浮动玻璃，放在窗口底部的两个角：左下是导航胶囊（Home、Library、Search），右下是手机上那颗迷你播放器（51 2026-11-11 定：不要系统 Tab 栏和侧边栏；「两个角」胜过「底部居中一条」）。宽窗口里详情页在同一窗口的右列打开，Now Playing 是列旁边的侧栏，放不下时直接 Full。

早先这一页比较过三个方案（A 系统顶栏 + Inspector、B 保留 Yoin 底栏、C 三栏侧边栏）。最后做的是 B 的变体（两个角），A 和它的 Labs 开关、`-ipadNav` 参数都已删掉（e29bb8768）。

```
iPad 横屏，开着一张专辑和 NP 侧栏
┌──────────────────────────────────────────────────────────────────┐
│ ┌──────── shell 列 ──────┐ ┃ ┌──── 详情列 ────┐ ╭── NP 侧栏 ──╮ │
│ │ Home feed              │ ┃ │ ▣ 专辑封面      │ │   ▣ 封面     │ │
│ │ ▣ ▣ ▣ ▣                │ ┃ │ 标题 / 评分     │ │   歌名       │ │
│ │ ▣ ▣ ▣ ▣                │ ┃ │ 1. 曲目         │ │ ~~~~~──      │ │
│ │ Recently added …       │ ┃ │ 2. 曲目         │ │ ◁◁  ▶  ▷▷   │ │
│ │ ╭ Home Library ⌕ ╮     │ ┃ │ ╭ Play ▾ ╮      │ ╰──────────────╯ │
│ └────────────────────────┘ ┃ └────────────────┘                  │
│        把手（可拖，只钳制）┛                                       │
└──────────────────────────────────────────────────────────────────┘
NP 侧栏关着时，右下角是迷你播放器的玻璃胶囊。
```

## 守住的 Android 原则

1. **按容器的宽高换档，不按设备型号。** 进不进 `PadShell` 看系统的 `horizontalSizeClass`（regular 用 `PadShell`，compact 用 `PhoneShell`，所以 iPad 上很窄的窗口就是手机界面）；进了 `PadShell` 以后，列、侧栏、Full 的档位都读窗口的实际宽高，照搬 Android 的 840 / 480 和 744 / 320 这几条线。
2. **一套 Now Playing 状态链：Closed → Panel → Full。** 从侧栏进 Full，关掉 Full 回到侧栏（不论是 ⌄、下拉还是返回）；从迷你播放器进 Full，关掉回到迷你播放器。
3. **详情是同一窗口里的列，不是第二个窗口。**
4. **换档不落在弹簧中途；数量跟宽度走，卡片内部不缩放。**
5. **返回是进入的反序（Android #200）。** ⌘[ 先关盖住整个窗口的（搜索、Full Now Playing、窄窗里推上来的页），再按打开顺序从新到旧关并排的面：Now Playing 侧栏、详情列（先退列里的栈，再关列）、Memories。列里再开一页、侧栏进歌词或专注、侧栏放大成 Full，都算重新进入，记为最新。规则在 `Shell/ShellBackOrder.swift`（纯函数，有单测）。

## 导航

- `TabView` 只有一个 Tab，Tab 栏隐藏（`.toolbarVisibility(.hidden, for: .tabBar)`）。Home / Library 和 Search 在这个 Tab 里交叉淡入淡出，各自在两次访问之间保留状态（Search 第一次打开时才建）。
- 导航胶囊和迷你播放器就是 `ShellBar` 的两半（`pad: .nav` / `pad: .player`），所以选中态、颜色、波浪、长按和失败横幅都和手机一样。尺寸在 `PadDockMetrics`：玻璃高 60，离底边 22、离侧边 24，播放器宽 320。
- NP 侧栏开着时迷你播放器让开（不然屏幕上有两个播放器）。Memories 从 Home 下拉时占满整个窗口，两块玻璃跟着下拉退场。

## 详情列

- 窗口宽 ≥ 840 时，专辑、歌手、歌单、Settings 都在右列打开；更深的页面在列自己的 `NavigationStack` 里推入，shell 列不动。窗口窄于 840 时只有一个栈，所有页面推到 shell 上面。
- 列宽照搬 `resolvePaneBudget`：shell 份额 0.45，窗口 ≥ 1080 时 shell ≥ 600，两列都 ≥ 360。中间是自己画的把手（`DragGesture` 1:1 跟手、钳制、不吸附，松手停在原地；窗口宽度一变就回到预算值）。不用 `NavigationSplitView`。
- 开列时 shell 在点击那一帧让出列宽，列带着页面从右边滑进来；关列时列先滑出去，shell 在落定时收回宽度。都是 spatial 弹簧。
- 详情列和推入的详情页各有一个 Play 分裂按钮，在列的左下角；列太窄放不下 Play 和迷你播放器同一行时，Play 抬到上面一行。

## Now Playing

- **Panel：** 自己画的侧栏（不是系统 `.inspector`，因为它在窄窗口会自己变成底部弹层）。宽 = clamp(窗口宽 × 0.5, 360, 420)。只有当窗口减去侧栏还剩 ≥ 320（开着详情列时 ≥ 744）才开侧栏，否则点迷你播放器直接进 Full（`AppModel.padPanelFits`）。侧栏开着时如果又开了详情列、两列放不下，侧栏退开。进出按自己的宽度平移，不淡入淡出。
- **Full：** 从右下角的迷你播放器长出来、关闭时折回去（`NowPlayingMorph`，和手机同一个进度：⌄、拖动、返回都驱动它）；从侧栏进时从侧栏的位置长出来。窗口宽 ≥ 840 且高 ≥ 480 时是双栏播放器（`NowPlayingPadFrame.wide`，Android DualPane），其他 iPad 窗口是放大的手机单栏（`.enlarged`）。

## iPad 专属的补充

- **键盘：** `Yoin/App/YoinCommands.swift`，和 YoinMac 的 Controls 菜单同一套快捷键，按住 ⌘ 能看到。
  - Controls：播放 / 暂停（空格）、下一首（⌘→）、上一首（⌘←）、随机（⌥⌘S）、重复（子菜单）、喜欢（⌘D）、Now Playing（⇧⌘P，有空间时开侧栏）、Search（⌘K）、返回（⌘[）、Settings（⌘,）。
  - Go：Home（⌘1）、Library（⌘2）、All Memories（⌘3）。
- **指针：** 卡片在指针下抬起（`PressScale` 的 `.hoverEffect(.lift)`）；歌曲行、展开歌词行、底栏胶囊、分栏把手用 M3 悬停状态层（8 %，effects 弹簧），进度条未播部分加深 8 %；把手的指针是竖线加左右箭头（`Theme/PointerHover.swift`）。右键（或 Control 点按）打开和长按同一张歌曲面板（`Theme/SecondaryClick.swift`）；歌曲、专辑、歌单能拖到队列或歌单上（`Library/YoinDrag.swift`）。
- **多窗口：** `UIApplicationSupportsMultipleScenes` 已开，但所有窗口共用一个 `AppModel.shared`，所以页面路径、侧栏和 Full 的状态在窗口之间是共享的，不是 Android 的「一个窗口一棵树」。
- **窗口拖动缩放：** 列宽、侧栏是否放得下、Full 的档位都按实时宽高重新算；把手拖过的宽度在窗口宽度变化时作废。

## 怎么检查

`qa-script.md` 第 3 节：在 iPad Pro 13 上跑 `home library album settings np search srv`，竖屏和横屏各一遍；`-screen np-panel` 直接打开侧栏。对照 `docs/adaptive-principles.md` 末尾的「各窗口一览」表看换档。
