import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/home/home_bubble_tips.dart';
import 'package:yoin/platform/posture/display_posture.dart';

void main() {
  tearDown(() => HomeBubbleTip.deviceFolds = null);

  test('should_leaveTabletopOut_when_theDeviceDoesNotFold', () {
    HomeBubbleTip.deviceFolds = false;
    expect(HomeBubbleTip.available('ohos'), isNot(contains(HomeBubbleTip.tabletop)));
  });

  test('should_listTabletop_when_theDeviceFoldsOrIsUnknown', () {
    HomeBubbleTip.deviceFolds = true;
    expect(HomeBubbleTip.available('ohos'), contains(HomeBubbleTip.tabletop));
    HomeBubbleTip.deviceFolds = null;
    expect(HomeBubbleTip.available('ohos'), contains(HomeBubbleTip.tabletop));
  });

  test('should_readFoldable_when_thePostureHostSaysSo', () {
    expect(DisplayPosture.fromMap({'foldable': false}).foldable, isFalse);
    expect(DisplayPosture.fromMap({}).foldable, isNull);
  });
}
