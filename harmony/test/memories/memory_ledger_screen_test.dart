import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/memory/memory_ledger.dart';
import 'package:yoin/data/memory/memory_ledger_list.dart';
import 'package:yoin/data/memory/memory_store.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/profile/profile_repository.dart';
import 'package:yoin/memories/ledger/memory_grid_view.dart';
import 'package:yoin/memories/ledger/memory_ledger_home_card.dart';
import 'package:yoin/memories/ledger/memory_ledger_screen.dart';
import 'package:yoin/memories/ledger/memory_ledger_view_model.dart';
import 'package:yoin/memories/memories_view_model.dart';
import 'package:yoin/memories/other_profiles.dart';
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

  Future<InMemoryStore> pump(
    WidgetTester tester,
    Widget child, {
    Size size = const Size(392, 850),
    MemoryLedgerState? state,
    InMemoryStore? store,
    ProfileRepository? repo,
  }) async {
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final kv = store ?? InMemoryStore();
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          keyValueStoreProvider.overrideWithValue(kv),
          playbackEngineProvider.overrideWithValue(FakePlaybackEngine()),
          activeSourceProvider.overrideWith((ref) async => null),
          memoryLedgerProvider.overrideWith(() => _FixedLedger(state ?? _state())),
          if (repo != null) profileRepositoryProvider.overrideWithValue(repo),
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

  group('another account', () {
    const own = LedgerProfile(id: 'p1', name: 'ann', provider: MediaId.providerSubsonic, isActive: true);
    const other = LedgerProfile(id: 'p2', name: 'ben', provider: MediaId.providerSubsonic, shape: 3, canSwitch: true);
    final mine = MemoryLedgerAlbum(albumId: MediaId.subsonic('a'), name: 'Glass Orchard', profileId: 'p1');
    final theirs = MemoryLedgerAlbum(albumId: MediaId.subsonic('x'), name: 'Night Bus', profileId: 'p2');

    MemoryLedgerState twoAccounts({LedgerProfile second = other}) => MemoryLedgerState.of(
      [
        MemoryLedgerEntry(
          id: 'review:p1|subsonic|a',
          kind: MemoryLedgerKind.review,
          at: _at(11, 5),
          album: mine,
          text: 'Rain again.',
          profileId: 'p1',
        ),
        MemoryLedgerEntry(
          id: 'review:p2|subsonic|x',
          kind: MemoryLedgerKind.review,
          at: _at(11, 4),
          album: theirs,
          text: 'Late trains.',
          profileId: 'p2',
        ),
        MemoryLedgerEntry(
          id: 'song-note:n2',
          kind: MemoryLedgerKind.songNote,
          at: _at(11, 3),
          album: theirs,
          text: 'the tunnel',
          trackTitle: 'Lights',
          songId: MediaId.subsonic('x-t1'),
          profileId: 'p2',
        ),
      ],
      today: DateTime.utc(2026, 11, 15),
      dayOf: _dayOf,
      profiles: [own, second],
      activeProfileId: 'p1',
    );

    testWidgets('should_sayWhoseItIs_when_memoryIsAnotherAccounts', (tester) async {
      await pump(tester, const MemoryLedgerScreen(), state: twoAccounts());
      // The album card and the note card each say it; the account in use's says nothing.
      expect(find.text('From ben'), findsNWidgets(2));
      expect(find.text('From ann'), findsNothing);
      expect(tester.takeException(), isNull);
    });

    testWidgets('should_askBeforeSwitching_when_otherAccountsAlbumTapped', (tester) async {
      final kv = InMemoryStore();
      final repo = ProfileRepository(kv, InMemoryStore());
      final b = await repo.add(
        provider: MediaId.providerSubsonic,
        displayName: 'B',
        credentials: const SubsonicCredentials(baseUrl: 'https://b.test', username: 'ben', password: 'p'),
      );
      final a = await repo.add(
        provider: MediaId.providerSubsonic,
        displayName: 'A',
        credentials: const SubsonicCredentials(baseUrl: 'https://a.test', username: 'ann', password: 'p'),
      );
      final state = MemoryLedgerState.of(
        [
          MemoryLedgerEntry(
            id: 'review:x',
            kind: MemoryLedgerKind.review,
            at: _at(11, 4),
            album: MemoryLedgerAlbum(albumId: MediaId.subsonic('x'), name: 'Night Bus', profileId: b.id),
            text: 'Late trains.',
            profileId: b.id,
          ),
          MemoryLedgerEntry(
            id: 'review:a',
            kind: MemoryLedgerKind.review,
            at: _at(11, 5),
            album: MemoryLedgerAlbum(albumId: MediaId.subsonic('a'), name: 'Glass Orchard', profileId: a.id),
            text: 'Rain again.',
            profileId: a.id,
          ),
        ],
        today: DateTime.utc(2026, 11, 15),
        dayOf: _dayOf,
        profiles: [
          LedgerProfile(id: a.id, name: 'ann', provider: MediaId.providerSubsonic, isActive: true),
          LedgerProfile(id: b.id, name: 'ben', provider: MediaId.providerSubsonic, canSwitch: true),
        ],
        activeProfileId: a.id,
      );
      // The notice's buttons need a little more than a phone's width in the test font.
      await pump(tester, const MemoryLedgerScreen(), state: state, store: kv, repo: repo, size: const Size(480, 900));
      await tester.ensureVisible(find.text('Late trains.'));
      await tester.pump(const Duration(milliseconds: 600));
      await tester.tap(find.text('Late trains.'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 800));
      expect(find.text('Switch to ben?'), findsOneWidget);
      // Cancel: nothing switches.
      await tester.tap(find.text('Cancel'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 800));
      expect((await repo.activeProfile())!.id, a.id);
      // Again, and switch.
      await tester.ensureVisible(find.text('Late trains.'));
      await tester.pump(const Duration(milliseconds: 600));
      await tester.tap(find.text('Late trains.'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 800));
      await tester.tap(find.text('Switch and open'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 800));
      expect((await repo.activeProfile())!.id, b.id);
      expect(kv.values[OtherProfilesNoticeStore.key], 'true');
    });

    testWidgets('should_stayShut_when_otherAccountCantBeSwitchedTo', (tester) async {
      await pump(
        tester,
        const MemoryLedgerScreen(),
        state: twoAccounts(
          second: const LedgerProfile(id: 'p2', name: 'ben', provider: MediaId.providerSubsonic),
        ),
      );
      await tester.ensureVisible(find.text('Late trains.'));
      await tester.pump(const Duration(milliseconds: 600));
      await tester.tap(find.text('Late trains.'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 800));
      expect(find.text('Switch to ben?'), findsNothing);
      expect(tester.takeException(), isNull);
    });
  });
}
