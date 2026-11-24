# 云同步（Google Drive）

> 状态：0.6.3+ 四端互通规范（Android / iOS & macOS / HarmonyOS）。产品与本地存储边界以本文、`docs/platforms.md` 与 `docs/i18n/zh-glossary.md` 为准，`docs/design.md` 引用这里。

## 一句话

把笔记、评分、专辑评论、首页布局、少量设置、歌单备份以及**在线 AI** 生成的歌词翻译与关于/问答内容，存进用户**自己** Google Drive 里的隐藏应用文件夹（`appDataFolder`，权限只申请 `drive.appdata`）。默认关闭、可随时关；不登录 Yoin 照常完整可用，本地优先。

## 多端架构与存储形态（0.6.3+）

四端复用相同的同步数据契约与合并语义（Core 引擎已在 `:shared` 的 `CloudSyncCore` / `SyncEngine` 中跨平台对齐）：

| 平台 | 同步位置与鉴权方式 | 引擎实现 | 远端存储形态（同一套 `SyncContracts` 线协议） | 本地防克隆副本库（不入系统备份） |
| :--- | :--- | :--- | :--- | :--- |
| **Android** (`app/`) | Google Drive（Credential Manager + Play Services Drive Auth） | Kotlin 同步引擎 + Room 副本库 | `appDataFolder`：`yoin-state-<设备ID>.json.gz`、`yoin-artifacts-<设备ID>-<0..3>.json.gz`、`yoin-reset-*`、`yoin-purge-*`、`yoin-migrated-*` | `no_backup/yoin-sync.db` |
| **iOS & iPadOS / macOS** (`iosApp/` on `main`) | **iCloud（CloudKit 私有库，免登录）** 或 Google Drive（`GoogleSignIn`） | `:shared` `CloudSyncCore`（`SyncEngine`、`DriveAppDataClient`、`CloudKitSyncTransport`） | 同上线协议；iCloud 存入私有库自定义 Zone `YoinSyncZone` 的 `YoinSyncFile`（元数据字段 + `CKAsset` 存 `.json.gz`，绕开 1 MB 上限） | `Application Support/Yoin/sync/yoin-sync.json`；Keychain 存 OAuth 凭据（`Yoin.entitlements` 含 `iCloud.com.gpo.yoin` CloudKit 与 `keychain-access-groups`） |
| **HarmonyOS** (`harmony/`) | Google Drive（Loopback OAuth PKCE） | Dart 移植 `SyncEngine` + `CloudSyncPlugin.ets` | 同上共享 `SyncContracts` 线协议文件 | Ability `filesDir` 下的 `yoin-sync.json.gz` (`harmony/docs/cloud-sync.md`)；Asset Store Kit (`SecretStore`) 存凭据 |

> 注：Google Drive 各平台必须在**同一个** Google Cloud 项目下配置各自平台的 OAuth Client ID，否则不同平台或构建会看到彼此隔离的 `appDataFolder`。iCloud（CloudKit）仅支持 Apple 设备（iOS / iPadOS / macOS），与 Android / 鸿蒙互通请继续使用 Google Drive 或通过「导出 / 导入备份文件」迁移。

## 评估结论

整体方向（`appDataFolder` + 本地优先 + 字段白名单 + 墓碑 + 按 Profile 分区 + 凭据与密钥不出设备）在多端均成立，落到实现上的核心约束如下：

| 问题 | 结论 |
|---|---|
| Drive v3 **没有条件写**（无 ETag / If-Match），多设备写同一个文件必丢数据 | 每台设备写自己的状态文件（Android 按设备 ID 分 state/artifacts；Apple/HarmonyOS 通过各自 `SyncEngine` 读合并再写回），读他端快照后按版本号全序合并 |
| Profile ID 是每台设备随机生成的 UUID，两台设备上的同一个账号 ID 不同 | 用账号指纹（Subsonic 服务器+用户名、Spotify 用户 ID）派生同步 ID（`:shared` `AccountFingerprints` / `AccountBinder`），持久化绑定，只有显式操作才会改 |
| 删除是硬删除，数据表大多没有可靠时间戳 | 本地副本库对比出变更，删除生成墓碑，不在写入点埋点 |
| Android `gemini_config` 一行里同时放了 Gemini API Key 和翻译语言 | 云同步只同步语言字段；所有 AI / Search / 音源 API Key **永不同步到 Drive**（注意在 Android 本机系统备份中，`gemini_config` 位于主数据库内仍可能随 Android 自动备份同步；DeepSeek/千问/自定义 AI 与搜索密钥在 `noBackupFilesDir/ai_provider.json`，HarmonyOS 在 `SecretStore`，iOS 在 Keychain） |
| 改翻译语言会清空所有 About/Ask（含用户写的问题） | 同步过来的语言只在本机没有 About/Ask 记录时才生效，绝不清表 |
| 同步过来的 Spotify Client ID 会让本机已登录的 Spotify 刷新失败 | 只在本机没有自定义 ID、也没有 Spotify 账号时才采用 |
| NeoDB 实例地址同步过来会把本机 token 发到别的服务器 | 不同步 |

