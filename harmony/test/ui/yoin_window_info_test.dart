import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';

void main() {
  test('should_judgeHeightFirst_when_landscapeHandset', () {
    const info = YoinWindowInfo(915, 412); // wide but short
    expect(info.isCompactHeight, isTrue);
    expect(info.chromeForm, ShellChromeForm.edgeSplit);
    expect(info.layoutMode, LayoutMode.wide);
  });

  test('should_classifyWidths_when_atBreakpoints', () {
    expect(const YoinWindowInfo(599, 800).layoutMode, LayoutMode.compact);
    expect(const YoinWindowInfo(600, 800).layoutMode, LayoutMode.medium);
    expect(const YoinWindowInfo(839, 800).layoutMode, LayoutMode.medium);
    expect(const YoinWindowInfo(840, 800).layoutMode, LayoutMode.wide);
  });

  test('should_readAsCompact_when_givenNarrowPane', () {
    const window = YoinWindowInfo(1280, 800);
    expect(window.forPaneWidth(460).layoutMode, LayoutMode.compact);
    expect(window.forPaneWidth(600).layoutMode, LayoutMode.medium);
  });
}
