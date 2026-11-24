import 'package:flutter/widgets.dart';

/// Export all data, Import from backup and Transfer Memories: their UI
/// strings, English and Simplified Chinese, copied from Android
/// res/values{,-zh-rCN}/strings_settings.xml (key in the comment; plurals
/// give their "one" / "other" forms). Cloud sync's own strings stay in
/// `cloud_sync_strings.dart`.
class PortabilityStrings {
  const PortabilityStrings._(this._zh);

  /// For tests and non-widget callers.
  const PortabilityStrings.forLanguage({required bool chinese}) : _zh = chinese;

  final bool _zh;

  bool get isChinese => _zh;

  static PortabilityStrings of(BuildContext context) =>
      PortabilityStrings._(Localizations.maybeLocaleOf(context)?.languageCode == 'zh');

  // ── Cloud sync page: the backup group ───────────────────────────────
  String get sectionBackup => _zh ? '备份与迁移' : 'Backup and migration'; // settings_sync_section_backup
  String get sectionBackupFile => _zh ? '备份文件' : 'Backup file'; // settings_sync_section_backup_file
  String get exportTitle => _zh ? '下载全部云端数据' : 'Download all cloud data'; // settings_sync_export_title
  String get exportTitleLocal => _zh ? '下载全部数据' : 'Download all data'; // settings_sync_export_title_local
  String get exportSummary => _zh
      ? '导出为单个 ZIP，含可还原快照与可读 Markdown'
      : 'Export a single ZIP with a restorable snapshot and readable Markdown'; // settings_sync_export_summary
  String get importTitle => _zh ? '从备份文件导入' : 'Import from backup'; // settings_sync_import_title
  String get importSummary => _zh
      ? '从其他云服务迁移，或在无 Google 服务的设备上还原'
      : 'Migrate from another cloud service, or restore without Google services'; // settings_sync_import_summary
  String get transferTitle => _zh ? '转移 Memories' : 'Transfer Memories'; // settings_xfer_title
  String get transferSummary => _zh
      ? '将笔记、评分与乐评复制到另一个账号，原数据保留'
      : 'Copy notes, ratings, and reviews to another account without changing the original'; // settings_xfer_summary

  // ── Shared ───────────────────────────────────────────────────────────
  String get done => _zh ? '完成' : 'Done'; // settings_sync_done
  String get busy => _zh ? '正在进行，请稍候' : 'Working on it…'; // settings_sync_busy
  String get googleDrive => 'Google Drive'; // settings_sync_google_drive
  String get icloud => 'iCloud'; // settings_sync_icloud
  String get spotify => 'Spotify'; // settings_intro_spotify_name
  String get cloudOnly => _zh ? '仅在云端' : 'Cloud only'; // settings_sync_cloud_only
  String get noData => _zh ? '暂无数据' : 'No data yet'; // settings_sync_no_data
  String get unknownSong => _zh ? '未知歌曲' : 'Unknown song'; // settings_sync_unknown_song
  String get unitKb => 'KB'; // settings_sync_unit_kb
  String get unitMb => 'MB'; // settings_sync_unit_mb
  String get listSeparator => _zh ? '、' : ', '; // settings_sync_list_separator
  String listLast(String head, String last) => _zh ? '$head和$last' : '$head and $last'; // settings_sync_list_last
  String removeFailed(String name) => _zh
      ? '无法从云端移除 $name，请检查网络后重试。'
      : "Couldn't remove $name from the cloud. Check your connection and try again."; // settings_sync_remove_failed

  /// "a, b and c" in the UI language (Android joinedList).
  String joined(List<String> parts) {
    if (parts.isEmpty) return '';
    if (parts.length == 1) return parts.single;
    return listLast(parts.sublist(0, parts.length - 1).join(listSeparator), parts.last);
  }