## 同步什么

| 数据 | 归属 | 合并方式 |
|---|---|---|
| 歌曲笔记 | 按音乐账号 | 删除会同步（墓碑）；批量消失会先暂停问你 |
| 专辑笔记（`album_note`，2026-11-23 笔记改版起；一张专辑可多条） | 按音乐账号 | 同歌曲笔记：按笔记 id，最后修改为准，删除会同步（墓碑），同时一边改一边删时留下改的那条；Android、iOS、HarmonyOS 三端都发布并应用（iOS 自笔记 phase 1b 起，2026-11-24，用 `:shared` 的 `AlbumNoteSyncAdapter`，字段、id、墓碑、时间戳与 Android 完全相同，Google Drive 和 iCloud 两种存储都一样；HarmonyOS 用自己的 Dart 适配器，存在 `album_notes.v1.<账号>`，字段与 Android 相同）；还没有适配器的旧版本把记录原样带着再发布（不写库、不丢墓碑）。iOS 更新前原样带着的记录留在本机同步副本里，更新后的第一次同步按 id 写进本机（每条一次、不重复，也不另发新版本，再发布时仍是原样）。旧备份里只在备份中的 `album_note` 记录同名同字段，导入时照普通同步类型合并，每条只写一次 |
| 单曲评分、专辑评分 | 按音乐账号 | 最后修改的为准（取消评分 = 0，也是一个值） |
| 专辑长评 | 按音乐账号 | 两台设备同时改：**两段都保留**，中间用「设备 · 日期」分隔 |
| 首页布局 | 按音乐账号 | 最后修改的为准；「恢复默认」会同步（本机删掉布局行 = 默认）。文档里两栏的并排记忆（可选的 `wide` 键，见 design.md「两栏的并排记忆」）随同一个字符串同步；不认识它的版本写入时会丢掉，新版本再按 `span` 推出来 |
| 翻译语言、Spotify Client ID、滚动边缘样式 | 全局 | 见上表的保护规则；注：`ScreenCorners` 仅限本机生效，不同步 |
| 在线 AI 歌词翻译（附生成时用的歌词快照） | 全局 | 只增不删；不会覆盖你手动修改的歌词 |
| Memories 的 AI 文案（`memory_copy`）和 AI 拟题（`memory_title`） | 按音乐账号 | 文案、标题分两类各自「最后修改为准」，新文案不会冲掉别处写的标题；不删 |
| 你改过的专辑标题 | 按音乐账号 | 最后修改为准，始终优先于 AI 标题；「恢复 AI 标题」会同步 |
| 歌曲「关于」/ 问一问回答（`song_about`，含你自己问的问题） | 全局 | 每条带生成时的语言，只写进同语言的设备；切换语言清表不会同步成删除，换到那个语言后自动补回 |
| 账号描述（服务类型、名称、Subsonic「用户名 @ 主机」、Apple Music 店面） | 全局 | 用来在新设备上提示「Drive 里有这些账号的数据」 |
| 歌单备份（歌名/艺人/专辑/时长/ISRC/原 ID、没能备份的条目和原因、恢复记录；见 `docs/playlist-transfer.md`） | 全局（`playlist_backup`，artifacts 分片） | 删除会同步（墓碑）；两台设备同时改，后改的为准；一边删、另一边同时恢复了它，**留着**（备份删了就没法重做） |

笔记和长评里的格式和提及（`[@名字](yoin:…)`，见 `docs/design.md` 专辑详情「笔记的文字格式」）就是文字的一部分，原样同步；两台设备同时改长评时拼接两段也不会弄坏格式。

