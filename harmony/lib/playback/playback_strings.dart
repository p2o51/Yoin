import 'package:flutter/widgets.dart';

import 'now_playing_state.dart';
import 'playback_controller.dart';

/// Playback-chain strings, English and Simplified Chinese, copied from
/// Android res/values{,-zh-rCN}/strings*.xml (key in the comment). Same
/// pattern as YoinStrings.
class PlaybackStrings {
  const PlaybackStrings._(this.zh);

  final bool zh;

  static PlaybackStrings of(BuildContext context) =>
      PlaybackStrings._(Localizations.maybeLocaleOf(context)?.languageCode == 'zh');

  static const en = PlaybackStrings._(false);
  static const zhHans = PlaybackStrings._(true);

  String get playFailed => zh ? '无法播放此歌曲' : 'Unable to play this song'; // player_play_failed
  String get initFailed => zh ? '无法初始化播放' : 'Unable to initialize playback'; // player_init_failed
  String get playbackInterrupted => zh ? '播放已中断。' : 'Playback was interrupted.'; // np_msg_playback_interrupted
  String get couldntStart => zh ? '无法开始播放。' : "Couldn't start playback."; // np_msg_couldnt_start
  String get couldntSaveFavorite => zh ? '无法保存到收藏' : "Couldn't save to favorites"; // np_msg_couldnt_save_favorite
  String get couldntRemoveFavorite =>
      zh ? '无法从收藏移除' : "Couldn't remove from favorites"; // np_msg_couldnt_remove_favorite

  String get snackbarDismiss => zh ? '关闭' : 'Dismiss'; // cmp_snackbar_dismiss

  /// What to show for [kind] (never the engine's raw text).
  String errorMessage(PlaybackErrorKind kind) => switch (kind) {
        PlaybackErrorKind.interrupted => playbackInterrupted,
        PlaybackErrorKind.playFailed => playFailed,
      };

  /// The failure toast for a heart write that tried to set [favorite].
  String favoriteFailure({required bool favorite}) => favorite ? couldntSaveFavorite : couldntRemoveFavorite;

  /// The shell snackbar's text for [notice]; null shows nothing. A rating
  /// write has no failure copy on Android (ratings are local-first and retry
  /// on their own), so it stays silent until the owner's copy model gives one.
  String? noticeMessage(PlaybackNotice notice) => switch (notice) {
        FavoriteWriteFailed(:final favorite) => favoriteFailure(favorite: favorite),
        PlaybackFailed(:final kind) => errorMessage(kind),
        RatingWriteFailed() => null,
      };
}
