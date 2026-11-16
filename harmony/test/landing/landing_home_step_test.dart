import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/home/home_section.dart';
import 'package:yoin/landing/landing_home_step.dart';
import 'package:yoin/landing/landing_view_model.dart';

void main() {
  Future<List<(int, int)>> pumpScene(WidgetTester tester, {List<(HomeSection, bool)>? toggles}) async {
    tester.view.physicalSize = const Size(400, 1400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final moves = <(int, int)>[];
    var layout = HomeLayout.defaultLayout;
    await tester.pumpWidget(
      MaterialApp(
        home: MediaQuery(
          data: const MediaQueryData(size: Size(400, 1400), disableAnimations: true),
          child: Scaffold(
            body: StatefulBuilder(
              builder: (context, setState) => HomeSectionsScene(
                sections: landingShownSections(layout),
                onEnabled: (section, on) {
                  toggles?.add((section, on));
                  setState(() => layout = withSectionEnabled(layout, section, on));
                },
                onMove: (from, to) {
                  moves.add((from, to));
                  final shown = landingShownSections(layout);
                  final list = [...layout.sections];
                  final a = list.indexOf(shown[from]);
                  final b = list.indexOf(shown[to]);
                  list.insert(b, list.removeAt(a));
                  setState(() => layout = HomeLayout(list));
                },
              ),
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    return moves;
  }

  testWidgets('should_listTheSectionsThisBuildDraws_when_shown', (tester) async {
    await pumpScene(tester);
    expect(find.text('Activities'), findsOneWidget);
    expect(find.text('All Memories'), findsOneWidget);
    expect(find.text('Lyric Cards'), findsNothing);
    expect(find.text('To change how many rows a section shows, long-press Home later.'), findsOneWidget);
  });

  testWidgets('should_moveARowDown_when_itsHandleIsDraggedPastTheNext', (tester) async {
    final moves = await pumpScene(tester);
    final handle = find.bySemanticsLabel('Reorder Activities');
    final rowHeight = tester.getSize(find.byType(SectionSketch).first).height + 20;
    await tester.drag(handle, Offset(0, rowHeight * 1.2));
    await tester.pumpAndSettle();
    expect(moves, [(0, 1)]);
    // Jump Back In now leads.
    final activities = tester.getTopLeft(find.text('Activities')).dy;
    final jump = tester.getTopLeft(find.text('Jump Back In')).dy;
    expect(jump, lessThan(activities));
  });

  testWidgets('should_switchASectionOff_when_itsSwitchTapped', (tester) async {
    final toggles = <(HomeSection, bool)>[];
    await pumpScene(tester, toggles: toggles);
    await tester.tap(find.byType(Switch).first);
    await tester.pumpAndSettle();
    expect(toggles, [(HomeSection.activities, false)]);
  });
}
