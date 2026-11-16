import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/ai/ai_service.dart';
import 'package:yoin/data/ai/ai_settings.dart';
import 'package:yoin/data/memory/memory_copy_cache.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/memories/copy/memory_voice.dart';
import 'package:yoin/memories/memory_ai.dart';
import 'package:yoin/platform/storage/stores.dart';

import '../support/fake_ai_client.dart';

final _album = Album(id: MediaId.subsonic('al1'), name: 'Blue', artist: 'Joni', year: 1971);

/// Android getOrGenerateAlbumMemoryTitle and GeminiMemoryNarrationSource.
void main() {
  late InMemoryStore kv;
  late FakeAiClient client;
  late AiSettings settings;

  MemoryAi ai() => MemoryAi(
    cache: MemoryCopyCache(kv, profileId: 'p1'),
    settings: () async => settings,
    ai: AiService(client),
    clock: () => 7,
  );

  setUp(() {
    kv = InMemoryStore();
    client = FakeAiClient(reply: 'Blue in the morning');
    settings = const AiSettings(provider: AiProvider.gemini, geminiKey: 'g');
  });

  test('should_matchKotlinStringHash_when_hashed', () {
    expect(javaStringHash(''), 0);
    expect(javaStringHash('a'), 97);
    expect(javaStringHash('hello'), 99162322);
    // Overflows to negative like a Kotlin Int.
    expect(javaStringHash('polygenelubricants'), -2147483648);
  });

  group('title', () {
    test('should_generateOnceAndCache_when_writingUnchanged', () async {
      const writing = MemoryTitleWriting(kind: 'album review', text: 'I wept.');
      expect(await ai().title(_album, writing), 'Blue in the morning');
      expect(await ai().title(_album, writing), 'Blue in the morning');
      expect(client.prompts, hasLength(1));
      expect(client.prompts.single, contains('Their album review:\nI wept.'));
      expect(client.useSearch.single, isFalse);
      expect(await MemoryCopyCache(kv, profileId: 'p1').title(_album.id), 'Blue in the morning');
    });

    test('should_askAgain_when_writingChanged', () async {
      await ai().title(_album, const MemoryTitleWriting(kind: 'note', text: 'One'));
      client.reply = '"Two days"';
      expect(await ai().title(_album, const MemoryTitleWriting(kind: 'note', text: 'Two')), 'Two days');
      expect(client.prompts, hasLength(2));
    });

    test('should_keepCachedTitle_when_noServiceOrCallFails', () async {
      await ai().title(_album, const MemoryTitleWriting(kind: 'note', text: 'One'));
      settings = const AiSettings();
      expect(await ai().title(_album, const MemoryTitleWriting(kind: 'note', text: 'Two')), 'Blue in the morning');
      settings = const AiSettings(provider: AiProvider.gemini, geminiKey: 'g');
      client.error = Exception('down');
      expect(await ai().title(_album, const MemoryTitleWriting(kind: 'note', text: 'Two')), 'Blue in the morning');
    });

    test('should_readCacheOnly_when_userWroteNothing', () async {
      expect(await ai().title(_album, null), isNull);
      expect(client.prompts, isEmpty);
    });
  });

  group('narration', () {
    const brief = MemoryNarrationBrief(
      language: MemoryProseLanguage.en,
      facts: ['Plays in Yoin: 12', 'Last played: yesterday'],
    );

    test('should_useAndCacheNarration_when_voiceRulesHold', () async {
      client.reply =
          '[NARRATION]12 plays, the last yesterday.[/NARRATION]\n[QUESTION]What brought you back?[/QUESTION]';
      final first = await ai().narrate(_album.id, brief);
      expect(first, const MemoryNarrationText('12 plays, the last yesterday.', 'What brought you back?'));
      expect(await ai().narrate(_album.id, brief), first);
      expect(client.prompts, hasLength(1));
      expect(client.prompts.single, contains('- Plays in Yoin: 12\n- Last played: yesterday'));
      final row = await MemoryCopyCache(kv, profileId: 'p1').get(_album.id);
      expect(row!.promptHash, narrationCacheKey(brief));
      expect(row.promptHash, startsWith('narration-v2:'));
    });

    test('should_fallBackToTemplate_when_answerNamesTheMechanism', () async {
      client.reply = '[NARRATION]A memory of spring.[/NARRATION][QUESTION]Remember it?[/QUESTION]';
      expect(await ai().narrate(_album.id, brief), isNull);
    });

    test('should_fallBackToTemplate_when_questionIsNotOne', () async {
      client.reply = '[NARRATION]Twelve plays.[/NARRATION][QUESTION]Tell me more.[/QUESTION]';
      expect(await ai().narrate(_album.id, brief), isNull);
    });

    test('should_fallBackToTemplate_when_noService', () async {
      settings = const AiSettings();
      expect(await ai().narrate(_album.id, brief), isNull);
      expect(client.prompts, isEmpty);
    });

    test('should_askAgain_when_factsChanged', () async {
      client.reply = '[NARRATION]Twelve plays.[/NARRATION][QUESTION]Why?[/QUESTION]';
      await ai().narrate(_album.id, brief);
      await ai().narrate(
        _album.id,
        const MemoryNarrationBrief(language: MemoryProseLanguage.en, facts: ['Plays in Yoin: 13']),
      );
      expect(client.prompts, hasLength(2));
    });
  });

  test('should_keepBothHalves_when_titleAndNarrationWritten', () async {
    await ai().title(_album, const MemoryTitleWriting(kind: 'note', text: 'One'));
    client.reply = '[NARRATION]Twelve plays.[/NARRATION][QUESTION]Why?[/QUESTION]';
    await ai().narrate(_album.id, const MemoryNarrationBrief(language: MemoryProseLanguage.en, facts: ['x']));
    final row = await MemoryCopyCache(kv, profileId: 'p1').get(_album.id);
    expect(row!.title, 'Blue in the morning');
    expect(parseMemoryNarration(row.copy)!.question, 'Why?');
  });

  test('should_parseNarration_when_tagsPresent', () {
    expect(
      parseMemoryNarration('[NARRATION] "Hi." [/NARRATION][QUESTION]Ok？[/QUESTION]'),
      const MemoryNarrationText('Hi.', 'Ok？'),
    );
    expect(parseMemoryNarration('[NARRATION]Hi.[/NARRATION]'), isNull);
    expect(
      encodeMemoryNarration(const MemoryNarrationText('a', 'b?')),
      '[NARRATION]a[/NARRATION]\n[QUESTION]b?[/QUESTION]',
    );
  });
}
