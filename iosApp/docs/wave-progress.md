# 波浪进度条上 iOS

## 结论

**照搬 Android 的数字，用 SwiftUI 自己画；在玻璃界面里选「(a) 色调波浪 + (c) 只在播放时起伏」。**

- 波浪本身是实心线条，颜色取封面色（或深色封面上的白色），底下的轨道是安静的半透明直线。
- 播放时起伏，暂停时用 500ms 收平成直线（和 Android 一样）；手指按住拖动时也收平（这是新加的，见下文）。
- 玻璃只用在「拖动时冒出来的时间气泡」和整块播放控制面板上，**不给细线本身做玻璃**。

代码：`wave/WavyProgress.swift`（单文件，iOS 26 / Swift 6，自带 `#Preview`，拖进 Xcode 工程就能在画布里看三种样式）。
几何示意图：`shots/wave-geometry.png`（Yoin 播放中 / 收平一半 / 平直，以及 M3E 默认值，对比用）。

## 三个方向，为什么这样选

| 方向 | 样子 | 判断 |
| --- | --- | --- |
| (a) 色调波浪在安静轨道上 | 和 Android 一模一样，颜色取封面 | **选。** Yoin 的辨识度就在这根线上；在玻璃控制面板里，实心的一根线反而最清楚 |
| (b) 波浪做成玻璃管 | 线条加粗到约 8pt，里面是折射的玻璃 | **不选，留作实验。** 液态玻璃要有面积才看得出折射，4pt 的线做成玻璃就是一根发灰的线；而且每帧都要重算玻璃，费电 |
| (c) 只在播放时起伏，暂停/拖动时放松成直线 | 状态一眼可见 | **选，和 (a) 叠用。** 暂停收平是 Android 已有的；拖动时收平是新增：手指下面的线在摆动，不好看准位置，这时由玻璃时间气泡负责「在动」的感觉。不喜欢就把 `flattensWhileScrubbing` 设成 `false`，和 Android 完全一致 |

`WavyProgress.swift` 里三种都能切：`look: .tint`（默认）/ `.glassTube`，`flattensWhileScrubbing`。

## 「原版的样子去哪里找」

全部数字都在 androidx 的源码里，Yoin 钉的版本是 `material3 1.5.0-alpha27`（`gradle/libs.versions.toml` 的 `material3Expressive`）。

**最快的看法：** Android Studio 里在 `WaveProgressBar.kt` 上按住 ⌘ 点 `LinearWavyProgressIndicator`，就会打开带源码的 jar。或者直接下载：
`https://dl.google.com/android/maven2/androidx/compose/material3/material3-android/1.5.0-alpha27/material3-android-1.5.0-alpha27-sources.jar`

**要看的文件：**

| 文件 | 里面有什么 |
| --- | --- |
| `tokens/LinearProgressIndicatorTokens.kt` | 线粗 4、轨道粗 4、容器高 10、振幅 3、波长 40、线和轨道的间隙 4、末端圆点 4 |
| `WavyProgressIndicator.kt` → `WavyProgressIndicatorDefaults` | 上面这些 token 的公开名字；`waveSpeed = wavelength`（每秒走一个波长）；默认振幅函数（前 10%、后 5% 拉平）；振幅动画：变大 500ms `Standard (0.2, 0, 0, 1)`，变小 500ms `EmphasizedAccelerate (0.3, 0, 0.8, 0.15)` |
| `internal/LinearWavyProgressModifiers.kt` | 怎么画：二次贝塞尔半波，控制点在 ±(高 − 线粗)，所以峰值 = 振幅 × (高 − 线粗) / 2；先按满振幅画好、按弧长截取、再沿 y 压扁；轨道从「头部 + 间隙 + 两个圆头」开始 |
| `tokens/MotionTokens.kt` | 上面两条缓动曲线和 500ms |

**Yoin 自己改过的数**（`app/.../ui/component/WaveProgressBar.kt`）：

| | M3E 默认 | Yoin |
| --- | --- | --- |
| 高 | 10 | 18（触摸区 `SeekTouchHeight`，整个控件 40 高，上面留给时间气泡） |
| 线粗 | 4 | 4 × 0.92 = 3.68 |
| 振幅 | 1（峰值 3） | 0.28（峰值 0.28 × (18 − 3.68) / 2 ≈ **2.0**） |
| 波长 / 速度 | 40 / 每秒一个波长 | 24 / 每秒一个波长 |
| 间隙 | 4 | 1 |
| 末端圆点 | 4 | 无 |
| 轨道 | 圆头 | 平头，透明度 14%（缓冲超前时 20%） |
| 振幅规则 | 首 10%、尾 5% 拉平 | 只看播放状态，整首歌都起伏（2026-10-08 / 10-10 owner） |
| 头部移动 | 跟着进度 | 播放器每秒报 4 次位置，中间 300ms 线性滑过去；跳动超过 6% 直接跳 |

这些都原样写进了 Swift 文件顶部的 `WavyProgressMetrics.yoin` / `.material` 和 `WavyMotion`，旁边注明出处，以后 Android 改了数，对着改一处即可。

## SwiftUI 里怎么画（给 Android 背景的对照）

- **`Shape`** ≈ Compose 里 `DrawScope` 画一条 `Path`。`WavyActiveShape` 就是把 M3 的那段造路径代码逐行翻译过来：同样的二次曲线、同样的「先满振幅、按弧长截、再压扁」。
- **`TimelineView(.animation)`** ≈ `withFrameNanos` 循环：每一帧给出当前时间，算出波的相位。暂停时它整个停掉，静止时零开销（和 design.md 里「静止时零开销」一致）。
- **`animatableData`** ≈ 让 Compose 的 `Animatable` 去插值：进度和振幅交给 SwiftUI 的动画系统插值（用上面那两条 500ms 曲线），相位每帧直接算。
- **系统「减少动态效果」**打开时，相位固定不走、波形保留（和 Android 省电模式 / 移除动画时的潮线一样）。
- 震动用 `.sensoryFeedback(.selection, …)`，松手时一下。

## 和 Android 不一样的地方（有意的）

1. **暂停时相位冻结，而不是归零。** M3 暂停时把相位直接拍回 0（在 500ms 收平过程里会有一下小跳），Swift 版停在原处再收平，没有跳。
2. **拖动时收平**（可关）。
3. **时间气泡是玻璃胶囊**，带一点封面色；Android 是 `secondaryContainer` 实心胶囊。
4. 还没移植：笔记锚点的磁吸、经过锚点时的震动和气泡里的「Note」字样。缓冲超前时轨道变亮（14% → 20%）已经保留。

## 还要在 Mac 上确认的

- 这里是 Linux，Swift 没法编译。几何是用 Python 按同一套公式画出来对过的（`shots/wave-geometry.png`），但 SwiftUI 代码本身要在 Xcode 里打开预览确认一次。
- 波的走向：按源码，波峰是朝起点方向（往左）流的。如果在 Pixel 上看到的是往右，把 `WavyActiveShape` 里算 `shift` 的那行改成用 `1 - phase` 即可（只改相位，截取和平移会跟着一起对）。
- 迷你播放条（底部胶囊）里那道波浪可以复用同一个 `WavyActiveShape`，换一组更小的数。
