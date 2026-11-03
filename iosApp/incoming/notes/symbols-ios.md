# Yoin Symbols 上 iOS

## 结论

**做成 SF Symbols 的「自定义符号」，放在 Xcode 的素材目录（asset catalog）里，用 Swift Package 发布。**
每个图标是一个 `.symbolset` 文件夹，里面一张模板 SVG，模板里只放三个粗细（Ultralight / Regular / Black），系统自己插值出其余 24 种粗细和大小。样品和生成脚本已经做好：

- `symbols-ios/YoinSymbols.xcassets/`：5 个样品（play_filled、pause_filled、home、library、favorite），整个文件夹拖进 Xcode 就能用
- `symbols-ios/generator/gen_sfsymbols.py`：生成脚本，读 yoin-symbols 仓库里同一份中心线源，和 Android / Flutter / Web 同源
- `symbols-ios/package/`：Swift Package 骨架（`Package.swift` + 生成的 `YoinSymbol.swift` 枚举 + 同一份素材目录）
- `symbols-ios/preview/weights.png`：三个源粗细 + 两个插值出来的中间粗细；`template-library.png`：模板长什么样

跑法：`python3 gen_sfsymbols.py --repo /path/to/yoin-symbols play_filled home …`（不写名字就是全部 170 个）。

## 给 Android 背景的对照

| iOS | 相当于 Android 的 |
| --- | --- |
| asset catalog（`.xcassets`） | `res/drawable`，但带元数据 |
| SF Symbols | Material Symbols，但它不是图片而是「会跟字体走的字形」 |
| 自定义符号 `.symbolset` | 自己做的 Material Symbol，系统当成原生符号对待 |
| Swift Package | 一个 Gradle 模块 + Maven 坐标，只是不需要上传：给 git 打 tag 就算发布 |

## 为什么是自定义符号

自定义符号在系统眼里和苹果自己的符号一样，所以下面这些都能直接用：

1. **跟着字走。** `Image(yoin: .home).font(.title2)` 会按标题字号排，和文字对齐在同一条基线上；`.fontWeight(.semibold)` 会让线条变粗；用户调大系统字号（Dynamic Type，相当于 Android 的字体缩放）时图标跟着长。
2. **系统动效。** `.symbolEffect(.bounce)`、`.pulse`、`.contentTransition(.symbolEffect(.replace))` 都能用。
3. **只收符号的地方也能放。** Tab 栏、菜单、右键菜单、Widget、灵动岛 / 实时活动、控制中心按钮（ControlWidget 只接受符号，不接受普通图片）。这一条决定了不能只交 PDF。
4. **一份源出所有粗细。** 模板只要三个粗细，系统插值出 9 个字重 × 3 个大小。

## 另外两条路，为什么不选

- **图标字体（Flutter 那份 TTF）**：放进 `Text` 能跟字号走，但只有一个粗细，没有符号动效，Tab 栏和控制中心不能用，颜色只能当文字颜色。可以留给 Web 风格的场景，不当主交付。
- **PDF / SVG 普通矢量图**：最省事（Xcode 勾「Preserve Vector Data」就能任意放大），但粗细固定、不和文字对齐、没有动效、进不了控制中心。只适合当实在出了问题时的临时兜底。

## 生成器怎么做的（为什么不能直接用现成工具）

苹果的硬性要求：三个源粗细必须是「纯填充路径」（不能是描边），而且**路径数量和控制点数量完全相同**，系统才能在它们之间插值。

Yoin 的图标是 1.5 粗的描边中心线。现成办法（`gen_font.py` 用的 shapely 外扩）每换一个粗细，点数就不一样，没法插值。所以脚本自己把描边展开成轮廓，规则固定、只让粗细移动点的位置：

- 中心线在尖角处切成几段，每段展开成一根两头圆的「香肠」；闭合的圆滑曲线（圆、方圆）展开成一个环。
- 圆角连接不用另画：相邻两段的圆头互相重叠，自然就是圆角。
- 曲线用「端点沿法线外移、控制柄按曲率缩放」的办法偏移，对圆弧是精确的。
- 实心图标（play_filled 等）保留它的填充多边形，再叠上同一条中心线的描边，和 Android 的实心孪生一样。

