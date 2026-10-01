# Yoin 图标系统 · Luminous 版

> 2026-09-29 重做，取代 2026-08-28 的第一版。参考源：`~/Downloads/gemini-icons`（Gemini 网页里提取的 Luminous Symbols / Google Symbols）。
> 所有路径都按**描边中线**从零重画，没有复用参考文件里的字形轮廓。
> 预览页：`docs/icons/preview.html`（图标库 + 22 个可点的动效原型）。动效规格见 `YOIN_ICON_MOTION.md`。

---

## 1. 从参考里量到的东西

参考 SVG 是字体轮廓（960 单位 = 24 dp，y 轴向上）。逐个量出来的关键数字：

| 项目 | 参考（Luminous） | 说明 |
|---|---|---|
| 描边 | 60 单位 ≈ **1.5 dp**（opsz 24 · wght 300）；opsz 20 · wght 320 缩放到 24 后约 1.4 | 比 Material Symbols 默认的 2 dp 轻 |
| 端点 / 转角 | 全部圆头、圆角 | 没有平头和尖角 |
| 圆形 | 外沿直径 18.4 dp（`info`） | 比 Material 的 20 dp 小一圈，留白更多 |
| 方形容器 | **连续曲率**（`side_nav`、`image_create`、`quiz`）：17 dp 的方框里直边只剩约 3 dp，其余全是渐变的弯曲 | 这是 Luminous 和普通圆角矩形最大的区别 |
| “圆形”头像 | `account_circle` 其实是带一点点直边的超椭圆（n≈2.3） | 连圆都带一点方 |
| 小圆点 | 直径 1.5 dp，等于描边宽（`info`、`quiz`） | 点是“长度为零的描边” |
| V 形箭头 | 约 9 × 4.5 dp，45° 臂（`keyboard_arrow_down`） | 箭头小而轻 |
| 有机曲线 | `person` 的肩线是 S 形，`gemini_chat` 是开口的圆 | 允许一点手写感 |

一句话：**线更细、留白更大、容器用连续曲率、细节小而克制、允许有机曲线。**

---

## 2. Yoin 的规则

### 2.1 画板与关键线

```
24 × 24 dp 画板
├─ 安全边距 2 dp → 关键区 20 × 20
├─ 圆形：中线 r = 8.5（外沿 18.5）
├─ 方形容器：中线 16 × 16（x, y ∈ [4, 20]）
├─ 竖长方形：中线约 11–13 × 18
└─ 横长方形：中线约 16.5–18.5 × 12.5–14.5
```

### 2.2 线

```svg
<path d="…" fill="none" stroke="currentColor"
      stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
```

- 描边统一 **1.5**。需要整体更轻/更重时，只改 `stroke-width`（1.25 / 1.75 都成立，预览页可以切换）。
- 路径只用 `M L C Z` 绝对坐标。圆弧也转成三次贝塞尔，方便 VectorDrawable 和两态插值。

### 2.3 形状

| 形状 | 做法 | 常用参数 |
|---|---|---|
| 连续曲率容器 | `geom.squircle(x, y, w, h, r, s)`（Figma 的 corner smoothing 算法） | r 3.1–4.0，s = 0.6，转角影响长度 = 1.6 r |
| 圆角多边形 | `geom.rounded_poly(points, radii)`，每个角独立半径 | 三角形顶点 0.85–1.0；房顶 2.3 |
| 圆角折线 | `geom.rounded_polyline` | 开口形状（托盘、把手） |
| 圆 / 椭圆 / 弧 | `geom.circle` / `ellipse` / `arc`（角度制，0° = 右，90° = 下） | — |

### 2.4 细节

- **信息点**：r 0.95（比描边宽略大一点，光学上才和线一样重）；“更多”三点 r 1.4。
- **V 形箭头**：9 × 4.5，所有方向是同一条路径旋转，保证一致。
- **交叉**：两条线交叉时，下层那条在交叉点两侧各断开 **1.05**（中线到中线 3.6）。用 `knock.cut(path, knock.around(over, 1.8))` 生成，结果仍是真正的贝塞尔曲线（`shuffle`、`visibility_off`、`cloud_offline`、`devices`）。
- **实心态**：同一条中线“填充 + 同宽描边”，外沿与线框版完全重合；需要镂空时用 `evenodd`（`error_filled`、`library_filled`）。
- **填充的播放控制**（`play_filled` / `pause_filled` / `skip_*_filled`）用直边多边形，角由 0.75 的圆角连接自然形成（与参考的 play_arrow 同样紧）；这样播放 ↔ 暂停可以逐点插值。

