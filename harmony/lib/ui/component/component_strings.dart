import 'package:flutter/widgets.dart';

/// Strings of the shared sheets (queue editing, the song sheet, Add to
/// playlist, the playlist dialogs), English and Simplified Chinese, copied
/// from Android res/values{,-zh-rCN}/strings*.xml (key in the comment).
class ComponentStrings {
  const ComponentStrings._(this.zh);

  final bool zh;

  static ComponentStrings of(BuildContext context) =>
      ComponentStrings._(Localizations.maybeLocaleOf(context)?.languageCode == 'zh');

  static const en = ComponentStrings._(false);

  /// The Explicit "E" after a title, for the screen reader (ExplicitBadge).
  String get explicitBadge => zh ? '含不雅内容' : 'Explicit'; // cmp_explicit_badge_cd

  // Queue sheet editing (strings_component.xml).
  String get keepScreenOn => zh ? '屏幕常亮' : 'Keep screen on'; // np_keep_screen_on
  String get queueClear => zh ? '清除' : 'Clear'; // cmp_queue_clear
  String get queueHintShuffle =>
      zh ? '随机播放已开启：关掉随机后才能调整顺序。' : 'Shuffle is on: turn it off to reorder songs.'; // cmp_queue_hint_shuffle
  String get queueCdRemove => zh ? '从队列移除' : 'Remove from queue'; // cmp_queue_cd_remove
  String queueCdMove(String title) => zh ? '移动 $title' : 'Move $title'; // cmp_queue_cd_move

  // The song sheet (strings_detail.xml, strings_stream_quality.xml).
  String get playNext => zh ? '下一首播放' : 'Play next'; // detail_menu_play_next
  String get addToQueue => zh ? '加入队列' : 'Add to queue'; // detail_menu_add_to_queue
  String get addToPlaylist => zh ? '添加到播放列表' : 'Add to playlist'; // detail_menu_add_to_playlist
  String get favorite => zh ? '收藏' : 'Favorite'; // track_menu_favorite
  String get favorited => zh ? '已收藏' : 'Favorited'; // track_menu_favorited
  String get goToAlbum => zh ? '前往专辑' : 'Go to album'; // track_menu_go_to_album
  String get goToArtist => zh ? '前往艺术家' : 'Go to artist'; // track_menu_go_to_artist
  String goToNamedArtist(String name) => zh ? '前往 $name' : 'Go to $name'; // track_menu_go_to_named_artist
  String get share => zh ? '分享' : 'Share'; // track_menu_share
  String get viewInScrapbook => zh ? '在剪贴簿里查看' : 'View in Scrapbook'; // track_menu_view_in_scrapbook

  /// detail_menu_queued (plurals).
  String queued(int n) => zh ? '已将 $n 首加入队列' : (n == 1 ? 'Added $n song to the queue' : 'Added $n songs to the queue');

  /// detail_menu_play_next_count (plurals).
  String playNextCount(int n) => zh ? '接下来将播放 $n 首' : (n == 1 ? '$n song will play next' : '$n songs will play next');

  String get couldntSaveFavorite => zh ? '无法保存到收藏' : "Couldn't save to favorites"; // np_msg_couldnt_save_favorite
  String get couldntRemoveFavorite =>
      zh ? '无法从收藏移除' : "Couldn't remove from favorites"; // np_msg_couldnt_remove_favorite

  // Add to playlist (strings_component.xml, strings_nowplaying.xml).
  String get playlistSheetTitle => zh ? '添加到播放列表' : 'Add to playlist'; // cmp_playlist_sheet_title
  String get playlistCreateRow => zh ? '新建播放列表…' : 'Create new playlist…'; // cmp_playlist_create_row
  String get playlistNewTitle => zh ? '新建播放列表' : 'New playlist'; // cmp_playlist_new_title
  String get playlistNameLabel => zh ? '名称' : 'Name'; // cmp_playlist_name_label
  String get playlistCreate => zh ? '创建' : 'Create'; // cmp_playlist_create
  String get playlistCancel => zh ? '取消' : 'Cancel'; // cmp_playlist_cancel

  /// cmp_playlist_song_count (plurals).
  String playlistSongCount(int n) => zh ? '$n 首' : (n == 1 ? '$n song' : '$n songs');
  String get addedToPlaylist => zh ? '已添加到播放列表' : 'Added to playlist'; // np_msg_added_to_playlist
  String get couldntAddToPlaylist => zh ? '无法添加到播放列表' : "Couldn't add to playlist"; // np_msg_couldnt_add_to_playlist
  String couldntCreate(String name) => zh ? '无法创建“$name”' : 'Couldn\'t create "$name"'; // np_msg_couldnt_create

  // Library: New playlist (strings_library.xml).
  String get libraryPlaylistFab => zh ? '新建播放列表' : 'New playlist'; // library_playlist_fab
  String libraryPlaylistCreated(String name) => zh ? '已创建“$name”' : 'Created "$name"'; // library_playlist_created
  String libraryPlaylistCreateFailed(String name) =>
      zh ? '无法创建“$name”' : 'Couldn\'t create "$name"'; // library_playlist_create_failed

  // Playlist page: rename and delete (strings_detail.xml).
  String get playlistRename => zh ? '重命名' : 'Rename'; // detail_playlist_rename
  String get playlistDeleteMenu => zh ? '删除' : 'Delete'; // detail_playlist_delete_menu
  String get playlistRenameTitle => zh ? '重命名播放列表' : 'Rename playlist'; // detail_playlist_rename_title
  String get playlistName => zh ? '名称' : 'Name'; // detail_playlist_name
  String get playlistRenameConfirm => zh ? '重命名' : 'Rename'; // detail_playlist_rename_confirm
  String get playlistCancelRename => zh ? '取消' : 'Cancel'; // detail_playlist_cancel_rename
  String get playlistRenamed => zh ? '已重命名' : 'Renamed'; // detail_playlist_renamed
  String get playlistErrorRename => zh ? '无法重命名播放列表' : "Couldn't rename playlist"; // detail_playlist_error_rename
  String get playlistDeleteTitle => zh ? '删除播放列表？' : 'Delete playlist?'; // detail_playlist_delete_title
  String playlistDeleteBody(String name) =>
      zh ? '“$name”将从你的资料库中移除。' : '"$name" will be removed from your library.'; // detail_playlist_delete_body
  String get playlistDelete => zh ? '删除' : 'Delete'; // detail_playlist_delete
  String get playlistCancelDelete => zh ? '取消' : 'Cancel'; // detail_playlist_cancel_delete
  String get playlistDeleted => zh ? '已删除播放列表' : 'Playlist deleted'; // detail_playlist_deleted
  String get playlistErrorDelete => zh ? '无法删除播放列表' : "Couldn't delete playlist"; // detail_playlist_error_delete
  String get playlistErrorRemove => zh ? '无法移除曲目' : "Couldn't remove track"; // detail_playlist_error_remove
  String get removeFromPlaylist => zh ? '从播放列表移除' : 'Remove from playlist'; // track_menu_remove_from_playlist
}
