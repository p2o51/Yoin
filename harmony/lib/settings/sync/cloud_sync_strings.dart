import 'package:flutter/widgets.dart';

/// Cloud sync's UI strings, English and Simplified Chinese, copied from
/// Android res/values{,-zh-rCN}/strings_settings.xml (key in the comment).
class CloudSyncStrings {
  const CloudSyncStrings._(this._zh);

  /// For tests and non-widget callers.
  const CloudSyncStrings.forLanguage({required bool chinese}) : _zh = chinese;

  final bool _zh;

  bool get isChinese => _zh;

  static CloudSyncStrings of(BuildContext context) =>
      CloudSyncStrings._(Localizations.maybeLocaleOf(context)?.languageCode == 'zh');

  String get sectionSyncStorage => _zh ? '同步与存储' : 'Sync and storage'; // settings_section_sync_storage
  String get title => _zh ? '云同步' : 'Cloud sync'; // settings_sync_title
  String get lead => _zh ? '同步笔记、评分与翻译' : 'Sync notes, ratings and translations'; // settings_sync_lead
  String resetOnDevice(String device) => _zh
      ? '已在 $device 上关闭云同步并删除 Drive 数据。再次开启将从此设备重新开始。'
      : 'Cloud sync was turned off and its Drive data deleted on $device. Turn it on again to start fresh from this device.'; // settings_sync_reset_on_device
  String get resetOtherDevice => _zh
      ? '已在另一台设备上关闭云同步并删除 Drive 数据。再次开启将从此设备重新开始。'
      : 'Cloud sync was turned off and its Drive data deleted on another device. Turn it on again to start fresh from this device.'; // settings_sync_reset_other_device
  String get sectionWhatYouGet => _zh ? '同步内容' : 'What you get'; // settings_sync_section_what_you_get
  String get benefitNotes => _zh ? '笔记、评分与评论' : 'Notes, ratings and reviews'; // settings_sync_benefit_notes
  String get benefitTranslations => _zh ? '歌词翻译' : 'Lyric translations'; // settings_sync_benefit_translations
  String get benefitSettings => _zh ? '首页布局与设置' : 'Home layout and settings'; // settings_sync_benefit_settings
  String get benefitOffline => _zh ? '支持离线记录' : 'Works offline'; // settings_sync_benefit_offline
  String get sectionGoodToKnow => _zh ? '说明' : 'Good to know'; // settings_sync_section_good_to_know
  String get knowDrive => _zh
      ? '保存在你 Google Drive 的隐藏 Yoin 文件夹中。由 Google 账号保护，不是端到端加密，并计入你的网盘存储空间。'
      : "Saved in a hidden Yoin folder in your own Google Drive. It's protected by your Google account, not end-to-end encrypted, and counts toward your storage."; // settings_sync_know_drive
  String get knowSecrets => _zh
      ? '仅同步服务器地址与用户名；密码、登录状态与 API 密钥始终保存在本地。'
      : 'Only server addresses and usernames sync; passwords, sign-ins and API keys stay on this device.'; // settings_sync_know_secrets
  String get knowHistory =>
      _zh ? '播放记录仅保存在本地。' : 'Listening history stays on each device.'; // settings_sync_know_history
  String get unavailable =>
      _zh ? '当前版本的 Yoin 不支持云同步。' : "Cloud sync isn't available in this build of Yoin."; // settings_sync_unavailable
  String get waitingRetry => _zh ? '正在等待 Google…' : 'Waiting for Google…'; // settings_sync_waiting_retry
  String get tryAgain => _zh ? '重试' : 'Try again'; // settings_sync_try_again
  String get waitingTurnOn => _zh ? '正在等待 Google…' : 'Waiting for Google…'; // settings_sync_waiting_turn_on
  String get turnOn => _zh ? '通过 Google 开启' : 'Turn on with Google'; // settings_sync_turn_on
  String get certUnknown => _zh ? '未知' : 'unknown'; // settings_sync_cert_unknown
  String get cdCopyBuild => _zh ? '复制构建详情' : 'Copy build details'; // settings_cd_copy_build
  String clipBody(String packageName, String sha1) => 'Package: $packageName\nSHA-1: $sha1'; // settings_sync_clip_body
  String get copied => _zh ? '已复制' : 'Copied'; // settings_sync_copied
  String get details => _zh ? '详情' : 'Details'; // settings_sync_details
  String get unrecognized => _zh ? 'Google 未识别此版本' : "Google didn't recognize this build"; // settings_sync_unrecognized
  String get reauthBody => _zh ? '已退出 Google 账号' : 'Signed out of Google'; // settings_sync_reauth_body
  String get reconnect => _zh ? '登录' : 'Sign in'; // settings_sync_reconnect
  String get copyRemovedBody => _zh ? 'Drive 副本已移除' : 'Drive copy removed'; // settings_sync_copy_removed_body
  String get uploadAgain => _zh ? '重新上传' : 'Upload again'; // settings_sync_upload_again
  String get turnOffShort => _zh ? '关闭' : 'Turn off'; // settings_sync_turn_off_short
  String get googleAccount => _zh ? 'Google 账号' : 'Google account'; // settings_sync_google_account
  String get sectionAccounts => _zh ? '账号' : 'Accounts'; // settings_sync_section_accounts
  String get sectionCloudOnly =>
      _zh ? '在你的 Drive 中，未在此设备上' : 'In your Drive, not on this device'; // settings_sync_section_cloud_only
  String get sectionDevices => _zh ? '设备' : 'Devices'; // settings_sync_section_devices
  String get turnOffTitle => _zh ? '在此设备上关闭' : 'Turn off on this device'; // settings_sync_turn_off_title
  String get turnOffSummary => _zh
      ? '数据仍保留在此设备与你的 Drive 中。其他设备继续同步。'
      : 'Your data stays here and in your Drive. Other devices keep syncing.'; // settings_sync_turn_off_summary
  String get deleteTitle => _zh ? '删除云端数据…' : 'Delete cloud data…'; // settings_sync_delete_title
  String get deleting => _zh ? '正在删除…' : 'Deleting…'; // settings_sync_deleting
  String get cdSyncNow => _zh ? '立即同步' : 'Sync now'; // settings_cd_sync_now
  String get accountSyncing => _zh ? '正在同步' : 'Syncing'; // settings_sync_account_syncing
  String get accountReconnect => _zh ? '重新连接以同步' : 'Reconnect to sync'; // settings_sync_account_reconnect
  String accountDuplicate(String other) => _zh ? '与 $other 相同' : 'Same as $other'; // settings_sync_account_duplicate
  String get accountNotSynced => _zh ? '未同步' : 'Not synced'; // settings_sync_account_not_synced
  String get accountChanged => _zh ? '已暂停' : 'Paused'; // settings_sync_account_changed
  String get accountRemovedFromCloud =>
      _zh ? '已从云端移除' : 'Removed from the cloud'; // settings_sync_account_removed_from_cloud
  String get startSyncing => _zh ? '开始同步' : 'Start syncing'; // settings_sync_start_syncing
  String get resume => _zh ? '恢复同步' : 'Resume syncing'; // settings_sync_resume
  String get keepBefore => _zh ? '继续按此前方式同步' : 'Keep syncing as before'; // settings_sync_keep_before
  String get startFresh => _zh ? '重新开始' : 'Start fresh'; // settings_sync_start_fresh
  String linkTo(String name) => _zh ? '关联到 $name' : 'Link to $name'; // settings_sync_link_to
  String get linkMenu => _zh ? '关联…' : 'Link…'; // settings_sync_link_menu
  String linkFrom(String name, String device) =>
      _zh ? '$name（来自 $device）' : '$name from $device'; // settings_sync_link_from
  String get link => _zh ? '关联' : 'Link'; // settings_sync_link
  String get add => _zh ? '添加' : 'Add'; // settings_sync_add
  String get thisDevice => _zh ? '此设备' : 'This device'; // settings_sync_this_device
  String deviceLastSynced(String when) => _zh ? '上次同步：$when' : 'Last synced $when'; // settings_sync_device_last_synced
  String get deviceNever => _zh ? '尚未同步' : "Hasn't synced yet"; // settings_sync_device_never
  String cdDeviceOptions(String name) => _zh ? '$name 的更多选项' : 'More options for $name'; // settings_cd_device_options
  String get removeDeviceMenu => _zh ? '从同步中移除' : 'Remove from sync'; // settings_sync_remove_device_menu
  String get whatTitle => _zh ? '同步内容' : 'What syncs'; // settings_sync_what_title
  String get whatSummary =>
      _zh ? '笔记、评分、翻译与少量设置' : 'Notes, ratings, translations and a few settings'; // settings_sync_what_summary
  String get whatSyncs => _zh ? '会同步' : 'Syncs'; // settings_sync_what_syncs
  String get whatNotes => _zh ? '歌曲笔记' : 'Notes on songs'; // settings_sync_what_notes
  String get whatRatings =>
      _zh ? '歌曲与专辑评分、专辑评论' : 'Song and album ratings, album reviews'; // settings_sync_what_ratings
  String get whatHome => _zh ? '首页布局' : 'Home layout'; // settings_sync_what_home
  String get whatTranslations => _zh ? 'AI 歌词翻译' : 'AI lyric translations'; // settings_sync_what_translations
  String get whatPlaylistBackups => _zh ? '歌单云备份' : 'Playlist cloud backup'; // settings_sync_what_playlist_backups
  String get whatAccounts => _zh ? '账号名称、服务器地址' : 'Account names, server addresses'; // settings_sync_what_accounts
  String get whatStays => _zh ? '仅保存在本地' : 'Stays on this device'; // settings_sync_what_stays
  String get whatHistory => _zh ? '播放记录' : 'Listening history'; // settings_sync_what_history
  String get whatSecrets => _zh ? '密码、登录状态与 API 密钥' : 'Passwords, sign-ins and API keys'; // settings_sync_what_secrets
  String get whatCaches => _zh ? '缓存与下载' : 'Caches and downloads'; // settings_sync_what_caches
  String get cdExpand => _zh ? '展开' : 'Expand'; // settings_cd_expand
  String get cdCollapse => _zh ? '收起' : 'Collapse'; // settings_cd_collapse
  String get deleteDialogTitle => _zh ? '删除云端数据？' : 'Delete cloud data?'; // settings_sync_delete_dialog_title
  String get deleteDialogBody => _zh
      ? '从你的 Google Drive 中删除所有设备的 Yoin 数据，并在各设备上关闭同步。此设备上的数据会保留。'
      : "Deletes Yoin's data from your Google Drive for all devices, and turns sync off on them. Data on this device stays."; // settings_sync_delete_dialog_body
  String get deleteConfirm => _zh ? '删除' : 'Delete'; // settings_sync_delete_confirm
  String get deleteCancel => _zh ? '取消' : 'Cancel'; // settings_sync_delete_cancel
  String get freshTitle => _zh ? '以此账号重新开始？' : 'Start fresh as this account?'; // settings_sync_fresh_title
  String freshBody(String name) => _zh
      ? '“$name”现在登录了另一个账号。此前同步的笔记与评分将从此设备移除，后续将作为新账号同步。你的 Drive 与其他设备仍保留旧数据。'
      : '“$name” is now signed in as a different account. Notes and ratings it synced before are removed from this device, and it syncs as the new account from now on. Your Drive and your other devices keep the old ones.'; // settings_sync_fresh_body
  String get freshConfirm => _zh ? '重新开始' : 'Start fresh'; // settings_sync_fresh_confirm
  String get freshCancel => _zh ? '取消' : 'Cancel'; // settings_sync_fresh_cancel
  String removeDeviceTitle(String name) =>
      _zh ? '将 $name 从同步中移除？' : 'Remove $name from sync?'; // settings_sync_remove_device_title
  String get removeDeviceBody => _zh
      ? '从你的 Google Drive 中删除该设备的副本。若该设备仍在同步，下次同步时会重新上传。'
      : "Deletes this device's copy from your Google Drive. If it's still syncing, it uploads again the next time it syncs."; // settings_sync_remove_device_body
  String get removeDeviceConfirm => _zh ? '移除' : 'Remove'; // settings_sync_remove_device_confirm
  String get removeDeviceCancel => _zh ? '取消' : 'Cancel'; // settings_sync_remove_device_cancel
  String get reviewRestore => _zh ? '在此处恢复' : 'Restore here'; // settings_sync_review_restore
  String get reviewDeleteAll => _zh ? '在所有设备上删除' : 'Delete on all devices'; // settings_sync_review_delete_all
  String get deleted =>
      _zh ? '已从你的 Google Drive 删除 Yoin 数据' : "Deleted Yoin's data from your Google Drive"; // settings_sync_deleted
  String get deleteFailed => _zh
      ? '无法删除云端数据。请检查网络后重试。'
      : "Couldn't delete the cloud data. Check your connection and try again."; // settings_sync_delete_failed
  String get turnOnFailed =>
      _zh ? '无法连接 Google。请稍后再试。' : "Couldn't reach Google. Try again in a moment."; // settings_sync_turn_on_failed

