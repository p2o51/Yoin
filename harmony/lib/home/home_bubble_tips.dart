/// What Home's bubble says besides Memories (Android ui/home/HomeBubbleSay.kt
/// HomeBubbleTip): a short tip — a gesture the app hides, or a detail about
/// it. Each opens on its own page (lib/tips/tip_screen.dart) with a demo.
///
/// Only tips whose gesture or feature exists in this build are listed, in
/// Android's order. Copy: Android's (strings_home.xml, key in the comment)
/// where it holds on HarmonyOS; the rest was written for HarmonyOS by the
/// owner's copy model (Gemini Carbon, .argon/harmony-tips-1116) and is used
/// as given.
library;

import 'dart:io' show Platform;

import 'package:flutter/widgets.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

/// The tips, in the order the bubble goes through them (Android's enum
/// order, HarmonyOS's own first). The bubble shows the [title]; readers hear
/// the [text]. [updated] is the day the tip last changed what it shows: a
/// tip read before that comes back unread.
enum HomeBubbleTip {
  lyricsTools(YoinSymbols.lyrics, (2026, 11, 16)),
  lyricsSelect(YoinSymbols.check, (2026, 11, 16)),
  memoriesDiary(YoinSymbols.article, (2026, 11, 16)),
  cloudSync(YoinSymbols.cloud, (2026, 11, 16)),
  welcomeGuide(YoinSymbols.refresh, (2026, 11, 16)),
  keyboardShortcuts(YoinSymbols.keyboard, (2026, 11, 13)),
  volumeLeveling(YoinSymbols.volumeUp, (2026, 11, 13)),
  homeScreenWidgets(YoinSymbols.library, (2026, 11, 13), desktop: false),
  libraryRefresh(YoinSymbols.refresh, (2026, 11, 13)),
  memoryLedger(YoinSymbols.memory, (2026, 11, 12)),
  aiServices(YoinSymbols.sparkle, (2026, 11, 13)),
  accountQuickSwitch(YoinSymbols.person, (2026, 11, 6)),
  miniPlayerMenu(YoinSymbols.playCircle, (2026, 11, 16)),
  focusCover(YoinSymbols.openInFull, (2026, 11, 3)),
  downloads(YoinSymbols.download, (2026, 11, 4)),
  editHome(YoinSymbols.dragHandle, (2026, 10, 17)),
  pullForMemories(YoinSymbols.memory, (2026, 10, 17)),
  coverMenu(YoinSymbols.album, (2026, 11, 16)),
  songMenu(YoinSymbols.queue, (2026, 11, 16)),
  playModes(YoinSymbols.shuffle, (2026, 10, 21)),
  noteMoments(YoinSymbols.editNote, (2026, 10, 17)),
  librarySearch(YoinSymbols.search, (2026, 11, 16)),
  keepScreenOn(YoinSymbols.lightMode, (2026, 10, 17)),
  name(YoinSymbols.sparkle, (2026, 10, 17));

  const HomeBubbleTip(this.icon, this._day, {this.desktop = true});

  final IconData icon;
  final (int, int, int) _day;

  /// False: the feature isn't there on a desktop build (Windows has no service widgets).
  final bool desktop;

  DateTime get updated => DateTime(_day.$1, _day.$2, _day.$3);

  /// The tips this build can use, in the bubble's order (Android HomeBubbleTip.available).
  static List<HomeBubbleTip> available([String? operatingSystem]) {
    final os = operatingSystem ?? Platform.operatingSystem;
    final desktopHost = os == 'windows' || os == 'linux' || os == 'macos';
    return [
      for (final tip in values)
        if (tip.desktop || !desktopHost) tip,
    ];
  }

