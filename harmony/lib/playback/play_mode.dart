import '../platform/playback/playback_engine.dart';
import 'now_playing_state.dart';

/// Play mode as two independent axes, shuffle (off / on) × repeat (all /
/// one / off) — a port of Android player/PlayMode.kt.
///
/// The round button cycles the 2 × 2 core one axis per tap (a Gray code):
/// repeat all → shuffle → shuffle + repeat one → repeat one → repeat all.
/// Repeat off lives only in the split pill's repeat segment ([nextRepeat]);
/// a round-button tap in an off mode turns repeat back to all and keeps
/// shuffle. Every mode writes repeat AND shuffle together, so the result
/// never depends on the engine's previous state. Not persisted (design.md).
enum PlayMode {
  repeatAll(RepeatMode.all, false),
  shuffle(RepeatMode.all, true),
  shuffleRepeatOne(RepeatMode.one, true),
  repeatOne(RepeatMode.one, false),
  repeatOff(RepeatMode.off, false),
  shuffleRepeatOff(RepeatMode.off, true);

  const PlayMode(this.repeat, this.shuffled);

  final RepeatMode repeat;
  final bool shuffled;

  static const defaultMode = PlayMode.repeatAll;

  bool get isRepeatOne => repeat == RepeatMode.one;
  bool get isRepeatOff => repeat == RepeatMode.off;

  /// The mode the round button's next tap moves to.
  PlayMode next() => switch (this) {
        repeatAll => shuffle,
        shuffle => shuffleRepeatOne,
        shuffleRepeatOne => repeatOne,
        repeatOne => repeatAll,
        repeatOff => repeatAll,
        shuffleRepeatOff => shuffle,
      };

  /// This mode with only the shuffle axis changed.
  PlayMode withShuffle(bool on) => of(repeat, on);

  /// The split pill's repeat segment: all → one → off → all, shuffle kept.
  PlayMode nextRepeat() => of(
        switch (repeat) {
          RepeatMode.all => RepeatMode.one,
          RepeatMode.one => RepeatMode.off,
          RepeatMode.off => RepeatMode.all,
        },
        shuffled,
      );

  /// Reads a reported state back into a mode, one axis at a time.
  static PlayMode of(RepeatMode repeat, bool shuffle) => switch (repeat) {
        RepeatMode.one => shuffle ? shuffleRepeatOne : repeatOne,
        RepeatMode.off => shuffle ? shuffleRepeatOff : repeatOff,
        RepeatMode.all => shuffle ? PlayMode.shuffle : repeatAll,
      };
}

extension RepeatModeEngine on RepeatMode {
  EngineRepeat get engine => switch (this) {
        RepeatMode.off => EngineRepeat.off,
        RepeatMode.one => EngineRepeat.one,
        RepeatMode.all => EngineRepeat.all,
      };

  static RepeatMode fromEngine(EngineRepeat repeat) => switch (repeat) {
        EngineRepeat.off => RepeatMode.off,
        EngineRepeat.one => RepeatMode.one,
        EngineRepeat.all => RepeatMode.all,
      };
}
