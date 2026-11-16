import 'package:flutter/widgets.dart';

/// Detail page strings, English and Simplified Chinese, copied from Android
/// res/values{,-zh-rCN}/strings*.xml (key in the comment).
class DetailStrings {
  const DetailStrings._(this.zh);

  final bool zh;

  static DetailStrings of(BuildContext context) =>
      DetailStrings._(Localizations.maybeLocaleOf(context)?.languageCode == 'zh');

  static const en = DetailStrings._(false);

  String get cdBack => zh ? '返回' : 'Back'; // detail_cd_back
  String get retry => zh ? '重试' : 'Retry'; // cmp_detail_retry
  String get albumNotFound => zh ? '未找到专辑' : 'Album not found'; // detail_album_error_not_found
  String get albumLoadError => zh ? '无法加载此专辑。' : "Couldn't load this album."; // detail_album_error_load
  String get artistNotFound => zh ? '未找到艺术家' : 'Artist not found'; // detail_artist_error_not_found
  String get artistLoadError => zh ? '无法加载此艺术家。' : "Couldn't load this artist."; // detail_artist_error_load
  String get playlistNotFound => zh ? '未找到播放列表' : 'Playlist not found'; // detail_playlist_error_not_found
  String get playlistLoadError => zh ? '无法加载此播放列表。' : "Couldn't load this playlist."; // detail_playlist_error_load

  String get albumKind => zh ? '专辑' : 'Album'; // detail_album_kind
  String unitTrack(int n) => zh ? '首' : (n == 1 ? 'track' : 'tracks'); // detail_unit_track
  String get unitHour => zh ? '小时' : 'h'; // detail_unit_hour
  String get unitMinute => zh ? '分钟' : 'min'; // detail_unit_minute

  /// detail_album_feat: NBSP-led " (feat. %1$s)".
  String albumFeat(String artist) => ' (feat. $artist)';

  String discHeading(int n) => zh ? '第 $n 碟' : 'Disc $n'; // detail_disc_heading
  String get cdAddFavorite => zh ? '加入收藏' : 'Add to favorites'; // detail_album_cd_add_favorite
  String get cdRemoveFavorite => zh ? '取消收藏' : 'Remove from favorites'; // detail_album_cd_remove_favorite
  String get cdNowPlaying => zh ? '正在播放' : 'Now playing'; // detail_album_cd_now_playing

  String get artistFavorite => zh ? '收藏' : 'Favorite'; // detail_artist_favorite
  String get artistFavorited => zh ? '已收藏' : 'Favorited'; // detail_artist_favorited
  String get artistDiscography => zh ? '作品列表' : 'Discography'; // detail_artist_discography
  String artistReleases(int n) => zh ? '$n 张作品' : (n == 1 ? '$n release' : '$n releases'); // detail_artist_releases
  String get artistNoReleases => zh ? '无发行作品' : 'No releases'; // detail_artist_no_releases
  String artistShowAll(int n) => zh ? '显示全部 $n' : 'Show all $n'; // detail_artist_show_all
  String get artistShowFewer => zh ? '收起' : 'Show fewer'; // detail_artist_show_fewer
  String get artistLatest => zh ? '最新' : 'Latest'; // detail_artist_latest
  String get kindAlbum => zh ? '专辑' : 'Album'; // detail_artist_kind_album
  String get kindEp => 'EP'; // detail_artist_kind_ep
  String get kindSingle => zh ? '单曲' : 'Single'; // detail_artist_kind_single
  String get kindCompilation => zh ? '合辑' : 'Compilation'; // detail_artist_kind_compilation
  String get filterAll => zh ? '全部' : 'All'; // detail_artist_filter_all
  String get filterAlbums => zh ? '专辑' : 'Albums'; // detail_artist_filter_albums
  String get filterSingles => zh ? '单曲与 EP' : 'Singles & EPs'; // detail_artist_filter_singles
  String get filterCompilations => zh ? '合辑' : 'Compilations'; // detail_artist_filter_compilations
  String unitSong(int n) => zh ? '首' : (n == 1 ? 'song' : 'songs'); // detail_unit_song