**不同步**：密码、Token、API Key（音源 / NeoDB / 在线 AI / 外部搜索）、Apple Music 服务地址、当前激活的账号、头像、播放历史/活动记录（每台设备各自保留）、各种音频与封面缓存、`ScreenCorners`（每台设备屏幕物理圆角不同）、手打的歌词（v1 暂不；从搜索面板选的歌词会随 AI 译文的快照带过去，带「用户选择」标记）。

## 怎么存

```
Drive appDataFolder / CloudKit Private Database (iCloud.com.gpo.yoin / YoinSyncZone)
  yoin-state-<设备ID>.json.gz            每台设备一份：笔记/评分/布局/设置/账号描述 + 墓碑
  yoin-artifacts-<设备ID>-<0..3>.json.gz  每台设备四份分片：AI 翻译、About/Ask、歌单备份
  yoin-reset-<时间>-<设备ID>.json         「删除云端数据」留下的标记
  yoin-purge-<时间>-<设备ID>.json         「从云端移除这个账号」留下的标记（见下文）
  yoin-migrated-<时间>-<设备ID>.json      「切换同步位置」留在旧位置的迁移提示标记（含 targetLocation）
```

- 每台设备上传的是**完整副本**（含别的设备来的数据），所以任何一台设备丢了，数据都还在。
- 本机有一个独立的小数据库 `no_backup/yoin-sync.db` 记录副本和同步状态。放在不参与 Android 自动备份的目录里，备份恢复不会克隆出两台「同一设备」；主数据库 `yoin-database` 不改表结构、不升版本。
- 版本号 = (修改时间, 设备ID)，全序比较；同一条记录基于同一版本的并发修改会被识别出来。

## 账号怎么对上

- Subsonic：服务器地址（忽略 http/https、默认端口、末尾斜杠、大小写）+ 用户名 → 指纹 → 同步 ID。两台设备连同一个服务器，自动对上。
- Spotify：Spotify 用户 ID（用本机还没过期的 token 调一次 `/v1/me`，不额外刷新）。
- Apple Music：拿不到稳定的账号标识，**不自动配对**；在云同步页里点「开始同步」或「关联到 Drive 里的 Apple Music 账号」。
- 同一台设备上重复添加了同一个账号：只同步最早那个，其余标「与 X 是同一账号，未同步」。
- 账号内重新登录成了另一个账号：暂停该账号的同步，让你选「继续按原来的同步」或「作为新账号重新开始」。
- 删除本机账号不会删云端数据，也不会影响其他设备；重新添加同一个账号，数据自动回来。
- 新设备不会凭空生成账号卡片；Drive 里有、本机没有的账号列在云同步页「Drive 里有，本机没有」，点「添加」走正常的添加账号流程，加完自动对上。

## 安全边界（会写进界面）

- 存在你自己 Google Drive 的隐藏 Yoin 文件夹里，只有 Yoin 能打开，不出现在 Drive 文件列表里。
- 受你的 Google 账号保护，**不是端到端加密**，占用你的 Google 存储空间。
- 密码、登录凭据和 API Key 永远不离开设备；服务器地址和用户名会同步，方便重新连接。
- 可以在 Yoin 里删除，也可以在 Drive 设置 › 管理应用里删除。

## 什么时候同步

- 打开 App（回到前台，60 秒节流）
- 本地改动后约 8 秒（只在开启时监听相关表）
- App 退到后台且有未上传改动时
- 手动「立即同步」

v1 不用 WorkManager：离线改动不会丢，只是等下次回到前台再上传。

## 开关语义

- **在本设备关闭**：只停本机，保留本机数据、本机副本和云端数据；其他设备照常。
- **删除云端数据**（所有设备）：先确认当前 Google 账号就是同步用的那个 → 写重置标记 → 本机关闭 → 删除 Drive 里所有 Yoin 文件（中途断网下次打开 App 会接着删）。其他设备看到标记后自动关闭、不会再传回去。本机数据都还在。所有同步类型一样处理，专辑笔记（`album_note`）也在内：Drive 里的记录随文件删掉、各设备的副本清空，本机的专辑笔记留着，重新开启后作为新数据再上传（不是墓碑；HarmonyOS 有测试覆盖）。
  - 不撤销 Google 授权：撤销是按账号生效的，其他设备会因此读不到重置标记。想彻底断开，可在 Google 账号 › 第三方应用与服务里移除 Yoin。
- **被别的设备「移出同步」**：这台设备下次同步时发现只有自己的文件没了（别的设备还在），会自动重新上传；只有所有设备的文件都没了（比如在 Drive 设置里删了隐藏数据）才停下来问你。

