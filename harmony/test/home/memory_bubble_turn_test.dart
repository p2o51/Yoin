import 'dart:math' as math;

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/memory/album_memory_candidate.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/home/home_bubble_tips.dart';
import 'package:yoin/home/memory_bubble_director.dart';
import 'package:yoin/home/memory_bubble_turn.dart';
import 'package:yoin/memories/home_memory_pill.dart';

AlbumMemoryCandidate _c(String id, {int? written, int? played}) => AlbumMemoryCandidate(
  profileId: 'p',
  albumId: MediaId.subsonic(id),
  albumName: id,
  totalTracks: 8,
  ratedTrackCount: 0,
  ratingCoverage: 0,
  hasAlbumReview: true,
  noteCount: 0,
  isMemoryEligible: true,
  lastWrittenAt: written,
  lastPlayedAt: played,
);

HomeMemoryPillState _pill({
  AlbumMemoryCandidate? latest,
  int notes = 3,
  String key = 'k1',
  List<AlbumMemoryCandidate> recall = const [],
}) => HomeMemoryPillState(
  latest: latest,
  noteCount: notes,
  resolved: true,
  scope: 'subsonic|p1',
  newsKey: key,
  recall: recall,
);

/// A random whose every draw is [value]: never chains when 0.99, always when 0.
class _FixedRandom implements math.Random {
  _FixedRandom(this.value);
  final double value;
  @override
  double nextDouble() => value;
  @override
  int nextInt(int max) => 0;
  @override
  bool nextBool() => false;
}

