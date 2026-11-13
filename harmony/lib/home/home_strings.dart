import 'package:flutter/widgets.dart';

import 'home_selection.dart';

/// Home's UI strings, English and Simplified Chinese, copied from Android
/// res/values{,-zh-rCN}/strings_home.xml (key in the comment). Plurals are
/// small helpers instead of an ICU stack: English one/other, Chinese other.
class HomeStrings {
  const HomeStrings._(this._zh);

  /// For tests and non-widget callers.
  const HomeStrings.forLanguage({required bool chinese}) : _zh = chinese;

  final bool _zh;

  static HomeStrings of(BuildContext context) =>
      HomeStrings._(Localizations.maybeLocaleOf(context)?.languageCode == 'zh');

  String get title => _zh ? '首页' : 'Home'; // home_title
  String get greetingMorning => _zh ? '早上好' : 'Good morning'; // home_greeting_morning
  String get greetingNoon => _zh ? '中午好' : 'Good afternoon'; // home_greeting_noon
  String get greetingAfternoon => _zh ? '下午好' : 'Good afternoon'; // home_greeting_afternoon
  String get greetingEvening => _zh ? '晚上好' : 'Good evening'; // home_greeting_evening

  String greeting(HomeDayPart part) => switch (part) {
        HomeDayPart.morning => greetingMorning,
        HomeDayPart.noon => greetingNoon,
        HomeDayPart.afternoon => greetingAfternoon,
        HomeDayPart.evening => greetingEvening,
      };

  String get settingsCd => _zh ? '设置' : 'Settings'; // home_cd_settings
  String accountCd(String name) => _zh ? '账号：$name' : 'Account: $name'; // home_account_cd

  String get errorLoadFailed => _zh ? '无法加载首页内容' : 'Failed to load home content'; // home_error_load_failed
  String get errorRetry => _zh ? '重试' : 'Retry'; // home_error_retry
  String get errorSettings => _zh ? '设置' : 'Settings'; // home_error_settings

  String get sectionActivities => _zh ? '动态' : 'Activities'; // home_section_activities
  String get sectionJumpBackIn => _zh ? '接着听' : 'Jump Back In'; // home_section_jump_back_in
  String get sectionRecentlyAdded => _zh ? '最近添加' : 'Recently Added'; // home_section_recently_added
  String get sectionRecentlyPlayed => _zh ? '最近播放' : 'Recently Played'; // home_section_recently_played
  String get sectionYourPlaylists => _zh ? '你的播放列表' : 'Your Playlists'; // home_section_your_playlists

  String get activitiesEmptyTitle => _zh ? '暂无动态' : 'No activity yet'; // home_activities_empty_title

  String get activityTypeAlbum => _zh ? '专辑' : 'Album'; // home_activity_type_album
  String get activityTypeArtist => _zh ? '艺术家' : 'Artist'; // home_activity_type_artist
  String get activityTypePlaylist => _zh ? '播放列表' : 'Playlist'; // home_activity_type_playlist
  String get activityTypeTrack => _zh ? '曲目' : 'Track'; // home_activity_type_track
  String get activityFallbackArtist => _zh ? '艺术家' : 'Artist'; // home_activity_fallback_artist
  String get activityFallbackRecent => _zh ? '近期活跃' : 'Recently active'; // home_activity_fallback_recent

  /// home_unit_songs (plural).
  String songsUnit(int count) => _zh ? '首' : (count == 1 ? 'song' : 'songs');

  /// home_unit_minutes (plural; English one = other).
  String minutesUnit(int count) => _zh ? '分钟' : 'min';

  /// home_playlist_songs (plural).
  String playlistSongs(int count) => _zh ? '$count 首' : (count == 1 ? '$count song' : '$count songs');

  /// home_time_just_now / home_time_*_ago (plurals; the English forms are the same for one and other).
  String timeAgo(TimeAgo ago) => switch (ago) {
        TimeAgoJustNow() => _zh ? '刚刚' : 'just now', // home_time_just_now
        TimeAgoMinutes(:final count) => _zh ? '$count 分钟前' : '${count}m ago', // home_time_minutes_ago
        TimeAgoHours(:final count) => _zh ? '$count 小时前' : '${count}h ago', // home_time_hours_ago
        TimeAgoDays(:final count) => _zh ? '$count 天前' : '${count}d ago', // home_time_days_ago
        TimeAgoWeeks(:final count) => _zh ? '$count 周前' : '${count}w ago', // home_time_weeks_ago
      };
}