## 从云端移除一个账号

「删除云端数据」是全部删掉；只想让**一个**音乐账号的数据离开 Drive（换了服务、Memories 已经转移到新账号、旧服务器不用了），用「从云端移除」（`CloudSyncController.removeAccountFromCloud`）。入口会放在云同步页的账号行和「Drive 里有，本机没有」（界面待做，数据层已就绪）。

- 写一个清除标记：`yoin-purge-<epoch>-<设备ID>.json`，`appProperties` 为 `yoinKind=purge`、`scope=<syncProfileId>`、`epoch`、`deviceId`；内容是 `ScopePurgeMarker`（`scope`、`epoch`、`at`、`deviceId`、`deviceName`）。和重置标记一样先确认当前 Google 账号就是同步用的那个。
- epoch 不早于本机副本里这个账号的任何版本，也比这个账号以前的清除标记新。
- **每台看到标记的设备**（发起的那台立刻，其他设备下次同步时、合并别的设备文件之前）：
  - 从本机副本删掉这个账号的全部记录和它的账号描述，记住 `purged_scope:<scope> = epoch`（`sync_meta`）。
  - 这个账号在本机的绑定变成「已从云端移除」（`sync_bindings.state = purged`，界面 `SyncAccountStatus.RemovedFromCloud`）：不再采集、不再写回、不再描述。之后才加进来的同一个账号也直接是这个状态，不会把数据传回去。
  - **本机数据都还在**，和「删除云端数据」一样，只是不再同步。
  - 自己上传的文件从此不再含这个账号，并在信封里写上 `"purges": {"<scope>": epoch}`，表示「我知道这次清除」。
- 合并别的设备文件时，这个账号的记录**只从声明知道这次清除的文件里收**，而且只收 epoch 之后的版本；其余（还没看到标记的设备、旧版本 Yoin 转发的旧副本，哪怕时钟跑在前面）一律丢掉。
- 想重新同步：在某台设备上点「重新开始同步」（`resumeAccountSync`），它重新加入这个账号，新版本的时间都排在 epoch 之后；其他设备仍是「已从云端移除」，要在各自设备上再点一次。
- 账号描述（全局记录，key = 这个账号的 scope）跟着它的账号走：重新同步后它的新版本同样排在 epoch 之后，别的设备才会收。
- 「删除云端数据」也会删掉清除标记；本机副本清空时清除记录一起清空，「已从云端移除」的绑定恢复正常（新 Drive、或重置之后没有可清除的东西了）。

**兼容性（线协议没有破坏）**：

- 旧版本只认 `state` / `artifacts` / `reset` 三种 `yoinKind`，其余文件直接跳过；信封按 `ignoreUnknownKeys` 解析，新增的 `purges` 字段被忽略；不带 `purges` 的信封照旧可读（字段可空、为空时不写，所以没有清除时文件字节与以前完全一样，golden 测试不变）。
- 代价：旧版本不懂清除，会继续把这个账号的旧数据留在自己的文件里传回去。新版本不收这些记录，但 Drive 里旧版本的文件要等那台设备更新后才干净。旧版本「删除云端数据」不会删清除标记（只删它认识的三种），留着无害。
- `:shared`（iOS）同步了契约（`ScopePurgeMarker`、`SyncFileProps.KIND_PURGE`/`SCOPE`、信封 `purges`）、编解码和引擎/绑定/`CloudSyncCore` 的「应用标记」与 `resumeAccountSync`，所以 iOS 也会停止回传；iOS 还不能发起移除，界面也还没显示「已从云端移除」。HarmonyOS 的 Dart 引擎同样移植了「应用标记」（引擎、绑定、信封 `purges`、发布时排在 epoch 之后）和 `resumeAccountSync`，所以也会停止回传；自 parity phase 2（2026-11-24）起也能发起移除（`CloudSyncManager.removeAccountFromCloud`，epoch 规则、标记文件名、`appProperties` 和内容与 Android 相同）：云同步页的账号行和「Drive 里有，本机没有」的行点开是这个账号的云端数据页（条数、笔记 / 专辑评分与乐评 / 单曲评分 / Memories 标题，可搜索），⋮ 里有「查看云端数据」「从云端移除…」，确认框用 Android 的文案（`harmony/docs/cloud-sync.md`）。
- **跨端金样**：`:shared` 的 `SyncPurgeGoldenTest`（androidHostTest）写出并校验 `shared/src/commonTest/resources/golden/sync-purge.json`：标记的编码与文件名、解码（含 kotlinx 把 `"epoch":"5"` 这样的数字字符串读成 Long）、合并时哪些记录被拒收、`applyScopePurge` 逐次调用的结果、恢复同步后版本时间的抬升和信封里的 `purges`。HarmonyOS 的 Dart 引擎用 `harmony/test/data/sync/sync_purge_golden_test.dart` 对照它；改动只能从 Kotlin 一侧重新生成（`YOIN_WRITE_SYNC_GOLDEN=1 ./gradlew :shared:testAndroidHostTest --tests '*SyncPurgeGoldenTest*'`）。

