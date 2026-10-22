# Spotoolfy 导出格式 v1

Yoin 读的是 Spotoolfy（`p2o51/spotoolfy_flutter`，1.3.x）「设置 › 数据管理 › 导出数据」生成的 JSON。格式没有版本号，下文称 v1。迁移方案见 `docs/spotoolfy-migration.md`，读取代码在 `app/src/main/java/com/gpo/yoin/data/migration/spotoolfy/`。

## 文件

- 文件名 `spotoolfy_backup_<ISO 8601，':' 换成 '-'>.json`。
- 经系统分享菜单发出，MIME `application/json`（`local_database_provider.dart` 的 `exportDataToJson`）。
- UTF-8，两格缩进。开头可能带 BOM，Yoin 会去掉。
- Yoin 只收 32 MB 以内的文件。

## 顶层

一个对象，四个键都必须在、都必须是数组；少一个或类型不对就不是 Spotoolfy 文件。其余键忽略。

| 键 | 内容 | Yoin 是否读 |
|---|---|---|
| `tracks` | 有记录的歌曲的元数据 | 读 |
| `records` | 笔记和评分 | 读 |
| `translations` | Gemini 歌词翻译缓存 | 不读（D8） |
| `play_contexts` | 最近播放的专辑、歌单 | 不读 |

## `tracks[]`

| 字段 | 类型 | 说明 |
|---|---|---|
| `trackId` | string | Spotify 曲目 id，22 位字母数字；内置样例行是 `spotify:track:sample1` 这种形式 |
| `trackName` | string | |
| `artistName` | string | 多位艺人用 `", "` 连接 |
| `albumName` | string | 只有名字，没有专辑 id |
| `albumCoverUrl` | string 或 null | Spotify CDN 地址 |
| `lastRecordedAt` | int、string 或 null | 毫秒，Yoin 不用 |
| `latestPlayedAt` | int 或 null | 毫秒，Yoin 不用 |

## `records[]`

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | int | 每台设备自增，不同设备会撞号，Yoin 不用 |
| `trackId` | string | 同 `tracks[].trackId`，偶尔带 `spotify:track:` 前缀 |
| `noteContent` | string 或 null | 笔记正文；只打分的记录为 null 或空白 |
| `rating` | int、string 或 null | 0 👎、3 😐、5 🔥。旧版写过字符串，按 3 处理；其它数值不导入 |
| `songTimestampMs` | int 或 null | 写下时的播放位置；0 或负数视为没有 |
| `recordedAt` | int、string 或浮点 | 毫秒；缺了这一行就读不了 |
| `contextUri` / `contextName` | string 或 null | 播放上下文，不可靠，不导入 |
| `lyricsSnapshot` | string 或 null | 最多三行歌词。歌词带时间轴时是去掉时间标签的三行；不带时是 LRC 原文的前三行，可能含 `[ti:…]` 这类标签 |

## 宽容规则

与 Spotoolfy 自己的导入器一致或更宽：

- 不是对象的行、缺 `trackId` 或 `recordedAt` 的行跳过，只计数，不打印内容。
- 时间可以是数字、数字字符串或带小数的数字，小数截掉。
- `rating` 是整数值的浮点（`5.0`）按整数读；`2.5`、布尔值等读成 null。
- 一首歌多条评分，取 `recordedAt` 最大的一条；时间相同时取文件里靠后的一条。

## 样本

`app/src/test/resources/spotoolfy/export_v1.json` 覆盖了：内置样例行、带前缀的 id、字符串评分、字符串和浮点时间、带 LRC 标签的歌词片段、空白笔记、缺时间的行、非对象行、未知顶层键。5 万条记录和超过 32 MB 的情况在 `SpotoolfyExportParserTest` 里现场生成。
