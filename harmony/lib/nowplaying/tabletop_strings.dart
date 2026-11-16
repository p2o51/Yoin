import 'package:flutter/widgets.dart';

/// The kickstand (Tabletop) Now Playing: English and Simplified Chinese from
/// Android res/values{,-zh-rCN}/strings_nowplaying.xml (key in the comment).
class TabletopStrings {
  const TabletopStrings._(this._zh);

  final bool _zh;

  static TabletopStrings of(BuildContext context) =>
      TabletopStrings._(Localizations.maybeLocaleOf(context)?.languageCode == 'zh');

  String get cdClose => _zh ? '关闭' : 'Close'; // np_cd_tabletop_close
  String get cdExpandLyrics => _zh ? '展开歌词' : 'Expand lyrics'; // np_cd_expand_lyrics
  String get cdCollapseLyrics => _zh ? '收起歌词' : 'Collapse lyrics'; // np_cd_collapse_lyrics
}
