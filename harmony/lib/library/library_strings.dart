import 'package:flutter/widgets.dart';

/// Library strings, English and Simplified Chinese, copied from Android
/// res/values{,-zh-rCN}/strings*.xml (key in the comment). Same pattern as
/// [YoinStrings].
class LibraryStrings {
  const LibraryStrings._(this.zh);

  final bool zh;

  static LibraryStrings of(BuildContext context) =>
      LibraryStrings._(Localizations.maybeLocaleOf(context)?.languageCode == 'zh');

  /// For code without a context (tests, sorting labels).
  static const en = LibraryStrings._(false);
  static const zhHans = LibraryStrings._(true);

  String get title => zh ? '资料库' : 'Library'; // library_title
  String get errorLoadLibrary => zh ? '无法加载资料库' : 'Failed to load library'; // library_error_load_library
  String get errorRetry => zh ? '重试' : 'Retry'; // library_error_retry
  String get errorSettings => zh ? '设置' : 'Settings'; // library_error_settings

  /// The account in use has no source and none is coming (its credentials
  /// couldn't be read): said as Home's dot says it.
  String get accountReconnectNeeded => zh ? '账号需要重新连接' : 'Account needs reconnecting'; // home_account_reconnect_needed
  String get errorLoadArtists => zh ? '无法加载艺术家' : 'Failed to load Artists'; // library_error_load_tab_artists
  String get errorLoadAlbums => zh ? '无法加载专辑' : 'Failed to load Albums'; // library_error_load_tab_albums
  String get errorLoadSongs => zh ? '无法加载歌曲' : 'Failed to load Songs'; // library_error_load_tab_songs
  String get errorLoadPlaylists => zh ? '无法加载播放列表' : 'Failed to load Playlists'; // library_error_load_tab_playlists
  String get errorLoadFavorites => zh ? '无法加载收藏' : 'Failed to load Favorites'; // library_error_load_tab_favorites

  String get emptyAll => zh ? '资料库为空' : 'Your library is empty'; // library_empty_all
  String get emptyArtists => zh ? '未找到艺术家' : 'No artists found'; // library_empty_artists
  String get emptyAlbums => zh ? '未找到专辑' : 'No albums found'; // library_empty_albums
  String get emptySongs => zh ? '未找到歌曲' : 'No songs found'; // library_empty_songs
  String get emptyPlaylists => zh ? '无播放列表' : 'No playlists'; // library_empty_playlists
  String get emptyFavorites => zh ? '暂无收藏' : 'No favorites yet'; // library_empty_favorites

  String get searchPlaceholder => zh ? '搜索资料库' : 'Search library'; // library_search_placeholder_library
  String get searchPlaceholderAppleMusic =>
      zh ? '搜索 Apple Music' : 'Search Apple Music'; // library_search_placeholder_apple_music
  String get searchScopeLibrary => zh ? '资料库' : 'Library'; // library_search_scope_library
  String get searchScopeAppleMusic => 'Apple Music'; // library_search_scope_apple_music (translatable="false")

  // Add to library on a catalogue song (TrackLibraryButton, SearchSongRow).
  String get cdLibraryAdd => zh ? '添加到 Apple Music 资料库' : 'Add to Apple Music library'; // cmp_library_cd_add
  String get cdLibraryAdded => zh ? '已在 Apple Music 资料库中' : 'In Apple Music library'; // cmp_library_cd_added
  String get cdLibraryPending => zh ? '检查是否已加入资料库' : 'Check library addition'; // cmp_library_cd_pending
  String get searchAdded => zh ? '已加入资料库' : 'Added to library'; // library_search_added
  String get feedbackApplePending =>
      zh ? '等待 Apple Music 确认' : 'Waiting for Apple Music'; // library_feedback_apple_music_pending
  String get errorAddSong => zh ? '无法将此歌曲加入资料库。' : "Couldn't add this song to the library."; // library_error_add_song
  String get cdSettings => zh ? '设置' : 'Settings'; // library_cd_settings

