import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/platform/i18n/name_transliterator.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('yoin/i18n');
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  late List<List<String>> batches;

  void serve(Map<String, String> pinyin) {
    messenger.setMockMethodCallHandler(channel, (call) async {
      expect(call.method, 'latinKeys');
      final texts = ((call.arguments as Map)['texts'] as List).cast<String>();
      batches.add(texts);
      return [for (final t in texts) pinyin[t] ?? t];
    });
  }

  setUp(() => batches = []);
  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  test('should_sendOnlyHanTexts_when_askedForKeys', () async {
    serve({'林间': 'lín jiān'});
    final t = ChannelNameTransliterator();
    expect(await t.latinKeys(['Beck', '林间', 'ヨルシカ']), ['Beck', 'lín jiān', 'ヨルシカ']);
    expect(batches, [
      ['林间'],
    ]);
  });

  test('should_notAskAgain_when_keyCached', () async {
    serve({'林间': 'lín jiān'});
    final t = ChannelNameTransliterator();
    await t.latinKeys(['林间']);
    expect(await t.latinKeys(['林间', '林间']), ['lín jiān', 'lín jiān']);
    expect(batches, hasLength(1));
  });

  test('should_keepAllKeys_when_cacheLimitExceeded', () async {
    serve({'林间': 'lín jiān', '周杰伦': 'zhōu jié lún', '陈奕迅': 'chén yì xùn'});
    final t = ChannelNameTransliterator(cacheLimit: 2);
    await t.latinKeys(['林间', '周杰伦']);
    expect(await t.latinKeys(['林间', '周杰伦', '陈奕迅']), ['lín jiān', 'zhōu jié lún', 'chén yì xùn']);
    expect(await t.latinKeys(['陈奕迅', '林间']), ['chén yì xùn', 'lín jiān']);
  });

  test('should_splitIntoBatches_when_manyTexts', () async {
    serve({});
    final t = ChannelNameTransliterator(batchSize: 2);
    await t.latinKeys(['一', '二', '三', '四', '五']);
    expect(batches.map((b) => b.length), [2, 2, 1]);
  });

  test('should_fallBackToIdentity_when_pluginMissing', () async {
    final t = ChannelNameTransliterator();
    expect(await t.latinKeys(['周杰伦', 'Beck']), ['周杰伦', 'Beck']);
  });

  test('should_fallBackToIdentity_when_pluginFails', () async {
    messenger.setMockMethodCallHandler(channel, (call) async => throw PlatformException(code: 'i18n'));
    final t = ChannelNameTransliterator();
    expect(await t.latinKeys(['周杰伦']), ['周杰伦']);
  });

  test('should_returnUnchanged_when_identityFallback', () async {
    expect(await const IdentityNameTransliterator().latinKeys(['周杰伦']), ['周杰伦']);
  });

  group('latinKeyMap', () {
    test('should_returnNull_when_noHan', () {
      expect(latinKeyMap(const IdentityNameTransliterator(), ['Beck', null, 'ヨルシカ']), isNull);
    });

    test('should_mapOnlyChangedNames_when_hanPresent', () async {
      serve({'林间': 'lín jiān'});
      final map = await latinKeyMap(ChannelNameTransliterator(), ['林间', '林间', null, 'Beck', '王菲']);
      expect(map, {'林间': 'lín jiān'});
      expect(batches.single, ['林间', '王菲']);
    });
  });

  test('should_detectHan_when_kanjiOrIdeographPresent', () {
    expect(containsHan('午後のソーダ'), isTrue);
    expect(containsHan('ヨルシカ'), isFalse);
    expect(containsHan('아이유'), isFalse);
    expect(containsHan('佐々木'), isTrue);
  });
}
