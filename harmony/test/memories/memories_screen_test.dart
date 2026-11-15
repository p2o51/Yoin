import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/memories/copy/memory_voice.dart';
import 'package:yoin/memories/memories_layout.dart';
import 'package:yoin/memories/memories_motion.dart';
import 'package:yoin/memories/memories_screen.dart';
import 'package:yoin/memories/memories_ui_state.dart';
import 'package:yoin/memories/memories_view_model.dart';
import 'package:yoin/platform/storage/stores.dart';

import '../support/fake_playback_engine.dart';

class _FixedDeck extends MemoriesViewModel {
  _FixedDeck(this.fixed);
  final MemoriesUiState fixed;

  @override
  MemoriesUiState build() => fixed;

  @override
  Future<void> ensureLoaded({int? focus}) async {}
}

MemoryEntry _memory(String id, {String? review}) {
  final track = MemoryTrack(
    trackId: MediaId.subsonic('$id-t1'),
    title: 'Satellites',
    artist: 'The Meridians',
    number: 1,
    playbackIndex: 0,
    rating: 8.5,
  );
  return MemoryEntry(
    sessionId: id.hashCode,
    albumId: MediaId.subsonic(id),
    albumName: 'Glass Orchard $id',
    artistName: 'The Meridians',
    year: 2019,
    scoreKind: MemoryScoreKind.albumRating,
    score: 8.5,
    albumRating: 8.5,
    memoryTitle: 'Glass Orchard $id',
    memoryTitleKind: MemoryTitleKind.album,
    proseLanguage: MemoryProseLanguage.en,
    review: review == null
        ? null
        : MemoryWriting(kind: MemoryWritingKind.review, text: review, writtenAt: DateTime(2026, 11, 14).millisecondsSinceEpoch),
    yoinQuestion: review == null ? 'What stays with you from this one?' : null,
    excerptCandidates: [
      MemoryExcerptCandidate(review, const MemoryExcerptAttribution(kind: MemoryExcerptKind.review, detail: 'Nov 14')),
    ],
    excerptCandidatesMedium: [
      MemoryExcerptCandidate(review, const MemoryExcerptAttribution(kind: MemoryExcerptKind.review, detail: 'Nov 14')),
    ],
    tracks: [track],
    diaryTracks: [MemoryDiaryTrack(track, const [])],
    totalTrackCount: 9,
    ratedTrackCount: 1,
    playsInYoin: 3,
    firstHeardAt: DateTime(2026, 11, 1).millisecondsSinceEpoch,
    lastHeardAt: DateTime(2026, 11, 13).millisecondsSinceEpoch,
  );
}

