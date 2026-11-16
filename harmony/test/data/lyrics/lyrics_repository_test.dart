import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:yoin/data/ai/ai_settings.dart';
import 'package:yoin/data/ai/gemini_client.dart';
import 'package:yoin/data/lyrics/lrc_parser.dart';
import 'package:yoin/data/lyrics/lyric_provider.dart';
import 'package:yoin/data/lyrics/lyrics_cache.dart';
import 'package:yoin/data/lyrics/lyrics_registry.dart';
import 'package:yoin/data/lyrics/lyrics_repository.dart';
import 'package:yoin/data/lyrics/lyrics_translation.dart';
import 'package:yoin/data/lyrics/netease_provider.dart';
import 'package:yoin/data/model/model.dart';
import 'package:yoin/data/source/music_source.dart';
import 'package:yoin/nowplaying/lyrics_logic.dart';
import 'package:yoin/platform/storage/stores.dart';

import '../../support/fake_music_source.dart';

/// The lyrics half of Android's YoinRepository: load order, the fallback
/// chain and its cache, translation by the lyrics' own source, the switch
/// offer, and Gemini.
void main() {
  late InMemoryStore kv;
  late LyricsCacheStore cache;
  late int now;
  late List<http.Request> geminiCalls;
  late AiSettings ai;
  late String geminiReply;

  Track track({String provider = MediaId.providerAppleMusic, String title = 'Song', String artist = 'Artist'}) =>
      Track(id: MediaId(provider, '1'), title: title, artist: artist, durationSec: 200);

  LyricsRepository repo(List<LyricProvider> providers, {NetEaseTranslator? netEase}) => LyricsRepository(
    registry: LyricsProviderRegistry(providers: providers),
    cache: cache,
    aiSettings: () async => ai,
    gemini: GeminiClient(
      client: MockClient((request) async {
        geminiCalls.add(request);
        return http.Response.bytes(
          utf8.encode(
            jsonEncode({
              'candidates': [
                {
                  'content': {
                    'parts': [
                      {'text': geminiReply},
                    ],
                  },
                },
              ],
            }),
          ),
          200,
        );
      }),
    ),
    netEaseTranslator: netEase,
    clock: () => now,
  );

  setUp(() {
    kv = InMemoryStore();
    cache = LyricsCacheStore(kv);
    now = 10 * LyricsCachePolicy.automaticTtlMs;
    geminiCalls = [];
    ai = const AiSettings();
    geminiReply = '[L0]First[/L0]\n[L1]Second[/L1]';
  });

  group('getLoadedLyrics', () {
    test('should_preferUserPick_when_subsonicServerHasLyricsToo', () async {
      final r = repo([]);
      final song = track(provider: MediaId.providerSubsonic);
      await r.applyLyrics(song, '[00:01.00]mine');
      final source = _Source.subsonic(lyrics: SyncedLyrics([LyricLine(0, 'server')]));
      final loaded = await r.getLoadedLyrics(song, source);
      expect(loaded?.sheet.rows.single.text, 'mine');
      expect(loaded?.providerName, LyricsCachePolicy.manualProvider);
      expect(source.lyricRequests, isEmpty);
    });

    test('should_useOnlyServerLyrics_when_subsonicHasNoPick', () async {
      final qq = _Provider('qq', lrc: '[00:01.00]from qq');
      final r = repo([qq]);
      final source = _Source.subsonic(lyrics: null);
      expect(await r.getLoadedLyrics(track(provider: MediaId.providerSubsonic), source), isNull);
      expect(qq.searches, isEmpty, reason: 'Subsonic never runs the fallback chain');
      final withServer = _Source.subsonic(lyrics: SyncedLyrics([LyricLine(0, 'server')]));
      final loaded = await r.getLoadedLyrics(track(provider: MediaId.providerSubsonic), withServer);
      expect(loaded?.providerName, 'subsonic');
      expect(loaded?.providerSongId, '1');
    });

    test('should_walkTheChainAndCache_when_serviceHasNoLyrics', () async {
      final qq = _Provider('qq');
      final netease = _Provider('netease', lrc: '[00:01.00]from netease');
      final r = repo([qq, netease]);
      final loaded = await r.getLoadedLyrics(track(), _Source.apple());
      expect(loaded?.sheet.rows.single.text, 'from netease');
      expect(loaded?.providerName, 'netease');
      expect(qq.searches.single, ('Song', 'Artist', 200000));
      final row = await cache.get(track().id.toString());
      expect(row?.userChosen, isFalse);
      expect(row?.lyricsProvider, 'netease');

      // Within 30 days the cached row answers; nothing is searched again.
      final again = await r.getLoadedLyrics(track(), _Source.apple());
      expect(again?.sheet.rows.single.text, 'from netease');
      expect(netease.searches, hasLength(1));

      // After 30 days it is matched again.
      now += LyricsCachePolicy.automaticTtlMs + 1;
      await r.getLoadedLyrics(track(), _Source.apple());
      expect(netease.searches, hasLength(2));
    });

    test('should_retryUnderEnglishNames_when_shownNamesFindNothing', () async {
      final lrclib = _Provider('lrclib', onlyFor: ('I LUV IT', 'Kim Petras'), lrc: '[00:01.00]english');
      final r = repo([lrclib]);
      final source = _Source.apple(
        alternate: const TrackNames(title: 'I LUV IT', artist: 'Kim Petras'),
      );
      final loaded = await r.getLoadedLyrics(track(title: 'アイ・ラヴ・イット', artist: 'キム・ペトラス'), source);
      expect(loaded?.sheet.rows.single.text, 'english');
      expect(lrclib.searches.map((s) => s.$1), ['アイ・ラヴ・イット', 'I LUV IT']);
    });

    test('should_notAskForOtherNames_when_shownNamesAreLatin', () async {
      final lrclib = _Provider('lrclib');
      final source = _Source.apple(
        alternate: const TrackNames(title: 'X', artist: 'Y'),
      );
      expect(await repo([lrclib]).getLoadedLyrics(track(), source), isNull);
      expect(source.alternateAsked, 0);
    });
  });

  group('translateLyrics', () {
    LyricSheet sheetOf(String lrc) => lyricSheetOf(LrcParser.parse(lrc));

    test('should_sayAlreadyChinese_when_targetIsChineseAndLinesAre', () async {
      ai = const AiSettings(targetLanguage: 'Simplified Chinese');
      final result = await repo([]).translateLyrics(
        track: track(),
        sheet: sheetOf('[00:01.00]大千世界曾由我主宰巨浪也曾因我之命澎湃'),
        currentProviderName: 'subsonic',
        currentProviderSongId: null,
      );
      expect(result, isA<LyricsAlreadyTargetLanguage>().having((r) => r.targetLanguage, 'lang', 'Simplified Chinese'));
    });

    test('should_useCurrentSourcesTranslation_when_itTranslates', () async {
      ai = const AiSettings(targetLanguage: 'Simplified Chinese');
      final qq = _Provider(
        'qq',
        lrc: '[00:01.00]one\n[00:02.00]two',
        translation: '[00:01.00]一\n[00:02.00]//',
        native: true,
      );
      final r = repo([qq]);
      await r.applyLyrics(track(), '[00:01.00]one\n[00:02.00]two', provider: 'qq', providerSongId: '5');
      final result = await r.translateLyrics(
        track: track(),
        sheet: sheetOf('[00:01.00]one\n[00:02.00]two'),
        currentProviderName: 'qq',
        currentProviderSongId: '5',
      );
      expect(result, isA<LyricsTranslated>());
      final done = result as LyricsTranslated;
      expect(done.translations, {0: '一'}, reason: '"//" means untranslated');
      expect(done.providerName, 'qq');
      expect(qq.fetched, ['5']);
      expect((await cache.get(track().id.toString()))?.userChosen, isTrue, reason: 'a pick fetched again stays one');
      expect(geminiCalls, isEmpty);
    });

    test('should_offerLaterSource_when_currentSourceHasNoTranslation', () async {
      ai = const AiSettings(targetLanguage: 'Simplified Chinese', geminiKey: 'k');
      final qq = _Provider('qq', lrc: '[00:01.00]one', native: true);
      final netease = _Provider('netease', lrc: '[00:01.00]one', translation: '[00:01.00]壹', native: true);
      final lrclib = _Provider('lrclib', lrc: '[00:01.00]one');
      final r = repo([qq, netease, lrclib]);
      final result = await r.translateLyrics(
        track: track(),
        sheet: sheetOf('[00:01.00]one'),
        currentProviderName: 'qq',
        currentProviderSongId: '5',
      );
      expect(result, isA<LyricsSwitchAvailable>());
      final offer = (result as LyricsSwitchAvailable).offer;
      expect(offer.providerName, 'netease');
      expect(offer.translations, {0: '壹'});
      expect(geminiCalls, isEmpty);
      expect(await cache.get(track().id.toString()), isNull, reason: 'an offer is not applied');

      await r.applySwitch(track(), offer);
      final row = await cache.get(track().id.toString());
      expect(row?.lyricsProvider, 'netease');
      expect(row?.userChosen, isTrue);
    });

    test('should_neverOfferEarlierOrNonTranslatingSources_when_listingCandidates', () {
      final registry = LyricsProviderRegistry(
        providers: [_Provider('qq', native: true), _Provider('netease', native: true), _Provider('lrclib')],
      );
      expect(registry.translationSwitchCandidates('qq', 'Simplified Chinese'), ['netease']);
      expect(registry.translationSwitchCandidates('netease', 'Simplified Chinese'), isEmpty);
      expect(registry.translationSwitchCandidates('qq', 'English'), isEmpty);
      expect(registry.translationSwitchCandidates('subsonic', 'Simplified Chinese'), isEmpty);
    });

    test('should_translateWithGeminiAndCache_when_sourceNeverTranslates', () async {
      ai = const AiSettings(geminiKey: 'AIza-test', tier: AiTier.best, targetLanguage: 'English');
      final r = repo([]);
      final sheet = sheetOf('[00:01.00]一行\n[00:02.00]\n[00:03.00]二行');
      final result = await r.translateLyrics(
        track: track(),
        sheet: sheet,
        currentProviderName: 'subsonic',
        currentProviderSongId: null,
      );
      expect(result, isA<LyricsTranslated>());
      // Gemini saw only the lines with words; its answers land on their rows.
      expect((result as LyricsTranslated).translations, {0: 'First', sheet.rows.length - 1: 'Second'});
      final call = geminiCalls.single;
      expect(call.url.path, '/v1beta/models/${AiSettings.geminiBest}:generateContent');
      expect(call.headers['x-goog-api-key'], 'AIza-test');
      expect(call.body, contains('Translate these lyric lines into English.'));

      final again = await r.translateLyrics(
        track: track(),
        sheet: sheet,
        currentProviderName: 'subsonic',
        currentProviderSongId: null,
      );
      expect((again as LyricsTranslated).translations, result.translations);
      expect(geminiCalls, hasLength(1), reason: 'the second answer comes from the cache');
    });

    test('should_sayKeyMissing_when_noKeyAndNothingElseTranslates', () async {
      final result = await repo([]).translateLyrics(
        track: track(),
        sheet: sheetOf('[00:01.00]one'),
        currentProviderName: 'lrclib',
        currentProviderSongId: '1',
      );
      expect(result, isA<LyricsApiKeyMissing>());
    });

    test('should_lineUpNetEase_when_noKeyIsSet', () async {
      final netEase = NetEaseTranslator(
        provider: NetEaseLyricsProvider(
          client: MockClient((request) async {
            final body = request.url.path == '/cloudsearch'
                ? {
                    'result': {
                      'songs': [
                        {
                          'id': 5,
                          'name': 'Song',
                          'ar': [
                            {'name': 'Artist'},
                          ],
                        },
                      ],
                    },
                  }
                : {
                    'lrc': {'lyric': '[00:01.00]one'},
                    'tlyric': {'lyric': '[00:01.00]壹'},
                  };
            return http.Response.bytes(utf8.encode(jsonEncode(body)), 200);
          }),
        ),
      );
      final result = await repo([], netEase: netEase).translateLyrics(
        track: track(provider: MediaId.providerSubsonic),
        sheet: sheetOf('[00:01.00]one'),
        currentProviderName: 'subsonic',
        currentProviderSongId: null,
      );
      expect((result as LyricsTranslated).translations, {0: '壹'});
    });

    test('should_reportGeminisError_when_requestFails', () async {
      ai = const AiSettings(geminiKey: 'bad');
      final r = LyricsRepository(
        registry: LyricsProviderRegistry(providers: []),
        cache: cache,
        aiSettings: () async => ai,
        gemini: GeminiClient(
          client: MockClient((_) async => http.Response('{"error":{"message":"API key not valid"}}', 400)),
        ),
      );
      final result = await r.translateLyrics(
        track: track(),
        sheet: sheetOf('[00:01.00]one'),
        currentProviderName: null,
        currentProviderSongId: null,
      );
      expect(
        result,
        isA<LyricsTranslationFailed>().having((f) => f.message, 'message', 'Gemini error (400): API key not valid'),
      );
    });
  });

  group('Gemini parsing', () {
    test('should_readTaggedBlocks_when_modelAnswersInTags', () {
      expect(parseLineTranslations('[L0] 1. Hello [/L0]\n[L1]World[/L1]\n[L9]stray[/L9]', 2), {0: 'Hello', 1: 'World'});
    });

    test('should_readPlainLines_when_countMatches', () {
      expect(parseLineTranslations('Hello\n\nWorld', 2), {0: 'Hello', 1: 'World'});
      expect(parseLineTranslations('Hello', 2), isEmpty);
    });

    test('should_stripNumbering_when_cleaning', () {
      expect(cleanLineTranslation('• [3] Hi'), 'Hi');
      expect(cleanLineTranslation('【2】、Hi'), 'Hi');
    });

    test('should_normalizeTargetLanguage_when_unknown', () {
      expect(AiLanguages.normalize('Klingon'), 'English');
      expect(AiLanguages.normalize('Japanese'), 'Japanese');
    });

    test('should_keepKeyInSecrets_when_settingsStored', () async {
      final plain = InMemoryStore();
      final secrets = InMemoryStore();
      final store = AiSettingsStore(plain, secrets);
      await store.write(const AiSettings(geminiKey: ' k ', tier: AiTier.best, targetLanguage: 'Korean'));
      expect(secrets.values[AiSettingsStore.geminiKeyKey], 'k');
      expect(plain.values[AiSettingsStore.prefsKey], isNot(contains('k"')));
      expect(await store.read(), const AiSettings(geminiKey: 'k', tier: AiTier.best, targetLanguage: 'Korean'));
    });

    test('should_hashLinesLikeAndroid_when_keyingTheCache', () {
      expect(lyricsTranslationSourceHash('t', 'a', ['x']), isNot(lyricsTranslationSourceHash('t', 'a', ['y'])));
      expect(
        lyricsTranslationSourceHash('t', 'a', ['ab', 'c']),
        isNot(lyricsTranslationSourceHash('t', 'a', ['a', 'bc'])),
      );
    });
  });
}