  // Units after a count (plurals settings_sync_unit_*).
  String unitAccounts(int n) => _zh ? '个账号' : (n == 1 ? 'account' : 'accounts'); // settings_sync_unit_accounts
  String unitItems(int n) => _zh ? '条数据' : (n == 1 ? 'item' : 'items'); // settings_sync_unit_items
  String unitBackups(int n) =>
      _zh ? '份歌单备份' : (n == 1 ? 'playlist backup' : 'playlist backups'); // settings_sync_unit_backups
  String unitTranslations(int n) =>
      _zh ? '条歌词翻译' : (n == 1 ? 'lyric translation' : 'lyric translations'); // settings_sync_unit_translations
  String unitAbout(int n) =>
      _zh ? '条歌曲关于' : (n == 1 ? 'song About entry' : 'song About entries'); // settings_sync_unit_about
  String unitCount(int n) => _zh ? '条' : (n == 1 ? 'item' : 'items'); // settings_sync_unit_count
  String unitSongs(int n) => _zh ? '首' : (n == 1 ? 'song' : 'songs'); // settings_sync_unit_songs
  String unitTitleCount(int n) => _zh ? '个' : (n == 1 ? 'title' : 'titles'); // settings_sync_unit_title_count

  // Kinds as the person knows them.
  String get kindNotes => _zh ? '笔记' : 'Notes'; // settings_sync_kind_notes
  String get kindRatings => _zh ? '评分' : 'Ratings'; // settings_sync_kind_ratings
  String get kindReviews => _zh ? '乐评' : 'Reviews'; // settings_sync_kind_reviews
  String get kindTitles => _zh ? 'Memories 标题' : 'Memories titles'; // settings_sync_kind_titles
  String get kindOther => _zh ? '其他数据' : 'Other data'; // settings_sync_kind_other

  // ── Export ───────────────────────────────────────────────────────────
  String get exportRunning => _zh ? '正在打包' : 'Creating backup'; // settings_sync_export_running
  String get exportRunningSummary => _zh
      ? '正在整理云端数据并生成 ZIP 备份。'
      : 'Gathering your cloud data into a ZIP backup.'; // settings_sync_export_running_summary
  String get exportRunningSummaryLocal => _zh
      ? '正在整理此设备上的数据并生成 ZIP 备份。'
      : 'Gathering the data on this device into a ZIP backup.'; // settings_sync_export_running_summary_local
  String get exportDoneBar => _zh ? '已导出' : 'Exported'; // settings_sync_export_done_bar
  String get exportSaved => _zh ? '已保存' : 'Saved'; // settings_sync_export_saved
  String get exportContents => _zh ? '备份内容' : 'Backup contents'; // settings_sync_export_contents
  String get exportManifest => _zh
      ? '导出时间、账号摘要与 SHA-256 校验'
      : 'Export time, account summary, and SHA-256 checksum'; // settings_sync_export_manifest
  String get exportData => _zh ? '可无损还原的数据快照' : 'Lossless snapshot for restoring'; // settings_sync_export_data
  String get exportReadable =>
      _zh ? '按账号与专辑整理的 Markdown' : 'Markdown sorted by account and album'; // settings_sync_export_readable
  String get exportAbout => _zh ? '说明' : 'About this file'; // settings_sync_export_about
  String get exportLossless => _zh ? '可无损还原' : 'Lossless restore'; // settings_sync_export_lossless
  String get exportLosslessSummary => _zh
      ? '通过「从备份文件导入」合并，不会产生重复'
      : 'Merge it back with Import from backup, without duplicates'; // settings_sync_export_lossless_summary
  String get exportOffline => _zh ? '脱机可读' : 'Readable offline'; // settings_sync_export_offline
  String get exportOfflineSummary => _zh
      ? '每张专辑对应一份 Markdown，含评分、乐评与笔记'
      : 'One Markdown file per album, with ratings, reviews, and notes'; // settings_sync_export_offline_summary
  String get exportPrivate => _zh ? '不含隐私凭据' : 'No credentials included'; // settings_sync_export_private
  String get exportPrivateSummary => _zh
      ? '密码、登录凭据、API 密钥、播放记录与音频缓存均不导出'
      : 'Passwords, credentials, API keys, play history, and audio cache are never exported'; // settings_sync_export_private_summary
  String exportReadFailures(String kinds) => _zh
      ? '部分数据无法从此设备读取，已改用上次同步的副本：$kinds'
      : "Some data couldn't be read on this device, so its last synced copy was used: $kinds"; // settings_sync_export_read_failures
  String get exportFailed => _zh ? '无法生成备份' : "Couldn't create the backup"; // settings_sync_export_failed
  String get exportFailedSummary => _zh
      ? '没有保存完整的文件。请检查保存位置后重试。'
      : 'Nothing complete was saved. Check the storage location and try again.'; // settings_sync_export_failed_summary

