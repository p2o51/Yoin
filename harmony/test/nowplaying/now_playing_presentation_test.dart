import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/nowplaying/now_playing_presentation.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';

void main() {
  YoinWindowInfo w(double width, double height) => YoinWindowInfo(width, height);

  test('should_clampPanelWidth_when_windowsVary', () {
    expect(nowPlayingPanelWidth(690), 360);
    expect(nowPlayingPanelWidth(800), 400);
    expect(nowPlayingPanelWidth(1280), 420);
  });

  test('should_openPanelFirst_when_mediumOrWideAndTall', () {
    expect(resolveNowPlayingPresentation(w(800, 1280), fullscreen: false), NowPlayingPresentation.panel);
    expect(resolveNowPlayingPresentation(w(1440, 900), fullscreen: false), NowPlayingPresentation.panel);
  });

  test('should_resolveTierFull_when_fullscreen', () {
    expect(resolveNowPlayingPresentation(w(800, 1280), fullscreen: true), NowPlayingPresentation.enlarged);
    expect(resolveNowPlayingPresentation(w(1280, 800), fullscreen: true), NowPlayingPresentation.dualPane);
  });

  test('should_skipPanel_when_contentBesideWouldBeNarrowerThanAPhone', () {
    // 640 − 360 = 280 < 320: straight to the enlarged phone, no toggle.
    final half = w(640, 800);
    expect(canOpenNowPlayingPanel(half), isFalse);
    expect(resolveNowPlayingPresentation(half, fullscreen: false), NowPlayingPresentation.enlarged);
    expect(NowPlayingFrame.resolve(half, fullscreen: false).panelFamily, isFalse);
  });

  test('should_judgeHeightFirst_when_shortWindow', () {
    expect(resolveNowPlayingPresentation(w(900, 400), fullscreen: false), NowPlayingPresentation.landscape);
    expect(resolveNowPlayingPresentation(w(500, 400), fullscreen: false), NowPlayingPresentation.phone);
    expect(resolveNowPlayingPresentation(w(390, 844), fullscreen: true), NowPlayingPresentation.phone);
  });

  test('should_keepPanelFamily_when_toggledFull', () {
    final frame = NowPlayingFrame.resolve(w(1280, 800), fullscreen: true);
    expect(frame.presentation, NowPlayingPresentation.dualPane);
    expect(frame.panelFamily, isTrue);
    expect(frame.panelWidth, 420);
  });

  test('should_growEnlargedColumn_when_windowWidens', () {
    expect(nowPlayingEnlargedSpec(800), const NowPlayingEnlargedSpec(columnWidth: 640, ratingColumn: 104, controlSize: 60));
    final fold = nowPlayingEnlargedSpec(690);
    expect(fold.columnWidth, 530);
    expect(fold.ratingColumn, closeTo(82, 0.01));
    expect(fold.controlSize, 56);
  });

  test('should_followAndroidRules_when_sizeClassChangesWhileOpen', () {
    expect(
      fullscreenAfterLayoutChange(previous: LayoutMode.compact, current: LayoutMode.medium, expanded: true, wasFullscreen: false),
      isTrue,
    );
    expect(
      fullscreenAfterLayoutChange(previous: LayoutMode.medium, current: LayoutMode.wide, expanded: true, wasFullscreen: true),
      isTrue,
    );
    expect(
      fullscreenAfterLayoutChange(previous: LayoutMode.wide, current: LayoutMode.medium, expanded: true, wasFullscreen: false),
      isFalse,
    );
    expect(
      fullscreenAfterLayoutChange(previous: null, current: LayoutMode.wide, expanded: false, wasFullscreen: true),
      isFalse,
    );
    expect(
      fullscreenAfterLayoutChange(previous: LayoutMode.wide, current: LayoutMode.compact, expanded: true, wasFullscreen: true),
      isTrue,
    );
  });

  test('should_morphOnlyWherePlayerCoversBar_when_presentationVaries', () {
    expect(NowPlayingPresentation.panel.pillMorph, PillMorph.none);
    expect(NowPlayingPresentation.enlarged.pillMorph, PillMorph.coverAndText);
    expect(NowPlayingPresentation.dualPane.pillMorph, PillMorph.cover);
  });

  test('should_openFullDirectly_when_panelCannotSitBesideTwoColumns', () {
    // adaptive-principles §4 table: 1000/1100 + detail column → straight to Full two-column.
    const twoColumns = 744.0;
    for (final width in [1000.0, 1100.0, 1163.0]) {
      final frame = NowPlayingFrame.resolve(
        YoinWindowInfo(width, 800),
        fullscreen: false,
        minContentWidth: twoColumns,
      );
      expect(frame.panelAvailable, isFalse, reason: '$width');
      expect(frame.presentation, NowPlayingPresentation.dualPane, reason: '$width');
      expect(frame.panelFamily, isFalse);
    }
    // 1164 − 420 = 744: both columns fit beside the panel.
    final fits = NowPlayingFrame.resolve(const YoinWindowInfo(1164, 800), fullscreen: false, minContentWidth: twoColumns);
    expect(fits.presentation, NowPlayingPresentation.panel);
    // Without the column the same 1100 window keeps its panel.
    expect(NowPlayingFrame.resolve(const YoinWindowInfo(1100, 800), fullscreen: false).presentation,
        NowPlayingPresentation.panel);
  });
}
