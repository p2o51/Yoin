import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/cards/memory_card.dart';
import 'package:yoin/data/memory/album_memory_candidate.dart';
import 'package:yoin/data/memory/memory_store.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/platform/cards/cards_channel.dart';

const _day = Duration.millisecondsPerDay;

AlbumMemoryCandidate _memory(String id, {int? heardAt, int? lastPlayedAt, double? album, double? songs}) =>
    AlbumMemoryCandidate(
      profileId: 'p1',
      albumId: MediaId.subsonic(id),
      albumName: 'Album $id',
      artistName: 'Artist',
      totalTracks: 10,
      ratedTrackCount: 0,
      ratingCoverage: 0,
      albumRating: album,
      averageSongRating: songs,
      hasAlbumReview: false,
      noteCount: 0,
      lastPlayedAt: lastPlayedAt,
      isMemoryEligible: true,
      plays: heardAt == null
          ? null
          : AlbumPlays(albumId: MediaId.subsonic(id), playCount: 1, firstPlayedAt: heardAt, lastPlayedAt: heardAt),
    );

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('selectMemoryCandidates', () {
    test('should_orderByLastHeard_when_poolIsStrengthRanked', () {
      final out = selectMemoryCandidates([
        _memory('strong', heardAt: 10),
        _memory('recent', heardAt: 30),
        _memory('visitedOnly', lastPlayedAt: 20),
      ]);
      expect(out.map((c) => c.albumId.rawId), ['recent', 'visitedOnly', 'strong']);
    });

    test('should_listEachAlbumOnce_when_latestAlsoInRecall', () {
      final out = selectMemoryCandidates([_memory('a', heardAt: 1), _memory('a', heardAt: 1), _memory('b', heardAt: 2)]);
      expect(out.map((c) => c.albumId.rawId), ['b', 'a']);
    });

    test('should_stopAtTheLimit_when_poolIsLarge', () {
      final out = selectMemoryCandidates([for (var i = 0; i < 20; i++) _memory('a$i', heardAt: i)]);
      expect(out, hasLength(memoryCardLimit));
      expect(out.first.albumId.rawId, 'a19');
    });
  });

  group('wording', () {
    test('should_preferAlbumScore_when_bothScoresExist', () {
      expect(memoryCardScore(_memory('a', album: 8.5, songs: 6)), '8.5');
      expect(memoryCardScore(_memory('a', songs: 7)), '7.0');
      expect(memoryCardScore(_memory('a')), isNull);
    });

    test('should_sayHowLongAgo_when_lastHeardKnown', () {
      const now = 1000 * _day;
      expect(lastHeardText(null, now, zh: false), isNull);
      expect(lastHeardText(now - 1000, now, zh: false), 'Today');
      expect(lastHeardText(now - _day, now, zh: false), '1 Day Ago');
      expect(lastHeardText(now - 3 * _day, now, zh: false), '3 Days Ago');
      expect(lastHeardText(now - 45 * _day, now, zh: false), '1 Month Ago');
      expect(lastHeardText(now - 100 * _day, now, zh: false), '3 Months Ago');
      expect(lastHeardText(now - 800 * _day, now, zh: false), '2 Years Ago');
      expect(lastHeardText(now - 3 * _day, now, zh: true), '3 天前');
      expect(lastHeardText(now, now, zh: true), '今天');
    });
  });

  group('channel', () {
    test('should_parseMemoryTap_when_idIsASessionId', () {
      expect(CardAction.fromMap({'kind': 'memory', 'id': '42'}), isA<CardOpenMemory>().having((a) => a.sessionId, 'id', 42));
      expect(CardAction.fromMap({'kind': 'memory', 'id': 'x'}), isA<CardOpenMemory>().having((a) => a.sessionId, 'id', null));
      expect(CardAction.fromMap({'kind': 'memory'}), isNull);
    });

    test('should_sendWordedItems_when_publishMemory', () async {
      const channel = MethodChannel('yoin/cards');
      final calls = <MethodCall>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        return null;
      });
      addTearDown(() => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, null));
      await const ChannelCardsHost().publishMemory([
        const MemoryCardItem(sessionId: 7, album: 'A', score: '8.0', lastHeard: 'Today'),
      ]);
      expect(calls.single.method, 'publishMemory');
      final item = ((calls.single.arguments as Map)['items'] as List).single as Map;
      expect(item['session'], '7');
      expect(item['artist'], '');
      expect(item['score'], '8.0');
      expect(item['lastHeard'], 'Today');
    });
  });
}
