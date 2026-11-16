import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/home/widgets/seam.dart';
import 'package:yoin/ui/component/seam/chrome_seam.dart';
import 'package:yoin/ui/component/seam/seam_dissolve.dart';
import 'package:yoin/ui/component/seam/seam_field.dart';

/// A window with one floating bar and a field viewport holding two covers:
/// one near the bar, one at the top.
class _Window extends StatefulWidget {
  const _Window({this.barInSlot = true, this.showBar = true, this.withViewport = true});

  final bool barInSlot;
  final bool showBar;
  final bool withViewport;

  @override
  State<_Window> createState() => _WindowState();
}

class _WindowState extends State<_Window> with TickerProviderStateMixin {
  late final SeamBarField field = SeamBarField(vsync: this);
  late final SeamFlow flow = SeamFlow(vsync: this);

  @override
  void dispose() {
    flow.dispose();
    field.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final covers = Stack(
      children: const [
        Positioned(
          left: 20,
          top: 650,
          width: 160,
          height: 120,
          child: SeamDissolve(
            key: ValueKey('near'),
            child: ColoredBox(color: Color(0xFF3050A0)),
          ),
        ),
        Positioned(
          left: 20,
          top: 40,
          width: 160,
          height: 120,
          child: SeamDissolve(
            key: ValueKey('far'),
            child: ColoredBox(color: Color(0xFF3050A0)),
          ),
        ),
      ],
    );
    final bar = SeamBarSource(
      cornerRadius: 28,
      color: const Color(0xFFE0E0E0),
      child: const ColoredBox(color: Color(0xFFE0E0E0)),
    );
    return SeamBarFieldScope(
      field: field,
      child: Stack(
        children: [
          Positioned.fill(
            child: widget.withViewport
                ? SeamFieldViewport(
                    flow: flow,
                    background: const SeamBackground([Color(0xFFF7F7F7)]),
                    remaining: () => double.infinity,
                    child: covers,
                  )
                : covers,
          ),
          if (widget.showBar)
            Positioned(
              left: 16,
              right: 16,
              bottom: 24,
              height: 68,
              child: widget.barInSlot ? SeamBarSlot(field: field, child: bar) : bar,
            ),
        ],
      ),
    );
  }
}

void main() {
  Future<SeamBarField> pump(
    WidgetTester tester, {
    bool barInSlot = true,
    bool showBar = true,
    bool withViewport = true,
  }) async {
    tester.view.physicalSize = const Size(392, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(
      MaterialApp(
        home: _Window(barInSlot: barInSlot, showBar: showBar, withViewport: withViewport),
      ),
    );
    await tester.pumpAndSettle();
    return tester.state<_WindowState>(find.byType(_Window)).field;
  }

  int maskLayers(WidgetTester tester) => tester.layers.whereType<ShaderMaskLayer>().length;

  testWidgets('should_reportTheBar_when_itSitsInTheChromeSlot', (tester) async {
    final field = await pump(tester);
    expect(field.attached, isTrue);
    expect(field.bounds, const Rect.fromLTRB(16, 708, 376, 776));
    expect(field.cornerRadius, 28);
    expect(field.containerColor, const Color(0xFFE0E0E0));
    expect(field.rootHeight, 800);
    expect(field.reveal, closeTo(1, 0.01));
  });

  testWidgets('should_ignoreTheBar_when_itIsADemoOutsideTheSlot', (tester) async {
    final field = await pump(tester, barInSlot: false);
    expect(field.attached, isFalse);
    expect(field.reveal, 0);
    expect(maskLayers(tester), 0);
  });

  testWidgets('should_breakOnlyTheCoverNearTheBar_when_fieldIsIn', (tester) async {
    await pump(tester);
    // One print breaks up: the cover by the bar; the one at the top paints whole, with no layer.
    expect(maskLayers(tester), 1);
    final near = tester.renderObject<RenderSeamDissolve>(find.byKey(const ValueKey('near')));
    final far = tester.renderObject<RenderSeamDissolve>(find.byKey(const ValueKey('far')));
    expect(near.alwaysNeedsCompositing, isTrue);
    expect(far.alwaysNeedsCompositing, isFalse);
  });

  testWidgets('should_paintWhole_when_viewportTakesNoField', (tester) async {
    await pump(tester, withViewport: false);
    expect(maskLayers(tester), 0);
  });

  testWidgets('should_fadeTheFieldOut_when_nowPlayingCoversTheBar', (tester) async {
    final field = await pump(tester);
    field.shown = false;
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 16));
    // On the effects spring, not cut.
    expect(field.reveal, inExclusiveRange(0, 1));
    await tester.pumpAndSettle();
    expect(field.reveal, closeTo(0, 0.001));
    expect(maskLayers(tester), 0);
    field.shown = true;
    await tester.pumpAndSettle();
    expect(field.reveal, closeTo(1, 0.01));
    expect(maskLayers(tester), 1);
  });

  testWidgets('should_releaseTheField_when_barLeavesTheWindow', (tester) async {
    final field = await pump(tester);
    expect(field.attached, isTrue);
    await tester.pumpWidget(const MaterialApp(home: _Window(showBar: false)));
    await tester.pumpAndSettle();
    expect(tester.state<_WindowState>(find.byType(_Window)).field.attached, isFalse);
  });
}