  // Relative times.
  String get relativeJustNow => _zh ? '刚刚' : 'just now'; // settings_sync_relative_just_now
  String get relativeYesterday => _zh ? '昨天' : 'yesterday'; // settings_sync_relative_yesterday
  String relativeMinutes(int n) => _zh ? '$n 分钟前' : '$n min ago'; // settings_sync_relative_minutes
  String relativeHours(int n) => _zh ? '$n 小时前' : '$n hr ago'; // settings_sync_relative_hours
  String relativeDays(int n) => _zh ? '$n 天前' : (n == 1 ? '$n day ago' : '$n days ago'); // settings_sync_relative_days

  // The Settings row.
  String get summaryOff => _zh ? '关' : 'Off'; // settings_sync_summary_off
  String get summaryMisconfigured =>
      _zh ? '当前版本不可用' : 'Not available in this build'; // settings_sync_summary_misconfigured
  String summaryResetOn(String device) =>
      _zh ? '已在 $device 上关闭' : 'Turned off on $device'; // settings_sync_summary_reset_on
  String get summaryResetOther =>
      _zh ? '已在另一台设备上关闭' : 'Turned off on another device'; // settings_sync_summary_reset_other
  String get summarySyncing => _zh ? '正在同步…' : 'Syncing…'; // settings_sync_summary_syncing
  String get summaryPending => _zh ? '有更改等待上传' : 'Changes waiting to upload'; // settings_sync_summary_pending
  String get summaryPendingOffline =>
      _zh ? '有更改等待上传' : 'Changes waiting to upload'; // settings_sync_summary_pending_offline
  String summarySynced(String when) => _zh ? '已同步：$when' : 'Synced $when'; // settings_sync_summary_synced
  String get summaryOn => _zh ? '开' : 'On'; // settings_sync_summary_on
  String get summaryOffline => _zh ? '离线' : 'Offline'; // settings_sync_summary_offline
  String get summaryReauth => _zh ? '重新连接 Google' : 'Reconnect Google'; // settings_sync_summary_reauth
  String get summaryStorage => _zh ? 'Google 存储空间已满' : 'Google storage full'; // settings_sync_summary_storage
  String get summaryCopyRemoved => _zh ? 'Drive 副本已移除' : 'Drive copy removed'; // settings_sync_summary_copy_removed
  String get summaryUpdate => _zh ? '更新 Yoin' : 'Update Yoin'; // settings_sync_summary_update
  String get summaryNeedsReviewStatus => _zh ? '待确认' : 'Needs review'; // settings_sync_summary_needs_review_status
  String summaryNeedsReview(int n) =>
      _zh ? '$n 条笔记已移除' : (n == 1 ? '$n note removed' : '$n notes removed'); // settings_sync_summary_needs_review
  String syncedOtherDevicesCount(int n) =>
      _zh ? '$n\n已从其他设备同步' : '$n\nSynced from other devices'; // settings_sync_synced_other_devices_count
  String get summaryError => _zh ? '无法同步' : "Couldn't sync"; // settings_sync_summary_error