void main() {
  Future<void> pumpAt(WidgetTester tester, Size size) async {
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final state = MemoriesContent([_memory('a', review: 'Late-night record.'), _memory('b')]);
    late MemoriesRevealController reveal;
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          keyValueStoreProvider.overrideWithValue(InMemoryStore()),
          playbackEngineProvider.overrideWithValue(FakePlaybackEngine()),
          activeSourceProvider.overrideWith((ref) async => null),
          memoriesViewModelProvider.overrideWith(() => _FixedDeck(state)),
        ],
        child: MaterialApp(
          home: Scaffold(
            body: _Host(onReveal: (r) => reveal = r),
          ),
        ),
      ),
    );
    reveal.snapTo(0);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));
  }

  for (final (name, size) in [
    ('phone', const Size(392, 850)),
    ('medium', const Size(800, 1280)),
    ('spread', const Size(1280, 800)),
    ('landscape', const Size(860, 400)),
  ]) {
    testWidgets('should_layOutDeck_when_${name}Window', (tester) async {
      await pumpAt(tester, size);
      expect(tester.takeException(), isNull);
      expect(find.text('Glass Orchard a'), findsWidgets);
      if (name == 'spread' || name == 'landscape') {
        // Two pages at once: the review sits on the right page beside the exhibit.
        expect(find.text('Late-night record.'), findsOneWidget);
      }
    });
  }

  testWidgets('should_pageTheRightColumnWithTheSwipe_when_spread', (tester) async {
    await pumpAt(tester, const Size(1280, 800));
    final review = find.text('Late-night record.');
    final rest = tester.getTopLeft(review).dx;
    final gesture = await tester.startGesture(const Offset(900, 500));
    for (var i = 0; i < 10; i++) {
      await gesture.moveBy(const Offset(-30, 0));
      await tester.pump(const Duration(milliseconds: 16));
    }
    // The whole spread pages with the finger, the right column as much as the left page (Android MemorySpreadPage).
    final mid = tester.getTopLeft(review).dx;
    expect(mid, closeTo(rest - 270, 30));
    // The leaving page's "Memories / Last heard" slot is gone before it reaches the Home pill.
    double shown(Element e) {
      var a = 1.0;
      e.visitAncestorElements((p) {
        final w = p.widget;
        if (w is Opacity) a *= w.opacity;
        return true;
      });
      return a;
    }

    final pillEnd = tester.getTopRight(find.text('Home')).dx;
    for (final e in find.text('Memories').evaluate()) {
      final box = e.renderObject! as RenderBox;
      final left = box.localToGlobal(Offset.zero).dx;
      if (left < pillEnd + 40) expect(shown(e), 0, reason: 'slot at $left');
    }
    await gesture.up();
    await tester.pump(const Duration(seconds: 1));
  });

  test('should_fadeTheSlotsOutBeforeThePill_when_pageLeavesLeftward', () {
    const rest = 116.0; // slot A
    const pill = 104.0; // its pill's end
    expect(memoriesBarSlotLeave(travel: 0, restStart: rest, pillEnd: pill), 1);
    expect(memoriesBarSlotLeave(travel: -200, restStart: rest, pillEnd: pill), 1);
    expect(memoriesBarSlotLeave(travel: (rest - pill + memoriesBarSlotFade) / 2, restStart: rest, pillEnd: pill), closeTo(0.5, 1e-9));
    // Gone once its start has crossed the pill's end and the mask's fade.
    expect(memoriesBarSlotLeave(travel: rest - pill + memoriesBarSlotFade, restStart: rest, pillEnd: pill), 0);
  });

  test('should_clearSlotsUpToThePillsEnd_when_maskingTheBar', () {
    final (a, b) = memoriesBarSlotMaskStops(400, 104);
    expect(a * 400, closeTo(104, 1e-9));
    expect(b * 400, closeTo(104 + memoriesBarSlotFade, 1e-9));
    // At rest slot A (pillStart + 100) starts where the fade has ended.
    final (_, rest) = memoriesBarSlotMaskStops(400, 16 + 88);
    expect(rest * 400, lessThanOrEqualTo(16 + 100));
    expect(memoriesBarSlotMaskStops(0, 10), (0.0, 0.0));
  });

  testWidgets('should_fadeTheTitleAtThePill_when_pagesSlideUnderIt', (tester) async {
    await pumpAt(tester, const Size(392, 850));
    final title = find.text('Memories').first;
    expect(find.ancestor(of: title, matching: find.byType(ShaderMask)), findsOneWidget);
    // The pill sits outside the mask, over it.
    expect(find.ancestor(of: find.text('Home'), matching: find.byType(ShaderMask)), findsNothing);
  });

  testWidgets('should_leaveHomeOutOfTheReader_when_memoriesIsOpen', (tester) async {
    final handle = tester.ensureSemantics();
    late MemoriesRevealController reveal;
    await tester.pumpWidget(
      MaterialApp(
        home: _BehindHost(onReveal: (r) => reveal = r),
      ),
    );
    bool read() {
      var found = false;
      bool visit(SemanticsNode node) {
        if (node.label.contains('Home feed')) found = true;
        node.visitChildren(visit);
        return true;
      }

      visit(tester.binding.renderViews.first.owner!.semanticsOwner!.rootSemanticsNode!);
      return found;
    }

    expect(read(), isTrue);
    reveal.snapTo(0);
    await tester.pump();
    expect(read(), isFalse);
    reveal.snapTo(1);
    await tester.pump();
    expect(read(), isTrue);
    handle.dispose();
  });
}

class _Host extends StatefulWidget {
  const _Host({required this.onReveal});

  final void Function(MemoriesRevealController) onReveal;

  @override
  State<_Host> createState() => _HostState();
}

class _HostState extends State<_Host> with TickerProviderStateMixin {
  late final MemoriesRevealController _reveal = MemoriesRevealController(vsync: this);

  @override
  void initState() {
    super.initState();
    widget.onReveal(_reveal);
  }

  @override
  void dispose() {
    _reveal.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => MemoriesLayer(reveal: _reveal);
}

class _BehindHost extends StatefulWidget {
  const _BehindHost({required this.onReveal});

  final void Function(MemoriesRevealController) onReveal;

  @override
  State<_BehindHost> createState() => _BehindHostState();
}

class _BehindHostState extends State<_BehindHost> with TickerProviderStateMixin {
  late final MemoriesRevealController _reveal = MemoriesRevealController(vsync: this);

  @override
  void initState() {
    super.initState();
    widget.onReveal(_reveal);
  }

  @override
  void dispose() {
    _reveal.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => MemoriesHomeBehind(reveal: _reveal, child: const Text('Home feed'));
}