  // ── Import ───────────────────────────────────────────────────────────
  String get importReading => _zh ? '正在校验备份' : 'Verifying backup'; // settings_sync_import_reading
  String get importReview => _zh ? '核对导入内容' : 'Review import'; // settings_sync_import_review
  String get importInfo => _zh ? '备份信息' : 'Backup info'; // settings_sync_import_info
  String get importExported => _zh ? '导出时间' : 'Exported'; // settings_sync_import_exported
  String get importThisDevice => _zh ? '此设备' : 'This device'; // settings_sync_import_this_device
  String get importThisDeviceInline => _zh ? '此设备' : 'this device'; // settings_sync_import_this_device_inline
  String get importVerified => _zh ? '校验通过' : 'Verified'; // settings_sync_import_verified
  String get importVerifiedSummary =>
      _zh ? 'SHA-256 一致，文件完整' : 'SHA-256 matches, file intact'; // settings_sync_import_verified_summary
  String importMergeInto(String where) => _zh ? '合并到 $where' : 'Merge into $where'; // settings_sync_import_merge_into
  String get importSameAccount =>
      _zh ? '合并到此设备上的同一账号' : 'Merges into the same account on this device'; // settings_sync_import_same_account
  String get importCloudOnlyNote => _zh
      ? '此设备未登录该账号，数据将保留在云端，登录后自动关联'
      : 'Not signed in on this device: the data stays in the cloud and links up when you sign in'; // settings_sync_import_cloud_only_note
  String get importChoose => _zh ? '选择导入目标' : 'Import destination'; // settings_sync_import_choose
  String get importKeepCloud => _zh ? '仅留在云端' : 'Keep in the cloud only'; // settings_sync_import_keep_cloud
  String get importSkip => _zh ? '跳过' : 'Skip'; // settings_sync_import_skip
  String get importPurgedNote => _zh
      ? '此账号已从云端移除，只能导入到此设备上的账号，或跳过。'
      : 'This account was removed from the cloud. Import it into an account on this device, or skip it.'; // settings_sync_import_purged_note
  String importIntoProfile(String name) => _zh ? '导入到 $name' : 'Into $name'; // settings_sync_import_into_profile
  String get importGlobal => _zh ? '全局数据' : 'Global data'; // settings_sync_import_global
  String get importRules => _zh ? '合并规则' : 'Merge rules'; // settings_sync_import_rules
  String get importRuleAdd => _zh ? '只增不删' : 'Add only'; // settings_sync_import_rule_add
  String get importRuleAddSummary =>
      _zh ? '备份中缺失的笔记不会被删除' : "Notes missing from the backup aren't deleted"; // settings_sync_import_rule_add_summary
  String get importRuleRating => _zh ? '评分取较新值' : 'Latest rating wins'; // settings_sync_import_rule_rating
  String get importRuleRatingSummary =>
      _zh ? '取消评分也会按修改时间覆盖' : 'Clearing a rating counts as a change too'; // settings_sync_import_rule_rating_summary
  String get importRuleReview => _zh ? '乐评双向保留' : 'Keep both reviews'; // settings_sync_import_rule_review
  String get importRuleReviewSummary => _zh
      ? '两边内容不同时合并保留，并标注设备与日期'
      : 'When the two differ, both are kept, marked with device and date'; // settings_sync_import_rule_review_summary
  String get importCancel => _zh ? '取消' : 'Cancel'; // settings_sync_import_cancel
  String get importConfirm => _zh ? '合并导入' : 'Merge and import'; // settings_sync_import_confirm
  String get importRunning => _zh ? '正在导入' : 'Importing'; // settings_sync_import_running
  String get importDone => _zh ? '已导入' : 'Imported'; // settings_sync_import_done
  String importDoneSummary(String where) => _zh
      ? '数据已合并到 $where，下次同步时将推送到其他设备。'
      : 'Merged into $where. Other devices get it with the next sync.'; // settings_sync_import_done_summary
  String get importDoneSummaryLocal =>
      _zh ? '数据已合并到此设备。' : 'Merged into this device.'; // settings_sync_import_done_summary_local
  String get importWritten => _zh ? '已导入' : 'Imported'; // settings_sync_import_written
  String get importSkipped => _zh ? '已跳过' : 'Skipped'; // settings_sync_import_skipped
  String get importCloud => _zh ? '留在云端' : 'Kept in cloud'; // settings_sync_import_cloud
  String importFailedKinds(String kinds) =>
      _zh ? '以下数据未能写入：$kinds' : "Couldn't write: $kinds"; // settings_sync_import_failed_kinds
  String importRefused(String names) => _zh
      ? '以下账号已从云端移除，未保留在云端：$names'
      : 'Not kept in the cloud (removed from the cloud): $names'; // settings_sync_import_refused
  String get importFailed => _zh ? '导入没有完成' : "The import didn't finish"; // settings_sync_import_failed
  String get importFailedSummary =>
      _zh ? '已有数据不受影响，请稍后再试。' : 'Nothing was lost. Try again in a moment.'; // settings_sync_import_failed_summary
  String get readNotBackup =>
      _zh ? '这不是 Yoin 备份文件。' : "This isn't a Yoin backup file."; // settings_sync_read_not_backup
  String get readTooNew => _zh
      ? '此备份由较新版本的 Yoin 生成，请先更新 Yoin。'
      : 'This backup was made by a newer Yoin. Update Yoin to import it.'; // settings_sync_read_too_new
  String get readChecksum => _zh
      ? '备份已损坏或被修改（SHA-256 不一致）。'
      : "The backup is damaged or was changed (SHA-256 doesn't match)."; // settings_sync_read_checksum
  String get readFailed => _zh ? '无法读取此备份文件。' : "Couldn't read this backup file."; // settings_sync_read_failed