  String get tabPlaylists => zh ? '播放列表' : 'Playlists'; // library_tab_playlists
  String get filterByYou => zh ? '你创建的' : 'By You'; // library_filter_by_you
  String get tabArtists => zh ? '艺术家' : 'Artists'; // library_tab_artists
  String get tabAlbums => zh ? '专辑' : 'Albums'; // library_tab_albums
  String get tabSongs => zh ? '歌曲' : 'Songs'; // library_tab_songs
  String get tabFavorites => zh ? '收藏' : 'Favorites'; // library_tab_favorites
  String get cdClearFilter => zh ? '清除筛选' : 'Clear filter'; // library_cd_clear_filter

  String get sortAction => zh ? '排序' : 'Sort'; // library_sort_action
  String get sortRecents => zh ? '最近' : 'Recents'; // library_sort_recents
  String get sortRecentlyAdded => zh ? '最近添加' : 'Recently added'; // library_sort_recently_added
  String get sortAlphabetical => zh ? '字母顺序' : 'Alphabetical'; // library_sort_alphabetical
  String get sortCreator => zh ? '创建者' : 'Creator'; // library_sort_creator

  String get recentsToday => zh ? '今天' : 'Today'; // library_recents_today
  String get recentsThisWeek => zh ? '本周' : 'This week'; // library_recents_this_week
  String get recentsThisMonth => zh ? '本月' : 'This month'; // library_recents_this_month
  String get recentsNotOpened => zh ? '未打开过' : 'Not opened'; // library_recents_not_opened

  /// library_artist_grid_album_count (one / other); zh has one form.
  String artistGridAlbumCount(int n) => zh ? '$n 张专辑' : (n == 1 ? '$n album' : '$n albums');

  /// library_artist_row_album_count.
  String artistRowAlbumCount(int n) => zh ? '$n 张专辑' : (n == 1 ? '$n album' : '$n albums');
  String get playlistFallback => zh ? '播放列表' : 'Playlist'; // library_playlist_fallback

  String get favoritesSectionArtists => zh ? '艺术家' : 'Artists'; // library_favorites_section_artists
  String get favoritesSectionAlbums => zh ? '专辑' : 'Albums'; // library_favorites_section_albums
  String get favoritesSectionSongs => zh ? '歌曲' : 'Songs'; // library_favorites_section_songs

  /// library_playlist_track_unit (one track / other tracks; zh 首).
  String playlistTrackUnit(int n) => zh ? '首' : (n == 1 ? 'track' : 'tracks');
  String get durationHourUnit => zh ? '小时' : 'h'; // library_duration_hour_unit
  String get durationMinuteUnit => zh ? '分钟' : 'min'; // library_duration_minute_unit
  String get cdNowPlaying => zh ? '正在播放' : 'Now playing'; // detail_album_cd_now_playing

  String get searchFilterAll => zh ? '全部' : 'All'; // library_search_filter_all
  String get searchSectionArtists => zh ? '艺术家' : 'Artists'; // library_search_section_artists
  String get searchSectionAlbums => zh ? '专辑' : 'Albums'; // library_search_section_albums
  String get searchSectionSongs => zh ? '歌曲' : 'Songs'; // library_search_section_songs
  String get searchSectionPlaylists => zh ? '播放列表' : 'Playlists'; // library_search_section_playlists
  String get emptySearch => zh ? '未找到结果' : 'No results found'; // library_empty_search
  String get searchRetry => zh ? '重试' : 'Retry'; // library_search_retry
  String get errorSearch => zh ? '搜索失败。' : 'Search failed.'; // library_error_search
  String get errorSearchSnackbar => zh ? '搜索失败' : 'Search failed'; // library_error_search_snackbar
  String get cdCloseSearch => zh ? '关闭搜索' : 'Close search'; // library_cd_close_search
  String get cdClearSearch => zh ? '清除搜索' : 'Clear search'; // library_cd_clear_search

  // Month names for the fast scroller's timeline (Android formats them with
  // ICU skeletons "yMMM" / "MMM": "Mar 2024" / "2024年3月", "Mar" / "3月").
  static const _shortMonths = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

  /// [month] 1–12.
  String yearMonth(int year, int month) => zh ? '$year年$month月' : '${_shortMonths[month - 1]} $year';
  String shortMonth(int month) => zh ? '$month月' : _shortMonths[month - 1];
}
