import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/about/album_about_repository.dart';
import 'package:yoin/data/about/album_about_store.dart';
import 'package:yoin/data/about/song_about_repository.dart';
import 'package:yoin/data/ai/ai_client.dart';
import 'package:yoin/data/ai/ai_service.dart';
import 'package:yoin/data/ai/ai_settings.dart';
import 'package:yoin/data/albumabout/album_about_models.dart';
import 'package:yoin/data/albumabout/yoin_album_about.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/platform/storage/stores.dart';

import '../../support/fake_ai_client.dart';

const _ready = AiSettings(provider: AiProvider.qwen, qwenKey: 'q', targetLanguage: 'English');

const _reply = '''
[CREDITS]
{"label": null, "producers": {"names": ["Joni Mitchell"], "confidence": "high"}, "mastering": null,
 "mixing": null, "recorded": {"time": "1971", "studios": ["A&M Studios, Los Angeles"], "confidence": "medium"},
 "source": null, "first_released": null,}
[/CREDITS]
[ABOUT]
Blue is the fourth album by Joni Mitchell.
[/ABOUT]''';

const _knownEmpty = '[CREDITS]\n{}\n[/CREDITS]\n[ABOUT]\nN/A\n[/ABOUT]';

final _album = Album(
  id: MediaId.subsonic('al1'),
  name: 'Blue',
  artist: 'Joni Mitchell',
  year: 1971,
  genre: 'Folk',
  releaseType: ReleaseType.album,
  releaseDate: '1971-06-22',
  recordLabels: const ['Reprise'],
  tracks: [
    Track(id: MediaId.subsonic('t1'), title: 'All I Want', durationSec: 200),
    Track(id: MediaId.subsonic('t2'), title: '  ', durationSec: 100),
    Track(id: MediaId.subsonic('t3'), title: 'River', durationSec: 240),
  ],
);