  // ── Transfer Memories ────────────────────────────────────────────────
  String step(int index, int count, String name) =>
      _zh ? '第 $index 步，共 $count 步：$name' : 'Step $index of $count: $name'; // settings_xfer_step
  String get stepSource => _zh ? '选择来源' : 'Source'; // settings_xfer_step_source
  String get stepTarget => _zh ? '选择目标' : 'Target'; // settings_xfer_step_target
  String get stepSummary => _zh ? '确认内容' : 'Review summary'; // settings_xfer_step_summary
  String get stepReview => _zh ? '核对曲目' : 'Match tracks'; // settings_xfer_step_review
  String get stepCopy => _zh ? '复制' : 'Copy'; // settings_xfer_step_copy
  String get headSource => _zh ? '选择来源账号' : 'Choose source account'; // settings_xfer_head_source
  String get headTarget => _zh ? '选择目标账号' : 'Choose target account'; // settings_xfer_head_target
  String get sourceGroup => _zh ? '来源账号' : 'Source account'; // settings_xfer_source_group
  String get targetGroup => _zh ? '目标账号' : 'Target account'; // settings_xfer_target_group
  String from(String name) => _zh ? '从 $name' : 'From $name'; // settings_xfer_from
  String get change => _zh ? '更改' : 'Change'; // settings_xfer_change
  String get addAccount => _zh ? '添加账号…' : 'Add account…'; // settings_xfer_add_account
  String get addAccountSummary => _zh
      ? '目标账号需先登录在此设备上'
      : 'The target account must be signed in on this device'; // settings_xfer_add_account_summary
  String cloudNote(String name) => _zh
      ? '$name 仅在云端，无需重新登录，Yoin 会直接读取云端副本。'
      : '$name is only in the cloud. No need to sign in again: Yoin reads the cloud copy directly.'; // settings_xfer_cloud_note
  String get badgeDirect => _zh ? '直接复制' : 'Direct copy'; // settings_xfer_badge_direct
  String get badgePartial => _zh ? '部分需匹配' : 'Partial match'; // settings_xfer_badge_partial
  String get badgeMatch => _zh ? '需匹配曲目' : 'Needs matching'; // settings_xfer_badge_match
  String get next => _zh ? '下一步' : 'Next'; // settings_xfer_next
  String get noTargets =>
      _zh ? '此设备上没有可复制到的其他账号。' : 'No other account on this device to copy into.'; // settings_xfer_no_targets
  String get summaryTitle => _zh ? '确认转移内容' : 'Review what will be copied'; // settings_xfer_summary_title
  String skipTitle(int n) =>
      _zh ? '已跳过 $n 条已转移内容' : 'Skipping $n items already transferred'; // settings_xfer_skip_title
  String get skipSome => _zh
      ? '仅复制新增或此前未匹配的内容。'
      : "Only new items, or ones that weren't matched before, are copied."; // settings_xfer_skip_some
  String get skipAll => _zh ? '没有新增内容需要复制。' : 'Nothing new to copy.'; // settings_xfer_skip_all
  String get what => _zh ? '将复制的内容' : 'What will be copied'; // settings_xfer_what
  String total(int n) => _zh ? '共 $n 条' : '$n in total'; // settings_xfer_total
  String get rowNotes => _zh ? '笔记' : 'Notes'; // settings_xfer_row_notes
  String get rowSongRatings => _zh ? '单曲评分' : 'Song ratings'; // settings_xfer_row_song_ratings
  String get rowAlbums => _zh ? '专辑评分与乐评' : 'Album ratings & reviews'; // settings_xfer_row_albums
  String get rowCustomTitles => _zh ? '自定义 Memories 标题' : 'Custom Memories titles'; // settings_xfer_row_custom_titles
  String get rowAiTitles => _zh ? 'AI Memories 标题与文案' : 'AI Memories titles and blurbs'; // settings_xfer_row_ai_titles
  String get rowLinks => _zh ? '曲目与专辑归属' : 'Track and album links'; // settings_xfer_row_links
  String get rowLinksSummary =>
      _zh ? '保留笔记所属的专辑分组' : 'Notes stay grouped under their albums'; // settings_xfer_row_links_summary
  String get matching => _zh ? '匹配方式' : 'Matching'; // settings_xfer_matching
  String get methodDirect => _zh ? '直接复制' : 'Direct copy'; // settings_xfer_method_direct
  String methodDirectSummary(String service) => _zh
      ? '$service 的曲目 ID 跨账号通用，无需匹配。'
      : '$service track IDs work across accounts, so nothing needs matching.'; // settings_xfer_method_direct_summary
  String get methodPartial => _zh ? '大部分直接复制，其余需匹配' : 'Mostly direct, the rest matched'; // settings_xfer_method_partial
  String get methodPartialSummary => _zh
      ? '目录曲目直接复制；仅限原资料库的私有曲目将按歌名与艺人重新匹配。'
      : 'Catalog tracks copy directly; tracks private to the original library are matched again by title and artist.'; // settings_xfer_method_partial_summary
  String get directSongs => _zh ? '首直接复制' : 'direct'; // settings_xfer_direct_songs
  String get matchSongs => _zh ? '首需匹配' : 'to match'; // settings_xfer_match_songs
  String get methodMatch => _zh ? '先匹配曲目' : 'Match tracks first'; // settings_xfer_method_match
  String methodMatchSummary(String name) => _zh
      ? '优先按 ISRC 匹配，其次按歌名、艺人与时长；待确认项由你核对，未匹配项保留在 $name。'
      : 'Matched by ISRC first, then by title, artist, and duration. You check the unsure ones; tracks not found stay in $name.'; // settings_xfer_method_match_summary
  String get duplicates => _zh ? '重复处理' : 'Duplicates'; // settings_xfer_duplicates
  String get dupNotes => _zh ? '笔记双向保留' : 'Keep all notes'; // settings_xfer_dup_notes
  String get dupNotesSummary =>
      _zh ? '同一首歌的笔记按时间合并展示' : 'Notes on the same song are shown together by time'; // settings_xfer_dup_notes_summary
  String get dupRatings => _zh ? '评分取较新值' : 'Latest rating wins'; // settings_xfer_dup_ratings
  String get dupReviews => _zh ? '乐评合并保留' : 'Keep both reviews'; // settings_xfer_dup_reviews
  String dupReviewsSummary(String name) =>
      _zh ? '以「— 来自 $name」分隔' : 'Separated by “— from $name”'; // settings_xfer_dup_reviews_summary
  String copyOnly(String name) =>
      _zh ? '仅复制不移动，$name 的原有数据保持不变。' : 'Copy only, never move: $name keeps all its data.'; // settings_xfer_copy_only
  String get startCopy => _zh ? '开始复制' : 'Start copying'; // settings_xfer_start_copy
  String get startMatch => _zh ? '开始匹配' : 'Start matching'; // settings_xfer_start_match
  String get nothingNew => _zh ? '暂无新增内容' : 'Nothing new'; // settings_xfer_nothing_new
  String progress(int done, int total) => '$done / $total'; // settings_xfer_progress
  String matchingOn(String service) => _zh ? '正在匹配 $service 曲库' : 'Matching on $service'; // settings_xfer_matching_on
  String get cancel => _zh ? '取消' : 'Cancel'; // settings_xfer_cancel
  String reviewLead(String source, int n, String service) => _zh
      ? '已为 $source 的 $n 首曲目在 $service 中查找对应版本。'
      : 'Looked up $n tracks of $source on $service.'; // settings_xfer_review_lead
  String reviewPartialLead(int direct, int n) => _zh
      ? '$direct 首目录曲目将直接复制，以下 $n 首私有曲目请核对匹配结果。'
      : '$direct catalog tracks copy directly. Check the matches for these $n private tracks.'; // settings_xfer_review_partial_lead
  String get stays => _zh ? '保留原账号' : 'Stays in source'; // settings_xfer_stays
  String get chooseMatch => _zh ? '选择匹配项' : 'Choose a match'; // settings_xfer_choose_match
  String albumsNote(int all, int matched, int other, String name) => _zh
      ? '专辑评分与乐评：共 $all 张，已匹配 $matched 张，其余 $other 张保留在 $name。'
      : 'Album ratings & reviews: $all albums, $matched matched; the other $other stay in $name.'; // settings_xfer_albums_note
  String leftNote(String name) => _zh
      ? '未找到或未勾选的条目将保留在 $name，日后可随时重试。'
      : 'Tracks not found or not checked stay in $name. You can transfer them again later.'; // settings_xfer_left_note
  String get cdCopy => _zh ? '复制此曲目的数据' : "Copy this track's data"; // settings_xfer_cd_copy
  String copying(String name) => _zh ? '正在复制到 $name' : 'Copying to $name'; // settings_xfer_copying
  String get doneBar => _zh ? '已复制' : 'Copied'; // settings_xfer_done_bar
  String doneTitle(String name) => _zh ? '已复制到 $name' : 'Copied to $name'; // settings_xfer_done_title
  String doneKept(String name) => _zh ? '$name 的原有数据已保留。' : '$name keeps all its data.'; // settings_xfer_done_kept
  String get tileCopied => _zh ? '已复制' : 'Copied'; // settings_xfer_tile_copied
  String get tileSkipped => _zh ? '已跳过' : 'Skipped'; // settings_xfer_tile_skipped
  String get tileLeft => _zh ? '保留原账号' : 'Left in source'; // settings_xfer_tile_left
  String get removeCard =>
      _zh ? '完成后移除此设备上的原账号卡片' : 'Remove original account card from this device'; // settings_xfer_remove_card
  String get removeCardSummary => _zh
      ? '避免「全部 Memories」与搜索出现重复内容，云端数据仍会保留。'
      : 'Avoids duplicates in All Memories and Search; cloud data stays intact.'; // settings_xfer_remove_card_summary
  String get clean => _zh ? '清理原账号云端数据' : 'Remove original cloud data'; // settings_xfer_clean
  String cleanSummary(String target, String source) => _zh
      ? '确认 $target 正常后，可从云端删除 $source 的副本'
      : "Once $target looks right, you can delete $source's copy from the cloud"; // settings_xfer_clean_summary
  String cleanTitle(String name) => _zh ? '清理 $name 的云端数据？' : "Remove $name's cloud data?"; // settings_xfer_clean_title
  String cleanBody(String source, String what, String target) => _zh
      ? '将从云端删除 $source 的 $what，已复制到 $target 的内容不受影响。'
      : "This deletes $what of $source from the cloud. What was copied to $target isn't affected."; // settings_xfer_clean_body
  String get cleanLater => _zh ? '稍后再说' : 'Not now'; // settings_xfer_clean_later
  String get cleanNow => _zh ? '立即清理' : 'Remove now'; // settings_xfer_clean_now
  String cleaned(String name) =>
      _zh ? '已从云端删除 $name 的数据' : "Deleted $name's data from the cloud"; // settings_xfer_cleaned
  String get neodbNote => _zh
      ? '复制的评分不会重复推送至 NeoDB，再次运行会自动跳过已复制项。'
      : "Copied ratings aren't pushed to NeoDB again, and running it again skips what was already copied."; // settings_xfer_neodb_note
  String cardRemoved(String name) => _zh
      ? '已移除 $name 的账号卡片，云端数据已保留'
      : "Removed $name's account card. Its cloud data is kept."; // settings_xfer_card_removed
  String finished(String name) =>
      _zh ? '复制完成，$name 的原有数据已保留' : 'Copy complete. $name keeps all its data.'; // settings_xfer_finished
  String get cancelTitle => _zh ? '取消转移？' : 'Cancel the transfer?'; // settings_xfer_cancel_title
  String get cancelBody => _zh
      ? '尚未写入任何数据，两个账号均不会改变。'
      : 'Nothing has been written yet; neither account changes.'; // settings_xfer_cancel_body
  String get cancelKeep => _zh ? '继续' : 'Keep going'; // settings_xfer_cancel_keep
  String get cancelConfirm => _zh ? '取消转移' : 'Cancel transfer'; // settings_xfer_cancel_confirm
  String xferReadFailed(String name) =>
      _zh ? '无法读取 $name 的数据。' : "Couldn't read $name's data."; // settings_xfer_read_failed
  String targetUnreachable(String name) =>
      _zh ? '无法连接 $name，请稍后再试。' : "Couldn't reach $name. Try again later."; // settings_xfer_target_unreachable
  String get runFailed =>
      _zh ? '复制没有完成，请稍后再试。' : "The copy didn't finish. Try again later."; // settings_xfer_run_failed

  // Review filters, shared with playlist restore (strings_playlist_backup.xml).
  String get filterAll => _zh ? '全部' : 'All'; // playlist_restore_all
  String get filterToCheck => _zh ? '待确认' : 'To check'; // playlist_restore_to_check
  String get filterNotFound => _zh ? '未找到' : 'Not found'; // playlist_restore_not_found
}
