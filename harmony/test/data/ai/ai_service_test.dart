import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/data/ai/ai_client.dart';
import 'package:yoin/data/ai/ai_service.dart';
import 'package:yoin/data/ai/ai_settings.dart';

import '../../support/fake_ai_client.dart';

/// Android GeminiService over any service: About with its searches, Ask,
/// lyric translation, and the parsing of the tagged answers.
void main() {
  group('runAbout', () {
    test('should_searchTwiceAndPutPagesInPrompt_when_deepSeekBestRuns', () async {
      final fake = FakeAiClient(
        reply: '[CREATION_TIME]1999[/CREATION_TIME][LYRICIST]N/A[/LYRICIST][REVIEW]Good.[/REVIEW]',
        hits: const [
          AiSearchHit(title: 'Credits', url: 'u1', text: 'Written by A'),
          AiSearchHit(title: 'Same', url: 'u1', text: 'dup'),
        ],
      );
      final run = await AiService(fake).runAbout(
        const AiSettings(provider: AiProvider.deepSeek, deepSeekKey: 'd', tier: AiTier.best),
        title: 'Song',
        artist: 'Artist',
        album: 'Album',
        targetLanguage: 'English',
      );
      expect(fake.queries, ['Song Artist songwriting credits producer', 'Song Artist Album']);
      expect(run.searchKind, AiSearchKind.external);
      expect(run.searchQueries, fake.queries);
      expect(run.pagesRead, 1);
      expect(run.values, const [AboutValue(AboutFields.creationTime, '1999'), AboutValue(AboutFields.review, 'Good.')]);
      final prompt = fake.prompts.single;
      expect(prompt, contains('Use the search results provided below to find accurate information.'));
      expect(prompt, endsWith('Search results:\n[1] Credits (u1)\nWritten by A'));
      expect(fake.useSearch.single, isTrue);
    });

    test('should_letModelSearch_when_serviceSearchesOnItsOwn', () async {
      final fake = FakeAiClient(reply: '[PRODUCER]P[/PRODUCER]');
      final run = await AiService(fake).runAbout(
        const AiSettings(provider: AiProvider.qwen, qwenKey: 'q', tier: AiTier.best),
        title: 'S',
        artist: 'A',
        album: 'B',
        targetLanguage: 'Japanese',
      );
      expect(fake.queries, isEmpty);
      expect(run.searchQueries, isEmpty);
      expect(run.searchKind, AiSearchKind.builtIn);
      expect(fake.prompts.single, contains('Use the search tool to find accurate information.'));
      expect(fake.prompts.single, contains('answers in Japanese.'));
    });

    test('should_answerFromMemory_when_customHasNoSearch', () async {
      final fake = FakeAiClient(reply: '');
      await AiService(fake).runAbout(
        const AiSettings(provider: AiProvider.custom, searchService: AiSearchService.none),
        title: 'S',
        artist: 'A',
        album: 'B',
        targetLanguage: 'English',
      );
      expect(fake.queries, isEmpty);
      expect(fake.prompts.single, contains('Answer from what you reliably know; write "N/A"'));
    });
  });

  group('translateLyricLines', () {
    test('should_translateWithoutSearch_when_anyServiceIsSet', () async {
      final fake = FakeAiClient(reply: '[L0]Hello[/L0]\n[L1]World[/L1]');
      final lines = await AiService(fake).translateLyricLines(
        const AiSettings(provider: AiProvider.deepSeek, deepSeekKey: 'd'),
        title: 't',
        artist: 'a',
        lines: ['你好', '世界'],
        targetLanguage: 'English',
      );
      expect(lines, {0: 'Hello', 1: 'World'});
      expect(fake.useSearch.single, isFalse);
      expect(fake.prompts.single, startsWith('Translate these lyric lines into English.'));
    });

    test('should_fail_when_answerHasNoUsableLines', () async {
      expect(
        AiService(FakeAiClient(reply: 'sorry')).translateLyricLines(
          const AiSettings(),
          title: 't',
          artist: 'a',
          lines: ['1', '2'],
          targetLanguage: 'English',
        ),
        throwsA(isA<AiException>()),
      );
    });
  });

  group('parsing', () {
    test('should_dropEmptyFields_when_readingAbout', () {
      expect(
        parseAboutResponse('[ creation_location ]Tokyo[/creation_location][COMPOSER]无法获取[/COMPOSER][PRODUCER]null'),
        const [AboutValue(AboutFields.creationLocation, 'Tokyo')],
      );
      expect(parseAboutResponse('[REVIEW]Cut off mid'), const [AboutValue(AboutFields.review, 'Cut off mid')]);
    });

    test('should_fallBackToQuestion_when_askAnswerIsUntagged', () {
      expect(parseAskResponse('[TITLE]Who[/TITLE][ANSWER]Her[/ANSWER]', 'q'), const AskAnswer('Who', 'Her'));
      expect(parseAskResponse('[ANSWER]Her', ' q '), const AskAnswer('q', 'Her'));
      expect(parseAskResponse('Plain [X] prose', 'q'), const AskAnswer('q', 'Plain  prose'));
    });

    test('should_trimQuotesAndPunctuation_when_cleaningAskTitle', () {
      expect(cleanAskTitle('\n“Who wrote it?”\nmore', 'q'), 'Who wrote it');
      expect(cleanAskTitle('谁写的。', 'q'), '谁写的');
      expect(cleanAskTitle('  ', ' fallback '), 'fallback');
    });

    test('should_readTaggedBlocks_when_modelAnswersInTags', () {
      expect(parseLineTranslations('[L0] 1. Hello [/L0]\n[L1]World[/L1]\n[L9]stray[/L9]', 2), {0: 'Hello', 1: 'World'});
      expect(parseLineTranslations('Hello\n\nWorld', 2), {0: 'Hello', 1: 'World'});
      expect(parseLineTranslations('Hello', 2), isEmpty);
    });
  });
}
