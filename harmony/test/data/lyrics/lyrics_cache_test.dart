import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/lyrics/lyrics_cache.dart';
import 'package:yoin/platform/storage/stores.dart';

/// Android LyricsCachePolicyTest + LyricsCacheBudgetTest.
void main() {
  LyricsFootprint fp(
    String key, {
    required int bytes,
    required int usedAt,
    bool chosen = false,
    bool typed = false,
    bool orphan = false,
  }) => LyricsFootprint(
    songKey: key,
    userChosen: chosen || typed,
    lastUsedAt: usedAt,
    bytes: bytes,
    lyricsCachedAt: orphan ? null : usedAt,
    typed: typed,
  );

  group('LyricsCachePolicy', () {
    test('should_markSongId_when_entryIsAUserChosenPick', () {
      final e = LyricsCachePolicy.entry(
        songKey: 's',
        lyricsProvider: 'qq',
        lyricsProviderSongId: '97773',
        lrc: 'x',
        cachedAt: 1,
        userChosen: true,
      );
      expect(e.storedSongId, 'user|97773');
      expect(e.providerSongId, '97773');
      expect(e.userChosen, isTrue);
    });

    test('should_keepSongIdUnmarked_when_entryIsAutomatic', () {
      final e = LyricsCachePolicy.entry(
        songKey: 's',
        lyricsProvider: 'qq',
        lyricsProviderSongId: '97773',
        lrc: 'x',
        cachedAt: 1,
        userChosen: false,
      );
      expect(e.storedSongId, '97773');
      expect(e.userChosen, isFalse);
    });

    test('should_keepManualSongIdNull_when_entryIsTypedLyrics', () {
      final e = LyricsCachePolicy.entry(
        songKey: 's',
        lyricsProvider: 'manual',
        lyricsProviderSongId: null,
        lrc: 'x',
        cachedAt: 1,
        userChosen: true,
      );
      expect(e.storedSongId, isNull);
      expect(e.userChosen, isTrue);
      expect(LyricsCachePolicy.isTyped(e.lyricsProvider), isTrue);
      expect(LyricsCachePolicy.providerSongId('user|'), isNull);
    });

    test('should_expireAutomaticRow_when_olderThanTtl', () {
      const now = 100 * LyricsCachePolicy.automaticTtlMs;
      final old = LyricsCachePolicy.entry(
        songKey: 's',
        lyricsProvider: 'qq',
        lyricsProviderSongId: '1',
        lrc: 'x',
        cachedAt: now - LyricsCachePolicy.automaticTtlMs - 1,
        userChosen: false,
      );
      expect(LyricsCachePolicy.isUsable(old, now), isFalse);
      final chosen = LyricsCachePolicy.entry(
        songKey: 's',
        lyricsProvider: 'qq',
        lyricsProviderSongId: '1',
        lrc: 'x',
        cachedAt: 0,
        userChosen: true,
      );
      expect(LyricsCachePolicy.isUsable(chosen, now), isTrue);
      expect(LyricsCachePolicy.needsRecencyRefresh(chosen, now), isTrue);
      expect(LyricsCachePolicy.needsRecencyRefresh(old, now), isFalse, reason: 'an automatic TTL is never moved');
    });

    test('should_countUtf8Bytes_when_textHasCjk', () {
      expect(LyricsCachePolicy.utf8Bytes('晴天'), 6);
      expect(LyricsCachePolicy.utf8Bytes('ab'), 2);
    });

    test('should_evictNothing_when_withinBudget', () {
      expect(LyricsCachePolicy.planEviction([fp('a', bytes: 5, usedAt: 1)], budgetBytes: 10, targetBytes: 8), isEmpty);
    });

    test('should_evictAutomaticRowsBeforeUserRows_when_overBudget', () {
      final plan = LyricsCachePolicy.planEviction(
        [
          fp('chosenOld', bytes: 4, usedAt: 1, chosen: true),
          fp('autoNew', bytes: 4, usedAt: 5),
          fp('autoOld', bytes: 4, usedAt: 2),
        ],
        budgetBytes: 10,
        targetBytes: 4,
      );
      expect(plan.map((f) => f.songKey), ['autoOld', 'autoNew']);
    });

    test('should_evictLeastRecentlyUsedUserRowFirst_when_automaticRowsAreGone', () {
      final plan = LyricsCachePolicy.planEviction(
        [fp('recent', bytes: 6, usedAt: 9, chosen: true), fp('stale', bytes: 6, usedAt: 1, chosen: true)],
        budgetBytes: 10,
        targetBytes: 8,
      );
      expect(plan.map((f) => f.songKey), ['stale']);
    });

    test('should_neverEvictTypedOrProtected_when_theyAreTheOldest', () {
      final plan = LyricsCachePolicy.planEviction(
        [
          fp('typed', bytes: 6, usedAt: 0, typed: true),
          fp('just', bytes: 6, usedAt: 1),
          fp('other', bytes: 6, usedAt: 2),
        ],
        budgetBytes: 10,
        targetBytes: 6,
        protectedSong: 'just',
      );
      expect(plan.map((f) => f.songKey), ['other'], reason: 'stops short of the target rather than touch those');
    });
  });

  group('LyricsCacheStore + LyricsCacheBudget', () {
    late InMemoryStore kv;
    late LyricsCacheStore store;

    setUp(() {
      kv = InMemoryStore();
      store = LyricsCacheStore(kv);
    });

    Future<void> put(String key, String lrc, int at, {bool chosen = false, String provider = 'qq'}) => store.upsert(
      LyricsCachePolicy.entry(
        songKey: key,
        lyricsProvider: provider,
        lyricsProviderSongId: '1',
        lrc: lrc,
        cachedAt: at,
        userChosen: chosen,
      ),
    );

    test('should_roundTripRow_when_written', () async {
      await put('subsonic:1', '[00:01.00]x', 7, chosen: true);
      final back = await store.get('subsonic:1');
      expect(back?.lrc, '[00:01.00]x');
      expect(back?.lyricsProvider, 'qq');
      expect(back?.providerSongId, '1');
      expect(back?.userChosen, isTrue);
      expect(back?.cachedAt, 7);
    });

    test('should_moveOldUserPickIn_when_onlyLegacyRowExists', () async {
      await kv.write(
        LyricsCacheStore.legacyUserKey('subsonic:1'),
        jsonEncode({'lrc': '[00:01.00]mine', 'provider': 'manual', 'providerSongId': null}),
      );
      final back = await store.get('subsonic:1', now: 42);
      expect(back?.lrc, '[00:01.00]mine');
      expect(back?.userChosen, isTrue);
      expect(await kv.read(LyricsCacheStore.legacyUserKey('subsonic:1')), isNull);
      expect((await store.footprints()).single.typed, isTrue);
    });

    test('should_keepRowsOutOfTheSettingsStore_when_cacheHasItsOwn', () async {
      final settings = InMemoryStore();
      final caches = InMemoryStore();
      await settings.write(
        LyricsCacheStore.legacyUserKey('subsonic:1'),
        jsonEncode({'lrc': '[00:01.00]mine', 'provider': 'manual', 'providerSongId': null}),
      );
      final split = LyricsCacheStore(caches, legacy: settings);
      expect((await split.get('subsonic:1', now: 42))?.lrc, '[00:01.00]mine');
      await split.upsert(
        LyricsCachePolicy.entry(
          songKey: 'apple:2',
          lyricsProvider: 'qq',
          lyricsProviderSongId: '9',
          lrc: '[00:01.00]auto',
          cachedAt: 1,
          userChosen: false,
        ),
      );
      expect(settings.values, isEmpty, reason: 'the old pick moved out, nothing else written there');
      expect(caches.values.keys, contains(LyricsCacheStore.rowKey('subsonic:1')));
      expect(caches.values.keys, contains(LyricsCacheStore.rowKey('apple:2')));
    });

    test('should_countOnlyProviderTranslations_when_measuring', () async {
      await put('s', 'ab', 1);
      await store.upsertTranslation(
        songKey: 's',
        sourceHash: 'h',
        targetLanguage: 'Simplified Chinese',
        model: 'provider:qq',
        lines: ['一'],
        cachedAt: 2,
      );
      await store.upsertTranslation(
        songKey: 's',
        sourceHash: 'h',
        targetLanguage: 'English',
        model: 'gemini-3.1-flash-lite',
        lines: ['one'],
        cachedAt: 2,
      );
      final f = (await store.footprints()).single;
      expect(f.bytes, 2 + utf8.encode(jsonEncode(['一'])).length);
      expect(
        await store.getTranslation(
          songKey: 's',
          sourceHash: 'h',
          targetLanguage: 'English',
          model: 'gemini-3.1-flash-lite',
        ),
        ['one'],
      );
      expect(
        await store.getTranslation(
          songKey: 's',
          sourceHash: 'other',
          targetLanguage: 'English',
          model: 'gemini-3.1-flash-lite',
        ),
        isNull,
        reason: 'new lyrics never pick up an old translation',
      );
    });

    test('should_evictAutomaticRowsBeforeUserChosen_when_overBudget', () async {
      final budget = LyricsCacheBudget(store, budgetBytes: 10, targetBytes: 8);
      await put('auto', '1234', 1);
      await put('chosen', '1234', 0, chosen: true);
      await put('new', '1234', 5);
      await budget.onWrite('new');
      expect(await store.get('auto'), isNull);
      expect(await store.get('chosen'), isNotNull);
      expect(await store.get('new'), isNotNull, reason: 'the row just written is never evicted');
    });

    test('should_deleteProviderTranslationsWithTheirLyricRow_when_evicting', () async {
      final budget = LyricsCacheBudget(store, budgetBytes: 10, targetBytes: 6);
      await put('old', '1234', 1);
      await store.upsertTranslation(
        songKey: 'old',
        sourceHash: 'h',
        targetLanguage: 'Simplified Chinese',
        model: 'provider:qq',
        lines: ['一'],
        cachedAt: 1,
      );
      await put('new', '12345678', 5);
      await budget.onWrite('new');
      expect(await store.get('old'), isNull);
      expect(
        await store.getTranslation(
          songKey: 'old',
          sourceHash: 'h',
          targetLanguage: 'Simplified Chinese',
          model: 'provider:qq',
        ),
        isNull,
      );
      expect((await store.footprints()).map((f) => f.songKey), ['new']);
    });

    test('should_keepRow_when_itWasRewrittenAfterTheSnapshot', () async {
      await put('s', 'abc', 1);
      expect(await store.deleteIfUnchanged('s', 0), 0);
      expect(await store.get('s'), isNotNull);
      expect(await store.deleteIfUnchanged('s', 1), 1);
      expect(await store.get('s'), isNull);
    });

    test('should_keepTypedLyrics_when_theyAreTheLeastRecentlyUsed', () async {
      final budget = LyricsCacheBudget(store, budgetBytes: 10, targetBytes: 6);
      await put('typed', '123456', 0, provider: 'manual', chosen: true);
      await put('auto', '1234', 2);
      await put('new', '1234', 5);
      await budget.onWrite('new');
      expect(await store.get('typed'), isNotNull);
      expect(await store.get('auto'), isNull);
    });

    test('should_refreshRecency_when_markedUsed', () async {
      await put('s', 'abc', 1, chosen: true);
      await store.markUsed('s', 99);
      expect((await store.get('s'))?.cachedAt, 99);
      expect((await LyricsCacheStore(kv).footprints()).single.lastUsedAt, 99, reason: 'the index is persisted');
    });
  });
}
