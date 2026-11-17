import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yoin/platform/continueon/continue_on_host.dart';
import 'package:yoin/platform/yoin_platform.dart';

/// The `yoin/continue_on` channel as Dart sees it (ContinueOnPlugin.ets on the other side).
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('yoin/continue_on');
  const codec = StandardMethodCodec();
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  late List<MethodCall> calls;

  setUp(() {
    calls = [];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return switch (call.method) {
        'localAddresses' => ['192.168.1.20', 7, 'fd00::5'],
        'deviceName' => '  HUAWEI MatePad Pro  ',
        _ => null,
      };
    });
  });

  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  /// ArkTS calling Dart.
  Future<Object?> fromArkTs(String method, [Object? arguments]) async {
    ByteData? reply;
    await messenger.handlePlatformMessage(
      channel.name,
      codec.encodeMethodCall(MethodCall(method, arguments)),
      (data) => reply = data,
    );
    return reply == null ? null : codec.decodeEnvelope(reply!);
  }

  test('should_passOfferAndSnapshot_when_dartOffersTheHandOff', () async {
    final host = ChannelContinueOnHost();
    await host.ready();
    await host.setOffered(true, snapshot: {'yoin.continueOn.v': 1});
    await host.setOffered(false);
    expect(calls.map((c) => c.method), ['ready', 'setOffered', 'setOffered']);
    expect(calls[1].arguments, {
      'offered': true,
      'snapshot': {'yoin.continueOn.v': 1},
    });
    expect(calls[2].arguments, {'offered': false, 'snapshot': null});
  });

  test('should_readAddressesAndName_when_asked', () async {
    final host = ChannelContinueOnHost();
    expect(await host.localAddresses(), ['192.168.1.20', 'fd00::5']);
    expect(await host.deviceName(), 'HUAWEI MatePad Pro');
  });

  test('should_deliverTheCursor_when_theDeviceWasContinued', () async {
    final host = ChannelContinueOnHost();
    final received = host.received.first;
    await fromArkTs('received', {'yoin.continueOn.v': 1, 'yoin.continueOn.track': 't1', 3: 'not a key'});
    expect(await received, {'yoin.continueOn.v': 1, 'yoin.continueOn.track': 't1'});
  });

  test('should_answerTheSystem_when_itAsksForTheHandOff', () async {
    final host = ChannelContinueOnHost();
    expect(await fromArkTs('handoff'), isNull);
    host.handoffRequests = () async => {'yoin.continueOn.v': 1};
    expect(await fromArkTs('handoff'), {'yoin.continueOn.v': 1});
    host.handoffRequests = () async => throw StateError('no song');
    expect(await fromArkTs('handoff'), isNull);
  });

  test('should_offerNothing_when_onWindows', () async {
    final p = YoinPlatform.forHost('windows');
    expect(p.continueOnHost, isA<NoContinueOnHost>());
    expect(p.continueOnHost.supported, isFalse);
    expect(await p.continueOnHost.localAddresses(), isEmpty);
    expect(YoinPlatform.forHost('ohos').continueOnHost, isA<ChannelContinueOnHost>());
  });

  test('should_keepOnlyStringKeys_when_readingEntries', () {
    expect(continueOnEntriesOf(null), isNull);
    expect(continueOnEntriesOf('x'), isNull);
    expect(continueOnEntriesOf({'a': 1, 2: 'b'}), {'a': 1});
  });
}