## 导出与导入

设置 › 同步与备份将可以把**全部**同步数据导出成一个文件（界面待做，数据层已就绪），放到任何地方（SAF 选位置）；不登录 Google、没有 Play 服务也能用。数据层：`data/sync/backup/`（入口 `MemoriesPortability`）。

**代码放在哪**（2026-11-24 起，parity p2）：规则只有一份，在 `:shared` 的 `com.gpo.yoin.shared.portability`（commonMain），Android 和 iOS 用同一份，HarmonyOS 按 golden 向量移植：

- `:shared`：`BackupFormat` 和 manifest 模型、`BackupExport`（manifest / 快照 / readable 的字节）、`BackupParser`（校验格式、版本、SHA-256、同步 schema）、`ReadableExport`（日期按时区 `BackupDates`）、`SnapshotAssembler`（副本叠加本机数据）、`ImportPlanner` + `BackupMerger`（导入计划与合并规则）、`memories/`（`MemoryItems.fromRecords`、`TransferRules`、`MigrationIds`、`MemoryTransferApplier`）。它们只通过几个小接口读写数据：`BackupReplica`（同步副本）、`BackupDomain`（歌曲 → 专辑关联、笔记归属）、`MemoryTransferStore`（转移写目标账号）。
- Android 只留平台部分：zip 读写（`BackupWriter` / `BackupReader`）、Room 和同步引擎的桥（`SharedBackupBridge.kt`、`RoomTransferStore`）、`MemoryItems.fromProfile`（读 Room 和播放记录）、按曲库搜索匹配（`MemoryTransfer.plan`，用 app 的 `Track` / `Album` / `TrackMatcher`）、SAF 和界面。
- iOS 用 Swift 门面 `YoinBackup`（`shared/.../portability/YoinBackup.kt`，只有平铺的 data class 和 enum）：导出得到文件内容（zip 由 Swift 写）、读文件并校验、生成并执行导入计划、列出转移来源、生成转移计划（列出要匹配的歌和专辑、沿用账本里之前的匹配）、按匹配结果复制。iOS 侧的端口实现也在 commonMain（`StoreBackupReplica`、`SyncDomainTransferStore`、`SyncStoreTransferLedger`），Swift 只需实现 `YoinTrackAlbumLinkStore`。
- **iOS 界面**（parity p2 step B，2026-11-24）：设置 › 云同步里和 Android 一样的「备份与迁移」组（同步开：下载全部云端数据 / 从备份文件导入 / 转移 Memories；同步关：「备份文件」组，下载全部数据 / 从备份文件导入），每一步的页面、文案（Android 的 values/ 与 values-zh-rCN/ 原文，`Localizable.xcstrings` 里按 Android 资源名作 key）和 Android 的 `BackupScreens` / `TransferScreens` 对应。导出先生成 zip，再交给系统的「存储到文件」；取消就什么也不导出。zip 由 `Data/Sync/BackupZip.swift` 写和读：条目顺序、名字与 Android `BackupWriter` 相同（manifest.json、data/snapshot.json、readable/…，DEFLATE，UTF-8 文件名），读的时候走中央目录，所以 Android 带数据描述符的 zip 也能读；读不了 → 「不是 Yoin 备份文件」，条目超限或损坏 → 「无法读取」，其余错误类型来自 `YoinBackup.readBackup`。导入、导出、转移期间持有同步锁（`CloudSyncCore.beginExclusive` / `endExclusive`，相当于 Android 的 `withReplica`），写完把副本落盘；导入或复制写了东西且同步开着时马上同步一次。导入的全局数据（设置、翻译、关于、歌单备份）和 Android 一样总是导入，审核页只显示条数，不提供开关。旧的「备份并导出」（把云端文件原样打包）已去掉；同步核心自己的自动备份（`BackupFolder`，首次拉取、删除、换位置前）不变。
- 互通：`backup-export-ios.zip`（golden 目录）是 iOS 写出的 en-UTC golden 导出，`YoinTests` 的 `BackupPortabilityTests` 生成并核对它，Android 的 `IosBackupZipInteropTest` 用 `BackupReader` 读它（条目逐字相同、顺序相同、校验通过）。
- 字节不变：`BackupExportPinTest`（Android）用迁移前的代码生成并钉住了固定数据导出的每个 zip 条目，迁移后逐字节相同。
- Golden 向量（`shared/src/commonTest/resources/golden/`，`PortabilityGoldenTest` 生成并校验）：`backup-export.json`（快照 → manifest / 快照 / readable 文件，三种语言和时区）、`backup-import.json`（备份 + 设备状态 + 选择 → 计划、结果、导入后的数据）、`memories-transfer.json`（id 空间、`mig-` id、提及改写、乐评拼接，以及转移的完整用例）。

