/// Playlist backups' copy (Android res/values{,-zh-rCN}/strings_playlist_backup.xml
/// and strings_detail's detail_playlist_backup_*), English and Simplified
/// Chinese, each with its Android key.
library;

import 'package:flutter/material.dart';

import '../../data/backup/playlist_backup_models.dart';

class PlaylistBackupStrings {
  const PlaylistBackupStrings._(this._zh);

  /// For tests and non-widget callers.
  const PlaylistBackupStrings.forLanguage({required bool chinese}) : _zh = chinese;

  final bool _zh;

  bool get isChinese => _zh;

  static PlaylistBackupStrings of(BuildContext context) =>
      PlaylistBackupStrings._(Localizations.maybeLocaleOf(context)?.languageCode == 'zh');

  String get title => _zh ? '歌单云备份' : 'Playlist cloud backup'; // settings_playlist_backups_title
  String get empty => _zh ? '没有备份' : 'No backups'; // playlist_backups_empty

  /// playlist_backup_songs
  String songs(int count) => _zh ? '$count 首' : (count == 1 ? '$count song' : '$count songs');

  /// playlist_backup_unexported
  String unexported(int count) => _zh ? '$count 首未能备份' : "$count couldn't be backed up";

  String get restore => _zh ? '恢复' : 'Restore'; // playlist_backup_restore
  String get delete => _zh ? '删除' : 'Delete'; // playlist_backup_delete
  String get cdMore => _zh ? '更多选项' : 'More options'; // playlist_backup_cd_more
  String get deleteTitle => _zh ? '删除这个备份？' : 'Delete this backup?'; // playlist_backup_delete_title
  String get deleteBody =>
      _zh ? '会从本机和同步的设备上删除。' : 'Removed here and on your synced devices.'; // playlist_backup_delete_body
  String get cancel => _zh ? '取消' : 'Cancel'; // playlist_backup_cancel
  String get sectionSongs => _zh ? '歌曲' : 'Songs'; // playlist_backup_section_songs
  String get sectionUnexported => _zh ? '未能备份' : "Couldn't be backed up"; // playlist_backup_section_unexported
  String get sectionRestores => _zh ? '恢复记录' : 'Restores'; // playlist_backup_section_restores
  String get sectionVersions => _zh ? '版本' : 'Versions'; // playlist_backup_section_versions
  String get versionCurrent => _zh ? '当前' : 'Current'; // playlist_backup_version_current
  String get untitled => _zh ? '无标题' : 'Untitled'; // playlist_backup_untitled

  /// playlist_backup_reason_*
  String reason(UnexportedReason reason) => switch (reason) {
    UnexportedReason.localFile => _zh ? '本地文件' : 'Local file',
    UnexportedReason.episode => _zh ? '播客单集' : 'Podcast episode',
    UnexportedReason.video => _zh ? '音乐视频' : 'Music video',
    UnexportedReason.unavailable => _zh ? '不可用' : 'Unavailable',
  };

  String restoreAdded(int count) => _zh ? '已添加 $count 首' : '$count added'; // playlist_backup_restore_added
  String restoreSkipped(int count) => _zh ? '跳过 $count 首' : '$count skipped'; // playlist_backup_restore_skipped
  String restoreNotFoundCount(int count) =>
      _zh ? '未找到 $count 首' : '$count not found'; // playlist_backup_restore_not_found
  String get restoreFailedRecord => _zh ? '未能创建' : 'Not created'; // playlist_backup_restore_failed

  String get following => _zh ? '跟随变化中' : 'Following changes'; // playlist_backup_following
  String get sourceGone => _zh ? '原歌单已不在' : 'Original playlist gone'; // playlist_backup_source_gone
  String get notHere => _zh ? '在其他设备上跟随' : 'Followed on another device'; // playlist_backup_not_here
  String get created => _zh ? '歌单创建' : 'Playlist created'; // playlist_backup_created
  String get since => _zh ? '开始备份' : 'Backed up since'; // playlist_backup_since
  String get last => _zh ? '最后备份' : 'Last backup'; // playlist_backup_last
  String get changed => _zh ? '最近变化' : 'Last change'; // playlist_backup_changed
  String get backUpNow => _zh ? '立即备份' : 'Back up now'; // playlist_backup_now

