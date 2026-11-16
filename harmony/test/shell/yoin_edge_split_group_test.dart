import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/shell/bar_edit_pose.dart';
import 'package:yoin/shell/detail_chrome.dart';
import 'package:yoin/shell/shell_state.dart';
import 'package:yoin/shell/yoin_edge_split_group.dart';
import 'package:yoin/shell/yoin_section.dart';
import 'package:yoin/theme/tokens.dart';
import 'package:yoin/ui/adaptive/edge_split.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';
import 'package:yoin_symbols/yoin_symbols.dart';

/// A 390 tall landscape phone with its camera centred on the left edge.
final _segments = computeEdgeSplitSegments(
  windowHeight: 390,
  topInset: 0,
  bottomInset: 0,
  cutoutTop: 176,
  cutoutBottom: 212,
);
final _roomy = computeEdgeSplitSegments(windowHeight: 390, topInset: 0, bottomInset: 0);

const _pill = Key('pill');

Widget _group({
  YoinSection selected = YoinSection.home,
  ValueChanged<YoinSection>? onSelect,
  bool idle = false,
  DetailPlayActions? actions,
  BarEditPose? editPose,
  VoidCallback? onLibraryLongPress,
  EdgeSplitSegments? segments,
  EdgeSplitSide side = EdgeSplitSide.left,
}) => MaterialApp(
  navigatorKey: appNavigatorKey,
  home: YoinWindowScope.measured(
    child: Scaffold(
      body: YoinEdgeSplitGroup(
        selected: selected,
        onSelect: onSelect ?? (_) {},
        idle: idle,
        detailActions: actions,
        editPose: editPose,
        onLibraryLongPress: onLibraryLongPress,
        pill: const SizedBox.expand(key: _pill),
        segmentsOverride: segments ?? _segments,
        sideOverride: side,
      ),
    ),
  ),
);

void _landscape(WidgetTester tester) {
  tester.view.physicalSize = const Size(844, 390) * 2;
  tester.view.devicePixelRatio = 2;
  addTearDown(tester.view.reset);
}

Finder get _home => find.byKey(const ValueKey('edge.home'));
Finder get _library => find.byKey(const ValueKey('edge.library'));

