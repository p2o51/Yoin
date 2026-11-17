import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin_symbols/yoin_symbols.dart';
import 'package:yoin/shell/detail_chrome.dart';
import 'package:yoin/shell/shell_state.dart';
import 'package:yoin/shell/yoin_bottom_bar.dart';
import 'package:yoin/shell/yoin_section.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';
import 'package:yoin/ui/component/expressive/expressive_split_button.dart';

Widget _bar(DetailPlayActions actions) => MaterialApp(
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

Finder get _splitMaterials => find.descendant(of: find.byType(ExpressiveSplitButton), matching: find.byType(Material));

void main() {
  testWidgets('should_listShuffleThenPageRows_when_menuOpened', (tester) async {
    _window(tester, const Size(412, 915));
    final tapped = <String>[];
    await tester.pumpWidget(
      _bar(
        DetailPlayActions(
          onPlay: () {},
          onShuffle: () => tapped.add('shuffle'),
          menuItems: [
            DetailMenuAction(icon: YoinSymbols.skipNext, label: 'Play next', onTap: () => tapped.add('next')),
            DetailMenuAction(icon: YoinSymbols.artist, label: 'Go to artist', onTap: () => tapped.add('artist')),
          ],
        ),
      ),
    );
    await tester.pumpAndSettle();

    await tester.tap(_splitMaterials.at(1));
    await tester.pumpAndSettle();
    final shuffle = tester.getTopLeft(find.text('Shuffle play'));
    final next = tester.getTopLeft(find.text('Play next'));
    final artist = tester.getTopLeft(find.text('Go to artist'));
    expect(shuffle.dy, lessThan(next.dy));
    expect(next.dy, lessThan(artist.dy));

    await tester.tap(find.text('Go to artist'));
    await tester.pumpAndSettle();
    expect(tapped, ['artist']);
    expect(find.text('Play next'), findsNothing);
  });

  testWidgets('should_keepMenuForPageRows_when_centredPosePromotesShuffle', (tester) async {
    _window(tester, const Size(900, 1200));
    await tester.pumpWidget(
      _bar(
        DetailPlayActions(
          onPlay: () {},
          onShuffle: () {},
          menuItems: [DetailMenuAction(icon: YoinSymbols.queue, label: 'Add to queue', onTap: () {})],
        ),
      ),
    );
    await tester.pumpAndSettle();
    // ▾ stays: the page's row still needs it, while Shuffle has its own button.
    expect(_splitMaterials, findsNWidgets(2));

    await tester.tap(_splitMaterials.at(1));
    await tester.pumpAndSettle();
    expect(find.text('Add to queue'), findsOneWidget);
    expect(find.text('Shuffle play'), findsNothing);
  });

  testWidgets('should_promotePageActionsBesideShuffle_when_centredDetailPose', (tester) async {
    _window(tester, const Size(900, 1200));
    final tapped = <String>[];
    await tester.pumpWidget(
      _bar(
        DetailPlayActions(
          onPlay: () {},
          onShuffle: () => tapped.add('shuffle'),
          menuItems: [
            DetailMenuAction(icon: YoinSymbols.queue, label: 'Add to queue', onTap: () {}),
            DetailMenuAction(
              icon: YoinSymbols.artist,
              label: 'Go to artist',
              promotable: true,
              onTap: () => tapped.add('artist'),
            ),
            DetailMenuAction(
              icon: YoinSymbols.share,
              label: 'Share',
              promotable: true,
              onTap: () => tapped.add('share'),
            ),
          ],
        ),
      ),
    );
    await tester.pumpAndSettle();

    // Shuffle, Go to artist and Share as round buttons, in that order, after the split.
    final shuffle = find.bySemanticsLabel('Shuffle play');
    final artist = find.bySemanticsLabel('Go to artist');
    final share = find.bySemanticsLabel('Share');
    expect(tester.getCenter(shuffle).dx, lessThan(tester.getCenter(artist).dx));
    expect(tester.getCenter(artist).dx, lessThan(tester.getCenter(share).dx));
    await tester.tap(share);
    await tester.pumpAndSettle();
    expect(tapped, ['share']);

    // ▾ keeps only what was not pulled out.
    await tester.tap(_splitMaterials.at(1));
    await tester.pumpAndSettle();
    expect(find.text('Add to queue'), findsOneWidget);
    expect(find.text('Go to artist'), findsNothing);
    expect(find.text('Share'), findsNothing);
  });

  testWidgets('should_keepPromotableRowsInMenu_when_portraitBar', (tester) async {
    _window(tester, const Size(412, 915));
    await tester.pumpWidget(
      _bar(
        DetailPlayActions(
          onPlay: () {},
          onShuffle: () {},
          menuItems: [DetailMenuAction(icon: YoinSymbols.share, label: 'Share', promotable: true, onTap: () {})],
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.bySemanticsLabel('Share'), findsNothing);

    await tester.tap(_splitMaterials.at(1));
    await tester.pumpAndSettle();
    expect(find.text('Shuffle play'), findsOneWidget);
    expect(find.text('Share'), findsOneWidget);
  });
}
