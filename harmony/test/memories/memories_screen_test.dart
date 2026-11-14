import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/memories/copy/memory_voice.dart';
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
