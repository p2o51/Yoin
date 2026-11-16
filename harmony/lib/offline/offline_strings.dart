import 'package:flutter/widgets.dart';

/// Offline downloads' strings, English and Simplified Chinese, copied from
/// Android res/values{,-zh-rCN}/strings_offline.xml and strings_settings.xml
/// (key in the comment).
class OfflineStrings {
  const OfflineStrings._(this.zh);

  /// For tests and non-widget callers.
  const OfflineStrings.forLanguage({required bool chinese}) : zh = chinese;

  final bool zh;

  static OfflineStrings of(BuildContext context) =>
      OfflineStrings._(Localizations.maybeLocaleOf(context)?.languageCode == 'zh');

  // ── The Download row (album ▾, playlist ▾, a song's sheet) ─────────
  String get download => zh ? '下载' : 'Download'; // offline_download
  String downloading(int done, int total) =>
      zh ? '正在下载 $done / $total' : 'Downloading $done / $total'; // offline_downloading
  String get removeDownload => zh ? '移除下载' : 'Remove download'; // offline_remove_download
  String get retry => zh ? '重新下载' : 'Retry download'; // offline_retry
  String queued(int n) => zh ? '开始下载 $n 首歌' : 'Downloading $n songs'; // offline_queued
  String queuedWaitingWifi(int n) =>
      zh ? '$n 首歌会在连上 Wi‑Fi 后下载' : '$n songs will download on Wi‑Fi'; // offline_queued_waiting_wifi
  String get removed => zh ? '已移除下载' : 'Download removed'; // offline_removed

  // ── The progress notification (YoinDownloadService) ─────────────────
  String get notificationDownloading =>
      zh ? '正在下载，供离线收听' : 'Downloading for offline listening'; // offline_notification_downloading

  // ── Settings › account › Storage › Downloads ────────────────────────
  String get sectionStorage => zh ? '存储' : 'Storage'; // settings_section_storage
  String get title => zh ? '下载' : 'Downloads'; // settings_downloads_title
  String summaryEmpty(String quality) =>
      zh ? '$quality · 还没有下载' : '$quality · nothing downloaded yet'; // settings_downloads_summary_empty
  String summary(String quality, String size) =>
      zh ? '$quality · 本机已存 $size' : '$quality · $size on this device'; // settings_downloads_summary
  String get quality => zh ? '下载音质' : 'Download quality'; // settings_downloads_quality
  String get qualityOriginalDetail =>
      zh ? '服务器上的原文件' : 'The file as it is on your server'; // settings_downloads_quality_original_detail
  String get qualityNote => zh
      ? '只影响之后下载的歌，已下载的保持原来的音质。'
      : 'Applies to what you download next. Songs already downloaded keep their quality.'; // settings_downloads_quality_note
  String get mobileData => zh ? '用移动数据下载' : 'Download using mobile data'; // settings_downloads_mobile_data
  String get mobileDataDetail =>
      zh ? '关闭时，下载会等到连上 Wi‑Fi' : 'Off: downloads wait for Wi‑Fi'; // settings_downloads_mobile_data_detail
  String get storage => zh ? '存储' : 'Storage'; // settings_downloads_storage
  String get used => zh ? '已下载' : 'Downloads'; // settings_downloads_used
  String get removeAll => zh ? '删除全部下载' : 'Remove all downloads'; // settings_downloads_remove_all
  String get removeAllDetail =>
      zh ? '本机所有账号的下载' : "Every account's downloads on this device"; // settings_downloads_remove_all_detail
  String get removeAllBody => zh
      ? '会删掉本机所有账号下载的歌。之后可以重新下载。'
      : 'The songs downloaded for every account on this device will be deleted. You can download them again.'; // settings_downloads_remove_all_body
  String get removeAllConfirm => zh ? '删除' : 'Remove'; // settings_downloads_remove_all_confirm
  String get cancel => zh ? '取消' : 'Cancel'; // settings_downloads_cancel
  String get list => zh ? '已下载' : 'Downloaded'; // settings_downloads_list
  String get listEmpty => zh ? '还没有下载' : 'Nothing downloaded yet'; // settings_downloads_list_empty
  String entrySongs(int n, String size) => zh ? '$n 首 · $size' : '$n songs · $size'; // settings_downloads_entry_songs
  String entryProgress(int done, int total) =>
      zh ? '正在下载 $done / $total' : 'Downloading $done of $total'; // settings_downloads_entry_progress
  String get entryFailed => zh ? '有些歌没下载成功' : "Some songs couldn't download"; // settings_downloads_entry_failed
  String get entryRemove => zh ? '移除下载' : 'Remove download'; // settings_downloads_entry_remove

  // ── Sizes (SettingsScreen formatBytes) ───────────────────────────────
  /// settings_cache_empty / _bytes / _kb / _mb / _gb.
  String bytes(int bytes) {
    if (bytes <= 0) return zh ? '空' : 'Empty';
    if (bytes < 1024) return '$bytes B';
    if (bytes < 1048576) return '${(bytes / 1024).toStringAsFixed(1)} KB';
    if (bytes < 1073741824) return '${(bytes / 1048576).toStringAsFixed(1)} MB';
    return '${(bytes / 1073741824).toStringAsFixed(2)} GB';
  }
}