  String title({required bool zh}) => switch (this) {
    lyricsTools => zh ? '歌词工具' : 'Lyrics tools', // Carbon 2026-11-16
    lyricsSelect => zh ? '选择与编辑歌词' : 'Select and edit lyrics', // Carbon 2026-11-16
    memoriesDiary => zh ? '回忆日记' : 'Memory diary', // Carbon 2026-11-16
    cloudSync => zh ? '云同步' : 'Cloud sync', // Carbon 2026-11-16
    welcomeGuide => zh ? '欢迎向导' : 'Welcome guide', // Carbon 2026-11-16
    keyboardShortcuts => zh ? '键盘快捷键' : 'Keyboard shortcuts', // home_tip_keyboard_shortcuts_title
    volumeLeveling => zh ? '音量平衡' : 'Volume leveling', // home_tip_volume_leveling_title
    homeScreenWidgets => zh ? '服务卡片' : 'Service widgets', // Carbon 2026-11-16 (Android: home_tip_widgets_title)
    libraryRefresh => zh ? '刷新资料库' : 'Refresh Library', // home_tip_library_refresh_title
    memoryLedger => zh ? '全部回忆' : 'All Memories', // home_tip_memory_ledger_title
    aiServices => zh ? 'AI 功能' : 'AI features', // home_tip_ai_services_title
    accountQuickSwitch => zh ? '切换账号' : 'Switch accounts', // home_tip_account_quick_switch_title
    miniPlayerMenu => zh ? '迷你播放器菜单' : 'Mini player options', // home_tip_mini_player_menu_title
    focusCover => zh ? '轻点封面聚焦' : 'Tap cover to Focus', // home_tip_focus_cover_title
    downloads => zh ? '离线下载' : 'Offline downloads', // home_tip_downloads_title
    editHome => zh ? '重新排版首页' : 'Rearrange Home', // home_tip_edit_home_title
    pullForMemories => 'Memories', // home_tip_pull_memories_title
    coverMenu => zh ? '歌曲菜单' : 'Song options', // home_tip_cover_menu_title
    songMenu => zh ? '长按歌曲操作' : 'Song quick actions', // home_tip_song_menu_title
    playModes => zh ? '随机与循环' : 'Shuffle and repeat', // home_tip_play_modes_title
    noteMoments => zh ? '笔记记住那一秒' : 'Notes pin the second', // home_tip_note_moments_title
    librarySearch => zh ? '快速搜索' : 'Quick search', // home_tip_library_search_title
    keepScreenOn => zh ? '屏幕常亮' : 'Keep screen on', // home_tip_keep_screen_on_title
    name => zh ? '名字的由来' : 'Behind the name', // home_tip_name_title
  };

