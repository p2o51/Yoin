import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/app/providers.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/memories/copy/memory_voice.dart';
import 'package:yoin/memories/memories_morph.dart';
import 'package:yoin/memories/memories_motion.dart';
import 'package:yoin/memories/memories_reveal_scope.dart';
import 'package:yoin/memories/memories_screen.dart';
import 'package:yoin/memories/memories_ui_state.dart';
import 'package:yoin/memories/memories_view_model.dart';
import 'package:yoin/memories/memory_card_face.dart';
import 'package:yoin/memories/memory_diary.dart';
import 'package:yoin/memories/memory_emblem_slot.dart';
import 'package:yoin/platform/storage/stores.dart';
import 'package:yoin/theme/tokens.dart';
import 'package:yoin/ui/component/cover_art.dart';

import '../support/fake_playback_engine.dart';

class _FixedDeck extends MemoriesViewModel {
  _FixedDeck(this.fixed);
  final MemoriesUiState fixed;

  @override
  MemoriesUiState build() => fixed;

  @override
  Future<void> ensureLoaded({int? focus}) async {}
}

/// A memory with [tracks] rated songs (a long diary scrolls) and, given
/// [title], a written title that heads the diary.
MemoryEntry _memory(String id, {String? title, String? album, int tracks = 1}) {
  final rows = [
    for (var i = 0; i < tracks; i++)
      MemoryTrack(
        trackId: MediaId.subsonic('$id-t$i'),
        title: 'Satellites $i',
        artist: 'The Meridians',
        number: i + 1,
        playbackIndex: i,
        rating: 8.5,
      ),
  ];
  final name = album ?? 'Glass Orchard $id';
  return MemoryEntry(
    sessionId: id.hashCode,
    albumId: MediaId.subsonic(id),
    albumName: name,
    artistName: 'The Meridians',
    year: 2019,
    scoreKind: MemoryScoreKind.albumRating,
    score: 8.5,
    albumRating: 8.5,
    memoryTitle: title ?? name,
    memoryTitleKind: title == null ? MemoryTitleKind.album : MemoryTitleKind.user,
    proseLanguage: MemoryProseLanguage.en,
    yoinQuestion: 'What stays with you from this one?',
    excerptCandidates: const [
      MemoryExcerptCandidate(null, MemoryExcerptAttribution(kind: MemoryExcerptKind.review, detail: 'Nov 14')),
    ],
    excerptCandidatesMedium: const [
      MemoryExcerptCandidate(null, MemoryExcerptAttribution(kind: MemoryExcerptKind.review, detail: 'Nov 14')),
    ],
    tracks: rows,
    diaryTracks: [for (final t in rows) MemoryDiaryTrack(t, const [])],
    totalTrackCount: tracks,
    ratedTrackCount: tracks,
    playsInYoin: 3,
    firstHeardAt: DateTime(2026, 11, 1).millisecondsSinceEpoch,
    lastHeardAt: DateTime(2026, 11, 13).millisecondsSinceEpoch,
  );
}

class _Host extends StatefulWidget {
  const _Host();

  @override
  State<_Host> createState() => _HostState();
}

class _HostState extends State<_Host> with TickerProviderStateMixin {
  late final MemoriesRevealController _reveal = MemoriesRevealController(vsync: this)..snapTo(0);

