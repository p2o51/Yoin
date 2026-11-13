import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/platform/storage/channel_stores.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('yoin/storage');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  late List<MethodCall> calls;
  late Map<String, String> kv;
  late Map<String, String> secrets;

  setUp(() {
    calls = [];
    kv = {};
    secrets = {};
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      final args = call.arguments as Map<Object?, Object?>;
      final key = args['key']! as String;
      final value = args['value'] as String?;
      switch (call.method) {
        case 'kvRead':
          return kv[key];
        case 'secretRead':
          return secrets[key];
        case 'kvWrite':
          value == null ? kv.remove(key) : kv[key] = value;
          return null;
        case 'secretWrite':
          value == null ? secrets.remove(key) : secrets[key] = value;
          return null;
      }
      throw MissingPluginException();
    });
  });

  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  test('should_sendKeyAndValue_when_kvWrite', () async {
    await const ChannelKeyValueStore().write('home.layout', 'grid');

    expect(calls.single.method, 'kvWrite');
    expect(calls.single.arguments, {'key': 'home.layout', 'value': 'grid'});
    expect(kv, {'home.layout': 'grid'});
  });

  test('should_sendNullValue_when_kvWriteDeletes', () async {
    kv['k'] = 'v';
    await const ChannelKeyValueStore().write('k', null);

    expect(calls.single.arguments, {'key': 'k', 'value': null});
    expect(kv, isEmpty);
  });

  test('should_returnStoredValueOrNull_when_kvRead', () async {
    kv['k'] = 'v';
    const store = ChannelKeyValueStore();

    expect(await store.read('k'), 'v');
    expect(await store.read('missing'), isNull);
    expect(calls.map((c) => c.method), ['kvRead', 'kvRead']);
  });

  test('should_useSecretMethods_when_secretStore', () async {
    const store = ChannelSecretStore();
    await store.write('profile/1', '{"password":"x"}');
    expect(await store.read('profile/1'), '{"password":"x"}');
    await store.write('profile/1', null);
    expect(await store.read('profile/1'), isNull);

    expect(calls.map((c) => c.method),
        ['secretWrite', 'secretRead', 'secretWrite', 'secretRead']);
    expect(calls[2].arguments, {'key': 'profile/1', 'value': null});
    expect(kv, isEmpty);
  });

  test('should_throwPlatformException_when_nativeFails', () async {
    messenger.setMockMethodCallHandler(channel, (call) async {
      throw PlatformException(code: 'storage', message: 'boom');
    });

    expect(const ChannelSecretStore().read('k'),
        throwsA(isA<PlatformException>()));
  });
}