/// Android YoinRepository's album About: one AI call per album identity, known-empty stored, a failure
/// stored nowhere, Regenerate with Undo, questions keyed by YoinAlbumAbout.questionKey.
void main() {
  late AlbumAboutStore store;
  late FakeAiClient client;
  late AiSettings settings;
  var now = 1000;

  AlbumAboutRepository repo() =>
      AlbumAboutRepository(store: store, settings: () async => settings, ai: AiService(client), clock: () => now);

  setUp(() {
    store = AlbumAboutStore(InMemoryStore());
    client = FakeAiClient(reply: _reply);
    settings = _ready;
    now = 1000;
  });

  group('grounding', () {
    test('should_carryTheServicesFacts_when_groundingAnAlbum', () {
      final g = albumAboutGrounding(_album);
      expect(g.album, 'Blue');
      expect(g.artist, 'Joni Mitchell');
      expect(g.labels, ['Reprise']);
      expect(g.releaseDate, '1971-06-22');
      expect(g.releaseType, 'Album');
      expect(g.tracks, ['All I Want', 'River']);
      expect(g.totalDurationSec, 540);
      expect(g.serviceNotes, isNull);
    });
  });

  group('ensure', () {
    test('should_reportKeyMissingAndAskNothing_when_noService', () async {
      settings = const AiSettings();
      expect(await repo().isAiReady(), isFalse);
      expect(await repo().ensure(_album), isA<AboutLoadKeyMissing>());
      expect(client.prompts, isEmpty);
      expect(await store.all(), isEmpty);
    });

    test('should_writeCreditsAndBlurbOnce_when_askedThenOpenedAgain', () async {
      final r = repo();
      expect(await r.ensure(_album), isA<AboutLoadSuccess>());
      expect(client.prompts, hasLength(1));
      // The shared prompt, with the service's label as ground truth.
      expect(client.prompts.single, contains('Record label (from the service): Reprise'));
      final rows = await store.forAlbum('subsonic', 'al1');
      final content = YoinAlbumAbout.content([for (final row in rows) row.row]);
      expect(content.generated, isTrue);
      expect(content.blurb, 'Blue is the fourth album by Joni Mitchell.');
      expect(content.credits.producers, ['Joni Mitchell']);
      expect(content.credits.recordedTime, '1971');
      expect(rows.first.albumDisplay, 'Blue');
      // Cached: the next visit asks nothing.
      expect(await r.ensure(_album), isA<AboutLoadSuccess>());
      expect(client.prompts, hasLength(1));
    });

    test('should_storeTheAnswer_when_theAiKnewNothing', () async {
      client.reply = _knownEmpty;
      expect(await repo().ensure(_album), isA<AboutLoadSuccess>());
      final rows = await store.forAlbum('subsonic', 'al1');
      expect(rows, hasLength(2));
      final content = YoinAlbumAbout.content([for (final row in rows) row.row]);
      expect(content.generated, isTrue);
      expect(content.blurb, isNull);
      expect(content.credits.isEmpty, isTrue);
      expect(await repo().ensure(_album), isA<AboutLoadSuccess>());
      expect(client.prompts, hasLength(1));
    });

    test('should_storeNothing_when_theReplyIsNotAnAnswer', () async {
      client.reply = 'I cannot help with that.';
      expect(await repo().ensure(_album), isA<AboutLoadError>());
      expect(await store.all(), isEmpty);
      // Asked again next time.
      client.reply = _reply;
      expect(await repo().ensure(_album), isA<AboutLoadSuccess>());
      expect(client.prompts, hasLength(2));
    });

    test('should_storeNothing_when_theCallFails', () async {
      client.error = const AiException('boom');
      expect(await repo().ensure(_album), isA<AboutLoadError>());
      expect(await store.all(), isEmpty);
    });

    test('should_keyByProviderAndAlbumId_when_twoReleasesShareAName', () async {
      await repo().ensure(_album);
      final deluxe = Album(id: MediaId.subsonic('al2'), name: 'Blue', artist: 'Joni Mitchell');
      expect(await store.forAlbum('subsonic', 'al2'), isEmpty);
      await repo().ensure(deluxe);
      expect(client.prompts, hasLength(2));
      expect(await store.forAlbum('subsonic', 'al2'), hasLength(2));
    });
  });

  group('regenerate', () {
    test('should_returnTheRowsBeforeAndKeepCreatedAt_when_regenerated', () async {
      final r = repo();
      await r.ensure(_album);
      final before = await store.canonical('subsonic', 'al1');
      now = 5000;
      client.reply = _reply.replaceFirst('fourth album', 'fourth studio album');
      final result = await r.regenerate(_album);
      expect(result, isA<AlbumAboutRegenerated>());
      expect((result as AlbumAboutRegenerated).before.toSet(), before.toSet());
      final after = await store.canonical('subsonic', 'al1');
      expect(after.every((row) => row.createdAt == 1000 && row.updatedAt == 5000), isTrue);
      expect(after.firstWhere((row) => row.entryKey == YoinAlbumAbout.canonBlurb).answerText, contains('studio'));
      // Undo writes the old rows back, stamped now so they sync as the newest.
      now = 6000;
      await r.restore(result.before);
      final restored = await store.canonical('subsonic', 'al1');
      expect(
        restored.firstWhere((row) => row.entryKey == YoinAlbumAbout.canonBlurb).answerText,
        isNot(contains('studio')),
      );
      expect(restored.every((row) => row.updatedAt == 6000), isTrue);
    });

    test('should_keepWhatWasThere_when_regenerateComesBackEmpty', () async {
      final r = repo();
      await r.ensure(_album);
      final before = await store.canonical('subsonic', 'al1');
      client.reply = _knownEmpty;
      expect(await r.regenerate(_album), isA<AlbumAboutNothingNew>());
      expect((await store.canonical('subsonic', 'al1')).toSet(), before.toSet());
    });

    test('should_reportKeyMissingOrError_when_noServiceOrFailure', () async {
      settings = const AiSettings();
      expect(await repo().regenerate(_album), isA<AlbumAboutKeyMissing>());
      settings = _ready;
      client.error = const AiException('down');
      expect(await repo().regenerate(_album), isA<AlbumAboutRegenerateError>());
      expect(await store.all(), isEmpty);
    });
  });

  group('ask', () {
    test('should_fileTheAnswerUnderTheQuestionKey_when_askedTwice', () async {
      client.reply = '[TITLE]Why blue[/TITLE]\n[ANSWER]Because.[/ANSWER]';
      final r = repo();
      expect(await r.ask(_album, '  Why is it called Blue? '), isA<AskSuccess>());
      expect(client.prompts.single, contains('Answer the following question about the album "Blue" by Joni Mitchell.'));
      now = 2000;
      client.reply = '[TITLE]Why blue[/TITLE]\n[ANSWER]Because, again.[/ANSWER]';
      await r.ask(_album, 'why is it called   blue?');
      final asks = [
        for (final row in await store.forAlbum('subsonic', 'al1'))
          if (!row.isCanonical) row,
      ];
      expect(asks, hasLength(1));
      expect(asks.single.entryKey, YoinAlbumAbout.questionKey('Why is it called Blue?'));
      expect(asks.single.answerText, 'Because, again.');
      expect(asks.single.createdAt, 1000);
      expect(asks.single.updatedAt, 2000);
      final content = YoinAlbumAbout.content([for (final row in await store.forAlbum('subsonic', 'al1')) row.row]);
      // Only questions: not "generated" (the page shows the question card, not the ✦ circle).
      expect(content.generated, isFalse);
      expect(
        content.asks.single,
        const AlbumAboutAsk(question: 'why is it called   blue?', title: 'Why blue', answer: 'Because, again.'),
      );
    });

    test('should_askNothing_when_blankOrNoService', () async {
      expect(await repo().ask(_album, '   '), isA<AskError>());
      settings = const AiSettings();
      expect(await repo().ask(_album, 'Why?'), isA<AskKeyMissing>());
      expect(client.prompts, isEmpty);
    });
  });

  group('store', () {
    test('should_readBackEveryRow_when_reopenedOnTheSameDocument', () async {
      final kv = InMemoryStore();
      final first = AlbumAboutStore(kv);
      await AlbumAboutRepository(
        store: first,
        settings: () async => _ready,
        ai: AiService(client),
        clock: () => 7,
      ).ensure(_album);
      final reopened = AlbumAboutStore(kv);
      expect((await reopened.all()).toSet(), (await first.all()).toSet());
      await reopened.deleteAll();
      expect(await reopened.all(), isEmpty);
    });
  });
}