  @override
  void dispose() {
    _reveal.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => MemoriesLayer(reveal: _reveal);
}

const _phone = Size(392, 850);

void main() {
  late ProviderContainer container;

  Future<void> pump(WidgetTester tester, List<MemoryEntry> memories, {Size size = _phone}) async {
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          keyValueStoreProvider.overrideWithValue(InMemoryStore()),
          playbackEngineProvider.overrideWithValue(FakePlaybackEngine()),
          activeSourceProvider.overrideWith((ref) async => null),
          memoriesViewModelProvider.overrideWith(() => _FixedDeck(MemoriesContent(memories))),
        ],
        child: const MaterialApp(home: Scaffold(body: _Host())),
      ),
    );
    container = ProviderScope.containerOf(tester.element(find.byType(_Host)));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 600));
  }

  dynamic surface(WidgetTester tester) => tester.state(find.byType(MemoriesSurface));
  MemoriesDiaryController diary(WidgetTester tester) => surface(tester).debugDiary as MemoriesDiaryController;
  MemoriesDiaryDeck deck(WidgetTester tester) => surface(tester).debugMorph as MemoriesDiaryDeck;

  /// Sets p as a finger would (no spring) and lets the frame land.
  Future<void> scrub(WidgetTester tester, double p) async {
    diary(tester).snapTo(p);
    await tester.pump();
  }

  Finder cardCover() => find.byWidgetPredicate((w) => w is CoverArt && w.size > MemoriesTokens.barCover).first;
  Finder barCover() => find.byWidgetPredicate((w) => w is CoverArt && w.size == MemoriesTokens.barCover).first;
  MorphPose painted(WidgetTester tester, Finder of) {
    final layer = tester.renderObject<RenderMorphLayer>(find.ancestor(of: of, matching: find.byType(MorphLayer)).first);
    return layer.debugPainted!;
  }

  Finder pill() => find.ancestor(of: find.text('Home'), matching: find.byType(Material)).first;

  // The bar's 40 dp slot on a phone (pill 16 + 48), the status bar 0 in tests.
  const barSlot = Rect.fromLTWH(64, 12, 40, 40);

  testWidgets('should_flyTheCoverIntoTheBarSlot_when_pScrubsToTheDiary', (tester) async {
    await pump(tester, [_memory('a', title: 'The rain year'), _memory('b')]);
    final rest = tester.getRect(cardCover());
    expect(rest.width, 256);

    await scrub(tester, 0.5);
    // Size leads position: half way along, three quarters of the way down to 40.
    final mid = tester.getRect(cardCover());
    final scale = 1 + (40 / 256 - 1) * 0.75;
    expect(mid.width, closeTo(256 * scale, 0.5));
    expect(mid.center.dx, closeTo(lerpDouble(rest.center.dx, barSlot.center.dx, 0.5), 0.5));
    expect(mid.center.dy, closeTo(lerpDouble(rest.center.dy, barSlot.center.dy, 0.5), 0.5));
    // The bar's own cover never shows this early.
    expect(painted(tester, barCover()).alpha, 0);
    expect(painted(tester, cardCover()).alpha, 1);

    await scrub(tester, 1);
    await tester.pump(const Duration(milliseconds: 600));
    // Landed: the card's cover sits exactly on the bar slot, gone, and the bar's cover has taken over.
    final landed = tester.getRect(cardCover());
    expect(landed.center.dx, closeTo(barSlot.center.dx, 0.5));
    expect(landed.center.dy, closeTo(barSlot.center.dy, 0.5));
    expect(landed.width, closeTo(40, 0.5));
    expect(painted(tester, cardCover()).alpha, 0);
    expect(tester.getRect(barCover()), barSlot);
    expect(painted(tester, barCover()).alpha, 1);
    expect(painted(tester, barCover()).clipRadius, MemoriesTokens.coverCornerBar);
  });

  testWidgets('should_landTheCardTitleAndEmblemOnTheDiaryHead_when_diaryOpens', (tester) async {
    await pump(tester, [_memory('a', title: 'The rain year'), _memory('b')]);
    Finder cardTitle() => find.descendant(of: find.byType(MemoryCardFace), matching: find.text('The rain year')).first;
    Finder diaryTitle() => find.descendant(of: find.byType(MemoryDiary), matching: find.text('The rain year')).first;
    final rest = tester.getRect(cardTitle());

    // Held until p .2: the title waits on the card, its twin on top of it at the card's size.
    await scrub(tester, 0.15);
    expect(tester.getRect(cardTitle()), rest);
    expect(tester.getRect(diaryTitle()).topCenter.dx, closeTo(rest.topCenter.dx, 0.5));
    expect(tester.getRect(diaryTitle()).top, closeTo(rest.top, 0.5));
    expect(painted(tester, diaryTitle()).alpha, 0);

    await scrub(tester, 1);
    // Landed: the card's copy lies over the diary's, faded out; the 96 has become the native 48.
    final head = tester.getRect(diaryTitle());
    final flown = tester.getRect(cardTitle());
    expect(flown.center.dx, closeTo(head.center.dx, 0.5));
    expect(flown.top, closeTo(head.top, 0.5));
    // Scaled to the diary's size (glyph widths are not quite linear in the font size).
    expect(flown.width, closeTo(head.width, 3));
    expect(painted(tester, cardTitle()).alpha, 0);
    expect(painted(tester, diaryTitle()), const MorphPose(origin: Alignment.topCenter));

    Finder emblem({required bool diary}) =>
        find.byWidgetPredicate((w) => w is MemoryEmblemSlot && w.diary == diary).first;
    final seal = tester.getRect(emblem(diary: false));
    final dem = tester.getRect(emblem(diary: true));
    expect(dem.size, const Size.square(MemoriesTokens.diaryEmblem));
    expect(seal.center.dx, closeTo(dem.center.dx, 0.5));
    expect(seal.center.dy, closeTo(dem.center.dy, 0.5));
    expect(seal.width, closeTo(dem.width, 0.5));
    expect(painted(tester, emblem(diary: false)).alpha, 0);
    expect(painted(tester, emblem(diary: true)).alpha, 1);
  });

  testWidgets('should_flyTheAlbumTitleToTheBarsAlbumName_when_theMemoryHasNoWrittenTitle', (tester) async {
    await pump(tester, [_memory('a', album: 'Orchard'), _memory('b')]);
    Finder cardTitle() => find.descendant(of: find.byType(MemoryCardFace), matching: find.text('Orchard')).first;
    await scrub(tester, 1);
    final bar = find.byWidgetPredicate((w) => w is Text && w.data == 'Orchard' && w.style?.fontSize == 15);
    // The card's title box is its column (the words centred in it): its centre lands on the bar's words.
    final flown = tester.getRect(cardTitle());
    final target = tester.getRect(bar);
    expect(flown.center.dx, closeTo(target.center.dx, 1));
    expect(flown.top, closeTo(target.top, 1));
    expect(painted(tester, cardTitle()).alpha, 0);
  });

  testWidgets('should_holdTheCoverInTheBarAndSinkTheText_when_closingAScrolledDiary', (tester) async {
    await pump(tester, [_memory('a', title: 'The rain year', tracks: 30), _memory('b')]);
    final rest = tester.getRect(cardCover());
    final cardTitle = find.descendant(of: find.byType(MemoryCardFace), matching: find.text('The rain year')).first;
    final titleRest = tester.getRect(cardTitle);
    await scrub(tester, 1);
    final page = deck(tester).current()!;
    page.scroll!.jumpTo(800);
    await tester.pump();
    // The title is under the bar: a collapse from here is frozen.
    expect(deck(tester).frozen, isTrue);

    await scrub(tester, 0.4);
    // The cover still waits near the bar (fp = p / .55), the card's title fades in where it lives.
    final waiting = tester.getRect(cardCover());
    final fp = 0.4 / MemoriesTokens.frozenCoverSpan;
    expect(waiting.center.dx, closeTo(lerpDouble(rest.center.dx, barSlot.center.dx, fp), 0.5));
    expect(waiting.center.dy, closeTo(lerpDouble(rest.center.dy, barSlot.center.dy, fp), 0.5));
    expect(tester.getRect(cardTitle), titleRest);
    expect(painted(tester, cardTitle).alpha, greaterThan(0));
    expect(page.scrollOffset, 800);
    // The Home pill follows the cover: it stays narrow while the cover still waits in the bar.
    expect(tester.getSize(pill()).width, MemoriesTokens.pillWidthDiary);

    // Below p .05 the text is gone: the scroll returns to its top, unseen, and the timing holds.
    await scrub(tester, 0.03);
    expect(page.scrollOffset, 0);
    expect(deck(tester).frozen, isTrue);
    await scrub(tester, 0);
    expect(deck(tester).frozen, isFalse);
    expect(tester.getRect(cardCover()), rest);
  });

  testWidgets('should_fadeInPlace_when_reducedMotion', (tester) async {
    tester.platformDispatcher.accessibilityFeaturesTestValue = const FakeAccessibilityFeatures(disableAnimations: true);
    addTearDown(tester.platformDispatcher.clearAccessibilityFeaturesTestValue);
    await pump(tester, [_memory('a', title: 'The rain year'), _memory('b')]);
    final rest = tester.getRect(cardCover());
    await scrub(tester, 0.3);
    // Nothing moves: the card fades out by p .5, the diary in from there.
    expect(tester.getRect(cardCover()), rest);
    final face = tester.renderObject<RenderMorphLayer>(
      find.descendant(of: find.byType(MemoryCardFace), matching: find.byType(MorphLayer)).first,
    );
    expect(face.debugPainted!.alpha, closeTo(reducedCardAlpha(0.3), 1e-9));
    expect(painted(tester, barCover()).alpha, 0);
    await scrub(tester, 1);
    expect(face.debugPainted!.alpha, 0);
    expect(tester.getRect(barCover()), barSlot);
    expect(painted(tester, barCover()).alpha, 1);
  });

  testWidgets('should_runTheSameFlightBack_when_theDiaryIsOpenedByButtonAndClosedByBack', (tester) async {
    await pump(tester, [_memory('a', title: 'The rain year'), _memory('b')]);
    final rest = tester.getRect(cardCover());
    await tester.tap(find.text('Diary').first);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 80));
    // In flight on p's spring: between the card and the bar.
    final flying = tester.getRect(cardCover());
    expect(flying.width, lessThan(256));
    expect(flying.width, greaterThan(40));
    await tester.pump(const Duration(seconds: 1));
    expect(diary(tester).fraction, closeTo(1, 1e-3));
    expect(painted(tester, barCover()).alpha, 1);

    // System back closes the diary through the same controller: the cover flies back to the card.
    container.read(memoriesRevealHandleProvider).back();
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 80));
    expect(diary(tester).fraction, inExclusiveRange(0, 1));
    await tester.pump(const Duration(seconds: 1));
    expect(diary(tester).fraction, closeTo(0, 1e-3));
    expect(tester.getRect(cardCover()), rest);
    expect(painted(tester, cardCover()).alpha, 1);
  });

  testWidgets('should_riseTheTrackRowsAsOneBlock_when_theDiaryOpens', (tester) async {
    await pump(tester, [_memory('a', title: 'The rain year', tracks: 3), _memory('b')]);
    await scrub(tester, 0.6);
    RenderMorphLayer block(String text) => tester.renderObject<RenderMorphLayer>(
      find.ancestor(of: find.text(text), matching: find.byType(MorphLayer)).first,
    );
    // Android's liner is one block: every track row rides the same layer.
    expect(block('Satellites 0'), same(block('Satellites 2')));
    expect(block('Satellites 0').debugPainted!.dy, greaterThan(0));
  });

  testWidgets('should_landTheDiaryEmblemUncutAtFullSize_when_theDiaryButtonOpensACardStillDueItsAward', (tester) async {
    final memories = [_memory('a', title: 'The rain year'), _memory('b')];
    await pump(tester, memories);
    final card = tester.widget<MemoryEmblemSlot>(
      find.byWidgetPredicate((w) => w is MemoryEmblemSlot && !w.diary).first,
    );
    expect(card.awards.cardController(memories.first).isAwarded, isFalse);
    await tester.tap(find.text('Diary').first);
    await tester.pump();
    await tester.pump(const Duration(seconds: 1));
    expect(diary(tester).fraction, closeTo(1, 1e-3));
    final slot = tester.widget<MemoryEmblemSlot>(find.byWidgetPredicate((w) => w is MemoryEmblemSlot && w.diary).first);
    final dem = slot.awards.diaryController(memories.first);
    // Full size and upright, not the small waiting pose a drag-opened diary nods out of; nothing cut yet.
    expect(dem.emblemScale, 1);
    expect(dem.cutProgress(0), 0);
  });

  testWidgets('should_landTheCoverAndTitleOnTheBarAndHead_when_theWindowIsMediumWidth', (tester) async {
    // A tablet in portrait (800 dp, Medium): the card is a centred column, the bar keeps its own insets.
    await pump(tester, [_memory('a', title: 'The rain year'), _memory('b')], size: const Size(800, 1280));
    expect(tester.getRect(cardCover()).width, greaterThan(MemoriesTokens.barCover));
    await scrub(tester, 1);
    await tester.pump(const Duration(milliseconds: 600));
    final bar = tester.getRect(barCover());
    final landed = tester.getRect(cardCover());
    expect(landed.center.dx, closeTo(bar.center.dx, 0.5));
    expect(landed.center.dy, closeTo(bar.center.dy, 0.5));
    expect(landed.width, closeTo(bar.width, 0.5));
    final cardTitle = find.descendant(of: find.byType(MemoryCardFace), matching: find.text('The rain year')).first;
    final diaryTitle = find.descendant(of: find.byType(MemoryDiary), matching: find.text('The rain year')).first;
    expect(tester.getRect(cardTitle).center.dx, closeTo(tester.getRect(diaryTitle).center.dx, 0.5));
    expect(tester.getRect(cardTitle).top, closeTo(tester.getRect(diaryTitle).top, 0.5));
  });
}