  // The page's status row.
  String get statusSyncing => _zh ? '正在同步…' : 'Syncing…'; // settings_sync_status_syncing
  String get statusPending => _zh ? '有更改等待上传' : 'Changes waiting to upload'; // settings_sync_status_pending
  String get statusUpToDate => _zh ? '已是最新' : 'Up to date'; // settings_sync_status_up_to_date
  String get statusNotYet => _zh ? '尚未同步' : 'Not synced yet'; // settings_sync_status_not_yet
  String statusLastSynced(String when) => _zh ? '上次同步：$when' : 'Last synced $when'; // settings_sync_status_last_synced
  String get statusOffline => _zh ? '无法连接 Google' : "Can't reach Google"; // settings_sync_status_offline
  String get statusPaused => _zh ? '已暂停' : 'Paused'; // settings_sync_status_paused
  String get statusStorage => _zh ? 'Google 存储空间已满' : 'Google storage full'; // settings_sync_status_storage
  String get statusReview => _zh ? '需要你确认' : 'Needs your review'; // settings_sync_status_review
  String get statusRemoved => _zh ? 'Drive 副本已移除' : 'Drive copy removed'; // settings_sync_status_removed
  String get statusUpdate => _zh ? '更新 Yoin' : 'Update Yoin'; // settings_sync_status_update
  String get statusError => _zh ? '无法同步' : "Couldn't sync"; // settings_sync_status_error
  String get statusOff => _zh ? '关' : 'Off'; // settings_sync_status_off