文件名 `yoin-backup-YYYY-MM-DD.zip`，里面：

```
manifest.json          格式与版本、导出时间、来源（google-drive / icloud / this-device）、设备 ID 与名称、
                       App 版本、每个账号的描述（服务、名称、「用户名 @ 主机」、店面；不含任何凭据）、
                       每个账号每类的条数、data/snapshot.json 的 SHA-256 / 字节数 / 条数 / 墓碑数
data/snapshot.json     一个 SyncEnvelope（fileClass = "backup"），records 就是 Drive 状态文件里的
                       WireRecord：版本、上一版本、墓碑、scope（syncProfileId）原样保留
readable/<账号>/<专辑>.md         给人看的：专辑 → 歌曲（评分、带时间与播放位置的笔记）、乐评、专辑笔记、标题
readable/<账号>/_未归入专辑的歌曲.md  还不知道专辑的歌曲
readable/global/歌单备份.md        歌单备份列表（含曲目）
```

- **内容 = 本机此刻同步会上传的东西**：本机副本里的全部记录（所有账号，包括「Drive 里有，本机没有」的、墓碑、本版本不认识的类型），再叠上本机数据库的现状——和副本一致的行保留原版本；同步后改过的行按采集规则生成本机的新版本；同步过、现在被删掉的笔记 / 专辑标题写成墓碑。并发的乐评不在导出时合并，下次同步照常处理。
- **没开过云同步**也能导出：直接用各适配器的投影；账号的 scope 和绑定时会得到的一样（Subsonic 指纹、本机已知的 Spotify 用户），算不出来的（Apple Music、没解析过的 Spotify）用 `local-<本机 profile id>`。同一账号在本机有两个 profile 时，后一个也用 `local-…`，不混在一起。
- **墓碑保留**：快照是无损的，墓碑能防止导回同步副本时把删掉的笔记复活；导入到本机数据时墓碑什么也不删。
- 额外带一类只在备份里的记录：`track_album_link`（歌曲 → 专辑，key = `provider␟trackId`）。它不同步，导入时只写本机数据库，**永远不进同步副本和 Drive**。专辑笔记（`album_note`）自笔记改版起是普通同步类型，和歌曲笔记一样从同步适配器进备份；旧版备份里作为只在备份中的 `album_note` 记录也照样能导入（同一形状，走同步合并，按 id 去重）。迁移 Memories 时，笔记、专辑笔记和长评里指向被重新匹配的歌曲 / 专辑的提及（`[@名字](yoin:…)`）改指目标账号的 id（`NoteMarkup.rewriteMentions`），名字和其余文字不动。
- 白名单和同步完全一样：所有值都来自适配器投影、副本里已经过白名单的 payload，或账号描述的那几个字段。密码、Token、API Key 永不进入文件（有哨兵测试）。
- readable/ 只给人看，导入时不读。名字只用本机已有的信息（Yoin 自己的播放记录、笔记里的歌名、专辑笔记里的专辑名），不联网；不知道名字的写 id。

**导入**：