  String get playlistKind => zh ? '播放列表' : 'Playlist'; // detail_playlist_kind
  String get playlistLength => zh ? '时长' : 'Length'; // detail_playlist_length
  String get playlistOwner => zh ? '创建者' : 'Owner'; // detail_playlist_owner
  String get playlistDescription => zh ? '简介' : 'Description'; // detail_playlist_description
  String get playlistPublic => zh ? '公开' : 'Public'; // detail_playlist_public
  String get playlistPrivate => zh ? '私密' : 'Private'; // detail_playlist_private
  String get playlistEmpty => zh ? '此播放列表为空。' : 'This playlist is empty.'; // detail_playlist_empty_hero
  String playlistTracks(int n) => zh ? '$n 首曲目' : (n == 1 ? '$n track' : '$n tracks'); // detail_playlist_tracks
  String durationHoursMinutes(int h, int m) => zh ? '$h 小时 $m 分钟' : '${h}h ${m}m'; // cmp_duration_hours_minutes
  String durationMinutes(int m) => zh ? '$m 分钟' : '${m}m'; // cmp_duration_minutes

  // The ▾ menu's page rows and their confirmations.
  String get menuPlayNext => zh ? '下一首播放' : 'Play next'; // detail_menu_play_next
  String get menuAddToQueue => zh ? '加入队列' : 'Add to queue'; // detail_menu_add_to_queue
  String get albumGoToArtist => zh ? '前往艺术家' : 'Go to artist'; // detail_album_go_to_artist

  /// detail_menu_queued (plurals).
  String menuQueued(int n) =>
      zh ? '已将 $n 首加入队列' : (n == 1 ? 'Added $n song to the queue' : 'Added $n songs to the queue');

  /// detail_menu_play_next_count (plurals).
  String menuPlayNextCount(int n) =>
      zh ? '接下来将播放 $n 首' : (n == 1 ? '$n song will play next' : '$n songs will play next');

  // The album hero's score emblem.
  String get emblemUnrated => zh ? '未评分' : 'Unrated'; // mem_emblem_unrated
  String get emblemCdUnrated => zh ? '未评分' : 'Not rated'; // mem_cd_emblem_unrated
  String get albumCdRate => zh ? '评分与评论' : 'Rate and comment'; // detail_album_cd_rate

  // The album's Last Play and rated coverage; the artist's Most Played.
  String get albumLastPlay => zh ? '上次播放' : 'Last Play'; // detail_album_last_play
  String get albumNever => zh ? '从未' : 'Never'; // detail_album_never
  String get relativeToday => zh ? '今天' : 'Today'; // detail_relative_today
  String get relativeYesterday => zh ? '昨天' : 'Yesterday'; // detail_relative_yesterday

  /// detail_relative_days_ago (plurals).
  String relativeDaysAgo(int n) => zh ? '$n 天前' : (n == 1 ? '$n day ago' : '$n days ago');

  /// Android getBestDateTimePattern(locale, "MMMd"): "Nov 3" / "11月3日".
  String monthDay(int month, int day) => zh ? '$month月$day日' : '${_months[month - 1]} $day';
  static const _months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

  String albumRated(int rated, int total) => zh ? '已评 $rated/$total' : '$rated/$total rated'; // detail_album_rated
  String get artistMostPlayed => zh ? '最常播放' : 'Most Played'; // detail_artist_most_played

  /// detail_artist_plays_total / detail_artist_plays (plurals, same words).
  String artistPlays(int n) => zh ? '$n 次播放' : (n == 1 ? '$n play' : '$n plays');

  // The album's page 2, the scrapbook, and its page dots.
  String get albumPageOverview => zh ? '概览' : 'Overview'; // detail_album_page_overview
  String get albumPageScrapbook => zh ? '剪贴簿' : 'Scrapbook'; // detail_album_page_scrapbook
  String albumPageN(int n) => zh ? '第 $n 页' : 'Page $n'; // detail_album_page_n
  String get scrapCdRate => zh ? '评分与评论' : 'Rate and comment'; // detail_scrap_cd_rate
  String get scrapCdEditComment => zh ? '编辑评论' : 'Edit comment'; // detail_scrap_cd_edit_comment
  String get scrapCdWriteComment => zh ? '写评论' : 'Write a comment'; // detail_scrap_cd_write_comment
  String get scrapNotYet => zh ? '暂无内容' : 'Nothing yet'; // detail_scrap_not_yet
  String get scrapCdPlaying => zh ? '正在播放' : 'Playing'; // detail_scrap_cd_playing
  String scrapCdNote(String text) => zh ? '笔记：$text' : 'Note: $text'; // detail_scrap_cd_note
  String scrapCdNoteAt(String at, String text) =>
      zh ? '$at 的笔记：$text' : 'Note at $at: $text'; // detail_scrap_cd_note_at