void main() {
  const idleReminder = MemoryBubbleTurn(reason: BubbleReason.idle, spokeThisForeground: true);

  group('MemoryBubbleTurn.after', () {
    test('should_goQuietButRememberItSpoke_when_theDwellIsOver', () {
      expect(idleReminder.after(MemoryBubbleCue.dwellOver), const MemoryBubbleTurn(spokeThisForeground: true));
    });

    test('should_beTuckedAway_when_backFromTheBackground', () {
      final back = const MemoryBubbleTurn().after(MemoryBubbleCue.news).after(MemoryBubbleCue.backgrounded);
      expect(back.reason, isNull);
      expect(back.spokeThisForeground, isFalse);
      expect(back.after(MemoryBubbleCue.idleElapsed).reason, BubbleReason.idle);
    });

    test('should_speakAgain_when_leftAloneAfterItWent', () {
      expect(const MemoryBubbleTurn().after(MemoryBubbleCue.idleElapsed), idleReminder);
      final gone = idleReminder.after(MemoryBubbleCue.dwellOver);
      expect(gone.after(MemoryBubbleCue.idleElapsed).reason, BubbleReason.idle);
      final back = gone.after(MemoryBubbleCue.touch).after(MemoryBubbleCue.outOfReach);
      expect(back.after(MemoryBubbleCue.idleElapsed).reason, BubbleReason.idle);
    });

    test('should_speakAfterTheNews_when_leftAloneOnceTheNewsWent', () {
      final told = const MemoryBubbleTurn().after(MemoryBubbleCue.news).after(MemoryBubbleCue.dwellOver);
      expect(told.after(MemoryBubbleCue.idleElapsed).reason, BubbleReason.idle);
    });

    test('should_notTalkOverItself_when_theIdleWindowElapsesWhileSpeaking', () {
      final news = const MemoryBubbleTurn().after(MemoryBubbleCue.news);
      expect(news.after(MemoryBubbleCue.idleElapsed).reason, BubbleReason.news);
      expect(idleReminder.after(MemoryBubbleCue.idleElapsed), idleReminder);
    });

    test('should_stillTellNews_when_chattering', () {
      expect(idleReminder.after(MemoryBubbleCue.news).reason, BubbleReason.news);
    });

    test('should_tuck_when_touchedOrOutOfReach', () {
      expect(idleReminder.after(MemoryBubbleCue.touch).reason, isNull);
      expect(idleReminder.after(MemoryBubbleCue.outOfReach).reason, isNull);
    });
  });

  group('the dwell', () {
    test('should_startTheDwell_when_fullyOutAndAtRest', () {
      bool said(double u, bool moving, {double fly = 0, bool flying = false}) =>
          memoryBubbleFullySaid(unroll: u, unrolling: moving, flyAway: fly, flyingAway: flying);
      expect(said(0.6, true), isFalse);
      expect(said(1.04, true), isFalse); // the Expressive spring overshoots on its way in
      expect(said(0.98, false), isFalse);
      expect(said(1, false), isTrue);
      expect(said(1, false, fly: 0.4, flying: true), isFalse);
      expect(said(1, false, fly: 0.4), isFalse);
      expect(said(1, false, flying: true), isFalse);
    });

    test('should_pickTheIdleRhythmWithinTheOwnersRanges_when_asked', () {
      final random = math.Random(7);
      for (var i = 0; i < 200; i++) {
        final idle = pickMemoryBubbleMs(memoryBubbleIdleMinMs, memoryBubbleIdleMaxMs, random);
        expect(idle, inInclusiveRange(4000, 5000));
        final dwell = pickMemoryBubbleMs(memoryBubbleChatterDwellMinMs, memoryBubbleChatterDwellMaxMs, random);
        expect(dwell, inInclusiveRange(2000, 3000));
      }
    });
  });

  group('news', () {
    test('should_speakNews_when_keyDiffersFromLastSeen', () {
      expect(isMemoryNews(_pill(latest: _c('a')), null), isTrue);
      expect(isMemoryNews(_pill(latest: _c('a'), key: 'k2'), 'k1'), isTrue);
      expect(isMemoryNews(_pill(latest: _c('a'), key: 'k1'), 'k1'), isFalse);
    });

    test('should_stayQuiet_when_nothingToSay', () {
      expect(isMemoryNews(null, null), isFalse);
      expect(isMemoryNews(_pill(notes: 0), null), isFalse);
      expect(_pill(notes: 0).hasSomethingToSay, isFalse);
      expect(_pill(notes: 2).hasSomethingToSay, isTrue);
      expect(isMemoryNews(_pill(latest: _c('a'), key: ''), null), isFalse);
    });

    test('should_changeNewsKey_when_writeOrNoteCountMoves', () {
      final base = _c('a', written: 100);
      expect(memoryNewsKey(base, 3), '100#3');
      expect(memoryNewsKey(_c('a', written: 100, played: 999), 3), '100#3'); // plays never count
      expect(memoryNewsKey(null, 0), '0#0');
      expect(parseMemoryNewsKey('100#3'), (100, 3));
      expect(parseMemoryNewsKey('garbage'), isNull);
    });

    test('should_speak_when_newerWriteOrMoreNotes', () {
      expect(isMemoryNews(_pill(latest: _c('a'), key: '200#3'), '100#3'), isTrue);
      expect(isMemoryNews(_pill(latest: _c('a'), key: '100#4'), '100#3'), isTrue);
    });

    test('should_stayQuiet_when_aDeletionMovesTheKeyBack', () {
      expect(isMemoryNews(_pill(latest: _c('a'), key: '100#2'), '100#3'), isFalse);
      expect(isMemoryNews(_pill(latest: _c('a'), key: '50#3'), '100#3'), isFalse);
    });
  });

  group('what it says', () {
    test('should_sayTipsOnly_when_thereAreNoMemories', () {
      final say = idleBubbleSay(_pill(notes: 0), sayMemory: true, memoryTurn: 0, tipTurn: 1);
      expect(say, BubbleSayTip(HomeBubbleTip.values[1]));
    });

    test('should_recallAMemory_when_itIsAMemoryTurn', () {
      final pill = _pill(latest: _c('latest'), recall: [_c('a'), _c('b')]);
      expect(
        (idleBubbleSay(pill, sayMemory: true, memoryTurn: 1, tipTurn: 0) as BubbleSayMemory).latest!.albumName,
        'b',
      );
      expect(
        (idleBubbleSay(pill, sayMemory: true, memoryTurn: 2, tipTurn: 0) as BubbleSayMemory).latest!.albumName,
        'a',
      );
      // No others yet: the latest itself.
      final alone = _pill(latest: _c('latest'));
      expect(
        (idleBubbleSay(alone, sayMemory: true, memoryTurn: 5, tipTurn: 0) as BubbleSayMemory).latest!.albumName,
        'latest',
      );
    });

    test('should_goThroughEveryTipBeforeRepeating_when_tipTurnsAdvance', () {
      final said = [
        for (var turn = 0; turn < HomeBubbleTip.values.length * 2; turn++)
          (idleBubbleSay(null, sayMemory: false, memoryTurn: 0, tipTurn: turn) as BubbleSayTip).tip,
      ];
      expect(said.take(HomeBubbleTip.values.length).toSet(), HomeBubbleTip.values.toSet());
      expect(said.skip(HomeBubbleTip.values.length), HomeBubbleTip.values);
    });

    test('should_sayNothing_when_noTipsAndNoMemories', () {
      expect(idleBubbleSay(_pill(notes: 0), sayMemory: false, memoryTurn: 0, tipTurn: 0, tips: const []), isNull);
      final memory = idleBubbleSay(_pill(latest: _c('a')), sayMemory: false, memoryTurn: 0, tipTurn: 0, tips: const []);
      expect(memory, isA<BubbleSayMemory>());
    });

    test('should_recallWrittenFirstNewestFirstOnePerAlbum_when_picking', () {
      final latest = _c('latest', written: 900);
      final recall = pickMemoryRecall([
        latest,
        _c('played', played: 999),
        _c('old', written: 100),
        _c('new', written: 500),
        _c('new', written: 50),
      ], latest);
      expect(recall.map((c) => c.albumName), ['new', 'old', 'played']);
      final many = [for (var i = 0; i < 12; i++) _c('a$i', written: i)];
      expect(pickMemoryRecall(many, null), hasLength(memoryRecallLimit));
    });
  });

  group('where the arrow goes', () {
    test('should_hangAtThePageCentre_when_theBubbleHasRoom', () {
      final (x, _) = memoryArrowCenterX(pageCentre: 180, titleLeft: 0, freeEnd: 300, minLeft: 150, reach: 80);
      expect(x, 180);
    });

    test('should_keepTheBubblesLeftRoom_when_theCentreIsTooFarLeft', () {
      final (x, under) = memoryArrowCenterX(pageCentre: 100, titleLeft: 0, freeEnd: 300, minLeft: 150, reach: 80);
      expect(x, 150);
      expect(under, isFalse);
    });

    test('should_hangUnderTheCutout_when_itFits', () {
      final (x, under) = memoryArrowCenterX(
        cutoutCenterX: 170,
        pageCentre: 180,
        titleLeft: 0,
        freeEnd: 300,
        minLeft: 150,
        reach: 80,
      );
      expect((x, under), (170.0, true));
    });
  });

  group('MemoryBubbleDirector', () {
    MemoryBubbleDirector director({double random = 0.99, MemoryBubbleSeenStore? store}) => MemoryBubbleDirector(
      store: store ?? MemoryBubbleSeenStore.inMemory(),
      timings: const MemoryBubbleTimings(idleMs: 4500, chatterDwellMs: 2500),
      random: _FixedRandom(random),
    );

    testWidgets('should_speakAfterIdleAndGoAfterTheDwell_when_leftAlone', (tester) async {
      final d = director();
      await tester.pump();
      d.setPill(_pill(notes: 0, key: ''));
      d.setReadable(true);
      await tester.pump(const Duration(milliseconds: 4499));
      expect(d.speaking, isFalse);
      await tester.pump(const Duration(milliseconds: 1));
      expect(d.reason, BubbleReason.idle);
      expect(d.say, isA<BubbleSayTip>());
      // Still unrolling: the clock hasn't started, however long that takes.
      await tester.pump(const Duration(seconds: 20));
      expect(d.speaking, isTrue);
      d.setFullySaid(true);
      await tester.pump(const Duration(milliseconds: 2499));
      expect(d.speaking, isTrue);
      await tester.pump(const Duration(milliseconds: 1));
      expect(d.speaking, isFalse);
      d.dispose();
    });

    testWidgets('should_startTheIdleClockOver_when_touched', (tester) async {
      final d = director();
      await tester.pump();
      d.setPill(_pill(notes: 0, key: ''));
      d.setReadable(true);
      await tester.pump(const Duration(milliseconds: 3500));
      d.touch();
      await tester.pump(const Duration(milliseconds: 4499));
      expect(d.speaking, isFalse);
      await tester.pump(const Duration(milliseconds: 1));
      expect(d.speaking, isTrue);
      d.touch();
      expect(d.speaking, isFalse);
      d.dispose();
    });

    testWidgets('should_notRunTheClock_when_homeIsOutOfReach', (tester) async {
      final d = director();
      await tester.pump();
      d.setPill(_pill(notes: 0, key: ''));
      d.setReadable(true);
      await tester.pump(const Duration(milliseconds: 3500));
      d.setReadable(false);
      await tester.pump(const Duration(seconds: 60));
      expect(d.speaking, isFalse);
      d.setReadable(true);
      await tester.pump(const Duration(milliseconds: 4500));
      expect(d.speaking, isTrue);
      d.setReadable(false);
      expect(d.speaking, isFalse);
      d.dispose();
    });

    testWidgets('should_waitForTheRelease_when_theDwellEndsUnderAFinger', (tester) async {
      final d = director();
      await tester.pump();
      d.setPill(_pill(notes: 0, key: ''));
      d.setReadable(true);
      await tester.pump(const Duration(milliseconds: 4500));
      d.setFullySaid(true);
      await tester.pump(const Duration(milliseconds: 2400));
      d.setHeld(true);
      await tester.pump(const Duration(seconds: 10));
      expect(d.speaking, isTrue);
      d.setHeld(false);
      expect(d.speaking, isFalse);
      d.dispose();
    });

    testWidgets('should_tellNewsOnceAndHoldSixSeconds_when_somethingNewWasWritten', (tester) async {
      final store = MemoryBubbleSeenStore.inMemory();
      final d = director(store: store);
      await tester.pump();
      final latest = _c('fresh', written: 200);
      d.setPill(_pill(latest: latest, key: '200#3'));
      d.setReadable(true);
      await tester.pump(const Duration(milliseconds: 450));
      expect(d.reason, BubbleReason.news);
      expect((d.say! as BubbleSayMemory).latest!.albumName, 'fresh');
      expect(store.lastSeen('subsonic|p1'), '200#3');
      d.setFullySaid(true);
      await tester.pump(const Duration(milliseconds: 5999));
      expect(d.speaking, isTrue);
      await tester.pump(const Duration(milliseconds: 1));
      expect(d.speaking, isFalse);
      d.dispose();
    });

    testWidgets('should_turnIntoItsNextLine_when_theChainChanceHits', (tester) async {
      final d = director(random: 0);
      await tester.pump();
      d.setPill(_pill(latest: _c('a'), key: ''));
      d.setReadable(true);
      await tester.pump(const Duration(milliseconds: 4500));
      expect(d.say, isA<BubbleSayMemory>());
      d.setFullySaid(true);
      await tester.pump(const Duration(milliseconds: 2500));
      // Turned in place into the next line (a tip), and it holds that one too.
      expect(d.speaking, isTrue);
      expect(d.say, isA<BubbleSayTip>());
      await tester.pump(const Duration(milliseconds: 2500));
      expect(d.speaking, isFalse); // once per turn
      d.dispose();
    });

    testWidgets('should_alternateMemoriesAndTips_when_chattering', (tester) async {
      final d = director();
      await tester.pump();
      d.setPill(_pill(latest: _c('a'), key: ''));
      d.setReadable(true);
      final said = <BubbleSay?>[];
      for (var i = 0; i < 3; i++) {
        await tester.pump(const Duration(milliseconds: 4500));
        said.add(d.say);
        d.touch();
      }
      expect(said[0], isA<BubbleSayMemory>());
      expect(said[1], isA<BubbleSayTip>());
      expect(said[2], isA<BubbleSayMemory>());
      d.dispose();
    });
  });
}
