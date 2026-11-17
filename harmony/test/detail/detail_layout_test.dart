import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/detail/detail_layout.dart';
import 'package:yoin/detail/detail_page.dart';
import 'package:yoin/detail/pull_up_reveal.dart';
import 'package:yoin/theme/tokens.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';

void main() {
  group('resolveDetailLayout', () {
    test('should_keepPhoneHero_when_portraitPhone', () {
      expect(resolveDetailLayout(const YoinWindowInfo(390, 844), hasShort: true), DetailLayout.compact);
    });

    test('should_turnSideways_when_heightUnder480', () {
      expect(resolveDetailLayout(const YoinWindowInfo(844, 390), hasShort: true), DetailLayout.landscape);
      // Height first: a wide but short MateBook window is still a landscape handset.
      expect(resolveDetailLayout(const YoinWindowInfo(1200, 460)), DetailLayout.landscape);
    });

    test('should_beMedium_when_width600to839', () {
      expect(resolveDetailLayout(const YoinWindowInfo(600, 900)), DetailLayout.medium);
      expect(resolveDetailLayout(const YoinWindowInfo(800, 1280), hasShort: true), DetailLayout.medium);
    });

    test('should_beWide_when_widthAtLeast840', () {
      expect(resolveDetailLayout(const YoinWindowInfo(840, 700)), DetailLayout.wide);
      expect(resolveDetailLayout(const YoinWindowInfo(1280, 800), hasShort: true), DetailLayout.wide);
    });

    test('should_beShort_when_compactWindowTooShortForHero', () {
      expect(resolveDetailLayout(const YoinWindowInfo(420, 640), hasShort: true), DetailLayout.short);
      // A page without the Short portrait (the Playlist) keeps its phone hero.
      expect(resolveDetailLayout(const YoinWindowInfo(420, 640)), DetailLayout.compact);
    });

    test('should_beShort_when_largeTextNeedsMoreRoom', () {
      expect(resolveDetailLayout(const YoinWindowInfo(390, 844), hasShort: true, fontScale: 1.5), DetailLayout.short);
    });

    test('should_followColumnWidth_when_inDetailColumn', () {
      const window = YoinWindowInfo(1280, 800);
      expect(resolveDetailLayout(window.forPaneWidth(656)), DetailLayout.medium);
      // A ~460 column beside the side panel on an 800-tall window is too short for the phone hero.
      expect(resolveDetailLayout(window.forPaneWidth(460), hasShort: true), DetailLayout.short);
      expect(
        resolveDetailLayout(const YoinWindowInfo(1280, 1000).forPaneWidth(460), hasShort: true),
        DetailLayout.compact,
      );
    });

    test('should_stayCompact_when_tabletop', () {
      const tabletop = YoinWindowInfo(700, 800, isTabletop: true);
      expect(resolveDetailLayout(tabletop), DetailLayout.compact);
    });
  });

  group('albumHeroFitsPortrait', () {
    test('should_fit_when_390x844', () {
      expect(albumHeroFitsPortrait(390, 844), isTrue);
    });

    test('should_notFit_when_foldOuterScreen', () {
      expect(albumHeroFitsPortrait(420, 640), isFalse);
    });
  });

  group('albumShortCoverSide', () {
    test('should_capAt260_when_wideEnough', () {
      expect(albumShortCoverSide(420), 260);
    });

    test('should_leaveMetaItsMinimum_when_narrow', () {
      // 360 − (48 + 112) = 200 < 0.62 × 360.
      expect(albumShortCoverSide(360), 200);
    });
  });

  group('detailSideBarGeometry', () {
    const topInset = 24.0;
    const headerBottom = topInset + DetailHeaderMetrics.bandHeight;

    test('should_centreMediumCoverInPageColumn_when_tablet', () {
      final g = detailSideBarGeometry(
        DetailLayout.medium,
        width: 800,
        height: 1280,
        topInset: topInset,
        headerBottom: headerBottom,
      );
      expect(g.cover, const Rect.fromLTWH(56, headerBottom + 4, 240, 240));
      expect(g.stamp.width, DetailTokens.albumStamp);
      expect(g.stamp.right, 800 - DetailTokens.albumStampEndInset);
    });

    test('should_fitLandscapeCoverUnderHeader_when_handset', () {
      final g = detailSideBarGeometry(
        DetailLayout.landscape,
        width: 760,
        height: 320,
        topInset: 0,
        headerBottom: DetailHeaderMetrics.bandHeight,
      );
      expect(g.cover.left, DetailLayoutTokens.albumLandscapeCoverStart);
      expect(g.cover.width, 320 - DetailHeaderMetrics.bandHeight - 24);
      expect(g.stamp.width, DetailLayoutTokens.landscapeStamp);
    });

    test('should_putShortCoverOnStartLine_when_short', () {
      final g = detailSideBarGeometry(
        DetailLayout.short,
        width: 420,
        height: 640,
        topInset: topInset,
        headerBottom: headerBottom,
      );
      expect(g.cover, const Rect.fromLTWH(16, headerBottom + 4, 260, 260));
    });

    test('should_mirror_when_rtl', () {
      final g = detailSideBarGeometry(
        DetailLayout.short,
        width: 420,
        height: 640,
        topInset: topInset,
        headerBottom: headerBottom,
        rtl: true,
      );
      expect(g.cover.right, 420 - 16);
      expect(g.stamp.left, DetailTokens.albumStampEndInset);
    });
  });

  group('albumHuggingMark', () {
    test('should_hug1point3TimesCover_when_roomy', () {
      final mark = albumHuggingMark(const Rect.fromLTWH(200, 100, 200, 200));
      expect(mark.width, closeTo(260, 0.001));
      expect(mark.center.dx, 300);
      expect(mark.top, 100);
    });

    test('should_shrinkToClearEdge_when_coverNearStart', () {
      final mark = albumHuggingMark(const Rect.fromLTWH(16, 100, 200, 200));
      // The near arm (0.46 of the mark from its centre) stays 8 off the edge.
      expect(mark.center.dx - mark.width * DetailLayoutTokens.huggingArmReach, closeTo(8, 0.001));
      expect(mark.width, lessThan(260));
    });
  });

  group('ScrollBarMorph', () {
    testWidgets('should_formBar_when_coverHasScrolledBy', (tester) async {
      final controller = ScrollController();
      final morph = ScrollBarMorph(controller, coverSide: 240);
      addTearDown(() {
        morph.dispose();
        controller.dispose();
      });
      await tester.pumpWidget(
        Directionality(
          textDirection: TextDirection.ltr,
          child: ListView(controller: controller, children: const [SizedBox(height: 3000)]),
        ),
      );
      expect(morph.barProgress, 0);
      controller.jumpTo(120);
      expect(morph.barProgress, 0.5);
      expect(morph.coverLift, 120);
      controller.jumpTo(600);
      expect(morph.barProgress, 1);
      expect(detailMediumBarProgress(600, 240), 1);
    });
  });

  test('should_readPullUpAsBarProgress_when_revealController', () {
    final reveal = RevealController(vsync: const TestVSync());
    addTearDown(reveal.dispose);
    expect(reveal.barProgress, 0);
    reveal.setExpanded(true, animate: false);
    expect(reveal.barProgress, 1);
    expect(reveal.coverLift, 0);
  });
}