  // Held review.
  String reviewRowTitle(int n) => _zh
      ? '此设备上有 $n 条笔记已移除'
      : (n == 1
            ? '$n note removed on this device'
            : '$n notes removed on this device'); // settings_sync_review_row_title
  String reviewDialogTitle(int n) =>
      _zh ? '$n 条笔记已移除' : (n == 1 ? '$n note removed' : '$n notes removed'); // settings_sync_review_dialog_title
  String reviewDialogBody(int n, String account) => _zh
      ? '已在当前设备上从 $account 中移除。可在此处恢复，或在所有设备上删除。'
      : n == 1
      ? 'It was removed on this device from $account. Restore it here, or delete it on all your devices.'
      : 'They were removed on this device from $account. Restore them here, or delete them on all your devices.'; // settings_sync_review_dialog_body

  // Cloud data: one account's data in the cloud, and removing it.
  String get googleDrive => 'Google Drive'; // settings_sync_google_drive
  String get cloudOnly => _zh ? '仅在云端' : 'Cloud only'; // settings_sync_cloud_only
  String fromDevice(String device) => _zh ? '来自 $device' : 'From $device'; // settings_sync_from_device
  String cdAccountOptions(String name) =>
      _zh ? '$name 的更多选项' : 'More options for $name'; // settings_sync_cd_account_options
  String get menuView => _zh ? '查看云端数据' : 'View cloud data'; // settings_sync_menu_view
  String get menuRemove => _zh ? '从云端移除…' : 'Remove from cloud…'; // settings_sync_menu_remove
  String get removeAction => _zh ? '从云端移除' : 'Remove from cloud'; // settings_sync_remove_action
  String browserReadonly(String where) =>
      _zh ? '$where 云端副本（只读）' : 'Cloud copy on $where (read-only)'; // settings_sync_browser_readonly
  String get browserSearch => _zh ? '搜索笔记、乐评或歌名' : 'Search notes, reviews, or songs'; // settings_sync_browser_search
  String get loading => _zh ? '正在读取…' : 'Loading…'; // settings_sync_loading
  String get filesFailed => _zh
      ? '无法读取云端文件，请检查网络后重试。'
      : "Couldn't read the cloud files. Check your connection and try again."; // settings_sync_files_failed
  String get tabNotes => _zh ? '笔记' : 'Notes'; // settings_sync_tab_notes
  String get tabAlbums => _zh ? '专辑评分与乐评' : 'Album ratings & reviews'; // settings_sync_tab_albums
  String get tabSongs => _zh ? '单曲评分' : 'Song ratings'; // settings_sync_tab_songs
  String get tabTitles => _zh ? 'Memories 标题' : 'Memories titles'; // settings_sync_tab_titles
  String get unknownSong => _zh ? '未知歌曲' : 'Unknown song'; // settings_sync_unknown_song
  String get unknownAlbum => _zh ? '未知专辑' : 'Unknown album'; // settings_sync_unknown_album
  String get ratingOnly => _zh ? '仅评分' : 'Rating only'; // settings_sync_rating_only
  String get titleCustom => _zh ? '自定义标题' : 'Custom title'; // settings_sync_title_custom
  String get titleAi => _zh ? 'AI 标题' : 'AI title'; // settings_sync_title_ai
  String get noData => _zh ? '暂无数据' : 'No data yet'; // settings_sync_no_data
  String get noMatches => _zh ? '没有找到匹配内容' : 'No matches'; // settings_sync_no_matches

