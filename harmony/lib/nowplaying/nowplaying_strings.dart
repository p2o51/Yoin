import 'package:flutter/widgets.dart';

/// Now Playing, the pill, the queue sheet and the bar's detail pose: English
/// and Simplified Chinese, copied from Android res/values{,-zh-rCN}/strings*.xml
/// (key in the comment). PLAY / PAUSE words are English-only on Android
/// (translatable="false"); other locales show the glyph instead.
class NowPlayingStrings {
  const NowPlayingStrings._(this._zh, this.isEnglish);

  final bool _zh;

  /// The transport shows the PLAY / PAUSE words only in English (NowPlayingScreen transport).
  final bool isEnglish;

  static NowPlayingStrings of(BuildContext context) {
    final code = Localizations.maybeLocaleOf(context)?.languageCode ?? 'en';
    return NowPlayingStrings._(code == 'zh', code == 'en');
  }

  // Bar + pill.
  String get pillTapOpen => _zh ? '打开播放器' : 'Open player'; // cmp_pill_tap_open
  String get pillNothingPlaying => _zh ? '未在播放' : 'Nothing playing'; // cmp_pill_nothing_playing
  String get pillPlaybackUnavailable => _zh ? '无法播放' : 'Playback unavailable'; // cmp_pill_playback_unavailable
  String get pillCurrentTrack => _zh ? '当前曲目' : 'Current track'; // cmp_pill_cd_current_track
  String get barCdHome => _zh ? '首页' : 'Home'; // cmp_bar_cd_home
  String get barCdLibrary => _zh ? '资料库' : 'Library'; // cmp_bar_cd_library

  // Detail pose Play split button.
  String get playLabel => _zh ? '播放' : 'Play'; // cmp_play_label
  String get playCdMore => _zh ? '更多播放选项' : 'More play options'; // cmp_play_cd_more
  String get playMenuShuffle => _zh ? '随机播放' : 'Shuffle play'; // cmp_play_menu_shuffle

  // Overlay.
  String get cdCloseNowPlaying => _zh ? '关闭正在播放' : 'Close Now Playing'; // np_cd_stage_close_now_playing
  String get cdBackNowPlaying => _zh ? '返回正在播放' : 'Back to Now Playing'; // np_cd_stage_back_now_playing

  // Top bar.
  String get fromAlbum => _zh ? '播放自专辑' : 'PLAYING FROM ALBUM'; // np_from_album
  String get fromPlaylist => _zh ? '播放自播放列表' : 'PLAYING FROM PLAYLIST'; // np_from_playlist
  String get fromArtist => _zh ? '播放自艺术家' : 'PLAYING FROM ARTIST'; // np_from_artist
  String get fromNowPlaying => _zh ? '正在播放' : 'NOW PLAYING'; // np_from_now_playing

  // Cover row.
  String get cdAlbumCover => _zh ? '专辑封面' : 'Album cover'; // np_cd_album_cover
  String get cdAddFavorite => _zh ? '加入收藏' : 'Add to favorites'; // np_cd_add_favorite
  String get cdRemoveFavorite => _zh ? '取消收藏' : 'Remove from favorites'; // np_cd_remove_favorite
  String ratingState(String value) => _zh ? '评分 $value / 10' : 'Rated $value out of 10'; // cmp_rating_state
  String get ratingUnrated => _zh ? '未评分' : 'Not rated'; // cmp_rating_state_unrated

  // Transport.
  String get pauseWord => 'PAUSE'; // np_transport_pause_word (not translatable)
  String get playWord => 'PLAY'; // np_transport_play_word (not translatable)
  String get cdPause => 'Pause'; // np_cd_transport_pause (not translatable)
  String get cdPlay => 'Play'; // np_cd_transport_play (not translatable)
  String get cdSkipPrevious => _zh ? '上一首' : 'Skip previous'; // np_cd_skip_previous
  String get cdSkipNext => _zh ? '下一首' : 'Skip next'; // np_cd_skip_next
  String get cdPlayMode => _zh ? '播放模式' : 'Play mode'; // np_cd_play_mode
  String get repeatAll => _zh ? '列表循环' : 'Repeat all'; // np_transport_repeat_all
  String get shuffle => _zh ? '随机播放' : 'Shuffle'; // np_transport_shuffle
  String get shuffleRepeatOne => _zh ? '随机播放，单曲循环' : 'Shuffle, repeat one'; // np_transport_shuffle_repeat_one
  String get repeatOne => _zh ? '单曲循环' : 'Repeat one'; // np_transport_repeat_one
  String get repeatOff => _zh ? '不循环' : 'Repeat off'; // np_transport_repeat_off
  String get shuffleRepeatOff => _zh ? '随机播放，不循环' : 'Shuffle, repeat off'; // np_transport_shuffle_repeat_off

  // Lyrics.
  String get lyricsEmpty => _zh ? '暂无歌词' : 'No lyrics available'; // cmp_lyrics_empty / np_lyrics_none
  String get cdReturnCurrentLine => _zh ? '回到当前句' : 'Return to current line'; // np_cd_return_current_line

  // Queue.
  String get pillQueue => _zh ? '队列' : 'Queue'; // np_pill_queue
  String get queueTitle => _zh ? '队列' : 'Queue'; // cmp_queue_title
  String get queuePlayingFrom => _zh ? '播放自' : 'Playing from'; // cmp_queue_playing_from
  String get queueSectionNow => _zh ? '正在播放' : 'Now playing'; // cmp_queue_section_now
  String get queueSectionQueued => _zh ? '队列中接下来' : 'Next in queue'; // cmp_queue_section_queued
  String queueNextFrom(String name) =>
      _zh ? '接下来：$name' : 'Next from: $name'; // cmp_queue_next_from_album / _playlist / _artist
  String get queueNextUp => _zh ? '接下来' : 'Next up'; // cmp_queue_next_up
  String get queueCdPlaying => _zh ? '正在播放' : 'Playing'; // cmp_queue_cd_playing
  String get queueCdPaused => _zh ? '已暂停' : 'Paused'; // cmp_queue_cd_paused

  // Idle.
  String get emptyNothingPlaying => _zh ? '未在播放' : 'Nothing playing'; // np_empty_nothing_playing
}
