import 'package:flutter/widgets.dart';

import 'home_section.dart';
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

  String get sectionRediscover => _zh ? '重温' : 'Rediscover'; // home_section_rediscover
  String get sectionOtherProfiles => _zh ? '在你的其他资料里' : 'On Your Other Accounts'; // home_section_other_profiles
  String get sectionLyricHighlight => _zh ? '歌词卡片' : 'Lyric Cards'; // home_section_lyric_highlight
  String get sectionMemoryGrid => _zh ? '全部回忆' : 'All Memories'; // home_section_memory_grid
  String get sectionMadeForYou => _zh ? '为你推荐' : 'Made For You'; // home_section_made_for_you

  String sectionTitle(HomeSection section) => switch (section) {
        HomeSection.activities => sectionActivities,
        HomeSection.jumpBackIn => sectionJumpBackIn,
        HomeSection.recentlyAdded => sectionRecentlyAdded,
        HomeSection.rediscover => sectionRediscover,
        HomeSection.recentlyPlayed => sectionRecentlyPlayed,
        HomeSection.yourPlaylists => sectionYourPlaylists,
        HomeSection.otherProfiles => sectionOtherProfiles,
        HomeSection.lyricHighlight => sectionLyricHighlight,
        HomeSection.memoryGrid => sectionMemoryGrid,
        HomeSection.madeForYou => sectionMadeForYou,
      };

  /// home_section_*_supporting: the edit plate's line under a section's name.
  String sectionSupporting(HomeSection section) => switch (section) {
        HomeSection.activities => _zh ? '近期播放与访问记录' : 'Your recent plays and visits',
        HomeSection.jumpBackIn => _zh ? '专辑、歌曲与播放列表' : 'Albums, songs and playlists',
        HomeSection.recentlyAdded => _zh ? '本月添加至资料库' : 'Added to your library this month',
        HomeSection.rediscover =>
          _zh ? '评过分或写过笔记、有一阵子未播放的专辑与歌曲' : 'Albums and songs you rated or wrote about, not played in a while',
        HomeSection.recentlyPlayed => _zh ? '近期在任意设备上播放过的专辑' : 'Albums you played lately, on any device',
        HomeSection.yourPlaylists => _zh ? '资料库中的播放列表' : 'Playlists in your library',
        HomeSection.otherProfiles => _zh ? '你的其他账号常听的专辑和播放列表' : 'Albums and playlists your other accounts play most',
        HomeSection.lyricHighlight => _zh ? '从你的歌里挑出的歌词，点按从那句开播' : 'Lyrics picked from your songs, tap to play from that line',
        HomeSection.memoryGrid => _zh ? '所有笔记和评分，格子跟着你写的多少变' : 'Every note and score, on a grid that grows with you',
        HomeSection.madeForYou => _zh ? 'Spotify 和 Apple Music 为你做的歌单' : 'Playlists Spotify and Apple Music make for you',
      };

  /// home_placeholder_*: an edit plate's empty section.
  String sectionPlaceholder(HomeSection section) => switch (section) {
        HomeSection.activities => _zh ? '近期无动态' : 'No recent activity',
        HomeSection.jumpBackIn => _zh ? '暂无可接着听的内容' : 'Nothing to jump back into',
        HomeSection.recentlyAdded => _zh ? '本月暂无添加' : 'Nothing added this month',
        HomeSection.rediscover => _zh ? '暂无可重温的内容' : 'Nothing to rediscover yet',
        HomeSection.recentlyPlayed => _zh ? '近期无播放记录' : 'Nothing played lately',
        HomeSection.yourPlaylists => _zh ? '资料库中无播放列表' : 'No playlists in your library',
        HomeSection.otherProfiles => _zh ? '其他账号最近没有播放' : 'Nothing played on your other accounts lately',
        // Carbon 2026-11-17: no Gemini Nano picks a line here (Android: home_placeholder_lyric_highlight).
        HomeSection.lyricHighlight => _zh ? '在歌词里选句分享或保存，这里就会有第一张' : 'Share or save lines in Lyrics to add the first card',
        HomeSection.memoryGrid => _zh ? '还没有回忆' : 'No memories yet',
        HomeSection.madeForYou => _zh ? '为你做的歌单会出现在这里' : 'Playlists made for you show here',
      };

  // ---- Jump Back In signal cards ----

  String get widgetRatingNa => _zh ? '暂无' : 'N/A'; // home_widget_rating_na
  String widgetBasisTracks(int rated, int total) =>
      _zh ? '基于 $rated/$total 首曲目' : 'Based on $rated/$total tracks'; // home_widget_basis_tracks

  /// The basis date in the locale's "MMMd" (formatHomeMemoryDate): "Jun 26", "6月26日".
  String memoryDate(int epochMs) {
    final d = DateTime.fromMillisecondsSinceEpoch(epochMs);
    const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
    return _zh ? '${d.month}月${d.day}日' : '${months[d.month - 1]} ${d.day}';
  }

  // ---- Lyric Cards ----

  String get lyricPlayFromLine => _zh ? '从这句开始播放' : 'Play from this line'; // home_lyric_play_from_line
  String get lyricKindHeavy => _zh ? '常听' : 'On repeat'; // home_lyric_kind_heavy
  String get lyricKindRediscover => _zh ? '久违' : 'Been a while'; // home_lyric_kind_rediscover
  String get lyricKindUser => _zh ? '你选的' : 'Your pick'; // home_lyric_kind_user
  String get lyricIntroBadge => 'Beta'; // home_lyric_intro_badge
  String get lyricIntroTitle => _zh ? '恭喜你发现了一个 Beta 功能' : 'You found a beta feature'; // home_lyric_intro_title
  // The intro's body: Carbon 2026-11-17 for HarmonyOS (no Gemini Nano, and tablets and PCs too); Android's
  // home_lyric_intro_unsupported / home_lyric_intro_picks don't hold here.
  String get lyricIntroPicks => _zh
      ? '你在歌词页选中并分享或保存为图片卡片的歌词，我们会记住，显示在这里。'
      : 'Lines you select on the lyrics page and share or save as an image card are remembered and show up here.';
  String get lyricIntroPlay => _zh
      ? '点卡片就从那句开始播放。挑过的句子按账号留在这台设备上。'
      : 'Tap a card to play from that line. Picks stay with each account on this device.';
  String get lyricIntroGotIt => _zh ? '知道了' : 'Got it'; // home_lyric_intro_got_it

  /// home_edit_lyric_cards (plural).
  String editLyricCards(int count) => _zh ? '$count 段' : (count == 1 ? '$count card' : '$count cards');

  // ---- Rediscover ----

  String get rediscoverReviewed => _zh ? '已写评论' : 'Reviewed'; // home_rediscover_reviewed
  String rediscoverNotes(int n) => _zh ? '$n 条笔记' : (n == 1 ? '$n note' : '$n notes'); // home_rediscover_notes
  String rediscoverTracksRated(int n) =>
      _zh ? '已评 $n 首' : (n == 1 ? '$n track rated' : '$n tracks rated'); // home_rediscover_tracks_rated

  /// home_rediscover_eyebrow_months / _years.
  String rediscoverAway(int count, {required bool years}) => years
      ? (_zh ? '$count 年未听' : (count == 1 ? '$count year away' : '$count years away'))
      : (_zh ? '$count 个月未听' : (count == 1 ? '$count month away' : '$count months away'));
  String rediscoverCdYourRating(String score) => _zh ? '你的评分 $score' : 'Your rating $score'; // home_rediscover_cd_your_rating
  String rediscoverCdTrackAverage(String score) => _zh ? '曲目均分 $score' : 'Track average $score'; // home_rediscover_cd_track_average

  /// home_unit_notes (plural).
  String notesUnit(int count) => _zh ? '条笔记' : (count == 1 ? 'note' : 'notes');

  /// home_unit_plays (plural).
  String playsUnit(int count) => _zh ? '次播放' : (count == 1 ? 'play' : 'plays');

  // ---- Account switcher ----

  String accountQuickSwitch(String name) => _zh ? '切换到 $name' : 'Switch to $name'; // home_account_quick_switch_action
  String get accountClose => _zh ? '关闭' : 'Close'; // home_account_close
  String get accountManage => _zh ? '管理账号' : 'Manage accounts'; // home_account_manage
  String get accountAdd => _zh ? '添加账号' : 'Add account'; // home_account_add
  String get accountSettings => _zh ? '设置' : 'Settings'; // home_account_settings
  String get accountReconnect => _zh ? '重新连接' : 'Reconnect'; // home_account_reconnect
  String get accountReconnectNeeded => _zh ? '账号需要重新连接' : 'Account needs reconnecting'; // home_account_reconnect_needed

  /// Another account on the card whose credentials can't be read (Android
  /// AccountRow's accountIssueLabel, as Settings' pill says it).
  String get accountCredentialsMissing => _zh ? '凭据缺失' : 'Credentials missing'; // settings_account_credentials_missing

  // ---- Edit Home ----

  String get undo => _zh ? '撤销' : 'Undo'; // cmp_bar_cd_undo
  String get done => _zh ? '完成' : 'Done'; // cmp_bar_label_done
  String get editTitle => _zh ? '编辑首页' : 'Edit Home'; // home_edit_title
  String get editAction => _zh ? '编辑首页' : 'Edit Home'; // home_edit_action_section
  String get editHidden => _zh ? '已隐藏' : 'Hidden'; // home_edit_hidden
  String get editHiddenEmpty => _zh ? '无隐藏分区' : 'No hidden sections'; // home_edit_hidden_empty
  String get editReset => _zh ? '重置首页' : 'Reset Home'; // home_edit_reset
  String get editNew => _zh ? '新' : 'New'; // home_edit_new
  String showSectionAction(String name) => _zh ? '显示 $name' : 'Show $name'; // home_action_show_section
  String get editAllHiddenTitle => _zh ? '首页是空的' : 'Home is empty'; // home_edit_all_hidden_title
  String hideSectionCd(String name) => _zh ? '隐藏 $name' : 'Hide $name'; // home_cd_hide_section
  String reorderSectionCd(String name) => _zh ? '调整“$name”的顺序' : 'Reorder $name'; // landing_home_drag
  String showSectionCd(String name) => _zh ? '显示 $name' : 'Show $name'; // home_cd_show_section
  String get editMoveUp => _zh ? '上移' : 'Move up'; // home_edit_move_up
  String get editMoveDown => _zh ? '下移' : 'Move down'; // home_edit_move_down
  String get editMoreRows => _zh ? '增加行数' : 'More rows'; // home_edit_more_rows
  String get editFewerRows => _zh ? '减少行数' : 'Fewer rows'; // home_edit_fewer_rows
  String get editHide => _zh ? '隐藏' : 'Hide'; // home_edit_hide
  String get jbiHideThreeDays => _zh ? '3 天内不显示' : 'Hide for 3 days'; // home_jbi_hide_three_days
  String get jbiHideWeek => _zh ? '一周内不显示' : 'Hide for a week'; // home_jbi_hide_week
  String sectionIndexCd(int index, int count) =>
      _zh ? '第 $index 个分区，共 $count 个' : 'Section $index of $count'; // home_cd_section_index
  String sectionIndexRowsCd(int index, int count, String rows) =>
      _zh ? '第 $index 个分区，共 $count 个，$rows' : 'Section $index of $count, $rows'; // home_cd_section_index_rows

  /// home_edit_rows (plural).
  String rowsLabel(int rows) => _zh ? '$rows 行' : (rows == 1 ? '$rows row' : '$rows rows');

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
