// About's long-press sheet offers Share as image (Android AboutEntrySheet,
// owner 2026-11-25): inside Now Playing, for the AI's rows, never the
// listener's own; it asks for the sheet on 关于 with that row.
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/about/song_about_store.dart';
import 'package:yoin/nowplaying/about_widgets.dart';
import 'package:yoin/nowplaying/share_content.dart';
import 'package:yoin/nowplaying/share_sheet.dart';

SongAboutEntry _entry(String kind) => SongAboutEntry(
  titleKey: 't',
  artistKey: 'a',
  albumKey: '',
  titleDisplay: 'T',
  artistDisplay: 'A',
  albumDisplay: '',
  kind: kind,
  entryKey: kind == 'canonical' ? 'review' : 'k',
  answerText: 'Words.',
  createdAt: 1,
  updatedAt: 1,
);

void main() {
  Future<List<ShareRequest>> open(WidgetTester tester, SongAboutEntry entry, {bool scoped = true}) async {
    final asked = <ShareRequest>[];
    final actions = AboutEntryActions(canManage: () => false, onManage: (_) {}, onRegenerate: (_) {});
    Widget page = Builder(
      builder: (context) => TextButton(
        onPressed: () => showAboutEntrySheet(context, entry: entry, label: 'About', actions: actions),
        child: const Text('open'),
      ),
    );
    if (scoped) page = NowPlayingShareScope(open: asked.add, child: page);
    await tester.pumpWidget(
      ProviderScope(
        child: MaterialApp(
          home: Scaffold(body: Center(child: page)),
        ),
      ),
    );
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    return asked;
  }

  testWidgets('should_openTheSheetOnAbout_when_anAiRowIsShared', (tester) async {
    final entry = _entry('canonical');
    final asked = await open(tester, entry);
    await tester.tap(find.text('Share as image'));
    await tester.pumpAndSettle();
    expect(asked.single.tab, ShareTab.about);
    expect(asked.single.aboutFocus, entry);
  });

  testWidgets('should_offerNoShare_when_theRowIsTheListenersOwn', (tester) async {
    await open(tester, _entry('own'));
    expect(find.text('Share as image'), findsNothing);
  });

  testWidgets('should_offerNoShare_when_outsideNowPlaying', (tester) async {
    await open(tester, _entry('ask'), scoped: false);
    expect(find.text('Share as image'), findsNothing);
  });
}
