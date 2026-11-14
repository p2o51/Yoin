import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/memory/album_memory_candidate.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/home/home_memory_bubble.dart';
import 'package:yoin/home/memory_bubble_director.dart';
import 'package:yoin/memories/home_memory_pill.dart';
import 'package:yoin/memories/memories_reveal_scope.dart';
import 'package:yoin/theme/theme.dart';

final _pang = AlbumMemoryCandidate(
  profileId: 'p1',
  albumId: MediaId.subsonic('pang'),
  albumName: 'Pang',
  artistName: 'Caroline Polachek',
  totalTracks: 12,
  ratedTrackCount: 0,
  ratingCoverage: 0,
  hasAlbumReview: true,
  noteCount: 0,
  isMemoryEligible: true,
);

class _FixedPill extends HomeMemoryPillNotifier {
  _FixedPill(this.fixed);
  final HomeMemoryPillState fixed;

  @override
  HomeMemoryPillState build() => fixed;
}

class _NoChain implements math.Random {
  @override
  double nextDouble() => 0.99;
  @override
  int nextInt(int max) => 0;
  @override
  bool nextBool() => false;
}

class _Chain implements math.Random {
  @override
  double nextDouble() => 0;
  @override
  int nextInt(int max) => 0;
  @override
  bool nextBool() => false;
}

Future<ProviderContainer> _pump(
  WidgetTester tester,
  HomeMemoryPillState pill, {
  math.Random? random,
  Widget? header,
}) async {
  final container = ProviderContainer(
    overrides: [
      homeMemoryPillProvider.overrideWith(() => _FixedPill(pill)),
      memoryBubbleSeenStoreProvider.overrideWithValue(MemoryBubbleSeenStore.inMemory()),
    ],
  );
  addTearDown(container.dispose);
  await tester.pumpWidget(
    UncontrolledProviderScope(
      container: container,
      child: MaterialApp(
        theme: yoinTheme(Brightness.light),
        home: Scaffold(
          body: Column(
            children: [
              HomeMemoryBubble(
                rowTop: 12,
                trailingReserve: 50,
                timings: const MemoryBubbleTimings(idleMs: 4500, chatterDwellMs: 2500),
                random: random ?? _NoChain(),
                child: header ?? const SizedBox(height: 90, width: double.infinity, child: Text('Home')),
              ),
              const Expanded(child: SizedBox.expand()),
            ],
          ),
        ),
      ),
    ),
  );
  await tester.pump();
  return container;
}

Future<void> _frames(WidgetTester tester, Duration total) async {
  const step = Duration(milliseconds: 16);
  for (var t = Duration.zero; t < total; t += step) {
    await tester.pump(step);
  }
}