  /// detail_scrap_hidden_notes (plurals).
  String scrapHiddenNotes(int n) => zh ? '+$n 条笔记' : (n == 1 ? '+$n note' : '+$n notes');

  // Page 2's Ask / About cards and fact tags (strings_detail detail_scrap_*).
  String get scrapAbout => zh ? '关于' : 'About'; // detail_scrap_about
  String get scrapReadAll => zh ? '阅读全部' : 'Read all'; // detail_scrap_read_all
  String get scrapCdReadAll => zh ? '阅读全部' : 'Read all'; // detail_scrap_cd_read_all
  String get scrapQ => 'Q'; // detail_scrap_q (untranslatable)
  String scrapCdAsk(String track, String question) =>
      zh ? '关于 $track 的提问：$question' : 'Asked about $track: $question'; // detail_scrap_cd_ask
  String scrapCdAbout(String track, String line) =>
      zh ? '关于 $track：$line' : 'About $track: $line'; // detail_scrap_cd_about
  String scrapFactMore(String value, int n) => '$value  +$n'; // detail_scrap_fact_more

  /// A fact's label (detail_scrap_fact_created … detail_scrap_fact_about).
  String scrapFact(String key) => switch (key) {
    'creation_time' => zh ? '创作时间' : 'Created',
    'creation_location' => zh ? '录制地点' : 'Recorded in',
    'lyricist' => zh ? '作词' : 'Lyricist',
    'composer' => zh ? '作曲' : 'Composer',
    'producer' => zh ? '制作人' : 'Producer',
    'review' => zh ? '关于' : 'About',
    _ => key,
  };

