import 'dart:convert';
import 'dart:math';

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/highlight/lyric_highlight.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/home/home_section.dart';
import 'package:yoin/home/lyric_highlight_view_model.dart';
import 'package:yoin/platform/storage/stores.dart';

const _day = 24 * 60 * 60 * 1000;
const _now = 1000 * _day;

LyricHighlightRow _row(String id, {int? shownAt, String? model = lyricHighlightUserPick, String? text = 'a line'}) =>
    LyricHighlightRow(
      songId: id,
      title: 'Song $id',
      artist: 'Artist',
      album: 'Album',
      albumId: 'al-$id',
      coverArtId: 'cover-$id',
      durationMs: 200000,
      text: text,
      startMs: 30000,
      model: model,
      attemptedAt: _now - _day,
      lastShownAt: shownAt,
    );

HomeLyricHighlight _card(String id, {String provider = MediaId.providerSubsonic}) =>
    HomeLyricHighlight.fromRow(provider, _row(id), LyricHighlightBucket.user);

void main() {
  group('user pick text', () {
    test('should_keepFirstFourTrimmedLines_when_pickHasMore', () {
      expect(lyricHighlightUserPickText([' one ', '', 'two', '  ', 'three', 'four', 'five']), 'one\ntwo\nthree\nfour');
    });

    test('should_beEmpty_when_everyLineIsBlank', () {
      expect(lyricHighlightUserPickText(['', '  ']), isEmpty);
    });
  });

  group('next to show', () {
    test('should_preferANeverShownLine_when_othersWereShown', () {
      final next = nextLyricHighlightToShow(
        const [LyricHighlightShown('a', _now - 30 * _day), LyricHighlightShown('b', null)],
        bucketOf: (_) => LyricHighlightBucket.user,
        now: _now,
        random: Random(1),
      );
      expect(next?.songId, 'b');
    });

    test('should_holdBackALineShownThisWeek_when_aRestedOneWaits', () {
      for (var seed = 0; seed < 20; seed++) {
        final next = nextLyricHighlightToShow(
          const [LyricHighlightShown('fresh', _now - _day), LyricHighlightShown('rested', _now - 8 * _day)],
          bucketOf: (_) => LyricHighlightBucket.user,
          now: _now,
          random: Random(seed),
        );
        expect(next?.songId, 'rested');
      }
    });

    test('should_showTheLongestAgo_when_everyLineWasShownThisWeek', () {
      final next = nextLyricHighlightToShow(
        const [LyricHighlightShown('a', _now - _day), LyricHighlightShown('b', _now - 3 * _day)],
        bucketOf: (_) => LyricHighlightBucket.user,
        now: _now,
      );
      expect(next?.songId, 'b');
    });

    test('should_passOverThePlayingSong_when_excluded', () {
      final next = nextLyricHighlightToShow(
        const [LyricHighlightShown('playing', null), LyricHighlightShown('other', _now - 2 * _day)],
        bucketOf: (_) => LyricHighlightBucket.user,
        now: _now,
        exclude: 'playing',
      );
      expect(next?.songId, 'other');
    });

    test('should_drawKindsByTheMix_when_severalKindsWait', () {
      final counts = <String, int>{};
      final rng = Random(7);
      for (var i = 0; i < 2000; i++) {
        final next = nextLyricHighlightToShow(
          const [LyricHighlightShown('heavy', null), LyricHighlightShown('user', null)],
          bucketOf: (id) => id == 'heavy' ? LyricHighlightBucket.heavy : LyricHighlightBucket.user,
          now: _now,
          random: rng,
        );
        counts.update(next!.songId, (n) => n + 1, ifAbsent: () => 1);
      }
      // 5 : 3.
      expect(counts['heavy']! / 2000, closeTo(5 / 8, 0.05));
    });
  });

  group('picking cards', () {
    test('should_fillUpToFourDifferentSongs_when_drawnFresh', () {
      final rows = [for (var i = 0; i < 6; i++) _row('s$i')];
      final picked = pickLyricHighlights(rows: rows, provider: MediaId.providerSubsonic, keep: const [], now: _now)!;
      expect(picked.cards, hasLength(lyricHighlightMaxCards));
      expect(picked.cards.map((c) => c.songId.rawId).toSet(), hasLength(4));
      expect(picked.shown, [for (final c in picked.cards) c.songId.rawId]);
      expect(picked.cards.every((c) => c.kind == LyricHighlightBucket.user), isTrue);
    });

    test('should_returnNothing_when_keptCardsHaveNoNewNeighbour', () {
      final rows = [_row('a'), _row('b')];
      final keep = [_card('a'), _card('b')];
      expect(pickLyricHighlights(rows: rows, provider: MediaId.providerSubsonic, keep: keep, now: _now), isNull);
    });

    test('should_addTheNewPickAfterTheKeptOnes_when_aLineArrives', () {
      final rows = [_row('a'), _row('b')];
      final picked = pickLyricHighlights(
        rows: rows,
        provider: MediaId.providerSubsonic,
        keep: [_card('a')],
        now: _now,
      )!;
      expect(picked.cards.map((c) => c.songId.rawId), ['a', 'b']);
      expect(picked.shown, ['b']);
    });

    test('should_dropAnotherProvidersCards_when_accountChanged', () {
      final picked = pickLyricHighlights(
        rows: [_row('a')],
        provider: MediaId.providerSubsonic,
        keep: [_card('x', provider: MediaId.providerSpotify)],
        now: _now,
      )!;
      expect(picked.cards.map((c) => c.songId), [MediaId.subsonic('a')]);
    });

    test('should_leaveThePlayingSongOut_when_othersAreThere', () {
      for (var seed = 0; seed < 10; seed++) {
        final picked = pickLyricHighlights(
          rows: [_row('playing'), _row('b')],
          provider: MediaId.providerSubsonic,
          keep: const [],
          now: _now,
          playingRawId: 'playing',
          random: Random(seed),
        )!;
        // The playing song only comes once it is the last left.
        expect(picked.cards.first.songId.rawId, 'b');
      }
    });
  });

  group('cards and presets', () {
    test('should_mapPresetsToTwoToFourCards_when_read', () {
      expect(lyricCardsFor(HomeRowPreset.s), 2);
      expect(lyricCardsFor(HomeRowPreset.m), 3);
      expect(lyricCardsFor(HomeRowPreset.l), 4);
      expect(lyricCardsFor(HomeRowPreset.xl), 4);
      expect([for (var n = 2; n <= 4; n++) lyricPresetFor(n)], [HomeRowPreset.s, HomeRowPreset.m, HomeRowPreset.l]);
    });

    test('should_startABreathBeforeTheLine_when_synced', () {
      HomeLyricHighlight at(int? start, {int duration = 200000}) => HomeLyricHighlight(
        songId: MediaId.subsonic('a'),
        title: 't',
        artist: '',
        album: '',
        albumId: null,
        coverKey: null,
        durationMs: duration,
        text: 'x',
        startMs: start,
        kind: LyricHighlightBucket.user,
      );
      expect(at(30000).playFromMs, 30000 - lyricHighlightSeekLeadMs);
      expect(at(null).playFromMs, 0);
      expect(at(400).playFromMs, 0, reason: 'a line in the first breath plays from the top');
      expect(at(250000).playFromMs, 0, reason: 'past the end plays from the top');
      expect(at(30000).asTrack().durationSec, 200);
    });
  });

  group('store', () {
    test('should_keepRowsPerAccountAndProvider_when_upserted', () async {
      final kv = InMemoryStore();
      final store = LyricHighlightStore(kv);
      await store.upsert('p1', MediaId.providerSubsonic, _row('a'));
      await store.upsert('p2', MediaId.providerSubsonic, _row('b'));
      await store.upsert('p1', MediaId.providerSpotify, _row('c'));
      expect((await store.usable('p1', MediaId.providerSubsonic)).map((r) => r.songId), ['a']);
      expect((await store.usable('p2', MediaId.providerSubsonic)).map((r) => r.songId), ['b']);
      expect((await store.usable('p1', MediaId.providerSpotify)).map((r) => r.songId), ['c']);
      expect(jsonDecode(kv.values[LyricHighlightStore.keyFor(MediaId.providerSubsonic, 'p1')]!), contains('a'));
    });

    test('should_leaveTriedRowsOut_when_listingUsable', () async {
      final store = LyricHighlightStore(InMemoryStore());
      await store.upsert('p', MediaId.providerSubsonic, _row('tried', text: null, model: null));
      await store.upsert('p', MediaId.providerSubsonic, _row('kept'));
      expect((await store.usable('p', MediaId.providerSubsonic)).map((r) => r.songId), ['kept']);
    });

    test('should_stampEachShownRow_when_markedShown', () async {
      final store = LyricHighlightStore(InMemoryStore());
      await store.upsert('p', MediaId.providerSubsonic, _row('a'));
      await store.upsert('p', MediaId.providerSubsonic, _row('b'));
      await store.markShown('p', MediaId.providerSubsonic, ['a', 'gone'], _now);
      expect((await store.get('p', MediaId.providerSubsonic, 'a'))!.lastShownAt, _now);
      expect((await store.get('p', MediaId.providerSubsonic, 'b'))!.lastShownAt, isNull);
    });

    test('should_tellTheDisplay_when_aPickIsKept', () async {
      final store = LyricHighlightStore(InMemoryStore());
      var told = 0;
      final sub = store.changes.listen((_) => told++);
      await store.upsert('p', MediaId.providerSubsonic, _row('a'));
      await store.markShown('p', MediaId.providerSubsonic, ['a'], _now);
      await Future<void>.delayed(Duration.zero);
      expect(told, 1);
      await sub.cancel();
    });

    test('should_readNothing_when_storedJsonIsBroken', () async {
      final kv = InMemoryStore()..values[LyricHighlightStore.keyFor(MediaId.providerSubsonic, 'p')] = '{oops';
      expect(await LyricHighlightStore(kv).usable('p', MediaId.providerSubsonic), isEmpty);
    });

    test('should_rememberTheIntro_when_answered', () async {
      final kv = InMemoryStore();
      final store = LyricHighlightStore(kv);
      expect(await store.introSeen(), isFalse);
      await store.markIntroSeen();
      expect(await LyricHighlightStore(kv).introSeen(), isTrue);
    });
  });
}