  // An account's counts: "128 notes" as value + unit (settings_sync_unit_*).
  String unitNotes(int n) => _zh ? '条笔记' : (n == 1 ? 'note' : 'notes'); // settings_sync_unit_notes
  String unitAlbums(int n) =>
      _zh ? '条专辑评分与乐评' : (n == 1 ? 'album rating & review' : 'album ratings & reviews'); // settings_sync_unit_albums
  String unitSongRatings(int n) =>
      _zh ? '条单曲评分' : (n == 1 ? 'song rating' : 'song ratings'); // settings_sync_unit_song_ratings
  String unitTitles(int n) =>
      _zh ? '个 Memories 标题' : (n == 1 ? 'Memories title' : 'Memories titles'); // settings_sync_unit_titles

  // Remove from the cloud (RemoveFromCloudDialog).
  String removeTitle(String name) =>
      _zh ? '从云端移除 $name？' : 'Remove $name from the cloud?'; // settings_sync_remove_title
  String removeBody(String where, String what) =>
      _zh ? '将删除此账号在 $where 中的 $what。' : 'This deletes $what of this account from $where.'; // settings_sync_remove_body
  String get removeBodyLocal => _zh
      ? '此设备上的数据会保留，但该账号将停止同步。'
      : 'Data on this device stays, but this account stops syncing.'; // settings_sync_remove_body_local
  String removeBodyCloudDevice(String device) => _zh
      ? '其他仍登录该账号的设备（如 $device）也会停止上传其数据。'
      : 'Other devices still signed in to this account (like $device) also stop uploading its data.'; // settings_sync_remove_body_cloud_device
  String get removeBodyCloud => _zh
      ? '其他仍登录该账号的设备也会停止上传其数据。'
      : 'Other devices still signed in to this account also stop uploading its data.'; // settings_sync_remove_body_cloud
  String get removeBodyTransferred => _zh
      ? '已转移到其他账号的内容不受影响。'
      : "Anything already transferred to other accounts isn't affected."; // settings_sync_remove_body_transferred
  String get removeConfirm => _zh ? '移除' : 'Remove'; // settings_sync_remove_confirm
  String get removeCancel => _zh ? '取消' : 'Cancel'; // settings_sync_remove_cancel
  String get removeAllData => _zh ? '全部数据' : 'all its data'; // settings_sync_remove_all_data
  String countNotes(int n) => _zh ? '$n 条笔记' : (n == 1 ? '$n note' : '$n notes'); // settings_sync_count_notes
  String countAlbums(int n) => _zh
      ? '$n 条专辑评分与乐评'
      : (n == 1 ? '$n album rating & review' : '$n album ratings & reviews'); // settings_sync_count_albums
  String countSongRatings(int n) =>
      _zh ? '$n 条单曲评分' : (n == 1 ? '$n song rating' : '$n song ratings'); // settings_sync_count_song_ratings
  String countTitles(int n) =>
      _zh ? '$n 个 Memories 标题' : (n == 1 ? '$n Memories title' : '$n Memories titles'); // settings_sync_count_titles
  String get listSeparator => _zh ? '、' : ', '; // settings_sync_list_separator
  String listLast(String head, String last) => _zh ? '$head和$last' : '$head and $last'; // settings_sync_list_last
  String removed(String name) => _zh ? '已从云端移除 $name' : 'Removed $name from the cloud'; // settings_sync_removed
  String removeFailed(String name) => _zh
      ? '无法从云端移除 $name，请检查网络后重试。'
      : "Couldn't remove $name from the cloud. Check your connection and try again."; // settings_sync_remove_failed
}