void main() {
  // Unkeyed: never news, so only the idle clock makes it speak.
  final memoryPill = HomeMemoryPillState(latest: _pang, noteCount: 2, resolved: true, scope: 'subsonic|p1');

  testWidgets('should_speakTheLatestMemory_when_homeIsLeftAloneForTheIdleWindow', (tester) async {
    await _pump(tester, memoryPill);
    await _frames(tester, const Duration(milliseconds: 4400));
    expect(find.text('Pang'), findsNothing);
    await _frames(tester, const Duration(milliseconds: 800));
    expect(find.text('Pang'), findsOneWidget);
    expect(find.bySemanticsLabel('Memories. Latest: Pang by Caroline Polachek'), findsOneWidget);
  });

  testWidgets('should_openMemoriesOnThatMemory_when_theBubbleIsTapped', (tester) async {
    final container = await _pump(tester, memoryPill);
    await _frames(tester, const Duration(milliseconds: 5500));
    final handle = container.read(memoriesRevealHandleProvider);
    expect(handle.pendingFocus, isNull);
    await tester.tapAt(tester.getCenter(find.text('Pang')));
    await tester.pump();
    expect(handle.pendingFocus, _pang.sessionId);
    // A tap is a touch too: it flies back into the arrow.
    await _frames(tester, const Duration(seconds: 2));
    expect(find.text('Pang'), findsNothing);
  });

  testWidgets('should_tuckAway_when_theScreenIsTouchedElsewhere', (tester) async {
    final container = await _pump(tester, memoryPill);
    await _frames(tester, const Duration(milliseconds: 5500));
    expect(find.text('Pang'), findsOneWidget);
    await tester.tapAt(const Offset(20, 500));
    await _frames(tester, const Duration(seconds: 2));
    expect(find.text('Pang'), findsNothing);
    expect(container.read(memoriesRevealHandleProvider).pendingFocus, isNull);
    // The idle clock started over: left alone again, it speaks again — a tip's turn now.
    await _frames(tester, const Duration(milliseconds: 4000));
    expect(find.text('Refresh Library'), findsOneWidget);
  });

  testWidgets('should_sayATip_when_thereAreNoMemories', (tester) async {
    await _pump(tester, const HomeMemoryPillState(resolved: true, scope: 'subsonic|p1'));
    await _frames(tester, const Duration(milliseconds: 5500));
    expect(find.text('Refresh Library'), findsOneWidget);
    // Tapping a tip that isn't the pull just sends it away.
    await tester.tapAt(tester.getCenter(find.text('Refresh Library')));
    await _frames(tester, const Duration(seconds: 2));
    expect(find.text('Refresh Library'), findsNothing);
  });

  testWidgets('should_openMemories_when_theArrowIsTapped', (tester) async {
    final container = await _pump(tester, memoryPill);
    final handle = container.read(memoriesRevealHandleProvider);
    await tester.tap(find.bySemanticsLabel('Memories'));
    await tester.pump();
    // No controller in this harness: the open lands as no focus at all.
    expect(handle.pendingFocus, isNull);
    expect(find.text('Pang'), findsNothing);
  });
  // The header's title as Home lays it: at the row's start, in MemoryBubbleTitle.
  const titled = SizedBox(
    height: 90,
    width: double.infinity,
    child: Align(alignment: Alignment.bottomLeft, child: MemoryBubbleTitle(child: Text('Good evening\nHome'))),
  );
  double titleOpacity(WidgetTester tester) => tester
      .widget<Opacity>(find.ancestor(of: find.text('Good evening\nHome'), matching: find.byType(Opacity)).first)
      .opacity;

  testWidgets('should_keepTheTitle_when_theBubbleLandsClearOfItOnAWideWindow', (tester) async {
    tester.view.physicalSize = const Size(1400, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await _pump(tester, memoryPill, header: titled);
    await _frames(tester, const Duration(milliseconds: 5500));
    expect(find.text('Pang'), findsOneWidget);
    expect(titleOpacity(tester), 1);
  });

  testWidgets('should_letTheTitleGiveWay_when_theBubbleSpeaksOverItOnAPhone', (tester) async {
    tester.view.physicalSize = const Size(400, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await _pump(tester, memoryPill, header: titled);
    expect(titleOpacity(tester), 1);
    await _frames(tester, const Duration(milliseconds: 5500));
    expect(find.text('Pang'), findsOneWidget);
    expect(titleOpacity(tester), lessThan(0.01));
  });

  testWidgets('should_labelWhatItShows_when_aMemoryHandsOverToTheNextLine', (tester) async {
    final harbor = AlbumMemoryCandidate(
      profileId: 'p1',
      albumId: MediaId.subsonic('harbor'),
      albumName: 'Harbor Lights',
      totalTracks: 10,
      ratedTrackCount: 0,
      ratingCoverage: 0,
      hasAlbumReview: true,
      noteCount: 0,
      isMemoryEligible: true,
    );
    final pill = HomeMemoryPillState(latest: _pang, recall: [harbor], noteCount: 1, resolved: true, scope: 'subsonic|p1');
    await _pump(tester, pill, random: _Chain());
    await _frames(tester, const Duration(milliseconds: 5500));
    // The idle turn brings back a memory from the recall, and says that one.
    expect(find.text('Harbor Lights'), findsOneWidget);
    expect(find.bySemanticsLabel('Memories. Latest: Harbor Lights'), findsOneWidget);
    expect(find.bySemanticsLabel(RegExp('Pang')), findsNothing);
    // It chains on to a tip: the label moves with it.
    await _frames(tester, const Duration(milliseconds: 3500));
    expect(find.text('Refresh Library'), findsOneWidget);
    expect(find.bySemanticsLabel(RegExp('Harbor Lights')), findsNothing);
  });
}

