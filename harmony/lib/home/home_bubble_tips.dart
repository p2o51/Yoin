/// What Home's bubble says besides Memories (Android ui/home/HomeBubbleSay.kt
/// HomeBubbleTip): a short tip — a gesture the app hides, or a detail about
/// it. Only tips whose gesture or feature exists in this build are listed;
/// the copy is Android's, strings_home.xml (key in the comment).
library;

import 'package:flutter/widgets.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

/// The tips, in the order the bubble goes through them (Android's enum order).
/// The bubble shows the [title]; readers hear the [text].
enum HomeBubbleTip {
  libraryRefresh(YoinSymbols.refresh),
  downloads(YoinSymbols.download),
  editHome(YoinSymbols.dragHandle),
  pullForMemories(YoinSymbols.memory),
  playModes(YoinSymbols.shuffle),
  noteMoments(YoinSymbols.editNote),
  name(YoinSymbols.sparkle);

  const HomeBubbleTip(this.icon);

  final IconData icon;

  String title({required bool zh}) => switch (this) {
    libraryRefresh => zh ? '刷新资料库' : 'Refresh Library', // home_tip_library_refresh_title
    downloads => zh ? '离线下载' : 'Offline downloads', // home_tip_downloads_title
    editHome => zh ? '重新排版首页' : 'Rearrange Home', // home_tip_edit_home_title
    pullForMemories => 'Memories', // home_tip_pull_memories_title
    playModes => zh ? '随机与循环' : 'Shuffle and repeat', // home_tip_play_modes_title
    noteMoments => zh ? '笔记记住那一秒' : 'Notes pin the second', // home_tip_note_moments_title
    name => zh ? '名字的由来' : 'Behind the name', // home_tip_name_title
  };

  String text({required bool zh}) => switch (this) {
    // home_tip_library_refresh
    libraryRefresh => zh ? '在资料库顶部往下拉，刷新正在看的内容' : 'Pull down at the top of Library to refresh the current view',
    // home_tip_downloads
    downloads =>
      zh
          ? 'Subsonic 账号可在歌曲、专辑或歌单菜单里下载，管理在设置 › 存储 › 下载'
          : 'Subsonic accounts can download from song, album, or playlist menus; manage in Settings › Storage › Downloads',
    // home_tip_edit_home
    editHome => zh ? '长按首页，重新排版' : 'Long-press Home to rearrange sections',
    // home_tip_pull_memories
    pullForMemories => zh ? '首页往下拉，打开 Memories' : 'Pull down on Home to open Memories',
    // home_tip_play_modes
    playModes =>
      zh
          ? '点按切换随机与循环；长按拆成两个按钮，单独开关循环'
          : 'Tap to switch shuffle and repeat; long-press to split them and toggle repeat off',
    // home_tip_note_moments
    noteMoments => zh ? '在正在播放写的笔记，会钉在那一秒' : 'Notes written in Now Playing stay pinned to that exact second',
    // home_tip_name
    name => zh ? 'Yoin 的名字来自日语「余韻」：余音不散' : 'Yoin comes from the Japanese word "余韻" — a lingering resonance',
  };
}

/// The bubble's own strings (strings_home.xml, key in the comment).
class MemoryBubbleStrings {
  const MemoryBubbleStrings({required this.zh});

  static MemoryBubbleStrings of(BuildContext context) =>
      MemoryBubbleStrings(zh: Localizations.maybeLocaleOf(context)?.languageCode == 'zh');

  final bool zh;

  String get title => 'Memories'; // home_memory_bubble_title
  String get arrowCd => 'Memories'; // home_cd_memories_arrow
  String get cdMemories => 'Memories'; // home_cd_bubble_memories

  /// home_cd_bubble_latest_by / home_cd_bubble_latest.
  String cdLatest(String album, String? artist) => artist == null
      ? (zh ? 'Memories。最新：$album' : 'Memories. Latest: $album')
      : (zh ? 'Memories。最新：$album，$artist' : 'Memories. Latest: $album by $artist');
}