class _Provider extends LyricProvider {
  _Provider(this.name, {this.lrc, this.translation, this.native = false, this.onlyFor});

  @override
  final String name;
  final String? lrc;
  final String? translation;
  final bool native;

  /// Only this title and artist are found (else every search hits).
  final (String, String)? onlyFor;
  final List<(String, String, int?)> searches = [];
  final List<String> fetched = [];

  @override
  NativeTranslation? get nativeTranslation => native ? NativeTranslation.simplifiedChinese : null;

  @override
  Future<SongMatch?> search(String title, String artist, {int? durationMs}) async {
    searches.add((title, artist, durationMs));
    if (lrc == null) return null;
    if (onlyFor != null && onlyFor != (title, artist)) return null;
    return SongMatch(songId: '9', title: title, artist: artist);
  }

  @override
  Future<String?> fetchLyric(String songId) async {
    fetched.add(songId);
    return lrc;
  }

  @override
  Future<LyricPayload?> fetchLyricWithTranslation(String songId) async {
    fetched.add(songId);
    final l = lrc;
    return l == null ? null : LyricPayload(lyric: l, translatedLyric: translation);
  }
}

class _Source extends FakeMusicSource implements MusicMetadata {
  _Source._(this._provider, this._lyrics, this._caps, {this.alternate});

  factory _Source.subsonic({Lyrics? lyrics}) => _Source._(MediaId.providerSubsonic, lyrics, const {Capability.lyrics});

  factory _Source.apple({TrackNames? alternate}) =>
      _Source._(MediaId.providerAppleMusic, null, const {}, alternate: alternate);

  final String _provider;
  final Lyrics? _lyrics;
  final Set<Capability> _caps;
  final TrackNames? alternate;
  final List<String> lyricRequests = [];
  int alternateAsked = 0;

  @override
  String get provider => _provider;
  @override
  Set<Capability> get capabilities => _caps;
  @override
  MusicMetadata get metadata => this;

  @override
  Future<Lyrics?> getLyrics(Track track) async {
    lyricRequests.add(track.id.rawId);
    return _lyrics;
  }

  @override
  Future<TrackNames?> alternateNames(Track track) async {
    alternateAsked++;
    return alternate;
  }
}
