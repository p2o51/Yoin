import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/shell/detail_chrome.dart';
import 'package:yoin/shell/shell_state.dart';
import 'package:yoin/shell/yoin_bottom_bar.dart';
import 'package:yoin/shell/yoin_section.dart';
import 'package:yoin/theme/tokens.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';
import 'package:yoin/ui/component/expressive/expressive_split_button.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

Widget _bar({DetailPlayActions? actions}) => MaterialApp(
  navigatorKey: appNavigatorKey,
  home: YoinWindowScope.measured(
    child: Scaffold(
      body: Align(
        alignment: Alignment.bottomCenter,
        child: YoinBottomBar(selected: YoinSection.home, onSelect: (_) {}, detailActions: actions),
      ),
    ),
  ),
);

void _window(WidgetTester tester, Size dp) {
  tester.view.physicalSize = dp * 2;
  tester.view.devicePixelRatio = 2;
  addTearDown(tester.view.reset);
}

final _actions = DetailPlayActions(onPlay: () {}, onShuffle: () {});

Finder get _splitMaterials => find.descendant(of: find.byType(ExpressiveSplitButton), matching: find.byType(Material));

void main() {
  testWidgets('should_drawPlaySplitAtButtonHeight_when_portraitDetailPose', (tester) async {
    _window(tester, const Size(412, 915));
    await tester.pumpWidget(_bar(actions: _actions));
    await tester.pumpAndSettle();
    expect(_splitMaterials, findsNWidgets(2));
    for (var i = 0; i < 2; i++) {
      expect(tester.getSize(_splitMaterials.at(i)).height, BarTokens.buttonHeight);
    }
    final play = tester.getRect(_splitMaterials.at(0));
    final more = tester.getRect(_splitMaterials.at(1));
    expect(more.right - play.left, closeTo(BarTokens.splitWidth, 0.01));
    expect(more.left - play.right, closeTo(BarTokens.splitGap, 0.01));
  });

  testWidgets('should_promoteShuffleAndStretchSplit_when_centredDetailPose', (tester) async {
    _window(tester, const Size(900, 1200));
    await tester.pumpWidget(_bar(actions: _actions));
    await tester.pumpAndSettle();
    // Shuffle left the menu, which would be empty: no ▾, Play alone and stretched.
    expect(_splitMaterials, findsOneWidget);
    expect(tester.getSize(_splitMaterials).height, BarTokens.centeredButtonHeight);
    expect(tester.getSize(_splitMaterials).width, greaterThan(BarTokens.splitWidth));
    expect(find.bySemanticsLabel('Shuffle play'), findsOneWidget);
  });

  group('long-press Library', () {
    Widget navBar({VoidCallback? onLongPress, ValueChanged<YoinSection>? onSelect}) => MaterialApp(
      navigatorKey: appNavigatorKey,
      home: YoinWindowScope.measured(
        child: Scaffold(
          body: Align(
            alignment: Alignment.bottomCenter,
            child: YoinBottomBar(
              selected: YoinSection.home,
              onSelect: onSelect ?? (_) {},
              onLibraryLongPress: onLongPress,
            ),
          ),
        ),
      ),
    );

    Finder hint() => find.byIcon(YoinSymbols.search);

    double hintAlpha(WidgetTester tester) =>
        tester.widget<Opacity>(find.ancestor(of: hint(), matching: find.byType(Opacity)).first).opacity;

    testWidgets('should_openSearchWithoutSelecting_when_libraryLongPressed', (tester) async {
      _window(tester, const Size(412, 915));
      var searches = 0;
      final selected = <YoinSection>[];
      await tester.pumpWidget(navBar(onLongPress: () => searches++, onSelect: selected.add));
      await tester.pumpAndSettle();
      await tester.longPress(find.bySemanticsLabel('Library'));
      await tester.pumpAndSettle();
      expect(searches, 1);
      expect(selected, isEmpty, reason: 'the long-press is not a tap');
    });

    testWidgets('should_showSearchHintWhileHeld_when_libraryIsPressed', (tester) async {
      _window(tester, const Size(412, 915));
      await tester.pumpWidget(navBar(onLongPress: () {}));
      await tester.pumpAndSettle();
      expect(hint(), findsNothing);

      final gesture = await tester.startGesture(tester.getCenter(find.bySemanticsLabel('Library')));
      await tester.pump(const Duration(milliseconds: BarTokens.searchHintDelayMs - 40));
      expect(hint(), findsNothing, reason: 'a tap never flashes the hint');
      await tester.pump(const Duration(milliseconds: 80));
      await tester.pump(const Duration(milliseconds: 600));
      expect(hint(), findsOneWidget);
      expect(hintAlpha(tester), closeTo(1, 0.02));
      // Above the bar, over the Library end.
      final library = tester.getRect(find.bySemanticsLabel('Library'));
      final bubble = tester.getRect(hint());
      expect(bubble.bottom, lessThan(library.top));
      expect(bubble.center.dx, greaterThan(library.left));

      await gesture.up();
      await tester.pump(const Duration(milliseconds: BarTokens.searchHintSettleMs + 20));
      await tester.pumpAndSettle();
      expect(hint(), findsNothing);
    });

    testWidgets('should_offerNoHint_when_barHasNoLongPress', (tester) async {
      _window(tester, const Size(412, 915));
      await tester.pumpWidget(navBar());
      await tester.pumpAndSettle();
      final gesture = await tester.startGesture(tester.getCenter(find.bySemanticsLabel('Library')));
      await tester.pump(const Duration(seconds: 1));
      expect(hint(), findsNothing);
      await gesture.up();
      await tester.pumpAndSettle();
    });
  });
}