  // The restore sheet.
  String get restoreTitle => _zh ? '恢复到' : 'Restore to'; // playlist_restore_title
  String get noTargets => _zh ? '没有账号' : 'No accounts'; // playlist_restore_no_targets
  String matching(String target) => _zh ? '正在 $target 上匹配' : 'Matching on $target'; // playlist_restore_matching
  String progress(int done, int total) => '$done / $total'; // playlist_restore_progress
  String get matched => _zh ? '已匹配' : 'Matched'; // playlist_restore_matched
  String get toCheck => _zh ? '待确认' : 'To check'; // playlist_restore_to_check
  String get added => _zh ? '已添加' : 'Added'; // playlist_restore_added
  String get skipped => _zh ? '已跳过' : 'Skipped'; // playlist_restore_skipped
  String get notFound => _zh ? '未找到' : 'Not found'; // playlist_restore_not_found
  String get create => _zh ? '创建歌单' : 'Create playlist'; // playlist_restore_create
  String get writingShort => _zh ? '正在创建' : 'Creating'; // playlist_restore_writing_short
  String writing(String target) => _zh ? '正在 $target 上创建' : 'Creating on $target'; // playlist_restore_writing

  /// The restore's progress notification while it matches (playlist_restore_live_matching).
  String liveMatching(String target) => _zh ? '正在 $target 匹配' : 'Matching on $target';
  String done(String target) => _zh ? '已在 $target 上创建' : 'Created on $target'; // playlist_restore_done
  String notCreated(String target) =>
      _zh ? '未能在 $target 上创建' : 'Not created on $target'; // playlist_restore_not_created
  String failed(String target) => _zh ? '无法连接 $target' : "Couldn't reach $target"; // playlist_restore_failed
  String get doneButton => _zh ? '完成' : 'Done'; // playlist_restore_done_button
  String search(String service) => _zh ? '在 $service 搜索' : 'Search $service'; // playlist_restore_search
  String get searchHint => _zh ? '搜索' : 'Search'; // playlist_restore_search_hint
  String get searchNone => _zh ? '没有结果' : 'No results'; // playlist_restore_search_none
  String get picked => _zh ? '已选' : 'Picked'; // playlist_restore_picked
  String get all => _zh ? '全部' : 'All'; // playlist_restore_all
  String get cdCheck => _zh ? '加入歌单' : 'Add to playlist'; // playlist_restore_cd_check

  // The playlist page (strings_detail).
  String get detailMenu => _zh ? '云备份' : 'Cloud backup'; // detail_playlist_backup_menu

  /// detail_playlist_backup_done
  String detailDone(int count) =>
      _zh ? '已备份 $count 首' : (count == 1 ? 'Backed up $count song' : 'Backed up $count songs');

  /// detail_playlist_backup_partial
  String detailPartial(int count, int total) => _zh
      ? '已备份 $count / $total 首'
      : (total == 1 ? 'Backed up $count of $total song' : 'Backed up $count of $total songs');

  String get detailFailed => _zh ? '歌单备份失败' : "Couldn't back up the playlist"; // detail_playlist_backup_failed
  String get detailView => _zh ? '查看' : 'View'; // detail_playlist_backup_view
}

/// A backup or restore time (Android formatBackupTime): "Oct 19, 2:05 PM" this
/// year, "Oct 19, 2025, 2:05 PM" before, in the device's locale and clock.
String formatBackupTime(BuildContext context, int thenMs, int nowMs) {
  final material = MaterialLocalizations.of(context);
  final then = DateTime.fromMillisecondsSinceEpoch(thenMs);
  final now = DateTime.fromMillisecondsSinceEpoch(nowMs);
  final date = then.year == now.year ? material.formatShortMonthDay(then) : material.formatShortDate(then);
  final time = material.formatTimeOfDay(
    TimeOfDay.fromDateTime(then),
    alwaysUse24HourFormat: MediaQuery.maybeAlwaysUse24HourFormatOf(context) ?? false,
  );
  final zh = Localizations.maybeLocaleOf(context)?.languageCode == 'zh';
  return zh ? '$date $time' : '$date, $time';
}

/// A service's ISO-8601 date ("2024-03-01T10:00:00Z", or a bare date) as epoch millis; null when unreadable.
int? parseServiceDate(String? text) {
  final value = text?.trim();
  if (value == null || value.isEmpty) return null;
  final parsed = DateTime.tryParse(value) ?? (value.length >= 10 ? DateTime.tryParse(value.substring(0, 10)) : null);
  if (parsed == null) return null;
  // An instant (a zone given) as it is; anything else is its date at midnight UTC, as Android reads it.
  if (parsed.isUtc) return parsed.millisecondsSinceEpoch;
  return DateTime.utc(parsed.year, parsed.month, parsed.day).millisecondsSinceEpoch;
}

/// Songs added and removed going from [older] to [newer], by their source ids.
(int added, int removed) versionDiff(List<BackupTrack> older, List<BackupTrack> newer) {
  final before = {for (final t in older) t.sourceTrackId};
  final after = {for (final t in newer) t.sourceTrackId};
  return (after.difference(before).length, before.difference(after).length);
}
