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
}
