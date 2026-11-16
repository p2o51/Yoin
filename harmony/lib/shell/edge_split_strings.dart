import 'package:flutter/widgets.dart';

/// The edge-split capsules, their upright pill and Play split: English and
/// Simplified Chinese from Android res/values{,-zh-rCN}/strings_component.xml
/// (key in the comment).
class EdgeSplitStrings {
  const EdgeSplitStrings._(this._zh);

  final bool _zh;

  static EdgeSplitStrings of(BuildContext context) =>
      EdgeSplitStrings._(Localizations.maybeLocaleOf(context)?.languageCode == 'zh');

  String get cdHome => _zh ? '首页' : 'Home'; // cmp_edge_cd_home
  String get cdLibrary => _zh ? '资料库' : 'Library'; // cmp_edge_cd_library
  String get cdDone => _zh ? '完成' : 'Done'; // cmp_edge_cd_done

  String get pillNothingPlaying => _zh ? '未在播放' : 'Nothing playing'; // cmp_pill_vertical_nothing_playing
  String get pillPlaybackUnavailable => _zh ? '无法播放' : 'Playback unavailable'; // cmp_pill_vertical_playback_unavailable
  String get pillSongOptions => _zh ? '歌曲选项' : 'Song options'; // np_cd_cover_menu

  String get playCd => _zh ? '播放' : 'Play'; // cmp_play_vertical_cd
  String get playCdMore => _zh ? '更多播放选项' : 'More play options'; // cmp_play_vertical_cd_more
  String get playCdShuffle => _zh ? '随机播放' : 'Shuffle play'; // cmp_play_vertical_cd_shuffle
}