  // The track sheet (AlbumScrapbookTrackSheet, detail_scrap_*).
  String get scrapCdManageTrack => zh ? '评分和编辑' : 'Score and edit'; // detail_scrap_cd_manage_track
  String get scrapTabAbout => zh ? '关于' : 'About'; // detail_scrap_tab_about
  String scrapTabNotes(int n) => n == 0
      ? (zh ? '笔记' : 'Note') // detail_scrap_tab_notes_none
      : (zh ? '笔记 · $n' : 'Note · $n'); // detail_scrap_tab_notes
  String get scrapRate => zh ? '打分' : 'Rate'; // detail_scrap_rate
  String get scrapRateHint => zh ? '点按或拖动来打分' : 'Tap or slide to rate'; // detail_scrap_rate_hint
  String get scrapNotRated => zh ? '未评分' : 'Not rated'; // detail_scrap_not_rated
  String scrapCdScore(String score) => zh ? '评分 $score' : 'Score $score'; // detail_scrap_cd_score
  String get scrapCdScoreOpen => zh ? '评分条已展开' : 'Score bar open'; // detail_scrap_cd_score_open
  String get scrapCdScoreClosed => zh ? '点按修改评分' : 'Tap to change the score'; // detail_scrap_cd_score_closed
  String get scrapClear => zh ? '清除评分' : 'Clear rating'; // detail_scrap_clear
  String get scrapScoreCleared => zh ? '已清除评分' : 'Rating cleared'; // detail_scrap_score_cleared
  String get scrapAboutEmpty => zh ? '这首歌还没有资料。' : 'Nothing about this song yet.'; // detail_scrap_about_empty
  String get scrapAboutGenerate => zh ? '让 Gemini 生成' : 'Generate with Gemini'; // detail_scrap_about_generate
  String get scrapAboutGenerating => zh ? 'Gemini 正在写…' : 'Gemini is writing…'; // detail_scrap_about_generating
  String scrapAsked(String q) => zh ? '问：$q' : 'Asked: $q'; // detail_scrap_asked
  String get scrapByGemini => 'Gemini'; // detail_scrap_by_gemini
  String get scrapByYou => zh ? '我写的' : 'Written by you'; // detail_scrap_by_you
  String get scrapCdEdit => zh ? '编辑' : 'Edit'; // detail_scrap_cd_edit
  String get scrapEditTitleHint => zh ? '标题' : 'Title'; // detail_scrap_edit_title_hint
  String get scrapEditTextHint => zh ? '留空即删除' : 'Empty removes it'; // detail_scrap_edit_text_hint
  String get scrapDelete => zh ? '删除' : 'Delete'; // detail_scrap_delete
  String get scrapRegenerate => zh ? '重新生成' : 'Regenerate'; // detail_scrap_regenerate
  String get scrapDone => zh ? '完成' : 'Done'; // detail_scrap_done
  String get scrapDeleted => zh ? '已删除' : 'Deleted'; // detail_scrap_deleted
  String scrapDeletedNamed(String name) => zh ? '已删除「$name」' : 'Deleted “$name”'; // detail_scrap_deleted_named
  String get scrapRegenerated => zh ? '已重新生成' : 'Regenerated'; // detail_scrap_regenerated
  String get scrapUndo => zh ? '撤销' : 'Undo'; // detail_scrap_undo
  String get scrapNewTitleHint =>
      zh ? '写个标题，比如：这首歌在讲什么' : 'A title, like: what is this song about'; // detail_scrap_new_title_hint
  String get scrapNewTextHint => zh ? '写下你知道的…' : 'Write what you know…'; // detail_scrap_new_text_hint
  String get scrapAskGemini => zh ? '问 Gemini' : 'Ask Gemini'; // detail_scrap_ask_gemini
  String get scrapWrite => zh ? '输入' : 'Write'; // detail_scrap_write
  String get scrapSave => zh ? '保存' : 'Save'; // detail_scrap_save
  String get scrapGeminiFailed =>
      zh ? 'Gemini 没能回答，再试一次' : "Gemini couldn't answer. Try again."; // detail_scrap_gemini_failed
  String get scrapGeminiNothing => zh ? 'Gemini 没有新的内容' : 'Gemini had nothing new'; // detail_scrap_gemini_nothing
  String get scrapApiKeyMissing => zh ? '缺少 Gemini API 密钥' : 'Gemini API key missing'; // np_ask_api_key_missing
  String get scrapExpandLyrics => zh ? '展开全部歌词' : 'All lyrics'; // detail_scrap_expand_lyrics
  String get scrapCollapseLyrics => zh ? '只看笔记' : 'Notes only'; // detail_scrap_collapse_lyrics
  String get scrapLyricsLoading => zh ? '正在加载歌词…' : 'Loading lyrics…'; // detail_scrap_lyrics_loading
  String get scrapNotesEmpty => zh ? '还没有笔记。' : 'No notes yet.'; // detail_scrap_notes_empty
  String get scrapNewNote => zh ? '新建笔记' : 'New note'; // detail_scrap_new_note
  String scrapPlayFrom(String at) => zh ? '从 $at 播放' : 'Play from $at'; // detail_scrap_play_from
  String scrapPlayingFrom(String at) => zh ? '从 $at 开始播放' : 'Playing from $at'; // detail_scrap_playing_from
  String get scrapCdPlay => zh ? '播放' : 'Play'; // detail_scrap_cd_play
  String get scrapEditNote => zh ? '修改' : 'Edit'; // detail_scrap_edit_note
  String get scrapNoteDeleted => zh ? '已删除笔记' : 'Note deleted'; // detail_scrap_note_deleted
  String scrapNoteHint(String at) => zh ? '在 $at 记一笔…' : 'A note at $at…'; // detail_scrap_note_hint
  String get scrapNoteHintPlain => zh ? '记一笔…' : 'A note…'; // detail_scrap_note_hint_plain
  String get scrapCancel => zh ? '取消' : 'Cancel'; // detail_scrap_cancel

  /// detail_unit_play (plurals).
  String unitPlay(int n) => zh ? '次' : (n == 1 ? 'play' : 'plays');

  /// detail_scrap_cd_track / _rated / _liked / _plays and their combinations.
  String scrapCdTrack(int n, String title, {String? score, bool liked = false, int plays = 0}) {
    final parts = <String>[
      zh ? '第 $n 首' : 'Track $n',
      title,
      if (score != null) zh ? '评分 $score' : 'rated $score',
      if (liked) zh ? '已喜欢' : 'liked',
      if (plays > 0) zh ? '播放 $plays 次' : 'played $plays times',
    ];
    return parts.join(zh ? '，' : ', ');
  }
}