粗细取值（以 24 格网格计）：Ultralight 0.5、**Regular 1.5（就是 Yoin 本来的粗细）**、Black 2.5。大小：16 格 = 文字的大写字母高度，所以心形和播放键差不多是一个大写字母高，房子略高一点，和苹果自己的符号比例接近。

已验证（在 Linux 上能验的部分）：

- 全部 170 个图标都生成成功，三个粗细的路径结构逐段一致（脚本里有检查，不一致会直接报错）。
- 用 Chromium 把展开后的轮廓和原始描边逐像素比：Regular 最差 1.9%（waves），大多 < 0.5%，差异只在抗锯齿边缘。
- 插值出的中间粗细看起来干净，没有破洞（见 `preview/weights.png`）。

## 能不能导入 Xcode：我的把握

**大约八成第一次就能导入，九成五以上改一处小地方就行。** 这里是 Linux，没法真跑 Xcode。

- 模板结构照苹果文档（Creating custom symbols）和 SwiftDraw 的 v3.0 模板来写：`Notes / Guides / Symbols` 三组、`template-version`、`Baseline-S / Capline-S`、每个粗细的左右边距线、`Ultralight-S / Regular-S / Black-S`。SwiftDraw 生成的就是这个格式，社区里大量拿它直接拖进 Xcode。
- 主要风险：
  1. 轮廓之间有重叠（靠「非零环绕」规则填满）。单色显示没问题；苹果自己的符号一般不重叠，如果 Xcode 的校验对这个挑剔，改成在生成器里做一次布尔合并即可（但合并后点数会因粗细而不同，要再想办法保持一致）。
  2. Black 2.5 是我挑的值，粗了或细了只改 `WEIGHTS` 一行。
- 在 Mac 上验收（5 分钟）：
  1. 把 `YoinSymbols.xcassets` 拖进任意 Xcode 工程；在素材目录里点开 `yoin.home`，能看到图标网格就说明导入成功。
  2. 或者打开 SF Symbols app → File → Validate Templates，选这几个 SVG。
  3. 写一行 `Image("yoin.home").font(.largeTitle).fontWeight(.black)` 看粗细是否跟着变。

## 还没做的（下一步）

- **分层上色**（hierarchical / palette，比如心形外框和填充两层不同透明度）：需要在 SF Symbols app 里给路径标注图层，或在生成器里按 `id` 写 class。现在只有单色。
- **Draw 动画、Magic Replace**（iOS 26 的「一笔一笔画出来」）：要在 SF Symbols app 里加标注，生成器还不会写。
- **播放 ⇄ 暂停变形**：系统的 `.replace` 是模糊淡入淡出，不是 Yoin 的逐点变形。想要原样的变形，就像 Android 的 `SymbolGeometry.kt` 一样，让生成器再输出一份 Swift 的路径数据，用 SwiftUI `Shape` 自己插值。
- **右到左镜像**（back、chevron 等，Android 的 autoMirror）：素材目录里每个符号要设方向属性，具体键名要在 Mac 上确认后加进生成器；生成的枚举里已经列出了需要镜像的符号。

## 怎么像 Android / Flutter 包一样发布

- 仓库根目录加 `Package.swift`，`ios/Sources/YoinSymbols/` 下放生成的 `YoinSymbol.swift` 和 `Resources/YoinSymbols.xcassets`（`package/` 就是这个结构的样品）。
- `build.py` 里加一行 `gen_ios.build()`，和 android / web / flutter 并列；CI 照样比对生成结果有没有漂移。
- 发布不用额外操作：Swift Package 直接按 git tag 拉取，现有的 `vX.Y.Z` tag 就是版本。App 里写 `.package(url: "https://github.com/p2o51/yoin-symbols", from: "x.y.z")`。
- 用法：`Image(yoin: .playFilled)`、`Label("Library", yoin: .library)`、`UIImage(yoin: .home)`；Tab 栏里可以直接写 `Tab("Home", image: "yoin.home") { … }`（跨包时要带 bundle，用 `Image(yoin:)` 那个包装更稳）。
- 170 个图标的素材源文件约 3.5 MB（SVG 文本），编译进 App 后是压缩的 `Assets.car`，小得多。

参考：苹果文档 Creating custom symbols（developer.apple.com/documentation/technologyoverviews/custom-sf-symbols）；SwiftDraw（github.com/swhitty/SwiftDraw）的模板格式。