1. 读文件，校验 `format = yoin-backup`、`formatVersion`（比本机新就提示更新）、快照 SHA-256、快照的同步 schema。
2. 生成导入计划（`ImportPlan`）：每个账号对到本机 profile——同步绑定或指纹相同即自动对上；`local-<id>` 在原设备上对回原 profile；对不上的列出同服务的 profile 让你选，或者「只放在云端」（有同步副本且是真正的同步 scope 时可选：并入副本，下次同步上传，之后可以从它转移 Memories；这个账号已「从云端移除」时不提供——`wasPurged`，并入也会被逐条拒绝——改为导进本机账号，或先重新开始同步），或者先不导（文件里的数据仍可直接作为转移来源）。
3. 按同步引擎的规则合并进本机数据库，之后由同步引擎当作本机改动上传：
   - 最后修改的为准（记录版本时间 vs 行自己的 updatedAt），相同的值不动；本机同步副本里已有更新的版本（比如删除）时以副本为准。
   - 专辑长评两段都保留：本机的在前，备份的跟在「— <导出设备> · 日期 —」下面；本机已经包含那段文字就不再加。
   - 同一账号的笔记 id 不变；导进另一个账号（scope 不同），或某个 id 在本机属于别的 profile 时，用和转移一样的 `mig-` id 另存一份。
   - 墓碑一律不执行：导入只会增加，不会删除。
   - 某类数据在本机读不出来时，这一类整个跳过，不写（结果里列出这些类）。
4. **幂等**：同一个文件导两次，第二次什么都不改。

## 转移 Memories

把一个账号的 Memories **复制**（不是移动）到本机另一个 profile。来源可以是本机 profile、只在云端的账号（直接读本机同步副本，不用重新登录），或者一个备份文件里的账号。数据层：`data/transfer/memories/`（`MemoryTransfer`，入口 `MemoriesPortability.planTransfer / runTransfer`）；复制什么、写到哪、冲突怎么处理在 `:shared`（`MemoryTransferApplier`、`TransferRules`），按曲库搜索匹配留在各平台（Android 的 `MemoryTransfer.plan`；iOS 拿 `YoinBackup.planTransfer` 给出的待匹配列表，用 `:shared` 的 `TrackMatcher` 自己搜）。

复制的内容：歌曲笔记、单曲评分、专辑评分与长评、专辑笔记、你改过的专辑标题（`ALBUM_TITLE`）、AI 拟题（`MEMORY_TITLE`）与 AI 文案（`MEMORY_COPY`）、歌曲 → 专辑关联（`track_album_links`）。

**id 怎么对上**：

- 两边在同一个 id 空间里就原样复制 key：同一服务，并且是 Spotify、Apple Music 曲库目录 id（纯数字；`library:…` 和裸的 `i.` / `l.` 资料库 id 不算），或同一台 Subsonic 服务器（按「主机[:端口]」比较）。歌曲和专辑各自判断：目录里的歌挂在资料库专辑 id 上，专辑那一侧仍要匹配。
- 否则（换 Subsonic 服务器、跨服务、Apple Music 的个人资料库 id `library:i.…` / `library:l.…`）生成匹配计划（`TransferPlan`）：
  - 歌曲：先 ISRC，再标题 + 艺人 + 时长（`TrackMatcher`，和歌单迁移同一套规则）；本地信息不够或搜索没定下来时，才向来源服务要一次 ISRC。
  - 专辑：先看这张专辑里已匹配的歌在目标上大多属于哪张专辑；没有再按专辑名 + 艺人搜索。
  - 结果分三档：已匹配 / 不确定（最多 3 个候选，不点选就不复制）/ 没找到。UI 可以 `pickTrack` / `pickAlbum` 指定。实际搜索由 UI 层提供的 `MemoryCatalog` 完成。
- 笔记（歌曲与专辑）用新的确定性 id：`mig-` + sha256(来源 scope ␟ 原 id ␟ 目标 scope) 的前 16 位十六进制（␟ = U+001F）。不会和原笔记冲突，同一份复制在任何设备上 id 都一样。

**目标里已经有东西时**：笔记两份都留；评分、标题以后改的为准；长评两段都留，来源的那段放在「— 来自 <来源账号名>」下面；目标自己的 AI 拟题优先。专辑行写入时 `ratingNeedsSync = false`、`reviewNeedsSync = false`，不会推到 NeoDB；单曲评分也不推到目标服务。

**重跑**：每对（来源 scope，目标 profile）有一本转移账本，存在同步数据库的 `sync_meta` 里（`transfer:<hash>:<条目>`，不改任何表结构）：已复制的条目跳过并报告跳过了多少；之前匹配上的和你点选过的直接沿用；之前没找到的重新找。