### 2.5 颜色

- SVG：`currentColor`。
- Android：路径为黑色，由 Compose `Icon` 的 tint（`LocalContentColor`）上色。深浅色、封面取色、动态色都不需要额外资源。

---

## 3. 清单（91 个）

| 分组 | 图标 |
|---|---|
| 导航与通用 | home · home_filled · library · library_filled · search · settings · back · close · chevron_down/up/right/left · unfold_more · unfold_less · more_vertical · more_horizontal · add · check · drag_handle · edit · delete · share · refresh · launch · info · error · error_filled · visibility · visibility_off · code · send |
| 播放与音乐 | play_arrow · play_filled · pause · pause_filled · skip_next · skip_previous · skip_next_filled · skip_previous_filled · shuffle · repeat · repeat_one · playlist · queue · music_note · album · person · artist · favorite · favorite_filled · star · star_filled · lyrics · translate · recenter · equalizer · sparkle · sparkle_filled · play_circle |
| 设备与服务 | cast · cast_connected · smartphone · tablet · computer · tv · speaker · headphones · devices · car · gamepad · device_other · cloud · cloud_offline · storage · folder · download · download_done · volume_up · volume_mute |
| 内容与笔记 | note · edit_note · reviews · insights · explicit · sort · filter · sleep · speed · memory（提案）· library_add · library_added |

- 覆盖了 `app/src/main` 里现在用到的**全部** `Icons.*`（替换对照见预览页最后一节），以及第一版的 52 个文件名。
- 第一版的 `queue_music` 拆成了语义更清楚的 `queue`（播放队列 = 列表 + 播放三角）和 `playlist`（歌单 = 列表 + 音符）。
- 带动画部件的图标在 SVG 里有 `id` / 分组，VectorDrawable 里对应 `android:name`：
  - `translate`：`<g id="wen">`、`<g id="latin">`
  - `shuffle`：`strand-over` / `strand-under` / `head-over` / `head-under`
  - `cast` / `cast_connected`：`screen`、`wave-0..2`、`panel`
  - `download` / `download_done`：`tray`、`shaft`、`head`、`check`
  - `library_add` / `library_added`：`front`、`back`、`plus-v`、`plus-h`、`check`
  - `equalizer`：`bar-0..4`；`sparkle`：`sparkle-main`、`sparkle-mini`；`repeat_one`：`one`

---

## 4. 文件与构建

```
docs/icons/
├── YOIN_ICONS_STYLE.md      本文
├── YOIN_ICON_MOTION.md      动效规格与待定问题
├── build.py                 生成 svg/ + icons.json + res/drawable/ic_yoin_*.xml
├── make_preview.py          生成 preview.html
├── preview_template.html    预览页模板（样式 + spring 原型脚本）
├── preview.html             生成物：图标库 + 动效实验室
├── icons.json               生成物：元数据
├── src/                     源：geom.py · knock.py · registry.py · icons_*.py
└── svg/yoin_<name>.svg      生成物：可编辑 SVG
app/src/main/res/drawable/ic_yoin_<name>.xml   生成物：VectorDrawable
```

```bash
cd docs/icons
pip install shapely            # knock.py 需要
python3 build.py               # SVG + JSON + VectorDrawable
python3 make_preview.py preview_template.html .   # 预览页（同时生成 artifact.html，可删）
```

**不要手改生成物。** 改图标就改 `src/icons_*.py` 里对应的函数，再跑一次构建。

在 Compose 里使用：

```kotlin
Icon(
    painter = painterResource(R.drawable.ic_yoin_translate),
    contentDescription = "翻译歌词",
)
```

需要动画的图标（见 `YOIN_ICON_MOTION.md`）不走 `painterResource`，而是用 `rememberVectorPainter { Group(…) { Path(…) } }` 按部件驱动。

---

## 5. 新增图标检查清单

- [ ] 24 × 24，内容在 20 × 20 关键区内，光学居中
- [ ] 描边 1.5，round / round；只用 `M L C Z`
- [ ] 方形容器用 `squircle(…, s=0.6)`，不用普通圆角矩形
- [ ] 交叉处下层线条用 `knock.cut` 留缝，不叠画
- [ ] 小圆点 r 0.95；三点菜单 r 1.4
- [ ] 如果有第二状态（开/关、完成/未完成），两态用同一组部件、同样的点数，能逐点插值
- [ ] 需要动画的部件给 `id` 或 `group`
- [ ] 在预览页里切 1.25 / 1.5 / 1.75 描边、深浅色都看一遍
