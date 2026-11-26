// The share sheet (Android ShareSheetTest): the tabs a song has, the layouts
// each offers, the layout each tab remembers, the tones (every family on
// every tab, each opening on its role's colour, a pick kept across tabs), Card / Story, and Save / Share (Home's lyric pick kept only from 歌词).
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/nowplaying/share_cards.dart';
import 'package:yoin/nowplaying/share_content.dart';
import 'package:yoin/nowplaying/share_sheet.dart';
import 'package:yoin/platform/share/image_share.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/theme/theme.dart';

import 'share_fixtures.dart';

class _Share implements ImageShare {
  _Share({this.ok = true});

  final bool ok;
  final List<String> calls = [];

  @override
  bool get canSave => true;

  @override
  Future<bool> save(Uint8List png, {required String fileName}) async {
    calls.add('save $fileName ${png.isNotEmpty}');
    return ok;
  }

  @override
  Future<bool> share(Uint8List png, {required String fileName, String? caption}) async {
    calls.add('share ${png.isNotEmpty} $caption');
    return ok;
  }
}

class _Harness {
  _Harness(this.share, this.store);

  final _Share share;
  final ShareLayoutStore store;
  final List<String> events = [];
}

Future<_Harness> _pump(
  WidgetTester tester, {
  ShareContent? content,
  ShareTab tab = ShareTab.lyrics,
  ShareLayoutStore? store,
  bool ok = true,
  Size size = const Size(412, 915),
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final h = _Harness(_Share(ok: ok), store ?? ShareLayoutStore.inMemory());
  await tester.pumpWidget(
    ProviderScope(
      overrides: [imageShareProvider.overrideWithValue(h.share), shareLayoutStoreProvider.overrideWithValue(h.store)],
      child: MaterialApp(
        theme: yoinTheme(Brightness.light),
        home: Builder(
          builder: (context) => Scaffold(
            body: Center(
              child: TextButton(
                onPressed: () => showShareSheet(
                  context,
                  content: content ?? shareFixture(cover: false),
                  initialTab: tab,
                  onMessage: (m) => h.events.add('message'),
                  onShared: () => h.events.add('shared'),
                  onKept: () => h.events.add('kept'),
                ),
                child: const Text('open'),
              ),
            ),
          ),
        ),
      ),
    ),
  );
  await tester.tap(find.text('open'));
  await tester.pumpAndSettle();
  return h;
}

/// The image the preview shows (the newest, when a cross-fade is under way).
ShareImage _preview(WidgetTester tester) => tester
    .widgetList<ShareImage>(
      find.descendant(of: find.byKey(const ValueKey('share-preview-box')), matching: find.byType(ShareImage)),
    )
    .last;

Future<void> _tapAndWait(WidgetTester tester, Key key) async {
  await tester.runAsync(() async {
    await tester.tap(find.byKey(key));
    await Future<void>.delayed(const Duration(milliseconds: 300));
  });
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('should_openOnTheAskedTab_when_theSongHasIt', (tester) async {
    await _pump(tester, tab: ShareTab.note);
    for (final tab in ShareTab.values) {
      expect(find.byKey(ValueKey('share-tab-${tab.storeKey}')), findsOneWidget);
    }
    expect(_preview(tester).layout, ShareLayout.noteBig);
    expect(find.byKey(const ValueKey('share-thumb-NoteTimeline')), findsOneWidget);
  });

  testWidgets('should_hideTheTab_when_theSongHasNothingForIt', (tester) async {
    final full = shareFixture(cover: false);
    await _pump(
      tester,
      content: ShareContent(song: full.song, lyrics: full.lyrics, note: full.note),
      tab: ShareTab.about,
    );
    expect(find.byKey(const ValueKey('share-tab-About')), findsNothing);
    // Asked for About, which isn't there: the first tab.
    expect(_preview(tester).layout, ShareLayout.lyricsClassic);
  });

  testWidgets('should_offerVerticalOnlyForCjk_when_listingLyricsLayouts', (tester) async {
    await _pump(tester);
    expect(find.byKey(const ValueKey('share-thumb-LyricsVertical')), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
    await _pump(tester, content: shareFixture(lang: 'en', cover: false));
    expect(find.byKey(const ValueKey('share-thumb-LyricsVertical')), findsNothing);
    expect(find.byKey(const ValueKey('share-thumb-LyricsPlayer')), findsOneWidget);
  });

  testWidgets('should_switchTheThumbsAndPreview_when_aTabIsPicked', (tester) async {
    await _pump(tester);
    await tester.tap(find.byKey(const ValueKey('share-tab-About')));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('share-thumb-AboutQuote')), findsOneWidget);
    expect(find.byKey(const ValueKey('share-thumb-LyricsPoster')), findsNothing);
    expect(_preview(tester).layout, ShareLayout.aboutReceipt);
  });

  testWidgets('should_rememberEachTabsLayout_when_reopened', (tester) async {
    final kv = InMemoryStore();
    var h = await _pump(tester, store: ShareLayoutStore(kv));
    await tester.tap(find.byKey(const ValueKey('share-thumb-LyricsPoster')));
    await tester.pumpAndSettle();
    expect(_preview(tester).layout, ShareLayout.lyricsPoster);
    await tester.tap(find.byKey(const ValueKey('share-tab-Note')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('share-thumb-NoteClipping')));
    await tester.pumpAndSettle();
    // Back on 歌词: its own layout, not the note's.
    await tester.tap(find.byKey(const ValueKey('share-tab-Lyrics')));
    await tester.pumpAndSettle();
    expect(_preview(tester).layout, ShareLayout.lyricsPoster);
    expect(kv.values, {'yoin_share_layouts.Lyrics': 'LyricsPoster', 'yoin_share_layouts.Note': 'NoteClipping'});

    // A new sheet (a new store reading the same device memory) opens on the remembered layouts.
    await tester.pumpWidget(const SizedBox());
    h = await _pump(tester, store: ShareLayoutStore(kv), tab: ShareTab.note);
    expect(_preview(tester).layout, ShareLayout.noteClipping);
    await tester.tap(find.byKey(const ValueKey('share-tab-Lyrics')));
    await tester.pumpAndSettle();
    expect(_preview(tester).layout, ShareLayout.lyricsPoster);
    expect(h.events, isEmpty);
  });

  testWidgets('should_fallBackToTheFirstLayout_when_theRememberedOneIsNotOffered', (tester) async {
    final kv = InMemoryStore()..values['yoin_share_layouts.Lyrics'] = 'LyricsVertical';
    await _pump(
      tester,
      store: ShareLayoutStore(kv),
      content: shareFixture(lang: 'en', cover: false),
    );
    expect(_preview(tester).layout, ShareLayout.lyricsClassic);
    // A value filed under another tab, or one a later build dropped, reads as none.
    kv.values['yoin_share_layouts.Lyrics'] = 'NoteBig';
    await tester.pumpWidget(const SizedBox());
    await _pump(tester, store: ShareLayoutStore(kv));
    expect(_preview(tester).layout, ShareLayout.lyricsClassic);
  });

  testWidgets('should_openEachTabOnItsOwnColour_when_nothingIsPicked', (tester) async {
    await _pump(tester, tab: ShareTab.note);
    expect(_preview(tester).tone, ShareTone.soft);
    await tester.tap(find.byKey(const ValueKey('share-tab-About')));
    await tester.pumpAndSettle();
    // About opens tertiary (Mist), and offers every family plus Paper (owner 2026-11-26).
    expect(_preview(tester).tone, ShareTone.mist);
    for (final tone in ShareTone.values) {
      expect(find.byKey(ValueKey('share-tone-${tone.kotlinName}')), findsOneWidget);
    }
    await tester.tap(find.byKey(const ValueKey('share-tab-Lyrics')));
    await tester.pumpAndSettle();
    expect(_preview(tester).tone, ShareTone.soft);
    expect(find.byKey(const ValueKey('share-tone-Paper')), findsNothing);
  });

  testWidgets('should_carryThePickedTone_when_switchingTabs', (tester) async {
    await _pump(tester, tab: ShareTab.about);
    await tester.tap(find.byKey(const ValueKey('share-tone-Soft')));
    await tester.pumpAndSettle();
    expect(_preview(tester).tone, ShareTone.soft);
    await tester.tap(find.byKey(const ValueKey('share-tab-Note')));
    await tester.pumpAndSettle();
    expect(_preview(tester).tone, ShareTone.soft);
    await tester.tap(find.byKey(const ValueKey('share-tone-Paper')));
    await tester.pumpAndSettle();
    expect(_preview(tester).tone, ShareTone.paper);
    // The lyrics don't offer Paper: their default; back on the note the pick is still Paper.
    await tester.tap(find.byKey(const ValueKey('share-tab-Lyrics')));
    await tester.pumpAndSettle();
    expect(_preview(tester).tone, ShareTone.soft);
    await tester.tap(find.byKey(const ValueKey('share-tab-Note')));
    await tester.pumpAndSettle();
    expect(_preview(tester).tone, ShareTone.paper);
  });

  testWidgets('should_keepTheTabsFixed_when_theSheetScrolls', (tester) async {
    await _pump(tester, size: const Size(412, 700));
    final tabTop = tester.getTopLeft(find.byKey(const ValueKey('share-tab-Lyrics'))).dy;
    final thumbTop = tester.getTopLeft(find.byKey(const ValueKey('share-thumb-LyricsClassic'))).dy;
    await tester.drag(find.byKey(const ValueKey('share-sheet-scroll')), const Offset(0, -200));
    await tester.pumpAndSettle();
    expect(tester.getTopLeft(find.byKey(const ValueKey('share-tab-Lyrics'))).dy, tabTop);
    expect(tester.getTopLeft(find.byKey(const ValueKey('share-thumb-LyricsClassic'))).dy, lessThan(thumbTop));
  });

  testWidgets('should_previewAStory_when_storyIsPicked', (tester) async {
    await _pump(tester);
    final box = tester.getSize(find.byKey(const ValueKey('share-preview-box')));
    await tester.tap(find.byKey(const ValueKey('share-format-Story')));
    await tester.pumpAndSettle();
    expect(_preview(tester).format, ShareFormat.story);
    // The fixed box: the sheet doesn't resize on a switch.
    expect(tester.getSize(find.byKey(const ValueKey('share-preview-box'))), box);
    expect(box.height, closeTo(915 * ShareSheetMetrics.previewHeightFraction, 0.01));
  });

  testWidgets('should_layOutTwoPanes_when_theWindowIsExpanded', (tester) async {
    await _pump(tester, size: const Size(1280, 800));
    final preview = tester.getCenter(find.byKey(const ValueKey('share-preview-box')));
    final tabs = tester.getCenter(find.byKey(const ValueKey('share-tab-Lyrics')));
    expect(preview.dx, lessThan(tabs.dx));
  });

  testWidgets('should_keepTheLinesAndSendTheCaption_when_lyricsShared', (tester) async {
    final h = await _pump(tester);
    await _tapAndWait(tester, const ValueKey('share-share'));
    expect(h.share.calls, ['share true 他夏了夏天 · 苏打绿']);
    expect(h.events, ['kept', 'shared']);
  });

  testWidgets('should_saveWithoutKeeping_when_notOnTheLyricsTab', (tester) async {
    final h = await _pump(tester, tab: ShareTab.note);
    await _tapAndWait(tester, const ValueKey('share-save'));
    expect(h.share.calls.single, startsWith('save Yoin - 他夏了夏天 - '));
    expect(h.events, isEmpty);
    expect(find.text('Saved'), findsOneWidget);
  });

  testWidgets('should_keepTheLines_when_lyricsSaved', (tester) async {
    final h = await _pump(tester);
    await _tapAndWait(tester, const ValueKey('share-save'));
    expect(h.events, ['kept']);
  });

  testWidgets('should_sayItCouldnt_when_shareFails', (tester) async {
    final h = await _pump(tester, ok: false);
    await _tapAndWait(tester, const ValueKey('share-save'));
    await _tapAndWait(tester, const ValueKey('share-share'));
    expect(h.events, ['message']);
    expect(find.text('Try again'), findsOneWidget);
  });

  testWidgets('should_notOpen_when_theSongHasNothingToShare', (tester) async {
    final full = shareFixture(cover: false);
    await _pump(tester, content: ShareContent(song: full.song));
    expect(find.byKey(const ValueKey('share-sheet')), findsNothing);
  });
}
