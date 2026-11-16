import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/memory/memory_ledger.dart';
import 'package:yoin/data/memory/memory_ledger_list.dart';
import 'package:yoin/data/memory/memory_store.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/memories/ledger/memory_grid_view.dart';
import 'package:yoin/memories/ledger/memory_ledger_home_card.dart';
import 'package:yoin/memories/ledger/memory_ledger_screen.dart';
import 'package:yoin/memories/ledger/memory_ledger_view_model.dart';
import 'package:yoin/memories/memories_view_model.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';

import '../support/fake_playback_engine.dart';

DateTime _dayOf(int ms) {
  final t = DateTime.fromMillisecondsSinceEpoch(ms, isUtc: true);
  return DateTime.utc(t.year, t.month, t.day);
}

int _at(int m, int d) => DateTime.utc(2026, m, d, 12).millisecondsSinceEpoch;

final _album = MemoryLedgerAlbum(albumId: MediaId.subsonic('a'), name: 'Glass Orchard', artist: 'The Meridians');

MemoryLedgerState _state() => MemoryLedgerState.of(
  [
    MemoryLedgerEntry(
      id: 'title:a',
      kind: MemoryLedgerKind.title,
      at: _at(11, 5),
      album: _album,
      text: 'The rain year',
    ),
    MemoryLedgerEntry(
      id: 'review:a',
      kind: MemoryLedgerKind.review,
      at: _at(11, 4),
      album: _album,
      text: 'Rain again.',
    ),
    MemoryLedgerEntry(
      id: 'note:1',
      kind: MemoryLedgerKind.songNote,
      at: _at(9, 3),
      album: _album,
      text: 'the bridge',
      trackTitle: 'Satellites',
      songId: MediaId.subsonic('a-t1'),
      positionMs: 90000,
    ),
  ],
  today: DateTime.utc(2026, 11, 15),
  dayOf: _dayOf,
);

class _FixedLedger extends MemoryLedgerViewModel {
  _FixedLedger(this.fixed);
  final MemoryLedgerState fixed;

  @override
  Future<MemoryLedgerState> build() async => fixed;
}

void main() {
  test('should_runColumnsByWidth_when_windowGrows', () {
    expect(MemoryLedgerLayout.of(const YoinWindowInfo(392, 850)).columns, 1);
    expect(MemoryLedgerLayout.of(const YoinWindowInfo(700, 1000)).columns, 2);
    expect(MemoryLedgerLayout.of(const YoinWindowInfo(1280, 800)).columns, 3);
    expect(MemoryLedgerLayout.of(const YoinWindowInfo(1280, 800)).wide, isTrue);
    // A landscape phone is the phone layout whatever its width.
    expect(MemoryLedgerLayout.of(const YoinWindowInfo(900, 400)).columns, 1);
  });

  Future<InMemoryStore> pump(WidgetTester tester, Widget child, {Size size = const Size(392, 850)}) async {
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final kv = InMemoryStore();
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          keyValueStoreProvider.overrideWithValue(kv),
          playbackEngineProvider.overrideWithValue(FakePlaybackEngine()),
          activeSourceProvider.overrideWith((ref) async => null),
          memoryLedgerProvider.overrideWith(() => _FixedLedger(_state())),
        ],
        child: MaterialApp(home: YoinWindowScope.measured(child: child)),
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 600));
    return kv;
  }

  testWidgets('should_showTimeline_when_ledgerOpened', (tester) async {
    await pump(tester, const MemoryLedgerScreen());
    expect(find.text('All Memories'), findsWidgets);
    expect(find.text('Timeline'), findsOneWidget);
    expect(find.text('November'), findsOneWidget);
    expect(find.text('The rain year'), findsOneWidget);
    expect(find.text('Nothing written in October'), findsOneWidget);
    expect(find.text('the bridge'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('should_switchToCardsAndRemember_when_viewPicked', (tester) async {
    final kv = await pump(tester, const MemoryLedgerScreen());
    await tester.tap(find.text('Timeline'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 600));
    await tester.tap(find.text('Cards').last);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 800));
    expect(find.text('Cards'), findsOneWidget);
    expect(find.text('November'), findsNothing);
    expect(kv.values[memoryLedgerViewKey], MemoryLedgerView.cards.name);
  });

  testWidgets('should_showOnlyThatMonth_when_gridCellPicked', (tester) async {
    await pump(tester, const MemoryLedgerScreen());
    // Few days written: a month a cell, the last one is November.
    final grid = find.byType(MemoryGridView);
    final quads = find.descendant(of: grid, matching: find.byType(AspectRatio));
    await tester.tap(quads.last);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 800));
    expect(find.text('November 2026'), findsOneWidget);
    expect(find.text('the bridge'), findsNothing);
    expect(find.text('Nothing written in October'), findsNothing);
  });

  testWidgets('should_liftGridIntoBar_when_scrolledPastIt', (tester) async {
    await pump(tester, const MemoryLedgerScreen(), size: const Size(392, 700));
    final grid = find.byType(MemoryGridView);
    Opacity gridOpacity() => tester.widget<Opacity>(find.ancestor(of: grid, matching: find.byType(Opacity)).first);
    expect(gridOpacity().opacity, 1);
    await tester.drag(find.byType(ListView), const Offset(0, -500));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 600));
    // The squares flew up: the grid stepped aside and the bar formed (white title, light status icons).
    expect(gridOpacity().opacity, 0);
    final bar = tester.widget<AppBar>(find.byType(AppBar));
    expect(bar.systemOverlayStyle!.statusBarIconBrightness, Brightness.light);
    await tester.drag(find.byType(ListView), const Offset(0, 800));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 600));
    expect(gridOpacity().opacity, 1);
  });

  testWidgets('should_countWritings_when_homeCardShown', (tester) async {
    await pump(
      tester,
      Scaffold(
        body: MemoryLedgerHomeCard(state: _state(), onOpen: () {}),
      ),
    );
    expect(find.text('2'), findsOneWidget);
    expect(find.text('memories written'), findsOneWidget);
  });

  test('should_keepTitleInLedger_when_userNamedMemory', () {
    final entries = buildMemoryLedger(
      MemoryLedgerRows(
        titles: {_album.albumId: MemoryTitleRow(albumId: _album.albumId, title: 'Mine', updatedAt: 1)},
      ),
      album: (_) => _album,
      track: (_) => null,
    );
    expect(memoryLedgerItems(entries).whereType<MemoryLedgerAlbumItem>().single.title, 'Mine');
    expect(memoriesClockProvider, isNotNull);
  });
}
