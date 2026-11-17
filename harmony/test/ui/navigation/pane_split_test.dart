import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/ui/navigation/pane/pane_split.dart';

void main() {
  test('should_giveShell600_when_tabletLandscape', () {
    final b = resolvePaneBudget(1280, null);
    expect(b.shellWidth, closeTo(600, 0.01));
    expect(b.paneWidth, closeTo(656, 0.01));
  });

  test('should_useDefaultShare_when_narrowerThan1080', () {
    final b = resolvePaneBudget(1000, null);
    expect(b.shellFraction, 0.45);
    expect(b.shellWidth, closeTo(976 * 0.45, 0.01));
  });

  test('should_keepPhoneColumns_when_dragged', () {
    var f = dragShellFraction(1440, null, -2000);
    expect(resolvePaneBudget(1440, f).shellWidth, closeTo(360, 0.01));
    f = dragShellFraction(1440, f, 5000);
    expect(resolvePaneBudget(1440, f).paneWidth, closeTo(360, 0.01));
  });

  test('should_splitEvenly_when_onlyTwoPhoneColumnsFit', () {
    final b = resolvePaneBudget(744, 0.2);
    expect(b.shellWidth, 360);
    expect(b.paneWidth, 360);
  });
}
