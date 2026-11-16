import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/shell/bar_edit_pose.dart';
import 'package:yoin/shell/bar_geometry.dart';
import 'package:yoin/shell/shell_state.dart';
import 'package:yoin/shell/yoin_bottom_bar.dart';
import 'package:yoin/shell/yoin_section.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';

Widget _bar(BarEditPose? pose, {bool hasTrack = true, List<YoinSection>? selected}) => MaterialApp(
  navigatorKey: appNavigatorKey,
  home: YoinWindowScope.measured(
    child: Scaffold(
      body: Align(
        alignment: Alignment.bottomCenter,
        child: YoinBottomBar(
          selected: YoinSection.home,
          onSelect: (s) => selected?.add(s),
          hasTrack: hasTrack,
          editPose: pose,
          pillBuilder: (context, _) => const Text('pill'),
        ),
      ),
    ),
  ),
);

void _window(WidgetTester tester) {
  tester.view.physicalSize = const Size(412, 915) * 2;
  tester.view.devicePixelRatio = 2;
  addTearDown(tester.view.reset);
}

void main() {
  group('resolveEditLeftSlot', () {
    test('should_offerUndo_when_historyExists', () => expect(resolveEditLeftSlot(2, 3), BarEditLeftSlot.undo));
    test('should_offerAdd_when_onlyHiddenSections', () => expect(resolveEditLeftSlot(0, 1), BarEditLeftSlot.add));
    test('should_dimUndo_when_nothingToDo', () => expect(resolveEditLeftSlot(0, 0), BarEditLeftSlot.undoDisabled));
  });

  test('should_swapOverTheMiddle_when_editProgresses', () {
    expect(barEditSwap(0.3), 0);
    expect(barEditSwap(0.5), closeTo(0.5, 1e-9));
    expect(barEditSwap(0.7), 1);
  });

  test('should_rideTheIdlePath_when_editing', () {
    final nav = resolveBarGeometry(const BarGeometryInput(innerWidth: 360, idle: 0));
    final edit = resolveBarGeometry(const BarGeometryInput(innerWidth: 360, idle: 0, edit: 1));
    expect(nav.composePill, isTrue);
    // The pill folds and the two halves share the bar, labelled.
    expect(edit.composePill, isFalse);
    expect(edit.homeWidth, closeTo((360 - 16) / 2, 0.01));
    expect(edit.libraryWidth, closeTo(edit.homeWidth, 0.01));
    expect(edit.labelAlpha, closeTo(1, 1e-9));
  });

  testWidgets('should_showUndoAndDone_when_homeIsEdited', (tester) async {
    _window(tester);
    final calls = <String>[];
    final selected = <YoinSection>[];
    BarEditPose pose(BarEditLeftSlot slot) => BarEditPose(
      editing: true,
      leftSlot: slot,
      onLeftSlot: () => calls.add('left'),
      onDone: () => calls.add('done'),
    );
    await tester.pumpWidget(_bar(null, selected: selected));
    await tester.pumpAndSettle();
    expect(find.text('pill'), findsOneWidget);
    await tester.pumpWidget(_bar(pose(BarEditLeftSlot.undo), selected: selected));
    await tester.pumpAndSettle();
    expect(find.text('pill'), findsNothing);
    expect(find.text('Undo'), findsWidgets);
    expect(find.text('Done'), findsOneWidget);
    await tester.tap(find.text('Done'));
    await tester.tap(find.bySemanticsLabel('Undo'));
    expect(calls, ['done', 'left']);
    expect(selected, isEmpty); // edit taps never navigate
    // Nothing to undo but hidden sections: Add.
    await tester.pumpWidget(_bar(pose(BarEditLeftSlot.add), selected: selected));
    await tester.pumpAndSettle();
    expect(find.bySemanticsLabel('Show hidden sections'), findsOneWidget);
    // Leaving: the destinations come back.
    await tester.pumpWidget(_bar(null, selected: selected));
    await tester.pumpAndSettle();
    expect(find.text('Done'), findsNothing);
    expect(find.text('pill'), findsOneWidget);
  });
}
