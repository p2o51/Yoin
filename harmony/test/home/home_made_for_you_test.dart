import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/profile/profile.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/home/home_made_for_you.dart';
import 'package:yoin/home/home_screen.dart';
import 'package:yoin/home/home_shelves.dart';
import 'package:yoin/home/home_view_model.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/theme/theme.dart';
import 'package:yoin/ui/adaptive/yoin_window_info.dart';

import 'home_fakes.dart';

/// An Apple Music-like account: Home's lists, plus the mixes it makes ([MusicMadeForYou]).
final class _MixSource extends HomeFakeSource implements MusicMadeForYou {
  _MixSource(this.mixes);

  /// What each read answers, in turn (the last repeats); an [Exception] fails that read.
  final List<Object> mixes;
  int reads = 0;

  @override
  String get provider => MediaId.providerAppleMusic;

  @override
  Future<List<MadeForYouPlaylist>> madeForYou() async {
    final answer = mixes[reads < mixes.length ? reads : mixes.length - 1];
    reads++;
    if (answer is Exception) throw answer;
    return answer as List<MadeForYouPlaylist>;
  }
}

class _PushCounter extends NavigatorObserver {
  int pushes = 0;
  @override
  void didPush(Route<dynamic> route, Route<dynamic>? previousRoute) => pushes++;
}

MadeForYouPlaylist _mix(String id, String title) => MadeForYouPlaylist(
  id: MediaId(MediaId.providerAppleMusic, 'pl.pm-$id'),
  title: title,
  subtitle: 'Updated Wednesdays',
  cover: CoverUrl('https://art.test/$id/600x600bb.jpg'),
);

