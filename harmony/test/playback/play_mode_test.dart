import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/playback/now_playing_state.dart';
import 'package:yoin/playback/play_mode.dart';

void main() {
  test('should_cycleGrayCode_when_roundButtonTapped', () {
    expect(PlayMode.repeatAll.next(), PlayMode.shuffle);
    expect(PlayMode.shuffle.next(), PlayMode.shuffleRepeatOne);
    expect(PlayMode.shuffleRepeatOne.next(), PlayMode.repeatOne);
    expect(PlayMode.repeatOne.next(), PlayMode.repeatAll);
  });

  test('should_returnToRepeatAllKeepingShuffle_when_tappedInAnOffMode', () {
    expect(PlayMode.repeatOff.next(), PlayMode.repeatAll);
    expect(PlayMode.shuffleRepeatOff.next(), PlayMode.shuffle);
  });

  test('should_stepRepeatAxisOnly_when_nextRepeat', () {
    expect(PlayMode.repeatAll.nextRepeat(), PlayMode.repeatOne);
    expect(PlayMode.repeatOne.nextRepeat(), PlayMode.repeatOff);
    expect(PlayMode.repeatOff.nextRepeat(), PlayMode.repeatAll);
    expect(PlayMode.shuffle.nextRepeat(), PlayMode.shuffleRepeatOne);
    expect(PlayMode.shuffleRepeatOff.nextRepeat(), PlayMode.shuffle);
  });

  test('should_changeOnlyShuffle_when_withShuffle', () {
    expect(PlayMode.repeatOne.withShuffle(true), PlayMode.shuffleRepeatOne);
    expect(PlayMode.shuffleRepeatOff.withShuffle(false), PlayMode.repeatOff);
  });

  test('should_roundTripBothAxes_when_readBack', () {
    for (final mode in PlayMode.values) {
      expect(PlayMode.of(mode.repeat, mode.shuffled), mode);
    }
    expect(PlayMode.defaultMode, PlayMode.repeatAll);
    expect(PlayMode.repeatAll.repeat, RepeatMode.all);
  });
}