  String text({required bool zh}) => switch (this) {
    // Carbon 2026-11-16.
    lyricsTools =>
      zh
          ? '正在播放里展开歌词，底部可从 QQ 音乐、网易云、LRCLIB 等搜索并应用歌词、显示歌词源或 Gemini 翻译（再点隐藏），或点 ✓ 选词；播放 3 秒后按钮会收起，轻点重新显示'
          : 'Expand Lyrics in Now Playing to search and apply lyrics from QQ Music, NetEase, LRCLIB, and more, show '
                'source or Gemini translations (tap again to hide), or tap ✓ to select; while playing, the tools hide '
                'after 3 s and return on a tap',
    // Carbon 2026-11-16.
    lyricsSelect =>
      zh
          ? '展开歌词后点 ✓，点选最多 15 行可复制、分享为图片卡片，或以 LRC 格式修改词句与时间轴并应用；没有歌词时点同一按钮可添加歌词'
          : 'In expanded Lyrics, tap ✓ and tap up to 15 lines to copy them, share them as an image card, or edit lines '
                'and timing as LRC; when there are no lyrics, the same button adds them',
    // Carbon 2026-11-16.
    memoriesDiary =>
      zh
          ? '在首页下拉打开 Memories，点卡片上的「日记」展开日记；往下拖顶栏或在日记顶部下拉回到卡片，在卡片上往上滑回到首页'
          : 'Pull down on Home to open Memories and tap Diary on a card to expand its diary; drag the top bar down or '
                'pull down from the top of the diary to return to the card, or swipe up on a card to return to Home',
    // Carbon 2026-11-16.
    cloudSync =>
      zh
          ? '在设置 › 同步与存储 › 云同步中登录 Google，通过你自己的 Google Drive 在 HarmonyOS 与 Android 设备间同步笔记、评分、评论、歌词翻译、首页布局与设置；密码、API 密钥与播放记录留在本地'
          : 'Sign in with Google in Settings › Sync and storage › Cloud sync to keep notes, ratings, reviews, lyric '
                'translations, Home layout, and settings in sync across HarmonyOS and Android via your Google Drive; '
                'passwords, API keys, and listening history stay on each device',
    // Carbon 2026-11-16.
    welcomeGuide =>
      zh
          ? '随时可在设置 › 帮助与关于 › 欢迎向导中重新打开首次引导，选择音乐服务并连接'
          : 'Reopen the first-run setup guide any time in Settings › Help and about › Welcome guide to choose a service '
                'and connect',
    // Carbon 2026-11-16 (Android's home_tip_keyboard_shortcuts names a list HarmonyOS doesn't have).
    keyboardShortcuts =>
      zh
          ? '按空格播放或暂停，Ctrl+← 或 Ctrl+→ 切歌，正在播放里按 ← 或 → 快退快进 5 秒，Ctrl+K 搜索，Ctrl+Shift+P 开关正在播放'
          : 'Press Space to play or pause, Ctrl+← or Ctrl+→ to skip, ← or → in Now Playing to seek 5 s, Ctrl+K to '
                'search, and Ctrl+Shift+P to open or close Now Playing',
    // home_tip_volume_leveling
    volumeLeveling =>
      zh
          ? '设置 › 点开 Subsonic 账号 › 音量平衡，按曲目或按专辑开启 ReplayGain，让歌曲音量一致'
          : 'In Settings › your Subsonic account › Volume leveling, turn on ReplayGain by track or by album to keep '
                'volume consistent',
    // Carbon 2026-11-16 (HarmonyOS service widgets; Android: home_tip_widgets).
    homeScreenWidgets =>
      zh
          ? '在桌面长按或上滑 Yoin 图标，选择「服务卡片」，挑选 1×2、2×2 或 2×4 的「播放」或「Memories」卡片添加到桌面'
          : "Long-press or swipe up on Yoin's icon on the home screen, choose Service widgets, and add the Play or "
                'Memory widget in 1×2, 2×2, or 2×4',
    // home_tip_library_refresh
    libraryRefresh => zh ? '在资料库顶部往下拉，刷新正在看的内容' : 'Pull down at the top of Library to refresh the current view',
    // Carbon 2026-11-16 (home_tip_memory_ledger without the pixel bar, which isn't built here).
    memoryLedger =>
      zh
          ? '点首页的「全部回忆」，在日历格点一格只看那段时间；下方右侧可切换时间线、卡片或可排序的列表，大屏上时间线和卡片排成 2–3 列'
          : 'Tap All Memories on Home and tap a square on the grid to see just that time; switch between Timeline, '
                'Cards, and a sortable List below on the right, with Timeline and Cards in 2–3 columns on large screens',
    // Carbon 2026-11-16 (Gemini only on HarmonyOS; Android: home_tip_ai_services).
    aiServices =>
      zh
          ? '在设置 › AI 功能中粘贴 Gemini API 密钥，选择「好」或「最佳」档位与回答语言，用于歌曲信息、「问 Gemini」、歌词翻译与 Memories 标题'
          : 'Paste a Gemini API key in Settings › AI features and pick the Good or Best tier and answer language to '
                'power song info, Ask Gemini, lyrics translation, and Memory titles',
    // home_tip_account_quick_switch
    accountQuickSwitch => zh ? '长按头像并滑动以切换账号' : 'Long-press the avatar and slide to switch accounts',
    // home_tip_mini_player_menu
    miniPlayerMenu => zh ? '长按底栏的迷你播放器，直接打开当前歌曲菜单' : 'Long-press the mini player in the bar to open Song options',
    // home_tip_focus_cover
    focusCover =>
      zh ? '正在播放里轻点封面进入聚焦，再点一下回到原样' : 'Tap the cover in Now Playing to enter Focus, and tap again to return',
    // home_tip_downloads
    downloads =>
      zh
          ? 'Subsonic 账号可在歌曲、专辑或歌单菜单里下载，管理在设置 › 账号 › 当前账号 › 存储 › 下载'
          : 'Subsonic accounts can download from song, album, or playlist menus; manage in Settings › Accounts › your '
                'active account › Storage › Downloads',
    // home_tip_edit_home
    editHome => zh ? '长按首页，重新排版' : 'Long-press Home to rearrange sections',
    // home_tip_pull_memories
    pullForMemories => zh ? '首页往下拉，打开 Memories' : 'Pull down on Home to open Memories',
    // home_tip_cover_menu
    coverMenu =>
      zh ? '正在播放里长按封面，打开歌曲菜单与快捷操作' : 'Long-press the cover in Now Playing for Song options and quick actions',
    // home_tip_song_menu (the song menu has Share now, as on Android).
    songMenu =>
      zh
          ? '长按专辑、歌单或资料库里的歌，下一首播放、加入队列、收藏或分享'
          : 'Long-press a song in an album, playlist, or Library to play next, queue, favorite, or share',
    // home_tip_play_modes
    playModes =>
      zh
          ? '点按切换随机与循环；长按拆成两个按钮，单独开关循环'
          : 'Tap to switch shuffle and repeat; long-press to split them and toggle repeat off',
    // home_tip_note_moments
    noteMoments => zh ? '在正在播放写的笔记，会钉在那一秒' : 'Notes written in Now Playing stay pinned to that exact second',
    // home_tip_library_search
    librarySearch => zh ? '长按底栏的资料库，直接搜索' : 'Long-press Library in the bar to search right away',
    // home_tip_keep_screen_on
    keepScreenOn => zh ? '屏幕常亮在封面的长按菜单里' : "Keep screen on is in the cover's long-press menu",
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