**iOS**（parity p2 step B）：来源是本机 profile 或只在云端的账号（和 Android 界面一样，备份文件里的账号虽然门面支持，界面还没提供）。按曲库搜索匹配在 `Data/Sync/Portability.swift` 的 `TransferMatching`（Android `MemoryTransfer.plan` 的移植：ISRC、`TrackMatcher` 标题 + 艺人 + 时长、只按标题、最后才向来源服务要 ISRC；专辑先按已匹配的歌投票，再用 `TrackMatcher.albumQuery` 搜索、`sameVersion` 区分版本），目标曲库在 `MemoryCatalogs.swift`（Subsonic `search3`、Apple Music 目录搜索与 ISRC、Spotify 搜索与 `isrc:`），审核结果变成 `TrackPick` / `AlbumPick` 交给 `YoinBackup.applyTransfer`。`YoinTransferPlan.directSongs` 给出 id 可以直接沿用、仍有东西要复制的歌数（摘要的「直接复制」和审核页的提示用它）。iOS 没有 Android 的「云端数据统计」，所以账号行不显示条数，清理确认框用「全部数据」。

iOS 的歌曲 → 专辑关联存在 `TrackAlbumLinkStore`（`Application Support/Yoin/track-album-links.json`，按 profile、服务、歌曲 id），和 Android 一样不同步；目前只有导入备份和转移会写它（Android 还会从播放、专辑页和查询学到），导出时原样带上。

歌曲 → 专辑关联：除了 `track_album_links` 里的，有笔记或评分、但只有播放记录知道专辑的歌也会带上一条（来源 `play`）；匹配到的歌按目标上那首歌的专辑补一条。

复制写进的是目标 profile 的本机数据，同步引擎把它们当成目标账号自己的改动上传。

## 必须由你在 Google Cloud 完成的配置

Android 客户端不需要在代码里写 Client ID，但 Google 要能认出这个 App：

1. 在一个 Google Cloud 项目里启用 **Google Drive API**。
2. OAuth 同意屏幕：用户类型「外部」，填应用名、支持邮箱、首页和隐私政策链接；只申请 `drive.appdata`（非敏感权限，不需要 Google 审核）。
   - 停在「测试」状态时只有测试用户能用，而且**授权每 7 天过期**；要正常用需要「发布到正式版」。
3. 创建 **Android** 类型的 OAuth 客户端，包名 `com.gpo.yoin`，每个签名证书一个：
   - debug（也用于 releaseDebugSigned）：`D0:66:57:67:4D:EE:71:CB:BB:5D:7E:1E:21:30:A2:AD:50:93:C9:03`
   - 上传密钥（侧载的 release 包）：`59:62:39:67:68:32:F7:26:D8:69:B4:A6:E9:2B:3F:9B:EC:1E:C1:37`
   - Play 应用签名密钥：在 Play Console › 应用完整性里查
4. 所有客户端必须在**同一个**项目里，否则不同构建看到的是不同的 appDataFolder，互相看不到。
5. （可选，推荐）在同一个项目里再建一个 **Web 应用** 类型的 OAuth 客户端（不用填重定向 URI），它的 Client ID 已写进 `app/build.gradle.kts` 作为默认值（Client ID 是公开的，不是密钥），用 Gradle 属性 `googleWebClientId` 可以覆盖，传空值即关闭。
   - 有它时，「Turn on with Google」先弹 Credential Manager 的 Sign in with Google 底部账号面板选账号，再只为这个账号申请 `drive.appdata`，不再出现 AuthorizationClient 那个老式的账号选择框。
   - 没有它（或面板在这台设备上用不了）时，自动退回原来的 AuthorizationClient 账号选择框，行为和以前一样。

没配好时，云同步页会显示「这个版本的 Yoin 还不能使用云同步」并列出包名和证书指纹，不会自己反复重试；配好之后点「Try again」即可。

## 后续（不在 v1）

- WorkManager 后台补传
- 手打歌词单独同步（缓存规则已改为用户选的行不按天数过期、整体 10 MiB LRU，见 `data/lyrics/LyricsCachePolicy.kt`，前提已具备）
- 播放历史（按月分块文件）
- 自动清理长期不活跃设备的文件
- iOS 发起「从云端移除」与对应界面（已应用清除标记，见「从云端移除一个账号」；HarmonyOS 自 parity phase 2 起已能发起）
- 转移后在本机删掉旧账号卡片的入口（All Memories / 搜索会合并所有本机账号，不删会看到两份）