/// Home's Made For You (Android HomeMadeForYou.kt): the last shelf at once,
/// read again when Home shows after a while, kept per account, hidden for a
/// service that makes none.
void main() {
  late DateTime now;
  late InMemoryStore store;

  setUp(() {
    HomeViewModel.clearProcessCache();
    now = homeTestNow;
    store = InMemoryStore();
  });

  Future<(ProviderContainer, String)> container(
    MusicSource source, {
    List<Duration> delays = const [Duration.zero],
  }) async {
    final c = ProviderContainer(
      overrides: [
        keyValueStoreProvider.overrideWithValue(store),
        secretStoreProvider.overrideWithValue(InMemoryStore()),
        activeSourceProvider.overrideWith((ref) async => source),
        homeClockProvider.overrideWithValue(() => now),
        madeForYouRetryDelaysProvider.overrideWithValue(delays),
      ],
    );
    final profile = await c
        .read(profileRepositoryProvider)
        .add(
          provider: MediaId.providerAppleMusic,
          displayName: 'Apple',
          credentials: const AppleMusicCredentials(endpoint: 'https://token.test', musicUserToken: 'u'),
        );
    c.listen(madeForYouProvider, (_, _) {});
    await c.read(activeSourceProvider.future);
    return (c, profile.id);
  }

  Future<void> flush() => pumpEventQueue();

  test('should_readTheMixesAndKeepThem_when_anAppleAccountIsActive', () async {
    final source = _MixSource([
      [_mix('fav', 'Favorites Mix'), _mix('new', 'New Music Mix')],
    ]);
    final (c, profileId) = await container(source);
    addTearDown(c.dispose);
    await flush();

    expect(c.read(madeForYouProvider).map((m) => m.title), ['Favorites Mix', 'New Music Mix']);
    final saved = jsonDecode(store.values[MadeForYouNotifier.keyFor(profileId)]!) as List;
    expect(saved.map((m) => (m as Map)['title']), ['Favorites Mix', 'New Music Mix']);
  });

  test('should_showTheLastShelfAtOnce_when_theReadHasNotLandedYet', () async {
    final source = _MixSource([Exception('offline')]);
    final (c, profileId) = await container(source);
    addTearDown(c.dispose);
    store.values[MadeForYouNotifier.keyFor(profileId)] = jsonEncode([_mix('chill', 'Chill Mix').toJson()]);
    c.invalidate(madeForYouProvider);
    await flush();

    expect(c.read(madeForYouProvider).map((m) => m.title), ['Chill Mix']);
  });

  test('should_tryAgain_when_aReadFails', () async {
    final source = _MixSource([
      Exception('slow'),
      Exception('slow'),
      [_mix('fav', 'Favorites Mix')],
    ]);
    final (c, _) = await container(source, delays: const [Duration.zero, Duration.zero, Duration.zero]);
    addTearDown(c.dispose);
    await flush();

    expect(source.reads, 3);
    expect(c.read(madeForYouProvider).single.title, 'Favorites Mix');
  });

  test('should_readAgainOnlyAfterTenMinutes_when_homeShowsAgain', () async {
    final source = _MixSource([
      [_mix('fav', 'Favorites Mix')],
      [_mix('get', 'Get Up! Mix')],
    ]);
    final (c, _) = await container(source);
    addTearDown(c.dispose);
    await flush();
    expect(source.reads, 1);

    now = now.add(const Duration(minutes: 9));
    c.read(madeForYouProvider.notifier).onHomeShown();
    await flush();
    expect(source.reads, 1);

    now = now.add(const Duration(minutes: 2));
    c.read(madeForYouProvider.notifier).onHomeShown();
    await flush();
    expect(source.reads, 2);
    expect(c.read(madeForYouProvider).single.title, 'Get Up! Mix');
  });

  test('should_neverKeepTheOldAccountsMixes_when_aSwitchIsStillLoading', () async {
    final first = _MixSource([
      [_mix('fav', 'Favorites Mix')],
    ]);
    final second = _MixSource([
      [_mix('chill', 'Chill Mix')],
    ]);
    var current = Future<MusicSource?>.value(first);
    final c = ProviderContainer(
      overrides: [
        keyValueStoreProvider.overrideWithValue(store),
        secretStoreProvider.overrideWithValue(InMemoryStore()),
        activeSourceProvider.overrideWith((ref) => current),
        homeClockProvider.overrideWithValue(() => now),
        madeForYouRetryDelaysProvider.overrideWithValue(const [Duration.zero]),
      ],
    );
    addTearDown(c.dispose);
    Future<String> add(String name) async => (await c
            .read(profileRepositoryProvider)
            .add(
              provider: MediaId.providerAppleMusic,
              displayName: name,
              credentials: const AppleMusicCredentials(endpoint: 'https://token.test', musicUserToken: 'u'),
            ))
        .id;
    await add('First');
    c.listen(madeForYouProvider, (_, _) {});
    await c.read(activeSourceProvider.future);
    await flush();
    expect(first.reads, 1);

    // The switch: the second account is active in storage, its source still being built.
    final secondId = await add('Second');
    final building = Completer<MusicSource?>();
    current = building.future;
    c.invalidate(activeSourceProvider);
    await flush();
    expect(first.reads, 1, reason: "the first account's source is not read for the second");
    expect(store.values[MadeForYouNotifier.keyFor(secondId)], isNull);

    building.complete(second);
    await flush();
    expect(c.read(madeForYouProvider).single.title, 'Chill Mix');
    final saved = jsonDecode(store.values[MadeForYouNotifier.keyFor(secondId)]!) as List;
    expect(saved.map((m) => (m as Map)['title']), ['Chill Mix']);
  });

  test('should_haveNoShelf_when_theServiceMakesNone', () async {
    final (c, _) = await container(HomeFakeSource());
    addTearDown(c.dispose);
    await flush();
    c.read(madeForYouProvider.notifier).onHomeShown();
    await flush();
    expect(c.read(madeForYouProvider), isEmpty);
  });

  test('should_sayWhoseItIs_when_aCardIsLabelled', () {
    expect(madeForYouCardSubtitle(_mix('fav', 'Favorites Mix')), 'Apple Music');
  });

  testWidgets('should_showTheMixesAndOpenOne_when_theAccountHasSome', (tester) async {
    tester.view.physicalSize = const Size(400, 3000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final source = _MixSource([
      [_mix('fav', 'Favorites Mix'), _mix('chill', 'Chill Mix')],
    ]);
    final h = await homeContainer(store: store, source: source);
    final c = h.container;
    final pushes = _PushCounter();
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: c,
        child: MaterialApp(
          theme: yoinTheme(Brightness.light),
          navigatorObservers: [pushes],
          builder: (context, child) => YoinWindowScope.measured(child: child!),
          home: const Scaffold(body: HomeScreen()),
        ),
      ),
    );
    for (var i = 0; i < 20; i++) {
      await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 2)));
      await tester.pump(const Duration(milliseconds: 100));
    }

    expect(find.byType(MadeForYouSection), findsOneWidget);
    expect(find.text('Made For You'), findsOneWidget);
    expect(find.text('Favorites Mix'), findsOneWidget);
    expect(find.text('Apple Music'), findsNWidgets(2));

    // A tap opens the playlist's page.
    final before = pushes.pushes;
    await tester.tap(find.text('Chill Mix'));
    await tester.pump(const Duration(milliseconds: 50));
    expect(pushes.pushes, before + 1);
    await tester.pumpWidget(const SizedBox.shrink());
    c.dispose();
  });

  testWidgets('should_leaveTheSectionOut_when_theAccountIsSubsonic', (tester) async {
    tester.view.physicalSize = const Size(400, 3000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final h = await homeContainer(
      store: store,
      source: HomeFakeSource(playlists: [playlist('p1', songs: 2)]),
    );
    await tester.pumpWidget(
      UncontrolledProviderScope(
        container: h.container,
        child: MaterialApp(
          theme: yoinTheme(Brightness.light),
          builder: (context, child) => YoinWindowScope.measured(child: child!),
          home: const Scaffold(body: HomeScreen()),
        ),
      ),
    );
    for (var i = 0; i < 20; i++) {
      await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 2)));
      await tester.pump(const Duration(milliseconds: 100));
    }
    expect(find.byType(MadeForYouSection), findsNothing);
    expect(find.text('Made For You'), findsNothing);
    await tester.pumpWidget(const SizedBox.shrink());
    h.container.dispose();
  });
}
