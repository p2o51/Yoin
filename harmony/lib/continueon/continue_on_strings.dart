import 'package:flutter/widgets.dart';

/// The Continue On card's words: English and Simplified Chinese, copied from
/// Android res/values{,-zh-rCN}/strings_continue_on.xml (key in the comment).
class ContinueOnStrings {
  const ContinueOnStrings._(this._zh);

  final bool _zh;

  static ContinueOnStrings of(BuildContext context) =>
      ContinueOnStrings._((Localizations.maybeLocaleOf(context)?.languageCode ?? 'en') == 'zh');

  String from(String device) => _zh ? '来自 $device' : 'From $device'; // continue_on_from
  String get otherDevice => _zh ? '另一台设备' : 'your other device'; // continue_on_other_device
  String get unknownSong => _zh ? '未知歌曲' : 'Unknown song'; // continue_on_unknown_song
  String get playing => _zh ? '正在播放' : 'Playing'; // continue_on_playing
  String get paused => _zh ? '已暂停' : 'Paused'; // continue_on_paused
  String get continueHere => _zh ? '在这里继续' : 'Continue here'; // continue_on_continue_here
  String get notNow => _zh ? '不用了' : 'Not now'; // continue_on_not_now
  String get ok => _zh ? '好' : 'OK'; // continue_on_ok

  // continue_on_no_account
  String get noAccount => _zh
      ? '这台设备还没有添加这个账号。在设置里添加后就能在这里继续。'
      : "This account isn't on this device yet. Add it in Settings to continue here.";

  String get failed => _zh ? '这台读不到这首歌。' : "Couldn't load this song here."; // continue_on_failed

  // continue_on_other_still_playing
  String get otherStillPlaying =>
      _zh ? '已在这台播放。另一台没有回应，可能还在放。' : "Playing here. Your other device didn't answer, so it may still be playing.";
}