void main() {
  testWidgets('should_standCapsulesAroundCutout_when_landscapePhone', (tester) async {
    _landscape(tester);
    await tester.pumpWidget(_group());
    await tester.pumpAndSettle();
    // Home (selected) over Library inside the upper capsule; the pill in the lower one.
    final home = tester.getRect(_home);
    final library = tester.getRect(_library);
    expect(home.left, EdgeSplitTokens.groupInset + EdgeSplitTokens.capsulePadding);
    expect(home.width, EdgeSplitTokens.buttonWidth);
    expect(library.height, EdgeSplitTokens.navRestingHeight);
    expect(home.height + EdgeSplitTokens.navGap + library.height, closeTo(_segments.upperHeight - 12, 0.01));
    expect(home.top, _segments.upperTop + EdgeSplitTokens.capsulePadding);
    final pill = tester.getRect(find.byKey(_pill));
    expect(pill.top, _segments.lowerTop + EdgeSplitTokens.capsulePadding);
    expect(pill.bottom, _segments.lowerBottom - EdgeSplitTokens.capsulePadding);
  });

  testWidgets('should_moveCapsulesToRightEdge_when_cutoutOnTheRight', (tester) async {
    _landscape(tester);
    await tester.pumpWidget(_group(side: EdgeSplitSide.right));
    await tester.pumpAndSettle();
    expect(tester.getRect(_home).right, 844 - EdgeSplitTokens.groupInset - EdgeSplitTokens.capsulePadding);
  });

  testWidgets('should_growSelectedButton_when_selectionMoves', (tester) async {
    _landscape(tester);
    YoinSection? picked;
    await tester.pumpWidget(_group(onSelect: (s) => picked = s));
    await tester.tap(_library);
    expect(picked, YoinSection.library);
    await tester.pumpWidget(_group(selected: YoinSection.library));
    await tester.pumpAndSettle();
    expect(tester.getSize(_home).height, EdgeSplitTokens.navRestingHeight);
    expect(tester.getSize(_library).height, greaterThan(EdgeSplitTokens.navRestingHeight));
    expect(find.byIcon(YoinSymbols.libraryFilled), findsOneWidget);
  });

  testWidgets('should_stepPillOut_when_nothingPlays', (tester) async {
    _landscape(tester);
    await tester.pumpWidget(_group(idle: true));
    await tester.pumpAndSettle();
    expect(find.byKey(_pill), findsNothing);
    await tester.pumpWidget(_group());
    await tester.pump(const Duration(milliseconds: 50));
    // Riding back in over its edge.
    expect(
      tester.getRect(find.byKey(_pill)).left,
      lessThan(EdgeSplitTokens.groupInset + EdgeSplitTokens.capsulePadding),
    );
    await tester.pumpAndSettle();
    expect(
      tester.getRect(find.byKey(_pill)).left,
      closeTo(EdgeSplitTokens.groupInset + EdgeSplitTokens.capsulePadding, 0.01),
    );
  });

  testWidgets('should_crossfadeToUprightPlaySplit_when_detailPose', (tester) async {
    _landscape(tester);
    var played = 0;
    var shuffled = 0;
    final actions = DetailPlayActions(onPlay: () => played++, onShuffle: () => shuffled++);
    await tester.pumpWidget(_group(idle: true, actions: actions));
    await tester.pumpAndSettle();
    // The detail pose keeps the pill even with nothing playing.
    expect(find.byKey(_pill), findsOneWidget);
    expect(_home, findsNothing);
    final play = find.byKey(const ValueKey('edge.play'));
    final more = find.byKey(const ValueKey('edge.more'));
    expect(tester.getSize(more), const Size(EdgeSplitTokens.buttonWidth, EdgeSplitTokens.splitMenuHeight));
    expect(tester.getRect(more).top - tester.getRect(play).bottom, closeTo(EdgeSplitTokens.splitGap, 0.01));
    // A cutout on this edge: no room for a Shuffle segment.
    expect(find.byKey(const ValueKey('edge.shuffle')), findsNothing);
    await tester.tap(play);
    expect(played, 1);
    // ▾ carries Shuffle play.
    await tester.tap(more);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Shuffle play'));
    await tester.pumpAndSettle();
    expect(shuffled, 1);
  });

  testWidgets('should_addShuffleSegment_when_capsuleIsRoomy', (tester) async {
    _landscape(tester);
    var shuffled = 0;
    await tester.pumpWidget(
      _group(
        actions: DetailPlayActions(onPlay: () {}, onShuffle: () => shuffled++),
        segments: _roomy,
      ),
    );
    await tester.pumpAndSettle();
    final shuffle = find.byKey(const ValueKey('edge.shuffle'));
    expect(tester.getSize(shuffle).height, EdgeSplitTokens.splitShuffleHeight);
    await tester.tap(shuffle);
    expect(shuffled, 1);
    // Shuffle left the menu, which is then empty: ▾ does nothing.
    await tester.tap(find.byKey(const ValueKey('edge.more')));
    await tester.pumpAndSettle();
    expect(find.text('Shuffle play'), findsNothing);
  });

  testWidgets('should_turnIntoUndoAndDone_when_homeIsEdited', (tester) async {
    _landscape(tester);
    var undone = 0;
    var done = 0;
    BarEditPose pose(BarEditLeftSlot slot) =>
        BarEditPose(editing: true, leftSlot: slot, onLeftSlot: () => undone++, onDone: () => done++);
    await tester.pumpWidget(_group(editPose: pose(BarEditLeftSlot.undo)));
    await tester.pumpAndSettle();
    // The pill steps out while Home is edited.
    expect(find.byKey(_pill), findsNothing);
    expect(find.text('Undo'), findsOneWidget);
    expect(find.byIcon(YoinSymbols.check), findsOneWidget);
    expect(find.bySemanticsLabel('Done'), findsOneWidget);
    await tester.tap(_home);
    await tester.tap(_library);
    expect(undone, 1);
    expect(done, 1);
  });

  testWidgets('should_showSearchHintTowardContent_when_libraryLongPressed', (tester) async {
    _landscape(tester);
    var searched = 0;
    await tester.pumpWidget(_group(onLibraryLongPress: () => searched++));
    await tester.longPress(_library);
    await tester.pumpAndSettle();
    expect(searched, 1);
    final hint = tester.getRect(find.byIcon(YoinSymbols.search));
    expect(hint.left, greaterThan(EdgeSplitTokens.groupInset + EdgeSplitTokens.groupWidth));
    // It leaves on its own after the hold.
    await tester.pump(const Duration(milliseconds: EdgeSplitTokens.searchHintHoldMs + 50));
    await tester.pumpAndSettle();
    expect(find.byIcon(YoinSymbols.search), findsNothing);
  });
}
