import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/platform/posture/display_posture.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';

/// A half-folded book foldable stood on its kickstand: 1280 × 800, crease across the middle.
const _hinge = Rect.fromLTWH(0, 396, 1280, 8);

DisplayPosture _posture(FoldStatus status, List<Rect> creases) =>
    DisplayPosture(foldStatus: status, creaseRects: creases);

void main() {
  group('fold status codes', () {
    test('should_readSingleAxisCodes_when_displayReportsThem', () {
      expect(FoldStatus.fromCode(0), FoldStatus.unknown);
      expect(FoldStatus.fromCode(1), FoldStatus.expanded);
      expect(FoldStatus.fromCode(2), FoldStatus.folded);
      expect(FoldStatus.fromCode(3), FoldStatus.halfFolded);
    });

    test('should_readFirstAxis_when_triFoldReportsTwoAxes', () {
      expect(FoldStatus.fromCode(11), FoldStatus.expanded);
      expect(FoldStatus.fromCode(21), FoldStatus.halfFolded);
      expect(FoldStatus.fromCode(22), FoldStatus.folded);
      expect(FoldStatus.fromCode(99), FoldStatus.unknown);
      expect(FoldDisplayMode.fromCode(1), FoldDisplayMode.full);
      expect(FoldDisplayMode.fromCode(42), FoldDisplayMode.unknown);
    });
  });

  group('tabletop hinge', () {
    const window = Size(1280, 800);

    test('should_findHinge_when_halfFoldedOnHorizontalCrease', () {
      expect(_posture(FoldStatus.halfFolded, [_hinge]).tabletopHinge(window), _hinge);
    });

    test('should_notBeTabletop_when_flatOrFolded', () {
      expect(_posture(FoldStatus.expanded, [_hinge]).tabletopHinge(window), isNull);
      expect(_posture(FoldStatus.folded, [_hinge]).tabletopHinge(window), isNull);
      expect(DisplayPosture.flat.tabletopHinge(window), isNull);
    });

    test('should_notBeTabletop_when_creaseIsVertical', () {
      // A book fold held open like a book: the crease runs top to bottom.
      const vertical = Rect.fromLTWH(636, 0, 8, 800);
      expect(_posture(FoldStatus.halfFolded, [vertical]).tabletopHinge(window), isNull);
    });

    test('should_notBeTabletop_when_creaseMissesThisWindow', () {
      // A split-screen half below the crease.
      const below = Rect.fromLTWH(0, -40, 1280, 8);
      expect(_posture(FoldStatus.halfFolded, [below]).tabletopHinge(window), isNull);
      expect(_posture(FoldStatus.halfFolded, const []).tabletopHinge(window), isNull);
    });
  });

  test('should_parseChannelMap_when_pluginPushesPosture', () {
    final posture = DisplayPosture.fromMap({
      'foldStatus': 3,
      'foldDisplayMode': 1,
      'creaseRects': [
        {'left': 0, 'top': 396.0, 'width': 1280, 'height': 8},
        {'left': 0, 'top': 0, 'width': 0, 'height': 0},
      ],
      'leftCutout': {'left': 0, 'top': 170.0, 'width': 32.0, 'height': 40.0},
    });
    expect(posture.foldStatus, FoldStatus.halfFolded);
    expect(posture.foldDisplayMode, FoldDisplayMode.full);
    expect(posture.creaseRects, [_hinge]);
    expect(posture.leftCutout, const SideCutout(top: 170, bottom: 210, inset: 32));
    expect(posture.rightCutout, isNull);
  });

  group('the channel host', () {
    TestWidgetsFlutterBinding.ensureInitialized();
    const channel = MethodChannel(ChannelDisplayPostureHost.channelName);

    tearDown(
      () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, null),
    );

    test('should_emitReadThenPushes_when_listened', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, (call) async {
        expect(call.method, 'read');
        return {'foldStatus': 1, 'creaseRects': []};
      });
      final host = ChannelDisplayPostureHost();
      final seen = <DisplayPosture>[];
      final sub = host.watch().listen(seen.add);
      await Future<void>.delayed(Duration.zero);
      expect(seen.single.foldStatus, FoldStatus.expanded);
      // The plugin pushes a half fold.
      final codec = const StandardMethodCodec();
      await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.handlePlatformMessage(
        ChannelDisplayPostureHost.channelName,
        codec.encodeMethodCall(
          const MethodCall('posture', {
            'foldStatus': 3,
            'creaseRects': [
              {'left': 0, 'top': 396, 'width': 1280, 'height': 8},
            ],
          }),
        ),
        (_) {},
      );
      expect(seen.last.foldStatus, FoldStatus.halfFolded);
      await sub.cancel();
    });

    test('should_readFlat_when_noPlugin', () async {
      final host = ChannelDisplayPostureHost();
      final first = await host.watch().first.timeout(const Duration(seconds: 1));
      expect(first, DisplayPosture.flat);
    });
  });

  testWidgets('should_provideTabletopWindow_when_measuredWithFakeHalfFold', (tester) async {
    tester.view.physicalSize = const Size(1280, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    YoinWindowInfo? seen;
    await tester.pumpWidget(
      YoinWindowScope.measured(
        posture: _posture(FoldStatus.halfFolded, [_hinge]),
        child: Builder(
          builder: (context) {
            seen = YoinWindowInfo.of(context);
            return const SizedBox();
          },
        ),
      ),
    );
    expect(seen!.isTabletop, isTrue);
    expect(seen!.hingeBounds, _hinge);
    expect(seen!.layoutMode, LayoutMode.tabletop);
    expect(seen!.chromeForm, ShellChromeForm.portraitBar);
  });
}
